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

        const val FLASH_SHIFT  = 17
        const val FLASH_MASK   = 0xFFFFL shl FLASH_SHIFT

        private const val GROW_RATIO = 2
        private const val POOL_CHUNK = 8192

        private fun encodeMediaType(type: String): Long = when {
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

    // ==================== 公开属性 ====================
    val count: Int get() = size

    // ================================================================
    // 写入 API
    // ================================================================

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
        broadcastTaskId: Long = 0
    ): Int {
        if (size >= ids.size) growArrays()
        val idx = size++

        ids[idx] = id
        fromUids[idx] = fromUserId
        toUids[idx] = toUserId
        timestamps[idx] = createdAt
        replyToIds[idx] = replyToId
        broadcastTaskIds[idx] = broadcastTaskId

        // === flags：一次构造，零分支预测惩罚 ===
        var f = 0L
        f = f or (if (isMine) FLAG_IS_MINE else 0L)
        f = f or (if (isRevoked) FLAG_IS_REVOKED else 0L)
        f = f or (if (isUploading) FLAG_IS_UPLOADING else 0L)
        f = f or (if (isSystemNotice) FLAG_IS_SYSTEM else 0L)
        f = f or (if (isNew) FLAG_IS_NEW else 0L)
        if (flashDuration > 0) {
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

        return idx
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
            broadcastTaskId = m.broadcastTaskId
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
            System.arraycopy(replyToIds, index + 1, replyToIds, index, len)
            System.arraycopy(flags, index + 1, flags, index, len)
            System.arraycopy(broadcastTaskIds, index + 1, broadcastTaskIds, index, len)
            System.arraycopy(contentOffset, index + 1, contentOffset, index, len)
            System.arraycopy(senderNameOffset, index + 1, senderNameOffset, index, len)
            System.arraycopy(targetNameOffset, index + 1, targetNameOffset, index, len)
            System.arraycopy(replyTextOffset, index + 1, replyTextOffset, index, len)
            System.arraycopy(replySenderOffset, index + 1, replySenderOffset, index, len)
            System.arraycopy(mediaUrlOffset, index + 1, mediaUrlOffset, index, len)
            System.arraycopy(cachedContent, index + 1, cachedContent, index, len)
            System.arraycopy(cachedSenderName, index + 1, cachedSenderName, index, len)
            System.arraycopy(cachedTargetName, index + 1, cachedTargetName, index, len)
            System.arraycopy(cachedReplyText, index + 1, cachedReplyText, index, len)
            System.arraycopy(cachedReplySender, index + 1, cachedReplySender, index, len)
            System.arraycopy(cachedMediaUrl, index + 1, cachedMediaUrl, index, len)
        }
        size--
        // 清空末尾残留的读取缓存引用，避免被下一条消息复用读到旧 String
        cachedContent[size] = null
        cachedSenderName[size] = null
        cachedTargetName[size] = null
        cachedReplyText[size] = null
        cachedReplySender[size] = null
        cachedMediaUrl[size] = null
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
    fun flashDuration(index: Int): Int = ((flags[index] and FLASH_MASK) shr FLASH_SHIFT).toInt()
    fun mediaTypeBits(index: Int): Long = flags[index] and MEDIA_MASK

    fun mediaTypeString(index: Int): String = when (mediaTypeBits(index)) {
        MEDIA_IMAGE -> "image"
        MEDIA_VIDEO -> "video"
        MEDIA_AUDIO -> "audio"
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
        replyToIds = replyToIds.copyOf(newSize)
        flags = flags.copyOf(newSize)
        broadcastTaskIds = broadcastTaskIds.copyOf(newSize)
        contentOffset = contentOffset.copyOf(newSize)
        senderNameOffset = senderNameOffset.copyOf(newSize)
        targetNameOffset = targetNameOffset.copyOf(newSize)
        replyTextOffset = replyTextOffset.copyOf(newSize)
        replySenderOffset = replySenderOffset.copyOf(newSize)
        mediaUrlOffset = mediaUrlOffset.copyOf(newSize)
        cachedContent = cachedContent.copyOf(newSize)
        cachedSenderName = cachedSenderName.copyOf(newSize)
        cachedTargetName = cachedTargetName.copyOf(newSize)
        cachedReplyText = cachedReplyText.copyOf(newSize)
        cachedReplySender = cachedReplySender.copyOf(newSize)
        cachedMediaUrl = cachedMediaUrl.copyOf(newSize)
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
    val broadcastTaskId: Long = 0
)
