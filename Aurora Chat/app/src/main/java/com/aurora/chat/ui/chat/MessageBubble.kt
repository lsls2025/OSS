package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.data.upload.MediaUploadManager
import com.aurora.chat.ui.components.BubbleColorStorage
import com.aurora.chat.ui.components.BubbleTailSide
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.ShimmerText
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.components.avatarCornerRadius
import com.aurora.chat.ui.components.bubbleCornerRadius
import com.aurora.chat.ui.components.bubbleTailEnabled
import com.aurora.chat.ui.components.bubbleBackground
import com.aurora.chat.ui.text.UrlText
import com.aurora.chat.ui.text.LinkPreviewCard
import com.aurora.chat.ui.text.firstUrl
import com.aurora.chat.ui.text.LocalAutoSelectAll
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.chat.FlashIcon
import com.aurora.chat.ui.chat.FlashViewerDialog
import com.aurora.chat.ui.chat.VideoThumbnailView
import com.aurora.chat.ui.chat.media.ChatMediaImage
import com.aurora.chat.ui.chat.media.MediaStore
import com.aurora.chat.ui.chat.isLocationCardMessage
import com.aurora.chat.ui.chat.parseLocationCard
import com.aurora.chat.ui.chat.LocationCardData
import com.aurora.chat.ui.chat.isShareCardMessage
import com.aurora.chat.ui.chat.parseShareCard
import com.aurora.chat.ui.chat.isPrivacyRequestCard
import com.aurora.chat.ui.chat.isPrivacyResponseCard
import com.aurora.chat.ui.chat.PrivacyCard
import com.aurora.chat.ui.chat.isClearChatRequestCard
import com.aurora.chat.ui.chat.isClearChatRejectCard
import com.aurora.chat.ui.chat.ClearChatCard
import java.io.File
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import com.aurora.chat.data.local.VideoThumbCache
import com.aurora.chat.ui.chat.media.calcThumbLayout
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import androidx.compose.material3.AlertDialog

/**
 * 会话内头像统一尺寸，同时作为单行气泡的最小高度：改这一处即可让头像与气泡整体等比缩放。
 * 38dp 对照微信/QQ，比早期 42dp 更省纵向空间，同屏能多露出消息、减少滑动。
 * 单行气泡内容高 = 行高 21 + 上下内边距各 8 = 37dp < 38dp，气泡被撑到 38dp 且文字垂直居中，
 * 与头像上下边完全对齐（不会出现「头像小、气泡大」）。
 */
internal val ChatAvatarSize = 36.dp

/**
 * 「等待模型响应」空窗的开始时间缓存（消息 id → elapsedRealtime 毫秒）。
 * LazyColumn 虚拟化会把滚出视口的气泡回收，重新组合时 remember 会重置；
 * 用消息 id 维度的 map 缓存保证"滑到历史再切回"等待秒数连续、不归零。
 */
private val aiWaitStartMap = mutableMapOf<Long, Long>()

/**
 * AI 自定义头像的全局可观察状态：用户上传后替换默认 AI 头像（ai_avatar 资源）。
 * 使用 mutableStateOf 以便对话中已渲染的 AI 气泡在上传/恢复后自动刷新。
 */
object AiAvatarState {
    var bitmap by mutableStateOf<android.graphics.Bitmap?>(null)
}

/**
 * AI 自定义名称的全局可观察状态：用户设置后替换默认名称（AI）。
 * 使用 mutableStateOf 以便聊天列表 / 对话标题 / AI 气泡在设置后自动刷新。
 */
object AiNameState {
    var name by mutableStateOf<String?>(null)
}

/**
 * 默认 AI 头像：渐变蓝色圆角徽章 + 白色「AI」，代表通用 AI（不再使用 DeepSeek 鲸鱼 logo）。
 * 字体大小随头像尺寸自动缩放，替代原先依赖 ai_avatar 图片资源的兜底显示。
 */
@Composable
fun AiDefaultAvatar(modifier: Modifier = Modifier, size: Dp = ChatAvatarSize) {
    BoxWithConstraints(
        modifier = modifier.clip(RoundedCornerShape(avatarCornerRadius(size))).background(
            Brush.verticalGradient(listOf(Color(0xFF79A3FF), Color(0xFF3E63DD)))
        ),
        contentAlignment = Alignment.Center
    ) {
        val fs = (maxWidth.value * 0.32f).sp
        Text(
            text = "AI",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = fs,
            letterSpacing = (fs.value * 0.14f).sp,
            maxLines = 1
        )
    }
}

/**
 * 图片渲染（委托给重写后的 ChatMediaImage，统一媒体来源与失败兜底，避免显示空气泡）。
 */
@Composable
private fun MediaImageView(
    url: String,
    isMine: Boolean,
    loadMedia: Boolean,
    highlightAnim: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    onImageClick: (String) -> Unit,
    onBubbleLongPress: () -> Unit,
    mediaWidth: Int = 0,
    mediaHeight: Int = 0,
    onBoundsMeasured: (String, Rect) -> Unit = { _, _ -> },
    /** 导出截图用：已解码的同步 Bitmap，非空时 ChatMediaImage 直接绘制不走 Coil */
    forcedBitmap: ImageBitmap? = null
) = ChatMediaImage(
    url = url,
    isMine = isMine,
    loadMedia = loadMedia,
    highlightAnim = highlightAnim,
    onImageClick = onImageClick,
    onBubbleLongPress = onBubbleLongPress,
    mediaSize = if (mediaWidth > 0 && mediaHeight > 0) mediaWidth to mediaHeight else null,
    onBoundsMeasured = onBoundsMeasured,
    forcedBitmap = forcedBitmap
)





private data class LocalPreviewData(
    val bitmap: ImageBitmap,
    val ratioW: Int,
    val ratioH: Int
)

private fun decodeLocalImage(ctx: Context, uri: String): LocalPreviewData? = runCatching {
    var ow = 0
    var oh = 0
    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        val source = if (uri.startsWith("content://")) {
            ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))
        } else {
            ImageDecoder.createSource(File(uri.removePrefix("file://")))
        }
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            ow = info.size.width
            oh = info.size.height
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(ow, oh)
            if (longest > 480) {
                val s = 480f / longest
                decoder.setTargetSize(
                    (ow * s).toInt().coerceAtLeast(1),
                    (oh * s).toInt().coerceAtLeast(1)
                )
            }
        }
    } else {
        val path = uri.removePrefix("file://")
        fun open() = if (uri.startsWith("content://")) {
            ctx.contentResolver.openInputStream(Uri.parse(uri))
        } else {
            java.io.FileInputStream(path)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ow = bounds.outWidth
        oh = bounds.outHeight
        if (ow <= 0 || oh <= 0) return@runCatching null
        var sample = 1
        while (maxOf(ow, oh) / (sample * 2) > 480) sample *= 2
        open()?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
    }
    LocalPreviewData(
        bitmap = bmp.asImageBitmap(),
        ratioW = bmp.width,
        ratioH = bmp.height
    )
}.getOrNull()

private fun decodeLocalVideo(ctx: Context, uri: String): LocalPreviewData? = runCatching {
    val frame = VideoThumbCache.localFrame(ctx, uri, 480) ?: return@runCatching null
    LocalPreviewData(
        bitmap = frame.asImageBitmap(),
        ratioW = frame.width,
        ratioH = frame.height
    )
}.getOrNull()

private fun loadLocalPreview(ctx: Context, uri: String, isVideo: Boolean): LocalPreviewData? =
    when {
        uri.isEmpty() -> null
        isVideo -> decodeLocalVideo(ctx, uri)
        else -> decodeLocalImage(ctx, uri)
    }

@Composable
private fun UploadingMediaPreview(
    previewUri: String,
    isVideo: Boolean,
    knownWidth: Int = 0,
    knownHeight: Int = 0,
    fallbackRatio: Float = 0f,
    progress: Float,
    stage: String,
    onCancelUpload: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    var data by remember(previewUri) { mutableStateOf<LocalPreviewData?>(null) }
    LaunchedEffect(previewUri, isVideo) {
        data = withContext(Dispatchers.IO) { loadLocalPreview(ctx, previewUri, isVideo) }
    }

    val ratioW = if (knownWidth > 0) knownWidth else data?.ratioW ?: 0
    val ratioH = if (knownHeight > 0) knownHeight else data?.ratioH ?: 0
    val layout = if (ratioW > 0 && ratioH > 0) calcThumbLayout(ratioW, ratioH, 150.dp, 200.dp) else null
    val boxW: Dp = layout?.width ?: 160.dp
    val boxH: Dp = layout?.height ?: if (fallbackRatio > 0f) (160.dp / fallbackRatio) else 160.dp

    Box(
        modifier = Modifier
            .width(boxW)
            .height(boxH)
            .clip(RoundedCornerShape(6.dp))
            .background(com.aurora.chat.ui.theme.appTextSecondary())
    ) {
        data?.let { d ->
            Image(
                bitmap = d.bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (layout?.cropTop == true || layout?.cropMiddle == true) {
                    ContentScale.Crop
                } else {
                    ContentScale.Fit
                },
                alignment = if (layout?.cropTop == true) Alignment.TopCenter else Alignment.Center
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x33000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val showPercent = stage != "compressing" && progress > 0f
                if (showPercent) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                        trackColor = Color(0x4DFFFFFF)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color.White,
                        strokeWidth = 3.dp
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (showPercent) {
                    Text(
                        if (progress >= 0.999f) "发送中" else "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }
                when (stage) {
                    "compressing" -> Text("压缩中...", fontSize = 11.sp, color = Color.White)
                    "uploading" -> if (progress < 0.999f) {
                        Text("上传中...", fontSize = 10.sp, color = Color(0xFFD1D5DB))
                    }
                    "failed" -> Text("重新发送中...", fontSize = 10.sp, color = Color(0xFFFCA5A5))
                }
            }
        }

        if (onCancelUpload != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .clickable { onCancelUpload() },
                contentAlignment = Alignment.Center
            ) {
                Text("×", fontSize = 14.sp, color = Color.White)
            }
        }
    }
}

/** 引用胶囊最小宽度：内容再短也留出这么宽，避免缩成一小撮（参考 QQ 的下限）。 */
private val QuoteCapsuleMinWidth = 128.dp

/**
 * 气泡内容布局：引用胶囊与正文共用一个宽度（「气泡内容宽」）。
 *
 * 架构：把「胶囊底片」从胶囊内容里拆出来，作为独立节点参与测量：
 *   1) 底片（纯背景 + 点击层）——测量阶段按最终共享宽固定尺寸，所以永远左右贴住气泡，右侧不留白；
 *   2) 胶囊内容（表头 + 预览）——按自然宽测一次，决定共享宽的下界；
 *   3) 正文——按自然宽测一次，决定共享宽的上界。
 * 共享宽 = min(气泡上限, max(胶囊自然宽, 正文自然宽, 下限))。
 *
 * 为什么不能把背景留在内容里：内容只能被测量一次（Compose 禁止同一 Measurable 在一次测量中
 * measure 两次），要么换 fillMaxWidth（短消息被撑满整行），要么按内容自适应（正文更宽时右侧留白）。
 * 拆出底片后两个目标同时成立：胶囊跟着气泡走，内容更宽时气泡也跟着变宽，不会被提前截断；
 * 只有超过气泡上限时才触发单行省略。
 */
@Composable
private fun QuoteBubbleContent(
    modifier: Modifier = Modifier,
    hasQuote: Boolean,
    maxWidth: Dp,
    minFloor: Dp,
    capsuleColor: Color,
    onCapsuleClick: () -> Unit,
    onCapsuleLongPress: () -> Unit,
    quoteContent: @Composable () -> Unit,
    bodyContent: @Composable () -> Unit
) {
    val hPad = 10.dp
    val vPad = 6.dp
    val gap = 4.dp
    val density = LocalDensity.current
    Layout(
        modifier = modifier,
        content = {
            if (hasQuote) {
                // 底片必须排在最前：绘制顺序决定层级，底片永远在内容之下
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(capsuleColor)
                        .combinedClickable(onClick = onCapsuleClick, onLongClick = onCapsuleLongPress)
                )
                Box { quoteContent() }
            }
            // 正文可能包含「展开/收起」等多个节点，用 Column 包成一个 Measurable
            Column { bodyContent() }
        }
    ) { measurables, constraints ->
        val maxW = constraints.maxWidth.coerceAtMost(with(density) { maxWidth.roundToPx() })
        val minW = with(density) { minFloor.roundToPx() }
        val hPadPx = with(density) { hPad.roundToPx() }
        val vPadPx = with(density) { vPad.roundToPx() }
        val gapPx = with(density) { gap.roundToPx() }

        if (!hasQuote) {
            val body = measurables.first().measure(Constraints(maxWidth = maxW))
            layout(body.width, body.height) { body.place(0, 0) }
        } else {
            // 三个节点各测一次（不同 Measurable，符合 Compose 的单次测量限制）
            val quote = measurables[1].measure(Constraints(maxWidth = (maxW - hPadPx * 2).coerceAtLeast(0)))
            val body = measurables[2].measure(Constraints(maxWidth = maxW))
            val capsuleW = (quote.width + hPadPx * 2).coerceAtMost(maxW)
            val shared = maxOf(capsuleW, body.width, minW).coerceAtMost(maxW)
            val capsuleH = quote.height + vPadPx * 2
            val chrome = measurables[0].measure(
                Constraints.fixedWidth(shared).copy(minHeight = capsuleH, maxHeight = capsuleH)
            )
            layout(shared, capsuleH + gapPx + body.height) {
                chrome.place(0, 0)
                quote.place(hPadPx, vPadPx)
                body.place(0, capsuleH + gapPx)
            }
        }
    }
}

/**
 * 引用胶囊的表头：被引用者名字 + 右侧时间（QQ 同款）。
 *
 * 时间取的是「被引用那条消息的发出时间」：优先 clientSendAt（客户端发送瞬间），
 * 没有则回退服务端 createdAt；不是引用动作发生的时刻。
 * 宽度用 weight(1f, fill = false)：名字长了先省略，时间始终贴着名字右侧。
 */
