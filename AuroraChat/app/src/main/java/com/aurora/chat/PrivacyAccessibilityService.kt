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
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 隐私锁定辅助功能服务 —— 「禁止退出」的执行端。
 *
 * 职责：
 * 1. **前台回弹**：用户一旦离开 App（HOME / 最近任务 / 上滑 / 切到别的应用），
 *    立刻由 [PrivacyEnforcer] 拽回。事件驱动 + 1 秒轮询兜底。
 * 2. **状态同步**：每 15 秒与服务端核对「双方是否都开了禁止退出」，
 *    任一方关闭即立刻放行。
 *
 * 注意：返回键**不在这里消费**（历史实现里在这 `return true` 会导致 App 内
 * 也无法返回上一级）。App 内的「退出应用」由 MainActivity 的根 BackHandler 拦截。
 */
class PrivacyAccessibilityService : AccessibilityService() {

    /**
     * 「禁止退出」状态同步看门狗。
     *
     * 注意：回弹本身不在这里做（回弹由 [PrivacyEnforcer] 用 250ms 快轮询 +
     * 「离开前台瞬间触发」负责，比 1 秒 tick 快得多）。这里只负责定期与服务端核对
     * 「双方是否都开着」——保证推送丢失时也能及时锁上 / 放行。
     */
    private val watchdog = Handler(Looper.getMainLooper())
    private var watchdogRunning = false
    private var syncTick = 0

    private val watchdogTask = object : Runnable {
        override fun run() {
            if (!watchdogRunning) return
            // 我方已开启、对方还没开 → 正处于「等对方」状态，用密集节奏（3 秒）核对，
            // 让「对方一开就立刻锁上」不必等 15 秒；其余情况 15 秒一次即可。
            val waitingForPartner = PrivacyLockState.myNoExit && !PrivacyLockState.friendNoExit
            val everyTicks = if (waitingForPartner) FAST_SYNC_TICKS else SLOW_SYNC_TICKS
            syncTick++
            if (syncTick >= everyTicks) {
                syncTick = 0
                try {
                    PrivacyLockState.refresh()
                } catch (_: Throwable) {}
            }
            // 屏幕固定弹窗：窗口事件万一没派发，用 tick 再兜几次（仅在那 6 秒窗口内）
            if (PrivacyLockState.locked && PrivacyEnforcer.withinLockTaskRequestWindow()) {
                try {
                    autoConfirmPinDialog()
                } catch (_: Throwable) {}
            }
            watchdog.postDelayed(this, TICK_MS)
        }
    }

    companion object {
        /** 同步看门狗的基础 tick */
        private const val TICK_MS = 1000L
        /** 「等对方开启」时的核对间隔（3 秒） */
        private const val FAST_SYNC_TICKS = 3
        /** 平时核对间隔（15 秒） */
        private const val SLOW_SYNC_TICKS = 15

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
            // 保留按键过滤声明（历史配置）。返回键已不在这里消费，见 onKeyEvent 说明。
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
        } catch (_: Exception) {
        }

