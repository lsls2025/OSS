package com.aurora.chat.ui.custom
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 自定义颜色精调：一块大的饱和度/明度方格 + 色相条 + 不透明度条 + HEX 输入。
 *
 * 默认色板只放几个常用色，任意颜色都在这里拖出来，不再提供一堆没人用的偏色快捷块。
 * state 用无 key 的 remember 固定实例，避免拖动过程中重组换掉 State 导致手势失灵。
 */
@Composable
fun ColorPrecisionPicker(
    current: Long,
    /** 每次变化都直接写状态，不进撤销栈 */
    onPick: (Long) -> Unit,
    /** 一次手势开始时记一次历史，撤销才能一步回到拖动前 */
    onGestureStart: () -> Unit,
    /** 锁定不透明度：给「必须不透明」的颜色用（如消息气泡底色），此时隐藏不透明度条并强制 alpha=FF */
    lockAlpha: Boolean = false
) {
    val initial = remember { toHsv(current) }
    var hue by remember { mutableStateOf(initial[0]) }
    var sat by remember { mutableStateOf(initial[1]) }
    var value by remember { mutableStateOf(initial[2]) }
    var alpha by remember { mutableStateOf(alphaOf(current)) }
    var hexText by remember { mutableStateOf(argbToHex(current)) }
    // 区分「自己 emit 出去的颜色」和「外部切换的颜色」，避免同步回环打断拖动。
    var lastEmitted by remember { mutableStateOf(current) }

    fun syncFrom(c: Long) {
        val h = toHsv(c)
        hue = h[0]
        sat = h[1]
        value = h[2]
        alpha = if (lockAlpha) 1f else alphaOf(c)
        hexText = argbToHex(if (lockAlpha) (c or 0xFF000000L) else c)
    }

    LaunchedEffect(current) {
        if (current != lastEmitted) syncFrom(current)
    }

    fun emit() {
        val a = if (lockAlpha) 255 else (alpha * 255f).roundToInt().coerceIn(0, 255)
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        val argb = ((a shl 24) or (rgb and 0x00FFFFFF)).toLong() and 0xFFFFFFFFL
        lastEmitted = argb
        hexText = argbToHex(argb)
        onPick(argb)
    }

    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val opaque = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

    Column(Modifier.fillMaxWidth()) {
        // ── 大方格：横向饱和度、纵向明度 ──
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            val w = maxWidth.value
            val h = maxHeight.value
            Box(Modifier.fillMaxSize().background(pure))
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(listOf(Color.White, Color.Transparent))
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            onGestureStart()
                            sat = (p.x / size.width).coerceIn(0f, 1f)
                            value = (1f - p.y / size.height).coerceIn(0f, 1f)
                            emit()
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { p ->
                                onGestureStart()
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = (1f - p.y / size.height).coerceIn(0f, 1f)
                                emit()
                            },
                            onDrag = { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                                emit()
                            }
                        )
                    }
            )
            Box(
                Modifier
                    .offset(
                        x = (w * sat - 8f).coerceAtLeast(-8f).dp,
                        y = (h * (1f - value) - 8f).coerceAtLeast(-8f).dp
                    )
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White, CircleShape)
                    .background(Color.Transparent)
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("色相", fontSize = 11.sp, color = CustomUi.Sub)
        Spacer(Modifier.height(5.dp))
        HueBar(hue = hue, onStart = onGestureStart) { h ->
            hue = h
            emit()
        }

        if (!lockAlpha) {
            Spacer(Modifier.height(10.dp))
            Text("不透明度", fontSize = 11.sp, color = CustomUi.Sub)
            Spacer(Modifier.height(5.dp))
            AlphaBar(opaque = opaque, onStart = onGestureStart) { a ->
                alpha = a
                emit()
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(current))
                    .border(0.5.dp, CustomUi.Border, RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(10.dp))
            CustomField(
                value = hexText,
                onValueChange = { s ->
                    hexText = s
                    parseHex(s)?.let { c ->
                        onGestureStart()
                        syncFrom(c)
                        lastEmitted = c
                        onPick(c)
                    }
                },
                hint = "#AARRGGBB 或 #RRGGBB",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("直接在方格内拖动取色，或输入十六进制", fontSize = 11.sp, color = CustomUi.Hint)
    }
}

@Composable
private fun HueBar(hue: Float, onStart: () -> Unit, onChange: (Float) -> Unit) {
    val colors = (0..6).map { i ->
        Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f)))
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
    ) {
        val w = maxWidth.value
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(colors))
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        onStart()
                        onChange((p.x / size.width * 360f).coerceIn(0f, 360f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            onStart()
                            onChange((p.x / size.width * 360f).coerceIn(0f, 360f))
                        },
                        onDrag = { change, _ ->
                            onChange((change.position.x / size.width * 360f).coerceIn(0f, 360f))
                        }
                    )
                }
        )
        Box(
            Modifier
                .offset(x = (w * hue / 360f - 3f).coerceAtLeast(-3f).dp)
                .width(6.dp)
                .height(18.dp)
                .background(com.aurora.chat.ui.theme.appSurface())
                .border(0.5.dp, com.aurora.chat.ui.theme.appTextHint(), RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun AlphaBar(opaque: Color, onStart: () -> Unit, onChange: (Float) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(com.aurora.chat.ui.theme.appDivider())
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, opaque)))
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        onStart()
                        onChange((p.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { p ->
                            onStart()
                            onChange((p.x / size.width).coerceIn(0f, 1f))
                        },
                        onDrag = { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
                    )
                }
        )
    }
}

private fun toHsv(c: Long): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV((c and 0xFFFFFFFFL).toInt(), out)
    return out
}

private fun alphaOf(c: Long): Float = ((c ushr 24) and 0xFFL) / 255f

private fun argbToHex(c: Long): String =
    "#%08X".format(Locale.getDefault(), c and 0xFFFFFFFFL)

private fun parseHex(s: String): Long? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6 && t.length != 8) return null
    if (t.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    val full = if (t.length == 6) "FF$t" else t
    return runCatching { full.toLong(16) and 0xFFFFFFFFL }.getOrNull()
}
