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
 * 极致优化点：常规读写走在 Dispatchers.IO，并增加内存缓存，重复 load 不再重新读盘 + 解密 lastMessage。
 * 冷启动首帧额外提供 [peek] / [loadBlocking]：让聊天列表在**第一帧**就能铺出磁盘里的会话，
 * 而不是先只显示个人对话 / AI / 通知中心这 3 个固定项、等 IO 回来再补。
 */
object LocalConversationStore {

    private const val FILE_NAME = "conversations_cache.json"
    private val memCache = ConcurrentHashMap<Boolean, List<ConversationInfo>>()
    private val lock = Any()

    private fun getFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    /** 单条会话序列化为加密 JSONObject（lastMessage 经 AES-GCM 加密落盘） */
    private fun conversationToJson(c: ConversationInfo): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("email", c.email)
        put("username", c.username)
        put("createdAt", c.createdAt)
        // 单条加密失败不能拖垮整份缓存：失败则明文落盘（至少会话还在，预览重载时由本地消息库补全）
        put("lastMessage", try { CryptoUtil.encryptLocal(c.lastMessage) } catch (_: Exception) { c.lastMessage })
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

    /**
     * 已经进内存的会话快照；从未加载过时返回 null。
     * 供聊天列表首帧同步取用，不触发任何磁盘 IO。
     */
    fun peek(): List<ConversationInfo>? = memCache[true]

    /**
     * 同步加载：内存缓存命中直接返回；未命中才读一次文件（冷启动首帧兜底）。
     * 文件是纯本地小 JSON，且 Application 启动时已在 IO 预热，正常情况下不会走到读盘分支。
     */
    fun loadBlocking(context: Context): List<ConversationInfo> {
        memCache[true]?.let { return it }
        return synchronized(lock) {
            memCache[true]?.let { return@synchronized it }
            val result = try {
                val file = getFile(context)
                if (!file.exists()) {
                    emptyList()
                } else {
                    val text = file.readText()
                    val json = JSONArray(text)
                    val list = mutableListOf<ConversationInfo>()
                    for (i in 0 until json.length()) {
                        // 单条损坏（如旧版残留/解密失败）跳过，不能让整份缓存变空
                        try {
                            val obj = json.getJSONObject(i)
                            list.add(ConversationInfo(
                                id = obj.optLong("id"),
                                email = obj.optString("email"),
                                username = obj.optString("username"),
                                createdAt = obj.optLong("createdAt"),
                                lastMessage = try { CryptoUtil.decryptLocal(obj.optString("lastMessage")) } catch (_: Exception) { obj.optString("lastMessage") },
                                lastTime = obj.optLong("lastTime")
                            ))
                        } catch (_: Exception) { /* skip bad item */ }
                    }
                    list
                }
            } catch (_: Exception) {
                emptyList()
            }
            memCache[true] = result
            result
        }
    }

    /** 从本地加载对话列表（IO 线程 + 内存缓存） */
    suspend fun load(context: Context): List<ConversationInfo> = withContext(Dispatchers.IO) {
        loadBlocking(context)
    }
}
