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
// 注意：这里不能 import com.aurora.chat.data.local.LocalMessageStore。
// 本文件底部也声明了一个同名 object（现已改为 SQLite 实现），两者同名会让
// 「只改了一半」这种错变得无声无息——旧 JSON 实现会继续被调用而不报错。删掉 import 后，
// 文件内所有 LocalMessageStore.xxx 都确定指向本文件的 SQLite 版本。
import com.aurora.chat.data.local.MessageDb
import com.aurora.chat.TcpService
import com.aurora.chat.ui.tools.DownloadManager
import com.aurora.chat.core.domain.ChatEvents
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

    suspend fun register(email: String, username: String, password: String, code: String = "", deviceId: String = "", qqNumber: String = "") =
        AuroraApi.register(email, username, password, code, deviceId, qqNumber)

    suspend fun registerWithCaptcha(
        email: String, username: String, password: String, code: String,
        captchaId: String, captchaAnswer: String, deviceId: String, qqNumber: String = ""
    ) = AuroraApi.registerWithCaptcha(email, username, password, code, captchaId, captchaAnswer, deviceId, qqNumber)

    /** 获取验证码挑战（captcha_id + 题目），供登录/注册页用作图形验证 */
    suspend fun fetchCaptchaChallengeSuspend(): Pair<String, String>? = AuroraApi.fetchCaptchaChallengeSuspend()

    suspend fun login(email: String, password: String) = AuroraApi.login(email, password)

    suspend fun qqLogin(openid: String, accessToken: String, username: String = "", deviceId: String = "") =
        AuroraApi.qqLogin(openid, accessToken, username, deviceId)

    suspend fun fetchQQAvatar(qq: String) = AuroraApi.fetchQQAvatar(qq)

    suspend fun resetPassword(email: String, code: String, newPassword: String) =
        AuroraApi.resetPassword(email, code, newPassword)

    // ==================== 「看见即缓存」：全局用户资料缓存 ====================
    // 任何接口只要返回了用户资料（资料页、搜索、好友、群成员、消息里的发送者），
    // 就顺手写进 UserProfileStore；之后断网也能按 userId 立刻查到名字/资料。
    // 写盘走单线程 executor + 内容无变化不写，联机路径几乎零开销。

    private fun appCtx(): Context = com.aurora.chat.AuroraChatApplication.instance

    private fun cacheUsers(users: List<UserInfo>?) {
        if (!users.isNullOrEmpty()) {
            com.aurora.chat.data.local.UserProfileStore.putAllUsers(appCtx(), users)
        }
    }

    /** 只带名字的列表型来源（好友申请 / 黑名单）。 */
    private fun cacheNames(pairs: List<Pair<Long, String>>) {
        if (pairs.isNotEmpty()) {
            com.aurora.chat.data.local.UserProfileStore.putNames(appCtx(), pairs)
        }
    }

    // ==================== 用户 ====================

    suspend fun searchUsers(keyword: String) = AuroraApi.searchUsers(keyword).also { cacheUsers(it.data) }

    suspend fun getAllUsers() = AuroraApi.getAllUsers().also { cacheUsers(it.data) }
    suspend fun searchAdminUsers(keyword: String) = AuroraApi.searchAdminUsers(keyword).also { cacheUsers(it.data) }

    suspend fun getOnlineUsers() = AuroraApi.getOnlineUsers().also { cacheUsers(it.data) }

    /** 开发者 / 管理员主动创建用户（培养开发者自建账号） */
    suspend fun adminCreateUser(username: String, qqNumber: String, password: String, avatarB64: String? = null) =
        AuroraApi.adminCreateUser(username, qqNumber, password, avatarB64)

    // ==================== 好友请求 ====================

    suspend fun sendFriendRequest(target: String, greeting: String = "") =
        AuroraApi.sendFriendRequest(target, greeting)

    suspend fun getFriendRequests() = AuroraApi.getFriendRequests().also { r ->
        cacheNames(r.data?.map { it.fromUserId to it.fromUsername } ?: emptyList())
    }

    suspend fun respondFriendRequest(requestId: Long, action: String) =
        AuroraApi.respondFriendRequest(requestId, action)

    suspend fun cancelFriendRequest(requestId: Long) =
        AuroraApi.cancelFriendRequest(requestId)

    // ==================== 好友 ====================

    suspend fun getFriends() = AuroraApi.getFriends().also { r ->
        cacheUsers(r.data)
        // 好友列表是「我这边」的单向事实，只用来提供正向证据（是好友 → 解除标记）。
        // 它永远不能单独证明「不是好友」：列表为空 / 抖动 / JOIN 丢行都会造成全局误报横幅。
        val users = r.takeIf { it.success }?.data
        if (users != null) {
            val ids = users.map { it.id }
            FriendIdSnapshot.replace(appCtx(), ids)
            FriendRelationMarker.clearStrangers(appCtx(), ids)
        }
    }
    suspend fun sendPoke(toUserId: Long, targetUserId: Long = 0) = AuroraApi.sendPoke(toUserId, targetUserId)
    suspend fun deleteFriend(friendId: Long) = AuroraApi.deleteFriend(friendId).also { r ->
        // 删除成功 = 百分百确定的非好友动作：立刻挂标记（离线也保留），
        // 同时清掉该用户的一切好友证据，避免下一帧又被当成好友。
        if (r.success) FriendRelationMarker.markStranger(appCtx(), friendId)
    }
    /** 好友关系权威结论（服务端单向口径 + certain）。只有它能新增本地「非好友标记」。 */
    suspend fun getFriendStatus(userId: Long) = AuroraApi.getFriendStatus(userId)

    // ==================== 用户级黑名单 ====================

    suspend fun blockUser(targetUserId: Long) = AuroraApi.blockUser(targetUserId)
    suspend fun unblockUser(targetUserId: Long) = AuroraApi.unblockUser(targetUserId)
    suspend fun getBlacklist() = AuroraApi.getBlacklist().also { r ->
        cacheNames(r.data?.map { it.userId to it.username } ?: emptyList())
    }
    suspend fun isBlocked(targetUserId: Long) = AuroraApi.isBlocked(targetUserId)
    suspend fun isFriend(userId: Long) = AuroraApi.isFriend(userId)
    suspend fun updatePrivacy(
        hideEmail: Boolean? = null,
        hideQQ: Boolean? = null,
        requireGroupConsent: Boolean? = null,
        blockStrangerMsg: Boolean? = null
    ) = AuroraApi.updatePrivacy(hideEmail, hideQQ, requireGroupConsent, blockStrangerMsg)
    suspend fun updateSignature(signature: String) = AuroraApi.updateSignature(signature)
    /**
     * 用户资料：联网成功即写全局缓存；断网 / 接口失败时回退到「看过的」缓存。
     * 好友资料面板、社区用户卡等全部走这里，一处接入即可离线可用。
     */
    suspend fun getUserInfo(userId: Long): ApiResult<UserInfo> {
        val ctx = appCtx()
        val r = AuroraApi.getUserInfo(userId)
        if (r.success && r.data != null) {
            com.aurora.chat.data.local.UserProfileStore.putUser(ctx, r.data!!)
            return r
        }
        val cached = com.aurora.chat.data.local.UserProfileStore.get(ctx, userId)
        return if (cached != null) ApiResult(true, "缓存", cached) else r
    }

    /** 首帧即时显示用的缓存快照（纯内存，不联网）。 */
    fun getCachedUserInfo(userId: Long): UserInfo? =
        if (com.aurora.chat.data.local.UserProfileStore.nameOf(userId).isEmpty()) null
        else com.aurora.chat.data.local.UserProfileStore.get(appCtx(), userId)

    /**
     * 群资料：联网成功即写穿缓存；失败/异常时返回缓存（断网也能显示群号、群二维码离线可用）。
     * 调用方（群设置页、群二维码页）拿到的 data 与后端 /api/groups/info 完全一致。
     */
    suspend fun getGroupInfo(groupId: Long): com.aurora.chat.data.api.ApiResult<org.json.JSONObject> {
        val ctx = appCtx()
        return try {
            val r = AuroraApi.getGroupInfo(groupId)
            if (r.success && r.data != null) {
                com.aurora.chat.data.local.GroupInfoStore.put(ctx, groupId, r.data!!)
                r
            } else {
                val cached = com.aurora.chat.data.local.GroupInfoStore.get(ctx, groupId)
                if (cached != null) com.aurora.chat.data.api.ApiResult(true, "缓存", cached)
                else r
            }
        } catch (e: Exception) {
            val cached = com.aurora.chat.data.local.GroupInfoStore.get(ctx, groupId)
            if (cached != null) com.aurora.chat.data.api.ApiResult(true, "缓存", cached)
            else throw e
        }
    }

    /** 首帧即时显示用的群资料缓存快照（纯内存，不联网）。 */
    fun getCachedGroupInfo(groupId: Long): org.json.JSONObject? =
        com.aurora.chat.data.local.GroupInfoStore.get(appCtx(), groupId)
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

    // mediaWidth：语音消息用它承载时长（秒），后端 media_width 列原样存回；非语音恒 0。
    suspend fun sendMessage(toUserId: Long, content: String, replyTo: Long = 0, mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0, replyToText: String = "", replyToSender: String = "", contactSource: String = "", mediaWidth: Int = 0, mediaHeight: Int = 0, mentions: Map<String, Long> = emptyMap()) =
        AuroraApi.sendMessage(toUserId, content, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender, contactSource, mediaWidth = mediaWidth, mediaHeight = mediaHeight, mentions = mentions)

    suspend fun sendGroupMessage(toUserId: Long, content: String, replyTo: Long = 0, mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0, replyToText: String = "", replyToSender: String = "", mediaWidth: Int = 0, mediaHeight: Int = 0, mentions: Map<String, Long> = emptyMap()) =
        AuroraApi.sendGroupMessage(toUserId, content, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender, mediaWidth = mediaWidth, mediaHeight = mediaHeight, mentions = mentions)

    /**
     * 本地清理过的会话：历史请求统一带上水位线 after_id，服务端只返回清理之后的新消息。
     * 这条是「本地清理不复活」的总闸——进入会话、滚到顶分页、推送补拉、会话列表同步、
     * ChatViewModel.loadMessages 全都经过这两个函数，一处拦截即可全覆盖。
     */
    private suspend fun fetchHistoryGuarded(
        convId: Long,
        afterId: Long,
        fetch: suspend (Long) -> ApiResult<List<MessageInfo>>
    ): ApiResult<List<MessageInfo>> {
        val ctx = com.aurora.chat.AuroraChatApplication.instance
        val effective = com.aurora.chat.data.local.LocalClearStore.effectiveAfterId(ctx, convId, afterId)
        val r = fetch(effective)
        val list = r.data ?: return r
        if (list.isEmpty()) return r
        val filtered = com.aurora.chat.data.local.LocalClearStore.filterHistory(ctx, convId, list)
        // 聊天里出现过的发送者就是「看过的用户」，看见一次即缓存一次（断网后名字仍可解析）。
        if (r.success && filtered.isNotEmpty()) {
            com.aurora.chat.data.local.UserProfileStore.putNamesFromMessages(ctx, filtered)
        }
        return if (filtered.size == list.size) r else r.copy(data = filtered)
    }

    suspend fun getGroupMessages(groupId: Long, limit: Int = 100, offset: Int = 0, afterId: Long = 0): ApiResult<List<MessageInfo>> =
        fetchHistoryGuarded(groupId, afterId) { effective ->
            AuroraApi.getGroupMessages(groupId, limit, offset, effective)
        }

    suspend fun getMessages(userId1: Long, userId2: Long, limit: Int = 50, offset: Int = 0, afterId: Long = 0): ApiResult<List<MessageInfo>> =
        fetchHistoryGuarded(userId2, afterId) { effective ->
            AuroraApi.getMessages(userId1, userId2, limit, offset, effective)
        }

    // ==================== 会话 ====================

    suspend fun getConversations(forceRefresh: Boolean = false) = AuroraApi.getConversations(forceRefresh)

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
        } catch (t: Throwable) {
            // 含 OutOfMemoryError：头像是用户主动操作，宁可返回失败也不能让异常逃到主线程
            // （逃出去会被 UiCrashGuard 兜成「刚才的操作触发异常」，用户以为上传功能坏了）
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.e("Avatar", "Upload exception", t)
            com.aurora.chat.ErrorReporter.warn(
                "Avatar", "头像上传失败：${t.javaClass.simpleName}: ${t.message}", t
            )
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
        val myId = AuroraApi.currentUserId
        // 1. 清除缓存（内存 + 磁盘），但保留自己的头像。
        //    自己的头像是「本地优先」的基准，清掉会让「我的」页又出现占位图闪烁，
        //    也违背「首次从服务器拿到的头像要落本地」这条约定。
        AvatarCache.clearAllExcept(context, myId)
        android.util.Log.d("Avatar", "登录时已清除其他头像缓存（保留自己的头像）")

        // 2. 预加载自己的头像（本地还在就直接命中，不会走网络）
        try {
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
                                     notSearchable: Boolean = false, joinRequired: Boolean = false,
                                     announcements: org.json.JSONArray? = null): ApiResult<Unit> {
        return AuroraApi.updateGroupSettings(groupId, name, signature, announcement, welcomeEnabled, welcomeText, notSearchable, joinRequired, announcements)
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
                ChatEvents.sink.refreshGroupAvatars()
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
        ChatEvents.sink.removeUserData(userId)
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
        ChatEvents.sink.removeUserData(userId)
        return true
    }

    suspend fun searchGroups(keyword: String) = AuroraApi.searchGroups(keyword)
    /**
     * 群成员名单：联网成功即整份落盘 + 把每个成员名字写进全局用户缓存；
     * 断网 / 403 / 超时时直接把上次的名单当结果返回。
     * 成员页、群设置、@ 列表、转让群主、禁言/黑名单子面板等所有调用点一处接入全部离线可用。
     */
    suspend fun getGroupMembers(groupId: Long): ApiResult<org.json.JSONArray> {
        val ctx = appCtx()
        val r = AuroraApi.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val arr = r.data!!
            withContext(Dispatchers.IO) {
                com.aurora.chat.data.local.GroupMemberStore.save(ctx, groupId, arr)
                com.aurora.chat.data.local.UserProfileStore.putNamesFromMemberArray(ctx, arr)
            }
            return r
        }
        val cached = withContext(Dispatchers.IO) {
            com.aurora.chat.data.local.GroupMemberStore.load(ctx, groupId)
        }
        return if (cached != null && cached.length() > 0) ApiResult(true, "缓存", cached) else r
    }

    // ==================== 群管理 ====================

    suspend fun dissolveGroup(groupId: Long) = AuroraApi.dissolveGroup(groupId)
    /** 群内发言频率：-1=不限制，0=全员禁言，N>0=每位成员每分钟最多 N 条 */
    suspend fun setGroupMsgRate(groupId: Long, limit: Int, scope: Int? = null) =
        AuroraApi.setGroupMsgRate(groupId, limit, scope)
    suspend fun transferOwner(groupId: Long, newOwnerId: Long) = AuroraApi.transferOwner(groupId, newOwnerId)
    suspend fun setGroupAdmin(groupId: Long, userId: Long, role: String) = AuroraApi.setGroupAdmin(groupId, userId, role)
    suspend fun searchUsersForGroup(keyword: String) = AuroraApi.searchUsersForGroup(keyword).also { r ->
        val arr = r.data ?: return@also
        cacheNames((0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val uid = o.optLong("id", o.optLong("user_id", 0L))
            val name = o.optString("username", "")
            if (uid > 0L && name.isNotBlank()) uid to name else null
        })
    }

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
    suspend fun adminDissolveGroupsByCreator(creatorId: Long, dryRun: Boolean, limit: Int = 0) =
        AuroraApi.adminDissolveGroupsByCreator(creatorId, dryRun, limit)

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
    suspend fun likeComment(commentId: Long) = AuroraApi.likeComment(commentId)
    suspend fun deleteComment(commentId: Long) = AuroraApi.deleteComment(commentId)

    suspend fun getCommunityActivities(page: Int = 1, limit: Int = 20) = AuroraApi.getCommunityActivities(page, limit)
    suspend fun clearCommunityActivities() = AuroraApi.clearCommunityActivities()

    suspend fun deleteCommunityPost(postId: Long) = AuroraApi.deleteCommunityPost(postId)

    // ==================== 服务器板块 ====================



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
    // ==================== 开发者通知中心 / 余额 ====================

    /** 向用户发送系统通知（通知中心） */
    suspend fun sendNotification(
        scope: String,
        userIds: List<Long>,
        ranges: List<Pair<Long, Long>>,
        title: String,
        content: String,
        extra: String
    ) = AuroraApi.sendNotification(scope, userIds, ranges, title, content, extra)

    /** 调整指定用户 Token 余额（正=增加，负=扣减） */
    suspend fun adjustBalance(
        scope: String,
        userIds: List<Long>,
        ranges: List<Pair<Long, Long>>,
        op: String,
        amount: Long,
        reason: String
    ) = AuroraApi.adjustBalance(scope, userIds, ranges, op, amount, reason)

    // ==================== 好友关系本地标记（聊天页横幅的唯一判定依据） ====================

    /**
     * 是否存在该用户的好友证据（纯本地、不联网）。
     * 来源：好友列表快照 + 「服务端明确说是好友」写下的粘性确认。
     * 只要为 true，横幅一律不显示 —— 哪怕服务端结论还没回来。
     */
    fun isLocalFriend(userId: Long): Boolean {
        val ctx = appCtx()
        return FriendIdSnapshot.contains(ctx, userId) || FriendRelationMarker.isConfirmedFriend(ctx, userId)
    }

    /** 「非好友标记」是否挂着：横幅只由它驱动，跨重启、离线都有效。 */
    fun isMarkedStranger(userId: Long): Boolean = FriendRelationMarker.isStranger(appCtx(), userId)

    /**
     * 是好友 → 解除标记，并写下粘性好友证据。
     * 好友列表命中、服务端状态为好友、服务端明确返回「已经是好友」都走这里。
     */
    fun markAsFriend(userId: Long) {
        val ctx = appCtx()
        FriendIdSnapshot.add(ctx, userId)
        FriendRelationMarker.markFriend(ctx, userId)
    }

    /**
     * 挂上「非好友标记」。
     * 只允许两个调用方：① 服务端 /api/friend/status 返回 certain=true 且 is_friend=false；
     * ② 本地 deleteFriend() 成功。其余（好友列表为空、请求失败、超时、字段缺失）一律不许调用。
     */
    fun markStranger(userId: Long) = FriendRelationMarker.markStranger(appCtx(), userId)

    /** 解除标记（不写好友证据）：好友申请刚被同意等场景。 */
    fun clearStrangerMark(userId: Long) = FriendRelationMarker.clearStranger(appCtx(), userId)

    /**
     * 好友 ID 快照：getFriends() 成功时全量替换。
     * 只提供正向证据，**绝不参与「非好友」判定**（列表为空/抖动/JOIN 丢行会造成全局误报横幅）。
     */
    private object FriendIdSnapshot {
        private const val PREF = "friend_id_snapshot"
        private const val KEY_IDS = "ids"
        @Volatile private var cached: Set<Long>? = null

        private fun prefs(ctx: Context) =
            ctx.getSharedPreferences("${PREF}_${AuroraApi.currentUserId}", Context.MODE_PRIVATE)

        @Synchronized
        fun contains(ctx: Context, id: Long): Boolean = all(ctx).contains(id)

        @Synchronized
        fun replace(ctx: Context, ids: Collection<Long>) {
            val set = ids.toSet()
            cached = set
            prefs(ctx).edit().putString(KEY_IDS, set.joinToString(",")).apply()
        }

        @Synchronized
        fun add(ctx: Context, id: Long) {
            val set = all(ctx).toMutableSet().apply { add(id) }
            replace(ctx, set)
        }

        @Synchronized
        fun remove(ctx: Context, id: Long) {
            val set = all(ctx).toMutableSet().apply { remove(id) }
            replace(ctx, set)
        }

        private fun all(ctx: Context): Set<Long> {
            cached?.let { return it }
            val s = prefs(ctx).getString(KEY_IDS, "") ?: ""
            val set = if (s.isEmpty()) emptySet<Long>()
                      else s.split(",").mapNotNull { it.toLongOrNull() }.toSet()
            cached = set
            return set
        }
    }

    /**
     * 好友关系标记（按账号隔离、SharedPreferences 持久化）：
     *  - strangerIds：只有服务端百分百判定非好友才写入；聊天页横幅只认它，离线也挂得住。
     *  - confirmedFriendIds：粘性好友证据，getFriends() 的全量替换不会把它冲掉，
     *    避免「服务端说已经是好友 → 自愈 → 下次拉列表又被替换掉 → 横幅又回来」的死循环。
     * 两者互斥：写入一方时同步从另一方移除。
     */
    private object FriendRelationMarker {
        private const val PREF = "friend_relation_marker"
        private const val KEY_STRANGERS = "stranger_ids"
        private const val KEY_FRIENDS = "confirmed_friend_ids"

        private fun prefs(ctx: Context) =
            ctx.getSharedPreferences("${PREF}_${AuroraApi.currentUserId}", Context.MODE_PRIVATE)

        private fun read(ctx: Context, key: String): MutableSet<Long> =
            (prefs(ctx).getString(key, "") ?: "")
                .split(",").mapNotNull { it.toLongOrNull() }.toMutableSet()

        private fun write(ctx: Context, key: String, ids: Set<Long>) =
            prefs(ctx).edit().putString(key, ids.joinToString(",")).apply()

        @Synchronized
        fun isStranger(ctx: Context, id: Long): Boolean = read(ctx, KEY_STRANGERS).contains(id)

        @Synchronized
        fun isConfirmedFriend(ctx: Context, id: Long): Boolean = read(ctx, KEY_FRIENDS).contains(id)

        /** 是好友就解除标记，并记下粘性好友证据。 */
        @Synchronized
        fun markFriend(ctx: Context, id: Long) {
            write(ctx, KEY_FRIENDS, read(ctx, KEY_FRIENDS).apply { add(id) })
            write(ctx, KEY_STRANGERS, read(ctx, KEY_STRANGERS).apply { remove(id) })
        }

        /** 非好友：挂标记，同时清掉该用户的一切好友证据（含好友列表快照）。 */
        @Synchronized
        fun markStranger(ctx: Context, id: Long) {
            write(ctx, KEY_STRANGERS, read(ctx, KEY_STRANGERS).apply { add(id) })
            write(ctx, KEY_FRIENDS, read(ctx, KEY_FRIENDS).apply { remove(id) })
            FriendIdSnapshot.remove(ctx, id)
        }

        @Synchronized
        fun clearStranger(ctx: Context, id: Long) {
            write(ctx, KEY_STRANGERS, read(ctx, KEY_STRANGERS).apply { remove(id) })
        }

        /** 好友列表里出现的 ID 一律解除标记（覆盖申请被同意、推送刷新等所有入口）。 */
        @Synchronized
        fun clearStrangers(ctx: Context, ids: Collection<Long>) {
            if (ids.isEmpty()) return
            write(ctx, KEY_STRANGERS, read(ctx, KEY_STRANGERS).apply { removeAll(ids.toSet()) })
        }
    }
}

