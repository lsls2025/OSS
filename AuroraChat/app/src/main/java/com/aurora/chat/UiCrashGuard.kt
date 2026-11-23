package com.aurora.chat

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.aurora.chat.ui.custom.ExtensionSafety
import kotlinx.coroutines.CancellationException
import java.util.ArrayDeque

/**
 * 主线程消息级防闪退保护。
 *
 * [CrashHandler] 处理的是「异常已经逃出主线程消息循环」的情况 —— 那时进程必须重启，
 * 用户看到的是一次闪退。这里补的是更早一层：在主线程 Looper 外面再套一层循环，
 * 某次点击 / 重组 / 测量里抛出的异常只让**那一条消息**失败，被丢弃并提示，
 * 界面继续运行，用户不会看到 App 退出。
 *
 * 安全边界（避免把真问题藏起来）：
 * - VirtualMachineError / OOM / ThreadDeath 不吞，交回系统既有崩溃处理；
 * - 5 秒内连续超过 3 次异常说明已经进入坏状态，停止兜底，交回 [CrashHandler] 重启；
 * - 每次兜住都写 ErrorReporter 日志 + Toast 提示；涉及自定义模块的额外打开保护模式，
 *   避免下一次冷启动又被同一份坏配置带崩。
 */
object UiCrashGuard {
    private const val TAG = "UiCrashGuard"
    private const val WINDOW_MS = 5_000L
    private const val MAX_IN_WINDOW = 3

    @Volatile private var installed = false
    @Volatile private var gaveUp = false
    private val lock = Any()
    private val recent = ArrayDeque<Long>()

    fun install(app: Application) {
        if (installed) return
        installed = true
        // 排到当前启动消息之后：此刻 Application.onCreate 还在外层 loop 的 dispatch 中，
        // 等它返回后由内层 loop 接管后续所有主线程消息（含 Activity 创建、Compose 首帧）。
        Handler(Looper.getMainLooper()).post {
            Log.i(TAG, "主线程消息保护已接管")
            while (!gaveUp) {
                try {
                    Looper.loop()
                    return@post
                } catch (t: Throwable) {
                    if (!recover(app, t)) {
                        gaveUp = true
                        runCatching {
                            Thread.getDefaultUncaughtExceptionHandler()
                                ?.uncaughtException(Thread.currentThread(), t)
                        }
                        return@post
                    }
                }
            }
        }
    }

    /** @return true = 已就地兜住，继续跑；false = 放弃兜底，交回系统崩溃流程。 */
    private fun recover(app: Application, t: Throwable): Boolean {
        // 协程取消异常（含 JobCancellationException）不是 UI 错误：它是正常生命周期
        // （离开界面 / 作用域取消 / 列表刷新被中断）。绝不能把它当「操作触发异常」弹给用户，
        // 也绝不能计入 5 秒阈值——否则正常取消会被误判为坏状态而放弃兜底。
        if (isCancellation(t)) return true

        // 虚拟机级错误不能吞：吞掉只会让进程处于更糟的半死状态
        if (t is VirtualMachineError || t is ThreadDeath) return false

        val now = System.currentTimeMillis()
        val count = synchronized(lock) {
            while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) recent.removeFirst()
            recent.addLast(now)
            recent.size
        }
        if (count > MAX_IN_WINDOW) return false

        val stack = stackTrace(t)
        val fromCustom = stack.contains("com.aurora.chat.ui.custom") ||
            (t is ExceptionInInitializerError && stack.contains("DynamicValues"))

        runCatching {
            ErrorReporter.warn(
                tag = TAG,
                message = "已跳过一条主线程消息（$count/$MAX_IN_WINDOW）：" +
                    "${t.javaClass.simpleName}: ${t.message?.take(160) ?: ""}",
                throwable = t
            )
        }

        if (fromCustom) {
            // 自定义模块异常：额外进保护模式，下次冷启动不再自动恢复坏配置
            runCatching { ExtensionSafety.onUnhandled(app, "自定义界面", t, critical = true) }
        } else {
            runCatching {
                Toast.makeText(app, "刚才的操作触发异常，已跳过并记入错误日志", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun isCancellation(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is CancellationException) return true
            cur = cur.cause
        }
        return false
    }

    private fun stackTrace(t: Throwable): String = buildString {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 5) {
            append(cur.javaClass.name).append('\n')
            cur.stackTrace.take(40).forEach {
                append(it.className).append('.').append(it.methodName).append('\n')
            }
            cur = cur.cause
            depth++
        }
    }
}
