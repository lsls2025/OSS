package com.aurora.chat.data.store

/**
 * ## 极致性能消息存储引擎
 *
 * ### 架构
 * 每条消息的信息分布在 **6 个平行 LongArray + 6 个偏移 LongArray** 中，
 * 文本内容集中存储在单个 **CharArray** (池) 中。
 *
 * 一条消息占用的 Java 堆内存：
 *   - 12 × 8 bytes (LongArray entries) = **96 bytes**
 *   - CharArray 共享，文本不额外分配
 * 对比 ChatMsg：~200-300 bytes + 多个 String 对象（各 ~40 bytes）
 *
 * ### 缓存友好
 * 顺序遍历时数据连续，cache line 利用率接近 100%。
 * ChatMsg 散落堆中，每次访问需 pointer chase。
 *
 * ### 零临时分配（读取路径）
 * 预分配数组，append 按 2x 增长。
 * 文本内容首次读取时从内容池物化为 String 并缓存，之后任意次读取都返回同一引用，
 * 组合期不再分配新 String（消除快滑时每条消息每帧数十次分配驱动的 GC 抖动）。
 *
 * ### 线程安全
 * **不内置锁**，由调用方（ViewModel 协程）保证单线程访问。
 */
class MessageStore(initialCapacity: Int = 256) {

    companion object {
        // ========== flags 位域 ==========
        const val FLAG_IS_MINE      = 1L shl 0
        const val FLAG_IS_REVOKED   = 1L shl 1
        const val FLAG_IS_UPLOADING = 1L shl 2
        const val FLAG_IS_SYSTEM    = 1L shl 3
        const val FLAG_IS_FLASH     = 1L shl 4
        const val FLAG_IS_NEW       = 1L shl 5

        const val MEDIA_SHIFT  = 6
        const val MEDIA_MASK   = 0b111L shl MEDIA_SHIFT
        const val MEDIA_NONE   = 0L shl MEDIA_SHIFT
        const val MEDIA_IMAGE  = 1L shl MEDIA_SHIFT
        const val MEDIA_VIDEO  = 2L shl MEDIA_SHIFT
        const val MEDIA_AUDIO  = 3L shl MEDIA_SHIFT
        const val MEDIA_FILE   = 4L shl MEDIA_SHIFT
        // 语音消息（聊天里的长按录音）：单独占一位，不能和 file 混用，
        // 否则本地缓存往返一次就把 voice 写成 file，对方/重进会话后全变成文件卡片。
        const val MEDIA_VOICE  = 5L shl MEDIA_SHIFT
        // 平台表情包 / 内置 emoji 字符：同样必须单独占位。
        // 旧实现的兜底 `type.isNotEmpty() -> MEDIA_FILE` 会把这两种统统压成 file，
        // 于是引用一条表情包消息时，引用条显示 "[文件]"、写进服务端的 reply_to_text
        // 也是 "[文件]"，对方和重进会话后胶囊就渲染成文件缩略块（用户报的"引用表情包显示成文件"）。
        // MEDIA_MASK 是 0b111（3 bit，值 0..7），6/7 是最后两个空位，正好够用。
        const val MEDIA_STICKER = 6L shl MEDIA_SHIFT
        const val MEDIA_EMOJI   = 7L shl MEDIA_SHIFT

        const val FLASH_SHIFT  = 17
        const val FLASH_MASK   = 0xFFFFL shl FLASH_SHIFT

        private const val GROW_RATIO = 2
        private const val POOL_CHUNK = 8192

        private fun encodeMediaType(type: String): Long = when {
            // 表情优先：必须先于 `type.isNotEmpty() -> MEDIA_FILE`，否则会被压成文件。
            // sticker:// 也认，兼容个别把协议串直接塞进 mediaType 的历史数据。
            type == "sticker" || type.startsWith("sticker://") -> MEDIA_STICKER
            type == "emoji" -> MEDIA_EMOJI
            type == "voice" -> MEDIA_VOICE
            type == "image" || type.startsWith("image/") || type in setOf("jpg","png","gif","jpeg","webp") -> MEDIA_IMAGE
            type == "video" || type.startsWith("video/") || type == "mp4" -> MEDIA_VIDEO
            type.startsWith("audio/") -> MEDIA_AUDIO
            type.isNotEmpty() -> MEDIA_FILE
            else -> MEDIA_NONE
        }

        /** 将 [start, end) 打包到一个 Long 中。 */
        private fun packOffset(start: Int, end: Int): Long =
            (start.toLong() shl 32) or (end.toLong() and 0xFFFF_FFFFL)

        /** 从 Long 解包 [start, end)。 */
        private fun unpackOffset(packed: Long): Pair<Int, Int> =
            (packed shr 32).toInt() to packed.toInt()
    }

