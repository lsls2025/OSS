package com.aurora.chat.ui.tools

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun DownloadQueueScreen(
    onMinimize: () -> Unit
) {
    val queue = DownloadManager.queue
    var showClearDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var showInAppText by remember { mutableStateOf(false) }
    var inAppTextUrl by remember { mutableStateOf("") }
    var inAppTextName by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        com.aurora.chat.ui.components.EventBlocker()
        Column(Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8F9FA))
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮（最小化到悬浮球）
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onMinimize() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 20.sp, color = Color(0xFF1F2937))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "下载管理",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937)
                )
                if (queue.isNotEmpty()) {
                    val active = queue.count { it.isDownloading.value }
                    Text(
                        "（${queue.size}个${if (active > 0) "，${active}下载中" else ""}）",
                        fontSize = 13.sp,
                        color = Color(0xFF6B7280)
                    )
                }
                Spacer(Modifier.weight(1f))
                // 清理记录按钮
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFDC2626))
                        .clickable { showClearDialog = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("清理记录", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                Spacer(Modifier.width(4.dp))
            }

            Spacer(Modifier.height(8.dp))

            if (queue.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无下载任务", fontSize = 16.sp, color = Color(0xFF9CA3AF))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(queue, key = { it.id }) { item ->
                        DownloadQueueItem(
                            item = item,
                            onOpenInApp = { url, name ->
                                inAppTextName = name
                                inAppTextUrl = url
                                showInAppText = true
                            }
                        )
                    }
                }
            }
        }

        // 清理记录确认弹窗
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                containerColor = Color.White,
                title = { Text("清理记录", fontWeight = FontWeight.SemiBold) },
                text = { Text("确定要清理所有下载记录吗？\n当前正在下载的任务也会被取消。", fontSize = 14.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        showClearDialog = false
                        DownloadManager.clearAllRecords(context)
                    }) {
                        Text("确认清理", color = Color(0xFFDC2626))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("取消", color = Color(0xFF9CA3AF))
                    }
                }
            )
        }

        // ── 应用内文本查看器（从 URL 拉取内容，同卡片内查看内容原理） ──
        if (showInAppText) {
            var fetchedContent by remember(inAppTextUrl) { mutableStateOf("") }
            var isLoading by remember(inAppTextUrl) { mutableStateOf(true) }
            LaunchedEffect(inAppTextUrl) {
                withContext(Dispatchers.IO) {
                    try {
                        val url = inAppTextUrl
                        fetchedContent = com.aurora.chat.data.api.HttpClient.jsonRequest("GET", url)
                    } catch (_: Exception) { fetchedContent = "加载失败" }
                    isLoading = false
                }
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.White).clickable(enabled = false) {}) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("←", fontSize = 20.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { showInAppText = false }.padding(end = 8.dp))
                        Text(inAppTextName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
                    if (isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                        }
                    } else {
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            SelectionContainer {
                                Text(fetchedContent, fontSize = 13.sp, color = Color(0xFF374151), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueItem(item: DownloadItem, onOpenInApp: (url: String, name: String) -> Unit = { _, _ -> }) {
    val progress by item.progress.collectAsState()
    val isDownloading by item.isDownloading.collectAsState()
    val isCompleted by item.isCompleted.collectAsState()
    val isPaused by item.isPaused.collectAsState()
    val error by item.error.collectAsState()
    val speedText by item.speedText.collectAsState()
    val context = LocalContext.current

    val audioExts = listOf("mp3", "wav", "ogg", "aac", "flac", "m4a", "wma")
    val textExts = listOf("txt", "json", "xml", "html", "css", "js", "md", "log", "csv", "ini", "cfg", "properties",
        "py", "c", "h", "java", "kt", "go", "rs", "ts", "php", "swift")
    var showOpenDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // APK：本地已下载时尝试提取该 APK 自己的应用图标；失败则显示默认 APK 占位符（不用本应用图标）
    var apkIcon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val isApkItem = item.fileExt == "apk"
    LaunchedEffect(item.id, item.fileExt, isCompleted) {
        if (isApkItem && apkIcon == null) {
            val local = File(context.filesDir, "downloads/${item.fileName}")
            if (local.exists()) {
                com.aurora.chat.util.ApkIconExtractor.ensureIcon(context, "file:${local.absolutePath}", local.absolutePath) { bmp ->
                    apkIcon = bmp
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF3F4F6))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：应用图标 / 社区文件扩展名标签
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isApkItem) Color(0xFFF0FDF4) else Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (isApkItem) {
                // APK：优先显示该 APK 自己的图标，否则默认 APK 占位符
                val icon = apkIcon
                if (icon != null) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Android,
                        contentDescription = "APK",
                        tint = Color(0xFF16A34A),
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else if (item.iconUrl.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = item.iconUrl,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (item.iconRes != 0) {
                Image(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else if (item.fileExt.isNotEmpty()) {
                val (label, bgColor) = when (item.fileExt) {
                    "apk" -> "APK" to Color(0xFF16A34A)
                    "zip", "rar", "7z" -> "ZIP" to Color(0xFFD97706)
                    "pdf" -> "PDF" to Color(0xFFDC2626)
                    "txt", "json", "xml", "html", "css", "js", "md", "log", "csv", "ini", "cfg", "properties" -> "TXT" to Color(0xFF2563EB)
                    "py" -> "PY" to Color(0xFF306998)
                    "c", "h" -> "C" to Color(0xFF004481)
                    "mp3", "wav", "ogg", "aac", "flac", "m4a", "wma" -> "♪" to Color(0xFF16A34A)
                    else -> item.fileExt.uppercase().take(4) to Color(0xFF6B7280)
                }
                Box(Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(6.dp)).background(bgColor),
                    contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            } else {
                Text(
                    text = item.name.take(2),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9CA3AF)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 中间：名称 + 状态
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1F2937),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    error != null -> Text(error!!, fontSize = 12.sp, color = Color(0xFFDC2626),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    isCompleted -> Text("下载完成", fontSize = 12.sp, color = Color(0xFF16A34A))
                    isPaused -> Text("已暂停", fontSize = 12.sp, color = Color(0xFFF59E0B))
                    isDownloading -> Text("正在下载...", fontSize = 12.sp, color = Color(0xFF6B7280))
                    else -> Text("等待中", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                }
                // 下载中时在"正在下载"右边显示网速
                if (isDownloading && speedText.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(speedText, fontSize = 11.sp, color = Color(0xFF1E40AF))
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 操作按钮
        if (error != null) {
            // 失败：重试
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E40AF))
                    .clickable { DownloadManager.retryItem(context, item.id) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("重试", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            // 失败时也显示删除
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFDC2626))
                    .clickable { showDeleteDialog = true }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("删除", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        } else if (isCompleted) {
            // 完成：按文件类型显示不同按钮
            if (item.fileExt == "apk" || item.packageName.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 启动按钮
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E40AF))
                            .clickable { launchInstalledApp(context, item) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("启动", fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                    Spacer(Modifier.height(4.dp))
                    // 安装按钮
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF16A34A))
                            .clickable { DownloadManager.installApp(context, item) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("安装", fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            } else {
                // 社区文件：按类型显示不同按钮
                val (btnLabel, btnColor) = when {
                    item.fileExt in audioExts -> "播放" to Color(0xFF059669)
                    item.fileExt in textExts -> "查看" to Color(0xFF1E40AF)
                    else -> "打开" to Color(0xFF1E40AF)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(btnColor)
                        .clickable {
                            val file = java.io.File(context.filesDir, "downloads/${item.fileName}")
                            if (!file.exists()) {
                                android.widget.Toast.makeText(context, "文件不存在，请重新下载", android.widget.Toast.LENGTH_SHORT).show()
                                return@clickable
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            // 文本文件 → 弹出选择打开方式
                            if (item.fileExt in textExts) {
                                showOpenDialog = true
                                return@clickable
                            }
                            // 其他文件直接打开
                            val mime = when (item.fileExt) {
                                in audioExts -> "audio/*"
                                "pdf" -> "application/pdf"
                                "zip", "rar", "7z" -> "application/zip"
                                else -> "*/*"
                            }
                            launchSystemViewer(context, uri, mime)
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(btnLabel, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
            Spacer(Modifier.width(4.dp))
        } else if (isDownloading || isPaused) {
            // 下载中/暂停中：显示删除按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFDC2626))
                    .clickable { showDeleteDialog = true }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("删除", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }

        Spacer(Modifier.width(8.dp))

        // 右侧：下载百分比
        Box(
            modifier = Modifier
                .size(52.dp, 52.dp),
            contentAlignment = Alignment.Center
        ) {
            // 圆形进度背景
            androidx.compose.material3.CircularProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxSize(),
                color = when {
                    error != null -> Color(0xFFDC2626)
                    isCompleted -> Color(0xFF16A34A)
                    else -> Color(0xFF1E40AF)
                },
                trackColor = Color(0xFFE5E7EB),
                strokeWidth = 4.dp
            )
            Text(
                "${progress}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
                textAlign = TextAlign.Center
            )
        }
    }

    // ── 选择打开方式对话框（文本文件） ──
    if (showOpenDialog) {
        AlertDialog(
            onDismissRequest = { showOpenDialog = false },
            containerColor = Color.White,
            title = { Text("请选择打开方式", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val readScope = rememberCoroutineScope()
                    TextButton(
                        onClick = {
                            showOpenDialog = false
                            onOpenInApp(item.downloadUrl, item.name)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) { Text("应用内打开", fontSize = 15.sp, color = Color(0xFF1E40AF)) }
                    TextButton(
                        onClick = {
                            showOpenDialog = false
                            val file = java.io.File(context.filesDir, "downloads/${item.fileName}")
                            if (file.exists()) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", file
                                )
                                launchSystemViewer(context, uri, "text/plain", chooseSystem = true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) { Text("用其他应用打开", fontSize = 15.sp, color = Color(0xFF4B5563)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showOpenDialog = false }) { Text("取消", color = Color(0xFF9CA3AF)) }
            }
        )
    }

    // ── 删除确认弹窗 ──
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color.White,
            title = { Text("删除下载", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定要删除「${item.name}」的下载吗？\n已下载的文件也会被删除。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    DownloadManager.deleteDownload(context, item.id)
                }) {
                    Text("确认删除", color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = Color(0xFF9CA3AF))
                }
            }
        )
    }

}

/** 在下载队列中尝试启动已安装的应用，未安装则提示 */
private fun launchInstalledApp(context: android.content.Context, item: DownloadItem) {
    val launched = DownloadManager.launchApp(context, item)
    if (!launched) {
        // 启动失败 → 引导用户开启 QUERY_ALL_PACKAGES 权限（Android 11+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val pkg = DownloadManager.getPackageNameFromApk(context, item)
            if (pkg.isNotEmpty()) {
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    }
                } catch (_: Exception) {}
            }
        }
    }
}

private fun launchSystemViewer(context: android.content.Context, uri: android.net.Uri, mimeType: String, chooseSystem: Boolean = false) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        if (chooseSystem) {
            context.startActivity(android.content.Intent.createChooser(intent, "选择应用"))
        } else {
            context.startActivity(intent)
        }
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, "没有可打开此文件的应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 悬浮球 - 显示在任何界面上层，点击回到下载管理 */
@Composable
fun DownloadFloatingBubble(
    onTap: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 100.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E40AF))
                .clickable { onTap() },
            contentAlignment = Alignment.Center
        ) {
            Text("↓", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
