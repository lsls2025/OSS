package com.aurora.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.chat.AiChatManager
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 系统分享接收器（增量式新增，不影响既有流程）。
 *
 * 其他 App 通过系统分享菜单（ACTION_SEND / ACTION_SEND_MULTIPLE）选择 Aurora Chat 后：
 *  1. handleIntent 解析 EXTRA_TEXT / EXTRA_STREAM，将外部 Uri 落盘到应用内收件箱 filesDir/share_inbox/；
 *  2. Compose 层读取 pendingShare 弹出三选项对话框（保存到工作区 / 发给 AI / 发给好友）；
 *  3. 解析失败一律静默忽略，不干扰原 onCreate / onNewIntent 流程。
 *
 * 挂接方式与 OpenAuthManager 相同：MainActivity.onCreate 冷启动 + onNewIntent 热启动双路调用。
 */
object ShareReceiveHandler {

    /** 一次分享的载荷：文本 + 已落盘的收件箱文件 */
    data class SharePayload(
        val text: String,
        val files: List<File>
    ) {
        val summary: String
            get() = buildString {
                if (text.isNotBlank()) {
                    append(text.replace("\n", " ").trim().take(60))
                }
                if (files.isNotEmpty()) {
                    if (isNotEmpty()) append(" ｜ ")
                    append(files.joinToString("、") { it.name })
                }
            }
    }

    /** 待处理分享（Composable 读取触发弹窗）；null 表示无待处理 */
    val pendingShare = mutableStateOf<SharePayload?>(null)

    private var lastHandleKey = ""