    // ==================== 6 个原始字段数组 ====================
    private var ids = LongArray(initialCapacity)
    private var fromUids = LongArray(initialCapacity)
    private var toUids = LongArray(initialCapacity)
    private var timestamps = LongArray(initialCapacity)
    // 本机毫秒时间（客户端事件时刻：发送瞬间/到达瞬间），0 = 未记录（读取时回退 createdAt*1000）
    private var localTimes = LongArray(initialCapacity)
    private var replyToIds = LongArray(initialCapacity)
    private var flags = LongArray(initialCapacity)
    private var broadcastTaskIds = LongArray(initialCapacity)

    // ==================== 6 个内容偏移数组 ====================
    // 每个 Long = [高32位:start] [低32位:end]，指向 pool
    private var contentOffset = LongArray(initialCapacity)
    private var senderNameOffset = LongArray(initialCapacity)
    private var targetNameOffset = LongArray(initialCapacity)
    private var replyTextOffset = LongArray(initialCapacity)
    private var replySenderOffset = LongArray(initialCapacity)
    private var mediaUrlOffset = LongArray(initialCapacity)
    private var contactSourceOffset = LongArray(initialCapacity)

    // ==================== 内容池 ====================
    private var pool = CharArray(POOL_CHUNK)
    private var poolEnd = 0

    private var size = 0

    // ==================== 字符串读取缓存 ====================
    // 与索引等长。contentAt 等首次读取时把池内片段物化为 String 并缓存，
    // 之后组合期对同一条消息的任意次读取都返回同一引用，彻底消除快滑时的重复分配。
    // 缓存的 String 引用原始 pool 的 char[]，pool 扩容（copyOf）后旧引用仍有效。
    private var cachedContent: Array<String?> = arrayOfNulls(initialCapacity)
    private var cachedSenderName: Array<String?> = arrayOfNulls(initialCapacity)
    private var cachedTargetName: Array<String?> = arrayOfNulls(initialCapacity)
    private var cachedReplyText: Array<String?> = arrayOfNulls(initialCapacity)
    private var cachedReplySender: Array<String?> = arrayOfNulls(initialCapacity)
    private var cachedMediaUrl: Array<String?> = arrayOfNulls(initialCapacity)
    private var cachedContactSource: Array<String?> = arrayOfNulls(initialCapacity)

    // ==================== 公开属性 ====================
    val count: Int get() = size

    // ================================================================
    // 写入 API
    // ================================================================

