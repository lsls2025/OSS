package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

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
import coil.imageLoader

// ── 闪照查看器（FLAG_SECURE 防截图/录屏，按住查看松手即毁）──
@Composable
fun FlashIcon(iconSize: Dp = 18.dp, color: Color = com.aurora.chat.ui.theme.appTextHint()) {
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
    imageUrl: String,
    durationSec: Int,
    onViewStart: () -> Unit = {},   // 真正开始查看（按住 300ms 露出图片）时回调，用于上报"对方查看中"
    onDestroy: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val window = (context as android.app.Activity).window
    val resolvedUrl = resolveMediaUrl(imageUrl)
    var isPressing by remember { mutableStateOf(false) }
    var showImage by remember { mutableStateOf(false) }
    var remainingSec by remember { mutableIntStateOf(durationSec) }

    // 彻底销毁闪照的 Coil 缓存（内存 + 磁盘）
    fun purgeCoilCache() {
        try {
            val loader = context.imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        } catch (_: Exception) {}
    }

    // 防截图（系统栏的隐藏/恢复交给 ImmersiveSystemBars 统一处理）
    DisposableEffect(Unit) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // 全屏沉浸：BEHAVIOR_DEFAULT，侧滑返回一次就生效（详见 ImmersiveSystemBars 注释）
    com.aurora.chat.ui.components.ImmersiveSystemBars()

    if (isPressing && !showImage) {
        LaunchedEffect(isPressing) {
            delay(300)
            if (isPressing) {
                showImage = true
                remainingSec = durationSec
                onViewStart()
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
            purgeCoilCache()
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
                        Text("按住可观看 ${durationSec} 秒", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
                        purgeCoilCache()
                        onDestroy()
                        onDismiss()
                    }
                }
            })
        }
    }
}
