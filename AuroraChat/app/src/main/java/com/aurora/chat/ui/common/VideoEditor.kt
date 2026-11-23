package com.aurora.chat.ui.common
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Size as AndroidSize
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationException
import androidx.media3.transformer.TransformationResult
import androidx.media3.transformer.Transformer
import androidx.media3.exoplayer.ExoPlayer
import com.aurora.chat.ErrorReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 一次编辑的全部参数，导出时原样交给 Transformer。裁剪区域为视频归一化坐标（左上原点）。 */
data class VideoEditConfig(
    val inW: Int,
    val inH: Int,
    val cropL: Float,
    val cropT: Float,
    val cropR: Float,
    val cropB: Float,
    val trimStartMs: Long,
    val trimEndMs: Long, // 0 = 到结尾
    val outW: Int,
    val outH: Int
)

/** 按裁剪区域算输出尺寸（限制最大边 1920、宽高取偶数，否则 MediaCodec 直接失败）。 */
private fun computeCropOutputSize(vw: Int, vh: Int, l: Float, t: Float, r: Float, b: Float): AndroidSize {
    val cw = ((r - l) * vw).toInt().coerceAtLeast(2)
    val ch = ((b - t) * vh).toInt().coerceAtLeast(2)
    val k = min(1f, 1920f / max(cw, ch))
    var w = (cw * k).toInt()
    var h = (ch * k).toInt()
    // MediaCodec(H.264) 编码器要求宽高均为偶数，奇数尺寸会直接导致转码失败
    w = max((w / 2) * 2, 2)
    h = max((h / 2) * 2, 2)
    return AndroidSize(w, h)
}

/** 视频元信息：displayW/H 是转正后的显示尺寸（预览用），rotation 是旋转元数据，codedW/H 是编码帧尺寸（导出用）。 */
data class VideoMeta(
    val displayW: Int,
    val displayH: Int,
    val rotation: Int,
    val durationMs: Long
) {
    /** 编码帧尺寸（Media3 效果链作用在未旋转帧上，导出换算要用） */
    val codedW: Int get() = if (rotation == 90 || rotation == 270) displayH else displayW
    val codedH: Int get() = if (rotation == 90 || rotation == 270) displayW else displayH
}

/**
 * 读取视频元信息（content:// 也支持）。
 * 注意：MediaMetadataRetriever 的 WIDTH/HEIGHT 返回的是【编码尺寸】，不是显示尺寸；
 * 带 90°/270° 旋转标记的视频（相机/录屏/转发视频常见）显示尺寸要宽高互换，
 * 否则预览的宽高比会算反、内容被挤压——这就是"有的视频正常、有的被挤"的原因。
 */
suspend fun readVideoMeta(ctx: Context, uri: Uri): VideoMeta = withContext(Dispatchers.IO) {
    val ret = android.media.MediaMetadataRetriever()
    try {
        ret.setDataSource(ctx, uri)
        val w = ret.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = ret.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = ret.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val d = ret.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        // 旋转 90/270 时显示尺寸宽高互换（播放器渲染的是转正后的画面）
        val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
        VideoMeta(dw, dh, rot, d)
    } catch (_: Exception) {
        VideoMeta(0, 0, 0, 0L)
    } finally {
        runCatching { ret.release() }
    }
}

/**
 * 把「显示空间」（预览所见，转正后、左上原点、y 向下）的裁剪区域
 * 换算成「编码帧空间」（Media3 效果链作用的未旋转帧）。
 * 返回顺序：l, r, t, b（都是编码帧归一化坐标）。
 */
private fun displayCropToCoded(rotation: Int, l: Float, t: Float, r: Float, b: Float): FloatArray =
    when (rotation) {
        90 -> floatArrayOf(t, b, 1f - r, 1f - l)
        180 -> floatArrayOf(1f - r, 1f - l, 1f - b, 1f - t)
        270 -> floatArrayOf(1f - b, 1f - t, l, r)
        else -> floatArrayOf(l, r, t, b)
    }

/** 最近一次 exportVideo 失败的原因（供 UI 展示，避免只弹"处理失败"没法排查）。 */
@Volatile
var lastExportError: String? = null

/**
 * 用 Media3 Transformer 真转码：裁剪 + 掐头去尾。
 * 本项目锁定 media3 1.5.1，按 1.5.1 实测签名写：
 *  - 掐头去尾：ClippingConfiguration 挂在 MediaItem 上（1.5.1 的 EditedMediaItem.Builder 没有 setClippingConfiguration）；
 *  - 裁剪：1.5.1 自带 androidx.media3.effect.Crop(left, right, bottom, top)，归一化、左上为原点，自带 isNoOp；
 *  - 输出尺寸：用 Presentation 效果约束到目标画布（SCALE_TO_FIT_WITH_CROP 填满）；
 *  - 进度：1.5.1 的 Listener 没有 onTransformationProgress，用 getProgress(ProgressHolder) 轮询；
 *  - Transformer 必须在带 Looper 的线程（主线程）创建与调用，否则转码直接失败。
 */
