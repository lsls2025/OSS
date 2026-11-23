package com.aurora.chat.data.local

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义表情（用户从相册添加）的本地存储。
 *
 * 文件落在 filesDir/custom_stickers/，清单直接由目录扫描得出，不额外维护索引文件，
 * 避免「清单与磁盘不一致」的脏数据。目前只负责本地收藏与表情面板展示；
 * 跨设备同步 / 上传按后续需求接入（发送时走普通图片消息，对方能正常收到）。
 */
object CustomStickerStore {
    private const val DIR = "custom_stickers"
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif")

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 自定义表情文件绝对路径，按添加时间倒序（文件名带时间戳）。 */
    fun list(context: Context): List<String> = runCatching {
        dir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXT }
            ?.sortedByDescending { it.name }
            ?.map { it.absolutePath }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** 把相册 Uri 复制进私有目录，返回新文件绝对路径；读取失败返回 null。 */
    suspend fun add(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
            val ext = when {
                "png" in mime -> "png"
                "webp" in mime -> "webp"
                "gif" in mime -> "gif"
                "jpeg" in mime || "jpg" in mime -> "jpg"
                else -> "jpg"
            }
            val f = File(dir(context), "cs_${System.currentTimeMillis()}.$ext")
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
                true
            } ?: false
            if (copied && f.length() > 0) f.absolutePath else {
                f.delete()
                null
            }
        }.getOrNull()
    }

    fun remove(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)
}