    /** 解析系统分享 intent；非分享/解析失败时静默忽略。冷/热启动均可调用，内部去重。 */
    fun handleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE && action != Intent.ACTION_VIEW) return
        try {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val streamUris = when (action) {
                // "用其他应用打开"（ACTION_VIEW）：把 intent.data 当作单一待接收文件
                Intent.ACTION_VIEW -> {
                    val u = intent.data
                    if (u != null && (u.scheme == "content" || u.scheme == "file")) listOf(u) else emptyList()
                }
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (u != null) listOf(u) else emptyList()
                }
                else -> {
                    @Suppress("DEPRECATION")
                    val l = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    l?.toList() ?: emptyList()
                }
            }
            // 去重：同一 intent 冷/热双路到达时不重复弹窗
            val key = action + "|" + text + "|" + streamUris.joinToString { it.toString() }
            if (key == lastHandleKey) return
            lastHandleKey = key

            val context = AuroraChatApplication.instance
            val saved = streamUris.mapNotNull { uri ->
                runCatching { saveStreamToInbox(context, uri) }.getOrNull()
            }
            if (text.isBlank() && saved.isEmpty()) return // 无可分享内容，静默忽略
            pendingShare.value = SharePayload(text.trim(), saved)
        } catch (_: Exception) {
            // 解析失败静默忽略，不影响原流程
            pendingShare.value = null
        }
    }

    fun dismiss() {
        pendingShare.value = null
        // 重置去重锚点，避免同一内容（如再次分享同一文件）二次分享被静默吞掉
        lastHandleKey = ""
    }

    // ==================== 收件箱（临时接收） ====================

    /** 收件箱目录：应用私有 filesDir/share_inbox，外部 Uri 先落到这里，避免分享源释放后无法读取 */
    private fun inboxDir(ctx: Context): File =
        File(ctx.filesDir, "share_inbox").apply { if (!exists()) mkdirs() }

    private fun saveStreamToInbox(context: Context, uri: Uri): File? {
        val resolver = context.contentResolver
        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: ("share_" + System.currentTimeMillis())
        val out = File(inboxDir(context), sanitizeFileName(name))
        val input = resolver.openInputStream(uri) ?: return null
        input.use { ins -> out.outputStream().use { ous -> ins.copyTo(ous) } }
        return out
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    // ==================== 动作一：保存到工作区 ====================

    /**
     * 将分享内容保存到 AI 工作区（复用现有工作区目录机制：filesDir/ai_files，见 AiChatManager.agentRoot）。
     * 文本写入 share_inbox/分享文本_时间戳.txt，文件拷贝到 share_inbox/ 下（重名自动加 (n)）。
     * @return 保存后的工作区 share_inbox 目录；异常时返回 null
     */
    fun saveToWorkspace(ctx: Context, payload: SharePayload): File? = try {
        val dir = File(AiChatManager.agentRoot(ctx), "share_inbox").apply { if (!exists()) mkdirs() }
        payload.files.forEach { f ->
            val target = uniqueFile(dir, f.name)
            f.copyTo(target, overwrite = false)
        }
        if (payload.text.isNotBlank()) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            uniqueFile(dir, "分享文本_$ts.txt").writeText(payload.text)
        }
        dir
    } catch (_: Exception) {
        null
    }

    /**
     * 带真实进度回调的保存到工作区：流式逐块拷贝，按已拷贝字节 / 总字节回报 onProgress(0..1)。
     * 大文件保存期间过渡弹窗可显示真实百分比，而非摆设。
     */
    fun saveToWorkspaceWithProgress(ctx: Context, payload: SharePayload, onProgress: (Float) -> Unit): File? = try {
        val dir = File(AiChatManager.agentRoot(ctx), "share_inbox").apply { if (!exists()) mkdirs() }
        val textBytes = payload.text.toByteArray()
        val totalBytes = payload.files.sumOf { it.length() } + textBytes.size.toLong()
        var done = 0L
        fun report() {
            onProgress(if (totalBytes > 0) (done.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f)
        }
        payload.files.forEach { f ->
            val target = uniqueFile(dir, f.name)
            f.inputStream().use { ins ->
                target.outputStream().use { ous ->
                    val buf = ByteArray(64 * 1024)
                    var n = ins.read(buf)
                    while (n >= 0) {
                        if (n > 0) {
                            ous.write(buf, 0, n)
                            done += n
                            report()
                        }
                        n = ins.read(buf)
                    }
                }
            }
        }
        if (payload.text.isNotBlank()) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            uniqueFile(dir, "分享文本_$ts.txt").writeBytes(textBytes)
            done += textBytes.size
            report()
        }
        dir
    } catch (_: Exception) {
        null
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "${base}($i)$ext")
            i++
        }
        return f
    }

    // ==================== 动作三：发给好友的消息构造 ====================

    /**
     * 构造"分享文件"站内卡片文本（与"分享帖子/分享资源/分享群聊"同协议，ShareCard 可识别渲染）。
     * 文件保存在发送方本地工作区 ai_files/share_inbox/，卡片携带 file_id/file_path 供接收方点击查看。
     */
    fun buildFriendMessage(payload: SharePayload): String {
        val sender = AuroraApi.currentUserName.ifBlank { "我" }
        val fileNames = payload.files.joinToString("、") { it.name }
        val preview = buildString {
            if (payload.files.isNotEmpty()) {
                append("文件：").append(fileNames)
                if (payload.text.isNotBlank()) append(" ｜ ").append(payload.text.replace("\n", " ").trim().take(60))
            } else {
                append(payload.text.replace("\n", " ").trim().take(120))
            }
        }
        val fileId = System.currentTimeMillis().toString()
        val firstPath = payload.files.firstOrNull()?.let { "share_inbox/${it.name}" } ?: ""
        return buildString {
            append("分享文件\n")
            append("━━━━━━━━━━\n")
            append(if (payload.files.isNotEmpty()) fileNames else "文件分享").append('\n')
            if (preview.isNotBlank()) append(preview).append('\n')
            append("━━━━━━━━━━\n")
            append("来自 $sender\n")
            append("file_id=$fileId\n")
            if (firstPath.isNotEmpty()) append("file_path=$firstPath")
        }.trimEnd()
    }

    // ==================== 动作二：发给 AI 的提示词构造 ====================

    /** 文件已在保存到工作区时调用，让 AI 能通过工作区工具读取 */
    fun buildAiPrompt(payload: SharePayload): String {
        val sb = StringBuilder()
        if (payload.text.isNotBlank()) sb.append(payload.text)
        if (payload.files.isNotEmpty()) {
            val names = payload.files.joinToString("、") { it.name }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("用户通过系统分享发来了文件：$names。")
        }
        return sb.toString()
    }
}

