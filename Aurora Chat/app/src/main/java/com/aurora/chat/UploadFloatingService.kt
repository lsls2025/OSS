package com.aurora.chat

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
import kotlin.math.abs

/**
 * 上传悬浮球 —— 后台上传时显示可拖拽的悬浮窗，显示上传进度。
 */
class UploadFloatingService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var popupView: View? = null
    private var isPopupShowing = false
    private var isDestroyed = false

    private var tvProgress: TextView? = null
    private var progressBar: ProgressBar? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val updateTask = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            refreshState()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (floatingView == null) buildFloatingView()
        mainHandler.removeCallbacks(updateTask)
        mainHandler.post(updateTask)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacks(updateTask)
        removePopup()
        floatingView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        floatingView = null
        super.onDestroy()
    }

    private fun buildFloatingView() {
        val density = resources.displayMetrics.density
        val bubbleSize = (56 * density).toInt()

        val bg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1E40AF.toInt(), 0xFF7C3AED.toInt())
        ).apply { shape = GradientDrawable.OVAL }

        // 进度条
        val barHeight = (4 * density).toInt()
        val barWidth = (42 * density).toInt()
        val cornerRadius = barHeight / 2f

        val trackDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setCornerRadius(cornerRadius); setColor(0x55FFFFFF.toInt())
        }
        val progDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setCornerRadius(cornerRadius); setColor(android.graphics.Color.WHITE)
        }
        val clipDrawable = android.graphics.drawable.ClipDrawable(
            progDrawable, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        val layerDrawable = android.graphics.drawable.LayerDrawable(arrayOf(trackDrawable, clipDrawable)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = ViewGroup.LayoutParams(barWidth, barHeight)
            setProgressDrawable(layerDrawable); max = 100; progress = 0
        }

        tvProgress = TextView(this).apply {
            text = "上传中"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(bubbleSize, bubbleSize)
            background = bg
        }
        tvProgress?.let { root.addView(it) }
        root.addView(progressBar)
        (progressBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.apply { topMargin = (2 * density).toInt() }

        floatingView = root

        val params = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (200 * density).toInt()
        }

        windowManager?.addView(root, params)
        root.setOnTouchListener(FloatingTouchHandler(params))
        root.setOnClickListener { togglePopup() }
    }

    private fun refreshState() {
        val mgr = UploadManager
        if (mgr.queue.isEmpty()) { stopSelf(); return }

        val hasActive = mgr.activeUploadCount > 0
        val allDone = mgr.queue.all { it.isCompleted.value || it.error.value != null || it.isCancelled.value }

        if (!hasActive && allDone) {
            tvProgress?.text = "完成"
            progressBar?.progress = 100
            mainHandler.postDelayed({ if (!isDestroyed) stopSelf() }, 2000L)
            mainHandler.removeCallbacks(updateTask)
            return
        }

        val pct = mgr.currentFileProgress
        tvProgress?.text = "$pct%"
        progressBar?.progress = pct
    }

    private fun togglePopup() {
        if (isPopupShowing) removePopup() else showPopup()
    }

    private fun showPopup() {
        if (popupView != null) removePopup()
        val density = resources.displayMetrics.density

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x88000000.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = 16 * density; setColor(android.graphics.Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams((240 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        card.addView(TextView(this).apply {
            text = "上传进度"
            textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1F2937.toInt()); gravity = Gravity.CENTER
            setPadding(0, (20 * density).toInt(), 0, (12 * density).toInt())
        })
        card.addView(View(this).apply {
            setBackgroundColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })

        val mgr = UploadManager
        card.addView(TextView(this).apply {
            text = "正在上传: ${mgr.currentFileName}"
            textSize = 13f; setTextColor(0xFF4B5563.toInt()); gravity = Gravity.CENTER
            setPadding(0, (14 * density).toInt(), 0, (4 * density).toInt())
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        })
        card.addView(TextView(this).apply {
            val pct = mgr.currentFileProgress
            text = "进度 $pct%"
            textSize = 12f; setTextColor(0xFF9CA3AF.toInt()); gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14 * density).toInt())
        })

        card.addView(View(this).apply {
            setBackgroundColor(0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        card.addView(TextView(this).apply {
            text = "取消上传"
            textSize = 15f; setTextColor(0xFFDC2626.toInt()); gravity = Gravity.CENTER
            setPadding(0, (14 * density).toInt(), 0, (14 * density).toInt())
            setOnClickListener {
                removePopup()
                UploadManager.cancelAll()
                stopSelf()
            }
        })

        root.addView(card)
        root.setOnClickListener { removePopup() }
        popupView = root

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(root, params)
        isPopupShowing = true
    }

    private fun removePopup() {
        popupView?.let { v -> try { windowManager?.removeView(v) } catch (_: Exception) {} }
        popupView = null; isPopupShowing = false
    }

    private inner class FloatingTouchHandler(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var initX = 0; private var initY = 0
        private var initTouchX = 0f; private var initTouchY = 0f
        private var dragging = false; private val threshold = 10

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    dragging = false; return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initTouchX).toInt(); val dy = (event.rawY - initTouchY).toInt()
                    if (!dragging && (abs(dx) > threshold || abs(dy) > threshold)) dragging = true
                    if (dragging) { params.x = initX + dx; params.y = initY + dy
                        try { windowManager?.updateViewLayout(floatingView, params) } catch (_: Exception) {} }
                    return true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) v.performClick(); return true }
            }
            return false
        }
    }

    companion object {
        @JvmStatic
        fun start(context: Context) { context.startService(Intent(context, UploadFloatingService::class.java)) }
        @JvmStatic
        fun stop(context: Context) { context.stopService(Intent(context, UploadFloatingService::class.java)) }

        fun ensureOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(context)) true
                else {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    false
                }
            } else true
        }
    }
}
