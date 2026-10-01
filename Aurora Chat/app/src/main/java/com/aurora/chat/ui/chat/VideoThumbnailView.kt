package com.aurora.chat.ui.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── VideoThumbnailView ──
@Composable
fun VideoThumbnailView(videoUrl: String, modifier: Modifier) {
    var thumbnail by remember(videoUrl) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(videoUrl) { mutableStateOf(false) }
    var videoAspectRatio by remember(videoUrl) { mutableFloatStateOf(1f) }

    LaunchedEffect(videoUrl) {
        withContext(Dispatchers.IO) {
            try {
                val mmr = MediaMetadataRetriever()
                // 本地文件（file:// 或纯路径）直接用 setDataSource(String)
                val localPath = videoUrl.removePrefix("file://")
                mmr.setDataSource(localPath)

                val wStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val hStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val w = wStr?.toIntOrNull() ?: 0
                val h = hStr?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) videoAspectRatio = w.toFloat() / h.toFloat()

                val bmp = mmr.frameAtTime
                mmr.release()
                if (bmp != null) {
                    thumbnail = bmp
                } else {
                    loadFailed = true
                }
            } catch (_: Exception) {
                loadFailed = true
            }
        }
    }

    // 注：视频缩略图已通过 MediaMetadataRetriever 自上而下获取，不再单独预加载
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null && !loadFailed) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = "视频封面",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                contentScale = ContentScale.FillWidth
            )
        } else if (loadFailed) {
            Text(
                "Aurora Chat",
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0x99FFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 280.dp)
                    .aspectRatio(videoAspectRatio)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xCCFFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", fontSize = 24.sp, color = Color(0xFF1F2937))
        }
    }
}
