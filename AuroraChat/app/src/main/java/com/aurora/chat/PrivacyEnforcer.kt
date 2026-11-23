package com.aurora.chat

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * 「禁止退出」的执行器：用户一旦离开 App，几乎实时地把他拽回来。
 *
 * ## 判定依据：**是否 resumed**，而不是是否 started
 *
 * 这是关键。上滑回桌面 / 上滑进多任务视图时，我们的 Activity 往往只是 `onPause`
 * （尤其多任务视图，窗口可能一直没走到 `onStop`），如果按"有没有 started 的 Activity"
 * 判断，就会认为"还在前台"，于是什么都不做 —— 表现就是「上滑根本不拦」。
 * 改用「有没有 resumed 的 Activity」，上述情况立刻会被判定为"已离开"。
 *
 * ## 四条互补的路径（按响应速度排序）
 * 1. **用户主动离开的瞬间**：`Activity.onUserLeaveHint()` → [onUserLeaveHint]，
 *    在按 HOME / 最近任务 / 上滑回桌面时**同步**拉回，此时桌面往往还没画出来。
 * 2. **暂停即触发**：[AppVisibility] 在 Activity 暂停/全部 stop 时调 [nudge]，0 延迟判断。
 * 3. **16ms 快轮询**：跑在**独立后台线程**上。因为判定条件是"未 resumed"，只要没被拉回来
 *    就会持续重试 —— 相当于一个自校正的循环，直到真的把 Activity 拉回前台为止。
 * 4. **无障碍事件**：前台窗口一变（HOME / 最近任务 / 切应用）也会立刻拉回。
 *
 * ## 为什么不影响性能 / 不费电
 * - 轮询只做一次 `@Volatile` 布尔读取，不触碰 UI、不建对象；
 * - 跑在独立 `HandlerThread`，不占主线程，界面滚动/动画完全不受影响；
 * - **未锁定时退避到 500ms 一次**，几乎零开销；
 * - `Handler.postDelayed` 基于 uptime，**不持有 wakelock**：息屏后到期消息不会唤醒 CPU。
 * - 连续拉回失败超过 10 次会自动降频到 1 秒一次，避免被系统判定为异常后台启动。
 *
 * ⚠️ 做不到的部分（普通 App 的能力边界）：
 * - HOME / 最近任务键本身**拦不到**（无障碍服务收不到这两个键），只能事后补偿；
 * - 拉回是 `startActivity`，抢不过系统切桌面的那套动作，仍可能有极短的桌面/多任务闪现；
 * - 真正锁死需要 Device Owner（设备管理员模式）；
 * - 系统设置里关闭无障碍 / 强行停止应用，无法阻止。
 */
object PrivacyEnforcer {

    /** 锁定时的快轮询间隔（一帧） */
    private const val FAST_POLL_MS = 16L

    /** 未锁定时的退避间隔（几乎零开销） */
    private const val IDLE_POLL_MS = 500L

    /** 两次拉回的最小间隔：约 8 次/秒，既够快又不会和系统疯狂打架 */
    private const val MIN_PULL_INTERVAL_MS = 120L

    /** 连续拉回失败超过这个次数后降到慢速重试，避免被系统当成异常后台启动 */
    private const val MAX_FAST_RETRY = 10

    /** 降频后的重试间隔 */
    private const val SLOW_RETRY_INTERVAL_MS = 1000L

    @Volatile
    private var appCtx: Context? = null

    /**
     * 桌面（HOME）应用的包名集合。
     *
     * 手势导航下，「上滑回桌面」和「上滑进多任务视图」的前台窗口都属于桌面应用 ——
     * 只要看到前台窗口变成了它，说明用户已经离开，不必等我们的生命周期回调
     * （多任务视图有时只走 onPause，甚至我们的回调还没到），直接拉回。
     */
    @Volatile
    private var homePackages: Set<String> = emptySet()

    /** 只在主线程读写 */
    private var running = false

    /** 后台轮询线程（懒创建，随执行器起停） */
    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var lastPullAt = 0L

