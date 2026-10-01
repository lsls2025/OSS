package com.aurora.chat.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.chat.Tuple4
import com.aurora.chat.ui.chat.isImageFile

@Composable
fun InputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    friendId: Long = 0,
    modifier: Modifier = Modifier,
    onSendMedia: (Uri, Int, String) -> Unit = { _, _, _ -> },
    showPlusDialog: Boolean = false,
    onTogglePlus: () -> Unit = {},
    onClosePlus: () -> Unit = {},
    onOpenCamera: () -> Unit = {},
    onRequestLocation: () -> Unit = {},
    onSecretClick: () -> Unit = {},
    onTransfer: () -> Unit = {},
    onRedPacket: () -> Unit = {},
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
    // AI 对话：当前 AI 模式 + 模式选择入口（仅 third_party / personal 显示）
    aiMode: String = "official",
    onModeSelect: () -> Unit = {},
    // AI 对话：是否正在生成回复（真则发送按钮切换为「停止」）
    aiInFlight: Boolean = false,
    onStopSend: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // 已选择的文件列表
    var selectedFiles by remember { mutableStateOf<List<Triple<Uri, String, Long>>>(emptyList()) } // (uri, name, size)

    // 是否显示媒体选择完成弹窗（带发送按钮）
    var showMediaConfirmDialog by remember { mutableStateOf(false) }
    // 闪照配置：index -> 秒数（0/不存在 = 普通照片）
    var flashSettings by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    // 图片/视频选择器（支持多选，最多 10 个）
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10) // 最多选 10 个
    ) { uris ->
        if (uris.isEmpty()) {
            return@rememberLauncherForActivityResult
        }
        val room = 10 - selectedFiles.size
        if (room <= 0) {
            android.widget.Toast.makeText(context, "最多同时发送 10 个文件", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val limited = uris.take(room)
        if (uris.size > room) android.widget.Toast.makeText(context, "最多选择 10 个", android.widget.Toast.LENGTH_SHORT).show()
        val selectedList = mutableListOf<Triple<Uri, String, Long>>()
        for (uri in limited) {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "Unknown"
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    selectedList.add(Triple(uri, name, size))
                }
            } ?: run {
                val name = uri.lastPathSegment ?: "Unknown"
                selectedList.add(Triple(uri, name, 0L))
            }
        }
        selectedFiles = selectedFiles + selectedList
        onClosePlus()
        showMediaConfirmDialog = true
    }

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
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "Unknown"
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    selectedList.add(Triple(uri, name, size))
                }
            } ?: run {
                val name = uri.lastPathSegment ?: "Unknown"
                selectedList.add(Triple(uri, name, 0L))
            }
        }
        selectedFiles = selectedFiles + selectedList
        showMediaConfirmDialog = true
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
                } else if (friendId == -1001L) {
                    android.widget.Toast.makeText(context, "官方群暂不支持上传此类型文件", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 相册：与微信一致，图片和视频混合多选
                    selectedFiles = emptyList()
                    mediaPickerLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                        )
                    )
                }
            }
            "file" -> {
                if (friendId == -1001L) {
                    android.widget.Toast.makeText(context, "官方群暂不支持上传此类型文件", android.widget.Toast.LENGTH_SHORT).show()
                } else if (isAiChat) {
                    // AI 对话：不走普通聊天的「选完即发」，改由屏幕层暂存到输入框上方，随发送按钮一起发
                    onPickAiFile()
                } else {
                    selectedFiles = emptyList()
                    filePickerLauncher.launch(arrayOf("*/*"))
                }
            }
            "voice" -> { /* TODO: 语音消息 */ }
            "red_packet" -> {
                onRedPacket()
            }
            "transfer" -> { onTransfer() }
            "broadcast" -> { onBroadcast() }
            "camera" -> { onOpenCamera() }
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
            "mode_select" -> { onModeSelect() }
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF9FAFB))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // 键盘控制器
        val keyboardController = LocalSoftwareKeyboardController.current
        // + 按钮旋转动画
        val plusRotation = androidx.compose.animation.core.animateFloatAsState(
            if (showPlusDialog) 45f else 0f,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
            label = "plusRotation"
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                // PlusButton (circle with +) - same style as AuroraTopBar
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(
                            enabled = !isMuted,
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) { 
                            onTogglePlus()
                        }
                        .then(if (isMuted) Modifier.alpha(0.3f) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(32.dp).graphicsLayer { rotationZ = plusRotation.value }) {
                        val c = center
                        val r = size.width / 2f - 1.dp.toPx()
                        drawCircle(Color.Black, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
                        val len = r * 0.5f
                        drawLine(Color.Black, Offset(c.x - len, c.y), Offset(c.x + len, c.y), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(Color.Black, Offset(c.x, c.y - len), Offset(c.x, c.y + len), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val borderColor by androidx.compose.animation.animateColorAsState(
                    targetValue = if (isFocused) Color(0xFF1E40AF) else Color(0xFFD1D5DB),
                    animationSpec = androidx.compose.animation.core.tween(200)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isMuted) Color(0xFFF3F4F6) else Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, if (isMuted) Color(0xFFFDE68A) else borderColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 0.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = if (isMuted) {{}} else onInputChange,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 120.dp),
                        singleLine = false, maxLines = 5,
                        readOnly = isMuted,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = if (isMuted) Color(0xFF9CA3AF) else Color(0xFF1F2937)),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(if (isMuted) Color.Transparent else Color(0xFF1E40AF)),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (inputText.isEmpty()) {
                                    Text(if (isMuted) "你已被禁言" else "输入消息...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                }
                                innerTextField()
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // AI 确认发言按钮
                if (isAiChat && confirmSpeechEnabled) {
                    Box(
                        modifier = Modifier
                            .height(36.dp).widthIn(min = 68.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF7C3AED))
                            .clickable(enabled = !isMuted) { onConfirmReply() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("确认发言", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .height(36.dp).widthIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                isMuted -> Color(0xFFD1D5DB)
                                aiInFlight -> Color(0xFFEF4444)
                                else -> Color(0xFF1E40AF)
                            }
                        )
                        .clickable(enabled = !isMuted) {
                            if (aiInFlight) onStopSend?.invoke() else onSend()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            isMuted -> "禁言中"
                            aiInFlight -> "停止"
                            else -> "发送"
                        },
                        fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White
                    )
                }
            }
            // Plus菜单区域：固定 300px 滑入距离（不受面板高度影响），配合 graphicsLayer 硬件加速
            val plusAnimAlpha by animateFloatAsState(
                targetValue = if (showPlusDialog) 1f else 0f,
                animationSpec = tween(durationMillis = 250)
            )
            val plusAnimOffset by animateFloatAsState(
                targetValue = if (showPlusDialog) 0f else 1f,
                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
            )
            if (plusAnimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(
                            alpha = plusAnimAlpha,
                            translationY = plusAnimOffset * 300f
                        )
                ) {
                    PlusDialogContent(friendId = friendId, context = context, onAction = handleDialogAction, isAiChat = isAiChat, aiMode = aiMode, onModeSelect = onModeSelect)
                }
            }
        }
    }

    // 媒体选择确认弹窗（大弹窗，每行左侧预览 + 右侧 X 删除）
    if (showMediaConfirmDialog && selectedFiles.isNotEmpty()) {
        Dialog(onDismissRequest = { showMediaConfirmDialog = false }) {
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
                        .background(Color.White)
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
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)
                            )
                            IconButton(onClick = { showMediaConfirmDialog = false }) {
                                Icon(Icons.Filled.Close, "关闭", tint = Color(0xFF9CA3AF))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

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
                                        .background(Color(0xFFF3F4F6))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFFE5E7EB))
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
                                                        else Color(0xFFE5E7EB)
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
                                                Text("闪照", fontSize = 13.sp, color = Color(0xFF6B7280),
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

                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
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
                                    .background(Color(0xFFF3F4F6))
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
                                            onSendMedia(file.first, flashSettings[idx] ?: 0, file.second)
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

@Composable
fun PlusDialogContent(friendId: Long, context: android.content.Context, onAction: (String) -> Unit, isAiChat: Boolean = false, aiMode: String = "official", onModeSelect: () -> Unit = {}) {
    val isOfficialGroup = friendId == -1001L
    val isPrivateChat = friendId > 0
    val accentColor = Color(0xFF1E40AF)

    val allButtons = if (isAiChat) {
        // 模式选择（智能/图片/视频/对话）：仅 个人 API 显示（第三方模式改为 AI 自动判断并调用 generate_media 工具生成，官方 DeepSeek 无多模态）
        val isGenMode = aiMode == "personal"
        val base = listOf(
            Tuple4("相册", "image", Icons.Default.Photo, Color.Transparent),
            Tuple4("文件", "file", Icons.Default.Description, Color.Transparent),
            Tuple4("时间点", "time_point", Icons.Default.Schedule, Color.Transparent),
            Tuple4("确认发言", "confirm_speech", Icons.Default.CheckCircle, Color.Transparent)
        )
        if (isGenMode) base + Tuple4("模式选择", "mode_select", Icons.Default.Tune, Color.Transparent) else base
    } else if (isLocalChat(friendId)) {
        // 自己对话：提供与私聊相同的功能（本地保存）
        listOf(
            Tuple4("相册", "image", Icons.Default.Photo, Color.Transparent),
            Tuple4("拍摄", "camera", CameraIcon, Color.Transparent),
            Tuple4("文件", "file", Icons.Default.Description, Color.Transparent),
            Tuple4("红包", "red_packet", Icons.Default.Redeem, Color.Transparent),
            Tuple4("转账", "transfer", Icons.Default.CallMade, Color.Transparent),
            Tuple4("秘密", "secret", Icons.Default.Lock, Color.Transparent),
            Tuple4("位置", "location", Icons.Default.LocationOn, Color.Transparent)
        )
    } else if (isPrivateChat) {
        listOf(
            Tuple4("相册", "image", Icons.Default.Photo, Color.Transparent),
            Tuple4("拍摄", "camera", CameraIcon, Color.Transparent),
            Tuple4("文件", "file", Icons.Default.Description, Color.Transparent),
            Tuple4("红包", "red_packet", Icons.Default.Redeem, Color.Transparent),
            Tuple4("转账", "transfer", Icons.Default.CallMade, Color.Transparent),
            Tuple4("秘密", "secret", Icons.Default.Lock, Color.Transparent),
            Tuple4("位置", "location", Icons.Default.LocationOn, Color.Transparent)
        )
    } else {
        listOf(
            Tuple4("相册", "image", Icons.Default.Photo, Color.Transparent),
            Tuple4("拍摄", "camera", CameraIcon, Color.Transparent),
            Tuple4("文件", "file", Icons.Default.Description, Color.Transparent),
            Tuple4("红包", "red_packet", Icons.Default.Redeem, Color.Transparent),
            Tuple4("转账", "transfer", Icons.Default.CallMade, Color.Transparent)
        )
    }

    val loginPrefs2 = context.getSharedPreferences("aurora_login", android.content.Context.MODE_PRIVATE)
    val currentQQ = loginPrefs2.getString("user_qq", "") ?: ""
    val currentEmail = loginPrefs2.getString("user_email", "") ?: ""
    val isDeveloper2 = currentQQ == "YOUR_DEVELOPER_QQ" || currentEmail.equals("YOUR_DEVELOPER_EMAIL", ignoreCase = true)
    // 开发者专属「广播」入口：仅主开发者（ID=1）可见，AI 对话中不展示。
    // 注意：客户端仅做显示隐藏，真正的越权防护必须由后端在接口内校验 userId==1。
    val showBroadcast = AuroraApi.currentUserId == 1L && !isAiChat
    val buttons = if (showBroadcast) {
        allButtons + Tuple4("广播", "broadcast", Icons.Filled.Campaign, Color.Transparent)
    } else {
        allButtons
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color.White),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (isPrivateChat) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                buttons.take(4).forEach { (label, actionId, icon, _) ->
                    PlusDialogButton(label = label, icon = icon, accentColor = accentColor, action = actionId, onAction = onAction)
                }
            }
            if (buttons.size > 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    buttons.drop(4).forEach { (label, actionId, icon, _) ->
                        PlusDialogButton(label = label, icon = icon, accentColor = accentColor, action = actionId, onAction = onAction)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                buttons.take(4).forEach { (label, actionId, icon, _) ->
                    PlusDialogButton(label = label, icon = icon, accentColor = accentColor, action = actionId, onAction = onAction)
                }
            }
            if (buttons.size > 4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    buttons.drop(4).forEach { (label, actionId, icon, _) ->
                        PlusDialogButton(label = label, icon = icon, accentColor = accentColor, action = actionId, onAction = onAction)
                    }
                }
            }
        }
    }
}

@Composable
fun PlusDialogButton(label: String, icon: ImageVector, accentColor: Color, action: String, onAction: (String) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onAction(action) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, fontSize = 11.sp, color = Color(0xFF6B7280), fontWeight = FontWeight.Normal)
    }
}
