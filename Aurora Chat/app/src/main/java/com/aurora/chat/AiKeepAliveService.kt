package com.aurora.chat

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * AI 长任务保活服务：由 Service 的主线程承载悬浮窗（与 DownloadFloatingService 同一套做法），
 * 让应用退到后台后进程仍保持较高优先级，不被系统冻结或回收，从而让 AI 的长任务
 * （wait 延迟、连续打开其它应用等）能跑完。
 *
 * 为什么必须由 Service 建窗：悬浮窗的 addView 只能在有 Looper 的线程执行，而 AI 工具是在
 * 协程 IO 线程里跑的（IO 线程没有 Looper，直接 addView 会抛
 * "Can't create handler inside thread that has not called Looper.prepare()"）。Service 回调天然在主线程。
 *
 * 两种窗口：
 *  - visible（默认，推荐）：用户可见的小窗「● AI 运行中」，可拖动、点击回到应用，保活最稳。
 *  - invisible：1×1 像素完全透明的隐形窗，用户看不到，但保活强度较弱。
 *
 * 需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW，Manifest 已声明）。
 */
class AiKeepAliveService : Service() {

    companion object {
        private const val EXTRA_VISIBLE = "visible"
        private const val EXTRA_ANCHOR = "anchor"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_TEXT = "text"
        /** 小窗默认文案 */
        const val DEFAULT_TEXT = "AI 运行中"
        /** 小窗文案长度上限（太长会把小窗撑得很宽） */
        private const val MAX_TEXT_LEN = 12
        const val MODE_VISIBLE = "visible"
        const val MODE_INVISIBLE = "invisible"
        const val ANCHOR_TOP_RIGHT = "top_right"
        const val ANCHOR_TOP_LEFT = "top_left"
        const val ANCHOR_BOTTOM_LEFT = "bottom_left"
        const val ANCHOR_BOTTOM_RIGHT = "bottom_right"

        /** 位置写法归一化：兼容中英文、连字符与下划线混用；无法识别返回 null */
        fun normalizeAnchor(raw: String): String? =
            when (raw.trim().lowercase().replace('-', '_').replace(" ", "")) {
                "top_right", "topright", "right_top", "右上", "右上角" -> ANCHOR_TOP_RIGHT
                "top_left", "topleft", "left_top", "左上", "左上角" -> ANCHOR_TOP_LEFT
                "bottom_left", "bottomleft", "left_bottom", "左下", "左下角" -> ANCHOR_BOTTOM_LEFT
                "bottom_right", "bottomright", "right_bottom", "右下", "右下角" -> ANCHOR_BOTTOM_RIGHT
                else -> null
            }

        /** 位置的中文名（用于工具结果回执） */
        fun anchorLabel(anchor: String): String = when (anchor) {
            ANCHOR_TOP_LEFT -> "左上角"
            ANCHOR_BOTTOM_LEFT -> "左下角"
            ANCHOR_BOTTOM_RIGHT -> "右下角"
            else -> "右上角"
        }

        /** 保活窗当前是否真的挂着（跨线程可见）。工具回执以它为准，避免「实际成功却报失败」 */
        @Volatile private var runningFlag = false
        fun isRunning(): Boolean = runningFlag

        /**
         * 运行中的 Service 实例（弱引用，避免泄漏）。
         *
         * 存在的意义：应用退到后台后 startService 会被系统拦截（Android 8+ 后台服务限制），
         * 导致 onStartCommand 不执行、小窗文案更新不到（表现为「一直显示上一轮」）。
         * 有了它就能直接在主线程改 labelView.text，完全绕开后台上限。
         */
        @Volatile private var instanceRef: java.lang.ref.WeakReference<AiKeepAliveService>? = null

        /**
         * 直接更新小窗文案与位置（不启服务，后台可用）。
         *
         * @return true 表示已交给主线程执行；false 表示当前没有挂着的窗口，需回退到 startService 路径
         */
        fun updateInPlace(anchor: String?, xDp: Int, yDp: Int, text: String?, onDone: ((Boolean) -> Unit)? = null): Boolean {
            val svc = instanceRef?.get() ?: return false
            val handler = svc.mainHandler ?: return false
            handler.post {
                val ok = svc.applyInPlace(anchor, xDp, yDp, text)
                onDone?.invoke(ok)
            }
            return true
        }

        /** 由 Service 在 onCreate/onDestroy 维护实例引用 */
        internal fun attach(svc: AiKeepAliveService) {
            instanceRef = java.lang.ref.WeakReference(svc)
        }

        internal fun detach(svc: AiKeepAliveService) {
            if (instanceRef?.get() === svc) instanceRef = null
        }

        /** 挂窗结果回传：Service 在主线程挂完窗后 complete 它（"" 表示成功），供工具调用方同步拿到成败原因 */
        @Volatile private var pending: CompletableDeferred<String>? = null

        /** 是否已授予「显示在其他应用上层」权限 */
        fun hasOverlayPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

        /** 跳到系统授权页，引导用户开启「显示在其他应用上层」 */
        fun requestOverlayPermission(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }

        /** 小窗文案归一化：去掉换行与首尾空白、限长；空则用默认文案 */
        fun normalizeText(raw: String): String {
            val t = raw.replace("\n", " ").replace("\r", " ").trim()
            if (t.isEmpty()) return DEFAULT_TEXT
            return if (t.length > MAX_TEXT_LEN) t.take(MAX_TEXT_LEN) else t
        }

        /**
         * 启动保活窗并等待挂载结果；成功返回 null，失败返回原因。
         * anchor 为空表示不改位置（沿用当前或默认右上角）；xDp/yDp ≥ 0 时按自定义坐标（dp，相对屏幕左上角）摆放。
         * text 为空表示不改文案（沿用当前或默认「AI 运行中」）。
         */
        suspend fun start(
            ctx: Context,
            visible: Boolean,
            anchor: String? = null,
            xDp: Int = -1,
            yDp: Int = -1,
            text: String? = null
        ): String? {
            if (!hasOverlayPermission(ctx)) return "未授予「显示在其他应用上层」权限"
            val def = CompletableDeferred<String>()
            pending = def
            try {
                val i = Intent(ctx, AiKeepAliveService::class.java).putExtra(EXTRA_VISIBLE, visible)
                if (!anchor.isNullOrBlank()) i.putExtra(EXTRA_ANCHOR, anchor)
                if (xDp >= 0) i.putExtra(EXTRA_X, xDp)
                if (yDp >= 0) i.putExtra(EXTRA_Y, yDp)
                if (!text.isNullOrBlank()) i.putExtra(EXTRA_TEXT, text)
                ctx.startService(i)
            } catch (e: Exception) {
                pending = null
                // 应用退到后台时系统可能拦截 startService；但窗口也许本来就挂着，此时按成功处理
                if (isRunning()) return null
                return "启动保活服务失败：${e.message ?: e.javaClass.simpleName}"
            }
            val r = withTimeoutOrNull(5000L) { def.await() }
            // 兜底：只要窗口确实挂上了，就算回执超时/丢失也按成功处理（避免「实际成功却报失败」）
            if (isRunning()) return null
            return if (r.isNullOrEmpty()) "启动保活窗超时：系统可能拦截了后台服务启动" else r
        }

        /** 关闭保活窗 */
        fun stop(ctx: Context) {
            pending = null
            try { ctx.stopService(Intent(ctx, AiKeepAliveService::class.java)) } catch (_: Exception) {}
        }

        // ===== 自动保活(Activity onStop 时 AI 任务未完成自动开启) =====

        /** 用户在悬浮窗里手动「关闭保活」后置位:本次在 App 外不再自动重启,回到前台后自动复位 */
        @Volatile internal var autoSuppressed = false
        fun isAutoSuppressed(): Boolean = autoSuppressed
        fun resetAutoSuppress() { autoSuppressed = false }

        /**
         * 自动保活入口:AI 任务未完成(AgentTaskGuard 在跑)且保活窗没挂时,退到后台自动开可见小窗。
         * 不等待挂载结果(非挂起),失败静默——审批通知兜底路径不受影响。
         */
        fun startAuto(ctx: Context) {
            if (isRunning() || autoSuppressed) return
            if (!hasOverlayPermission(ctx)) return
            try {
                ctx.startService(
                    Intent(ctx, AiKeepAliveService::class.java)
                        .putExtra(EXTRA_VISIBLE, true)
                        .putExtra(EXTRA_TEXT, DEFAULT_TEXT)
                )
            } catch (_: Exception) {}
        }
    }

