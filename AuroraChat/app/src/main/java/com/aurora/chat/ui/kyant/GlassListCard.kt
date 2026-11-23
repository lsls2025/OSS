package com.aurora.chat.ui.kyant

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle

/**
 * 列表卡片玻璃化助手（批次 B：卡片列表玻璃化复用）。
 *
 * - 液态模式：drawBackdrop 折射页面 backdrop（页面用
 *   `rememberCanvasBackdrop { drawRect(Color(0xFFEFF1F5)) }` 提供折射源，与开关/滑块同方案），
 *   玻璃面为半透明白磨砂，圆角=radius。
 * - 经典模式：返回 this（不改调用方已有的 background / clip），保证「经典模式一行不改」。
 *
 * 用法：在列表项原有 `.clip(...).background(...)` 之后追加
 * `Modifier.glassListCard(backdrop, isLiquid, r)`，经典模式下本助手为空操作，视觉完全不变。
 */
fun Modifier.glassListCard(
    backdrop: Backdrop,
    radius: Dp,
    surfaceColor: Color = Color.White.copy(alpha = 0.5f)
): Modifier = if (false) { // 液态模式已移除：恒为空操作
    this
} else {
    this
}
