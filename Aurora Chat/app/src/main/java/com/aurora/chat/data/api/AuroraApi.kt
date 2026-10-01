package com.aurora.chat.data.api

import com.aurora.chat.AuroraChatApplication
import com.aurora.chat.CryptoUtil

import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject


object AuroraApi {

    // 可配置的服务器地址（未来可从设置中修改）
    var serverUrl = "http://www.YOUR_SERVER_DOMAIN:5004"

    // JWT Token（登录/注册后设置）
    var authToken: String? = null

    // 单设备登录踢出事件：被其他设备登录时触发
    private val _kickedOff = MutableStateFlow<String?>(null)
    val kickedOffEvent: StateFlow<String?> = _kickedOff

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

    // ==================== 核心请求方法 ====================

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = false
): JSONObject = withContext(Dispatchers.IO) {
        val url = "$serverUrl$path"
        val raw = try {
            HttpClient.jsonRequest(method, url, body?.toString(), if (authenticated) authToken else null)
        } catch (e: java.io.IOException) {
            val msg = e.message ?: "请求失败"
            throw if (msg.startsWith("[HTTP")) Exception(msg) else Exception("请求失败: $msg")
        }
        try {
            JSONObject(raw)
        } catch (e: Exception) {
            android.util.Log.e("AuroraApi", "JSON解析失败: HTTP path=$path response=$raw")
            throw Exception("服务器返回异常: $path → ${raw.take(300)}")
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
    } catch (e: java.io.IOException) {
        val detail = e.localizedMessage ?: "网络连接失败"
        android.util.Log.e("AuroraApi", "API call failed: $detail", e)
        com.aurora.chat.ErrorReporter.error("Network", "网络错误: $detail", e)
        ApiResult(false, "网络错误: $detail")
    } catch (e: Exception) {
        val msg = e.localizedMessage ?: "未知错误"
        android.util.Log.e("AuroraApi", "API call failed: $msg", e)
        // 单设备登录：检测被踢下线信号 → 清除 token 并通知 UI
        if (msg.contains("在其他设备登录")) {
            authToken = null
            currentUserId = 0
            _kickedOff.value = msg
        } else {
            com.aurora.chat.ErrorReporter.error("API", "请求失败: $msg", e)
        }
        // 已是带 HTTP 状态码的结构化错误（如 [HTTP 400] ...）则不再包一层"请求失败"
        val finalMsg = if (msg.startsWith("[HTTP")) msg else "请求失败: $msg"
        ApiResult(false, finalMsg)
    }

    // ==================== 辅助解析 ====================

    private fun checkSuccess(json: JSONObject): Boolean {
        val code = json.optInt("code", 500)
        if (code != 200) {
            throw Exception("[HTTP $code] ${json.optString("message", "请求失败")}")
        }
        return true
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
                broadcastTaskId = obj.optLong("broadcast_task_id", 0)
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
            // 私聊 E2EE 密文在会话列表展示前解密；明文（群聊预览）原样显示；失败显示空（不再显示占位）
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

    suspend fun register(email: String, username: String, password: String, code: String = "", deviceId: String = ""): ApiResult<LoginResponse> = safeCall {
        val body = JSONObject().apply {
            put("email", email); put("username", username); put("password", password); put("code", code)
            put("device_id", deviceId)
        }
        val json = request("POST", "/api/register", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "注册失败"))
        val d = json.optJSONObject("data")!!
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
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
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
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
        captchaId: String, captchaAnswer: String, deviceId: String
    ): ApiResult<LoginResponse> = safeCall {
        val body = JSONObject().apply {
            put("email", email); put("username", username); put("password", password); put("code", code)
            put("device_id", deviceId)
            put("captcha_id", captchaId); put("captcha_answer", captchaAnswer)
        }
        val json = request("POST", "/api/register", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "注册失败"))
        val d = json.optJSONObject("data")!!
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
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

    /** 带显式验证码的 QQ 注册/登录（测试模式：传 qq 号，access_token 留空） */
    suspend fun qqLoginWithCaptcha(
        qq: String, username: String, captchaId: String, captchaAnswer: String, deviceId: String
    ): ApiResult<LoginResponse> = safeCall {
        val body = JSONObject().apply {
            put("qq", qq)
            put("username", username)
            put("device_id", deviceId)
            put("captcha_id", captchaId); put("captcha_answer", captchaAnswer)
        }
        val json = request("POST", "/api/qq/login", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "登录失败"))
        val d = json.optJSONObject("data")!!
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
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
        val body = JSONObject().apply { put("email", email); put("password", password) }
        val json = request("POST", "/api/login", body)
        if (!checkSuccess(json)) throw Exception("邮箱或密码错误")
        val d = json.optJSONObject("data")!!
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
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
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
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

    suspend fun sendFriendRequest(toEmail: String, greeting: String = ""): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("to_email", toEmail); put("greeting", greeting) }
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

    suspend fun sendPoke(toUserId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("to_user_id", toUserId) }
        val json = request("POST", "/api/messages/poke", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
    }

    suspend fun deleteFriend(friendId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("friend_id", friendId) }
        val json = request("POST", "/api/friend/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
    }

    suspend fun isFriend(userId: Long): ApiResult<Boolean> = safeCall(authenticated = true) {
        // 获取好友列表检查目标是否在好友中
        val json = request("GET", "/api/friends/${currentUserId}", authenticated = true)
        if (!checkSuccess(json)) throw Exception("获取失败")
        val list = parseUserList(json.optJSONArray("data") ?: JSONArray())
        list.any { it.id == userId }
    }

    // ==================== 隐私设置 ====================

    suspend fun updatePrivacy(hideEmail: Boolean? = null, hideQQ: Boolean? = null, requireGroupConsent: Boolean? = null): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject()
        if (hideEmail != null) body.put("hide_email", hideEmail)
        if (hideQQ != null) body.put("hide_qq", hideQQ)
        if (requireGroupConsent != null) body.put("require_group_consent", requireGroupConsent)
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
     * @param scope "user"(按用户名) | "all"(全部) | "range"(ID 区块)
     * @param usernames 当 scope=user 时的用户名列表
     * @param ranges 当 scope=range 时的 ID 区间，每个为 [min,max]
     * @param title 通知标题
     * @param content 通知内容
     * @param extra 底部补充信息
     */
    suspend fun sendNotification(
        scope: String,
        usernames: List<String>,
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
            put("usernames", JSONArray(usernames))
            put("ranges", rangesArr)
            put("title", title)
            put("content", content)
            put("extra", extra)
        }
        request("POST", "/api/admin/notify", body, authenticated = true)
    }

    /** 开发者/管理员按范围批量调整用户 Token 余额：scope=user/all/range，op=add/deduct/reset */
    suspend fun adjustBalance(
        scope: String,
        usernames: List<String>,
        ranges: List<Pair<Long, Long>>,
        op: String,
        amount: Long,
        reason: String
    ): ApiResult<JSONObject> = safeCall(authenticated = true) {
        val rangesArr = org.json.JSONArray()
        for ((a, b) in ranges) {
            rangesArr.put(org.json.JSONArray().apply { put(a); put(b) })
        }
        val namesArr = org.json.JSONArray()
        for (u in usernames) namesArr.put(u)
        val body = JSONObject().apply {
            put("scope", scope)
            put("usernames", namesArr)
            put("ranges", rangesArr)
            put("op", op)
            put("amount", amount)
            put("reason", reason)
        }
        request("POST", "/api/admin/balance/adjust", body, authenticated = true)
    }

    // ==================== E2EE 公钥 ====================

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

    // ==================== 每好友端到端加密开关 ====================

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

    suspend fun sendGroupMessage(toUserId: Long, content: String, replyTo: Long = 0, mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0, replyToText: String = "", replyToSender: String = ""): ApiResult<Long> = safeCall(authenticated = true) {
        // 传输加密：正文和引用预览用 session_key 加密为 sess:v1:，消除传输明文；
        // 服务端 decryptSessionContent 解密后再走 enc:v1: 静态加密落库。无 session_key 时降级明文。
        val encContent = CryptoUtil.encryptWithKey(content, sessionKey)
        val encReplyToText = if (replyToText.isNotEmpty()) CryptoUtil.encryptWithKey(replyToText, sessionKey) else ""
        val body = JSONObject().apply { put("from_user_id", currentUserId); put("to_user_id", toUserId); put("content", encContent); if (replyTo > 0) put("reply_to", replyTo); if (encReplyToText.isNotEmpty()) put("reply_to_text", encReplyToText); if (replyToSender.isNotEmpty()) put("reply_to_sender", replyToSender); if (mediaType.isNotEmpty()) { put("media_type", mediaType); put("media_url", mediaUrl) }; if (flashDuration > 0) put("flash_duration", flashDuration) }
        val json = request("POST", "/api/messages/send", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
        json.optJSONObject("data")?.optLong("message_id", 0) ?: 0
    }

    suspend fun sendMessage(toUserId: Long, content: String, replyTo: Long = 0, mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0, replyToText: String = "", replyToSender: String = ""): ApiResult<Long> = safeCall(authenticated = true) {
        // 私聊消息统一使用 session_key 传输加密（sess:v1:）：服务端解密后静态加密落库、读取时解密返回明文。
        // 已取消端到端加密（E2EE）：跨设备密钥不匹配会导致接收方解密失败、列表显示"端到端加密消息"占位，
        // 故统一走服务端可解密的传输加密，保证任意设备都能直接显示真实消息。
        val encContent = CryptoUtil.encryptWithKey(content, sessionKey)
        val encReplyToText = if (replyToText.isNotEmpty()) CryptoUtil.encryptWithKey(replyToText, sessionKey) else ""
        val body = JSONObject().apply { put("from_user_id", currentUserId); put("to_user_id", toUserId); put("content", encContent); if (replyTo > 0) put("reply_to", replyTo); if (encReplyToText.isNotEmpty()) put("reply_to_text", encReplyToText); if (replyToSender.isNotEmpty()) put("reply_to_sender", replyToSender); if (mediaType.isNotEmpty()) { put("media_type", mediaType); put("media_url", mediaUrl) }; if (flashDuration > 0) put("flash_duration", flashDuration) }
        val json = request("POST", "/api/messages/send", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "发送失败"))
        json.optJSONObject("data")?.optLong("message_id", 0) ?: 0
    }

    /**
     * 私聊发送是否应走端到端加密：仅读取本地记录（好友资料面板打开时将双方开关与对方公钥写入本地），
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
            // 私聊 E2EE 密文（带 E2EE:v2: 前缀）尝试解密；明文/群聊直接显示
            val decrypted = if (userId2 > 0 && CryptoUtil.isEncrypted(encrypted)) {
                try {
                    CryptoUtil.decrypt(encrypted)
                } catch (_: Exception) {
                    // 解密失败显示空（已取消端到端加密，历史密文无法还原则留空，不显示占位）
                    ""
                }
            } else {
                encrypted // 明文 / 群聊直接显示原文
            }
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
                broadcastTaskId = obj.optLong("broadcast_task_id", 0)
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

    // ==================== 会话列表 ====================

    suspend fun getConversations(): ApiResult<List<ConversationInfo>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/conversations/${currentUserId}", authenticated = true)
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

    /** 记录闪照已查看 */
    suspend fun recordFlashView(messageId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("message_id", messageId) }
        val json = request("POST", "/api/flash/view", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception("记录失败")
    }

    /** 获取已查看闪照ID列表 */
    suspend fun getViewedFlashIds(): ApiResult<List<Long>> = safeCall(authenticated = true) {
        val json = request("GET", "/api/flash/viewed-ids", authenticated = true)
        val data = json.optJSONObject("data")
        val arr = data?.optJSONArray("ids") ?: JSONArray()
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
        val response = LoginResponse(
            id = d.optLong("id"),
            email = d.optString("email"),
            username = d.optString("username"),
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
                                     notSearchable: Boolean = false, joinRequired: Boolean = false): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("group_id", groupId)
            put("name", name)
            put("signature", signature)
            put("announcement", announcement)
            put("welcome_enabled", welcomeEnabled)
            put("welcome_text", welcomeText)
            put("not_searchable", notSearchable)
            put("join_required", joinRequired)
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
            ApiResult(false, "上传失败: ${e.message}")
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
                reply_count = obj.optInt("reply_count", 0)
            )
        }
        CommentListResponse(comments = comments, total = data.optInt("total"))
    }

    suspend fun addComment(postId: Long, content: String, parentId: Long = 0): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("post_id", postId); put("content", content); put("parent_id", parentId) }
        val json = request("POST", "/api/community/comment/add", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "添加失败"))
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

    /**
     * 注册服务器（需要 JWT 认证）
     * 一个账号只能注册一个服务器（1:1 绑定）
     */
    suspend fun registerServer(name: String, password: String): ApiResult<ServerInfo> = safeCall(authenticated = true) {
        val body = JSONObject().apply {
            put("name", name); put("password", password)
        }
        val json = request("POST", "/api/server/register", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "注册失败"))
        val d = json.optJSONObject("data")!!
        // 注册成功后设置当前服务器信息
        currentServerName = d.optString("name", "")
        currentServerDomain = d.optString("domain", "")
        val serverStatus = d.optString("status", "offline")
        ServerInfo(
            id = d.optLong("id"),
            ownerUserId = d.optLong("owner_user_id"),
            ownerUsername = d.optString("owner_username", ""),
            name = currentServerName,
            domain = currentServerDomain,
            serverUrl = d.optString("server_url", ""),
            createdAt = d.optLong("created_at"),
            status = serverStatus
        )
    }

    /**
     * 登录服务器（无需 JWT，开放给所有人）
     */
    suspend fun loginServer(name: String, password: String): ApiResult<ServerInfo> = safeCall(authenticated = false) {
        val body = JSONObject().apply { put("name", name); put("password", password) }
        val json = request("POST", "/api/server/login", body)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "登录失败"))
        val d = json.optJSONObject("data")!!
        currentServerName = d.optString("name", "")
        currentServerDomain = d.optString("domain", "")
        val serverStatus = d.optString("status", "offline")
        ServerInfo(
            id = d.optLong("id"),
            ownerUserId = d.optLong("owner_user_id"),
            ownerUsername = d.optString("owner_username", ""),
            name = currentServerName,
            domain = currentServerDomain,
            serverUrl = d.optString("server_url", ""),
            createdAt = d.optLong("created_at"),
            status = serverStatus
        )
    }

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

    /** 客户端直接向上游免费 API 发一次最小请求，测量往返延迟（ms）。返回 Pair(是否连通, 延迟毫秒) */
    suspend fun measureFreeApiLatency(apiKey: String, baseUrl: String, model: String): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trimEnd('/')
        val endpoint = if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        return@withContext try {
            val body = org.json.JSONObject().apply {
                put("model", if (model.isBlank()) "gpt-3.5-turbo" else model)
                val arr = org.json.JSONArray()
                arr.put(org.json.JSONObject().apply { put("role", "user"); put("content", "hi") })
                put("messages", arr)
                put("max_tokens", 1)
                put("stream", false)
            }
            val req = Request.Builder()
                .url(endpoint)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept-Encoding", "identity")
                .build()
            val client = com.aurora.chat.data.api.HttpClient.client.newBuilder()
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val t0 = System.currentTimeMillis()
            val resp = client.newCall(req).execute()
            val code = resp.code
            resp.body?.close()
            val ok = code in 200..299
            ok to (System.currentTimeMillis() - t0)
        } catch (e: Exception) {
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
            android.util.Log.e("AuroraApi", "uploadServerFile JSON parse error: response=$raw")
            throw Exception("服务器返回异常: ${raw.take(200)}")
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

    /** 获取文件夹面包屑导航 */
    suspend fun getFolderBreadcrumb(folderId: Long?): ApiResult<List<BreadcrumbItem>> = safeCall(authenticated = true) {
        val path = if (folderId != null) "/api/server/files/breadcrumb?folder_id=$folderId" else "/api/server/files/breadcrumb?folder_id=root"
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
            HttpClient.downloadBytes("$serverUrl/api/server/files/download/$fileId", authToken)
        } catch (e: Exception) {
            throw Exception("下载失败: ${e.localizedMessage ?: "网络错误"}")
        }
    }

    /** 删除文件或文件夹 */
    suspend fun deleteServerFile(fileId: Long): ApiResult<Unit> = safeCall(authenticated = true) {
        val body = JSONObject().apply { put("file_id", fileId) }
        val json = request("POST", "/api/server/files/delete", body, authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "删除失败"))
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
    suspend fun getServerDashboard(): ApiResult<JSONObject> = ApiDedup.dedup("dashboard") {
        safeCall(authenticated = true) {
            val json = request("GET", "/api/server/dashboard", authenticated = true)
            if (!checkSuccess(json)) throw Exception(json.optString("message", "获取失败"))
            json.optJSONObject("data") ?: JSONObject()
        }
    }


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

    /** 创建沙盒目录 */
    suspend fun mkdirSandbox(projectId: Long, path: String): ApiResult<Unit> = safeCall(authenticated = true) {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val json = request("POST", "/api/sandbox/files/mkdir?project_id=$projectId&path=$encodedPath", authenticated = true)
        if (!checkSuccess(json)) throw Exception(json.optString("message", "创建失败"))
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
        val message: String
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
            message = d.optString("message")
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
}