// ==================== 颜色常量（与项目现有 UI 对齐） ====================

private val UiTextPrimary = com.aurora.chat.ui.theme.appTextPrimary()
private val UiTextSecondary = com.aurora.chat.ui.theme.appTextSecondary()
private val UiTextHint = com.aurora.chat.ui.theme.appTextHint()
private val UiBgCard get() = com.aurora.chat.ui.theme.appCardBg()
private val UiBgSearch = com.aurora.chat.ui.theme.appCardBg()
private val UiBgIcon = Color(0xFFEEF2FF)
private val UiAccent = Color(0xFF1E40AF)
private val UiDivider = com.aurora.chat.ui.theme.appCardBg()

/**
 * 系统分享三选项对话框。
 * 使用 Dialog 默认 scrim（单一遮罩），内容为居中白色圆角卡片；样式对齐项目 SharePostDialog：
 * 深色标题、灰底搜索框、蓝白胶囊切换、浅蓝按钮、浅灰分割线。
 * 步骤：0=主菜单（保存到工作区 / 发给 AI / 发给好友），1=AI 处理中/结果，2=好友/群聊选择。
 */
@Composable
fun ShareReceiveDialog(
    payload: ShareReceiveHandler.SharePayload,
    onDismiss: () -> Unit,
    onSendAiToChat: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    // 过渡提示：保存到工作区 / 发给好友期间置非 null，无论文件大小都提示（大文件等待感更明显）
    var busyText by remember { mutableStateOf<String?>(null) }
    var busyProgress by remember { mutableFloatStateOf(0f) }

    val content: @Composable () -> Unit = {
        if (busyText != null) {
            BusyStep(busyText ?: "处理中…", busyProgress)
        } else {
            when (step) {
                0 -> MainMenu(payload, onSave = {
                    // 保存到工作区：流式拷贝带真实进度（大文件也先弹过渡提示，进度条+百分比实时刷新）
                    busyText = "正在保存到工作区…"
                    busyProgress = 0f
                    scope.launch(Dispatchers.IO) {
                        val dir = ShareReceiveHandler.saveToWorkspaceWithProgress(ctx, payload) { p ->
                            busyProgress = p
                        }
                        withContext(Dispatchers.Main) {
                            busyText = null
                            busyProgress = 0f
                            if (dir != null) {
                                Toast.makeText(ctx, "已保存到工作区：${dir.absolutePath}", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(ctx, "保存失败", Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        }
                    }
                }, onSendAi = onSendAiToChat, onSendFriend = {
                    step = 2
                }, onDismiss = onDismiss)

                2 -> FriendPickerStep(
                    payload,
                    onBack = { step = 0 },
                    onDismiss = onDismiss,
                    busyText = busyText,
                    onBusyChange = { t -> busyText = t },
                    busyProgress = busyProgress,
                    onProgressChange = { p -> busyProgress = p }
                )
            }
        }
    }

        Dialog(onDismissRequest = onDismiss) {
            Column(
                Modifier
                    .fillMaxWidth(0.88f)
                    .background(UiBgCard, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) { content() }
        }
    }

// ==================== 主菜单 ====================

@Composable
private fun MainMenu(
    payload: ShareReceiveHandler.SharePayload,
    onSave: () -> Unit,
    onSendAi: () -> Unit,
    onSendFriend: () -> Unit,
    onDismiss: () -> Unit
) {
    Text(
        "收到分享",
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = UiTextPrimary
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (payload.summary.isNotBlank()) payload.summary else "来自其他应用的内容",
        fontSize = 13.sp,
        color = UiTextSecondary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(18.dp))

    MenuItem(Icons.Outlined.Save, "保存到工作区", "写入 AI 工作区 ai_files/share_inbox/", onSave)
    Spacer(Modifier.height(10.dp))
    MenuItem(Icons.Outlined.SmartToy, "发给 AI", "跳转 AI 对话，预挂到输入区后发送", onSendAi)
    Spacer(Modifier.height(10.dp))
    MenuItem(Icons.AutoMirrored.Outlined.Send, "发给好友", "选择好友或群聊发送", onSendFriend)

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text("取消", color = UiTextSecondary) }
    }
}

/** 统一菜单项：浅灰圆角底 + 统一浅蓝图标块 + 标题/副标题 + 右侧箭头，三个选项视觉完全一致 */
@Composable
private fun MenuItem(icon: ImageVector, label: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(UiBgSearch)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(UiBgIcon),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = UiAccent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = UiTextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = UiTextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFFD1D5DB)
        )
    }
}

