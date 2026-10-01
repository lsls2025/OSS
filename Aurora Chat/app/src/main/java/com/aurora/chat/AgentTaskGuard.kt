package com.aurora.chat

import android.content.Context

/**
 * AI 长任务（工具循环）的执行哨兵。
 *
 * 解决的问题：
 * AI 正在跑一个多步任务时，进程可能被系统回收（后台清理 / 内存不足 / ANR），
 * 也可能被用户或管家类应用「强行停止」。这类终止**不会**产生未捕获异常，
 * 所以 CrashHandler 抓不到、闪退拦截弹窗也不会弹——用户下次打开只看到
 * 「任务没做完」，却完全不知道是被谁、因为什么中断的。
 *
 * 做法：
 * 任务开始时落一个持久化标记，任务正常收尾（含出错退出）时清掉。
 * 下次启动时若标记仍在，说明上一轮是被强行掐断的——我们这边根本没机会跑到收尾代码，
 * 于是给出一条明确的说明。
 *
 * 诚实原则：只陈述「上次任务没有正常结束」这一事实，不推测是谁杀的，
 * 也不谎报任务成功或失败。
 */
object AgentTaskGuard {

    private const val PREFS = "agent_task_guard"
    private const val KEY_RUNNING = "running"
    private const val KEY_DESC = "desc"
    private const val KEY_STARTED_AT = "started_at"

    /**
     * 计数而不是布尔。
     *
     * 因为同一次 AI 回复会有多处嵌套的「进行中」区间（外层是工具循环、内层是每一轮流式请求），
     * 若用布尔，内层结束时就会把外层的标记一并清掉，导致外层区间被漏检。
     * 计数能保证「只要有任意一层还在跑」标记就保留。
     */
    private var depth = 0

    /** 进入一段「AI 正在处理」的区间（可嵌套，需与 [exit] 成对）。 */
    fun enter(ctx: Context, desc: String) {
        synchronized(this) { depth++ }
        try {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!p.getBoolean(KEY_RUNNING, false)) {
                p.edit()
                    .putBoolean(KEY_RUNNING, true)
                    .putString(KEY_DESC, desc.take(120))
                    .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                    .commit()
            } else {
                // 已有区间在进行中：只刷新描述，不重置起始时间
                p.edit().putString(KEY_DESC, desc.take(120)).apply()
            }
        } catch (_: Throwable) {}
    }

    /** 退出一段区间（可嵌套，需与 [enter] 成对）。 */
    fun exit(ctx: Context) {
        val left = synchronized(this) { if (depth > 0) --depth else 0 }
        if (left > 0) return      // 还有外层区间在跑，不要清标记
        try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .commit()
        } catch (_: Throwable) {}
    }

    // 兼容旧命名
    fun markRunning(ctx: Context, desc: String) = enter(ctx, desc)
    fun markFinished(ctx: Context) = exit(ctx)

    /**
     * 读取「上一轮任务是否被强行中断」，**读完即清除**，保证只提示一次。
     *
     * @return null 表示上次正常收尾；否则返回可直接展示给用户的中断说明
     */
    fun consumeAbnormalTermination(ctx: Context): String? {
        // 修复误报：进程内仍有任务在跑（depth > 0）说明任务并未被中断，只是 Activity/组合重建
        // （旋转、切后台回前台等都会重建组合并触发本方法）。此时直接返回 null、不消费标记，
        // 避免「AI 明明还在正常跑，却弹上一次的任务被中断」。
        // 只有进程内已无任何任务区间（depth == 0）且持久化标记仍在——即上一轮确实没跑到收尾——
        // 才判定为被中断并提示。
        val active = synchronized(this) { depth > 0 }
        if (active) return null

        val p = try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return null }
        if (!p.getBoolean(KEY_RUNNING, false)) return null

        val desc = p.getString(KEY_DESC, "").orEmpty()
        val startedAt = p.getLong(KEY_STARTED_AT, 0L)
        // 先清标记，避免同一次中断被反复提示
        p.edit().putBoolean(KEY_RUNNING, false).commit()
        synchronized(this) { depth = 0 }

        val elapsed = if (startedAt > 0L) {
            val sec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
            when {
                sec < 60 -> "${sec} 秒"
                sec < 3600 -> "${sec / 60} 分钟"
                else -> "${sec / 3600} 小时"
            }
        } else ""

        val who = if (desc.isBlank()) "上一次的 AI 任务" else "上一次的 AI 任务（$desc）"
        return who + "没有正常结束" + (if (elapsed.isNotEmpty()) "，运行 $elapsed 后就被中断了" else "") + "。\n\n" +
            "可能的原因：\n" +
            "1. 应用被切到后台，随后被系统清理掉（后台内存不足时最常见的死法）；\n" +
            "2. 被安全软件 / 手机管家的「一键清理」「强行停止」掐掉；\n" +
            "3. 任务耗时过长触发了系统 ANR；\n" +
            "4. 或流程自身出错提前退出。\n\n" +
            "需要说明的是：这次中断**不是代码崩溃**，所以不会有闪退报告。任务需要在应用里重新发起一次。"
    }

    /** 仅供排查：当前是否标记为「有任务在跑」。 */
    fun isRunning(ctx: Context): Boolean = try {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RUNNING, false)
    } catch (_: Throwable) { false }
}
