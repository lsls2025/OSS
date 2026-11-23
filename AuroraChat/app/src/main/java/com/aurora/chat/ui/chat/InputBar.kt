package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.chat.Tuple4
import com.aurora.chat.ui.chat.isImageFile
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 键盘图标（填充式）：黑色圆角面板铺满 Canvas + 两排白色键位 + 底部白色空格条。
 *
 * 语音钮 / 表情钮切到「键盘」态共用这一个画法。注意它**不带外圈**——
 * 之前是「白圆底 + 黑描边圈 + 里面一把小小的线稿键盘」，用户反馈圈难看、
 * 而且线稿键盘（小框 + 几个点）根本不像键盘。现在去掉圈，
 * 键盘本体直接放大铺满图标位，用填充画法一眼可辨。
 */
private fun DrawScope.drawKeyboardGlyph() {
    val w = size.width
    val h = size.height
    val bh = h * 0.64f
    val top = (h - bh) / 2f
    // 面板：占满宽度
    drawRoundRect(
        color = com.aurora.chat.ui.theme.appInk(),
        topLeft = Offset(0f, top),
        size = androidx.compose.ui.geometry.Size(w, bh),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.6.dp.toPx())
    )
    // 键位：两排白点，5 列
    val dotR = 1.05.dp.toPx()
    for (row in 0..1) {
        val y = top + bh * (0.28f + row * 0.26f)
        for (i in 0..4) {
            drawCircle(com.aurora.chat.ui.theme.appInkContrast(), dotR, Offset(w * (0.14f + i * 0.18f), y))
        }
    }
    // 空格条：底部白色圆角横条
    drawRoundRect(
        color = com.aurora.chat.ui.theme.appInkContrast(),
        topLeft = Offset(w * 0.28f, top + bh * 0.68f),
        size = androidx.compose.ui.geometry.Size(w * 0.44f, bh * 0.18f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bh * 0.09f)
    )
}

