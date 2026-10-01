package com.aurora.chat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Aurora 风格的下拉刷新指示器。
 *
 * Material3 PullToRefreshBox indicator（BOM 2025.02.00）签名：
 *   indicator: @Composable BoxScope.() -> Unit
 *
 * 三段式动画：
 *   1) 拖动中      —— （由 Material3 原生默认处理，此处不覆盖）
 *   2) 松手刷新中  —— 蓝色圆环匀速旋转
 *   3) 刷新完成    —— 绿色对勾短暂显示后缩回
 *
 * @param isRefreshing 外层 PullToRefreshBox 的 isRefreshing 状态（闭包捕获传入）
 */
@Composable
fun AuroraPullToRefreshIndicator(
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    circleSize: Dp = 22.dp,
    primaryColor: Color = Color(0xFF2563EB),
    successColor: Color = Color(0xFF10B981),
    trackColor: Color = Color(0xFFE5E7EB),
) {

    var rotation by remember { mutableFloatStateOf(0f) }
    var showSuccess by remember { mutableStateOf(false) }

    // 刷新中驱动旋转
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            showSuccess = false
            // 每 60ms 增量 24° = 1 秒约 4 圈，流畅不拖沓
            while (isRefreshing) {
                rotation = (rotation + 24f) % 360f
                kotlinx.coroutines.delay(60)
            }
        } else if (rotation > 0f) {
            // 刚刚结束刷新：显示成功对勾 500ms
            showSuccess = true
            kotlinx.coroutines.delay(500)
            showSuccess = false
            rotation = 0f
        }
    }

    val animatedRotation by animateFloatAsState(
        targetValue = if (isRefreshing) rotation else 0f,
        animationSpec = tween(durationMillis = 60),
        label = "ptr_rotation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .width(circleSize * 2)
                .height(circleSize * 2)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = min(cx, cy) - 4f
            val stroke = 3.5f

            if (showSuccess) {
                // 成功：实心圆 + 白色对勾
                drawCircle(color = successColor, radius = r, center = Offset(cx, cy))
                // 对勾两段
                val checkStart = Offset(cx - r * 0.5f, cy)
                val checkMid = Offset(cx - r * 0.1f, cy + r * 0.4f)
                val checkEnd = Offset(cx + r * 0.55f, cy - r * 0.35f)
                drawLine(
                    color = Color.White,
                    start = checkStart, end = checkMid,
                    strokeWidth = stroke + 1f, cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = checkMid, end = checkEnd,
                    strokeWidth = stroke + 1f, cap = StrokeCap.Round
                )
            } else if (isRefreshing) {
                // 刷新中：蓝色圆环 + 旋转
                rotate(animatedRotation, pivot = Offset(cx, cy)) {
                    // 轨道
                    drawCircle(
                        color = trackColor, radius = r,
                        center = Offset(cx, cy),
                        style = Stroke(width = stroke)
                    )
                    // 前景弧（约 270°）
                    val sweep = 270f
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            // 松手前的"拖动中"状态由 Material3 原生指示器处理
            // 这里只覆盖刷新中和完成态
        }
    }
}