package com.aurora.chat.ui.chat

import com.aurora.chat.ui.server.SetDialogSystemBarColors
import com.aurora.chat.ui.profile.FreeApiSelectScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import com.aurora.chat.ui.chat.media.ChatMedia
import com.aurora.chat.ui.chat.media.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R
import com.aurora.chat.NotificationHelper
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.data.store.MessageData
import com.aurora.chat.data.store.MessageStoreAdapter
import com.aurora.chat.data.store.StoreMessageRef
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.AnimationMode
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.profile.CropPreviewDialog
import com.aurora.chat.ui.profile.OnlineTimeTracker
import com.aurora.chat.ui.components.UserAvatar
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.Log
import android.view.TextureView
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.ui.viewinterop.AndroidView
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.zIndex












/** 跨组合期持久化聊天数据（LRU 淘汰，最多保留 5 个对话 ≈ 750KB） */
private val companionMessageCache = object : LinkedHashMap<Long, List<IMessageRef>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<IMessageRef>>?): Boolean {
        return size > 5
    }
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
    val bytes: ByteArray? = null
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
)

/** 当前正在进行的 AI 回复（全局，跨组合期）。界面通过收集此流把回复实时同步进消息列表。 */
private val aiReplyState = kotlinx.coroutines.flow.MutableStateFlow<AiBackgroundReply?>(null)

/** 是否正在生成 AI 回复（控制发送按钮在「发送 ↔ 停止」间切换）。文件级 State，跨组合期稳定。 */
private val aiWriting = androidx.compose.runtime.mutableStateOf(false)

/**
 * 当前正在生成回复的【会话 id】（按会话隔离发送/停止按钮状态）。
 * 回复内容已用 AiBackgroundReply.convId 做代次隔离，但按钮状态此前是全局布尔 aiWriting，
 * 导致 A 会话生成中点进 B 会话仍显示「停止」、且点「停止」会误取消 A 的在途任务、B 永远无法发送。
 * 改为记录「哪个会话在生成」：仅当 aiWritingConvId == 当前会话时按钮才显示「停止」。
 * 值为 -1L 表示当前没有任何会话在生成。
 */
private var aiWritingConvId = -1L

/** 用户点击「停止」后的请求标记：网络读流据此提前退出，且后续 onDelta/onDone 被忽略。 */
private var aiStopRequested = false

/** 当前正在进行的 AI 流式协程句柄（供「停止」取消）。文件级，跨组合期稳定。 */
private var activeAiJob: kotlinx.coroutines.Job? = null

/** AI 会话代次标记：开启新对话（清空 AI）时自增。后台回复携带着创建时的代次，只渲染进同一个会话。 */
private var aiConvTag = 0L

/** 丢弃当前后台 AI 后台任务（清空对话时调用）：取消流式协程并把状态清理干净，防止旧回复污染新会话。 */
private fun clearActiveAiReply() {
    activeAiJob?.cancel()
    activeAiJob = null
    aiStopRequested = false
    aiWriting.value = false
    aiWritingConvId = -1L
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
            if (c.moveToFirst()) c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null
        }
    } catch (_: Exception) { uri.lastPathSegment }
}

/** 导入时占位符→人物名称 的映射项（占位符不可编辑，目标名可改，checked 控制是否应用） */
data class ImportMapItem(val placeholder: String, var target: String, var checked: Boolean)

// 好友（服务端）对话导入的本地兜底：服务端不会保存导入的历史，重载后会丢失，
// 故把导入消息单独存一份，加载好友对话时合并进来（不往服务端发送、不骚扰对方）。
private fun importedMessagesFile(ctx: android.content.Context, currentUserId: Long, friendId: Long): java.io.File =
    java.io.File(ctx.cacheDir, "imported_${friendId}_${currentUserId}.json")

private fun saveImportedMessages(ctx: android.content.Context, currentUserId: Long, friendId: Long, msgs: List<ChatMsg>) {
    try {
        val arr = org.json.JSONArray()
        for (m in msgs) {
            val obj = org.json.JSONObject()
            obj.put("id", m.id)
            obj.put("text", m.text)
            obj.put("is_mine", m.isMine)
            obj.put("from_user_id", m.fromUserId)
            obj.put("sender_name", m.senderName)
            obj.put("created_at", m.createdAt)
            obj.put("media_type", m.mediaType)
            obj.put("media_url", m.mediaUrl)
            obj.put("revoked", m.isRevoked)
            arr.put(obj)
        }
        importedMessagesFile(ctx, currentUserId, friendId).writeText(arr.toString())
    } catch (_: Exception) {}
}

