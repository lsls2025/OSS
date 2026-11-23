package com.aurora.chat.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 闹钟/定时提醒调度器（AI 工具 "set_alarm" 的底层实现）。
 *
 * 设计要点：
 *  - 真正落成系统级 AlarmManager 闹钟（[AlarmManager.setAlarmClock]），状态栏出现闹钟图标，
 *    进程被杀、手机重启都仍然有效（重启由 [AlarmBootReceiver] 重新排程）。
 *  - 权限是硬闸门：未授予「闹钟和提醒」（精确闹钟）或通知权限时，[schedule] / [scheduleAt]
 *    直接返回 null，不落库也不排程——绝不允许"降级成非精确闹钟还报成功"。
 *  - 到点只做两件事：发高优先级通知（闹钟铃声 + 震动）+ 重新排下一次（周期闹钟）。
 *    不拉全屏 Activity，避免在用户正在用别的 App 时被强行抢焦点。
 *  - 全部数据落 SharedPreferences（每个账号共享本机闹钟，闹钟本身与账号无关）。
 */
object AlarmScheduler {
    private const val PREFS = "aurora_alarm_store"
    private const val KEY = "alarms"
    const val CHANNEL_ID = "aurora_agent_alarm"
    const val EXTRA_ID = "aurora_alarm_id"
    const val EXTRA_LABEL = "aurora_alarm_label"

    /** ISO 星期：1=周一 … 7=周日；days 为空表示只响一次。 */
    data class AlarmInfo(
        val id: Int,
        val atMillis: Long,
        val hour: Int,
        val minute: Int,
        val label: String,
        val days: List<Int> = emptyList()
    )

    // ---------- 渠道 ----------

