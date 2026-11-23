package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import coil.compose.AsyncImage
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.FavoritesStore
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.viewmodel.ChatViewModel
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.chat.MessageBubble
import com.aurora.chat.ui.components.AnimationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.rememberCoroutineScope

// 缓存时间格式化器，避免每次调用创建新对象
private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
private val DATE_FMT = SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.CHINA)
private val YEAR_FMT = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA)
// 毫秒精度时间格式化器（消息信息面板专用：区分到达时间与发生时间）
private val TIME_MS_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
private val DATE_MS_FMT = SimpleDateFormat("MM月dd日 HH:mm:ss.SSS", Locale.CHINA)
private val YEAR_MS_FMT = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss.SSS", Locale.CHINA)
private val DAY_NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

// 已播放过「新消息滑入」动画的消息 id（全局去重）：保证每条新消息只滑一次，
// 翻看历史时 item 重新进入组合也不会重放 —— 吸取当年"所有 item 进出都动画"导致滑动卡的坑。
private val playedEnterIds = mutableSetOf<Long>()

/**
 * 独立的「AI 工具调用状态」行（无头像、无气泡）：居中显示一行纯文本。
 * state=0「正在调用…」(蓝) / 1「调用成功」(绿) / 2「调用失败」(红)。文字颜色按状态区分，不显示彩色圆点或对错符号。
 * 作为独立消息随消息列表渲染与持久化，绝不消失。
 */