    /** 在指定下标写入一条消息的全部字段（append / prepend 共用，零重复）。 */
    private fun writeAt(
        idx: Int,
        id: Long,
        fromUserId: Long,
        toUserId: Long,
        content: String,
        createdAt: Long,
        isMine: Boolean,
        senderName: String,
        targetName: String,
        isNew: Boolean,
        isRevoked: Boolean,
        isSystemNotice: Boolean,
        isUploading: Boolean,
        flashDuration: Int,
        mediaType: String,
        mediaUrl: String,
        replyToId: Long,
        replyToText: String,
        replyToSender: String,
        broadcastTaskId: Long,
        contactSource: String,
        localTimeMs: Long
    ) {
        ids[idx] = id
        fromUids[idx] = fromUserId
        toUids[idx] = toUserId
        timestamps[idx] = createdAt
        localTimes[idx] = localTimeMs
        replyToIds[idx] = replyToId
        broadcastTaskIds[idx] = broadcastTaskId

        // === flags：一次构造，零分支预测惩罚 ===
        var f = 0L
        f = f or (if (isMine) FLAG_IS_MINE else 0L)
        f = f or (if (isRevoked) FLAG_IS_REVOKED else 0L)
        f = f or (if (isUploading) FLAG_IS_UPLOADING else 0L)
        f = f or (if (isSystemNotice) FLAG_IS_SYSTEM else 0L)
        f = f or (if (isNew) FLAG_IS_NEW else 0L)
        // 必须用 != 0 而不是 > 0：拍一拍用 flashDuration = -1 作为服务端标记
        // （friend_handlers.go 保存 POKE 时唯一写入点）。旧实现只编码 >0，
        // 导致拍一拍经过一次 store 往返后 -1 退化成 0，类型信息丢失，
        // 磁盘路径就只能靠「正文包含拍了拍」去猜，进而把普通正文误判成系统广播。
        if (flashDuration != 0) {
            f = f or FLAG_IS_FLASH
            f = f or ((flashDuration.toLong() and 0xFFFF) shl FLASH_SHIFT)
        }
        f = f or encodeMediaType(mediaType)
        flags[idx] = f

        // === 写入内容池 ===
        contentOffset[idx] = poolAppend(content)
        senderNameOffset[idx] = poolAppend(senderName)
        targetNameOffset[idx] = poolAppend(targetName)
        replyTextOffset[idx] = poolAppend(replyToText)
        replySenderOffset[idx] = poolAppend(replyToSender)
        mediaUrlOffset[idx] = poolAppend(mediaUrl)
        contactSourceOffset[idx] = poolAppend(contactSource)
    }

    /** 追加一条消息，返回其索引。O(1) 摊销。 */
    fun append(
        id: Long,
        fromUserId: Long,
        toUserId: Long,
        content: String,
        createdAt: Long,
        isMine: Boolean,
        senderName: String = "",
        targetName: String = "",
        isNew: Boolean = false,
        isRevoked: Boolean = false,
        isSystemNotice: Boolean = false,
        isUploading: Boolean = false,
        flashDuration: Int = 0,
        mediaType: String = "",
        mediaUrl: String = "",
        replyToId: Long = 0,
        replyToText: String = "",
        replyToSender: String = "",
        broadcastTaskId: Long = 0,
        contactSource: String = "",
        localTimeMs: Long = 0
    ): Int {
        if (size >= ids.size) growArrays()
        val idx = size++
        writeAt(
            idx, id, fromUserId, toUserId, content, createdAt, isMine, senderName, targetName,
            isNew, isRevoked, isSystemNotice, isUploading, flashDuration, mediaType, mediaUrl,
            replyToId, replyToText, replyToSender, broadcastTaskId, contactSource, localTimeMs
        )
        return idx
    }

    /**
     * 在 store 最前部插入一批更旧消息（服务端上滑翻页用）。
     * 现有全部条目整体右移 [batch.size]，新批次写入前部 [0, batch.size)。
     * pool 不参与平移（旧条目仍指向同一池片段，String 引用有效）。
     * 平移后新占用的前部缓存槽置空，避免懒读取命中旧 String 引用。
     */
    fun prepend(batch: List<MessageData>) {
        if (batch.isEmpty()) return
        val shift = batch.size
        val needed = size + shift
        if (needed > ids.size) growArrays(needed)
        shiftRight(shift)
        for (k in 0 until shift) {
            val m = batch[k]
            writeAt(
                k, m.id, m.fromUserId, m.toUserId, m.content, m.createdAt, m.isMine,
                m.senderName, m.targetName, m.isNew, m.isRevoked, m.isSystemNotice, m.isUploading,
                m.flashDuration, m.mediaType, m.mediaUrl, m.replyToId, m.replyToText,
                m.replyToSender, m.broadcastTaskId, m.contactSource, m.localTimeMs
            )
        }
        // 前部缓存槽置空，强制后续懒读取按新批次物化
        for (k in 0 until shift) {
            cachedContent[k] = null
            cachedSenderName[k] = null
            cachedTargetName[k] = null
            cachedReplyText[k] = null
            cachedReplySender[k] = null
            cachedMediaUrl[k] = null
            cachedContactSource[k] = null
        }
        size = needed
    }

