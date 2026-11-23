package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 视频显示尺寸统一口径：把真实宽高比 [ratio] 等比收进「适中」上限内（横向撑满 maxW、纵向撑满 maxH，
 * 另一边封顶），**不放大**。上传中占位与发布后的缩略图都走这一个函数，保证同一视频两种状态尺寸完全一致
 * （此前上传中用 width(160).aspectRatio、发布后用 150×200 两套口径，竖屏视频上传态明显更大）。
 */
internal fun videoDisplaySize(ratio: Float, maxWidthDp: Dp = 140.dp, maxHeightDp: Dp = 180.dp): Pair<Dp, Dp> {
    val maxW = maxWidthDp
    val maxH = maxHeightDp
    val ar = if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(0.4f, 2.5f) else 1f
    return if (ar >= 1f) {
        Pair(maxW, (maxW / ar).coerceAtMost(maxH))
    } else {
        Pair((maxH * ar).coerceAtMost(maxW), maxH)
    }
}

/**
 * 同时取帧的并发上限：九宫格里一屏能有十几个视频，全部同时联网取帧会把网络和
 * MediaMetadataRetriever 一起拖死；限 2 个排队，其余等前面的落盘后直接命中缓存。
 */
private val coverFetchLock = java.util.concurrent.Semaphore(2)

/**
 * 取视频封面（一张首帧位图），与 [VideoThumbnailView] 共用同一份磁盘缓存：
 * - 先读缓存，命中即零网络（在会话里看过的视频，搜索列表直接显示同一张封面）；
 * - 没缓存才联网取帧，取到后落盘，供会话里的 [VideoThumbnailView] 直接复用。
 *
 * 供九宫格/搜索结果这类只需要「一张封面」的地方调用，调用方负责放到 IO 线程。
 */
internal fun loadVideoCoverCached(context: android.content.Context, videoUrl: String, maxDim: Int = 0): Bitmap? {
    com.aurora.chat.data.local.VideoThumbCache.load(context, videoUrl, maxDim)?.let { return it }
    coverFetchLock.acquireUninterruptibly()
    try {
        // 排队期间别的调用可能已经取到并落盘，再查一次
        com.aurora.chat.data.local.VideoThumbCache.load(context, videoUrl, maxDim)?.let { return it }
        return runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                // 整片视频已缓存（看过）→ 读本地文件；否则才走网络
                val cachedVideo = com.aurora.chat.data.local.MediaCache.getCachedVideoUri(context, videoUrl)
                if (cachedVideo != null) mmr.setDataSource(context, cachedVideo)
                else mmr.setDataSource(videoUrl.removePrefix("file://"))
                mmr.frameAtTime?.also {
                    com.aurora.chat.data.local.VideoThumbCache.save(context, videoUrl, it)
                }
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()
    } finally {
        coverFetchLock.release()
    }
}

// ── VideoThumbnailView ──
@Composable
fun VideoThumbnailView(
    videoUrl: String,
    modifier: Modifier,
    /** 调用方已知宽高比（消息宽高 / 上传比例）；>0 时首帧即用它定框，避免 1:1 抖动 */
    initialAspectRatio: Float = 0f,
    /** 引用胶囊里需要更小的缩略（默认是消息正文那档 140×180） */
    maxWidthDp: Dp = 140.dp,
    maxHeightDp: Dp = 180.dp
) {
    val ctx = LocalContext.current
    // 同步读一次磁盘缓存（只解一张小 JPEG，很快）：命中则第一帧就是稳定帧 + 真实比例，
    // 不再 1:1 → 真实比例 跳变，也不再联网。未命中才走下面的异步解码。
    val cachedInit = remember(videoUrl) {
        runCatching { com.aurora.chat.data.local.VideoThumbCache.load(ctx, videoUrl) }.getOrNull()
    }
    var thumbnail by remember(videoUrl) { mutableStateOf(cachedInit) }
    var loadFailed by remember(videoUrl) { mutableStateOf(false) }
    var videoAspectRatio by remember(videoUrl) {
        mutableFloatStateOf(
            when {
                cachedInit != null -> cachedInit.width.toFloat() / cachedInit.height.toFloat().coerceAtLeast(1.0f)
                initialAspectRatio > 0f -> initialAspectRatio.coerceIn(0.4f, 2.5f)
                else -> 1f
            }
        )
    }

    LaunchedEffect(videoUrl) {
        if (cachedInit != null) return@LaunchedEffect // 已命中缓存，无需再加载
        withContext(Dispatchers.IO) {
            try {
                val mmr = MediaMetadataRetriever()
                try {
                    // 整片视频已缓存（看过）→ 读本地文件秒开；否则才走网络 URL 取帧
                    val cachedVideo = com.aurora.chat.data.local.MediaCache.getCachedVideoUri(ctx, videoUrl)
                    if (cachedVideo != null) mmr.setDataSource(ctx, cachedVideo)
                    else mmr.setDataSource(videoUrl.removePrefix("file://"))

                    val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    if (w > 0 && h > 0) videoAspectRatio = w.toFloat() / h.toFloat()

                    val bmp = mmr.frameAtTime
                    if (bmp != null) {
                        thumbnail = bmp
                        // 解码成功即落盘：下次进会话/重看直接命中缓存，不再联网取帧
                        com.aurora.chat.data.local.VideoThumbCache.save(ctx, videoUrl, bmp)
                    } else {
                        loadFailed = true
                    }
                } finally {
                    runCatching { mmr.release() }
                }
            } catch (_: Exception) {
                loadFailed = true
            }
        }
    }

    // 尺寸与「上传中」占位共用同一口径（videoDisplaySize），不再用图片那套 150×200：
    // 视频预览取更适中的一档，竖屏也不会顶得过高。引用胶囊可传更小的 max 上限。
    val (dispW, dispH) = videoDisplaySize(videoAspectRatio, maxWidthDp, maxHeightDp)

    Box(
        modifier = modifier
            .width(dispW)
            .height(dispH),
        contentAlignment = Alignment.Center
    ) {
        // ── 画面层：填满整张卡片（Crop），失败/加载中给深色底，避免透明塌陷 ──
        when {
            thumbnail != null && !loadFailed -> Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = "视频封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            loadFailed -> Box(
                modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appTextPrimary()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Aurora Chat",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = Color(0x99FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            else -> Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appTextPrimary()))
        }

        // ── 居中播放键（半透明圆 + Play 图标），换掉原来的 "▶" 文本 ──
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
