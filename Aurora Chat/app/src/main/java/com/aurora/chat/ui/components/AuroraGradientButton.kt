package com.aurora.chat.ui.components

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 蓝粉流动渐变按钮（与「开启新对话」「AI 设置」「AI 沙盒」完全一致的效果）。
 */
@Composable
fun AuroraGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val floatTrans = rememberInfiniteTransition()
    val floatT by floatTrans.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
    val floatColors = listOf(
        0xFF38BDF8.toInt(), 0xFFEC4899.toInt(),
        0xFFF9A8D4.toInt(), 0xFF7DD3FC.toInt(), 0xFF38BDF8.toInt()
    )
    fun lerpFloatColor(tt: Float): Int {
        val seg = (floatColors.size - 1) * tt
        val i = seg.toInt().coerceIn(0, floatColors.size - 2)
        val f = seg - i
        val c1 = floatColors[i]; val c2 = floatColors[i + 1]
        val r = ((c1 shr 16 and 0xFF) * (1 - f) + (c2 shr 16 and 0xFF) * f).toInt()
        val g = ((c1 shr 8 and 0xFF) * (1 - f) + (c2 shr 8 and 0xFF) * f).toInt()
        val b = ((c1 and 0xFF) * (1 - f) + (c2 and 0xFF) * f).toInt()
        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }
    val g1 = Color(lerpFloatColor(floatT))
    val g2 = Color(lerpFloatColor((floatT + 0.5f) % 1f))
    Box(
        modifier = modifier
            .background(Brush.linearGradient(colors = listOf(g1, g2)), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