// ==================== 好友/群聊选择页（样式对齐 SharePostDialog） ====================

/**
 * 分享出去但没发成功的消息 → 记到目标会话的失败表。
 *
 * 会话界面进入时会从 FailedMessageStore 还原失败气泡（左侧带红叹号），
 * 所以用户在目标会话里能看到"这条没发出去"，并点叹号重发 ——
 * 与在会话里直接发送失败的表现完全一致，不会再被静默吞掉。
 */
private fun recordShareFailure(
    ctx: Context,
    friendId: Long,
    text: String,
    mediaType: String,
    mediaUrl: String
) {
    try {
        val localId = -(System.currentTimeMillis() * 1000L + (0..999).random())
        val createdAt = System.currentTimeMillis() / 1000
        // 上传就失败时没有资源地址，气泡不能是空文件卡：用类型占位文案兜底
        val shownText = if (text.isNotEmpty()) text else when (mediaType) {
            "image" -> "[图片]"; "video" -> "[视频]"; "voice" -> "[语音]"
            "gif" -> "[动图]"; "file" -> "[文件]"; else -> ""
        }
        com.aurora.chat.data.local.FailedMessageStore.save(
            ctx,
            com.aurora.chat.data.local.FailedMessage(
                id = localId, friendId = friendId, text = shownText,
                mediaType = mediaType, mediaUrl = mediaUrl, createdAt = createdAt
            )
        )
    } catch (_: Exception) {}
}

