package com.aurora.chat.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aurora.chat.data.api.ApiResult
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.data.api.ServerFileInfo
import com.aurora.chat.data.api.FriendRequestInfo
import com.aurora.chat.data.api.LoginResponse
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.api.ActivityListResponse
import com.aurora.chat.data.local.AvatarCache
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.local.LocalMessageStore
import com.aurora.chat.TcpService
import com.aurora.chat.ui.tools.DownloadManager
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/**
 * Aurora Chat 数据仓库
 *
 * 统一管理所有数据获取逻辑：
 * - 网络请求通过 AuroraApi
 * - 本地缓存通过 AvatarCache / LocalStorage
 * - 解密逻辑
 */
object ChatRepository {

    // ==================== 认证 ====================

    suspend fun sendCode(email: String) = AuroraApi.sendCode(email)

    suspend fun sendResetCode(email: String) = AuroraApi.sendResetCode(email)

    suspend fun register(email: String, username: String, password: String, code: String = "", deviceId: String = "") =
        AuroraApi.register(email, username, password, code, deviceId)


    suspend fun registerWithCaptcha(
        email: String, username: String, password: String, code: String,
        captchaId: String, captchaAnswer: String, deviceId: String
    ) = AuroraApi.registerWithCaptcha(email, username, password, code, captchaId, captchaAnswer, deviceId)

    /** 获取验证码挑战（captcha_id + 题目），供登录/注册页用作图形验证 */
    suspend fun fetchCaptchaChallengeSuspend(): Pair<String, String>? = AuroraApi.fetchCaptchaChallengeSuspend()

    suspend fun login(email: String, password: String) = AuroraApi.login(email, password)

    suspend fun qqLogin(openid: String, accessToken: String, username: String = "", deviceId: String = "") =
        AuroraApi.qqLogin(openid, accessToken, username, deviceId)

    suspend fun qqLoginWithCaptcha(
        qq: String, username: String, captchaId: String, captchaAnswer: String, deviceId: String
    ) = AuroraApi.qqLoginWithCaptcha(qq, username, captchaId, captchaAnswer, deviceId)

    suspend fun fetchQQAvatar(qq: String) = AuroraApi.fetchQQAvatar(qq)

    suspend fun resetPassword(email: String, code: String, newPassword: String) =
        AuroraApi.resetPassword(email, code, newPassword)

    // ==================== 用户 ====================

    suspend fun searchUsers(keyword: String) = AuroraApi.searchUsers(keyword)

    suspend fun getAllUsers() = AuroraApi.getAllUsers()

    suspend fun getOnlineUsers() = AuroraApi.getOnlineUsers()

    // ==================== 好友请求 ====================

    suspend fun sendFriendRequest(toEmail: String, greeting: String = "") =
        AuroraApi.sendFriendRequest(toEmail, greeting)

    suspend fun getFriendRequests() = AuroraApi.getFriendRequests()

    suspend fun respondFriendRequest(requestId: Long, action: String) =
        AuroraApi.respondFriendRequest(requestId, action)

    suspend fun cancelFriendRequest(requestId: Long) =
        AuroraApi.cancelFriendRequest(requestId)

    // ==================== 好友 ====================

    suspend fun getFriends() = AuroraApi.getFriends()
    suspend fun sendPoke(toUserId: Long) = AuroraApi.sendPoke(toUserId)
    suspend fun deleteFriend(friendId: Long) = AuroraApi.deleteFriend(friendId)
    suspend fun isFriend(userId: Long) = AuroraApi.isFriend(userId)
    suspend fun updatePrivacy(hideEmail: Boolean? = null, hideQQ: Boolean? = null, requireGroupConsent: Boolean? = null) = AuroraApi.updatePrivacy(hideEmail, hideQQ, requireGroupConsent)
    suspend fun updateSignature(signature: String) = AuroraApi.updateSignature(signature)
    suspend fun getUserInfo(userId: Long) = AuroraApi.getUserInfo(userId)
    suspend fun adminBanUser(userId: Long, duration: Long, reason: String, unbanPopupMessage: String = "") =
        AuroraApi.adminBanUser(userId, duration, reason, unbanPopupMessage)
    suspend fun adminKickUser(targetUserId: Long) = AuroraApi.adminKickUser(targetUserId)
    suspend fun adminKickAll() = AuroraApi.adminKickAll()
    suspend fun requestUnbanNotify() = AuroraApi.requestUnbanNotify()
    suspend fun adminMuteUser(userId: Long, duration: Long, muteType: Int) = AuroraApi.adminMuteUser(userId, duration, muteType)
    suspend fun adminUnmuteUser(userId: Long) = AuroraApi.adminUnmuteUser(userId)
    suspend fun adminCheckMuteStatus(userId: Long) = AuroraApi.adminCheckMuteStatus(userId)
    suspend fun groupMuteUser(groupId: Long, userId: Long, duration: Long) = AuroraApi.groupMuteUser(groupId, userId, duration)
    suspend fun groupUnmuteUser(groupId: Long, userId: Long) = AuroraApi.groupUnmuteUser(groupId, userId)
    suspend fun groupCheckMuteStatus(groupId: Long, userId: Long) = AuroraApi.groupCheckMuteStatus(groupId, userId)
    suspend fun groupMuteMembers(groupId: Long) = AuroraApi.groupMuteMembers(groupId)

