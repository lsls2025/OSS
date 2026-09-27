package com.agit.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

/** 将 base64 data url 解码为 Compose ImageBitmap（用于显示已上传头像）。 */
fun decodeAvatarDataUrl(dataUrl: String): ImageBitmap? {
    return try {
        val base64 = dataUrl.substringAfter("base64,", "")
        if (base64.isBlank()) return null
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bmp.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/** 从相册 Uri 读取并压缩为 JPEG base64 data url（最大边长 256，质量 82）。 */
fun uriToAvatarDataUrl(context: Context, uri: Uri): String? {
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val src = BitmapFactory.decodeStream(input)
        input.close()
        val scaled = scaleAvatar(src, 256)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val bytes = out.toByteArray()
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

private fun scaleAvatar(src: Bitmap, max: Int): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= max && h <= max) return src
    val ratio = max.toFloat() / maxOf(w, h)
    val tw = (w * ratio).toInt().coerceAtLeast(1)
    val th = (h * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, tw, th, true)
}
