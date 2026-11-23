package com.aurora.chat.ui.custom

import android.animation.ValueAnimator
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aurora.chat.MainActivity
import com.aurora.chat.R
import kotlin.math.abs
import kotlin.math.min

/**
 * 系统悬浮窗服务 —— 自定义悬浮窗的运行时。
 *
 * 用 WindowManager.TYPE_APPLICATION_OVERLAY 挂系统窗口，Service 与 Activity 生命周期解耦。
 *
 * **窗口装饰全部交给用户**：标题栏、收起按钮、关闭按钮、拖拽条、白色外框都是可关的，
 * 默认全关，窗口长什么样完全由画布决定。想收起/关闭/锁定，用户在画布上自己放一个按钮
 * 并挂对应动作即可，不存在"必须长这样"的宿主 UI。
 *
 * 画布用原生 View 渲染（不用 ComposeView）：Service 窗口没有 Activity 的 Lifecycle /
 * SavedStateRegistryOwner，ComposeView 在这类窗口里容易抛 ViewTreeLifecycleOwner not found。
 */
class ExtensionFloatingService : Service() {

    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var currentExt: HostExtension? = null
    private var isCollapsed = false

    private var collapsedView: View? = null
    private var collapsedParams: WindowManager.LayoutParams? = null
    private var expandedView: View? = null
    private var expandedParams: WindowManager.LayoutParams? = null
    private var autoCollapseRunnable: Runnable? = null
    private var dynamicRunnable: Runnable? = null
    private var sosRunnable: Runnable? = null

    /** 运行时状态：不写回配置，关掉浮窗后就还原。 */
    private var runtimeLocked = false
    private var torchOn = false
    private var torchLevel = 1
    private var keepScreenOn = false
    private var tts: TextToSpeech? = null

