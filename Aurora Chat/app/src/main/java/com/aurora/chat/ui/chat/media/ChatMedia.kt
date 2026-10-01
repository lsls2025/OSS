package com.aurora.chat.ui.chat.media

import java.util.concurrent.ConcurrentHashMap

/**
 * 媒体消息值对象。
 * @param type 媒体类型（"image" / "video" / ...）
 * @param url  客户端可达的完整 URL（落盘时一次性固化，读回后不再依赖运行时解析）
 */
data class ChatMedia(
    val type: String,
    val url: String
)

/**
 * 媒体 URL 的权威单一来源。
 *
 * 设计目标：彻底解决"发送后正常、重新进入变空气泡"的问题。
 * 旧链路里图片 URL 在 发送→落盘(JSON)→读回(自定义池)→网络合并 各环节都可能被丢/被空值覆盖，
 * 于是重载后 mediaUrl 为空 → 渲染出空气泡。
 *
 * 这里改为：以 msgId 为键，在【发送成功】和【每次重载】时把【已验证可达】的 URL 写入本内存表，
 * 渲染时优先查本表，任何环节丢了都能兜底。内存表进程内常驻，重载时重新填充。
 */
object MediaStore {
    private val map = ConcurrentHashMap<Long, ChatMedia>()

    /** 写入一条媒体（msgId <= 0 忽略，避免占位本地 id 污染）。 */
    fun put(msgId: Long, media: ChatMedia) {
        if (msgId <= 0) return
        map[msgId] = media
    }

    /** 读取某条消息的可达媒体 URL（不存在返回 null）。 */
    fun get(msgId: Long): ChatMedia? = if (msgId > 0) map[msgId] else null

    fun remove(msgId: Long) {
        if (msgId > 0) map.remove(msgId)
    }

    /**
     * 用一批消息里的非空媒体回填（重载时调用）。
     * 仅当本地尚未记录、或已记录但 URL 为空时才写入，绝不拿空值覆盖已有有效 URL。
     * @param items List<Pair<msgId, Pair<type, resolvedUrl>>>
     */
    fun seed(items: List<Pair<Long, Pair<String, String>>>) {
        for ((id, pair) in items) {
            if (id <= 0) continue
            val (type, url) = pair
            if (type.isEmpty() || url.isEmpty()) continue
            val existing = map[id]
            if (existing == null || existing.url.isEmpty()) {
                map[id] = ChatMedia(type, url)
            }
        }
    }

    fun clear() = map.clear()
}
