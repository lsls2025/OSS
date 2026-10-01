package com.aurora.chat.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
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
}
