package com.aurora.chat.ui.viewmodel

import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.data.api.FriendRequestInfo
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.data.local.LocalConversationStore
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.data.repository.LocalMessageStore
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.chat.isGroupChat
import com.aurora.chat.util.NetworkMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import com.aurora.chat.data.api.UserInfo
import android.content.Context

// ==================== 用户信息查询结果（public，避免被 internal 类包裹） ====================
data class UserInfoResult(val userInfo: UserInfo? = null, val friends: List<ConversationInfo> = emptyList())

// ==================== LRU 消息缓存（按会话 key 缓存，超阈值淘汰最久未访问的） ====================
class LruCache<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>(0, 0.75f, true) // accessOrder=true
    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) {
        while (map.size >= maxSize && !map.containsKey(key)) {
            val oldest = map.entries.firstOrNull()?.key ?: break
            map.remove(oldest)
        }
        map[key] = value
    }
    @Synchronized fun remove(key: K) = map.remove(key)
    @Synchronized fun clear() = map.clear()
}

// ==================== 内部状态管理器（绑定 SupervisorJob，配套后台调度器）= ====================
internal class ChatViewModelDelegate {

    /** LRU 消息缓存：最多 20 个会话的消息快照 */
    val msgCache = LruCache<String, List<ChatMsg>>(20)

    /** 主线程协程作用域（页面退出时 cancel 终止全部异步任务） */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 后台 IO 协程（上传等持续任务） */
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==================== 网络状态 ====================
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()
    // ==================== 对话列表 ====================
    private val _conversations = MutableStateFlow<List<ConversationInfo>>(emptyList())
    val conversations: StateFlow<List<ConversationInfo>> = _conversations.asStateFlow()
    private val _conversationsLoading = MutableStateFlow(false)
    val conversationsLoading: StateFlow<Boolean> = _conversationsLoading.asStateFlow()
    private var loadConversationsJob: Job? = null
    /** 上次成功拉取对话列表的时间戳，用于新鲜度守卫 */
    private var lastSuccessLoadTime = 0L
    /** 连续拉取失败次数：仅当“连续多次”都失败才判定为离线，避免启动连接过程中的单次超时误报离线横幅 */
    private var offlineFailStreak = 0

    // ==================== 好友请求 ====================
    private val _friendRequests = MutableStateFlow<List<FriendRequestInfo>>(emptyList())
    val friendRequests: StateFlow<List<FriendRequestInfo>> = _friendRequests.asStateFlow()
    private val _friendRequestsLoading = MutableStateFlow(false)
    val friendRequestsLoading: StateFlow<Boolean> = _friendRequestsLoading.asStateFlow()

    // ==================== TCP 推送通知 ====================
    private val _newMessageReceived = MutableStateFlow(0)
    val newMessageReceived: StateFlow<Int> = _newMessageReceived.asStateFlow()
    private val _friendRequestReceived = MutableStateFlow(0)
    val friendRequestReceived: StateFlow<Int> = _friendRequestReceived.asStateFlow()
    private val _selfChatRefreshKey = MutableStateFlow(0)
    val selfChatRefreshKey: StateFlow<Int> = _selfChatRefreshKey.asStateFlow()
    private val _groupAvatarRefreshKey = MutableStateFlow(0)
    val groupAvatarRefreshKey: StateFlow<Int> = _groupAvatarRefreshKey.asStateFlow()

    // ==================== 未读消息（客户端本地跟踪，不改后端） ====================
    // 内存镜像供 UI 订阅（避免列表逐项读 SP 造成重组开销）；SP 仅用于进程死亡后持久化。
    private val _unreadCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<Long, Int>> = _unreadCounts.asStateFlow()

    /** 当前正在查看的会话 id（"正在看的会话不计未读"）；0 表示不在任何会话内 */
    @Volatile var openConversationId: Long = 0L

    /** 已读游标：每个会话"已读到的最新消息时间"，用于 loadConversations 兜底补齐未读（不依赖实时推送） */
    private val readUpTo = mutableMapOf<Long, Long>()
    private var readUpToInitialized = false

    private fun unreadSp(context: Context, userId: Long) =
        context.getSharedPreferences("aurora_unread_$userId", Context.MODE_PRIVATE)