    /** 元素 id → 渲染出来的 View，供「显隐元素」动作使用。 */
    private val elementViews = mutableMapOf<String, View>()
    /** 含 {变量} 的文字 View，每秒刷新一次。 */
    private val dynamicTexts = mutableListOf<Pair<CanvasElement, TextView>>()
    /** 连击判定：元素 id → 计数状态，重渲染时清空。 */
    private val clickChains = mutableMapOf<String, MultiClickTracker>()

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            DynamicValues.lightLux = e.values.firstOrNull() ?: -1f
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    companion object {
        private const val ACTION_REFRESH = "com.aurora.chat.custom.FLOATING_REFRESH"
        private const val EXTRA_ID = "ext_id"
        private const val EXTRA_EXPAND = "expand"
        private const val MANAGE_ALL_FILES = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"
        /** 连击判定窗口：两次点击间隔小于该值算同一次连击。 */
        private const val CLICK_CHAIN_MS = 320L

        /** 从管理页点「显示」时 expand=true，强制展开，避免点了看不到东西。 */
        fun start(ctx: Context, id: String, expand: Boolean = false) {
            runCatching {
                ctx.startService(
                    Intent(ctx, ExtensionFloatingService::class.java)
                        .putExtra(EXTRA_ID, id)
                        .putExtra(EXTRA_EXPAND, expand)
                )
            }
        }

        fun refresh(ctx: Context, id: String) {
            runCatching {
                ctx.startService(
                    Intent(ctx, ExtensionFloatingService::class.java)
                        .setAction(ACTION_REFRESH)
                        .putExtra(EXTRA_ID, id)
                )
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ExtensionFloatingService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        registerLightSensor()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        ExtensionSafety.guard(
            this, "启动悬浮窗", START_NOT_STICKY,
            critical = true, badId = intent?.getStringExtra(EXTRA_ID)
        ) {
            val id = intent?.getStringExtra(EXTRA_ID).takeUnless { it.isNullOrBlank() }
                ?: FloatingExtensionHost.activeId(this)
            if (ExtensionSafety.isBad(this, id)) {
                FloatingExtensionHost.clearActive(this)
                stopSelf()
                START_NOT_STICKY
            } else {
                val ext = HostExtensionStore.usable(this).firstOrNull {
                    it.id == id && it.kind == ExtensionKind.FLOATING_WINDOW
                }
                if (ext == null || !FloatingExtensionHost.hasOverlayPermission(this)) {
                    FloatingExtensionHost.clearActive(this)
                    stopSelf()
                    START_NOT_STICKY
                } else {
                    currentExt = ext
                    val ok = ExtensionSafety.guardRun(
                        this, "渲染悬浮窗", critical = true, badId = ext.id
                    ) {
                        applyConfig(ext, intent?.getBooleanExtra(EXTRA_EXPAND, false) == true)
                    }
                    if (!ok) {
                        FloatingExtensionHost.clearActive(this)
                        stopSelf()
                        START_NOT_STICKY
                    } else START_STICKY
                }
            }
        }

    override fun onDestroy() {
        destroyed = true
        cancelAutoCollapse()
        cancelDynamicRefresh()
        cancelSos()
        setTorch(false, quiet = true)
        unregisterLightSensor()
        runCatching { tts?.shutdown() }
        tts = null
        DynamicValues.torchOn = false
        DynamicValues.keepScreenOn = false
        DynamicValues.lightLux = -1f
        handler.removeCallbacksAndMessages(null)
        removeExpanded()
        removeCollapsed()
        super.onDestroy()
    }

    private fun applyConfig(
        ext: HostExtension,
        forceExpand: Boolean,
        origin: Pair<Int, Int>? = null
    ) {
        removeExpanded()
        removeCollapsed()
        cancelAutoCollapse()
        cancelDynamicRefresh()
        runtimeLocked = false
        elementViews.clear()
        dynamicTexts.clear()
        cancelClickChains()
        val collapsed = ext.floatingStyle.collapsible && !ext.floatingAutoExpand && !forceExpand
        if (collapsed) showCollapsed(ext, origin) else showExpanded(ext, origin)
    }

    // ────────────────────────── 展开态：完整画布 ──────────────────────────

    private fun showExpanded(ext: HostExtension, origin: Pair<Int, Int>? = null) {
        removeCollapsed()
        cancelAutoCollapse()
        isCollapsed = false
        val d = resources.displayMetrics.density
        val wPx = (ext.floatingWidthDp.coerceIn(80f, 340f) * d).toInt()
        val headerPx = if (ext.floatingShowHeader) {
            (ext.floatingHeaderHeightDp.coerceIn(16f, 56f) * d).toInt()
        } else 0
        val bodyPx = (ext.floatingHeightDp.coerceIn(60f, 480f) * d).toInt()
        val hPx = headerPx + bodyPx
        val radius = ext.floatingRadiusDp.coerceIn(0f, 40f) * d

        val root = FrameLayout(this).apply {
            if (!ext.floatingBareCanvas) {
                background = roundedDrawable(0xFFFFFFFF.toInt(), radius, 0xFFE5E7EB.toInt(), 1)
                outlineProvider = roundOutline(radius)
                clipToOutline = true
            }
            alpha = ext.floatingAlpha.coerceIn(0.3f, 1f)
        }

        // 标题栏：整块可关；里面每个按钮也各自可关；标题文字可自定义。
        var header: LinearLayout? = null
        if (ext.floatingShowHeader) {
            header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ColorDrawable(ext.floatingHeaderBg.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, headerPx
                )
                setPadding((10 * d).toInt(), 0, (4 * d).toInt(), 0)
            }
            if (ext.floatingShowDragBar) {
                header.addView(View(this).apply {
                    background = roundedDrawable(0xFFD1D5DB.toInt(), 2 * d)
                    layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), (3 * d).toInt())
                })
            }
            if (ext.floatingHeaderTitle.isNotBlank() || ext.name.isNotBlank()) {
                header.addView(TextView(this).apply {
                    text = ext.floatingHeaderTitle.ifBlank { ext.name }
                    textSize = 11f
                    setTextColor(ext.floatingHeaderTextColor.toInt())
                    maxLines = 1
                    setPadding((8 * d).toInt(), 0, 0, 0)
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                })
            } else {
                header.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                })
            }
            if (ext.floatingShowCollapse) {
                header.addView(
                    headerButton("−", ext.floatingHeaderTextColor.toInt()) {
                        val p = expandedParams
                        showCollapsed(ext, p?.let { it.x to it.y })
                    },
                    LinearLayout.LayoutParams((28 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                )
            }
            if (ext.floatingShowClose) {
                header.addView(
                    headerButton("×", ext.floatingHeaderTextColor.toInt()) { closeAll() },
                    LinearLayout.LayoutParams((28 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                )
            }
            root.addView(header)
            root.addView(View(this).apply {
                setBackgroundColor(0x1F000000)
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 1)
                y = headerPx.toFloat()
            })
        }

        val canvasRoot = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(wPx, bodyPx)
            y = headerPx.toFloat()
            background = canvasBackground(ext.canvas)
            if (ext.floatingBareCanvas && radius > 0f) {
                outlineProvider = roundOutline(radius)
                clipToOutline = true
            }
        }
        renderCanvasInto(canvasRoot, ext, wPx, bodyPx, d, interactive = true)
        canvasRoot.setOnClickListener { performAction(ext.action, ext.actionValue, ext) }
        root.addView(canvasRoot)

        // 没有标题栏时，顶部留一条极窄的拖拽热区；关掉可见条后它仍透明存在，
        // 保证窗口永远能拖动，又不强加任何视觉元素。
        var dragStrip: View? = null
        if (header == null) {
            dragStrip = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(wPx, (14 * d).toInt())
                if (ext.floatingShowDragBar) {
                    addView(View(this@ExtensionFloatingService).apply {
                        background = roundedDrawable(0x66D1D5DB.toInt(), 2 * d)
                        layoutParams = FrameLayout.LayoutParams(
                            (26 * d).toInt(), (3 * d).toInt(),
                            Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                        )
                    })
                }
            }
            root.addView(dragStrip)
        }