@Composable
private fun FriendPickerStep(
    payload: ShareReceiveHandler.SharePayload,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    busyText: String?,
    onBusyChange: (String?) -> Unit,
    busyProgress: Float,
    onProgressChange: (Float) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0=好友 1=群聊
    var conversations by remember { mutableStateOf<List<ConversationInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val result = ChatRepository.getConversations()
        if (result.success && result.data != null) {
            conversations = result.data!!
        }
        isLoading = false
    }

    val filteredList = remember(conversations, searchText, selectedTab) {
        conversations.distinctBy { it.id }.filter { conv ->
            val matchTab = if (selectedTab == 0) conv.id > 0 else conv.id < 0
            val matchSearch = searchText.isBlank() ||
                    conv.username.contains(searchText, ignoreCase = true) ||
                    conv.lastMessage.contains(searchText, ignoreCase = true)
            matchTab && matchSearch
        }
    }

    fun sendTo(conv: ConversationInfo) {
        // 官方群限制（复用 SharePostDialog 规则）：群名含"官方"的非开发者禁止分享
        val isOfficialGroup = conv.id < 0 && conv.username.contains("官方", ignoreCase = true)
        val prefs = ctx.getSharedPreferences("aurora_login", Context.MODE_PRIVATE)
        val userQQ = prefs.getString("user_qq", "") ?: ""
        val userEmail = prefs.getString("user_email", "") ?: ""
        val isDeveloper = LocalStorage.isDeveloper(userQQ, userEmail)
        if (isOfficialGroup && !isDeveloper) {
            Toast.makeText(ctx, "官方群禁止分享", Toast.LENGTH_SHORT).show()
            return
        }
        // 过渡提示：无论文件大小，进入发送处理即展示（大文件等待感更明显）
        onBusyChange("正在发送给 ${conv.username}…")
        onProgressChange(0f)
        scope.launch(Dispatchers.IO) {
            var allOk = true
            var firstError = ""

            // ① 文本消息先发送（如果有）—— 纯文本，不带媒体
            if (payload.text.isNotBlank()) {
                val r = if (conv.id < 0) ChatRepository.sendGroupMessage(conv.id, payload.text)
                        else ChatRepository.sendMessage(conv.id, payload.text)
                if (!r.success) {
                    allOk = false
                    if (firstError.isEmpty()) firstError = r.message
                    // 失败留痕：目标会话里出现带红叹号的失败气泡，可点叹号重发
                    recordShareFailure(ctx, conv.id, payload.text, "", "")
                }
            }

            // ② 每个文件：上传服务器 → 发真正的 media 文件消息（mediaType=file/image/video，mediaUrl=上传地址）
            // 流式上传带进度回调，大文件不再"无提示卡住"
            val totalBytes = payload.files.sumOf { it.length() }
            var doneBytes = 0L
            for (file in payload.files) {
                val mimeType = com.aurora.chat.ui.chat.guessMediaMimeType(file.name)
                val extraType = when {
                    mimeType.startsWith("image/") -> "image"
                    mimeType.startsWith("video/") -> "video"
                    else -> "file"
                }
                // 对齐 MediaUploadManager：上传套 90s 超时，避免弱网/服务端响应慢时永久卡在 99%~100%
                val up = withTimeoutOrNull(90_000L) {
                    AuroraApi.uploadChatMediaFile(
                        file, file.name, mimeType,
                        onProgress = { p ->
                            val ratio = if (totalBytes > 0) (doneBytes + file.length() * p) / totalBytes.toDouble() else 0.0
                            onProgressChange(ratio.toFloat().coerceIn(0f, 1f))
                        }
                    )
                } ?: com.aurora.chat.data.api.ApiResult(false, "上传超时（网络较慢或服务器未响应）")
                doneBytes += file.length()
                if (!up.success || up.data == null) {
                    allOk = false
                    if (firstError.isEmpty()) firstError = up.message.ifBlank { "上传失败: ${file.name}" }
                    // 上传就失败：连服务器地址都没有，只能留下失败痕迹（重发时需要重新上传原文件）
                    recordShareFailure(ctx, conv.id, "", extraType, "")
                    continue
                }
                val url = up.data!!.optString("url")
                val r = if (conv.id < 0) ChatRepository.sendGroupMessage(conv.id, "", mediaType = extraType, mediaUrl = url)
                        else ChatRepository.sendMessage(conv.id, "", mediaType = extraType, mediaUrl = url)
                if (!r.success) {
                    allOk = false
                    if (firstError.isEmpty()) firstError = r.message
                    // 文件已上传成功、只是消息没发出去：带着地址留痕，重发时可直接复用
                    recordShareFailure(ctx, conv.id, "", extraType, url)
                }
            }

            withContext(Dispatchers.Main) {
                try {
                    if (allOk) Toast.makeText(ctx, "已分享给 ${conv.username}", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(ctx, "部分失败: $firstError", Toast.LENGTH_SHORT).show()
                    // 分享出去的这条消息在目标会话里没有本地气泡（客户端不是从会话页发的），
                    // 发送成功后立刻做一次对齐：目标会话的预览立即更新，
                    // 正在打开的那个会话也会增量补拉，不必等 15 秒兜底轮询或重进。
                    // 二者任一抛异常都不能影响「关闭过渡弹窗」，否则对话框会永远停在 99%/100%。
                    runCatching { com.aurora.chat.ui.viewmodel.ChatViewModel.notifyNewMessage() }
                    runCatching { com.aurora.chat.ui.viewmodel.ChatViewModel.loadConversations(ctx, showLoading = false, force = true) }
                } finally {
                    // 无论成功/失败/收尾异常，过渡弹窗必须关闭，否则会永久卡在「99%/100%」
                    onBusyChange(null)
                    onProgressChange(0f)
                    onDismiss()
                }
            }
        }
    }

    Text(
        "发给好友",
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = UiTextPrimary
    )
    Spacer(Modifier.height(4.dp))
    Text(
        if (payload.summary.isNotBlank()) "分享内容：${payload.summary}" else "选择要发送的好友或群聊",
        fontSize = 12.sp,
        color = UiTextHint,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(12.dp))

    // 搜索输入框（与 SharePostDialog 一致）
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(UiBgSearch)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = searchText,
            onValueChange = { searchText = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = UiTextPrimary),
            cursorBrush = SolidColor(UiAccent),
            decorationBox = { innerTextField ->
                if (searchText.isBlank()) {
                    Text("搜索好友或群聊", fontSize = 14.sp, color = UiTextHint)
                }
                innerTextField()
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(10.dp))

    // 好友/群聊切换胶囊（与 SharePostDialog 一致）
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("好友" to 0, "群聊" to 1).forEach { (label, index) ->
            val isSelected = selectedTab == index
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isSelected) UiAccent else UiBgSearch
                    )
                    .clickable { selectedTab = index }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color.White else UiTextSecondary
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    // 数量提示
    Text(
        "${filteredList.size} 个${if (selectedTab == 0) "好友" else "群聊"}",
        fontSize = 12.sp,
        color = UiTextHint
    )
    Spacer(Modifier.height(4.dp))

    // 会话列表
    Box(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", fontSize = 14.sp, color = UiTextHint)
            }
            filteredList.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchText.isNotEmpty()) "未找到匹配的${if (selectedTab == 0) "好友" else "群聊"}"
                    else "暂无${if (selectedTab == 0) "好友" else "群聊"}",
                    fontSize = 14.sp,
                    color = UiTextHint
                )
            }
            else -> LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filteredList, key = { it.id }) { conv ->
                    val internalGroupId = if (conv.id < 0) -(conv.id + 1000) else null
                    val isGroup = conv.id < 0
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 头像
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            if (isGroup && internalGroupId != null) {
                                GroupAvatar(
                                    internalGroupId = internalGroupId,
                                    groupName = conv.username,
                                    size = 44.dp
                                )
                            } else {
                                UserAvatar(
                                    userId = conv.id,
                                    userName = conv.username,
                                    size = 44.dp
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        // 名称
                        Column(Modifier.weight(1f)) {
                            Text(
                                conv.username,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = UiTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isGroup) {
                                Text("群聊", fontSize = 11.sp, color = UiTextHint)
                            }
                        }
                        // 分享按钮（与 SharePostDialog 一致）
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(UiBgIcon)
                                .clickable { sendTo(conv) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "分享",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = UiAccent
                            )
                        }
                    }
                    // 分割线
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(UiDivider))
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onBack) { Text("返回", color = UiTextSecondary) }
    }
}

// ==================== 过渡提示（处理中） ====================

/**
 * 分享处理中的过渡步骤：保存到工作区 / 发给好友期间展示，无论文件大小都提示，
 * 大文件拷贝与上传不再表现为"无提示卡住"。progress>0 时显示确定进度条，否则显示循环动画。
 */
@Composable
private fun BusyStep(text: String, progress: Float) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (progress > 0f) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = UiTextPrimary)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { progress },
                color = UiAccent,
                trackColor = com.aurora.chat.ui.theme.appDivider(),
                modifier = Modifier.fillMaxWidth(0.8f).height(6.dp).clip(RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.height(6.dp))
            Text("${(progress * 100).toInt()}%", fontSize = 12.sp, color = UiTextHint)
        } else {
            CircularProgressIndicator(
                color = UiAccent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = UiTextPrimary)
        }
    }
}