    /** 连续拉回次数（成功回到前台后清零）。只在后台线程读写 */
    private var consecutivePulls = 0

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            val locked = PrivacyLockState.locked
            if (locked) checkNow()
            // 锁定时 16ms 一跳（一帧，几乎实时）；未锁定时退避，避免无谓唤醒
            handler?.postDelayed(this, if (locked) FAST_POLL_MS else IDLE_POLL_MS)
        }
    }

    private val nudgeTask = Runnable { checkNow() }

    /** 无障碍服务连上时启动（只在这里起，避免重复起线程） */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (homePackages.isEmpty()) homePackages = resolveHomePackages(ctx)
        if (running) return
        running = true
        val t = HandlerThread("privacy-enforcer").also { it.start() }
        worker = t
        handler = Handler(t.looper).also { h ->
            h.removeCallbacks(pollTask)
            h.post(pollTask)
        }
    }

    /** 无障碍服务断开时停止，并回收后台线程 */
    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        worker?.quitSafely()
        worker = null
    }

    /**
     * 「用户主动离开 App」的即时拉回 —— 由 `Activity.onUserLeaveHint()` 调用。
     *
     * 这是**最早**的可靠信号：按 HOME / 最近任务 / 上滑回桌面时会立刻触发，此时桌面
     * 往往还没画出来。而 `onStop` 要等桌面已经起来之后才回调。
     * 它只在用户主动离开时触发，应用内拉起相机 / 选文件 / 权限弹窗不会误触发。
     */
    fun onUserLeaveHint(ctx: Context) {
        if (!PrivacyLockState.locked) return
        pullBack(ctx)
    }

    /**
     * 「App 刚暂停 / 刚离开前台」的即时触发（由 [AppVisibility] 调用）。
     *
     * 0 延迟，立即判断并拉回。
     */
    fun nudge() {
        if (!running) return
        val h = handler ?: return
        h.removeCallbacks(nudgeTask)
        h.post(nudgeTask)
    }

    /** 已经回到前台（有 Activity 处于 resumed）→ 清掉连续失败计数，并确保屏幕固定仍生效 */
    fun onReturnedToForeground() {
        consecutivePulls = 0
        syncLockTask()
    }

    // ==================== 屏幕固定（LockTask）====================

    /** 屏幕固定的主线程 Handler（startLockTask / stopLockTask 必须在主线程调用） */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lockTaskActivity: WeakReference<Activity>? = null

    /** 最近一次请求进入屏幕固定的时刻（uptime）。仅用于圈定"自动确认系统弹窗"的时间窗 */
    @Volatile
    private var lockTaskRequestedAt = 0L

    /**
     * 是否处于「刚请求进入屏幕固定」的时间窗内（6 秒）。
     *
     * 系统在应用主动进入固定模式时会弹一次说明弹窗（按钮为"知道了"），
     * 无障碍服务只在这个时间窗内自动点掉它 —— 不会在其它任何时候乱点系统弹窗。
     */
    fun withinLockTaskRequestWindow(): Boolean {
        val t = lockTaskRequestedAt
        if (t == 0L) return false
        return SystemClock.uptimeMillis() - t < 6000L
    }

    /**
     * 按当前锁定状态同步「屏幕固定」。
     *
     * 屏幕固定是 Android 上**唯一**能真正拦住上滑手势（HOME / 最近任务）的手段：
     * 普通应用无法消费系统手势，手势一做出，系统就已经开始切桌面/多任务了，
     * 事后拉回必然会看到闪现。进入固定模式后，上滑不再是"进多任务"，而变成
     * "解除固定并回到桌面"，且会先提示需要长按——因此多任务视图根本不会出现。
     *
     * 说明与代价（产品侧已确认）：
     * - 固定模式下通知栏不能下拉、HOME/最近任务被禁用；
     * - 用户仍可用系统的"解除固定"手势（长按返回+最近任务 / 上滑并长按）退出，
     *   这是平台保留的兜底，普通应用无法移除（只有 Device Owner 才能做成真 kiosk）；
     * - 退出后我们的回弹会立刻把人拉回来，并重新进入固定模式。
     */
    fun syncLockTask() {
        mainHandler.post {
            val ctx = appCtx ?: return@post
            val act = AppVisibility.currentActivity?.get()
            val locked = PrivacyLockState.locked
            if (locked) {
                if (act != null && !act.isFinishing && !act.isDestroyed && !isInLockTask(ctx)) {
                    try {
                        // 记下时刻：无障碍服务只会在这之后 6 秒内自动点掉系统的"固定"说明弹窗
                        lockTaskRequestedAt = SystemClock.uptimeMillis()
                        act.startLockTask()
                        lockTaskActivity = WeakReference(act)
                    } catch (_: Throwable) {
                        // 少数 ROM 会拒绝应用主动进入固定模式；忽略即可，回弹仍然有效
                    }
                }
            } else if (isInLockTask(ctx)) {
                try {
                    (lockTaskActivity?.get() ?: act)?.stopLockTask()
                } catch (_: Throwable) {
                }
                lockTaskActivity = null
            }
        }
    }

    /** 当前是否处于屏幕固定（LockTask）模式 */
    private fun isInLockTask(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        (am?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE) !=
            ActivityManager.LOCK_TASK_MODE_NONE
    } catch (_: Throwable) {
        false
    }

    /**
     * 无障碍事件里发现了「非本包」的前台窗口。
     *
     * 只在 App 已经不处于 resumed 时才拉回：App 前台时弹系统对话框（权限申请、日期选择、
     * 应用内弹窗）也会产生同类事件，那些不该被拽回。
     */
    fun onForeignWindow(ctx: Context, pkg: String?) {
        if (!PrivacyLockState.locked) return
        if (pkg.isNullOrEmpty()) return
        if (pkg == ctx.packageName) return
        // 前台窗口是桌面（含手势导航的多任务视图）→ 一定已经离开，直接拉回，
        // 不等我们的生命周期回调（多任务视图可能只走 onPause，甚至回调还没到）。
        val isHome = pkg in homePackages
        if (!isHome && AppVisibility.isResumed) return
        pullBack(ctx)
    }

    /** 解析当前桌面应用（HOME）的包名集合 */
    private fun resolveHomePackages(ctx: Context): Set<String> = try {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val infos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        infos.mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    /** 轮询兜底入口（供外部复用） */
    fun poll(ctx: Context) {
        appCtx = ctx.applicationContext
        checkNow()
    }

    private fun checkNow() {
        if (!PrivacyLockState.locked) return
        // 关键：以「是否 resumed」为准，这样上滑进多任务（只 pause 不 stop）也能识别
        if (AppVisibility.isResumed) {
            consecutivePulls = 0
            return
        }
        val ctx = appCtx ?: return
        pullBack(ctx)
    }

    /** 把 App 拉回前台 */
    fun pullBack(ctx: Context) {
        // 屏幕熄灭时不拉回，否则会不断把屏幕点亮；用户点亮屏幕后轮询会立刻生效
        if (!isScreenOn(ctx)) return
        val now = SystemClock.uptimeMillis()
        // 连续失败太多次就降频，避免被系统判定为异常的后台启动行为
        val interval = if (consecutivePulls > MAX_FAST_RETRY) SLOW_RETRY_INTERVAL_MS
        else MIN_PULL_INTERVAL_MS
        if (now - lastPullAt < interval) return
        lastPullAt = now
        consecutivePulls++
        try {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                // NEW_TASK：从非 Activity 上下文启动 Activity 必须加；
                // REORDER_TO_FRONT + SINGLE_TOP：复用已有实例，不重建、不丢当前页面。
                // 同时带 CLEAR_TOP 语义的 SINGLE_TOP 足以把处于多任务预览里的任务拉回前台。
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            // 本应用自己发起的跳转（把应用拉回前台）：登记后「后台锁定」不会误判为用户离开
            AppLockGuard.markSelfLaunch()
            ctx.startActivity(intent)
        } catch (_: Throwable) {
            // 极少数 ROM 会拦截后台启动 Activity；此时由下一次轮询重试
        }
    }

    private fun isScreenOn(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isInteractive ?: true
    } catch (_: Throwable) {
        true
    }
}

