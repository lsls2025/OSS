package com.pm.manager.terminal

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 业务异常：后端返回 ok=false，或响应无法解析。message 可直接展示给用户。 */
class ApiException(message: String) : Exception(message)

/** 网络不可达 / 超时 / 连接被重置。与业务错误分开，界面可给出不同提示。 */
class NetworkException(message: String = "网络异常，请检查网络连接") : Exception(message)

/**
 * HTTP 层。
 *
 * 重构点：
 * - 原来 postJson / postJsonNoAuth / getJson 三段几乎一样的复制粘贴，各自维护超时和错误分支，
 *   现在收敛到一个 [request]；
 * - 原来 catch(Exception) 直接吞成 `{"ok":false}` 再让上层去猜，现在抛出 [NetworkException]/
 *   [ApiException]，调用方可以区分「断网」和「服务器拒绝」；
 * - 统一在任何出口识别 `expired` 并置位 [SessionState]，不再靠调用方各自检查；
 * - 流式接口在读错误体后正确关闭连接。
 */
object SiteApi {

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 60_000

    private fun open(url: String, method: String, auth: Boolean): HttpURLConnection =
        (URL(SiteConfig.BASE_URL + url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            if (auth) {
                setRequestProperty("X-Auth-Token", SiteConfig.TOKEN)
                if (SiteConfig.hasCard) {
                    setRequestProperty("X-Card-Key", SiteConfig.cardKey)
                    setRequestProperty("X-Card-Password", SiteConfig.cardPassword)
                }
            }
        }

    /** 统一的响应读取 + 失效识别 + 异常分类。 */
    private fun finish(conn: HttpURLConnection, fallback: String): JSONObject {
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = try {
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (e: Exception) {
            throw NetworkException(e.message ?: fallback)
        } finally {
            conn.disconnect()
        }
        val body = try {
            JSONObject(text)
        } catch (_: Exception) {
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == -1 || text.isBlank()) {
                throw NetworkException(if (code == HttpURLConnection.HTTP_UNAUTHORIZED) "鉴权失败" else fallback)
            }
            throw ApiException("$fallback（HTTP $code）")
        }
        if (body.optBoolean("expired", false) || code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            SessionState.markExpired()
        }
        return body
    }

    private fun request(
        url: String,
        method: String,
        auth: Boolean,
        payload: Map<String, Any?>? = null,
        readTimeoutMs: Int = READ_TIMEOUT
    ): JSONObject {
        val conn = try {
            open(url, method, auth).apply {
                readTimeout = readTimeoutMs
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
        } catch (e: Exception) {
            throw NetworkException(e.message ?: "无法连接服务器")
        }
        return try {
            if (payload != null) {
                val json = JSONObject()
                payload.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            }
            finish(conn, "请求失败")
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    fun postJson(path: String, map: Map<String, Any?>): JSONObject =
        request(path, "POST", auth = true, payload = map)

    /** 免鉴权请求，仅用于卡密激活。 */
    fun postJsonNoAuth(path: String, map: Map<String, Any?>): JSONObject =
        request(path, "POST", auth = false, payload = map)

    fun getJson(path: String): JSONObject = request(path, "GET", auth = true)

    // ==================== 流式下载 / 上传 ====================

    /** 下载原始文件到目标文件。 */
    fun downloadRaw(path: List<String>, dest: File) {
        dest.outputStream().use { out -> downloadRawToStream(path, out) {} }
    }

    /** 流式下载原始文件，带实时进度（0~1）。 */
    fun downloadRawToStream(path: List<String>, out: OutputStream, onProgress: (Float) -> Unit) {
        copyWithProgress(
            url = "/api/raw?path=" + URLEncoder.encode(path.joinToString("/"), "UTF-8"),
            method = "GET",
            readTimeoutMs = 120_000,
            out = out,
            onProgress = onProgress
        )
    }

    /** 流式下载一份备份 zip。 */
    fun downloadBackupToStream(name: String, out: OutputStream, onProgress: (Float) -> Unit) {
        val conn = open("/api/backup-download", "POST", auth = true).apply {
            readTimeout = 300_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use {
                it.write(JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8))
            }
            pump(conn, out, onProgress, "下载失败")
        } finally {
            conn.disconnect()
        }
        out.flush()
    }

    /** 流式上传：能拿到大小走定长（进度精确），拿不到走 chunked（否则协议错误卡死）。 */
    fun uploadStream(
        path: String,
        fileName: String,
        inputStream: InputStream,
        fileSize: Long,
        onProgress: (Float) -> Unit
    ) {
        val url = "/api/upload-stream?path=" + URLEncoder.encode(path, "UTF-8") +
            "&file=" + URLEncoder.encode(fileName, "UTF-8")
        val conn = open(url, "POST", auth = true).apply {
            readTimeout = 600_000
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            if (fileSize > 0) setFixedLengthStreamingMode(fileSize) else setChunkedStreamingMode(65536)
        }
        try {
            conn.outputStream.use { out ->
                val buf = ByteArray(65536)
                var sent = 0L
                while (true) {
                    val n = inputStream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    if (fileSize > 0) onProgress((sent.toFloat() / fileSize).coerceIn(0f, 1f))
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(readErrorBody(conn, "上传失败"))
        } finally {
            conn.disconnect()
        }
    }

    private fun copyWithProgress(
        url: String,
        method: String,
        readTimeoutMs: Int,
        out: OutputStream,
        onProgress: (Float) -> Unit
    ) {
        val conn = open(url, method, auth = true).apply { readTimeout = readTimeoutMs }
        try {
            pump(conn, out, onProgress, "下载失败")
        } finally {
            conn.disconnect()
        }
    }

    private fun pump(conn: HttpURLConnection, out: OutputStream, onProgress: (Float) -> Unit, fallback: String) {
        val code = conn.responseCode
        if (code !in 200..299) throw ApiException(readErrorBody(conn, fallback))
        val total = conn.contentLengthLong
        var read = 0L
        conn.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                read += n
                if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        // 服务器没给 Content-Length 时，结束时补一个 100%，否则进度条会永远停在 0
        onProgress(1f)
    }

    private fun readErrorBody(conn: HttpURLConnection, fallback: String): String {
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val text = try {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return try {
            val obj = JSONObject(text)
            if (obj.optBoolean("expired", false)) SessionState.markExpired()
            obj.optString("error", fallback).ifBlank { fallback }
        } catch (_: Exception) {
            text.ifBlank { "$fallback（HTTP $code）" }.take(200)
        }
    }
}
