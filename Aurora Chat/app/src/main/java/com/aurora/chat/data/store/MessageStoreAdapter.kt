package com.aurora.chat.data.store

import androidx.compose.runtime.Stable
import java.util.ArrayList
import com.aurora.chat.ui.chat.IMessageRef
import com.aurora.chat.ui.chat.isGroupConsentNotice
import com.aurora.chat.ui.chat.isSystemNoticeMessage
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

    /**
     * 可见窗口硬上限（0 = 不限制，兼容旧行为）。
     *
     * 为什么会存在：[_indices] 与 UI 的 SnapshotStateList 是同一条消息的两种表示，
     * 都是 O(可见条数)。一旦「上滑加载」无上限地增长，UI 列表、组合、布局测量全部线性变慢，
     * 几万条时进会话就是几百毫秒到秒级卡顿。这里给可见窗口一个恒定上限：
     * 超过就从「离视口最远的那一端」丢弃可见索引，消息本体仍完整留在 [store] 里，
     * 反向滑动时由 [prependOlderFromStore] / [appendNewerFromStore] 以 O(batch) 零 I/O 补回。
     * 即：内存与渲染成本恒定，滑动方向对称，任一方向都不会「到头就没」。
     */
    var windowCap: Int = 0
        private set

    /**
     * 可见窗口是否已与「最新消息」脱钩（上滑时把底部丢弃过）。
     *
     * 为 true 时新到达的消息只写入 [store]、不进 [_indices]：
     * 否则用户正在读顶部历史，一条新消息的窗口化插入会把顶部挤掉一屏，等于强行抢走阅读位置。
     * 等用户下滑到窗口底部（[appendNewerFromStore] 追平 [store] 末尾）或主动回底（[reattachTail]）时自动复位。
     */
    var tailDetached: Boolean = false
        private set

    fun setWindowCap(cap: Int) { windowCap = if (cap > 0) cap else 0 }

    val count: Int get() = store.count
    val visibleCount: Int get() = _indices.size

    /** 当前可见窗口在 store 中的范围（[0] = 最旧可见，[1] = 最新可见，含端点）。空窗口返回 -1..-1。 */
    fun visibleRange(): IntRange =
        if (_indices.isEmpty()) IntRange.EMPTY else _indices.first().._indices.last()

    // ================================================================
    // 写入
    // ================================================================

    /**
     * 追加单条消息。返回追加后的索引。
     * 自动维护 _indices 和 lastMessage* 状态。
     */
    /**
     * 顺序兜底：确保消息按「旧 → 新」排列，作为 adapter 的最后一道防线。
     *
     * 为什么必须在这里兜底：UI 是 reverseLayout + asReversed 渲染，对 store 内顺序极度敏感。
     * 上游（磁盘缓存 / 服务端 / 合并逻辑）一旦给倒序，[loadWindowed] 会取末尾的
     * 「最旧 N 条」当首屏，视觉上就是整列颠倒、旧消息堆在最上面。
     *
     * 刻意做成「先线性预检、只在真乱序时才排序」：
     * - 正常路径：一次 O(n) 扫描、零分配，直接返回原列表（[loadAll] 的无变化早退仍然有效）；
     * - 异常路径：才付一次排序成本（进会话 1~2 次、且跑在 Dispatchers.Default 上，可忽略）。
     *
     * 排序键只用 id、不用 createdAt，原因有两个：
     * 1) 服务端 id 单调递增，天然等于写入时序，是比 createdAt 更可靠的顺序信号；
     * 2) createdAt 存在历史脏数据（早期 ChatMsg.create 默认 0）。若拿 createdAt 当主键，
     *    这些 0 值消息会被钉到列表最顶部——正好复现「旧消息跑到最上面」这个 bug 本身。
     */
    private fun ensureChronological(src: List<MessageData>): List<MessageData> {
        if (src.size < 2) return src
        for (i in 1 until src.size) {
            if (src[i - 1].id > src[i].id) return src.sortedBy { it.id }
        }
        return src
    }

    fun append(
        id: Long, fromUserId: Long, toUserId: Long, content: String,
        createdAt: Long, isMine: Boolean, senderName: String = "",
        targetName: String = "", isNew: Boolean = false,
        isRevoked: Boolean = false, isSystemNotice: Boolean = false,
        isUploading: Boolean = false, flashDuration: Int = 0,
        mediaType: String = "", mediaUrl: String = "",
        replyToId: Long = 0, replyToText: String = "",
        replyToSender: String = "", broadcastTaskId: Long = 0, contactSource: String = "",
        localTimeMs: Long = 0
    ): Int {
        val idx = store.append(
            id, fromUserId, toUserId, content, createdAt,
            isMine, senderName, targetName, isNew, isRevoked,
            isSystemNotice, isUploading, flashDuration, mediaType,
            mediaUrl, replyToId, replyToText, replyToSender, broadcastTaskId, contactSource,
            localTimeMs
        )
        // 窗口已与最新脱钩（用户正在看老历史）：只入 store，不进可见窗口，
        // 否则窗口化插入会把用户正在读的那一屏顶掉。用户自己发的消息例外——必须立刻可见，
        // 此时走 reattachTail()，它会把窗口一路补到 store 末尾（已包含刚追加的这一条），
        // 所以这里不能再 add 一次，否则同一条会重复出现在窗口里。
        if (tailDetached && _indices.isNotEmpty()) {
            if (isMine) reattachTail()
        } else {
            _indices.add(idx)
            enforceCap(keepBottom = true)
        }
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
        // 先去重再整理顺序：全量来源（磁盘/网络首次）可能含重复 id，
        // 去重放在排序前，既能保留「原顺序第一条」的既有语义，也让后续排序的 n 更小。
        val unique = if (messages.size > 1) messages.distinctBy { it.id } else messages
        // 强制升序：所有入口（磁盘缓存 / 服务端 / 合并结果）统一在这里归位，
        // 上游顺序错乱不会再传导到 UI（详见 ensureChronological 注释）。
        val ordered = ensureChronological(unique)
        // 快速检测：数量或最后一条 ID+flags 是否变了
        if (loaded && ordered.size == _indices.size) {
            if (ordered.isNotEmpty() && _indices.isNotEmpty()) {
                val lastIdx = _indices.last()
                val last = ordered.last()
                if (last.id == store.idAt(lastIdx) &&
                    last.isRevoked == store.isRevoked(lastIdx)) {
                    // 媒体字段也必须一致，否则视为有变化——
                    // 防止旧磁盘缓存里"空的 mediaUrl"被早退优化冻结成空气泡（网络刷新被整段跳过）
                    // 分类位同理：历史上被误判成广播的消息，若这里只比 id/撤回/媒体，
                    // 会一直命中「无变化」早退，服务端的正确分类永远写不进来。
                    var consistent = true
                    for (i in ordered.indices) {
                        if (i >= store.count) { consistent = false; break }
                        val m = ordered[i]
                        if (m.mediaType != store.mediaTypeString(i) || m.mediaUrl != store.mediaUrlAt(i) ||
                            m.isSystemNotice != store.isSystemNotice(i)) {
                            consistent = false
                            break
                        }
                    }
                    if (consistent) return false // 无变化，跳过
                }
            }
        }

        // 重建（去重已在入口完成，这里直接写入有序数据）
        // 先记录旧 store 的本机毫秒时间：重建后同一 id 的消息继续沿用首次落地时刻，
        // 避免每次全量重建都把 time 刷成当下时刻（否则「上传耗时/延迟」随进入次数递增）。
        val oldLocalTimes = if (store.count > 0) {
            (0 until store.count).associate { store.idAt(it) to store.localTimeMsAt(it) }
        } else emptyMap()
        val effective = ordered.map { m ->
            if (m.localTimeMs != 0L) m
            else oldLocalTimes[m.id]?.let { m.copy(localTimeMs = it) }
                ?: m.copy(localTimeMs = System.currentTimeMillis())
        }
        store.clear()
        _indices.clear()
        store.appendAll(effective)
        for (i in 0 until store.count) _indices.add(i)
        loaded = true
        tailDetached = false
        refreshLastFromStore()
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
        // 强制升序：窗口取的是「末尾最近 N 条」，若输入是倒序，会取到最旧的 N 条当首屏，
        // 直接表现为「一进会话满屏都是很久以前的旧消息」。这是必须的兜底，不可省略。
        // 同样先去重再整理，保留「原顺序第一条」的既有去重语义。
        val deduped = if (messages.size > 1) messages.distinctBy { it.id } else messages
        val unique = ensureChronological(deduped)
        // 保留旧 store 的本机毫秒时间（同 loadAll：防止每次窗口化重建把 time 刷成当下时刻）
        val oldLocalTimes = if (store.count > 0) {
            (0 until store.count).associate { store.idAt(it) to store.localTimeMsAt(it) }
        } else emptyMap()
        val effective = unique.map { m ->
            if (m.localTimeMs != 0L) m
            else oldLocalTimes[m.id]?.let { m.copy(localTimeMs = it) }
                ?: m.copy(localTimeMs = System.currentTimeMillis())
        }
        store.clear()
        _indices.clear()
        store.appendAll(effective)
        val total = store.count
        val start = (total - windowSize).coerceAtLeast(0)
        for (i in start until total) _indices.add(i)
        loaded = true
        tailDetached = false
        refreshLastFromStore()
        return start > 0
    }

    /** store 中是否还有未暴露给 UI 的更旧消息（用于滚动到顶时决定走本地预加载还是服务端分页）。 */
    fun hasOlderInStore(): Boolean = _indices.isNotEmpty() && _indices.first() > 0

    /**
     * store 中「服务端消息」（正 id）的条数，用作服务端分页的 offset。
     *
     * 必须独立统计、不能用可见窗口条数：窗口有上限、还会上下滑动，拿它当 offset 会在
     * 窗口触顶后反复请求同一页（被去重逻辑判成「没有更多」而提前停止上拉）。
     * 负 id 是本地乐观/本地系统消息，服务端不认识，必须排除，否则 offset 会偏大跳消息。
     */
    fun serverCount(): Int {
        var n = 0
        for (i in 0 until store.count) if (store.idAt(i) > 0L) n++
        return n
    }

    /**
     * 把一批「比 store 首条更旧」的消息并入 store，但**不扩大可见窗口**（返回并入条数）。
     *
     * 与 [prependOlderFromServer] 的唯一区别就在「不扩窗口」：
     * 后者用于用户上滑翻页——新页必须立刻出现在屏幕上；而这里是「跳转到很久以前的消息」时
     * 用来填内存与目标之间的空洞——中间几万条不需要一次全渲染，只要它们进 store、
     * 下标连续，之后 [windowAroundId] 把窗口搬到目标附近即可。
     * 若沿用后者，[page] 每批都会塞进窗口，跳一次就要往 SnapshotStateList 里写几千条，必卡。
     *
     * 要求 [page] 严格早于当前首条、且页内升序；调用方按游标分批传入。
     */
    fun stashOlder(page: List<MessageData>): Int {
        if (page.isEmpty()) return 0
        val ordered = ensureChronological(page)
        val shift = ordered.size
        store.prepend(ordered)
        // 老条目绝对下标整体右移，窗口位置与内容不变
        for (i in _indices.indices) _indices[i] = _indices[i] + shift
        if (_indices.isNotEmpty()) tailDetached = _indices.last() < store.count - 1
        return shift
    }

    /**
     * 把可见窗口重设为「以 id 为中心」的一段（前 [before] 条 + 后 [after] 条），
     * 返回该 id 在窗口内的位置（0..visibleCount-1）；store 里没有这个 id 时返回 -1。
     *
     * 跳转专用：目标可能离原窗口很远，靠 [prependOlderFromStore] 一条条挪窗口太慢，
     * 这里直接换窗口区间，O(窗口大小)。
     */
    fun windowAroundId(id: Long, before: Int, after: Int): Int {
        val idx = store.indexOf(id)
        if (idx < 0) return -1
        val start = (idx - before).coerceAtLeast(0)
        val end = (idx + after).coerceAtMost(store.count - 1)
        _indices.clear()
        for (i in start..end) _indices.add(i)
        // 目标之后若还有更新的消息没进窗口，说明窗口与尾部脱钩：新消息只进 store 不抢视口，
        // 等用户下滑到窗口底部时由 appendNewerFromStore 接回（与上滑翻页的行为一致）。
        tailDetached = _indices.last() < store.count - 1
        return idx - start
    }

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
        // 上限压缩：用户正在看顶部，从底部（较新一端）丢弃，视口不受影响；
        // 丢掉的那些仍在 store，下滑时 appendNewerFromStore 立刻补回。
        enforceCap(keepBottom = false)
        tailDetached = _indices.last() < store.count - 1
        return added
    }

    /**
     * 下滑时把「窗口下方仍留在 store 里」的较新消息补回可见窗口，返回实际补回条数。
     * 与 [prependOlderFromStore] 完全对称：零 I/O、零解析，只是把窗口范围往后推。
     */
    fun appendNewerFromStore(batch: Int): Int {
        if (_indices.isEmpty()) return 0
        val cur = _indices.last()
        val lastRow = store.count - 1
        if (cur >= lastRow) return 0
        val end = (cur + batch).coerceAtMost(lastRow)
        for (i in (cur + 1)..end) _indices.add(i)
        val added = end - cur
        // 用户正在看底部，上限压缩从顶部（更旧一端）丢弃
        enforceCap(keepBottom = true)
        tailDetached = _indices.last() < store.count - 1
        return added
    }

    /** store 里是否还有「比当前窗口底部更新」的消息（上滑时被丢弃的那些）。 */
    fun hasNewerInStore(): Boolean = _indices.isNotEmpty() && _indices.last() < store.count - 1

    /**
     * 强制把可见窗口重新接到最新消息（发送消息 / 点「回到底部」时用）。
     * 会把窗口一路补到 store 末尾，并按上限从顶部裁掉多余部分。返回净增条数。
     */
    fun reattachTail(): Int {
        if (_indices.isEmpty()) return 0
        val before = _indices.size
        val cur = _indices.last()
        for (i in (cur + 1) until store.count) _indices.add(i)
        tailDetached = false
        enforceCap(keepBottom = true)
        return _indices.size - before
    }

    /**
     * 重进会话时把窗口复位到「最近 [size] 条」，store 不动。
     * 这样每次进入都是固定成本的一屏，内存不会随使用时长膨胀；更旧的在 store 里，上滑零 I/O 补回。
     */
    fun resetWindowToTail(size: Int): Boolean {
        _indices.clear()
        tailDetached = false
        if (store.count == 0) return false
        val n = size.coerceAtLeast(1)
        val start = (store.count - n).coerceAtLeast(0)
        for (i in start until store.count) _indices.add(i)
        return start > 0
    }

    /** 把可见窗口压回 [windowCap]。keepBottom=true 从顶部丢（用户在看底部），false 从底部丢（用户在看顶部）。 */
    private fun enforceCap(keepBottom: Boolean) {
        val cap = windowCap
        if (cap <= 0) return
        var overflow = _indices.size - cap
        if (overflow <= 0) return
        while (overflow-- > 0 && _indices.size > MIN_VISIBLE) {
            if (keepBottom) _indices.removeAt(0) else _indices.removeAt(_indices.size - 1)
        }
    }

    /** 无论如何都不把窗口裁到比这更小：太小会让 LazyColumn 一屏都填不满，触发来回抖动。 */
    private val MIN_VISIBLE = 24

    private fun refreshLastFromStore() {
        if (store.count > 0) {
            val i = store.count - 1
            lastMessageId = store.idAt(i)
            lastMessageFlags = store.flagsAt(i)
        } else {
            lastMessageId = 0L
            lastMessageFlags = 0L
        }
    }

    /**
     * 从服务端分页拉取的更旧消息插入到 store 与 _indices 最前部（与本地预加载同一数据通道，
     * 保证 messages 永远只是 _indices 的投影，任何 syncMessages() 都不会把上滑加载的历史截断丢掉）。
     * 返回实际插入条数。
     */
    fun prependOlderFromServer(page: List<MessageData>): Int {
        if (page.isEmpty()) return 0
        // 服务端分页同样强制升序：这一页会被整体插到 store 最前面，
        // 若页内是倒序，上滑加载出来的历史本身就是「新旧交错」的，越翻越乱。
        // 注意这里只排页内、不动整表：整表重排会打乱 _indices 的增量语义与滚动位置。
        val orderedPage = ensureChronological(page)
        val shift = orderedPage.size
        store.prepend(orderedPage)
        // 现有 _indices 绝对下标整体 +shift；新页绝对下标 [0, shift) 插到最前
        for (i in _indices.indices) _indices[i] = _indices[i] + shift
        for (k in (shift - 1) downTo 0) _indices.add(0, k)
        enforceCap(keepBottom = false)
        tailDetached = _indices.last() < store.count - 1
        refreshLastFromStore()
        return shift
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
        // 判定依据必须是「store 里有没有数据」，不能看可见窗口：窗口可能已与最新脱钩、甚至被裁到很小，
        // 用窗口判空会把已有历史当成「首次加载」继续走 loadAll 全量重建。
        val existingIds = if (store.count > 0) {
            // 用「最后一条服务端消息 id」做快速检查。
            //
            // 不能直接取 store 末尾：末尾可能是一条尚未确认的本地乐观消息（id 为负数），
            // 此时服务端所有正数 id 都会满足 it.id > lastLocalId，导致整批历史被重复 append，
            // store 里出现同一 id 的两份；displayMessages 按 id 去重只保留先出现的那份，
            // 于是「磁盘里的旧分类」会盖掉「服务端刚给的干净分类」——
            // 表现就是隔一段时间同步一次后，某些消息突然换了渲染样式。
            val lastServerId = run {
                // 直接扫 store 尾部：窗口尾部可能已经被「上滑丢底部」裁掉，
                // 拿 _indices.last() 当游标会把已存在的服务端消息当新消息重复追加。
                var i = store.count - 1
                while (i >= 0) {
                    val v = store.idAt(i)
                    if (v > 0L) return@run v
                    i--
                }
                0L
            }
            var added = false
            // 服务端返回顺序不保证（私信为倒序、群为正序），
            // 先按 id 升序整理再追加，确保追加后仍是最新在最底部、不出现"新消息跑中间/旧消息沉底"
            val newMsgs = messages.filter { it.id > lastServerId && it.id !in skipIds }.sortedBy { it.id }
            for (msg in newMsgs) {
                val isPoke = msg.flashDuration == -1 || msg.content.trim().equals("POKE", ignoreCase = true)
                val isConsentNotice = isGroupConsentNotice(msg.content)
                // 撤回判定只看 isRevoked；isSystemNotice 表示"居中广播"（含撤回/拍一拍/入群通知/开发者广播，
                // 以及服务端 msg_type=system 的隐私告警）。二者解耦，避免把隐私告警误套"撤回了一条消息"文案。
                val isRecall = msg.isRevoked
                // 复核：MessageData 不带 msgType（上游已折算进 isSystemNotice），
                // 这里用统一函数补算结构条件，再 OR 上游布尔（本地系统消息没有结构化字段，只能靠它）。
                // 不再单独手写 isPoke / isConsentNotice 分支，避免再次出现「各路径一套规则」。
                val isSystemNotice = isSystemNoticeMessage(
                    isRevoked = msg.isRevoked,
                    msgType = "",
                    broadcastTaskId = msg.broadcastTaskId,
                    flashDuration = msg.flashDuration,
                    content = msg.content
                ) || msg.isSystemNotice
                val text = when {
                    // 开发者广播：直接展示广播正文，不套用"撤回"等系统文案
                    msg.broadcastTaskId != 0L -> msg.content
                    isPoke -> parsePokeText(msg, currentUserId, currentUserName)
                    // 群邀请结果：保留原始文本，由渲染层按查看者身份个性化
                    isConsentNotice -> msg.content
                    // 群敏感词处置提示：按查看者补人称（本人「你因…」/其他人「某某 因…」）
                    com.aurora.chat.ui.chat.isSensitivePunishText(msg.content) ->
                        com.aurora.chat.ui.chat.parseSensitivePunishText(msg.content, msg.targetName, currentUserName)
                    isRecall -> {
                        // 开发者撤回窥探：缓存撤回前的原文（store 正文随后会被占位文案覆盖）
                        com.aurora.chat.ui.viewmodel.recallOriginalCache[msg.id] = msg.content
                        val sender = if (msg.fromUserId == currentUserId) currentUserName else msg.senderName
                        // 群聊撤回提示遵循该群的「群通知方式」设置：
                        //  - 关闭「消息撤回」→ 不产生提示（渲染层跳过空文本）
                        //  - 撤回提示不带回 @：它陈述的是「谁做了撤回」这个事实，
                        //    不是对成员的呼叫（@ 昔年 会让人以为被提及），QQ/微信都不带
                        val gid = if (msg.toUserId < 0) -msg.toUserId - 1000 else 0L
                        val prefs = if (gid > 0) com.aurora.chat.data.local.GroupNotifyPrefs.get(gid)
                        else com.aurora.chat.data.local.GroupNotifySettings.DEFAULT
                        when {
                            gid > 0 && !prefs.enabled(com.aurora.chat.data.local.GroupNotices.KEY_RECALL) -> ""
                            else -> "$sender 撤回了一条消息"
                        }
                    }
                    // 其余（含 msg_type=system 的隐私告警）：直接展示服务端文案，居中广播
                    else -> msg.content
                }
                append(
                    id = msg.id, fromUserId = msg.fromUserId, toUserId = msg.toUserId,
                    content = text, createdAt = msg.createdAt, isMine = msg.fromUserId == currentUserId,
                    senderName = msg.senderName, targetName = msg.targetName,
                    isRevoked = msg.isRevoked, isSystemNotice = isSystemNotice,
                    replyToId = msg.replyToId, replyToText = msg.replyToText,
                    replyToSender = msg.replyToSender, mediaType = msg.mediaType,
                    mediaUrl = msg.mediaUrl, flashDuration = msg.flashDuration,
                    broadcastTaskId = msg.broadcastTaskId, contactSource = msg.contactSource,
                    // 到达时刻：merge 是「收到即落地」，用当前本机时刻；上游已带 localTimeMs 则沿用
                    localTimeMs = msg.localTimeMs.takeIf { it != 0L } ?: System.currentTimeMillis()
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
     * 服务端权威分类校正：对「已经存在于 store 中」的消息，用服务端这次返回的分类重写居中提示标志。
     *
     * 为什么必须有这一步：消息一旦写入 store / 磁盘，[mergeServerMessages] 只会「追加新 id」，
     * 永远不会重算老条目的 SYSTEM 位。于是历史上任何一次误判（正文 contains 兜底、旧版本分类规则）
     * 都会被永久固化 —— 表现就是「隔一段时间有些消息变成广播样式，怎么重进都不恢复」。
     * 这里只改分类位（SYSTEM）和广播任务 ID，**不动正文、时间、顺序、滚动位置**，
     * 且仅当服务端本次确实返回了这条消息时才校正，没有服务端数据的老消息保持原样。
     *
     * @return 实际被纠正的条数（0 表示无需改动，调用方可据此决定是否 syncMessages）
     */
    fun reconcileServerClassification(server: List<MessageData>): Int {
        if (server.isEmpty() || store.count == 0) return 0
        var fixed = 0
        for (msg in server) {
            if (msg.id <= 0L) continue
            val idx = store.indexOf(msg.id)
            if (idx < 0) continue
            // 服务端这侧的分类已经是统一入口算好的结论（撤回/msgType/广播任务/拍一拍/群邀请协议）
            val want = msg.isSystemNotice
            if (store.isSystemNotice(idx) != want) {
                store.setSystemNotice(idx, want)
                fixed++
            }
            // 广播任务 ID 只在「服务端有、本地丢了」时补；不反向清空（防止服务端旧接口缺字段时丢管理入口）
            if (msg.broadcastTaskId != 0L && store.broadcastTaskIdAt(idx) == 0L) {
                store.setBroadcastTaskId(idx, msg.broadcastTaskId)
                fixed++
            }
        }
        return fixed
    }


    /**
     * 本地系统通知专用负 id 序列。
     *
     * 与服务端正数 id 区间完全隔离：负 id 永远不会被当成「最新服务端消息」，
     * 不会污染增量游标 afterId，也不会参与服务端 id 比较。
     */
    private var nextLocalId = -(System.currentTimeMillis() % 900000L) - 1L

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
            mediaType = mediaType, mediaUrl = mediaUrl, flashDuration = flashDuration,
            localTimeMs = System.currentTimeMillis()
        )
    }

    /**
     * 追加系统通知消息。
     */
    fun appendSystemNotice(text: String): Int {
        return append(
            // 必须是负数：System.nanoTime() 生成的正数是 19 位数，
            // 会把 serverCursor()/lastMessageId 撑到远超服务端的量级，
            // 之后 `serverData.maxOf { it.id } <= adapter.lastMessageId` 恒成立，
            // 增量拉取被整段跳过 —— 会话从此收不到任何新消息（只能退出重进）。
            id = nextLocalId--,
            fromUserId = 0, toUserId = 0, content = text,
            createdAt = System.currentTimeMillis() / 1000,
            isMine = false, isSystemNotice = true,
            localTimeMs = System.currentTimeMillis()
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
        tailDetached = false
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
        // 删除可能发生在窗口已脱钩的状态下（回到底部后删除），这里统一按「保底部」压回上限
        enforceCap(keepBottom = true)
        tailDetached = _indices.isNotEmpty() && _indices.last() < store.count - 1
        skipIds.removeAll { id -> (0 until store.count).none { store.idAt(it) == id } }
        refreshLastFromStore()
    }

    // ==================== 内部工具 ====================

    private fun parsePokeText(msg: MessageData, currentUserId: Long, currentUserName: String): String {
        // 被拍者名称必须来自 targetName；targetName 为空时回退到「对方」。
        // 群聊拍一拍的 to_user_id 是群会话 ID（负数），不能用 toUserId 判断「被拍的是你」，
        // 必须比较 targetName == currentUserName，否则会显示成「你拍了拍小明」这种主角错乱。
        val pokerName = if (msg.fromUserId == currentUserId) "你" else msg.senderName.ifEmpty { "对方" }
        val target = if (msg.targetName == currentUserName) "你" else msg.targetName.ifEmpty { "对方" }
        return if (msg.fromUserId == currentUserId && msg.targetName == currentUserName) {
            "你拍了拍自己"
        } else if (msg.fromUserId == currentUserId) {
            "你拍了拍$target"
        } else if (target == "你") {
            "${pokerName}拍了拍你"
        } else {
            "${pokerName}拍了拍$target"
        }
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
    override val time: Long get() = store.localTimeMsAt(index).takeIf { it != 0L }
        ?: store.timestampAt(index) * 1000L
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
    override val contactSource: String get() = store.contactSourceAt(index)

    /** 系统通知是否因撤回。 */
    val isRecallNotice: Boolean get() = isSystemNotice && isRevoked == 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is StoreMessageRef && other.store === store && other.index == index
    }

    override fun hashCode(): Int = index
}
