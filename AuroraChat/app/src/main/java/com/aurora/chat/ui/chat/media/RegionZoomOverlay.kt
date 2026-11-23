package com.aurora.chat.ui.chat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 全屏看图的高清层：预览图只负责「瞬间可见 + 捏合动画」，
 * 一旦放大到肉眼能分辨像素的倍数，就从**原图**按当前可见区域 1:1 解码一块，
 * 叠在预览图上面。
 *
 * 为什么必须这样：预览图（本地解码或服务端压缩版）是按「整张图塞进屏幕」算的，
 * 长截图 1080×12000 压完只有 184×2048，宽度不到屏幕的 1/5；放大 = 拉伸这几个像素，
 * 再高的清晰度设置也救不回来。微信/QQ 放大后是清晰的原始像素，靠的正是区域解码，
 * 而不是「加载一张更大的整图」（整图会 OOM，且长图始终受最长边限制）。
 *
 * 注意：本层只负责画，不接管手势 —— 缩放/位移状态由调用方（现有预览器）提供，
 * 本层用同一套 scale/offset 反算出可见区域，因此不会和现有的单击/双击/捏合逻辑冲突。
 */
@Composable
internal fun RegionZoomOverlay(
    url: String,
    aspect: Float,
    scale: Float,
    offset: Offset,
    modifier: Modifier = Modifier,
) {
    // 1 倍看不出差别，也没有必要为「没放大」付出解码开销。
    // GIF 一律不叠：区域解码只能解出第一帧，盖上去等于把动图冻住（动图文件的像素本来就够）。
    if (scale < 1.2f || aspect <= 0f) return
    if (url.substringAfterLast('.').substringBefore('?').lowercase() == "gif") return

    val ctx = LocalContext.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var source by remember(url) { mutableStateOf<RegionSource?>(null) }
    var region by remember(url) { mutableStateOf<DecodedRegion?>(null) }

    LaunchedEffect(url) {
        // 打开原图（本地文件直接读；远程图先落盘一份，之后所有缩放都只读本地）
        source = RegionSource.open(ctx, url)
    }

    LaunchedEffect(url, scale, offset, viewport, source) {
        val src = source ?: return@LaunchedEffect
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        if (scale < 1.2f) {
            region = null
            return@LaunchedEffect
        }
        // 手势还在动的时候不解码（每一帧都解一块 = 卡顿）；停手 120ms 后取当前可见区域
        delay(120)
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        val rect = visibleRectInImage(src, aspect, vw, vh, scale, offset) ?: run {
            region = null
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) { src.decode(rect.rect, rect.drawScale) }
        val old = region
        region = decoded
        if (old != null) {
            // 等一帧再回收：旧位图可能还挂在上一帧的 display list 上，立刻 recycle 会崩
            delay(80)
            old.bitmap.recycle()
        }
    }

    DisposableEffect(url) {
        onDispose {
            region?.bitmap?.recycle()
            source?.close()
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
    ) {
        val r = region ?: return@Canvas
        val vw = size.width
        val vh = size.height
        val iw = r.sourceWidth.toFloat()
        val ih = r.sourceHeight.toFloat()
        if (iw <= 0f || ih <= 0f) return@Canvas
        // 与预览图完全同一套「Fit」几何：先算图片在盒子里的位置，再把区域矩形按像素比例映射过去。
        // 预览层的缩放/位移由外层 graphicsLayer 完成，这里只画内容，因此两者永远对齐。
        val fit = min(vw / iw, vh / ih)
        val fitW = iw * fit
        val fitH = ih * fit
        val left = (vw - fitW) / 2f
        val top = (vh - fitH) / 2f
        val dst = RectF(
            left + r.src.left / iw * fitW,
            top + r.src.top / ih * fitH,
            left + r.src.right / iw * fitW,
            top + r.src.bottom / ih * fitH,
        )
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(
                r.bitmap,
                Rect(0, 0, r.bitmap.width, r.bitmap.height),
                dst,
                REGION_PAINT
            )
        }
    }
}

/** 区域解码用画刷：关掉双线性过滤 —— 这块已经是 1:1 原始像素，插值只会让它变糊。 */
private val REGION_PAINT = Paint().apply {
    isAntiAlias = true
    isFilterBitmap = false
    isDither = true
}

