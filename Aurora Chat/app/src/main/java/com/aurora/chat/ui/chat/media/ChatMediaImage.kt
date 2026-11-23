package com.aurora.chat.ui.chat.media
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.network.HttpException
import coil.request.ImageRequest
import com.aurora.chat.ui.chat.resolveMediaUrl
import com.aurora.chat.util.ImageSizeFetcher
import androidx.compose.animation.core.Animatable
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 记录各图片缩略图在屏幕上的真实矩形（点击瞬间实时更新，含滚动偏移），
 * 供全屏预览做共享元素(hero)放大/缩回动画时校准起点位置（"回到原来位置"）。
 */
class ImageBoundsRegistry {
    private val map = mutableMapOf<String, Rect>()
    fun put(url: String, rect: Rect) { map[url] = rect }
    fun get(url: String): Rect? = map[url]
    fun clear() { map.clear() }

    companion object {
        /**
         * 引用缩略图专用键前缀。
         *
         * 引用里的缩略图和正文里的同一张图是**屏幕上两个不同的位置**：共用同一个 key 会互相覆盖，
         * 点引用缩略图时 hero 动画就会从正文那张图的位置飞出来。所以引用缩略图单独记一份。
         */
        const val QUOTE_KEY_PREFIX = "quote|"
    }

    /**
     * 取「引用缩略图」上报的矩形。
     *
     * 不取走：引用缩略图只在**布局变化**时上报，取走后再点同一条引用就再也拿不到，
     * 会退回正文那张图的矩形、动画又从别处飞出来。正文路径只读 [get]，不会读到这个键，因此不存在误用。
     */
    fun getQuote(url: String): Rect? = map[QUOTE_KEY_PREFIX + url]
}

/** hero 放大动画的起始变换：缩略图相对"居中 fit 全屏"的缩放与位移（progress 0→1 时插值到 1/0）。 */
data class HeroTransform(val scale0: Float, val tx0: Float, val ty0: Float)

/**
 * 「已被清理 / 已过期」图片的负结果缓存。
 *
 * 为什么要它：服务端对已被清理的媒体返回 404（AI 图 410），这种失败 Coil 不会缓存，
 * 于是每次把消息滑出屏幕再滑回来，都会重新嗅探尺寸 + 重新请求一遍，白跑流量也白压服务器。
 * 命中过一次就记下来，之后这个地址直接渲染"已被清理或过期"卡片，一个请求都不发。
 *
 * 只存内存：重启后重新探一次（万一服务端恢复了文件，不至于永远显示失效）；
 * 容量满了整批清空即可，不需要精确 LRU。
 */
/**
 * 图片原始尺寸缓存（URL → 宽高）。
 *
 * 为什么要独立记一份：[MediaFailureCache] 只记「这个地址没了」，但一旦标记失效，
 * 尺寸嗅探也不会再跑，缩略图就退化成没有尺寸的占位 → 被文字撑成一个小气泡。
 * 这里把**任何时候拿到的尺寸**（后端字段 / 头部嗅探 / 解码成功）都记下来，
 * 即使图片已被清理，气泡仍按原图比例占位，外观和正常图片一致。
 */
internal object MediaSizeCache {
    private const val MAX = 1200
    private const val PREF = "media_size_cache"
    private const val KEY = "sizes"
    private val map = java.util.Collections.synchronizedMap(
        LinkedHashMap<String, Pair<Int, Int>>()
    )
    private var prefs: android.content.SharedPreferences? = null

    /**
     * 冷启动读回上次嗅探到的图片尺寸（URL → 宽高）。
     * 原本 [map] 是纯内存缓存，进程死亡即丢，导致每次打开会话都要重新嗅探头部、
     * 缩略图在加载完成后才定高，在 reverseLayout 列表里表现为「加载完往上跳」。
     * 这里把尺寸落到 SharedPreferences，重启后首帧即能用真实比例占位，不再重排。
     */
    fun init(context: android.content.Context) {
        if (prefs != null) return
        val sp = context.applicationContext
            .getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
        prefs = sp
        sp.getStringSet(KEY, emptySet()).orEmpty().forEach { raw ->
            val parts = raw.split('\t')
            if (parts.size < 3) return@forEach
            val w = parts[1].toIntOrNull() ?: return@forEach
            val h = parts[2].toIntOrNull() ?: return@forEach
            if (w > 0 && h > 0) map[parts[0]] = w to h
        }
    }

