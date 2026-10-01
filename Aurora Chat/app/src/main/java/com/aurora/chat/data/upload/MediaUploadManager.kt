package com.aurora.chat.data.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.data.repository.LocalMessageStore
import com.aurora.chat.ui.chat.media.ChatMedia
import com.aurora.chat.ui.chat.media.MediaStore
import com.aurora.chat.ui.chat.resolveMediaUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 媒体消息后台发送管理器。
 *
 * 职责：
 * 1. 接收"用户点发送"的媒体（[enqueueFromUri]）：
 *    - 图片：本地压成 JPG、字节落到应用私有目录；
 *    - 视频：本地先压缩（Media3 Transformer，节省服务器带宽），压缩后再上传；
 *    再把任务写进持久化队列 [PendingUploadStore]，确保离开对话甚至退出软件都不丢。
 * 2. 在应用级协程里顺序处理队列：视频压缩 → 上传（带进度） + 调发送接口；成功后把真实消息落盘并通知前台替换占位。
 * 3. 失败自动重试（离线/服务端错误均重试，带退避），[init] 在 Application 启动时调用，
 *    因此"上次没发出去、下次打开自动重试"天然成立。
 *
 * 注意：本对象运行在 Application 级作用域，与具体对话屏幕生命周期无关。
 */
object MediaUploadManager {
    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<UploadEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    @Volatile
    private var processing = false

    private const val NETWORK_RETRY_MS = 30_000L
    private const val RETRY_INTERVAL_MS = 15_000L
    private const val MAX_SIDE = 2048
    private const val JPEG_QUALITY = 85

    // 视频压缩参数：码率上限 2Mbps（H264），音频转 AAC，控制上传体积
    private const val VIDEO_BITRATE = 2_000_000
    private const val VIDEO_AUDIO_BITRATE = 128_000

    // 记录每个视频的宽高比（用于上传占位块按比例显示），localId -> width/height
    private val videoAspectRatios = ConcurrentHashMap<Long, Float>()

    fun init(context: Context) {
        ctx = context.applicationContext
        processQueue()
    }

    /** 读取某视频的宽高比（供 UI 上传占位按比例显示），缺省 1f（正方形）。 */
    fun videoAspectRatio(localId: Long): Float = videoAspectRatios[localId] ?: 1f

    /**
     * 用户点发送时调用：图片本地压缩为 JPG、视频本地压缩为 MP4、落盘、入队，返回占位用的本地 id（<0 表示读取失败）。
     */
    fun enqueueFromUri(
        context: Context,
        friendId: Long,
        isGroup: Boolean,
        uri: Uri,
        mimeType: String,
        text: String,
        flashDuration: Int
    ): Long {
        val localId = System.currentTimeMillis() * 10000 + (0..9999).random()
        val isImage = mimeType.startsWith("image/") && !mimeType.contains("gif", ignoreCase = true)
        val isVideo = mimeType.startsWith("video/")

        val dir = File(context.filesDir, "media_uploads")
        dir.mkdirs()
        val file = File(dir, "up_$localId.bin")

        // 视频：先流式落盘（不占内存），记录宽高比，入队后由队列在后台压缩再上传
        if (isVideo) {
            if (!copyUriToFile(context, uri, file)) return -1L
            videoAspectRatios[localId] = videoAspectRatio(context, uri)
            val item = PendingUpload(
                id = localId, friendId = friendId, isGroup = isGroup, localPath = file.absolutePath,
                mimeType = mimeType, fileName = (uri.lastPathSegment ?: "video_$localId.mp4"),
                text = text, flashDuration = flashDuration,
                createdAt = System.currentTimeMillis(), status = PendingUpload.STATUS_COMPRESSING, retryCount = 0
            )
            PendingUploadStore.enqueue(context, item)
            processQueue()
            return localId
        }

        // 图片/其他：本地压缩后落盘（保持原有逻辑不变）
        val finalMime = if (isImage) "image/jpeg" else mimeType
        val bytes = try {
            if (isImage) compressImageToJpeg(context, uri) else readUriBytes(context, uri)
        } catch (_: Exception) {
            readUriBytes(context, uri)
        } ?: return -1L
        file.writeBytes(bytes)

        val fileName = if (isImage) "media_$localId.jpg" else (uri.lastPathSegment ?: "file")
        val item = PendingUpload(
            id = localId, friendId = friendId, isGroup = isGroup, localPath = file.absolutePath,
            mimeType = finalMime, fileName = fileName, text = text, flashDuration = flashDuration,
            createdAt = System.currentTimeMillis(), status = PendingUpload.STATUS_PENDING, retryCount = 0
        )
        PendingUploadStore.enqueue(context, item)
        processQueue()
        return localId
    }

