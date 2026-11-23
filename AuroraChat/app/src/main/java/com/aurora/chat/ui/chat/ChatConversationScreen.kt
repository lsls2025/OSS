package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import com.aurora.chat.ui.server.SetDialogSystemBarColors
import com.aurora.chat.ui.profile.FreeApiSelectScreen
import com.aurora.chat.ui.profile.PaidApiSelectScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import com.aurora.chat.ui.chat.media.ChatMedia
import com.aurora.chat.ui.chat.media.HeroTransform
import com.aurora.chat.ui.chat.media.ImageBoundsRegistry
import com.aurora.chat.ui.chat.media.MediaStore
import com.aurora.chat.ui.chat.media.chatMediaThumbUrl
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R
import com.aurora.chat.NotificationHelper
import com.aurora.chat.ErrorReporter
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.DataCache
import com.aurora.chat.data.store.MessageData
import com.aurora.chat.data.store.MessageStoreAdapter
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.AnimationMode
import com.aurora.chat.ui.components.BubbleColorStorage
import com.aurora.chat.ui.profile.OnlineTimeTracker
import com.aurora.chat.ui.profile.WordCountTracker
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.components.avatarCornerRadius
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aurora.chat.ui.common.createExoPlayer
import android.util.Log
import android.view.TextureView
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.ui.viewinterop.AndroidView
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.material3.AlertDialog

/** 拍一拍冷却窗口：5 秒内只能拍一次（开发者豁免） */
private const val POKE_COOLDOWN_MS = 5_000L

/** 上一次成功发起拍一拍的时间戳（进程内共享，私聊/群聊共用同一个冷却） */
private var lastPokeAtMs = 0L












/** 跨组合期持久化聊天数据（LRU 淘汰，最多保留 5 个对话 ≈ 750KB） */
private val companionMessageCache = object : LinkedHashMap<Long, List<IMessageRef>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<IMessageRef>>?): Boolean {
        return size > 5
    }
}

/**
 * 本地清理：只删本机的聊天痕迹，服务器数据完全不动。
 *
 * 清掉的东西（缺一不可，之前只清了消息文件，导致"清空后红叹号还挂着"）：
 *   1. 本地消息文件（LocalMessageStore）—— 含两个遗留 JSON 目录，防止迁移复活
 *   2. 发送失败的气泡（FailedMessageStore）—— 带红叹号那些
 *   3. 本地系统广播（LocalNoticeStore）—— 拉黑/好友验证那些居中提示
 *   4. 内存缓存（热缓存 + 伴生缓存）
 *   5. 通知正在查看该会话的界面清空内存列表
 *   6. 持久化「本地清理水位线」：此后所有历史请求只认界线之后的新消息。
 *      【关键】少了这一步，重新进入会话 / 下拉刷新时服务端会把整段历史重新拉回来
 *      并写进本地库 —— 就是用户看到的「清理完刷新一下，数据全回来了，本地清理只是障眼法」。
 */
internal suspend fun clearConversationLocalOnly(ctx: Context, currentUserId: Long, friendId: Long) {
    val store = com.aurora.chat.data.repository.LocalMessageStore
    // 水位线必须在删本地库之前算：取清理瞬间服务端最大消息 id（id 全局单调递增，
    // 比时间戳可靠——不受本机与服务器时钟差影响）。
    val watermarkId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val localMax = runCatching {
            store.loadRecentMessages(ctx, friendId, 1).messages.maxOfOrNull { it.id } ?: 0L
        }.getOrDefault(0L)
        if (localMax > 0L) localMax else {
            // 本地库为空（新装 / 从未缓存）时才问服务端；此刻水位线尚未写入，
            // 不会把自己要拿的最大 id 拦掉。离线或失败则退化为按时间过滤（见 LocalClearStore）。
            runCatching {
                val r = if (isGroupChat(friendId)) {
                    com.aurora.chat.data.repository.ChatRepository.getGroupMessages(friendId, 0, 0)
                } else {
                    com.aurora.chat.data.repository.ChatRepository.getMessages(currentUserId, friendId, 0, 0)
                }
                if (r.success) r.data?.maxOfOrNull { it.id } ?: 0L else 0L
            }.getOrDefault(0L)
        }
    }
    com.aurora.chat.data.local.LocalClearStore.mark(ctx, friendId, watermarkId)

    store.deleteConversation(ctx, friendId)
    com.aurora.chat.data.local.FailedMessageStore.clear(ctx, friendId)
    com.aurora.chat.data.local.LocalNoticeStore.clear(ctx, friendId)
    companionMessageCache.remove(friendId)
    com.aurora.chat.ui.viewmodel.ChatViewModel.invalidateHotCache(currentUserId, friendId)
    // 通知正在查看该会话的界面：清空内存列表（含红叹号气泡与本地广播）
    com.aurora.chat.PendingConversationDeleted.hasUpdate = true
    com.aurora.chat.PendingConversationDeleted.friendId = friendId
    // 立刻刷新会话列表：服务端还会返回旧的 last_message，不刷新的话列表上仍挂着旧预览。
    // 刷新后由 ChatViewModel.enrichConversationsFromLocal 按水位线把预览清掉。
    com.aurora.chat.ui.viewmodel.ChatViewModel.loadConversations(ctx, showLoading = false, force = true)
}

/**
 * 「删除聊天」：只读会话（被移出 / 已退出 / 群主解散 / 服务端判定不在群内）的本地彻底清理。
 *
 * 与 [clearConversationLocalOnly]（清空聊天记录、会话仍在列表）不同，这里是「删掉这个会话」：
 *   1. 先按消息里引用的地址删掉本机附件（图片/视频/文件）与 Coil 缓存 —— 必须在清库之前，
 *      清完库就查不到这些地址了，图片会变成永远删不掉的孤儿文件；
 *   2. 再走一遍完整的本地清理（消息库 / 失败气泡 / 系统广播 / 热缓存 / 清理水位线）；
 *   3. 从会话列表移除（本地隐藏集合），并清掉「我已不在该群」的退出记录 ——
 *      留着它 mergeExitedGroups 会把会话重新拼回列表，等于没删。
 * 全程只动本机，服务端消息与群资料一概不动。
 */
internal suspend fun deleteReadOnlyConversationLocalFully(
    ctx: Context, currentUserId: Long, friendId: Long
) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    // 1) 收集附件地址：本地消息 + 发送失败的草稿气泡（带图失败时媒体挂在失败记录上）
    val fromMessages = runCatching {
        com.aurora.chat.data.repository.LocalMessageStore.loadMessages(ctx, friendId).map { it.mediaUrl }
    }.getOrDefault(emptyList())
    val fromFailed = runCatching {
        com.aurora.chat.data.local.FailedMessageStore.list(ctx, friendId).map { it.mediaUrl }
    }.getOrDefault(emptyList())
    for (url in (fromMessages + fromFailed).filter { it.isNotBlank() }.distinct()) {
        runCatching { deleteMediaFile(ctx, url) }
    }

    // 2) 消息 / 失败气泡 / 系统广播 / 内存缓存 / 水位线
    clearConversationLocalOnly(ctx, currentUserId, friendId)

    // 3) 会话列表移除 + 退出记录（群聊才有）
    if (isGroupChat(friendId)) {
        runCatching {
            com.aurora.chat.data.local.GroupExitStore.remove(ctx, -(friendId + 1000L))
        }
    }
    com.aurora.chat.ui.viewmodel.ChatViewModel.hideConversation(ctx, friendId)
    com.aurora.chat.ui.viewmodel.ChatViewModel.loadConversations(ctx, showLoading = false, force = true)
}

/**
 * AI 对话中「待发送」的图片附件：选择后暂存在输入框上方，点击发送时才随消息一起发出去。
 * @param uri 原始 Uri，用于输入框上方缩略图预览
 * @param bytes 压缩后的图片文件字节（发给 AI 时转 base64；落盘渲染时直接写入）
 * @param name 文件名（仅展示）
 * @param mime 图片 MIME 类型
 */
data class PendingAiImage(
    val uri: Uri,
    val bytes: ByteArray,
    val name: String,
    val mime: String
)

/** AI 对话待发送文件附件（与图片附件并列）。size 用于大文件提示；bytes 可能为空（大文件不预读）。 */
data class PendingAiFile(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val bytes: ByteArray? = null,
    /** 非空 = 工作区引用（文件本来就在 ai_files 内）：发送时不再拷贝/改名，直接用该相对路径提示 AI */
    val wsRelPath: String = ""
)

/**
 * 系统分享 → AI 对话的注入请求（进程级单例）。
 * MainActivity 在分享对话框选择"发给 AI"时写入，ChatConversationScreen 组合后消费并置 null；
 * 与正常 AI 对话选文件一致：内容预挂到输入区上方文件挂载行，由用户点发送才真正发送。
 */
object AiChatShare {
    val pending = mutableStateOf<AiChatSharePayload?>(null)
}

/** 分享到 AI 对话的内容：文本 + 预挂附件（PendingAiFile 与正常选文件流程同构） */
data class AiChatSharePayload(
    val text: String,
    val files: List<PendingAiFile>
)

/**
 * AI 对话的「后台进行中的回复」。发送后即使立即离开对话，回复仍在应用级协程中继续生成，
 * 通过 [ChatConversationScreen.aiReplyState] 跨组合期持有，供重进对话时继续实时刷新。
 */
data class AiBackgroundReply(
    val aiMsgId: Long,
    var text: String = "",
    var reasoningText: String = "",
    var done: Boolean = false,
    var error: String? = null,
    var stopped: Boolean = false,
    val convId: Long = 0   // 所属 AI 会话代次：仅当 == 当前 aiConvTag 时才渲染到当前对话，杜绝跨会话串扰
)

/**
 * 应用级协程作用域：AI 流式回复在此运行，不随 ChatConversationScreen 组合销毁而被取消，
 * 从而保证「发完消息立即离开对话，AI 仍会继续回复完」。
 */
private val aiGlobalScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
        + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            // 兜底：收尾段 / UI 回调之外的任何未捕获异常都落日志，避免被 SupervisorJob 静默吞掉，
            // 导致「任务毫无征兆直接结束、零反馈」。取消异常不算错误，跳过。
            if (e is kotlin.coroutines.cancellation.CancellationException) return@CoroutineExceptionHandler
            ErrorReporter.error("AI_Coroutine", "AI 回复协程未捕获异常(任务静默终止): ${e.javaClass.simpleName}: ${e.message}")
        }
)

/** 当前正在进行的 AI 回复（全局，跨组合期）。界面通过收集此流把回复实时同步进消息列表。 */
private val aiReplyState = kotlinx.coroutines.flow.MutableStateFlow<AiBackgroundReply?>(null)

/** 是否正在生成 AI 回复（控制发送按钮在「发送 ↔ 停止」间切换）。文件级 State，跨组合期稳定。
 *  internal：气泡渲染层（MessageBubble）也要读它来判定"这条消息是否还在流式" ——
 *  只有 `expandedReasoningMsgId` 是不够的：它**只在下一轮发送时才复位**，回复结束后仍等于最后那条
 *  AI 消息的 id，于是"思考中/大思考块/交付总结扫光"会永远挂在那条消息上。 */
internal val aiWriting = androidx.compose.runtime.mutableStateOf(false)

/**
 * 是否**正在流式接收「思考」增量**（reasoning_content 正在往外吐）。
 *
 * 与 [aiWriting] 的区别很关键：aiWriting 表示「整条回复还在生成」——它把
 * 「等待模型响应 / 工具执行 / 多轮工具循环 / 收尾交付」全都算在内，跨度远大于思考本身。
 * 拿它当思考信号，就会出现「命令跑完、结果已回喂、本地正等模型响应时，思考卡片还挂着『思考中』」
 * （用户反馈的原话）。所以思考卡片单独用这个标志：**只有它为 true 时才存在思考卡片**。
 *
 * 写入时机（三条流式链路都必须成对写）：
 *  · onReasoningDelta → true（正在吐思考）
 *  · onDelta（正文首字到达 = 思考已结束）→ false
 *  · onDone（本轮流结束）→ false；onError → false
 *  · 发送开始时复位 false（上一轮若停在思考中途，标志会残留 → 新气泡一出现就闪思考卡片）
 */
internal val aiThinkingLive = androidx.compose.runtime.mutableStateOf(false)

/**
 * 当前正在流式生成的那条 AI 消息 id（**文件级**，跨组合期存活）。
 *
 * 它原先写成页面内的 `remember { mutableStateOf(0L) }` —— 切出对话再进来就**归零**，于是
 * `liveNow = aiWriting && msgId == id` 恒为 false：思考卡片不再显示「思考中」、交付总结不再扫光、
 * 「等待模型响应」那类流式动效全部挂不上（用户反馈的原话：「我切出对话重新进入，之前的计时和显示
 * 全部不见」），而且因为后续每次重组都拿 0 去比，**永远不会自愈**，只有发一条新消息重新赋值才恢复。
 * 提升为文件级后，它与 [aiWriting] / [aiReplyState] 同寿命：离开页面再回来，判定依然指向那条在写的消息。
 */
internal val aiStreamMsgId = androidx.compose.runtime.mutableStateOf(0L)

/** AI 显示设置页（齿轮入口）的显隐；文件级，供工具行 / 全屏面板 / 列表过滤共用。 */
internal val showAiSettings = androidx.compose.runtime.mutableStateOf(false)

/** 显示设置变动版本号：设置页开关一改就 +1，让列表隐藏规则立即重算，不必等下一条消息。 */
internal val aiDisplayRefresh = androidx.compose.runtime.mutableIntStateOf(0)

/**
 * 当前正在生成回复的【会话 id】（按会话隔离发送/停止按钮状态）。
 * 回复内容已用 AiBackgroundReply.convId 做代次隔离，但按钮状态此前是全局布尔 aiWriting，
 * 导致 A 会话生成中点进 B 会话仍显示「停止」、且点「停止」会误取消 A 的在途任务、B 永远无法发送。
 * 改为记录「哪个会话在生成」：仅当 aiWritingConvId == 当前会话时按钮才显示「停止」。
 * 值为 -1L 表示当前没有任何会话在生成。
 */
private var aiWritingConvId = -1L
/** 后台 AI 任务运行中标志（会话级）。与 aiWriting 分离：aiWriting 会因 onError 等前台路径
 *  提前复位，但 runAIChatLoop 后台可能仍在重试 / 裁剪上下文 / 规划生成 / 等待工具响应；
 *  在任务真正结束（runAIChatLoop 返回并完成 outcome 收尾）之前，按钮必须保持「停止」，
 *  禁止提前切回「发送」。修复 UI 状态机误报（真实实验：任务未完成时按钮误显示「发送」）。 */
private val agentTaskInFlight = androidx.compose.runtime.mutableStateOf(false)
private var agentTaskInFlightConvId = -1L

// 工具调用系统提示注入已清理：保留常量名以维持 AI 骨架，内容已清空。
private const val BROKEN_TOOL_CALL_INSTRUCTION = ""

// ============================================================================
// 流式 UI 写入节流 + 增量 token 估算（性能·超长对话/大输出防卡）
// ============================================================================
/**
 * 流式 UI 写入的最小间隔（毫秒）。
 *
 * 流式回调（onDelta / onReasoningDelta）给出的是**累积全文**，所以丢弃中间帧不会丢内容：
 * 下一帧必然包含上一帧的全部文本，收尾路径（onDone / 中断兜底 / 停止收尾）还会再整体写一次。
 *
 * 为什么必须节流：超长输出 + 一大堆思考时，每个 token 都要把整条消息**全量重解析**一遍 ——
 * 交付块 / 方案卡 / DSML / 内联思考块 / 思考分段，每一项都是 O(文本长度)；再叠加消息列表整体失效
 * 引发的列表重建。token 密集时每秒 30~60 次这种量级的重解析，主线程直接被占满 → 整个对话卡死。
 * 钳到约 20 帧/秒后：肉眼仍是连续流式（人眼分辨不出 50ms 的刷新间隔），而这块开销直接降 3~5 倍。
 */
internal const val STREAM_UI_FRAME_MS = 50L

/**
 * 按「消息 id」隔离的流式写入节流器（三条流式链路共用）。
 * 用 ConcurrentHashMap 是因为流式回调可能来自不同协程/线程。
 */
internal object StreamUiThrottle {
    private val lastWriteMs = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * 该消息此刻是否允许写入 UI。
     *
     * 间隔随正文长度**自适应**：每次写入都要重跑「剥工具协议 + 切内联块 + markdown 解析」，
     * 全部是 O(文本长度)；长输出时固定 50ms 会把主线程占满（用户实测"输出非常卡"）。
     * 文本越长间隔越大：短输出仍保持 ~20fps 的顺滑；长输出降到 12/8fps ——
     * 长文本本来是在"读"而不是"看动画"，8fps 完全够用，而这块开销直接降 2~3 倍。
     */
    fun due(msgId: Long, textLen: Int = 0): Boolean {
        val now = System.currentTimeMillis()
        val interval = when {
            textLen < 4_000 -> STREAM_UI_FRAME_MS   // ~20fps
            textLen < 16_000 -> 80L                 // ~12fps
            else -> 120L                            // ~8fps
        }
        val prev = lastWriteMs[msgId] ?: 0L
        if (now - prev < interval) return false
        // 表过大时整体清空：节流表只影响刷新频率，清空最多让下一帧立即可写，无任何正确性影响
        if (lastWriteMs.size > 128) lastWriteMs.clear()
        lastWriteMs[msgId] = now
        return true
    }

    /** 回复收尾时清理，避免表被历史消息 id 撑大。 */
    fun clear(msgId: Long) { lastWriteMs.remove(msgId) }
}

/**
 * 流式 token 估算的**增量**版本（语义与 [AiChatManager.estimateTokens] 完全一致）。
 *
 * 原实现每收到一个 delta 就把**整段累积输出**逐字符重扫一遍（O(输出长度)）——
 * 输出越长越慢，正是「一大堆输出」时卡顿的来源之一。这里利用「文本只追加」的特性，
 * 只统计新增的那一段，增量累加 CJK / 其他字符计数，复杂度降为 O(增量)。
 */
internal class StreamingTokenEstimator {
    private var len = 0
    private var cjk = 0
    private var other = 0

    fun estimate(s: String): Int {
        if (s.length < len) { len = 0; cjk = 0; other = 0 }   // 文本被替换/回退（重生成）→ 重新统计
        var i = len
        while (i < s.length) {
            val c = s[i].code
            if (c in 0x4E00..0x9FFF || c in 0x3000..0x303F || c in 0xFF00..0xFFEF) cjk++ else other++
            i++
        }
        len = s.length
        return ((cjk + other / 4) + 1).coerceAtLeast(1)
    }
}

/**
 * play_video 工具结果 → 应用内全屏播放器的**中转**（文件级）。
 *
 * 为什么需要中转：主发送路径走的是 ChatConversationScreen 的**内联工具循环**，而该循环位于
 * `videoPlayUrl` 声明（本函数靠后位置）**之前** —— 内联循环无法直接给 `videoPlayUrl` 赋值。
 * 于是循环里把 play_url 写入本文件级 State，再由 `videoPlayUrl` 声明之后的 LaunchedEffect 消费并清空。
 * 漏接此链路的表症就是用户报的：「AI 明明调用成功了，全屏播放器根本不弹」。
 */
private val pendingOpenVideoUrl = androidx.compose.runtime.mutableStateOf("")

/** 用户点击「停止」后的请求标记：网络读流据此提前退出，且后续 onDelta/onDone 被忽略。 */
private var aiStopRequested = false

/** 当前正在进行的 AI 流式协程句柄（供「停止」取消）。文件级，跨组合期稳定。 */
private var activeAiJob: kotlinx.coroutines.Job? = null

/** AI 会话代次标记：开启新对话（清空 AI）时自增。后台回复携带着创建时的代次，只渲染进同一个会话。 */
private var aiConvTag = 0L

/** 丢弃当前后台 AI 后台任务（清空对话时调用）：取消流式协程并把状态清理干净，防止旧回复污染新会话。 */
/**
 * 合并已读状态：人数取大、已读时间取大。
 *
 * 已读是单调的 —— 人数只增不减，已读时间只会从 0 变成某个时刻。所以任何「旧数据覆盖新数据」
 * 都会让界面从「已读」跳回「未读」。乱序返回的请求、缓存与网络写入的竞争，都在这里兜住。
 */
private fun mergeReadStats(
    base: Map<Long, com.aurora.chat.data.api.ReadStat>,
    add: Map<Long, com.aurora.chat.data.api.ReadStat>
): Map<Long, com.aurora.chat.data.api.ReadStat> {
    if (add.isEmpty()) return base
    val out = HashMap<Long, com.aurora.chat.data.api.ReadStat>(base.size + add.size)
    out.putAll(base)
    add.forEach { (id, v) ->
        val old = out[id]
        out[id] = if (old == null) v else com.aurora.chat.data.api.ReadStat(
            id,
            maxOf(old.readCount, v.readCount),
            maxOf(old.otherReadAt, v.otherReadAt)
        )
    }
    return out
}

/**
 * 本地库里的历史消息 → 渲染用 [MessageData]。
 *
 * 只给「跳转到很久以前的消息」补洞时用（见 search/jump 的定位逻辑）：
 * 库里存的 content 在写入时已经按当时的规则生成过（撤回占位、拍一拍、广播等），
 * 所以这里只做字段搬运 + 媒体类型兜底，不重算任何文案。
 */
private fun com.aurora.chat.data.api.MessageInfo.toJumpMessageData(
    currentUserId: Long
): MessageData = MessageData(
    id = id,
    fromUserId = fromUserId,
    toUserId = toUserId,
    content = content,
    createdAt = createdAt,
    isMine = fromUserId == currentUserId,
    senderName = fromUserName,
    targetName = targetName,
    isRevoked = isRevoked != 0,
    isSystemNotice = isSystemNotice,
    replyToText = replyToText,
    replyToSender = replyToSender,
    replyToId = replyToId,
    mediaType = normalizeMediaType(mediaType, mediaUrl, id),
    mediaUrl = resolveMediaUrl(mediaUrl),
    flashDuration = flashDuration,
    broadcastTaskId = broadcastTaskId,
    contactSource = contactSource,
    localTimeMs = localTimeMs
)

private fun clearActiveAiReply() {
    activeAiJob?.cancel()
    activeAiJob = null
    aiStopRequested = false
    aiWriting.value = false
    aiWritingConvId = -1L
    agentTaskInFlight.value = false
    agentTaskInFlightConvId = -1L
    aiReplyState.value = null
}

/**
 * 「开启新对话 / 清空 AI 会话」的完整安全序列（suspend，需在调用方已 launch 的协程内执行）：
 * ① 先中断在途 AI 回复（复用「手动停止」同款取消机制）并等待其真正停止——join() 带 800ms 上限，绝不阻塞/死锁；
 * ② 开启新会话代次 aiConvTag++，让任何残余写回都被 collect 按代次忽略，杜绝旧回复渗入新会话；
 * ③ 清空内存列表、LRU 缓存，覆盖本地持久化为空，重置会话预览，并异步删除本会话生成的服务器图片。
 * 幂等：会话已空再次调用同样安全。返回后调用方仅负责关闭各自 UI 并做一次成功提示（提示后即结束）。
 */
private suspend fun resetAiConversation(
    ctx: android.content.Context,
    currentUserId: Long,
    messages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>
) {
    // —— ① 先中断在途 AI 回复并等待其退出 ——
    aiStopRequested = true
    val job = activeAiJob
    activeAiJob?.cancel()
    activeAiJob = null
    aiWriting.value = false
    aiWritingConvId = -1L
    agentTaskInFlight.value = false
    agentTaskInFlightConvId = -1L
    aiReplyState.value = null
    kotlinx.coroutines.withTimeoutOrNull(800L) { job?.join() }
    // —— ② 开启新会话代次，隔离残余写回 ——
    aiConvTag++
    // —— ③ 清空并持久化 ——
    val genNames = mutableSetOf<String>()
    for (m in messages) {
        val marker = "/chat-media/"
        val idx = m.mediaUrl.indexOf(marker)
        if (idx >= 0) genNames.add(m.mediaUrl.substring(idx + marker.length).substringBefore('?').substringAfterLast('/'))
    }
    messages.clear()
    companionMessageCache.remove(AI_CHAT_ID)
    if (genNames.isNotEmpty()) {
        aiGlobalScope.launch { for (n in genNames) { try { AuroraApi.deleteChatMedia(n) } catch (_: Exception) {} } }
    }
    withContext(Dispatchers.IO) {
        try { saveLocalChatMessages(ctx, currentUserId, AiConversationSession.storageKey(), emptyList()) } catch (_: Exception) {}
    }
    ChatViewModel.updateConversationPreview(AI_CHAT_ID, "", 0)
}

/**
 * 读取图片 Uri 并压缩为可供发送/落盘的字节（与相机上传保持同一压缩策略，控制体积防 OOM）。
 * 失败返回 null。
 */
private fun readAiImageBytes(ctx: Context, uri: Uri): ByteArray? {
    return try {
        ctx.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }?.let { fileBytes ->
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size, opts)
            val maxDimension = 1600
            val sampleSize = maxOf(
                (opts.outWidth + maxDimension - 1) / maxDimension,
                (opts.outHeight + maxDimension - 1) / maxDimension,
                1
            )
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size, decodeOpts)
            if (bitmap != null && (fileBytes.size > 400 * 1024 || sampleSize > 1)) {
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            } else fileBytes
        }
    } catch (_: Exception) { null }
}

/** 查询 Uri 的显示文件名，查不到则回退 lastPathSegment。 */
private fun queryDisplayName(ctx: Context, uri: Uri): String? {
    return try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            // getColumnIndex 查不到列时返回 -1，直接 getString(-1) 会抛异常（编译器也判定为越界）。
            // 先取列号并校验，查不到就按「查不到文件名」处理，交给上层的 lastPathSegment 兜底。
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && col >= 0) c.getString(col) else null
        }
    } catch (_: Exception) { uri.lastPathSegment }
}

/** 群聊 @ 成员：id、显示名、是否群主 */
private data class AtMember(val id: Long, val name: String, val isOwner: Boolean)

/** 计算成员名首位排序字母：A-Z，其余（数字/中文等）归入 '#'，用于名单 A-Z 排列 */
private fun atSectionLetter(name: String): Char {
    val c = name.firstOrNull() ?: return '#'
    val up = c.uppercaseChar()
    return if (up in 'A'..'Z') up else '#'
}

/** 判断输入框是否正处于 @ 状态：返回当前正在输入的 @ 下标（-1 表示未在 @） */
private fun atMentionStart(text: String): Int {
    if (text.isEmpty()) return -1
    var end = text.length
    while (end > 0) {
        val c = text[end - 1]
        if (c == ' ' || c == '\n' || c == '\t') break
        end--
    }
    // 只看「最后一个空白之后到末尾」这一段：这段里出现过 @ 就进入 @ 状态。
    // 原来要求 @ 必须紧跟在空白/文首，于是输入框里已有内容且没打空格时（如「你好@」）@ 完全不生效。
    val at = text.lastIndexOf('@')
    return if (at >= end) at else -1
}

/** 群聊 @ 抽屉：从输入框上方往上拉出，顶部 @全体成员（群主/群管理员可用），下方成员按 A-Z 排列 */
@Composable
private fun AtMemberSheet(
    members: List<AtMember>,
    canAtAll: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // 抽屉内搜索框关键字：输入成员名可过滤名单
    var searchText by remember { mutableStateOf("") }
    val sections = remember(members, canAtAll, searchText) {
        val kw = searchText.trim()
        val filtered = if (kw.isEmpty()) members else members.filter { it.name.contains(kw, ignoreCase = true) }
        filtered.asSequence()
            .groupBy { atSectionLetter(it.name) }
            .toSortedMap(compareBy<Char> { if (it == '#') 1 else 0 }.thenBy { it })
            .map { (letter, list) -> letter to list.sortedBy { it.name.lowercase() } }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        // 主体：贴底、大尺度的抽屉面板（接近全屏，不完整覆盖）；顶部留出区域由外层遮罩处理
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(com.aurora.chat.ui.theme.appSurface())
                .navigationBarsPadding()
                // 面板内部空白处消费点击，避免误触穿透到背景遮罩而关闭抽屉
                .clickable(
                    onClick = {},
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
        ) {
            // 顶部拖动条
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Color(0xFFD1D5DB)))
            }
            Text(
                "选择成员", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            // 成员名搜索框：输入可过滤名单
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg())
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    // weight 占宽度；heightIn(min) 保证空内容时不塌扁，且不会因 fillMaxHeight 把列表挤掉
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = searchText,
                        onValueChange = { searchText = it.take(30) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (searchText.isEmpty()) Text("搜索成员名字", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                inner()
                            }
                        }
                    )
                }
                if (searchText.isNotEmpty()) {
                    Text("✕", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.clickable(
                            onClick = { searchText = "" },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ).padding(4.dp))
                }
            }
            HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider(), thickness = 0.5.dp)

            // @全体成员（群主 / 群管理员可；官方群群主同样可使用）
            if (canAtAll) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClick = { onSelect("@全体成员") },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(com.aurora.chat.ui.theme.appSelectedBg()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("@", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("@全体成员", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium)
                }
                HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider(), thickness = 0.5.dp)
            }

            // 成员名单（按首字母 A-Z 分组，其余归入 #）
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                sections.forEach { (letter, list) ->
                    item(key = "sec_$letter") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(com.aurora.chat.ui.theme.appCardBg())
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                        ) {
                            Text(letter.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextHint())
                        }
                    }
                    itemsIndexed(list, key = { index, m -> m.id.takeUnless { it == 0L } ?: -index }) { _, m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    onClick = { onSelect("@" + m.name) },
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                )
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            com.aurora.chat.ui.components.UserAvatar(
                                userId = m.id,
                                userName = m.name,
                                size = 40.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(m.name, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.isOwner) {
                                Spacer(Modifier.width(6.dp))
                                Text("群主", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/**
 * 陌生人首条消息来源 → 接收方系统广播文案。
 * 来源值约定：search / group:<群名>。
 */
private fun buildContactSourceBroadcast(source: String): String {
    return when {
        source == "search" -> "对方通过搜索与你联系"
        source.startsWith("group:") -> "对方通过${source.substring(6)}群聊与你联系"
        else -> ""
    }
}

/**
 * 组装「任务型会话」的系统提示 —— 主发送链路与内联工具循环共用同一份实现。
 *
 * 【前缀静态化·硬规矩】这里只允许放**跨回合稳定**的内容：人格设定、高级选项、多次输出约束、
 * 格式化要求、系统能力提示(awareness)。每回合会变的（当前时间、记忆检索、工具轮数提示、
 * 方案执行约束、续写约束）**一律不许进来** —— system 全文跨回合逐字节稳定，上游(DeepSeek 等)的
 * 自动前缀缓存才能命中，命中价约为未命中的 1/10。动态内容统一由调用方作为**末尾 user 提醒**注入。
 *
 * 历史事故（勿再复现）：主链路曾在这里拼入【当前时间】——每轮时间不同 → system 每轮都变 → 前缀
 * 缓存每轮必失效，等于白扔掉整个静态化设计；且时间又被末尾 user 提醒注入一次，同一轮出现两遍。
 *
 * 两条链路共用本函数的另一个原因：组装结果若不同（曾差一个【格式化要求】），缓存前缀会在
 * 「主链路 ↔ 工具循环」之间反复重建，命中率同样上不去。
 */
// 工具调用系统提示已清理：system 仅保留「性格调试」内容，其它能力/格式化/工具协议一律不再注入。
private fun buildAISystemPrompt(ctx: Context, userId: Long, personaText: String): String = personaText

/**
 * 群公告图片全屏查看器：真全屏（usePlatformDefaultWidth=false）+ 黑底 + 双指缩放/拖动。
 * 之前的实现没关平台默认宽度，实际是个小窗口，才出现"四周空白、中间一块黑"。
 */
@Composable
private fun AnnouncementFullscreenImageViewer(url: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 5f)
                        scale = next
                        if (next <= 1.001f) offset = Offset.Zero else offset += pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            var announceAspect by remember(url) { mutableStateOf(0f) }
            // 变换挂在容器上（而不是图片自己身上），这样「区域解码高清层」能和预览图共用同一套
            // scale/offset —— 放大后从原图取 1:1 像素，长图也能看清。
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
            ) {
                coil.compose.AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { res ->
                        val d = res.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                            announceAspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                        }
                    }
                )
                com.aurora.chat.ui.chat.media.RegionZoomOverlay(
                    url = url,
                    aspect = announceAspect,
                    scale = scale,
                    offset = offset,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(16.dp).size(36.dp)
                    .clip(RoundedCornerShape(50.dp)).background(Color(0x66000000))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 聊天顶栏的顶部安全区（稳定值）。
 *
 * 全屏看图/看视频会临时隐藏系统栏；退出时系统栏重新出现，statusBarsPadding 会从 0 补到真实高度
 * （inset 带显隐动画），顶栏就会跟着"从上往下"动一下。这里把状态栏高度冻结成稳定值：
 * 全屏期间不采样；退出后等 inset 稳定一小段时间再采纳（此时数值与冻结值相同，画面不会有位移）。
 */
@Composable
fun rememberStableStatusBarTopPadding(freeze: Boolean): Dp {
    val density = LocalDensity.current
    val livePx = WindowInsets.statusBars.getTop(density)
    var stablePx by remember { mutableIntStateOf(livePx) }
    LaunchedEffect(livePx, freeze) {
        if (freeze || livePx <= 0) return@LaunchedEffect
        // 首次拿到有效值（组合早期 inset 可能还没派发）：直接采纳，避免进场时顶栏下跳
        if (stablePx <= 0) {
            stablePx = livePx
            return@LaunchedEffect
        }
        // 系统栏显隐动画期间 livePx 逐帧变化，每次变化都会重启本 effect；
        // 只有数值稳定下来才采纳 → 顶栏不跟着 inset 动画走。
        kotlinx.coroutines.delay(150L)
        stablePx = livePx
    }
    return with(density) { stablePx.toDp() }
}

/**
 * 跨会话保留的消息存储适配器缓存（按 friendId 索引，LRU 最多 [MAX] 个会话）。
 *
 * 修复「顶部消息消失」：原实现用 `remember(friendId) { MessageStoreAdapter() }`，
 * 切走再切回会话时 friendId 变化导致适配器被销毁重建，入口重新 [MessageStoreAdapter.loadWindowed]
 * 把可见窗口重置成「最近 120 条」，之前上滑加载出来的更旧历史被丢回 store 不可见。
 * 这里按 friendId 缓存适配器实例，离开再回来时复用同一份（含已加载窗口与已上滑历史）。
 *
 * 但缓存本身必须有上限：每个适配器都持有一整段对话的列式 store，
 * 无上限地按会话累积等于「聊过多少会话就常驻多少份完整历史」。
 * 超出后淘汰最久未使用的会话，下次进入重新读盘即可（后台线程，几十毫秒级）。
 */
private object ConversationAdapterCache {
    private const val MAX = 4

    private val map = object : java.util.LinkedHashMap<Long, MessageStoreAdapter>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MessageStoreAdapter>): Boolean =
            size > MAX
    }

    operator fun get(friendId: Long): MessageStoreAdapter = synchronized(map) {
        map[friendId] ?: MessageStoreAdapter()
            .also { it.setWindowCap(CHAT_WINDOW_CAP) }
            .also { map[friendId] = it }
    }
}

/**
 * 可见窗口硬上限（条）。UI 的 SnapshotStateList、LazyColumn 的 item 数量、组合数量都锚定在这个值上，
 * 与对话本身有多少条消息无关 —— 这是「永远不会随消息量变卡」的关键。
 *
 * 为什么是 260：一屏大约 8~12 条气泡，260 条 ≈ 20 多屏，足够覆盖任何「手指连滑」的距离；
 * 留出 INITIAL_WINDOW(120) 之上 140 条的向上缓冲，正常阅读几乎碰不到上限，
 * 真碰到了也是从看不见的另一端丢弃，视口不跳。
 */
private const val CHAT_WINDOW_CAP = 260

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatInputBarArea(
    // session params
    currentUserId: Long,
    currentUserName: String,
    friendId: Long,
    friendName: String,
    contactSource: String,
    onContactSourceConsumed: () -> Unit,
    onTokenExhausted: () -> Unit,
    onAppAction: (suspend (AiChatManager.AppAction) -> AiChatManager.AppActionResult)?,
    // context / scope / list
    ctx: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    listState: androidx.compose.foundation.lazy.LazyListState,
    draftKey: String,
    quoteKey: String,
    // shared collections (same instances as the parent screen)
    messages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>,
    pendingUploadPlaceholders: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>,
    pendingFailedMessages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>,
    aiConvs: androidx.compose.runtime.snapshots.SnapshotStateList<AiConvMeta>,
    uploadProgressMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Float>,
    uploadStageMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, String>,
    uploadRatioMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Float>,
    uploadFileNameMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, String>,
    recentlySentMessages: MutableMap<String, Long>,
    replyToMsg: MutableState<IMessageRef?>,
    selectedMsgIds: MutableState<Set<Long>>,
    aiUsageState: MutableState<Pair<Long, Long>>,
    // activity result launchers
    aiImagePicker: androidx.activity.compose.ManagedActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest, List<Uri>>,
    aiFilePicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    // parent local functions (passed as lambdas)
    addLocalNotice: (Long, String, String) -> Unit,
    closeStaleAiWaiting: (Long) -> Unit,
    exitMultiSelect: () -> Unit,
    insertAiFileCard: (String, Boolean, Long) -> Unit,
    insertAiMediaCard: (String, String, Long) -> Unit,
    markSendFailed: (ChatMsg) -> Unit,
    programmaticScroll: (Int) -> Unit,
    scheduleAiDebouncedSave: () -> Unit,
    updatePlanStep: (Int, String) -> Unit,
    openVideoPlayer: (String) -> Unit,
    toolApprovalHook: suspend (String, String) -> Boolean,
    onConfirmReply: () -> Unit,
    // input bar states (same MutableState objects as the parent screen)
    inputTextState: MutableState<String>,
    autoFollowState: MutableState<Boolean>,
    pendingScrollToBottomState: MutableState<Boolean>,
    unreadNewCountState: MutableState<Int>,
    anchorItemIndexState: MutableState<Int>,
    apiSuspendedDialogState: MutableState<Boolean>,
    groupMemberNamesState: MutableState<Set<String>>,
    groupRolesState: MutableState<Map<Long, String>>,
    quoteFocusTickState: MutableState<Int>,
    inputBarTopYState: MutableState<Float>,
    showTransferDialogState: MutableState<Boolean>,
    showRedPacketDialogState: MutableState<Boolean>,
    showCameraState: MutableState<Boolean>,
    showPrivacyPanelState: MutableState<Boolean>,
    mutedUntilState: MutableState<Long>,
    showLocationConfirmState: MutableState<Boolean>,
    showTimePointDialogState: MutableState<Boolean>,
    showConfirmSpeechDialogState: MutableState<Boolean>,
    showAiImageNoticeState: MutableState<Boolean>,
    confirmSpeechEnabledState: MutableState<Boolean>,
    currentTimePointState: MutableState<String>,
    confirmSpeechPendingState: MutableState<Boolean>,
    pendingAiImagesState: MutableState<List<PendingAiImage>>,
    pendingAiFilesState: MutableState<List<PendingAiFile>>,
    wsPickModeState: MutableState<Boolean>,
    wsInjectForSendState: MutableState<List<Triple<Uri, String, Long>>>,
    editTargetMsgIdState: MutableState<Long>,
    showBroadcastSendDialogState: MutableState<Boolean>,
    aiFileSendingTipState: MutableState<String?>,
    agentAccessModeState: MutableState<String>,
    agentModeState: MutableState<String>,
    sendUserTurnState: MutableState<() -> Unit>,
    resumeTaskPendingState: MutableState<Boolean>,
    showThinkingLevelMenuState: MutableState<Boolean>,
    thinkingLevelState: MutableState<Int>,
    execModesState: MutableState<Set<String>>,
    showWorkspaceFilesState: MutableState<Boolean>,
    modeExplainDialogState: MutableState<String?>,
    multiSelectModeState: MutableState<Boolean>,
    multiDeleteConfirmState: MutableState<Boolean>,
    chatShareCardTextState: MutableState<String>,
    destroyedFlashIdsState: MutableState<Set<Long>>,
    readSheetMsgIdState: MutableState<Long>,
    payDisabledState: MutableState<Boolean>,
    payBlockReasonState: MutableState<String?>,
    showPlusDialogState: MutableState<Boolean>,
    showStickerPanelState: MutableState<Boolean>,
    showChatShareDialogState: MutableState<Boolean>,
    activeAiConvIdState: MutableState<Long>,
) {
    var inputText by inputTextState
    var autoFollow by autoFollowState
    var pendingScrollToBottom by pendingScrollToBottomState
    var unreadNewCount by unreadNewCountState
    var anchorItemIndex by anchorItemIndexState
    var apiSuspendedDialog by apiSuspendedDialogState
    var groupMemberNames by groupMemberNamesState
    var groupRoles by groupRolesState
    var quoteFocusTick by quoteFocusTickState
    var inputBarTopY by inputBarTopYState
    var showTransferDialog by showTransferDialogState
    var showRedPacketDialog by showRedPacketDialogState
    var showCamera by showCameraState
    var showPrivacyPanel by showPrivacyPanelState
    var mutedUntil by mutedUntilState
    var showLocationConfirm by showLocationConfirmState
    var showTimePointDialog by showTimePointDialogState
    var showConfirmSpeechDialog by showConfirmSpeechDialogState
    var showAiImageNotice by showAiImageNoticeState
    var confirmSpeechEnabled by confirmSpeechEnabledState
    var currentTimePoint by currentTimePointState
    var confirmSpeechPending by confirmSpeechPendingState
    var pendingAiImages by pendingAiImagesState
    var pendingAiFiles by pendingAiFilesState
    var wsPickMode by wsPickModeState
    var wsInjectForSend by wsInjectForSendState
    var editTargetMsgId by editTargetMsgIdState
    var showBroadcastSendDialog by showBroadcastSendDialogState
    var aiFileSendingTip by aiFileSendingTipState
    var agentAccessMode by agentAccessModeState
    var agentMode by agentModeState
    var sendUserTurn by sendUserTurnState
    var resumeTaskPending by resumeTaskPendingState
    var showThinkingLevelMenu by showThinkingLevelMenuState
    var thinkingLevel by thinkingLevelState
    var execModes by execModesState
    var showWorkspaceFiles by showWorkspaceFilesState
    var modeExplainDialog by modeExplainDialogState
    var multiSelectMode by multiSelectModeState
    var multiDeleteConfirm by multiDeleteConfirmState
    var chatShareCardText by chatShareCardTextState
    var destroyedFlashIds by destroyedFlashIdsState
    var readSheetMsgId by readSheetMsgIdState
    var payDisabled by payDisabledState
    var payBlockReason by payBlockReasonState
    var showPlusDialog by showPlusDialogState
    var showStickerPanel by showStickerPanelState
    var showChatShareDialog by showChatShareDialogState
    var activeAiConvId by activeAiConvIdState

            if (!multiSelectMode) {
            // AI 对话「图片」附件条：选择后暂存在输入框正上方，随发送按钮一起发出去
            if (friendId == AI_CHAT_ID && pendingAiImages.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSelectedBg()).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("图片", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                        modifier = Modifier.padding(end = 10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(pendingAiImages) { index, img ->
                            Box {
                                Box(
                                    Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                        .background(com.aurora.chat.ui.theme.appDivider())
                                ) {
                                    coil.compose.AsyncImage(
                                        model = img.uri,
                                        contentDescription = img.name,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Box(
                                    Modifier.size(18.dp).align(Alignment.TopEnd)
                                        .clip(CircleShape).background(Color(0xCC111827))
                                        .clickable {
                                            pendingAiImages = pendingAiImages.toMutableList().apply { removeAt(index) }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✕", fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(20.dp).clickable { pendingAiImages = emptyList() }, contentAlignment = Alignment.Center) {
                        Text("✕", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                }
            }
            // AI 对话「文件」附件条：选择后暂存在输入框正上方，随发送按钮一起发出去
            if (friendId == AI_CHAT_ID && pendingAiFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSelectedBg()).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("文件", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.padding(end = 10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(pendingAiFiles) { index, file ->
                            Box {
                                Row(
                                    Modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
                                        // 工作区引用用绿色区分（文件已在工作区内，发送时不会重新上传拷贝）
                                        .background(if (file.wsRelPath.isNotBlank()) Color(0xFFDCFCE7) else com.aurora.chat.ui.theme.appSelectedBg())
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                                        contentDescription = null,
                                        tint = if (file.wsRelPath.isNotBlank()) Color(0xFF15803D) else Color(0xFF0284C7),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        file.name,
                                        fontSize = 12.sp,
                                        color = Color(0xFF1E40AF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 140.dp)
                                    )
                                }
                                Box(
                                    Modifier.size(16.dp).align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp)
                                        .clip(CircleShape).background(Color(0xCC111827))
                                        .clickable {
                                            pendingAiFiles = pendingAiFiles.toMutableList().apply { removeAt(index) }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    // 取消按钮 X：使用 TextAlign.Center + 严格 lineHeight = fontSize + 1.sp，避免默认行高导致“偏下”
                                    Text(
                                        "✕",
                                        fontSize = 10.sp,
                                        color = Color.White,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = androidx.compose.ui.text.TextStyle(lineHeight = 11.sp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(20.dp).clickable { pendingAiFiles = emptyList() }, contentAlignment = Alignment.Center) {
                        Text(
                            "✕",
                            fontSize = 13.sp,
                            color = com.aurora.chat.ui.theme.appTextHint(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = androidx.compose.ui.text.TextStyle(lineHeight = 14.sp)
                        )
                    }
                }
            }
            // AI 对话「工具行」：位于输入框正上方，浅灰底与输入栏区分、无缝衔接（无圆角无间距）。
            if (friendId == AI_CHAT_ID) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .padding(horizontal = 12.dp, vertical = 0.dp)
                        .height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 思考深度按钮：点击向上滑出选择菜单
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showThinkingLevelMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Psychology,
                            contentDescription = "思考深度",
                            tint = when (thinkingLevel) {
                                AiChatManager.THINKING_OFF -> com.aurora.chat.ui.theme.appTextHint()
                                AiChatManager.THINKING_LOW -> Color(0xFF60A5FA)
                                AiChatManager.THINKING_HIGH -> Color(0xFF7C3AED)
                                else -> Color(0xFF2563EB)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 工作区按钮：打开 AI 工作区文件管理（全屏面板，右侧滑入，可新建/编辑文件与文件夹）
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showWorkspaceFiles = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Folder,
                            contentDescription = "工作区",
                            tint = Color(0xFF7C3AED),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 设置（齿轮）：打开 AI 显示设置全屏页
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showAiSettings.value = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = Color(0xFF4B5563),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            // 群聊气泡下方「N人已读」点开：底部抽屉列出到底谁读了
            if (readSheetMsgId > 0L) {
                ModalBottomSheet(
                    onDismissRequest = { readSheetMsgId = 0L },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = com.aurora.chat.ui.theme.appSurface()
                ) {
                    ReadReceiptSheetContent(readSheetMsgId)
                }
            }
            // AI「思考深度」菜单：从底部向上滑出（Off / Low / Medium / High，选中即持久化）
            if (friendId == AI_CHAT_ID && showThinkingLevelMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showThinkingLevelMenu = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    ) {
                        Text(
                            "思考深度",
                            fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        // 只显示英文档位，不再重复「不思考/低/中/高」那套字样
                        val options = listOf(
                            AiChatManager.THINKING_OFF to "Off",
                            AiChatManager.THINKING_LOW to "Low",
                            AiChatManager.THINKING_MEDIUM to "Medium",
                            AiChatManager.THINKING_HIGH to "High"
                        )
                        options.forEachIndexed { i, (level, label) ->
                            val on = thinkingLevel == level
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) com.aurora.chat.ui.theme.appSelectedBg() else com.aurora.chat.ui.theme.appCardBg())
                                    .border(if (on) 1.dp else 0.dp, Color(0xFF2563EB), RoundedCornerShape(10.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    ) {
                                        thinkingLevel = level
                                        AiChatManager.setThinkingLevel(ctx, currentUserId, level)
                                        showThinkingLevelMenu = false
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    label,
                                    fontSize = 15.sp,
                                    color = if (on) Color(0xFF2563EB) else com.aurora.chat.ui.theme.appTextSecondary(),
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (on) Text("✓", fontSize = 15.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.Bold)
                            }
                            if (i < options.lastIndex) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            // 模式说明弹窗（样式参照「权限控制/权限审批」弹窗）
            if (friendId == AI_CHAT_ID && modeExplainDialog != null) {
                val modeName = when (modeExplainDialog) {
                    AiChatManager.MODE_ASK -> "Ask"
                    AiChatManager.MODE_PLAN -> "Plan"
                    else -> "Craft"
                }
                val modeDesc = when (modeExplainDialog) {
                    AiChatManager.MODE_ASK -> "仅能对话无访问、修改、创建文件等权限"
                    AiChatManager.MODE_PLAN -> "开启后 AI 会根据你的所需，先在工作区的目录下创建规划方案然后才考虑执行"
                    else -> "拥有完整的AI能力可完成工具的调用有基础权限"
                }
                AlertDialog(
                    onDismissRequest = { modeExplainDialog = null },
                    title = { Text("$modeName 模式", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                    text = { Text(modeDesc, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), lineHeight = 22.sp) },
                    confirmButton = {
                        TextButton(onClick = { modeExplainDialog = null }) { Text("知道了", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF)) }
                    }
                )
            }

            InputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                friendId = friendId,
                showPlusDialog = showPlusDialog,
                onTogglePlus = { showPlusDialog = !showPlusDialog },
                onClosePlus = { showPlusDialog = false },
                showStickerPanel = showStickerPanel,
                onShowStickerPanelChange = { showStickerPanel = it },
                modifier = Modifier.onGloballyPositioned { inputBarTopY = it.positionInRoot().y },
                    onOpenCamera = { showCamera = true },
                    onRequestLocation = { showLocationConfirm = true },
                    onSecretClick = { showPrivacyPanel = true },
                    onTransfer = { showTransferDialog = true },
                    onRedPacket = { showRedPacketDialog = true },
                    // 转账 / 发红包门控：非好友 / 拉黑 / 对方开启验证 等场景禁用（由屏幕层按关系状态计算）
                    payDisabled = payDisabled,
                    payBlockReason = payBlockReason ?: "",
                    // 开发者专属「广播」入口（仅 ID=1 可见）。功能待实现，暂以 Toast 占位，不调用任何接口。
                    onBroadcast = {
                        showBroadcastSendDialog = true
                    },
                    mutedUntil = mutedUntil,
                    isAiChat = friendId == AI_CHAT_ID,
                    onTimePoint = { showTimePointDialog = true },
                    onConfirmSpeech = { showConfirmSpeechDialog = true },
                    confirmSpeechEnabled = confirmSpeechEnabled,
                    onConfirmReply = onConfirmReply,
                    onPickAiImage = {
                        // 首次使用先弹提示，避免用户误以为图片一定能被识别
                        val noticePrefs = ctx.getSharedPreferences("aurora_ai_notice", Context.MODE_PRIVATE)
                        if (noticePrefs.getBoolean("image_vision_notice", false)) {
                            aiImagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else {
                            showAiImageNotice = true
                        }
                    },
                    // 「+ → 文件」的来源选择弹窗在 InputBar 内（本地文件 / 工作区，所有会话类型通用）：
                    // 这里只需分别接住两个落地点。
                    onPickAiFile = { aiFilePicker.launch(arrayOf("*/*")) },
                    onPickWorkspaceFile = { wsPickMode = true; showWorkspaceFiles = true },
                    quoteFocusTick = quoteFocusTick,
                    // 语音消息：录完的 m4a 走与图片/视频同一条上传队列（断网/退出后自动续传），
                    // 先插占位气泡，上传成功由 UploadEvent.Succeeded 换成真实消息。
                    onSendVoice = { path, secs ->
                        val voiceUri = android.net.Uri.fromFile(java.io.File(path))
                        val localId = com.aurora.chat.data.upload.MediaUploadManager.enqueueFromUri(
                            ctx, friendId, isGroupChat(friendId), voiceUri, "audio/mp4", "", 0,
                            voiceDuration = secs
                        )
                        if (localId >= 0) {
                            messages.add(
                                ChatMsg.create(
                                    serverId = localId, text = "", isMine = true, fromUserId = currentUserId,
                                    isNew = true, mediaType = "voice", mediaUrl = "", isUploading = true,
                                    mediaWidth = secs
                                )
                            )
                            pendingUploadPlaceholders.add(messages.last())
                            uploadProgressMap[localId] = 0f
                            uploadFileNameMap[localId] = "语音 $secs″"
                            uploadStageMap[localId] = "compressing"
                            scope.launch { if (messages.isNotEmpty()) listState.scrollToItem(0) }
                        } else {
                            AuroraApi.showErrorToast(ctx, "语音发送失败")
                        }
                    },
                    injectedSelectedFiles = wsInjectForSend,
                    onInjectedSelectedFilesConsumed = { wsInjectForSend = emptyList() },
                    // 表情面板：点击表情 → 立即发送 sticker 消息。
                    // content 存 "[表情]"（会话预览/引用/推送通知天然正确），真正的资源在
                    // media_url = sticker://<分组>/<文件名>，接收端气泡按 assets 渲染，不走网络。
                    onSendSticker = { id ->
                        val stickerUrl = "sticker://$id"
                        val placeholder = ChatMsg.create(
                            text = "[表情]", isMine = true, fromUserId = currentUserId,
                            isNew = true, mediaType = "sticker", mediaUrl = stickerUrl
                        )
                        if (!isLocalChat(friendId)) messages.add(placeholder)
                        autoFollow = true
                        anchorItemIndex = -1
                        unreadNewCount = 0
                        pendingScrollToBottom = true
                        scope.launch { kotlinx.coroutines.delay(50); if (messages.isNotEmpty()) listState.scrollToItem(0) }
                        ChatViewModel.sendMessage(
                            currentUserId = currentUserId,
                            friendId = friendId,
                            text = "[表情]",
                            mediaType = "sticker",
                            mediaUrl = stickerUrl,
                            onSuccess = { serverMsgId ->
                                if (serverMsgId > 0) {
                                    val si = messages.indexOfFirst { it.id == placeholder.id }
                                    if (si >= 0) messages[si] = (messages[si] as? ChatMsg)?.copy(serverId = serverMsgId) ?: messages[si]
                                    // 记录真实类型：重进对话加载时用本机记录覆盖服务端返回，杜绝"刷新后表情变文件卡片"
                                    com.aurora.chat.data.local.LocalMediaTypeStore.put(serverMsgId, "sticker")
                                }
                                ChatViewModel.ensureConversation(friendId, friendName, "[表情]", System.currentTimeMillis() / 1000)
                            },
                            onError = { msg ->
                                // 通用失败处理：贴纸气泡原地转红叹号 + 落盘，终端业务错误（拉黑/好友验证…）打印本地广播
                                markSendFailed(placeholder)
                                val sf = classifySendFailure(msg)
                                if (sf != null && sf.logout) {
                                    AuroraApi.showErrorToast(ctx, sf.text)
                                    scope.launch { ChatRepository.checkAndLogoutCurrentUser(ctx, currentUserId) }
                                    messages.removeAll { it.id == placeholder.id }
                                    pendingFailedMessages.removeAll { it.id == placeholder.id }
                                } else if (sf != null) {
                                    addLocalNotice(friendId, sf.text, "send_blocked")
                                }
                                // 瞬时失败：气泡已经原地转红叹号，点击可重发。
                                // 不再弹「发送失败」提示，避免批量发送时提示满天飞。
                            },
                            context = ctx
                        )
                    },
                    // emoji tab：点击内置 emoji 字符 → 立即发送 emoji 消息。
                    // content 存 emoji 字符本身（会话预览/引用/推送通知天然正确），media_url 也存字符，
                    // 接收端气泡按 mediaType=="emoji" 命中大字号渲染分支，不走网络、无资源加载。
                    onSendEmoji = { emoji ->
                        val placeholder = ChatMsg.create(
                            text = emoji, isMine = true, fromUserId = currentUserId,
                            isNew = true, mediaType = "emoji", mediaUrl = emoji
                        )
                        if (!isLocalChat(friendId)) messages.add(placeholder)
                        autoFollow = true
                        anchorItemIndex = -1
                        unreadNewCount = 0
                        pendingScrollToBottom = true
                        scope.launch { kotlinx.coroutines.delay(50); if (messages.isNotEmpty()) listState.scrollToItem(0) }
                        ChatViewModel.sendMessage(
                            currentUserId = currentUserId,
                            friendId = friendId,
                            text = emoji,
                            mediaType = "emoji",
                            mediaUrl = emoji,
                            onSuccess = { serverMsgId ->
                                if (serverMsgId > 0) {
                                    val si = messages.indexOfFirst { it.id == placeholder.id }
                                    if (si >= 0) messages[si] = (messages[si] as? ChatMsg)?.copy(serverId = serverMsgId) ?: messages[si]
                                    // 记录真实类型：重进对话加载时用本机记录覆盖服务端返回，杜绝"刷新后 emoji 变文件卡片"
                                    com.aurora.chat.data.local.LocalMediaTypeStore.put(serverMsgId, "emoji")
                                }
                                ChatViewModel.ensureConversation(friendId, friendName, emoji, System.currentTimeMillis() / 1000)
                            },
                            onError = { msg ->
                                // 通用失败处理：emoji 气泡原地转红叹号 + 落盘，终端业务错误打印本地广播
                                markSendFailed(placeholder)
                                val sf = classifySendFailure(msg)
                                if (sf != null && sf.logout) {
                                    AuroraApi.showErrorToast(ctx, sf.text)
                                    scope.launch { ChatRepository.checkAndLogoutCurrentUser(ctx, currentUserId) }
                                    messages.removeAll { it.id == placeholder.id }
                                    pendingFailedMessages.removeAll { it.id == placeholder.id }
                                } else if (sf != null) {
                                    addLocalNotice(friendId, sf.text, "send_blocked")
                                }
                                // 瞬时失败：气泡已经原地转红叹号，点击可重发。
                                // 不再弹「发送失败」提示，避免批量发送时提示满天飞。
                            },
                            context = ctx
                        )
                    },
                    // ── 引用条：渲染在 InputBar 三组件 Row 之上，即消息区与输入栏之间 ──
                    // 由 InputBar 内部渲染而不是屏幕层 Column，是为了落在 navigationBarsPadding 之上：
                    // 键盘弹出时它始终贴着键盘，不会被导航栏挤出屏幕。
                    // 高度动画方向取 Bottom，横幅从底部向上长出，三组件被平滑推下，不再一帧跳变。
                    quoteBanner = {
                        // 全限定名：此处仍处在屏幕层的 ColumnScope 内，直接写会被解析成 ColumnScope 扩展版本。
                        androidx.compose.animation.AnimatedVisibility(
                            visible = replyToMsg.value != null,
                            enter = expandVertically(animationSpec = tween(200), expandFrom = Alignment.Bottom) + fadeIn(tween(160)),
                            exit = shrinkVertically(animationSpec = tween(200), shrinkTowards = Alignment.Bottom) + fadeOut(tween(120))
                        ) {
                            val ref = replyToMsg.value
                            val isGroupRef = isGroupChat(friendId)
                            val refName = ref?.senderName?.ifBlank {
                                if (ref.isMine) "我" else if (isGroupRef) "对方" else ""
                            } ?: ""
                            // ⚠️ 媒体类型必须排在 text 之前判断：
                            // 语音消息的 content 存的是时长（如 "1"），file 类型的旧消息也可能 content 非空，
                            // 先看 text 会把引用预览显示成一个孤零零的数字。
                            // 表情引用的内容必须"直接显示表情包"，不能落到 "[文件]" 文字。
                            // mediaType 不可靠：MessageStore 旧数据会把 sticker/emoji 压成 file
                            // （已在 MessageStore 补媒体位修根因，但历史 store 数据仍可能读到 file），
                            // 因此以 mediaUrl 的 sticker:// 协议串 / emoji 字符特征为准。
                            val refStickerRel = ref?.mediaUrl
                                ?.takeIf { it.startsWith("sticker://") }
                                ?.removePrefix("sticker://")?.trim()?.trim('/') ?: ""
                            val refEmojiChar = ref?.let { r ->
                                val c = r.mediaUrl.ifBlank { r.text }
                                if (r.mediaType == "emoji" ||
                                    (c.isNotEmpty() && isEmojiMediaUrl(c))) c else ""
                            } ?: ""
                            val refContent = when {
                                ref == null -> ""
                                ref.isRevoked == 1 -> if (ref.isMine) "我撤回了一条消息" else "对方撤回了一条消息"
                                refStickerRel.isNotEmpty() -> "[表情]"
                                refEmojiChar.isNotEmpty() -> ""
                                ref.mediaType == "voice" -> "[语音]"
                                ref.mediaType == "sticker" -> "[表情]"
                                ref.mediaType == "image" -> "[图片]"
                                ref.mediaType == "video" || ref.mediaType == "mp4" -> "[视频]"
                                ref.mediaType == "file" -> "[文件]"
                                ref.mediaType == "transfer" -> "[转账]"
                                ref.flashDuration > 0 -> "[闪照]"
                                ref.mediaType.isNotEmpty() -> "[媒体]"
                                ref.text.isNotBlank() -> ref.text.take(40) + if (ref.text.length > 40) "…" else ""
                                ref.mediaUrl.isNotEmpty() -> "[媒体]"
                                else -> "[空消息]"
                            }
                            // 表情引用：直接显示表情包本身（sticker 缩略 / emoji 字符），
                            // 而不是 "[文件]" 或 "[表情]" 这类文字标签。
                            // 历史引用可能指向已下架的表情，先探测 assets 是否还在，缺失时给灰块兜底。
                            val stickerExists = androidx.compose.runtime.produceState<Boolean?>(
                                initialValue = null, refStickerRel
                            ) {
                                value = if (refStickerRel.isEmpty()) false
                                else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching {
                                        ctx.assets.open("stickers/$refStickerRel.webp").use { }
                                        true
                                    }.getOrDefault(false)
                                }
                            }.value
                            val stickerReq = remember(refStickerRel) {
                                if (refStickerRel.isEmpty()) null
                                else coil.request.ImageRequest.Builder(ctx)
                                    .data("file:///android_asset/stickers/$refStickerRel.webp")
                                    .decoderFactory(
                                        if (android.os.Build.VERSION.SDK_INT >= 28)
                                            coil.decode.ImageDecoderDecoder.Factory()
                                        else coil.decode.BitmapFactoryDecoder.Factory()
                                    ).build()
                            }
                            // 纯文字单行：无侧边竖条、无名字胶囊底色、无「回复」二字、无圆角卡片。
                            // 顶部细线只做区域分隔，不参与任何装饰。
                            Column(Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg())) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(com.aurora.chat.ui.theme.appDivider())
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (refName.isNotEmpty()) {
                                        Text(
                                            text = "$refName：",
                                            fontSize = 12.sp,
                                            color = Color(0xFF1E40AF),
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                    }
                                    when {
                                        // sticker：直接显示表情包缩略（与气泡内同一套动图解码器）
                                        refStickerRel.isNotEmpty() && stickerExists != false && stickerReq != null -> {
                                            coil.compose.AsyncImage(
                                                model = stickerReq,
                                                contentDescription = "表情",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(Modifier.weight(1f))
                                        }
                                        // 表情资源已下架：灰块 + 「表情」，绝不显示成文件
                                        refStickerRel.isNotEmpty() -> {
                                            Box(
                                                Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
                                                    .background(com.aurora.chat.ui.theme.appDivider()),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("表情", fontSize = 9.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = 1)
                                            }
                                            Spacer(Modifier.weight(1f))
                                        }
                                        // 平台内置 emoji：直接显示字符本身
                                        refEmojiChar.isNotEmpty() -> {
                                            Text(
                                                text = refEmojiChar,
                                                fontSize = 18.sp,
                                                maxLines = 1,
                                                modifier = Modifier.padding(end = 4.dp)
                                            )
                                            Spacer(Modifier.weight(1f))
                                        }
                                        else -> Text(
                                            text = refContent,
                                            fontSize = 12.sp,
                                            color = com.aurora.chat.ui.theme.appTextSecondary(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    // 纯字符关闭键，无圆形底、无描边；24dp 点击区不把单行撑高。
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) {
                                                replyToMsg.value = null
                                                saveQuoteDraft(ctx, quoteKey, null)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("×", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    }
                                }
                            }
                        }
                    },
                    // 发送按钮「停止 ↔ 发送」按【当前会话】判定：仅当正在生成的会话就是当前会话时才显示「停止」。
        // 修复跨会话污染：A 会话在后台生成时新建/切到 B 会话，B 的按钮不再误显示「停止」，且点「停止」不会误取消 A 的在途任务。
        // 双保险：非 AI 对话（普通聊天/群聊）的 activeAiConvId 恒为 -1L，即便哪天初值回归也绝不会误命中。
        aiInFlight = friendId == AI_CHAT_ID &&
                ((aiWriting.value && aiWritingConvId == activeAiConvId) ||
                (agentTaskInFlight.value && agentTaskInFlightConvId == activeAiConvId)),
        // 输入框为空、但挂着待发附件时，行尾那颗圆钮也要已经是「发送」形态，
                // 口径与下面 onSend 里的判定保持一致（见 8052 附近）。
                hasPendingAttachment = friendId == AI_CHAT_ID &&
                    (pendingAiImages.isNotEmpty() || pendingAiFiles.isNotEmpty()),
                    onStopSend = {
                        // 手动停止：仅当“正在生成/后台 AI 任务运行中”的会话就是当前会话时才生效（按会话隔离按钮状态）。
                        // 否则（如 A 会话在后台生成、当前停在 B 会话）不误取消 A 的在途任务，也避免 B 的按钮被污染。
                        if (friendId == AI_CHAT_ID &&
                            ((aiWriting.value && aiWritingConvId == activeAiConvId) ||
                            (agentTaskInFlight.value && agentTaskInFlightConvId == activeAiConvId))) {
                            aiStopRequested = true
                            // 【停止要保住收尾】这里不再「立即 cancel」：协程一旦被取消，取消异常会穿过整个
                            // 工具循环，把统一收尾整段跳过 —— 表现就是停止后 token 明细 / 耗时 / 工具计数 /
                            // 文件改动全部丢失（用户反馈「中断了就查不到消耗」）。
                            // 改为：先让循环通过 stopCheck 优雅收口（streamChat 看门狗 ~120ms 内停流，
                            // 走正常返回路径，收尾照常执行、数据全保住）；仅当 2 秒后协程仍未退出
                            // （例如正卡在长耗时的 shell / python 工具里，看门狗管不到）才用 cancel 强制中断，
                            // 保证「停止」在任何情况下都点得动。
                            val stoppingJob = activeAiJob
                            activeAiJob = null
                            if (stoppingJob != null) {
                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                    kotlinx.coroutines.delay(2000)
                                    if (stoppingJob.isActive) stoppingJob.cancel()
                                }
                            }
                            aiWriting.value = false
                            aiWritingConvId = -1L
                            // 停止瞬间思考卡片必须立刻消失（否则这次思考会被"定格"在屏上直到下次发送）
                            aiThinkingLive.value = false
                            agentTaskInFlight.value = false
                            agentTaskInFlightConvId = -1L
                            // ── 中断落定：把「等待模型响应」空窗收口成「已手动停止」 ──
                            // 判据不能只看 aiReplyState：它只在主发送链路写入，「确认发言」链路从不写，
                            // 于是从确认发言发起的回复被中断时复位直接失效 —— 气泡永远停在「等待模型响应」、
                            // 秒数一直跳，用户看到的假象就是「明明点了停止却没停住」。
                            // 因此改为直接扫描消息列表：所有仍在等待的 AI 气泡一律收口（清掉等待文案与进度提示，
                            // 标记 manualStopped 以露出「继续处理任务」）；一个字都没吐出的空白占位清空文本，
                            // 避免残留「思考中」字样。
                            var stoppedAny = false
                            for (i in messages.indices) {
                                val m = messages[i]
                                if (m !is ChatMsg || m.isMine || m.fromUserId != AI_CHAT_ID) continue
                                if (!m.aiWaiting) continue
                                val blankPlaceholder = m.text.isBlank() || m.text == AI_THINKING
                                messages[i] = m.copy(
                                    text = if (blankPlaceholder) "" else m.text,
                                    aiWaiting = false,
                                    aiProgressHint = "",
                                    manualStopped = true
                                )
                                stoppedAny = true
                            }
                            // 兜底：内容已开始流式（气泡不再是等待态）时，把最后一条 AI 气泡标为已停止，
                            // 保证「已手动停止 + 继续处理任务」一定出现，且已生成内容原样保留。
                            if (!stoppedAny) {
                                val lastAiIdx = messages.indexOfLast { it is ChatMsg && !it.isMine && it.fromUserId == AI_CHAT_ID }
                                if (lastAiIdx >= 0) {
                                    messages[lastAiIdx] = (messages[lastAiIdx] as ChatMsg)
                                        .copy(manualStopped = true, aiWaiting = false, aiProgressHint = "")
                                }
                            }
                            aiReplyState.value = aiReplyState.value?.copy(done = true, stopped = true)
                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, AiConversationSession.storageKey(), messages.filter { !it.isSystemNotice }) } catch (_: Exception) {} }
                        }
                    },
                    onSend = { sendUserTurn() }.also { sendUserTurn = {
                    // 「继续处理任务」标志在本轮发送入口一次性消费：无论后面被余额/网络拦截还是正常发出，
                    // 都不会残留到下一次普通发送（残留会误把普通对话也当成续写任务）。
                    val resumeTaskNow = resumeTaskPending
                    resumeTaskPending = false
                    // AI 对话「图片/文件」：允许在输入框文本为空、但带了附件时也能发送
                    if (inputText.isNotBlank() || (friendId == AI_CHAT_ID && (pendingAiImages.isNotEmpty() || pendingAiFiles.isNotEmpty()))) {
                        // 发送新消息即代表用户放弃对上一条「已手动停止」消息的「继续处理任务」：
                        // 清除所有消息的 manualStopped 标记，使其「继续处理任务」按钮与「已手动停止」标签失效
                        // （仅最后一条 AI 消息可继续；用户发新消息后上一条不再是最后一条，功能即失效）。
                        val stoppedIdxs = messages.indices.filter { messages[it] is ChatMsg && (messages[it] as ChatMsg).manualStopped }
                        for (i in stoppedIdxs) messages[i] = (messages[i] as ChatMsg).copy(manualStopped = false, aiWaiting = false)
                        val text = inputText.trim()
                        inputText = ""
                        // 清除草稿
                        ctx.getSharedPreferences("aurora_drafts", Context.MODE_PRIVATE)
                            .edit().remove(draftKey).apply()
                        // 第0关：发送前检查本地存储的用户名是否为"注销用户"
                        val loginPrefs = ctx.getSharedPreferences("aurora_login", android.content.Context.MODE_PRIVATE)
                        if (loginPrefs.getString("username", "") == "注销用户") {
                            android.widget.Toast.makeText(ctx, "您的账号已被注销，无法发送消息", android.widget.Toast.LENGTH_SHORT).show()
                            scope.launch { ChatRepository.checkAndLogoutCurrentUser(ctx, currentUserId) }
                        } else {
                        // 记录到最近发送集合（30 秒内防重，即使 onSuccess 后 loadMessages 触发也不会重复添加）
                        recentlySentMessages[text] = System.currentTimeMillis()
                        // 先把消息加到本地并开启动画，保证即时显示
                        // 媒体类型优先：语音的 text 存的是时长（"1"），文件也可能带脏数据，
                        // 先看 text 会把引用胶囊写成孤零零的数字。
                        //
                        // ⚠️ 这条文本会原样持久化到服务端的 reply_to_text：对方客户端、以及重进会话后
                        // 若被引用的原消息不在本地窗口，胶囊只能靠它兜底，写错就永远显示成文件块。
                        // 不能只信 mediaType —— MessageStore 旧数据会把 sticker/emoji 压成 "file"
                        // （见 encodeMediaType 的 MEDIA_STICKER/MEDIA_EMOJI 修复），所以先按
                        // mediaUrl 的协议串 / emoji 字符特征认表情，且必须排在 "file" 分支之前。
                        val replyText = replyToMsg.value?.let { msg ->
                            val isSticker = msg.mediaType == "sticker" || msg.mediaUrl.startsWith("sticker://")
                            val isEmoji = msg.mediaType == "emoji" ||
                                (msg.mediaUrl.isNotEmpty() && isEmojiMediaUrl(msg.mediaUrl))
                            when {
                                isSticker -> "[表情]"
                                isEmoji -> msg.mediaUrl.ifBlank { msg.text }.ifBlank { "[表情]" }
                                msg.mediaType == "voice" -> "[语音]"
                                msg.mediaType == "image" -> "[图片]"
                                msg.mediaType == "video" || msg.mediaType == "mp4" -> "[视频]"
                                msg.mediaType == "file" -> "[文件]"
                                msg.mediaType == "transfer" -> "[转账]"
                                msg.text.isNotEmpty() -> msg.text
                                else -> "[消息]"
                            }
                        } ?: ""
                        // 引用自己刚发出的消息时本地还没有发送者名（乐观消息），用当前用户名兜底，
                        // 否则群聊引用胶囊会缺发送者
                        val replySender = replyToMsg.value?.let { q ->
                            q.senderName.ifBlank { if (q.isMine) currentUserName else "" }
                        } ?: ""
                        val replyToId = replyToMsg.value?.id ?: 0L
                        replyToMsg.value = null   // 发送后清除引用
                        saveQuoteDraft(ctx, quoteKey, null)
                        val sentMsg: ChatMsg? = if (!isLocalChat(friendId)) {
                            ChatMsg.create(text = text, isMine = true, fromUserId = currentUserId, isNew = true, replyToText = replyText, replyToSender = replySender, replyToId = replyToId).also { messages.add(it) }
                        } else null
                        // 自己发消息：无条件回到底部 + 恢复自动跟随（微信/QQ 都是这么做的）。
                        // 真正的滚动交给 LaunchedEffect(messages.size) 等一帧再做 ——
                        // 这里新气泡还没被 LazyColumn 布局，直接 scrollToItem(0) 只会滚到旧的最底部。
                        autoFollow = true
                        anchorItemIndex = -1
                        unreadNewCount = 0
                        pendingScrollToBottom = true
                        // 统计发言字数
                        com.aurora.chat.ui.profile.WordCountTracker.addChars(ctx, text.length)

                        if (isLocalChat(friendId)) {
                            // 本地会话（AI 对话）整体异步执行：附件拷入工作区等 IO 不再阻塞主线程，
                            // 期间用 aiFileSendingTip 显示过渡提示，避免大文件（>300/500MB）无反馈卡死感。
                            scope.launch {
                            val key = localChatKey(friendId)
                            val nowSec = System.currentTimeMillis() / 1000
                            // 捕获本次要发送的 AI 图片/文件附件并立即清空输入框上方的附件条
                            val sentAiImages = if (friendId == AI_CHAT_ID) pendingAiImages else emptyList()
                            val sentAiFiles = if (friendId == AI_CHAT_ID) pendingAiFiles else emptyList()
                            if (friendId == AI_CHAT_ID) {
                                pendingAiImages = emptyList()
                                pendingAiFiles = emptyList()
                            }
                            // AI 文件：先拷进 AI 工作区（ai_files），让 AI 所有文件工具（list_files/read_file/edit_file/get_file）
                            // 直接用工作区相对路径就能访问——之前拷到 ai_chat/files 里 AI 根本找不到。
                            val aiFileAttachmentHints = mutableListOf<String>()
                            // 文件里包含的媒体类(图片/可嵌入视频)会转成 AiImage 走多模态输入,让模型直接看到内容
                            val aiMediaFromFiles = mutableListOf<Pair<ByteArray, String>>() // bytes + mimeType
                            // 读不到的附件（工作区文件已删、0 字节、拷贝失败）：原来直接 continue 静默跳过，
                            // 用户以为发出去了、AI 却什么都没收到，事后完全无法追溯。收集起来统一提示。
                            val failedAttachments = mutableListOf<String>()
                            // 过渡提示：只要带附件，进入处理阶段就显示提示（不分大小，大文件等待感更明显）
                            aiFileSendingTip = if (sentAiFiles.isNotEmpty()) "正在处理附件（${sentAiFiles.size} 个），请稍候…" else null
                            if (friendId == AI_CHAT_ID && sentAiFiles.isNotEmpty()) {
                                val workspace = AiChatManager.agentRoot(ctx)  // = filesDir/ai_files
                                for (f in sentAiFiles) {
                                    // 【工作区引用】文件本来就在 ai_files 内：不拷贝、不改名，直接用原相对路径交给 AI，
                                    // 避免在工作区里凭空多出一份带时间戳的副本。
                                    if (f.wsRelPath.isNotBlank()) {
                                        val src = java.io.File(workspace, f.wsRelPath)
                                        if (!src.exists() || src.length() == 0L) {
                                            failedAttachments.add(f.name)
                                            continue
                                        }
                                        val wExt = f.name.substringAfterLast('.', "").lowercase()
                                        val wMimeLower = f.mime.lowercase()
                                        val wIsImage = wMimeLower.startsWith("image/") ||
                                            wExt in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "ico", "tiff")
                                        val wIsVideo = wMimeLower.startsWith("video/") ||
                                            wExt in setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "m4v")
                                        // 工作区图片同样走多模态，让模型直接看到内容（体积上限与上传路径一致）
                                        if (wIsImage && src.length() <= 8 * 1024 * 1024) {
                                            val bytes = runCatching { src.readBytes() }.getOrNull()
                                            if (bytes != null && bytes.isNotEmpty()) {
                                                aiMediaFromFiles.add(bytes to f.mime.ifBlank { "image/$wExt" })
                                            }
                                        }
                                        // UI：直接引用工作区原文件落一条消息卡片（本地 file:// 渲染）
                                        messages.add(ChatMsg.create(
                                            serverId = System.currentTimeMillis() * 10000 + (0..9999).random(),
                                            text = "", isMine = true, fromUserId = currentUserId, isNew = true,
                                            mediaType = if (wIsImage) "image" else if (wIsVideo) "video" else "file",
                                            mediaUrl = "file://${src.absolutePath}", createdAt = nowSec))
                                        val wSizeStr = when {
                                            src.length() >= 1024 * 1024 -> "%.2f MB".format(src.length() / (1024.0 * 1024.0))
                                            src.length() >= 1024 -> "%.1f KB".format(src.length() / 1024.0)
                                            else -> "${src.length()} B"
                                        }
                                        aiFileAttachmentHints.add(
                                            if (wIsImage)
                                                "[用户引用了工作区图片: ${f.wsRelPath} ($wSizeStr),已直接作为图片输入传给你,请看一下内容后回答用户的问题]"
                                            else
                                                "[用户引用了工作区文件: ${f.wsRelPath} ($wSizeStr),可直接用 read_file 读取、edit_file 编辑,无需再次上传]"
                                        )
                                        continue
                                    }
                                    // 用安全文件名 + 时间戳防重名
                                    val safeName = f.name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
                                        .take(120)
                                    val dest = java.io.File(workspace,
                                        "${System.currentTimeMillis()}_${(0..9999).random()}_${safeName}")
                                    try {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                                                dest.outputStream().use { output -> input.copyTo(output) }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                    if (!dest.exists() || dest.length() == 0L) {
                                        failedAttachments.add(f.name)
                                        continue
                                    }
                                    val ext = f.name.substringAfterLast('.', "").lowercase()
                                    // 图片类文件:优先走多模态输入(直接喂 bytes 给模型),让模型能"看到"内容;
                                    // 同时也拷进工作区,让模型后续能用工具再读。视频类(mime 含 video)也尝试走多模态,
                                    // 但多数模型只支持图片,所以视频文件走"纯文本提示 + 可工具读取"的保底路径
                                    val mimeLower = f.mime.lowercase()
                                    val isImageMedia = mimeLower.startsWith("image/") ||
                                        ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "ico", "tiff")
                                    val isVideoMedia = mimeLower.startsWith("video/") ||
                                        ext in setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "m4v")
                                    if (isImageMedia && dest.length() <= 8 * 1024 * 1024) {
                                        // 图片≤8MB 直接读 bytes 走多模态(避免 base64 过大超限)
                                        val bytes = runCatching { dest.readBytes() }.getOrNull() ?: continue
                                        if (bytes.isNotEmpty()) aiMediaFromFiles.add(bytes to f.mime.ifBlank { "image/$ext" })
                                    }
                                    // 不管成功拷贝与否,仍在 ai_chat/files 里落一份文件卡片(UI 展示用)
                                    val uiDir = java.io.File(ctx.filesDir, "ai_chat/files")
                                    if (!uiDir.exists()) uiDir.mkdirs()
                                    val uiFile = java.io.File(uiDir, "${System.currentTimeMillis()}_${(0..9999).random()}_${safeName}")
                                    try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dest.inputStream().use { input -> uiFile.outputStream().use { output -> input.copyTo(output) } } } } catch (_: Exception) {}
                                    if (uiFile.exists()) {
                                        val mUrl = "file://${uiFile.absolutePath}"
                                        val mType = if (isImageMedia) "image" else if (isVideoMedia) "video" else "file"
                                        messages.add(ChatMsg.create(
                                            serverId = System.currentTimeMillis() * 10000 + (0..9999).random(),
                                            text = "", isMine = true, fromUserId = currentUserId, isNew = true,
                                            mediaType = mType, mediaUrl = mUrl, createdAt = nowSec))
                                    }
                                    // 给 AI 的提示:媒体类已经作为多模态输入喂进去了,让模型直接看内容;非媒体类维持纯文本提示让它调工具读
                                    val sizeStr = when {
                                        dest.length() >= 1024 * 1024 -> "%.2f MB".format(dest.length() / (1024.0 * 1024.0))
                                        dest.length() >= 1024 -> "%.1f KB".format(dest.length() / 1024.0)
                                        else -> "${dest.length()} B"
                                    }
                                    if (isImageMedia) {
                                        aiFileAttachmentHints.add("[用户发送了图片: ${f.name} ($sizeStr),已直接作为图片输入传给你,请看一下内容后回答用户的问题]")
                                    } else {
                                        val contentHint = if (f.bytes != null && dest.length() <= 400 * 1024) {
                                            val preview = dest.readBytes().decodeToString(throwOnInvalidSequence = false).take(4000)
                                            "[用户上传文件: ${f.name} ($sizeStr),已存入 AI 工作区,相对路径 ${dest.name}]\n```$ext\n$preview\n```"
                                        } else {
                                            "[用户上传文件: ${f.name} ($sizeStr),已存入 AI 工作区,相对路径 ${dest.name}。可调用 list_files 确认、read_file 直接读取、edit_file 编辑。]"
                                        }
                                        aiFileAttachmentHints.add(contentHint)
                                    }
                                }
                            }
                            // 有附件没读成功：必须让用户知道，不能让「发了却像没发」继续发生
                            if (failedAttachments.isNotEmpty()) {
                                runCatching {
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "有 ${failedAttachments.size} 个附件没能读取（${failedAttachments.joinToString("、")}），未发送给 AI",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            // 附件处理完成，关闭过渡提示（后续进入 AI 回复阶段，气泡自带"等待模型响应"状态）
                            aiFileSendingTip = null
                            // AI 图片：先添加图片气泡（图片在上），本地落盘 + 秒渲染（file:// 方式，与相机上传一致）
                            if (friendId == AI_CHAT_ID && sentAiImages.isNotEmpty()) {
                                val dir = java.io.File(ctx.filesDir, "ai_chat")
                                if (!dir.exists()) dir.mkdirs()
                                // 工作区根目录（ai_files）：把用户发的图也留一份副本，供 AI 后续用 list_files/get_file 等工具操作
                                val imgWorkspace = AiChatManager.agentRoot(ctx)
                                for (img in sentAiImages) {
                                    val ext = if (img.mime.contains("png")) "png" else "jpg"
                                    val file = java.io.File(dir, "${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
                                    try { java.io.FileOutputStream(file).use { it.write(img.bytes) } } catch (_: Exception) {}
                                    val mUrl = "file://${file.absolutePath}"
                                    messages.add(ChatMsg.create(
                                        serverId = System.currentTimeMillis() * 10000 + (0..9999).random(),
                                        text = "", isMine = true, fromUserId = currentUserId, isNew = true,
                                        mediaType = "image", mediaUrl = mUrl, createdAt = nowSec))
                                    // 复制副本到工作区，并提示 AI 相对路径（图片已作为多模态输入，这里只补充「可工具操作」的路径）
                                    val wsName = "ai_img_${System.currentTimeMillis()}_${(0..9999).random()}.$ext"
                                    val wsFile = java.io.File(imgWorkspace, wsName)
                                    try { wsFile.outputStream().use { it.write(img.bytes) } } catch (_: Exception) {}
                                    if (wsFile.exists() && wsFile.length() > 0L) {
                                        aiFileAttachmentHints.add("[用户发送了图片(已作为图片输入,副本已存入 AI 工作区,相对路径 $wsName),如需用工具处理(裁剪/分析文件/转格式)可直接用该相对路径]")
                                    }
                                }
                            }
                            // 文字消息气泡（放在图片/文件下方）；若有文件提示，把文件内容/提示单独给 AI 上下文
                            var currentAiTextMsgId = 0L
                            val textForAi = if (aiFileAttachmentHints.isNotEmpty()) {
                                if (text.isNotBlank()) text + "\n\n" + aiFileAttachmentHints.joinToString("\n\n")
                                else aiFileAttachmentHints.joinToString("\n\n")
                            } else text
                            // 只有用户真的输入了文字才发文字气泡；只发附件时不再强塞一条 "[文件]" 气泡
                            if (text.isNotBlank()) {
                                val selfMsgId = System.currentTimeMillis() * 10000 + (0..9999).random()
                                currentAiTextMsgId = selfMsgId
                                messages.add(ChatMsg.create(
                                    serverId = selfMsgId, text = text, isMine = true,
                                    fromUserId = currentUserId, isNew = true,
                                    replyToText = replyText, replyToSender = replySender, replyToId = replyToId,
                                    createdAt = nowSec))
                            }
                            saveLocalChatMessages(ctx, currentUserId, key, messages.filter { !it.isSystemNotice })
                            val previewText = when {
                                text.isNotBlank() -> text
                                aiFileAttachmentHints.isNotEmpty() -> "[文件]"
                                sentAiImages.isNotEmpty() -> "[图片]"
                                else -> text
                            }
                            ChatViewModel.updateConversationPreview(
                                friendId,
                                previewText,
                                nowSec)

                            // 会话自动命名:标题还是「新对话」时,用首条消息生成简短标题
                            // (与 AI 回答同时异步进行,不阻塞回复;用户手动改过名的会话不会被动)
                            if (friendId == AI_CHAT_ID && text.isNotBlank()) {
                                val convIdAtSend = activeAiConvId
                                aiGlobalScope.launch {
                                    val newTitle = AiChatManager.autoTitleAiConversation(ctx, currentUserId, text)
                                    if (newTitle != null) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            val i = aiConvs.indexOfFirst { it.id == convIdAtSend }
                                            if (i >= 0) aiConvs[i] = aiConvs[i].copy(title = newTitle)
                                        }
                                    }
                                }
                            }

                            // P3:统一走 AiChatManager.buildChatContext(模式/思考深度自适应+超长折叠)
                            // 同样排除尾部「实时进度卡」(纯 UI 状态，不喂模型)
                            val chatCandidates = messages.filter {
                                !it.isSystemNotice && it.text.isNotBlank() && it.text != AI_THINKING && !it.text.startsWith(PLAN_LIVE_TAG)
                            }
                            // buildChatContext 已移到下方 aiGlobalScope.launch 内、切 Default 线程执行（避免超大任务后主线程卡死）

                            // AI 图文一起发：把待发送图片转成 base64（直接按 DeepSeek/OpenAI 多模态格式发往用户配置的 API），
                            // 并从上下文剔除本次文字消息，避免同一段文字既走 history 又走多模态内容块造成重复。
                            // 文件里的图片类（用户以"文件"形式发的 jpg/png 等）也一起合进来走多模态输入
                            val aiImagesForSend = if (friendId == AI_CHAT_ID && (sentAiImages.isNotEmpty() || aiMediaFromFiles.isNotEmpty())) {
                                val directImages = sentAiImages.map { img -> com.aurora.chat.ui.chat.AiImage(
                                    base64 = android.util.Base64.encodeToString(img.bytes, android.util.Base64.NO_WRAP),
                                    mimeType = img.mime) }
                                val fileImages = aiMediaFromFiles.map { (bytes, mime) -> com.aurora.chat.ui.chat.AiImage(
                                    base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                                    mimeType = mime) }
                                directImages + fileImages
                            } else emptyList()
                            // AI 对话：本地存用户消息后调用接口流式回复
                            if (friendId == AI_CHAT_ID) {
                                // 确认发言模式：跳过 AI 自动回复，标记有待确认消息
                                if (confirmSpeechEnabled) {
                                    confirmSpeechPending = true
                                } else {
                                val mode = AiChatManager.getMode(ctx, currentUserId)
                                val canCall: Boolean
                                val quotaMsg: String?
                            // 本次请求相关：baseUsed=发送前今日已用，poolAtStart=发送前剩余余额，liveEstInput=本次输入估算
                            var liveEstInput = 0L
                            val baseUsed: Long
                            var poolAtStart = 0L
                            // ── system 提示提前构建（在 liveEstInput 之前） ──
                            // 它既是最终请求的 system 内容，也是输入 token 估算的一部分。
                            // 工具调用系统提示注入已清理：system 仅保留「性格调试」内容，不再包含能力/格式化/工具协议文本。
                            val systemText = buildString {
                                // 「节省 Token / 极致节省」模式已移除：不再注入对应指令。
                                // 性格：模型无状态，每次请求都必须把【当前性格设定】写入 system 提示（仅当「开启性格调试」为 ON 时）。
                                if (AiChatManager.getPersonalityDebug(ctx, currentUserId)) {
                                    append(AiChatManager.buildPersonaSystemPrompt(AiChatManager.getTraits(ctx, currentUserId), AiChatManager.getCustomTraits(ctx, currentUserId)))
                                    // 性格刚被修改：注入切换声明，强制模型丢弃历史中的旧语气、按新设定走，避免被上下文带偏
                                    if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) {
                                        append("\n\n").append(AiChatManager.PERSONA_SWITCH_RELEASE)
                                    }
                                }
                                // 回复模板：与性格调试叠加（性格在前、模板在后）。默认关闭；
                                // 性格调试的判定、内容与一次性切换声明一行都不动。
                                val templateInject = AiReplyTemplateStore.activeInject(ctx, currentUserId)
                                if (templateInject.isNotEmpty()) {
                                    if (length > 0) append("\n\n")
                                    append(templateInject)
                                }
                            }
                            // 工具调用系统提示注入已清理：不再注入系统能力指令、动态提醒与工具协议，
                            // system 里只保留跨回合稳定的「性格调试」内容。
                            val fastOn = AiChatManager.isFastMode(ctx, currentUserId)
                            // 性格切换声明是一次性开关：systemText 里已读过它，这里必须立刻复位，
                            // 否则同一份声明会在后续每一轮重复注入。
                            if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) AiChatManager.setPersonaSwitch(ctx, currentUserId, false)
                            // 与「确认发言」链路共用同一组装：两条链路的 system 必须逐字节一致
                            // buildAISystemPrompt 已移到下方 launch 内、切 Default 线程执行（避免主线程卡死）
                            if (mode == "official") {
                                // 余额只来自服务器：remaining 仅可为服务器真实值或 -1（未加载）。
                                // 仅当服务器明确返回 0 才拦截；未加载(-1)交由服务器裁决，不本地封顶。
                                // 发送前强制拉取最新余额（站A的同步刷新已修），站B因在同步块里不能直接调 suspend，
                                // 这里改为同步路径先读缓存余额判断，后续在下方 scope.launch 中强制刷新一次。
                                val (used, remaining) = AiChatManager.getOfficialUsage(ctx, currentUserId)
                                aiUsageState.value = Pair(used, remaining)
                                baseUsed = used
                                poolAtStart = remaining
                                canCall = remaining != 0L
                                quotaMsg = when {
                                    remaining == 0L ->
                                        "官方 API 余额已用完，可在右上角「使用」切换个人 API 或充值后继续使用"
                                    else -> null
                                }
                            } else if (mode == "paid_api") {
                                // 付费 API：发送前读缓存余额快照（下方 launch 内再强制刷新一次），
                                // 流式输出时按倍率实时估算成本，余额不足立即掐断，避免「输出完才扣费、不足扣不掉」
                                val (used, remaining) = AiChatManager.getOfficialUsage(ctx, currentUserId)
                                aiUsageState.value = Pair(used, remaining)
                                baseUsed = used
                                poolAtStart = remaining
                                canCall = remaining != 0L
                                quotaMsg = if (remaining == 0L) "付费 API 余额已用完，请先充值后再使用" else null
                            } else {
                                    canCall = true
                                    quotaMsg = null
                                    baseUsed = aiUsageState.value.first
                                }

                                if (!canCall) {
                                    onTokenExhausted()
                                } else {
                                    // 使用应用级协程作用域：离开对话（composable 销毁）也不取消，保证 AI 继续回复完
                                    aiStopRequested = false
                                    aiThinkingLive.value = false
                                    aiWriting.value = true
                                    aiWritingConvId = AiConversationSession.activeConvId
                                    agentTaskInFlight.value = true
                                    agentTaskInFlightConvId = AiConversationSession.activeConvId
                                    // 本轮发送起始时刻：中断收尾时用它补算「总时长」（上游 onDone 只在正常结束回调，
                                    // 中断时 totalSeconds 拿不到，导致底部整行信息不渲染——见中断收尾分支）
                                    val tTurnStartMs = System.currentTimeMillis()
                                    activeAiJob = aiGlobalScope.launch {
                                        // 付费 API 实时扣费参数：发送前快照余额与倍率，流式中估算成本，不足即掐断
                                        var paidInPrice = 0.0
                                        var paidOutPrice = 0.0
                                        var paidApiInsufficientTip = ""
                                        // 发送前再强制刷新一次服务器余额，并以「服务器真实 token_balance」为准判定：
                                        // 余额确为 0 时弹卡片并拦截，任何人无豁免。
                                    if (mode == "official") {
                                        AiChatManager.syncServerLimit(ctx, currentUserId)
                                        val rawBal = AiChatManager.getServerTokenBalance(ctx, currentUserId)
                                        if (rawBal <= 0L) {
                                            onTokenExhausted()
                                            return@launch
                                        }
                                    } else if (mode == "paid_api") {
                                        AiChatManager.syncServerLimit(ctx, currentUserId)
                                        val rawBal = AiChatManager.getServerTokenBalance(ctx, currentUserId)
                                        if (rawBal <= 0L) {
                                            onTokenExhausted()
                                            return@launch
                                        }
                                        if (rawBal > 0L && rawBal < Long.MAX_VALUE) poolAtStart = rawBal
                                        try { AiChatManager.refreshPaidApiPrice(ctx, currentUserId) } catch (_: Exception) {}
                                        val (inP, outP) = AiChatManager.getPaidApiPrice(ctx, currentUserId)
                                        paidInPrice = inP; paidOutPrice = outP
                                    }
                                    // 重量级计算卸载到 Default 线程：超大任务后 history 极大，主线程同步跑 buildChatContext/
                                    // buildAISystemPrompt 会把 UI 卡死（发消息无响应）。这三项只在本 launch 内使用，故移至此处异步算。
                                    // 若只有图片没有文件，剔除本次文字消息避免与多模态内容块重复；带文件附件则保留文字提示。
                                    val chatContext = withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        AiChatManager.buildChatContext(chatCandidates, editTargetMsgId, ctx, currentUserId)
                                    }
                                    val chatContextFinal = if (aiImagesForSend.isNotEmpty() && currentAiTextMsgId != 0L && aiFileAttachmentHints.isEmpty())
                                        chatContext.filter { it.id != currentAiTextMsgId } else chatContext
                                    val systemForHistory = systemText
                                    // system 提示：同一份字符串既是请求内容，也是输入 token 估算的一部分（避免两处口径漂移）。
                                    // 方案进行中:解除工具轮次硬墙,采用「完成即停」(大厂式终止)
                                    val planActiveC = messages.any { it is ChatMsg && it.text.contains("[[PLAN_CARD]]") }
                                        && !messages.any { it is ChatMsg && it.text.contains(AITaskCompletion.DELIVER_OPEN) }
                                    val planCardExistsC = messages.any { it is ChatMsg && it.text.contains("[[PLAN_CARD]]") }
                                    // 系统提示注入已清理：不再向 system 追加任何续写约束。
                                    val systemFinal = systemForHistory
                                    // 若本次携带文件附件，把文件内容/提示注入到最后一条用户消息里给 AI，但 UI 气泡仍显示原文字
                                    val chatContextForAi = if (aiFileAttachmentHints.isNotEmpty() && currentAiTextMsgId != 0L) {
                                        chatContextFinal.map { m ->
                                            if (m.id == currentAiTextMsgId && m.isMine) (m as? ChatMsg)?.copy(text = textForAi) ?: m else m
                                        }
                                    } else chatContextFinal
                                    // 【只发附件、没打字的场景】上面那条"把附件提示塞进最后一条用户文字"的替换，
                                    // 前提是存在一条本次的文字气泡（currentAiTextMsgId != 0）。而"只发文件"时
                                    // 刻意不造文字气泡（见上文「只发附件时不再强塞一条 [文件] 气泡」），于是
                                    // aiFileAttachmentHints 一条都进不了上下文 —— 模型完全不知道用户发了文件，
                                    // 回一句"我没看到文件"，用户看到的正是「发给 AI 的文件它根本看不见」。
                                    // 这里单独补一条 user 消息承载附件信息（UI 气泡不受影响，仍只有文件卡片）。
                                    val attachmentOnlyTurn = aiFileAttachmentHints.isNotEmpty() &&
                                        currentAiTextMsgId == 0L && textForAi.isNotBlank()
                                    // 输入 token 估算：system 提示 + 工具 schema + 本次真实发送的消息文本 + 图片。
                                    // 必须放在这一步算——只有到这里才知道最终 history 内容与附件数量。
                                    // 口径与 streamChat 实际发出的请求严格一致；缺 system 或 tools 会低一个数量级。
                                    liveEstInput = AiChatManager.estimateRequestInputTokens(
                                        systemText = systemFinal,
                                        historyTexts = chatContextForAi.map { it.text } +
                                            (if (attachmentOnlyTurn) listOf(textForAi) else emptyList()),
                                        toolSchemaJson = "",
                                        imageCount = aiImagesForSend.size
                                    )
                                    val history = ((if (systemFinal.isNotBlank()) listOf("system" to systemFinal) else emptyList()) + chatContextForAi.map { m -> 
val role = if (m.isMine) "user" else "assistant"
                                        // 引用声明（仅 AI 对话生效）：把被引用/回复的消息内容用系统声明标注进上下文，
                                        // 让 AI 明确看到用户引用的是哪一条（普通对话走各自发送链路，不受此影响）
                                        // 注入前清洗 AI 侧历史：剥掉交付块哨兵（保留可读正文）与工具状态哨兵，
                                        // 否则模型会看到上一轮的哨兵原文、被诱导"把上一轮交付块抄进这一轮"（嵌套根源）
                                        val content = if (m.isMine) m.text else AiChatManager.sanitizeTextForModel(m.text)
                                        role to content
                                    })
                                        // 清洗后可能变成空串（原文只有工具状态/思考哨兵、没有正文）——空 content 会被上游拒
                                        // （400）并白白浪费一次重试，这里直接剔除
                                        .filter { it.second.isNotBlank() }
                                        .toMutableList()
                                        // 只发附件：把附件说明作为本轮 user 消息追加进去（见 attachmentOnlyTurn 注释）
                                        if (attachmentOnlyTurn) history.add("user" to textForAi)
                                        // 【前缀静态化】动态信息走末尾 user 提醒而非 system:当前时间/记忆检索/轮数提示/
                                        // 方案执行约束/续写约束都是每回合会变的内容,放进 system 会让整段前缀缓存每轮作废
                                        // (DeepSeek 等上游按前缀匹配计费,命中价约为未命中的 1/10)。system 保持逐字节稳定。
                                        val aiMsgId = System.currentTimeMillis() * 10000 + (0..9999).random()
                                        aiStreamMsgId.value = aiMsgId
                                        // 开启"显示思考过程"时，初始占位用空文本，稍后由 onReasoningDelta 填充思考内容
                                        val initText = ""  // 占位文本恒空：思考阶段不上屏，等正文真正开始（或整段输出完成）才由渲染层放出来
                                        messages.add(ChatMsg.create(
                                            serverId = aiMsgId, text = initText, isMine = false,
                                            fromUserId = AI_CHAT_ID, senderName = AiNameState.name ?: "AI", isNew = true,
                                            createdAt = System.currentTimeMillis() / 1000
                                        ).copy(aiWaiting = true))
                                        // 新占位入列即收口旧的等待态：历史遗留的「等待模型响应」绝不允许与新的一起计时
                                        closeStaleAiWaiting(aiMsgId)
                                        // 回复所属会话在发送时绑定，后续保存/渲染全部以它为准，防止切会话后写错文件
                                        val myKey = AiConversationSession.storageKey()
                                        val myConvId = AiConversationSession.activeConvId
                                        // 立即落盘：即使马上离开对话，AI 占位消息也已在本地，重进可见（快照在发送时捕获，避免切会话后写错文件）
                                        val sendSnap = messages.filter { !it.isSystemNotice }.toList()
                                        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, sendSnap) } catch (_: Exception) {} }
                                        // 登记后台进行中的回复（跨组合期持有，供重进对话继续实时刷新），并打上当前会话代次
                                        aiReplyState.value = AiBackgroundReply(aiMsgId = aiMsgId, convId = myConvId)
                                        try { listState.scrollToItem(0) } catch (_: Exception) {}
                                        // 多段思考：跨轮累积（reasoning_content），各段用哨兵拼接
                                        var reasoningSegs = ""
                                        var roundReasoning = ""
                                        var thisRoundReasoning = ""  // 仅本轮思考，传给循环做内联嵌入（避免把累积思考重复内嵌）
                                        // 【强制中断兜底】用户点停止时若协程被 cancel 强杀（长耗时工具卡住 2 秒后的兜底），
                                        // 取消异常会穿过 runAIChatLoop 直接抛到这里，使下面的中断收尾整段跳过 ——
                                        // token 明细 / 耗时 / 工具计数会全部写不进去（用户反馈「中断了就查不到消耗」）。
                                        // 这里接住并合成一个「中断结果」，让收尾照常执行：正文取当前已流式生成的内容，
                                        // 用量与耗时由中断收尾按同一口径补算（卡片会标注是中断记录）。
                                        val outcome = try {
                                        // 对话级基线快照:任务开始前对 ai_files 全量拍照(每对话仅捕获一次)
                                        captureAIBaseline(ctx, AiConversationSession.storageKey())
                                        AiChatManager.runAIChatLoop(
                                        maxRoundsOverride = if (planActiveC) Int.MAX_VALUE else null,
                                        ctx = ctx, userId = currentUserId, history = history,
                                        onNeedApproval = { tool, args -> toolApprovalHook(tool, args) },
                                        onFileSent = { fs -> insertAiFileCard(fs.absPath, fs.forceFileCard, aiMsgId) },
                                        onMediaGenerated = { t, url -> insertAiMediaCard(t, url, aiMsgId) },
                                        onOpenVideo = { url -> openVideoPlayer(url) },
                                        onPlanStepUpdate = { idx, st -> updatePlanStep(idx, st) },
                                        onToolStage = { tool, st, argsJson, cumToolCalls, cumChanges, stages ->
                                            // 工具状态直接内联进当前 AI 消息文本（哨兵块随正文持久化，绝不丢失）：
                                            // 调用开始(0)追加「正在调用」状态行，完成(1/2)把同名状态行原地替换为「已调用/失败」，
                                            // 让状态显示在调用真正发生的位置，而不是堆到所有结果之后。
                                            val fixedToolLog = AIUiPrefs.fixedToolChangeLog(ctx)
                                            val sIdx = messages.indexOfFirst { x -> x.id == aiMsgId }
                                            if (sIdx >= 0 && messages[sIdx] is ChatMsg) {
                                                val cur = messages[sIdx] as ChatMsg
                                                // 真实目标 + 参数摘要一次解析（状态行显示目标，点击展开看关键参数）
                                                val info = toolCallInfo(tool, argsJson)
                                                val newText = if (st == 0) {
                                                    // 工具开始执行 = 本轮思考已结束：立即复位「思考中」卡片（含 ask_user 挂起场景）
                                                    aiThinkingLive.value = false
                                                    AiChatManager.appendToolInline(cur.text, tool, 0, info.target, info.argsSummary)
                                                } else {
                                                    AiChatManager.replaceToolInline(
                                                        cur.text, tool, st,
                                                        target = info.target, argsSummary = info.argsSummary
                                                    )
                                                }
                                                // 固定工具调用与修改日志：流式期间实时把调用数与改动写回消息，
                                                // 让底部胶囊随每次调用/改动即时刷新（数字边跑边涨），并由 debounced 保存落盘，闪退不丢。
                                                messages[sIdx] = cur.copy(
                                                    text = newText,
                                                    aiToolCallCount = if (fixedToolLog) cumToolCalls else cur.aiToolCallCount,
                                                    aiToolStages = if (fixedToolLog) stages else cur.aiToolStages,
                                                    aiFileChanges = if (fixedToolLog)
                                                        AiChatManager.serializeFileChanges(AiChatManager.activeFileChangeRecorder ?: emptyList())
                                                    else cur.aiFileChanges
                                                )
                                                scheduleAiDebouncedSave()
                                                // 仅贴底(autoFollow)时跟随最新；用户上滑看历史时不抢滚动
                                                if (autoFollow) { try { aiGlobalScope.launch { listState.scrollToItem(0) } } catch (_: Exception) {} }
                                            }
                                            // 工具完成后同步刷新 UI 模式状态（Plan 模式下 AI 可能已用 set_agent_mode 自动切换到 craft）
                                            agentMode = AiChatManager.getAIMode(ctx, currentUserId)
                                        },
                                        appActionHandler = onAppAction,
                                        onProgress = { hint ->
                                            val sIdx = messages.indexOfFirst { x -> x.id == aiMsgId }
                                            if (sIdx >= 0 && messages[sIdx] is ChatMsg) {
                                                messages[sIdx] = (messages[sIdx] as ChatMsg).copy(aiProgressHint = hint)
                                            }
                                        }
                                    ) { nativeCalls, prefix, toolChoice ->
                                            var finishReason = ""
                                            var lastErrMsg = ""
                                            val isFirstRound = prefix.isEmpty()
                                            val shownFor = { frag: String ->
                                                val cleaned = AiChatManager.stripToolBlocks(frag)
                                                if (isFirstRound) cleaned
                                                else (if (prefix.isBlank()) cleaned else prefix + "\n\n" + cleaned)
                                            }
                                            // 【性能】流式 token 增量估算器（只统计新增字符，避免每个 delta 重扫整段输出）
                                            val streamEst = StreamingTokenEstimator()
                                            var done = false
                                            var ft = ""
                                            var u = 0L
                                            var th = 0L
                                            var tt = 0L
                                            var err = false
                                            var ud: AiChatManager.AiUsageDetail? = null
                                            AiChatManager.streamChat(
                                                ctx = ctx, userId = currentUserId, history = history,
                                                images = if (isFirstRound) aiImagesForSend else emptyList(),
                                                imageText = if (isFirstRound) text else "",
                                                stopCheck = { aiStopRequested },
                                                // 空内容不当致命错误：推理模型(DeepSeek)常整轮只吐思考、content 为空。
                                                // 交给 runAIChatLoop 的「有界补催」处理，而不是直接中断任务。
                                                treatEmptyAsError = false,
                                                onReasoningDelta = { reasoning ->
                                                    aiThinkingLive.value = true
                                                    // 每轮思考都实时刷新：已累积段 + 本轮思考，分段展示（不限于首轮）
                                                    roundReasoning = reasoning
                                                    // 【性能】思考写入节流（详见 StreamUiThrottle 说明）：思考量往往比正文还大，
                                                    // 不节流就是每 token 一次整段思考重解析。丢帧不丢内容，onDone 会用完整累积思考兜底。
                                                    if (StreamUiThrottle.due(aiMsgId)) {
                                                        val live = AiChatManager.appendReasoningSegment(reasoningSegs, reasoning)
                                                        // 更新全局状态（跨组合期），并尽力更新界面内消息
                                                        aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.copy(reasoningText = live)
                                                        // 首批思考内容到达 → 立即退出「等待模型响应」空窗（清除占位符；无论是否展示思考过程都清）
                                                        val rIdx = messages.indexOfFirst { m -> m.id == aiMsgId }
                                                        if (rIdx >= 0 && !(messages[rIdx] as ChatMsg).manualStopped) {
                                                            val rm = messages[rIdx] as ChatMsg
                                                            messages[rIdx] = rm.copy(
                                                                reasoningText = if (AiChatManager.getShowReasoning(ctx, currentUserId)) live else rm.reasoningText,
                                                                aiWaiting = false
                                                            )
                                                            scheduleAiDebouncedSave()
                                                        }
                                                        // 思考过程输出时自动滚动到底部，与对话文本输出体验一致
                                                        if (autoFollow) {
                                                            programmaticScroll(0)
                                                        }
                                                    }
                                                    // 思考过程中实时更新用量：输入已消耗，输出尚未产生，先显示输入部分
                                                    if (mode == "official" && poolAtStart > 0L && poolAtStart < Long.MAX_VALUE) {
                                                        val liveUsed = baseUsed + liveEstInput
                                                        val liveRemaining = (poolAtStart - liveEstInput).coerceAtLeast(0)
                                                        aiUsageState.value = Pair(liveUsed, liveRemaining)
                                                    } else if (mode == "paid_api" && poolAtStart > 0L && poolAtStart < Long.MAX_VALUE && (paidInPrice > 0.0 || paidOutPrice > 0.0)) {
                                                        // 付费 API：思考阶段按「输入*入倍率」估算成本（与后端 chargeTokensFromPrice 口径一致）
                                                        val cost = kotlin.math.ceil(liveEstInput * paidInPrice).toLong()
                                                        aiUsageState.value = Pair(baseUsed + cost, (poolAtStart - cost).coerceAtLeast(0))
                                                    }
                                                },
                                                onDelta = { cur ->
                                                    aiThinkingLive.value = false
                                                    // 【性能】UI 写入节流（详见 StreamUiThrottle 说明）：cur 是累积全文，丢帧不丢内容
                                                    if (StreamUiThrottle.due(aiMsgId)) {
                                                        val shown = shownFor(cur)
                                                        // 更新全局状态（跨组合期），保证离开对话后重进仍能读到最新输出
                                                        aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.copy(text = shown)
                                                        val idx = messages.indexOfFirst { m -> m.id == aiMsgId }
                                                        if (idx >= 0 && !(messages[idx] as ChatMsg).manualStopped) {
                                                            val msg = messages[idx] as ChatMsg
                                                            val keepReasoning = if (AiChatManager.getShowReasoning(ctx, currentUserId)) msg.reasoningText else ""
                                                            messages[idx] = msg.copy(
                                                                text = shown.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""),
                                                                reasoningText = keepReasoning,
                                                                aiWaiting = false
                                                            )
                                                            // 防抖落盘：不在主线程每次增量同步写盘（那是卡顿根源），停增 600ms 后后台落盘一次
                                                            scheduleAiDebouncedSave()
                                                        }
                                                        // 流式输出时仅在用户已停在底部才自动跟随；用户上滑看历史时不抢占，不阻止其操作
                                                        if (autoFollow) {
                                                            programmaticScroll(0)
                                                        }
                                                    }
                                                    // 实时预估：以发送前服务器余额(poolAtStart)为基准，边输出边递减展示。
                                                    // 仅当服务器余额已加载(>0)才做本地预估；未加载(-1)、已 0 或异常大值(Long.MAX_VALUE)时不预估，
                                                    // 未加载保持「加载中…」展示，等服务器裁决。
                                                    if (mode == "official" && poolAtStart > 0L && poolAtStart < Long.MAX_VALUE) {
                                                        val inc = liveEstInput + streamEst.estimate(cur)
                                                        val liveRemaining = (poolAtStart - inc).coerceAtLeast(0)
                                                        val liveUsed = baseUsed + inc
                                                        aiUsageState.value = Pair(liveUsed, liveRemaining)
                                                    } else if (mode == "paid_api" && poolAtStart > 0L && poolAtStart < Long.MAX_VALUE && (paidInPrice > 0.0 || paidOutPrice > 0.0)) {
                                                        // 付费 API 实时预估：cost = ceil(输入*入倍率) + ceil(已输出*出倍率)，与后端口径一致
                                                        val outEst = streamEst.estimate(cur).toLong()
                                                        val cost = kotlin.math.ceil(liveEstInput * paidInPrice).toLong() + kotlin.math.ceil(outEst * paidOutPrice).toLong()
                                                        val liveRemaining = (poolAtStart - cost).coerceAtLeast(0)
                                                        aiUsageState.value = Pair(baseUsed + cost, liveRemaining)
                                                        // 余额不足以支付已生成内容：立即掐断流式输出（不再等输出完才扣费）
                                                        if (liveRemaining <= 0L && !aiStopRequested) {
                                                            aiStopRequested = true
                                                            paidApiInsufficientTip = "\n\n（余额不足，已停止输出）"
                                                            // 此分支可能落在节流窗口内（本轮尚未写入 UI），所以用 cur 现算一次文本
                                                            val tipText = shownFor(cur) + paidApiInsufficientTip
                                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.copy(text = tipText)
                                                            val idx2 = messages.indexOfFirst { m -> m.id == aiMsgId }
                                                            if (idx2 >= 0) {
                                                                messages[idx2] = (messages[idx2] as ChatMsg).copy(text = tipText.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""), aiWaiting = false)
                                                                scheduleAiDebouncedSave()
                                                            }
                                                            onTokenExhausted()
                                                        }
                                                    }
                                                },
                                                onDone = { usage, full, thinkingSeconds, totalSeconds ->
                                                    aiThinkingLive.value = false
                                                    done = true
                                                    ft = full
                                                    u = usage
                                                    th = thinkingSeconds
                                                    tt = totalSeconds
                                                    // 本轮思考完整 → 并入多段思考累积
                                                    thisRoundReasoning = roundReasoning
                                                    reasoningSegs = AiChatManager.appendReasoningSegment(reasoningSegs, roundReasoning)
                                                    roundReasoning = ""
                                                    val shown = shownFor(full)
                                                    aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.copy(text = shown, reasoningText = reasoningSegs)
                                                    val idx = messages.indexOfFirst { m -> m.id == aiMsgId }
                                                    if (idx >= 0 && !(messages[idx] as ChatMsg).manualStopped) {
                                                        val msg = messages[idx] as ChatMsg
                                                        val keepReasoning = if (AiChatManager.getShowReasoning(ctx, currentUserId)) reasoningSegs else msg.reasoningText
                                                        messages[idx] = msg.copy(
                                                            text = shown.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""),
                                                            reasoningText = keepReasoning,
                                                            aiWaiting = false
                                                        )
                                                        scheduleAiDebouncedSave()
                                                    }
                                                },
                                                onError = { errMsg ->
                                                    aiThinkingLive.value = false
                                                    err = true
                                                    lastErrMsg = errMsg
                                                    // 修复 UI 状态机误报：onError 不再复位按钮状态——runAIChatLoop 后台可能仍在
                                                    // 重试（可重试错误）/ 裁剪上下文 / 规划生成 / 等待工具响应 / 执行 verifyDelivery 收尾。
                                                    // 按钮保持「停止」直至任务真正结束（runAIChatLoop 返回、outcome 分支统一复位）。
                                                    // 修复：错误不再删消息。先把「已生成内容 + 错误提示」写进跨组合期状态
                                                    // （text 追加「（生成中断：原因）」；空文本时提示即正文），再同步到内存消息并落盘。
                                                    // 原实现在 removeAt 删整条消息并落盘——API 异常/连接断开时，已流式生成的前文整段消失。
                                                    val prevReplyText = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.text.orEmpty()
                                                    val errText = if (prevReplyText.isBlank()) "（生成中断：$errMsg）" else prevReplyText + "\n\n（生成中断：$errMsg）"
                                                    aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.copy(text = errText, error = errMsg)
                                                    // 仅当仍停留在本会话才操作当前内存列表；已切走则把「已生成内容+错误提示」合并写回所属会话文件
                                                    if (AiConversationSession.storageKey() == myKey) {
                                                        val idx = messages.indexOfFirst { m -> m.id == aiMsgId }
                                                        if (idx >= 0) {
                                                            // 不删消息：保留已流式生成的部分输出（修复"异常致前文整段消失"），
                                                            // 并在文本上追加错误提示，让用户切回会话也能看到中断原因。
                                                            val cur = messages[idx] as ChatMsg
                                                            messages[idx] = cur.copy(
                                                                text = errText.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""),
                                                                manualStopped = cur.manualStopped,
                                                                aiWaiting = false
                                                            )
                                                            // 错误时也落盘，保留带错误提示的占位气泡
                                                            val snap = messages.filter { !it.isSystemNotice }.toList()
                                                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                                                        }
                                                    } else {
                                                        // 已切走：原实现只写 manualStopped=true，正文全丢；这里把已生成内容+错误提示一并写回所属会话
                                                        val liveReasoning = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.reasoningText ?: ""
                                                        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                            try {
                                                                patchAiReplyInFile(
                                                                    ctx, currentUserId, myKey, aiMsgId,
                                                                    text = errText,
                                                                    reasoningText = liveReasoning,
                                                                    manualStopped = true
                                                                )
                                                            } catch (_: Exception) {}
                                                        }
                                                    }
                                                    if (errMsg.contains("已被管理员暂停")) {
                                                        apiSuspendedDialog = true
                                                    } else {
                                                        android.widget.Toast.makeText(ctx, errMsg, android.widget.Toast.LENGTH_LONG).show()
                                                    }
                                                },
                                                onTokenInsufficient = onTokenExhausted,
                                                nativeToolAccumulator = nativeCalls,
                                                enableAITools = agentMode != AiChatManager.MODE_ASK,
                                                toolChoice = toolChoice,
                                                onFinish = { r -> finishReason = r },
                                                onUsageDetail = { d -> ud = d }
                                            )
                                            AiChatManager.AIRoundResult(done && !err, ft, u, th, tt, ud, thisRoundReasoning, finishReason, if (err) com.aurora.chat.ui.chat.AITaskCompletion.isRetriable(lastErrMsg) else false, if (err) lastErrMsg else "")
                                        }
                                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                            // 协程被强杀：合成中断结果（clean=false 走下面的中断收尾分支）
                                            AiChatManager.AILoopOutcome(
                                                accumulated = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.text
                                                    ?: (messages.firstOrNull { it.id == aiMsgId } as? ChatMsg)?.text.orEmpty(),
                                                toolRounds = 0,
                                                clean = false,
                                                usage = 0L,
                                                thinkingSeconds = 0L,
                                                totalSeconds = ((System.currentTimeMillis() - tTurnStartMs) / 1000L).coerceAtLeast(0L)
                                            )
                                        } catch (e: Exception) {
                                            // 未捕获的业务异常（工具执行 / UI 回调 / 收尾等环节）：合成中断结果并把异常信息带进正文，
                                            // 避免被 SupervisorJob 静默吞掉导致「任务毫无征兆直接结束、零反馈」。
                                            ErrorReporter.error("AI_Loop", "runAIChatLoop 未捕获异常: ${e.javaClass.simpleName}: ${e.message}")
                                            val partial = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.text
                                                ?: (messages.firstOrNull { it.id == aiMsgId } as? ChatMsg)?.text.orEmpty()
                                            AiChatManager.AILoopOutcome(
                                                accumulated = (if (partial.isNotBlank()) partial else "") + "\n〔系统提示〕任务因异常中断：${e.message ?: e.javaClass.simpleName}",
                                                toolRounds = 0,
                                                clean = false,
                                                usage = 0L,
                                                thinkingSeconds = 0L,
                                                totalSeconds = ((System.currentTimeMillis() - tTurnStartMs) / 1000L).coerceAtLeast(0L)
                                            )
                                        }
                                        if (outcome.clean) {
                                            // 一次性收尾（仅正常完成时执行；出错/手动停止保留现场，跳过此块）
                                            aiWriting.value = false
                                            aiWritingConvId = -1L
                                            aiStopRequested = false
                                            activeAiJob = null
                                            agentTaskInFlight.value = false
                                            agentTaskInFlightConvId = -1L
                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.copy(text = outcome.accumulated, done = true)
                                            val stillHere = AiConversationSession.storageKey() == myKey
                                            val idx = if (stillHere) messages.indexOfFirst { m -> m.id == aiMsgId } else -1
                                            // 取出最终 reasoningText（仅开启时保留）
                                            val finalReasoning = if (idx >= 0 && AiChatManager.getShowReasoning(ctx, currentUserId)) (messages[idx] as ChatMsg).reasoningText else ""
                                            val multi = AiChatManager.getMultiOutput(ctx, currentUserId)
                                            var full = outcome.accumulated
                                            // 兜底：循环正常结束但正文（剥离内联思考后）为空，说明模型只想没说；补一句占位，避免只剩思考块
                                            if (full.isNotBlank() && AiChatManager.stripInlineReasoning(full).isBlank()) {
                                                full = full + "\n\n（AI 未生成有效回复内容）"
                                            }
                                            if (stillHere) {
                                                if (multi && full.contains(AiChatManager.MULTI_OUTPUT_SPLIT)) {
                                                    // 按 [[SPLIT]] 拆分为多条短消息（仅 AI 主动使用分隔符时才拆，不强行拆分）
                                                    if (idx >= 0) messages.removeAt(idx)
                                                    val segs = full.split(AiChatManager.MULTI_OUTPUT_SPLIT)
                                                        .map { it.trim() }.filter { it.isNotBlank() }
                                                    var pos = idx.coerceAtLeast(0)
                                                    var firstText = ""
                                                    for (seg in segs) {
                                                        if (firstText.isEmpty()) firstText = AiChatManager.stripInlineReasoning(seg).take(60)
                                                        messages.add(pos, ChatMsg.create(
                                                            serverId = System.currentTimeMillis() * 10000L + (0..9999).random(),
                                                            text = seg, isMine = false, fromUserId = AI_CHAT_ID,
                                                            senderName = AiNameState.name ?: "AI", isNew = true,
                                                            createdAt = System.currentTimeMillis() / 1000,
                                                            reasoningText = finalReasoning,
                                                            thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds,
                                                            inputTokens = outcome.usageDetail?.inputTokens ?: 0, outputTokens = outcome.usageDetail?.outputTokens ?: 0,
                                                            cacheHitTokens = outcome.usageDetail?.cacheHitTokens ?: 0, contextLength = outcome.usageDetail?.contextLength ?: 0,
                                                            aiFileChanges = AiChatManager.serializeFileChanges(outcome.fileChanges),
                                                            aiToolCallCount = outcome.toolCallCount,
                                                            aiToolStages = outcome.toolCalls
                                                        ))
                                                        pos++
                                                    }
                                                    if (firstText.isNotEmpty()) ChatViewModel.updateConversationPreview(friendId, firstText, System.currentTimeMillis() / 1000)
                                                } else {
                                                    if (idx >= 0) messages[idx] = (messages[idx] as ChatMsg).copy(aiMode = mode, text = full, reasoningText = finalReasoning, thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds, inputTokens = outcome.usageDetail?.inputTokens ?: 0, outputTokens = outcome.usageDetail?.outputTokens ?: 0, cacheHitTokens = outcome.usageDetail?.cacheHitTokens ?: 0, contextLength = outcome.usageDetail?.contextLength ?: 0, aiWaiting = false, aiProgressHint = "", aiFileChanges = AiChatManager.serializeFileChanges(outcome.fileChanges), aiToolCallCount = outcome.toolCallCount, aiToolStages = outcome.toolCalls)
                                                    // 工具状态行动画收回:已调用/失败/调用中全部不遗留
                                                    collapseToolStatusMessages(messages)
                                                    if (full.isNotBlank()) {
                                                        ChatViewModel.updateConversationPreview(friendId, AiChatManager.stripInlineReasoning(full).take(60), System.currentTimeMillis() / 1000)
                                                    }
                                                }
                                                // 本轮正常收尾：除本条外任何仍停在「等待模型响应」的气泡统一收口
                                                // （工具轮 / 切会话恢复都可能遗留旧等待态，绝不允许与新的并存计时）
                                                closeStaleAiWaiting(aiMsgId)
                                                val snap = messages.filter { !it.isSystemNotice }.toList()
                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                                            } else {
                                                // 已切走：把完整回复合并写回所属会话文件，用户切回即可见（multi 场景写回含分隔符原文，保证内容完整）
                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                    try { patchAiReplyInFile(ctx, currentUserId, myKey, aiMsgId, text = full, reasoningText = finalReasoning, thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds, inputTokens = outcome.usageDetail?.inputTokens, outputTokens = outcome.usageDetail?.outputTokens, cacheHitTokens = outcome.usageDetail?.cacheHitTokens, contextLength = outcome.usageDetail?.contextLength, fileChangesJson = AiChatManager.serializeFileChanges(outcome.fileChanges), toolCallCount = outcome.toolCallCount, toolStages = outcome.toolCalls) } catch (_: Exception) {}
                                                }
                                            }
                                            // 真实消耗模式（个人 / 官方 / 第三方 / 付费，非免费共享接口 free_api）均累加「今日已用」，
                                            // 口径与每条消息的 token 明细一致：input+output 真实 token 之和，无倍率换算。
                                            if (mode != "free_api" && outcome.usage > 0) {
                                                // 记账（仅累加本地「今日已用」计数）
                                                AiChatManager.addOfficialUsage(ctx, currentUserId, outcome.usage)
                                                // 余额只来自服务器：重新拉取权威余额后再刷新面板，不本地扣减
                                                aiGlobalScope.launch {
                                                    try {
                                                        AiChatManager.syncServerLimit(ctx, currentUserId)
                                                        aiUsageState.value = AiChatManager.getOfficialUsage(ctx, currentUserId)
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                            // 付费 API：计费与扣款已由后端中转接口完成（权威计量），这里只算展示用的「已扣费」，
                                            // 不再向服务端上报 token——重复上报会造成二次扣费。
                                            if (mode == "paid_api") {
                                                val ud2 = outcome.usageDetail
                                                if (ud2 != null && (ud2.inputTokens > 0 || ud2.outputTokens > 0)) {
                                                    val (paidInPrice, paidOutPrice) = AiChatManager.getPaidApiPrice(ctx, currentUserId)
                                                    val cost = if (paidInPrice > 0.0 || paidOutPrice > 0.0) {
                                                        kotlin.math.ceil(ud2.inputTokens * paidInPrice).toLong() +
                                                            kotlin.math.ceil(ud2.outputTokens * paidOutPrice).toLong()
                                                    } else 0L
                                                    if (cost > 0L) {
                                                        if (idx >= 0) {
                                                            messages[idx] = (messages[idx] as ChatMsg).copy(aiCostDeducted = cost)
                                                            val snap2 = messages.filter { !it.isSystemNotice }.toList()
                                                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap2) } catch (_: Exception) {} }
                                                        } else {
                                                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, myKey, aiMsgId, aiCostDeducted = cost) } catch (_: Exception) {} }
                                                        }
                                                    }
                                                    aiGlobalScope.launch {
                                                        try {
                                                            AiChatManager.syncServerLimit(ctx, currentUserId)
                                                            aiUsageState.value = AiChatManager.getOfficialUsage(ctx, currentUserId)
                                                        } catch (_: Exception) {}
                                                    }
                                                }
                                            }
                                        } else {
                                            // ═══ 中断收尾（手动停止/出错/强制 break 等 clean=false 路径）═══
                                            // 旧代码只把 aiFileChanges/aiToolCallCount/aiToolStages 写回消息放在
                                            // if(outcome.clean) 正常完成块里；中断时被整体跳过，导致底部
                                            // 「查看所有改动」「调用 N 个工具」按钮不渲染、中断前已做的修改不留痕。
                                            // 现在中断也写回：AiChatManager 中断路径已执行 mergeSandboxDiff 并在
                                            // outcome 里返回 fileChanges/toolCallCount/toolCalls（含中途停止前的真实改动），
                                            // 这里只要落到消息并落盘，重进会话按钮仍在、改动列表完整。
                                            // 是否「用户手动停止」：aiStopRequested 立刻就会被复位，这里先取快照。
                                            // 它与真出错的兜底口径不同：手停允许用估算补出用量（保证 Token 明细入口一定在），
                                            // 真出错只用真实值、不拿估算冒充（错因已写在正文里，不该再给一张像模像样的明细卡）。
                                            val stoppedByUser = aiStopRequested
                                            aiWriting.value = false
                                            aiWritingConvId = -1L
                                            aiStopRequested = false
                                            activeAiJob = null
                                            agentTaskInFlight.value = false
                                            agentTaskInFlightConvId = -1L
                                            // 修复：中断收尾补写正文——原实现只回写工具/改动字段，outcome.accumulated
                                            // （中断前已流式生成的内容）从未写回消息，离开会话/异常中断后正文丢失。
                                            // 这里把最终正文写入跨组合期状态、内存消息与所属会话文件（思考内容流式阶段已实时写入）。
                                            val finalText = outcome.accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                                            val tipSuffix = if (paidApiInsufficientTip.isNotBlank() && !finalText.endsWith(paidApiInsufficientTip)) paidApiInsufficientTip else ""
                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }
                                                ?.copy(text = (if (finalText.isNotBlank()) finalText else "") + tipSuffix, done = true)
                                            // ── 中断时的「耗时 / 用量」补算（用户点停止后原本整块入口消失）──
                                            // 根因：上游 onDone 只在正常结束时回调，手动停止时 outcome 里的
                                            // usageDetail/totalSeconds 恒为空/0 → 消息上的 token 与耗时字段写不进去 →
                                            // 底部渲染条件（totalSeconds>0 || 有 token 统计）不成立 → 「Token 明细」按钮连同
                                            // 整行信息一起不渲染，用户停止后就再也查不到本次消耗。
                                            // 口径：有真实用量就用真实值；用户手动停止且没拿到真实用量时用估算兜底
                                            // （输入=最终 history 重算值，输出=已生成正文估算，与流式期间余额预估同一套估算器）。
                                            // 明细卡片会对中断记录做标注，不把估算当作权威计量。
                                            val realUd = outcome.usageDetail
                                            val hasRealUsage = realUd != null && (realUd.inputTokens > 0L || realUd.outputTokens > 0L)
                                            // paid_api：后端在客户端断开时照样结算落库（backend/paid_api.go 防线③）。
                                            // 手停且上游没回报 usage 时先查一次「最近一次结算」拿真值——能用真值绝不用估算。
                                            val settled = if (!hasRealUsage && stoppedByUser && mode == "paid_api") {
                                                runCatching { AuroraApi.paidApiLastUsage() }.getOrNull()
                                                    ?.takeIf { it.success && it.data?.optBoolean("found") == true }
                                                    ?.data
                                            } else null
                                            val srvIn = settled?.optLong("input_tokens", 0L) ?: 0L
                                            val srvOut = settled?.optLong("output_tokens", 0L) ?: 0L
                                            val srvCache = settled?.optLong("cache_hit_tokens", 0L) ?: 0L
                                            val srvCtx = settled?.optLong("context_length", 0L) ?: 0L
                                            // 服务端结算到的即权威值；只有真拿不到真值时才轮到估算
                                            val usageAuthoritative = hasRealUsage || srvIn > 0L || srvOut > 0L
                                            val estOutTokens = AiChatManager.estimateTokens(finalText).toLong()
                                            // 手停一律补输入估算：请求已经发出，输入确实被消耗了；
                                            // 哪怕一个字都没生成（finalText 为空），也要留下「Token 明细」入口。
                                            val useEstimate = !usageAuthoritative && stoppedByUser
                                            // 手动中断且无任何真值时，用「最终真实 history」重算输入（含全程累积的工具结果），
                                            // 替代过时的发送前初始估算 liveEstInput（不含任务过程工具结果，会低一个数量级）。
                                            val finalHistoryInput = AiChatManager.estimateHistoryInputTokens(
                                                ctx, currentUserId, systemForHistory, history.map { it.second },
                                                AiChatManager.getAIMode(ctx, currentUserId) != AiChatManager.MODE_ASK, fastOn,
                                                lastUserText = history.lastOrNull { it.first == "user" }?.second, history = history
                                            )
                                            val inTok = when {
                                                hasRealUsage -> realUd?.inputTokens ?: 0L
                                                srvIn > 0L -> srvIn
                                                useEstimate -> finalHistoryInput
                                                else -> 0L
                                            }
                                            val outTok = when {
                                                hasRealUsage -> realUd?.outputTokens ?: 0L
                                                srvOut > 0L -> srvOut
                                                useEstimate -> estOutTokens
                                                else -> 0L
                                            }
                                            val cacheTok = if (hasRealUsage) realUd?.cacheHitTokens ?: 0L else srvCache
                                            val ctxTok = if (hasRealUsage) (realUd?.contextLength ?: 0L)
                                                else if (srvCtx > 0L) srvCtx else (inTok + outTok)
                                            val thinkSec = outcome.thinkingSeconds
                                            val totalSec = if (outcome.totalSeconds > 0L) outcome.totalSeconds
                                                else ((System.currentTimeMillis() - tTurnStartMs) / 1000L).coerceAtLeast(0L)
                                            val hasCalls = outcome.toolCallCount > 0
                                            val hasChanges = outcome.fileChanges.isNotEmpty()
                                            val stillHere = AiConversationSession.storageKey() == myKey
                                            val idx = if (stillHere) messages.indexOfFirst { m -> m.id == aiMsgId } else -1
                                            if (idx >= 0) {
                                                // 仍在当前会话：正文/思考/工具字段全部写回内存消息 + 落盘（保留已有字段，只补缺失数据）
                                                val cur = messages[idx] as ChatMsg
                                                messages[idx] = cur.copy(
                                                    text = (if (finalText.isNotBlank()) finalText else cur.text) + tipSuffix,
                                                    reasoningText = cur.reasoningText,
                                                    aiWaiting = false,
                                                    // 用户点停止即落「已停止」标记（明细卡据此标注中断记录）
                                                    manualStopped = if (stoppedByUser) true else cur.manualStopped,
                                                    // 口径来源：true=服务端权威结算，false=客户端估算；估算绝不冒充权威
                                                    aiUsageAuthoritative = when {
                                                        usageAuthoritative -> true
                                                        stoppedByUser -> false
                                                        else -> cur.aiUsageAuthoritative
                                                    },
                                                    // 中断也要落「耗时 + 用量」：这是「Token 明细」按钮的渲染依据，
                                                    // 中断前拿不到值就保留已有值，绝不用 0 覆盖掉已经写好的统计
                                                    thinkingSeconds = if (thinkSec > 0L) thinkSec else cur.thinkingSeconds,
                                                    totalSeconds = if (totalSec > 0L) totalSec else cur.totalSeconds,
                                                    inputTokens = if (inTok > 0L) inTok else cur.inputTokens,
                                                    outputTokens = if (outTok > 0L) outTok else cur.outputTokens,
                                                    cacheHitTokens = if (cacheTok > 0L) cacheTok else cur.cacheHitTokens,
                                                    contextLength = if (ctxTok > 0L) ctxTok else cur.contextLength,
                                                    aiFileChanges = if (hasChanges) AiChatManager.serializeFileChanges(outcome.fileChanges) else cur.aiFileChanges,
                                                    aiToolCallCount = if (hasCalls) outcome.toolCallCount else cur.aiToolCallCount,
                                                    aiToolStages = if (hasCalls) outcome.toolCalls else cur.aiToolStages
                                                )
                                                val snap = messages.filter { !it.isSystemNotice }.toList()
                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                                            } else if (!stillHere) {
                                                // 已切走会话：把中断前已生成的正文与真实改动/工具调用写回所属会话文件，切回即可见
                                                val liveReasoning = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }?.reasoningText ?: ""
                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                    try {
                                                        patchAiReplyInFile(
                                                            ctx, currentUserId, myKey, aiMsgId,
                                                            text = (if (finalText.isNotBlank()) finalText else "") + tipSuffix,
                                                            reasoningText = liveReasoning,
                                                            // 用户点了停止：会话文件里也标记「已手动停止」（与仍在当前会话时的内存行为一致，
                                                            // 切回来才有「已停止」状态与「继续处理任务」；同时让 token 卡片知道这是中断记录）
                                                            manualStopped = if (stoppedByUser) true else null,
                                                            // 中断也把耗时/用量写回会话文件：切回会话时「Token 明细」入口依旧在
                                                            thinkingSeconds = if (thinkSec > 0L) thinkSec else null,
                                                            totalSeconds = if (totalSec > 0L) totalSec else null,
                                                            inputTokens = if (inTok > 0L) inTok else null,
                                                            outputTokens = if (outTok > 0L) outTok else null,
                                                            cacheHitTokens = if (cacheTok > 0L) cacheTok else null,
                                                            contextLength = if (ctxTok > 0L) ctxTok else null,
                                                            fileChangesJson = if (hasChanges) AiChatManager.serializeFileChanges(outcome.fileChanges) else "",
                                                            toolCallCount = if (hasCalls) outcome.toolCallCount else 0,
                                                            toolStages = if (hasCalls) outcome.toolCalls else null,
                                                            // 口径来源一并落盘：true=服务端权威结算，false=客户端估算
                                                            aiUsageAuthoritative = when {
                                                                usageAuthoritative -> true
                                                                stoppedByUser -> false
                                                                else -> null
                                                            }
                                                        )
                                                    } catch (_: Exception) {}
                                                }
                                            }
                                            // 中断/出错收尾：同样统一收口残留的等待态（本条若因索引/ID 变化没被写回，
                                            // 这里也能保证它立刻退出「等待模型响应」，秒数不再跳）
                                            if (stillHere) closeStaleAiWaiting(0L)
                                        }
                                    }
                                }
                            }
                            }
                            }
                        } else {
                        ChatViewModel.sendMessage(
                            currentUserId = currentUserId,
                            friendId = friendId,
                            text = text,
                            replyTo = replyToId,
                            replyToText = replyText,
                            replyToSender = replySender,
                            contactSource = contactSource,
                            onSuccess = { serverMsgId ->
                                // 回填服务端消息 ID：撤回时优先用 serverId，避免用本地临时 id 撤回导致 404
                                if (serverMsgId > 0 && sentMsg != null) {
                                    val si = messages.indexOfFirst { it.id == sentMsg.id }
                                    if (si >= 0) messages[si] = (messages[si] as? ChatMsg)?.copy(serverId = serverMsgId) ?: messages[si]
                                }
                                ChatViewModel.ensureConversation(friendId, friendName, text, System.currentTimeMillis() / 1000)
                                onContactSourceConsumed()
                                scope.launch {
                                    kotlinx.coroutines.delay(50)
                                    if (messages.isNotEmpty()) {
                                        listState.scrollToItem(0)
                                    }
                                }
                            },
                            onError = { msg ->
                                // 统一分类：命中即终态业务失败（拉黑/好友验证/账号异常/被禁言…），
                                // 没命中即瞬时失败（网络/超时/5xx/限流）。
                                val sf = classifySendFailure(msg)
                                when {
                                    sf != null && sf.logout -> {
                                        // 账号已废 / 登录失效：直接踢下线，不保留气泡、不打印广播
                                        AuroraApi.showErrorToast(ctx, sf.text)
                                        scope.launch { ChatRepository.checkAndLogoutCurrentUser(ctx, currentUserId) }
                                        if (sentMsg != null) messages.removeAll { it.id == sentMsg.id }
                                    }
                                    sf != null -> {
                                        // 终态业务失败：红叹号气泡留在原地，并打印一条本地广播（居中系统提示）留存。
                                        // 这类错误服务端已明确拒收，重试也没用，所以不再自动重试。
                                        markSendFailed(
                                            sentMsg ?: ChatMsg.create(
                                                text = text, isMine = true, fromUserId = currentUserId,
                                                replyToId = replyToId, replyToText = replyText,
                                                replyToSender = replySender, contactSource = contactSource
                                            )
                                        )
                                        addLocalNotice(friendId, sf.text, "send_blocked")
                                    }
                                    else -> {
                                        // 瞬时失败（网络/超时/限流…）：红叹号气泡留在原地，后台会自动重试。
                                        // 不再弹「发送失败」提示：气泡本身就是失败态，点它即可重发。
                                        markSendFailed(
                                            sentMsg ?: ChatMsg.create(
                                                text = text, isMine = true, fromUserId = currentUserId,
                                                replyToId = replyToId, replyToText = replyText,
                                                replyToSender = replySender, contactSource = contactSource
                                            )
                                        )
                                    }
                                }
                            },
                            context = ctx
                        )
                    } // end else (非注销用户 走 API)
                    } // end else (非注销用户发送逻辑)
                    }
                } },
                onSendMedia = { uri, flashSec, name, original ->
                    // 用文件名扩展名推断类型（比 contentResolver.getType 可靠，避免 APK/TXT 被误判成图片/视频）；
                    // 文件名拿不到扩展名时（content:// 常是数字 ID）再用系统 MIME 兜底，
                    // 否则 SVG 等会落到 application/octet-stream，被当普通文件上传、最终变成 .bin
                    val byName = guessMediaMimeType(name)
                    val mimeType = if (byName == "application/octet-stream") {
                        ctx.contentResolver.getType(uri)?.lowercase()
                            ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                            ?: byName
                    } else byName
                    val isImage = mimeType.startsWith("image/")
                    val isVideo = mimeType.startsWith("video/")
                    val extraType = if (isImage) "image" else if (isVideo) "video" else "file"
                    // 交给后台上传管理器：本地压成 JPG → 落盘待发队列 → 离开对话/退出软件也继续上传，下次启动自动重试
                    // original = 相册里勾了「原图」：不压缩、按原始字节直传
                    val localId = com.aurora.chat.data.upload.MediaUploadManager.enqueueFromUri(
                        ctx, friendId, isGroupChat(friendId), uri, mimeType, "", flashSec,
                        voiceDuration = 0, original = original
                    )
                    if (localId >= 0) {
                        val placeholderMsg = ChatMsg.create(serverId = localId, text = "", isMine = true, fromUserId = currentUserId, isNew = true, mediaType = extraType, mediaUrl = "", isUploading = true, flashDuration = flashSec, localPreviewUri = uri.toString())
                        messages.add(placeholderMsg)
                        pendingUploadPlaceholders.add(placeholderMsg)
                        uploadProgressMap[localId] = 0f
                        uploadFileNameMap[localId] = name
                        // 图片/文件同样要先在后台解码+压缩+落盘（10 张图是串行预处理），
                        // 这段时间进度只能是 0%，明确标成「压缩中」而不是让用户看「上传中 0%」干等
                        uploadStageMap[localId] = "compressing"
                        if (isVideo) {
                            uploadRatioMap[localId] = com.aurora.chat.data.upload.MediaUploadManager.videoAspectRatio(localId)
                        }
                        scope.launch { if (messages.isNotEmpty()) listState.scrollToItem(0) }
                    } else {
                        AuroraApi.showErrorToast(ctx, "无法读取文件")
                    }
                }
            )
                } else {
                    // 多选模式：底部输入区被操作栏覆盖（复制 / 删除 / 取消）
                    MultiSelectActionBar(
                        count = selectedMsgIds.value.size,
                        onSave = {
                            // 把选中的消息用「真实气泡组件」离屏渲染后拼成一张长图，保存到相册。
                            // 不设条数上限：条数多时由导出器加高/整体缩放来适配。
                            val picked = messages.filter { it.id in selectedMsgIds.value }
                            // 闪照/已撤回/系统通知一律不入图（与「分享」同一套隐私过滤）
                            val usable = picked.filter { isShareableChatMessage(it, destroyedFlashIds) }
                            when {
                                picked.isEmpty() ->
                                    Toast.makeText(ctx, "请先选择要保存的消息", Toast.LENGTH_SHORT).show()
                                usable.isEmpty() ->
                                    Toast.makeText(ctx, "选中的都是闪照/已撤回内容，无法保存为图片", Toast.LENGTH_SHORT).show()
                                else -> {
                                    // 虚拟截图：与列表一致，用真实气泡组件离屏渲染后一次性画成等高的长图。
                                    // 不加标题栏/页脚，只输出选中的消息本身。
                                    val items = usable.sortedBy { it.createdAt }
                                        .map { ChatImageExporter.Item(it) }
                                    Toast.makeText(ctx, "正在生成图片…", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val bmp = runCatching {
                                            ChatImageExporter.render(
                                                context = ctx,
                                                currentUserId = currentUserId,
                                                currentUserName = currentUserName,
                                                friendId = friendId,
                                                friendName = friendName,
                                                isGroup = isGroupChat(friendId),
                                                groupMemberNames = groupMemberNames,
                                                senderRoles = groupRoles,
                                                items = items,
                                                allMessages = messages,
                                            )
                                        }.onFailure { t ->
                                            // 记录真实异常（含布局/绘制阶段的栈），便于定位“含图片/卡片消息生成失败”
                                            ErrorReporter.error("ChatShot", "导出异常 friendId=$friendId 选中=${items.size} 条", t)
                                        }.getOrNull()
                                        if (bmp == null) {
                                            ErrorReporter.warn("ChatShot", "导出失败：render 返回 nullfriendId=$friendId 选中=${items.size} 条")
                                            Toast.makeText(ctx, "生成失败，请重试", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val ok = com.aurora.chat.util.GallerySaver.saveBitmap(ctx, bmp, "ChatShot")
                                            bmp.recycle()
                                            if (!ok) ErrorReporter.warn("ChatShot", "写入相册失败 friendId=$friendId 选中=${items.size} 条")
                                            Toast.makeText(ctx, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        onShare = {
                            // 按时间顺序取出选中消息 → 过滤掉闪照/已销毁闪照/已撤回/系统通知 → 再序列化为「分享聊天」卡片
                            val picked = messages.filter { it.id in selectedMsgIds.value }
                            val shareable = picked.filter { isShareableChatMessage(it, destroyedFlashIds) }
                            when {
                                picked.isEmpty() ->
                                    Toast.makeText(ctx, "请先选择要分享的消息", Toast.LENGTH_SHORT).show()
                                shareable.isEmpty() ->
                                    Toast.makeText(ctx, "选中的都是闪照/已撤回内容，无法分享", Toast.LENGTH_SHORT).show()
                                else -> {
                                    // 不做任何条数/长度裁剪：选中的可分享消息全部进卡片。
                                    // 闪照/已撤回内容仅在构建时静默剔除（防护要求），不再弹任何「已跳过」提示。
                                    chatShareCardText = buildChatShareCard(shareable, currentUserName, destroyedFlashIds)
                                    showChatShareDialog = true
                                }
                            }
                        },
                        onFavorite = {
                            // 批量收藏：把选中的多条消息整合成一条「聊天记录」收藏，
                            // 在收藏页「文本」分类里按对话原样查看。
                            val picked = messages.filter { it.id in selectedMsgIds.value }
                            val usable = picked.filter { isShareableChatMessage(it, destroyedFlashIds) }
                            when {
                                picked.isEmpty() ->
                                    Toast.makeText(ctx, "请先选择要收藏的消息", Toast.LENGTH_SHORT).show()
                                usable.isEmpty() ->
                                    Toast.makeText(ctx, "选中的都是闪照/已撤回内容，无法收藏", Toast.LENGTH_SHORT).show()
                                else -> scope.launch {
                                    val r = com.aurora.chat.data.local.FavoritesStore.addBundle(
                                        ctx,
                                        usable.sortedBy { it.createdAt },
                                        title = "与 $friendName 的聊天记录",
                                        subtitle = friendName
                                    )
                                    Toast.makeText(
                                        ctx,
                                        when (r) {
                                            com.aurora.chat.data.local.FavoriteAddResult.ADDED ->
                                                "已收藏 ${usable.size} 条消息为一条聊天记录"
                                            com.aurora.chat.data.local.FavoriteAddResult.EXISTS ->
                                                "这段聊天记录已收藏过"
                                            com.aurora.chat.data.local.FavoriteAddResult.FAILED ->
                                                "收藏失败"
                                        },
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (r == com.aurora.chat.data.local.FavoriteAddResult.ADDED) {
                                        selectedMsgIds.value = emptySet()
                                    }
                                }
                            }
                        },
                        onDelete = { multiDeleteConfirm = true },
                        onCopy = {
                            // 按时间顺序逐条复制，不是把内容整个拼成一段
                            val texts = messages.asSequence()
                                .filter { it.id in selectedMsgIds.value && it.text.isNotBlank() }
                                .map { it.text }
                                .joinToString("\n")
                            if (texts.isNotBlank()) {
                                try {
                                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("消息", texts))
                                    Toast.makeText(ctx, "已复制 ${selectedMsgIds.value.size} 条消息", Toast.LENGTH_SHORT).show()
                                    exitMultiSelect()
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(ctx, "所选消息无可复制内容", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onCancel = { exitMultiSelect() }
                    )
                }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ChatConversationOverlaysInChatBox(
    currentUserId: Long,
    friendId: Long,
    friendName: String,
    onBack: () -> Unit,
    onOpenJoinRequestFullScreen: (org.json.JSONArray, Long) -> Unit,
    onOpenNewChat: (Long, String, String) -> Unit,
    ctx: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    listState: androidx.compose.foundation.lazy.LazyListState,
    messages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>,
    adapter: MessageStoreAdapter,
    isExitedGroup: Boolean,
    chatSearchState: ChatSearchState,
    /** 全屏图片预览是否打开：搜索页据此让出返回键，保证预览最先接住返回 */
    imagePreviewUrlsState: MutableState<List<String>>,
    shouldShowScrollDown: androidx.compose.runtime.State<Boolean>,
    selectedMsgIds: MutableState<Set<Long>>,
    pendingToolApprovalDeferred: MutableState<kotlinx.coroutines.CompletableDeferred<Boolean>?>,
    syncMessages: () -> Unit,
    aiChangesFocusDetailState: MutableState<Boolean>,
    aiChangesFocusPathState: MutableState<String>,
    aiChangesJsonDataState: MutableState<String>,
    aiChangesMsgIdState: MutableState<Long>,
    aiChangesOpenState: MutableState<Boolean>,
    aiToolCallsDataState: MutableState<List<ChatMsg.ToolStageRecord>>,
    aiToolCallsOpenState: MutableState<Boolean>,
    approvalMinimizedState: MutableState<Boolean>,
    approvalTrustUntilState: MutableState<Long>,
    autoFollowState: MutableState<Boolean>,
    friendStatusRefreshState: MutableState<Int>,
    isNotGroupMemberState: MutableState<Boolean>,
    jumpToMessageIdState: MutableState<Long>,
    multiSelectModeState: MutableState<Boolean>,
    pendingAiFilesState: MutableState<List<PendingAiFile>>,
    pendingToolApprovalState: MutableState<Pair<String, String>?>,
    profileUserIdState: MutableState<Long>,
    profileUserNameState: MutableState<String>,
    programmaticScrollingState: MutableState<Boolean>,
    searchReturnPendingState: MutableState<Boolean>,
    selectAnchorMsgIdState: MutableState<Long>,
    selectToHereDirState: MutableState<Int>,
    showAddFriendGreetingState: MutableState<Boolean>,
    showAiUsageState: MutableState<Boolean>,
    showBackToBottomState: MutableState<Boolean>,
    showBackupScreenState: MutableState<Boolean>,
    showChatHistorySearchState: MutableState<Boolean>,
    showFreeApiSelectState: MutableState<Boolean>,
    showFriendSettingsState: MutableState<Boolean>,
    showGroupSettingsState: MutableState<Boolean>,
    showPaidApiSelectState: MutableState<Boolean>,
    showProfileState: MutableState<Boolean>,
    showUsageHistoryState: MutableState<Boolean>,
    showWorkspaceFilesState: MutableState<Boolean>,
    unreadNewCountState: MutableState<Int>,
    upScrollPxState: MutableState<Float>,
    workspaceLocateNameState: MutableState<String?>,
    workspaceLocateOpenState: MutableState<Boolean>,
    wsInjectForSendState: MutableState<List<Triple<Uri, String, Long>>>,
    wsPickModeState: MutableState<Boolean>,
    /** 查找聊天记录里点图片：直接开全屏预览（带 hero 放大/缩回动画），不跳回会话定位 */
    onOpenImagePreview: (urls: List<String>, index: Int, sourceRect: Rect?) -> Unit,
) {
    var aiChangesFocusDetail by aiChangesFocusDetailState
    var aiChangesFocusPath by aiChangesFocusPathState
    var aiChangesJsonData by aiChangesJsonDataState
    var aiChangesMsgId by aiChangesMsgIdState
    var aiChangesOpen by aiChangesOpenState
    var aiToolCallsData by aiToolCallsDataState
    var aiToolCallsOpen by aiToolCallsOpenState
    var approvalMinimized by approvalMinimizedState
    var approvalTrustUntil by approvalTrustUntilState
    var autoFollow by autoFollowState
    var friendStatusRefresh by friendStatusRefreshState
    var isNotGroupMember by isNotGroupMemberState
    var jumpToMessageId by jumpToMessageIdState
    var multiSelectMode by multiSelectModeState
    var pendingAiFiles by pendingAiFilesState
    var pendingToolApproval by pendingToolApprovalState
    var profileUserId by profileUserIdState
    var profileUserName by profileUserNameState
    var programmaticScrolling by programmaticScrollingState
    var searchReturnPending by searchReturnPendingState
    var selectAnchorMsgId by selectAnchorMsgIdState
    var selectToHereDir by selectToHereDirState
    var showAddFriendGreeting by showAddFriendGreetingState
    var showAiUsage by showAiUsageState
    var showBackToBottom by showBackToBottomState
    var showBackupScreen by showBackupScreenState
    var showChatHistorySearch by showChatHistorySearchState
    var showFreeApiSelect by showFreeApiSelectState
    var showFriendSettings by showFriendSettingsState
    var showGroupSettings by showGroupSettingsState
    var showPaidApiSelect by showPaidApiSelectState
    var showProfile by showProfileState
    var showUsageHistory by showUsageHistoryState
    var showWorkspaceFiles by showWorkspaceFilesState
    var unreadNewCount by unreadNewCountState
    var upScrollPx by upScrollPxState
    var workspaceLocateName by workspaceLocateNameState
    var workspaceLocateOpen by workspaceLocateOpenState
    var wsInjectForSend by wsInjectForSendState
    var wsPickMode by wsPickModeState
    // 图片预览打开时，本层所有返回处理（搜索页分类回退、跳回搜索页）一律让位给预览层
    val imagePreviewOpen = imagePreviewUrlsState.value.isNotEmpty()

        // ===== 滚动到底部按钮（悬浮在输入栏上方，靠右对齐） =====
        AnimatedVisibility(
            visible = shouldShowScrollDown.value,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(
                // AI 对话输入框正上方多一行 36.dp 工具行，按钮需整体上移避开，普通对话保持不变
                bottom = if (friendId == AI_CHAT_ID) 92.dp else 56.dp,
                end = 16.dp
            )
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(com.aurora.chat.ui.theme.appCardBg(), CircleShape)
                    .border(1.dp, Color(0xFFD1D5DB), CircleShape)
                    .clickable {
                        // 点击：恢复自动跟随 + 带守卫滚回底部（恢复后 AI 继续输出会自动跟随）
                        autoFollow = true
                        programmaticScrolling = true
                        unreadNewCount = 0
                        // 程序化滚动期间 collect 会提前 return，这里必须自己把按钮状态与累计距离归零
                        showBackToBottom = false
                        upScrollPx = 0f
                        // 窗口若与最新脱钩（上滑超过上限、底部被丢弃），先接回最新再滚，
                        // 否则 scrollToItem(0) 只会滚到「窗口里的最新」，而不是真正的最新消息。
                        if (adapter.hasNewerInStore()) {
                            adapter.reattachTail()
                            syncMessages()
                        }
                        scope.launch {
                            try { listState.scrollToItem(0) } finally { programmaticScrolling = false }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // 有未读新消息时显示数量（替换三角）；无未读时维持原三角指示
                if (unreadNewCount > 0) {
                    Text(
                        text = if (unreadNewCount >= 99) "99+" else "$unreadNewCount",
                        color = Color(0xFF1E40AF),
                        fontSize = if (unreadNewCount >= 99) 11.sp else 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                } else {
                    Canvas(modifier = Modifier.size(26.dp)) {
                        val c = center
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(c.x - 10.5f, c.y - 5f)
                            lineTo(c.x, c.y + 6.5f)
                            lineTo(c.x + 10.5f, c.y - 5f)
                            close()
                        }
                        drawPath(path, com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            }
        }

        // ===== 多选「选择到这里」：先点选第一条作锚点，再滚动出现按钮，点击连选锚点→滚动边界这段 =====
        if (multiSelectMode) {
            // 可视区的顶部/底部展示下标。
            // 注意 reverseLayout=true 时 LazyColumn 从底部开始布局：firstVisibleItemIndex 是
            // **最底部**可见项、不是顶部项（visibleItemsInfo 里 index 最小=底部、最大=顶部）。
            // 之前误把 firstVisibleItemIndex 当顶部，导致方向判断整个翻转（选的是底部那半段）。
            fun visibleBounds(): Pair<Int, Int>? {
                val infos = listState.layoutInfo.visibleItemsInfo
                if (infos.isEmpty()) return null
                val minDisp = infos.minOf { it.index }
                val maxDisp = infos.maxOf { it.index }
                return minDisp to maxDisp
            }
            fun dispToData(disp: Int, n: Int): Int =
                if (n > 0) (n - 1 - disp).coerceIn(0, n - 1) else 0

            LaunchedEffect(selectAnchorMsgId, listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size) {
                if (selectAnchorMsgId == -1L) {
                    selectToHereDir = 0
                } else {
                    val anchorIdx = messages.indexOfFirst { it.id == selectAnchorMsgId }
                    val n = messages.size
                    val b = visibleBounds()
                    selectToHereDir = when {
                        anchorIdx < 0 || b == null -> 0
                        else -> {
                            // 数据下标：越小越新(靠下)，越大越旧(靠上)
                            val bottomData = dispToData(b.first, n)   // 可视区底部(较新)
                            val topData = dispToData(b.second, n)     // 可视区顶部(较旧)
                            when {
                                // 锚点比可视区底部还新(在下方) → 就近显示底部按钮，从锚点选到可视底部
                                anchorIdx < bottomData -> -1
                                // 锚点比可视区顶部还旧(在上方) → 就近显示顶部按钮，从可视顶部选到锚点
                                anchorIdx > topData -> 1
                                // 锚点在可视区内：偏上就顶部按钮、偏下就底部按钮
                                anchorIdx >= (topData + bottomData) / 2 -> 1
                                else -> -1
                            }
                        }
                    }
                }
            }
            if (selectToHereDir != 0) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = if (selectToHereDir == 1)
                        Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 100.dp)
                    else
                        Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 76.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                            .clickable {
                                val anchorIdx = messages.indexOfFirst { it.id == selectAnchorMsgId }
                                val infos = listState.layoutInfo.visibleItemsInfo
                                if (anchorIdx >= 0 && infos.isNotEmpty()) {
                                    val n = messages.size
                                    fun d2d(disp: Int): Int =
                                        if (n > 0) (n - 1 - disp).coerceIn(0, n - 1) else 0
                                    // reverseLayout：index 最小=可视区底部(较新)，最大=顶部(较旧)
                                    val bottomData = d2d(infos.minOf { it.index })
                                    val topData = d2d(infos.maxOf { it.index })
                                    val boundary = if (selectToHereDir == -1) bottomData else topData
                                    val (from, to) = minOf(anchorIdx, boundary) to maxOf(anchorIdx, boundary)
                                    selectedMsgIds.value = messages.subList(from, to + 1).map { it.id }.toSet()
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("选择到这里", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ── 选择免费 API（外层 Box 直接子，位于 Column 之后 → 全屏覆盖顶栏）──
        AnimatedVisibility(
            visible = showFreeApiSelect,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            FreeApiSelectScreen(
                currentUserId = currentUserId,
                onBack = { showFreeApiSelect = false; showAiUsage = true },
                onSelected = { showFreeApiSelect = false; showAiUsage = true }
            )
        }

        // ── 选择付费 API（外层 Box 直接子，全屏覆盖）──
        AnimatedVisibility(
            visible = showPaidApiSelect,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            PaidApiSelectScreen(
                currentUserId = currentUserId,
                onBack = { showPaidApiSelect = false; showAiUsage = true },
                onSelected = { showPaidApiSelect = false; showAiUsage = false }
            )
        }


        // 好友资料面板（从右侧滑入覆盖）
        if (friendId > 0) {
            AnimatedVisibility(
                visible = showFriendSettings,
                enter = slideInHorizontally(tween(300)) { it },
                exit = slideOutHorizontally(tween(300)) { it },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
                    com.aurora.chat.ui.components.EventBlocker()
                    FriendProfilePanel(
                        friendId = friendId,
                        friendName = friendName,
                        currentUserId = currentUserId,
                        onBack = { showFriendSettings = false },
                        onOpenHistorySearch = { showChatHistorySearch = true },
                        onDeleteFriend = {
                            showFriendSettings = false
                            scope.launch {
                                val r = ChatRepository.deleteFriend(friendId)
                                if (r.success) {
                                    android.widget.Toast.makeText(ctx, "好友已删除", android.widget.Toast.LENGTH_SHORT).show()
                                    // 单方面删除：只我自己这一侧移除，需清掉联系人缓存避免残留；
                                    // 会话与历史消息保留，横幅刷新为「对方还不是你的好友」
                                    com.aurora.chat.ui.contacts.invalidateFriendCache(ctx, currentUserId)
                                    friendStatusRefresh++
                                    ChatViewModel.loadConversations(force = true)
                                    onBack()
                                } else {
                                    android.widget.Toast.makeText(ctx, "删除失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }

        // 群设置面板（从右侧滑入覆盖，始终保留在组合树中以确保退出动画播放）
        if (isGroupChat(friendId)) {
            val internalGroupId = -friendId - 1000
            AnimatedVisibility(
                visible = showGroupSettings,
                enter = slideInHorizontally(tween(300)) { it },
                exit = slideOutHorizontally(tween(300)) { it },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
                    com.aurora.chat.ui.components.EventBlocker()
                    GroupSettingsPanel(
                        internalGroupId = internalGroupId,
                        groupName = friendName,
                        // 只读态覆盖所有「已经不能在此发言」的场景：
                        //   1) 有本地退出记录 —— 被管理员移出 / 自己退群 / 群主解散 / 服务端给出的其它原因；
                        //   2) 服务端判定我已不是该群成员（角色为空，但退出广播没收到/丢了）。
                        // 只认 isExitedGroup 的话，第 2 种会漏掉，表现为：底部已经不能发言，
                        // 设置页里却还能改「置顶/免打扰」、也没有「删除聊天」。
                        readOnly = isExitedGroup || isNotGroupMember,
                        onBack = { showGroupSettings = false },
                        onOpenProfile = { uid, name ->
                            profileUserId = uid
                            profileUserName = name
                            // 从群设置进入：尝试用现有 friendInfo / systemUserName
                            showProfile = true
                        },
                        onOpenChat = { uid, name ->
                            showGroupSettings = false
                            if (uid != friendId) {
                                onOpenNewChat(uid, name, if (isGroupChat(friendId)) "group:${friendName}" else "")
                            }
                        },
                        // 解散 / 退出群聊：只收起设置面板，回到这条会话里。
                        // 会话此时会切成只读并显示真实原因（「你已退出群聊」/「群主已解散群聊」），
                        // 不再整屏退回聊天列表。只有下面的「删除聊天」才走 onBack 回列表 ——
                        // 那条会话已经从本机清掉了，留在里面没有意义。
                        onDissolved = { showGroupSettings = false },
                        onDeleted = {
                            // 本地数据已清干净、会话已从列表移除：关掉设置页并退出这个会话，
                            // 否则会停留在一条已经不存在的聊天里。
                            showGroupSettings = false
                            onBack()
                        },
                        onOpenJoinRequestList = onOpenJoinRequestFullScreen,
                        onOpenHistorySearch = { showChatHistorySearch = true }
                    )
                }
            }
        }

        // 查找聊天记录（全屏）：从好友资料 / 群聊信息进入，顶部直接是输入框
        AnimatedVisibility(
            visible = showChatHistorySearch,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                ChatHistorySearchScreen(
                    conversationId = friendId,
                    currentUserId = currentUserId,
                    conversationTitle = friendName,
                    state = chatSearchState,
                    onBack = { showChatHistorySearch = false; searchReturnPending = false },
                    onOpenMessage = { msgId ->
                        // 关闭搜索与设置面板，回到会话并定位该消息；
                        // 返回键回到搜索页（关键字与结果都保留，不用重搜）
                        showChatHistorySearch = false
                        showFriendSettings = false
                        showGroupSettings = false
                        searchReturnPending = true
                        jumpToMessageId = msgId
                    },
                    // 图片走「直接放大查看」：不关搜索页、不定位原消息，
                    // 关闭预览后自然回到刚才那屏九宫格。
                    onOpenMedia = onOpenImagePreview,
                    // 预览打开时本页不接返回：返回键/侧滑一律先关图片
                    imagePreviewOpen = imagePreviewOpen
                )
            }
        }
        // 从搜索结果跳回会话后：先回到搜索页
        BackHandler(enabled = searchReturnPending && !showChatHistorySearch && !imagePreviewOpen) {
            showChatHistorySearch = true
            searchReturnPending = false
        }

        // 用户资料面板（从右侧滑入）
        AnimatedVisibility(
            visible = showProfile && profileUserId > 0,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                UserProfileCard(
                userId = profileUserId,
                userName = profileUserName,
                currentUserId = currentUserId,
                onBack = { showProfile = false },
                onOpenChat = { uid, name ->
                    showProfile = false
                    showGroupSettings = false
                    showFriendSettings = false
                    if (uid != friendId) {
                        onOpenNewChat(uid, name, if (isGroupChat(friendId)) "group:${friendName}" else "")
                    }
                },
                onDeleteFriend = {
                    scope.launch {
                        val r = ChatRepository.deleteFriend(profileUserId)
                        if (r.success) {
                            android.widget.Toast.makeText(ctx, "已删除好友", android.widget.Toast.LENGTH_SHORT).show()
                            // 单方面删除：清掉联系人缓存（只影响我这一侧），会话与历史消息保留
                            com.aurora.chat.ui.contacts.invalidateFriendCache(ctx, currentUserId)
                            ChatViewModel.loadConversations(force = true)
                            // 若删的就是当前会话对象，立即刷新横幅为「对方还不是你的好友」
                            if (profileUserId == friendId) friendStatusRefresh++
                            showProfile = false
                        } else {
                            android.widget.Toast.makeText(ctx, "删除失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            }
        }
        // ── 发送好友申请（添加好友）全屏面板：复用「新的好友 → 打招呼」面板，不另写界面 ──
        AnimatedVisibility(
            visible = showAddFriendGreeting,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                SendGreetingPanel(
                    userName = friendName.ifBlank { "用户$friendId" },
                    onSend = { greeting ->
                        scope.launch {
                            val r = ChatRepository.sendFriendRequest(friendId.toString(), greeting.trim())
                            if (!r.success && (r.message.contains("已经是你的好友") || r.message.contains("已经是好友"))) {
                                // 服务端明确说「已经是好友」= 好友证据。立即解除本地「非好友标记」，
                                // 并写下粘性好友证据（不会被 getFriends() 的全量替换冲掉），
                                // 横幅当场收掉且以后也不会再回来。
                                ChatRepository.markAsFriend(friendId)
                                // 子屏内拿不到横幅状态：靠 friendStatusRefresh 触发主屏重查
                                showAddFriendGreeting = false
                                friendStatusRefresh++
                                android.widget.Toast.makeText(
                                    ctx, "对方已经是你的好友，无需重复添加",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (r.success) "好友申请已发送" else "发送失败: ${r.message}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                if (r.success) showAddFriendGreeting = false
                            }
                        }
                    },
                    onBack = { showAddFriendGreeting = false }
                )
            }
        }


        // 工作区文件管理：从右侧滑入的全屏面板（AI 对话与好友/群聊通用，置于根 Box 最后一个子项保证全屏覆盖）
        AnimatedVisibility(
            visible = showWorkspaceFiles,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            WorkspaceFileManagerScreen(
                onBack = {
                    showWorkspaceFiles = false
                    wsPickMode = false
                    workspaceLocateName = null
                },
                    // 备份面板是 AI 对话专属，其它会话点它不跳转
                    onOpenBackup = {
                        if (friendId == AI_CHAT_ID) {
                            showWorkspaceFiles = false; wsPickMode = false; workspaceLocateName = null; showBackupScreen = true
                        }
                    },
                    locateName = workspaceLocateName,
                    locateOpen = workspaceLocateOpen,
                    // 「选择文件」模式：工作区里点中文件即交给当前对话
                    pickMode = wsPickMode,
                    // ⋮ 菜单项文案随会话类型变化
                    referenceLabel = when {
                        friendId == AI_CHAT_ID -> "发送给 AI"
                        isGroupChat(friendId) -> "发送给群聊"
                        else -> "发送给好友"
                    },
                    // ⋮ 菜单项与选择模式共用：只挂起来、不发送 —— 与正常选文件流程一致，点发送才发出
                    onPickToChat = { rel ->
                        wsPickMode = false
                        showWorkspaceFiles = false
                        workspaceLocateName = null
                        val f = java.io.File(AiChatManager.agentRoot(ctx), rel)
                        if (f.exists()) {
                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(f.extension.lowercase())
                                ?: "application/octet-stream"
                            if (friendId == AI_CHAT_ID) {
                                // AI 对话：挂到输入框上方的附件条（工作区引用，发送时不重复拷贝）
                                pendingAiFiles = pendingAiFiles + PendingAiFile(
                                    uri = Uri.fromFile(f),
                                    name = f.name,
                                    mime = mime,
                                    size = f.length(),
                                    bytes = null,
                                    wsRelPath = rel
                                )
                            } else {
                                // 好友/群聊：注入普通聊天的待发送队列 → 弹「已选择 N 个文件」，点发送才真正发出
                                wsInjectForSend = listOf(
                                    Triple(Uri.fromFile(f), f.name, f.length())
                                )
                            }
                        }
                    }
                )
        }
        // AI 显示设置（齿轮）：全屏从右侧切入的普通 Compose 层，根 Box 直属子项。
        // 刻意不用 Dialog —— 不用 decorFitsSystemWindows，就没有状态栏穿透 / 顶栏配色不一致的问题。
        if (friendId == AI_CHAT_ID) {
            AnimatedVisibility(
                visible = showAiSettings.value,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
                    com.aurora.chat.ui.components.EventBlocker()
                    AiDisplaySettingsScreen(onBack = { showAiSettings.value = false })
                }
            }
        }
        // Token 用量 · 近 30 天：从右侧滑入的全屏面板(点击顶栏「今日已用」进入)
        if (friendId == AI_CHAT_ID) {
            AnimatedVisibility(
                visible = showUsageHistory,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                UsageHistoryScreen(userId = currentUserId, onBack = { showUsageHistory = false })
            }
        }
        // 备份与恢复：独立全屏界面（单独 AnimatedVisibility，置于根 Box 直属子项，绝不作为工作区的子组件嵌入）
        if (friendId == AI_CHAT_ID) {
            AnimatedVisibility(
                visible = showBackupScreen,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                BackupScreen(onBack = { showBackupScreen = false; showWorkspaceFiles = true })
            }
            // 系统返回手势(左滑/系统返回键)拦截:备份打开时优先"回到工作区",
            // 绝不能让它直接穿透退出到聊天板块。必须放在其他 BackHandler 之后,确保优先级最高。
            BackHandler(enabled = showBackupScreen) { showBackupScreen = false; showWorkspaceFiles = true }
        }
        // 「查看所有改动」：独立全屏界面（根 Box 直属 AnimatedVisibility，与工作区/备份同层级，绝不嵌套）。
        // 数据 = 最后一次点击按钮那条 AI 消息携带的 ai_file_changes JSON。
        AnimatedVisibility(
            visible = aiChangesOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            AiChangesScreen(
                changesJson = aiChangesJsonData,
                onBack = { aiChangesOpen = false; aiChangesFocusDetail = false },
                focusPath = aiChangesFocusPath,
                openDetail = aiChangesFocusDetail,
                onChangesUpdated = { newJson ->
                    // 退回成功:更新内存消息的 ai_file_changes 并落盘,重进改动详情仍保持已退回
                    aiChangesJsonData = newJson
                    if (aiChangesMsgId > 0L) {
                        val idx = messages.indexOfFirst { it.id == aiChangesMsgId }
                        if (idx >= 0) {
                            val m = messages[idx]
                            if (m is ChatMsg) {
                                messages[idx] = m.copy(aiFileChanges = newJson)
                                val snap = messages.filter { !it.isSystemNotice }.toList()
                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    try { saveLocalChatMessages(ctx, currentUserId, AiConversationSession.storageKey(), snap) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                },
                baselineKey = AiConversationSession.storageKey()
            )
        }
        BackHandler(enabled = aiChangesOpen) { aiChangesOpen = false; aiChangesFocusDetail = false }

        // 「工具调用」全屏清单:与改动详情同层级,独立全屏、绝不嵌套
        AnimatedVisibility(
            visible = aiToolCallsOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            AiToolCallsScreen(records = aiToolCallsData, onBack = { aiToolCallsOpen = false })
        }
        BackHandler(enabled = aiToolCallsOpen) { aiToolCallsOpen = false }
        // 工具执行审批（用户要求：审批必须是弹窗形态，不能是常驻底部条把输入框遮住）。
        // 有待审批请求时才弹出居中弹窗，选择后自动关闭，平时不占任何布局空间、不遮挡输入框。
        // onDismissRequest（点击弹窗外区域）：**绝不默认判拒绝**——「点击外部=拒绝」是错误的，
        // 点外部只是用户暂时不想看/误触，不是拒绝。这里仅收起弹窗、审批保持挂起（deferred 不完成、
        // 进程级 Bridge 仍登记），并补发带「批准/拒绝」按钮的系统通知作为审批入口，不静默也不丢。
        // dismissOnBackPress=false：左滑/返回键切出对话时不把审批判为失败，弹窗随页面销毁、
        // 审批保持挂起（进程级桥仍在），由 ConversationPageTracker 触发通知/悬浮卡，回来可继续审批。
        if (pendingToolApproval != null) {
            val desc = com.aurora.chat.ui.chat.describeToolCall(pendingToolApproval!!.first, pendingToolApproval!!.second)
            Dialog(
                onDismissRequest = {
                    // 仅收起弹窗：不 complete、不判拒绝，审批保持挂起，由通知/悬浮卡承载后续审批入口
                    pendingToolApproval = null
                    pendingToolApprovalDeferred.value = null
                    approvalMinimized = true
                    // 收起后立即补发审批通知（带批准/拒绝按钮），审批既不静默也不丢失
                    val bridgeDesc = com.aurora.chat.AIApprovalBridge.pendingDescription()
                    if (bridgeDesc != null) {
                        NotificationHelper.showToolApprovalNotification(
                            ctx, com.aurora.chat.ui.chat.describeToolCall(bridgeDesc.first, bridgeDesc.second),
                            com.aurora.chat.AIApprovalBridge.activeRequestId() ?: -1L
                        )
                    }
                },
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = true)
            ) {
                Box(
                    Modifier
                        .width(320.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(com.aurora.chat.ui.theme.appSurface())
                        .padding(20.dp)
                ) {
                    Column {
                        Text("权限审批", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "AI 即将执行：\n$desc",
                            color = com.aurora.chat.ui.theme.appTextSecondary(), fontSize = 14.sp, lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 拒绝
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFFFEF2F2))
                                    .clickable { pendingToolApprovalDeferred.value?.complete(false); pendingToolApproval = null }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("拒绝", fontWeight = FontWeight.Bold, color = Color(0xFFDC2626), fontSize = 14.sp) }
                            // 信任5分钟（批量放行：本轮后续工具全部自动通过）
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(com.aurora.chat.ui.theme.appSelectedBg())
                                    .clickable {
                                        approvalTrustUntil = System.currentTimeMillis() + 5 * 60_000L
                                        pendingToolApprovalDeferred.value?.complete(true)
                                        pendingToolApproval = null
                                    }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("信任5分钟", fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF), fontSize = 14.sp) }
                            // 允许
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E40AF))
                                    .clickable {
                                        pendingToolApprovalDeferred.value?.complete(true)
                                        pendingToolApproval = null
                                    }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("允许", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp) }
                        }
                    }
                }
            }
        }

        // AI 向用户提问(ask_user 工具):抽屉从屏幕最底部滑上,盖在输入栏之上,不挤压任何布局。
        // 必须是根 Box 的直属子项——放进 Column 会占走列表/输入栏的空间,把工具栏顶起来。
        AskUserHost()
        // ═══ 备份恢复审批(红字警告,强制用户手动批准)═══
        // AI 调用 restore_backup 时(无论手动/完全访问/root 模式),只把请求挂到此总线并挂起;
        // 这里弹出红字警告确认框,用户亲自批准才真正写回工作区,拒绝则工作区与备份都不动。
        // 这是根 Box 直属子项,始终盖在最上层。
        val brReq by AiChatManager.BackupRestoreBus.pending.collectAsState()
        brReq?.let { req ->
            val ctxBr = LocalContext.current
            val brScope = rememberCoroutineScope()
            // 语言必须在组合中「可观察」地读取：直接 Locale.getDefault() 在切换系统/应用语言时
            // 不会触发重组，格式化结果会一直停在旧语言（Compose 已把这种写法判为错误）。
            val brLocale = LocalConfiguration.current.locales[0]
            val brTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", brLocale).format(Date(req.meta.createdAt))
            val brScopeStr = if (req.meta.type == "workspace" || req.meta.sourceRel.isEmpty()) "整个工作区" else "文件夹 ai_files/${req.meta.sourceRel}"
            val brSize = req.meta.sizeBytes.let { b ->
                when {
                    b < 1024 -> "$b B"
                    b < 1024 * 1024 -> String.format(brLocale, "%.1f KB", b / 1024f)
                    b < 1024L * 1024 * 1024 -> String.format(brLocale, "%.2f MB", b / (1024f * 1024f))
                    else -> String.format(brLocale, "%.2f GB", b / (1024f * 1024f * 1024f))
                }
            }
            AlertDialog(
                onDismissRequest = { AiChatManager.BackupRestoreBus.complete(false) },
                containerColor = com.aurora.chat.ui.theme.appCardBg(),
                title = { Text("⚠ 恢复备份确认", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "AI 请求将以下备份恢复到工作区。此操作会覆盖工作区对应内容，且无法撤销。",
                            color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("备份时间：$brTime", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        Text("备份范围：$brScopeStr", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        Text("文件数：${req.meta.fileCount}    大小：$brSize", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        Text(
                            "无论当前访问模式（手动 / 完全访问 / root），都必须由你亲自点击「批准恢复」才执行；拒绝或关闭则工作区与备份均保持不变。",
                            fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        brScope.launch { com.aurora.chat.ui.chat.restoreBackup(ctxBr, req.meta) }
                        AiChatManager.BackupRestoreBus.complete(true)
                    }) { Text("批准恢复", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { AiChatManager.BackupRestoreBus.complete(false) }) { Text("拒绝", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                }
            )
        }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatConversationOverlays(
    currentUserId: Long,
    currentUserName: String,
    friendId: Long,
    friendName: String,
    onBack: () -> Unit,
    onOpenShortVideo: (Long) -> Unit,
    onOpenPostDetail: (Long) -> Unit,
    onOpenGroupDetail: (Long) -> Unit,
    /** 分享抽屉「定位聊天」：把当前预览的那张图在消息列表里高亮并滚到可见位置 */
    onLocateInChat: (Long) -> Unit,
    ctx: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    listState: androidx.compose.foundation.lazy.LazyListState,
    messages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>,
    /** 「查找聊天记录」九宫格打开的老图片可能不在 messages 窗口里：URL → 消息引用兜底，供收藏/定位使用 */
    previewExtraRefs: Map<String, IMessageRef>,
    pendingUploadPlaceholders: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>,
    aiConvs: androidx.compose.runtime.snapshots.SnapshotStateList<AiConvMeta>,
    redpacketStatus: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, RedPacketStatus>,
    uploadProgressMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Float>,
    uploadStageMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, String>,
    uploadRatioMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Float>,
    uploadFileNameMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, String>,
    focusManager: androidx.compose.ui.focus.FocusManager,
    imageBounds: ImageBoundsRegistry,
    heroProgress: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    exitFade: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    videoProgress: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    aiAvatarLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, Uri?>,
    closeVideoPlayer: () -> Unit,
    deleteAiConv: (Long) -> Unit,
    deleteAiConvs: (List<Long>) -> Unit,
    openAiConversation: (Long) -> Unit,
    openVideoPlayer: (String) -> Unit,
    renameAiConv: (Long, String) -> Unit,
    saveChatImageToGallery: (String) -> Unit,
    startChatFileDownload: (String, String) -> Unit,
    startFileSaveAs: (String, String, String) -> Unit,
    startNewAiConversation: () -> Unit,
    activeAiConvIdState: MutableState<Long>,
    aiNameInputState: MutableState<String>,
    chatShareViewMsgIdState: MutableState<Long>,
    chatShareViewTextState: MutableState<String>,
    fileDownloadConfirmState: MutableState<Triple<String, String, String>?>,
    filePreviewTargetState: MutableState<Pair<String, String>?>,
    filePreviewVisibleState: MutableState<Boolean>,
    groupAtMembersState: MutableState<List<AtMember>>,
    heroClosingState: MutableState<Boolean>,
    heroEnterDoneState: MutableState<Boolean>,
    heroInitialIndexState: MutableState<Int>,
    heroReopeningState: MutableState<Boolean>,
    heroSourceRectState: MutableState<androidx.compose.ui.geometry.Rect?>,
    imagePreviewCloserState: MutableState<() -> Unit>,
    imagePreviewIndexState: MutableState<Int>,
    imagePreviewUrlsState: MutableState<List<String>>,
    imageSaveConfirmState: MutableState<Triple<String, String, String>?>,
    inputTextState: MutableState<String>,
    inviteRespondState: MutableState<InviteGroupCardData?>,
    isAdminOrOwnerState: MutableState<Boolean>,
    previewReadyState: MutableState<Boolean>,
    previewSessionState: MutableState<Int>,
    privacyRefreshKeyState: MutableState<Int>,
    profileUserIdState: MutableState<Long>,
    profileUserNameState: MutableState<String>,
    redPacketDetailIdState: MutableState<Long>,
    showAiConvDrawerState: MutableState<Boolean>,
    showAiNameInputState: MutableState<Boolean>,
    showAiNewConfirmState: MutableState<Boolean>,
    showAiUsageState: MutableState<Boolean>,
    showCameraState: MutableState<Boolean>,
    showLocationConfirmState: MutableState<Boolean>,
    showLocationRequestState: MutableState<Boolean>,
    showPrivacyPanelState: MutableState<Boolean>,
    showProfileState: MutableState<Boolean>,
    showRedPacketDialogState: MutableState<Boolean>,
    showImageShareSheetState: MutableState<Boolean>,
    showTransferDialogState: MutableState<Boolean>,
    videoHeroRectState: MutableState<androidx.compose.ui.geometry.Rect?>,
    videoOverlaySizeState: MutableState<androidx.compose.ui.unit.IntSize>,
    videoPlayUrlState: MutableState<String>,
    videoSessionState: MutableState<Int>,
) {
    var activeAiConvId by activeAiConvIdState
    var aiNameInput by aiNameInputState
    var chatShareViewMsgId by chatShareViewMsgIdState
    var chatShareViewText by chatShareViewTextState
    var fileDownloadConfirm by fileDownloadConfirmState
    var filePreviewTarget by filePreviewTargetState
    var filePreviewVisible by filePreviewVisibleState
    var groupAtMembers by groupAtMembersState
    var heroClosing by heroClosingState
    var heroEnterDone by heroEnterDoneState
    var heroInitialIndex by heroInitialIndexState
    var heroReopening by heroReopeningState
    var heroSourceRect by heroSourceRectState
    var imagePreviewCloser by imagePreviewCloserState
    var imagePreviewIndex by imagePreviewIndexState
    var imagePreviewUrls by imagePreviewUrlsState
    // 分享聊天全屏页等「独立来源」打开图片预览时，撤回判定交由调用方（其快照内消息恒未撤回），
    // 避免被真实会话里「同 URL 的原消息已撤回」误伤（详见 isMediaRevoked）。
    var previewRevokeOverride by remember { mutableStateOf<((String) -> Boolean)?>(null) }
    var imageSaveConfirm by imageSaveConfirmState
    var inputText by inputTextState
    var inviteRespond by inviteRespondState
    var isAdminOrOwner by isAdminOrOwnerState
    var previewReady by previewReadyState
    var previewSession by previewSessionState
    var privacyRefreshKey by privacyRefreshKeyState
    var profileUserId by profileUserIdState
    var profileUserName by profileUserNameState
    var redPacketDetailId by redPacketDetailIdState
    var showAiConvDrawer by showAiConvDrawerState
    var showAiNameInput by showAiNameInputState
    var showAiNewConfirm by showAiNewConfirmState
    var showAiUsage by showAiUsageState
    var showCamera by showCameraState
    var showLocationConfirm by showLocationConfirmState
    var showLocationRequest by showLocationRequestState
    var showPrivacyPanel by showPrivacyPanelState
    var showProfile by showProfileState
    var showRedPacketDialog by showRedPacketDialogState
    var showImageShareSheet by showImageShareSheetState
    var showTransferDialog by showTransferDialogState
    var videoHeroRect by videoHeroRectState
    var videoOverlaySize by videoOverlaySizeState
    var videoPlayUrl by videoPlayUrlState
    var videoSession by videoSessionState
    // 「Agent 功能已移至新软件」一次性提示：首次进入 AI 对话时弹一次
    var showAgentMovedDialog by remember { mutableStateOf(false) }
    LaunchedEffect(friendId) {
        if (friendId == AI_CHAT_ID && !AIUiPrefs.agentMovedDialogShown(ctx)) {
            showAgentMovedDialog = true
        }
    }

    // ═════════════════════════════════════════════════════
    // 隐私控制面板（从右侧滑入覆盖，显示双方隐私设置状态）
    // ═════════════════════════════════════════════════════
    if (friendId > 0) {
        AnimatedVisibility(
            visible = showPrivacyPanel,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
                // 点击背景关闭
                Box(
                    modifier = Modifier.fillMaxSize().clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showPrivacyPanel = false }
                )
                PrivacySettingsPanel(
                    friendId = friendId,
                    currentUserId = currentUserId,
                    onClose = { showPrivacyPanel = false; privacyRefreshKey++ }
                )
            }
        }
    }


    // ── 相机拍摄覆盖层 ──
    if (showCamera) {
        Box(Modifier.fillMaxSize().zIndex(100f)) {
            com.aurora.chat.ui.components.EventBlocker()
            CameraCaptureScreen(
                onDismiss = { showCamera = false },
                onPhotoCaptured = { file ->
                    showCamera = false
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", file
                    )
                    // 拍摄结果走与「相册发图」同一条上传队列：本地对话直接落盘；
                    // 普通 / 群聊若被拉黑 / 对方开启验证 / 非好友，由服务端在发送阶段拒绝，
                    // 失败气泡原地转红叹号并保留在队列（与正常发图一致），不发送前直接拦截。
                    val camName = file.name
                    val camMime = guessMediaMimeType(camName)
                    val camIsImage = camMime.startsWith("image/")
                    val camIsVideo = camMime.startsWith("video/")
                    val camExtra = if (camIsImage) "image" else if (camIsVideo) "video" else "file"
                    if (isLocalChat(friendId)) {
                        scope.launch {
                            sendCameraFile(uri, ctx, currentUserId, friendId, scope, messages, uploadProgressMap, listState) { newMessages -> messages.clear(); messages.addAll(newMessages) }
                        }
                    } else {
                        val localId = com.aurora.chat.data.upload.MediaUploadManager.enqueueFromUri(ctx, friendId, isGroupChat(friendId), uri, camMime, "", 0)
                        if (localId >= 0) {
                            val ph = ChatMsg.create(serverId = localId, text = "", isMine = true, fromUserId = currentUserId, isNew = true, mediaType = camExtra, mediaUrl = "", isUploading = true, flashDuration = 0, localPreviewUri = uri.toString())
                            messages.add(ph)
                            pendingUploadPlaceholders.add(ph)
                            uploadProgressMap[localId] = 0f
                            uploadFileNameMap[localId] = camName
                            uploadStageMap[localId] = "compressing"
                            if (camIsVideo) {
                                uploadRatioMap[localId] = com.aurora.chat.data.upload.MediaUploadManager.videoAspectRatio(localId)
                            }
                            scope.launch { if (messages.isNotEmpty()) listState.scrollToItem(0) }
                        } else {
                            com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, "无法读取文件")
                        }
                    }
                },
                onVideoCaptured = { file, _ ->
                    showCamera = false
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", file
                    )
                    // 拍摄结果走与「相册发图」同一条上传队列：本地对话直接落盘；
                    // 普通 / 群聊若被拉黑 / 对方开启验证 / 非好友，由服务端在发送阶段拒绝，
                    // 失败气泡原地转红叹号并保留在队列（与正常发图一致），不发送前直接拦截。
                    val camName = file.name
                    val camMime = guessMediaMimeType(camName)
                    val camIsImage = camMime.startsWith("image/")
                    val camIsVideo = camMime.startsWith("video/")
                    val camExtra = if (camIsImage) "image" else if (camIsVideo) "video" else "file"
                    if (isLocalChat(friendId)) {
                        scope.launch {
                            sendCameraFile(uri, ctx, currentUserId, friendId, scope, messages, uploadProgressMap, listState) { newMessages -> messages.clear(); messages.addAll(newMessages) }
                        }
                    } else {
                        val localId = com.aurora.chat.data.upload.MediaUploadManager.enqueueFromUri(ctx, friendId, isGroupChat(friendId), uri, camMime, "", 0)
                        if (localId >= 0) {
                            val ph = ChatMsg.create(serverId = localId, text = "", isMine = true, fromUserId = currentUserId, isNew = true, mediaType = camExtra, mediaUrl = "", isUploading = true, flashDuration = 0, localPreviewUri = uri.toString())
                            messages.add(ph)
                            pendingUploadPlaceholders.add(ph)
                            uploadProgressMap[localId] = 0f
                            uploadFileNameMap[localId] = camName
                            uploadStageMap[localId] = "compressing"
                            if (camIsVideo) {
                                uploadRatioMap[localId] = com.aurora.chat.data.upload.MediaUploadManager.videoAspectRatio(localId)
                            }
                            scope.launch { if (messages.isNotEmpty()) listState.scrollToItem(0) }
                        } else {
                            com.aurora.chat.data.api.AuroraApi.showErrorToast(ctx, "无法读取文件")
                        }
                    }
                }
            )
        }
    }

    // ── AI 对话列表抽屉 ──
    AiConversationListDrawer(
        visible = friendId == AI_CHAT_ID && showAiConvDrawer,
        onClose = { showAiConvDrawer = false },
        aiConvs = aiConvs,
        activeAiConvId = activeAiConvId,
        currentUserId = currentUserId,
        onNewConversation = { startNewAiConversation() },
        onOpenConversation = { id -> openAiConversation(id) },
        onDeleteConversation = { id -> deleteAiConv(id) },
        onRenameConversation = { id, title -> renameAiConv(id, title) },
        onBatchDeleteConversations = { ids -> deleteAiConvs(ids) }
    )

    // ── 转账（全屏页，自右滑入）──
    AnimatedVisibility(
        visible = showTransferDialog,
        enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(260)),
        exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(260)),
        modifier = Modifier.fillMaxSize()
    ) {
        TransferScreen(
            friendId = friendId,
            currentUserId = currentUserId,
            currentUserName = currentUserName,
            friendName = friendName,
            messages = messages,
            ctx = ctx,
            onDismiss = { showTransferDialog = false }
        )
    }
    BackHandler(enabled = showTransferDialog) { showTransferDialog = false }

    // ── 红包发送弹窗 ──
    if (showRedPacketDialog) {
        RedPacketSendDialog(
            friendId = friendId,
            currentUserId = currentUserId,
            currentUserName = currentUserName,
            friendName = friendName,
            messages = messages,
            ctx = ctx,
            onDismiss = { showRedPacketDialog = false }
        )
    }

    // ── 红包详情全屏 ──
    if (redPacketDetailId > 0) {
        RedPacketDetailDialog(
            packetId = redPacketDetailId,
            currentUserId = currentUserId,
            statusMap = redpacketStatus,
            onDismiss = { redPacketDetailId = 0 }
        )
    }

    // ── 位置权限与发送 ──
    val locationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        showLocationRequest = false
        if (granted) {
            scope.launch { sendLocationMessage(ctx, currentUserId, friendId) { newMessages -> messages.clear(); messages.addAll(newMessages) } }
        } else {
            // 权限被永久拒绝（勾选了"不再询问"），引导用户去设置页
            if (!ActivityCompat.shouldShowRequestPermissionRationale(ctx as android.app.Activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
                Toast.makeText(ctx, "位置权限已被拒绝，请在设置中手动开启", Toast.LENGTH_LONG).show()
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", ctx.packageName, null)
                    }
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
            } else {
                Toast.makeText(ctx, "需要位置权限才能分享位置", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(showLocationRequest) {
        if (showLocationRequest) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                showLocationRequest = false
                scope.launch { sendLocationMessage(ctx, currentUserId, friendId) { newMessages -> messages.clear(); messages.addAll(newMessages) } }
            } else {
                locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    // ── 位置发送确认弹窗 ──
    if (showLocationConfirm) {
        AlertDialog(
            onDismissRequest = { showLocationConfirm = false },
            title = { Text("发送当前位置", fontWeight = FontWeight.Bold) },
            text = { Text("确认将您当前所在位置以卡片形式发送给好友？") },
            confirmButton = {
                TextButton(onClick = {
                    showLocationConfirm = false
                    showLocationRequest = true
                }) {
                    Text("确认发送", color = Color(0xFF1E40AF))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationConfirm = false }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }

    // 图片全屏预览的返回拦截写在预览块内部（见下方 imagePreviewUrls.isNotEmpty() 分支）。
    // 原因：OnBackPressedDispatcher 是「后注册的优先」。这里的组件一直存在，注册得很早，
    // 而「查找聊天记录」页是预览之后才组合进来的，它的 BackHandler 反而更靠后 →
    // 看图时侧滑返回先把搜索页关掉（退到群聊信息），图还挂着。
    // 跟着预览一起注册，就能保证「预览是最先接返回的那一层」。

    // 被查看媒体对应的消息是否已被撤回（对齐 QQ：查看中被撤回应立即中断）。
    // 撤回仅把消息标记为 isRevoked（其媒体文件被清理），但媒体内容仍可能命中内存/磁盘缓存继续展示；
    // 服务端会话撤回后消息仍保留 mediaUrl、仅置 isRevoked 标记；本地/AI 会话撤回会直接移除该条消息。
    val isMediaRevoked: (String) -> Boolean = { url ->
        // 独立来源预览（如分享聊天全屏页）：调用方决定撤回判定，其快照内消息恒未撤回，避免误伤。
        // 注意不能用 return@isMediaRevoked —— lambda 被赋给变量时没有这个名字的标签，编译不过。
        val override = previewRevokeOverride
        when {
            override != null -> override(url)
            url.isBlank() -> false
            // 工作区(ai_files)内的文档是 AI 产出的文件,并非聊天的媒体附件:
            // 不适用"消息撤回"判定——否则打开方案原文(ai_files 下的 .md)会因为
            // 匹配不到任何消息 mediaUrl 而被误判为已撤回,弹出「该消息已被撤回」并强制关闭预览。
            url.contains("/ai_files/") -> false
            else -> {
                var present = false
                var revokedFlag = false
                for (m in messages) {
                    if (resolveMediaUrl(m.mediaUrl) == url) {
                        present = true
                        if (m.isRevoked == 1) { revokedFlag = true; break }
                    }
                }
                revokedFlag || (!present && isLocalChat(friendId))
            }
        }
    }

    // 视频播放中若被对方撤回：立即中断播放并提示
    LaunchedEffect(videoPlayUrl) {
        if (videoPlayUrl.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(300L)
            if (videoPlayUrl.isEmpty()) break
            if (isMediaRevoked(videoPlayUrl)) {
                videoPlayUrl = ""
                android.widget.Toast.makeText(ctx, "该消息已被撤回，已停止查看", android.widget.Toast.LENGTH_SHORT).show()
                break
            }
        }
    }

    // 文本类消息（分享聊天卡片等）是否已被撤回：口径与 isMediaRevoked 一致
    val isMessageRevoked: (Long) -> Boolean = { msgId ->
        if (msgId <= 0L) {
            false
        } else {
            var present = false
            var revokedFlag = false
            for (m in messages) {
                if (m.id == msgId) {
                    present = true
                    if (m.isRevoked == 1) { revokedFlag = true; break }
                }
            }
            revokedFlag || (!present && isLocalChat(friendId))
        }
    }

    // 文件预览 / 文件下载确认弹窗打开时若被撤回：立即关闭并提示（对齐图片、视频）
    LaunchedEffect(filePreviewVisible, filePreviewTarget, fileDownloadConfirm) {
        val url = if (filePreviewVisible) filePreviewTarget?.first else fileDownloadConfirm?.first
        if (url.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(300L)
            if (isMediaRevoked(url)) {
                // 与 onClose 一致：只收起可见性，保留 target 给退出动画用
                filePreviewVisible = false
                fileDownloadConfirm = null
                android.widget.Toast.makeText(ctx, "该消息已被撤回，已停止查看", android.widget.Toast.LENGTH_SHORT).show()
                break
            }
        }
    }

    // 已下载的音频文件（在文件卡片里点播）若被撤回：立即停止播放，避免撤回后仍在响
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500L)
            val playing = com.aurora.chat.ui.chat.ChatAudioPlayer.currentPlayingPath() ?: continue
            val stopping = messages.any { m ->
                m.isRevoked == 1 && m.mediaUrl.isNotEmpty() &&
                    java.io.File(
                        ctx.filesDir,
                        "downloads/" + resolveMediaUrl(m.mediaUrl).substringAfterLast('/').substringBefore('?')
                    ).absolutePath == playing
            }
            if (stopping) com.aurora.chat.ui.chat.ChatAudioPlayer.stopPlaying()
        }
    }

    // 「分享聊天」全屏查看中若该消息被撤回：立即关闭并提示
    LaunchedEffect(chatShareViewMsgId) {
        if (chatShareViewMsgId <= 0L) return@LaunchedEffect
        val targetId = chatShareViewMsgId
        while (true) {
            kotlinx.coroutines.delay(300L)
            if (isMessageRevoked(targetId)) {
                chatShareViewText = ""
                chatShareViewMsgId = 0L
                android.widget.Toast.makeText(ctx, "该消息已被撤回，已停止查看", android.widget.Toast.LENGTH_SHORT).show()
                break
            }
        }
    }

    // 入群邀请应答全屏页：收到"邀请入群"卡片时打开，同意/拒绝后关闭
    val currentInvite = inviteRespond
    if (currentInvite != null) {
        BackHandler(enabled = true) { if (inviteRespond != null) inviteRespond = null }
        GroupInviteRespondPage(
            invite = currentInvite,
            onDismiss = { inviteRespond = null },
            onResponded = {
                inviteRespond = null
            }
        )
    }

    // 「分享聊天」卡片 → 全屏查看页：完整消息 + 图片，图片点击复用下方已有的可缩放图片预览。
    // 必须放在图片预览层之前，保证预览层绘制在其之上。
    // 从右滑入/滑出（与资料面板、查找聊天记录等全屏页同款动画）。
    // 退出动画期间 text 已清空，用缓存的最后非空文本兜底，避免内容先闪成空白再滑走。
    val shareTextHolder = remember { mutableStateOf("") }
    if (chatShareViewText.isNotEmpty()) shareTextHolder.value = chatShareViewText
    AnimatedVisibility(
        visible = chatShareViewText.isNotEmpty(),
        enter = slideInHorizontally(tween(300)) { it } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(300)) { it } + fadeOut(tween(200)),
        modifier = Modifier.fillMaxSize()
    ) {
        // 返回由 ChatShareFullScreen 内部的层级栈统一处理（逐层出栈，栈底才关闭全屏）
        ChatShareFullScreen(
            text = shareTextHolder.value,
            friendId = friendId,
            // 图片预览层打开时本全屏页不抢返回：返回键/手势优先关闭图片预览（见 ShareCard.kt backEnabled）
            backEnabled = imagePreviewUrls.isEmpty(),
            // 全屏看图隐藏系统栏期间冻结状态栏高度，避免退出时顶栏「抽搐」（与普通聊天顶栏一致）
            freezeStatusBar = imagePreviewUrls.isNotEmpty(),
            onBack = {
                chatShareViewText = ""
                chatShareViewMsgId = 0L
            },
            onImageClick = { urls, index ->
                // 分享聊天卡片是独立快照，其图片不应被真实会话里「同 URL 原消息已撤回」误判为已撤回
                previewRevokeOverride = { false }
                imagePreviewUrls = urls
                imagePreviewIndex = index.coerceAtLeast(0)
                heroInitialIndex = imagePreviewIndex
                // 分享卡片若登记过缩略图矩形就走 hero，否则为 null → 自动退化为淡入淡出
                heroSourceRect = imageBounds.get(urls.getOrNull(index) ?: "")
                heroEnterDone = false
                previewReady = false
                previewSession++
            },
            // 卡片内的嵌套卡片可继续逐层点进去
            onUserClick = { uid ->
                profileUserId = uid
                profileUserName = ""
                showProfile = true
            },
            onGroupClick = onOpenGroupDetail,
            onPostClick = onOpenPostDetail,
            // 卡片内嵌套的「分享短视频」缩略图 → 切入短视频模块并定位
            onShortVideoClick = onOpenShortVideo,
            // 卡片内的视频缩略图 → 应用内全屏播放器（与对话内视频同一播放内核）
            onVideoClick = { url -> openVideoPlayer(url) },
            // 缩略图屏幕矩形上报：让全屏图片预览的 hero 放大动画从卡片内原位置起步
            onBoundsMeasured = { url, rect -> imageBounds.put(url, rect) },
            // 嵌套卡片是内嵌快照（属于别的会话），没有可校验的撤回态，故 id 置 0。
            // 「聊天记录套聊天记录」的逐层进入/返回由 ChatShareFullScreen 内部层级栈接管，
            // 这里绝不能替换 chatShareViewText，否则 remember(text) 会重建栈、丢失层级。
            onChatClick = { _ -> chatShareViewMsgId = 0L }
        )
    }

    // 图片全屏预览（在 Column 外部，覆盖整个屏幕包括顶部栏）。
    // - 支持多图：点击某张后从该张起，左右滑动查看对话内全部图片（HorizontalPager）。
    // - 性能：Pager 只对当前可见页（及相邻页）创建内容，配合 AsyncImage 按需懒加载，不一次性加载全部图片。
    // - 交互：共享元素(hero)动画——打开时从缩略图原位置放大到全屏、关闭时缩回原位；
    //   无源矩形（如分享卡片）则退化为淡入淡出。全程只用 graphicsLayer 变换（GPU），不触发布局，保证顺滑。
    if (imagePreviewUrls.isNotEmpty()) {
        // 返回键/侧滑返回：只关预览。注册在这里（而不是外层常驻），
        // 才能保证比「查找聊天记录」等后打开的全屏页更晚注册 → 优先接住返回。
        BackHandler(enabled = true) { imagePreviewCloser() }
        // ── hero 进入/退出动画所需状态与计算 ──
        val initialUrl = imagePreviewUrls.getOrNull(heroInitialIndex) ?: imagePreviewUrls.firstOrNull() ?: ""
        // ⚠️ 起跑点必须是**点击瞬间冻结**的矩形，不能读实时 imageBounds：
        // 打开预览会隐藏系统栏 → 聊天页 inset 变化 → 列表位置重排 → onGloballyPositioned 上报新矩形，
        // 动画中途重算 heroTransform 就会"跳帧/跳位"（快速点进点出时尤其明显）。
        val sourceRect = heroSourceRect
        val heroActive = sourceRect != null
        // ⚠️ 起跑尺寸直接用**屏幕像素**，不等 overlay 的 onSizeChanged。
        // 等测量的话链路是：组合 → 布局 → onSizeChanged 回传 → 下一帧重组 → heroTransform 才有值，
        // 动画天生晚 2~3 帧（首帧还要组合 Pager/大图，低端机上就是 100ms+），
        // 用户看到的就是「点下去图片先不动，过一会儿才开始放大」。
        // overlay 是 edge-to-edge 的全屏 Box，尺寸与屏幕像素一致，首帧就能算出起点 → 动画立刻起步。
        val screenPx = remember {
            val dm = ctx.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
        val heroTransform = remember(sourceRect, screenPx) {
            if (sourceRect == null || screenPx.first <= 0 || screenPx.second <= 0) null
            else {
                val sw = sourceRect.width; val sh = sourceRect.height
                val aspect = sw / sh
                val screenW = screenPx.first.toFloat(); val screenH = screenPx.second.toFloat()
                val (fitW, fitH) = if (screenW / screenH > aspect) (screenH * aspect) to screenH else screenW to (screenW / aspect)
                val fitCx = screenW / 2f; val fitCy = screenH / 2f
                val srcCx = sourceRect.left + sw / 2f; val srcCy = sourceRect.top + sh / 2f
                HeroTransform(
                    scale0 = (sw / fitW).coerceAtLeast(0.01f),
                    tx0 = srcCx - fitCx,
                    ty0 = srcCy - fitCy
                )
            }
        }
        // 进入动画：每次打开只起跑一次。heroTransform 现在首帧就有（用屏幕像素算，不等测量），
        // 所以动画在组合后的第一个 after-commit 就起步，点下去立刻动，不再有"先杵一下"的空窗。
        var heroStarted by remember(previewSession) { mutableStateOf(false) }
        LaunchedEffect(previewSession, heroTransform, heroActive) {
            if (heroStarted) return@LaunchedEffect
            if (heroActive && heroTransform == null) return@LaunchedEffect
            heroStarted = true
            heroReopening = false
            heroClosing = false
            previewReady = false
            heroEnterDone = false
            heroProgress.snapTo(0f)
            // 200ms：再短会显得"怼脸"，再长就开始拖。FastOutSlowIn 起步快、收尾稳，
            // 从缩略图起放大最像手感的组合。
            heroProgress.animateTo(1f, tween(if (heroActive) 200 else 160, easing = FastOutSlowInEasing))
            // 原图没就绪前不交棒（否则放大到位是一片糊的缩略图，像卡住了）。
            // 但只等 120ms：超时先交棒，绝不能"放大完就定住不动"——那是肉眼可见的卡顿感。
            kotlinx.coroutines.withTimeoutOrNull(120L) {
                while (!previewReady) kotlinx.coroutines.delay(16L)
            }
            heroEnterDone = true
        }
        // 沉浸式全屏统一走 ImmersiveSystemBars（内部 BEHAVIOR_DEFAULT）：
        // 以前这里是 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE，系统会把「从边缘往里滑」优先解释成
        // 「先把隐藏的系统栏临时露出来」→ 手势导航的返回被吃掉一次，用户看到的就是
        // 「左滑一下只冒出一条状态栏，得再滑一下才退出」。默认行为在开启手势导航时
        // 系统手势不受系统栏可见性影响，一次侧滑直接返回（微信/QQ 全屏看图就是这个手感）。
        // 注意不要换成随列表重建而变化的 key：重复 hide→show→hide 会让状态栏闪一下。
        com.aurora.chat.ui.components.ImmersiveSystemBars()
        val pagerState = rememberPagerState(
            initialPage = heroInitialIndex.coerceIn(0, (imagePreviewUrls.size - 1).coerceAtLeast(0)),
            pageCount = { imagePreviewUrls.size }
        )
        // hero 生效条件：有源矩形 且 当前停在起始图（滑到别的图后关闭走淡出，不回缩）。
        // 注意用 heroActive（sourceRect != null）而非 heroTransform——后者首帧 overlaySize 未测量为 null，
        // 若用它判定会让 Pager 在第一帧露出全屏图（打开瞬间闪一下）。
        val heroOnStart = heroActive && pagerState.currentPage == heroInitialIndex
        // 入场动画结束就交棒给 Pager。previewReady 只用于动画期间「略等原图加载」，
        // 不能让它无限期压住 Pager：重复打开同一张图时 Coil 复用缓存可能不再回调 onSuccess，
        // 若仍要求 previewReady，Pager 会一直是 alpha=0，hero 层静帧盖在上面 →
        // 表现为「没有动画、双指缩放完全失效」。
        val heroVisible = heroOnStart && !heroEnterDone
        // Pager 内容层透明度：hero 显示期间一律让位（alpha=0），稳态才显示。
        // ⚠️ 写成函数、且只在 graphicsLayer 里调用：若在组合期把 exitFade.value 读成 val，
        // 淡出关闭的每一帧都会重组整个预览层（含 Pager），低端机上就是明显的掉帧。
        fun currentPagerAlpha(): Float = when {
            heroClosing && heroOnStart -> 0f     // hero 缩回中：让位
            heroVisible -> 0f                    // hero 进入中/未就绪：让位
            heroClosing -> exitFade.value        // 淡出关闭（滑到过别的图）：内容层随黑底渐隐
            else -> 1f
        }
        // 退出：能 hero 退出的（有源矩形且停在原图）则缩回原位；否则整体淡出。
        // allowReopen：只有"再点一下图片"才允许撤销关闭；点背景/返回键要让它关完，不能把人拽回来。
        fun requestClose(allowReopen: Boolean = true) {
            if (heroClosing) {
                // 关闭动画还没播完又点了一下：撤销关闭、原路回弹。
                // 以前这里是直接 return —— 这 220ms 里的点击会被吞掉，快速点进点出就会"点一下没反应"。
                if (!allowReopen || !heroOnStart) return
                heroClosing = false
                heroEnterDone = false
                heroReopening = true
                scope.launch {
                    // 新动画会取消上面那条 animateTo(0f)，关闭协程不会再把列表清空。
                    // 140ms：只求"撤回到一半就把手收回来"的利落，别磨。
                    heroProgress.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
                    heroReopening = false
                    heroEnterDone = true
                }
                return
            }
            // 同步翻转 heroClosing/heroEnterDone：同一帧内 Pager 让位、hero 层接上，杜绝中间黑帧
            heroReopening = false
            heroClosing = true
            heroEnterDone = false
            scope.launch {
                if (heroOnStart) {
                    // 180ms + FastOutLinearIn：起步干脆、匀速到达、没有收尾减速。
                    // 关闭比打开更快才对味——拖长的"缩回"看着是在拖泥带水。
                    heroProgress.animateTo(0f, tween(180, easing = FastOutLinearInEasing))
                } else {
                    exitFade.animateTo(0f, tween(140))
                }
                heroClosing = false
                imagePreviewUrls = emptyList()
                // 预览关闭后恢复真实会话的撤回判定（分享聊天来源设置的 override 失效）
                previewRevokeOverride = null
            }
        }
        // 对外暴露关闭动作（返回键/外部调用都走带 hero 退出的关闭）
        DisposableEffect(Unit) {
            // 返回键/外部关闭：不允许回弹（否则关到一半按返回会被"撤销关闭"）
            imagePreviewCloser = { requestClose(allowReopen = false) }
            onDispose { imagePreviewCloser = { imagePreviewUrls = emptyList() } }
        }
        // 滑动时同步当前索引，供底部指示器与"保存当前图"使用
        LaunchedEffect(pagerState.currentPage) {
            imagePreviewIndex = pagerState.currentPage
        }
        // 查看过程中若某张图片被对方撤回：从预览列表剔除（避免滑到该张时瞬间显示旧图造成闪烁），
        // 若正在查看的这张被撤回则立即关闭预览并提示（对齐 QQ 行为）。
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(300L)
                if (imagePreviewUrls.isEmpty()) break
                val cur = imagePreviewUrls.getOrNull(pagerState.currentPage)
                if (cur != null && isMediaRevoked(cur)) {
                    imagePreviewUrls = emptyList()
                    android.widget.Toast.makeText(ctx, "该消息已被撤回，已停止查看", android.widget.Toast.LENGTH_SHORT).show()
                    break
                }
                // 先无分配探测，确有撤回才重建列表（避免每 300ms 分配新 list 造成 GC 抖动）
                if (imagePreviewUrls.isNotEmpty() && imagePreviewUrls.any { isMediaRevoked(it) }) {
                    val filtered = imagePreviewUrls.filterNot { isMediaRevoked(it) }
                    val target = filtered.indexOf(cur).coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
                    imagePreviewUrls = filtered
                    if (filtered.isNotEmpty()) pagerState.scrollToPage(target)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { requestClose(allowReopen = false) },   // 点背景：让它关完，不回弹
            contentAlignment = Alignment.Center
        ) {
            // 黑底独立成层：径向渐变晕影（中心浅 → 边缘深）+ 透明度单独控制。
            // 不要 100% 纯色死黑——那是一刀砍死的黑、和图片/聊天界面硬切割；
            // 渐变阴影与内容衔接柔和，打开时阴影从图片四周晕开、退出时同步溶解。
            // hero 图/Pager 内容恒不透明，看到的始终是缩略图平滑放大/缩回。
            // 背景统一纯黑：之前用的径向渐变晕影在「图片没撑满全屏」时，
            // 上下两端会露出明显的灰黑层次（中间浅、边缘深），看起来像图片外挂了一圈阴影，
            // 与图片严重割裂。图库类应用（微信/QQ/相册）一律纯黑底，让画面成为唯一焦点。
            Box(
                Modifier
                    .fillMaxSize()
                    // ⚠️ 顺序很重要：graphicsLayer 必须在 background **之前**。
                    // 修饰符链从左到右是"外 → 内"，alpha 只作用于它内部的绘制节点；
                    // 若写成 .background().graphicsLayer{alpha}，黑底画在图层外面，透明度根本不生效。
                    .graphicsLayer {
                        val p = heroProgress.value
                        alpha = when {
                            // 回弹：沿用关闭时的线性曲线，避免点击瞬间阴影从 p 跳到 p²（会闪一下）
                            heroReopening -> p
                            // hero 缩回：黑底随缩回同步溶解（p:1→0），落地即全透明，无残留
                            heroClosing && heroOnStart -> p
                            // 淡出关闭（无源矩形 / 已滑到别的图）：黑底与内容一起渐隐
                            heroClosing -> exitFade.value
                            // 打开：黑底与放大**同步**走（线性）
                            else -> p
                        }
                    }
                    .background(Color.Black)
            )
            // Pager 全程保持组合（打开即挂载、关闭不卸载），仅用 alpha 让位给 hero 层：
            // 快速点进点出时不产生整棵子树重组/销毁，帧连续性不受打断（丝滑的关键）。
            // 打开期间(alpha=0)原图就在后台加载，进入动画播完即可无缝交棒。
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = heroEnterDone && !heroClosing,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = currentPagerAlpha() }
            ) { page ->
                val url = imagePreviewUrls.getOrNull(page) ?: return@HorizontalPager
                // 每页独立的缩放/位移状态（按「打开会话 + url」记忆）：
                // 必须带上 previewSession —— 关闭动画未播完又点开时组合不会销毁，
                // 只按 url 记忆会把上一次自动放大的 scale/offset 带进新一次打开，
                // 表现为「后续打开没有动画、也缩放不动」。
                var scale by remember(previewSession, url) { mutableFloatStateOf(1f) }
                var offset by remember(previewSession, url) { mutableStateOf(Offset.Zero) }
                var previewViewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                // 图片真实长宽比（宽/高）。0 = 尚未加载出来，退回按整屏比例估算。
                // 用途：放大后按图片实际占据的矩形算平移边界，否则宽图/长图会被允许拖出大片空白。
                var aspect by remember(previewSession, url) { mutableFloatStateOf(0f) }
                // 预览图分级回退：1=1080 缩略图（气泡同款，必能加载）→ 2=原图。
                // 默认直接从 1080 起，避免先试 2048 大图时偶发挂起/过慢而长时间卡「加载中」
                // （气泡里用的就是 1080，已验证可加载且 Coil 多半已缓存，点开即显）；
                // 仅当 1080 也失败才回退到原图。本地文件/动图不回退（原样）。
                var previewStage by remember(previewSession, url) { mutableIntStateOf(1) }
                var autoZoomDone by remember(previewSession, url) { mutableStateOf(false) }
                // 长图打开后会自动放大到顶部（上面的 auto-zoom），那是「系统给的初始视角」，
                // 不算用户自己缩放。点一下必须直接退出；只有用户亲手双指/双击放大过，
                // 才保留「先复位再关闭」这一步（避免误触把放大中的图一下关掉）。
                var userZoomed by remember(previewSession, url) { mutableStateOf(false) }
                // 上一次「点按」的时间与位置，用于区分单击与双击（双击缩放优先）
                var lastTapTime by remember(previewSession, url) { mutableStateOf(0L) }
                var lastTapPos by remember(previewSession, url) { mutableStateOf(Offset.Zero) }
                val pageScope = rememberCoroutineScope()
                // 长图（宽高比 < 0.66，手机截图这类）：打开动画结束后自动放大到「铺满屏幕宽度」，
                // 并从顶部开始看，省掉手动双指缩放。横图/方图 Fit 已铺满，不触发；
                // 用户已开始缩放或正在关闭时不打扰。
                LaunchedEffect(previewSession, url, aspect, previewViewport, pagerState.currentPage, heroEnterDone) {
                    // 一次打开只跑一次：autoZoomDone 防重入，避免协程重启时把用户的手动缩放又拉回去
                    if (autoZoomDone || !heroEnterDone || heroClosing) return@LaunchedEffect
                    if (pagerState.currentPage != page) return@LaunchedEffect
                    if (aspect <= 0f || aspect >= 0.66f) return@LaunchedEffect
                    val vw = previewViewport.width.toFloat()
                    val vh = previewViewport.height.toFloat()
                    if (vw <= 0f || vh <= 0f) return@LaunchedEffect
                    val fitW = kotlin.math.min(vw, vh * aspect)
                    val target = (vw / fitW).coerceIn(1f, 3f)
                    // 只从「原始大小」起跑；用户已经缩放过就不抢
                    if (target < 1.1f || scale != 1f) return@LaunchedEffect
                    delay(280)
                    if (pagerState.currentPage != page || scale != 1f || heroClosing) return@LaunchedEffect
                    autoZoomDone = true
                    androidx.compose.animation.core.animate(
                        initialValue = 1f,
                        targetValue = target,
                        animationSpec = tween(420, easing = FastOutSlowInEasing)
                    ) { v, _ ->
                        scale = v
                        // 顶部锚定：长图按高度 Fit，向下平移让图片顶边始终贴住屏幕顶
                        offset = Offset(0f, vh * (v - 1f) / 2f)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { previewViewport = it }
                        .pointerInput(url) {
                            // 当前缩放倍数下，图片向左右/上下各能移动多少像素（0 = 该方向已铺满屏幕，无需平移）
                            fun limits(s: Float): Pair<Float, Float> {
                                val vw = size.width.toFloat().coerceAtLeast(1f)
                                val vh = size.height.toFloat().coerceAtLeast(1f)
                                val a = if (aspect > 0f) aspect else (vw / vh)
                                val fitW = kotlin.math.min(vw, vh * a)
                                val fitH = kotlin.math.min(vh, vw / a)
                                return ((fitW * s - vw) / 2f).coerceAtLeast(0f) to
                                    ((fitH * s - vh) / 2f).coerceAtLeast(0f)
                            }
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val downPos = firstDown.position
                                val downTime = System.currentTimeMillis()
                                var prevDistance = 0f
                                var prevPos: Offset? = null
                                var handedOff = false   // 已把后续手势交给 Pager 翻页
                                var moved = false      // 本次手势是否发生过移动（区分「点按」与「滑动/缩放」）
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val changes = event.changes
                                    if (changes.isEmpty()) break
                                    if (changes.size >= 2) {
                                        moved = true
                                        // ── 双指缩放：以双指中心为锚点（缩放前后中心下的像素保持不动）──
                                        val dist = (changes[0].position - changes[1].position).getDistance()
                                        val centroid = (changes[0].position + changes[1].position) / 2f
                                        if (prevDistance > 0f && dist > 0f) {
                                            val old = scale
                                            val ns = (old * (dist / prevDistance)).coerceIn(1f, 5f)
                                            if (ns != old) {
                                                val c = Offset(size.width / 2f, size.height / 2f)
                                                val k = ns / old
                                                val shifted = (centroid - c) - (centroid - c - offset) * k
                                                val (mx, my) = limits(ns)
                                                scale = ns
                                                if (ns > 1.001f) userZoomed = true
                                                offset = if (ns <= 1.001f) Offset.Zero else Offset(
                                                    shifted.x.coerceIn(-mx, mx),
                                                    shifted.y.coerceIn(-my, my)
                                                )
                                            }
                                        }
                                        prevDistance = dist
                                        prevPos = null
                                        handedOff = false
                                        changes.forEach { it.consume() }
                                    } else if (changes.size == 1) {
                                        val ch = changes[0]
                                        if (scale > 1.001f && ch.pressed && !handedOff) {
                                            // ── 已放大：单指拖拽平移图片 ──
                                            val cur = ch.position
                                            val last = prevPos
                                            if (last != null) {
                                                val pan = cur - last
                                                if (pan.getDistance() > 0.3f) {
                                                    moved = true
                                                    val (mx, my) = limits(scale)
                                                    val nx = offset.x + pan.x
                                                    val ny = offset.y + pan.y
                                                    // 已经在水平边界还继续往外推 → 把这一小段让给 Pager 翻页，
                                                    // 之后整个手势都不再抢回（否则会和 Pager 来回拉扯）
                                                    if (mx > 0f && abs(pan.x) > abs(pan.y) &&
                                                        (nx > mx || nx < -mx)
                                                    ) {
                                                        handedOff = true
                                                        moved = true
                                                        prevPos = null
                                                    } else {
                                                        offset = Offset(nx.coerceIn(-mx, mx), ny.coerceIn(-my, my))
                                                        prevPos = cur
                                                        ch.consume()
                                                    }
                                                } else {
                                                    // 位移小于抖动阈值：不消费，让点击/双击能正常命中
                                                    prevPos = cur
                                                }
                                            } else {
                                                prevPos = cur
                                            }
                                    } else {
                                        // 未放大：不消费单指事件，交给 HorizontalPager 左右翻页；
                                        // 但仍记录位移，用于区分「单击关闭」与「横向滑动翻页」（滑动不算点按）
                                        val last = prevPos
                                        if (last != null && (ch.position - last).getDistance() > 8f) moved = true
                                        prevPos = ch.position
                                    }
                                        prevDistance = 0f
                                    }
                                    if (changes.all { !it.pressed }) break
                                }
                                // ── 抬手吸附：双指外扩很难精确回到 1.000（常停在 1.02~1.08）──
                                // 不吸附的话，Pager 会一直收到"被消费"的拖动而无法翻页，
                                // 点击关闭（scale<=1.001 判定）也会失效 —— 就是"放大缩小后全都不好使"的根因。
                                if (scale < 1.08f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val (mx, my) = limits(scale)
                                    offset = Offset(offset.x.coerceIn(-mx, mx), offset.y.coerceIn(-my, my))
                                }
                                // ── 单击/双击判定 ──
                                // 单击「取消」立即生效（不再走 combinedClickable 的双击检测窗口），
                                // 因此单击关闭的起点速度与单击打开一致。紧接着再点一下（双击）时，
                                // 用 requestClose 的 allowReopen 逻辑撤销刚才的关闭，再切换缩放，
                                // 这样既保留双击放大、又不牺牲单击关闭的即时性。
                                val nowMs = System.currentTimeMillis()
                                if (!moved && (nowMs - downTime) < 250L) {
                                    val isDouble = (nowMs - lastTapTime) < 300L &&
                                        (lastTapPos - downPos).getDistance() < 48f
                                    if (isDouble) {
                                        lastTapTime = 0L
                                        // 撤销刚刚可能开始的关闭（双击缩放优先），再切换缩放
                                        if (heroClosing) requestClose()   // 默认 allowReopen=true 且 heroOnStart → 取消关闭
                                        val target = if (scale > 1.001f) 1f else 2.5f
                                        userZoomed = target > 1.001f
                                        pageScope.launch {
                                            androidx.compose.animation.core.animate(
                                                initialValue = scale,
                                                targetValue = target,
                                                animationSpec = tween(220)
                                            ) { v, _ ->
                                                scale = v
                                                if (v <= 1.001f) offset = Offset.Zero
                                            }
                                        }
                                    } else {
                                        lastTapTime = nowMs
                                        lastTapPos = downPos
                                        // 用户自己双指放大过 → 先复位；否则直接关闭（长图自动放大视角点一下也直接关）
                                        if (scale > 1.001f && userZoomed) {
                                            scale = 1f
                                            offset = Offset.Zero
                                            userZoomed = false
                                        } else {
                                            if (scale != 1f) {
                                                scale = 1f
                                                offset = Offset.Zero
                                            }
                                            requestClose()
                                        }
                                    }
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // 全屏预览动图（GIF / 动图 WebP）同样要显式挂 ImageDecoderDecoder（API 28+），
                    // 否则默认管线只解首帧，点开大图也是一张静止图（与气泡 ChatMediaImage 同一套处理）。
                    var previewLoading by remember(previewSession, url) { mutableStateOf(true) }
                    var previewFailed by remember(previewSession, url) { mutableStateOf(false) }
                    // 加载/失败状态完全交给 Coil 的 onLoading / onSuccess / onError 维护。
                    // 之前这里有个无条件 previewLoading = true 的 LaunchedEffect：命中缓存时 Coil 在
                    // 组合期就回调 onSuccess 把 loading 置 false，effect 随后又覆盖回 true，之后再也没有
                    // success 回调 → 图片明明已显示，屏幕中央却永远卡着「加载中」。
                    val previewCtx = androidx.compose.ui.platform.LocalContext.current
                    val previewUrl = remember(url, previewStage) {
                        when (previewStage) {
                            1 -> com.aurora.chat.ui.chat.media.chatMediaThumbUrl(url)
                            2 -> url
                            else -> com.aurora.chat.ui.chat.media.chatMediaPreviewUrl(url)
                        }
                    }
                    val previewModel = remember(url, previewStage) {
                        // 默认走「长边 2048」的服务端压缩版；失败（onError）自动降到 1080 缩略图 / 原图。
                        val b = ImageRequest.Builder(previewCtx).data(previewUrl)
                        val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
                        if ((ext == "gif" || ext == "webp") && android.os.Build.VERSION.SDK_INT >= 28) {
                            b.decoderFactory(coil.decode.ImageDecoderDecoder.Factory())
                        }
                        b.build()
                    }
                    // 纯黑：以前这里是「深灰底 0xFF111418」，在低亮度下会发闷、看着像脏褐色，
                    // 表现为「图片放大到全屏后背景从黑变成一层褐色」。加载中/失败有文字提示，不需要靠底色区分。
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        coil.compose.AsyncImage(
                            model = previewModel,
                            contentDescription = "图片预览",
                            modifier = Modifier
                                .fillMaxSize()
                                .combinedClickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    // 单击退出由外层 pointerInput 即时处理（不再经过双击检测窗口，
                                    // 所以单击关闭的起点速度与单击打开一致）；这里只保留长按呼出「分享至」抽屉。
                                    // onClick 置空以满足签名要求，实际关闭逻辑在 pointerInput 的单击判定里。
                                    onClick = { },
                                    onLongClick = { showImageShareSheet = true }
                                ),
                            contentScale = ContentScale.Fit,
                            onLoading = {
                                // 由 Coil 的真实加载状态驱动：进入加载态（含分级回退换图）时置 true，
                                // 命中缓存直接 Success 时不会误把它留在 true（见上方说明）。
                                previewLoading = true
                                previewFailed = false
                            },
                            onSuccess = { res ->
                                previewLoading = false
                                previewFailed = false
                                if (aspect <= 0f) {
                                    val d = res.result.drawable
                                    val w = d.intrinsicWidth
                                    val h = d.intrinsicHeight
                                    if (w > 0 && h > 0) aspect = w.toFloat() / h.toFloat()
                                }
                                if (page == heroInitialIndex) previewReady = true
                            },
                            onError = {
                                if (previewStage < 2) {
                                    // 当前一级失败：降到下一级（2048 → 1080 → 原图）继续加载，
                                    // 不再让黑洞露出来；只有原图也失败才判定彻底加载失败。
                                    previewStage++
                                } else {
                                    previewLoading = false
                                    previewFailed = true
                                    if (page == heroInitialIndex) previewReady = true
                                }
                            }
                        )
                        // 放大后的高清层：预览图只保证「瞬间可见」，一旦捏合/自动放大到能看出像素的倍数，
                        // 就从原图按当前可见区域 1:1 解码一块叠上去（微信/QQ 的同款做法）。
                        // 预览图按「整图塞进屏幕」算尺寸，长截图压完只有一两百像素宽，放大只能拉伸；
                        // 这一层不改任何手势，只跟随外层同一个 scale/offset。
                        com.aurora.chat.ui.chat.media.RegionZoomOverlay(
                            url = url,
                            aspect = aspect,
                            scale = scale,
                            offset = offset,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 加载/失败提示：套一层「反向」graphicsLayer，抵消外层图片的 scale/offset。
                        // 这样提示文字始终固定在屏幕中心、保持原始字号，不会随图片一起被放大。
                        if (previewLoading || previewFailed) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val inv = if (scale > 0.01f) 1f / scale else 1f
                                        scaleX = inv
                                        scaleY = inv
                                        translationX = -offset.x * inv
                                        translationY = -offset.y * inv
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (previewFailed) "图片加载失败" else "加载中…",
                                    fontSize = 14.sp,
                                    color = com.aurora.chat.ui.theme.appTextHint()
                                )
                            }
                        }
                    }
                }
            }

            // 多图底部指示器（与 Pager 同 alpha，让位/淡出一致）。
            // 原来「每张图一个圆点」在对话里图片很多时会横向铺满整屏、密密麻麻（还会组合出上百个 Box）。
            // 因此：≤7 张沿用圆点；超过 7 张改成「当前第 n / 共 m 张」计数，
            // 既不会密，也不会像固定几个点那样误导成「只有这几张图」。
            if (imagePreviewUrls.size > 1) {
                val totalPages = imagePreviewUrls.size
                val shownPage = pagerState.currentPage.coerceIn(0, totalPages - 1)
                if (totalPages <= 7) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .graphicsLayer { alpha = currentPagerAlpha() },
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        repeat(totalPages) { i ->
                            Box(
                                Modifier.size(if (shownPage == i) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(if (shownPage == i) Color.White else Color.White.copy(alpha = 0.4f))
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 30.dp)
                            .graphicsLayer { alpha = currentPagerAlpha() }
                            .clip(RoundedCornerShape(50.dp))
                            .background(Color(0x66000000))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${shownPage + 1} / $totalPages",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                }
            }
            // hero 层常驻组合（与 Pager 对称）：只用 alpha 控制显隐，不随开关增删组件。
            // 此前用 if(heroVisible) 增删 AsyncImage——每次打开/关闭都重新组合+取图+建 layer，
            // 第一次必付冷启动开销（组合→Coil 拉取→建 RenderLayer），这就是
            // 「第一次卡、多来几次就不卡」的根因。常驻后：打开前缩略图已在加载，
            // 关闭归位复用现成组件与纹理，零重建、零解码。
            // 恒不透明 + 纯 transform（无 clip/shape）：缩放 clip 会每帧重算路径、重绘全屏 layer，
            // 是动画卡顿的主因；圆角差异在快速过渡中不可感知，直接省略。
            // hero 层同理：GIF/动图 WebP 用原图 + ImageDecoderDecoder，避免过渡期只显示首帧
            val heroCtx = androidx.compose.ui.platform.LocalContext.current
            val heroModel = remember(initialUrl) {
                val u = chatMediaThumbUrl(initialUrl)
                val b = ImageRequest.Builder(heroCtx).data(u)
                val ext = u.substringAfterLast('.').substringBefore('?').lowercase()
                if ((ext == "gif" || ext == "webp") && android.os.Build.VERSION.SDK_INT >= 28) {
                    b.decoderFactory(coil.decode.ImageDecoderDecoder.Factory())
                }
                b.build()
            }
            coil.compose.AsyncImage(
                model = heroModel,
                contentDescription = "图片预览",
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val t = heroTransform
                    if (t == null || !heroVisible) {
                        // 未测量/稳态：完全让位（alpha=0，不画 transform）
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val p = heroProgress.value
                    val s = lerp(t.scale0, 1f, p)
                    val tx = lerp(t.tx0, 0f, p)
                    val ty = lerp(t.ty0, 0f, p)
                    alpha = 1f
                    scaleX = s
                    scaleY = s
                    translationX = tx
                    translationY = ty
                },
                contentScale = ContentScale.Fit
            )
        }
    }

    // 图片长按 → 「分享至」抽屉。
    // 原先这里是一个「是否保存图片」的确认弹窗，保存只是其中一个动作，长按先被弹窗拦一道；
    // 现在长按直接划出抽屉，保存/收藏/定位/发到外部应用都在里面一次选完。
    if (showImageShareSheet) {
        val shareImageUrl = imagePreviewUrls.getOrNull(imagePreviewIndex) ?: ""
        // 从「查找聊天记录」九宫格点开的是历史图片，可能不在当前消息窗口里；
        // 先查窗口，再查搜索页登记的那批引用，抽屉里的「收藏 / 定位聊天」才不会失效。
        fun findRef(url: String): IMessageRef? =
            messages.firstOrNull { resolveMediaUrl(it.mediaUrl) == url } ?: previewExtraRefs[url]
        ImageShareSheet(
            imageUrl = shareImageUrl,
            // 收藏复用长按菜单同一入口（FavoritesStore.addMessage），按完整 URL 反查本会话的消息引用。
            favoriteRef = remember(shareImageUrl, previewExtraRefs) { findRef(shareImageUrl) },
            onLocate = {
                findRef(shareImageUrl)?.let { onLocateInChat(it.id) }
                imagePreviewCloser()
            },
            onDismiss = { showImageShareSheet = false }
        )
    }

    // ── 视频全屏播放器（TextureView + ExoPlayer，复用 OkHttp 磁盘缓存：流式分片/可拖动/弱网不卡）──
    // 关闭走 closeVideoPlayer()：先播完缩回动画再卸载，返回键/点击关闭都统一走这里。
    // 退出动画期间仍拦截返回键（closeVideoPlayer 幂等），否则第二次返回会直接把聊天页退掉。
    BackHandler(enabled = videoPlayUrl.isNotEmpty()) { closeVideoPlayer() }
    if (videoPlayUrl.isNotEmpty()) {
        // 打开动画：从视频缩略图矩形放大到全屏（无源矩形时退化为淡入）
        LaunchedEffect(videoSession) {
            videoProgress.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
        }
        // 同图片预览：沉浸式全屏统一走 ImmersiveSystemBars（BEHAVIOR_DEFAULT），
        // 不改动 window 的 decorFits 标志，也不再用 transient-bars-by-swipe 那种
        // 「第一次侧滑只露出系统栏」的模式。
        com.aurora.chat.ui.components.ImmersiveSystemBars(key = videoPlayUrl)

        // 缓冲百分比 0~100
        var bufferPercent by remember(videoPlayUrl) { mutableIntStateOf(0) }
        // 是否正在缓冲
        var isBuffering by remember(videoPlayUrl) { mutableStateOf(true) }
        // 下载速度文本（如 "2.3 MB/s"）
        var speedText by remember(videoPlayUrl) { mutableStateOf("") }
        // 是否已准备好播放
        var isPrepared by remember(videoPlayUrl) { mutableStateOf(false) }
        // 视频宽高比
        var videoAspectRatio by remember(videoPlayUrl) { mutableFloatStateOf(1f) }
        // 视频文件总大小（HEAD 请求缓存，0=未知）
        var totalFileSize by remember(videoPlayUrl) { mutableLongStateOf(0L) }
        // 计算下载速度的追踪变量
        var lastBytes by remember(videoPlayUrl) { mutableLongStateOf(0L) }
        var lastTimeMs by remember(videoPlayUrl) { mutableLongStateOf(0L) }

        // ── 播放控制状态 ──
        var isPaused by remember(videoPlayUrl) { mutableStateOf(false) }
        var showPlayPauseIcon by remember(videoPlayUrl) { mutableStateOf(false) }
        var currentPositionMs by remember(videoPlayUrl) { mutableLongStateOf(0L) }
        var videoDurationMs by remember(videoPlayUrl) { mutableLongStateOf(0L) }
        var exoPlayerRef by remember(videoPlayUrl) { mutableStateOf<ExoPlayer?>(null) }
        // 退出/换源时强制释放播放器：onSurfaceTextureDestroyed 有时不及时甚至不触发，
        // 导致关掉播放器后视频仍在后台播（还有声音）。这里随 videoPlayUrl 卸载兜底 stop+release。
        DisposableEffect(videoPlayUrl) {
            onDispose {
                runCatching { exoPlayerRef?.stop() }
                runCatching { exoPlayerRef?.release() }
                exoPlayerRef = null
            }
        }
        // 是否正在拖拽进度条（拖拽时暂停位置跟踪定时器）
        var isSeeking by remember(videoPlayUrl) { mutableStateOf(false) }

        // ── 首帧优化：先铺缩略图当"封面"，纹理出画后再撤下 ──
        // 点开瞬间就有画面（不再黑屏），hero 放大也与气泡里的缩略图无缝衔接。
        var posterBitmap by remember(videoPlayUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var showPoster by remember(videoPlayUrl) { mutableStateOf(true) }
        LaunchedEffect(videoPlayUrl) {
            posterBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    com.aurora.chat.data.local.VideoThumbCache.load(ctx, videoPlayUrl, maxDim = 1440)
                }.getOrNull()
            }
        }
        // 进度/缓冲轮询（250ms）：ExoPlayer 没有 MediaPlayer 那种「缓冲百分比」回调，
        // 这里统一从播放器读 position/duration/bufferedPercentage/isLoading 驱动 UI
        // （进度条、下载速度、缓冲遮罩），替代旧的整文件下载 + 缓冲回调。
        LaunchedEffect(videoPlayUrl) {
            while (true) {
                exoPlayerRef?.let { p ->
                    currentPositionMs = p.currentPosition.coerceAtLeast(0)
                    videoDurationMs = p.duration.coerceAtLeast(0)
                    bufferPercent = p.bufferedPercentage
                    // 只以 STATE_BUFFERING 判定"缓冲中"：不要带 p.isLoading —— 流式播放时 ExoPlayer
                    // 会持续向前预缓冲，isLoading 长期为 true，导致"一边在播一边显示缓冲中"。
                    // STATE_BUFFERING 只在真正缺数据（起播前 / 卡顿重缓冲）时才进入，播放中为 READY。
                    isBuffering = p.playbackState == Player.STATE_BUFFERING
                    isPrepared = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED
                }
                delay(250)
            }
        }

        // 本地优先：工作区文件 / 绝对路径 / file:// 一律直接读本机文件，绝不走网络、不下载、不缓存。
        // 只有真正的 http(s) 地址才走网络。此前的实现对本机文件也会命中 MediaCache/HEAD 请求，
        // 导致「本地视频却在走网络」的观感与无谓流量。
        val localVideoPath: String? = when {
            videoPlayUrl.startsWith("http://") || videoPlayUrl.startsWith("https://") -> null
            videoPlayUrl.startsWith("file://") -> videoPlayUrl.removePrefix("file://")
            videoPlayUrl.startsWith("/") -> videoPlayUrl
            else -> null
        }
        val isLocalVideo = localVideoPath != null

        fun formatTime(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { videoOverlaySize = it }
                // 打开/关闭：整层（黑底 + 播放画面 + 控件）从视频缩略图矩形放大/缩回。
                // 只做 GPU 变换、不触发布局；graphicsLayer 必须在 background 之前，
                // 否则黑底画在图层外，缩放/淡出对它不生效。
                .graphicsLayer {
                    val p = videoProgress.value
                    val src = videoHeroRect
                    val w = videoOverlaySize.width.toFloat()
                    val h = videoOverlaySize.height.toFloat()
                    if (src != null && w > 0f && h > 0f) {
                        val sx0 = (src.width / w).coerceIn(0.05f, 1f)
                        val sy0 = (src.height / h).coerceIn(0.05f, 1f)
                        scaleX = sx0 + (1f - sx0) * p
                        scaleY = sy0 + (1f - sy0) * p
                        translationX = (src.center.x - w / 2f) * (1f - p)
                        translationY = (src.center.y - h / 2f) * (1f - p)
                    }
                    alpha = p
                }
                .background(Color(0xFF000000)),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        var exoPlayer: ExoPlayer? = null
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                // ExoPlayer：复用项目 OkHttp 磁盘缓存（createExoPlayer 内部），
                                // 流式分片、可拖动、弱网不卡，与短视频内核一致；不再整文件下载。
                                exoPlayer = com.aurora.chat.ui.common.createExoPlayer(
                                    context,
                                    if (isLocalVideo) "file://$localVideoPath" else videoPlayUrl,
                                    isLocalVideo
                                )
                                exoPlayerRef = exoPlayer
                                exoPlayer?.setVideoTextureView(this@apply)
                                exoPlayer?.addListener(object : Player.Listener {
                                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                                        if (videoSize.width > 0 && videoSize.height > 0) {
                                            videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat().coerceAtLeast(1f)
                                        }
                                    }
                                    override fun onRenderedFirstFrame() {
                                        // 真正画出第一帧才撤封面（短视频同款逻辑）
                                        showPoster = false
                                        // isBuffering 由下方轮询统一维护，这里不抢
                                    }
                                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                        Log.e("VideoPlayer", "播放错误: code=${error.errorCode} ${error.message} url=$videoPlayUrl", error)
                                        isPrepared = false
                                    }
                                })
                            }

                            override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean {
                                exoPlayer?.release()
                                exoPlayer = null
                                exoPlayerRef = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {
                                // 纹理真正出画了才撤下封面：之前一直显示缩略图，避免黑屏
                                if (showPoster) {
                                    showPoster = false
                                    isBuffering = false
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    // 保持原始宽高比、按「完整可见」缩放：横屏视频左右满宽、上下留黑边；竖屏视频上下满高、左右留黑边。
                    // 此前这里写死 fillMaxSize()：TextureView 会把解码帧强行拉伸铺满自身尺寸，
                    // 而 setVideoScalingMode(SCALE_TO_FIT) 对 TextureView 无效（那只对 SurfaceView 生效），
                    // 于是任何非全屏比例的视频都被拉变形（竖屏视频尤其明显）。尺寸必须由外层布局约束。
                    .aspectRatio(
                        videoAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
                    )
            )

            // ── 首帧封面：叠在纹理之上，等第一帧真正出画再撤掉 ──
            if (showPoster) {
                posterBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // ── 点击遮罩层：点击显示进度条，再次点击暂停/播放 ──
            var showControls by remember(videoPlayUrl) { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (!showControls) {
                            // 首次点击：显示控制栏
                            showControls = true
                        } else {
                            // 已显示控制栏：切换暂停/播放
                            val p = exoPlayerRef
                            if (p != null && isPrepared) {
                                if (p.isPlaying) {
                                    p.pause()
                                    isPaused = true
                                } else {
                                    p.play()
                                    isPaused = false
                                }
                                showPlayPauseIcon = true
                            }
                        }
                    }
            )

            // ── Material 动画暂停/播放图标（仅控制栏显示时可见）──
            AnimatedVisibility(
                visible = showControls && showPlayPauseIcon,
                enter = scaleIn(animationSpec = tween(300)) + fadeIn(tween(200)),
                exit = scaleOut(animationSpec = tween(300)) + fadeOut(tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (isPaused) "点击播放" else "点击暂停",
                        modifier = Modifier.size(40.dp),
                        tint = Color.White
                    )
                }
            }

            // ── 底部进度条和时间显示（默认隐藏，点击唤起，2秒自动淡出）──
            // 2秒无操作自动隐藏控制栏
            if (showControls) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showControls = false
                    showPlayPauseIcon = false
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.BottomCenter),
                contentAlignment = Alignment.BottomCenter
            ) {
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(animationSpec = tween(300)),
                    exit = fadeOut(animationSpec = tween(300))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .background(Color(0x80000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                    // 进度条轨道（扩大触摸区域便于拖动）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp) // 触摸区域
                            .clipToBounds()
                            .pointerInput(videoDurationMs) {
                                detectTapGestures { offset ->
                                    if (videoDurationMs > 0) {
                                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        val targetMs = (fraction * videoDurationMs).toLong()
                                        exoPlayerRef?.seekTo(targetMs)
                                        currentPositionMs = targetMs
                                    }
                                }
                            }
                            .pointerInput(videoDurationMs) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        isSeeking = true
                                        if (videoDurationMs > 0) {
                                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            val targetMs = (fraction * videoDurationMs).toLong()
                                            exoPlayerRef?.seekTo(targetMs)
                                            currentPositionMs = targetMs
                                        }
                                    },
                                    onDragEnd = {
                                        isSeeking = false
                                    },
                                    onDragCancel = {
                                        isSeeking = false
                                    }
                                ) { change, _ ->
                                    if (videoDurationMs > 0) {
                                        val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        val targetMs = (fraction * videoDurationMs).toLong()
                                        exoPlayerRef?.seekTo(targetMs)
                                        currentPositionMs = targetMs
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // 视觉进度条（4dp 细条）：不再铺浅灰轨道底色，只留已播放的那段白条
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        ) {
                            val progress = if (videoDurationMs > 0) {
                                (currentPositionMs.toFloat() / videoDurationMs).coerceIn(0f, 1f)
                            } else 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = progress)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(com.aurora.chat.ui.theme.appSurface())
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(currentPositionMs), fontSize = 11.sp, color = Color(0xCCFFFFFF))
                        Text(formatTime(videoDurationMs), fontSize = 11.sp, color = Color(0xCCFFFFFF))
                    }
                }
            }
            }



            // ── 加载遮罩层（仅在缓冲或未准备好时显示；本地文件秒开，一律不显示遮罩）──
            if (!isLocalVideo && (isBuffering || !isPrepared)) {
                if (totalFileSize == 0L) {
                    LaunchedEffect(Unit) {
                        try {
                            // 走到这里必然是非本地（本地文件不显示遮罩），只需取网络文件大小
                            val len = com.aurora.chat.data.api.HttpClient.headContentLength(videoPlayUrl)
                            if (len > 0) totalFileSize = len
                        } catch (_: Exception) { totalFileSize = -1L }
                    }
                }

                LaunchedEffect(bufferPercent) {
                    if (bufferPercent > 0 && totalFileSize > 0) {
                        val nowMs = System.currentTimeMillis()
                        val currentBytes = (totalFileSize * bufferPercent) / 100L
                        if (lastBytes <= 0L) {
                            // 首次采样只建基准，不算速度（否则第一帧会得到天文数字）
                            lastBytes = currentBytes
                            lastTimeMs = nowMs
                        } else {
                            val elapsed = nowMs - lastTimeMs
                            val deltaBytes = currentBytes - lastBytes
                            when {
                                // 往回拖进度条 / 重新缓冲时缓冲区会回退，字节数变少。
                                // 直接相减再除时间会算出负数速度（"−2809148 B/s"）。这里直接重置基准。
                                deltaBytes <= 0L -> {
                                    lastBytes = currentBytes
                                    lastTimeMs = nowMs
                                    speedText = ""
                                }
                                // 采样间隔太短（1ms 级）算出来的速度同样失真，累加到 300ms 再报
                                elapsed >= 300L -> {
                                    val bytesPerSec = (deltaBytes * 1000L) / elapsed
                                    speedText = when {
                                        bytesPerSec > 1_000_000 -> "${"%.1f".format(bytesPerSec / 1_000_000.0)} MB/s"
                                        bytesPerSec > 1_000 -> "${"%.0f".format(bytesPerSec / 1_000.0)} KB/s"
                                        else -> "$bytesPerSec B/s"
                                    }
                                    lastBytes = currentBytes
                                    lastTimeMs = nowMs
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 有封面时减淡遮罩：让缩略图当首帧，只保留加载信息，观感不再"黑屏等"
                        .background(if (showPoster && posterBitmap != null) Color(0x66000000) else Color(0x80000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color.White,
                            strokeWidth = 4.dp,
                            trackColor = Color(0x4DFFFFFF)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (bufferPercent > 0) "缓冲中 ${bufferPercent}%" else "加载中...",
                            fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium
                        )
                        if (speedText.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(speedText, fontSize = 13.sp, color = Color(0xCCFFFFFF))
                        }
                        if (bufferPercent > 0) {
                            Spacer(Modifier.height(12.dp))
                            // 缓冲进度条：同样不铺灰色轨道，只显示白色的已缓冲部分
                            Box(
                                modifier = Modifier
                                    .width(200.dp).height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction = bufferPercent / 100f)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(com.aurora.chat.ui.theme.appSurface())
                                )
                            }
                        }
                    }
                }
            }

            // ── 播放图标自动隐藏（2秒后）──
            LaunchedEffect(showPlayPauseIcon) {
                if (showPlayPauseIcon) {
                    delay(2000)
                    showPlayPauseIcon = false
                }
            }

            // ── 实时更新播放位置（拖拽进度条时跳过）──
            LaunchedEffect(isPaused, isPrepared) {
                while (isPrepared && !isPaused) {
                    if (!isSeeking) {
                        exoPlayerRef?.let { p ->
                            if (p.isPlaying) currentPositionMs = p.currentPosition.coerceAtLeast(0)
                        }
                    }
                    delay(250)
                }
            }
        }
    }

    // ── 文本文件预览：全屏覆盖，从右侧滑入/滑出 ──
    androidx.compose.animation.AnimatedVisibility(
        visible = filePreviewVisible,
        enter = androidx.compose.animation.slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = androidx.compose.animation.core.tween(220)
        ),
        exit = androidx.compose.animation.slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = androidx.compose.animation.core.tween(200)
        )
    ) {
        val target = filePreviewTarget
        if (target != null) {
            FileTextPreview(
                url = target.first,
                fileName = target.second,
                onClose = { filePreviewVisible = false },
                onDownload = { url, fileName ->
                            // 文本/MD 等预览文件：走系统「另存为」让用户自己挑保存目录，
                            // 而不是只能落到固定 downloads 目录（CreateDocument 已支持 http/本地/Content 三种源写入）。
                            val ext = fileName.substringAfterLast('.', "").lowercase()
                            startFileSaveAs(url, fileName, ext)
                        }
            )
        }
    }
    if (filePreviewVisible) {
        BackHandler(enabled = filePreviewVisible) { filePreviewVisible = false }
    }

    // ── 图片保存到相册确认弹窗（JPG/PNG 等）──
    val imgConfirm = imageSaveConfirm
    if (imgConfirm != null) {
        val (imgUrl, imgName, _) = imgConfirm
        AlertDialog(
            onDismissRequest = { imageSaveConfirm = null },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("保存图片", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    Text("这是一张图片，是否保存到本地相册？", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), lineHeight = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(imgName, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    imageSaveConfirm = null
                    saveChatImageToGallery(imgUrl)
                }) { Text("保存到相册", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { imageSaveConfirm = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }

    // ── 文件下载确认弹窗（APK 等非文本类型）──
    val confirm = fileDownloadConfirm
    if (confirm != null) {
        val (dlUrl, dlName, dlExt) = confirm
        val title = if (dlExt == "apk") "这是一个 APK 文件，是否下载？" else "这是一个 ${dlExt.ifEmpty { "文件" }.uppercase()} 文件，是否下载？"
        AlertDialog(
            onDismissRequest = { fileDownloadConfirm = null },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("文件下载", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    Text(title, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), lineHeight = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(dlName, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fileDownloadConfirm = null
                    // 直接进入「下载管理」下载：悬浮球 + 百分比 + 速度全程可见，支持暂停/重试
                    startChatFileDownload(dlUrl, dlName)
                }) { Text("下载", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { fileDownloadConfirm = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }


    // ── 「AI 设置 → 开启新对话」确认弹窗：与主界面「开启新对话」走同一安全清空序列 ──
    if (showAiNewConfirm) {
        AlertDialog(
            onDismissRequest = { showAiNewConfirm = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("开启新对话", fontWeight = FontWeight.Bold) },
            text = { Text("将清空当前 AI 对话的全部记录且无法恢复，确定要开启新对话吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showAiNewConfirm = false
                    scope.launch {
                        // 同一安全序列：先中断在途回复→等它真正停止→新会话代次隔离→清空并持久化
                        resetAiConversation(ctx, currentUserId, messages)
                        showAiUsage = false
                        // 确认成功：提示后返回控制，不做任何后续工作（确认成功即停）
                        android.widget.Toast.makeText(ctx, "已开启新对话", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确定", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showAiNewConfirm = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }



    // ── 设置 AI 名称输入框 ──
    if (showAiNameInput) {
        AlertDialog(
            onDismissRequest = { showAiNameInput = false },
            title = { Text("设置 AI 名称") },
            text = {
                OutlinedTextField(
                    value = aiNameInput,
                    onValueChange = { aiNameInput = it.take(20) },
                    placeholder = { Text("例如：小深") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = aiNameInput.trim()
                    if (n.isNotEmpty()) {
                        LocalStorage.setAiName(ctx, n)
                        AiNameState.name = n
                        Toast.makeText(ctx, "AI 名称已设为：$n", Toast.LENGTH_SHORT).show()
                    }
                    showAiNameInput = false
                }) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { showAiNameInput = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }

    // ── 群聊 @ 成员抽屉：输入 @ 时从输入框上方往上拉出，选择成员（按 A-Z 排列）──
    if (isGroupChat(friendId)) {
        val atStart = remember(inputText) { atMentionStart(inputText) }
        val atShow = atStart >= 0
        // 抽屉拉出时收起软键盘，让接近全屏的面板完整露出
        LaunchedEffect(atShow) { if (atShow) focusManager.clearFocus() }
        Box(modifier = Modifier.fillMaxSize()) {
            // 上半部淡色遮罩（淡入淡出，点击关闭并去掉未输完的 @...）
            AnimatedVisibility(
                visible = atShow,
                enter = fadeIn(tween(250)),
                exit = fadeOut(tween(220)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable(
                            onClick = { inputText = inputText.substring(0, atStart) },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        )
                )
            }
            // 面板：从输入框上方边界往上拉出
            AnimatedVisibility(
                visible = atShow,
                enter = slideInVertically(tween(300)) { it },
                exit = slideOutVertically(tween(300)) { it },
                modifier = Modifier.fillMaxSize()
            ) {
                AtMemberSheet(
                    members = groupAtMembers,
                    canAtAll = isAdminOrOwner,
                    onSelect = { word -> inputText = inputText.substring(0, atStart) + word + " " },
                    onDismiss = { inputText = inputText.substring(0, atStart) }
                )
            }
        }
        BackHandler(enabled = atShow) { inputText = inputText.substring(0, atStart) }
    }

    // ── 「Agent 功能已移至新软件」一次性提示（首次进入 AI 对话）──
    if (showAgentMovedDialog) {
        // 品牌深蓝（与下方「我知道了」按钮同色），不用浅蓝
        val linkBlue = Color(0xFF1E40AF)
        AlertDialog(
            onDismissRequest = {
                showAgentMovedDialog = false
                AIUiPrefs.setAgentMovedDialogShown(ctx)
            },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("Agent功能已移至新软件", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("可前往官网下载：", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        Text(
                            "跳转官网",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = linkBlue,
                            modifier = Modifier.clickable {
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
                                }
                                showAgentMovedDialog = false
                                AIUiPrefs.setAgentMovedDialogShown(ctx)
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("或直接下载：", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        Text(
                            "直接下载",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = linkBlue,
                            modifier = Modifier.clickable {
                                startChatFileDownload(
                                    "https://www.example.com/backend/data/tools/AuroraAgent.apk",
                                    "AuroraAgent.apk"
                                )
                                showAgentMovedDialog = false
                                AIUiPrefs.setAgentMovedDialogShown(ctx)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAgentMovedDialog = false
                    AIUiPrefs.setAgentMovedDialogShown(ctx)
                }) { Text("我知道了", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF)) }
            }
        )
    }
}

@Stable
private class ChatMainScope {
    var currentUserId: Long = 0L
    lateinit var currentUserName: String
    var friendId: Long = 0L
    lateinit var friendName: String
    lateinit var onBack: () -> Unit
    lateinit var onOpenJoinRequestFullScreen: (org.json.JSONArray, Long) -> Unit
    lateinit var onOpenPostDetail: (Long) -> Unit
    lateinit var onOpenGroupDetail: (Long) -> Unit
    lateinit var onOpenNewChat: (Long, String, String) -> Unit
    lateinit var onTokenExhausted: () -> Unit
    var onAppAction: (suspend (AiChatManager.AppAction) -> AiChatManager.AppActionResult)? = null
    lateinit var contactSource: String
    lateinit var onContactSourceConsumed: () -> Unit
    lateinit var onOpenShortVideo: (Long) -> Unit
    lateinit var inputTextState: MutableState<String>
    lateinit var ctx: Context
    lateinit var confirmedAnnouncementTexts: androidx.compose.runtime.snapshots.SnapshotStateSet<String>
    lateinit var annDetailRawState: MutableState<String?>
    lateinit var annDetailTimeState: MutableState<Long>
    lateinit var annImageViewerUrlState: MutableState<String?>
    lateinit var annReadCountsState: MutableState<Map<Long, Int>>
    lateinit var annConfirmCountsState: MutableState<Map<Long, Int>>
    lateinit var annConfirmSheetTextState: MutableState<String?>
    lateinit var draftKey: String
    lateinit var quoteKey: String
    lateinit var listState: androidx.compose.foundation.lazy.LazyListState
    lateinit var scope: kotlinx.coroutines.CoroutineScope
    lateinit var aiUsageState: MutableState<Pair<Long, Long>>
    var BOTTOM_OFFSET_TOLERANCE_PX: Int = 0
    lateinit var autoFollowState: MutableState<Boolean>
    lateinit var pendingScrollToBottomState: MutableState<Boolean>
    lateinit var programmaticScrollingState: MutableState<Boolean>
    lateinit var unreadNewCountState: MutableState<Int>
    lateinit var programmaticScroll: (Int, Int) -> Unit
    lateinit var msgBoundsInWindow: HashMap<Long, Rect>
    lateinit var listViewportInWindowState: MutableState<Rect>
    lateinit var anchorItemIndexState: MutableState<Int>
    lateinit var anchorItemOffsetState: MutableState<Int>
    lateinit var anchorUpdateSuppressedState: MutableState<Boolean>
    lateinit var apiSuspendedDialogState: MutableState<Boolean>
    lateinit var groupMemberNamesState: MutableState<Set<String>>
    lateinit var groupMemberIdMapState: MutableState<Map<String, Long>>
    lateinit var groupRolesState: MutableState<Map<Long, String>>
    lateinit var isLoadingMessagesState: MutableState<Boolean>
    lateinit var initialLoadFailedState: MutableState<Boolean>
    lateinit var loadRetryTickState: MutableState<Int>
    lateinit var groupExitTickState: MutableState<Int>
    var groupExit: com.aurora.chat.data.local.GroupExit? = null
    var isExitedGroup: Boolean = false
    lateinit var scrollReadyState: MutableState<Boolean>
    lateinit var recallTickState: MutableState<Int>
    lateinit var uploadProgressMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Float>
    lateinit var uploadStageMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, String>
    lateinit var uploadRatioMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Float>
    lateinit var uploadFileNameMap: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, String>
    lateinit var replyToMsg: MutableState<IMessageRef?>
    lateinit var quoteFocusTickState: MutableState<Int>
    lateinit var showPlusDialogState: MutableState<Boolean>
    lateinit var showStickerPanelState: MutableState<Boolean>
    lateinit var inputBarTopYState: MutableState<Float>
    lateinit var showStickerPanelSt: State<Boolean>
    lateinit var showPlusDialogSt: State<Boolean>
    lateinit var inputBarTopYSt: State<Float>
    lateinit var showTransferDialogState: MutableState<Boolean>
    lateinit var showRedPacketDialogState: MutableState<Boolean>
    lateinit var redPacketDetailIdState: MutableState<Long>
    lateinit var redpacketStatus: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, RedPacketStatus>
    lateinit var showCameraState: MutableState<Boolean>
    lateinit var showPrivacyPanelState: MutableState<Boolean>
    lateinit var mutedUntilState: MutableState<Long>
    lateinit var showLocationConfirmState: MutableState<Boolean>
    lateinit var showTimePointDialogState: MutableState<Boolean>
    lateinit var showConfirmSpeechDialogState: MutableState<Boolean>
    lateinit var showAiImageNoticeState: MutableState<Boolean>
    lateinit var confirmSpeechEnabledState: MutableState<Boolean>
    lateinit var currentTimePointState: MutableState<String>
    lateinit var confirmSpeechPendingState: MutableState<Boolean>
    lateinit var pendingAiImagesState: MutableState<List<PendingAiImage>>
    lateinit var aiImagePicker: androidx.activity.compose.ManagedActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest, List<Uri>>
    lateinit var pendingAiFilesState: MutableState<List<PendingAiFile>>
    lateinit var wsPickModeState: MutableState<Boolean>
    lateinit var showPlanEditState: MutableState<Boolean>
    lateinit var planEditTextState: MutableState<String>
    lateinit var wsInjectForSendState: MutableState<List<Triple<Uri, String, Long>>>
    lateinit var aiFilePicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<Uri>>
    lateinit var adapter: MessageStoreAdapter
    lateinit var messages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>
    lateinit var pendingUploadPlaceholders: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>
    lateinit var pendingFailedMessages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>
    lateinit var retryTargetMsgState: MutableState<IMessageRef?>
    lateinit var markSendFailed: (ChatMsg) -> Unit
    lateinit var addLocalNotice: (Long, String, String) -> Unit
    lateinit var resendFailedMessage: (IMessageRef) -> Unit
    lateinit var insertAiMediaCard: (String, String, Long) -> Unit
    lateinit var insertAiFileCard: (String, Boolean, Long) -> Unit
    lateinit var scheduleAiDebouncedSave: () -> Unit
    lateinit var updatePlanStep: (Int, String) -> Unit
    lateinit var openPlanEditor: () -> Unit
    lateinit var savePlanStepsFromEditor: () -> Unit
    lateinit var syncMessages: () -> Unit
    lateinit var showEditDialogState: MutableState<Boolean>
    lateinit var editTargetMsgIdState: MutableState<Long>
    lateinit var editReasoningTextState: MutableState<String>
    lateinit var editOutputTextState: MutableState<String>
    lateinit var resendNewChatConfirmState: MutableState<Boolean>
    lateinit var resendEditMsgIdState: MutableState<Long>
    lateinit var resendEditTextState: MutableState<String>
    lateinit var showBroadcastSendDialogState: MutableState<Boolean>
    lateinit var broadcastManageMsgIdState: MutableState<Long>
    lateinit var broadcastManageTaskIdState: MutableState<Long>
    lateinit var aiFileSendingTipState: MutableState<String?>
    lateinit var viewOrderIdState: MutableState<Long?>
    lateinit var agentAccessModeState: MutableState<String>
    lateinit var agentModeState: MutableState<String>
    lateinit var sendUserTurnState: MutableState<() -> Unit>
    lateinit var resumeTaskPendingState: MutableState<Boolean>
    lateinit var showThinkingLevelMenuState: MutableState<Boolean>
    lateinit var thinkingLevelState: MutableState<Int>
    lateinit var execModesState: MutableState<Set<String>>
    lateinit var showWorkspaceFilesState: MutableState<Boolean>
    lateinit var workspaceLocateNameState: MutableState<String?>
    lateinit var workspaceLocateOpenState: MutableState<Boolean>
    lateinit var showUsageHistoryState: MutableState<Boolean>
    lateinit var showBackupScreenState: MutableState<Boolean>
    lateinit var aiChangesOpenState: MutableState<Boolean>
    lateinit var aiChangesJsonDataState: MutableState<String>
    lateinit var aiChangesMsgIdState: MutableState<Long>
    lateinit var aiChangesFocusPathState: MutableState<String>
    lateinit var aiChangesFocusDetailState: MutableState<Boolean>
    lateinit var aiToolCallsOpenState: MutableState<Boolean>
    lateinit var aiToolCallsDataState: MutableState<List<ChatMsg.ToolStageRecord>>
    lateinit var modeExplainDialogState: MutableState<String?>
    lateinit var pendingToolApprovalState: MutableState<Pair<String, String>?>
    lateinit var pendingToolApprovalDeferred: MutableState<kotlinx.coroutines.CompletableDeferred<Boolean>?>
    lateinit var approvalMinimizedState: MutableState<Boolean>
    lateinit var approvalTrustUntilState: MutableState<Long>
    lateinit var toolApprovalHook: suspend (String, String) -> Boolean
    lateinit var closeStaleAiWaiting: (Long) -> Unit
    lateinit var onConfirmReply: () -> Unit
    lateinit var myAvatarBitmapState: MutableState<android.graphics.Bitmap?>
    lateinit var destroyedFlashIdsState: MutableState<Set<Long>>
    lateinit var partnerViewedFlashIdsState: MutableState<Set<Long>>
    lateinit var viewingFlashIdsState: MutableState<Set<Long>>
    lateinit var saveDestroyedFlash: (Long, String) -> Unit
    lateinit var saveFlashViewStart: (Long) -> Unit
    lateinit var openMenuMsgIdState: MutableState<Long>
    lateinit var redPacketMenuMsgIdState: MutableState<Long>
    lateinit var redPacketMenuAnchorState: MutableState<Rect>
    lateinit var announcementMenuMsgIdState: MutableState<Long>
    lateinit var announcementMenuAnchorState: MutableState<Rect>
    lateinit var clearSelectionKeyState: MutableState<Int>
    lateinit var multiSelectModeState: MutableState<Boolean>
    lateinit var selectedMsgIds: MutableState<Set<Long>>
    lateinit var multiDeleteConfirmState: MutableState<Boolean>
    lateinit var chatShareCardTextState: MutableState<String>
    lateinit var showChatShareDialogState: MutableState<Boolean>
    lateinit var chatShareViewTextState: MutableState<String>
    lateinit var chatShareViewMsgIdState: MutableState<Long>
    lateinit var selectAnchorMsgIdState: MutableState<Long>
    lateinit var selectToHereDirState: MutableState<Int>
    lateinit var exitMultiSelect: () -> Unit
    lateinit var highlightedMsgIdState: MutableState<Long>
    lateinit var jumpToMessageIdState: MutableState<Long>
    lateinit var imagePreviewUrlsState: MutableState<List<String>>
    lateinit var imagePreviewIndexState: MutableState<Int>
    lateinit var imageBounds: ImageBoundsRegistry
    lateinit var heroInitialIndexState: MutableState<Int>
    lateinit var previewSessionState: MutableState<Int>
    lateinit var previewReadyState: MutableState<Boolean>
    lateinit var heroEnterDoneState: MutableState<Boolean>
    lateinit var heroSourceRectState: MutableState<androidx.compose.ui.geometry.Rect?>
    lateinit var heroRectFor: (String, Boolean) -> Rect?
    lateinit var openVideoPlayer: (String, Boolean) -> Unit
    var headerTopPadding: Dp = Dp.Unspecified
    lateinit var filePreviewTargetState: MutableState<Pair<String, String>?>
    lateinit var filePreviewVisibleState: MutableState<Boolean>
    lateinit var fileDownloadConfirmState: MutableState<Triple<String, String, String>?>
    lateinit var startChatFileDownload: (String, String) -> Unit
    lateinit var deleteConfirmMsgIdState: MutableState<Long>
    lateinit var muteTargetUserIdState: MutableState<Long>
    lateinit var muteTargetUserNameState: MutableState<String>
    lateinit var isGroupOwnerState: MutableState<Boolean>
    lateinit var deletedMessageIdsState: MutableState<Set<Long>>
    lateinit var focusManager: androidx.compose.ui.focus.FocusManager
    lateinit var view: android.view.View
    lateinit var showGroupSettingsState: MutableState<Boolean>
    lateinit var showFriendSettingsState: MutableState<Boolean>
    lateinit var showChatHistorySearchState: MutableState<Boolean>
    lateinit var chatSearchState: ChatSearchState
    lateinit var searchReturnPendingState: MutableState<Boolean>
    lateinit var showProfileState: MutableState<Boolean>
    lateinit var profileUserIdState: MutableState<Long>
    lateinit var profileUserNameState: MutableState<String>
    lateinit var inviteRespondState: MutableState<InviteGroupCardData?>
    lateinit var showAiUsageState: MutableState<Boolean>
    lateinit var showFreeApiSelectState: MutableState<Boolean>
    lateinit var showPaidApiSelectState: MutableState<Boolean>
    lateinit var readSheetMsgIdState: MutableState<Long>
    lateinit var animMode: AnimationMode
    var advancedAnim: Boolean = false
    var myBubbleColor: Color = Color.Unspecified
    var otherBubbleColor: Color = Color.Unspecified
    lateinit var recentlySentMessages: MutableMap<String, Long>
    lateinit var isAdminOrOwnerState: MutableState<Boolean>
    lateinit var pendingCountState: MutableState<Int>
    lateinit var isNotGroupMemberState: MutableState<Boolean>
    lateinit var popupAnnouncementState: MutableState<GroupAnnouncementData?>
    lateinit var isFriendChatState: MutableState<Boolean?>
    lateinit var friendStatusRefreshState: MutableState<Int>
    lateinit var payDisabledState: MutableState<Boolean>
    lateinit var payBlockReasonState: MutableState<String?>
    lateinit var showAddFriendGreetingState: MutableState<Boolean>
    var INITIAL_WINDOW: Int = 0
    lateinit var loadMoreProgressState: MutableState<Int>
    lateinit var dbHasOlderState: MutableState<Boolean>
    lateinit var aiConvs: androidx.compose.runtime.snapshots.SnapshotStateList<AiConvMeta>
    lateinit var showAiConvDrawerState: MutableState<Boolean>
    lateinit var activeAiConvIdState: MutableState<Long>
    lateinit var showBackToBottomState: MutableState<Boolean>
    lateinit var upScrollPxState: MutableState<Float>
    lateinit var shouldShowScrollDown: androidx.compose.runtime.State<Boolean>
    lateinit var backToBottomThresholdPx: () -> Float
    lateinit var revealMessageRow: suspend (Int, Long) -> Unit
    lateinit var flashMessage: suspend (Long) -> Unit
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatMainScope.ChatConversationMain() {
    var inputText by inputTextState
    var annDetailRaw by annDetailRawState
    var annDetailTime by annDetailTimeState
    var annImageViewerUrl by annImageViewerUrlState
    var annReadCounts by annReadCountsState
    var annConfirmCounts by annConfirmCountsState
    var annConfirmSheetText by annConfirmSheetTextState
    var autoFollow by autoFollowState
    var programmaticScrolling by programmaticScrollingState
    var listViewportInWindow by listViewportInWindowState
    var anchorItemIndex by anchorItemIndexState
    var anchorItemOffset by anchorItemOffsetState
    var anchorUpdateSuppressed by anchorUpdateSuppressedState
    var apiSuspendedDialog by apiSuspendedDialogState
    var groupMemberNames by groupMemberNamesState
    var groupMemberIdMap by groupMemberIdMapState
    var groupRoles by groupRolesState
    var isLoadingMessages by isLoadingMessagesState
    var initialLoadFailed by initialLoadFailedState
    var loadRetryTick by loadRetryTickState
    var groupExitTick by groupExitTickState
    var scrollReady by scrollReadyState
    var recallTick by recallTickState
    var quoteFocusTick by quoteFocusTickState
    var showPlusDialog by showPlusDialogState
    var showStickerPanel by showStickerPanelState
    var redPacketDetailId by redPacketDetailIdState
    var showTimePointDialog by showTimePointDialogState
    var showConfirmSpeechDialog by showConfirmSpeechDialogState
    var showAiImageNotice by showAiImageNoticeState
    var confirmSpeechEnabled by confirmSpeechEnabledState
    var currentTimePoint by currentTimePointState
    var showPlanEdit by showPlanEditState
    var planEditText by planEditTextState
    var retryTargetMsg by retryTargetMsgState
    var showEditDialog by showEditDialogState
    var editTargetMsgId by editTargetMsgIdState
    var editReasoningText by editReasoningTextState
    var editOutputText by editOutputTextState
    var resendNewChatConfirm by resendNewChatConfirmState
    var resendEditMsgId by resendEditMsgIdState
    var resendEditText by resendEditTextState
    var showBroadcastSendDialog by showBroadcastSendDialogState
    var broadcastManageMsgId by broadcastManageMsgIdState
    var broadcastManageTaskId by broadcastManageTaskIdState
    var aiFileSendingTip by aiFileSendingTipState
    var viewOrderId by viewOrderIdState
    var agentMode by agentModeState
    var sendUserTurn by sendUserTurnState
    var resumeTaskPending by resumeTaskPendingState
    var showWorkspaceFiles by showWorkspaceFilesState
    var workspaceLocateName by workspaceLocateNameState
    var workspaceLocateOpen by workspaceLocateOpenState
    var showUsageHistory by showUsageHistoryState
    var aiChangesOpen by aiChangesOpenState
    var aiChangesJsonData by aiChangesJsonDataState
    var aiChangesMsgId by aiChangesMsgIdState
    var aiChangesFocusPath by aiChangesFocusPathState
    var aiChangesFocusDetail by aiChangesFocusDetailState
    var aiToolCallsOpen by aiToolCallsOpenState
    var aiToolCallsData by aiToolCallsDataState
    var myAvatarBitmap by myAvatarBitmapState
    var destroyedFlashIds by destroyedFlashIdsState
    var partnerViewedFlashIds by partnerViewedFlashIdsState
    var viewingFlashIds by viewingFlashIdsState
    var openMenuMsgId by openMenuMsgIdState
    var redPacketMenuMsgId by redPacketMenuMsgIdState
    var redPacketMenuAnchor by redPacketMenuAnchorState
    var announcementMenuMsgId by announcementMenuMsgIdState
    var announcementMenuAnchor by announcementMenuAnchorState
    var clearSelectionKey by clearSelectionKeyState
    var multiSelectMode by multiSelectModeState
    var multiDeleteConfirm by multiDeleteConfirmState
    var chatShareCardText by chatShareCardTextState
    var showChatShareDialog by showChatShareDialogState
    var chatShareViewText by chatShareViewTextState
    var chatShareViewMsgId by chatShareViewMsgIdState
    var selectAnchorMsgId by selectAnchorMsgIdState
    var highlightedMsgId by highlightedMsgIdState
    var jumpToMessageId by jumpToMessageIdState
    var imagePreviewUrls by imagePreviewUrlsState
    var imagePreviewIndex by imagePreviewIndexState
    var heroInitialIndex by heroInitialIndexState
    var previewSession by previewSessionState
    var previewReady by previewReadyState
    var heroEnterDone by heroEnterDoneState
    var heroSourceRect by heroSourceRectState
    var filePreviewTarget by filePreviewTargetState
    var filePreviewVisible by filePreviewVisibleState
    var fileDownloadConfirm by fileDownloadConfirmState
    var deleteConfirmMsgId by deleteConfirmMsgIdState
    var muteTargetUserId by muteTargetUserIdState
    var muteTargetUserName by muteTargetUserNameState
    var isGroupOwner by isGroupOwnerState
    var deletedMessageIds by deletedMessageIdsState
    var showGroupSettings by showGroupSettingsState
    var showFriendSettings by showFriendSettingsState
    var showProfile by showProfileState
    var profileUserId by profileUserIdState
    var profileUserName by profileUserNameState
    var inviteRespond by inviteRespondState
    var showAiUsage by showAiUsageState
    var showFreeApiSelect by showFreeApiSelectState
    var showPaidApiSelect by showPaidApiSelectState
    var readSheetMsgId by readSheetMsgIdState
    var isAdminOrOwner by isAdminOrOwnerState
    var pendingCount by pendingCountState
    var isNotGroupMember by isNotGroupMemberState
    var popupAnnouncement by popupAnnouncementState
    var isFriendChat by isFriendChatState
    var friendStatusRefresh by friendStatusRefreshState
    var payDisabled by payDisabledState
    var payBlockReason by payBlockReasonState
    var showAddFriendGreeting by showAddFriendGreetingState
    var loadMoreProgress by loadMoreProgressState
    var dbHasOlder by dbHasOlderState
    var showAiConvDrawer by showAiConvDrawerState
    var showBackToBottom by showBackToBottomState
    var upScrollPx by upScrollPxState
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    var downPos: Offset? = null
                    var downAt = 0L
                    var lastY = 0f
                    while (true) {
                        // Final 阶段能看到所有事件（含被子组件消费的），用于判定「轻点在面板外」
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.pressed) {
                            if (downPos == null) {
                                downPos = change.position
                                downAt = System.currentTimeMillis()
                                lastY = change.position.y
                            } else if (event.changes.size == 1) {
                                // ── 「回底」按钮的手势判定（与轻点判定共用这一个循环）──
                                val dy = change.position.y - lastY
                                lastY = change.position.y
                                if (dy < -0.5f) {
                                    // 手指往上 = 翻更旧的消息：累计上滑距离；按钮已出现则继续上滑立刻收起
                                    upScrollPx += -dy
                                    if (showBackToBottom) showBackToBottom = false
                                } else if (dy > 0.5f) {
                                    // 手指往下 = 主动想回最新：上滑累计超过约一屏才给按钮出场资格
                                    if (!showBackToBottom && upScrollPx >= backToBottomThresholdPx()) {
                                        showBackToBottom = true
                                    }
                                }
                            }
                        } else {
                            val start = downPos
                            if (start != null) {
                                val moved = (change.position - start).getDistance()
                                // 轻点（非滑动）且落点在输入栏上方 → 收起表情/功能面板（只收起，不弹键盘）
                                if (moved < slop && System.currentTimeMillis() - downAt < 450 &&
                                    (showStickerPanelSt.value || showPlusDialogSt.value) &&
                                    change.position.y < inputBarTopYSt.value
                                ) {
                                    showStickerPanel = false
                                    showPlusDialog = false
                                }
                            }
                            downPos = null
                        }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 聊天背景：所有会话统一浅灰；该好友设置过背景图片时为该图
                .chatBackground(friendId)
                // 底部槽位（键盘 / 表情 / 加号共用）已经把 IME inset 算进去了，
                // 这里再叠一层 imePadding 会把键盘高度算两次、并让整个界面跟着键盘每帧抖动。
        ) {
            // 顶栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 顶栏用「栏底色」：深色主题下是纯黑，与页面/卡片拉开层次（用户明确要求顶栏纯黑）
                    .background(com.aurora.chat.ui.theme.appBarBg())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 顶部安全区用稳定值：全屏预览隐藏/恢复系统栏时，顶栏不跟着 inset 变化移动
                        .padding(top = headerTopPadding)
                        // 顶栏留白：原先上下各 16.dp，整条比微信/QQ 厚一截（约 60dp vs 48dp），
                        // 标题 16sp 本身已经比应用内其它页（17sp）小，显得「大」主要是这块留白，
                        // 所以只收留白不动字号：上下 11.dp ≈ 微信的紧凑高度。
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ← 用固定小尺寸 Box 限制触摸区域，防止覆盖标题
                    Box(
                        modifier = Modifier.size(28.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("←", fontSize = 20.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // 读取备注
                    val remarkPrefs = ctx.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                    val remarkKey = if (friendId < 0 && friendId != AI_CHAT_ID && friendId != NOTIFICATION_CHAT_ID)
                        "remark_group_${-(friendId + 1000)}" else "remark_$friendId"
                    var chatRemark by remember(remarkKey) { mutableStateOf(remarkPrefs.getString(remarkKey, "") ?: "") }
                    LaunchedEffect(remarkKey) { chatRemark = remarkPrefs.getString(remarkKey, "") ?: "" }
                    val chatTitle = if (friendId == AI_CHAT_ID) (AiNameState.name ?: "AI") else chatRemark.ifEmpty { friendName }.ifEmpty { "聊天" }
                    Text(
                        text = chatTitle,
                        fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    // 右上角三横杠菜单（群聊→群设置，私信→资料页）
                    if (!isNotificationChat(friendId)) {
                        // AI 对话：右上角「对话列表」按钮打开会话列表抽屉
                        if (friendId == AI_CHAT_ID) {
                            Spacer(Modifier.width(6.dp))
                            androidx.compose.foundation.layout.Box(modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(com.aurora.chat.ui.theme.appSelectedBg())
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { showAiConvDrawer = true }
                                .padding(horizontal = 10.dp, vertical = 5.dp)) {
                                Text("对话列表", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box {
                            Box(
                                modifier = Modifier.size(28.dp).clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    // 进入群聊信息 / 资料页前先收键盘与底部面板：否则输入法留在底部，页面只露出上半屏
                                    focusManager.clearFocus()
                                    showPlusDialog = false
                                    showStickerPanel = false
                                    val imm = ctx.getSystemService(
                                        android.content.Context.INPUT_METHOD_SERVICE
                                    ) as android.view.inputmethod.InputMethodManager
                                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                                    if (friendId == AI_CHAT_ID) {
                                        showAiUsage = true
                                    } else if (isGroupChat(friendId)) {
                                        showGroupSettings = true
                                    } else {
                                        // 私信 / 个人对话(服务端自聊)：点击三横杠直接进入资料页
                                        showFriendSettings = true
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("☰", fontSize = 22.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                            }
                        }
                    }
                }
            }

            // 开启新对话确认弹窗状态
            var showNewChatConfirm by remember { mutableStateOf(false) }

            // ── AI 对话「使用」面板（从右侧滑出：顶部 API 选择 + 中部性格调试）──
            if (showAiUsage) {
                // 把面板状态提升到 Dialog 之前，便于「离开即保存」
                var selMode by remember(currentUserId) { mutableStateOf(AiChatManager.getMode(ctx, currentUserId)) }
                val personal = AiChatManager.getPersonalConfig(ctx, currentUserId)
                var pKey by remember(currentUserId) { mutableStateOf(personal.first) }
                var pUrl by remember(currentUserId) { mutableStateOf(personal.second) }
                var pModel by remember(currentUserId) { mutableStateOf(personal.third) }
                var personalityDebug by remember(currentUserId) { mutableStateOf(AiChatManager.getPersonalityDebug(ctx, currentUserId)) }
                val traitLabels = AiChatManager.TRAIT_LABELS
                val traits = remember(currentUserId) {
                    mutableStateListOf<Float>().apply { addAll(AiChatManager.getTraits(ctx, currentUserId)) }
                }
                // 自定义性格设定（5 条维度之外额外添加的一条，按字面意思注入，不绑定任何维度）
                val customs = remember(currentUserId) {
                    mutableStateListOf<CustomTrait>().apply { addAll(AiChatManager.getCustomTraits(ctx, currentUserId)) }
                }
                var showCustomDialog by remember { mutableStateOf(false) }
                var customDialogText by remember { mutableStateOf("") }

                // ── 液态模式：性格调试滑块用液态玻璃滑块；经典模式保持原 Material3 Slider ──
                // 面板是白底，给玻璃手柄一个纯色折射源（面板本身仍是白底，只让滑块内部有玻璃感）
                // 高级选项：场景 / 角色设定 / AI 多次输出（离开面板即保存）
                var advScenario by remember(currentUserId) { mutableStateOf(AiChatManager.getScenario(ctx, currentUserId)) }
                var advMyRole by remember(currentUserId) { mutableStateOf(AiChatManager.getMyRole(ctx, currentUserId)) }
                var advAiRole by remember(currentUserId) { mutableStateOf(AiChatManager.getAiRole(ctx, currentUserId)) }
                var advMulti by remember(currentUserId) { mutableStateOf(AiChatManager.getMultiOutput(ctx, currentUserId)) }
                var memoryEnabled by remember(currentUserId) { mutableStateOf(AiChatManager.isMemoryEnabled(ctx, currentUserId)) }
                var showAdvancedOptions by remember { mutableStateOf(false) }
                // ── AI 对话专属：时间点 / 确认发言 ──（已提升到 ChatConversationScreen 顶层，此处不再重复声明）


                // 实时用量：与对话流式过程共享 aiUsageState，AI 边输出边刷新（不再等对话结束）
                // 打开面板时动态从服务器拉取最新余额，保证「剩余」是最新值
                LaunchedEffect(Unit) {
                    scope.launch {
                        AiChatManager.syncServerLimit(ctx, currentUserId)
                        aiUsageState.value = AiChatManager.getOfficialUsage(ctx, currentUserId)
                    }
                }

                // 离开面板即保存全部设置，并弹出提示；仅当性格确有修改时标记下一条请求注入新性格
                val saveAiUsageAndClose: () -> Unit = {
                    val stored = AiChatManager.getTraits(ctx, currentUserId)
                    val traitsChanged = stored.zip(traits) { a, b -> a != b }.any { it }
                    AiChatManager.saveTraits(ctx, currentUserId, traits)
                    AiChatManager.saveCustomTraits(ctx, currentUserId, customs)
                    // 选择免费 API / 付费 API：仅在已真正选中某条（有配置）时才持久化为模式，否则保留原模式
                    if (selMode == "free_api") {
                        if (AiChatManager.hasFreeApiConfig(ctx, currentUserId)) {
                            AiChatManager.setMode(ctx, currentUserId, "free_api")
                        }
                    } else if (selMode == "paid_api") {
                        // paid_api 配置已在 PaidApiSelectScreen 选中时通过 savePaidApiConfig 持久化
                        AiChatManager.setMode(ctx, currentUserId, "paid_api")
                    } else {
                        AiChatManager.setMode(ctx, currentUserId, selMode)
                    }
                    if (selMode == "personal" && pKey.isNotBlank()) {
                        AiChatManager.savePersonalConfig(ctx, currentUserId, pKey, pUrl, pModel)
                    }
                    if (traitsChanged) AiChatManager.setPersonaSwitch(ctx, currentUserId, true)
                    // 高级选项：离开面板即保存（按用户填写的设定运行）
                    AiChatManager.setScenario(ctx, currentUserId, advScenario)
                    AiChatManager.setMyRole(ctx, currentUserId, advMyRole)
                    AiChatManager.setAiRole(ctx, currentUserId, advAiRole)
                    AiChatManager.setMultiOutput(ctx, currentUserId, advMulti)
                    AiChatManager.setMemoryEnabled(ctx, currentUserId, memoryEnabled)
                    android.widget.Toast.makeText(ctx, "已保存设置", android.widget.Toast.LENGTH_SHORT).show()
                    showAiUsage = false
                }

                Dialog(
                    onDismissRequest = { saveAiUsageAndClose() },
                    // 窗口铺到状态栏后面、由页面自己画白底：否则状态栏区域露出系统灰底，
                    // 且 dimAmount=0 无遮罩时会透出后面聊天界面的「AI」标题（与转账/红包页同一套处理）。
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false
                    )
                ) {
                    // 自定义 Dialog 窗口动画：整个全屏界面从右侧向左滑入（替代系统默认的底部弹出动画）
                    val aiUsageDialogView = LocalView.current
                    SideEffect {
                        var p: Any? = aiUsageDialogView.parent
                        while (p != null) {
                            if (p is DialogWindowProvider) {
                                p.window?.setWindowAnimations(R.style.WindowRightSlide)
                                break
                            }
                            p = (p as? android.view.View)?.parent
                        }
                    }
                    // 彻底清除 Dialog 默认灰色状态栏蒙层 → 白底 + 深色图标
                    SetDialogSystemBarColors(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.WHITE,
                        lightStatusBars = true
                    )
                    // 白底全屏铺满（覆盖状态栏/导航栏区域，消除顶部灰色长条）
                    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).imePadding()) {
                        // 全屏「使用」界面：从右侧滑入，覆盖整个屏幕（不再是大弹窗）
                        androidx.compose.animation.AnimatedVisibility(
                            visible = true,
                            modifier = Modifier.align(Alignment.CenterEnd),
                            enter = slideInHorizontally(initialOffsetX = { it }),
                            exit = slideOutHorizontally(targetOffsetX = { it })
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                // 顶部固定栏：标题 + 关闭（仅顶栏避让状态栏，避免被时钟遮挡）
                                Row(
                                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("AI 使用", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
                                    Text("✕", fontSize = 20.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                        modifier = Modifier.clickable { saveAiUsageAndClose() }.padding(4.dp))
                                }
                                HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider())

                                // 中部可滚动内容
                                Column(
                                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)
                                ) {
                                    // 当前状态摘要：模式 / 今日已用 / 剩余余额（重要信息前置）
                                    val modeLabel = when (selMode) {
                                        "paid_api" -> "付费 API"
                                        "personal" -> "个人 API"
                                        else -> "免费 API"
                                    }
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(com.aurora.chat.ui.theme.appCardBg())
                                            .padding(horizontal = 14.dp, vertical = 12.dp)
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("当前状态", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                Spacer(Modifier.weight(1f))
                                                Text(modeLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Column(
                                                    Modifier.weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .clickable(
                                                            indication = null,
                                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                                        ) { showAiUsage = false; showUsageHistory = true }
                                                        .padding(4.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text("今日已用", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                        Spacer(Modifier.width(2.dp))
                                                        Text("›", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                                    }
                                                    Text("${aiUsageState.value.first}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                                                }
                                                Column(Modifier.weight(1f)) {
                                                    Text("剩余余额", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                    Text(
                                                        when {
                                                            aiUsageState.value.second >= Long.MAX_VALUE -> "无限"
                                                            aiUsageState.value.second < 0L -> "加载中…"
                                                            else -> "${aiUsageState.value.second}"
                                                        },
                                                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))

                                    // 使用付费 API（用户贡献、商业化，消耗 Token 余额）：点击切入独立全屏选择界面
                                    Row(
                                        Modifier.fillMaxWidth().clickable { selMode = "paid_api"; showAiUsage = false; showPaidApiSelect = true }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = selMode == "paid_api", onClick = { selMode = "paid_api"; showAiUsage = false; showPaidApiSelect = true })
                                        Column(Modifier.weight(1f)) {
                                            Text("使用付费 API", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                                            Text("消耗token余额，AI输出速度更快限流更宽", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                        }
                                    }

                                    // 个人 API
                                    Row(
                                        Modifier.fillMaxWidth().clickable { selMode = "personal" }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = selMode == "personal", onClick = { selMode = "personal" })
                                        Column(Modifier.weight(1f)) {
                                            Text("使用个人 API", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                                            Text("使用自己的密钥，不受余额限制", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                        }
                                    }

                                    // 选择免费 API（由用户贡献、开发者审核；点击切入独立全屏选择界面，选中后真正调用）
                                    Row(
                                        Modifier.fillMaxWidth().clickable { selMode = "free_api"; showAiUsage = false; showFreeApiSelect = true }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = selMode == "free_api", onClick = { selMode = "free_api"; showAiUsage = false; showFreeApiSelect = true })
                                        Column(Modifier.weight(1f)) {
                                            Text("选择免费 API", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                                            Text("由用户贡献，开发者审核后可用", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                        }
                                    }

                                    if (selMode == "personal") {
                                        Spacer(Modifier.height(10.dp))
                                        OutlinedTextField(
                                            value = pKey, onValueChange = { pKey = it },
                                            label = { Text("API Key") }, singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = pUrl, onValueChange = { pUrl = it },
                                            label = { Text("Base URL（如 https://api.deepseek.com/v1）") }, singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = pModel, onValueChange = { pModel = it },
                                            label = { Text("模型名（如 deepseek-chat）") }, singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    Spacer(Modifier.height(16.dp))



                                    // 记忆增强（常驻开关，独立于高级选项折叠区）
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("记忆增强", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                                            Text("开启后自动读取并维护长期记忆 memory.md，跨对话记住偏好；关闭不读取、不维护，文件保留", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Switch(checked = memoryEnabled, onCheckedChange = { memoryEnabled = it })
                                    }
                                    Spacer(Modifier.height(8.dp))




                                    // 高级选项（会员体系已移除，所有用户均可查看与使用）
                                    Row(
                                        Modifier.fillMaxWidth().clickable { showAdvancedOptions = !showAdvancedOptions }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("高级选项", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                                        Spacer(Modifier.weight(1f))
                                        Text(if (showAdvancedOptions) "收起" else "展开", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                        Icon(
                                            imageVector = if (showAdvancedOptions) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null, tint = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    if (showAdvancedOptions) {
                                        Column(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                                .background(com.aurora.chat.ui.theme.appCardBg()).padding(12.dp)
                                        ) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("场景", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    advScenario = ""
                                                    AiChatManager.setScenario(ctx, currentUserId, "")
                                                }) {
                                                    Text("清空", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Box(Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = advScenario, onValueChange = { advScenario = it },
                                                    enabled = true,
                                                    placeholder = { Text("例如：你们在一艘太空飞船上", color = com.aurora.chat.ui.theme.appTextHint()) },
                                                    singleLine = false, modifier = Modifier.fillMaxWidth().height(72.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Text("你的角色设定", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("你在对话中扮演的身份（用户本人）", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    advMyRole = ""
                                                    AiChatManager.setMyRole(ctx, currentUserId, "")
                                                }) {
                                                    Text("清空", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Box(Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = advMyRole, onValueChange = { advMyRole = it },
                                                    enabled = true,
                                                    placeholder = { Text("例如：一名舰长", color = com.aurora.chat.ui.theme.appTextHint()) },
                                                    singleLine = false, modifier = Modifier.fillMaxWidth().height(72.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Text("AI 的角色设定", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("让 AI 扮演的身份", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    advAiRole = ""
                                                    AiChatManager.setAiRole(ctx, currentUserId, "")
                                                }) {
                                                    Text("清空", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Box(Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = advAiRole, onValueChange = { advAiRole = it },
                                                    enabled = true,
                                                    placeholder = { Text("例如：飞船的人工智能助手", color = com.aurora.chat.ui.theme.appTextHint()) },
                                                    singleLine = false, modifier = Modifier.fillMaxWidth().height(72.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("AI 多次输出", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                                                    Text("开启后 AI 可自行决定是否分多条短消息回复（如追问、分段），而非一次输出一大段", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                }
                                                Spacer(Modifier.width(12.dp))
                                                Switch(checked = advMulti, onCheckedChange = { advMulti = it })
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(16.dp))

                                    // 开启新对话：动态渐变（同下载悬浮窗蓝粉流动效果），位于节省模式与性格调试之间
                                    val floatTrans = rememberInfiniteTransition()
                                    val floatT by floatTrans.animateFloat(
                                        initialValue = 0f, targetValue = 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(durationMillis = 4000, easing = LinearEasing),
                                            repeatMode = RepeatMode.Restart
                                        )
                                    )
                                    val floatColors = listOf(
                                        0xFF38BDF8.toInt(), 0xFFEC4899.toInt(),
                                        0xFFF9A8D4.toInt(), 0xFF7DD3FC.toInt(), 0xFF38BDF8.toInt()
                                    )
                                    fun lerpFloatColor(tt: Float): Int {
                                        val seg = (floatColors.size - 1) * tt
                                        val i = seg.toInt().coerceIn(0, floatColors.size - 2)
                                        val f = seg - i
                                        val c1 = floatColors[i]; val c2 = floatColors[i + 1]
                                        val r = ((c1 shr 16 and 0xFF) * (1 - f) + (c2 shr 16 and 0xFF) * f).toInt()
                                        val g = ((c1 shr 8 and 0xFF) * (1 - f) + (c2 shr 8 and 0xFF) * f).toInt()
                                        val b = ((c1 and 0xFF) * (1 - f) + (c2 and 0xFF) * f).toInt()
                                        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
                                    }
                                    val g1 = Color(lerpFloatColor(floatT))
                                    val g2 = Color(lerpFloatColor((floatT + 0.5f) % 1f))

                                    // 开启性格调试：性格/自定义设定（CustomTrait）仅在开启时注入系统提示并生效；关闭时忽略（默认关闭）
                                    val togglePersonalityDebug: (Boolean) -> Unit = { on ->
                                        personalityDebug = on
                                        AiChatManager.setPersonalityDebug(ctx, currentUserId, on)
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().clickable { togglePersonalityDebug(!personalityDebug) }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("开启性格调试", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                                            Text(
                                                if (personalityDebug) "已开启：性格/自定义设定将注入并生效" else "已关闭：性格设定暂不生效",
                                                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                                            )
                                        }
                                        Switch(checked = personalityDebug, onCheckedChange = togglePersonalityDebug)
                                    }

                                    // 性格调试
                                    Text("性格调试", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                                    Spacer(Modifier.height(4.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        val used = traits.sum().toInt()
                                        Text("已分配 $used% / 100%", fontSize = 12.sp,
                                            color = if (used > 100) Color(0xFFDC2626) else com.aurora.chat.ui.theme.appTextSecondary())
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    traitLabels.forEachIndexed { i, label ->
                                        // 5 维总和上限 100：拖动一个维度时按比例压缩其余维度
                                        val traitOnValueChange: (Float) -> Unit = { newVal ->
                                            val old = traits[i]
                                            val othersSum = traits.sum() - old
                                            val v = newVal.coerceIn(0f, 100f)
                                            if (v + othersSum <= 100f) {
                                                traits[i] = v
                                            } else if (othersSum > 0f) {
                                                val scale = (100f - v) / othersSum
                                                for (j in traits.indices) if (j != i) traits[j] = traits[j] * scale
                                                traits[i] = v
                                            } else {
                                                traits[i] = 100f
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(label, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.width(48.dp))
                                            Slider(
                                                value = traits[i],
                                                onValueChange = traitOnValueChange,
                                                valueRange = 0f..100f,
                                                modifier = Modifier.weight(1f),
                                                // 自定义轨道铺满整条，消除圆球左右默认留白（不能用负 padding，否则闪退）
                                                track = { positions ->
                                                    Box(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(RoundedCornerShape(3.dp))
                                                            .background(com.aurora.chat.ui.theme.appDivider())
                                                    ) {
                                                        Box(
                                                            Modifier
                                                                .fillMaxWidth((positions.value / 100f).coerceIn(0f, 1f))
                                                                .height(6.dp)
                                                                .clip(RoundedCornerShape(3.dp))
                                                                .background(Color(0xFF1E40AF))
                                                        )
                                                    }
                                                },
                                                thumb = {
                                                    Box(
                                                        Modifier
                                                            .size(22.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xFF1E40AF))
                                                    )
                                                }
                                            )
                                        Text("${traits[i].toInt()}%", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                            modifier = Modifier.width(44.dp).padding(start = 4.dp))
                                        }
                                        Spacer(Modifier.height(10.dp))
                                    }
                                    // 极端（最后一条性格设定）下方：自定义入口，仅此一处，不绑定任何维度
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .zIndex(10f)
                                            .clickable { showCustomDialog = true; customDialogText = "" }
                                            .padding(vertical = 6.dp)
                                    ) {
                                        Text("＋ 自定义设定（在 5 条调试之外额外添加）", fontSize = 12.sp, color = Color(0xFF1E40AF))
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    // 自定义设定列表（按字面意思注入，可调节权重）
                                    if (customs.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("已添加的自定义设定", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                                        Text("按字面意思注入，右侧可调节权重，点击 ✕ 删除", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                        Spacer(Modifier.height(8.dp))
                                        customs.forEachIndexed { ci, c ->
                                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(c.text, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                                }
                                                Slider(
                                                    value = c.pct,
                                                    onValueChange = { customs[ci] = c.copy(pct = it) },
                                                    valueRange = 0f..100f,
                                                    modifier = Modifier.weight(1f),
                                                    track = { positions ->
                                                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(com.aurora.chat.ui.theme.appDivider())) {
                                                            Box(Modifier.fillMaxWidth((positions.value / 100f).coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E40AF))) {}
                                                        }
                                                    },
                                                    thumb = { Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF1E40AF))) {} }
                                                )
                                                Text("${c.pct.toInt()}%", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                                    modifier = Modifier.width(40.dp))
                                                Text("✕", fontSize = 14.sp, color = Color(0xFFDC2626),
                                                    modifier = Modifier.clickable { customs.removeAt(ci) }.padding(start = 4.dp, end = 2.dp))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }


                            }
                        }
                        // 自定义性格设定输入弹窗（液态模式为 Box 覆盖层，须位于 Dialog 窗口内部才能全屏显示）
                            if (showCustomDialog) {
                                AlertDialog(
                                    onDismissRequest = { showCustomDialog = false; customDialogText = "" },
                                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                                    title = { Text("自定义设定", fontWeight = FontWeight.Bold) },
                                    text = {
                                        OutlinedTextField(
                                            value = customDialogText,
                                            onValueChange = { customDialogText = it },
                                            label = { Text("输入自定义内容（按字面意思注入）") },
                                            placeholder = { Text("例如：回答时先给结论再展开") },
                                            singleLine = false,
                                            modifier = Modifier.fillMaxWidth().height(120.dp)
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val txt = customDialogText.trim()
                                            if (txt.isNotBlank()) customs.add(CustomTrait(txt, 50f))
                                            showCustomDialog = false; customDialogText = ""
                                        }) { Text("添加", color = Color(0xFF1E40AF)) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showCustomDialog = false; customDialogText = "" }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                                    }
                                )
                            }
                    }
                }

            }

            // ── AI 时间点弹窗（位于主聊天界面，不在 DeepSeek使用 面板内）──
            if (showTimePointDialog) {
                var customHour by remember { mutableStateOf("") }
                var customMinute by remember { mutableStateOf("00") }
                Dialog(onDismissRequest = { showTimePointDialog = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = com.aurora.chat.ui.theme.appSurface()) {
                        Column(Modifier.padding(20.dp).widthIn(min = 280.dp)) {
                            Text("设置时间点", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Spacer(Modifier.height(12.dp))
                            Text("快捷选择", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("早上 6:00" to "06:00", "中午 11:00" to "11:00", "晚上 18:00" to "18:00").forEach { (label, time) ->
                                    Box(
                                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                                            .clickable {
                                                showTimePointDialog = false
                                                currentTimePoint = "当前时间设定为：$time"
                                                AiChatManager.setTimePoint(ctx, currentUserId, "当前时间设定为：$time")
                                                val timeText = "设定时间点：$time"
                                                val timeMsg = ChatMsg.create(
                                                    serverId = System.currentTimeMillis() * 10000L + (0..9999).random(),
                                                    text = timeText, isMine = false, fromUserId = 0L,
                                                    isSystemNotice = true, isNew = true,
                                                    createdAt = System.currentTimeMillis() / 1000
                                                )
                                                messages.add(timeMsg)
                                                saveLocalChatMessages(ctx, currentUserId, AiConversationSession.storageKey(), messages.filter { !it.isSystemNotice })
                                                ChatViewModel.updateConversationPreview(friendId, timeText, System.currentTimeMillis() / 1000)
                                            }.padding(vertical = 10.dp, horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) { Text(label, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Text("自定义时间", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customHour, onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 2) customHour = v },
                                    placeholder = { Text("时", fontSize = 13.sp) }, singleLine = true,
                                    modifier = Modifier.width(60.dp)
                                )
                                Text(" : ", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                                OutlinedTextField(
                                    value = customMinute, onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 2) customMinute = v },
                                    placeholder = { Text("分", fontSize = 13.sp) }, singleLine = true,
                                    modifier = Modifier.width(60.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Button(onClick = {
                                    val h = customHour.padStart(2, '0').take(2)
                                    val m = customMinute.padStart(2, '0').take(2)
                                    if (h.toIntOrNull() != null && h.toInt() in 0..23 && m.toIntOrNull() != null && m.toInt() in 0..59) {
                                        showTimePointDialog = false
                                        val time = "$h:$m"
                                        currentTimePoint = "当前时间设定为：$time"
                                        AiChatManager.setTimePoint(ctx, currentUserId, "当前时间设定为：$time")
                                        val timeText = "设定时间点：$time"
                                        val timeMsg = ChatMsg.create(
                                            serverId = System.currentTimeMillis() * 10000L + (0..9999).random(),
                                            text = timeText, isMine = false, fromUserId = 0L,
                                            isSystemNotice = true, isNew = true,
                                            createdAt = System.currentTimeMillis() / 1000
                                        )
                                        messages.add(timeMsg)
                                        saveLocalChatMessages(ctx, currentUserId, AiConversationSession.storageKey(), messages.filter { !it.isSystemNotice })
                                        ChatViewModel.updateConversationPreview(friendId, timeText, System.currentTimeMillis() / 1000)
                                    } else {
                                        android.widget.Toast.makeText(ctx, "请输入有效时间（0-23时，0-59分）", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("确认", fontSize = 13.sp) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showTimePointDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                            }
                        }
                    }
                }
            }

            // ── 方案编辑弹窗（构建前可改方案：逐行 = 一个步骤）──
            if (showPlanEdit) {
                Dialog(onDismissRequest = { showPlanEdit = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = com.aurora.chat.ui.theme.appSurface()) {
                        Column(Modifier.padding(18.dp).widthIn(min = 300.dp, max = 460.dp)) {
                            Text("编辑方案", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Spacer(Modifier.height(4.dp))
                            Text("每行一个步骤；保存后方案卡片立即更新（构建开工后就不能再改）", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            Spacer(Modifier.height(10.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = planEditText,
                                onValueChange = { planEditText = it },
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                placeholder = { Text("1. 第一步\n2. 第二步", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()) }
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showPlanEdit = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                                Spacer(Modifier.width(6.dp))
                                TextButton(onClick = { savePlanStepsFromEditor() }) {
                                    Text("保存", color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            // ── AI 确认发言弹窗（位于主聊天界面，不在 DeepSeek使用 面板内）──
            if (showConfirmSpeechDialog) {
                Dialog(onDismissRequest = { showConfirmSpeechDialog = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = com.aurora.chat.ui.theme.appSurface()) {
                        Column(Modifier.padding(20.dp).widthIn(min = 260.dp)) {
                            Text("确认发言", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Spacer(Modifier.height(12.dp))
                            Text(if (confirmSpeechEnabled) "当前已开启确认发言模式，AI 不会自动回复" else "开启后，AI 不会自动回复，需要手动确认后才回复",
                                fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                TextButton(onClick = { showConfirmSpeechDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                                if (confirmSpeechEnabled) {
                                    Button(onClick = {
                                        confirmSpeechEnabled = false
                                        AiChatManager.setConfirmSpeech(ctx, currentUserId, false)
                                        showConfirmSpeechDialog = false
                                        android.widget.Toast.makeText(ctx, "已关闭确认发言模式", android.widget.Toast.LENGTH_SHORT).show()
                                    }) { Text("关闭", color = Color.White) }
                                } else {
                                    Button(onClick = {
                                        confirmSpeechEnabled = true
                                        AiChatManager.setConfirmSpeech(ctx, currentUserId, true)
                                        showConfirmSpeechDialog = false
                                        android.widget.Toast.makeText(ctx, "已开启确认发言模式，AI 将不再自动回复", android.widget.Toast.LENGTH_SHORT).show()
                                    }) { Text("开启", color = Color.White) }
                                }
                            }
                        }
                    }
                }
            }


            // ── AI「图片」首次使用提示：告知所选模型可能不支持图片识别（如 DeepSeek 为纯文本模型）──
            if (showAiImageNotice) {
                Dialog(onDismissRequest = { showAiImageNotice = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = com.aurora.chat.ui.theme.appSurface()) {
                        Column(Modifier.padding(20.dp).widthIn(min = 280.dp)) {
                            Text("图片识别提示", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Spacer(Modifier.height(12.dp))
                            Text("图片会以真实图片形式发送给 AI，但如果当前模型不支持图片识别，AI 将无法看到图片内容。",
                                fontSize = 14.sp, color = Color(0xFF4B5563), lineHeight = 20.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("例如 DeepSeek 官方模型为纯文本模型，不支持图片识别；如需 AI 看图，请在个人 API 中配置支持视觉的多模态模型（如通义千问 VL、GPT-4o、GLM-4V 等）。",
                                fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), lineHeight = 19.sp)
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    showAiImageNotice = false
                                    ctx.getSharedPreferences("aurora_ai_notice", Context.MODE_PRIVATE)
                                        .edit().putBoolean("image_vision_notice", true).apply()
                                }) { Text("知道了", color = Color(0xFF1E40AF)) }
                                Button(onClick = {
                                    showAiImageNotice = false
                                    ctx.getSharedPreferences("aurora_ai_notice", Context.MODE_PRIVATE)
                                        .edit().putBoolean("image_vision_notice", true).apply()
                                    aiImagePicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) { Text("去选图", color = Color.White) }
                            }
                        }
                    }
                }
            }

            // 开启新对话确认弹窗（清空 AI 对话全部记录）
            if (showNewChatConfirm) {
                AlertDialog(
                    onDismissRequest = { showNewChatConfirm = false },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("开启新对话", fontWeight = FontWeight.Bold) },
                    text = { Text("将清空当前 AI 对话的全部记录且无法恢复，确定要开启新对话吗？") },
                    confirmButton = {
                        TextButton(onClick = {
                            // 界面先把确认弹窗关掉；真正的清空在下面独立协程中按
                            // 「先中断在途回复 → 等它真正停止 → 再清空」的安全序列执行。
                            showNewChatConfirm = false
                            scope.launch {
                                // 完整安全序列：① 中断在途回复并等待其停止 → ② 新会话代次隔离 → ③ 清空并持久化
                                resetAiConversation(ctx, currentUserId, messages)
                                // 关闭 AI 使用面板；成功后提示即返回控制，不再做任何后续工作（确认成功即停）
                                showAiUsage = false
                                android.widget.Toast.makeText(ctx, "已开启新对话", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("确定", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewChatConfirm = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                    }
                )
            }

            // ── 入群申请横幅（仅群聊且是管理员/群主）──
            if (isGroupChat(friendId)) {
                // 群通知设置：进群会话即刷新一次，撤回提示等本地合成的提示按该群设置渲染
                LaunchedEffect(friendId) {
                    com.aurora.chat.data.local.GroupNotifyPrefs.load(-(friendId + 1000))
                }
                // 合并到一个 LaunchedEffect：先获取角色，再启动监听和轮询
                LaunchedEffect(friendId) {
                    // 每次切换会话先重置，避免沿用上一个非成员群的旧状态（接口失败时也保持默认）
                    isAdminOrOwner = false
                    isNotGroupMember = false
                    isGroupOwner = false
                    // 1. 获取当前用户在该群的角色
                    try {
                        val gid = -(friendId + 1000)
                        val info = AuroraApi.getGroupInfo(gid)
                        if (info.success && info.data != null) {
                            val role = info.data!!.optString("user_role", "")
                            val creatorId = info.data!!.optLong("creator_id", 0)
                            isGroupOwner = role == "owner" || creatorId == AuroraApi.currentUserId
                            isAdminOrOwner = role == "owner" || role == "admin" || AuroraApi.currentUserId == 1L || creatorId == AuroraApi.currentUserId
                            // 非群成员（含「入群需审核」待审核态）：user_role 为空，禁止当作正常会话处理。
                            // 但「我被移出/已退出」是另一回事：历史属于我自己的本地数据，必须留下可看，
                            // 由 isExitedGroup 单独走只读态，不能落进这个会清空消息的分支。
                            isNotGroupMember = isGroupChat(friendId) && role.isEmpty() && !isExitedGroup
                            // 自愈：服务端说我是成员，本地却还留着退出记录——典型场景是刚通过
                            // 搜群号/群资料重新加回来，记录要等会话列表刷新才清。不清的话底部
                            // 一直显示「你已被踢出群聊」，得退出重进才恢复。立即删记录并让
                            // isExitedGroup 重算；轮询与上滑翻页的守卫都是直接查 store 的，
                            // 记录一删它们自然恢复，无需任何重启。
                            if (role.isNotEmpty() && isExitedGroup) {
                                com.aurora.chat.data.local.GroupExitStore.remove(ctx, -(friendId + 1000))
                                groupExitTick++
                            }

                            // 群公告弹窗：进群聊时，若有 popup_enabled 且本机未看过的公告则弹出
                            // 仅在本人是群成员时弹（非成员/待审核连公告都不应看到）
                            if (role.isNotEmpty() && !isExitedGroup) {
                                val anns = parseGroupAnnouncementsJson(info.data!!)
                                val seenKey = "ann_seen_${AuroraApi.currentUserId}_$friendId"
                                val seenRaw = com.aurora.chat.data.local.DataCache.loadSeenAnnouncements(ctx, seenKey)
                                val seen: Set<String> = try {
                                    val a = org.json.JSONArray(seenRaw ?: "[]")
                                    buildSet { for (i in 0 until a.length()) add(a.optString(i)) }
                                } catch (_: Exception) { emptySet() }
                                val target = anns.firstOrNull { it.popupEnabled && it.text.isNotBlank() && it.text !in seen }
                                if (target != null) {
                                    // 立即写入已看记录：退出重进不会再弹同一条
                                    val arr = org.json.JSONArray()
                                    (seen + target.text).forEach { arr.put(it) }
                                    com.aurora.chat.data.local.DataCache.saveSeenAnnouncements(ctx, seenKey, arr.toString())
                                    popupAnnouncement = target
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (!isAdminOrOwner) return@LaunchedEffect

                    // 2. 首次加载申请数量
                    try {
                        val gid = -(friendId + 1000)
                        val r = AuroraApi.getJoinRequestCount(gid)
                        if (r.success && r.data != null) {
                            pendingCount = r.data!!.optInt("count", 0)
                        }
                    } catch (_: Exception) {}

                    // 3. 事件驱动监听：PendingJoinRequests 由 TCP 推送设置，不再轮询
                    launch {
                        // 仅通过 TCP 推送触发刷新，初始计数已在步骤 2 拉取
                        // 使用 3 秒间隔的轻量级标记检查（不调用 API）
                        while (true) {
                            delay(3000)
                            if (com.aurora.chat.PendingJoinRequests.hasUpdate &&
                                com.aurora.chat.PendingJoinRequests.groupConvId == friendId) {
                                com.aurora.chat.PendingJoinRequests.hasUpdate = false
                                try {
                                    val gid = -(friendId + 1000)
                                    val r = AuroraApi.getJoinRequestCount(gid)
                                    if (r.success && r.data != null) {
                                        pendingCount = r.data!!.optInt("count", 0)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                // 安全兜底：一旦确认未加入群（含待审核），立即清空可能已加载的群消息，避免越权泄露历史
                LaunchedEffect(isNotGroupMember) {
                    if (isNotGroupMember) messages.clear()
                }

                // ── 群公告弹窗（以弹窗形式展示 + 需确认收到）──
                popupAnnouncement?.let { ann ->
                    AlertDialog(
                        onDismissRequest = { popupAnnouncement = null },
                        containerColor = com.aurora.chat.ui.theme.appSurface(),
                        title = { Text("群公告", fontWeight = FontWeight.SemiBold) },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text(ann.text, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 21.sp)
                                if (ann.images.isNotEmpty()) {
                                    Spacer(Modifier.height(10.dp))
                                    coil.compose.AsyncImage(
                                        model = resolveMediaUrl(ann.images.first()),
                                        contentDescription = "公告图片",
                                        modifier = Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.FillWidth
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            if (ann.needConfirm && !ann.confirmedByMe) {
                                Text(
                                    "确认收到", color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable {
                                        popupAnnouncement = null
                                        confirmedAnnouncementTexts.add(ann.text)
                                        scope.launch {
                                            try {
                                                AuroraApi.confirmGroupAnnouncement(-(friendId + 1000), ann.text)
                                                DataCache.saveConfirmedAnnouncementTexts(
                                                    ctx, currentUserId, -friendId - 1000,
                                                    confirmedAnnouncementTexts.toList()
                                                )
                                            } catch (_: Exception) {}
                                        }
                                    }
                                )
                            } else {
                                Text(
                                    "我知道了", color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { popupAnnouncement = null }
                                )
                            }
                        }
                    )
                }

                // ── 公告「已收到」名单抽屉（群主/管理员）──
                annConfirmSheetText?.let { txt ->
                    ModalBottomSheet(
                        onDismissRequest = { annConfirmSheetText = null },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = com.aurora.chat.ui.theme.appSurface()
                    ) {
                        AnnouncementConfirmSheet(groupId = -(friendId + 1000), text = txt)
                    }
                }

                if (isAdminOrOwner && pendingCount > 0) {
                    Box(Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("有 $pendingCount 条入群申请", fontSize = 13.sp, color = Color(0xFF856404), modifier = Modifier.weight(1f))
                            Text("查看", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    scope.launch {
                                        try {
                                            val gid = -(friendId + 1000)
                                            val r = AuroraApi.getJoinRequests(gid)
                                            if (r.success && r.data != null) {
                                                onOpenJoinRequestFullScreen(r.data, gid)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }

                // ── 入群申请审批面板 ──
            }

            // ── 非好友私聊横幅（与上方入群申请横幅同一位置，文案与触发机制不同）──
            // 旧实现把「好友列表里没有这个人」当成明确非好友，结果除了群聊所有私聊都误报横幅。
            // 现在横幅只由「本地持久化标记」驱动：
            //   · 标记只能由服务端 certain=true 的权威结论 / 本地删除好友成功写入（= 百分百判定）；
            //   · 任何好友证据（好友列表命中、服务端说好友、返回「已经是好友」）立即解除标记；
            //   · 标记落盘，离线也挂得住；联网时重新核验，是好友就当场收掉。
            // 群聊 / 自己 / AI / 通知由 isPrivateChat 硬门控排除，永远不画。
            if (isPrivateChat(friendId, currentUserId)) {
                // 首帧直接读盘：不等网络，离线状态也能立刻正确显示（或立刻不显示）。
                var showStrangerBanner by remember(friendId, currentUserId) {
                    mutableStateOf(
                        ChatRepository.isMarkedStranger(friendId) && !ChatRepository.isLocalFriend(friendId)
                    )
                }

                LaunchedEffect(friendId, currentUserId, friendStatusRefresh) {
                    val bannerBefore = showStrangerBanner

                    // 0) 本地好友证据优先：是好友就解除标记，绝不显示横幅。
                    val localFriendAtStart = ChatRepository.isLocalFriend(friendId)
                    if (localFriendAtStart) ChatRepository.clearStrangerMark(friendId)
                    showStrangerBanner = ChatRepository.isMarkedStranger(friendId) && !localFriendAtStart
                    isFriendChat = if (localFriendAtStart) true else null
                    payDisabled = false
                    payBlockReason = null

                    // 1) 好友列表：只提供正向证据。失败 / 空列表 / 缺项一律不当成非好友，
                    //    这也是「所有私聊都弹横幅」的直接修复点。
                    val listR = try { ChatRepository.getFriends() } catch (_: Exception) { null }
                    val listData = listR?.takeIf { it.success }?.data
                    val inList = listData?.any { it.id == friendId } == true
                    if (inList) {
                        ChatRepository.markAsFriend(friendId)
                        isFriendChat = true
                        showStrangerBanner = false
                    }

                    // 2) 服务端权威关系：唯一能「新增标记」的来源，必须 certain=true。
                    val relR = try { ChatRepository.getFriendStatus(friendId) } catch (_: Exception) { null }
                    val rel = relR?.takeIf { it.success }?.data
                    if (rel != null && rel.certain) {
                        if (rel.isFriend) {
                            ChatRepository.markAsFriend(friendId)
                            isFriendChat = true
                            showStrangerBanner = false
                        } else {
                            // 但凡检测到好友证据，都不许画横幅，并顺手清掉陈旧标记。
                            val anyFriendEvidence = inList || ChatRepository.isLocalFriend(friendId)
                            if (anyFriendEvidence) {
                                ChatRepository.clearStrangerMark(friendId)
                                showStrangerBanner = false
                            } else {
                                ChatRepository.markStranger(friendId)
                                isFriendChat = false
                                showStrangerBanner = true
                            }
                        }
                    }
                    // 请求失败 / 超时 / 旧服务端无 certain：标记维持原样（离线仍挂横幅），绝不新增误报。

                    // 3) 拉黑独立判定，同样只认明确结果。
                    val blockR = try { ChatRepository.isBlocked(friendId) } catch (_: Exception) { null }
                    val iAmBlocker = blockR?.success == true && blockR.data?.iAmBlocker == true
                    val iAmBlocked = blockR?.success == true && blockR.data?.isBlocked == true

                    // 4) 转账 / 红包门控与横幅同一判定，避免好友被误禁用。
                    when {
                        iAmBlocker -> { payDisabled = true; payBlockReason = "你已拉黑对方，无法转账或发红包" }
                        iAmBlocked -> { payDisabled = true; payBlockReason = "你已被对方拉黑，无法转账或发红包" }
                        showStrangerBanner -> { payDisabled = true; payBlockReason = "对方还不是你的好友，无法转账或发红包" }
                        else -> { payDisabled = false; payBlockReason = null }
                    }

                    if (showStrangerBanner != bannerBefore) {
                        com.aurora.chat.ErrorReporter.debug(
                            "FriendBanner",
                            "peer=$friendId banner=$showStrangerBanner inList=$inList " +
                                "certain=${rel?.certain} isFriend=${rel?.isFriend} peerHasMe=${rel?.peerHasMe}"
                        )
                    }
                }

                if (showStrangerBanner) {
                    Box(Modifier.fillMaxWidth().background(Color(0xFFFFF3CD)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("对方还不是你的好友", fontSize = 13.sp, color = Color(0xFF856404), modifier = Modifier.weight(1f))
                            Text("发送好友申请", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    showAddFriendGreeting = true
                                }.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }

            // 消息列表（用 Box + weight 避免 weight 直接放 LazyColumn 上导致 infinity 崩溃）
            // ── 边界阻尼回弹（微信/QQ 手感）──
            // MainActivity 全局设了 LocalOverscrollFactory = null（关掉平台 stretch/光晕回弹），
            // 于是列表滚到边就"刻死"。这里只给消息列表补一层 iOS 式 rubber band。
            //
            // 【关键：位移本身就是状态，且天然有界】
            // 上一版是「原始拖动量无限累加 + 渐近映射」：单手滑一次到不了上限，
            // 但快速接力时 raw 跨手势累积（只增不减），3-4 次就把位移顶到半个屏，
            // 叠加 clipToBounds 直接把整段消息裁掉 —— 就是"吞内容"的来源。
            // 而且 raw != 0 时拖动被 100% 吞进 raw，LazyColumn 拿不到滚动量，
            // 于是归位前再滑会"冻住"。
            //
            // 现改为经典 UIScrollView 阻尼累加（状态即位移）：
            //   位移 += 拖动增量 × (1 - |位移| / 上限)
            //   - 位移有界 → 接力再多次也顶不过上限，绝不会越拉越深、吞掉整段内容
            //   - 系数 1 → 0.02 渐降 → 起手 1:1 跟手，越拉越沉，但永不为 0（没有硬卡死的墙）
            //   - 上限 140dp → 最坏只裁掉一条窄边，不再"整段消失"
            //   - 收回方向 1:1 跟手，越过 0 的剩余量同一帧交还列表（归位前也能继续滑）
            // 阻尼尺度 = 消息区视口高度（布局时写入）。用的是 iOS UIScrollView 的原始
            // rubber band 微分形式：d(位移)/d(手指) = (1 - |位移| / 视口高)²
            //   - 位移 = 0 时系数 1.0 → 起手严格 1:1 跟手
            //   - 越拉系数越小，但**永远不为 0**，也没有任何 coerceIn 硬墙
            //   - 位移天然渐近到视口高，正常手速远远摸不到
            // 上一版用的是「固定 160dp 上限 + coerceIn 截断 + 系数下限 0.02」，
            // 拉到那个距离后被硬裁死 → 就是"超过固定距离就拉不动"。
            val overscrollDim = remember { mutableFloatStateOf(600f) }
            // 起手跟手系数：0.55 = iOS UIScrollView 原生值。
            // 系数 1.0 时轻轻一划就能拉出一大片，太重；0.55 手感和微信/QQ 一致。
            val rubberFollow = 0.55f
            // 【为什么不再做「视口外扩」】
            // 上上版为了让位移时边缘仍有内容，把列表按 (H + 2*extra) 测量、整体上移 extra，
            // 再用等量 contentPadding 抵消。这套在 reverseLayout 下根本不成立：
            //   reverseLayout 的内容是从**底部**开始堆的，index 0（最新消息）就贴着视口底边。
            //   视口加高 2*extra 时，多出来的空间全部被分配到**顶部**（组合更早的消息），
            //   底部方向一格内容都不会多出来 —— 因为最新消息下面本来就没有 item。
            // 于是位移一发生，底部露出的就是一条没有任何内容的背景带；而且裁剪边界
            // 与内容不同步，气泡滑到那条线上就被裁掉半截 —— 就是"下面一块背景、东西被它遮住"。
            //
            // 现在回到 iOS/微信真正用的模型：**整块列表作为一个整体平移**（graphicsLayer），
            // 内容与它的裁剪边界一起走，边缘露出的是聊天背景本身。
            // 位移全程只写图层矩阵，不触发布局、不重组、不重测 item。
            // 当前位移（px，带符号）。它既是状态也是显示量，天生有界。
            val overscrollShift = remember { mutableFloatStateOf(0f) }
            val overscrollScope = rememberCoroutineScope()
            val reboundJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            val overscrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.Drag) return Offset.Zero
                        // 手指一碰就取消回弹：位移停在当前位置，由拖动直接接管（可二次抬升）
                        reboundJob.value?.let { it.cancel(); reboundJob.value = null }
                        val cur = overscrollShift.floatValue
                        if (cur == 0f) return Offset.Zero
                        // 继续外拉：走阻尼（与 onPostScroll 同一公式），全部消费不泄漏给列表
                        val deepening = (cur > 0f && available.y > 0f) || (cur < 0f && available.y < 0f)
                        if (deepening) {
                            // 阻尼系数 = 0.55 / (1 + |位移| / (视口高/2))
                            //   - 位移 0 时系数 0.55（iOS 原生手感，轻划不会甩出一大片）
                            //   - 系数严格大于 0、永不归零 → 没有硬墙，可以一直拉
                            //   - 对拖动量积分是 log 增长：越拉越沉，但永远有响应
                            // 上一版用 (1 - 位移/视口高)²，接近视口高时系数归零 → 又是一堵墙
                            val room = 1f / (1f + abs(cur) / (overscrollDim.floatValue * 0.25f))
                            overscrollShift.floatValue = cur + available.y * rubberFollow * room
                            return available
                        }
                        // 收回：1:1 跟手；越过 0 的剩余量立刻交还 LazyColumn，同一帧完成交接
                        val next = cur + available.y
                        val clamped = if (cur > 0f) next.coerceAtLeast(0f) else next.coerceAtMost(0f)
                        overscrollShift.floatValue = clamped
                        return Offset(0f, clamped - cur)
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        // 只有列表滚不动还剩增量时才会走到这里 —— 即真正到边界了
                        if (source != NestedScrollSource.Drag || available.y == 0f) return Offset.Zero
                        reboundJob.value?.let { it.cancel(); reboundJob.value = null }
                        val cur = overscrollShift.floatValue
                        // 与 onPreScroll 完全相同的阻尼公式（保证往两个方向的手感一致：
                        // 同一段手指位移在"加深"和"加深"之间不会出现变化率突变）
                        val room = 1f / (1f + abs(cur) / (overscrollDim.floatValue * 0.25f))
                        overscrollShift.floatValue = cur + available.y * rubberFollow * room
                        return available
                    }

                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                        // 松手回弹：独立协程 spring 归零。不阻塞滚动回调，随时可被新手势打断接管。
                        val start = overscrollShift.floatValue
                        if (start != 0f) {
                            reboundJob.value?.cancel()
                            reboundJob.value = overscrollScope.launch {
                                animate(
                                    initialValue = start,
                                    targetValue = 0f,
                                    // 回弹必须果断：上一版 stiffness 420 要 400ms+ 才归零，
                                    // 这期间边缘那块背景一直挂在那儿，看起来像"空间没被内容盖住"。
                                    // 1100/0.92 大约 180ms 贴回，且不过冲。
                                    animationSpec = spring(dampingRatio = 0.92f, stiffness = 1100f)
                                ) { value, _ -> overscrollShift.floatValue = value }
                            }
                        }
                        return available
                    }
                }
            }
            @Composable
            fun ChatMessageListArea() {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 父容器也铺上聊天背景（默认灰；该好友设置过背景图片时为该图）：
                    // 回弹把列表整块平移时，露出的边缘是「同一块背景」，
                    // 不出现一块外来灰、把消息衬成「穿过它看不见」的视觉断层。
                    .chatBackground(friendId)
                    // 记录消息区真实高度，作为 rubber band 的阻尼尺度（视口高）
                    .onSizeChanged {
                        overscrollDim.floatValue = it.height.toFloat().coerceAtLeast(1f)
                    }
                    .nestedScroll(overscrollConnection)
                    // 只把回弹位移裁到消息区边界（= 屏幕内这块区域），
                    // 保证列表平移出去的部分不会盖到顶栏/输入栏上。
                    // 位移本身由 LazyColumn 的 graphicsLayer 施加，这里不做任何 offset。
                    .clipToBounds()
            ) {
            // 防御性去重：快速切 tab / 并发写入会使 messages 短暂含重复 id，
            // LazyColumn 要求 key 唯一，否则 measure 时抛 "Key was already used" 崩溃。
            // 渲染层用 distinctBy 保证重复 id 只显示一次（数据层保持原样供其他逻辑使用）。
            // 过滤掉历史遗留的「任务流程」独立消息：它已改为「渲染期从最新方案卡派生」，
            // 不过滤的话老会话最底部会再多出一张进度卡（与注入的那张重复）。
            val displayMessages = remember(aiDisplayRefresh.intValue) {
                derivedStateOf {
                    val showReasoning = AIUiPrefs.showReasoning(ctx)
                    val afterComplete = AIUiPrefs.showAfterComplete(ctx)
                    val streamingId = aiStreamMsgId.value
                    val writing = aiWriting.value
                    messages.distinctBy { it.id }
                        .filter { !it.text.startsWith(PLAN_LIVE_TAG) }
                        .filter { m ->
                            if (friendId != AI_CHAT_ID || m.isMine || m.isSystemNotice || m.fromUserId != AI_CHAT_ID) true
                            else {
                                // 占位文本恒空；工具哨兵 / 协议块由渲染层对可见项再按「可见正文」过滤一次，
                                // 这里只做轻量判断，避免流式每一帧都对全表跑字符串清洗。
                                val hasBody = m.text.isNotBlank() && m.text != AI_THINKING
                                // 显示思考开启时，思考内容为独立气泡，可以在正文之前先出现。
                                val hasThinking = showReasoning && m.reasoningText.isNotBlank()
                                // 整段显示开启且这条仍在生成：整条按住，等全部输出完成再一次性放开。
                                val hold = afterComplete && m.id == streamingId && writing
                                !hold && (hasBody || hasThinking)
                            }
                        }
                }
            }.value
            // 【性能·1】DELIVER 交付块是否存在：原先这行写在每个 item 里 ——
            // 既对全表做 O(n) 字符串扫描（每条可见气泡都扫一遍全部消息），
            // 又让「每一个气泡」都订阅整个 messages 列表：任何一条消息变化（尤其流式时每个 token）
            // 都会把屏幕上所有可见气泡全部重组。提到列表外用 derivedStateOf 只算一次，
            // 且只在结果真正翻转（false↔true）时才通知下游。
            val planAllDoneState = remember {
                derivedStateOf {
                    // 「方案已完成」= 交付块出现在**方案卡消息之后**的消息里。
                    // 旧写法是"会话里任何地方有交付块就算"——于是「生成方案」那一轮自带的交付块
                    // 会让方案卡一出生就显示成全部勾选(用户还没点构建就看到全勾)。现在要求交付必须
                    // 发生在方案卡**之后**，方案自己那一轮的交付不算数。
                    // 注意 indexOfLast 要排除尾部「实时进度卡」，它贴在对话末尾会把基准索引带偏。
                    val planIdx = messages.indexOfLast {
                        it.text.contains("[[PLAN_CARD]]") && !it.text.startsWith(PLAN_LIVE_TAG)
                    }
                    if (planIdx < 0) false
                    else (planIdx + 1 until messages.size).any { messages[it].text.contains(AITaskCompletion.DELIVER_OPEN) }
                }
            }
            // 【性能·2】被引用消息解析用的 id→消息 映射：按「消息条数」缓存，只有增删才重建。
            // 气泡只读这个普通 Map（不订阅 messages），不再各自 messages.firstOrNull{}/any{} 全表扫描。
            // 【任务流程卡】数据源 = 会话里**最新一张方案卡**（排除历史遗留的进度卡消息）。
            // 它以前是一条独立消息、贴在会话最末尾 —— 于是永远出现在所有回答的**下方**。
            // 现在改为「渲染期派生 + 只注入最新一条 AI 消息」，正好落在**交付总结上方**；
            // 步骤状态本来就写在这张方案卡里（见 updatePlanStep），所以重进会话/重启都不会丢。
            val livePlanState = remember {
                derivedStateOf {
                    val m = messages.lastOrNull { it.text.contains("[[PLAN_CARD]]") && !it.text.startsWith(PLAN_LIVE_TAG) }
                    val d = m?.let { parsePlanCard(it.text) }
                    if (d == null || d.steps.isEmpty()) null
                    else d.copy(progressOnly = true, title = "任务流程", overview = "", changePoints = emptyList(), docPath = "")
                }
            }
            // 最新一条 AI 消息的 id：进度卡只挂给它（这样它永远紧贴当前这轮回答，不会钉死在会话最底部）
            val lastAiMsgIdState = remember {
                derivedStateOf {
                    messages.lastOrNull {
                        !it.isMine && it.fromUserId == AI_CHAT_ID && it.text.isNotBlank() && it.text != AI_THINKING
                    }?.id ?: 0L
                }
            }
            val msgById = remember(messages.size) { messages.associateBy { it.id } }
            // 【性能·P1-1】流式增量 O(1) 定位：原先每个增量都 messages.indexOfFirst{id==msg.id} 做 O(n) 线性查找，
            // 历史越长越掉帧。索引快照按「消息条数」缓存——纯文本流式更新不改变条数，故映射只在增删消息时重建，
            // 流式期间恒定 O(1)。注意：元素 copy 不改变 size，映射依然有效；插入/删除改变 size 后自动重建为最新。
            val msgIndexById = remember(messages.size) { messages.withIndex().associate { it.value.id to it.index } }
            // 【性能·P0-3】滚动自动跟随节流：原先每个增量都 aiGlobalScope.launch{ scrollToItem(0) }，
            // 每秒 30+ 次程序化滚动与用户手势/anchor 钉位互相打架，是「滚动跳动」主因。改为 ≥90ms 节流，
            // 且只在贴底(非用户上滑)时跟随，用户手势始终优先。
            val scrollThrottle = remember { java.util.concurrent.atomic.AtomicLong(0L) }
            fun autoFollowBottom() {
                val now = System.currentTimeMillis()
                if (now - scrollThrottle.get() < 90L) return
                // 用户正在滑 → 立即停止跟随,不等 snapshotFlow 下一帧才翻转(见 maybeScrollToBottom 内同款说明)
                if (listState.isScrollInProgress && !programmaticScrolling) {
                    autoFollow = false
                    return
                }
                scrollThrottle.set(now)
                // 贴底(autoFollow)跟随最新；离开底部(!autoFollow)钉住 anchor 防漂移
                if (!autoFollow && anchorItemIndex >= 0) {
                    programmaticScroll(anchorItemIndex, anchorItemOffset)
                } else if (autoFollow) {
                    programmaticScroll(0, 0)
                }
            }
            // 【性能·3】倒序视图只建一次：原先每个 item 都 displayMessages.asReversed() 复制一份。
            val reversedMessages = displayMessages.asReversed()
            // ===== 会话内「时间分隔」状态 =====
            // 判定只看相邻两条消息的间隔，文案只需要精确到「天」：now 仅在跨天时推进，
            // 绝不每秒 tick —— 否则可见 item 会被无意义重建，列表跟着抖。
            val timeConvKey = remember(friendId) { "f_$friendId" }
            var hiddenTimeDividers by remember(timeConvKey) {
                mutableStateOf(com.aurora.chat.data.local.ChatTimeDividerPrefs.load(ctx, timeConvKey))
            }
            var timeMenuMsgId by remember(timeConvKey) { mutableStateOf(0L) }
            var timeNowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(60_000L)
                    val now = System.currentTimeMillis() / 1000
                    if (ChatTimeFormat.dayIndex(now) != ChatTimeFormat.dayIndex(timeNowSec)) {
                        timeNowSec = now
                    }
                }
            }
            // ===== 「已读显示」状态 =====
            // 默认关闭（只能在长按 →「消息信息」二级页里看）。
            // 开启后两种摆法：气泡下方一行浅灰小字 / 气泡左侧一行浅灰小字。
            // 设置页改完立即生效：ReadReceiptPrefs.version 是会触发重组的快照状态，
            // 只 remember(friendId) 的话切回来还停在旧值（「改了没反应，退出重进才对」）。
            val readReceiptMode = remember(friendId, com.aurora.chat.data.local.ReadReceiptPrefs.version) {
                com.aurora.chat.data.local.ReadReceiptPrefs.get(ctx)
            }
            val readReceiptOn = readReceiptMode != com.aurora.chat.data.local.ReadReceiptPrefs.MODE_INFO_ONLY
            // 小字摆左侧（true）还是气泡下方（false）
            val readReceiptLeft = readReceiptMode == com.aurora.chat.data.local.ReadReceiptPrefs.MODE_BUBBLE_LEFT
            var readStats by remember(friendId) {
                // 首帧就用本地缓存（见 ReadStatCache）：进会话立刻有字，不等网络。
                mutableStateOf(com.aurora.chat.data.local.ReadStatCache.all(ctx))
            }
            // 已读推送计数：每收到一次 message_read 就 +1，触发下面的批量刷新。
            // 走 StateFlow 收集（而不是直接读快照状态）：这个计数是 TCP 读线程写的，
            // StateFlow 保证写不丢、collect 必到，比跨线程写 mutableStateOf 稳。
            val readPushVersion by com.aurora.chat.PendingMessageRead.version.collectAsState()
            // 已读事件（推送自带 reader_id / last_read_id / read_at）：私聊直接本地落地，零请求
            val readEvent by com.aurora.chat.PendingMessageRead.events.collectAsState()
            // 「会话里最后一条消息」的本地 id：私聊的已读/未读标记只挂在它身上。
            // 依据：对方回了消息，就等于他看过了我上一条 —— 标记再留着没有信息量。
            val lastMsgKey = remember {
                derivedStateOf { messages.lastOrNull { !it.isSystemNotice }?.id ?: -1L }
            }.value
            // 最后一条自己发的消息的 serverId：发送成功拿到服务端 id 的那一刻会变，
            // 用它当副作用 key，才能在 ack 之后立刻补上这条的已读数据（否则 group 一直是 0 人）。
            val lastMineServerId = remember {
                derivedStateOf { messages.lastOrNull { it.isMine }?.serverId ?: 0L }
            }.value
            // 本会话是否已做过一次全量刷新（进会话刷一次，之后只补新增）
            var readDidInitialFetch by remember(friendId) { mutableStateOf(false) }
            // 已经「消费掉」的推送版本号。只有成功拿到数据后才推进它 —— 见下面的说明。
            var readLastPushVer by remember(friendId) { mutableStateOf(-1L) }
            // 手动重查：点一下气泡下面那行「未读」= 立刻把下面那个探针踢醒，不等它到点。
            //
            // 点击自己不再发请求 —— 请求只有一条路径（探针里那一次）。点击只负责「打断睡眠」，
            // 所以点十下也只会有一次请求（Channel 是 CONFLATED 的，多次踢合并成一次）。
            // 这样既没有两处请求互相覆盖的问题，也不会出现「点了没用、退出重进才对」。
            val readProbeKick = remember(friendId) {
                kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
            }
            // 私聊已读：推送里已经把「读到哪一条、什么时候读的」带回来了，
            // 这里直接落内存 + 缓存，一步到位，不发任何请求。
            // 之前是「收到推送 → 再问一次接口」，中间多一次往返，还要跟 messages.size
            // 变化引起的副作用重启抢跑；现在这条链路彻底不需要网络。
            LaunchedEffect(readEvent?.seq) {
                val ev = readEvent ?: return@LaunchedEffect
                if (!readReceiptOn || isGroupChat(friendId)) return@LaunchedEffect
                if (ev.groupId > 0L || ev.readerId != friendId || ev.lastReadId <= 0L) return@LaunchedEffect
                val at = if (ev.readAt > 0L) ev.readAt else System.currentTimeMillis() / 1000
                // 已读单调：≤ lastReadId 的、我发的消息全部算已读
                val hit = messages.filter { it.isMine && it.serverId in 1..ev.lastReadId }
                if (hit.isEmpty()) return@LaunchedEffect
                val add = hit.associate {
                    it.serverId to com.aurora.chat.data.api.ReadStat(it.serverId, 0, at)
                }
                readStats = mergeReadStats(readStats, add)
                com.aurora.chat.data.local.ReadStatCache.putAll(ctx, add)
            }
            LaunchedEffect(readReceiptOn, readPushVersion, messages.size, lastMineServerId, isGroupChat(friendId)) {
                if (!readReceiptOn) return@LaunchedEffect
                // 去抖：连续收发 / 连续已读推送只拉一次
                kotlinx.coroutines.delay(300L)
                val group = isGroupChat(friendId)
                val mine = messages.filter { it.isMine && !it.isSystemNotice && it.serverId > 0 }
                val myIds: List<Long> =
                    (if (group) mine else listOfNotNull(mine.lastOrNull()))
                        .map { it.serverId }
                        .takeLast(100)
                if (myIds.isEmpty()) return@LaunchedEffect
                // 只有两种情况需要联网：① 收到已读推送（对方真的读了）；② 本会话第一次进来。
                // 其余（滚动、新发消息）只补「缓存里还没有」的那几条 —— 已有数据一律不再问服务端。
                val pushed = readPushVersion != readLastPushVer
                val force = pushed || !readDidInitialFetch
                val need = if (force) myIds else myIds.filter {
                    com.aurora.chat.data.local.ReadStatCache.get(ctx, it) == null
                }
                if (need.isEmpty()) return@LaunchedEffect
                val got = runCatching { AuroraApi.fetchReadStats(need) }.getOrNull()?.data
                // 失败（断网 / 超时）不推进版本号：下一次触发会重新尝试。
                if (got == null) return@LaunchedEffect
                // 关键：这两个标记必须在**数据真正拿到之后**才推进。
                // 之前是先推进再请求 —— 而 messages.size 变化（来一条新消息）会重启这个副作用、
                // 把正在飞的请求取消掉，于是这条「已读刷新」就被吞了，界面上永远停在「未读」，
                // 只有退出会话重进（重新全量拉）才恢复。这就是「时好时坏」的根因。
                readLastPushVer = readPushVersion
                readDidInitialFetch = true
                readStats = mergeReadStats(readStats, got)
                com.aurora.chat.data.local.ReadStatCache.putAll(ctx, got)
                // 服务端没返回的 id（消息已删 / 不属于我）：按 0 人已读落一条缓存，
                // 否则它们永远是「缓存未命中」，每次触发都白问一次服务端。
                val missing = need.filter { it !in got }
                if (missing.isNotEmpty()) {
                    com.aurora.chat.data.local.ReadStatCache.putAll(
                        ctx,
                        missing.associateWith { com.aurora.chat.data.api.ReadStat(it, 0, 0L) }
                    )
                }
            }
            // 兜底探针（私聊）：只在「会话最后一条是我发的、且还标着未读」时查，一旦变「已读」立刻停。
            // 正常路径靠推送自带的数据直接落地（零请求），这里只兜推送丢失 / 重连空窗 / 进程重建。
            //
            // 间隔分两段：刚发出去那 30 秒按 2.5 秒问（对方多半正在看，这一段直接决定手感），
            // 之后退到 10 秒慢慢等。别人不读就一直查会很吵，所以必须有这个退坡；
            // 而没有这个快段，手感就完全押在推送这一次机会上 —— 推丢了就要干等十几秒。
            LaunchedEffect(friendId, readReceiptOn) {
                if (!readReceiptOn || isGroupChat(friendId)) return@LaunchedEffect
                var fastLeft = 12
                while (true) {
                    val tail = messages.lastOrNull { !it.isSystemNotice }
                    val pendingId = if (tail != null && tail.isMine && tail.serverId > 0 &&
                        (readStats[tail.serverId]?.otherReadAt ?: 0L) <= 0L
                    ) tail.serverId else 0L
                    if (pendingId == 0L) {
                        // 没有待确认的未读：什么都不查，只是等状态变化（顺手清掉积压的点击）
                        fastLeft = 12
                        readProbeKick.tryReceive()
                        kotlinx.coroutines.delay(1_500L)
                        continue
                    }
                    // 睡眠：快阶段 2.5 秒、慢阶段 10 秒。这期间只要用户点了那行「未读」，
                    // 立刻被打断、当场查一次 —— 不用等下一个整点。
                    val kicked = kotlinx.coroutines.withTimeoutOrNull(
                        if (fastLeft > 0) 2_500L else 10_000L
                    ) { readProbeKick.receive() } != null
                    if (kicked) fastLeft = 12 else if (fastLeft > 0) fastLeft--
                    if ((readStats[pendingId]?.otherReadAt ?: 0L) > 0L) {
                        fastLeft = 12
                        continue
                    }
                    var got = runCatching { AuroraApi.fetchReadStats(listOf(pendingId)) }.getOrNull()?.data
                    // 手动点的那一下，额外补问一次单条消息信息：两个接口查同一张已读表，
                    // 但一个走 JOIN、一个走单条查询，历史上出现过只有一路生效的情况。
                    if (kicked && (got?.get(pendingId)?.otherReadAt ?: 0L) <= 0L) {
                        val at = runCatching { AuroraApi.getMessageInfo(pendingId) }.getOrNull()
                            ?.data?.optLong("other_read_at", 0L) ?: 0L
                        if (at > 0L) {
                            got = (got ?: emptyMap()) + (pendingId to
                                com.aurora.chat.data.api.ReadStat(pendingId, 0, at))
                        }
                    }
                    if (got.isNullOrEmpty()) continue
                    readStats = mergeReadStats(readStats, got)
                    com.aurora.chat.data.local.ReadStatCache.putAll(ctx, got)
                }
            }
            // 群聊没有已读推送（服务端只在私聊推 message_read），停在群聊页时别人读了数字不会动。
            // 这里每分钟补一次批量查询（一次请求覆盖全部可见的我方消息）；私聊靠推送 + 上面的探针。
            LaunchedEffect(friendId, readReceiptOn) {
                if (!readReceiptOn || !isGroupChat(friendId)) return@LaunchedEffect
                while (true) {
                    kotlinx.coroutines.delay(30_000L)
                    val ids = messages.filter { it.isMine && !it.isSystemNotice && it.serverId > 0 }
                        .map { it.serverId }.takeLast(100)
                    if (ids.isEmpty()) continue
                    val got = runCatching { AuroraApi.fetchReadStats(ids) }.getOrNull()?.data ?: continue
                    if (got.isEmpty()) continue
                    readStats = mergeReadStats(readStats, got)
                    com.aurora.chat.data.local.ReadStatCache.putAll(ctx, got)
                }
            }
            // 「查找聊天记录」点结果：滚动到目标消息并闪烁高亮（reverseLayout：数据下标 → 展示下标）
            //
            // 定位分三档，从便宜到贵，任何一档成功都不再弹「找不到」：
            //   ① 目标已在可见窗口 → 直接滚；
            //   ② 目标在内存 store、只是被窗口裁掉了（用户上滑过 / 首屏窗口之外）→ 换窗口；
            //   ③ 目标连 store 都没有（首屏只读了最近 INITIAL_WINDOW 条，更旧的只在本地库里）
            //      → 按游标一页页从库里补进 store 头部（不扩窗口、不重绘），补到跨过目标再换窗口。
            // 第③档的关键是「补洞」而不是「跳洞」：store 必须从目标一直连续到最新，
            // 否则上滑/下滑的懒加载、引用跳转、新消息追加全都建立在「下标连续」上，一旦有洞就全乱。
            LaunchedEffect(jumpToMessageId) {
                val targetId = jumpToMessageId
                if (targetId == 0L) return@LaunchedEffect

                // 与 LazyColumn 渲染口径一致：去重 + 过滤任务流程消息
                fun locateInWindow(): Pair<Int, Int>? {
                    val visible = messages.distinctBy { it.id }.filter { !it.text.startsWith(PLAN_LIVE_TAG) }
                    val i = visible.indexOfFirst { it.id == targetId }
                    return if (i < 0) null else i to visible.size
                }

                val JUMP_FILL_PAGE = 3000     // 每次从库里取多少条（嫌慢就调大，解密是线性成本）
                val JUMP_WINDOW_BEFORE = 40   // 目标上方（更旧）保留的条数
                val JUMP_WINDOW_AFTER = 120   // 目标下方（更新）保留的条数，够填满一屏

                var at = locateInWindow()

                // ② 目标在 store 里但不在窗口：整窗搬过去，O(窗口)。旧实现按条挪窗口，隔着几百条就会挪几百次。
                if (at == null && adapter.store.indexOf(targetId) >= 0) {
                    adapter.windowAroundId(targetId, JUMP_WINDOW_BEFORE, JUMP_WINDOW_AFTER)
                    syncMessages()
                    kotlinx.coroutines.delay(16)
                    at = locateInWindow()
                }

                // ③ 目标不在内存：从本地库向更旧方向逐页取，直到这一页跨过目标。
                //
                // 关键：先把所有页攒齐，最后**一次性**并入 store。
                // 旧写法是每页 stashOlder 一次，而 stashOlder 内部要让整个 store 右移
                // （7 个数组的 arraycopy），补几万条时退化成 O(n²) —— 这就是「离得越远越卡」的真凶，
                // 和消息量无关。一次性并入只有一次平移，成本是一次解密（线性）。
                if (at == null) {
                    loadMoreProgress = 1
                    try {
                        // pages 按「由近到远」排列，合并时要倒过来才是升序
                        val pages = ArrayList<List<com.aurora.chat.data.api.MessageInfo>>()
                        var rounds = 0
                        while (rounds++ < 40) {
                            val oldestId = adapter.store.idAt(0)
                            if (oldestId <= 0L || oldestId <= targetId) break
                            val page = withContext(Dispatchers.IO) {
                                com.aurora.chat.data.repository.LocalMessageStore
                                    .loadOlderMessages(ctx, friendId, oldestId, JUMP_FILL_PAGE)
                            }
                            if (page.isEmpty()) break
                            pages.add(page)
                            // 这一页已跨过目标（含目标）就停，别为了对齐再多读几年历史
                            if (page.first().id <= targetId) break
                            kotlinx.coroutines.yield()
                        }
                        if (pages.isNotEmpty()) {
                            val total = pages.sumOf { it.size }
                            val merged = ArrayList<com.aurora.chat.data.api.MessageInfo>(total)
                            for (i in pages.indices.reversed()) merged.addAll(pages[i])
                            adapter.stashOlder(merged.map { it.toJumpMessageData(currentUserId) })
                        }
                        if (adapter.store.indexOf(targetId) >= 0) {
                            adapter.windowAroundId(targetId, JUMP_WINDOW_BEFORE, JUMP_WINDOW_AFTER)
                            syncMessages()
                            kotlinx.coroutines.delay(16)
                            at = locateInWindow()
                        }
                        // 补进来的是更旧的历史，更旧那头大概率还有内容：交给上滑流程自己判定
                        if (adapter.store.count > 0) dbHasOlder = true
                    } finally {
                        loadMoreProgress = 0
                    }
                }

                if (at != null) {
                    val want = (at.second - 1 - at.first).coerceAtLeast(0)
                    // 必须等 LazyColumn 的 item 数真的追上 messages，才能滚。
                    //
                    // 换窗口是同步改数据的（windowAroundId + syncMessages），但列表重组要等下一帧。
                    // 在那一帧里 scrollToItem 一个「超出当前 item 数」的下标，LazyColumn 既不报错
                    // 也不排队，直接夹到当前最后一个 item —— 于是：跳 9 月 10 号的消息，
                    // 滚到旧窗口最顶上（9 月 14 号）就停住，再也不会自己继续。
                    // 第二次进来时窗口已经是目标窗口、下标刚好没越界，所以又正常。
                    var waitFrames = 0
                    while (listState.layoutInfo.totalItemsCount <= want && waitFrames++ < 30) {
                        androidx.compose.runtime.withFrameNanos { }
                    }
                    // 呈现目标：已可见就零位移、只叠高亮；看不见才贴顶（见 revealMessageRow）。
                    // 先把这个 id 上次布局留下的矩形删掉 —— 换窗口后旧 item 被销毁时不会清理这张表，
                    // 残留的旧矩形会被误判成「已经看得见」而漏掉滚动。
                    msgBoundsInWindow.remove(targetId)
                    revealMessageRow(want, targetId)
                    flashMessage(targetId)
                } else {
                    // 走到这里说明本地库与内存里都没有这条：只可能是它已被删除/清理（服务端也不再返回）。
                    android.widget.Toast.makeText(
                        ctx, "这条消息已被删除或清理", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                jumpToMessageId = 0L
            }
            // 开发者撤回窥探：记录已展开（查看原消息）的撤回消息 id，再次点击收起还原占位
            val revealedRecallIds = remember { mutableStateMapOf<Long, Boolean>() }
            // 【固定工具日志只留一行】开启「固定调用和改动」后，多轮工具调用会不断新建 AI 气泡；
            // 如果每条气泡都挂「调用 N 个工具 / 查看 N 处改动」，屏幕上就会同时出现好几个重复行
            // （用户反馈"有时候会出现好几个，有时候又不会"）。
            // 这里指定本任务最后一条普通 AI 气泡为唯一归属：新气泡一出现，旧气泡的行自动隐藏，
            // 这一行就跟着内容一路往下走，而不是钉在过程中间。
            val fixedToolLogOwnerId = if (!AIUiPrefs.fixedToolChangeLog(ctx)) 0L else {
                displayMessages.lastOrNull { m ->
                    m is ChatMsg && !m.isMine && !m.isSystemNotice && !m.isToolStatus &&
                        m.fromUserId == AI_CHAT_ID
                }?.id ?: 0L
            }
            // 到顶上滑加载更早历史时的顶部加载提示（微信式：转圈 + 「加载中…」）。
            // 之前没有这个提示，上滑加载旧消息的短暂等待看起来像「卡死」，用户误以为旧消息丢了。
            //
            // 纯展示层优化：原先这是一块固定在视口顶端的透明文字，加载期间一滑动，
            // 消息就从它底下滚过去，看起来像「加载中」印在气泡上。现在：
            //   ① 只有视口仍停在列表顶部时才显示，滑离顶部立即淡出，不再追着消息盖；
            //   ② 收成带底色的胶囊贴在最上沿，即使出现也是一条明确的状态浮标。
            // 只改这一块 UI，不碰 LazyColumn 的 item 结构/索引（引用跳转、补页定位都依赖 index）。
            val atListTop = remember(listState) {
                androidx.compose.runtime.derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    // reverseLayout：index 越大越旧、越靠顶部。留 4 条余量，
                    // 补页瞬间 total 突变也不会让提示硬闪一下。
                    info.totalItemsCount == 0 || last >= info.totalItemsCount - 4
                }
            }
            // 加载中提示：这里不能直接调 AnimatedVisibility —— 该处只能拿到外层 Column 的隐式
            // ColumnScope 接收者，会被 Kotlin DSL 作用域标记拦住（编译报 "cannot be called in this
            // context with an implicit receiver"）。改用透明度过渡，观感一致：120ms 淡入 / 160ms 淡出。
            val loadTipVisible = loadMoreProgress == 1 && !isLocalChat(friendId) && atListTop.value
            val loadTipAlpha by animateFloatAsState(
                targetValue = if (loadTipVisible) 1f else 0f,
                animationSpec = tween(if (loadTipVisible) 120 else 160),
                label = "loadMoreTipAlpha"
            )
            if (loadTipAlpha > 0.01f) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(10f)
                        .alpha(loadTipAlpha)
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xEEFFFFFF))
                        .border(0.5.dp, Color(0x1A000000), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(16.dp),
                        color = com.aurora.chat.ui.theme.appTextHint()
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("加载中…", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
            LazyColumn(
                state = listState,
                // reverseLayout：最新消息位于 index 0、（绘制在底部），进入会话时天然贴底，
                // 无需 scrollToItem 逐条测量历史即可定位底部，彻底消除「进入大历史会话首帧卡顿」的全量测量开销。
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    // 记录列表视口矩形：引用跳转靠它判定「目标是否已完整可见」（见 revealMessageRow）
                    .onGloballyPositioned { c ->
                        val p = c.positionInWindow()
                        listViewportInWindow = Rect(
                            p.x, p.y,
                            p.x + c.size.width.toFloat(),
                            p.y + c.size.height.toFloat()
                        )
                    }
                    // 让滚动层自己带聊天背景（默认灰；该好友设置过背景图片时为该图）：
                    // 回弹平移时露出的边缘是「同一块背景」，
                    // 不会出现一块外来灰、把消息衬成「穿过它看不见」的视觉断层。
                    .chatBackground(friendId)
                    // 边界回弹位移：整块列表作为**一个整体**做绘制层平移。
                    // 只写图层矩阵：不重组、不重新测量、不重新布局。
                    .graphicsLayer { translationY = overscrollShift.floatValue }
                    // 滚到底部前不绘制（防止闪现顶部）；空列表/加载时仍需显示。
                    // 【性能】原用 Modifier.alpha(0f/1f)：会给整个可滚动列表套一层 graphicsLayer，
                    // 长列表滑动时这层要整块重录，是滑动卡顿的常见来源。改用 drawWithContent 只影响绘制，不建图层。
                    .drawWithContent { if (scrollReady || messages.isEmpty()) drawContent() }
                    .pointerInput(Unit) {
                        // 长按菜单收起：手指按下即收起菜单（保留副作用）；
                        // 自动跟随 / anchor 钉位不再由手势锁存，改由 LaunchedEffect(listState) 基于列表真实位置推导，
                        // 彻底消除「异步布局导致误解锁/误锁死」的滚动跳动与完全不跟随。
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed && it.previousPressed != true }) {
                                    openMenuMsgId = 0L
                                    redPacketMenuMsgId = 0L
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures {
                            // 点击空白区域关闭所有弹窗
                            openMenuMsgId = 0L
                            redPacketMenuMsgId = 0L
                            focusManager.clearFocus()
                            clearSelectionKey++
                            val imm = ctx.getSystemService(

                                android.content.Context.INPUT_METHOD_SERVICE
                            ) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                        }
                    }
                    // 【多指接力】Compose 的 scrollable 只跟踪「按下那一刻的那根手指」：
                    // 第一根手指按住不动，第二根手指再往上拖 —— 首指没越过 touchSlop，
                    // 后续 pointer 的位移被完全丢弃，列表纹丝不动。微信/QQ 没有这个限制。
                    // 这里在多指按下、且列表当前并未滚动时，把各指的平均位移自己喂给 listState；
                    // 一旦首指真的在拖（isScrollInProgress）立刻让位，绝不会双倍滚动。
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                var sum = 0f
                                var n = 0
                                for (c in event.changes) {
                                    if (!c.pressed) continue
                                    sum += c.position.y - c.previousPosition.y
                                    n++
                                }
                                if (n < 2 || sum == 0f) continue
                                if (listState.isScrollInProgress) continue
                                // dispatchRawDelta 接收的是「手指位移」，与 scrollable 内部
                                // 传给 ScrollingLogic 的量是同一个（reverseDirection 由它自己处理），
                                // 所以这里直接给平均值，不要再取反。
                                listState.dispatchRawDelta(sum / n)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    // AI 工具行是输入框上方独立的 36dp 行（在列表之外），列表内不需要再留一大截空白。
                    // 旧值 32dp 把最后一条气泡顶得离底部过远；恢复成与普通对话几乎一致，只多留 4dp 呼吸量给工具行。
                    bottom = if (friendId == AI_CHAT_ID) 8.dp else 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoadingMessages) {
                                androidx.compose.foundation.layout.Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(32.dp),
                                        color = com.aurora.chat.ui.theme.appTextHint()
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "加载中...",
                                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()
                                    )
                                }
                            } else if (initialLoadFailed && !isNotGroupMember) {
                                // 加载失败且真的没有任何内容：在空白处放一句静态说明 + 重试。
                                // 刻意不用 Toast / 弹窗——断网时用户已经知道没网，网络抖动更不该打断；
                                // 这里只是填补「一片空白」的信息真空，并且给一个可点的出口。
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("消息加载失败", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "点击重试",
                                        fontSize = 14.sp,
                                        color = Color(0xFF1E40AF),
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { loadRetryTick++ }
                                            .padding(horizontal = 16.dp, vertical = 6.dp)
                                    )
                                }
                            } else if (isNotGroupMember) {
                                androidx.compose.foundation.layout.Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("你还未加入该群", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(6.dp))
                                    Text("入群申请审核中，暂无法查看消息", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    Spacer(Modifier.height(16.dp))
                                    Box(
                                        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E40AF))
                                            .clickable {
                                                com.aurora.chat.ui.viewmodel.ChatViewModel.removeUserData(friendId)
                                                onBack()
                                            }.padding(horizontal = 28.dp, vertical = 10.dp)
                                    ) {
                                        Text("退出会话", fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            // 真的没有消息：什么都不显示。
                            // 没消息就没消息 —— 不写「暂无消息」这类占位文案，空会话就该是空的。
                        }
                    }
                } else {
                    // 倒序渲染：index 0 = 最新（绘制在底部），配合 reverseLayout 使用。
                    displayMessages.asReversed().forEachIndexed { index, msg ->
                        val cellContentType = run {
val m = msg
                            if (m.isSystemNotice || m.isRevoked == 1) "notice"
                            else if (m.isMine) "mine"
                            else "other"
                        }
                        item(
                            key = msg.id,
                            contentType = cellContentType
                        ) {
                        // 撤回占位强制重组：读取 recallTick 使 LazyColumn 在该状态变化时重跑可见 item 的 lambda
                        val _recall = recallTick
val rmsg = msg
                        // 已删除：derivedStateOf 作用域到单条，删除某条时仅该条重算，避免整窗重组
                        val isDeleted by remember(msg.id) { derivedStateOf { msg.id in deletedMessageIds } }
                        if (isDeleted) return@item
                        // 群公告卡片（不居中，对齐发送者头像侧；卡片较大展示公告内容，长按撤回后服务端联动移除群公告）
                        if (isGroupAnnouncementMessage(msg.text) && msg.isRevoked != 1) {
                            val annText = parseGroupAnnouncementText(msg.text)
                            val annNeedConfirm = parseGroupAnnouncementNeedConfirm(msg.text)
                            if (annText.isNotEmpty()) {
                                var annCardExpanded by remember(msg.id) { mutableStateOf(false) }
                                var annTextOverflows by remember(msg.id) { mutableStateOf(false) }
                                val annSenderAvatarMod = Modifier.size(ChatAvatarSize).clip(RoundedCornerShape(avatarCornerRadius(ChatAvatarSize)))
                                val myAnnAvatar = myAvatarBitmap
                                var annCardAnchor by remember(msg.id) { mutableStateOf(Rect.Zero) }
                                @Composable
                                fun AnnouncementSenderAvatar() {
                                    if (msg.isMine && myAnnAvatar != null) {
                                        Image(bitmap = myAnnAvatar.asImageBitmap(), contentDescription = null, modifier = annSenderAvatarMod, contentScale = ContentScale.Fit)
                                    } else {
                                        UserAvatar(userId = msg.fromUserId, userName = msg.senderName.ifEmpty { "?" }, size = ChatAvatarSize, modifier = annSenderAvatarMod)
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .onGloballyPositioned { annCardAnchor = it.boundsInWindow() }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
                                ) {
                                    if (!msg.isMine) {
                                        AnnouncementSenderAvatar()
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Column(
                                        Modifier.widthIn(max = 300.dp)
                                            .fillMaxWidth(0.78f)
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(com.aurora.chat.ui.theme.appSurface())
                                            .combinedClickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { annDetailRaw = msg.text; annDetailTime = msg.createdAt },
                                                onLongClick = {
                                                    announcementMenuAnchor = annCardAnchor
                                                    announcementMenuMsgId = msg.id
                                                }
                                            )
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color(0xFF1E40AF), modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("群公告", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        // 长公告折叠：默认最多 6 行，超出显示「展开」，避免卡片无限拉高
                                        Text(
                                            annText,
                                            fontSize = 15.sp, lineHeight = 22.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                                            maxLines = if (annCardExpanded) Int.MAX_VALUE else 6,
                                            overflow = TextOverflow.Ellipsis,
                                            onTextLayout = { if (!annCardExpanded) annTextOverflows = it.hasVisualOverflow }
                                        )
                                        if (annCardExpanded || annTextOverflows) {
                                            Text(
                                                if (annCardExpanded) "收起" else "展开",
                                                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                                modifier = Modifier.padding(top = 4.dp).clickable(
                                                    indication = null,
                                                    interactionSource = remember { MutableInteractionSource() }
                                                ) { annCardExpanded = !annCardExpanded }
                                            )
                                        }
                                        // 公告图片（最多5张，3列网格；URL 为相对路径时拼接服务端地址）
                                        val annImages = parseGroupAnnouncementImages(msg.text)
                                        if (annImages.isNotEmpty()) {
                                            Spacer(Modifier.height(8.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                annImages.chunked(3).forEach { rowImages ->
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        rowImages.forEach { url ->
                                                            coil.compose.AsyncImage(
                                                                model = resolveMediaUrl(url),
                                                                contentDescription = null,
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                                                                    .clickable { annImageViewerUrl = resolveMediaUrl(url) }
                                                            )
                                                        }
                                                        if (rowImages.size < 3) {
                                                            repeat(3 - rowImages.size) { Spacer(Modifier.weight(1f)) }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        // 群主/管理员：拉取该消息的群已读人数（read_count）
                                        if (isAdminOrOwner && msg.serverId > 0) {
                                            LaunchedEffect(msg.serverId) {
                                                try {
                                                    val info = AuroraApi.getMessageInfo(msg.serverId)
                                                    val rc = info.data?.optInt("read_count", 0) ?: 0
                                                    annReadCounts = annReadCounts + (msg.id to rc)
                                                    // 需确认的公告：同时带出已收到人数
                                                    if (annNeedConfirm) {
                                                        val cc = info.data?.optInt("announcement_confirm_count", 0) ?: 0
                                                        annConfirmCounts = annConfirmCounts + (msg.id to cc)
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        }
                                        // 卡片底部：左侧发布日期，右侧（仅群主/管理员）已读人数
                                        Spacer(Modifier.height(10.dp))
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                formatAnnouncementDate(msg.createdAt),
                                                fontSize = 11.sp,
                                                color = com.aurora.chat.ui.theme.appTextHint()
                                            )
                                            if (isAdminOrOwner) {
                                                Spacer(Modifier.weight(1f))
                                                Text(
                                                    "${annReadCounts[msg.id] ?: 0} 人已读",
                                                    fontSize = 11.sp,
                                                    color = com.aurora.chat.ui.theme.appTextHint(),
                                                    modifier = Modifier.clickable(
                                                        indication = null,
                                                        interactionSource = remember { MutableInteractionSource() }
                                                    ) { if (msg.serverId > 0) readSheetMsgId = msg.serverId }
                                                )
                                                // 「需确认收到」的公告：已读之外再显示已收到人数（点开抽屉看名单）
                                                if (annNeedConfirm) {
                                                    Spacer(Modifier.width(12.dp))
                                                    Text(
                                                        "${annConfirmCounts[msg.id] ?: 0} 人已收到",
                                                        fontSize = 11.sp,
                                                        color = com.aurora.chat.ui.theme.appTextHint(),
                                                        modifier = Modifier.clickable(
                                                            indication = null,
                                                            interactionSource = remember { MutableInteractionSource() }
                                                        ) { annConfirmSheetText = annText }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (msg.isMine) {
                                        Spacer(Modifier.width(6.dp))
                                        AnnouncementSenderAvatar()
                                    }
                                }
                                // 长按菜单：撤回（服务端联动移除群公告列表项）/ 删除
                                if (announcementMenuMsgId == msg.id) {
                                    val menuEntries = buildList {
                                        if (msg.serverId > 0 && canRecallMessage(msg, currentUserId, friendId)) {
                                            add(MenuEntry("撤回", RecallIcon) {
                                                announcementMenuMsgId = 0L
                                                val recallTargetId = msg.serverId
                                                ChatViewModel.backgroundScope.launch {
                                                    var result = AuroraApi.recallMessage(recallTargetId)
                                                    // 发送/入库竞态：服务端可能尚未提交该消息，稍后重试一次
                                                    if (!result.success) {
                                                        delay(1200)
                                                        result = AuroraApi.recallMessage(recallTargetId)
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        if (result.success) {
                                                            com.aurora.chat.PendingMessageRecalled.messageId = recallTargetId
                                                            com.aurora.chat.PendingMessageRecalled.senderName =
                                                                if (msg.isMine) currentUserName else msg.senderName
                                                            com.aurora.chat.PendingMessageRecalled.hasUpdate = true
                                                            ChatViewModel.updateConversationPreview(
                                                                friendId,
                                                                if (msg.isMine) "你撤回了一条公告" else "${msg.senderName} 撤回了一条公告",
                                                                System.currentTimeMillis() / 1000
                                                            )
                                                            // 落盘标记撤回，防止重启后公告卡片复活
                                                            withContext(Dispatchers.IO) {
                                                                com.aurora.chat.data.repository.LocalMessageStore.markRevoked(ctx, friendId, msg.id)
                                                            }
                                                            ChatViewModel.invalidateHotCache(currentUserId, friendId)
                                                        } else {
                                                            Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            })
                                        }
                                        add(MenuEntry("删除", Icons.Filled.Delete, danger = true) {
                                            announcementMenuMsgId = 0L
                                            deleteConfirmMsgId = msg.id
                                        })
                                    }
                                    MessageMenuPopup(
                                        anchor = announcementMenuAnchor,
                                        entries = menuEntries,
                                        onDismiss = { announcementMenuMsgId = 0L }
                                    )
                                }
                                return@item
                            }
                        }
                        // 红包卡片（不居中，对齐到发送者头像侧；点击进入全屏领取界面，长按呼出撤回/删除菜单）
                        // 注意：已撤回的红包不走卡片渲染，交给下方「撤回占位」分支 —— 否则撤回后红包卡片仍然显示。
                        if ((msg.mediaType == "redpacket" || isRedPacketMessage(msg.text)) && msg.isRevoked != 1) {
                            val rp = parseRedPacketMessage(msg.text)
                            if (rp != null) {
                                val senderAvatarMod = Modifier.size(ChatAvatarSize).clip(RoundedCornerShape(avatarCornerRadius(ChatAvatarSize)))
val myAvatar = myAvatarBitmap
                                // 卡片在窗口中的位置：长按弹菜单时用它贴近定位（布局时更新，取值可靠）
                                var cardAnchor by remember(msg.id) { mutableStateOf(Rect.Zero) }
                                @Composable
                                fun RedPacketSenderAvatar() {
                                    if (rp.fromUserId == currentUserId && myAvatar != null) {
                                        Image(bitmap = myAvatar.asImageBitmap(), contentDescription = null, modifier = senderAvatarMod, contentScale = ContentScale.Fit)
                                    } else {
                                        UserAvatar(userId = rp.fromUserId, userName = rp.fromName.ifEmpty { "?" }, size = ChatAvatarSize, modifier = senderAvatarMod)
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .onGloballyPositioned { cardAnchor = it.boundsInWindow() }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
                                ) {
                                    if (!msg.isMine) {
                                        RedPacketSenderAvatar()
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    RedPacketCardWithStatus(
                                        data = rp,
                                        isMine = msg.isMine,
                                        statusMap = redpacketStatus,
                                        onClick = { redPacketMenuMsgId = 0L; redPacketDetailId = rp.packetId },
                                        onLongClick = {
                                            redPacketMenuAnchor = cardAnchor
                                            redPacketMenuMsgId = msg.id
                                        }
                                    )
                                    if (msg.isMine) {
                                        Spacer(Modifier.width(6.dp))
                                        RedPacketSenderAvatar()
                                    }
                                }
                                // 长按菜单：撤回（未领取的余额会退回发送者）/ 删除
                                if (redPacketMenuMsgId == msg.id) {
                                    val menuEntries = buildList {
                                        // 仅发送者本人或开发者可撤回，且必须在 2 分钟窗口内；且必须是服务端消息（serverId>0）
                                        if (msg.serverId > 0 && canRecallMessage(msg, currentUserId, friendId)) {
                                            add(MenuEntry("撤回", RecallIcon) {
                                                redPacketMenuMsgId = 0L
                                                val recallTargetId = msg.serverId
                                                ChatViewModel.backgroundScope.launch {
                                                    var result = AuroraApi.recallMessage(recallTargetId)
                                                    // 发送/入库竞态：服务端可能尚未提交该消息，稍后重试一次
                                                    if (!result.success) {
                                                        delay(1200)
                                                        result = AuroraApi.recallMessage(recallTargetId)
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        if (result.success) {
                                                            Toast.makeText(ctx, "已撤回，未领取的 token 已退回", Toast.LENGTH_SHORT).show()
                                                            com.aurora.chat.PendingMessageRecalled.messageId = recallTargetId
                                                            com.aurora.chat.PendingMessageRecalled.senderName =
                                                                if (msg.isMine) currentUserName else msg.senderName
                                                            com.aurora.chat.PendingMessageRecalled.hasUpdate = true
                                                            ChatViewModel.updateConversationPreview(
                                                                friendId,
                                                                if (msg.isMine) "你撤回了一条消息" else "${msg.senderName} 撤回了一条消息",
                                                                System.currentTimeMillis() / 1000
                                                            )
                                                            // 落盘标记撤回，防止重启后红包卡片复活
                                                            withContext(Dispatchers.IO) {
                                                                com.aurora.chat.data.repository.LocalMessageStore.markRevoked(ctx, friendId, msg.id)
                                                            }
                                                            ChatViewModel.invalidateHotCache(currentUserId, friendId)
                                                        } else {
                                                            Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            })
                                        }
                                        add(MenuEntry("删除", Icons.Filled.Delete, danger = true) {
                                            redPacketMenuMsgId = 0L
                                            deleteConfirmMsgId = msg.id
                                        })
                                    }
                                    MessageMenuPopup(
                                        anchor = redPacketMenuAnchor,
                                        entries = menuEntries,
                                        onDismiss = { redPacketMenuMsgId = 0L }
                                    )
                                }
                                return@item
                            }
                        }
                        // 转账卡片：与红包一致，以「带头像的气泡」按发送方左右排布（对齐微信）。
                        // 旧写法是居中 Box，没有头像、也不分左右，看起来像系统提示而不是一条消息。
                        // 用正文前缀判断而非 mediaType，避免不同解析路径 media_type 丢失导致显示原文。
                        if (msg.mediaType == "transfer" || isTransferMessage(msg.text)) {
                            val t = parseTransferMessage(msg.text)
                            if (t != null) {
                                val avatarMod = Modifier.size(ChatAvatarSize).clip(RoundedCornerShape(avatarCornerRadius(ChatAvatarSize)))
                                // myAvatarBitmap 是委托属性，不能 smart cast；先落到局部 val
                                // （红包卡片分支也是这么处理的），否则 asImageBitmap() 编译不过。
                                val myAvatar = myAvatarBitmap
                                // 头像必须取「这条消息实际是谁发的」，不能用卡片内容里的 t.fromUserId：
                                // 后者是原始转账的发起方。对方收款后后端会回推一条 claimed 卡片，
                                // 那条是对方发出的（msg.fromUserId = 收款方），用 t.fromUserId 会一直显示成我的头像。
                                val avatarUserId = msg.fromUserId
                                val avatarName = msg.senderName.ifEmpty { t.fromName }
                                @Composable
                                fun TransferSenderAvatar() {
                                    if (avatarUserId == currentUserId && myAvatar != null) {
                                        Image(
                                            bitmap = myAvatar.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = avatarMod,
                                            contentScale = ContentScale.Fit
                                        )
                                    } else {
                                        UserAvatar(
                                            userId = avatarUserId,
                                            userName = avatarName.ifEmpty { "?" },
                                            size = ChatAvatarSize,
                                            modifier = avatarMod
                                        )
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = if (msg.isMine) Arrangement.End else Arrangement.Start
                                ) {
                                    if (!msg.isMine) {
                                        TransferSenderAvatar()
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    TransferCardWithDetail(
                                        data = t,
                                        currentUserId = currentUserId,
                                        fallbackCreatedAt = msg.createdAt
                                    )
                                    if (msg.isMine) {
                                        Spacer(Modifier.width(6.dp))
                                        TransferSenderAvatar()
                                    }
                                }
                                return@item
                            }
                        }
                        if (msg.isSystemNotice && msg.text.isBlank() && msg.isRevoked != 1) {
                            // 群通知里「消息撤回」被关闭时的静默撤回：不渲染任何提示
                            // 注意撤回消息（isRevoked=1）不能跳过：服务端已不返回原文，要靠占位文案补上
                            return@item
                        }
                        if (msg.isSystemNotice || msg.isRevoked == 1) {
                            val isDeveloper = currentUserId == 1L || com.aurora.chat.data.api.AuroraApi.isDeveloperAccount
                            val isRecall = msg.isRevoked == 1
                            // 开发者：对「X 撤回了一条消息」点击 → 回显原消息；再点一次 → 恢复占位
                            val recallRevealed = isRecall && isDeveloper && (revealedRecallIds[msg.id] ?: false)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            if (isRecall && isDeveloper) {
                                                revealedRecallIds[msg.id] = !(revealedRecallIds[msg.id] ?: false)
                                            }
                                        },
                                        onLongClick = {
                                            // 自己的开发者广播：弹出专属管理菜单（删除单个 / 撤回全部）
                                            if (msg.broadcastTaskId != 0L && msg.fromUserId == currentUserId) {
                                                broadcastManageMsgId = msg.id
                                                broadcastManageTaskId = msg.broadcastTaskId
                                            } else {
                                                deleteConfirmMsgId = msg.id
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (recallRevealed) {
                                    // 开发者窥探：回显被撤回的原消息内容（私聊 E2EE 密文先解密）
                                    // 与占位文案保持同样的居中布局与灰色，避免点击后偏移/变色，不附加任何提示
                                    val raw = com.aurora.chat.ui.viewmodel.recallOriginalCache[msg.id] ?: msg.text
                                    val decoded = if (raw.isNotBlank() && com.aurora.chat.CryptoUtil.isEncrypted(raw)) {
                                        try { com.aurora.chat.CryptoUtil.decrypt(raw) } catch (_: Exception) { raw }
                                    } else raw
                                    Text(
                                        text = if (decoded.isNotBlank()) decoded else "[原消息为媒体/文件，无文本可预览]",
                                        fontSize = 12.sp,
                                        color = com.aurora.chat.ui.theme.appTextHint(),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        maxLines = 4,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                                    )
                                } else {
                                    // 撤回消息一律生成占位文案，绝不再渲染 msg.text：
                                    // isSystemNoticeMessage() 对 isRevoked 恒返回 true，旧条件 !msg.isSystemNotice
                                    // 永远不成立，导致群公告的「系统公告数据{...}」JSON、广播正文被原样印在屏幕上
                                    val isRecallPlaceholder = isRecall
                                    val noticeText = if (isRecallPlaceholder) {
                                        val sender = if (msg.isMine) currentUserName else msg.senderName
                                        "$sender 撤回了一条消息"
                                    } else msg.text
                                    // 存量「系统转账通知」同样按通知中心的统一模板渲染，
                                    // 不再出现专门的橙色转账卡片。
                                    val transferNotice = if (!isRecallPlaceholder) parseTransferNotice(noticeText) else null
                                    if (transferNotice != null) {
                                        OrderNoticeCard(
                                            data = OrderNoticeData(
                                                title = "转账收款",
                                                desc = transferNotice,
                                                tag = "转账"
                                            )
                                        )
                                    } else {
                                        val order = if (!isRecallPlaceholder) parseOrderNotice(noticeText) else null
                                        if (order != null) {
                                            OrderNoticeCard(data = order, onViewOrder = { viewOrderId = it })
                                        } else {
                                            Text(
                                                text = if (!isRecallPlaceholder && isGroupConsentNotice(noticeText))
                                                    parseGroupConsentNotice(noticeText, msg.fromUserId == currentUserId)
                                                else noticeText,
                                                fontSize = 12.sp,
                                                color = com.aurora.chat.ui.theme.appTextHint(),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                            return@item
                        }
                        //  LazyColumn 只 Compose 可见项，所有 Compose 出来的项都加载媒体
                        //   不用 layoutInfo — 避免快滑时每帧全量重组风暴
                        val loadMedia = msg.mediaUrl.isNotEmpty()
                        //  图片预加载：LazyColumn 已保证只有近屏项才进入此块
                        if (loadMedia) {
                            LaunchedEffect(msg.mediaUrl) {
                                ctx.imageLoader.enqueue(
                                    ImageRequest.Builder(ctx)
                                        .data(com.aurora.chat.ui.chat.media.chatMediaThumbUrl(resolveMediaUrl(msg.mediaUrl)))
                                        .memoryCachePolicy(CachePolicy.ENABLED)
                                        .diskCachePolicy(CachePolicy.ENABLED)
                                        .size(720)
                                        .build()
                                )
                            }
                        }
                        // 倒序渲染中，可视区上一条（更旧）位于 index+1
val rprev = reversedMessages.getOrNull(index + 1)
                        // 间距统一由列表 spacedBy(6.dp) 控制，不因发送者切换额外加 Spacer，
                        // 避免出现「切换处头像间距 > 连续同人间距」的参差
                        // 交互态用 derivedStateOf 作用域到单条消息：打开菜单/勾选/高亮/删除闪照等全局态变化时，
                        // 仅真正受影响的项重组，其余项（派生值未变）被 Compose 跳过，消除整窗重组风暴
                        val isMenuOpenForItem by remember(msg.id) { derivedStateOf { openMenuMsgId == msg.id } }
                        val isSelectedForItem by remember(msg.id) { derivedStateOf { selectedMsgIds.value.contains(msg.id) } }
                        val isHighlightedForItem by remember(msg.id) { derivedStateOf { msg.id == highlightedMsgId } }
                        val selKeyForItem by remember(msg.id) { derivedStateOf { clearSelectionKey } }
                        val destroyedFlashForItem by remember(msg.id) { derivedStateOf { destroyedFlashIds } }
                        val replyIsRevoked = if (rmsg.replyToId > 0L) {
                            val rq = adapter.store.indexOf(rmsg.replyToId)
                            if (rq >= 0) {
                                adapter.store.isRevoked(rq)                       // 服务端会话：仅依赖 adapter.store（全量，与窗口无关）
                            } else {
                                // 本地/AI 会话不经过 adapter、messages 为全量（非窗口化）；
                                // 服务端会话 rq<0 时无法判断，绝不回退误判为已撤回
                                isLocalChat(friendId) && msgById[rmsg.replyToId] == null
                            }
                        } else false
                        // 被引用消息是否自己发的：为真时引用胶囊只显示内容、不显示名字（群聊除外，见下）
                        val replyIsMine = if (rmsg.replyToId > 0L) {
                            val rq = adapter.store.indexOf(rmsg.replyToId)
                            if (rq >= 0) adapter.store.isMine(rq)
                            else isLocalChat(friendId) && msgById[rmsg.replyToId]?.isMine == true
                        } else false
                        // 被引用消息的发送者名：优先按 id 从全量数据解析（群聊里"刚发出还没回声"的乐观消息、
                        // 以及旧数据 reply_to_sender 为空的情况，都能拿到正确名字），解析不到才回退存储字段
                        val replySenderResolved = if (rmsg.replyToId > 0L) {
                            val rq = adapter.store.indexOf(rmsg.replyToId)
                            val resolved = if (rq >= 0) adapter.store.senderNameAt(rq)
                            else msgById[rmsg.replyToId]?.senderName ?: ""
                            when {
                                resolved.isNotBlank() -> resolved
                                replyIsMine -> currentUserName.ifEmpty { rmsg.replyToSender }
                                else -> rmsg.replyToSender
                            }
                        } else rmsg.replyToSender
                        // 被引用消息的媒体类型/URL：用于在引用胶囊里渲染图片缩略图
                        val replyMediaType: String
                        val replyMediaUrl: String
                        // 被引用语音的时长（秒）：胶囊里要画小号语音气泡，必须带秒数
                        val replyVoiceSeconds: Int
                        run {
                            val rq = if (rmsg.replyToId > 0L) adapter.store.indexOf(rmsg.replyToId) else -1
                            val refByMap = msgById[rmsg.replyToId]
                            // msgById 只含可见窗口；窗口外的被引用消息必须用「绝对下标」直读 store：
                            // 绝不能用 refAt(rq)——它期望可见窗口下标，越界会兜底成最新一条消息（mediaUrl 为空），
                            // 导致视频/文件/卡片等被引用媒体的缩略全部落到文字占位分支。
                            // 注意：不能只用 `?:`。窗口内消息对象的 mediaType/mediaUrl 有可能是**空串**
                            //（本地乐观项、只装了部分字段的引用对象），空串不会触发 elvis，胶囊就会退化成
                            // 纯文字预览（文件引用只剩一行 "[文件]"）。空串同样回落到 store。
                            replyMediaType = refByMap?.mediaType?.takeIf { it.isNotBlank() }
                                ?: (if (rq >= 0) adapter.store.mediaTypeString(rq) else "")
                            replyMediaUrl = refByMap?.mediaUrl?.takeIf { it.isNotBlank() }
                                ?: (if (rq >= 0) adapter.store.mediaUrlAt(rq) else "")
                            // 语音秒数两处来源：内存消息对象带 mediaWidth（发送时写入），
                            // 窗口外只能读 store 的 content——语音把秒数写在 content 里。
                            replyVoiceSeconds = when {
                                refByMap != null ->
                                    if (refByMap.mediaWidth > 0) refByMap.mediaWidth
                                    else refByMap.text.toIntOrNull() ?: 0
                                rq >= 0 -> adapter.store.contentAt(rq).toIntOrNull() ?: 0
                                else -> 0
                            }
                        }
                        // 引用胶囊里的时间 = 被引用那条消息的「发出时间」，不是引用动作发生的时刻：
                        // 优先 clientSendAt（客户端按下发送的瞬间 ms），没有则回退服务端 createdAt（秒）。
                        val replyTimeMs: Long = if (rmsg.replyToId > 0L) {
                            val rq = adapter.store.indexOf(rmsg.replyToId)
                            val ref = msgById[rmsg.replyToId]
                            when {
                                ref != null && ref.clientSendAt > 0 -> ref.clientSendAt
                                ref != null && ref.createdAt > 0 -> ref.createdAt * 1000L
                                rq >= 0 -> adapter.store.timestampAt(rq) * 1000L
                                else -> 0L
                            }
                        } else 0L
                        // 时间分隔：与本条上方（更旧）那条消息的间隔决定要不要插一条时间。
                        // rprev 是倒序列表里的 index+1，即「更旧的上一条」。
                        val showTimeMenuForItem by remember(rmsg.id) {
                            derivedStateOf { timeMenuMsgId == rmsg.id }
                        }
                        val timeDividerText: String? = if (rmsg.id in hiddenTimeDividers) null else {
                            val curSec = ChatTimeFormat.secOf(rmsg)
                            val prevSec = rprev?.let { ChatTimeFormat.secOf(it) } ?: 0L
                            if (ChatTimeFormat.shouldShow(prevSec, curSec)) {
                                ChatTimeFormat.formatLabel(curSec, timeNowSec)
                            } else null
                        }
                        // 气泡下方的已读状态。
                        //
                        // 私聊的显示规则（跟微信/QQ 的直觉一致）：
                        //   只有「会话最后一条消息是我发的」时，才在她下面标「已读 / 未读」。
                        //   对方一旦回了消息，说明他必然看过我上一条 —— 这个时候标记就该消失，
                        //   再挂着「已读」只是废话。等我再发一条，标记自然重新出现在新消息下面。
                        //
                        // 群聊不套这个规则：群里是多人，「有人回了」不能推出「其他人都读过」，
                        // 每条自己发的都保留人数（而且那行数字点得开，能看名单）。
                        val readLabelForItem by remember(rmsg.id, lastMsgKey, readReceiptOn) {
                            derivedStateOf {
                                if (!readReceiptOn || !rmsg.isMine || rmsg.isSystemNotice) return@derivedStateOf null
                                val stat = readStats[rmsg.serverId]
                                when {
                                    isGroupChat(friendId) -> "${stat?.readCount ?: 0}人已读"
                                    rmsg.id == lastMsgKey ->
                                        if ((stat?.otherReadAt ?: 0L) > 0) "已读" else "未读"
                                    else -> null
                                }
                            }
                        }
                        // 「气泡左侧」模式下，这行字不进下面的 Column，而是交给气泡自己画在左边
                        // （只有气泡知道自己多宽、多高，才能贴着它的左沿垂直居中）。
                        val readLeftLabel = if (readReceiptLeft) readLabelForItem else null
                        val readLeftClick: (() -> Unit)? = when {
                            readLeftLabel == null -> null
                            // 群聊点「N人已读」看名单
                            isGroupChat(friendId) -> ({ if (rmsg.serverId > 0) readSheetMsgId = rmsg.serverId })
                            // 私聊点一下 = 立刻重查一次（与气泡下方那行同一条路径，CONFLATED 不会叠请求）
                            else -> ({ readProbeKick.trySend(Unit) })
                        }
                        // 【顺序固定】reverseLayout 的 LazyColumn 里，一个 item 内直接铺多个子节点时，
                        // 放置顺序会被倒过来——原先提示写在气泡前面，实际被排到了气泡下方
                        // （用户反馈：「对方通过搜索与你联系」跑到了消息下面）。
                        // 这里用 Column 显式包住「提示 + 气泡」，Column 内部始终自上而下，提示稳定在气泡上方。
                        Column(Modifier
                            .fillMaxWidth()
                            // 记录这一行的窗口矩形：引用跳转用它判定「是否已完整可见」（见 revealMessageRow）
                            .onGloballyPositioned { c ->
                                val p = c.positionInWindow()
                                msgBoundsInWindow[rmsg.id] = Rect(
                                    p.x, p.y,
                                    p.x + c.size.width.toFloat(),
                                    p.y + c.size.height.toFloat()
                                )
                            }
                        ) {
                        // 时间分隔永远是本条最上方的一个子节点（Column 内自上而下）。
                        // 长按 → 白底菜单 → 删除：只对这一条记墓碑，判定规则不受影响。
                        if (timeDividerText != null) {
                            ChatTimeDivider(
                                label = timeDividerText,
                                expanded = showTimeMenuForItem,
                                onLongPress = { timeMenuMsgId = rmsg.id },
                                onDismiss = { timeMenuMsgId = 0L },
                                onDelete = {
                                    timeMenuMsgId = 0L
                                    hiddenTimeDividers = hiddenTimeDividers + rmsg.id
                                    com.aurora.chat.data.local.ChatTimeDividerPrefs
                                        .hide(ctx, timeConvKey, rmsg.id)
                                }
                            )
                        }
                        // 陌生人首条消息来源系统广播（仅接收方显示）：气泡上方居中提示
                        if (!rmsg.isMine && rmsg.contactSource.isNotEmpty()) {
                            val csText = buildContactSourceBroadcast(rmsg.contactSource)
                            if (csText.isNotEmpty()) {
                                Box(
                                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        csText,
                                        fontSize = 12.sp,
                                        color = com.aurora.chat.ui.theme.appTextHint(),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    )
                                }
                            }
                        }
                        MessageBubbleItem(
                            msg = rmsg,
                            myBubbleColor = myBubbleColor,
                            otherBubbleColor = otherBubbleColor,
                            isLastMessage = index == 0,  // 倒序渲染：index 0 = 最新（最后一条）消息
                            // 固定工具日志的唯一归属气泡：只有它会渲染「调用 N 个工具 / 查看 N 处改动」
                            isFixedLogOwner = rmsg.id == fixedToolLogOwnerId,
                            // 发送失败：点气泡左侧红叹号 → 弹「是否重新发送」
                            onRetrySend = { failed -> retryTargetMsg = failed },
                            // 「已读显示 → 气泡左侧」：把文案交给气泡，让它贴着自己左沿垂直居中画
                            readLabelLeft = readLeftLabel,
                            onReadLabelClick = readLeftClick,
                        aiToolStatus = null,  // 工具状态现已作为独立消息（isToolStatus=true）就地渲染，不再挂到气泡上
                            // 「查看所有改动」：强制渲染按钮由 aiFileChanges 数据驱动,点击切入全屏改动详情
                            onViewChanges = {
                                (msg as? ChatMsg)?.aiFileChanges?.takeIf { it.isNotBlank() }?.let {
                                    aiChangesJsonData = it
                                    aiChangesMsgId = msg.id
                                    aiChangesFocusPath = ""
                                    aiChangesFocusDetail = false
                                    aiChangesOpen = true
                                }
                            },
                            // 「工具调用」按钮:点击切入全屏工具清单(工具名/成败/毫秒时间戳)
                            onToolCalls = {
                                (msg as? ChatMsg)?.aiToolStages?.takeIf { it.isNotEmpty() }?.let {
                                    aiToolCallsData = it
                                    aiToolCallsOpen = true
                                }
                            },
                            onPlanBuild = {
                                // 以用户身份发送「接受计划并立即开始」并启动 AI:复用 InputBar 的 onSend 链路,
                                // 确保消息落库 + AI 真正回应(之前只调 ChatViewModel.sendMessage 存了消息却没启动对话循环,导致「发了但 AI 不回应」)
                                inputText = PLAN_BUILD_COMMAND
                                sendUserTurn()
                            },
                            // 「✎ 编辑」：构建前可以改方案（增/删/改步骤）；开工后卡片不再显示这个入口
                            onPlanEdit = { openPlanEditor() },
                            // 任务已整体交付完成(会话内出现过 DELIVER 交付块)→ 方案流程步骤全部勾选为已完成,
                            // 对齐 CodeBuddy:执行完后方案里的步骤全部打勾,而不是一直停在未开始。
                            planAllDone = planAllDoneState.value,
                            // 任务流程卡：只喂给**最新一条 AI 消息**，由它渲染在交付总结上方
                            livePlan = if (rmsg.id == lastAiMsgIdState.value) livePlanState.value else null,
                            replyIsRevoked = replyIsRevoked,
                            replyIsMine = replyIsMine,
                            replyMediaType = replyMediaType,
                            replyMediaUrl = replyMediaUrl,
                            replyVoiceSeconds = replyVoiceSeconds,
                            replyTimeMs = replyTimeMs,
                            onFileRefAction = { name, action ->
                                // 点击产物 / 文件引用胶囊的**唯一正确语义**：先看这个文件到底还在不在工作区。
                                //   · 还在  → 切入工作区定位（0=查看并打开编辑器，1=所在位置）；
                                //   · 不在了（被 AI 删除、被移走、或在历史消息里）→ 绝不走工作区定位：
                                //     明明已经删掉的东西，去工作区找必然弹「工作区中未找到」，那才是真正的错。
                                //     此时直接切入「查看所有改动」的改动详情——命中改动记录就展开那条 diff
                                //     （目录级操作展开内容视图），未命中就进改动界面如实展示记录。
                                val refRaw = name.trim().replace('\\', '/').trim('/')
                                val root = AiChatManager.agentRoot(ctx)
                                val rootAbs = root.absolutePath.replace('\\', '/').trimEnd('/')
                                // 产物里的路径可能带 ai_files/ 前缀或整条绝对路径：统一还原成工作区相对路径
                                val relName = when {
                                    rootAbs.isNotEmpty() && refRaw.startsWith("$rootAbs/") -> refRaw.removePrefix("$rootAbs/")
                                    refRaw.startsWith("ai_files/") -> refRaw.removePrefix("ai_files/")
                                    else -> refRaw
                                }
                                // （存在性判断挪到下面：要用「改动记录里的权威路径」来判，而不是只用产物路径）

                                val changesJson = (msg as? ChatMsg)?.aiFileChanges?.takeIf { it.isNotBlank() }
                                val fileChanges = changesJson?.let { AiChatManager.parseFileChanges(it) } ?: emptyList()
                                // 匹配放宽到「末段文件名相同」：产物路径与改动记录路径的目录写法常不一致。
                                // 取**最后一条**匹配 —— 它代表该文件当前的最终状态（先建后删、先删后建都能判准）。
                                val matched = fileChanges.lastOrNull { c ->
                                    val cp = c.path.trim().replace('\\', '/').trim('/')
                                    cp == relName || cp == refRaw || cp.endsWith("/$relName") ||
                                        relName.endsWith("/$cp") ||
                                        (cp.substringAfterLast('/').isNotBlank() &&
                                            cp.substringAfterLast('/') == relName.substringAfterLast('/'))
                                }
                                // 【删除类 → 改动详情】用户明确要求：产物若已被删除，点击只应看改动记录，
                                // 绝不能去工作区找（那里必然找不到，还会白弹一次「未找到」）。
                                val deletedOp = matched?.op == "deleted" || matched?.op == "deleted_folder"
                                // 路径以**改动记录**为准：它来自工具真实写入，比模型手写的产物路径可靠得多。
                                // 之前只拿产物路径去 File(...).exists() 判断，字形稍不一致就一律判"不存在"
                                // → 于是所有产物都跳去了改动详情（用户反馈的正是这个）。
                                val effectiveRel = matched?.path?.trim()?.replace('\\', '/')?.trim('/')
                                    ?.takeIf { it.isNotEmpty() } ?: relName
                                val exists = runCatching {
                                    java.io.File(root, effectiveRel).exists() ||
                                        java.io.File(root, relName).exists() ||
                                        java.io.File(refRaw).exists()
                                }.getOrDefault(false)

                                if (deletedOp || !exists) {
                                    aiChangesJsonData = changesJson ?: ""
                                    aiChangesMsgId = msg.id
                                    aiChangesFocusPath = matched?.path ?: relName
                                    aiChangesFocusDetail = matched != null
                                    aiChangesOpen = true
                                } else {
                                    // 常规文件引用（新建/下载/编辑等仍在磁盘上的产物）：切入工作区定位目标文件
                                    // （0=查看并打开编辑器 1=所在位置）；用改动记录里的权威路径，定位更准。
                                    workspaceLocateName = effectiveRel
                                    workspaceLocateOpen = action == 0
                                    showWorkspaceFiles = true
                                }
                            },
                            replySenderName = replySenderResolved,
                            loadMedia = loadMedia,
myAvatar = myAvatarBitmap,
                            currentUserId = currentUserId,
                            currentUserName = currentUserName,
                            friendId = friendId,
                            friendName = friendName,
                            animMode = animMode,
                            advancedAnim = advancedAnim,
                            onOpenPostDetail = onOpenPostDetail,
                            onOpenGroupDetail = onOpenGroupDetail,
                            onOpenShortVideo = onOpenShortVideo,
                            onRespondGroupInvite = { inviteRespond = it },
                            isMenuOpen = isMenuOpenForItem,
                            onMenuOpen = { openMenuMsgId = msg.id },
                            onMenuClose = { openMenuMsgId = 0L },
                            onAvatarClick = { uid, name ->
                                if (isGroupChat(friendId) && uid == friendId) {
                                    // 点击群系统消息头像 → 打开群设置
                                    showGroupSettings = true
                                } else {
                                    profileUserId = uid
                                    profileUserName = if (isGroupChat(friendId)) "" else name
                                    showProfile = true
                                }
                            },
                            onDeleteMessage = { msgId -> deleteConfirmMsgId = msgId },
                            onBroadcastManage = { mid, tid -> broadcastManageMsgId = mid; broadcastManageTaskId = tid },
                            // 群主/管理员长按别人的气泡才有「禁言」；私聊、非管理、自己发的消息都不给这个入口
                            onMuteMember = if (isGroupChat(friendId) && isAdminOrOwner) { uid, name ->
                                muteTargetUserId = uid
                                muteTargetUserName = name
                            } else null,
                            onCopyMessage = { text ->
                                try {
                                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    // 复制：统一走 messageEditableBody —— 把思考/工具状态/文本协议/方案卡/交付块
                                    // 全部剥掉，只留可读正文（旧写法只剥思考+交付，工具哨兵与方案卡 JSON 会跟着进剪贴板）
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("消息", messageEditableBody(text)))
                                    Toast.makeText(ctx, "复制成功", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show()
                                }
                            },
                            // 收藏：图片/视频/语音/文件会先复制到本机再记录，服务器 30 天清理后仍可见。
                            // AI 对话不提供收藏入口（收藏页只服务于聊天与社区内容）。
                            onFavoriteMessage = if (friendId == AI_CHAT_ID) null else { target ->
                                scope.launch {
                                    val r = com.aurora.chat.data.local.FavoritesStore.addMessage(ctx, target)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            ctx,
                                            when (r) {
                                                com.aurora.chat.data.local.FavoriteAddResult.ADDED -> "已收藏"
                                                com.aurora.chat.data.local.FavoriteAddResult.EXISTS -> "该消息已在收藏中"
                                                com.aurora.chat.data.local.FavoriteAddResult.FAILED -> "收藏失败"
                                            },
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            // 分享：单条消息也直接走「分享聊天」卡片，不用先进多选。
                            // 与多选 → 分享共用同一个弹窗与同一套过滤规则。
                            onShareMessage = { target ->
                                if (!isShareableChatMessage(target, destroyedFlashIds)) {
                                    Toast.makeText(ctx, "闪照/已撤回内容无法分享", Toast.LENGTH_SHORT).show()
                                } else {
                                    chatShareCardText = buildChatShareCard(
                                        listOf(target), currentUserName, destroyedFlashIds
                                    )
                                    showChatShareDialog = true
                                }
                            },
                            // 点「引用」后立即聚焦输入框并拉起键盘（自增信号，连续引用不同消息也能重复触发）
                            onReplyTo = { replyToMsg.value = msg; quoteFocusTick++ },
                            // 「修改」只在 AI 对话出现，私信/群聊不显示
                            onEditMessage = if (friendId == AI_CHAT_ID) { target ->
                                // ① 生成中不许改：此刻 message.text 还在被流式追加，改完重发会把
                                //    两次生成的内容叠在同一条消息里（用户反馈的"两个生成的东西全塞进去"）。
                                if (aiWriting.value) {
                                    Toast.makeText(ctx, "AI 正在生成中，请先停止再修改", Toast.LENGTH_SHORT).show()
                                } else {
                                    editTargetMsgId = target.id
                                    editReasoningText = (target as? ChatMsg)?.reasoningText ?: ""
                                    // ② 只把**可读正文**放进编辑框：剥掉全部内部协议（工具哨兵/方案卡 JSON/交付块等）
                                    editOutputText = messageEditableBody(target.text)
                                    showEditDialog = true
                                }
                            } else null,
                            onMultiSelect = {
                                openMenuMsgId = 0L
                                multiSelectMode = true
                                // 进入多选时自动勾选长按的这条消息（与微信/QQ 一致），
                                // 否则用户点了「多选」还要再手动点一次才能选中目标。
                                selectedMsgIds.value = selectedMsgIds.value + msg.id
                                // 勾选第一条时记录「选择到这里」锚点
                                if (selectAnchorMsgId == -1L) selectAnchorMsgId = msg.id
                            },
                            multiSelectActive = multiSelectMode,
                            isSelected = isSelectedForItem,
                            onToggleSelected = {
                                selectedMsgIds.value = if (selectedMsgIds.value.contains(msg.id))
                                    selectedMsgIds.value - msg.id
                                else selectedMsgIds.value + msg.id
                                // 勾选第一条时记录「选择到这里」锚点
                                if (selectAnchorMsgId == -1L) selectAnchorMsgId = msg.id
                            },
                            clearSelectionKey = selKeyForItem,
                            isHighlighted = isHighlightedForItem,
                            onCapsuleClick = { targetId ->
                                scope.launch {
                                    // 用 displayMessages 取下标：与 LazyColumn 的 item 列表同源。
                                    // 旧写法按 messages 取下标、却按 displayMessages.size 换算，
                                    // 一旦存在重复 id 就会整体错一位，跳到隔壁那条上。
                                    val targetIdx = displayMessages.indexOfFirst { it.id == targetId }
                                    if (targetIdx >= 0) {
                                        // reverseLayout：数据下标 → 展示下标（index0=最新）
                                        val displayIdx = (displayMessages.size - 1 - targetIdx).coerceAtLeast(0)
                                        // 呈现语义统一收口：已可见零位移、部分可见最小位移、看不见才贴顶
                                        revealMessageRow(displayIdx, targetId)
                                        flashMessage(targetId)
                                    } else {
                                        android.widget.Toast.makeText(ctx, "未找到被引用的消息", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            destroyedFlashIds = destroyedFlashForItem,
                            partnerViewedFlashIds = partnerViewedFlashIds,
                            viewingFlashIds = viewingFlashIds,
                            onFlashViewStart = { id -> saveFlashViewStart(id) },
                            onFlashDestroy = { id, mediaUrl -> saveDestroyedFlash(id, mediaUrl) },
                            onAtMention = { userName ->
                                inputText += "@${userName} "
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            },
                            onAvatarLongPress = { uid, name ->
                                if (friendId > 0) {
                                    // 私信：长按对方头像粘贴名称（自己头像不做操作）
                                    if (uid != currentUserId) {
                                        inputText += name
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                    }
                                } else {
                                    // 群聊：保持 @mention
                                    inputText += "@${name} "
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            },
                            onAvatarDoubleTap = { uid, name ->
                                // 拍一拍限流：5 秒内只能拍一次（开发者豁免），避免连点刷屏。
                                // 被限流时不发请求、也不插本地提示，只弹一句系统提示。
                                val pokeNow = System.currentTimeMillis()
                                if (currentUserId != 1L && pokeNow - lastPokeAtMs < POKE_COOLDOWN_MS) {
                                    Toast.makeText(ctx, "休息一下吧", Toast.LENGTH_SHORT).show()
                                } else {
                                    lastPokeAtMs = pokeNow
                                    scope.launch {
                                        // 群聊：把拍一拍作为群消息发送（to_user_id=群会话ID，target_user_id=被拍成员），
                                        // 确保拍一拍隔离在群内、群成员都能看到，且不泄漏到与成员的私聊。
                                        com.aurora.chat.data.repository.ChatRepository.sendPoke(
                                            if (isGroupChat(friendId)) friendId else uid, uid
                                        )
                                    }
                                    val timeNow = pokeNow
                                    val noticeText = if (uid == currentUserId) {
                                        "你拍了拍自己"
                                    } else {
                                        "你拍了拍${name}"
                                    }
                                    // 用负数本地 id：保证恒小于服务端真实消息 id（服务端 id 为正整数自增），
                                    // 否则超大正数乐观 id 会把增量同步游标 afterId 撑大，导致 TCP 触发的服务端重载
                                    // 把真实拍一拍（id 较小）过滤掉、永不落盘，重进对话即“消失”（新群/小群必现）。
                                    val localId = -(timeNow % 900000L) - 1L
                                    messages.add(ChatMsg(
                                        id = localId, text = noticeText, isMine = true,
                                        fromUserId = currentUserId, senderName = currentUserName.ifEmpty { "我" }, time = timeNow,
                                        isSystemNotice = true, isNew = true, createdAt = timeNow / 1000
                                    ))
                                }
                            },
                            onAtMentionClick = { mentionedName ->
                                // 优先用这条消息自带的 @ 映射：昵称是发送时的快照，对方改名后也查得到
                                val memberId = msg.mentions[mentionedName]
                                    ?: groupMemberIdMap[mentionedName]
                                    ?: com.aurora.chat.data.store.MentionDirectory.idOf(friendId, mentionedName)
                                if (memberId != null && memberId > 0) {
                                    profileUserId = memberId
                                    profileUserName = if (isGroupChat(friendId)) "" else mentionedName
                                    showProfile = true
                                } else {
                                    // 回退：从消息列表中查找
                                    val found = messages.firstOrNull { it.senderName == mentionedName }
                                    if (found != null) {
                                        profileUserId = found.fromUserId
                                        profileUserName = if (isGroupChat(friendId)) "" else mentionedName
                                        showProfile = true
                                    } else {
                                        Toast.makeText(ctx, "未找到该用户", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            isGroup = isGroupChat(friendId),
                            groupMemberNames = groupMemberNames,
                            // 该条消息发送者的群角色 → 昵称前的「群主 / 群管理」徽章。
                            // 成员表里查得到就用成员表；查不到且是自己时，退回本页已探明的角色（防止成员接口失败时自己没徽章）。
                            senderRole = groupRoles[msg.fromUserId] ?: when {
                                msg.isMine && isGroupOwner -> "owner"
                                msg.isMine && isAdminOrOwner -> "admin"
                                else -> ""
                            },
                            onImageClick = { url ->
                                // 根据 mediaType 区分图片还是视频（onImageClick 传入的是 resolve 后的完整 URL）
                                val m = messages.find { resolveMediaUrl(it.mediaUrl) == url }
                                if (m?.mediaType == "video" || m?.mediaType == "mp4") {
                                    openVideoPlayer(url, false)
                                } else {
                                    // 收集当前对话全部图片并 resolve 成完整 URL，点击后进入多图左右滑动预览；
                                    // 用完整 URL 渲染，避免全屏查看器拿到相对路径导致黑屏。
                                    val imgUrls = messages
                                        .filter {
                                            (it.mediaType == "image" || it.mediaType == "jpg" ||
                                                it.mediaType == "png" || it.mediaType == "gif" ||
                                                it.mediaType == "jpeg") && it.mediaUrl.isNotEmpty()
                                                && it.flashDuration == 0  // 闪照不进图片预览
                                                && it.isRevoked != 1      // 已撤回的不进预览（避免滑到它闪一下）
                                        }
                                        .map { resolveMediaUrl(it.mediaUrl) }
                                        .distinct()
                                    val idx = imgUrls.indexOf(url)
                                    imagePreviewUrls = imgUrls
                                    imagePreviewIndex = if (idx >= 0) idx else 0
                                    heroInitialIndex = imagePreviewIndex
                                    // 点击瞬间同步冻结"从哪来"，之后布局怎么变都不再改起跑点
                                    heroSourceRect = heroRectFor(url, false)
                                    // 点击瞬间同步重置，保证打开第一帧就是「hero 待播」状态
                                    heroEnterDone = false
                                    previewReady = false
                                    previewSession++
                                }
                            },
                            onBoundsMeasured = { url, rect -> imageBounds.put(url, rect) },
                            onFileClick = { url, fileName ->
                                val resolved = com.aurora.chat.ui.chat.resolveMediaUrl(url)
                                val ext = fileName.substringAfterLast('.', "").lowercase()
                                val imgExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
                                val vidExts = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg")
                                when {
                                    ext in imgExts -> {
                                        // 图片 → 打开全屏图片预览（和图片消息气泡同一路径）
                                        val imgUrls = messages
                                            .filter { (it.mediaType == "image" || it.mediaType == "jpg" || it.mediaType == "png" || it.mediaType == "gif" || it.mediaType == "jpeg") && it.mediaUrl.isNotEmpty() && it.flashDuration == 0 && it.isRevoked != 1 }
                                            .map { resolveMediaUrl(it.mediaUrl) }
                                            .toMutableList()
                                        if (resolved !in imgUrls) imgUrls.add(0, resolved)
                                        // 先去重再算索引：否则重复项被 distinct 掉后起始页会错位
                                        val urls = imgUrls.distinct()
                                        val idx = urls.indexOf(resolved)
                                        imagePreviewUrls = urls
                                        imagePreviewIndex = if (idx >= 0) idx else 0
                                        heroInitialIndex = imagePreviewIndex
                                        heroSourceRect = heroRectFor(resolved, true)
                                        heroEnterDone = false
                                        previewReady = false
                                        previewSession++
                                    }
                                    ext in vidExts -> {
                                        // 视频 → 打开视频全屏播放器
                                        openVideoPlayer(resolved, true)
                                    }
                                    isTextViewableExt(ext) -> {
                                        filePreviewTarget = resolved to fileName
                                        filePreviewVisible = true
                                    }
                                    else -> {
                                        // 其它类型：弹窗询问是否下载
                                        fileDownloadConfirm = Triple(resolved, fileName, ext)
                                    }
                                }
                            },
                            onFileDownload = { url, fileName ->
                                val _resolved = com.aurora.chat.ui.chat.resolveMediaUrl(url)
                                // 走「下载管理」：有悬浮球 + 百分比 + 速度，完成后可直接打开/安装。
                                // 不再走「另存为」静默保存——那条路全程没有任何进度提示，用户随手退出即失败；
                                // 且失败时目标文件已被 SAF 创建，会留下一个 0 字节的坏文件，正是"保存后损坏"的观感来源。
                                startChatFileDownload(_resolved, fileName)
                            },
                            onCancelUpload = { localId ->
                                // 上传中即可撤回：取消后台压缩/上传并移除占位气泡
                                com.aurora.chat.data.upload.MediaUploadManager.cancelUpload(localId)
                            },
                            onOpenChatShare = { msgId, cardText ->
                                chatShareViewMsgId = msgId
                                chatShareViewText = cardText
                            },
                            uploadProgressMap = uploadProgressMap,
                            uploadStageMap = uploadStageMap,
                            uploadRatioMap = uploadRatioMap,
                            uploadFileNameMap = uploadFileNameMap,
                            onReasoningToggled = {
                                // 展开/收起思考过程时保持视角稳定（核心诉求：不漂移、不飞顶/底）。
                                // 两条路径分别处理：
                                //  · 本来就贴在底部(看最新)：保持贴底跟随，最新消息底部(含刚展开的思考)始终可见，且不跳。
                                //  · 已上滑看历史：钉住「展开前」的首条可见项，避免上方内容被展开顶移。
                                val atBottomNow = listState.firstVisibleItemIndex == 0 &&
                                    listState.firstVisibleItemScrollOffset <= BOTTOM_OFFSET_TOLERANCE_PX
                                if (atBottomNow) {
                                    autoFollow = true
                                } else {
                                    autoFollow = false
                                    // 先记下展开前的首条可见项作为锚点，并压制 snapshotFlow 在重排期间改写它；
                                    // 重排完成后把它钉回原位即可，无论被点的思考块在视口内还是视口下方都不会跳。
                                    anchorUpdateSuppressed = true
                                    anchorItemIndex = listState.firstVisibleItemIndex
                                    anchorItemOffset = listState.firstVisibleItemScrollOffset
                                    scope.launch {
                                        try {
                                            repeat(8) { androidx.compose.runtime.withFrameNanos { } }
                                            if (anchorItemIndex >= 0) {
                                                programmaticScroll(anchorItemIndex, anchorItemOffset)
                                            }
                                        } finally {
                                            anchorUpdateSuppressed = false
                                        }
                                    }
                                }
                            },
                            onCodeBackToTop = {
                                // 代码块「回到顶部」：把该条消息（代码块所在）滚动到可视区顶部
                                scope.launch {
                                    try { listState.scrollToItem(index.coerceAtLeast(0), 0) } catch (_: Exception) {}
                                }
                            },
                            expandedReasoningMsgId = aiStreamMsgId.value,
                            onContinueAi = {
                                // 「继续」：在同一消息内从断点续写。把已生成部分作为上下文中最后一条 assistant 内容，
                                // 让 AI 从该处继续追加（不重复部分文本），完成后移除「已手动停止 / 继续」。
                                if (friendId == AI_CHAT_ID) {
                                    val ci = messages.indexOfFirst { it.id == msg.id }
                                    if (ci >= 0 && messages[ci] is ChatMsg && !aiWriting.value) {
                                        val target = messages[ci] as ChatMsg
                                        if (target.manualStopped) {
                                            // 「继续处理任务」：模型无法真正对话重连，故改为以用户身份补发一条
                                            // 「继续完成未完成任务」指令，让模型基于既有上下文（含已生成的部分内容）
                                            // 从断点接着把任务做完（大厂式交互）。
                                            // 该新消息令上一条 AI 消息不再是「最后一条」，其「继续处理任务」按钮随之自动消失
                                            // （见 isLastMessage 判定）；主发送链路会清除全部 manualStopped 标记，令上一条「继续」功能失效。
                                            // 空白占位（一个字都没来得及输出）没有保留价值，直接移除：否则历史里会永久留一条空气泡；
                                            // 已有内容的气泡则只清停止标记、原地保留已生成内容。
                                            if (target.text.isBlank()) messages.removeAt(ci)
                                            else messages[ci] = target.copy(manualStopped = false, aiWaiting = false)
                                            // 用户可见文本保持一句话；执行约束（断点续做/不重复/不寒暄）改由
                                            // resumeTaskPending 触发、在 system 提示词里注入（见 RESUME_TASK_PROMPT），
                                            // 不再把一长串括号指令当作用户发言打在气泡里。
                                            inputText = "继续完成未完成的任务"
                                            resumeTaskPending = true
                                            sendUserTurn()
                                            }
                                        // ↓↓↓ 以下为旧版「就地续写」实现，已废弃（改用上面的「继续处理任务」指令模式），
                                        // 以 if(false) 隔离避免误执行，保留以最小化改动风险 ↓↓↓
                                        if (false) {
                                            @Suppress("UNUSED_VARIABLE") val baseText = target.text.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                                            val mode = AiChatManager.getMode(ctx, currentUserId)
                                            // 重建系统提示（与主发送链路一致的极致省/性格/高级选项）
                                            // 方案进行中:解除工具轮数硬墙,采用「完成即停」(大厂式终止)
                                            val planActiveB = messages.any { it is ChatMsg && it.text.contains("[[PLAN_CARD]]") }
                                                && !messages.any { it is ChatMsg && it.text.contains(AITaskCompletion.DELIVER_OPEN) }
                                            val sysText = buildString {
                                                // 「节省 Token / 极致节省」模式已移除；性格/自定义设定仅在「开启性格调试」为 ON 时注入
                                                if (AiChatManager.getPersonalityDebug(ctx, currentUserId)) {
                                                    append(AiChatManager.buildPersonaSystemPrompt(AiChatManager.getTraits(ctx, currentUserId), AiChatManager.getCustomTraits(ctx, currentUserId)))
                                                    if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) append("\n\n").append(AiChatManager.PERSONA_SWITCH_RELEASE)
                                                }
                                                // 回复模板：与其余两条链路保持同一口径（此分支当前不执行）。
                                                val templateInject = AiReplyTemplateStore.activeInject(ctx, currentUserId)
                                                if (templateInject.isNotEmpty()) {
                                                    if (length > 0) append("\n\n")
                                                    append(templateInject)
                                                }
                                            }
                                            // 系统提示注入已清理：system 仅保留性格调试内容。
                                            val fastOn = AiChatManager.isFastMode(ctx, currentUserId)
                                            val sysFinal = sysText
                                            // 去掉被续写的那条，并把已生成部分作为最后的 assistant 内容
                                            val cap = 40
                                            val cands = messages.filter { !it.isSystemNotice && it.text.isNotBlank() && it.text != AI_THINKING && it.id != msg.id }
                                            val clist = if (cands.size > cap) cands.take(1) + cands.takeLast(cap - 1) else cands
                                            val history = ((if (sysFinal.isNotBlank()) listOf("system" to sysFinal) else emptyList()) + clist.map { m ->
val role = if (m.isMine) "user" else "assistant"
                                                // 注入前清洗（与主发送链路一致）：剥交付块哨兵 + 工具状态哨兵
                                                val content = if (m.isMine) m.text else AiChatManager.sanitizeTextForModel(m.text)
                                                role to content
                                            }.filter { it.second.isNotBlank() } + listOf("assistant" to baseText)).toMutableList()
                                            // 清除旧的停止标记，进入续写状态
                                            messages[ci] = target.copy(manualStopped = false)
                                            // 续写所属会话在启动时绑定，后续保存/渲染以此为准，防止切会话后写错文件
                                            val myKey = AiConversationSession.storageKey()
                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = baseText, reasoningText = aiReplyState.value?.reasoningText ?: "", done = false)
                                            aiStopRequested = false
                                            aiThinkingLive.value = false
                                            aiWriting.value = true
                                            aiWritingConvId = AiConversationSession.activeConvId
                                            agentTaskInFlight.value = true
                                            agentTaskInFlightConvId = AiConversationSession.activeConvId
                                            // 【实时判定指向本条消息】liveNow = aiWriting && msgId == aiStreamMsgId。
                                            // 「确认发言」这条链路此前从不设置它，于是本条气泡的思考卡片/交付扫光全都挂不上
                                            // （除非恰好残留着上一条消息的 id）。
                                            aiStreamMsgId.value = msg.id
                                            activeAiJob = aiGlobalScope.launch {
                                                try {
                                                    // 付费 API 实时扣费参数：发送前快照余额与倍率，流式中估算成本，不足即掐断
                                                    val modeNow = AiChatManager.getMode(ctx, currentUserId)
                                                    val baseUsedT = aiUsageState.value.first
                                                    val liveEstInputT = history.sumOf { (_, c) -> if (c.isNotBlank()) AiChatManager.estimateTokens(c).toLong() else 0L }
                                                    var paidPoolAtStart = 0L
                                                    var paidInPrice = 0.0
                                                    var paidOutPrice = 0.0
                                                    var paidApiInsufficientTip = ""
                                                    if (modeNow == "paid_api") {
                                                        AiChatManager.syncServerLimit(ctx, currentUserId)
                                                        val (used, remaining) = AiChatManager.getOfficialUsage(ctx, currentUserId)
                                                        aiUsageState.value = Pair(used, remaining)
                                                        val rawBal = AiChatManager.getServerTokenBalance(ctx, currentUserId)
                                                        if (rawBal > 0L && rawBal < Long.MAX_VALUE) paidPoolAtStart = rawBal
                                                        try { AiChatManager.refreshPaidApiPrice(ctx, currentUserId) } catch (_: Exception) {}
                                                        val (inP, outP) = AiChatManager.getPaidApiPrice(ctx, currentUserId)
                                                        paidInPrice = inP; paidOutPrice = outP
                                                    }
                                                    // 多段思考：跨轮累积（reasoning_content），各段用哨兵拼接
                                                    var reasoningSegs = ""
                                                    var roundReasoning = ""
                                                    var thisRoundReasoning = ""  // 仅本轮思考，传给循环做内联嵌入（避免把累积思考重复内嵌）
                                                    // 对话级基线快照:任务开始前对 ai_files 全量拍照(每对话仅捕获一次,供二进制/截断场景兜底退回)
                                                    captureAIBaseline(ctx, AiConversationSession.storageKey())
                                                    val outcome = AiChatManager.runAIChatLoop(
                                                        maxRoundsOverride = if (planActiveB) Int.MAX_VALUE else null,
                                                        ctx = ctx, userId = currentUserId, history = history,
                                                        onNeedApproval = { tool, args -> toolApprovalHook(tool, args) },
                                                        onFileSent = { fs -> insertAiFileCard(fs.absPath, fs.forceFileCard, msg.id) },
                                                        onMediaGenerated = { t, url -> insertAiMediaCard(t, url, msg.id) },
                                                        onOpenVideo = { url -> openVideoPlayer(url, false) },
                                                        onPlanStepUpdate = { idx, st -> updatePlanStep(idx, st) },
                                                        onToolStage = { tool, st, argsJson, cumToolCalls, cumChanges, stages ->
                                                            // 工具状态直接内联进当前 AI 消息文本（哨兵块随正文持久化，绝不丢失）：
                                                            // 调用开始(0)追加「正在调用」状态行，完成(1/2)把同名状态行原地替换为「已调用/失败」，
                                                            // 让状态显示在调用真正发生的位置，而不是堆到所有结果之后。
                                                            // O(1) 索引快照定位；若流式期间发生过插入导致索引偏移，回退线性查找兜底，保证写回目标正确。
                                                            val fixedToolLog = AIUiPrefs.fixedToolChangeLog(ctx)
                                                            val rawIdx = msgIndexById[msg.id] ?: -1
                                                            val sIdx = if (rawIdx >= 0 && messages.getOrNull(rawIdx)?.id == msg.id) rawIdx
                                                                else messages.indexOfFirst { it.id == msg.id }
                                                            if (sIdx >= 0 && messages[sIdx] is ChatMsg) {
                                                                val cur = messages[sIdx] as ChatMsg
                                                                // 真实目标 + 参数摘要一次解析：状态行显示「搜索文件：xxx」，
                                                                // 点击状态行展开后能看到本次调用的关键参数（路径/命令/URL…）
                                                                val info = toolCallInfo(tool, argsJson)
                                                                val newText = if (st == 0) {
                                                                    // 工具开始执行 = 本轮思考已结束：立即复位「思考中」卡片。
                                                                    // 避免工具执行期间（尤其 ask_user 挂起等待用户回答时）残留的思考块
                                                                    // 叠加在工具状态行下方造成视觉压制（与「市场内容被思考中吞噬」同类问题）。
                                                                    aiThinkingLive.value = false
                                                                    AiChatManager.appendToolInline(cur.text, tool, 0, info.target, info.argsSummary)
                                                                } else {
                                                                    AiChatManager.replaceToolInline(
                                                                        cur.text, tool, st,
                                                                        target = info.target, argsSummary = info.argsSummary
                                                                    )
                                                                }
                                                                // 固定工具调用与修改日志：流式期间实时把调用数与改动写回消息，
                                                                // 让底部胶囊随每次调用/改动即时刷新（数字边跑边涨），并由 debounced 保存落盘，闪退不丢。
                                                                messages[sIdx] = cur.copy(
                                                                    text = newText,
                                                                    aiToolCallCount = if (fixedToolLog) cumToolCalls else cur.aiToolCallCount,
                                                                    aiToolStages = if (fixedToolLog) stages else cur.aiToolStages,
                                                                    aiFileChanges = if (fixedToolLog)
                                                                        AiChatManager.serializeFileChanges(AiChatManager.activeFileChangeRecorder ?: emptyList())
                                                                    else cur.aiFileChanges
                                                                )
                                                                scheduleAiDebouncedSave()
                                                                // 工具状态变化:同流式增量一样,钉住 anchor(节流跟随)
                                                                autoFollowBottom()
                                                            }
                                                            // 工具完成后同步刷新 UI 模式状态（Plan 模式下 AI 可能已用 set_agent_mode 自动切换到 craft）
                                                            agentMode = AiChatManager.getAIMode(ctx, currentUserId)
                                                        },
                                                        appActionHandler = onAppAction,
                                                        onProgress = { hint ->
                                                            val rawIdx = msgIndexById[msg.id] ?: -1
                                                            val sIdx = if (rawIdx >= 0 && messages.getOrNull(rawIdx)?.id == msg.id) rawIdx
                                                                else messages.indexOfFirst { it.id == msg.id }
                                                            if (sIdx >= 0 && messages[sIdx] is ChatMsg) {
                                                                messages[sIdx] = (messages[sIdx] as ChatMsg).copy(aiProgressHint = hint)
                                                            }
                                                        }
                                                    ) { nativeCalls, prefix, toolChoice ->
                                                        var finishReason = ""
                                                        var lastErrMsg = ""
                                                        val isFirstRound = prefix.isEmpty()
                                                        // 【性能】流式每帧都要拼一次完整展示文本。旧写法是
                                                        // `baseText + (prefix + "\n\n" + cleaned)` —— 三层嵌套拼接 = 每帧**两次**
                                                        // 整段字符串拷贝。而 baseText / prefix / isFirstRound 在一轮里都是常量，
                                                        // 头部完全可以提到 lambda 外面只算一次；头部为空（最常见：首轮且 baseText 空）时
                                                        // 连一次拷贝都不需要，cleaned 直接就是展示文本。
                                                        val streamHead = if (isFirstRound) {
                                                            baseText
                                                        } else if (prefix.isBlank()) {
                                                            baseText
                                                        } else {
                                                            baseText + prefix + "\n\n"
                                                        }
                                                        val shownFor = { frag: String ->
                                                            val cleaned = AiChatManager.stripToolBlocks(frag)
                                                            if (streamHead.isEmpty()) cleaned else streamHead + cleaned
                                                        }
                                                        // 【性能】流式 token 增量估算器（只统计新增字符，避免每个 delta 重扫整段输出）
                                                        val streamEst = StreamingTokenEstimator()
                                                        var done = false
                                                        var ft = ""
                                                        var u = 0L
                                                        var th = 0L
                                                        var tt = 0L
                                                        var ud: AiChatManager.AiUsageDetail? = null
                                                        var err = false
                                                        AiChatManager.streamChat(
                                                        ctx = ctx, userId = currentUserId, history = history,
                                                        stopCheck = { aiStopRequested },
                                                        onReasoningDelta = { reasoning ->
                                                            aiThinkingLive.value = true
                                                            roundReasoning = reasoning
                                                            // 【性能】思考写入节流（详见 StreamUiThrottle 说明）：思考量往往比正文还大，
                                                            // 不节流就是每 token 一次整段思考重解析。丢帧不丢内容，onDone 会用完整累积思考兜底。
                                                            if (StreamUiThrottle.due(msg.id)) {
                                                                // 每轮思考都实时刷新：已累积段 + 本轮思考，分段展示；退出「等待模型响应」空窗
                                                                val live = AiChatManager.appendReasoningSegment(reasoningSegs, reasoning)
                                                                // 【P0-2 性能】移除对 messages 的直接写入：思考增量统一经由 aiReplyState，
                                                                // 由下方 collect 协程单一路径写回 messages（避免「回调写一次 + collect 又写一次」双重写入）。
                                                                aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(reasoningText = live)
                                                                // 流式增量滚动跟随（节流，用户手势优先）
                                                                autoFollowBottom()
                                                                if (modeNow == "paid_api" && paidPoolAtStart > 0L && paidPoolAtStart < Long.MAX_VALUE && (paidInPrice > 0.0 || paidOutPrice > 0.0)) {
                                                                    val cost = kotlin.math.ceil(liveEstInputT * paidInPrice).toLong()
                                                                    aiUsageState.value = Pair(baseUsedT + cost, (paidPoolAtStart - cost).coerceAtLeast(0))
                                                                }
                                                            }
                                                        },
                                                        onDelta = { cur ->
                                                            aiThinkingLive.value = false
                                                            // 【性能】UI 写入节流（详见 StreamUiThrottle）：cur 是累积全文，丢帧不丢内容
                                                            if (StreamUiThrottle.due(msg.id)) {
                                                                val shown = shownFor(cur)
                                                                // 【P0-2 性能】移除对 messages 的直接写入：文本增量统一经由 aiReplyState，
                                                                // 由下方 collect 协程单一路径写回 messages（避免「回调写一次 + collect 又写一次」双重写入）。
                                                                // manualStopped 等保护在 collect 写回时统一判断。
                                                                aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = shown)
                                                                // 流式增量滚动跟随（节流，用户手势优先）
                                                                autoFollowBottom()
                                                            }
                                                            // 付费 API 实时预估：不足立即掐断流式输出
                                                            if (modeNow == "paid_api" && paidPoolAtStart > 0L && paidPoolAtStart < Long.MAX_VALUE && (paidInPrice > 0.0 || paidOutPrice > 0.0)) {
                                                                val outEst = streamEst.estimate(cur).toLong()
                                                                val cost = kotlin.math.ceil(liveEstInputT * paidInPrice).toLong() + kotlin.math.ceil(outEst * paidOutPrice).toLong()
                                                                val liveRemaining = (paidPoolAtStart - cost).coerceAtLeast(0)
                                                                aiUsageState.value = Pair(baseUsedT + cost, liveRemaining)
                                                                if (liveRemaining <= 0L && !aiStopRequested) {
                                                                    aiStopRequested = true
                                                                    paidApiInsufficientTip = "\n\n（余额不足，已停止输出）"
                                                                    // 此分支可能落在节流窗口内（本轮尚未写入 UI），所以用 cur 现算一次文本
                                                                    val finalShown = shownFor(cur) + paidApiInsufficientTip
                                                                    // 仍只走 aiReplyState 单一写入路径（collect 写回时带 manualStopped 保护）
                                                                    aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = finalShown)
                                                                    onTokenExhausted()
                                                                }
                                                            }
                                                        },
                                                        onDone = { usage, full, thinkingSeconds, totalSeconds ->
                                                            aiThinkingLive.value = false
                                                            done = true
                                                            ft = full
                                                            u = usage
                                                            th = thinkingSeconds
                                                            tt = totalSeconds
                                                            // 本轮思考完整 → 并入多段思考累积
                                                            thisRoundReasoning = roundReasoning
                                                            reasoningSegs = AiChatManager.appendReasoningSegment(reasoningSegs, roundReasoning)
                                                            roundReasoning = ""
                                                            val shownRaw = shownFor(full)
                                                            // 若已被付费 API 余额掐断，把「余额不足」提示保留在最终正文尾部
                                                            val shown = if (paidApiInsufficientTip.isNotBlank() && !shownRaw.endsWith(paidApiInsufficientTip)) shownRaw + paidApiInsufficientTip else shownRaw
                                                            val i = messages.indexOfFirst { x -> x.id == msg.id }
                                                            if (i >= 0) {
                                                                val keepReasoning = if (AiChatManager.getShowReasoning(ctx, currentUserId)) reasoningSegs else (messages[i] as ChatMsg).reasoningText
                                                                // 流式每帧都要过一次：用 indexOf 快路径版，无标记时零拷贝
                                                                messages[i] = (messages[i] as ChatMsg).copy(text = AiChatManager.stripMultiSplit(shown), reasoningText = keepReasoning, aiWaiting = false)
                                                            }
                                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = shown)
                                                            scheduleAiDebouncedSave()
                                                        },
                                                        onError = { errMsg ->
                                                            aiThinkingLive.value = false
                                                            err = true
                                                            lastErrMsg = errMsg
                                                            // 修复 UI 状态机误报：onError 不再复位按钮状态——runAIChatLoop 后台可能仍在
                                                            // 重试（可重试错误）/ 裁剪上下文 / 规划生成 / 等待工具响应 / 执行 verifyDelivery 收尾。
                                                            // 按钮保持「停止」直至任务真正结束（runAIChatLoop 返回、outcome 分支统一复位）。
                                                            //
                                                            // 【可重试错误绝不污染气泡】断流 / 网络抖动会被 runAIChatLoop 原地重试并继续生成。
                                                            // 旧实现在这里**无条件**把消息标成 manualStopped=true 并写入 error，造成两个后果：
                                                            //   ① 气泡里闪出一句「请求失败: …」（就是用户看到的 EOFException）；
                                                            //   ② manualStopped 一旦置真，下方 aiReplyState.collect 写回路径会直接 return
                                                            //      （见「手动停止后不再被流式增量覆盖」），于是重试成功后的正文再也写不进气泡 ——
                                                            //      表现就是"任务没中断，但内容不走了"。
                                                            // 现在可重试的分支只留日志、气泡继续保持「等待模型响应」。
                                                            if (AITaskCompletion.isRetriable(errMsg)) {
                                                                com.aurora.chat.ModelLogger.warn(
                                                                    ctx, "ERR", "本轮可重试错误，静默重试中 | $errMsg"
                                                                )
                                                            } else {
                                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(error = errMsg, done = true)
                                                            // 仅当仍停留在本会话才操作当前内存列表；已切走则把占位标记合并写回所属会话文件
                                                            if (AiConversationSession.storageKey() == myKey) {
                                                                val i = messages.indexOfFirst { x -> x.id == msg.id }
                                                                if (i >= 0 && !(messages[i] as ChatMsg).manualStopped) {
                                                                    messages[i] = (messages[i] as ChatMsg).copy(manualStopped = true, aiWaiting = false)
                                                                }
                                                                val snap = messages.filter { !it.isSystemNotice }.toList()
                                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                                                            } else {
                                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, myKey, msg.id, manualStopped = true) } catch (_: Exception) {} }
                                                            }
                                                            if (errMsg.contains("已被管理员暂停")) apiSuspendedDialog = true else android.widget.Toast.makeText(ctx, errMsg, android.widget.Toast.LENGTH_LONG).show()
                                                            }
                                                        },
                                                        onTokenInsufficient = onTokenExhausted,
                                                        nativeToolAccumulator = nativeCalls,
                                                        enableAITools = agentMode != AiChatManager.MODE_ASK,
                                                        toolChoice = toolChoice,
                                                        onFinish = { r -> finishReason = r },
                                                        onUsageDetail = { d -> ud = d }
                                                    )
                                                    AiChatManager.AIRoundResult(done && !err, ft, u, th, tt, ud, thisRoundReasoning, finishReason, if (err) com.aurora.chat.ui.chat.AITaskCompletion.isRetriable(lastErrMsg) else false, if (err) lastErrMsg else "")
                                                }
                                                if (outcome.clean) {
                                                    val stillHere = AiConversationSession.storageKey() == myKey
                                                    val i = if (stillHere) messages.indexOfFirst { x -> x.id == msg.id } else -1
                                                    val finalReasoning = if (i >= 0 && AiChatManager.getShowReasoning(ctx, currentUserId)) (messages[i] as ChatMsg).reasoningText else ""
                                                    var accumulated = outcome.accumulated
                                                    // 兜底：循环正常结束但正文（剥离内联思考后）为空，说明模型只想没说；补一句占位
                                                    if (accumulated.isNotBlank() && AiChatManager.stripInlineReasoning(accumulated).isBlank()) {
                                                        accumulated = accumulated + "\n\n（AI 未生成有效回复内容）"
                                                    }
                                                    val combined = baseText + accumulated
                                                    aiWriting.value = false
                                                    aiWritingConvId = -1L
                                                    aiStopRequested = false
                                                    activeAiJob = null
                                                    agentTaskInFlight.value = false
                                                    agentTaskInFlightConvId = -1L
                                                    aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = combined, done = true)
                                                    if (stillHere) {
                                                        if (i >= 0) messages[i] = (messages[i] as ChatMsg).copy(aiMode = mode, text = combined, reasoningText = finalReasoning, manualStopped = false, thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds, inputTokens = outcome.usageDetail?.inputTokens ?: 0, outputTokens = outcome.usageDetail?.outputTokens ?: 0, cacheHitTokens = outcome.usageDetail?.cacheHitTokens ?: 0, contextLength = outcome.usageDetail?.contextLength ?: 0, aiWaiting = false, aiProgressHint = "", aiFileChanges = AiChatManager.serializeFileChanges(outcome.fileChanges), aiToolCallCount = outcome.toolCallCount, aiToolStages = outcome.toolCalls)
                                                        // 工具状态行动画收回:已调用/失败/调用中全部不遗留
                                                        collapseToolStatusMessages(messages)
                                                        val snap = messages.filter { !it.isSystemNotice }.toList()
                                                        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                                                    } else {
                                                        // 已切走：把完整续写结果合并写回所属会话文件，用户切回即可见
                                                        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, myKey, msg.id, text = combined, reasoningText = finalReasoning, thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds, inputTokens = outcome.usageDetail?.inputTokens, outputTokens = outcome.usageDetail?.outputTokens, cacheHitTokens = outcome.usageDetail?.cacheHitTokens, contextLength = outcome.usageDetail?.contextLength, fileChangesJson = AiChatManager.serializeFileChanges(outcome.fileChanges), toolCallCount = outcome.toolCallCount, toolStages = outcome.toolCalls) } catch (_: Exception) {} }
                                                    }
                                                    // 真实消耗模式（个人 / 官方 / 第三方 / 付费，非免费共享接口 free_api）均累加「今日已用」，
                                                    // 口径与每条消息的 token 明细一致：input+output 真实 token 之和，无倍率换算。
                                                    if (mode != "free_api" && outcome.usage > 0) {
                                                        AiChatManager.addOfficialUsage(ctx, currentUserId, outcome.usage)
                                                    }
                                                    // 付费 API：计费与扣款已由后端中转接口完成（权威计量），这里只算展示用的「已扣费」，
                                                    // 不再向服务端上报 token——重复上报会造成二次扣费。
                                                    if (mode == "paid_api") {
                                                        val ud2 = outcome.usageDetail
                                                        if (ud2 != null && (ud2.inputTokens > 0 || ud2.outputTokens > 0)) {
                                                            val (paidInPrice, paidOutPrice) = AiChatManager.getPaidApiPrice(ctx, currentUserId)
                                                            val cost = if (paidInPrice > 0.0 || paidOutPrice > 0.0) {
                                                                kotlin.math.ceil(ud2.inputTokens * paidInPrice).toLong() +
                                                                    kotlin.math.ceil(ud2.outputTokens * paidOutPrice).toLong()
                                                            } else 0L
                                                            if (cost > 0L) {
                                                                if (i >= 0) {
                                                                    messages[i] = (messages[i] as ChatMsg).copy(aiCostDeducted = cost)
                                                                    val snap2 = messages.filter { !it.isSystemNotice }.toList()
                                                                    aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap2) } catch (_: Exception) {} }
                                                                } else {
                                                                    aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, myKey, msg.id, aiCostDeducted = cost) } catch (_: Exception) {} }
                                                                }
                                                            }
                                                            aiGlobalScope.launch {
                                                                try {
                                                                    AiChatManager.syncServerLimit(ctx, currentUserId)
                                                                    aiUsageState.value = AiChatManager.getOfficialUsage(ctx, currentUserId)
                                                                } catch (_: Exception) {}
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // ═══ 续写链路中断收尾（手动停止/出错等 clean=false 路径）═══
                                                    // 与主链路一致：中断也把已发生的真实改动/工具调用写回消息并落盘，
                                                    // 底部「查看所有改动」「调用 N 个工具」按钮不因中断而消失、改动不丢。
                                                    aiWriting.value = false
                                                    aiWritingConvId = -1L
                                                    aiStopRequested = false
                                                    activeAiJob = null
                                                    agentTaskInFlight.value = false
                                                    agentTaskInFlightConvId = -1L
                                                    aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(done = true)
                                                    val hasCalls = outcome.toolCallCount > 0
                                                    val hasChanges = outcome.fileChanges.isNotEmpty()
                                                    if (hasCalls || hasChanges) {
                                                        val stillHere = AiConversationSession.storageKey() == myKey
                                                        val j = if (stillHere) messages.indexOfFirst { x -> x.id == msg.id } else -1
                                                        if (j >= 0) {
                                                            val cur = messages[j] as ChatMsg
                                                            // 中断时气泡若还停在占位态(没有任何正文),把循环里算出的正文写回去
                                                            // ——否则 outcome.accumulated 里的兜底交付/中断说明会被整段丢弃,用户只看到"停住了"
                                                            val needText = (cur.text.isBlank() || cur.text == AI_THINKING) && outcome.accumulated.isNotBlank()
                                                            messages[j] = cur.copy(
                                                                text = if (needText) outcome.accumulated else cur.text,
                                                                aiFileChanges = if (hasChanges) AiChatManager.serializeFileChanges(outcome.fileChanges) else cur.aiFileChanges,
                                                                aiToolCallCount = if (hasCalls) outcome.toolCallCount else cur.aiToolCallCount,
                                                                aiToolStages = if (hasCalls) outcome.toolCalls else cur.aiToolStages
                                                            )
                                                            val snap = messages.filter { !it.isSystemNotice }.toList()
                                                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                                                        } else if (!stillHere) {
                                                            // 已切走会话：onError 已写过 manualStopped，这里补写工具/改动字段（合并式更新）
                                                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                                try {
                                                                    patchAiReplyInFile(
                                                                        ctx, currentUserId, myKey, msg.id,
                                                                        fileChangesJson = if (hasChanges) AiChatManager.serializeFileChanges(outcome.fileChanges) else "",
                                                                        toolCallCount = if (hasCalls) outcome.toolCallCount else 0,
                                                                        toolStages = if (hasCalls) outcome.toolCalls else null
                                                                    )
                                                                } catch (_: Exception) {}
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (_: Exception) {
                                                    aiWriting.value = false
                                                    aiStopRequested = false
                                                    activeAiJob = null
                                                    agentTaskInFlight.value = false
                                                    agentTaskInFlightConvId = -1L
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        // 「已读显示」开启时，气泡下方一行浅灰小字。
                        // 群聊可点开看谁读了；私聊只标最后一条自己发的。
                        // 「气泡左侧」模式走 MessageBubble 内部，这里不再画（否则会出现两处）。
                        if (readLabelForItem != null && !readReceiptLeft) {
                            // 群聊：气泡内容整体上移了 10dp（见 MessageBubble 的 groupConvId<0 补偿），
                            // 布局盒底部会空出一截；这里跟着上提，否则这行字离气泡会明显偏下。
                            val readLabelLift = if (isGroupChat(friendId)) 9.dp else 1.dp
                            // 私聊：整行都能点，不是只有那两个小字能点。
                            // 「未读」只有 11sp，点字本身极其容易点到旁边的空白 —— 表现就是
                            // 「我点了，像没反应」。点一下 = 只静默重查这一条，不提示、不弹窗。
                            val canProbe = !isGroupChat(friendId) && rmsg.serverId > 0
                            // 点击 = 把上面的探针立刻踢醒，当场查这一次。请求只有探针那一条路径，
                            // 所以连点十下也只发一次（Channel CONFLATED），不会叠请求、不会互相覆盖。
                            val probeOnce: () -> Unit = { readProbeKick.trySend(Unit) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = -readLabelLift)
                                    // 让这行字贴着气泡右边缘，而不是被头像挤到最右边
                                    .padding(end = ChatAvatarSize + 6.dp)
                                    .then(
                                        if (!canProbe) Modifier else Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            // 不要水波纹：这行只是 11sp 的浅灰小字，点一下弹一圈涟漪
                                            // 比字本身还显眼，像点了个按钮。群聊那行「N人已读」保留水波纹
                                            // （它确实是个入口，点了会弹已读名单）。
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { probeOnce() }
                                    ),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = readLabelForItem!!,
                                    fontSize = 11.sp,
                                    color = com.aurora.chat.ui.theme.appTextHint(),
                                    // 群聊：点开「谁读了」名单。私聊的点击已经上移到整行（见上面的 then）。
                                    modifier = if (isGroupChat(friendId) && rmsg.serverId > 0) {
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { readSheetMsgId = rmsg.serverId }
                                            .padding(horizontal = 2.dp, vertical = 1.dp)
                                    } else Modifier
                                )
                            }
                        }
                        } // ← 关闭 Column(提示 + 气泡)
                        } // ← 关闭该消息的 item{} 内容块
                    } // ← 关闭 forEach
                }
            }

            // 发送失败重发确认弹窗（点气泡左侧红叹号触发）
            val retryTarget = retryTargetMsg
            if (retryTarget != null) {
                val retryPreview = when {
                    retryTarget.mediaType == "image" || retryTarget.mediaType == "jpg" -> "[图片]"
                    retryTarget.mediaType == "video" || retryTarget.mediaType == "mp4" -> "[视频]"
                    retryTarget.mediaType == "file" -> "[文件]"
                    retryTarget.mediaType == "voice" -> "[语音]"
                    retryTarget.text.isNotBlank() -> retryTarget.text
                    else -> "[消息]"
                }
                AlertDialog(
                    onDismissRequest = { retryTargetMsg = null },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("重新发送", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                "这条消息没有发送成功，是否重新发送？",
                                fontSize = 14.sp,
                                color = com.aurora.chat.ui.theme.appTextSecondary()
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = retryPreview.take(80),
                                fontSize = 13.sp,
                                color = com.aurora.chat.ui.theme.appTextSecondary(),
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(com.aurora.chat.ui.theme.appCardBg())
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                // 点确认即刻关弹窗，之后就是一次再普通不过的发送：
                                // 成功 → 气泡转正去掉红叹号；失败 → 气泡原样留着、红叹号继续亮。
                                // 不再把弹窗卡在「发送中…」上等结果，避免多出一层特殊状态。
                                val target = retryTarget
                                retryTargetMsg = null
                                resendFailedMessage(target)
                            }
                        ) {
                            Text(
                                "重新发送",
                                color = Color(0xFF1E40AF),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { retryTargetMsg = null }) {
                            Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                        }
                    }
                )
            }

            // 删除确认弹窗
            if (deleteConfirmMsgId != 0L) {
                AlertDialog(
                    onDismissRequest = { deleteConfirmMsgId = 0L },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("确认删除", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            when {
                                // 发送失败消息：纯本地占位，删了就不再重发
                                deleteConfirmMsgId < 0L -> "确认删除这条发送失败的消息？删除后无法重新发送。"
                                isNotificationChat(friendId) -> "确认删除此条通知？删除后将从通知中心移除，且无法恢复。"
                                else -> "确认删除此条消息，确认后将删除本地消息"
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val targetId = deleteConfirmMsgId
                            // 发送失败消息：不在服务端，删除 = 移出失败队列 + 抹掉落盘记录，绝不走删除接口
                            if (targetId < 0L) {
                                pendingFailedMessages.removeAll { it.id == targetId }
                                messages.removeAll { it.id == targetId }
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    com.aurora.chat.data.local.FailedMessageStore.remove(ctx, targetId)
                                }
                                deleteConfirmMsgId = 0L
                                return@TextButton
                            }
                            // 本地对话额外：先删媒体文件（本地 file:// + 服务器 /chat-media/ 生成图）
                            if (isLocalChat(friendId)) {
                                for (msg in messages) {
                                    if (msg.id == targetId && msg.mediaUrl.isNotEmpty()) {
                                        val u = msg.mediaUrl
                                        if (u.startsWith("file://")) {
                                            try { java.io.File(u.removePrefix("file://")).delete() } catch (_: Exception) {}
                                        }
                                        val marker = "/chat-media/"
                                        val idx = u.indexOf(marker)
                                        if (idx >= 0) {
                                            val name = u.substring(idx + marker.length).substringBefore('?').substringAfterLast('/')
                                            aiGlobalScope.launch { try { AuroraApi.deleteChatMedia(name) } catch (_: Exception) {} }
                                        }
                                        break
                                    }
                                }
                            }
                            // === 所有对话统一用 deletedMessageIds（其他对话有效，self_chat 也一样） ===
                            val newDeleted = deletedMessageIds + targetId
                            deletedMessageIds = newDeleted
                            try {
                                val delFile = java.io.File(ctx.filesDir, "deleted_msgs_${currentUserId}.json")
                                val arr = org.json.JSONArray(newDeleted.toList())
                                delFile.writeText(arr.toString())
                            } catch (_: Exception) {}
                            // AI / 本地对话：真正删除——物理移除快照条目并移出内存，避免下次重进/新对话再次出现、或进入下一条 AI 请求上下文
                            if (isLocalChat(friendId)) {
                                try {
                                    val key = localChatKey(friendId)
                                    val f = localChatMessagesFile(ctx, currentUserId, key)
                                    if (f.exists()) {
                                        val arr = org.json.JSONArray(f.readText())
                                        val out = org.json.JSONArray()
                                        for (i in 0 until arr.length()) {
                                            val o = arr.optJSONObject(i) ?: continue
                                            if (o.optLong("id", 0L) != targetId) out.put(o)
                                        }
                                        f.writeText(out.toString())
                                    }
                                } catch (_: Exception) {}
                                messages.removeAll { it.id == targetId }
                            }
                            if (isNotificationChat(friendId)) {
                                // 通知中心：从本地通知存储真正移除该条，避免重新进入又出现
                                try {
                                    val remaining = messages.filter { it.id != targetId }
                                    val toSave = remaining.map { m ->
                                        ChatMsg.create(
                                            serverId = m.id, text = m.text, isMine = m.isMine,
                                            isSystemNotice = m.isSystemNotice, createdAt = m.createdAt, isNew = false
                                        )
                                    }
                                    saveNotificationMessages(ctx, currentUserId, toSave)
                                } catch (_: Exception) {}
                            } else {
                                // 普通对话：更新会话列表预览（最后一条未删除消息）
                                val lastUndeleted = messages.lastOrNull { it.id !in newDeleted && (!it.isSystemNotice || it.flashDuration == -1) }
                                if (lastUndeleted != null) {
                                    val prev = when {
                                        lastUndeleted.mediaType == "image" || lastUndeleted.mediaType == "jpg" -> "[图片]"
                                        lastUndeleted.mediaType == "video" || lastUndeleted.mediaType == "mp4" -> "[视频]"
                                        lastUndeleted.mediaType == "file" -> "[文件]"
                                        lastUndeleted.mediaType == "voice" -> "[语音]"
                                        lastUndeleted.mediaType == "transfer" -> "[转账]"
                                        else -> lastUndeleted.text
                                    }
                                    ChatViewModel.updateConversationPreview(friendId, prev, lastUndeleted.createdAt)
                                }
                            }
                            deleteConfirmMsgId = 0L
                            }) { Text("删除", color = Color(0xFFDC2626)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteConfirmMsgId = 0L }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                    }
                )
            }

            // 点击群公告卡片 → 切入全屏群公告详情页（与群设置里的公告详情页一致，不再是弹窗）
            annDetailRaw?.let { raw ->
                val dText = parseGroupAnnouncementText(raw)
                val dAnn = GroupAnnouncementData(
                    text = dText,
                    images = parseGroupAnnouncementImages(raw),
                    confirmedByMe = dText in confirmedAnnouncementTexts,
                    // 发布日期取卡片消息的发送时间（epoch 秒，formatAnnouncementDate 会归一化为毫秒）
                    createdAt = annDetailTime
                )
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { annDetailRaw = null },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false
                    )
                ) {
                    AnnouncementDetailPage(
                        announcement = dAnn,
                        canEdit = false,
                        canConfirm = false,
                        showFlags = false,
                        onBack = { annDetailRaw = null },
                        onEdit = {},
                        onDelete = {},
                        onConfirm = {}
                    )
                }
            }

            // 点击公告图片 → 全屏查看（卡片缩略图 / 弹窗缩略图共用，支持双指缩放）
            annImageViewerUrl?.let { bigUrl ->
                AnnouncementFullscreenImageViewer(bigUrl) { annImageViewerUrl = null }
            }

            // 群成员禁言面板：直接复用群设置里那套「禁言成员」模块（搜索 / 列表 / 时长选择 / 解禁全都不改），
            // 只是多一个入口。用全屏 Dialog 承载：面板本身是 fillMaxSize 的底部抽屉，
            // 直接挂在页面 Column 里会挤掉消息区高度（列表瞬间塌 0），包一层 Dialog 它就自成一层。
            if (muteTargetUserId > 0L) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { muteTargetUserId = 0L },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false
                    )
                ) {
                    MuteMemberPanel(
                        groupId = -(friendId + 1000),
                        currentUserId = currentUserId,
                        isAdmin = isAdminOrOwner,
                        isOwner = isGroupOwner,
                        // 预填被长按成员的名字：打开即定位到这个人，清空搜索框就是完整成员列表
                        initialKeyword = muteTargetUserName,
                        onDismiss = { muteTargetUserId = 0L }
                    )
                }
            }

            // ==================== AI 附件发送过渡提示（大文件处理期间全屏遮罩） ====================
            aiFileSendingTip?.let { tip ->
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = {},
                    properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0x66000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Text(tip, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ==================== 开发者广播：发送弹窗（仅 ID=1 可见） ====================
            if (showBroadcastSendDialog) {
                // 多条广播，每条独立：新增条目默认空白，绝不携带上一条内容
                data class BcItem(val text: String, val count: String)
                val bcItems = remember { mutableStateListOf(BcItem("", "1")) }
                var sending by remember { mutableStateOf(false) }
                // 发送目标固定：始终发往当前所在会话（私信→该好友，普通群→该群，官方群→官方群）
                val bcCurName = friendName.ifBlank { if (isGroupChat(friendId)) "本群" else "对方" }
                AlertDialog(
                    onDismissRequest = { showBroadcastSendDialog = false },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("发送系统广播", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            // —— 发送目标：固定为当前所在会话，不可切换 ——
                            Text("发送目标", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = when {
                                        isGroupChat(friendId) -> "当前群聊（$bcCurName）"
                                        friendId > 0 -> "当前私聊（$bcCurName）"
                                        else -> "当前会话"
                                    },
                                    color = com.aurora.chat.ui.theme.appTextSecondary(), fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            bcItems.forEachIndexed { idx, item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        OutlinedTextField(
                                            value = item.count,
                                            onValueChange = { bcItems[idx] = item.copy(count = it.filter { c -> c.isDigit() }.take(7)) },
                                            label = { Text("数量 #${idx + 1}") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        OutlinedTextField(
                                            value = item.text,
                                            onValueChange = { bcItems[idx] = item.copy(text = it) },
                                            label = { Text("广播内容 #${idx + 1}") },
                                            modifier = Modifier.fillMaxWidth().height(80.dp),
                                            singleLine = false
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    TextButton(onClick = { if (bcItems.size > 1) bcItems.removeAt(idx) }) {
                                        Text("删除", color = Color(0xFFDC2626), fontSize = 12.sp)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            TextButton(onClick = { bcItems.add(BcItem("", "1")) }) {
                                Text("＋ 添加一条广播", color = Color(0xFF2563EB), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (sending) return@TextButton
                            val valid = bcItems.filter { it.text.trim().isNotBlank() && (it.count.toIntOrNull() ?: 0) >= 1 }
                            if (valid.isEmpty()) {
                                android.widget.Toast.makeText(ctx, "请至少填写一条有效内容", android.widget.Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            // 固定目标：始终是当前所在会话（私信=该好友，普通群=该群，官方群=官方群）
                            val toUser = friendId
                            if (!(toUser > 0 || isGroupChat(toUser))) {
                                android.widget.Toast.makeText(ctx, "当前会话不支持广播", android.widget.Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            sending = true
                            showBroadcastSendDialog = false
                            scope.launch {
                                try {
                                    // 逐条独立发送，每条互不干扰（不会把上一条内容带进来）
                                    valid.forEach { com.aurora.chat.data.api.AuroraApi.broadcastSend(it.text.trim(), it.count.toInt(), toUser) }
                                    withContext(Dispatchers.Main) {
                                        ChatViewModel.notifyNewMessage()
                                        android.widget.Toast.makeText(ctx, "已发送到当前会话", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(ctx, "发送失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } finally {
                                    sending = false
                                }
                            }
                        }) { Text("发送", color = Color(0xFF2563EB), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBroadcastSendDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                    }
                )
            }

            // ==================== 开发者广播：长按管理弹窗（删除单个 / 撤回全部） ====================
            if (broadcastManageMsgId > 0L) {
                AlertDialog(
                    onDismissRequest = { broadcastManageMsgId = 0L },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("广播管理", fontWeight = FontWeight.Bold) },
                    text = { Text("选择要执行的操作：") },
                    confirmButton = {
                        TextButton(onClick = {
                            val tid = broadcastManageTaskId
                            broadcastManageMsgId = 0L
                            scope.launch {
                                try {
                                    com.aurora.chat.data.api.AuroraApi.broadcastRecallAll(tid)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(ctx, "已撤回全部广播", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(ctx, "撤回失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }) { Text("撤回所有", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                val mid = broadcastManageMsgId
                                broadcastManageMsgId = 0L
                                scope.launch {
                                    try {
                                        com.aurora.chat.data.api.AuroraApi.broadcastDeleteSingle(mid)
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(ctx, "已删除该广播", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(ctx, "删除失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }) { Text("删除单个", color = Color(0xFF2563EB)) }
                            TextButton(onClick = { broadcastManageMsgId = 0L }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                        }
                    }
                )
            }

            // 多选删除确认（仅删除选中的第一层消息）
            if (multiDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { multiDeleteConfirm = false },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("确认删除", fontWeight = FontWeight.Bold) },
                    text = { Text("确认删除选中的 ${selectedMsgIds.value.size} 条消息？确认后将删除本地消息，且无法恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            val ids = selectedMsgIds.value
                            if (ids.isNotEmpty()) {
                                // 所有对话统一用 deletedMessageIds，并持久化到本地删除列表
                                val newDeleted = deletedMessageIds + ids
                                deletedMessageIds = newDeleted
                                try {
                                    val delFile = java.io.File(ctx.filesDir, "deleted_msgs_${currentUserId}.json")
                                    val arr = org.json.JSONArray(newDeleted.toList())
                                    delFile.writeText(arr.toString())
                                } catch (_: Exception) {}
                            }
                            multiDeleteConfirm = false
                            exitMultiSelect()
                            }) { Text("删除", color = Color(0xFFDC2626)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { multiDeleteConfirm = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                    }
                )
            }

            // 多选 → 分享聊天：把选中的消息作为一张「分享聊天」卡片分享给好友 / 群聊
            if (showChatShareDialog) {
                // 预览层状态：点预览卡片进入「聊天记录」全屏页；返回只关这一层，分享面板和已选对象都保留
                var sharePreviewOpen by remember { mutableStateOf(false) }
                var sharePreviewImage by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showChatShareDialog = false },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                        // 让面板自带遮罩一直盖到状态栏下方：不开启时窗口只到状态栏下沿，
                        // 顶部系统栏区域只剩一层系统 dim，比下面 45% 的遮罩亮一截，看起来像没压黑
                        decorFitsSystemWindows = false
                    )
                ) {
                    // 关掉窗口默认 dim：面板自己已经画了 45% 遮罩，叠加系统 dim 会让整屏忽深忽浅
                    val shareDialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
                    LaunchedEffect(shareDialogWindow) { shareDialogWindow?.setDimAmount(0f) }
                    Box(Modifier.fillMaxSize()) {
                        com.aurora.chat.ui.community.SharePostDialog(
                            postTitle = "分享聊天",
                            postContent = "",
                            postId = 0L,
                            postUserId = currentUserId,
                            postUsername = currentUserName,
                            postType = "chat",
                            titlePrefix = "分享聊天",
                            idKey = "chat_id",
                            customMessage = chatShareCardText,
                            preview = {
                                // 预览区直接放真实卡片：点一下就能进聊天记录逐条核对，
                                // 不用退出分享面板再回去翻。
                                // 宽度不加 compact：保持和聊天里一致的 200~260dp 区间，并整体居中，
                                // 否则预览卡片会比聊天里窄一截、还贴在左边。
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ShareCard(
                                        text = chatShareCardText,
                                        isMine = true,
                                        onChatClick = { sharePreviewOpen = true },
                                        framed = true
                                    )
                                }
                            },
                            onSent = {
                                showChatShareDialog = false
                                exitMultiSelect()
                            },
                            onDismiss = { showChatShareDialog = false }
                        )
                        // 「聊天记录」全屏页盖在分享面板之上，带左右滑入滑出
                        androidx.compose.animation.AnimatedVisibility(
                            visible = sharePreviewOpen,
                            modifier = Modifier.fillMaxSize(),
                            enter = slideInHorizontally(animationSpec = tween(260)) { it } + fadeIn(tween(180)),
                            exit = slideOutHorizontally(animationSpec = tween(260)) { it } + fadeOut(tween(180))
                        ) {
                            ChatShareFullScreen(
                                text = chatShareCardText,
                                friendId = friendId,
                                onBack = { sharePreviewOpen = false },
                                // 图片查看层打开时把返回键让给图片层，先关图再关这一页
                                backEnabled = sharePreviewImage == null,
                                onImageClick = { urls, index -> sharePreviewImage = urls to index }
                            )
                        }
                        // 轻量看图（只为核对内容）：点任意位置或返回即关闭
                        sharePreviewImage?.let { (urls, index) ->
                            BackHandler { sharePreviewImage = null }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { sharePreviewImage = null },
                                contentAlignment = Alignment.Center
                            ) {
                                coil.compose.AsyncImage(
                                    model = urls.getOrNull(index),
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // 官方 API 被管理员暂停的明确提示：需要普通用户一眼看懂，避免误以为发送 bug
            if (apiSuspendedDialog) {
                AlertDialog(
                    onDismissRequest = { apiSuspendedDialog = false },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("官方 API 已暂停", fontWeight = FontWeight.Bold, color = Color(0xFFB45309)) },
                    text = { Text("管理员已暂停官方 AI 接口，本次消息未发送。\n\n你可以：\n· 在右上角「使用」中切换为个人 API；\n· 或等待管理员恢复后重试。", color = com.aurora.chat.ui.theme.appTextSecondary()) },
                    confirmButton = {
                        TextButton(onClick = { apiSuspendedDialog = false }) { Text("知道了", color = Color(0xFF2563EB), fontWeight = FontWeight.SemiBold) }
                    }
                )
            }

            // ==================== 消息修改弹窗（长按 → 修改：编辑思考部分与实际输出，保存后写回本地） ====================
            if (showEditDialog) {
                AlertDialog(
                    onDismissRequest = { showEditDialog = false },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("修改消息", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            // 思考内容只对 AI 的回复有意义：编辑「自己的消息」时隐藏思考输入框（用户消息没有思考过程，也不应由用户编辑）
                            val editingOwnMessage = messages.firstOrNull { it.id == editTargetMsgId }?.isMine == true
                            if (!editingOwnMessage) {
                                Text("思考过程", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = FontWeight.Medium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.OutlinedTextField(
                                        value = editReasoningText,
                                        onValueChange = { editReasoningText = it },
                                        modifier = Modifier.weight(1f).heightIn(min = 80.dp, max = 140.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                        placeholder = { Text("AI 的思考过程（可留空）", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()) }
                                    )
                                    TextButton(onClick = { editReasoningText = "" }) { Text("清空", fontSize = 13.sp, color = Color(0xFFDC2626)) }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            Text("实际输出", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = editOutputText,
                                    onValueChange = { editOutputText = it },
                                    modifier = Modifier.weight(1f).heightIn(min = 100.dp, max = 180.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                    placeholder = { Text("AI 回复的内容", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()) }
                                )
                                TextButton(onClick = { editOutputText = "" }) { Text("清空", fontSize = 13.sp, color = Color(0xFFDC2626)) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val targetId = editTargetMsgId
                            val idx = messages.indexOfFirst { it.id == targetId }
                            if (idx >= 0 && messages[idx] is ChatMsg) {
                                val updated = (messages[idx] as ChatMsg).copy(
                                    reasoningText = editReasoningText,
                                    text = editOutputText
                                )
                                messages[idx] = updated
                                // 写回本地存储（AI 对话 / 自己对话 / 系统通知都走对应持久化）
                                try {
                                    if (isLocalChat(friendId)) {
                                        val key = localChatKey(friendId)
                                        saveLocalChatMessages(ctx, currentUserId, key, messages.filter { !it.isSystemNotice })
                                    } else if (isNotificationChat(friendId)) {
                                        saveNotificationMessages(ctx, currentUserId, messages.filterIsInstance<ChatMsg>())
                                    }
                                } catch (_: Exception) {}
                                ChatViewModel.updateConversationPreview(friendId, editOutputText.take(60), updated.createdAt)
                                showEditDialog = false
                                // 「它不是AI」的作用域：仅当在 AI 对话中编辑「自己」且是「自己的最后一条消息」时，
                                // 才提供重置&重发。注意不能拿 idx == lastIndex 判断：自己最后一条消息后通常紧跟 AI 回复
                                //（甚至正在流的占位），此时 idx 一定 != lastIndex，会导致弹窗永不触发。
                                // 正确判定 = 该消息是「最后一条 isMine 的用户消息」（其后只有 AI 回复/流式占位/系统通知）。
                                // 编辑 AI 的回复、或后面还有更新的自己消息（编辑更早的自己的消息），走普通修改。
                                val isOwnLastAiEdit =
                                    friendId == AI_CHAT_ID && updated.isMine &&
                                    idx == messages.indexOfLast { it.isMine && !it.isSystemNotice }
                                if (isOwnLastAiEdit) {
                                    resendEditMsgId = targetId
                                    resendEditText = editOutputText
                                    resendNewChatConfirm = true
                                } else {
                                    Toast.makeText(ctx, "已修改", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                showEditDialog = false
                            }
                        }) { Text("保存", color = Color(0xFF2563EB), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                    }
                )
            }

            // ==================== 「编辑自己最后一条消息 → 重置并重新发送」确认弹窗（仅 AI 对话） ====================
            // 确定 → 以安全顺序：先中断在途 AI 回复并隔离代次，删除最后一组（该消息及其配对 AI 回复），再复用确认发言链路重发。
            // 取消 → 保留刚才的编辑（不重发）。
            if (resendNewChatConfirm) {
                AlertDialog(
                    onDismissRequest = { resendNewChatConfirm = false },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("重新发送", fontWeight = FontWeight.Bold) },
                    text = { Text("确定重置这条消息并重新发送吗？\n\n重新发送后，将以新的修改内容为基础，重新生成 AI 的回复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            val ridText = resendEditText
                            resendNewChatConfirm = false
                            val targetId = resendEditMsgId
                            scope.launch {
                                // —— ① 先中断在途 AI 回复并等待其真正退出（复用「停止 / 开启新对话」同款机制），再自增代次隔离残余写回 ——
                                aiStopRequested = true
                                val job = activeAiJob
                                activeAiJob?.cancel()
                                activeAiJob = null
                                aiWriting.value = false
                                aiWritingConvId = -1L
                                agentTaskInFlight.value = false
                                agentTaskInFlightConvId = -1L
                                aiReplyState.value = null
                                kotlinx.coroutines.withTimeoutOrNull(800L) { job?.join() }
                                aiConvTag++
                                // —— ② 删除最后一条消息及其配对回复（自该消息索引起到末尾整段移除，避免残留半条回复） ——
                                val ridx = targetId.takeIf { it != 0L }
                                    ?.let { id -> messages.indexOfFirst { m -> m.id == id } } ?: -1
                                if (ridx >= 0) {
                                    var k = messages.size - 1
                                    while (k >= ridx) { messages.removeAt(k); k-- }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    try { saveLocalChatMessages(ctx, currentUserId, AiConversationSession.storageKey(), messages.filter { !it.isSystemNotice }) } catch (_: Exception) {}
                                }
                                ChatViewModel.updateConversationPreview(AI_CHAT_ID, "", 0)
                                // —— ③ 复用「确认发言」发送链路：把编辑后的文本写入输入框并触发流式重发（新增用户消息 + 重新生成 AI 回复）——
                                if (ridText.isNotBlank()) {
                                    inputText = ridText
                                    editTargetMsgId = 0L
                                    onConfirmReply()
                                }
                            }
                        }) { Text("确定", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { resendNewChatConfirm = false; Toast.makeText(ctx, "已保留修改", Toast.LENGTH_SHORT).show() }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                    }
                )
            }

            // 订单详情弹窗：从通知中心「查看订单」进入，展示与开发者管理一致的订单信息
            viewOrderId?.let { oid ->
                OrderDetailView(orderId = oid, onDismiss = { viewOrderId = null })
            }

            }
            }
            ChatMessageListArea()


            // 429 限流提示条（固定在输入框正上方）：左文案带秒数倒计时,右侧蓝色「再次尝试」点击立即重试
            val rateLimitText by AiChatManager.RateLimitBus.text.collectAsState()
            rateLimitText?.let { rlText ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFFFF7ED)).padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(rlText, fontSize = 12.sp, color = Color(0xFFD97706), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(
                        "再次尝试",
                        fontSize = 12.sp, color = Color(0xFF2F6FED), fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp).clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { AiChatManager.RateLimitBus.retryNow() }
                    )
                }
            }

            // 底部输入栏（在 Column 内部、LazyColumn 下方；严格位于消息区以下）
            // 通知系统：用户不可发消息，隐藏输入栏；非群成员（待审核）同样隐藏
            // 被移出 / 已退出的群：语音、输入框、表情、加号这一整块功能区全部移除，
            // 原位只留一条状态提示。历史照常翻、三横杠仍可进群信息（见 GroupSettingsPanel 的只读态）。
            if (isExitedGroup && !isNotificationChat(friendId)) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            // 文案跟着退出记录走：解散 =「群主已解散群聊」、自己被移出 =「XX 将你踢出群聊」、
                            // 自己主动退 =「你已退出群聊」。这里原先写死「你已被踢出群聊」，
                            // 群主解散的群在底部也会被说成"你被踢了"，与上方系统提示自相矛盾。
                            groupExit?.text ?: "你已被踢出群聊",
                            fontSize = 13.sp,
                            color = com.aurora.chat.ui.theme.appTextSecondary()
                        )
                    }
                }
            } else if (!isNotificationChat(friendId) && !isNotGroupMember) {
            ChatInputBarArea(
                currentUserId = currentUserId,
                currentUserName = currentUserName,
                friendId = friendId,
                friendName = friendName,
                contactSource = contactSource,
                onContactSourceConsumed = onContactSourceConsumed,
                onTokenExhausted = onTokenExhausted,
                onAppAction = onAppAction,
                ctx = ctx,
                scope = scope,
                listState = listState,
                draftKey = draftKey,
                quoteKey = quoteKey,
                messages = messages,
                pendingUploadPlaceholders = pendingUploadPlaceholders,
                pendingFailedMessages = pendingFailedMessages,
                aiConvs = aiConvs,
                uploadProgressMap = uploadProgressMap,
                uploadStageMap = uploadStageMap,
                uploadRatioMap = uploadRatioMap,
                uploadFileNameMap = uploadFileNameMap,
                recentlySentMessages = recentlySentMessages,
                replyToMsg = replyToMsg,
                selectedMsgIds = selectedMsgIds,
                aiUsageState = aiUsageState,
                aiImagePicker = aiImagePicker,
                aiFilePicker = aiFilePicker,
                addLocalNotice = { convId, noticeText, noticeType -> addLocalNotice(convId, noticeText, noticeType) },
                closeStaleAiWaiting = { keepId -> closeStaleAiWaiting(keepId) },
                exitMultiSelect = { exitMultiSelect() },
                insertAiFileCard = { absPath, forceFileCard, afterMsgId -> insertAiFileCard(absPath, forceFileCard, afterMsgId) },
                insertAiMediaCard = { mediaType, mediaUrl, afterMsgId -> insertAiMediaCard(mediaType, mediaUrl, afterMsgId) },
                markSendFailed = { ref -> markSendFailed(ref) },
                programmaticScroll = { index -> programmaticScroll(index, 0) },
                scheduleAiDebouncedSave = { scheduleAiDebouncedSave() },
                updatePlanStep = { stepIndex, status -> updatePlanStep(stepIndex, status) },
                openVideoPlayer = { url -> openVideoPlayer(url, false) },
                toolApprovalHook = toolApprovalHook,
                onConfirmReply = onConfirmReply,
                inputTextState = inputTextState,
                autoFollowState = autoFollowState,
                pendingScrollToBottomState = pendingScrollToBottomState,
                unreadNewCountState = unreadNewCountState,
                anchorItemIndexState = anchorItemIndexState,
                apiSuspendedDialogState = apiSuspendedDialogState,
                groupMemberNamesState = groupMemberNamesState,
                groupRolesState = groupRolesState,
                quoteFocusTickState = quoteFocusTickState,
                inputBarTopYState = inputBarTopYState,
                showTransferDialogState = showTransferDialogState,
                showRedPacketDialogState = showRedPacketDialogState,
                showCameraState = showCameraState,
                showPrivacyPanelState = showPrivacyPanelState,
                mutedUntilState = mutedUntilState,
                showLocationConfirmState = showLocationConfirmState,
                showTimePointDialogState = showTimePointDialogState,
                showConfirmSpeechDialogState = showConfirmSpeechDialogState,
                showAiImageNoticeState = showAiImageNoticeState,
                confirmSpeechEnabledState = confirmSpeechEnabledState,
                currentTimePointState = currentTimePointState,
                confirmSpeechPendingState = confirmSpeechPendingState,
                pendingAiImagesState = pendingAiImagesState,
                pendingAiFilesState = pendingAiFilesState,
                wsPickModeState = wsPickModeState,
                wsInjectForSendState = wsInjectForSendState,
                editTargetMsgIdState = editTargetMsgIdState,
                showBroadcastSendDialogState = showBroadcastSendDialogState,
                aiFileSendingTipState = aiFileSendingTipState,
                agentAccessModeState = agentAccessModeState,
                agentModeState = agentModeState,
                sendUserTurnState = sendUserTurnState,
                resumeTaskPendingState = resumeTaskPendingState,
                showThinkingLevelMenuState = showThinkingLevelMenuState,
                thinkingLevelState = thinkingLevelState,
                execModesState = execModesState,
                showWorkspaceFilesState = showWorkspaceFilesState,
                modeExplainDialogState = modeExplainDialogState,
                multiSelectModeState = multiSelectModeState,
                multiDeleteConfirmState = multiDeleteConfirmState,
                chatShareCardTextState = chatShareCardTextState,
                destroyedFlashIdsState = destroyedFlashIdsState,
                readSheetMsgIdState = readSheetMsgIdState,
                payDisabledState = payDisabledState,
                payBlockReasonState = payBlockReasonState,
                showPlusDialogState = showPlusDialogState,
                showStickerPanelState = showStickerPanelState,
                showChatShareDialogState = showChatShareDialogState,
                activeAiConvIdState = activeAiConvIdState,
            )
            }

        }

        ChatConversationOverlaysInChatBox(
            currentUserId = currentUserId,
            friendId = friendId,
            friendName = friendName,
            onBack = onBack,
            onOpenJoinRequestFullScreen = onOpenJoinRequestFullScreen,
            onOpenNewChat = onOpenNewChat,
            ctx = ctx,
            scope = scope,
            listState = listState,
            messages = messages,
            adapter = adapter,
            isExitedGroup = isExitedGroup,
            chatSearchState = chatSearchState,
            imagePreviewUrlsState = imagePreviewUrlsState,
            shouldShowScrollDown = shouldShowScrollDown,
            selectedMsgIds = selectedMsgIds,
            pendingToolApprovalDeferred = pendingToolApprovalDeferred,
            syncMessages = { syncMessages() },
            aiChangesFocusDetailState = aiChangesFocusDetailState,
            aiChangesFocusPathState = aiChangesFocusPathState,
            aiChangesJsonDataState = aiChangesJsonDataState,
            aiChangesMsgIdState = aiChangesMsgIdState,
            aiChangesOpenState = aiChangesOpenState,
            aiToolCallsDataState = aiToolCallsDataState,
            aiToolCallsOpenState = aiToolCallsOpenState,
            approvalMinimizedState = approvalMinimizedState,
            approvalTrustUntilState = approvalTrustUntilState,
            autoFollowState = autoFollowState,
            friendStatusRefreshState = friendStatusRefreshState,
            isNotGroupMemberState = isNotGroupMemberState,
            jumpToMessageIdState = jumpToMessageIdState,
            multiSelectModeState = multiSelectModeState,
            pendingAiFilesState = pendingAiFilesState,
            pendingToolApprovalState = pendingToolApprovalState,
            profileUserIdState = profileUserIdState,
            profileUserNameState = profileUserNameState,
            programmaticScrollingState = programmaticScrollingState,
            searchReturnPendingState = searchReturnPendingState,
            selectAnchorMsgIdState = selectAnchorMsgIdState,
            selectToHereDirState = selectToHereDirState,
            showAddFriendGreetingState = showAddFriendGreetingState,
            showAiUsageState = showAiUsageState,
            showBackToBottomState = showBackToBottomState,
            showBackupScreenState = showBackupScreenState,
            showChatHistorySearchState = showChatHistorySearchState,
            showFreeApiSelectState = showFreeApiSelectState,
            showFriendSettingsState = showFriendSettingsState,
            showGroupSettingsState = showGroupSettingsState,
            showPaidApiSelectState = showPaidApiSelectState,
            showProfileState = showProfileState,
            showUsageHistoryState = showUsageHistoryState,
            showWorkspaceFilesState = showWorkspaceFilesState,
            unreadNewCountState = unreadNewCountState,
            upScrollPxState = upScrollPxState,
            workspaceLocateNameState = workspaceLocateNameState,
            workspaceLocateOpenState = workspaceLocateOpenState,
            wsInjectForSendState = wsInjectForSendState,
            wsPickModeState = wsPickModeState,
            onOpenImagePreview = { urls, index, rect ->
                // 与会话内点图完全同一套预览：urls 决定可左右翻页的范围，
                // rect 是九宫格缩略图在屏幕上的位置 → 打开从这里放大、关闭缩回这里。
                imagePreviewUrls = urls
                imagePreviewIndex = index.coerceAtLeast(0)
                heroInitialIndex = imagePreviewIndex
                heroSourceRect = rect
                heroEnterDone = false
                previewReady = false
                previewSession++
            },
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatConversationScreen(
    currentUserId: Long = 0,
    currentUserName: String = "",
    friendId: Long = 0,
    friendName: String = "",
    onBack: () -> Unit,
    onBroadcastSent: () -> Unit = {},
    onOpenJoinRequestFullScreen: (org.json.JSONArray, Long) -> Unit = { _, _ -> },
    onOpenPostDetail: (Long) -> Unit = {},
    onOpenGroupDetail: (Long) -> Unit = {},
    onOpenNewChat: (Long, String, String) -> Unit = { _, _, _ -> },
    onRechargeToken: () -> Unit = {},
    onTokenExhausted: () -> Unit = {},
    onAppAction: (suspend (AiChatManager.AppAction) -> AiChatManager.AppActionResult)? = null,
    /** 陌生人首条消息来源（search / group:<群名>），非空时首条发送带上，发送成功后由调用方清除 */
    contactSource: String = "",
    onContactSourceConsumed: () -> Unit = {},
    /** 返回到本会话时如需连带恢复资料页，传入 (userId, userName)；打开后由 onRestoreProfileConsumed 通知清除 */
    restoreProfile: Pair<Long, String>? = null,
    onRestoreProfileConsumed: () -> Unit = {},
    /** 点击「短视频分享卡片」→ 打开短视频模块并定位到该视频（参数为视频 id） */
    onOpenShortVideo: (Long) -> Unit = {}
) {
    val inputTextState = rememberSaveable { mutableStateOf("") }
    var inputText by inputTextState
    val ctx = LocalContext.current

    // ── 群公告增强 ──
    // 已确认收到的公告文本集合（后端以公告文本作为确认标识）：按用户+群持久化，重进会话仍能保持「已确认」
    val confirmedAnnouncementTexts = remember(friendId, currentUserId) {
        mutableStateSetOf<String>().apply {
            try { addAll(DataCache.loadConfirmedAnnouncementTexts(ctx, currentUserId, -friendId - 1000)) } catch (_: Exception) {}
        }
    }
    // 点击群公告卡片 → 全屏公告详情页（raw=消息内容，time=卡片消息发送时间，用于详情页底部日期）
    val annDetailRawState = remember { mutableStateOf<String?>(null) }
    var annDetailRaw by annDetailRawState
    val annDetailTimeState = remember { mutableStateOf(0L) }
    var annDetailTime by annDetailTimeState
    // 点击公告图片（卡片网格/弹窗缩略图）→ 全屏查看大图
    val annImageViewerUrlState = remember { mutableStateOf<String?>(null) }
    var annImageViewerUrl by annImageViewerUrlState
    // 群主/管理员视角：卡片消息已读人数（msg.id → read_count）
    val annReadCountsState = remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var annReadCounts by annReadCountsState
    // 群主/管理员视角：需确认公告的已收到人数（msg.id → confirm_count）
    val annConfirmCountsState = remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var annConfirmCounts by annConfirmCountsState
    // 当前打开「已收到」名单抽屉的公告正文（null = 关闭）
    val annConfirmSheetTextState = remember { mutableStateOf<String?>(null) }
    var annConfirmSheetText by annConfirmSheetTextState

    // 标记当前打开的会话（用于"正在查看的会话不计未读"），进入即清零未读
    LaunchedEffect(friendId) {
        com.aurora.chat.ui.viewmodel.ChatViewModel.openConversationId = friendId
        com.aurora.chat.ui.viewmodel.ChatViewModel.markRead(ctx, friendId)
    }
    // 群公告：进入群聊时，带「弹窗展示」标记且本机未看过的公告会自动弹出（见下方 popupAnnouncement）
    DisposableEffect(friendId) {
        onDispose {
            com.aurora.chat.ui.viewmodel.ChatViewModel.openConversationId = 0L
            com.aurora.chat.ui.viewmodel.ChatViewModel.markRead(ctx, friendId)
        }
    }
    val draftKey = "draft_${currentUserId}_${friendId}"
    // 引用态按会话隔离持久化：AI 对话带上当前会话 key，避免多个 AI 会话串引用
    val quoteKey = "quote_${currentUserId}_${if (friendId == AI_CHAT_ID) AiConversationSession.storageKey() else friendId.toString()}"
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 官方 API 实时用量：与「使用」面板共享，流式回复过程中实时刷新，避免只能等对话结束后才更新。
    // 值为 Pair(今日已用, 剩余)；剩余即余额，扣到 0 才截止。
    val aiUsageState = remember(currentUserId) {
        mutableStateOf(AiChatManager.getOfficialUsage(ctx, currentUserId))
    }
    // 是否停留在列表底部（按像素偏移判断，不用 index）：
    // reverseLayout 下 firstVisibleItemIndex = 0 且滚动偏移 ≤ 容差像素才算真正贴底。
    // 不用 last.index——纯文字流式时 AI 正在吐的那条气泡永远在底部(last.index=0)，index 容差会完全失效。
    val BOTTOM_OFFSET_TOLERANCE_PX = 32
    // 可见窗口是否已与「真正的最新消息」脱钩（上滑超过窗口上限后，底部被丢弃过）。
    // 一旦脱钩，index 0 只代表「窗口里的最新」，不再是最新消息本身，
    // 贴底判定必须额外排除，否则自动跟随 / 未读清零 / 回底按钮会一起跑偏。
    // 由 adapter.tailDetached 单向镜像（adapter 里的字段不是 Compose state，无法直接 derive）。
    val windowDetached = remember(friendId) { mutableStateOf(false) }
    // 必须带 friendId key：这个 derivedStateOf 现在捕获了 windowDetached（本身按 friendId 重建），
    // 若这里仍是无 key 的 remember，切会话后会一直读上一个会话的那个 state 对象，贴底判定永久失真。
    val atBottomState = remember(friendId) {
        derivedStateOf {
            if (windowDetached.value) return@derivedStateOf false
            listState.layoutInfo.visibleItemsInfo.isEmpty() ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= BOTTOM_OFFSET_TOLERANCE_PX)
        }
    }
    // 自动跟随开关：基于列表真实滚动位置推导（见下方 LaunchedEffect(listState)），不再用手势事件锁存。
    // 贴底时 = true（AI 流式输出自动跟随最新）；一旦用户上滑离开底部立即变 false（不抢占、不拽回）；
    // 用户滑回底部自动恢复 true。彻底消除「手势锁存误判」导致的两种失败模式。
    val autoFollowState = remember { mutableStateOf(true) }
    var autoFollow by autoFollowState
    // 「自己发的消息必须回到底部」的强制跟随标记（微信 / QQ / Telegram 一致的做法：
    // 发送方永远能看到自己刚发出的那条，不论此前是否正在上滑翻历史）。
    // 必须通过它跨一帧处理，原因见下方 LaunchedEffect(messages.size)。
    val pendingScrollToBottomState = remember { mutableStateOf(false) }
    var pendingScrollToBottom by pendingScrollToBottomState
    // 程序化滚动标记：自身 scrollToItem 进行期间为 true，snapshotFlow 推导跳过，避免被自身滚动误翻转 autoFollow。
    val programmaticScrollingState = remember { mutableStateOf(false) }
    var programmaticScrolling by programmaticScrollingState
    // 未读新消息计数：用户离开底部（autoFollow=false，正在看历史）期间收到对方新消息时累加，
    // 回底按钮上以数字替换三角展示；点击回底或手动滑回底部后清零。
    val unreadNewCountState = remember { mutableStateOf(0) }
    var unreadNewCount by unreadNewCountState
    // 统一的程序化滚动封装：自动加 programmaticScrolling 守卫，供插入/流式增量/回底按钮复用。
    fun programmaticScroll(index: Int = 0, offset: Int = 0) {
        aiGlobalScope.launch {
            try {
                programmaticScrolling = true
                listState.scrollToItem(index, offset)
            } finally {
                programmaticScrolling = false
            }
        }
    }
    // ═══ 消息「呈现」的几何基准（引用胶囊 / 聊天记录搜索跳转共用）═══
    // 消息行在窗口里的真实矩形。故意用普通 HashMap 而不是 Compose state：
    // 滚动时这个值每帧都在变，用 state 会让整个列表跟着重组。
    val msgBoundsInWindow = remember { HashMap<Long, Rect>() }
    // 列表视口的窗口矩形：上沿是贴顶基准，下沿用来判定「这条到底看不看得全」
    val listViewportInWindowState = remember { mutableStateOf(Rect.Zero) }
    var listViewportInWindow by listViewportInWindowState
    // contentPadding.top = 4dp：贴顶后目标行应停在离列表上沿这个距离的位置
    val listPadTopPx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
    // 判可见只留 2dp 容差：**完整可见就一律不动**，不做「再往里收一点」的额外补偿。
    // 收边只发生在「确实被裁」的情况，且收 10dp 让目标离开边缘，看着不憋。
    val visibleEpsPx = with(androidx.compose.ui.platform.LocalDensity.current) { 2.dp.toPx() }
    val revealBreathPx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }

    /**
     * 把某条消息贴到列表顶部 —— **只**服务「完全不在视口」的远距离跳转。
     *
     * reverseLayout 下 scrollToItem 的默认落点是「贴视口底部」；而直接给 scrollOffset
     * 又会因为反向布局的语义反转把目标直接推出屏幕。这里全程只信实测坐标：
     * 目标行此刻距列表上沿多少像素就滚多少；如果滚完误差没变小说明方向反了，反号补一次。
     * 最多三轮，必然收敛到顶部；目标被列表边界钳住时自然停下，不会把内容推飞。
     *
     * 注意它**不再**参与「目标本来就在屏幕上」的场景 —— 那正是旧实现把同屏消息
     * 强行搬到顶上去、造成整屏瞬移闪烁的原因。是否调用它由 [revealMessageRow] 决定。
     */
    suspend fun scrollMessageRowToTop(displayIdx: Int, msgId: Long) {
        listState.scrollToItem(displayIdx)
        androidx.compose.runtime.withFrameNanos { }
        val vp = listViewportInWindow
        fun rowTopErr(): Float? {
            val r = msgBoundsInWindow[msgId] ?: return null
            return r.top - (vp.top + listPadTopPx)
        }
        val firstErr = rowTopErr() ?: return
        if (kotlin.math.abs(firstErr) <= 4f) return
        // 先用一小步（24px）探测 scrollBy 在这个列表里的真实方向。
        // 用小步的原因：方向反了也只挪出去 24px，目标仍在视口内、坐标还能读到；
        // 直接给整段距离的话，一旦方向反会把目标整条推出屏幕，之后就再也测不到了。
        listState.scrollBy(if (firstErr > 0f) 24f else -24f)
        androidx.compose.runtime.withFrameNanos { }
        val probeErr = rowTopErr() ?: return
        val dir = if (kotlin.math.abs(probeErr) < kotlin.math.abs(firstErr)) 1f else -1f
        var remaining = probeErr
        repeat(3) {
            if (kotlin.math.abs(remaining) <= 4f) return
            val before = remaining
            listState.scrollBy(dir * remaining)
            androidx.compose.runtime.withFrameNanos { }
            remaining = rowTopErr() ?: return
            // 误差没缩小 = 已经顶到列表边界（滚不动了），停手，别反复推
            if (kotlin.math.abs(remaining) >= kotlin.math.abs(before) - 2f) return
        }
    }

    /**
     * 把某条消息「呈现」给用户 —— 引用胶囊点击、聊天记录跳转的唯一入口。
     *
     * 契约（与旧实现的本质区别）：
     *  1. **已完整可见** → 一次滚动都不做。布局零变化，看到的还是原来那一屏，只叠一层高亮。
     *     旧实现无条件「贴到顶部」，同屏时也要把整屏内容挪一遍——那次瞬移本身就是闪烁。
     *  2. **部分被裁** → 只补被裁掉的那一段（外加呼吸位），滚最小必要距离。
     *  3. **完全不在视口** → 才贴顶，长距离跳转/换窗口后靠这条兜底。
     *
     * 判据只有一处：真实窗口几何（行矩形 vs 视口矩形）。不猜 reverseLayout 的滚动符号，
     * 也不做「滚一次→看误差→再修」的收敛循环：每多一轮就是一次可见位移。
     */
    suspend fun revealMessageRow(displayIdx: Int, msgId: Long) {
        val vp = listViewportInWindow
        // 「已组合」是权威可见信号：LazyColumn 只会组合视口内的 item。
        // 行矩形可能残留着这一行被回收前的旧值，所以它只用来算距离，不用来判可见。
        val composed = listState.layoutInfo.visibleItemsInfo.any { it.index == displayIdx }
        if (!composed) {
            // 完全不在视口（或还没被组合）：才允许贴顶
            scrollMessageRowToTop(displayIdx, msgId)
            return
        }
        // 已在视口却没量到（同一帧内的极端情况）：什么都不做，交给高亮
        val row = msgBoundsInWindow[msgId] ?: return
        if (vp.height <= 0f) return
        val limitTop = vp.top + revealBreathPx
        val limitBottom = vp.bottom - revealBreathPx
        val dy = when {
            // 比整个视口还高的消息：贴哪都看不全，别动它，就地闪
            row.height > vp.height -> 0f
            // 完整可见：唯一「什么都不做」的分支，闪烁不再伴随任何位移
            row.top >= vp.top + visibleEpsPx && row.bottom <= vp.bottom - visibleEpsPx -> 0f
            // 上方被裁 → 内容整体下移，把行顶露出来
            row.top < vp.top + visibleEpsPx -> limitTop - row.top
            // 下方被裁 → 内容整体上移，把行底露出来
            else -> limitBottom - row.bottom
        }
        if (dy == 0f) return
        // reverseLayout 下 scrollBy(正) = 朝更旧方向 = 内容整体下移，与 dy 同号。
        // 只做一次位移 + 一次自校验（防个别版本方向语义相反），绝不进入循环。
        listState.scrollBy(dy)
        androidx.compose.runtime.withFrameNanos { }
        val after = msgBoundsInWindow[msgId]
        val moved = if (after != null) after.top - row.top else 0f
        // 只有「移动方向反了」才纠正一次。「被列表边界钳住、只挪了一部分」是正常情况，
        // 不能当成方向错误处理，否则会反着推一大段、反而制造出一次可见跳动。
        if (kotlin.math.abs(moved) > 1f && moved * dy < 0f) {
            listState.scrollBy(-2f * dy)
            androidx.compose.runtime.withFrameNanos { }
        }
    }
    // ═══ 钉住 anchor item:解决「离开底部后 AI 继续吐把上面内容顶上去」的问题 ═══
    // reverseLayout 下 index 0(AI 正在吐的气泡)永远在屏幕最底部,它不断长高时从底部往上顶,
    // 会把视口里的其它气泡一起往上推——哪怕不调用 scrollToItem 也一样。
    // 解法:用户离开底部(!autoFollow)时,由 LaunchedEffect 记录 firstVisibleItemIndex/offset 作为 anchor;
    // 之后每次流式增量,只要 !autoFollow 就把这条钉回原位。anchor 条以下(含 index 0)
    // 继续长高没问题,anchor 条以上的内容不会被推。
    val anchorItemIndexState = remember { mutableStateOf(-1) }
    var anchorItemIndex by anchorItemIndexState
    val anchorItemOffsetState = remember { mutableStateOf(0) }
    var anchorItemOffset by anchorItemOffsetState
    // 展开/收起思考过程时的临时护栏：压制下方 snapshotFlow 在重排期间改写锚点，
    // 否则它会把「被展开顶移后的位置」当成新锚点再钉回去，反而造成视角漂移/飞顶/飞底。
    val anchorUpdateSuppressedState = remember { mutableStateOf(false) }
    var anchorUpdateSuppressed by anchorUpdateSuppressedState
    // 官方 API 被管理员暂停时的明确提示对话框：拦截时弹窗告知，避免用户误以为发送 bug
    val apiSuspendedDialogState = remember { mutableStateOf(false) }
    var apiSuspendedDialog by apiSuspendedDialogState
    val groupMemberNamesState = remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupMemberNames by groupMemberNamesState
    val groupMemberIdMapState = remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var groupMemberIdMap by groupMemberIdMapState
    // 群成员角色（userId → owner/admin/member）：气泡昵称前的「群主」「群管理」徽章用它
    val groupRolesState = remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var groupRoles by groupRolesState
    // @ 成员选择抽屉用：结构化成员列表
    val groupAtMembersState = remember { mutableStateOf<List<AtMember>>(emptyList()) }
    var groupAtMembers by groupAtMembersState
    val isLoadingMessagesState = remember { mutableStateOf(true) }
    var isLoadingMessages by isLoadingMessagesState
    // 首屏加载失败且本地一条都没有：用于在空白区域显示一行不打扰的占位（而不是弹 Toast）。
    // 只在「真的没有任何内容可显示」时为 true，正常会话永远不会出现。
    val initialLoadFailedState = remember { mutableStateOf(false) }
    var initialLoadFailed by initialLoadFailedState
    // 点击重试计数：作为初始加载 LaunchedEffect 的 key，自增即整段重跑（与首次进入同一条路径）
    val loadRetryTickState = remember { mutableStateOf(0) }
    var loadRetryTick by loadRetryTickState
    // 当前会话是不是「我被移出 / 已退出」的群：只读历史，输入区整块收起。
    // 必须**同步**取（GroupExitStore 带进程内缓存）：晚一拍的话，下面的群成员校验会先跑一轮，
    // 把 isNotGroupMember 置真并清空消息，历史就再也回不来了。
    val groupExitTickState = remember { mutableStateOf(0) }
    var groupExitTick by groupExitTickState
    val groupExit = remember(friendId, groupExitTick) {
        runCatching { com.aurora.chat.data.local.GroupExitStore.getByConv(ctx, friendId) }.getOrNull()
    }
    val isExitedGroup = groupExit != null
    val scrollReadyState = remember { mutableStateOf(false) }
    var scrollReady by scrollReadyState
    // 撤回计数：markRevoked 只原地改同一条 ref、messages 列表结构不变，Compose 不会重组；
    // bump 它强制可见项重组，使对话内/对方撤回的占位即时显示（无需离开重进）
    val recallTickState = remember { mutableStateOf(0) }
    var recallTick by recallTickState
    // 逐消息上传进度：messageId -> progress (0.0~1.0)
    val uploadProgressMap = remember { mutableStateMapOf<Long, Float>() }
    // 视频上传阶段：messageId -> "compressing"(压缩中) / "uploading"(上传中)
    val uploadStageMap = remember { mutableStateMapOf<Long, String>() }
    // 视频宽高比：messageId -> width/height（用于上传占位按比例显示）
    val uploadRatioMap = remember { mutableStateMapOf<Long, Float>() }
    // 上传中的文件名：localId -> 原始文件名（用于文件上传占位卡片显示）
    val uploadFileNameMap = remember { mutableStateMapOf<Long, String>() }
    val replyToMsg = remember { mutableStateOf<IMessageRef?>(null) } // 引用功能：当前正在回复的消息
    // 离开对话 / 进程重建后把引用条和输入草稿一起恢复，不用重新引用一次
    LaunchedEffect(quoteKey) {
        replyToMsg.value = loadQuoteDraft(ctx, quoteKey)
    }
    // 引用聚焦信号：每次点「引用」自增一次，InputBar 收到后自动聚焦输入框并弹起键盘
    val quoteFocusTickState = remember { mutableStateOf(0) }
    var quoteFocusTick by quoteFocusTickState

    // Plus按钮弹窗状态
    val showPlusDialogState = remember { mutableStateOf(false) }
    var showPlusDialog by showPlusDialogState
    // 表情面板状态（提升到屏幕层：点消息区任意位置可收起，且不弹键盘）
    val showStickerPanelState = remember { mutableStateOf(false) }
    var showStickerPanel by showStickerPanelState
    // 输入栏顶部在根坐标系的 Y：手势轻点落点高于它 → 判定为「面板外」
    val inputBarTopYState = remember { mutableStateOf(Float.MAX_VALUE) }
    var inputBarTopY by inputBarTopYState
    val showStickerPanelSt = rememberUpdatedState(showStickerPanel)
    val showPlusDialogSt = rememberUpdatedState(showPlusDialog)
    val inputBarTopYSt = rememberUpdatedState(inputBarTopY)
    // 转账弹窗状态
    val showTransferDialogState = remember { mutableStateOf(false) }
    var showTransferDialog by showTransferDialogState
    // 红包：发送弹窗 / 详情全屏 / 实时状态
    val showRedPacketDialogState = remember { mutableStateOf(false) }
    var showRedPacketDialog by showRedPacketDialogState
    val redPacketDetailIdState = remember { mutableStateOf(0L) }
    var redPacketDetailId by redPacketDetailIdState
    val redpacketStatus = remember { mutableStateMapOf<Long, RedPacketStatus>() }
    // 相机拍摄状态
    val showCameraState = remember { mutableStateOf(false) }
    var showCamera by showCameraState
    // 位置请求触发状态
    val showLocationRequestState = remember { mutableStateOf(false) }
    var showLocationRequest by showLocationRequestState
    // 隐私控制面板
    val showPrivacyPanelState = remember { mutableStateOf(false) }
    var showPrivacyPanel by showPrivacyPanelState
    // 禁言状态：禁言截止时间戳（0=未禁言）
    val mutedUntilState = remember { mutableStateOf(0L) }
    var mutedUntil by mutedUntilState
    val showLocationConfirmState = remember { mutableStateOf(false) }
    var showLocationConfirm by showLocationConfirmState

    // ── AI 对话专属：时间点 / 确认发言 ──（提升到顶层，让 InputBar 等兄弟块可见）
    val showTimePointDialogState = remember { mutableStateOf(false) }
    var showTimePointDialog by showTimePointDialogState
    val showConfirmSpeechDialogState = remember { mutableStateOf(false) }
    var showConfirmSpeechDialog by showConfirmSpeechDialogState
    // 「图片」首次使用提示：告知所选模型可能不支持图片识别（如 DeepSeek 为纯文本模型）
    val showAiImageNoticeState = remember { mutableStateOf(false) }
    var showAiImageNotice by showAiImageNoticeState
    val confirmSpeechEnabledState = remember(currentUserId) { mutableStateOf(AiChatManager.getConfirmSpeech(ctx, currentUserId)) }
    var confirmSpeechEnabled by confirmSpeechEnabledState
    val currentTimePointState = remember(currentUserId) { mutableStateOf(AiChatManager.getTimePoint(ctx, currentUserId)) }
    var currentTimePoint by currentTimePointState
    // 是否有待确认的消息（用户发送后 AI 未回复的消息）
    val confirmSpeechPendingState = remember { mutableStateOf(false) }
    var confirmSpeechPending by confirmSpeechPendingState
    // AI 对话「图片」附件：选择后暂存在输入框上方，点击发送时才随消息一起发出去
    val pendingAiImagesState = remember { mutableStateOf<List<PendingAiImage>>(emptyList()) }
    var pendingAiImages by pendingAiImagesState
    val aiImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = 10 - pendingAiImages.size
        if (room <= 0) {
            android.widget.Toast.makeText(ctx, "最多同时发送 10 张图片", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val added = mutableListOf<PendingAiImage>()
        for (uri in uris.take(room)) {
            val bytes = readAiImageBytes(ctx, uri) ?: continue
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val name = queryDisplayName(ctx, uri) ?: uri.lastPathSegment ?: "图片"
            added.add(PendingAiImage(uri = uri, bytes = bytes, name = name, mime = mime))
        }
        if (added.isNotEmpty()) {
            pendingAiImages = pendingAiImages + added
            showPlusDialog = false
        }
    }
    // AI 对话「文件」附件：选择后暂存在输入框上方，点击发送时才随消息一起发出去
    val pendingAiFilesState = remember { mutableStateOf<List<PendingAiFile>>(emptyList()) }
    var pendingAiFiles by pendingAiFilesState
    // 系统分享 → AI：跳转进入本对话后把分享内容预挂到输入区上方文件挂载行（与正常选文件一致），
    // 用户点发送才真正发送；消费后置 null，避免切换对话/重建时重复挂载。
    LaunchedEffect(friendId, AiChatShare.pending.value) {
        val inject = AiChatShare.pending.value ?: return@LaunchedEffect
        if (friendId != AI_CHAT_ID) return@LaunchedEffect
        if (inject.files.isNotEmpty()) {
            pendingAiFiles = pendingAiFiles + inject.files
        }
        if (inject.text.isNotBlank()) {
            inputText = if (inputText.isBlank()) inject.text else inputText.trimEnd() + "\n" + inject.text
        }
        AiChatShare.pending.value = null
    }
    // 工作区「选择文件」模式：点条目 = 交给当前对话并返回（「+ → 文件 → 工作区」会用到）
    val wsPickModeState = remember { mutableStateOf(false) }
    var wsPickMode by wsPickModeState
    // ── 方案编辑（构建前可改方案：逐行 = 一个步骤）──
    val showPlanEditState = remember { mutableStateOf(false) }
    var showPlanEdit by showPlanEditState
    val planEditTextState = remember { mutableStateOf("") }
    var planEditText by planEditTextState
    // 工作区选中的文件 → 注入普通聊天的待发送队列（好友/群聊复用「已选择 N 个文件」确认弹窗，点发送才发）
    val wsInjectForSendState = remember { mutableStateOf<List<Triple<Uri, String, Long>>>(emptyList()) }
    var wsInjectForSend by wsInjectForSendState

    val aiFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = 10 - pendingAiFiles.size
        if (room <= 0) {
            android.widget.Toast.makeText(ctx, "最多同时发送 10 个文件", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val added = mutableListOf<PendingAiFile>()
        for (uri in uris.take(room)) {
            val mime = ctx.contentResolver.getType(uri) ?: "*/*"
            val name = queryDisplayName(ctx, uri) ?: uri.lastPathSegment ?: "文件"
            val size = try {
                ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (_: Exception) { 0L }
            // 小文件（≤512KB）预读内容，方便直接传给 AI 上下文；大文件只保留元数据，AI 可用 get_file 读取
            val bytes = if (size in 1..(512 * 1024)) {
                try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
            } else null
            added.add(PendingAiFile(uri = uri, name = name, mime = mime, size = size, bytes = bytes))
        }
        if (added.isNotEmpty()) {
            pendingAiFiles = pendingAiFiles + added
            showPlusDialog = false
        }
    }
    // ── 文件「另存为」(SAF)：拉起系统文件管理器，用户自选目录/改名后把源文件拷贝过去（不后台静默下载）──
    var pendingSaveAs by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val pending = pendingSaveAs
        pendingSaveAs = null
        if (uri != null && pending != null) {
            com.aurora.chat.ui.chat.performSaveAs(scope, ctx, uri, pending.first, pending.second)
        }
    }
    // 当前展开思考过程的消息 ID（= 文件级 aiStreamMsgId，切出对话再回来不归零，见其声明处说明）
    // ── 隐私控制：仅在「当前对话内」应用 FLAG_SECURE（禁止截图 / 禁止录屏） ──
    // 关键修复：FLAG_SECURE 挂在 Activity 窗口上是全局的，必须在离开对话时清除，
    // 否则会出现「离开对话仍无法截图、切对话靠 recreate 闪一下才恢复」。
    // 这里：1) 先读对方设置得到 friendNeedsSecure；2) 用 DisposableEffect 进入时挂上、
    // 离开/切换/关闭保护时清除，且不再 recreate（彻底消除闪烁与卡顿）。
    val privacyRefreshKeyState = remember { mutableStateOf(0) }
    var privacyRefreshKey by privacyRefreshKeyState
    // 对方隐私开关的实时变更信号（TCP privacy_alert 推送触发）→ 重新读取对方设置
    val privacyTick by ChatViewModel.privacyRefreshKey.collectAsState()
    LaunchedEffect(privacyTick) { if (privacyTick > 0) privacyRefreshKey++ }
    // 对方是否要求禁止截图/录屏（不随 friendId 重置，避免切对话时短暂"解除保护"闪烁；读到后更新）
    var friendNeedsSecure by remember { mutableStateOf(false) }
    LaunchedEffect(friendId, privacyRefreshKey) {
        if (friendId <= 0) { friendNeedsSecure = false; return@LaunchedEffect }
        val fs = try {
            val fr = AuroraApi.getFriendPrivacySettings(friendId)
            if (fr.success) fr.data else null
        } catch (_: Exception) { null }
        friendNeedsSecure = fs?.let {
            it.optBoolean("no_screenshot", false) || it.optBoolean("no_recording", false)
        } ?: false
    }
    val privacyWindow = (ctx as? android.app.Activity)?.window
    // 仅当停留在需要保护的私聊里才挂 FLAG_SECURE；离开该对话（onDispose）立即清除
    val applySecure = friendId > 0 && friendNeedsSecure
    DisposableEffect(privacyWindow, applySecure) {
        if (privacyWindow != null) {
            if (applySecure) {
                privacyWindow.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                // 不需要保护时确保清掉（程序启动/上个对话残留都可能带着它）
                privacyWindow.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {
            // 离开对话 / 切换对话 / 关闭保护：立即解除，避免整个应用被锁在不可截图状态
            if (applySecure) {
                privacyWindow?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    // 消息列表 — 支持 StoreMessageRef (adapter) 和 ChatMsg (旧加载路径) 共存
    // 普通会话（私信/群/通知/自己）跨切回保留适配器与已加载窗口，避免重进被重置成「最近 120 条」丢历史；
    // AI 多对话自身由 switchAiConversation 管理内容，保持原 remember 行为不变。
    val adapter = if (friendId == AI_CHAT_ID) {
        remember(friendId) { MessageStoreAdapter().also { it.setWindowCap(CHAT_WINDOW_CAP) } }
    } else {
        ConversationAdapterCache[friendId]
    }
    //  从 companion cache 恢复已销毁组件的数据（切对话零加载）
    //   remember(friendId) 确保切换对话时重建列表，同时恢复该对话的缓存
    val messages = remember(friendId) {
        val saved = companionMessageCache.remove(friendId)
        if (saved != null) mutableStateListOf<IMessageRef>().also { it.addAll(saved) }
        else mutableStateListOf<IMessageRef>()
    }
    // 正在后台上传（Application 级、已持久化）的媒体占位气泡：与 messages 分离保存，
    // 这样切出对话/退出软件再回来、以及 syncMessages 按 store 重建列表时，上传占位都不会被冲掉。
    // 由 LaunchedEffect(friendId) 进入对话时从 PendingUploadStore 恢复，并在 onSendMedia/onSendVoice 时追加。
    val pendingUploadPlaceholders = remember(friendId) { mutableStateListOf<IMessageRef>() }

    // ── 发送失败消息（气泡左侧红叹号，点击可重发）──
    // 与「上传占位」同一套思路：独立于 messages 保存 → syncMessages 按 store 重建列表时能自动补回，
    // 不会再出现「发不出去 → 留在屏幕上 → 退出重进就消失」的割裂。
    // 同时落盘到 FailedMessageStore：切会话、杀进程、重启 App 都不会丢，直到发送成功或手动删除。
    val pendingFailedMessages = remember(friendId) { mutableStateListOf<IMessageRef>() }
    // 本地系统广播（拉黑/好友验证/账号异常…）的内存态：独立于 messages 保存，
    // syncMessages 按 store 重建列表时自动补回，不会被服务端数据冲掉（与 pendingFailedMessages 同一套思路）。
    // 同时落盘到 LocalNoticeStore：切会话、杀进程、重启 App 都还在，像微信那样一直挂着。
    val pendingLocalNotices = remember(friendId) { mutableStateListOf<IMessageRef>() }
    // 非空 = 用户点了某条红叹号，正在等「是否重新发送」确认
    val retryTargetMsgState = remember { mutableStateOf<IMessageRef?>(null) }
    var retryTargetMsg by retryTargetMsgState

    /** 读落盘的失败消息并还原成带红叹号的本地气泡（IO 读取 + 解密，必须在后台线程调用） */
    suspend fun loadFailedBubblesOnIo(): List<IMessageRef> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            // AI 对话是纯本地会话（friendId=-2）。旧版本误点表情包会往服务端发消息，
            // 失败后把「红叹号气泡 + 群聊不存在系统广播」落盘，且按 -2 被所有 AI 子对话共享，
            // 每次新建对话都被还原出来。这里一次性抹掉这批脏数据，之后本地会话不再还原。
            // 只清 -2 这一个会话，普通聊天 / 群聊的记录一概不动。
            if (isLocalChat(friendId)) {
                com.aurora.chat.data.local.FailedMessageStore.clear(ctx, friendId)
                com.aurora.chat.data.local.LocalNoticeStore.clear(ctx, friendId)
                return@withContext emptyList()
            }
            com.aurora.chat.data.local.FailedMessageStore.list(ctx, friendId).map { f ->
                ChatMsg.create(
                    text = f.text, isMine = true, fromUserId = currentUserId,
                    createdAt = f.createdAt, replyToText = f.replyToText,
                    replyToSender = f.replyToSender, replyToId = f.replyToId,
                    mediaType = f.mediaType, mediaUrl = f.mediaUrl,
                    flashDuration = f.flashDuration, contactSource = f.contactSource
                ).copy(id = f.id, sendFailed = true)
            }
        }

    /**
     * 把失败气泡并入内存态 + 当前消息列表（按 id 去重，可重复调用）。
     *
     * 进入会话时必须和消息在**同一帧**上屏：之前这一步挂在独立 LaunchedEffect 里，
     * 要等消息先画出来、再走一次 IO 读取与解密才补上，表现就是「其他消息都出来了，
     * 红叹号/失败提示才慢半拍冒出来」。现在并入主加载链，首屏即最终形态。
     */
    fun applyFailedBubbles(list: List<IMessageRef>) {
        pendingFailedMessages.clear()
        pendingFailedMessages.addAll(list)
        if (list.isEmpty()) return
        val existingIds = messages.mapTo(mutableSetOf()) { it.id }
        val toAdd = list.filter { existingIds.add(it.id) }
        if (toAdd.isNotEmpty()) messages.addAll(toAdd)
        // 与 syncMessages 同口径排序：按失败时刻归位，不会被甩到会话最末尾
        messages.sortWith(
            compareBy({ if (it.createdAt <= 0L) Long.MAX_VALUE else it.createdAt }, { it.id })
        )
    }

    /**
     * 把一条发不出去的消息变成「失败气泡」：留在原位、左侧红叹号、落盘可重发。
     *
     * @param optimisticId 乐观气泡的临时 id；传 0 表示没有乐观气泡（直接新增）
     */
    /**
     * 把一条发不出去的消息变成「失败气泡」：留在原位、左侧红叹号、落盘可重发。
     *
     * 通用架构：直接接收原始的乐观气泡 [ref]（ChatMsg），用 copy 仅翻转 sendFailed，
     * 完整保留 mediaType / mediaUrl / 贴纸 / 表情 / 引用 / 闪照 等全部内容 ——
     * 这样文本、图片、语音、贴纸、表情、分享卡片等任何形式的消息失败，都会以「原样 + 红叹号」呈现，
     * 不再出现「只有文本有提示、贴纸/表情被静默吞掉」的分裂。
     */
    fun markSendFailed(ref: ChatMsg) {
        // 本地 AI 对话不产生「发往服务端失败」的红叹号气泡
        if (isLocalChat(friendId)) return
        // 本地 id 用「时间戳*1000 + 随机」取负：负数与服务端 id 天然隔离，
        // 且不会与 nextId-- 的临时 id 撞车（重启后仍唯一）。
        val localId = -(System.currentTimeMillis() * 1000L + (0..999).random())
        val createdAt = System.currentTimeMillis() / 1000
        val failed = ref.copy(
            id = localId, serverId = 0, sendFailed = true,
            isUploading = false, isNew = false, createdAt = createdAt
        )
        // 乐观气泡（如果有）换成失败气泡：内容相同，只是 id 与红叹号不同
        messages.removeAll { it.id == ref.id }
        pendingUploadPlaceholders.removeAll { it.id == ref.id }
        pendingFailedMessages.removeAll { it.id == ref.id }
        pendingFailedMessages.add(failed)
        messages.add(failed)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.aurora.chat.data.local.FailedMessageStore.save(
                ctx,
                com.aurora.chat.data.local.FailedMessage(
                    id = localId, friendId = friendId, text = failed.text,
                    replyToId = failed.replyToId, replyToText = failed.replyToText,
                    replyToSender = failed.replyToSender,
                    mediaType = failed.mediaType, mediaUrl = failed.mediaUrl,
                    flashDuration = failed.flashDuration, contactSource = failed.contactSource,
                    createdAt = createdAt
                )
            )
        }
    }

    /**
     * 在会话里打印一条「本地广播」（居中系统提示）并落盘留存。
     *
     * 用于发送被服务器以业务原因拒绝时（拉黑 / 好友验证 / 账号异常 / 被移出群 / 被禁言 …）：
     * 这类提示由客户端自己渲染、自己留存，不依赖服务器推送（服务器只返回一句错误文案，
     * 真正的「对方已将你拉黑」广播是本地生成的，像微信那样一直挂在会话里）。
     */
    fun addLocalNotice(convId: Long, text: String, type: String = "") {
        // 本地 AI 对话不产生服务端业务广播，避免再写出永久残留
        if (isLocalChat(convId)) return
        val createdAt = System.currentTimeMillis() / 1000
        // 同内容短间隔去重：避免全站禁言 / 群解散期间连发被拒时，同一条提示反复刷屏
        // （像微信那样合并成一条）。超出 5 分钟再触发则重新提示一次。
        val duplicated = messages.any { it.isSystemNotice && it.text == text && (createdAt - it.createdAt) < 300 }
        if (duplicated) return
        val notice = ChatMsg.create(
            text = text, isMine = false, fromUserId = 0,
            createdAt = createdAt, isNew = false
        ).copy(isSystemNotice = true)
        messages.add(notice)
        // 同步进内存态：后续 syncMessages 按 store 重建列表时会自动补回，绝不会被服务端数据冲掉。
        // 按 id 去重，避免同一对象被重复加入（多次 syncMessages 调用安全）。
        if (!pendingLocalNotices.any { it.id == notice.id }) pendingLocalNotices.add(notice)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.aurora.chat.data.local.LocalNoticeStore.save(
                ctx,
                com.aurora.chat.data.local.LocalNotice(conversationId = convId, text = text, createdAt = createdAt, type = type)
            )
        }
    }

    /**
     * 进入会话时把落盘的本地广播还原进消息列表（与失败消息同理：本地生成的广播不进
     * LocalMessageStore，故独立从 [com.aurora.chat.data.local.LocalNoticeStore] 读回）。
     * 本地广播通常产生于最近一次失败，还原后追加在末尾即可。
     */
    fun restoreLocalNotices(convId: Long) {
        // AI 本地对话不该有服务端业务广播（拉黑/禁言/群聊不存在等），一律不还原
        if (isLocalChat(convId)) return
        val list = com.aurora.chat.data.local.LocalNoticeStore.list(ctx, convId)
        if (list.isEmpty()) return
        val exists = messages.mapTo(mutableSetOf()) { it.id }
        for (n in list) {
            val notice = ChatMsg.create(
                text = n.text, isMine = false, fromUserId = 0,
                createdAt = n.createdAt, isNew = false
            ).copy(isSystemNotice = true)
            // 内存态补回：syncMessages 重建列表时按 id 去重补回，不会因网络刷新被服务端数据冲掉。
            if (!pendingLocalNotices.any { it.id == notice.id }) pendingLocalNotices.add(notice)
            // 直接进入会话时立即上屏（若之后 syncMessages 已跑过，靠 pendingLocalNotices 再补回，按 id 去重不会重复）
            if (exists.add(notice.id)) messages.add(notice)
        }
    }

    /** 重发一条失败消息：成功后气泡转正（去红叹号、回填服务端 id），失败则保持原样 */
    fun resendFailedMessage(m: IMessageRef) {
        val localId = m.id
        val failedText = m.text
        // 护栏：资源型媒体消息没有资源地址（上传阶段就失败、或分享时文件没传上去）时不能重发 ——
        // 那样只会把一个空地址当成正常文件发出去，对方收到的是坏掉的卡片。
        // 只针对真正的资源型媒体（图片/视频/语音/动图/文件）；
        // 转账、位置、订单这类卡片型消息的内容在正文里，不受影响。
        val resourceMedia = setOf("image", "video", "voice", "gif", "file")
        val isResourceKind = m.mediaType in resourceMedia ||
                m.mediaType.startsWith("image/") || m.mediaType.startsWith("video/")
        if (isResourceKind && m.mediaUrl.isBlank()) {
            AuroraApi.showErrorToast(ctx, "原文件未上传成功，请重新选择文件发送")
            retryTargetMsg = null
            return
        }
        ChatViewModel.sendMessage(
            currentUserId = currentUserId,
            friendId = friendId,
            text = failedText,
            replyTo = m.replyToId,
            replyToText = m.replyToText,
            replyToSender = m.replyToSender,
            mediaType = m.mediaType,
            mediaUrl = m.mediaUrl,
            flashDuration = m.flashDuration,
            contactSource = m.contactSource,
            onSuccess = { serverId ->
                retryTargetMsg = null
                pendingFailedMessages.removeAll { it.id == localId }
                // 原地转正：去掉红叹号、回填服务端 id，视觉与普通已发消息一致
                val idx = messages.indexOfFirst { it.id == localId }
                if (idx >= 0) {
                    val cur = messages[idx]
                    messages[idx] = ChatMsg.create(
                        text = cur.text, isMine = true, fromUserId = currentUserId,
                        createdAt = cur.createdAt, replyToText = cur.replyToText,
                        replyToSender = cur.replyToSender, replyToId = cur.replyToId,
                        mediaType = cur.mediaType, mediaUrl = cur.mediaUrl,
                        flashDuration = cur.flashDuration
                    ).copy(id = localId, serverId = serverId, sendFailed = false)
                }
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    com.aurora.chat.data.local.FailedMessageStore.remove(ctx, localId)
                }
                if (failedText.isNotBlank()) {
                    ChatViewModel.updateConversationPreview(friendId, failedText, System.currentTimeMillis() / 1000)
                }
            },
            onError = { err ->
                retryTargetMsg = null
                // 重发失败 = 又一次普通的发送失败，不做任何特殊处理：
                // 气泡原样留在原地（红叹号继续亮着），不弹 toast。
                // 之前这里弹 "仍发送失败：$err"，而 err 是底层异常串（含完整请求 URL），
                // 用户看到的就是一长串网址，毫无意义 —— 失败这件事红叹号已经说明白了，
                // 想再试就再点一次叹号。
                // 唯一例外：账号被禁 / 登录失效，留着气泡也永远发不出去，直接踢下线并清掉。
                val accountGone = err.contains("您的账号异常") && err.contains("410")
                val loginExpired = err.contains("账号异常，请重新登录") && err.contains("401")
                if (accountGone || loginExpired) {
                    if (accountGone) {
                        AuroraApi.showErrorToast(ctx, "发送失败: 您的账号异常，操作已禁止")
                    } else {
                        android.widget.Toast.makeText(ctx, "登录状态已过期，请重新登录", android.widget.Toast.LENGTH_LONG).show()
                    }
                    scope.launch { ChatRepository.checkAndLogoutCurrentUser(ctx, currentUserId) }
                    // 账号已废：气泡留着也永远发不出去，跟首次发送失败一样清掉
                    pendingFailedMessages.removeAll { it.id == localId }
                    messages.removeAll { it.id == localId }
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        com.aurora.chat.data.local.FailedMessageStore.remove(ctx, localId)
                    }
                }
            },
            context = ctx
        )
    }

    // ═══ 新消息贴底（保留"把上面内容顶上去"的滚动动画）═══
    // 触发源：
    //   1) 自己发送（pendingScrollToBottom = true）→ 回到底部、并恢复自动跟随；
    //   2) 本来就贴在底部（autoFollow = true）时收到对方消息 → 跟随；
    //   3) 上滑翻历史（autoFollow = false）时收到对方消息 → 不拽回，交给未读角标。
    //
    // 气泡本身的进场动画已默认关闭（消息设置 → 消息动画 = 无），这里只保留列表的贴底动画，
    // 视觉上就是"新消息把上面的内容顶上去"。等一帧：messages.add() 的那一帧还没组合出新 item，
    // 此时 index 0 还是旧气泡。
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val forced = pendingScrollToBottom
        if (!forced && !autoFollow) return@LaunchedEffect
        withFrameNanos { }   // 等新 item 完成布局，此时 index 0 才是新气泡
        programmaticScrolling = true
        try {
            // 贴底动画提速一倍：先把剩余距离瞬移一半，再动画走完另一半。
            // 动画行程减半 → 时长约减半；终点仍由 animateScrollToItem(0) 精确对齐到底部。
            if (listState.firstVisibleItemIndex == 0) {
                val remain = listState.firstVisibleItemScrollOffset
                if (remain > 1) listState.scrollToItem(0, remain / 2)
            }
            listState.animateScrollToItem(0)
        } catch (_: Exception) {
        } finally {
            programmaticScrolling = false
        }
        autoFollow = true
        anchorItemIndex = -1
        unreadNewCount = 0
        pendingScrollToBottom = false
    }
    /** generate_media / capture_screen 生成成功：插入一条 AI 的图片/视频消息。
     *
     *  afterMsgId > 0 时**就近插入**到该条助手消息的正后方（而不是列表末尾）：
     *  这样"文字 → 图 → 后续文字"的顺序与工具调用真正发生的位置一致，
     *  不会等全部文字输出完才在末尾突然冒出图片。
     */
    fun insertAiMediaCard(type: String, mediaUrl: String, afterMsgId: Long = -1L) {
        val now = System.currentTimeMillis() / 1000
        val isVideo = type == "video"
        // 诊断：确认回调已进入 UI 层、URL 形态、以及本地文件是否真实存在
        val diskInfo = if (mediaUrl.startsWith("file://")) {
            val f = java.io.File(mediaUrl.removePrefix("file://"))
            "磁盘存在=${f.exists()} 大小=${if (f.exists()) f.length() else -1} abs='${f.absolutePath}'"
        } else "非本地路径"
        com.aurora.chat.ErrorReporter.debug(
            "AI_CaptureDelivery",
            "insertAiMediaCard 进入 type=$type url='$mediaUrl' $diskInfo afterMsgId=$afterMsgId messages.size=${messages.size}"
        )
        val card = ChatMsg.create(
            isNew = true, createdAt = now, isMine = false, fromUserId = AI_CHAT_ID,
            senderName = AiNameState.name ?: "AI", text = if (isVideo) "[视频]" else "[图片]",
            mediaType = if (isVideo) "video" else "image", mediaUrl = mediaUrl
        )
        // 就近插入:紧跟锚点消息之后,跳过已插在它后面的媒体卡片(保持多次生成的先后顺序)
        val anchor = if (afterMsgId > 0L) messages.indexOfFirst { it.id == afterMsgId } else -1
        if (anchor >= 0) {
            var insertAt = anchor + 1
            while (insertAt < messages.size) {
                val m = messages[insertAt]
                if (m is ChatMsg && (m.mediaType == "image" || m.mediaType == "video") && m.text.startsWith("[")) insertAt++
                else break
            }
            messages.add(insertAt, card)
            com.aurora.chat.ErrorReporter.debug("AI_CaptureDelivery", "卡片就近插入 insertAt=$insertAt (锚点=$anchor) size=${messages.size} id=${card.id}")
        } else {
            messages.add(card)
            com.aurora.chat.ErrorReporter.debug("AI_CaptureDelivery", "卡片追加末尾 size=${messages.size} id=${card.id}")
        }
        ChatViewModel.updateConversationPreview(AI_CHAT_ID, if (isVideo) "[视频]" else "[图片]", now)
        // 全量 JSON 序列化卸载到 IO 线程：超大会话下主线程同步序列化会卡 UI（含执行中每次方案卡更新）
        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, localChatKey(AI_CHAT_ID), messages.filter { !it.isSystemNotice }.toList()) } catch (_: Exception) {} }
        // 新卡片插入:同流式增量一样,钉住 anchor
        // 贴底自动跟随,离开底部钉住 anchor 防漂移（与流式增量一致）
        if (!autoFollow && anchorItemIndex >= 0) {
            programmaticScroll(anchorItemIndex, anchorItemOffset)
        } else if (autoFollow) {
            programmaticScroll(0)
        }
    }

    /** get_file 取出本地文件后，把它落成一条消息（AI 端，复用聊天渲染管线）。
     *  按扩展名自动分流：图片 → 图片消息气泡；视频 → 视频消息；其余 → 文件卡片。
     *  此前这里硬编码 mediaType="file"，导致即便取出的是 .jpg/.png 也一律显示成文件卡片。 */
    fun insertAiFileCard(absPath: String, forceFileCard: Boolean = false, afterMsgId: Long = -1L) {
        val now = System.currentTimeMillis() / 1000
        val disk = java.io.File(absPath)
        val lower = absPath.lowercase()
        // 按扩展名判定媒体类型（与 ChatMsg.create 的归一化保持一致）
        val ext = lower.substringAfterLast('.').substringBefore('?')
        val imgExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
        val vidExts = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg")
        val kind = when {
            forceFileCard -> "file"      // 调用方明确要求文件卡片(如 capture_screen deliver=file)
            ext in imgExts && disk.isFile && disk.length() > 0L -> "image"
            ext in vidExts && disk.isFile && disk.length() > 0L -> "video"
            else -> "file"
        }
        com.aurora.chat.ErrorReporter.debug(
            "AI_GetFile",
            "插入卡片 abs='$absPath' ext='$ext' 判定类型=$kind afterMsgId=$afterMsgId 磁盘存在=${disk.exists()} " +
            "大小=${if (disk.exists()) disk.length() else -1} bytes"
        )
        if (kind != "file") {
            // 图片/视频：交给媒体管线渲染成图片/视频消息（与 generate_media、capture_screen 同一路径）
            insertAiMediaCard(kind, "file://$absPath", afterMsgId)
            return
        }
        val card = ChatMsg.create(
            isNew = true, createdAt = now, isMine = false, fromUserId = AI_CHAT_ID,
            senderName = AiNameState.name ?: "AI", text = "", mediaType = "file",
            mediaUrl = "file://$absPath"
        )
        val anchor = if (afterMsgId > 0L) messages.indexOfFirst { it.id == afterMsgId } else -1
        if (anchor >= 0) {
            var insertAt = anchor + 1
            while (insertAt < messages.size) {
                val m = messages[insertAt]
                if (m is ChatMsg && m.mediaType.isNotEmpty() && m.mediaUrl.startsWith("file://")) insertAt++
                else break
            }
            messages.add(insertAt, card)
            com.aurora.chat.ErrorReporter.debug("AI_GetFile", "卡片就近插入 insertAt=$insertAt (锚点=$anchor) size=${messages.size} id=${card.id}")
        } else {
            messages.add(card)
            com.aurora.chat.ErrorReporter.debug("AI_GetFile", "卡片追加末尾 size=${messages.size} id=${card.id}")
        }
        ChatViewModel.updateConversationPreview(AI_CHAT_ID, "[文件]", now)
        // 全量 JSON 序列化卸载到 IO 线程：超大会话下主线程同步序列化会卡 UI（含执行中每次方案卡更新）
        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, localChatKey(AI_CHAT_ID), messages.filter { !it.isSystemNotice }.toList()) } catch (_: Exception) {} }
        // 新卡片插入:同流式增量一样,钉住 anchor
        // 贴底自动跟随,离开底部钉住 anchor 防漂移（与流式增量一致）
        if (!autoFollow && anchorItemIndex >= 0) {
            programmaticScroll(anchorItemIndex, anchorItemOffset)
        } else if (autoFollow) {
            programmaticScroll(0)
        }
    }

    /** AI 工具调用状态：作为「独立消息」插入消息列表，就地站位，像普通消息一样持久化，绝不消失。
     *  addToolStatus 在工具开始执行时插入一条状态消息（初始"调用中"，随后原地更新为成功/失败）。 */
    fun addToolStatus(tool: String, state0: Int = 0, afterMsgId: Long = -1L): Long {
        val now = System.currentTimeMillis() / 1000
        val id = System.currentTimeMillis() * 10000L + (0..9999).random()
        val nm = ChatMsg.create(
            serverId = id, text = tool, isMine = false, fromUserId = AI_CHAT_ID,
            senderName = "", isNew = true, createdAt = now,
            isToolStatus = true, aiToolStage = tool, aiToolStageState = state0
        )
        if (afterMsgId > 0L) {
            // 就地插入：紧跟在该条助手消息之后，按调用顺序排在已插入的状态消息后面，
            // 而不是追加到列表末尾（否则会被后续消息挤出时间线，堆到所有结果之后）。
            val ai = messages.indexOfFirst { it.id == afterMsgId }
            if (ai >= 0) {
                var insertAt = ai + 1
                while (insertAt < messages.size) {
                    val m = messages[insertAt]
                    if (m !is ChatMsg || !m.isToolStatus) break
                    insertAt++
                }
                messages.add(insertAt, nm)
            } else {
                messages.add(nm)
            }
        } else {
            messages.add(nm)
        }
        // 工具状态消息插入:同流式增量一样,钉住 anchor
        // 贴底自动跟随,离开底部钉住 anchor 防漂移（与流式增量一致）
        if (!autoFollow && anchorItemIndex >= 0) {
            programmaticScroll(anchorItemIndex, anchorItemOffset)
        } else if (autoFollow) {
            programmaticScroll(0)
        }
        return id
    }

    /**
     * AI 流式落盘防抖：流式生成期间 onDelta 高频触发，若每 token 都全量加密+序列化+写盘,
     * 主线程会被 IO 持续占满，导致上下滑/切板块极其卡顿。故采用「停止增量 600ms 后才落盘一次」的策略。
     */
    var aiSaveOpPending by remember { androidx.compose.runtime.mutableStateOf(false) }
    var aiSaveJob by remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun scheduleAiDebouncedSave() {
        val prevJob = aiSaveJob
        if (aiSaveOpPending) { prevJob?.cancel(); aiSaveOpPending = false }
        aiSaveOpPending = true
        // 调度时绑定目标会话 key：避免 600ms 防抖窗口内切换会话后，把执行时的内存写进错误会话文件
        val saveKey = AiConversationSession.storageKey()
        aiSaveJob = aiGlobalScope.launch {
            kotlinx.coroutines.delay(600)
            aiSaveOpPending = false
            // 已切到其他会话则跳过：切走前 openAiConversation 已强制落盘过本会话，避免把别的会话内存写进本会话文件
            if (AiConversationSession.storageKey() != saveKey) return@launch
            // 秒级防抖：将当前内存中的全部消息落盘（纯 IO，切到后台线程避免阻塞主线程）
            val snapshot = messages.filter { !it.isSystemNotice }.toList()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { saveLocalChatMessages(ctx, currentUserId, saveKey, snapshot) } catch (_: Exception) {}
            }
        }
    }
    /** 原地更新某条工具状态消息的最终状态（1成功/2失败）。为性能计，不再每完成一条工具就同步全量过滤+写盘，
     *  而是复用防抖保存：一次工具爆发由一轮结束时的一次落盘覆盖，避免「很多「已调用」时」反复 on 主线程全表过滤、IO 重复序列化整份会话。 */
    val updateToolStatus: (Long, String, Int) -> Unit = { statusId, tool, state ->
        val idx = messages.indexOfFirst { it.id == statusId }
        if (idx >= 0 && messages[idx] is ChatMsg) {
            val cur = messages[idx] as ChatMsg
            messages[idx] = cur.copy(aiToolStage = tool, aiToolStageState = state)
            scheduleAiDebouncedSave()
        }
    }

    /** 工具结果中若带交付信息，则落成对应消息。
     *  - get_file：结果带 abs（文件已复制到 downloads）→ 插入文件消息（按扩展名自动分流为图片/视频/文件）
     *  - capture_screen：结果带 delivered_url → 按 delivered_is_media 落成图片消息或文件卡片
     *  内联工具循环走这里交付；runAIChatLoop 走 onFileSent / onMediaGenerated 回调。二者互斥。 */


    fun insertAiFileCardIfGot(fsName: String, result: String, afterMsgId: Long = -1L) {
        com.aurora.chat.ErrorReporter.debug(
            "AI_GetFile",
            "insertAiFileCardIfGot tool=$fsName afterMsgId=$afterMsgId raw=${result.take(200)}"
        )
        val obj = try { org.json.JSONObject(result) } catch (_: Exception) { null }
        if (obj == null || !obj.optBoolean("ok")) return

        // capture_screen 交付：直接读 delivered_url / delivered_is_media
        if (fsName == AiChatManager.TOOL_CAPTURE_SCREEN) {
            val url = obj.optString("delivered_url", "")
            if (url.isBlank()) return                 // save 模式:无交付
            val isMedia = obj.optBoolean("delivered_is_media", true)
            if (isMedia) insertAiMediaCard("image", url, afterMsgId)
            else {
                val abs = url.removePrefix("file://")
                insertAiFileCard(abs, forceFileCard = true, afterMsgId = afterMsgId)
            }
            return
        }

        if (fsName != AiChatManager.TOOL_GET_FILE) return
        val sentAbs = obj.optString("abs", "")
        // AI 可显式要求「以文件形式发送」(as_file=true) → 强制文件卡片,不做图片自动分流
        val forceFile = obj.optBoolean("force_file_card", false)
        com.aurora.chat.ErrorReporter.debug("AI_GetFile", "解析出 abs='$sentAbs' forceFileCard=$forceFile")
        if (sentAbs.isNotEmpty()) insertAiFileCard(sentAbs, forceFileCard = forceFile, afterMsgId = afterMsgId)
    }

    /** play_video 工具结果 → 请求弹出应用内全屏播放器。
     *  内联工具循环走这里交付（与 runAIChatLoop 的 onOpenVideo 同效）；runAIChatLoop 路径用回调。
     *  二者都要覆盖——**只接一条**的表现就是「AI 调用成功但播放器不弹」。
     *  实际弹出由主 composable 消费 [pendingOpenVideoUrl] 完成（本函数在 videoPlayUrl 声明之前执行）。 */
    fun openVideoIfRequested(fsName: String, result: String) {
        if (fsName != AiChatManager.TOOL_PLAY_VIDEO) return
        val obj = try { org.json.JSONObject(result) } catch (_: Exception) { null } ?: return
        if (!obj.optBoolean("ok")) return
        val url = obj.optString("play_url", "")
        if (url.isNotBlank()) pendingOpenVideoUrl.value = url
    }

    /** update_plan_step 工具回调:把方案里某一步骤的状态(stepIndex 从 1 开始)回写到该方案消息的 [[PLAN_CARD]] JSON 并持久化,
     *  卡片据此自动打勾/隐藏已完成项(对齐 CodeBuddy 逐步勾选 + 逐条隐藏)。 */
    // 【任务流程卡】这里以前会新建/更新一条**独立消息**贴在会话最末尾 —— 结果它永远挂在「所有回答的下方」。
    // 现在改为「渲染期从最新一张方案卡派生、只注入最新一条 AI 消息」（见 livePlanState / lastAiMsgIdState），
    // 于是它正好出现在**交付总结上方**；步骤状态本就写在那张方案卡里，不需要再多一条消息承载。

    fun updatePlanStep(stepIndex: Int, status: String) {
        // 原始方案卡 = 最近一条**不带实时进度标记**的方案卡消息（必须排除尾部进度卡，否则会一直改到它）
        val idx = messages.indexOfLast {
            it is ChatMsg && it.text.contains("[[PLAN_CARD]]") && !it.text.startsWith(PLAN_LIVE_TAG)
        }
        if (idx < 0) return
        val m = messages[idx] as ChatMsg
        val data = parsePlanCard(m.text) ?: return
        val i0 = (if (stepIndex >= 1) stepIndex - 1 else stepIndex).coerceAtLeast(0)
        if (i0 >= data.steps.size) return
        // 【代码层强制约束】提示词只能"劝"，模型仍会犯；这里直接兜住两类实际反馈到的问题：
        //  ① 反复标记：把已经做完的步骤又标成「正在执行」→ 直接忽略该回退。
        //     （要重做必须先标成 pending，再标 doing，这样意图明确。）
        if (data.steps[i0].status == "done" && status == "doing") return
        //  ② 严格串行（单向收尾）：只要在标记第 i0 步（不管 doing 还是 done），
        //     就把它**之前**仍挂在 doing 的步骤一律收尾成 done。
        //     用户实际反馈："第 6 步完成了，第 5 步却还是正在执行" → 卡片看起来跳了流程。
        //     串行执行下能推进到第 i0 步，说明前面的步骤早已做完（模型只是忘了补 done）。
        //     ⚠️ 只收尾 doing，**绝不**把 pending 直接改成 done —— 没开始的步骤不能凭空算完成。
        //  ③ 最多一个 doing：把第 i0 步标成 doing 时，
        //     · 它之前的 doing → done（已完成，上面那条已覆盖）；
        //     · 它之后的 doing → 退回 pending（说明是回头重做，后面的步骤还没轮到，
        //       直接标 done 等于凭空算完成，退回 pending 才是诚实的）。
        val newSteps = data.steps.mapIndexed { j, s ->
            when {
                j == i0 -> s.copy(status = status)
                j < i0 && s.status == "doing" -> s.copy(status = "done")
                j > i0 && s.status == "doing" && status == "doing" -> s.copy(status = "pending")
                else -> s
            }
        }
        // 状态完全没有变化（例如重复 done）→ 不必改写消息、不必落盘
        if (newSteps == data.steps) return
        val newData = data.copy(steps = newSteps)
        val newJson = serializePlanJson(newData)
        val open = m.text.indexOf("[[PLAN_CARD]]")
        val close = m.text.indexOf("[[/PLAN_CARD]]")
        if (open < 0 || close <= open) return
        val newText = m.text.substring(0, open + "[[PLAN_CARD]]".length) + newJson + m.text.substring(close)
        messages[idx] = m.copy(text = newText)
        // 方案卡自身已就地更新（上一行）→ 由它派生出的「任务流程卡」会自动跟着刷新，无需再单独建消息。
        // 全量 JSON 序列化卸载到 IO 线程：超大会话下主线程同步序列化会卡 UI（含执行中每次方案卡更新）
        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, localChatKey(AI_CHAT_ID), messages.filter { !it.isSystemNotice }.toList()) } catch (_: Exception) {} }
    }

    /** update_plan_step 工具结果 → 回写方案卡片（打勾/逐条隐藏已完成项）。
     *  **所有**工具执行分支都必须调用：原生 function calling / 文本协议兜底 / 内联循环，
     *  任一条漏接，表现都是"方案卡片只渲染一次、此后再也不勾选"。 */
    /** 打开方案编辑器（构建前可改）：读出「最新一张方案卡」的步骤，一行一个填进输入框 */
    fun openPlanEditor() {
        val idx = messages.indexOfLast { it is ChatMsg && it.text.contains("[[PLAN_CARD]]") && !it.text.startsWith(PLAN_LIVE_TAG) }
        if (idx < 0) return
        val data = parsePlanCard((messages[idx] as ChatMsg).text) ?: return
        // 已开工（存在 done / doing）的方案不允许再改 —— 用户要求
        if (data.steps.any { it.status == "done" || it.status == "doing" }) return
        planEditText = data.steps.mapIndexed { i, s -> "${i + 1}. ${s.content}" }.joinToString("\n")
        showPlanEdit = true
    }

    /** 保存方案编辑：按行解析（自动去掉行首 "1. " 这类编号），重写方案卡 JSON 并落盘 */
    fun savePlanStepsFromEditor() {
        val lines = planEditText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.replace(Regex("^\\d+\\s*[.、:：)）]\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val idx = messages.indexOfLast { it is ChatMsg && it.text.contains("[[PLAN_CARD]]") && !it.text.startsWith(PLAN_LIVE_TAG) }
        if (idx < 0) return
        val m = messages[idx] as ChatMsg
        val data = parsePlanCard(m.text) ?: return
        if (data.steps.any { it.status == "done" || it.status == "doing" }) return   // 双保险：开工后拒绝修改
        val newData = data.copy(steps = lines.map { PlanStep(it, "pending") })
        val open = m.text.indexOf("[[PLAN_CARD]]")
        val close = m.text.indexOf("[[/PLAN_CARD]]")
        if (open < 0 || close <= open) return
        messages[idx] = m.copy(
            text = m.text.substring(0, open + "[[PLAN_CARD]]".length) + serializePlanJson(newData) + m.text.substring(close)
        )
        // 全量 JSON 序列化卸载到 IO 线程：超大会话下主线程同步序列化会卡 UI（含执行中每次方案卡更新）
        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, localChatKey(AI_CHAT_ID), messages.filter { !it.isSystemNotice }.toList()) } catch (_: Exception) {} }
        showPlanEdit = false
    }

    fun applyPlanStepResult(tool: String, result: String) {
        if (tool != "update_plan_step") return
        val o = try { org.json.JSONObject(result) } catch (_: Exception) { return }
        if (!o.optBoolean("ok")) return
        val stepIndex = o.optInt("step_index", -1)
        val status = o.optString("status", "").trim()
        if (stepIndex >= 0 && status.isNotBlank()) updatePlanStep(stepIndex, status)
    }
    /** 安全替换全部消息（去重：后出现的覆盖先出现的，避免 LazyColumn 因重复 key 闪退）。
     *  增量同步：尽量保留同 id 的现有对象引用，仅对新增/移除/移动的位置做最小改动，
     *  避免 clear()+addAll 触发整表变更通知，使 TCP 增量合并/撤回不再引发整窗重组。 */
    fun MutableList<IMessageRef>.replaceAllUnique(elements: List<IMessageRef>) {
        val oldById = LinkedHashMap<Long, IMessageRef>()
        forEach { oldById[it.id] = it }
        // 先按 id 去重：同 id 只保留一条（保留最后出现的对象，位置取首次出现处）。
        // 上层窗口越界兜底可能返回指向同一条消息的重复引用（refAt 越界时统一回退到 store 末尾），
        // 若不去重，下方 add(p, moved) 会与列表长度错位（index > size）而崩溃。
        val dedup = LinkedHashMap<Long, IMessageRef>()
        for (e in elements) dedup[e.id] = e
        // 复用旧对象引用（顺序按去重后的新列表），未命中的用新对象
        val desired = dedup.values.map { oldById[it.id] ?: it }
        val desiredIds = desired.mapTo(mutableSetOf()) { it.id }
        // 1) 移除已不存在的消息（从后往前，避免索引错位）
        var idx = size - 1
        while (idx >= 0) {
            if (this[idx].id !in desiredIds) removeAt(idx)
            idx--
        }
        // 2) 按 desired 顺序修正：缺失则插入，位置不符则移动到正确位（保留引用）
        //    插入位置统一夹紧到 [0, size]，双保险防止任何边界情况再次越界。
        var p = 0
        for (el in desired) {
            val pos = indexOfFirst { it.id == el.id }
            if (pos == -1) {
                add(p.coerceIn(0, size), el)
            } else if (pos != p) {
                val moved = removeAt(pos)
                add(p.coerceIn(0, size), moved)
            }
            p++
        }
        // 3) 不变式自检（自愈兜底）：上面两步在「目标列表里已有重复 id」的历史脏数据下，
        //    仍可能残留重复项（重复项 id 都在 desiredIds 里，第 1 步不会删；第 2 步只按 id 找首位）。
        //    重复 key 会让 LazyColumn 直接崩。这里做一次 O(n) 校验，一旦与期望序列不符就整体重建，
        //    保证「列表内容 == desired」，杜绝 index > size 与重复 key 两类崩溃。
        var same = size == desired.size
        if (same) {
            for (i in desired.indices) {
                if (this[i].id != desired[i].id) { same = false; break }
            }
        }
        if (!same) {
            clear()
            addAll(desired)
        }
    }

    /** 安全追加单个消息（若 ID 已存在则跳过） */
    fun MutableList<IMessageRef>.addUnique(element: IMessageRef): Boolean {
        if (any { it.id == element.id }) return false
        return add(element)
    }

    /** 安全批量插入到指定位置（跳过已存在的 ID） */
    fun MutableList<IMessageRef>.addAllUnique(index: Int = -1, elements: List<IMessageRef>) {
        val existingIds = mapTo(mutableSetOf()) { it.id }
        val toAdd = elements.filter { existingIds.add(it.id) }
        if (toAdd.isNotEmpty()) {
            if (index < 0) addAll(toAdd) else addAll(index, toAdd)
        }
    }

    /** 将 adapter 的 indices 同步到 SnapshotStateList。 */
    fun syncMessages() {
        // 注意：adapter.indices 的元素是「store 绝对下标」，不是可见位置。
        // 必须按位置(0..visibleCount-1)遍历后交给 refAt，让其内部用 _indices[pos] 取 store 下标；
        // 窗口化加载时 _indices 非连续（形如 [start,total)），若误把元素值当位置传入会访问
        // _indices[>=window] 越界崩溃。refAt 内部已做越界兜底，越界时返回安全空引用。
        val n = adapter.visibleCount
        // distinctBy(id)：窗口越界兜底会让多个位置回退到同一条消息，先去重再同步，
        // 避免重复 key 触发 LazyColumn 崩溃、以及 replaceAllUnique 内部索引错位。
        val refs = (0 until n).map { adapter.refAt(it) }.distinctBy { it.id }
        messages.replaceAllUnique(refs)
        // 把"正在后台上传的媒体占位"追加到列表尾部（按 id 去重，滚动重建不丢）
        if (pendingUploadPlaceholders.isNotEmpty()) {
            messages.addAllUnique(elements = pendingUploadPlaceholders)
        }
        // 发送失败消息同理：adapter 只装服务端数据，失败消息不在其中，
        // 不在这里补回就会被 replaceAllUnique 抹掉（这正是"退出重进就消失"的根因）。
        if (pendingFailedMessages.isNotEmpty()) {
            messages.addAllUnique(elements = pendingFailedMessages)
        }
        // 本地系统广播（拉黑/好友验证等）同理：服务端不知道这些本地提示，adapter 里没有，
        // 不在这里补回就会被 replaceAllUnique 整盘抹掉（表现为"进入会话闪一下就没"或"退出重进就消失"）。
        // addAllUnique 按 id 去重，多次 syncMessages 调用安全。
        if (pendingLocalNotices.isNotEmpty()) {
            messages.addAllUnique(elements = pendingLocalNotices)
        }
        // 失败气泡 / 本地广播按时间归位：它们产生于某一时刻，若服务端之后又来了新消息，
        // replaceAllUnique 重建列表会把本地项甩到最末尾（表现就是"红叹号 / 广播一直飘在最下方"）。
        // 按 (createdAt, id) 稳定排序，使其回到对应的时间点，不再乱飘。
        //
        // 时间戳为 0（旧数据 / 任何漏填时刻的本地气泡）一律按「最新」排到末尾，绝不能当「最旧」排到头部：
        // messages 是「旧→新」升序，排到头部就等于渲染在屏幕最上方 —— 这正是
        // 「图片刚发出去就跑到会话最顶部、要重新进几次才被矫正回来」的直接成因。
        messages.sortWith(
            compareBy({ if (it.createdAt <= 0L) Long.MAX_VALUE else it.createdAt }, { it.id })
        )
        // 窗口是否已与真正的最新消息脱钩（上滑超过窗口上限、底部被丢弃过）。
        // 所有滑动路径最后都会走 syncMessages，这里统一镜像一次，贴底判定才不会出现「假贴底」。
        windowDetached.value = adapter.tailDetached
    }

    /**
     * 把「渲染下标」换算成消息 id，作为视口锚点。
     *
     * messages 是「旧→新」升序，渲染时 asReversed() 成「新→旧」，所以渲染下标 d 对应的
     * 是 messages[size-1-d]。用 id 当锚点而不是下标：滑动补页会同时发生
     * 「顶端插入 + 底端裁剪」，净位移既不等于插入数也不等于裁剪数，任何算术补偿都会跳屏。
     */
    fun anchorIdAt(renderIndex: Int): Long =
        messages.getOrNull(messages.size - 1 - renderIndex)?.id ?: 0L

    /** 重建列表后按 id 把视口钉回原来那条消息；找不到（被裁剪/删除）时退回原下标。返回滚动任务。 */
    fun restoreViewport(anchorId: Long, fallbackRenderIndex: Int, offset: Int): kotlinx.coroutines.Job {
        val pos = if (anchorId != 0L) messages.indexOfFirst { it.id == anchorId } else -1
        val target = if (pos >= 0) messages.size - 1 - pos else fallbackRenderIndex.coerceAtLeast(0)
        return scope.launch { runCatching { listState.scrollToItem(target, offset) } }
    }

    // 服务端权威增量游标：只取「已确认落库的服务端消息」的最大 id，永不读取混有本地乐观项（现为负 id）的
    // UI 列表 messages，从根上杜绝 afterId 被本地乐观消息 id 撑大导致的增量拉取返回空、会话内收不到新消息、
    // 必须退出重进才恢复的死锁。adapter 只装服务端数据，天然是干净的游标来源。
    fun serverCursor(): Long = adapter.lastMessageId.coerceAtLeast(0L)

    // 进入对话时，把"正在后台上传（Application 级、已持久化）的媒体"恢复成占位气泡：
    // 切出对话/退出软件再回来、以及 syncMessages 按 store 重建列表时，上传占位都不会消失，且后台会续传。
    LaunchedEffect(friendId) {
        pendingUploadPlaceholders.clear()
        pendingLocalNotices.clear()
        val pending = try {
            com.aurora.chat.data.upload.PendingUploadStore.all(ctx)
                .filter { it.friendId == friendId && it.status != com.aurora.chat.data.upload.PendingUpload.STATUS_DONE }
        } catch (_: Exception) { emptyList() }
        for (item in pending) {
            val mediaType = when {
                item.mimeType.startsWith("video/") -> "video"
                item.mimeType.startsWith("audio/") -> "voice"
                item.mimeType.equals("image/gif", ignoreCase = true) || item.mimeType.contains("gif", ignoreCase = true) -> "gif"
                item.mimeType.startsWith("image/") -> "image"
                else -> "file"
            }
            val ph = ChatMsg.create(
                serverId = item.id, text = "", isMine = true, fromUserId = currentUserId,
                isNew = true, mediaType = mediaType, mediaUrl = "", isUploading = true,
                flashDuration = item.flashDuration,
                mediaWidth = if (mediaType == "voice") item.voiceDuration else 0,
                // 本地待发文件还在磁盘上：直接拿它当预览源，重进会话也不用等网络就能看到缩略图
                localPreviewUri = if (mediaType == "image" || mediaType == "gif" || mediaType == "video") {
                    "file://${item.localPath}"
                } else ""
            )
            pendingUploadPlaceholders.add(ph)
            uploadStageMap[item.id] = if (item.status == com.aurora.chat.data.upload.PendingUpload.STATUS_COMPRESSING) "compressing" else "uploading"
            uploadProgressMap[item.id] = 0f
            uploadFileNameMap[item.id] = item.fileName
            if (mediaType == "video") uploadRatioMap[item.id] = 16f / 9f
        }
        // 立即并入当前消息列表（后续 syncMessages 也会自动带上）
        messages.addAllUnique(elements = pendingUploadPlaceholders)
    }

    // ==================== 消息修改（长按 → 修改：编辑思考部分与实际输出，保存后写回本地） ====================
    // 声明在 onConfirmReply 之前，以便确认发言 / 直接发送两个入口都能读取 editTargetMsgId（补回被编辑消息到上下文）
    val showEditDialogState = remember { mutableStateOf(false) }
    var showEditDialog by showEditDialogState
    val editTargetMsgIdState = remember { mutableStateOf(0L) }
    var editTargetMsgId by editTargetMsgIdState
    val editReasoningTextState = remember { mutableStateOf("") }
    var editReasoningText by editReasoningTextState
    val editOutputTextState = remember { mutableStateOf("") }
    var editOutputText by editOutputTextState
    // 「编辑自己最后一条消息 → 重置并重新发送」的确认弹窗状态（仅 AI 对话、且是本人消息、且是最后一条时触发）
    val resendNewChatConfirmState = remember { mutableStateOf(false) }
    var resendNewChatConfirm by resendNewChatConfirmState
    val resendEditMsgIdState = remember { mutableStateOf(0L) }
    var resendEditMsgId by resendEditMsgIdState
    val resendEditTextState = remember { mutableStateOf("") }
    var resendEditText by resendEditTextState
    // 开发者广播（仅 ID=1 可见）：发送弹窗 / 长按管理弹窗状态
    val showBroadcastSendDialogState = remember { mutableStateOf(false) }
    var showBroadcastSendDialog by showBroadcastSendDialogState
    val broadcastManageMsgIdState = remember { mutableStateOf(0L) }
    var broadcastManageMsgId by broadcastManageMsgIdState
    val broadcastManageTaskIdState = remember { mutableStateOf(0L) }
    var broadcastManageTaskId by broadcastManageTaskIdState
    // AI 对话发送附件时的过渡提示文案（非 null 时渲染全屏遮罩，大文件拷入工作区期间告知用户"正在处理"）
    val aiFileSendingTipState = remember { mutableStateOf<String?>(null) }
    var aiFileSendingTip by aiFileSendingTipState
    // 订单详情：点击通知卡片「查看订单」后弹出
    val viewOrderIdState = remember { mutableStateOf<Long?>(null) }
    var viewOrderId by viewOrderIdState

    // AI 访问模式 + 工具审批门控。必须在「发送/确认发言」等用到 toolApprovalHook 的 lambda 之前声明。
    val agentAccessModeState = remember { mutableStateOf(AiChatManager.getAccessMode(ctx, currentUserId)) }
    var agentAccessMode by agentAccessModeState // root / full / manual
    // AI 语义模式：ask=仅对话（无工具权限）/ craft=完整工具能力（默认）/ plan=先规划再执行。选中即持久化；AI 用 set_agent_mode 切换后也会刷新。
    val agentModeState = remember { mutableStateOf(AiChatManager.getAIMode(ctx, currentUserId)) }
    var agentMode by agentModeState // ask / craft / plan
    // 构建按钮复用：持有 InputBar 的 onSend 闭包，供方案卡片「构建」以用户身份发送并启动 AI（行为零变化）
    val sendUserTurnState = mutableStateOf<() -> Unit>({})
    var sendUserTurn by sendUserTurnState
    // 「继续处理任务」一次性标志：true = 本次发送属于"从断点续写"。由发送链路消费一次后即清除，
    // 只用于决定是否在 system 提示词里注入 AiChatManager.RESUME_TASK_PROMPT（绝不写进用户可见文本）。
    val resumeTaskPendingState = remember { mutableStateOf(false) }
    var resumeTaskPending by resumeTaskPendingState
    val showThinkingLevelMenuState = remember { mutableStateOf(false) }
    var showThinkingLevelMenu by showThinkingLevelMenuState // 「思考深度」底部菜单
    val thinkingLevelState = remember(currentUserId) { mutableStateOf(AiChatManager.getThinkingLevel(ctx, currentUserId)) }
    var thinkingLevel by thinkingLevelState
    // AI 技能(多选):off=标准 / multi_agent=多 AI / reverse=逆向(可叠加)
    // 集合内多个模式叠加生效,选中即持久化。极速与记忆增强已是独立开关,长任务/深度研究已不是模式。
    val execModesState = remember { mutableStateOf(AiChatManager.getExecModes(ctx, currentUserId)) }
    var execModes by execModesState
    val showWorkspaceFilesState = remember { mutableStateOf(false) }
    var showWorkspaceFiles by showWorkspaceFilesState // 「工作区文件」全屏面板(右侧滑入)
    val workspaceLocateNameState = remember { mutableStateOf<String?>(null) }
    var workspaceLocateName by workspaceLocateNameState // 打开工作区后要定位的文件引用名
    val workspaceLocateOpenState = remember { mutableStateOf(false) }
    var workspaceLocateOpen by workspaceLocateOpenState // true=定位后自动打开编辑器;false=仅定位到目录并高亮
    val showUsageHistoryState = remember { mutableStateOf(false) }
    var showUsageHistory by showUsageHistoryState // 「Token 用量 · 近 30 天」全屏面板(点今日已用进入)
    val showBackupScreenState = remember { mutableStateOf(false) }
    var showBackupScreen by showBackupScreenState // 「备份与恢复」全屏界面(独立,不嵌套在工作区内)
    // 「查看所有改动」全屏界面状态:open 控制显隐,jsonData 保留最后一次打开的数据(退出动画期间仍有内容)
    val aiChangesOpenState = remember { mutableStateOf(false) }
    var aiChangesOpen by aiChangesOpenState
    val aiChangesJsonDataState = remember { mutableStateOf("") }
    var aiChangesJsonData by aiChangesJsonDataState
    // 当前打开的改动所属消息 ID:退回成功后回写 ai_file_changes 时定位消息用
    val aiChangesMsgIdState = remember { mutableStateOf(-1L) }
    var aiChangesMsgId by aiChangesMsgIdState
    // 打开改动详情时的定位目标(删除类产物点击跳转):非空时改动详情滚动并高亮对应被删除文件
    val aiChangesFocusPathState = remember { mutableStateOf("") }
    var aiChangesFocusPath by aiChangesFocusPathState
    // 定位目标是否**直接展开详情**(文件已不在工作区时用:用户要的是那条删除记录,不是列表)
    val aiChangesFocusDetailState = remember { mutableStateOf(false) }
    var aiChangesFocusDetail by aiChangesFocusDetailState
    // 「工具调用」全屏清单:数据 = 点击按钮那条 AI 消息携带的 aiToolStages(工具名/成败/毫秒时间戳)
    val aiToolCallsOpenState = remember { mutableStateOf(false) }
    var aiToolCallsOpen by aiToolCallsOpenState
    val aiToolCallsDataState = remember { mutableStateOf<List<ChatMsg.ToolStageRecord>>(emptyList()) }
    var aiToolCallsData by aiToolCallsDataState
    val modeExplainDialogState = remember { mutableStateOf<String?>(null) }
    var modeExplainDialog by modeExplainDialogState // 待说明的模式（ask/craft/plan），null=不弹
    // 待审批：Pair(工具名, 参数JSON)；审批弹窗据此生成「将做什么」的中文简述
    val pendingToolApprovalState = remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingToolApproval by pendingToolApprovalState
    val pendingToolApprovalDeferred = remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    // 【点外部收起】用户点击弹窗外区域时仅收起弹窗、审批保持挂起（绝不默认判拒绝）。
    // 置位后组合期恢复逻辑跳过，防止弹窗刚收起又被 Bridge 恢复逻辑立刻拉回来（陷入关不掉循环）。
    // 新审批到来 / 审批结束 / 重进对话页时自动重置：新审批重新弹窗，重进后恢复弹窗继续审批。
    val approvalMinimizedState = remember { mutableStateOf(false) }
    var approvalMinimized by approvalMinimizedState
    // 【P0 审批恢复】工具挂起协程跑在 aiGlobalScope（全局作用域，离开对话页不取消），而审批条状态
    // 是页面内 remember，退出对话页即销毁。若不操作直接离开重进，deferred 永远无人完成 → 会话卡死。
    // 这里在组合期检查进程级 Bridge：仍有未完成的待审批请求时，把 (工具名, 参数) 和同一个
    // deferred 恢复到本页审批条，用户重进后即可继续批准/拒绝，任务不再挂死。
    val restoredApproval = com.aurora.chat.AIApprovalBridge.pendingDescription()
    if (restoredApproval != null && pendingToolApproval == null && !approvalMinimized) {
        pendingToolApproval = restoredApproval
        pendingToolApprovalDeferred.value = com.aurora.chat.AIApprovalBridge.activeDeferred()
    }
    // 【体验优化 P0-1】免审批信任窗口（毫秒时间戳）：用户点「信任5分钟」后，手动模式下所有工具直接放行，
    // 避免研究/编码类任务连环弹窗打断心流。到点自动失效，回到逐次审批。
    val approvalTrustUntilState = remember { mutableStateOf(0L) }
    var approvalTrustUntil by approvalTrustUntilState
    // 【体验优化 P0-1】低危只读工具白名单：这些工具无破坏性（只读取/检索），手动模式下自动放行，
    // 免去的弹窗占 AI 工具调用的绝大多数（read_file/list/search 是研究型 AI 主力）。
    val readOnlyToolWhitelist = remember {
        setOf(
            "read_file", "list_files", "stat_file", "find_files", "search_files",
            "search_web", "deep_research", "fetch_webpage", "query_environment",
            "diff_text", "search_chunk", "search_file", "archive_list", "archive_read_file"
        )
    }
    val toolApprovalHook: suspend (String, String) -> Boolean = remember(agentAccessMode, agentMode) {
        { tool, argsJson ->
            // Ask 模式：除「模式切换」外硬性禁用所有工具（不弹审批，直接拒绝；执行端会回喂「需切换模式」的结果）。
            // set_agent_mode 必须放行——否则 Ask 模式一旦进入就永远无法靠 AI 切出，卡死在纯对话状态。
            if (agentMode == AiChatManager.MODE_ASK) {
                if (tool == "set_agent_mode") return@remember true
                if (tool == AiChatManager.TOOL_ASK_USER) return@remember true
                return@remember false
            }
            // root=已 root 管理员模式、full=完全访问：所有操作（含删改类）都直接执行，不再询问用户
            if (agentAccessMode == AiChatManager.ACCESS_ROOT || agentAccessMode == AiChatManager.ACCESS_FULL) return@remember true
            // ask_user 只是把提问面板展示给用户等作答,没有任何破坏性——手动审批模式下也免审批,
            // 否则会出现「批准 AI 向你提问」这种套娃弹窗,体验荒谬。
            if (tool == AiChatManager.TOOL_ASK_USER) return@remember true
            // 【体验优化 P0-1】免审批信任窗口内 → 全部直接放行（用户主动开启，覆盖本轮剩余工具调用）
            if (System.currentTimeMillis() < approvalTrustUntil) return@remember true
            // 【体验优化 P0-1】低危只读工具自动放行：read/search/list 等无破坏性操作不再逐个弹窗
            if (tool in readOnlyToolWhitelist) return@remember true
            // manual=手动审批：所有工具都需审批。
            // 应用在后台时,应用内审批弹窗用户看不到,任务会一直挂起——此时同步发一条
            // 带「批准/拒绝」按钮的系统通知,用户在通知栏即可完成审批,不必回到应用内。
            // 通知与应用内弹窗共用同一个 CompletableDeferred,哪边先批都幂等。
            val def = kotlinx.coroutines.CompletableDeferred<Boolean>()
            pendingToolApprovalDeferred.value = def
            pendingToolApproval = tool to argsJson
            approvalMinimized = false // 新审批到来：重新弹出弹窗，清除上次「点外部收起」标记
            // 【P0 审批恢复】必须把工具名/参数登记进 Bridge，否则重进对话后 pendingDescription() 恒为 null、审批条无法恢复
            val requestId = com.aurora.chat.AIApprovalBridge.begin(def, tool, argsJson)
            if (!com.aurora.chat.AppForegroundTracker.isForeground || !com.aurora.chat.ConversationPageTracker.active) {
                // 应用后台，或用户已离开对话页（应用内弹窗不可见）→ 必须走通知/悬浮卡持续提示，
                // 绝不让审批静默挂起、更不允许跳过审批直接执行。
                NotificationHelper.showToolApprovalNotification(
                    ctx, com.aurora.chat.ui.chat.describeToolCall(tool, argsJson), requestId
                )
            }
            val approved = try {
                def.await()
            } finally {
                com.aurora.chat.AIApprovalBridge.clear(def)
                pendingToolApproval = null
                pendingToolApprovalDeferred.value = null
                approvalMinimized = false
                NotificationHelper.cancelToolApprovalNotification(ctx)
            }
            approved
        }
    }

    // 前后台追踪 + 审批补发：审批弹窗还挂着用户就退到后台 → 补发一条带「批准/拒绝」按钮的
    // 系统通知，让用户在通知栏直接审批，任务不至于一直挂死。与挂起点发的是同一条通知（同 ID 覆盖）。
    val approvalLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(approvalLifecycleOwner) {
        // 进入对话页：标记页面可见，审批走应用内弹窗；离开页面（组合销毁）后标记不可见，
        // 挂起点据此改用通知/悬浮卡提示，保证用户不在对话内时审批请求始终可见、必须明确批准。
        com.aurora.chat.ConversationPageTracker.active = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    com.aurora.chat.AppForegroundTracker.isForeground = true
                    // 回到前台:应用内审批弹窗可见时,悬浮审批卡/通知若有残留立即撤下,绝不双重弹窗;
                    // 但「点外部已收起弹窗、审批仍挂起」时,通知/悬浮卡就是唯一审批入口,必须保留,
                    // 否则审批会变成无提示的静默挂起。
                    if (pendingToolApprovalDeferred.value?.isActive == true) {
                        com.aurora.chat.AIApprovalOverlay.dismiss()
                        NotificationHelper.cancelToolApprovalNotification(ctx)
                    } else if (!com.aurora.chat.AIApprovalBridge.hasActive()) {
                        // 无任何未决审批:清理可能残留的悬浮卡/通知
                        com.aurora.chat.AIApprovalOverlay.dismiss()
                        NotificationHelper.cancelToolApprovalNotification(ctx)
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    com.aurora.chat.AppForegroundTracker.isForeground = false
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    // 应用退到后台:只要还有未决审批(无论弹窗可见还是已被点外部收起)就补发通知+悬浮卡,
                    // 保证后台也有审批入口,绝不静默挂起。描述优先取页面状态,已收起时取 Bridge。
                    if (com.aurora.chat.AIApprovalBridge.hasActive() &&
                        !com.aurora.chat.AppForegroundTracker.isForeground
                    ) {
                        val requestId = com.aurora.chat.AIApprovalBridge.activeRequestId()
                        val desc = pendingToolApproval?.let {
                            com.aurora.chat.ui.chat.describeToolCall(it.first, it.second)
                        } ?: com.aurora.chat.AIApprovalBridge.pendingDescription()?.let {
                            com.aurora.chat.ui.chat.describeToolCall(it.first, it.second)
                        } ?: return@LifecycleEventObserver
                        NotificationHelper.showToolApprovalNotification(ctx, desc, requestId ?: -1L)
                    }
                }
                else -> {}
            }
        }
        approvalLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            approvalLifecycleOwner.lifecycle.removeObserver(observer)
            // 离开对话页:标记页面不可见,后续挂起的审批改走通知/悬浮卡,审批不会被跳过也不会静默
            com.aurora.chat.ConversationPageTracker.active = false
        }
    }

    /**
     * 「等待模型响应」气泡的唯一收口口。
     *
     * `aiWaiting` 是纯运行时标志（不落盘）：任何一条路径把它置 true 之后没再复位，
     * 渲染层就会一直显示流光「等待模型响应」并每 500ms 累加秒数，且永远不会自愈
     * （退出再进入会话才消失，因为那时是从磁盘重建、该标志为 false）。
     *
     * 现实中会造成「两条气泡同时等待、其中一条永久残留」的入口不止一处：
     *  ① 确认发言 / 编辑重发：与在途回复并存时会再插一条占位，旧占位只按 `text == AI_THINKING`
     *     清理，开了「显示思考过程」时旧占位文本为空串 → 清不掉，永久残留；
     *  ② 内联工具循环按**索引**（aiMsgIdx）写回：插入文件卡片/工具状态后索引一旦漂移，
     *     真正的占位再也没人写，收尾也只写到最后一条；
     *  ③ aiReplyState 的补建分支（idx < 0）会凭空再造一条等待态气泡，旧的那条无人收口。
     *
     * 因此：**每次新建占位气泡**、以及**每轮回复收尾**都调用本函数。
     * 除 [keepId] 外，所有仍处于等待态的气泡一律退出等待（清空等待文案与进度提示）。
     * 只改运行时字段，不动已生成的正文，绝不误删内容。
     */
    fun closeStaleAiWaiting(keepId: Long = 0L) {
        for (i in messages.indices) {
            val m = messages[i] as? ChatMsg ?: continue
            if (m.isMine || m.fromUserId != AI_CHAT_ID) continue
            if (keepId != 0L && m.id == keepId) continue
            if (!m.aiWaiting) continue
            val blankPlaceholder = m.text.isBlank() || m.text == AI_THINKING
            messages[i] = m.copy(
                text = if (blankPlaceholder) "" else m.text,
                aiWaiting = false,
                aiProgressHint = ""
            )
        }
    }

    // 确认发言：用户点击"确认发言"按钮后触发 AI 回复（定义在 messages 之后，避免前向引用）
    val onConfirmReply: () -> Unit = {
        // 先记录是否有一条"待确认"的用户消息（来自上一次发送），再清除待确认标记
        val hadPending = confirmSpeechPending
        confirmSpeechPending = false
        // 修复：确认发言改跑全局作用域（与普通发送一致）。原实现挂在组合作用域 scope 上，
        // 用户确认后立刻离开对话页（上滑关小窗/切会话）协程即被取消：中途任务被掐死、
        // AITaskGuard.exit 不执行 → 残留「运行中」标记，重进误报「上次任务被中断」。
        // 改为 aiGlobalScope 后离开对话不取消，收尾正常执行、标记正常清理。
        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // 【禁止同一会话并发回复】本会话已有回复在跑时（按钮虽已禁用，快速双击 / 无障碍触发仍可能穿透），
            // 再走一遍本链路只会多插一条「等待模型响应」占位、并起第二个流；旧占位在开启
            // 「显示思考过程」时文本为空串、清不掉 → 就变成「两个等待、其中一个永远原地点数」。
            // 编辑重发链路会先 cancel 并把 aiWriting 复位后再调用这里，因此不会被误挡。
            if (aiWriting.value && aiWritingConvId == AiConversationSession.activeConvId) return@launch
            // 与「普通发送」保持一致的就绪状态：解除可能残留的停止标记，并把发送按钮切为「停止」，
            // 让下面的占位消息以 aiWaiting=true 进入「等待模型响应」空窗（重发/确认发言均走此入口）。
            aiStopRequested = false
            // 上一轮若停在思考中途，思考标志会残留 → 新气泡一出现就闪「思考中」卡片，这里先复位
            aiThinkingLive.value = false
            aiWriting.value = true
            aiWritingConvId = AiConversationSession.activeConvId
            agentTaskInFlight.value = true
            agentTaskInFlightConvId = AiConversationSession.activeConvId
            // 确认发言所属会话在启动时绑定，后续保存/渲染以此为准，防止切会话后写错文件
            val myKey = AiConversationSession.storageKey()
            //  隔离判断：确认发言必须锚定在"用户反馈"上。
            //   - 输入框有文字 → 作为一条用户消息追加进上下文（用户确实做了反馈）
            //   - 没有输入但有"待确认"用户消息（hadPending）→ 正常基于那条消息回复
            //   - 没有任何新反馈（hadPending=false 且输入框为空）→ 不静默、也不自言自语，
            //     而是标记 syntheticNoFeedback：交给 AI 自己决定——它"知道"用户这次什么都没说，
            //     可以去质问用户、或说点别的，而不是拿自己上一条回复续写。
            var syntheticNoFeedback = false
            // 提前声明："用户无反馈"时的合成用户回合文本及其角色（用于 system 提示与 history 末尾）。
            // 必须在用到它的地方（官方模式 liveEstInput、history 构造）之前声明，避免 Kotlin 局部变量前向引用失败。
            val noFeedbackUserText = "（用户这次没有输入任何内容，也没有做出任何反应，只是又点了一次确认发言。）"
            val pendingUserText = inputText.trim()
            if (pendingUserText.isNotBlank()) {
                // 输入框有文字→作为一条用户消息追加进上下文（用户确实做了反馈）
                messages.add(ChatMsg.create(text = pendingUserText, isMine = true, fromUserId = currentUserId, isNew = true))
                inputText = ""
                ctx.getSharedPreferences("aurora_drafts", Context.MODE_PRIVATE)
                    .edit().remove(draftKey).apply()
                // 会话自动命名:标题还是「新对话」时,用首条消息异步生成简短标题(文件级函数,不依赖 aiConvs 状态)
                aiGlobalScope.launch { AiChatManager.autoTitleAiConversation(ctx, currentUserId, pendingUserText) }
            } else if (!hadPending) {
                syntheticNoFeedback = true
            }
            // 收回旧的思考过程：清理未完成的"思考中"占位气泡，
            // 并收起已展开的思考内容，避免上一轮的思考过程残留干扰
            val pendingThinking = messages.filter { it.text == AI_THINKING }
            if (pendingThinking.isNotEmpty()) messages.removeAll(pendingThinking)
            aiStreamMsgId.value = 0L
            // P3:统一走 AiChatManager.buildChatContext(按执行模式/思考深度自适应条数预算+超长单条折叠),
            // 取代旧硬截断 take(1)+takeLast(39)——旧逻辑只压条数不压单条,长工具结果/长回复仍会把上下文撑爆
            // 排除尾部「实时进度卡」:它是纯 UI 状态(与原始方案卡内容重复)，喂给模型只会浪费 token 并扰乱上下文
            val chatCandidates = messages.filter {
                !it.isSystemNotice && it.text.isNotBlank() && it.text != AI_THINKING && !it.text.startsWith(PLAN_LIVE_TAG)
            }
            // 重量级计算卸载到 Default 线程：超大任务后 history 极大，主线程同步跑会把 UI 卡死（发消息无响应）
            val chatContext = withContext(kotlinx.coroutines.Dispatchers.Default) {
                AiChatManager.buildChatContext(chatCandidates, editTargetMsgId, ctx, currentUserId)
            }
            val mode = AiChatManager.getMode(ctx, currentUserId)
            val baseUsed: Long
            var poolAtStart = 0L
            // 付费 API 实时扣费参数：发送前快照余额与倍率，流式中估算成本，不足即掐断
            var paidInPrice = 0.0
            var paidOutPrice = 0.0
            var paidApiInsufficientTip = ""
            // ── system 提示提前到这里构建 ──
            // 它有两个用途：① 最终请求的 system 内容；② **输入 token 估算的一部分**。
            // 历史教训：旧口径只累加聊天历史文本，漏掉了占比 90% 以上的 system 提示与 tools schema，
            // 中断时「输入」比真实值低一个数量级（用户实测"估算值和真实消耗毫无关系"）。
            // 同一份字符串两用，口径绝不会漂移。
            val systemText = buildString {
                // 「节省 Token / 极致节省」模式已移除：不再注入对应指令。
                // 性格/自定义设定（CustomTrait）仅当「开启性格调试」为 ON 时才注入并生效；关闭时忽略。
                if (AiChatManager.getPersonalityDebug(ctx, currentUserId)) {
                    append(AiChatManager.buildPersonaSystemPrompt(AiChatManager.getTraits(ctx, currentUserId), AiChatManager.getCustomTraits(ctx, currentUserId)))
                    if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) append("\n\n").append(AiChatManager.PERSONA_SWITCH_RELEASE)
                }
                // 回复模板：与主发送链路同口径，性格在前、模板在后，两条链路的 system 逐字节一致。
                val templateInject = AiReplyTemplateStore.activeInject(ctx, currentUserId)
                if (templateInject.isNotEmpty()) {
                    if (length > 0) append("\n\n")
                    append(templateInject)
                }
            }
            // 系统提示注入已清理：system 仅保留性格调试内容。
            val fastOn = AiChatManager.isFastMode(ctx, currentUserId)
            // 性格切换声明是一次性开关：systemText 里已读过它，这里必须立刻复位，
            // 否则同一份「已切换性格」声明会在后续每一轮重复注入。
            if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) AiChatManager.setPersonaSwitch(ctx, currentUserId, false)
            // 【前缀静态化】system 只放跨回合稳定内容（见 [buildAISystemPrompt] 的硬规矩）。
            // 每回合动态内容（当前时间/记忆检索/轮数提示/方案执行约束）走末尾 user 提醒注入，
            // 这是让上游前缀缓存跨轮命中的关键。
            val systemForHistory = systemText
            // 工具是否对模型可见（与原发送口径严格一致）：Ask 模式只注册 set_agent_mode，其余模式声明沙盒工具。
            val enableAITools = AiChatManager.getAIMode(ctx, currentUserId) != AiChatManager.MODE_ASK
            // 实时输入估算基线（首轮）：用初始 chatContext 算一次；循环内每轮会按真实 history 重算（见 while 循环顶部）。
            // 若首轮即被中断且任何上游/服务端都用不上，estFallback 会改用最终 history 重算值，避免数量级误差。
            var liveEstInput = withContext(kotlinx.coroutines.Dispatchers.Default) {
                AiChatManager.estimateHistoryInputTokens(
                    ctx, currentUserId, systemForHistory,
                    chatContext.map { it.text },
                    enableAITools, fastOn,
                    lastUserText = chatContext.lastOrNull { it.isMine }?.text
                )
            }.let { if (syntheticNoFeedback) it + AiChatManager.estimateTokens(noFeedbackUserText).toLong() else it }
            if (mode == "official") {
                // 发送前强制拉取服务器最新余额，避免因本地缓存过期导致「余额=0 时还能发一次」
                AiChatManager.syncServerLimit(ctx, currentUserId)
                val (used, remaining) = AiChatManager.getOfficialUsage(ctx, currentUserId)
                aiUsageState.value = Pair(used, remaining)
                baseUsed = used
                poolAtStart = remaining
                // 余额判定以「服务器真实 token_balance」为准：余额=0 时弹卡片并拦截发送，任何人无豁免。
                val rawBal = AiChatManager.getServerTokenBalance(ctx, currentUserId)
                if (rawBal <= 0L) {
                    onTokenExhausted()
                    return@launch
                }
            } else if (mode == "paid_api") {
                // 付费 API：发送前强制刷新余额与倍率；流式输出时按倍率实时估算成本，余额不足立即掐断，
                // 避免「输出完才扣费、余额不足扣不掉」——先掐断、已生成内容照常保留。
                AiChatManager.syncServerLimit(ctx, currentUserId)
                val (used, remaining) = AiChatManager.getOfficialUsage(ctx, currentUserId)
                aiUsageState.value = Pair(used, remaining)
                baseUsed = used
                poolAtStart = remaining
                val rawBal = AiChatManager.getServerTokenBalance(ctx, currentUserId)
                if (rawBal <= 0L) {
                    onTokenExhausted()
                    return@launch
                }
                if (rawBal > 0L && rawBal < Long.MAX_VALUE) poolAtStart = rawBal
                try { AiChatManager.refreshPaidApiPrice(ctx, currentUserId) } catch (_: Exception) {}
                val (inP, outP) = AiChatManager.getPaidApiPrice(ctx, currentUserId)
                paidInPrice = inP; paidOutPrice = outP
            } else {
                baseUsed = aiUsageState.value.first
            }
            // 方案进行中(存在未交付的 [[PLAN_CARD]]):解除工具轮数硬墙,采用大厂式「完成即停」,避免多步方案被默认 6 轮卡死
            val planActive = messages.any { it is ChatMsg && it.text.contains("[[PLAN_CARD]]") }
                && !messages.any { it is ChatMsg && it.text.contains(AITaskCompletion.DELIVER_OPEN) }
            // 【二次调用】只要会话里出现过方案卡，就允许 AI 继续用 update_plan_step 更新它。
            // 与 planActive 的区别：planActive 一旦**交付过**就变 false（那是给轮数限制用的），
            // 但用户说「继续做完未完成的步骤」时，AI 必须仍能再次逐条勾选、让任务流程卡重新显示进度。
            val planCardExists = messages.any {
                it is ChatMsg && it.text.contains("[[PLAN_CARD]]") && !it.text.startsWith(PLAN_LIVE_TAG)
            }
            // system 提示（systemText / fastOn / systemForHistory）已在上面提前构建：
            // 它同时是请求内容与输入 token 估算的一部分，两处必须用同一份字符串。
            // 无新反馈时，在末尾补一条"用户侧"回合，明确用户什么都没说，
            // 使模型以 assistant 身份回应（质问/闲聊），而不是续写自己上一条回复
            // （noFeedbackUserText 已在隔离判断处提前声明，此处只取对应角色）
            // 无新反馈时，在末尾补一条"用户侧"回合，明确用户什么都没说，
            // 使模型以 assistant 身份回应（质问/闲聊），而不是续写自己上一条回复
            // （noFeedbackUserText 已在隔离判断处提前声明，此处只取对应角色）
            val noFeedbackRole = "user"
            // 用 mutableListOf 累加，避免多行 + 链类型推断陷阱（之前报 unaryPlus）
            val history = mutableListOf<Pair<String, String>>()
            if (systemForHistory.isNotBlank()) history.add("system" to systemForHistory)
            // 与主发送链路同口径：AI 侧历史先清洗再喂模型（剥交付块/工具状态/DSML 哨兵）。
            // 不清洗的话，历史里上一轮的「目标/已完成/产物/验证/遗留」原文会被模型照抄成格式模板。
            chatContext.map {
                if (it.isMine) "user" to it.text
                else "assistant" to AiChatManager.sanitizeTextForModel(it.text)
            }
                .filter { it.second.isNotBlank() }
                .forEach { history.add(it) }
            // 系统提示注入已清理：system 里只保留跨回合稳定的「性格调试」内容，
            // 不再追加每回合动态提醒。
            if (syntheticNoFeedback) history.add(noFeedbackRole to noFeedbackUserText)
            val aiMsgId = System.currentTimeMillis() * 10000L + (0..9999).random()
            aiStreamMsgId.value = aiMsgId
            val initText = ""  // 占位文本恒空：思考阶段不上屏，等正文真正开始（或整段输出完成）才由渲染层放出来
            messages.add(ChatMsg.create(
                serverId = aiMsgId, text = initText, isMine = false, fromUserId = AI_CHAT_ID,
                senderName = AiNameState.name ?: "AI", isNew = true, createdAt = System.currentTimeMillis() / 1000, aiMode = mode
            ).copy(aiWaiting = true))
            // 新占位是唯一合法的等待者：把此前任何残留的「等待模型响应」气泡立即收口
            // （确认发言可被重复触发、也可与在途回复并存，这是「两个等待、其中一个永远不走」的主入口）
            closeStaleAiWaiting(aiMsgId)
            // 工具状态不再锚定到任何气泡：它作为独立消息由 addToolStatus 插入，天然站在它该站的位置
            // 流式期间索引稳定，缓存一次避免每个 token 都做 O(n) indexOfFirst
            var aiMsgIdx = messages.size - 1
            // 占位入列后立即落盘一次：即使随后切走会话，磁盘也有该条待回复占位，切回可恢复
            val pendingSnap = messages.filter { !it.isSystemNotice }.toList()
            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, pendingSnap) } catch (_: Exception) {} }
            // 滚动跟随按时间合并，避免每 token 新建协程
            var lastScrollMs = 0L
            /**
             * 用户此刻是否正在亲手滑列表。
             *
             * 为什么需要它(洞 A 修复):`autoFollow` 是由 `snapshotFlow` 推导的,而 snapshotFlow 的发射
             * 要等 snapshot apply 之后的下一帧 —— 从"用户手指开始上滑"到 `autoFollow` 变 false 之间有
             * 1~2 帧(16~32ms)的空档。这期间若正好一个节流帧到期并 programmaticScroll(0),用户就被拽回底部。
             * `isScrollInProgress` 是 ScrollableState 上的即时量,手指一按下就为真,能把空档压到接近 0。
             *
             * 为什么必须排除自身程序化滚动:`scrollToItem` 进行期间它同样为真,不排除会把自己人当成
             * 用户手势、把 autoFollow 永久摁死(表现:AI 输出时再也不跟随)。`programmaticScrolling` 正是这个护栏。
             */
            fun userIsDraggingList(): Boolean =
                listState.isScrollInProgress && !programmaticScrolling

            fun maybeScrollToBottom() {
                // 用户正在滑 → 立即停止跟随,不等 snapshotFlow 下一帧才翻转
                if (userIsDraggingList()) { autoFollow = false; return }
                if (autoFollow) {
                    val now = System.currentTimeMillis()
                    if (now - lastScrollMs > 80) {
                        lastScrollMs = now
                        programmaticScroll(0)
                    }
                }
            }
            try { listState.scrollToItem(0) } catch (_: Exception) {}
            // ===== AI 工具执行循环 =====
            // 回复含工具调用时执行并回喂模型继续生成；最多 MAX_AGENT_TOOL_ROUNDS 轮，防死循环。
            var accumulated = ""
            // 【性能】流式 token 估算器（增量）：只统计每次新增的字符，避免每个 delta 都重扫整段输出
            val streamEst = StreamingTokenEstimator()
            // 本轮（agent 循环）是否执行过工具：用于收尾时避免用累积全文覆盖多条已独立成文的工具气泡
            var loopUsedTools = false
            // 上一轮是否执行了工具：为 true 时下一轮正文应另开一条新气泡，从而工具调用/卡片就近显示而非堆到最底部
            var pendingToolRound = false
            var lastRawFull = ""
            var loopRounds = 0
            // 静默空轮计数（只有思考、没有正文也没有工具调用）：与 runAIChatLoop 同口径做有界补催。
            // 旧写法对内联空轮直接 continue，无补催无上限 → 模型「纸上写文件」时界面只剩一个
            // 「思考过程」折叠条、任务却还在无声空转（用户实测：一到创建文件就卡壳）。
            var emptyRounds = 0
            // 工具调用被截断/解析失败的次数：定向引导「分块写入」，最多 2 次，防死循环
            var brokenToolRounds = 0
            var roundDone = false
            var loopFailed = false
            var loopManualStop = false
            // 每轮进入请求前，按当前真实 history 重算的输入 token 估算（见 while 循环顶部）。
            // 手动中断时，被截断最后一轮的输入未由上游回报，用它补回，与已完成轮的 realIn 互不重叠、不重复计数。
            var prevRoundHistoryInput = 0L
            var lastUsage = 0L
            var lastThinking = 0L
            var lastTotal = 0L
            // 多段思考：跨轮累积（reasoning_content）。把思考按发生时间点内联嵌入正文流，
            // 使思考块出现在它前面的输出之后（而非强制挤到顶部）。reasoningText 同时保留（供复制/兼容）。
            var reasoningSegments = ""
            var roundReasoning = ""
            /** 显示思考是否开启 + 当前消息索引合法时，把指定思考文本写到气泡 */
            fun writeReasoning(r: String) {
                if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                    val m = messages[aiMsgIdx] as ChatMsg
                    messages[aiMsgIdx] = m.copy(
                        reasoningText = if (AiChatManager.getShowReasoning(ctx, currentUserId)) r else m.reasoningText,
                        aiWaiting = false
                    )
                }
                scheduleAiDebouncedSave()
            }
            // 新一轮 AI 任务开始:清除上一轮遗留的「跳过审查」请求(跳过标志整轮有效,跨轮不残留)
            AiChatManager.resetReviewSkip()
            // 长任务哨兵（内联循环这条路径）：整段工具循环期间标记「AI 正在处理」。
            // 正常收尾会清掉；只有进程被系统强行掐断才会留下标记，下次启动据此提示用户。
            com.aurora.chat.AITaskGuard.enter(ctx, "AI 工具任务")
            // 【崩溃可追溯】开一份追加式执行日志：工具调用与文件改动发生即落盘，
            // 即使进程随后被 SIGKILL（AS 停止运行 / 后台清理 / 强行停止），下次启动也能把记录折回来。
            // 正常收尾会在下方 AITaskGuard.exit 处调用 finish() 删除日志，不留垃圾。
            AIJournal.bind(
                ctx = ctx, userId = currentUserId, convKey = myKey,
                // 用 aiStreamMsgId 而不是 messages[aiMsgIdx]：前者在占位气泡建立时（:2002）就已是准确 id，
                // 而 aiMsgIdx 在极端情况下可能还是 -1，会导致日志没有归属、恢复时无处可折。
                msgId = aiStreamMsgId.value, desc = "AI 工具任务"
            )
            // 多 AI 强制验收跟踪(主发送路径走本内联循环,与 runAIChatLoop 的编排层兜底同逻辑);
            // 轮数上限用 maxToolRoundsFor(极速=0/多 AI=不限,其余默认上限),不再硬编码 MAX_AGENT_TOOL_ROUNDS
            val inlineMaxRounds = AiChatManager.maxToolRoundsFor(ctx, currentUserId, planActive)
            // ── 模型日志：本轮请求的起点（模型/模式/工具上限）── 长任务出问题时从这一行往下看即可复盘
            com.aurora.chat.ModelLogger.info(ctx, "REQ", "开始 | 模型=${AiChatManager.getMode(ctx, currentUserId)} 多AI=${AiChatManager.execActive(ctx, currentUserId, AiChatManager.EXEC_MULTI_AGENT)} 方案中=$planActive 工具上限=${if (inlineMaxRounds == Int.MAX_VALUE) "不限" else inlineMaxRounds}")
            var inlineReviewCalled = false
            var inlineReviewNudged = false
            // agent_review 已被调用次数:配合 MAX_AGENT_REVIEW_CALLS 做编排层硬上限(防验收死循环)
            var inlineReviewCount = 0
            val inlineMutated = mutableSetOf<String>()
            // ===== 任务守护状态机(NORMAL → WRAP_UP → DELIVERED)=====
            var phase = AITaskCompletion.AITaskPhase.NORMAL
            var wrapUpRounds = 0
            var nudgeCount = 0
            // 「请示式收尾」被驳回的次数（模型问「要不要我继续」→ 系统替用户回答「继续」）。
            // 独立预算：它和「防提前收工」成因不同（一个是不交付，一个是把决定权推回用户），
            // 共享额度会让多步任务在两种补救之间互相饿死。
            var permissionNudgeCount = 0
            // 方案步骤未完成时的强制续做预算:独立于「防提前收工」的 nudgeCount,
            // 避免被共享的 2 次 nudge 上限饿死;模型吐出交付块后仍能持续推动它做完剩余步骤。
            var planNudgeCount = 0
            val planStepNudgeCap = 30
            var continueCount = 0
            var retryCount = 0
            var currentToolChoice: String? = null
            var contractNeeded = AITaskCompletion.needsContract(ctx, currentUserId, 0)
            // 交付「格式规范」是否已注入（见下方首次契约触发处的说明）
            var contractSpecInjected = false
            var inlineToolCount = 0
            var finishReason = ""
            var lastErrMsg = ""
            var inlineRetryPending = false
            // ===== 付费 API 对齐 runAIChatLoop：补齐用量明细 / 文件改动 / 工具计数采集 =====
            // 发送起始时间戳：用于计算端到端「总时长」(覆盖单轮响应 <1s 被取整为 0 而不显示总时长的问题)
            val tSendStart = System.currentTimeMillis()
            // 用量明细(input/output token、缓存命中、上下文长度)：由 streamChat 的 onUsageDetail 回填
            var lastUsageDetail: AiChatManager.AiUsageDetail? = null
            // 工具调用阶段记录(工具名/成败/时间戳)：每条工具执行后追加,用于底部「调用 N 个工具」详情
            val inlineToolStages = mutableListOf<ChatMsg.ToolStageRecord>()
            // 激活文件改动收集器：本循环执行文件类工具时写入,收尾时序列化进消息
            AiChatManager.activeFileChangeRecorder = mutableListOf()
            // 固定工具调用与修改日志：开启时流式期间就把计数/改动实时写回消息并随 debounced 保存落盘
            val fixedToolLog = AIUiPrefs.fixedToolChangeLog(ctx)
            // 取消保护：用户点「停止」时 stop handler 会执行 activeAiJob.cancel()。若取消正好落在
            // 流式/工具调用内部，CancellationException 会一路穿过整个循环，把下面的「一次性收尾」整段跳过，
            // 结果就是停止后 token 明细 / 耗时 / 工具计数 / 文件改动全部写不进消息（用户反馈「中断了就查不到」）。
            // 这里吞掉取消并按「手动停止」收口，让收尾照常执行——与流内 break、与上面的 !roundDone 分支同语义。
            try {
            while (true) {
                // 上一轮执行了工具：本轮正文开一条新气泡（紧跟工具产物与文件卡片），而不是把所有正文堆进同一条旧气泡，
                // 使每条工具调用的状态行与文件卡片都显示在它真正发生的位置附近，而不是集中到整个回答的末尾。
                var freshNewBubble = false
                if (pendingToolRound) {
                    val newId = System.currentTimeMillis() * 10000L + (0..9999).random()
                    messages.add(ChatMsg.create(
                        serverId = newId, text = "", isMine = false, fromUserId = AI_CHAT_ID,
                        senderName = AiNameState.name ?: "AI", isNew = true, createdAt = System.currentTimeMillis() / 1000, aiMode = mode
                    ).copy(
                        aiWaiting = true,
                        // 固定工具日志：新气泡直接继承累计计数/改动，让「调用 N 个工具 / 查看 N 处改动」
                        // 从旧气泡无缝挪到新气泡，而不是先消失、等下一次工具调用才重新出现。
                        aiToolCallCount = if (fixedToolLog) inlineToolCount else 0,
                        aiFileChanges = if (fixedToolLog)
                            AiChatManager.serializeFileChanges(AiChatManager.activeFileChangeRecorder ?: emptyList())
                        else ""
                    ))
                    // 工具轮新建气泡时，上一轮气泡若因写回索引漂移而没被复位，这里统一收口：
                    // 同一时刻只允许一条气泡处于「等待模型响应」
                    closeStaleAiWaiting(newId)
                    aiMsgIdx = messages.size - 1
                    pendingToolRound = false
                    freshNewBubble = true
                    // 【实时判定必须指向新气泡】liveNow = aiWriting && msgId == expandedReasoningMsgId。
                    // 工具轮另开一条气泡后若不同步这个 id，它会一直指向上一条**已完成**的气泡：
                    // 新气泡永远判成"非流式"（思考卡片/交付扫光挂不上），旧气泡反倒被判成流式。
                    aiStreamMsgId.value = newId
                    // 思考段累积器按气泡隔离:上一轮的思考已内联进上一条气泡,若这里不清零,
                    // 渲染端会拿「跨气泡累积段数 > 本气泡内联块数」误判出一段"未内联的实时思考",
                    // 在新气泡末尾重复画出上一轮的思考内容(重复思考过程 bug 的根因)。
                    reasoningSegments = ""
                    maybeScrollToBottom()
                }
                roundDone = false
                val isFirstRound = (loopRounds == 0)
                val prefix = if (isFirstRound || freshNewBubble) "" else accumulated
                val nativeCalls = mutableListOf<AiChatManager.AINativeToolCall>()
                // 实时输入估算：每轮按当前真实 history 重算（含上一轮累积的工具结果），
                // 让「已用」随上下文真实增长，而非卡在第一轮输入。同时记录进入本轮时的 history 输入，
                // 供手动中断时补回被截断最后一轮的输入（与已完成轮 realIn 不重复计数）。
                liveEstInput = AiChatManager.estimateHistoryInputTokens(
                    ctx, currentUserId, systemForHistory, history.map { it.second }, enableAITools, fastOn,
                    lastUserText = history.lastOrNull { it.first == "user" }?.second, history = history
                )
                prevRoundHistoryInput = liveEstInput
                // ── 每一轮请求前都把目标气泡武装成「等待模型响应」──
                // 旧代码只在「新建占位气泡」时置 aiWaiting=true：首轮一定新建 → 稳定；后续轮次只有
                // 上一轮执行过工具（pendingToolRound）才会新建气泡，复用同一条气泡时该标志早被上一轮的
                // writeReasoning/onDelta 清掉 → 这一轮的等待空窗再也不显示。
                // 用户实测原话：「第一次很稳定，后面反复调用时有显示有不显示」——根因就在这里。
                // 现在按轮统一武装：只要本轮还没吐出思考/正文，就稳定处于「等待模型响应」。
                // （渲染侧已改成「叠加」而非「互斥」，所以给已有正文的气泡置位也不会把正文吞掉。）
                if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                    val w0 = messages[aiMsgIdx] as? ChatMsg
                    if (w0 != null && !w0.aiWaiting) messages[aiMsgIdx] = w0.copy(aiWaiting = true)
                }
                AiChatManager.streamChat(
                    ctx = ctx, userId = currentUserId, history = history,
                    // 【停止要真的停得住】此前这条内联链路没传 stopCheck，点「停止」后只能等本轮流自然跑完
                    // （activeAiJob 并不指向本协程，cancel 也打不中），表现就是「点了停止还在继续输出」。
                    // 接上 stopCheck 后由 streamChat 的看门狗在 ~120ms 内收口，且走「流内 break」的正常返回路径 ——
                    // 这点很关键：只有正常返回，下面的一次性收尾才会执行（token 明细/耗时/工具计数才写得进消息）。
                    stopCheck = { aiStopRequested },
                    // 空内容不当致命错误：推理模型(DeepSeek)常整轮只吐思考、content 为空。
                    // 这里交给下方「空轮有界补催 / 工具调用截断引导」定向处理，
                    // 而不是走 streamChat 内部的空响应报错把任务直接中断。
                    treatEmptyAsError = false,
                    onReasoningDelta = { reasoning ->
                        // 思考正在产出 → 思考卡片可以存在（标题「思考中」+ 正文流光）
                        aiThinkingLive.value = true
                        roundReasoning = reasoning
                        // 实时把本轮思考以哨兵嵌进当前正文流末尾：让它紧跟已输出的内容，而非叠到顶部
                        // 【性能】思考文本同样是「累积全文」，且思考量往往比正文还大 —— 写入同样要节流，
                        // 否则每 token 都要把整段累积思考重解析一遍，这是「一大堆思考过程就卡」的直接来源。
                        // 收尾（onDone）会用完整累积思考再写一次兜底，丢帧不丢内容。
                        if (StreamUiThrottle.due(aiMsgId, reasoning.length)) {
                            val live = AiChatManager.appendReasoningSegment(reasoningSegments, reasoning)
                            writeReasoning(live)
                            maybeScrollToBottom()
                        }
                        if (mode == "official" && poolAtStart > 0L && poolAtStart < Long.MAX_VALUE) {
                            val liveUsed = baseUsed + liveEstInput
                            val liveRemaining = (poolAtStart - liveEstInput).coerceAtLeast(0)
                            aiUsageState.value = Pair(liveUsed, liveRemaining)
                        } else if (mode == "paid_api" && poolAtStart > 0L && poolAtStart < Long.MAX_VALUE && (paidInPrice > 0.0 || paidOutPrice > 0.0)) {
                            // 付费 API：思考阶段按「输入*入倍率」估算成本（与后端 chargeTokensFromPrice 口径一致）
                            val cost = kotlin.math.ceil(liveEstInput * paidInPrice).toLong()
                            aiUsageState.value = Pair(baseUsed + cost, (poolAtStart - cost).coerceAtLeast(0))
                        }
                    },
                    onDelta = { cur ->
                        // 正文首字到达 = 本轮思考已结束 → 思考卡片立即整块消失（不留「思考过程 ▶」折叠行）
                        aiThinkingLive.value = false
                        // 【性能】UI 写入节流：cur 是累积全文，丢帧不丢内容（onDone / 收尾还会再写一次完整文本）。
                        // 超长输出时这里是「每 token 全量重解析」的重灾区，钳到 ~20 帧/秒。
                        if (StreamUiThrottle.due(aiMsgId, cur.length)) {
                            // 流式展示时同样去掉工具协议片段，避免气泡闪出 @tool 标记
                            val stripped = AiChatManager.stripToolBlocks(cur)
                            val shown = if (isFirstRound) stripped else (if (prefix.isBlank()) stripped else prefix + "\n\n" + stripped)
                            if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                                val msg = messages[aiMsgIdx] as ChatMsg
                                val keepReasoning = if (AiChatManager.getShowReasoning(ctx, currentUserId)) msg.reasoningText else ""
                                // 流式每帧都要过一次：用 indexOf 快路径版，无标记时零拷贝
                            messages[aiMsgIdx] = msg.copy(text = AiChatManager.stripMultiSplit(shown), reasoningText = keepReasoning, aiWaiting = false)
                            }
                            maybeScrollToBottom()
                        }
                        if (mode == "official" && poolAtStart > 0L && poolAtStart < Long.MAX_VALUE) {
                            // 【性能】增量 token 估算：只统计新增字符，避免每 token 重扫整段输出
                            val inc = liveEstInput + streamEst.estimate(cur)
                            aiUsageState.value = Pair(baseUsed + inc, (poolAtStart - inc).coerceAtLeast(0))
                        } else if (mode == "paid_api" && poolAtStart > 0L && poolAtStart < Long.MAX_VALUE && (paidInPrice > 0.0 || paidOutPrice > 0.0)) {
                            // 付费 API 实时预估：cost = ceil(输入*入倍率) + ceil(已输出*出倍率)，与后端口径一致
                            val outEst = streamEst.estimate(cur).toLong()
                            val cost = kotlin.math.ceil(liveEstInput * paidInPrice).toLong() + kotlin.math.ceil(outEst * paidOutPrice).toLong()
                            val liveRemaining = (poolAtStart - cost).coerceAtLeast(0)
                            aiUsageState.value = Pair(baseUsed + cost, liveRemaining)
                            // 余额不足以支付已生成内容：立即掐断流式输出（不再等输出完才扣费）
                            if (liveRemaining <= 0L && !aiStopRequested) {
                                aiStopRequested = true
                                paidApiInsufficientTip = "\n\n（余额不足，已停止输出）"
                                // 此分支可能落在节流窗口内（本轮尚未写入 UI），所以用 cur 现算一次文本
                                val curText = AiChatManager.stripToolBlocks(cur)
                                val shownNow = if (isFirstRound) curText else (if (prefix.isBlank()) curText else prefix + "\n\n" + curText)
                                accumulated = (if (accumulated.isBlank()) shownNow else accumulated) + paidApiInsufficientTip
                                if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                                    messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(text = accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""), aiWaiting = false)
                                }
                                scheduleAiDebouncedSave()
                                aiWriting.value = false
                                aiWritingConvId = -1L
                                agentTaskInFlight.value = false
                                agentTaskInFlightConvId = -1L
                                onTokenExhausted()
                            }
                        }
                    },
                    onDone = { usage, full, thinkingSeconds, totalSeconds ->
                        // 本轮流式结束（可能紧跟工具执行/下一轮）→ 思考卡片收掉
                        aiThinkingLive.value = false
                        // 实时进度提示（内联循环主路径）：仅任务型会话显示「任务进行中」，普通对话留空以免打扰
                        // 【不再暴露轮次】「第 N 轮」是编排层的内部计量，对用户没有可执行信息，统一说"任务进行中"。
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            val hint = if (phase == AITaskCompletion.AITaskPhase.WRAP_UP) "正在收尾交付"
                                       else if (contractNeeded) "任务进行中"
                                       else ""
                            messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(aiProgressHint = hint)
                        }
                        roundDone = true
                        // 用量跨轮累加(覆盖式只记最后一轮会系统性少扣,长任务按轮数成比例少收)
                        lastUsage += usage
                        lastThinking = thinkingSeconds
                        lastTotal = totalSeconds
                        lastRawFull = full
                        // 提取本轮思考（reasoning_content 在本轮已完整），再清空
                        val thisRoundThinking = roundReasoning
                        reasoningSegments = AiChatManager.appendReasoningSegment(reasoningSegments, roundReasoning)
                        roundReasoning = ""
                        // 去重/消费：从历史显示文本中剥离已执行/待执行的工具块
                        val cleaned = AiChatManager.stripToolBlocks(full)
                        // 内联嵌入本轮思考：思考发生在本轮正文之前，应排在本轮正文前、上一轮输出之后，
                        // 而非一股脑挤到顶部。仅嵌入本轮这一条，避免重复。
                        val showResp = AiChatManager.getShowReasoning(ctx, currentUserId)
                        accumulated = when {
                            showResp && thisRoundThinking.isNotBlank() -> {
                                if (accumulated.isBlank()) {
                                    cleaned.let { c -> if (c.isBlank()) "" else AiChatManager.appendInlineReasoning("", thisRoundThinking) + "\n\n" + c }
                                } else {
                                    // 本轮正文经去重后再追加：模型续写/被催交付时爱把前文重写一遍，
                                    // 直接追加会让同一条消息里同一段话出现两三遍（用户实际反馈）。
                                    appendRoundTextDedup(
                                        AiChatManager.appendInlineReasoning(accumulated, thisRoundThinking), cleaned
                                    )
                                }
                            }
                            isFirstRound -> cleaned
                            cleaned.isBlank() -> accumulated
                            accumulated.isBlank() -> cleaned
                            else -> appendRoundTextDedup(accumulated, cleaned)
                        }
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            val msg = messages[aiMsgIdx] as ChatMsg
                            val keepReasoning = if (showResp) reasoningSegments else msg.reasoningText
                            // 首轮或新开的气泡单独成文：只放本轮内容，避免把更早轮次的文本重复进这条新气泡；
                            // 同气泡续写（极稀有）时才用累积全文。
                            val bubbleText = if (isFirstRound || freshNewBubble) {
                                if (showResp && thisRoundThinking.isNotBlank()) {
                                    if (cleaned.isBlank()) AiChatManager.appendInlineReasoning("", thisRoundThinking)
                                    else AiChatManager.appendInlineReasoning("", thisRoundThinking) + "\n\n" + cleaned
                                } else cleaned
                            } else {
                                accumulated
                            }
                            messages[aiMsgIdx] = msg.copy(text = bubbleText.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""), reasoningText = keepReasoning, aiWaiting = false)
                        }
                        scheduleAiDebouncedSave()
                    },
                    onError = { err ->
                        aiThinkingLive.value = false
                        lastErrMsg = err
                        // 可重试错误(网络/5xx/断流):原地重试,保留已累积进度,不终结任务
                        if (AITaskCompletion.isRetriable(err) && retryCount < AITaskCompletion.MAX_ROUND_RETRIES) {
                            retryCount++
                            inlineRetryPending = true
                            Log.i("AiInline", "内联循环单轮可重试错误,第 $retryCount 次重试")
                            android.widget.Toast.makeText(ctx, "网络波动,正在重试($retryCount)...", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            loopFailed = true
                            // 任务意外中断(不可重试的致命错误)：把后台保活小窗切成故障态
                            // (红底「AI 出现故障，点击回前台」,30 秒后自动关窗),避免任务已死、小窗却一直显示「AI 运行中」的僵尸态。
                            try { com.aurora.chat.AiKeepAliveService.notifyFault(ctx) } catch (_: Exception) {}
                            aiWriting.value = false
                            aiWritingConvId = -1L
                            agentTaskInFlight.value = false
                            agentTaskInFlightConvId = -1L
                            val idx = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) aiMsgIdx else -1
                            // 修复：确认发言路径同样不删消息（与主发送路径一致），保留已流式生成的部分输出，
                            // 并在文本上追加错误提示，避免 API/连接异常时前文整段消失。
                            if (idx >= 0) {
                                val cur = messages[idx] as ChatMsg
                                val curText = cur.text
                                val errText = if (curText.isBlank() || curText == AI_THINKING) "（生成中断：$err）" else curText + "\n\n（生成中断：$err）"
                                messages[idx] = cur.copy(text = errText.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""), aiWaiting = false)
                            }
                            if (err.contains("已被管理员暂停")) {
                                apiSuspendedDialog = true
                            } else {
                                android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    // 额度不足是终态，但**绝不在这里复位按钮状态**：那会让「任务还在跑」的按钮瞬间跳成「发送」，
                    // 而下面 !roundDone 分支又会把本轮当成「用户手动停止」收尾（消息被标成已手动停止 + 挂中断
                    // token 卡）—— 用户实测的「按钮变发送、AI 还在动、右下角状态莫名其妙」正是这条链。
                    // 这里只把本轮标记为失败，交给与 onError 一致的分支收尾，按钮由 finally 统一复位。
                    onTokenInsufficient = {
                        loopFailed = true
                        if (lastErrMsg.isBlank()) lastErrMsg = "额度不足，任务已停止"
                        onTokenExhausted()
                    },
                    nativeToolAccumulator = nativeCalls,
                    enableAITools = agentMode != AiChatManager.MODE_ASK,
                    toolChoice = currentToolChoice,
                    onFinish = { r -> finishReason = r },
                    onUsageDetail = { d -> lastUsageDetail = AiChatManager.accumulateUsage(lastUsageDetail, d) }
                )
                // 可重试错误：原地重试，不终结任务（保留已累积进度与气泡）
                if (inlineRetryPending) { inlineRetryPending = false; continue }
                // 输出被上游 max_tokens 截断(finish_reason=length)：自动从断点续写，最多 MAX_CONTINUE_ROUNDS 次
                if (!loopFailed && !loopManualStop && finishReason == "length" && continueCount < AITaskCompletion.MAX_CONTINUE_ROUNDS) {
                    continueCount++
                    val tail = AiChatManager.stripToolBlocks(lastRawFull).takeLast(200)
                    // 截断点正好落在工具调用里（大文件写入被拦腰砍断）时，泛泛的"继续写完"只会让模型
                    // 再吐一遍同样长的调用、再次被截断。这里直接给分块写入的明确指令。
                    val instr = if (AiChatManager.hasBrokenToolCall(lastRawFull)) {
                        BROKEN_TOOL_CALL_INSTRUCTION
                    } else {
                        AITaskCompletion.continueInstruction(tail)
                    }
                    history.add("user" to instr)
                    if (AiChatManager.hasBrokenToolCall(lastRawFull)) {
                        com.aurora.chat.ModelLogger.warn(ctx, "GUARD", "输出被 max_tokens 截断且断点落在工具调用内 → 改下分块写入指令(第 $continueCount 次续写)")
                    }
                    Log.i("AiInline", "内联循环输出被截断,自动续写(第 $continueCount 次)")
                    com.aurora.chat.ModelLogger.warn(ctx, "ROUND", "输出被上游截断(max_tokens)，自动续写第 $continueCount 次")
                    continue
                }
                if (loopFailed) {
                    com.aurora.chat.ModelLogger.error(ctx, "STOP", "上游/网络失败，循环终止 | 轮次=$loopRounds 错误=${lastErrMsg.take(300)}")
                    break
                }
                // 流未正常结束（onDone 未被调用）：必须区分「用户真的点了停止」与「静默中断」。
                // ⚠️ 旧代码一律标成 loopManualStop，于是额度不足 / 上游无响应这类**非用户中断**，
                // 也会把消息写成「已手动停止」并挂上中断 token 卡 —— 用户根本没点停止，
                // 右下角状态就是这么莫名其妙的（用户实测原话）。按事实分流，绝不冒充用户手动停止。
                if (!roundDone) {
                    if (aiStopRequested) {
                        loopManualStop = true
                        com.aurora.chat.ModelLogger.info(ctx, "STOP", "用户手动停止 | 轮次=$loopRounds 工具数=$inlineToolCount")
                    } else {
                        loopFailed = true
                        if (lastErrMsg.isBlank()) lastErrMsg = "本轮未正常结束（疑似额度不足或上游无响应）"
                        com.aurora.chat.ModelLogger.error(ctx, "STOP", "静默中断(非用户停止) | 轮次=$loopRounds 错误=${lastErrMsg.take(300)}")
                    }
                    break
                }
                // 任务型会话(仅任务型执行模式)需要交付契约：动态更新判断依据(普通对话恒 false,绝不打扰)
                contractNeeded = AITaskCompletion.needsContract(ctx, currentUserId, inlineToolCount)
                // 【交付格式规范·首次注入】与 AiChatManager.runAIChatLoop 同口径：
                // needsContract() 在**系统提示构建时**是按 usedTools=0 判定的，纯 Craft（未开多 AI）那一步恒为
                // false —— 也就是「交付契约」那段格式说明从来没进过上下文。模型随后被 nudge 逼着"输出交付块"，
                // 却根本不知道哨兵怎么写，只能写一段散文式总结 → 交付卡片永远渲染不出来
                // （用户反馈的"craft 模式下交付总结那个块出不来、AI 好像也不知道"就是这条）。
                // 因此在"本次会话真的开始用工具"这一刻补注入一次；一次任务只注一次，纯闲聊零开销。
                if (contractNeeded && !contractSpecInjected) {
                    contractSpecInjected = true
                    // 系统提示里本就带契约（多 AI 任务型：needsContract(usedTools=0) 为 true）时不重复注入，
                    // 省掉这 ~1.5KB；只有"系统提示根本没这段"的纯 Craft 场景才补。
                    if (!AITaskCompletion.needsContract(ctx, currentUserId)) {
                        history.add("user" to AITaskCompletion.buildContractPrompt(ctx, currentUserId))
                        com.aurora.chat.ModelLogger.info(ctx, "CONTRACT", "首次注入交付格式规范(已用工具 $inlineToolCount 次)")
                    }
                }
                if (loopRounds >= inlineMaxRounds) {
                    com.aurora.chat.ModelLogger.warn(
                        ctx, "STOP",
                        "工具轮数已达上限 | 轮次=$loopRounds 上限=$inlineMaxRounds 契约=$contractNeeded 已收尾轮数=$wrapUpRounds"
                    )
                    // 软上限：轮次预算耗尽**不再硬断**，而是转入收尾阶段(禁工具、强制纯文本收口)，
                    // 保证用户一定拿到一个"收得住"的回答，而不是任务做到一半页面直接停住。
                    // ⚠️ 旧写法在这里额外要求 contractNeeded(只有任务型会话为真) —— 于是**普通对话**一旦撞上限
                    // 就是**静默 break**：任务没做完、也没有任何说明（用户反馈的"任务还没完成就直接中断"）。
                    // 现在所有会话统一走收尾：要么给出交付，要么让它把没说完的话说完。
                    if (phase == AITaskCompletion.AITaskPhase.NORMAL && wrapUpRounds < AITaskCompletion.MAX_WRAP_UP_ROUNDS) {
                        phase = AITaskCompletion.AITaskPhase.WRAP_UP
                        wrapUpRounds++
                        currentToolChoice = "none"
                        // 原因里带上**实际生效的数字**：用户能一眼看出"上限到底是多少"，
                        // 而不是只看到一句"预算已用尽"却无从判断。
                        history.add("user" to AITaskCompletion.wrapUpInstruction(
                            loopRounds,
                            ""
                        ))
                        Log.i("AiInline", "内联循环轮次预算耗尽,转入收尾阶段")
                        continue
                    }
                    break
                }
                // 优先走「原生 function calling」：检测到 tool_calls 则用 role:tool 标准形式回喂
                if (nativeCalls.isNotEmpty()) {
                    val tcArr = JSONArray()
                    for (nc in nativeCalls) {
                        tcArr.put(JSONObject().apply {
                            put("id", nc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", nc.name)
                                put("arguments", nc.argumentsJson)
                            })
                        })
                    }
                    history.add("assistant" to AiChatManager.NATIVE_TOOLS_ASSISTANT_PREFIX + tcArr.toString())
                    for (nc in nativeCalls) {
                        val args = try { JSONObject(nc.argumentsJson) } catch (_: Exception) { JSONObject() }
                        val execCall = AiChatManager.AIToolCall(
                            tool = nc.name,
                            path = args.optString("path", ""),
                            content = args.optString("content", ""),
                            argsJson = nc.argumentsJson
                        )
                        // 多 AI 强制验收跟踪:是否验过收 / 动过工作区 / 复验计数
                        if (nc.name == AiChatManager.TOOL_AGENT_REVIEW) { inlineReviewCalled = true; inlineReviewCount++ }
                        if (nc.name in AiChatManager.REVIEW_REQUIRED_TOOLS) inlineMutated.add(nc.name)
                        // 调用开始：把「正在调用」状态行内联进当前 AI 消息正文（哨兵块随文本持久化，绝不丢失），
                        // 让状态显示在调用真正发生的位置，而不是堆到所有结果之后。
                        // 这条内联循环此前只传工具名 → 状态行永远是「正在读取文件」这种泛化文案；现在统一带具体目标。
                        val stageInfo = toolCallInfo(nc.name, nc.argumentsJson)
                        accumulated = AiChatManager.appendToolInline(
                            accumulated, nc.name, 0, stageInfo.target, stageInfo.argsSummary
                        )
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(
                                text = accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                            )
                        }
                        scheduleAiDebouncedSave()
                        maybeScrollToBottom()
                        val approved = toolApprovalHook(nc.name, nc.argumentsJson)
                        val result = if (approved) {
                            if (nc.name == AiChatManager.TOOL_AGENT_REVIEW && inlineReviewCount > AiChatManager.MAX_AGENT_REVIEW_CALLS) {
                                AiChatManager.reviewCapExceededResult()
                            } else {
                                AiChatManager.executeAITool(ctx, currentUserId, execCall, onAppAction)
                            }
                        } else {
                            AiChatManager.agentDeniedResult(ctx, currentUserId, nc.name)
                        }
                        // 完成态：结果 ok 显示绿色「已调用」，否则红色「调用失败」（完成为彩色状态行，不再写纯文本）
                        val sOk = try { org.json.JSONObject(result).optBoolean("ok") } catch (_: Exception) { false }
                        // 记录工具调用阶段(用于底部「调用 N 个工具」详情)
                        val stageRec = ChatMsg.ToolStageRecord(
                            tool = nc.name,
                            state = if (sOk) 1 else 2,
                            ts = System.currentTimeMillis(),
                            reason = describeToolCall(nc.name, nc.argumentsJson, snippetMax = 300),
                            target = stageInfo.fullTarget,
                            argsSummary = stageInfo.argsDetail,
                            code = stageInfo.code,
                            output = toolOutputOf(nc.name, result),
                            diff = toolDiffOf(nc.name, nc.argumentsJson)
                        )
                        inlineToolStages.add(stageRec)
                        // 【崩溃可追溯】立即写进追加式执行日志（SIGKILL 也留得住）
                        try { AIJournal.tool(stageRec) } catch (_: Throwable) {}
                        // 多 AI 元工具：把验收/规划/并行报告块写进消息流（可见的接手记录，随消息持久化）
                        // 报告块内包含子 AI 内部的工具轨迹（哪个文件/哪条命令），保证「一个不漏」
                        if (nc.name == AiChatManager.TOOL_AGENT_REVIEW || nc.name == AiChatManager.TOOL_AGENT_FORK ||
                            nc.name == AiChatManager.TOOL_AGENT_PLAN
                        )
                            (when (nc.name) {
                                AiChatManager.TOOL_AGENT_REVIEW -> AiChatManager.reviewReportBlock(result)
                                AiChatManager.TOOL_AGENT_FORK -> AiChatManager.forkReportBlock(result)
                                else -> AiChatManager.planReportBlock(result)
                            })?.let { blk ->
                            accumulated = accumulated + "\n" + blk
                        }
                        // 调用完成：把同名状态行原地替换为最终态（1 已调用 / 2 失败）
                        // ask_user 完成：「问/答」记录直接嵌进状态块（点状态行展开查看）
                        accumulated = AiChatManager.replaceToolInline(
                            accumulated, nc.name, if (sOk) 1 else 2,
                            if (nc.name == AiChatManager.TOOL_ASK_USER) AiChatManager.askUserRecordText(result) ?: "" else "",
                            stageInfo.target, stageInfo.argsSummary
                        )
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            val curAi = messages[aiMsgIdx] as ChatMsg
                            messages[aiMsgIdx] = curAi.copy(
                                text = accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""),
                                // 固定工具调用与修改日志：流式期间实时把调用数与改动写回消息，
                                // 让底部胶囊随每次调用/改动即时刷新（数字边跑边涨），并由下方 debounced 保存落盘，
                                // 即使软件闪退，这条消息也已带上已发生的工具/改动记录。
                                aiToolCallCount = if (fixedToolLog) inlineToolCount else curAi.aiToolCallCount,
                                aiToolStages = if (fixedToolLog) inlineToolStages.toList() else curAi.aiToolStages,
                                aiFileChanges = if (fixedToolLog)
                                    AiChatManager.serializeFileChanges(AiChatManager.activeFileChangeRecorder ?: emptyList())
                                else curAi.aiFileChanges
                            )
                        }
                        scheduleAiDebouncedSave()
                        maybeScrollToBottom()
                        // 工具完成后同步刷新 UI 模式状态（Plan 模式下 AI 可能已用 set_agent_mode 自动切换到 craft）
                        agentMode = AiChatManager.getAIMode(ctx, currentUserId)
                        // role:tool 回喂：PREFIX + tool_call_id + \0 + resultJson（request 组装时会还原）
                        val anchorId1 = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) messages[aiMsgIdx].id else -1L
                        insertAiFileCardIfGot(nc.name, result, anchorId1)
                        applyPlanStepResult(nc.name, result)
                        openVideoIfRequested(nc.name, result)
                        // 与 AiChatManager.runAIChatLoop 保持同一口径回喂:压缩超长工具结果。
                // 两处曾不一致(一边压缩一边原文),导致同一份代码在不同入口下上下文膨胀程度不同。
                history.add("tool" to AiChatManager.NATIVE_TOOL_RESULT_PREFIX + nc.id + "\u0000" + AiChatManager.condenseToolResult(result))
                    }
                    loopUsedTools = true
                    inlineToolCount++
                    pendingToolRound = true
                    loopRounds++
                    continue
                }
                // 文本协议兜底（原生不可用时）：@tool:start 块 → JSON 代码块 → Qwen XML 文本协议(<tool_call><function=…>)
                var toolCall = AiChatManager.extractFirstToolCall(lastRawFull)
                // 第三层兜底：扫描 JSON 代码块里的工具调用（模型可能裸输出 {"tool":"..."} 作为代码块）
                if (toolCall == null) toolCall = AiChatManager.extractToolCallFromCodeBlock(lastRawFull)
                // 第四层兜底：Qwen 系模型把工具调用以 XML 文本协议写在正文里（免费 API 不透传原生 tool_calls 时常见）
                if (toolCall == null) toolCall = AiChatManager.extractXmlStyleToolCall(lastRawFull)
                if (toolCall == null) {
                    // ── 工具调用被截断 / 解析失败 → 定向引导「分块写入」──
                    // 典型：模型用 @tool:start 协议写一个大文件，内容没吐完就被 max_tokens 截断
                    // → @tool:end 缺失或 JSON 不完整 → 解析返回 null、工具不执行。
                    // 旧代码只当成"没调工具"走空轮补催，模型往往又一次被截断，来回空转
                    // （用户实测：单独派创建文件没事，AI 自己决定写大文件就卡住不动）。
                    if (AiChatManager.hasBrokenToolCall(lastRawFull)) {
                        brokenToolRounds++
                        if (brokenToolRounds <= 2) {
                            history.add("user" to BROKEN_TOOL_CALL_INSTRUCTION)
                            Log.w("AiInline", "工具调用被截断/解析失败,第 $brokenToolRounds 次引导分块写入")
                            com.aurora.chat.ModelLogger.warn(ctx, "GUARD", "工具调用被截断/解析失败(大文件写入超限) 第 $brokenToolRounds 次引导分块写入 | 轮次=$loopRounds")
                            loopRounds++
                            continue
                        }
                    }
                    // 模型本轮既没调用工具、也没输出正文（只有思考）：只要没到轮数上限就继续下一轮，
                    // 避免"想了半天却什么都没说"就结束。
                    val cleanedFull = AiChatManager.stripToolBlocks(lastRawFull).trim()
                    // ── 空轮（只有思考、没有正文也没有工具调用）→ 有界补催，绝不静默空转 ──
                    // 这是模型「过度思考 / 纸上写文件」的典型形态：把要写的文件内容在思考里列一遍，
                    // 既不调工具也不输出正文。旧写法这里直接 continue：无补催、无提示、无上限，
                    // 表现为「一到创建文件就卡壳」——界面只剩一个「思考过程」折叠条、再无任何输出，
                    // 而停止键还是红的（任务确实在跑，只是在无声烧轮次）。用户实测原话。
                    // 现在与 runAIChatLoop 同口径：补催话术直接点到「要落盘就立刻发起工具调用」，
                    // 并用 MAX_EMPTY_ROUNDS 封顶，耗尽就明确结束，绝不无限空转。
                    if (cleanedFull.isBlank() && loopRounds < inlineMaxRounds) {
                        emptyRounds++
                        if (emptyRounds <= AITaskCompletion.MAX_EMPTY_ROUNDS) {
                            history.add("user" to "")
                            Log.w("AiInline", "内联循环静默空轮,第 $emptyRounds 次补催")
                            // 同时写入本地日志，便于事后定位空转轮次
                            com.aurora.chat.ModelLogger.warn(ctx, "GUARD", "静默空轮(只思考/无正文/无工具) 第 $emptyRounds 次补催 | 轮次=$loopRounds")
                            loopRounds++
                            continue
                        }
                        Log.w("AiInline", "内联循环空轮补催已耗尽($emptyRounds 次),结束任务")
                        com.aurora.chat.ModelLogger.warn(ctx, "GUARD", "静默空轮补催已耗尽($emptyRounds 次)→ 任务终止 | 轮次=$loopRounds 工具数=$inlineToolCount")
                        if (accumulated.isBlank()) {
                            accumulated = "〔系统提示〕AI 连续 $emptyRounds 轮只思考、不输出正文也不调用工具（疑似上游空响应或模型异常），任务未能完成。请重试，或换个说法。"
                        } else {
                            accumulated += "\n〔系统提示〕AI 连续 $emptyRounds 轮只思考、不输出正文也不调用工具，任务在此中断，尚未完成。可以让我重试。"
                        }
                        break
                    }
                    // 多 AI 硬约束(编排层兜底):动了工作区却从未调用 agent_review → 注入补验指令,强制再跑一轮。
                    // 提示词层的「必须验收」被模型无视时的确定性补救;只补验一次,防死循环。
                    if (AiChatManager.execActive(ctx, currentUserId, AiChatManager.EXEC_MULTI_AGENT) &&
                        !inlineReviewCalled && !inlineReviewNudged && inlineMutated.isNotEmpty() && loopRounds < inlineMaxRounds
                    ) {
                        inlineReviewNudged = true
                        history.add("user" to "")
                        continue
                    }
                    // 【交付前·清单校验】强约束:只要会话里存在方案卡、且「流程」清单还有未完成的步骤,
                    // 就不允许任务结束——即使模型已吐出交付块(DELIVER)也照常强制续做。
                    // 用 planCardExists(而非 planActive)判定:planActive 会在模型吐出交付块后变 false,
                    // 旧写法下模型只要「做几步 + 写交付总结」就能借 DELIVER 让 planActive 翻转、从而绕过本校验提前停下。
                    // 独立预算 planNudgeCount(上限 planStepNudgeCap)不与「防提前收工」共享,确保多步方案不被 2 次 nudge 额度饿死。
                    val planPendingSteps = if (planCardExists) {
                        messages.lastOrNull { it.text.contains("[[PLAN_CARD]]") && !it.text.startsWith(PLAN_LIVE_TAG) }
                            ?.let { parsePlanCard(it.text) }?.steps?.count { it.status != "done" } ?: 0
                    } else 0
                    if (phase != AITaskCompletion.AITaskPhase.WRAP_UP && planPendingSteps > 0 &&
                        planNudgeCount < planStepNudgeCap
                    ) {
                        planNudgeCount++
                        history.add("user" to "")
                        continue
                    }
                    // 【禁止请示·确定性封堵】模型以「要不要我继续 / 需要我现在开始吗 / 是否继续处理」这类请示句
                    // 收尾（且没有交付）→ 判为**无效收尾**：系统替用户回答「继续」并强制再跑一轮。
                    // 用户实测的恶性循环：任务刚开头就问要不要下一轮再改 → 用户说继续 → 只做一步又问 → 无限套娃。
                    // 提示词层的劝阻被模型稳定无视，所以这里必须在编排层封死。
                    // 位置必须在「防提前收工」之前：后者要求"补齐交付总结"，对着"任务才做了一半"的回合
                    // 反而会诱导模型写一张半截交付卡就此合法收工。
                    // 只封**纯文本**请示；真正的破坏性操作确认走 ask_user 工具（那是工具调用，不走这条分支）。
                    if (phase != AITaskCompletion.AITaskPhase.WRAP_UP && contractNeeded &&
                        permissionNudgeCount < AITaskCompletion.MAX_PERMISSION_NUDGES &&
                        AITaskCompletion.asksForPermission(accumulated)
                    ) {
                        permissionNudgeCount++
                        history.add("user" to AITaskCompletion.permissionDeniedInstruction())
                        Log.i("AiInline", "内联循环:请示式收尾被驳回,强制继续(第 $permissionNudgeCount 次)")
                        continue
                    }
                    // 防提前收工：按实际进度分两种(与 runAIChatLoop 同口径):
                    //  · 工作未完成(无成功产出类工具调用) → 「继续执行」指令,预算 MAX_CONTINUE_WORK_NUDGES;
                    //  · 工作已完成但无交付 → 「补交付」指令,预算 MAX_DELIVER_NUDGES。
                    // 旧写法只有一条「不要再调用任何工具」的补做指令,工作没做完就催着写总结,等于教模型半途收工。
                    val objectiveDone = AiChatManager.hasObjectiveCompletion(inlineToolStages.toList())
                    val nudgeBudget = if (objectiveDone) AITaskCompletion.MAX_DELIVER_NUDGES else AITaskCompletion.MAX_CONTINUE_WORK_NUDGES
                    if (phase != AITaskCompletion.AITaskPhase.WRAP_UP && contractNeeded && !AITaskCompletion.hasDelivery(accumulated) && nudgeCount < nudgeBudget) {
                        nudgeCount++
                        history.add("user" to AITaskCompletion.nudgeInstruction(objectiveDone))
                        Log.i("AiInline", "内联循环模型想结束但未交付,强制续做(第 $nudgeCount 次,工作已客观完成=$objectiveDone)")
                        continue
                    }
                    // 收尾阶段(WRAP_UP)：给最多 MAX_WRAP_UP_ROUNDS 轮交付；交付即停，否则续一轮
                    if (phase == AITaskCompletion.AITaskPhase.WRAP_UP) {
                        if (AITaskCompletion.hasDelivery(accumulated) || wrapUpRounds >= AITaskCompletion.MAX_WRAP_UP_ROUNDS) break
                        wrapUpRounds++
                        history.add("user" to "")
                        continue
                    }
                    // 兜底：任务型会话模型始终不交付 → 编排层合成交付块，确保用户一定拿到结果
                    // ⚠️ 旧写法传的是 emptyList(), emptyList()：合成出来的卡片永远印着「(无工具调用记录) /
                    // (未检出文件改动)」—— 明明是刚跑完一堆工具的任务，卡片却是空壳，形同噪声。
                    // 现在把本轮的**真实**工具阶段记录与文件改动喂进去（与 runAIChatLoop 同口径）。
                    if (contractNeeded && !AITaskCompletion.hasDelivery(accumulated)) {
                        accumulated += "\n" + AITaskCompletion.fallbackDeliveryBlock(
                            inlineToolStages.toList(),
                            AiChatManager.activeFileChangeRecorder?.toList() ?: emptyList()
                        )
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(text = accumulated)
                        }
                    }
                    break
                }
                // 调用开始：把「正在调用」状态行内联进当前 AI 消息正文（哨兵块随文本持久化，绝不丢失），
                // 让状态显示在调用真正发生的位置，而不是堆到所有结果之后。
                val stageInfo = toolCallInfo(toolCall.tool, toolCall.argsJson)
                accumulated = AiChatManager.appendToolInline(
                    accumulated, toolCall.tool, 0, stageInfo.target, stageInfo.argsSummary
                )
                // 多 AI 强制验收跟踪:是否验过收 / 动过工作区 / 复验计数
                if (toolCall.tool == AiChatManager.TOOL_AGENT_REVIEW) { inlineReviewCalled = true; inlineReviewCount++ }
                if (toolCall.tool in AiChatManager.REVIEW_REQUIRED_TOOLS) inlineMutated.add(toolCall.tool)
                if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                    messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(
                        text = accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                    )
                }
                scheduleAiDebouncedSave()
                maybeScrollToBottom()
                // 执行工具（IO 调度、内部容错不抛异常）
                val approved = toolApprovalHook(toolCall.tool, toolCall.argsJson)
                val result = if (approved) {
                    if (toolCall.tool == AiChatManager.TOOL_AGENT_REVIEW && inlineReviewCount > AiChatManager.MAX_AGENT_REVIEW_CALLS) {
                        AiChatManager.reviewCapExceededResult()
                    } else {
                        AiChatManager.executeAITool(ctx, currentUserId, toolCall, onAppAction)
                    }
                } else {
                    AiChatManager.agentDeniedResult(ctx, currentUserId, toolCall.tool)
                }
                // 完成态：结果 ok 显示绿色「已调用」，否则红色「调用失败」（完成为彩色状态行，不再写纯文本）
                val sOk = try { org.json.JSONObject(result).optBoolean("ok") } catch (_: Exception) { false }
                // 记录工具调用阶段(用于底部「调用 N 个工具」详情)
                val stageRec = ChatMsg.ToolStageRecord(
                    tool = toolCall.tool,
                    state = if (sOk) 1 else 2,
                    ts = System.currentTimeMillis(),
                    reason = describeToolCall(toolCall.tool, toolCall.argsJson, snippetMax = 300),
                    target = stageInfo.fullTarget,
                    argsSummary = stageInfo.argsDetail,
                    code = stageInfo.code,
                    output = toolOutputOf(toolCall.tool, result),
                    diff = toolDiffOf(toolCall.tool, toolCall.argsJson)
                )
                inlineToolStages.add(stageRec)
                try { AIJournal.tool(stageRec) } catch (_: Throwable) {}
                // 多 AI 元工具：把验收/规划/并行报告块写进消息流（可见的接手记录，随消息持久化）
                if (toolCall.tool == AiChatManager.TOOL_AGENT_REVIEW || toolCall.tool == AiChatManager.TOOL_AGENT_FORK ||
                    toolCall.tool == AiChatManager.TOOL_AGENT_PLAN
                )
                    (when (toolCall.tool) {
                        AiChatManager.TOOL_AGENT_REVIEW -> AiChatManager.reviewReportBlock(result)
                        AiChatManager.TOOL_AGENT_FORK -> AiChatManager.forkReportBlock(result)
                        else -> AiChatManager.planReportBlock(result)
                    })?.let { blk ->
                    accumulated = accumulated + "\n" + blk
                }
                // 调用完成：把同名状态行原地替换为最终态（1 已调用 / 2 失败）
                // ask_user 完成：「问/答」记录直接嵌进状态块（点状态行展开查看）
                accumulated = AiChatManager.replaceToolInline(
                    accumulated, toolCall.tool, if (sOk) 1 else 2,
                    if (toolCall.tool == AiChatManager.TOOL_ASK_USER) AiChatManager.askUserRecordText(result) ?: "" else "",
                    stageInfo.target, stageInfo.argsSummary
                )
                if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                    messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(
                        text = accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                    )
                }
                scheduleAiDebouncedSave()
                maybeScrollToBottom()
                // 工具完成后同步刷新 UI 模式状态（Plan 模式下 AI 可能已用 set_agent_mode 自动切换到 craft）
                agentMode = AiChatManager.getAIMode(ctx, currentUserId)
                // 回喂历史：本轮助手原文（含已消费的工具块）+ 工具执行结果
                val anchorId2 = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) messages[aiMsgIdx].id else -1L
                insertAiFileCardIfGot(toolCall.tool, result, anchorId2)
                applyPlanStepResult(toolCall.tool, result)
                openVideoIfRequested(toolCall.tool, result)
                history.add("assistant" to lastRawFull)
                // 与 AiChatManager.runAIChatLoop 的文本协议回喂保持同一口径(压缩超长结果)
                history.add("user" to "工具执行结果: " + AiChatManager.condenseToolResult(result))
                loopUsedTools = true
                    inlineToolCount++
                pendingToolRound = true
                    loopRounds++
            }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // 用户手动停止（协程被 cancel）：按手动停止收口，绝不向上抛——
                // 抛出等于把下面的一次性收尾整段作废，token 明细/耗时/工具计数会全部丢失。
                loopManualStop = true
                com.aurora.chat.ModelLogger.info(ctx, "STOP", "用户手动停止(协程取消) | 轮次=$loopRounds 工具数=$inlineToolCount")
            }
            // ===== 一次性收尾（正常完成 / 手动停止 / 出错 均执行；与 runAIChatLoop 对齐补齐用量明细、文件改动、工具计数、扣费）=====
            // 端到端「总时长」：取发送起始到收尾的真实耗时，覆盖单轮响应 <1s 被取整为 0 而隐藏总时长的问题
            if (lastTotal <= 0L) lastTotal = ((System.currentTimeMillis() - tSendStart) / 1000L).coerceAtLeast(0L)
            // 【中断也要能查看消耗】手动停止时本轮流被 stopCheck 提前收口，上游 onDone 不回调 →
            // lastUsageDetail 可能为空。原来这里直接写 0，会覆盖已有统计、并让底部「Token 明细」按钮的
            // 渲染条件（totalSeconds>0 || 有 token 统计）彻底不成立，用户点停止后就查不到本次消耗。
            //
            // 取值优先级（用户对"假数据"零容忍 → 能用真值绝不用估算）：
            //  ① 上游 usage（未截断的正常轮次已经有）；
            //  ② paid_api：服务端权威结算 —— 后端在客户端断开时**照样结算并落库**
            //     （backend/paid_api.go 防线③），这里查一次「最近一次结算」把真实 input/output 取回；
            //  ③ 才轮到客户端估算：输入用「最终真实 history」重算（system+工具schema+全部累积工具结果的完整口径）、
            //     输出=正文+思考+工具调用的估算。明细卡片会标注这是中断记录。
            //     注：旧回退值是发送时按初始上下文算的 liveEstInput，不含任务过程的工具结果，会低一个数量级
            //     （见 AiChatManager 旧教训注释）；现改为按最终 history 重算。
            val realIn = lastUsageDetail?.inputTokens ?: 0L
            val realOut = lastUsageDetail?.outputTokens ?: 0L
            val realCache = lastUsageDetail?.cacheHitTokens ?: 0L
            val realCtx = lastUsageDetail?.contextLength ?: 0L
            val settled = if (loopManualStop && realIn <= 0L && realOut <= 0L && mode == "paid_api") {
                runCatching { AuroraApi.paidApiLastUsage() }.getOrNull()
                    ?.takeIf { it.success && it.data?.optBoolean("found") == true }
                    ?.data
            } else null
            val srvIn = settled?.optLong("input_tokens", 0L) ?: 0L
            val srvOut = settled?.optLong("output_tokens", 0L) ?: 0L
            val srvCost = settled?.optLong("cost_tokens", 0L) ?: 0L
            // 基于「最终真实 history」重算输入：history 已累积全部工具结果，口径与 streamChat 实际发出一致。
            // 用途 ①：无任何上游/服务端真值时（estFallback）替代过时的首轮估算，消除"低一个数量级"误差；
            // 用途 ②：配合下方 inFlightInput，补回手动中断时被截断最后一轮的输入。
            val finalHistoryInput = AiChatManager.estimateHistoryInputTokens(
                ctx, currentUserId, systemForHistory, history.map { it.second }, enableAITools, fastOn,
                lastUserText = history.lastOrNull { it.first == "user" }?.second, history = history
            )
            // 手动中断且最后一轮被截断：该轮输入未进 realIn。用进入该轮时的 history 输入估算补回
            // （每轮顶部已重算并存入 prevRoundHistoryInput）。该值只含被截断那一轮自身的 system+tools+历史，
            // 与 realIn（已完成轮各自的上游真值）互不重叠，不会重复计数。
            val inFlightInput = if (loopManualStop) prevRoundHistoryInput else 0L
            // 输出估算必须包含**思考**（reasoning 也是计费输出，量级常与正文相当）与工具调用参数
            val estOut = AiChatManager.estimateTokens(accumulated).toLong() +
                (if (reasoningSegments.isNotBlank()) AiChatManager.estimateTokens(reasoningSegments).toLong() else 0L)
            val estFallback = loopManualStop && realIn <= 0L && realOut <= 0L &&
                srvIn <= 0L && srvOut <= 0L && accumulated.isNotBlank()
            val inTok = when {
                srvIn > 0L -> srvIn
                realIn > 0L -> realIn + inFlightInput
                estFallback -> finalHistoryInput
                else -> 0L
            }
            val outTok = when {
                realOut > 0L -> realOut
                srvOut > 0L -> srvOut
                estFallback -> estOut
                else -> 0L
            }
            val ctxTok = if (realCtx > 0L) realCtx else (inTok + outTok)
            val stillHere = AiConversationSession.storageKey() == myKey
            val idx = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) aiMsgIdx else -1
            if (stillHere && idx >= 0) {
                // 正常完成时按原逻辑改写正文/思考/时长（多工具、多输出分气泡已在循环内就近处理，避免重复）
                if (!loopFailed && !loopManualStop) {
                    val multi = AiChatManager.getMultiOutput(ctx, currentUserId)
                    val full = accumulated
                    val finalReasoning = if (AiChatManager.getShowReasoning(ctx, currentUserId)) (messages[idx] as ChatMsg).reasoningText else ""
                    if (loopUsedTools) {
                        messages[idx] = (messages[idx] as ChatMsg).copy(
                            reasoningText = finalReasoning,
                            thinkingSeconds = lastThinking, totalSeconds = lastTotal, aiWaiting = false, aiProgressHint = ""
                        )
                    } else if (multi && full.contains(AiChatManager.MULTI_OUTPUT_SPLIT)) {
                        messages.removeAt(idx)
                        val segs = full.split(AiChatManager.MULTI_OUTPUT_SPLIT).map { it.trim() }.filter { it.isNotBlank() }
                        var pos = idx.coerceAtLeast(0)
                        var firstText = ""
                        for (seg in segs) {
                            if (firstText.isEmpty()) firstText = AiChatManager.stripInlineReasoning(seg).take(60)
                            messages.add(pos, ChatMsg.create(
                                serverId = System.currentTimeMillis() * 10000L + (0..9999).random(), text = seg,
                                isMine = false, fromUserId = AI_CHAT_ID, senderName = AiNameState.name ?: "AI",
                                isNew = true, createdAt = System.currentTimeMillis() / 1000, reasoningText = finalReasoning, aiMode = mode,
                                thinkingSeconds = lastThinking, totalSeconds = lastTotal
                            ))
                            pos++
                        }
                        if (firstText.isNotEmpty()) ChatViewModel.updateConversationPreview(friendId, firstText, System.currentTimeMillis() / 1000)
                    } else {
                        messages[idx] = (messages[idx] as ChatMsg).copy(
                            text = full, reasoningText = finalReasoning,
                            thinkingSeconds = lastThinking, totalSeconds = lastTotal, aiWaiting = false, aiProgressHint = ""
                        )
                        if (full.isNotBlank()) ChatViewModel.updateConversationPreview(friendId, AiChatManager.stripInlineReasoning(full).take(60), System.currentTimeMillis() / 1000)
                    }
                } else if (loopManualStop) {
                    // 手动停止收尾：标记「已手动停止」并复位等待态，彻底停掉「模型响应中」。
                    // 同时补写耗时——底部整行信息（查看改动 / 调用工具 / Token 明细）的渲染条件就是
                    // manualStopped || 总时长>0 || 有 token 统计，不写耗时时若连 token 也拿不到，整行直接不渲染。
                    val prevM = messages[idx] as ChatMsg
                    messages[idx] = prevM.copy(
                        manualStopped = true, aiWaiting = false,
                        thinkingSeconds = if (lastThinking > 0L) lastThinking else prevM.thinkingSeconds,
                        totalSeconds = if (lastTotal > 0L) lastTotal else prevM.totalSeconds
                    )
                } else {
                    // 出错收尾：复位等待态（非手动停止，不标 manualStopped）
                    // 同样补写真实端到端耗时：出错也不该让「查看改动 / 调用工具 / 耗时」整行消失
                    val prevE = messages[idx] as ChatMsg
                    messages[idx] = prevE.copy(
                        aiWaiting = false,
                        thinkingSeconds = if (lastThinking > 0L) lastThinking else prevE.thinkingSeconds,
                        totalSeconds = if (lastTotal > 0L) lastTotal else prevE.totalSeconds
                    )
                }
                // 统一补写付费 API 用量明细 / 文件改动 / 工具计数（与 runAIChatLoop 对齐）：
                // 无论正常完成、手动停止还是出错，均保证底部「token详情 / 查看所有改动 / 调用 N 个工具」可见、扣费依据完整
                val fc = AiChatManager.serializeFileChanges(AiChatManager.activeFileChangeRecorder ?: emptyList())
                val curMsg = messages[idx] as ChatMsg
                messages[idx] = curMsg.copy(
                    // 一律「有值才覆盖」：中断前写好的统计不能被 0 冲掉
                    inputTokens = if (inTok > 0L) inTok else curMsg.inputTokens,
                    outputTokens = if (outTok > 0L) outTok else curMsg.outputTokens,
                    cacheHitTokens = if (realCache > 0L) realCache else curMsg.cacheHitTokens,
                    contextLength = if (ctxTok > 0L) ctxTok else curMsg.contextLength,
                    // 中断记录的口径来源：true=服务端结算真值，false=客户端估算。卡片据此标注，绝不把估算当权威。
                    aiUsageAuthoritative = loopManualStop && (srvIn > 0L || srvOut > 0L || srvCost > 0L),
                    aiFileChanges = fc,
                    aiToolCallCount = inlineToolCount,
                    aiToolStages = inlineToolStages.toList()
                )
                val snap = messages.filter { !it.isSystemNotice }.toList()
                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
            } else if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                // 已切走：把完整回复与元数据合并写回所属会话文件（aiMsgIdx 是 A 会话索引，不碰当前内存）
                val aiId = messages[aiMsgIdx].id
                if (aiId != 0L) {
                    val fc = AiChatManager.serializeFileChanges(AiChatManager.activeFileChangeRecorder ?: emptyList())
                    aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, myKey, aiId, text = accumulated, thinkingSeconds = lastThinking, totalSeconds = lastTotal, manualStopped = if (loopManualStop) true else null, inputTokens = if (inTok > 0L) inTok else null, outputTokens = if (outTok > 0L) outTok else null, cacheHitTokens = if (realCache > 0L) realCache else null, contextLength = if (ctxTok > 0L) ctxTok else null, fileChangesJson = fc, toolCallCount = inlineToolCount, toolStages = inlineToolStages.toList(), aiUsageAuthoritative = if (loopManualStop && (srvIn > 0L || srvOut > 0L || srvCost > 0L)) true else null) } catch (_: Exception) {} }
                }
            }
            // 付费 API：扣费由后端中转完成（后端解析上游真实 usage，按 paid_apis 权威倍率扣费）。
            // 「已扣费」优先用**服务端权威值**（中断时后端照样结算落库，srvCost 即真实扣费 token）；
            // 拿不到才退回同一倍率口径本地估算。不再向服务端上报 token——重复上报会二次扣费。
            if (mode == "paid_api") {
                val ud2 = lastUsageDetail
                val billedIn = if (ud2 != null && ud2.inputTokens > 0L) ud2.inputTokens else srvIn
                val billedOut = if (ud2 != null && ud2.outputTokens > 0L) ud2.outputTokens else srvOut
                if ((billedIn > 0L || billedOut > 0L) || srvCost > 0L) {
                    val (paidInPrice, paidOutPrice) = AiChatManager.getPaidApiPrice(ctx, currentUserId)
                    val cost = if (srvCost > 0L) srvCost else if (paidInPrice > 0.0 || paidOutPrice > 0.0) {
                        kotlin.math.ceil(billedIn * paidInPrice).toLong() +
                            kotlin.math.ceil(billedOut * paidOutPrice).toLong()
                    } else 0L
                    if (cost > 0L) {
                        val i2 = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) aiMsgIdx else -1
                        if (i2 >= 0) {
                            messages[i2] = (messages[i2] as ChatMsg).copy(aiCostDeducted = cost)
                            val snap2 = messages.filter { !it.isSystemNotice }.toList()
                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap2) } catch (_: Exception) {} }
                        } else {
                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, myKey, messages[aiMsgIdx].id, aiCostDeducted = cost) } catch (_: Exception) {} }
                        }
                    }
                    aiGlobalScope.launch {
                        try {
                            AiChatManager.syncServerLimit(ctx, currentUserId)
                            aiUsageState.value = AiChatManager.getOfficialUsage(ctx, currentUserId)
                        } catch (_: Exception) {}
                    }
                }
            }
            // 今日已用本地计数：凡「真实消耗」模式（个人 / 官方 / 第三方 / 付费 API，即非免费共享接口 free_api）
            // 均累加「今日已用」，口径与每条消息的 token 明细完全一致：上游 input+output 真实 token 之和，不做任何倍率换算。
            if (!loopFailed && !loopManualStop && mode != "free_api" && lastUsage > 0L) {
                AiChatManager.addOfficialUsage(ctx, currentUserId, lastUsage)
            }
            // 官方 / 第三方共享额度模式额外递减「剩余」（其余模式余额来自服务器，不在本地计算）
            if (!loopFailed && !loopManualStop && (mode == "official" || mode == "third_party") && lastUsage > 0 && poolAtStart < Long.MAX_VALUE) {
                val finalUsed = baseUsed + lastUsage
                val finalRemaining = (poolAtStart - lastUsage).coerceAtLeast(0)
                aiUsageState.value = Pair(finalUsed, finalRemaining)
                scope.launch {
                    AiChatManager.syncServerLimit(ctx, currentUserId)
                }
            } else if (!loopFailed && !loopManualStop && mode == "personal") {
                // 个人 API 无共享额度池：剩余以服务器为准（通常 -1），仅刷新「今日已用」展示
                aiUsageState.value = AiChatManager.getOfficialUsage(ctx, currentUserId)
            }
            // 本轮结束（正常完成 / 出错 / 手动停止 / 协程被取消，都会走到这里）：
            // 统一收口所有仍停在「等待模型响应」的气泡。工具轮次新建过气泡、或写回索引漂移
            // 导致某条占位再没人写时，这里是最后一道兜底——绝不让秒数继续跳。
            // 已切走会话时不碰当前内存列表（那是另一个会话，可能正在正常生成）。
            // ── 【结束原因·一行定性】无论从哪个分支退出，这里都给一句确定的结论，
            // 用户在「AI 使用 → 查看模型日志」里只看最后一行 STOP 就知道"到底为什么停"，
            // 不用从十几行流水里自己猜。这是"出问题能定位"的最后一块。
            val stopReason = when {
                loopManualStop && aiStopRequested -> "用户点停止"
                loopManualStop -> "协程被取消(切走会话/页面销毁)"
                loopFailed -> "上游失败:${lastErrMsg.take(160)}"
                emptyRounds >= AITaskCompletion.MAX_EMPTY_ROUNDS -> "静默空轮补催耗尽(模型只思考不行动)"
                brokenToolRounds >= 2 -> "大文件写入被截断且分块引导无效"
                finishReason == "length" -> "输出被 max_tokens 截断(续写额度已尽)"
                loopRounds >= inlineMaxRounds && inlineMaxRounds != Int.MAX_VALUE -> "工具轮数达上限($inlineMaxRounds)"
                else -> "正常完成"
            }
            com.aurora.chat.ModelLogger.info(
                ctx, "STOP",
                "结束 | 原因=$stopReason | 轮次=$loopRounds 工具=$inlineToolCount 空轮=$emptyRounds 截断引导=$brokenToolRounds 续写=$continueCount 正文=${accumulated.length}字"
            )
            if (AiConversationSession.storageKey() == myKey) closeStaleAiWaiting(0L)
            // 复位文件改动收集器（避免泄漏到后续 runAIChatLoop 调用），并复位写状态
            AiChatManager.activeFileChangeRecorder = null
            aiWriting.value = false
            aiWritingConvId = -1L
            agentTaskInFlight.value = false
            agentTaskInFlightConvId = -1L
            com.aurora.chat.AITaskGuard.exit(ctx)
            // 收尾(正常完成 / 出错 / 用户手动停止都会到这里)：日志已完成使命，删掉
            try { AIJournal.finish(ctx) } catch (_: Throwable) {}
        }
    }

    // 进入时从 SharedPreferences 恢复草稿
    LaunchedEffect(Unit) {
        val saved = ctx.getSharedPreferences("aurora_drafts", Context.MODE_PRIVATE)
            .getString(draftKey, "") ?: ""
        inputText = saved
    }

    // 平台头像（所有对话共用）
    val myAvatarBitmapState = remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var myAvatarBitmap by myAvatarBitmapState
    // AI 对话专用自定义头像（与平台头像完全隔离）

    // 离开时保存草稿 + 缓存消息数据（让再次进入零延迟）
    DisposableEffect(Unit) {
        onDispose {
            ctx.getSharedPreferences("aurora_drafts", Context.MODE_PRIVATE)
                .edit().putString(draftKey, inputText).apply()
            //  保存当前消息列表到顶层缓存，组件销毁后数据不丢
            if (messages.isNotEmpty() && !isLocalChat(friendId)) {
                companionMessageCache[friendId] = messages.map { msg ->
                    if (msg is com.aurora.chat.data.store.StoreMessageRef) {
                        ChatMsg(
                            id = msg.id, serverId = msg.serverId, text = msg.text, isMine = msg.isMine,
                            fromUserId = msg.fromUserId, senderName = msg.senderName,
                            createdAt = msg.createdAt, time = msg.time,
                            isRevoked = msg.isRevoked, isSystemNotice = msg.isSystemNotice,
                            replyToText = msg.replyToText, replyToSender = msg.replyToSender,
                            replyToId = msg.replyToId, mediaType = msg.mediaType,
                            mediaUrl = msg.mediaUrl, isUploading = msg.isUploading,
                            toUserId = msg.toUserId, targetName = msg.targetName,
                            flashDuration = msg.flashDuration
                        )
                    } else msg as ChatMsg
                    }
                    }
                    }
                    }
    // 已销毁的闪照消息 ID（本地 + 服务端双重记录，防清缓存重看）
    val destroyedFlashIdsState = remember {
        mutableStateOf(
            try {
                val flashFile = java.io.File(ctx.filesDir, "flash_destroyed_${currentUserId}.json")
                if (flashFile.exists()) {
                    val arr = org.json.JSONArray(flashFile.readText())
                    val set = mutableSetOf<Long>()
                    for (i in 0 until arr.length()) set.add(arr.getLong(i))
                    set
                } else emptySet()
            } catch (_: Exception) { emptySet() }
        )
    }
    var destroyedFlashIds by destroyedFlashIdsState
    // 对方已查看的闪照消息ID（我发的，对方已点过）
    val partnerViewedFlashIdsState = remember { mutableStateOf<Set<Long>>(emptySet()) }
    var partnerViewedFlashIds by partnerViewedFlashIdsState
    // 对方"正在查看中"的闪照消息ID（我发的；由 TCP flash_viewing/flash_viewed 实时维护）
    val viewingFlashIdsState = remember { mutableStateOf<Set<Long>>(emptySet()) }
    var viewingFlashIds by viewingFlashIdsState
    // 加载服务端的闪照查看记录（防清缓存重看）
    LaunchedEffect(currentUserId) {
        try {
            val serverIds = com.aurora.chat.data.api.AuroraApi.getViewedFlashIds()
            if (serverIds.success && serverIds.data != null) {
                destroyedFlashIds = destroyedFlashIds + serverIds.data.toSet()
                // 同步到本地文件
                try {
                    val flashFile = java.io.File(ctx.filesDir, "flash_destroyed_${currentUserId}.json")
                    val arr = org.json.JSONArray(destroyedFlashIds.toList())
                    flashFile.writeText(arr.toString())
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
    // 拉取"我发的闪照"中哪些已被对方查看（进入对话时 + 新消息到达时刷新）
    LaunchedEffect(currentUserId, friendId, messages.size) {
        val myFlashMsgIds = messages
            .filter { it.flashDuration > 0 && it.isMine }
            .map { it.id }
        if (myFlashMsgIds.isEmpty()) {
            partnerViewedFlashIds = emptySet()
            viewingFlashIds = emptySet()
            return@LaunchedEffect
        }
        try {
            val res = com.aurora.chat.data.api.AuroraApi.getPartnerViewedFlashIds(myFlashMsgIds)
            if (res.success && res.data != null) {
                // 合并而非覆盖：避免 TCP 实时事件刚到、HTTP 拉取尚未包含时把状态回退
                val viewed = res.data.toSet()
                partnerViewedFlashIds = partnerViewedFlashIds + viewed
                viewingFlashIds = viewingFlashIds - viewed
            }
        } catch (_: Exception) {}
    }
    // 保存已销毁闪照 ID 到文件 + 服务端双写
    fun saveDestroyedFlash(id: Long, mediaUrl: String = "") {
        destroyedFlashIds = destroyedFlashIds + id
        try {
            val flashFile = java.io.File(ctx.filesDir, "flash_destroyed_${currentUserId}.json")
            val arr = org.json.JSONArray(destroyedFlashIds.toList())
            flashFile.writeText(arr.toString())
        } catch (_: Exception) {}
        // 同步到服务端（防止清缓存重看 + 让服务端删资源文件）
        scope.launch {
            try { com.aurora.chat.data.api.AuroraApi.recordFlashView(id, mediaUrl) } catch (_: Exception) {}
        }
    }
    // 上报"我正在查看对方的闪照"：服务端据此给发送方实时推送"对方查看中"（fire-and-forget）
    fun saveFlashViewStart(id: Long) {
        if (id <= 0L) return
        scope.launch {
            try { com.aurora.chat.data.api.AuroraApi.notifyFlashViewing(id) } catch (_: Exception) {}
        }
    }

    // 【Fix 2】点击 "+" 后平滑滚动到底部。
    // 时机必须晚于面板展开动画（240ms）：面板现在是逐帧增高，列表同时在逐帧重排，
    // 原来在 110ms（动画正中间）就 animateScrollToItem，等于让两套动画抢同一批帧，
    // 观感就是"走到一半卡一下"。末尾那次 dispatchRawDelta(1f) 只是为了在阶跃布局下
    // 强制再走一次布局，现在高度是连续变化的，它只会多制造一次重排，一并去掉。
    LaunchedEffect(showPlusDialog) {
        if (showPlusDialog && messages.isNotEmpty()) {
            try {
                delay(260)
                listState.animateScrollToItem(0)
            } catch (_: Exception) { }
        }
    }

    // 当前打开弹窗的消息 ID（0 表示无弹窗），用于全局关闭
    val openMenuMsgIdState = remember { mutableStateOf(0L) }
    var openMenuMsgId by openMenuMsgIdState
    // 红包卡片长按菜单：目标消息 id（0=未打开）+ 卡片在窗口中的位置（供浮层贴近定位）
    val redPacketMenuMsgIdState = remember { mutableStateOf(0L) }
    var redPacketMenuMsgId by redPacketMenuMsgIdState
    val redPacketMenuAnchorState = remember { mutableStateOf(Rect.Zero) }
    var redPacketMenuAnchor by redPacketMenuAnchorState
    // 群公告卡片长按菜单：目标消息 id + 卡片位置
    val announcementMenuMsgIdState = remember { mutableStateOf(0L) }
    var announcementMenuMsgId by announcementMenuMsgIdState
    val announcementMenuAnchorState = remember { mutableStateOf(Rect.Zero) }
    var announcementMenuAnchor by announcementMenuAnchorState
    // 点击空白区域时递增，用于通知所有气泡退出选择模式
    val clearSelectionKeyState = remember { mutableStateOf(0) }
    var clearSelectionKey by clearSelectionKeyState
    // 多选：进入多选模式、已选消息 id（不可变 Set，切换/删除都换引用以触发重组）、删除确认
    val multiSelectModeState = remember { mutableStateOf(false) }
    var multiSelectMode by multiSelectModeState
    val selectedMsgIds = remember { mutableStateOf(setOf<Long>()) }
    val multiDeleteConfirmState = remember { mutableStateOf(false) }
    var multiDeleteConfirm by multiDeleteConfirmState
    // 多选 → 分享聊天：待分享的卡片文本 + 分享面板开关
    val chatShareCardTextState = remember { mutableStateOf("") }
    var chatShareCardText by chatShareCardTextState
    val showChatShareDialogState = remember { mutableStateOf(false) }
    var showChatShareDialog by showChatShareDialogState
    // 点开「分享聊天」卡片 → 全屏查看页（非空即显示）；msgId 用于查看中被撤回时立即中断
    val chatShareViewTextState = remember { mutableStateOf("") }
    var chatShareViewText by chatShareViewTextState
    val chatShareViewMsgIdState = remember { mutableStateOf(0L) }
    var chatShareViewMsgId by chatShareViewMsgIdState
    // 「选择到这里」：锚点消息 id 与按钮出现方向（+1 上滑看更早→顶侧, -1 下滑看更新→底侧, 0 不显示）
    val selectAnchorMsgIdState = remember { mutableStateOf(-1L) }
    var selectAnchorMsgId by selectAnchorMsgIdState
    val selectToHereDirState = remember { mutableStateOf(0) }
    var selectToHereDir by selectToHereDirState
    fun exitMultiSelect() {
        multiSelectMode = false
        selectedMsgIds.value = emptySet()
        selectAnchorMsgId = -1L
        selectToHereDir = 0
    }
    // 高亮消息 ID（点击引用胶囊 / 搜索跳转定位后闪烁）
    val highlightedMsgIdState = remember { mutableStateOf(0L) }
    var highlightedMsgId by highlightedMsgIdState
    /**
     * 定位完成后的「闪一下」：纯展示动作，不改任何布局。
     *
     * 抽出来是因为它以前散在两个入口里，各自配一串固定延时
     * （等 isScrollInProgress → delay(100) → 高亮 → delay(1600) → 熄灭），
     * 和真实布局没有同步关系，慢机型上高亮会在滚动途中就亮起来。
     * 现在定位是同步收敛的（要么零位移、要么一次性位移），闪就只需管自己：
     * 到点亮、到点灭，并由 id 自校验收尾，不会被下一次跳转误清。
     */
    suspend fun flashMessage(msgId: Long) {
        highlightedMsgId = msgId
        kotlinx.coroutines.delay(1500)
        if (highlightedMsgId == msgId) highlightedMsgId = 0L
    }
    // 「查找聊天记录」点结果后要定位的消息 id（由列表内的 LaunchedEffect 消费）
    val jumpToMessageIdState = remember { mutableStateOf(0L) }
    var jumpToMessageId by jumpToMessageIdState
    // 图片全屏预览：当前对话全部图片 URL 列表（懒加载）+ 点击起始索引，支持左右滑动切换
    val imagePreviewUrlsState = remember { mutableStateOf<List<String>>(emptyList()) }
    var imagePreviewUrls by imagePreviewUrlsState
    val imagePreviewIndexState = remember { mutableStateOf(0) }
    var imagePreviewIndex by imagePreviewIndexState
    // 图片缩略图屏幕矩形登记表（供全屏 hero 动画从原位置放大/缩回校准）
    val imageBounds = remember { ImageBoundsRegistry() }
    // 全屏预览关闭动作（由预览层挂载时覆写为带 hero 退出的关闭；默认直接清空列表）
    val imagePreviewCloserState = remember { mutableStateOf<() -> Unit>({ imagePreviewUrls = emptyList() }) }
    var imagePreviewCloser by imagePreviewCloserState
    // hero 动画状态（Animatable 按 previewSession 键控：每次打开都是全新实例（初值 0f/1f），
    // 彻底消除上一次关闭残留进度导致「点击瞬间黑底+图停在全屏位置闪一下」的问题）
    val heroInitialIndexState = remember { mutableStateOf(0) }
    var heroInitialIndex by heroInitialIndexState
    val previewSessionState = remember { mutableStateOf(0) }
    var previewSession by previewSessionState
    val heroProgress = remember(previewSession) { Animatable(0f) }
    val exitFade = remember(previewSession) { Animatable(1f) }
    val heroClosingState = remember { mutableStateOf(false) }
    var heroClosing by heroClosingState
    // 全屏预览当前起始图的原图是否已加载完成（未完成前 hero 层不撤，避免放大完了才弹出原图）
    val previewReadyState = remember { mutableStateOf(false) }
    var previewReady by previewReadyState
    // hero 进入动画是否播完（只在首尾翻转一次，动画期间不随进度重组整层——这是丝滑的关键）
    val heroEnterDoneState = remember { mutableStateOf(false) }
    var heroEnterDone by heroEnterDoneState
    // 关闭动画途中再点一下 → 回弹（撤销关闭）。没有它，220ms 内的点击会被直接吞掉。
    val heroReopeningState = remember { mutableStateOf(false) }
    var heroReopening by heroReopeningState
    // 打开瞬间**冻结**的缩略图屏幕矩形。绝不能动画期间再读实时矩形：
    // 打开预览会隐藏系统栏 → 聊天页 statusBarsPadding 归零 → 顶栏/消息列表整体上移 →
    // 缩略图 onGloballyPositioned 上报新矩形 → heroTransform 中途重算 → 图片"跳一下"。
    val heroSourceRectState = remember { mutableStateOf<Rect?>(null) }
    var heroSourceRect by heroSourceRectState
    val showImageShareSheetState = remember { mutableStateOf(false) }
    var showImageShareSheet by showImageShareSheetState // 长按图片 → 「分享至」抽屉
    val videoPlayUrlState = remember { mutableStateOf("") }
    var videoPlayUrl by videoPlayUrlState // 应用内视频播放
    // ── 视频全屏播放器打开/关闭动画（与图片预览一致：从缩略图位置放大/缩回）──
    // 关闭时不能立刻清空 videoPlayUrl：先播完缩回再卸载播放器，否则画面会瞬间消失。
    var videoClosing by remember { mutableStateOf(false) }
    val videoHeroRectState = remember { mutableStateOf<Rect?>(null) }
    var videoHeroRect by videoHeroRectState // 打开瞬间冻结的缩略图矩形
    val videoOverlaySizeState = remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var videoOverlaySize by videoOverlaySizeState
    val videoSessionState = remember { mutableIntStateOf(0) }
    var videoSession by videoSessionState
    val videoProgress = remember(videoSession) { Animatable(0f) }   // 0=缩略图位置，1=全屏
    /**
     * hero 动画的起点矩形。
     *
     * [preferQuote] = true 时（点击引用胶囊里的缩略图）优先用「引用缩略图」上报的矩形：
     * 引用缩略图和正文那张图是屏幕上两个不同位置，只按 url 查会拿到正文的旧矩形，
     * 动画就会从别处飞出来。正文路径（preferQuote=false）只读正文自己的登记，两者互不干扰。
     */
    fun heroRectFor(rawUrl: String, preferQuote: Boolean = false): Rect? {
        val resolved = resolveMediaUrl(rawUrl)
        if (!preferQuote) return imageBounds.get(resolved)
        return imageBounds.getQuote(resolved) ?: imageBounds.get(resolved)
    }
    fun openVideoPlayer(url: String, preferQuote: Boolean = false) {
        videoHeroRect = heroRectFor(url, preferQuote)
        videoClosing = false
        videoSession++
        videoPlayUrl = url
    }
    fun closeVideoPlayer() {
        if (videoPlayUrl.isEmpty() || videoClosing) return
        videoClosing = true
        val s = videoSession
        scope.launch {
            videoProgress.animateTo(0f, tween(180, easing = FastOutLinearInEasing))
            // 动画期间又打开了另一个视频：别把新打开的那个关掉
            if (videoSession == s) {
                videoPlayUrl = ""
                videoClosing = false
            }
        }
    }
    // 顶栏顶部安全区（稳定值）：全屏看图/看视频隐藏系统栏时不会跟着 inset 变化，
    // 退出全屏后系统栏显隐动画也不会把顶栏"从上往下"顶一下。
    val headerTopPadding = rememberStableStatusBarTopPadding(
        freeze = imagePreviewUrls.isNotEmpty() || videoPlayUrl.isNotEmpty()
    )
    // 消费内联工具循环投递的 play_video 请求（文件级中转 → 本状态）→ 弹出应用内全屏播放器。
    // 内联循环在 videoPlayUrl 声明之前执行，无法直接赋值，故经 pendingOpenVideoUrl 中转。
    androidx.compose.runtime.LaunchedEffect(pendingOpenVideoUrl.value) {
        val u = pendingOpenVideoUrl.value
        if (u.isNotBlank()) {
            openVideoPlayer(u)
            pendingOpenVideoUrl.value = ""
        }
    }
    // 文本文件预览（TXT/MD/LOG 等）：target 保存内容（退出动画期间仍保留），visible 控制滑入/滑出
    val filePreviewTargetState = remember { mutableStateOf<Pair<String, String>?>(null) }
    var filePreviewTarget by filePreviewTargetState
    val filePreviewVisibleState = remember { mutableStateOf(false) }
    var filePreviewVisible by filePreviewVisibleState
    // 打开任何全屏预览（看图 / 看视频 / 看 TXT 等文本）时立刻收起输入法并清掉输入框焦点：
    // 以前输入法还开着，点开图片只看到半屏图，另一半被键盘盖住，必须手动点一下才收得回去。
    val imeKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeFocusManager = LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(
        imagePreviewUrls.isNotEmpty(), videoPlayUrl.isNotEmpty(), filePreviewVisible
    ) {
        if (imagePreviewUrls.isNotEmpty() || videoPlayUrl.isNotEmpty() || filePreviewVisible) {
            imeFocusManager.clearFocus(force = true)
            imeKeyboard?.hide()
        }
    }
    // 非预览类文件（APK 等）下载确认弹窗：url, fileName, ext
    val fileDownloadConfirmState = remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var fileDownloadConfirm by fileDownloadConfirmState
    // JPG/PNG 等图片文件：保存到本地相册确认弹窗：url, fileName, ext
    val imageSaveConfirmState = remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var imageSaveConfirm by imageSaveConfirmState

    // 把聊天里的图片保存到本地相册（Pictures/AuroraChat）
    fun saveChatImageToGallery(url: String) {
        scope.launch {
            val ok = com.aurora.chat.util.GallerySaver.saveImage(ctx, url)
            android.widget.Toast.makeText(ctx, if (ok) "已保存到相册" else "保存失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 触发「另存为」：先记录源文件信息，再拉起系统文件管理器（用户自选目录/改名），随后把源数据拷贝过去
    fun startFileSaveAs(url: String, fileName: String, ext: String) {
        pendingSaveAs = Triple(url, fileName, ext)
        try {
            saveAsLauncher.launch(fileName.ifEmpty { "文件" })
        } catch (e: Exception) {
            pendingSaveAs = null
            android.widget.Toast.makeText(ctx, "无法打开保存对话框", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 本地来源判定：file:// / content:// / 裸绝对路径。
     * 这类源只存在于本机磁盘；下载管理器只认 http(s)，交给它处理必然失败。
     */
    fun isLocalFileSource(url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")) return false
        return url.startsWith("file://") || url.startsWith("content://") || url.startsWith("/")
    }

    /**
     * 下载聊天里的文件。
     *
     * - 远程（http / https）：交给下载管理（悬浮球 + 百分比 + 速度 + 重试）。
     * - 本地（file:// / content:// / 绝对路径）：AI 从工作区取出的文件、本地上传的文件都属此类。
     *   这类文件**绝不进下载队列** —— OkHttp 不认 file scheme、也无权直接读 content://，
     *   走网络必然 100% 失败；正确做法是直接弹系统「另存为」，由用户选择目录落盘。
     */
    fun startChatFileDownload(url: String, fileName: String) {
        if (isLocalFileSource(url)) {
            // 源文件已被清理时提前告知，免去用户白走一趟「另存为」
            if (url.startsWith("file://") && !java.io.File(url.removePrefix("file://")).exists()) {
                android.widget.Toast.makeText(ctx, "源文件不存在或已被移除", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            startFileSaveAs(url, fileName, fileName.substringAfterLast('.', "").lowercase())
            return
        }
        val name = fileName.ifEmpty { "文件下载" }
        com.aurora.chat.ui.tools.DownloadManager.addCommunityDownload(
            name,
            fileName.ifEmpty { "download_${System.currentTimeMillis()}" },
            url,
            com.aurora.chat.R.drawable.ic_app_icon
        )
        com.aurora.chat.ui.tools.DownloadManager.startDownloads(ctx)
    }

    // 引用恢复：服务端返回的消息若无引用字段，从旧本地消息中按 ID 恢复
    val restoreReplyInfo: (List<IMessageRef>) -> List<IMessageRef> = { fromServer ->
        val oldById = messages.associateBy { it.id }
        fromServer.map { newMsg ->
            if (newMsg.replyToText.isNotEmpty()) return@map newMsg
            oldById[newMsg.id]?.let { old ->
                if (old.replyToText.isNotEmpty())
                    return@map ChatMsg(
                        id = old.id, text = newMsg.text, isMine = newMsg.isMine,
                        fromUserId = newMsg.fromUserId, senderName = newMsg.senderName,
                        createdAt = newMsg.createdAt,
                        isRevoked = newMsg.isRevoked, isSystemNotice = newMsg.isSystemNotice,
                        replyToText = old.replyToText, replyToSender = old.replyToSender,
                        replyToId = old.replyToId, mediaType = newMsg.mediaType,
                        mediaUrl = newMsg.mediaUrl, flashDuration = newMsg.flashDuration,
                        toUserId = newMsg.toUserId, targetName = newMsg.targetName,
                        // 保留本机时间快照，避免每次服务端拉取重建消息都把 time 刷新成当下时刻
                        time = old.time
                    )
            }
            newMsg
        }
    }


    val deleteConfirmMsgIdState = remember { mutableStateOf(0L) }
    var deleteConfirmMsgId by deleteConfirmMsgIdState

    // 群管理：长按成员气泡 →「禁言」。0L 表示未打开禁言面板；
    // 打开后直接复用群设置里那套「禁言成员」面板（同一份 UI，不另写）。
    val muteTargetUserIdState = remember { mutableStateOf(0L) }
    var muteTargetUserId by muteTargetUserIdState
    val muteTargetUserNameState = remember { mutableStateOf("") }
    var muteTargetUserName by muteTargetUserNameState
    // 当前用户是群主（与 isAdminOrOwner 区分，仅用于文案「群主/管理员 禁言了 X」）
    val isGroupOwnerState = remember { mutableStateOf(false) }
    var isGroupOwner by isGroupOwnerState

    // 已删除消息 ID 集合（持久化到文件，避免 SP 大字符串读写）
    val deletedMessageIdsState = remember { mutableStateOf(
        try {
            val delFile = java.io.File(ctx.filesDir, "deleted_msgs_${currentUserId}.json")
            if (delFile.exists()) {
                val arr = org.json.JSONArray(delFile.readText())
                val set = mutableSetOf<Long>()
                for (i in 0 until arr.length()) set.add(arr.getLong(i))
                set
            } else emptySet()
        } catch (_: Exception) { emptySet() }
    ) }
    var deletedMessageIds by deletedMessageIdsState
    val focusManager = LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    val showGroupSettingsState = remember { mutableStateOf(false) }
    var showGroupSettings by showGroupSettingsState
    val showFriendSettingsState = remember { mutableStateOf(false) }
    var showFriendSettings by showFriendSettingsState
    // 全屏「查找聊天记录」（从好友资料 / 群聊信息进入）
    val showChatHistorySearchState = remember { mutableStateOf(false) }
    var showChatHistorySearch by showChatHistorySearchState
    // 搜索状态提升：关闭后再打开，关键字与结果都还在，不用重新搜
    val chatSearchState = remember(friendId) { ChatSearchState() }
    // 从搜索结果跳回会话后，返回键应先回到搜索页（而不是退出会话）
    val searchReturnPendingState = remember { mutableStateOf(false) }
    var searchReturnPending by searchReturnPendingState
    val showProfileState = remember { mutableStateOf(false) }
    var showProfile by showProfileState
    val profileUserIdState = remember { mutableStateOf(0L) }
    var profileUserId by profileUserIdState
    val profileUserNameState = remember { mutableStateOf("") }
    var profileUserName by profileUserNameState
    // 返回记忆：由 MainActivity 在切回群聊时传入“待恢复资料”，自动重新打开对应成员资料页，
    // 从而还原「群聊 + 资料页」双层界面；消费后置空避免重复触发
    LaunchedEffect(restoreProfile) {
        if (restoreProfile != null) {
            profileUserId = restoreProfile.first
            profileUserName = restoreProfile.second
            showProfile = true
            onRestoreProfileConsumed()
        }
    }
    // 入群邀请应答页：非空时全屏显示
    val inviteRespondState = remember { mutableStateOf<InviteGroupCardData?>(null) }
    var inviteRespond by inviteRespondState
    val showAiUsageState = remember { mutableStateOf(false) }
    var showAiUsage by showAiUsageState
    // 选择免费 API（由用户贡献、开发者审核通过）：顶层全屏，避免被 AI 使用面板（Dialog 模态层）遮挡
    val showFreeApiSelectState = remember { mutableStateOf(false) }
    var showFreeApiSelect by showFreeApiSelectState
    // 选择付费 API（已上架）：顶层全屏
    val showPaidApiSelectState = remember { mutableStateOf(false) }
    var showPaidApiSelect by showPaidApiSelectState
    // 群聊「N人已读」点开的已读名单抽屉：当前查看的 serverMessageId（0 = 关闭）。
    // 必须声明在屏幕顶层——抽屉是盖在整屏之上的覆盖层，写在消息区 Box 里会在那个作用域外不可见。
    val readSheetMsgIdState = remember(friendId) { mutableStateOf(0L) }
    var readSheetMsgId by readSheetMsgIdState
    // 「AI 设置」里「开启新对话」的确认弹窗开关（与主界面「开启新对话」弹窗走同一安全清空序列）
    val showAiNewConfirmState = remember { mutableStateOf(false) }
    var showAiNewConfirm by showAiNewConfirmState
    val showAiNameInputState = remember { mutableStateOf(false) }
    var showAiNameInput by showAiNameInputState
    val aiNameInputState = remember { mutableStateOf("") }
    var aiNameInput by aiNameInputState

    // AI 自定义头像：选取图片并保存到本地文件，替换对话中的 AI 头像
    val aiAvatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val file = LocalStorage.getAiAvatarFile(ctx)
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            java.io.FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        AiAvatarState.bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    } catch (_: Exception) {}
                }
            }
        }
    }



    // 设置页 / 模板页是文件级状态，退出聊天板块时收掉，避免下次进来还停在子页
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { showAiSettings.value = false }
    }
    // 好友资料返回：先回对话，不直接退出聊天板块（补充缺失的 BackHandler）
    BackHandler(enabled = showFriendSettings) { showFriendSettings = false }
    // 群设置返回：先回对话，不直接跳到聊天板块
    BackHandler(enabled = showGroupSettings) { showGroupSettings = false }
    // 用户资料返回：先回对话，不直接跳到聊天板块
    BackHandler(enabled = showProfile && profileUserId > 0) { showProfile = false }
    // 多选状态下返回键直接退出多选
    BackHandler(enabled = multiSelectMode) { exitMultiSelect() }
    // 长按菜单打开时，返回键先关闭菜单（Popup 改为非 focusable 后不再自行处理返回键）
    BackHandler(enabled = openMenuMsgId != 0L) { openMenuMsgId = 0L }
    // 红包卡片长按菜单打开时，返回键先关闭它
    BackHandler(enabled = redPacketMenuMsgId != 0L) { redPacketMenuMsgId = 0L }
    // AI 使用面板返回：先关面板，不穿透到关闭会话
    BackHandler(enabled = showAiUsage) { showAiUsage = false }
    // 选择免费/付费 API 全屏页返回：先回 AI 使用面板（只退一层），不穿透到聊天/退出
    BackHandler(enabled = showFreeApiSelect) { showFreeApiSelect = false; showAiUsage = true }
    BackHandler(enabled = showPaidApiSelect) { showPaidApiSelect = false; showAiUsage = true }

    val animPrefs = LocalStorage.getChatPrefs(ctx)
    val animMode by remember {
        derivedStateOf { AnimationMode.entries[animPrefs.getInt("animation_mode", 0)] }
    }
    val advancedAnim by remember {
        derivedStateOf { animPrefs.getBoolean("advanced_anim", false) }
    }

    // 我方气泡配色：每次重组都从 BubbleColorStorage 读取最新值（改色后返回聊天即可生效）
    val myBubbleColor = BubbleColorStorage.myBubbleColor(ctx)
    // 对方气泡配色：仅本地视角生效
    val otherBubbleColor = BubbleColorStorage.otherBubbleColor(ctx)

    // 带时间戳的最近发送文本集合（防止 TCP/polling 触发 loadMessages 时重复添加自己发出的消息）
    // 用时间戳确保：消息发送后 30 秒内不被服务端同步重复添加
    val recentlySentMessages = remember { mutableMapOf<String, Long>() }

    // ── 入群申请相关状态（提升到 Column 外部，避免 AnimatedVisibility 内引用不到）──
    val isAdminOrOwnerState = remember { mutableStateOf(false) }
    var isAdminOrOwner by isAdminOrOwnerState
    val pendingCountState = remember { mutableIntStateOf(0) }
    var pendingCount by pendingCountState
    // 非群成员（含「入群需审核」待审核态）：user_role 为空
    val isNotGroupMemberState = remember { mutableStateOf(false) }
    var isNotGroupMember by isNotGroupMemberState
    // 群公告弹窗：进群聊时自动弹出 popup_enabled 且本机未看过的公告
    val popupAnnouncementState = remember { mutableStateOf<GroupAnnouncementData?>(null) }
    var popupAnnouncement by popupAnnouncementState

    // ── 非好友私聊横幅状态（与「入群申请横幅」同一位置，不同文案与触发机制）──
    // null = 尚未确定（加载中，不显示横幅，避免接口返回前闪一下）；true/false = 服务端结果
    val isFriendChatState = remember { mutableStateOf<Boolean?>(null) }
    var isFriendChat by isFriendChatState
    // 手动触发好友关系重新判定（如在本会话里刚删除/重新添加好友后，立即刷新横幅）
    val friendStatusRefreshState = remember { mutableIntStateOf(0) }
    var friendStatusRefresh by friendStatusRefreshState
    // ── 转账 / 发红包门控 ──
    // 非好友 / 你拉黑对方 / 对方拉黑你 / 对方开启好友验证 等场景下，禁用「转账」与「发红包」。
    // 默认 false（允许）：仅私聊进入时按好友关系 + 拉黑状态重新判定（群聊 / 本地 / AI 不受影响）。
    val payDisabledState = remember { mutableStateOf(false) }
    var payDisabled by payDisabledState
    val payBlockReasonState = remember { mutableStateOf<String?>(null) }
    var payBlockReason by payBlockReasonState
    // 「发送好友申请」全屏面板开关（复用已有的打招呼面板）
    val showAddFriendGreetingState = remember { mutableStateOf(false) }
    var showAddFriendGreeting by showAddFriendGreetingState
    // 切换会话时关闭该面板，避免残留在新会话上
    LaunchedEffect(friendId) {
        showAddFriendGreeting = false
        showAiSettings.value = false
        // 切会话后不该再「返回搜索」，搜索层也一并关掉
        showChatHistorySearch = false
        searchReturnPending = false
    }
    // 「发送好友申请」面板返回：先关面板，不穿透到关闭会话
    BackHandler(enabled = showAddFriendGreeting) { showAddFriendGreeting = false }

    LaunchedEffect(Unit) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 平台头像（所有对话共用）
            val platformFile = LocalStorage.getMyAvatarFile(ctx, currentUserId)
            if (platformFile.exists()) {
                myAvatarBitmap = android.graphics.BitmapFactory.decodeFile(platformFile.absolutePath)
            }
            // AI 自定义头像：存在则载入全局状态，替换默认 AI 头像
            val aiFile = LocalStorage.getAiAvatarFile(ctx)
            if (aiFile.exists()) {
                AiAvatarState.bitmap = android.graphics.BitmapFactory.decodeFile(aiFile.absolutePath)
            }
        }
    }

    // 加载禁言状态
    LaunchedEffect(currentUserId, friendId) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val result = com.aurora.chat.data.api.AuroraApi.checkMuteStatus()
                if (result.success && result.data != null) {
                    val expiresAt = result.data.optLong("expires_at", 0L)
                    val muteType = result.data.optInt("mute_type", 0)
                    if (expiresAt > 0 && (muteType == 2 || muteType == 3)) {
                        mutedUntil = expiresAt
                    } else {
                        mutedUntil = 0L
                    }
                }
            } catch (_: Exception) {
                mutedUntil = 0L
            }
        }
    }

    // ── 消息加载（初始加载不限量，由服务端返回全部；滚动到顶部继续追加）──
    val pageSize = 200  // 每批次加载量（非总上限）
    // 进入对话时首屏只渲染近屏条数（其余更旧消息驻留内存 store，滚到顶懒加载），
    // 避免大群几万条消息一次性建索引导致主线程 O(n) 卡顿
    val INITIAL_WINDOW = 120
    val DISK_PAGE = 60
    // 初始化期间（后台正在为磁盘消息全量建索引）为 true，禁止 TCP 增量合并并发写 store 导致错乱
    // 按 friendId 重置：切会话时归零，避免上一个会话的「正在加载」状态泄漏到新会话；
    // 否则一旦为真且未在 finally 复位，会永久拦截该会话的所有推送刷新。
    var initialLoadInProgress by remember(friendId) { mutableStateOf(false) }
    var hasMoreMessages by remember { mutableStateOf(true) }
    val loadMoreProgressState = remember { mutableIntStateOf(0) } // 0=空闲, 1=加载中, 2=无更多
    var loadMoreProgress by loadMoreProgressState
    // 本地库（SQLite）里是否还有比当前最早消息更旧的一页。
    // 上滑到顶时先吃本地库（零网络、服务端清理过的 30 天前历史也在这里），本地库用尽才去请求服务端分页。
    val dbHasOlderState = remember(friendId) { mutableStateOf(false) }
    var dbHasOlder by dbHasOlderState
    // 本地库分页的并发闸门：取页期间视口会被 scrollToItem 修正，snapshotFlow 会再次触发，
    // 没有闸门就会一口气连取好几页。用 remember 持有，避免重组把闸门重置成 false。
    var dbPagingBusy by remember(friendId) { mutableStateOf(false) }

        // 后台媒体上传事件：压缩中/上传中更新进度与阶段，完成后把占位(本地 id)替换为真实消息
        // 按 friendId 重启：切对话后闭包必须引用当前会话的 messages/进度表，否则进度与占位更新会打偏到旧会话
        LaunchedEffect(friendId) {
            com.aurora.chat.data.upload.MediaUploadManager.events.collect { ev ->
                when (ev) {
                    is com.aurora.chat.data.upload.UploadEvent.Compressing -> {
                        uploadStageMap[ev.localId] = "compressing"
                        uploadProgressMap[ev.localId] = ev.progress
                    }
                    is com.aurora.chat.data.upload.UploadEvent.Uploading -> {
                        uploadStageMap[ev.localId] = "uploading"
                        uploadProgressMap[ev.localId] = ev.progress
                    }
                    is com.aurora.chat.data.upload.UploadEvent.Failed -> {
                        // 失败不再静默：停在该状态并提示原因
                        uploadStageMap[ev.localId] = "failed"
                        android.util.Log.e("ChatConversation", "上传失败 ${ev.localId} permanent=${ev.permanent}: ${ev.message}")
                        if (ev.friendId == friendId) {
                            if (ev.permanent) {
                                // 终态失败（4xx/超限/被拉黑/好友验证/账号异常…）：重试无意义。
                                // 把占位气泡转成「红叹号失败气泡」留在原地并落盘留存，而不是默默消失；
                                // 用户点红叹号可手动重发（走一遍重发流程，失败就继续挂着）。
                                val sf = classifySendFailure(ev.message)
                                if (sf != null && sf.logout) {
                                    // 账号已废 / 登录失效：踢下线，不留气泡、不打印广播
                                    AuroraApi.showErrorToast(ctx, sf.text)
                                    scope.launch { ChatRepository.checkAndLogoutCurrentUser(ctx, currentUserId) }
                                } else {
                                    pendingUploadPlaceholders.removeAll { it.id == ev.localId }
                                    messages.removeAll { it.id == ev.localId }
                                    uploadProgressMap.remove(ev.localId)
                                    uploadStageMap.remove(ev.localId)
                                    uploadFileNameMap.remove(ev.localId)
                                    uploadRatioMap.remove(ev.localId)
                                    val createdAt = System.currentTimeMillis() / 1000
                                    val localId = -(System.currentTimeMillis() * 1000L + (0..999).random())
                                    // 媒体已上传成功（mediaUrl 非空）→ 渲染真实媒体 + 红叹号；
                                    // 否则（上传阶段就失败）用占位文案 + 红叹号，用户重发时会重新上传。
                                    val bubbleText = if (ev.mediaUrl.isNotEmpty()) "" else when (ev.mediaType) {
                                        "image" -> "[图片]"; "video" -> "[视频]"; "voice" -> "[语音]"
                                        "gif" -> "[动图]"; "file" -> "[文件]"; else -> "[消息]"
                                    }
                                    val failed = ChatMsg.create(
                                        text = bubbleText, isMine = true, fromUserId = currentUserId,
                                        createdAt = createdAt, mediaType = ev.mediaType, mediaUrl = ev.mediaUrl,
                                        flashDuration = ev.flashDuration
                                    ).copy(id = localId, sendFailed = true)
                                    pendingFailedMessages.add(failed)
                                    messages.add(failed)
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        com.aurora.chat.data.local.FailedMessageStore.save(
                                            ctx,
                                            com.aurora.chat.data.local.FailedMessage(
                                                id = localId, friendId = friendId, text = bubbleText,
                                                mediaType = ev.mediaType, mediaUrl = ev.mediaUrl,
                                                flashDuration = ev.flashDuration, createdAt = createdAt
                                            )
                                        )
                                    }
                                    if (sf != null) {
                                        // 打印本地广播（居中系统提示）并留存，像微信那样挂在该会话里
                                        addLocalNotice(friendId, sf.text, type = "send_blocked")
                                    }
                                    // 其余终态失败不再弹任何提示：气泡本身就是红感叹号，
                                    // 点它即可重发，弹窗只会制造噪音。
                                }
                            }
                            // 瞬时失败（网络/超时/5xx/服务端限流）：保留占位，后台自动重试。
                            // 不弹任何提示——气泡上会显示「发送失败，重试中...」，
                            // 真正的终态失败走上面的红感叹号分支。
                        }
                    }
                    is com.aurora.chat.data.upload.UploadEvent.Succeeded -> {
                        // 这条完成事件是否对应本屏真实的「上传占位」：只有真占位才允许补消息，
                        // 否则（切会话回来、服务端数据已含该消息）补进去就是重复气泡。
                        val hadPlaceholder = pendingUploadPlaceholders.any { it.id == ev.localId }
                        // 上传完成：从待上传占位列表移除，避免下次 syncMessages 再带出旧占位
                        pendingUploadPlaceholders.removeAll { it.id == ev.localId }
                        // 语音只有几十 KB，上传往往一两帧就结束，气泡会直接从 0% 变成语音卡片。
                        // 先把进度钉到 100%、停 220ms 让数字走完，再替换占位消息。
                        if (ev.mediaType == "voice") {
                            uploadProgressMap[ev.localId] = 1f
                            kotlinx.coroutines.delay(220)
                        }
                        if (ev.friendId == friendId) {
                            // 语音把秒数写进 text（与上传链路一致），其它类型仍为空串
                            val doneText = if (ev.mediaType == "voice") ev.voiceDuration.toString() else ""
                            val idx = messages.indexOfFirst { it.id == ev.localId }
                            if (idx >= 0) {
                                // 沿用占位气泡的「发送时刻」：上传可能耗时几十秒，若用「此刻」，
                                // 消息时间会整体后移，夹在上传期间发出的其它消息之间错位。
                                val sentAt = (messages[idx] as? ChatMsg)?.createdAt ?: 0L
                                messages[idx] = ChatMsg.create(serverId = ev.serverId, text = doneText, isMine = true, fromUserId = currentUserId, isNew = true, mediaType = ev.mediaType, mediaUrl = ev.mediaUrl, flashDuration = ev.flashDuration, mediaWidth = if (ev.mediaType == "voice") ev.voiceDuration else ev.mediaWidth, mediaHeight = ev.mediaHeight, createdAt = if (sentAt > 0) sentAt else System.currentTimeMillis() / 1000)
                            } else if (hadPlaceholder) {
                                // 占位确实在本屏，但已被某次全量刷新冲掉（列表刚重建）：
                                // 把真实消息直接补进列表，否则气泡会停在 100% 等下一次服务端同步才出现。
                                // 用的是服务端真实 id，后续 syncMessages 按 id 去重，不会出现重复气泡。
                                messages.add(
                                    ChatMsg.create(
                                        serverId = ev.serverId, text = doneText, isMine = true,
                                        fromUserId = currentUserId, isNew = true, mediaType = ev.mediaType,
                                        mediaUrl = ev.mediaUrl, flashDuration = ev.flashDuration,
                                        mediaWidth = if (ev.mediaType == "voice") ev.voiceDuration else ev.mediaWidth,
                                        mediaHeight = ev.mediaHeight, createdAt = System.currentTimeMillis() / 1000
                                    )
                                )
                            }
                            val previewText = when (ev.mediaType) { "image" -> "[图片]"; "video" -> "[视频]"; "file" -> "[文件]"; "voice" -> "[语音]"; "transfer" -> "[转账]"; else -> "[消息]" }
                            com.aurora.chat.ui.viewmodel.ChatViewModel.updateConversationPreview(ev.friendId, previewText, System.currentTimeMillis() / 1000)
                        }
                        uploadProgressMap.remove(ev.localId)
                        uploadStageMap.remove(ev.localId)
                        uploadRatioMap.remove(ev.localId)
                        uploadFileNameMap.remove(ev.localId)
                    }
                    is com.aurora.chat.data.upload.UploadEvent.Cancelled -> {
                        // 用户主动撤回上传中的媒体：移除占位，不留失败态、不提示"自动重试"
                        pendingUploadPlaceholders.removeAll { it.id == ev.localId }
                        messages.removeAll { it.id == ev.localId }
                        uploadProgressMap.remove(ev.localId)
                        uploadStageMap.remove(ev.localId)
                        uploadRatioMap.remove(ev.localId)
                        uploadFileNameMap.remove(ev.localId)
                    }
                }
            }
        }

        // 后台 AI 回复实时同步：发完消息立即离开对话再回来时，未完成的回复在此继续刷新到消息列表。
        // 仅当该回复属于当前 AI 会话（convId == aiConvTag）才同步，避免上一个会话的后台回复污染新会话。
        // 【性能】改用 aiReplyState.collect 在协程内直接消费流，而非用 collectAsState() 把状态读进重组作用域：
        // 流式回复每 token 都会更新 aiReplyState，若读进重组作用域会导致整个对话页/LazyColumn 每次增量都全量重组（大输出卡死）。
        // 现在只有被更新到的那一条消息会被重组，其余单元格全部跳过。
        LaunchedEffect(friendId, isLoadingMessages) {
            if (friendId != AI_CHAT_ID) return@LaunchedEffect
            aiReplyState.collect { reply ->
                val r = reply ?: return@collect
                if (AiConversationSession.activeConvId != r.convId) return@collect   // 来自其它会话/被清空的会话 → 忽略
                // 【消息重载期间不写】重进会话时 isLoadingMessages 会先 true 再 false（本地 JSON 会被整表替换），
                // 此刻写进去的增量马上就会被随后的加载覆盖掉。本 LaunchedEffect 的 key 含 isLoadingMessages，
                // 加载完成后它会自动重启并重放 aiReplyState 的当前值（StateFlow 语义），所以这里直接跳过不会漏。
                if (isLoadingMessages) return@collect
                // 定位目标消息（collect 每增量只查一次，O(n) 可忽略；热路径 onDelta 等已走 O(1) 索引快照）
                var idx = messages.indexOfFirst { m -> m.id == r.aiMsgId }
                if (idx < 0) {
                    // 已结束（完成/出错）的回复不进补建：它必定已随收尾落盘，列表里找不到只有一种解释 ——
                    // 用户自己删掉了这条消息，此时"复活"它是错的。这里只负责**在途回复**的重建。
                    if (r.done || r.error != null) return@collect
                    // 【切出再进来必须能自愈】AI 回复的气泡有可能整个不在列表里：
                    //   · 切出对话时本地 JSON 尚未落盘 / 落盘被进程回收打断 → 重进加载出的历史里根本没有它；
                    //   · 新建会话、缓存过期等路径把消息表整体替换掉。
                    // 旧实现到这一步直接 `return@collect` —— 于是这条回复**永远不会再出现在界面上**，
                    // 「等待模型响应」和计时一旦丢失就永久丢失，只有用户再发一条新消息才会重新触发（用户反馈原话）。
                    // 现在：优先复用末尾那条「还没写完的 AI 气泡」（把 id 校正到后台回复 id），实在没有就补建一条。
                    val reuse = (messages.lastIndex downTo 0).firstOrNull { i ->
                        val m = messages[i]
                        m is ChatMsg && !m.isMine && !m.isSystemNotice && !m.isToolStatus &&
                            m.fromUserId == AI_CHAT_ID && !m.manualStopped &&
                            (m.aiWaiting || m.text.isBlank() || m.text == AI_THINKING ||
                                (r.text.isNotBlank() && m.text == AiChatManager.stripMultiSplit(r.text)))
                    }
                    if (reuse != null) {
                        messages[reuse] = (messages[reuse] as ChatMsg).copy(id = r.aiMsgId, serverId = r.aiMsgId)
                        idx = reuse
                    } else {
                        messages.add(ChatMsg.create(
                            serverId = r.aiMsgId, text = "", isMine = false, fromUserId = AI_CHAT_ID,
                            senderName = AiNameState.name ?: "AI", isNew = false,
                            createdAt = System.currentTimeMillis() / 1000
                        ).copy(aiWaiting = true))
                        idx = messages.size - 1
                    }
                    // 流式目标重新指回这条消息：否则渲染层的 liveNow 判定继续落空（思考卡片/交付扫光一律不出现）
                    if (!r.done && r.error == null) aiStreamMsgId.value = r.aiMsgId
                    // 补建/复用后只允许这一条处于等待态：旧目标（或索引漂移留下的占位）立即收口，
                    // 否则会出现两条「等待模型响应」，其中旧的永远没人再写、秒数一直跳
                    closeStaleAiWaiting(r.aiMsgId)
                }
                val showReasoning = AiChatManager.getShowReasoning(ctx, currentUserId)
                val cur = messages[idx] as ChatMsg
                // 【P0-2】用户手动停止后不再被流式增量覆盖（保护在单一写入路径统一判断）。
                // 例外：仍是等待态时必须放行——否则「停止」标记会把「等待模型响应」永久定格在屏上。
                if (cur.manualStopped && !cur.aiWaiting) return@collect
                // 任务已结束(正常完成 done / 出错 error)：把最终内容合并进消息并退出等待态，
                // 兜底「离开对话期间磁盘异步落盘尚未完成/失败」的场景——修复切回时读到旧占位、
                // 或已生成内容整段消失。onDone/onError 已把最新文本(出错时含「生成中断」提示)
                // 写入 aiReplyState.text，这里原样合并即可。
                if (r.done || r.error != null) {
                    val doneText = AiChatManager.stripMultiSplit(r.text)
                    val mergedText = if (doneText.isNotBlank()) doneText
                        else if (r.error != null && (cur.text.isBlank() || cur.text == AI_THINKING)) "（生成中断：${r.error}）"
                        else cur.text
                    val mergedReasoning = if (showReasoning) r.reasoningText else ""
                    if (cur.text != mergedText || cur.reasoningText != mergedReasoning || cur.aiWaiting) {
                        messages[idx] = cur.copy(
                            text = mergedText,
                            reasoningText = mergedReasoning,
                            aiWaiting = false
                        )
                        scheduleAiDebouncedSave()
                    }
                    // 本轮回复已终结：随时可能还有别的气泡停在等待态（旧目标 / 索引漂移的占位），统一收口
                    closeStaleAiWaiting(r.aiMsgId)
                    return@collect
                }
                val newText = AiChatManager.stripMultiSplit(r.text)
                // 尚未产生任何思考/输出 → 仍在「等待模型响应」空窗；否则立即退出
                val waiting = r.text.isBlank() && r.reasoningText.isBlank()
                if (cur.text != newText || cur.reasoningText != (if (showReasoning) r.reasoningText else "") || cur.aiWaiting != waiting) {
                    messages[idx] = cur.copy(
                        text = newText,
                        reasoningText = if (showReasoning) r.reasoningText else "",
                        aiWaiting = waiting
                    )
                    scheduleAiDebouncedSave()
                }
                // 流式同步期间顺手收口其它残留等待气泡（只有 keepId 这一条可以显示「等待模型响应」）。
                // 这里只遍历列表、且只写仍在等待态的那几条，正常情况下零写入，开销可忽略。
                closeStaleAiWaiting(r.aiMsgId)
            }
        }

        // 加载消息（热缓存 + 网络刷新）

    val selfChatRefresh by com.aurora.chat.ui.viewmodel.ChatViewModel.selfChatRefreshKey.collectAsState()
    // ── AI 多对话状态：进入/切换/新建会话用 ──
    val aiConvs = remember(friendId) { mutableStateListOf<AiConvMeta>() }
    var aiConvReloadTick by remember(friendId) { mutableStateOf(0) }
    val showAiConvDrawerState = remember(friendId) { mutableStateOf(false) }
    var showAiConvDrawer by showAiConvDrawerState
    // 初始值直接取内存里的会话全局（同步、无 IO）：旧写法初值 0L，要等 LaunchedEffect 才回填，
    // 这期间 aiInFlight 的「会话 id 相等」判定恒为假 → 发送按钮会从「停止」误跳成「发送」
    // （用户实测：任务没完成，右下角却显示可发送）。用同步初值堵掉这个窗口。
    // 【跨会话污染修复】初值只对 AI 对话取全局活跃会话 id；普通聊天/群聊一律 -1L ——
    // 否则 AI 在后台生成时进入普通聊天，这里会带上 AI 的会话 id，
    // aiWritingConvId == activeAiConvId 误成立 → 普通聊天也显示红色「停止」且无法发送。
    val activeAiConvIdState = remember(friendId) {
        mutableStateOf(if (friendId == AI_CHAT_ID) AiConversationSession.activeConvId else -1L)
    }
    var activeAiConvId by activeAiConvIdState
    // 进入 AI 对话：加载会话列表并复位当前会话 id（会话元数据存 SharedPreferences）
    LaunchedEffect(friendId) {
        if (friendId == AI_CHAT_ID) {
            showStickerPanel = false
            aiConvs.clear()
            aiConvs.addAll(loadAiConvs(ctx))
            activeAiConvId = getActiveAiConvId(ctx)
        }
    }
    // 新建一个 AI 对话（不覆盖已有对话）：置为当前并重载消息列表
    fun startNewAiConversation() {
        if (friendId != AI_CHAT_ID) return
        // 新建对话前先把当前会话内存快照落盘；不中断在途 AI 回复（协程跑在 aiGlobalScope，会按启动时绑定的 key 写回原会话）
        if (messages.isNotEmpty()) {
            try {
                val curKey = AiConversationSession.storageKey()
                val snap = messages.filter { !it.isSystemNotice }.toList()
                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { saveLocalChatMessages(ctx, currentUserId, curKey, snap) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        val meta = createAiConversation(ctx)
        activeAiConvId = meta.id
        aiConvs.add(0, meta) // 实时刷新列表：新会话立即出现在对话列表最顶部（loadAiConvs 已按创建时间降序，内存列表同步保持「最新在前」）
        aiConvReloadTick++
        android.widget.Toast.makeText(ctx, "已新建对话", android.widget.Toast.LENGTH_SHORT).show()
    }
    // 切换到某个已存在的对话
    fun openAiConversation(id: Long) {
        if (id == activeAiConvId) { showAiConvDrawer = false; return }
        // 不中断在途 AI 回复：回复协程跑在 aiGlobalScope 跨组合期存活，切回后由 aiReplyState.collect 按 convId 恢复渲染。
        // 切走前先把当前内存快照落盘到本会话文件，防止已输出内容在切走/切回间丢失
        if (friendId == AI_CHAT_ID && messages.isNotEmpty()) {
            try {
                val curKey = AiConversationSession.storageKey()
                val snap = messages.filter { !it.isSystemNotice }.toList()
                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try { saveLocalChatMessages(ctx, currentUserId, curKey, snap) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        switchAiConversation(ctx, id)
        activeAiConvId = id
        aiConvReloadTick++
        showAiConvDrawer = false
    }
    // 重命名指定会话（长按列表项 → 重命名，输入新名称后保存）
    fun renameAiConv(id: Long, title: String) {
        if (id <= 0L) return
        val t = title.trim()
        if (t.isBlank()) return
        renameAiConversation(ctx, id, t)
        val i = aiConvs.indexOfFirst { it.id == id }
        if (i >= 0) aiConvs[i] = aiConvs[i].copy(title = t)
    }
    // 删除某个会话（最后一个不允许删，避免无会话可用）
    fun deleteAiConv(id: Long) {
        if (aiConvs.size <= 1) {
            android.widget.Toast.makeText(ctx, "至少保留一个对话", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        deleteAiConversation(ctx, id) // 内部已持久化；若删的是当前会话会自动切换 active
        val i = aiConvs.indexOfFirst { it.id == id }
        if (i >= 0) aiConvs.removeAt(i)
        activeAiConvId = AiConversationSession.activeConvId
    }
    // 批量删除会话（至少保留一个）
    fun deleteAiConvs(ids: List<Long>) {
        if (ids.isEmpty()) return
        if (ids.size >= aiConvs.size) {
            android.widget.Toast.makeText(ctx, "至少保留一个对话", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        for (id in ids) deleteAiConversation(ctx, id) // 逐个删除并持久化，删的是当前会话时自动切换 active
        aiConvs.removeAll { it.id in ids }
        activeAiConvId = AiConversationSession.activeConvId
        aiConvReloadTick++
        android.widget.Toast.makeText(ctx, "已删除 ${ids.size} 个对话", android.widget.Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(friendId, selfChatRefresh, aiConvReloadTick, activeAiConvId, loadRetryTick) {
        // 每次进入 / 重试都先清掉上一轮的失败态：失败提示只在「本轮真的失败且无内容」时出现
        initialLoadFailed = false
        // 失败气泡与消息并行读取，并在各自分支「上屏之前」合并好 —— 保证首屏就是最终形态。
        // （若放到消息之后再异步补，就会出现「消息都显示完了，红叹号才慢半拍冒出来」。）
        val failedBubbles = loadFailedBubblesOnIo()
        // 先登记到内存态：后续任何一次 syncMessages 重建列表时都会把失败气泡一并带上
        pendingFailedMessages.clear()
        pendingFailedMessages.addAll(failedBubbles)
        if (isLocalChat(friendId)) {
            // AI 多对话：进入/切换会话时把当前会话 id 写入 session，加载对应会话的消息
            if (friendId == AI_CHAT_ID && activeAiConvId > 0L) {
                switchAiConversation(ctx, activeAiConvId)
            }
            // 本地对话（仅 AI 多会话）：从 JSON 文件加载，消息 ID 为时间戳*10000+随机，重启不丢失。
            // 个人对话已改为服务端自聊，走上面的网络私聊分支，不再有本地提示。
            val key = localChatKey(friendId)
            isLoadingMessages = true
            val deletedNow = deletedMessageIds.toSet()   // 快照，供 IO 线程读取
            // 【性能】AI 对话的 JSON 文件可能巨大（多轮长输出可达数百 KB）：文件读取 + JSON 解析 + 逐条解密
            // 全部移到 IO 线程，主线程只做状态写入，避免进入 AI 对话时卡顿
            val list = withContext(Dispatchers.IO) {
                val out = mutableListOf<ChatMsg>()
                val arr = loadLocalChatMessages(ctx, currentUserId, key)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        try {
                            // 单条消息损坏（缺字段 / 非对象）则跳过，绝不因一条坏数据让整屏崩溃
                            val obj = arr.optJSONObject(i) ?: continue
                            val msgId = obj.optLong("id", 0L)
                            // 统一用 deletedMessageIds 过滤（跟其他对话一样）
                            if (obj.optBoolean("revoked", false)) continue
                            // 工具状态行不落盘也不加载:运行期元素,任务收尾即收回,绝不遗留
                            if (obj.optBoolean("is_tool_status", false)) continue
                            if (msgId in deletedNow) continue
                            val isMine = obj.optBoolean("is_mine", msgId == 0L)
                            val fromUserId = obj.optLong("from_user_id", if (isMine) currentUserId else friendId)
                            val senderName = obj.optString("sender_name", if (isMine) currentUserName else friendName)
                            out.add(ChatMsg.create(
                                serverId = msgId,
                                text = obj.optString("text", ""),
                                isMine = isMine,
                                fromUserId = fromUserId,
                                isNew = false,
                                senderName = senderName,
                                createdAt = obj.optLong("created_at", 0),
                                mediaType = obj.optString("media_type", ""),
                                mediaUrl = obj.optString("media_url", ""),
                                reasoningText = obj.optString("reasoning_text", ""),
                                thinkingSeconds = obj.optLong("thinking_seconds", 0),
                                totalSeconds = obj.optLong("total_seconds", 0),
                                inputTokens = obj.optLong("input_tokens", 0),
                                outputTokens = obj.optLong("output_tokens", 0),
                                cacheHitTokens = obj.optLong("cache_hit_tokens", 0),
                                contextLength = obj.optLong("context_length", 0),
                                aiCostDeducted = obj.optLong("ai_cost_deducted", 0),
                                aiMode = obj.optString("ai_mode", ""),
                                aiUsageAuthoritative = obj.optBoolean("ai_usage_authoritative", false),
                                aiToolStage = obj.optString("ai_tool_stage", ""),
                                aiToolStageState = obj.optInt("ai_tool_stage_state", -1),
                                isToolStatus = obj.optBoolean("is_tool_status", false),
                                aiToolStages = parseToolStages(obj), aiFileChanges = obj.optString("ai_file_changes", ""), aiToolCallCount = obj.optInt("ai_tool_call_count", 0)
                            ).withKnownMediaType())
                        } catch (_: Exception) {
                            // 跳过损坏的单条消息
                        }
                    }
                }
                out
            }
            messages.clear()
            messages.addAll(list.sortedBy { if (it.createdAt == 0L) Long.MIN_VALUE else it.createdAt })
            restoreLocalNotices(friendId)
            applyFailedBubbles(failedBubbles)
            //  诊断：记录每条媒体消息的 mediaType/mediaUrl，定位「重进变空气泡」
            list.forEach { m ->
                if (m.mediaType.isNotEmpty()) {
                    val status = if (m.mediaUrl.isEmpty()) "URL为空(空气泡)" else "OK"
                    MediaDebug.log(ctx, "LOAD_LOCAL", "id=${m.id} mediaType=${m.mediaType} mediaUrl=${m.mediaUrl} -> $status")
                }
            }
            if (messages.isNotEmpty()) {
                // 延迟一帧再滚动：刚加载完消息时 LazyColumn 尚未 layout，立即 scrollToItem 会是空操作导致停在顶部
                try {
                    delay(60)
                    listState.scrollToItem(0)
                } catch (_: Exception) {}
            }
            scrollReady = true
            isLoadingMessages = false
            return@LaunchedEffect
        }

        // 通知系统：从本地存储加载，并与服务端持久化历史合并（确保离线/后端重启后不丢失）
        if (isNotificationChat(friendId)) {
            isLoadingMessages = true
            val localList = loadNotificationMessages(ctx, currentUserId).filter { it.id !in deletedMessageIds }.toMutableList()
            // 进入即清除未读角标
            setNotificationUnread(ctx, currentUserId, 0)
            // 从服务端拉取持久化通知，合并到本地（防止离线/后端重启导致通知丢失）
            try {
                val r = com.aurora.chat.data.api.AuroraApi.getSystemNotices()
                if (r.success && r.data != null) {
                    val existingTexts = localList.map { it.text }.toMutableSet()
                    // 存量通知是旧的「系统转账通知」格式、新模板是「系统订单通知」格式，
                    // 文本不同会让同一条通知被再加一次。按描述去重，保证旧数据不与服务端合并重复。
                    val existingDescs = localList.mapNotNull {
                        com.aurora.chat.ui.chat.parseTransferNotice(it.text)
                            ?: com.aurora.chat.ui.chat.parseOrderNotice(it.text)?.desc
                    }.toMutableSet()
                    for (item in r.data!!) {
                        // 已删除的通知（即便服务端仍有记录）也跳过，避免重新进入又出现
                        if (item.id in deletedMessageIds) continue
                        // 通知中心所有系统通知统一走同一份模板（与「更新通知」等一致），
                        // 不再按 tag 把转账特判成另一套卡片。
                        val text = com.aurora.chat.ui.chat.formatOrderNotice(item.title, item.desc, item.tag, item.orderId)
                        if (existingDescs.add(item.desc) && existingTexts.add(text)) {
                            localList.add(ChatMsg.create(
                                serverId = item.id, text = text, isMine = false, fromUserId = 0,
                                isSystemNotice = true, isNew = false, createdAt = item.createdAt
                            ))
                        }
                    }
                    // 合并后回写本地，确保即使后续离线也能看到完整历史
                    saveNotificationMessages(ctx, currentUserId, localList)
                }
            } catch (_: Exception) {}
            val list = localList
            if (list.isEmpty()) {
                list.add(ChatMsg.create(
                    text = "暂无通知",
                    isMine = false, fromUserId = 0, isSystemNotice = true, isNew = false
                ))
            }
            messages.clear()
            messages.addAll(list.sortedBy { if (it.createdAt == 0L) Long.MIN_VALUE else it.createdAt })
            if (messages.isNotEmpty()) {
                try { listState.scrollToItem(0) } catch (_: Exception) {}
            }
            scrollReady = true
            isLoadingMessages = false
            return@LaunchedEffect
        }

        // 非群成员（含待审核入群申请）：不加载、不展示任何群消息，避免越权读取/泄露历史
        if (isNotGroupMember) {
            messages.clear()
            scrollReady = true
            isLoadingMessages = false
            return@LaunchedEffect
        }

        // 第一步：群聊成员列表改为后台加载，不阻塞消息显示
        if (isGroupChat(friendId)) {
            launch {
                try {
                    val gid = -(friendId + 1000)
                    // 走仓库层：联网成功即写成员/名字缓存，断网时直接读上次的名单，
                    // @ 提及列表与身份徽章离线也能用。
                    val membersResult = com.aurora.chat.data.repository.ChatRepository.getGroupMembers(gid)
                    if (membersResult.success && membersResult.data != null) {
                        val members = mutableSetOf<String>()
                        val memberIdMap = mutableMapOf<String, Long>()
                        val roleMap = mutableMapOf<Long, String>()
                        val atList = mutableListOf<AtMember>()
                        for (i in 0 until membersResult.data!!.length()) {
                            val memberObj = membersResult.data!!.getJSONObject(i)
                            val userId = memberObj.optLong("user_id", 0)
                            val username = memberObj.optString("username", "")
                            val role = memberObj.optString("role", "")
                            if (userId > 0 && role.isNotEmpty()) roleMap[userId] = role
                            if (username.isNotEmpty()) {
                                members.add(username)
                                memberIdMap[username] = userId
                                atList.add(AtMember(userId, username, role == "owner"))
                            }
                        }
                        // 登记「昵称 → 用户ID」本地目录：断网时群成员接口拿不到名单，
                        // 但本地缓存/历史消息里其实还是定位得到人，@ 不该因此变灰。
                        com.aurora.chat.data.store.MentionDirectory.put(friendId, memberIdMap)
                        groupMemberNames = members + com.aurora.chat.data.store.MentionDirectory.namesOf(friendId)
                        groupMemberIdMap = memberIdMap
                        groupRoles = roleMap
                        groupAtMembers = atList
                    }
                } catch (_: Exception) {}
            }
        }

        // 第二步：优先从热缓存显示（永不过期），再读磁盘，最后等网络

        // 与磁盘建索引并行发起网络增量请求，重叠 I/O 以消除「先旧后新」的串行等待。
        // 增量游标在 adapter 尚未加载时使用磁盘侧最大 id（diskMaxId），与进入时的游标保持一致。
        var diskMaxId = 0L
        var netDeferred: kotlinx.coroutines.Deferred<com.aurora.chat.data.api.ApiResult<List<com.aurora.chat.data.api.MessageInfo>>>? = null
        val cacheKey = "$currentUserId:$friendId"
        val cached = messageHotCache[cacheKey]
        if (cached != null) {
            // 有热缓存 → 立即显示，零延迟
            diskMaxId = (cached.messages as? List<IMessageRef>)?.maxOfOrNull { it.id } ?: 0L
            // 被移出 / 已退出的群：服务端对我的每个请求都回 403（我已不是成员），
            // 没必要每次进会话都白撞一次。历史完全从本地库读——这正是「消息不丢」的前提。
            netDeferred = if (isExitedGroup) null else async(kotlinx.coroutines.Dispatchers.IO) {
                if (isGroupChat(friendId)) {
                    com.aurora.chat.data.repository.ChatRepository.getGroupMessages(friendId, 0, 0, diskMaxId)
                } else {
                    com.aurora.chat.data.repository.ChatRepository.getMessages(currentUserId, friendId, 0, 0, diskMaxId)
                }
            }
            messages.clear()
            messages.addAll(cached.messages as List<IMessageRef>)
            // 旧版本可能缓存过倒序消息，热缓存必须按 id 升序重排，保证最旧在上、最新在下
            messages.sortBy { it.id }
            restoreLocalNotices(friendId)
            applyFailedBubbles(failedBubbles)
            isLoadingMessages = false
            if (messages.isNotEmpty()) {
                listState.scrollToItem(0)
            }
            scrollReady = true
            // 热缓存里只有当时窗口内的消息，本地库里可能还有更旧的：进会话先问一次（EXISTS，常数级）。
            // 不能默认 false，否则滚到顶会误判「本地没有更早的」，直接跳过本地库去撞服务端分页——
            // 而服务端早已按 30 天清理，历史就永远翻不到了。
            val oldestCached = messages.minOfOrNull { it.id }
            if (oldestCached != null && !isLocalChat(friendId)) {
                dbHasOlder = withContext(Dispatchers.IO) {
                    com.aurora.chat.data.repository.LocalMessageStore
                        .hasOlderMessages(ctx, friendId, oldestCached)
                }
            }
        } else {
            // 无热缓存 → 读本地库；只取最近 INITIAL_WINDOW 条（索引查询 + 只解这么多条密文），
            // 不再把整段历史全量解析 + 逐条解密。更旧的历史由滚到顶时按游标一页页取。
            val diskPage = withContext(Dispatchers.Default) {
                com.aurora.chat.data.repository.LocalMessageStore.loadRecentMessages(ctx, friendId, INITIAL_WINDOW)
            }
            val diskMsgs = diskPage.messages
            dbHasOlder = diskPage.hasOlder
            diskMaxId = diskMsgs.maxOfOrNull { it.id } ?: 0L
            // 被移出 / 已退出的群：服务端对我的每个请求都回 403（我已不是成员），
            // 没必要每次进会话都白撞一次。历史完全从本地库读——这正是「消息不丢」的前提。
            netDeferred = if (isExitedGroup) null else async(kotlinx.coroutines.Dispatchers.IO) {
                if (isGroupChat(friendId)) {
                    com.aurora.chat.data.repository.ChatRepository.getGroupMessages(friendId, 0, 0, diskMaxId)
                } else {
                    com.aurora.chat.data.repository.ChatRepository.getMessages(currentUserId, friendId, 0, 0, diskMaxId)
                }
            }
            if (diskMsgs.isNotEmpty()) {
                initialLoadInProgress = true
                try {
                val diskData = withContext(Dispatchers.Default) {
                    val data = diskMsgs.map { m ->
                        // 解析只做一次：媒体类型判定要用完整 URL 的后缀，这里先算好复用
                        val resolvedUrl = resolveMediaUrl(m.mediaUrl)
                        // 处置提示的正文在库里**不带人称主语**（「因触发该群敏感词…」），
                        // 网络路径会按查看者补「你因…/某某 因…」，磁盘路径（被踢者的唯一历史来源）
                        // 也必须同样补齐，否则重进会话后那行少了主语，读不通。
                        // 拍一拍同理：库里可能残留服务端协议正文 "POKE"（旧版本或原始数据直接落盘），
                        // 读盘时不翻译就会把「X 拍了拍你」显示成 "POKE" —— 这正是拍一拍时好时坏的根因：
                        // 网络路径会转中文，磁盘路径以前原样输出。
                        val diskText = when {
                            m.flashDuration == -1 || m.content.trim().equals("POKE", ignoreCase = true) ->
                                parsePokeText(m, currentUserId, currentUserName)
                            isSensitivePunishText(m.content) ->
                                parseSensitivePunishText(m.content, m.targetName, currentUserName)
                            else -> m.content
                        }
                        MessageData(
                            id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
                            content = diskText, createdAt = m.createdAt,
                            isMine = m.fromUserId == currentUserId,
                            senderName = m.fromUserName,
                            targetName = m.targetName,
                            isRevoked = m.isRevoked != 0,
                            // 分类必须走唯一入口（只认结构化字段）。
                            // 【历史错误】这里曾用正文 contains("拍了拍"/"撤回了一条消息") 兜底：
                            // 磁盘路径每进一次会话都会执行，普通聊天只要正文里出现这两个词，
                            // 就会被标成居中广播并随写回固化；而随后的网络同步只追加「新 id」、
                            // 从不重算已有条目 → 一旦误判就再也回不来（就是"隔一段时间变广播"）。
                            isSystemNotice = isSystemNoticeMessage(
                                isRevoked = m.isRevoked != 0,
                                msgType = m.msgType,
                                broadcastTaskId = m.broadcastTaskId,
                                flashDuration = m.flashDuration,
                                content = m.content
                            ),
                            replyToText = m.replyToText, replyToSender = m.replyToSender,
                            replyToId = m.replyToId,
                            // 类型走唯一入口：库里的 media_type 可能被旧版本/旧后端污染过，
                            // 每次读盘都按「本机记录 > URL 后缀 > 服务端字段」重算一遍，不信任存量值。
                            mediaType = normalizeMediaType(m.mediaType, resolvedUrl, m.id),
                            mediaUrl = resolvedUrl, flashDuration = m.flashDuration,
                            // 广播任务 ID / 陌生人来源必须带进 store：少了它们，磁盘回报的开发者广播
                            // 在渲染层就只剩一个「居中提示」的样子，长按管理（删除/撤回全部）也会失效。
                            broadcastTaskId = m.broadcastTaskId,
                            contactSource = m.contactSource,
                            // 磁盘持久化的本机毫秒时间：跨会话保持「发送/到达时刻」稳定，不再每次进入刷新
                            localTimeMs = m.localTimeMs
                        )
                    }
                    // 诊断：磁盘缓存重载后扫描媒体消息（后台线程，避免主线程 O(n) 日志）
                    data.forEach { m ->
                        if (m.mediaType.isNotEmpty()) {
                            val status = if (m.mediaUrl.isEmpty()) "URL为空(空气泡)" else "OK"
                            MediaDebug.log(ctx, "LOAD_DISK", "id=${m.id} mediaType=${m.mediaType} mediaUrl=${m.mediaUrl} -> $status")
                        }
                    }
                    if (!adapter.loaded) {
                        // store 全量写入 + 窗口化暴露（后台线程，彻底消除主线程 O(n) 建索引）
                        adapter.loadWindowed(data, INITIAL_WINDOW)
                    } else if (adapter.visibleCount > INITIAL_WINDOW * 2) {
                        // 重进会话且上次滑得很远：把窗口收回「最近 INITIAL_WINDOW 条」，
                        // 让每次进入都是固定成本的一屏。更旧的全在 store 里，上滑零 I/O 补回，不会丢。
                        // 只在窗口确实鼓起来时才收：这个 effect 还会因 selfChatRefresh / AI 刷新重跑，
                        // 无条件收会把用户正滑着的位置强行打回底部。
                        adapter.resetWindowToTail(INITIAL_WINDOW)
                    }
                    // 媒体 URL 落库（后台线程写 ConcurrentHashMap，零主线程成本）
                    MediaStore.seed(data.filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
                        .map { it.id to (it.mediaType to it.mediaUrl) })
                    data
                }
                // 历史消息里出现过的发送者，同样补进本地「昵称 → 用户ID」目录：
                // 断网 + 群成员缓存为空时，@ 仍能定位到这些发言过的人（不再是灰色不可点）。
                if (isGroupChat(friendId)) {
                    val senderMap = HashMap<String, Long>()
                    for (m in diskData) {
                        if (m.fromUserId > 0L && m.senderName.isNotBlank()) senderMap[m.senderName] = m.fromUserId
                    }
                    if (senderMap.isNotEmpty()) {
                        com.aurora.chat.data.store.MentionDirectory.put(friendId, senderMap)
                        groupMemberNames = groupMemberNames + senderMap.keys
                    }
                }
                initialLoadInProgress = false
                syncMessages()
                // 还原本地广播（拉黑/好友验证等系统提示），与失败消息同理：本地生成、独立落盘，
                // 须等 adapter 全量替换完 messages 之后再补回，否则会被 syncMessages 冲掉。
                restoreLocalNotices(friendId)
                applyFailedBubbles(failedBubbles)
                isLoadingMessages = false
                if (messages.isNotEmpty()) {
                    listState.scrollToItem(0)
                }
                scrollReady = true
                } finally {
                    initialLoadInProgress = false
                }
            } else {
                isLoadingMessages = true
            }
        }

        // 第三步：网络刷新（请求已在第二步与磁盘建索引并行发起，这里仅 await 结果并合并）
        try {
            val ot = OnlineTimeTracker.getCurrentTotalSeconds(ctx)
            val wc = WordCountTracker.computeSyncValue()
            val r = AuroraApi.syncUserStats(ot, wc)
            if (r.success) WordCountTracker.markSynced(ctx)
        } catch (_: Exception) {}
        // 与预取的游标保持一致：使用磁盘侧 diskMaxId（adapter 此时已加载，且 diskMaxId 即其最大服务端 id，二者等价；
        // 若磁盘为空则 diskMaxId=0 走全量，仍是正确增量）。
        val afterId = diskMaxId
        // 被移出 / 已退出的群不发网络请求（netDeferred 为 null），这里只呈现本地历史
        val result = netDeferred?.await()
        if (result != null && result.success && result.data != null) {
            //  使用 MessageStoreAdapter 数组引擎加载，零 ChatMsg 对象分配
            val serverData = result.data!!
            // 用当前已显示消息（磁盘/缓存）里的非空 mediaUrl 回填服务器可能返回的空值，
            // 防御旧后端 / 旧缓存把空 mediaUrl 直接当空气泡的问题（send 时已把可用 URL 落盘）
            val existingMediaByMsgId = messages.filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
                .associate { it.id to it.mediaUrl }
            val dataList = withContext(kotlinx.coroutines.Dispatchers.Default) {
                serverData.map { m ->
                    val isRevoked = m.isRevoked
                    val isConsentNotice = isGroupConsentNotice(m.content)
                    // 统一入口：撤回 / msgType / 广播任务 / 拍一拍标记 / 群邀请协议正文
                    val isSystemNotice = isSystemNoticeMessage(
                        isRevoked = isRevoked == 1,
                        msgType = m.msgType,
                        broadcastTaskId = m.broadcastTaskId,
                        flashDuration = m.flashDuration,
                        content = m.content
                    )
                    val text = when {
                        // 开发者广播：直接展示广播正文
                        m.broadcastTaskId != 0L -> m.content
                        isRevoked == 1 -> {
                            val sender = if (m.fromUserId == currentUserId) currentUserName else m.fromUserName
                            "$sender 撤回了一条消息"
                        }
                        // 群邀请结果：保留原始文本(带"群聊邀请结果"前缀)，由渲染层按查看者身份个性化，
                        // 避免覆盖成个性化文案后重进对话无法再识别为系统广播而变成气泡
                        isConsentNotice -> m.content
                        m.flashDuration == -1 || m.content.trim().equals("POKE", ignoreCase = true) -> parsePokeText(m, currentUserId, currentUserName)
                        // 群敏感词处置提示：按查看者补人称（本人「你因…」/其他人「某某 因…」）
                        isSensitivePunishText(m.content) -> parseSensitivePunishText(m.content, m.targetName, currentUserName)
                        else -> m.content
                    }
                    // 服务器返回空 mediaUrl 时，优先用本地已验证可用的完整 URL 回填；否则做相对/内网 host 解析
                    val finalMediaUrl = if (m.mediaUrl.isEmpty()) (existingMediaByMsgId[m.id] ?: "") else resolveMediaUrl(m.mediaUrl)
                    // 类型走唯一入口：本地后缀优先（服务端 media_type 只做无后缀时的兜底）。
                    // 老后端把文件类消息一律存成 jpg，直接信它会把这个视频渲染成一张加载不出的图片。
                    // 用「原始 URL 优先」：sticker:// / emoji 这类协议特征只存在于原始值里；
                    // 原始为空时（服务端漏字段）才退回磁盘回填后的完整 URL。
                    val typeUrl = if (m.mediaUrl.isNotEmpty()) m.mediaUrl else finalMediaUrl
                    val finalMediaType = normalizeMediaType(m.mediaType, typeUrl, m.id)
                    MessageData(
                        id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
                        content = text, createdAt = m.createdAt, isMine = m.fromUserId == currentUserId,
                        senderName = m.fromUserName,
                        targetName = m.targetName,
                        isRevoked = isRevoked != 0, isSystemNotice = isSystemNotice,
                        replyToText = m.replyToText, replyToSender = m.replyToSender, replyToId = m.replyToId,
                        mediaType = finalMediaType, mediaUrl = finalMediaUrl, flashDuration = m.flashDuration,
                        broadcastTaskId = m.broadcastTaskId,
                        contactSource = m.contactSource
                    )
                }
            }
            // 服务端返回顺序不保证（私信倒序、群正序），统一按 id 升序整理后再入库，
            // 保证全量加载与增量追加都呈现"最旧在上、最新在下"，避免顺序颠倒/乱跳
            val orderedData = dataList.sortedBy { it.id }
            // 同磁盘路径：把本次拉到的发言者补进本地目录，断网时 @ 仍可定位
            if (isGroupChat(friendId)) {
                val senderMap = HashMap<String, Long>()
                for (m in dataList) {
                    if (m.fromUserId > 0L && m.senderName.isNotBlank()) senderMap[m.senderName] = m.fromUserId
                }
                if (senderMap.isNotEmpty()) {
                    com.aurora.chat.data.store.MentionDirectory.put(friendId, senderMap)
                    groupMemberNames = groupMemberNames + senderMap.keys
                }
            }
            if (adapter.loaded && adapter.visibleCount > 0) {
                // 已建立初始窗口（首屏/重进）：只增量追加服务端新消息并校正分类，
                // 绝不 loadWindowed（会重置窗口、丢弃已上滑加载的历史，表现为「顶部消息消失」）。
                // 注意：不能用单纯的 adapter.loaded 判断——无磁盘缓存的首次进入会先 loadWindowed(empty)
                // 把 loaded 置真，这里必须再要求 visibleCount>0 确认「窗口已建立」，否则会误走 merge→loadAll 全量建索引。
                if (orderedData.isNotEmpty()) {
                    adapter.mergeServerMessages(orderedData, currentUserId, currentUserName)
                    // 追加之外还要「校正」：在线数据是权威分类，把历史上被误判成居中提示
                    // （或本该是广播却被降级成气泡）的老条目就地改回来 —— 不清空、不重排、不闪屏。
                    adapter.reconcileServerClassification(orderedData)
                }
            } else {
                // 首次加载 / 无本地数据：全量替换（含空列表，正确显示"暂无消息"）
                // 窗口化加载：store 写入全部，但首屏只暴露最近 INITIAL_WINDOW 条；
                // 后台线程建索引，避免首次进入大群（无磁盘缓存）时主线程 O(n) 卡顿
                initialLoadInProgress = true
                try {
                withContext(Dispatchers.Default) {
                    adapter.loadWindowed(orderedData, INITIAL_WINDOW)
                }
                } finally {
                    initialLoadInProgress = false
                }
            }
            // 用 messages 里已回填(finalMediaUrl)的权威媒体 URL 喂给 MediaStore，
            // 不再用原始 serverData 重新解析（避免服务端返回空时漏填，与加载保持单一事实来源一致）
            MediaStore.seed(messages.filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
                .map { it.id to (it.mediaType to it.mediaUrl) })
            // 始终同步：网络数据是权威源（磁盘缓存可能缺 mediaType/mediaUrl 等字段）
            // 即使用户量级一致也要同步，避免因本地缓存字段缺失导致空气泡
            syncMessages()
            // 把修正后的消息（含完整 mediaUrl）增量追加写入本地磁盘（filesDir/messages/${friendId}.json，
            // saveMessages 仅按 id 去重追加、不更新已存在条目），
            // 彻底消除"旧缓存空 mediaUrl + 早退优化冻结"导致的空气泡，并支持离线查看图片
            // 注意：保存涉及逐条解密+加密+磁盘写入，必须放 IO 线程，否则进入大群时主线程被卡爆
            try {
                // 保存全量 store（而非仅窗口），否则窗口化后只存近屏会丢历史；
                // 后台线程构建 MessageInfo 列表，避免主线程 O(n)
                val infos = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    // msg_type 必须随行落盘：store 不携带该字段，漏掉它服务端标了 'system'
                    // 的广播落库就是空串，配合增量拉取（只取 id > 磁盘最大值）永远等不到矫正。
                    val serverTypeById = serverData.associate { it.id to it.msgType }
                    adapter.allRefs().map { ref ->
                        com.aurora.chat.data.api.MessageInfo(
                            id = ref.id, fromUserId = ref.fromUserId, toUserId = ref.toUserId,
                            content = ref.text, createdAt = ref.createdAt, fromUserName = ref.senderName,
                            isRevoked = ref.isRevoked, isSystemNotice = ref.isSystemNotice, replyToText = ref.replyToText,
                            replyToSender = ref.replyToSender, replyToId = ref.replyToId, mediaType = ref.mediaType,
                            mediaUrl = ref.mediaUrl, flashDuration = ref.flashDuration, targetName = ref.targetName,
                            // 分类结构化字段必须一起回写：漏掉它们，下次冷启动只剩一个 isSystemNotice 布尔，
                            // 磁盘路径就无法结构化还原（广播会掉成气泡、普通消息会被正文猜成广播）。
                            msgType = serverTypeById[ref.id] ?: "",
                            broadcastTaskId = ref.broadcastTaskId, contactSource = ref.contactSource,
                            mentions = ref.mentions,
                            // 本机毫秒时间落盘：跨会话保持「发送/到达时刻」稳定（ref.time 已是稳定快照）
                            localTimeMs = ref.time
                        )
                    }
                }
                if (infos.isNotEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.aurora.chat.data.repository.LocalMessageStore.saveMessages(ctx, friendId, infos)
                    }
                }
            } catch (_: Exception) { }
            // 自愈：媒体消息 URL 为空时，尝试从 /api/messages/info 恢复（后端已补 media_type/media_url 字段）。
            // 覆盖"旧缓存空 URL + 服务端列表也恰好空"的极端场景，彻底杜绝重进对话后图片变空气泡。
            // 用全量 store（adapter.allRefs）而非仅窗口，确保未暴露的更旧消息也能被自愈；后台线程构建避免主线程 O(n)
            val emptyMediaMsgs = withContext(kotlinx.coroutines.Dispatchers.Default) {
                adapter.allRefs().filter { it.mediaType.isNotEmpty() && it.mediaUrl.isEmpty() && it.id > 0 }
            }
            if (emptyMediaMsgs.isNotEmpty()) {
                scope.launch {
                    for (m in emptyMediaMsgs) {
                        try {
                            val info = com.aurora.chat.data.api.AuroraApi.getMessageInfo(m.id)
                            if (info.success && info.data != null) {
                                val recoveredUrl = info.data!!.optString("media_url", "")
                                val recoveredType = info.data!!.optString("media_type", "")
                                if (recoveredUrl.isNotEmpty()) {
                                    MediaStore.put(m.id, ChatMedia(recoveredType.ifEmpty { m.mediaType }, resolveMediaUrl(recoveredUrl)))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            //  诊断：网络刷新后扫描媒体消息
            messages.forEach { m ->
                if (m.mediaType.isNotEmpty()) {
                    val status = if (m.mediaUrl.isEmpty()) "URL为空(空气泡)" else "OK"
                    MediaDebug.log(ctx, "LOAD_NET", "id=${m.id} mediaType=${m.mediaType} mediaUrl=${m.mediaUrl} -> $status")
                }
            }
            // 同步设置滚动位置，与消息赋值在同一个 snapshot 中，消除闪屏
            if (messages.isNotEmpty()) {
                listState.scrollToItem(0)
            }
            // 自动标记对方消息为已读（批量，避免并发请求压垮数据库）
            ChatViewModel.backgroundScope.launch {
                val ids = serverData.filter { it.fromUserId != currentUserId }.map { it.id }
                if (ids.isNotEmpty()) {
                    com.aurora.chat.data.api.AuroraApi.markMessagesReadBatch(ids)
                }
            }
        }
        // 兜底：磁盘为空、只有网络数据的分支（上面没走到任何一处 apply）在这里补齐，
        // 且仍在 isLoadingMessages=false 之前，不会出现"消息先出、红叹号后到"的补帧。
        if (failedBubbles.isNotEmpty()) applyFailedBubbles(failedBubbles)
        isLoadingMessages = false
        scrollReady = true
        // 加载失败且一条内容都没有时，不再弹 Toast。
        // 理由：断网时界面本身已经能看出来（离线横幅 + 消息发不出去），每次进会话弹一次纯属噪音；
        // 纯粹的网络抖动更是没必要打断用户——那次请求失败，下一轮轮询/推送自然会补上。
        // 改成在「空白区域」放一行静态提示 + 重试入口：不遮挡、不打断、不重复打扰，
        // 只有真的什么都没有可看时才出现。
        if (result != null && !result.success && messages.isEmpty() && !isNotGroupMember) {
            initialLoadFailed = true
            Log.w("ChatConv", "初始加载失败: ${result.message}")
        }
    }
    // 加载更多历史消息（无限滚动，始终保留最新 pageSize 条在内存中用于展示）
    fun triggerLoadMore(offset: Int) {
        scope.launch {
            if (loadMoreProgress != 0) return@launch
            loadMoreProgress = 1
            if (isLocalChat(friendId)) { loadMoreProgress = 0; return@launch }
            // 被移出/已退出的群：服务端分页只会 403，更旧的历史全部由本地库那条路径提供。
            // 同样直接查 store（原因见 pullNewMessages 的注释）。
            if (isGroupChat(friendId) &&
                runCatching { com.aurora.chat.data.local.GroupExitStore.getByConv(ctx, friendId) }.getOrNull() != null
            ) { hasMoreMessages = false; loadMoreProgress = 0; return@launch }
            val result = if (isGroupChat(friendId)) {
                com.aurora.chat.data.repository.ChatRepository.getGroupMessages(friendId, pageSize, offset)
            } else {
                com.aurora.chat.data.repository.ChatRepository.getMessages(currentUserId, friendId, pageSize, offset)
            }
            if (result.success && result.data != null && result.data!!.isNotEmpty()) {
                // 这一页是「本地库已经翻到底」之后才向服务端要的老历史，必须落库。
                // 以前只 prepend 进内存 store：消息在聊天里往上翻能看到，但「查找聊天记录」
                // 只读本地库，所以搜不到；退出会话后这些页也一并消失，下次进来还得重新走网络。
                // 服务端返回顺序不保证（私信倒序/群正序），统一按 id 升序整理为"最旧在上"
                val dataList = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    result.data!!.map { m ->
                        val isRevoked = m.isRevoked
                        val isConsentNotice = isGroupConsentNotice(m.content)
                        val isSystemNotice = isSystemNoticeMessage(
                            isRevoked = isRevoked == 1,
                            msgType = m.msgType,
                            broadcastTaskId = m.broadcastTaskId,
                            flashDuration = m.flashDuration,
                            content = m.content
                        )
                        val text = when {
                            // 开发者广播：直接展示广播正文
                            m.broadcastTaskId != 0L -> m.content
                            isRevoked == 1 -> {
                                val sender = if (m.fromUserId == currentUserId) currentUserName else m.fromUserName
                                "$sender 撤回了一条消息"
                            }
                            isConsentNotice -> m.content
                            m.flashDuration == -1 || m.content.trim().equals("POKE", ignoreCase = true) -> parsePokeText(m, currentUserId, currentUserName)
                            // 群敏感词处置提示：按查看者补人称（同上）
                            isSensitivePunishText(m.content) -> parseSensitivePunishText(m.content, m.targetName, currentUserName)
                            else -> m.content
                        }
                        MessageData(
                            id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
                            content = text, createdAt = m.createdAt, isMine = m.fromUserId == currentUserId,
                            senderName = m.fromUserName, targetName = m.targetName,
                            isRevoked = isRevoked != 0, isSystemNotice = isSystemNotice,
                            replyToText = m.replyToText, replyToSender = m.replyToSender, replyToId = m.replyToId,
                            // 唯一入口：上滑翻页拿到的老消息同样按本地后缀重算，不沿用库里可能被污染的类型
                            mediaType = normalizeMediaType(m.mediaType, m.mediaUrl, m.id),
                            mediaUrl = resolveMediaUrl(m.mediaUrl),
                            flashDuration = m.flashDuration, broadcastTaskId = m.broadcastTaskId,
                            contactSource = m.contactSource
                        )
                    }
                }
                val orderedOlder = dataList.sortedBy { it.id }
                // 落盘用「翻译后」的正文（重点是拍一拍 "POKE" → 「X 拍了拍你」）：
                // 直接存服务端原文的话，这条以后每次从磁盘渲染都会显示成 POKE。
                val localizedText = dataList.associate { it.id to it.content }
                runCatching {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.aurora.chat.data.repository.LocalMessageStore.saveMessages(
                            ctx, friendId,
                            result.data!!.map { m -> m.copy(content = localizedText[m.id] ?: m.content) }
                        )
                    }
                }
                // 去重：该页最新一条已在 store，说明早已加载过，跳过避免重复 key 崩溃
                if (adapter.store.indexOf(orderedOlder.last().id) >= 0) {
                    hasMoreMessages = false
                    loadMoreProgress = 0
                    return@launch
                }
                if (orderedOlder.size < pageSize) hasMoreMessages = false
                // 维持滚动态：先记住视口锚点（按 id，不按下标），插入+裁剪后按 id 重新定位
                val prevFirst = listState.firstVisibleItemIndex
                val prevOffset = listState.firstVisibleItemScrollOffset
                val anchorId = anchorIdAt(prevFirst)
                val added = adapter.prependOlderFromServer(orderedOlder)
                if (added > 0) {
                    syncMessages()
                    restoreViewport(anchorId, prevFirst, prevOffset)
                    // 同步更旧消息的媒体 URL 到 MediaStore，防止上滑后历史图片变空气泡
                    val newlySeeded = messages.take(added)
                        .filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
                        .map { it.id to (it.mediaType to it.mediaUrl) }
                    if (newlySeeded.isNotEmpty()) MediaStore.seed(newlySeeded)
                }
            } else {
                hasMoreMessages = false
            }
            loadMoreProgress = 0
        }
    }

    /**
     * 从已读入内存 store 的更旧消息中预加载一批到列表顶部（零网络、零重复）。
     * store 中保留着磁盘/首屏已读入的全部消息，仅 _indices 窗口化暴露最近若干条；
     * 滚到顶时把更旧的一段补进窗口并维持滚动位置，避免大群一次性全量渲染卡顿。
     */
    fun prependOlderFromStore() {
        val prevFirst = listState.firstVisibleItemIndex
        val prevOffset = listState.firstVisibleItemScrollOffset
        val anchorId = anchorIdAt(prevFirst)
        val added = adapter.prependOlderFromStore(DISK_PAGE)
        if (added <= 0) return
        syncMessages()
        restoreViewport(anchorId, prevFirst, prevOffset)
        // 同步更旧消息的媒体 URL 到 MediaStore，防止上滑后历史图片变空气泡
        val newlySeeded = messages.take(added)
            .filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
            .map { it.id to (it.mediaType to it.mediaUrl) }
        if (newlySeeded.isNotEmpty()) MediaStore.seed(newlySeeded)
    }

    /**
     * 从本地库（SQLite）按游标取更旧的一页，补到列表顶部。
     *
     * 和 [prependOlderFromStore] 的分工：后者从「已经读进内存的 store」里挪窗口（零 I/O）；
     * 这里是真的去库里取下一批 —— 首屏只读了最近 120 条，再往上的历史只在库里，内存里没有。
     * 走 `(conv_key, msg_id)` 主键索引 + 游标（`msg_id < 当前最早一条`），不重复、不漏、不受新消息影响。
     */
    fun prependOlderFromDb() {
        if (dbPagingBusy) return
        if (adapter.count <= 0) {
            dbHasOlder = false
            return
        }
        dbPagingBusy = true
        loadMoreProgress = 1
        scope.launch {
            // 取不到本地更旧的一页时，要在闸门释放之后再接力服务端分页：
            // triggerLoadMore 开头的 `loadMoreProgress != 0` 会直接 return，在 try 里调等于白调，
            // 结果就是「本地库到底了，界面却停在顶部不动」——这正是之前那次「到顶卡死」的成因。
            var serverFallback = false
            try {
                val oldestId = adapter.store.idAt(0)
                if (oldestId == 0L) {
                    dbHasOlder = false
                    serverFallback = hasMoreMessages
                } else {
                    // 先记住视口锚点（按消息 id，不按下标）：插入 + 裁剪后按 id 重新定位，绝不跳屏
                    val prevFirst = listState.firstVisibleItemIndex
                    val prevOffset = listState.firstVisibleItemScrollOffset
                    val anchorId = anchorIdAt(prevFirst)
                    val page = withContext(Dispatchers.IO) {
                        com.aurora.chat.data.repository.LocalMessageStore
                            .loadOlderMessages(ctx, friendId, oldestId, DISK_PAGE)
                    }
                    if (page.isEmpty()) {
                        // 本地库已到最早：交给服务端继续翻
                        dbHasOlder = false
                        serverFallback = hasMoreMessages
                    } else {
                        // 取满一页就认为还有；不足一页说明库里已到底
                        dbHasOlder = page.size >= DISK_PAGE
                        // 与磁盘首屏同一套字段规则：分类已由库里按唯一入口算好，这里只补渲染相关的媒体类型兜底
                        val data = page.map { m ->
                            MessageData(
                                id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
                                content = m.content, createdAt = m.createdAt,
                                isMine = m.fromUserId == currentUserId,
                                senderName = m.fromUserName,
                                targetName = m.targetName,
                                isRevoked = m.isRevoked != 0,
                                isSystemNotice = m.isSystemNotice,
                                replyToText = m.replyToText, replyToSender = m.replyToSender,
                                replyToId = m.replyToId,
                                mediaType = normalizeMediaType(m.mediaType, m.mediaUrl, m.id),
                                mediaUrl = resolveMediaUrl(m.mediaUrl),
                                flashDuration = m.flashDuration,
                                broadcastTaskId = m.broadcastTaskId,
                                contactSource = m.contactSource,
                                localTimeMs = m.localTimeMs
                            )
                        }
                        val added = adapter.prependOlderFromServer(data)
                        if (added > 0) {
                            syncMessages()
                            restoreViewport(anchorId, prevFirst, prevOffset)
                            // 更旧消息的媒体 URL 同步进 MediaStore，避免上滑后历史图片变空气泡
                            val seeded = page
                                .filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
                                .map { it.id to (it.mediaType to it.mediaUrl) }
                            if (seeded.isNotEmpty()) MediaStore.seed(seeded)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                loadMoreProgress = 0
                dbPagingBusy = false
            }
            if (serverFallback) triggerLoadMore(adapter.serverCount())
        }
    }

    // 滚动到顶部时加载更多：优先吃内存里已读入的更旧消息（零 I/O），
    // 其次吃本地库的上一页（零网络），本地库用尽后才向服务端分页拉取。
    LaunchedEffect(listState) {
        // 下滑补页的并发闸门：appendNewerFromStore 之后要靠异步 scrollToItem 把视口钉回去，
        // 这期间 total 已变、index 还没变，snapshotFlow 会再发一次；没有闸门就会一口气连补好几页并跳屏。
        var tailPagingBusy = false
        snapshotFlow {
            val info = listState.layoutInfo
            // 末尾可见项 index + 总数：上滑加载的判定必须用「末尾可见项」，不能用 firstVisibleItemIndex。
            // firstVisibleItemIndex 在贴顶时是常量，补进一页后它依然不变，snapshotFlow 不会重新发射，
            // 结果就是「到顶只加载一页，之后怎么滑都不动了」。
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            // reverseLayout：index 0 = 最新（底部），index 越大越旧（顶部）。
            val atTop = total > 0 && lastVisible >= total - 2
            if (atTop && loadMoreProgress == 0 && !isLoadingMessages && messages.isNotEmpty()) {
                if (adapter.hasOlderInStore()) {
                    prependOlderFromStore()
                } else if (dbHasOlder && !isLocalChat(friendId)) {
                    // 本地库还有更旧的一页：先吃本地（零网络；服务端已按 30 天清理的历史也只在这里）
                    prependOlderFromDb()
                } else if (hasMoreMessages) {
                    // offset 用「已持服务端消息条数」，不能用窗口条数：窗口有上限且会上下滑动，
                    // 拿它当 offset 会在触顶后反复请求同一页，被判重复后提前「没有更多」。
                    triggerLoadMore(adapter.serverCount())
                }
            }
            // 下滑补回：窗口曾与最新脱钩（上滑超过上限、底部被丢弃），滑到窗口底部时把更近的消息接回来。
            // 与上滑完全对称：零 I/O、零网络，只是把窗口范围往后推；追平 store 末尾后自动复位。
            if (!tailPagingBusy && listState.firstVisibleItemIndex <= 1 && adapter.hasNewerInStore()) {
                tailPagingBusy = true
                val prevFirst = listState.firstVisibleItemIndex
                val prevOffset = listState.firstVisibleItemScrollOffset
                val anchorId = anchorIdAt(prevFirst)
                val added = adapter.appendNewerFromStore(DISK_PAGE)
                if (added > 0) {
                    syncMessages()
                    // 较新的消息插在 messages 尾部 → 反转渲染后落在列表开头；同时可能从顶端裁掉同样多，
                    // 净位移不等于 added，所以按 id 重新定位，别做算术补偿。
                    restoreViewport(anchorId, prevFirst, prevOffset)
                        .invokeOnCompletion { tailPagingBusy = false }
                    val seeded = messages.takeLast(added)
                        .filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
                        .map { it.id to (it.mediaType to it.mediaUrl) }
                    if (seeded.isNotEmpty()) MediaStore.seed(seeded)
                } else {
                    tailPagingBusy = false
                }
            }
        }
    }
    // （scrollReady 已在初始加载 LaunchedEffect 中设置，独立 LaunchedEffect 已移除避免滚动竞争）
    // 等 LazyColumn 布局完成后滚动到底部，同时支持缓存回填
    // （已合并到消息加载 LaunchedEffect 中，避免独立 LaunchedEffect 造成的闪屏）


    // 会话内实时增量拉取：TCP 推送与 15s 兜底轮询共用，避免重复逻辑。
    // 关键修复：只用服务端权威游标 serverCursor()（adapter 数据），彻底规避本地乐观项污染 afterId 的死锁
    // （本地乐观 id 已改为负数，但若仍混入 UI 列表也不会再污染增量游标）。
    suspend fun pullNewMessages(forceFull: Boolean = false) {
        // 被移出/已退出的群不再拉增量：服务端只会回 403，历史由本地库负责。
        // 这里直接查 store 而不是用 isExitedGroup 状态：轮询协程是在「进入会话那一刻」创建的，
        // 它捕获的是当时的组合值；如果恰好在会话里被踢，状态变量更新了但协程拿到的还是旧值，
        // 会继续每 15 秒撞一次 403。store 是权威且带内存缓存，查询成本可忽略。
        if (isGroupChat(friendId) &&
            runCatching { com.aurora.chat.data.local.GroupExitStore.getByConv(ctx, friendId) }.getOrNull() != null
        ) return
        // 领取转账时服务端会改写老消息的正文（status=claimed）并插入一条「已收款」卡片。
        // 只按 afterId 增量拉取永远拿不到老消息的改动，所以状态变更时做一次全量对齐。
        // 标记只消费一次，15s 兜底轮询仍然走增量，不会反复全量拉。
        val fullSync = forceFull || com.aurora.chat.ui.chat.TransferStatusCache.consumeFullSync()
        val afterId = if (fullSync) 0L else serverCursor()
        val result = if (isGroupChat(friendId)) {
            com.aurora.chat.data.repository.ChatRepository.getGroupMessages(friendId, 0, 0, afterId)
        } else {
            com.aurora.chat.data.repository.ChatRepository.getMessages(currentUserId, friendId, 0, 0, afterId)
        }
        if (result.success && result.data != null && result.data!!.isNotEmpty()) {
            // 全量对齐返回的是整段历史（后端 limit=0 不加 LIMIT），只取尾部一段即可：
            // 新卡片必在末尾，老转账消息的状态也已单独从服务端正文提取。
            val serverData = result.data!!.let {
                if (fullSync && it.size > 120) it.takeLast(120) else it
            }
            // 在 IO 线程转换 MessageData
            val dataList = withContext(kotlinx.coroutines.Dispatchers.Default) {
                serverData.map { m ->
                    val isRevoked = m.isRevoked
                    val isSystemNotice = isSystemNoticeMessage(
                        isRevoked = isRevoked == 1,
                        msgType = m.msgType,
                        broadcastTaskId = m.broadcastTaskId,
                        flashDuration = m.flashDuration,
                        content = m.content
                    )
                    MessageData(
                        id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
                        content = m.content, createdAt = m.createdAt,
                        isMine = m.fromUserId == currentUserId, senderName = m.fromUserName,
                        targetName = m.targetName,
                        isRevoked = isRevoked != 0,
                        isSystemNotice = isSystemNotice, replyToText = m.replyToText,
                        replyToSender = m.replyToSender, replyToId = m.replyToId,
                        // 唯一入口判定类型（此处是 TCP 推送 / 15s 轮询共用的实时增量入口，
                        // 正是「发出后几秒变文件卡片」的矫正源头：后缀能认出来就以后缀为准）
                        mediaType = normalizeMediaType(m.mediaType, m.mediaUrl, m.id),
                        mediaUrl = m.mediaUrl,
                        flashDuration = m.flashDuration, broadcastTaskId = m.broadcastTaskId,
                        contactSource = m.contactSource
                    )
                }
            }
            // 以服务端返回的转账正文为权威状态：服务端领取后会改写原消息 content，
            // 本地磁盘里那条仍是 status=pending。这里写进持久化缓存，
            // 清后台重开、没收到 push、离线领取后重进会话都能自动恢复「已收款」。
            // 只扫 media_type=transfer，避免全量对齐时遍历上千条历史。
            for (m in serverData) {
                if (m.mediaType != "transfer") continue
                val t = parseTransferMessage(m.content) ?: continue
                if (t.transferId <= 0L || t.status == TRANSFER_STATUS_PENDING) continue
                if (com.aurora.chat.ui.chat.TransferStatusCache.statusOf(t.transferId)?.first != t.status) {
                    com.aurora.chat.ui.chat.TransferStatusCache.put(
                        t.transferId, t.status, t.claimedAt, requestFullSync = false
                    )
                }
            }
            // 用服务端真实最大 id 与 adapter 当前最大服务端 id 比较；
            // 不再依赖 messages.lastOrNull()?.id（其可能混有本地乐观项导致误跳过），
            // 也与「服务端返回顺序不保证」保持一致（私信倒序/群正序均正确）。
            if (serverData.maxOf { it.id } <= adapter.lastMessageId) {
                // 没有新消息也要过一遍分类校正：服务端这次返回的老消息若是权威分类，
                // 就地修正历史误判（只改渲染标志，不动列表），否则误判永远等不到修复时机。
                if (adapter.reconcileServerClassification(dataList) > 0) syncMessages()
                return
            }
            val hasNew = adapter.mergeServerMessages(dataList, currentUserId, currentUserName)
            val repaired = adapter.reconcileServerClassification(dataList)
            if (!hasNew && repaired > 0) syncMessages()
            if (hasNew) {
                // 距底部行数：reverseLayout 下 firstVisibleItemIndex=视觉底部第一条可见项，即离最新几行。
                // 新规则：非 AI 对话离底 ≤5 行（含贴底）自动顶底看新消息；离底 >5 行才在回底按钮累计未读数（深蓝数字）。
                // AI 对话消息极长，用户上滑哪怕 1 行都是明确在看历史，不能被「离底≤5 行」阈值拽回底部：
                // 仅严格贴底（像素级 atBottom）才自动跟随，其余一律累计未读走回底按钮。
                val rowsFromBottom = listState.firstVisibleItemIndex
                val shouldFollowLatest = if (friendId == AI_CHAT_ID) atBottomState.value else rowsFromBottom <= 5
                // 未读角标：用户离底超 5 行（正在看历史）时收到对方新消息，累计显示到回底按钮上。
                // 只统计本次 id>afterId 的对方消息，不含本人确认回填、撤回/闪照/系统/广播消息。
                // 程序化滚动（自身回底动画）期间不累计，避免误把回底瞬间拉到的新消息算成未读。
                if (!shouldFollowLatest && !programmaticScrolling) {
                    val freshIncoming = serverData.count {
                        it.id > afterId && it.fromUserId != currentUserId &&
                            !(it.isRevoked == 1 || it.flashDuration == -1 || it.broadcastTaskId != 0L || it.msgType == "system")
                    }
                    if (freshIncoming > 0) unreadNewCount = (unreadNewCount + freshIncoming).coerceAtMost(99)
                }
                syncMessages()
                // TCP 实时到达的拍一拍/系统通知等即时消息，原本只在内存 adapter 中，
                // 落盘只发生在「进入会话」的网络同步步骤。这里把本次合并进来的新消息（id > afterId）
                // 立即按真实服务端 id 追加落盘，保证重进对话后依然可见，且名字正确（adapter 已带 targetName）。
                try {
                    val newRefs = adapter.allRefs().filter { it.id > afterId }
                    if (newRefs.isNotEmpty()) {
                        // msg_type 必须随行落盘：store（内存列式引擎）不携带 msgType，
                        // 只从 ref 构造会把服务端标了 'system' 的广播落成空串，重进对话就掉成气泡。
                        // 这里从本次服务端增量里按 id 取回真实值；ref 不在本次增量里的（本地合成）本来就是空。
                        val serverTypeById = serverData.associate { it.id to it.msgType }
                        val infos = newRefs.map { ref ->
                            com.aurora.chat.data.api.MessageInfo(
                                id = ref.id, fromUserId = ref.fromUserId, toUserId = ref.toUserId,
                                content = ref.text, createdAt = ref.createdAt, fromUserName = ref.senderName,
                                isRevoked = ref.isRevoked, isSystemNotice = ref.isSystemNotice,
                                replyToText = ref.replyToText, replyToSender = ref.replyToSender,
                                replyToId = ref.replyToId, mediaType = ref.mediaType, mediaUrl = ref.mediaUrl,
                                flashDuration = ref.flashDuration, targetName = ref.targetName,
                                // 分类结构化字段随行落盘（否则重进会话后广播/系统提示会被当成普通气泡）
                                msgType = serverTypeById[ref.id] ?: "",
                                broadcastTaskId = ref.broadcastTaskId, contactSource = ref.contactSource,
                                // 本机毫秒时间随行落盘：保持「发送/到达时刻」稳定（ref.time 已是稳定快照）
                                localTimeMs = ref.time
                            )
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.aurora.chat.data.repository.LocalMessageStore.mergeAndSave(ctx, friendId, infos)
                        }
                    }
                } catch (_: Exception) { }
                // 从推送数据更新对话列表预览（支持双向实时）
                val lastServerMsg = serverData.lastOrNull()
                if (lastServerMsg != null) {
                    // 私聊 E2EE 密文在展示为会话列表预览前解密，与对话内明文保持一致
                    val rawContent = if (friendId > 0 && com.aurora.chat.CryptoUtil.isEncrypted(lastServerMsg.content)) {
                        try { com.aurora.chat.CryptoUtil.decrypt(lastServerMsg.content) }
                        catch (_: Exception) { "[端到端加密消息]" }
                    } else lastServerMsg.content
                    // 媒体类型走与对话内一致的归一化入口（本机记录 > URL 后缀 > 服务端字段）。
                    // 语音必须排在 content 之前：语音 content 是时长秒数，直接拿去当预览就会显示成「2」。
                    val pushMediaKind = normalizeMediaType(lastServerMsg.mediaType, lastServerMsg.mediaUrl, lastServerMsg.id)
                    val preview = if (lastServerMsg.isRevoked > 0) {
                        "${lastServerMsg.fromUserName} 撤回了一条消息"
                    } else if (lastServerMsg.flashDuration == -1 || lastServerMsg.content.trim().equals("POKE", ignoreCase = true)) {
                        parsePokeText(lastServerMsg, currentUserId, currentUserName)
                    } else if (isSensitivePunishText(lastServerMsg.content)) {
                        parseSensitivePunishText(lastServerMsg.content, lastServerMsg.targetName, currentUserName)
                    } else if (isClearChatRequestCard(lastServerMsg.content) || isClearChatRejectCard(lastServerMsg.content)) {
                        if (isClearChatRejectCard(lastServerMsg.content)) "清空聊天记录(已拒绝)" else "清空聊天记录请求"
                    } else if (pushMediaKind == "voice") {
                        "[语音]"
                    } else if (rawContent.isNotEmpty()) {
                        rawContent
                    } else when (pushMediaKind) {
                        "image" -> "[图片]"
                        "video" -> "[视频]"
                        "file" -> "[文件]"
                        "sticker", "emoji" -> "[表情]"
                        else -> "[消息]"
                    }
                    ChatViewModel.updateConversationPreview(friendId, preview, lastServerMsg.createdAt)
                }
                // 自动标记对方发来的消息为已读（批量）
                ChatViewModel.backgroundScope.launch {
                    val ids = serverData.filter { it.fromUserId != currentUserId }.map { it.id }
                    if (ids.isNotEmpty()) {
                        com.aurora.chat.data.api.AuroraApi.markMessagesReadBatch(ids)
                    }
                }
                // 用户贴底或离底 5 行内时自动滚动到最新消息（贴底后新消息必须自动顶下来，不用手动滑）
                // 注意 reverseLayout 下 visibleItemsInfo 第一个元素才是视觉底部（index 最小=最新），
                // 不能用 lastOrNull() 判断"靠近底部"（那会取到最顶部旧消息导致永不触发）。
                // AI 对话遵循 shouldFollowLatest（仅严格贴底跟随，上滑 1 行即不再拽回）。
                if (messages.isNotEmpty() && shouldFollowLatest) {
                    delay(50)
                    listState.scrollToItem(0)
                }
            }
        }
    }

    // 进入会话即做一次全量对齐：离线期间被领取的转账、服务端改写了正文的老消息、
    // 以及领取时插入的「已收款」卡片，都不会因为增量游标而永远错过。
    // 等初始化窗口建完再拉，避免与 store 初始化并发写冲突。
    LaunchedEffect(friendId) {
        var wait = 0
        while (wait++ < 20 && initialLoadInProgress) kotlinx.coroutines.delay(300)
        if (!initialLoadInProgress && !isLocalChat(friendId) && !isNotificationChat(friendId)) {
            pullNewMessages(forceFull = true)
        }
    }

    // TCP 推送触发即时刷新 — 使用 adapter.mergeServerMessages 增量更新
    // key 改用 friendId：切会话（不退出）时重建收集器，避免 LaunchedEffect(Unit) 持有上一个会话的
    // messages/adapter 引用，导致增量写入错误会话。
    LaunchedEffect(friendId) {
        ChatViewModel.newMessageReceived.collect {
            delay(200L) // 等 200ms 让服务端写入完成
            if (isLocalChat(friendId) || isNotificationChat(friendId)) return@collect
            // 初始化（磁盘窗口化建索引）进行中时跳过增量合并：store 正被后台线程构建，
            // 并发写入会导致数据错乱；网络刷新会补拉期间新增，不会漏消息
            if (initialLoadInProgress) return@collect
            pullNewMessages()
        }
    }

    // 兜底轮询：TCP 推送是会话内唯一实时入口，缺兜底会静默丢消息。
    // 仅会话打开时生效，退出组合（friendId 变化或 chatOpen=false）自动取消；低频 15s 对电量/流量可忽略。
    LaunchedEffect(friendId) {
        while (isActive) {
            delay(15_000L)
            if (isLocalChat(friendId) || isNotificationChat(friendId)) continue
            if (initialLoadInProgress) continue
            pullNewMessages()
        }
    }

    // 即时处理消息撤回 — 使用 adapter.markRevoked 原地更新数组
    LaunchedEffect(Unit) {
        // 广播删除/撤回用版本号而不是 hasUpdate：同时存在多个会话页时，
        // 事件不能被第一个看到的页面「消费」掉，否则真正包含广播副本的会话永远删不掉。
        var handledBroadcastDelVer = 0L
        var handledBroadcastRecallVer = 0L
        while (true) {
            delay(200L)
            if (com.aurora.chat.PendingMessageRecalled.hasUpdate) {
                com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "broadcast: hasUpdate consumed msgId=${com.aurora.chat.PendingMessageRecalled.messageId} friendId=$friendId")
                com.aurora.chat.PendingMessageRecalled.hasUpdate = false
                val msgId = com.aurora.chat.PendingMessageRecalled.messageId
                val senderName = com.aurora.chat.PendingMessageRecalled.senderName
                if (msgId > 0L) {
                    if (isLocalChat(friendId)) {
                        // 本地/AI 对话：消息以 ChatMsg 直接存于 messages，不经过 adapter。
                        // 直接原地移除该条，避免 syncMessages() 用空 adapter 覆盖清空整个列表
                        // （否则会出现「列表整屏消失 + 撤回后继续聊上下文全部丢失」的问题）
                        val idx = messages.indexOfFirst { it.id == msgId }
                        if (idx >= 0) messages.removeAt(idx)
                        // 落盘：把移除结果同步写回本地 JSON，避免重启后消息复活
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            saveLocalChatMessages(ctx, currentUserId, localChatKey(friendId), messages.filter { !it.isSystemNotice })
                        }
                    } else {
                        adapter.markRevoked(msgId, senderName, currentUserName)
                        // 绝不 syncMessages()：它是「用 adapter 全量替换 messages」(replaceAllUnique 会删掉不在
                        // adapter 里的消息)，会把尚未回流到 adapter 的本地即时消息(例如刚发出、引用了被撤回消息
                        // 的那条回复)一并误删 —— 表现为「撤回被引用消息，引用它的那条也跟着消失」，重进会话才恢复。
                        // 这里只做原地更新：adapter 引用(StoreMessageRef)直接读 store 标志；本地 ChatMsg 就地标记撤回。
                        val recallLi = messages.indexOfFirst { it.id == msgId || it.serverId == msgId }
                        if (recallLi >= 0) {
                            val cur = messages[recallLi]
                            if (cur is ChatMsg && cur.isRevoked != 1) {
                                // 开发者撤回窥探：缓存撤回前的原文，供对话内点击「X 撤回了一条消息」回显
                                com.aurora.chat.ui.viewmodel.recallOriginalCache[msgId] = cur.text
                                messages[recallLi] = cur.copy(isRevoked = 1)
                            }
                        }
                        recallTick++
                        // 落盘标记撤回，防止重启后消息复活（saveMessages 只追加不更新，需专用原子写）
                        com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "broadcast: calling markRevoked friendId=$friendId msgId=$msgId")
                        com.aurora.chat.data.repository.LocalMessageStore.markRevoked(ctx, friendId, msgId)
                        // 清理媒体缓存与内存/热缓存，避免旧数据命中
                        com.aurora.chat.ui.chat.media.MediaStore.remove(msgId)
                        com.aurora.chat.ui.viewmodel.ChatViewModel.invalidateHotCache(currentUserId, friendId)
                        // 更新会话列表"最后一条消息"预览
                        com.aurora.chat.ui.viewmodel.ChatViewModel.updateConversationPreview(friendId, "$senderName 撤回了一条消息", System.currentTimeMillis() / 1000)
                        // 清理被撤回媒体的物理文件与 Coil 缓存（best-effort）
                        val mi = adapter.store.indexOf(msgId)
                        if (mi >= 0) {
                            val mUrl = adapter.store.mediaUrlAt(mi)
                            if (mUrl.isNotEmpty()) deleteMediaFile(ctx, mUrl)
                        }
                    }
                }
            }
            // 广播单条删除（悄无声息）：服务端已删库，本地按 task_id 移除该广播在所有会话的副本
            if (com.aurora.chat.PendingBroadcastDeleted.version != handledBroadcastDelVer) {
                handledBroadcastDelVer = com.aurora.chat.PendingBroadcastDeleted.version
                val bid = com.aurora.chat.PendingBroadcastDeleted.taskId
                if (bid > 0L) {
                    adapter.removeByPredicate { it.broadcastTaskId == bid }
                    syncMessages()
                    // 关键：同步删本地落盘。只删内存的话，退出重进会话时消息会从本地库"复活"
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.aurora.chat.data.local.MessageDb.deleteByBroadcastTask(ctx, bid)
                    }
                }
            }
            // 广播撤回全部：移除该 task_id 下的所有广播
            if (com.aurora.chat.PendingBroadcastRecalled.version != handledBroadcastRecallVer) {
                handledBroadcastRecallVer = com.aurora.chat.PendingBroadcastRecalled.version
                val btid = com.aurora.chat.PendingBroadcastRecalled.taskId
                if (btid > 0L) {
                    adapter.removeByPredicate { it.broadcastTaskId == btid }
                    syncMessages()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.aurora.chat.data.local.MessageDb.deleteByBroadcastTask(ctx, btid)
                    }
                }
            }
            // 服务端删除整个会话 → 本地同步清空（服务器→本地删除链路）
            // 无论是否正在查看该会话都先清理本地落盘与缓存，避免「推送到达时不在该会话」导致事件被丢弃而漏删
            // 闪照实时状态：对方开始查看 → "对方查看中"
            if (com.aurora.chat.PendingFlashViewing.hasUpdate) {
                com.aurora.chat.PendingFlashViewing.hasUpdate = false
                val fid = com.aurora.chat.PendingFlashViewing.messageId
                if (fid > 0L) viewingFlashIds = viewingFlashIds + fid
            }
            // 闪照实时状态：对方查看结束 → 立即切到"对方已查看"（无需退出重进）
            if (com.aurora.chat.PendingFlashViewed.hasUpdate) {
                com.aurora.chat.PendingFlashViewed.hasUpdate = false
                val fid = com.aurora.chat.PendingFlashViewed.messageId
                if (fid > 0L) {
                    partnerViewedFlashIds = partnerViewedFlashIds + fid
                    viewingFlashIds = viewingFlashIds - fid
                }
            }
            // 被移出群聊：如果正停在这个会话里，立刻切成只读态并追加一条居中提示。
            // 不在这个会话也无需处理——GroupExitStore 已经记下了，会话列表会把它保留下来，
            // 下次点进来时 `groupExit` 同步读到记录，输入区一开始就是隐藏的。
            if (com.aurora.chat.PendingGroupKicked.hasUpdate) {
                com.aurora.chat.PendingGroupKicked.hasUpdate = false
                val kConv = com.aurora.chat.PendingGroupKicked.convId
                if (kConv != 0L && kConv == friendId) {
                    val kText = com.aurora.chat.PendingGroupKicked.text
                    if (kText.isNotEmpty()) addLocalNotice(friendId, kText, type = "group_kicked")
                    // 新版：附加消息按服务端**真实形态**补进会话（id 是服务端真身）——
                    //   群身份模式 → 群气泡（与群里其他人看到的一致，而不是把「@某人 …」
                    //                渲染成居中灰字——那是广播形态，两种模式就串了）；
                    //   广播模式   → msg_type=system 的居中提示。
                    // TcpService 已把它们落进 MessageDb（重进会话从本地库还原，按 id 去重），
                    // 这里再补进当前列表并挂到 pendingLocalNotices，保证后续 syncMessages 不冲掉。
                    val kExtras = com.aurora.chat.PendingGroupKicked.extraMsgs
                    if (kExtras.isNotEmpty()) {
                        val kAt = com.aurora.chat.PendingGroupKicked.at
                        for (e in kExtras) {
                            val display = if (isSensitivePunishText(e.text)) {
                                parseSensitivePunishText(e.text, e.target, currentUserName)
                            } else e.text
                            val extraMsg = ChatMsg.create(
                                text = display,
                                isMine = false,
                                fromUserId = friendId,
                                serverId = e.id,
                                isNew = false,
                                senderName = friendName,
                                createdAt = kAt,
                                isSystemNotice = e.type == "system",
                                targetName = e.target
                            )
                            if (messages.none { it.id == extraMsg.id }) {
                                messages.add(extraMsg)
                                if (pendingLocalNotices.none { it.id == extraMsg.id }) {
                                    pendingLocalNotices.add(extraMsg)
                                }
                            }
                        }
                        // 同 syncMessages：时间戳 0 视为最新，绝不允许排到列表头部
                        messages.sortWith(
                            compareBy({ if (it.createdAt <= 0L) Long.MAX_VALUE else it.createdAt }, { it.id })
                        )
                    }
                    // 旧版兜底：服务端没带 extra_msgs 时，附加提示退回本地广播形式
                    for (t in com.aurora.chat.PendingGroupKicked.extraNotices) {
                        addLocalNotice(friendId, t, type = "group_kicked")
                    }
                    // 触发重新读取退出记录 → isExitedGroup 变 true → 输入区收起
                    groupExitTick++
                }
                com.aurora.chat.PendingGroupKicked.extraMsgs = emptyList()
                com.aurora.chat.PendingGroupKicked.extraNotices = emptyList()
            }
            if (com.aurora.chat.PendingConversationDeleted.hasUpdate) {
                com.aurora.chat.PendingConversationDeleted.hasUpdate = false
                val did = com.aurora.chat.PendingConversationDeleted.friendId
                if (did > 0L) {
                    // 本地存储 + 内存缓存一律清理（幂等：重复执行无害，已撤回消息只是同文件中的一行，删一次或删多次结果一致）
                    // 双向同意的清空：服务端删除后向双方各推一条 conversation_deleted，
                    // 两端都会走到这里清掉本地记录 —— 必须对称，不能只清一方。
                    com.aurora.chat.data.repository.LocalMessageStore.deleteConversation(ctx, did)
                    // 本地系统广播一并清掉（拉黑/好友验证提示随会话删除而消失，避免孤儿提示残留）
                    com.aurora.chat.data.local.LocalNoticeStore.clear(ctx, did)
                    // 发送失败的气泡（带红叹号）同样要清：它独立落在 FailedMessageStore，
                    // 之前只清了 LocalMessageStore/LocalNoticeStore，导致"清空后红叹号还挂着"。
                    com.aurora.chat.data.local.FailedMessageStore.clear(ctx, did)
                    if (did == friendId) {
                        pendingLocalNotices.clear()
                        pendingFailedMessages.clear()
                        pendingUploadPlaceholders.clear()
                    }
                    companionMessageCache.remove(did)
                    com.aurora.chat.ui.viewmodel.ChatViewModel.invalidateHotCache(currentUserId, did)
                    if (did == friendId) {
                        // 当前正在查看该会话 → 同步清空内存列表与适配器（再次进入会重新拉取，结果为空）
                        messages.clear()
                        adapter.removeByPredicate { true }
                        uploadProgressMap.clear()
                        uploadStageMap.clear()
                        uploadFileNameMap.clear()
                        uploadRatioMap.clear()
                        syncMessages()
                        com.aurora.chat.ui.viewmodel.ChatViewModel.updateConversationPreview(did, "", System.currentTimeMillis() / 1000)
                    }
                }
            }
        }
    }

    // 键盘弹起时：若用户本就在底部，则把视角重新贴到底部（长消息也不跳到顶部，且不打扰上滑看历史的用户）
    // 只订阅「可见 / 不可见」这个布尔，不订阅 inset 的逐帧数值：
    // 键盘动画每帧都在改 ime bottom，直接读它会让整个聊天屏幕每帧重组 —— 那是切换时卡顿的主因。
    val imeDensity = androidx.compose.ui.platform.LocalDensity.current
    val imeHolder = WindowInsets.ime
    val imeVisible by remember(imeDensity, imeHolder) {
        androidx.compose.runtime.derivedStateOf { imeHolder.getBottom(imeDensity) > 0 }
    }
    LaunchedEffect(imeVisible) {
        // 面板展开时槽位高度恒定（max(imeNet, panelPx) 不变），列表尺寸没变，
        // 不需要重新贴底；这时候滚一下反而就是用户看到的「整个界面往上滑了一下」。
        if (imeVisible && !showStickerPanelSt.value && !showPlusDialogSt.value &&
            messages.isNotEmpty() && atBottomState.value
        ) {
            try { listState.scrollToItem(0) } catch (_: Exception) {}
        }
    }

    // 「回底」按钮显隐 —— 严格按用户定义的手感：
    //   1) 手指上滑累计超过约一屏，才算“离开了底部”；只是上滑（不管滑多远）都不显示按钮，不挡视线；
    //   2) 离开一屏之后，主动往下滑（哪怕只滑一点点）→ 按钮出现；
    //   3) 按钮出现后继续上滑 / 或回到底部 → 立刻收起。
    // 方向与距离用触点位移累计（PointerEventPass.Final 只读不消费，不影响列表滚动），
    // 不依赖 item 高度：轻滑 1px 也能识别方向，长消息不会因 index 不变而判错，惯性 fling 抬指后不误触发。
    val showBackToBottomState = remember { mutableStateOf(false) }
    var showBackToBottom by showBackToBottomState
    val upScrollPxState = remember { mutableStateOf(0f) }
    var upScrollPx by upScrollPxState
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, off) ->
            // ═══ 基于真实位置推导 autoFollow + 钉位 anchor ═══
            // 自身程序化滚动期间跳过，避免被 scrollToItem 中途位置误翻转；
            // 借助 firstVisibleItemIndex/offset（与 scrollToItem 坐标约定一致）做钉位，方向零歧义。
            if (programmaticScrolling || anchorUpdateSuppressed) return@collect
            // 窗口与最新脱钩时，idx==0 只是「窗口里的最新」，不能算贴底：
            // 否则 AI 流式输出会把用户正在读的老历史一路拽到窗口底部。
            val atBottom = !windowDetached.value && idx == 0 && off <= BOTTOM_OFFSET_TOLERANCE_PX
            // 【AI 流式 / 思考气泡展开】item 0 逐帧长高时 idx/off 会跟着抖动，这属于「内容自身变化」，
            // 不是用户手势。旧逻辑把它当成用户上滑：autoFollow 立刻翻成 false 并钉住 anchor，
            // 视角从此被固定在原地，正在输出的内容跑出视野，只能手动往下滑。
            // 现在只有用户亲手滚动（含 fling）造成的离底才关闭跟随；无手势的内容变化保持原值。
            // 自身程序化滚动已在上面用 programmaticScrolling 排除，不会把自己人当成手势。
            if (!atBottom && !listState.isScrollInProgress) return@collect
            if (autoFollow != atBottom) autoFollow = atBottom
            if (atBottom) {
                if (anchorItemIndex != -1) anchorItemIndex = -1
                // 手动滑回底部：未读新消息已看到，清零角标
                if (unreadNewCount != 0) unreadNewCount = 0
                // 回到贴底：上滑累计距离与按钮状态一起归零，下次要重新“上滑一屏”才具备出现资格
                if (upScrollPx != 0f) upScrollPx = 0f
                if (showBackToBottom) showBackToBottom = false
            } else {
                if (anchorItemIndex != idx) anchorItemIndex = idx
                if (anchorItemOffset != off) anchorItemOffset = off
            }
        }
    }
    // 双条件：必须先「上滑超过一屏 + 主动下滑」拿到资格（showBackToBottom），且此刻确实不在底部。
    val shouldShowScrollDown = remember {
        derivedStateOf { showBackToBottom && !atBottomState.value }
    }
    // “上滑一屏”的阈值：按实时可视高度算，键盘弹起 / 横竖屏切换后自动跟随，取 0.9 屏留一点余量。
    fun backToBottomThresholdPx(): Float {
        val h = listState.layoutInfo.viewportSize.height
        return if (h > 0) h * 0.9f else 1200f
    }
    // 切会话时清掉上一个会话遗留的“出场资格”，避免刚进来上滑一点点就能唤出按钮
    LaunchedEffect(friendId) {
        showBackToBottom = false
        upScrollPx = 0f
    }

    val mainScope = remember { ChatMainScope() }
    mainScope.currentUserId = currentUserId
    mainScope.currentUserName = currentUserName
    mainScope.friendId = friendId
    mainScope.friendName = friendName
    mainScope.onBack = onBack
    mainScope.onOpenJoinRequestFullScreen = onOpenJoinRequestFullScreen
    mainScope.onOpenPostDetail = onOpenPostDetail
    mainScope.onOpenGroupDetail = onOpenGroupDetail
    mainScope.onOpenNewChat = onOpenNewChat
    mainScope.onTokenExhausted = onTokenExhausted
    mainScope.onAppAction = onAppAction
    mainScope.contactSource = contactSource
    mainScope.onContactSourceConsumed = onContactSourceConsumed
    mainScope.onOpenShortVideo = onOpenShortVideo
    mainScope.inputTextState = inputTextState
    mainScope.ctx = ctx
    mainScope.confirmedAnnouncementTexts = confirmedAnnouncementTexts
    mainScope.annDetailRawState = annDetailRawState
    mainScope.annDetailTimeState = annDetailTimeState
    mainScope.annImageViewerUrlState = annImageViewerUrlState
    mainScope.annReadCountsState = annReadCountsState
    mainScope.annConfirmCountsState = annConfirmCountsState
    mainScope.annConfirmSheetTextState = annConfirmSheetTextState
    mainScope.draftKey = draftKey
    mainScope.quoteKey = quoteKey
    mainScope.listState = listState
    mainScope.scope = scope
    mainScope.aiUsageState = aiUsageState
    mainScope.BOTTOM_OFFSET_TOLERANCE_PX = BOTTOM_OFFSET_TOLERANCE_PX
    mainScope.autoFollowState = autoFollowState
    mainScope.pendingScrollToBottomState = pendingScrollToBottomState
    mainScope.programmaticScrollingState = programmaticScrollingState
    mainScope.unreadNewCountState = unreadNewCountState
    mainScope.programmaticScroll = { index, offset -> programmaticScroll(index, offset) }
    mainScope.msgBoundsInWindow = msgBoundsInWindow
    mainScope.listViewportInWindowState = listViewportInWindowState
    mainScope.anchorItemIndexState = anchorItemIndexState
    mainScope.anchorItemOffsetState = anchorItemOffsetState
    mainScope.anchorUpdateSuppressedState = anchorUpdateSuppressedState
    mainScope.apiSuspendedDialogState = apiSuspendedDialogState
    mainScope.groupMemberNamesState = groupMemberNamesState
    mainScope.groupMemberIdMapState = groupMemberIdMapState
    mainScope.groupRolesState = groupRolesState
    mainScope.isLoadingMessagesState = isLoadingMessagesState
    mainScope.initialLoadFailedState = initialLoadFailedState
    mainScope.loadRetryTickState = loadRetryTickState
    mainScope.groupExitTickState = groupExitTickState
    mainScope.groupExit = groupExit
    mainScope.isExitedGroup = isExitedGroup
    mainScope.scrollReadyState = scrollReadyState
    mainScope.recallTickState = recallTickState
    mainScope.uploadProgressMap = uploadProgressMap
    mainScope.uploadStageMap = uploadStageMap
    mainScope.uploadRatioMap = uploadRatioMap
    mainScope.uploadFileNameMap = uploadFileNameMap
    mainScope.replyToMsg = replyToMsg
    mainScope.quoteFocusTickState = quoteFocusTickState
    mainScope.showPlusDialogState = showPlusDialogState
    mainScope.showStickerPanelState = showStickerPanelState
    mainScope.inputBarTopYState = inputBarTopYState
    mainScope.showStickerPanelSt = showStickerPanelSt
    mainScope.showPlusDialogSt = showPlusDialogSt
    mainScope.inputBarTopYSt = inputBarTopYSt
    mainScope.showTransferDialogState = showTransferDialogState
    mainScope.showRedPacketDialogState = showRedPacketDialogState
    mainScope.redPacketDetailIdState = redPacketDetailIdState
    mainScope.redpacketStatus = redpacketStatus
    mainScope.showCameraState = showCameraState
    mainScope.showPrivacyPanelState = showPrivacyPanelState
    mainScope.mutedUntilState = mutedUntilState
    mainScope.showLocationConfirmState = showLocationConfirmState
    mainScope.showTimePointDialogState = showTimePointDialogState
    mainScope.showConfirmSpeechDialogState = showConfirmSpeechDialogState
    mainScope.showAiImageNoticeState = showAiImageNoticeState
    mainScope.confirmSpeechEnabledState = confirmSpeechEnabledState
    mainScope.currentTimePointState = currentTimePointState
    mainScope.confirmSpeechPendingState = confirmSpeechPendingState
    mainScope.pendingAiImagesState = pendingAiImagesState
    mainScope.aiImagePicker = aiImagePicker
    mainScope.pendingAiFilesState = pendingAiFilesState
    mainScope.wsPickModeState = wsPickModeState
    mainScope.showPlanEditState = showPlanEditState
    mainScope.planEditTextState = planEditTextState
    mainScope.wsInjectForSendState = wsInjectForSendState
    mainScope.aiFilePicker = aiFilePicker
    mainScope.adapter = adapter
    mainScope.messages = messages
    mainScope.pendingUploadPlaceholders = pendingUploadPlaceholders
    mainScope.pendingFailedMessages = pendingFailedMessages
    mainScope.retryTargetMsgState = retryTargetMsgState
    mainScope.markSendFailed = { ref -> markSendFailed(ref) }
    mainScope.addLocalNotice = { convId, text, type -> addLocalNotice(convId, text, type) }
    mainScope.resendFailedMessage = { m -> resendFailedMessage(m) }
    mainScope.insertAiMediaCard = { type, mediaUrl, afterMsgId -> insertAiMediaCard(type, mediaUrl, afterMsgId) }
    mainScope.insertAiFileCard = { absPath, forceFileCard, afterMsgId -> insertAiFileCard(absPath, forceFileCard, afterMsgId) }
    mainScope.scheduleAiDebouncedSave = { scheduleAiDebouncedSave() }
    mainScope.updatePlanStep = { stepIndex, status -> updatePlanStep(stepIndex, status) }
    mainScope.openPlanEditor = { openPlanEditor() }
    mainScope.savePlanStepsFromEditor = { savePlanStepsFromEditor() }
    mainScope.syncMessages = { syncMessages() }
    mainScope.showEditDialogState = showEditDialogState
    mainScope.editTargetMsgIdState = editTargetMsgIdState
    mainScope.editReasoningTextState = editReasoningTextState
    mainScope.editOutputTextState = editOutputTextState
    mainScope.resendNewChatConfirmState = resendNewChatConfirmState
    mainScope.resendEditMsgIdState = resendEditMsgIdState
    mainScope.resendEditTextState = resendEditTextState
    mainScope.showBroadcastSendDialogState = showBroadcastSendDialogState
    mainScope.broadcastManageMsgIdState = broadcastManageMsgIdState
    mainScope.broadcastManageTaskIdState = broadcastManageTaskIdState
    mainScope.aiFileSendingTipState = aiFileSendingTipState
    mainScope.viewOrderIdState = viewOrderIdState
    mainScope.agentAccessModeState = agentAccessModeState
    mainScope.agentModeState = agentModeState
    mainScope.sendUserTurnState = sendUserTurnState
    mainScope.resumeTaskPendingState = resumeTaskPendingState
    mainScope.showThinkingLevelMenuState = showThinkingLevelMenuState
    mainScope.thinkingLevelState = thinkingLevelState
    mainScope.execModesState = execModesState
    mainScope.showWorkspaceFilesState = showWorkspaceFilesState
    mainScope.workspaceLocateNameState = workspaceLocateNameState
    mainScope.workspaceLocateOpenState = workspaceLocateOpenState
    mainScope.showUsageHistoryState = showUsageHistoryState
    mainScope.showBackupScreenState = showBackupScreenState
    mainScope.aiChangesOpenState = aiChangesOpenState
    mainScope.aiChangesJsonDataState = aiChangesJsonDataState
    mainScope.aiChangesMsgIdState = aiChangesMsgIdState
    mainScope.aiChangesFocusPathState = aiChangesFocusPathState
    mainScope.aiChangesFocusDetailState = aiChangesFocusDetailState
    mainScope.aiToolCallsOpenState = aiToolCallsOpenState
    mainScope.aiToolCallsDataState = aiToolCallsDataState
    mainScope.modeExplainDialogState = modeExplainDialogState
    mainScope.pendingToolApprovalState = pendingToolApprovalState
    mainScope.pendingToolApprovalDeferred = pendingToolApprovalDeferred
    mainScope.approvalMinimizedState = approvalMinimizedState
    mainScope.approvalTrustUntilState = approvalTrustUntilState
    mainScope.toolApprovalHook = toolApprovalHook
    mainScope.closeStaleAiWaiting = { keepId -> closeStaleAiWaiting(keepId) }
    mainScope.onConfirmReply = onConfirmReply
    mainScope.myAvatarBitmapState = myAvatarBitmapState
    mainScope.destroyedFlashIdsState = destroyedFlashIdsState
    mainScope.partnerViewedFlashIdsState = partnerViewedFlashIdsState
    mainScope.viewingFlashIdsState = viewingFlashIdsState
    mainScope.saveDestroyedFlash = { id, mediaUrl -> saveDestroyedFlash(id, mediaUrl) }
    mainScope.saveFlashViewStart = { id -> saveFlashViewStart(id) }
    mainScope.openMenuMsgIdState = openMenuMsgIdState
    mainScope.redPacketMenuMsgIdState = redPacketMenuMsgIdState
    mainScope.redPacketMenuAnchorState = redPacketMenuAnchorState
    mainScope.announcementMenuMsgIdState = announcementMenuMsgIdState
    mainScope.announcementMenuAnchorState = announcementMenuAnchorState
    mainScope.clearSelectionKeyState = clearSelectionKeyState
    mainScope.multiSelectModeState = multiSelectModeState
    mainScope.selectedMsgIds = selectedMsgIds
    mainScope.multiDeleteConfirmState = multiDeleteConfirmState
    mainScope.chatShareCardTextState = chatShareCardTextState
    mainScope.showChatShareDialogState = showChatShareDialogState
    mainScope.chatShareViewTextState = chatShareViewTextState
    mainScope.chatShareViewMsgIdState = chatShareViewMsgIdState
    mainScope.selectAnchorMsgIdState = selectAnchorMsgIdState
    mainScope.selectToHereDirState = selectToHereDirState
    mainScope.exitMultiSelect = { exitMultiSelect() }
    mainScope.highlightedMsgIdState = highlightedMsgIdState
    mainScope.jumpToMessageIdState = jumpToMessageIdState
    mainScope.imagePreviewUrlsState = imagePreviewUrlsState
    mainScope.imagePreviewIndexState = imagePreviewIndexState
    mainScope.imageBounds = imageBounds
    mainScope.heroInitialIndexState = heroInitialIndexState
    mainScope.previewSessionState = previewSessionState
    mainScope.previewReadyState = previewReadyState
    mainScope.heroEnterDoneState = heroEnterDoneState
    mainScope.heroSourceRectState = heroSourceRectState
    mainScope.heroRectFor = { rawUrl, preferQuote -> heroRectFor(rawUrl, preferQuote) }
    mainScope.openVideoPlayer = { url, preferQuote -> openVideoPlayer(url, preferQuote) }
    mainScope.headerTopPadding = headerTopPadding
    mainScope.filePreviewTargetState = filePreviewTargetState
    mainScope.filePreviewVisibleState = filePreviewVisibleState
    mainScope.fileDownloadConfirmState = fileDownloadConfirmState
    mainScope.startChatFileDownload = { url, fileName -> startChatFileDownload(url, fileName) }
    mainScope.deleteConfirmMsgIdState = deleteConfirmMsgIdState
    mainScope.muteTargetUserIdState = muteTargetUserIdState
    mainScope.muteTargetUserNameState = muteTargetUserNameState
    mainScope.isGroupOwnerState = isGroupOwnerState
    mainScope.deletedMessageIdsState = deletedMessageIdsState
    mainScope.focusManager = focusManager
    mainScope.view = view
    mainScope.showGroupSettingsState = showGroupSettingsState
    mainScope.showFriendSettingsState = showFriendSettingsState
    mainScope.showChatHistorySearchState = showChatHistorySearchState
    mainScope.chatSearchState = chatSearchState
    mainScope.searchReturnPendingState = searchReturnPendingState
    mainScope.showProfileState = showProfileState
    mainScope.profileUserIdState = profileUserIdState
    mainScope.profileUserNameState = profileUserNameState
    mainScope.inviteRespondState = inviteRespondState
    mainScope.showAiUsageState = showAiUsageState
    mainScope.showFreeApiSelectState = showFreeApiSelectState
    mainScope.showPaidApiSelectState = showPaidApiSelectState
    mainScope.readSheetMsgIdState = readSheetMsgIdState
    mainScope.animMode = animMode
    mainScope.advancedAnim = advancedAnim
    mainScope.myBubbleColor = myBubbleColor
    mainScope.otherBubbleColor = otherBubbleColor
    mainScope.recentlySentMessages = recentlySentMessages
    mainScope.isAdminOrOwnerState = isAdminOrOwnerState
    mainScope.pendingCountState = pendingCountState
    mainScope.isNotGroupMemberState = isNotGroupMemberState
    mainScope.popupAnnouncementState = popupAnnouncementState
    mainScope.isFriendChatState = isFriendChatState
    mainScope.friendStatusRefreshState = friendStatusRefreshState
    mainScope.payDisabledState = payDisabledState
    mainScope.payBlockReasonState = payBlockReasonState
    mainScope.showAddFriendGreetingState = showAddFriendGreetingState
    mainScope.INITIAL_WINDOW = INITIAL_WINDOW
    mainScope.loadMoreProgressState = loadMoreProgressState
    mainScope.dbHasOlderState = dbHasOlderState
    mainScope.aiConvs = aiConvs
    mainScope.showAiConvDrawerState = showAiConvDrawerState
    mainScope.activeAiConvIdState = activeAiConvIdState
    mainScope.showBackToBottomState = showBackToBottomState
    mainScope.upScrollPxState = upScrollPxState
    mainScope.shouldShowScrollDown = shouldShowScrollDown
    mainScope.backToBottomThresholdPx = { backToBottomThresholdPx() }
    mainScope.revealMessageRow = { displayIdx, msgId -> revealMessageRow(displayIdx, msgId) }
    mainScope.flashMessage = { msgId -> flashMessage(msgId) }
    mainScope.ChatConversationMain()

    ChatConversationOverlays(
        currentUserId = currentUserId,
        currentUserName = currentUserName,
        friendId = friendId,
        friendName = friendName,
        onBack = onBack,
        onOpenShortVideo = onOpenShortVideo,
        onOpenPostDetail = onOpenPostDetail,
        onOpenGroupDetail = onOpenGroupDetail,
        onLocateInChat = { id ->
            jumpToMessageId = id
            // 预览若是从「查找聊天记录」打开的，定位时必须把覆盖层一起收掉，
            // 否则跳转后的消息被搜索页 / 资料面板盖住，看起来像「点了没反应」。
            showChatHistorySearch = false
            showFriendSettings = false
            showGroupSettings = false
        },
        ctx = ctx,
        scope = scope,
        listState = listState,
        messages = messages,
        previewExtraRefs = chatSearchState.previewRefs,
        pendingUploadPlaceholders = pendingUploadPlaceholders,
        aiConvs = aiConvs,
        redpacketStatus = redpacketStatus,
        uploadProgressMap = uploadProgressMap,
        uploadStageMap = uploadStageMap,
        uploadRatioMap = uploadRatioMap,
        uploadFileNameMap = uploadFileNameMap,
        focusManager = focusManager,
        imageBounds = imageBounds,
        heroProgress = heroProgress,
        exitFade = exitFade,
        videoProgress = videoProgress,
        aiAvatarLauncher = aiAvatarLauncher,
        closeVideoPlayer = { closeVideoPlayer() },
        deleteAiConv = { id -> deleteAiConv(id) },
        deleteAiConvs = { ids -> deleteAiConvs(ids) },
        openAiConversation = { id -> openAiConversation(id) },
        openVideoPlayer = { url -> openVideoPlayer(url) },
        renameAiConv = { id, title -> renameAiConv(id, title) },
        saveChatImageToGallery = { url -> saveChatImageToGallery(url) },
        startChatFileDownload = { url, fileName -> startChatFileDownload(url, fileName) },
        startFileSaveAs = { url, fileName, ext -> startFileSaveAs(url, fileName, ext) },
        startNewAiConversation = { startNewAiConversation() },
        activeAiConvIdState = activeAiConvIdState,
        aiNameInputState = aiNameInputState,
        chatShareViewMsgIdState = chatShareViewMsgIdState,
        chatShareViewTextState = chatShareViewTextState,
        fileDownloadConfirmState = fileDownloadConfirmState,
        filePreviewTargetState = filePreviewTargetState,
        filePreviewVisibleState = filePreviewVisibleState,
        groupAtMembersState = groupAtMembersState,
        heroClosingState = heroClosingState,
        heroEnterDoneState = heroEnterDoneState,
        heroInitialIndexState = heroInitialIndexState,
        heroReopeningState = heroReopeningState,
        heroSourceRectState = heroSourceRectState,
        imagePreviewCloserState = imagePreviewCloserState,
        imagePreviewIndexState = imagePreviewIndexState,
        imagePreviewUrlsState = imagePreviewUrlsState,
        imageSaveConfirmState = imageSaveConfirmState,
        inputTextState = inputTextState,
        inviteRespondState = inviteRespondState,
        isAdminOrOwnerState = isAdminOrOwnerState,
        previewReadyState = previewReadyState,
        previewSessionState = previewSessionState,
        privacyRefreshKeyState = privacyRefreshKeyState,
        profileUserIdState = profileUserIdState,
        profileUserNameState = profileUserNameState,
        redPacketDetailIdState = redPacketDetailIdState,
        showAiConvDrawerState = showAiConvDrawerState,
        showAiNameInputState = showAiNameInputState,
        showAiNewConfirmState = showAiNewConfirmState,
        showAiUsageState = showAiUsageState,
        showCameraState = showCameraState,
        showLocationConfirmState = showLocationConfirmState,
        showLocationRequestState = showLocationRequestState,
        showPrivacyPanelState = showPrivacyPanelState,
        showProfileState = showProfileState,
        showRedPacketDialogState = showRedPacketDialogState,
        showImageShareSheetState = showImageShareSheetState,
        showTransferDialogState = showTransferDialogState,
        videoHeroRectState = videoHeroRectState,
        videoOverlaySizeState = videoOverlaySizeState,
        videoPlayUrlState = videoPlayUrlState,
        videoSessionState = videoSessionState,
    )
}
// 把某条 AI 回复的状态/正文合并写回指定会话文件（回复收尾时用户已切走该会话时使用），不动当前内存列表
internal fun patchAiReplyInFile(
    ctx: android.content.Context, userId: Long, key: String, msgId: Long,
    text: String? = null, reasoningText: String? = null, manualStopped: Boolean? = null,
    thinkingSeconds: Long? = null, totalSeconds: Long? = null,
    inputTokens: Long? = null, outputTokens: Long? = null, cacheHitTokens: Long? = null, contextLength: Long? = null,
    aiCostDeducted: Long? = null,
    aiUsageAuthoritative: Boolean? = null,
    mediaType: String? = null, mediaUrl: String? = null,
    fileChangesJson: String? = null,
    toolCallCount: Int? = null,
    toolStages: List<ChatMsg.ToolStageRecord>? = null
) {
    val arr = loadLocalChatMessages(ctx, userId, key) ?: return
    val out = mutableListOf<ChatMsg>()
    var touched = false
    for (i in 0 until arr.length()) {
        try {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id", 0L)
            if (obj.optBoolean("revoked", false)) continue
            if (obj.optBoolean("is_tool_status", false)) continue
            if (!touched && id == msgId) {
                val isMine = obj.optBoolean("is_mine", false)
                var nm = ChatMsg.create(
                    serverId = id,
                    text = obj.optString("text", ""),
                    isMine = isMine,
                    fromUserId = obj.optLong("from_user_id", if (isMine) userId else AI_CHAT_ID),
                    isNew = false,
                    senderName = obj.optString("sender_name", ""),
                    createdAt = obj.optLong("created_at", 0),
                    mediaType = obj.optString("media_type", ""),
                    mediaUrl = obj.optString("media_url", ""),
                    reasoningText = obj.optString("reasoning_text", ""),
                    thinkingSeconds = obj.optLong("thinking_seconds", 0),
                    totalSeconds = obj.optLong("total_seconds", 0),
                    inputTokens = obj.optLong("input_tokens", 0),
                    outputTokens = obj.optLong("output_tokens", 0),
                    cacheHitTokens = obj.optLong("cache_hit_tokens", 0),
                    contextLength = obj.optLong("context_length", 0),
                    aiCostDeducted = obj.optLong("ai_cost_deducted", 0),
                    aiMode = obj.optString("ai_mode", ""),
                    aiToolStage = obj.optString("ai_tool_stage", ""),
                    aiToolStageState = obj.optInt("ai_tool_stage_state", -1),
                    isToolStatus = obj.optBoolean("is_tool_status", false),
                    aiToolStages = parseToolStages(obj), aiFileChanges = obj.optString("ai_file_changes", ""), aiToolCallCount = obj.optInt("ai_tool_call_count", 0)
                ).withKnownMediaType()
                if (text != null) nm = nm.copy(text = text, aiWaiting = false)
                if (reasoningText != null) nm = nm.copy(reasoningText = reasoningText)
                if (manualStopped != null) nm = nm.copy(manualStopped = manualStopped)
                if (thinkingSeconds != null) nm = nm.copy(thinkingSeconds = thinkingSeconds)
                if (totalSeconds != null) nm = nm.copy(totalSeconds = totalSeconds)
                if (inputTokens != null) nm = nm.copy(inputTokens = inputTokens)
                if (outputTokens != null) nm = nm.copy(outputTokens = outputTokens)
                if (cacheHitTokens != null) nm = nm.copy(cacheHitTokens = cacheHitTokens)
                if (contextLength != null) nm = nm.copy(contextLength = contextLength)
                if (aiCostDeducted != null) nm = nm.copy(aiCostDeducted = aiCostDeducted)
                if (aiUsageAuthoritative != null) nm = nm.copy(aiUsageAuthoritative = aiUsageAuthoritative)
                if (mediaType != null) nm = nm.copy(mediaType = mediaType)
                if (mediaUrl != null) nm = nm.copy(mediaUrl = mediaUrl)
                if (fileChangesJson != null) nm = nm.copy(aiFileChanges = fileChangesJson)
                if (toolCallCount != null) nm = nm.copy(aiToolCallCount = toolCallCount)
                if (toolStages != null) nm = nm.copy(aiToolStages = toolStages)
                out.add(nm)
                touched = true
            } else {
                val isMine = obj.optBoolean("is_mine", false)
                out.add(ChatMsg.create(
                    serverId = id,
                    text = obj.optString("text", ""),
                    isMine = isMine,
                    fromUserId = obj.optLong("from_user_id", if (isMine) userId else AI_CHAT_ID),
                    isNew = false,
                    senderName = obj.optString("sender_name", ""),
                    createdAt = obj.optLong("created_at", 0),
                    mediaType = obj.optString("media_type", ""),
                    mediaUrl = obj.optString("media_url", ""),
                    reasoningText = obj.optString("reasoning_text", ""),
                    thinkingSeconds = obj.optLong("thinking_seconds", 0),
                    totalSeconds = obj.optLong("total_seconds", 0),
                    inputTokens = obj.optLong("input_tokens", 0),
                    outputTokens = obj.optLong("output_tokens", 0),
                    cacheHitTokens = obj.optLong("cache_hit_tokens", 0),
                    contextLength = obj.optLong("context_length", 0),
                    aiCostDeducted = obj.optLong("ai_cost_deducted", 0),
                    aiMode = obj.optString("ai_mode", ""),
                    aiToolStage = obj.optString("ai_tool_stage", ""),
                    aiToolStageState = obj.optInt("ai_tool_stage_state", -1),
                    isToolStatus = obj.optBoolean("is_tool_status", false),
                    aiToolStages = parseToolStages(obj), aiFileChanges = obj.optString("ai_file_changes", ""), aiToolCallCount = obj.optInt("ai_tool_call_count", 0)
                ).withKnownMediaType())
            }
        } catch (_: Exception) {}
    }
    if (touched) saveLocalChatMessages(ctx, userId, key, out)
}

/**
 * 任务收尾:把遗留的工具状态行(isToolStatus=true 的运行期消息)逐条移除,每条间隔 60ms,
 * 形成"依次收回"的动画,列表随之平滑收拢。已调用/调用失败/调用中一律不留——
 * 调用明细已汇聚到消息底部的「调用 N 个工具」按钮,点开看全屏清单。
 */
private suspend fun collapseToolStatusMessages(messages: MutableList<IMessageRef>) {
    val ids = messages.filter { (it as? ChatMsg)?.isToolStatus == true }.map { it.id }
    for (id in ids) {
        messages.removeAll { it.id == id }
        kotlinx.coroutines.delay(60)
    }
}

// ═════════════════════════════════════════════════════
    // ── 转账全屏页 ──
    @Composable
    private fun TransferScreen(
        friendId: Long,
        currentUserId: Long,
        currentUserName: String,
        friendName: String,
        messages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>,
        ctx: Context,
        onDismiss: () -> Unit
    ) {
        val isGroup = isGroupChat(friendId)
        var members by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
        var loading by remember { mutableStateOf(isGroup) }
        var keyword by remember { mutableStateOf("") }
        // 当前正在输入金额的收款人（null = 未打开金额弹窗）
        var payTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
        var amountText by remember { mutableStateOf("") }
        var sending by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(friendId, currentUserId) {
            if (!isGroup) {
                members = listOf(friendId to friendName)
                loading = false
            } else {
                loading = true
                try {
                    val gid = -(friendId + 1000)
                    val r = ChatRepository.getGroupMembers(gid)
                    if (r.success && r.data != null) {
                        val list = mutableListOf<Pair<Long, String>>()
                        for (i in 0 until r.data!!.length()) {
                            val o = r.data!!.optJSONObject(i) ?: continue
                            val id = if (o.has("user_id")) o.optLong("user_id") else o.optLong("id")
                            val name = o.optString("username", "").ifBlank { "用户$id" }
                            if (id != currentUserId && id > 0) list.add(id to name)
                        }
                        members = list
                    }
                } catch (_: Exception) { }
                loading = false
            }
        }

        // 搜索过滤：姓名包含关键字，或直接搜用户 id
        val filtered = remember(members, keyword) {
            if (keyword.isBlank()) members else members.filter { (id, name) ->
                name.contains(keyword, ignoreCase = true) || id.toString().contains(keyword)
            }
        }

        Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).groupPageTouchGuard()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                GroupPageHeader(title = "转账", onBack = onDismiss)

                // 顶部输入框：群聊按成员姓名 / id 过滤；单聊只有一个人，不需要搜索
                if (isGroup) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        BasicTextField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                            singleLine = true,
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                            decorationBox = { inner ->
                                Box {
                                    if (keyword.isEmpty()) Text("搜索成员...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    inner()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

                when {
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color(0xFF1E40AF), strokeWidth = 3.dp)
                    }
                    filtered.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            if (members.isEmpty()) "群内暂无可转账成员" else "没有匹配的成员",
                            fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()
                        )
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
                        items(filtered, key = { it.first }) { (uid, name) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(userId = uid, userName = name, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "转账", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1E40AF),
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(com.aurora.chat.ui.theme.appSelectedBg())
                                        .clickable {
                                            amountText = ""
                                            payTarget = uid to name
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                        }
                    }
                }
            }

            // 点击行内「转账」→ 金额输入弹窗
            payTarget?.let { (targetId, targetName) ->
                val amount = amountText.toLongOrNull() ?: 0L
                val canSend = amount > 0 && !sending
                AlertDialog(
                    onDismissRequest = { if (!sending) payTarget = null },
                    containerColor = com.aurora.chat.ui.theme.appSurface(),
                    title = { Text("转账给 $targetName", fontWeight = FontWeight.SemiBold) },
                    text = {
                        Column {
                            Text("转账金额（token）", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Spacer(Modifier.height(6.dp))
                            BasicTextField(
                                value = amountText,
                                onValueChange = { amountText = it.filter { c -> c.isDigit() } },
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(com.aurora.chat.ui.theme.appCardBg())
                                    .padding(12.dp),
                                textStyle = TextStyle(fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                                singleLine = true,
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                                // 空值时也要调用 inner()，否则输入光标不渲染
                                decorationBox = { inner ->
                                    Box {
                                        if (amountText.isEmpty()) Text("请输入数量", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                        inner()
                                    }
                                }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = canSend,
                            onClick = {
                                if (!canSend) return@TextButton
                                sending = true
                                scope.launch {
                                    try {
                                        val r = AuroraApi.transfer(friendId, targetId, targetName, amount)
                                        if (r.success) {
                                            val senderName = currentUserName.ifEmpty {
                                                ctx.getSharedPreferences("aurora_login", android.content.Context.MODE_PRIVATE).getString("username", "") ?: ""
                                            }
                                            val d = r.data
                                            val transferId = d?.optLong("transfer_id", 0L) ?: 0L
                                            val createdAt = d?.optLong("created_at", 0L)?.takeIf { it > 0L }
                                                ?: (System.currentTimeMillis() / 1000)
                                            val content = buildTransferContent(
                                                currentUserId, senderName, targetId, targetName, amount,
                                                transferId = transferId,
                                                status = TRANSFER_STATUS_PENDING,
                                                createdAt = createdAt
                                            )
                                            messages.add(ChatMsg.create(text = content, isMine = true, fromUserId = currentUserId, isNew = true, mediaType = "transfer", createdAt = System.currentTimeMillis() / 1000))
                                            ChatViewModel.updateConversationPreview(friendId, "[转账]", System.currentTimeMillis() / 1000)
                                            ChatViewModel.notifyNewMessage()
                                            Toast.makeText(ctx, "转账成功", Toast.LENGTH_SHORT).show()
                                            payTarget = null
                                            onDismiss()
                                        } else {
                                            Toast.makeText(ctx, r.message, Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "转账失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        sending = false
                                    }
                                }
                            }
                        ) {
                            Text(
                                if (amount > 0) "确认转账 $amount token" else "确认转账",
                                color = if (canSend) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint()
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(enabled = !sending, onClick = { payTarget = null }) {
                            Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                        }
                    }
                )
            }
        }
    }

    // 隐私控制面板组件 — 从右侧滑入，显示双方隐私设置
    // ═════════════════════════════════════════════════════
    @Composable
    private fun PrivacySettingsPanel(
    friendId: Long,
    currentUserId: Long,
    onClose: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var mySettings by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var friendSettings by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showNoExitInfo by remember { mutableStateOf(false) }
    // 未开启无障碍就想打开「禁止退出」时的拦截提示
    var showA11yRequired by remember { mutableStateOf(false) }

    // ── 液态模式：隐私开关用液态玻璃 Toggle；经典模式保持 Material3 Switch ──

    // 加载双方隐私设置
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val myResult = AuroraApi.getPrivacySettings(friendId)
                mySettings = myResult.data
            } catch (_: Exception) {}
            try {
                // 查询对方对当前用户的隐私设置（禁止截图/录屏/退出）
                val fr = AuroraApi.getFriendPrivacySettings(friendId)
                friendSettings = fr.data
            } catch (_: Exception) {}
            // 「禁止退出」互锁：把双方开关写进全局锁状态（双方都开启才真正锁定）。
            // 两侧都拿到才写：任一接口失败时保持原状态，避免把已有锁定误判成未锁定（fail-closed）。
            val mine = mySettings
            val theirs = friendSettings
            if (mine != null && theirs != null) {
                com.aurora.chat.PrivacyLockState.setPair(
                    friendId,
                    mine.optBoolean("no_exit", false),
                    theirs.optBoolean("no_exit", false)
                )
            }
            loading = false
        }
    }

    // 对方开关一变（TCP privacy_alert 推送）就立刻重拉对方设置并刷新互锁状态：
    // 让「双方都开启的那一刻」在面板上瞬间变成「已生效」，不用退出重进。
    val peerTick by ChatViewModel.privacyRefreshKey.collectAsState()
    LaunchedEffect(peerTick) {
        if (peerTick <= 0 || friendId <= 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val fr = AuroraApi.getFriendPrivacySettings(friendId)
                val data = fr.data
                if (fr.success && data != null) {
                    friendSettings = data
                    val mine = mySettings
                    if (mine != null) {
                        com.aurora.chat.PrivacyLockState.setPair(
                            friendId,
                            mine.optBoolean("no_exit", false),
                            data.optBoolean("no_exit", false)
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).padding(top = 48.dp)) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF1E40AF))
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                // 顶栏
                Text("隐私控制", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.height(20.dp))

                // ── 我的设置 ──
                Text("我的设置", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFF1E40AF))
                Spacer(Modifier.height(12.dp))

                // 禁止退出（我方开启→我方锁定退出；双方都开启→双方都锁定；独立开关，无需对方同意）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("禁止退出", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.weight(1f))
                    Text(
                        text = "?",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E40AF),
                        modifier = Modifier
                            .clickable { showNoExitInfo = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Switch(
                        checked = mySettings?.optBoolean("no_exit", false) ?: false,
                        onCheckedChange = { v ->
                            // 开启前必须先有无障碍：没有它既拦不住返回键、也拉不回前台，
                            // 开关会变成一个假的"已开启"。这里直接不让开，并引导去系统设置。
                            if (v && !com.aurora.chat.PrivacyAccessibilityService.isEnabledInSystem(ctx)) {
                                showA11yRequired = true
                            } else {
                                // 立即更新本地 UI（创建新对象触发 Compose 重组）
                                val newObj = org.json.JSONObject(mySettings?.toString() ?: "{}")
                                newObj.put("no_exit", v)
                                mySettings = newObj
                                // 同步全局互锁状态：两个值此刻都已知，直接写入。
                                // （此前用 setMine 会先清掉"对方"那一侧，导致双方都已开启却锁不上。）
                                com.aurora.chat.PrivacyLockState.setPair(
                                    friendId, v,
                                    friendSettings?.optBoolean("no_exit", false) ?: false
                                )
                                scope.launch {
                                    AuroraApi.updatePrivacySettings(friendId, false, v,
                                        mySettings?.optBoolean("no_screenshot", false) ?: false,
                                        mySettings?.optBoolean("no_recording", false) ?: false,
                                        mySettings?.optBoolean("detect_screenshot", false) ?: false,
                                        mySettings?.optBoolean("detect_recording", false) ?: false)
                                    AuroraApi.sendPrivacyAlert(friendId, "no_exit_toggle", v)
                                    // 我方值已落库，再核对一次对方的最新开关：
                                    // 面板是进入时拉的，若对方在此之后才开启，本地还是旧值，
                                    // 不补这一下就会出现「双方都开着却锁不上」。
                                    com.aurora.chat.PrivacyLockState.refresh(friendId)
                                }
                            }
                        }
                    )
                }
                // 生效状态提示：无障碍 + 双方都开启，才真正锁住
                if (mySettings?.optBoolean("no_exit", false) == true) {
                    val friendOn = friendSettings?.optBoolean("no_exit", false) == true
                    val a11yOn = com.aurora.chat.PrivacyAccessibilityService.isEnabledInSystem(ctx)
                    Text(
                        text = when {
                            !a11yOn -> "无障碍未开启，锁定不会生效——请先到系统设置开启无障碍"
                            friendOn -> "已生效：双方都已开启，双方都无法退出应用"
                            else -> "等待对方开启「禁止退出」——双方都开启才会真正锁定"
                        },
                        fontSize = 12.sp,
                        color = if (friendOn && a11yOn) Color(0xFFDC2626) else com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
                    )
                }

                // 「禁止截图」与「禁止录屏」已合并为一个开关。
                // 底层 no_screenshot / no_recording 两个字段仍分开存储（保持向后兼容、不动既有拦截与
                // FLAG_SECURE 判定逻辑），只是由同一个开关同时驱动；广播也只发一条，
                // 避免出现「开启了禁止截图」+「开启了禁止录屏」两条重复提示。
                PrivacySettingRow(
                    "禁止截图 / 录屏",
                    (mySettings?.optBoolean("no_screenshot", false) ?: false) ||
                        (mySettings?.optBoolean("no_recording", false) ?: false),
                    onToggle = { v ->
                    val newObj = org.json.JSONObject(mySettings?.toString() ?: "{}")
                    newObj.put("no_screenshot", v)
                    newObj.put("no_recording", v)
                    mySettings = newObj
                    scope.launch {
                        AuroraApi.updatePrivacySettings(friendId, false,
                            mySettings?.optBoolean("no_exit", false) ?: false, v, v,
                            mySettings?.optBoolean("detect_screenshot", false) ?: false,
                            mySettings?.optBoolean("detect_recording", false) ?: false)
                        AuroraApi.sendPrivacyAlert(friendId, "no_capture_toggle", v)
                    }
                }
                )

                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(com.aurora.chat.ui.theme.appDivider()))
                Spacer(Modifier.height(24.dp))

                // ── 对方设置（只读显示） ──
                Text("对方设置", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(12.dp))
                FriendSettingRow("禁止退出", friendSettings?.optBoolean("no_exit", false) ?: false)
                // 已合并展示：任一项开启即视为对方要求禁止截屏（与实际 FLAG_SECURE 判定保持一致）
                FriendSettingRow(
                    "禁止截图 / 录屏",
                    (friendSettings?.optBoolean("no_screenshot", false) ?: false) ||
                        (friendSettings?.optBoolean("no_recording", false) ?: false)
                )

                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.weight(1f))

                // 关闭按钮
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                ) {
                    Text("关闭", color = Color.White, fontSize = 15.sp)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // 禁止退出帮助弹窗
    if (showNoExitInfo) {
        AlertDialog(
            onDismissRequest = { showNoExitInfo = false },
            title = { Text("禁止退出", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    "双方互锁：\n\n" +
                    "• 双方都开启 → 双方都被锁在应用内，无法退出\n" +
                    "• 任一方关闭 → 双方立即解除\n" +
                    "• 生效时按返回键、回到桌面、切到其它应用，都会被立刻拉回\n" +
                    "• 需开启无障碍权限才能生效",
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showNoExitInfo = false }) {
                    Text("我知道了", color = Color(0xFF1E40AF))
                }
            }
        )
    }

    // 未开启无障碍就想打开「禁止退出」→ 拦住并引导去系统设置
    if (showA11yRequired) {
        AlertDialog(
            onDismissRequest = { showA11yRequired = false },
            title = { Text("需要先开启无障碍", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    "「禁止退出」依赖无障碍服务才能拦截返回键、并在你离开应用时立刻把你拉回。\n\n" +
                    "请先在「系统设置 → 无障碍」中开启 Aurora Chat 的服务，再回来打开这个开关。",
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showA11yRequired = false
                    com.aurora.chat.PhoneControl.openSettings(ctx)
                }) {
                    Text("去开启", color = Color(0xFF1E40AF))
                }
            },
            dismissButton = {
                TextButton(onClick = { showA11yRequired = false }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }
}

@Composable
private fun PrivacySettingRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun FriendSettingRow(label: String, checked: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = false)
    }
}

// ==================== 订单详情（从通知中心「查看订单」进入） ====================

@Composable
private fun OrderDetailView(orderId: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf<com.aurora.chat.data.api.AuroraApi.UserOrderDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(orderId) {
        loading = true
        errMsg = null
        order = null
        scope.launch {
            try {
                val r = com.aurora.chat.data.api.AuroraApi.getOrderById(orderId)
                if (r.success && r.data != null) order = r.data
                else errMsg = r.message
            } catch (e: Exception) {
                errMsg = e.localizedMessage ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    // 同上：语言要可观察地读，否则切换语言后时间格式不刷新（Compose 判定为错误写法）
    val uiLocale = LocalConfiguration.current.locales[0]
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", uiLocale)
    fun ts(t: Long): String = if (t <= 0) "-" else fmt.format(java.util.Date(t * 1000))


    // 正常居中弹出：淡入 + 从中心轻微放大，避免默认对话框的异常位移动画
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(160)) +
                scaleIn(tween(200), initialScale = 0.95f, transformOrigin = TransformOrigin(0.5f, 0.5f)),
            exit = fadeOut(tween(120)) +
                scaleOut(tween(140), targetScale = 0.95f, transformOrigin = TransformOrigin(0.5f, 0.5f))
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                tonalElevation = 0.dp
            ) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .widthIn(min = 240.dp, max = 300.dp)
                ) {
                    Text("订单详情", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.height(14.dp))
                    when {
                        loading -> Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        errMsg != null -> Text(errMsg ?: "加载失败", fontSize = 14.sp, color = Color(0xFFDC2626), lineHeight = 22.sp)
                        order != null -> {
                            val o = order!!
                            val statusText = when (o.status) {
                                "approved" -> "已通过"
                                "rejected" -> "已拒绝"
                                else -> "待审核"
                            }
                            // 会员体系已移除，订单均为 Token 充值订单
                            val typeText = "Token 充值 · ${o.tokens} Token"
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                OrderInfoRow("订单号", o.orderNo)
                                OrderInfoRow("类型", typeText)
                                OrderInfoRow("金额", "¥${o.amount}")
                                OrderInfoRow("提交时间", ts(o.createdAt))
                                OrderInfoRow("状态", statusText)
                                if (o.status == "rejected" && o.rejectReason.isNotBlank()) {
                                    OrderInfoRow("拒绝原因", o.rejectReason)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("关闭", color = Color(0xFF1E40AF)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.width(76.dp))
        Text(value, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
    }
}

// 多选模式的底部操作栏：显示已选数量 + 保存 / 分享 / 收藏 / 复制 / 删除 / 取消
@Composable
private fun MultiSelectActionBar(
    count: Int,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        color = Color.White,
        shadowElevation = 8.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("已选", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Text("$count", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF), maxLines = 1)
            }
            // 6 个按钮紧凑排布：统一用小内边距的紧凑按钮，避免把「已选 N」挤到裁切/换行
            CompactBarButton("保存", Color(0xFF1E40AF), onSave)
            CompactBarButton("分享", Color(0xFF1E40AF), onShare)
            CompactBarButton("收藏", Color(0xFF1E40AF), onFavorite)
            CompactBarButton("复制", com.aurora.chat.ui.theme.appTextPrimary(), onCopy)
            CompactBarButton("删除", Color(0xFFDC2626), onDelete)
            CompactBarButton("取消", com.aurora.chat.ui.theme.appTextPrimary(), onCancel)
        }
    }
}

/** 操作栏紧凑按钮：固定小内边距，保证 5 个按钮 + 已选数量在窄屏也能一行排下。 */
@Composable
private fun CompactBarButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, color = color, maxLines = 1)
    }
}

/** 判断扩展名是否按文本预览（TXT/MD/LOG/代码/配置等）。 */
private fun isTextViewableExt(ext: String): Boolean = ext in setOf(
    "txt", "text", "log", "md", "markdown",
    "json", "xml", "ini", "conf", "cfg", "properties", "env", "toml", "yml", "yaml",
    "csv", "sql", "sh", "bat", "cmd", "ps1",
    "java", "kt", "kts", "c", "h", "cpp", "hpp", "cc", "py", "js", "mjs", "cjs",
    "ts", "tsx", "jsx", "html", "htm", "css", "scss", "less", "php", "rb", "go", "rs",
    "swift", "gradle", "gitignore", "dockerfile", "vue", "svelte", "ini"
)



/*
 * 【已移除】Modifier.overscrollViewport
 *
 * 曾用「列表多测量 2*extra、整体上移 extra、contentPadding 等量抵消」实现边界回弹。
 * 在 reverseLayout = true 的列表里这从机制上就是错的：
 *   - reverseLayout 的内容从底部开始堆，index 0（最新消息）贴视口底边；
 *   - 视口加高时多出来的空间全部被分到顶部（组合更早的消息），
 *     底部方向不可能多出任何 item —— 最新消息下面本来就没有内容；
 *   - 结果：位移一发生，底边就是一条没有内容的背景带，
 *     且裁剪边界固定、内容在动，气泡滑到那条线上会被切掉 —— 即用户看到的
 *     「下面是一块背景色，东西到那里不显示、被它遮住」。
 *
 * 现在改用 graphicsLayer 整体平移：内容与裁剪边界一起移动，边缘露出聊天背景本身。
 */

