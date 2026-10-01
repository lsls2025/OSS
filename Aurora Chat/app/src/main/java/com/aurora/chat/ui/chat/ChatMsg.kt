package com.aurora.chat.ui.chat

// ==================== 热缓存（内存消息缓存，2分钟过期） ====================
// key = "currentUserId:friendId"，切换账号后不命中旧缓存
internal data class HotCacheEntry(val cachedAtMs: Long, val messages: List<ChatMsg>)
internal val messageHotCache = mutableMapOf<String, HotCacheEntry>()
internal const val HOT_CACHE_TTL_MS = 1_800_000L // 30分钟（缓存永不过期，网络刷新增量）

data class ChatMsg(
    override val id: Long,
    override val serverId: Long = 0,
    override val text: String,
    override val isMine: Boolean,
    override val fromUserId: Long,
    override val senderName: String = "",
    override val time: Long = System.currentTimeMillis(),
    override var isNew: Boolean = false,
    override val createdAt: Long = 0,  // 服务端时间戳（秒），0表示本地消息
    override val isRevoked: Int = 0,
    override val isSystemNotice: Boolean = false,  // true = 系统通知（居中显示，非气泡）
    override val replyToText: String = "",
    override val replyToSender: String = "",
    override val replyToId: Long = 0,
    override val mediaType: String = "",
    override val mediaUrl: String = "",
    override val isUploading: Boolean = false,  // 正在上传中
    override val toUserId: Long = 0,
    override val targetName: String = "",
    override val flashDuration: Int = 0,  // 0=普通照片，>0=闪照秒数
    override val reasoningText: String = "",
    override val thinkingSeconds: Long = 0,
    override val totalSeconds: Long = 0,
    override val manualStopped: Boolean = false,
    override val broadcastTaskId: Long = 0,
    /** AI 本次会话改动过的文件记录（JSON 字符串，空表示无）；供「查看所有改动」全屏界面使用 */
    override val aiFileChanges: String = "",
    /** AI 本次会话实际调用的工具次数（0 表示无；运行时由采集层计数） */
    override val aiToolCallCount: Int = 0,
    // 仅运行时：流式阶段是否正处在「等待模型响应」空窗（尚未产生任何思考/输出）。不落盘。
    val aiWaiting: Boolean = false,
    /** 运行时任务进度提示（如「任务进行中 · 第 N 轮」「正在收尾交付」）；仅运行时,落盘为空串,不持久化语义 */
    val aiProgressHint: String = "",
    // 工具调用状态行专用标记：true 表示这是一条独立的「工具调用状态」消息（并非普通对话气泡）。
    // 工具状态作为独立消息插入消息列表，就地站位、随消息列表一并持久化，永不丢失。
    val isToolStatus: Boolean = false,
    // 工具调用最终状态（持久化）：如非空则表示本条消息曾触发工具调用并结束，重进会话后仍显示彩色状态行。
    // aiToolStage=工具名；aiToolStageState=0调用中/1已调用成功/2调用失败（仅 1/2 会落盘，0 仅运行时）
    val aiToolStage: String = "",
    val aiToolStageState: Int = -1,
    // 工具调用完整记录（持久化列表）：每次调用的完成态都会追加留存，不再被下一次调用覆盖。
    // aiToolStages 按调用顺序追加本条消息历史上全部工具调用记录（state 仅存 1已调用/2失败）；
    // aiToolStage/aiToolStageState 保留为"最近一次"以兼容旧数据与旧读取路径。
    val aiToolStages: List<ToolStageRecord> = emptyList()
) : IMessageRef {

/** AI 工具调用记录（tool=工具名；state=1已调用成功/2调用失败；ts=调用发起时刻毫秒时间戳,0=旧数据无记录）。 */
data class ToolStageRecord(val tool: String, val state: Int, val ts: Long = 0)
    companion object {
        private var nextId = System.currentTimeMillis()
        fun create(text: String, isMine: Boolean, fromUserId: Long = 0, serverId: Long = 0, isNew: Boolean = false, senderName: String = "", createdAt: Long = 0, isRevoked: Int = 0, isSystemNotice: Boolean = false, replyToText: String = "", replyToSender: String = "", replyToId: Long = 0, mediaType: String = "", mediaUrl: String = "", isUploading: Boolean = false, flashDuration: Int = 0, toUserId: Long = 0, targetName: String = "", reasoningText: String = "", thinkingSeconds: Long = 0, totalSeconds: Long = 0, manualStopped: Boolean = false, broadcastTaskId: Long = 0, isToolStatus: Boolean = false, aiToolStage: String = "", aiToolStageState: Int = -1, aiToolStages: List<ToolStageRecord> = emptyList(), aiFileChanges: String = "", aiToolCallCount: Int = 0, aiProgressHint: String = ""): ChatMsg {
            val normalizedType = when {
                // 优先看 mediaUrl 扩展名：APK/TXT/PDF 等文件即使服务器误标成 image/jpg，也归为 file
                mediaUrl.isNotEmpty() && !isImageMediaUrl(mediaUrl) && !isVideoMediaUrl(mediaUrl) -> "file"
                mediaType.startsWith("video/") -> "video"
                mediaType.startsWith("image/") -> "image"
                mediaType == "mp4" -> "video"
                mediaType == "jpg" || mediaType == "png" || mediaType == "gif" || mediaType == "jpeg" -> "image"
                else -> mediaType
            }
            return ChatMsg(
                id = if (serverId > 0) serverId else nextId++,
                serverId = serverId,
                text = text, isMine = isMine, fromUserId = fromUserId, isNew = isNew, senderName = senderName, createdAt = createdAt, isRevoked = isRevoked, isSystemNotice = isSystemNotice, replyToText = replyToText, replyToSender = replyToSender, replyToId = replyToId, mediaType = normalizedType, mediaUrl = mediaUrl,                 isUploading = isUploading, flashDuration = flashDuration, toUserId = toUserId, targetName = targetName, reasoningText = reasoningText, thinkingSeconds = thinkingSeconds, totalSeconds = totalSeconds, manualStopped = manualStopped, broadcastTaskId = broadcastTaskId, isToolStatus = isToolStatus, aiToolStage = aiToolStage, aiToolStageState = aiToolStageState, aiToolStages = aiToolStages, aiFileChanges = aiFileChanges, aiToolCallCount = aiToolCallCount, aiProgressHint = aiProgressHint
                )
        }
    }
}

/** 依据 mediaUrl 的扩展名判断是否为【确定】的图片。 */
fun isImageMediaUrl(url: String): Boolean {
    val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
    return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
}

/** 依据 mediaUrl 的扩展名判断是否为【确定】的视频。 */
fun isVideoMediaUrl(url: String): Boolean {
    val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
    return ext in setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg")
}