    /** 全部 21 个数组（7 原始 + 7 偏移 + 7 缓存）整体右移 [shift]；pool 不平移。 */
    private fun shiftRight(shift: Int) {
        System.arraycopy(ids, 0, ids, shift, size)
        System.arraycopy(fromUids, 0, fromUids, shift, size)
        System.arraycopy(toUids, 0, toUids, shift, size)
        System.arraycopy(timestamps, 0, timestamps, shift, size)
        System.arraycopy(localTimes, 0, localTimes, shift, size)
        System.arraycopy(replyToIds, 0, replyToIds, shift, size)
        System.arraycopy(flags, 0, flags, shift, size)
        System.arraycopy(broadcastTaskIds, 0, broadcastTaskIds, shift, size)
        System.arraycopy(contentOffset, 0, contentOffset, shift, size)
        System.arraycopy(senderNameOffset, 0, senderNameOffset, shift, size)
        System.arraycopy(targetNameOffset, 0, targetNameOffset, shift, size)
        System.arraycopy(replyTextOffset, 0, replyTextOffset, shift, size)
        System.arraycopy(replySenderOffset, 0, replySenderOffset, shift, size)
        System.arraycopy(mediaUrlOffset, 0, mediaUrlOffset, shift, size)
        System.arraycopy(contactSourceOffset, 0, contactSourceOffset, shift, size)
        System.arraycopy(cachedContent, 0, cachedContent, shift, size)
        System.arraycopy(cachedSenderName, 0, cachedSenderName, shift, size)
        System.arraycopy(cachedTargetName, 0, cachedTargetName, shift, size)
        System.arraycopy(cachedReplyText, 0, cachedReplyText, shift, size)
        System.arraycopy(cachedReplySender, 0, cachedReplySender, shift, size)
        System.arraycopy(cachedMediaUrl, 0, cachedMediaUrl, shift, size)
        System.arraycopy(cachedContactSource, 0, cachedContactSource, shift, size)
    }

    /**
     * 批量追加。初始加载时用，一次 grow 到位。
     */
    fun appendAll(messages: List<MessageData>) {
        val needed = size + messages.size
        if (needed > ids.size) growArrays(needed)

        var extraChars = 0
        for (m in messages) {
            extraChars += m.content.length + m.senderName.length +
                    m.targetName.length + m.replyToText.length +
                    m.replyToSender.length + m.mediaUrl.length
        }
        ensurePoolCapacity(poolEnd + extraChars)

        for (m in messages) append(
            id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
            content = m.content, createdAt = m.createdAt, isMine = m.isMine,
            senderName = m.senderName, targetName = m.targetName,
            isNew = m.isNew, isRevoked = m.isRevoked,
            isSystemNotice = m.isSystemNotice, isUploading = m.isUploading,
            flashDuration = m.flashDuration, mediaType = m.mediaType,
            mediaUrl = m.mediaUrl, replyToId = m.replyToId,
            replyToText = m.replyToText, replyToSender = m.replyToSender,
            broadcastTaskId = m.broadcastTaskId, contactSource = m.contactSource,
            localTimeMs = m.localTimeMs
        )
    }

    /** 清空所有数据。O(1)。 */
    fun clear() {
        size = 0
        poolEnd = 0
        cachedContent.fill(null)
        cachedSenderName.fill(null)
        cachedTargetName.fill(null)
        cachedReplyText.fill(null)
        cachedReplySender.fill(null)
        cachedMediaUrl.fill(null)
        cachedContactSource.fill(null)
    }

    /** 按 ID 查找索引。从尾向前扫描（新消息在末尾）。O(n)。 */
    fun indexOf(id: Long): Int {
        var i = size - 1
        while (i >= 0) {
            if (ids[i] == id) return i
            i--
        }
        return -1
    }

