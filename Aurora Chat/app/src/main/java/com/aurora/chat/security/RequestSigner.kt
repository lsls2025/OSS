package com.aurora.chat.security

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名（防脚本裸调接口）。
 *
 * 算法与后端 client_sign.go 完全一致：
 *   canonical = method + "\n" + path + "\n" + sha256hex(body) + "\n" + ts + "\n" + nonce
 *   sign      = hex( HMAC_SHA256( SECRET, canonical ) )
 * 请求头：X-AC-Ts（秒级时间戳）/ X-AC-Nonce（一次性）/ X-AC-Sign（签名）。
 *
 * 安全设计：SECRET 为**运行时动态密钥**——每用户随机生成、仅服务端持有，
 * 登录时经 LoginResponse.sign_secret 下发，登录态恢复场景经
 * GET /api/auth/client-sign-secret 拉取。源码不内置任何固定密钥，
 * 反编译 APK 拿不到密钥，签名伪造成本显著提高。
 *
 * 自建部署提示：若后端仍启用固定/旧版签名兼容密钥，请通过 BuildConfig 或安全注入
 * 方式在运行时设置，切勿把真实密钥写回源码。
 */
object RequestSigner {

    /**
     * 旧的固定签名密钥占位符。真实值一律从服务端动态下发；
     * 留空表示不依赖固定密钥（推荐）。如自建后端需要固定密钥兼容，
     * 请在构建期注入，不要提交明文。
     */
    private const val FIXED_SIGN_SECRET = ""

    /** 运行时签名密钥（由登录响应 / 拉取接口下发；默认空） */
    @Volatile
    private var secret: String = FIXED_SIGN_SECRET

    private val fetchLock = Object()

    /** 是否已持有签名密钥 */
    fun hasSecret(): Boolean = secret.isNotEmpty()

    /** 设置/更新签名密钥（登录响应或拉取接口返回时调用；空值忽略） */
    fun setSecret(s: String?) {
        if (!s.isNullOrBlank()) secret = s
    }

    /** 清空（退出登录 / 被踢下线时调用）。清空后需重新登录或拉取动态密钥。 */
    fun clearSecret() {
        secret = FIXED_SIGN_SECRET
    }

    /**
     * 确保持有签名密钥；缺失时用登录态**同步**拉取一次后再签。
     * 用 raw HttpURLConnection 直拉该端点（它本身只要求登录态、不要求签名），
     * 不经 HttpClient 的签名链，避免递归；双检锁保证并发调用只发一次网络请求。
     * 返回是否已持有可用密钥。
     */
    fun ensureSecret(authToken: String?): Boolean {
        if (secret.isNotEmpty()) return true
        if (authToken.isNullOrEmpty()) return false
        synchronized(fetchLock) {
            if (secret.isNotEmpty()) return true
            val url = com.aurora.chat.data.api.AuroraApi.serverUrl.trimEnd('/') +
                "/api/auth/client-sign-secret"
            var conn: java.net.HttpURLConnection? = null
            return try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Authorization", "Bearer $authToken")
                }
                if (conn.responseCode != 200) return false
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = org.json.JSONObject(text)
                // 服务端封装为 {code, message, data:{sign_secret}}；容错兼容平铺返回
                val data = root.optJSONObject("data") ?: root
                val s = data.optString("sign_secret", "")
                if (s.isNotBlank()) {
                    secret = s
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            } finally {
                conn?.disconnect()
            }
        }
    }

    /** 生成签名请求头。method 为 HTTP 方法，path 为不含 query 的解码路径，body 为请求体（无则 null）。
     *  须先通过登录响应或拉取接口注入动态密钥后再签名。 */
    fun signHeaders(method: String, path: String, body: ByteArray?): Map<String, String> {
        val key = secret
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val bodyHash = sha256Hex(body ?: ByteArray(0))
        val canonical = buildString {
            append(method.uppercase())
            append('\n'); append(path)
            append('\n'); append(bodyHash)
            append('\n'); append(ts)
            append('\n'); append(nonce)
        }
        return mapOf(
            "X-AC-Ts" to ts,
            "X-AC-Nonce" to nonce,
            "X-AC-Sign" to hmacSha256Hex(key, canonical)
        )
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun hmacSha256Hex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
