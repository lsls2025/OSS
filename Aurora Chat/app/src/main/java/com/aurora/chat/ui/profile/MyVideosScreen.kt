package com.aurora.chat.ui.profile
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 我的发布 - 视频：缩略图网格展示当前用户自己发布的短视频。
 *
 * 数据走 /api/shortvideo/feed?mine=1（服务端按 user_id 过滤），不在客户端逐页拉全量再筛。
 * 点缩略图 → 打开短视频播放页（定位到该条），返回后按 refreshKey 重新拉取，
 * 保证在播放页里删掉的视频不会残留在网格里。
 */
@Composable
fun MyVideosScreen(
    onBack: () -> Unit,
    onOpenVideo: (SVItem) -> Unit,
    refreshKey: Int = 0
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageSize = 30
    var items by remember { mutableStateOf<List<SVItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    // 总开关：开发者关闭短视频后，这里也只显示其自定义提示，不展示任何作品
    var gateChecked by remember { mutableStateOf(false) }
    var gateDisabled by remember { mutableStateOf(false) }
    var gateMessage by remember { mutableStateOf("") }

    BackHandler(enabled = true) { onBack() }

    suspend fun loadFirst() {
        loading = true
        loadError = false
        val list = fetchShortVideoFeed(page = 1, limit = pageSize, mine = true)
        loading = false
        if (list == null) {
            loadError = true
        } else {
            items = list
            page = 1
            hasMore = list.size >= pageSize
        }
    }

    LaunchedEffect(refreshKey) {
        // 先问总开关：关闭时不拉列表（服务端也会 403），直接显示开发者配置的提示
        try {
            val r = AuroraApi.getShortVideoGatePublic()
            if (r.success && r.data != null) {
                gateDisabled = r.data!!.optBoolean("disabled", false)
                gateMessage = r.data!!.optString("message", "")
            }
        } catch (_: Exception) { }
        gateChecked = true
        if (!gateDisabled) loadFirst()
    }

    if (gateChecked && gateDisabled) {
        Box(
            modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = gateMessage.ifBlank { "短视频功能已关闭" },
                color = com.aurora.chat.ui.theme.appTextSecondary(),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg())) {
        // 顶栏：返回 + 标题（白色顶栏向上延伸覆盖状态栏区）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.aurora.chat.ui.theme.appSurface())
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = "视频",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF2563EB))
            }

            loadError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Text(
                        "重试",
                        fontSize = 14.sp,
                        color = Color(0xFF2563EB),
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clickable { scope.launch { loadFirst() } }
                    )
                }
            }

            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有发布视频", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                gridItems(items, key = { it.id }) { item ->
                    MyVideoTile(item = item) { onOpenVideo(item) }
                }
                if (hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp)
                                .clickable(enabled = !loadingMore) {
                                    if (!loadingMore) {
                                        loadingMore = true
                                        scope.launch {
                                            val next = page + 1
                                            val list = fetchShortVideoFeed(page = next, limit = pageSize, mine = true)
                                            loadingMore = false
                                            if (list == null) return@launch
                                            items = items + list.filterNot { n -> items.any { it.id == n.id } }
                                            page = next
                                            hasMore = list.size >= pageSize
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF2563EB)
                                )
                            } else {
                                Text("加载更多", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyVideoTile(item: SVItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    // 封面：后端给的是站内相对路径；缺失时回退到 HLS 目录的 cover.jpg（与收藏页同一套兜底）
    val cover = remember(item.id, item.coverUrl) {
        when {
            item.coverUrl.startsWith("http") -> item.coverUrl
            item.coverUrl.isNotBlank() -> AuroraApi.serverUrl + item.coverUrl
            else -> "${AuroraApi.serverUrl}/api/shortvideo/hls/${item.id}/cover.jpg"
        }
    }
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(8.dp))
            .background(com.aurora.chat.ui.theme.appCardBg())
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(cover).crossfade(true).build(),
            contentDescription = item.title.ifBlank { "视频" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 播放标识
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(21.dp)
            )
        }

        // 时长
        if (item.duration > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = formatVideoDuration(item.duration),
                    fontSize = 10.sp,
                    color = Color.White
                )
            }
        }

        // 标题（单行，贴底）
        if (item.title.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = item.title,
                    fontSize = 10.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatVideoDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) {
        String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    } else {
        String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
