package com.aurora.chat.ui.custom

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 进程内秒表。编辑器预览和系统悬浮窗服务读的是同一个状态，
 * 所以「秒表开始/暂停」「秒表归零」这类动作点了之后，画布上的 {timer} 会立刻有反馈。
 */
object HostTimer {
    @Volatile private var running = false
    @Volatile private var startMs = 0L
    @Volatile private var accumulatedMs = 0L

    fun toggle() {
        if (running) {
            accumulatedMs += System.currentTimeMillis() - startMs
            running = false
        } else {
            startMs = System.currentTimeMillis()
            running = true
        }
    }

    fun reset() {
        running = false
        accumulatedMs = 0L
        startMs = 0L
    }

    fun elapsedMs(): Long =
        accumulatedMs + if (running) System.currentTimeMillis() - startMs else 0L

    fun text(): String {
        val total = elapsedMs() / 1000L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}

/**
 * 文字元素里的动态变量。写 "{time} 电量 {battery}% 网速 {speed}" 就会在运行时被替换，
 * 不需要为每种数据单独做一种组件 —— 系统能力直接交给用户自己组合。
 *
 * 时钟 / 电量 / 剪贴板 / 秒表之外，还接了硬件与系统状态：
 * 网速、内存、存储、光感、音量、亮度、网络类型、充电、勿扰、旋转、常亮、手电筒状态。
 * 每个变量都单独兜异常，取不到就留空，绝不让一次读取把界面带崩。
 */
object DynamicValues {
    /**
     * Android 用的是 ICU 正则，字面 `}` 必须转义；写成 `\{(\w+)}` 在部分机型上会抛
     * PatternSyntaxException，且它发生在类初始化阶段（<clinit>），会让所有调用方在
     * 首帧组合时直接 ExceptionInInitializerError —— 也就是「一进去就闪退」。
     * 这里同时做两件事：转义 + 惰性安全构造，构造失败就降级成"不替换变量"，绝不崩。
     */
    private val pattern: Regex? = runCatching { Regex("\\{([A-Za-z_][A-Za-z_0-9]*)\\}") }.getOrNull()

    val hint = "文字里写 {time} 这类变量，运行时自动显示实时数据"

    /** 由悬浮窗服务写入的运行时状态（预览里显示的是本机默认状态）。 */
    @Volatile var torchOn: Boolean = false
    @Volatile var keepScreenOn: Boolean = false
    @Volatile var lightLux: Float = -1f

    @Volatile private var lastBytes = -1L
    @Volatile private var lastAt = 0L
    @Volatile private var speedText = "--"

    fun hasVariables(text: String): Boolean = pattern?.containsMatchIn(text) == true

    fun resolve(text: String, ctx: Context): String {
        val p = pattern ?: return text
        if (!hasVariables(text)) return text
        sampleSpeed()
        return runCatching {
            p.replace(text) { m ->
                runCatching { value(m.groupValues[1].lowercase(Locale.getDefault()), ctx) }
                    .getOrDefault("")
            }
        }.getOrDefault(text)
    }

    /** 一帧里多个文字元素同时解析时只采一次，避免网速被算出 0。 */
    private fun sampleSpeed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 900L) return
        val total = runCatching { TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes() }
            .getOrDefault(-1L)
        if (total >= 0L && lastBytes >= 0L && lastAt > 0L) {
            val dt = (now - lastAt).coerceAtLeast(1L)
            val kb = (total - lastBytes).coerceAtLeast(0L) * 1000f / dt / 1024f
            speedText = if (kb >= 1024f) String.format(Locale.getDefault(), "%.1f MB/s", kb / 1024f)
            else String.format(Locale.getDefault(), "%.0f KB/s", kb)
        }
        lastBytes = total
        lastAt = now
    }

    /** 不用正则，避免某些机型的 ICU 正则在类初始化时抛异常。 */
    private fun leadingNumber(s: String): Double? {
        val sb = StringBuilder()
        for (c in s.trim()) {
            if (c.isDigit() || c == '.' || c == '-' || c == '+') sb.append(c) else break
        }
        return sb.toString().toDoubleOrNull()
    }

    private fun value(key: String, ctx: Context): String = when (key) {
        "time" -> SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())
        "date" -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(System.currentTimeMillis())
        "week" -> SimpleDateFormat("EEEE", Locale.getDefault()).format(System.currentTimeMillis())
        "timer" -> HostTimer.text()
        "battery" -> battery(ctx)
        "charging" -> charging(ctx)
        "clipboard" -> clipboard(ctx)
        "speed" -> speedText
        "net" -> networkType(ctx)
        "wifi" -> wifiName(ctx)
        "volume" -> volume(ctx)
        "brightness" -> brightness(ctx)
        "light" -> if (lightLux >= 0f) String.format(Locale.getDefault(), "%.0f lx", lightLux) else "--"
        "mem" -> memory(ctx)
        "storage" -> storage()
        "screen" -> screen(ctx)
        "torch" -> if (torchOn) "开" else "关"
        "keepon" -> if (keepScreenOn) "开" else "关"
        "dnd" -> dnd(ctx)
        "rotation" -> rotation(ctx)
        "uptime" -> String.format(Locale.getDefault(), "%.1f h", SystemClock.elapsedRealtime() / 3_600_000f)
        "carrier" -> carrier(ctx)
        else -> ""
    }

    private fun battery(ctx: Context): String = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i == null) "" else {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) "" else (level * 100 / scale).toString()
        }
    }.getOrDefault("")

    private fun charging(ctx: Context): String = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0 > 0) "充电中" else "未充电"
    }.getOrDefault("")

    /** Android 10+ 限制后台读剪贴板，取不到就留空，不影响其它变量。 */
    private fun clipboard(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()?.trim()?.take(60).orEmpty()
    }.getOrDefault("")

    private fun networkType(ctx: Context): String = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "离线"
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "离线"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WLAN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            else -> "离线"
        }
    }.getOrDefault("")

    @Suppress("DEPRECATION")
    private fun wifiName(ctx: Context): String = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = wm?.connectionInfo?.ssid?.trim('"').orEmpty()
        if (ssid.isBlank() || ssid == "<unknown ssid>") "WLAN" else ssid
    }.getOrDefault("")

    private fun volume(ctx: Context): String = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return ""
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).toString() + "%"
    }.getOrDefault("")

    private fun brightness(ctx: Context): String = runCatching {
        val v = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        if (v < 0) "--" else (v * 100 / 255).toString() + "%"
    }.getOrDefault("")

    private fun memory(ctx: Context): String = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ""
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.availMem / 1024 / 1024).toString() + " MB"
    }.getOrDefault("")

    private fun storage(): String = runCatching {
        val fs = StatFs(Environment.getDataDirectory().path)
        String.format(Locale.getDefault(), "%.1f GB", fs.availableBytes / 1_073_741_824f)
    }.getOrDefault("")

    private fun screen(ctx: Context): String = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == true) "亮屏" else "灭屏"
    }.getOrDefault("")

    private fun dnd(ctx: Context): String = runCatching {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) "关" else "开"
    }.getOrDefault("")

    private fun rotation(ctx: Context): String = runCatching {
        val v = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1)
        if (v == 1) "自动" else "锁定"
    }.getOrDefault("")

    private fun carrier(ctx: Context): String = runCatching {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperatorName?.takeIf { it.isNotBlank() }.orEmpty()
    }.getOrDefault("")
}
