package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.CryptoUtil
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.LoginResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 集中管理 SharedPreferences 存储
 *
 * 核心原则：所有用户信息按 userId 隔离存储，
 * 每次登录/切换账号从服务器拉取最新数据后写入对应的 userId 槽位。
 */
object LocalStorage {

    private const val PREFS_LOGIN = "aurora_login"
    private const val PREFS_PROFILE = "aurora_profile"
    private const val PREFS_CHAT = "aurora_chat"

    // ==================== 登录状态 ====================

    fun saveLogin(context: Context, response: LoginResponse) {
        // 换号登录：本地会话缓存 / 消息库 / 退群记录都是设备级共享文件，换号必须先清掉。
        // 否则新账号会看到上一个账号的会话；更严重的是「本地有记录 + 服务端列表里没有」
        // 会被 recordVanishedGroups 兜底逻辑误判成「你已被踢出群聊」（新注册账号整片只读的根因）。
        // owner 标记存在 PREFS_CHAT（logout 不清），所以登出后再注册也能识别出是另一个账号。
        val devicePrefs = getChatPrefs(context)
        val lastOwner = devicePrefs.getLong("local_data_owner_uid", 0L)
        if (lastOwner != 0L && lastOwner != response.id) {
            runCatching { LocalConversationStore.clear(context) }
            runCatching { MessageDb.clearAll(context) }
            runCatching { GroupExitStore.clear(context) }
        }
        devicePrefs.edit().putLong("local_data_owner_uid", response.id).apply()

        val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
        // session_key 兜底：历史调用方用位置参数构造 LoginResponse 时漏传该字段（默认空串），
        // 若把空串直接落盘，冷启动后 TCP 长连接会以「认证失败: session_key 校验不通过」被服务端拒绝。
        // 按「响应 > 当前内存 > 本地已存」顺序回退，绝不写入空值。
        val effectiveSessionKey = response.sessionKey
            .ifEmpty { AuroraApi.sessionKey }
            .ifEmpty { getStoredSessionKey(context) }
        prefs.edit()
            .putBoolean("is_logged_in", true)
            .putString("user_email", response.email)
            .putString("user_qq", response.qqNumber)
            .putLong("user_id", response.id)
            .putString("auth_token", response.token)
            .putBoolean("email_verified", response.emailVerified)
            .putString("session_key", CryptoUtil.encryptLocal(effectiveSessionKey))
            // 服务端判定的开发者身份：落盘，冷启动也能正确显示开发者管理入口与全部 Tab
            .putBoolean("server_is_developer", AuroraApi.isDeveloperAccount || response.isDeveloper)
            .commit() // 同步写入，确保退出应用时不丢失

        // 同步到 AuroraApi
        AuroraApi.authToken = response.token
        AuroraApi.currentUserId = response.id
        AuroraApi.currentUserEmailVerified = response.emailVerified
        AuroraApi.currentUserQQ = response.qqNumber
        AuroraApi.sessionKey = effectiveSessionKey
        // 请求签名密钥（服务端动态下发，每用户随机；见 RequestSigner）
        com.aurora.chat.security.RequestSigner.setSecret(response.signSecret)

        // 保存用户信息（按 userId 隔离，同步写入）
        saveUserProfile(context, response.id, response.username, response.signature)

        // 登录成功后拉取服务端保存的客户端偏好（置顶会话 / 动态已读 / 社区排序）。
        // 重装 APK、换机、清数据后登录也能还原，不再全部归零。
        ClientPrefsSync.pull(context)
    }