@Composable
fun InputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    friendId: Long = 0,
    modifier: Modifier = Modifier,
    onSendMedia: (Uri, Int, String, Boolean) -> Unit = { _, _, _, _ -> },
    showPlusDialog: Boolean = false,
    onTogglePlus: () -> Unit = {},
    onClosePlus: () -> Unit = {},
    // 表情面板状态由屏幕层持有：点消息区任意位置可收起（只收起，不弹键盘）
    showStickerPanel: Boolean = false,
    onShowStickerPanelChange: (Boolean) -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onRequestLocation: () -> Unit = {},
    onSecretClick: () -> Unit = {},
    onTransfer: () -> Unit = {},
    onRedPacket: () -> Unit = {},
    // 转账 / 发红包门控：非好友 / 拉黑 / 对方开启验证 等场景禁用（由屏幕层按关系状态计算）
    payDisabled: Boolean = false,
    payBlockReason: String = "",
    // 开发者专属：广播（仅 ID=1 可见；实际接口须由后端校验 userId==1 防越权）
    onBroadcast: () -> Unit = {},
    mutedUntil: Long = 0L,  // 禁言截止时间戳（0=未禁言）
    // AI 对话专属
    isAiChat: Boolean = false,
    onTimePoint: () -> Unit = {},
    onConfirmSpeech: () -> Unit = {},
    confirmSpeechEnabled: Boolean = false,
    onConfirmReply: () -> Unit = {},
    // AI 对话「图片」：选择后不立即发送，先附着在输入框上方，随发送一起发出去
    onPickAiImage: () -> Unit = {},
    // AI 对话「文件」：选择文件后附着在输入框上方，随发送一起发出去
    onPickAiFile: () -> Unit = {},
    // 「+ → 文件 → 工作区」：由屏幕层打开工作区选择面板（所有会话类型共用，与是否 AI 对话无关）
    onPickWorkspaceFile: () -> Unit = {},
    // 外部注入的待发送文件（例如「从工作区选择」）：复用普通聊天的选中队列，
    // 注入后弹出「已选择 N 个文件」确认弹窗，由用户点发送才真正发出（不直接发送）
    injectedSelectedFiles: List<Triple<Uri, String, Long>> = emptyList(),
    onInjectedSelectedFilesConsumed: () -> Unit = {},
    // AI 对话：是否正在生成回复（真则发送按钮切换为「停止」）
    aiInFlight: Boolean = false,
    // 是否有待发附件（AI 图片/文件挂载区）。这类内容不在输入框里，
    // 但「有东西可发」时发送按钮必须已经就位，所以由屏幕层告知。
    hasPendingAttachment: Boolean = false,
    onStopSend: (() -> Unit)? = null,
    // 引用条槽位：由屏幕层注入，渲染在本栏内容的最下方（三组件 → 引用条 → 键盘/导航栏）。
    // 放在 InputBar 内部而不是屏幕层的 Column 里，是为了让它位于 navigationBarsPadding 之上，
    // 键盘弹出时始终贴着键盘、不会被导航栏挤出屏幕。
    quoteBanner: @Composable () -> Unit = {},
    // 引用聚焦信号：每次「引用某条消息」自增一次，InputBar 收到后自动聚焦输入框并弹起键盘。
    // 用自增计数而非布尔值：连续引用不同消息时每一次都要重新拉起键盘，
    // 布尔值在第二次引用（已经是 true）时不会触发 LaunchedEffect。
    quoteFocusTick: Int = 0,
    // 语音消息：录完一段后回调（本地 m4a 绝对路径 + 秒数），由屏幕层负责上传与发送
    onSendVoice: (String, Int) -> Unit = { _, _ -> },
    // 表情面板：点击某个表情后回调（id = "builtin/001" 这样的资源 id），
    // 发送链路由屏幕层接入；当前图片资源未到位前屏幕层暂不处理
    onSendSticker: (String) -> Unit = {},
    // emoji tab：点击内置 emoji 字符后回调（直接发送，不插入输入框）
    onSendEmoji: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // 自定义表情收藏：本地文件列表 + 相册选择（选完立即刷新面板）
    var customStickers by remember { mutableStateOf(com.aurora.chat.data.local.CustomStickerStore.list(context)) }
    val stickerScope = rememberCoroutineScope()
    val customStickerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            stickerScope.launch {
                com.aurora.chat.data.local.CustomStickerStore.add(context, uri)
                customStickers = com.aurora.chat.data.local.CustomStickerStore.list(context)
            }
        }
    }

    // 官方群媒体限制：仅开发者（ID=1，或开发者 QQ/邮箱）可发图片/文件，普通成员禁止
    // （与后端 friend_handlers.go handleSendMessage 的官方群媒体限制保持一致）
    val isDeveloperUser = AuroraApi.currentUserId == 1L || run {
        val lp = context.getSharedPreferences("aurora_login", android.content.Context.MODE_PRIVATE)
        com.aurora.chat.data.local.LocalStorage.isDeveloper(
            lp.getString("user_qq", "") ?: "",
            lp.getString("user_email", "") ?: ""
        )
    }

    // 已选择的文件列表
    var selectedFiles by remember { mutableStateOf<List<Triple<Uri, String, Long>>>(emptyList()) } // (uri, name, size)

    // 是否显示媒体选择完成弹窗（带发送按钮）
    var showMediaConfirmDialog by remember { mutableStateOf(false) }

    // 「文件」来源选择弹窗（本地文件 / 工作区）——AI 对话与好友/群聊共用同一个入口
    var showFileSourceDialog by remember { mutableStateOf(false) }

    // 外部注入（例如从工作区选择文件）：追加到待发送队列并弹出确认弹窗，随后通知父层清空注入值
    LaunchedEffect(injectedSelectedFiles) {
        if (injectedSelectedFiles.isNotEmpty()) {
            val room = 10 - selectedFiles.size
            if (room <= 0) {
                android.widget.Toast.makeText(context, "最多同时发送 10 个文件", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                selectedFiles = selectedFiles + injectedSelectedFiles.take(room)
                showMediaConfirmDialog = true
            }
            onInjectedSelectedFilesConsumed()
        }
    }
    // 闪照配置：index -> 秒数（0/不存在 = 普通照片）
    var flashSettings by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    // 应用内相册选择器是否展开（替代系统 PickMultipleVisualMedia）
    var showMediaPicker by remember { mutableStateOf(false) }

    // 图片/视频选择器：已改为应用内自绘相册（见 MediaPickerScreen），
    // 系统选择器无法做相册/类型筛选和原图开关，样式也和 App 不搭。

    // 文件选择器（多选，系统原生界面，支持 APK 等任意文件，最多 10 个；文件不压缩）
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = 10 - selectedFiles.size
        if (room <= 0) {
            android.widget.Toast.makeText(context, "最多同时发送 10 个文件", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val limited = uris.take(room)
        if (uris.size > room) android.widget.Toast.makeText(context, "最多选择 10 个", android.widget.Toast.LENGTH_SHORT).show()
        val selectedList = mutableListOf<Triple<Uri, String, Long>>()
        for (uri in limited) {
            val mime = context.contentResolver.getType(uri) ?: ""
            var added = false
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val rawName = if (nameIndex >= 0) cursor.getString(nameIndex) else "Unknown"
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    selectedList.add(Triple(uri, ensureMediaNameWithExt(rawName, mime), size))
                    added = true
                }
            }
            if (!added) {
                val rawName = uri.lastPathSegment ?: "Unknown"
                selectedList.add(Triple(uri, ensureMediaNameWithExt(rawName, mime), 0L))
            }
        }
        selectedFiles = selectedFiles + selectedList
        showMediaConfirmDialog = true
    }

    // ── 「+」面板展开时的新照片推荐（微信 / QQ 同款）──
    // 只推荐「基线之后 24 小时内」新增的照片；用户发送/关闭/自己在相册里发掉后推进基线，不会反复打扰。
    // AI 对话走「先挂附件再发送」的另一条链路，不在这里推荐；官方群仅开发者能发图，同样跳过。
    var recentPhoto by remember { mutableStateOf<RecentPhoto?>(null) }
    // 推荐缩略图点开后的全屏预览态（null = 未打开）
    var previewPhoto by remember { mutableStateOf<RecentPhoto?>(null) }
    LaunchedEffect(showPlusDialog, isAiChat, mutedUntil) {
        val mutedNow = mutedUntil > System.currentTimeMillis() / 1000L
        val allowed = showPlusDialog && !isAiChat && !mutedNow && (friendId != -1001L || isDeveloperUser)
        recentPhoto = if (allowed) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                RecentPhotoSuggestion.detect(context)
            }
        } else {
            // 面板收起（用户关掉 / 切走 / 不满足展示条件）即视为「已看过这张推荐」，
            // 标记处理过并推进基线，下次打开不再重复提示同一张（与微信一致）。
            // 仅当当前确实在展示某张推荐时才记录，避免对 null 误写。
            recentPhoto?.let { RecentPhotoSuggestion.markHandled(context, it) }
            null
        }
    }

    // 处理弹窗按钮点击
    val handleDialogAction: (String) -> Unit = { action ->
        onClosePlus()
        when (action) {
            "time_point" -> { onTimePoint() }
            "confirm_speech" -> { onConfirmSpeech() }
            "image" -> {
                // AI 对话：不走普通聊天的「选完即发」，改由屏幕层暂存到输入框上方，随发送按钮一起发
                if (isAiChat) {
                    onPickAiImage()
                } else if (friendId == -1001L && !isDeveloperUser) {
                    android.widget.Toast.makeText(context, "官方群仅开发者可发送图片、视频、文件", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 相册：应用内自绘选择器，图片/视频混选 + 相册筛选 + 原图开关
                    // 用户已经自己去相册挑了，推荐卡不再重复打扰
                    recentPhoto?.let { RecentPhotoSuggestion.markHandled(context, it) }
                    recentPhoto = null
                    selectedFiles = emptyList()
                    showMediaPicker = true
                }
            }
            "file" -> {
                if (friendId == -1001L && !isDeveloperUser) {
                    android.widget.Toast.makeText(context, "官方群仅开发者可发送图片、视频、文件", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 不再直接进文件管理：先弹来源选择（本地文件 / 工作区）。
                    // AI 对话与好友/群聊统一走这里，只是选中后的落地方式不同。
                    showFileSourceDialog = true
                }
            }
            "voice" -> { /* TODO: 语音消息 */ }
            "red_packet" -> {
                if (payDisabled) {
                    android.widget.Toast.makeText(context, payBlockReason.ifBlank { "当前无法发红包" }, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onRedPacket()
                }
            }
            "transfer" -> {
                if (payDisabled) {
                    android.widget.Toast.makeText(context, payBlockReason.ifBlank { "当前无法转账" }, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onTransfer()
                }
            }
            "broadcast" -> { onBroadcast() }
            "camera" -> {
                if (friendId == -1001L && !isDeveloperUser) {
                    android.widget.Toast.makeText(context, "官方群仅开发者可发送图片、视频、文件", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 用户改走拍摄：旧推荐不再重复提示
                    recentPhoto?.let { RecentPhotoSuggestion.markHandled(context, it) }
                    recentPhoto = null
                    onOpenCamera()
                }
            }
            "secret" -> {
                if (friendId > 0) {
                    onSecretClick()
                } else {
                    android.widget.Toast.makeText(context,
                        if (isLocalChat(friendId)) "本地对话暂不支持" else "群聊暂不支持",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            "location" -> { onRequestLocation() }
        }
    }

    // 计算剩余禁言时间文本
    val muteRemainingText = remember(mutedUntil) {
        if (mutedUntil <= 0L) ""
        else {
            val remainSec = (mutedUntil - System.currentTimeMillis() / 1000).coerceAtLeast(0)
            if (remainSec <= 0) ""
            else {
                val days = remainSec / 86400
                val hours = (remainSec % 86400) / 3600
                val mins = (remainSec % 3600) / 60
                when {
                    days > 0 -> "还剩 ${days}天${hours}小时${mins}分钟"
                    hours > 0 -> "还剩 ${hours}小时${mins}分钟"
                    mins > 0 -> "还剩 ${mins}分钟"
                    else -> "还剩 ${remainSec}秒"
                }
            }
        }
    }
    val isMuted = mutedUntil > 0L && muteRemainingText.isNotEmpty()
    // 录音中：整条输入栏换底色（不只是输入框那一小块），所以状态提到根 Box 之前
    var isRecording by remember { mutableStateOf(false) }
    // ── 全屏输入编辑 ──
    // 草稿独立于 inputText：点「完成」才回填，点「取消」原内容不动。
    // 状态放在 InputBar 顶层，因为全屏 Dialog 渲染在根 Box 之外。
    var showFullscreenInput by remember { mutableStateOf(false) }
    var fullscreenDraft by remember { mutableStateOf("") }
    val fullscreenFocusRequester = remember { FocusRequester() }

    // 输入栏外观（设置 → 消息设置 → 输入框设置）：每次重组读一次偏好，
    // 与气泡配色同样的做法 —— 改完设置返回聊天页即刻生效，不需要重启会话。
    // 样式与预览共用 InputBarLook，保证「设置里看到的」就是「聊天里生效的」。
    val barStyle = com.aurora.chat.data.local.InputBarStylePrefs.get(context)
    // 自定义样式的参数一起进来：data class 的 equals 天然做了 remember 的 key，
    // 在设置里拖圆角 / 换颜色，返回聊天页就是新的一套。
    val barCustom = com.aurora.chat.data.local.InputBarStylePrefs.custom(context)
    val barLook = remember(barStyle, isMuted, barCustom) { inputBarLook(barStyle, isMuted, barCustom) }

    // 外层 Column：引用条要整行铺满，所以 12dp 水平内边距只加在「三组件」那一层，
    // 不能加在最外层容器上（否则引用条左右仍各留 12dp，又变成中间一小截）。
    // ⚠️ Compose 的 padding 不接受负值：用「负 padding 抵消外层内边距」会直接抛
    //    IllegalArgumentException: Padding must be non-negative 并崩在主线程。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 输入栏底色由样式决定：默认白底，与上方消息区不再有色差带（那也像一道分割线）。
            .background(barLook.barBg)
            .navigationBarsPadding()
    ) {
        // ── 新照片推荐：真正浮在「+」按钮上方（覆盖在消息区之上，不占输入栏布局）──
        if (showPlusDialog && recentPhoto != null) {
            RecentPhotoSuggestCard(
                photo = recentPhoto!!,
                onClick = { previewPhoto = recentPhoto },
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = 0, minHeight = 0)
                    )
                    // 以 0x0 参与布局：不撑高输入栏，只在「+」上方绘制
                    layout(0, 0) {
                        val parentWidth = if (constraints.maxWidth == Constraints.Infinity) {
                            placeable.width
                        } else constraints.maxWidth
                        placeable.place(
                            x = parentWidth - placeable.width - 8.dp.roundToPx(),
                            y = -placeable.height - 6.dp.roundToPx()
                        )
                    }
                }
            )
        }
        // ── 全屏预览：点缩略图先进来看清楚，右下角悬浮发送按钮才真正发出去 ──
        previewPhoto?.let { photo ->
            Dialog(
                onDismissRequest = { previewPhoto = null },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = { previewPhoto = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(20.dp)
                            .height(40.dp)
                            .widthIn(min = 84.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E40AF))
                            .clickable {
                                previewPhoto = null
                                RecentPhotoSuggestion.markHandled(context, photo)
                                recentPhoto = null
                                onClosePlus()
                                onSendMedia(photo.uri, 0, photo.displayName.ifBlank { "photo.jpg" }, false)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("发送", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }
        }
        // ── 引用条：消息区与输入栏之间，整行铺满到屏幕左右边缘 ──
        Box(Modifier.fillMaxWidth()) { quoteBanner() }
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
        // 键盘控制器
        val keyboardController = LocalSoftwareKeyboardController.current
        val localView = LocalView.current
        /**
         * API 29(含)以下没有 WindowInsetsController(API30+)：
         * LocalSoftwareKeyboardController.show() 会退回 InputMethodManager.showSoftInput 并传入未持焦的根视图，
         * 部分输入法(搜狗)会忽略该请求导致键盘不弹。这里对真正获焦的视图显式弹起。
         */
        fun showImeCompat() {
            if (Build.VERSION.SDK_INT >= 30) {
                keyboardController?.show()
            } else {
                val imm = context.getSystemService(InputMethodManager::class.java) ?: return
                // 焦点可能还没落到原生 EditText：post 到下一帧再弹，确保作用在持焦视图上
                localView.post {
                    val focusView = localView.findFocus() ?: localView
                    imm.showSoftInput(focusView, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        // 引用后自动聚焦：不聚焦的话键盘不会弹起，用户还得自己点一下输入框
        val inputFocusRequester = remember { FocusRequester() }
        LaunchedEffect(quoteFocusTick) {
            if (quoteFocusTick > 0) {
                runCatching { inputFocusRequester.requestFocus() }
                runCatching { showImeCompat() }
            }
        }
        // ── 输入框容量 + 全屏输入 ──
        // 行高固定 20.sp，最多可视 10.5 行；再多就在框内上下滚动，输入栏高度不再往上累加。
        val density = LocalDensity.current
        val lineHeightDp = with(density) { 20.sp.toDp() }
        val lineHeightPx = with(density) { 20.sp.toPx() }
        val maxInputHeight = lineHeightDp * 10.5f
        // 输入框实际高度（px）：超过 3 行就浮出「全屏输入」按钮
        var inputBoxHeightPx by remember { mutableStateOf(0) }
        val showFullscreenBtn = !isMuted && inputBoxHeightPx > lineHeightPx * 3.5f
        LaunchedEffect(showFullscreenInput) {
            if (showFullscreenInput) {
                runCatching { fullscreenFocusRequester.requestFocus() }
                runCatching { showImeCompat() }
            }
        }
        // ── 语音输入 ──
        // voiceMode：圆钮在「麦克风 ↔ 键盘」之间切换；切换后输入框区域变成「按住说话」。
        var voiceMode by remember { mutableStateOf(false) }
        // ── 表情面板 ──
        // 与键盘互斥：点笑脸开面板、收键盘；点键盘图标关面板、弹键盘。
        // 与「+」附件面板互斥：两个都占 Column 底部同一个槽位，不能同时展开。
        // 状态由屏幕层传入：点消息区任意位置可收起（只收起，不弹键盘）。
        // pointerInput(Unit) 的手势闭包不会随重组更新，直接读 var 会永远拿到首次的 false，
        // 必须经 rememberUpdatedState 中转。
        val showStickerPanelState = rememberUpdatedState(showStickerPanel)
        val focusManager = LocalFocusManager.current
        LaunchedEffect(showPlusDialog) {
            // 「+」面板被打开（无论从哪条路径）→ 表情面板让位，并收起键盘
            if (showPlusDialog) {
                onShowStickerPanelChange(false)
                runCatching { focusManager.clearFocus(force = true) }
                runCatching { keyboardController?.hide() }
            }
        }
        LaunchedEffect(showStickerPanel) {
            if (showStickerPanel) onClosePlus()
        }

        // ══════════ 表情面板 / 键盘：同一块槽位，只有一个高度来源 ══════════
        // MainActivity 是 enableEdgeToEdge：IME 是盖在窗口上的系统窗口，不会把窗口压矮。
        // 所以这块 panelH 高的槽位一直画在屏幕底部就够了：
        //   键盘在 → 被键盘盖住；键盘退 → 槽位里的面板自然露出来。
        // 全程应用布局一动不动：没有「一个收、一个起」两套动画，也没有逐帧改高度的补间。
        // 屏幕层在 pin 期间不再叠 imePadding（否则输入栏会被顶高两次）。
        val stickDensity = LocalDensity.current
        val imeHolder = androidx.compose.foundation.layout.WindowInsets.ime
        val navHolder = androidx.compose.foundation.layout.WindowInsets.navigationBars
        val imeHolderState = rememberUpdatedState(imeHolder)
        val imeDensityState = rememberUpdatedState(stickDensity)
        fun currentImeBottomPx() = imeHolderState.value.getBottom(imeDensityState.value)
        // 键盘净高 = IME inset − 导航栏 inset。InputBar 根容器已经单独加了
        // navigationBarsPadding()，不扣掉就会把导航栏算两次（输入栏被多抬一截）。
        fun currentNetPx() = (currentImeBottomPx() - navHolder.getBottom(imeDensityState.value)).coerceAtLeast(0)
        // 「键盘有没有盖住槽位」只订阅布尔：给按钮图标与点击分支用，不订阅逐帧数值。
        val imeCovering by remember(imeHolderState, imeDensityState) {
            androidx.compose.runtime.derivedStateOf { currentImeBottomPx() > 0 }
        }
        val imeCoveringState = rememberUpdatedState(imeCovering)
        // 从「+」面板切换到输入法：不能在获焦瞬间立刻收起加号 —— 那样槽位先塌、输入栏先落，
        // 键盘才升上来把它顶回去，就是「先下拉再上提」的两段式。这里只登记一个待收起标记，
        // 真正收起推迟到键盘净高达到实测值、槽位高度已完全由键盘接管之后（见下方 LaunchedEffect），
        // 那一刻动画收起不产生任何位移。表情面板本就是"不卸载、键盘直接盖住"，所以没有这个问题。
        var pendingPlusClose by remember { mutableStateOf(false) }

        // 面板是否占住槽位：表情与加号共用同一个槽位、同一份高度，天然等高。
        val slotPinned = showStickerPanel || showPlusDialog
        // 记住系统实测的键盘净高。键盘稳定 160ms 后校准一次；因为槽位取
        // max(insetNet, panelPx)，校准那一刻布局值不变，完全无感。
        // 初值取持久化的实测值：remember 只在单个输入栏实例内有效，每进一次会话都会归零，
        // 之前只能退回硬编码兜底值，导致「首次进对话 + 先开加号再点输入法」时
        // 面板高 ≠ 真实键盘高，输入栏被顶上/落下一小截。持久化后跨会话都是真实键盘高度。
        val measuredPanelPx = remember {
            mutableStateOf(ImePanelHeightStore.loadPx(context, stickDensity.density))
        }
        // 最近一帧的键盘净高（不做延迟校准）。给「键盘在场时打开面板」判断用：
        // 这个值来自上一帧，不受 hide() 调用时序影响。
        val lastNetPx = remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { currentNetPx() }
                .collectLatest { px ->
                    lastNetPx.value = px
                    kotlinx.coroutines.delay(160)
                    if (px > 0) {
                        measuredPanelPx.value = px
                        // 落盘（按 dp 存，换分辨率/字体缩放后仍能换算回正确高度）
                        ImePanelHeightStore.savePx(context, stickDensity.density, px)
                    }
                }
        }
        // 键盘从未弹起时 Android 不提供键盘高度，只用这个初始占位撑住第一次的面板。
        // 一旦有过实测（含上次会话持久化下来的值），就完全以实测为准，不再与兜底常量取 max ——
        // 否则键盘比兜底矮的机型，面板会永远偏高那一截，切到键盘时输入栏就往下掉一下。
        val fallbackPx = (268f * stickDensity.density).toInt()
        val panelBasePx = measuredPanelPx.value.takeIf { it > 0 } ?: fallbackPx
        val targetPanelPx = if (slotPinned) panelBasePx else 0
        // panelPx 用 Animatable 驱动，只在 layout 阶段读 .value：
        // 空闲 → 面板展开时得到 220ms 抬起动画，而整个过程不重组输入栏。
        val panelAnim = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(targetPanelPx) {
            val target = targetPanelPx.toFloat()
            // 「面板从无到有」且键盘正盖着槽位 → 一帧就位，不播抬起动画。
            // 原因：此时键盘正在往退，槽位高度 = max(键盘净高, 面板高)。
            // 若面板从 0 用 220ms 慢慢长起来，两条反向曲线会反复互相反超：
            // 先被退下去的键盘压低、再被长起来的面板顶高，输入栏就是那段「抽搐」。
            // 一帧就位后，面板与键盘等高、藏在键盘后面，键盘一退自然露出，全程零位移。
            // 键盘不在场（空闲态打开）时仍走正常抬起动画。
            //
            // 判断条件里 imeCovering（键盘 inset > 0）比 lastNetPx 更可靠：
            // 点击面板按钮时先 clearFocus、键盘随即开始下降，等到本效果执行已经是下一帧，
            // lastNetPx 往往已比面板高矮一点点（或正处在回收中途），"net >= 面板高" 就会假失败，
            // 退回 220ms 抬起动画 —— 于是同一套操作，键盘收起快的那几次必抽、慢的那一次不抽。
            // 而 imeCovering 在整个键盘收起动画期间都是 true，能稳定表达"此刻键盘正盖着槽位"。
            val openingUnderIme = target > 0f && panelAnim.value < target - 0.5f &&
                (imeCovering || lastNetPx.value >= targetPanelPx)
            if (openingUnderIme) panelAnim.snapTo(target)
            else panelAnim.animateTo(target, tween(220))
        }
        // 「+ → 键盘」的延后收起：等键盘净高升到实测值（键盘基本升到位）再关加号。
        // 此时 slot 高度 = max(imeNet, panelPx) 已由键盘撑住，panelPx 收缩不会再改变布局高度，
        // 因此从「+」切输入法全程输入栏零位移，不再「先落下再被顶上」。
        LaunchedEffect(pendingPlusClose, showPlusDialog) {
            if (!pendingPlusClose) return@LaunchedEffect
            if (!showPlusDialog) { pendingPlusClose = false; return@LaunchedEffect }
            // 目标高度：面板当前实际占用的高度（真实键盘实测值，未实测才用兜底常量）。
            val threshold = panelBasePx
            // 最多等一个键盘动画量级（420ms）：正常情况键盘升到面板高度即返回（零位移）；
            // 小键盘/异常时序则超时兜底，此时键盘也已基本升起，收起只会产生极小位移，且不会卡在 ×。
            withTimeoutOrNull(420) {
                androidx.compose.runtime.snapshotFlow { currentNetPx() }.first { it >= threshold }
            }
            onClosePlus()
            pendingPlusClose = false
        }
        // 不要再按 slotPinned 把这里强行归零：关闭时 targetPanelPx 自己会动画到 0，
        // panelAnim 跟着降，槽位才有平滑落下；瞬间归零会变成「面板一帧消失」。
        val slotInsets = remember(imeHolder, navHolder) {
            ChatBottomSlotInsets(imeHolder, navHolder) { panelAnim.value.toInt() }
        }
        val stickerGap = 8.dp
        var recordSeconds by remember { mutableStateOf(0) }
        // 手指是否已移出底部半圆（松手即取消）
        var cancelZone by remember { mutableStateOf(false) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        // 录音半圆的流体填充色：直接沿用 AiKeepAliveService「AI 运行中」悬浮球那套色循环
        // （天空蓝 → 青绿 → 浅青 → 浅天空蓝，4 秒一轮）。这里只在绘制阶段读 .value，
        // 不参与组合订阅，所以不会每帧重组整个输入栏。
        val voiceFlowTrans = androidx.compose.animation.core.rememberInfiniteTransition(label = "voiceFlow")
        val voiceFlowAnim = voiceFlowTrans.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(
                    4000,
                    easing = androidx.compose.animation.core.LinearEasing
                ),
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart
            ),
            label = "voiceFlowT"
        )
        val voiceFlowColors = remember {
            intArrayOf(
                0xFF38BDF8.toInt(), // 天空蓝
                0xFF14B8A6.toInt(), // 青绿
                0xFF5EEAD4.toInt(), // 浅青
                0xFF7DD3FC.toInt(), // 浅天空蓝
                0xFF38BDF8.toInt()
            )
        }
        var recorderRef by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
        var recordStartMs by remember { mutableStateOf(0L) }
        var recordFilePath by remember { mutableStateOf("") }
        // 录音计时：每 200ms 刷一次秒数（够跟手，也不至于每帧重组）
        LaunchedEffect(isRecording) {
            while (isRecording) {
                val now = System.currentTimeMillis()
                recordSeconds = ((now - recordStartMs) / 1000).toInt()
                kotlinx.coroutines.delay(200)
            }
        }
        val recordPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (!granted) {
                android.widget.Toast.makeText(context, "需要麦克风权限才能发语音", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        // + 按钮旋转动画（展开面板时转 45° 变成 ×）
        val plusRotation = androidx.compose.animation.core.animateFloatAsState(
            // 键盘正盖住槽位时不显示成 ×（否则「+ → 键盘」过渡期会闪一下 ×）；
            // 与表情钮的 stickerToggleT 口径一致：只在「面板露出且未被键盘盖住」时才是打开态。
            if (showPlusDialog && !imeCovering) 45f else 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
            label = "plusRotation"
        )
        // ── 右侧「加号 ⇄ 发送」一体按钮的形态进度 ──
        // 0 = 圆圈加号（点它展开/收起附件面板），1 = 蓝底纸飞机（点它发送）。
        // hasContent 的口径要和真正能发出去的东西一致：输入框有字、或已选好待发文件、
        // 或挂着 AI 待发附件。少算一种，用户就会看到一个「+」却发不出东西。
        val hasContent = inputText.isNotBlank() || selectedFiles.isNotEmpty() || hasPendingAttachment
        val canSend = !isMuted && !aiInFlight && hasContent
        // AI 会话没有表情可发（表情进不了 AI），所以有内容时把左侧「笑脸」那一格让给加号：
        // 空输入仍是大笑脸 + 加号；有内容时变成「圆圈加号 + 发送条」，附件入口不再被发送键吞掉。
        val plusInStickerSlot = isAiChat && !isMuted && hasContent
        // 左侧那一格是否需要参与组合：AI 对话空态整格不渲染，输入框（weight(1f)）自动铺满，
        // 不再留一个透明的固定占位；有内容时这一格仍是「加号」附件入口。
        val showStickerSlot = !isAiChat || plusInStickerSlot
        // 行尾三态容器宽度（动画值 64/100dp）：Row 内尺寸与引用条右侧留白共用同一份状态，避免两处漂移
        // 左格 = 28dp 按钮 + 8dp 间隙；AI 空态没有左格 -> 0dp。
        // 右侧区域空态 28dp 圆钮、有内容 64dp 发送条。容器宽度由两者相加得出，
        // 旁边 weight(1f) 的输入框自动吃掉省下来的空间，无需任何硬编码偏移。
        val leftSlotWidth = if (showStickerSlot) 36.dp else 0.dp
        val tailContainerWidth by androidx.compose.animation.core.animateDpAsState(
            targetValue = leftSlotWidth + if (canSend || aiInFlight || isMuted) 64.dp else 28.dp,
            animationSpec = androidx.compose.animation.core.tween(200),
            label = "tailContainerWidth"
        )
        val sendProgress = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (canSend) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 200,
                easing = FastOutSlowInEasing
            ),
            label = "sendProgress"
        )
        // ── 形态兜底：不允许「没内容却一直挂着发送条」──
        // 用户反馈：输入框已经空了，行尾却还是蓝色「发送」、加号回不来，点它也只是去开加号面板。
        // 除了媒体弹窗残留（下面关闭处已清队列），animateFloatAsState 偶发被打断也会让
        // target 已回 0 而动画值停在 1。这里只负责把形态「解放」回加号：
        // 不碰发送链路、不代替用户发消息、不改任何状态归属。
        var forcePlusShape by remember { mutableStateOf(false) }
        // 自我轮询：canSend 变 false 后给 200ms 退场动画留 320ms，再直接把形态锁回加号。
        // canSend 一旦变 true，LaunchedEffect 取消并重启，强制立即解除，正常发送路径不受影响。
        LaunchedEffect(canSend) {
            if (canSend) {
                forcePlusShape = false
            } else {
                kotlinx.coroutines.delay(320)
                forcePlusShape = true
            }
        }
        val shapeT = if (forcePlusShape) 0f else sendProgress.value.coerceIn(0f, 1f)
        // 左侧那一格「笑脸 ⇄ 加号」的切换进度（0 = 笑脸/键盘，1 = 圆圈加号）
        val leftPlusT = androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (plusInStickerSlot) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
            label = "leftPlus"
        )

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // ── 禁言横幅 ──
            if (isMuted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFFEF3C7))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "你已被禁言，$muteRemainingText",
                        fontSize = 13.sp,
                        color = Color(0xFF92400E),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 半圆圆心要落到「输入栏真正的最底部」，而这一层 Box 的底边只到 Row 脚下：
            // 外面还有 4dp 垂直 padding + 导航栏 inset，不补上这小段，半圆下方就会剩一条白边。
            val voiceBottomExtraPx = with(density) { 4.dp.toPx() } +
                androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(density)
            // 全屏输入按钮要浮在「输入框最上面一行的左边、麦克风正上方」，
            // 所以给整行套一层 Box 做绝对定位（Row 的垂直对齐只影响它自己的子项）。
            // 同时这层 Box 负责画录音时的底部半圆环（绘制越界，不占布局高度）。
            // 外层只负责一件事：让 HUD 文字排在半圆之后绘制（HUD 是内层的兄弟节点）。
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
            // 内层 = 输入栏本体 + 半圆遮罩。
            // 必须用 drawWithContent：先 drawContent() 把输入框/三个圆钮画完，
            // 再画半圆盖上去。drawBehind 是画在内容**下面**的，用它等于没遮。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        drawContent()
                        if (isRecording) {
                            // 圆心落在输入栏底部中心，只画上半圆；半径 88dp，与手势判定完全一致。
                            // 移出这个半圆后松手才会取消，移出过程中录音继续。
                            val r = 220.dp.toPx()
                            val cx = size.width / 2f
                            // 圆心下移到输入栏真正底边，盖住 padding / 导航栏那一小条白
                            val cy = size.height + voiceBottomExtraPx
                            // 流体色插值：色表与 AI 运行球完全一致（蓝→青绿→浅青→浅蓝）
                            val tt = voiceFlowAnim.value.coerceIn(0f, 1f)
                            val seg = (voiceFlowColors.size - 1) * tt
                            val idx = seg.toInt().coerceIn(0, voiceFlowColors.size - 2)
                            val fr = seg - idx
                            fun ch(c: Int, sh: Int): Float = ((c shr sh) and 0xFF).toFloat()
                            val ca = voiceFlowColors[idx]
                            val cb = voiceFlowColors[idx + 1]
                            fun mix(sh: Int): Int = (ch(ca, sh) * (1 - fr) + ch(cb, sh) * fr).toInt()
                            val flow1 = Color(0xFF shl 24 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0))
                            // 第二层掺 40% 浅青，上下两层不同色 → 流动感
                            fun blend(c: Color, o: Color, p: Float) = Color(
                                red = c.red * (1 - p) + o.red * p,
                                green = c.green * (1 - p) + o.green * p,
                                blue = c.blue * (1 - p) + o.blue * p
                            )
                            val flow2 = blend(flow1, Color(0xFF5EEAD4), 0.40f)
                            // 填充：自下而上渐隐，底部实、顶部几乎透明，像流体积在底部
                            drawArc(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    // 纯色渲染，不带任何透明度：整个半圆都是实心色块，
                                    // 只靠三段不同明度的色带（浅青 → 混合 → 主色）体现流体层次。
                                    colors = listOf(
                                        flow2,
                                        blend(flow1, flow2, 0.55f),
                                        flow1
                                    ),
                                    startY = cy - r,
                                    endY = cy
                                ),
                                startAngle = 180f,
                                sweepAngle = 180f,
                                useCenter = true,
                                topLeft = Offset(cx - r, cy - r),
                                size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                            )
                            // 描边：品牌深蓝；进入取消区变红
                            drawArc(
                                color = if (cancelZone) Color(0xFFE5484D) else Color(0xFF1E40AF),
                                startAngle = 180f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = Offset(cx - r, cy - r),
                                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // 圆钮贴**行底固定**，不随输入框行数变化而上下抬。
                // 输入框长高时整行向上长（行底=屏幕底固定），若用 CenterVertically，
                // 三个圆钮会被行中心一起顶上去（3 行起明显）。改 Bottom 钉住底部，
                // 再由各圆钮自行 +4dp 上移补偿，使单行时与旧 CenterVertically 视觉位置一致。
                verticalAlignment = Alignment.Bottom
            ) {
                // ── 左侧按钮：输入模式=麦克风（点击切语音），语音模式=键盘（点击切回输入）──
                // 不画任何外圈：麦克风、键盘都是直接露出图形本身（微信 / QQ 也是这样），
                // 那圈白底 + 黑描边去掉后，图标才能和右侧按钮在同一视觉重量上。
                // micRingT 保留：它现在只负责「麦克风 ↔ 键盘」的交叉淡入淡出。
                val micRingT by animateFloatAsState(
                    targetValue = if (voiceMode) 0f else 1f,
                    animationSpec = tween(180),
                    label = "micRing"
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        // 贴底后再上移 4dp：单行时与旧 CenterVertically 位置一致，多行时钉住不随输入框抬起
                        .offset(y = (-4).dp)
                        .clip(CircleShape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            voiceMode = !voiceMode
                            // 切到语音时收起键盘：否则「按住说话」上面还顶着输入法
                            if (voiceMode) {
                                keyboardController?.hide()
                                // 底部槽位同一时间只能有一个面板：进语音就把表情面板收掉
                                onShowStickerPanelChange(false)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (voiceMode) {
                        // 键盘：填充式（黑面板 + 白键位 + 空格条），24dp 直接露出、不套外圈，
                        // 随切换进度淡入淡出（与外圈的消隐同步，交接帧不闪）
                        Canvas(
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { alpha = 1f - micRingT }
                        ) { drawKeyboardGlyph() }
                    } else {
                        // 22dp：去掉外圈之后 20dp 显得偏小（圈原本撑着视觉重量），
                        // 只放大 2dp，比例按 24 格算，放大不会变形。
                        Canvas(modifier = Modifier.size(22.dp).graphicsLayer { alpha = micRingT }) {
                            // 全部按 24 格设计空间算，再统一乘 S 缩到实际尺寸（20dp）。
                            // 比例取自参考图：话筒头宽高比约 0.62，托罩直径 = 头宽 × 2，
                            // 支杆短（2.4），线宽 1.7；没有底座。
                            // 旧版（头 0.32w × 0.42h、托罩半径 0.30w、杆长到底）杯比头宽近一倍、
                            // 头又短，看着像「细杆顶大碗」，所以整体重排。
                            val s = size.width / 24f
                            val cx = center.x
                            val sw = 1.7f * s
                            // 话筒头：竖着的胶囊，描边（圆角 = 半径，两端天然是半圆）
                            val headW = 8f * s
                            val headH = 13f * s
                            val headTop = 1.8f * s
                            drawRoundRect(
                                color = com.aurora.chat.ui.theme.appInk(),
                                topLeft = Offset(cx - headW / 2f, headTop),
                                size = androidx.compose.ui.geometry.Size(headW, headH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(headW / 2f),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
                            )
                            // 托罩：下半圆弧（开口 0°→180° 即圆的下半部），把话筒头兜住
                            val arcR = 8f * s
                            val arcCy = 11.8f * s
                            drawArc(
                                color = com.aurora.chat.ui.theme.appInk(),
                                startAngle = 0f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = Offset(cx - arcR, arcCy - arcR),
                                size = androidx.compose.ui.geometry.Size(arcR * 2, arcR * 2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = StrokeCap.Round)
                            )
                            // 支杆：从托罩最低点往下 2.4 就结束，不画底座（参考图没有）
                            val stemTop = arcCy + arcR
                            val stemBottom = stemTop + 2.4f * s
                            drawLine(com.aurora.chat.ui.theme.appInk(), Offset(cx, stemTop), Offset(cx, stemBottom), strokeWidth = sw, cap = StrokeCap.Round)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                // 原先左侧那个独立的「圆圈加号」已撤掉：它和右侧发送按钮现在是同一个东西，
                // 合成到行尾那个会变形的圆钮上（见下方）。

                // 正文样式抽出来：占位文字必须用同一套 fontSize + lineHeight，
                // 否则占位会继承 Material 默认行高（24sp），把空态输入框撑高 4dp。
                val fieldTextStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = if (isMuted) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextPrimary()
                )
                // 输入框的填充 / 描边 / 圆角全部来自 barLook（设置 → 消息设置 → 输入框设置）。
                // 描边只在「经典描边」「全圆角描边」两种样式下才画，默认样式没有那圈线。
                val fieldBorder = barLook.border
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(barLook.radius))
                        .background(barLook.fieldBg, RoundedCornerShape(barLook.radius))
                        .then(
                            if (fieldBorder != null) {
                                Modifier.border(
                                    barLook.borderWidth,
                                    fieldBorder,
                                    RoundedCornerShape(barLook.radius)
                                )
                            } else Modifier
                        )
                        // 单行时保持 36dp 的视觉高度；多行时由内容自己撑开。
                        // 这里必须用 defaultMinSize 而不是把 min 挂在 BasicTextField 上：
                        // heightIn(min = 36.dp) 会把 Measurable 的高度下限锁在 36dp，
                        // 两行文本（2×20sp）在部分 density 下算出来正好贴着这个下限，
                        // 结果就是「两行挤在一行的高度里」——垂直内边距由 padding(vertical=8.dp) 提供，
                        // 单行 20+16=36dp 与旧版完全一致，两行则是 40+16=56dp，自然腾出两行。
                        .defaultMinSize(minHeight = 36.dp)
                        .padding(horizontal = 12.dp, vertical = 0.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = if (isMuted) {{}} else onInputChange,
                        // 高度交给文本自己决定：先按 maxInputHeight 限住可增长的上限（10.5 行），
                        // 再用 padding 补出上下呼吸空间。不再设 min，否则内容会被压回一行高。
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 8.dp)
                            // 记录「纯文本区」高度（不含上下内边距），
                            // 这样 showFullscreenBtn 的 3.5 行阈值与改动前一致。
                            .onSizeChanged { inputBoxHeightPx = it.height }
                            // 语音模式必须把文本区锁回单行：graphicsLayer(alpha=0) 只隐藏绘制，
                            // 不参与测量。不锁的话草稿有 N 行，整条输入栏（连同「按住说话」）
                            // 就会被这层看不见的文本撑成 N 行高。
                            .then(
                                if (voiceMode) Modifier.height(lineHeightDp)
                                else Modifier.heightIn(max = maxInputHeight)
                            )
                            .focusRequester(inputFocusRequester)
                            // 聚焦输入框也不要立刻收起「+」面板（否则槽位先落、键盘再顶，两段式）。
                            // 只登记待收起标记，等键盘升到位后再收（见 pendingPlusClose 的 LaunchedEffect）。
                            // 表情槽位同样不卸载：键盘是盖在窗口上的，获焦后直接盖住即可。
                            .onFocusChanged { if (it.isFocused) pendingPlusClose = true }
                            // 语音模式下整块隐藏（含占位提示），避免和「按住说话」文字叠在一起
                            .graphicsLayer { alpha = if (voiceMode) 0f else 1f },
                        singleLine = false, maxLines = 11,
                        readOnly = isMuted,
                        textStyle = fieldTextStyle,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(if (isMuted) Color.Transparent else Color(0xFF1E40AF)),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                // 空输入框不再显示「输入消息...」占位预览。
                                // 只保留禁言态提示：那是状态说明而非占位文案，删掉会让被禁言的人
                                // 以为输入框坏了。语音模式下整个 BasicTextField 已由 graphicsLayer 隐藏。
                                if (inputText.isEmpty() && isMuted) {
                                    Text(
                                        text = "你已被禁言",
                                        style = fieldTextStyle,
                                        color = com.aurora.chat.ui.theme.appTextHint(),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    // ── 语音模式：整块盖住输入框，居中显示「按住说话」──
                    // 盖一层而不是把 BasicTextField 摘掉：光标/草稿/焦点状态全部原样保留，
                    // 切回键盘模式时内容一个字都不会丢。
                    if (voiceMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                // 不再自己铺底色：整条输入栏由根 Box 统一配色（录音时整条变蓝），
                                // 这里一旦自己画白/蓝，就会把左右两侧切成两种颜色。
                                .pointerInput(Unit) {
                                    // 不用 detectTapGestures：tap 手势在手指移动超过 touchSlop 时就判定取消，
                                    // 按住说话时只要轻轻一挪录音就断了。这里只认「真正抬起」。
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        // 按下即震动，给一个明确的"已按住"反馈
                                        haptic.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                        )
                                        run {
                                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                                    context, android.Manifest.permission.RECORD_AUDIO
                                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                            ) {
                                                recordPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                val f = java.io.File(
                                                    context.cacheDir,
                                                    "voice_${System.currentTimeMillis()}.m4a"
                                                )
                                                val rec = runCatching {
                                                    val mr =
                                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                                                            android.media.MediaRecorder(context)
                                                        else @Suppress("DEPRECATION") android.media.MediaRecorder()
                                                    mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                                                    mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                                                    mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                                                    mr.setAudioEncodingBitRate(64000)
                                                    mr.setAudioSamplingRate(44100)
                                                    mr.setOutputFile(f.absolutePath)
                                                    mr.prepare()
                                                    mr.start()
                                                    mr
                                                }.getOrNull()
                                                if (rec != null) {
                                                    recorderRef = rec
                                                    recordFilePath = f.absolutePath
                                                    recordStartMs = System.currentTimeMillis()
                                                    recordSeconds = 0
                                                    isRecording = true
                                                } else {
                                                    f.delete()
                                                    android.widget.Toast.makeText(context, "录音启动失败", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                        // 手指跟踪：以「底部中心」为圆心、半径 88dp 的半圆为界。
                                        // 关键点是"移出去不立刻断"，只有「移出后松手」才算取消。
                                        val cancelR = 220.dp.toPx()
                                        val cancelR2 = cancelR * cancelR
                                        val cx = size.width / 2f
                                        // 与绘制用同一个圆心（含底部补偿），保证视觉范围 = 判定范围
                                        val cy = size.height.toFloat() + voiceBottomExtraPx
                                        var outside = false
                                        var released = false
                                        while (isRecording) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            val dx = ch.position.x - cx
                                            val dy = ch.position.y - cy
                                            outside = dx * dx + dy * dy > cancelR2
                                            if (outside != cancelZone) {
                                                // 进/出取消区的瞬间各震一次：移出=即将取消，缩回=继续录音
                                                haptic.performHapticFeedback(
                                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                                )
                                            }
                                            cancelZone = outside
                                            if (!ch.pressed) {
                                                released = true
                                                break
                                            }
                                        }
                                        cancelZone = false
                                        if (isRecording) {
                                            isRecording = false
                                            runCatching { recorderRef?.stop() }
                                            runCatching { recorderRef?.release() }
                                            recorderRef = null
                                            val secs = ((System.currentTimeMillis() - recordStartMs) / 1000).toInt()
                                            val path = recordFilePath
                                            recordFilePath = ""
                                            when {
                                                !released -> {
                                                    // 手势被系统/其它控件抢走：静静丢弃，不打扰用户
                                                    if (path.isNotEmpty()) runCatching { java.io.File(path).delete() }
                                                }
                                                outside -> {
                                                    if (path.isNotEmpty()) runCatching { java.io.File(path).delete() }
                                                    android.widget.Toast.makeText(
                                                        context, "已取消", android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                                secs >= 1 && path.isNotEmpty() && java.io.File(path).length() > 0 ->
                                                    onSendVoice(path, secs)
                                                else -> {
                                                    if (path.isNotEmpty()) runCatching { java.io.File(path).delete() }
                                                    android.widget.Toast.makeText(
                                                        context, "说话时间太短", android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when {
                                    !isRecording -> "按住说话"
                                    cancelZone -> "松开取消"
                                    else -> "松开结束 · ${recordSeconds}s"
                                },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = when {
                                    !isRecording -> com.aurora.chat.ui.theme.appTextSecondary()
                                    cancelZone -> Color(0xFFE5484D)
                                    else -> com.aurora.chat.ui.theme.appTextSecondary()
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // AI 确认发言按钮
                // 【本会话正在生成时禁用】此前它只判断 isMuted，于是在本轮回复仍在跑（尤其是还在
                // 「等待模型响应」空窗）时再点一次，会另起一轮回复、多插一条等待占位，
                // 旧占位只按 text == AI_THINKING 清理，开了「显示思考过程」时清不掉 →
                // 屏上出现两个「等待模型响应」、其中一个永远停在原地计时。直接禁用是最省事的根治。
                if (isAiChat && confirmSpeechEnabled) {
                    Box(
                        modifier = Modifier
                            .height(36.dp).widthIn(min = 68.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF7C3AED).copy(alpha = if (aiInFlight) 0.4f else 1f))
                            .clickable(enabled = !isMuted && !aiInFlight) { onConfirmReply() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("确认发言", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // ── 行尾「表情 · 加号 ⇄ 发送」三态容器 ──
                // 空输入：左笑脸 + 右加号（宽 64dp）；有内容：左笑脸 + 右侧蓝色发送条（宽 100dp）。
                //
                // 【为什么容器要变宽】发送条是从右侧向左生长的。容器宽度不变的话，它会长到左边
                // 直接压住笑脸（笑脸被盖住、也点不到）。所以有内容时把容器加宽 36dp，让
                // 「笑脸 28 + 间隙 8 + 发送条 64」三段并排；旁边 weight(1f) 的输入框会同步
                // 让出这 36dp，按钮区既不遮输入框、也不遮笑脸。
                Box(
                    modifier = Modifier
                        // 与麦克风同一垂直基准：贴底 + 4dp 上移，多行时不随输入框抬起。
                        .size(width = tailContainerWidth, height = 28.dp)
                        .offset(y = (-4).dp)
                        .clickable(
                            enabled = !isMuted,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            when {
                                aiInFlight -> onStopSend?.invoke()
                                // 有内容 → 发送。顺手把面板收起（和面板按钮的行为一致，
                                // 否则发完消息面板还挂在下面）
                                canSend -> { onClosePlus(); onSend() }
                                else -> {
                                    // 没内容却仍挂着「发送」条时，点一下立刻解放回加号，不等轮询。
                                    // 这里绝不调 onSend，避免幽灵态下发出空消息。
                                    forcePlusShape = true
                                    onTogglePlus()
                                }
                            }
                        }
                        .drawBehind {
                            // 禁言 / 停止两种特殊态直接铺满，不参与形变
                            val t = if (isMuted || aiInFlight) 1f else shapeT
                            val h = size.height
                            // 右对齐生长：空态宽 = h（正圆），有内容时最宽 64dp（只占最右侧，
                            // 不越过左边那一格笑脸）
                            val barMaxW = 64.dp.toPx()
                            val w = h + (barMaxW - h) * t
                            val left = size.width - w
                            // 圆角：正圆时 = h/2，矩形时 = 8dp
                            val radius = (h / 2f) * (1f - t) + 8.dp.toPx() * t
                            // 空态（加号）时：浅色 = 白圆底 + 黑描边，深色 = 黑圆底 + 白描边。
                            // 有内容时圆底渐变为品牌蓝，描边随之淡出，两套配色都能自然过渡。
                            val dark = com.aurora.chat.ui.theme.isAppDark()
                            val baseFill = if (dark) Color.Black else Color.White
                            val baseStroke = if (dark) Color.White else Color.Black
                            val fill = when {
                                isMuted -> Color(0xFFD1D5DB)
                                aiInFlight -> Color(0xFFEF4444)
                                else -> androidx.compose.ui.graphics.lerp(baseFill, Color(0xFF1E40AF), t)
                            }
                            drawRoundRect(
                                color = fill,
                                topLeft = Offset(left, 0f),
                                size = androidx.compose.ui.geometry.Size(w, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                            )
                            if (!isMuted && !aiInFlight) {
                                // 描边跟随圆底色板（深色=白圈，浅色=黑圈），随蓝底淡出
                                val stroke = 1.5.dp.toPx()
                                val inner = (radius - stroke / 2f).coerceAtLeast(0f)
                                drawRoundRect(
                                    color = baseStroke.copy(alpha = 1f - t),
                                    topLeft = Offset(left + stroke / 2f, stroke / 2f),
                                    size = androidx.compose.ui.geometry.Size(w - stroke, h - stroke),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(inner, inner),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // ── 左侧表情钮：笑脸 ⇄ 键盘 ──
                    // 只切焦点与图标，底部槽位的高度始终不变：
                    // 点笑脸 = 撤掉键盘、露出槽位里的面板；点键盘 = 弹键盘把槽位盖回去。
                    // 常驻：不再随发送态淡出、也不再交出点击。发送条只在它右侧 64dp 区域里生长，
                    // 两者互不重叠，所以不管有没有内容，按钮都看得见、点得到。
                    // 表情态（槽位 pin 住且键盘没盖着）显示键盘图标，其余显示笑脸。
                    val stickerToggleT by animateFloatAsState(
                        if (showStickerPanel && !imeCovering) 1f else 0f,
                        animationSpec = tween(180),
                        label = "stickerToggle"
                    )
                    if (showStickerSlot) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(28.dp)
                                // key 必须是 plusInStickerSlot：pointerInput(Unit) 会把首次组合时的
                                // 布尔值固化进闭包，之后有内容了也还按「笑脸」分支走。
                                .pointerInput(plusInStickerSlot) {
                                    detectTapGestures {
                                        if (plusInStickerSlot) {
                                            // AI 会话、有内容：这一格是附件入口，开/收 Plus 面板。
                                            // 必须在这里消费点击，否则会穿透到父容器触发发送。
                                            onTogglePlus()
                                        } else if (imeCoveringState.value) {
                                            // 键盘正盖着底部 → 只把键盘撤掉，槽位里的面板本来就在下面。
                                            // 槽位高度由 slotInsets 自己算，这里不碰任何高度值。
                                            runCatching { focusManager.clearFocus(force = true) }
                                            if (!showStickerPanelState.value) onShowStickerPanelChange(true)
                                        } else if (showStickerPanelState.value) {
                                            // 表情态 → 弹键盘把槽位盖住：max(imeNet, panelPx) 恒等，
                                            // 输入栏一动不动。
                                            runCatching { inputFocusRequester.requestFocus() }
                                            showImeCompat()
                                        } else {
                                            // 空闲 → pin 住槽位（首次用兜底，键盘一弹起即被实测值取代）
                                            runCatching { focusManager.clearFocus(force = true) }
                                            onShowStickerPanelChange(true)
                                        }
                                    }
                                }
                                .clip(CircleShape)
                                .drawBehind {
                                    // 圆钮只属于「加号」态。笑脸换成 C 版之后，脸自己就是一圈轮廓，
                                    // 外面再套一圈就成了圆里套圆，所以笑脸态不再画。
                                    // 用 leftPlusT 单值控制：「加号 ↔ 笑脸」切换时圈与加号同步淡入淡出。
                                    // 配色：
                                    //   浅色 = 白圆底 + 黑描边 + 黑加号（改动前的观感）
                                    //   深色 = 黑圆底 + 白描边 + 白加号（圆与外圈都白、底色仍黑）
                                    val ringA = leftPlusT.value
                                    if (ringA > 0.01f) {
                                        val stroke = 1.4.dp.toPx()
                                        val dark = com.aurora.chat.ui.theme.isAppDark()
                                        drawCircle(
                                            color = if (dark) Color.Black else Color.White,
                                            alpha = ringA
                                        )
                                        drawCircle(
                                            color = if (dark) Color.White else Color.Black,
                                            radius = size.minDimension / 2f - stroke / 2f,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                                            alpha = ringA
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // 笑脸（面板关闭态）：随切换进度淡出。
                            // 造型 B：纯线稿描边脸，嘴是一条细弧、不填充。
                            // 比 C 放大到 28dp（触点 28dp，脸圈直径 22.4dp 仍在圆内），线宽再收细一档。
                            Canvas(modifier = Modifier.size(28.dp).graphicsLayer { alpha = (1f - stickerToggleT) * (1f - leftPlusT.value) }) {
                                val s = size.width / 24f
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                // 脸：描边圆（半径比 C 略放，配合整体放大）
                                // 物理线宽随 Canvas 一起变大，所以格数从 1.7 收到 1.45，
                                // 抵消放大后实际约 1.7dp，比 26dp 版略细一点点。
                                drawCircle(
                                    color = com.aurora.chat.ui.theme.appInk(),
                                    radius = 9.6f * s,
                                    center = Offset(cx, cy),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.45f * s)
                                )
                                // 眼：实心小圆点（全镂空在 24dp 下会糊）
                                val eyeDx = 3.4f * s
                                val eyeY = cy - 3.1f * s
                                drawCircle(com.aurora.chat.ui.theme.appInk(), 1.15f * s, Offset(cx - eyeDx, eyeY))
                                drawCircle(com.aurora.chat.ui.theme.appInk(), 1.15f * s, Offset(cx + eyeDx, eyeY))
                                // 嘴：细描边笑弧，圆头两端。B 与 C 的唯一区别：这里是线，不是实心月牙。
                                // 位置保持「下移到脸中下部」（端点 cy+2.9、底 cy+5.5），
                                // 弧度取三版中间：弓高 2.65 格（最初 3.12 太弯、上一版 2.06 太平）。
                                val mouthR = 6.0f * s
                                val mouthCy = cy - 0.5f * s
                                drawArc(
                                    color = com.aurora.chat.ui.theme.appInk(),
                                    startAngle = 34f,
                                    sweepAngle = 112f,
                                    useCenter = false,
                                    topLeft = Offset(cx - mouthR, mouthCy - mouthR),
                                    size = androidx.compose.ui.geometry.Size(mouthR * 2f, mouthR * 2f),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 1.45f * s,
                                        cap = StrokeCap.Round
                                    )
                                )
                            }
                            // 键盘（面板打开态）：随切换进度淡入。填充式画法（黑面板 + 白键位 + 空格条），
                            // 与语音钮那把键盘共用 drawKeyboardGlyph()；不带外圈，本体放大到 24dp
                            Canvas(
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer { alpha = stickerToggleT * (1f - leftPlusT.value) }
                            ) { drawKeyboardGlyph() }
                            // 加号（AI 会话有内容态）：白圆底 + 黑描边由外层 drawBehind 负责，
                            // 这里只补两笔加号；面板展开时沿用 plusRotation 转成 ×。
                            Canvas(
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer {
                                        alpha = leftPlusT.value
                                        rotationZ = plusRotation.value
                                    }
                            ) {
                                val c = center
                                val len = 4.5.dp.toPx()
                                val sw = 1.6.dp.toPx()
                                // 加号笔画跟随圆底色板：浅色主题黑加号（白圆）、深色主题白加号（黑圆）
                                val plusInk = if (com.aurora.chat.ui.theme.isAppDark()) Color.White else Color.Black
                                drawLine(plusInk, Offset(c.x - len, c.y), Offset(c.x + len, c.y), strokeWidth = sw, cap = StrokeCap.Round)
                                drawLine(plusInk, Offset(c.x, c.y - len), Offset(c.x, c.y + len), strokeWidth = sw, cap = StrokeCap.Round)
                            }
                        }
                    }
                    when {
                        aiInFlight -> {
                            // 停止：白色圆角方块（不用图标，省一次图标依赖）。
                            // 必须和「发送」文字用同一块 64dp 右对齐区域居中：
                            // 直接裸放 Canvas 的话会吃 Box 默认的 TopStart，缩到左上角去，
                            // 红底 64dp 条里就只剩个偏心的白块。
                            Box(
                                modifier = Modifier
                                    .width(64.dp)
                                    .fillMaxHeight()
                                    .align(Alignment.CenterEnd),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.size(10.dp)) {
                                    drawRoundRect(
                                        color = Color.White,
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                                    )
                                }
                            }
                        }
                        isMuted -> {
                            // 同上：锁图标也必须在 64dp 灰条里居中，不能贴 TopStart。
                            Box(
                                modifier = Modifier
                                    .width(64.dp)
                                    .fillMaxHeight()
                                    .align(Alignment.CenterEnd),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "禁言中",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        else -> {
                            // 加号层：贴在右端那个 32dp 圆里（圆心距右边缘 16dp），
                            // 随 t 淡出缩小，同时保留「展开面板转 45° 变 ×」的既有反馈。
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .align(Alignment.CenterEnd),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer {
                                            val t = shapeT
                                            alpha = 1f - t
                                            rotationZ = plusRotation.value
                                            val s = 1f - 0.3f * t
                                            scaleX = s
                                            scaleY = s
                                        }
                                ) {
                                    val c = center
                                    // 臂长 4.5dp、线宽 1.6dp：再收一圈，和 28dp 圆环成比例。
                                    // 笔画跟圆底色板走：浅色 = 黑加号 + 白圆底；深色 = 白加号 + 黑圆底。
                                    val len = 4.5.dp.toPx()
                                    val sw = 1.6.dp.toPx()
                                    val plusInk = if (com.aurora.chat.ui.theme.isAppDark()) Color.White else Color.Black
                                    drawLine(plusInk, Offset(c.x - len, c.y), Offset(c.x + len, c.y), strokeWidth = sw, cap = StrokeCap.Round)
                                    drawLine(plusInk, Offset(c.x, c.y - len), Offset(c.x, c.y + len), strokeWidth = sw, cap = StrokeCap.Round)
                                }
                            }
                            // 发送文字：右对齐在发送条区域里居中（和矩形一起向左长），随 t 淡入。
                            // 无左格时容器只有 28dp，宽度同步收窄，避免透明块越界盖住输入框。
                            Box(
                                modifier = Modifier
                                    .width(if (showStickerSlot) 64.dp else 28.dp)
                                    .fillMaxHeight()
                                    .align(Alignment.CenterEnd)
                                    .graphicsLayer { alpha = shapeT },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "发送",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
                // ── 全屏输入：内容超过 3 行时，浮在输入框最上面一行的左侧（麦克风正上方）──
                // 用绝对定位而不是塞进 Row：Row 的垂直对齐变化不会把它带到顶部。
                if (showFullscreenBtn) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                // 草稿单独存：取消不污染原内容，完成才回填
                                fullscreenDraft = inputText
                                showFullscreenInput = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "全屏输入",
                            tint = com.aurora.chat.ui.theme.appTextSecondary(),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
            // 录音 HUD：必须是内层 Box 的兄弟节点（绘制顺序在内层之后），
            // 否则会被不透明半圆整片盖住。
            //
            // 外面再套一层 matchParentSize 的 Box：让 HUD 的高度**不参与**外层 Box 的高度计算。
            // 否则 HUD（约 48dp）会把外层撑得比输入栏本体（约 36dp）高，
            // 而半圆圆心 = 本体高度 + 4dp + 导航栏 inset，终点就会落在屏幕底边上方的十几 dp，
            // 底部留出一条白边——之前那块白就是这么来的。
            if (isRecording) {
                Box(modifier = Modifier.matchParentSize()) {
                    Column(
                        modifier = Modifier
                            // align 不能省：只写 wrapContentSize 时，Column 的宽度会被收成文字自身宽度，
                            // 而它在父 Box 里默认是 TopStart 摆放 —— 整块文字就跑到最左边去了。
                            // 这里先让父 Box 按 TopCenter 摆这个节点，再解开高度分摊。
                            .align(Alignment.TopCenter)
                            // 外层 Box 被 matchParentSize 钉成输入栏高度（约 36dp）。
                            // 直接塞进来时，Column 会把「36 - 第一行 - 间距」这点剩余高度分给第二行，
                            // 秒数只剩几个 dp，被 Text 自己裁成一条缝 —— 数字消失就是这个原因。
                            // unbounded = true：按内容自身高度测量，不参与父高度的分摊。
                            .wrapContentSize(Alignment.TopCenter, unbounded = true)
                            .offset(y = (-150).dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (cancelZone) "松手取消录制" else "上滑取消",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(6.dp))
                        // 只显示「本次已录多久」：移出/缩回取消区都不打断、不归零、不另记一套秒数。
                        Text(
                            text = "${recordSeconds}s",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
            }
            // ── 底部槽位：表情 / 加号 / 键盘共用同一块高度 ──
            // 高度由 slotInsets 在 layout 阶段给出（见 ChatBottomSlotInsets）：
            // 面板只是填进这块区域的内容，切换谁都不改变布局高度，输入栏全程不动。
            // 表情与加号共用同一槽位，因此两者天然等高，切输入法时不会再有高度差抽搐。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .bottomSlotHeight(slotInsets)
                    .clipToBounds()
            ) {
                if (slotPinned) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            // 抬起动画：整体从底部滑入。offset 的 lambda 重载在 layout 阶段
                            // 读 panelAnim.value，不会让输入栏每帧重组。
                            .offset {
                                val p = if (targetPanelPx > 0)
                                    (panelAnim.value / targetPanelPx).coerceIn(0f, 1f) else 1f
                                androidx.compose.ui.unit.IntOffset(
                                    0, (targetPanelPx * (1f - p)).toInt()
                                )
                            }
                    ) {
                        if (showStickerPanel) {
                            Spacer(Modifier.height(stickerGap))
                            StickerPanel(
                                modifier = Modifier.weight(1f),
                                panelHeight = null, // 高度交给槽位，不再自己写死
                                // AI 对话即便面板被意外展开，点表情也不回调到屏幕层，绝不发往服务端
                                onSendSticker = if (isAiChat) ({ _: String -> }) else onSendSticker,
                                onSendEmoji = if (isAiChat) ({ _: String -> }) else onSendEmoji,
                                onPickFromAlbum = { customStickerPicker.launch("image/*") },
                                customStickers = customStickers,
                                // 自定义表情是本地文件，按普通图片消息发送（对方也能收到）；
                                // AI 对话纯本地，面板不会展开，这里再兜一层不回调
                                onSendCustomSticker = { path ->
                                    if (!isAiChat) {
                                        val f = java.io.File(path)
                                        onSendMedia(Uri.fromFile(f), 0, f.name, false)
                                    }
                                },
                                onDeleteCustomSticker = { path ->
                                    com.aurora.chat.data.local.CustomStickerStore.remove(path)
                                    customStickers = com.aurora.chat.data.local.CustomStickerStore.list(context)
                                }
                            )
                        } else {
                            PlusDialogContent(
                                friendId = friendId,
                                context = context,
                                onAction = handleDialogAction,
                                isAiChat = isAiChat,
                                fillHeight = true,
                                // 按钮块底色跟随输入框主题：灰底栏用白块，
                                // 接近纯白的栏底（浅灰填充 / 全圆角 / 自定义纯白）自动换成中性浅灰。
                                buttonBlockColor = barLook.buttonBlockBg,
                                // 转账 / 发红包门控：非好友 / 拉黑 / 对方开启验证 等场景灰显并点击提示
                                payDisabled = payDisabled,
                                payBlockReason = payBlockReason
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // ── 全屏输入编辑界面 ──
    // 微信式布局：顶部左侧「取消」、右侧「完成」，中间整块文本编辑区，键盘常驻。
    // 取消 = 丢弃草稿；完成 = 把草稿回填到输入框。
    if (showFullscreenInput) {
        Dialog(
            onDismissRequest = { showFullscreenInput = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .imePadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "取消",
                            fontSize = 15.sp,
                            color = com.aurora.chat.ui.theme.appTextSecondary(),
                            modifier = Modifier.clickable { showFullscreenInput = false }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "全屏输入",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = com.aurora.chat.ui.theme.appTextSecondary()
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "完成",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable {
                                onInputChange(fullscreenDraft)
                                showFullscreenInput = false
                            }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(com.aurora.chat.ui.theme.appCardBg())
                    )
                    BasicTextField(
                        value = fullscreenDraft,
                        onValueChange = { fullscreenDraft = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                            .focusRequester(fullscreenFocusRequester),
                        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (fullscreenDraft.isEmpty()) {
                                    Text("输入内容…", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        }
    }

    // 「文件」来源选择弹窗（本地文件 / 工作区）：所有会话类型共用
    if (showFileSourceDialog) {
        Dialog(onDismissRequest = { showFileSourceDialog = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = com.aurora.chat.ui.theme.appSurface()) {
                Column(Modifier.padding(20.dp).widthIn(min = 280.dp)) {
                    Text("选择文件来源", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.height(4.dp))
                    Text("选中的文件会先挂起来，点发送才会发出", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Spacer(Modifier.height(14.dp))
                    // (a) 本地文件
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                            .clickable {
                                showFileSourceDialog = false
                                if (isAiChat) {
                                    // AI 对话：屏幕层选择器 → 挂到输入框上方，随发送按钮一起发
                                    onPickAiFile()
                                } else {
                                    // 好友/群聊：保持原行为（选完弹「已选择 N 个文件」，点发送才真正发出）
                                    selectedFiles = emptyList()
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Description, null, Modifier.size(22.dp), tint = Color(0xFF1E40AF))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("本地文件", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Text("从手机或其它应用中选择文件", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        }
                        Text("›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                    Spacer(Modifier.height(10.dp))
                    // (b) 工作区
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                            .clickable {
                                showFileSourceDialog = false
                                onPickWorkspaceFile()
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, null, Modifier.size(22.dp), tint = Color(0xFF0284C7))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("工作区", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Text("从 AI 工作区中选择", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        }
                        Text("›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showFileSourceDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                    }
                }
            }
        }
    }

    // 应用内相册选择器：全屏白底，底栏「原图」+ 发送。
    // 点发送直接发（与微信一致），不再走二次确认弹窗；原图勾选时整批不压缩直传。
    if (showMediaPicker) {
        Dialog(
            onDismissRequest = { showMediaPicker = false },
            // decorFitsSystemWindows = false：让对话框铺到状态栏后面，由页面自己画白底，
            // 否则状态栏那一条会露出系统默认灰
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            MediaPickerScreen(
                maxCount = (10 - selectedFiles.size).coerceAtLeast(1),
                onDismiss = { showMediaPicker = false },
                onConfirm = { uris, original ->
                    showMediaPicker = false
                    onClosePlus()
                    uris.forEach { uri ->
                        val mime = context.contentResolver.getType(uri) ?: ""
                        onSendMedia(uri, 0, resolvePickerFileName(context, uri, mime), original)
                    }
                }
            )
        }
    }

    // 取消选择：关闭弹窗时必须一并清掉隐藏的待发队列（以及闪照设置）。
    // 只关弹窗不清队列的话，selectedFiles 会残留并一直把 hasContent 顶成 true，
    // 表现就是「输入框已经空了，加号不回来、按钮还挂着发送」。
    fun cancelMediaSelection() {
        showMediaConfirmDialog = false
        selectedFiles = emptyList()
        flashSettings = emptyMap()
    }

    // 媒体选择确认弹窗（大弹窗，每行左侧预览 + 右侧 X 删除）
    if (showMediaConfirmDialog && selectedFiles.isNotEmpty()) {
        Dialog(onDismissRequest = { cancelMediaSelection() }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(460.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(com.aurora.chat.ui.theme.appSurface())
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // ── 顶部标题栏 ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已选择 ${selectedFiles.size} 个文件",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()
                            )
                            IconButton(onClick = { cancelMediaSelection() }) {
                                Icon(Icons.Filled.Close, "关闭", tint = com.aurora.chat.ui.theme.appTextHint())
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

                        // ── 文件列表（每行：左预览 + 文件名/大小 + 右 X 删除） ──
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // key 用文件 uri（稳定）而非 index：删除中间项时其余项保持身份不误重组
                            items(selectedFiles.withIndex().toList(), key = { it.value.first }) { (index, file) ->
                                var showFlashMenu by remember { mutableStateOf(false) }
                                val currentFlash = flashSettings[index] ?: 0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(com.aurora.chat.ui.theme.appCardBg())
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(com.aurora.chat.ui.theme.appDivider())
                                    ) {
                                        coil.compose.AsyncImage(
                                            model = file.first,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    // 闪照按钮（仅图片显示）
                                    if (isImageFile(file.second)) {
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (currentFlash > 0) Color(0xFF7C3AED).copy(alpha = 0.15f)
                                                        else com.aurora.chat.ui.theme.appDivider()
                                                    )
                                                    .clickable(
                                                        indication = null,
                                                        interactionSource = remember { MutableInteractionSource() }
                                                    ) { showFlashMenu = true },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (currentFlash > 0) {
                                                    Text(
                                                        "${currentFlash}s",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF7C3AED)
                                                    )
                                                } else {
                                                    FlashIcon(iconSize = 18.dp)
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = showFlashMenu,
                                                onDismissRequest = { showFlashMenu = false }
                                            ) {
                                                Text("闪照", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                                listOf(3, 5, 10).forEach { sec ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text("$sec 秒", fontSize = 14.sp)
                                                                if (currentFlash == sec) {
                                                                    Spacer(Modifier.width(8.dp))
                                                                    Text("✓", fontSize = 14.sp, color = Color(0xFF7C3AED))
                                                                }
                                                            }
                                                        },
                                                        onClick = {
                                                            flashSettings = flashSettings + (index to sec)
                                                            showFlashMenu = false
                                                        }
                                                    )
                                                }
                                                if (currentFlash > 0) {
                                                    HorizontalDivider()
                                                    DropdownMenuItem(
                                                        text = { Text("取消闪照", fontSize = 14.sp, color = Color(0xFFEF4444)) },
                                                        onClick = {
                                                            flashSettings = flashSettings - index
                                                            showFlashMenu = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            selectedFiles = selectedFiles.toMutableList().also { it.removeAt(index) }
                                            if (selectedFiles.isEmpty()) showMediaConfirmDialog = false
                                        }
                                    ) {
                                        Icon(
                                            Icons.Filled.Close, "移除",
                                            tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                        }

                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                        // ── 底部：上传按钮（左）+ 发送按钮（右） ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f).height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(com.aurora.chat.ui.theme.appCardBg())
                                    .clickable { filePickerLauncher.launch(arrayOf("*/*")) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("上传", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                            }
                            Box(
                                modifier = Modifier
                                    .weight(2f).height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selectedFiles.isNotEmpty() && !isMuted) Color(0xFF1E40AF) else Color(0xFFD1D5DB))
                                    .clickable(enabled = selectedFiles.isNotEmpty() && !isMuted) {
                                        for ((idx, file) in selectedFiles.withIndex()) {
                                            // 文件/工作区注入的条目一律按非原图处理（文件本身不做压缩）
                                            onSendMedia(file.first, flashSettings[idx] ?: 0, file.second, false)
                                        }
                                        selectedFiles = emptyList()
                                        flashSettings = emptyMap()
                                        showMediaConfirmDialog = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (isMuted) "你已被禁言" else "发送 ${selectedFiles.size} 个文件",
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}




// 自定义相机图标（Material 风格：机身 + 镜头 + 闪光灯）
val CameraIcon: androidx.compose.ui.graphics.vector.ImageVector
    get() = androidx.compose.ui.graphics.vector.ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
            // 机身
            addPath(
                pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                    "M20 4h-3.17L15 2H9L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 14H4V6h4.05l1.83-2h4.24l1.83 2H20v12z"
                ).toNodes(),
                fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
            )
            // 镜头
            addPath(
                pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                    "M12 7c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zm0 8c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z"
                ).toNodes(),
                fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
            )
            // 闪光灯小点
            addPath(
                pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                    "M18.5 7.5c.55 0 1-.45 1-1s-.45-1-1-1-1 .45-1 1 .45 1 1 1z"
                ).toNodes(),
                fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
            )
    }.build()

val TransferIcon: androidx.compose.ui.graphics.vector.ImageVector
    get() = androidx.compose.ui.graphics.vector.ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 上：一条圆头粗横条，左端接一个向右上翘的斜三角（箭头朝左）。
        // 下：中心对称，右端接一个向左下压的斜三角（箭头朝右）。
        // 两条之间留出明显空隙；颜色仍是单色黑，蓝色由 Icon 的 tint 提供（不改配色）。
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M2 10.6 L11.5 1.5 L9 8.6 L21 8.6 Q22 8.6 22 9.6 Q22 10.6 21 10.6 Z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        )
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                "M22 13.4 L12.5 22.5 L15 15.4 L3 15.4 Q2 15.4 2 14.4 Q2 13.4 3 13.4 Z"
            ).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        )
    }.build()

@Composable
fun PlusDialogContent(
    friendId: Long,
    context: android.content.Context,
    onAction: (String) -> Unit,
    isAiChat: Boolean = false,
    // true = 铺满统一底部槽位（与表情面板 / 键盘等高），按钮组垂直居中
    fillHeight: Boolean = false,
    // 每个功能按钮圆角块的底色，由 inputBarLook() 按栏底色派生
    buttonBlockColor: Color = Color.White,
    // 转账 / 发红包门控：非好友 / 拉黑 / 对方开启验证 等场景禁用（灰显 + 点击提示）
    payDisabled: Boolean = false,
    payBlockReason: String = ""
) {
    val isOfficialGroup = friendId == -1001L
    // 「对方就是自己」不算私聊：那条服务端会话没有好友关系，转账/红包必然被拒（不能给自己转账）。
    val isPrivateChat = friendId > 0 && friendId != com.aurora.chat.data.api.AuroraApi.currentUserId
    val accentColor = Color(0xFF1E40AF)

    // 按钮清单只在会话类型变化时重算一次。旧写法每次展开面板都重建这一整组 Tuple4 列表，
    // 把开销全放在了动画首帧上（面板刚组合的那一帧正是最贵的一帧）。
    val allButtons = remember(isAiChat, friendId) {
        if (isAiChat) {
        listOf(
            Tuple4("相册", "image", Icons.Default.Photo, Color.Transparent),
            Tuple4("文件", "file", Icons.Default.Description, Color.Transparent),
            Tuple4("时间点", "time_point", Icons.Default.Schedule, Color.Transparent),
            Tuple4("确认发言", "confirm_speech", Icons.Default.CheckCircle, Color.Transparent)
        )
    } else if (isLocalChat(friendId)) {
        // 自己对话：提供与私聊相同的功能（本地保存）
        listOf(
            Tuple4("相册", "image", Icons.Default.Photo, Color.Transparent),
            Tuple4("拍摄", "camera", CameraIcon, Color.Transparent),
            Tuple4("文件", "file", Icons.Default.Description, Color.Transparent),
            Tuple4("红包", "red_packet", Icons.Default.Redeem, Color.Transparent),
            Tuple4("转账", "transfer", TransferIcon, Color.Transparent),
            Tuple4("秘密", "secret", Icons.Default.Lock, Color.Transparent),
            Tuple4("位置", "location", Icons.Default.LocationOn, Color.Transparent)
        )
    } else if (isPrivateChat) {
        listOf(
            Tuple4("相册", "image", Icons.Default.Photo, Color.Transparent),
            Tuple4("拍摄", "camera", CameraIcon, Color.Transparent),
            Tuple4("文件", "file", Icons.Default.Description, Color.Transparent),
            Tuple4("红包", "red_packet", Icons.Default.Redeem, Color.Transparent),
            Tuple4("转账", "transfer", TransferIcon, Color.Transparent),
            Tuple4("秘密", "secret", Icons.Default.Lock, Color.Transparent),
            Tuple4("位置", "location", Icons.Default.LocationOn, Color.Transparent)
        )
    } else {
        listOf(
            Tuple4("相册", "image", Icons.Default.Photo, Color.Transparent),
            Tuple4("拍摄", "camera", CameraIcon, Color.Transparent),
            Tuple4("文件", "file", Icons.Default.Description, Color.Transparent),
            Tuple4("红包", "red_packet", Icons.Default.Redeem, Color.Transparent),
            Tuple4("转账", "transfer", TransferIcon, Color.Transparent)
        )
        }
    }

    // 开发者专属「广播」入口：仅主开发者（ID=1）可见，AI 对话中不展示。
    // 注意：客户端仅做显示隐藏，真正的越权防护必须由后端在接口内校验 userId==1。
    // 顺带删掉了原先每次展开都读一遍 SharedPreferences 的 4 个局部变量 —— 它们全部未被使用，
    // 只是白白占用了动画首帧的时间。
    val showBroadcast = remember(isAiChat) { AuroraApi.currentUserId == 1L && !isAiChat }
    val buttons = remember(allButtons, showBroadcast) {
        if (showBroadcast) {
            allButtons + Tuple4("广播", "broadcast", Icons.Filled.Campaign, Color.Transparent)
        } else {
            allButtons
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 与表情面板共用同一块槽位：铺满后两者等高，切换输入法不再有高度差。
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
            // 不再铺满一张白卡片：底色直接沿用输入栏那层灰（跟随「输入框设置」的主题），
            // 白色只出现在每个按钮自己的圆角块上，和微信一样「灰底 + 白按钮」。
            // 之前整块刷白，把 PlusDialogButton 里已经画好的白色块全吃掉了。
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = if (fillHeight) Arrangement.Center else Arrangement.spacedBy(0.dp)
    ) {
        if (isPrivateChat) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                buttons.take(4).forEach { (label, actionId, icon, _) ->
                    PlusDialogButton(label = label, icon = icon, accentColor = accentColor, action = actionId, onAction = onAction, blockColor = buttonBlockColor, enabled = (!payDisabled || (actionId != "red_packet" && actionId != "transfer")), disabledReason = if (actionId == "red_packet" || actionId == "transfer") payBlockReason else "")
                }
            }
            if (buttons.size > 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    buttons.drop(4).forEach { (label, actionId, icon, _) ->
                        PlusDialogButton(label = label, icon = icon, accentColor = accentColor, action = actionId, onAction = onAction, blockColor = buttonBlockColor, enabled = (!payDisabled || (actionId != "red_packet" && actionId != "transfer")), disabledReason = if (actionId == "red_packet" || actionId == "transfer") payBlockReason else "")
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                buttons.take(4).forEach { (label, actionId, icon, _) ->
                    PlusDialogButton(label = label, icon = icon, accentColor = accentColor, action = actionId, onAction = onAction, blockColor = buttonBlockColor, enabled = (!payDisabled || (actionId != "red_packet" && actionId != "transfer")), disabledReason = if (actionId == "red_packet" || actionId == "transfer") payBlockReason else "")
                }
            }
            if (buttons.size > 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    buttons.drop(4).forEach { (label, actionId, icon, _) ->
                        PlusDialogButton(label = label, icon = icon, accentColor = accentColor, action = actionId, onAction = onAction, blockColor = buttonBlockColor, enabled = (!payDisabled || (actionId != "red_packet" && actionId != "transfer")), disabledReason = if (actionId == "red_packet" || actionId == "transfer") payBlockReason else "")
                    }
                }
            }
        }
    }
}

@Composable
fun PlusDialogButton(
    label: String,
    icon: ImageVector,
    accentColor: Color,
    action: String,
    onAction: (String) -> Unit,
    blockColor: Color = Color.White,
    enabled: Boolean = true,
    disabledReason: String = ""
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val disabledColor = Color(0xFFB0B3B8)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (enabled) onAction(action)
                else if (disabledReason.isNotBlank()) android.widget.Toast.makeText(ctx, disabledReason, android.widget.Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(blockColor, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) accentColor else disabledColor,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, fontSize = 11.sp, color = if (enabled) com.aurora.chat.ui.theme.appTextSecondary() else disabledColor, fontWeight = FontWeight.Normal)
    }
}

/**
 * 键盘实测高度的持久化缓存（单位 dp）。
 *
 * 面板槽位高度 = max(键盘净高, 面板高)，而面板高取"最近一次实测的键盘高度"，
 * 只有两者相等，「+ 面板 ⇄ 键盘」切换才是零位移。
 * 但 remember 仅存活于单个输入栏实例：每进一次会话都会归零、退回硬编码兜底值，
 * 于是首次「先开加号、再点输入法」时面板高与真实键盘高不等，
 * 输入栏会被多顶起（或落下）一截。这里把实测值落盘，跨会话/跨进程直接拿到真实键盘高度。
 */
internal object ImePanelHeightStore {
    private const val PREF = "aurora_ime_height"
    private const val KEY_DP = "panel_height_dp"
    // 合理区间（dp）：过滤 0 与异常脏数据，避免把离谱值当面板高度
    private fun clampDp(dp: Float) = dp.coerceIn(120f, 480f)

    // 进程内缓存：每进一次会话都会构造输入栏，没必要每次都读一遍磁盘
    @Volatile private var cachedDp = -1f

    /** 读出上次实测的键盘高度（px）；从未测到过则返回 0，由调用方用兜底值 */
    fun loadPx(context: Context, density: Float): Int {
        if (density <= 0f) return 0
        var dp = cachedDp
        if (dp < 0f) {
            dp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getFloat(KEY_DP, 0f)
            cachedDp = dp
        }
        if (dp <= 0f) return 0
        return (clampDp(dp) * density).toInt()
    }

    /** 按 dp 存，换分辨率/字体缩放后仍能换算回与当前键盘一致的高度 */
    fun savePx(context: Context, density: Float, px: Int) {
        if (px <= 0 || density <= 0f) return
        val dp = clampDp(px / density)
        cachedDp = dp
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_DP, dp).apply()
    }
}

/**
 * 底部槽位：键盘与「表情 / 加号」面板共用同一块区域，只有一个高度来源。
 *
 *   bottom = max(ime − navigationBars, panelPx)
 *
 * · 键盘在场 → 由系统 IME inset 逐帧决定，输入栏精确贴在键盘顶；
 * · 面板展开、键盘退场 → 由 panelPx（上次实测的键盘净高）撑住同一高度；
 * · 两者相等时 max 恒为同一个值，所以「表情 ⇄ 键盘 ⇄ 加号」切换时布局零变化。
 *
 * 全部在 layout 阶段读取 inset 与 panelPx：键盘动画逐帧自适应，但不触发任何重组，
 * 也不会和另一套高度动画叠在一起 —— 这是「抽搐」的根治点。
 */
internal class ChatBottomSlotInsets(
    private val ime: androidx.compose.foundation.layout.WindowInsets,
    private val nav: androidx.compose.foundation.layout.WindowInsets,
    private val panelPx: () -> Int
) : androidx.compose.foundation.layout.WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int = 0
    override fun getTop(density: Density): Int = 0
    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int = 0
    override fun getBottom(density: Density): Int {
        val net = (ime.getBottom(density) - nav.getBottom(density)).coerceAtLeast(0)
        return maxOf(net, panelPx())
    }
}

/** 把 measurable 的高度锁成槽位 inset 的高度，内容直接铺满这块区域。 */
private fun Modifier.bottomSlotHeight(insets: ChatBottomSlotInsets): Modifier =
    this.layout { measurable, constraints ->
        // lambda 的 receiver 就是 MeasureScope（实现 Density），inset 与 State 都在这里
        // 的 measure snapshot 中读取：变化只触发重新测量，不会重组输入栏。
        val h = insets.getBottom(this).coerceIn(0, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    }

/**
 * 相册选择器返回的 content:// Uri 取显示名，并保证带扩展名。
 * 缺扩展名会被后端按「不支持的文件格式」拒绝，也会让闪照/图片判定失效。
 */
private fun resolvePickerFileName(context: Context, uri: Uri, mime: String): String {
    var raw: String? = null
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) raw = c.getString(idx)
        }
    }
    return ensureMediaNameWithExt(raw ?: uri.lastPathSegment ?: "media", mime)
}
