package com.pm.manager.ui
import androidx.compose.foundation.layout.*

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pm.manager.core.ToastBus
import com.pm.manager.core.formatDuration
import com.pm.manager.model.FileKind
import com.pm.manager.terminal.SiteFiles
import com.pm.manager.ui.components.ProgressOverlay
import com.pm.manager.ui.components.TopBar
import androidx.compose.material.icons.rounded.*
import com.pm.manager.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MediaPreviewScreen(mode: FileKind, path: List<String>, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val name = path.lastOrNull() ?: "文件"

    var localFile by remember { mutableStateOf<File?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }

    fun load() {
        loading = true
        error = null
        val file = File(context.cacheDir, "media_preview_${path.hashCode()}.tmp")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { SiteFiles.downloadToFile(path, file) }
                file
            }.onSuccess {
                localFile = it
                if (mode == FileKind.IMAGE) {
                    bitmap = BitmapFactory.decodeFile(it.absolutePath)
                }
                loading = false
            }.onFailure { error = it.message ?: "加载失败"; loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = name, subtitle = path.dropLast(1).joinToString("/"), onBack = onClose)
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(color = c.teal, modifier = Modifier.size(36.dp))
                    error != null -> Text(error ?: "加载失败", color = Color.White, fontSize = 14.sp)
                    mode == FileKind.IMAGE && bitmap != null -> Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    mode == FileKind.VIDEO && localFile != null -> AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.fromFile(localFile!!))
                                setOnPreparedListener { start() }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    mode == FileKind.AUDIO && localFile != null -> AudioPlayer(file = localFile!!, c = c)
                    else -> Text("不支持预览该类型", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun AudioPlayer(file: File, c: com.pm.manager.ui.theme.AppColors) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0) }
    var duration by remember { mutableStateOf(0) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(file) {
        val mp = MediaPlayer.create(ctx, Uri.fromFile(file))
        player = mp
        mp?.setOnPreparedListener { duration = mp.duration }
        mp?.setOnCompletionListener { playing = false; pos = 0 }
        onDispose { mp?.release(); player = null }
    }

    Column(Modifier.wrapContentSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Audiotrack, contentDescription = null, tint = c.teal, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text("${formatDuration(pos)} / ${formatDuration(duration)}", color = Color.White, fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                player?.let { mp ->
                    if (mp.isPlaying) { mp.pause(); playing = false; pos = mp.currentPosition }
                    else { mp.start(); playing = true }
                }
            }) {
                Icon(if (playing) androidx.compose.material.icons.Icons.Rounded.Pause else androidx.compose.material.icons.Icons.Rounded.PlayArrow, contentDescription = "播放/暂停", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}