    /** 已载入未读的账号（用于切换账号时以 SP 为准，避免跨账号串号）。-1 表示尚未载入 */
    @Volatile private var loadedUserId = -1L

    /**
     * 进入聊天板块/登录后载入当前用户未读。
     *
     * 关键约束：内存(_unreadCounts)是运行期增量的唯一真相源，本函数只做"初始化/补全"，
     * **绝不能以 SP 覆盖内存中已有的增量**——否则会与 TcpService.addUnread 的异步 SP 写入(sp.edit().apply())
     * 产生竞态：切回大号自动进入聊天 tab 触发本函数时，addUnread 的 apply 往往尚未落盘，
     * 覆盖式 clear 会把刚收到的未读整个清掉，导致红点不显示。
     */
    fun loadUnreadForUser(context: Context, userId: Long) {
        try {
            val sp = unreadSp(context, userId)
            val spMap = mutableMapOf<Long, Int>()
            sp.all.forEach { (k, v) ->
                if (k.startsWith("u_")) {
                    val id = k.removePrefix("u_").toLongOrNull() ?: return@forEach
                    val n = (v as? Int) ?: (v as? Long)?.toInt() ?: 0
                    if (n > 0) spMap[id] = n
                }
            }
            // 兼容旧版：通知中心未读曾存在独立 SP（notification_chat_<userId> 的 "unread" 键），一次性并入并清除
            val legacyNotif = context.getSharedPreferences("notification_chat_$userId", Context.MODE_PRIVATE).getInt("unread", 0)
            if (legacyNotif > 0) {
                spMap[com.aurora.chat.ui.chat.NOTIFICATION_CHAT_ID] =
                    (spMap[com.aurora.chat.ui.chat.NOTIFICATION_CHAT_ID] ?: 0) + legacyNotif
                context.getSharedPreferences("notification_chat_$userId", Context.MODE_PRIVATE).edit().remove("unread").apply()
            }
            val cur = _unreadCounts.value.toMutableMap()
            if (userId != loadedUserId) {
                // 切换账号：以 SP 为准，丢弃其他账号的内存增量（防止串号）
                cur.clear()
                cur.putAll(spMap)
                loadedUserId = userId
                // 关键：同时清空已读游标并重置初始化标记。
                // 否则新账号首次加载会话列表时，readUpTo 里是本账号游标为 0/上个账号残留值，
                // syncUnreadFromConversations 走增量判断（lastTime > before 恒成立，before=0）→ 每个会话都被 +1，
                // 导致"切换/切回账号出现一大堆未读"。重置后下次加载走 firstInit 的"游标=当前最新、不计未读"路径。
                readUpTo.clear()
                readUpToInitialized = false
            } else {
                // 同账号：内存优先，SP 仅补全内存中尚不存在的会话（免疫异步 apply 竞态，绝不丢增量）
                spMap.forEach { (id, n) -> if ((cur[id] ?: 0) < n) cur[id] = n }
            }
            _unreadCounts.value = cur
        } catch (_: Exception) {}
    }

    /** 增量增加某会话未读（默认 +1）。self(0)/AI(-2) 不计。同时更新内存与 SP。 */
    fun addUnread(context: Context, convId: Long, n: Int = 1) {
        if (n <= 0 || convId == 0L || convId == com.aurora.chat.ui.chat.AI_CHAT_ID) return
        val userId = com.aurora.chat.data.api.AuroraApi.currentUserId
        val cur = _unreadCounts.value.toMutableMap()
        val next = (cur[convId] ?: 0) + n
        cur[convId] = next
        _unreadCounts.value = cur
        android.util.Log.i("Unread", "addUnread convId=$convId -> count=${cur[convId]}, total=${cur.values.sum()}")
        try { unreadSp(context, userId).edit().putInt("u_$convId", next).apply() } catch (_: Exception) {}
    }

    /** 清零某会话未读（进会话即已读）。self(0)/AI(-2) 不计。 */
    fun clearUnread(context: Context, convId: Long) {
        if (convId == 0L || convId == com.aurora.chat.ui.chat.AI_CHAT_ID) return
        val userId = com.aurora.chat.data.api.AuroraApi.currentUserId
        val cur = _unreadCounts.value.toMutableMap()
        cur[convId] = 0
        _unreadCounts.value = cur
        try { unreadSp(context, userId).edit().putInt("u_$convId", 0).apply() } catch (_: Exception) {}
    }