    /**
     * 删除指定下标的消息，并把其后所有条目向前平移一位，保持 19 个平行数组一致。
     * 调用方保证单线程；仅 [MessageStoreAdapter.removeByPredicate] 使用。
     */
    fun removeAt(index: Int) {
        if (index < 0 || index >= size) return
        val last = size - 1
        if (index != last) {
            val len = last - index
            System.arraycopy(ids, index + 1, ids, index, len)
            System.arraycopy(fromUids, index + 1, fromUids, index, len)
            System.arraycopy(toUids, index + 1, toUids, index, len)
            System.arraycopy(timestamps, index + 1, timestamps, index, len)
            System.arraycopy(localTimes, index + 1, localTimes, index, len)
            System.arraycopy(replyToIds, index + 1, replyToIds, index, len)
            System.arraycopy(flags, index + 1, flags, index, len)
            System.arraycopy(broadcastTaskIds, index + 1, broadcastTaskIds, index, len)
            System.arraycopy(contentOffset, index + 1, contentOffset, index, len)
            System.arraycopy(senderNameOffset, index + 1, senderNameOffset, index, len)
            System.arraycopy(targetNameOffset, index + 1, targetNameOffset, index, len)
            System.arraycopy(replyTextOffset, index + 1, replyTextOffset, index, len)
            System.arraycopy(replySenderOffset, index + 1, replySenderOffset, index, len)
            System.arraycopy(mediaUrlOffset, index + 1, mediaUrlOffset, index, len)
            System.arraycopy(contactSourceOffset, index + 1, contactSourceOffset, index, len)
            System.arraycopy(cachedContent, index + 1, cachedContent, index, len)
            System.arraycopy(cachedSenderName, index + 1, cachedSenderName, index, len)
            System.arraycopy(cachedTargetName, index + 1, cachedTargetName, index, len)
            System.arraycopy(cachedReplyText, index + 1, cachedReplyText, index, len)
            System.arraycopy(cachedReplySender, index + 1, cachedReplySender, index, len)
            System.arraycopy(cachedMediaUrl, index + 1, cachedMediaUrl, index, len)
            System.arraycopy(cachedContactSource, index + 1, cachedContactSource, index, len)
        }
        size--
        // 清空末尾残留的读取缓存引用，避免被下一条消息复用读到旧 String
        cachedContent[size] = null
        cachedSenderName[size] = null
        cachedTargetName[size] = null
        cachedReplyText[size] = null
        cachedReplySender[size] = null
        cachedMediaUrl[size] = null
        cachedContactSource[size] = null
    }

    /** 更新 flags 位域（按掩码）。 */
    fun updateFlags(index: Int, mask: Long, value: Long) {
        flags[index] = (flags[index] and mask.inv()) or (value and mask)
    }

    /** 标记撤回。 */
    fun markRevoked(index: Int) {
        flags[index] = flags[index] or FLAG_IS_REVOKED
        flags[index] = flags[index] and FLAG_IS_UPLOADING.inv()
    }

    /**
     * 重写「居中提示」标志（服务端权威分类校正用）。
     * 只改这一位，不动正文、时间、顺序与其它标志。
     */
    fun setSystemNotice(index: Int, on: Boolean) {
        if (index < 0 || index >= size) return
        flags[index] = if (on) flags[index] or FLAG_IS_SYSTEM
        else flags[index] and FLAG_IS_SYSTEM.inv()
    }

    /** 重写广播任务 ID（0 = 普通消息）。 */
    fun setBroadcastTaskId(index: Int, taskId: Long) {
        if (index < 0 || index >= size) return
        broadcastTaskIds[index] = taskId
    }

    /** 更新消息 ID（本地消息→服务端 ID）。 */
    fun updateId(index: Int, newId: Long) {
        ids[index] = newId
    }

    /** 标记上传中/已上传。 */
    fun markUploading(index: Int, uploading: Boolean) {
        flags[index] = if (uploading) flags[index] or FLAG_IS_UPLOADING
        else flags[index] and FLAG_IS_UPLOADING.inv()
    }

    // ================================================================
    // 读取 API
    // ================================================================

