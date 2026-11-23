package com.aurora.chat.util

import android.util.Log
import com.aurora.chat.data.api.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片尺寸嗅探器 —— 只下载图片头部前 2KB 就解析出宽高，
 * 避免为了拿个尺寸把整张图拉下来（省带宽、省时间）。
 *
 * 支持格式：PNG / JPEG / GIF / BMP / WebP。
 * 解析结果进程级内存缓存（URL → Pair(width, height)），同 URL 二次命中零开销。
 * 同一 URL 并发请求会被折叠：只发一次网络请求，其他等待结果。
 *
 * 若服务器不支持 Range 或格式识别失败，返回 null —— 调用方回退到 maxW/maxH 布局。
 */
object ImageSizeFetcher {

    private const val TAG = "ImageSizeFetcher"
    /** 前 2KB 足够命中所有主流图片格式的尺寸信息。JPEG 的 SOF marker 通常在头部 1KB 内。 */
    private const val HEAD_BYTES = 2048

    /** 已解析成功的尺寸缓存。 */
    private val sizeCache = ConcurrentHashMap<String, Pair<Int, Int>>()
    /** 正在进行中的请求（用于并发折叠）。 */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, Int>?>>()
    private val inflightMutex = Mutex()

    /** 清空缓存（低内存回调或测试用）。 */
    fun clearCache() {
        sizeCache.clear()
    }

    /**
     * 同步查缓存（不发网络），命中直接返回。
     */
    fun getCached(url: String): Pair<Int, Int>? = sizeCache[url]

    /**
     * 异步嗅探图片尺寸。
     * - 命中缓存立即返回；
     * - 同 URL 已有请求在途则复用其结果；
     * - 否则发 Range 请求下载头部并解析。
     *
     * @return (width, height) 或 null（失败/不支持）
     */
    suspend fun fetchSize(url: String): Pair<Int, Int>? {
        // 1) 内存缓存命中
        sizeCache[url]?.let { return it }

        // 2) 并发折叠：如果已经有人在发这个 URL 的请求，挂起等他的结果
        inflightMutex.withLock {
            inFlight[url]?.let { return@withLock it }
        }

        val deferred = CompletableDeferred<Pair<Int, Int>?>()
        inflightMutex.withLock { inFlight[url] = deferred }

        try {
            val result = doFetch(url)
            if (result != null) sizeCache[url] = result
            deferred.complete(result)
            return result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inflightMutex.withLock { inFlight.remove(url) }
        }
    }

