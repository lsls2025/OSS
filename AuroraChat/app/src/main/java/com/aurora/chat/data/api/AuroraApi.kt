package com.aurora.chat.data.api

import com.aurora.chat.AuroraChatApplication
import com.aurora.chat.CryptoUtil
import com.aurora.chat.ModelLogger

import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject


/**
 * 业务/接口错误：message 是**面向用户的友好文案**，UI 直接展示即可。
 * 不再把 "HTTP 404 / 403" 这类状态码或原始响应体写进 message（那些只进日志）。
 */
class ApiException(message: String, val httpCode: Int = 0) : Exception(message)

/** 关注关系快照：粉丝数、关注数、我是否已关注对方、对方是否关注我。 */
data class FollowStats(
    val fans: Int = 0,
    val following: Int = 0,
    val isFollowing: Boolean = false,
    /** 对方是否关注我 —— 与 isFollowing 同时为真才是「互相关注」 */
    val followsYou: Boolean = false
)

object AuroraApi {

    /** 脱敏响应日志：剔除 token / session_key / sessionKey / password 等字段明文，防止敏感凭据进入日志 */
    private fun maskSensitiveInBody(s: String): String = try {
        s.replace(Regex("(\"(?:token|session_key|sessionKey|password)\"\\s*:\\s*\")[^\"]*(\")"), "$1***$2")
    } catch (_: Exception) {
        s
    }

    // 可配置的服务器地址（未来可从设置中修改）
    //
    // 【全站 HTTPS】客户端只走 https://www.example.com（443），由 nginx 把
    // /api/、/ws、/qrlogin、/tools/、/chat-media/、/fd/ 反代到 127.0.0.1:5004。
    // 证书由部署方自行配置；如需证书绑定，在 HttpClient.CERT_PINS 中填入自建证书指纹。
    // 因此抓包即使装了自签 CA 也解不开。
    //
    // 【部署顺序】必须先给 443 配好上述反代，再装新版客户端；反代没上就装，App 会直接连不上。
    // 后端的 5004 明文端口只应监听本机 / 内网，公网必须关掉（否则密码经 5004 仍是明文）。
    var serverUrl = "https://www.example.com"

    /**
     * 【五子棋 WebSocket 地址】与上面的 serverUrl **各占一项、互不推导**——二者是两条不同的入口，不要合并成一个变量，
     * 也不要用 serverUrl 去拼：
     *  · serverUrl   —— 主站 REST 接口（/api/...），经 nginx 443 反代；
     *  · gomokuWsUrl —— 五子棋对战的 WS 入口（/ws 挂在我们自己的 Go 后端上），
     *    同样经 nginx 443 反代，所以是 wss:// 且不带端口。反代处必须带 Upgrade/Connection 头。
     * 调整服务器时两处都要看，别只改一处。
     */
    var gomokuWsUrl = "wss://www.example.com/ws"

    // JWT Token（登录/注册后设置）
    var authToken: String? = null

    /**
     * 踢下线事件携带的结构化信息。
     * - reason: 服务端原始提示文案（"账号已被管理员踢出，请重新登录" / "账号已在其他设备登录"）
     * - account: 被踢账号标识（邮箱优先，否则 "ID x"），在 triggerKickOff 时就地捕获，避免退登录后被清空
     * - ip / loginTs: 仅"新设备登录"场景由服务端推送带来；手动踢出为空，前端走通用权威文案
     */
    data class KickOffInfo(
        val reason: String,
        val account: String,
        val ip: String = "",
        val loginTs: Long = 0L
    )

    // 单设备登录踢出事件：被其他设备登录 / 管理员踢出时触发
    private val _kickedOff = MutableStateFlow<KickOffInfo?>(null)
    val kickedOffEvent: StateFlow<KickOffInfo?> = _kickedOff

    /**
     * 判断接口返回的错误文案是否为"被踢下线"信号。
     * 覆盖两种服务端口径：
     *  - "账号已在其他设备登录"（旧 token 被新登录顶掉；当前未启用自动踢，仅作兜底）
     *  - "账号已被管理员踢出"（开发者在后台手动踢人）
     * 命中后上层会清空 token、重置登录态并切回登录页。
     */
    private fun isKickOffMessage(msg: String): Boolean {
        return msg.contains("在其他设备登录") || msg.contains("已被管理员踢出")
    }

    /**
     * 消费踢下线事件后必须复位。
     * 否则同一句文案的第二次踢出会因为 StateFlow 值未变化而不再发射，
     * 出现"第一次能踢、第二次踢不掉"的半残现象。
     */
    fun clearKickOffEvent() {
        _kickedOff.value = null
    }

    /**
     * 由 TCP 主动推送（account_kicked）触发踢下线。
     * 与 HTTP 中间件兜底共用同一个 _kickedOff StateFlow，单一出口，保证行为完全一致。
     */
    fun triggerKickOff(reason: String, ip: String = "", loginTs: Long = 0L) {
        val account = currentUserEmail.ifBlank { "ID $currentUserId" }
        _kickedOff.value = KickOffInfo(reason, account, ip, loginTs)
    }

    // 当前登录用户 ID
    var currentUserId: Long = 0

    // 当前登录用户的邮箱
    var currentUserEmail: String = ""

    // 当前登录用户的名称（登录后从服务端返回）
    var currentUserName: String = ""

    // 当前登录用户的签名（登录后立即从服务端获取）
    var currentUserSignature: String = ""

    // 当前登录用户的邮箱是否已验证（快速注册用户为 false）
    var currentUserEmailVerified: Boolean = false

    // 当前登录用户的 QQ 号（用于开发者身份识别与资料完善）
    var currentUserQQ: String = ""

    // 服务端下发的用户级 AES-256 传输会话密钥（base64，32 字节）。
    // 客户端发送消息前用它加密内容（前缀 sess:v1:），消除传输阶段明文。
    var sessionKey: String = ""

    // 服务端判定的开发者身份（登录/注册/完善资料响应里的 is_developer）。
    // 本地硬编码 QQ/邮箱只是兜底，**服务端结果才是权威**：本地 QQ/邮箱缺失或改动时，
    // 仍能正确显示开发者管理入口与全部 Tab（避免「明明是开发者却无权访问」）。
    @Volatile
    var isDeveloperAccount: Boolean = false

    /** 记录服务端开发者判定结果（响应里没有该字段时不动，避免误清） */
    private fun applyDeveloperFlag(d: JSONObject) {
        if (d.has("is_developer")) {
            isDeveloperAccount = d.optBoolean("is_developer", false)
        }
    }

    // 登录后是否需要完善资料（临时标志，doLogin 读取后决定是否弹出强制完善弹窗）
    var needsProfileCompletion: Boolean = false

    // 当前登录的服务器信息（服务器板块登录后设置）
    var currentServerDomain: String = ""
    var currentServerName: String = ""

    // 应用版本号（通过反射获取 BuildConfig，避免编译时依赖）
    private val appVersionName: String by lazy {
        try {
            val cls = Class.forName("com.aurora.chat.BuildConfig")
            cls.getField("VERSION_NAME").get(null) as? String ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // 公钥内存缓存
    private val publicKeyCache = mutableMapOf<Long, String>()

    // 错误 toast 去重：记录最后显示的错误和时间
    private var lastToastMessage: String = ""
    private var lastToastTime: Long = 0
    private const val TOAST_DEBOUNCE_MS = 15000L

    /** 显示错误 toast，同一错误 15 秒内不重复显示 */
    fun showErrorToast(context: android.content.Context, message: String) {
        val now = System.currentTimeMillis()
        if (message == lastToastMessage && now - lastToastTime < TOAST_DEBOUNCE_MS) {
            return
        }
        lastToastMessage = message
        lastToastTime = now
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ==================== 入口地址自愈 ====================

    /**
     * 过渡期开关：HTTPS 入口不可用时，是否允许退回明文 5004。
     *
     * 为什么需要它：客户端切到 https 后，所有请求都打到 nginx 443，而 443 只有在
     * 配好 /api/ 反代之后才会转给 127.0.0.1:5004。反代没配的那一刻，整站 404，
     * 表现为「App 直接断网」——这个坑已经踩过一次。
     *
     * 等 443 反代验证通过、旧版本用户基本升级完，把这里改成 false，
     * 回退通道即彻底关闭（届时 HTTPS 不通就应当报错，而不是悄悄降级）。
     * 注意：降级通道存在期间，能主动阻断 HTTPS 的攻击者可以把你压回明文——
     * 这正是最后必须把它关掉的原因。
     */
    private const val ALLOW_HTTP_FALLBACK = false

    private const val HTTP_FALLBACK_URL = "http://www.example.com:5004"
    private const val HTTP_FALLBACK_WS = "ws://www.example.com:5004/ws"

    @Volatile
    private var baseUrlResolved = false
    private val baseUrlLock = Any()

    /**
     * 探测当前入口是否可用，只探测一次并缓存在内存里。
     *
     * 判定口径：https 的 /api/health 能返回 2xx/3xx 才算通。
     * 反代没配时 nginx 会返回 404（站点存在但没有这个 location），同样判为不通——
     * 这一点很关键，只看"能不能握手"是不够的，证书好好的也可能是个 404。
     *
     * 进程重启（升级安装后）会重新探测，所以 nginx 配好后无需重新打包，下次启动自动切回 https。
     */
    fun ensureBaseUrl() {
        if (baseUrlResolved) return
        synchronized(baseUrlLock) {
            if (baseUrlResolved) return
            baseUrlResolved = true
            if (!serverUrl.startsWith("https://")) return
            val reachable = try {
                // 必须用带证书绑定的同一个 OkHttp 客户端探测。
                // 之前用 HttpURLConnection 探测，它不校验 CERT_PINS：证书指纹写错时探测返回 200、
                // 真正请求却全部被 OkHttp 拒绝，自愈逻辑形同虚设，App 表现为彻底连不上。
                if (!HttpClient.isReady()) {
                    false
                } else {
                    val req = okhttp3.Request.Builder()
                        .url("$serverUrl/api/health")
                        .header("User-Agent", "AuroraChat-Android")
                        .build()
                    HttpClient.client.newCall(req).execute().use { resp -> resp.code in 200..399 }
                }
            } catch (e: Exception) {
                android.util.Log.w("AuroraApi", "HTTPS 入口探测失败: ${e.message}")
                false
            }
            if (!reachable) {
                if (ALLOW_HTTP_FALLBACK) {
                    serverUrl = HTTP_FALLBACK_URL
                    gomokuWsUrl = HTTP_FALLBACK_WS
                    android.util.Log.w("AuroraApi", "HTTPS 入口不可用，已回退明文端口（nginx 443 反代未就绪）")
                } else {
                    android.util.Log.e("AuroraApi", "HTTPS 入口不可用，且已禁止回退")
                }
            }
        }
    }

    // ==================== 核心请求方法 ====================

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = false
): JSONObject = withContext(Dispatchers.IO) {
        // 每次请求前确保入口已定（首次调用才会真的探测，之后是内存读）
        ensureBaseUrl()
        val url = "$serverUrl$path"
        val raw = try {
            HttpClient.jsonRequest(method, url, body?.toString(), if (authenticated) authToken else null)
        } catch (e: HttpClient.HttpStatusException) {
            // 状态码与原始响应体只写日志，交给 UI 的是友好文案
            android.util.Log.w("AuroraApi", "HTTP ${e.code} path=$path body=${maskSensitiveInBody(e.body).take(300)}")
            throw ApiException(friendlyHttpMessage(e.code, e.body), e.code)
        } catch (e: javax.net.ssl.SSLPeerUnverifiedException) {
            // 证书绑定失败（服务器换证书了 / 私钥变了 → 公钥指纹变），与「网络不好」完全两回事。
            // OkHttp 的异常消息里自带 "Certificate pinning failure" 和实际收到的指纹，
            // 打出来就能立刻知道该把 CERT_PINS 改成什么，不用再从头排查。
            val detail = e.message ?: ""
            android.util.Log.e("AuroraApi", "TLS 证书校验失败 path=$path detail=$detail")
            throw if (detail.contains("pin", ignoreCase = true)) {
                ApiException("服务器安全证书已更新，请升级到最新版本后重试")
            } else {
                ApiException("安全连接建立失败，请检查网络后重试")
            }
        } catch (e: java.io.IOException) {
            val msg = e.message ?: "请求失败"
            throw if (msg.startsWith("[HTTP")) Exception(msg) else Exception("请求失败: $msg")
        }
        try {
            JSONObject(raw)
        } catch (e: Exception) {
            android.util.Log.e("AuroraApi", "JSON解析失败: HTTP path=$path response=${maskSensitiveInBody(raw)}")
            throw ApiException("服务器返回数据异常，请稍后重试")
        }
    }

    /** 安全地执行 API 调用，统一处理网络异常，并记录到错误报告系统 */
    private suspend fun <T> safeCall(
        authenticated: Boolean = false,
        block: suspend () -> T
    ): ApiResult<T> = try {
        ApiResult(true, "成功", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() })
    } catch (e: android.os.NetworkOnMainThreadException) {
        android.util.Log.e("AuroraApi", "API call failed: 主线程网络请求", e)
        com.aurora.chat.ErrorReporter.error("API", "主线程网络请求被拦截", e)
        ApiResult(false, "内部错误：网络请求被主线程拦截")
    } catch (e: java.net.ConnectException) {
        android.util.Log.e("AuroraApi", "API call failed: 连接被拒绝", e)
        com.aurora.chat.ErrorReporter.error("Network", "服务器连接被拒绝", e)
        ApiResult(false, "无法连接服务器，请检查网络或联系管理员 (连接被拒绝)")
    } catch (e: java.net.SocketTimeoutException) {
        android.util.Log.e("AuroraApi", "API call failed: 连接超时", e)
        com.aurora.chat.ErrorReporter.error("Network", "服务器响应超时", e)
        ApiResult(false, "服务器响应超时，请检查网络或稍后重试")
    } catch (e: java.net.UnknownHostException) {
        android.util.Log.e("AuroraApi", "API call failed: 无法解析主机", e)
        com.aurora.chat.ErrorReporter.error("Network", "无法解析服务器地址", e)
        ApiResult(false, "无法解析服务器地址，请检查网络连接")
    } catch (e: javax.net.ssl.SSLException) {
        android.util.Log.e("AuroraApi", "API call failed: SSL错误", e)
        com.aurora.chat.ErrorReporter.error("SSL", "安全连接失败: ${e.message}", e)
        ApiResult(false, "安全连接失败，请检查服务器配置")
    } catch (e: ApiException) {
        // 业务/接口错误：message 已是友好文案，直接返回，不再二次拼接"请求失败"或状态码
        val msg = e.message ?: "请求失败"
        android.util.Log.e("AuroraApi", "API call failed: $msg")
        if (isKickOffMessage(msg)) {
            authToken = null
            currentUserId = 0
            com.aurora.chat.security.RequestSigner.clearSecret()
            triggerKickOff(msg)
        } else {
            com.aurora.chat.ErrorReporter.error("API", "业务错误: $msg", e)
        }
        ApiResult(false, msg, httpCode = e.httpCode)
    } catch (e: java.io.IOException) {
        val detail = e.localizedMessage ?: "网络连接失败"
        android.util.Log.e("AuroraApi", "API call failed: $detail", e)
        com.aurora.chat.ErrorReporter.error("Network", "网络错误: $detail", e)
        ApiResult(false, "网络错误: $detail")
    } catch (e: Exception) {
        val msg = e.localizedMessage ?: "未知错误"
        android.util.Log.e("AuroraApi", "API call failed: $msg", e)
        // 单设备登录：检测被踢下线信号 → 清除 token 并通知 UI
        if (isKickOffMessage(msg)) {
            authToken = null
            currentUserId = 0
            com.aurora.chat.security.RequestSigner.clearSecret()
            triggerKickOff(msg)
        } else {
            com.aurora.chat.ErrorReporter.error("API", "请求失败: $msg", e)
        }
        // 已是带 HTTP 状态码的结构化错误（如 [HTTP 400] ...）则不再包一层"请求失败"
        val finalMsg = if (msg.startsWith("[HTTP")) msg else "请求失败: $msg"
        ApiResult(false, finalMsg)
    }

    /** 拉取当前用户的客户端签名密钥（登录态恢复 / 服务端重启后密钥变更时调用）。
     *  密钥每用户随机生成、仅服务端持有，不落盘；失败静默（HttpClient 签名时还会按需重试）。
     *  同一响应顺带带回传输会话密钥 session_key：旧版本登录链路漏存该字段的设备在此补齐，
     *  否则 TCP 长连接会一直以「认证失败: session_key 校验不通过」被服务端拒绝。 */
    suspend fun refreshClientSignSecret() {
        val token = authToken ?: return
        try {
            val json = request("GET", "/api/auth/client-sign-secret", authenticated = true)
            val data = json.optJSONObject("data") ?: return
            com.aurora.chat.security.RequestSigner.setSecret(data.optString("sign_secret", ""))
            data.optString("session_key", "").takeIf { it.isNotEmpty() }?.let { sessionKey = it }
        } catch (_: Exception) { }
    }

    // ==================== 辅助解析 ====================

    private fun checkSuccess(json: JSONObject): Boolean {
        val code = json.optInt("code", 500)
        if (code != 200) {
            val message = json.optString("message", "").trim()
            android.util.Log.w("AuroraApi", "业务失败 code=$code message=$message")
            // 优先用服务端返回的 message；缺失时按状态码给一个通用友好提示
            throw ApiException(message.ifBlank { friendlyHttpMessage(code, "") }, code)
        }
        return true
    }

    /** 把 HTTP 状态码 / 服务端响应体转成面向用户的友好提示（状态码只出现在日志里，不给用户看）。 */
    private fun friendlyHttpMessage(code: Int, body: String): String {
        val serverMsg = try {
            if (body.isNotBlank()) JSONObject(body).optString("message", "").trim() else ""
        } catch (_: Exception) {
            ""
        }
        if (serverMsg.isNotEmpty()) return serverMsg
        return when (code) {
            400 -> "请求有误，请检查后重试"
            401 -> "登录状态已失效，请重新登录"
            403 -> "没有权限执行该操作"
            404 -> "请求的内容不存在或已被删除"
            405 -> "服务器不支持该操作"
            408 -> "请求超时，请重试"
            413 -> "内容过大，请精简后重试"
            429 -> "操作太频繁，请稍后再试"
            in 500..599 -> "服务器开小差了，请稍后重试"
            else -> "网络或服务异常，请稍后重试"
        }
    }

    private fun parseUserList(data: JSONArray): List<UserInfo> {
        val list = mutableListOf<UserInfo>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            list.add(UserInfo(
                id = obj.optLong("id"),
                email = obj.optString("email"),
                username = obj.optString("username"),
                qqNumber = obj.optString("qq_number", ""),
                createdAt = obj.optLong("created_at"),
                lastActiveAt = obj.optLong("updated_at"),
                hideEmail = obj.optInt("hide_email", 0),
                hideQQ = obj.optInt("hide_qq", 0),
                signature = obj.optString("signature", ""),
                onlineTimeSeconds = obj.optLong("online_time_seconds", 0),
                wordCount = obj.optLong("word_count", 0),
                regIp = obj.optString("reg_ip", "")
            ))
        }
        return list
    }

