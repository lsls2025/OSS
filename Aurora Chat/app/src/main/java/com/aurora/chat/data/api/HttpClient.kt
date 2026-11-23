package com.aurora.chat.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import okhttp3.CertificatePinner
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
 * TLS：使用系统默认信任链；证书绑定 CERT_PINS 默认为空，由部署方按需配置。
 * 原实现是 trustAllCerts + 跳过主机名校验（等于对中间人完全敞开），现已移除。
 */
object HttpClient {

    private const val TAG = "HttpClient"
    private const val USER_AGENT = "AuroraChat-Android"

    /**
     * 证书绑定（CertificatePinner）：host -> 该主机证书 SPKI 的 SHA-256(base64)。
     * 开源版本不含任何真实证书指纹。自建部署如需启用证书绑定，请按下方命令自行计算并填入。
     *
     * 【计算方式】
     * openssl s_client -connect 主机:443 </dev/null 2>/dev/null \
     *   | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
     *   | openssl dgst -sha256 -binary | openssl enc -base64
     */
    private val CERT_PINS: List<Pair<String, String>> = listOf()

    private fun certificatePinner(): CertificatePinner = CertificatePinner.Builder().apply {
        CERT_PINS.forEach { (host, pin) -> add(host, pin) }
    }.build()

    // ⚠️ 遗留代码，已【不再被任何客户端使用】。原为信任任意证书（中间人漏洞的根源），现已改为
    //    系统默认信任链 + 证书绑定（CERT_PINS）。保留仅为便于对照历史实现，请勿再接回 Builder。
    @Suppress("unused")
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    // 整个进程只初始化一次
    // ⚠️ 遗留代码，配合上面的 trustAllCerts 使用，已【不再被任何客户端使用】。
    @Suppress("unused")
    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    /** 20MB HTTP 磁盘缓存：GET 响应带 Cache-Control 时由 OkHttp 复用，减少重复网络与解码。 */
    private var httpCache: Cache? = null

    lateinit var client: OkHttpClient
        private set

    /** 客户端是否已初始化。供入口探测等在 init 之前可能被调用的路径做保护。 */
    fun isReady(): Boolean = ::client.isInitialized

    /** 在 [AuroraChatApplication.onCreate] 中调用一次，建立连接池与 HTTP 磁盘缓存。 */
    fun init(context: Context) {
        if (::client.isInitialized) return
        httpCache = try {
            Cache(java.io.File(context.cacheDir, "http_cache"), 20L * 1024 * 1024)
        } catch (_: Exception) { null }
        client = OkHttpClient.Builder()
            .cache(httpCache)
            // OkHttp 默认 maxRequestsPerHost=5：批量发图时第 6 个起的请求会卡在 OkHttp 内部排队，
            // 一个字节都发不出去 → 上传进度停在 0% 直到前面的请求腾出槽位。
            // 与 MediaUploadManager 的上传并发（10）对齐，并给消息收发/头像等普通请求留余量。
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .certificatePinner(certificatePinner())
            // 主机名校验：已恢复 OkHttp 默认校验（原先是 hostnameVerifier { _, _ -> true }，等于完全跳过）
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .addInterceptor { chain -> guardCapture(); chain.proceed(chain.request()) }
            .build()
    }

    /** 反抓包拦截：抓包正在实际生效时（系统代理已设置 / VPN 已连接）直接抛 IOException，阻断一切网络请求。
     *  仅"设备上装了抓包 App"不阻断，详见 SecurityGuard.detect()。 */
    private fun guardCapture() {
        com.aurora.chat.security.SecurityGuard.detect()?.let { reason ->
            com.aurora.chat.security.SecurityGuard.logBlock(reason)
            throw IOException("安全拦截：检测到抓包环境（$reason）")
        }
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
            .certificatePinner(certificatePinner())
            // 主机名校验：已恢复 OkHttp 默认校验（原先是 hostnameVerifier { _, _ -> true }，等于完全跳过）
            .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
            .addInterceptor { chain -> guardCapture(); chain.proceed(chain.request()) }
            .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class MultipartFile(
        val partName: String,
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    /**
     * JSON 请求。2xx 返回响应体字符串；非 2xx 抛 [HttpStatusException]（携带状态码与响应体），
     * 空响应抛 [IOException]。调用方（AuroraApi）会把状态码转成面向用户的友好文案，状态码只进日志。
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
        // 请求签名（防脚本裸调接口）：canonical 与后端 client_sign.go 完全一致。
        // 密钥为运行时动态密钥（登录下发/按需拉取），缺失时先同步拉取再签。
        runCatching {
            val path = java.net.URI(url).path
            if (!path.isNullOrEmpty()) {
                com.aurora.chat.security.RequestSigner.ensureSecret(authToken)
                com.aurora.chat.security.RequestSigner
                    .signHeaders(method, path, body?.toByteArray(Charsets.UTF_8))
                    .forEach { (k, v) -> reqBuilder.header(k, v) }
            }
        }

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

    /**
     * 非 2xx 的 HTTP 响应。携带状态码与原始响应体，供上层解析出面向用户的友好提示，
     * 避免把 "HTTP 404" 这类状态码或原始响应体直接显示给用户（状态码仅在日志里体现）。
     */
    class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code")

    @Throws(IOException::class)
    private fun execute(request: Request): String {
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code, body)
            }
            if (body.isBlank()) throw IOException("服务器返回了空响应，请稍后重试")
            return body
        }
    }
}
