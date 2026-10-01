package com.aurora.chat.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.viewmodel.ChatViewModel
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.chat.MessageBubble
import com.aurora.chat.ui.components.AnimationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

// 缓存时间格式化器，避免每次调用创建新对象
private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
private val DATE_FMT = SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.CHINA)
private val YEAR_FMT = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.CHINA)
private val DAY_NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

/**
 * 独立的「AI 工具调用状态」行（无头像、无气泡）：居中显示一行纯文本。
 * state=0「正在调用…」(蓝) / 1「调用成功」(绿) / 2「调用失败」(红)。文字颜色按状态区分，不显示彩色圆点或对错符号。
 * 作为独立消息随消息列表渲染与持久化，绝不消失。
 */
@Composable
private fun ToolStatusRow(state: Int, tool: String) {
    // 多 Agent 元工具:专属文案 + 实时状态(验收轮数 / 并行完成进度)
    val isReview = tool == "agent_review"
    val isFork = tool == "agent_fork"
    val isAsk = tool == "ask_user"
    val isAgent = isReview || isFork
    val liveStatus by if (isAgent && state == 0) {
        (if (isFork) com.aurora.chat.ui.chat.AiChatManager.AgentForkBus.live
        else com.aurora.chat.ui.chat.AiChatManager.AgentReviewBus.live).collectAsState()
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
        isFork && state == 0 -> "并行派发中,多个子执行 Agent 同时干活…"
        isFork && state == 1 -> "并行子任务全部完成"
        isFork -> "并行派发失败"
        isReview && state == 0 -> "验收 Agent 接手中…"
        isReview && state == 1 -> "验收 Agent 核验完成"
        isReview -> "验收 Agent 核验失败"
        isAsk && state == 0 -> "正在向用户提问"
        isAsk && state == 1 -> "已向用户提问"
        isAsk -> "提问未完成"
        state == 0 -> "正在调用 $tool…"
        state == 1 -> "调用成功 $tool"
        else -> "调用失败 $tool"
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
            fontWeight = FontWeight.Medium
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
            // 跳过审查:点击后验收 Agent 在当前请求结束后立即中止,执行者会收到"用户跳过了验收"并如实告知
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
    if (isAgent && state == 0 && !liveStatus.isNullOrBlank()) {
        Text(
            text = liveStatus!!,
            color = Color(0xFF9CA3AF),
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
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
    onCopyMessage: (String) -> Unit = {},
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
    onFlashDestroy: (Long) -> Unit = {},
    isGroup: Boolean = false,
    groupMemberNames: Set<String> = emptySet(),
    onImageClick: (String) -> Unit = {},
    onFileClick: (String, String) -> Unit = { _, _ -> },
    onFileDownload: (String, String) -> Unit = { _, _ -> },
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
    expandedReasoningMsgId: Long = 0,
    onRespondGroupInvite: ((InviteGroupCardData) -> Unit)? = null,
    replyIsRevoked: Boolean = false
) {

    val displayName = when {
        msg.isMine && isGroup -> currentUserName.ifEmpty { friendName }
        msg.isMine -> friendName
        isGroup && msg.fromUserId == friendId -> friendName
        isGroup -> msg.senderName.ifEmpty { friendName }
        else -> friendName
    }

    // 独立「工具调用状态」消息：像普通消息一样独立站位渲染，绝不与其他气泡合并、绝不消失。
    if (msg is ChatMsg && msg.isToolStatus) {
        ToolStatusRow(state = msg.aiToolStageState, tool = msg.text)
        return
    }

    @Composable
    fun BubbleWithMenu() {
        var isSelecting by remember { mutableStateOf(false) }
        val ctx = LocalContext.current
        val prefs = remember { ctx.getSharedPreferences("aurora_select_info", Context.MODE_PRIVATE) }
        var showInfoDialog by remember { mutableStateOf(false) }
        var dontShowAgain by remember { mutableStateOf(false) }
        // 记录气泡在窗口中的位置，供长按菜单（微信/QQ 风格浮出小卡片）贴近气泡定位
        var bubbleBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

        // 点击空白区域退出选择模式
        LaunchedEffect(clearSelectionKey) {
            if (clearSelectionKey > 0 && isSelecting) {
                isSelecting = false
            }
        }

        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false; if (dontShowAgain) prefs.edit().putBoolean("select_info_dismissed", true).apply() },
                containerColor = Color.White,
                title = { Text("关于选择功能", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                text = {
                    Column {
                        Text("由于 Android Compose 框架的限制，SelectionContainer 不支持代码触发的「全选」操作。\n\n您可以通过系统提供的手柄拖拽选择文字范围，选择后使用菜单中的「复制」按钮复制文字。如需全文复制，可在主菜单中直接点击「复制」按钮。", fontSize = 14.sp, color = Color(0xFF374151), lineHeight = 22.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                            Spacer(Modifier.width(6.dp))
                            Text("不再显示", fontSize = 14.sp, color = Color(0xFF6B7280))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showInfoDialog = false
                        if (dontShowAgain) prefs.edit().putBoolean("select_info_dismissed", true).apply()
                    }) { Text("知道了", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF)) }
                }
            )
        }
        // 用透明盒子包裹气泡，捕获其在窗口中的坐标，供长按菜单定位
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { bubbleBounds = it.boundsInWindow() }
        ) {        // 垂直堆叠：气泡占用一行，工具状态行独占下方一行，避免相互覆盖重叠
        Column {
        MessageBubble(text = msg.text, isMine = msg.isMine, fromUserId = msg.fromUserId, myAvatar = myAvatar, displayName = displayName, showSenderName = isGroup && displayName.isNotEmpty(), onAvatarClick = onAvatarClick,
        groupConvId = if (isGroup) friendId else 0, currentUserId = currentUserId,
            onBubbleTap = { if (!multiSelectActive) { onMenuClose(); isSelecting = false } },
            onBubbleLongPress = { isSelecting = false; if (!multiSelectActive) onMenuOpen() },
            selectable = isSelecting, replyToText = msg.replyToText, replyToSender = msg.replyToSender,
            replyToId = msg.replyToId, replyIsRevoked = replyIsRevoked, isHighlighted = isHighlighted, onCapsuleClick = onCapsuleClick,
            onAvatarLongPress = onAvatarLongPress,
            onAvatarDoubleTap = onAvatarDoubleTap,
            onAtMentionClick = onAtMentionClick,
            isGroup = isGroup,
            groupMemberNames = groupMemberNames,
            loadMedia = loadMedia,
            mediaType = msg.mediaType,
            mediaUrl = msg.mediaUrl,
            isUploading = msg.isUploading,
            uploadProgress = uploadProgressMap[msg.id] ?: 0f,
            uploadStage = uploadStageMap[msg.id] ?: "",
            uploadRatio = uploadRatioMap[msg.id] ?: 0f,
            uploadFileName = uploadFileNameMap[msg.id] ?: "",
            onImageClick = onImageClick,
            onFileClick = onFileClick,
            onFileDownload = onFileDownload,
            flashDuration = msg.flashDuration,
            reasoningText = msg.reasoningText,
            msgId = msg.id,
            onOpenPostDetail = onOpenPostDetail,
            onOpenGroupDetail = onOpenGroupDetail,
            destroyedFlashIds = destroyedFlashIds,
            onFlashDestroy = onFlashDestroy,
            onReasoningToggled = onReasoningToggled,
            expandedReasoningMsgId = expandedReasoningMsgId,
            thinkingSeconds = msg.thinkingSeconds,
            totalSeconds = msg.totalSeconds,
            aiFlatBubble = friendId == AI_CHAT_ID,
            manualStopped = msg.manualStopped,
            aiWaiting = (msg as? ChatMsg)?.aiWaiting ?: false,
            aiProgressHint = (msg as? ChatMsg)?.aiProgressHint ?: "",
            onContinueClick = onContinueAi,
            onCodeBackToTop = onCodeBackToTop,
            onRespondGroupInvite = onRespondGroupInvite,
            aiFileChanges = (msg as? ChatMsg)?.aiFileChanges ?: "",
            // 优先用采集层真实计数的 aiToolCallCount;旧消息无该字段时回退 aiToolStages 条数
            toolCallCount = maxOf((msg as? ChatMsg)?.aiToolCallCount ?: 0, (msg as? ChatMsg)?.aiToolStages?.size ?: 0),
            onViewChanges = onViewChanges,
            onToolCalls = onToolCalls
        )
        // 工具调用动态状态行（独占一行，位于气泡下方）：蓝=正在调用（三点轮换）/ 绿=调用成功 / 红=调用失败
        // 历史记录逐条渲染，每次调用的完成态都留存展示，不被下一次调用覆盖
        aiToolStatus?.forEach { (tool, state) ->
            AiToolStatusRow(tool = tool, state = state)
        }
        }
        // 多选模式：整行点击切换选中，并在对侧显示勾选标识
        if (multiSelectActive) {
            // 选中底色（未选中=透明，仅拦截点击）
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Color(0x142F6FED) else Color.Transparent)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onToggleSelected() }
            )
            Box(
                Modifier
                    .align(if (msg.isMine) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 3.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF2F6FED) else Color(0x40000000), CircleShape)
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

        // 消息信息弹窗（全界面样式）
        var showMsgInfoDialog by remember { mutableStateOf(false) }
        var msgInfoData by remember { mutableStateOf<JSONObject?>(null) }
        if (showMsgInfoDialog) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showMsgInfoDialog = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .background(Color.White, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val info = msgInfoData
                    if (info != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 标题
                            Text("消息信息", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF111827))
                            Spacer(Modifier.height(20.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)))
                            Spacer(Modifier.height(16.dp))

                            fun formatTime(ts: Long): String {
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
                                    sameDay -> TIME_FMT.format(d)
                                    isYesterday -> "昨天 ${TIME_FMT.format(d)}"
                                    isDayBefore -> "前天 ${TIME_FMT.format(d)}"
                                    calNow.get(Calendar.WEEK_OF_YEAR) == calMsg.get(Calendar.WEEK_OF_YEAR) &&
                                        calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) ->
                                        "${DAY_NAMES[weekDay]} ${TIME_FMT.format(d)}"
                                    calNow.get(Calendar.YEAR) == calMsg.get(Calendar.YEAR) ->
                                        DATE_FMT.format(d)
                                    else -> YEAR_FMT.format(d)
                                }
                            }
                            val sentTime = info.optLong("created_at", 0) * 1000L

                            // 发送时间
                            InfoRow(label = "消息发送时间", value = formatTime(sentTime))
                            Spacer(Modifier.height(14.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF3F4F6)))
                            Spacer(Modifier.height(14.dp))

                            // 到达时间
                            InfoRow(label = "消息到达时间", value = formatTime(sentTime))
                            Spacer(Modifier.height(14.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF3F4F6)))
                            Spacer(Modifier.height(14.dp))

                            // 已读信息
                            if (info.optInt("read_count", -1) >= 0) {
                                InfoRow(label = "消息已读人数", value = "${info.optInt("read_count", 0)} 人")
                            } else {
                                val readAt = info.optLong("other_read_at", 0) * 1000L
                                val readStatus = if (info.optString("other_read_status", "unread") == "read" && readAt > 0) {
                                    formatTime(readAt)
                                } else {
                                    "对方未读"
                                }
                                InfoRow(label = "对方已读时间", value = readStatus)
                            }

                            Spacer(Modifier.height(20.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)))
                            Spacer(Modifier.height(16.dp))

                            // 关闭按钮
                            TextButton(
                                onClick = { showMsgInfoDialog = false },
                                modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6))
                            ) {
                                Text("关闭", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
                            }
                        }
                    } else {
                        Box(Modifier.padding(32.dp)) {
                            Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                        }
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
                properties = PopupProperties(focusable = true),
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
                    add(MenuEntry("复制", Icons.Filled.ContentCopy) { onCopyMessage(msg.text); onMenuClose() })
                    add(MenuEntry("选择", Icons.Filled.CheckCircle) {
                        onMenuClose()
                        if (prefs.getBoolean("select_info_dismissed", false)) { isSelecting = true } else { showInfoDialog = true }
                    })
                    add(MenuEntry("引用", Icons.AutoMirrored.Filled.Reply) { onReplyTo(msg); onMenuClose() })
                    // AI / 本地对话不展示「信息」（本地消息没有服务端信息可查）
                    if (!isLocalChat(friendId)) {
                        add(MenuEntry("信息", Icons.Filled.Info) {
                        onMenuClose()
                        menuScope.launch {
                            val result = com.aurora.chat.data.api.AuroraApi.getMessageInfo(msg.id)
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
                    // 撤回：本人消息，或开发者(ID==1)可撤回任何人发的消息
                    if (msg.isMine || currentUserId == 1L) {
                        add(MenuEntry("撤回", Icons.Filled.Undo) {
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
                                    ChatViewModel.updateConversationPreview(friendId, "$currentUserName 撤回了一条消息", System.currentTimeMillis() / 1000)
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
                                        ChatViewModel.updateConversationPreview(friendId, "${if (msg.isMine) currentUserName else msg.senderName} 撤回了一条消息", System.currentTimeMillis() / 1000)
                                        // 落盘标记撤回 + 清理被撤回媒体（saveMessages 不更新已存在条目，需专用原子写）
                                        withContext(Dispatchers.IO) {
                                            com.aurora.chat.data.repository.LocalMessageStore.markRevoked(ctx, friendId, msg.id)
                                            if (msg.mediaUrl.isNotEmpty()) deleteMediaFile(ctx, msg.mediaUrl)
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
                    add(MenuEntry(if (speaking) "停止" else "朗读", if (speaking) Icons.Filled.Stop else Icons.Filled.VolumeUp, danger = speaking) {
                        if (speaking) {
                            TtsHelper.stop()
                        } else if (msg.text.isBlank()) {
                            Toast.makeText(ctx, "没有可朗读的内容", Toast.LENGTH_SHORT).show()
                        } else {
                            TtsHelper.speak(ctx, msg.text)
                        }
                        onMenuClose()
                    })
                    // 开发者自己的广播：在普通气泡菜单里也提供「广播管理」入口，确保长按一定能呼出
                    if (msg.broadcastTaskId != 0L && msg.isMine) {
                        add(MenuEntry("广播管理", Icons.Filled.Info) {
                            onBroadcastManage(msg.id, msg.broadcastTaskId); onMenuClose()
                        })
                    }
                    add(MenuEntry("删除", Icons.Filled.Delete, danger = true) { onDeleteMessage(msg.id); onMenuClose() })
                }

                // 网格布局：每排 4 个，单元格紧凑，避免大块浪费空间
                Column(
                    modifier = Modifier
                        .graphicsLayer {
                            this.alpha = alpha.value
                            scaleX = scale.value
                            scaleY = scale.value
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .border(0.5.dp, Color(0xFFE5E7EB), RoundedCornerShape(10.dp))
                        .padding(3.dp)
                ) {
                    entries.chunked(4).forEach { rowEntries ->
                        Row(verticalAlignment = Alignment.Top) {
                            rowEntries.forEach { MenuCell(it, Modifier.width(56.dp).height(48.dp)) }
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
        Text(label, fontSize = 13.sp, color = Color(0xFF6B7280))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF111827))
    }
}

/** 微信/QQ 风格长按菜单条目（图标 + 文字） */
private data class MenuEntry(
    val label: String,
    val icon: ImageVector,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/** 网格菜单单元格：上方图标，下方文字，紧凑布局 */
@Composable
private fun MenuCell(entry: MenuEntry, modifier: Modifier = Modifier) {
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
private fun AiToolStatusRow(tool: String, state: Int) {
    // 多 Agent 元工具:专属文案 + 实时状态(验收轮数 / 并行完成进度)
    val isReview = tool == "agent_review"
    val isFork = tool == "agent_fork"
    val isAsk = tool == "ask_user"
    val isAgent = isReview || isFork
    val liveStatus by if (isAgent && state == 0) {
        (if (isFork) com.aurora.chat.ui.chat.AiChatManager.AgentForkBus.live
        else com.aurora.chat.ui.chat.AiChatManager.AgentReviewBus.live).collectAsState()
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
        isFork && state == 0 -> "并行派发中,多个子执行 Agent 同时干活…"
        isFork && state == 1 -> "并行子任务全部完成"
        isFork -> "并行派发失败"
        isReview && state == 0 -> "验收 Agent 接手中…"
        isReview && state == 1 -> "验收 Agent 核验完成"
        isReview -> "验收 Agent 核验失败"
        isAsk && state == 0 -> "正在向用户提问"
        isAsk && state == 1 -> "已向用户提问"
        isAsk -> "提问未完成"
        state == 1 -> "调用成功 $tool"
        state == 2 -> "调用失败 $tool"
        else -> "正在调用 $tool…"
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
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
                // 跳过审查:点击后验收 Agent 在当前请求结束后立即中止,执行者会收到"用户跳过了验收"并如实告知
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
        if (isAgent && state == 0 && !liveStatus.isNullOrBlank()) {
            Text(
                text = liveStatus!!,
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
            )
        }
    }
}
