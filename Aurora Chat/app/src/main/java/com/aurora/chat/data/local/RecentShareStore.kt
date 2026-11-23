package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.data.api.AuroraApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 图片分享面板第一排的「最近分享过的人」。
 *
 * 只记「分享给站内会话」的目标，不记发到微信/QQ/系统分享（那些没有会话 id，也没法回投）。
 * 按分享时刻倒序保存：最近分享过的排在最左，同一目标再次分享会移到最前而不是重复出现。
 *
 * 按账号隔离文件名，换账号登录不会看到上一个人的分享对象。
 * 数据量最多 [MAX] 条，写独立 JSON 文件（临时文件 + rename 原子写），不进消息热路径。
 */
data class RecentShareTarget(
    /** 会话 id：私聊为正的用户 id，群聊为负的会话 id */
    val id: Long,
    val name: String,
    val isGroup: Boolean,
    /** 分享时刻（秒） */
    val time: Long
)

object RecentShareStore {

    private const val MAX = 8

    @Volatile
    private var cache: List<RecentShareTarget>? = null

    @Volatile
    private var cacheUid: Long = Long.MIN_VALUE

    private fun file(ctx: Context) = File(ctx.filesDir, "recent_shares_${AuroraApi.currentUserId}.json")

    /** 最近分享的排最前（列表从左到右就是这个顺序）。 */
    fun all(ctx: Context): List<RecentShareTarget> {
        val uid = AuroraApi.currentUserId
        val hit = cache
        if (hit != null && cacheUid == uid) return hit
        val list = try {
            val f = file(ctx)
            if (!f.exists()) emptyList()
            else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        val o = arr.getJSONObject(i)
                        RecentShareTarget(
                            id = o.optLong("id"),
                            name = o.optString("name", ""),
                            isGroup = o.optBoolean("isGroup", false),
                            time = o.optLong("time", 0L)
                        )
                    }.getOrNull()
                }.filter { it.id != 0L }.sortedByDescending { it.time }
            }
        } catch (_: Exception) {
            emptyList()
        }
        cache = list
        cacheUid = uid
        return list
    }

    /** 记一次分享：同 id 去重后置顶，最多保留 [MAX] 条。 */
    @Synchronized
    fun record(ctx: Context, target: RecentShareTarget) {
        try {
            val fresh = RecentShareTarget(
                id = target.id,
                name = target.name,
                isGroup = target.isGroup,
                time = if (target.time > 0L) target.time else System.currentTimeMillis() / 1000
            )
            val next = (listOf(fresh) + all(ctx).filter { it.id != fresh.id }).take(MAX)
            cache = next
            cacheUid = AuroraApi.currentUserId
            val arr = JSONArray()
            next.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("isGroup", it.isGroup)
                    put("time", it.time)
                })
            }
            val f = file(ctx)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    @Synchronized
    fun clear(ctx: Context) {
        cache = emptyList()
        cacheUid = AuroraApi.currentUserId
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
