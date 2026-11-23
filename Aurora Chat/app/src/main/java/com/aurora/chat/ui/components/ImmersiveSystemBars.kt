package com.aurora.chat.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸式全屏：隐藏系统栏，但**不吃掉侧滑返回**。
 *
 * 图片 / 视频 / 闪照这类全屏预览都用它，别自己再写 hide()。
 *
 * ## 为什么必须是 BEHAVIOR_DEFAULT
 *
 * 之前的写法是 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（等于老的"沉浸式粘性"）。
 * 这个模式下系统把「从屏幕边缘往里滑」优先解释成**先把被隐藏的系统栏临时露出来**，
 * 于是手势导航的返回手势第一次必然被系统吃掉：
 * 用户左滑一下，只看到顶部状态栏冒出来（时间、电量出现），图还在；再滑一下才真正退出。
 *
 * 换成 `BEHAVIOR_DEFAULT` 后，按官方文档：
 * "When the gesture navigation is enabled, the system gestures can be triggered
 * regardless the visibility of system bars." —— 开了手势导航时，系统手势不受系统栏可见性影响，
 * 一次侧滑就直接派发返回事件。微信 / QQ 的全屏看图就是这个手感。
 *
 * ## 代价（已知且可接受）
 *
 * 默认行为下系统栏不是"临时"的：从顶部下拉会露出状态栏并停在屏幕上，不会自动缩回。
 * 这里故意**不做**「定时再隐藏」——那会和用户的下拉手势抢控制权，
 * 出现"拉一半被打断"的怪异感。退出预览时本组件统一 show() 恢复，不会留下隐藏态。
 *
 * @param key 变化时重新隐藏一次（例如切换视频源）；默认 Unit 表示进入时隐藏一次。
 * @param types 要隐藏的系统栏类型，默认状态栏 + 导航栏；只隐藏状态栏可传 [WindowInsetsCompat.Type.statusBars]。
 */
@Composable
fun ImmersiveSystemBars(
    key: Any? = Unit,
    types: Int = WindowInsetsCompat.Type.systemBars()
) {
    val ctx = LocalContext.current
    val window = remember(ctx) { ctx.findHostActivityOrNull()?.window }

    DisposableEffect(window, key, types) {
        // 注意：MainActivity 已 enableEdgeToEdge（decorFitsSystemWindows=false），
        // 这里【绝不能】再改 window 的 decorFits 标志，否则退出预览后主界面顶栏会被状态栏顶上去。
        // 只临时隐藏系统栏，退出时恢复。
        val win = window ?: return@DisposableEffect onDispose { }
        val ctrl = WindowCompat.getInsetsController(win, win.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        ctrl.hide(types)
        onDispose {
            runCatching {
                WindowCompat.getInsetsController(win, win.decorView).show(types)
            }
        }
    }
}

/** 从 Compose 的 Context 往上找宿主 Activity（可能是 ContextWrapper 包装过的）。 */
private tailrec fun Context.findHostActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivityOrNull()
    else -> null
}
