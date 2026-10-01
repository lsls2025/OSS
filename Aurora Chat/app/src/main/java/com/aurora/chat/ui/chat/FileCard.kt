@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aurora.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 聊天文件卡片的应用内音频播放器：播放/停止本地已下载的音频，不跳系统音乐软件。 */
object ChatAudioPlayer {
    @Volatile private var player: android.media.MediaPlayer? = null
    @Volatile private var currentPath: String? = null
    @Volatile private var isPlayingState = false
    private var stateListener: ((Boolean) -> Unit)? = null

    /** 指定音频文件当前是否在播放中 */
    fun currentIsPlaying(path: String): Boolean = currentPath == path && isPlayingState

    /** 播放/停止切换：点同一文件再点停止；点其他文件则切过去播放 */
    fun toggle(path: String, onStateChanged: (Boolean) -> Unit) {
        stateListener = onStateChanged
        val cur = player
        if (currentPath == path && cur != null) {
            if (cur.isPlaying) {
                cur.pause(); isPlayingState = false; onStateChanged(false)
            } else {
                cur.start(); isPlayingState = true; onStateChanged(true)
            }
            return
        }
        try {
            cur?.release()
            val p = android.media.MediaPlayer()
            p.setDataSource(path)
            p.setOnCompletionListener { isPlayingState = false; stateListener?.invoke(false) }
            p.setOnErrorListener { _, _, _ -> isPlayingState = false; stateListener?.invoke(false); true }
            p.setOnPreparedListener { p.start(); isPlayingState = true; onStateChanged(true) }
            p.prepareAsync()
            player = p
            currentPath = path
        } catch (_: Exception) {
            onStateChanged(false)
        }
    }
}

/**
 * 聊天里的文件消息卡片（TXT / APK / PDF / 任意文件）。
 * 样式参考 ShareCard，更紧凑：左侧扩展名色块，中部文件名 + 大小/类型，右侧下载图标。
 * 仅用于"已确认为文件、非图片非视频"的消息，绝不误判图片/视频。
 */
/** 记录用户通过「另存为」显式保存过的文件源地址。 */
object SavedFiles {
    private const val PREFS = "aurora_saved_files"
    fun mark(ctx: android.content.Context, sourceKey: String) {
        try {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(sourceKey, true).apply()
        } catch (_: Exception) {}
    }
    fun isSaved(ctx: android.content.Context, sourceKey: String): Boolean {
        if (sourceKey.isBlank()) return false
        return try {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(sourceKey, false)
        } catch (_: Exception) { false }
    }
}
private val fileSizeCache = ConcurrentHashMap<String, String>()

private fun extLabel(ext: String): String = when (ext) {
    "txt", "text", "log", "md", "markdown" -> "文本文档"
    "apk" -> "APK 文件"
    "pdf" -> "PDF 文档"
    "zip", "rar", "7z", "gz", "tar" -> "压缩包"
    "doc", "docx" -> "Word 文档"
    "xls", "xlsx", "csv" -> "Excel 表格"
    "ppt", "pptx" -> "PPT 演示"
    "json", "xml", "ini", "conf", "yml", "yaml" -> "配置文件"
    "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "音频文件"
    "" -> "文件"
    else -> "$ext 文件".uppercase()
}

private fun extColor(ext: String): Color = when (ext) {
    "apk" -> Color(0xFF16A34A)
    "pdf" -> Color(0xFFDC2626)
    "txt", "text", "log", "md", "markdown", "json", "xml", "ini", "conf", "yml", "yaml", "csv" -> Color(0xFF2563EB)
    "zip", "rar", "7z", "gz", "tar" -> Color(0xFFD97706)
    "doc", "docx" -> Color(0xFF2563EB)
    "xls", "xlsx" -> Color(0xFF059669)
    "ppt", "pptx" -> Color(0xFFEA580C)
    else -> Color(0xFF6B7280)
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
    else -> String.format("%.1f GB", bytes / (1024f * 1024f * 1024f))
}

/** HEAD 请求读取文件大小文本；失败返回空串（回退显示类型标签）。 */
private fun fetchFileSizeText(url: String): String {
    // 本地文件（file:// 协议）：直接读磁盘大小，OkHttp 不支持非 http(s) scheme，且能显示 AI_GetFile 本地文件的真实大小
    if (url.startsWith("file://")) {
        return try {
            val f = java.io.File(url.removePrefix("file://"))
            if (f.exists() && f.isFile) formatSize(f.length()) else ""
        } catch (_: Exception) {
            ""
        }
    }
    return try {
        val req = okhttp3.Request.Builder().url(url).head().header("Connection", "close").build()
        com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
            val len = resp.body?.contentLength() ?: -1L
            if (len > 0) formatSize(len) else ""
        }
    } catch (_: Exception) {
        ""
    }
}

