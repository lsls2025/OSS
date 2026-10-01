package com.aurora.chat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 隐私锁定辅助功能服务 —— 拦截返回键实现"禁止退出"功能。
 * 
 * 当 PrivacyFloatingService.noExit == true 时，拦截系统返回键，
 * 阻止用户退出当前聊天界面。
 * 双方都开启后，必须双方都同意才能解锁退出。
 */
class PrivacyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var isServiceRunning = false
        @Volatile private var serviceInstance: PrivacyAccessibilityService? = null

        /**
         * 无障碍能力是否真的可用（服务已连接）。
         *
         * **只用「服务是否已连接」来判断，不用系统设置里的开关状态。**
         * 开关是「已授权」，「已连接」才是「现在能读屏/手势」。
         */
        fun isRunning(): Boolean = isServiceRunning && serviceInstance != null

        /** 供 PhoneControl 调用（读屏 / 手势 / 截屏） */
        fun instance(): PrivacyAccessibilityService? = serviceInstance

        /**
         * 系统设置里本服务是否处于「已开启」状态。
         *
         * 用途：区分两种「用不了无障碍」的情况——
         * - 系统里根本没开 → 该引导用户去设置页开启
         * - 系统里开着、只是服务还没连上（进程刚重启 / 服务刚被重新绑定）
         *   → **不该跳设置页**，等一会儿就好。此前不加区分一律跳设置页，
         *     用户会觉得「明明我已经开了还跳，莫名其妙」。
         */
        fun isEnabledInSystem(ctx: android.content.Context): Boolean = try {
            val am = ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager
            val list = am?.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ) ?: emptyList()
            val self = ctx.packageName
            list.any { info ->
                val name = try { info.resolveInfo?.serviceInfo?.packageName } catch (_: Exception) { null }
                name == self
            }
        } catch (_: Exception) { false }

        /**
         * 最近一次「窗口内容变化」的时间戳（毫秒）。
         *
         * 用途：点击图标后目标应用冷启动需要几百毫秒到一两秒，期间
         * rootInActiveWindow 仍可能返回上一个界面的节点树，导致 AI 读到旧界面
         * （表现为「一直显示上一轮」）。读屏前据此等待界面稳定即可避免误判。
         */
        @Volatile private var lastWindowChangeAt: Long = 0L

        /** 供无障碍服务回调更新变化时间 */
        internal fun markWindowChanged() {
            lastWindowChangeAt = android.os.SystemClock.uptimeMillis()
        }

        /**
         * 距离上次窗口变化已过去多少毫秒（从未变化则返回一个很大的值，表示「一直很稳定」）。
         */
        fun msSinceLastWindowChange(): Long {
            val t = lastWindowChangeAt
            if (t == 0L) return Long.MAX_VALUE
            return android.os.SystemClock.uptimeMillis() - t
        }

        /** 模拟返回键（用于解锁后恢复退出功能） */
        fun simulateBackPress() {
            try {
                serviceInstance?.performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (_: Exception) {}
        }

        /** 检查无障碍服务是否在运行 */
        fun checkEnabled(): Boolean = isRunning()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        serviceInstance = this
        // 读屏 / 手势 / 截屏能力由 res/xml/accessibility_service_config.xml 声明；
        // 这里只在现有配置上补充事件类型与返回键拦截，避免覆盖掉那些能力。
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            // 注意：这里赋的值会覆盖 XML 中的 accessibilityEventTypes，两处必须保持一致。
            // 监听内容变化是为了让 PhoneControl 能判断「界面是否已切换完成」再读屏。
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            info.notificationTimeout = 100
            // 关键：声明要拦截返回键
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录窗口/内容变化时间：PhoneControl 读屏前据此判断界面是否已切换完成，
        // 避免在目标应用冷启动期间读到上一个界面的节点树（旧数据）。
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> markWindowChanged()
        }
    }

    /**
     * ⚠️ 这里**绝对不能**把 isServiceRunning / serviceInstance 清掉。
     *
     * onInterrupt() 的语义是「系统想打断本服务当前的反馈输出」，**不是服务结束**——
     * 服务依然处于已启用、已连接状态，读屏与手势注入都还能用。部分 ROM 会频繁调用它
     * （手势执行期间、窗口焦点切换时都会触发）。
     *
     * 曾经的写法是 `isServiceRunning = false; serviceInstance = null`，后果很严重：
     * 只要 onInterrupt 被调一次，App 就永久认为「无障碍没开」，于是所有 phone_* 工具
     * 都返回未就绪，并**把用户反复甩到系统无障碍设置页**——而用户明明已经开好了。
     * 真正代表服务结束的是 onDestroy / onUnbind。
     */
    override fun onInterrupt() {
        try {
            ErrorReporter.debug("A11y", "onInterrupt 被调用（服务仍在运行，不做任何状态清除）")
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        isServiceRunning = false
        serviceInstance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        // 拦截返回键：当禁止退出开启时，阻止返回操作
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
            if (PrivacyFloatingService.masterOn && PrivacyFloatingService.noExit) {
                // 显示 Toast 提示
                showToast("隐私锁定中，无法退出")
                return true // 拦截事件
            }
        }
        return false
    }

    private fun showToast(msg: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
