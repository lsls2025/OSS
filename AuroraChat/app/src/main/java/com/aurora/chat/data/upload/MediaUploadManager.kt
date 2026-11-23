package com.aurora.chat.data.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
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
import com.aurora.chat.ui.chat.ensureMediaNameWithExt
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    private val processing = AtomicBoolean(false)

    // 服务端限流退避截止时间：期间队列整批等待，不再重发（重发必然再被拒）
    @Volatile private var rateLimitedUntilMs = 0L

    // 批量发送时串行做本地预处理（解码/压缩/落盘）：避免一次选 10 张图同时全分辨率解码导致内存暴涨，
    // 同时因为整体在 IO 线程执行，不会阻塞 UI。
    private val prepareMutex = Mutex()

    private const val NETWORK_RETRY_MS = 30_000L
    private const val RETRY_INTERVAL_MS = 15_000L
    // 服务端明确限流（如「本群一分钟内只能发 N 条消息」）后的退避时间：期间不再反复重试，
    // 否则 10 张图会被同一分钟额度卡住，每隔 15 秒失败一次、反复弹"发送失败"，用户完全不知道发生了什么。
    private const val RATE_LIMIT_BACKOFF_MS = 60_000L
    // 单次上传（含调用发送接口）超时：防止弱网/服务器无响应时无限卡在 0%，超时即转失败并自动重试
    private const val UPLOAD_TIMEOUT_MS = 90_000L
    // 同时在途的上传任务数：与批量选择上限（10）对齐，批量发图时不再排队等闸门
    private const val MAX_CONCURRENT_UPLOADS = 10
    // 服务端单文件上传上限 100MB（见后端 handleUploadChatMedia），超出必被拒
    private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024
    private const val MAX_SIDE = 2048
    private const val JPEG_QUALITY = 85

    // 视频压缩参数：码率上限 2Mbps（H264），音频转 AAC，控制上传体积
    private const val VIDEO_BITRATE = 2_000_000
    private const val VIDEO_AUDIO_BITRATE = 128_000

    // 记录每个视频的宽高比（用于上传占位块按比例显示），localId -> width/height
    private val videoAspectRatios = ConcurrentHashMap<Long, Float>()

    // 在途上传任务：localId -> 协程 Job。供 cancelUpload 中断正在进行的压缩/上传，
    // 实现"上传中即可撤回"，不必等它跑到 100%。
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    // 已撤回但还在后台预处理（尚未入队）的 localId：避免"撤回后压缩完又被塞回队列上传"。
    private val cancelledIds = ConcurrentHashMap.newKeySet<Long>()

    fun init(context: Context) {
        ctx = context.applicationContext
        processQueue()
    }

    /** 读取某视频的宽高比（供 UI 上传占位按比例显示），缺省 1f（正方形）。 */
    fun videoAspectRatio(localId: Long): Float = videoAspectRatios[localId] ?: 1f

    /**
     * 用户点发送时调用：立即返回占位用的本地 id。
     *
     * 解码/压缩/落盘等重活不再在主线程同步执行，而是丢到后台串行处理：
     * 批量选 10 张图时，占位气泡可以立刻全部出现，UI 也不会被压缩阻塞（进度事件得以及时上屏）。
     * 预处理失败时抛 [UploadEvent.Failed]（permanent），由 UI 把占位转成失败气泡。
     */
    fun enqueueFromUri(
        context: Context,
        friendId: Long,
        isGroup: Boolean,
        uri: Uri,
        mimeType: String,
        text: String,
        flashDuration: Int,
        // 语音时长（秒）：仅 mimeType 为 audio/* 时使用，随消息以 mediaWidth 字段上送。
        // 参数列表内不能用 /** */ 块注释（"audio/*" 还会触发嵌套块注释，导致 Unclosed comment）。
        voiceDuration: Int = 0,
        // 原图：相册里勾了「原图」时为 true —— 图片不重新编码、视频不压缩，按原始字节直传。
        original: Boolean = false
    ): Long {
        val localId = System.currentTimeMillis() * 10000 + (0..9999).random()
        val isImage = mimeType.startsWith("image/") && !mimeType.contains("gif", ignoreCase = true)
        val isGif = mimeType.contains("gif", ignoreCase = true)
        val isVideo = mimeType.startsWith("video/")
        val isAudio = mimeType.startsWith("audio/")
        // SVG 是 XML 文本，BitmapFactory 解不了；直接上传 .svg 又会被服务端（禁止可脚本渲染格式）
        // 按普通文件落库、无扩展名时兜底成 .bin。所以发送前先本地渲染成 PNG，整条链路按普通图片走。
        val isSvg = mimeType.contains("svg", ignoreCase = true)

        val dir = File(context.filesDir, "media_uploads")
        dir.mkdirs()
        val file = File(dir, "up_$localId.bin")

        // 视频宽高比只读元数据，保持同步：调用方拿到 id 后要立刻按比例渲染占位块
        if (isVideo) {
            runCatching { videoAspectRatios[localId] = videoAspectRatio(context, uri) }
        }

        scope.launch {
            val prepared = prepareMutex.withLock {
                try {
                    if (isVideo) {
                        // 视频：流式落盘（不占内存），入队后由队列在后台压缩再上传
                        copyUriToFile(context, uri, file)
                    } else {
                        val bytes = try {
                            when {
                                // SVG 无论是否勾了「原图」都转 PNG：.svg 原样上传会被服务端当危险格式处理
                                isSvg -> renderSvgToPng(context, uri)
                                isImage && !original -> compressImageToJpeg(context, uri)
                                else -> readUriBytes(context, uri)
                            }
                        } catch (t: Throwable) {
                            // 关键：OutOfMemoryError 属于 Error，原来的 catch(Exception) 抓不住；
                            // 大图（12MP+）全分辨率解码会 OOM 并直接中断入队，表现为"图片发不出去"。
                            // 这里兜住并退回原始字节，保证图片至少能作为文件发出去。
                            // SVG 例外：退回 .svg 原始字节会被服务端改成 .bin，宁可明确失败也不发坏文件。
                            Log.e("MediaUploadManager", "图片预处理失败: ${t.message}", t)
                            if (isSvg) null else readUriBytes(context, uri)
                        }
                        if (bytes == null) {
                            false
                        } else {
                            file.writeBytes(bytes)
                            true
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("MediaUploadManager", "媒体预处理失败: ${t.message}", t)
                    false
                }
            }

            if (!prepared) {
                runCatching { file.delete() }
                _events.tryEmit(
                    UploadEvent.Failed(
                        localId = localId, friendId = friendId, message = "无法读取文件", permanent = true,
                        mediaType = when {
                            isImage -> "image"
                            isGif -> "gif"
                            isVideo -> "video"
                            isAudio -> "voice"
                            else -> "file"
                        },
                        flashDuration = flashDuration
                    )
                )
                return@launch
            }
            // 预处理期间用户可能已经撤回（占位气泡已出现，撤回不等预处理完成）
            if (cancelledIds.remove(localId)) {
                runCatching { file.delete() }
                return@launch
            }

            val item = if (isVideo) {
                PendingUpload(
                    id = localId, friendId = friendId, isGroup = isGroup, localPath = file.absolutePath,
                    mimeType = mimeType, fileName = ensureMediaNameWithExt(uri.lastPathSegment ?: "video_$localId", mimeType),
                    text = text, flashDuration = flashDuration,
                    // 原图视频不压缩，直接进队列上传
                    createdAt = System.currentTimeMillis(),
                    status = if (original) PendingUpload.STATUS_PENDING else PendingUpload.STATUS_COMPRESSING,
                    retryCount = 0, original = original
                )
            } else {
                // 语音：不压缩、不转码，保留原始 m4a（转码只会增大体积且丢质量）
                val fileName = when {
                    // SVG 已在预处理阶段渲染成 PNG：扩展名/MIME 必须同步改成 png，
                    // 否则服务端按文件名判类型会把 PNG 内容存成 .svg（甚至 .bin）
                    isSvg -> "media_$localId.png"
                    // 原图：保留原始扩展名（可能是 png/heic/webp），后端按扩展名判格式
                    isImage -> if (original) ensureMediaNameWithExt(uri.lastPathSegment ?: "media_$localId", mimeType)
                               else "media_$localId.jpg"
                    isAudio -> "voice_$localId.m4a"
                    // GIF：保留动图、原字节直传（不 JPEG 转码）；文件名必须带 .gif。
                    // uri.lastPathSegment 是 content:// 形态、无扩展名，服务端按扩展名判定格式/落库，
                    // 缺扩展名会被判成"不支持的文件格式"导致上传失败（与视频分支同因，见下方压缩回退注释）。
                    isGif -> "media_$localId.gif"
                    // 其他普通文件：content:// 的 lastPathSegment 常是无扩展名的数字 ID，
                    // 必须按 MIME 兜底补扩展名，否则会被后端 validateUploadExt 按"无扩展名"拒绝
                    // （表现为"聊天里任何文件都传不出去"）。
                    else -> ensureMediaNameWithExt(uri.lastPathSegment ?: "file", mimeType)
                }
                PendingUpload(
                    id = localId, friendId = friendId, isGroup = isGroup, localPath = file.absolutePath,
                    // 原图保持原始 MIME（png/heic/webp 不能被标成 image/jpeg，否则服务端按 jpg 落库导致打不开）
                    mimeType = when {
                        isSvg -> "image/png"
                        isImage && !original -> "image/jpeg"
                        else -> mimeType
                    }, fileName = fileName,
                    text = text, flashDuration = flashDuration,
                    createdAt = System.currentTimeMillis(), status = PendingUpload.STATUS_PENDING, retryCount = 0,
                    voiceDuration = voiceDuration, original = original
                )
            }
            PendingUploadStore.enqueue(context, item)
            _events.tryEmit(UploadEvent.Uploading(localId, 0f))
            processQueue()
        }
        return localId
    }

    /**
     * 撤回上传中的媒体（压缩/上传任一阶段都能撤）：中断在途协程、移出队列、删本地文件，
     * 并抛 [UploadEvent.Cancelled] 让 UI 移除占位气泡。已传到服务器的部分无法回收，但能立刻止损。
     */
    fun cancelUpload(localId: Long) {
        // 1) 中断正在进行的压缩/上传协程（在途才有效；未开始的会被 2) 的移出队列挡住）
        cancelledIds.add(localId)
        activeJobs.remove(localId)?.cancel()
        // 2) 移出持久化队列，防止 processQueue 下一轮又把它捡起来重传
        PendingUploadStore.remove(ctx, localId)
        // 3) 清理本地文件：原始 up_<id>.bin + 压缩产物 up_<id>_c.mp4（避免撤回后残留垃圾）
        runCatching {
            val dir = File(ctx.filesDir, "media_uploads")
            File(dir, "up_$localId.bin").delete()
            File(dir, "up_${localId}_c.mp4").delete()
        }
        // 4) 通知 UI 移除占位（不带失败态、不提示"自动重试"）
        _events.tryEmit(UploadEvent.Cancelled(localId))
    }

    private fun processQueue() {
        // CAS 而非「读-判断-写」：批量入队时多个调用可能并发，非原子判断会丢掉唤醒
        if (!processing.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val items = PendingUploadStore.all(ctx).filter { it.status != PendingUpload.STATUS_DONE }
                    if (items.isEmpty()) break
                    // 服务端刚回过限流：整批等退避时间结束再试，避免每隔 15 秒重发一次、反复失败
                    val backoff = rateLimitedUntilMs - System.currentTimeMillis()
                    if (backoff > 0) {
                        delay(backoff)
                        continue
                    }
                    if (!isNetworkAvailable()) {
                        delay(NETWORK_RETRY_MS)
                        continue
                    }
                    // 并发上传：每项一个协程并行处理，用信号量限制同时在途数量（图片可并发提升体验，
                    // 视频/大文件不并发太多，避免占满带宽与 CPU）。任一项失败不影响其余项。
                    val gate = Semaphore(MAX_CONCURRENT_UPLOADS)
                    val deferreds = items.map { item ->
                        async(Dispatchers.IO) { gate.withPermit { uploadOne(ctx, item) } }
                            .also { activeJobs[item.id] = it }
                    }
                    // 等待全部结束：单个被「撤回」取消或失败，都不影响其他，也绝不让异常击穿队列循环
                    runCatching { deferreds.awaitAll() }
                    items.forEach { activeJobs.remove(it.id) }
                    val remaining = PendingUploadStore.all(ctx).filter { it.status != PendingUpload.STATUS_DONE }
                    if (remaining.isEmpty()) break
                    // 只有「确实失败、需要退避重试」的项才等 15 秒；
                    // 若只是本批上传期间又有新项入队（还没轮到它），立刻进入下一轮，避免新图片白白停在 0%。
                    if (remaining.any { it.status == PendingUpload.STATUS_FAILED }) {
                        delay(RETRY_INTERVAL_MS)
                    } else {
                        delay(100L)
                    }
                }
            } finally {
                processing.set(false)
                // 收尾补一次唤醒：若新任务恰好在上面的「判定无待办」与「置 false」之间入队，
                // 那次 enqueue 的 processQueue() 会被 CAS 挡掉，不补唤醒该媒体就会永远停在 0%。
                val stillPending = runCatching {
                    PendingUploadStore.all(ctx).any { it.status != PendingUpload.STATUS_DONE }
                }.getOrDefault(false)
                if (stillPending) processQueue()
            }
        }
    }

    /** 标记"客户端永久错误"：重试无意义，应移出队列并停止自动重试。 */
    private class PermanentUploadException(message: String) : Exception(message)

    /** 4xx（除 408 请求超时 / 429 限流）属客户端永久错误，重试无意义。 */
    private fun isPermanentUploadError(code: Int): Boolean = code in 400..499 && code != 408 && code != 429

    /**
     * 判断 sendMessage 返回的错误文案是否属于「终态业务失败」：服务端已明确拒收，重试无意义。
     * 命中即当作永久错误，停止自动重试并清理队列（否则会"传到 100% → 被拒 → 重试 → 又 100% → 又被拒"无限循环）。
     *
     * 覆盖：被拉黑 / 对方开启好友验证(非好友禁止发消息) / 账号异常(对方或自己) / 被移出群 /
     * 被禁言 / 未通过入群审核 / 全员禁言 / 官方群仅开发者。
     * 关键词与后端 handleSendMessage 的写死文案一致，不做模糊臆测。
     */
    private fun isPermanentBusinessError(message: String?): Boolean {
        val m = message ?: return false
        return m.contains("拉入黑名单") ||
            m.contains("非好友禁止发消息") ||
            m.contains("账号异常") ||
            m.contains("已被移出该群") ||
            m.contains("已被禁言") ||
            m.contains("群内禁言") ||
            m.contains("尚未通过入群审核") ||
            m.contains("全员禁言") ||
            m.contains("官方群仅开发者")
    }

    /** 服务端发送频率限制（临时性，额度恢复后即可发送）。文案与后端 writeJSON 保持一致。 */
    private fun isRateLimitedMessage(message: String?): Boolean {
        val m = message ?: return false
        return m.contains("只能发") || m.contains("过于频繁") || m.contains("发送频率")
    }

    /**
     * 处理单个队列项：视频先本地压缩 → 上传（流式带进度）→ 调发送接口 → 落库 → 通知 UI 替换占位。
     * 可被多个协程并发调用（各字段/文件按 localId 隔离，PendingUploadStore 写操作已 @Synchronized）。
     */
    private suspend fun uploadOne(ctx: Context, item: PendingUpload) {
        var current = item
        var httpCode = 0
        // 提升到函数作用域：上传成功、仅 sendMessage 被拒时，catch 里仍能拿到已上传的媒体信息，
        // 用来把占位气泡转成「红叹号失败气泡」（媒体已在服务器，重试时直接复用 mediaUrl）。
        var finalMediaUrl = ""
        var finalMediaType = ""
        // 上次已上传成功、只是「发送」被服务端拒绝（限流 / 瞬时错误）：直接复用媒体地址，只重发消息。
        // 否则每次自动重试都会把整张图重新传一遍（又走到 100% 再失败），既费流量又让人误以为没发出去。
        val reuseUrl = current.uploadedUrl
        // 已被用户撤回（cancelUpload 已把该项移出队列）：直接结束，不再压缩/上传。
        // 兜住"撤回"与"processQueue 本轮已读出该 item"的竞态，避免撤回了还照样上传。
        cancelledIds.remove(item.id)
        if (PendingUploadStore.all(ctx).none { it.id == item.id }) return
        try {
            var upW = 0
            var upH = 0
            val mediaUrl: String
            if (reuseUrl.isNotEmpty()) {
                // 已经传完：进度直接顶到 100%，只等发送结果（重试不再重传文件）
                mediaUrl = reuseUrl
                upW = current.uploadedWidth
                upH = current.uploadedHeight
                _events.tryEmit(UploadEvent.Uploading(current.id, 1f))
            } else {
                // 1) 视频压缩阶段：先本地压缩，再进入上传
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
                        // 压缩失败/不可用：退回原视频直传。必须同时把 fileName 改成带合法扩展名的名字——
                        // enqueue 时 fileName 取的是 uri.lastPathSegment（content:// 往往无扩展名），
                        // 原样上传会被后端 validateUploadExt 判 400（这就是"传到 100% 却 HTTP 400"的根因之一）。
                        current = current.copy(
                            status = PendingUpload.STATUS_UPLOADING,
                            fileName = "media_${current.id}.mp4"
                        )
                        PendingUploadStore.update(ctx, current)
                        runCatching { compressedFile.delete() }
                        // 原视频超过服务端 100MB 上限时，直传必然被拒；提前判定为永久失败，
                        // 避免上传到 100% 才收到 400 并无意义地自动重试。
                        if (rawFile.length() > MAX_UPLOAD_BYTES) {
                            throw PermanentUploadException("视频超过100MB上限且自动压缩失败，无法发送")
                        }
                    }
                } else {
                    current = current.copy(status = PendingUpload.STATUS_UPLOADING, retryCount = current.retryCount + 1)
                    PendingUploadStore.update(ctx, current)
                }
                _events.tryEmit(UploadEvent.Uploading(current.id, 0f))
                // 2) 上传 + 发送阶段（统一用流式上传，实时上报进度；大文件不整读进内存）
                val up = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
                    AuroraApi.uploadChatMediaFile(File(current.localPath), current.fileName, current.mimeType) { p ->
                        _events.tryEmit(UploadEvent.Uploading(current.id, p))
                    }
                } ?: throw Exception("上传超时（网络较慢或服务器未响应）")
                if (!up.success || up.data == null) {
                    httpCode = up.httpCode
                    throw Exception(up.message)
                }
                val rawUrl = up.data!!.optString("url", "")
                if (rawUrl.isEmpty()) throw Exception("empty media url")
                mediaUrl = resolveMediaUrl(rawUrl)
                // 上传返回的媒体真实宽高（图片后端 image.DecodeConfig、视频 ffprobe）：随消息存库/下发，
                // 接收方首帧即可按正确比例定框（跨设备稳定，无需等本地解码/缓存）。
                upW = up.data!!.optInt("media_width", 0)
                upH = up.data!!.optInt("media_height", 0)
                // 上传成功先把媒体地址落盘：此后即便发送被限流 / 失败，重试也只重发消息，不再重传文件
                current = current.copy(uploadedUrl = mediaUrl, uploadedWidth = upW, uploadedHeight = upH)
                PendingUploadStore.update(ctx, current)
            }
            finalMediaUrl = mediaUrl
            // 视频：发送成功后立刻用本地文件预热首帧缩略图缓存（零网络），
            // 让自己刚发出的视频气泡首帧就是稳定帧，切走再回/二次查看也直接命中缓存、不再联网取帧。
            if (item.mimeType.startsWith("video/")) {
                runCatching { com.aurora.chat.data.local.VideoThumbCache.warmFromLocalFile(ctx, mediaUrl, File(current.localPath)) }
            }
            // 依据我们上传的 mimeType 分类，不信任服务器默认值（否则 APK/TXT 等会被误判成图片）
            val mediaType = when {
                item.mimeType.startsWith("video/") -> "video"
                // 语音：独立类型，气泡走专用卡片 + 播放器
                item.mimeType.startsWith("audio/") -> "voice"
                // GIF 是特殊的图片类型，应以内联图片形式直接展示（可动），而非文件卡片
                item.mimeType.equals("image/gif", ignoreCase = true) ||
                    item.mimeType.contains("gif", ignoreCase = true) -> "gif"
                item.mimeType.startsWith("image/") -> "image"
                else -> "file"
            }
            finalMediaType = mediaType
            // 语音时长借 media_width 字段随消息上送（后端该列已存在，接收端原样读回），非语音消息恒传 0。
            val voiceSec = if (mediaType == "voice") item.voiceDuration else 0
            // 语音：media_width=时长、media_height=0；图片/视频：media_width/height=真实宽高（供客户端按比例定框）
            val sendW = if (mediaType == "voice") voiceSec else upW
            val sendH = if (mediaType == "voice") 0 else upH
            // 语音时长同时写进 content：本地消息存储是并行数组结构（没有时长列），
            // content 是唯一天然持久化且能跨设备同步的字段。渲染端优先读 text，读不到再退回 mediaWidth。
            val msgText = if (mediaType == "voice") voiceSec.toString() else item.text
            val sendRes = if (item.isGroup) {
                ChatRepository.sendGroupMessage(
                    item.friendId, msgText, 0, mediaType, mediaUrl, item.flashDuration,
                    mediaWidth = sendW, mediaHeight = sendH
                )
            } else {
                ChatRepository.sendMessage(
                    item.friendId, msgText, 0, mediaType, mediaUrl, item.flashDuration,
                    mediaWidth = sendW, mediaHeight = sendH
                )
            }
            if (!sendRes.success) throw Exception(sendRes.message)
            val serverId = sendRes.data ?: 0L

            // —— 以下都是「消息已经真正发到服务器之后」的本地收尾 ——
            // 任何一步抛异常都不能再当成"发送失败"：否则会标 FAILED 并自动重试，
            // 表现就是用户看到的"弹发送失败，但其实对方已经收到了"，重试还会产生重复消息。
            // 所以这里全部兜异常，并且 Succeeded 事件必须必达、队列项必须移出。
            runCatching {
                // 记录本机发送的真实媒体类型，供加载时覆盖后端返回的误标 media_type
                com.aurora.chat.data.local.LocalMediaTypeStore.put(serverId, mediaType)
            }.onFailure { Log.e("MediaUploadManager", "本地媒体类型落盘失败 id=$serverId", it) }

            runCatching {
                val info = MessageInfo(
                    id = serverId, fromUserId = AuroraApi.currentUserId, toUserId = item.friendId,
                    content = msgText, createdAt = System.currentTimeMillis() / 1000,
                    mediaType = mediaType, mediaUrl = mediaUrl, isRevoked = 0, flashDuration = item.flashDuration,
                    mediaWidth = sendW, mediaHeight = sendH
                )
                com.aurora.chat.data.repository.LocalMessageStore.mergeAndSave(ctx, item.friendId, listOf(info))
            }.onFailure { Log.e("MediaUploadManager", "本地消息落盘失败 id=$serverId", it) }

            runCatching {
                MediaStore.put(serverId, ChatMedia(mediaType, resolveMediaUrl(mediaUrl)))
            }.onFailure { Log.e("MediaUploadManager", "媒体缓存写入失败 id=$serverId", it) }

            // 队列项清理单独兜：写盘失败也不能让这条已发送的任务留在队列里被重发
            runCatching { PendingUploadStore.remove(ctx, item.id) }
            runCatching { File(current.localPath).delete() }
            _events.emit(UploadEvent.Succeeded(item.friendId, item.id, serverId, mediaType, mediaUrl, item.flashDuration, voiceSec, upW, upH))
        } catch (e: Exception) {
            // 用户主动撤回：CancellationException 绝不能被当成"上传失败"（否则会误标失败态并自动重试），
            // 原样向上抛，协程正常结束，UI 侧由 Cancelled 事件移除占位。
            if (e is CancellationException) throw e
            // 不再静默吞异常：记录真实原因并通知 UI，避免失败后占位气泡无限转圈。
            Log.e("MediaUploadManager", "上传失败 localId=${item.id} name=${current.fileName} type=${current.mimeType} http=$httpCode: ${e.message}", e)
            // 终态业务失败（被拉黑 / 对方开启好友验证 / 对方账号异常 / 被移出群 / 被禁言 …）：
            // 服务端已经明确拒收，媒体即使传到 100% 也没用，重试纯属浪费，必须当作永久错误停掉自动重试。
            val businessBlocked = isPermanentBusinessError(e.message)
            val permanent = e is PermanentUploadException || isPermanentUploadError(httpCode) || businessBlocked
            // 服务端限流（群消息每分钟 N 条 / 全站发送频率）：不是永久失败，但立刻重试必然再被拒。
            // 整批退避一分钟，等额度恢复后再发；已传完的项会直接复用媒体地址，不再重传。
            if (!permanent && (httpCode == 429 || isRateLimitedMessage(e.message))) {
                rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
            }
            PendingUploadStore.update(ctx, current.copy(status = PendingUpload.STATUS_FAILED))
            _events.tryEmit(
                UploadEvent.Failed(
                    localId = current.id, friendId = item.friendId,
                    message = e.message ?: "上传失败", permanent = permanent,
                    mediaType = finalMediaType, mediaUrl = finalMediaUrl, flashDuration = item.flashDuration
                )
            )
            if (permanent) {
                // 4xx / 超限 / 业务拒收属永久错误，重试无意义：移出队列并清理本地文件，
                // 避免"上传到 100% → 被拒 → 自动重试 → 又 100% → 又被拒"的无限循环，
                // 也避免退出重进后又从 0% 重新上传。
                PendingUploadStore.remove(ctx, current.id)
                runCatching { File(current.localPath).delete() }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // activeNetworkInfo 已废弃，新系统上可能返回 null 导致队列误判"无网"永远不传
                val nw = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(nw) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val active = cm.activeNetworkInfo
                active != null && active.isConnected
            }
        } catch (e: Exception) {
            Log.w("MediaUploadManager", "联网状态判断异常(按有网处理): ${e.message}")
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
            val inputLen = input.length().coerceAtLeast(1)
            val pollJob = scope.launch {
                while (cont.isActive) {
                    try {
                        var p = -1f
                        if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            p = progressHolder.progress.toFloat() / 100f
                        }
                        // media3 getProgress 经常返回 0/不可用，用「已写出文件大小 / 输入文件大小」兜底，
                        // 保证压缩阶段始终有可信百分比，而不是一直 0%。
                        if (p < 0f) {
                            val out = runCatching { output.length() }.getOrDefault(0L)
                            p = (out.toFloat() / inputLen).coerceIn(0f, 0.99f)
                        }
                        onProgress(p.coerceIn(0f, 1f))
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
        // 先只读尺寸，再按 MAX_SIDE 采样解码：避免 12MP+ 照片全分辨率解码（约 48MB）触发 OOM。
        // 原实现直接 decodeByteArray 全图，是"大图发不出去"的直接原因。
        // 阈值从 2×MAX_SIDE 收紧到 MAX_SIDE：以前 4032px 的手机原图会整张全分辨率解码（约 48MB/张），
        // 而批量发图时压缩是串行的，一张大图就能把后面所有图堵在「压缩中」好几秒
        // （表现就是"4 张秒发完，中间 1 张卡住，剩下 5 张一直 0%"）。
        // 现在最大只解码约 2048px（约 12MB/张），解码+压缩快 3~4 倍，内存也更安全。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        if (longSide > MAX_SIDE) {
            sample = ((longSide + MAX_SIDE - 1) / MAX_SIDE).coerceAtLeast(1)
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return raw
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

    /**
     * 把 SVG 渲染成 PNG 字节，让聊天图片统一走位图链路。
     *
     * SVG 本质是 XML 文本：BitmapFactory 解不了（原逻辑会退回原始字节，但文件名仍叫 .jpg）；
     * 服务端又把 .svg 视为可脚本渲染的危险格式，不会按图片处理，无扩展名时还会兜底成 .bin。
     * 所以在这里按文档尺寸等比渲染成 PNG，长边不超过 [MAX_SIDE]。
     */
    private fun renderSvgToPng(context: Context, uri: Uri): ByteArray? {
        return try {
            val raw = readUriBytes(context, uri) ?: return null
            val svg = com.caverock.androidsvg.SVG.getFromInputStream(raw.inputStream())
            var w = svg.documentWidth
            var h = svg.documentHeight
            if (w <= 0f || h <= 0f) {
                // 只写了 viewBox 没写 width/height 的 SVG：按 viewBox 比例兜底
                val box = svg.documentViewBox
                if (box != null && box.width() > 0f && box.height() > 0f) {
                    w = box.width()
                    h = box.height()
                } else {
                    w = 1024f
                    h = 1024f
                }
            }
            val scale = minOf(1f, MAX_SIDE / maxOf(w, h))
            val outW = (w * scale).toInt().coerceAtLeast(1)
            val outH = (h * scale).toInt().coerceAtLeast(1)
            svg.setDocumentWidth(outW.toFloat())
            svg.setDocumentHeight(outH.toFloat())
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            // SVG 多为透明背景：铺白，避免气泡里透出深色底
            canvas.drawColor(android.graphics.Color.WHITE)
            svg.renderToCanvas(canvas)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (t: Throwable) {
            Log.e("MediaUploadManager", "SVG 渲染失败: ${t.message}", t)
            null
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
        val flashDuration: Int,
        /** 语音时长（秒）；非语音消息为 0。 */
        val voiceDuration: Int = 0,
        val mediaWidth: Int = 0,
        val mediaHeight: Int = 0
    ) : UploadEvent()

    /** 视频本地压缩中，[progress] 0~1。 */
    data class Compressing(val localId: Long, val progress: Float) : UploadEvent()

    /** 上传中，[progress] 0~1。 */
    data class Uploading(val localId: Long, val progress: Float) : UploadEvent()

    /** 上传失败（队列会自动重试）。UI 据此提示/标红，避免一直转圈。 */
    data class Failed(
        val localId: Long,
        val friendId: Long,
        val message: String,
        /** true=客户端永久错误(4xx/超限/业务拒收)，重试无意义：UI 应转成红叹号失败气泡并留存，不再提示"自动重试" */
        val permanent: Boolean = false,
        /** 已上传成功的媒体类型（sendMessage 被拒但媒体已上传时非空），用于把占位转成可重发的失败气泡 */
        val mediaType: String = "",
        /** 已上传成功的媒体 URL（服务器侧），重试时直接复用、无需重新上传 */
        val mediaUrl: String = "",
        val flashDuration: Int = 0
    ) : UploadEvent()

    /** 用户主动撤回上传中的媒体：UI 应移除占位，不标记失败、不提示重试。 */
    data class Cancelled(val localId: Long) : UploadEvent()
}
