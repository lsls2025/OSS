package com.aurora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 通用事件拦截组件 —— **完全阻止触摸事件穿透**。
 *
 * ## 原理
 * 在 `PointerEventPass.Final` 阶段消费未被面板内容处理的事件。
 * 点击面板内部 → 面板先处理 ✓
 * 点击面板外部（遮罩区）→ EventBlocker 消费事件并触发 onClick ✓
 *
 * ## 用法
 * ```kotlin
 * // 遮罩层（黑色半透明 + 事件拦截 + 点击关闭）
 * AnimatedVisibility(visible = showSomething) {
 *     EventBlocker(Color.Black.copy(alpha = 0.4f), onClick = { close() })
 * }
 * // 面板（正常接收事件）
 * AnimatedVisibility(visible = showSomething) {
 *     Box(Modifier.fillMaxSize()) { Content() }
 * }
 * ```
 */
@Composable
fun EventBlocker(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .then(
                remember { Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            // Final 阶段：等子组件处理完，没被消费的才拦截
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            var triggered = false
                            event.changes.forEach { change ->
                                if (!change.isConsumed && change.changedToUp()) {
                                    change.consume()
                                    triggered = true
                                }
                            }
                            if (triggered && onClick != null) {
                                onClick()
                            }
                        }
                    }
                } }
            )
    )
}
