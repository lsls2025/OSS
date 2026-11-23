package com.aurora.chat.ui.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 语音「转文字」失败（message 为可直接展示给用户的中文文案）。
 */
class VoiceTranscribeException(message: String) : Exception(message)

/** 解码后的原始 PCM（16bit 小端、交错多声道）。 */
internal data class DecodedPcm(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int
) {
    // data class 生成的 equals 对数组是引用比较，这里按内容比较，避免误判
    override fun equals(other: Any?): Boolean =
        other is DecodedPcm && samples.contentEquals(other.samples) &&
            sampleRate == other.sampleRate && channels == other.channels

    override fun hashCode(): Int = (samples.contentHashCode() * 31 + sampleRate) * 31 + channels
}

// ==================== 纯函数 DSP（不依赖 Android，可 JVM 单测） ====================

/**
 * 多声道混降单声道：每 channels 个采样取均值。单声道/空输入原样返回。
 */
internal fun mixToMono(input: ShortArray, channels: Int): ShortArray {
    if (channels <= 1 || input.isEmpty()) return input
    val out = ShortArray(input.size / channels)
    var i = 0
    var o = 0
    while (i + channels <= input.size) {
        var sum = 0
        for (c in 0 until channels) sum += input[i + c]
        out[o++] = (sum / channels).toShort()
        i += channels
    }
    return out
}

/**
 * 盒式均值预滤波：按 window 分组求均值，抑制下采样混叠。
 * 只用于重采样前（重采样倍率 >= 2 时）；末组不足 window 时按实际个数平均。
 */
internal fun boxBlur(input: ShortArray, window: Int): ShortArray {
    if (window <= 1 || input.isEmpty()) return input
    val out = ShortArray((input.size + window - 1) / window)
    var i = 0
    var o = 0
    while (i < input.size) {
        var sum = 0
        var n = 0
        while (n < window && i < input.size) {
            sum += input[i]
            i++
            n++
        }
        out[o++] = (sum / n).toShort()
    }
    return out
}

/**
 * 线性插值重采样到目标采样率。同速率原样返回；空输入返回空数组。
 * 末样本越界时钳位到最后一个样本（与首样本相等，等价于补零延拓）。
 */
internal fun resampleLinear(input: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
    if (input.isEmpty()) return ShortArray(0)
    if (inputRate <= 0 || outputRate <= 0 || inputRate == outputRate) return input
    val ratio = inputRate.toDouble() / outputRate
    val outLen = maxOf(1, Math.ceil(input.size / ratio).toInt())
    val out = ShortArray(outLen)
    var pos = 0.0
    for (i in 0 until outLen) {
        val idx = pos.toInt()
        val frac = pos - idx
        val a = input[idx.coerceIn(0, input.size - 1)].toInt()
        val b = input[(idx + 1).coerceIn(0, input.size - 1)].toInt()
        out[i] = (a + (b - a) * frac).toInt().toShort()
        pos += ratio
    }
    return out
}

/** 识别器错误码 → 中文文案。 */
internal fun mapRecognizerError(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "录音出错，请重试"
    SpeechRecognizer.ERROR_CLIENT -> "识别服务异常，请重试"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限，请在系统设置中授予"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络异常，识别失败"
    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请重试"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，请稍后再试"
    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "识别服务连接失败"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        "当前系统不支持中文语音识别"
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "无法检查识别服务支持"
    else -> "识别失败（错误码 $code）"
}

// ==================== 引擎 ====================

/**
 * 语音转文字引擎（零第三方依赖，全部走系统能力）：
 *
 *  1. 取音频：远程 URL 流式下载到 cacheDir/voice_stt/，本地 file:// 直接读，content:// 交给 MediaExtractor；
 *  2. 解码：MediaExtractor + MediaCodec 解出 16bit 小端 PCM（m4a/AAC、webm/Opus 等通吃）；
 *  3. 重采样：多声道混降单声道 + 线性插值到 16kHz（实测部分识别器只认 16k/24k，22050Hz 会被拒）；
 *  4. 落盘：16kHz 单声道 16bit PCM 临时文件（按 URL MD5 缓存，重复转写直接命中）；
 *  5. 识别：SpeechRecognizer + RecognizerIntent.EXTRA_AUDIO_SOURCE（API 33+，把 PCM 文件喂给识别器，
 *     不需要麦克风，也不会录到环境音）；API 33 以下退化为「外放语音 + 麦克风识别」。
 *
 * 所有 suspend 函数都可在任意线程调用，内部自行切换 IO / Main。
 */