    /** 读取本地存储的传输会话密钥：session_key 以 loc:v1: 密文保存，读取时解密；旧明文自动兼容（decryptLocal 对非 loc:v1: 原样返回） */
    fun getStoredSessionKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
        return CryptoUtil.decryptLocal(prefs.getString("session_key", "") ?: "")
    }

    /** 仅更新本地存储的传输会话密钥（冷启动向服务端补齐时用；空值忽略） */
    fun saveSessionKey(context: Context, key: String) {
        if (key.isEmpty()) return
        context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
            .edit().putString("session_key", CryptoUtil.encryptLocal(key)).apply()
    }

    /** 仅更新本地存储的 QQ 号（用于旧会话未存 QQ 时从服务器补齐） */
    fun saveUserQQ(context: Context, qq: String) {
        context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
            .edit().putString("user_qq", qq).apply()
    }

    /** 保存用户昵称和签名到本地（按 userId 隔离） */
    fun saveUserProfile(context: Context, userId: Long, username: String, signature: String) {
        val prefs = context.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_name_$userId", username)
            .putString("user_signature_$userId", signature)
            .commit()
    }

    /** 读取指定用户的昵称 */
    fun getUserName(context: Context, userId: Long): String {
        return context.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
            .getString("user_name_$userId", "") ?: ""
    }

    /** 读取指定用户的签名 */
    fun getUserSignature(context: Context, userId: Long): String {
        return context.getSharedPreferences(PREFS_PROFILE, Context.MODE_PRIVATE)
            .getString("user_signature_$userId", "") ?: ""
    }

    fun loadLoginState(context: Context): LoginState {
        val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        val userId = prefs.getLong("user_id", 0)
        val email = prefs.getString("user_email", "") ?: ""
        val qq = prefs.getString("user_qq", "") ?: ""
        val token = prefs.getString("auth_token", "") ?: ""

        if (!isLoggedIn || userId == 0L) {
            return LoginState(false, 0, "", "", "", "")
        }

        // 恢复登录态（无论 token 是否为空，保证用户不会丢失登录）
        AuroraApi.authToken = token.ifEmpty { null }
        AuroraApi.currentUserId = userId
        AuroraApi.currentUserEmailVerified = prefs.getBoolean("email_verified", false)
        AuroraApi.currentUserQQ = qq
        AuroraApi.sessionKey = getStoredSessionKey(context)
        // 服务端判定的开发者身份（权威）：本地 QQ/邮箱只是兜底，冷启动也据此显示开发者管理
        AuroraApi.isDeveloperAccount = prefs.getBoolean("server_is_developer", false)

        // 签名密钥不落盘（服务端内存/持久化持有）：冷启动后异步拉取一次，
        // 保证发消息等强制验签的接口立即可用；拉取失败也有 HttpClient 的按需兜底。
        // 同一响应还会带回 session_key：旧版本登录链路漏存该字段的设备在此补齐并落盘，
        // 否则 TCP 认证会一直因空 session_key 失败（认证失败: session_key 校验不通过）。
        if (token.isNotEmpty()) {
            GlobalScope.launch(Dispatchers.IO) {
                runCatching { AuroraApi.refreshClientSignSecret() }
                if (getStoredSessionKey(context).isEmpty() && AuroraApi.sessionKey.isNotEmpty()) {
                    saveSessionKey(context, AuroraApi.sessionKey)
                }
            }
        }

        val username = getUserName(context, userId)

        return LoginState(true, userId, email, username, token, qq)
    }

    fun logout(context: Context) {
        // 登出前记住本地数据归属账号：之后换号登录（含新注册）据此清理设备级聊天缓存，
        // 不然会话缓存/消息库/退群记录会跨账号串用。
        val prevUid = AuroraApi.currentUserId
        if (prevUid != 0L) {
            getChatPrefs(context).edit().putLong("local_data_owner_uid", prevUid).apply()
        }
        context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        AuroraApi.authToken = null
        AuroraApi.currentUserId = 0
        AuroraApi.currentUserSignature = ""
        AuroraApi.sessionKey = ""
        AuroraApi.isDeveloperAccount = false
        // 清空签名密钥，避免串用上一个账号的
        com.aurora.chat.security.RequestSigner.clearSecret()
        AvatarCache.clearAll()
        // 「看过的用户」资料按账号隔离：登出清掉，避免下个账号看到上个人的名字
        com.aurora.chat.data.local.UserProfileStore.clear(context)
        // 群信息缓存按账号隔离：登出清掉，避免下个账号看到上个账号所在群号
        com.aurora.chat.data.local.GroupInfoStore.clear(context)
        // 群通知设置按群缓存，切换账号必须清掉，避免沿用上一个账号所在群的设置
        GroupNotifyPrefs.clear()
        // 退出登录必须解除「禁止退出」互锁，否则登录页也会被粘住无法退出
        com.aurora.chat.PrivacyLockState.clear()
    }
    // ==================== 聊天设置 ====================

    fun getChatPrefs(context: Context) =
        context.getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)

    // ==================== 我的头像（按 userId 隔离） ====================

    /** 获取当前用户头像文件路径（按 userId 隔离，不再用固定 avatar.png） */
    fun getMyAvatarFile(context: Context, userId: Long): java.io.File {
        return java.io.File(context.filesDir, "avatar_cache/avatar_${userId}.png")
    }


    /** 兼容旧版：删除旧版固定路径的头像文件，防残留 */
    fun cleanupOldAvatarFile(context: Context) {
        val oldFile = java.io.File(context.filesDir, "avatar.png")
        if (oldFile.exists()) oldFile.delete()
    }

    // ==================== AI 自定义头像（全局，不按 userId 隔离） ====================

    /** 获取 AI 自定义头像文件路径（用户上传后替换默认 AI 头像） */
    fun getAiAvatarFile(context: Context): java.io.File {
        return java.io.File(context.filesDir, "ai_avatar.png")
    }

    /** 读取 AI 自定义名称（null 表示使用默认名 AI） */
    fun getAiName(context: Context): String? {
        val n = getChatPrefs(context).getString("ai_name", null)
        return if (n.isNullOrEmpty()) null else n
    }

    /** 保存 AI 自定义名称 */
    fun setAiName(context: Context, name: String) {
        getChatPrefs(context).edit().putString("ai_name", name).apply()
    }

    /** 恢复 AI 默认名称 */
    fun resetAiName(context: Context) {
        getChatPrefs(context).edit().remove("ai_name").apply()
    }



    /**
     * 开发者标识：QQ 号或邮箱任一匹配即视为开发者（与后端 DEV_QQ / DEV_EMAIL 对应）。
     * 真实值不再硬编码；自建部署请在此改用 BuildConfig 注入，或依赖后端 ID=1 主账号兜底。
     */
    const val DEVELOPER_QQ = ""
    const val DEVELOPER_EMAIL = ""

    fun isDeveloper(qq: String, email: String = ""): Boolean {
        if (DEVELOPER_QQ.isEmpty() && DEVELOPER_EMAIL.isEmpty()) return false
        return (DEVELOPER_QQ.isNotEmpty() && qq == DEVELOPER_QQ) ||
            (DEVELOPER_EMAIL.isNotEmpty() && email.equals(DEVELOPER_EMAIL, ignoreCase = true))
    }

    // ==================== 对话导入 / 导出体验次数（普通用户各 2 次，按 userId 隔离） ====================
    private const val EXPORT_TRIAL_LIMIT = 2
    private const val IMPORT_TRIAL_LIMIT = 2

    fun getExportTrialLimit(): Int = EXPORT_TRIAL_LIMIT
    fun getImportTrialLimit(): Int = IMPORT_TRIAL_LIMIT

    /** 已使用的导出次数 */
    fun getExportUsedCount(context: Context, userId: Long): Int {
        return getChatPrefs(context).getInt("export_used_$userId", 0).coerceAtLeast(0)
    }

    fun setExportUsedCount(context: Context, userId: Long, count: Int) {
        getChatPrefs(context).edit().putInt("export_used_$userId", count).apply()
    }

    /** 已使用的导入次数 */
    fun getImportUsedCount(context: Context, userId: Long): Int {
        return getChatPrefs(context).getInt("import_used_$userId", 0).coerceAtLeast(0)
    }

    fun setImportUsedCount(context: Context, userId: Long, count: Int) {
        getChatPrefs(context).edit().putInt("import_used_$userId", count).apply()
    }
}

data class LoginState(
    val isLoggedIn: Boolean,
    val userId: Long,
    val email: String,
    val username: String,
    val token: String,
    val qq: String = ""
)