    private var wm: WindowManager? = null
    private var windowView: View? = null
    private var windowMode: String? = null
    private var windowParams: WindowManager.LayoutParams? = null
    /** 可见小窗的位置锚点（默认右上角） */
    private var anchor: String = ANCHOR_TOP_RIGHT
    /** 自定义坐标（dp，相对屏幕左上角）；-1 表示未指定，按锚点摆放 */
    private var posX = -1
    private var posY = -1
    /** 可见小窗的渐变底色，用于颜色循环动画 */
    private var bgGradient: GradientDrawable? = null
    /** 底色循环动画 */
    private var colorAnimator: ValueAnimator? = null
    /** 小窗文案控件（AI 可随时改文案，无需重建窗口） */
    private var labelView: TextView? = null
    /** 当前小窗文案 */
    private var currentText: String = DEFAULT_TEXT
    /** 主线程 Handler：供 updateInPlace 从任意线程切主线程改 UI */
    private var mainHandler: android.os.Handler? = null

    override fun onCreate() {
        super.onCreate()
        mainHandler = android.os.Handler(mainLooper)
        attach(this)
    }

    /**
     * 原地更新小窗（文案 / 位置），不重启服务。
     * 只应在主线程调用。返回 true 表示确实应用了某项变更。
     */
    private fun applyInPlace(anchorRaw: String?, xDp: Int, yDp: Int, textRaw: String?): Boolean {
        val v = windowView ?: return false
        var changed = false
        // 位置：只有明确传了参数才改，避免把用户手动拖到的位置重置
        if (anchorRaw != null || xDp >= 0 || yDp >= 0) {
            if (anchorRaw != null) anchor = normalizeAnchor(anchorRaw) ?: ANCHOR_TOP_RIGHT
            if (xDp >= 0) posX = xDp
            if (yDp >= 0) posY = yDp
            changed = true
        }
        // 文案：直接改控件，无需 startService
        if (textRaw != null) {
            currentText = normalizeText(textRaw)
            labelView?.text = currentText
            changed = true
        }
        if (changed) {
            // 文案 / 位置变化会改变小窗宽度，等重新布局后再贴一次边，避免超出屏幕
            v.post { windowParams?.let { applyPosition(v, it) } }
        }
        return changed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val visible = intent?.getBooleanExtra(EXTRA_VISIBLE, true) ?: true
        val want = if (visible) MODE_VISIBLE else MODE_INVISIBLE
        // 只有本次明确带了位置参数才改位置，避免「重复调用 keep_alive」把用户手动拖到的位置重置回右上角
        val hasPos = intent?.hasExtra(EXTRA_ANCHOR) == true ||
                intent?.hasExtra(EXTRA_X) == true ||
                intent?.hasExtra(EXTRA_Y) == true
        if (hasPos) {
            anchor = normalizeAnchor(intent?.getStringExtra(EXTRA_ANCHOR).orEmpty()) ?: ANCHOR_TOP_RIGHT
            posX = intent?.getIntExtra(EXTRA_X, -1) ?: -1
            posY = intent?.getIntExtra(EXTRA_Y, -1) ?: -1
        }
        // 本次是否指定了小窗文案（AI 可用它说明「正在做什么」）
        val textRaw = if (intent?.hasExtra(EXTRA_TEXT) == true)
            normalizeText(intent.getStringExtra(EXTRA_TEXT).orEmpty()) else null
        var err: String? = null
        val existing = windowView
        if (existing != null && windowMode == want) {
            // 窗口已在跑：原地更新文案 / 位置，不重建（避免闪一下）
            if (textRaw != null) {
                currentText = textRaw
                labelView?.text = textRaw
            }
            if (hasPos || textRaw != null) {
                // 文案变了宽度也会变，等重新布局后再贴一次边
                existing.post { windowParams?.let { applyPosition(existing, it) } }
            }
        } else {
            removeWindow()
            try {
                wm = getSystemService(WINDOW_SERVICE) as WindowManager
                currentText = textRaw ?: DEFAULT_TEXT
                if (visible) addVisibleWindow() else addInvisibleWindow()
                windowMode = want
                runningFlag = true
            } catch (e: Exception) {
                removeWindow()
                windowMode = null
                err = "创建保活悬浮窗失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
        pending?.let { if (!it.isCompleted) it.complete(err ?: "") }
        pending = null
        return START_STICKY
    }

    override fun onDestroy() {
        dismissDialog()
        removeWindow()
        wm = null
        windowMode = null
        detach(this)
        mainHandler = null
        super.onDestroy()
    }

    private fun removeWindow() {
        colorAnimator?.cancel()
        colorAnimator = null
        bgGradient = null
        windowParams = null
        labelView = null
        windowView?.let { v -> try { wm?.removeView(v) } catch (_: Exception) {} }
        windowView = null
        runningFlag = false
    }

    // ==================== 隐形窗 ====================

    private fun addInvisibleWindow() {
        val v = View(this)
        val params = WindowManager.LayoutParams(
            1, 1, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        wm?.addView(v, params)
        windowView = v
    }

    // ==================== 可见小窗 ====================

    private fun addVisibleWindow() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val screenW = resources.displayMetrics.widthPixels

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
        }
        val label = TextView(this).apply {
            text = currentText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }
        labelView = label

        // 底色：沿用下载悬浮球那套「渐变 + 颜色循环」，只换成蓝 → 青绿的配色
        bgGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF38BDF8.toInt(), 0xFF14B8A6.toInt())
        ).apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.LINEAR_GRADIENT
            cornerRadius = dp(18).toFloat()
        }
        colorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            0xFF38BDF8.toInt(),  // 天空蓝（起点）
            0xFF14B8A6.toInt(),  // 青绿（主色，停留最久）
            0xFF5EEAD4.toInt(),  // 浅青（柔和过渡）
            0xFF7DD3FC.toInt(),  // 浅天空蓝（过渡回蓝色）
            0xFF38BDF8.toInt()   // 天空蓝（终点/起点）
        ).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val curr = anim.animatedValue as Int
                bgGradient?.setColors(intArrayOf(curr, blendColor(curr, 0xFF5EEAD4.toInt(), 0.4f)))
                windowView?.invalidate()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = bgGradient
            addView(dot)
            addView(label)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowParams = params
        applyPosition(root, params)

        root.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x; initY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (!dragging && (abs(dx) > 10 || abs(dy) > 10)) dragging = true
                        if (dragging) {
                            params.x = (initX + dx).coerceIn(0, (screenW - v.width).coerceAtLeast(0))
                            params.y = (initY + dy).coerceAtLeast(0)
                            try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // 点击(非拖动):弹「请选择你要进行的操作」菜单,不再直接回 App
                        if (!dragging) showActionDialog()
                        return true
                    }
                }
                return false
            }
        })

        wm?.addView(root, params)
        windowView = root
        colorAnimator?.start()
        // 布局完成后拿到真实宽高再校准一次（贴右/贴下需要真实尺寸才能贴边）
        root.post { applyPosition(root, params) }
    }

    /**
     * 按当前锚点/自定义坐标摆放可见小窗。
     * x/y 单位是 dp、相对屏幕左上角；未指定时按 anchor（默认右上角）贴边。
     */
    private fun applyPosition(v: View, params: WindowManager.LayoutParams) {
        val d = resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val margin = dp(12)
        val w = if (v.width > 0) v.width else dp(110)
        val h = if (v.height > 0) v.height else dp(36)
        params.gravity = Gravity.TOP or Gravity.START
        if (posX >= 0 || posY >= 0) {
            if (posX >= 0) params.x = dp(posX)
            if (posY >= 0) params.y = dp(posY)
        } else {
            when (anchor) {
                ANCHOR_TOP_LEFT -> { params.x = margin; params.y = margin }
                ANCHOR_BOTTOM_LEFT -> { params.x = margin; params.y = (screenH - h - margin).coerceAtLeast(0) }
                ANCHOR_BOTTOM_RIGHT -> {
                    params.x = (screenW - w - margin).coerceAtLeast(0)
                    params.y = (screenH - h - margin).coerceAtLeast(0)
                }
                else -> { params.x = (screenW - w - margin).coerceAtLeast(0); params.y = margin }
            }
        }
        try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
    }

    /** 将两个颜色按比例混合，ratio=0 → 纯 c1，ratio=1 → 纯 c2 */
    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = ((c1 shr 16 and 0xFF) * (1f - ratio) + (c2 shr 16 and 0xFF) * ratio).toInt().coerceIn(0, 255)
        val g = ((c1 shr 8 and 0xFF) * (1f - ratio) + (c2 shr 8 and 0xFF) * ratio).toInt().coerceIn(0, 255)
        val b = ((c1 and 0xFF) * (1f - ratio) + (c2 and 0xFF) * ratio).toInt().coerceIn(0, 255)
        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }

    // ==================== 点击操作弹窗(请选择你要进行的操作) ====================

    private var dialogView: View? = null

    /** 弹「请选择你要进行的操作」菜单:回到软件 / 关闭保活(带二次确认)。点遮罩关闭菜单。 */
    private fun showActionDialog() {
        if (dialogView != null) return
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val wmLocal = wm ?: return

        // 遮罩层:半透明压暗,点击遮罩(卡片外)直接收起菜单
        val dim = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0x66000000.toInt())
            setOnClickListener { dismissDialog() }
        }
        // 卡片容器:居中
        val center = android.widget.FrameLayout(this)
        dim.addView(center, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        ))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
            setPadding(dp(22), dp(20), dp(22), dp(18))
            elevation = dp(10).toFloat()
        }
        center.addView(card)
        // 菜单/确认共用一个内容容器,切换时只重填内容
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(content)

        fun bigButton(label: String, filled: Boolean, colorHex: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                if (filled) {
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.parseColor(colorHex)); cornerRadius = dp(12).toFloat() }
                } else {
                    setTextColor(Color.parseColor(colorHex))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE); cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Color.parseColor(colorHex))
                    }
                }
                setOnClickListener { onClick() }
            }

        // 菜单/确认两视图互相切换,用 lambda 变量解决局部函数前向引用
        lateinit var showMenu: () -> Unit
        lateinit var showConfirm: () -> Unit

        showMenu = {
            content.removeAllViews()
            content.addView(TextView(this).apply {
                text = "请选择你要进行的操作"
                setTextColor(Color.parseColor("#1F2937")); textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            content.addView(bigButton("回到软件", true, "#1E40AF") {
                dismissDialog(); openApp()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
            content.addView(bigButton("关闭保活", false, "#DC2626") { showConfirm() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        }

        showConfirm = {
            content.removeAllViews()
            content.addView(TextView(this).apply {
                text = "关闭保活"
                setTextColor(Color.parseColor("#1F2937")); textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            content.addView(TextView(this).apply {
                text = "关闭后可能导致后台被系统冻结，导致 AI 无法完成任务。确定要关闭吗？"
                setTextColor(Color.parseColor("#4B5563")); textSize = 13f
                lineHeight = dp(19)
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            content.addView(bigButton("仍要关闭", true, "#DC2626") {
                dismissDialog()
                autoSuppressed = true
                stopSelf()   // onDestroy → removeWindow,保活窗与保活效果一并撤销
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
            content.addView(bigButton("取消", false, "#6B7280") { showMenu() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        }

        showMenu()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wmLocal.addView(dim, params)
            dialogView = dim
        } catch (_: Exception) {}
    }

    private fun dismissDialog() {
        dialogView?.let { v -> try { wm?.removeView(v) } catch (_: Exception) {} }
        dialogView = null
    }

    private fun openApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
}
