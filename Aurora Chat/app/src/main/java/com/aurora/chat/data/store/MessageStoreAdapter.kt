package com.aurora.chat.data.store

import androidx.compose.runtime.Stable
import java.util.ArrayList
import com.aurora.chat.ui.chat.IMessageRef
import com.aurora.chat.ui.chat.isGroupConsentNotice
import com.aurora.chat.ui.chat.parseGroupConsentNotice

/**
 * ## 消息存储适配器 — 每条对话一个实例
 *
 * 职责：
 * 1. 内部持有 [MessageStore]，管理该对话的全部消息数据
 * 2. 对外提供 [StoreMessageRef] 列表（轻量索引引用），
 *    兼容现有 `itemsIndexed` UI 代码
 * 3. 支持增量更新：只追加新消息，不清空重建
 * 4. 支持 [SnapshotStateList] 级别的同步
 *
 * ### 内存对比 (1000 条消息)
 * | 方案 | 内存 | GC 触发 |
 * |------|:----:|:-------:|
 * | ChatMsg 对象 | ~250KB + String ~100KB | **频繁** |
 * | StoreMessageRef + MessageStore | 1000×24byte + 96KB 数组 = ~120KB | **几乎零** |
 */
class MessageStoreAdapter(initialCapacity: Int = 256) {

    /** 内部存储引擎。public 以允许直接数组访问。 */
    val store = MessageStore(initialCapacity)

    /** 索引列表：[SnapshotStateList] 持有的是 store 索引，而非对象。
     *  每次有消息变化时更新此列表。 */
    // 单线程访问（由 ViewModel 协程保证），用 ArrayList 避免 CopyOnWriteArrayList 的写放大
    private val _indices = ArrayList<Int>()
    val indices: List<Int> get() = _indices

    // ==================== 状态追踪 ====================
    /** 最后一条消息的 ID，用于快速检测变化 */
    var lastMessageId: Long = 0L
        private set
    /** 最后一条消息的 flags，用于检测撤回变化 */
    var lastMessageFlags: Long = 0L
        private set
    /** 对话是否已加载过初始数据 */
    var loaded: Boolean = false
        private set

    val count: Int get() = store.count
    val visibleCount: Int get() = _indices.size

    // ================================================================
    // 写入
    // ================================================================

    /**
     * 追加单条消息。返回追加后的索引。
     * 自动维护 _indices 和 lastMessage* 状态。
     */
    fun append(
        id: Long, fromUserId: Long, toUserId: Long, content: String,
        createdAt: Long, isMine: Boolean, senderName: String = "",
        targetName: String = "", isNew: Boolean = false,
        isRevoked: Boolean = false, isSystemNotice: Boolean = false,
        isUploading: Boolean = false, flashDuration: Int = 0,
        mediaType: String = "", mediaUrl: String = "",
        replyToId: Long = 0, replyToText: String = "",
        replyToSender: String = "", broadcastTaskId: Long = 0
    ): Int {
        val idx = store.append(
            id, fromUserId, toUserId, content, createdAt,
            isMine, senderName, targetName, isNew, isRevoked,
            isSystemNotice, isUploading, flashDuration, mediaType,
            mediaUrl, replyToId, replyToText, replyToSender, broadcastTaskId
        )
        _indices.add(idx)
        lastMessageId = id
        lastMessageFlags = store.flagsAt(idx)
        return idx
    }

