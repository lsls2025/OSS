package com.aurora.chat

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * 全局崩溃拦截器（重写版）。
 *
 * 设计原则：
 * 1. 诚实 —— 绝不在崩溃当下谎报“拦截成功”。是否真正自愈（自动重启并恢复运行）
 *    只能由“重启后进程是否再次起来”来证明，因此成功标志只在【确认重启成功】时写入。
 * 2. 兜底 —— 若自身处理逻辑抛异常，则退回系统默认 handler（如实弹系统崩溃框），
 *    绝不掩盖真实崩溃。
 * 3. 后台线程异常不视为闪退 —— TCP 保活等后台线程异常是运行期常态，仅记录，不自杀。
 */
object CrashHandler {

    private const val TAG = "CrashHandler"
    private const val PREFS = "crash_stats"
    private const val KEY_TOTAL = "total_crashes"
    private const val KEY_PENDING_RESTART = "crash_pending_restart"
    private const val KEY_LAST_INTERCEPTED = "last_intercepted"
    private const val KEY_LAST_CRASH_TYPE = "last_crash_type"
    private const val KEY_LAST_CRASH_MSG = "last_crash_msg"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"

    fun init(application: Application) {
        // 必须在接管前先捕获系统默认 handler，否则兜底分支会无限递归调用自身
        val systemDefault = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val handledInApp = runCatching { handleCrash(application, thread, throwable) }.getOrDefault(false)
            if (!handledInApp) {
                // 自身处理失败 → 如实交给系统默认 handler（弹系统“已停止运行”崩溃框）
                try { systemDefault?.uncaughtException(thread, throwable) } catch (_: Throwable) {}
            }
        }
        Log.i(TAG, "CrashHandler 初始化完成，已接管全部线程未捕获异常")
    }

    /**
     * @return true 表示已在 App 内处理（不再交给系统 handler）；
     *         false 表示交给系统默认 handler（如实弹系统崩溃框）。
     */
    private fun handleCrash(app: Application, thread: Thread, throwable: Throwable): Boolean {
        val isMain = Looper.getMainLooper().thread === thread

        // ---- 后台线程：记录为异常，不视为闪退，吞掉，进程继续存活 ----
        if (!isMain) {
            runQuietly {
                ErrorReporter.warn(
                    tag = "BgThread",
                    message = "后台线程(${thread.name})未捕获异常: ${throwable.javaClass.simpleName}: ${throwable.message?.take(200) ?: ""}",
                    throwable = throwable
                )
            }
            return true
        }

        // ---- 主线程：真正的闪退 ----
        persistCrashCount(app)

        // 诚实标记：发生过崩溃并尝试重启。此时【尚不能断言“成功”】，成功与否交由重启后判定。
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_PENDING_RESTART, true)
            putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
            putString(KEY_LAST_CRASH_TYPE, throwable.javaClass.name)
            putString(KEY_LAST_CRASH_MSG, throwable.message?.take(200) ?: "")
            // 注意：不在此处写 last_intercepted，避免谎报成功
            commit()
        }

        // 尽力调度自动重启（仅作为“尝试”，不据此判定成功）
        val restartScheduled = runCatching { scheduleRestart(app) }.getOrDefault(false)

        // 诚实记录崩溃日志：描述“已捕获 + 是否已尝试调度重启”，绝不写“拦截成功”
        ErrorReporter.fatal(
            tag = "Crash",
            message = buildString {
                append("主线程未捕获异常已被捕获 | 线程:${thread.name}(${thread.id})")
                append(" | 已尝试调度自动重启:$restartScheduled")
                append(" | ${throwable.javaClass.simpleName}: ${throwable.message?.take(200) ?: ""}")
            },
            throwable = throwable
        )

        // 终结当前进程（进程死亡是既定事实；若重启成功，下次启动会写“拦截成功”）
        Process.killProcess(Process.myPid())
        // 返回 true：不交给系统 handler，避免系统“已停止运行”崩溃框；由 Alarm 于 500ms 后拉起新进程
        return true
    }

    /**
     * 在 MainActivity 冷启动早期、且本次是由崩溃重启意图(from_crash)拉起时调用。
     * 只有“确实被我们重启拉起”才证明自愈成功，此时才写 last_intercepted=true，
     * 使弹窗“闪退拦截成功”名副其实；其余情况一律不写，绝不通报假成功。
     */
    fun confirmRestartIfNeeded(context: Context, fromCrash: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (fromCrash && prefs.getBoolean(KEY_PENDING_RESTART, false)) {
            prefs.edit()
                .putBoolean(KEY_LAST_INTERCEPTED, true)
                .putBoolean(KEY_PENDING_RESTART, false)
                .commit()
        }
    }

    private fun scheduleRestart(app: Application) {
        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("from_crash", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            app, 10086, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmMgr = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // setAndAllowWhileIdle：无需 SCHEDULE_EXACT_ALARM，比 set() 更可靠，避免 OEM 延迟拉起
        alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 500L, pendingIntent)
    }

    private fun persistCrashCount(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = prefs.getInt(KEY_TOTAL, 0)
        prefs.edit().putInt(KEY_TOTAL, cur + 1).apply()
    }

    private inline fun runQuietly(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    // ======================== 对外查询 ========================

    fun getCrashCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TOTAL, 0)

    /**
     * 最近一次闪退是否“确认自愈成功”。
     * 仅当本次启动确由崩溃重启意图拉起（confirmRestartIfNeeded 已判定）时才为 true。
     */
    fun getLastIntercepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAST_INTERCEPTED, false)

    fun resetCrashCount(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_TOTAL, 0).apply()
    }
}