@Composable
fun ChatFileCard(
    url: String,
    fileName: String,
    ext: String,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onBubbleLongPress: () -> Unit = {},
    // AI 直接发送的文件卡片：没有聊天气泡包裹，白卡铺在白色背景上会显得突兀，故加一圈灰色描边框住
    showBorder: Boolean = false
) {
    var sizeText by remember { mutableStateOf(fileSizeCache[url] ?: "") }
    LaunchedEffect(url) {
        if (sizeText.isEmpty()) {
            val cached = fileSizeCache[url]
            if (cached != null) {
                sizeText = cached
            } else {
                val label = fetchFileSizeText(url)
                fileSizeCache[url] = label
                sizeText = label
            }
        }
    }

    val context = LocalContext.current
    var apkIcon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val isApk = ext == "apk"
    LaunchedEffect(url, ext) {
        if (isApk && apkIcon == null) {
            com.aurora.chat.util.ApkIconExtractor.ensureIcon(context, url, null) { bmp ->
                apkIcon = bmp
            }
        }
    }

    val typeLabel = extLabel(ext)
    val subText = if (sizeText.isNotEmpty()) "$typeLabel · $sizeText" else typeLabel
    val accent = extColor(ext)

    // 已下载检测：本地下载目录里是否存在该文件（下载走 filesDir/downloads/<fileName>）
    val audioExts = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus")
    val isAudio = ext in audioExts
    val localFile = File(context.filesDir, "downloads/$fileName")
    // 音频是否需要本地源文件才能播放（沿用旧逻辑：本机存在即可播放）
    var downloaded by remember(fileName) { mutableStateOf(localFile.exists()) }
    LaunchedEffect(fileName) {
        com.aurora.chat.ui.tools.DownloadManager.downloadChanged.collect {
            downloaded = localFile.exists()
        }
    }
    // 用户是否已通过「另存为」显式保存过该文件：只有显式保存过才显示「已保存」。
    // 不能把「本机存在同名源文件」当成「已下载」，否则 AI 端本地生成的文件在用户未保存前就会误显示为已下载。
    var saved by remember(url, fileName) { mutableStateOf(SavedFiles.isSaved(context, url)) }
    LaunchedEffect(url, fileName) {
        com.aurora.chat.ui.tools.DownloadManager.downloadChanged.collect {
            saved = SavedFiles.isSaved(context, url)
        }
    }
    var audioPlaying by remember { mutableStateOf(ChatAudioPlayer.currentIsPlaying(localFile.absolutePath)) }

    Card(
        modifier = Modifier
            .widthIn(max = 272.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // 音频且已下载：点击播放/停止；否则走原有交互（下载确认等）
                    if (isAudio && downloaded) {
                        ChatAudioPlayer.toggle(localFile.absolutePath) { playing -> audioPlaying = playing }
                    } else {
                        onClick()
                    }
                },
                onLongClick = onBubbleLongPress
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (showBorder) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E7EB)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：扩展名色块（APK 优先显示提取出的应用图标，否则显示默认 APK 占位符）
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isApk) Color(0xFFF0FDF4) else accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (isApk) {
                    val icon = apkIcon
                    if (icon != null) {
                        Image(
                            bitmap = icon.asImageBitmap(),
                            contentDescription = fileName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Android,
                            contentDescription = "APK",
                            tint = accent,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                } else {
                    Text(
                        text = ext.ifEmpty { "FILE" }.take(4).uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // 中部：文件名 + 大小/类型
            Column(Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1F2937),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                // 类型/大小 + 右侧"已下载"（音频播放中显示"播放中"）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subText,
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isAudio && audioPlaying) {
                        Spacer(Modifier.width(6.dp))
                        Text("播放中", fontSize = 11.sp, color = Color(0xFF059669))
                    } else if (saved) {
                        Spacer(Modifier.width(6.dp))
                        Text("已保存", fontSize = 11.sp, color = Color(0xFF16A34A))
                    }
                }
            }
            // 右侧：下载图标
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = "下载",
                tint = Color(0xFF1E40AF),
                modifier = Modifier
                    .size(24.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onDownload() },
                        onLongClick = onBubbleLongPress
                    )
            )
        }
    }
}

/**
 * 上传中的文件占位卡片：先画出文件卡基础架构（扩展名色块 + 文件名 + 类型），
 * 下方用进度条 + 百分比展示「压缩中/上传中」，避免上传期间出现空气泡。
 */
@Composable
fun ChatFileUploadCard(
    fileName: String,
    progress: Float,
    stage: String
) {
    val ext = fileName.substringAfterLast('.', "").lowercase().ifEmpty { "file" }
    val typeLabel = extLabel(ext)
    val accent = extColor(ext)
    val pct = (progress.coerceIn(0f, 1f) * 100).toInt().coerceIn(0, 100)
    val stageText = if (stage == "compressing") "压缩中" else "上传中"

    Card(
        modifier = Modifier.widthIn(max = 292.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左侧：扩展名色块
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ext.take(4).uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(12.dp))
                // 中部：文件名 + 类型
                Column(Modifier.weight(1f)) {
                    Text(
                        text = fileName.ifEmpty { "文件上传中" },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2937),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = typeLabel,
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 进度条 + 百分比
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF1E40AF),
                        trackColor = Color(0xFFE5E7EB)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text("$stageText $pct%", fontSize = 12.sp, color = Color(0xFF1E40AF))
            }
        }
    }
}
