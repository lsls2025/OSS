package com.aurora.chat.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import kotlinx.coroutines.ensureActive
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

    /** 当前是否连接 Wi-Fi（用于决定是否后台整文件缓存视频，避免弱网/流量下双下载浪费带宽） */
    fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 下载视频到缓存（IO 线程执行），返回缓存 File，失败/取消返回 null。
     *
     * 先写 `.part`，校验长度完整后才改名为正式缓存文件：
     * - 播放器只认正式文件，不会把「半截视频」当缓存命中；
     * - 取消（快速退出播放器）时删掉 `.part`，立刻停下载、不浪费流量。
     */
    suspend fun downloadVideo(context: Context, url: String): File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cacheFile = getVideoCacheFile(context, url)
        if (cacheFile.exists() && cacheFile.length() > 0) return@withContext cacheFile

        val partFile = File(cacheFile.parentFile, cacheFile.name + ".part")
        try {
            val req = okhttp3.Request.Builder().url(url).build()
            com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val total = resp.body?.contentLength() ?: -1L
                val inputStream = resp.body?.byteStream() ?: throw java.io.IOException("空响应")
                var written = 0L
                FileOutputStream(partFile).use { outputStream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()  // 播放器关闭 → 协程取消 → 立刻停下载
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        outputStream.write(buffer, 0, bytesRead)
                        written += bytesRead
                    }
                }
                if (written <= 0L) throw java.io.IOException("空文件")
                if (total > 0 && written != total) throw java.io.IOException("下载不完整")
            }

            if (!partFile.renameTo(cacheFile)) {
                partFile.copyTo(cacheFile, overwrite = true)
                partFile.delete()
            }
            trimVideoCache(context)
            cacheFile
        } catch (e: kotlinx.coroutines.CancellationException) {
            partFile.delete()
            throw e
        } catch (e: Exception) {
            partFile.delete()
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