    /** 创建闹钟通知渠道（高重要性 + 闹钟铃声 + 震动 + 绕过免打扰）。 */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ch = NotificationChannel(
            CHANNEL_ID, "闹钟提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AI 为你设置的闹钟与定时提醒"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            setSound(
                soundUri,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /** Android 12+ 精确闹钟权限是否可用（未授予时不允许建闹钟）。 */
    fun canScheduleExact(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true
    } catch (_: Exception) {
        false
    }

    /** Android 13+ 通知权限（没有通知权限 = 到点什么提示都看不到，等同于闹钟失效）。 */
    fun canNotify(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
        else androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /**
     * 建闹钟前的权限闸门：精确闹钟 + 通知，两项都授予才允许真正设置。
     * 任一缺失即视为失败（由调用方向用户要授权），而不是降级后报成功。
     */
    data class PermState(val exact: Boolean, val notify: Boolean) {
        val ok: Boolean get() = exact && notify
        fun missing(): List<String> = buildList {
            if (!exact) add("「闹钟和提醒」精确闹钟权限")
            if (!notify) add("通知权限")
        }
        fun missingText(): String = missing().joinToString("、")
    }

    fun permState(ctx: Context): PermState = PermState(canScheduleExact(ctx), canNotify(ctx))

    /** 打开系统「闹钟和提醒」授权页（Android 12+ 需要用户手动打开，无法用运行时弹窗申请）。 */
    fun requestExactPermission(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExact(ctx)) {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } else false
    } catch (_: Exception) {
        false
    }

    // ---------- 时间计算 ----------

    /** 下一次 hour:minute 的触发时间（今天已过则顺延到明天）。 */
    fun nextTrigger(hour: Int, minute: Int, from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** 下一次落在 days 指定星期上的触发时间（严格晚于 after）。 */
    fun nextWeekdayTrigger(
        hour: Int,
        minute: Int,
        days: List<Int>,
        after: Long = System.currentTimeMillis()
    ): Long {
        if (days.isEmpty()) return nextTrigger(hour, minute, after)
        for (i in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = after
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, i)
            }
            if (c.timeInMillis <= after) continue
            val dow = c.get(Calendar.DAY_OF_WEEK)
            val iso = if (dow == Calendar.SUNDAY) 7 else dow - 1
            if (days.contains(iso)) return c.timeInMillis
        }
        // 兜底：一周内必然命中，走到这里说明 days 非法，按明天算
        return nextTrigger(hour, minute, after)
    }

    // ---------- 增删查 ----------

    /**
     * 设置闹钟。权限不齐时返回 null，且**不落库、不排程、不降级**。
     * 返回非 null 表示已经真正落成系统精确闹钟。
     */
    fun schedule(ctx: Context, hour: Int, minute: Int, label: String, days: List<Int>): AlarmInfo? {
        if (!permState(ctx).ok) return null
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        val at = nextWeekdayTrigger(h, m, days)
        val info = AlarmInfo(id, at, h, m, label.ifBlank { "闹钟" }, days.distinct().sorted())
        if (!arm(ctx, info)) return null
        ensureChannel(ctx)
        save(ctx, load(ctx) + info)
        return info
    }

    /** 按绝对时间排程（用于「N 分钟后提醒我」这类相对时间，避免被 0-59 分取整绕晕）。 */
    fun scheduleAt(
        ctx: Context,
        atMillis: Long,
        label: String,
        days: List<Int> = emptyList()
    ): AlarmInfo? {
        if (!permState(ctx).ok) return null
        val cal = Calendar.getInstance().apply { timeInMillis = atMillis }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        val info = AlarmInfo(id, atMillis, h, m, label.ifBlank { "闹钟" }, days.distinct().sorted())
        if (!arm(ctx, info)) return null
        ensureChannel(ctx)
        save(ctx, load(ctx) + info)
        return info
    }

    fun cancel(ctx: Context, id: Int): Boolean {
        val list = load(ctx)
        val target = list.firstOrNull { it.id == id } ?: return false
        cancelPending(ctx, target.id)
        save(ctx, list.filterNot { it.id == id })
        return true
    }

    fun cancelAll(ctx: Context): Int {
        val list = load(ctx)
        list.forEach { cancelPending(ctx, it.id) }
        save(ctx, emptyList())
        return list.size
    }

    fun list(ctx: Context): List<AlarmInfo> = load(ctx).sortedBy { it.atMillis }

    // ---------- 排程 ----------

    /**
     * 真正排程：只用 [AlarmManager.setAlarmClock]（系统闹钟图标 + 灭屏/省电也能准时响）。
     * 权限未授予或系统拒绝时返回 false，由上层如实报失败——这里不再降级成非精确闹钟。
     */
    private fun arm(ctx: Context, info: AlarmInfo): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        if (!canScheduleExact(ctx)) return false
        val pi = triggerIntent(ctx, info)
        val showIntent = PendingIntent.getActivity(
            ctx, info.id,
            Intent(ctx, com.aurora.chat.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(info.atMillis, showIntent), pi)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun triggerIntent(ctx: Context, info: AlarmInfo): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ID, info.id)
            putExtra(EXTRA_LABEL, info.label)
        }
        return PendingIntent.getBroadcast(
            ctx, info.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPending(ctx: Context, id: Int) {
        val pi = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pi)
        }
    }

    // ---------- 到点 ----------

    /** 闹钟触发：发通知 + 震动；周期闹钟自动排下一次，一次性闹钟移除记录。 */
    fun onFired(ctx: Context, id: Int, labelFallback: String) {
        val list = load(ctx)
        val target = list.firstOrNull { it.id == id }
        val label = target?.label?.takeIf { it.isNotBlank() } ?: labelFallback.ifBlank { "闹钟" }
        notifyNow(ctx, id, label)
        if (target != null && target.days.isNotEmpty()) {
            val next = nextWeekdayTrigger(target.hour, target.minute, target.days, System.currentTimeMillis() + 60_000L)
            val renewed = target.copy(atMillis = next)
            save(ctx, list.map { if (it.id == id) renewed else it })
            arm(ctx, renewed)
        } else {
            save(ctx, list.filterNot { it.id == id })
        }
    }

    private fun notifyNow(ctx: Context, id: Int, label: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val alarmFor = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(System.currentTimeMillis()))
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, com.aurora.chat.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(com.aurora.chat.R.mipmap.ic_launcher)
            .setContentTitle("闹钟 · $alarmFor")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(90_000 + (id and 0xFFF), n) }

        // 震动（部分机型静音/勿扰下通道不会震，这里再显式补一次）
        runCatching {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching
            if (!vib.hasVibrator()) return@runCatching
            val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
        }
    }

    /** 开机后重新排程：周期闹钟按下一个周期重算，过期的一次性闹钟直接清掉。 */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val kept = mutableListOf<AlarmInfo>()
        load(ctx).forEach { info ->
            val renewed = if (info.days.isNotEmpty()) {
                info.copy(atMillis = nextWeekdayTrigger(info.hour, info.minute, info.days, now + 1_000L))
            } else {
                info
            }
            if (renewed.atMillis > now) {
                kept.add(renewed)
                arm(ctx, renewed)
            }
        }
        save(ctx, kept)
    }

    // ---------- 持久化 ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): List<AlarmInfo> = try {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            AlarmInfo(
                id = o.optInt("id"),
                atMillis = o.optLong("at"),
                hour = o.optInt("h"),
                minute = o.optInt("m"),
                label = o.optString("label"),
                days = o.optJSONArray("days")?.let { d ->
                    (0 until d.length()).map { d.optInt(it) }.filter { it in 1..7 }
                } ?: emptyList()
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, list: List<AlarmInfo>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(
                    JSONObject()
                        .put("id", a.id)
                        .put("at", a.atMillis)
                        .put("h", a.hour)
                        .put("m", a.minute)
                        .put("label", a.label)
                        .put("days", JSONArray(a.days))
                )
            }
            prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        }
    }

    /** 展示用：重复描述。 */
    fun repeatText(days: List<Int>): String = when {
        days.isEmpty() -> "只响一次"
        days.size == 7 -> "每天"
        days.toSet() == setOf(1, 2, 3, 4, 5) -> "工作日"
        days.toSet() == setOf(6, 7) -> "周末"
        else -> days.joinToString("、") { d ->
            when (d) {
                1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
                5 -> "周五"; 6 -> "周六"; else -> "周日"
            }
        }
    }
}

/** 闹钟到点广播：短暂持唤醒锁，保证灭屏时也能发通知。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, 0)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL).orEmpty()
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aurora:agent_alarm")?.apply {
                setReferenceCounted(false)
                acquire(10_000L)
            }
        }.getOrNull()
        try {
            AlarmScheduler.onFired(context.applicationContext, id, label)
        } catch (_: Exception) {
            // 广播里绝不抛异常，否则会被系统判定为崩溃
        } finally {
            runCatching { if (wl?.isHeld == true) wl.release() }
        }
    }
}

/** 重启后恢复闹钟（AlarmManager 的闹钟在重启后会全部丢失）。 */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        runCatching { AlarmScheduler.rescheduleAll(context.applicationContext) }
    }
}
