package com.aurora.chat.data.local

import android.content.Context
import android.net.Uri
import com.aurora.chat.CryptoUtil
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.data.repository.ChatRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据本地备份 / 重置 / 30 天消息拉取。
 *
 * 安全边界：所有操作只遍历「当前登录用户自己的会话列表 getConversations()」，
 * 绝不拼接任意 userId / 群 ID；服务端 handleGetMessages 也会二次校验
 * （私聊须自己是双方之一、群聊须是群成员），官方群对全员自动放行。
 */
object DataBackup {

    const val BACKUP_VERSION = 1

    // ==================== 结果类型 ====================

    data class PullResult(
        val success: Boolean,
        val totalPulled: Int,
        val totalConversations: Int,
        val error: String
    )

    data class BackupResult(
        val success: Boolean,
        val conversations: Int,
        val messages: Int,
        val filePath: String,
        val error: String,
        val fileSize: Long = 0
    )

    data class ResetResult(
        val success: Boolean,
        val conversations: Int,
        val messages: Int,
        val error: String
    )

    // ==================== 拉取 30 天内未拉取的消息 ====================

    /**
     * 遍历当前用户自己的会话列表，对每个会话用 after_id=本地最大消息id
     * 拉取比本地新的消息（服务端仅保留 30 天，自然覆盖窗口内全部），去重落盘。
     *
     * @param onProgress (已完成会话数, 总会话数, 累计新增条数)
     */
    suspend fun pullRecentMessages(
        context: Context,
        currentUserId: Long,
        onProgress: (done: Int, total: Int, newCount: Int) -> Unit = { _, _, _ -> }
    ): PullResult {
        return try {
            val convs = AuroraApi.getConversations().data ?: emptyList()
            if (convs.isEmpty()) return PullResult(true, 0, 0, "")

            var totalPulled = 0
            convs.forEachIndexed { idx, conv ->
                try {
                    val afterId = com.aurora.chat.data.repository.LocalMessageStore.loadMessages(context, conv.id)
                        .maxOfOrNull { it.id } ?: 0L
                    val msgs = ChatRepository.getMessages(currentUserId, conv.id, 0, 0, afterId).data ?: emptyList()
                    if (msgs.isNotEmpty()) {
                        com.aurora.chat.data.repository.LocalMessageStore.saveMessages(context, conv.id, msgs)
                        totalPulled += msgs.size
                    }
                } catch (_: Exception) { /* 单会话失败不影响其余 */ }
                onProgress(idx + 1, convs.size, totalPulled)
            }
            PullResult(true, totalPulled, convs.size, "")
        } catch (e: Exception) {
            PullResult(false, 0, 0, e.message ?: "拉取失败")
        }
    }

    // ==================== 将所有数据保存到本地 ====================

    /**
     * 先拉取 30 天内未拉取的消息，再把对话列表、各会话消息、个人信息
     * 打包成 AES-GCM（设备派生密钥）加密内容，写入用户通过文件管理器选择的 URI。
     */
    suspend fun exportTo(context: Context, currentUserId: Long, uri: Uri): BackupResult {
        return try {
            // 1. 确保消息最新
            pullRecentMessages(context, currentUserId)

            // 2. 收集会话列表（仅自己的会话）
            val convs = AuroraApi.getConversations().data ?: emptyList()

            // 3. 收集各会话消息 + 个人信息
            val messagesArr = JSONArray()
            var msgCount = 0
            convs.forEach { conv ->
                val msgs = com.aurora.chat.data.repository.LocalMessageStore.loadMessages(context, conv.id)
                if (msgs.isNotEmpty()) {
                    messagesArr.put(JSONObject().apply {
                        put("convId", conv.id)
                        put("convName", conv.username)
                        put("messages", messagesToJson(msgs))
                    })
                    msgCount += msgs.size
                }
            }

            val profile = JSONObject().apply {
                put("username", LocalStorage.getUserName(context, currentUserId))
                put("signature", LocalStorage.getUserSignature(context, currentUserId))
                put("qq", AuroraApi.currentUserQQ)
            }

            val envelope = JSONObject().apply {
                put("version", BACKUP_VERSION)
                put("userId", currentUserId)
                put("timestamp", System.currentTimeMillis())
                put("conversations", conversationsToJson(convs))
                put("messages", messagesArr)
                put("profile", profile)
            }

            // 4. 整体加密后写入用户选择的文件（设备派生密钥，双击无法读取明文）
            val encrypted = CryptoUtil.encryptLocal(envelope.toString(2))
            var size = 0L
            context.contentResolver.openOutputStream(uri)?.use { os ->
                val bytes = encrypted.toByteArray(Charsets.UTF_8)
                os.write(bytes)
                size = bytes.size.toLong()
            }
            BackupResult(true, convs.size, msgCount, uri.toString(), "", size)
        } catch (e: Exception) {
            BackupResult(false, 0, 0, "", e.message ?: "保存失败", 0)
        }
    }

