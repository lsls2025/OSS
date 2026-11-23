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
    Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
        val s = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rRoot = s * 0.31f   // 齿根
        val rTip = s * 0.45f    // 齿顶
        val rHole = s * 0.15f   // 中心轴孔
        val teeth = 8
        val step = (2f * Math.PI / teeth).toFloat()
        val gear = Path().apply {
            var first = true
            for (i in 0 until teeth) {
                val a = -Math.PI.toFloat() / 2f + i * step
                // 每个齿：齿根 → 齿顶左 → 齿顶右 → 齿根（梯形齿，齿间留齿槽）
                val points = listOf(
                    (a - step * 0.34f) to rRoot,
                    (a - step * 0.18f) to rTip,
                    (a + step * 0.18f) to rTip,
                    (a + step * 0.34f) to rRoot
                )
                for ((ang, r) in points) {
                    val x = cx + kotlin.math.cos(ang) * r
                    val y = cy + kotlin.math.sin(ang) * r
                    if (first) { moveTo(x, y); first = false } else lineTo(x, y)
                }
            }
            close()
            // 偶奇填充：中心圆把齿轮挖出轴孔，得到真正的齿轮轮廓
            addOval(androidx.compose.ui.geometry.Rect(cx - rHole, cy - rHole, cx + rHole, cy + rHole))
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        }
        drawPath(gear, color)
    }
}
