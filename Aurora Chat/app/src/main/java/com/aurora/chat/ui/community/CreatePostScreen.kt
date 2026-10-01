package com.aurora.chat.ui.community

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                resources = resources + PendingResource(uri = uri, fileName = name, mimeType = mime, fileSize = fileSize)
            }
        }
    }

    suspend fun uploadResources(postId: Long): Boolean {
        for ((idx, res) in resources.withIndex()) {
            if (res.uploadedPath.isNotEmpty()) continue
            if (res.fileSize > MAX_UPLOAD_SIZE) {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "${res.fileName} 超过500MB限制，已跳过", Toast.LENGTH_SHORT).show() }
                resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = false) }
                continue
            }
            resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = true) }
            try {
                val inputStream = ctx.contentResolver.openInputStream(res.uri)
                if (inputStream == null) { resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = false) }; continue }
                val uploadMime = if (res.mimeType.startsWith("audio/")) "application/octet-stream" else res.mimeType
                val result = AuroraApi.uploadCommunityFileStreaming(postId, inputStream, res.fileName, uploadMime, totalBytes = res.fileSize)
                inputStream.close()
                if (result.success && result.data != null) {
                    val path = result.data!!.optString("file_path", "")
                    resources = resources.toMutableList().also { it[idx] = res.copy(uploadedPath = path, isUploading = false) }
                } else {
                    resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = false) }
                }
            } catch (_: Exception) {
                resources = resources.toMutableList().also { it[idx] = res.copy(isUploading = false) }
            }
        }
        return resources.all { it.uploadedPath.isNotEmpty() || resources.isEmpty() }
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
            val result = ChatRepository.createCommunityPost(title, content, postType, isAdult)
            if (result.success && result.data != null && isAdult) {
                AdultPostCache.markAdult(result.data!!.optLong("id"))
            }
            if (!result.success || result.data == null) {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "发布失败", Toast.LENGTH_SHORT).show() }
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

            uploadResources(postId)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "发布成功", Toast.LENGTH_SHORT).show()
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBackgroundDialog = false },
            containerColor = Color.White,
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
                        uploadResources(pendingPostId)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "发布成功", Toast.LENGTH_SHORT).show()
                        }
                        isUploading = false
                        onPostCreated()
                    }
                }) {
                    Text("直接上传", color = Color(0xFF6B7280))
                }
            }
        )
    }

    // ── 上传中返回确认 ──
    BackHandler(enabled = isUploading) { showExitConfirmDialog = true }
    if (showExitConfirmDialog) {
        androidx.compose.material3.AlertDialog(
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
            containerColor = Color.White
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", fontSize = 20.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    if (isUploading) showExitConfirmDialog = true else onBack()
                }.padding(end = 8.dp))
            Text("发布", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
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
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1E40AF)),
                decorationBox = { inner ->
                    Box(Modifier.padding(top = 2.dp)) {
                        if (title.isEmpty()) Text("标题", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD1D5DB))
                        inner()
                    }
                }
            )

            androidx.compose.material3.Divider(color = Color(0xFFE5E7EB), thickness = 1.dp)
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
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color(0xFF1F2937), lineHeight = 22.sp),
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

            // ── 成人内容标记 ──
            Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isAdult) Color(0xFF1E40AF) else Color.White)
                            .border(1.5.dp, if (isAdult) Color(0xFF1E40AF) else Color(0xFFD1D5DB), CircleShape)
                            .clickable { isAdult = !isAdult },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAdult) {
                            Text("✓", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("标注为成人内容", fontSize = 13.sp, color = Color(0xFF1E40AF),
                        modifier = Modifier.clickable { showAdultTooltip = true })
                }
            if (showAdultTooltip) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showAdultTooltip = false },
                    title = { Text("内容说明", fontWeight = FontWeight.Bold) },
                    text = { Text("如您发布的内容涉及成人色情低俗类的且不适宜让未成年人观看需勾选此框，如未勾选将会导致删帖并封禁处罚", fontSize = 14.sp, lineHeight = 22.sp) },
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
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun AddResourceButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF3F4F6))
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F6)).padding(10.dp),
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
            Text(res.fileName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937), maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (res.isUploading) "上传中..." else formatFileSize(res.fileSize), fontSize = 11.sp, color = Color(0xFF9CA3AF))
                if (isLarge && !res.isUploading) {
                    Spacer(Modifier.width(6.dp))
                    Text("大文件", fontSize = 10.sp, color = Color(0xFFD97706), fontWeight = FontWeight.Medium)
                }
            }
        }
        Text("✕", fontSize = 16.sp, color = Color(0xFF9CA3AF), modifier = Modifier.clickable { onRemove() }.padding(4.dp))
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
