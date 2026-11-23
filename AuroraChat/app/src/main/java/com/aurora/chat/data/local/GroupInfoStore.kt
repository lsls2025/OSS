package com.aurora.chat.data.local

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 群信息缓存：按 internalGroupId 持久化「群资料」（display_id / name / signature / avatar_url 等），
 * 与 UserProfileStore 同一思路——看见过一次就落盘，之后进群设置/群二维码断网也照样能显示群号。
 *
 * 为什么需要它：群号（display_id）以前只来自 AuroraApi.getGroupInfo 的实时返回，零持久化，
 * 断网时 groupInfo 为空 → displayId=0 → 群二维码页面拿不到群号、群设置页群号一栏空白。
 * 现在联网成功即写穿，断网/请求失败时由 ChatRepository 直接拿它当结果返回。
 *
 * 设计要点：
 *  - 内存 ConcurrentHashMap 供渲染热路径同步读（get 不碰磁盘）；
 *  - 磁盘单文件 JSON + 原子 tmp/rename，写盘丢到单线程 executor，不阻塞主线程；
 *  - 内容未变不写盘（群设置页每次进都会调，不能每次重写）；
 *  - 不做 TTL：群资料改了由下次联网结果覆盖，缓存永远能兜底；
 *  - 上限 2000 个群，超限按 updatedAt 淘汰最旧的一批。
 */
object GroupInfoStore {
    private const val FILE_NAME = "group_info_cache.json"
    private const val MAX_ENTRIES = 2000
    private val lock = Any()
    private val map = ConcurrentHashMap<Long, Entry>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "group-info-cache").apply { isDaemon = true }
    }

    @Volatile private var loaded = false

    data class Entry(
        val groupId: Long,
        val data: JSONObject,
        val updatedAt: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("gid", groupId)
            put("data", data)
            put("ts", updatedAt)
        }
    }

    private fun fromJson(o: JSONObject): Entry? {
        val gid = o.optLong("gid", 0L)
        if (gid <= 0L) return null
        val d = o.optJSONObject("data") ?: return null
        return Entry(groupId = gid, data = d, updatedAt = o.optLong("ts", 0L))
    }

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                val f = file(ctx)
                if (f.exists()) {
                    val root = JSONObject(f.readText())
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val e = fromJson(root.optJSONObject(keys.next()) ?: continue) ?: continue
                        map[e.groupId] = e
                    }
                }
            } catch (_: Exception) { }
            loaded = true
        }
    }

    private fun file(ctx: Context): File =
        File(ctx.applicationContext.filesDir, FILE_NAME)

    private fun ensure(ctx: Context) {
        if (!loaded) init(ctx)
    }

    /** 断网兜底：返回缓存的群资料（含 display_id），没有则返回 null。 */
    fun get(ctx: Context, groupId: Long): JSONObject? {
        ensure(ctx)
        return map[groupId]?.data
    }

    /** 渲染热路径：同步读群号，不碰磁盘。 */
    fun displayIdOf(groupId: Long): Long = map[groupId]?.data?.optLong("display_id", 0L) ?: 0L

    fun nameOf(groupId: Long): String = map[groupId]?.data?.optString("name", "").orEmpty()

    private fun merge(e: Entry): Boolean {
        val old = map[e.groupId]
        if (old != null) {
            // 内容没变（data 同字符串）就不写盘
            if (old.data.toString() == e.data.toString() && old.updatedAt == e.updatedAt) return false
            map[e.groupId] = e
        } else {
            map[e.groupId] = e
        }
        return true
    }

    private fun flush(ctx: Context) {
        val snapshot = HashMap(map)
        if (snapshot.size > MAX_ENTRIES) {
            val keepIds = snapshot.values
                .sortedByDescending { it.updatedAt }
                .take(MAX_ENTRIES * 4 / 5)
                .mapTo(HashSet()) { it.groupId }
            map.keys.removeAll { it !in keepIds }
        }
        val finalSnapshot = HashMap(map)
        writer.execute {
            try {
                val root = JSONObject()
                for ((gid, e) in finalSnapshot) root.put(gid.toString(), e.toJson())
                val f = file(ctx)
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(f)
            } catch (_: Exception) { }
        }
    }

    /** 写入一份群资料（data 为后端 /api/groups/info 返回的 JSONObject）。 */
    fun put(ctx: Context, groupId: Long, data: JSONObject) {
        ensure(ctx)
        val now = System.currentTimeMillis()
        if (merge(Entry(groupId = groupId, data = data, updatedAt = now))) flush(ctx)
    }

    /** 账号切换/登出时清空，避免把上个账号所在的群留给下个账号。 */
    fun clear(ctx: Context) {
        synchronized(lock) {
            map.clear()
            try { file(ctx).delete() } catch (_: Exception) { }
        }
    }
}
