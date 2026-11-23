package com.aurora.chat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
/**
 * 聊天背景图（按好友维度，本地存储）。
 *
 * 入口：好友资料页 → 右上角「⋮」→「背景图片」。
 * 效果：与该好友的对话页，消息区背景由默认浅灰（AI 会话为纯白）改为用户上传的图片，
 *       中心裁剪铺满（cover）。经典/液态模式都生效——图片不参与玻璃折射。
 *
 * 存储：filesDir/chat_bg/<当前用户id>/bg_<好友id>.jpg（多账号隔离，JPEG 长边≤1920 控体积）。
 * 账号维度：好友 id 为正、群/通知/AI 会话 id 为负（见 [isGroupChat]），两者不会串。
 */
object ChatBackgroundStorage {

    private const val TAG = "ChatBg"
    private const val DIR = "chat_bg"
    private const val MAX_DIM = 1920
    private const val QUALITY = 85
    private const val CACHE_LIMIT = 8

    /**
     * 变更世代：保存/移除后 +1。
     * [chatBackground] 读它做重组 key——上传完成后，已打开的对话页下一帧即换新背景。
     */
    var revision by mutableStateOf(0)
        private set

    // 必须线程安全：chatBackground 在聊天页有 3 处，各自在 Dispatchers.IO 上并发调用 load()，
    // 会并发执行 cache[k]=... 与 trimCache() 的遍历/删除。普通 HashMap 在这种并发写会写坏
    // （死循环/丢数据）→ 主线程卡死/ANR，表现为"带背景图的会话要点好几下才进、甚至闪退"。
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    private fun uid(): Long = com.aurora.chat.data.api.AuroraApi.currentUserId

    private fun fileFor(context: Context, uid: Long, friendId: Long): File {
        val dir = File(context.filesDir, "$DIR/$uid")
        dir.mkdirs()
        return File(dir, "bg_$friendId.jpg")
    }

    /** 该好友当前是否设置过背景图（主线程轻量 File.exists） */
    fun has(context: Context, friendId: Long): Boolean {
        val u = uid()
        if (u <= 0) return false
        return fileFor(context, u, friendId).exists()
    }

    /** 只查内存缓存，不解码不进磁盘：给合成首帧用，避免先闪一下默认灰 */
    fun peek(friendId: Long): ImageBitmap? {
        val u = uid()
        if (u <= 0) return null
        return cache["$u-$friendId"]
    }

    /** 内存 → 磁盘加载。调用方自行放 IO 线程（解码可能耗时几十毫秒）。 */
    fun load(context: Context, friendId: Long): ImageBitmap? {
        val u = uid()
        if (u <= 0) return null
        val k = "$u-$friendId"
        cache[k]?.let { return it }
        val f = fileFor(context, u, friendId)
        if (!f.exists()) return null
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        val img = bmp.asImageBitmap()
        cache[k] = img
        return img
    }

    /** 保存为该会话的背景图。成功返回 true，并刷新内存缓存与世代号。 */
    fun save(context: Context, friendId: Long, source: Bitmap): Boolean {
        val u = uid()
        if (u <= 0) return false
        return try {
            // 先按屏幕宽高比居中裁剪：正方形等图片不再左右溢出（侧滑/拖动时不露边），只存裁剪后的版本
            val cropped = centerCropToScreen(context, source)
            val scaled = downscale(cropped, MAX_DIM)
            val target = fileFor(context, u, friendId)
            val tmp = File(target.absolutePath + ".tmp")
            FileOutputStream(tmp).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            cache["$u-$friendId"] = scaled.asImageBitmap()
            trimCache()
            revision++
            true
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            false
        }
    }

    /** 移除背景图，恢复默认灰色 */
    fun clear(context: Context, friendId: Long) {
        val u = uid()
        if (u <= 0) return
        runCatching { fileFor(context, u, friendId).delete() }
        cache.remove("$u-$friendId")
        revision++
    }