@OptIn(UnstableApi::class)
suspend fun exportVideo(
    ctx: Context,
    inputUri: Uri,
    cfg: VideoEditConfig,
    onProgress: (Float) -> Unit
): Uri? = withContext(Dispatchers.IO) {
    val outFile = File(ctx.cacheDir, "ve_${System.currentTimeMillis()}.mp4")
    if (outFile.exists()) outFile.delete()
    var completed = false
    var result: Uri? = null
    var errorMsg: String? = null
    ErrorReporter.debug(
        "VideoPublish",
        "转码开始: input=$inputUri 输出=${outFile.name} in=${cfg.inW}x${cfg.inH} " +
            "crop=[${cfg.cropL},${cfg.cropT},${cfg.cropR},${cfg.cropB}] trim=[${cfg.trimStartMs},${cfg.trimEndMs}] " +
            "out=${cfg.outW}x${cfg.outH}"
    )
    try {
        // 掐头去尾：仅真正设置了片段时才挂 clipping（无操作配置反而可能踩坑）
        val needClip = cfg.trimStartMs > 0 || cfg.trimEndMs > 0
        val mediaItem = if (needClip) {
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(if (cfg.trimStartMs > 0) cfg.trimStartMs else 0)
                .setEndPositionMs(if (cfg.trimEndMs > 0) cfg.trimEndMs else C.TIME_END_OF_SOURCE)
                .build()
            MediaItem.fromUri(inputUri).buildUpon().setClippingConfiguration(clip).build()
        } else {
            MediaItem.fromUri(inputUri)
        }

        val effects = mutableListOf<Effect>()
        val hasCrop = cfg.cropL > 0.001f || cfg.cropT > 0.001f || cfg.cropR < 0.999f || cfg.cropB < 0.999f
        if (hasCrop) {
            // Crop 参数顺序是 (left, right, bottom, top)，且用 GL 坐标系（左下原点、bottom<top）；
            // 本项目 cfg 是左上原点（cropT=0 顶边、cropB=1 底边），必须做 y 翻转：
            // gl_bottom = 1 - cropB（UI 底边 1 → GL 0），gl_top = 1 - cropT（UI 顶边 0 → GL 1）。
            // 直接传原值会触发 "top should be greater than bottom" 校验异常（发布失败的根因）。
            effects.add(Crop(cfg.cropL, cfg.cropR, 1f - cfg.cropB, 1f - cfg.cropT))
        }
        // 输出尺寸约束：裁剪结果按目标画布缩放填满
        effects.add(Presentation.createForWidthAndHeight(cfg.outW, cfg.outH, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), effects))
            .build()

        // Transformer 必须在带 Looper 的线程（主线程）上创建与调用，否则转码直接失败。
        // 等待用 delay 让出线程（不阻塞 UI），进度回调也天然在主线程更新 Compose 状态。
        withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(ctx)
                .addListener(object : Transformer.Listener {
                    override fun onTransformationCompleted(item: MediaItem, exportResult: TransformationResult) {
                        if (exportResult.transformationException == null && outFile.exists() && outFile.length() > 0) {
                            result = Uri.fromFile(outFile)
                            ErrorReporter.debug("VideoPublish", "转码完成: 文件=${outFile.length()} 字节")
                        } else {
                            errorMsg = exportResult.transformationException?.toString() ?: "输出文件无效"
                            ErrorReporter.error("VideoPublish", "转码完成但产物无效: errorMsg=$errorMsg 文件存在=${outFile.exists()} 大小=${outFile.length()}", exportResult.transformationException)
                        }
                        completed = true
                    }

                    // 失败也必须置完成标志：否则调用方白等 10 分钟
                    override fun onTransformationError(item: MediaItem, exception: TransformationException) {
                        errorMsg = exception.toString()
                        completed = true
                        ErrorReporter.error(
                            "VideoPublish",
                            "Transformer 导出失败: errorCode=${exception.errorCode}(${exception.getErrorCodeName()}) msg=${exception.message}",
                            exception
                        )
                    }
                })
                .build()

            transformer.start(edited, outFile.absolutePath)
            // 同步等待完成（最长 10 分钟）；期间轮询进度回调（1.5.1 无 Listener 进度回调）
            val holder = ProgressHolder()
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
            while (!completed) {
                delay(200)
                if (System.currentTimeMillis() > deadline) {
                    errorMsg = "转码超时"
                    ErrorReporter.error("VideoPublish", "转码超时（10 分钟无完成回调），已取消 Transformer")
                    try { transformer.cancel() } catch (_: Exception) {}
                    completed = true
                    break
                }
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                }
            }
        }
    } catch (e: Exception) {
        errorMsg = e.toString()
        android.util.Log.e("VideoEditor", "export 异常", e)
        ErrorReporter.error("VideoPublish", "exportVideo 流程异常: $errorMsg", e)
    }
    if (result == null && errorMsg != null) {
        android.util.Log.e("VideoEditor", "export 失败: $errorMsg")
        lastExportError = errorMsg
        ErrorReporter.error("VideoPublish", "导出最终失败: $errorMsg")
    } else if (result != null) {
        ErrorReporter.debug("VideoPublish", "导出成功: $result")
    }
    result
}

