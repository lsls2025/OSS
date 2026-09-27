package com.pm.manager.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.pm.manager.terminal.SiteApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/**
 * 远程图片加载器：内存 LRU + 磁盘缓存 + 采样解码。
 *
 * 原来的实现是「/api/download 拿 base64 → decodeByteArray 全量解码」，问题很实在：
 * - base64 比原始字节大约 1/3，一张 8MB 的图要同时持有 ~11MB 字符串 + 8MB 字节数组 + 解码后的
 *   ARGB 位图（8MP 的图就是 32MB），中低端机直接 OOM；
 * - 每次预览都重新下载，来回切图反复走网络；
 * - 不管 ImageView 多大都按原尺寸解码，纯浪费。
 *
 * 现在改成：流式下载到磁盘缓存 → 先读 bounds 算 inSampleSize → 按目标尺寸解码 → 存内存 LRU。
 */
object ImageLoader {

    /** 内存缓存上限取可用内存的 1/8。 */
    private val memoryCache: LruCache<String, Bitmap> = run {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private const val DIR = "imgcache"
    private const val DISK_LIMIT = 120L * 1024 * 1024
    private val diskLock = Mutex()
    private var cacheDirRef: File? = null

    private fun cacheDir(context: Context): File =
        cacheDirRef ?: File(context.cacheDir, DIR).apply { mkdirs(); cacheDirRef = this }

    private fun keyOf(relPath: String, maxPx: Int) = "$relPath@$maxPx"

    private fun md5(s: String): String =
        BigInteger(1, MessageDigest.getInstance("MD5").digest(s.toByteArray())).toString(16).padStart(32, '0')

    /**
     * 加载远程图片。
     * @param relPath 相对根目录的路径串
     * @param maxPx 目标最长边像素，用于计算采样率；传 0 表示按原图解码（仅全屏预览建议）
     */
    suspend fun load(
        context: Context,
        relPath: String,
        remote: List<String>,
        maxPx: Int = 720
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        val key = keyOf(relPath, maxPx)
        memoryCache.get(key)?.let { return@withContext Result.success(it) }

        runCatching {
            val file = ensureCached(context, relPath, remote)
            val bitmap = decodeSampled(file, maxPx)
                ?: throw IllegalStateException("图片解码失败")
            memoryCache.put(key, bitmap)
            bitmap
        }
    }

    /** 从内存缓存取（用于网格快速复用，取不到返回 null 由调用方决定是否发起加载）。 */
    fun peek(relPath: String, maxPx: Int): Bitmap? = memoryCache.get(keyOf(relPath, maxPx))

    private suspend fun ensureCached(context: Context, relPath: String, remote: List<String>): File {
        val dir = cacheDir(context)
        val file = File(dir, md5(relPath))
        diskLock.withLock {
            if (file.exists() && file.length() > 0) return file
            val tmp = File(dir, "${file.name}.tmp")
            try {
                SiteApi.downloadRaw(remote, tmp)
                if (tmp.length() <= 0) throw IllegalStateException("下载内容为空")
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            trimDisk(dir)
        }
        return file
    }

    /** 按采样率解码，避免大图一次性吃满内存。 */
    private fun decodeSampled(file: File, maxPx: Int): Bitmap? {
        if (maxPx <= 0) return BitmapFactory.decodeFile(file.absolutePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > maxPx && h / sample > maxPx) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    /** 磁盘缓存超过上限时，按最后修改时间淘汰最旧的一半。 */
    private fun trimDisk(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT) return
        files.sortedBy { it.lastModified() }
            .take((files.size / 2).coerceAtLeast(1))
            .forEach { it.delete() }
    }

    /** 退出登录时清空全部缓存（内存 + 磁盘）。 */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        memoryCache.evictAll()
        runCatching { cacheDir(context).deleteRecursively() }
        cacheDirRef = null
    }
}