    fun get(url: String): Pair<Int, Int>? = map[url]?.takeIf { it.first > 0 && it.second > 0 }

    fun put(url: String, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (map.size >= MAX) map.clear()
        map[url] = w to h
        persist()
    }

    private fun persist() {
        val out = HashSet<String>(map.size)
        synchronized(map) { map.forEach { (u, v) -> out.add("$u\t${v.first}\t${v.second}") } }
        runCatching { prefs?.edit()?.putStringSet(KEY, out)?.apply() }
    }
}

internal object MediaFailureCache {
    private const val MAX = 800
    private const val PREF = "media_failure_cache"
    private const val KEY = "entries"
    private const val LEGACY_KEY = "urls"

    /** url -> "w,h"，尺寸未知时为空串。 */
    private val entries = java.util.Collections.synchronizedMap(
        LinkedHashMap<String, String>()
    )
    private var prefs: android.content.SharedPreferences? = null

    /**
     * 冷启动读回上次记录的失效地址（连同它的尺寸）：
     * 重启后同一张图既不会再显示、也不会再请求，气泡还能保持原来的比例。
     */
    fun init(context: android.content.Context) {
        if (prefs != null) return
        val sp = context.applicationContext
            .getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
        prefs = sp
        sp.getStringSet(KEY, emptySet()).orEmpty().forEach { raw ->
            val parts = raw.split('\t')
            if (parts.isEmpty() || parts[0].isEmpty()) return@forEach
            entries[parts[0]] = if (parts.size >= 3) "${parts[1]},${parts[2]}" else ""
        }
        // 旧版本只存了 url 集合，兼容一下
        sp.getStringSet(LEGACY_KEY, emptySet()).orEmpty().forEach {
            if (!entries.containsKey(it)) entries[it] = ""
        }
    }

    fun isExpired(url: String): Boolean = entries.containsKey(url)

    /** 记下失效地址，顺带把尺寸一起存，图片没了但占位尺寸还在。 */
    fun markExpired(url: String, size: Pair<Int, Int>? = null) {
        if (entries.size >= MAX) entries.clear()
        entries[url] = if (size != null && size.first > 0 && size.second > 0) {
            "${size.first},${size.second}"
        } else ""
        persist()
    }