    /**
     * 批量加载（全量初始化用）。
     * 会清空 _indices，重建索引。
     * 仅当消息数量或最后消息 ID/flags 有变化时才触发。
     *
     * @return true = 数据有变化
     */
    fun loadAll(messages: List<MessageData>): Boolean {
        // 快速检测：数量或最后一条 ID+flags 是否变了
        if (loaded && messages.size == _indices.size) {
            if (messages.isNotEmpty() && _indices.isNotEmpty()) {
                val lastIdx = _indices.last()
                val last = messages.last()
                if (last.id == store.idAt(lastIdx) &&
                    last.isRevoked == store.isRevoked(lastIdx)) {
                    // 媒体字段也必须一致，否则视为有变化——
                    // 防止旧磁盘缓存里"空的 mediaUrl"被早退优化冻结成空气泡（网络刷新被整段跳过）
                    var mediaConsistent = true
                    for (i in messages.indices) {
                        if (i >= store.count) { mediaConsistent = false; break }
                        val m = messages[i]
                        if (m.mediaType != store.mediaTypeString(i) || m.mediaUrl != store.mediaUrlAt(i)) {
                            mediaConsistent = false
                            break
                        }
                    }
                    if (mediaConsistent) return false // 无变化，跳过
                }
            }
        }

        // 重建
        // 全量来源（磁盘/网络首次）可能含重复 id，先去重再重建，
        // 避免重复 key 写进 store 后被 LazyColumn 渲染时抛 "Key was already used" 崩溃，
        // 也防止重复 id 后续被持久化回缓存越积越多。
        val uniqueMessages = if (messages.size > 1) messages.distinctBy { it.id } else messages
        store.clear()
        _indices.clear()
        store.appendAll(uniqueMessages)
        for (i in 0 until store.count) _indices.add(i)
        loaded = true
        if (_indices.isNotEmpty()) {
            val last = _indices.last()
            lastMessageId = store.idAt(last)
            lastMessageFlags = store.flagsAt(last)
        }
        return true
    }

    /**
     * 窗口化加载：把全部消息写入 store，但 _indices 只暴露最近 [windowSize] 条，
     * 其余（更旧）保留在 store 中，待滚动到顶部时由 [prependOlderFromStore] 懒加载。
     *
     * 用于「进入大群」场景：磁盘可能有几万条消息，若一次性全量建索引并同步到 SnapshotStateList，
     * 主线程会被 O(n) 占满导致卡顿。这里把 store 写入与窗口化都在后台线程完成后，
     * 主线程只需同步可见窗口，首屏只渲染近屏条数。
     *
     * @return true = 还有更旧消息未暴露（可继续上拉加载）
     */
    fun loadWindowed(messages: List<MessageData>, windowSize: Int): Boolean {
        val unique = if (messages.size > 1) messages.distinctBy { it.id } else messages
        store.clear()
        _indices.clear()
        store.appendAll(unique)
        val total = store.count
        val start = (total - windowSize).coerceAtLeast(0)
        for (i in start until total) _indices.add(i)
        loaded = true
        if (_indices.isNotEmpty()) {
            val last = _indices.last()
            lastMessageId = store.idAt(last)
            lastMessageFlags = store.flagsAt(last)
        }
        return start > 0
    }

    /** store 中是否还有未暴露给 UI 的更旧消息（用于滚动到顶时决定走本地预加载还是服务端分页）。 */
    fun hasOlderInStore(): Boolean = _indices.isNotEmpty() && _indices.first() > 0

    /**
     * 从 store 头部预加载 [batch] 条更旧消息到 _indices，返回实际预加载条数。
     * 调用方负责同步 messages 并维持滚动位置。
     */
    fun prependOlderFromStore(batch: Int): Int {
        if (_indices.isEmpty()) return 0
        val first = _indices.first()
        val newStart = (first - batch).coerceAtLeast(0)
        if (newStart == first) return 0
        val added = first - newStart
        for (i in (first - 1) downTo newStart) _indices.add(0, i)
        return added
    }