        // ── 启动「禁止退出」执行与同步 ──
        // 注意：这里**不主动恢复/武装锁定状态**（该功能默认关闭，冷启动即复位），
        // 只在状态被用户开启后才真正生效。
        // 回弹：250ms 快轮询 + 离开前台的瞬间触发
        try {
            PrivacyEnforcer.start(this)
        } catch (_: Throwable) {}
        // 状态同步：与服务端核对双方开关
        watchdogRunning = true
        watchdog.removeCallbacks(watchdogTask)
        watchdog.postDelayed(watchdogTask, TICK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 记录窗口/内容变化时间：PhoneControl 读屏前据此判断界面是否已切换完成，
        // 避免在目标应用冷启动期间读到上一个界面的节点树（旧数据）。
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> markWindowChanged()
        }
        // 前台窗口切到了别的应用（HOME / 最近任务 / 上滑 / 切应用）→ 立刻拉回。
        // 只在 App 已不在前台时才动，避免把应用内弹起的系统对话框也一起拽回。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                PrivacyEnforcer.onForeignWindow(this, event.packageName?.toString())
            } catch (_: Throwable) {}
        }
        // 屏幕固定的系统说明弹窗（按钮"知道了"）→ 自动点掉，不让用户被这一步拦住。
        // 安全边界：① 必须处于锁定状态；② 必须是刚请求过进入固定后的 6 秒窗口内；
        // ③ 点到的东西必须通过文案校验（见 autoConfirmPinDialog）。三条同时满足才动手。
        val pkgName = event?.packageName?.toString()
        if (PrivacyLockState.locked &&
            !pkgName.isNullOrEmpty() &&
            pkgName != packageName &&
            PrivacyEnforcer.withinLockTaskRequestWindow()
        ) {
            pinDialogHandler.removeCallbacks(pinDialogTask)
            pinDialogHandler.postDelayed(pinDialogTask, 200L)
            pinDialogHandler.postDelayed(pinDialogTask, 700L)
        }
    }

    // ==================== 屏幕固定系统弹窗的自动确认 ====================

    private val pinDialogHandler = Handler(Looper.getMainLooper())
    private val pinDialogTask = Runnable { autoConfirmPinDialog() }

    /** 视为"确认/知道了"的按钮文案 */
    private val pinConfirmTexts = setOf(
        "知道了", "我知道了", "知道了！", "好的", "好", "确定",
        "got it", "ok, got it", "i got it", "ok", "got it!"
    )

    /** 只有弹窗内容确实和"屏幕固定"有关时才动手，避免误点其它系统弹窗 */
    private val pinHintKeywords = listOf("固定", "pin", "unpin", "解除固定", "最近任务", "overview")

    /**
     * 自动点击屏幕固定的系统说明弹窗。
     *
     * 三重保险：
     * 1. 只在锁定生效时；
     * 2. 只在「刚请求进入固定」的 6 秒窗口内；
     * 3. 弹窗文案必须包含"固定 / pin"等关键词，按钮文案必须在白名单里。
     * 任何一条不满足就什么都不做。
     */
    private fun autoConfirmPinDialog() {
        if (!PrivacyLockState.locked) return
        if (!PrivacyEnforcer.withinLockTaskRequestWindow()) return
        try {
            // 系统弹窗往往是独立窗口，不一定是"活动窗口"，所以逐个窗口找
            val roots = ArrayList<AccessibilityNodeInfo>(4)
            try {
                for (w in windows) {
                    val r = w?.root ?: continue
                    roots.add(r)
                }
            } catch (_: Throwable) {
            }
            try {
                rootInActiveWindow?.let { active ->
                    if (roots.none { it === active }) roots.add(active)
                }
            } catch (_: Throwable) {
            }
            for (root in roots) {
                if (tryConfirmOnRoot(root)) return
            }
        } catch (_: Throwable) {
        }
    }

    /** 在某个窗口的节点树里尝试点击"确认/知道了"。点到返回 true。 */
    private fun tryConfirmOnRoot(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>(64)
        collectNodes(root, nodes, 0)
        val allText = nodes.joinToString(" ") {
            it.text?.toString() ?: it.contentDescription?.toString() ?: ""
        }
        // 必须确认这个窗口确实和"屏幕固定"有关，否则不动手
        if (pinHintKeywords.none { allText.contains(it, ignoreCase = true) }) return false
        val target = nodes.firstOrNull { n ->
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            pinConfirmTexts.any { it.equals(t, ignoreCase = true) }
        } ?: return false
        // 自身不可点就往上找最近的可点祖先
        var clickable: AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        return try {
            (clickable ?: target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Throwable) {
            false
        }
    }

    /** 递归收集节点（限深限量，避免窗口巨大时卡顿） */
    private fun collectNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 30 || out.size >= 400) return
        out.add(node)
        val n = node.childCount
        for (i in 0 until n) {
            collectNodes(node.getChild(i), out, depth + 1)
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
        watchdogRunning = false
        watchdog.removeCallbacks(watchdogTask)
        try {
            PrivacyEnforcer.stop()
        } catch (_: Throwable) {}
        isServiceRunning = false
        serviceInstance = null
        super.onDestroy()
    }

    /**
     * ⚠️ 这里**故意不消费返回键**（永远返回 false）。
     *
     * 历史实现在这里 `return true`，有两个致命问题：
     * 1. 无障碍服务的按键过滤是「全局生效」的：一旦消费，App 内部也无法返回上一级，
     *    用户被卡死在当前页面（连会话都退不出去）；
     * 2. 它读的是 `PrivacyFloatingService.masterOn / noExit`——这两个字段只有弹悬浮球
     *    的面板才写入，正常从会话内隐私面板开启时恒为 false，所以「禁止退出」实际从未生效。
     *
     * 现在改为两条更可靠、且不破坏 App 内导航的路径：
     * - App 内根页面的返回键：由 MainActivity 的根 BackHandler 拦截（仅拦「退出 App」）；
     * - 用户以任何其它方式离开 App：由 [PrivacyEnforcer] 的前台回弹拉回。
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
