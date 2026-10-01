package com.aurora.chat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun AboutUsIcon(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize().padding(7.dp)) {
        val s = size.minDimension
        val st = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx = s / 2; val r = s * 0.44f
        // 圆
        drawCircle(color, r, center = Offset(cx, cx), style = st)
        // i 的点
        drawCircle(color, s * 0.06f, center = Offset(cx, cx - r * 0.35f))
        // i 的竖线
        drawLine(color, Offset(cx, cx - r * 0.15f), Offset(cx, cx + r * 0.35f), strokeWidth = 3f)
    }
}

@Composable
fun ClearCacheTrashIcon(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize().padding(7.dp)) {
        val s = size.minDimension
        val st = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = s * 0.2f
        // 垃圾桶主体
        val path = Path().apply {
            moveTo(p + s * 0.05f, p + s * 0.05f)
            lineTo(s - p - s * 0.05f, p + s * 0.05f)
            lineTo(s - p - s * 0.12f, s - p)
            lineTo(p + s * 0.12f, s - p)
            close()
        }
        drawPath(path, color, style = st)
        // 盖子
        drawLine(color, Offset(p, p), Offset(s - p, p), strokeWidth = 3f)
        drawLine(color, Offset(p + s * 0.15f, p), Offset(p + s * 0.15f, p - s * 0.1f), strokeWidth = 3f)
        drawLine(color, Offset(s - p - s * 0.15f, p), Offset(s - p - s * 0.15f, p - s * 0.1f), strokeWidth = 3f)
    }
}

@Composable
fun SettingsGearIcon(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize().padding(7.dp)) {
        val s = size.minDimension
        val st = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val cx = s / 2; val cy = s / 2; val ri = s * 0.12f; val ro = s * 0.35f
        // 内圈
        drawCircle(color, ri, center = Offset(cx, cy), style = st)
        // 四个齿
        for (angle in listOf(45f, 135f, 225f, 315f)) {
            val rad = Math.toRadians(angle.toDouble()).toFloat()
            val dx = kotlin.math.cos(rad) * (ri + ro) / 2f
            val dy = kotlin.math.sin(rad) * (ri + ro) / 2f
            drawLine(color, Offset(cx + dx - ri * 0.3f * kotlin.math.cos(rad), cy + dy - ri * 0.3f * kotlin.math.sin(rad)),
                Offset(cx + dx + ri * 0.3f * kotlin.math.cos(rad), cy + dy + ri * 0.3f * kotlin.math.sin(rad)),
                strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
}
