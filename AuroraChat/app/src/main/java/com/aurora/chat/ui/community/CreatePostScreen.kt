package com.aurora.chat.ui.community
import com.aurora.chat.ui.theme.LocalAppColors

import com.aurora.chat.ui.chat.ensureMediaNameWithExt
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.AdultPostCache
import com.aurora.chat.UploadService
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog

data class PendingResource(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    var uploadedPath: String = "",
    var isUploading: Boolean = false
) {
    val ext: String get() = fileName.substringAfterLast('.', "").lowercase()
}

private const val LARGE_FILE_THRESHOLD = 50L * 1024 * 1024 // 50MB（超过弹窗提示后台上传）
private const val MAX_UPLOAD_SIZE = 500L * 1024 * 1024    // 500MB（服务端上限）

@Composable
fun CreatePostScreen(
    currentUserId: Long,
    onBack: () -> Unit,
    onPostCreated: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var resources by remember { mutableStateOf(listOf<PendingResource>()) }
    var isAdult by remember { mutableStateOf(false) }
    var showAdultTooltip by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadedPostId by remember { mutableStateOf(0L) }
    // 挂载的好友/群卡片
    var mountedCards by remember { mutableStateOf(listOf<MountedCard>()) }
    var showCardPicker by remember { mutableStateOf(false) }
    // 当前用户名（用于卡片「来自」字段）
    var currentUsername by remember { mutableStateOf("") }

    LaunchedEffect(currentUserId) {
        if (currentUserId > 0) {
            currentUsername = withContext(Dispatchers.IO) {
                runCatching { ChatRepository.getUserInfo(currentUserId).data?.username ?: "" }.getOrDefault("")
            }
        }
    }

    // ── 大文件后台上传对话框 ──
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var pendingPostId by remember { mutableStateOf(0L) }
    // ── 上传中返回确认 ──
    var showExitConfirmDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            var name = "unknown"
            var fileSize = 0L
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx) ?: "unknown"
                    val szIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (szIdx >= 0) fileSize = c.getLong(szIdx)
                }
            }
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            if (fileSize > MAX_UPLOAD_SIZE) {
                val mb = fileSize / (1024 * 1024)
                Toast.makeText(ctx, "不支持上传超过500MB的文件 (当前 ${mb}MB)", Toast.LENGTH_LONG).show()
            } else {
                // 文件名必须带扩展名：OpenableColumns.DISPLAY_NAME 可能无扩展名（或整个是数字 ID），
                // 直接上传会被后端 validateUploadExt 按"无扩展名"拒绝（表现为"任何文件都传不出去"）。
                // 用 MIME 兜底补扩展名，保证后端收到合法文件名。
                val safeName = ensureMediaNameWithExt(name, mime)
                resources = resources + PendingResource(uri = uri, fileName = safeName, mimeType = mime, fileSize = fileSize)
            }
        }
    }

    // 返回上传失败的文件名+原因列表（空列表代表全部成功）。不再静默吞掉失败。
    suspend fun uploadResources(postId: Long): List<String> {
        val failed = mutableListOf<String>()
        for ((idx, res) in resources.withIndex()) {
            if (res.uploadedPath.isNotEmpty()) continue
            if (res.fileSize > MAX_UPLOAD_SIZE) {
                failed.add("${res.fileName}：超过500MB限制，已跳过")
                resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = false) }
                continue
            }
            resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = true) }
            try {
                val inputStream = ctx.contentResolver.openInputStream(res.uri)
                if (inputStream == null) {
                    failed.add("${res.fileName}：无法读取文件")
                    resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = false) }
                    continue
                }
                val uploadMime = if (res.mimeType.startsWith("audio/")) "application/octet-stream" else res.mimeType
                val result = AuroraApi.uploadCommunityFileStreaming(postId, inputStream, res.fileName, uploadMime, totalBytes = res.fileSize)
                inputStream.close()
                if (result.success && result.data != null) {
                    val path = result.data!!.optString("file_path", "")
                    resources = resources.toMutableList().also { it[idx] = res.copy(uploadedPath = path, isUploading = false) }
                } else {
                    // 后端拒绝（如扩展名不合法）或网络错误：如实记录，不让帖子假成功
                    failed.add("${res.fileName}：${result.message.ifBlank { "未知错误" }}")
                    resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = false) }
                }
            } catch (e: Exception) {
                failed.add("${res.fileName}：${e.localizedMessage ?: "网络错误"}")
                resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = false) }
            }
        }
        return failed
    }

    fun startBackgroundUpload(postId: Long) {
        val intent = UploadService.startIntent(
            ctx, postId,
            resources.map { it.uri },
            resources.map { it.fileName },
            resources.map { it.mimeType }
        )
        ctx.startForegroundService(intent)
        Toast.makeText(ctx, "正在后台上传，请勿关闭应用后台", Toast.LENGTH_LONG).show()
        onPostCreated()
    }

    suspend fun doCreatePost() {
        if (title.isBlank()) return
        try {
            isUploading = true
            // 有附件即视为资源，无附件为图文：由内容本身决定，不与服务端 post_type 自动改判冲突
            val postType = if (resources.isNotEmpty()) "resource" else "post"
            // 把挂载的好友/群卡片序列化为「分享用户/分享群聊」文本块追加进正文（不新增后端字段）
            val finalContent = buildFinalContent(content, mountedCards, currentUsername)
            val result = ChatRepository.createCommunityPost(title, finalContent, postType, isAdult)
            if (result.success && result.data != null && isAdult) {
                AdultPostCache.markAdult(result.data!!.optLong("id"))
            }
            if (!result.success || result.data == null) {
                // 带上服务端返回的原因（如「你已被社区禁言」），不再只显示干巴巴的"发布失败"
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "发布失败: ${result.message.ifBlank { "未知错误" }}", Toast.LENGTH_SHORT).show() }
                isUploading = false
                return
            }
            val postId = result.data!!.optLong("id")
            uploadedPostId = postId

            // 检查是否有大文件
            val largeFiles = resources.filter { it.fileSize > LARGE_FILE_THRESHOLD }
            if (largeFiles.isNotEmpty() && resources.any { it.uploadedPath.isEmpty() }) {
                pendingPostId = postId
                showBackgroundDialog = true
                isUploading = false
                return
            }

            val failed = uploadResources(postId)
            withContext(Dispatchers.Main) {
                if (failed.isEmpty()) {
                    Toast.makeText(ctx, "发布成功", Toast.LENGTH_SHORT).show()
                } else {
                    // 部分文件上传失败：明确告知用户，避免“发出去却看不到文件”的困惑
                    Toast.makeText(ctx, "发布成功，但 ${failed.size} 个文件上传失败：\n${failed.joinToString("\n")}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CreatePost", "发布异常", e)
            withContext(Dispatchers.Main) { Toast.makeText(ctx, "发布失败: ${e.localizedMessage ?: "未知错误"}", Toast.LENGTH_SHORT).show() }
        } finally {
            isUploading = false
        }
        onPostCreated()
    }

    // ── 大文件确认对话框 ──
    if (showBackgroundDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("检测到大型资源", fontWeight = FontWeight.Bold) },
            text = {
                Text("部分上传的资源大小超过 ${LARGE_FILE_THRESHOLD / (1024 * 1024)}MB，是否切换到后台上传？\n\n" +
                        "确认后即使用户退出应用，只要不手动关闭应用后台上传即可继续。上传完成后将通过通知提醒您。")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showBackgroundDialog = false
                    startBackgroundUpload(pendingPostId)
                }) {
                    Text("后台上传", color = Color(0xFF1E40AF))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showBackgroundDialog = false
                    scope.launch {
                        isUploading = true
                        val failed = uploadResources(pendingPostId)
                        withContext(Dispatchers.Main) {
                            if (failed.isEmpty()) {
                                Toast.makeText(ctx, "发布成功", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "发布成功，但 ${failed.size} 个文件上传失败：\n${failed.joinToString("\n")}", Toast.LENGTH_LONG).show()
                            }
                        }
                        isUploading = false
                        onPostCreated()
                    }
                }) {
                    Text("直接上传", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }

    // ── 上传中返回确认 ──
    BackHandler(enabled = isUploading) { showExitConfirmDialog = true }
    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("上传进行中", fontWeight = FontWeight.Bold) },
            text = { Text("当前有文件正在上传，返回后将终止上传。\n是否确认返回？") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showExitConfirmDialog = false
                    isUploading = false
                    onBack()
                }) { Text("确认返回", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showExitConfirmDialog = false }) {
                    Text("继续上传", color = Color(0xFF1E40AF))
                }
            },
            containerColor = com.aurora.chat.ui.theme.appSurface()
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .statusBarsPadding()
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", fontSize = 20.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    if (isUploading) showExitConfirmDialog = true else onBack()
                }.padding(end = 8.dp))
            Text("发布", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (title.isBlank()) Color(0xFFD1D5DB) else Color(0xFF1E40AF))
                    .clickable(enabled = title.isNotBlank() && !isUploading) {
                        scope.launch { doCreatePost() }
                    }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isUploading) "发布中..." else "发布", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp)) {
            // 标题
            androidx.compose.foundation.text.BasicTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                decorationBox = { inner ->
                    Box(Modifier.padding(top = 2.dp)) {
                        if (title.isEmpty()) Text("标题", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD1D5DB))
                        inner()
                    }
                }
            )

            androidx.compose.material3.Divider(color = com.aurora.chat.ui.theme.appDivider(), thickness = 1.dp)
            Spacer(Modifier.height(8.dp))

            // 内容
            val contentInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isContentFocused by contentInteractionSource.collectIsFocusedAsState()
            val contentFocusRequester = remember { FocusRequester() }
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).clickable(
                    interactionSource = contentInteractionSource,
                    indication = null
                ) { contentFocusRequester.requestFocus() }) {
                androidx.compose.foundation.text.BasicTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxSize().focusRequester(contentFocusRequester),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 22.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                    interactionSource = contentInteractionSource
                )
                if (content.isEmpty() && !isContentFocused) {
                    Text("写点什么...", fontSize = 15.sp, color = Color(0xFFD1D5DB))
                }
            }

            Spacer(Modifier.height(16.dp))

            // 已选资源预览
            resources.forEach { res ->
                ResourcePreviewItem(ctx = ctx, res = res, onRemove = {
                    resources = resources.filter { it.uri != res.uri }
                })
                Spacer(Modifier.height(8.dp))
            }

            // 已挂载卡片预览（可删除）
            mountedCards.forEach { card ->
                MountedCardPreviewItem(card = card, onRemove = {
                    mountedCards = mountedCards.filter { it != card }
                })
                Spacer(Modifier.height(8.dp))
            }

            // ── 敏感内容标记 ──
            Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isAdult) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                            .border(1.5.dp, if (isAdult) Color(0xFF1E40AF) else Color(0xFFD1D5DB), CircleShape)
                            .clickable { isAdult = !isAdult },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAdult) {
                            Text("✓", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("标注为敏感内容", fontSize = 13.sp, color = Color(0xFF1E40AF),
                        modifier = Modifier.clickable { showAdultTooltip = true })
                }
            if (showAdultTooltip) {
                AlertDialog(
                    onDismissRequest = { showAdultTooltip = false },
                    title = { Text("内容说明", fontWeight = FontWeight.Bold) },
                    text = { Text("如你发布的内容含敏感信息且不适宜让未成年观看，需勾选此框，如未勾选会导致删帖并封禁处理。", fontSize = 14.sp, lineHeight = 22.sp) },
                    confirmButton = {
                        Text("知道了", color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable { showAdultTooltip = false }.padding(12.dp))
                    }
                )
            }

            // 添加资源按钮
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AddResourceButton("图片/视频", onClick = { filePickerLauncher.launch(arrayOf("image/*", "video/*")) })
                AddResourceButton("文件", onClick = { filePickerLauncher.launch(arrayOf("*/*")) })
                AddResourceButton("添加卡片", onClick = { showCardPicker = true })
            }

            Spacer(Modifier.height(40.dp))
        }
      }

      // ── 添加卡片选择界面（全屏覆盖） ──
      if (showCardPicker) {
          CardPickerScreen(
              onDismiss = { showCardPicker = false },
              initialSelected = mountedCards,
              onConfirm = { picked ->
                  mountedCards = picked
                  showCardPicker = false
              }
          )
      }
    }
}