@Composable
private fun ToolStatusRow(state: Int, tool: String, target: String = "") {
    // 多 AI 元工具:专属文案 + 实时状态(验收轮数 / 并行完成进度)
    val isReview = tool == "agent_review"
    val isFork = tool == "agent_fork"
    val isAsk = tool == "ask_user"
    val isAI = isReview || isFork
    val liveStatus by if (isAI && state == 0) {
        (if (isFork) com.aurora.chat.ui.chat.AiChatManager.AIForkBus.live
        else com.aurora.chat.ui.chat.AiChatManager.AIReviewBus.live).collectAsState()
    } else remember { mutableStateOf<String?>(null) }
    // ask_user 进行中且仍有挂起请求:状态行可点击,重新展开被收起的提问面板(带着上次作答草稿);
    // 面板被收起时右侧追加「继续回答」徽标,提示用户可以点回来
    val askState by if (isAsk && state == 0) {
        com.aurora.chat.ui.chat.AiChatManager.AskUserBus.pending.collectAsState()
    } else remember { mutableStateOf<com.aurora.chat.ui.chat.AiChatManager.AskUserBus.Request?>(null) }
    val askHidden by if (isAsk && state == 0) {
        com.aurora.chat.ui.chat.AiChatManager.AskUserBus.hidden.collectAsState()
    } else remember { mutableStateOf(true) }
    val askCanResume = askState != null && askHidden
    val text = when {
        isFork && state == 0 -> "并行派发中,多个子执行 AI 同时干活…"
        isFork && state == 1 -> "并行子任务全部完成"
        isFork -> "并行派发失败"
        isReview && state == 0 -> "验收 AI 接手中…"
        isReview && state == 1 -> "验收 AI 核验完成"
        isReview -> "验收 AI 核验失败"
        isAsk && state == 0 -> "正在向用户提问"
        isAsk && state == 1 -> "已向用户提问"
        isAsk -> "提问未完成"
        // 普通工具：统一走唯一事实源（"正在读取文件…／已读取文件／读取文件失败"，未收录的回退通用句式）
        else -> toolStatusText(tool, state, target)
    }
    val stateColor = when (state) {
        0 -> Color(0xFF2F6FED)   // 蓝：正在调用
        1 -> Color(0xFF16A34A)   // 绿：调用成功
        else -> Color(0xFFDC2626) // 红：调用失败
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (askState != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) { com.aurora.chat.ui.chat.AiChatManager.AskUserBus.reopen() } else Modifier
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = stateColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            // 目标可能很长（绝对路径 / 命令）：单行省略，避免把整行撑成多行
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (askCanResume) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x142F6FED))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("继续回答", fontSize = 11.sp, color = Color(0xFF2F6FED), fontWeight = FontWeight.Medium)
            }
        }
        if (isReview && state == 0) {
            // 跳过审查:点击后验收 AI 在当前请求结束后立即中止,执行者会收到"用户跳过了验收"并如实告知
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x14DC2626))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null
                    ) { com.aurora.chat.ui.chat.AiChatManager.requestReviewSkip() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("跳过审查", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
            }
        }
    }
    if (isAI && state == 0 && !liveStatus.isNullOrBlank()) {
        Text(
            text = liveStatus!!,
            color = com.aurora.chat.ui.theme.appTextHint(),
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** 服务端撤回窗口：普通用户只能撤回自己发出后 2 分钟内的消息。 */
private const val RECALL_WINDOW_SECONDS = 120L

/**
 * 「撤回」图标：一条掉头的回旋箭头（底部左起 → 右侧半圆弯上去 → 顶端向左收，箭头朝左）。
 * 不用 Material 的 Undo / Replay：Undo 是"退回"感，Replay 是环形刷新，都不像撤回。
 * 线宽 2、圆头圆角，跟菜单里其它图标的观感一致；用 Icon(tint=...) 上色即可。
 */
internal val RecallIcon: ImageVector = ImageVector.Builder(
    name = "Recall",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    // 用 SVG 路径字符串构建（与 InputBar 里的自定义图标同一套写法）：
    // 底部左端 → 右 → 右侧半圆弯上去 → 顶端向左收 → 朝左箭头
    addPath(
        pathData = androidx.compose.ui.graphics.vector.PathParser()
            .parsePathString(
                "M4 18.5 H14.6 C17.03 18.5 19 16.07 19 14.1 " +
                    "C19 11.67 17.03 9.7 14.6 9.7 H9.2 " +
                    "M11.8 7.1 L9.2 9.7 L11.8 12.3"
            ).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    )
}.build()

/**
 * 这条消息此刻还能不能撤回 —— 决定长按菜单里「撤回」是否出现。
 *
 * 规则与服务端 /api/messages/recall 完全一致，避免出现「菜单里有、点了却提示已超过 2 分钟」：
 *  · 开发者（ID=1）：可撤回任何人的消息，不限时间；
 *  · 普通用户：只能撤回自己发的，且 2 分钟内；
 *  · 本地对话（AI）：撤回的是本地文件，没有服务端时限。
 *
 * createdAt 为 0 表示时间未知（刚发出的乐观上屏消息），不拦截。
 */
internal fun canRecallMessage(msg: IMessageRef, currentUserId: Long, friendId: Long): Boolean {
    if (currentUserId == 1L) return true
    if (!msg.isMine) return false
    if (isLocalChat(friendId)) return true
    if (msg.createdAt <= 0L) return true
    return System.currentTimeMillis() / 1000 - msg.createdAt <= RECALL_WINDOW_SECONDS
}

@Composable
internal fun MessageBubbleItem(
    msg: IMessageRef,
    loadMedia: Boolean = true,
    myAvatar: android.graphics.Bitmap?,
    currentUserId: Long,
    currentUserName: String = "",
    friendId: Long,
    friendName: String,
    animMode: AnimationMode,
    advancedAnim: Boolean = false,
    isMenuOpen: Boolean = false,
    onMenuOpen: () -> Unit = {},
    onMenuClose: () -> Unit = {},
    onAvatarClick: (userId: Long, userName: String) -> Unit,
    onDeleteMessage: (Long) -> Unit = {},
    onBroadcastManage: (msgId: Long, taskId: Long) -> Unit = { _, _ -> },
    /** 群管理专用：对群成员气泡提供「禁言」入口。null = 当前用户不是群主/管理员（或非群聊），不显示该入口 */
    onMuteMember: ((userId: Long, userName: String) -> Unit)? = null,
    /** 群待办：群主/管理员长按群消息时提供「群待办」入口。null = 非群主/管理员或非群聊，不显示 */
    onSetGroupTodo: ((IMessageRef) -> Unit)? = null,
    onCopyMessage: (String) -> Unit = {},
    /** 收藏：null = 不显示入口（如闪照、表情、协议卡片、系统通知） */
    onFavoriteMessage: ((IMessageRef) -> Unit)? = null,
    /** 分享单条消息（走「分享聊天」卡片，与多选→分享同一条链路）。null = 不显示入口 */
    onShareMessage: ((IMessageRef) -> Unit)? = null,
    onReplyTo: (IMessageRef) -> Unit = {},
    onEditMessage: ((IMessageRef) -> Unit)? = null,
    onMultiSelect: () -> Unit = {},
    multiSelectActive: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelected: () -> Unit = {},
    clearSelectionKey: Int = 0,
    isHighlighted: Boolean = false,
    onCapsuleClick: (Long) -> Unit = {},
    /** AI 工具调用状态列表（(工具名,状态：0=调用中 1=成功 2=失败)）：非空时在气泡下方逐条显示彩色工具状态行（蓝/绿/红） */
    aiToolStatus: List<Pair<String, Int>>? = null,
    onAtMention: (String) -> Unit = {},
    onAvatarLongPress: (Long, String) -> Unit = { _, _ -> },
    onAvatarDoubleTap: (Long, String) -> Unit = { _, _ -> },
    onAtMentionClick: (String) -> Unit = {},
    destroyedFlashIds: Set<Long> = emptySet(),
    onFlashDestroy: (Long, String) -> Unit = { _, _ -> },
    partnerViewedFlashIds: Set<Long> = emptySet(),
    /** 我发的闪照中，对方"正在查看中"的 id 集合（实时 TCP 推送更新） */
    viewingFlashIds: Set<Long> = emptySet(),
    /** 我开始查看对方的闪照（露出图片）时回调，用于上报"对方查看中" */
    onFlashViewStart: (Long) -> Unit = {},
    isGroup: Boolean = false,
    groupMemberNames: Set<String> = emptySet(),
    /** 本条消息发送者的群角色：owner=群主 / admin=群管理 / 其它=无徽章 */
    senderRole: String = "",
    onImageClick: (String) -> Unit = {},
    onBoundsMeasured: (String, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
    onFileClick: (String, String) -> Unit = { _, _ -> },
    onFileDownload: (String, String) -> Unit = { _, _ -> },
    /** 上传中媒体「撤回」：取消后台压缩/上传并移除占位气泡（参数为本地占位 id） */
    onCancelUpload: (Long) -> Unit = {},
    /**
     * 发送失败消息的重发入口：点击气泡左侧红色感叹号时回调。
     * 是否真正重发由上层弹「重新发送」确认框决定，气泡本身不直接发起请求。
     */
    onRetrySend: (IMessageRef) -> Unit = {},
    /** 文件引用点击回调：0=查看 1=所在位置（默认空实现保持 Toast） */
    onFileRefAction: ((String, Int) -> Unit) = { _, _ -> },
    /** 「分享聊天」卡片点击 → 打开全屏查看页（参数：消息 id、卡片原始文本；id 用于查看中被撤回时中断） */
    onOpenChatShare: (Long, String) -> Unit = { _, _ -> },
    /** 方案卡片「构建」按钮回调（照抄 CodeBuddy 方案卡） */
    onPlanBuild: (() -> Unit)? = null,
    /** 方案卡片「✎ 编辑」回调：仅未开工时可用（构建前可改方案） */
    onPlanEdit: (() -> Unit)? = null,
    /** 本条方案所属任务是否已整体完成：置 true 时流程步骤全部勾选为已完成 */
    planAllDone: Boolean = false,
    /** 任务流程卡（最新方案卡进度）：仅最新一条 AI 消息会被传入，渲染在交付总结上方 */
    livePlan: PlanCardData? = null,
    uploadProgressMap: Map<Long, Float> = emptyMap(),
    uploadStageMap: Map<Long, String> = emptyMap(),
    uploadRatioMap: Map<Long, Float> = emptyMap(),
    uploadFileNameMap: Map<Long, String> = emptyMap(),
    onOpenPostDetail: (Long) -> Unit = {},
    onOpenGroupDetail: (Long) -> Unit = {},
    onReasoningToggled: (Boolean) -> Unit = {},
    onCodeBackToTop: () -> Unit = {},
    onContinueAi: () -> Unit = {},
    onViewChanges: (() -> Unit)? = null,
    onToolCalls: (() -> Unit)? = null,
    /** 是否为会话最后一条消息：透传给气泡，用于约束「继续处理任务」按钮仅最后一条显示。 */
    isLastMessage: Boolean = false,
    /** 是否为本任务「固定工具日志」行的唯一归属气泡（多轮任务里只让最后一条 AI 气泡显示，避免重复） */
    isFixedLogOwner: Boolean = true,
    expandedReasoningMsgId: Long = 0,
    onRespondGroupInvite: ((InviteGroupCardData) -> Unit)? = null,
    replyIsRevoked: Boolean = false,
    /** 被引用消息是否为本人发送：为真时引用胶囊只显示内容、不显示发送者名字（群聊除外） */
    replyIsMine: Boolean = false,
    /** 被引用消息的发送者名（调用方按 id 解析后的结果；空则回退消息自带字段） */
    replySenderName: String = "",
    /** 被引用消息的媒体类型（如 "image"），用于引用胶囊显示图片缩略图 */
    replyMediaType: String = "",
    /** 被引用消息的媒体 URL */
    replyMediaUrl: String = "",
    /** 被引用语音的时长（秒）：>0 时引用胶囊里渲染小号语音气泡 */
    replyVoiceSeconds: Int = 0,
    /** 被引用消息的发出时间（毫秒）：显示在引用胶囊里被引用者名字右侧 */
    replyTimeMs: Long = 0,
    /** 我方气泡配色（默认微信绿），由 ChatConversationScreen 自 BubbleColorStorage 读取后下传 */
    myBubbleColor: Color = com.aurora.chat.ui.components.BubbleColorStorage.DEFAULT,
    /** 对方气泡配色（默认白色），仅本地视角生效 */
    otherBubbleColor: Color = com.aurora.chat.ui.components.BubbleColorStorage.DEFAULT_OTHER,
    /** 导出截图用：已解码的同步图片 Bitmap（key=resolveMediaUrl 后的 URL），
     *  非空时图片消息直接绘制不走 Coil，避免离屏导出画空 */
    exportMediaBitmaps: Map<String, ImageBitmap> = emptyMap(),
    /** 点击「短视频分享卡片」→ 打开短视频模块并定位到该视频（参数为视频 id） */
    onOpenShortVideo: (Long) -> Unit = {},
    /** 「已读显示 → 气泡左侧」：非空时在自己气泡左边画一行浅灰小字（已读/未读/N人已读） */
    readLabelLeft: String? = null,
    /** 左侧已读小字被点击：群聊=看已读名单，私聊=立刻重查一次。null = 不可点 */
    onReadLabelClick: (() -> Unit)? = null
) {

    val displayName = when {
        msg.isMine && isGroup -> currentUserName.ifEmpty { friendName }
        msg.isMine -> friendName
        isGroup && msg.fromUserId == friendId -> friendName
        // 消息自带的 senderName 缺失（旧数据 / 服务端漏字段）时，按 userId 从
        // 「看过的用户」缓存里补名字；纯内存读，不碰磁盘、不影响滚动帧率。
        isGroup -> msg.senderName.ifEmpty {
            com.aurora.chat.data.local.UserProfileStore.nameOf(msg.fromUserId).ifEmpty { friendName }
        }
        else -> friendName
    }

    // ── 消息滑入动画（设置 → 消息设置 → 消息动画）──
    // 规则按会话类型区分，与消息内容和体量无关：
    //  · 普通聊天：双方都参与；高级动画开启时，历史消息首次滑入视口也播放。
    //  · AI 对话：高级动画对双方都不生效（我的和 AI 的消息都不参与）；
    //    动画模式只对我发出的气泡生效，AI 发的消息不播放。
    // 非高级路径始终是"新消息只播一次"（playedEnterIds 去重），
    // 不会因为滚动进出组合而反复重播。
    val isAIChat = friendId == AI_CHAT_ID
    val modeApplies = animMode != AnimationMode.NONE && (!isAIChat || msg.isMine)
    val advancedApplies = advancedAnim && !isAIChat
    val shouldEnter = modeApplies && msg is ChatMsg &&
        (advancedApplies || (msg.isNew && msg.id !in playedEnterIds))
    val appearAnim = remember(msg.id) { Animatable(if (shouldEnter) 1f else 0f) }
    LaunchedEffect(msg.id, shouldEnter) {
        if (shouldEnter) {
            // 非高级路径：先等列表回底滚动（约 300ms，覆盖回底弹簧时长）再滑入，
            // 达成"先往下滑、气泡紧随其后滑出"，两者不重叠，右下斜滑在静止列表上更明显。
            // 高级路径不限新消息、进场即播，不加延迟。
            if (!advancedApplies) kotlinx.coroutines.delay(300)
            appearAnim.animateTo(0f, tween(227))
            if (!advancedApplies) playedEnterIds.add(msg.id)
        } else if (appearAnim.value != 0f) {
            appearAnim.snapTo(0f)
        }
    }

    // 独立「工具调用状态」消息：独立站位渲染，不与其他气泡合并。
    //
    // 【交互优化·用户明确要求】批量调用（例如连续创建 10 个文件，每个内容都不同、没法用脚本批量做）
    // 时，每完成一次就留下「调用成功 write_file」一行，几十行堆在聊天流里非常占空间。所以改为：
    //  ① 完成态(成功/失败)只做**短暂提示**：约 1.6 秒后自动隐藏；
    //     隐藏 = 该列表项渲染为空（零高度）→ 后面的内容自然顶上，**不会留下空行/空隙**；
    //  ② 重新进入会话时（历史消息 isNew=false）**完成行直接不显示**，不再刷屏；
    //     调用明细随时可在气泡底部「调用 N 个工具」里看（那里是完整的，含原因与精确时间）。
    //  ③ 进行中(state=0)一直显示到有结果；若长时间仍无结果（用户中断会话 / 进程被杀 / 上游异常），
    //     **判定为失败**，不允许永远停在「正在调用」（用户要求：不能确定成功就按失败处理）。
    if (msg is ChatMsg && msg.isToolStatus) {
        val tool = msg.text
        val rawState = msg.aiToolStageState
        // 工具调用行总开关 / 停留时长（AI 设置里可改；0 = 永不收回）
        val itemCtx0 = LocalContext.current
        val rowEnabled = AIUiPrefs.showToolRow(itemCtx0)
        val holdMs = AIUiPrefs.toolRowMs(itemCtx0)
        if (!rowEnabled) return
        // 多 AI 元工具与提问行：数量少、含义重（提问可能等用户很久、验收本身可能跑很久），
        // 保持原样渲染 —— 既不自动隐藏、也不做超时判失败。
        if (tool == "ask_user" || tool == "agent_review" || tool == "agent_fork" || tool == "agent_plan") {
            ToolStatusRow(state = rawState, tool = tool)
            return
        }
        // ③ 卡死兜底：90 秒还没有结果 → 按失败处理（只影响这一行的显示判定，不改已落盘的数据）
        var timedOut by remember(msg.id) { mutableStateOf(false) }
        LaunchedEffect(msg.id) {
            if (rawState == 0) {
                kotlinx.coroutines.delay(90_000)
                timedOut = true
            }
        }
        val state = if (rawState == 0 && timedOut) 2 else rawState
        // ①② 完成态只短暂提示；历史里已完成的直接不显示。
        // 初始值就按「该不该显示」定：历史完成行首帧即不可见，避免先闪一下再淡出。
        var visible by remember(msg.id) { mutableStateOf(state == 0 || msg.isNew) }
        LaunchedEffect(msg.id, state, holdMs) {
            if (state != 0) {
                if (!msg.isNew) {
                    visible = false            // 历史/重进会话：立刻不占位
                } else if (holdMs > 0) {
                    // 停留时长由「AI 设置 → 工具调用行」决定，默认仍是 1600ms
                    kotlinx.coroutines.delay(holdMs.toLong())
                    visible = false
                } else {
                    // 0 = 永不收回（用户要求：填 0 就一直留在对话里）
                    visible = true
                }
            }
        }
        // 退场改为淡出+轻微上收,不再「啪」地消失(用户要求:不突兀);收完高度归零,不留空行
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.fadeIn(tween(180)),
            exit = androidx.compose.animation.fadeOut(tween(260)) +
                androidx.compose.animation.shrinkVertically(tween(260))
        ) {
            ToolStatusRow(state = state, tool = tool)
        }
        return
    }

    @Composable
    fun BubbleWithMenu() {
        var isSelecting by remember { mutableStateOf(false) }
        val ctx = LocalContext.current
        // 记录气泡在窗口中的位置，供长按菜单（微信/QQ 风格浮出小卡片）贴近气泡定位
        var bubbleBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

        // 点击空白区域退出选择模式
        LaunchedEffect(clearSelectionKey) {
            if (clearSelectionKey > 0 && isSelecting) {
                isSelecting = false
            }
        }
        // 引用跳转高亮：整行区块闪烁（沿用多选选中同款蓝色块，保证图片/卡片等非气泡消息也清晰可见）
        val highlightFlash = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(isHighlighted) {
            if (isHighlighted) {
                highlightFlash.snapTo(0f)
                kotlinx.coroutines.delay(50)
                repeat(3) {
                    highlightFlash.animateTo(0.28f, androidx.compose.animation.core.tween(200))
                    highlightFlash.animateTo(0f, androidx.compose.animation.core.tween(200))
                }
            }
        }
        // 用透明盒子包裹气泡，捕获其在窗口中的坐标，供长按菜单定位
    Box(
        Modifier
            .fillMaxWidth()
            // 新消息滑入：按消息动画模式做位移（graphicsLayer 走绘制层，不重组不重排）
            .graphicsLayer {
                when (animMode) {
                    // 左右滑出：从右侧横滑入
                    AnimationMode.LEFT_RIGHT -> translationX = appearAnim.value * size.width
                    // 右下滑出：从右下角斜滑入（横 + 纵都偏移）
                    AnimationMode.BOTTOM_RIGHT -> {
                        translationX = appearAnim.value * size.width
                        translationY = appearAnim.value * size.height
                    }
                    else -> {}
                }
            }
            .onGloballyPositioned { bubbleBounds = it.boundsInWindow() }
            // 引用跳转高亮：画在内容「之下」的背景层（drawBehind 先绘制、内容再叠上去），
            // 因此不会盖住头像/群昵称徽章/卡片/气泡本身，只在行内空隙处透出；
            // 左右各外扩 12dp 抵消 LazyColumn 的 contentPadding，让高亮铺满整行到屏幕边缘。
            .drawBehind {
                val a = highlightFlash.value
                if (a > 0.01f) {
                    val extX = 12.dp.toPx()
                    // 群聊里消息内容列会被整体上移 10dp（MessageBubble 内 groupConvId<0 的
                    // 视觉补偿）。高亮块若还按未偏移的布局区域铺，上沿就会切进群昵称和
                    // 角色徽章中间（「包不住徽章」），下沿则凭空多出一段。这里跟着平移。
                    val contentShiftUp = if (isGroup && friendId < 0L) 10.dp.toPx() else 0f
                    // 上下各留 8dp 呼吸量，别贴着头像/昵称/卡片边缘
                    val padY = 8.dp.toPx()
                    val w = this.size.width
                    val h = this.size.height
                    val top = -contentShiftUp - padY
                    val bottom = h - contentShiftUp + padY
                    drawRect(
                        color = Color(0xFF2F6FED).copy(alpha = a),
                        topLeft = Offset(-extX, top),
                        size = Size(w + extX * 2f, bottom - top)
                    )
                }
            }
    ) {        // 垂直堆叠：气泡占用一行，工具状态行独占下方一行，避免相互覆盖重叠
        // 多选时内容整体右移，给最左侧的勾选圈让位（圈本身仍贴在行首 x=0）
        Column(Modifier.padding(start = if (multiSelectActive) 30.dp else 0.dp)) {
        // ── AI 对话字号（AI 设置 → 字号）──
        // 用 LocalDensity.fontScale 整体缩放整块气泡：正文里所有 sp 一起变，
        // 不会出现「有的字号改了、有的还是老大小」的错位。1f = 默认，等于旧行为。
        val aiFontScale = if (friendId == AI_CHAT_ID) {
            AIUiPrefs.fontScale(LocalContext.current)
        } else 1f
        val bubbleBody: @Composable () -> Unit = {
        // 「选择」模式下给整条消息挂一个一次性自动全选标记：第一个文字区自动全选并弹出
        // 系统选择菜单，之后的分段不再重复全选（避免多个菜单互相抢焦点）。
        val autoSelFlag = remember(isSelecting) {
            if (isSelecting) com.aurora.chat.ui.text.AutoSelectAllFlag() else null
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.aurora.chat.ui.text.LocalAutoSelectAll provides autoSelFlag
        ) {
        MessageBubble(text = msg.text, isMine = msg.isMine, fromUserId = msg.fromUserId, myAvatar = myAvatar, displayName = displayName, showSenderName = isGroup && displayName.isNotEmpty(), onAvatarClick = onAvatarClick,
        groupConvId = if (isGroup) friendId else 0, currentUserId = currentUserId,
            onBubbleTap = { if (!multiSelectActive) { onMenuClose(); isSelecting = false } },
            onBubbleLongPress = { isSelecting = false; if (!multiSelectActive) onMenuOpen() },
            sendFailed = msg.sendFailed,
            onRetrySend = { onRetrySend(msg) },
            selectable = isSelecting, replyToText = msg.replyToText,
            replyToSender = replySenderName.ifBlank { msg.replyToSender },
            replyToId = msg.replyToId, replyIsRevoked = replyIsRevoked, replyIsMine = replyIsMine, replyMediaType = replyMediaType, replyMediaUrl = replyMediaUrl, replyVoiceSeconds = replyVoiceSeconds, replyTimeMs = replyTimeMs, onCapsuleClick = onCapsuleClick,
            onAvatarLongPress = onAvatarLongPress,
            onAvatarDoubleTap = onAvatarDoubleTap,
            onAtMentionClick = onAtMentionClick,
            isGroup = isGroup,
            // 群聊：把这条消息自带的 @ 目标（发送时昵称快照）并入有效成员名，
            // 这样对方改名后历史 @ 仍然高亮可点，而不是变成灰色「找不到用户」。
            groupMemberNames = if (isGroup && msg.mentions.isNotEmpty()) groupMemberNames + msg.mentions.keys else groupMemberNames,
            senderRole = senderRole,
            loadMedia = loadMedia,
            mediaType = msg.mediaType,
            mediaUrl = msg.mediaUrl,
            mediaWidth = msg.mediaWidth,
            mediaHeight = msg.mediaHeight,
            localPreviewUri = msg.localPreviewUri,
            isUploading = msg.isUploading,
            uploadProgress = uploadProgressMap[msg.id] ?: 0f,
            uploadStage = uploadStageMap[msg.id] ?: "",
            uploadRatio = uploadRatioMap[msg.id] ?: 0f,
            uploadFileName = uploadFileNameMap[msg.id] ?: "",
            onImageClick = onImageClick,
            onBoundsMeasured = onBoundsMeasured,
            onFileClick = onFileClick,
            onFileDownload = onFileDownload,
            onCancelUpload = onCancelUpload,
            onFileRefAction = onFileRefAction,
            onPlanBuild = onPlanBuild,
            onPlanEdit = onPlanEdit,
            planAllDone = planAllDone,
            livePlan = livePlan,
            onOpenChatShare = { cardText -> onOpenChatShare(msg.id, cardText) },
            flashDuration = msg.flashDuration,
            reasoningText = msg.reasoningText,
            msgId = msg.id,
            onOpenPostDetail = onOpenPostDetail,
            onOpenGroupDetail = onOpenGroupDetail,
            onOpenShortVideo = onOpenShortVideo,
            destroyedFlashIds = destroyedFlashIds,
            onFlashDestroy = onFlashDestroy,
            partnerViewedFlashIds = partnerViewedFlashIds,
            viewingFlashIds = viewingFlashIds,
            onFlashViewStart = onFlashViewStart,
            onReasoningToggled = onReasoningToggled,
            expandedReasoningMsgId = expandedReasoningMsgId,
            thinkingSeconds = msg.thinkingSeconds,
            totalSeconds = msg.totalSeconds,
            inputTokens = msg.inputTokens,
            outputTokens = msg.outputTokens,
            cacheHitTokens = msg.cacheHitTokens,
            contextLength = msg.contextLength,
            aiCostDeducted = msg.aiCostDeducted,
            aiMode = (msg as? ChatMsg)?.aiMode ?: "",
            aiFlatBubble = false,
            manualStopped = msg.manualStopped,
            aiUsageAuthoritative = msg.aiUsageAuthoritative,
            aiWaiting = (msg as? ChatMsg)?.aiWaiting ?: false,
            aiProgressHint = (msg as? ChatMsg)?.aiProgressHint ?: "",
            isLastMessage = isLastMessage,
            isFixedLogOwner = isFixedLogOwner,
            onContinueClick = onContinueAi,
            onCodeBackToTop = onCodeBackToTop,
            onRespondGroupInvite = onRespondGroupInvite,
            aiFileChanges = (msg as? ChatMsg)?.aiFileChanges ?: "",
            // 真实工具调用记录 → 交付卡片用它做客观状态修正（工具报错却显示"已完成"的那个问题）
            toolStages = (msg as? ChatMsg)?.aiToolStages ?: emptyList(),
            // 优先用采集层真实计数的 aiToolCallCount;旧消息无该字段时回退 aiToolStages 条数
            toolCallCount = maxOf((msg as? ChatMsg)?.aiToolCallCount ?: 0, (msg as? ChatMsg)?.aiToolStages?.size ?: 0),
            onViewChanges = onViewChanges,
            onToolCalls = onToolCalls,
            myBubbleColor = myBubbleColor,
            otherBubbleColor = otherBubbleColor,
            exportMediaBitmaps = exportMediaBitmaps,
            readLabelLeft = readLabelLeft,
            onReadLabelClick = onReadLabelClick
        )
        }
        }
        val uiCtx = LocalContext.current
        // ── 独立思考气泡（显示思考）：默认收回，点标题行展开；与正文气泡分开站位 ──
        if (isAIChat && !msg.isMine && AIUiPrefs.showReasoning(uiCtx) && msg.reasoningText.isNotBlank()) {
            AiThinkingBubble(msg.reasoningText, msg.id)
        }
        // 正文尚未开始（或整段显示还没放开）的占位消息：不画头像、不画空气泡。
        val bodyBlank = remember(msg.text) { messageEditableBody(msg.text).isBlank() }
        if (!isAIChat || msg.isMine || !bodyBlank) {
            DragToQuoteBox(msg = msg, onReplyTo = onReplyTo) {
                if (aiFontScale != 1f) {
                    val d = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(d.density, d.fontScale * aiFontScale)
                    ) { bubbleBody() }
                } else {
                    bubbleBody()
                }
            }
        }
        // ── AI 消息区元素（时间 / 字数 / 复制，默认全关 = 不加任何东西）──
        if (friendId == AI_CHAT_ID && !msg.isMine) {
            AIMsgMetaRow(msg = msg, onCopy = { onCopyMessage(msg.text) })
        }
        // ── 本次消耗（显示消耗）：气泡下方一行，只显示数据 ──
        if (friendId == AI_CHAT_ID && !msg.isMine && AIUiPrefs.showUsage(uiCtx) &&
            (msg.inputTokens > 0L || msg.outputTokens > 0L)) {
            AiUsageLine(msg)
        }
        // 工具调用动态状态行（独占一行，位于气泡下方）：蓝=正在调用（三点轮换）/ 绿=调用成功 / 红=调用失败
        // 历史记录逐条渲染，每次调用的完成态都留存展示，不被下一次调用覆盖
        aiToolStatus?.forEach { (tool, state) ->
            AiToolStatusRow(tool = tool, state = state)
        }
        }
        // 引用跳转高亮已改为消息根容器的 drawBehind 背景层（见上），不再在此处叠一块覆盖内容的高亮 Box
        // 多选模式：整行点击切换选中，勾选圈统一贴左边（自己/对方都一致）
        if (multiSelectActive) {
            // 整行点击层（不加任何底色）
            //
            // 这里以前会用 Canvas 画一块超出自身边界的浅蓝圆角块来标「已选中」，
            // 但相邻两条都选中时两块会上下交叠，群聊带昵称时更明显。
            // 现在只靠左边的勾选圈表达选中态，行本身不画区域块。
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onToggleSelected() }
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 3.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    // 选中 = 品牌深蓝；未选中 = 半透明深灰（保持原样）
                    .background(if (isSelected) Color(0xFF1E40AF) else Color(0x40000000), CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onToggleSelected() },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) Icon(Icons.Filled.Check, contentDescription = "已选", tint = Color.White, modifier = Modifier.size(14.dp))
                else Text("✓", color = Color.White, fontSize = 12.sp)
            }
        }
        } // Box(onGloballyPositioned)

        // ==================== 消息信息（纯全屏界面）：右切入 / 毫秒时间 / 已读明细 / 丰富信息 ====================
        var showMsgInfoDialog by remember { mutableStateOf(false) }
        var msgInfoData by remember { mutableStateOf<JSONObject?>(null) }
        if (showMsgInfoDialog) {
            // 紧凑弹窗（替代原全屏面板）：只展示 发送时间 + 已读状态/已读时间；点外部或返回关闭
            Dialog(onDismissRequest = { showMsgInfoDialog = false }) {
                val info = msgInfoData
                if (info != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .heightIn(max = 560.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(com.aurora.chat.ui.theme.appSurface())
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp)
                    ) {
                        Text("消息信息", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                        Spacer(Modifier.height(14.dp))

                        fun formatTimeMs(ts: Long): String {
                            if (ts <= 0) return "未知"
                            val calNow = Calendar.getInstance()
                            val calMsg = Calendar.getInstance().apply { timeInMillis = ts }
                            val sameDay = calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) &&
                                calNow.get(Calendar.DAY_OF_YEAR) == calMsg.get(Calendar.DAY_OF_YEAR)
                            val isYesterday = {
                                val y = Calendar.getInstance()
                                y.add(Calendar.DAY_OF_YEAR, -1)
                                calMsg.get(Calendar.YEAR) == y.get(Calendar.YEAR) &&
                                    calMsg.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
                            }()
                            val isDayBefore = {
                                val db = Calendar.getInstance()
                                db.add(Calendar.DAY_OF_YEAR, -2)
                                calMsg.get(Calendar.YEAR) == db.get(Calendar.YEAR) &&
                                    calMsg.get(Calendar.DAY_OF_YEAR) == db.get(Calendar.DAY_OF_YEAR)
                            }()
                            val weekDay = calMsg.get(Calendar.DAY_OF_WEEK) - 1
                            val d = java.util.Date(ts)
                            return when {
                                sameDay -> TIME_MS_FMT.format(d)
                                isYesterday -> "昨天 " + TIME_MS_FMT.format(d)
                                isDayBefore -> "前天 " + TIME_MS_FMT.format(d)
                                calNow.get(Calendar.WEEK_OF_YEAR) == calMsg.get(Calendar.WEEK_OF_YEAR) &&
                                    calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) ->
                                    DAY_NAMES[weekDay] + " " + TIME_MS_FMT.format(d)
                                calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) ->
                                    DATE_MS_FMT.format(d)
                                else -> YEAR_MS_FMT.format(d)
                            }
                        }
                        @Composable
                        fun divider() {
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                            Spacer(Modifier.height(10.dp))
                        }
                        // 服务器侧时间均为秒级精度，去掉虚假的毫秒尾巴
                        fun formatServerTime(ts: Long): String = formatTimeMs(ts).substringBefore(".")

                        // 发送时间：优先客户端发送本机毫秒（client_send_at），缺失回退服务端接收时间（created_at）
                        val sendAtMs = info.optLong("client_send_at", 0)
                            .let { if (it > 0) it else info.optLong("created_at", 0) * 1000L }
                        InfoRow("发送时间", formatServerTime(sendAtMs))

                        if (info.optLong("to_user_id", 0) < 0) {
                            // 群聊：已读人数 + 已读成员（各带已读时间）
                            val readCount = info.optInt("read_count", 0)
                            val memberCount = info.optInt("group_member_count", 0)
                            divider()
                            InfoRow("已读人数", readCount.toString() + " / " + memberCount.toString() + " 人")
                            val readUsers = info.optJSONArray("read_users")
                            if (readUsers != null && readUsers.length() > 0) {
                                Spacer(Modifier.height(10.dp))
                                Text("已读成员（" + readUsers.length() + " 人）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                                Spacer(Modifier.height(6.dp))
                                for (i in 0 until readUsers.length()) {
                                    val ru = readUsers.getJSONObject(i)
                                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF34C759)))
                                        Spacer(Modifier.width(8.dp))
                                        Text(ru.optString("nickname", "用户"), fontSize = 14.sp, color = Color(0xFF111827), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(formatServerTime(ru.optLong("read_at", 0) * 1000L), fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    }
                                }
                            }
                        } else {
                            // 私聊：已读状态 + 已读时间
                            val readAtMs = info.optLong("other_read_at", 0) * 1000L
                            val isRead = info.optString("other_read_status", "unread") == "read" && readAtMs > 0
                            divider()
                            InfoRow("已读状态", if (isRead) "已读" else "对方未读")
                            if (isRead) {
                                divider()
                                InfoRow("已读时间", formatServerTime(readAtMs))
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(com.aurora.chat.ui.theme.appSurface())
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    ) {
                        Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                }
            }
        }

        // 长按菜单：微信/QQ 风格 —— 贴近气泡浮出的小卡片菜单（优先显示在气泡上方，空间不足时显示在下方）
        if (isMenuOpen && !isSelecting) {
            // 注意：不能用 rememberCoroutineScope() 绑定到菜单 Composable，
            // 因为 onMenuClose() 会立刻触发 Composable 移出 Composition → scope 被取消 →
            // recallMessage / getMessageInfo 请求还没发出去就被 LeftCompositionCancellationException 打断。
            // 这里改用全局 ChatViewModel.backgroundScope，让请求脱离 UI 生命周期。
            val menuScope = ChatViewModel.backgroundScope
            val speaking = TtsHelper.isSpeaking.value
            val density = LocalDensity.current
            Popup(
                onDismissRequest = { onMenuClose() },
                // focusable=false：菜单窗口不抢焦点、不吃外部触摸，
                // 这样在菜单已弹出时长按另一个气泡，手势能落到下面的消息上，
                // 从而"旧菜单收回 + 新菜单弹出"同时发生（QQ 效果）。
                // 点空白/滚动收起由列表手势负责，返回键由 BackHandler 负责。
                properties = PopupProperties(focusable = false),
                popupPositionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        // 以实际气泡在窗口中的位置为准（bubbleBounds 由 onGloballyPositioned 捕获，弹窗锚点本身只是所在行）
                        val ab = if (bubbleBounds.width > 0f && bubbleBounds.height > 0f)
                        IntRect(bubbleBounds.left.toInt(), bubbleBounds.top.toInt(), bubbleBounds.right.toInt(), bubbleBounds.bottom.toInt())
                    else anchorBounds
                        val m = (8 * density.density).toInt()
                        val menuW = popupContentSize.width
                        val menuH = popupContentSize.height
                        val abTop = ab.top
                        val abBottom = ab.bottom
                        // 气泡上方放得下就显示在气泡上方，否则显示在气泡下方
                        var y = (abTop - m - menuH).coerceAtLeast(0)
                        if (abTop - menuH < 0) {
                            y = (abBottom + m).coerceAtMost((windowSize.height - menuH).coerceAtLeast(0))
                        }
                        // 水平方向尽量对齐气泡中心，并夹在窗口左右边界内
                        val x = (ab.center.x - menuW / 2)
                            .coerceIn(0, (windowSize.width - menuW).coerceAtLeast(0))
                        return IntOffset(x, y)
                    }
                }
            ) {
                // 入场动画：快速弹簧缩放（有回弹灵动但紧绷利落） + 淡入
                val scale = remember { Animatable(0.88f) }
                val alpha = remember { Animatable(0f) }
                LaunchedEffect(Unit) { alpha.animateTo(1f, tween(100)) }
                LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }

                val entries = listOf<MenuEntry>().toMutableList().apply {
                    if (onEditMessage != null) add(MenuEntry("修改", Icons.Filled.Edit) { onEditMessage?.invoke(msg); onMenuClose() })
                    // 「复制」「选择」只给真正有可读文本的消息。
                    // 图片/视频/文件/语音/闪照的 text 是内部占位（时长、空串），复制出来是 "[图片]" 或 "1"，
                    // 也没有文字可供长按选择；位置/分享/邀请/隐私请求/清空请求/红包/转账的 text 是协议 JSON，
                    // 复制出去是一串不可读的标记。这些场景两项一律不出现。
                    if (hasCopyableText(msg)) {
                        add(MenuEntry("复制", Icons.Filled.ContentCopy) {
                            onCopyMessage(msg.text)
                            onMenuClose()
                        })
                        add(MenuEntry("选择", Icons.Filled.CheckCircle) {
                            onMenuClose()
                            // 长按菜单里点「选择」= 直接进入文字选择模式（气泡切为 SelectionContainer，
                            // 可拖手柄选局部文字并用系统工具条复制），不再弹解释框。
                            isSelecting = true
                        })
                    }
                    add(MenuEntry("引用", Icons.Filled.FormatQuote) { onReplyTo(msg); onMenuClose() })
                    // 收藏：文本/图片/视频/语音/文件均可；闪照、表情、系统通知、已撤回、上传中、协议卡片不显示。
                    // 图片/视频等媒体收藏时会复制一份到本机，服务器 30 天清理后仍可查看。
                    if (onFavoriteMessage != null && FavoritesStore.canFavorite(msg)) {
                        add(MenuEntry("收藏", Icons.Filled.StarBorder) {
                            onMenuClose()
                            onFavoriteMessage?.invoke(msg)
                        })
                    }
                    // 分享：单条消息也直接走「分享聊天」卡片，不用先进多选再勾选。
                    // 过滤规则与多选分享完全一致：闪照（含已销毁）、已撤回、系统通知不出现。
                    if (onShareMessage != null && isShareableChatMessage(msg, destroyedFlashIds)) {
                        add(MenuEntry("分享", Icons.Filled.Share) {
                            onMenuClose()
                            onShareMessage?.invoke(msg)
                        })
                    }
                    // 语音消息：「转文字」跟在「引用」后面（不放首位，避免误触；点开的概率比引用低）。
                    // 走系统语音识别把语音文件转成文字，结果卡片显示在语音气泡下方；
                    // 键与渲染层共用 mediaKey，菜单点击后气泡下方必然能接到状态。
                    if (msg.mediaType == "voice") {
                        add(MenuEntry("转文字", Icons.Filled.Subtitles) {
                            onMenuClose()
                            val url = msg.mediaUrl
                            val secs = msg.text.toIntOrNull() ?: msg.mediaWidth
                            if (url.isEmpty()) {
                                Toast.makeText(ctx, "语音文件不存在", Toast.LENGTH_SHORT).show()
                                return@MenuEntry
                            }
                            if (VoiceTranscribeEngine.resolveRecognizerComponent(ctx) == null) {
                                Toast.makeText(ctx, "当前设备没有可用的语音识别服务", Toast.LENGTH_SHORT).show()
                                return@MenuEntry
                            }
                            VoiceTranscribeStore.start(
                                ctx,
                                VoiceTranscribeStore.mediaKey(msg.id, url),
                                secs
                            )
                        })
                    }
                    // AI / 本地对话不展示「信息」；乐观上屏消息（服务端尚未确认，serverId<=0）也不展示——
                    // 此时 msg.id 是本地负数临时 id，直接查服务端会报「消息不存在」，等确认回填 serverId 后即可查询。
                    // 已确认消息优先用 serverId 查询，双保险规避本地 id 与真实 id 不一致。
                    if (!isLocalChat(friendId) && msg.serverId > 0) {
                        add(MenuEntry("信息", Icons.Filled.Info) {
                        onMenuClose()
                        menuScope.launch {
                            val result = com.aurora.chat.data.api.AuroraApi.getMessageInfo(msg.serverId)
                            withContext(Dispatchers.Main) {
                                if (result.success) {
                                    msgInfoData = result.data
                                    showMsgInfoDialog = true
                                } else {
                                    Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    })
                    }
                    add(MenuEntry("多选", Icons.Filled.CheckBox) { onMenuClose(); onMultiSelect() })
                    // 群待办：群主/管理员把当前消息设为群待办（全员在会话顶部看到提示，可跳转）
                    if (onSetGroupTodo != null) {
                        add(MenuEntry("群待办", Icons.Filled.Flag) {
                            onSetGroupTodo?.invoke(msg)
                            onMenuClose()
                        })
                    }
                    // 撤回：开发者(ID==1)可撤回任何人的消息且不限时间；普通用户只限自己 2 分钟内。
                    // 超过窗口直接不出现，免得用户点了才被告知「已超过2分钟」。
                    if (canRecallMessage(msg, currentUserId, friendId)) {
                        add(MenuEntry("撤回", RecallIcon) {
                        onMenuClose()
                        val recallTargetId = if (msg.serverId > 0) msg.serverId else msg.id
                        menuScope.launch {
                            if (isLocalChat(friendId)) {
                                // 个人对话：本地撤回，直接更新 JSON 文件
                                try {
                                    val file = localChatMessagesFile(ctx, currentUserId, localChatKey(friendId))
                                    if (file.exists()) {
                                        val arr = org.json.JSONArray(file.readText())
                                        for (i in 0 until arr.length()) {
                                            if (arr.getJSONObject(i).optLong("id", 0) == msg.id) {
                                                arr.getJSONObject(i).put("revoked", true); break
                                            }
                                        }
                                        file.writeText(arr.toString())
                                    }
                                } catch (_: Exception) {}
                                withContext(Dispatchers.Main) {
                                    com.aurora.chat.PendingMessageRecalled.messageId = msg.id
                                    com.aurora.chat.PendingMessageRecalled.senderName = currentUserName
                                    com.aurora.chat.PendingMessageRecalled.hasUpdate = true
                                    // 文案与服务端会话列表保持一致（服务端同样返回"你撤回了一条消息"）
                                    ChatViewModel.updateConversationPreview(friendId, "你撤回了一条消息", System.currentTimeMillis() / 1000)
                                    Toast.makeText(ctx, "已撤回", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                var result = com.aurora.chat.data.api.AuroraApi.recallMessage(recallTargetId)
                                // 发送/入库竞态：服务端可能尚未提交该消息，稍后重试一次
                                if (!result.success) {
                                    kotlinx.coroutines.delay(1200)
                                    result = com.aurora.chat.data.api.AuroraApi.recallMessage(recallTargetId)
                                }
                                com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "active recall: result.success=${result.success} msgId=$recallTargetId friendId=$friendId")
                                withContext(Dispatchers.Main) {
                                    if (result.success) {
                                        Toast.makeText(ctx, "撤回成功", Toast.LENGTH_SHORT).show()
                                        com.aurora.chat.PendingMessageRecalled.messageId = recallTargetId
                                        com.aurora.chat.PendingMessageRecalled.senderName =
                                            if (msg.isMine) currentUserName else msg.senderName
                                        com.aurora.chat.PendingMessageRecalled.hasUpdate = true
                                        ChatViewModel.updateConversationPreview(
                                            friendId,
                                            if (msg.isMine) "你撤回了一条消息" else "${msg.senderName} 撤回了一条消息",
                                            System.currentTimeMillis() / 1000
                                        )
                                        // 落盘标记撤回。
                                        // 注意：这里**不再**清媒体文件/Coil 缓存。分享聊天（以及转发）的快照
                                        // 直接复用同一个媒体 URL，撤回原消息时把本地缓存删掉，会让已经分享出去的
                                        // 聊天记录里的图片立刻变成「图片已被清理或过期」（服务端原文件过了保留期就 404）。
                                        // 撤回后的气泡本身只显示「撤回了一条消息」，不会再读这张图，留着缓存没有副作用。
                                        withContext(Dispatchers.IO) {
                                            com.aurora.chat.data.repository.LocalMessageStore.markRevoked(ctx, friendId, msg.id)
                                        }
                                        // 同步失效会话消息缓存（与广播路径保持一致，避免重进会话命中旧快照）
                                        com.aurora.chat.ui.viewmodel.ChatViewModel.invalidateHotCache(currentUserId, friendId)
                                    } else {
                                        Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    })
                    }
                    // 朗读：只要有文字内容就支持（私聊 / 群聊 / AI 都一样），
                    // 媒体消息（语音、图片、文件）正文为空，读出来没有意义，因此不出现
                    if (msg.text.isNotBlank() && msg.mediaType.isBlank() && msg.isRevoked == 0) {
                        add(MenuEntry(if (speaking) "停止" else "朗读", if (speaking) Icons.Filled.Stop else Icons.Filled.VolumeUp, danger = speaking) {
                            if (speaking) {
                                TtsHelper.stop()
                            } else {
                                TtsHelper.speak(ctx, msg.text)
                            }
                            onMenuClose()
                        })
                    }
                    // 开发者自己的广播：在普通气泡菜单里也提供「广播管理」入口，确保长按一定能呼出
                    if (msg.broadcastTaskId != 0L && msg.isMine) {
                        add(MenuEntry("广播管理", Icons.Filled.Info) {
                            onBroadcastManage(msg.id, msg.broadcastTaskId); onMenuClose()
                        })
                    }
                    // 群管理：对别人发的消息提供「禁言」入口（紧挨着「删除」左侧）。
                    // 自己发的消息不显示——群主/管理员不能禁言自己，后端也会拒绝。
                    if (onMuteMember != null && !msg.isMine && msg.fromUserId > 0) {
                        val muteTargetName =
                            if (msg.senderName.isNotBlank()) msg.senderName else "用户${msg.fromUserId}"
                        add(MenuEntry("禁言", Icons.Filled.Block) {
                            onMenuClose()
                            onMuteMember?.invoke(msg.fromUserId, muteTargetName)
                        })
                    }
                    add(MenuEntry("删除", Icons.Filled.Delete, danger = true) { onDeleteMessage(msg.id); onMenuClose() })
                }.sortedBy { e ->
                    // 固定槽位重排：某个动作在本次消息里不可用就跳过，但绝不让后面的项顶上它的位置
                    val slot = MENU_ORDER.indexOf(e.label)
                    if (slot >= 0) slot else MENU_ORDER.size
                }

                // 网格布局：每排 5 个，单元格略微收窄但保留呼吸感（列间 2dp 间距），避免挤在一起
                Column(
                    modifier = Modifier
                        .graphicsLayer {
                            this.alpha = alpha.value
                            scaleX = scale.value
                            scaleY = scale.value
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(com.aurora.chat.ui.theme.appSurface())
                        .border(0.5.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(10.dp))
                        .padding(3.dp)
                ) {
                    entries.chunked(5).forEach { rowEntries ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            rowEntries.forEach { MenuCell(it, Modifier.width(52.dp).height(48.dp)) }
                        }
                    }
                }
            }
        }
    }

    Box {
        BubbleWithMenu()
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF111827))
    }
}

/**
 * 微信/QQ 风格长按菜单浮层：贴近锚点（气泡/卡片）浮出小卡片，5 列网格排布。
 * 视觉与定位规则和消息气泡内的长按菜单完全一致，供红包等「非标准气泡」复用，
 * 避免同一 App 出现两套观感不同的菜单。
 * @param anchor 锚点在窗口中的坐标（用 onGloballyPositioned { it.boundsInWindow() } 捕获）
 */
@Composable
internal fun MessageMenuPopup(
    anchor: androidx.compose.ui.geometry.Rect,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    Popup(
        onDismissRequest = { onDismiss() },
        properties = PopupProperties(focusable = false),
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val ab = if (anchor.width > 0f && anchor.height > 0f)
                    IntRect(anchor.left.toInt(), anchor.top.toInt(), anchor.right.toInt(), anchor.bottom.toInt())
                else anchorBounds
                val m = (8 * density.density).toInt()
                val menuW = popupContentSize.width
                val menuH = popupContentSize.height
                val abTop = ab.top
                val abBottom = ab.bottom
                // 锚点上方放得下就显示在上方，否则显示在下方
                var y = (abTop - m - menuH).coerceAtLeast(0)
                if (abTop - menuH < 0) {
                    y = (abBottom + m).coerceAtMost((windowSize.height - menuH).coerceAtLeast(0))
                }
                val x = (ab.center.x - menuW / 2)
                    .coerceIn(0, (windowSize.width - menuW).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    ) {
        // 入场动画：快速弹簧缩放 + 淡入（与原菜单一致）
        val scale = remember { Animatable(0.88f) }
        val alpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) { alpha.animateTo(1f, tween(100)) }
        LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
        Column(
            modifier = Modifier
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(RoundedCornerShape(10.dp))
                .background(com.aurora.chat.ui.theme.appSurface())
                .border(0.5.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(10.dp))
                .padding(3.dp)
        ) {
            entries.chunked(5).forEach { rowEntries ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    rowEntries.forEach { MenuCell(it, Modifier.width(52.dp).height(48.dp)) }
                }
            }
        }
    }
}

/**
 * 这条消息有没有「人能读、能复制、能长按选中」的文本。
 *
 * 为 false 的情形（长按菜单里不出现「复制」「选择」）：
 *  - 媒体：图片 / 视频 / 文件 / 语音 / 闪照。它们的 text 是内部占位（语音存的是时长、文件常为空串），
 *    复制出来只会得到 "[图片]" 或 "1"；气泡里也没有可选中的文字。
 *  - 卡片：位置 / 分享 / 邀请入群 / 隐私请求 / 隐私回复 / 清空聊天请求 / 订单通知 / 转账 / 红包。
 *    它们的 text 全是对用户不可读的协议 JSON 或前缀标记，复制粘贴出去是一串乱码。
 *  - 正文为空的消息。
 *
 * 其余（普通文字、AI 正文、带文字说明的媒体说明等）保持原样，照常显示这两项。
 */
private fun hasCopyableText(msg: com.aurora.chat.ui.chat.IMessageRef): Boolean {
    // 闪照：按查看一次即销毁，文本也没有意义
    if (msg.flashDuration > 0) return false
    // 媒体类型不是 text 的一律不是可读正文（image/jpg/png/gif/jpeg/webp/video/mp4/mov/file/voice/...）
    when (msg.mediaType.lowercase()) {
        "", "text" -> Unit
        else -> return false
    }
    val t = msg.text
    if (t.isBlank()) return false
    // 卡片协议文本：用现有的解析器判定，避免再维护一套前缀常量
    if (parseLocationCard(t) != null) return false
    if (parseOrderNotice(t) != null) return false
    if (isShareCardMessage(t)) return false
    if (com.aurora.chat.ui.chat.isGomokuInviteCard(t)) return false
    if (isPrivacyRequestCard(t) || isPrivacyResponseCard(t)) return false
    if (isInviteGroupCard(t)) return false
    if (isClearChatRequestCard(t) || isClearChatRejectCard(t)) return false
    if (isTransferMessage(t) || isRedPacketMessage(t)) return false
    if (parseTransferNotice(t) != null) return false
    return true
}

/** 微信/QQ 风格长按菜单条目（图标 + 文字） */
internal data class MenuEntry(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 长按菜单的固定槽位顺序。
 *
 * 以前菜单顺序完全由各消息类型「按条件 add」的先后决定：文本消息有「复制/选择」，
 * 表情包没有，于是「引用」被顶到第一格 —— 同样是长按，不同消息的按钮位置天天变，
 * 用户记不住哪一格是什么。这里给每个动作一个固定位次，缺项直接跳过：
 * 同一种操作在所有消息类型里永远落在同一格位。
 *
 * 「引用」放第一：它几乎对所有消息类型都可用（文字/图片/语音/表情都能引用），
 * 这样任何消息长按后的第一格都是同一个动作。
 * 「删除/撤回」这类破坏性操作压到最后，避免误触。
 */
private val MENU_ORDER = listOf(
    "引用", "复制", "选择", "修改", "收藏", "多选",
    "转文字", "朗读", "信息", "群待办", "广播管理", "禁言", "撤回", "删除"
)

/** 网格菜单单元格：上方图标，下方文字，紧凑布局 */
@Composable
internal fun MenuCell(entry: MenuEntry, modifier: Modifier = Modifier) {
    val color = if (entry.danger) Color(0xFFF04438) else Color(0xFF111827)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = entry.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = entry.label,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = entry.label,
            fontSize = 11.sp,
            color = color,
            maxLines = 1
        )
    }
}

