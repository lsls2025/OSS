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

// ==================== 开发者撤回窥探缓存 ====================
// 记录「被撤回消息 id → 撤回前的原始文本」，仅内存（进程重启清空）。
// 撤回后 store 的正文会被占位文案「X 撤回了一条消息」覆盖，原内容在此缓存，
// 供对话内「X 撤回了一条消息」允许开发者点击回显原文（再点一次恢复占位）。
internal val recallOriginalCache = mutableMapOf<Long, String>()

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

    /** 最近一次加载/发消息拿到的 Context，供 ensureConversation 等无 context 入参的写者落盘本地缓存 */
    private var cachedContext: android.content.Context? = null

    /** 后台 IO 协程（上传等持续任务） */
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 文本消息瞬时失败的重试间隔（毫秒）：长度即重试次数（这里 4 次 + 首次 = 5 次尝试）。 */
    private val SEND_RETRY_DELAYS_MS = longArrayOf(100L, 200L, 300L, 400L)

    /** 重试总窗口（毫秒）：从第一次发送开始算，超过这个时间就立刻给红叹号，绝不让用户干等。 */
    private val SEND_RETRY_WINDOW_MS = 2_000L

    // ==================== 网络状态 ====================
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()
    // ==================== 对话列表 ====================
    private val _conversations = MutableStateFlow<List<ConversationInfo>>(emptyList())
    val conversations: StateFlow<List<ConversationInfo>> = _conversations.asStateFlow()

    // ==================== 已隐藏的会话（仅从聊天列表移除，不删联系人或消息） ====================
    // 按当前用户隔离，持久化到 SP；发消息/收消息后该会话会重新出现在列表。
    private val _hiddenConversations = MutableStateFlow<Set<Long>>(emptySet())
    val hiddenConversations: StateFlow<Set<Long>> = _hiddenConversations.asStateFlow()

    private fun hiddenSp(context: Context) =
        context.getSharedPreferences("aurora_hidden_conversations", Context.MODE_PRIVATE)

    /** 载入当前用户的隐藏会话集合（切换账号时调用，避免串号）。 */
    fun loadHiddenConversations(context: Context) {
        val userId = com.aurora.chat.data.api.AuroraApi.currentUserId
        val raw = hiddenSp(context).getStringSet("hidden_$userId", emptySet()) ?: emptySet()
        _hiddenConversations.value = raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    /** 隐藏某会话：仅从聊天列表移除，保留联系人/群与消息历史。 */
    fun hideConversation(context: Context, convId: Long) {
        val userId = com.aurora.chat.data.api.AuroraApi.currentUserId
        val cur = _hiddenConversations.value.toMutableSet()
        cur.add(convId)
        _hiddenConversations.value = cur
        try {
            hiddenSp(context).edit().putStringSet("hidden_$userId", cur.map { it.toString() }.toSet()).apply()
        } catch (_: Exception) {}
    }

    /** 取消隐藏：发消息/收消息后该会话会重新出现在列表。 */
    fun unhideConversation(context: Context, convId: Long) {
        val userId = com.aurora.chat.data.api.AuroraApi.currentUserId
        val cur = _hiddenConversations.value.toMutableSet()
        if (!cur.remove(convId)) return
        _hiddenConversations.value = cur
        try {
            hiddenSp(context).edit().putStringSet("hidden_$userId", cur.map { it.toString() }.toSet()).apply()
        } catch (_: Exception) {}
    }
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
    // 隐私设置实时变更信号（对方开关禁止截图/录屏/退出时，TCP privacy_alert 推送触发）
    private val _privacyRefreshKey = MutableStateFlow(0)
    val privacyRefreshKey: StateFlow<Int> = _privacyRefreshKey.asStateFlow()

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
     * 未读表的写锁。
     *
     * 未读表是「读-改-写」的 Map：TCP 线程的 addUnread（每条消息 +1）、
     * 列表刷新的 syncUnreadFromConversations、进 tab 的 loadUnreadForUser、markRead/markAllRead
     * 都可能并发。旧实现各自拷贝一份 Map 再整体写回，后写者会用旧快照覆盖别人的增量，
     * 表现就是「第一条 1、第二条还是 1」。所有写入必须串行化。
     */
    private val unreadLock = Any()

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
            synchronized(unreadLock) {
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
            }
        } catch (_: Exception) {}
    }

    /**
     * 不计未读的会话：AI 多会话、通知中心，以及「个人对话」（服务端自聊，id = 自己 userId）。
     * 自聊的消息是自己发的，不该给自己点红点；若不豁免，syncUnreadFromConversations 会因
     * 服务端 lastTime 推进反复写 1，形成永远清不掉的幽灵红点。
     */
    private fun isUnreadExempt(convId: Long): Boolean =
        convId == com.aurora.chat.ui.chat.AI_CHAT_ID ||
            convId == com.aurora.chat.ui.chat.NOTIFICATION_CHAT_ID ||
            (convId > 0L && convId == com.aurora.chat.data.api.AuroraApi.currentUserId)

    /** 增量增加某会话未读（默认 +1）。AI(-2)/个人对话(自聊) 不计。同时更新内存与 SP。 */
    fun addUnread(context: Context, convId: Long, n: Int = 1) {
        if (n <= 0 || isUnreadExempt(convId)) return
        // 收到新消息即视为该会话重新活跃：若此前被「删除会话」隐藏，解除隐藏让其重新出现在列表。
        // 设计本意就是"删除仅是隐藏，重新收发消息后应恢复显示"；原先只实现了"自己发消息"时的 unhide，
        // 漏了"收到对方消息"这条路径，导致 B 被 A 发消息后列表里仍不显示 A（红点有、列表没有）。
        unhideConversation(context, convId)
        val userId = com.aurora.chat.data.api.AuroraApi.currentUserId
        val next: Int
        val total: Int
        synchronized(unreadLock) {
            val cur = _unreadCounts.value.toMutableMap()
            next = (cur[convId] ?: 0) + n
            cur[convId] = next
            _unreadCounts.value = cur
            total = cur.values.sum()
        }
        android.util.Log.i("Unread", "addUnread convId=$convId -> count=$next, total=$total")
        try { unreadSp(context, userId).edit().putInt("u_$convId", next).apply() } catch (_: Exception) {}
    }

    /** 清零某会话未读（进会话即已读）。AI/个人对话(自聊) 不计。 */
    fun clearUnread(context: Context, convId: Long) {
        if (isUnreadExempt(convId)) return
        val userId = com.aurora.chat.data.api.AuroraApi.currentUserId
        synchronized(unreadLock) {
            val cur = _unreadCounts.value.toMutableMap()
            cur[convId] = 0
            _unreadCounts.value = cur
        }
        try { unreadSp(context, userId).edit().putInt("u_$convId", 0).apply() } catch (_: Exception) {}
    }

    /** 标记某会话已读：清零红点并把"已读游标"推进到当前时刻（供 loadConversations 兜底补齐判断"此后新消息"） */
    fun markRead(context: Context, convId: Long) {
        if (isUnreadExempt(convId)) return
        clearUnread(context, convId)
        // 关键修复（幽灵红点「1」根因）：已读游标不再用设备时钟覆盖。原先 readUpTo = 设备时钟秒；
        // 而 syncUnreadFromConversations 用服务端 lastTime（MAX(created_at)）判定"有新消息"。一旦设备时钟落后于
        // 服务端，markRead 会把游标从服务端时间「倒退」回设备时间，下次 loadConversations 又判定有新消息 →
        // 用硬编码 1 重新点亮，永远清不掉、且数字恒为 1。这里取三者最大值，保证游标单调不回退。
        val serverTime = _conversations.value.firstOrNull { it.id == convId }?.lastTime ?: 0L
        val deviceTime = System.currentTimeMillis() / 1000
        val next: Long
        synchronized(unreadLock) {
            val prev = readUpTo[convId] ?: 0L
            next = maxOf(prev, serverTime, deviceTime)
            readUpTo[convId] = next
        }
        android.util.Log.i("Unread", "markRead convId=$convId -> readUpTo=$next, serverTime=$serverTime, deviceTime=$deviceTime")
    }

    /** 全部已读：清掉底部聊天 Tab 的全部红点，并把每个会话的已读游标推进到其服务端最新时间（避免被重新点亮）。 */
    fun markAllRead(context: Context) {
        if (com.aurora.chat.data.api.AuroraApi.currentUserId <= 0L) return
        synchronized(unreadLock) {
            val cur = _unreadCounts.value.toMutableMap()
            cur.keys.forEach { id ->
                if (!isUnreadExempt(id)) cur[id] = 0
            }
            _unreadCounts.value = cur
            // 已读游标推进到各会话最新服务端时间，确保后续 loadConversations 不会用硬编码 1 重新点亮
            _conversations.value.forEach { conv ->
                if (!isUnreadExempt(conv.id)) {
                    val prev = readUpTo[conv.id] ?: 0L
                    readUpTo[conv.id] = if (conv.lastTime > prev) conv.lastTime else prev
                }
            }
        }
        // 同步清本地 SP，避免持久化层与内存不一致
        try {
            val sp = unreadSp(context, com.aurora.chat.data.api.AuroraApi.currentUserId)
            sp.all.keys.filter { it.startsWith("u_") }.forEach { sp.edit().remove(it).apply() }
        } catch (_: Exception) {}
        android.util.Log.i("Unread", "markAllRead -> total=${getUnreadTotal()}")
    }

    /** 清理幽灵未读：移除被 clearUnread/markRead/addUnread 守卫拒绝、永远清不掉的 key（AI/通知/个人对话）。
     *  其余「隐形 key」（非好友/已注销/已退群会话）风险低，且若按会话列表/本地缓存兜底清理会误删排名靠后的合法未读，
     *  故不做范围性清理，专治「守卫不对称导致的恒为 1」。 */
    private fun purgeGhostUnread() {
        synchronized(unreadLock) {
            val cur = _unreadCounts.value.toMutableMap()
            var changed = false
            for (id in cur.keys.toSet()) {
                if (isUnreadExempt(id)) {
                    cur.remove(id); changed = true
                }
            }
            if (changed) _unreadCounts.value = cur
        }
    }

    /**
     * 用会话列表的 lastTime 对比"已读游标"，为未打开过的会话补齐未读红点。
     * 这是实时 addUnread 的兜底：即使 new_message 推送因离线/重连时序/被吞而没触发，
     * 只要聊天列表刷新过（进 tab / 下拉 / 推送触发刷新），有新消息的会话就会出红点。
     */
    private fun syncUnreadFromConversations(list: List<com.aurora.chat.data.api.ConversationInfo>) {
        synchronized(unreadLock) {
            val cur = _unreadCounts.value.toMutableMap()
            var changed = false
            val firstInit = !readUpToInitialized
            for (conv in list) {
                // 守卫：AI/通知/个人对话(自聊) 这类会话由 clearUnread/markRead/addUnread 拒绝，
                // 若此处不拦截会被写入且永远清不掉，形成幽灵红点。
                if (isUnreadExempt(conv.id)) continue
                val before = readUpTo[conv.id] ?: 0L
                if (firstInit) {
                    readUpTo[conv.id] = conv.lastTime   // 首次：把游标初始化为已见到的最新，不计入未读
                    continue
                }
                if (conv.id == openConversationId) {
                    readUpTo[conv.id] = conv.lastTime    // 正在查看的会话视作已读
                    cur[conv.id] = 0                     // 同时清掉红点，避免仍残留 1
                    changed = true
                    continue
                }
                if (conv.lastTime > before) {
                    if ((cur[conv.id] ?: 0) == 0) {
                        cur[conv.id] = 1
                        changed = true
                    }
                    // 游标单调推进：取 max(旧值, 服务端最新)，避免被旧值/乱序覆盖后漏判
                    val prev = readUpTo[conv.id] ?: 0L
                    readUpTo[conv.id] = if (conv.lastTime > prev) conv.lastTime else prev
                }
            }
            if (firstInit) readUpToInitialized = true
            if (changed) _unreadCounts.value = cur
        }
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

    /**
     * 把「我已经被移出 / 已退出」的群重新拼回会话列表。
     *
     * 服务端的群会话是按成员身份返回的（`getGroupsForUser`），一旦被踢，这个群立刻从群列表消失，
     * 客户端表现出来就是**会话凭空蒸发**：没有提示、没有解释，历史也再也点不进去。
     * QQ/微信不是这样——会话留着，变成只读，最后一条是「XX 将你踢出群聊」。
     *
     * 所以这里用本地退出记录（[com.aurora.chat.data.local.GroupExitStore]）把它补回来：
     * 预览固定为退出提示（退出后不可能再有新消息，所以它一定是最新一条）。
     *
     * @param serverAuthoritative 只有「服务端列表刚成功返回」时才为 true：
     *        此时群重新出现在列表里 → 说明又进群了，顺手删掉退出记录让会话恢复正常。
     *        离线/缓存分支不能删，否则断网时会把退出态误判成"已恢复"。
     */
    private fun mergeExitedGroups(
        context: android.content.Context?,
        list: List<ConversationInfo>,
        serverAuthoritative: Boolean
    ): List<ConversationInfo> {
        if (context == null) return list
        val exits = runCatching {
            com.aurora.chat.data.local.GroupExitStore.all(context)
        }.getOrDefault(emptyList())
        if (exits.isEmpty()) return list
        val byConv = exits.associateBy { it.convId }
        val out = ArrayList<ConversationInfo>(list.size + exits.size)
        for (c in list) {
            val e = byConv[c.id]
            if (e == null) {
                out.add(c)
                continue
            }
            if (serverAuthoritative) {
                // 服务端又返回了这个群 = 已重新入群，退出记录作废
                runCatching { com.aurora.chat.data.local.GroupExitStore.remove(context, e.groupId) }
                out.add(c)
            } else {
                out.add(c.copy(lastMessage = e.text, lastTime = e.time))
            }
        }
        val present = list.mapTo(HashSet()) { it.id }
        for (e in exits) {
            if (present.contains(e.convId)) continue
            out.add(
                ConversationInfo(
                    id = e.convId,
                    email = "group_${e.groupId}@aurora.chat",
                    username = e.groupName.ifBlank { "群聊" },
                    createdAt = e.time,
                    lastMessage = e.text,
                    lastTime = e.time
                )
            )
        }
        return out
    }

    /**
     * 兜底补记：服务端列表里已经消失、但本地确实有聊天记录的群 → 记为「你已被踢出群聊」。
     *
     * 为什么需要这一层：被踢的定向推送走的是服务端**内存**队列，服务重启或长期离线会丢。
     * 丢了之后用户看到的仍然只是「群会话凭空消失」，而这正是要修的现象。
     * 这里用「本地有历史 + 服务端群列表里没有」把退出态补出来：虽然不知道是谁踢的，
     * 但成因只有「被移出」这一种，文案就按被踢写，不再退化成含糊的「你已不在该群」。
     *
     * 只在服务端**成功**返回列表时调用；离线/缓存分支绝不能调，否则会把"还没拉到"误判成"已经不在群里"。
     */
    private suspend fun recordVanishedGroups(
        context: android.content.Context?,
        serverList: List<ConversationInfo>,
        localCache: List<ConversationInfo>
    ) {
        if (context == null || localCache.isEmpty()) return
        // 保守开关：服务端这次一个群都没返回时，不做任何推断。
        // 否则「群列表查询失败 / 服务端异常」会被当成「所有群都没了」，
        // 一次性把所有群标成只读——这个错误代价太大，宁可漏判（推送通道才是主路径）。
        val serverHasGroups = serverList.any { it.id <= -1001L }
        if (!serverHasGroups) return
        val serverIds = serverList.mapTo(HashSet()) { it.id }
        val nowSec = System.currentTimeMillis() / 1000
        for (c in localCache) {
            // 只看群会话：私聊 id > 0，AI / 通知中心是固定负 id，都不是"群"
            if (c.id >= 0L) continue
            if (c.id == com.aurora.chat.ui.chat.AI_CHAT_ID) continue
            if (c.id == com.aurora.chat.ui.chat.NOTIFICATION_CHAT_ID) continue
            if (serverIds.contains(c.id)) continue
            // 只处理"最近还活跃"的群：老用户可能在本地躺着几十个早就退掉的群，
            // 全捞回列表会变成另一种噪音。30 天内说过话的才算「这次真的被踢了」。
            if (c.lastTime <= 0L || nowSec - c.lastTime > 30L * 24 * 3600) continue
            val gid = -(c.id + 1000)
            if (gid <= 0L) continue
            if (com.aurora.chat.data.local.GroupExitStore.get(context, gid) != null) continue
            // 本地没有消息的群不记：可能是很久以前点进去过一次的空壳，记了反而误伤
            val hasHistory = try {
                LocalMessageStore.loadRecentMessages(context, c.id, 1).messages.isNotEmpty()
            } catch (_: Exception) {
                false
            }
            if (!hasHistory) continue
            com.aurora.chat.data.local.GroupExitStore.record(
                context,
                com.aurora.chat.data.local.GroupExit(
                    groupId = gid,
                    groupName = c.username,
                    operatorName = "",
                    time = if (c.lastTime > 0L) c.lastTime else System.currentTimeMillis() / 1000,
                    reason = com.aurora.chat.data.local.GroupExit.REASON_KICKED
                )
            )
        }
    }

    /**
     * 用本地消息库回填会话列表预览：服务端对私聊的 last_message 因 E2EE 故意留空，
     * 离线时本地缓存也没有预览 → 列表一片空白，违背"尽量本地化"的意愿。
     * 这里对 lastMessage 为空的会话，从本地消息库取最后一条消息补全预览
     * （含撤回/拍一拍/媒体占位），保证断网也能看到"最后聊了什么"。
     */
    private suspend fun enrichConversationsFromLocal(
        context: android.content.Context,
        list: List<com.aurora.chat.data.api.ConversationInfo>
    ): List<com.aurora.chat.data.api.ConversationInfo> {
        if (list.isEmpty()) return list
        val curUid = com.aurora.chat.data.api.AuroraApi.currentUserId
        val curName = com.aurora.chat.data.api.AuroraApi.currentUserName
        // 本地清理过的会话：服务端仍会返回旧的 last_message 预览，先抹掉再让本地库补全。
        // 本地库已被清空 → 预览保持空白；清理之后来了新消息 → 下面用本地库把新消息补成预览。
        // 不这样做的话，聊天记录是没了，列表上却还挂着清理前的最后一句话，看起来像没清干净。
        val clearStore = com.aurora.chat.data.local.LocalClearStore
        val out = list.map { c ->
            if (clearStore.isCleared(context, c.id) && c.lastMessage.isNotEmpty()) {
                c.copy(lastMessage = "")
            } else c
        }.toMutableList()
        for (i in out.indices) {
            val c = out[i]
            // 三种「服务端预览不可用」的情况，一律用本地消息库补全：
            //  1) 空串：群聊媒体消息 content 为空、私聊因 E2EE 服务端故意留空；
            //  2) 残留 "POKE"：拍一拍已用 flash_duration=-1 表达，旧标记会显示成乱码；
            //  3) E2EE 密文：服务端把私聊密文直接当预览返回时，列表会显示一串 base64。
            val lm = c.lastMessage
            // 旧服务端 / 旧磁盘缓存可能把语音的 content（时长秒数）直接当预览返回。
            // 群聊预览带「发送者: 」前缀，取冒号后的尾段再判。这里只标记「可疑」，
            // 是否覆盖要等本地库确认最后一条确实是语音，避免把用户真发的纯数字文本（如 "2024"）误伤。
            val lmTail = lm.substringAfterLast(": ").trim()
            val maybeVoiceDuration = lmTail.isNotEmpty() && lmTail.length <= 4 && lmTail.all { it.isDigit() }
            val unusable = lm.isEmpty() ||
                lm.trim().equals("POKE", ignoreCase = true) ||
                com.aurora.chat.CryptoUtil.isEncrypted(lm) ||
                // 4) "[消息]"：服务端认不出类型时给的最低信息量占位。
                //    本地按 URL 后缀往往能算出真类型（视频/图片/文件），没道理把占位留在列表上。
                lm.trim() == "[消息]"
            if (!unusable && !maybeVoiceDuration) continue
            // 只跳过预览来源不是消息库的两个特殊会话（AI / 通知中心）。
            // 不能直接用 isUnreadExempt：它把「个人对话（id == currentUserId）」也算作不计未读，
            // 但个人对话的预览完全来自本地消息库——跟着跳过的话，服务端预览不可用时
            // 刷新后列表预览就是空白（发消息当下正常、一下拉刷新就没了）。
            if (c.id == com.aurora.chat.ui.chat.AI_CHAT_ID ||
                c.id == com.aurora.chat.ui.chat.NOTIFICATION_CHAT_ID
            ) continue
            try {
                // 只为拿「最后一条」做会话列表预览：取最近几条即可，
                // 不要 loadMessages 全量（那是 O(整个会话) 的全量解密，列表里几十个会话就是几十次）
                val msgs = LocalMessageStore.loadRecentMessages(context, c.id, 8).messages
                if (msgs.isEmpty()) continue
                val last = msgs.lastOrNull { it.isRevoked == 0 } ?: msgs.last()
                // 可疑裸数字：只有本地最后一条真是语音才用本地结果覆盖；否则保留服务端原文。
                if (maybeVoiceDuration && !unusable &&
                    com.aurora.chat.ui.chat.normalizeMediaType(last.mediaType, last.mediaUrl, last.id) != "voice") continue
                val preview = buildConvPreviewFromMsg(last, curUid, curName)
                if (preview.isNotEmpty()) out[i] = c.copy(lastMessage = preview, lastTime = last.createdAt)
            } catch (e: Exception) {
                // 协程被取消（离开列表 / 作用域取消 / 刷新中断）属于正常生命周期，直接传播出去，
                // 不要记成「预览补全失败」——否则日志里一堆 JobCancellationException 误报，掩盖真问题。
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 真正的异常（DB 读失败等）才记日志：下次出现空白能直接看出是 DB 异常还是真的没数据。
                com.aurora.chat.ErrorReporter.debug(
                    "CONV_PREVIEW",
                    "会话 ${c.id} 预览补全失败: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        return out
    }

    /** 由一条消息推导会话列表预览文本（与 ChatConversationScreen 内逻辑保持一致）。 */
    private fun buildConvPreviewFromMsg(
        msg: com.aurora.chat.data.api.MessageInfo,
        curUid: Long,
        curName: String
    ): String {
        return when {
            msg.isRevoked != 0 -> {
                val sender = if (msg.fromUserId == curUid) curName else msg.fromUserName
                if (sender.isNotEmpty()) "$sender 撤回了一条消息" else "撤回了一条消息"
            }
            msg.flashDuration == -1 || msg.content.trim().equals("POKE", ignoreCase = true) ->
                com.aurora.chat.ui.chat.parsePokeText(msg, curUid, curName)
            // 群敏感词处置提示：按查看者补人称（本人「你因…」/其他人「某某 因…」）
            com.aurora.chat.ui.chat.isSensitivePunishText(msg.content) ->
                com.aurora.chat.ui.chat.parseSensitivePunishText(msg.content, msg.targetName, curName)
            msg.content.startsWith("清空聊天记录请求") -> "清空聊天记录请求"
            msg.content.startsWith("清空聊天记录\n") -> "清空聊天记录(已拒绝)"
            // 群公告卡片消息：原始内容以「系统公告数据」前缀开头，列表预览显示「群公告：正文」
            com.aurora.chat.ui.chat.isGroupAnnouncementMessage(msg.content) ->
                com.aurora.chat.ui.chat.normalizeGroupAnnouncementPreview(msg.content)
            // 语音消息的 content 存的是「时长秒数」（如 "2"），不是给人看的正文。
            // 必须排在 content.isNotEmpty() 之前：否则本地库回填 / 服务端回包会把会话列表
            // 预览写成裸数字「2」，把发送成功那一刻写入的「[语音]」覆盖掉。
            com.aurora.chat.ui.chat.normalizeMediaType(msg.mediaType, msg.mediaUrl, msg.id) == "voice" -> "[语音]"
            // 聊天记录分享卡片：正文是「分享聊天\n[分享卡片]\nmsgs=<Base64>」，
            // 不能原样当预览（会显示一长串载荷或空白），统一显示「[聊天记录]」。
            // 方括号必须保留：列表预览按方括号包住才和发送当下（[语音]/[图片]）一致，
            // 漏掉方括号刷新后就成了裸的「聊天记录」四个字。
            com.aurora.chat.ui.chat.isShareCardMessage(msg.content) -> "[聊天记录]"
            msg.content.isNotEmpty() -> {
                // 私聊 E2EE 密文在展示为会话列表预览前解密，与对话内明文保持一致
                if (msg.fromUserId > 0 && com.aurora.chat.CryptoUtil.isEncrypted(msg.content)) {
                    try { com.aurora.chat.CryptoUtil.decrypt(msg.content) } catch (_: Exception) { "[端到端加密消息]" }
                } else msg.content
            }
            else -> when (
                // 与对话内共用同一个判定入口（本机记录 > URL 后缀 > 服务端字段）。
                // 原来这里是手写的 when(mediaType)，规则和对话内的归一化不一致，
                // 才会出现「同一个视频：进对话是视频、列表预览变 [消息]」。收敛后两边不可能再分叉。
                com.aurora.chat.ui.chat.normalizeMediaType(msg.mediaType, msg.mediaUrl, msg.id)
            ) {
                "video" -> "[视频]"
                "image" -> "[图片]"
                "voice" -> "[语音]"
                "file" -> "[文件]"
                "sticker" -> "[表情]"
                else -> "[消息]"
            }
        }
    }

    fun loadConversations(context: android.content.Context? = null, showLoading: Boolean = true, force: Boolean = false, onComplete: (() -> Unit)? = null) {
        cachedContext = context
        // 先载入当前用户隐藏的会话集合（必须在新鲜度守卫之前，否则缓存命中时不会刷新隐藏状态）
        if (context != null) loadHiddenConversations(context)
        // 新鲜度守卫：缓存非空且距上次成功拉取不足 30s 时（非强制刷新）直接沿用缓存，避免高频切回聊天重复网络请求
        if (!force && _conversations.value.isNotEmpty() && System.currentTimeMillis() - lastSuccessLoadTime < 30_000) {
            onComplete?.invoke()
            return
        }
        loadConversationsJob?.cancel()
        loadConversationsJob = scope.launch(Dispatchers.IO) {
            try {
            // 首屏铺底：进入聊天 Tab 且内存列表仍为空时，先在 IO 线程把磁盘缓存铺设出来，
            // 让进入后**立即**可见「上一次磁盘里已有的会话列表」，
            // 而非只有 3 个固定特殊会话（个人对话 / AI / 通知中心，由 UI 层追加）。
            //
            // 关键：铺底之后必须**立刻**用本地消息库补一次预览，不能等网络。
            // 原来这里只铺磁盘原文，注释写「预览稍后由网络/离线分支补全」——但离线/弱网时
            // HTTP 要等到超时才进失败分支，这段时间列表预览全是空白，表现就是
            // 「没网时预览解析不出来，刷新一下才有」。本地读库是毫秒级的，没理由等网络。
            if (context != null && !force && _conversations.value.isEmpty()) {
                val cached = runCatching { LocalConversationStore.load(context) }.getOrDefault(emptyList())
                if (cached.isNotEmpty()) {
                    // 先把本地退出过的群拼回来（冷启动时服务端还没返回，这一步是唯一的信息来源）
                    val cachedWithExits = mergeExitedGroups(context, cached, serverAuthoritative = false)
                    _conversations.value = sortConversationsByPin(context, cachedWithExits)
                    val enrichedCache = enrichConversationsFromLocal(context, cachedWithExits)
                    if (enrichedCache != cachedWithExits) {
                        _conversations.value = sortConversationsByPin(context, enrichedCache)
                        // 补全结果回写磁盘：否则下次冷启动首帧拿到的仍是旧的空预览，
                        // 每次都要靠「再补一遍」才显示，正是「进二级页返回后又消失」的来源之一。
                        runCatching { LocalConversationStore.save(context, enrichedCache) }
                    }
                }
            }
            // 有本地缓存铺底（或内存已有）时静默刷新，不显示「加载中」；
            // 连本地缓存都没有时才进入加载态（此时界面仅显示 3 个固定特殊会话）。
            val shouldShowLoading = showLoading && _conversations.value.isEmpty()
            if (shouldShowLoading) _conversationsLoading.value = true
            // force=true（下拉刷新 / 新消息到达）时绕过 HTTP 磁盘缓存，确保拉到最新数据
            val result = ChatRepository.getConversations(forceRefresh = force)
            if (result.success) {
                val data = result.data ?: emptyList()
                // 群公告卡片消息的原始内容以「系统公告数据」前缀开头。这里必须先归一化服务端预览，
                // 否则 enrichConversationsFromLocal 因 lastMessage 非空直接跳过，列表会一直显示原始 JSON 串。
                // 注意：服务端群聊预览还会带「发送者: 」前缀，必须用容忍前缀的归一化（见 helper 注释）。
                val normalizedData = data.map { conv ->
                    val norm = com.aurora.chat.ui.chat.normalizeGroupAnnouncementPreview(conv.lastMessage)
                    if (norm != conv.lastMessage) conv.copy(lastMessage = norm) else conv
                }
                // 用本地消息库补全会话预览（服务端私聊预览因 E2EE 留空；离线也能显示最后聊了什么）
                val enriched = if (context != null) enrichConversationsFromLocal(context, normalizedData) else normalizedData
                // 服务端权威返回：先补记「服务端已没有、本地还在」的群（推送丢失时的兜底），
                // 再把被移出/已退出的群拼回列表；某个群重新出现 → 说明已重新入群，顺手清掉退出记录。
                if (context != null) {
                    val cachedConvs = runCatching { LocalConversationStore.load(context) }.getOrDefault(emptyList())
                    recordVanishedGroups(context, normalizedData, cachedConvs)
                }
                val mergedList = mergeExitedGroups(context, enriched, serverAuthoritative = true)
                _conversations.value = sortConversationsByPin(context, mergedList); offlineFailStreak = 0; _isOffline.value = false
                lastSuccessLoadTime = System.currentTimeMillis()
                // 兜底补齐未读：覆盖实时推送漏触发/离线补推未达（详见 syncUnreadFromConversations）
                syncUnreadFromConversations(data)
                // 清理幽灵未读：移除 0/-2 等被守卫拒绝、永远清不掉的 key（避免恒为 1 的红点）
                purgeGhostUnread()
                // 每次加载成功都保存到本地（注意去重）
                if (context != null) {
					try {
						val existing = LocalConversationStore.load(context)
						// 以服务端 enriched 为准（服务端权威）；local 仅补充服务端未返回的会话。
						// 原先 existing 在前会因本地缓存里残留的旧空群名等覆盖服务端正确数据，
						// 导致官方群名修复后仍显示空白（被旧缓存覆盖）。
						// 用 mergedList（含"被移出/已退出"的群）而不是 enriched：否则被踢的群
						// 每次成功刷新都会被写盘逻辑抹掉，冷启动又消失一次。
						val merged = (mergedList + existing).distinctBy { it.id }
						LocalConversationStore.save(context, merged)
					} catch (_: Exception) {}
                }
            } else {
                if (result.message != "暂无消息") android.util.Log.w("ChatVM", "对话列表加载失败: ${result.message}")
                if (context != null) {
                    val local = LocalConversationStore.load(context)
                    if (local.isNotEmpty()) {
                        // 离线时同样用本地消息库补全预览，避免列表一片空白
                        val localEnriched = enrichConversationsFromLocal(context, local)
                        // 再套一层「退出过的群」：顺序不能反——enrich 可能用最后一条真实消息
                        // 覆盖掉预览，而退出提示必须是这个会话的最后一条。
                        val withExits = mergeExitedGroups(context, localEnriched, serverAuthoritative = false)
                        _conversations.value = sortConversationsByPin(context, withExits)
                        // 补全后回写磁盘。原来只更新内存，磁盘里始终是那份空预览，
                        // 于是每次冷启动首帧又回到空白，得再补一次才显示——「不稳定」的直接来源。
                        if (withExits != local) {
                            runCatching { LocalConversationStore.save(context, withExits) }
                        }
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
                    contactSource: String = "",
                    onSuccess: (Long) -> Unit, onError: (String) -> Unit,
                    context: android.content.Context? = null) {
        // 群聊：发送瞬间把正文里的 @昵称 固化成「昵称 → 用户ID」随消息一起发出去。
        // 昵称会随改名变化，ID 不会 —— 否则对方改名后，历史消息里的 @ 就再也定位不到人。
        val mentions = if (isGroupChat(friendId)) com.aurora.chat.data.store.parseMentionsFromText(text) {
            com.aurora.chat.data.store.MentionDirectory.idOf(friendId, it)
        } else emptyMap()
        scope.launch {
            // 网络抖动时的自动重试，但总窗口硬性不超过 2 秒：
            // 一次失败 → 100ms → 200ms → 300ms → 400ms（共 5 次尝试，约 1 秒）。
            // 2 秒内还没成功就立刻转红叹号，用户点一下就能重发；
            // 绝不为了"多试几次"让消息在十几秒后才突然出现（那种延迟比失败更难受）。
            var lastMessage = "发送失败"
            val startedAt = System.currentTimeMillis()
            for (attempt in 0..SEND_RETRY_DELAYS_MS.size) {
                if (attempt > 0) {
                    val remain = SEND_RETRY_WINDOW_MS - (System.currentTimeMillis() - startedAt)
                    if (remain <= 0L) break
                    delay(minOf(SEND_RETRY_DELAYS_MS[attempt - 1], remain))
                }
                val result = if (isGroupChat(friendId))
                    ChatRepository.sendGroupMessage(friendId, text, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender, mentions = mentions)
                else
                    ChatRepository.sendMessage(friendId, text, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender, contactSource, mentions = mentions)
                if (result.success) {
                    context?.let { unhideConversation(it, friendId) }
                    onSuccess(result.data ?: 0L)
                    return@launch
                }
                lastMessage = result.message
                // 终态业务失败（拉黑/禁言/账号异常/超限…）：服务端已明确拒收，重试没有意义，立刻交给 UI 处理
                if (com.aurora.chat.ui.chat.classifySendFailure(lastMessage) != null) break
            }
            onError(lastMessage)
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
            } else if (action == "accept") {
                // 刚同意对方的好友申请 = 好友证据：立刻解除「非好友标记」并写下粘性好友证据，
                // 否则离线状态下进这个会话还会挂着陈旧的横幅。
                val fromUid = _friendRequests.value.find { it.id == requestId }?.fromUserId ?: 0L
                if (fromUid > 0L) {
                    ChatRepository.clearStrangerMark(fromUid)
                    ChatRepository.markAsFriend(fromUid)
                }
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
    /** 通知当前会话重新读取对方隐私设置并应用/清除 FLAG_SECURE */
    fun notifyPrivacyChanged() { scope.launch { _privacyRefreshKey.value++ } }

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
        // 群公告卡片消息：列表预览统一改写为「群公告：正文」（容忍服务端「发送者: 」前缀，见 helper 注释）
        val normalized = com.aurora.chat.ui.chat.normalizeGroupAnnouncementPreview(preview)
        // 私聊 E2EE 密文在写入会话列表预览前统一解密（群聊不加密、占位符不受影响），
        // 避免 TCP 推送/增量刷新路径把密文直接写进预览导致"列表显示密文、进对话才是明文"
        val decrypted = if (friendId > 0 && com.aurora.chat.CryptoUtil.isEncrypted(normalized)) {
            try { com.aurora.chat.CryptoUtil.decrypt(normalized) }
            catch (_: Exception) { "" }
        } else normalized
        _conversations.update { current ->
            current.map { if (it.id == friendId) it.copy(lastMessage = decrypted, lastTime = createdAt) else it }
        }
    }

    /**
     * 发送首条消息给"列表里还没有"的新联系人时，updateConversationPreview 只更新已存在项、不会新增，
     * 导致对方迟迟不出现在消息列表。这里改为 upsert：已存在则刷新预览（含 E2EE 解密），
     * 不存在则新增并置顶，保证"发完消息联系人立即出现在消息列表"（含持久化由后续 loadConversations 兜底）。
     */
    fun ensureConversation(friendId: Long, username: String, preview: String, createdAt: Long) {
        val decrypted = if (friendId > 0 && com.aurora.chat.CryptoUtil.isEncrypted(preview)) {
            try { com.aurora.chat.CryptoUtil.decrypt(preview) }
            catch (_: Exception) { "" }
        } else preview
        _conversations.update { current ->
            val existing = current.firstOrNull { it.id == friendId }
            val list = if (existing != null) {
                current.map { if (it.id == friendId) it.copy(lastMessage = decrypted, lastTime = createdAt) else it }
            } else {
                current + ConversationInfo(
                    id = friendId, email = "", username = username,
                    createdAt = createdAt, lastMessage = decrypted, lastTime = createdAt
                )
            }
            list.sortedByDescending { it.lastTime }
        }
        // 立即把新会话落盘，保证“给对方发消息”后即便断网/重启，对方也仍在列表里
        // （不再只依赖后续 loadConversations 兜底——那样一旦刷新时服务端尚未同步就会把本地会话丢掉）
        if (cachedContext != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val existing = LocalConversationStore.load(cachedContext!!)
                    // 顺序必须是「内存在前」：distinctBy 只保留第一次出现的同 id 项。
                    // 原来写成 (existing + _conversations.value)，磁盘里的旧数据（预览可能是空的）
                    // 会盖掉刚写进内存的正确预览，再被下次 loadConversations 读回来——
                    // 这就是「发完消息预览正常，进二级页再出来又没了」的机制。
                    LocalConversationStore.save(cachedContext!!, (_conversations.value + existing).distinctBy { it.id })
                } catch (_: Exception) {}
            }
        }
    }

    fun loadMessages(currentUserId: Long, friendId: Long, context: android.content.Context? = null) {
        cachedContext = context
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
                    // 分类走唯一入口：撤回 / msgType / 广播任务 / 拍一拍(-1) / 群邀请协议正文。
                    // 不再只按 msgType 判断 —— 拍一拍与广播任务此前都靠别的字段，靠 msgType 一套会漏。
                    isSystemNotice = com.aurora.chat.ui.chat.isSystemNoticeMessage(
                        isRevoked = m.isRevoked == 1,
                        msgType = m.msgType,
                        broadcastTaskId = m.broadcastTaskId,
                        flashDuration = m.flashDuration,
                        content = m.content
                    ),
                    replyToText = m.replyToText,
                    replyToSender = m.replyToSender, replyToId = m.replyToId, mediaType = m.mediaType,
                    mediaUrl = m.mediaUrl, flashDuration = m.flashDuration, toUserId = m.toUserId, targetName = m.targetName,
                    broadcastTaskId = m.broadcastTaskId)
            })
            // 4) 持久化合并结果，确保本地始终完整（不被服务器清理影响）
            if (context != null) {
                LocalMessageStore.saveMessages(context, friendId, merged)
            }
        }
    }

    /**
     * 合并本地与服务器消息：按 id 去重，server 覆盖 local，仅本地有的（已被服务器清理）保留。
     *
     * 【顺序铁律】返回值永远升序（旧→新），任何分支都不许直接透传服务端原始顺序。
     * 历史坑：这里原本有 `if (local.isEmpty()) return server`，服务端一旦返回倒序，
     * 首次进入会话（无本地缓存）就会把整列颠倒，并且以倒序写进 LocalMessageStore，
     * 传染下一次冷启动——表现为「旧消息堆在最上面、新消息沉到下面」。
     */
    private fun mergeMessages(local: List<MessageInfo>, server: List<MessageInfo>): List<MessageInfo> {
        if (local.isEmpty() && server.isEmpty()) return emptyList()
        val byId = LinkedHashMap<Long, MessageInfo>(local.size + server.size)
        for (m in local) byId[m.id] = m
        for (m in server) byId[m.id] = m
        // createdAt 为主序，同秒用 id 兜底（服务端 id 单调递增，天然等于时间序），
        // 规避 sortedBy(createdAt) 在「同一秒多条」时保持服务端 DESC 原序导致的局部颠倒。
        //
        // 时间戳缺失（<=0，仅可能是旧数据/异常行）按「最新」处理，绝不能当「最旧」排到头部：
        // 排到头部就等于把这条消息显示在会话最上方 —— 新发的图片因此"跑到顶部"的直接成因之一。
        // 这些行之间仍按 id 升序，彼此相对顺序不变。
        return byId.values.sortedWith(
            compareBy({ if (it.createdAt <= 0L) Long.MAX_VALUE else it.createdAt }, { it.id })
        )
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
    val privacyRefreshKey: StateFlow<Int> get() = d.privacyRefreshKey
    val pinChanged: StateFlow<Int> get() = d.pinChanged
    val hiddenConversations: StateFlow<Set<Long>> get() = d.hiddenConversations
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
                    contactSource: String = "",
                    onSuccess: (Long) -> Unit, onError: (String) -> Unit,
                    context: android.content.Context? = null) =
        d.sendMessage(currentUserId, friendId, text, replyTo, mediaType, mediaUrl, flashDuration, replyToText, replyToSender, contactSource, onSuccess, onError, context)
    fun updateConversationPreview(friendId: Long, preview: String, createdAt: Long) = d.updateConversationPreview(friendId, preview, createdAt)
    fun ensureConversation(friendId: Long, username: String, preview: String, createdAt: Long) = d.ensureConversation(friendId, username, preview, createdAt)
    fun invalidateHotCache(currentUserId: Long, friendId: Long) = d.invalidateHotCache(currentUserId, friendId)
    suspend fun getUserInfo(userId: Long) = d.getUserInfo(userId)
    fun notifyNewMessage() = d.notifyNewMessage()
    fun notifySelfChatRefresh() = d.notifySelfChatRefresh()
    fun notifyPrivacyChanged() = d.notifyPrivacyChanged()
    fun notifyFriendRequest() = d.notifyFriendRequest()
    fun notifyPinChanged() = d.notifyPinChanged()
    fun hideConversation(context: android.content.Context, convId: Long) = d.hideConversation(context, convId)
    fun unhideConversation(context: android.content.Context, convId: Long) = d.unhideConversation(context, convId)
    fun loadUnreadForUser(context: android.content.Context, userId: Long) = d.loadUnreadForUser(context, userId)
    fun clearHotCache() = d.clearHotCache()
    fun addUnread(context: android.content.Context, convId: Long, n: Int = 1) = d.addUnread(context, convId, n)
    fun clearUnread(context: android.content.Context, convId: Long) = d.clearUnread(context, convId)
    fun markRead(context: android.content.Context, convId: Long) = d.markRead(context, convId)
    fun markAllRead(context: android.content.Context) = d.markAllRead(context)
    fun getUnread(context: android.content.Context, convId: Long): Int = d.getUnread(context, convId)
    fun getUnreadTotal(): Int = d.getUnreadTotal()
    fun loadMessages(currentUserId: Long, friendId: Long, context: android.content.Context? = null) = d.loadMessages(currentUserId, friendId, context)
    fun release() = _delegate.release()
}