    private suspend fun doFetch(url: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${HEAD_BYTES - 1}")
            .header("User-Agent", "AuroraChat-Android")
            .get()
            .build()

        HttpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                Log.w(TAG, "size fetch failed HTTP ${resp.code} url=$url")
                return@use null
            }
            val body = resp.body ?: return@use null
            val head = body.source().use { source ->
                val buf = ByteArray(HEAD_BYTES)
                var read = 0
                while (read < HEAD_BYTES) {
                    val n = source.read(buf, read, HEAD_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 0) return@use null
                if (read < HEAD_BYTES) buf.copyOf(read) else buf
            } ?: return@use null
            return@use parseSize(head)
        }
    }

    // ============================================================
    // 图片头部解析
    // ============================================================

    private fun parseSize(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 8) return null
        return when {
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            head[0] == (0x89).toByte() && head[1] == 0x50.toByte() &&
                    head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> parsePng(head)
            // JPEG: FF D8 FF
            head[0] == (0xFF).toByte() && head[1] == (0xD8).toByte() &&
                    head[2] == (0xFF).toByte() -> parseJpeg(head)
            // GIF: 47 49 46 38 ("GIF8")
            head[0] == 0x47.toByte() && head[1] == 0x49.toByte() &&
                    head[2] == 0x46.toByte() && head[3] == 0x38.toByte() -> parseGif(head)
            // BMP: 42 4D
            head[0] == 0x42.toByte() && head[1] == 0x4D.toByte() -> parseBmp(head)
            // WebP: 52 49 46 46 ("RIFF") … 偏移 8 处是 "WEBP"
            head.size >= 12 &&
                    head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
                    head[2] == 0x46.toByte() && head[3] == 0x46.toByte() &&
                    head[8] == 0x57.toByte() && head[9] == 0x45.toByte() &&
                    head[10] == 0x42.toByte() && head[11] == 0x50.toByte() -> parseWebp(head)
            else -> null
        }
    }

    /** PNG: 偏移 16 处 width(4) height(4) 大端。 */
    private fun parsePng(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24) return null
        val w = (b[16].toInt() and 0xFF) shl 24 or (b[17].toInt() and 0xFF) shl 16 or
                (b[18].toInt() and 0xFF) shl 8 or (b[19].toInt() and 0xFF)
        val h = (b[20].toInt() and 0xFF) shl 24 or (b[21].toInt() and 0xFF) shl 16 or
                (b[22].toInt() and 0xFF) shl 8 or (b[23].toInt() and 0xFF)
        if (w <= 0 || h <= 0 || w > 100_000 || h > 100_000) return null
        return w to h
    }

    /**
     * JPEG: 扫描 marker，找 SOF0 (0xFFC0) 或 SOF2 (0xFFC2)。
     * SOF marker 格式：FF C0/C2 | 长度(2) | 精度(1) | 高(2) | 宽(2)
     */
    private fun parseJpeg(b: ByteArray): Pair<Int, Int>? {
        var i = 2 // 跳过 FFD8
        while (i < b.size - 4) {
            if (b[i] != (0xFF).toByte()) { i++; continue }
            // 跳过填充 FF
            while (i < b.size && b[i] == (0xFF).toByte()) i++
            if (i >= b.size) return null
            val marker = b[i].toInt() and 0xFF
            i++
            // SOF0 = 0xC0, SOF2 = 0xC2 —— baseline/progressive DCT, 都有尺寸
            if (marker == 0xC0 || marker == 0xC2) {
                // 长度(2) + 精度(1) + 高(2) + 宽(2)
                if (i + 7 >= b.size) return null
                val h = (b[i + 3].toInt() and 0xFF) shl 8 or (b[i + 4].toInt() and 0xFF)
                val w = (b[i + 5].toInt() and 0xFF) shl 8 or (b[i + 6].toInt() and 0xFF)
                if (w <= 0 || h <= 0 || w > 100_000 || h > 100_000) return null
                return w to h
            }
            // 其他 marker：跳过其长度
            if (marker == 0xD8 || marker == 0xD9) continue // SOI/EOI 无长度
            if (i + 1 >= b.size) return null
            val len = (b[i].toInt() and 0xFF) shl 8 or (b[i + 1].toInt() and 0xFF)
            if (len < 2) return null
            i += len
        }
        return null
    }

    /** GIF: 偏移 6 处 width(2) little-endian, 偏移 8 处 height(2) little-endian。 */
    private fun parseGif(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 10) return null
        val w = (b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8)
        val h = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)
        if (w <= 0 || h <= 0 || w > 100_000 || h > 100_000) return null
        return w to h
    }

    /**
     * BMP: 偏移 18 处 width(4) little-endian，偏移 22 处 height(4) little-endian。
     * height 可能为负（顶向下 DIB），取绝对值。
     */
    private fun parseBmp(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 26) return null
        val w = (b[18].toInt() and 0xFF) or ((b[19].toInt() and 0xFF) shl 8) or
                ((b[20].toInt() and 0xFF) shl 16) or ((b[21].toInt() and 0xFF) shl 24)
        var h = (b[22].toInt() and 0xFF) or ((b[23].toInt() and 0xFF) shl 8) or
                ((b[24].toInt() and 0xFF) shl 16) or ((b[25].toInt() and 0xFF) shl 24)
        if (h < 0) h = -h
        if (w <= 0 || h <= 0 || w > 100_000 || h > 100_000) return null
        return w to h
    }

    /**
     * WebP: 偏移 12 处是 VP8/VP8L/VP8X 子格式。
     * - VP8 (lossy): 偏移 26 处 width(2) little-endian, 偏移 28 处 height(2) little-endian
     * - VP8L (lossless): 偏移 21 起，需要 4 字节拼一个 28bit 值
     * - VP8X (extended): 偏移 24 起 width(3), 偏移 27 起 height(3)
     */
    private fun parseWebp(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30) return null
        val sub = String(b, 12, 3, Charsets.US_ASCII)
        return when (sub) {
            "VP8 " -> {
                // lossy: start code 0x9D012A at offset 15
                val w = (b[26].toInt() and 0xFF) or ((b[27].toInt() and 0xFF) shl 8)
                val h = (b[28].toInt() and 0xFF) or ((b[29].toInt() and 0xFF) shl 8)
                if (w <= 0 || h <= 0) null else w to h
            }
            "VP8L" -> {
                // lossless: 偏移 21 起 4 字节 (28 bit) —— 低 14bit = w-1, 高 14bit = h-1
                val b0 = b[21].toInt() and 0xFF
                val b1 = b[22].toInt() and 0xFF
                val b2 = b[23].toInt() and 0xFF
                val b3 = b[24].toInt() and 0xFF
                val bits = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                val w = (bits and 0x3FFF) + 1
                val h = ((bits shr 14) and 0x3FFF) + 1
                if (w <= 0 || h <= 0) null else w to h
            }
            "VP8X" -> {
                // extended: 偏移 24 width(3), 偏移 27 height(3) —— 各减 1
                val w = ((b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or
                        ((b[26].toInt() and 0xFF) shl 16)) + 1
                val h = ((b[27].toInt() and 0xFF) or ((b[28].toInt() and 0xFF) shl 8) or
                        ((b[29].toInt() and 0xFF) shl 16)) + 1
                if (w <= 0 || h <= 0) null else w to h
            }
            else -> null
        }
    }
}
