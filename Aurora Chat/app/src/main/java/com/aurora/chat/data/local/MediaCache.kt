package com.aurora.chat.data.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** 媒体缓存管理器：图片/视频从网络加载后存入应用缓存，下次直接读缓存 */
object MediaCache {

    private const val VIDEO_CACHE_DIR = "media_cache/video"
    private const val MAX_VIDEO_CACHE_BYTES = 500L * 1024 * 1024 // 500MB

    /** 根据 URL 生成缓存文件名（MD5） */
    private fun cacheFileName(url: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 获取视频缓存文件路径 */
    fun getVideoCacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, VIDEO_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, cacheFileName(url) + ".mp4")
    }

    /** 同步下载视频到缓存（IO 线程调用），返回缓存 File，失败返回 null */
    fun downloadVideo(context: Context, url: String): File? {
        val cacheFile = getVideoCacheFile(context, url)
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        return try {
            val req = okhttp3.Request.Builder().url(url).build()
            com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                val inputStream = resp.body?.byteStream() ?: throw java.io.IOException("空响应")
                val outputStream = FileOutputStream(cacheFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.close()
            }

            // 缓存文件大于0字节才算成功
            if (cacheFile.length() > 0) {
                trimVideoCache(context)
                cacheFile
            } else {
                cacheFile.delete()
                null
            }
        } catch (e: Exception) {
            if (cacheFile.exists()) cacheFile.delete()
            null
        }
    }

    /** 限制视频缓存总大小，超出时删除最旧文件 */
    private fun trimVideoCache(context: Context) {
        try {
            val dir = File(context.cacheDir, VIDEO_CACHE_DIR)
            if (!dir.exists()) return
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= MAX_VIDEO_CACHE_BYTES) break
                total -= f.length()
                f.delete()
            }
        } catch (_: Exception) {}
    }

    /** 获取视频缓存文件（最优先调用），如果已缓存返回 file:// URI，否则返回 null */
    fun getCachedVideoUri(context: Context, url: String): Uri? {
        val file = getVideoCacheFile(context, url)
        if (file.exists() && file.length() > 0) {
            return Uri.fromFile(file)
        }
        return null
    }

    /** 获取所有缓存的视频文件列表 */
    private fun getCachedVideoFiles(context: Context): List<File> {
        val dir = File(context.cacheDir, VIDEO_CACHE_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.toList() ?: emptyList()
    }

    /** 清除所有媒体缓存 */
    fun clearAll(context: Context) {
        try {
            val dir = File(context.cacheDir, VIDEO_CACHE_DIR)
            if (dir.exists()) dir.deleteRecursively()
        } catch (_: Exception) {}
    }

    /** 获取媒体缓存总大小（字节） */
    fun getTotalCacheSize(context: Context): Long {
        var total = 0L
        try {
            val dir = File(context.cacheDir, VIDEO_CACHE_DIR)
            if (dir.exists()) {
                total += dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        } catch (_: Exception) {}
        return total
    }
}
