package com.aurora.chat.ui.kyant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.RoundedRectangle

/**
 * Kyant0 ScrollContainer —— 可竖向滑动的玻璃卡片列表（原样移植）
 *
 * 移植自 Kyant0/AndroidLiquidGlass 的 catalog/destinations/ScrollContainerContent.kt：
 * 每张卡片高160dp、圆角 RoundedRectangle(32dp)，玻璃效果为
 * vibrancy() + lens(16dp, 32.dp)；滑动时实时折射背后的内容。
 * 唯一改动：demo 用壁纸做背景，这里改成渐变色，避免依赖 demo 的图片资源。
 */
@Composable
fun KyantScrollContainer(modifier: Modifier = Modifier) {
    val backdrop = rememberLayerBackdrop()

    Box(modifier.fillMaxSize()) {
        // 背景：作为玻璃卡片的折射源（对应 demo 里的壁纸图）
        Box(
            Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF1E3A8A),
                            Color(0xFF3B82F6),
                            Color(0xFF06B6D4),
                            Color(0xFF1E40AF)
                        )
                    )
                )
        )

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .systemBarsPadding()
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            repeat(20) {
                Box(
                    Modifier
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedRectangle(32.dp) },
                            effects = {
                                vibrancy()
                                lens(16.dp.toPx(), 32.dp.toPx())
                            }
                        )
                        .height(160.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}