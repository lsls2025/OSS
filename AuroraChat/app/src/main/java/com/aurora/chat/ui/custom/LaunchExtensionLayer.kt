package com.aurora.chat.ui.custom

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * 启动画面扩展点（LaunchHost 的运行时）。
 *
 * 由 MainActivity 挂在 setContent 根 Box 的顶层：盖住主界面，但不盖住
 * CaptureBlockOverlay（安全守卫仍在最上层）。
 *
 * 默认不注册任何扩展，因此本层通常直接 return，冷启动不会多出画面。
 * 只有用户在「自定义」里自建并启用扩展后才会真正渲染。
 *
 * 约束（对应设计里的安全边界）：
 * 1. 只在**冷启动**展示；首次引导、外部 intent 唤起（分享/deep link/通知）都不展示；
 * 2. 时长上限 10s，绝不阻塞首帧业务初始化；
 * 3. 元素绑了动作的，点了会真的执行；点空白处优先触发画布整体动作，
 *    没配整体动作且开了「点击跳过」才关闭。
 */
@Composable
fun LaunchExtensionLayer(
    extension: HostExtension?,
    onDismiss: () -> Unit
) {
    val ext = extension ?: return
    val ctx = LocalContext.current
    var visible by remember(ext.id) { mutableStateOf(true) }

    fun dismiss() {
        visible = false
        onDismiss()
    }

    LaunchedEffect(ext.id) {
        delay(ext.durationMs.coerceIn(0L, 10_000L))
        dismiss()
    }

    if (!visible) return

    Box(Modifier.fillMaxSize()) {
        ExtensionCanvas(
            spec = ext.canvas,
            modifier = Modifier.fillMaxSize(),
            onElementAction = { el, action, value ->
                if (action != ElementAction.NONE) {
                    ExtensionActionRunner.run(ctx, action, value)
                } else if (ext.action != ElementAction.NONE) {
                    ExtensionActionRunner.run(ctx, ext.action, ext.actionValue)
                } else if (ext.tapToDismiss) {
                    dismiss()
                }
            },
            onCanvasAction = {
                if (ext.action != ElementAction.NONE) {
                    ExtensionActionRunner.run(ctx, ext.action, ext.actionValue)
                } else if (ext.tapToDismiss) {
                    dismiss()
                }
            }
        )
    }
}