        val params = windowParams(wPx, hPx, ext, origin)
        if (!addWindow(root, params)) return
        expandedView = root
        expandedParams = params
        val dragTarget = header ?: dragStrip
        dragTarget?.setOnTouchListener(
            DragHandler(
                params, root, wPx, hPx,
                draggable = { !ext.floatingLockPosition && !runtimeLocked },
                snap = ext.floatingSnapToSide,
                onTap = null
            )
        )
        scheduleAutoCollapse(ext)
        scheduleDynamicRefresh()
    }

    // ────────────────────────── 折叠态：把手 ──────────────────────────

    private fun showCollapsed(ext: HostExtension, origin: Pair<Int, Int>? = null) {
        removeExpanded()
        cancelAutoCollapse()
        isCollapsed = true
        val d = resources.displayMetrics.density
        val (wDp, hDp) = when (ext.floatingStyle) {
            FloatingStyle.BALL -> 56f to 56f
            FloatingStyle.MINI -> 48f to 48f
            FloatingStyle.BAR -> ext.floatingWidthDp.coerceIn(120f, 320f) to 44f
            FloatingStyle.CARD -> 56f to 56f
        }
        val wPx = (wDp * d).toInt()
        val hPx = (hDp * d).toInt()
        val radius = if (ext.floatingStyle == FloatingStyle.BALL) wPx / 2f else 12 * d

        val root: View
        if (ext.floatingStyle == FloatingStyle.BALL) {
            root = TextView(this).apply {
                text = ext.floatingBadge.trim().take(2).ifEmpty { "✦" }
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(ext.floatingCollapsedTextColor.toInt())
                setTypeface(null, Typeface.BOLD)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(ext.floatingCollapsedBg.toInt(), ext.floatingCollapsedBg.toInt())
                ).apply { shape = GradientDrawable.OVAL }
                alpha = ext.floatingAlpha.coerceIn(0.3f, 1f)
                elevation = 6 * d
            }
        } else {
            val frame = FrameLayout(this).apply {
                background = roundedDrawable(
                    ext.floatingCollapsedBg.toInt(), radius, 0xFFE5E7EB.toInt(), 1
                )
                outlineProvider = roundOutline(radius)
                clipToOutline = true
                alpha = ext.floatingAlpha.coerceIn(0.3f, 1f)
                elevation = 4 * d
            }
            val content = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(wPx, hPx)
                background = canvasBackground(ext.canvas)
            }
            renderCanvasInto(content, ext, wPx, hPx, d, interactive = false)
            frame.addView(content)
            root = frame
        }

        val params = windowParams(wPx, hPx, ext, origin)
        if (!addWindow(root, params)) return
        collapsedView = root
        collapsedParams = params
        root.setOnTouchListener(
            DragHandler(
                params, root, wPx, hPx,
                draggable = { !ext.floatingLockPosition && !runtimeLocked },
                snap = ext.floatingSnapToSide,
                onTap = { showExpanded(ext, params.x to params.y) }
            )
        )
        scheduleDynamicRefresh()
    }

    // ────────────────────────── 画布渲染 ──────────────────────────

    /** 与 ExtensionCanvas 同一套 CanvasSpec → View 的等比换算。 */
    private fun renderCanvasInto(
        container: FrameLayout,
        ext: HostExtension,
        wPx: Int,
        hPx: Int,
        d: Float,
        interactive: Boolean
    ) {
        val spec = ext.canvas
        val wDp = wPx / d
        val hDp = hPx / d
        val scale = min(wDp / spec.widthDp.coerceAtLeast(1f), hDp / spec.heightDp.coerceAtLeast(1f))
        val offXDp = (wDp - spec.widthDp * scale) / 2f
        val offYDp = (hDp - spec.heightDp * scale) / 2f
        elementViews.clear()
        dynamicTexts.clear()
        cancelClickChains()
        var skipped = 0
        spec.elements.forEach { el ->
            runCatching {
                val v = renderElement(el, scale, d)
                v.x += offXDp * d
                v.y += offYDp * d
                elementViews[el.id] = v
                if (v is TextView && DynamicValues.hasVariables(el.text)) {
                    dynamicTexts.add(el to v)
                }
                if (interactive) {
                    v.isClickable = true
                    wireClicks(v, el, ext)
                }
                container.addView(v)
            }.onFailure { t ->
                skipped++
                runCatching {
                    com.aurora.chat.ErrorReporter.warn(
                        tag = "FloatingRender",
                        message = "跳过元素 ${el.kind.label}: ${t.javaClass.simpleName}",
                        throwable = t
                    )
                }
            }
        }
        if (skipped > 0) ExtensionSafety.toast(this, "$skipped 个元素渲染失败，已跳过")
    }

    private fun renderElement(el: CanvasElement, scale: Float, d: Float): View {
        val view: View = when (el.kind) {
            CanvasElementKind.TEXT, CanvasElementKind.BUTTON -> TextView(this).apply {
                text = DynamicValues.resolve(el.text, this@ExtensionFloatingService)
                val baseSp = (el.fontSizeSp * scale).coerceAtLeast(1f)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp)
                // 框小的时候由系统把字缩到能放下，不再因为 8sp 下限被裁成残缺
                runCatching {
                    setAutoSizeTextTypeUniformWithConfiguration(
                        1, baseSp.toInt().coerceAtLeast(2), 1, TypedValue.COMPLEX_UNIT_SP
                    )
                }
                setTextColor(el.textColor.toInt())
                setTypeface(
                    null,
                    if (el.bold || el.kind == CanvasElementKind.BUTTON) Typeface.BOLD else Typeface.NORMAL
                )
                gravity = when (el.textAlign) {
                    0 -> Gravity.START or Gravity.CENTER_VERTICAL
                    2 -> Gravity.END or Gravity.CENTER_VERTICAL
                    else -> Gravity.CENTER
                }
                maxLines = 3
                includeFontPadding = false
                setLineSpacing(0f, 1f)
                // 等宽数字：时间 / 网速 / 电量变化时字宽不变，不会左右跳
                fontFeatureSettings = "tnum"
                setPadding(0, 0, 0, 0)
            }
            CanvasElementKind.IMAGE, CanvasElementKind.ICON -> ImageView(this).apply {
                val bmp = if (el.assetPath.isNotBlank()) {
                    runCatching { BitmapFactory.decodeFile(el.assetPath) }.getOrNull()
                } else null
                if (bmp != null) setImageBitmap(bmp) else setImageResource(R.drawable.ic_app_icon)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            CanvasElementKind.SHAPE -> View(this)
        }

        view.background = GradientDrawable().apply {
            setColor(el.bgColor.toInt())
            cornerRadius = el.cornerRadiusDp * scale * d
            if (el.borderWidthDp > 0f) {
                setStroke(
                    (el.borderWidthDp * scale * d).toInt().coerceAtLeast(1),
                    el.borderColor.toInt()
                )
            }
        }
        if (el.cornerRadiusDp > 0f) {
            val radius = el.cornerRadiusDp * scale * d
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            view.clipToOutline = true
        }
        view.layoutParams = FrameLayout.LayoutParams(
            (el.widthDp * scale * d).toInt().coerceAtLeast(1),
            (el.heightDp * scale * d).toInt().coerceAtLeast(1)
        )
        view.x = el.xDp * scale * d
        view.y = el.yDp * scale * d
        view.alpha = el.alpha.coerceIn(0f, 1f)
        view.rotation = el.rotation
        return view
    }

    // ────────────────────────── 动作分发 ──────────────────────────

    /**
     * 单击 / 双击 / 三击 / 长按的统一分发。
     * 只有当用户配置了更高阶动作（双击或三击）时，单击才会延迟约 320ms 等待连击；
     * 什么都没配就走原来的立即触发，不引入任何手感损失。
     */
    private fun wireClicks(v: View, el: CanvasElement, ext: HostExtension) {
        if (el.longClickAction != ElementAction.NONE) {
            v.setOnLongClickListener {
                performAction(el.longClickAction, el.longClickValue, ext)
                true
            }
        }
        val hasDouble = el.doubleClickAction != ElementAction.NONE
        val hasTriple = el.tripleClickAction != ElementAction.NONE
        if (!hasDouble && !hasTriple) {
            v.setOnClickListener {
                performAction(
                    if (el.clickAction == ElementAction.NONE) ext.action else el.clickAction,
                    if (el.clickAction == ElementAction.NONE) ext.actionValue else el.clickValue,
                    ext
                )
            }
            return
        }
        val tracker = MultiClickTracker(el, ext)
        clickChains[el.id] = tracker
        v.setOnClickListener { tracker.onClick() }
    }

    private inner class MultiClickTracker(
        private val el: CanvasElement,
        private val ext: HostExtension
    ) {
        private var count = 0
        private var lastAt = 0L
        private var pending: Runnable? = null

        fun cancel() {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
            count = 0
        }

        fun onClick() {
            val now = SystemClock.uptimeMillis()
            count = if (now - lastAt < CLICK_CHAIN_MS) count + 1 else 1
            lastAt = now
            pending?.let { handler.removeCallbacks(it) }
            pending = null

            if (count >= 3) {
                count = 0
                performAction(el.tripleClickAction, el.tripleClickValue, ext)
                return
            }
            val next = Runnable {
                pending = null
                when (count) {
                    1 -> performAction(
                        if (el.clickAction == ElementAction.NONE) currentExt?.action ?: ElementAction.NONE
                        else el.clickAction,
                        if (el.clickAction == ElementAction.NONE) currentExt?.actionValue.orEmpty() else el.clickValue,
                        currentExt ?: return@Runnable
                    )
                    2 -> performAction(el.doubleClickAction, el.doubleClickValue, ext)
                }
                count = 0
            }
            pending = next
            // 配了三击时双击也要多等一拍，给第三次点击留机会
            val wait = if (count == 2 && el.tripleClickAction != ElementAction.NONE) CLICK_CHAIN_MS * 2L
            else CLICK_CHAIN_MS
            handler.postDelayed(next, wait)
        }
    }

    /** 参数里的 {变量} 会先展开，所以「朗读当前电量」「复制网速」这类组合都能做。 */
    private fun performAction(action: ElementAction, rawValue: String, ext: HostExtension) {
        ExtensionSafety.guardRun(this, "执行浮窗动作", badId = ext.id) {
            val value = DynamicValues.resolve(rawValue, this@ExtensionFloatingService)
            when (action) {
                // 硬件
                ElementAction.FLASHLIGHT -> toggleFlashlight()
                ElementAction.TORCH_SOS -> toggleTorchSos()
                ElementAction.TORCH_LEVEL -> setTorchLevel(value)
                ElementAction.VIBRATE -> vibrate(value)
                ElementAction.VIBRATE_PATTERN -> vibratePattern(value)
                ElementAction.MEDIA_PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                ElementAction.MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                ElementAction.MEDIA_PREV -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                ElementAction.VOLUME_UP -> adjustVolume(AudioManager.ADJUST_RAISE)
                ElementAction.VOLUME_DOWN -> adjustVolume(AudioManager.ADJUST_LOWER)
                ElementAction.VOLUME_MUTE -> toggleMute()
                ElementAction.KEEP_SCREEN_ON -> toggleKeepScreenOn()
                ElementAction.SCREEN_BRIGHT -> setBrightness(value)
                ElementAction.AUTO_BRIGHTNESS -> toggleAutoBrightness()
                ElementAction.ROTATION_LOCK -> toggleRotationLock()
                ElementAction.RINGER_MODE -> setRinger(value)
                ElementAction.DND_TOGGLE -> toggleDnd()
                ElementAction.WAKE_SCREEN -> wakeScreen()

                // 权限 / 系统面板
                ElementAction.PERM_WRITE_SETTINGS ->
                    openSystemPage(Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true)
                ElementAction.PERM_DND ->
                    openSystemPage(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                ElementAction.PERM_OVERLAY ->
                    openSystemPage(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, withPackage = true)
                ElementAction.PERM_BATTERY ->
                    openSystemPage(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                ElementAction.PERM_USAGE ->
                    openSystemPage(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                ElementAction.PERM_ALL_FILES -> openSystemPage(MANAGE_ALL_FILES)
                ElementAction.APP_DETAILS ->
                    openSystemPage(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)
                ElementAction.SET_TIMER -> setTimer(value)
                ElementAction.SET_ALARM -> setAlarm(value)
                ElementAction.WIFI_PANEL -> openSystemPage(Settings.ACTION_WIFI_SETTINGS)
                ElementAction.BT_PANEL -> openSystemPage(Settings.ACTION_BLUETOOTH_SETTINGS)
                ElementAction.NFC_PANEL -> openSystemPage(Settings.ACTION_NFC_SETTINGS)
                ElementAction.LOCATION_PANEL ->
                    openSystemPage(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                ElementAction.SOUND_PANEL -> openSystemPage(Settings.ACTION_SOUND_SETTINGS)
                ElementAction.AIRPLANE_PANEL ->
                    openSystemPage(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                ElementAction.BATTERY_PANEL ->
                    openSystemPage(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                ElementAction.PRIVACY_PANEL -> openSystemPage(Settings.ACTION_PRIVACY_SETTINGS)

                // 窗口
                ElementAction.NONE -> Unit
                ElementAction.COLLAPSE -> collapseToHandle()
                ElementAction.EXPAND -> {
                    if (isCollapsed) {
                        val p = collapsedParams
                        showExpanded(ext, p?.let { it.x to it.y })
                    }
                }
                ElementAction.CLOSE -> closeAll()
                ElementAction.TOGGLE_LOCK -> {
                    runtimeLocked = !runtimeLocked
                    toast(if (runtimeLocked) "位置已锁定" else "位置已解锁")
                }
                ElementAction.REFRESH -> {
                    val p = (expandedParams ?: collapsedParams)?.let { it.x to it.y }
                    applyConfig(ext, false, p)
                }
                ElementAction.TOUCH_THROUGH -> toggleTouchThrough()

                // 跳转
                ElementAction.BACK_TO_APP -> backToApp()
                ElementAction.OPEN_URL -> openUrl(value)
                ElementAction.OPEN_APP -> openApp(value)
                ElementAction.OPEN_SETTINGS -> openSettings(value)
                ElementAction.DIAL -> dial(value)
                ElementAction.SMS -> sms(value)

                // 数据
                ElementAction.TTS_SPEAK -> speak(value)
                ElementAction.TOGGLE_ELEMENT -> toggleElement(value)
                ElementAction.TIMER_TOGGLE -> {
                    HostTimer.toggle()
                    refreshDynamicNow()
                }
                ElementAction.TIMER_RESET -> {
                    HostTimer.reset()
                    refreshDynamicNow()
                }

                // 杂项
                ElementAction.TOAST -> toast(value)
                ElementAction.COPY_TEXT -> copyText(value)
                ElementAction.SHARE_TEXT -> shareText(value)
            }
        }
    }

    // ────────────────────────── 硬件动作实现 ──────────────────────────

    private fun torchCamera(cm: CameraManager): String? = cm.cameraIdList.firstOrNull { id ->
        runCatching {
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.getOrDefault(false)
    }

    private fun setTorch(on: Boolean, quiet: Boolean = false) {
        runCatching {
            val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: throw IllegalStateException("no camera service")
            val cam = torchCamera(cm) ?: throw IllegalStateException("no flash")
            cm.setTorchMode(cam, on)
            torchOn = on
            DynamicValues.torchOn = on
            if (!quiet) toast(if (on) "手电筒已开" else "手电筒已关")
        }.onFailure { if (!quiet) toast("无法控制手电筒") }
    }

    private fun toggleFlashlight() = setTorch(!torchOn)

    /** 再点一次停止；服务销毁时自动关掉，不会留下一直闪的手电筒。 */
    private fun toggleTorchSos() {
        if (sosRunnable != null) {
            cancelSos()
            setTorch(false, quiet = true)
            toast("爆闪已停")
            return
        }
        var on = false
        val r = object : Runnable {
            override fun run() {
                if (destroyed || sosRunnable !== this) return
                on = !on
                setTorch(on, quiet = true)
                handler.postDelayed(this, 110L)
            }
        }
        sosRunnable = r
        handler.post(r)
        toast("爆闪中，再点一次停止")
    }

    private fun cancelSos() {
        sosRunnable?.let { handler.removeCallbacks(it) }
        sosRunnable = null
    }

    /** Android 13+ 支持闪光灯分级亮度；低版本回退成普通开关。 */
    private fun setTorchLevel(value: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toggleFlashlight()
            return
        }
        runCatching {
            val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: throw IllegalStateException("no camera service")
            val cam = torchCamera(cm) ?: throw IllegalStateException("no flash")
            val max = cm.getCameraCharacteristics(cam)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            if (max <= 1) {
                toggleFlashlight()
                return
            }
            val want = value.trim().toIntOrNull()
            torchLevel = (want ?: (torchLevel % max) + 1).coerceIn(1, max)
            cm.turnOnTorchWithStrengthLevel(cam, torchLevel)
            torchOn = true
            DynamicValues.torchOn = true
            toast("手电亮度 $torchLevel/$max")
        }.onFailure {
            toast("该设备不支持分级亮度")
            toggleFlashlight()
        }
    }

    private fun vibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private fun vibrate(value: String) {
        val ms = value.trim().toLongOrNull()?.coerceIn(10L, 2000L) ?: 80L
        val v = vibrator() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }
    }

    private fun vibratePattern(value: String) {
        val parts = value.split(",", " ", "，").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty()) {
            vibrate("80")
            return
        }
        val v = vibrator() ?: return
        runCatching {
            val pattern = LongArray(parts.size) { parts[it].coerceIn(0L, 2000L) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        }
    }

    private fun mediaKey(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }.onFailure { toast("媒体键不可用") }
    }

    private fun adjustVolume(dir: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }.onFailure { toast("音量调节失败") }
    }

    private fun toggleMute() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            val muted = am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE,
                0
            )
            toast(if (muted) "已取消静音" else "已静音")
        }.onFailure { toast("静音切换失败") }
    }

    /** 悬浮窗窗口带 KEEP_SCREEN_ON 时全局屏幕不会自动熄灭，关掉浮窗即还原。 */
    private fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn
        DynamicValues.keepScreenOn = keepScreenOn
        listOfNotNull(expandedParams, collapsedParams).forEach { p ->
            val v = if (p === expandedParams) expandedView else collapsedView
            p.flags = if (keepScreenOn) {
                p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            }
            v?.let { runCatching { wm?.updateViewLayout(it, p) } }
        }
        toast(if (keepScreenOn) "屏幕常亮已开" else "屏幕常亮已关")
    }

    /** 让整个窗口变成纯贴纸；5 秒后自动恢复，避免用户把自己锁死。 */
    private fun toggleTouchThrough() {
        val p = expandedParams ?: collapsedParams ?: return
        val v = expandedView ?: collapsedView ?: return
        runCatching {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm?.updateViewLayout(v, p)
        }
        toast("已穿透，5 秒后恢复")
        handler.postDelayed({
            if (destroyed) return@postDelayed
            runCatching {
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                wm?.updateViewLayout(v, p)
            }
        }, 5000L)
    }

    private fun wakeScreen() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "aurora:custom:wake"
            )
            lock.acquire(1500L)
            toast("已点亮屏幕")
        }.onFailure { toast("无法点亮屏幕") }
    }

    private fun ensureWriteSettings(): Boolean {
        if (Settings.System.canWrite(this)) return true
        openSystemPage(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            withPackage = true,
            tip = "需要先允许修改系统设置"
        )
        return false
    }

    private fun setBrightness(value: String) {
        val n = value.trim().toIntOrNull()
        if (n == null) {
            toast("亮度填 0-255")
            return
        }
        if (!ensureWriteSettings()) return
        runCatching {
            val v = n.coerceIn(1, 255)
            Settings.System.putInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
            toast("亮度已设为 $v")
        }.onFailure { toast("亮度设置失败") }
    }

    private fun toggleAutoBrightness() {
        if (!ensureWriteSettings()) return
        runCatching {
            val cur = Settings.System.getInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val auto = cur != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            Settings.System.putInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            toast(if (auto) "自动亮度已开" else "自动亮度已关")
        }.onFailure { toast("自动亮度切换失败") }
    }

    private fun toggleRotationLock() {
        if (!ensureWriteSettings()) return
        runCatching {
            val cur = Settings.System.getInt(
                contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1
            )
            Settings.System.putInt(
                contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                if (cur == 1) 0 else 1
            )
            toast(if (cur == 1) "已锁定竖屏" else "已恢复自动旋转")
        }.onFailure { toast("旋转设置失败") }
    }

    private fun ensureDndAccess(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        if (nm?.isNotificationPolicyAccessGranted == true) return true
        openSystemPage(
            Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            tip = "需要先授予勿扰权限"
        )
        return false
    }

    private fun setRinger(value: String) {
        if (!ensureDndAccess()) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = when (value.trim().lowercase()) {
            "silent", "静音" -> AudioManager.RINGER_MODE_SILENT
            "vibrate", "振动" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        runCatching {
            am.setRingerMode(mode)
            toast("铃音模式已切换")
        }.onFailure { toast("铃音模式切换失败") }
    }

    private fun toggleDnd() {
        if (!ensureDndAccess()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        runCatching {
            val all = nm.currentInterruptionFilter ==
                android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            nm.setInterruptionFilter(
                if (all) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            )
            toast(if (all) "勿扰已开" else "勿扰已关")
        }.onFailure { toast("勿扰切换失败") }
    }

    private fun speak(value: String) {
        if (value.isBlank()) return
        runCatching {
            val t = tts ?: TextToSpeech(this) { }.also { tts = it }
            t.setLanguage(java.util.Locale.getDefault())
            @Suppress("DEPRECATION")
            t.speak(value, TextToSpeech.QUEUE_FLUSH, Bundle.EMPTY, "aurora_custom")
        }.onFailure { toast("语音朗读不可用") }
    }

    // ────────────────────────── 系统页面与跳转 ──────────────────────────

    private fun openSystemPage(action: String, withPackage: Boolean = false, tip: String? = null) {
        tip?.let { toast(it) }
        runCatching {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (withPackage) i.data = Uri.parse("package:$packageName")
            startActivity(i)
        }.onFailure { toast("系统不支持这个设置页") }
    }

    private fun setTimer(value: String) {
        val sec = value.trim().toIntOrNull()?.coerceIn(1, 86400) ?: 60
        runCatching {
            startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("无法启动系统计时器") }
    }

    private fun setAlarm(value: String) {
        val parts = value.trim().split(":", "：")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 30
        runCatching {
            startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, h.coerceIn(0, 23))
                    .putExtra(AlarmClock.EXTRA_MINUTES, m.coerceIn(0, 59))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("无法设置闹钟") }
    }

    private fun openSettings(key: String) {
        val target = when (key.trim().lowercase()) {
            "wifi", "wlan" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "notification" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "vpn" -> Settings.ACTION_VPN_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        runCatching {
            val i = Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (target == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(i)
        }.onFailure { toast("打不开这个设置页") }
    }

    private fun collapseToHandle() {
        val cur = currentExt ?: return
        if (!cur.floatingStyle.collapsible || isCollapsed) return
        val p = expandedParams
        showCollapsed(cur, p?.let { it.x to it.y })
    }

    private fun backToApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    private fun openUrl(value: String) {
        val v = normalizeUrl(value) ?: run {
            toast("网址为空或无法识别")
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("没有能打开网页的应用") }
    }

    /** 自动补 https:// 前缀；用户只填 baidu.com 也能直接跳系统浏览器。 */
    private fun normalizeUrl(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return when {
            v.startsWith("http://") || v.startsWith("https://") -> v
            v.contains(".") && !v.contains(" ") -> "https://$v"
            else -> null
        }
    }

    private fun openApp(value: String) {
        val pkg = value.trim()
        if (pkg.isEmpty()) return
        val intent = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun dial(value: String) {
        val n = value.trim()
        if (n.isEmpty()) return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun sms(value: String) {
        val n = value.trim()
        if (n.isEmpty()) return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun copyText(value: String) {
        if (value.isEmpty()) return
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("aurora", value))
            toast("已复制")
        }
    }

    private fun shareText(value: String) {
        if (value.isEmpty()) return
        runCatching {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }
            startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun toast(text: String) {
        if (text.isBlank()) return
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    /** 显隐另一个元素：参数填元素 ID，或直接填图层面板里的序号 1、2、3。 */
    private fun toggleElement(id: String) {
        val key = id.trim()
        val v = elementViews[key] ?: key.toIntOrNull()?.let { n ->
            elementViews.values.toList().getOrNull(n - 1)
        } ?: return
        v.visibility = if (v.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
    }

    private fun closeAll() {
        FloatingExtensionHost.clearActive(this)
        stopSelf()
    }

    // ────────────────────────── 传感器 ──────────────────────────

    private fun registerLightSensor() {
        runCatching {
            val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            sensorManager = sm
            lightSensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
            lightSensor?.let { sm.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    private fun unregisterLightSensor() {
        runCatching { lightSensor?.let { sensorManager?.unregisterListener(lightListener, it) } }
        sensorManager = null
        lightSensor = null
    }

    // ────────────────────────── 窗口与拖动 ──────────────────────────

    private fun windowParams(
        wPx: Int,
        hPx: Int,
        ext: HostExtension,
        origin: Pair<Int, Int>?
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        wPx, hPx, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val pos = initialPosition(ext, wPx, hPx, origin)
        x = pos.first
        y = pos.second
    }

    private fun initialPosition(
        ext: HostExtension,
        wPx: Int,
        hPx: Int,
        origin: Pair<Int, Int>?
    ): Pair<Int, Int> {
        val d = resources.displayMetrics.density
        val maxX = (resources.displayMetrics.widthPixels - wPx).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - hPx).coerceAtLeast(0)
        val edge = (12 * d).toInt()
        val inset = (90 * d).toInt()
        fun clamp(x: Int, y: Int) = x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
        if (origin != null) return clamp(origin.first, origin.second)
        return when (ext.floatingAnchor) {
            FloatingAnchor.REMEMBER -> {
                val saved = FloatingExtensionHost.windowPosition(this)
                if (saved.first >= 0) clamp(saved.first, saved.second)
                else clamp(maxX / 2, maxY / 3)
            }
            FloatingAnchor.TOP_LEFT -> clamp(edge, inset)
            FloatingAnchor.TOP_RIGHT -> clamp(maxX - edge, inset)
            FloatingAnchor.CENTER -> clamp(maxX / 2, maxY / 2)
            FloatingAnchor.BOTTOM_LEFT -> clamp(edge, maxY - inset)
            FloatingAnchor.BOTTOM_RIGHT -> clamp(maxX - edge, maxY - inset)
        }
    }

    private fun addWindow(v: View, p: WindowManager.LayoutParams): Boolean = try {
        if (wm == null) false
        else {
            wm?.addView(v, p)
            true
        }
    } catch (_: Exception) {
        false
    }

    private inner class DragHandler(
        private val params: WindowManager.LayoutParams,
        private val target: View,
        private val wPx: Int,
        private val hPx: Int,
        private val draggable: () -> Boolean,
        private val snap: Boolean,
        private val onTap: (() -> Unit)?
    ) : View.OnTouchListener {
        private var initX = 0
        private var initY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!draggable()) return true
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragging = true
                    if (dragging) {
                        val maxX = (resources.displayMetrics.widthPixels - wPx).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - hPx).coerceAtLeast(0)
                        params.x = (initX + dx).coerceIn(0, maxX)
                        params.y = (initY + dy).coerceIn(0, maxY)
                        runCatching { wm?.updateViewLayout(target, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onTap?.invoke()
                    else {
                        val finalX = if (snap) snapToEdge() else params.x
                        FloatingExtensionHost.saveWindowPosition(
                            this@ExtensionFloatingService, finalX, params.y
                        )
                    }
                    return true
                }
            }
            return false
        }

        /** @return 吸附后的最终 x，供落盘用 */
        private fun snapToEdge(): Int {
            val maxX = (resources.displayMetrics.widthPixels - wPx).coerceAtLeast(0)
            val dest = if (params.x < maxX / 2) 0 else maxX
            if (dest == params.x) return dest
            ValueAnimator.ofInt(params.x, dest).apply {
                duration = 180L
                addUpdateListener { anim ->
                    params.x = anim.animatedValue as Int
                    runCatching { wm?.updateViewLayout(target, params) }
                }
                start()
            }
            return dest
        }
    }

    private fun scheduleAutoCollapse(ext: HostExtension) {
        cancelAutoCollapse()
        if (!ext.floatingStyle.collapsible || ext.floatingAutoCollapseMs <= 0L) return
        val runnable = Runnable {
            if (destroyed || isCollapsed) return@Runnable
            val cur = currentExt ?: return@Runnable
            val p = expandedParams
            showCollapsed(cur, p?.let { it.x to it.y })
        }
        autoCollapseRunnable = runnable
        handler.postDelayed(runnable, ext.floatingAutoCollapseMs)
    }

    private fun cancelAutoCollapse() {
        autoCollapseRunnable?.let { handler.removeCallbacks(it) }
        autoCollapseRunnable = null
    }

    /** 只有画布里真的写了 {变量} 或显隐条件才起定时器，避免空转耗电。 */
    private fun scheduleDynamicRefresh() {
        cancelDynamicRefresh()
        if (dynamicTexts.isEmpty()) return
        val r = object : Runnable {
            override fun run() {
                if (destroyed) return
                refreshDynamicNow()
                handler.postDelayed(this, 1000L)
            }
        }
        dynamicRunnable = r
        handler.postDelayed(r, 1000L)
    }

    private fun refreshDynamicNow() {
        runCatching {
            dynamicTexts.forEach { (el, tv) ->
                tv.text = DynamicValues.resolve(el.text, this@ExtensionFloatingService)
            }
        }.onFailure {
            cancelDynamicRefresh()
            ExtensionSafety.toast(this, "动态文字刷新失败，已停止")
        }
    }

    private fun cancelDynamicRefresh() {
        dynamicRunnable?.let { handler.removeCallbacks(it) }
        dynamicRunnable = null
    }

    /** 重渲染前清掉连击等待，避免旧元素的挂起动作在新画面上误触发。 */
    private fun cancelClickChains() {
        clickChains.values.forEach { it.cancel() }
        clickChains.clear()
    }

    private fun removeExpanded() {
        expandedView?.let { v -> runCatching { wm?.removeView(v) } }
        expandedView = null
        expandedParams = null
    }

    private fun removeCollapsed() {
        collapsedView?.let { v -> runCatching { wm?.removeView(v) } }
        collapsedView = null
        collapsedParams = null
    }

    private fun headerButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(color)
            setOnClickListener { onClick() }
        }

    private fun roundedDrawable(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) {
            o.setRoundRect(0, 0, v.width, v.height, radius.coerceAtLeast(0f))
        }
    }

    private fun canvasBackground(spec: CanvasSpec) =
        if (spec.backgroundEnd != 0L) {
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(spec.background.toInt(), spec.backgroundEnd.toInt())
            )
        } else ColorDrawable(spec.background.toInt())

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
}
