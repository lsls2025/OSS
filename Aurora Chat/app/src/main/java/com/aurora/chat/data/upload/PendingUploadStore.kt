package com.aurora.chat.data.upload

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 待发送媒体队列的持久化（JSON 文件，存于 filesDir）。
 *
 * 用途：用户点发送图片/视频后，立即把"已本地压缩的字节 + 目标会话"落盘，
 * 即使离开对话、退出软件也不丢。下次启动由 [MediaUploadManager] 重新读取并自动重试。
 */
data class PendingUpload(
    val id: Long,
    val friendId: Long,
    val isGroup: Boolean,
    val localPath: String,
    val mimeType: String,
    val fileName: String,
    val text: String,
    val flashDuration: Int,
    val createdAt: Long,
    val status: Int,
    val retryCount: Int
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_UPLOADING = 1
        const val STATUS_FAILED = 2
        const val STATUS_DONE = 3
        const val STATUS_COMPRESSING = 4
    }
}

object PendingUploadStore {
    private const val FILE_NAME = "pending_uploads.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    fun enqueue(ctx: Context, item: PendingUpload) {
        val all = all(ctx).toMutableList()
        all.removeAll { it.id == item.id }
        all.add(item)
        write(ctx, all)
    }

    @Synchronized
    fun update(ctx: Context, item: PendingUpload) {
        val all = all(ctx).toMutableList()
        val idx = all.indexOfFirst { it.id == item.id }
        if (idx >= 0) all[idx] = item else all.add(item)
        write(ctx, all)
    }

    @Synchronized
    fun remove(ctx: Context, id: Long) {
        write(ctx, all(ctx).filter { it.id != id })
    }

    fun all(ctx: Context): List<PendingUpload> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                PendingUpload(
                    id = o.getLong("id"),
                    friendId = o.getLong("friendId"),
                    isGroup = o.optBoolean("isGroup"),
                    localPath = o.getString("localPath"),
                    mimeType = o.optString("mimeType", ""),
                    fileName = o.optString("fileName", "file"),
                    text = o.optString("text", ""),
                    flashDuration = o.optInt("flashDuration", 0),
                    createdAt = o.optLong("createdAt", 0),
                    status = o.optInt("status", PendingUpload.STATUS_PENDING),
                    retryCount = o.optInt("retryCount", 0)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun write(ctx: Context, list: List<PendingUpload>) {
        try {
            val arr = JSONArray()
            list.forEach { it ->
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("friendId", it.friendId)
                    put("isGroup", it.isGroup)
                    put("localPath", it.localPath)
                    put("mimeType", it.mimeType)
                    put("fileName", it.fileName)
                    put("text", it.text)
                    put("flashDuration", it.flashDuration)
                    put("createdAt", it.createdAt)
                    put("status", it.status)
                    put("retryCount", it.retryCount)
                })
            }
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) { }
    }
}
