package com.aurora.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "aurora_chat_messages"
    private const val CHANNEL_NAME = "消息通知"
    private const val CHANNEL_DESC = "Aurora Chat 新消息通知"
    // 前台服务专用频道（无震动、无声音，避免保活心跳时震动）
    private const val FG_CHANNEL_ID = "aurora_chat_foreground"
    private const val FG_CHANNEL_NAME = "连接状态"

    /** Android 13+ 检查通知权限是否已授予 */
    fun hasNotificationPermission(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 检查已有渠道的重要性，如果用户或系统改低了就补一条日志
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing != null) {
                if (existing.importance < NotificationManager.IMPORTANCE_HIGH) {
                    android.util.Log.w("NotificationHelper",
                        "通知渠道重要性已被设为 ${existing.importance}（期望 HIGH=${NotificationManager.IMPORTANCE_HIGH}），" +
                        "请手动检查设置中 Aurora Chat → 通知 → 消息通知 → 重要程度")
                } else {
                    android.util.Log.i("NotificationHelper", "通知渠道重要性正常（HIGH）")
                }
            }
            // 消息通知频道（高重要性，有震动）
            val msgChannel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
                setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setBypassDnd(true)
            }
            nm.createNotificationChannel(msgChannel)

            // 前台服务专用频道（最低重要性，状态栏无图标，无震动无声音）
            val fgChannel = NotificationChannel(
                FG_CHANNEL_ID, FG_CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "TCP 长连接状态"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
            nm.createNotificationChannel(fgChannel)
        }
    }

    fun showMessageNotification(context: Context, title: String, content: String, friendId: Long = 0L, friendName: String = "") {
        // Android 13+ 未授权通知权限时日志记录
        if (!hasNotificationPermission(context)) {
            android.util.Log.w("NotificationHelper", "通知未显示: 缺少 POST_NOTIFICATIONS 权限")
            return
        }
        android.util.Log.i("NotificationHelper", "显示通知: title=$title, content=$content, friendId=$friendId")

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (friendId != 0L) {
                putExtra("open_chat_id", friendId)
                putExtra("open_chat_name", friendName)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.aurora.chat.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun showFriendRequestNotification(context: Context, fromName: String, greeting: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_friend_requests", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.aurora.chat.R.mipmap.ic_launcher)
            .setContentTitle("好友请求")
            .setContentText("${fromName} 请求添加你为好友")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt() + 1, notification)
    }

    fun buildForegroundNotification(context: Context, statusText: String = "正在连接..."): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 使用前台服务专用频道（最低重要性，状态栏无图标），动态显示 TCP 状态
        return NotificationCompat.Builder(context, FG_CHANNEL_ID)
            .setSmallIcon(com.aurora.chat.R.mipmap.ic_launcher)
            .setContentTitle("Aurora Chat")
            .setContentText("$statusText | 请勿划掉后台，否则将收不到消息")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun buildDownloadNotification(context: Context, text: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aurora Chat 下载")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun buildUploadNotification(context: Context, text: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Aurora Chat 上传")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun showUploadCompleteNotification(context: Context, summary: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("上传完成")
            .setContentText(summary)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt() + 500, notification)
    }

    /** 下载完成通知（带"安装"按钮） */
    fun showDownloadCompleteNotification(context: Context, apkUri: Uri, itemName: String) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val installPendingIntent = PendingIntent.getActivity(
            context, itemName.hashCode(), installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$itemName 下载完成")
            .setContentText("点击安装")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_upload, "安装", installPendingIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt() + 100, notification)
    }

    /** 隐私告警通知 */
    fun showPrivacyAlert(context: Context, title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt() + 300, notification)
    }

    // ===== AI 工具审批通知（应用在后台时使用）=====
    // 手动审批模式下 AI 退到后台干活时,应用内审批弹窗用户看不到,任务会一直挂起。
    // 此时发一条带「批准/拒绝」按钮的高优先级通知,用户在通知栏即可完成审批,不必回到应用。
    const val APPROVAL_NOTIFICATION_ID = 472019

    /** AI 工具审批通知:批准/拒绝两个动作经 AgentApprovalReceiver 回传结果;点通知本体回到应用内查看详情 */
    fun showToolApprovalNotification(context: Context, description: String, requestId: Long) {
        // 悬浮窗审批卡片:应用不在前台且已授予「显示在其他应用上层」权限时,直接在系统层弹出,
        // 用户在桌面/其它应用里也能一键批准或拒绝,不必下拉通知栏;未授权时静默跳过,通知仍是兜底入口。
        if (!com.aurora.chat.AppForegroundTracker.isForeground) {
            com.aurora.chat.AgentApprovalOverlay.show(context, description)
        }
        if (!hasNotificationPermission(context)) {
            android.util.Log.w("NotificationHelper", "审批通知未显示: 缺少 POST_NOTIFICATIONS 权限")
            return
        }
        val baseIntent = Intent(context, AgentApprovalReceiver::class.java)
        val approveIntent = Intent(baseIntent).setAction(AgentApprovalReceiver.ACTION_APPROVE)
        val denyIntent = Intent(baseIntent).setAction(AgentApprovalReceiver.ACTION_DENY)
        val approvePi = PendingIntent.getBroadcast(
            context, (requestId * 2).toInt(), approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyPi = PendingIntent.getBroadcast(
            context, (requestId * 2 + 1).toInt(), denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.aurora.chat.R.mipmap.ic_launcher)
            .setContentTitle("AI 请求审批")
            .setContentText(description)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                .bigText("$description\n（点此可回到应用内查看详情）"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_send, "批准", approvePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒绝", denyPi)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(APPROVAL_NOTIFICATION_ID, notification)
    }

    /** 撤掉审批通知（审批已在应用内完成、或请求已结束不再需要时调用）。同时撤下悬浮审批卡片。 */
    fun cancelToolApprovalNotification(context: Context) {
        com.aurora.chat.AgentApprovalOverlay.dismiss()
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(APPROVAL_NOTIFICATION_ID)
        }
    }

    fun showAccountDeletedNotification(context: Context, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("force_logout", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("账号已被删除")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt() + 200, notification)
    }
}
