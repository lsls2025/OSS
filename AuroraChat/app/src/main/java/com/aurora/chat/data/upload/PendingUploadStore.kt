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
    val retryCount: Int,
    // 语音时长（秒）。仅 mimeType 为 audio/* 时有意义；其它类型恒为 0。
    // 注意：参数列表里不能用 /** */ 块注释，且注释里写 "audio/*" 会开启嵌套块注释，
    // 会把整个文件后面的注释结构带崩（报 Unclosed comment），只能用行注释。
    val voiceDuration: Int = 0,
    // 已上传成功但「发送」被拒（限流/瞬时错误）时记录的媒体地址与真实宽高。
    // 重试时直接复用，不再重新上传整个文件：否则每次重试都会重新走到 100% 才失败，既费流量又让人误以为没发出去。
    val uploadedUrl: String = "",
    val uploadedWidth: Int = 0,
    val uploadedHeight: Int = 0,
    // 原图：true 表示不压缩（图片不重新编码、视频不转码），按原始字节直传
    val original: Boolean = false
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

    /**
     * 读取整个队列。
     *
     * 必须与写操作互斥：批量上传时最多 10 个协程会同时 update 状态，
     * 若此时另一个协程正在读，可能读到「写了一半」的 JSON → 解析失败 → 返回空列表，
     * 于是 [MediaUploadManager.uploadOne] 误判「该项已被移出队列」直接返回，
     * 占位气泡就永远停在 0%（表现为「一批图片里总有几张一直 0%」）。
     */
    @Synchronized
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
                    retryCount = o.optInt("retryCount", 0),
                    voiceDuration = o.optInt("voiceDuration", 0),
                    uploadedUrl = o.optString("uploadedUrl", ""),
                    uploadedWidth = o.optInt("uploadedWidth", 0),
                    uploadedHeight = o.optInt("uploadedHeight", 0),
                    original = o.optBoolean("original", false)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 落盘：先写临时文件再 rename。
     * 直接 writeText 原文件时，进程被杀/并发读到半截文件都会让整份队列丢失（所有待发媒体一起消失）。
     */
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
                    put("voiceDuration", it.voiceDuration)
                    put("uploadedUrl", it.uploadedUrl)
                    put("uploadedWidth", it.uploadedWidth)
                    put("uploadedHeight", it.uploadedHeight)
                    put("original", it.original)
                })
            }
            val target = file(ctx)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(arr.toString())
                tmp.delete()
            }
        } catch (_: Exception) { }
    }
}