private class DecodedRegion(
    val bitmap: Bitmap,
    val src: Rect,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/**
 * 当前屏幕上真正可见的那部分图片，换算到原图像素坐标（外扩一圈，减少小幅平移时的重新解码）。
 * 与预览层同源的变换：外层 graphicsLayer 以盒子中心为原点先缩后移。
 *
 * [RegionTarget.drawScale] = 图片像素 → 屏幕像素 的比例（fit × 缩放倍数），
 * 解码时用它决定采样率：解出「刚好够这张屏」的像素，既清晰又不爆内存。
 */
private class RegionTarget(val rect: Rect, val drawScale: Float)

private fun visibleRectInImage(
    src: RegionSource,
    aspect: Float,
    vw: Float,
    vh: Float,
    scale: Float,
    offset: Offset,
): RegionTarget? {
    val iw = src.width
    val ih = src.height
    if (iw <= 0 || ih <= 0) return null
    // 预览层可能已经降级成「长图只截顶部一段」的缩略图（?top=1），那张图的比例和原图不同、
    // 位置也对不上；比例差超过 2% 就不叠区域层，宁可不清晰也不能画错位置。
    val fileAspect = iw.toFloat() / ih.toFloat()
    if (fileAspect <= 0f) return null
    if (kotlin.math.abs(aspect - fileAspect) / fileAspect > 0.02f) return null
    val fit = min(vw / iw.toFloat(), vh / ih.toFloat())
    val fitW = iw * fit
    val fitH = ih * fit
    val left = (vw - fitW) / 2f
    val top = (vh - fitH) / 2f
    val cx = vw / 2f
    val cy = vh / 2f
    // 屏幕坐标 → 图层内容坐标（graphicsLayer: p = C + t + s·(q - C)）
    fun toLocalX(x: Float) = cx + (x - cx - offset.x) / scale
    fun toLocalY(y: Float) = cy + (y - cy - offset.y) / scale
    val lx0 = toLocalX(0f)
    val lx1 = toLocalX(vw)
    val ly0 = toLocalY(0f)
    val ly1 = toLocalY(vh)
    var x0 = (min(lx0, lx1) - left) / fitW * iw
    var x1 = (max(lx0, lx1) - left) / fitW * iw
    var y0 = (min(ly0, ly1) - top) / fitH * ih
    var y1 = (max(ly0, ly1) - top) / fitH * ih
    // 外扩：用户手一抖就重新解码的话，画面会一直闪
    val padX = (x1 - x0) * 0.15f
    val padY = (y1 - y0) * 0.15f
    x0 -= padX
    x1 += padX
    y0 -= padY
    y1 += padY
    if (x1 - x0 < 8f || y1 - y0 < 8f) return null
    val rect = Rect(
        floor(max(0f, x0)).toInt(),
        floor(max(0f, y0)).toInt(),
        ceil(min(iw.toFloat(), x1)).toInt(),
        ceil(min(ih.toFloat(), y1)).toInt(),
    )
    return RegionTarget(rect, fit * scale)
}

/** 原图访问器：持有 [BitmapRegionDecoder]，按需要的矩形解码，内存只跟「一块」相关。 */
private class RegionSource(
    private val decoder: BitmapRegionDecoder,
    val width: Int,
    val height: Int,
) {
    fun decode(rect: Rect, drawScale: Float): DecodedRegion? = runCatching {
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val opts = BitmapFactory.Options().apply {
            // 采样率按「这块在图里占多少屏幕像素」算：
            //   drawScale=1 → 1 图素正好 1 屏幕像素，原样解码（最清晰）；
            //   整图缩小着看时（drawScale<1）按比例抽点，省内存；
            //   再兜一道 8M 像素上限，避免极端长图的区域顶爆（同屏像素再多也看不出）。
            var sample = 1
            val drawnW = rect.width() * drawScale
            val drawnH = rect.height() * drawScale
            if (drawnW > 0f && drawnH > 0f) {
                while (rect.width() / sample > max(1f, drawnW) * 1.2f &&
                    rect.height() / sample > max(1f, drawnH) * 1.2f
                ) sample++
            }
            while ((rect.width() / sample).toLong() * (rect.height() / sample) > 8_000_000L) sample++
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = decoder.decodeRegion(rect, opts) ?: return null
        DecodedRegion(bmp, Rect(rect), width, height)
    }.getOrNull()

    fun close() {
        runCatching { decoder.recycle() }
    }

    companion object {
        suspend fun open(ctx: Context, url: String): RegionSource? = withContext(Dispatchers.IO) {
            val file = cacheOriginal(ctx, url) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val dec = runCatching {
                BitmapRegionDecoder.newInstance(file.absolutePath, false)
            }.getOrNull() ?: return@withContext null
            RegionSource(dec, bounds.outWidth, bounds.outHeight)
        }

        /**
         * 拿到「可随机读的本地原图」。
         * 本地文件直接用；远程图优先复用 Coil 磁盘缓存里已有的原图，
         * 没有才下载到 cacheDir（按 URL 摘要命名，第二次打开直接命中）。
         */
        private fun cacheOriginal(ctx: Context, url: String): File? {
            when {
                url.startsWith("file://") -> {
                    val p = runCatching { Uri.parse(url).path }.getOrNull()
                    return p?.let { File(it) }?.takeIf { it.isFile }
                }
                url.startsWith("/") -> return File(url).takeIf { it.isFile }
                url.startsWith("content:") -> {
                    // SAF/媒体库给的 content:// 不能随机读，先复制一份到缓存再区域解码
                    return runCatching {
                        val dir = File(ctx.cacheDir, "zoom_hd").apply { mkdirs() }
                        val f = File(dir, md5(url) + ".img")
                        if (f.isFile && f.length() > 0L) return f
                        ctx.contentResolver.openInputStream(Uri.parse(url))?.use { ins ->
                            FileOutputStream(f).use { ous -> ins.copyTo(ous) }
                        }
                        f.takeIf { it.isFile && it.length() > 0L }
                    }.getOrNull()
                }
                !url.startsWith("http") -> return null
            }
            // ① Coil 磁盘缓存（若这张原图刚好被加载过，省一次下载）
            runCatching {
                // Coil 2 的 Snapshot.data 是 okio Path（不是 java.io.File），toFile() 之后才是文件
                coil.Coil.imageLoader(ctx).diskCache?.get(url)?.data?.toFile()
            }.getOrNull()?.let { if (it.isFile) return it }
            // ② 自己下载一份
            val dir = File(ctx.cacheDir, "zoom_hd").apply { mkdirs() }
            val target = File(dir, md5(url) + ".img")
            if (target.isFile && target.length() > 0L) return target
            val tmp = File(dir, target.name + ".part")
            return runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                try {
                    if (conn.responseCode !in 200..299) return null
                    conn.inputStream.use { ins ->
                        FileOutputStream(tmp).use { ous -> ins.copyTo(ous) }
                    }
                } finally {
                    conn.disconnect()
                }
                if (tmp.length() <= 0L) null
                else {
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    target
                }
            }.getOrNull()
        }

        private fun md5(s: String): String {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }
    }
}
