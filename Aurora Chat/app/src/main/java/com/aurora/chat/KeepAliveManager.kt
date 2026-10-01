package com.aurora.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

private const val ALARM_REQUEST_CODE = 2001
private const val ALARM_INTERVAL_MS = 10 * 60 * 1000L // 10 分钟

/**
 * 保活管理器 — 三级保活策略
 *
 * 所有设置存入 SharedPreferences "aurora_keepalive"
 * 通过 SettingsPage 引导用户手动开启
 */
object KeepAliveManager {

    private const val PREFS_NAME = "aurora_keepalive"
    private const val TAG = "KeepAlive"

    // 保活层开关 key
    const val KEY_LAYER1 = "keepalive_layer1"  // 核心保活：前台服务 + 自适应心跳
    const val KEY_LAYER2 = "keepalive_layer2"  // 辅助防休眠：1px浮窗 + 电池白名单
    const val KEY_LAYER3 = "keepalive_layer3"  // 兜底恢复：AlarmManager 定时唤醒

    // 网络切换监听器
    var onNetworkChanged: ((isOnline: Boolean, isWifi: Boolean) -> Unit)? = null
    private var cmCallback: ConnectivityManager.NetworkCallback? = null

    // 网络抖动防抖：连续 onCapabilitiesChanged 可能在同一秒内多次触发，限频避免反复拉起前台服务
    private var lastNetworkEnsureMs = 0L