    private fun parseMessageList(data: JSONArray): List<MessageInfo> {
        val list = mutableListOf<MessageInfo>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            // @ 目标映射随消息下发：解析后同时登记到 MentionStore，渲染层按消息 id 直接取
            val mentions = com.aurora.chat.data.store.MentionStore.parse(obj.optString("mentions", ""))
            com.aurora.chat.data.store.MentionStore.put(obj.optLong("id"), mentions)
            list.add(MessageInfo(
                id = obj.optLong("id"),
                fromUserId = obj.optLong("from_user_id"),
                toUserId = obj.optLong("to_user_id"),
                content = obj.optString("content"),
                createdAt = obj.optLong("created_at"),
                fromUserName = obj.optString("username", ""),
                isRevoked = obj.optInt("is_revoked", 0),
                replyToText = obj.optString("reply_to_text", ""),
                replyToSender = obj.optString("reply_to_sender", ""),
                replyToId = obj.optLong("reply_to_id", 0),
                mediaType = obj.optString("media_type", ""),
                mediaUrl = obj.optString("media_url", ""),
                flashDuration = obj.optInt("flash_duration", 0),
                targetName = obj.optString("to_user_name", ""),
                msgType = obj.optString("msg_type", ""),
                broadcastTaskId = obj.optLong("broadcast_task_id", 0),
                contactSource = obj.optString("contact_source", ""),
                mediaWidth = obj.optInt("media_width", 0),
                mediaHeight = obj.optInt("media_height", 0),
                clientSendAt = obj.optLong("client_send_at", 0),
                mentions = mentions
            ))
        }
        return list
    }

    private fun parseFriendRequestList(data: JSONArray): List<FriendRequestInfo> {
        val list = mutableListOf<FriendRequestInfo>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            list.add(FriendRequestInfo(
                id = obj.optLong("id"),
                fromUserId = obj.optLong("from_user_id"),
                fromEmail = obj.optString("from_email"),
                fromUsername = obj.optString("from_username"),
                toUserId = obj.optLong("to_user_id"),
                toUsername = obj.optString("to_username"),
                toEmail = obj.optString("to_email"),
                greeting = obj.optString("greeting"),
                status = obj.optString("status"),
                createdAt = obj.optLong("created_at")
            ))
        }
        return list
    }

    private fun parseConversationList(data: JSONArray): List<ConversationInfo> {
        val list = mutableListOf<ConversationInfo>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val rawLast = obj.optString("last_message", "")
            // 历史密文在会话列表展示前尝试还原；明文（群聊预览）原样显示；失败显示空
            val lastMessage = when {
                rawLast.isEmpty() -> ""
                CryptoUtil.isEncrypted(rawLast) -> {
                    try { CryptoUtil.decrypt(rawLast) }
                    catch (_: Exception) { "" }
                }
                else -> rawLast
            }
            list.add(ConversationInfo(
                id = obj.optLong("id"),
                email = obj.optString("email"),
                username = obj.optString("username"),
                createdAt = obj.optLong("created_at"),
                lastMessage = lastMessage,
                lastTime = obj.optLong("last_time")
            ))
        }
        return list
    }

    // ==================== 认证 API ====================

    suspend fun sendCode(email: String): ApiResult<Unit> = safeCall {
        val body = JSONObject().apply {
            put("email", email)
        }
        val json = request("POST", "/api/send-code", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    suspend fun register(email: String, username: String, password: String, code: String = "", deviceId: String = "", qqNumber: String = ""): ApiResult<LoginResponse> = safeCall {
        val body = JSONObject().apply {
            put("email", email); put("username", username); put("password", password); put("code", code)
            put("device_id", deviceId)
            put("qq_number", qqNumber)
        }
        val json = request("POST", "/api/register", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "注册失败"))
        val d = json.optJSONObject("data")!!
        applyDeveloperFlag(d)
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
            signSecret = d.optString("sign_secret", ""),
            signature = d.optString("signature", ""),
            token = d.optString("token"),
            emailVerified = d.optBoolean("email_verified", false),
            needsProfileCompletion = d.optBoolean("needs_profile_completion", false),
            qqNumber = d.optString("qq_number", ""),
            sessionKey = d.optString("session_key", "")
        )
        // 注册成功后立即设置 token 和 userId
        authToken = response.token
        currentUserId = response.id
        currentUserEmail = response.email
        currentUserName = d.optString("username", "")
        currentUserSignature = response.signature
        currentUserEmailVerified = response.emailVerified
        currentUserQQ = response.qqNumber
        sessionKey = response.sessionKey
        needsProfileCompletion = response.needsProfileCompletion
        response
    }

    // QQ 一键登录/注册：移动端通过腾讯 QQ SDK 取得 openid + access_token 后调用
    suspend fun qqLogin(openid: String, accessToken: String, username: String = "", deviceId: String = ""): ApiResult<LoginResponse> = safeCall {
        val body = JSONObject().apply {
            put("openid", openid)
            put("access_token", accessToken)
            put("username", username)
            put("device_id", deviceId)
        }
        val json = request("POST", "/api/qq/login", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "登录失败"))
        val d = json.optJSONObject("data")!!
        applyDeveloperFlag(d)
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
            signSecret = d.optString("sign_secret", ""),
            signature = d.optString("signature", ""),
            token = d.optString("token"),
            emailVerified = d.optBoolean("email_verified", false),
            needsProfileCompletion = d.optBoolean("needs_profile_completion", false),
            qqNumber = d.optString("qq_number", "")
        )
        authToken = response.token
        currentUserId = response.id
        currentUserEmail = response.email
        currentUserName = d.optString("username", "")
        currentUserSignature = response.signature
        currentUserEmailVerified = response.emailVerified
        currentUserQQ = response.qqNumber
        needsProfileCompletion = response.needsProfileCompletion
        response
    }

    // ==================== QQ 头像 / 验证码挑战 ====================

    /** 根据 QQ 号拼接公开头像地址（qlogo.cn，无需授权即可拉取） */
    fun qqAvatarUrl(qq: String): String =
        "https://q.qlogo.cn/headimg_dl?dst_uin=$qq&spec=640&img_type=jpg"

    /** 拉取 QQ 头像字节；无法拉取则返回 null（无官方公开接口校验账号是否存在，仅能拉取头像） */
    suspend fun fetchQQAvatar(qq: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val bytes = HttpClient.downloadBytes(qqAvatarUrl(qq))
            if (bytes.size < 200) return@withContext null // 过小视为默认灰像/无效
            bytes
        } catch (_: Exception) {
            null
        }
    }

    /** 获取验证码挑战（id + 题目），由用户手动作答后回传 */
    private suspend fun fetchCaptchaChallenge(): Pair<String, String>? = try {
        val json = request("GET", "/api/captcha/issue")
        if (checkSuccess(json)) {
            val d = json.optJSONObject("data")
            val id = d?.optString("captcha_id") ?: ""
            val q = d?.optString("image") ?: ""
            if (id.isNotBlank()) id to q else null
        } else null
    } catch (_: Exception) { null }

    /** 公开：获取验证码挑战（供需要后端验证码的入口调用），返回 id、题目 */
    suspend fun fetchCaptchaChallengeSuspend(): Pair<String, String>? = fetchCaptchaChallenge()

    /** 带显式验证码的注册（供注册弹窗使用，验证码由用户手动作答） */
    suspend fun registerWithCaptcha(
        email: String, username: String, password: String, code: String,
        captchaId: String, captchaAnswer: String, deviceId: String, qqNumber: String = ""
    ): ApiResult<LoginResponse> = safeCall {
        val body = JSONObject().apply {
            put("email", email); put("username", username); put("password", password); put("code", code)
            put("device_id", deviceId)
            put("qq_number", qqNumber)
            put("captcha_id", captchaId); put("captcha_answer", captchaAnswer)
        }
        val json = request("POST", "/api/register", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "注册失败"))
        val d = json.optJSONObject("data")!!
        applyDeveloperFlag(d)
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
            signSecret = d.optString("sign_secret", ""),
            signature = d.optString("signature", ""),
            token = d.optString("token"),
            emailVerified = d.optBoolean("email_verified", false),
            needsProfileCompletion = d.optBoolean("needs_profile_completion", false),
            qqNumber = d.optString("qq_number", "")
        )
        authToken = response.token
        currentUserId = response.id
        currentUserEmail = response.email
        currentUserName = d.optString("username", "")
        currentUserSignature = response.signature
        currentUserEmailVerified = response.emailVerified
        currentUserQQ = response.qqNumber
        needsProfileCompletion = response.needsProfileCompletion
        response
    }

    suspend fun login(email: String, password: String): ApiResult<LoginResponse> = safeCall {
        // device=mobile：手机端与桌面端各自持有独立登录态，同端新登录只挤掉同端旧登录，
        // 不会再把已登录的桌面端（或反向）顶下线。
        val body = JSONObject().apply { put("email", email); put("password", password); put("device", "mobile") }
        val json = request("POST", "/api/login", body)
        // 用服务端文案：失败熔断/限流时后端返回的是"已临时锁定，请 N 秒后再试"，
        // 继续硬编码"邮箱或密码错误"会让用户以为是密码问题，反复重试把锁定时间越拖越久。
        if (!checkSuccess(json)) throw Exception(json.optString("message").ifBlank { "邮箱或密码错误" })
        val d = json.optJSONObject("data")!!
        applyDeveloperFlag(d)
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
            signSecret = d.optString("sign_secret", ""),
            signature = d.optString("signature", ""),
            token = d.optString("token"),
            emailVerified = d.optBoolean("email_verified", false),
            needsProfileCompletion = d.optBoolean("needs_profile_completion", false),
            qqNumber = d.optString("qq_number", ""),
            sessionKey = d.optString("session_key", "")
        )
        // 登录成功后立即设置 token 和 userId，确保后续调用可用
        authToken = response.token
        currentUserId = response.id
        currentUserEmail = response.email
        currentUserName = d.optString("username", "")
        currentUserSignature = response.signature
        currentUserEmailVerified = response.emailVerified
        currentUserQQ = response.qqNumber
        sessionKey = response.sessionKey
        needsProfileCompletion = response.needsProfileCompletion
        response
    }

    // 完善资料（防重复建号，仅 UPDATE 当前用户）
    suspend fun completeProfile(
        username: String,
        qqNumber: String,
        password: String? = null
    ): ApiResult<LoginResponse> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("username", username)
            put("qq_number", qqNumber)
            if (!password.isNullOrEmpty()) put("password", password)
        }
        val json = request("POST", "/api/user/complete-profile", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "完善资料失败"))
        val d = json.optJSONObject("data")!!
        applyDeveloperFlag(d)
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
            signSecret = d.optString("sign_secret", ""),
            signature = d.optString("signature", ""),
            token = d.optString("token"),
            emailVerified = d.optBoolean("email_verified", false),
            needsProfileCompletion = false,
            qqNumber = d.optString("qq_number", "")
        )
        // 更新全局认证状态
        authToken = response.token
        currentUserId = response.id
        currentUserEmail = response.email
        currentUserName = response.username
        currentUserSignature = response.signature
        currentUserEmailVerified = response.emailVerified
        currentUserQQ = response.qqNumber
        needsProfileCompletion = false
        response
    }

    suspend fun sendBindCode(email: String): ApiResult<Unit> = safeCall {
        val body = JSONObject().apply { put("email", email) }
        val json = request("POST", "/api/user/bind-email/send-code", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    suspend fun bindEmail(email: String, code: String): ApiResult<Unit> = safeCall {
        val body = JSONObject().apply { put("email", email); put("code", code) }
        val json = request("POST", "/api/user/bind-email", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "绑定失败"))
    }

    /** 保存当前用户的邮箱推送配置（方式/模板模式/自己的邮箱/授权码/免打扰列表/总通知开关） */
    suspend fun savePushConfig(
        method: Int,
        templateMode: Int,
        ownEmail: String,
        ownAuth: String,
        dndIds: List<Long>,
        notifyEnabled: Int = 1
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("method", method)
            put("template_mode", templateMode)
            put("own_email", ownEmail)
            put("own_auth", ownAuth)
            put("dnd_ids", org.json.JSONArray(dndIds))
            put("notify_enabled", notifyEnabled)
        }
        val json = request("POST", "/api/user/push-config", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /** 读取当前用户的邮箱推送配置（不存在返回默认：官方+提示模板） */
    suspend fun getPushConfig(): ApiResult<PushConfig> = safeCall(authenticated = true) {
        val json = request("GET", "/api/user/push-config", authenticated = true)
        val data = json.optJSONObject("data") ?: JSONObject()
        PushConfig(
            method = data.optInt("method", 2),
            templateMode = data.optInt("template_mode", 1),
            ownEmail = data.optString("own_email", ""),
            ownAuth = data.optString("own_auth", "")
        )
    }

    suspend fun sendResetCode(email: String): ApiResult<Unit> = safeCall {
        val body = JSONObject().apply { put("email", email) }
        val json = request("POST", "/api/send-reset-code", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String): ApiResult<Unit> = safeCall {
        val body = JSONObject().apply {
            put("email", email); put("code", code); put("new_password", newPassword)
        }
        val json = request("POST", "/api/reset-password", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "重置失败"))
    }

    // ==================== 搜索用户 ====================

    suspend fun searchUsers(keyword: String): ApiResult<List<UserInfo>> = safeCall(authenticated = true) {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val json = request("GET", "/api/search-users?q=$encoded", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "搜索失败"))
        parseUserList(json.optJSONArray("data") ?: JSONArray())
    }

    // ==================== 好友请求 ====================

    suspend fun sendFriendRequest(target: String, greeting: String = ""): ApiResult<Unit> = safeCall(authenticated = true) {
        // target 可以是邮箱 / QQ号 / 用户ID / 用户名，后端会智能匹配
        val body = JSONObject().apply { put("to_email", target); put("greeting", greeting) }
        val json = request("POST", "/api/friend-request/send", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    suspend fun getFriendRequests(): ApiResult<List<FriendRequestInfo>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/friend-requests/${currentUserId}", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        parseFriendRequestList(json.optJSONArray("data") ?: JSONArray())
    }

    suspend fun respondFriendRequest(requestId: Long, action: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("request_id", requestId); put("action", action) }
        val json = request("POST", "/api/friend-request/respond", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    suspend fun cancelFriendRequest(requestId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("request_id", requestId) }
        val json = request("POST", "/api/friend-request/cancel", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "取消失败"))
    }

    // ==================== 好友列表 ====================

    suspend fun getFriends(): ApiResult<List<UserInfo>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/friends/${currentUserId}", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        parseUserList(json.optJSONArray("data") ?: JSONArray())
    }

    suspend fun sendPoke(toUserId: Long, targetUserId: Long = 0): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("to_user_id", toUserId)
            // 群聊场景：to_user_id 传群会话 ID（负数），target_user_id 传被拍的群成员 ID，
            // 让服务端把拍一拍作为群消息存储（隔离在群内，不泄漏到私聊）。私聊场景两者一致。
            put("target_user_id", if (targetUserId != 0L) targetUserId else toUserId)
        }
        val json = request("POST", "/api/messages/poke", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    suspend fun deleteFriend(friendId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("friend_id", friendId) }
        val json = request("POST", "/api/friend/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    // ==================== 关注 / 粉丝 ====================

    /** 关注某人。返回最新的粉丝数/关注数与关注状态；失败返回 null。 */
    suspend fun followUser(targetUserId: Long): FollowStats? = runCatching {
        val body = JSONObject().apply { put("target_user_id", targetUserId) }
        val json = request("POST", "/api/user/follow", body, authenticated = true)
        if (!checkSuccess(json)) return@runCatching null
        val d = json.optJSONObject("data") ?: return@runCatching null
        FollowStats(
            fans = d.optInt("fans"),
            following = d.optInt("following"),
            isFollowing = d.optBoolean("is_following", true),
            followsYou = d.optBoolean("follows_you", false)
        )
    }.getOrNull()

    /** 取消关注。 */
    suspend fun unfollowUser(targetUserId: Long): FollowStats? = runCatching {
        val body = JSONObject().apply { put("target_user_id", targetUserId) }
        val json = request("POST", "/api/user/unfollow", body, authenticated = true)
        if (!checkSuccess(json)) return@runCatching null
        val d = json.optJSONObject("data") ?: return@runCatching null
        FollowStats(
            fans = d.optInt("fans"),
            following = d.optInt("following"),
            isFollowing = d.optBoolean("is_following", false),
            followsYou = d.optBoolean("follows_you", false)
        )
    }.getOrNull()

    /**
     * 查某人的粉丝数 / 关注数，以及「我是否已关注他」。
     * 资料页用它显示粉丝数，并决定是否还需要显示关注按钮。
     */
    suspend fun getFollowStats(userId: Long): FollowStats? = runCatching {
        val json = request("GET", "/api/user/follow_stats?id=$userId", authenticated = true)
        if (!checkSuccess(json)) return@runCatching null
        val d = json.optJSONObject("data") ?: return@runCatching null
        FollowStats(
            fans = d.optInt("fans"),
            following = d.optInt("following"),
            isFollowing = d.optBoolean("is_following", false),
            followsYou = d.optBoolean("follows_you", false)
        )
    }.getOrNull()

    // ==================== 用户级黑名单（拉黑 / 拉出） ====================

    /** 将某个用户拉入黑名单（对称：对方也能在其黑名单看到你并拉出；拉黑自动解除好友）。 */
    suspend fun blockUser(targetUserId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("target_user_id", targetUserId) }
        val json = request("POST", "/api/user/block", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 将某个用户移出黑名单（拉出）。 */
    suspend fun unblockUser(targetUserId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("target_user_id", targetUserId) }
        val json = request("POST", "/api/user/unblock", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 获取我的黑名单列表（含双向）。 */
    suspend fun getBlacklist(): ApiResult<List<BlacklistItem>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/user/blacklist", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        val arr = json.optJSONArray("data") ?: org.json.JSONArray()
        val list = mutableListOf<BlacklistItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                BlacklistItem(
                    userId = o.optLong("user_id"),
                    username = o.optString("username"),
                    email = o.optString("email"),
                    qqNumber = o.optString("qq_number"),
                    signature = o.optString("signature"),
                    iAmBlocker = o.optBoolean("i_am_blocker", false),
                    blockedAt = o.optLong("blocked_at")
                )
            )
        }
        list
    }

    /** 查询我与某用户之间的黑名单关系（用于菜单文案）。 */
    suspend fun isBlocked(targetUserId: Long): ApiResult<IsBlockedResult> = safeCall(authenticated = true) {
        val json = request("GET", "/api/user/is-blocked?target_user_id=$targetUserId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        val data = json.optJSONObject("data") ?: org.json.JSONObject()
        IsBlockedResult(
            isBlocked = data.optBoolean("is_blocked", false),
            iAmBlocker = data.optBoolean("i_am_blocker", false)
        )
    }

    suspend fun isFriend(userId: Long): ApiResult<Boolean> = safeCall(authenticated = true) {
        // 获取好友列表检查目标是否在好友中
        val json = request("GET", "/api/friends/${currentUserId}", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        val list = parseUserList(json.optJSONArray("data") ?: JSONArray())
        list.any { it.id == userId }
    }

    /**
     * 好友关系权威结论（服务端 friendRelation 单向口径，与好友列表 / 好友申请一致）。
     * 只有 certain=true 才是「百分百判定」，客户端方可据此落地本地非好友标记。
     * 响应缺 data / 缺 is_friend / 缺 certain 一律抛错走失败分支（= 未知），
     * 绝不能默认成 false —— 旧写法就是这样把字段缺失当成「明确非好友」，
     * 导致所有私聊都误报「对方还不是你的好友」。
     */
    suspend fun getFriendStatus(userId: Long): ApiResult<FriendRelationResult> = safeCall(authenticated = true) {
        val json = request("GET", "/api/friend/status?user_id=$userId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        val data = json.optJSONObject("data") ?: throw Exception("好友状态数据缺失")
        if (!data.has("is_friend")) throw Exception("好友状态字段缺失")
        if (!data.has("certain") || !data.optBoolean("certain", false)) throw Exception("好友状态结论不确定")
        FriendRelationResult(
            isFriend = data.optBoolean("is_friend", false),
            peerHasMe = data.optBoolean("peer_has_me", false),
            certain = true
        )
    }

    // ==================== 隐私设置 ====================

    suspend fun updatePrivacy(
        hideEmail: Boolean? = null,
        hideQQ: Boolean? = null,
        requireGroupConsent: Boolean? = null,
        blockStrangerMsg: Boolean? = null
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject()
        if (hideEmail != null) body.put("hide_email", hideEmail)
        if (hideQQ != null) body.put("hide_qq", hideQQ)
        if (requireGroupConsent != null) body.put("require_group_consent", requireGroupConsent)
        // 非好友禁止向我发消息：必须在服务端落库，发消息接口据此拦截
        if (blockStrangerMsg != null) body.put("block_stranger_msg", blockStrangerMsg)
        val json = request("POST", "/api/user/privacy", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "设置失败"))
    }

    suspend fun updateSignature(signature: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("signature", signature) }
        val json = request("POST", "/api/user/signature", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "更新失败"))
    }

    /** 更新用户名和签名 */
    suspend fun updateProfile(username: String, signature: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("username", username)
            put("signature", signature)
        }
        val json = request("POST", "/api/user/update", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "更新失败"))
    }

    // ==================== Token 余额 / 订单 ====================

    /**
     * 在途请求去重：同一 key 的并发调用只真正发一次网络，其余复用同一结果。
     * 用于仪表盘/余额/用户信息等被多处同时轮询的只读接口，避免重复联网与重复解析。
     * 仅合并并发窗口内的重复请求，不做跨时刻缓存，不会返回过期业务数据。
     */
    private object ApiDedup {
        private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Any?>>()
        suspend fun <T> dedup(key: String, block: suspend () -> T): T {
            inFlight[key]?.let {
                @Suppress("UNCHECKED_CAST")
                return it.await() as T
            }
            val d = kotlinx.coroutines.CompletableDeferred<Any?>()
            inFlight.putIfAbsent(key, d)?.let {
                @Suppress("UNCHECKED_CAST")
                return it.await() as T
            }
            return try {
                val r = block()
                d.complete(r)
                r
            } catch (e: Throwable) {
                d.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** 查询当前用户 Token 余额与官方 API 开关（独立于会员体系）：返回 token_balance / disable_official_api */
    suspend fun tokenBalance(): ApiResult<JSONObject> = ApiDedup.dedup("tokenBalance") {
        safeCall(authenticated = true) { request("GET", "/api/user/token-balance", authenticated = true) }
    }

    /**
     * 最近一次**已结算**的付费 API 用量明细（服务端权威值）。
     *
     * 为什么需要它：付费 API 中转在客户端断开（用户点停止）时**照样结算并落库**
     * （见 backend/paid_api.go 防线③），只是那条 SSE 结算事件已经随断开发不出去了。
     * 中断记录的 token 明细因此可以用这里的真实 input/output/cost，而不是客户端估算。
     *
     * 返回 data：found(Bool)、input_tokens、output_tokens、cost_tokens(真实扣费)、
     * model、request_id、created_at。
     */
    suspend fun paidApiLastUsage(): ApiResult<JSONObject> =
        safeCall(authenticated = true) { request("GET", "/api/paid-api/last-usage", authenticated = true) }

    /** 提交 Token 充值订单：tokens 为充值数量，amount 为应付金额(元)，orderNo 为支付订单号 */
    suspend fun submitRechargeOrder(tokens: Long, amount: Double, orderNo: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("tokens", tokens)
            put("amount", amount)
            put("order_no", orderNo)
        }
        request("POST", "/api/orders/recharge/submit", body, authenticated = true)
    }

    /** 管理员：获取所有订单（会员体系已移除，当前均为 Token 充值订单） */
    suspend fun getMemberOrders(): ApiResult<JSONObject> = safeCall(authenticated = true) {
        request("GET", "/api/admin/member-orders", authenticated = true)
    }

    /** 管理员：处理订单（action: approve / reject / delete）；reject 时可附拒绝原因 */
    suspend fun processMemberOrder(orderId: Long, action: String, reason: String = ""): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("id", orderId)
            put("action", action)
            put("reason", reason)
        }
        request("POST", "/api/admin/member-order/process", body, authenticated = true)
    }

    /** 系统通知历史项（服务端持久化，重启/离线不丢） */
    data class SystemNoticeItem(
        val id: Long,
        val title: String,
        val desc: String,
        val tag: String,
        val orderId: Long,
        val createdAt: Long
    )

    /** 群待办：指向群内某条消息，群主/管理员设置，全员在会话顶部看到提示 */
    data class GroupTodo(
        val groupId: Long,
        val messageId: Long,
        val setterName: String,
        val snippet: String,
        val createdAt: Long
    )

    /** 拉取当前用户的系统通知历史（来自服务端持久化存储，确保离线/重启后仍存在） */
    suspend fun getSystemNotices(): ApiResult<List<SystemNoticeItem>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/system-notices", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取通知失败")
        val data = json.optJSONArray("data") ?: JSONArray()
        val list = mutableListOf<SystemNoticeItem>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            list.add(SystemNoticeItem(
                id = obj.optLong("id", 0),
                title = obj.optString("title", ""),
                desc = obj.optString("desc", ""),
                tag = obj.optString("tag", ""),
                orderId = obj.optLong("order_id", 0),
                createdAt = obj.optLong("created_at", 0)
            ))
        }
        list
    }

    /** 用户查看自己的某笔订单详情（仅属于自己的订单可见） */
    data class UserOrderDetail(
        val id: Long,
        val userId: Long,
        val username: String,
        val level: Int,
        val durationDays: Int,
        val amount: Double,
        val orderNo: String,
        val status: String,
        val createdAt: Long,
        val processedAt: Long,
        val type: String,
        val tokens: Long,
        val rejectReason: String
    )

    /** GET /api/orders/<id> — 拉取当前用户自己的订单详情 */
    suspend fun getOrderById(orderId: Long): ApiResult<UserOrderDetail> = safeCall(authenticated = true) {
        val json = request("GET", "/api/orders/$orderId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取订单失败"))
        val d = json.optJSONObject("data") ?: throw Exception("订单数据为空")
        UserOrderDetail(
            id = d.optLong("id", 0),
            userId = d.optLong("user_id", 0),
            username = d.optString("username", ""),
            level = d.optInt("level", 0),
            durationDays = d.optInt("duration_days", 0),
            amount = d.optDouble("amount", 0.0),
            orderNo = d.optString("order_no", ""),
            status = d.optString("status", ""),
            createdAt = d.optLong("created_at", 0),
            processedAt = d.optLong("processed_at", 0),
            type = d.optString("type", "member"),
            tokens = d.optLong("tokens", 0),
            rejectReason = d.optString("reject_reason", "")
        )
    }

    // ==================== 开发者通知中心（系统通知下发） ====================

    /**
     * 开发者/管理员向用户发送系统通知（通知中心）。
     * @param scope "user"(按用户 ID) | "all"(全部) | "range"(ID 区块)
     * @param userIds 当 scope=user 时的用户 ID 列表
     * @param ranges 当 scope=range 时的 ID 区间，每个为 [min,max]
     * @param title 通知标题
     * @param content 通知内容
     * @param extra 底部补充信息
     */
    suspend fun sendNotification(
        scope: String,
        userIds: List<Long>,
        ranges: List<Pair<Long, Long>>,
        title: String,
        content: String,
        extra: String
    ): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val rangesArr = JSONArray()
        ranges.forEach { rg ->
            rangesArr.put(JSONArray().apply { put(rg.first); put(rg.second) })
        }
        val body = JSONObject().apply {
            put("scope", scope)
            put("user_ids", JSONArray(userIds))
            put("ranges", rangesArr)
            put("title", title)
            put("content", content)
            put("extra", extra)
        }
        request("POST", "/api/admin/notify", body, authenticated = true)
    }

    /** 开发者/管理员按范围批量调整用户 Token 余额：scope=user_ids/all/range，op=add/deduct/reset */
    suspend fun adjustBalance(
        scope: String,
        userIds: List<Long>,
        ranges: List<Pair<Long, Long>>,
        op: String,
        amount: Long,
        reason: String
    ): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val rangesArr = org.json.JSONArray()
        for ((a, b) in ranges) {
            rangesArr.put(org.json.JSONArray().apply { put(a); put(b) })
        }
        val idsArr = org.json.JSONArray()
        for (id in userIds) idsArr.put(id)
        val body = JSONObject().apply {
            put("scope", scope)
            put("user_ids", idsArr)
            put("ranges", rangesArr)
            put("op", op)
            put("amount", amount)
            put("reason", reason)
        }
        request("POST", "/api/admin/balance/adjust", body, authenticated = true)
    }

    // ==================== 公钥管理 ====================

    suspend fun uploadPublicKey(publicKeyBase64: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("public_key", publicKeyBase64) }
        val json = request("POST", "/api/keys/upload", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上传失败"))
    }

    suspend fun getPublicKey(userId: Long): ApiResult<String> = safeCall(authenticated = true) {
        publicKeyCache[userId]?.let { return@safeCall it }
        val json = request("GET", "/api/keys/$userId", authenticated = true)
        if (!checkSuccess(json)) throw Exception("对方未设置密钥")
        val key = json.optJSONObject("data")?.optString("public_key", "") ?: ""
        if (key.isNotEmpty()) publicKeyCache[userId] = key
        key
    }

    fun clearPublicKeyCache() {
        publicKeyCache.clear()
    }

    // ==================== 历史加密开关 ====================

    suspend fun setE2EEnabled(enabled: Boolean): ApiResult<Boolean> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("e2e_enabled", enabled) }
        val json = request("POST", "/api/e2e/enable", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "设置失败"))
        json.optJSONObject("data")?.optBoolean("e2e_enabled", enabled) ?: enabled
    }

    suspend fun getE2EStatus(userId: Long): ApiResult<Boolean> = safeCall(authenticated = true) {
        val json = request("GET", "/api/e2e/status/$userId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data")?.optBoolean("e2e_enabled", false) ?: false
    }

    // ==================== 消息 ====================

    suspend fun sendGroupMessage(toUserId: Long, content: String, replyTo: Long = 0, mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0, replyToText: String = "", replyToSender: String = "", mediaWidth: Int = 0, mediaHeight: Int = 0, mentions: Map<String, Long> = emptyMap()): ApiResult<Long> = safeCall(authenticated = true) {
        // 传输加密：正文和引用预览用 session_key 加密为 sess:v1:，消除传输明文；
        // 服务端 decryptSessionContent 解密后再走 enc:v1: 静态加密落库。无 session_key 时降级明文。
        val encContent = CryptoUtil.encryptWithKey(content, sessionKey)
        val encReplyToText = if (replyToText.isNotEmpty()) CryptoUtil.encryptWithKey(replyToText, sessionKey) else ""
        val encMentions = com.aurora.chat.data.store.MentionStore.encode(mentions)
        val body = JSONObject().apply { put("from_user_id", currentUserId); put("to_user_id", toUserId); put("content", encContent); put("client_send_at", System.currentTimeMillis()); if (replyTo > 0) put("reply_to", replyTo); if (encReplyToText.isNotEmpty()) put("reply_to_text", encReplyToText); if (replyToSender.isNotEmpty()) put("reply_to_sender", replyToSender); if (encMentions.isNotEmpty()) put("mentions", encMentions); if (mediaType.isNotEmpty()) { put("media_type", mediaType); put("media_url", mediaUrl); if (mediaWidth > 0) put("media_width", mediaWidth); if (mediaHeight > 0) put("media_height", mediaHeight) }; if (flashDuration > 0) put("flash_duration", flashDuration) }
        val json = request("POST", "/api/messages/send", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
        json.optJSONObject("data")?.optLong("message_id", 0) ?: 0
    }

    suspend fun sendMessage(toUserId: Long, content: String, replyTo: Long = 0, mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0, replyToText: String = "", replyToSender: String = "", contactSource: String = "", mediaWidth: Int = 0, mediaHeight: Int = 0, mentions: Map<String, Long> = emptyMap()): ApiResult<Long> = safeCall(authenticated = true) {
        // 私聊消息统一使用 session_key 传输加密（sess:v1:）：服务端解密后静态加密落库、读取时解密返回明文，
        // 保证任意设备都能直接显示真实消息。
        val encContent = CryptoUtil.encryptWithKey(content, sessionKey)
        val encReplyToText = if (replyToText.isNotEmpty()) CryptoUtil.encryptWithKey(replyToText, sessionKey) else ""
        val encMentions = com.aurora.chat.data.store.MentionStore.encode(mentions)
        val body = JSONObject().apply { put("from_user_id", currentUserId); put("to_user_id", toUserId); put("content", encContent); put("client_send_at", System.currentTimeMillis()); if (replyTo > 0) put("reply_to", replyTo); if (encReplyToText.isNotEmpty()) put("reply_to_text", encReplyToText); if (replyToSender.isNotEmpty()) put("reply_to_sender", replyToSender); if (encMentions.isNotEmpty()) put("mentions", encMentions); if (mediaType.isNotEmpty()) { put("media_type", mediaType); put("media_url", mediaUrl); if (mediaWidth > 0) put("media_width", mediaWidth); if (mediaHeight > 0) put("media_height", mediaHeight) }; if (flashDuration > 0) put("flash_duration", flashDuration); if (contactSource.isNotEmpty()) put("contact_source", contactSource) }
        val json = request("POST", "/api/messages/send", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
        json.optJSONObject("data")?.optLong("message_id", 0) ?: 0
    }

    /**
     * 私聊发送是否应走历史兼容加密：仅读取本地记录（好友资料面板打开时将双方开关与对方公钥写入本地），
     * 三方条件（我的开关 && 对方开关 && 已有对方公钥）都满足才返回对方公钥，否则返回 null 走 AES。
     * 本地无记录按未开启处理，保证安全兜底。
     */
    private fun e2eFriendPubKey(friendId: Long): String? {
        return try {
            val prefs = AuroraChatApplication.instance
                .getSharedPreferences("aurora_friend_settings", android.content.Context.MODE_PRIVATE)
            val myOn = prefs.getBoolean("e2e_enabled_$friendId", false)
            if (!myOn) return null
            val friendOn = prefs.getBoolean("e2e_friend_status_$friendId", false)
            if (!friendOn) return null
            val pub = prefs.getString("e2e_friend_pub_$friendId", "") ?: ""
            if (pub.isEmpty()) null else pub
        } catch (_: Exception) { null }
    }

    // ==================== token 转账 ====================
    // toUserId: 会话对端（1:1 为好友ID，群聊为群ID）；realToUserId: 真实收款人
    suspend fun transfer(toUserId: Long, realToUserId: Long, realToName: String, amount: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("to_user_id", toUserId)
            put("real_to_user_id", realToUserId)
            put("real_to_name", realToName)
            put("amount", amount)
        }
        val json = request("POST", "/api/transfer", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "转账失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 领取转账：返回 status、claimed_at 等 */
    suspend fun claimTransfer(transferId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("transfer_id", transferId) }
        val json = request("POST", "/api/transfer/claim", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "收款失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 转账详情：发送时间、领取时间、状态 */
    suspend fun getTransferDetail(transferId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/transfer/detail?id=$transferId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    // ==================== 红包 ====================
    /** 发红包：toUserId 为会话对端（1:1 好友ID 或群ID），count 份数，amount 总额（token），greeting 祝福语 */
    suspend fun createRedPacket(toUserId: Long, count: Int, amount: Long, greeting: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("to_user_id", toUserId)
            put("count", count)
            put("amount", amount)
            put("greeting", greeting)
        }
        val json = request("POST", "/api/redpacket/create", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 抢红包：返回 amount（领取到的 token）、claimed_count、remaining、already 等 */
    suspend fun grabRedPacket(packetId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("packet_id", packetId) }
        val json = request("POST", "/api/redpacket/grab", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "领取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 红包详情：领取名单 + 自身领取额 + 状态 */
    suspend fun getRedPacketDetail(packetId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/redpacket/detail?id=$packetId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }


    suspend fun getGroupMessages(groupId: Long, limit: Int = 100, offset: Int = 0, afterId: Long = 0): ApiResult<List<MessageInfo>> = safeCall(authenticated = true) {
        val after = if (afterId > 0) "&after_id=$afterId" else ""
        val json = request("GET", "/api/messages/0/$groupId?limit=$limit&offset=$offset$after", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        parseMessageList(json.optJSONArray("data") ?: JSONArray())
    }

    suspend fun getMessages(userId1: Long, userId2: Long, limit: Int = 50, offset: Int = 0, afterId: Long = 0): ApiResult<List<MessageInfo>> = safeCall(authenticated = true) {
        val after = if (afterId > 0) "&after_id=$afterId" else ""
        val json = request("GET", "/api/messages/$userId1/$userId2?limit=$limit&offset=$offset$after", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")

        val data = json.optJSONArray("data") ?: JSONArray()
        val list = mutableListOf<MessageInfo>()

        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val encrypted = obj.optString("content", "")
            // 历史密文尝试还原；明文/群聊直接显示
            val decrypted = if (userId2 > 0 && CryptoUtil.isEncrypted(encrypted)) {
                try {
                    CryptoUtil.decrypt(encrypted)
                } catch (_: Exception) {
                    // 解密失败显示空（历史密文无法还原则留空，不显示占位）
                    ""
                }
            } else {
                encrypted // 明文 / 群聊直接显示原文
            }
            val mentions = com.aurora.chat.data.store.MentionStore.parse(obj.optString("mentions", ""))
            com.aurora.chat.data.store.MentionStore.put(obj.optLong("id"), mentions)
            list.add(MessageInfo(
                id = obj.optLong("id"),
                fromUserId = obj.optLong("from_user_id"),
                toUserId = obj.optLong("to_user_id"),
                content = decrypted,
                createdAt = obj.optLong("created_at"),
                fromUserName = obj.optString("username", ""),
                isRevoked = obj.optInt("is_revoked", 0),
                replyToText = obj.optString("reply_to_text", ""),
                replyToSender = obj.optString("reply_to_sender", ""),
                replyToId = obj.optLong("reply_to_id", 0),
                mediaType = obj.optString("media_type", ""),
                mediaUrl = obj.optString("media_url", ""),
                flashDuration = obj.optInt("flash_duration", 0),
                targetName = obj.optString("to_user_name", ""),
                msgType = obj.optString("msg_type", ""),
                broadcastTaskId = obj.optLong("broadcast_task_id", 0),
                contactSource = obj.optString("contact_source", ""),
                mediaWidth = obj.optInt("media_width", 0),
                mediaHeight = obj.optInt("media_height", 0),
                clientSendAt = obj.optLong("client_send_at", 0),
                mentions = mentions
            ))
        }
        list
    }

    // ==================== 消息撤回 / 信息 / 已读 ====================

    suspend fun recallMessage(messageId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("message_id", messageId) }
        val json = request("POST", "/api/messages/recall", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "撤回失败"))
    }

    /** 发布群公告：以卡片形式发送到群聊对话内，返回服务端生成的 message_id（images 为已上传的服务器图片 URL，最多5张） */
    suspend fun postGroupAnnouncement(groupId: Long, text: String, needConfirm: Boolean = false, images: List<String> = emptyList()): ApiResult<Long> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("text", text)
            put("need_confirm", needConfirm)
            if (images.isNotEmpty()) put("images", JSONArray(images))
        }
        val json = request("POST", "/api/groups/announcement/post", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发布失败"))
        json.optJSONObject("data")?.optLong("message_id") ?: throw Exception("发布结果为空")
    }

    // ==================== 开发者广播（仅 ID=1 可用，服务端强制校验 userId==1） ====================

    /** 发送广播：text=内容，count=条数，toUser=指定接收用户（0=官方群/所有人）。服务端异步处理，立即返回。 */
    suspend fun broadcastSend(text: String, count: Int, toUser: Long = 0L): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("text", text)
            put("count", count)
            put("to_user", toUser)
        }
        val json = request("POST", "/api/broadcast/send", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    /** 删除单条广播（悄无声息，所有人侧同步移除）。 */
    suspend fun broadcastDeleteSingle(messageId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("message_id", messageId) }
        val json = request("POST", "/api/broadcast/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 撤回整个广播任务：停止未发送的，并清理已发出的。 */
    suspend fun broadcastRecallAll(taskId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("task_id", taskId) }
        val json = request("POST", "/api/broadcast/recall", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "撤回失败"))
    }

    suspend fun getMessageInfo(messageId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/messages/info/$messageId", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        json.optJSONObject("data") ?: throw Exception("数据为空")
    }

    suspend fun markMessageRead(messageId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("message_id", messageId) }
        val json = request("POST", "/api/messages/read", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "标记已读失败"))
    }

    suspend fun markMessagesReadBatch(messageIds: List<Long>): ApiResult<Unit> = safeCall(authenticated = true) {
        val ids = org.json.JSONArray()
        messageIds.forEach { ids.put(it) }
        val body = JSONObject().apply { put("message_ids", ids) }
        val json = request("POST", "/api/messages/read-batch", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "批量标记已读失败"))
    }

    /**
     * 批量查询「我自己发的消息」的已读情况（气泡下方常驻显示时用）。
     *
     * 不按条调 [getMessageInfo]：那个接口每条都要拉群成员 + 查已读明细 + 逐个取昵称，
     * 一屏气泡就是十几次查询。这里一次一批（服务端上限 100），返回 messageId → [ReadStat]。
     */
    suspend fun fetchReadStats(messageIds: List<Long>): ApiResult<Map<Long, ReadStat>> = safeCall(authenticated = true) {
        if (messageIds.isEmpty()) return@safeCall emptyMap()
        val ids = org.json.JSONArray()
        messageIds.forEach { ids.put(it) }
        val body = JSONObject().apply { put("message_ids", ids) }
        val json = request("POST", "/api/messages/read-stats", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取已读状态失败")
        val arr = json.optJSONObject("data")?.optJSONArray("stats") ?: org.json.JSONArray()
        val out = HashMap<Long, ReadStat>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("message_id", 0L)
            if (id <= 0L) continue
            out[id] = ReadStat(
                messageId = id,
                readCount = o.optInt("read_count", 0),
                otherReadAt = o.optLong("other_read_at", 0L)
            )
        }
        out
    }

    // ==================== 会话列表 ====================

    /**
     * 会话列表。
     * forceRefresh=true 时带时间戳参数，强制绕过 OkHttp 磁盘缓存，保证下拉刷新拿到的是服务器最新数据。
     */
    suspend fun getConversations(forceRefresh: Boolean = false): ApiResult<List<ConversationInfo>> = safeCall(authenticated = true) {
        val path = "/api/conversations/${currentUserId}" +
            if (forceRefresh) "?t=${System.currentTimeMillis()}" else ""
        val json = request("GET", path, authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        parseConversationList(json.optJSONArray("data") ?: JSONArray())
    }

    // ==================== 头像 ====================

    fun getAvatarUrl(userId: Long): String = "$serverUrl/api/avatar/$userId"

    /** 获取服务器上文件的精确字节大小（HEAD 请求） */
    suspend fun fetchFileSize(fileName: String): Long = withContext(Dispatchers.IO) {
        try {
            HttpClient.headContentLength("$serverUrl/tools/$fileName").coerceAtLeast(0)
        } catch (_: Exception) { 0L }
    }

    suspend fun uploadAvatar(avatarBase64: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", currentUserId); put("avatar", avatarBase64) }
        val json = request("POST", "/api/avatar/upload", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上传失败"))
    }

    /** 上传头像（base64，带真实字节进度回调）。最终成败以服务端响应为准。 */
    suspend fun uploadAvatarWithProgress(
        avatarBase64: String,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", currentUserId); put("avatar", avatarBase64) }
        val raw = HttpClient.postJsonWithProgress(
            "$serverUrl/api/avatar/upload", body.toString(), authToken, onProgress
        )
        val json = JSONObject(raw)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上传失败"))
        Unit
    }

    /** 检查用户是否有真实头像文件（非默认头像） */
    suspend fun checkAvatarExists(userId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val raw = HttpClient.jsonRequest("GET", "$serverUrl/api/avatar/$userId/exists", null, authToken)
            org.json.JSONObject(raw).optBoolean("has_avatar", false)
        } catch (_: Exception) { false }
    }

    /** 获取带缓存戳的头像 URL（防止 CDN/浏览器缓存旧头像） */
    fun getAvatarUrlWithTimestamp(userId: Long): String = "$serverUrl/api/avatar/$userId?t=${System.currentTimeMillis()}"

    suspend fun loadAvatarBytes(userId: Long): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // 带缓存戳拉取，避免 OkHttp 命中后端 max-age 缓存的旧头像（上传新头像后仍显示旧图）
            HttpClient.downloadBytes(getAvatarUrlWithTimestamp(userId), authToken)
        } catch (_: Exception) { null }
    }

    // ==================== 用户存在性检测（安全验证） ====================

    /**
     * 安全验证：查询该用户 ID 在数据库是否存在。
     * 仅在服务器明确返回 {"exists": false} 时才返回 false，
     * 网络超时/服务器异常等均返回 true（不动本地数据）。
     */
    suspend fun checkUserExists(userId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val raw = HttpClient.jsonRequest("GET", "$serverUrl/api/user/check-exists?userId=$userId", null, authToken)
            org.json.JSONObject(raw).optBoolean("exists", true)
        } catch (_: Exception) {
            true // 网络异常，不清理
        }
    }

    /** 查询当前用户的禁言状态 */
    suspend fun checkMuteStatus(): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/user/mute-status", authenticated = true)
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 查询当前用户是否为平台管理员 */
    suspend fun isPlatformAdmin(): ApiResult<Boolean> = safeCall(authenticated = true) {
        val json = request("GET", "/api/user/is-platform-admin", authenticated = true)
        json.optJSONObject("data")?.optBoolean("is_admin", false) ?: false
    }

    /** 获取当前用户的细粒度权限列表 */
    suspend fun getMyPermissions(): ApiResult<List<String>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/my-permissions", authenticated = true)
        val arr = json.optJSONArray("data") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.optString(it) }
    }

    // ==================== 插件市场 ====================

    /** 提交插件到插件市场(进入待审核) */
    suspend fun pluginSubmit(name: String, description: String, pluginJson: String): ApiResult<Long> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("pluginJson", pluginJson)
        }
        val json = request("POST", "/api/plugin/submit", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "提交失败"))
        json.optJSONObject("data")?.optLong("id") ?: 0L
    }

    /** 市场列表:默认仅已上架;mine=true 时额外包含本人自建的全部状态插件(自建无需审核即可自用) */
    suspend fun pluginMarket(mine: Boolean = false): ApiResult<List<JSONObject>> = safeCall(authenticated = true) {
        val json = request("GET", if (mine) "/api/plugin/market?mine=1" else "/api/plugin/market", authenticated = true)
        val arr = json.optJSONArray("data") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** 我的插件:当前账号提交过的全部插件(含待审/已上架/未通过/已下架,含 AI 以本人身份创建的) */
    suspend fun pluginMine(): ApiResult<List<JSONObject>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/plugin/mine", authenticated = true)
        val arr = json.optJSONArray("data") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** 删除自己创建的插件(任意状态,物理删除) */
    suspend fun pluginDelete(id: Long): ApiResult<Boolean> = safeCall(authenticated = true) {
        val body = JSONObject().put("id", id)
        val json = request("POST", "/api/plugin/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
        true
    }

    /** 开发者:插件审核列表(status=pending/approved/rejected/removed/all) */
    suspend fun pluginAdminList(status: String = "pending"): ApiResult<List<JSONObject>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/plugin/admin/list?status=$status", authenticated = true)
        val arr = json.optJSONArray("data") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** 开发者:审核操作 approve=上架 / reject=拒绝 / remove=下架 */
    suspend fun pluginAdminReview(id: Long, action: String, reason: String = ""): ApiResult<String> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("id", id)
            put("action", action)
            put("reason", reason)
        }
        val json = request("POST", "/api/plugin/admin/review", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
        json.optJSONObject("data")?.optString("status") ?: ""
    }

    /** 记录闪照已查看 + 传 media_url 让服务端删资源文件 */
    suspend fun recordFlashView(messageId: Long, mediaUrl: String = ""): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("message_id", messageId)
            if (mediaUrl.isNotEmpty()) put("media_url", mediaUrl)
        }
        val json = request("POST", "/api/flash/view", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception("记录失败")
    }

    /** 上报"我正在查看对方的闪照"：服务端据此给发送方实时推送"对方查看中"。fire-and-forget。 */
    suspend fun notifyFlashViewing(messageId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("message_id", messageId) }
        val json = request("POST", "/api/flash/viewing", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception("上报失败")
    }

    /** 获取已查看闪照ID列表 */
    suspend fun getViewedFlashIds(): ApiResult<List<Long>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/flash/viewed-ids", authenticated = true)
        val data = json.optJSONObject("data")
        val arr = data?.optJSONArray("ids") ?: JSONArray()
        (0 until arr.length()).map { arr.getLong(it) }
    }

    /** 查询对方是否查看过我发的闪照（传入我发的闪照messageId列表，返回已被看过的） */
    suspend fun getPartnerViewedFlashIds(messageIds: List<Long>): ApiResult<List<Long>> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("message_ids", JSONArray(messageIds)) }
        val json = request("POST", "/api/flash/partner-viewed", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception("查询失败")
        val data = json.optJSONObject("data")
        val arr = data?.optJSONArray("viewed_ids") ?: JSONArray()
        (0 until arr.length()).map { arr.getLong(it) }
    }

    /** 查询当前用户的封禁状态 */
    suspend fun checkBanStatus(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/user/ban-status", authenticated = true)
        // 后端在未封禁时返回 data: null，这里用空对象兜底，避免 NPE
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员操作：解封用户 */
    suspend fun adminUnbanUser(userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", userId) }
        val json = request("POST", "/api/admin/user/unban", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "解封失败"))
    }

    /** 管理员查询指定用户的封禁状态 */
    suspend fun adminCheckBanStatus(userId: Long): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/user/ban-status?user_id=$userId", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员操作：封禁用户 */
    suspend fun adminBanUser(userId: Long, duration: Long, reason: String, unbanPopupMessage: String = ""): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("duration", duration)
            put("reason", reason)
            put("unban_popup_message", unbanPopupMessage)
        }
        val json = request("POST", "/api/admin/user/ban", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "封禁失败"))
    }

    /** 管理员操作：禁言用户 */
    suspend fun adminMuteUser(userId: Long, duration: Long, muteType: Int): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("duration", duration)
            put("mute_type", muteType)
        }
        val json = request("POST", "/api/admin/user/mute", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "禁言失败"))
    }

    /** 管理员操作：解除禁言 */
    suspend fun adminUnmuteUser(userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", userId) }
        val json = request("POST", "/api/admin/user/unmute", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "解除禁言失败"))
    }

    /** 管理员查询指定用户的禁言状态 */
    suspend fun adminCheckMuteStatus(userId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/user/mute-status?user_id=$userId", authenticated = true)
        json.optJSONObject("data") ?: JSONObject()
    }

    // ==================== 群内禁言 ====================

    /** 群内禁言成员（仅群主/管理员） */
    suspend fun groupMuteUser(groupId: Long, userId: Long, duration: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("user_id", userId)
            put("duration", duration)
        }
        val json = request("POST", "/api/group/mute", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "禁言失败"))
    }

    /** 解除群内禁言 */
    suspend fun groupUnmuteUser(groupId: Long, userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("user_id", userId)
        }
        val json = request("POST", "/api/group/unmute", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "解除禁言失败"))
    }

    /** 查询用户在群内的禁言状态 */
    suspend fun groupCheckMuteStatus(groupId: Long, userId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/group/mute-status?group_id=$groupId&user_id=$userId", authenticated = true)
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 批量查询群内被禁言成员列表 */
    suspend fun groupMuteMembers(groupId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/group/mute-members?group_id=$groupId", authenticated = true)
        json
    }

    /** 用户请求解封时发送邮件通知 */
    suspend fun requestUnbanNotify(): ApiResult<Unit> = safeCall(authenticated = true) {
        val json = request("POST", "/api/user/ban-notify", null, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "请求失败"))
    }

    // ==================== 安全设置 API（服务端存储） ====================

    /**
     * 查询当前登录用户是否在服务端开启了安全验证
     * @return JSONObject 包含 {security_enabled, has_password, has_gesture}
     */
    suspend fun getSecurityStatus(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/security/status", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /**
     * 将安全设置同步到服务端
     * @param passwordPlain 明文密码（为空则不更新）
     * @param gesturePattern 手势图案（为空则不更新）
     * @param securityEnabled 是否启用安全锁
     * @param multiVerify 是否启用多重验证
     */
    suspend fun saveSecuritySettings(
        passwordPlain: String = "",
        gesturePattern: String = "",
        securityEnabled: Int? = null,
        multiVerify: Int? = null
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject()
        if (passwordPlain.isNotEmpty()) body.put("password_plain", passwordPlain)
        if (gesturePattern.isNotEmpty()) body.put("gesture_pattern", gesturePattern)
        if (securityEnabled != null) body.put("security_enabled", securityEnabled)
        if (multiVerify != null) body.put("multi_verify", multiVerify)
        val json = request("POST", "/api/security/save", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /**
     * 在服务端验证密码或手势
     * @param passwordPlain 明文密码（选其一）
     * @param gesturePattern 手势图案（选其一）
     * @return true=验证通过
     */
    suspend fun verifySecurity(passwordPlain: String = "", gesturePattern: String = ""): ApiResult<Boolean> = safeCall(authenticated = true) {
        val body = org.json.JSONObject()
        if (passwordPlain.isNotEmpty()) body.put("password_plain", passwordPlain)
        if (gesturePattern.isNotEmpty()) body.put("gesture_pattern", gesturePattern)
        val json = request("POST", "/api/security/verify", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "验证失败"))
        json.optJSONObject("data")?.optBoolean("verified", false) ?: false
    }

    // ==================== 用户信息 ====================

    suspend fun getUserInfo(userId: Long): ApiResult<UserInfo> = ApiDedup.dedup("userInfo:$userId") {
        safeCall(authenticated = true) {
            val json = request("GET", "/api/user/$userId", authenticated = true)
            if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
            val data = json.optJSONObject("data") ?: throw Exception("数据为空")
        val result = UserInfo(
            id = data.optLong("id"),
            email = data.optString("email"),
            username = data.optString("username"),
            qqNumber = data.optString("qq_number", ""),
            createdAt = data.optLong("created_at"),
            lastActiveAt = data.optLong("updated_at"),
            hideEmail = data.optInt("hide_email", 0),
            hideQQ = data.optInt("hide_qq", 0),
            signature = data.optString("signature", ""),
            onlineTimeSeconds = data.optLong("online_time_seconds", 0),
            wordCount = data.optLong("word_count", 0),
            emailVerified = data.optBoolean("email_verified", false)
        )
        // 如果是查自己的信息，同步更新全局 email_verified 状态
        if (result.id == currentUserId) {
            currentUserEmailVerified = result.emailVerified
        }
        result
    }
    }

    suspend fun syncUserStats(onlineTimeSeconds: Long, wordCount: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("online_time_seconds", onlineTimeSeconds)
            put("word_count", wordCount)
        }
        val json = request("POST", "/api/user/stats", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "同步失败"))
    }

    // ==================== 系统公告 API ====================

    /** 管理员保存/更新公告 */
    suspend fun saveAnnouncement(title: String, content: String, source: String, date: String, supplement: String, enabled: Boolean): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("title", title)
            put("content", content)
            put("source", source)
            put("date", date)
            put("supplement", supplement)
            put("enabled", enabled)
        }
        val json = request("POST", "/api/admin/announcement/save", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /** 管理员获取当前公告 */
    suspend fun getAdminAnnouncement(): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/announcement", authenticated = true)
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 用户获取当前启用的公告（登录后调用） */
    suspend fun fetchActiveAnnouncement(): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/announcement", authenticated = true)
        json.optJSONObject("data") ?: JSONObject()
    }

    // ==================== 开发者管理 ====================

    suspend fun getAllUsers(): ApiResult<List<UserInfo>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/users", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        parseUserList(json.optJSONArray("data") ?: JSONArray())
    }

    suspend fun getOnlineUsers(): ApiResult<List<UserInfo>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/users/online", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        parseUserList(json.optJSONArray("data") ?: JSONArray())
    }

    /** 开发者用户搜索：服务端按 ID/QQ/用户名/邮箱过滤，最多返回 50 条，不再把全量用户拉到本地比对 */
    suspend fun searchAdminUsers(keyword: String, limit: Int = 50): ApiResult<List<UserInfo>> = safeCall(authenticated = true) {
        val q = java.net.URLEncoder.encode(keyword, "UTF-8")
        val json = request("GET", "/api/admin/users/search?q=$q&limit=$limit", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "搜索失败"))
        parseUserList(json.optJSONArray("data") ?: JSONArray())
    }

    /** 用户自主注销账号（验证密码后删除所有数据） */
    suspend fun selfDeleteAccount(password: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("password", password) }
        val json = request("POST", "/api/user/self-delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "注销失败"))
    }

    /** 管理员操作：删除用户账号 */
    suspend fun adminDeleteUser(userId: Long, blockEmail: Boolean = false, blockIp: Boolean = false): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", userId); put("block_email", blockEmail); put("block_ip", blockIp) }
        val json = request("POST", "/api/admin/user/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 开发者 / 管理员主动创建用户（培养开发者自建账号）。username 选填，qq/password 必填，avatar 选填（base64 PNG）。 */
    suspend fun adminCreateUser(
        username: String,
        qqNumber: String,
        password: String,
        avatarB64: String? = null
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("username", username)
            put("qq_number", qqNumber)
            put("password", password)
            if (!avatarB64.isNullOrEmpty()) put("avatar", avatarB64)
        }
        val json = request("POST", "/api/admin/users/create", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "创建失败"))
    }

    /** 开发者修改用户资料（ID/用户名/QQ号/注册时间），仅开发者可用 */
    suspend fun adminUpdateUser(
        userId: Long,
        newId: Long? = null,
        username: String? = null,
        qqNumber: String? = null,
        createdAt: Long? = null
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("user_id", userId)
            newId?.let { put("new_id", it) }
            username?.let { put("username", it) }
            qqNumber?.let { put("qq_number", it) }
            createdAt?.let { put("created_at", it) }
        }
        val json = request("POST", "/api/admin/user/update", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "更新失败"))
    }

    // ==================== 平台管理员 ====================

    /** 授权平台管理员（仅开发者） */
    suspend fun grantPlatformAdmin(userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", userId) }
        val json = request("POST", "/api/admin/platform-admin/grant", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "授权失败"))
    }

    /** 删除与指定用户的会话消息（开发者接口，永久删除），返回删除条数 */
    suspend fun deleteConversation(friendId: Long): ApiResult<Int> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("friend_id", friendId) }
        val json = request("POST", "/api/dev/delete-conversation", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
        json.optJSONObject("data")?.optInt("count") ?: 0
    }

    /** 清理两个用户 ID 之间的全部对话数据（含图片/卡片，永久删除），返回删除条数（仅开发者） */
    suspend fun cleanConversationBetween(userA: Long, userB: Long): ApiResult<Int> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_a", userA); put("user_b", userB) }
        val json = request("POST", "/api/dev/clean-conversation", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "清理失败"))
        json.optJSONObject("data")?.optInt("count") ?: 0
    }

    /** 撤销平台管理员（仅开发者） */
    suspend fun revokePlatformAdmin(userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", userId) }
        val json = request("POST", "/api/admin/platform-admin/revoke", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "撤销失败"))
    }

    /** 取消授权 - 撤销管理员的全部权限（需要 admin.revoke 权限） */
    suspend fun revokeAdminPermissions(userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", userId) }
        val json = request("POST", "/api/admin/permissions/revoke-admin", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 获取平台管理员列表（仅开发者） */
    suspend fun listPlatformAdmins(): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/platform-admin/list", authenticated = true)
        json.optJSONArray("data") ?: JSONArray()
    }

    /** 获取用户细粒度权限（仅开发者） */
    suspend fun getUserPermissions(userId: Long): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/permissions?user_id=$userId", authenticated = true)
        json.optJSONArray("data") ?: JSONArray()
    }

    /** 保存用户细粒度权限（仅开发者） */
    suspend fun saveUserPermissions(userId: Long, perms: List<String>): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", userId); put("perms", JSONArray(perms)) }
        val json = request("POST", "/api/admin/permissions/save", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /** 撤回管理员操作（仅开发者） */
    suspend fun revertAdminOperation(logId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("log_id", logId) }
        val json = request("POST", "/api/admin/admin-logs/revert", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "撤回失败"))
    }

    /** 获取管理员操作日志（仅开发者） */
    suspend fun getAdminLogs(limit: Int = 50, offset: Int = 0, adminUserId: Long = 0): ApiResult<JSONObject> = safeCall(authenticated = true) {
        var path = "/api/admin/admin-logs?limit=$limit&offset=$offset"
        if (adminUserId > 0) path += "&admin_user_id=$adminUserId"
        val json = request("GET", path, authenticated = true)
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 清空所有管理员操作日志（仅开发者） */
    suspend fun clearAdminLogs(): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject()
        val json = request("POST", "/api/admin/admin-logs/clear", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "清空失败"))
    }

    /** 获取今日 DAU（去重登录用户数） */
    suspend fun getTodayActiveUsers(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/stats/today-active-users", authenticated = true)
        checkSuccess(json)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 获取今日登录总次数 */
    suspend fun getTodayLoginCount(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/stats/total-login-count", authenticated = true)
        checkSuccess(json)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 按用户名或 ID 查用户登录统计详情 */
    suspend fun getUserLoginStats(query: String): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/stats/user-login-stats?query=${java.net.URLEncoder.encode(query, "UTF-8")}", authenticated = true)
        checkSuccess(json)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 今日活跃用户列表（去重） */
    suspend fun getTodayActiveUsersList(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/stats/today-active-users-list", authenticated = true)
        checkSuccess(json)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 查询当前用户是否为平台管理员 */
    suspend fun checkIsPlatformAdmin(): ApiResult<Boolean> = safeCall(authenticated = true) {
        val admins = listPlatformAdmins()
        if (admins.success && admins.data != null) {
            for (i in 0 until admins.data.length()) {
                val obj = admins.data.getJSONObject(i)
                if (obj.optLong("user_id") == currentUserId) {
                    return@safeCall true
                }
            }
        }
        false
    }

    /** 管理员操作：踢出指定用户（强制离线） */
    suspend fun adminKickUser(targetUserId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("user_id", targetUserId) }
        val json = request("POST", "/api/admin/user/kick", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "踢出失败"))
    }

    /** 管理员操作：踢出全部用户（除开发者外） */
    suspend fun adminKickAll(): ApiResult<Unit> = safeCall(authenticated = true) {
        val json = request("POST", "/api/admin/user/kick-all", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }


    /** 管理员操作：获取用户最近的位置消息 */
    suspend fun adminGetUserLocation(userId: Long): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/user/location?user_id=$userId", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员操作：获取用户全部位置历史记录 */
    suspend fun adminGetUserLocations(userId: Long): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/user/locations?user_id=$userId", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 用户自动上报位置 */
    suspend fun uploadLocation(lat: Double, lng: Double, address: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("lat", lat); put("lng", lng); put("address", address) }
        val json = request("POST", "/api/location/upload", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上报失败"))
    }

    /** 管理员查询用户服务器信息 */
    /** 获取与某好友的隐私设置 */
    suspend fun getPrivacySettings(friendId: Long): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/privacy/settings?friend_id=$friendId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 更新隐私设置 */
    suspend fun updatePrivacySettings(friendId: Long, masterOn: Boolean, noExit: Boolean, noScreenshot: Boolean, noRecording: Boolean, detectScreenshot: Boolean = false, detectRecording: Boolean = false): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("friend_id", friendId); put("master_on", masterOn)
            put("no_exit", noExit); put("no_screenshot", noScreenshot); put("no_recording", noRecording)
            put("detect_screenshot", detectScreenshot); put("detect_recording", detectRecording)
        }
        val json = request("POST", "/api/privacy/settings/update", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /** 发送隐私告警（截图/录屏提醒到对方） */
    /** 发送隐私锁定请求 */
    suspend fun sendPrivacyRequest(friendId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("friend_id", friendId) }
        val json = request("POST", "/api/privacy/request-send", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    /** 响应隐私锁定请求 */
    suspend fun respondPrivacyRequest(fromUserId: Long, accepted: Boolean): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("from_user_id", fromUserId); put("accepted", accepted) }
        val json = request("POST", "/api/privacy/request-respond", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    // ==================== 申请清空聊天记录（双向同意） ====================

    /** 申请清空聊天记录：向对方发送请求卡片 */
    suspend fun sendClearChatRequest(friendId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("friend_id", friendId) }
        val json = request("POST", "/api/messages/clear-chat-request", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    /** 同意清空聊天记录：双向永久删除会话数据 */
    suspend fun agreeClearChat(friendId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("friend_id", friendId) }
        val json = request("POST", "/api/messages/clear-chat", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 拒绝清空聊天记录：通知对方 */
    suspend fun rejectClearChat(friendId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("friend_id", friendId) }
        val json = request("POST", "/api/messages/clear-chat-reject", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 发送取消锁定请求 */
    suspend fun sendUnlockRequest(friendId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("friend_id", friendId) }
        val json = request("POST", "/api/privacy/unlock-request", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    /** 响应取消锁定请求 */
    suspend fun respondUnlockRequest(fromUserId: Long, accepted: Boolean): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("from_user_id", fromUserId); put("accepted", accepted) }
        val json = request("POST", "/api/privacy/unlock-respond", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 检查与某用户的锁定状态 */
    suspend fun checkPrivacyLock(userId: Long): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/privacy/request-status?user_id=$userId", authenticated = true)
        if (!checkSuccess(json)) throw Exception("查询失败")
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 获取好友对本人的隐私设置 */
    suspend fun getFriendPrivacySettings(friendId: Long): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/privacy/settings/friend?friend_id=$friendId", authenticated = true)
        if (!checkSuccess(json)) throw Exception("查询失败")
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    suspend fun sendPrivacyAlert(friendId: Long, type: String, blocked: Boolean): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("friend_id", friendId); put("type", type); put("blocked", blocked)
        }
        val json = request("POST", "/api/privacy/alert", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    suspend fun adminGetUserServer(userId: Long): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/user/server?user_id=$userId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员重置用户密码（明文 → 服务端统一 bcrypt 哈希后存储） */
    suspend fun adminResetUserPassword(userId: Long, newPassword: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("user_id", userId)
            put("new_password", newPassword)
        }
        val json = request("POST", "/api/admin/user/reset-password", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "重置密码失败"))
    }



    /** 开发者模拟登录：直接以目标用户身份登录（无需密码） */
    suspend fun adminLoginAsUser(targetUserId: Long): ApiResult<LoginResponse> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("target_user_id", targetUserId) }
        val json = request("POST", "/api/admin/login-as-user", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "登录失败"))
        val d = json.optJSONObject("data")!!
        applyDeveloperFlag(d)
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
            signSecret = d.optString("sign_secret", ""),
            signature = d.optString("signature", ""),
            token = d.optString("token"),
            emailVerified = d.optBoolean("email_verified", false)
        )
        // 立即切换当前登录身份
        authToken = response.token
        currentUserId = response.id
        currentUserName = d.optString("username", "")
        currentUserSignature = response.signature
        currentUserEmailVerified = response.emailVerified
        response
    }

    // 兼容旧版的 Avatar URL 方法
    @Deprecated("Use getAvatarUrl() instead")
    fun getAvatarUrlCompat(userId: Long): String = getAvatarUrl(userId)

    // ==================== 群聊 ====================

    suspend fun getGroupMembers(groupId: Long): ApiResult<org.json.JSONArray> = safeCall(authenticated = true) {
        val json = request("GET", "/api/groups/members/$groupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONArray("data") ?: JSONArray()
    }

    suspend fun createGroup(currentUserId: Long, name: String, signature: String, announcement: String,
                            welcomeEnabled: Boolean = false, welcomeText: String = ""): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("user_id", currentUserId)
            put("name", name)
            put("signature", signature)
            put("announcement", announcement)
            put("welcome_enabled", welcomeEnabled)
            put("welcome_text", welcomeText)
        }
        val json = request("POST", "/api/groups/create", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "创建失败"))
        json.optJSONObject("data") ?: throw Exception("数据为空")
    }

    /** 更新群聊设置 */
    suspend fun updateGroupSettings(groupId: Long, name: String, signature: String, announcement: String,
                                     welcomeEnabled: Boolean, welcomeText: String,
                                     notSearchable: Boolean = false, joinRequired: Boolean = false,
                                     announcements: org.json.JSONArray? = null): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("name", name)
            put("signature", signature)
            put("welcome_enabled", welcomeEnabled)
            put("welcome_text", welcomeText)
            put("not_searchable", notSearchable)
            put("join_required", joinRequired)
            if (announcements != null) {
                put("announcements", announcements)
            } else {
                put("announcement", announcement)
            }
        }
        val json = request("POST", "/api/groups/settings", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "更新失败"))
    }

    /** 获取群聊详细信息（用内部正ID） */
    suspend fun getGroupInfo(internalGroupId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/groups/info/$internalGroupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取群信息失败")
        json.optJSONObject("data") ?: throw Exception("数据为空")
    }

    // ==================== 群通知方式 ====================

    /** 读取群通知方式与逐项开关（群成员均可读） */
    suspend fun getGroupNotifySettings(groupId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/groups/notify-settings?group_id=$groupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /**
     * 保存群通知方式与逐项开关。
     * @param mode "group"=以群身份通知 / "broadcast"=以系统广播通知
     * @param disabled 被关闭的提示项 key 列表
     */
    suspend fun saveGroupNotifySettings(
        groupId: Long,
        mode: String,
        disabled: List<String>
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("mode", mode)
            put("disabled", JSONArray(disabled))
        }
        val json = request("POST", "/api/groups/notify-settings", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /**
     * 读取群敏感词配置。
     * data: {enabled, words:[...], action:"recall"|"mute"|"kick", mute_seconds, kick_blacklist, notice_text}
     */
    suspend fun getGroupSensitiveSettings(groupId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/group/sensitive?group_id=$groupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 保存群敏感词配置（词表整体覆盖）。 */
    suspend fun saveGroupSensitiveSettings(
        groupId: Long,
        enabled: Boolean,
        words: List<String>,
        action: String,
        muteSeconds: Long,
        kickBlacklist: Boolean,
        noticeText: String,
        bypassRole: String = "admin",
        blockLinks: Boolean = false
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("enabled", enabled)
            put("words", JSONArray(words))
            put("action", action)
            put("mute_seconds", muteSeconds)
            put("kick_blacklist", kickBlacklist)
            put("notice_text", noticeText)
            put("bypass_role", bypassRole)
            put("block_links", blockLinks)
        }
        val json = request("POST", "/api/group/sensitive", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /** 群黑名单列表：data.items = [{user_id, username, blocked_at}] */
    suspend fun getGroupBlacklist(groupId: Long): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val json = request("GET", "/api/group/blacklist?group_id=$groupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
    }

    /** 把某个用户移出群黑名单（解除拉黑后可以重新加群）。 */
    suspend fun removeFromGroupBlacklist(groupId: Long, userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("user_id", userId)
        }
        val json = request("POST", "/api/group/blacklist", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "解除失败"))
    }

    /**
     * 读取群管理员细粒度权限（仅群主可读）。
     * 返回 data：all_keys / default_perms / default_customized / admins[{user_id,username,perms,customized}] / my_perms
     */
    suspend fun getGroupAdminPerms(groupId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/groups/admin-perms?group_id=$groupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /**
     * 保存权限：userId = 0 表示群级默认模板，>0 表示单个管理员覆盖。
     * reset = true 时删除该条记录（管理员回到跟随默认模板）。
     */
    suspend fun saveGroupAdminPerms(
        groupId: Long,
        userId: Long,
        perms: List<String>,
        reset: Boolean = false
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("user_id", userId)
            put("perms", JSONArray(perms))
            put("reset", reset)
        }
        val json = request("POST", "/api/groups/admin-perms", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /** 群头像URL */
    fun getGroupAvatarUrl(internalGroupId: Long): String = "$serverUrl/api/groups/avatar/$internalGroupId"
    /** 上传群头像 */
    suspend fun uploadGroupAvatar(groupId: Long, avatarBase64: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("avatar", avatarBase64)
        }
        val json = request("POST", "/api/groups/avatar/upload", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上传失败"))
    }

    /** 下载群头像字节数据 */
    suspend fun loadGroupAvatarBytes(groupId: Long): ByteArray? = withContext(Dispatchers.IO) {
        try {
            HttpClient.downloadBytes(getGroupAvatarUrl(groupId), authToken)
        } catch (_: Exception) { null }
    }

    /** 按 display_id 搜索群聊 */
    suspend fun searchGroups(keyword: String): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val json = request("GET", "/api/search-groups?q=$encoded", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "搜索失败"))
        json.optJSONArray("data") ?: JSONArray()
    }

    // ==================== 群管理 ====================

    /**
     * 设置群内发言频率（群主 / 管理员）。
     * @param limit -1=不限制（关闭），0=全员禁言，N>0=每位成员每分钟最多 N 条
     */
    suspend fun setGroupMsgRate(groupId: Long, limit: Int, scope: Int? = null): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("limit", limit)
            // scope：0=群主及管理员不受限，1=管理员受限，2=全部受限；null=不改动作用范围
            if (scope != null) put("scope", scope)
        }
        val json = request("POST", "/api/groups/msg-rate", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "设置失败"))
    }

    /** 解散群聊（仅群主） */
    suspend fun dissolveGroup(groupId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("group_id", groupId) }
        val json = request("POST", "/api/groups/dissolve", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "解散失败"))
    }

    /** 转让群主（仅群主） */
    suspend fun transferOwner(groupId: Long, newOwnerId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("new_owner_id", newOwnerId)
        }
        val json = request("POST", "/api/groups/transfer", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "转让失败"))
    }

    /** 设置/取消管理员（仅群主） */
    suspend fun setGroupAdmin(groupId: Long, userId: Long, role: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("user_id", userId)
            put("role", role)
        }
        val json = request("POST", "/api/groups/set-admin", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /**
     * 五子棋房间探活（邀请卡片用）：房间不存在 / 已开局 / 已满 → 邀请视为失效。
     * 返回 can_join；网络异常时返回 false（由 safeCall 包装成失败结果，调用方按"未知"处理）。
     */
    suspend fun getGomokuRoomStatus(roomId: String): ApiResult<Boolean> = safeCall(authenticated = false) {
        val json = request("GET", "/api/gomoku/room?room_id=${java.net.URLEncoder.encode(roomId, "UTF-8")}")
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        json.optJSONObject("data")?.optBoolean("can_join", false) ?: false
    }

    /** 搜索用户（按邮箱/用户名/ID） */
    suspend fun searchUsersForGroup(keyword: String): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("keyword", keyword) }
        val json = request("POST", "/api/users/search", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "搜索失败"))
        json.optJSONArray("data") ?: JSONArray()
    }

    /** 管理员拉人入群 */
    suspend fun addGroupMember(groupId: Long, userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("group_id", groupId); put("user_id", userId) }
        val json = request("POST", "/api/groups/add-member", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 应答"拉我时需经过我同意"的入群邀请 */
    suspend fun respondGroupInvite(groupId: Long, inviterId: Long, approve: Boolean): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("inviter_id", inviterId)
            put("approve", approve)
        }
        val json = request("POST", "/api/groups/respond-invite", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 移除群成员（可拉黑） */
    suspend fun removeGroupMember(groupId: Long, userId: Long, blockUser: Boolean = false): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("group_id", groupId); put("user_id", userId); put("block_user", blockUser) }
        val json = request("POST", "/api/groups/remove-member", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 确认收到群公告（幂等，服务端记录 公告标识=公告文本+用户+时间） */
    suspend fun confirmGroupAnnouncement(groupId: Long, text: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("text", text)
        }
        val json = request("POST", "/api/groups/announcement/confirm", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "确认失败"))
    }

    /** 群公告「确认收到」名单（仅群主/管理员）：返回 [{user_id,nickname,confirmed_at}] */
    suspend fun getAnnouncementConfirms(groupId: Long, text: String): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("text", text)
        }
        val json = request("POST", "/api/groups/announcement/confirms", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data")?.optJSONArray("users") ?: JSONArray()
    }

    // ==================== 加群申请 ====================

    /** 提交加群申请（返回实际消息内容以区分"已提交申请"和"已加入群聊"） */
    suspend fun submitJoinRequest(groupId: Long, reason: String): ApiResult<String> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("reason", reason)
        }
        val json = request("POST", "/api/groups/join-request", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "提交失败"))
        json.optString("message", "提交成功")
    }

    /** 获取待审批加群申请列表（群主/管理员） */
    suspend fun getJoinRequests(groupId: Long): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val json = request("GET", "/api/groups/join-requests/$groupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONArray("data") ?: JSONArray()
    }

    /** 审批加群申请 */
    suspend fun reviewJoinRequest(requestId: Long, approve: Boolean): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("request_id", requestId)
            put("approve", approve)
        }
        val json = request("POST", "/api/groups/review-join", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 忽略加群申请（不计入横幅，列表仍可见） */
    suspend fun ignoreJoinRequest(requestId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("request_id", requestId) }
        val json = request("POST", "/api/groups/ignore-join", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 获取待审批加群申请数量 */
    suspend fun getJoinRequestCount(groupId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/groups/join-request-count/$groupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: throw Exception("数据为空")
    }

    // ==================== 群待办（群主/管理员） ====================

    /** 把某条群消息设为群待办。messageId 为群消息的服务端 id。 */
    suspend fun setGroupTodo(messageId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("message_id", messageId) }
        val json = request("POST", "/api/group/set_todo", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "设置失败"))
    }

    /** 对所有人撤回群待办（清除后全员横幅消失）。groupId 为群内部 id。 */
    suspend fun clearGroupTodo(groupId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("group_id", groupId) }
        val json = request("POST", "/api/group/clear_todo", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "撤回失败"))
    }

    /** 获取群当前生效的待办；无则返回 null。 */
    suspend fun getGroupTodo(groupId: Long): ApiResult<GroupTodo?> = safeCall(authenticated = true) {
        val json = request("GET", "/api/group/todo?group_id=$groupId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        val obj = json.optJSONObject("data")?.optJSONObject("todo")
        if (obj == null) null
        else GroupTodo(
            groupId = obj.optLong("group_id", groupId),
            messageId = obj.optLong("message_id", 0L),
            setterName = obj.optString("setter_name", ""),
            snippet = obj.optString("snippet", ""),
            createdAt = obj.optLong("created_at", 0L)
        )
    }

    // ==================== 社区帖子 ====================

    /** 获取帖子列表 */
    suspend fun getCommunityPosts(page: Int = 1, limit: Int = 20, type: String = "", keyword: String = "", hasRes: String = "", mine: Boolean = false): ApiResult<PostListResponse> = safeCall(authenticated = true) {
        val typeParam = if (type.isNotEmpty()) "&type=$type" else ""
        val hasResParam = if (hasRes.isNotEmpty()) "&has_res=$hasRes" else ""
        val searchParam = if (keyword.isNotEmpty()) "&q=${java.net.URLEncoder.encode(keyword, "UTF-8")}" else ""
        val mineParam = if (mine) "&mine=1" else ""
        val json = request("GET", "/api/community/posts?page=$page&limit=$limit$typeParam$hasResParam$searchParam$mineParam", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        val data = json.optJSONObject("data") ?: throw Exception("数据为空")
        val arr = data.optJSONArray("posts") ?: JSONArray()
        val posts = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            PostListItem(
                id = obj.optLong("id"),
                user_id = obj.optLong("user_id"),
                username = obj.optString("username"),
                title = obj.optString("title"),
                content = obj.optString("content"),
                created_at = obj.optLong("created_at"),
                post_type = obj.optString("post_type", "post"),
                res_types = obj.optString("res_types"),
                res_count = obj.optInt("res_count"),
                first_resource_url = obj.optString("first_resource_url"),
                image_urls = obj.optJSONArray("image_urls")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
                is_adult = obj.optBoolean("is_adult")
            )
        }
        PostListResponse(
            posts = posts,
            total = data.optInt("total"),
            page = data.optInt("page"),
            limit = data.optInt("limit")
        )
    }

    // ==================== 管理员群组管理 ====================

    suspend fun adminListGroups(page: Int, limit: Int = 15, keyword: String = ""): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/groups?page=$page&limit=$limit&keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }
    suspend fun adminUpdateGroupDisplayId(groupId: Long, newDisplayId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("group_id", groupId); put("new_display_id", newDisplayId) }
        val json = request("POST", "/api/admin/groups/update-display-id", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }
    suspend fun adminDeveloperSendMessage(groupId: Long, content: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("group_id", groupId); put("content", content) }
        val json = request("POST", "/api/admin/groups/send-message", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }
    suspend fun adminGetGroupMessages(groupId: Long, page: Int = 1, limit: Int = 30): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/groups/messages/$groupId?page=$page&limit=$limit", authenticated = true)
        json.optJSONArray("data") ?: JSONArray()
    }

    /**
     * 按创建者批量解散群聊（仅开发者）。
     * @param dryRun true=只查询数量/预览，false=真正解散
     * @param limit 本次最多清理多少个；<=0 = 全部
     */
    suspend fun adminDissolveGroupsByCreator(creatorId: Long, dryRun: Boolean, limit: Int = 0): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("creator_id", creatorId)
            put("dry_run", dryRun)
            if (limit > 0) put("limit", limit)
        }
        val json = request("POST", "/api/admin/groups/dissolve-by-creator", body, authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 获取单个帖子详情 */
    suspend fun getCommunityPostDetail(postId: Long): ApiResult<CommunityPost> = safeCall(authenticated = true) {
        val json = request("GET", "/api/community/post/$postId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        val d = json.optJSONObject("data") ?: throw Exception("数据为空")
        parseCommunityPost(d)
    }

    private fun parseCommunityPost(d: JSONObject): CommunityPost {
        val resArr = d.optJSONArray("resources") ?: JSONArray()
        val resources = (0 until resArr.length()).map { i ->
            val r = resArr.getJSONObject(i)
            CommunityResource(
                id = r.optLong("id"),
                post_id = r.optLong("post_id"),
                resource_type = r.optString("resource_type"),
                file_name = r.optString("file_name"),
                file_path = r.optString("file_path"),
                file_size = r.optLong("file_size")
            )
        }
        return CommunityPost(
            id = d.optLong("id"),
            user_id = d.optLong("user_id"),
            username = d.optString("username"),
            title = d.optString("title"),
            content = d.optString("content"),
            created_at = d.optLong("created_at"),
            post_type = d.optString("post_type", "post"),
            has_liked = d.optBoolean("has_liked"),
            likes_count = d.optInt("likes_count"),
            comments_count = d.optInt("comments_count"),
            is_adult = d.optBoolean("is_adult"),
            resources = resources
        )
    }

    /** 创建帖子 */
    suspend fun createCommunityPost(title: String, content: String, postType: String = "post", isAdult: Boolean = false): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("title", title); put("content", content); put("post_type", postType); put("is_adult", isAdult) }
        val json = request("POST", "/api/community/post/create", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "创建失败"))
        json.optJSONObject("data") ?: throw Exception("数据为空")
    }

    /** 上传文件（multipart） */
    suspend fun uploadChatMedia(fileBytes: ByteArray, fileName: String, mimeType: String): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val raw = HttpClient.multipart(
                "$serverUrl/api/messages/upload-media",
                files = listOf(HttpClient.MultipartFile("file", fileName, mimeType, fileBytes)),
                authToken = authToken
            )
            try {
                val json = JSONObject(raw)
                if (json.optInt("code", 500) == 200) {
                    ApiResult(true, "上传成功", json.optJSONObject("data"))
                } else {
                    ApiResult(false, json.optString("message", "上传失败: $raw"))
                }
            } catch (jsonErr: Exception) {
                ApiResult(false, "服务器响应格式错误: $raw")
            }
        } catch (e: Exception) {
            ApiResult(false, "上传失败: ${e.message}")
        }
    }

    /**
     * 删除聊天媒体文件（AI 生成图清理等）。name 为 /chat-media/ 后去掉前缀的纯文件名。
     */
    suspend fun deleteChatMedia(name: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("name", name).toString()
            val raw = HttpClient.jsonRequestAllowError("POST", "$serverUrl/api/messages/delete-chat-media", body, authToken = authToken)
            try {
                val json = JSONObject(raw)
                if (json.optInt("code", 500) == 200) ApiResult(true, "已删除")
                else ApiResult(false, json.optString("message", "删除失败: $raw"))
            } catch (jsonErr: Exception) {
                ApiResult(false, "服务器响应格式错误: $raw")
            }
        } catch (e: Exception) {
            ApiResult(false, "删除失败: ${e.message}")
        }
    }

    /**
     * 带进度的文件上传（流式读取本地文件，边传边上报进度），用于视频等大文件。
     * 与 [uploadChatMedia] 同接口路径、同响应格式，仅改为从文件流式读取并回调进度。
     */
    suspend fun uploadChatMediaFile(file: java.io.File, fileName: String, mimeType: String, onProgress: ((Float) -> Unit)? = null): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val raw = HttpClient.uploadStreamWithProgress(
                url = "$serverUrl/api/messages/upload-media",
                fields = emptyMap(),
                input = file.inputStream(),
                partName = "file",
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = file.length(),
                authToken = authToken,
                onProgress = { sent, total -> if (total > 0) onProgress?.invoke((sent.toFloat() / total).coerceIn(0f, 1f)) }
            )
            try {
                val json = JSONObject(raw)
                if (json.optInt("code", 500) == 200) {
                    ApiResult(true, "上传成功", json.optJSONObject("data"))
                } else {
                    ApiResult(false, json.optString("message", "上传失败: $raw"))
                }
            } catch (jsonErr: Exception) {
                ApiResult(false, "服务器响应格式错误: $raw")
            }
        } catch (e: Exception) {
            // HttpStatusException 的 body 里才是服务端面向用户的真实原因（如"文件过大，最大100MB"、
            // "不支持的文件格式"），不要只显示 "HTTP 400" 这类状态码；同时带上状态码供上层判断是否需重试。
            val hse = e as? HttpClient.HttpStatusException
            val detail = hse?.let {
                runCatching { JSONObject(it.body).optString("message").takeIf { m -> m.isNotBlank() } }.getOrNull()
            }
            ApiResult(success = false, message = detail ?: "上传失败: ${e.message}", httpCode = hse?.code ?: 0)
        }
    }

    suspend fun uploadCommunityFile(postId: Long, fileBytes: ByteArray, fileName: String, mimeType: String): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val resType = when {
                mimeType.startsWith("image/") -> "image"
                mimeType.startsWith("video/") -> "video"
                mimeType.startsWith("audio/") -> "audio"
                else -> "file"
            }
            val raw = HttpClient.multipart(
                "$serverUrl/api/community/upload",
                fields = mapOf("post_id" to postId.toString(), "resource_type" to resType),
                files = listOf(HttpClient.MultipartFile("file", fileName, mimeType, fileBytes)),
                authToken = authToken
            )
            val json = JSONObject(raw)
            if (json.optInt("code", 500) == 200) {
                ApiResult(true, "上传成功", json.optJSONObject("data"))
            } else {
                ApiResult(false, json.optString("message", "上传失败"))
            }
        } catch (e: Exception) {
            android.util.Log.e("Community", "Upload error", e)
            ApiResult(false, "上传失败: ${e.localizedMessage ?: "网络错误"}")
        }
    }

    /** 流式上传文件（边读边写网络 + 进度回调，不缓冲整文件到内存） */
    suspend fun uploadCommunityFileStreaming(
        postId: Long,
        inputStream: java.io.InputStream,
        fileName: String,
        mimeType: String,
        totalBytes: Long = -1,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val resType = when {
                mimeType.startsWith("image/") -> "image"
                mimeType.startsWith("video/") -> "video"
                mimeType.startsWith("audio/") -> "audio"
                else -> "file"
            }
            val raw = HttpClient.uploadStreamWithProgress(
                "$serverUrl/api/community/upload",
                fields = mapOf("post_id" to postId.toString(), "resource_type" to resType),
                input = inputStream,
                partName = "file",
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = totalBytes,
                authToken = authToken,
                onProgress = onProgress
            )
            val json = JSONObject(raw)
            if (json.optInt("code", 500) == 200) {
                ApiResult(true, "上传成功", json.optJSONObject("data"))
            } else {
                ApiResult(false, json.optString("message", "上传失败"))
            }
        } catch (e: Exception) {
            android.util.Log.e("Community", "Streaming upload error", e)
            ApiResult(false, "上传失败: ${e.localizedMessage ?: "网络错误"}")
        }
    }

    // ==================== 社区点赞/评论（占位，后续实现） ====================

    suspend fun toggleLike(postId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("post_id", postId) }
        val json = request("POST", "/api/community/like/toggle", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
        json.optJSONObject("data") ?: throw Exception("数据为空")
    }

    suspend fun getComments(postId: Long, page: Int = 1, limit: Int = 20): ApiResult<CommentListResponse> = safeCall(authenticated = true) {
        val json = request("GET", "/api/community/comments/$postId?page=$page&limit=$limit", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        val data = json.optJSONObject("data") ?: throw Exception("数据为空")
        val arr = data.optJSONArray("comments") ?: JSONArray()
        val comments = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            CommentInfo(
                id = obj.optLong("id"),
                user_id = obj.optLong("user_id"),
                username = obj.optString("username"),
                content = obj.optString("content"),
                parent_id = obj.optLong("parent_id", 0),
                created_at = obj.optLong("created_at"),
                reply_count = obj.optInt("reply_count", 0),
                like_count = obj.optInt("like_count", 0),
                liked = obj.optBoolean("liked", false)
            )
        }
        CommentListResponse(comments = comments, total = data.optInt("total"))
    }

    suspend fun addComment(postId: Long, content: String, parentId: Long = 0): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("post_id", postId); put("content", content); put("parent_id", parentId) }
        val json = request("POST", "/api/community/comment/add", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "添加失败"))
    }

    /** 点赞 / 取消点赞评论（toggle）。返回 liked=最新状态, like_count=最新点赞数 */
    suspend fun likeComment(commentId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("comment_id", commentId) }
        val json = request("POST", "/api/community/comment/like", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
        json.optJSONObject("data") ?: throw Exception("数据为空")
    }

    /**
     * 删除评论（自己 / 开发者 / 平台管理员）。后端级联删除所有后代回复，
     * 父楼删掉后其下回复不会留成幽灵数据。
     */
    suspend fun deleteComment(commentId: Long): ApiResult<Int> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("comment_id", commentId) }
        val json = request("POST", "/api/community/comment/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
        json.optJSONObject("data")?.optInt("deleted", 0) ?: 0
    }

    /** 删除帖子 */
    suspend fun deleteCommunityPost(postId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("post_id", postId) }
        val json = request("POST", "/api/community/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 清空当前用户全部动态（直接 DELETE 服务端数据库） */
    suspend fun clearCommunityActivities(): ApiResult<Int> = safeCall(authenticated = true) {
        val json = request("POST", "/api/community/activities/clear", JSONObject(), authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "清空失败"))
        val data = json.optJSONObject("data")
        val count = data?.optInt("deleted_count") ?: 0
        count
    }

    /** 获取最新动态列表 */
    suspend fun getCommunityActivities(page: Int = 1, limit: Int = 20): ApiResult<ActivityListResponse> = safeCall(authenticated = true) {
        val json = request("GET", "/api/community/activities?page=$page&limit=$limit", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        val data = json.optJSONObject("data") ?: throw Exception("数据为空")
        val arr = data.optJSONArray("activities") ?: JSONArray()
        val activities = (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            CommunityActivity(
                id = obj.optLong("id"),
                user_id = obj.optLong("user_id"),
                username = obj.optString("username"),
                action_type = obj.optString("action_type"),
                target_type = obj.optString("target_type"),
                target_id = obj.optLong("target_id"),
                target_title = obj.optString("target_title", ""),
                comment_content = obj.optString("comment_content", ""),
                created_at = obj.optLong("created_at")
            )
        }
        ActivityListResponse(
            activities = activities,
            total = data.optInt("total")
        )
    }

    // ==================== 服务器板块 API ====================



    // ==================== 服务器登出 ====================
    suspend fun getMyServer(): ApiResult<ServerInfo?> = safeCall(authenticated = true) {
        val json = request("GET", "/api/server/my", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        val d = json.optJSONObject("data")
        if (d == null || d.length() == 0) {
            return@safeCall null
        }
        val serverStatus = d.optString("status", "offline")
        val info = ServerInfo(
            id = d.optLong("id"),
            ownerUserId = d.optLong("owner_user_id"),
            ownerUsername = d.optString("owner_username", ""),
            name = d.optString("name", ""),
            domain = d.optString("domain", ""),
            serverUrl = d.optString("server_url", ""),
            createdAt = d.optLong("created_at"),
            status = serverStatus
        )
        currentServerName = info.name
        currentServerDomain = info.domain
        info
    }

    /** 检查版本更新 */
    suspend fun checkAppVersion(): ApiResult<org.json.JSONObject> = safeCall(authenticated = false) {
        val json = request("GET", "/api/app/version", authenticated = false)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    // ==================== 体验版卡密 ====================

    /** 检查体验版模式是否开启 */
    suspend fun checkTrialMode(): ApiResult<org.json.JSONObject> = safeCall(authenticated = false) {
        val json = request("GET", "/api/check-trial", authenticated = false)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 验证卡密 */
    suspend fun validateCardKey(key: String, userId: Long): ApiResult<org.json.JSONObject> = safeCall(authenticated = false) {
        val body = org.json.JSONObject().apply {
            put("key", key)
            put("user_id", userId)
        }
        val json = request("POST", "/api/validate-card-key", body, authenticated = false)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "验证失败"))
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员：获取体验版状态 */
    suspend fun adminGetTrialMode(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/trial-mode", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员：设置体验版开关 */
    suspend fun adminSetTrialMode(enabled: Boolean): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("enabled", enabled) }
        val json = request("POST", "/api/admin/trial-mode/set", body, authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员：获取体验版版本锁定设置 */
    suspend fun adminGetTrialVersionSettings(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/trial-mode/version-settings", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员：保存体验版版本锁定设置 */
    suspend fun adminSaveTrialVersionSettings(minVersion: String, lockVersion: String): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("min_supported_version", minVersion)
            put("force_lock_version", lockVersion)
        }
        val json = request("POST", "/api/admin/trial-mode/version-settings/save", body, authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者：获取"抵防"全局防御开关状态 */
    suspend fun getDefense(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/dev/defense", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者：设置"抵防"某个防御开关 */
    suspend fun setDefense(key: String, value: Boolean): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("key", key)
            put("value", value)
        }
        val json = request("POST", "/api/dev/defense", body, authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者：读取短视频总开关（disabled）+ 开发者自定义的提示文案（message） */
    suspend fun getShortVideoGate(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/dev/shortvideo-gate", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者：设置短视频总开关 / 上传开关 / 提示文案（均可只传其一） */
    suspend fun setShortVideoGate(
        disabled: Boolean? = null,
        message: String? = null,
        uploadDisabled: Boolean? = null
    ): ApiResult<org.json.JSONObject> =
        safeCall(authenticated = true) {
            val body = org.json.JSONObject().apply {
                if (disabled != null) put("disabled", disabled)
                if (uploadDisabled != null) put("upload_disabled", uploadDisabled)
                if (message != null) put("message", message)
            }
            val json = request("POST", "/api/dev/shortvideo-gate", body, authenticated = true)
            json.optJSONObject("data") ?: org.json.JSONObject()
        }

    /** 任意登录用户：读取短视频总开关（客户端「进入短视频页后」据此显示提示） */
    suspend fun getShortVideoGatePublic(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/shortvideo/gate", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /**
     * 管理员：生成卡密
     * @param durationMinutes 有效期（分钟）
     * @param cardType 卡密类型："public"=公共卡密（谁都能用，无需绑定账号）/ "personal"=个人卡密（必须绑定账号 ID，他人无法使用）
     * @param rewardType 奖励类型：当前仅 "token"=发放 token（会员体系已移除）
     * @param tokenAmount rewardType="token" 时的 token 数量（用户使用后直接加到账户）
     * @param bindUserId cardType="personal" 时绑定的账号 ID
     *
     * 后端（handleAdminGenerateCardKey）已落库 rewardType/tokenAmount/cardType/bindUserID；
     * 用户激活时（handleValidateCardKey）已按 rewardType 实际发放 Token。
     */
    suspend fun adminGenerateCardKey(
        category: String = "trial",
        durationMinutes: Int = 0,
        cardType: String = "public",
        rewardType: String = "token",
        tokenAmount: Long = 0,
        bindUserId: Long = 0,
        domain: String = "",
        password: String = "",
        customKey: String = "",
        permissionLevel: String = "B",
        dangerQuota: Int = 0
    ): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("category", category)
            put("duration_minutes", durationMinutes)
            put("card_type", cardType)
            put("reward_type", rewardType)
            if (rewardType == "token") put("token_amount", tokenAmount)
            if (cardType == "personal") put("bind_user_id", bindUserId)
            if (category == "site") {
                put("domain", domain)
                put("password", password)
                put("custom_key", customKey)
                put("permission_level", permissionLevel)
                put("danger_quota", dangerQuota)
            }
        }
        val json = request("POST", "/api/admin/card-keys/generate", body, authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员：获取卡密列表（category: "trial"=体验卡密 / "reward"=奖励卡密 / ""=全部） */
    suspend fun adminListCardKeys(category: String = ""): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val url = if (category.isNotEmpty()) "/api/admin/card-keys/list?category=$category" else "/api/admin/card-keys/list"
        val json = request("GET", url, authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 管理员：取消卡密使用 */
    suspend fun adminCancelCardKey(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/admin/card-keys/cancel", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "取消失败"))
    }

    /** 管理员：删除卡密 */
    suspend fun adminDeleteCardKey(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/admin/card-keys/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 管理员：更新站点卡密权限等级和危险次数 */
    suspend fun adminUpdateCardKeyPermission(id: Long, permissionLevel: String, dangerQuota: Int): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("id", id)
            put("permission_level", permissionLevel)
            put("danger_quota", dangerQuota)
        }
        val json = request("POST", "/api/admin/card-keys/update-permission", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "更新失败"))
    }

    /** 管理员：给站点卡添加备份余额（可同时保留的最大备份数） */
    suspend fun adminAddCardBackupBalance(id: Long, amount: Int): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("id", id)
            put("amount", amount)
        }
        val json = request("POST", "/api/admin/card-keys/backup/add", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "添加备份余额失败"))
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者：查看某域名的服务器绑定与在线状态 */
    suspend fun adminGetSiteEndpoint(domain: String): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val d = java.net.URLEncoder.encode(domain, "UTF-8")
        val json = request("GET", "/api/admin/site-endpoints/get?domain=$d", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者：手动指定域名的服务器端点（换服务器时可直接改绑，不再依赖重新打包客户端） */
    suspend fun adminSaveSiteEndpoint(domain: String, host: String, scheme: String, httpPort: Int, wsPort: Int, token: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("domain", domain)
            put("host", host)
            put("scheme", scheme)
            put("http_port", httpPort)
            put("ws_port", wsPort)
            put("token", token)
        }
        val json = request("POST", "/api/admin/site-endpoints/save", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /** 开发者：取消手动绑定，恢复站点服务自动上报 */
    suspend fun adminResetSiteEndpoint(domain: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("domain", domain) }
        val json = request("POST", "/api/admin/site-endpoints/reset", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    // ==================== 免费 API 提交与审核 ====================

    /** 用户提交免费 API（API Key / Base URL / 模型名），等待开发者审核 */
    suspend fun submitFreeApi(apiKey: String, baseUrl: String, modelName: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("api_key", apiKey)
            put("base_url", baseUrl)
            put("model_name", modelName)
        }
        val json = request("POST", "/api/free-api/submit", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "提交失败"))
    }

    /** 开发者/管理员：获取全部免费 API 提交（含提交者信息） */
    suspend fun adminListFreeApis(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/free-api/list", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者/管理员：同意(approve)或拒绝(reject)某条免费 API 提交 */
    suspend fun adminReviewFreeApi(id: Long, action: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("id", id)
            put("action", action)
        }
        val json = request("POST", "/api/free-api/review", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 开发者/管理员：下架(已通过的)某条免费 API */
    suspend fun adminTakedownFreeApi(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("id", id)
        }
        val json = request("POST", "/api/free-api/takedown", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 删除免费 API 记录(仅已下架/已拒绝的记录) */
    suspend fun adminDeleteFreeApi(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("id", id)
        }
        val json = request("POST", "/api/free-api/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 普通用户：获取已通过的免费 API 列表（用于「选择免费 API」直接调用上游），返回 id/key/url/model/latency/marked_down */
    suspend fun listAvailableFreeApis(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/free-api/available", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /**
     * 服务端测速：由服务器向该免费 API 的上游发一次最小请求（max_tokens=1）。
     * 安全（密钥不下发）：贡献者的 api_key 不再随 available 接口下发，客户端自己
     * 无法直连上游测速，改由服务端代发。返回 Pair(是否连通, 延迟毫秒；失败为 -1)。
     * 注意：本结果代表「服务器到上游通不通」，仅作选用参考；全局「已挂」判定
     * 仍归服务端后台周期探测（probeAllFreeApis）。
     */
    suspend fun testFreeApi(id: Long): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        try {
            val body = org.json.JSONObject().apply { put("id", id) }
            val json = request("POST", "/api/free-api/test", body, authenticated = true)
            val data = json.optJSONObject("data") ?: org.json.JSONObject()
            val ok = data.optBoolean("ok", false)
            val ms = data.optLong("latency", -1L)
            ok to (if (ok && ms >= 0) ms else -1L)
        } catch (_: Exception) {
            false to -1L
        }
    }

    /** 用户测速后上报结果（ok=是否连通，latency=毫秒），服务端据此累计失败次数、判定「已挂」 */
    suspend fun pingFreeApi(id: Long, ok: Boolean, latency: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("id", id)
            put("ok", ok)
            put("latency", latency)
        }
        val json = request("POST", "/api/free-api/ping", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上报失败"))
    }

    /** 开发者/管理员：清除某条免费 API 的「已挂」标记与失败计数 */
    suspend fun adminClearFreeApiMark(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/free-api/clear-mark", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    // ==================== 付费 API ====================

    /** 用户提交付费 API（API Key / Base URL / 模型名 / 价格） */
    suspend fun submitPaidApi(
        apiKey: String, baseUrl: String, modelName: String,
        inputPrice: String, outputPrice: String, priceNote: String
    ): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("api_key", apiKey)
            put("base_url", baseUrl)
            put("model_name", modelName)
            put("input_price", inputPrice)
            put("output_price", outputPrice)
            put("price_note", priceNote)
        }
        val json = request("POST", "/api/paid-api/submit", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "提交失败"))
    }

    /** 普通用户：获取已上架的付费 API 列表（用于选择调用） */
    suspend fun listAvailablePaidApis(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/paid-api/available", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 付费 API 用量扣费：按上传者填写的 input_price/output_price 从使用者 token 余额扣费；requestId 用于幂等防重（同一请求不重复记账）；baseUrl/modelName 用于 paid_api_id 失效时后端按配置兜底匹配 */
    suspend fun consumePaidApi(paidApiId: Long, inputTokens: Long, outputTokens: Long, requestId: String = "", baseUrl: String = "", modelName: String = ""): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("api_id", paidApiId)
            put("input_tokens", inputTokens)
            put("output_tokens", outputTokens)
            if (requestId.isNotEmpty()) put("request_id", requestId)
            if (baseUrl.isNotEmpty()) put("base_url", baseUrl)
            if (modelName.isNotEmpty()) put("model_name", modelName)
        }
        val json = request("POST", "/api/paid-api/consume", body, authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者/管理员：获取全部付费 API 提交（含提交者信息） */
    suspend fun adminListPaidApis(): ApiResult<org.json.JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/paid-api/list", authenticated = true)
        json.optJSONObject("data") ?: org.json.JSONObject()
    }

    /** 开发者/管理员：同意(approve)或拒绝(reject)某条付费 API 提交 */
    suspend fun adminReviewPaidApi(id: Long, action: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply {
            put("id", id)
            put("action", action)
        }
        val json = request("POST", "/api/paid-api/review", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 开发者/管理员：下架已通过的付费 API */
    suspend fun adminTakedownPaidApi(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/paid-api/takedown", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 删除付费 API 记录(仅已下架/已拒绝) */
    suspend fun adminDeletePaidApi(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = org.json.JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/paid-api/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    // ==================== 第三方接入开放平台 API ====================

    /** 管理员：第三方接入申请列表（含申请人用户名） */
    suspend fun openAdminList(): ApiResult<List<JSONObject>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/open/admin/list", authenticated = true)
        val arr = json.optJSONArray("data") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** 管理员：审核操作 approve=同意 / reject=拒绝 */
    suspend fun openAdminReview(id: Long, action: String, reason: String = ""): ApiResult<String> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("id", id)
            put("action", action)
            put("reason", reason)
        }
        val json = request("POST", "/api/open/admin/review", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
        json.optJSONObject("data")?.optString("status") ?: ""
    }

    /** 管理员：吊销应用授权（仅已通过可吊销） */
    suspend fun openAdminRevoke(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/open/admin/revoke", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "吊销失败"))
    }

    /** 管理员：删除接入记录 */
    suspend fun openAdminDelete(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/open/admin/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 管理员：主动创建应用并直接通过（返回 clientId / appSecret） */
    suspend fun openAdminCreate(appName: String, owner: String, scopes: String = "", remark: String = ""): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("appName", appName)
            put("owner", owner)
            put("scopes", scopes)
            put("remark", remark)
        }
        val json = request("POST", "/api/open/admin/create", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "创建失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    // ==================== Agent 应用版本管理 ====================

    /** 管理员：Agent 应用版本列表（含当前生效标记） */
    suspend fun agentAppListVersions(): ApiResult<List<JSONObject>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/agent-app/versions", authenticated = true)
        val arr = json.optJSONArray("data") ?: org.json.JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** 管理员：推送新版本（仅新增记录，不自动设为生效） */
    suspend fun agentAppPushVersion(
        versionCode: Int,
        versionName: String,
        apkUrl: String,
        forceUpdate: Boolean,
        updateMessage: String,
    ): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("apkUrl", apkUrl)
            put("forceUpdate", forceUpdate)
            put("updateMessage", updateMessage)
        }
        val json = request("POST", "/api/admin/agent-app/version", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "推送失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 管理员：切换当前生效版本 */
    suspend fun agentAppActivateVersion(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/admin/agent-app/version/activate", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "切换失败"))
    }

    /** 管理员：删除版本 */
    suspend fun agentAppDeleteVersion(id: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("id", id) }
        val json = request("POST", "/api/admin/agent-app/version/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 用户：提交第三方接入申请（进入待审核） */
    suspend fun openApply(appName: String, reason: String, scopes: String, contact: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("appName", appName)
            put("reason", reason)
            put("scopes", scopes)
            put("contact", contact)
        }
        val json = request("POST", "/api/open/apply", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "提交失败"))
    }

    /** 公开：查询应用信息（返回 appName/scopes/status，供授权页展示真实申请权限） */
    suspend fun openAppInfo(clientId: String): ApiResult<JSONObject> = safeCall(authenticated = false) {
        val json = request("GET", "/api/open/app?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}", authenticated = false)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询应用信息失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 用户：确认第三方授权 → 签发一次性授权码（返回 data 含 code/state） */
    suspend fun authorizeOpen(clientId: String, scopes: String, redirectUri: String = "", state: String = ""): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("client_id", clientId)
            put("scopes", scopes)
            put("redirect_uri", redirectUri)
            put("state", state)
        }
        val json = request("POST", "/api/open/authorize", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "授权失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    // ==================== 服务器文件管理 API ====================

    /** 创建空文件（支持指定父文件夹） */
    suspend fun createServerFile(fileName: String, content: String = "", parentId: Long? = null): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("file_name", fileName)
            put("content", content)
            if (parentId != null) put("parent_id", parentId)
        }
        val json = request("POST", "/api/server/files/create", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "创建失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 创建文件夹 */
    suspend fun createServerFolder(folderName: String, parentId: Long? = null): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("folder_name", folderName)
            if (parentId != null) put("parent_id", parentId)
        }
        val json = request("POST", "/api/server/files/create-folder", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "创建文件夹失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 上传文件（字节数组，支持指定父文件夹） */
    suspend fun uploadServerFile(fileBytes: ByteArray, fileName: String, parentId: Long? = null): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val safeName = fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
        val fields = if (parentId != null) mapOf("parent_id" to parentId.toString()) else emptyMap()
        val raw = HttpClient.multipart(
            "$serverUrl/api/server/files/upload",
            fields = fields,
            files = listOf(HttpClient.MultipartFile("file", safeName, "application/octet-stream", fileBytes)),
            authToken = authToken
        )
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            android.util.Log.e("AuroraApi", "uploadServerFile JSON parse error: response=${maskSensitiveInBody(raw)}")
            throw Exception("服务器返回异常: ${maskSensitiveInBody(raw).take(200)}")
        }
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上传失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 获取文件列表（支持按父文件夹筛选） */
    suspend fun getServerFiles(parentId: Long? = null): ApiResult<List<ServerFileInfo>> = safeCall(authenticated = true) {
        val path = if (parentId != null) "/api/server/files/list?parent_id=$parentId" else "/api/server/files/list"
        val json = request("GET", path, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        val arr = json.optJSONObject("data")?.optJSONArray("files") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ServerFileInfo(
                id = obj.optLong("id"),
                fileName = obj.optString("file_name", ""),
                fileSize = obj.optLong("file_size", 0),
                mimeType = obj.optString("mime_type", ""),
                isDir = obj.optBoolean("is_dir", false),
                parentId = if (obj.has("parent_id") && !obj.isNull("parent_id")) obj.optLong("parent_id", -1) else null,
                createdAt = obj.optLong("created_at", 0),
                updatedAt = obj.optLong("updated_at", 0),
                downloadUrl = obj.optString("download_url", "")
            )
        }
    }

    // ==================== 文件分发（后台中心板块一） ====================

    /** 上传文件到文件分发（生成公开下载直链 /fd/<token>）。
     *  alias 非空时绑定固定链接 /fd/<alias>：同名 alias 属本人则原地替换内容，链接不变。 */
    suspend fun fdUpload(fileBytes: ByteArray, fileName: String, mimeType: String, alias: String = ""): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val safeName = fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
        val raw = HttpClient.multipart(
            "$serverUrl/api/fd/upload",
            fields = if (alias.isBlank()) emptyMap() else mapOf("alias" to alias.trim()),
            files = listOf(HttpClient.MultipartFile("file", safeName, mimeType, fileBytes)),
            authToken = authToken
        )
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            android.util.Log.e("AuroraApi", "fdUpload JSON parse error: response=${maskSensitiveInBody(raw)}")
            throw Exception("服务器返回异常: ${maskSensitiveInBody(raw).take(200)}")
        }
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上传失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 上传文件到文件分发（带进度回调，sent/total 为文件字节；生成公开下载直链 /fd/<token>）。
     *  alias 非空时绑定固定链接 /fd/<alias>，同名且属本人则原地替换（链接不变）。 */
    suspend fun fdUploadWithProgress(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        alias: String = "",
        onProgress: (sent: Long, total: Long) -> Unit
    ): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val safeName = fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
        val raw = HttpClient.uploadWithProgress(
            "$serverUrl/api/fd/upload",
            fields = if (alias.isBlank()) emptyMap() else mapOf("alias" to alias.trim()),
            file = HttpClient.MultipartFile("file", safeName, mimeType, fileBytes),
            authToken = authToken,
            onProgress = onProgress
        )
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            android.util.Log.e("AuroraApi", "fdUpload JSON parse error: response=${maskSensitiveInBody(raw)}")
            throw Exception("服务器返回异常: ${maskSensitiveInBody(raw).take(200)}")
        }
        if (!checkSuccess(json)) throw Exception(json.optString("message", "上传失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 我的文件分发列表（含已用容量 / 速度与容量配置） */
    suspend fun fdList(): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/fd/list", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 删除我的文件分发记录（连同物理文件） */
    suspend fun fdDelete(fileId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("id", fileId) }
        val json = request("POST", "/api/fd/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 设置/取消固定链接（alias 为空串 = 取消，退回 token 链接）。
     *  返回 data: {id, alias, fixed_url, url} */
    suspend fun fdSetAlias(fileId: Long, alias: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("id", fileId)
            put("alias", alias.trim())
        }
        val json = request("POST", "/api/fd/alias", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 管理员：读取配置（user_id=0 → 默认通用配置；>0 → 该用户的单独配置）。
     *  返回 data: {user_id, has_custom, configs:{键值}, default_configs:{键值}, used} */
    suspend fun adminGetFdConfig(userId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/fd/config?user_id=$userId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 管理员：保存配置（键值驱动，不写死字段；值为空串 = 删除该键=该项不限）。
     *  userId=0 → 修改默认(通用)配置；>0 → 修改该用户的单独配置（单独配置的用户不受默认限制约束） */
    suspend fun adminSetFdConfig(userId: Long, configs: JSONObject): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("configs", configs)
        }
        val json = request("POST", "/api/admin/fd/config", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
    }

    /** 管理员：清除某用户的单独配置（恢复为套用默认通用配置） */
    suspend fun adminResetFdConfig(userId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("reset", true)
        }
        val json = request("POST", "/api/admin/fd/config", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "操作失败"))
    }

    /** 管理员：已单独配置的用户列表 data.list = [{user_id, username, configs, used}] */
    suspend fun adminGetFdCustomList(): ApiResult<JSONArray> = safeCall(authenticated = true) {
        val json = request("GET", "/api/admin/fd/config/custom-list", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
    }

    /** 获取文件夹面包屑导航 */
    suspend fun getFolderBreadcrumb(folderId: Long?): ApiResult<List<BreadcrumbItem>> = safeCall(authenticated = true) {        val path = if (folderId != null) "/api/server/files/breadcrumb?folder_id=$folderId" else "/api/server/files/breadcrumb?folder_id=root"
        val json = request("GET", path, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        val arr = json.optJSONObject("data")?.optJSONArray("path") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            BreadcrumbItem(
                id = obj.optLong("id"),
                name = obj.optString("name", "")
            )
        }
    }

    /** 移动文件或文件夹 */
    suspend fun moveServerFile(fileId: Long, targetParentId: Long?): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("file_id", fileId)
            if (targetParentId != null) put("target_parent_id", targetParentId)
            else put("target_parent_id", JSONObject.NULL)
        }
        val json = request("POST", "/api/server/files/move", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "移动失败"))
    }

    /** 获取文件下载链接 */
    fun getServerFileDownloadUrl(fileId: Long): String = "$serverUrl/api/server/files/download/$fileId"

    /** 下载文件内容（直接获取文件字节） */
    suspend fun downloadFileContent(fileId: Long): ApiResult<ByteArray> = safeCall(authenticated = true) {
        try {
            val url = "$serverUrl/api/server/files/download/$fileId"
            ModelLogger.info(AuroraChatApplication.instance, "DL", "GET $url")
            val bytes = HttpClient.downloadBytes(url, authToken)
            ModelLogger.info(AuroraChatApplication.instance, "DL", "返回字节数 len=${bytes.size}")
            bytes
        } catch (e: Exception) {
            ModelLogger.error(AuroraChatApplication.instance, "DL", "下载异常: ${e.message}", e)
            throw Exception("下载失败: ${e.localizedMessage ?: "网络错误"}")
        }
    }

    /** 删除文件或文件夹 */
    suspend fun deleteServerFile(fileId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("file_id", fileId) }
        val json = request("POST", "/api/server/files/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 预览 zip 内容（不解压）。返回 data.entries 列表。 */
    suspend fun zipInfoServerFile(fileId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/server/files/zip-info?id=$fileId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "读取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 解压服务端 zip 文件到同名新文件夹，并登记进数据库。 */
    suspend fun unzipServerFile(fileId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("POST", "/api/server/files/unzip?id=$fileId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "解压失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 关机 */
    suspend fun shutdownServer(): ApiResult<String> = safeCall(authenticated = true) {
        val json = request("POST", "/api/server/shutdown", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "关机失败"))
        json.optJSONObject("data")?.optString("status", "stopping") ?: "stopping"
    }

    /** 重启 */
    suspend fun restartServer(): ApiResult<String> = safeCall(authenticated = true) {
        val json = request("POST", "/api/server/restart", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "重启失败"))
        json.optJSONObject("data")?.optString("status", "restarting") ?: "restarting"
    }

    /** 获取服务器实时状态 */
    suspend fun getServerStatus(): ApiResult<String> = safeCall(authenticated = true) {
        val json = request("GET", "/api/server/status", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "查询失败"))
        json.optJSONObject("data")?.optString("status", "offline") ?: "offline"
    }

    /** 更新文件内容 */
    suspend fun updateServerFile(fileId: Long, content: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("file_id", fileId); put("content", content) }
        val json = request("POST", "/api/server/files/update", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "保存失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 重命名文件 */
    suspend fun renameServerFile(fileId: Long, newName: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("file_id", fileId); put("new_name", newName) }
        val json = request("POST", "/api/server/files/rename", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "重命名失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 搜索文件 */
    suspend fun searchServerFiles(keyword: String): ApiResult<List<ServerFileInfo>> = safeCall(authenticated = true) {
        val encoded = java.net.URLEncoder.encode(keyword, "utf-8")
        val json = request("GET", "/api/server/files/search?q=$encoded", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "搜索失败"))
        val arr = json.optJSONObject("data")?.optJSONArray("files") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ServerFileInfo(
                id = obj.optLong("id"),
                fileName = obj.optString("file_name", ""),
                fileSize = obj.optLong("file_size", 0),
                mimeType = obj.optString("mime_type", ""),
                isDir = obj.optBoolean("is_dir", false),
                parentId = if (obj.has("parent_id") && !obj.isNull("parent_id")) obj.optLong("parent_id", -1) else null,
                createdAt = obj.optLong("created_at", 0),
                updatedAt = obj.optLong("updated_at", 0),
                downloadUrl = obj.optString("download_url", "")
            )
        }
    }

    /** 获取服务器仪表盘数据 */


    // ==================== 漂流瓶 API ====================

    /** 扔瓶子 */
    suspend fun throwBottle(content: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("content", content) }
        val json = request("POST", "/api/bottle/throw", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "扔瓶子失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 捞瓶子 */
    suspend fun pickBottle(): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("POST", "/api/bottle/pick", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "捞瓶子失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 获取漂流瓶总数 */
    suspend fun getBottleCount(): ApiResult<JSONObject> = safeCall(authenticated = false) {
        val json = request("GET", "/api/bottle/count")
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 获取海面漂流瓶列表 */
    suspend fun getBottleList(): ApiResult<JSONArray?> = safeCall(authenticated = true) {
        val json = request("GET", "/api/bottle/list", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data")?.optJSONArray("bottles")
    }

    /** 按 ID 捞取特定漂流瓶 */
    suspend fun pickBottleById(id: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("POST", "/api/bottle/pick/$id", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "捞瓶子失败"))
        json.optJSONObject("data") ?: JSONObject()
    }



    // ==================== 沙盒部署 API ====================

    /** 部署沙盒项目（multipart: name, type, file） */
    suspend fun deploySandbox(name: String, type: String, fileBytes: ByteArray?, fileName: String    ): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val formFields = mutableMapOf("name" to name, "type" to type)
        val files = mutableListOf<HttpClient.MultipartFile>()
        if (fileBytes != null) files.add(HttpClient.MultipartFile("file", fileName, "application/octet-stream", fileBytes))
        val raw = HttpClient.multipartAllowError("$serverUrl/api/sandbox/deploy", formFields, files, authToken)
        val json = JSONObject(raw)
        if (json.optInt("code", 500) != 200) throw Exception(json.optString("message", "部署失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 获取沙盒项目列表 */
    suspend fun getSandboxList(): ApiResult<JSONArray?> = safeCall(authenticated = true) {
        val json = request("GET", "/api/sandbox/list", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONArray("data")
    }

    /** 获取或自动创建用户的单项目 */
    suspend fun getMySandboxProject(): ApiResult<JSONObject?> = safeCall(authenticated = true) {
        val json = request("GET", "/api/sandbox/my-project", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data")
    }

    /** 停止沙盒项目 */
    suspend fun stopSandbox(projectId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val json = request("POST", "/api/sandbox/stop?project_id=$projectId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "停止失败"))
    }

    /** 启动沙盒项目 */
    suspend fun startSandbox(projectId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val json = request("POST", "/api/sandbox/start?project_id=$projectId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "启动失败"))
    }

    /** 删除沙盒项目 */
    suspend fun deleteSandbox(projectId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val json = request("DELETE", "/api/sandbox/delete?project_id=$projectId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 获取沙盒日志 */
    suspend fun getSandboxLogs(projectId: Long): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val json = request("GET", "/api/sandbox/logs?project_id=$projectId", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 获取沙盒文件列表 */
    suspend fun getSandboxFiles(projectId: Long, path: String = "."): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val json = request("GET", "/api/sandbox/files/list?project_id=$projectId&path=$encodedPath", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 读取沙盒文件 */
    suspend fun readSandboxFile(projectId: Long, path: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val json = request("GET", "/api/sandbox/files/read?project_id=$projectId&path=$encodedPath", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "读取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 写入沙盒文件 */
    suspend fun writeSandboxFile(projectId: Long, path: String, content: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val body = JSONObject().apply { put("content", content) }
        val json = request("POST", "/api/sandbox/files/write?project_id=$projectId&path=$encodedPath", body = body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "写入失败"))
    }

    /** 删除沙盒文件或目录 */
    suspend fun deleteSandboxFile(projectId: Long, path: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val json = request("DELETE", "/api/sandbox/files/delete?project_id=$projectId&path=$encodedPath", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    /** 服务端重命名/移动文件或目录（保留二进制内容，不经由文本读写） */
    suspend fun renameSandboxFile(projectId: Long, oldPath: String, newPath: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("old_path", oldPath)
            put("new_path", newPath)
        }
        val json = request("POST", "/api/sandbox/files/rename?project_id=$projectId", body = body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "重命名失败"))
    }

    /** 创建沙盒目录 */
    suspend fun mkdirSandbox(projectId: Long, path: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val json = request("POST", "/api/sandbox/files/mkdir?project_id=$projectId&path=$encodedPath", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "创建失败"))
    }

    /** 上传文件到沙盒目录（支持二进制，如 zip 压缩包）。path 为目标目录。 */
    suspend fun uploadSandboxFile(projectId: Long, path: String, bytes: ByteArray, fileName: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val url = "$serverUrl/api/sandbox/files/upload?project_id=$projectId&path=$encodedPath"
        val files = listOf(HttpClient.MultipartFile("file", fileName, "application/octet-stream", bytes))
        val raw = HttpClient.multipartAllowError(url, emptyMap(), files, authToken)
        val json = JSONObject(raw)
        if (json.optInt("code", 500) != 200) throw Exception(json.optString("message", "上传失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 解压沙盒目录中已上传的 zip 压缩包。path 为 zip 文件相对项目根的路径。 */
    suspend fun unzipSandboxFile(projectId: Long, path: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val body = JSONObject().apply { put("dest", "") }
        val json = request("POST", "/api/sandbox/files/unzip?project_id=$projectId&path=$encodedPath", body = body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "解压失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 预览 zip 压缩包内容（不解压）。返回 data.entries 列表。 */
    suspend fun zipInfoSandboxFile(projectId: Long, path: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val json = request("GET", "/api/sandbox/files/zip-info?project_id=$projectId&path=$encodedPath", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "读取失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 在容器内执行命令 */
    suspend fun execSandboxCommand(projectId: Long, command: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("command", command) }
        val json = request("POST", "/api/sandbox/exec?project_id=$projectId", body = body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "执行失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    // ==================== 二维码（扫码加好友） ====================

    data class QrResolveResult(
        val isSelf: Boolean,
        val userId: Long,
        val username: String,
        val signature: String,
        val avatarUrl: String,
        val message: String,
        // 群二维码字段（后端 is_group=true 时填充）
        val isGroup: Boolean = false,
        val groupId: Long = 0,
        val groupName: String = "",
        val displayId: Long = 0,
        val groupSignature: String = "",
        val memberCount: Long = 0,
        val isMember: Boolean = false
    )

    /** 邮箱推送配置（与后端 user_push_config 对应） */
    data class PushConfig(
        val method: Int,
        val templateMode: Int,
        val ownEmail: String,
        val ownAuth: String
    )

    /** 获取我的签名二维码内容 */
    suspend fun getMyQrCode(): ApiResult<String> = safeCall(authenticated = true) {
        val json = request("GET", "/api/user/qrcode", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
        json.optJSONObject("data")?.optString("content") ?: throw Exception("数据为空")
    }

    /** 解析二维码内容（服务端验签 + 返回目标用户信息） */
    suspend fun resolveQrCode(content: String): ApiResult<QrResolveResult> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("content", content) }
        val json = request("POST", "/api/user/qrcode/resolve", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "解析失败"))
        val d = json.optJSONObject("data") ?: throw Exception("数据为空")
        QrResolveResult(
            isSelf = d.optBoolean("is_self"),
            userId = d.optLong("id"),
            username = d.optString("username"),
            signature = d.optString("signature"),
            avatarUrl = d.optString("avatar_url"),
            message = d.optString("message"),
            isGroup = d.optBoolean("is_group"),
            groupId = d.optLong("group_id"),
            groupName = d.optString("group_name"),
            displayId = d.optLong("display_id"),
            groupSignature = d.optString("signature"),
            memberCount = d.optLong("member_count"),
            isMember = d.optBoolean("is_member")
        )
    }

    /** 上报已扫描桌面登录二维码（扫码后调用） */
    suspend fun scanLoginQr(qrContent: String): ApiResult<String> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("qr_content", qrContent) }
        val json = request("POST", "/api/qrcode-login/scan", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "扫描上报失败"))
        json.optJSONObject("data")?.optString("status") ?: "scanned"
    }

    /** 手机端确认授权桌面登录（QQ/微信式扫码确认），返回桌面端登录 JWT；persist=true 表示桌面端下次免登录 */
    suspend fun confirmLoginQr(qrContent: String, sessionId: String, persist: Boolean = false): ApiResult<String> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("session_id", sessionId); put("qr_content", qrContent); put("persist", persist) }
        val json = request("POST", "/api/qrcode-login/confirm", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "授权失败"))
        json.optJSONObject("data")?.optString("token") ?: throw Exception("未返回登录凭证")
    }

    /** 上报已扫描第三方授权二维码（扫码后调用），返回 data 含 status/app_name/scopes 供确认框展示 */
    suspend fun scanQrAuth(qrContent: String): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("qr_content", qrContent) }
        val json = request("POST", "/api/open/qrauth/scan", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "扫描上报失败"))
        json.optJSONObject("data") ?: JSONObject()
    }

    /** 手机端确认第三方扫码授权，签发一次性授权码（第三方凭 code 走 /api/open/token + /api/open/userinfo） */
    suspend fun confirmQrAuth(qrContent: String, sessionId: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("session_id", sessionId); put("qr_content", qrContent) }
        val json = request("POST", "/api/open/qrauth/confirm", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "授权失败"))
    }

    // ==================== 备注云同步（存于服务器 user-store，key=chat_remarks） ====================

    /** 从服务器拉取备注映射（会话id -> 备注）。返回 null 表示拉取失败；服务器为空返回空 map */
    suspend fun fetchServerRemarks(): HashMap<Long, String>? {
        return try {
            val json = request("GET", "/api/user/data?key=chat_remarks", authenticated = true)
            if (json.optInt("code", 500) != 200) return HashMap()
            val raw = json.optJSONObject("data")?.optString("value", "") ?: ""
            if (raw.isBlank()) return HashMap()
            val obj = JSONObject(raw)
            val map = HashMap<Long, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                k.toLongOrNull()?.let { map[it] = obj.optString(k, "") }
            }
            map
        } catch (e: Exception) {
            null
        }
    }

    /** 将整个备注映射保存到服务器 */
    suspend fun saveServerRemarks(remarks: Map<Long, String>): Boolean {
        return try {
            val value = JSONObject()
            remarks.forEach { (k, v) -> if (v.isNotBlank()) value.put(k.toString(), v) }
            val json = request(
                "POST",
                "/api/user/data/save",
                JSONObject().put("key", "chat_remarks").put("value", value.toString()),
                authenticated = true
            )
            json.optInt("code", 500) == 200
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 客户端偏好（服务端持久化） ====================
    //
    // 置顶会话 / 社区动态已读 / 社区排序方式，全部塞进用户 KV 存储的一个 key。
    // 复用已有的 /api/user/data 接口（备注同步也是这套），不需要新增表或接口，
    // 天然带 5MB 用户配额与账号隔离。

    /**
     * 读取服务端保存的客户端偏好 JSON。
     * 返回空串 = 服务端还没有记录（首次使用）；返回 null = 请求失败（调用方保留本地数据）。
     */
    suspend fun fetchClientPrefs(): String? {
        return try {
            val json = request("GET", "/api/user/data?key=client_prefs", authenticated = true)
            if (json.optInt("code", 500) != 200) return null
            json.optJSONObject("data")?.optString("value", "") ?: ""
        } catch (e: Exception) {
            null
        }
    }

    /** 整份覆盖写入客户端偏好；返回是否成功 */
    suspend fun saveClientPrefs(prefsJson: String): Boolean {
        return try {
            val json = request(
                "POST",
                "/api/user/data/save",
                JSONObject().put("key", "client_prefs").put("value", prefsJson),
                authenticated = true
            )
            json.optInt("code", 500) == 200
        } catch (e: Exception) {
            false
        }
    }
}


