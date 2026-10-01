package com.aurora.chat.data.local

import android.content.Context
import com.aurora.chat.CryptoUtil
import com.aurora.chat.data.api.ConversationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 对话列表本地缓存（JSON 文件）
 * 断网时读取缓存，不显示"暂无会话"
 *
 * 极致优化点：所有读写移至 Dispatchers.IO（旧实现在调用方主线程做整文件读写 + GCM），
 * 并增加内存缓存，重复 load 不再重新读盘 + 解密 lastMessage。
 */
object LocalConversationStore {

    private const val FILE_NAME = "conversations_cache.json"
    private val memCache = ConcurrentHashMap<Boolean, List<ConversationInfo>>()

    private fun getFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** 单条会话序列化为加密 JSONObject（lastMessage 经 AES-GCM 加密落盘） */
    private fun conversationToJson(c: ConversationInfo): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("email", c.email)
        put("username", c.username)
        put("createdAt", c.createdAt)
        put("lastMessage", CryptoUtil.encryptLocal(c.lastMessage))
        put("lastTime", c.lastTime)
    }

    /** 保存对话列表到本地（IO 线程，避免阻塞主线程） */
    suspend fun save(context: Context, conversations: List<ConversationInfo>) = withContext(Dispatchers.IO) {
        try {
            val json = JSONArray()
            conversations.forEach { json.put(conversationToJson(it)) }
            getFile(context).writeText(json.toString(2))
            memCache[true] = conversations.toList()
        } catch (_: Exception) {}
    }

    /** 清空本地会话列表缓存：清内存缓存 + 删除缓存文件。供「清空所有聊天记录」使用 */
    fun clear(context: Context) {
        try {
            memCache.clear()
            getFile(context).delete()
        } catch (_: Exception) {}
    }

    /** 从本地加载对话列表（IO 线程 + 内存缓存） */
    suspend fun load(context: Context): List<ConversationInfo> = withContext(Dispatchers.IO) {
        memCache[true]?.let { return@withContext it }
        val result = try {
            val file = getFile(context)
            if (!file.exists()) return@withContext emptyList()
            val text = file.readText()
            val json = JSONArray(text)
            val list = mutableListOf<ConversationInfo>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                list.add(ConversationInfo(
                    id = obj.optLong("id"),
                    email = obj.optString("email"),
                    username = obj.optString("username"),
                    createdAt = obj.optLong("createdAt"),
                    lastMessage = CryptoUtil.decryptLocal(obj.optString("lastMessage")),
                    lastTime = obj.optLong("lastTime")
                ))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
        memCache[true] = result
        result
    }
}