private fun loadImportedMessages(ctx: android.content.Context, currentUserId: Long, friendId: Long): List<MessageData> {
    val f = importedMessagesFile(ctx, currentUserId, friendId)
    if (!f.exists()) return emptyList()
    return try {
        val arr = org.json.JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            MessageData(
                id = o.getLong("id"),
                fromUserId = o.optLong("from_user_id", 0),
                toUserId = currentUserId,
                content = o.optString("text", ""),
                createdAt = o.optLong("created_at", 0),
                isMine = o.optBoolean("is_mine", false),
                senderName = o.optString("sender_name", ""),
                isRevoked = o.optInt("revoked", 0) != 0,
                isSystemNotice = o.optInt("revoked", 0) == 1,
                mediaType = o.optString("media_type", ""),
                mediaUrl = o.optString("media_url", "")
            )
        }
    } catch (_: Exception) { emptyList() }
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
    var end = text.length
    while (end > 0) {
        val c = text[end - 1]
        if (c == ' ' || c == '\n' || c == '\t') break
        end--
    }
    return if (end < text.length && text[end] == '@') end else -1
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
                .background(Color.White)
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
                "选择成员", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
            // 成员名搜索框：输入可过滤名单
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF2F3F5))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color(0xFF9CA3AF),
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
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color(0xFF1F2937)),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box {
                                if (searchText.isEmpty()) Text("搜索成员名字", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                inner()
                            }
                        }
                    )
                }
                if (searchText.isNotEmpty()) {
                    Text("✕", fontSize = 14.sp, color = Color(0xFF9CA3AF),
                        modifier = Modifier.clickable(
                            onClick = { searchText = "" },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ).padding(4.dp))
                }
            }
            HorizontalDivider(color = Color(0xFFEEF0F4), thickness = 0.5.dp)

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
                            .background(Color(0xFFEAF1FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("@", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("@全体成员", fontSize = 15.sp, color = Color(0xFF1F2937), fontWeight = FontWeight.Medium)
                }
                HorizontalDivider(color = Color(0xFFEEF0F4), thickness = 0.5.dp)
            }

            // 成员名单（按首字母 A-Z 分组，其余归入 #）
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                sections.forEach { (letter, list) ->
                    item(key = "sec_$letter") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF7F8FA))
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                        ) {
                            Text(letter.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9CA3AF))
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
                            Text(m.name, fontSize = 15.sp, color = Color(0xFF1F2937), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.isOwner) {
                                Spacer(Modifier.width(6.dp))
                                Text("群主", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
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
    onOpenNewChat: (Long, String) -> Unit = { _, _ -> },
    onOpenAiSandbox: () -> Unit = {},
    onRechargeToken: () -> Unit = {},
    onTokenExhausted: () -> Unit = {},
    onAppAction: (suspend (AiChatManager.AppAction) -> AiChatManager.AppActionResult)? = null
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val ctx = LocalContext.current
    // 标记当前打开的会话（用于"正在查看的会话不计未读"），进入即清零未读
    LaunchedEffect(friendId) {
        com.aurora.chat.ui.viewmodel.ChatViewModel.openConversationId = friendId
        com.aurora.chat.ui.viewmodel.ChatViewModel.markRead(ctx, friendId)
    }
    DisposableEffect(friendId) {
        onDispose {
            com.aurora.chat.ui.viewmodel.ChatViewModel.openConversationId = 0L
            com.aurora.chat.ui.viewmodel.ChatViewModel.markRead(ctx, friendId)
        }
    }
    // 「人物对换 / 历史反转」：AI 视角反转（头像/对话左右互换，并让 AI 以我方身份续聊）。
    // 声明在顶层，确保对话列表、AI 发送、AI 设置等各嵌套块都能捕获到。
    var reverseHistory by remember(currentUserId) { mutableStateOf(LocalStorage.getReverseHistory(ctx, currentUserId)) }
    val draftKey = "draft_${currentUserId}_${friendId}"
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
    val atBottomState = remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.isEmpty() ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= BOTTOM_OFFSET_TOLERANCE_PX)
        }
    }
    // 用户是否主动离开过底部（意图锁存）：流式输出只在“用户仍在底部”时自动跟随。
    // 用户主动上滑后即停止跟随，即使 AI 内容增长把位置顶回末尾也不抢滚动；
    // 仅当用户再次主动滚动贴回到底部时才恢复自动跟随。
    // 自动跟随意图锁存:手指一碰屏幕立刻锁 true,只有用户自己手动贴底才解锁 false。
    // 旧方案用 isScrollInProgress 更新锁存——手指一抬就变 false(惯性也不例外),锁存时机太窄;
    // 加上 index 容差在纯文字流式时 last.index 永远是 0,完全失效。新锁存逻辑在 LazyColumn pointerInput 里。
    var userScrolledAway by remember { mutableStateOf(false) }
    // ═══ 钉住 anchor item:解决「离开底部后 AI 继续吐把上面内容顶上去」的问题 ═══
    // reverseLayout 下 index 0(AI 正在吐的气泡)永远在屏幕最底部,它不断长高时从底部往上顶,
    // 会把视口里的其它气泡一起往上推——哪怕不调用 scrollToItem 也一样。
    // 解法:用户手指按下开始滑时,记录下视口里最靠下那条 index + offset 作为 anchor;
    // 之后每次流式增量,只要 userScrolledAway=true,就把这条钉回原位。anchor 条以下(含 index 0)
    // 继续长高没问题,anchor 条以上的内容不会被推。
    var anchorItemIndex by remember { mutableStateOf(-1) }
    var anchorItemOffset by remember { mutableStateOf(0) }
    // 官方 API 被管理员暂停时的明确提示对话框：拦截时弹窗告知，避免用户误以为发送 bug
    var apiSuspendedDialog by remember { mutableStateOf(false) }
    var groupMemberNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupMemberIdMap by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    // @ 成员选择抽屉用：结构化成员列表
    var groupAtMembers by remember { mutableStateOf<List<AtMember>>(emptyList()) }
    var isLoadingMessages by remember { mutableStateOf(true) }
    var scrollReady by remember { mutableStateOf(false) }
    // 撤回计数：markRevoked 只原地改同一条 ref、messages 列表结构不变，Compose 不会重组；
    // bump 它强制可见项重组，使对话内/对方撤回的占位即时显示（无需离开重进）
    var recallTick by remember { mutableStateOf(0) }
    // 逐消息上传进度：messageId -> progress (0.0~1.0)
    val uploadProgressMap = remember { mutableStateMapOf<Long, Float>() }
    // 视频上传阶段：messageId -> "compressing"(压缩中) / "uploading"(上传中)
    val uploadStageMap = remember { mutableStateMapOf<Long, String>() }
    // 视频宽高比：messageId -> width/height（用于上传占位按比例显示）
    val uploadRatioMap = remember { mutableStateMapOf<Long, Float>() }
    // 上传中的文件名：localId -> 原始文件名（用于文件上传占位卡片显示）
    val uploadFileNameMap = remember { mutableStateMapOf<Long, String>() }
    val replyToMsg = remember { mutableStateOf<IMessageRef?>(null) } // 引用功能：当前正在回复的消息

    // Plus按钮弹窗状态
    var showPlusDialog by remember { mutableStateOf(false) }
    // 转账弹窗状态
    var showTransferDialog by remember { mutableStateOf(false) }
    // 红包：发送弹窗 / 详情全屏 / 实时状态
    var showRedPacketDialog by remember { mutableStateOf(false) }
    var redPacketDetailId by remember { mutableStateOf(0L) }
    val redpacketStatus = remember { mutableStateMapOf<Long, RedPacketStatus>() }
    // 相机拍摄状态
    var showCamera by remember { mutableStateOf(false) }
    // 位置请求触发状态
    var showLocationRequest by remember { mutableStateOf(false) }
    // 隐私控制面板
    var showPrivacyPanel by remember { mutableStateOf(false) }
    // 禁言状态：禁言截止时间戳（0=未禁言）
    var mutedUntil by remember { mutableStateOf(0L) }
    var showLocationConfirm by remember { mutableStateOf(false) }

    // ── AI 对话专属：时间点 / 确认发言 ──（提升到顶层，让 InputBar 等兄弟块可见）
    var showTimePointDialog by remember { mutableStateOf(false) }
    var showConfirmSpeechDialog by remember { mutableStateOf(false) }
    // 「图片」首次使用提示：告知所选模型可能不支持图片识别（如 DeepSeek 为纯文本模型）
    var showAiImageNotice by remember { mutableStateOf(false) }
    var confirmSpeechEnabled by remember(currentUserId) { mutableStateOf(AiChatManager.getConfirmSpeech(ctx, currentUserId)) }
    var currentTimePoint by remember(currentUserId) { mutableStateOf(AiChatManager.getTimePoint(ctx, currentUserId)) }
    // 是否有待确认的消息（用户发送后 AI 未回复的消息）
    var confirmSpeechPending by remember { mutableStateOf(false) }
    // AI 对话「图片」附件：选择后暂存在输入框上方，点击发送时才随消息一起发出去
    var pendingAiImages by remember { mutableStateOf<List<PendingAiImage>>(emptyList()) }
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
    var pendingAiFiles by remember { mutableStateOf<List<PendingAiFile>>(emptyList()) }
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
    // 当前展开思考过程的消息 ID（仅最新 AI 消息展开，其余收起）
    var expandedReasoningMsgId by remember { mutableStateOf(0L) }
    // ── 隐私控制：加载对方设置并应用 FLAG_SECURE（禁止截图 / 禁止录屏） ──
    var privacyRefreshKey by remember { mutableStateOf(0) }
    // 记录是否是由隐私设置触发的 Activity 重建（避免无限循环）
    val wasRecreatedForPrivacy = remember { mutableStateOf(false) }
    LaunchedEffect(friendId, privacyRefreshKey) {
        if (friendId <= 0) return@LaunchedEffect
        try {
            val fr = AuroraApi.getFriendPrivacySettings(friendId)
            if (fr.success && fr.data != null) {
                val s = fr.data!!
                val noScreenshot = s.optBoolean("no_screenshot", false)
                val noRecording = s.optBoolean("no_recording", false)
                val needFlagSecure = noScreenshot || noRecording
                val act = ctx as? android.app.Activity
                val hasFlagSecure = (act?.window?.attributes?.flags
                    ?.and(android.view.WindowManager.LayoutParams.FLAG_SECURE) ?: 0) != 0
                if (needFlagSecure && !hasFlagSecure) {
                    // 对方开启了禁止截图/录屏 → 设置 FLAG_SECURE
                    act?.runOnUiThread {
                        act.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                } else if (!needFlagSecure && hasFlagSecure && !wasRecreatedForPrivacy.value) {
                    // 对方没开启，但 FLAG_SECURE 还挂着（来自上个对话）→ 重建 Activity 清掉
                    wasRecreatedForPrivacy.value = true
                    act?.runOnUiThread {
                        act.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        // 真正清除 FLAG_SECURE 需要重建 Activity
                        act.recreate()
                    }
                } else if (needFlagSecure && hasFlagSecure) {
                    wasRecreatedForPrivacy.value = false
                }
            }
        } catch (_: Exception) {}
    }

    // 消息列表 — 支持 StoreMessageRef (adapter) 和 ChatMsg (旧加载路径) 共存
    val adapter = remember(friendId) { MessageStoreAdapter() }
    //  从 companion cache 恢复已销毁组件的数据（切对话零加载）
    //   remember(friendId) 确保切换对话时重建列表，同时恢复该对话的缓存
    val messages = remember(friendId) {
        val saved = companionMessageCache.remove(friendId)
        if (saved != null) mutableStateListOf<IMessageRef>().also { it.addAll(saved) }
        else mutableStateListOf<IMessageRef>()
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
        saveLocalChatMessages(ctx, currentUserId, localChatKey(AI_CHAT_ID), messages.filter { !it.isSystemNotice })
        // 新卡片插入:同流式增量一样,钉住 anchor
        if (userScrolledAway && anchorItemIndex > 0) {
            aiGlobalScope.launch { listState.scrollToItem(anchorItemIndex, anchorItemOffset) }
        } else if (atBottomState.value && !userScrolledAway) {
            try { aiGlobalScope.launch { listState.scrollToItem(0) } } catch (_: Exception) {}
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
        saveLocalChatMessages(ctx, currentUserId, localChatKey(AI_CHAT_ID), messages.filter { !it.isSystemNotice })
        // 新卡片插入:同流式增量一样,钉住 anchor
        if (userScrolledAway && anchorItemIndex > 0) {
            aiGlobalScope.launch { listState.scrollToItem(anchorItemIndex, anchorItemOffset) }
        } else if (atBottomState.value && !userScrolledAway) {
            try { aiGlobalScope.launch { listState.scrollToItem(0) } } catch (_: Exception) {}
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
        if (userScrolledAway && anchorItemIndex > 0) {
            aiGlobalScope.launch { listState.scrollToItem(anchorItemIndex, anchorItemOffset) }
        } else if (atBottomState.value && !userScrolledAway) {
            try { aiGlobalScope.launch { listState.scrollToItem(0) } } catch (_: Exception) {}
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
     *  内联工具循环走这里交付；runAgentChatLoop 走 onFileSent / onMediaGenerated 回调。二者互斥。 */
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
    /** 安全替换全部消息（去重：后出现的覆盖先出现的，避免 LazyColumn 因重复 key 闪退）。
     *  增量同步：尽量保留同 id 的现有对象引用，仅对新增/移除/移动的位置做最小改动，
     *  避免 clear()+addAll 触发整表变更通知，使 TCP 增量合并/撤回不再引发整窗重组。 */
    fun MutableList<IMessageRef>.replaceAllUnique(elements: List<IMessageRef>) {
        val oldById = LinkedHashMap<Long, IMessageRef>()
        forEach { oldById[it.id] = it }
        // 复用旧对象引用（顺序按新列表），未命中的用新对象
        val desired = elements.map { oldById[it.id] ?: it }
        val desiredIds = desired.mapTo(mutableSetOf()) { it.id }
        // 1) 移除已不存在的消息（从后往前，避免索引错位）
        var idx = size - 1
        while (idx >= 0) {
            if (this[idx].id !in desiredIds) removeAt(idx)
            idx--
        }
        // 2) 按 desired 顺序修正：缺失则插入，位置不符则移动到正确位（保留引用）
        var p = 0
        for (el in desired) {
            val pos = indexOfFirst { it.id == el.id }
            if (pos == -1) {
                add(p, el)
            } else if (pos != p) {
                val moved = removeAt(pos)
                add(p, moved)
            }
            p++
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
        val refs = (0 until n).map { adapter.refAt(it) }
        messages.replaceAllUnique(refs)
    }

    // ==================== 消息修改（长按 → 修改：编辑思考部分与实际输出，保存后写回本地） ====================
    // 声明在 onConfirmReply 之前，以便确认发言 / 直接发送两个入口都能读取 editTargetMsgId（补回被编辑消息到上下文）
    var showEditDialog by remember { mutableStateOf(false) }
    var editTargetMsgId by remember { mutableStateOf(0L) }
    var editReasoningText by remember { mutableStateOf("") }
    var editOutputText by remember { mutableStateOf("") }
    // 「编辑自己最后一条消息 → 重置并重新发送」的确认弹窗状态（仅 AI 对话、且是本人消息、且是最后一条时触发）
    var resendNewChatConfirm by remember { mutableStateOf(false) }
    var resendEditMsgId by remember { mutableStateOf(0L) }
    var resendEditText by remember { mutableStateOf("") }
    // 开发者广播（仅 ID=1 可见）：发送弹窗 / 长按管理弹窗状态
    var showBroadcastSendDialog by remember { mutableStateOf(false) }
    var broadcastManageMsgId by remember { mutableStateOf(0L) }
    var broadcastManageTaskId by remember { mutableStateOf(0L) }
    // 订单详情：点击通知卡片「查看订单」后弹出
    var viewOrderId by remember { mutableStateOf<Long?>(null) }

    // Agent 访问模式 + 工具审批门控。必须在「发送/确认发言」等用到 toolApprovalHook 的 lambda 之前声明。
    var agentAccessMode by remember { mutableStateOf(AiChatManager.getAccessMode(ctx, currentUserId)) } // root / full / manual
    // Agent 语义模式：ask=仅对话（无工具权限）/ craft=完整工具能力（默认）/ plan=先规划再执行。选中即持久化；AI 用 set_agent_mode 切换后也会刷新。
    var agentMode by remember { mutableStateOf(AiChatManager.getAgentMode(ctx, currentUserId)) } // ask / craft / plan
    var showAgentModeMenu by remember { mutableStateOf(false) } // 「模式选择」底部菜单
    var thinkingDepth by remember { mutableStateOf(AiChatManager.getThinkingDepth(ctx, currentUserId)) } // low / medium / high
    var showThinkingDepthMenu by remember { mutableStateOf(false) } // 「思考深度」底部菜单
    // Agent 执行模式(多选):off=标准 / fast=极速 / long=长任务 / selfcheck=自检 / research=深度研究 / plan=规划先行
    // multi_agent=多 Agent / memory=记忆增强 / create=创作;集合内多个模式叠加生效,冲突项由菜单层拦截(选中即持久化)
    var execModes by remember { mutableStateOf(AiChatManager.getExecModes(ctx, currentUserId)) }
    var showExecModeMenu by remember { mutableStateOf(false) } // 「执行模式」底部菜单
    var showWorkspaceFiles by remember { mutableStateOf(false) } // 「工作区文件」全屏面板(右侧滑入)
    var showPluginMarket by remember { mutableStateOf(false) } // 「插件市场」全屏面板(右侧滑入)
    var showBackupScreen by remember { mutableStateOf(false) } // 「备份与恢复」全屏界面(独立,不嵌套在工作区内)
    // 「查看所有改动」全屏界面状态:open 控制显隐,jsonData 保留最后一次打开的数据(退出动画期间仍有内容)
    var aiChangesOpen by remember { mutableStateOf(false) }
    var aiChangesJsonData by remember { mutableStateOf("") }
    // 当前打开的改动所属消息 ID:退回成功后回写 ai_file_changes 时定位消息用
    var aiChangesMsgId by remember { mutableStateOf(-1L) }
    // 「工具调用」全屏清单:数据 = 点击按钮那条 AI 消息携带的 aiToolStages(工具名/成败/毫秒时间戳)
    var aiToolCallsOpen by remember { mutableStateOf(false) }
    var aiToolCallsData by remember { mutableStateOf<List<ChatMsg.ToolStageRecord>>(emptyList()) }
    var modeExplainDialog by remember { mutableStateOf<String?>(null) } // 待说明的模式（ask/craft/plan），null=不弹
    // 待审批：Pair(工具名, 参数JSON)；审批弹窗据此生成「将做什么」的中文简述
    var pendingToolApproval by remember { mutableStateOf<Pair<String, String>?>(null) }
    val pendingToolApprovalDeferred = remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
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
            // manual=手动审批：所有工具都需审批。
            // 应用在后台时,应用内审批弹窗用户看不到,任务会一直挂起——此时同步发一条
            // 带「批准/拒绝」按钮的系统通知,用户在通知栏即可完成审批,不必回到应用内。
            // 通知与应用内弹窗共用同一个 CompletableDeferred,哪边先批都幂等。
            val def = kotlinx.coroutines.CompletableDeferred<Boolean>()
            pendingToolApprovalDeferred.value = def
            pendingToolApproval = tool to argsJson
            val requestId = com.aurora.chat.AgentApprovalBridge.begin(def)
            if (!com.aurora.chat.AppForegroundTracker.isForeground) {
                NotificationHelper.showToolApprovalNotification(
                    ctx, com.aurora.chat.ui.chat.describeToolCall(tool, argsJson), requestId
                )
            }
            val approved = try {
                def.await()
            } finally {
                com.aurora.chat.AgentApprovalBridge.clear(def)
                pendingToolApproval = null
                pendingToolApprovalDeferred.value = null
                NotificationHelper.cancelToolApprovalNotification(ctx)
            }
            approved
        }
    }

    // 前后台追踪 + 审批补发：审批弹窗还挂着用户就退到后台 → 补发一条带「批准/拒绝」按钮的
    // 系统通知，让用户在通知栏直接审批，任务不至于一直挂死。与挂起点发的是同一条通知（同 ID 覆盖）。
    val approvalLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(approvalLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    com.aurora.chat.AppForegroundTracker.isForeground = true
                    // 回到前台:应用内审批弹窗已可见,悬浮审批卡若有残留立即撤下,绝不双重弹窗
                    com.aurora.chat.AgentApprovalOverlay.dismiss()
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    com.aurora.chat.AppForegroundTracker.isForeground = false
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val pending = pendingToolApprovalDeferred.value
                    if (pending != null && pending.isActive &&
                        com.aurora.chat.AgentApprovalBridge.hasActive() &&
                        !com.aurora.chat.AppForegroundTracker.isForeground
                    ) {
                        val requestId = com.aurora.chat.AgentApprovalBridge.activeRequestId()
                        val (toolName, toolArgs) = pendingToolApproval ?: return@LifecycleEventObserver
                        NotificationHelper.showToolApprovalNotification(
                            ctx, com.aurora.chat.ui.chat.describeToolCall(toolName, toolArgs),
                            requestId ?: -1L
                        )
                    }
                }
                else -> {}
            }
        }
        approvalLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { approvalLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 确认发言：用户点击"确认发言"按钮后触发 AI 回复（定义在 messages 之后，避免前向引用）
    val onConfirmReply: () -> Unit = {
        // 先记录是否有一条"待确认"的用户消息（来自上一次发送），再清除待确认标记
        val hadPending = confirmSpeechPending
        confirmSpeechPending = false
        // 修复：确认发言改跑全局作用域（与普通发送一致）。原实现挂在组合作用域 scope 上，
        // 用户确认后立刻离开对话页（上滑关小窗/切会话）协程即被取消：中途任务被掐死、
        // AgentTaskGuard.exit 不执行 → 残留「运行中」标记，重进误报「上次任务被中断」。
        // 改为 aiGlobalScope 后离开对话不取消，收尾正常执行、标记正常清理。
        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            // 与「普通发送」保持一致的就绪状态：解除可能残留的停止标记，并把发送按钮切为「停止」，
            // 让下面的占位消息以 aiWaiting=true 进入「等待模型响应」空窗（重发/确认发言均走此入口）。
            aiStopRequested = false
            aiWriting.value = true
            aiWritingConvId = AiConversationSession.activeConvId
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
            // 必须在用到它的地方（官方模式 estInput、history 构造）之前声明，避免 Kotlin 局部变量前向引用失败。
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
            expandedReasoningMsgId = 0L
            // P3:统一走 AiChatManager.buildChatContext(按执行模式/思考深度自适应条数预算+超长单条折叠),
            // 取代旧硬截断 take(1)+takeLast(39)——旧逻辑只压条数不压单条,长工具结果/长回复仍会把上下文撑爆
            val chatCandidates = messages.filter { !it.isSystemNotice && it.text.isNotBlank() && it.text != AI_THINKING }
            val chatContext = AiChatManager.buildChatContext(chatCandidates, editTargetMsgId, ctx, currentUserId)
            val mode = AiChatManager.getMode(ctx, currentUserId)
            var estInput = 0L
            val baseUsed: Long
            var poolAtStart = 0L
            if (mode == "official") {
                // 发送前强制拉取服务器最新余额，避免因本地缓存过期导致「余额=0 时还能发一次」
                AiChatManager.syncServerLimit(ctx, currentUserId)
                val (used, remaining) = AiChatManager.getOfficialUsage(ctx, currentUserId)
                aiUsageState.value = Pair(used, remaining)
                baseUsed = used
                poolAtStart = remaining
                estInput = chatContext.sumOf { AiChatManager.estimateTokens(it.text).toLong() }
                    .let { if (syntheticNoFeedback) it + AiChatManager.estimateTokens(noFeedbackUserText).toLong() else it }
                // 余额判定以「服务器真实 token_balance」为准（不受开发者无限额度影响）：
                // 余额=0 时弹卡片；开发者仍放行（无限额度），普通用户则拦截发送。
                val rawBal = AiChatManager.getServerTokenBalance(ctx, currentUserId)
                if (rawBal <= 0L) {
                    onTokenExhausted()
                    if (!AiChatManager.isDeveloper(ctx, currentUserId)) return@launch
                }
            } else {
                baseUsed = aiUsageState.value.first
            }
            val systemText = buildString {
                // 「节省 Token / 极致节省」模式已移除：不再注入对应指令。
                // 性格/自定义设定（CustomTrait）仅当「开启性格调试」为 ON 时才注入并生效；关闭时忽略。
                if (AiChatManager.getPersonalityDebug(ctx, currentUserId)) {
                    append(AiChatManager.buildPersonaSystemPrompt(AiChatManager.getTraits(ctx, currentUserId), AiChatManager.getCustomTraits(ctx, currentUserId)))
                    if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) append("\n\n").append(AiChatManager.PERSONA_SWITCH_RELEASE)
                }
            }
            val reversed = friendId == AI_CHAT_ID && reverseHistory
            val reverseNote = if (reversed) "\n\n【重要设定】本次对话的聊天记录是「反转」的：原本由用户（我方）发送的消息，现在代表「对方」；原本由你（AI）发送的回复，现在代表「我（用户）」。请你以用户（我方）的视角和身份，继续与对方自然对话，不要提及这是反转设定，也不要点破角色互换。" else ""
            val systemFinal = buildString {
                append(systemText)
                append(AiChatManager.buildAdvancedSystemPrompt(ctx, currentUserId))
                append(AiChatManager.multiOutputConstraint(ctx, currentUserId))
                if (reverseNote.isNotBlank()) append(reverseNote)
                if (currentTimePoint.isNotBlank()) append("\n\n【当前时间】$currentTimePoint")
                // 让 AI 把代码/HTML 用三反引号代码块输出，便于界面渲染出一键复制按钮
                append("\n\n【格式化要求】你的回答中只要包含代码片段（HTML、CSS、JavaScript、SQL、JSON、Python 等任何代码），都必须用三个反引号包裹成标准 Markdown 代码块并在起始反引号后标注语言，例如 ```html ... ```。代码块以外的文字用普通段落即可；没有代码时不要使用代码块。")
            }
            if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) AiChatManager.setPersonaSwitch(ctx, currentUserId, false)
            // 无新反馈时：在 system 里明确告诉 AI"用户这次什么都没说"，并让 AI 自己决定如何回应
            val systemForHistory = buildString {
                append(systemFinal)
                append(AiChatManager.buildAgentAwarenessPrompt(ctx, currentUserId))
                if (syntheticNoFeedback) append(
                    "\n\n【场景提示】用户刚刚又点击了一次「确认发言」，但本次没有输入任何内容、也没有发送新消息——也就是说用户这一次没有任何新反馈。" +
                    "请不要把自己的上一句回复当成用户在说话，也不要无脑续写、自顾自地聊；你可以主动反问用户、引导话题，或表达在等待用户的回应，话题请承接已有上下文。"
                )
            }
            // 无新反馈时，在末尾补一条"用户侧"回合，明确用户什么都没说，
            // 使模型以 assistant 身份回应（质问/闲聊），而不是续写自己上一条回复
            // （noFeedbackUserText 已在隔离判断处提前声明，此处只取对应角色）
            // 无新反馈时，在末尾补一条"用户侧"回合，明确用户什么都没说，
            // 使模型以 assistant 身份回应（质问/闲聊），而不是续写自己上一条回复
            // （noFeedbackUserText 已在隔离判断处提前声明，此处只取对应角色）
            val noFeedbackRole = if (true != reversed) "user" else "assistant"
            // 用 mutableListOf 累加，避免多行 + 链类型推断陷阱（之前报 unaryPlus）
            val history = mutableListOf<Pair<String, String>>()
            if (systemForHistory.isNotBlank()) history.add("system" to systemForHistory)
            chatContext.map { (if (it.isMine != reversed) "user" else "assistant") to it.text }
                .forEach { history.add(it) }
            if (syntheticNoFeedback) history.add(noFeedbackRole to noFeedbackUserText)
            val aiMsgId = System.currentTimeMillis() * 10000L + (0..9999).random()
            expandedReasoningMsgId = aiMsgId
            val initText = if (AiChatManager.getShowReasoning(ctx, currentUserId)) "" else AI_THINKING
            messages.add(ChatMsg.create(
                serverId = aiMsgId, text = initText, isMine = false, fromUserId = AI_CHAT_ID,
                senderName = AiNameState.name ?: "AI", isNew = true, createdAt = System.currentTimeMillis() / 1000
            ).copy(aiWaiting = true))
            // 工具状态不再锚定到任何气泡：它作为独立消息由 addToolStatus 插入，天然站在它该站的位置
            // 流式期间索引稳定，缓存一次避免每个 token 都做 O(n) indexOfFirst
            var aiMsgIdx = messages.size - 1
            // 占位入列后立即落盘一次：即使随后切走会话，磁盘也有该条待回复占位，切回可恢复
            val pendingSnap = messages.filter { !it.isSystemNotice }.toList()
            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, pendingSnap) } catch (_: Exception) {} }
            // 滚动跟随按时间合并，避免每 token 新建协程
            var lastScrollMs = 0L
            fun maybeScrollToBottom() {
                if (atBottomState.value && !userScrolledAway) {
                    val now = System.currentTimeMillis()
                    if (now - lastScrollMs > 80) {
                        lastScrollMs = now
                        scope.launch { try { listState.scrollToItem(0) } catch (_: Exception) {} }
                    }
                }
            }
            try { listState.scrollToItem(0) } catch (_: Exception) {}
            // ===== Agent 工具执行循环 =====
            // 回复含工具调用时执行并回喂模型继续生成；最多 MAX_AGENT_TOOL_ROUNDS 轮，防死循环。
            var accumulated = ""
            // 本轮（agent 循环）是否执行过工具：用于收尾时避免用累积全文覆盖多条已独立成文的工具气泡
            var loopUsedTools = false
            // 上一轮是否执行了工具：为 true 时下一轮正文应另开一条新气泡，从而工具调用/卡片就近显示而非堆到最底部
            var pendingToolRound = false
            var lastRawFull = ""
            var loopRounds = 0
            var roundDone = false
            var loopFailed = false
            var loopManualStop = false
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
            com.aurora.chat.AgentTaskGuard.enter(ctx, "AI 工具任务")
            // 多 Agent 强制验收跟踪(主发送路径走本内联循环,与 runAgentChatLoop 的编排层兜底同逻辑);
            // 轮数上限用 maxToolRoundsFor(执行模式优先:极速=0/长任务=不限),不再硬编码 MAX_AGENT_TOOL_ROUNDS
            val inlineMaxRounds = AiChatManager.maxToolRoundsFor(ctx, currentUserId)
            var inlineReviewCalled = false
            var inlineReviewNudged = false
            // agent_review 已被调用次数:配合 MAX_AGENT_REVIEW_CALLS 做编排层硬上限(防验收死循环)
            var inlineReviewCount = 0
            val inlineMutated = mutableSetOf<String>()
            // ===== 任务守护状态机(NORMAL → WRAP_UP → DELIVERED)=====
            var phase = AgentTaskCompletion.AgentTaskPhase.NORMAL
            var wrapUpRounds = 0
            var nudgeCount = 0
            var continueCount = 0
            var retryCount = 0
            var currentToolChoice: String? = null
            var contractNeeded = AgentTaskCompletion.needsContract(ctx, currentUserId, 0)
            var inlineToolCount = 0
            var finishReason = ""
            var lastErrMsg = ""
            var inlineRetryPending = false
            while (true) {
                // 上一轮执行了工具：本轮正文开一条新气泡（紧跟工具产物与文件卡片），而不是把所有正文堆进同一条旧气泡，
                // 使每条工具调用的状态行与文件卡片都显示在它真正发生的位置附近，而不是集中到整个回答的末尾。
                var freshNewBubble = false
                if (pendingToolRound) {
                    val newId = System.currentTimeMillis() * 10000L + (0..9999).random()
                    messages.add(ChatMsg.create(
                        serverId = newId, text = "", isMine = false, fromUserId = AI_CHAT_ID,
                        senderName = AiNameState.name ?: "AI", isNew = true, createdAt = System.currentTimeMillis() / 1000
                    ).copy(aiWaiting = true))
                    aiMsgIdx = messages.size - 1
                    pendingToolRound = false
                    freshNewBubble = true
                    // 思考段累积器按气泡隔离:上一轮的思考已内联进上一条气泡,若这里不清零,
                    // 渲染端会拿「跨气泡累积段数 > 本气泡内联块数」误判出一段"未内联的实时思考",
                    // 在新气泡末尾重复画出上一轮的思考内容(重复思考过程 bug 的根因)。
                    reasoningSegments = ""
                    maybeScrollToBottom()
                }
                roundDone = false
                val isFirstRound = (loopRounds == 0)
                val prefix = if (isFirstRound || freshNewBubble) "" else accumulated
                val nativeCalls = mutableListOf<AiChatManager.AgentNativeToolCall>()
                AiChatManager.streamChat(
                    ctx = ctx, userId = currentUserId, history = history,
                    onReasoningDelta = { reasoning ->
                        roundReasoning = reasoning
                        // 实时把本轮思考以哨兵嵌进当前正文流末尾：让它紧跟已输出的内容，而非叠到顶部
                        val live = AiChatManager.appendReasoningSegment(reasoningSegments, reasoning)
                        writeReasoning(live)
                        maybeScrollToBottom()
                        if (mode == "official" && poolAtStart > 0L) {
                            val liveUsed = baseUsed + estInput
                            val liveRemaining = (poolAtStart - estInput).coerceAtLeast(0)
                            aiUsageState.value = Pair(liveUsed, liveRemaining)
                        }
                    },
                    onDelta = { cur ->
                        // 流式展示时同样去掉工具协议片段，避免气泡闪出 @tool 标记
                        val stripped = AiChatManager.stripToolBlocks(cur)
                        val shown = if (isFirstRound) stripped else (if (prefix.isBlank()) stripped else prefix + "\n\n" + stripped)
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            val msg = messages[aiMsgIdx] as ChatMsg
                            val keepReasoning = if (AiChatManager.getShowReasoning(ctx, currentUserId)) msg.reasoningText else ""
                            messages[aiMsgIdx] = msg.copy(text = shown.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""), reasoningText = keepReasoning, aiWaiting = false)
                        }
                        maybeScrollToBottom()
                        if (mode == "official" && poolAtStart > 0L) {
                            val inc = estInput + AiChatManager.estimateTokens(cur)
                            aiUsageState.value = Pair(baseUsed + inc, (poolAtStart - inc).coerceAtLeast(0))
                        }
                    },
                    onDone = { usage, full, thinkingSeconds, totalSeconds ->
                        // 实时进度提示（内联循环主路径）：仅任务型会话显示「任务进行中」，普通对话留空以免打扰
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            val hint = if (phase == AgentTaskCompletion.AgentTaskPhase.WRAP_UP) "正在收尾交付…"
                                       else if (contractNeeded) "任务进行中 · 第 ${loopRounds + 1} 轮"
                                       else ""
                            messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(aiProgressHint = hint)
                        }
                        roundDone = true
                        lastUsage = usage
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
                                    AiChatManager.appendInlineReasoning(accumulated, thisRoundThinking) + "\n\n" + cleaned
                                }
                            }
                            isFirstRound -> cleaned
                            cleaned.isBlank() -> accumulated
                            accumulated.isBlank() -> cleaned
                            else -> accumulated + "\n\n" + cleaned
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
                        lastErrMsg = err
                        // 可重试错误(网络/5xx/断流):原地重试,保留已累积进度,不终结任务
                        if (AgentTaskCompletion.isRetriable(err) && retryCount < AgentTaskCompletion.MAX_ROUND_RETRIES) {
                            retryCount++
                            inlineRetryPending = true
                            Log.i("AiInline", "内联循环单轮可重试错误,第 $retryCount 次重试")
                            android.widget.Toast.makeText(ctx, "网络波动,正在重试($retryCount)...", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            loopFailed = true
                            aiWriting.value = false
                            aiWritingConvId = -1L
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
                    onTokenInsufficient = { aiWriting.value = false; aiWritingConvId = -1L; onTokenExhausted() },
                    nativeToolAccumulator = nativeCalls,
                    enableAgentTools = agentMode != AiChatManager.MODE_ASK,
                    toolChoice = currentToolChoice,
                    onFinish = { r -> finishReason = r }
                )
                // 可重试错误：原地重试，不终结任务（保留已累积进度与气泡）
                if (inlineRetryPending) { inlineRetryPending = false; continue }
                // 输出被上游 max_tokens 截断(finish_reason=length)：自动从断点续写，最多 MAX_CONTINUE_ROUNDS 次
                if (!loopFailed && !loopManualStop && finishReason == "length" && continueCount < AgentTaskCompletion.MAX_CONTINUE_ROUNDS) {
                    continueCount++
                    val tail = AiChatManager.stripToolBlocks(lastRawFull).takeLast(200)
                    history.add("user" to AgentTaskCompletion.continueInstruction(tail))
                    Log.i("AiInline", "内联循环输出被截断,自动续写(第 $continueCount 次)")
                    continue
                }
                if (loopFailed) break
                // 手动停止：保留已流出部分文本，跳过工具循环（此处 aiWriting 已复位）
                if (!roundDone) { loopManualStop = true; break }
                // 任务型会话(仅任务型执行模式)需要交付契约：动态更新判断依据(普通对话恒 false,绝不打扰)
                contractNeeded = AgentTaskCompletion.needsContract(ctx, currentUserId, inlineToolCount)
                if (loopRounds >= inlineMaxRounds) {
                    // 软上限：轮次预算耗尽不再硬断，而是转入收尾阶段(禁工具强制纯文本交付)，保证用户拿到交付
                    if (contractNeeded && phase == AgentTaskCompletion.AgentTaskPhase.NORMAL && wrapUpRounds < AgentTaskCompletion.MAX_WRAP_UP_ROUNDS) {
                        phase = AgentTaskCompletion.AgentTaskPhase.WRAP_UP
                        wrapUpRounds++
                        currentToolChoice = "none"
                        history.add("user" to AgentTaskCompletion.wrapUpInstruction(loopRounds, "轮次预算已用尽"))
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
                        val execCall = AiChatManager.AgentToolCall(
                            tool = nc.name,
                            path = args.optString("path", ""),
                            content = args.optString("content", ""),
                            argsJson = nc.argumentsJson
                        )
                        // 多 Agent 强制验收跟踪:是否验过收 / 动过工作区 / 复验计数
                        if (nc.name == AiChatManager.TOOL_AGENT_REVIEW) { inlineReviewCalled = true; inlineReviewCount++ }
                        if (nc.name in AiChatManager.REVIEW_REQUIRED_TOOLS) inlineMutated.add(nc.name)
                        // 调用开始：把「正在调用」状态行内联进当前 AI 消息正文（哨兵块随文本持久化，绝不丢失），
                        // 让状态显示在调用真正发生的位置，而不是堆到所有结果之后。
                        accumulated = AiChatManager.appendToolInline(accumulated, nc.name, 0)
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
                                AiChatManager.executeAgentTool(ctx, currentUserId, execCall, onAppAction)
                            }
                        } else {
                            AiChatManager.agentDeniedResult(ctx, currentUserId, nc.name)
                        }
                        // 完成态：结果 ok 显示绿色「已调用」，否则红色「调用失败」（完成为彩色状态行，不再写纯文本）
                        val sOk = try { org.json.JSONObject(result).optBoolean("ok") } catch (_: Exception) { false }
                        // agent_review：把验收 Agent 的报告块写进消息流（可见的接手记录，随消息持久化）
                        if (nc.name == AiChatManager.TOOL_AGENT_REVIEW || nc.name == AiChatManager.TOOL_AGENT_FORK)
                            (if (nc.name == AiChatManager.TOOL_AGENT_REVIEW) AiChatManager.reviewReportBlock(result) else AiChatManager.forkReportBlock(result))?.let { blk ->
                            accumulated = accumulated + "\n" + blk
                        }
                        // 调用完成：把同名状态行原地替换为最终态（1 已调用 / 2 失败）
                        // ask_user 完成：「问/答」记录直接嵌进状态块（点状态行展开查看）
                        accumulated = AiChatManager.replaceToolInline(
                            accumulated, nc.name, if (sOk) 1 else 2,
                            if (nc.name == AiChatManager.TOOL_ASK_USER) AiChatManager.askUserRecordText(result) ?: "" else ""
                        )
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(
                                text = accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                            )
                        }
                        scheduleAiDebouncedSave()
                        maybeScrollToBottom()
                        // 工具完成后同步刷新 UI 模式状态（Plan 模式下 AI 可能已用 set_agent_mode 自动切换到 craft）
                        agentMode = AiChatManager.getAgentMode(ctx, currentUserId)
                        // role:tool 回喂：PREFIX + tool_call_id + \0 + resultJson（request 组装时会还原）
                        val anchorId1 = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) messages[aiMsgIdx].id else -1L
                        insertAiFileCardIfGot(nc.name, result, anchorId1)
                        history.add("tool" to AiChatManager.NATIVE_TOOL_RESULT_PREFIX + nc.id + "\u0000" + result)
                    }
                    loopUsedTools = true
                    inlineToolCount++
                    pendingToolRound = true
                    loopRounds++
                    continue
                }
                // 文本协议兜底（原生不可用时）：@tool:start 块
                var toolCall = AiChatManager.extractFirstToolCall(lastRawFull)
                // 第三层兜底：扫描 JSON 代码块里的工具调用（模型可能裸输出 {"tool":"..."} 作为代码块）
                if (toolCall == null) toolCall = AiChatManager.extractToolCallFromCodeBlock(lastRawFull)
                if (toolCall == null) {
                    // 模型本轮既没调用工具、也没输出正文（只有思考）：只要没到轮数上限就继续下一轮，
                    // 避免"想了半天却什么都没说"就结束。
                    val cleanedFull = AiChatManager.stripToolBlocks(lastRawFull).trim()
                    if (cleanedFull.isBlank() && loopRounds < inlineMaxRounds) {
                        loopRounds++
                        continue
                    }
                    // 多 Agent 硬约束(编排层兜底):动了工作区却从未调用 agent_review → 注入补验指令,强制再跑一轮。
                    // 提示词层的「必须验收」被模型无视时的确定性补救;只补验一次,防死循环。
                    if (AiChatManager.execActive(ctx, currentUserId, AiChatManager.EXEC_MULTI_AGENT) &&
                        !inlineReviewCalled && !inlineReviewNudged && inlineMutated.isNotEmpty() && loopRounds < inlineMaxRounds
                    ) {
                        inlineReviewNudged = true
                        history.add("user" to "【系统强制补验】你处于多 Agent 模式,本次任务调用了 ${inlineMutated.joinToString("、")} 等改动工作区的工具,但没有调用 agent_review 做独立验收。请现在调用 agent_review(task=用户的原始请求,summary=你的工作汇报:做了什么、改了哪些文件含路径、关键操作与结果)完成验收,再向用户交付最终答复。")
                        continue
                    }
                    // 防提前收工：任务型会话想结束却没交付 → 强制再跑(最多 MAX_DELIVER_NUDGES 次)
                    if (phase != AgentTaskCompletion.AgentTaskPhase.WRAP_UP && contractNeeded && !AgentTaskCompletion.hasDelivery(accumulated) && nudgeCount < AgentTaskCompletion.MAX_DELIVER_NUDGES) {
                        nudgeCount++
                        history.add("user" to AgentTaskCompletion.nudgeInstruction())
                        Log.i("AiInline", "内联循环模型想结束但无交付,强制补做(第 $nudgeCount 次)")
                        continue
                    }
                    // 收尾阶段(WRAP_UP)：给最多 MAX_WRAP_UP_ROUNDS 轮交付；交付即停，否则续一轮
                    if (phase == AgentTaskCompletion.AgentTaskPhase.WRAP_UP) {
                        if (AgentTaskCompletion.hasDelivery(accumulated) || wrapUpRounds >= AgentTaskCompletion.MAX_WRAP_UP_ROUNDS) break
                        wrapUpRounds++
                        history.add("user" to "【系统·收尾续写】请继续补全你的交付总结(若确需补救且额度未耗尽可少量调用工具)。")
                        continue
                    }
                    // 兜底：任务型会话模型始终不交付 → 编排层合成交付块，确保用户一定拿到结果
                    if (contractNeeded && !AgentTaskCompletion.hasDelivery(accumulated)) {
                        accumulated += "\n" + AgentTaskCompletion.fallbackDeliveryBlock(emptyList(), emptyList())
                        if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                            messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(text = accumulated)
                        }
                    }
                    break
                }
                // 调用开始：把「正在调用」状态行内联进当前 AI 消息正文（哨兵块随文本持久化，绝不丢失），
                // 让状态显示在调用真正发生的位置，而不是堆到所有结果之后。
                accumulated = AiChatManager.appendToolInline(accumulated, toolCall.tool, 0)
                // 多 Agent 强制验收跟踪:是否验过收 / 动过工作区 / 复验计数
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
                        AiChatManager.executeAgentTool(ctx, currentUserId, toolCall, onAppAction)
                    }
                } else {
                    AiChatManager.agentDeniedResult(ctx, currentUserId, toolCall.tool)
                }
                // 完成态：结果 ok 显示绿色「已调用」，否则红色「调用失败」（完成为彩色状态行，不再写纯文本）
                val sOk = try { org.json.JSONObject(result).optBoolean("ok") } catch (_: Exception) { false }
                // agent_review：把验收 Agent 的报告块写进消息流（可见的接手记录，随消息持久化）
                if (toolCall.tool == AiChatManager.TOOL_AGENT_REVIEW || toolCall.tool == AiChatManager.TOOL_AGENT_FORK)
                    (if (toolCall.tool == AiChatManager.TOOL_AGENT_REVIEW) AiChatManager.reviewReportBlock(result) else AiChatManager.forkReportBlock(result))?.let { blk ->
                    accumulated = accumulated + "\n" + blk
                }
                // 调用完成：把同名状态行原地替换为最终态（1 已调用 / 2 失败）
                // ask_user 完成：「问/答」记录直接嵌进状态块（点状态行展开查看）
                accumulated = AiChatManager.replaceToolInline(
                    accumulated, toolCall.tool, if (sOk) 1 else 2,
                    if (toolCall.tool == AiChatManager.TOOL_ASK_USER) AiChatManager.askUserRecordText(result) ?: "" else ""
                )
                if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) {
                    messages[aiMsgIdx] = (messages[aiMsgIdx] as ChatMsg).copy(
                        text = accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                    )
                }
                scheduleAiDebouncedSave()
                maybeScrollToBottom()
                // 工具完成后同步刷新 UI 模式状态（Plan 模式下 AI 可能已用 set_agent_mode 自动切换到 craft）
                agentMode = AiChatManager.getAgentMode(ctx, currentUserId)
                // 回喂历史：本轮助手原文（含已消费的工具块）+ 工具执行结果
                val anchorId2 = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) messages[aiMsgIdx].id else -1L
                insertAiFileCardIfGot(toolCall.tool, result, anchorId2)
                history.add("assistant" to lastRawFull)
                history.add("user" to "工具执行结果: $result")
                loopUsedTools = true
                    inlineToolCount++
                pendingToolRound = true
                    loopRounds++
            }
            // ===== 一次性收尾（仅正常完成时执行；出错/手动停止保留现场） =====
            if (!loopFailed && !loopManualStop) {
                aiWriting.value = false
                aiWritingConvId = -1L
                val stillHere = AiConversationSession.storageKey() == myKey
                val idx = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) aiMsgIdx else -1
                val multi = AiChatManager.getMultiOutput(ctx, currentUserId)
                val full = accumulated
                if (stillHere && idx >= 0) {
                    val finalReasoning = if (AiChatManager.getShowReasoning(ctx, currentUserId)) (messages[idx] as ChatMsg).reasoningText else ""
                    if (loopUsedTools) {
                        // 多轮工具：各轮正文已独立成气泡，工具卡片/状态行也已在各自位置就近插入；
                        // 收尾只更新最后一条气泡（去掉等待态、补齐时长），切忌用累积全文覆盖，否则会造成文本重复。
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
                                isNew = true, createdAt = System.currentTimeMillis() / 1000, reasoningText = finalReasoning,
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
                }
                if (stillHere) {
                    val snap = messages.filter { !it.isSystemNotice }.toList()
                    aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                } else {
                    // 已切走：把完整回复合并写回所属会话文件（aiMsgIdx 是 A 会话索引，不能碰当前内存）
                    val aiId = if (aiMsgIdx >= 0 && aiMsgIdx < messages.size) messages[aiMsgIdx].id else 0L
                    if (aiId != 0L) {
                        val finalId = aiId
                        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, myKey, finalId, text = full, thinkingSeconds = lastThinking, totalSeconds = lastTotal) } catch (_: Exception) {} }
                    }
                }
                if (mode == "official" && lastUsage > 0) {
                    AiChatManager.addOfficialUsage(ctx, currentUserId, lastUsage)
                    val finalUsed = baseUsed + lastUsage
                    val finalRemaining = (poolAtStart - lastUsage).coerceAtLeast(0)
                    aiUsageState.value = Pair(finalUsed, finalRemaining)
                    scope.launch {
                        AiChatManager.syncServerLimit(ctx, currentUserId)
                    }
                }
            }
            com.aurora.chat.AgentTaskGuard.exit(ctx)
        }
    }

    // 进入时从 SharedPreferences 恢复草稿
    LaunchedEffect(Unit) {
        val saved = ctx.getSharedPreferences("aurora_drafts", Context.MODE_PRIVATE)
            .getString(draftKey, "") ?: ""
        inputText = saved
    }

    // 平台头像（所有对话共用）
    var myAvatarBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // AI 对话专用自定义头像（与平台头像完全隔离）
    var aiMyAvatarBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

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
                            createdAt = msg.createdAt,
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
    var destroyedFlashIds by remember {
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
    // 保存已销毁闪照 ID 到文件 + 服务端双写
    fun saveDestroyedFlash(id: Long) {
        destroyedFlashIds = destroyedFlashIds + id
        try {
            val flashFile = java.io.File(ctx.filesDir, "flash_destroyed_${currentUserId}.json")
            val arr = org.json.JSONArray(destroyedFlashIds.toList())
            flashFile.writeText(arr.toString())
        } catch (_: Exception) {}
        // 同步到服务端（防止清缓存重看）
        scope.launch {
            try { com.aurora.chat.data.api.AuroraApi.recordFlashView(id) } catch (_: Exception) {}
        }
    }

    // 【Fix 2】点击 "+" 时延迟后平滑滚动到底部
    LaunchedEffect(showPlusDialog) {
        if (showPlusDialog && messages.isNotEmpty()) {
            try {
                delay(110)
                listState.animateScrollToItem(0)
                delay(50)
                listState.dispatchRawDelta(1f)
            } catch (_: Exception) { }
        }
    }

    // 当前打开弹窗的消息 ID（0 表示无弹窗），用于全局关闭
    var openMenuMsgId by remember { mutableStateOf(0L) }
    // 点击空白区域时递增，用于通知所有气泡退出选择模式
    var clearSelectionKey by remember { mutableStateOf(0) }
    // 多选：进入多选模式、已选消息 id（不可变 Set，切换/删除都换引用以触发重组）、删除确认
    var multiSelectMode by remember { mutableStateOf(false) }
    val selectedMsgIds = remember { mutableStateOf(setOf<Long>()) }
    var multiDeleteConfirm by remember { mutableStateOf(false) }
    // 「选择到这里」：锚点消息 id 与按钮出现方向（+1 上滑看更早→顶侧, -1 下滑看更新→底侧, 0 不显示）
    var selectAnchorMsgId by remember { mutableStateOf(-1L) }
    var selectToHereDir by remember { mutableStateOf(0) }
    fun exitMultiSelect() {
        multiSelectMode = false
        selectedMsgIds.value = emptySet()
        selectAnchorMsgId = -1L
        selectToHereDir = 0
    }
    // 高亮消息 ID（点击引用胶囊后滚动到目标消息时闪烁）
    var highlightedMsgId by remember { mutableStateOf(0L) }
    // 图片全屏预览：当前对话全部图片 URL 列表（懒加载）+ 点击起始索引，支持左右滑动切换
    var imagePreviewUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var imagePreviewIndex by remember { mutableStateOf(0) }
    var showSaveImageDialog by remember { mutableStateOf(false) } // 长按图片 → 保存相册确认
    var videoPlayUrl by remember { mutableStateOf("") }  // 应用内视频播放
    // 文本文件预览（TXT/MD/LOG 等）：target 保存内容（退出动画期间仍保留），visible 控制滑入/滑出
    var filePreviewTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var filePreviewVisible by remember { mutableStateOf(false) }
    // 非预览类文件（APK 等）下载确认弹窗：url, fileName, ext
    var fileDownloadConfirm by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    // JPG/PNG 等图片文件：保存到本地相册确认弹窗：url, fileName, ext
    var imageSaveConfirm by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    // 把聊天里的图片保存到本地相册（Pictures/AuroraChat）
    fun saveChatImageToGallery(url: String) {
        scope.launch {
            val ok = com.aurora.chat.util.GallerySaver.saveImage(ctx, url)
            android.widget.Toast.makeText(ctx, if (ok) "已保存到相册" else "保存失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 把聊天里的文件加入下载管理并开始下载（跳转至下载管理）
    fun startChatFileDownload(url: String, fileName: String) {
        val name = fileName.ifEmpty { "文件下载" }
        com.aurora.chat.ui.tools.DownloadManager.addCommunityDownload(
            name,
            fileName.ifEmpty { "download_${System.currentTimeMillis()}" },
            url,
            com.aurora.chat.R.drawable.ic_app_icon
        )
        com.aurora.chat.ui.tools.DownloadManager.startDownloads(ctx)
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
                        toUserId = newMsg.toUserId, targetName = newMsg.targetName
                    )
            }
            newMsg
        }
    }


    var deleteConfirmMsgId by remember { mutableStateOf(0L) }

    // 已删除消息 ID 集合（持久化到文件，避免 SP 大字符串读写）
    var deletedMessageIds by remember { mutableStateOf(
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
    val focusManager = LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    var showGroupSettings by remember { mutableStateOf(false) }
    var showFriendSettings by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var profileUserId by remember { mutableStateOf(0L) }
    var profileUserName by remember { mutableStateOf("") }
    // 入群邀请应答页：非空时全屏显示
    var inviteRespond by remember { mutableStateOf<InviteGroupCardData?>(null) }
    var showAiUsage by remember { mutableStateOf(false) }
    // 选择免费 API（由用户贡献、开发者审核通过）：顶层全屏，避免被 AI 使用面板（Dialog 模态层）遮挡
    var showFreeApiSelect by remember { mutableStateOf(false) }
    // AI 模式选择弹窗（智能/图片/视频/对话）：仅 第三方 / 个人 API 生效
    var showModeSelectDialog by remember { mutableStateOf(false) }
    // AI 设置弹窗状态（合并「上传/恢复头像」与「设置/恢复名称」）
    var showAiSettingsDialog by remember { mutableStateOf(false) }
    var showAgentToolsMenu by remember { mutableStateOf(false) } // Agent「访问控制」菜单（完全访问红色警告 / 手动审批）
    var showReverseInfo by remember { mutableStateOf(false) }
    var showPlaceholderConvert by remember { mutableStateOf(false) }
    // 「AI 设置」里「开启新对话」的确认弹窗开关（与主界面「开启新对话」弹窗走同一安全清空序列）
    var showAiNewConfirm by remember { mutableStateOf(false) }
    var showAiNameInput by remember { mutableStateOf(false) }
    var aiNameInput by remember { mutableStateOf("") }

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

    // 我方自定义头像（DeepSeek 对话专用）：选取图片并保存到本地，在 DeepSeek 对话中替换用户头像
    // 使用 getAiMyAvatarFile 路径，与平台头像隔离，不污染 UserAvatar 组件
    var myCropUri by remember { mutableStateOf<Uri?>(null) }
    var showMyCrop by remember { mutableStateOf(false) }
    val myAvatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            myCropUri = uri
            showMyCrop = true
        }
    }

    // 裁剪弹窗：选图后弹出，裁剪完成再保存
    if (showMyCrop && myCropUri != null) {
        CropPreviewDialog(
            imageUri = myCropUri!!, context = ctx,
            onCropConfirm = { cropped ->
                showMyCrop = false
                myCropUri = null
                aiMyAvatarBitmap = cropped
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val file = LocalStorage.getAiMyAvatarFile(ctx, currentUserId)
                            java.io.FileOutputStream(file).use { out ->
                                cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            }
                        } catch (_: Exception) {}
                    }
                }
            },
            onDismiss = { showMyCrop = false; myCropUri = null }
        )
    }

    // 好友资料返回：先回对话，不直接退出聊天板块（补充缺失的 BackHandler）
    BackHandler(enabled = showFriendSettings) { showFriendSettings = false }
    // 群设置返回：先回对话，不直接跳到聊天板块
    BackHandler(enabled = showGroupSettings) { showGroupSettings = false }
    // 用户资料返回：先回对话，不直接跳到聊天板块
    BackHandler(enabled = showProfile && profileUserId > 0) { showProfile = false }
    // 多选状态下返回键直接退出多选
    BackHandler(enabled = multiSelectMode) { exitMultiSelect() }

    val animPrefs = LocalStorage.getChatPrefs(ctx)
    val animMode by remember {
        derivedStateOf { AnimationMode.entries[animPrefs.getInt("animation_mode", 2)] }
    }
    val advancedAnim by remember {
        derivedStateOf { animPrefs.getBoolean("advanced_anim", false) }
    }

    // 带时间戳的最近发送文本集合（防止 TCP/polling 触发 loadMessages 时重复添加自己发出的消息）
    // 用时间戳确保：消息发送后 30 秒内不被服务端同步重复添加
    val recentlySentMessages = remember { mutableMapOf<String, Long>() }

    // ── 入群申请相关状态（提升到 Column 外部，避免 AnimatedVisibility 内引用不到）──
    var isAdminOrOwner by remember { mutableStateOf(false) }
    var pendingCount by remember { mutableIntStateOf(0) }
    // 非群成员（含「入群需审核」待审核态）：user_role 为空
    var isNotGroupMember by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 平台头像（所有对话共用）
            val platformFile = LocalStorage.getMyAvatarFile(ctx, currentUserId)
            if (platformFile.exists()) {
                myAvatarBitmap = android.graphics.BitmapFactory.decodeFile(platformFile.absolutePath)
            }
            // AI 对话专用自定义头像（完全隔离）
            val aiCustomFile = LocalStorage.getAiMyAvatarFile(ctx, currentUserId)
            if (aiCustomFile.exists()) {
                aiMyAvatarBitmap = android.graphics.BitmapFactory.decodeFile(aiCustomFile.absolutePath)
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
    var initialLoadInProgress by remember { mutableStateOf(false) }
    var hasMoreMessages by remember { mutableStateOf(true) }
    var loadMoreProgress by remember { mutableIntStateOf(0) } // 0=空闲, 1=加载中, 2=无更多

        // 后台媒体上传事件：压缩中/上传中更新进度与阶段，完成后把占位(本地 id)替换为真实消息
        LaunchedEffect(Unit) {
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
                    is com.aurora.chat.data.upload.UploadEvent.Succeeded -> {
                        if (ev.friendId == friendId) {
                            val idx = messages.indexOfFirst { it.id == ev.localId }
                            if (idx >= 0) {
                                messages[idx] = ChatMsg.create(serverId = ev.serverId, text = "", isMine = true, fromUserId = currentUserId, isNew = true, mediaType = ev.mediaType, mediaUrl = ev.mediaUrl, flashDuration = ev.flashDuration)
                            }
                            val previewText = when (ev.mediaType) { "image" -> "[图片]"; "video" -> "[视频]"; "file" -> "[文件]"; "voice" -> "[语音]"; "transfer" -> "[转账]"; else -> "[消息]" }
                            com.aurora.chat.ui.viewmodel.ChatViewModel.updateConversationPreview(ev.friendId, previewText, System.currentTimeMillis() / 1000)
                        }
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
                val idx = messages.indexOfFirst { m -> m.id == r.aiMsgId }
                if (idx < 0) return@collect
                val showReasoning = AiChatManager.getShowReasoning(ctx, currentUserId)
                val cur = messages[idx] as ChatMsg
                // 任务已结束(正常完成 done / 出错 error)：把最终内容合并进消息并退出等待态，
                // 兜底「离开对话期间磁盘异步落盘尚未完成/失败」的场景——修复切回时读到旧占位、
                // 或已生成内容整段消失。onDone/onError 已把最新文本(出错时含「生成中断」提示)
                // 写入 aiReplyState.text，这里原样合并即可。
                if (r.done || r.error != null) {
                    val doneText = r.text.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
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
                    }
                    return@collect
                }
                val newText = r.text.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                    .ifEmpty { if (showReasoning) "" else AI_THINKING }
                // 尚未产生任何思考/输出 → 仍在「等待模型响应」空窗；否则立即退出
                val waiting = r.text.isBlank() && r.reasoningText.isBlank()
                if (cur.text != newText || cur.reasoningText != (if (showReasoning) r.reasoningText else "") || cur.aiWaiting != waiting) {
                    messages[idx] = cur.copy(
                        text = newText,
                        reasoningText = if (showReasoning) r.reasoningText else "",
                        aiWaiting = waiting
                    )
                }
            }
        }

        // 加载消息（热缓存 + 网络刷新）

    val selfChatRefresh by com.aurora.chat.ui.viewmodel.ChatViewModel.selfChatRefreshKey.collectAsState()
    // ── AI 多对话状态：进入/切换/新建会话用 ──
    val aiConvs = remember(friendId) { mutableStateListOf<AiConvMeta>() }
    var aiConvReloadTick by remember(friendId) { mutableStateOf(0) }
    var showAiConvDrawer by remember(friendId) { mutableStateOf(false) }
    var activeAiConvId by remember(friendId) { mutableStateOf(0L) }
    // 进入 AI 对话：加载会话列表并复位当前会话 id（会话元数据存 SharedPreferences）
    LaunchedEffect(friendId) {
        if (friendId == AI_CHAT_ID) {
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

    LaunchedEffect(friendId, selfChatRefresh, aiConvReloadTick, activeAiConvId) {
        if (isLocalChat(friendId)) {
            // AI 多对话：进入/切换会话时把当前会话 id 写入 session，加载对应会话的消息
            if (friendId == AI_CHAT_ID && activeAiConvId > 0L) {
                switchAiConversation(ctx, activeAiConvId)
            }
            // 本地对话（自己 / AI）：从 JSON 文件加载，消息 ID 为时间戳*10000+随机，重启不丢失
            val key = localChatKey(friendId)
            isLoadingMessages = true
            val deletedNow = deletedMessageIds.toSet()   // 快照，供 IO 线程读取
            // 仅自己对话首屏显示本地提示，AI 保持空白
            val noticeMsg = if (friendId == 0L) {
                val noticeShown = java.io.File(ctx.cacheDir, "self_chat_notice_${currentUserId}.txt").exists()
                if (!noticeShown) {
                    try { java.io.File(ctx.cacheDir, "self_chat_notice_${currentUserId}.txt").writeText("1") } catch (_: Exception) {}
                    ChatMsg.create(
                        text = "此对话为本地个人对话，对话内容全部存在本地，清理数据后此对话内的数据也会随之清理",
                        isMine = false, fromUserId = 0, isSystemNotice = true, isNew = false
                    )
                } else null
            } else null
            // 【性能】AI 对话的 JSON 文件可能巨大（多轮长输出可达数百 KB）：文件读取 + JSON 解析 + 逐条解密
            // 全部移到 IO 线程，主线程只做状态写入，避免进入 AI 对话时卡顿
            val list = withContext(Dispatchers.IO) {
                val out = mutableListOf<ChatMsg>()
                if (noticeMsg != null) out.add(noticeMsg)
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
                    for (item in r.data!!) {
                        // 已删除的通知（即便服务端仍有记录）也跳过，避免重新进入又出现
                        if (item.id in deletedMessageIds) continue
                        val text = if (item.tag == "转账") com.aurora.chat.ui.chat.formatTransferNotice(item.desc)
                                   else com.aurora.chat.ui.chat.formatOrderNotice(item.title, item.desc, item.tag, item.orderId)
                        if (existingTexts.add(text)) {
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
                    val membersResult = AuroraApi.getGroupMembers(gid)
                    if (membersResult.success && membersResult.data != null) {
                        val members = mutableSetOf<String>()
                        val memberIdMap = mutableMapOf<String, Long>()
                        val atList = mutableListOf<AtMember>()
                        for (i in 0 until membersResult.data!!.length()) {
                            val memberObj = membersResult.data!!.getJSONObject(i)
                            val userId = memberObj.optLong("user_id", 0)
                            val username = memberObj.optString("username", "")
                            val role = memberObj.optString("role", "")
                            if (username.isNotEmpty()) {
                                members.add(username)
                                memberIdMap[username] = userId
                                atList.add(AtMember(userId, username, role == "owner"))
                            }
                        }
                        groupMemberNames = members
                        groupMemberIdMap = memberIdMap
                        groupAtMembers = atList
                    }
                } catch (_: Exception) {}
            }
        }

        // 第二步：优先从热缓存显示（永不过期），再读磁盘，最后等网络

        val cacheKey = "$currentUserId:$friendId"
        val cached = messageHotCache[cacheKey]
        if (cached != null) {
            // 有热缓存 → 立即显示，零延迟
            messages.clear()
            messages.addAll(cached.messages as List<IMessageRef>)
            // 旧版本可能缓存过倒序消息，热缓存必须按 id 升序重排，保证最旧在上、最新在下
            messages.sortBy { it.id }
            isLoadingMessages = false
            if (messages.isNotEmpty()) {
                listState.scrollToItem(0)
            }
            scrollReady = true
        } else {
            // 无热缓存 → 读磁盘缓存；磁盘读取放到后台线程，避免进入大群时卡住主线程
            val diskMsgs = withContext(Dispatchers.Default) {
                com.aurora.chat.data.repository.LocalMessageStore.loadMessages(ctx, friendId)
            }
            if (diskMsgs.isNotEmpty()) {
                initialLoadInProgress = true
                val diskData = withContext(Dispatchers.Default) {
                    val data = diskMsgs.map { m ->
                        MessageData(
                            id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
                            content = m.content, createdAt = m.createdAt,
                            isMine = m.fromUserId == currentUserId,
                            senderName = m.fromUserName,
                            isRevoked = m.isRevoked != 0,
                            // 还原系统通知标记：优先用落盘字段；旧磁盘数据无此字段时，用文案兜底
                            // （召回/拍一拍通知内容特征明显），避免冷启动/缓存失效时先当气泡、后被 syncMessages 矫正的闪烁
                            isSystemNotice = m.isSystemNotice
                                || m.content.contains("撤回了一条消息")
                                || m.content.contains("拍了拍"),
                            replyToText = m.replyToText, replyToSender = m.replyToSender,
                            replyToId = m.replyToId, mediaType = m.mediaType,
                            mediaUrl = resolveMediaUrl(m.mediaUrl), flashDuration = m.flashDuration
                        )
                    }
                    // 诊断：磁盘缓存重载后扫描媒体消息（后台线程，避免主线程 O(n) 日志）
                    data.forEach { m ->
                        if (m.mediaType.isNotEmpty()) {
                            val status = if (m.mediaUrl.isEmpty()) "URL为空(空气泡)" else "OK"
                            MediaDebug.log(ctx, "LOAD_DISK", "id=${m.id} mediaType=${m.mediaType} mediaUrl=${m.mediaUrl} -> $status")
                        }
                    }
                    // store 全量写入 + 窗口化暴露（后台线程，彻底消除主线程 O(n) 建索引）
                    adapter.loadWindowed(data, INITIAL_WINDOW)
                    // 媒体 URL 落库（后台线程写 ConcurrentHashMap，零主线程成本）
                    MediaStore.seed(data.filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
                        .map { it.id to (it.mediaType to it.mediaUrl) })
                    data
                }
                initialLoadInProgress = false
                syncMessages()
                isLoadingMessages = false
                if (messages.isNotEmpty()) {
                    listState.scrollToItem(0)
                }
                scrollReady = true
            } else {
                isLoadingMessages = true
            }
        }

        // 第三步：网络刷新（同步在线时间 + 拉取最新消息）
        try {
            val ot = OnlineTimeTracker.getCurrentTotalSeconds(ctx)
            AuroraApi.syncUserStats(ot, 0)
        } catch (_: Exception) {}
        // 本地已加载的最大消息 id，作为增量游标（after_id）：只拉比本地新的，不重复拉旧
        val afterId = if (messages.isNotEmpty()) {
            (messages.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L)
        } else 0L
        val result = if (isGroupChat(friendId)) {
            com.aurora.chat.data.repository.ChatRepository.getGroupMessages(friendId, 0, 0, afterId)
        } else {
            com.aurora.chat.data.repository.ChatRepository.getMessages(currentUserId, friendId, 0, 0, afterId)
        }
        if (result.success && result.data != null) {
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
                    val isSystemNotice = isRevoked == 1 || isConsentNotice || m.broadcastTaskId != 0L
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
                        m.flashDuration == -1 -> parsePokeText(m, currentUserId, currentUserName)
                        else -> m.content
                    }
                    // 服务器返回空 mediaUrl 时，优先用本地已验证可用的完整 URL 回填；否则做相对/内网 host 解析
                    val finalMediaUrl = if (m.mediaUrl.isEmpty()) (existingMediaByMsgId[m.id] ?: "") else resolveMediaUrl(m.mediaUrl)
                    MessageData(
                        id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
                        content = text, createdAt = m.createdAt, isMine = m.fromUserId == currentUserId,
                        senderName = m.fromUserName,
                        isRevoked = isRevoked != 0, isSystemNotice = isSystemNotice || m.flashDuration == -1,
                        replyToText = m.replyToText, replyToSender = m.replyToSender, replyToId = m.replyToId,
                        mediaType = m.mediaType, mediaUrl = finalMediaUrl, flashDuration = m.flashDuration,
                        broadcastTaskId = m.broadcastTaskId
                    )
                }
            }
            // 服务端返回顺序不保证（私信倒序、群正序），统一按 id 升序整理后再入库，
            // 保证全量加载与增量追加都呈现"最旧在上、最新在下"，避免顺序颠倒/乱跳
            val orderedData = dataList.sortedBy { it.id }
            if (messages.isNotEmpty()) {
                // 已有本地数据：只增量追加服务端新消息；若无新消息则保留现有列表，
                // 绝不 loadAll(empty) 清空（否则重进已缓存对话会瞬间"闪掉"变"暂无消息"）
                if (orderedData.isNotEmpty()) {
                    adapter.mergeServerMessages(orderedData, currentUserId, currentUserName)
                }
            } else {
                // 首次加载 / 无本地数据：全量替换（含空列表，正确显示"暂无消息"）
                // 窗口化加载：store 写入全部，但首屏只暴露最近 INITIAL_WINDOW 条；
                // 后台线程建索引，避免首次进入大群（无磁盘缓存）时主线程 O(n) 卡顿
                initialLoadInProgress = true
                withContext(Dispatchers.Default) {
                    adapter.loadWindowed(orderedData, INITIAL_WINDOW)
                }
                initialLoadInProgress = false
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
                    adapter.allRefs().map { ref ->
                        com.aurora.chat.data.api.MessageInfo(
                            id = ref.id, fromUserId = ref.fromUserId, toUserId = ref.toUserId,
                            content = ref.text, createdAt = ref.createdAt, fromUserName = ref.senderName,
                            isRevoked = ref.isRevoked, isSystemNotice = ref.isSystemNotice, replyToText = ref.replyToText,
                            replyToSender = ref.replyToSender, replyToId = ref.replyToId, mediaType = ref.mediaType,
                            mediaUrl = ref.mediaUrl, flashDuration = ref.flashDuration, targetName = ref.targetName
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
            // 合并导入的消息（好友对话服务端不保存导入历史，重载会丢失，故本地合并；不往服务端发送、不骚扰对方）
            if (!isLocalChat(friendId)) {
                val importedMsgs = loadImportedMessages(ctx, currentUserId, friendId)
                if (importedMsgs.isNotEmpty()) {
                    val existingKeys = messages.map { it.createdAt to it.text }.toSet()
                    val toAdd = importedMsgs.filter { (it.createdAt to it.content) !in existingKeys }
                    if (toAdd.isNotEmpty()) {
                        messages.addAll(toAdd.map { md ->
                            ChatMsg.create(
                                text = md.content, isMine = md.isMine, fromUserId = md.fromUserId,
                                senderName = md.senderName, createdAt = md.createdAt,
                                isRevoked = if (md.isRevoked) 1 else 0,
                                isSystemNotice = md.isSystemNotice,
                                mediaType = md.mediaType, mediaUrl = md.mediaUrl,
                                serverId = md.id
                            ).withKnownMediaType() as IMessageRef
                        })
                        messages.sortBy { if (it.createdAt == 0L) Long.MAX_VALUE else it.createdAt }
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
        isLoadingMessages = false
        scrollReady = true
        // 加载失败且没有缓存数据时提示用户（非成员态由占位界面处理，不弹网络错误）
        if (!result.success && messages.isEmpty() && !isNotGroupMember) {
            Toast.makeText(ctx, "消息加载失败，请检查网络", Toast.LENGTH_SHORT).show()
            Log.w("ChatConv", "初始加载失败: ${result.message}")
        }
    }
    // 加载更多历史消息（无限滚动，始终保留最新 pageSize 条在内存中用于展示）
    fun triggerLoadMore(offset: Int) {
        scope.launch {
            if (loadMoreProgress != 0) return@launch
            loadMoreProgress = 1
            if (isLocalChat(friendId)) { loadMoreProgress = 0; return@launch }
            val result = if (isGroupChat(friendId)) {
                com.aurora.chat.data.repository.ChatRepository.getGroupMessages(friendId, pageSize, offset)
            } else {
                com.aurora.chat.data.repository.ChatRepository.getMessages(currentUserId, friendId, pageSize, offset)
            }
            if (result.success && result.data != null && result.data!!.isNotEmpty()) {
                val older: List<IMessageRef> = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    result.data!!.map { m -> ChatMsg.create(text = m.content, isMine = m.fromUserId == currentUserId,
                        fromUserId = m.fromUserId, serverId = m.id, isNew = false, senderName = m.fromUserName,
                        createdAt = m.createdAt, isRevoked = m.isRevoked,
                        isSystemNotice = m.isRevoked == 1, replyToText = m.replyToText,
                        replyToSender = m.replyToSender, replyToId = m.replyToId, mediaType = m.mediaType,
                        mediaUrl = m.mediaUrl, flashDuration = m.flashDuration, toUserId = m.toUserId, targetName = "").withKnownMediaType() as IMessageRef
                    }
                }
                // 在列表顶部插入历史消息（智能缓存：超出上限淘汰最旧的）
                // 服务端可能返回倒序，先按 id 升序整理，确保插入后"最旧在上"
                val orderedOlder = older.sortedBy { it.id }
                val MAX_CACHED = 500
                if (older.size < pageSize) hasMoreMessages = false
                val idx = messages.indexOfFirst { it.id == orderedOlder.last().id }
                if (idx < 0) {
                    messages.addAll(0, orderedOlder)
                    // 仅当总数超出上限时淘汰最旧的消息（不会影响刚加载的历史")
                    if (messages.size > MAX_CACHED) {
                        val excess = messages.size - MAX_CACHED
                        messages.removeRange(0, excess)
                    }
                }
            } else {
                hasMoreMessages = false
            }
            loadMoreProgress = 0
        }
    }

    /**
     * 从已读入内存 store 的更旧消息中预加载一批到列表顶部（零网络、零重复）。
     * store 中保留着磁盘/首屏已全量读入的全部消息，仅 _indices 窗口化暴露最近若干条；
     * 滚到顶时把更旧的一段补进窗口并维持滚动位置，避免大群一次性全量渲染卡顿。
     */
    fun prependOlderFromStore() {
        val added = adapter.prependOlderFromStore(DISK_PAGE)
        if (added <= 0) return
        val prevFirst = listState.firstVisibleItemIndex
        val prevOffset = listState.firstVisibleItemScrollOffset
        syncMessages()
        // 预加载后原首条下标后移 added 位，scrollToItem 维持视口不跳
        scope.launch { runCatching { listState.scrollToItem(prevFirst + added, prevOffset) } }
        // 同步更旧消息的媒体 URL 到 MediaStore，防止上滑后历史图片变空气泡
        val newlySeeded = messages.take(added)
            .filter { it.mediaType.isNotEmpty() && it.mediaUrl.isNotEmpty() }
            .map { it.id to (it.mediaType to it.mediaUrl) }
        if (newlySeeded.isNotEmpty()) MediaStore.seed(newlySeeded)
    }

    // 滚动到顶部时加载更多：优先从内存 store 预加载已读入的更旧消息（零网络、零重复），
    // store 用尽后再向服务端分页拉取（复用既有 triggerLoadMore，offset 用全量条数避免重复）
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                // reverseLayout：最旧消息在 index 高的一端（顶部）。滚到顶=firstVisibleItemIndex 接近 totalItemsCount-1。
                val atTop = index >= (listState.layoutInfo.totalItemsCount - 2).coerceAtLeast(0)
                if (atTop && loadMoreProgress == 0 && !isLoadingMessages && messages.isNotEmpty()) {
                    if (adapter.hasOlderInStore()) {
                        prependOlderFromStore()
                    } else if (hasMoreMessages) {
                        triggerLoadMore(messages.size)
                    }
                }
            }
    }
    // （scrollReady 已在初始加载 LaunchedEffect 中设置，独立 LaunchedEffect 已移除避免滚动竞争）
    // 等 LazyColumn 布局完成后滚动到底部，同时支持缓存回填
    // （已合并到消息加载 LaunchedEffect 中，避免独立 LaunchedEffect 造成的闪屏）


    // TCP 推送触发即时刷新 — 使用 adapter.mergeServerMessages 增量更新
    LaunchedEffect(Unit) {
            ChatViewModel.newMessageReceived.collect {
            delay(200L) // 等 200ms 让服务端写入完成
            if (isLocalChat(friendId) || isNotificationChat(friendId)) return@collect
            // 初始化（磁盘窗口化建索引）进行中时跳过增量合并：store 正被后台线程构建，
            // 并发写入会导致数据错乱；网络刷新会补拉期间新增，不会漏消息
            if (initialLoadInProgress) return@collect
            // 只拉比本地新的消息（after_id 增量），避免每次推送都全量拉取
            val afterId = (messages.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L)
            val result = if (isGroupChat(friendId)) {
                com.aurora.chat.data.repository.ChatRepository.getGroupMessages(friendId, 0, 0, afterId)
            } else {
                com.aurora.chat.data.repository.ChatRepository.getMessages(currentUserId, friendId, 0, 0, afterId)
            }
            if (result.success && result.data != null && result.data!!.isNotEmpty()) {
                val serverData = result.data!!
                // 增量结果只含比本地新的消息；末条仍 <= 本地末条则视为无变化，直接跳过
                if (serverData.last().id <= (messages.lastOrNull()?.id ?: 0L)) return@collect
                // 在 IO 线程转换 MessageData
                val dataList = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    serverData.map { m ->
                        val isRevoked = m.isRevoked
                        val isSystemNotice = isRevoked == 1 || m.flashDuration == -1 || m.broadcastTaskId != 0L
                        MessageData(
                            id = m.id, fromUserId = m.fromUserId, toUserId = m.toUserId,
                            content = m.content, createdAt = m.createdAt,
                            isMine = m.fromUserId == currentUserId, senderName = m.fromUserName,
                            isRevoked = isRevoked != 0,
                            isSystemNotice = isSystemNotice, replyToText = m.replyToText,
                            replyToSender = m.replyToSender, replyToId = m.replyToId,
                            mediaType = m.mediaType, mediaUrl = m.mediaUrl,
                            flashDuration = m.flashDuration, broadcastTaskId = m.broadcastTaskId
                        )
                    }
                }
                val hasNew = adapter.mergeServerMessages(dataList, currentUserId, currentUserName)
                if (hasNew) {
                    syncMessages()
                }
                // 从 TCP 推送数据更新对话列表预览（支持双向实时）
                val lastServerMsg = serverData.lastOrNull()
                if (lastServerMsg != null) {
                    // 私聊 E2EE 密文在展示为会话列表预览前解密，与对话内明文保持一致
                    val rawContent = if (friendId > 0 && com.aurora.chat.CryptoUtil.isEncrypted(lastServerMsg.content)) {
                        try { com.aurora.chat.CryptoUtil.decrypt(lastServerMsg.content) }
                        catch (_: Exception) { "[端到端加密消息]" }
                    } else lastServerMsg.content
                    val preview = if (lastServerMsg.isRevoked > 0) {
                        "${lastServerMsg.fromUserName} 撤回了一条消息"
                    } else if (lastServerMsg.flashDuration == -1) {
                        parsePokeText(lastServerMsg, currentUserId, currentUserName)
                    } else if (rawContent.isNotEmpty()) {
                        rawContent
                    } else when (lastServerMsg.mediaType) {
                        "image" -> "[图片]"
                        "video", "mp4" -> "[视频]"
                        "file" -> "[文件]"
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
                // 用户靠近底部时自动滚动到最新消息
                if (messages.isNotEmpty()) {
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    // reverseLayout：靠近底部 = 可视区最底部一条 index 接近 0
                    if (lastVisible <= 2) {
                        delay(50)
                        listState.scrollToItem(0)
                    }
                }
            }
        }
    }

    // 即时处理消息撤回 — 使用 adapter.markRevoked 原地更新数组
    LaunchedEffect(Unit) {
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
                        syncMessages()
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
            if (com.aurora.chat.PendingBroadcastDeleted.hasUpdate) {
                com.aurora.chat.PendingBroadcastDeleted.hasUpdate = false
                val bid = com.aurora.chat.PendingBroadcastDeleted.taskId
                if (bid > 0L) {
                    adapter.removeByPredicate { it.broadcastTaskId == bid }
                    syncMessages()
                }
            }
            // 广播撤回全部：移除该 task_id 下的所有广播
            if (com.aurora.chat.PendingBroadcastRecalled.hasUpdate) {
                com.aurora.chat.PendingBroadcastRecalled.hasUpdate = false
                val btid = com.aurora.chat.PendingBroadcastRecalled.taskId
                if (btid > 0L) {
                    adapter.removeByPredicate { it.broadcastTaskId == btid }
                    syncMessages()
                }
            }
            // 服务端删除整个会话 → 本地同步清空（服务器→本地删除链路）
            // 无论是否正在查看该会话都先清理本地落盘与缓存，避免「推送到达时不在该会话」导致事件被丢弃而漏删
            if (com.aurora.chat.PendingConversationDeleted.hasUpdate) {
                com.aurora.chat.PendingConversationDeleted.hasUpdate = false
                val did = com.aurora.chat.PendingConversationDeleted.friendId
                if (did > 0L) {
                    // 本地存储 + 内存缓存一律清理（幂等：重复执行无害，已撤回消息只是同文件中的一行，删一次或删多次结果一致）
                    com.aurora.chat.data.repository.LocalMessageStore.deleteConversation(ctx, did)
                    companionMessageCache.remove(did)
                    com.aurora.chat.ui.viewmodel.ChatViewModel.invalidateHotCache(currentUserId, did)
                    if (did == friendId) {
                        // 当前正在查看该会话 → 同步清空内存列表与适配器（再次进入会重新拉取，结果为空）
                        messages.clear()
                        adapter.removeByPredicate { true }
                        syncMessages()
                        com.aurora.chat.ui.viewmodel.ChatViewModel.updateConversationPreview(did, "", System.currentTimeMillis() / 1000)
                    }
                }
            }
        }
    }

    // 键盘弹起时：若用户本就在底部，则把视角重新贴到底部（长消息也不跳到顶部，且不打扰上滑看历史的用户）
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty() && atBottomState.value) {
            try { listState.scrollToItem(0) } catch (_: Exception) {}
        }
    }

    // 判断是否需要显示"滚动到底部"按钮
    // 智能逻辑：只有用户往上面滑到一定距离后，再往下滑（判定想返回底部）才显示
    var scrollDirectionIsDown by remember { mutableStateOf(false) } // true=正在往下滑, false=正在往上滑
    var lastTrackedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex
        }.collect { index ->
            val delta = index - lastTrackedIndex
            // reverseLayout：index 增大=往更旧(top)方向滚 = 上滑；index 减小=往最新(bottom)方向滚 = 下滑
            if (delta < 0) scrollDirectionIsDown = true  // 正在下滑（回最新）
            else if (delta > 0) scrollDirectionIsDown = false // 正在上滑（看历史）
            lastTrackedIndex = index
        }
    }
    val shouldShowScrollDown = remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            // reverseLayout：贴底时最底部一条 index≈0；离开底部超过 12 条 = lastVisibleItem > 12
            // 条件是「离开底部 + 正在下滑（想返回最新）」
            totalItems > 0 && lastVisibleItem > 12 && scrollDirectionIsDown
        }
    }


    // ── 拆分超大 Composable：ChatConversationScreen 单函数字节码超过 JVM 64KB 单方法限制
    // （Method too large / Internal compiler error），将主界面 UI 抽取为局部 @Composable 函数，编译为独立方法。
    @Composable
    fun ChatMainUi() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // 在 Final 阶段消费所有未被子组件处理的事件，
                        // 防止点击空白区域穿透到外层遮罩导致退出聊天
                        awaitPointerEvent(PointerEventPass.Final)
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (friendId == AI_CHAT_ID) Color.White else Color(0xFFEDEDED))
                .imePadding() // 整体内容避开键盘
        ) {
            // 顶栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF9FAFB))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
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
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2937), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    // 右上角三横杠菜单（群聊→群设置，私信→下拉菜单含隐私控制）
                    var showSelfChatInfo by remember { mutableStateOf(false) }
                    if (!isNotificationChat(friendId)) {
                        // AI 对话：右上角「对话列表」按钮打开会话列表抽屉
                        if (friendId == AI_CHAT_ID) {
                            Spacer(Modifier.width(6.dp))
                            androidx.compose.foundation.layout.Box(modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFFEAF1FF))
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
                                    if (friendId == AI_CHAT_ID) {
                                        showAiUsage = true
                                    } else if (friendId == 0L) {
                                        showSelfChatInfo = true
                                    } else if (isGroupChat(friendId)) {
                                        showGroupSettings = true
                                    } else {
                                        // 私信：点击三横杠直接进入好友资料，不再经过下拉菜单
                                        showFriendSettings = true
                                    }
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("☰", fontSize = 22.sp, color = Color(0xFF1F2937))
                            }
                        }
                    }
                    // 个人对话介绍弹窗
                    if (showSelfChatInfo) {
                        Dialog(
                            onDismissRequest = { showSelfChatInfo = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Box(Modifier.fillMaxSize().background(Color(0x60000000)).clickable(
                                indication = null, interactionSource = remember { MutableInteractionSource() }
                            ) { showSelfChatInfo = false }, contentAlignment = Alignment.Center) {
                                Column(
                                    Modifier.fillMaxWidth(0.85f).background(Color.White, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 24.dp, vertical = 28.dp)
                                ) {
                                    Text("个人对话", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                                    Spacer(Modifier.height(12.dp))
                                    Text("• 这是个人对话（类似文件传输助手），仅你可见", fontSize = 14.sp, color = Color(0xFF6B7280))
                                    Spacer(Modifier.height(6.dp))
                                    Text("• 发出的消息只保存在本地，不会发送到服务器", fontSize = 14.sp, color = Color(0xFF6B7280))
                                    Spacer(Modifier.height(6.dp))
                                    Text("• 卸载应用或清理数据后，此对话内的所有消息将永久丢失", fontSize = 14.sp, color = Color(0xFF6B7280))
                                    Spacer(Modifier.height(6.dp))
                                    Text("• 删除消息后无法恢复，请谨慎操作", fontSize = 14.sp, color = Color(0xFF6B7280))
                                    Spacer(Modifier.height(20.dp))
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        Text("我知道了", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                            modifier = Modifier.clickable { showSelfChatInfo = false }.padding(horizontal = 16.dp, vertical = 8.dp))
                                    }
                                }
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
                // 高级选项：场景 / 角色设定 / AI 多次输出（离开面板即保存）
                var advScenario by remember(currentUserId) { mutableStateOf(AiChatManager.getScenario(ctx, currentUserId)) }
                var advMyRole by remember(currentUserId) { mutableStateOf(AiChatManager.getMyRole(ctx, currentUserId)) }
                var advAiRole by remember(currentUserId) { mutableStateOf(AiChatManager.getAiRole(ctx, currentUserId)) }
                var advMulti by remember(currentUserId) { mutableStateOf(AiChatManager.getMultiOutput(ctx, currentUserId)) }
                var showReasoning by remember(currentUserId) { mutableStateOf(AiChatManager.getShowReasoning(ctx, currentUserId)) }
                var showAdvancedOptions by remember { mutableStateOf(false) }
                // ── 对话导出 / 导入 ──
                var showExportDialog by remember { mutableStateOf(false) }
                var expUserAvatar by remember { mutableStateOf(true) }
                var expAiAvatar by remember { mutableStateOf(true) }
                var expName by remember { mutableStateOf(true) }
                // 导出前全文替换（隐私保护）：勾选后在下方填写「被替换名称 → 占位符」
                var expReplace by remember { mutableStateOf(false) }
                // 导出场景信息（场景 + 角色设定）
                var expScene by remember { mutableStateOf(true) }
                // 左侧默认：人物1/人物2（新增续人物3…）；右侧默认：男主/女主（新增续 X1、X2…）
                val replaceFroms = remember { mutableStateListOf("人物1", "人物2") }
                val replaceTos = remember { mutableStateListOf("男主", "女主") }
                // 导入预览弹窗：选完文件后展示文件名/大小，并可设置人物对换与占位符还原
                var showImportDialog by remember { mutableStateOf(false) }
                var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
                var importFileName by remember { mutableStateOf("") }
                var importFileSize by remember { mutableStateOf(0L) }
                var importSwap by remember { mutableStateOf(false) }
                var importMaps by remember { mutableStateOf(listOf<ImportMapItem>()) }
                // 导入场景信息（场景 + 角色设定）：勾选后替换当前设置，不勾选则保持现有设置
                var importScene by remember { mutableStateOf(true) }

                // ── AI 对话专属：时间点 / 确认发言 ──（已提升到 ChatConversationScreen 顶层，此处不再重复声明）


                // 上传头像到服务器，返回完整可访问 URL；失败返回空串（导出仍继续，不阻断）
                val tryUploadAvatar: suspend (Boolean, android.graphics.Bitmap?) -> String = { userCustom, bitmap ->
                    try {
                        val bytes: ByteArray? = if (userCustom) {
                            val f = LocalStorage.getMyAvatarFile(ctx, currentUserId)
                            if (f.exists()) f.readBytes() else null
                        } else {
                            bitmap?.let { bmp ->
                                val out = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                out.toByteArray()
                            }
                        }
                        if (bytes == null) {
                            if (userCustom) resolveMediaUrl("/api/avatar/$currentUserId") else ""
                        } else {
                            val res = com.aurora.chat.data.api.AuroraApi.uploadChatMedia(
                                bytes,
                                if (userCustom) "user_avatar.jpg" else "ai_avatar.png",
                                if (userCustom) "image/jpeg" else "image/png"
                            )
                            if (res.success && res.data != null) resolveMediaUrl(res.data!!.optString("url")) else ""
                        }
                    } catch (_: Exception) { "" }
                }

                // 从导入文件还原消息并追加到当前对话；swap=人物对换，maps=占位符→人物名称（仅勾选项生效）
                // swap 不再用于反转底层 isMine，改由确认导入时开启 reverseHistory 统一处理视角
                // swap：人物对换仅在导入时翻转本批消息数据（isMine + fromUserId 一起交换），不碰全局 reverseHistory
                val importConversationFromUri: suspend (android.net.Uri, Boolean, List<ImportMapItem>) -> Unit = { uri, swap, maps ->
                    run doImport@{
                        val jsonText: String? = try {
                            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        } catch (e: Exception) {
                            null
                        }
                        if (jsonText == null) {
                            Toast.makeText(ctx, "读取文件失败", Toast.LENGTH_SHORT).show()
                            return@doImport
                        }
                        try {
                            val root = org.json.JSONObject(jsonText)
                            val arr = root.optJSONArray("messages")
                            if (arr == null) {
                                Toast.makeText(ctx, "文件内容无效", Toast.LENGTH_SHORT).show()
                                return@doImport
                            }
                            // 占位符→人物名称 还原映射（仅勾选且目标非空）
                            // 勾选框已移除：占位符与名称都非空即参与还原
                            val rev = maps.filter { it.placeholder.isNotBlank() && it.target.isNotBlank() }.associate { it.placeholder to it.target }
                            var nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1
                            val imported = mutableListOf<ChatMsg>()
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                var text = o.optString("text", "")
                                for ((ph, name) in rev) text = text.replace(ph, name)
                                var replyToText = o.optString("reply_to_text", "")
                                for ((ph, name) in rev) replyToText = replyToText.replace(ph, name)
                                val origIsMine = o.optBoolean("is_mine", false)
                                val origFrom = o.optLong("from_user_id", 0L)
                                // 人物对换：导入时直接翻转底层数据（isMine 与 fromUserId 同步交换）。
                                // 头像按 fromUserId 取、左右按 isMine 取，二者一起翻才不会"双方都是我的头像"；
                                // 且只影响本批导入消息，不碰全局 reverseHistory，避免污染整段对话/卡死。
                                val newIsMine = if (swap) !origIsMine else origIsMine
                                val newFrom = if (swap) (if (origFrom == currentUserId) friendId else currentUserId) else origFrom
                                val newSender = if (swap) (if (origFrom == currentUserId) friendName else currentUserName) else o.optString("sender_name", "")
                                imported.add(ChatMsg.create(
                                    serverId = nextId++,
                                    text = text,
                                    isMine = newIsMine,
                                    fromUserId = newFrom,
                                    isNew = false,
                                    senderName = newSender,
                                    createdAt = o.optLong("created_at", 0L),
                                    mediaType = o.optString("media_type", ""),
                                    mediaUrl = o.optString("media_url", ""),
                                    replyToText = replyToText,
                                    replyToSender = o.optString("reply_to_sender", ""),
                                    replyToId = o.optLong("reply_to_id", 0L),
                                    isRevoked = o.optInt("revoked", 0),
                                    flashDuration = o.optInt("flash_duration", 0),
                                    reasoningText = o.optString("reasoning_text", ""),
                                    thinkingSeconds = o.optLong("thinking_seconds", 0),
                                    totalSeconds = o.optLong("total_seconds", 0)
                                ).withKnownMediaType())
                            }
                            messages.addAll(imported.sortedBy { if (it.createdAt == 0L) Long.MAX_VALUE else it.createdAt })
                            saveLocalChatMessages(ctx, currentUserId, localChatKey(friendId), messages.filter { !it.isSystemNotice })
                            // 好友（服务端）对话：导入的消息服务端不会保存，重载会丢失，额外存到本地兜底
                            if (!isLocalChat(friendId)) {
                                saveImportedMessages(ctx, currentUserId, friendId, imported.toList())
                            }

                            // ── 导入头像：从 JSON meta 读取头像 URL，下载到临时文件并覆盖 AiAvatarState.bitmap ──
                            // 每次导入生成一个唯一 ID，临时头像文件以 import_avatar_<uuid>.png 命名（cacheDir），
                            // 不覆盖用户的正式 AI 头像（LocalStorage.getAiAvatarFile）。用户后续手动修改 AI 头像后
                            // 这个临时头像自然被废弃（AiAvatarState.bitmap 被用户新头像替换），旧临时文件不会被删除，
                            // 但不再被引用，最终会被系统清理。
                            try {
                                val meta = root.optJSONObject("meta")
                                if (meta != null) {
                                    val avatarUrl = meta.optString("ai_avatar_url", "")
                                    if (avatarUrl.isNotEmpty()) {
                                        scope.launch {
                                            try {
                                                val bytes = com.aurora.chat.data.api.HttpClient.downloadBytes(avatarUrl)
                                                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                if (bmp != null) {
                                                    val importAvatarId = java.util.UUID.randomUUID().toString()
                                                    val tempFile = java.io.File(ctx.cacheDir, "import_avatar_${importAvatarId}.png")
                                                    tempFile.outputStream().use { os -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os) }
                                                    AiAvatarState.bitmap = bmp
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                            } catch (_: Exception) {}

                            // ── 导入场景信息：勾选后替换当前设置，不勾选则保持现有设置 ──
                            if (importScene) {
                                try {
                                    val meta = root.optJSONObject("meta")
                                    if (meta != null) {
                                        val sceneInfo = meta.optJSONObject("scene_info")
                                        if (sceneInfo != null) {
                                            val scene = sceneInfo.optString("scenario", "")
                                            val myRole = sceneInfo.optString("my_role", "")
                                            val aiRole = sceneInfo.optString("ai_role", "")
                                            if (scene.isNotEmpty()) {
                                                AiChatManager.setScenario(ctx, currentUserId, scene)
                                                advScenario = scene
                                            }
                                            if (myRole.isNotEmpty()) {
                                                AiChatManager.setMyRole(ctx, currentUserId, myRole)
                                                advMyRole = myRole
                                            }
                                            if (aiRole.isNotEmpty()) {
                                                AiChatManager.setAiRole(ctx, currentUserId, aiRole)
                                                advAiRole = aiRole
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            Toast.makeText(ctx, "已导入 ${imported.size} 条消息", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // 读取导入文件中的占位符映射（导出时写入 meta.replace_map），用于导入时还原为人物名称
                fun readReplaceMap(c: android.content.Context, uri: android.net.Uri): List<ImportMapItem> {
                    return try {
                        val text = c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return emptyList()
                        val root = org.json.JSONObject(text)
                        val meta = root.optJSONObject("meta") ?: return emptyList()
                        val mapArr = meta.optJSONArray("replace_map") ?: return emptyList()
                        val list = mutableListOf<ImportMapItem>()
                        for (i in 0 until mapArr.length()) {
                            val m = mapArr.getJSONObject(i)
                            val from = m.optString("from", "")
                            val to = m.optString("to", "")
                            if (to.isNotEmpty()) list.add(ImportMapItem(placeholder = to, target = from, checked = true))
                        }
                        list
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                // 全文替换：把文本中所有「被替换名称」依次替换为对应占位符（隐私保护）
                fun applyReplace(text: String): String {
                    var t = text
                    for (i in replaceFroms.indices) {
                        val f = replaceFroms[i].trim()
                        if (f.isNotEmpty()) t = t.replace(f, replaceTos[i])
                    }
                    return t
                }

                // 构建导出 JSON：对话始终导出；头像按需上传服务器，文件只存服务器 URL
                val buildExportJson: suspend () -> String = {
                    val root = org.json.JSONObject()
                    root.put("app", "AuroraChat")
                    root.put("type", "conversation")
                    root.put("version", 1)
                    root.put("exported_at", System.currentTimeMillis())
                    root.put("friend_id", AI_CHAT_ID)
                    val meta = org.json.JSONObject()
                    if (expName) {
                        val uname = com.aurora.chat.data.api.AuroraApi.currentUserName
                        val aname = AiNameState.name ?: "AI"
                        meta.put("user_name", if (expReplace) applyReplace(uname) else uname)
                        meta.put("ai_name", if (expReplace) applyReplace(aname) else aname)
                    }
                    if (expUserAvatar) {
                        val url = tryUploadAvatar(true, null)
                        if (url.isNotEmpty()) meta.put("user_avatar_url", url)
                    }
                    if (expAiAvatar) {
                        val url = tryUploadAvatar(false, AiAvatarState.bitmap)
                        if (url.isNotEmpty()) meta.put("ai_avatar_url", url)
                    }
                    if (expScene) {
                        val scene = advScenario.trim()
                        val myRole = advMyRole.trim()
                        val aiRole = advAiRole.trim()
                        val sceneObj = org.json.JSONObject()
                        if (scene.isNotEmpty()) sceneObj.put("scenario", scene)
                        if (myRole.isNotEmpty()) sceneObj.put("my_role", myRole)
                        if (aiRole.isNotEmpty()) sceneObj.put("ai_role", aiRole)
                        if (sceneObj.length() > 0) meta.put("scene_info", sceneObj)
                    }
                    // 导出替换映射，便于导入时一键还原为人物名称
                    if (expReplace) {
                        val rm = org.json.JSONArray()
                        for (i in replaceFroms.indices) {
                            val f = replaceFroms[i].trim()
                            if (f.isNotEmpty()) {
                                rm.put(org.json.JSONObject().apply {
                                    put("from", f)
                                    put("to", replaceTos[i])
                                })
                            }
                        }
                        if (rm.length() > 0) meta.put("replace_map", rm)
                    }
                    root.put("meta", meta)
                    val arr = org.json.JSONArray()
                    for (m in messages) {
                        val o = org.json.JSONObject()
                        o.put("id", m.id)
                        o.put("text", if (expReplace) applyReplace(m.text) else m.text)
                        o.put("is_mine", m.isMine)
                        o.put("from_user_id", m.fromUserId)
                        o.put("sender_name", if (expReplace) applyReplace(m.senderName) else m.senderName)
                        o.put("created_at", m.createdAt)
                        o.put("media_type", m.mediaType)
                        o.put("media_url", m.mediaUrl)
                        o.put("reply_to_text", if (expReplace) applyReplace(m.replyToText) else m.replyToText)
                        o.put("reply_to_sender", if (expReplace) applyReplace(m.replyToSender) else m.replyToSender)
                        o.put("reply_to_id", m.replyToId)
                        o.put("revoked", m.isRevoked)
                        o.put("flash_duration", m.flashDuration)
                        o.put("reasoning_text", m.reasoningText)
                        o.put("thinking_seconds", m.thinkingSeconds)
                        o.put("total_seconds", m.totalSeconds)
                        arr.put(o)
                    }
                    root.put("messages", arr)
                    root.toString()
                }

                val exportSaver = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                val json = buildExportJson()
                                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                                    os.write(json.toByteArray(Charsets.UTF_8))
                                }
                                Toast.makeText(ctx, "对话已导出到本地", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                val importPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        var name = "对话文件.json"
                        var size = 0L
                        try {
                            ctx.contentResolver.query(
                                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null
                            )?.use { c ->
                                if (c.moveToFirst()) {
                                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                    if (ni >= 0) name = c.getString(ni) ?: name
                                    if (si >= 0) size = c.getLong(si)
                                }
                            }
                        } catch (_: Exception) {}
                        importFileName = name
                        importFileSize = size
                        importSwap = false
                        importScene = true
                        importMaps = readReplaceMap(ctx, uri)
                        pendingImportUri = uri
                        showImportDialog = true
                    }
                }

                // 点击导出/导入入口：会员体系已移除，所有用户直接放行
                // 必须在 importPicker 声明之后定义，否则 Kotlin 标记 unresolved reference
                fun requestUse(action: String) {
                    if (action == "export") showExportDialog = true
                    else importPicker.launch(arrayOf("application/json", "*/*"))
                }

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
                    // 选择免费 API：仅在已真正选中某条（有配置）时才持久化为模式，否则保留原模式
                    if (selMode != "free_api") {
                        AiChatManager.setMode(ctx, currentUserId, selMode)
                    } else if (AiChatManager.hasFreeApiConfig(ctx, currentUserId)) {
                        AiChatManager.setMode(ctx, currentUserId, "free_api")
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
                    AiChatManager.setShowReasoning(ctx, currentUserId, showReasoning)
                    android.widget.Toast.makeText(ctx, "已保存设置", android.widget.Toast.LENGTH_SHORT).show()
                    showAiUsage = false
                }

                Dialog(
                    onDismissRequest = { saveAiUsageAndClose() },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    // 彻底清除 Dialog 默认灰色状态栏蒙层 → 白底 + 深色图标
                    SetDialogSystemBarColors(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.WHITE,
                        lightStatusBars = true
                    )
                    // 白底全屏铺满（覆盖状态栏/导航栏区域，消除顶部灰色长条）
                    Box(Modifier.fillMaxSize().background(Color.White).imePadding()) {
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
                                    Text("AI 使用", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
                                    Text("✕", fontSize = 20.sp, color = Color(0xFF6B7280),
                                        modifier = Modifier.clickable { saveAiUsageAndClose() }.padding(4.dp))
                                }
                                HorizontalDivider(color = Color(0xFFE5E7EB))

                                // 中部可滚动内容
                                Column(
                                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)
                                ) {
                                    // 第三方免费 API（仅展示，暂无实际功能）
                                    Row(
                                        Modifier.fillMaxWidth().clickable { selMode = "third_party" }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = selMode == "third_party", onClick = { selMode = "third_party" })
                                        Column(Modifier.weight(1f)) {
                                            Text("使用 SolitaryCryAI", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                                            Text("由用户ID 13的用户提供", fontSize = 12.sp, color = Color(0xFF6B7280))
                                        }
                                    }

                                    // 个人 API
                                    Row(
                                        Modifier.fillMaxWidth().clickable { selMode = "personal" }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = selMode == "personal", onClick = { selMode = "personal" })
                                        Column(Modifier.weight(1f)) {
                                            Text("使用个人 API", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                                            Text("使用自己的密钥，不受余额限制", fontSize = 12.sp, color = Color(0xFF6B7280))
                                        }
                                    }

                                    // 选择免费 API（由用户贡献、开发者审核；点击切入独立全屏选择界面，选中后真正调用）
                                    Row(
                                        Modifier.fillMaxWidth().clickable { selMode = "free_api"; showAiUsage = false; showFreeApiSelect = true }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = selMode == "free_api", onClick = { selMode = "free_api"; showAiUsage = false; showFreeApiSelect = true })
                                        Column(Modifier.weight(1f)) {
                                            Text("选择免费 API", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                                            Text("由用户贡献，开发者审核后可用", fontSize = 12.sp, color = Color(0xFF6B7280))
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
                                            Text("开启性格调试", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                                            Text(
                                                if (personalityDebug) "已开启：性格/自定义设定将注入并生效" else "已关闭：性格设定暂不生效",
                                                fontSize = 12.sp, color = Color(0xFF6B7280)
                                            )
                                        }
                                        Switch(checked = personalityDebug, onCheckedChange = togglePersonalityDebug)
                                    }

                                    // 高级选项（会员体系已移除，所有用户均可查看与使用）
                                    Row(
                                        Modifier.fillMaxWidth().clickable { showAdvancedOptions = !showAdvancedOptions }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("高级选项", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                                        Spacer(Modifier.weight(1f))
                                        Text(if (showAdvancedOptions) "收起" else "展开", fontSize = 12.sp, color = Color(0xFF6B7280))
                                        Icon(
                                            imageVector = if (showAdvancedOptions) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null, tint = Color(0xFF6B7280), modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    if (showAdvancedOptions) {
                                        Column(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                                .background(Color(0xFFF9FAFB)).padding(12.dp)
                                        ) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("场景", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    advScenario = ""
                                                    AiChatManager.setScenario(ctx, currentUserId, "")
                                                }) {
                                                    Text("清空", fontSize = 11.sp, color = Color(0xFF6B7280))
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Box(Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = advScenario, onValueChange = { advScenario = it },
                                                    enabled = true,
                                                    placeholder = { Text("例如：你们在一艘太空飞船上", color = Color(0xFF9CA3AF)) },
                                                    singleLine = false, modifier = Modifier.fillMaxWidth().height(72.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Text("你的角色设定", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("你在对话中扮演的身份（用户本人）", fontSize = 11.sp, color = Color(0xFF6B7280))
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    advMyRole = ""
                                                    AiChatManager.setMyRole(ctx, currentUserId, "")
                                                }) {
                                                    Text("清空", fontSize = 11.sp, color = Color(0xFF6B7280))
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Box(Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = advMyRole, onValueChange = { advMyRole = it },
                                                    enabled = true,
                                                    placeholder = { Text("例如：一名舰长", color = Color(0xFF9CA3AF)) },
                                                    singleLine = false, modifier = Modifier.fillMaxWidth().height(72.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            Text("AI 的角色设定", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF374151))
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("让 AI 扮演的身份", fontSize = 11.sp, color = Color(0xFF6B7280))
                                                Spacer(Modifier.weight(1f))
                                                TextButton(onClick = {
                                                    advAiRole = ""
                                                    AiChatManager.setAiRole(ctx, currentUserId, "")
                                                }) {
                                                    Text("清空", fontSize = 11.sp, color = Color(0xFF6B7280))
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Box(Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = advAiRole, onValueChange = { advAiRole = it },
                                                    enabled = true,
                                                    placeholder = { Text("例如：飞船的人工智能助手", color = Color(0xFF9CA3AF)) },
                                                    singleLine = false, modifier = Modifier.fillMaxWidth().height(72.dp)
                                                )
                                            }
                                            Spacer(Modifier.height(12.dp))
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("AI 多次输出", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                    Text("开启后 AI 可自行决定是否分多条短消息回复（如追问、分段），而非一次输出一大段", fontSize = 11.sp, color = Color(0xFF6B7280))
                                                }
                                                Spacer(Modifier.width(12.dp))
                                                Switch(checked = advMulti, onCheckedChange = { advMulti = it })
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("显示思考过程", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                    Text("开启后 AI 在思考时实时显示思考内容，可折叠收起不占空间", fontSize = 11.sp, color = Color(0xFF6B7280))
                                                }
                                                Spacer(Modifier.width(12.dp))
                                                Switch(checked = showReasoning, onCheckedChange = {
                                                    showReasoning = it
                                                    AiChatManager.setShowReasoning(ctx, currentUserId, it)
                                                })
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }

                                    Spacer(Modifier.height(16.dp))

                                    // 高峰期提示（不添加分割线）
                                    Text(
                                        "模型高峰期：9:00-12:00、14:00-18:00。建议在此时间段之外使用；如需在高峰期使用，建议开启「节省 Token 模式」。",
                                        fontSize = 12.sp, color = Color(0xFF6B7280),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))

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
                                    // AI 沙盒 与 AI 设置：左右排列在同一行（朴素纯文字按钮）
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            modifier = Modifier.weight(1f)
                                                .background(Color.White, RoundedCornerShape(10.dp))
                                                .border(1.dp, Color(0xFFD5DCE3), RoundedCornerShape(10.dp))
                                                .clickable { showAiUsage = false; onOpenAiSandbox() }
                                                .padding(vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("AI 沙盒", color = Color(0xFF1F2937), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        }
                                        Box(
                                            modifier = Modifier.weight(1f)
                                                .background(Color.White, RoundedCornerShape(10.dp))
                                                .border(1.dp, Color(0xFFD5DCE3), RoundedCornerShape(10.dp))
                                                .clickable { showAiSettingsDialog = true }
                                                .padding(vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("AI 设置", color = Color(0xFF1F2937), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))

                                    // 对话导入 / 导出（朴素纯文字按钮）
                                    Text("对话管理", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1F2937))
                                    Spacer(Modifier.height(8.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            modifier = Modifier.weight(1f)
                                                .background(Color.White, RoundedCornerShape(10.dp))
                                                .border(1.dp, Color(0xFFD5DCE3), RoundedCornerShape(10.dp))
                                                .clickable { requestUse("export") }
                                                .padding(vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) { Text("导出对话", color = Color(0xFF1F2937), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                                        Box(
                                            modifier = Modifier.weight(1f)
                                                .background(Color.White, RoundedCornerShape(10.dp))
                                                .border(1.dp, Color(0xFFD5DCE3), RoundedCornerShape(10.dp))
                                                .clickable { requestUse("import") }
                                                .padding(vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) { Text("导入对话", color = Color(0xFF1F2937), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                                    }
                                    Spacer(Modifier.height(16.dp))


                                    // 导出对话弹窗：勾选要附带的内容（对话记录始终导出）
                                    if (showExportDialog) {
                                        Dialog(onDismissRequest = { showExportDialog = false }) {
                                            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                                                Column(
                                                    Modifier
                                                        .padding(20.dp)
                                                        .verticalScroll(rememberScrollState())
                                                ) {
                                                    Text("导出对话", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                                                    Spacer(Modifier.height(10.dp))
                                                    Text("对话记录始终导出，可选择是否附带以下内容：", fontSize = 13.sp, color = Color(0xFF6B7280))
                                                    Spacer(Modifier.height(8.dp))
                                                    // 第一排：用户头像 + AI 头像 同行
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Row(
                                                            Modifier.weight(1f).clickable { expUserAvatar = !expUserAvatar }.padding(vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(checked = expUserAvatar, onCheckedChange = { expUserAvatar = it })
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("用户头像", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                        }
                                                        Row(
                                                            Modifier.weight(1f).clickable { expAiAvatar = !expAiAvatar }.padding(vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(checked = expAiAvatar, onCheckedChange = { expAiAvatar = it })
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("AI 头像", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                        }
                                                    }
                                                    // 第二排：名字 + 场景信息 同行
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Row(
                                                            Modifier.weight(1f).clickable { expName = !expName }.padding(vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(checked = expName, onCheckedChange = { expName = it })
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("名字", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                        }
                                                        Row(
                                                            Modifier.weight(1f).clickable { expScene = !expScene }.padding(vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(checked = expScene, onCheckedChange = { expScene = it })
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("场景信息", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                        }
                                                    }
                                                    // 第三排：全文替换（单独一行）
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Row(
                                                            Modifier.weight(1f).clickable { expReplace = !expReplace }.padding(vertical = 6.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Checkbox(checked = expReplace, onCheckedChange = { expReplace = it })
                                                            Spacer(Modifier.width(6.dp))
                                                            Text("全文替换", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                        }
                                                    }
                                                    // 全文替换：每行「被替换名称 → 占位符」，可增删
                                                    if (expReplace) {
                                                        Spacer(Modifier.height(10.dp))
                                                        Text("隐私保护：导出前将全文中的名称替换为占位符", fontSize = 12.sp, color = Color(0xFF6B7280))
                                                        Spacer(Modifier.height(6.dp))
                                                        // 占位符不可重复：找出出现次数 > 1 的非空占位符
                                                        val toTrimmed = replaceTos.map { it.trim() }
                                                        val dupTos = toTrimmed
                                                            .filter { it.isNotEmpty() }
                                                            .groupingBy { it }.eachCount()
                                                            .filter { it.value > 1 }.keys
                                                        val idxList = replaceFroms.indices.toList()
                                                        idxList.forEach { i ->
                                                            Row(
                                                                Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                OutlinedTextField(
                                                                    value = replaceFroms[i],
                                                                    onValueChange = { replaceFroms[i] = it },
                                                                    placeholder = { Text("名称", fontSize = 12.sp) },
                                                                    singleLine = true,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                Text("→", fontSize = 13.sp, color = Color(0xFF6B7280))
                                                                val toVal = replaceTos[i].trim()
                                                                OutlinedTextField(
                                                                    value = replaceTos[i],
                                                                    onValueChange = { replaceTos[i] = it },
                                                                    placeholder = { Text("占位符", fontSize = 12.sp) },
                                                                    singleLine = true,
                                                                    isError = toVal.isNotEmpty() && toVal in dupTos,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                if (replaceFroms.size > 1) {
                                                                    Text(
                                                                        "✕",
                                                                        fontSize = 14.sp,
                                                                        color = Color(0xFFDC2626),
                                                                        modifier = Modifier.clickable {
                                                                            replaceFroms.removeAt(i)
                                                                            replaceTos.removeAt(i)
                                                                        }.padding(4.dp)
                                                                    )
                                                                }
                                                            }
                                                            Spacer(Modifier.height(6.dp))
                                                        }
                                                        if (dupTos.isNotEmpty()) {
                                                            Text("⚠ 占位符不能重复，否则所有人将合并成同一个名字，请修改后再导出", fontSize = 12.sp, color = Color(0xFFDC2626))
                                                            Spacer(Modifier.height(6.dp))
                                                        }
                                                        TextButton(onClick = {
                                                            replaceFroms.add("人物" + (replaceFroms.size + 1))
                                                            replaceTos.add("X" + (replaceTos.size - 1))
                                                        }) {
                                                            Text("+ 添加替换项", color = Color(0xFF1E40AF), fontSize = 13.sp)
                                                        }
                                                    }
                                                    Spacer(Modifier.height(16.dp))
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                        TextButton(onClick = { showExportDialog = false }) { Text("取消") }
                                                        Spacer(Modifier.width(8.dp))
                                                        Button(onClick = {
                                                            if (expReplace) {
                                                                val tos = replaceTos.map { it.trim() }.filter { it.isNotEmpty() }
                                                                if (tos.size != tos.toSet().size) {
                                                                    Toast.makeText(ctx, "占位符不能重复，请修改后再导出", Toast.LENGTH_SHORT).show()
                                                                    return@Button
                                                                }
                                                            }
                                                            showExportDialog = false
                                                            exportSaver.launch("aurora_chat_${System.currentTimeMillis()}.json")
                                                        }) { Text("确认导出") }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 导入对话预览弹窗：显示文件名/大小，可选择人物对换与占位符还原
                                    if (showImportDialog) {
                                        Dialog(onDismissRequest = { showImportDialog = false }) {
                                            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                                                Column(
                                                    Modifier
                                                        .padding(20.dp)
                                                        .verticalScroll(rememberScrollState())
                                                ) {
                                                    Text("导入对话", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                                                    Spacer(Modifier.height(10.dp))
                                                    // 文件名
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Text("文件：", fontSize = 13.sp, color = Color(0xFF6B7280))
                                                        Text(importFileName, fontSize = 14.sp, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
                                                    }
                                                    Spacer(Modifier.height(4.dp))
                                                    // 文件大小
                                                    val sizeTxt = when {
                                                        importFileSize < 1024 -> "${importFileSize} B"
                                                        importFileSize < 1024 * 1024 -> String.format("%.1f KB", importFileSize / 1024f)
                                                        else -> String.format("%.2f MB", importFileSize / 1024f / 1024f)
                                                    }
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Text("大小：", fontSize = 13.sp, color = Color(0xFF6B7280))
                                                        Text(sizeTxt, fontSize = 14.sp, color = Color(0xFF1F2937))
                                                    }
                                                    Spacer(Modifier.height(10.dp))
                                                    // 人物对换
                                                    Row(
                                                        Modifier.fillMaxWidth().clickable { importSwap = !importSwap }.padding(vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Checkbox(checked = importSwap, onCheckedChange = { importSwap = it })
                                                        Spacer(Modifier.width(8.dp))
                                                        Column(Modifier.weight(1f)) {
                                                            Text("人物对换", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                            Text("勾选后将导入的对话以对方视角呈现", fontSize = 12.sp, color = Color(0xFF6B7280))
                                                        }
                                                    }
                                                    // 场景信息导入
                                                    Spacer(Modifier.height(8.dp))
                                                    Row(
                                                        Modifier.fillMaxWidth().clickable { importScene = !importScene }.padding(vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Checkbox(checked = importScene, onCheckedChange = { importScene = it })
                                                        Spacer(Modifier.width(8.dp))
                                                        Column(Modifier.weight(1f)) {
                                                            Text("场景信息", fontSize = 14.sp, color = Color(0xFF1F2937))
                                                            Text("勾选后将导入文件中的场景和角色设定，替换当前设置", fontSize = 12.sp, color = Color(0xFF6B7280))
                                                        }
                                                    }
                                                    // 占位符转回人物名称（可多选、可增删；左侧占位符，右侧真实姓名）
                                                    Spacer(Modifier.height(8.dp))
                                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                        Text("占位符转回人物名称", fontSize = 13.sp, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
                                                        TextButton(onClick = { importMaps = importMaps + ImportMapItem("", "", true) }) {
                                                            Text("+ 添加", fontSize = 12.sp, color = Color(0xFF1E40AF))
                                                        }
                                                    }
                                                    Spacer(Modifier.height(6.dp))
                                                    if (importMaps.isEmpty()) {
                                                        Text("点击「+ 添加」：左侧填占位符，右侧填要替换成的人物名称", fontSize = 12.sp, color = Color(0xFF6B7280))
                                                    } else {
                                                        importMaps.forEachIndexed { idx, item ->
                                                            Row(
                                                                Modifier.fillMaxWidth(),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                OutlinedTextField(
                                                                    value = item.placeholder,
                                                                    onValueChange = { importMaps = importMaps.toMutableList().apply { this[idx] = this[idx].copy(placeholder = it) } },
                                                                    placeholder = { Text("占位符", fontSize = 12.sp) },
                                                                    singleLine = true,
                                                                    modifier = Modifier.width(88.dp)
                                                                )
                                                                Text("→", fontSize = 13.sp, color = Color(0xFF6B7280))
                                                                OutlinedTextField(
                                                                    value = item.target,
                                                                    onValueChange = { importMaps = importMaps.toMutableList().apply { this[idx] = this[idx].copy(target = it) } },
                                                                    placeholder = { Text("人物", fontSize = 12.sp) },
                                                                    singleLine = true,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                TextButton(onClick = { importMaps = importMaps.toMutableList().apply { removeAt(idx) } }) {
                                                                    Text("删除", fontSize = 12.sp, color = Color(0xFFDC2626))
                                                                }
                                                            }
                                                            Spacer(Modifier.height(6.dp))
                                                        }
                                                    }
                                                    Spacer(Modifier.height(16.dp))
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                        TextButton(onClick = { showImportDialog = false }) { Text("取消") }
                                                        Spacer(Modifier.width(8.dp))
                                                        Button(onClick = {
                                                            showImportDialog = false
                                                            // 人物对换改为「导入时翻转本批消息数据」（见 importConversationFromUri），
                                                            // 不再开启全局 reverseHistory，避免它卡死并污染其他对话。
                                                            // 这里统一关掉全局反转，确保导入显示不被多余的显示层翻转干扰。
                                                            reverseHistory = false
                                                            LocalStorage.setReverseHistory(ctx, currentUserId, false)
                                                            val uri = pendingImportUri
                                                            if (uri != null) scope.launch {
                                                                importConversationFromUri(uri, importSwap, importMaps.toList())
                                                            }
                                                        }) { Text("确认导入") }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 性格调试
                                    Text("性格调试", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1F2937))
                                    Spacer(Modifier.height(4.dp))
                                    Text("滑动调节 AI 的性格倾向（5 个维度总和不超过 100%，拖动一个会自动压缩其余维度，离开面板自动保存）", fontSize = 12.sp, color = Color(0xFF6B7280))
                                    Spacer(Modifier.height(4.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        val used = traits.sum().toInt()
                                        Text("已分配 $used% / 100%", fontSize = 12.sp,
                                            color = if (used > 100) Color(0xFFDC2626) else Color(0xFF6B7280))
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    traitLabels.forEachIndexed { i, label ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(label, fontSize = 14.sp, color = Color(0xFF374151), modifier = Modifier.width(48.dp))
                                            Slider(
                                                value = traits[i],
                                                onValueChange = { newVal ->
                                                    // 5 维总和上限 100：超出时按比例压缩其余维度
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
                                                },
                                                valueRange = 0f..100f,
                                                modifier = Modifier.weight(1f),
                                                // 自定义轨道铺满整条，消除圆球左右默认留白（不能用负 padding，否则闪退）
                                                track = { positions ->
                                                    Box(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(RoundedCornerShape(3.dp))
                                                            .background(Color(0xFFE5E7EB))
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
                                        Text("${traits[i].toInt()}%", fontSize = 13.sp, color = Color(0xFF374151),
                                            modifier = Modifier.width(44.dp).padding(start = 4.dp))
                                        }
                                        Spacer(Modifier.height(10.dp))
                                    }
                                    // 极端（最后一条性格设定）下方：自定义入口，仅此一处，不绑定任何维度
                                    Text("＋ 自定义设定（在 5 条调试之外额外添加）", fontSize = 12.sp, color = Color(0xFF1E40AF),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { showCustomDialog = true; customDialogText = "" }
                                            .padding(vertical = 6.dp))
                                    Spacer(Modifier.height(8.dp))
                                    // 自定义设定列表（按字面意思注入，可调节权重）
                                    if (customs.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("已添加的自定义设定", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1F2937))
                                        Text("按字面意思注入，右侧可调节权重，点击 ✕ 删除", fontSize = 11.sp, color = Color(0xFF6B7280))
                                        Spacer(Modifier.height(8.dp))
                                        customs.forEachIndexed { ci, c ->
                                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(c.text, fontSize = 13.sp, color = Color(0xFF374151))
                                                }
                                                Slider(
                                                    value = c.pct,
                                                    onValueChange = { customs[ci] = c.copy(pct = it) },
                                                    valueRange = 0f..100f,
                                                    modifier = Modifier.weight(1f),
                                                    track = { positions ->
                                                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE5E7EB))) {
                                                            Box(Modifier.fillMaxWidth((positions.value / 100f).coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1E40AF))) {}
                                                        }
                                                    },
                                                    thumb = { Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF1E40AF))) {} }
                                                )
                                                Text("${c.pct.toInt()}%", fontSize = 12.sp, color = Color(0xFF374151),
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
                    }
                }

                // 自定义性格设定输入弹窗（位于 showAiUsage 块内，可访问 customs / showCustomDialog）
                if (showCustomDialog) {
                    AlertDialog(
                        onDismissRequest = { showCustomDialog = false; customDialogText = "" },
                        containerColor = Color.White,
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
                            TextButton(onClick = { showCustomDialog = false; customDialogText = "" }) { Text("取消", color = Color(0xFF6B7280)) }
                        }
                    )
                }
            }

            // ── AI 时间点弹窗（位于主聊天界面，不在 DeepSeek使用 面板内）──
            if (showTimePointDialog) {
                var customHour by remember { mutableStateOf("") }
                var customMinute by remember { mutableStateOf("00") }
                Dialog(onDismissRequest = { showTimePointDialog = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Column(Modifier.padding(20.dp).widthIn(min = 280.dp)) {
                            Text("设置时间点", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                            Spacer(Modifier.height(12.dp))
                            Text("快捷选择", fontSize = 14.sp, color = Color(0xFF6B7280))
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("早上 6:00" to "06:00", "中午 11:00" to "11:00", "晚上 18:00" to "18:00").forEach { (label, time) ->
                                    Box(
                                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6))
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
                                    ) { Text(label, fontSize = 13.sp, color = Color(0xFF1F2937)) }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Text("自定义时间", fontSize = 14.sp, color = Color(0xFF6B7280))
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = customHour, onValueChange = { v -> if (v.all { it.isDigit() } && v.length <= 2) customHour = v },
                                    placeholder = { Text("时", fontSize = 13.sp) }, singleLine = true,
                                    modifier = Modifier.width(60.dp)
                                )
                                Text(" : ", fontSize = 16.sp, color = Color(0xFF1F2937))
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
                                TextButton(onClick = { showTimePointDialog = false }) { Text("取消", color = Color(0xFF6B7280)) }
                            }
                        }
                    }
                }
            }

            // ── AI 确认发言弹窗（位于主聊天界面，不在 DeepSeek使用 面板内）──
            if (showConfirmSpeechDialog) {
                Dialog(onDismissRequest = { showConfirmSpeechDialog = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Column(Modifier.padding(20.dp).widthIn(min = 260.dp)) {
                            Text("确认发言", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                            Spacer(Modifier.height(12.dp))
                            Text(if (confirmSpeechEnabled) "当前已开启确认发言模式，AI 不会自动回复" else "开启后，AI 不会自动回复，需要手动确认后才回复",
                                fontSize = 14.sp, color = Color(0xFF6B7280))
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                TextButton(onClick = { showConfirmSpeechDialog = false }) { Text("取消", color = Color(0xFF6B7280)) }
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

            // ── AI 模式选择弹窗（智能/图片/视频/对话）：仅 第三方 / 个人 API 生效 ──
            if (showModeSelectDialog) {
                var selGen by remember { mutableStateOf(AiChatManager.getGenMode(ctx, currentUserId)) }
                val genOptions = listOf(
                    Triple(AiChatManager.GEN_SMART, "智能", "智能识别用户意图来判断是否生成视频、图片等"),
                    Triple(AiChatManager.GEN_IMAGE, "图片", "开启此功能，发任何对话将直接提交发出的内容为图片提示词"),
                    Triple(AiChatManager.GEN_VIDEO, "视频", "开启此功能，发任何对话将直接提交发出的内容为视频提示词"),
                    Triple(AiChatManager.GEN_CHAT, "对话", "开启后仅对话，不生成图片视频")
                )
                Dialog(onDismissRequest = { showModeSelectDialog = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Column(Modifier.padding(20.dp).widthIn(min = 300.dp)) {
                            Text("模式选择", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                            Spacer(Modifier.height(4.dp))
                            Text("用于生成图片 / 视频，可随时切换", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                            Spacer(Modifier.height(12.dp))
                            genOptions.forEach { (mode, label, desc) ->
                                val selected = selGen == mode
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) Color(0xFFEFF6FF) else Color.Transparent)
                                        .clickable { selGen = mode }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = selected, onClick = { selGen = mode })
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                                        Text(desc, fontSize = 12.sp, color = Color(0xFF6B7280), lineHeight = 16.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showModeSelectDialog = false }) { Text("取消", color = Color(0xFF6B7280)) }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    AiChatManager.setGenMode(ctx, currentUserId, selGen)
                                    showModeSelectDialog = false
                                    android.widget.Toast.makeText(ctx, "已切换为「${genOptions.first { it.first == selGen }.second}」模式", android.widget.Toast.LENGTH_SHORT).show()
                                }) { Text("确定", color = Color.White) }
                            }
                        }
                    }
                }
            }

            // ── AI「图片」首次使用提示：告知所选模型可能不支持图片识别（如 DeepSeek 为纯文本模型）──
            if (showAiImageNotice) {
                Dialog(onDismissRequest = { showAiImageNotice = false }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Column(Modifier.padding(20.dp).widthIn(min = 280.dp)) {
                            Text("图片识别提示", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                            Spacer(Modifier.height(12.dp))
                            Text("图片会以真实图片形式发送给 AI，但如果当前模型不支持图片识别，AI 将无法看到图片内容。",
                                fontSize = 14.sp, color = Color(0xFF4B5563), lineHeight = 20.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("例如 DeepSeek 官方模型为纯文本模型，不支持图片识别；如需 AI 看图，请在个人 API 中配置支持视觉的多模态模型（如通义千问 VL、GPT-4o、GLM-4V 等）。",
                                fontSize = 13.sp, color = Color(0xFF6B7280), lineHeight = 19.sp)
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
                    containerColor = Color.White,
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
                        TextButton(onClick = { showNewChatConfirm = false }) { Text("取消", color = Color(0xFF6B7280)) }
                    }
                )
            }

            // ── 入群申请横幅（仅群聊且是管理员/群主）──
            if (isGroupChat(friendId)) {
                // 合并到一个 LaunchedEffect：先获取角色，再启动监听和轮询
                LaunchedEffect(friendId) {
                    // 每次切换会话先重置，避免沿用上一个非成员群的旧状态（接口失败时也保持默认）
                    isAdminOrOwner = false
                    isNotGroupMember = false
                    // 1. 获取当前用户在该群的角色
                    try {
                        val gid = -(friendId + 1000)
                        val info = AuroraApi.getGroupInfo(gid)
                        if (info.success && info.data != null) {
                            val role = info.data!!.optString("user_role", "")
                            val creatorId = info.data!!.optLong("creator_id", 0)
                            isAdminOrOwner = role == "owner" || role == "admin" || AuroraApi.currentUserId == 1L || creatorId == AuroraApi.currentUserId
                            // 非群成员（含「入群需审核」待审核态）：user_role 为空，禁止当作正常会话处理
                            isNotGroupMember = isGroupChat(friendId) && role.isEmpty()
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

            // 消息列表（用 Box + weight 避免 weight 直接放 LazyColumn 上导致 infinity 崩溃）
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 防御性去重：快速切 tab / 并发写入会使 messages 短暂含重复 id，
            // LazyColumn 要求 key 唯一，否则 measure 时抛 "Key was already used" 崩溃。
            // 渲染层用 distinctBy 保证重复 id 只显示一次（数据层保持原样供其他逻辑使用）。
            val displayMessages = remember { derivedStateOf { messages.distinctBy { it.id } } }.value
            LazyColumn(
                state = listState,
                // reverseLayout：最新消息位于 index 0、（绘制在底部），进入会话时天然贴底，
                // 无需 scrollToItem 逐条测量历史即可定位底部，彻底消除「进入大历史会话首帧卡顿」的全量测量开销。
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (scrollReady || messages.isEmpty()) 1f else 0f) // 滚到底部前隐藏，防止闪现顶部；空列表/加载时仍需显示
                    .pointerInput(Unit) {
                        // ── 自动跟随意图锁存 + anchor 钉位的核心 ──
                        // 手指按下 → userScrolledAway=true 同时记录 anchor(用户此刻最关注的那条)
                        // 手指抬起且此时列表已贴底 → 解锁 false,清空 anchor
                        // 其他情况保持锁存,让后续流式增量去"钉住"anchor
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val anyDownNow = event.changes.any { it.pressed && it.previousPressed != true }
                                val anyUpNow = event.changes.any { !it.pressed && it.previousPressed }
                                if (anyDownNow) {
                                    userScrolledAway = true
                                    // 记录 anchor:视口内最靠下那条(最后一条 visibleItems),钉住它防止被 index 0 长高顶上去
                                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                                    if (lastVisible != null && lastVisible.index > 0) {
                                        anchorItemIndex = lastVisible.index
                                        anchorItemOffset = lastVisible.offset
                                    } else {
                                        anchorItemIndex = -1
                                    }
                                }
                                if (anyUpNow) {
                                    if (atBottomState.value) {
                                        userScrolledAway = false
                                        anchorItemIndex = -1
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures {
                            // 点击空白区域关闭所有弹窗
                            openMenuMsgId = 0L
                            focusManager.clearFocus()
                            clearSelectionKey++
                            val imm = ctx.getSystemService(

                                android.content.Context.INPUT_METHOD_SERVICE
                            ) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                        }
                    },
                // AI 对话末尾留出更充足的可滚动呼吸空间，避免最后一行内容紧贴底部输入栏；非 AI 对话间距保持不变
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = if (friendId == AI_CHAT_ID) 32.dp else 4.dp
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
                                        color = Color(0xFF9CA3AF)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "加载中...",
                                        fontSize = 14.sp, color = Color(0xFF9CA3AF)
                                    )
                                }
                            } else {
                                if (isNotGroupMember) {
                                    androidx.compose.foundation.layout.Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text("你还未加入该群", fontSize = 15.sp, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium)
                                        Spacer(Modifier.height(6.dp))
                                        Text("入群申请审核中，暂无法查看消息", fontSize = 13.sp, color = Color(0xFF9CA3AF))
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
                                } else {
                                    Text(
                                        "暂无消息",
                                        fontSize = 14.sp, color = Color(0xFF9CA3AF)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 倒序渲染：index 0 = 最新（绘制在底部），配合 reverseLayout 使用。
                    displayMessages.asReversed().forEachIndexed { index, msg ->
                        val cellContentType = run {
                            val m = if (reverseHistory && !msg.isSystemNotice) (msg as ChatMsg).copy(
                                isMine = !msg.isMine,
                            ) else msg
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
                        // 人物对换：显示时整体反转（左右位置互换），不改变底层数据；
                        // 只翻 isMine，不翻 fromUserId/senderName，头像仍跟真正发消息的人走。
                        // remember(msg, reverseHistory) 缓存翻转结果，避免每次重组都 copy；
                        // 用 is ChatMsg 智能转换替代强制 as，消除对 StoreMessageRef 的 ClassCastException 风险
                        val rmsg = remember(msg, reverseHistory) {
                            if (reverseHistory && !msg.isSystemNotice && msg is ChatMsg)
                                msg.copy(isMine = !msg.isMine) else msg
                        }
                        // 已删除：derivedStateOf 作用域到单条，删除某条时仅该条重算，避免整窗重组
                        val isDeleted by remember(msg.id) { derivedStateOf { msg.id in deletedMessageIds } }
                        if (isDeleted) return@item
                        // 红包卡片（不居中，对齐到发送者头像侧；点击进入全屏领取界面）
                        if (msg.mediaType == "redpacket" || isRedPacketMessage(msg.text)) {
                            val rp = parseRedPacketMessage(msg.text)
                            if (rp != null) {
                                val senderAvatarMod = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                                val myAvatar = if (friendId == AI_CHAT_ID) (aiMyAvatarBitmap ?: myAvatarBitmap) else myAvatarBitmap
                                @Composable
                                fun RedPacketSenderAvatar() {
                                    if (rp.fromUserId == currentUserId && myAvatar != null) {
                                        Image(bitmap = myAvatar.asImageBitmap(), contentDescription = null, modifier = senderAvatarMod, contentScale = ContentScale.Fit)
                                    } else {
                                        UserAvatar(userId = rp.fromUserId, userName = rp.fromName.ifEmpty { "?" }, size = 42.dp, modifier = senderAvatarMod)
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                                        onClick = { redPacketDetailId = rp.packetId }
                                    )
                                    if (msg.isMine) {
                                        Spacer(Modifier.width(6.dp))
                                        RedPacketSenderAvatar()
                                    }
                                }
                                return@item
                            }
                        }
                        // 转账卡片（群聊/私聊统一渲染，视角由 data 现算）
                        // 用正文前缀判断而非 mediaType，避免不同解析路径 media_type 丢失导致显示原文
                        if (msg.mediaType == "transfer" || isTransferMessage(msg.text)) {
                            val t = parseTransferMessage(msg.text)
                            if (t != null) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    TransferCard(data = t, currentUserId = currentUserId)
                                }
                                return@item
                            }
                        }
                        if (msg.isSystemNotice || msg.isRevoked == 1) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp)
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {},
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
                                // 对话内撤回：原文仍保留在数据中，需生成"X 撤回了一条消息"占位文案；
                                // 服务端重载路径已在数据层把文案/isSystemNotice 设好，这里仅兜底对话内场景
                                val isRecallPlaceholder = msg.isRevoked == 1 && !msg.isSystemNotice
                                val noticeText = if (isRecallPlaceholder) {
                                    val sender = if (msg.isMine) currentUserName else msg.senderName
                                    "$sender 撤回了一条消息"
                                } else msg.text
                                val transferNotice = if (!isRecallPlaceholder) parseTransferNotice(noticeText) else null
                                if (transferNotice != null) {
                                    TransferNoticeCard(desc = transferNotice)
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
                                            color = Color(0xFF9CA3AF),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            maxLines = 2
                                        )
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
                        val prevBubbleMsg = displayMessages.asReversed().getOrNull(index + 1)
                        val rprev = remember(prevBubbleMsg, reverseHistory) {
                            prevBubbleMsg?.let { if (reverseHistory && !it.isSystemNotice && it is ChatMsg) it.copy(isMine = !it.isMine) else it }
                        }
                        val senderChanged = rprev != null
                            && !rprev.isSystemNotice && !rmsg.isSystemNotice
                            && rprev.isMine != rmsg.isMine
                        if (senderChanged) Spacer(Modifier.height(10.dp))
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
                                isLocalChat(friendId) && !messages.any { it.id == rmsg.replyToId }
                            }
                        } else false
                        MessageBubbleItem(
                            msg = rmsg,
                        aiToolStatus = null,  // 工具状态现已作为独立消息（isToolStatus=true）就地渲染，不再挂到气泡上
                            // 「查看所有改动」：强制渲染按钮由 aiFileChanges 数据驱动,点击切入全屏改动详情
                            onViewChanges = {
                                (msg as? ChatMsg)?.aiFileChanges?.takeIf { it.isNotBlank() }?.let {
                                    aiChangesJsonData = it
                                    aiChangesMsgId = msg.id
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
                            replyIsRevoked = replyIsRevoked,
                            loadMedia = loadMedia,
                            myAvatar = if (friendId == AI_CHAT_ID) (aiMyAvatarBitmap ?: myAvatarBitmap) else myAvatarBitmap,
                            currentUserId = currentUserId,
                            currentUserName = currentUserName,
                            friendId = friendId,
                            friendName = friendName,
                            animMode = animMode,
                            advancedAnim = advancedAnim,
                            onOpenPostDetail = onOpenPostDetail,
                            onOpenGroupDetail = onOpenGroupDetail,
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
                            onCopyMessage = { text ->
                                try {
                                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    // 复制时取出内联思考的纯正文，避免把哨兵字符带进剪贴板
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("消息", AiChatManager.stripInlineReasoning(text)))
                                    Toast.makeText(ctx, "复制成功", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onReplyTo = { replyToMsg.value = msg },
                            // 「修改」只在 AI 对话出现，私信/群聊不显示
                            onEditMessage = if (friendId == AI_CHAT_ID) { target ->
                                editTargetMsgId = target.id
                                editReasoningText = (target as? ChatMsg)?.reasoningText ?: ""
                                editOutputText = target.text
                                showEditDialog = true
                            } else null,
                            onMultiSelect = {
                                openMenuMsgId = 0L
                                multiSelectMode = true
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
                                    val targetIdx = messages.indexOfFirst { it.id == targetId }
                                    if (targetIdx >= 0) {
                                        // reverseLayout：数据下标 → 展示下标（index0=最新）
                                        listState.animateScrollToItem((displayMessages.size - 1 - targetIdx).coerceAtLeast(0))
                                        // 等待滚动动画彻底完成后才开始闪烁
                                        while (listState.isScrollInProgress) {
                                            kotlinx.coroutines.delay(50)
                                        }
                                        kotlinx.coroutines.delay(100)
                                        highlightedMsgId = targetId
                                        kotlinx.coroutines.delay(1600)
                                        highlightedMsgId = 0L
                                    } else {
                                        android.widget.Toast.makeText(ctx, "未找到被引用的消息", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            destroyedFlashIds = destroyedFlashForItem,
                            onFlashDestroy = { id -> saveDestroyedFlash(id) },
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
                                scope.launch {
                                    com.aurora.chat.data.repository.ChatRepository.sendPoke(uid)
                                }
                                val timeNow = System.currentTimeMillis()
                                val noticeText = if (uid == currentUserId) {
                                    "你拍了拍自己"
                                } else {
                                    "你拍了拍${name}"
                                }
                                val localId = 10000L + (timeNow % 900000) + 1
                                messages.add(ChatMsg(
                                    id = localId, text = noticeText, isMine = true,
                                    fromUserId = currentUserId, senderName = currentUserName.ifEmpty { "我" }, time = timeNow,
                                    isSystemNotice = true, isNew = true, createdAt = timeNow / 1000
                                ))
                            },
                            onAtMentionClick = { mentionedName ->
                                // 从群成员列表映射中查找被艾特的用户ID
                                val memberId = groupMemberIdMap[mentionedName]
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
                            onImageClick = { url ->
                                // 根据 mediaType 区分图片还是视频（onImageClick 传入的是 resolve 后的完整 URL）
                                val m = messages.find { resolveMediaUrl(it.mediaUrl) == url }
                                if (m?.mediaType == "video" || m?.mediaType == "mp4") {
                                    videoPlayUrl = url
                                } else {
                                    // 收集当前对话全部图片并 resolve 成完整 URL，点击后进入多图左右滑动预览；
                                    // 用完整 URL 渲染，避免全屏查看器拿到相对路径导致黑屏。
                                    val imgUrls = messages
                                        .filter {
                                            (it.mediaType == "image" || it.mediaType == "jpg" ||
                                                it.mediaType == "png" || it.mediaType == "gif" ||
                                                it.mediaType == "jpeg") && it.mediaUrl.isNotEmpty()
                                        }
                                        .map { resolveMediaUrl(it.mediaUrl) }
                                        .distinct()
                                    val idx = imgUrls.indexOf(url)
                                    imagePreviewUrls = imgUrls
                                    imagePreviewIndex = if (idx >= 0) idx else 0
                                }
                            },
                            onFileClick = { url, fileName ->
                                val resolved = com.aurora.chat.ui.chat.resolveMediaUrl(url)
                                val ext = fileName.substringAfterLast('.', "").lowercase()
                                val imgExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
                                val vidExts = setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg")
                                when {
                                    ext in imgExts -> {
                                        // 图片 → 打开全屏图片预览（和图片消息气泡同一路径）
                                        val imgUrls = messages
                                            .filter { (it.mediaType == "image" || it.mediaType == "jpg" || it.mediaType == "png" || it.mediaType == "gif" || it.mediaType == "jpeg") && it.mediaUrl.isNotEmpty() }
                                            .map { resolveMediaUrl(it.mediaUrl) }
                                            .toMutableList()
                                        if (resolved !in imgUrls) imgUrls.add(0, resolved)
                                        val idx = imgUrls.indexOf(resolved)
                                        imagePreviewUrls = imgUrls.distinct()
                                        imagePreviewIndex = if (idx >= 0) idx else 0
                                    }
                                    ext in vidExts -> {
                                        // 视频 → 打开视频全屏播放器
                                        videoPlayUrl = resolved
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
                                val _ext = fileName.substringAfterLast('.', "").lowercase()
                                startFileSaveAs(_resolved, fileName, _ext)
                            },
                            uploadProgressMap = uploadProgressMap,
                            uploadStageMap = uploadStageMap,
                            uploadRatioMap = uploadRatioMap,
                            uploadFileNameMap = uploadFileNameMap,
                            onReasoningToggled = {
                                // 展开/收起思考过程时保持该条消息在屏幕上的位置不动。
                                // 1) 先退出「自动跟随」：否则下一段流式增量又会把列表拽回底部，
                                //    这正是「点开思考就跳回底部」的直接原因；用户再次滑到底部会自动恢复跟随。
                                userScrolledAway = true
                                // 2) 用「重排前后同一条目的位移」反向补偿，而不是 scrollToItem(idx, 旧偏移)：
                                //    反向列表(reverseLayout)里偏移的方向解释与直觉相反，直接套旧值容易整列表滚到底。
                                val idx = index.coerceAtLeast(0)
                                val beforeOffset = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == idx }?.offset
                                scope.launch {
                                    try {
                                        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
                                        val afterOffset = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == idx }?.offset
                                        if (beforeOffset != null && afterOffset != null) {
                                            val delta = afterOffset - beforeOffset
                                            if (delta != 0) listState.dispatchRawDelta(delta.toFloat())
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            onCodeBackToTop = {
                                // 代码块「回到顶部」：把该条消息（代码块所在）滚动到可视区顶部
                                scope.launch {
                                    try { listState.scrollToItem(index.coerceAtLeast(0), 0) } catch (_: Exception) {}
                                }
                            },
                            expandedReasoningMsgId = expandedReasoningMsgId,
                            onContinueAi = {
                                // 「继续」：在同一消息内从断点续写。把已生成部分作为上下文中最后一条 assistant 内容，
                                // 让 AI 从该处继续追加（不重复部分文本），完成后移除「已手动停止 / 继续」。
                                if (friendId == AI_CHAT_ID) {
                                    val ci = messages.indexOfFirst { it.id == msg.id }
                                    if (ci >= 0 && messages[ci] is ChatMsg && !aiWriting.value) {
                                        val target = messages[ci] as ChatMsg
                                        if (target.manualStopped) {
                                            val baseText = target.text.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                                            val mode = AiChatManager.getMode(ctx, currentUserId)
                                            // 重建系统提示（与主发送链路一致的极致省/性格/高级选项）
                                            val sysText = buildString {
                                                // 「节省 Token / 极致节省」模式已移除；性格/自定义设定仅在「开启性格调试」为 ON 时注入
                                                if (AiChatManager.getPersonalityDebug(ctx, currentUserId)) {
                                                    append(AiChatManager.buildPersonaSystemPrompt(AiChatManager.getTraits(ctx, currentUserId), AiChatManager.getCustomTraits(ctx, currentUserId)))
                                                    if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) append("\n\n").append(AiChatManager.PERSONA_SWITCH_RELEASE)
                                                }
                                            }
                                            val sysFinal = buildString {
                                                append(sysText)
                                                append(AiChatManager.buildAdvancedSystemPrompt(ctx, currentUserId))
                                                append(AiChatManager.multiOutputConstraint(ctx, currentUserId))
                                                if (reverseHistory) append("\n\n【重要设定】本次对话的聊天记录是「反转」的：原本由用户（我方）发送的消息，现在代表「对方」；原本由你（AI）发送的回复，现在代表「我（用户）」。请你以用户（我方）的视角和身份，继续与对方自然对话，不要提及这是反转设定，也不要点破角色互换。")
                                                if (currentTimePoint.isNotBlank()) append("\n\n【当前时间】$currentTimePoint")
                                                append(AiChatManager.buildAgentAwarenessPrompt(ctx, currentUserId))
                                            }
                                            // 去掉被续写的那条，并把已生成部分作为最后的 assistant 内容
                                            val cap = 40
                                            val cands = messages.filter { !it.isSystemNotice && it.text.isNotBlank() && it.text != AI_THINKING && it.id != msg.id }
                                            val clist = if (cands.size > cap) cands.take(1) + cands.takeLast(cap - 1) else cands
                                            val history = ((if (sysFinal.isNotBlank()) listOf("system" to sysFinal) else emptyList()) + clist.map { m ->
                                                val role = if (m.isMine != reverseHistory) "user" else "assistant"
                                                val content = if (m.replyToText.isNotEmpty()) "【引用了 ${m.replyToSender} 的消息：${m.replyToText}】\n${m.text}" else m.text
                                                role to content
                                            } + listOf("assistant" to baseText)).toMutableList()
                                            // 清除旧的停止标记，进入续写状态
                                            messages[ci] = target.copy(manualStopped = false)
                                            // 续写所属会话在启动时绑定，后续保存/渲染以此为准，防止切会话后写错文件
                                            val myKey = AiConversationSession.storageKey()
                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = baseText, reasoningText = aiReplyState.value?.reasoningText ?: "", done = false)
                                            aiStopRequested = false
                                            aiWriting.value = true
                                            aiWritingConvId = AiConversationSession.activeConvId
                                            activeAiJob = aiGlobalScope.launch {
                                                try {
                                                    // 多段思考：跨轮累积（reasoning_content），各段用哨兵拼接
                                                    var reasoningSegs = ""
                                                    var roundReasoning = ""
                                                    var thisRoundReasoning = ""  // 仅本轮思考，传给循环做内联嵌入（避免把累积思考重复内嵌）
                                                    val outcome = AiChatManager.runAgentChatLoop(
                                                        ctx = ctx, userId = currentUserId, history = history,
                                                        onNeedApproval = { tool, args -> toolApprovalHook(tool, args) },
                                                        onFileSent = { fs -> insertAiFileCard(fs.absPath, fs.forceFileCard, msg.id) },
                                                        onMediaGenerated = { t, url -> insertAiMediaCard(t, url, msg.id) },
                                                        onToolStage = { st ->
                                                            // 工具状态直接内联进当前 AI 消息文本（哨兵块随正文持久化，绝不丢失）：
                                                            // 调用开始(0)追加「正在调用」状态行，完成(1/2)把同名状态行原地替换为「已调用/失败」，
                                                            // 让状态显示在调用真正发生的位置，而不是堆到所有结果之后。
                                                            val sIdx = messages.indexOfFirst { x -> x.id == msg.id }
                                                            if (sIdx >= 0 && messages[sIdx] is ChatMsg) {
                                                                val cur = messages[sIdx] as ChatMsg
                                                                val newText = if (st.second == 0) {
                                                                    AiChatManager.appendToolInline(cur.text, st.first, 0)
                                                                } else {
                                                                    AiChatManager.replaceToolInline(cur.text, st.first, st.second)
                                                                }
                                                                messages[sIdx] = cur.copy(text = newText)
                                                                scheduleAiDebouncedSave()
                                                                // 工具状态变化:同流式增量一样,钉住 anchor
                                                                if (userScrolledAway && anchorItemIndex > 0) {
                                                                    aiGlobalScope.launch { listState.scrollToItem(anchorItemIndex, anchorItemOffset) }
                                                                } else if (atBottomState.value && !userScrolledAway) {
                                                                    try { aiGlobalScope.launch { listState.scrollToItem(0) } } catch (_: Exception) {}
                                                                }
                                                            }
                                                            // 工具完成后同步刷新 UI 模式状态（Plan 模式下 AI 可能已用 set_agent_mode 自动切换到 craft）
                                                            agentMode = AiChatManager.getAgentMode(ctx, currentUserId)
                                                        },
                                                        appActionHandler = onAppAction,
                                                        onProgress = { hint ->
                                                            val sIdx = messages.indexOfFirst { x -> x.id == msg.id }
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
                                                            if (isFirstRound) baseText + cleaned
                                                            else baseText + (if (prefix.isBlank()) cleaned else prefix + "\n\n" + cleaned)
                                                        }
                                                        var done = false
                                                        var ft = ""
                                                        var u = 0L
                                                        var th = 0L
                                                        var tt = 0L
                                                        var err = false
                                                        AiChatManager.streamChat(
                                                        ctx = ctx, userId = currentUserId, history = history,
                                                        stopCheck = { aiStopRequested },
                                                        onReasoningDelta = { reasoning ->
                                                            roundReasoning = reasoning
                                                            // 每轮思考都实时刷新：已累积段 + 本轮思考，分段展示；退出「等待模型响应」空窗
                                                            val live = AiChatManager.appendReasoningSegment(reasoningSegs, reasoning)
                                                            val ri = messages.indexOfFirst { x -> x.id == msg.id }
                                                            if (ri >= 0) {
                                                                val rm = messages[ri] as ChatMsg
                                                                messages[ri] = rm.copy(
                                                                    reasoningText = if (AiChatManager.getShowReasoning(ctx, currentUserId)) live else rm.reasoningText,
                                                                    aiWaiting = false
                                                                )
                                                                scheduleAiDebouncedSave()
                                                            }
                                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(reasoningText = live)
                                                            // 流式增量时的滚动决策:
                                                            //   - 还在底部附近(userScrolledAway=false) → scrollToItem(0) 平滑跟随
                                                            //   - 已经在看历史(userScrolledAway=true) → 钉住 anchor,防止 index 0 长高把上面内容顶上去
                                                            if (userScrolledAway && anchorItemIndex > 0) {
                                                                aiGlobalScope.launch { listState.scrollToItem(anchorItemIndex, anchorItemOffset) }
                                                            } else if (atBottomState.value && !userScrolledAway) {
                                                                aiGlobalScope.launch { try { listState.scrollToItem(0) } catch (_: Exception) {} }
                                                            }
                                                        },
                                                        onDelta = { cur ->
                                                            val shown = shownFor(cur)
                                                            val i = messages.indexOfFirst { x -> x.id == msg.id }
                                                            if (i >= 0 && !(messages[i] as ChatMsg).manualStopped) {
                                                                messages[i] = (messages[i] as ChatMsg).copy(text = shown.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""), aiWaiting = false)
                                                                scheduleAiDebouncedSave()
                                                            }
                                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = shown)
                                                            if (userScrolledAway && anchorItemIndex > 0) {
                                                                aiGlobalScope.launch { listState.scrollToItem(anchorItemIndex, anchorItemOffset) }
                                                            } else if (atBottomState.value && !userScrolledAway) {
                                                                aiGlobalScope.launch { try { listState.scrollToItem(0) } catch (_: Exception) {} }
                                                            }
                                                        },
                                                        onDone = { usage, full, thinkingSeconds, totalSeconds ->
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
                                                            val i = messages.indexOfFirst { x -> x.id == msg.id }
                                                            if (i >= 0) {
                                                                val keepReasoning = if (AiChatManager.getShowReasoning(ctx, currentUserId)) reasoningSegs else (messages[i] as ChatMsg).reasoningText
                                                                messages[i] = (messages[i] as ChatMsg).copy(text = shown.replace(AiChatManager.MULTI_OUTPUT_SPLIT, ""), reasoningText = keepReasoning, aiWaiting = false)
                                                            }
                                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = shown)
                                                            scheduleAiDebouncedSave()
                                                        },
                                                        onError = { errMsg ->
                                                            err = true
                                                            lastErrMsg = errMsg
                                                            aiWriting.value = false
                                                            aiWritingConvId = -1L
                                                            aiStopRequested = false
                                                            activeAiJob = null
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
                                                        },
                                                        onTokenInsufficient = onTokenExhausted,
                                                        nativeToolAccumulator = nativeCalls,
                                                        enableAgentTools = agentMode != AiChatManager.MODE_ASK,
                                                        toolChoice = toolChoice,
                                                        onFinish = { r -> finishReason = r }
                                                    )
                                                    AiChatManager.AgentRoundResult(done && !err, ft, u, th, tt, thisRoundReasoning, finishReason, if (err) com.aurora.chat.ui.chat.AgentTaskCompletion.isRetriable(lastErrMsg) else false)
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
                                                    aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(text = combined, done = true)
                                                    if (stillHere) {
                                                        if (i >= 0) messages[i] = (messages[i] as ChatMsg).copy(text = combined, reasoningText = finalReasoning, manualStopped = false, thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds, aiWaiting = false, aiProgressHint = "", aiFileChanges = AiChatManager.serializeFileChanges(outcome.fileChanges), aiToolCallCount = outcome.toolCallCount, aiToolStages = outcome.toolCalls)
                                                        // 工具状态行动画收回:已调用/失败/调用中全部不遗留
                                                        collapseToolStatusMessages(messages)
                                                        val snap = messages.filter { !it.isSystemNotice }.toList()
                                                        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                                                    } else {
                                                        // 已切走：把完整续写结果合并写回所属会话文件，用户切回即可见
                                                        aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, myKey, msg.id, text = combined, reasoningText = finalReasoning, thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds, fileChangesJson = AiChatManager.serializeFileChanges(outcome.fileChanges), toolCallCount = outcome.toolCallCount, toolStages = outcome.toolCalls) } catch (_: Exception) {} }
                                                    }
                                                    if (mode == "official" && outcome.usage > 0) {
                                                        AiChatManager.addOfficialUsage(ctx, currentUserId, outcome.usage)
                                                    }
                                                } else {
                                                    // ═══ 续写链路中断收尾（手动停止/出错等 clean=false 路径）═══
                                                    // 与主链路一致：中断也把已发生的真实改动/工具调用写回消息并落盘，
                                                    // 底部「查看所有改动」「调用 N 个工具」按钮不因中断而消失、改动不丢。
                                                    aiWriting.value = false
                                                    aiWritingConvId = -1L
                                                    aiStopRequested = false
                                                    activeAiJob = null
                                                    aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == msg.id }?.copy(done = true)
                                                    val hasCalls = outcome.toolCallCount > 0
                                                    val hasChanges = outcome.fileChanges.isNotEmpty()
                                                    if (hasCalls || hasChanges) {
                                                        val stillHere = AiConversationSession.storageKey() == myKey
                                                        val j = if (stillHere) messages.indexOfFirst { x -> x.id == msg.id } else -1
                                                        if (j >= 0) {
                                                            val cur = messages[j] as ChatMsg
                                                            messages[j] = cur.copy(
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
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        } // ← 关闭该消息的 item{} 内容块
                    } // ← 关闭 forEach
                }
            }

            // 删除确认弹窗
            if (deleteConfirmMsgId > 0L) {
                AlertDialog(
                    onDismissRequest = { deleteConfirmMsgId = 0L },
                    containerColor = Color.White,
                    title = { Text("确认删除", fontWeight = FontWeight.Bold) },
                    text = { Text(if (isNotificationChat(friendId)) "确认删除此条通知？删除后将从通知中心移除，且无法恢复。" else "确认删除此条消息，确认后将删除本地消息") },
                    confirmButton = {
                        TextButton(onClick = {
                            val targetId = deleteConfirmMsgId
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
                        }) { Text("删除", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteConfirmMsgId = 0L }) { Text("取消", color = Color(0xFF6B7280)) }
                    }
                )
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
                    containerColor = Color.White,
                    title = { Text("发送系统广播", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            // —— 发送目标：固定为当前所在会话，不可切换 ——
                            Text("发送目标", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF374151))
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFF3F4F6), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = when {
                                        isGroupChat(friendId) -> "当前群聊（$bcCurName）"
                                        friendId > 0 -> "当前私聊（$bcCurName）"
                                        else -> "当前会话"
                                    },
                                    color = Color(0xFF374151), fontSize = 12.sp
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
                        TextButton(onClick = { showBroadcastSendDialog = false }) { Text("取消", color = Color(0xFF6B7280)) }
                    }
                )
            }

            // ==================== 开发者广播：长按管理弹窗（删除单个 / 撤回全部） ====================
            if (broadcastManageMsgId > 0L) {
                AlertDialog(
                    onDismissRequest = { broadcastManageMsgId = 0L },
                    containerColor = Color.White,
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
                            TextButton(onClick = { broadcastManageMsgId = 0L }) { Text("取消", color = Color(0xFF6B7280)) }
                        }
                    }
                )
            }

            // 多选删除确认（仅删除选中的第一层消息）
            if (multiDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { multiDeleteConfirm = false },
                    containerColor = Color.White,
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
                        }) { Text("删除", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { multiDeleteConfirm = false }) { Text("取消", color = Color(0xFF6B7280)) }
                    }
                )
            }

            // 官方 API 被管理员暂停的明确提示：需要普通用户一眼看懂，避免误以为发送 bug
            if (apiSuspendedDialog) {
                AlertDialog(
                    onDismissRequest = { apiSuspendedDialog = false },
                    containerColor = Color.White,
                    title = { Text("官方 API 已暂停", fontWeight = FontWeight.Bold, color = Color(0xFFB45309)) },
                    text = { Text("管理员已暂停官方 AI 接口，本次消息未发送。\n\n你可以：\n· 在右上角「使用」中切换为个人 API；\n· 或等待管理员恢复后重试。", color = Color(0xFF374151)) },
                    confirmButton = {
                        TextButton(onClick = { apiSuspendedDialog = false }) { Text("知道了", color = Color(0xFF2563EB), fontWeight = FontWeight.SemiBold) }
                    }
                )
            }

            // ==================== 消息修改弹窗（长按 → 修改：编辑思考部分与实际输出，保存后写回本地） ====================
            if (showEditDialog) {
                AlertDialog(
                    onDismissRequest = { showEditDialog = false },
                    containerColor = Color.White,
                    title = { Text("修改消息", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            // 思考内容只对 AI 的回复有意义：编辑「自己的消息」时隐藏思考输入框（用户消息没有思考过程，也不应由用户编辑）
                            val editingOwnMessage = messages.firstOrNull { it.id == editTargetMsgId }?.isMine == true
                            if (!editingOwnMessage) {
                                Text("思考内容", fontSize = 12.sp, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.OutlinedTextField(
                                        value = editReasoningText,
                                        onValueChange = { editReasoningText = it },
                                        modifier = Modifier.weight(1f).heightIn(min = 80.dp, max = 140.dp),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                        placeholder = { Text("AI 的思考过程（可留空）", fontSize = 13.sp, color = Color(0xFF9CA3AF)) }
                                    )
                                    TextButton(onClick = { editReasoningText = "" }) { Text("清空", fontSize = 13.sp, color = Color(0xFFDC2626)) }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            Text("实际输出", fontSize = 12.sp, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = editOutputText,
                                    onValueChange = { editOutputText = it },
                                    modifier = Modifier.weight(1f).heightIn(min = 100.dp, max = 180.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                    placeholder = { Text("AI 回复的内容", fontSize = 13.sp, color = Color(0xFF9CA3AF)) }
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
                        TextButton(onClick = { showEditDialog = false }) { Text("取消", color = Color(0xFF6B7280)) }
                    }
                )
            }

            // ==================== 「编辑自己最后一条消息 → 重置并重新发送」确认弹窗（仅 AI 对话） ====================
            // 确定 → 以安全顺序：先中断在途 AI 回复并隔离代次，删除最后一组（该消息及其配对 AI 回复），再复用确认发言链路重发。
            // 取消 → 保留刚才的编辑（不重发）。
            if (resendNewChatConfirm) {
                AlertDialog(
                    onDismissRequest = { resendNewChatConfirm = false },
                    containerColor = Color.White,
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
                        TextButton(onClick = { resendNewChatConfirm = false; Toast.makeText(ctx, "已保留修改", Toast.LENGTH_SHORT).show() }) { Text("取消", color = Color(0xFF6B7280)) }
                    }
                )
            }

            // 订单详情弹窗：从通知中心「查看订单」进入，展示与开发者管理一致的订单信息
            viewOrderId?.let { oid ->
                OrderDetailView(orderId = oid, onDismiss = { viewOrderId = null })
            }

            }


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

            // 引用预览条（固定在输入框正上方，绝不跑位）
            replyToMsg.value?.let { reply ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFDBEAFE)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${reply.senderName}:${if (reply.text.isNotEmpty()) reply.text else when (reply.mediaType) { "image" -> "[图片]"; "video", "mp4" -> "[视频]"; "file" -> "[文件]"; "voice" -> "[语音]"; "transfer" -> "[转账]"; else -> "[消息]" }}",
                        fontSize = 12.sp, color = Color(0xFF6B7280),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box(Modifier.size(20.dp).clickable { replyToMsg.value = null }, contentAlignment = Alignment.Center) {
                        Text("✕", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                    }
                }
            }

            // 底部输入栏（在 Column 内部、LazyColumn 下方；严格位于消息区以下）
            // 通知系统：用户不可发消息，隐藏输入栏；非群成员（待审核）同样隐藏
            if (!isNotificationChat(friendId) && !isNotGroupMember) {
            if (!multiSelectMode) {
            // AI 对话「图片」附件条：选择后暂存在输入框正上方，随发送按钮一起发出去
            if (friendId == AI_CHAT_ID && pendingAiImages.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F4FF)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("图片", fontSize = 12.sp, color = Color(0xFF6B7280),
                        modifier = Modifier.padding(end = 10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(pendingAiImages) { index, img ->
                            Box {
                                Box(
                                    Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE5E7EB))
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
                        Text("✕", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                    }
                }
            }
            // AI 对话「文件」附件条：选择后暂存在输入框正上方，随发送按钮一起发出去
            if (friendId == AI_CHAT_ID && pendingAiFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F9FF)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("文件", fontSize = 12.sp, color = Color(0xFF6B7280), modifier = Modifier.padding(end = 10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(pendingAiFiles) { index, file ->
                            Box {
                                Row(
                                    Modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE0F2FE))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFF0284C7),
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
                            color = Color(0xFF9CA3AF),
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
                        .background(Color(0xFFF3F4F6))
                        .padding(horizontal = 12.dp, vertical = 0.dp)
                        .height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧菜单按钮：点击向上滑出「访问控制」菜单（仅样式，功能待接）
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showAgentToolsMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Tune,
                            contentDescription = "Agent 工具",
                            tint = Color(0xFF6B7280),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 模式选择按钮：点击向上滑出「模式选择」菜单（Ask / Craft / Plan 语义模式）
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showAgentModeMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Category,
                            contentDescription = "模式选择",
                            tint = if (agentMode == AiChatManager.MODE_ASK) Color(0xFF2563EB)
                                   else if (agentMode == AiChatManager.MODE_PLAN) Color(0xFF7C3AED)
                                   else Color(0xFF16A34A),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 思考深度按钮：点击向上滑出「思考深度」菜单（低/中/高，底层控制模型输出预算与工具轮数）
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showThinkingDepthMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Psychology,
                            contentDescription = "思考深度",
                            tint = when (thinkingDepth) {
                                AiChatManager.THINK_LOW -> Color(0xFF6B7280)
                                AiChatManager.THINK_HIGH -> Color(0xFFEA580C)
                                else -> Color(0xFF2563EB)
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 执行模式按钮：点击向上滑出「执行模式」菜单（标准/极速/长任务/自检/深度研究/规划先行，多 Agent 与记忆增强占位）
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showExecModeMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = "执行模式",
                            // 多选:任一模式激活即着色,颜色取优先级最高(列表靠前)的激活模式
                            tint = run {
                                val execColors = mapOf(
                                    AiChatManager.EXEC_FAST to Color(0xFF059669),
                                    AiChatManager.EXEC_LONG to Color(0xFFD97706),
                                    AiChatManager.EXEC_SELFCHECK to Color(0xFF7C3AED),
                                    AiChatManager.EXEC_RESEARCH to Color(0xFF0D9488),
                                    AiChatManager.EXEC_PLAN to Color(0xFF2563EB),
                                    AiChatManager.EXEC_MULTI_AGENT to Color(0xFFDB2777),
                                    AiChatManager.EXEC_MEMORY to Color(0xFF4F46E5),
                                    AiChatManager.EXEC_CREATE to Color(0xFFBE185D)
                                )
                                execModes.mapNotNull { m -> execColors[m] }.firstOrNull() ?: Color(0xFF6B7280)
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
                    // 插件市场按钮：打开插件市场面板（全屏，右侧滑入，与工作区同模板）
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showPluginMarket = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = "插件市场",
                            tint = Color(0xFFD97706),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            // Agent「访问控制」菜单：从底部向上滑出（仅样式，功能待接）
            if (friendId == AI_CHAT_ID && showAgentToolsMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showAgentToolsMenu = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    ) {
                        // 标题
                        Text(
                            "访问控制",
                            fontSize = 16.sp, color = Color(0xFF1F2937), fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        // ── root 模式（紫标，仅设备已 root 可选）──
                        val isRoot = agentAccessMode == AiChatManager.ACCESS_ROOT
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isRoot) Color(0xFFF5F3FF) else Color.White)
                                .border(if (isRoot) 1.dp else 0.dp, Color(0xFF7C3AED), RoundedCornerShape(10.dp))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) {
                                    if (AiChatManager.isDeviceRooted()) {
                                        agentAccessMode = AiChatManager.ACCESS_ROOT
                                        AiChatManager.setAccessMode(ctx, currentUserId, AiChatManager.ACCESS_ROOT)
                                    } else {
                                        Toast.makeText(ctx, "你的设备未 root，请 root 后再选择", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "root 模式",
                                fontSize = 15.sp, color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            if (isRoot) Text("✓", fontSize = 15.sp, color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        // ── 完全访问（红色警示，选中加红框）──
                        val isFull = agentAccessMode == AiChatManager.ACCESS_FULL
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isFull) Color(0xFFFEF2F2) else Color.White)
                                .border(if (isFull) 1.dp else 0.dp, Color(0xFFDC2626), RoundedCornerShape(10.dp))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) {
                                    agentAccessMode = AiChatManager.ACCESS_FULL
                                    AiChatManager.setAccessMode(ctx, currentUserId, AiChatManager.ACCESS_FULL)
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "完全访问",
                                fontSize = 15.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            if (isFull) Text("✓", fontSize = 15.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        // ── 手动审批（默认，选中加边框）──
                        val isManual = agentAccessMode == AiChatManager.ACCESS_MANUAL
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isManual) Color(0xFFEFF6FF) else Color.White)
                                .border(if (isManual) 1.dp else 0.dp, Color(0xFF2563EB), RoundedCornerShape(10.dp))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) {
                                    agentAccessMode = AiChatManager.ACCESS_MANUAL
                                    AiChatManager.setAccessMode(ctx, currentUserId, AiChatManager.ACCESS_MANUAL)
                                }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "手动审批",
                                fontSize = 15.sp, color = if (isManual) Color(0xFF2563EB) else Color(0xFF374151), fontWeight = if (isManual) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (isManual) Text("✓", fontSize = 15.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // Agent「模式选择」菜单：从底部向上滑出（Ask / Craft / Plan 语义模式，选中即持久化）
            if (friendId == AI_CHAT_ID && showAgentModeMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showAgentModeMenu = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    ) {
                        // 标题
                        Text(
                            "模式选择",
                            fontSize = 16.sp, color = Color(0xFF1F2937), fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        // ── Ask 模式（蓝色）──
                        val isAsk = agentMode == AiChatManager.MODE_ASK
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isAsk) Color(0xFFEFF6FF) else Color.White)
                                .border(if (isAsk) 1.dp else 0.dp, Color(0xFF2563EB), RoundedCornerShape(10.dp))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) {
                                    agentMode = AiChatManager.MODE_ASK
                                    AiChatManager.setAgentMode(ctx, currentUserId, AiChatManager.MODE_ASK)
                                    showAgentModeMenu = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Ask",
                                fontSize = 15.sp, color = if (isAsk) Color(0xFF2563EB) else Color(0xFF374151), fontWeight = if (isAsk) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (isAsk) Text("✓", fontSize = 15.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    ) { modeExplainDialog = AiChatManager.MODE_ASK },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = "Ask 模式说明",
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // ── Craft 模式（绿色，默认）──
                        val isCraft = agentMode == AiChatManager.MODE_CRAFT
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isCraft) Color(0xFFF0FDF4) else Color.White)
                                .border(if (isCraft) 1.dp else 0.dp, Color(0xFF16A34A), RoundedCornerShape(10.dp))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) {
                                    agentMode = AiChatManager.MODE_CRAFT
                                    AiChatManager.setAgentMode(ctx, currentUserId, AiChatManager.MODE_CRAFT)
                                    showAgentModeMenu = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Craft",
                                fontSize = 15.sp, color = if (isCraft) Color(0xFF16A34A) else Color(0xFF374151), fontWeight = if (isCraft) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (isCraft) Text("✓", fontSize = 15.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    ) { modeExplainDialog = AiChatManager.MODE_CRAFT },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = "Craft 模式说明",
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // ── Plan 模式（紫色）──
                        val isPlan = agentMode == AiChatManager.MODE_PLAN
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isPlan) Color(0xFFF5F3FF) else Color.White)
                                .border(if (isPlan) 1.dp else 0.dp, Color(0xFF7C3AED), RoundedCornerShape(10.dp))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) {
                                    agentMode = AiChatManager.MODE_PLAN
                                    AiChatManager.setAgentMode(ctx, currentUserId, AiChatManager.MODE_PLAN)
                                    showAgentModeMenu = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Plan",
                                fontSize = 15.sp, color = if (isPlan) Color(0xFF7C3AED) else Color(0xFF374151), fontWeight = if (isPlan) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (isPlan) Text("✓", fontSize = 15.sp, color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold)
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    ) { modeExplainDialog = AiChatManager.MODE_PLAN },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = "Plan 模式说明",
                                    tint = Color(0xFF9CA3AF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
            // Agent「思考深度」菜单：从底部向上滑出（低/中/高，选中即持久化；底层控制输出预算与工具轮数）
            if (friendId == AI_CHAT_ID && showThinkingDepthMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showThinkingDepthMenu = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    ) {
                        Text(
                            "思考深度",
                            fontSize = 16.sp, color = Color(0xFF1F2937), fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        // 三档共用一个渲染块：低（灰）/ 中（蓝）/ 高（橙）
                        data class DepthOption(val key: String, val title: String, val desc: String, val color: Color, val bg: Color)
                        val depthOptions = listOf(
                            DepthOption(AiChatManager.THINK_LOW, "低", "快速响应，省 token；最多 3 轮工具调用", Color(0xFF6B7280), Color(0xFFF3F4F6)),
                            DepthOption(AiChatManager.THINK_MEDIUM, "中", "均衡（默认）；最多 6 轮工具调用", Color(0xFF2563EB), Color(0xFFEFF6FF)),
                            DepthOption(AiChatManager.THINK_HIGH, "高", "深度推理，工具轮数不设上限", Color(0xFFEA580C), Color(0xFFFFF7ED))
                        )
                        depthOptions.forEachIndexed { idx, opt ->
                            if (idx > 0) Spacer(Modifier.height(10.dp))
                            val selected = thinkingDepth == opt.key
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) opt.bg else Color.White)
                                    .border(if (selected) 1.dp else 0.dp, opt.color, RoundedCornerShape(10.dp))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    ) {
                                        thinkingDepth = opt.key
                                        AiChatManager.setThinkingDepth(ctx, currentUserId, opt.key)
                                        showThinkingDepthMenu = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        opt.title,
                                        fontSize = 15.sp, color = if (selected) opt.color else Color(0xFF374151),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        opt.desc,
                                        fontSize = 12.sp, color = Color(0xFF9CA3AF),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                if (selected) Text("✓", fontSize = 15.sp, color = opt.color, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            // Agent「执行模式」菜单：从底部向上滑出,支持多选叠加(冲突项自动拦截);
            // 内容可垂直滚动,后续新增模式不会溢出屏幕。选中即持久化,编排层与提示词同步生效
            if (friendId == AI_CHAT_ID && showExecModeMenu) {
                ModalBottomSheet(
                    onDismissRequest = { showExecModeMenu = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                    ) {
                        Text(
                            "执行模式",
                            fontSize = 16.sp, color = Color(0xFF1F2937), fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        data class ExecOption(val key: String, val title: String, val desc: String, val color: Color, val bg: Color, val exclusive: Boolean)
                        val execOptions = listOf(
                            ExecOption(AiChatManager.EXEC_FAST, "极速", "纯对话，禁用全部工具调用，最快最省（与其余模式互斥）", Color(0xFF059669), Color(0xFFECFDF5), true),
                            ExecOption(AiChatManager.EXEC_LONG, "长任务", "工具轮数不设上限，进度落盘支持断点续跑", Color(0xFFD97706), Color(0xFFFFFBEB), false),
                            ExecOption(AiChatManager.EXEC_SELFCHECK, "自检", "干完活后 AI 重新核验自己的成果，通过才交付（与多 Agent 互斥）", Color(0xFF7C3AED), Color(0xFFF5F3FF), false),
                            ExecOption(AiChatManager.EXEC_RESEARCH, "深度研究", "先多角度联网检索交叉验证，再输出结构化报告", Color(0xFF0D9488), Color(0xFFF0FDFA), false),
                            ExecOption(AiChatManager.EXEC_PLAN, "规划先行", "多步任务先写方案文件，边干边更新进度", Color(0xFF2563EB), Color(0xFFEFF6FF), false),
                            ExecOption(AiChatManager.EXEC_MULTI_AGENT, "多 Agent", "执行者干活 + 独立验收 Agent 核验，不合格自动打回重做（与自检互斥）", Color(0xFFDB2777), Color(0xFFFDF2F8), false),
                            ExecOption(AiChatManager.EXEC_MEMORY, "记忆增强", "自动读取并维护长期记忆 memory.md，跨对话记住偏好", Color(0xFF4F46E5), Color(0xFFEEF2FF), false),
                            ExecOption(AiChatManager.EXEC_CREATE, "创作", "AI 替你在插件市场做插件：问清需求→调试接口→提交上架", Color(0xFFBE185D), Color(0xFFFDF2F8), false)
                        )
                        fun activeNames(conflicts: Set<String>): List<String> =
                            execOptions.filter { it.key in conflicts && it.key in execModes }.map { it.title }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            execOptions.forEachIndexed { idx, opt ->
                                if (idx > 0) Spacer(Modifier.height(10.dp))
                                val selected = opt.key in execModes
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) opt.bg else Color.White)
                                        .border(if (selected) 1.dp else 0.dp, opt.color, RoundedCornerShape(10.dp))
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                        ) {
                                            // 多选逻辑:极速=独占(选中即清空其它);其余模式点击切换,与已激活模式冲突时拦截并提示
                                            val conflicts = AiChatManager.EXEC_CONFLICTS[opt.key] ?: emptySet()
                                            if (selected) {
                                                execModes = execModes - opt.key
                                                AiChatManager.setExecModes(ctx, currentUserId, execModes)
                                            } else if (opt.key == AiChatManager.EXEC_FAST) {
                                                execModes = setOf(AiChatManager.EXEC_FAST)
                                                AiChatManager.setExecModes(ctx, currentUserId, execModes)
                                            } else {
                                                val clashing = activeNames(conflicts)
                                                if (clashing.isNotEmpty()) {
                                                    Toast.makeText(ctx, "与「${clashing.joinToString("、")}」互相干扰,不能同时开启", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    execModes = execModes + opt.key
                                                    AiChatManager.setExecModes(ctx, currentUserId, execModes)
                                                }
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            opt.title,
                                            fontSize = 15.sp, color = if (selected) opt.color else Color(0xFF374151),
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Text(
                                            opt.desc,
                                            fontSize = 12.sp, color = Color(0xFF9CA3AF),
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    // 多选勾选框:选中打勾;极速独占不加勾选框样式区分
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (selected) opt.color else Color.Transparent)
                                            .border(1.5.dp, if (selected) opt.color else Color(0xFFD1D5DB), RoundedCornerShape(6.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) Text("✓", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        // 底部确定:收起菜单(选择即时生效,无需额外提交)
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E40AF))
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                ) { showExecModeMenu = false }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (execModes.isEmpty()) "标准模式（未选任何附加模式）· 确定" else "已选 ${execModes.size} 个模式 · 确定",
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White
                            )
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
                    AiChatManager.MODE_PLAN -> "开启后AI会根据你的所需，先在工作区的目录下创建规划方案然后才考虑执行"
                    else -> "拥有完整的Agent能力可完成工具的调用有基础权限"
                }
                AlertDialog(
                    onDismissRequest = { modeExplainDialog = null },
                    title = { Text("$modeName 模式", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF1F2937)) },
                    text = { Text(modeDesc, fontSize = 14.sp, color = Color(0xFF374151), lineHeight = 22.sp) },
                    confirmButton = {
                        TextButton(onClick = { modeExplainDialog = null }) { Text("知道了", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF)) }
                    }
                )
            }
            // 工具执行审批对话框（非 root / 手动审批 / 完全访问下的删改工具）
            if (pendingToolApproval != null) {
                val desc = com.aurora.chat.ui.chat.describeToolCall(pendingToolApproval!!.first, pendingToolApproval!!.second)
                AlertDialog(
                    onDismissRequest = {
                        pendingToolApprovalDeferred.value?.complete(false)
                        pendingToolApproval = null
                    },
                    title = { Text("权限审批") },
                    text = {
                        Text(
                            "AI 即将执行以下操作：\n${desc}\n\n是否允许？",
                            color = Color(0xFF374151)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingToolApprovalDeferred.value?.complete(true)
                            pendingToolApproval = null
                        }) { Text("允许") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            pendingToolApprovalDeferred.value?.complete(false)
                            pendingToolApproval = null
                        }) { Text("拒绝") }
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
                    onOpenCamera = { showCamera = true },
                    onRequestLocation = { showLocationConfirm = true },
                    onSecretClick = { showPrivacyPanel = true },
                    onTransfer = { showTransferDialog = true },
                    onRedPacket = { showRedPacketDialog = true },
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
                    onPickAiFile = { aiFilePicker.launch(arrayOf("*/*")) },
                    aiMode = AiChatManager.getMode(ctx, currentUserId),
                    onModeSelect = { showModeSelectDialog = true },
                    // 发送按钮「停止 ↔ 发送」按【当前会话】判定：仅当正在生成的会话就是当前会话时才显示「停止」。
        // 修复跨会话污染：A 会话在后台生成时新建/切到 B 会话，B 的按钮不再误显示「停止」，且点「停止」不会误取消 A 的在途任务。
        aiInFlight = aiWriting.value && aiWritingConvId == activeAiConvId,
                    onStopSend = {
                        // 手动停止：仅当“正在生成”的会话就是当前会话时才生效（按会话隔离按钮状态）。
                        // 否则（如 A 会话在后台生成、当前停在 B 会话）不误取消 A 的在途任务，也避免 B 的按钮被污染。
                        if (aiWriting.value && aiWritingConvId == activeAiConvId) {
                            aiStopRequested = true
                            activeAiJob?.cancel()
                            activeAiJob = null
                            aiWriting.value = false
                            aiWritingConvId = -1L
                            aiReplyState.value?.let { br ->
                                val idx = messages.indexOfFirst { m -> m.id == br.aiMsgId }
                                if (idx >= 0) {
                                    messages[idx] = (messages[idx] as ChatMsg).copy(manualStopped = true)
                                }
                                aiReplyState.value = br.copy(done = true, stopped = true)
                            }
                            aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, AiConversationSession.storageKey(), messages.filter { !it.isSystemNotice }) } catch (_: Exception) {} }
                        }
                    },
                    onSend = {
                    // AI 对话「图片/文件」：允许在输入框文本为空、但带了附件时也能发送
                    if (inputText.isNotBlank() || (friendId == AI_CHAT_ID && (pendingAiImages.isNotEmpty() || pendingAiFiles.isNotEmpty()))) {
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
                        val replyText = replyToMsg.value?.let { msg ->
                            if (msg.text.isNotEmpty()) msg.text else when (msg.mediaType) {
                                "image" -> "[图片]"
                                "video", "mp4" -> "[视频]"
                                "file" -> "[文件]"
                                "voice" -> "[语音]"
                                "transfer" -> "[转账]"
                                else -> "[消息]"
                            }
                        } ?: ""
                        val replySender = replyToMsg.value?.senderName ?: ""
                        val replyToId = replyToMsg.value?.id ?: 0L
                        replyToMsg.value = null   // 发送后清除引用
                        val sentMsg: ChatMsg? = if (!isLocalChat(friendId)) {
                            ChatMsg.create(text = text, isMine = true, fromUserId = currentUserId, isNew = true, replyToText = replyText, replyToSender = replySender, replyToId = replyToId).also { messages.add(it) }
                        } else null
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.scrollToItem(0)
                            }
                        }
                        // 统计发言字数
                        com.aurora.chat.ui.profile.WordCountTracker.addChars(ctx, text.length)

                        if (isLocalChat(friendId)) {
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
                            if (friendId == AI_CHAT_ID && sentAiFiles.isNotEmpty()) {
                                val workspace = AiChatManager.agentRoot(ctx)  // = filesDir/ai_files
                                for (f in sentAiFiles) {
                                    // 用安全文件名 + 时间戳防重名
                                    val safeName = f.name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
                                        .take(120)
                                    val dest = java.io.File(workspace,
                                        "${System.currentTimeMillis()}_${(0..9999).random()}_${safeName}")
                                    try {
                                        ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                                            dest.outputStream().use { output -> input.copyTo(output) }
                                        }
                                    } catch (_: Exception) {}
                                    if (!dest.exists() || dest.length() == 0L) continue
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
                                    try { dest.inputStream().use { input -> uiFile.outputStream().use { output -> input.copyTo(output) } } } catch (_: Exception) {}
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
                            val chatCandidates = messages.filter { !it.isSystemNotice && it.text.isNotBlank() && it.text != AI_THINKING }
                            val chatContext = AiChatManager.buildChatContext(chatCandidates, editTargetMsgId, ctx, currentUserId)

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
                            // 若只有图片没有文件，为避免同一段文字既走 history 又走多模态内容块，剔除本次文字；
                            // 若带有文件附件，则文字消息里包含文件提示，必须保留给 AI 上下文。
                            val chatContextFinal = if (aiImagesForSend.isNotEmpty() && currentAiTextMsgId != 0L && aiFileAttachmentHints.isEmpty())
                                chatContext.filter { it.id != currentAiTextMsgId } else chatContext

                            // AI 对话：本地存用户消息后调用接口流式回复
                            if (friendId == AI_CHAT_ID) {
                                // ── 生成模式（图片/视频）：仅 personal 生效，文本作为提示词；第三方模式改为由 AI 判断并调用 generate_media 工具生成 ──
                                val genAiMode = AiChatManager.getMode(ctx, currentUserId)
                                val genSupported = genAiMode == "personal"
                                val genMode = AiChatManager.getGenMode(ctx, currentUserId)
                                val wantVideo = genSupported && text.isNotBlank() && (genMode == AiChatManager.GEN_VIDEO ||
                                    (genMode == AiChatManager.GEN_SMART && AiChatManager.isVideoIntent(text)))
                                val wantImage = genSupported && text.isNotBlank() && (genMode == AiChatManager.GEN_IMAGE ||
                                    (genMode == AiChatManager.GEN_SMART && !wantVideo && AiChatManager.isImageIntent(text)))
                                if (wantImage || wantVideo) {
                                    val genKind = if (wantVideo) "video" else "image"
                                    val genHintId = System.currentTimeMillis() * 10000 + (0..9999).random()
                                    val aiSenderName = AiNameState.name ?: "AI"
                                    messages.add(ChatMsg.create(
                                        serverId = genHintId,
                                        text = if (genKind == "video") "正在生成视频：$text" else "正在生成图片：$text",
                                        isMine = false, fromUserId = AI_CHAT_ID, senderName = aiSenderName, isNew = true,
                                        createdAt = System.currentTimeMillis() / 1000
                                    ))
                                    saveLocalChatMessages(ctx, currentUserId, AiConversationSession.storageKey(), messages.filter { !it.isSystemNotice })
                                    // 生成任务所属会话在启动时绑定，完成后按归属写回，防止生成期间切走污染其它会话文件
                                    val genKey = AiConversationSession.storageKey()
                                    aiGlobalScope.launch {
                                        try {
                                            val res = if (genKind == "video") AiChatManager.generateVideo(ctx, currentUserId, text)
                                                      else AiChatManager.generateImage(ctx, currentUserId, text)
                                            // 后端已把图片存到本服务 chat_media，直接走 /chat-media 渲染（无需再传 2MB base64）
                                            val mediaPathUrl = res.mediaPath?.takeIf { it.isNotBlank() }
                                            val mediaBytes: ByteArray = when {
                                                mediaPathUrl != null -> ByteArray(0)
                                                res.b64 != null -> android.util.Base64.decode(res.b64, android.util.Base64.DEFAULT)
                                                res.url != null -> com.aurora.chat.data.api.HttpClient.downloadBytes(res.url)
                                                else -> ByteArray(0)
                                            }
                                            if (mediaPathUrl == null && mediaBytes.isEmpty()) {
                                                throw RuntimeException("生成服务未返回图片数据" + (res.taskId?.let { " (任务id=$it)" } ?: ""))
                                            }
                                            val mediaUrl: String = if (mediaPathUrl != null) {
                                                mediaPathUrl
                                            } else if (mediaBytes.isEmpty()) {
                                                ""
                                            } else if (genKind == "video") {
                                                // 视频保持本地落盘（文件较大，暂不批量上服务器）
                                                val dir = java.io.File(ctx.filesDir, "ai_chat"); if (!dir.exists()) dir.mkdirs()
                                                val f = java.io.File(dir, "${System.currentTimeMillis()}_${(0..9999).random()}.mp4")
                                                f.writeBytes(mediaBytes)
                                                "file://${f.absolutePath}"
                                            } else {
                                                // 图片：优先上传服务器，作为普通 /chat-media/ 聊天图渲染（消除外链/本地加载失败），
                                                // 开启新对话/删除消息时可同步删服务器文件，不留遗留。
                                                val up = AuroraApi.uploadChatMedia(mediaBytes, "ai_gen_${System.currentTimeMillis()}_${(0..9999).random()}.jpg", "image/jpeg")
                                                if (up.success && up.data != null) {
                                                    up.data.optString("url", "")
                                                } else {
                                                    val dir = java.io.File(ctx.filesDir, "ai_chat"); if (!dir.exists()) dir.mkdirs()
                                                    val f = java.io.File(dir, "${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
                                                    f.writeBytes(mediaBytes)
                                                    "file://${f.absolutePath}"
                                                }
                                            }
                                            val resultText = if (genKind == "video") "[视频]" else "[图片]"
                                            val resultType = if (genKind == "video") "video" else "image"
                                            if (AiConversationSession.storageKey() == genKey) {
                                                val hi = messages.indexOfFirst { it.id == genHintId }
                                                if (hi >= 0) messages.removeAt(hi)
                                                messages.add(ChatMsg.create(
                                                    serverId = System.currentTimeMillis() * 10000 + (0..9999).random(),
                                                    text = resultText,
                                                    isMine = false, fromUserId = AI_CHAT_ID, senderName = aiSenderName, isNew = true,
                                                    mediaType = resultType, mediaUrl = mediaUrl,
                                                    createdAt = System.currentTimeMillis() / 1000
                                                ))
                                                saveLocalChatMessages(ctx, currentUserId, genKey, messages.filter { !it.isSystemNotice })
                                                ChatViewModel.updateConversationPreview(friendId, resultText, System.currentTimeMillis() / 1000)
                                            } else {
                                                // 已切走：把「正在生成」提示原地替换为生成结果，写回所属会话文件
                                                val fId = genHintId
                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, genKey, fId, text = resultText, mediaType = resultType, mediaUrl = mediaUrl) } catch (_: Exception) {} }
                                            }
                                        } catch (e: Exception) {
                                            if (AiConversationSession.storageKey() == genKey) {
                                                val hi = messages.indexOfFirst { it.id == genHintId }
                                                if (hi >= 0) messages.removeAt(hi)
                                                messages.add(ChatMsg.create(
                                                    serverId = System.currentTimeMillis() * 10000 + (0..9999).random(),
                                                    text = "生成${if (genKind == "video") "视频" else "图片"}失败",
                                                    isMine = false, fromUserId = AI_CHAT_ID, senderName = aiSenderName, isNew = true,
                                                    createdAt = System.currentTimeMillis() / 1000
                                                ))
                                                saveLocalChatMessages(ctx, currentUserId, genKey, messages.filter { !it.isSystemNotice })
                                            } else {
                                                val fId = genHintId
                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { patchAiReplyInFile(ctx, currentUserId, genKey, fId, text = "生成${if (genKind == "video") "视频" else "图片"}失败") } catch (_: Exception) {} }
                                            }
                                            com.aurora.chat.ErrorReporter.warn("AiGen", "生成${genKind}失败: ${e.message}", e)
                                            android.widget.Toast.makeText(ctx, e.message ?: "生成失败", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                // 确认发言模式：跳过 AI 自动回复，标记有待确认消息
                                if (confirmSpeechEnabled) {
                                    confirmSpeechPending = true
                                } else {
                                val mode = AiChatManager.getMode(ctx, currentUserId)
                                val canCall: Boolean
                                val quotaMsg: String?
                            // 本次请求相关：baseUsed=发送前今日已用，poolAtStart=发送前剩余余额，estInput=本次输入估算
                            var estInput = 0L
                            val baseUsed: Long
                            var poolAtStart = 0L
                            if (mode == "official") {
                                // 余额只来自服务器：remaining 仅可为服务器真实值或 -1（未加载）。
                                // 仅当服务器明确返回 0 才拦截；未加载(-1)交由服务器裁决，不本地封顶。
                                // 发送前强制拉取最新余额（站A的同步刷新已修），站B因在同步块里不能直接调 suspend，
                                // 这里改为同步路径先读缓存余额判断，后续在下方 scope.launch 中强制刷新一次。
                                val (used, remaining) = AiChatManager.getOfficialUsage(ctx, currentUserId)
                                aiUsageState.value = Pair(used, remaining)
                                baseUsed = used
                                poolAtStart = remaining
                                estInput = chatContext.sumOf { AiChatManager.estimateTokens(it.text).toLong() }
                                canCall = remaining != 0L
                                quotaMsg = when {
                                    remaining == 0L ->
                                        "官方 API 余额已用完，可在右上角「使用」切换个人 API 或充值后继续使用"
                                    else -> null
                                }
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
                                    aiWriting.value = true
                                    aiWritingConvId = AiConversationSession.activeConvId
                                    activeAiJob = aiGlobalScope.launch {
                                        // 发送前再强制刷新一次服务器余额，并以「服务器真实 token_balance」为准判定，
                                        // 不受开发者无限额度影响；余额确为 0 时弹卡片，普通用户直接拦截、开发者仍放行。
                                    if (mode == "official") {
                                        AiChatManager.syncServerLimit(ctx, currentUserId)
                                        val rawBal = AiChatManager.getServerTokenBalance(ctx, currentUserId)
                                        if (rawBal <= 0L) {
                                            onTokenExhausted()
                                            if (!AiChatManager.isDeveloper(ctx, currentUserId)) return@launch
                                        }
                                    }
                                    // 构造上下文（排除系统提示与思考占位）
                                    // 性格：模型无状态，每次请求都必须把【当前性格设定】写入 system 提示，
                                    // 否则 AI 在没有性格指令时会回退到默认友好语气（这是之前"设定不生效"的根因）。
                                    // 因此不再用"事件驱动仅注入一次"，改为每次发送都携带最新性格设定。
                                    // 「开启性格调试」为 ON 时注入性格；关闭时不注入（忽略性格设定）。
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
                                    }
                                    // 高级选项（场景 / 角色设定 / AI 多次输出）
                                    // 人物对换 / 历史反转：仅 AI 对话生效
                                    val reversed = friendId == AI_CHAT_ID && reverseHistory
                                    val reverseNote = if (reversed) {
                                        "\n\n【重要设定】本次对话的聊天记录是「反转」的：原本由用户（我方）发送的消息，现在代表「对方」；" +
                                        "原本由你（AI）发送的回复，现在代表「我（用户）」。请你以用户（我方）的视角和身份，" +
                                        "继续与对方自然对话，不要提及这是反转设定，也不要点破角色互换。"
                                    } else ""
                                    val systemFinal = buildString {
                                        append(systemText)
                                        append(AiChatManager.buildAdvancedSystemPrompt(ctx, currentUserId))
                                        append(AiChatManager.multiOutputConstraint(ctx, currentUserId))
                                        if (reverseNote.isNotEmpty()) append(reverseNote)
                                        if (currentTimePoint.isNotBlank()) append("\n\n【当前时间】$currentTimePoint")
                                        append(AiChatManager.buildAgentAwarenessPrompt(ctx, currentUserId))
                                    }
                                    if (AiChatManager.getPersonaSwitch(ctx, currentUserId)) {
                                        AiChatManager.setPersonaSwitch(ctx, currentUserId, false)
                                    }
                                    // 若本次携带文件附件，把文件内容/提示注入到最后一条用户消息里给 AI，但 UI 气泡仍显示原文字
                                    val chatContextForAi = if (aiFileAttachmentHints.isNotEmpty() && currentAiTextMsgId != 0L) {
                                        chatContextFinal.map { m ->
                                            if (m.id == currentAiTextMsgId && m.isMine) (m as? ChatMsg)?.copy(text = textForAi) ?: m else m
                                        }
                                    } else chatContextFinal
                                    val history = ((if (systemFinal.isNotBlank()) listOf("system" to systemFinal) else emptyList()) + chatContextForAi.map { m -> 
                                        val role = if (m.isMine != reversed) "user" else "assistant"
                                        // 引用声明（仅 AI 对话生效）：把被引用/回复的消息内容用系统声明标注进上下文，
                                        // 让 AI 明确看到用户引用的是哪一条（普通对话走各自发送链路，不受此影响）
                                        val content = if (m.replyToText.isNotEmpty()) "【引用了 ${m.replyToSender} 的消息：${m.replyToText}】\n${m.text}" else m.text
                                        role to content
                                    }).toMutableList()
                                        val aiMsgId = System.currentTimeMillis() * 10000 + (0..9999).random()
                                        expandedReasoningMsgId = aiMsgId
                                        // 开启"显示思考过程"时，初始占位用空文本，稍后由 onReasoningDelta 填充思考内容
                                        val initText = if (AiChatManager.getShowReasoning(ctx, currentUserId)) "" else AI_THINKING
                                        messages.add(ChatMsg.create(
                                            serverId = aiMsgId, text = initText, isMine = false,
                                            fromUserId = AI_CHAT_ID, senderName = AiNameState.name ?: "AI", isNew = true,
                                            createdAt = System.currentTimeMillis() / 1000
                                        ).copy(aiWaiting = true))
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
                                        val outcome = AiChatManager.runAgentChatLoop(
                                        ctx = ctx, userId = currentUserId, history = history,
                                        onNeedApproval = { tool, args -> toolApprovalHook(tool, args) },
                                        onFileSent = { fs -> insertAiFileCard(fs.absPath, fs.forceFileCard, aiMsgId) },
                                        onMediaGenerated = { t, url -> insertAiMediaCard(t, url, aiMsgId) },
                                        onToolStage = { st ->
                                            // 工具状态直接内联进当前 AI 消息文本（哨兵块随正文持久化，绝不丢失）：
                                            // 调用开始(0)追加「正在调用」状态行，完成(1/2)把同名状态行原地替换为「已调用/失败」，
                                            // 让状态显示在调用真正发生的位置，而不是堆到所有结果之后。
                                            val sIdx = messages.indexOfFirst { x -> x.id == aiMsgId }
                                            if (sIdx >= 0 && messages[sIdx] is ChatMsg) {
                                                val cur = messages[sIdx] as ChatMsg
                                                val newText = if (st.second == 0) {
                                                    AiChatManager.appendToolInline(cur.text, st.first, 0)
                                                } else {
                                                    AiChatManager.replaceToolInline(cur.text, st.first, st.second)
                                                }
                                                messages[sIdx] = cur.copy(text = newText)
                                                scheduleAiDebouncedSave()
                                                try { aiGlobalScope.launch { listState.scrollToItem(0) } } catch (_: Exception) {}
                                            }
                                            // 工具完成后同步刷新 UI 模式状态（Plan 模式下 AI 可能已用 set_agent_mode 自动切换到 craft）
                                            agentMode = AiChatManager.getAgentMode(ctx, currentUserId)
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
                                            var done = false
                                            var ft = ""
                                            var u = 0L
                                            var th = 0L
                                            var tt = 0L
                                            var err = false
                                            AiChatManager.streamChat(
                                                ctx = ctx, userId = currentUserId, history = history,
                                                images = if (isFirstRound) aiImagesForSend else emptyList(),
                                                imageText = if (isFirstRound) text else "",
                                                stopCheck = { aiStopRequested },
                                                onReasoningDelta = { reasoning ->
                                                    // 每轮思考都实时刷新：已累积段 + 本轮思考，分段展示（不限于首轮）
                                                    roundReasoning = reasoning
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
                                                    if (atBottomState.value && !userScrolledAway) {
                                                        aiGlobalScope.launch { try { listState.scrollToItem(0) } catch (_: Exception) {} }
                                                    }
                                                    // 思考过程中实时更新用量：输入已消耗，输出尚未产生，先显示输入部分
                                                    if (mode == "official" && poolAtStart > 0L) {
                                                        val liveUsed = baseUsed + estInput
                                                        val liveRemaining = (poolAtStart - estInput).coerceAtLeast(0)
                                                        aiUsageState.value = Pair(liveUsed, liveRemaining)
                                                    }
                                                },
                                                onDelta = { cur ->
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
                                                    if (atBottomState.value && !userScrolledAway) {
                                                        aiGlobalScope.launch { try { listState.scrollToItem(0) } catch (_: Exception) {} }
                                                    }
                                                    // 实时预估：以发送前服务器余额(poolAtStart)为基准，边输出边递减展示。
                                                    // 仅当服务器余额已加载(>0)才做本地预估；未加载(-1)或已 0 时不预估，等服务器裁决。
                                                    if (mode == "official" && poolAtStart > 0L) {
                                                        val inc = estInput + AiChatManager.estimateTokens(cur)
                                                        val liveRemaining = (poolAtStart - inc).coerceAtLeast(0)
                                                        val liveUsed = baseUsed + inc
                                                        aiUsageState.value = Pair(liveUsed, liveRemaining)
                                                    }
                                                },
                                                onDone = { usage, full, thinkingSeconds, totalSeconds ->
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
                                                    err = true
                                                    lastErrMsg = errMsg
                                                    // 记录错误并标记后台回复结束（跨组合期）
                                                    aiWriting.value = false
                                                    aiWritingConvId = -1L
                                                    aiStopRequested = false
                                                    activeAiJob = null
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
                                                enableAgentTools = agentMode != AiChatManager.MODE_ASK,
                                                toolChoice = toolChoice,
                                                onFinish = { r -> finishReason = r }
                                            )
                                            AiChatManager.AgentRoundResult(done && !err, ft, u, th, tt, thisRoundReasoning, finishReason, if (err) com.aurora.chat.ui.chat.AgentTaskCompletion.isRetriable(lastErrMsg) else false)
                                        }
                                        if (outcome.clean) {
                                            // 一次性收尾（仅正常完成时执行；出错/手动停止保留现场，跳过此块）
                                            aiWriting.value = false
                                            aiWritingConvId = -1L
                                            aiStopRequested = false
                                            activeAiJob = null
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
                                                            aiFileChanges = AiChatManager.serializeFileChanges(outcome.fileChanges),
                                                            aiToolCallCount = outcome.toolCallCount,
                                                            aiToolStages = outcome.toolCalls
                                                        ))
                                                        pos++
                                                    }
                                                    if (firstText.isNotEmpty()) ChatViewModel.updateConversationPreview(friendId, firstText, System.currentTimeMillis() / 1000)
                                                } else {
                                                    if (idx >= 0) messages[idx] = (messages[idx] as ChatMsg).copy(text = full, reasoningText = finalReasoning, thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds, aiWaiting = false, aiProgressHint = "", aiFileChanges = AiChatManager.serializeFileChanges(outcome.fileChanges), aiToolCallCount = outcome.toolCallCount, aiToolStages = outcome.toolCalls)
                                                    // 工具状态行动画收回:已调用/失败/调用中全部不遗留
                                                    collapseToolStatusMessages(messages)
                                                    if (full.isNotBlank()) {
                                                        ChatViewModel.updateConversationPreview(friendId, AiChatManager.stripInlineReasoning(full).take(60), System.currentTimeMillis() / 1000)
                                                    }
                                                }
                                                val snap = messages.filter { !it.isSystemNotice }.toList()
                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { saveLocalChatMessages(ctx, currentUserId, myKey, snap) } catch (_: Exception) {} }
                                            } else {
                                                // 已切走：把完整回复合并写回所属会话文件，用户切回即可见（multi 场景写回含分隔符原文，保证内容完整）
                                                aiGlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                    try { patchAiReplyInFile(ctx, currentUserId, myKey, aiMsgId, text = full, reasoningText = finalReasoning, thinkingSeconds = outcome.thinkingSeconds, totalSeconds = outcome.totalSeconds, fileChangesJson = AiChatManager.serializeFileChanges(outcome.fileChanges), toolCallCount = outcome.toolCallCount, toolStages = outcome.toolCalls) } catch (_: Exception) {}
                                                }
                                            }
                                            if (mode == "official" && outcome.usage > 0) {
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
                                        } else {
                                            // ═══ 中断收尾（手动停止/出错/强制 break 等 clean=false 路径）═══
                                            // 旧代码只把 aiFileChanges/aiToolCallCount/aiToolStages 写回消息放在
                                            // if(outcome.clean) 正常完成块里；中断时被整体跳过，导致底部
                                            // 「查看所有改动」「调用 N 个工具」按钮不渲染、中断前已做的修改不留痕。
                                            // 现在中断也写回：AiChatManager 中断路径已执行 mergeSandboxDiff 并在
                                            // outcome 里返回 fileChanges/toolCallCount/toolCalls（含中途停止前的真实改动），
                                            // 这里只要落到消息并落盘，重进会话按钮仍在、改动列表完整。
                                            aiWriting.value = false
                                            aiWritingConvId = -1L
                                            aiStopRequested = false
                                            activeAiJob = null
                                            // 修复：中断收尾补写正文——原实现只回写工具/改动字段，outcome.accumulated
                                            // （中断前已流式生成的内容）从未写回消息，离开会话/异常中断后正文丢失。
                                            // 这里把最终正文写入跨组合期状态、内存消息与所属会话文件（思考内容流式阶段已实时写入）。
                                            val finalText = outcome.accumulated.replace(AiChatManager.MULTI_OUTPUT_SPLIT, "")
                                            aiReplyState.value = aiReplyState.value?.takeIf { it.aiMsgId == aiMsgId }
                                                ?.copy(text = finalText, done = true)
                                            val hasCalls = outcome.toolCallCount > 0
                                            val hasChanges = outcome.fileChanges.isNotEmpty()
                                            val stillHere = AiConversationSession.storageKey() == myKey
                                            val idx = if (stillHere) messages.indexOfFirst { m -> m.id == aiMsgId } else -1
                                            if (idx >= 0) {
                                                // 仍在当前会话：正文/思考/工具字段全部写回内存消息 + 落盘（保留已有字段，只补缺失数据）
                                                val cur = messages[idx] as ChatMsg
                                                messages[idx] = cur.copy(
                                                    text = if (finalText.isNotBlank()) finalText else cur.text,
                                                    reasoningText = cur.reasoningText,
                                                    aiWaiting = false,
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
                                                            text = if (finalText.isNotBlank()) finalText else "",
                                                            reasoningText = liveReasoning,
                                                            fileChangesJson = if (hasChanges) AiChatManager.serializeFileChanges(outcome.fileChanges) else "",
                                                            toolCallCount = if (hasCalls) outcome.toolCallCount else 0,
                                                            toolStages = if (hasCalls) outcome.toolCalls else null
                                                        )
                                                    } catch (_: Exception) {}
                                                }
                                            }
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
                            onSuccess = { serverMsgId ->
                                // 回填服务端消息 ID：撤回时优先用 serverId，避免用本地临时 id 撤回导致 404
                                if (serverMsgId > 0 && sentMsg != null) {
                                    val si = messages.indexOfFirst { it.id == sentMsg.id }
                                    if (si >= 0) messages[si] = (messages[si] as? ChatMsg)?.copy(serverId = serverMsgId) ?: messages[si]
                                }
                                ChatViewModel.updateConversationPreview(friendId, text, System.currentTimeMillis() / 1000)
                                scope.launch {
                                    kotlinx.coroutines.delay(50)
                                    if (messages.isNotEmpty()) {
                                        listState.scrollToItem(0)
                                    }
                                }
                            },
                            onError = { msg ->
                                val displayMsg = when {
                                    msg.contains("您的账号异常") && msg.contains("410") -> {
                                        scope.launch { ChatRepository.checkAndLogoutCurrentUser(ctx, currentUserId) }
                                        "发送失败: 您的账号异常，操作已禁止"
                                    }
                                    msg.contains("对方账号异常") -> "对方账号异常，无法发送消息"
                                    msg.contains("连接") || msg.contains("超时") || msg.contains("网络") -> "发送失败，请检查网络"
                                    msg.contains("账号异常，请重新登录") && msg.contains("401") -> {
                                        android.widget.Toast.makeText(ctx, "登录状态已过期，请重新登录", android.widget.Toast.LENGTH_LONG).show()
                                        scope.launch {
                                            ChatRepository.checkAndLogoutCurrentUser(ctx, currentUserId)
                                        }
                                        return@sendMessage
                                    }
                                    else -> "发送失败: $msg"
                                }
                                AuroraApi.showErrorToast(ctx, displayMsg)
                            },
                            context = ctx
                        )
                    } // end else (非注销用户 走 API)
                    } // end else (非注销用户发送逻辑)
                    }
                },
                onSendMedia = { uri, flashSec, name ->
                    // 用文件名扩展名推断类型（比 contentResolver.getType 可靠，避免 APK/TXT 被误判成图片/视频）
                    val mimeType = guessMediaMimeType(name)
                    val isImage = mimeType.startsWith("image/")
                    val isVideo = mimeType.startsWith("video/")
                    val extraType = if (isImage) "image" else if (isVideo) "video" else "file"
                    // 交给后台上传管理器：本地压成 JPG → 落盘待发队列 → 离开对话/退出软件也继续上传，下次启动自动重试
                    val localId = com.aurora.chat.data.upload.MediaUploadManager.enqueueFromUri(ctx, friendId, isGroupChat(friendId), uri, mimeType, "", flashSec)
                    if (localId >= 0) {
                        val placeholderMsg = ChatMsg.create(serverId = localId, text = "", isMine = true, fromUserId = currentUserId, isNew = true, mediaType = extraType, mediaUrl = "", isUploading = true, flashDuration = flashSec)
                        messages.add(placeholderMsg)
                        uploadProgressMap[localId] = 0f
                        uploadFileNameMap[localId] = name
                        if (isVideo) {
                            uploadStageMap[localId] = "compressing"
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

        }

        // ===== 滚动到底部按钮（悬浮在输入栏上方，靠右对齐） =====
        AnimatedVisibility(
            visible = shouldShowScrollDown.value,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 56.dp, end = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White, CircleShape)
                    .border(1.dp, Color(0xFFD1D5DB), CircleShape)
                    .clickable {
                        scope.launch {
                            listState.scrollToItem(0)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(26.dp)) {
                    val c = center
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c.x - 10.5f, c.y - 5f)
                        lineTo(c.x, c.y + 6.5f)
                        lineTo(c.x + 10.5f, c.y - 5f)
                        close()
                    }
                    drawPath(path, Color(0xFF6B7280))
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
                onSelected = { showFreeApiSelect = false; showAiUsage = false }
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
                Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                    com.aurora.chat.ui.components.EventBlocker()
                    FriendProfilePanel(
                        friendId = friendId,
                        friendName = friendName,
                        currentUserId = currentUserId,
                        onBack = { showFriendSettings = false },
                        onDeleteFriend = {
                            showFriendSettings = false
                            scope.launch {
                                val r = ChatRepository.deleteFriend(friendId)
                                if (r.success) {
                                    android.widget.Toast.makeText(ctx, "好友已删除", android.widget.Toast.LENGTH_SHORT).show()
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
                Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
                    com.aurora.chat.ui.components.EventBlocker()
                    GroupSettingsPanel(
                        internalGroupId = internalGroupId,
                        groupName = friendName,
                        onBack = { showGroupSettings = false },
                        onOpenProfile = { uid, name ->
                            profileUserId = uid
                            profileUserName = name
                            // 从群设置进入：尝试用现有 friendInfo / systemUserName
                            showProfile = true
                        },
                        onDissolved = onBack,
                        onOpenJoinRequestList = onOpenJoinRequestFullScreen
                    )
                }
            }
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
                        onOpenNewChat(uid, name)
                    }
                },
                onDeleteFriend = {
                    scope.launch {
                        val r = ChatRepository.deleteFriend(profileUserId)
                        if (r.success) {
                            android.widget.Toast.makeText(ctx, "已删除好友", android.widget.Toast.LENGTH_SHORT).show()
                            ChatViewModel.loadConversations(force = true)
                            showProfile = false
                        } else {
                            android.widget.Toast.makeText(ctx, "删除失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            }
        }
        // 工作区文件管理：从右侧滑入的全屏面板（AI 对话专属，置于根 Box 最后一个子项保证全屏覆盖）
        if (friendId == AI_CHAT_ID) {
            AnimatedVisibility(
                visible = showWorkspaceFiles,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                if (com.aurora.chat.host.HostSession.inHostMode.value) {
                    HostFileManagerScreen(
                        onBack = {
                            showWorkspaceFiles = false
                            com.aurora.chat.host.HostSession.inHostMode.value = false
                            com.aurora.chat.host.HostConfig.saveHostMode(false)
                        },
                        onBackToWorkspace = {
                            com.aurora.chat.host.HostSession.inHostMode.value = false
                            com.aurora.chat.host.HostConfig.saveHostMode(false)
                        }
                    )
                } else {
                    WorkspaceFileManagerScreen(
                        onBack = {
                            showWorkspaceFiles = false
                            com.aurora.chat.host.HostSession.inHostMode.value = false
                            com.aurora.chat.host.HostConfig.saveHostMode(false)
                        },
                        onOpenBackup = { showWorkspaceFiles = false; showBackupScreen = true }
                    )
                }
            }
        }
        // 插件市场：从右侧滑入的全屏面板（与工作区同模板同层级）
        if (friendId == AI_CHAT_ID) {
            AnimatedVisibility(
                visible = showPluginMarket,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                PluginMarketScreen(userId = currentUserId, onBack = { showPluginMarket = false })
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
                onBack = { aiChangesOpen = false },
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
                }
            )
        }
        BackHandler(enabled = aiChangesOpen) { aiChangesOpen = false }
        // 「工具调用」全屏清单:与改动详情同层级,独立全屏、绝不嵌套
        AnimatedVisibility(
            visible = aiToolCallsOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            AiToolCallsScreen(records = aiToolCallsData, onBack = { aiToolCallsOpen = false })
        }
        BackHandler(enabled = aiToolCallsOpen) { aiToolCallsOpen = false }
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
            val brTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(req.meta.createdAt))
            val brScopeStr = if (req.meta.type == "workspace" || req.meta.sourceRel.isEmpty()) "整个工作区" else "文件夹 ai_files/${req.meta.sourceRel}"
            val brSize = req.meta.sizeBytes.let { b ->
                when {
                    b < 1024 -> "$b B"
                    b < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", b / 1024f)
                    b < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f MB", b / (1024f * 1024f))
                    else -> String.format(Locale.getDefault(), "%.2f GB", b / (1024f * 1024f * 1024f))
                }
            }
            AlertDialog(
                onDismissRequest = { AiChatManager.BackupRestoreBus.complete(false) },
                containerColor = Color(0xFFFFFBFB),
                title = { Text("⚠ 恢复备份确认", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "AI 请求将以下备份恢复到工作区。此操作会覆盖工作区对应内容，且无法撤销。",
                            color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("备份时间：$brTime", fontSize = 13.sp, color = Color(0xFF1F2937))
                        Text("备份范围：$brScopeStr", fontSize = 13.sp, color = Color(0xFF1F2937))
                        Text("文件数：${req.meta.fileCount}    大小：$brSize", fontSize = 13.sp, color = Color(0xFF1F2937))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "无论当前访问模式（手动 / 完全访问 / root），都必须由你亲自点击「批准恢复」才执行；拒绝或关闭则工作区与备份均保持不变。",
                            fontSize = 12.sp, color = Color(0xFF6B7280)
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
                    TextButton(onClick = { AiChatManager.BackupRestoreBus.complete(false) }) { Text("拒绝", color = Color(0xFF6B7280)) }
                }
            )
        }
    }

    }
    ChatMainUi()

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
            Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
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
                    // 直接用文件 URI 走上传流程
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", file
                    )
                    scope.launch {
                        sendCameraFile(uri, ctx, currentUserId, friendId, scope, messages, uploadProgressMap, listState) { newMessages -> messages.clear(); messages.addAll(newMessages) }
                    }
                },
                onVideoCaptured = { file, durationSec ->
                    showCamera = false
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.fileprovider", file
                    )
                    scope.launch {
                        sendCameraFile(uri, ctx, currentUserId, friendId, scope, messages, uploadProgressMap, listState) { newMessages -> messages.clear(); messages.addAll(newMessages) }
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

    // ── 转账弹窗 ──
    if (showTransferDialog) {
        TransferDialog(
            friendId = friendId,
            currentUserId = currentUserId,
            currentUserName = currentUserName,
            friendName = friendName,
            messages = messages,
            ctx = ctx,
            onDismiss = { showTransferDialog = false }
        )
    }

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
                    Text("取消", color = Color(0xFF6B7280))
                }
            }
        )
    }

    // 图片全屏预览时拦截返回键：左滑/返回只关预览，不退到聊天板块
    BackHandler(enabled = imagePreviewUrls.isNotEmpty()) { imagePreviewUrls = emptyList() }

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

    // 图片全屏预览（在 Column 外部，覆盖整个屏幕包括顶部栏）。
    // - 支持多图：点击某张后从该张起，左右滑动查看对话内全部图片（HorizontalPager）。
    // - 性能：Pager 只对当前可见页（及相邻页）创建内容，配合 AsyncImage 按需懒加载，不一次性加载全部图片。
    // - 交互：进入/退出带淡入淡出动画；单图支持双指缩放/拖动。
    AnimatedVisibility(
        visible = imagePreviewUrls.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        val window = (ctx as android.app.Activity).window
        DisposableEffect(imagePreviewUrls) {
            // 注意：MainActivity 已 enableEdgeToEdge（decorFitsSystemWindows=false），
            // 这里【绝不能】再改 window 的 decorFits 标志，否则退出预览后主界面顶栏会被状态栏顶上去。
            // 只需临时隐藏系统栏即可全屏预览，退出时恢复显示。
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose {
                val ctrl = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                ctrl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        val pagerState = rememberPagerState(
            initialPage = imagePreviewIndex.coerceIn(0, (imagePreviewUrls.size - 1).coerceAtLeast(0)),
            pageCount = { imagePreviewUrls.size }
        )
        // 滑动时同步当前索引，供底部指示器与"保存当前图"使用
        LaunchedEffect(pagerState.currentPage) {
            imagePreviewIndex = pagerState.currentPage
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { imagePreviewUrls = emptyList() },
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val url = imagePreviewUrls.getOrNull(page) ?: return@HorizontalPager
                // 每页独立的缩放/位移状态（按 url 记忆），仅当前可见页被实例化 → 懒加载
                var scale by remember(url) { mutableFloatStateOf(1f) }
                var offset by remember(url) { mutableStateOf(Offset.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(url) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var prevDistance = 0f
                                var prevPos: Offset? = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val changes = event.changes
                                    if (changes.size >= 2) {
                                        // 双指缩放：始终消费，避免被 Pager 抢走
                                        val dist = (changes[0].position - changes[1].position).getDistance()
                                        if (prevDistance > 0f) {
                                            val zoom = dist / prevDistance
                                            scale = (scale * zoom).coerceIn(1f, 5f)
                                            if (scale <= 1f) {
                                                scale = 1f
                                                offset = Offset.Zero
                                            }
                                        }
                                        prevDistance = dist
                                        prevPos = null
                                        changes.forEach { it.consume() }
                                    } else if (changes.size == 1) {
                                        val ch = changes[0]
                                        if (scale > 1f) {
                                            // 已放大：单指拖拽平移图片，消费事件
                                            val cur = ch.position
                                            if (prevPos != null) {
                                                val pan = cur - prevPos!!
                                                offset = Offset(
                                                    x = (offset.x + pan.x).coerceIn(
                                                        -(scale - 1f) * size.width / 2f,
                                                        (scale - 1f) * size.width / 2f
                                                    ),
                                                    y = (offset.y + pan.y).coerceIn(
                                                        -(scale - 1f) * size.height / 2f,
                                                        (scale - 1f) * size.height / 2f
                                                    )
                                                )
                                            }
                                            prevPos = cur
                                            ch.consume()
                                        } else {
                                            // 未放大：不消费单指事件，让 HorizontalPager 接管横向滑动
                                            prevPos = null
                                        }
                                        prevDistance = 0f
                                    }
                                    if (changes.all { it.changedToUp() }) break
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
                    coil.compose.AsyncImage(
                        model = url,
                        contentDescription = "图片预览",
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { },
                                onLongClick = { showSaveImageDialog = true }
                            ),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            // 多图底部指示器
            if (imagePreviewUrls.size > 1) {
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    imagePreviewUrls.forEachIndexed { i, _ ->
                        Box(
                            Modifier.size(if (pagerState.currentPage == i) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (pagerState.currentPage == i) Color.White else Color.White.copy(alpha = 0.4f))
                        )
                    }
                }
            }
        }
    }

    // 图片长按 → 保存到手机相册确认弹窗
    if (showSaveImageDialog) {
        AlertDialog(
            onDismissRequest = { showSaveImageDialog = false },
            containerColor = Color.White,
            title = { Text("保存图片", fontWeight = FontWeight.Bold) },
            text = { Text("是否将这张图片保存到手机相册？", fontSize = 14.sp, color = Color(0xFF4B5563)) },
            confirmButton = {
                TextButton(onClick = {
                    showSaveImageDialog = false
                    val url = imagePreviewUrls.getOrNull(imagePreviewIndex) ?: ""
                    scope.launch {
                        val ok = com.aurora.chat.util.GallerySaver.saveImage(ctx, url)
                        android.widget.Toast.makeText(ctx, if (ok) "已保存到相册" else "保存失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("保存", color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveImageDialog = false }) { Text("取消", color = Color(0xFF6B7280)) }
            }
        )
    }

    // ── 视频全屏播放器（TextureView + MediaPlayer，支持渐进加载/缓冲进度/下载速度）──
    BackHandler(enabled = videoPlayUrl.isNotEmpty()) { videoPlayUrl = "" }
    if (videoPlayUrl.isNotEmpty()) {
        val window = (ctx as android.app.Activity).window
        DisposableEffect(videoPlayUrl) {
            // 同图片预览：不改动 window 的 decorFits 标志，仅临时隐藏/恢复系统栏
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            onDispose {
                val ctrl = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                ctrl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }

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
        var mediaPlayerRef by remember(videoPlayUrl) { mutableStateOf<MediaPlayer?>(null) }
        // 是否正在拖拽进度条（拖拽时暂停位置跟踪定时器）
        var isSeeking by remember(videoPlayUrl) { mutableStateOf(false) }

        fun formatTime(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000)),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        var mediaPlayer: MediaPlayer? = null
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                mediaPlayer = MediaPlayer().apply {
                                    mediaPlayerRef = this
                                    try {
                                        // 优先使用缓存视频
                                        val cacheUri = com.aurora.chat.data.local.MediaCache.getCachedVideoUri(ctx, videoPlayUrl)
                                        if (cacheUri != null) {
                                            setDataSource(ctx, cacheUri)
                                        } else {
                                            setDataSource(videoPlayUrl)
                                            // 异步缓存到本地
                                            kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                                                com.aurora.chat.data.local.MediaCache.downloadVideo(ctx, videoPlayUrl)
                                            }
                                        }
                                        setSurface(android.view.Surface(surface))

                                        setOnBufferingUpdateListener { _, percent ->
                                            bufferPercent = percent
                                            if (percent >= 5 && isPrepared && !isPaused && !isPlaying) {
                                                start()
                                            }
                                        }

                                        setOnInfoListener { _, what, _ ->
                                            when (what) {
                                                MediaPlayer.MEDIA_INFO_BUFFERING_START -> isBuffering = true
                                                MediaPlayer.MEDIA_INFO_BUFFERING_END -> isBuffering = false
                                                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> isBuffering = false
                                            }
                                            false
                                        }

                                        setOnPreparedListener { mp ->
                                            videoAspectRatio = mp.videoWidth.toFloat() / mp.videoHeight.toFloat().coerceAtLeast(1f)
                                            videoDurationMs = mp.duration.toLong()
                                            isPrepared = true
                                            mp.isLooping = true
                                            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                                            if (bufferPercent >= 5) mp.start()
                                        }

                                        setOnErrorListener { _, what, extra ->
                                            Log.e("VideoPlayer", "播放错误: what=$what extra=$extra")
                                            isPrepared = false
                                            isBuffering = false
                                            true
                                        }

                                        setKeepScreenOn(true)
                                        prepareAsync()
                                    } catch (e: Exception) {
                                        Log.e("VideoPlayer", "初始化失败: ${e.message}")
                                    }
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean {
                                mediaPlayer?.release()
                                mediaPlayer = null
                                mediaPlayerRef = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

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
                            val mp = mediaPlayerRef
                            if (mp != null && isPrepared) {
                                if (mp.isPlaying) {
                                    mp.pause()
                                    isPaused = true
                                } else {
                                    mp.start()
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
                                        mediaPlayerRef?.seekTo(targetMs.toInt())
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
                                            mediaPlayerRef?.seekTo(targetMs.toInt())
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
                                        mediaPlayerRef?.seekTo(targetMs.toInt())
                                        currentPositionMs = targetMs
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // 视觉进度条（4dp 细条）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0x4DFFFFFF))
                        ) {
                            val progress = if (videoDurationMs > 0) {
                                (currentPositionMs.toFloat() / videoDurationMs).coerceIn(0f, 1f)
                            } else 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = progress)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White)
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



            // ── 加载遮罩层（仅在缓冲或未准备好时显示）──
            if (isBuffering || !isPrepared) {
                if (totalFileSize == 0L) {
                    LaunchedEffect(Unit) {
                        try {
                            val len = com.aurora.chat.data.api.HttpClient.headContentLength(videoPlayUrl)
                            if (len > 0) totalFileSize = len
                        } catch (_: Exception) { totalFileSize = -1L }
                    }
                }

                LaunchedEffect(bufferPercent) {
                    if (bufferPercent > 0 && totalFileSize > 0) {
                        val nowMs = System.currentTimeMillis()
                        val currentBytes = (totalFileSize * bufferPercent) / 100L
                        if (lastBytes > 0) {
                            val elapsed = (nowMs - lastTimeMs).coerceAtLeast(1)
                            val deltaBytes = currentBytes - lastBytes
                            val bytesPerSec = (deltaBytes * 1000L) / elapsed
                            speedText = when {
                                bytesPerSec > 1_000_000 -> "${"%.1f".format(bytesPerSec / 1_000_000.0)} MB/s"
                                bytesPerSec > 1_000 -> "${"%.0f".format(bytesPerSec / 1_000.0)} KB/s"
                                else -> "$bytesPerSec B/s"
                            }
                        }
                        lastBytes = currentBytes
                        lastTimeMs = nowMs
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x80000000)),
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
                            Box(
                                modifier = Modifier
                                    .width(200.dp).height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0x4DFFFFFF))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction = bufferPercent / 100f)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White)
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
                        mediaPlayerRef?.let { mp ->
                            if (mp.isPlaying) currentPositionMs = mp.currentPosition.toLong()
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
                            val _fe = fileName.substringAfterLast('.', "").lowercase()
                            startFileSaveAs(url, fileName, _fe)
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
            containerColor = Color.White,
            title = { Text("保存图片", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    Text("这是一张图片，是否保存到本地相册？", fontSize = 14.sp, color = Color(0xFF374151), lineHeight = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(imgName, fontSize = 13.sp, color = Color(0xFF6B7280), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    imageSaveConfirm = null
                    saveChatImageToGallery(imgUrl)
                }) { Text("保存到相册", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { imageSaveConfirm = null }) { Text("取消", color = Color(0xFF6B7280)) }
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
            containerColor = Color.White,
            title = { Text("文件下载", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    Text(title, fontSize = 14.sp, color = Color(0xFF374151), lineHeight = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(dlName, fontSize = 13.sp, color = Color(0xFF6B7280), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fileDownloadConfirm = null
                    startFileSaveAs(dlUrl, dlName, dlExt)
                }) { Text("另存为", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { fileDownloadConfirm = null }) { Text("取消", color = Color(0xFF6B7280)) }
            }
        )
    }

    // ── AI 设置弹窗（合并上传/恢复头像 + 设置/恢复名称）──
    AiSettingsDialog(
        visible = showAiSettingsDialog,
        reverseHistory = reverseHistory,
        onClose = { showAiSettingsDialog = false },
        onUploadAvatar = { aiAvatarLauncher.launch("image/*") },
        onRestoreAvatar = {
            try { LocalStorage.getAiAvatarFile(ctx).delete() } catch (_: Exception) {}
            AiAvatarState.bitmap = null
            Toast.makeText(ctx, "已恢复默认头像", Toast.LENGTH_SHORT).show()
        },
        onSetName = { aiNameInput = AiNameState.name ?: ""; showAiNameInput = true },
        onRestoreName = { LocalStorage.resetAiName(ctx); AiNameState.name = null },
        onReverse = { showReverseInfo = true },
        onUploadMyAvatar = { myAvatarLauncher.launch("image/*") },
        onPlaceholderConvert = { showPlaceholderConvert = true }
    )

    // ── 「AI 设置 → 开启新对话」确认弹窗：与主界面「开启新对话」走同一安全清空序列 ──
    if (showAiNewConfirm) {
        AlertDialog(
            onDismissRequest = { showAiNewConfirm = false },
            containerColor = Color.White,
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
                TextButton(onClick = { showAiNewConfirm = false }) { Text("取消", color = Color(0xFF6B7280)) }
            }
        )
    }

    // ── 人物对换说明弹窗 ──
    if (showReverseInfo) {
        AlertDialog(
            onDismissRequest = { showReverseInfo = false },
            title = { Text("人物对换") },
            text = {
                Column {
                    Text(
                        "开启后：\n" +
                            "• 对话将整体反转——你与 AI 的左右位置互换、头像也互换；\n" +
                            "• 发给 AI 的上下文中会标注「聊天记录是反过来的」，让 AI 以你的身份（我方）继续与对方自然聊下去。\n\n" +
                            "适合你导入了别人的对话、想以对方视角接着聊的场景。",
                        fontSize = 14.sp, color = Color(0xFF374151)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    reverseHistory = !reverseHistory
                    LocalStorage.setReverseHistory(ctx, currentUserId, reverseHistory)
                    showReverseInfo = false
                    Toast.makeText(ctx, if (reverseHistory) "已开启人物对换" else "已关闭人物对换", Toast.LENGTH_SHORT).show()
                }) { Text(if (reverseHistory) "关闭" else "开启", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { showReverseInfo = false }) { Text("取消", color = Color(0xFF6B7280)) }
            }
        )
    }

    // ── 占位符转换弹窗 ──
    if (showPlaceholderConvert) {
        val phFroms = remember { mutableStateListOf("男主", "女主") }
        val phTos = remember { mutableStateListOf("人物1", "人物2") }
        AlertDialog(
            onDismissRequest = { showPlaceholderConvert = false },
            containerColor = Color.White,
            title = { Text("占位符转换", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.widthIn(max = 320.dp)) {
                    Text("设置名称与占位符的对应关系，将全文中的名称替换为占位符或反向还原", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(12.dp))
                    // 每行：名称 → 占位符，可增删
                    val dupTos = phTos.map { it.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                    phFroms.indices.forEach { i ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = phFroms[i], onValueChange = { phFroms[i] = it },
                                placeholder = { Text("名称", fontSize = 12.sp) }, singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Text("→", fontSize = 13.sp, color = Color(0xFF6B7280))
                            val toVal = phTos[i].trim()
                            OutlinedTextField(
                                value = phTos[i], onValueChange = { phTos[i] = it },
                                placeholder = { Text("占位符", fontSize = 12.sp) }, singleLine = true,
                                isError = toVal.isNotEmpty() && toVal in dupTos,
                                modifier = Modifier.weight(1f)
                            )
                            if (phFroms.size > 1) {
                                Text("✕", fontSize = 14.sp, color = Color(0xFFDC2626),
                                    modifier = Modifier.clickable { phFroms.removeAt(i); phTos.removeAt(i) }.padding(4.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (dupTos.isNotEmpty()) {
                        Text("⚠ 占位符不能重复，否则会合并成同一个名称", fontSize = 12.sp, color = Color(0xFFDC2626))
                        Spacer(Modifier.height(6.dp))
                    }
                    TextButton(onClick = { phFroms.add(""); phTos.add("") }) { Text("+ 添加替换项", color = Color(0xFF1E40AF), fontSize = 13.sp) }
                    Spacer(Modifier.height(4.dp))
                    Text("转换方向：点击确定后，全文中的「名称」将被替换为「占位符」；再次点击确定可将「占位符」还原回「名称」（修改对应关系后点确定即可反向操作）", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val tos = phTos.map { it.trim() }.filter { it.isNotEmpty() }
                    if (tos.size != tos.toSet().size) {
                        Toast.makeText(ctx, "占位符不能重复，请修改后再执行", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    showPlaceholderConvert = false
                    scope.launch {
                        val newList = messages.map { msg ->
                            var text = msg.text
                            var sender = msg.senderName
                            var replyText = msg.replyToText
                            var replySender = msg.replyToSender
                            for (j in phFroms.indices) {
                                val f = phFroms[j].trim()
                                val t = phTos[j].trim()
                                if (f.isNotEmpty() && t.isNotEmpty()) {
                                    text = text.replace(f, t)
                                    sender = sender.replace(f, t)
                                    replyText = replyText.replace(f, t)
                                    replySender = replySender.replace(f, t)
                                }
                            }
                            (msg as? ChatMsg)?.copy(text = text, senderName = sender) ?: msg
                        }
                        messages.clear()
                        messages.addAll(newList)
                        saveLocalChatMessages(ctx, currentUserId, localChatKey(friendId), messages.filter { !it.isSystemNotice })
                        Toast.makeText(ctx, "占位符转换完成", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { showPlaceholderConvert = false }) { Text("取消", color = Color(0xFF6B7280)) }
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
                TextButton(onClick = { showAiNameInput = false }) { Text("取消", color = Color(0xFF6B7280)) }
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
}
// 把某条 AI 回复的状态/正文合并写回指定会话文件（回复收尾时用户已切走该会话时使用），不动当前内存列表
private fun patchAiReplyInFile(
    ctx: android.content.Context, userId: Long, key: String, msgId: Long,
    text: String? = null, reasoningText: String? = null, manualStopped: Boolean? = null,
    thinkingSeconds: Long? = null, totalSeconds: Long? = null,
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

/** AI 设置弹窗里的单行可点击项 */
@Composable
private fun AiSettingsItem(title: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp)
    ) {
        Text(title, fontSize = 15.sp, color = Color(0xFF374151))
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFF0F1F3)))
}

// ═════════════════════════════════════════════════════
    // ── 转账弹窗 ──
    @Composable
    private fun TransferDialog(
        friendId: Long,
        currentUserId: Long,
        currentUserName: String,
        friendName: String,
        messages: androidx.compose.runtime.snapshots.SnapshotStateList<IMessageRef>,
        ctx: Context,
        onDismiss: () -> Unit
    ) {
        val isGroup = isGroupChat(friendId)
        var amountText by remember { mutableStateOf("") }
        var selectedId by remember { mutableStateOf(0L) }
        var selectedName by remember { mutableStateOf("") }
        var members by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            if (!isGroup) {
                selectedId = friendId
                selectedName = friendName
            } else {
                loading = true
                try {
                    val gid = -(friendId + 1000)
                    val r = AuroraApi.getGroupMembers(gid)
                    if (r.success && r.data != null) {
                        val list = mutableListOf<Pair<Long, String>>()
                        for (i in 0 until r.data!!.length()) {
                            val o = r.data!!.optJSONObject(i) ?: continue
                            val id = if (o.has("user_id")) o.optLong("user_id") else o.optLong("id")
                            val name = o.optString("username", "用户$id")
                            if (id != currentUserId && id > 0) list.add(id to name)
                        }
                        members = list
                    }
                } catch (_: Exception) { }
                loading = false
            }
        }

        Dialog(onDismissRequest = onDismiss) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(20.dp)
            ) {
                Column {
                    Text("转账", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    Spacer(Modifier.height(14.dp))
                    if (isGroup) {
                        Text("收款人", fontSize = 13.sp, color = Color(0xFF6B7280))
                        Spacer(Modifier.height(6.dp))
                        if (loading) {
                            Text("加载成员中...", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                        } else {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFF3F4F6))
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Column {
                                    members.forEach { (id, name) ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { selectedId = id; selectedName = name }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(selected = selectedId == id, onClick = { selectedId = id; selectedName = name })
                                            Spacer(Modifier.width(8.dp))
                                            Text(name, fontSize = 14.sp, color = Color(0xFF1F2937))
                                        }
                                    }
                                    if (members.isEmpty()) {
                                        Text("群内暂无可转账成员", fontSize = 13.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(12.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("转账金额（token）", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(6.dp))
                    BasicTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { c -> c.isDigit() }.take(12) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFF3F4F6))
                            .padding(12.dp),
                        textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF1F2937)),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (amountText.isEmpty()) Text("请输入数量", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                            else inner()
                        }
                    )
                    Spacer(Modifier.height(18.dp))
                    val amount = amountText.toLongOrNull() ?: 0
                    val canSend = amount > 0 && selectedId > 0 && selectedId != currentUserId
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (canSend) Color(0xFF1E40AF) else Color(0xFFD1D5DB))
                            .clickable(enabled = canSend) {
                                scope.launch {
                                    try {
                                        val r = AuroraApi.transfer(friendId, selectedId, selectedName, amount)
                                        if (r.success) {
                                            val senderName = currentUserName.ifEmpty {
                                                ctx.getSharedPreferences("aurora_login", android.content.Context.MODE_PRIVATE).getString("username", "") ?: ""
                                            }
                                            val content = buildTransferContent(currentUserId, senderName, selectedId, selectedName, amount)
                                            messages.add(ChatMsg.create(text = content, isMine = true, fromUserId = currentUserId, isNew = true, mediaType = "transfer", createdAt = System.currentTimeMillis() / 1000))
                                            ChatViewModel.updateConversationPreview(friendId, "[转账]", System.currentTimeMillis() / 1000)
                                            ChatViewModel.notifyNewMessage()
                                            android.widget.Toast.makeText(ctx, "转账成功", android.widget.Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } else {
                                            android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(ctx, "转账失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (amount > 0) "确认转账 $amount token" else "确认转账", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
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
            loading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White).padding(top = 48.dp)) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF1E40AF))
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                // 顶栏
                Text("隐私控制", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                Spacer(Modifier.height(20.dp))

                // ── 我的设置 ──
                Text("我的设置", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFF1E40AF))
                Spacer(Modifier.height(12.dp))

                // 禁止退出（我方开启→我方锁定退出；双方都开启→双方都锁定；独立开关，无需对方同意）
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("禁止退出", fontSize = 14.sp, color = Color(0xFF374151), modifier = Modifier.weight(1f))
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
                            // 立即更新本地 UI（创建新对象触发 Compose 重组）
                            val newObj = org.json.JSONObject(mySettings?.toString() ?: "{}")
                            newObj.put("no_exit", v)
                            mySettings = newObj
                            scope.launch {
                                AuroraApi.updatePrivacySettings(friendId, false, v,
                                    mySettings?.optBoolean("no_screenshot", false) ?: false,
                                    mySettings?.optBoolean("no_recording", false) ?: false,
                                    mySettings?.optBoolean("detect_screenshot", false) ?: false,
                                    mySettings?.optBoolean("detect_recording", false) ?: false)
                                AuroraApi.sendPrivacyAlert(friendId, "no_exit_toggle", v)
                            }
                        }
                    )
                }

                PrivacySettingRow("禁止截图", mySettings?.optBoolean("no_screenshot", false) ?: false) { v ->
                    val newObj = org.json.JSONObject(mySettings?.toString() ?: "{}")
                    newObj.put("no_screenshot", v)
                    mySettings = newObj
                    scope.launch {
                        AuroraApi.updatePrivacySettings(friendId, false,
                            mySettings?.optBoolean("no_exit", false) ?: false, v,
                            mySettings?.optBoolean("no_recording", false) ?: false,
                            mySettings?.optBoolean("detect_screenshot", false) ?: false,
                            mySettings?.optBoolean("detect_recording", false) ?: false)
                        AuroraApi.sendPrivacyAlert(friendId, "no_screenshot_toggle", v)
                    }
                }
                PrivacySettingRow("禁止录屏", mySettings?.optBoolean("no_recording", false) ?: false) { v ->
                    val newObj = org.json.JSONObject(mySettings?.toString() ?: "{}")
                    newObj.put("no_recording", v)
                    mySettings = newObj
                    scope.launch {
                        AuroraApi.updatePrivacySettings(friendId, false,
                            mySettings?.optBoolean("no_exit", false) ?: false,
                            mySettings?.optBoolean("no_screenshot", false) ?: false, v,
                            mySettings?.optBoolean("detect_screenshot", false) ?: false,
                            mySettings?.optBoolean("detect_recording", false) ?: false)
                        AuroraApi.sendPrivacyAlert(friendId, "no_recording_toggle", v)
                    }
                }

                // ── 截图检测（不拦截，仅通知） ──
                PrivacySettingRow("截图检测", mySettings?.optBoolean("detect_screenshot", false) ?: false) { v ->
                    val newObj = org.json.JSONObject(mySettings?.toString() ?: "{}")
                    newObj.put("detect_screenshot", v)
                    mySettings = newObj
                    if (v) {
                        // 开启检测 → 启动后台服务运行 ScreenshotDetector
                        com.aurora.chat.PrivacyFloatingService.start(ctx, friendId)
                    }
                    scope.launch {
                        AuroraApi.updatePrivacySettings(friendId, false,
                            mySettings?.optBoolean("no_exit", false) ?: false,
                            mySettings?.optBoolean("no_screenshot", false) ?: false,
                            mySettings?.optBoolean("no_recording", false) ?: false, v,
                            mySettings?.optBoolean("detect_recording", false) ?: false)
                    }
                }
                // ── 录屏检测（不拦截，仅通知） ──
                PrivacySettingRow("录屏检测", mySettings?.optBoolean("detect_recording", false) ?: false) { v ->
                    val newObj = org.json.JSONObject(mySettings?.toString() ?: "{}")
                    newObj.put("detect_recording", v)
                    mySettings = newObj
                    if (v) {
                        com.aurora.chat.PrivacyFloatingService.start(ctx, friendId)
                    }
                    scope.launch {
                        AuroraApi.updatePrivacySettings(friendId, false,
                            mySettings?.optBoolean("no_exit", false) ?: false,
                            mySettings?.optBoolean("no_screenshot", false) ?: false,
                            mySettings?.optBoolean("no_recording", false) ?: false,
                            mySettings?.optBoolean("detect_screenshot", false) ?: false, v)
                    }
                }

                Spacer(Modifier.height(24.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)))
                Spacer(Modifier.height(24.dp))

                // ── 对方设置（只读显示） ──
                Text("对方设置", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(12.dp))
                FriendSettingRow("禁止退出", friendSettings?.optBoolean("no_exit", false) ?: false)
                FriendSettingRow("禁止截图", friendSettings?.optBoolean("no_screenshot", false) ?: false)
                FriendSettingRow("禁止录屏", friendSettings?.optBoolean("no_recording", false) ?: false)
                FriendSettingRow("截图检测", friendSettings?.optBoolean("detect_screenshot", false) ?: false)
                FriendSettingRow("录屏检测", friendSettings?.optBoolean("detect_recording", false) ?: false)

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
                    "独立开关 + 协同生效：\n\n" +
                    "• 你开启 → 你无法退出（返回键被拦截）\n" +
                    "• 对方开启 → 对方无法退出\n" +
                    "• 双方都开启 → 双方都无法退出\n" +
                    "• 各自独立控制，无需对方同意\n" +
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
}

@Composable
private fun PrivacySettingRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF374151), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun FriendSettingRow(label: String, checked: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF6B7280), modifier = Modifier.weight(1f))
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

    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
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
                    Text("订单详情", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1F2937))
                    Spacer(Modifier.height(14.dp))
                    when {
                        loading -> Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
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
        Text(label, fontSize = 13.sp, color = Color(0xFF9CA3AF), modifier = Modifier.width(76.dp))
        Text(value, fontSize = 13.sp, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
    }
}

// 多选模式的底部操作栏：显示已选数量 + 复制 / 删除 / 取消
@Composable
private fun MultiSelectActionBar(
    count: Int,
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("已选", fontSize = 14.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.width(4.dp))
                Text("$count", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
            }
            TextButton(onClick = onCopy) { Text("复制", fontSize = 14.sp, color = Color(0xFF1F2937)) }
            TextButton(onClick = onDelete) { Text("删除", fontSize = 14.sp, color = Color(0xFFDC2626)) }
            TextButton(onClick = onCancel) { Text("取消", fontSize = 14.sp, color = Color(0xFF1F2937)) }
        }
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


/** AI 设置弹窗：头像 / 名称 / 人物对换 / 占位符转换。抽成独立函数以缩小 ChatConversationScreen 方法体积。 */
@Composable
private fun AiSettingsDialog(
    visible: Boolean,
    reverseHistory: Boolean,
    onClose: () -> Unit,
    onUploadAvatar: () -> Unit,
    onRestoreAvatar: () -> Unit,
    onSetName: () -> Unit,
    onRestoreName: () -> Unit,
    onReverse: () -> Unit,
    onUploadMyAvatar: () -> Unit,
    onPlaceholderConvert: () -> Unit
) {
    if (!visible) return
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("AI 设置", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(16.dp))
                AiSettingsItem("上传 AI 头像") { onClose(); onUploadAvatar() }
                AiSettingsItem("恢复默认头像") { onClose(); onRestoreAvatar() }
                AiSettingsItem("设置 AI 名称") { onClose(); onSetName() }
                AiSettingsItem("恢复 AI 名称") { onClose(); onRestoreName() }
                AiSettingsItem("人物对换" + if (reverseHistory) "（已开启）" else "") { onClose(); onReverse() }
                AiSettingsItem("上传我方头像") { onClose(); onUploadMyAvatar() }
                AiSettingsItem("占位符转换") { onClose(); onPlaceholderConvert() }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("取消", color = Color(0xFF6B7280), fontSize = 15.sp)
                }
            }
        }
    }
}
