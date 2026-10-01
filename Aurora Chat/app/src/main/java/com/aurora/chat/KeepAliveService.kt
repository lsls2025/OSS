package com.aurora.chat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 1px 悬浮窗保活服务
 * 在系统窗口层添加 1×1 像素的透明悬浮窗，提升进程优先级
 * 配合电池白名单使用，减少系统查杀概率
 */
class KeepAliveService : Service() {

    companion object {
        fun start(context: Context) {
            try {
                context.startService(Intent(context, KeepAliveService::class.java))
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (_: Exception) {}
        }
    }

    private var wm: WindowManager? = null
    private var floatView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            floatView = TextView(this).apply {
                // 1px 不可见
                setWidth(1)
                setHeight(1)
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            params = WindowManager.LayoutParams(
                1, // width 1px
                1, // height 1px
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm?.addView(floatView, params)
        } catch (_: Exception) {
            // 无悬浮窗权限时不显示
        }
    }

    override fun onDestroy() {
        try {
            floatView?.let { wm?.removeView(it) }
        } catch (_: Exception) {}
        floatView = null
        wm = null
        super.onDestroy()
    }
}