    /**
     * 从相册 Uri 解码背景图：先按 [MAX_DIM] 降采样，再按 EXIF 方向旋转
     * （否则竖拍照片在对话里会横躺）。返回的 Bitmap 交给 [save]。
     */
    fun decodeUri(context: Context, uri: Uri): Bitmap? {
        return try {
            // 第一遍：仅取尺寸（inJustDecodeBounds 模式下 decodeStream 恒返回 null，属正常，
            // 不能对其做 ?: return null，否则任何图都解不出来）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val w = bounds.outWidth
            val h = bounds.outHeight
            // 第二遍：按尺寸降采样解码；取不到尺寸就按原图直解，交给 save 里 downscale 兜底
            val opts = BitmapFactory.Options().apply {
                if (w > 0 && h > 0) {
                    var s = 1
                    while (max(w, h) / (s * 2) >= MAX_DIM) s *= 2
                    inSampleSize = s
                }
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            applyExifRotation(context, uri, decoded)
        } catch (e: Exception) {
            Log.e(TAG, "decodeUri failed", e)
            null
        }
    }

    /** 按 EXIF 方向旋转（框架 ExifInterface 没有 rotationDegrees，按 TAG_ORIENTATION 换算） */
    private fun applyExifRotation(context: Context, uri: Uri, decoded: Bitmap): Bitmap {
        val rotation = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val orientation = ExifInterface(pfd.fileDescriptor)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (rotation == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotation) }
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrDefault(decoded)
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val m = max(w, h)
        if (m <= maxDim || w <= 0 || h <= 0) return src
        val scale = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(
            src,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /** 居中裁剪到屏幕宽高比：正方形等图片不再左右溢出（侧滑/拖动时不露边）。 */
    private fun centerCropToScreen(context: Context, src: Bitmap): Bitmap {
        val dm = context.resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        if (sw <= 0 || sh <= 0) return src
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val targetRatio = sw.toFloat() / sh.toFloat()
        val srcRatio = w.toFloat() / h.toFloat()
        return try {
            if (srcRatio > targetRatio) {
                // 源更宽（如正方形）→ 裁掉左右，只留中间
                val newW = (h * targetRatio).toInt().coerceIn(1, w)
                Bitmap.createBitmap(src, (w - newW) / 2, 0, newW, h)
            } else {
                // 源更高 → 裁掉上下
                val newH = (w / targetRatio).toInt().coerceIn(1, h)
                Bitmap.createBitmap(src, 0, (h - newH) / 2, w, newH)
            }
        } catch (e: Exception) {
            src
        }
    }

    private fun trimCache() {
        if (cache.size > CACHE_LIMIT) {
            val it = cache.keys.iterator()
            var overflow = cache.size - CACHE_LIMIT
            while (overflow > 0 && it.hasNext()) {
                it.next()
                it.remove()
                overflow--
            }
        }
    }
}

/** 对话页默认背景：所有会话统一浅灰（与 ChatImageExporter.chatBgOf 保持一致）。
 *  AI 对话改成普通气泡后不再用纯白底，顶栏 / 标题栏的颜色观感与普通对话一致。 */
fun defaultChatBg(friendId: Long): Color =
    if (com.aurora.chat.ui.theme.isAppDark()) com.aurora.chat.ui.theme.appSurface() else Color(0xFFEDEDED)


/**
 * 聊天背景 Modifier：该好友设置过背景图片 → 图片中心裁剪铺满；否则沿用默认底色。
 *
 * 放在原 `.background(灰/白)` 的位置即可，链上后续修饰符（imePadding/clipToBounds 等）不受影响。
 * 群聊/通知/AI 会话（id ≤ 0）永远走默认底色——自定义背景只在 1:1 好友会话生效。
 */
@Composable
fun Modifier.chatBackground(friendId: Long): Modifier {
    val context = LocalContext.current
    // 读 revision：上传/移除背景后，已打开的对话页随重组换图
    val revision = ChatBackgroundStorage.revision
    val bgImage by produceState<ImageBitmap?>(
        initialValue = ChatBackgroundStorage.peek(friendId),
        key1 = friendId,
        key2 = revision
    ) {
        value = withContext(Dispatchers.IO) {
            if (friendId <= 0) null else ChatBackgroundStorage.load(context, friendId)
        }
    }
    // 铺法（cover 的缩放/偏移）在组合期算好，绘制期不许再算：
    // 这层可能被录进玻璃折射图层，record 过程中重算/分配会显著拖慢并放大递归风险。
    return this.drawWithContent {
        val img = bgImage
        if (img == null) {
            drawRect(defaultChatBg(friendId))
        } else {
            drawChatBackgroundCover(img)
        }
        drawContent()
    }
}


/** 中心裁剪铺满（cover），与 ImageView 的 CENTER_CROP 一致 */
private fun DrawScope.drawChatBackgroundCover(img: ImageBitmap) {
    val iw = img.width.toFloat()
    val ih = img.height.toFloat()
    if (iw <= 0f || ih <= 0f || size.width <= 0f || size.height <= 0f) return
    val scale = maxOf(size.width / iw, size.height / ih)
    val dw = (iw * scale).toInt()
    val dh = (ih * scale).toInt()
    val dx = ((size.width - dw) / 2f).toInt()
    val dy = ((size.height - dh) / 2f).toInt()
    clipRect {
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(dx, dy),
            dstSize = IntSize(dw, dh),
            filterQuality = FilterQuality.Medium
        )
    }
}
