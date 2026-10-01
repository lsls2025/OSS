package com.aurora.chat.ui.qr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.provider.MediaStore
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Hashtable

/**
 * 二维码内容约定（新版）：http://YOUR_SERVER_DOMAIN/#<userId>
 * 官网网址 + #用户ID 两个信息分离：
 *  - APP 外扫码（QQ/微信/相机/浏览器）识别为网址 → 直接打开官网首页；
 *  - APP 内扫码从 # 片段提取用户ID，把完整内容传给后端 resolve 接口加好友。
 * 同时向后兼容旧版 aurora://user?id=<userId> 和纯数字。
 */

/**
 * 生成二维码 Bitmap（白底黑码）。
 * 使用最高纠错级别 H，确保中心叠加图标后仍可被识别。
 */
fun generateQrBitmap(content: String, sizePx: Int, centerIcon: Bitmap? = null): Bitmap {
    val writer = QRCodeWriter()
    val hints = Hashtable<EncodeHintType, Any>().apply {
        put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
        put(EncodeHintType.MARGIN, 1)
    }
    val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    if (centerIcon != null) {
        return overlayCenterIcon(bitmap, centerIcon)
    }
    return bitmap
}

/**
 * 将图标叠加到二维码正中心。
 * 排版（从内到外）：图标 → 图标与分割线之间的小空隙 → 浅色分割线（把图标围起来）→ 分割线外的空隙 → 二维码内容。
 * 图标尺寸维持约码宽的 22% 不变。
 */
fun overlayCenterIcon(qrBitmap: Bitmap, icon: Bitmap): Bitmap {
    val size = qrBitmap.width
    // 头像（正方形圆角）边长 = 码宽的 22%，正好填满分割线内区域
    val iconSize = (size * 0.22f).toInt().coerceAtLeast(1)
    // 分割线描边宽度（浅灰色，细细一条）
    val strokeW = (size * 0.003f).toInt().coerceAtLeast(1)
    // 分割线外的空隙（白色，避免分割线贴到二维码黑码）
    val outerGap = (size * 0.02f).toInt().coerceAtLeast(1)
    // 分割线外框边长（= 头像 + 两侧描边，头像贴合分割线内缘）
    val frameSize = iconSize + strokeW * 2
    // 白底（含分割线外空隙）边长
    val bgSize = frameSize + outerGap * 2

    val result = qrBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)

    // 白色底（覆盖二维码中心被替换的区域，充当分割线外的空隙）
    val bgRect = RectF(
        (size - bgSize) / 2f,
        (size - bgSize) / 2f,
        (size + bgSize) / 2f,
        (size + bgSize) / 2f
    )
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    canvas.drawRoundRect(bgRect, bgSize * 0.15f, bgSize * 0.15f, bgPaint)

    // 分割线（浅灰色圆角边框，把头像围起来）
    val frameRect = RectF(
        (size - frameSize) / 2f,
        (size - frameSize) / 2f,
        (size + frameSize) / 2f,
        (size + frameSize) / 2f
    )
    val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E0E0.toInt() // 浅浅的灰色分割线
        style = Paint.Style.STROKE
        strokeWidth = strokeW.toFloat()
    }
    canvas.drawRoundRect(frameRect, frameSize * 0.15f, frameSize * 0.15f, framePaint)

    // 头像（正方形圆角，填满分割线内区域，贴合分割线内缘）
    // 注意：clipPath 的裁剪区域必须与头像实际绘制位置一致（居中），否则裁剪窗口错位导致头像不可见
    val scaledIcon = Bitmap.createScaledBitmap(icon, iconSize, iconSize, true)
    val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = (iconSize * 0.14f).coerceAtLeast(1f)
    val drawX = (size - iconSize) / 2f
    val drawY = (size - iconSize) / 2f
    val clipPath = android.graphics.Path().apply {
        addRoundRect(
            RectF(drawX, drawY, drawX + iconSize, drawY + iconSize),
            radius, radius, android.graphics.Path.Direction.CW
        )
    }
    canvas.save()
    canvas.clipPath(clipPath)
    canvas.drawBitmap(scaledIcon, drawX, drawY, avatarPaint)
    canvas.restore()
    if (scaledIcon != icon) scaledIcon.recycle()
    return result
}

