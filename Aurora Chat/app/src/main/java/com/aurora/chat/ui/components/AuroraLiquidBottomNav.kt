package com.aurora.chat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.AuroraPrimary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 液态玻璃底栏 —— 对标 Liquidglass.js 的 bottom-tabs 参数：
 *   TABS_PAD = 36dp, CONTAINER_H = 64dp, GLASS_H = 56dp, GLASS_PAD = 4dp
 *   indicator w = tabW（填满一个 tab 宽度）
 *   indicator 按下 scale = 78/56 ≈ 1.39
 */

@Composable
fun AuroraLiquidBottomNav(
    currentTab: AuroraTab,
    onTabSelected: (AuroraTab) -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    showDivider: Boolean = false,
    badges: Map<AuroraTab, Int> = emptyMap()
) {
    val scope = rememberCoroutineScope()
    val tabCount = AuroraTab.entries.size

    // ── 物理动画状态 ──
    val indicatorIndex = remember { Animatable(currentTab.ordinal.toFloat()) }
    val panelOffset = remember { Animatable(0f) }
    val pressProgress = remember { Animatable(0f) }
    val velocity = remember { Animatable(0f) }

    LaunchedEffect(currentTab) {
        val diff = abs(indicatorIndex.value - currentTab.ordinal)
        if (!indicatorIndex.isRunning && diff > 0.01f) {
            indicatorIndex.animateTo(
                currentTab.ordinal.toFloat(),
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f)
            )
        }
    }

    // ── 精确尺寸（对标 Liquidglass.js）──
    val TABS_PAD = 36.dp
    val CONTAINER_H = 64.dp
    val GLASS_H = 56.dp
    val GLASS_PAD = 4.dp

    // 暗色主题下的玻璃参数（tabsContainer = [18,18,18, 0.4]）
    val containerColor = Color(0xFF121212).copy(alpha = 0.4f)
    val containerBorder = Color.White.copy(alpha = 0.1f)
    val indicatorBorder = Color.White.copy(alpha = 0.25f)
    val shadowColor = Color.Black.copy(alpha = 0.1f)
    val innerShadowColor = Color.Black.copy(alpha = 0.15f)
    val highlightColor = Color.White.copy(alpha = 0.45f)

    // ── 顶层：外框胶囊（64dp 高）──
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = TABS_PAD, vertical = 4.dp)
            .height(CONTAINER_H)
    ) {
        // ── Layer 1: 外框 + Tab Row ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = panelOffset.value }
                .drawBehind {
                    // 外阴影：radius=24dp, offsetY=24/6=4dp, alpha=0.1
                    drawCircle(
                        brush = Brush.radialGradient(
                            center = Offset(size.width / 2f, size.height / 2f + 4.dp.toPx()),
                            radius = size.width / 2f + 24.dp.toPx(),
                            colors = listOf(shadowColor, shadowColor.copy(alpha = 0f))
                        ),
                        radius = size.width / 2f + 24.dp.toPx(),
                        center = Offset(size.width / 2f, size.height / 2f + 4.dp.toPx())
                    )
                    // 外框底色：半透明深色 40%
                    drawRoundRect(
                        color = containerColor,
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
                .border(0.5.dp, containerBorder, RoundedCornerShape(32.dp))
                .clip(RoundedCornerShape(32.dp))
                .padding(GLASS_PAD),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuroraTab.entries.forEachIndexed { index, tab ->
                    val selected = abs(indicatorIndex.value - index) < 0.5f
                    LiquidTabItem(
                        tab = tab,
                        badgeCount = badges[tab] ?: 0,
                        tinted = selected,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onTabSelected(tab) }
                    )
                }
            }
        }

        // ── Layer 2: indicator（完整 tabW × GLASS_H，按下放大 1.39×）──
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val innerWdp = maxWidth - GLASS_PAD * 2
            val tabWdp = innerWdp / tabCount
            val indHdp = GLASS_H
            val scaleOnPress = 78f / 56f  // ~1.39，Liquidglass.js gooseTabScale

            Box(
                modifier = Modifier
                    .offset {
                        with(density) {
                            androidx.compose.ui.unit.IntOffset(
                                x = (indicatorIndex.value * tabWdp.value + GLASS_PAD.value).dp.roundToPx(),
                                y = 0
                            )
                        }
                    }
                    .width(tabWdp)
                    .height(indHdp)
                    .graphicsLayer {
                        translationX = panelOffset.value
                        // 按下时整体放大（围绕自己中心）
                        val pressScale = 1f + (scaleOnPress - 1f) * pressProgress.value
                        // velocity 形变（温和版）
                        val v = velocity.value / 15f
                        val vc = v.coerceIn(-0.12f, 0.12f)
                        scaleX = pressScale * (1f / (1f - vc * 0.5f))
                        scaleY = pressScale * (1f - vc * 0.2f)
                    }
                    .pointerInput(tabCount) {
                        detectDragGestures(
                            onDragStart = {
                                scope.launch {
                                    pressProgress.animateTo(1f,
                                        spring(stiffness = 1000f, dampingRatio = 1f))
                                }
                                scope.launch { velocity.snapTo(0f) }
                            },
                            onDragEnd = {
                                val snapped = max(0f, min((tabCount - 1).toFloat(),
                                    (indicatorIndex.value + 0.5f).toInt().toFloat()))
                                scope.launch {
                                    indicatorIndex.animateTo(snapped,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f))
                                }
                                scope.launch {
                                    panelOffset.animateTo(0f,
                                        spring(dampingRatio = 0.5f, stiffness = 300f))
                                }
                                scope.launch {
                                    pressProgress.animateTo(0f,
                                        spring(stiffness = 1000f, dampingRatio = 1f))
                                }
                                scope.launch { velocity.animateTo(0f, spring(260f, 0.5f)) }
                                val finalIndex = snapped.toInt()
                                if (finalIndex in AuroraTab.entries.indices) {
                                    onTabSelected(AuroraTab.entries[finalIndex])
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    panelOffset.animateTo(0f)
                                    pressProgress.animateTo(0f)
                                    indicatorIndex.animateTo(currentTab.ordinal.toFloat(),
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x
                                val tabWpx = tabWdp.value * density.density
                                val target = indicatorIndex.value + dx / tabWpx
                                val clamped = max(0f, min((tabCount - 1).toFloat(), target))
                                scope.launch { indicatorIndex.snapTo(clamped) }
                                scope.launch { velocity.snapTo(dx) }
                                val maxOffsetPx = 4.dp.toPx()
                                scope.launch {
                                    panelOffset.snapTo(
                                        (panelOffset.value + dx * 0.05f).coerceIn(-maxOffsetPx, maxOffsetPx)
                                    )
                                }
                            }
                        )
                    }
                    .drawBehind {
                        val press = pressProgress.value
                        // 外阴影（按下显示）
                        if (press > 0.01f) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    center = Offset(size.width / 2f, size.height / 2f + 4.dp.toPx()),
                                    radius = size.width / 2f + 24.dp.toPx(),
                                    colors = listOf(
                                        shadowColor.copy(alpha = shadowColor.alpha * press),
                                        shadowColor.copy(alpha = 0f)
                                    )
                                ),
                                radius = size.width / 2f + 24.dp.toPx(),
                                center = Offset(size.width / 2f, size.height / 2f + 4.dp.toPx())
                            )
                        }
                        // indicator 主体：透明玻璃 + 极淡底色（跟 Liquidglass.js 一致）
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.12f * (1 - press) + 0.02f * press),
                                    Color.White.copy(alpha = 0.08f * (1 - press) + 0f * press)
                                )
                            ),
                            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                        )
                        // innerShadow（按下显示）：黑色向内 8dp
                        if (press > 0.01f) {
                            drawRoundRect(
                                brush = Brush.radialGradient(
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    radius = size.width / 2f,
                                    colors = listOf(
                                        Color.Transparent,
                                        innerShadowColor.copy(alpha = innerShadowColor.alpha * press * 0.3f)
                                    ),
                                    tileMode = androidx.compose.ui.graphics.TileMode.Clamp
                                ),
                                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                            )
                        }
                        // 顶部高光（按下增强）
                        drawLine(
                            color = highlightColor.copy(alpha = highlightColor.alpha * (0.3f + 0.7f * press)),
                            start = Offset(10.dp.toPx(), 6.dp.toPx()),
                            end = Offset(size.width - 10.dp.toPx(), 6.dp.toPx()),
                            strokeWidth = 0.5.dp.toPx()
                        )
                    }
                    .border(0.5.dp, indicatorBorder, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
            )
        }
    }
}

// ================================= Tab 项 =================================

@Composable
private fun LiquidTabItem(
    tab: AuroraTab,
    badgeCount: Int,
    tinted: Boolean,
    modifier: Modifier = Modifier
) {
    val contentColor = AuroraPrimary
    val baseColor = Color.White.copy(alpha = 0.55f)

    val scale by animateFloatAsState(
        targetValue = if (tinted) 1.06f else 1f,
        animationSpec = tween(150),
        label = "tabScale"
    )

    Column(
        modifier = modifier.scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(tab.iconRes),
                contentDescription = tab.label,
                tint = if (tinted) contentColor else baseColor,
                modifier = Modifier.size(24.dp)
            )
            if (badgeCount > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = 1.dp)
                ) {
                    UnreadBadge(count = badgeCount, size = 14.dp, fontSize = 9.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = tab.label,
            fontSize = 11.sp,
            fontWeight = if (tinted) FontWeight.SemiBold else FontWeight.Normal,
            color = if (tinted) contentColor else baseColor
        )
    }
}
