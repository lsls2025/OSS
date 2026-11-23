package com.aurora.chat.ui.chat

import androidx.compose.runtime.Immutable

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
    override val createdAt: Long = 0,  // 消息时刻（秒）。本地新消息由 create() 自动填「当前时刻」，0 = 未知
    override val isRevoked: Int = 0,
    override val isSystemNotice: Boolean = false,  // true = 系统通知（居中显示，非气泡）
    override val replyToText: String = "",
    override val replyToSender: String = "",
    override val replyToId: Long = 0,
    override val mediaType: String = "",
    override val mediaUrl: String = "",
    /** 媒体原始宽度（0 表示后端未返回/非媒体消息） */
    override val mediaWidth: Int = 0,
    /** 媒体原始高度（0 表示后端未返回/非媒体消息） */
    override val mediaHeight: Int = 0,
    override val isUploading: Boolean = false,  // 正在上传中
    /** 上传中的本地媒体预览地址（content:// / file://）：气泡立刻显示真实缩略图与真实比例，零网络 */
    override val localPreviewUri: String = "",
    override val toUserId: Long = 0,
    override val targetName: String = "",
    override val flashDuration: Int = 0,  // 0=普通照片，>0=闪照秒数
    override val reasoningText: String = "",
    override val thinkingSeconds: Long = 0,
    override val totalSeconds: Long = 0,
    override val inputTokens: Long = 0,
    override val outputTokens: Long = 0,
    override val cacheHitTokens: Long = 0,
    override val contextLength: Long = 0,
    override val aiCostDeducted: Long = 0,
    /** 本条 AI 回复发送时使用的 API 模式（personal/third_party/free_api/paid_api/official，空=旧数据无记录）。用于区分免费/付费展示「无消耗」。 */
    val aiMode: String = "",
    override val manualStopped: Boolean = false,
    /** 中断记录的 token 数值是否来自服务端权威结算（false = 客户端估算）。见 [IMessageRef.aiUsageAuthoritative]。 */
    override val aiUsageAuthoritative: Boolean = false,
    override val broadcastTaskId: Long = 0,
    /** 陌生人首条消息来源：search / group:<群名>；非空时会话内渲染系统广播（仅接收方显示）。 */
    override val contactSource: String = "",
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
    val aiToolStages: List<ToolStageRecord> = emptyList(),
    /**
     * 发送失败标记：气泡左侧显示红色感叹号，点击可重发。
     * 失败消息由 [com.aurora.chat.data.local.FailedMessageStore] 落盘，
     * 因此重进会话后仍会以「红叹号 + 原文」的形式还原。
     */
    override val sendFailed: Boolean = false,
    /** 服务端持久化的客户端发送瞬间（毫秒）；0 = 旧消息 / 无记录，引用胶囊回退 createdAt */
    override val clientSendAt: Long = 0,
    /** @ 目标映射（发送时昵称 → 用户ID）：昵称会变，ID 不会，改名后仍能定位被 @ 的人 */
    override val mentions: Map<String, Long> = emptyMap()
) : IMessageRef {

/**
 * AI 工具调用记录。
 *
 * @param tool 工具名（英文原名，展示时经 toolDisplayName 汉化）
 * @param state 1=调用成功 / 2=调用失败
 * @param ts 调用发起时刻毫秒时间戳，0=旧数据无记录
 * @param reason 调用原因（由工具名+参数生成的用途短句，展示于工具名与调用时间之间）
 * @param target 本次调用的**具体目标**（文件路径 / 命令 / URL / 关键字…），明细页单行显示、可展开
 * @param argsSummary 本次调用的**关键参数摘要**（多行 key=value；已脱敏、大字段只留字数），
 *        供「调用 N 个工具」明细页展开对账
 */
@Immutable
data class ToolStageRecord(
    val tool: String,
    val state: Int,
    val ts: Long = 0,
    val reason: String = "",
    val target: String = "",
    val argsSummary: String = "",
    /**
     * 代码类工具（execute_python / serve_start / shell_exec）的**原始代码正文**，其余工具为空串。
     *
     * 为什么不复用 argsSummary：摘要把大字段压成「N 字 / M 行」，用户看得到却抄不走；
     * 而「运行 Python」卡片的价值恰恰在于用户能把代码复制出来自己跑。长度在采集侧已封顶（见 CODE_MAX）。
     */
    val code: String = "",
    /**
     * 执行类工具（execute_python / shell_exec / serve_start）的**真实运行结果**（stdout / stderr / 失败原因），
     * 其余工具为空串。以前结果只在 AI 内部用完就丢，用户点开卡片只能看到参数属性；
     * 采集侧从工具返回的 JSON 里提取并截断（见 toolOutputOf），随消息本地持久化。
     */
    val output: String = "",
    /**
     * 编辑类工具（edit_file / write_file / append_file / apply_patch）的**变更预览**：
     * 每行带 `- ` / `+ ` 前缀的迷你 diff，其余工具为空串。
     * 由参数里的 old_string / new_string / content / patch 直接算出，不需要读整个文件。
     */
    val diff: String = ""
)
    companion object {
        // 本地乐观消息 id 恒为负数，保证永远小于服务端正整数自增 id。
        // 背景：消息增量游标 afterId 一旦被本地乐观消息的巨正数 id（System.currentTimeMillis() 量级）污染，
        // 会导致服务端增量拉取永远返回空、会话内收不到新消息、必须退出重进（与拍一拍路径 :5009-5012 同因）。
        // 改为负数后，即使游标仍从 messages 取，也不会被撑大；最终游标统一改由 adapter.store 提供（见 ChatConversationScreen.serverCursor）。
        private var nextId = -(System.currentTimeMillis() % 900000L) - 1L
        fun create(text: String, isMine: Boolean, fromUserId: Long = 0, serverId: Long = 0, isNew: Boolean = false, senderName: String = "", createdAt: Long = System.currentTimeMillis() / 1000, isRevoked: Int = 0, isSystemNotice: Boolean = false, replyToText: String = "", replyToSender: String = "", replyToId: Long = 0, mediaType: String = "", mediaUrl: String = "", isUploading: Boolean = false, flashDuration: Int = 0, toUserId: Long = 0, targetName: String = "", reasoningText: String = "", thinkingSeconds: Long = 0, totalSeconds: Long = 0, inputTokens: Long = 0, outputTokens: Long = 0, cacheHitTokens: Long = 0, contextLength: Long = 0, aiCostDeducted: Long = 0, aiMode: String = "", manualStopped: Boolean = false, broadcastTaskId: Long = 0, contactSource: String = "", isToolStatus: Boolean = false, aiToolStage: String = "", aiToolStageState: Int = -1, aiToolStages: List<ToolStageRecord> = emptyList(), aiFileChanges: String = "", aiToolCallCount: Int = 0, aiProgressHint: String = "", aiUsageAuthoritative: Boolean = false, mediaWidth: Int = 0, mediaHeight: Int = 0, localPreviewUri: String = ""): ChatMsg {
            // 媒体类型统一归一化：本机记录 > URL 后缀 > 服务端字段，全部在本地算，断网也判得出。
            // 历史上这套判定在 5 处 MessageData 构造 + 会话列表预览里各写一遍，标准互不一致，
            // 才会出现「同一个视频：进对话是视频、列表预览变 [消息]」。收敛到下面这一个函数。
            val normalizedType = normalizeMediaType(mediaType, mediaUrl, serverId)
            return ChatMsg(
                id = if (serverId > 0) serverId else nextId--,
                serverId = serverId,
                text = text, isMine = isMine, fromUserId = fromUserId, isNew = isNew, senderName = senderName, createdAt = createdAt, isRevoked = isRevoked, isSystemNotice = isSystemNotice, replyToText = replyToText, replyToSender = replyToSender, replyToId = replyToId, mediaType = normalizedType, mediaUrl = mediaUrl, mediaWidth = mediaWidth, mediaHeight = mediaHeight, isUploading = isUploading, localPreviewUri = localPreviewUri, flashDuration = flashDuration, toUserId = toUserId, targetName = targetName, reasoningText = reasoningText, thinkingSeconds = thinkingSeconds, totalSeconds = totalSeconds, inputTokens = inputTokens, outputTokens = outputTokens, cacheHitTokens = cacheHitTokens, contextLength = contextLength, aiCostDeducted = aiCostDeducted, aiMode = aiMode, manualStopped = manualStopped, aiUsageAuthoritative = aiUsageAuthoritative, broadcastTaskId = broadcastTaskId, contactSource = contactSource, isToolStatus = isToolStatus, aiToolStage = aiToolStage, aiToolStageState = aiToolStageState, aiToolStages = aiToolStages, aiFileChanges = aiFileChanges, aiToolCallCount = aiToolCallCount, aiProgressHint = aiProgressHint
                )
        }
    }
}