@Composable
private fun QuoteHeader(name: String, timeMs: Long, nameColor: Color, timeColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        val label = remember(timeMs) { quoteTimeLabel(timeMs) }
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                color = timeColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * 引用胶囊的内容（表头 + 预览），不含背景与内边距。
 *
 * 背景/内边距由 [QuoteBubbleContent] 的底片负责，这样胶囊才能既按内容自适应、
 * 又永远与气泡同宽。自己气泡 / 对方（AI）气泡共用这一份实现，只靠颜色区分，
 * 避免两边各写一套、宽度逻辑各走一条路（之前「一边右侧留白、一边提前截断」就是这么来的）。
 */
@Composable
private fun QuoteCapsuleInner(
    replyIsRevoked: Boolean,
    replyIsMine: Boolean,
    replyToSender: String,
    replyTimeMs: Long,
    replyMediaType: String,
    replyMediaUrl: String,
    replyToText: String,
    replyVoiceSeconds: Int,
    flashDuration: Int,
    textColor: Color,
    subColor: Color,
    onFileClick: (String, String) -> Unit,
    onBoundsMeasured: (String, Rect) -> Unit
) {
    // 语音判定走统一入口：mediaType 不可靠（m4a 会被落成 .mp4），见 isVoiceQuote
    val isVoiceRef = isVoiceQuote(replyMediaType, replyMediaUrl, replyToText, replyVoiceSeconds)
    // 表情：sticker:// 协议没有扩展名，不单独拦就会被下面的 URL 兜底判成文件，
    // 缩略块上只显示 "FILE"（用户看到的「引用了文件」就是这个）。
    // emoji 引用：mediaUrl 存 emoji 字符本身，不单独拦就会被 URL 兜底判成文件，缩略块只显示 "FILE"
    val isEmojiRef = replyMediaType == "emoji" || (replyMediaUrl.isNotEmpty() && isEmojiMediaUrl(replyMediaUrl))
    val isStickerRef = replyMediaType == "sticker" || replyMediaUrl.startsWith("sticker://")
    val isImgRef = !isVoiceRef && replyMediaUrl.isNotEmpty() && (replyMediaType.startsWith("image") || isImageMediaUrl(replyMediaUrl))
    val isVideoRef = !isVoiceRef && replyMediaUrl.isNotEmpty() && (replyMediaType.startsWith("video") || replyMediaType == "mp4" || isVideoMediaUrl(replyMediaUrl))
    Column {
        QuoteHeader(
            name = if (replyIsRevoked) "消息已撤回" else (if (replyIsMine) "我" else replyToSender.ifBlank { "对方" }),
            timeMs = replyTimeMs,
            nameColor = subColor,
            timeColor = subColor.copy(alpha = 0.8f)
        )
        when {
            replyIsRevoked -> Text("[消息已撤回]", fontSize = 12.sp, color = textColor, maxLines = 1)
            // emoji 引用：直接显示 emoji 字符缩略，不再落到文件/文字占位
            isEmojiRef -> QuoteEmojiThumb(replyMediaUrl)
            // 表情：直接显示表情缩略图，不再落到文件/文字占位
            isStickerRef -> QuoteStickerThumb(replyMediaUrl)
            // 语音：直接把小号语音气泡放进胶囊（播放三角 + 秒数），
            // 不再走灰色图标/视频缩略图那套占位。
            isVoiceRef -> QuoteVoiceThumb(seconds = replyVoiceSeconds)
            isImgRef -> QuoteImageThumb(replyMediaUrl, onClick = { onFileClick(replyMediaUrl, quoteMediaFileName(replyMediaUrl)) }, modifier = Modifier.quoteThumbBounds(replyMediaUrl, onBoundsMeasured))
            // 视频：保留真实宽高比（与正文那档同一口径，但封顶更小，不撑爆胶囊）
            isVideoRef -> VideoThumbnailView(
                videoUrl = replyMediaUrl,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).quoteThumbBounds(replyMediaUrl, onBoundsMeasured).clickable {
                    onFileClick(
                        resolveMediaUrl(replyMediaUrl),
                        quoteMediaFileName(replyMediaUrl, "mp4")
                    )
                },
                maxWidthDp = 100.dp,
                maxHeightDp = 150.dp
            )
            // 文件：用文件卡片那块扩展名缩略（固定 40dp，不会撑爆胶囊）
            replyMediaType == "file" || replyMediaType.startsWith("application/") -> QuoteFileThumb(replyMediaUrl, onClick = { onFileClick(replyMediaUrl, quoteMediaFileName(replyMediaUrl)) }, modifier = Modifier.quoteThumbBounds(replyMediaUrl, onBoundsMeasured))
            replyMediaType == "transfer" -> QuoteThumbIcon(Icons.Default.Paid)
            // 带 mediaUrl 且不是图片/视频/语音：一律按文件缩略渲染。
            // 必须排在 replyToText 之前——文件引用的预览文本就是 "[文件]"，
            // 排在后面就只剩一行字，看不到缩略。
            replyMediaUrl.isNotEmpty() -> QuoteFileThumb(replyMediaUrl, onClick = { onFileClick(replyMediaUrl, quoteMediaFileName(replyMediaUrl)) }, modifier = Modifier.quoteThumbBounds(replyMediaUrl, onBoundsMeasured))
            // 预览文本明确是「[文件]」：即使 mediaUrl 缺失也画文件缩略块，
            // 不再退化成一串文字（扩展名取不到时显示 FILE）。
            replyToText.startsWith("[文件]") -> QuoteFileThumb(replyMediaUrl, onClick = { onFileClick(replyMediaUrl, quoteMediaFileName(replyMediaUrl)) }, modifier = Modifier.quoteThumbBounds(replyMediaUrl, onBoundsMeasured))
            flashDuration > 0 -> QuoteThumbIcon(Icons.Default.Flare)
            // 单行预览：不预先 take(40) 硬砍，宽不下时才由 Text 自己省略并补 "…"
            replyToText.isNotBlank() -> Text(
                text = replyToText,
                fontSize = 12.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 引用时间文案：今天只给 HH:mm，昨天加「昨天」，今年给月日，更早给年月日。 */
private fun quoteTimeLabel(timeMs: Long): String {
    if (timeMs <= 0L) return ""
    val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
    val now = Calendar.getInstance()
    val hm = String.format(
        Locale.CHINA, "%02d:%02d",
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
    )
    val diff = ChatTimeFormat.dayIndex(now.timeInMillis / 1000L) -
        ChatTimeFormat.dayIndex(timeMs / 1000L)
    val md = "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
    return when {
        diff <= 0L -> hm
        diff == 1L -> "昨天 $hm"
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> md
        else -> "${cal.get(Calendar.YEAR)}年$md"
    }
}

/**
 * 引用胶囊里媒体类型的图标缩略：浅灰底 + 居中图标（视频/文件/卡片等无图资源时用）。
 */
@Composable
private fun QuoteThumbIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(com.aurora.chat.ui.theme.appDivider()),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.size(22.dp))
    }
}

/**
 * 引用胶囊里的小号语音气泡：白底 + 浅描边 + 播放三角 + 秒数，
 * 与消息本体的 VoiceMessageCard 同一套语言（40dp 高，和图片/文件缩略同高）。
 * 不接播放器：胶囊整体点击是「跳到原消息」，与图片缩略图保持一致。
 * 宽度随秒数小幅增长（78dp → 124dp 封顶），不会撑爆胶囊。
 */
@Composable
private fun QuoteVoiceThumb(seconds: Int) {
    val secs = seconds.coerceIn(0, 99)
    val secText = if (secs > 0) "$secs″" else "语音"
    val w = (78 + secs.coerceAtMost(26) * 1.7f).dp.coerceAtMost(124.dp)
    Row(
        modifier = Modifier
            .width(w)
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(com.aurora.chat.ui.theme.appCardSurface())
            .border(1.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(11.dp)) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(p, Color(0xFF1E40AF))
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = secText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = com.aurora.chat.ui.theme.appTextSecondary(),
            maxLines = 1
        )
    }
}

/**
 * 引用缩略图点击时用的文件名：取 URL 末段、去掉 query。
 *
 * [fallbackExt] 只在末段确实没有扩展名时补上——视频常常是 `/chat-media/<uuid>` 这种无后缀地址，
 * 不补就会在 ChatConversationScreen 的 onFileClick 里落到「下载确认」，而不是直接进播放器。
 */
private fun quoteMediaFileName(url: String, fallbackExt: String = ""): String {
    val n = url.substringAfterLast('/').substringBefore('?').ifEmpty { "文件" }
    if (fallbackExt.isNotEmpty() && !n.contains('.')) return "$n.$fallbackExt"
    return n
}

/**
 * 引用缩略图的屏幕矩形上报。
 *
 * 键位带 `quote|` 前缀，与正文里同一张图的登记分开——两者是屏幕上两个不同位置，
 * 共用一个 key 就会出现「点引用缩略图，动画却从正文那张图的位置飞出来」。
 * 全屏预览打开时按需用 [com.aurora.chat.ui.chat.media.ImageBoundsRegistry.getQuote] 读取。
 */
private fun Modifier.quoteThumbBounds(
    rawUrl: String,
    onBoundsMeasured: (String, Rect) -> Unit
): Modifier = this.onGloballyPositioned { c ->
    val p = c.localToWindow(Offset.Zero)
    onBoundsMeasured(
        com.aurora.chat.ui.chat.media.ImageBoundsRegistry.QUOTE_KEY_PREFIX + resolveMediaUrl(rawUrl),
        Rect(p.x, p.y, p.x + c.size.width, p.y + c.size.height)
    )
}

/**
 * 引用胶囊里的文件缩略：固定 40dp，不随文件名长度变化，保证不会把胶囊撑变形。
 *
 * APK 与正文文件卡片保持同一套表现：调 [ApkIconExtractor] 解出安装包自带的应用图标，
 * 而不是只画「APK」几个字母。提取器自带内存 + 磁盘缓存，正文卡片已经取过的话这里直接命中，
 * 不会重复下载。解不出图标时退回 Android 占位图标；其它类型仍用扩展名色块。
 */
@Composable
private fun QuoteFileThumb(url: String, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val name = url.substringAfterLast('/').substringBefore('?')
    val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase().take(4) else ""
    val accent = extColor(ext)
    val isApk = ext == "apk"

    val context = LocalContext.current
    var apkIcon by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        if (!isApk || url.isBlank()) return@LaunchedEffect
        // 服务端可能只存相对路径，补全成可下载地址；本地 file:// 直接走本地文件解析，不发起网络请求。
        val iconUrl = when {
            url.startsWith("http") || url.startsWith("file://") || url.startsWith("content://") -> url
            else -> resolveMediaUrl(url)
        }
        val localPath = if (url.startsWith("file://")) url.removePrefix("file://") else null
        com.aurora.chat.util.ApkIconExtractor.ensureIcon(context, iconUrl, localPath) { bmp ->
            apkIcon = bmp
        }
    }
    val icon = apkIcon

    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp))
            // 点缩略图 = 直接打开/下载该文件（APK 则走下载后询问安装）；
            // 胶囊其余区域仍是「定位原消息」，两级点击分工与 QQ 一致。
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(if (isApk && icon != null) Color.Transparent else accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            isApk && icon != null -> Image(
                bitmap = icon!!.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(2.dp)
            )
            isApk -> Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = "APK",
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            // 取不到扩展名时也不再显示「FILE」这串字母，用通用文件图标代替
            ext.isEmpty() -> Icon(
                imageVector = Icons.Filled.InsertDriveFile,
                contentDescription = "文件",
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            else -> Text(
                text = ext.uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1
            )
        }
    }
}

/**
 * 引用胶囊里的图片缩略：保留图片真实宽高比（像 QQ 那样直接按原比例显示，而不是压成正方形），
 * 最长边封顶 58dp，另一边等比收窄。
 * gif / 动图 webp 必须显式挂 ImageDecoderDecoder（API 28+），否则 Coil 只解第一帧。
 */
@Composable
private fun QuoteImageThumb(url: String, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val ext = url.substringAfterLast('.').substringBefore('?').lowercase()
    val animated = ext == "gif" || ext == "webp"
    val ctx = LocalContext.current
    val model: Any = if (animated && android.os.Build.VERSION.SDK_INT >= 28) {
        remember(url) {
            coil.request.ImageRequest.Builder(ctx)
                .data(url)
                .decoderFactory(coil.decode.ImageDecoderDecoder.Factory())
                .build()
        }
    } else url
    // 先按 1:1 占位，加载完成后用真实宽高比重算尺寸（与视频缩略同一套 videoDisplaySize 口径，上限更小）
    var aspect by remember(url) { mutableStateOf(1f) }
    val (thumbW, thumbH) = videoDisplaySize(aspect, 100.dp, 150.dp)
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(thumbW, thumbH)
            .clip(RoundedCornerShape(4.dp))
            // 点缩略图 = 直接看大图；胶囊其余区域仍是「定位原消息」
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        onSuccess = { state ->
            val size = (state as? coil.compose.AsyncImagePainter.State.Success)?.painter?.intrinsicSize
            if (size != null && size.width > 0 && size.height > 0) {
                aspect = size.width / size.height
            }
        }
    )
}

/**
 * 引用胶囊里的表情缩略：走 sticker:// 协议 → assets/stickers/<组>/<名>.webp，
 * 与气泡里的 StickerMessageView 同一套解码（动图必须显式 ImageDecoderDecoder，
 * 否则 Coil 只解第一帧）。固定 40dp，ContentScale.Fit 不裁不变形。
 */
@Composable
private fun QuoteStickerThumb(mediaUrl: String) {
    val rel = mediaUrl.removePrefix("sticker://").trim().trim('/')
    val ctx = LocalContext.current
    // 探测资源是否还在包内：历史引用消息可能指向已下架的表情（如 sticker://gif/gif001）。
    // null = 检查中（仍按正常图片渲染，避免闪一下占位）、false = 确认缺失 → 走灰块兜底。
    // 以后若接入服务端历史归档回源，把 missing 分支换成回源 URL 即可，其余逻辑不用动。
    val exists = androidx.compose.runtime.produceState<Boolean?>(initialValue = null, rel) {
        value = if (rel.isEmpty()) false else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                ctx.assets.open("stickers/$rel.webp").use { }
                true
            }.getOrDefault(false)
        }
    }.value
    if (rel.isEmpty() || exists == false) {
        // 协议异常 / 资源已下架：给个 40dp 灰块，绝不渲染成空白
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(com.aurora.chat.ui.theme.appDivider()),
            contentAlignment = Alignment.Center
        ) {
            Text("表情", fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = 1)
        }
        return
    }
    val req = remember(rel) {
        coil.request.ImageRequest.Builder(ctx)
            .data("file:///android_asset/stickers/$rel.webp")
            .decoderFactory(
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    coil.decode.ImageDecoderDecoder.Factory()
                else
                    coil.decode.BitmapFactoryDecoder.Factory()
            )
            .build()
    }
    AsyncImage(
        model = req,
        contentDescription = "表情",
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
    )
}

