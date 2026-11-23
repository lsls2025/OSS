package com.aurora.chat.ui.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.aurora.chat.data.link.LinkPreviewLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 链接预览卡片（仿 QQ / 微信）。
 *
 * 结构：正文下方一条分割线，下面是「图标 + 标题」一行，整块可点（仍走应用内的链接打开选择器）。
 * 解析不到标题时整张卡片不渲染 —— 不占空间、不留空壳，和 QQ 的行为一致。
 *
 * @param url 消息正文里解析出的第一个链接（已补全 scheme）
 * @param titleColor 标题颜色（跟随气泡明暗）
 * @param dividerColor 分割线颜色（跟随气泡明暗）
 */
@Composable
fun LinkPreviewCard(
    url: String,
    titleColor: Color,
    dividerColor: Color,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    // 先吃内存缓存：滚动回来的卡片当帧就有内容，不会闪一下再出现
    var info by remember(url) { mutableStateOf(LinkPreviewLoader.peek(url)) }
    var showChooser by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (info == null) {
            info = withContext(Dispatchers.IO) { LinkPreviewLoader.load(ctx, url) }
        }
    }

    val data = info ?: return

    // 宽度上限 240dp：短消息不会因为挂了一张卡片就被撑成整屏宽（QQ/微信同样不会）
    Column(modifier.widthIn(max = 240.dp).fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        // 正文与卡片之间的分割线
        HorizontalDivider(thickness = 0.7.dp, color = dividerColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showChooser = true }
                .padding(top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标：**加载成功后才占位**（用户定规：默认不留空间，有图标才把标题往右推）。
            // 旧实现先按 hasIcon 占 18dp+8dp，等 AsyncImage 报错再缩回——就是“刚出现时抽搐一下再缩回去”。
            // 现在只用 painter 在后台预加载，不参与布局；拿到 Success 后才把 Image 插进来。
            var iconLoaded by remember(data.iconUrl) { mutableStateOf(false) }
            var iconFailed by remember(data.iconUrl) { mutableStateOf(false) }
            if (data.hasIcon && !iconFailed) {
                val iconPainter = coil.compose.rememberAsyncImagePainter(model = data.iconUrl)
                LaunchedEffect(iconPainter.state) {
                    when (val s = iconPainter.state) {
                        is AsyncImagePainter.State.Success -> iconLoaded = true
                        is AsyncImagePainter.State.Error -> iconFailed = true
                        else -> Unit
                    }
                }
                if (iconLoaded) {
                    Image(
                        painter = iconPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(
                text = data.title,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showChooser) {
        LinkOpenChooserDialog(url = url, onDismiss = { showChooser = false })
    }
}