/**
 * ## 消息本地持久化（SQLite，filesDir/aurora_messages.db）
 *
 * 旧实现是 `local_messages/{friendId}.json` 单文件：每次保存都要
 * 整文件读入 → 全量 JSON 解析 → 逐条解密（取已有内容）→ 逐条重新加密 → 全量序列化 → 整文件写。
 * 成本 O(会话总条数)：一万条约几百毫秒、五万条秒级，且堆里瞬时出现一个几十 MB 的字符串。
 * 这就是「越聊越卡 / 进大群卡死 / 发消息顿一下」的根因。
 *
 * 现在全部走 [MessageDb]：
 *  - 写：只 INSERT 真正新增的行，O(新增条数)，与历史条数无关；
 *  - 读：首屏只取最近 N 条，上滑用游标取更旧一页，都是主键索引查询；
 *  - 全量接口（历史搜索 / 备份）保留，但不再参与日常收发路径。
 *
 * 对外方法签名保持不变，既有调用点无需改动。
 */
object LocalMessageStore {

    /** 分页结果：一页消息 + 是否还有更旧的（滚到顶时据此决定是否继续上翻）。 */
    data class MessagePage(val messages: List<MessageInfo>, val hasOlder: Boolean)

    /** 会话在库里的主键。私聊正数 id、群聊负数 id，天然不撞。 */
    private fun convKey(friendId: Long): String = friendId.toString()

