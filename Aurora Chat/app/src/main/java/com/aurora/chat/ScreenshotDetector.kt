package com.aurora.chat

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.*

/**
 * 截屏检测器（使用 ContentObserver 监听媒体库变化）
 * 当检测到新的截图文件时，通过 API 向对方发送告警。
 */
object ScreenshotDetector {

    private var observer: ContentObserver? = null
    private var lastDetectedAt = 0L
    private var currentFriendId = 0L

    fun start(context: Context, friendId: Long) {
        currentFriendId = friendId
        val contentResolver = context.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                val now = System.currentTimeMillis()
                if (now - lastDetectedAt < 1000) return
                lastDetectedAt = now
                CoroutineScope(Dispatchers.IO).launch {
                    delay(1000)
                    if (isRecentlyCapturedScreenshot(context)) {
                        val blockScreenshot = PrivacyFloatingService.noScreenshot
                        val detectScreenshot = PrivacyFloatingService.detectScreenshot
                        if (blockScreenshot || detectScreenshot) {
                            try {
                                // blocked=true → 拦截并通知; blocked=false → 仅通知检测
                                AuroraApi.sendPrivacyAlert(currentFriendId, "screenshot", blockScreenshot)
                            } catch (_: Exception) {}
                            if (blockScreenshot) {
                                NotificationHelper.showPrivacyAlert(context,
                                    "隐私保护",
                                    "对方尝试截图，已被系统拦截")
                            } else {
                                NotificationHelper.showPrivacyAlert(context,
                                    "隐私提醒",
                                    "对方进行了截图")
                            }
                        }
                    }
                }
            }
        }
        try {
            contentResolver.registerContentObserver(uri, true, observer!!)
        } catch (_: Exception) {}
    }

    fun stop(context: Context) {
        observer?.let {
            try { context.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        observer = null
    }

    private fun isRecentlyCapturedScreenshot(context: Context): Boolean {
        return try {
            val nowSec = System.currentTimeMillis() / 1000
            // 用多个条件搜索截图目录，兼容不同 Android 版本
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ 使用 RELATIVE_PATH 替代废弃的 DATA
                "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?) " +
                "AND ${MediaStore.Images.Media.DATE_ADDED} > ?"
            } else {
                "(${MediaStore.Images.Media.DATA} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?) " +
                "AND ${MediaStore.Images.Media.DATE_ADDED} > ?"
            }
            val selectionArgs = arrayOf("%DCIM/Screenshots%", "%Pictures/Screenshots%", "${nowSec - 10}")
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DATE_ADDED),
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"
            )
            cursor?.use {
                it.moveToFirst() && it.count > 0
            } ?: false
        } catch (_: Exception) { false }
    }
}
