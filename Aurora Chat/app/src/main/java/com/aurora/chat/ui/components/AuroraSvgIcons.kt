package com.aurora.chat.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 使用 Aurora 原版 SVG path 数据逐段翻译
 */
object AuroraSvgIcons {

    @Composable
    fun Chat(iconSize: Dp = 26.dp, color: Color) {
        Canvas(modifier = Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val st = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val path = Path().apply {
                // 从 M21 15 开始
                moveTo(s * 21f / 24f, s * 15f / 24f)
                // a2 2 0 0 1-2 2 → 到 (19, 17)
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 17f / 24f, s * 15f / 24f, s * 21f / 24f, s * 19f / 24f),
                    startAngleDegrees = 0f, sweepAngleDegrees = -90f, forceMoveTo = false)
                // H7
                lineTo(s * 7f / 24f, s * 17f / 24f)
                // l-4 4
                lineTo(s * 3f / 24f, s * 21f / 24f)
                // V5
                lineTo(s * 3f / 24f, s * 5f / 24f)
                // a2 2 0 0 1 2-2
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 3f / 24f, s * 3f / 24f, s * 7f / 24f, s * 7f / 24f),
                    startAngleDegrees = 180f, sweepAngleDegrees = -90f, forceMoveTo = false)
                // h14
                lineTo(s * 19f / 24f, s * 3f / 24f)
                // a2 2 0 0 1 2 2
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 19f / 24f, s * 3f / 24f, s * 23f / 24f, s * 7f / 24f),
                    startAngleDegrees = 270f, sweepAngleDegrees = -90f, forceMoveTo = false)
                close()
            }
            drawPath(path, color, style = st)
        }
    }

    @Composable
    fun Server(iconSize: Dp = 26.dp, color: Color) {
        Canvas(modifier = Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val st = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(color, Offset(s * 2f / 24f, s * 3f / 24f), Size(s * 20f / 24f, s * 14f / 24f), CornerRadius(s * 2f / 24f), style = st)
            drawLine(color, Offset(s * 8f / 24f, s * 21f / 24f), Offset(s * 16f / 24f, s * 21f / 24f), strokeWidth = 3f)
            drawLine(color, Offset(s * 12f / 24f, s * 17f / 24f), Offset(s * 12f / 24f, s * 21f / 24f), strokeWidth = 3f)
        }
    }

    @Composable
    fun Community(iconSize: Dp = 26.dp, color: Color) {
        Canvas(modifier = Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val st = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val cx = s * 12f / 24f; val cy = s * 12f / 24f; val r = s * 10f / 24f
            drawCircle(color, r, Offset(cx, cy), style = st)
            val vPath = Path().apply {
                moveTo(cx, cy - r)
                cubicTo(cx + r * 0.65f, cy - r * 0.35f, cx + r * 0.65f, cy + r * 0.35f, cx, cy + r)
            }
            drawPath(vPath, color, style = st)
            drawLine(color, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 3f)
        }
    }

    @Composable
    fun Tools(iconSize: Dp = 26.dp, color: Color) {
        Canvas(modifier = Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val st = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val path = Path().apply {
                moveTo(s * 14.7f / 24f, s * 6.3f / 24f)
                // a1 1 0 0 0 0 1.4 → 小圆弧
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 13.7f / 24f, s * 5.3f / 24f, s * 15.7f / 24f, s * 7.3f / 24f),
                    startAngleDegrees = 180f, sweepAngleDegrees = -90f, forceMoveTo = false)
                // l1.6 1.6
                lineTo(s * 16.3f / 24f, s * 7.9f / 24f)
                // a1 1 0 0 0 1.4 0
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 15.3f / 24f, s * 6.9f / 24f, s * 17.3f / 24f, s * 8.9f / 24f),
                    startAngleDegrees = 0f, sweepAngleDegrees = -90f, forceMoveTo = false)
                // l3.77-3.77
                lineTo(s * 20.07f / 24f, s * 4.13f / 24f)
                // a6 6 0 0 1-7.94 7.94
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 14.07f / 24f, s * -1.87f / 24f, s * 26.07f / 24f, s * 10.13f / 24f),
                    startAngleDegrees = 90f, sweepAngleDegrees = -90f, forceMoveTo = false)
                // l-6.91 6.91
                lineTo(s * 13.16f / 24f, s * 11.04f / 24f)
                // a2.12 2.12 0 0 1-3-3
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 10.16f / 24f, s * 8.04f / 24f, s * 14.4f / 24f, s * 12.28f / 24f),
                    startAngleDegrees = 180f, sweepAngleDegrees = -90f, forceMoveTo = false)
                // l6.91-6.91
                lineTo(s * 6.25f / 24f, s * 4.13f / 24f)
                // a6 6 0 0 1 7.94-7.94
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 0.25f / 24f, s * -1.87f / 24f, s * 12.25f / 24f, s * 10.13f / 24f),
                    startAngleDegrees = 90f, sweepAngleDegrees = -90f, forceMoveTo = false)
                // l-3.76 3.76
                lineTo(s * 10.49f / 24f, s * 7.89f / 24f)
                close()
            }
            drawPath(path, color, style = st)
        }
    }

    @Composable
    fun Profile(iconSize: Dp = 26.dp, color: Color) {
        Canvas(modifier = Modifier.size(iconSize)) {
            val s = this.size.minDimension
            val st = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val cx = s * 12f / 24f
            drawCircle(color, s * 4f / 24f, Offset(cx, s * 7f / 24f), style = st)
            val body = Path().apply {
                moveTo(s * 20f / 24f, s * 21f / 24f)
                lineTo(s * 20f / 24f, s * 19f / 24f)
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 16f / 24f, s * 15f / 24f, s * 24f / 24f, s * 23f / 24f),
                    startAngleDegrees = 0f, sweepAngleDegrees = -90f, forceMoveTo = false)
                lineTo(s * 8f / 24f, s * 15f / 24f)
                arcTo(rect = androidx.compose.ui.geometry.Rect(s * 0f / 24f, s * 15f / 24f, s * 8f / 24f, s * 23f / 24f),
                    startAngleDegrees = 90f, sweepAngleDegrees = -90f, forceMoveTo = false)
                lineTo(s * 4f / 24f, s * 21f / 24f)
                close()
            }
            drawPath(body, color, style = st)
        }
    }
}
