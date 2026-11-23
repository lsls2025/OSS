package com.aurora.chat.data.store

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息级 @ 提及映射（消息 id → 「发送时昵称 → 用户ID」）。
 *
 * 为什么需要它：正文里的 `@昵称` 只是一段纯文本，昵称一改，按昵称去群成员列表里反查就查不到，
 * 渲染层会把它标成「非成员」灰色、点击提示找不到用户。
 * 发送那一刻把「昵称 → 用户ID」随消息一起存下来，ID 不会变，改名后依然能定位到人。
 *
 * 与 [MediaStore] 一样按消息 id 全局索引，不改动 MessageStore 的定长数组结构。
 */
object MentionStore {

    private val map = ConcurrentHashMap<Long, Map<String, Long>>()

    /** 后端字段 mentions 是 JSON 对象字符串（{"昵称": 用户ID}）；解析失败一律当空。 */
    fun parse(raw: String): Map<String, Long> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = HashMap<String, Long>(obj.length().coerceAtLeast(1))
            for (key in obj.keys()) {
                val id = obj.optLong(key, 0L)
                if (key.isNotBlank() && id > 0L) out[key] = id
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 序列化为后端可直接落库的 JSON 对象字符串。 */
    fun encode(mentions: Map<String, Long>): String {
        if (mentions.isEmpty()) return ""
        val obj = JSONObject()
        for ((name, id) in mentions) if (name.isNotBlank() && id > 0L) obj.put(name, id)
        return if (obj.length() == 0) "" else obj.toString()
    }

    fun put(id: Long, mentions: Map<String, Long>) {
        if (id <= 0L || mentions.isEmpty()) return
        map[id] = mentions
    }

    fun putRaw(id: Long, raw: String) = put(id, parse(raw))

    fun seed(items: Iterable<Pair<Long, Map<String, Long>>>) {
        for ((id, m) in items) put(id, m)
    }

    fun get(id: Long): Map<String, Long> = map[id] ?: emptyMap()

    fun clear() = map.clear()
}

/**
 * 会话级「昵称 → 用户ID」本地目录。
 *
 * 断网时群成员接口拿不到名单，渲染层会把所有 @ 都判成「非成员」变灰；
 * 但本地其实还留着这个会话的成员列表缓存和全部消息发送者，足够定位到人。
 * 这里把「成员名单 + 历史消息发送者」合并成一个目录，渲染/点击都优先问它。
 */
object MentionDirectory {

    private val byConv = ConcurrentHashMap<Long, Map<String, Long>>()

    fun put(convId: Long, entries: Map<String, Long>) {
        if (convId == 0L || entries.isEmpty()) return
        val cur = byConv[convId]
        if (cur == null) {
            byConv[convId] = entries
        } else {
            val merged = HashMap<String, Long>(cur.size + entries.size)
            merged.putAll(cur)
            merged.putAll(entries)
            byConv[convId] = merged
        }
    }

    fun namesOf(convId: Long): Set<String> = byConv[convId]?.keys ?: emptySet()

    fun idOf(convId: Long, name: String): Long? = byConv[convId]?.get(name)?.takeIf { it > 0L }

    fun clear() = byConv.clear()
}

/**
 * 从正文里提取 `@昵称`，并用 [resolve] 把昵称解析成用户ID；解析不到的昵称直接忽略。
 * 只返回真正能定位到人的条目，避免把无效 @ 也写进消息。
 */
fun parseMentionsFromText(text: String, resolve: (String) -> Long?): Map<String, Long> {
    if (text.isEmpty() || !text.contains('@')) return emptyMap()
    val out = HashMap<String, Long>(2)
    for (match in MENTION_REGEX.findAll(text)) {
        val name = match.groupValues[1]
        if (name.isBlank() || out.containsKey(name)) continue
        val id = resolve(name) ?: continue
        if (id > 0L) out[name] = id
    }
    return out
}

/** 与渲染层的高亮规则保持一致：@ 后面直到空白为止算一个昵称。 */
private val MENTION_REGEX = Regex("@(\\S+)")