/** 图片扩展名白名单（含 .heic/.heif：iPhone 直传的默认格式）。 */
internal val IMAGE_MEDIA_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

/** 视频扩展名白名单。 */
internal val VIDEO_MEDIA_EXTS = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg", "m4v", "flv", "wmv")

/**
 * 明确的「非媒体」扩展名。
 *
 * 为什么需要它：后端历史上把文件类消息的 media_type 一律存成 jpg，
 * 于是 APK / TXT / PDF 会被渲染成一张加载不出来的图片。只要后缀能认出是这类文件，
 * 就无视服务端字段，直接归为普通文件。
 */
internal val NON_MEDIA_EXTS = setOf(
    "apk", "aab", "apks", "xapk", "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "zip", "rar", "7z", "gz", "tar", "bz2", "xz", "json", "xml", "csv", "md", "log", "ini", "conf",
    "html", "htm", "js", "css", "kt", "java", "py", "go", "rs", "c", "cpp", "h", "sh", "bat",
    "exe", "msi", "dmg", "iso", "img", "bin", "dat", "db", "sqlite", "ttf", "otf", "woff", "woff2",
    "amr", "m4a", "aac", "mp3", "wav", "ogg", "flac" // 音频同样不是图片/视频，走文件卡片
)

/**
 * 从 mediaUrl 里取「像样的」扩展名；取不到返回空串。
 *
 * 这里刻意比 `substringAfterLast('.')` 严格：域名（`cdn.a.com/x`）、版本号（`v1.2/x`）、
 * IP:端口（`1.2.3.4:8080/x`）都会让暴力切分拿到一串假扩展名，进而把正常图片判成文件。
 * 只认「最后一段路径里、点后面 1~6 位纯字母数字」这种形态。
 */