@Composable
private fun AddResourceButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(com.aurora.chat.ui.theme.appCardBg())
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF4B5563))
    }
}

@Composable
private fun ResourcePreviewItem(ctx: android.content.Context, res: PendingResource, onRemove: () -> Unit) {
    val isImage = res.mimeType.startsWith("image/")
    val isLarge = res.fileSize > LARGE_FILE_THRESHOLD
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(com.aurora.chat.ui.theme.appCardBg()).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isImage) {
            var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(res.uri) {
                withContext(Dispatchers.IO) {
                    try {
                        val input = ctx.contentResolver.openInputStream(res.uri)
                        val bytes = input?.readBytes()
                        input?.close()
                        bitmap = bytes?.let { b ->
                            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, opts)
                            opts.inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 96)
                            opts.inJustDecodeBounds = false
                            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, opts)
                        }
                    } catch (_: Exception) {}
                }
            }
            bitmap?.let { bmp ->
                Image(bitmap = bmp.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop)
            } ?: Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFD1D5DB)))
        } else {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(
                when (res.ext) {
                    "apk" -> Color(0xFF16A34A)
                    "zip", "rar", "7z" -> Color(0xFFD97706)
                    else -> Color(0xFFD1D5DB)
                }
            ), contentAlignment = Alignment.Center) {
                when {
                    res.mimeType.startsWith("video/") -> Text("▶", fontSize = 18.sp)
                    res.mimeType.startsWith("audio/") -> Text("♪", fontSize = 18.sp)
                    res.ext == "apk" -> Text("APK", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    res.ext in listOf("zip", "rar", "7z") -> Text("ZIP", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    else -> Text("📄", fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(res.fileName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (res.isUploading) "上传中..." else formatFileSize(res.fileSize), fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                if (isLarge && !res.isUploading) {
                    Spacer(Modifier.width(6.dp))
                    Text("大文件", fontSize = 10.sp, color = Color(0xFFD97706), fontWeight = FontWeight.Medium)
                }
            }
        }
        Text("✕", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.clickable { onRemove() }.padding(4.dp))
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

/** 把挂载卡片序列化为「分享用户/分享群聊」文本块，追加到帖子正文末尾（空行分隔）。 */
private fun buildFinalContent(body: String, cards: List<MountedCard>, author: String): String {
    if (cards.isEmpty()) return body
    val sb = StringBuilder(body)
    cards.forEachIndexed { idx, card ->
        if (idx == 0) {
            if (body.isNotBlank()) sb.append("\n")
        } else {
            sb.append("\n")
        }
        sb.append(serializeCard(card, author))
    }
    return sb.toString()
}

private fun serializeCard(card: MountedCard, author: String): String = when (card.type) {
    MountedCard.TYPE_GROUP ->
        "分享群聊\n━━━\n${card.name}\n━━━\n来自 $author\ngroup_id=${card.id}"
    else ->
        "分享用户\n━━━\n${card.name}\n━━━\n来自 $author\nuser_id=${card.id}"
}

/** 发帖页已挂载卡片预览条目（头像 + 名称 + 删除）。 */
@Composable
private fun MountedCardPreviewItem(card: MountedCard, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg()).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (card.type == MountedCard.TYPE_GROUP) {
            GroupAvatar(internalGroupId = card.id, groupName = card.name, size = 20.dp)
        } else {
            UserAvatar(userId = card.id, userName = card.name, size = 20.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(card.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, modifier = Modifier.weight(1f))
        Text(if (card.type == MountedCard.TYPE_GROUP) "群聊卡片" else "用户卡片", fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextHint())
        Spacer(Modifier.width(6.dp))
        Text("✕", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.clickable { onRemove() }.padding(2.dp))
    }
}