object VoiceTranscribeEngine {

    private const val DIR_NAME = "voice_stt"
    private const val TARGET_RATE = 16000
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_CACHE_BYTES = 100L * 1024 * 1024 // PCM 缓存上限 100MB

    /** 识别总超时：至少 45s，长语音按秒数 ×2 放宽。 */
    private fun timeoutMs(seconds: Int): Long = maxOf(45_000L, seconds.coerceAtLeast(1) * 2000L)

    // ---------- 识别服务发现 ----------

    /**
     * 找到设备上可用的语音识别引擎（ComponentName），找不到返回 null。
     *
     * 为什么不能只信 [SpeechRecognizer.isRecognitionAvailable]：国产 ROM（小米/华为/OPPO/vivo/一加）
     * 普遍不装 Google 服务，它查询不到 Google 的 RecognitionService 就返回 false；
     * 但这些机器其实自带 OEM 引擎（如 com.xiaomi.mibrain.speech/.asr.AsrService），
     * 只登记在系统设置里，PackageManager 枚举也看不到 —— 直接放弃就会误判「设备不支持」。
     *
     * 查找顺序：
     *  1. 系统设置里配置的默认识别引擎（Settings.Secure.voice_recognition_service，OEM 引擎通常只在这）；
     *  2. 枚举所有注册了 RecognitionService 的实现（装了 Google 应用的机器一定在这）。
     * 两项都查不到才真的没有。
     */
    internal fun resolveRecognizerComponent(context: Context): ComponentName? {
        val app = context.applicationContext
        // 1) 系统默认引擎（需确认包还装着，防止设置里残留已卸载应用的组件）
        runCatching {
            val flat = Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
            if (!flat.isNullOrBlank()) {
                val cn = ComponentName.unflattenFromString(flat)
                if (cn != null && isPackageInstalled(app, cn.packageName)) return cn
            }
        }
        // 2) 枚举 RecognitionService 实现：优先导出的（隐式绑定只连导出组件），
        //    全是非导出时也返回第一个，让绑定阶段暴露真实错误而不是静默放弃
        runCatching {
            val services = app.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE), 0
            )
            var fallback: ComponentName? = null
            for (ri in services) {
                val si = ri.serviceInfo ?: continue
                val cn = ComponentName(si.packageName, si.name)
                if (si.exported) return cn
                if (fallback == null) fallback = cn
            }
            if (fallback != null) return fallback
        }
        return null
    }

    /**
     * 是否 Google 识别器。判定「文件直喂」能力用：`EXTRA_AUDIO_SOURCE`（把 PCM 文件句柄
     * 喂给识别器、不走麦克风）目前只有 Google 的实现支持，国产 OEM 引擎与第三方识别器
     * 一律走「外放 + 麦克风」回退（有服务就能用，不挑设备）。
     */
    private fun isGoogleRecognizer(cn: ComponentName?): Boolean =
        cn != null && cn.packageName.contains("google", ignoreCase = true)

    internal fun isPackageInstalled(app: Context, pkg: String): Boolean = runCatching {
        app.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /** 设备语音识别能力快照（诊断弹窗用）。 */
    internal data class RecognitionSupport(
        val enabledCount: Int,
        /** 装着但被停用的识别服务组件（"包名/类名"）——国行 ROM 组件管理的高发坑 */
        val disabledComponents: List<String>,
        val googleAppInstalled: Boolean,
        val gmsInstalled: Boolean,
        /** Speech Services by Google（com.google.android.tts）：Android 13+ 真正的识别提供者 */
        val speechServicesInstalled: Boolean,
        val defaultEngine: String?
    )

    /**
     * 体检：设备上到底有没有语音识别能力、卡在哪一环。
     *
     * 实测背景（一加 Android 15 + Google 应用 16.46）：Google 官方从 Android 13 起把
     * RecognitionService 从「Google 应用」里**移除**了，识别能力搬到独立的
     * 「Speech Services by Google」（com.google.android.tts）；国内谷歌安装器只装
     * GMS + Play 商店 + Google 应用，这个关键包通常没装 —— 于是
     * `SpeechRecognizer.isRecognitionAvailable` 稳定返回 false、枚举到 0 个服务。
     * Google 应用里那个 GoogleRecognitionService 是移除后遗留的死组件：
     * 能进 Resolver Table，但 start-service 报 Not found，pm enable 也被系统拒绝。
     *
     * 关键点：`queryIntentServices` 默认**不返回被停用的组件**，而国行 ROM（ColorOS/MIUI 等）
     * 的组件管理偶尔会停用识别服务——只看默认查询会得到「识别服务 0 个」，
     * 误判成设备不支持。必须带 GET_DISABLED_COMPONENTS 再查一轮才能看到真相。
     */
    internal fun inspectRecognitionSupport(context: Context): RecognitionSupport {
        val app = context.applicationContext
        val pm = app.packageManager
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val enabled = runCatching { pm.queryIntentServices(intent, 0) }.getOrDefault(emptyList())
        val enabledKeys = enabled.mapNotNull { ri ->
            ri.serviceInfo?.let { "${it.packageName}/${it.name}" }
        }.toSet()
        val all = runCatching {
            pm.queryIntentServices(
                intent,
                PackageManager.GET_DISABLED_COMPONENTS or PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS
            )
        }.getOrDefault(emptyList())
        val disabled = all.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            val key = "${si.packageName}/${si.name}"
            if (key in enabledKeys) null else key
        }.distinct().filterNot { key ->
            // Android 13+ 起 Google 应用里的 RecognitionService 是官方移除后的死组件：
            // manifest 级禁用，pm enable 都改不动，列出来只会把人往没用的命令上带
            key.startsWith("com.google.android.googlequicksearchbox/")
        }
        val default = runCatching {
            Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
        }.getOrNull()
        return RecognitionSupport(
            enabledCount = enabled.size,
            disabledComponents = disabled,
            googleAppInstalled = isPackageInstalled(app, "com.google.android.googlequicksearchbox"),
            gmsInstalled = isPackageInstalled(app, "com.google.android.gms"),
            speechServicesInstalled = isPackageInstalled(app, "com.google.android.tts"),
            defaultEngine = default?.takeIf { it.isNotBlank() }
        )
    }

    /** 诊断摘要文本（弹窗灰底里展示，保持精简）。 */
    internal fun describeRecognitionSupport(support: RecognitionSupport): String = buildString {
        append("识别服务：${support.enabledCount} 个")
        append("\nSpeech Services by Google：${if (support.speechServicesInstalled) "已安装" else "未安装"}")
        if (support.disabledComponents.isNotEmpty()) {
            append("\n被停用的识别服务：${support.disabledComponents.joinToString("、")}")
        }
        if (support.defaultEngine != null) append("\n系统默认引擎：${support.defaultEngine}")
    }

    /**
     * 转文字主入口。成功返回识别文本；失败抛 [VoiceTranscribeException]（中文文案可直接 Toast）。
     *
     * @param url     消息 mediaUrl（原始或 MediaStore 固化 URL 均可，内部会再 resolve 一次）
     * @param seconds 语音时长（秒），用于超时预估
     * @param onPartial 局部识别结果回调（用于 UI 实时显示「正在识别…」的中间文字）
     */
    suspend fun transcribe(
        context: Context,
        url: String,
        seconds: Int,
        onPartial: (String) -> Unit = {}
    ): String {
        val app = context.applicationContext
        if (url.isBlank()) throw VoiceTranscribeException("语音文件不存在")
        // 不再用 isRecognitionAvailable 一票否决：国产 ROM 会误报，按「系统默认引擎 → 枚举」找
        val component = resolveRecognizerComponent(app)
            ?: throw VoiceTranscribeException("当前设备没有可用的语音识别服务")
        // 文件直喂只在 Google 引擎 + API 33+ 时用；OEM 引擎只认麦克风，走外放回退
        val fileSourceOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isGoogleRecognizer(component)
        com.aurora.chat.ErrorReporter.debug(
            "VOICE_STT",
            "recognizer=$component fileSource=$fileSourceOk url=$url"
        )
        val resolved = resolveMediaUrl(url)
        return withContext(Dispatchers.Main) {
            val result = withTimeoutOrNull(timeoutMs(seconds)) {
                if (fileSourceOk) {
                    val pcmFile = withContext(Dispatchers.IO) { preparePcm16kFile(app, resolved) }
                        ?: throw VoiceTranscribeException("语音文件下载或解码失败")
                    recognizeFromPcmFile(app, component, pcmFile, onPartial)
                } else {
                    // Android 13 以下、以及所有 OEM/第三方引擎：外放语音、麦克风识别（识别器"听"外放声）
                    recognizeFromMicWithPlayback(app, component, resolved, onPartial)
                }
            }
            result ?: throw VoiceTranscribeException("识别超时，请重试")
        }
    }

    // ---------- 音频 → 16kHz 单声道 PCM 文件 ----------

    private fun preparePcm16kFile(app: Context, resolvedUrl: String): File? {
        val dir = File(app.cacheDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return null
        val pcmFile = File(dir, "${md5(resolvedUrl)}.pcm")
        // 命中缓存：上次已转写过，直接复用（原子写入保证不会读到半个文件）
        if (pcmFile.exists() && pcmFile.length() > 0) return pcmFile

        val decoded = when {
            resolvedUrl.startsWith("http") -> {
                val src = downloadAudio(app, resolvedUrl, File(dir, "${md5(resolvedUrl)}.src")) ?: return null
                // 源文件解码完即删：PCM 已在本地，重复转写不需要再下一次
                decodeAudioFileToPcm(src.absolutePath).also { src.delete() }
            }
            resolvedUrl.startsWith("file://") -> {
                val path = Uri.parse(resolvedUrl).path
                if (path.isNullOrBlank()) null else decodeAudioFileToPcm(path)
            }
            resolvedUrl.startsWith("content://") -> decodeAudioUriToPcm(app, Uri.parse(resolvedUrl))
            else -> decodeAudioFileToPcm(resolvedUrl)
        } ?: return null

        var samples = decoded.samples
        if (decoded.channels > 1) samples = mixToMono(samples, decoded.channels)
        if (decoded.sampleRate != TARGET_RATE) {
            // 重采样倍率 >= 2 时先做均值预滤波，抑制高频混叠（识别器对混叠敏感）
            if (decoded.sampleRate >= TARGET_RATE * 2) {
                samples = boxBlur(samples, decoded.sampleRate / TARGET_RATE)
            }
            samples = resampleLinear(samples, decoded.sampleRate, TARGET_RATE)
        }
        if (samples.isEmpty()) return null
        writePcm16(pcmFile, samples)
        trimCache(dir)
        return pcmFile
    }

    /** OkHttp 流式下载音频到指定文件（已存在且非空则复用），失败返回 null。 */
    private fun downloadAudio(app: Context, url: String, target: File): File? {
        if (target.exists() && target.length() > 0) return target
        return try {
            val req = okhttp3.Request.Builder().url(url).build()
            com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp ->
                val input = resp.body?.byteStream() ?: throw IOException("空响应")
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.outputStream().use { out -> input.copyTo(out, 8192) }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            if (target.length() > 0) target else { target.delete(); null }
        } catch (e: Exception) {
            if (target.exists()) target.delete()
            null
        }
    }

    /** MediaExtractor + MediaCodec 解码音频文件为 16bit 小端 PCM。 */
    internal fun decodeAudioFileToPcm(path: String): DecodedPcm? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            runDecode(extractor)
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrNull()

    private fun decodeAudioUriToPcm(app: Context, uri: Uri): DecodedPcm? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, uri, null)
            runDecode(extractor)
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrNull()

    private fun runDecode(extractor: MediaExtractor): DecodedPcm? {
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) return null
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else TARGET_RATE
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        val codec = MediaCodec.createDecoderByType(mime)
        val out = ArrayList<Short>(1 shl 16)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputEof = false
            var idleRounds = 0
            while (true) {
                if (!inputEof) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEof = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        idleRounds = 0
                        // CODEC_CONFIG 缓冲是编解码器私有数据，不是 PCM，必须跳过
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                outBuf.get(chunk)
                                appendPcmChunk(out, chunk, pcmEncoding)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = nf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    else -> { // INFO_TRY_AGAIN_LATER
                        if (inputEof) {
                            idleRounds++
                            // 输入已耗尽却长时间拿不到输出：解码器卡死，放弃而不是死循环
                            if (idleRounds > 5000) break
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        val samples = ShortArray(out.size) { out[it] }
        if (samples.isEmpty()) return null
        return DecodedPcm(samples, sampleRate, channels)
    }

    /** 把一块 PCM 字节按编码格式追加进 Short 列表（16bit 小端 / 32bit float）。 */
    private fun appendPcmChunk(out: MutableList<Short>, chunk: ByteArray, pcmEncoding: Int) {
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val buf = ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            while (buf.hasRemaining()) {
                val f = buf.get()
                out.add((f * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
            }
        } else {
            var i = 0
            while (i + 1 < chunk.size) {
                val v = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                out.add(v.toShort())
                i += 2
            }
        }
    }

    /** 16bit 小端 PCM 原子写入（先 .tmp 再 rename，避免崩溃留下半个文件被下次命中）。 */
    private fun writePcm16(target: File, samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            bytes[i++] = (s.toInt() and 0xFF).toByte()
            bytes[i++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** PCM 缓存瘦身：超出上限按最旧优先删。 */
    private fun trimCache(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= MAX_CACHE_BYTES) break
                total -= f.length()
                f.delete()
            }
        }
    }

    // ---------- 识别 ----------

    /**
     * Google 引擎 + API 33+：把 PCM 文件通过 EXTRA_AUDIO_SOURCE 喂给识别器（不走麦克风，环境音零干扰）。
     * 识别会话在音频流读完后自行结束（onResults / onError）。
     */
    private suspend fun recognizeFromPcmFile(
        app: Context,
        component: ComponentName,
        pcmFile: File,
        onPartial: (String) -> Unit
    ): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(app, component)
            var done = false
            var pfd: ParcelFileDescriptor? = null
            val mainHandler = Handler(Looper.getMainLooper())

            fun finish(result: Result<String>) {
                if (done) return
                if (!cont.isActive) { done = true; return }
                done = true
                runCatching { pfd?.close() }
                runCatching { recognizer.cancel() }
                // destroy 不放在回调里同步执行：部分 OEM 实现在监听器回调内 destroy 会崩，post 到队列尾更稳
                mainHandler.post { runCatching { recognizer.destroy() } }
                cont.resumeWith(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    finish(
                        if (text.isNotEmpty()) Result.success(text)
                        else Result.failure(VoiceTranscribeException("没有识别到语音内容"))
                    )
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (p.isNotEmpty()) onPartial(p)
                }

                override fun onError(error: Int) {
                    finish(Result.failure(VoiceTranscribeException(mapRecognizerError(error))))
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            try {
                pfd = ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                finish(Result.failure(VoiceTranscribeException("音频文件读取失败")))
                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation {
                // 关闭 PFD 即结束识别会话（识别进程持有 dup 出来的 fd，取消时这里负责收尾）
                runCatching { pfd?.close() }
                runCatching { recognizer.cancel() }
                mainHandler.post { runCatching { recognizer.destroy() } }
            }

            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // EXTRA_AUDIO_SOURCE 三件套仅 API 33+ 开放；本函数只在 API 33+ 被调用，
                    // 这里的版本守卫同时满足 lint 的 NewApi 检查
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_RATE)
                    }
                }
            )
        }
    }

    /**
     * 外放语音 + 麦克风识别：识别器通过麦克风"听"到外放的音频。
     * 两条使用场景：① Android 13 以下没有 EXTRA_AUDIO_SOURCE；② 国产 ROM 的 OEM 引擎
     * （以及第三方识别器）不认文件直喂，只支持麦克风输入。
     * 播放延迟 200ms 启动，等麦克风就绪后再出声，尽量少丢语音开头。
     */
    private suspend fun recognizeFromMicWithPlayback(
        app: Context,
        component: ComponentName,
        audioUrl: String,
        onPartial: (String) -> Unit
    ): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(app, component)
            var done = false
            var player: MediaPlayer? = null
            val mainHandler = Handler(Looper.getMainLooper())

            fun finish(result: Result<String>) {
                if (done) return
                if (!cont.isActive) { done = true; return }
                done = true
                runCatching { player?.release() }
                runCatching { recognizer.cancel() }
                mainHandler.post { runCatching { recognizer.destroy() } }
                cont.resumeWith(result)
            }

            // 延迟启动外放：先让识别器把麦克风打开（startListening → onReadyForSpeech 约 100~300ms），
            // 再出声，避免语音最前面几个字在麦克风就绪前就播完了
            val startPlayback = Runnable {
                if (done) return@Runnable
                runCatching {
                    player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(audioUrl)
                        setOnCompletionListener { runCatching { it.release() } }
                        prepare()
                        start()
                    }
                }.onFailure {
                    finish(Result.failure(VoiceTranscribeException("语音播放失败，无法转文字")))
                }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    finish(
                        if (text.isNotEmpty()) Result.success(text)
                        else Result.failure(VoiceTranscribeException("没有识别到语音内容"))
                    )
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (p.isNotEmpty()) onPartial(p)
                }

                override fun onError(error: Int) {
                    finish(Result.failure(VoiceTranscribeException(mapRecognizerError(error))))
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            // 先开后麦克风，再排外放
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
            )
            mainHandler.postDelayed(startPlayback, 200)

            cont.invokeOnCancellation {
                mainHandler.removeCallbacks(startPlayback)
                runCatching { player?.release() }
                runCatching { recognizer.cancel() }
                mainHandler.post { runCatching { recognizer.destroy() } }
            }
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
