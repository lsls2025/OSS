package com.aurora.chat.ui.kyant

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle

/**
 * 列表卡片玻璃折射源：带色彩的竖向渐变 canvas。
 *
 * 必须用有色彩的图层作为折射源，避免「白上加白」的塑料感（此前踩坑结论）。
 * 仅在液态模式配合 [liquidCard] 使用；经典模式不调用，外观零改动。
 */
@Composable
fun rememberListGlassBackdrop(): Backdrop = rememberCanvasBackdrop {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFFEDF1F7), Color(0xFFE0E7F1))
        )
    )
}

/**
 * 列表卡片统一 Modifier。
 *
 * - 液态模式：[drawBackdrop] 折射 [glassBackdrop]（由调用方用 [rememberListGlassBackdrop] 提供）。
 * - 经典模式：原样 [clip] + [background]，保持与改造前完全一致的外观。
 *
 * 直接替换原 `.clip(RoundedCornerShape(N.dp)).background(c)` 链即可，经典模式无需其他改动。
 */
fun Modifier.liquidCard(
    isLiquid: Boolean,
    glassBackdrop: Backdrop,
    cornerDp: Dp,
    baseColor: Color,
    surfaceAlpha: Float = 0.32f
): Modifier = then(
    if (isLiquid) {
        drawBackdrop(
            backdrop = glassBackdrop,
            shape = { RoundedRectangle(cornerDp) },
            effects = {
                vibrancy()
                lens(16f.dp.toPx(), 32f.dp.toPx())
            },
            onDrawSurface = { drawRect(Color.White.copy(alpha = surfaceAlpha)) }
        )
    } else {
        clip(RoundedCornerShape(cornerDp)).background(baseColor)
    }
)