// ============ 系统原生 TTS 朗读（长按消息「朗读」按钮调用） ============
// 单例持有 TextToSpeech 实例，懒初始化；朗读前剥离常见 markdown 标记，避免把 **、# 等符号念出来。
object TtsHelper {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initStarted = false
    private val pending = ArrayDeque<String>()
    // 当前是否正在朗读，供 UI 切换「朗读 / 停止」按钮
    val isSpeaking = mutableStateOf(false)
    private const val UTTERANCE_ID = "aurora_tts"

    fun speak(context: Context, text: String) {
        val content = cleanForTts(text)
        if (content.isBlank()) return
        if (tts == null && !initStarted) {
            initStarted = true
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.CHINA
                    tts?.setSpeechRate(1.0f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { isSpeaking.value = true }
                        override fun onDone(utteranceId: String?) { isSpeaking.value = false }
                        override fun onError(utteranceId: String?) { isSpeaking.value = false }
                    })
                    ready = true
                    // 初始化期间排队的文本现在补播
                    while (pending.isNotEmpty()) {
                        tts?.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
                    }
                } else {
                    initStarted = false
                    tts = null
                    Toast.makeText(context.applicationContext, "语音引擎不可用，请检查系统 TTS", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (ready) {
            // QUEUE_FLUSH：朗读新内容前停止上一段
            tts?.speak(content, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else if (initStarted) {
            pending.addLast(content)
        }
    }

    fun stop() {
        tts?.stop()
        isSpeaking.value = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        initStarted = false
        isSpeaking.value = false
        pending.clear()
    }

    /** 朗读前剥离 markdown 标记 */
    fun clean(text: String): String = cleanForTts(text)

    // 去掉 markdown / 链接等会影响朗读体验的符号
    private fun cleanForTts(text: String): String {
        return text
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`[^`]*`"), " ")
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            .replace(Regex("[*_~]"), "")
            .replace(Regex(">\\s?"), "")
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]+\\)"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/** AI 工具调用状态行，放在 AI 气泡下方：0 正在调用(蓝) / 1 调用成功(绿) / 2 调用失败(红)。纯文本，不带彩色圆点。完成态不自动消失。 */
@Composable
private fun AiToolStatusRow(tool: String, state: Int, target: String = "") {
    // 多 AI 元工具:专属文案 + 实时状态(验收轮数 / 并行完成进度)
    val isReview = tool == "agent_review"
    val isFork = tool == "agent_fork"
    val isAsk = tool == "ask_user"
    val isAI = isReview || isFork
    val liveStatus by if (isAI && state == 0) {
        (if (isFork) com.aurora.chat.ui.chat.AiChatManager.AIForkBus.live
        else com.aurora.chat.ui.chat.AiChatManager.AIReviewBus.live).collectAsState()
    } else remember { mutableStateOf<String?>(null) }
    // ask_user 进行中且仍有挂起请求:状态行可点击,重新展开被收起的提问面板(带着上次作答草稿);
    // 面板被收起时右侧追加「继续回答」徽标,提示用户可以点回来
    val askState by if (isAsk && state == 0) {
        com.aurora.chat.ui.chat.AiChatManager.AskUserBus.pending.collectAsState()
    } else remember { mutableStateOf<com.aurora.chat.ui.chat.AiChatManager.AskUserBus.Request?>(null) }
    val askHidden by if (isAsk && state == 0) {
        com.aurora.chat.ui.chat.AiChatManager.AskUserBus.hidden.collectAsState()
    } else remember { mutableStateOf(true) }
    val askCanResume = askState != null && askHidden
    val text = when {
        isFork && state == 0 -> "并行派发中,多个子执行 AI 同时干活…"
        isFork && state == 1 -> "并行子任务全部完成"
        isFork -> "并行派发失败"
        isReview && state == 0 -> "验收 AI 接手中…"
        isReview && state == 1 -> "验收 AI 核验完成"
        isReview -> "验收 AI 核验失败"
        isAsk && state == 0 -> "正在向用户提问"
        isAsk && state == 1 -> "已向用户提问"
        isAsk -> "提问未完成"
        // 普通工具：统一走唯一事实源
        else -> toolStatusText(tool, state, target)
    }
    val color = when (state) {
        1 -> Color(0xFF16A34A)
        2 -> Color(0xFFDC2626)
        else -> Color(0xFF2F6FED)
    }
    androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 4.dp)
                .then(
                    if (askState != null) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { com.aurora.chat.ui.chat.AiChatManager.AskUserBus.reopen() } else Modifier
                ),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (askCanResume) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x142F6FED))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("继续回答", fontSize = 11.sp, color = Color(0xFF2F6FED), fontWeight = FontWeight.Medium)
                }
            }
            if (isReview && state == 0) {
                // 跳过审查:点击后验收 AI 在当前请求结束后立即中止,执行者会收到"用户跳过了验收"并如实告知
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x14DC2626))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { com.aurora.chat.ui.chat.AiChatManager.requestReviewSkip() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("跳过审查", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
                }
            }
        }
        if (isAI && state == 0 && !liveStatus.isNullOrBlank()) {
            Text(
                text = liveStatus!!,
                color = com.aurora.chat.ui.theme.appTextHint(),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
        }
    }
}

/**
 * AI 回复底部的可选元信息行：时间 / 字数 / 复制。
 *
 * 三个开关都在「AI 设置 → 消息区元素」里，默认**全关** —— 全关时这个函数直接返回、
 * 不产生任何布局节点，界面与改动前完全一致（当前状态即默认状态）。
 */
@Composable
private fun AIMsgMetaRow(msg: IMessageRef, onCopy: () -> Unit) {
    val ctx = LocalContext.current
    val showTime = AIUiPrefs.showMsgTime(ctx)
    val showChars = AIUiPrefs.showCharCount(ctx)
    val showCopy = AIUiPrefs.showCopyBtn(ctx)
    if (!showTime && !showChars && !showCopy) return

    // 字数只算"看得见的字符"：内联哨兵（\u0001W\u0001 之类的控制字符）不计入
    val chars = remember(msg.text) { msg.text.count { it == '\n' || it >= ' ' } }
    val ts = if (msg.createdAt > 0) msg.createdAt else msg.time
    val timeText = remember(ts) {
        if (ts <= 0) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
    val noRipple = remember { MutableInteractionSource() }

    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showTime && timeText.isNotEmpty()) {
            Text(timeText, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
            if (showChars || showCopy) Spacer(Modifier.width(8.dp))
        }
        if (showChars) {
            Text("$chars 字", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
            if (showCopy) Spacer(Modifier.width(8.dp))
        }
        if (showCopy) {
            Text(
                "复制",
                fontSize = 11.sp,
                color = Color(0xFF1E40AF),
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(interactionSource = noRipple, indication = null) { onCopy() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 独立思考气泡：左边缘与最大宽度都跟正文气泡对齐（头像 36dp + 6dp 间距、宽度上限同 bubbleMaxWidth），
 * 不再用 weight(1f) 撑满右侧。
 *
 * 思考进行中（aiThinkingLive）默认展开；正文首字到达 = 思考结束，自动收回。
 * 展开 / 收回由 AnimatedVisibility 逐帧改变高度驱动：卡片底边、下方正文气泡和后续消息
 * 都随每一帧自然下移或上移，不会「啪」地跳一下。这里刻意不叠加 animateContentSize / animateItem，
 * 否则高度会被补间两次，起步发木、下面的气泡跟着抖。
 *
 * 只呈现纯文本思考内容，不解析 Markdown / 表格，也不加任何说明文案。
 */
@Composable
private fun AiThinkingBubble(text: String, msgId: Long) {
    // aiThinkingLive 只在 reasoning_content 正在吐时为真；正文首字到达即置 false。
    val thinkingLive = aiThinkingLive.value && aiStreamMsgId.value == msgId
    var expanded by remember(msgId) { mutableStateOf(thinkingLive) }
    LaunchedEffect(thinkingLive) { expanded = thinkingLive }
    val noRipple = remember { MutableInteractionSource() }
    val maxWidth = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.70f)
        .coerceAtMost(400f).dp
    val arrowRotation = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(260)
    ).value
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(ChatAvatarSize), contentAlignment = Alignment.Center) {
            val avatar = AiAvatarState.bitmap
            if (avatar != null) {
                Image(
                    bitmap = avatar.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(ChatAvatarSize).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                AiDefaultAvatar(Modifier.size(ChatAvatarSize), ChatAvatarSize)
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(
            Modifier
                .width(maxWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.clickable(indication = null, interactionSource = noRipple) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "思考",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = com.aurora.chat.ui.theme.appTextSecondary()
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    null,
                    Modifier.size(18.dp).graphicsLayer { rotationZ = arrowRotation },
                    tint = com.aurora.chat.ui.theme.appTextSecondary()
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = tween(260), expandFrom = Alignment.Top
                ) + fadeIn(animationSpec = tween(260)),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = tween(260), shrinkTowards = Alignment.Top
                ) + fadeOut(animationSpec = tween(260))
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        AiChatManager.stripInlineReasoning(text),
                        fontSize = 12.sp,
                        color = com.aurora.chat.ui.theme.appTextSecondary(),
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

/** 本次消耗：气泡下方一行，只列输入 / 输出 / 合计，不写任何提示文字。 */
@Composable
private fun AiUsageLine(msg: IMessageRef) {
    val input = msg.inputTokens
    val output = msg.outputTokens
    Text(
        "输入 $input 输出 $output 共 ${input + output}",
        fontSize = 11.sp,
        color = com.aurora.chat.ui.theme.appTextSecondary(),
        modifier = Modifier.padding(start = 44.dp, top = 2.dp, bottom = 2.dp)
    )
}

/**
 * 「左拖引用」：按住消息气泡向左拖动，拖出「一半」距离后气泡右侧出现引用图标，
 * 松手即引用该消息并震动一下。覆盖自己与他方的所有消息（与微信/QQ 一致）。
 *
 * 手势实现：基于 Compose 官方 [draggable]（与 Material SwipeToDismiss 同源的
 * 嵌套滚动 / 指针 slop 机制），企业级列表滑动交互标准做法：
 * 1. 仅声明 [Orientation.Horizontal]——方向由系统 slop 在手势开始时一次性判定，
 *    纵向滚动天然交给 LazyColumn，上滑绝不卡顿；右滑也绝不抽搐。
 * 2. 拖拽过程中**任意方向都参与**：向左拖出、向右拖回（取消），但永不超过原点
 *    （向右 delta 经 coerceAtMost(0) 钳制为 0），所以中途可滑回、不会定死。
 * 3. 仅平移「气泡本体」（内层 Box），引用图标放在**未平移的外层**，气泡左移后
 *    图标自然落在右侧露出的空隙里，绝不会叠进气泡；阈值设为 48dp（约一半手感）
 *    即默认引用。松手未达阈值则 120ms 回弹归位，达阈值则触发引用 + 震动。
 */
@Composable
private fun DragToQuoteBox(
    msg: IMessageRef,
    onReplyTo: (IMessageRef) -> Unit,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { mutableStateOf(0f) }
    val quoteHint = remember { mutableStateOf(false) }
    val hintAtPx = 48f * density.density
    val snapJob = remember { mutableStateOf<Job?>(null) }

    Box(
        Modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // 阻尼：气泡位移只取手指位移的 DAMP 比例，跟手但有"重量感"，不再发滑。
                    // 任意方向都参与：向左拖出 / 向右拖回，但永不超过原点（杜绝右滑越界与抽搐）。
                    val DAMP = 0.6f
                    val new = (offset.value + delta * DAMP).coerceAtMost(0f)
                    offset.value = new
                    quoteHint.value = new <= -hintAtPx
                },
                onDragStarted = { snapJob.value?.cancel() },
                onDragStopped = {
                    if (quoteHint.value) {
                        onReplyTo(msg)
                        vibrate(ctx)
                    }
                    val from = offset.value
                    if (from != 0f) {
                        snapJob.value = scope.launch {
                            val a = Animatable(from)
                            a.animateTo(0f, tween(120)) { offset.value = value }
                        }
                    }
                    quoteHint.value = false
                }
            )
    ) {
        // 气泡本体：随左拖而平移；松手回弹
        Box(Modifier.graphicsLayer { translationX = offset.value }) {
            content()
        }
        // 引用提示：固定在右侧（不被气泡平移带走），气泡左移后落在露出的空隙里
        if (quoteHint.value) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
            ) {
                Icon(
                    Icons.Filled.FormatQuote,
                    "引用",
                    tint = Color(0xFF1E40AF),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/** 轻震动一下（引用确认反馈）。 */
private fun vibrate(ctx: android.content.Context) {
    val v = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
    if (android.os.Build.VERSION.SDK_INT >= 26) {
        v.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        v.vibrate(30)
    }
}