    /**
     * TCP 推送后增量更新：只处理新消息和撤回事件。
     * 不触发清空重建，只追加增量。
     *
     * @param messages 来自服务端的最新消息列表（完整）
     * @param currentUserId 当前用户 ID（用于判断撤回）
     * @param currentUserName 当前用户名（用于撤回通知文本）
     * @return 是否有新消息追加
     */
    fun mergeServerMessages(
        messages: List<MessageData>,
        currentUserId: Long,
        currentUserName: String
    ): Boolean {
        if (messages.isEmpty()) return false

        // ---- 处理撤回：扫描 messages 中已撤回的，更新本地对应 flags ----
        for (msg in messages) {
            if (!msg.isRevoked) continue
            val localIdx = store.indexOf(msg.id)
            if (localIdx >= 0 && !store.isRevoked(localIdx)) {
                store.markRevoked(localIdx)
            }
        }

        // ---- 处理新消息：只追加不在本地的 ----
        val existingIds = if (_indices.isNotEmpty()) {
            // 用最后一条消息 id 做快速检查
            val lastLocalId = store.idAt(_indices.last())
            var added = false
            // 服务端返回顺序不保证（私信为倒序、群为正序），
            // 先按 id 升序整理再追加，确保追加后仍是最新在最底部、不出现"新消息跑中间/旧消息沉底"
            val newMsgs = messages.filter { it.id > lastLocalId && it.id !in skipIds }.sortedBy { it.id }
            for (msg in newMsgs) {
                val isPoke = msg.flashDuration == -1
                val isConsentNotice = isGroupConsentNotice(msg.content)
                val isSystemNotice = msg.isRevoked || isPoke || isConsentNotice || msg.broadcastTaskId != 0L
                val text = when {
                    // 开发者广播：直接展示广播正文，不套用"撤回"等系统文案
                    msg.broadcastTaskId != 0L -> msg.content
                    isSystemNotice && isPoke -> parsePokeText(msg, currentUserId, currentUserName)
                    // 群邀请结果：保留原始文本，由渲染层按查看者身份个性化
                    isConsentNotice -> msg.content
                    isSystemNotice -> {
                        val sender = if (msg.fromUserId == currentUserId) currentUserName else msg.senderName
                        "$sender 撤回了一条消息"
                    }
                    else -> msg.content
                }
                append(
                    id = msg.id, fromUserId = msg.fromUserId, toUserId = msg.toUserId,
                    content = text, createdAt = msg.createdAt, isMine = msg.fromUserId == currentUserId,
                    senderName = msg.senderName,
                    isRevoked = msg.isRevoked, isSystemNotice = isSystemNotice,
                    replyToId = msg.replyToId, replyToText = msg.replyToText,
                    replyToSender = msg.replyToSender, mediaType = msg.mediaType,
                    mediaUrl = msg.mediaUrl, flashDuration = msg.flashDuration,
                    broadcastTaskId = msg.broadcastTaskId
                )
                added = true
            }
            return added
        } else {
            // 首次加载
            return loadAll(messages)
        }
    }

    /** 本地的、尚未同步到服务端的消息 ID（用于增量合并时跳过）。 */
    private val skipIds = mutableSetOf<Long>()

    /**
     * 追加本地消息（未从服务端获取 ID）。
     * 本地消息 ID 为负值或 >= 10000，与服务端消息区分。
     */
    fun appendLocal(
        localId: Long, text: String, fromUserId: Long, toUserId: Long,
        isMine: Boolean, mediaType: String = "", mediaUrl: String = "",
        flashDuration: Int = 0
    ): Int {
        skipIds.add(localId)
        return append(
            id = localId, fromUserId = fromUserId, toUserId = toUserId,
            content = text, createdAt = System.currentTimeMillis() / 1000,
            isMine = isMine, isUploading = true,
            mediaType = mediaType, mediaUrl = mediaUrl, flashDuration = flashDuration
        )
    }

    /**
     * 追加系统通知消息。
     */
    fun appendSystemNotice(text: String): Int {
        return append(
            id = System.nanoTime() and Long.MAX_VALUE,
            fromUserId = 0, toUserId = 0, content = text,
            createdAt = System.currentTimeMillis() / 1000,
            isMine = false, isSystemNotice = true
        )
    }

    /** 将本地消息的 ID 替换为服务端 ID（发送成功回调时调用）。 */
    fun replaceLocalId(localId: Long, serverId: Long) {
        skipIds.remove(localId)
        val idx = store.indexOf(localId)
        if (idx >= 0) store.updateId(idx, serverId)
    }

    /** 标记消息为上传完成。 */
    fun markUploaded(localId: Long, serverId: Long) {
        val idx = store.indexOf(localId)
        if (idx >= 0) {
            store.markUploading(idx, false)
            store.updateId(idx, serverId)
        }
    }

    /** 标记消息为已撤回。 */
    fun markRevoked(messageId: Long, senderName: String, currentUserName: String) {
        val idx = store.indexOf(messageId)
        if (idx >= 0) {
            store.markRevoked(idx)
        }
    }

    // ================================================================
    // 读取
    // ================================================================

    /** 获取第 k 条可见消息的引用。越界时返回安全空引用（兜底，避免任何误用导致崩溃）。 */
    fun refAt(visibleIndex: Int): StoreMessageRef {
        if (visibleIndex < 0 || visibleIndex >= _indices.size) {
            return StoreMessageRef(store, (store.count - 1).coerceAtLeast(0))
        }
        val storeIdx = _indices[visibleIndex]
        if (storeIdx < 0 || storeIdx >= store.count) {
            return StoreMessageRef(store, (store.count - 1).coerceAtLeast(0))
        }
        return StoreMessageRef(store, storeIdx)
    }

