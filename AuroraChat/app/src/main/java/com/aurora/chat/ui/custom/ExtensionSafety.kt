package com.aurora.chat.ui.custom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.aurora.chat.ErrorReporter

/**
 * 自定义模块的安全兜底。
 *
 * 这里解决的是「用户随手做一个操作，极端情况下整包数据/渲染炸掉，整个 App 跟着闪退」：
 * 1. [guard] 把每个可能出错的操作包起来 —— 失败只丢弃这一次操作、给出提示、写错误日志，
 *    绝不把异常抛到主线程消息循环。
 * 2. 出错的扩展被记进 [badIds]，启动时不再自动恢复它，避免「一进去就闪退」无限循环。
 * 3. 启动期 / 渲染期这类会影响首帧的关键异常会打开 [isSafeMode]：
 *    下次冷启动跳过启动画面与悬浮窗恢复，管理页顶部显示可手动解除的提示条。
 *
 * 提示一律走 Toast（主线程排队显示）与 ErrorReporter 错误日志，用户可以在错误日志页复查。
 */
object ExtensionSafety {
    private const val PREF = "aurora_custom_safety"
    private const val KEY_SAFE_MODE = "safe_mode"
    private const val KEY_REASON = "reason"
    private const val KEY_BAD_IDS = "bad_ids"
    private const val KEY_LAST_TIP = "last_tip"
    private const val KEY_LAST_TIME = "last_time"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isSafeMode(ctx: Context): Boolean = runCatching {
        prefs(ctx).getBoolean(KEY_SAFE_MODE, false)
    }.getOrDefault(false)

    fun reason(ctx: Context): String = runCatching {
        prefs(ctx).getString(KEY_REASON, "") ?: ""
    }.getOrDefault("")

    /** 最近一次被兜住的异常摘要，管理页用它显示提示条。 */
    fun lastIncident(ctx: Context): Pair<String, Long>? = runCatching {
        val p = prefs(ctx)
        val tip = p.getString(KEY_LAST_TIP, "") ?: ""
        val t = p.getLong(KEY_LAST_TIME, 0L)
        if (tip.isEmpty()) null else tip to t
    }.getOrNull()

    /** 进入保护模式：启动层与悬浮窗恢复全部跳过，直到用户在自定义页手动解除。 */
    fun enterSafeMode(ctx: Context, reason: String) {
        runCatching {
            prefs(ctx).edit()
                .putBoolean(KEY_SAFE_MODE, true)
                .putString(KEY_REASON, reason.take(120))
                .apply()
        }
    }

    /** 解除保护模式，同时清掉坏扩展隔离标记，让配置重新生效。 */
    fun clearSafeMode(ctx: Context) {
        runCatching {
            prefs(ctx).edit()
                .putBoolean(KEY_SAFE_MODE, false)
                .remove(KEY_REASON)
                .remove(KEY_BAD_IDS)
                .apply()
        }
    }

    fun badIds(ctx: Context): Set<String> = runCatching {
        (prefs(ctx).getString(KEY_BAD_IDS, "") ?: "")
            .split('|').filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    fun isBad(ctx: Context, id: String): Boolean = id.isNotBlank() && id in badIds(ctx)

    /** 用户主动重试某个扩展 = 明确授权解除它的隔离。 */
    fun unmarkBad(ctx: Context, id: String) {
        if (id.isBlank()) return
        runCatching {
            val next = (badIds(ctx) - id).joinToString("|")
            prefs(ctx).edit().putString(KEY_BAD_IDS, next).apply()
        }
    }

    /** 隔离反复出错的扩展；管理页仍能看到并删除它，只是不再自动加载/渲染。 */
    fun markBad(ctx: Context, id: String, reason: String) {
        if (id.isBlank()) return
        runCatching {
            val next = (badIds(ctx) + id).joinToString("|")
            prefs(ctx).edit()
                .putString(KEY_BAD_IDS, next)
                .putString(KEY_REASON, reason.take(120))
                .apply()
        }
    }

    /**
     * 统一兜底入口。返回 [fallback] 而不是抛出异常。
     *
     * @param critical true 表示这是启动/渲染链路上的异常，失败后进入保护模式；
     *                 false 表示只是一次普通编辑操作，就地跳过即可。
     */
    fun <T> guard(
        ctx: Context,
        tag: String,
        fallback: T,
        critical: Boolean = false,
        badId: String? = null,
        block: () -> T
    ): T = try {
        block()
    } catch (t: Throwable) {
        onFailure(ctx, tag, t, critical, badId)
        fallback
    }

    /** 只关心成功与否的版本。 */
    fun guardRun(
        ctx: Context,
        tag: String,
        critical: Boolean = false,
        badId: String? = null,
        block: () -> Unit
    ): Boolean = guard(ctx, tag, false, critical, badId) {
        block()
        true
    }

    /** 给全局主线程守卫调用：异常已经发生了，只做记录、提示与降级。 */
    fun onUnhandled(ctx: Context, tag: String, t: Throwable, critical: Boolean = true) {
        onFailure(ctx, tag, t, critical, null)
    }

    private fun onFailure(
        ctx: Context,
        tag: String,
        t: Throwable,
        critical: Boolean,
        badId: String?
    ) {
        val detail = buildString {
            append(tag)
            append(" 已跳过：")
            append(t.javaClass.simpleName)
            t.message?.take(100)?.let { append(" ").append(it) }
        }
        runCatching {
            ErrorReporter.warn(tag = "CustomSafety", message = detail, throwable = t)
        }
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_LAST_TIP, detail.take(120))
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                .apply()
        }
        badId?.let { markBad(ctx, it, detail) }
        if (critical) enterSafeMode(ctx, detail)
        toast(ctx, "操作异常已跳过，已记入错误日志")
    }

    /** 统一提示；非主线程也能调，Toast 排到主线程显示。 */
    fun toast(ctx: Context, text: String) {
        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            } else {
                Handler(Looper.getMainLooper()).post {
                    runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}
