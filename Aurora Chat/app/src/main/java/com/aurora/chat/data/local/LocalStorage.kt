package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.LoginResponse

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
        val prefs = context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_logged_in", true)
            .putString("user_email", response.email)
            .putString("user_qq", response.qqNumber)
            .putLong("user_id", response.id)
            .putString("auth_token", response.token)
            .putBoolean("email_verified", response.emailVerified)
            .putString("session_key", response.sessionKey)
            .commit() // 同步写入，确保退出应用时不丢失

        // 同步到 AuroraApi
        AuroraApi.authToken = response.token
        AuroraApi.currentUserId = response.id
        AuroraApi.currentUserEmailVerified = response.emailVerified
        AuroraApi.currentUserQQ = response.qqNumber
        AuroraApi.sessionKey = response.sessionKey

        // 保存用户信息（按 userId 隔离，同步写入）
        saveUserProfile(context, response.id, response.username, response.signature)
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
        AuroraApi.sessionKey = prefs.getString("session_key", "") ?: ""

        val username = getUserName(context, userId)

        return LoginState(true, userId, email, username, token, qq)
    }

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        AuroraApi.authToken = null
        AuroraApi.currentUserId = 0
        AuroraApi.currentUserSignature = ""
        AuroraApi.sessionKey = ""
        AvatarCache.clearAll()
    }
    // ==================== 聊天设置 ====================

    fun getChatPrefs(context: Context) =
        context.getSharedPreferences(PREFS_CHAT, Context.MODE_PRIVATE)

    // ==================== 我的头像（按 userId 隔离） ====================

    /** 获取当前用户头像文件路径（按 userId 隔离，不再用固定 avatar.png） */
    fun getMyAvatarFile(context: Context, userId: Long): java.io.File {
        return java.io.File(context.filesDir, "avatar_cache/avatar_${userId}.png")
    }

    /** DeepSeek AI 对话专用：我方自定义头像文件路径（与平台头像隔离） */
    fun getAiMyAvatarFile(context: Context, userId: Long): java.io.File {
        return java.io.File(context.filesDir, "avatar_cache/ai_avatar_${userId}.png")
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

    /** 读取「人物对换 / 历史反转」开关（按 userId 隔离） */
    fun getReverseHistory(context: Context, userId: Long): Boolean =
        getChatPrefs(context).getBoolean("reverse_history_$userId", false)

    /** 保存「人物对换 / 历史反转」开关 */
    fun setReverseHistory(context: Context, userId: Long, on: Boolean) {
        getChatPrefs(context).edit().putBoolean("reverse_history_$userId", on).apply()
    }


    /** 开发者标识：QQ 号或邮箱任一匹配即视为开发者（与后端 DeveloperQQ / DeveloperEmail 保持一致） */
    const val DEVELOPER_QQ = "YOUR_DEVELOPER_QQ"
    const val DEVELOPER_EMAIL = "YOUR_DEVELOPER_EMAIL"

    fun isDeveloper(qq: String, email: String = ""): Boolean {
        return qq == DEVELOPER_QQ || email.equals(DEVELOPER_EMAIL, ignoreCase = true)
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