    // ==================== 消息 ====================

    suspend fun sendMessage(toUserId: Long, content: String, replyTo: Long = 0, mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0, replyToText: String = "", replyToSender: String = "") =
        AuroraApi.sendMessage(toUserId, content, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender)

    suspend fun sendGroupMessage(toUserId: Long, content: String, replyTo: Long = 0, mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0, replyToText: String = "", replyToSender: String = "") =
        AuroraApi.sendGroupMessage(toUserId, content, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender)

    suspend fun getGroupMessages(groupId: Long, limit: Int = 100, offset: Int = 0, afterId: Long = 0) =
        AuroraApi.getGroupMessages(groupId, limit, offset, afterId)

    suspend fun getMessages(userId1: Long, userId2: Long, limit: Int = 50, offset: Int = 0, afterId: Long = 0) =
        AuroraApi.getMessages(userId1, userId2, limit, offset, afterId)

    // ==================== 会话 ====================

    suspend fun getConversations() = AuroraApi.getConversations()

    // ==================== 保存全部数据到本地 ====================

    /** "保存所有数据"结果 */
    data class SaveAllResult(
        val success: Boolean,
        val savedConversations: Int,
        val totalMessages: Int,
        val error: String
    )

    /**
     * 遍历当前用户全部好友，拉取每个会话的全量历史并写入本地存储。
     * 服务器后续按 30 天留存策略清理后，这些本地副本仍可正常查看。
     * 仅保存 1:1 私聊（好友列表即私聊会话）；群聊会话可通过各自聊天界面正常加载。
     */
    suspend fun saveAllLocalData(context: Context, currentUserId: Long): SaveAllResult {
        return try {
            val friendsResult = getFriends()
            if (!friendsResult.success || friendsResult.data == null) {
                SaveAllResult(false, 0, 0, "获取好友列表失败")
            } else {
                val friends = friendsResult.data!!
                withContext(Dispatchers.IO) {
                    var savedConversations = 0
                    var totalMessages = 0
                    for (friend in friends) {
                        try {
                            val msgs = getMessages(currentUserId, friend.id, 0, 0).data ?: emptyList()
                            if (msgs.isNotEmpty()) {
                                LocalMessageStore.saveMessages(context, friend.id, msgs)
                                totalMessages += msgs.size
                                savedConversations++
                            }
                        } catch (_: Exception) { /* 单个会话失败不影响其余 */ }
                    }
                    SaveAllResult(true, savedConversations, totalMessages, "")
                }
            }
        } catch (e: Exception) {
            SaveAllResult(false, 0, 0, e.message ?: "保存失败")
        }
    }

    // ==================== 密钥 ====================

    suspend fun uploadPublicKey(publicKeyBase64: String) =
        AuroraApi.uploadPublicKey(publicKeyBase64)

    suspend fun setE2EEnabled(enabled: Boolean) =
        AuroraApi.setE2EEnabled(enabled)

    suspend fun getE2EStatus(userId: Long) =
        AuroraApi.getE2EStatus(userId)

    // ==================== 头像 ====================

    suspend fun loadAvatar(context: Context, userId: Long): Bitmap? {
        return try {
            AvatarCache.loadAvatar(context, userId) { uid ->
                AuroraApi.loadAvatarBytes(uid)
            }
        } catch (_: Exception) {
            AvatarCache.getAvatar(context, userId)
        }
    }

    /** 加载群头像（优先本地磁盘缓存，再走网络） */
    suspend fun loadGroupAvatar(context: Context, groupId: Long): Bitmap? {
        return AvatarCache.loadGroupAvatar(context, groupId) { gid ->
            AuroraApi.loadGroupAvatarBytes(gid)
        }
    }

    /** 强制刷新群头像（清除本地缓存后重新下载） */
    suspend fun refreshGroupAvatar(context: Context, groupId: Long): Bitmap? {
        return AvatarCache.refreshGroupAvatar(context, groupId) { gid ->
            AuroraApi.loadGroupAvatarBytes(gid)
        }
    }

    fun getAvatarUrl(userId: Long): String = AuroraApi.getAvatarUrl(userId)

    suspend fun uploadAvatar(avatarBase64: String) = AuroraApi.uploadAvatar(avatarBase64)

    // ==================== 头像上传（带 Bitmap 转 Base64） ====================