    // ==================== 设置读写 ====================

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isLayer1Enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LAYER1, true)
    fun isLayer2Enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LAYER2, false)
    fun isLayer3Enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LAYER3, false)

    fun setLayer1(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LAYER1, enabled).apply()
        if (enabled) {
            ensureTcpService(context)
            Toast.makeText(context, "核心保活已开启", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "核心保活已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    fun setLayer2(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LAYER2, enabled).apply()
        if (enabled) {
            start1pxFloating(context)
            Toast.makeText(context, "辅助防休眠已开启", Toast.LENGTH_SHORT).show()
            // 检查悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        } else {
            stop1pxFloating(context)
            Toast.makeText(context, "辅助防休眠已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    fun setLayer3(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LAYER3, enabled).apply()
        if (enabled) {
            scheduleAlarmWakeup(context)
            Toast.makeText(context, "定时唤醒已开启", Toast.LENGTH_SHORT).show()
        } else {
            cancelAlarmWakeup(context)
            Toast.makeText(context, "定时唤醒已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 权限检测 ====================

    /** 检测是否已加入电池优化白名单 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 检测悬浮窗权限 */
    fun hasOverlayPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(context)
    }

    /** 一键开启全部三层保活（已开启的层不再重复触发权限申请） */
    fun enableAllLayers(context: Context) {
        if (!isLayer1Enabled(context)) setLayer1(context, true)
        if (!isLayer2Enabled(context)) setLayer2(context, true)
        if (!isLayer3Enabled(context)) setLayer3(context, true)
    }

    /** 是否有任何后台权限缺失（电池优化 / 悬浮窗） */
    fun needBackgroundPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations(context)) return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasOverlayPermission(context)) return true
        }
        return false
    }

    /** 引导用户开启「忽略电池优化」（不重复弹窗） */
    fun requestIgnoreBatteryIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations(context)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    // 兜底：直接跳 App 详情页
                    openAppDetails(context)
                }
            } catch (_: Exception) {
                openAppDetails(context)
            }
        }
    }

    /** 打开应用详情页 */
    private fun openAppDetails(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

// ==================== 请求后台运行权限 ====================

    /**
     * 引导用户开启后台运行权限。
     * 逐项尝试打开系统设置页，最后跳转应用详情页兜底。
     */
    fun requestBackgroundPermission(context: Context) {
        var opened = false

        // 1. 请求忽略电池优化 (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        opened = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. 悬浮窗权限检查 (Android 6+)
        if (!opened && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(context)) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        opened = true
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. 兜底：直接打开应用详情页（所有机型都支持）
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            opened = true
        } catch (_: Exception) {}

        if (!opened) {
            Toast.makeText(context, "无法打开系统设置，请手动前往设置中开启相关权限", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 第1层：核心保活 ====================

    /** 确保 TCP 服务运行 */
    fun ensureTcpService(context: Context) {
        try {
            TcpService.start(context)
        } catch (_: Exception) {}
    }

    // ==================== 第2层：1px 悬浮窗 ====================

    private fun start1pxFloating(context: Context) {
        try {
            context.startService(Intent(context, KeepAliveService::class.java))
        } catch (_: Exception) {}
    }

    private fun stop1pxFloating(context: Context) {
        try {
            context.stopService(Intent(context, KeepAliveService::class.java))
        } catch (_: Exception) {}
    }

    private fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                Toast.makeText(context, "请在设置中将「Aurora Chat」设为「无限制」以保持后台连接", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    // ==================== 第3层：AlarmManager 定时唤醒 ====================

    private fun scheduleAlarmWakeup(context: Context) {
        val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = "com.aurora.chat.KEEPALIVE_CHECK"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 使用 setAndAllowWhileIdle 让闹钟在 Doze 模式下也能触发
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + ALARM_INTERVAL_MS, pendingIntent)
        } else {
            alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + ALARM_INTERVAL_MS, ALARM_INTERVAL_MS, pendingIntent)
        }
        Log.i(TAG, "AlarmManager 定时唤醒已启动，间隔 ${ALARM_INTERVAL_MS / 60000} 分钟")
    }

    private fun cancelAlarmWakeup(context: Context) {
        val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = "com.aurora.chat.KEEPALIVE_CHECK"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmMgr.cancel(pendingIntent)
        Log.i(TAG, "AlarmManager 定时唤醒已取消")
    }

    /** 应用保活设置（启动时调用，自动恢复各层状态） */
    fun applySettings(context: Context) {
        if (isLayer1Enabled(context)) ensureTcpService(context)
        if (isLayer2Enabled(context)) start1pxFloating(context)
        if (isLayer3Enabled(context)) scheduleAlarmWakeup(context)
    }

    /** 注册网络切换监听（在应用启动时调用一次） */
    fun registerNetworkListener(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cmCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                Log.i(TAG, "网络已切换: ${if (isWifi) "WiFi" else "蜂窝数据"}")
                onNetworkChanged?.invoke(true, isWifi)
                // 网络切换时立即重置 TCP 连接
                ensureTcpService(context)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "网络已断开")
                onNetworkChanged?.invoke(false, false)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                onNetworkChanged?.invoke(hasInternet, isWifi)
                if (hasInternet) {
                    val now = System.currentTimeMillis()
                    if (now - lastNetworkEnsureMs > 1000) {
                        lastNetworkEnsureMs = now
                        ensureTcpService(context)
                    }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, cmCallback!!)
        Log.i(TAG, "网络切换监听已注册")
    }

    /** 取消网络切换监听 */
    fun unregisterNetworkListener(context: Context) {
        cmCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            cmCallback = null
        }
    }

    /**
     * 用户下拉刷新时调用：若 TCP 长连接已断开，则强制重建连接（stop→start），
     * 这样网络异常恢复后不用退出软件、直接下拉即可重新连上。TCP 在线时不操作。
     */
    fun tcpReconnectIfDisconnected(context: Context) {
        if (com.aurora.chat.TcpService.isTcpConnected()) return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                com.aurora.chat.TcpService.stop(context)
                com.aurora.chat.TcpService.start(context)
            } catch (_: Exception) {}
        }
    }
}

/** BroadcastReceiver — 接收 AlarmManager 定时唤醒信号 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.aurora.chat.KEEPALIVE_CHECK") {
            Log.i("KeepAlive", "AlarmManager 定时唤醒：检查 TCP 连接")
            // 检查 TCP 是否存活，断开则重启
            KeepAliveManager.ensureTcpService(context)

            // 重新调度下一次（setAndAllowWhileIdle 只触发一次）
            val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context, 2001, Intent(context, KeepAliveReceiver::class.java).apply {
                    action = "com.aurora.chat.KEEPALIVE_CHECK"
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60 * 1000L, pendingIntent)
            }
        }
    }
}