/** 从 Bitmap 中解析二维码文本；失败返回 null */
fun parseQrFromBitmap(bitmap: Bitmap): String? {
    // 多级解析：原图 → 放大2x → 放大3x
    // 相册缩略图/低分辨率图直接解析容易丢失尾部内容，放大后 ZXing 完整解析率显著提高。
    val attempts = arrayOf(1, 2, 3)
    for (scale in attempts) {
        val target = if (scale == 1) bitmap else {
            Bitmap.createScaledBitmap(
                bitmap,
                bitmap.width * scale,
                bitmap.height * scale,
                true
            )
        }
        val text = decodeOnce(target)
        if (scale != 1 && target != bitmap) target.recycle()
        if (!text.isNullOrEmpty()) return text
    }
    return null
}

/** 把方形头像裁剪成圆形 Bitmap（二维码中心头像用） */
fun toCircleBitmap(src: Bitmap): Bitmap {
    val size = minOf(src.width, src.height)
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(
            Bitmap.createScaledBitmap(src, size, size, true),
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP
        )
    }
    val radius = size / 2f
    canvas.drawCircle(radius, radius, radius, paint)
    return output
}

/** 单次 ZXing 解码 */
private fun decodeOnce(bitmap: Bitmap): String? {
    return try {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader()
        val hints = Hashtable<com.google.zxing.DecodeHintType, Any>().apply {
            put(com.google.zxing.DecodeHintType.TRY_HARDER, true)
        }
        reader.setHints(hints)
        val result = reader.decode(BinaryBitmap(HybridBinarizer(source)))
        result.text
    } catch (_: Exception) {
        null
    }
}

/** 从二维码文本中提取 userId。
 *  兼容四种格式：
 *   1. 最新版 官网落地页 http://www.YOUR_SERVER_DOMAIN:5004/q.html?u=123（从 query 参数 u 取 ID）
 *   2. 历史网址 http://www.YOUR_SERVER_DOMAIN/#123（从 # 片段取 ID）
 *   3. 旧版 aurora://user?id=123
 *   4. 纯数字 123 */
fun extractUserId(text: String?): Long {
    if (text == null) return 0L
    val s = text.trim()
    // 网址格式
    if (s.startsWith("http://") || s.startsWith("https://")) {
        // 优先取 query 参数 u=（新版 /q.html?u=<id>）
        val qIdx = s.indexOf('?')
        if (qIdx >= 0) {
            s.substring(qIdx + 1).split('&').forEach { pair ->
                val kv = pair.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == "u") {
                    return kv[1].toLongOrNull() ?: 0L
                }
            }
        }
        // 兼容历史 # 片段格式 http://.../#<id>
        val hashIdx = s.indexOf('#')
        if (hashIdx >= 0) {
            return s.substring(hashIdx + 1).toLongOrNull() ?: 0L
        }
        return 0L
    }
    // 旧版 aurora://user?id=123
    if (s.startsWith("aurora://user?id=")) {
        return s.removePrefix("aurora://user?id=").toLongOrNull() ?: 0L
    }
    // 容错：纯数字也当作 userId
    return s.toLongOrNull() ?: 0L
}

/** 保存 Bitmap 到系统相册（Pictures/AuroraChat），返回是否成功 */
suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val bytes = ByteArrayOutputStream().use { bos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AuroraChat")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(bytes)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

/** 解析相册选图字节为 Bitmap */
fun decodeBitmapFromBytes(bytes: ByteArray): Bitmap? {
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (_: Exception) {
        null
    }
}

fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
