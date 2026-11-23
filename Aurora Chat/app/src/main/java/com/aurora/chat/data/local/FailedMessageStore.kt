package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.CryptoUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 发送失败消息的本地持久化（JSON 文件，存于 filesDir）。
 *
 * 为什么需要它：发送失败时消息原本只存在内存的 `messages` 列表里，
 * 一旦 `syncMessages()` 用 adapter（只装服务端数据）重建列表，或退出重进对话，
 * 这条消息就凭空消失了 —— 用户看不到"哪条没发出去"，也就没有重发的入口。
 *
 * 这里把失败消息落盘：气泡留在原位、左侧带红叹号，点叹号可重发；
 * 无论切会话、杀进程、重启 App 都不会丢，直到发送成功或用户手动删除。
 *
 * 正文与引用文本按与 [LocalMessageStore] 相同口径做本地加密（AES-GCM），
 * 磁盘上不出现明文。
 */
data class FailedMessage(
    /** 本地临时 id（负数，与 adapter 的服务端 id 不冲突），重发成功前唯一标识这条消息 */
    val id: Long,
    val friendId: Long,
    val text: String,
    val replyToId: Long = 0,
    val replyToText: String = "",
    val replyToSender: String = "",
    val mediaType: String = "",
    val mediaUrl: String = "",
    val flashDuration: Int = 0,
    val contactSource: String = "",
    /** 失败时刻（秒），用作气泡时间与列表排序 */
    val createdAt: Long = 0
)

object FailedMessageStore {

    private const val FILE_NAME = "failed_messages.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun toJson(m: FailedMessage): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("friendId", m.friendId)
        put("text", CryptoUtil.encryptLocal(m.text))
        put("replyToId", m.replyToId)
        put("replyToText", CryptoUtil.encryptLocal(m.replyToText))
        put("replyToSender", m.replyToSender)
        put("mediaType", m.mediaType)
        put("mediaUrl", m.mediaUrl)
        put("flashDuration", m.flashDuration)
        put("contactSource", m.contactSource)
        put("createdAt", m.createdAt)
    }

    private fun fromJson(o: JSONObject): FailedMessage = FailedMessage(
        id = o.optLong("id"),
        friendId = o.optLong("friendId"),
        text = runCatching { CryptoUtil.decryptLocal(o.optString("text")) }.getOrElse { "" },
        replyToId = o.optLong("replyToId"),
        replyToText = runCatching { CryptoUtil.decryptLocal(o.optString("replyToText")) }.getOrElse { "" },
        replyToSender = o.optString("replyToSender"),
        mediaType = o.optString("mediaType"),
        mediaUrl = o.optString("mediaUrl"),
        flashDuration = o.optInt("flashDuration"),
        contactSource = o.optString("contactSource"),
        createdAt = o.optLong("createdAt")
    )

    /** 读取全部失败消息（所有会话）。文件损坏时返回空表，绝不抛异常打断进入对话。 */
    @Synchronized
    fun all(ctx: Context): List<FailedMessage> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 读取某个会话的失败消息（按失败时间升序，即最旧在前，与消息列表口径一致） */
    fun list(ctx: Context, friendId: Long): List<FailedMessage> =
        all(ctx).filter { it.friendId == friendId }.sortedBy { it.createdAt }

    /** 写入/覆盖一条失败消息（同一 id 去重） */
    @Synchronized
    fun save(ctx: Context, msg: FailedMessage) {
        try {
            val list = all(ctx).filter { it.id != msg.id } + msg
            write(ctx, list)
        } catch (_: Exception) {}
    }

    /** 删除一条失败消息（重发成功 / 用户手动删除） */
    @Synchronized
    fun remove(ctx: Context, id: Long) {
        try {
            write(ctx, all(ctx).filter { it.id != id })
        } catch (_: Exception) {}
    }

    /** 清空某个会话的失败消息（清空聊天记录时调用） */
    @Synchronized
    fun clear(ctx: Context, friendId: Long) {
        try {
            write(ctx, all(ctx).filter { it.friendId != friendId })
        } catch (_: Exception) {}
    }

    private fun write(ctx: Context, list: List<FailedMessage>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val f = file(ctx)
        // 原子写：先写临时文件再 rename，避免写入中崩溃留下半截 JSON
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }
}