    /** 读回上次记下的尺寸（失效前拿到的），没有则 null。 */
    fun getSize(url: String): Pair<Int, Int>? {
        val v = entries[url] ?: return null
        if (v.isEmpty()) return null
        val p = v.split(',')
        val w = p.getOrNull(0)?.toIntOrNull() ?: return null
        val h = p.getOrNull(1)?.toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    private fun persist() {
        val out = HashSet<String>(entries.size)
        synchronized(entries) {
            entries.forEach { (u, s) ->
                out.add(if (s.isEmpty()) u else "$u\t${s.replace(',', '\t')}")
            }
        }
        runCatching { prefs?.edit()?.putStringSet(KEY, out)?.apply() }
    }
}

/**
 * 视频类扩展名，用于把失败提示区分图片/视频。
 */
private val VIDEO_EXT = setOf("mp4", "mov", "webm", "mkv", "avi", "m4v", "3gp", "flv")

/**
 * 将聊天原图 URL 转换为服务端压缩预览图 URL（最长边 1080、质量 85）。
 *
 * 仅对指向 /chat-media/ 的 http(s) 地址生效；本地文件（file://）与无法识别的地址原样返回。
 * 点开大图时仍使用原图 URL，因此这里只影响气泡内的默认显示，用于降低服务器带宽压力。
 */

internal fun chatMediaThumbUrl(resolved: String, topCrop: Boolean = false): String {
    if (!resolved.startsWith("http")) return resolved
    if (resolved.contains("/api/chat-media/thumb/")) return resolved
    // GIF 需要保留动画，且服务端不会为其生成静态压缩图，直接返回原图
    val ext = resolved.substringAfterLast('.').substringBefore('?').lowercase()
    if (ext == "gif") return resolved
    val marker = "/chat-media/"
    val idx = resolved.indexOf(marker)
    if (idx < 0) return resolved
    val base = resolved.substring(0, idx)
    val file = resolved.substring(idx + marker.length)
    val url = "$base/api/chat-media/thumb/$file?w=1080&q=80"
    // 超长图：让服务端只裁顶部一段再压（否则整张长图按最长边 1080 压完，
    // 宽度只剩几十像素，缩略图放大后必然糊成一片）。
    return if (topCrop) "$url&top=1" else url
}

/**
 * 全屏预览用的大图地址：聊天媒体改走服务端「长边 2048 + q90」压缩版。
 *
 * 原来预览直接拉原图——一张长截图动辄 5~10MB，服务端带宽和手机解码都吃满，
 * 打开时 hero 层一直盖着缩略图放大的模糊画面等原图。2048 长边在 1080~1440 宽的
 * 手机屏上看不出差别，体积却小一个数量级；服务端生成后还有磁盘缓存，重复打开零计算。
 *
 * 本地文件（file:// / content://）、GIF / WebP（可能带动画）原样返回，不重编码。
 */
internal fun chatMediaPreviewUrl(resolved: String): String {
    if (!resolved.startsWith("http")) return resolved
    if (resolved.contains("/api/chat-media/thumb/")) return resolved
    val ext = resolved.substringAfterLast('.').substringBefore('?').lowercase()
    if (ext == "gif" || ext == "webp") return resolved
    val marker = "/chat-media/"
    val idx = resolved.indexOf(marker)
    if (idx < 0) return resolved
    val base = resolved.substring(0, idx)
    val file = resolved.substring(idx + marker.length)
    // 治标：只按「最长边」压的长截图会被压成 184×2048，宽度不到屏幕 1/5，放大必糊。
    // minw 让服务端先保证宽度不低于一屏（1080），maxpix 再把总像素压回 4MP 以内：
    // 既让「整图 Fit」这一步就有足够像素，也不会因为 12000 高的长图把客户端解码顶爆。
    // 治本那一层是客户端区域解码（RegionZoomOverlay）：放大后直接从原图取 1:1 像素。
    return "$base/api/chat-media/thumb/$file?w=2048&q=90&minw=1080&maxpix=4000000"
}

/**
 * 根据图片原始尺寸（px）计算气泡内显示尺寸（dp），等比缩放到 maxW × maxH 内。
 * 比例计算与 density 无关（dp 比例 = px 比例），直接用 dp 值算即可。
 */
internal fun calcDisplaySizeDp(
    originalW: Int, originalH: Int,
    maxW: Dp, maxH: Dp
): Pair<Dp, Dp> {
    val ratioW = maxW.value / originalW
    val ratioH = maxH.value / originalH
    val ratio = minOf(ratioW, ratioH, 1f) // 不放大
    return (originalW * ratio).dp to (originalH * ratio).dp
}

/**
 * 竖屏长图判定阈值：高 / 宽 ≥ 2 即视为「竖屏长图」，走紧凑缩略（顶部裁切）。
 *
 * 原先阈值 3.0 只覆盖"两三屏拼起来的超长截图"（4~10 倍），而普通手机截图
 * 极限也就 2.2~2.4 倍（1080×2400=2.22、21:9 屏=2.33）—— 它们被漏在阈值外，
 * 按普通图塞进 150×200 框、高度拉到 200dp，在气泡里显得过大。
 * 降到 2.0 后，手机截图也进紧凑缩略（高度封顶 150dp、只显顶部一段，与微信一致），
 * 一屏能放下更多条，不再出现"竖屏截图还是那么大"。
 */
private const val LONG_IMAGE_MIN_RATIO = 2.0f

/**
 * 超宽（左右很长、上下很短）图片判定阈值：宽 / 高 ≥ 3。与 [LONG_IMAGE_MIN_RATIO] 左右对称：
 * 监控面板、长条截图等「上下基本很短、左右很长」的图，不能再被塞进 150×200 的框压成细白条。
 */
private const val WIDE_IMAGE_MIN_RATIO = 3.0f

/**
 * 长图缩略显示框比例：宽/高 = 150 : 330（≈ 手机屏幕 1:2.2）。
 *
 * 长截图本身就是手机屏竖着拼起来的，缩略框就按手机截图的比例算 —— 但高度**不突破**
 * 原来的 maxH 上限（200dp），于是框约为 91×200dp，跟普通手机截图的缩略图一样大，
 * 只是框里显示的是图片最上面那一段，点开仍看完整长图。
 * 注意：必须与后端 `?top=1` 的裁切比例一致，否则会二次裁切。
 */
internal const val LONG_THUMB_ASPECT = 150f / 330f

/**
 * 竖屏长图缩略框的最大高度（dp）：原来直接锁死 maxH=200dp，竖屏截图在气泡里显得过大；
 * 这里单独收到 165dp（比 200 紧凑，又比一度试过的 150 略大一点，不至于削太小），
 * 仅作用于竖屏长图分支，不动全局 maxW / maxH。
 */
private const val PORTRAIT_THUMB_MAX_H = 165f

/**
 * 超宽图缩略框尺寸（dp）：宽 [WIDE_THUMB_W]、高 [WIDE_THUMB_H]。
 *
 * 与超长竖图（顶部放大、高度锁 200）左右对称 —— 超宽图改为「中间放大」：
 * - 高度给到 [WIDE_THUMB_H]（紧凑小框，不再是十几 dp 的细白条，也不会过高）；
 * - 宽度封顶 [WIDE_THUMB_W]（≈ 普通图片缩略上限 150 的一半，绝不顶满气泡，左右不会特别长）；
 * - 显示时用 [androidx.compose.ui.layout.ContentScale.Crop] + 居中，从原图**中间**裁一段放大铺满，
 *   与微信一致（微信对这类图也是取中间放大、整体尺寸紧凑，一屏能并排/叠放更多条）。
 *
 * 尺寸依据：以 360dp 宽主流手机为例，气泡可用宽约 252dp；微信同类超宽缩略约 90~100dp 宽、一屏可见约 3.5 条，
 * 这里取 132×88dp（比最早 240×150 大幅收紧，又比一度试过的 120×80 略大一点），
 * 仍小于普通图片上限，避免「左右特别长、上下非常高」。
 *
 * 注：后端对超宽图不需要 `?top=1` 之类的定向裁切 —— `?w=1080` 已把它压成 1080×110 的窄条（文件本就小），
 * 客户端居中裁切即可，既不会二次裁切、也不增加带宽。
 */
internal const val WIDE_THUMB_W = 132f
internal const val WIDE_THUMB_H = 88f

/**
 * 缩略图布局结果：显示框尺寸 + 裁切模式。
 * - [cropTop]：超长竖图，只显示图片**顶部**一段（TopCenter）；
 * - [cropMiddle]：超宽横图，从图片**中间**裁一段放大显示（Center）。
 * 两者不会同时为真；普通图两者都为 false（完整 Fit 居中，不裁切）。
 */
internal data class ThumbLayout(
    val width: Dp,
    val height: Dp,
    val cropTop: Boolean = false,
    val cropMiddle: Boolean = false
)

/**
 * 计算气泡内缩略图布局。
 *
 * - 普通图片（高宽比 < 2）→ 等比缩进 maxW × maxH，完整显示，不裁一像素；
 * - 竖屏长图（高宽比 ≥ 2）→ 紧凑缩略：高度锁 [PORTRAIT_THUMB_MAX_H]，宽度按手机屏比例，
 *   框内只显示最上面那一段（QQ / 微信的做法），点开看完整内容；
 * - 超宽截图（宽高比 ≥ 3）→ 不再等比缩成细白条：「中间放大」—— 高度给到 [WIDE_THUMB_H]、
 *   宽度封顶 [WIDE_THUMB_W]，从原图中间裁一段放大铺满（Crop + 居中），与微信一致。
 */
internal fun calcThumbLayout(
    originalW: Int, originalH: Int,
    maxW: Dp = 150.dp, maxH: Dp = 200.dp
): ThumbLayout? {
    if (originalW <= 0 || originalH <= 0) return null
    // 竖屏长图：高度锁 PORTRAIT_THUMB_MAX_H（150dp，比全局 maxH=200 更紧凑），宽度按手机屏比例，只显顶部一段
    if (originalH.toFloat() / originalW >= LONG_IMAGE_MIN_RATIO) {
        return ThumbLayout((PORTRAIT_THUMB_MAX_H * LONG_THUMB_ASPECT).dp, PORTRAIT_THUMB_MAX_H.dp, cropTop = true)
    }
    // 超宽横图：中间放大裁切（Crop + 居中）—— 高度给足、宽度封顶气泡宽度
    if (originalW.toFloat() / originalH >= WIDE_IMAGE_MIN_RATIO) {
        return ThumbLayout(WIDE_THUMB_W.dp, WIDE_THUMB_H.dp, cropMiddle = true)
    }
    val (dw, dh) = calcDisplaySizeDp(originalW, originalH, maxW, maxH)
    return ThumbLayout(dw, dh)
}

/**
 * 读取本地图片文件的原始宽高（只解文件头 inJustDecodeBounds，不解码像素，开销极小）。
 * 支持 file:// 与绝对路径；非图片/文件不存在/读取失败返回 null，调用方仍回退到旧的固定框兜底。
 * 有了尺寸，容器才能按真实比例精确锁定 → 图片铺满容器 → 12dp 圆角真正落在图片上。
 */
internal fun localImageSize(resolved: String): Pair<Int, Int>? {
    val path = if (resolved.startsWith("file://")) {
        runCatching { android.net.Uri.parse(resolved).path }.getOrNull()
    } else resolved
    if (path.isNullOrEmpty() || !path.startsWith("/")) return null
    return runCatching {
        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opt)
        if (opt.outWidth > 0 && opt.outHeight > 0) opt.outWidth to opt.outHeight else null
    }.getOrNull()
}

