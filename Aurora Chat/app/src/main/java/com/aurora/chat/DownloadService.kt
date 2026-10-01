package com.aurora.chat

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.aurora.chat.ui.tools.DownloadManager

/**
 * 下载前台服务——仅用于保活，让下载在后台持续运行。
 * 实际下载逻辑由 DownloadManager 协程完成，此服务只提供前台通知。
 *
 * 同时负责管理系统级悬浮窗（DownloadFloatingService）的显示与隐藏。
 */
class DownloadService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastFloatingState = false
    /** 是否已经提示过用户悬浮窗权限，避免重复弹窗 */
    private var permissionHintShown = false
    /** 后台下载唤醒锁，防止系统休眠中断下载 */
    private var wakeLock: PowerManager.WakeLock? = null

    private val checkTask = object : Runnable {
        override fun run() {
            updateNotification()
            checkFloatingService()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = buildNotificationText()
        val notification = NotificationHelper.buildDownloadNotification(this, text)
        startForeground(1002, notification)
        lastFloatingState = false
        permissionHintShown = false
        // 获取唤醒锁，防止后台下载时系统休眠中断连接
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AuroraChat:DownloadWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L) // 最长持有10分钟
        }
        handler.post(checkTask)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(checkTask)
        // 确保悬浮窗随 Service 销毁
        try { DownloadFloatingService.stop(this) } catch (_: Exception) {}
        // 释放唤醒锁
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    /** 更新前台通知文字 */
    private fun updateNotification() {
        val text = buildNotificationText()
        try {
            val notification = NotificationHelper.buildDownloadNotification(this, text)
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(1002, notification)
        } catch (_: Exception) {}
    }

    private fun buildNotificationText(): String {
        val active = DownloadManager.activeDownloadCount
        val total = DownloadManager.queue.size
        val progress = DownloadManager.overallProgress
        return if (active > 0) {
            "正在下载 $active 个任务，总进度 $progress%"
        } else if (DownloadManager.queue.any { it.isCompleted.value }) {
            "下载完成，共 $total 个"
        } else {
            "正在下载..."
        }
    }

    /** 检查是否需要显示/隐藏悬浮窗 */
    private fun checkFloatingService() {
        val needFloating = DownloadManager.shouldShowFloating
        if (needFloating && !lastFloatingState) {
            // 需要显示悬浮窗
            if (DownloadFloatingService.ensureOverlayPermission(this)) {
                DownloadFloatingService.start(this)
                lastFloatingState = true
                permissionHintShown = false
            } else {
                // 悬浮窗权限未授予——但下载仍会在后台继续进行（通过前台 Service 保活）
                // 仅提示一次，避免每次 1 秒轮询都弹 Toast
                if (!permissionHintShown) {
                    permissionHintShown = true
                    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !Settings.canDrawOverlays(this)
                    ) {
                        "您暂未给悬浮窗权限，应用将在后台继续下载"
                    } else {
                        "应用将在后台继续下载"
                    }
                    // 使用长 Toast 确保用户看到
                    Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
                }
            }
        } else if (!needFloating && lastFloatingState) {
            // 不需要显示悬浮窗了
            DownloadFloatingService.stop(this)
            lastFloatingState = false
            permissionHintShown = false
        } else if (!needFloating && !lastFloatingState) {
            // 没有下载任务时复位提示状态
            permissionHintShown = false
        }
    }
}