    private fun processQueue() {
        if (processing) return
        processing = true
        scope.launch {
            try {
                while (true) {
                    val items = PendingUploadStore.all(ctx).filter { it.status != PendingUpload.STATUS_DONE }
                    if (items.isEmpty()) break
                    if (!isNetworkAvailable()) {
                        delay(NETWORK_RETRY_MS)
                        continue
                    }
                    for (item in items) {
                        // 1) 视频压缩阶段：先本地压缩，再进入上传
                        var current = item
                        if (current.mimeType.startsWith("video/") && current.status == PendingUpload.STATUS_COMPRESSING) {
                            PendingUploadStore.update(ctx, current.copy(status = PendingUpload.STATUS_COMPRESSING, retryCount = current.retryCount + 1))
                            val rawFile = File(current.localPath)
                            val compressedFile = File(rawFile.parentFile, "up_${current.id}_c.mp4")
                            val ok = runCatching {
                                compressVideo(ctx, rawFile, compressedFile) { p ->
                                    _events.tryEmit(UploadEvent.Compressing(current.id, p))
                                }
                            }.getOrDefault(false)
                            if (ok && compressedFile.exists() && compressedFile.length() > 0) {
                                current = current.copy(
                                    localPath = compressedFile.absolutePath,
                                    status = PendingUpload.STATUS_UPLOADING,
                                    fileName = "media_${current.id}.mp4"
                                )
                                PendingUploadStore.update(ctx, current)
                                runCatching { rawFile.delete() }
                            } else {
                                // 压缩失败/不可用：退回原视频直传，避免卡死
                                current = current.copy(status = PendingUpload.STATUS_UPLOADING)
                                PendingUploadStore.update(ctx, current)
                                runCatching { compressedFile.delete() }
                            }
                            // 压缩结束，重置为"上传中 0%"
                            _events.tryEmit(UploadEvent.Uploading(current.id, 0f))
                        } else {
                            current = current.copy(status = PendingUpload.STATUS_UPLOADING, retryCount = current.retryCount + 1)
                            PendingUploadStore.update(ctx, current)
                        }
                        // 2) 上传 + 发送阶段（统一用流式上传，实时上报进度；大文件不整读进内存）
                        try {
                            val up = AuroraApi.uploadChatMediaFile(File(current.localPath), current.fileName, current.mimeType) { p ->
                                _events.tryEmit(UploadEvent.Uploading(current.id, p))
                            }
                            if (!up.success || up.data == null) throw Exception(up.message)
                            val rawUrl = up.data!!.optString("url", "")
                            if (rawUrl.isEmpty()) throw Exception("empty media url")
                            val mediaUrl = resolveMediaUrl(rawUrl)
                            // 依据我们上传的 mimeType 分类，不信任服务器默认值（否则 APK/TXT 等会被误判成图片）
                            val mediaType = when {
                                item.mimeType.startsWith("video/") -> "video"
                                item.mimeType.startsWith("image/") && !item.mimeType.contains("gif", ignoreCase = true) -> "image"
                                else -> "file"
                            }
                            val sendRes = if (item.isGroup) {
                                ChatRepository.sendGroupMessage(item.friendId, item.text, 0, mediaType, mediaUrl, item.flashDuration)
                            } else {
                                ChatRepository.sendMessage(item.friendId, item.text, 0, mediaType, mediaUrl, item.flashDuration)
                            }
                            if (!sendRes.success) throw Exception(sendRes.message)
                            val serverId = sendRes.data ?: 0L
                            // 记录本机发送的真实媒体类型，供加载时覆盖后端返回的误标 media_type
                            com.aurora.chat.data.local.LocalMediaTypeStore.put(serverId, mediaType)

                            val info = MessageInfo(
                                id = serverId, fromUserId = AuroraApi.currentUserId, toUserId = item.friendId,
                                content = item.text, createdAt = System.currentTimeMillis() / 1000,
                                mediaType = mediaType, mediaUrl = mediaUrl, isRevoked = 0, flashDuration = item.flashDuration
                            )
                            LocalMessageStore.mergeAndSave(ctx, item.friendId, listOf(info))
                            MediaStore.put(serverId, ChatMedia(mediaType, resolveMediaUrl(mediaUrl)))

                            PendingUploadStore.remove(ctx, item.id)
                            runCatching { File(current.localPath).delete() }
                            _events.emit(UploadEvent.Succeeded(item.friendId, item.id, serverId, mediaType, mediaUrl, item.flashDuration))
                        } catch (_: Exception) {
                            PendingUploadStore.update(ctx, current.copy(status = PendingUpload.STATUS_FAILED))
                        }
                    }
                    if (PendingUploadStore.all(ctx).none { it.status != PendingUpload.STATUS_DONE }) break
                    delay(RETRY_INTERVAL_MS)
                }
            } finally {
                processing = false
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetworkInfo
            active != null && active.isConnected
        } catch (_: Exception) {
            true
        }
    }

    private fun readUriBytes(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, dest: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs -> ins.copyTo(outs) }
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /** 读取视频宽高比（width/height），失败默认 1f。 */
    private fun videoAspectRatio(context: Context, uri: Uri): Float {
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) w.toFloat() / h else 1f
            } finally {
                runCatching { mmr.release() }
            }
        } catch (_: Exception) {
            1f
        }
    }

    /**
     * 本地压缩视频为 H264/AAC MP4（Media3 Transformer），码率上限 [VIDEO_BITRATE]。
     * 压缩进度通过 [onProgress] 回调（0~1）。成功返回 true 且 [output] 写入压缩结果。
     */
    private suspend fun compressVideo(context: Context, input: File, output: File, onProgress: (Float) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            val encoderFactory = try {
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build()
                    )
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(VIDEO_AUDIO_BITRATE).build()
                    )
                    .build()
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            val request = try {
                TransformationRequest.Builder()
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build()
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            val transformer = try {
                Transformer.Builder(context)
                    .setTransformationRequest(request)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                runCatching { transformer.cancel() }
            }
            // media3 1.5.x 移除了 Listener 进度回调，改为轮询 getProgress(ProgressHolder)
            val progressHolder = ProgressHolder()
            val pollJob = scope.launch {
                while (cont.isActive) {
                    try {
                        if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.toFloat() / 100f)
                        }
                    } catch (_: Exception) {
                    }
                    delay(200)
                }
            }
            try {
                transformer.start(MediaItem.fromUri(Uri.fromFile(input)), output.absolutePath)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }

    /**
     * 本地压缩图片为 JPG：长边不超过 [MAX_SIDE]，质量 [JPEG_QUALITY]。
     * 不压得太狠（85%）以保留观感；GIF 已在调用方排除（保留动图）。
     */
    private fun compressImageToJpeg(context: Context, uri: Uri): ByteArray {
        val raw = readUriBytes(context, uri) ?: throw java.io.IOException("cannot read uri")
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        return try {
            val (w, h) = bitmap.width to bitmap.height
            val scale = if (w > MAX_SIDE || h > MAX_SIDE) {
                val r = MAX_SIDE.toFloat() / maxOf(w, h)
                if (r < 1f) r else 1f
            } else 1f
            val target = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            target.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (target != bitmap) target.recycle()
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}

sealed class UploadEvent {
    data class Succeeded(
        val friendId: Long,
        val localId: Long,
        val serverId: Long,
        val mediaType: String,
        val mediaUrl: String,
        val flashDuration: Int
    ) : UploadEvent()

    /** 视频本地压缩中，[progress] 0~1。 */
    data class Compressing(val localId: Long, val progress: Float) : UploadEvent()

    /** 上传中，[progress] 0~1。 */
    data class Uploading(val localId: Long, val progress: Float) : UploadEvent()
}
