package com.aurora.chat.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 提取 APK 的应用图标并做内存 + 磁盘缓存。
 *
 * 用系统内置的 [PackageManager.getPackageArchiveInfo] + [android.content.pm.ApplicationInfo.loadIcon]
 * 解析本地 APK 文件拿到其自带图标，比手写解析 AndroidManifest/资源表可靠得多。
 *
 * 找不到图标 / 下载失败时返回 null，由调用方显示默认 APK 占位符（不要用本应用图标）。
 */
object ApkIconExtractor {
    private val memCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun iconDir(ctx: Context): File = File(ctx.filesDir, "apk_icons").apply { mkdirs() }

    private fun cacheKey(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun getCached(ctx: Context, url: String): Bitmap? {
        memCache.get(url)?.let { return it }
        val f = File(iconDir(ctx), "${cacheKey(url)}.png")
        if (f.exists()) {
            try {
                val bmp = BitmapFactory.decodeFile(f.absolutePath)
                if (bmp != null) { memCache.put(url, bmp); return bmp }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun saveCache(ctx: Context, url: String, bmp: Bitmap) {
        memCache.put(url, bmp)
        try {
            File(iconDir(ctx), "${cacheKey(url)}.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (_: Exception) {}
    }

    /** 从本地 APK 文件解出应用图标；解析失败返回 null。 */
    fun extractFromFile(context: Context, apkPath: String): Bitmap? {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA) ?: return null
            val ai = info.applicationInfo ?: return null
            ai.sourceDir = apkPath
            ai.publicSourceDir = apkPath
            val icon: Drawable = ai.loadIcon(pm) ?: return null
            when (icon) {
                is BitmapDrawable -> icon.bitmap
                else -> {
                    val w = icon.intrinsicWidth.coerceAtLeast(1)
                    val h = icon.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    icon.setBounds(0, 0, w, h)
                    icon.draw(canvas)
                    bmp
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 异步确保拿到 APK 图标并回调（主线程）。
     * - 优先用本地已下载的 APK 文件 [localPath] 提取；
     * - 否则从 [url] 下载到临时缓存提取（成功后删临时 APK）；
     * - 结果永久缓存缩略图。
     * 失败回调 null，由调用方显示默认占位符。
     */
    fun ensureIcon(context: Context, url: String, localPath: String?, onResult: (Bitmap?) -> Unit) {
        getCached(context, url)?.let { onResult(it); return }
        io.launch {
            var bmp: Bitmap? = null
            var tempFile: File? = null
            try {
                val apkPath = if (localPath != null && File(localPath).exists()) {
                    localPath
                } else {
                    tempFile = File(context.cacheDir, "apk_icon_${cacheKey(url)}.apk")
                    val req = okhttp3.Request.Builder().url(url).header("Connection", "close").build()
                    com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        resp.body!!.byteStream().use { input ->
                            tempFile!!.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    tempFile?.absolutePath
                }
                if (apkPath != null) bmp = extractFromFile(context, apkPath)
                if (bmp != null) saveCache(context, url, bmp)
            } catch (_: Exception) {
                bmp = null
            } finally {
                try { tempFile?.delete() } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) { onResult(bmp) }
        }
    }
}