fun mediaUrlExt(url: String): String {
    if (url.isEmpty()) return ""
    val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
    val seg = path.substringAfterLast('/')
    val dot = seg.lastIndexOf('.')
    if (dot <= 0 || dot == seg.length - 1) return ""
    val ext = seg.substring(dot + 1).lowercase()
    if (ext.length !in 1..6) return ""
    if (!ext.all { it in 'a'..'z' || it in '0'..'9' }) return ""
    return ext
}

/** 依据 mediaUrl 的扩展名判断是否为【确定】的图片。 */
fun isImageMediaUrl(url: String): Boolean = mediaUrlExt(url) in IMAGE_MEDIA_EXTS

/** 依据 mediaUrl 的扩展名判断是否为【确定】的视频。 */
fun isVideoMediaUrl(url: String): Boolean = mediaUrlExt(url) in VIDEO_MEDIA_EXTS

/**
 * 媒体类型的**唯一判定入口**。全部在本地算，不依赖服务端返回的字段，断网同样成立。
 *
 * 判定顺序（越靠前越可信）：
 * 1. **本机记录**：自己发出的媒体，发送时把选择器给出的真实 MIME 记在 `LocalMediaTypeStore`，
 *    这是唯一「确定」的事实，压过一切。
 * 2. **URL 协议 / 特征**：`sticker://` 固定是表情，纯 emoji 字符、`voice` 先拦（.m4a 既非图也非视频）。
 * 3. **URL 后缀**：视频 → video，图片 → image；能认出是 APK/PDF/TXT 等其他文件 → file。
 * 4. **服务端字段兜底**：只有在「URL 没有可识别后缀」时才用，此时本地确实无从判断。
 *
 * @param msgId 服务端消息 id（用于查本机记录；本地乐观消息传 0 即可）
 */
fun normalizeMediaType(mediaType: String, mediaUrl: String, msgId: Long = 0L): String {
    // 1) 本机记录：自己发出的媒体，发送时按真实 MIME 分类过（image/video/voice/gif/file）。
    //    它比服务端字段可信，但要**当作输入参与归一化**而不是直接返回——
    //    直接返回会把 "gif"/"jpg"/"mp4" 这类别名漏到渲染层，气泡分支只认 image/video 会渲染成空白。
    val known = if (msgId > 0L) com.aurora.chat.data.local.LocalMediaTypeStore.get(msgId) else null
    val t = (known?.takeIf { it.isNotEmpty() } ?: mediaType).trim().lowercase()

    // 2) 协议/特征类，先拦住，否则会被后面的后缀判定降级
    if (mediaUrl.startsWith("sticker://") || t == "sticker") return "sticker"
    if (t == "emoji" || isEmojiMediaUrl(mediaUrl)) return "emoji"
    if (t == "voice") return "voice"

    val ext = mediaUrlExt(mediaUrl)
    // 3) 后缀是本地唯一能独立确认的事实，优先于服务端字段
    if (ext in VIDEO_MEDIA_EXTS) return "video"
    if (ext in IMAGE_MEDIA_EXTS) return "image"
    // 服务端把非媒体文件误标成 image/jpg 时，靠后缀把它压回文件卡片
    if (ext in NON_MEDIA_EXTS) return "file"

    // 4) URL 没有可识别后缀：只能用类型字段兜底（此处可能是本机记录，也可能是服务端值）
    return when {
        t.startsWith("video/") || t == "mp4" || t == "mov" -> "video"
        t.startsWith("image/") || t == "jpg" || t == "jpeg" || t == "png" || t == "gif" -> "image"
        t == "file" -> "file"
        mediaUrl.isEmpty() -> mediaType
        // 有 URL 但既无后缀、类型也为空或不可识别：按文件卡片处理，至少可点开，不会渲染成空白
        else -> "file"
    }
}