    /**
     * 最近 [limit] 条（升序）。进入会话时只调这个，不再全量读盘 + 全量解密。
     * [MessagePage.hasOlder] 由一次 EXISTS 查询得出，成本常数级。
     */
    suspend fun loadRecentMessages(context: Context, friendId: Long, limit: Int): MessagePage =
        withContext(Dispatchers.IO) {
            try {
                val key = convKey(friendId)
                // 只迁这一个会话的旧 JSON（毫秒级），不等全量迁移，避免老用户首次进入先卡几秒
                MessageDb.ensureMigratedFor(context, key)
                val msgs = MessageDb.loadRecent(context, key, limit)
                val older = msgs.isNotEmpty() && MessageDb.hasOlder(context, key, msgs.first().id)
                MessagePage(msgs, older)
            } catch (_: Exception) {
                MessagePage(emptyList(), false)
            }
        }

    /** 比 [beforeMsgId] 更旧的一页（升序）。游标分页：不重复、不漏，也不受新消息插入影响。 */
    suspend fun loadOlderMessages(
        context: Context,
        friendId: Long,
        beforeMsgId: Long,
        limit: Int
    ): List<MessageInfo> = withContext(Dispatchers.IO) {
        try {
            MessageDb.ensureMigratedFor(context, convKey(friendId))
            MessageDb.loadOlder(context, convKey(friendId), beforeMsgId, limit)
        } catch (_: Exception) { emptyList() }
    }