    // ==================== 将数据重置到本地 ====================

    /**
     * 读取用户通过文件管理器选择的备份文件，解密后覆盖写回本地消息/会话缓存/个人信息。
     * 纯本地操作，不发服务器请求；账号/登录类敏感信息不覆盖。
     */
    suspend fun resetFromUri(context: Context, currentUserId: Long, uri: Uri): ResetResult {
        return try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return ResetResult(false, 0, 0, "无法读取所选文件")
            applyReset(context, currentUserId, content)
        } catch (e: Exception) {
            ResetResult(false, 0, 0, e.message ?: "重置失败")
        }
    }

    private suspend fun applyReset(context: Context, currentUserId: Long, encrypted: String): ResetResult {
        return try {
            val plain = CryptoUtil.decryptLocal(encrypted)
            val envelope = JSONObject(plain)
            if (envelope.optLong("userId") != currentUserId) {
                return ResetResult(false, 0, 0, "备份文件不属于当前账号，已拒绝")
            }

            // 1. 覆盖会话缓存
            val convsJson = envelope.optJSONArray("conversations") ?: JSONArray()
            val convs = conversationsFromJson(convsJson)
            if (convs.isNotEmpty()) LocalConversationStore.save(context, convs)

            // 2. 覆盖各会话消息
            var msgCount = 0
            val messagesArr = envelope.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until messagesArr.length()) {
                val obj = messagesArr.getJSONObject(i)
                val convId = obj.optLong("convId")
                val msgs = messagesFromJson(obj.optJSONArray("messages") ?: JSONArray())
                if (msgs.isNotEmpty()) {
                    com.aurora.chat.data.repository.LocalMessageStore.overwriteMessages(context, convId, msgs)
                    msgCount += msgs.size
                }
            }

            // 3. 覆盖个人信息（昵称/签名；账号/登录态不碰）
            val profile = envelope.optJSONObject("profile")
            if (profile != null) {
                LocalStorage.saveUserProfile(
                    context, currentUserId,
                    profile.optString("username", LocalStorage.getUserName(context, currentUserId)),
                    profile.optString("signature", LocalStorage.getUserSignature(context, currentUserId))
                )
            }

            ResetResult(true, convs.size, msgCount, "")
        } catch (e: Exception) {
            ResetResult(false, 0, 0, e.message ?: "重置失败")
        }
    }

    // ==================== 序列化辅助 ====================

    private fun messagesToJson(msgs: List<MessageInfo>): JSONArray {
        val arr = JSONArray()
        msgs.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("fromUserId", m.fromUserId)
                put("fromUserName", m.fromUserName); put("content", m.content)
                put("createdAt", m.createdAt); put("mediaType", m.mediaType)
                put("mediaUrl", m.mediaUrl); put("isRevoked", m.isRevoked)
                put("replyToText", m.replyToText); put("replyToSender", m.replyToSender)
                put("replyToId", m.replyToId); put("flashDuration", m.flashDuration)
                put("toUserId", m.toUserId)
            })
        }
        return arr
    }

    private fun messagesFromJson(arr: JSONArray): List<MessageInfo> {
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            MessageInfo(
                id = obj.optLong("id"),
                fromUserId = obj.optLong("fromUserId"),
                toUserId = obj.optLong("toUserId"),
                content = obj.optString("content"),
                createdAt = obj.optLong("createdAt"),
                fromUserName = obj.optString("fromUserName", ""),
                isRevoked = obj.optInt("isRevoked", 0),
                replyToText = obj.optString("replyToText", ""),
                replyToSender = obj.optString("replyToSender", ""),
                replyToId = obj.optLong("replyToId", 0),
                mediaType = obj.optString("mediaType", ""),
                mediaUrl = obj.optString("mediaUrl", ""),
                flashDuration = obj.optInt("flashDuration", 0)
            )
        }
    }

    private fun conversationsToJson(convs: List<ConversationInfo>): JSONArray {
        val arr = JSONArray()
        convs.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("email", c.email)
                put("username", c.username); put("createdAt", c.createdAt)
            })
        }
        return arr
    }

    private fun conversationsFromJson(arr: JSONArray): List<ConversationInfo> {
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ConversationInfo(
                id = obj.optLong("id"),
                email = obj.optString("email"),
                username = obj.optString("username"),
                createdAt = obj.optLong("createdAt"),
                lastMessage = obj.optString("lastMessage", ""),
                lastTime = obj.optLong("lastTime", 0)
            )
        }
    }
}