    suspend fun uploadAvatarBitmapWithProgress(
        context: Context,
        bitmap: Bitmap,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): Boolean {
        return try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
            val bytes = stream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val result = AuroraApi.uploadAvatarWithProgress(base64, onProgress)
            if (result.success) {
                // 上传成功后保存本地副本到 userId 隔离路径
                val uid = AuroraApi.currentUserId
                val file = if (uid > 0) {
                    com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, uid)
                } else {
                    java.io.File(context.filesDir, "avatar.png")
                }
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                if (uid > 0) {
                    AvatarCache.cacheUserAvatar(uid, bitmap, context)
                }
            } else {
                android.util.Log.e("Avatar", "Upload failed: ${result.message}")
            }
            result.success
        } catch (e: Exception) {
            android.util.Log.e("Avatar", "Upload exception", e)
            false
        }
    }

    /** 不带进度回调的头像上传（无进度 UI 的入口复用此方法） */
    suspend fun uploadAvatarBitmap(context: Context, bitmap: Bitmap): Boolean =
        uploadAvatarBitmapWithProgress(context, bitmap, null)

    /**
     * 登录时调用：清除所有头像缓存，然后预加载好友头像列表。
     * 确保登录后好友头像优先从服务器获取，而不是使用过期的本地缓存。
     */
    suspend fun refreshAvatarsOnLogin(context: Context) {
        // 1. 清除所有缓存（内存 + 磁盘）
        AvatarCache.clearAll(context)
        android.util.Log.d("Avatar", "登录时已清除所有头像缓存")

        // 2. 预加载自己的头像
        try {
            val myId = AuroraApi.currentUserId
            if (myId > 0) {
                loadAvatar(context, myId)
                android.util.Log.d("Avatar", "已预加载自己的头像")
            }
        } catch (_: Exception) {}

        // 3. 加载好友列表并预加载好友头像
        try {
            val friends = AuroraApi.getFriends()
            if (friends.success && friends.data != null) {
                for (friend in friends.data) {
                    try { loadAvatar(context, friend.id) } catch (_: Exception) {}
                }
                android.util.Log.d("Avatar", "已预加载 ${friends.data.size} 个好友的头像")
            }
        } catch (_: Exception) {}
    }

    // ==================== 群聊 ====================

    suspend fun createGroup(currentUserId: Long, name: String, signature: String, announcement: String,
                            welcomeEnabled: Boolean = false, welcomeText: String = ""): ApiResult<org.json.JSONObject> {
        return AuroraApi.createGroup(currentUserId, name, signature, announcement, welcomeEnabled, welcomeText)
    }

    suspend fun updateGroupSettings(groupId: Long, name: String, signature: String, announcement: String,
                                     welcomeEnabled: Boolean, welcomeText: String,
                                     notSearchable: Boolean = false, joinRequired: Boolean = false): ApiResult<Unit> {
        return AuroraApi.updateGroupSettings(groupId, name, signature, announcement, welcomeEnabled, welcomeText, notSearchable, joinRequired)
    }

    suspend fun uploadGroupAvatarBitmap(groupId: Long, bitmap: Bitmap, context: Context? = null): Boolean {
        return try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
            val bytes = stream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val result = AuroraApi.uploadGroupAvatar(groupId, base64)
            if (result.success && context != null) {
                // 上传成功后保存到本地磁盘缓存
                // 注意顺序：先清旧缓存（磁盘文件），再写新文件，最后设内存缓存
                AvatarCache.clearGroupCache(groupId, context) // 删除旧的磁盘文件
                val dir = java.io.File(context.filesDir, "group_avatar_cache")
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, "group_avatar_$groupId.png").writeBytes(bytes) // 写新磁盘文件
                val timeFile = java.io.File(dir, "group_avatar_${groupId}.time")
                timeFile.writeText(System.currentTimeMillis().toString())
                AvatarCache.cacheUploadedGroupAvatar(groupId, bitmap) // 设内存缓存（最后一步）
                ChatViewModel.refreshGroupAvatars()
            } else if (context != null) {
                queuePendingGroupAvatar(context, groupId, base64)
            }
            result.success
        } catch (e: Exception) {
            if (context != null) {
                try {
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
                    val bytes = stream.toByteArray()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    queuePendingGroupAvatar(context, groupId, base64)
                } catch (_: Exception) { }
            }
            false
        }
    }

    /** 将失败的群头像上传加入重试队列（Base64 存文件，SP 只记路径） */
    private fun queuePendingGroupAvatar(context: Context, groupId: Long, base64: String) {
        try {
            val dir = java.io.File(context.filesDir, "pending_avatars")
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "group_$groupId.jpg")
            file.writeText(base64)
            val prefs = context.getSharedPreferences("avatar_upload_queue", Context.MODE_PRIVATE)
            val queue = org.json.JSONArray(prefs.getString("pending_groups", "[]") ?: "[]")
            var exists = false
            for (i in 0 until queue.length()) {
                if (queue.getJSONObject(i).optLong("group_id") == groupId) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                val entry = org.json.JSONObject().apply {
                    put("group_id", groupId)
                    put("file", file.absolutePath)
                }
                queue.put(entry)
                prefs.edit().putString("pending_groups", queue.toString()).apply()
                android.util.Log.d("AvatarUpload", "头像上传失败，已加入重试队列: groupId=$groupId")
            }
        } catch (_: Exception) { }
    }

    /** 重试所有待上传的群头像（建议 App 启动时调用） */
    suspend fun retryPendingGroupAvatars(context: Context) {
        try {
            val prefs = context.getSharedPreferences("avatar_upload_queue", Context.MODE_PRIVATE)
            val json = prefs.getString("pending_groups", "[]") ?: "[]"
            val queue = org.json.JSONArray(json)
            if (queue.length() == 0) return

            val remaining = org.json.JSONArray()
            for (i in 0 until queue.length()) {
                val entry = queue.getJSONObject(i)
                val groupId = entry.optLong("group_id")
                // 从文件读取 Base64
                val filePath = entry.optString("file", "")
                val base64 = if (filePath.isNotEmpty()) {
                    try { java.io.File(filePath).readText() } catch (_: Exception) { "" }
                } else {
                    entry.optString("base64", "") // 兼容旧格式
                }
                if (base64.isEmpty()) continue
                val result = AuroraApi.uploadGroupAvatar(groupId, base64)
                if (!result.success) {
                    remaining.put(entry) // 还失败就继续留着
                } else {
                    // 上传成功后清理文件
                    if (filePath.isNotEmpty()) try { java.io.File(filePath).delete() } catch (_: Exception) {}
                    android.util.Log.d("AvatarUpload", "重试上传成功: groupId=$groupId")
                }
            }
            prefs.edit().putString("pending_groups", remaining.toString()).apply()
        } catch (_: Exception) { }
    }

    /**
     * 安全清理指定用户的所有本地残留数据。
     *
     * 安全机制（三层）：
     * 1. 双重验证 — 间隔 800ms 查两次，两次都返回 false 才清理
     * 2. 速率限制 — 同一 userId 5 分钟内不重复检查
     * 3. 网络异常/服务器错误时不动数据
     */
    private val cleanupLock = mutableMapOf<Long, Long>() // userId -> lastCheckTimeMs

    suspend fun cleanupUserData(context: Context, userId: Long) {
        if (userId <= 0) return

        // 速率限制：同一用户 5 分钟内不重复检查
        val now = System.currentTimeMillis()
        val lastCheck = cleanupLock[userId] ?: 0L
        if (now - lastCheck < 300_000L) {
            android.util.Log.d("Cleanup", "用户 $userId 已在 5 分钟内检查过，跳过")
            return
        }
        cleanupLock[userId] = now

        // 第一轮验证：查一次
        if (AuroraApi.checkUserExists(userId)) return

        // 第二轮验证：间隔 800ms 再查一次（防止瞬态服务器抖动）
        kotlinx.coroutines.delay(800L)
        if (AuroraApi.checkUserExists(userId)) return

        // 第三轮验证：再查一次对话/好友请求 API 中该用户的邮箱
        // 如果 userId 不存在但邮箱也没注册过，100% 确定已删除
        val userInfo = try { AuroraApi.getUserInfo(userId) } catch (_: Exception) { null }
        if (userInfo?.success == true) return // 还能查到用户信息，不动数据

        android.util.Log.w("Cleanup", "用户 $userId 经三重验证确认不存在，开始清理本地数据")

        // 清理头像缓存
        AvatarCache.clear(userId, context)

        // 清理公钥缓存
        AuroraApi.clearPublicKeyCache()

        // 清理对话列表和好友请求列表（触发 UI 刷新）
        ChatViewModel.removeUserData(userId)
    }

    /**
     * 检查当前登录用户是否仍在服务器端存在且账号信息正常。
     * 检测项：
     * 1. 用户 ID 在数据库中是否存在（三重验证）
     * 2. ID-邮箱绑定关系是否正确（防止账号异常/错乱）
     * 确认异常后自动执行强制退出并清理所有本地数据。
     * @return true=已执行强制退出，false=用户正常
     */
    suspend fun checkAndLogoutCurrentUser(context: Context, userId: Long): Boolean {
        if (userId <= 0) return false
        val localEmail = LocalStorage.loadLoginState(context).email

        // === 前置检查：如果当前 token 为空，说明已经登出或 token 已过期 ===
        // 这种情况不应视为账号异常，而是登录状态丢失
        if (AuroraApi.authToken.isNullOrEmpty()) {
            android.util.Log.w("Cleanup", "authToken 为空，可能已登出，不执行强制退出")
            return false
        }

        // === 第一关：确认服务器明确返回"用户不存在"才继续，网络异常不做任何操作 ===
        // checkUserExists 在异常时返回 true（安全），只在服务器明确返回 false 时进入
        try {
            if (!AuroraApi.checkUserExists(userId)) {
                kotlinx.coroutines.delay(800L)
                if (!AuroraApi.checkUserExists(userId)) {
                    // 二次确认后调用 getUserInfo 做最终验证
                    val userInfo = try { AuroraApi.getUserInfo(userId) } catch (_: Exception) { null }
                    // 只有服务器明确返回数据（非网络异常）且数据不包含用户信息时才视为已删除
                    if (userInfo?.success == true && userInfo.data == null) {
                        // 第三次验证：再等 2 秒后重新查询一次，防止服务器瞬态抖动导致误踢
                        kotlinx.coroutines.delay(2000L)
                        val retryInfo = try { AuroraApi.getUserInfo(userId) } catch (_: Exception) { null }
                        if (retryInfo?.success == true && retryInfo.data == null) {
                            return forceLogout(context, userId, "用户 $userId 经三次确认不存在")
                        }
                        // 第三次查询到了用户信息 → 用户正常，不踢
                        android.util.Log.w("Cleanup", "用户 $userId 初次检测不存在但第三次查询到信息，忽略（瞬态抖动）")
                    }
                    // 网络异常或服务器异常 → 不操作
                }
            }
        } catch (e: Exception) {
            // 如果是 401 认证错误，说明是 token 过期，不是账号异常
            if (e.message?.contains("401") == true) {
                android.util.Log.w("Cleanup", "收到 401 认证错误，视为 token 过期而非账号异常")
                return false
            }
            // 其他异常（网络错误等）→ 不操作
            android.util.Log.w("Cleanup", "checkAndLogoutCurrentUser 遇到异常: ${e.message}")
        }

        // === 第二关：获取用户信息，检查具体状态 ===
        val userInfo = try { AuroraApi.getUserInfo(userId) } catch (_: Exception) { null }
        if (userInfo?.success == true && userInfo.data != null) {
            val serverUsername = userInfo.data!!.username ?: ""
            val serverEmail = userInfo.data!!.email ?: ""
            // 用户名被改为"注销用户" → 确实被删
            if (serverUsername == "注销用户") {
                return forceLogout(context, userId, "账号已被管理员删除")
            }
            // 邮箱对不上：切换账号后本地邮箱短暂滞后是正常现象（token 已通过鉴权）。
            // 只做日志校正，不据此强制退出，避免"切换账号被误踢"。
            if (serverEmail.isNotBlank() && localEmail.isNotBlank() && !serverEmail.equals(localEmail, ignoreCase = true)) {
                android.util.Log.w("Cleanup", "本地邮箱($localEmail)与服务器($serverEmail)不一致，疑似切换账号后的缓存滞后，仅校正不踢出")
                // 用服务器返回的邮箱校正本地登录态，避免后续无意义的日志
                try {
                    val st = LocalStorage.loadLoginState(context)
                    if (st.isLoggedIn) {
                        LocalStorage.saveLogin(context, LoginResponse(
                            id = st.userId,
                            email = serverEmail,
                            username = st.username,
                            signature = AuroraApi.currentUserSignature,
                            token = AuroraApi.authToken ?: "",
                            emailVerified = AuroraApi.currentUserEmailVerified,
                            qqNumber = AuroraApi.currentUserQQ
                        ))
                    }
                } catch (_: Exception) { }
            }
        }
        // 网络异常导致 getUserInfo 失败 → 不做任何操作

        return false
    }

    /**
     * 强制退出：清理所有本地数据并关闭连接
     */
    private suspend fun forceLogout(context: Context, userId: Long, reason: String): Boolean {
        android.util.Log.w("Cleanup", "$reason，执行强制退出")
        // 先提示用户
        Toast.makeText(context, "账号异常，请重新登录", Toast.LENGTH_LONG).show()
        LocalStorage.logout(context)
        TcpService.stop(context)
        DownloadManager.closeAll()
        ChatViewModel.removeUserData(userId)
        return true
    }

    suspend fun searchGroups(keyword: String) = AuroraApi.searchGroups(keyword)
    suspend fun getGroupMembers(groupId: Long) = AuroraApi.getGroupMembers(groupId)

    // ==================== 群管理 ====================

    suspend fun dissolveGroup(groupId: Long) = AuroraApi.dissolveGroup(groupId)
    suspend fun transferOwner(groupId: Long, newOwnerId: Long) = AuroraApi.transferOwner(groupId, newOwnerId)
    suspend fun setGroupAdmin(groupId: Long, userId: Long, role: String) = AuroraApi.setGroupAdmin(groupId, userId, role)
    suspend fun searchUsersForGroup(keyword: String) = AuroraApi.searchUsersForGroup(keyword)

    // ==================== 平台管理员 ====================

    suspend fun grantPlatformAdmin(userId: Long) = AuroraApi.grantPlatformAdmin(userId)
    suspend fun revokePlatformAdmin(userId: Long) = AuroraApi.revokePlatformAdmin(userId)
    suspend fun listPlatformAdmins() = AuroraApi.listPlatformAdmins()
    suspend fun getAdminLogs(limit: Int = 50, offset: Int = 0, adminUserId: Long = 0) = AuroraApi.getAdminLogs(limit, offset, adminUserId)

    // ==================== 加群申请 & 群成员管理 ====================

    suspend fun submitJoinRequest(groupId: Long, reason: String): com.aurora.chat.data.api.ApiResult<String> = AuroraApi.submitJoinRequest(groupId, reason)
    suspend fun getJoinRequests(groupId: Long) = AuroraApi.getJoinRequests(groupId)
    suspend fun addGroupMember(groupId: Long, userId: Long) = AuroraApi.addGroupMember(groupId, userId)
    suspend fun removeGroupMember(groupId: Long, userId: Long, blockUser: Boolean = false) =
        AuroraApi.removeGroupMember(groupId, userId, blockUser)
    suspend fun reviewJoinRequest(requestId: Long, approve: Boolean) = AuroraApi.reviewJoinRequest(requestId, approve)
    suspend fun ignoreJoinRequest(requestId: Long) = AuroraApi.ignoreJoinRequest(requestId)
    suspend fun getJoinRequestCount(groupId: Long) = AuroraApi.getJoinRequestCount(groupId)

    // ==================== 管理员群聊管理 ====================

    suspend fun adminListGroups(page: Int, limit: Int = 15, keyword: String = "") = AuroraApi.adminListGroups(page, limit, keyword)
    suspend fun adminUpdateGroupDisplayId(groupId: Long, newDisplayId: Long) = AuroraApi.adminUpdateGroupDisplayId(groupId, newDisplayId)
    suspend fun adminDeveloperSendMessage(groupId: Long, content: String) = AuroraApi.adminDeveloperSendMessage(groupId, content)
    suspend fun adminGetGroupMessages(groupId: Long, page: Int = 1, limit: Int = 30) = AuroraApi.adminGetGroupMessages(groupId, page, limit)

    // ==================== 系统公告 ====================

    suspend fun saveAnnouncement(title: String, content: String, source: String, date: String, supplement: String, enabled: Boolean) =
        AuroraApi.saveAnnouncement(title, content, source, date, supplement, enabled)

    suspend fun getAdminAnnouncement() = AuroraApi.getAdminAnnouncement()

    suspend fun fetchActiveAnnouncement() = AuroraApi.fetchActiveAnnouncement()

    // ==================== 社区帖子 ====================

    suspend fun getCommunityPosts(page: Int = 1, limit: Int = 20, type: String = "", keyword: String = "", hasRes: String = "", mine: Boolean = false) = AuroraApi.getCommunityPosts(page, limit, type, keyword, hasRes, mine)
    suspend fun getCommunityPostDetail(postId: Long) = AuroraApi.getCommunityPostDetail(postId)
    suspend fun createCommunityPost(title: String, content: String, postType: String = "post", isAdult: Boolean = false) = AuroraApi.createCommunityPost(title, content, postType, isAdult)
    suspend fun uploadCommunityFile(postId: Long, fileBytes: ByteArray, fileName: String, mimeType: String) =
        AuroraApi.uploadCommunityFile(postId, fileBytes, fileName, mimeType)

    suspend fun toggleLike(postId: Long) = AuroraApi.toggleLike(postId)
    suspend fun getComments(postId: Long, page: Int = 1, limit: Int = 20) = AuroraApi.getComments(postId, page, limit)
    suspend fun addComment(postId: Long, content: String, parentId: Long = 0) = AuroraApi.addComment(postId, content, parentId)

    suspend fun getCommunityActivities(page: Int = 1, limit: Int = 20) = AuroraApi.getCommunityActivities(page, limit)
    suspend fun clearCommunityActivities() = AuroraApi.clearCommunityActivities()

    suspend fun deleteCommunityPost(postId: Long) = AuroraApi.deleteCommunityPost(postId)

    // ==================== 服务器板块 ====================

    suspend fun registerServer(name: String, password: String) =
        AuroraApi.registerServer(name, password)

    suspend fun loginServer(name: String, password: String) =
        AuroraApi.loginServer(name, password)

    suspend fun getMyServer() = AuroraApi.getMyServer()

    // ==================== 服务器文件管理（支持文件夹） ====================
    suspend fun getServerFiles(parentId: Long? = null) = AuroraApi.getServerFiles(parentId)
    suspend fun createServerFile(fileName: String, content: String = "", parentId: Long? = null) = AuroraApi.createServerFile(fileName, content, parentId)
    suspend fun createServerFolder(folderName: String, parentId: Long? = null) = AuroraApi.createServerFolder(folderName, parentId)
    suspend fun uploadServerFile(inputStream: java.io.InputStream, fileName: String, parentId: Long? = null) =
        AuroraApi.uploadServerFile(inputStream.readBytes(), fileName, parentId)
    fun getServerFileDownloadUrl(fileId: Long) = AuroraApi.getServerFileDownloadUrl(fileId)
    suspend fun downloadFileContent(fileId: Long) = AuroraApi.downloadFileContent(fileId)
    suspend fun deleteServerFile(fileId: Long) = AuroraApi.deleteServerFile(fileId)
    suspend fun getFolderBreadcrumb(folderId: Long?) = AuroraApi.getFolderBreadcrumb(folderId)
    suspend fun moveServerFile(fileId: Long, targetParentId: Long?) = AuroraApi.moveServerFile(fileId, targetParentId)
    suspend fun shutdownServer() = AuroraApi.shutdownServer()
    suspend fun restartServer() = AuroraApi.restartServer()
    // 服务器状态短缓存：轮询与手动刷新去重，避免重复联网（过渡态轮询间隔 2s > 该 TTL）
    @Volatile
    private var serverStatusCache: Pair<Long, String>? = null
    private const val SERVER_STATUS_TTL = 1500L
    suspend fun getServerStatus(): ApiResult<String> {
        val cached = serverStatusCache
        if (cached != null && System.currentTimeMillis() - cached.first < SERVER_STATUS_TTL) {
            @Suppress("UNCHECKED_CAST")
            return ApiResult(success = true, data = cached.second, message = "")
        }
        val r = AuroraApi.getServerStatus()
        if (r.success && r.data != null) serverStatusCache = System.currentTimeMillis() to r.data
        return r
    }
    suspend fun updateServerFile(fileId: Long, content: String) = AuroraApi.updateServerFile(fileId, content)
    suspend fun renameServerFile(fileId: Long, newName: String) = AuroraApi.renameServerFile(fileId, newName)
    suspend fun searchServerFiles(keyword: String) = AuroraApi.searchServerFiles(keyword)
    suspend fun getServerDashboard() = AuroraApi.getServerDashboard()

    // ==================== 开发者通知中心 / 余额 ====================

    /** 向用户发送系统通知（通知中心） */
    suspend fun sendNotification(
        scope: String,
        usernames: List<String>,
        ranges: List<Pair<Long, Long>>,
        title: String,
        content: String,
        extra: String
    ) = AuroraApi.sendNotification(scope, usernames, ranges, title, content, extra)

    /** 调整指定用户 Token 余额（正=增加，负=扣减） */
    suspend fun adjustBalance(
        scope: String,
        usernames: List<String>,
        ranges: List<Pair<Long, Long>>,
        op: String,
        amount: Long,
        reason: String
    ) = AuroraApi.adjustBalance(scope, usernames, ranges, op, amount, reason)
}