    fun idAt(index: Int): Long = ids[index]
    fun fromUidAt(index: Int): Long = fromUids[index]
    fun toUidAt(index: Int): Long = toUids[index]
    fun timestampAt(index: Int): Long = timestamps[index]
    /** 本机毫秒时间；0 = 未记录（调用方应回退 createdAt*1000） */
    fun localTimeMsAt(index: Int): Long = localTimes[index]
    fun replyToIdAt(index: Int): Long = replyToIds[index]
    fun broadcastTaskIdAt(index: Int): Long = broadcastTaskIds[index]
    fun flagsAt(index: Int): Long = flags[index]

    // ---- flags 位域助手 ----
    fun isMine(index: Int): Boolean = (flags[index] and FLAG_IS_MINE) != 0L
    fun isRevoked(index: Int): Boolean = (flags[index] and FLAG_IS_REVOKED) != 0L
    fun isUploading(index: Int): Boolean = (flags[index] and FLAG_IS_UPLOADING) != 0L
    fun isSystemNotice(index: Int): Boolean = (flags[index] and FLAG_IS_SYSTEM) != 0L
    fun isFlash(index: Int): Boolean = (flags[index] and FLAG_IS_FLASH) != 0L
    fun isNew(index: Int): Boolean = (flags[index] and FLAG_IS_NEW) != 0L
    /**
     * 读取 flashDuration。
     *
     * 存的是 16 位补码，这里必须 [toShort] 还原负数：
     * 拍一拍标记 -1 编码后是 0xFFFF，直接 toInt() 会读成 65535，
     * 于是「flashDuration == -1」的判定永远不成立（拍一拍掉成普通气泡），
     * 而任何被写成 65535 的历史脏值又会被当成闪照。
     */
    fun flashDuration(index: Int): Int =
        ((flags[index] and FLASH_MASK) shr FLASH_SHIFT).toInt().toShort().toInt()
    fun mediaTypeBits(index: Int): Long = flags[index] and MEDIA_MASK

    fun mediaTypeString(index: Int): String = when (mediaTypeBits(index)) {
        MEDIA_IMAGE -> "image"
        MEDIA_VIDEO -> "video"
        MEDIA_AUDIO -> "audio"
        MEDIA_VOICE -> "voice"
        MEDIA_STICKER -> "sticker"
        MEDIA_EMOJI -> "emoji"
        MEDIA_FILE  -> "file"
        else -> ""
    }

    // ---- 字符串读取 ----

    /** 消息内容（首次读取时物化并缓存，之后直接返回缓存引用，组合期零分配）。 */
    fun contentAt(index: Int): String {
        cachedContent[index]?.let { return it }
        val (s, e) = unpackOffset(contentOffset[index])
        return pool.concatToString(s, e).also { cachedContent[index] = it }
    }

    /** 发送者名称。 */
    fun senderNameAt(index: Int): String {
        cachedSenderName[index]?.let { return it }
        val (s, e) = unpackOffset(senderNameOffset[index])
        return (if (e > s) pool.concatToString(s, e) else "").also { cachedSenderName[index] = it }
    }

    /** 目标/群名。 */
    fun targetNameAt(index: Int): String {
        cachedTargetName[index]?.let { return it }
        val (s, e) = unpackOffset(targetNameOffset[index])
        return (if (e > s) pool.concatToString(s, e) else "").also { cachedTargetName[index] = it }
    }

    /** 回复文本。 */
    fun replyToTextAt(index: Int): String {
        cachedReplyText[index]?.let { return it }
        val (s, e) = unpackOffset(replyTextOffset[index])
        return (if (e > s) pool.concatToString(s, e) else "").also { cachedReplyText[index] = it }
    }

    /** 回复发送者。 */
    fun replyToSenderAt(index: Int): String {
        cachedReplySender[index]?.let { return it }
        val (s, e) = unpackOffset(replySenderOffset[index])
        return (if (e > s) pool.concatToString(s, e) else "").also { cachedReplySender[index] = it }
    }

    /** 媒体 URL。 */
    fun mediaUrlAt(index: Int): String {
        cachedMediaUrl[index]?.let { return it }
        val (s, e) = unpackOffset(mediaUrlOffset[index])
        return (if (e > s) pool.concatToString(s, e) else "").also { cachedMediaUrl[index] = it }
    }

