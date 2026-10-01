package com.aurora.chat.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 进程级 OkHttp 单例。
 *
 * 相比原 HttpURLConnection 实现的两大问题：
 *  1) 每次请求都 `SSLContext.getInstance("TLS").init(...)` + `new SecureRandom()`（CPU 敏感），
 *     这里只初始化一次；
 *  2) HttpURLConnection 无连接池，每个请求都要完整 TCP + TLS 握手，这里通过连接池复用，
 *     高频路径（会话列表 / 消息拉取 / 头像）首屏延迟显著下降。
 *
 * 保留原实现对自签名证书的信任策略（服务器使用自签名证书，去掉会导致无法连接）。
 */
object HttpClient {

    private const val TAG = "HttpClient"
    private const val USER_AGENT = "AuroraChat-Android"

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    // 整个进程只初始化一次
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    /** 20MB HTTP 磁盘缓存：GET 响应带 Cache-Control 时由 OkHttp 复用，减少重复网络与解码。 */
    private var httpCache: Cache? = null

    lateinit var client: OkHttpClient
        private set

    /** 在 [AuroraChatApplication.onCreate] 中调用一次，建立连接池与 HTTP 磁盘缓存。 */
    fun init(context: Context) {
        if (::client.isInitialized) return
        httpCache = try {
            Cache(java.io.File(context.cacheDir, "http_cache"), 20L * 1024 * 1024)
        } catch (_: Exception) { null }
        client = OkHttpClient.Builder()
            .cache(httpCache)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * 构建一个独立的长读超时客户端（复用进程级自签名信任配置与 SSL）。
     * 用于 AI 图片/视频生成等慢接口：生成耗时可达 1 分钟以上，共享客户端的 15s 读超时会提前 Read timed out。
     * 调用方应缓存复用，不要每请求新建。
     */
    fun newClientWithTimeouts(readSeconds: Long, connectSeconds: Long = 30): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
            .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class MultipartFile(
        val partName: String,
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    /**
     * JSON 请求。2xx 返回响应体字符串；非 2xx 或空响应抛 [IOException]（message 形如 "[HTTP 400] ..."）。
     * 调用方（AuroraApi.safeCall）会按异常类型转换为友好错误。
     */
    @Throws(IOException::class)
    suspend fun jsonRequest(
        method: String,
        url: String,
        body: String? = null,
        authToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json; charset=utf-8")
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")

        val reqBody = body?.toRequestBody(jsonMediaType)
        when (method.uppercase()) {
            "GET" -> reqBuilder.get()
            "DELETE" -> reqBuilder.delete()
            "POST" -> reqBuilder.post(reqBody ?: "".toRequestBody(jsonMediaType))
            "PUT" -> reqBuilder.put(reqBody ?: "".toRequestBody(jsonMediaType))
            else -> reqBuilder.method(method.uppercase(), reqBody ?: "".toRequestBody(jsonMediaType))
        }
        execute(reqBuilder.build())
    }

    /**
     * 与 [jsonRequest] 类似，但**不**因非 2xx 抛异常，直接返回响应体（空响应返回 ""）。
     * 用于原 [java.net.HttpURLConnection] 实现中调用方自行解析业务 code 的场景（如 ApiClient）。
     */
    @Throws(IOException::class)
    suspend fun jsonRequestAllowError(
        method: String,
        url: String,
        body: String? = null,
        authToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json; charset=utf-8")
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")

        val reqBody = body?.toRequestBody(jsonMediaType)
        when (method.uppercase()) {
            "GET" -> reqBuilder.get()
            "DELETE" -> reqBuilder.delete()
            "POST" -> reqBuilder.post(reqBody ?: "".toRequestBody(jsonMediaType))
            "PUT" -> reqBuilder.put(reqBody ?: "".toRequestBody(jsonMediaType))
            else -> reqBuilder.method(method.uppercase(), reqBody ?: "".toRequestBody(jsonMediaType))
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            resp.body?.string() ?: ""
        }
    }

    /** HEAD 请求，返回 Content-Length（失败返回 -1） */
    @Throws(IOException::class)
    suspend fun headContentLength(url: String): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).head().build()
        client.newCall(req).execute().use { resp ->
            resp.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }

    @Throws(IOException::class)
    suspend fun downloadBytes(url: String, authToken: String? = null): ByteArray = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.bytes() ?: throw IOException("空响应")
        }
    }

    /**
     * 流式下载到文件（边收边写，单块 64KB，不把整文件缓冲进内存），避免大图/大文件整字节数组导致 OOM。
     * 支持断点续传：[append]=true 且目标文件已存在时，从已下载字节处发 `Range` 请求续传，[onProgress] 上报进度。
     * 返回最终已下载字节数。
     */
    @Throws(IOException::class)
    suspend fun downloadToFile(
        url: String,
        dest: java.io.File,
        authToken: String? = null,
        append: Boolean = false,
        onProgress: ((received: Long, total: Long) -> Unit)? = null
    ): Long = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")
        var downloaded = 0L
        if (append && dest.exists()) {
            downloaded = dest.length()
            if (downloaded > 0) reqBuilder.header("Range", "bytes=$downloaded-")
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw IOException("HTTP ${resp.code}")
            val total = (resp.header("Content-Length")?.toLongOrNull() ?: 0L) + downloaded
            val body = resp.body ?: throw IOException("空响应")
            dest.parentFile?.mkdirs()
            java.io.FileOutputStream(dest, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                while (body.source().read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    downloaded += read
                    if (total > downloaded) onProgress?.invoke(downloaded, total)
                }
            }
            downloaded
        }
    }

    @Throws(IOException::class)
    suspend fun multipart(
        url: String,
        fields: Map<String, String> = emptyMap(),
        files: List<MultipartFile> = emptyList(),
        authToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        files.forEach { f ->
            builder.addFormDataPart(
                f.partName, f.fileName,
                f.bytes.toRequestBody(f.mimeType.toMediaType())
            )
        }
        val reqBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .post(builder.build())
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")
        execute(reqBuilder.build())
    }

    /**
     * 与 [multipart] 类似，但不因非 2xx 抛异常，直接返回响应体（空响应返回 ""）。
     * 用于原 [java.net.HttpURLConnection] 实现中调用方自行解析业务 code 的多文件提交场景。
     */
    @Throws(IOException::class)
    suspend fun multipartAllowError(
        url: String,
        fields: Map<String, String> = emptyMap(),
        files: List<MultipartFile> = emptyList(),
        authToken: String? = null
    ): String = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        files.forEach { f ->
            builder.addFormDataPart(f.partName, f.fileName, f.bytes.toRequestBody(f.mimeType.toMediaType()))
        }
        val reqBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .post(builder.build())
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")
        client.newCall(reqBuilder.build()).execute().use { resp ->
            resp.body?.string() ?: ""
        }
    }

    /**
     * 流式多部分上传（单文件）+ 进度回调。直接把 [input] 通过 OkHttp 写向网络，
     * 边读边上报进度（不先把整个文件缓冲进内存），用于大文件上传场景。
     * 若 [totalBytes] > 0 则作为进度分母，否则不触发进度（长度未知）。
     */
    @Throws(IOException::class)
    suspend fun uploadStreamWithProgress(
        url: String,
        fields: Map<String, String> = emptyMap(),
        input: InputStream,
        partName: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long = -1,
        authToken: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        val fileBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength() = if (totalBytes > 0L) totalBytes else -1L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(65536)
                var sent = 0L
                var read: Int
                try {
                    while (input.read(buf).also { read = it } != -1) {
                        sink.write(buf, 0, read)
                        sent += read
                        if (totalBytes > 0L) onProgress?.invoke(sent, totalBytes)
                    }
                } finally {
                    input.close()
                }
            }
        }
        builder.addFormDataPart(partName, fileName, fileBody)
        val reqBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .post(builder.build())
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")
        execute(reqBuilder.build())
    }

    /**
     * 带进度回调的多部分上传（单文件）。用于需要向 UI/通知上报上传进度的场景。
     * 进度按**文件字节**上报（sent/total），与 multipart 边界开销无关，足够驱动进度条。
     */
    @Throws(IOException::class)
    suspend fun uploadWithProgress(
        url: String,
        fields: Map<String, String> = emptyMap(),
        file: MultipartFile,
        authToken: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> builder.addFormDataPart(k, v) }
        val fileBody = object : RequestBody() {
            override fun contentType() = file.mimeType.toMediaType()
            override fun contentLength() = file.bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                val chunk = 8192
                var offset = 0
                val total = file.bytes.size.toLong()
                var sent = 0L
                while (offset < file.bytes.size) {
                    val n = if (chunk <= file.bytes.size - offset) chunk else file.bytes.size - offset
                    sink.write(file.bytes, offset, n)
                    offset += n
                    sent += n
                    onProgress?.invoke(sent, total)
                }
            }
        }
        builder.addFormDataPart(file.partName, file.fileName, fileBody)
        val reqBuilder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .post(builder.build())
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")
        execute(reqBuilder.build())
    }

    /**
     * 带进度回调的 JSON 上传。与 [jsonRequest] 相同契约（非 2xx 抛 [IOException]），
     * 但用自定义 RequestBody 边写边上报**真实字节进度**（sent/total，写向网络 socket 的字节）。
     * 头像上传体积小，进度会很快到 100%，最终成败仍以服务端响应为准，两者不冲突。
     */
    @Throws(IOException::class)
    suspend fun postJsonWithProgress(
        url: String,
        jsonBody: String,
        authToken: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val bytes = jsonBody.toByteArray()
        val totalBytes = bytes.size.toLong()
        val reqBody = object : RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = totalBytes
            override fun writeTo(sink: BufferedSink) {
                val chunk = 8192
                var offset = 0
                var sent = 0L
                while (offset < bytes.size) {
                    val n = if (chunk <= bytes.size - offset) chunk else bytes.size - offset
                    sink.write(bytes, offset, n)
                    offset += n
                    sent += n
                    onProgress?.invoke(sent, totalBytes)
                }
            }
        }
        val reqBuilder = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(reqBody)
        if (!authToken.isNullOrEmpty()) reqBuilder.header("Authorization", "Bearer $authToken")
        execute(reqBuilder.build())
    }

    @Throws(IOException::class)
    private fun execute(request: Request): String {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("[HTTP ${resp.code}] ${body.take(300)}")
            }
            if (body.isBlank()) throw IOException("服务器返回空响应 (HTTP ${resp.code})")
            return body
        }
    }
}
