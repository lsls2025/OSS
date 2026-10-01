package com.aurora.chat

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
import androidx.compose.material3.Icon
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
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        try {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val streamUris = when (action) {
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
            sb.append("用户通过系统分享发来了文件：$names。这些文件已保存到工作区 share_inbox/ 目录下，请查看并处理。")
        }
        return sb.toString()
    }
}

// ==================== 颜色常量（与项目现有 UI 对齐） ====================

private val UiTextPrimary = Color(0xFF1F2937)
private val UiTextSecondary = Color(0xFF6B7280)
private val UiTextHint = Color(0xFF9CA3AF)
private val UiBgCard = Color.White
private val UiBgSearch = Color(0xFFF3F4F6)
private val UiBgIcon = Color(0xFFEEF2FF)
private val UiAccent = Color(0xFF1E40AF)
private val UiDivider = Color(0xFFF3F4F6)

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

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .background(UiBgCard, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            when (step) {
                0 -> MainMenu(payload, onSave = {
                    // 保存到工作区：拷贝到 ai_files/share_inbox/
                    scope.launch(Dispatchers.IO) {
                        val dir = ShareReceiveHandler.saveToWorkspace(ctx, payload)
                        withContext(Dispatchers.Main) {
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

                2 -> FriendPickerStep(payload, onBack = { step = 0 }, onDismiss = onDismiss)
            }
        }
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
        TextButton(onClick = onDismiss) {
            Text("取消", color = UiTextSecondary, fontSize = 14.sp)
        }
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

@Composable
private fun FriendPickerStep(
    payload: ShareReceiveHandler.SharePayload,
    onBack: () -> Unit,
    onDismiss: () -> Unit
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
        scope.launch {
            val msg = ShareReceiveHandler.buildFriendMessage(payload)
            // 群消息复用负会话 id 约定：后端内部 -(id+1000) 还原群 id
            val result = if (conv.id < 0) {
                ChatRepository.sendGroupMessage(conv.id, msg)
            } else {
                ChatRepository.sendMessage(conv.id, msg)
            }
            if (result.success) {
                Toast.makeText(ctx, "已分享给 ${conv.username}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "分享失败: ${result.message}", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
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
                    .background(if (isSelected) UiAccent else UiBgSearch)
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
        TextButton(onClick = onBack) {
            Text("返回", color = UiTextSecondary, fontSize = 14.sp)
        }
    }
}
