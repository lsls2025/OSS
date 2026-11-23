package com.aurora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * ## 关键防护：出现后 [ARM_DELAY_MS] 毫秒内不拦截
 * 否则"打开本层的那一次点击"的抬起会被本层当成"点外部"消费掉 → 打开的同时又被它自己关闭。
 * 表现就是：点一下只闪进一点又退回、要点第二下才真正打开。
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
    // 刚出现时先"上膛"，这段时间内不拦截抬起事件（避免把打开它的那一下当成点击外部）。
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(ARM_DELAY_MS)
        armed = true
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(armed) {
                if (!armed) return@pointerInput
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
            }
    )
}

/** 出现后多久才允许拦截抬起（避免"打开它的那一下"立刻把它关掉）。 */
private const val ARM_DELAY_MS = 200L

/**
 * Modifier 版穿透防御：在 Final 阶段消费「子组件未处理」的全部指针事件。
 *
 * 适用于根节点本身就是 Column/Box、不方便再套一层 [EventBlocker] 的全屏浮层：
 * 加上 `Modifier.blockPointerPassThrough()` 后，浮层空白区域的点击/滑动不会
 * 漏到下层的兄弟节点（列表、按钮等）。Final 阶段消费不影响浮层内部控件。
 */
fun Modifier.blockPointerPassThrough(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            event.changes.forEach { change ->
                if (!change.isConsumed) change.consume()
            }
        }
    }
}