/**
 * App 的可见性状态。
 *
 * 由 [AuroraChatApplication] 注册的 ActivityLifecycleCallbacks 维护。
 *
 * **关键：判定"用户是否离开"用的是 [isResumed]，不是 [isForeground]。**
 * 上滑进多任务视图时，我们的 Activity 通常只走 `onPause`，`started` 计数仍然大于 0，
 * 只看 started 就会误判成"还在前台"，从而完全不拦。
 */
object AppVisibility {

    /** 是否有 Activity 处于 resumed 状态（真正占据屏幕焦点的那一个） */
    @Volatile
    var isResumed: Boolean = false
        private set

    /** 是否有 Activity 处于 started 状态 */
    @Volatile
    var isForeground: Boolean = false
        private set

    /** 当前处于 resumed 的 Activity（弱引用）。屏幕固定需要 Activity 上下文。 */
    @Volatile
    var currentActivity: WeakReference<Activity>? = null
        private set

    /** 只在主线程（生命周期回调）读写 */
    private var startedCount = 0
    private var resumedCount = 0

    internal fun onActivityStarted() {
        startedCount++
        isForeground = true
    }

    internal fun onActivityStopped() {
        if (startedCount > 0) startedCount--
        isForeground = startedCount > 0
        // 刚离开前台 → 立即触发回弹判断（0 延迟）
        if (!isForeground) PrivacyEnforcer.nudge()
    }

    internal fun onActivityResumed(activity: Activity) {
        resumedCount++
        isResumed = true
        currentActivity = WeakReference(activity)
        PrivacyEnforcer.onReturnedToForeground()
    }

    internal fun onActivityPaused() {
        if (resumedCount > 0) resumedCount--
        isResumed = resumedCount > 0
        // 暂停即触发：上滑进多任务时可能只有 onPause，没有 onStop
        if (!isResumed) PrivacyEnforcer.nudge()
    }

    internal fun onActivityDestroyed(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }
}

/** 便于在 Application 里一行注册生命周期回调（保持 Application 文件简洁） */
fun registerAppVisibilityCallbacks(
    onStarted: (Activity) -> Unit,
    onStopped: (Activity) -> Unit,
    onResumed: (Activity) -> Unit = {},
    onPaused: (Activity) -> Unit = {},
    onDestroyed: (Activity) -> Unit = {}
): Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityStarted(activity: Activity) = onStarted(activity)
    override fun onActivityStopped(activity: Activity) = onStopped(activity)
    override fun onActivityResumed(activity: Activity) = onResumed(activity)
    override fun onActivityPaused(activity: Activity) = onPaused(activity)
    override fun onActivityDestroyed(activity: Activity) = onDestroyed(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