    /** 全部消息的引用（含未暴露的更旧窗口），用于落盘时全量保存，避免窗口化后只存近屏丢历史。 */
    fun allRefs(): List<StoreMessageRef> = (0 until store.count).map { StoreMessageRef(store, it) }

    /** 清空所有数据。 */
    fun clear() {
        store.clear()
        _indices.clear()
        skipIds.clear()
        loaded = false
        lastMessageId = 0L
        lastMessageFlags = 0L
    }

    /**
     * 按条件静默移除消息（用于广播的删除单个 / 撤回全部）。不追加"撤回"系统通知。
     * 注意：必须同时移除 store 中的真实数据并重建 _indices，
     * 否则 store 中的残留条目会随 allRefs() 落盘"复活"。
     */
    fun removeByPredicate(predicate: (StoreMessageRef) -> Boolean) {
        // 收集命中的 store 绝对下标（_indices 存的就是 store 下标）
        val toRemove = _indices.filter { predicate(StoreMessageRef(store, it)) }.sortedDescending()
        for (storeIdx in toRemove) store.removeAt(storeIdx)
        // 删除后剩余 store 条目必然连续，重建可见索引；同时清掉失效的 last*
        _indices.clear()
        for (i in 0 until store.count) _indices.add(i)
        skipIds.removeAll { id -> (0 until store.count).none { store.idAt(it) == id } }
        if (_indices.isNotEmpty()) {
            val last = _indices.last()
            lastMessageId = store.idAt(last)
            lastMessageFlags = store.flagsAt(last)
        } else {
            lastMessageId = 0L
            lastMessageFlags = 0L
        }
    }

    // ==================== 内部工具 ====================

    private fun parsePokeText(msg: MessageData, currentUserId: Long, currentUserName: String): String {
        return if (msg.fromUserId == currentUserId)
            "你拍了拍${msg.targetName.ifEmpty { msg.senderName }}"
        else
            "${msg.senderName}拍了拍${if (msg.toUserId == currentUserId) "你" else msg.targetName}"
    }
}

/**
 * ## 轻量消息引用 — 替代 ChatMsg 对象
 *
 * 不持有任何 String 数据，所有属性从 [MessageStore] 的原始数组读取。
 *
 * 内存占用：2 个引用 + 1 个 int = **~24 bytes**
 * 对比 ChatMsg：**~200-300 bytes** + 多个 String
 *
 * 使用 `@Stable` 标记确保 Compose 编译器信任其相等性，
 * 避免因 data class copy 导致的不必要重组。
 */
@Stable
class StoreMessageRef(
    internal val store: MessageStore,
    internal val index: Int
) : IMessageRef {
    override val id: Long get() = store.idAt(index)
    override val text: String get() = store.contentAt(index)
    override val isMine: Boolean get() = store.isMine(index)
    override val fromUserId: Long get() = store.fromUidAt(index)
    override val senderName: String get() = store.senderNameAt(index)
    override val time: Long get() = System.currentTimeMillis()
    override val isNew: Boolean get() = store.isNew(index)
    override val createdAt: Long get() = store.timestampAt(index)
    override val isRevoked: Int get() = if (store.isRevoked(index)) 1 else 0
    override val isSystemNotice: Boolean get() = store.isSystemNotice(index)
    override val replyToText: String get() = store.replyToTextAt(index)
    override val replyToSender: String get() = store.replyToSenderAt(index)
    override val replyToId: Long get() = store.replyToIdAt(index)
    override val mediaType: String get() = store.mediaTypeString(index)
    override val mediaUrl: String get() = store.mediaUrlAt(index)
    override val isUploading: Boolean get() = store.isUploading(index)
    override val toUserId: Long get() = store.toUidAt(index)
    override val targetName: String get() = store.targetNameAt(index)
    override val flashDuration: Int get() = store.flashDuration(index)
    override val broadcastTaskId: Long get() = store.broadcastTaskIdAt(index)

    /** 系统通知是否因撤回。 */
    val isRecallNotice: Boolean get() = isSystemNotice && isRevoked == 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is StoreMessageRef && other.store === store && other.index == index
    }

    override fun hashCode(): Int = index
}
