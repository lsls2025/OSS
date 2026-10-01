package com.aurora.chat.ui.chat.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.aurora.chat.ui.chat.resolveMediaUrl
import androidx.compose.animation.core.Animatable
import android.util.Log

/**
 * 将聊天原图 URL 转换为服务端压缩预览图 URL（最长边 1080、质量 85）。
 *
 * 仅对指向 /chat-media/ 的 http(s) 地址生效；本地文件（file://）与无法识别的地址原样返回。
 * 点开大图时仍使用原图 URL，因此这里只影响气泡内的默认显示，用于降低服务器带宽压力。
 */
internal fun chatMediaThumbUrl(resolved: String): String {
    if (!resolved.startsWith("http")) return resolved
    if (resolved.contains("/api/chat-media/thumb/")) return resolved
    val marker = "/chat-media/"
    val idx = resolved.indexOf(marker)
    if (idx < 0) return resolved
    val base = resolved.substring(0, idx)
    val file = resolved.substring(idx + marker.length)
    return "$base/api/chat-media/thumb/$file?w=1080&q=85"
}

/**
 * 重写后的图片渲染组件（取代旧 MediaImageView）。
 *
 * 确保不出现"空气泡"（图片空白无法显示）。
 * - URL 为空 → 显示明确的"图片已失效"卡片；
 * - 加载中 → 灰色骨架占位；
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
    onBubbleLongPress: () -> Unit
) {
    val ctx = LocalContext.current
    val resolved = resolveMediaUrl(url)

    if (resolved.isEmpty()) {
        Box(
            Modifier.widthIn(max = 200.dp).heightIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF374151)),
            contentAlignment = Alignment.Center
        ) {
            Text("图片已失效", fontSize = 13.sp, color = Color.White)
        }
        return
    }

    if (!loadMedia) {
        Box(
            Modifier.widthIn(max = 200.dp).heightIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF374151).copy(alpha = 0.15f))
        )
        return
    }

    // 气泡内默认加载服务端压缩预览图以节省带宽；
    // 若压缩接口不可用（后端未部署）则自动回退为原图，保证可用。
    var useOriginal by remember(resolved) { mutableStateOf(false) }
    var retryNonce by remember { mutableStateOf(0) }
    val displayUrl = if (useOriginal) resolved else chatMediaThumbUrl(resolved)
    val model = remember(displayUrl, retryNonce) {
        ImageRequest.Builder(ctx).data(displayUrl).build()
    }
    val painter = rememberAsyncImagePainter(model)
    val state = painter.state

    LaunchedEffect(state) {
        if (state is AsyncImagePainter.State.Error && !useOriginal) {
            useOriginal = true
        }
    }

    // 顶级适配：限制最大显示宽高并保持原始比例完整显示，
    // 避免高/宽截图（如 16:9、竖屏截图）占据过大的上下空间。
    val maxW = 200.dp
    val maxH = 200.dp
    Box(
        Modifier.widthIn(max = maxW).heightIn(max = maxH)
            .clip(RoundedCornerShape(12.dp))
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
    ) {
        Image(
            painter = painter,
            contentDescription = "图片消息",
            contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = maxW).heightIn(max = maxH)
        )

        when (state) {
            is AsyncImagePainter.State.Loading -> {
                // 加载占位：固定高度，防止快滑时塌缩造成抽搐
                Box(
                    Modifier.fillMaxWidth().height(180.dp)
                        .background(Color(0xFF374151).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("加载中…", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                }
            }
            is AsyncImagePainter.State.Error -> {
                Log.e("ChatMediaImage", "图片加载失败 url=$resolved err=${state.result.throwable?.message}")
                Box(
                    Modifier.fillMaxWidth().height(180.dp)
                        .background(Color(0xAA374151)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("图片加载失败", fontSize = 13.sp, color = Color.White)
                        Text(
                            "点击重试",
                            fontSize = 11.sp,
                            color = Color(0xFF9CA3AF),
                            modifier = Modifier.clickable { retryNonce++ }
                        )
                    }
                }
            }
            else -> {}
        }
    }
}