    /** 标记某会话已读：清零红点并把"已读游标"推进到当前时刻（供 loadConversations 兜底补齐判断"此后新消息"） */
    fun markRead(context: Context, convId: Long) {
        if (convId == 0L || convId == com.aurora.chat.ui.chat.AI_CHAT_ID) return
        clearUnread(context, convId)
        readUpTo[convId] = System.currentTimeMillis() / 1000
    }

    /**
     * 用会话列表的 lastTime 对比"已读游标"，为未打开过的会话补齐未读红点。
     * 这是实时 addUnread 的兜底：即使 new_message 推送因离线/重连时序/被吞而没触发，
     * 只要聊天列表刷新过（进 tab / 下拉 / 推送触发刷新），有新消息的会话就会出红点。
     */
    private fun syncUnreadFromConversations(list: List<com.aurora.chat.data.api.ConversationInfo>) {
        val cur = _unreadCounts.value.toMutableMap()
        var changed = false
        val firstInit = !readUpToInitialized
        for (conv in list) {
            val before = readUpTo[conv.id] ?: 0L
            if (firstInit) {
                readUpTo[conv.id] = conv.lastTime   // 首次：把游标初始化为已见到的最新，不计入未读
                continue
            }
            if (conv.id == openConversationId) {
                readUpTo[conv.id] = conv.lastTime    // 正在查看的会话视作已读
                continue
            }
            if (conv.lastTime > before) {
                if ((cur[conv.id] ?: 0) == 0) {
                    cur[conv.id] = 1
                    changed = true
                }
                readUpTo[conv.id] = conv.lastTime
            }
        }
        if (firstInit) readUpToInitialized = true
        if (changed) _unreadCounts.value = cur
    }

    /** 一次性读取某会话未读数（内存优先，SP 兜底）。 */
    fun getUnread(context: Context, convId: Long): Int {
        val inMem = _unreadCounts.value[convId]
        if (inMem != null) return inMem
        val userId = com.aurora.chat.data.api.AuroraApi.currentUserId
        return try { unreadSp(context, userId).getInt("u_$convId", 0) } catch (_: Exception) { 0 }
    }

    /** 底部"聊天"标签红点总数 = 全部会话未读之和（已含通知中心，因其走同一 store） */
    fun getUnreadTotal(): Int = _unreadCounts.value.values.sum()

    /** 释放资源：取消所有协程，清空缓存（在 Application.onTerminate 或退出登录时调用） */
    fun release() {
        scope.cancel()
        backgroundScope.cancel()
        msgCache.clear()
        _unreadCounts.value = emptyMap()
        loadedUserId = -1L
        readUpTo.clear()
        readUpToInitialized = false
    }

    fun notifyNewMessage() { scope.launch { _newMessageReceived.value++ } }
    fun notifyFriendRequest() { scope.launch { _friendRequestReceived.value++ } }

    /** 置顶状态变更通知 */
    private val _pinChanged = MutableStateFlow(0)
    val pinChanged: StateFlow<Int> = _pinChanged.asStateFlow()
    fun notifyPinChanged() { scope.launch { _pinChanged.value++ } }

    /** 根据本地置顶状态排序：置顶的会话排在前面 */
    private fun sortConversationsByPin(context: android.content.Context?, list: List<ConversationInfo>): List<ConversationInfo> {
        if (context == null) return list
        val prefs = context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
        return list.sortedByDescending { conv ->
            // 群聊 id 为负数（-1000 - internalGroupId），本地存储用正数 internalGroupId 做 key
            val key = if (conv.id < 0) "pin_group_${-(conv.id + 1000)}" else "pin_${conv.id}"
            prefs.getBoolean(key, false)
        }
    }

