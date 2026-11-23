package com.aurora.chat.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 会话页液态气泡的折射源。
 *
 * 由 [ChatGlassBackdropSource] 提供；不在会话页时为 null（气泡回落到合成源）。
 */
val LocalChatGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * 只画背景、不参与尺寸谈判的「折射底板」。
 *
 * 结构（关键，防止闪退）：
 * ```
 * Box(fillMaxSize)              <- 消息区
 *  ├ Box(backgroundModifier)    <- 只画聊天背景，录制进 backdrop 图层
 *  └ content                    <- 消息列表 / 气泡（气泡 drawBackdrop 折射上面那层）
 * ```
 * 底板里**只有背景绘制**，不含任何气泡；气泡只"读"图层、不"写"图层，
 * 因此不可能出现「图层内容包含读取者本身」的 Skia 显示列表递归。
 *
 * @param backgroundModifier 底板自身的绘制修饰（通常是 fillMaxSize + 背景）。
 */
@Composable
fun ChatGlassBackdropSource(
    backgroundModifier: Modifier,
    content: @Composable () -> Unit
) {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(backgroundModifier.layerBackdrop(backdrop))
        CompositionLocalProvider(LocalChatGlassBackdrop provides backdrop) {
            content()
        }
    }
}
