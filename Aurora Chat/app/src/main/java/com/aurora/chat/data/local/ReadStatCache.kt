package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.data.api.ReadStat

/**
 * 已读状态本地缓存（messageId → 已读人数 / 对方已读时间）。
 *
 * 为什么要缓存：已读状态本身变化很慢（对方什么时候点开是低频事件），而气泡下方那行字
 * 是常驻渲染的。若每次进会话、每次滚动都去问服务端，一屏十几条就是十几个请求，
 * 纯烧带宽，而且请求回来之前那行字是空的 —— 视觉上就是「气泡先出来、已读状态后补上」。
 *
 * 做法：内存 + SharedPreferences 双写。
 *  - 进会话先拿缓存渲染，立刻有字；
 *  - 只对「缓存里没有」的消息发请求（新增消息），已读推送时再强制刷新最后一条；
 *  - 写盘上限 [MAX] 条，超出按插入顺序淘汰最旧的。
 */
object ReadStatCache {

    private const val SP = "read_stat_cache"
    private const val KEY = "entries"

    /** 单机最多缓存多少条。1500 条约 100KB 级别，够覆盖很长的会话，又不至于把 SP 撑爆。 */
    private const val MAX = 1500

    private val mem = LinkedHashMap<Long, ReadStat>()
    private var loaded = false

    @Synchronized
    private fun ensure(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val obj = org.json.JSONObject(raw)
            obj.keys().forEach { k ->
                val id = k.toLongOrNull() ?: return@forEach
                val parts = obj.optString(k, "").split(':')
                mem[id] = ReadStat(
                    messageId = id,
                    readCount = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    otherReadAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                )
            }
        }
    }

    /** 全量快照：会话打开时作为初始值，保证首帧就有数据，不等网络。 */
    @Synchronized
    fun all(context: Context): Map<Long, ReadStat> {
        ensure(context)
        return HashMap(mem)
    }

    @Synchronized
    fun get(context: Context, messageId: Long): ReadStat? {
        ensure(context)
        return mem[messageId]
    }

    /** 网络回来的一批结果写进来。同一 id 覆盖旧值并挪到队尾（LRU 语义）。 */
    @Synchronized
    fun putAll(context: Context, stats: Map<Long, ReadStat>) {
        if (stats.isEmpty()) return
        ensure(context)
        stats.forEach { (id, s) ->
            // 已读是单调的：人数只增不减、已读时间只会从 0 变成一个时刻。
            // 所以合并取 max —— 否则一个「早发出、晚返回」的旧请求会把刚变「已读」的状态
            // 又写回「未读」，界面就会来回跳。这是典型的乱序响应问题，必须在写入口拦掉。
            val old = mem[id]
            val merged = if (old == null) s else ReadStat(
                id,
                maxOf(old.readCount, s.readCount),
                maxOf(old.otherReadAt, s.otherReadAt)
            )
            mem.remove(id)
            mem[id] = merged
        }
        while (mem.size > MAX) {
            val it = mem.keys.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
        persist(context)
    }

    @Synchronized
    fun put(context: Context, stat: ReadStat) = putAll(context, mapOf(stat.messageId to stat))

    private fun persist(context: Context) {
        val obj = org.json.JSONObject()
        mem.forEach { (id, s) -> obj.put(id.toString(), "${s.readCount}:${s.otherReadAt}") }
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putString(KEY, obj.toString()).apply()
    }
}
