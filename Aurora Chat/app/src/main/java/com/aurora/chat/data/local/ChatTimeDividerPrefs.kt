package com.aurora.chat.data.local

import android.content.Context

/**
 * 被用户长按「删除」掉的时间分隔。
 *
 * 时间分隔不是消息，是渲染期按相邻消息间隔派生出来的，所以「删除」只能记一条墓碑：
 * 记住「这条消息上方不再显示时间」。纯本地显示偏好，不上服务器（换机后按规则重新出现是正常的）。
 */
object ChatTimeDividerPrefs {

    private const val SP = "chat_time_divider_hidden"

    /** 单会话最多记 500 条墓碑，超出丢最早的，防止无限膨胀。 */
    private const val MAX_KEEP = 500

    fun load(ctx: Context, convKey: String): Set<Long> {
        val raw = sp(ctx).getString(convKey, null)
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun hide(ctx: Context, convKey: String, msgId: Long) {
        if (msgId <= 0L) return
        val cur = load(ctx, convKey)
        if (msgId in cur) return
        val all = ArrayList<Long>(cur.size + 1).apply {
            addAll(cur)
            add(msgId)
        }
        val trimmed = if (all.size > MAX_KEEP) all.subList(all.size - MAX_KEEP, all.size) else all
        sp(ctx).edit().putString(convKey, trimmed.joinToString(",")).apply()
    }

    fun clear(ctx: Context, convKey: String) {
        sp(ctx).edit().remove(convKey).apply()
    }

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
}