/**
 * 重写后的图片渲染组件（取代旧 MediaImageView）。
 *
 * ## 布局策略（解决"正方形占位符难受"问题）
 * 1. 先用 ImageSizeFetcher 嗅探图片头部（仅 2KB）拿到原始宽高；
 * 2. 拿到尺寸后立即按比例计算出精确的占位符宽高，骨架占位用正确比例；
 * 3. Coil 异步加载完整图片，加载完替换骨架——整个过程气泡尺寸零跳动。
 * 4. 若嗅探失败（不支持 Range/格式未知），回退到 maxW × maxH 的限制 Box。
 *
 * 确保不出现"空气泡"（图片空白无法显示）：
 * - URL 为空 → 显示明确的"图片已失效"卡片；
 * - 加载中 → 灰色骨架占位（比例正确）；
 * - 加载失败 → 显示"图片加载失败 + 点击重试"卡片，重试时重建请求。
 *
 * URL 统一经 resolveMediaUrl 解析，确保相对/内网路径也能被客户端加载。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMediaImage(
    url: String,
    isMine: Boolean,
    loadMedia: Boolean,
    highlightAnim: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    onImageClick: (String) -> Unit,
    onBubbleLongPress: () -> Unit,
    /** 后端返回的原始尺寸，第一帧就用；为 null 或 w/h 为 0 时客户端嗅探兜底 */
    mediaSize: Pair<Int, Int>? = null,
    /** 把自身在屏幕上的真实矩形上报，供全屏 hero 动画校准起点；默认空实现不干扰其它调用点 */
    onBoundsMeasured: (String, Rect) -> Unit = { _, _ -> },
    /** 导出截图用：已经同步解码好的图片 Bitmap。非空时直接绘制，完全不走 Coil 异步管线，
     *  避免离屏导出时图片还在异步加载导致画空/时序抖动。key 为 resolveMediaUrl 后的 URL。 */
    forcedBitmap: ImageBitmap? = null
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val resolved = resolveMediaUrl(url)

    if (resolved.isEmpty()) {
        Box(
            Modifier.widthIn(max = 150.dp).heightIn(max = 200.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(com.aurora.chat.ui.theme.appTextSecondary()),
            contentAlignment = Alignment.Center
        ) {
            Text("图片已失效", fontSize = 13.sp, color = Color.White)
        }
        return
    }

    if (!loadMedia) {
        Box(
            Modifier.widthIn(max = 150.dp).heightIn(max = 200.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(com.aurora.chat.ui.theme.appTextSecondary().copy(alpha = 0.15f))
        )
        return
    }

    // ====== 导出截图用：直接绘制已解码的同步 Bitmap ======
    // 不走 Coil 异步管线：Bitmap 在 IO 线程已解码完成，这里同步绘制即可，
    // 离屏导出不会因为图片还没加载完而画成骨架/失败卡。
    if (forcedBitmap != null) {
        val (fw, fh) = forcedBitmap.width to forcedBitmap.height
        val fLayout = calcThumbLayout(fw, fh, 150.dp, 200.dp)
        val fContainer = Modifier
            .then(
                if (fLayout != null) {
                    Modifier.width(fLayout.width).height(fLayout.height)
                } else {
                    Modifier.widthIn(max = 150.dp).heightIn(max = 200.dp)
                }
            )
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (highlightAnim.value > 0.01f) Modifier.drawWithContent {
                    drawContent()
                    drawRect(Color(0xFFBFDBFE).copy(alpha = highlightAnim.value), size = size)
                } else Modifier
            )
            .combinedClickable(
                onClick = { onImageClick(resolved) },
                onLongClick = onBubbleLongPress,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .onGloballyPositioned { coords ->
                val p = coords.localToWindow(Offset.Zero)
                onBoundsMeasured(
                    resolved,
                    Rect(p.x, p.y, p.x + coords.size.width, p.y + coords.size.height)
                )
            }
        Box(fContainer) {
            Image(
                bitmap = forcedBitmap,
                contentDescription = "图片消息",
                contentScale = if (fLayout?.cropTop == true || fLayout?.cropMiddle == true) ContentScale.Crop else ContentScale.Fit,
                alignment = when {
                    fLayout?.cropTop == true -> Alignment.TopCenter
                    fLayout?.cropMiddle == true -> Alignment.Center
                    else -> Alignment.Center
                },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
            )
        }
        return
    }

    // ====== 图片尺寸获取优先级 ======
    // 1. 后端消息 JSON 返回的 mediaWidth/mediaHeight —— 零成本、第一帧就有
    // 2. ImageSizeFetcher 内存缓存 —— 同 URL 已嗅探过
    // 3. Range 请求嗅探图片头部 —— 兜底
    val maxW = 150.dp
    val maxH = 200.dp

    // 后端字段有效直接用，跳过嗅探
    val backendSize = mediaSize?.let { (w, h) -> if (w > 0 && h > 0) (w to h) else null }

    // 这个地址已经 404/410 过：不再嗅探、不再请求，直接渲染失效卡片。
    val knownExpired = MediaFailureCache.isExpired(resolved)

    // 尺寸来源优先级：后端字段 > 本地尺寸缓存 > 嗅探结果。
    // 缓存这一层很关键：图片失效后嗅探不会再跑，靠它才能保持原来的气泡比例。
    val cachedSize = remember(resolved) {
        MediaSizeCache.get(resolved) ?: MediaFailureCache.getSize(resolved)
    }
    var sniffedSize by remember(resolved, backendSize) {
        mutableStateOf(backendSize ?: cachedSize)
    }
    // 图片真正解码出来后拿到的尺寸（嗅探失败时的兜底）。
    // 分享聊天快照常常没有 w/h，服务端嗅探也可能失败；只把尺寸写进缓存而不参与当前布局的话，
    // 容器会一直停在 maxW×maxH 的大框里，图片 Fit 居中留白，边框就落到「框」上而不是图片上。
    var decodedSize by remember(resolved) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(sniffedSize) {
        sniffedSize?.let { (w, h) -> MediaSizeCache.put(resolved, w, h) }
    }

    // 后端没给尺寸才启动嗅探（已知失效的地址连嗅探都省掉）
    if (backendSize == null && !knownExpired) {
        val isHttp = resolved.startsWith("http")
        LaunchedEffect(resolved, isHttp) {
            if (!isHttp) {
                // 本地文件(file:// 或绝对路径):必须读出尺寸。
                // 拿不到尺寸时 displaySizeDp=null，容器会退化成 maxW×maxH 的固定框，
                // 图片被 ContentScale.Fit 居中缩进框内(letterbox)，而 clip(12dp) 圆角落在
                // 「框」的四角、图本身没贴到角 —— 表现为「图片是方角 + 四周空边，看着很奇怪」。
                // AI 对话里用户发的图是 file:// 直存，正是这个现象。只解文件头，不解码像素，开销极小。
                sniffedSize = withContext(Dispatchers.IO) { localImageSize(resolved) }
                return@LaunchedEffect
            }
            ImageSizeFetcher.getCached(resolved)?.let { sniffedSize = it; return@LaunchedEffect }
            runCatching {
                ImageSizeFetcher.fetchSize(resolved)
            }.onSuccess { size -> sniffedSize = size }
              .onFailure { Log.w("ChatMediaImage", "size sniff failed: ${it.message}") }
        }
    }

    // 根据尺寸算出精确的显示框；超长图会返回「约 91×200dp（手机屏比例、高度不超上限）+ 只显示顶部」。
    // 全部失败则 null，走 maxW/maxH 限制。
    // 嗅探失败时用解码出来的真实尺寸兜底：框随图片收敛，不会出现「图没这么大、框却这么大」。
    val effectiveSize = sniffedSize ?: decodedSize
    val thumbLayout: ThumbLayout? = effectiveSize?.let { (w, h) -> calcThumbLayout(w, h, maxW, maxH) }
    val cropTop = thumbLayout?.cropTop == true
    val cropMiddle = thumbLayout?.cropMiddle == true

    // 已知失效：直接显示占位卡片，不建请求。
    // 尺寸仍按原图比例（尺寸缓存在失效前就记下了）；拿不到尺寸时只保留一个贴合文字的小块，
    // 不再撑成大框——分享聊天快照里往往没有 w/h，撑大框就成了「图没那么大、框却那么大」。
    if (knownExpired) {
        Box(
            modifier = Modifier
                .then(
                    if (thumbLayout != null) {
                        Modifier.width(thumbLayout.width).height(thumbLayout.height)
                    } else {
                        // 尺寸未知（分享聊天快照没存 w/h、服务端也没给，文件又被清理）：
                        // 不要再退回 FALLBACK_IMAGE_W×H 那个大框，否则会出现
                        // 「图片本来没这么大，边框却框出这么大」的怪现象。
                        // 这里改成宽度上限 + 高度包裹，画成贴合文字的小提示块。
                        Modifier.widthIn(max = maxW)
                    }
                )
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xAA374151))
                // 长按仍可弹消息菜单（删除等），单击不做事：图上已经没有可看的内容
                .combinedClickable(
                    onClick = {},
                    onLongClick = onBubbleLongPress,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "图片已被清理或过期",
                fontSize = 12.sp,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        return
    }

    // 气泡内默认加载服务端压缩预览图以节省带宽；
    // 若压缩接口不可用（后端未部署）则自动回退为原图，保证可用。
    var useOriginal by remember(resolved) { mutableStateOf(false) }
    var retryNonce by remember { mutableStateOf(0) }
    val displayUrl = if (useOriginal) resolved else chatMediaThumbUrl(resolved, cropTop)
    // 动图（GIF / 动图 WebP）：必须显式挂 ImageDecoderDecoder（API 28+）。
    // 否则默认管线先用 BitmapFactoryDecoder 解出 GIF 首帧即算成功、不再尝试动画解码器，
    // 动图就退化成一张静止图——表现为"发出的 GIF 不动"（与 QuoteImageThumb、表情缩略同一套处理）。
    val displayExt = resolved.substringAfterLast('.').substringBefore('?').lowercase()
    val animated = displayExt == "gif" || displayExt == "webp"
    val model = remember(displayUrl, retryNonce, animated) {
        val builder = ImageRequest.Builder(ctx).data(displayUrl)
        if (animated && android.os.Build.VERSION.SDK_INT >= 28) {
            builder.decoderFactory(coil.decode.ImageDecoderDecoder.Factory())
        }
        builder.build()
    }
    val painter = rememberAsyncImagePainter(model)
    val state = painter.state

    LaunchedEffect(state) {
        // 解码成功就把真实尺寸记下来：以后这张图被服务端清理了，气泡仍按原比例占位
        if (state is AsyncImagePainter.State.Success) {
            val sz = painter.intrinsicSize
            if (sz.width.isFinite() && sz.height.isFinite() && sz.width > 0f && sz.height > 0f) {
                val w = sz.width.toInt()
                val h = sz.height.toInt()
                // 同时用于当前布局：嗅探不到尺寸的图片（分享聊天快照等）靠它把框收敛到真实比例
                if (decodedSize != (w to h)) decodedSize = w to h
                MediaSizeCache.put(resolved, w, h)
            }
            return@LaunchedEffect
        }
        val err = state as? AsyncImagePainter.State.Error ?: return@LaunchedEffect
        // 404 / 410 = 文件已被清理或过期，重试原图也一样，直接记账、不再回退重试
        val code = (err.result.throwable as? HttpException)?.response?.code
        if (code == 404 || code == 410) {
            MediaFailureCache.markExpired(resolved, sniffedSize ?: MediaSizeCache.get(resolved))
        } else if (!useOriginal) {
            // 压缩接口不可用（后端未部署/5xx 等）：回退原图再试一次
            useOriginal = true
        }
    }

    // ====== 最终布局：拿到尺寸前用 maxW/maxH 的 Box，拿到后用精确比例 ======
    val containerModifier = Modifier
        .then(
            if (thumbLayout != null) {
                Modifier.width(width = thumbLayout.width).height(height = thumbLayout.height)
            } else {
                Modifier.widthIn(max = maxW).heightIn(max = maxH)
            }
        )
        .clip(RoundedCornerShape(6.dp))
        // 微信式极细描边：0.5dp、10% 黑，仅用于把图片边缘与背景分开，绝不能加粗
        .border(0.5.dp, Color(0x1A000000), RoundedCornerShape(6.dp))
        .then(
            if (highlightAnim.value > 0.01f) Modifier.drawWithContent {
                drawContent()
                drawRect(Color(0xFFBFDBFE).copy(alpha = highlightAnim.value), size = size)
            } else Modifier
        )
        .combinedClickable(
            onClick = { onImageClick(resolved) },
            onLongClick = onBubbleLongPress,
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        )
        .onGloballyPositioned { coords ->
            // 上报缩略图在屏幕上的真实矩形（含滚动偏移），hero 动画据此从原位置放大/缩回。
            val p = coords.localToWindow(Offset.Zero)
            onBoundsMeasured(
                resolved,
                Rect(p.x, p.y, p.x + coords.size.width, p.y + coords.size.height)
            )
        }

    Box(containerModifier) {
        Image(
            painter = painter,
            contentDescription = "图片消息",
            // 超长图裁顶部（TopCenter）；超宽图从中间放大（Crop + 居中）；其余完整 Fit。
            contentScale = when {
                cropTop -> ContentScale.Crop
                cropMiddle -> ContentScale.Crop
                else -> ContentScale.Fit
            },
            alignment = when {
                cropTop -> Alignment.TopCenter
                cropMiddle -> Alignment.Center
                else -> Alignment.Center
            },
            modifier = Modifier.fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
        )

        when (state) {
            is AsyncImagePainter.State.Loading -> {
                // 加载占位：尺寸已由容器锁定（嗅探到的比例 或 maxW/maxH 限制），不会塌缩
                Box(
                    Modifier.fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(com.aurora.chat.ui.theme.appTextSecondary().copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("加载中…", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            }
            is AsyncImagePainter.State.Error -> {
                val ex = state.result.throwable
                // 文件被服务器清理/过期：普通媒体返回 404、AI 图返回 410（JSON code:410）。
                // 其余（网络异常、解码失败、5xx 等）属于真正的加载错误。
                val httpCode = (ex as? HttpException)?.response?.code
                val isExpired = httpCode == 404 || httpCode == 410
                val isVideo = resolved.substringAfterLast('.')
                    .substringBefore('?').lowercase() in VIDEO_EXT
                val failText = when {
                    isExpired -> if (isVideo) "视频已被清理或过期" else "图片已被清理或过期"
                    isVideo -> "视频加载失败"
                    else -> "图片加载失败"
                }
                Log.e("ChatMediaImage", "媒体加载失败 url=$resolved code=$httpCode err=${ex.message}")
                Box(
                    Modifier
                        // 尺寸未知时容器只是 maxW×maxH 的宽松约束，占位若 fillMaxSize 会被撑成
                        // 150×200 的大灰框；改成包裹内容，失败提示就只占它需要的那点位置。
                        .then(if (thumbLayout == null) Modifier else Modifier.fillMaxSize())
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xAA374151))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(failText, fontSize = 13.sp, color = Color.White)
                }
            }
            else -> {}
        }
    }
}
