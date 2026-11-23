package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.data.api.MessageInfo

/**
 * 「本地清理」水位线。
 *
 * 背景：本地清理只删本机数据、服务端历史原样保留。旧实现清完本地文件就结束了，
 * 于是重进会话 / 下拉刷新时又从 /api/messages 把整段历史拉回来并写进本地库，
 * 表现就是「清理完刷新一下，数据全回来了，本地清理只是障眼法」。
 *
 * 这里在清理那一刻记录一条分界线，之后所有历史拉取都只认界线之后的消息：
 *   1) [effectiveAfterId] 把分界线注入请求 after_id，服务端直接不返回旧消息（省流量）；
 *   2) [filterHistory] 在返回结果与落库前再挡一层，防止服务端/推送/旧分页绕过 after_id。
 *
 * 分界线优先用服务端消息 id（全局单调递增，不受本机与服务器时钟差影响）；
 * 只有「离线清理且本地库也没缓存」拿不到 id 时，才退化成按 createdAt 的秒级时间戳过滤。
 */
object LocalClearStore {
    private const val PREF = "local_clear_watermark_v1"
    private const val KEY_MAX_ID = "max_id_"
    private const val KEY_TIME_SEC = "time_sec_"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 记录一次本地清理。[maxId] 为清理瞬间服务端最大消息 id，拿不到时传 0。 */
    fun mark(ctx: Context, convId: Long, maxId: Long) {
        if (convId == 0L) return
        sp(ctx).edit()
            .putLong(KEY_MAX_ID + convId, maxId.coerceAtLeast(0L))
            .putLong(KEY_TIME_SEC + convId, System.currentTimeMillis() / 1000)
            .apply()
    }

    fun maxId(ctx: Context, convId: Long): Long =
        runCatching { sp(ctx).getLong(KEY_MAX_ID + convId, 0L) }.getOrDefault(0L)

    fun timeSec(ctx: Context, convId: Long): Long =
        runCatching { sp(ctx).getLong(KEY_TIME_SEC + convId, 0L) }.getOrDefault(0L)

    fun isCleared(ctx: Context, convId: Long): Boolean = timeSec(ctx, convId) > 0L

    /** 会话被服务端真正删除 / 从备份整体重置时撤掉水位线，避免误挡合法历史。 */
    fun clear(ctx: Context, convId: Long) {
        sp(ctx).edit()
            .remove(KEY_MAX_ID + convId)
            .remove(KEY_TIME_SEC + convId)
            .apply()
    }

    /** 历史请求统一走这里：水位线优先于调用方自带的增量游标。 */
    fun effectiveAfterId(ctx: Context, convId: Long, afterId: Long): Long {
        val mid = maxId(ctx, convId)
        return if (mid > 0L) maxOf(afterId, mid) else afterId
    }

    /** 丢掉「本地清理之前」的历史消息；本地合成的临时消息（id <= 0）不参与判定。 */
    fun filterHistory(ctx: Context, convId: Long, list: List<MessageInfo>): List<MessageInfo> {
        val mid = maxId(ctx, convId)
        val t = timeSec(ctx, convId)
        if (mid <= 0L && t <= 0L) return list
        return list.filter { m ->
            if (m.id <= 0L) return@filter true
            if (mid > 0L) m.id > mid
            else !(m.createdAt > 0L && m.createdAt <= t)
        }
    }
}
