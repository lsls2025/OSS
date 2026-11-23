package com.aurora.chat.util

import android.content.ContentValues
import android.graphics.Bitmap
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.aurora.chat.ErrorReporter
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把聊天图片保存到系统相册（Pictures/AuroraChat）。
 *
 * - 支持 http(s) 与 file:// 两种来源；网络图走 [HttpClient.downloadToFile] 流式落盘（带鉴权），全程不把整图缓冲进内存，避免大图 OOM。
 * - Android 10+(Q) 通过 MediaStore 写入公共目录，无需 WRITE_EXTERNAL_STORAGE 权限；
 *   低版本在无该权限时会失败（被 catch 为 false），属极少见场景。
 */
object GallerySaver {

    private const val TAG = "GallerySaver"

    /** 记录一条相册写入的失败诊断（writeTag 区分是哪个调用方） */
    private fun logFail(writeTag: String, stage: String, bitmap: Bitmap? = null, t: Throwable? = null) {
        val size = bitmap?.let { "${it.width}x${it.height} bytes=${it.allocationByteCount}" } ?: "-"
        ErrorReporter.warn(TAG, "$writeTag stage=$stage size=$size " + (t?.let { "err=${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }
    suspend fun saveImage(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "gallery_tmp_${System.currentTimeMillis()}.jpg")
        try {
            val ok = if (url.startsWith("file://")) {
                File(url.removePrefix("file://")).copyTo(tmp, overwrite = true)
                tmp.exists() && tmp.length() > 0
            } else {
                try { HttpClient.downloadToFile(url, tmp, AuroraApi.authToken) > 0 } catch (_: Exception) { false }
            }
            if (!ok) return@withContext false

            val resolver = context.contentResolver
            val name = "Aurora_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AuroraChat")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
            resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /**
     * 把内存中已生成的 Bitmap 直接写入系统相册（Pictures/AuroraChat）。
     * 用于「多选消息合并为一张长图」这类本地生成、没有网络来源的场景。
     * 调用方需自行回收 bitmap。
     */
    suspend fun saveBitmap(context: Context, bitmap: Bitmap, baseName: String = "AuroraChat"): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val name = "${baseName}_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AuroraChat")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: run {
                        logFail(baseName, "insert-null")
                        return@withContext false
                    }
                val ok = resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                } ?: false
                if (!ok) {
                    logFail(baseName, "write-failed", bitmap)
                    runCatching { resolver.delete(uri, null, null) }
                    return@withContext false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                ErrorReporter.debug(TAG, "$baseName saved ok name=$name size=${bitmap.width}x${bitmap.height}")
                true
            } catch (t: Throwable) {
                // 必须是 Throwable：OutOfMemoryError 不是 Exception，原来的 catch(_: Exception) 会让它直接崩出去
                logFail(baseName, "exception", bitmap, t)
                false
            }
        }
}
