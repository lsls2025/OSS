package com.aurora.chat.ui.chat

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

// ── 闪照查看器（FLAG_SECURE 防截图/录屏，按住查看松手即毁）──
@Composable
fun FlashIcon(iconSize: Dp = 18.dp, color: Color = Color(0xFF9CA3AF)) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val strokeW = 2.5.dp.toPx()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.48f, 0f)
            lineTo(w * 0.20f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.52f)
            lineTo(w * 0.30f, h)
            lineTo(w * 0.72f, h * 0.43f)
            lineTo(w * 0.48f, h * 0.43f)
            close()
        }
        drawPath(path, color = color, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun FlashViewerDialog(
    imageUrl: String, durationSec: Int, onDestroy: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val window = (context as android.app.Activity).window
    var isPressing by remember { mutableStateOf(false) }
    var showImage by remember { mutableStateOf(false) }
    var remainingSec by remember { mutableIntStateOf(durationSec) }

    // 全屏 + 防截图
    DisposableEffect(Unit) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            val ctrl = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            ctrl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    if (isPressing && !showImage) {
        LaunchedEffect(isPressing) {
            delay(300)
            if (isPressing) {
                showImage = true
                remainingSec = durationSec
            }
        }
    }
    if (showImage && remainingSec > 0) {
        LaunchedEffect(remainingSec) {
            delay(1000)
            if (showImage && remainingSec > 0) {
                remainingSec--
            }
        }
    }
    if (showImage && remainingSec <= 0) {
        LaunchedEffect(Unit) {
            onDestroy()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (showImage) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    coil.compose.AsyncImage(model = resolveMediaUrl(imageUrl), contentDescription = "闪照",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x80000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("${remainingSec}s", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FlashIcon(iconSize = 48.dp, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text("此照片为闪照", fontSize = 16.sp, color = Color.White)
                        Text("按住可观看 ${durationSec} 秒", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                    }
                }
            }

            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressing = true
                    down.consume()

                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed && it.id == down.id }
                        if (!anyPressed) {
                            released = true
                        }
                    }

                    isPressing = false
                    if (showImage) {
                        onDestroy()
                        onDismiss()
                    }
                }
            })
        }
    }
}
