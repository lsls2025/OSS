package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.data.api.UserInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 全局用户资料缓存：任何地方「看见过」一个用户，就把他的名字/资料按 userId 记下来，
 * 之后一律先读这份缓存（QQ 同款：缓存优先、联网静默更新，断网也照样能显示）。
 *
 * 覆盖范围由调用方决定，写入点集中在 ChatRepository：
 *   getUserInfo / getFriends / searchUsers / getAllUsers / 好友申请 / 黑名单 /
 *   群成员列表 / 消息列表里的 fromUserName —— 看见一次就缓存一次。
 *
 * 设计要点：
 *  - 内存 ConcurrentHashMap 供渲染热路径同步读（nameOf 不碰磁盘）；
 *  - 磁盘用单文件 JSON + 原子 tmp/rename，写盘丢到单线程 executor，不阻塞主线程；
 *  - 只在内容真的变化时写盘（消息列表每次进会话都会调，不能每次都重写整份文件）；
 *  - 不做 TTL：资料改了由下次联网结果覆盖，缓存永远能兜底；
 *  - 上限 5000 人，超限按 updatedAt 淘汰最旧的一批，避免无限膨胀。
 */
object UserProfileStore {
    private const val FILE_NAME = "user_profiles.json"
    private const val MAX_ENTRIES = 5000
    private val lock = Any()
    private val map = ConcurrentHashMap<Long, Entry>()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "user-profile-cache").apply { isDaemon = true }
    }

    @Volatile private var loaded = false

    data class Entry(
        val id: Long,
        val username: String = "",
        val signature: String = "",
        val qqNumber: String = "",
        val email: String = "",
        val createdAt: Long = 0L,
        val lastActiveAt: Long = 0L,
        val hideEmail: Int = 0,
        val hideQQ: Int = 0,
        val onlineTimeSeconds: Long = 0L,
        val wordCount: Long = 0L,
        val emailVerified: Boolean = false,
        val updatedAt: Long = 0L
    ) {
        fun toUserInfo(): UserInfo = UserInfo(
            id = id, email = email, username = username, qqNumber = qqNumber,
            createdAt = createdAt, lastActiveAt = lastActiveAt,
            hideEmail = hideEmail, hideQQ = hideQQ, signature = signature,
            onlineTimeSeconds = onlineTimeSeconds, wordCount = wordCount,
            emailVerified = emailVerified
        )

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("username", username); put("signature", signature)
            put("qqNumber", qqNumber); put("email", email); put("createdAt", createdAt)
            put("lastActiveAt", lastActiveAt); put("hideEmail", hideEmail); put("hideQQ", hideQQ)
            put("onlineTimeSeconds", onlineTimeSeconds); put("wordCount", wordCount)
            put("emailVerified", emailVerified); put("updatedAt", updatedAt)
        }
    }

    private fun fromJson(o: JSONObject): Entry? {
        val id = o.optLong("id", 0L)
        if (id <= 0L) return null
        return Entry(
            id = id,
            username = o.optString("username", ""),
            signature = o.optString("signature", ""),
            qqNumber = o.optString("qqNumber", ""),
            email = o.optString("email", ""),
            createdAt = o.optLong("createdAt", 0L),
            lastActiveAt = o.optLong("lastActiveAt", 0L),
            hideEmail = o.optInt("hideEmail", 0),
            hideQQ = o.optInt("hideQQ", 0),
            onlineTimeSeconds = o.optLong("onlineTimeSeconds", 0L),
            wordCount = o.optLong("wordCount", 0L),
            emailVerified = o.optBoolean("emailVerified", false),
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }

    /** Application 启动时预热一次内存，之后 nameOf 等同步调用零磁盘读。 */
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
                        map[e.id] = e
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

    /** 断网兜底：把缓存还原成完整 UserInfo，供 getUserInfo 直接返回。 */
    fun get(ctx: Context, userId: Long): UserInfo? {
        ensure(ctx)
        return map[userId]?.takeIf { it.username.isNotEmpty() }?.toUserInfo()
    }

    /** 渲染热路径：纯内存同步读，不碰磁盘、不阻塞重组。 */
    fun nameOf(userId: Long): String = map[userId]?.username.orEmpty()

    /** 合并一条资料：空字段不覆盖已有值，避免「群成员只有名字」把完整资料洗掉。 */
    private fun merge(e: Entry): Boolean {
        val old = map[e.id]
        if (old != null) {
            val next = Entry(
                id = e.id,
                username = e.username.ifBlank { old.username },
                signature = e.signature.ifBlank { old.signature },
                qqNumber = e.qqNumber.ifBlank { old.qqNumber },
                email = e.email.ifBlank { old.email },
                createdAt = if (e.createdAt > 0L) e.createdAt else old.createdAt,
                lastActiveAt = if (e.lastActiveAt > 0L) e.lastActiveAt else old.lastActiveAt,
                hideEmail = e.hideEmail,
                hideQQ = e.hideQQ,
                onlineTimeSeconds = if (e.onlineTimeSeconds > 0L) e.onlineTimeSeconds else old.onlineTimeSeconds,
                wordCount = if (e.wordCount > 0L) e.wordCount else old.wordCount,
                emailVerified = e.emailVerified || old.emailVerified,
                updatedAt = e.updatedAt
            )
            // 内容没变就不写盘（消息列表每次进会话都会重复喂同一批名字）
            if (old.copy(updatedAt = e.updatedAt) == next) return false
            map[e.id] = next
        } else {
            map[e.id] = e
        }
        return true
    }

    private fun flush(ctx: Context) {
        val snapshot = HashMap(map)
        if (snapshot.size > MAX_ENTRIES) {
            val keep = snapshot.values.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES * 4 / 5)
            val keepIds = keep.mapTo(HashSet()) { it.id }
            map.keys.removeAll { it !in keepIds }
        }
        val finalSnapshot = HashMap(map)
        writer.execute {
            try {
                val root = JSONObject()
                for ((id, e) in finalSnapshot) root.put(id.toString(), e.toJson())
                val f = file(ctx)
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(f)
            } catch (_: Exception) { }
        }
    }

    fun putUser(ctx: Context, u: UserInfo) {
        ensure(ctx)
        val now = System.currentTimeMillis()
        val changed = merge(Entry(
            id = u.id, username = u.username, signature = u.signature,
            qqNumber = u.qqNumber, email = u.email, createdAt = u.createdAt,
            lastActiveAt = u.lastActiveAt, hideEmail = u.hideEmail, hideQQ = u.hideQQ,
            onlineTimeSeconds = u.onlineTimeSeconds, wordCount = u.wordCount,
            emailVerified = u.emailVerified, updatedAt = now
        ))
        if (changed) flush(ctx)
    }

    fun putAllUsers(ctx: Context, users: List<UserInfo>) {
        if (users.isEmpty()) return
        ensure(ctx)
        val now = System.currentTimeMillis()
        var changed = false
        for (u in users) {
            changed = merge(Entry(
                id = u.id, username = u.username, signature = u.signature,
                qqNumber = u.qqNumber, email = u.email, createdAt = u.createdAt,
                lastActiveAt = u.lastActiveAt, hideEmail = u.hideEmail, hideQQ = u.hideQQ,
                onlineTimeSeconds = u.onlineTimeSeconds, wordCount = u.wordCount,
                emailVerified = u.emailVerified, updatedAt = now
            )) || changed
        }
        if (changed) flush(ctx)
    }

    /** 只带名字的来源（搜索结果、好友申请、黑名单、群成员）。 */
    fun putName(ctx: Context, userId: Long, name: String) {
        putNames(ctx, listOf(userId to name))
    }

    /** 批量只写名字，一次落盘。 */
    fun putNames(ctx: Context, pairs: List<Pair<Long, String>>) {
        if (pairs.isEmpty()) return
        ensure(ctx)
        val now = System.currentTimeMillis()
        var changed = false
        for ((id, name) in pairs) {
            if (id <= 0L || name.isBlank()) continue
            changed = merge(Entry(id = id, username = name, updatedAt = now)) || changed
        }
        if (changed) flush(ctx)
    }

    /** 消息里出现过的发送者，一次批量入库（聊天里见过的人断网后也认得名字）。 */
    fun putNamesFromMessages(ctx: Context, messages: List<MessageInfo>) {
        if (messages.isEmpty()) return
        ensure(ctx)
        val now = System.currentTimeMillis()
        var changed = false
        for (m in messages) {
            if (m.fromUserId > 0L && m.fromUserName.isNotBlank()) {
                changed = merge(Entry(id = m.fromUserId, username = m.fromUserName, updatedAt = now)) || changed
            }
        }
        if (changed) flush(ctx)
    }

    /** 群成员 JSONArray：user_id / username。 */
    fun putNamesFromMemberArray(ctx: Context, arr: JSONArray) {
        ensure(ctx)
        val now = System.currentTimeMillis()
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val uid = o.optLong("user_id", 0L)
            val name = o.optString("username", "")
            if (uid <= 0L || name.isBlank()) continue
            changed = merge(Entry(id = uid, username = name, updatedAt = now)) || changed
        }
        if (changed) flush(ctx)
    }

    /** 账号切换/登出时清空，避免把上个账号看到的人留给下个账号。 */
    fun clear(ctx: Context) {
        synchronized(lock) {
            map.clear()
            try { file(ctx).delete() } catch (_: Exception) { }
        }
    }
}

/**
 * 群成员名单缓存：按 groupId 一个 JSON 文件，永不设 TTL。
 * 联网成功即覆盖，断网/请求失败时由 ChatRepository 直接拿它当结果返回，
 * 这样 17 处 getGroupMembers 调用点（成员页、群设置、@ 列表、管理子面板）全部自动离线可用。
 */
object GroupMemberStore {
    private const val DIR = "group_members_cache"

    private fun dir(ctx: Context): File = File(ctx.applicationContext.filesDir, DIR)

    private fun file(ctx: Context, groupId: Long): File =
        File(dir(ctx), "$groupId.json")

    fun save(ctx: Context, groupId: Long, arr: JSONArray) {
        try {
            val d = dir(ctx)
            if (!d.exists()) d.mkdirs()
            val f = file(ctx, groupId)
            val tmp = File(d, "${f.name}.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        } catch (_: Exception) { }
    }

    fun load(ctx: Context, groupId: Long): JSONArray? = try {
        val f = file(ctx, groupId)
        if (!f.exists()) null else JSONArray(f.readText())
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context, groupId: Long) {
        try { file(ctx, groupId).delete() } catch (_: Exception) { }
    }
}
