package com.aurora.chat.host

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** HTTP 层：对接 site_server.py，携带 X-Auth-Token 与卡密请求头。 */
object HostApi {

    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 60_000

    private fun open(url: String, method: String, auth: Boolean): HttpURLConnection =
        (URL(HostConfig.BASE_URL + url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            if (auth) {
                setRequestProperty("X-Auth-Token", HostConfig.TOKEN)
                if (HostConfig.hasCard) {
                    setRequestProperty("X-Card-Key", HostConfig.cardKey)
                    setRequestProperty("X-Card-Password", HostConfig.cardPassword)
                }
            }
        }

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
            HostSession.markLoggedOut()
        }
        return body
    }

    private fun request(
        url: String, method: String, auth: Boolean,
        payload: Map<String, Any?>? = null, readTimeoutMs: Int = READ_TIMEOUT
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

    fun postJsonNoAuth(path: String, map: Map<String, Any?>): JSONObject =
        request(path, "POST", auth = false, payload = map)

    fun getJson(path: String): JSONObject = request(path, "GET", auth = true)

    // ============ 流式下载 / 上传 ============

    fun downloadRaw(path: List<String>, dest: File) {
        dest.outputStream().use { out -> downloadRawToStream(path, out) {} }
    }

    fun downloadRawToStream(path: List<String>, out: OutputStream, onProgress: (Float) -> Unit) {
        copyWithProgress(
            url = "/api/raw?path=" + URLEncoder.encode(path.joinToString("/"), "UTF-8"),
            method = "GET", readTimeoutMs = 120_000, out = out, onProgress = onProgress
        )
    }

    fun uploadStream(
        path: String, fileName: String,
        inputStream: InputStream, fileSize: Long, onProgress: (Float) -> Unit
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
        url: String, method: String, readTimeoutMs: Int,
        out: OutputStream, onProgress: (Float) -> Unit
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
        onProgress(1f)
    }

    private fun readErrorBody(conn: HttpURLConnection, fallback: String): String {
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val text = try {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (_: Exception) { "" }
        return try {
            val obj = JSONObject(text)
            if (obj.optBoolean("expired", false)) HostSession.markLoggedOut()
            obj.optString("error", fallback).ifBlank { fallback }
        } catch (_: Exception) {
            text.ifBlank { "$fallback（HTTP $code）" }.take(200)
        }
    }
}
