package com.aurora.chat.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 视频首帧缩略图磁盘缓存。
 *
 * 背景：VideoThumbnailView 以前对网络 URL 直接用 MediaMetadataRetriever 取第一帧，
 * 每次进会话/切走再回来/二次查看，都会重新从服务器拉整段视频取帧（慢、费流量），
 * 且位图只 remember 在内存里、离开即丢。这里按 URL 把第一帧 JPEG 落盘：
 * 命中即用——零网络、宽高比第一时间就稳（帧不再 1:1 → 真实比例 乱跳）。
 */
object VideoThumbCache {
    private const val DIR = "video_thumbs"
    // 单张缩略图很小（几十~几百 KB），200MB 足够覆盖大量视频
    private const val MAX_BYTES = 200L * 1024 * 1024

    // 内存层：列表滚动时同一张封面反复进出组合，直接命中内存，不再读盘 + 解 JPEG
    private val memoryCache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    // 缩略图方向修正（按旋转元数据回正）后升版本：避免继续命中旧的、未旋转的横版封面
    private const val VER = "v2|"

    private fun memKey(url: String, maxDim: Int) = "$maxDim|$VER$url"

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    private fun fileFor(context: Context, url: String): File {
        val md5 = MessageDigest.getInstance("MD5").digest((VER + url).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(context), "$md5.jpg")
    }

    /**
     * 读取缓存的首帧；无缓存返回 null。
     * [maxDim] > 0 时按最长边降采样（全屏封面用，避免 4K 视频首帧整张大图进内存）。
     */
    fun load(context: Context, url: String, maxDim: Int = 0): Bitmap? {
        memoryCache.get(memKey(url, maxDim))?.let { return it }
        val f = fileFor(context, url)
        if (!f.exists() || f.length() == 0L) return null
        val bmp = runCatching {
            if (maxDim <= 0) {
                BitmapFactory.decodeFile(f.absolutePath)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / (sample * 2) >= maxDim) sample *= 2
                BitmapFactory.decodeFile(
                    f.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        }.getOrNull() ?: return null
        memoryCache.put(memKey(url, maxDim), bmp)
        return bmp
    }

    /** 把首帧写入磁盘缓存（JPEG）。 */
    fun save(context: Context, url: String, bitmap: Bitmap) {
        memoryCache.put(memKey(url, 0), bitmap)
        runCatching {
            val f = fileFor(context, url)
            FileOutputStream(f).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            trim(context)
        }
    }

    /**
     * 发送侧预热：从本地视频文件取第一帧存入缓存（键=上线后的完整 mediaUrl）。
     * 让自己刚发出的视频气泡首帧就是稳定帧，且不用再去网络取帧。
     */
    fun warmFromLocalFile(context: Context, url: String, localFile: File): Bitmap? {
        load(context, url)?.let { return it }
        if (!localFile.exists() || localFile.length() == 0L) return null
        val bmp = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(localFile.absolutePath)
                val frame = mmr.frameAtTime
                // 录屏/手机拍摄的视频常带旋转元数据：不回转会把竖屏封面存成横的
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (frame != null && rot % 360 != 0) {
                    android.graphics.Bitmap.createBitmap(
                        frame, 0, 0, frame.width, frame.height,
                        android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true
                    )
                } else frame
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull() ?: return null
        save(context, url, bmp)
        return bmp
    }

    /**
     * 本地视频（content:// / file:// / 绝对路径）首帧：先查缓存，未命中再解码并落盘。
     * 发送/上传中的视频气泡用它——文件就在本机，不该对着灰块等上传。
     */
    fun localFrame(context: Context, uri: String, maxDim: Int = 480): Bitmap? {
        load(context, uri, maxDim)?.let { return it }
        val bmp = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                if (uri.startsWith("content://")) {
                    mmr.setDataSource(context, android.net.Uri.parse(uri))
                } else {
                    mmr.setDataSource(if (uri.startsWith("file://")) uri.removePrefix("file://") else uri)
                }
                val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: mmr.frameAtTime
                    ?: return@runCatching null
                // 手机拍摄的视频常带旋转元数据，不回转首帧是横的
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                val upright = if (rot % 360 != 0) {
                    Bitmap.createBitmap(
                        frame, 0, 0, frame.width, frame.height,
                        android.graphics.Matrix().apply { postRotate(rot.toFloat()) }, true
                    )
                } else frame
                val longest = maxOf(upright.width, upright.height)
                if (maxDim > 0 && longest > maxDim) {
                    val s = maxDim.toFloat() / longest
                    Bitmap.createScaledBitmap(
                        upright,
                        (upright.width * s).toInt().coerceAtLeast(1),
                        (upright.height * s).toInt().coerceAtLeast(1),
                        true
                    )
                } else upright
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()
        if (bmp != null) save(context, uri, bmp)
        return bmp
    }

    /** 超出容量时按最旧优先删除。 */
    private fun trim(context: Context) {
        runCatching {
            val files = dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= MAX_BYTES) break
                total -= f.length()
                f.delete()
            }
        }
    }

    fun clearAll(context: Context) {
        runCatching { dir(context).deleteRecursively() }
    }
}
