package com.aurora.chat.ui.custom

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.aurora.chat.AppLockGuard

/**
 * 系统级悬浮球的宿主控制器。
 *
 * 与之前「应用内浮层」的本质区别：浮窗由 [ExtensionFloatingService] 通过
 * WindowManager.TYPE_APPLICATION_OVERLAY 挂在系统窗口上，Service 独立于 Activity 运行，
 * 回到桌面、切到别的应用、锁屏再回来都还在；只有用户主动关闭或系统回收进程才消失。
 *
 * 这里只负责状态与路由（哪个扩展在显示、悬浮球位置、悬浮窗权限、Service 启停），
 * 真正的 View 构建在 [ExtensionFloatingService]。
 */
object FloatingExtensionHost {
    private const val PREF = "aurora_floating_ext"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_X = "ball_x"
    private const val KEY_Y = "ball_y"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 当前正在系统悬浮窗上显示的扩展 id；空串 = 没开。 */
    fun activeId(ctx: Context): String = prefs(ctx).getString(KEY_ACTIVE, "") ?: ""

    fun isActive(ctx: Context, id: String): Boolean = activeId(ctx) == id

    fun hasOverlayPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    /** 跳转系统权限页之前调用，避免本次自发起的界面切换被「后台锁定」误判成用户离开。 */
    fun beforeOverlaySettings() {
        AppLockGuard.markSelfLaunch()
    }

    /** 显示某个悬浮窗扩展（同一时刻只显示一个，新点会顶掉旧的）。 */
    fun show(ctx: Context, ext: HostExtension) {
        if (ExtensionSafety.isBad(ctx, ext.id)) {
            // 用户主动点「显示」就是明确重试，解除这一个扩展的隔离后照常启动
            ExtensionSafety.unmarkBad(ctx, ext.id)
            ExtensionSafety.toast(ctx, "已重新启用该悬浮窗")
        }
        ExtensionSafety.guard(ctx, "开启悬浮窗", Unit, badId = ext.id) {
            prefs(ctx).edit().putString(KEY_ACTIVE, ext.id).apply()
            ExtensionFloatingService.start(ctx, ext.id, expand = true)
        }
    }

    fun hide(ctx: Context) {
        clearActive(ctx)
        ExtensionFloatingService.stop(ctx)
    }

    /** 编辑器保存后刷新正在显示的画面，不用关掉重开。 */
    fun refresh(ctx: Context) {
        val id = activeId(ctx)
        if (id.isNotEmpty()) ExtensionFloatingService.refresh(ctx, id)
    }

    /**
     * 应用冷启动时恢复：Service 还在就直接更新配置；被系统杀掉就重新拉起。
     * 没有权限或没有活动扩展时什么都不做，不会弹出多余界面。
     */
    fun restore(ctx: Context) {
        // 保护模式下不做任何恢复：避免一个坏配置让冷启动反复闪退。
        // 用户可以在「其他 → 自定义」里手动解除。
        if (ExtensionSafety.isSafeMode(ctx)) return
        ExtensionSafety.guard(ctx, "恢复悬浮窗", Unit, critical = true) {
            val id = activeId(ctx)
            when {
                !hasOverlayPermission(ctx) -> Unit
                id.isBlank() -> Unit
                ExtensionSafety.isBad(ctx, id) -> clearActive(ctx)
                !HostExtensionStore.usable(ctx).any {
                    it.id == id && it.kind == ExtensionKind.FLOATING_WINDOW
                } -> clearActive(ctx)
                else -> ExtensionFloatingService.start(ctx, id)
            }
        }
    }

    /** 删除扩展时顺手收掉悬浮球，避免留下一个渲染不出内容的空球。 */
    fun onRemoved(ctx: Context, id: String) {
        if (isActive(ctx, id)) hide(ctx)
    }

    fun clearActive(ctx: Context) {
        prefs(ctx).edit().remove(KEY_ACTIVE).apply()
    }

    fun windowPosition(ctx: Context): Pair<Int, Int> {
        val p = prefs(ctx)
        return p.getInt(KEY_X, -1) to p.getInt(KEY_Y, -1)
    }

    fun saveWindowPosition(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }
}