/**
 * 可复用的视频编辑界面（短视频上传 / 社区发帖 共用）。
 * - 所有修改（裁剪/片段）都立即在预览生效，无需先点"完成"；
 * - 「发布」= 正式发布：有修改先转码，再回调处理后的 Uri；无修改直接回调原 Uri。
 * - 交互：单击画面 = 暂停/继续；双击画面 = 进出预览（预览时显示调用方复用的真实叠加层）。
 * - 裁剪：框固定在屏幕上，**双指捏合缩放视频本身**、框内拖动平移视频（间接改变裁剪区域），
 *   拖框的四条边改框大小；比例按钮一键把框设为该比例的最大区域。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoEditorScreen(
    inputUri: Uri,
    previewOverlay: @Composable () -> Unit = {},
    onCancel: () -> Unit,
    onConfirm: (Uri, String, String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var meta by remember { mutableStateOf(VideoMeta(0, 0, 0, 0L)) }
    LaunchedEffect(inputUri) {
        meta = readVideoMeta(ctx, inputUri)
        ErrorReporter.debug("VideoPublish", "编辑器读取元信息: uri=$inputUri display=${meta.displayW}x${meta.displayH} rot=${meta.rotation} dur=${meta.durationMs}ms")
    }
    val videoW = meta.displayW
    val videoH = meta.displayH
    val videoRotation = meta.rotation
    val durationMs = meta.durationMs

    // 裁剪框：预览区归一化坐标（0..1），默认整幅
    var frameL by remember { mutableStateOf(0f) }
    var frameT by remember { mutableStateOf(0f) }
    var frameR by remember { mutableStateOf(1f) }
    var frameB by remember { mutableStateOf(1f) }
    // 视频缩放/平移（双指捏合缩放视频；框内拖动平移视频）
    var vZoom by remember { mutableStateOf(1f) } // 1 = 刚好铺满预览区（cover）
    var vPanX by remember { mutableStateOf(0f) } // 预览区归一化偏移
    var vPanY by remember { mutableStateOf(0f) }
    /**
     * 用户是否真的动过画面（拖框边 / 框内平移 / 双指捏合 / 选比例）。
     *
     * 【为什么必须有这个标记，不能用几何反推 hasCrop —— 线上事故根因】
     * 预览区是 cover 布局（covW = max(wPx, hPx*aspect)），视频一定铺满并超出预览区：
     *   竖屏 720x1280 在 1080x1600 的预览区里，上下各超出 160px → cT=0.083、cB=0.917；
     *   横屏 1920x1080 则左右各超出 → cL=0.31、cR=0.69。
     * 于是「用户什么都没动」也会被判成 hasCrop=true → hasEdit=true → 点发布走 Transformer，
     * 按这组参数把完整视频硬裁掉一圈（横屏更是左右各砍 31%）。
     * 用户看到的就是"发布后画面缺一块"，而且几乎所有视频都中招。
     * 结论：裁剪参数只能在用户真的操作过之后才生效。
     */
    var userEdited by remember(inputUri) { mutableStateOf(false) }
    // 比例锁定：null=自由；否则锁定框的宽高比（拖动框边只做等比缩放）
    var cropRatio by remember { mutableStateOf<Float?>(null) }
    var cropRatioLabel by remember { mutableStateOf("自由") }

    var trimStart by remember { mutableStateOf(0L) }
    var trimEnd by remember { mutableStateOf(0L) }

    var previewMode by remember { mutableStateOf(false) }
    var activeTool by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var flashPlaying by remember { mutableStateOf(false) }
    var flashNonce by remember { mutableStateOf(0) }
    LaunchedEffect(flashNonce) { if (flashNonce > 0) { delay(600); flashNonce = 0 } }

    // 预览区像素尺寸
    var areaPx by remember { mutableStateOf(IntSize.Zero) }
    val wPx = areaPx.width.toFloat()
    val hPx = areaPx.height.toFloat()

    val videoAspect = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 9f / 16f
    val areaAspect = if (hPx > 0f) wPx / hPx else videoAspect
    // 视频 cover 尺寸（铺满预览区时的显示尺寸，px）——必须与 coverSize() 用同一套公式（max）
    val covW = max(wPx, hPx * videoAspect)
    val covH = covW / videoAspect

    // 视频当前显示矩形（px）
    val dispW = covW * vZoom
    val dispH = covH * vZoom
    val vLeft = (0.5f + vPanX) * wPx - dispW / 2f
    val vTop = (0.5f + vPanY) * hPx - dispH / 2f

    // 由「框 ∩ 视频显示矩形」反推裁剪区域（视频归一化坐标）
    val cL = ((frameL * wPx - vLeft) / dispW).coerceIn(0f, 1f)
    val cR = ((frameR * wPx - vLeft) / dispW).coerceIn(0f, 1f)
    val cT = ((frameT * hPx - vTop) / dispH).coerceIn(0f, 1f)
    val cB = ((frameB * hPx - vTop) / dispH).coerceIn(0f, 1f)

    // 实际生效的裁剪区域：用户没动过画面就是「全画面」。
    // 预览映射、结果框、导出参数三者必须统一用这一组值，
    // 否则"预览里看到的"和"最终导出的"对不上（正是"发布后缺一块"的直接原因）。
    val effL = if (userEdited) cL else 0f
    val effT = if (userEdited) cT else 0f
    val effR = if (userEdited) cR else 1f
    val effB = if (userEdited) cB else 1f

    val hasCrop = userEdited && (cL > 0.002f || cT > 0.002f || cR < 0.998f || cB < 0.998f)
    val hasTrim = trimStart > 0 || trimEnd > 0
    val hasEdit = hasCrop || hasTrim

    // 结果视图（裁剪结果）的显示比例与尺寸：按结果比例“收进”预览区（不溢出）
    val resAspect = if (videoW > 0) ((effR - effL) * videoW) / ((effB - effT) * videoH) else videoAspect
    val resW = if (wPx <= hPx * resAspect) wPx else hPx * resAspect
    val resH = resW / resAspect

    val cropToolActive = activeTool == "裁剪" && videoW > 0

    // 画面矩阵：结果视图 = 把裁剪区域映射到结果框；编辑视图 = identity（框本身已定位好视频）
    fun applyMatrix(tv: TextureView, resultFit: Boolean) {
        val bw = tv.width
        val bh = tv.height
        if (bw <= 0 || bh <= 0 || videoW <= 0 || videoH <= 0) return
        val m = Matrix()
        if (resultFit) {
            // TextureView 的内容默认铺满整个 View，矩阵在 View 像素坐标系上生效：
            // 把「裁剪区域」这块内容缩放平移到正好填满 View。
            // 用 eff* 而不是 c*：没动过画面时 eff 就是全幅，矩阵退化为 identity，
            // 预览显示完整视频（不会莫名放大一圈）。
            val dw = (effR - effL).coerceAtLeast(0.0001f)
            val dh = (effB - effT).coerceAtLeast(0.0001f)
            m.setScale(1f / dw, 1f / dh)
            m.postTranslate(-effL * bw / dw, -effT * bh / dh)
        } else {
            m.reset()
        }
        tv.setTransform(m)
    }

    // 预览播放器
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var tvRef by remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(inputUri) {
        val p = ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(inputUri))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
        player = p
        onDispose { p.release(); player = null }
    }

    // 片段实时预览：播放限制在 [trimStart, trimEnd] 内循环（改完立即生效）
    LaunchedEffect(trimStart, trimEnd, player) {
        val p = player ?: return@LaunchedEffect
        if (trimStart <= 0 && trimEnd <= 0) return@LaunchedEffect
        p.seekTo(if (trimStart > 0) trimStart else 0L)
        while (true) {
            delay(120)
            val pos = p.currentPosition
            when {
                trimEnd > 0 && pos >= trimEnd - 100 -> p.seekTo(trimStart)
                trimStart > 0 && pos < trimStart - 400 -> p.seekTo(trimStart)
            }
        }
    }

    // 系统返回键：预览中→退出预览；工具展开中→收起；否则关闭编辑器（导出中禁用）
    BackHandler(enabled = !exporting) {
        when {
            previewMode -> previewMode = false
            activeTool != null -> activeTool = null
            else -> onCancel()
        }
    }

    // 视频 cover 尺寸（铺满预览区时的显示尺寸，px）；每次现算
    fun coverSize(): Pair<Float, Float> {
        val W = areaPx.width.toFloat(); val H = areaPx.height.toFloat()
        if (W <= 0f || H <= 0f || videoW <= 0) return 0f to 0f
        // cover = 至少一个方向铺满：显示宽高取大的一侧
        val cw = max(W, H * videoAspect)
        return cw to cw / videoAspect
    }

    // 把平移夹到「视频始终覆盖画框」（尺寸每次现算，避免手势里拿到过期值）
    fun clampPan(panX: Float, panY: Float, zoom: Float): Pair<Float, Float> {
        val W = areaPx.width.toFloat(); val H = areaPx.height.toFloat()
        if (W <= 0f || H <= 0f) return panX to panY
        val (cw, ch) = coverSize()
        if (cw <= 0f) return panX to panY
        val dw = cw * zoom
        val dh = ch * zoom
        val fL = frameL * W; val fR = frameR * W
        val fT = frameT * H; val fB = frameB * H
        val loX = fR / W - 0.5f - dw / (2f * W)
        val hiX = fL / W - 0.5f + dw / (2f * W)
        val loY = fB / H - 0.5f - dh / (2f * H)
        val hiY = fT / H - 0.5f + dh / (2f * H)
        val cx = (fL + fR) / (2f * W) - 0.5f
        val cy = (fT + fB) / (2f * H) - 0.5f
        val nx = if (loX <= hiX) panX.coerceIn(loX, hiX) else cx
        val ny = if (loY <= hiY) panY.coerceIn(loY, hiY) else cy
        return nx to ny
    }

    // 最小缩放：视频显示矩形至少要把画框盖住
    fun minZoom(): Float {
        val W = areaPx.width.toFloat(); val H = areaPx.height.toFloat()
        val (cw, ch) = coverSize()
        if (cw <= 0f || ch <= 0f || W <= 0f) return 1f
        return max((frameR - frameL) * W / cw, (frameB - frameT) * H / ch)
    }

    // 选中某个比例：框吸附成该比例下的最大区域（保持当前中心）
    fun applyCropRatio(ratio: Float, label: String) {
        cropRatio = ratio
        cropRatioLabel = label
        userEdited = true // 选了比例 = 明确要求按这个比例裁
        if (wPx <= 0f || hPx <= 0f) return
        val k = ratio / areaAspect // 归一化 宽/高
        val cw: Float; val ch: Float
        if (k >= 1f) { cw = 1f; ch = 1f / k } else { cw = k; ch = 1f }
        val cx = (frameL + frameR) / 2f
        val cy = (frameT + frameB) / 2f
        frameL = (cx - cw / 2f).coerceIn(0f, 1f - cw)
        frameT = (cy - ch / 2f).coerceIn(0f, 1f - ch)
        frameR = frameL + cw
        frameB = frameT + ch
        // 框变大了可能要相应放大视频，保证仍被覆盖
        vZoom = vZoom.coerceAtLeast(minZoom())
        val (nx, ny) = clampPan(vPanX, vPanY, vZoom)
        vPanX = nx; vPanY = ny
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // 顶栏：仅 ← 返回（预览模式隐藏；无标题）
        if (!previewMode) {
            Text("←", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(12.dp).clickable { onCancel() })
        }

        // 预览区：单击 暂停/继续；双击 进出预览
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                .onSizeChanged { areaPx = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (!exporting) {
                                val p = player
                                if (p != null) {
                                    p.playWhenReady = !p.playWhenReady
                                    flashPlaying = p.playWhenReady
                                    flashNonce++
                                }
                            }
                        },
                        onDoubleTap = { if (!exporting) previewMode = !previewMode }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (cropToolActive) {
                // ── 编辑视图：视频按缩放/平移显示，上叠裁剪框 ──
                Box(
                    Modifier
                        // vLeft/vTop 是按「预览区左上角为原点」算出的**绝对**坐标，
                        // 而外层 Box 是 contentAlignment=Center —— 子元素会先被居中，再叠加 offset，
                        // 等于偏移了两次：竖屏源垂直多偏几十~上百 px、横屏源水平能偏近一个屏宽。
                        // 表现出来就是"画面被推到右上角、左边和下边空了"，而且拖框位置与实际裁切对不上。
                        // 必须显式对齐左上角，offset 才是真正的绝对位置。
                        .align(Alignment.TopStart)
                        .offset(x = vLeft.toDp(density), y = vTop.toDp(density))
                        .size(width = dispW.toDp(density), height = dispH.toDp(density))
                ) {
                    AndroidView(
                        factory = { c ->
                            TextureView(c).apply {
                                tvRef = this
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                        player?.setVideoTextureView(this@apply)
                                        applyMatrix(this@apply, false)
                                    }
                                    override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                        applyMatrix(this@apply, false)
                                    }
                                    override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture) = true
                                    override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
                                }
                            }
                        },
                        update = { tv -> tvRef = tv; applyMatrix(tv, false) }
                    )
                    // 裁剪框（纯绘制）
                    CropFrameOverlay(frameL, frameT, frameR, frameB)
                }

                // 手势层：单指拖框边/平移视频，双指捏合缩放视频
                Box(
                    Modifier.fillMaxSize()                .pointerInput(Unit) {
                        awaitEachGesture {
                            // 尺寸现读：pointerInput(Unit) 只在首次组合跑一次，不能在外面固化
                            val W = areaPx.width.toFloat()
                            val H = areaPx.height.toFloat()
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val fL0 = frameL; val fT0 = frameT; val fR0 = frameR; val fB0 = frameB
                            val px = down.position.x; val py = down.position.y
                            val fl = fL0 * W; val fr = fR0 * W; val ft = fT0 * H; val fb = fB0 * H
                            val hot = 70f
                            val dL = if (py in ft..fb) abs(px - fl) else Float.MAX_VALUE
                            val dR = if (py in ft..fb) abs(px - fr) else Float.MAX_VALUE
                            val dT = if (px in fl..fr) abs(py - ft) else Float.MAX_VALUE
                            val dB = if (px in fl..fr) abs(py - fb) else Float.MAX_VALUE
                            val edge = listOf(1 to dL, 2 to dR, 3 to dT, 4 to dB)
                                .filter { it.second < hot }.minByOrNull { it.second }
                            val mode = when {
                                edge != null -> edge.first
                                px in fl..fr && py in ft..fb -> 5 // 框内 = 平移视频
                                else -> 0
                            }
                            var pinching = false
                            var prevDist = 0f
                            var accX = 0f; var accY = 0f
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    // 双指：缩放视频本身（间接改变裁剪区域），以捏合中心为锚点，避免内容乱飘
                                    pinching = true
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val d = (p0 - p1).getDistance()
                                    if (prevDist > 0f && d > 1f) {
                                        val nz = (vZoom * d / prevDist).coerceIn(minZoom(), 4f)
                                        val (cw, ch) = coverSize()
                                        if (cw > 0f) {
                                            val dwOld = cw * vZoom; val dhOld = ch * vZoom
                                            val dwNew = cw * nz; val dhNew = ch * nz
                                            val cxp = (p0.x + p1.x) / 2f
                                            val cyp = (p0.y + p1.y) / 2f
                                            // 捏合中心原本对应的视频归一化坐标
                                            val u = (cxp - ((0.5f + vPanX) * W - dwOld / 2f)) / dwOld
                                            val v = (cyp - ((0.5f + vPanY) * H - dhOld / 2f)) / dhOld
                                            // 缩放后让同一视频点仍留在捏合中心
                                            val vlNew = cxp - u * dwNew
                                            val vtNew = cyp - v * dhNew
                                            vZoom = nz
                                            val (nx, ny) = clampPan(
                                                (vlNew + dwNew / 2f) / W - 0.5f,
                                                (vtNew + dhNew / 2f) / H - 0.5f, nz
                                            )
                                            vPanX = nx; vPanY = ny
                                            userEdited = true // 用户真的缩放/平移过，裁剪参数从此生效
                                        }
                                    }
                                    prevDist = d
                                    pressed.forEach { it.consume() }
                                } else if (!pinching && pressed.size == 1) {
                                    val delta = pressed[0].positionChange()
                                    accX += delta.x; accY += delta.y
                                    // 防抖：累计超过 12px 才认定为拖动（双指落下前的那一下不会带着视频乱跑）
                                    if (!dragging && (abs(accX) > 12f || abs(accY) > 12f)) dragging = true
                                    if (dragging) {
                                        userEdited = true // 拖框边 / 框内平移，都算真实编辑
                                        val dx = delta.x / W
                                        val dy = delta.y / H
                                        val l0 = frameL; val t0 = frameT
                                        val r0 = frameR; val b0 = frameB
                                        when (mode) {
                                            1 -> frameL = (l0 + dx).coerceIn(0f, r0 - 0.08f)
                                            2 -> frameR = (r0 + dx).coerceIn(l0 + 0.08f, 1f)
                                            3 -> frameT = (t0 + dy).coerceIn(0f, b0 - 0.08f)
                                            4 -> frameB = (b0 + dy).coerceIn(t0 + 0.08f, 1f)
                                            5 -> {
                                                // 框内拖动 = 平移视频（视频跟着手指走）
                                                val (nx, ny) = clampPan(vPanX + dx, vPanY + dy, vZoom)
                                                vPanX = nx; vPanY = ny
                                            }
                                        }
                                        // 框变大可能要放大视频，保证仍被覆盖
                                        vZoom = vZoom.coerceAtLeast(minZoom())
                                        val (nx, ny) = clampPan(vPanX, vPanY, vZoom)
                                        vPanX = nx; vPanY = ny
                                        pressed[0].consume()
                                    }
                                }
                                if (event.changes.none { it.pressed }) break
                            }
                        }
                    }
                )
            } else {
                // ── 结果视图：裁剪区域撑满按结果比例算出的框（所见即所得） ──
                Box(
                    Modifier.size(width = resW.toDp(density), height = resH.toDp(density))
                ) {
                    AndroidView(
                        factory = { c ->
                            TextureView(c).apply {
                                tvRef = this
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                        player?.setVideoTextureView(this@apply)
                                        applyMatrix(this@apply, true)
                                    }
                                    override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                        applyMatrix(this@apply, true)
                                    }
                                    override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture) = true
                                    override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
                                }
                            }
                        },
                        update = { tv -> tvRef = tv; applyMatrix(tv, true) }
                    )
                }
            }

            // 预览模式：复用调用方传入的真实短视频叠加层（头像/点赞/评论/收藏/左下用户名）
            if (previewMode) { previewOverlay() }

            // 单击暂停/继续的瞬时图标反馈（无文字，600ms 后自动消失）
            if (flashNonce > 0) {
                Icon(
                    if (flashPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.Center).size(62.dp)
                )
            }
        }

        // ── 编辑模式：工具面板 + 底部工具栏（预览模式全部隐藏） ──
        if (!previewMode) {
            // 工具控制面板（选中某工具时展开）
            if (activeTool != null) {
                Column(
                    Modifier.fillMaxWidth().background(Color(0xF20B0E13)).padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    when (activeTool) {
                        "裁剪" -> {
                            // 常用比例快捷按钮：点一下自动吸附成该比例的最大区域（手指拖不准，比例才是主力）
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RatioChip("自由", cropRatioLabel == "自由") {
                                    cropRatio = null; cropRatioLabel = "自由"
                                }
                                RatioChip("原比例", cropRatioLabel == "原比例") { applyCropRatio(videoAspect, "原比例") }
                                RatioChip("9:16", cropRatioLabel == "9:16") { applyCropRatio(9f / 16f, "9:16") }
                                RatioChip("1:1", cropRatioLabel == "1:1") { applyCropRatio(1f, "1:1") }
                                RatioChip("16:9", cropRatioLabel == "16:9") { applyCropRatio(16f / 9f, "16:9") }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                Text("重置", color = Color(0xFF60A5FA), fontSize = 13.sp,
                                    modifier = Modifier.clickable {
                                        frameL = 0f; frameT = 0f; frameR = 1f; frameB = 1f
                                        vZoom = 1f; vPanX = 0f; vPanY = 0f
                                        cropRatio = null; cropRatioLabel = "自由"
                                        // 重置回初始态 = 没有任何编辑意图，发布时直接用原文件
                                        userEdited = false
                                    }.padding(8.dp))
                            }
                        }
                        "片段" -> {
                            if (durationMs > 0) {
                                TrimTimeline(inputUri, durationMs, trimStart, trimEnd) { s, e ->
                                    trimStart = s; trimEnd = e
                                }
                            } else Text("无法读取时长", color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 13.sp)
                        }
                    }
                }
            }

            // 底部：工具按钮 + 发布
            Column(
                Modifier.fillMaxWidth().background(Color(0xF20B0E13))
                    .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToolBtn("裁剪", activeTool == "裁剪") { activeTool = if (activeTool == "裁剪") null else "裁剪" }
                    ToolBtn("片段", activeTool == "片段") { activeTool = if (activeTool == "片段") null else "片段" }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (exporting) com.aurora.chat.ui.theme.appTextSecondary() else Color(0xFF1E40AF))
                        .clickable(enabled = !exporting && videoW > 0) {
                            // 先暂停预览播放：转码/上传期间不让解码器与声音占资源（否则上传容易超时失败）
                            player?.playWhenReady = false
                            scope.launch {
                                exporting = true
                                // 预览用的是显示尺寸；导出要换算回编码帧空间（效果链作用在未旋转帧上）
                                val coded = displayCropToCoded(videoRotation, effL, effT, effR, effB)
                                val cw = meta.codedW
                                val chh = meta.codedH
                                val out = computeCropOutputSize(cw, chh, coded[0], coded[2], coded[1], coded[3])
                                val cfg = VideoEditConfig(
                                    inW = cw, inH = chh,
                                    cropL = coded[0], cropT = coded[2], cropR = coded[1], cropB = coded[3],
                                    trimStartMs = trimStart, trimEndMs = trimEnd,
                                    outW = out.width, outH = out.height
                                )
                                val result = if (hasEdit) {
                                    ErrorReporter.debug("VideoPublish", "点发布: 有编辑，先转码（裁剪/片段参数见转码开始日志）")
                                    exportVideo(ctx, inputUri, cfg) { exportProgress = it }
                                } else {
                                    ErrorReporter.debug("VideoPublish", "点发布: 无编辑，跳过转码直接用原视频 $inputUri")
                                    inputUri
                                }
                                exporting = false
                                if (result != null) {
                                    onConfirm(result, "", "")
                                } else {
                                    ErrorReporter.error("VideoPublish", "点发布失败，弹Toast: 处理失败 ${lastExportError ?: "请重试"}")
                                    android.widget.Toast.makeText(
                                        ctx, "处理失败：${lastExportError ?: "请重试"}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (exporting) "处理中 ${(exportProgress * 100).toInt()}%" else "发布",
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Float(dp 像素值) → Dp */
private fun Float.toDp(density: androidx.compose.ui.unit.Density) = with(density) { this@toDp.toDp() }

/**
 * 裁剪框（纯绘制）：框外变暗 + 三分辅助线 + 四边抓手。
 * l/t/r/b 为预览区归一化 0..1。把手画在框内侧，避免贴屏幕边被系统手势吞掉。
 */
@Composable
private fun CropFrameOverlay(l: Float, t: Float, r: Float, b: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val fl = l * w; val fr = r * w; val ft = t * h; val fb = b * h
        val dim = Color.Black.copy(alpha = 0.55f)
        // 框外变暗
        drawRect(dim, topLeft = Offset(0f, 0f), size = Size(w, ft))
        drawRect(dim, topLeft = Offset(0f, fb), size = Size(w, h - fb))
        drawRect(dim, topLeft = Offset(0f, ft), size = Size(fl, fb - ft))
        drawRect(dim, topLeft = Offset(fr, ft), size = Size(w - fr, fb - ft))
        // 框线
        val white = Color.White.copy(alpha = 0.92f)
        val lw = 2.5f
        drawLine(white, Offset(fl, ft), Offset(fr, ft), lw)
        drawLine(white, Offset(fl, fb), Offset(fr, fb), lw)
        drawLine(white, Offset(fl, ft), Offset(fl, fb), lw)
        drawLine(white, Offset(fr, ft), Offset(fr, fb), lw)
        // 三分辅助线
        val grid = Color.White.copy(alpha = 0.28f)
        drawLine(grid, Offset(fl + (fr - fl) / 3, ft), Offset(fl + (fr - fl) / 3, fb), 1.2f)
        drawLine(grid, Offset(fl + (fr - fl) * 2 / 3, ft), Offset(fl + (fr - fl) * 2 / 3, fb), 1.2f)
        drawLine(grid, Offset(fl, ft + (fb - ft) / 3), Offset(fr, ft + (fb - ft) / 3), 1.2f)
        drawLine(grid, Offset(fl, ft + (fb - ft) * 2 / 3), Offset(fr, ft + (fb - ft) * 2 / 3), 1.2f)
        // 四边抓手（画在框内侧 18dp 处，保证贴边时也点得到）
        val inset = 18.dp.toPx()
        val gLen = 40f
        val gThick = 6f
        val rad = CornerRadius(gThick / 2)
        drawRoundRect(white, Offset((fl + fr) / 2 - gLen / 2, ft + inset), Size(gLen, gThick), cornerRadius = rad)
        drawRoundRect(white, Offset((fl + fr) / 2 - gLen / 2, fb - inset - gThick), Size(gLen, gThick), cornerRadius = rad)
        drawRoundRect(white, Offset(fl + inset, (ft + fb) / 2 - gLen / 2), Size(gThick, gLen), cornerRadius = rad)
        drawRoundRect(white, Offset(fr - inset - gThick, (ft + fb) / 2 - gLen / 2), Size(gThick, gLen), cornerRadius = rad)
    }
}

/**
 * 片段时间轴：背景是视频胶片缩略图（逐帧抽取、来一帧显示一帧），
 * 蓝色区间 = 保留部分，两端大手柄拖动调整开始/结束，区间内拖动整体移动。
 */
@Composable
private fun TrimTimeline(
    videoUri: Uri,
    durationMs: Long,
    startMs: Long,
    endMs: Long, // 0 = 到结尾
    onChange: (Long, Long) -> Unit
) {
    val ctx = LocalContext.current
    val curStart by rememberUpdatedState(startMs)
    val curEnd by rememberUpdatedState(endMs)
    val endEff = if (endMs > 0) endMs else durationMs

    // 胶片缩略图：逐帧抽取、来一帧就显示一帧（不等全部到齐）
    val frames = remember(videoUri) { mutableStateListOf<ImageBitmap>() }
    LaunchedEffect(videoUri, durationMs) {
        frames.clear()
        if (durationMs <= 0L) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val ret = android.media.MediaMetadataRetriever()
            try {
                ret.setDataSource(ctx, videoUri)
                val count = 10
                for (i in 0 until count) {
                    val tUs = durationMs * 1000L * i / (count - 1)
                    val bmp = ret.getFrameAtTime(
                        tUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: continue
                    val targetW = 160
                    val targetH = (targetW * bmp.height / max(bmp.width, 1)).coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                    frames.add(small.asImageBitmap())
                    if (small !== bmp) bmp.recycle()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { ret.release() }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // 时间标签：开始 / 保留时长 / 结束
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("开始 %.1fs".format(startMs / 1000f), color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 12.sp)
            Text("保留 %.1fs".format((endEff - startMs) / 1000f), color = Color(0xFF60A5FA),
                fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("结束 %.1fs".format(endEff / 1000f), color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(72.dp)
                .pointerInput(durationMs) {
                    if (durationMs <= 0L) return@pointerInput
                    val w = size.width.toFloat()
                    val dur = durationMs.toFloat()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val sl = curStart / dur * w
                        val sr = (if (curEnd > 0) curEnd else durationMs) / dur * w
                        val hot = 100f // 大手柄热区，好按
                        val mode = when {
                            abs(down.position.x - sl) < hot -> 1 // 左手柄
                            abs(down.position.x - sr) < hot -> 2 // 右手柄
                            down.position.x in sl..sr -> 3        // 区间内整体移动
                            else -> 0
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size == 1) {
                                val delta = pressed[0].positionChange()
                                if (delta != Offset.Zero) {
                                    val dMs = delta.x / w * dur
                                    val s0 = curStart
                                    val e0 = if (curEnd > 0) curEnd else durationMs
                                    when (mode) {
                                        1 -> onChange((s0 + dMs).toLong().coerceIn(0L, e0 - 500L), curEnd)
                                        2 -> {
                                            val ne = (e0 + dMs).toLong()
                                            onChange(curStart, if (ne >= durationMs) 0L
                                                             else ne.coerceAtLeast(s0 + 500L))
                                        }
                                        3 -> {
                                            val len = e0 - s0
                                            val ns = (s0 + dMs).toLong().coerceIn(0L, durationMs - len)
                                            onChange(ns, if (ns + len >= durationMs) 0L else ns + len)
                                        }
                                    }
                                    pressed[0].consume()
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val dur = if (durationMs > 0) durationMs.toFloat() else 1f
                // 背景：有帧显示帧，还没加载到就纯黑（不要灰不灰的胶囊）
                if (frames.isNotEmpty()) {
                    val slotW = w / frames.size
                    frames.forEachIndexed { i, img ->
                        drawImage(
                            img,
                            dstOffset = IntOffset((i * slotW).toInt(), 0),
                            dstSize = IntSize(slotW.toInt().coerceAtLeast(1), h.toInt())
                        )
                    }
                } else {
                    drawRect(Color.Black)
                }
                // 区间外压暗
                val sl = startMs / dur * w
                val sr = endEff / dur * w
                val dim = Color.Black.copy(alpha = 0.6f)
                drawRect(dim, topLeft = Offset(0f, 0f), size = Size(sl, h))
                drawRect(dim, topLeft = Offset(sr, 0f), size = Size(w - sr, h))
                // 区间上下亮边，让"选中的是哪段"更清楚
                drawRect(Color(0xFF60A5FA), Offset(sl, 0f), Size(sr - sl, 3f))
                drawRect(Color(0xFF60A5FA), Offset(sl, h - 3f), Size(sr - sl, 3f))
                // 两端大手柄（20dp 宽白色圆角竖条 + 蓝色防滑纹）
                val hw = 20f
                val rad = CornerRadius(10f)
                drawRoundRect(Color.White, Offset(sl, 0f), Size(hw, h), cornerRadius = rad)
                drawRoundRect(Color.White, Offset(sr - hw, 0f), Size(hw, h), cornerRadius = rad)
                val grip = Color(0xFF1E40AF)
                for (i in 0 until 3) {
                    val gy = h * (0.28f + i * 0.22f)
                    drawRoundRect(grip, Offset(sl + hw / 2 - 2f, gy - 8f), Size(4f, 16f),
                        cornerRadius = CornerRadius(2f))
                    drawRoundRect(grip, Offset(sr - hw / 2 - 2f, gy - 8f), Size(4f, 16f),
                        cornerRadius = CornerRadius(2f))
                }
            }
        }
    }
}

/** 裁剪比例快捷按钮（选中高亮） */
@Composable
private fun RatioChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextPrimary())
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextHint(), fontSize = 13.sp)
    }
}

/** 底部工具按钮（选中时高亮 + 下划线） */
@Composable
private fun ToolBtn(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) Color(0xFF60A5FA) else Color.White,
            fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.size(width = 18.dp, height = 2.dp)
            .background(if (selected) Color(0xFF60A5FA) else Color.Transparent))
    }
}