    /** 是否还有更旧消息（滚到顶时先问这个，再决定要不要真去取一页）。 */
    suspend fun hasOlderMessages(context: Context, friendId: Long, beforeMsgId: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // 这里也要按需迁移：热缓存命中时进会话不会走 loadRecentMessages，
                // 少了这一句，首次升级后会被判成「本地没有更早的」，老历史直接翻不到。
                MessageDb.ensureMigratedFor(context, convKey(friendId))
                MessageDb.hasOlder(context, convKey(friendId), beforeMsgId)
            } catch (_: Exception) { false }
        }

    /** 全量读取（历史搜索 / 备份导出）。会解密全部，日常收发路径不要再调它。 */
    suspend fun loadMessages(context: Context, friendId: Long): List<MessageInfo> = withContext(Dispatchers.IO) {
        try {
            MessageDb.ensureMigrated(context)
            MessageDb.loadAll(context, convKey(friendId))
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 保存消息（按 id 增量写入）。
     *
     * 语义与旧实现一致：新 id 插入；已存在的 id 不覆盖，只把库里为空的媒体 / 分类字段补上。
     * 但成本从「整段历史重写」变成「只写新增行」——
     * 即使调用方传的是全量列表，也只是 O(n) 次哈希判断，不产生任何写放大。
     */
    suspend fun saveMessages(context: Context, friendId: Long, messages: List<MessageInfo>) =
        withContext(Dispatchers.IO) {
            try {
                // 最后一道防线：本地清理水位线之前的旧消息绝不允许重新落库
                // （防止推送 / 备份 / 直连 API 绕过 fetchHistoryGuarded 把历史写回来）。
                val safe = com.aurora.chat.data.local.LocalClearStore.filterHistory(context, friendId, messages)
                if (safe.isEmpty()) return@withContext
                MessageDb.ensureMigratedFor(context, convKey(friendId))
                MessageDb.upsert(context, convKey(friendId), safe)
            } catch (e: Exception) {
                // 不再静默吞掉：写库失败必须留痕，避免「聊了半天重进就没了」查无实据
                com.aurora.chat.ErrorReporter.debug(
                    "MESSAGE_DB",
                    "saveMessages 失败 conv=$friendId size=${messages.size}: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

    /**
     * 合并服务端消息并落库。
     *
     * 旧实现为了返回「合并后的全量列表」，要 loadMessages（全量解密）+ 合并 + saveMessages（全量重写），
     * 每完成一个媒体上传就跑两遍全量。现有调用方都不使用返回值，故改为：
     * 把入参增量写库（只补空字段），返回入参本身。确实需要全量时请显式调用 [loadMessages]。
     */
    suspend fun mergeAndSave(
        context: Context,
        friendId: Long,
        serverMessages: List<MessageInfo>
    ): List<MessageInfo> = withContext(Dispatchers.IO) {
        val safe = com.aurora.chat.data.local.LocalClearStore.filterHistory(context, friendId, serverMessages)
        try {
            if (safe.isEmpty()) return@withContext safe
            MessageDb.ensureMigratedFor(context, convKey(friendId))
            MessageDb.upsert(context, convKey(friendId), safe)
        } catch (_: Exception) { }
        safe
    }

    /** 整体覆盖某个会话（从备份重置，替换旧内容）。 */
    suspend fun overwriteMessages(context: Context, friendId: Long, messages: List<MessageInfo>) =
        withContext(Dispatchers.IO) {
            try {
                MessageDb.ensureMigratedFor(context, convKey(friendId))
                MessageDb.overwrite(context, convKey(friendId), messages.sortedBy { it.id })
            } catch (_: Exception) { }
        }

    /** 标记单条消息已撤回（单行 UPDATE，不再「整文件读入 + 改一个字段 + 整文件写回」）。 */
    suspend fun markRevoked(context: Context, friendId: Long, messageId: Long) = withContext(Dispatchers.IO) {
        try {
            MessageDb.ensureMigratedFor(context, convKey(friendId))
            MessageDb.markRevoked(context, convKey(friendId), messageId)
            com.aurora.chat.ErrorReporter.debug(
                "RECALL_TRACE", "db markRevoked friendId=$friendId messageId=$messageId"
            )
        } catch (_: Exception) { }
    }

    /** 删除某会话的全部本地消息（开发者测试 / 服务端已清空时清理本地残留）。 */
    suspend fun deleteConversation(context: Context, friendId: Long) = withContext(Dispatchers.IO) {
        try {
            val key = convKey(friendId)
            MessageDb.deleteConversation(context, key)
            // 两个遗留 JSON 目录都必须删干净：MessageDb.ensureMigratedFor 每次进会话都会
            // 检查 local_messages/ 与 messages/ 下的 {key}.json 并重新导入 SQLite。
            // 旧实现只删了 local_messages 一个，messages/ 里的老文件会在下次进会话时
            // 把已清空的聊天记录整段「复活」——这是清理后数据回来的第二条路径。
            for (dirName in arrayOf("local_messages", "messages")) {
                val dir = java.io.File(context.filesDir, dirName)
                java.io.File(dir, "$key.json").delete()
                java.io.File(dir, "$key.json.imported").delete()
            }
        } catch (_: Exception) { }
    }

}