    /** 陌生人首条消息来源：search / group:<群名>，非空时气泡上方渲染系统广播。 */
    fun contactSourceAt(index: Int): String {
        if (index < 0 || index >= size) return ""
        val cached = cachedContactSource[index]
        if (cached != null) return cached
        val (s, e) = unpackOffset(contactSourceOffset[index])
        return (if (e > s) pool.concatToString(s, e) else "").also { cachedContactSource[index] = it }
    }

    // ================================================================
    // 辅助
    // ================================================================

    /** 将字符串追加到内容池，返回 [start, end) 偏移。 */
    private fun poolAppend(text: String): Long {
        val start = poolEnd
        val len = text.length
        ensurePoolCapacity(poolEnd + len)
        text.toCharArray(pool, start, 0, len)
        poolEnd += len
        return packOffset(start, poolEnd)
    }

    /** 确保数组容量。 */
    private fun ensureCapacity(min: Int) {
        if (min <= ids.size) return
        growArrays(min)
    }

    /** 按比例增长数组到至少 min 容量。 */
    private fun growArrays(min: Int = ids.size + 1) {
        val newSize = maxOf(min, ids.size * GROW_RATIO)
        ids = ids.copyOf(newSize)
        fromUids = fromUids.copyOf(newSize)
        toUids = toUids.copyOf(newSize)
        timestamps = timestamps.copyOf(newSize)
        localTimes = localTimes.copyOf(newSize)
        replyToIds = replyToIds.copyOf(newSize)
        flags = flags.copyOf(newSize)
        broadcastTaskIds = broadcastTaskIds.copyOf(newSize)
        contentOffset = contentOffset.copyOf(newSize)
        senderNameOffset = senderNameOffset.copyOf(newSize)
        targetNameOffset = targetNameOffset.copyOf(newSize)
        replyTextOffset = replyTextOffset.copyOf(newSize)
        replySenderOffset = replySenderOffset.copyOf(newSize)
        mediaUrlOffset = mediaUrlOffset.copyOf(newSize)
        contactSourceOffset = contactSourceOffset.copyOf(newSize)
        cachedContent = cachedContent.copyOf(newSize)
        cachedSenderName = cachedSenderName.copyOf(newSize)
        cachedTargetName = cachedTargetName.copyOf(newSize)
        cachedReplyText = cachedReplyText.copyOf(newSize)
        cachedReplySender = cachedReplySender.copyOf(newSize)
        cachedMediaUrl = cachedMediaUrl.copyOf(newSize)
        cachedContactSource = cachedContactSource.copyOf(newSize)
    }

    /** 确保内容池容量。 */
    private fun ensurePoolCapacity(min: Int) {
        if (min <= pool.size) return
        val newSize = maxOf(min, pool.size + POOL_CHUNK)
        pool = pool.copyOf(newSize)
    }
}

/**
 * 消息数据值对象 — 仅在写入 MessageStore 时作为桥梁使用。
 *
 * 此对象在 appendAll 之后即可被 GC 回收。
 * 运行时渲染不经过此对象，直接从 MessageStore 的数组读取。
 *
 * @param isRevoked 0=正常, 1=已撤回
 */
data class MessageData(
    val id: Long,
    val fromUserId: Long,
    val toUserId: Long,
    val content: String,
    val createdAt: Long,
    val isMine: Boolean,
    val senderName: String = "",
    val targetName: String = "",
    val isNew: Boolean = false,
    val isRevoked: Boolean = false,
    val isSystemNotice: Boolean = false,
    val isUploading: Boolean = false,
    val flashDuration: Int = 0,
    val mediaType: String = "",
    val mediaUrl: String = "",
    val replyToId: Long = 0,
    val replyToText: String = "",
    val replyToSender: String = "",
    val broadcastTaskId: Long = 0,
    val contactSource: String = "",
    /** 本机毫秒时间（客户端事件时刻：发送瞬间/到达瞬间），0 = 未记录 */
    val localTimeMs: Long = 0
)
