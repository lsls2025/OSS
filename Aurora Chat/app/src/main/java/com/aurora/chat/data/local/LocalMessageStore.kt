package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.CryptoUtil
import com.aurora.chat.data.api.MessageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天消息本地持久化存储（JSON 文件，按会话分组，不因清除缓存而丢失）
 *
 * 极致优化点（相对旧实现）：
 * 1) 所有读写均在 Dispatchers.IO，绝不阻塞主线程（旧实现在调用方主线程做整文件读写 + GCM 加解密）。
 * 2) 内存缓存：已解密的会话消息缓存在 memCache，重复 load 不再重新读盘 + 解密。
 * 3) 增量追加写：saveMessages 仅读取「原始加密 JSON」、把不存在的新消息加密后追加，
 *    不再像旧实现那样先整体解密再整体重加密（旧实现每条消息的保存都是 O(n) 的 GCM 加解密）。
 *    文件格式保持 JSONArray（已加密 content），无需迁移旧数据。
 */
object LocalMessageStore {

    private val memCache = ConcurrentHashMap<Long, List<MessageInfo>>()

    private fun getFile(ctx: Context, friendId: Long): File {
        val dir = File(ctx.filesDir, "messages")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${friendId}.json")
    }

    /** 仅读取原始加密 JSONArray（不解密），用于增量追加 */
    private fun readRawArray(ctx: Context, friendId: Long): JSONArray? {
        val f = getFile(ctx, friendId)
        if (!f.exists()) return null
        return try { JSONArray(f.readText()) } catch (_: Exception) { null }
    }

    /** 单条消息序列化为加密 JSONObject（content 经 AES-GCM 加密落盘） */
    private fun messageToJson(m: MessageInfo): JSONObject = JSONObject().apply {
        put("id", m.id); put("fromUserId", m.fromUserId)
        put("fromUserName", m.fromUserName); put("content", CryptoUtil.encryptLocal(m.content))
        put("createdAt", m.createdAt); put("mediaType", m.mediaType)
        put("mediaUrl", m.mediaUrl); put("isRevoked", m.isRevoked)
        put("replyToText", m.replyToText); put("replyToSender", m.replyToSender)
        put("replyToId", m.replyToId); put("flashDuration", m.flashDuration)
        put("toUserId", m.toUserId)
    }

    /** 保存加载到的消息到本地（增量追加，去重；仅加密新消息，不整体重加密） */
    suspend fun saveMessages(ctx: Context, friendId: Long, messages: List<MessageInfo>) = withContext(Dispatchers.IO) {
        try {
            val raw = readRawArray(ctx, friendId)
            val existingIds = if (raw != null) {
                (0 until raw.length()).mapTo(mutableSetOf()) { raw.getJSONObject(it).optLong("id") }
            } else {
                mutableSetOf()
            }
            val arr = raw ?: JSONArray()
            var changed = false
            for (m in messages) {
                if (m.id !in existingIds) {
                    arr.put(messageToJson(m))
                    existingIds.add(m.id)
                    changed = true
                }
            }
            if (!changed) return@withContext
            atomicWrite(getFile(ctx, friendId), arr.toString())
            // 更新内存缓存：合并新消息（保持按 id 排序）
            val cached = memCache[friendId]
            if (cached != null) {
                val byId = cached.associateBy { it.id }
                memCache[friendId] = (cached + messages.filter { it.id !in byId }).sortedBy { it.id }
            }
        } catch (_: Exception) {}
    }

    /** 整体覆盖某个会话的本地消息（用于从备份重置，替换旧内容） */
    suspend fun overwriteMessages(ctx: Context, friendId: Long, messages: List<MessageInfo>) = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            messages.forEach { arr.put(messageToJson(it)) }
            atomicWrite(getFile(ctx, friendId), arr.toString())
            memCache[friendId] = messages.toList()
        } catch (_: Exception) {}
    }

    /** 清空所有本地会话消息：清内存缓存 + 删除消息文件。供「清空所有聊天记录」使用 */
    fun clear(ctx: Context) {
        try {
            memCache.clear()
            val dir = File(ctx.filesDir, "messages")
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    /**
     * 原子写：先写临时文件再 rename，避免写入过程中崩溃/断电留下半截 JSON 导致整文件损坏。
     */
    private fun atomicWrite(file: File, json: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json)
        val ok = tmp.renameTo(file)
        com.aurora.chat.ErrorReporter.debug("RECALL_TRACE", "atomicWrite renameTo=$ok dest=${file.absolutePath}")
    }



    /** 从本地加载聊天消息（优先内存缓存 → 文件，无 1MB 上限；SP 仅兜底兼容旧数据） */
    suspend fun loadMessages(ctx: Context, friendId: Long): List<MessageInfo> = withContext(Dispatchers.IO) {
        memCache[friendId]?.let { return@withContext it }
        val result = try {
            val f = getFile(ctx, friendId)
            if (f.exists()) parseMessages(ctx, f.readText())
            else {
                // 兜底：旧版本曾将整段对话存于 SP
                val sp = ctx.getSharedPreferences("msg_store", Context.MODE_PRIVATE)
                    .getString("msg_$friendId", null)
                if (sp != null) parseMessages(ctx, sp) else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        memCache[friendId] = result
        result
    }

    private fun parseMessages(ctx: Context, json: String): List<MessageInfo> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            MessageInfo(
                id = obj.getLong("id"),
                fromUserId = obj.getLong("fromUserId"),
                fromUserName = obj.optString("fromUserName", ""),
                content = CryptoUtil.decryptLocal(obj.optString("content", "")),
                createdAt = obj.optLong("createdAt"),
                mediaType = obj.optString("mediaType", ""),
                mediaUrl = obj.optString("mediaUrl", ""),
                isRevoked = obj.optInt("isRevoked", 0),
                replyToText = obj.optString("replyToText", ""),
                replyToSender = obj.optString("replyToSender", ""),
                replyToId = obj.optLong("replyToId", 0),
                flashDuration = obj.optInt("flashDuration", 0),
                toUserId = obj.optLong("toUserId", 0)
            )
        }
    }
}