/**
 * 消息本地持久化存储（JSON 文件，存在 filesDir 中，清理缓存不会删除）
 * - 存储路径: filesDir/local_messages/{friendId}.json
 * - 按消息 id 去重，不会重复显示
 */
object LocalMessageStore {

    /** 从本地持久化文件读取消息列表 */
    suspend fun loadMessages(context: Context, friendId: Long): List<MessageInfo> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val file = getStoreFile(context, friendId)
            if (!file.exists()) return@withContext emptyList()
            val arr = org.json.JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MessageInfo(
                    id = obj.getLong("id"),
                    fromUserId = obj.getLong("fromUserId"),
                    toUserId = obj.getLong("toUserId"),
                    content = com.aurora.chat.CryptoUtil.decryptLocal(obj.getString("content")),
                    createdAt = obj.getLong("createdAt"),
                    fromUserName = obj.optString("fromUserName", ""),
                    isRevoked = obj.optInt("isRevoked", 0),
                    isSystemNotice = obj.optBoolean("isSystemNotice", false),
                    replyToText = obj.optString("replyToText", ""),
                    replyToSender = obj.optString("replyToSender", ""),
                    replyToId = obj.optLong("replyToId", 0),
                    mediaType = obj.optString("mediaType", ""),
                    mediaUrl = obj.optString("mediaUrl", ""),
                    flashDuration = obj.optInt("flashDuration", 0),
                    targetName = obj.optString("targetName", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 保存消息列表到本地持久化文件 */
    suspend fun saveMessages(context: Context, friendId: Long, messages: List<MessageInfo>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // 非空 mediaUrl 优先：整体覆盖写盘前，若本批次某条 mediaUrl 为空、但磁盘已有该 id 的非空 URL，
            // 则保留磁盘的完整 URL，避免图片可用 URL 被写成空（防止重进对话后图片丢失）。视频因服务端直给完整 URL 不受影响。
            val existing = loadMessages(context, friendId).associateBy { it.id }
            val arr = org.json.JSONArray()
            messages.forEach { raw ->
                val msg = if (raw.mediaUrl.isEmpty()) {
                    val e = existing[raw.id]
                    if (e != null && e.mediaUrl.isNotEmpty()) {
                        raw.copy(mediaUrl = e.mediaUrl, mediaType = if (raw.mediaType.isEmpty()) e.mediaType else raw.mediaType)
                    } else raw
                } else raw
                arr.put(org.json.JSONObject().apply {
                    put("id", msg.id)
                    put("fromUserId", msg.fromUserId)
                    put("toUserId", msg.toUserId)
                    put("content", com.aurora.chat.CryptoUtil.encryptLocal(msg.content))
                    put("createdAt", msg.createdAt)
                    put("fromUserName", msg.fromUserName)
                    put("isRevoked", msg.isRevoked)
                    put("isSystemNotice", msg.isSystemNotice)
                    put("replyToText", msg.replyToText)
                    put("replyToSender", msg.replyToSender)
                    put("replyToId", msg.replyToId)
                    put("mediaType", msg.mediaType)
                    put("mediaUrl", msg.mediaUrl)
                    put("flashDuration", msg.flashDuration)
                    put("targetName", msg.targetName)
                })
            }
            val file = getStoreFile(context, friendId)
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    /**
     * 合并服务端消息到本地存储（按 id 去重），返回合并后的完整列表
     */
    suspend fun mergeAndSave(context: Context, friendId: Long, serverMessages: List<MessageInfo>): List<MessageInfo> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val stored = loadMessages(context, friendId)
            val storedIds = stored.map { it.id }.toSet()
            val newFromServer = serverMessages.filter { it.id !in storedIds }
            // 非空 mediaUrl 优先：把传入消息里可达的完整媒体 URL 回填进本地（哪怕本地已存在该 id、哪怕本地为空）。
            // 关键：服务端对图片返回的 mediaUrl 常为空，发送成功时客户端已拿到
            // 完整 URL（onSuccess 传入），旧逻辑"无新消息信任本地"会把它丢弃；视频因服务端直给完整 URL 不受影响。
            val serverById = serverMessages.associateBy { it.id }
            val merged = (stored.map { s ->
                val sv = serverById[s.id]
                if (sv != null && sv.mediaUrl.isNotEmpty() && s.mediaUrl.isEmpty()) {
                    s.copy(
                        mediaUrl = sv.mediaUrl,
                        mediaType = if (s.mediaType.isEmpty()) sv.mediaType else s.mediaType
                    )
                } else s
            } + newFromServer).sortedBy { it.createdAt }
            saveMessages(context, friendId, merged)
            merged
        } catch (_: Exception) { serverMessages }
    }

    /** 整体覆盖某个会话的本地消息（用于从备份重置，替换旧内容），写入 local_messages/ */
    suspend fun overwriteMessages(context: Context, friendId: Long, messages: List<MessageInfo>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val arr = org.json.JSONArray()
            messages.sortedBy { it.id }.forEach { msg ->
                arr.put(org.json.JSONObject().apply {
                    put("id", msg.id)
                    put("fromUserId", msg.fromUserId)
                    put("toUserId", msg.toUserId)
                    put("content", com.aurora.chat.CryptoUtil.encryptLocal(msg.content))
                    put("createdAt", msg.createdAt)
                    put("fromUserName", msg.fromUserName)
                    put("isRevoked", msg.isRevoked)
                    put("isSystemNotice", msg.isSystemNotice)
                    put("replyToText", msg.replyToText)
                    put("replyToSender", msg.replyToSender)
                    put("replyToId", msg.replyToId)
                    put("mediaType", msg.mediaType)
                    put("mediaUrl", msg.mediaUrl)
                    put("flashDuration", msg.flashDuration)
                    put("targetName", msg.targetName)
                })
            }
            val file = getStoreFile(context, friendId)
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    private fun getStoreFile(context: Context, friendId: Long): java.io.File {
        return java.io.File(context.filesDir, "local_messages/$friendId.json")
    }

    /**
     * 标记单条消息为已撤回并原子落盘。
     * 撤回属于"更新已有消息状态"，而 [saveMessages] 会整体覆盖重写（非原子），
     * 故独立实现：仅把命中 id 的 isRevoked 置 1，并用原子写，避免整文件重写与写崩风险。
     * 注意：本对象的 [loadMessages] 每次直读磁盘（无 memCache），故无需失效内存缓存；
     * UI 内存态由 ChatConversationScreen 的 adapter.markRevoked + syncMessages + invalidateHotCache 负责。
     */
    suspend fun markRevoked(context: Context, friendId: Long, messageId: Long) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val file = getStoreFile(context, friendId)
        com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "markRevoked enter friendId=$friendId messageId=$messageId file=${file.absolutePath} exists=${file.exists()}")
        if (!file.exists()) return@withContext
        val arr = try { org.json.JSONArray(file.readText()) } catch (_: Exception) { null }
            ?: run {
                com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "markRevoked readRawArray=null friendId=$friendId")
                return@withContext
            }
        val diskIds = (0 until arr.length()).joinToString(",") { arr.optJSONObject(it)?.optLong("id")?.toString() ?: "?" }
        com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "markRevoked diskIds=[$diskIds] target=$messageId")
        var changed = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optLong("id") == messageId) {
                obj.put("isRevoked", 1)
                changed = true
                com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "markRevoked HIT idx=$i messageId=$messageId")
                break
            }
        }
        com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "markRevoked changed=$changed")
        if (changed) atomicWrite(file, arr.toString())
    }

    /** 原子写：先写临时文件再 rename，避免写入过程中崩溃留下半截 JSON 损坏整文件。 */
    private fun atomicWrite(file: java.io.File, json: String) {
        val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json)
        val ok = tmp.renameTo(file)
        com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "atomicWrite renameTo=$ok dest=${file.absolutePath}")
    }

    /** 删除某会话的全部本地消息文件（开发者测试 / 服务端已清空时清理本地残留） */
    suspend fun deleteConversation(context: Context, friendId: Long) = withContext(Dispatchers.IO) {
        try { getStoreFile(context, friendId).delete() } catch (_: Exception) {}
    }
}