    fun loadConversations(context: android.content.Context? = null, showLoading: Boolean = true, force: Boolean = false, onComplete: (() -> Unit)? = null) {
        // 新鲜度守卫：缓存非空且距上次成功拉取不足 30s 时（非强制刷新）直接沿用缓存，避免高频切回聊天重复网络请求
        if (!force && _conversations.value.isNotEmpty() && System.currentTimeMillis() - lastSuccessLoadTime < 30_000) {
            onComplete?.invoke()
            return
        }
        loadConversationsJob?.cancel()
        loadConversationsJob = scope.launch(Dispatchers.IO) {
            try {
            val shouldShowLoading = showLoading && _conversations.value.isEmpty()
            if (shouldShowLoading) _conversationsLoading.value = true
            val result = ChatRepository.getConversations()
            if (result.success) {
                val data = result.data ?: emptyList()
                _conversations.value = sortConversationsByPin(context, data); offlineFailStreak = 0; _isOffline.value = false
                lastSuccessLoadTime = System.currentTimeMillis()
                // 兜底补齐未读：覆盖实时推送漏触发/离线补推未达（详见 syncUnreadFromConversations）
                syncUnreadFromConversations(data)
                // 每次加载成功都保存到本地（注意去重）
                if (context != null) {
                    try {
                        val existing = LocalConversationStore.load(context)
                        val merged = (existing + data).distinctBy { it.id }
                        LocalConversationStore.save(context, merged)
                    } catch (_: Exception) {}
                }
            } else {
                if (result.message != "暂无消息") android.util.Log.w("ChatVM", "对话列表加载失败: ${result.message}")
                if (context != null) {
                    val local = LocalConversationStore.load(context)
                    if (local.isNotEmpty()) {
                        _conversations.value = sortConversationsByPin(context, local)
                        // 仅当“连续多次”拉取失败时才判定为离线，连接过程中的单次失败不显示离线横幅
                        offlineFailStreak++
                        if (offlineFailStreak >= 2) _isOffline.value = true
                    }
                }
            }
            if (shouldShowLoading) _conversationsLoading.value = false
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun loadFriendRequests(context: android.content.Context? = null) {
        scope.launch {
            _friendRequestsLoading.value = true
            val result = ChatRepository.getFriendRequests()
            if (result.success) {
                _friendRequests.value = result.data ?: emptyList(); _friendRequestReceived.value = 0
                android.util.Log.i("FriendReq", "拉取好友请求成功: ${_friendRequests.value.size} 条")
            } else {
                _friendRequests.value = emptyList()
                android.util.Log.w("FriendReq", "拉取好友请求失败: ${result.message}")
            }
            _friendRequestsLoading.value = false
        }
    }

    fun sendMessage(currentUserId: Long, friendId: Long, text: String, replyTo: Long = 0,
                    mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0,
                    replyToText: String = "", replyToSender: String = "",
                    onSuccess: (Long) -> Unit, onError: (String) -> Unit,
                    context: android.content.Context? = null) {
        scope.launch {
            val result = if (isGroupChat(friendId)) ChatRepository.sendGroupMessage(friendId, text, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender)
            else ChatRepository.sendMessage(friendId, text, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender)
            if (result.success) onSuccess(result.data ?: 0L) else onError(result.message)
        }
    }

    suspend fun getUserInfo(userId: Long): UserInfoResult {
        val info = ChatRepository.getUserInfo(userId)
        return UserInfoResult(if (info.success) info.data else null, _conversations.value)
    }

    fun clearFriendRequests() { _friendRequests.value = emptyList() }

    fun respondFriendRequest(requestId: Long, action: String, context: android.content.Context? = null, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        // 同意后仅更新本地状态，记录保留（不再删除）
        val newStatus = if (action == "accept") "accepted" else "rejected"
        _friendRequests.value = _friendRequests.value.map { if (it.id == requestId) it.copy(status = newStatus) else it }
        scope.launch {
            val result = ChatRepository.respondFriendRequest(requestId, action)
            if (!result.success) {
                // 如果失败了，重新加载列表确保数据正确
                loadFriendRequests(context)
            }
            onResult(result.success, result.message)
        }
    }

    fun cancelFriendRequest(requestId: Long, context: android.content.Context? = null, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            val result = ChatRepository.cancelFriendRequest(requestId)
            if (result.success) {
                _friendRequests.value = _friendRequests.value.filter { it.id != requestId }
            } else {
                loadFriendRequests(context)
            }
            onResult(result.success, result.message)
        }
    }

    fun removeUserData(userId: Long) {
        _conversations.update { current -> current.filter { it.id != userId } }
    }

    fun refreshGroupAvatars() { scope.launch { _groupAvatarRefreshKey.value++ } }
    fun notifySelfChatRefresh() { scope.launch { _selfChatRefreshKey.value++ } }

    /** 失效某会话的消息缓存（msgCache + 进程内热缓存），撤回后避免旧快照命中。 */
    fun invalidateHotCache(currentUserId: Long, friendId: Long) {
        msgCache.remove("$currentUserId:$friendId")
        com.aurora.chat.ui.chat.messageHotCache.remove("$currentUserId:$friendId")
    }

    /** 清空全部消息热缓存（msgCache + 进程内热缓存）。「拉取/恢复」完成后调用，使各对话重新从本地读盘显示最新数据。 */
    fun clearHotCache() {
        try { msgCache.clear() } catch (_: Exception) {}
        try { com.aurora.chat.ui.chat.messageHotCache.clear() } catch (_: Exception) {}
    }

    fun updateConversationPreview(friendId: Long, preview: String, createdAt: Long) {
        // 私聊 E2EE 密文在写入会话列表预览前统一解密（群聊不加密、占位符不受影响），
        // 避免 TCP 推送/增量刷新路径把密文直接写进预览导致"列表显示密文、进对话才是明文"
        val decrypted = if (friendId > 0 && com.aurora.chat.CryptoUtil.isEncrypted(preview)) {
            try { com.aurora.chat.CryptoUtil.decrypt(preview) }
            catch (_: Exception) { "" }
        } else preview
        _conversations.update { current ->
            current.map { if (it.id == friendId) it.copy(lastMessage = decrypted, lastTime = createdAt) else it }
        }
    }

    fun loadMessages(currentUserId: Long, friendId: Long, context: android.content.Context? = null) {
        // 整体在 IO 执行：本地读盘 + 解密、服务器拉取、消息合并映射均不应阻塞主线程
        scope.launch(Dispatchers.IO) {
            // 1) 先取本地已保存（可能含服务器已清理的 30 天前历史）
            val local = if (context != null) LocalMessageStore.loadMessages(context, friendId) else emptyList()
            // 2) 拉取服务器消息（limit=0 表示全部）
            val result = if (isGroupChat(friendId)) ChatRepository.getGroupMessages(friendId, 0, 0)
            else ChatRepository.getMessages(currentUserId, friendId, 0, 0)
            val server = if (result.success && result.data != null) result.data!! else emptyList()
            // 3) 合并：server 优先去重，local 中 server 已删除的补回
            val merged = mergeMessages(local, server)
            msgCache.put("$currentUserId:$friendId", merged.map { m ->
                ChatMsg.create(text = m.content, isMine = m.fromUserId == currentUserId,
                    fromUserId = m.fromUserId, serverId = m.id, isNew = false, senderName = m.fromUserName,
                    createdAt = m.createdAt, isRevoked = m.isRevoked,
                    isSystemNotice = m.isRevoked == 1 || m.msgType == "broadcast" || m.msgType == "system",
                    replyToText = m.replyToText,
                    replyToSender = m.replyToSender, replyToId = m.replyToId, mediaType = m.mediaType,
                    mediaUrl = m.mediaUrl, flashDuration = m.flashDuration, toUserId = m.toUserId, targetName = "",
                    broadcastTaskId = m.broadcastTaskId)
            })
            // 4) 持久化合并结果，确保本地始终完整（不被服务器清理影响）
            if (context != null) {
                LocalMessageStore.saveMessages(context, friendId, merged)
            }
        }
    }

    /** 合并本地与服务器消息：按 id 去重，server 覆盖 local，仅本地有的（已被服务器清理）保留 */
    private fun mergeMessages(local: List<MessageInfo>, server: List<MessageInfo>): List<MessageInfo> {
        if (local.isEmpty()) return server
        if (server.isEmpty()) return local
        val byId = local.associateBy { it.id }.toMutableMap()
        for (m in server) byId[m.id] = m
        return byId.values.sortedBy { it.createdAt }
    }
}

// ==================== 全局单例（兼容原有所有静态访问方式） ====================
object ChatViewModel {
    private val _delegate = ChatViewModelDelegate()

    private inline val d get() = _delegate

    val conversations: StateFlow<List<ConversationInfo>> get() = d.conversations
    val conversationsLoading: StateFlow<Boolean> get() = d.conversationsLoading
    val friendRequests: StateFlow<List<FriendRequestInfo>> get() = d.friendRequests
    val friendRequestsLoading: StateFlow<Boolean> get() = d.friendRequestsLoading
    val newMessageReceived: StateFlow<Int> get() = d.newMessageReceived
    val friendRequestReceived: StateFlow<Int> get() = d.friendRequestReceived
    val isOffline: StateFlow<Boolean> get() = d.isOffline
    val groupAvatarRefreshKey: StateFlow<Int> get() = d.groupAvatarRefreshKey
    val selfChatRefreshKey: StateFlow<Int> get() = d.selfChatRefreshKey
    val pinChanged: StateFlow<Int> get() = d.pinChanged
    val backgroundScope: CoroutineScope get() = d.backgroundScope
    val msgCache: LruCache<String, List<ChatMsg>> get() = d.msgCache
    val unreadCounts: StateFlow<Map<Long, Int>> get() = d.unreadCounts
    var openConversationId: Long
        get() = d.openConversationId
        set(v) { d.openConversationId = v }

    fun loadConversations(context: android.content.Context? = null, showLoading: Boolean = true, force: Boolean = false, onComplete: (() -> Unit)? = null) = d.loadConversations(context, showLoading, force, onComplete)
    fun loadFriendRequests(context: android.content.Context? = null) = d.loadFriendRequests(context)
    fun clearFriendRequests() = d.clearFriendRequests()
    fun respondFriendRequest(requestId: Long, action: String, context: android.content.Context? = null, onResult: (Boolean, String) -> Unit = { _, _ -> }) = d.respondFriendRequest(requestId, action, context, onResult)
    fun cancelFriendRequest(requestId: Long, context: android.content.Context? = null, onResult: (Boolean, String) -> Unit = { _, _ -> }) = d.cancelFriendRequest(requestId, context, onResult)
    fun removeUserData(userId: Long) = d.removeUserData(userId)
    fun refreshGroupAvatars() = d.refreshGroupAvatars()
    fun sendMessage(currentUserId: Long, friendId: Long, text: String, replyTo: Long = 0,
                    mediaType: String = "", mediaUrl: String = "", flashDuration: Int = 0,
                    replyToText: String = "", replyToSender: String = "",
                    onSuccess: (Long) -> Unit, onError: (String) -> Unit,
                    context: android.content.Context? = null) =
        d.sendMessage(currentUserId, friendId, text, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender, onSuccess, onError, context)
    fun updateConversationPreview(friendId: Long, preview: String, createdAt: Long) = d.updateConversationPreview(friendId, preview, createdAt)
    fun invalidateHotCache(currentUserId: Long, friendId: Long) = d.invalidateHotCache(currentUserId, friendId)
    suspend fun getUserInfo(userId: Long) = d.getUserInfo(userId)
    fun notifyNewMessage() = d.notifyNewMessage()
    fun notifySelfChatRefresh() = d.notifySelfChatRefresh()
    fun notifyFriendRequest() = d.notifyFriendRequest()
    fun notifyPinChanged() = d.notifyPinChanged()
    fun loadUnreadForUser(context: android.content.Context, userId: Long) = d.loadUnreadForUser(context, userId)
    fun clearHotCache() = d.clearHotCache()
    fun addUnread(context: android.content.Context, convId: Long, n: Int = 1) = d.addUnread(context, convId, n)
    fun clearUnread(context: android.content.Context, convId: Long) = d.clearUnread(context, convId)
    fun markRead(context: android.content.Context, convId: Long) = d.markRead(context, convId)
    fun getUnread(context: android.content.Context, convId: Long): Int = d.getUnread(context, convId)
    fun getUnreadTotal(): Int = d.getUnreadTotal()
    fun loadMessages(currentUserId: Long, friendId: Long, context: android.content.Context? = null) = d.loadMessages(currentUserId, friendId, context)
    fun release() = _delegate.release()
}