/**
 * 引用胶囊里「这是不是语音」的唯一判定入口。
 *
 * 不能只信 replyMediaType：语音的 m4a 走上传链路时，后端按容器 magic bytes 判成 video/mp4，
 * mediaUrl 会以 .mp4 结尾，类型也可能被写成 mp4/video，于是胶囊按后缀一路落到视频缩略图分支。
 * 所以按「权威类型 → 引用预览文本 → 音频后缀 → mp4 后缀 + 纯数字时长」逐级兜底。
 */
private fun isVoiceQuote(
    replyMediaType: String,
    replyMediaUrl: String,
    replyToText: String,
    replyVoiceSeconds: Int
): Boolean {
    if (replyMediaType == "voice" || replyMediaType == "audio" ||
        replyMediaType.startsWith("audio/")
    ) return true
    // 引用预览文本是发送时按被引用消息的真实类型写死的，比 mediaType 更接近事实
    if (replyToText.startsWith("[语音]")) return true
    val ext = replyMediaUrl.substringAfterLast('.').substringBefore('?').lowercase()
    if (ext in setOf("m4a", "aac", "mp3", "amr", "ogg", "wav", "opus")) return true
    // 最后一条兜底：语音的 content 存的是秒数（1..600），而 .mp4 恰好是 m4a 的真实容器后缀；
    // 视频消息的 content 不会是这种纯数字，因此「mp4 后缀 + 纯数字时长」判为语音。
    if (ext == "mp4" && replyVoiceSeconds in 1..600) return true
    return false
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(text: String, isMine: Boolean, fromUserId: Long, myAvatar: android.graphics.Bitmap?, displayName: String, showSenderName: Boolean = false, groupConvId: Long = 0, currentUserId: Long = 0, onAvatarClick: (Long, String) -> Unit = { _, _ -> }, onBubbleTap: () -> Unit = {}, onBubbleLongPress: () -> Unit = {}, selectable: Boolean = false, replyToText: String = "", replyToSender: String = "", replyToId: Long = 0, replyIsRevoked: Boolean = false, replyIsMine: Boolean = false, replyMediaType: String = "", replyMediaUrl: String = "", replyVoiceSeconds: Int = 0, replyTimeMs: Long = 0, isHighlighted: Boolean = false, onCapsuleClick: (Long) -> Unit = {}, onAvatarLongPress: (Long, String) -> Unit = { _, _ -> }, onAvatarDoubleTap: (Long, String) -> Unit = { _, _ -> }, onAtMentionClick: (String) -> Unit = {}, isGroup: Boolean = false, viewingFlashIds: Set<Long> = emptySet(), onFlashViewStart: (Long) -> Unit = {}, groupMemberNames: Set<String> = emptySet(), senderRole: String = "", loadMedia: Boolean = true, mediaType: String = "", mediaUrl: String = "", mediaWidth: Int = 0, mediaHeight: Int = 0, localPreviewUri: String = "", isUploading: Boolean = false, uploadProgress: Float = 0f, uploadStage: String = "", uploadRatio: Float = 0f, uploadFileName: String = "", onCancelUpload: (Long) -> Unit = {}, onImageClick: (String) -> Unit = {}, onBoundsMeasured: (String, Rect) -> Unit = { _, _ -> }, onFileClick: (String, String) -> Unit = { _, _ -> }, onFileDownload: (String, String) -> Unit = { _, _ -> }, flashDuration: Int = 0, reasoningText: String = "", msgId: Long = 0, destroyedFlashIds: Set<Long> = emptySet(), onFlashDestroy: (Long, String) -> Unit = { _, _ -> }, partnerViewedFlashIds: Set<Long> = emptySet(), onOpenPostDetail: (Long) -> Unit = {},
    onOpenGroupDetail: (Long) -> Unit = {},
    /** 「分享聊天」卡片点击 → 打开全屏查看页（参数为卡片原始文本） */
    onOpenChatShare: (String) -> Unit = {},
    onReasoningToggled: (Boolean) -> Unit = {}, expandedReasoningMsgId: Long = 0,
    thinkingSeconds: Long = 0, totalSeconds: Long = 0,
    inputTokens: Long = 0, outputTokens: Long = 0, cacheHitTokens: Long = 0, contextLength: Long = 0,
    aiCostDeducted: Long = 0,
    /** 本条消息发送时使用的 API 模式：free_api 显示「无消耗」；personal/third_party/paid_api 均展示真实 token 消耗(输入+输出,无倍率) */
    aiMode: String = "",
    aiFlatBubble: Boolean = false,
    manualStopped: Boolean = false,
    /** 中断记录的 token 数值是否来自服务端权威结算（false=客户端估算）；仅 manualStopped 时有意义 */
    aiUsageAuthoritative: Boolean = false,
    aiWaiting: Boolean = false,
    aiProgressHint: String = "",
    /** 是否为会话最后一条消息：仅最后一条的「继续处理任务」按钮才允许显示（上一条消息的继续功能随新消息发出而失效）。 */
    isLastMessage: Boolean = false,
    /**
     * 是否为本任务「固定工具日志」行的唯一归属消息。
     *
     * 开启「固定调用和改动」后，多轮工具调用会不断新建 AI 气泡；若每条气泡都挂
     * 「调用 N 个工具 / 查看 N 处改动」，用户会看到好几个重复行。这里由渲染层指定
     * 本任务最后一条普通 AI 气泡来承载，新气泡出现时旧气泡自动让位 —— 视觉上这一行
     * 跟着内容一路往下走，而不是钉在过程中间。
     */
    isFixedLogOwner: Boolean = true,
    onContinueClick: (() -> Unit)? = null,
    onCodeBackToTop: () -> Unit = {},
    onRespondGroupInvite: ((InviteGroupCardData) -> Unit)? = null,
    /** AI 本次会话的文件改动记录(JSON 字符串,空表示无改动):非空时强制渲染「查看所有改动」按钮 */
    aiFileChanges: String = "",
    /** 本条消息的真实工具调用记录(成败):透传给交付卡片,用于把"模型说完成但工具其实报错"压回来 */
    toolStages: List<ChatMsg.ToolStageRecord> = emptyList(),
    /** 本条消息累计调用的工具次数(用于底部信息行展示) */
    toolCallCount: Int = 0,
    /** 点击「查看所有改动」回调:null 时不渲染按钮 */
    onViewChanges: (() -> Unit)? = null,
    /** 点击「调用 N 个工具」回调:null 或无记录时不渲染按钮 */
    onToolCalls: (() -> Unit)? = null,
    /** 文件引用点击回调：0=查看 1=所在位置 */
    onFileRefAction: ((String, Int) -> Unit)? = null,
    /** 方案卡片「构建」按钮回调（照抄 CodeBuddy 方案卡）：点击后由上层发送规范执行指令 */
    onPlanBuild: (() -> Unit)? = null,
    /** 方案卡片「✎ 编辑」回调：仅未开工时可用（构建前可改方案） */
    onPlanEdit: (() -> Unit)? = null,
    /** 本条方案所属任务是否已整体完成：置 true 时流程步骤全部勾选为已完成 */
    planAllDone: Boolean = false,
    /** 任务流程卡（最新方案卡进度）：仅最新一条 AI 消息会拿到它，渲染在交付总结上方 */
    livePlan: PlanCardData? = null,
    /** 我方气泡配色（来自 BubbleColorStorage，默认微信绿）；深底色时气泡内文字自动转白 */
    myBubbleColor: Color = BubbleColorStorage.DEFAULT,
    /** 对方气泡配色（来自 BubbleColorStorage，默认白色）；仅本地视角生效，深底色时文字同样转白 */
    otherBubbleColor: Color = BubbleColorStorage.DEFAULT_OTHER,
    /** 导出截图用：已解码的同步图片 Bitmap（key=resolveMediaUrl 后的 URL），
     *  非空时图片消息直接绘制该 Bitmap，不走 Coil 异步管线，避免离屏导出画空 */
    exportMediaBitmaps: Map<String, ImageBitmap> = emptyMap(),
    /** 短视频模块分享卡片点击 → 打开 ShortVideoScreen 并定位到该视频（参数为视频 id） */
    onOpenShortVideo: (Long) -> Unit = {},
    /**
     * 本条消息发送失败：在气泡**左侧**渲染红色感叹号（不占气泡内部空间，不改变气泡宽度）。
     * 点击感叹号由 [onRetrySend] 交给上层弹「是否重新发送」确认。
     */
    sendFailed: Boolean = false,
    onRetrySend: () -> Unit = {},
    /**
     * 「已读显示 → 气泡左侧」：非空时在气泡**左边**画一行 11sp 浅灰小字（已读/未读/N人已读），
     * 与气泡垂直居中。null = 不显示（走默认的「气泡下方」或干脆不显示）。
     */
    readLabelLeft: String? = null,
    /** 左侧已读小字的点击回调：群聊=看已读名单，私聊=立刻重查一次。null = 不可点 */
    onReadLabelClick: (() -> Unit)? = null) {
    // 头像尺寸统一取 ChatAvatarSize（38dp）：气泡用 heightIn(min = avatarSize) 锁死同一高度，
    // 单行气泡被撑到与头像上下边完全对齐（不会「头像小、气泡大」）。
    // 想再提高密度只能从头像下手，鱼和熊掌不可兼得。
    val avatarSize = ChatAvatarSize
    // 导出截图用：本消息的预解码图片（非空时图片消息直接绘制，不走 Coil）
    val exportMediaBitmap = remember(exportMediaBitmaps, mediaUrl) {
        if (mediaUrl.isEmpty()) null else exportMediaBitmaps[resolveMediaUrl(mediaUrl)]
    }
    // 气泡最大宽度：按屏宽比例（微信/QQ 同款思路），不再写死 260dp。
    // 260dp 在 360dp 屏上约占 72%，但在 393/412dp 宽屏上只剩 63~66% ——
    // 同样一段长消息要多换 1~3 行，这才是「长消息放不下」的适配问题。
    // 比例取 0.70（微信/QQ 同级）；平板/折叠屏再封顶 400dp，避免气泡被拉成一整屏长条。
    val bubbleMaxWidth = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.70f)
        .coerceAtMost(400f).dp
    // ── 气泡内文字色：只由气泡底色决定，与页面主题无关 ──
    // 气泡底色是用户自己设的（默认我方品牌蓝 / 对方白），不随深浅主题变化；
    // 文字色若去取主题色（深色主题 appTextPrimary() = 白），黑主题下白色气泡里就是白字，
    // 整条消息直接看不见。所以统一走 BubbleColorStorage 的三口径取色（正文 / 次级 / 胶囊底纹）。
    val myBubbleDark = BubbleColorStorage.isDark(myBubbleColor)
    val myBubbleText = BubbleColorStorage.bubbleTextColor(myBubbleColor)
    // 引用胶囊统一用「气泡正文字色」：早先昵称/时间/预览都取次级灰，引用一句原话整块发灰，
    // 看起来像被禁用、读不清。层级改由字号和时间透明度区分，不再靠把文字变灰。
    val myBubbleCapsuleSub = BubbleColorStorage.bubbleTextColor(myBubbleColor)
    val myBubbleCapsuleText = BubbleColorStorage.bubbleTextColor(myBubbleColor)
    // 对方气泡配色适配（同上，同一套口径）
    val otherBubbleDark = BubbleColorStorage.isDark(otherBubbleColor)
    val otherBubbleText = BubbleColorStorage.bubbleTextColor(otherBubbleColor)
    val otherBubbleCapsuleSub = BubbleColorStorage.bubbleTextColor(otherBubbleColor)
    val otherBubbleCapsuleText = BubbleColorStorage.bubbleTextColor(otherBubbleColor)
    // ── 链接色随气泡底色自适应 ──
    // 旧实现里链接恒为品牌蓝（0xFF1E40AF）：气泡一旦设成品牌深蓝/天蓝/紫/红这类深底，
    // 蓝链接与底色直接糊成一片 —— 字还在、也点得到，但肉眼完全看不见（用户实测反馈）。
    // 深底统一改用**纯白 + 保留下划线**（下划线保住「这是链接」的语义），浅底维持品牌蓝。
    // 之所以用白色而不是某个彩色：气泡底色本身可变（红/紫/蓝都可能），任何固定彩色都会在某色底上低对比，
    // 只有白色对任意深底都是最高对比。
    val myBubbleLink = if (myBubbleDark) Color.White else Color(0xFF1E40AF)
    val otherBubbleLink = if (otherBubbleDark) Color.White else Color(0xFF1E40AF)
    // 所有消息统一按纯文本气泡渲染：不再解析表格 / 代码块 / 交付卡片，也不渲染文件引用胶囊。
    val allowFileRef = false
    // ── 分享文件卡片下载流程：点击 → 确认对话框 → SAF 选择保存位置 → 复制 → Toast ──
    // 保持卡片渲染不变，仅替换点击后的打开行为为"下载保存"。
    val shareFileCtx = LocalContext.current
    var pendingShareDownload by remember { mutableStateOf<String?>(null) }
    val shareDownloadLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val srcPath = pendingShareDownload
        pendingShareDownload = null
        if (uri == null || srcPath == null) return@rememberLauncherForActivityResult
        val src = java.io.File(srcPath)
        if (!src.exists()) {
            android.widget.Toast.makeText(shareFileCtx, "文件不存在或已被移除", android.widget.Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        try {
            shareFileCtx.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            android.widget.Toast.makeText(shareFileCtx, "已保存到 ${src.name}", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(shareFileCtx, "保存失败：${e.message ?: "未知错误"}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // 确认下载对话框：确定后调起系统文件管理器选择保存位置/文件名
    pendingShareDownload?.let { dlPath ->
        AlertDialog(
            onDismissRequest = { pendingShareDownload = null },
            title = { androidx.compose.material3.Text("下载文件") },
            text = { androidx.compose.material3.Text("是否下载该文件？\n${java.io.File(dlPath).name}") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingShareDownload = null
                    shareDownloadLauncher.launch(java.io.File(dlPath).name)
                }) { androidx.compose.material3.Text("确定") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingShareDownload = null }) {
                    androidx.compose.material3.Text("取消")
                }
            }
        )
    }
    // 媒体 URL 以 MediaStore 为权威单一来源：优先用按 msgId 记录的可达 URL，避免重载时丢失导致空气泡
    val effectiveMediaUrl = if (msgId > 0) (MediaStore.get(msgId)?.url ?: mediaUrl) else mediaUrl
    @Suppress("NAME_SHADOWING")
    val mediaUrl = effectiveMediaUrl
    // 高亮闪烁动画（浅蓝色闪3次）
    val highlightAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            highlightAnim.snapTo(0f)
            kotlinx.coroutines.delay(50)
            repeat(3) {
                highlightAnim.animateTo(0.4f, androidx.compose.animation.core.tween(200))
                highlightAnim.animateTo(0f, androidx.compose.animation.core.tween(200))
            }
        }
    }

    // 缩短长按判定时长（默认 400ms → 200ms），让气泡/头像长按更容易触发。
    // 用 remember 缓存包装对象，避免每条消息每次重组都新建匿名 ViewConfiguration。
    val customViewConfig = LocalViewConfiguration.current
    val chatViewConfig = remember(customViewConfig) {
        object : ViewConfiguration by customViewConfig {
            override val longPressTimeoutMillis: Long = 200L
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides chatViewConfig) {
        // 方案卡片(Plan 模式):text 内含 [[PLAN_CARD]] 哨兵 → 直接渲染 PlanCard,跳过常规气泡(重开会话后 text 仍保留,卡片还在)
        val planCardData: PlanCardData? = null // 方案卡片改由 AiInterleavedContent 统一渲染(带构建按钮/上下文/全部完成态),此处不再提前 return
        if (planCardData != null) {
            val ctx = LocalContext.current
            val onOpenPlanDoc: (() -> Unit)? = if (planCardData.docPath.isNotEmpty()) {
                val abs = remember(planCardData.docPath) {
                    val root = com.aurora.chat.ui.chat.AiChatManager.agentRoot(ctx)
                    java.io.File(root, planCardData.docPath).absolutePath
                }
                val action: () -> Unit = { onFileClick("file://$abs", planCardData.docPath.substringAfterLast('/').ifEmpty { "plan.md" }) }
                action
            } else null
            PlanCard(data = planCardData, onOpenDoc = onOpenPlanDoc)
            return@CompositionLocalProvider
        }
    if (isMine) {
        // ── 自己的消息：头像在右，气泡在左 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            // 发送失败红叹号：已下沉到气泡本体旁（见下方气泡 Box 内的 overlay）。
            // 旧版放在这个 Row 里当第一个子项，锚点变成「名字+气泡」整个 Box：
            // 水平上贴 Box 左沿，被更宽的群昵称撑开，叹号和气泡之间空出一截名字宽度的缺口；
            // 垂直上对整块居中，窄气泡时明显偏离气泡中心。
            // 已读显示 →「气泡左侧」：贴在气泡左沿外侧，**底对齐**（多行气泡就跟最后一行走）。
            val readLabelText = readLabelLeft
            if (readLabelText != null && readLabelText.isNotEmpty()) {
                // groupConvId<0 时整个气泡列被上移了 10dp（群聊视觉补偿），底对齐也要跟着上提，
                // 否则这行字会掉到气泡下方去。+6dp = 在「咬住最后一行」的基础上再往下压一点。
                val yShift = (if (groupConvId < 0L) (-10).dp else 0.dp) + 6.dp
                Text(
                    text = readLabelText,
                    fontSize = 11.sp,
                    color = com.aurora.chat.ui.theme.appTextHint(),
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.Bottom)
                        .offset(y = yShift)
                        .padding(end = 6.dp)
                        .then(
                            if (onReadLabelClick == null) Modifier else Modifier
                                .clip(RoundedCornerShape(4.dp))
                                // 不要水波纹：这行只是 11sp 的浅灰小字，涟漪比字本身还显眼
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onReadLabelClick?.invoke() }
                                .padding(horizontal = 2.dp, vertical = 1.dp)
                        )
                )
            }
            // 气泡区（含名字 + 气泡，整体向上偏移，仅限群聊）
            Box(Modifier.widthIn(max = bubbleMaxWidth)) {
                Column(horizontalAlignment = Alignment.End,
                    modifier = if (groupConvId < 0) Modifier.offset(y = (-10).dp) else Modifier) {
                    // 自己的群聊消息也显示名字，徽章在名字左边。
                    // 位置：锚定在**头像一侧**（右侧），而不是锚定气泡边缘。
                    // 气泡宽度随内容长短变化，但 Column 是 wrapContent 的、气泡始终右对齐贴着头像，
                    // 所以 align(End) 的名字与头像的相对位置恒定，长消息也不会被甩到屏幕左边。
                    if (showSenderName && displayName.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            GroupRoleBadge(senderRole)
                            if (senderRole == "owner" || senderRole == "admin") Spacer(Modifier.width(3.dp))
                            Text(
                                text = displayName,
                                fontSize = 11.sp,
                                color = com.aurora.chat.ui.theme.appTextSecondary(),
                                maxLines = 1,
                                modifier = Modifier.padding(bottom = 0.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    // 气泡本体包一层 Box：sendFailed 红叹号作为 overlay 锚定在**气泡**左侧
                    // 并垂直居中于气泡——锚点是气泡本身，不再被上方群昵称的宽度影响。
                    // Box 只包气泡内容、包裹自身大小，所以对正常消息（叹号不出现）零影响。
                    Box {
                    if (mediaType == "emoji" || isEmojiMediaUrl(mediaUrl)) {
                        // emoji 消息：无气泡、64sp 大字号字符渲染（mediaUrl 即字符本身），
                        // 长按仍出菜单（删除/撤回等），点击不进大图（emoji 无查看器链路）
                        EmojiMessageView(
                            emoji = mediaUrl,
                            onBubbleLongPress = onBubbleLongPress
                        )
                    } else if (mediaType == "sticker" || mediaUrl.startsWith("sticker://")) {
                        // 表情消息：无气泡、120dp 正方形渲染（sticker:// 协议 → assets），
                        // 长按仍出菜单（删除/撤回等），点击不进大图（assets 图无查看器链路）
                        StickerMessageView(
                            mediaUrl = mediaUrl,
                            onBubbleLongPress = onBubbleLongPress
                        )
                    } else if (mediaType == "image" || mediaType == "jpg" || mediaType == "png" || mediaType == "gif" || mediaType == "jpeg") {
                        if (isUploading) {
                            // 本地文件就在手上：直接按真实宽高比渲染缩略图（不再是与内容无关的灰块）
                            UploadingMediaPreview(
                                previewUri = localPreviewUri,
                                isVideo = false,
                                knownWidth = mediaWidth,
                                knownHeight = mediaHeight,
                                progress = uploadProgress,
                                stage = uploadStage
                            )
                        } else if (flashDuration > 0 && isMine) {
                            val partnerViewed = msgId in partnerViewedFlashIds
                            val partnerViewing = !partnerViewed && msgId in viewingFlashIds
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (partnerViewed) com.aurora.chat.ui.theme.appTextSecondary() else com.aurora.chat.ui.theme.appTextPrimary()),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FlashIcon(iconSize = 32.dp, color = if (partnerViewed) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextSecondary())
                                    Spacer(Modifier.height(4.dp))
                                    Text(if (partnerViewed) "对方已查看" else if (partnerViewing) "对方查看中" else "对方暂未查看", fontSize = 12.sp, color = if (partnerViewing) Color(0xFF60A5FA) else com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        } else if (flashDuration > 0 && msgId !in destroyedFlashIds) {
                            // 接收方视角：未销毁 → 闪照按钮
                            var showFlashViewer by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(com.aurora.chat.ui.theme.appTextPrimary())
                                    .combinedClickable(
                                        onClick = { showFlashViewer = true },
                                        onLongClick = { onBubbleLongPress() },
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FlashIcon(iconSize = 32.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("闪照", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    Text("点击查看", fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                }
                            }
                            if (showFlashViewer) {
                                FlashViewerDialog(
                                    imageUrl = mediaUrl, durationSec = flashDuration,
                                    onViewStart = { onFlashViewStart(msgId) },
                                    onDestroy = { onFlashDestroy(msgId, mediaUrl) },
                                    onDismiss = { showFlashViewer = false }
                                )
                            }
                        } else if (flashDuration > 0) {
                            // 已销毁/已查看的闪照仍支持长按（长按菜单：复制/删除/撤回等）
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(com.aurora.chat.ui.theme.appTextSecondary())
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { onBubbleLongPress() },
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("图片已销毁", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                        } else {
                        MediaImageView(
                            url = mediaUrl,
                            isMine = isMine,
                            loadMedia = loadMedia,
                            highlightAnim = highlightAnim,
                            onImageClick
                            = onImageClick,
                            onBubbleLongPress = onBubbleLongPress,
                            onBoundsMeasured = onBoundsMeasured,
                            mediaWidth = mediaWidth,
                            mediaHeight = mediaHeight,
                            forcedBitmap = exportMediaBitmap
                        )
                        }
                        } else if (mediaType == "video" || mediaType == "mp4") {
                        if (isUploading) {
                            // 视频首帧 + 真实比例：本地文件直接出图，不靠网络
                            UploadingMediaPreview(
                                previewUri = localPreviewUri,
                                isVideo = true,
                                fallbackRatio = uploadRatio,
                                progress = uploadProgress,
                                stage = uploadStage,
                                onCancelUpload = if (isMine) ({ onCancelUpload(msgId) }) else null
                            )
                        } else {
                        // 视频消息：不带气泡框，显示缩略图（点击应用内播放）
                        // 尺寸与图片消息同一套严格限制（150×200dp 内等比），由 VideoThumbnailView 内部按真实宽高比锁定
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (mediaUrl.isNotEmpty()) onImageClick(mediaUrl) else onBubbleTap()
                                    },
                                    onLongClick = { onBubbleLongPress() },
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                )
                                // 上报视频缩略图矩形：全屏播放器打开/关闭时从这里放大/缩回
                                .onGloballyPositioned { coords ->
                                    if (mediaUrl.isNotEmpty()) {
                                        val p = coords.localToWindow(Offset.Zero)
                                        onBoundsMeasured(
                                            resolveMediaUrl(mediaUrl),
                                            Rect(p.x, p.y, p.x + coords.size.width, p.y + coords.size.height)
                                        )
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(com.aurora.chat.ui.theme.appTextPrimary())
                                    .clip(RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadMedia) VideoThumbnailView(videoUrl = mediaUrl, modifier = Modifier, initialAspectRatio = if (mediaWidth > 0 && mediaHeight > 0) mediaWidth.toFloat() / mediaHeight.toFloat() else 0f)
                                else Box(Modifier.width(150.dp).height(120.dp).background(com.aurora.chat.ui.theme.appTextPrimary().copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { Text("视频", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()) }
                            }
                        }
                        }
                    } else if (mediaType == "voice") {
                        // 语音：独立卡片（秒数借 mediaWidth 承载），自己发和对端收的渲染一致
                        VoiceMessageCard(
                            mediaUrl = mediaUrl,
                            // 秒数优先取 content（跨设备同步），取不到再退回 mediaWidth
                            seconds = text.toIntOrNull() ?: mediaWidth,
                            isUploading = isUploading,
                            uploadProgress = uploadProgress,
                            // 长按冒泡给气泡层，弹出「撤回 / 转发 / 删除」等菜单
                            onLongPress = onBubbleLongPress,
                            // 转文字状态键与长按菜单共用（mediaKey），菜单点击后气泡下方必出结果卡
                            msgId = msgId
                        )
                    } else if (isUploading && mediaType == "file") {
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                        ) {
                            ChatFileUploadCard(
                                fileName = uploadFileName,
                                progress = uploadProgress,
                                stage = uploadStage
                            )
                        }
                    } else if ((mediaUrl.isNotEmpty() && text.isEmpty() && mediaType != "voice" && mediaType != "transfer") || mediaType == "file") {
                        val fileUrl = mediaUrl
                        val fileName = fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "[文件]" }
                        val fileExt = fileName.substringAfterLast('.', "").lowercase()
                        // 诊断：文件卡片渲染决策点，用于定位「会话预览见[文件]但气泡内无卡片」的数据
                        android.util.Log.d("AIFileCard", "render msgId=$msgId mediaType=$mediaType url='$mediaUrl' textLen=${text.length}")
                        ChatFileCard(
                            url = fileUrl,
                            fileName = fileName,
                            ext = fileExt,
                            onClick = { onFileClick(fileUrl, fileName) },
                            onDownload = { onFileDownload(fileUrl, fileName) },
                            onBubbleLongPress = onBubbleLongPress,
                            showBorder = true
                        )
                    } else if (com.aurora.chat.ui.chat.isLocationCardMessage(text)) {
                        val locCtx = androidx.compose.ui.platform.LocalContext.current
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    val loc = com.aurora.chat.ui.chat.parseLocationCard(text)
                                    if (loc != null && loc.lat != 0.0 && loc.lng != 0.0) {
                                        try {
                                            val geoUri = android.net.Uri.parse("geo:${loc.lat},${loc.lng}?q=${loc.lat},${loc.lng}")
                                            val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, geoUri)
                                            locCtx.startActivity(android.content.Intent.createChooser(mapIntent, "选择导航应用"))
                                        } catch (_: Exception) {
                                            android.widget.Toast.makeText(locCtx, "没有可用的导航应用", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                        ) {
                            com.aurora.chat.ui.chat.LocationCard(
                                text = text
                            )
                        }
                    } else if (com.aurora.chat.ui.chat.isShareCardMessage(text)) {
                        com.aurora.chat.ui.chat.ShareCard(
                            text = text,
                            isMine = true,
                            myBubbleColor = myBubbleColor,
                            otherBubbleColor = otherBubbleColor,
                            onPostClick = onOpenPostDetail,
                            onGroupClick = onOpenGroupDetail,
                            onUserClick = { uid -> onAvatarClick(uid, "") },
                            onFileClick = { fp -> pendingShareDownload = fp },
                            onShortVideoClick = onOpenShortVideo,
                            onChatClick = onOpenChatShare,
                            onBubbleLongPress = onBubbleLongPress
                        )
                    } else if (isPrivacyRequestCard(text) || isPrivacyResponseCard(text)) {
                        PrivacyCard(text = text, isMine = true)
                    } else if (com.aurora.chat.ui.chat.isGomokuInviteCard(text)) {
                        com.aurora.chat.ui.chat.GomokuInviteCard(
                            text = text,
                            isMine = true,
                            onBubbleLongPress = onBubbleLongPress
                        )
                    } else if (isInviteGroupCard(text)) {
                        InviteGroupCard(text = text, isMine = true, onRespondClick = onRespondGroupInvite, onBubbleLongPress = onBubbleLongPress)
                    } else if (isClearChatRequestCard(text) || isClearChatRejectCard(text)) {
                        ClearChatCard(text = text, isMine = true, onBubbleLongPress = onBubbleLongPress)
                    } else {
                        // 自己消息的文本气泡（绿色背景）
                        // 选择模式：记录气泡窗口坐标 + 长按按压位置，用于进入选择时预选「按下的那一行」
                        val selBoxCoords = remember { mutableStateOf<LayoutCoordinates?>(null) }
                        val pressWindowOffset = remember { mutableStateOf<Offset?>(null) }
                        Box(
                            modifier = Modifier
                                // 【气泡必须与头像等高】这一行是 Row(Top 对齐)：气泡最小高度锁成头像高度
                                // （avatarSize=38dp），单行文字在气泡内垂直居中，两者上下边永远重合；
                                // 无论以后调字号/调头像，都不会再错位。
                                .widthIn(max = bubbleMaxWidth).heightIn(min = avatarSize).wrapContentHeight()
                                // 气泡背景：普通色=实底；「液态」哨兵色=玻璃材质（bubbleBackground 内部分流）
                                // 液态走胶囊形：折射轮廓必须贴合气泡本身，圆角矩形折射会在短气泡两端露出未折射的白边
                                // 圆角跟随「消息设置 → 气泡设置 → 圆角」：默认 8dp、经典 12dp、也可自定义
                                .bubbleBackground(
                                    myBubbleColor,
                                    corner = bubbleCornerRadius(),
                                    capsule = BubbleColorStorage.isLiquid(myBubbleColor),
                                    // 「气泡设置 → 气泡凸起」：自己发的消息尖角朝右
                                    tail = if (bubbleTailEnabled() && !BubbleColorStorage.isLiquid(myBubbleColor))
                                        BubbleTailSide.RIGHT else BubbleTailSide.NONE
                                )
                                // 捕获气泡在窗口中的坐标，供长按按压位置换算成「窗口坐标」
                                .onGloballyPositioned { selBoxCoords.value = it }
                                // 长按：记录按压位置（窗口坐标）后打开菜单；选择模式下不响应长按。
                                // 放在 combinedClickable 之前优先拿到手势；仅处理长按、不消费点按，
                                // 因此普通点按仍由下方 combinedClickable 处理。
                                .pointerInput(selectable) {
                                    detectTapGestures(onLongPress = { offset ->
                                        if (!selectable) {
                                            val win = selBoxCoords.value?.localToWindow(Offset.Zero)
                                            if (win != null) pressWindowOffset.value = win + offset
                                            onBubbleLongPress()
                                        }
                                    })
                                }
                                // 整个气泡（含无内容的空白/边缘）都可点击：放在 padding 之前 → 热区覆盖整个气泡。
                                // 长按改为上面 pointerInput 处理，这里仅保留点按。
                                .combinedClickable(
                                    enabled = !selectable,
                                    onClick = { onBubbleTap() },
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                )
                                // 左右内边距 10dp：同样字数气泡左右少占 4dp（用户对比 QQ 反馈偏宽）；
                                // 上下 8dp，单行气泡内容高 = 21+16 = 37dp，被 min=38dp 撑成与头像等高。
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .then(if (highlightAnim.value > 0.01f) Modifier.drawWithContent {
                                    drawContent()
                                    drawRect(Color(0xFFBFDBFE).copy(alpha = highlightAnim.value), size = size)
                                } else Modifier),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (selectable) {
                                // 系统原生文字选择（与对方气泡同一套）：进入即自动全选，
                                // 系统选择菜单（复制/全选/朗读…）当场出现。
                                // 不再有自绘的「全选 / 复制 / 取消」小工具条，也不用再长按一次。
                                val selState = rememberSelectionState()
                                val autoFlag = LocalAutoSelectAll.current
                                if (autoFlag != null && !autoFlag.used) {
                                    LaunchedEffect(selState, autoFlag) {
                                        repeat(10) {
                                            withFrameNanos { }
                                            if (selState.getSelectableTexts().isNotEmpty()) {
                                                selState.selectAll()
                                                autoFlag.used = true
                                                return@LaunchedEffect
                                            }
                                        }
                                    }
                                }
                                SelectionContainer(state = selState) {
                                    Text(
                                        text = text,
                                        color = myBubbleText,
                                        fontSize = 15.sp,
                                        lineHeight = 21.sp
                                    )
                                }
                            } else {
                                // 长文本折叠：**真实**超过 6 行（排版溢出）才显示"展开"按钮
                                var isExpanded by remember { mutableStateOf(false) }
                                // 是否真的被裁剪：由 UrlText 按实际排版回报（旧实现用「字符数 >120」当判据，
                                // 中文长文约 6 行才 100 字上下、多短行更短 → 明明被裁掉却不给按钮，内容看不全）
                                var truncated by remember(text) { mutableStateOf(false) }
                                // 折叠阈值 6 → 12：微信/QQ 基本不在 6 行就砍，用户对比后反馈
                                // 「长消息我们能看的更少」。12 行仍能压住超长消息，只是不再过早折叠。
                                val maxCollapsedLines = 12
                                // 引用胶囊与正文共享同一宽度（= 两者自然宽度的最大值，不超气泡上限、且有下限）：
                                // 胶囊左右贴住气泡、无右侧缺口，短消息也不会被撑满整行（QQ 同款）。
                                QuoteBubbleContent(
                                    hasQuote = replyToId > 0L,
                                    maxWidth = bubbleMaxWidth,
                                    minFloor = QuoteCapsuleMinWidth,
                                    capsuleColor = BubbleColorStorage.bubbleCapsuleOverlay(myBubbleColor),
                                    onCapsuleClick = { onCapsuleClick(replyToId) },
                                    onCapsuleLongPress = { onBubbleLongPress() },
                                    quoteContent = {
                                        QuoteCapsuleInner(
                                            replyIsRevoked = replyIsRevoked,
                                            replyIsMine = replyIsMine,
                                            replyToSender = replyToSender,
                                            replyTimeMs = replyTimeMs,
                                            replyMediaType = replyMediaType,
                                            replyMediaUrl = replyMediaUrl,
                                            replyToText = replyToText,
                                            replyVoiceSeconds = replyVoiceSeconds,
                                            flashDuration = flashDuration,
                                            textColor = myBubbleCapsuleText,
                                            subColor = myBubbleCapsuleSub,
                                            onFileClick = onFileClick,
                                            onBoundsMeasured = onBoundsMeasured
                                        )
                                    },
                                    bodyContent = {
                                        UrlText(
                                            text = text,
                                            fontSize = 15.sp,
                                            defaultColor = myBubbleText,
                                            // 深底气泡里链接改白，否则品牌蓝链接与底色糊在一起看不见
                                            urlColor = myBubbleLink,
                                            // 行高 22 → 21sp：15sp 中文正文用 1.4 倍行距（微信/QQ 同级），
                                            // 22sp 是 1.47 倍，长消息 30 行就白白多吃一行高度。
                                            lineHeight = 21.sp,
                                            selectable = false,
                                            clickable = true,
                                            onClick = { if (!selectable) onBubbleTap() },
                                            onLongPress = { if (!selectable) onBubbleLongPress() },
                                            onAtMentionClick = onAtMentionClick,
                                            isGroup = isGroup,
                                            groupMemberNames = groupMemberNames,
                                            maxLines = if (isExpanded) Int.MAX_VALUE else maxCollapsedLines,
                                            // 折叠时末行用「…」示意还有内容（旧实现是硬裁，看起来像内容丢了）
                                            overflow = TextOverflow.Ellipsis,
                                            // 排版结果里只要出现过溢出，就永久记住「这条被裁过」：
                                            // 展开后 maxLines 放开、hasVisualOverflow 变 false，按钮不能因此消失
                                            onOverflowChange = { ov -> if (ov) truncated = true },
                                            allowFileRef = allowFileRef,
                                            onFileRefAction = onFileRefAction
                                        )
                                        // 链接预览卡片：正文里含链接时自动出现（标题/图标解析不到就不渲染）
                                        remember(text) { text.firstUrl() }?.let { link ->
                                            LinkPreviewCard(
                                                url = link,
                                                titleColor = myBubbleText,
                                                dividerColor = myBubbleText.copy(alpha = 0.22f)
                                            )
                                        }
                                        // 真的被裁过才显示展开按钮
                                        if (truncated && !isExpanded) {
                                            Text(
                                                "展开",
                                                fontSize = 13.sp,
                                                color = Color(0xFF576B95),
                                                modifier = Modifier.clickable { isExpanded = true }
                                                    .padding(top = 2.dp)
                                            )
                                        }
                                        if (truncated && isExpanded) {
                                            Text(
                                                "收起",
                                                fontSize = 13.sp,
                                                color = Color(0xFF576B95),
                                                modifier = Modifier.clickable { isExpanded = false }
                                                    .padding(top = 2.dp)
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // 发送失败：红底白叹号 overlay——锚定在气泡左侧、垂直居中于气泡本身，
                    // 不再受上方群昵称宽度/整块高度影响。只在 isMine 分支渲染，对方消息没有失败态。
                    // align(CenterStart) 贴住气泡左沿，再向左偏移出去（6dp 间隙 + 18dp 图标宽），
                    // 不占气泡内部空间，气泡宽度不受影响，长文本也不会把叹号挤出去。
                    // 叹号用 Canvas 手绘，不用 Text("!")：Text 的垂直居中基于字体行高
                    // （ascent/descent 不对称），「!」字形重心偏上、下面还留 descender 空间，
                    // 靠 offset 微调在不同字号/字体上会漂。手绘「圆角竖条 + 圆点」几何位置完全可控，
                    // 整组上下沿 4.5dp / 13.5dp，几何中心正好 9dp = 18dp 圆心，严格居中。
                    if (sendFailed) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = -(24).dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE53935))
                                // 去水波纹：这是状态图标不是按钮，点一下弹确认即可
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onRetrySend() },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val cx = w / 2f
                                val barW = w * 0.10f            // 竖条宽 ≈ 1.8dp
                                val barTop = h * 0.25f          // 4.5dp
                                val barBottom = h * 0.595f      // 10.7dp
                                drawRoundRect(
                                    color = Color.White,
                                    topLeft = Offset(cx - barW / 2f, barTop),
                                    size = Size(barW, barBottom - barTop),
                                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = w * 0.05f,          // 半径 ≈ 0.9dp
                                    center = Offset(cx, h * 0.70f) // 中心 12.6dp
                                )
                            }
                        }
                    }
                    } // 气泡 wrapper Box 收口

                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            // 头像（无波纹）
            val localView = androidx.compose.ui.platform.LocalView.current
            val shakeOff = remember { androidx.compose.animation.core.Animatable(0f) }
            val shakeScope = rememberCoroutineScope()
            val myMod = Modifier
                .size(avatarSize)
                .clip(RoundedCornerShape(avatarCornerRadius(avatarSize)))
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    rotationZ = shakeOff.value
                }
                .pointerInput(fromUserId) {
                    detectTapGestures(
                        onTap = { onAvatarClick(fromUserId, "我") },
                        onDoubleTap = {
                            localView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            shakeScope.launch {
                                shakeOff.animateTo(8f, tween(50))
                                shakeOff.animateTo(-8f, tween(50))
                                shakeOff.animateTo(5f, tween(40))
                                shakeOff.animateTo(-5f, tween(40))
                                shakeOff.animateTo(0f, tween(40))
                            }
                            onAvatarDoubleTap(fromUserId, "我")
                        },
                        onLongPress = {
                            localView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            onAvatarLongPress(fromUserId, "我")
                        }
                    )
                }
            // 右侧头像：根据 fromUserId 决定显示谁的头像
            val rightAvatarMod = Modifier
                .size(avatarSize)
                .clip(RoundedCornerShape(avatarCornerRadius(avatarSize)))
                .graphicsLayer {
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    rotationZ = shakeOff.value
                }
                .pointerInput(fromUserId) {
                    detectTapGestures(
                        onTap = { onAvatarClick(fromUserId, if (fromUserId == currentUserId) "我" else displayName) },
                        onDoubleTap = {
                            localView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            shakeScope.launch {
                                shakeOff.animateTo(8f, tween(50))
                                shakeOff.animateTo(-8f, tween(50))
                                shakeOff.animateTo(5f, tween(40))
                                shakeOff.animateTo(-5f, tween(40))
                                shakeOff.animateTo(0f, tween(40))
                            }
                            onAvatarDoubleTap(fromUserId, if (fromUserId == currentUserId) "我" else displayName)
                        },
                        onLongPress = {
                            localView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            onAvatarLongPress(fromUserId, if (fromUserId == currentUserId) "我" else displayName)
                        }
                    )
                }
            when {
                fromUserId == currentUserId -> {
                    if (myAvatar != null) {
                        Image(bitmap = myAvatar.asImageBitmap(), contentDescription = "我的头像",
                            modifier = rightAvatarMod, contentScale = ContentScale.Fit)
                    } else {
                        Image(painter = painterResource(R.drawable.ic_profile_filled), contentDescription = "我的头像",
                            modifier = rightAvatarMod, contentScale = ContentScale.Fit)
                    }
                }
                fromUserId == AI_CHAT_ID -> {
                    val customAiAvatar = AiAvatarState.bitmap
                    if (customAiAvatar != null) {
                        Image(bitmap = customAiAvatar.asImageBitmap(), contentDescription = "AI",
                            modifier = rightAvatarMod, contentScale = ContentScale.Crop)
                    } else {
                        AiDefaultAvatar(modifier = rightAvatarMod, size = avatarSize)
                    }
                }
                else -> {
                    UserAvatar(userId = fromUserId, userName = displayName.ifEmpty { "?" },
                        size = avatarSize, modifier = rightAvatarMod)
                }
            }
        }
    } else {
            // 别人的群聊消息：头像在左，名字在头像右上，气泡在名字下方
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                val localView2 = androidx.compose.ui.platform.LocalView.current
                // 头像（可点击/长按查看用户资料/艾特）
                if (groupConvId < 0 && fromUserId == groupConvId) {
                    // 群系统消息头像：使用群默认头像
                    val internalGroupId = -(groupConvId + 1000)
                    GroupAvatar(
                        internalGroupId = internalGroupId,
                        groupName = displayName,
                        size = avatarSize,
                        modifier = Modifier
                            .size(avatarSize)
                            .pointerInput(fromUserId) {
                                detectTapGestures(
                                    onTap = { onAvatarClick(fromUserId, displayName) },
                                    onDoubleTap = { onAvatarDoubleTap(fromUserId, displayName) },
                                    onLongPress = {
                                        localView2.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                        onAvatarLongPress(fromUserId, displayName)
                                    }
                                )
                            }
                    )
                } else {
                    if (fromUserId == AI_CHAT_ID) {
                        // AI 对话：优先使用用户上传的自定义头像，否则使用内置 AI 默认头像；不可点击
                        val customAiAvatar = AiAvatarState.bitmap
                        if (customAiAvatar != null) {
                            Image(
                                bitmap = customAiAvatar.asImageBitmap(),
                                contentDescription = "AI",
                                modifier = Modifier.size(avatarSize).clip(RoundedCornerShape(avatarCornerRadius(avatarSize))),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            AiDefaultAvatar(modifier = Modifier.size(avatarSize), size = avatarSize)
                        }
                    } else {
                    if (myAvatar != null && fromUserId == currentUserId) {
                        // 当气泡属于自己时直接显示本地头像
                        Image(
                            bitmap = myAvatar.asImageBitmap(),
                            contentDescription = "我的头像",
                            modifier = Modifier
                                .size(avatarSize)
                                .clip(RoundedCornerShape(avatarCornerRadius(avatarSize))),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                    val shakeOff2 = remember { androidx.compose.animation.core.Animatable(0f) }
                    val shakeScope2 = rememberCoroutineScope()
                    UserAvatar(
                        userId = fromUserId,
                        userName = displayName.ifEmpty { "?" },
                        size = avatarSize,
                        modifier = Modifier
                            .size(avatarSize)
                            .graphicsLayer {
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                                rotationZ = shakeOff2.value
                            }
                            .pointerInput(fromUserId) {
                                detectTapGestures(
                                    onTap = { onAvatarClick(fromUserId, displayName) },
                                    onDoubleTap = {
                                        localView2.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                        shakeScope2.launch {
                                            shakeOff2.animateTo(8f, tween(50))
                                            shakeOff2.animateTo(-8f, tween(50))
                                            shakeOff2.animateTo(5f, tween(40))
                                            shakeOff2.animateTo(-5f, tween(40))
                                            shakeOff2.animateTo(0f, tween(40))
                                        }
                                        onAvatarDoubleTap(fromUserId, displayName)
                                    },
                                    onLongPress = {
                                        localView2.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                        onAvatarLongPress(fromUserId, displayName)
                                    }
                                )
                            }
                    )
                    }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                // 气泡区（名字在上方独占一行，气泡在下方，整体向上偏移，仅限群聊）
                Box(Modifier.widthIn(max = bubbleMaxWidth)) {
                    Column(modifier = if (groupConvId < 0) Modifier.offset(y = (-10).dp) else Modifier) {
                        // 名字：在头像右上角，独占一行；徽章在名字左边。
                        // 位置：锚定在**头像一侧**（左侧），气泡左边缘始终贴着头像，
                        // 因此 align(Start) 的名字与头像相对位置恒定，长消息也不会被甩到屏幕右边。
                        if (showSenderName && displayName.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.align(Alignment.Start)
                            ) {
                                Spacer(Modifier.width(4.dp))
                                GroupRoleBadge(senderRole)
                                if (senderRole == "owner" || senderRole == "admin") Spacer(Modifier.width(3.dp))
                                Text(
                                    text = displayName,
                                    fontSize = 11.sp,
                                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                                    maxLines = 1,
                                    modifier = Modifier.padding(bottom = 0.dp)
                                )
                            }
                        }
                        if (mediaType == "emoji" || isEmojiMediaUrl(mediaUrl)) {
                            // emoji 消息：无气泡、64sp 大字号字符渲染（mediaUrl 即字符本身），
                            // 长按仍出菜单（删除/撤回等），点击不进大图（emoji 无查看器链路）
                            EmojiMessageView(
                                emoji = mediaUrl,
                                onBubbleLongPress = onBubbleLongPress
                            )
                        } else if (mediaType == "sticker" || mediaUrl.startsWith("sticker://")) {
                            StickerMessageView(
                                mediaUrl = mediaUrl,
                                onBubbleLongPress = onBubbleLongPress
                            )
                        } else if (mediaType == "image" || mediaType == "jpg" || mediaType == "png" || mediaType == "gif" || mediaType == "jpeg") {
                        if (isUploading) {
                            // 本地文件就在手上：直接按真实宽高比渲染缩略图（不再是与内容无关的灰块）
                            UploadingMediaPreview(
                                previewUri = localPreviewUri,
                                isVideo = false,
                                knownWidth = mediaWidth,
                                knownHeight = mediaHeight,
                                progress = uploadProgress,
                                stage = uploadStage
                            )
                        } else if (flashDuration > 0 && isMine) {
                            val partnerViewed = msgId in partnerViewedFlashIds
                            val partnerViewing = !partnerViewed && msgId in viewingFlashIds
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (partnerViewed) com.aurora.chat.ui.theme.appTextSecondary() else com.aurora.chat.ui.theme.appTextPrimary()),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FlashIcon(iconSize = 32.dp, color = if (partnerViewed) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextSecondary())
                                    Spacer(Modifier.height(4.dp))
                                    Text(if (partnerViewed) "对方已查看" else if (partnerViewing) "对方查看中" else "对方暂未查看", fontSize = 12.sp, color = if (partnerViewing) Color(0xFF60A5FA) else com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        } else if (flashDuration > 0 && msgId !in destroyedFlashIds) {
                            // 接收方视角：未销毁 → 闪照按钮
                            var showFlashViewer by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(com.aurora.chat.ui.theme.appTextPrimary())
                                    .combinedClickable(
                                        onClick = { showFlashViewer = true },
                                        onLongClick = { onBubbleLongPress() },
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FlashIcon(iconSize = 32.dp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("闪照", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    Text("点击查看", fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                }
                            }
                            if (showFlashViewer) {
                                FlashViewerDialog(
                                    imageUrl = mediaUrl, durationSec = flashDuration,
                                    onViewStart = { onFlashViewStart(msgId) },
                                    onDestroy = { onFlashDestroy(msgId, mediaUrl) },
                                    onDismiss = { showFlashViewer = false }
                                )
                            }
                        } else if (flashDuration > 0) {
                            // 已销毁/已查看的闪照仍支持长按（长按菜单：复制/删除/撤回等）
                            Box(
                                modifier = Modifier
                                    .width(160.dp).aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(com.aurora.chat.ui.theme.appTextSecondary())
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { onBubbleLongPress() },
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("图片已销毁", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                        } else {
                        // 图片消息：不带气泡框，直接显示
                        MediaImageView(
                            url = mediaUrl,
                            isMine = isMine,
                            loadMedia = loadMedia,
                            highlightAnim = highlightAnim,
                            onImageClick = onImageClick,
                            onBubbleLongPress = onBubbleLongPress,
                            onBoundsMeasured = onBoundsMeasured,
                            mediaWidth = mediaWidth,
                            mediaHeight = mediaHeight,
                            forcedBitmap = exportMediaBitmap
                        )
                        }
                    } else if (mediaType == "video" || mediaType == "mp4") {
                        if (isUploading) {
                            UploadingMediaPreview(
                                previewUri = localPreviewUri,
                                isVideo = true,
                                fallbackRatio = uploadRatio,
                                progress = uploadProgress,
                                stage = uploadStage,
                                onCancelUpload = if (isMine) ({ onCancelUpload(msgId) }) else null
                            )
                        } else {
                        // 视频消息：不带气泡框，显示缩略图（点击应用内播放）
                        // 尺寸与图片消息同一套严格限制（150×200dp 内等比），由 VideoThumbnailView 内部按真实宽高比锁定
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (mediaUrl.isNotEmpty()) onImageClick(mediaUrl) else onBubbleTap()
                                    },
                                    onLongClick = { onBubbleLongPress() },
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                )
                                // 上报视频缩略图矩形：全屏播放器打开/关闭时从这里放大/缩回
                                .onGloballyPositioned { coords ->
                                    if (mediaUrl.isNotEmpty()) {
                                        val p = coords.localToWindow(Offset.Zero)
                                        onBoundsMeasured(
                                            resolveMediaUrl(mediaUrl),
                                            Rect(p.x, p.y, p.x + coords.size.width, p.y + coords.size.height)
                                        )
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(com.aurora.chat.ui.theme.appTextPrimary())
                                    .clip(RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadMedia) VideoThumbnailView(videoUrl = mediaUrl, modifier = Modifier, initialAspectRatio = if (mediaWidth > 0 && mediaHeight > 0) mediaWidth.toFloat() / mediaHeight.toFloat() else 0f)
                                else Box(Modifier.width(150.dp).height(120.dp).background(com.aurora.chat.ui.theme.appTextPrimary().copy(alpha = 0.3f)), contentAlignment = Alignment.Center) { Text("视频", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()) }
                            }
                        }
                        }
                    } else if (mediaType == "voice") {
                        // 语音：独立卡片（秒数借 mediaWidth 承载），自己发和对端收的渲染一致
                        VoiceMessageCard(
                            mediaUrl = mediaUrl,
                            // 秒数优先取 content（跨设备同步），取不到再退回 mediaWidth
                            seconds = text.toIntOrNull() ?: mediaWidth,
                            isUploading = isUploading,
                            uploadProgress = uploadProgress,
                            // 长按冒泡给气泡层，弹出「撤回 / 转发 / 删除」等菜单
                            onLongPress = onBubbleLongPress,
                            // 转文字状态键与长按菜单共用（mediaKey），菜单点击后气泡下方必出结果卡
                            msgId = msgId
                        )
                    } else if (isUploading && mediaType == "file") {
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                        ) {
                            ChatFileUploadCard(
                                fileName = uploadFileName,
                                progress = uploadProgress,
                                stage = uploadStage
                            )
                        }
                    } else if ((mediaUrl.isNotEmpty() && text.isEmpty() && mediaType != "voice" && mediaType != "transfer") || mediaType == "file") {
                        val fileUrl = mediaUrl
                        val fileName = fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "[文件]" }
                        val fileExt = fileName.substringAfterLast('.', "").lowercase()
                        // 诊断：文件卡片渲染决策点，用于定位「会话预览见[文件]但气泡内无卡片」的数据
                        android.util.Log.d("AIFileCard", "render msgId=$msgId mediaType=$mediaType url='$mediaUrl' textLen=${text.length}")
                        ChatFileCard(
                            url = fileUrl,
                            fileName = fileName,
                            ext = fileExt,
                            onClick = { onFileClick(fileUrl, fileName) },
                            onDownload = { onFileDownload(fileUrl, fileName) },
                            onBubbleLongPress = onBubbleLongPress,
                            showBorder = true
                        )
                    } else if (com.aurora.chat.ui.chat.isLocationCardMessage(text)) {
                        val locCtx = androidx.compose.ui.platform.LocalContext.current
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    val loc = com.aurora.chat.ui.chat.parseLocationCard(text)
                                    if (loc != null && loc.lat != 0.0 && loc.lng != 0.0) {
                                        try {
                                            val geoUri = android.net.Uri.parse("geo:${loc.lat},${loc.lng}?q=${loc.lat},${loc.lng}")
                                            val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, geoUri)
                                            locCtx.startActivity(android.content.Intent.createChooser(mapIntent, "选择导航应用"))
                                        } catch (_: Exception) {
                                            android.widget.Toast.makeText(locCtx, "没有可用的导航应用", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onLongClick = { onBubbleLongPress() },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                        ) {
                            com.aurora.chat.ui.chat.LocationCard(
                                text = text
                            )
                        }
                    } else if (com.aurora.chat.ui.chat.isShareCardMessage(text)) {
                        com.aurora.chat.ui.chat.ShareCard(
                            text = text,
                            isMine = false,
                            myBubbleColor = myBubbleColor,
                            otherBubbleColor = otherBubbleColor,
                            onPostClick = onOpenPostDetail,
                            onGroupClick = onOpenGroupDetail,
                            onUserClick = { uid -> onAvatarClick(uid, "") },
                            onFileClick = { fp -> pendingShareDownload = fp },
                            onShortVideoClick = onOpenShortVideo,
                            onChatClick = onOpenChatShare,
                            onBubbleLongPress = onBubbleLongPress
                        )
                    } else if (isPrivacyRequestCard(text) || isPrivacyResponseCard(text)) {
                        PrivacyCard(text = text, isMine = false)
                    } else if (com.aurora.chat.ui.chat.isGomokuInviteCard(text)) {
                        com.aurora.chat.ui.chat.GomokuInviteCard(
                            text = text,
                            isMine = false,
                            onBubbleLongPress = onBubbleLongPress
                        )
                    } else if (isInviteGroupCard(text)) {
                        InviteGroupCard(text = text, isMine = false, onRespondClick = onRespondGroupInvite, onBubbleLongPress = onBubbleLongPress)
                    } else if (isClearChatRequestCard(text) || isClearChatRejectCard(text)) {
                        ClearChatCard(text = text, isMine = false, onBubbleLongPress = onBubbleLongPress)
                    } else {
                        // 对方消息的文本气泡（配色随 BubbleColorStorage 的 otherBubbleColor，仅本地视角）
                        Box(
                            modifier = Modifier
                                // 【气泡必须与头像等高】这一行是 Row(Top 对齐)：气泡最小高度锁成头像高度
                                // （avatarSize=38dp），单行文字在气泡内垂直居中，两者上下边永远重合；
                                // 且无论以后调字号/调头像，都不会再错位。
                                .widthIn(max = bubbleMaxWidth).heightIn(min = avatarSize).wrapContentHeight()
                                // 气泡背景：普通色=实底；「液态」哨兵色=玻璃材质（bubbleBackground 内部分流）
                                // 液态走胶囊形：折射轮廓必须贴合气泡本身，圆角矩形折射会在短气泡两端露出未折射的白边
                                // 圆角跟随「消息设置 → 气泡设置 → 圆角」：默认 8dp、经典 12dp、也可自定义
                                .bubbleBackground(
                                    otherBubbleColor,
                                    corner = bubbleCornerRadius(),
                                    capsule = BubbleColorStorage.isLiquid(otherBubbleColor),
                                    // 「气泡设置 → 气泡凸起」：对方发的消息尖角朝左
                                    tail = if (bubbleTailEnabled() && !BubbleColorStorage.isLiquid(otherBubbleColor))
                                        BubbleTailSide.LEFT else BubbleTailSide.NONE
                                )
                                // 整个气泡（含无内容的空白/边缘）都可点击/长按（同自己消息气泡）
                                .combinedClickable(
                                    enabled = !selectable,
                                    onClick = { onBubbleTap() },
                                    onLongClick = { onBubbleLongPress() },
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                )
                                // 左右内边距 12 → 10dp：同样字数气泡左右少占 4dp（用户对比 QQ 反馈偏宽）；
                                // 上下仍是 8dp，保证单行气泡高度 21+16=37dp。
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .then(if (highlightAnim.value > 0.01f) Modifier.drawWithContent {
                                    drawContent()
                                    drawRect(Color(0xFFBFDBFE).copy(alpha = highlightAnim.value), size = size)
                                } else Modifier),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            QuoteBubbleContent(
                                hasQuote = replyToId > 0L,
                                maxWidth = bubbleMaxWidth,
                                minFloor = QuoteCapsuleMinWidth,
                                capsuleColor = BubbleColorStorage.bubbleCapsuleOverlay(otherBubbleColor),
                                onCapsuleClick = { onCapsuleClick(replyToId) },
                                onCapsuleLongPress = { onBubbleLongPress() },
                                quoteContent = {
                                    QuoteCapsuleInner(
                                        replyIsRevoked = replyIsRevoked,
                                        replyIsMine = replyIsMine,
                                        replyToSender = replyToSender,
                                        replyTimeMs = replyTimeMs,
                                        replyMediaType = replyMediaType,
                                        replyMediaUrl = replyMediaUrl,
                                        replyToText = replyToText,
                                        replyVoiceSeconds = replyVoiceSeconds,
                                        flashDuration = flashDuration,
                                        textColor = otherBubbleCapsuleText,
                                        subColor = otherBubbleCapsuleSub,
                                        onFileClick = onFileClick,
                                        onBoundsMeasured = onBoundsMeasured
                                    )
                                },
                                bodyContent = {
                                    // 纯文本气泡：不再解析表格 / 代码块 / Markdown / 卡片，
                                    // 与己方气泡对称；历史消息里的内联哨兵先清洗掉再显示。
                                    UrlText(
                                        text = remember(text) { messageEditableBody(text) },
                                        fontSize = 15.sp,
                                        defaultColor = otherBubbleText,
                                        urlColor = otherBubbleLink,
                                        lineHeight = 21.sp,
                                        selectable = selectable,
                                        clickable = true,
                                        onClick = { if (!selectable) onBubbleTap() },
                                        onLongPress = { if (!selectable) onBubbleLongPress() },
                                        onAtMentionClick = onAtMentionClick,
                                        isGroup = isGroup,
                                        groupMemberNames = groupMemberNames
                                    )
                                    // 链接预览卡片：正文里含链接时自动出现（解析不到标题就不渲染）；
                                    // 选择模式下只做文字选择，不弹卡片，避免选中时布局跳动
                                    if (!selectable) {
                                        remember(text) { messageEditableBody(text).firstUrl() }?.let { link ->
                                            LinkPreviewCard(
                                                url = link,
                                                titleColor = otherBubbleText,
                                                dividerColor = otherBubbleText.copy(alpha = 0.18f)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                }
            }
        }
    }
    }
}

/**
 * 「Token 明细」卡片的弹出/收起动画。
 *
 * 与旧实现（AnimatedVisibility + expandVertically）的关键区别 —— 顺滑与性能：
 *  - 旧方案每帧都在改「高度」，会触发 measure/layout（重新测量+重新布局），既不够丝滑也更费性能；
 *  - 新方案用一条弹簧驱动的进度 0→1 统一驱动 缩放/位移/透明度，并且全部写进 [Modifier.graphicsLayer]：
 *    只提交渲染层变换（GPU 合成），不重新测量、不重新布局；
 *    同时在 graphicsLayer 的 lambda 内部读取 `progress.value`，把状态读取「延迟到绘制阶段」，
 *    因此逐帧动画期间不会触发任何重组（recomposition）—— 这是 Compose 官方推荐的高性能动画写法。
 *  - spring 的轻微过冲（约 9%）带来「弹」的灵动感；transformOrigin 锚在左下角（即按钮上沿），
 *    视觉上就是从按钮处「长」出来、再轻轻回落。
 */
@Composable
private fun TokenAnimatedDetailCard(
    visible: Boolean,
    inputTokens: Long,
    outputTokens: Long,
    cacheHitTokens: Long,
    contextLength: Long,
    reasoningText: String,
    aiCostDeducted: Long,
    aiMode: String,
    /** 该条回复是否是被手动停止的（中断记录）：上游未回报 usage 时数值含估算，卡片据此标注 */
    interrupted: Boolean = false,
    /** 中断数值的来源：true=服务端结算真值（权威），false=客户端估算 */
    authoritative: Boolean = false
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            // 进入：快起手 + 轻微回弹（灵动但不浮夸）
            progress.animateTo(
                1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 650f, visibilityThreshold = 0.001f)
            )
        } else {
            // 退出：短促干脆，干脆收回、不做回弹
            progress.animateTo(0f, animationSpec = tween(durationMillis = 130, easing = FastOutLinearInEasing))
        }
    }
    Box(
        Modifier.graphicsLayer {
            val v = progress.value                  // 在绘制阶段读取 → 逐帧动画不触发重组
            alpha = v.coerceIn(0f, 1f)
            val s = 0.86f + 0.14f * v               // 0.86 → 1.0（弹簧会带来极轻微过冲）
            scaleX = s
            scaleY = s
            translationY = (1f - v) * 16.dp.toPx()  // 自下而上「浮」出
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f) // 锚在左下角（按钮上沿）
        }
    ) {
        TokenUsageDetailCard(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheHitTokens = cacheHitTokens,
            contextLength = contextLength,
            reasoningText = reasoningText,
            aiCostDeducted = aiCostDeducted,
            aiMode = aiMode,
            interrupted = interrupted,
            authoritative = authoritative
        )
    }
}

// ===== Token 消耗明细 Inline 卡片 =====
private val TokenBlue = Color(0xFF3B82F6)
private val TokenGreen = Color(0xFF10B981)
private val TokenRed = Color(0xFFEF4444)
private val TokenYellow = Color(0xFFF59E0B)
private val TokenPurple = Color(0xFF8B5CF6)

/**
 * 在消息底部原位展开的 Token 消耗明细卡片；白色背景，非弹窗。
 *
 * 不使用投影：elevation 投影会随缩放动画被逐帧重新栅格化，边缘容易闪；
 * 卡片靠 1dp 浅灰描边与白底和背景拉开层次，效果更干净也更省性能。
 */
@Composable
private fun TokenUsageDetailCard(
    inputTokens: Long,
    outputTokens: Long,
    cacheHitTokens: Long,
    contextLength: Long,
    reasoningText: String,
    aiCostDeducted: Long,
    aiMode: String,
    /** 该条回复是否是被手动停止的（中断记录）：上游来不及回报 usage 时数值含估算，这里必须标注出来 */
    interrupted: Boolean = false,
    /** 中断数值的来源：true=服务端结算真值（权威），false=客户端估算 */
    authoritative: Boolean = false
) {
    val total = (inputTokens + outputTokens).coerceAtLeast(0L)
    val cacheMiss = (inputTokens - cacheHitTokens).coerceAtLeast(0L)
    val cacheWrite = 0L
    val reasoningTokens = if (reasoningText.isNotBlank()) AiChatManager.estimateTokens(reasoningText).toLong() else 0L
    val contentTokens = (outputTokens - reasoningTokens).coerceAtLeast(0L)
    val hitRate = if (inputTokens > 0L) (cacheHitTokens * 100 / inputTokens).toInt() else 0
    // 中断记录里，只有「非权威」时数字才是估算的；来自服务端结算时要照样当权威值展示。
    // 缓存命中率在估算口径下必然是 0%（估算器根本不产出命中数），此时展示 0% 属于误导 → 直接隐藏。
    val valuesEstimated = interrupted && !authoritative
    val showCacheStats = inputTokens > 0L && !valuesEstimated
    // 「无消耗」仅适用于真正的免费共享接口(free_api)：平台与用户钱包都不计费、也不计入任何额度。
    // 其余模式——个人 API(personal,走用户自己的 key)、官方(third_party,计每日额度)、
    // 付费 API(paid_api,后端按单价扣平台 token)——都应展示真实 token 消耗,且总计一律=输入+输出之和,
    // 不做任何倍率换算(倍率只影响平台记账用的 aiCostDeducted,不在这里展示)。
    val isFreeMode = aiMode == "free_api"

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(com.aurora.chat.ui.theme.appCardSurface())
            .border(1.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        // 标题 / 总计
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Token 消耗明细", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.weight(1f))
            val totalText = if (isFreeMode) "无消耗" else formatTokenCount(total)
            val totalColor = if (isFreeMode) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextPrimary()
            Text("总计 ${totalText}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = totalColor)
        }
        Spacer(Modifier.height(12.dp))

        // 输入（估算口径加 ≈，明示这不是权威值）
        TokenUsageHeaderRow(color = TokenBlue, label = if (valuesEstimated) "输入 ≈" else "输入", value = inputTokens)
        Spacer(Modifier.height(6.dp))
        TokenUsageSubRow(color = TokenGreen, label = "缓存命中", value = cacheHitTokens, indent = 16)
        if (cacheMiss > 0L) {
            TokenUsageSubRow(color = TokenRed, label = "缓存未命中", value = cacheMiss, indent = 16)
        }
        if (cacheWrite > 0L) {
            TokenUsageSubRow(color = TokenYellow, label = "缓存写入", value = cacheWrite, indent = 16)
        }

        // 分隔线
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
        Spacer(Modifier.height(10.dp))

        // 输出（估算口径加 ≈）
        TokenUsageHeaderRow(color = TokenPurple, label = if (valuesEstimated) "输出 ≈" else "输出", value = outputTokens)
        if (reasoningTokens > 0L) {
            Spacer(Modifier.height(6.dp))
            TokenUsageSubRow(color = null, label = "思考过程", value = reasoningTokens, indent = 16)
        }
        if (contentTokens > 0L) {
            Spacer(Modifier.height(6.dp))
            TokenUsageSubRow(color = null, label = "回复内容", value = contentTokens, indent = 16)
        }

        // 缓存命中率 + 进度条（估算口径下没有命中数，展示 0% 是误导 → 隐藏整段）
        if (showCacheStats) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("缓存命中率", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.weight(1f))
                Text("${hitRate}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TokenGreen)
            }
            Spacer(Modifier.height(6.dp))
            val totalWeight = cacheHitTokens + cacheMiss + cacheWrite
            val hitPct = if (totalWeight > 0L) (cacheHitTokens * 100 / totalWeight).toInt() else 0
            val writePct = if (totalWeight > 0L) (cacheWrite * 100 / totalWeight).toInt() else 0
            val missPct = if (totalWeight > 0L) (cacheMiss * 100 / totalWeight).toInt() else 0
            if (totalWeight > 0L) {
                // 细圆角分段条：高 8dp、全圆角（圆润、细），占比数字改由图例以彩色数字呈现，避免条形过粗
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    if (cacheHitTokens > 0L) {
                        Box(Modifier.weight(cacheHitTokens.toFloat()).fillMaxHeight().background(TokenGreen))
                    }
                    if (cacheWrite > 0L) {
                        Box(Modifier.weight(cacheWrite.toFloat()).fillMaxHeight().background(TokenYellow))
                    }
                    if (cacheMiss > 0L) {
                        Box(Modifier.weight(cacheMiss.toFloat()).fillMaxHeight().background(TokenRed))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 图例：命中 / 写入 / 未命中（各带占比百分比，与分段条同口径）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TokenUsageLegend(color = TokenGreen, label = "命中", percent = hitPct)
                TokenUsageLegend(color = TokenYellow, label = "写入", percent = writePct)
                TokenUsageLegend(color = TokenRed, label = "未命中", percent = missPct)
            }
        }

        // 上下文长度（保持旧能力，不丢失）
        if (contextLength > 0L) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上下文长度", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.weight(1f))
                Text(formatTokenCount(contextLength), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
            }
        }
    }
}

@Composable
private fun TokenUsageHeaderRow(color: Color, label: String, value: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.weight(1f))
        Text(formatTokenCount(value), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
    }
}

@Composable
private fun TokenUsageSubRow(
    color: Color?,
    label: String,
    value: Long,
    indent: Int = 0
) {
    Row(
        modifier = if (indent > 0) Modifier.padding(start = indent.dp) else Modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (color != null) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.5.dp)).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.weight(1f))
        Text(formatTokenCount(value), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
    }
}

@Composable
private fun TokenUsageLegend(color: Color, label: String, percent: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        if (percent != null) {
            Spacer(Modifier.width(3.dp))
            // 占比用分段同色数字呈现（可视化的百分比，细而清晰）
            Text("$percent%", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

private fun formatTokenCount(count: Long): String =
    if (count >= 0L) String.format("%,d", count) else "0"

/**
 * 语音消息卡片。
 *
 * - 尺寸：宽度随秒数线性增长，96dp 起、190dp 封顶，不会因为一条 60 秒的语音就铺满整行。
 * - 内容：左侧播放/暂停三角、中间时长「N″」、右侧一条随长度变长的进度暗示条。
 * - 播放：点击从服务器（或本地 file://）流式播放；同一气泡同一时刻只持有一个 MediaPlayer，
 *   离开组合时自动释放，避免退出会话后后台还在响。
 * - 上传中：只显示百分比，不响应点击（此时 mediaUrl 还是空的）。
 */
@Composable
private fun VoiceMessageCard(
    mediaUrl: String,
    seconds: Int,
    isUploading: Boolean,
    uploadProgress: Float,
    onLongPress: () -> Unit = {},
    msgId: Long = 0L
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var playing by remember { mutableStateOf(false) }
    val playerRef = remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    fun stopPlayback() {
        val p = playerRef.value ?: return
        playerRef.value = null
        playing = false
        runCatching { if (p.isPlaying) p.stop() }
        runCatching { p.release() }
    }

    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { stopPlayback() } }

    val secs = if (seconds > 0) seconds else 0
    // 紧凑一些：96dp 起、最多 148dp，够放下「三角 + 秒数」，不会显得空
    val cardWidth = (96 + secs.coerceAtMost(26) * 2).dp.coerceAtMost(148.dp)

    // ── 语音转文字（长按菜单「转文字」的结果卡片，渲染在语音气泡下方）──
    // 键与菜单层共用 mediaKey：菜单点击后这里必然能接到同一条状态。
    val transcribeKey = remember(msgId, mediaUrl) { VoiceTranscribeStore.mediaKey(msgId, mediaUrl) }
    val transcribeEntry = VoiceTranscribeStore.states.collectAsState().value[transcribeKey]
    // 首次组合时把磁盘上的历史识别结果预热进内存态（重进会话/重启后直接显示，不再转一次）
    androidx.compose.runtime.LaunchedEffect(Unit) { VoiceTranscribeStore.warmUp(ctx) }

    Column {
        Row(
            modifier = Modifier
                .width(cardWidth)
                // 语音气泡高度跟随头像：与单行文字气泡一样，上下边与头像对齐。
                .height(ChatAvatarSize)
                .clip(RoundedCornerShape(10.dp))
                .background(com.aurora.chat.ui.theme.appCardSurface())
                .border(1.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(10.dp))
                // 必须用 combinedClickable：普通的 clickable 会把 down 事件自己吃掉，
                // 父级气泡的 combinedClickable 收不到长按，语音消息因此弹不出菜单（撤不回来）。
                .combinedClickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onLongClick = onLongPress,
                    onClick = {
                    if (isUploading || mediaUrl.isEmpty()) return@combinedClickable
                    if (playing) {
                        stopPlayback()
                    } else {
                        val url = resolveMediaUrl(mediaUrl)
                        runCatching {
                            val mp = android.media.MediaPlayer()
                            mp.setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            mp.setDataSource(url)
                            mp.setOnPreparedListener {
                                it.start()
                                playing = true
                            }
                            mp.setOnCompletionListener {
                                playing = false
                                if (playerRef.value === it) playerRef.value = null
                                runCatching { it.release() }
                            }
                            mp.prepareAsync()
                            playerRef.value = mp
                        }.onFailure {
                            android.widget.Toast.makeText(ctx, "播放失败", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    }
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isUploading) {
                // 语音只有几十 KB，真实上传常常一两帧就结束：直接渲染原始值永远只看到 0%。
                // 用动画把百分比推上去，至少能看清"在涨"；配合屏幕层替换前的短延迟，能看到 100%。
                val shownPct by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = uploadProgress.coerceIn(0f, 1f),
                    animationSpec = androidx.compose.animation.core.tween(320),
                    label = "voiceUploadPct"
                )
                Text(
                    "${(shownPct * 100).toInt()}%",
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextSecondary()
                )
            } else {
                Canvas(modifier = Modifier.size(13.dp)) {
                    if (playing) {
                        val bw = size.width * 0.24f
                        val r = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
                        drawRoundRect(Color(0xFF1E40AF), size = androidx.compose.ui.geometry.Size(bw, size.height), cornerRadius = r)
                        drawRoundRect(
                            Color(0xFF1E40AF),
                            topLeft = Offset(size.width * 0.56f, 0f),
                            size = androidx.compose.ui.geometry.Size(bw, size.height),
                            cornerRadius = r
                        )
                    } else {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.16f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(size.width * 0.16f, size.height)
                            close()
                        }
                        drawPath(p, Color(0xFF1E40AF))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("$secs″", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
            }
        }
        // 识别卡片：上传中的语音没有可识别的远端文件，暂不显示
        if (!isUploading) {
            val entry = transcribeEntry
            if (entry != null) {
                Spacer(Modifier.height(6.dp))
                VoiceTranscribeCard(
                    entry = entry,
                    onCancel = { VoiceTranscribeStore.cancel(transcribeKey) },
                    onRetry = { VoiceTranscribeStore.start(ctx, transcribeKey, secs) },
                    onCopy = { text ->
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("语音转文字", text)
                        )
                        android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

/**
 * 语音「转文字」结果卡片（微信/QQ 风格：白底圆角小卡，贴在语音气泡正下方）。
 *
 *  · RUNNING：转圈 + 「识别中…」+ 取消按钮；有局部识别结果时顺带显示灰色中间文字；
 *  · DONE：识别文本（可多行）+ 底部「时长 · 复制」；
 *  · FAILED：红色错误文案 + 「重试」。
 */
@Composable
private fun VoiceTranscribeCard(
    entry: VoiceTranscribeStore.Entry,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(com.aurora.chat.ui.theme.appCardSurface())
            .border(1.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        when (entry.state) {
            VoiceTranscribeStore.State.RUNNING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color(0xFF1E40AF),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("识别中…", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.weight(1f))
                    Text(
                        "取消",
                        fontSize = 12.sp,
                        color = Color(0xFF1E40AF),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onCancel() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                val partial = entry.text
                if (partial.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        partial,
                        fontSize = 14.sp,
                        color = com.aurora.chat.ui.theme.appTextHint(),
                        lineHeight = 20.sp
                    )
                }
            }
            VoiceTranscribeStore.State.DONE -> {
                Text(
                    entry.text,
                    fontSize = 14.sp,
                    color = Color(0xFF111827),
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${entry.seconds}″ 语音", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Spacer(Modifier.weight(1f))
                    Text(
                        "复制",
                        fontSize = 12.sp,
                        color = Color(0xFF1E40AF),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onCopy(entry.text) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            VoiceTranscribeStore.State.FAILED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.error,
                        fontSize = 12.sp,
                        color = Color(0xFFF04438),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "重试",
                        fontSize = 12.sp,
                        color = Color(0xFF1E40AF),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { onRetry() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * 群聊昵称前的角色徽章。
 *
 * 群主：金色填充 + 描边（就是"外面那道分割线"），深棕字；
 * 群管理：浅蓝填充 + 同色系描边，深蓝字。
 *
 * 字号与昵称严格一致（11sp），内边距压到最小，保证它看起来是"和名字一样大的一个小牌子"，
 * 而不是一颗按钮：没有点击、没有水波纹、没有阴影。
 */
@Composable
private fun GroupRoleBadge(role: String) {
    val isOwner = role == "owner"
    val isAdmin = role == "admin"
    if (!isOwner && !isAdmin) return
    val label = if (isOwner) "群主" else "群管理"
    // 群主金色：旧版是纯色底 #F6C445（偏浅、发土黄，被反馈"不够金"）。
    // 旧版三色记录，若不满意按这组原样回退：底 #F6C445 / 描边 #D9A521 / 字 #6B4A00
    // 新版：亮金→深金的金属渐变底，描边压深到 #BE8A00，字色压深到 #5A3A00，更像真金而非米黄。
    val ownerBrush = Brush.linearGradient(listOf(Color(0xFFFFD95A), Color(0xFFEFAF00)))
    val fg = if (isOwner) Color(0xFF5A3A00) else Color(0xFF2563EB)
    val line = if (isOwner) Color(0xFFBE8A00) else Color(0xFFBBD6F2)
    val badgeShape = RoundedCornerShape(3.dp)
    Text(
        text = label,
        fontSize = 11.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(badgeShape)
            .then(
                if (isOwner) Modifier.background(ownerBrush, badgeShape)
                else Modifier.background(Color(0xFFDCEBFB), badgeShape)
            )
            .border(0.5.dp, line, badgeShape)
            .padding(horizontal = 3.dp, vertical = 0.dp)
    )
}

/**
 * 表情消息渲染：无气泡、120dp 正方形、ContentScale.Fit（不裁不变形）。
 * media_url 协议：sticker://<分组>/<文件名> → assets/stickers/<分组>/<文件名>.webp。
 * 动图（动态 WebP）由 Coil 在 Android 9+ 上自动逐帧播放，无需区分静态/动态。
 */
@Composable
private fun StickerMessageView(mediaUrl: String, onBubbleLongPress: () -> Unit) {
    val rel = mediaUrl.removePrefix("sticker://").trim().trim('/')
    if (rel.isEmpty()) {
        // 协议异常兜底：绝不渲染成空白静默消息
        Text(
            text = "[表情]",
            fontSize = 14.sp,
            color = com.aurora.chat.ui.theme.appTextHint(),
            modifier = Modifier.padding(8.dp)
        )
        return
    }
    // 动图必须显式指定 ImageDecoderDecoder：裸字符串 model 时 Coil 对 asset 路径拿不到
    // MIME，会退回 BitmapFactory 只解第一帧 → 动图表情变静图。API 28+ 逐帧播放。
    val ctx = LocalContext.current
    val stickerReq = remember(rel) {
        coil.request.ImageRequest.Builder(ctx)
            .data("file:///android_asset/stickers/$rel.webp")
            .decoderFactory(
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    coil.decode.ImageDecoderDecoder.Factory()
                else
                    coil.decode.BitmapFactoryDecoder.Factory()
            )
            .build()
    }
    // 按素材真实长宽比定尺寸（最长边 120dp），保证每条表情的圆角裁剪都落在图像本身上。
    // 之前固定 120dp 正方形 + ContentScale.Fit：只有接近 1:1 的图才顶到四角、看得见圆角，
    // 宽图/窄图四周留白，圆角视觉上就"消失"了 —— 于是出现"有的圆角有的没有"。
    //
    // 这一步同时兼作「资源是否还在包内」的探测：历史消息可能引用已经下架的表情
    // （例如 sticker://gif/gif001），此时 assets.open 抛异常。以前静默回落到 1f，
    // Coil 加载失败后 AsyncImage 什么都不画，外层 Box 仍占 120dp —— 用户看到的就是一块空白。
    // 现在明确分三态：null=检查中（沿用原图渲染，避免闪烁）、true=存在(带比例)、false=缺失。
    // 以后若接入服务端历史归档回源，只需把 missing 分支换成回源 URL，其余逻辑不用动。
    val assetState = androidx.compose.runtime.produceState<Pair<Boolean, Float>?>(initialValue = null, rel) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.assets.open("stickers/$rel.webp").use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    true to (opts.outWidth.toFloat() / opts.outHeight)
                } else false to 1f
            }.getOrDefault(false to 1f)
        }
    }
    // 只有明确探测到缺失才走兜底；检查中仍按正常图片渲染，与改动前行为一致。
    val missing = assetState.value?.first == false
    val ratio = (assetState.value?.second ?: 1f).coerceIn(0.5f, 2.5f)
    val boxW = if (missing) 88.dp else if (ratio >= 1f) 120.dp else (120f * ratio).dp
    val boxH = if (missing) 40.dp else if (ratio >= 1f) (120f / ratio).dp else 120.dp
    Box(
        modifier = Modifier
            .size(width = boxW, height = boxH)
            .clip(RoundedCornerShape(10.dp))
            .then(if (missing) Modifier.background(com.aurora.chat.ui.theme.appCardBg()) else Modifier)
            .combinedClickable(
                onClick = { /* 点击不进大图：assets 图无查看器链路，留空防止误触 */
                },
                onLongClick = onBubbleLongPress,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (missing) {
            // 发送时 content 存的就是 "[表情]"，兜底文案与会话预览 / 引用 / 推送保持一致。
            Text(
                text = "[表情]",
                fontSize = 13.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                maxLines = 1
            )
        } else {
            AsyncImage(
                model = stickerReq,
                contentDescription = "表情",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * emoji 消息渲染：无气泡、大字号 Text（64sp）渲染 emoji 字符（media_url 直接存字符本身），
 * 视觉上等同大号图片；长按仍出菜单（删除/撤回等），点击不进大图（emoji 无查看器链路）。
 */
@Composable
private fun EmojiMessageView(emoji: String, onBubbleLongPress: () -> Unit) {
    Box(
        modifier = Modifier
            .combinedClickable(
                onClick = { /* 点击不进大图：emoji 无查看器链路，留空防止误触 */ },
                onLongClick = onBubbleLongPress,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji.ifBlank { "😀" },
            fontSize = 64.sp
        )
    }
}

/**
 * 引用胶囊里的 emoji 缩略：直接显示 emoji 字符本身，不再落成 "FILE" 文件占位。
 * 无底色、无描边、无圆角方块——40dp 只是维持胶囊内缩略区的占位高度，背景完全透明，
 * 让 emoji 像正文里一样直接浮在引用胶囊上。
 */
@Composable
private fun QuoteEmojiThumb(mediaUrl: String) {
    Box(
        Modifier.size(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(mediaUrl.ifBlank { "😀" }, fontSize = 24.sp, maxLines = 1)
    }
}

/**
 * 判定 mediaUrl 是否为 emoji 字符（emoji 消息的 mediaUrl 直接存字符本身，无扩展名、无协议）。
 * 命中条件：不含路径/点/协议特征，且包含至少一个 Emoji 区码点（U+1F000..U+1FAFF、U+2600..U+27BF、
 * U+2B00..U+2BFF 或变体选择符 FE0F / ZWJ 200D），且 emoji 码点占比过半——避免把普通文本误判成 emoji。
 */
fun isEmojiMediaUrl(url: String): Boolean {
    if (url.isEmpty() || url.length > 16) return false
    if (url.contains('/') || url.contains('\\') || url.contains('.') || url.contains(':') || url.contains("http")) return false
    var emojiChars = 0
    var total = 0
    var i = 0
    while (i < url.length) {
        val cp = url.codePointAt(i)
        total++
        if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0xFE0F || cp == 0x200D) emojiChars++
        i += Character.charCount(cp)
    }
    return emojiChars > 0 && emojiChars * 2 >= total
}

