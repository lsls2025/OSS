package com.aurora.chat

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import com.aurora.chat.ui.tools.DownloadManager
import kotlin.math.abs

/**
 * 系统级悬浮窗服务 —— 有下载任务时在当前 Activity 之上显示一个可拖拽的悬浮球。
 *
 * 悬浮球显示内容：
 * - 蓝粉渐变背景 + 颜色循环动画
 * - 上方：下载百分比（所有下载中任务的平均进度）
 * - 下方：横向圆角进度条（两端圆润）
 *
 * 点击悬浮球弹出菜单：
 * - 「关闭悬浮球」：关闭悬浮窗，不取消下载
 * - 「打开下载管理」：回到 App 并显示下载队列
 *
 * 当没有任何下载中的任务且所有任务都已完成后自动销毁。
 */
class DownloadFloatingService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var popupView: View? = null
    private var isPopupShowing = false
    private var isDestroyed = false

    /** 悬浮球上的状态文字 */
    private var tvProgress: TextView? = null
    /** 横向进度条 */
    private var progressBar: ProgressBar? = null
    /** 渐变背景，用于颜色动画 */
    private var bgGradient: GradientDrawable? = null
    /** 背景色循环动画 */
    private var colorAnimator: ValueAnimator? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 每 500ms 刷新一次悬浮球上的进度和进度条 */
    private val updateTask = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            refreshFloatingState()
            mainHandler.postDelayed(this, 500L)
        }
    }

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (floatingView == null) {
            buildFloatingView()
        }
        mainHandler.removeCallbacks(updateTask)
        mainHandler.post(updateTask)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isDestroyed = true
        colorAnimator?.cancel()
        mainHandler.removeCallbacks(updateTask)
        removePopup()
        floatingView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        floatingView = null
        super.onDestroy()
    }

    // ==================== 构建悬浮球视图 ====================

    private fun buildFloatingView() {
        val density = resources.displayMetrics.density
        val bubbleSize = (56 * density).toInt()

        // 创建天空蓝→粉渐变背景
        bgGradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF38BDF8.toInt(), 0xFFEC4899.toInt())
        ).apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }

        // 颜色循环动画：蓝 → 粉 → 浅粉 → 浅蓝 → 蓝（无限循环）
        // 蓝色只占约 1/4 时长，粉色为主色调
        colorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            0xFF38BDF8.toInt(),  // 天空蓝（起点，快速过渡）
            0xFFEC4899.toInt(),  // 粉（主色，停留最久）
            0xFFF9A8D4.toInt(),  // 浅粉（柔和过渡）
            0xFF7DD3FC.toInt(),  // 浅天空蓝（过渡回蓝色）
            0xFF38BDF8.toInt()   // 天空蓝（终点/起点）
        ).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val currColor = anim.animatedValue as Int
                bgGradient?.setColors(intArrayOf(
                    currColor,
                    blendColor(currColor, 0xFFF9A8D4.toInt(), 0.4f)
                ))
                floatingView?.invalidate()
            }
        }

        // === 横向进度条（两端圆润）===
        val barHeight = (4 * density).toInt()
        val barWidth = (42 * density).toInt()
        val cornerRadius = barHeight / 2f

        val trackDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadius(cornerRadius)
            setColor(0x55FFFFFF.toInt()) // 半透明白色轨道
        }
        val progressDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setCornerRadius(cornerRadius)
            setColor(android.graphics.Color.WHITE)
        }
        val clipDrawable = android.graphics.drawable.ClipDrawable(
            progressDrawable, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        val layerDrawable = android.graphics.drawable.LayerDrawable(
            arrayOf(trackDrawable, clipDrawable)
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = ViewGroup.LayoutParams(barWidth, barHeight)
            setProgressDrawable(layerDrawable)
            max = 100
            progress = 0
        }

        // === 百分比文字 ===
        tvProgress = TextView(this).apply {
            text = "0%"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        // 根容器（垂直排列：文字在上，进度条在下）
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(bubbleSize, bubbleSize)
            background = bgGradient
        }
        tvProgress?.let { root.addView(it) }
        root.addView(progressBar)

        // 让文字和进度条之间留点间隙
        (progressBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            topMargin = (2 * density).toInt()
        }

        floatingView = root

        // WindowManager 参数
        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (200 * density).toInt()
        }

        windowManager?.addView(root, params)

        // 启动颜色动画
        colorAnimator?.start()

        // 拖拽 + 点击
        root.setOnTouchListener(FloatingTouchHandler(params))
        root.setOnClickListener { togglePopup() }
    }

    /** 将两个颜色按比例混合，ratio=0 → 纯 c1，ratio=1 → 纯 c2 */
    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = ((c1 shr 16 and 0xFF) * (1f - ratio) + (c2 shr 16 and 0xFF) * ratio).toInt().coerceIn(0, 255)
        val g = ((c1 shr 8 and 0xFF) * (1f - ratio) + (c2 shr 8 and 0xFF) * ratio).toInt().coerceIn(0, 255)
        val b = ((c1 and 0xFF) * (1f - ratio) + (c2 and 0xFF) * ratio).toInt().coerceIn(0, 255)
        return 0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }

    // ==================== 刷新数据 ====================

    private fun refreshFloatingState() {
        val items = DownloadManager.queue
        if (items.isEmpty()) {
            stopSelf()
            return
        }

        val downloading = items.filter { it.isDownloading.value }
        val hasActive = downloading.isNotEmpty()
        val allFinished = items.all { it.isCompleted.value || it.error.value != null }

        // 没有任何在下载中的任务且全部已结束 → 自动销毁
        if (!hasActive && allFinished) {
            stopSelf()
            return
        }

        // 计算平均进度
        val avgProgress = if (downloading.isNotEmpty()) {
            downloading.map { it.progress.value }.average().toInt()
        } else {
            items.filter { it.isCompleted.value }.let { if (it.isNotEmpty()) 100 else 0 }
        }

        tvProgress?.text = "${avgProgress}%"
        progressBar?.progress = avgProgress
    }

    // ==================== 弹出菜单 ====================

    private fun togglePopup() {
        if (isPopupShowing) removePopup() else showPopup()
    }

    private fun showPopup() {
        if (popupView != null) removePopup()
        val density = resources.displayMetrics.density

        // 半透明遮罩
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x88000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 菜单卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(android.graphics.Color.WHITE)
            }
            val cardWidth = (240 * density).toInt()
            val cardHeight = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams = FrameLayout.LayoutParams(cardWidth, cardHeight, Gravity.CENTER)
        }

        // 标题
        card.addView(TextView(this).apply {
            text = "下载管理"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1F2937.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (20 * density).toInt(), 0, (12 * density).toInt())
        })

        // 分隔线
        card.addView(View(this).apply {
            setBackgroundColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            )
        })

        // 打开按钮
        card.addView(createMenuItem("打开下载管理", 0xFF1E40AF.toInt()) {
            removePopup()
            openApp()
        })

        // 分隔线
        card.addView(View(this).apply {
            setBackgroundColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            )
        })

        // 关闭悬浮球按钮
        card.addView(createMenuItem("关闭悬浮球", 0xFFDC2626.toInt()) {
            // 只关闭悬浮窗，不取消下载
            stopSelf()
        })

        root.addView(card)

        // 点击遮罩关闭弹窗
        root.setOnClickListener { removePopup() }

        popupView = root

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        windowManager?.addView(root, params)
        isPopupShowing = true
    }

    private fun createMenuItem(text: String, textColor: Int, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun removePopup() {
        popupView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        popupView = null
        isPopupShowing = false
    }

    private fun openApp() {
        // 标记需要切换到下载管理页面
        DownloadManager.restore()
        DownloadManager.navigateToDownloadManager.value = true

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    // ==================== 拖拽处理 ====================

    private inner class FloatingTouchHandler(private val params: WindowManager.LayoutParams) :
        View.OnTouchListener {

        private var initX = 0
        private var initY = 0
        private var initTouchX = 0f
        private var initTouchY = 0f
        private var dragging = false
        private val threshold = 10

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    initTouchX = event.rawX
                    initTouchY = event.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initTouchX).toInt()
                    val dy = (event.rawY - initTouchY).toInt()
                    if (!dragging && (abs(dx) > threshold || abs(dy) > threshold)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = initX + dx
                        params.y = initY + dy
                        try { windowManager?.updateViewLayout(floatingView, params) } catch (_: Exception) {}
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, DownloadFloatingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadFloatingService::class.java))
        }

        /** 检查是否已授予悬浮窗权限；未授予时尝试引导用户开启 */
        fun ensureOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(context)) {
                    true
                } else {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    false
                }
            } else {
                true
            }
        }
    }
}
