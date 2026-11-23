package com.aurora.chat.ui.profile
import com.aurora.chat.ui.theme.LocalAppColors

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.FavoriteCategory
import com.aurora.chat.data.local.FavoriteItem
import com.aurora.chat.data.local.FavoritesStore
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.common.createExoPlayer
import java.io.File

/**
 * 收藏页：全部 / 文本 / 图片 / 视频 / 社区 / 其他 分类栏 + 网格。
 *
 * 数据来源两份：
 *  - [FavoritesStore]：聊天消息（文本/图片/视频/语音/文件）、社区帖子（媒体已复制到本机）
 *  - [SVFavoriteStore]：历史短视频收藏（沿用原存储，避免迁移丢数据）
 */
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenShortVideo: (Long) -> Unit = {},
    /** 外部状态变化时 +1 / 翻转（如从本页进入的短视频关闭后），触发收藏列表重新读取 */
    reloadKey: Int = 0
) {
    val ctx = LocalContext.current
    val rootView = LocalView.current
    DisposableEffect(Unit) {
        val window = ctx.findActivity()?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, rootView) }
        val prevStatusLight = controller?.isAppearanceLightStatusBars
        val prevNavLight = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            controller?.let { c ->
                if (prevStatusLight != null) c.isAppearanceLightStatusBars = prevStatusLight
                if (prevNavLight != null) c.isAppearanceLightNavigationBars = prevNavLight
            }
        }
    }

    var selectedCat by remember { mutableStateOf("all") }
    var viewing by remember { mutableStateOf<FavoriteItem?>(null) }
    // 长按待取消的收藏
    var pendingRemove by remember { mutableStateOf<FavoriteItem?>(null) }
    // 取消收藏后本地刷新用：reloadKey 由外部控制，这里再叠一层内部版本号
    var listVersion by remember { mutableStateOf(0) }
    val items = remember(reloadKey, listVersion) { loadFavorites(ctx.applicationContext) }
    val shown = remember(items, selectedCat) {
        if (selectedCat == "all") items else items.filter { it.category == selectedCat }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appCardBg())
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "收藏",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.aurora.chat.ui.theme.appTextPrimary()
                )
            }

            CategoryBar(selected = selectedCat, onSelect = { selectedCat = it })

            if (shown.isEmpty()) {
                EmptyFavorites(selectedCat)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(6.dp)
                ) {
                    gridItems(shown, key = { it.key }) { item ->
                        FavoriteTile(
                            item = item,
                            onClick = {
                                if (item.sourceType == "shortvideo") {
                                    onOpenShortVideo(item.sourceId)
                                } else {
                                    viewing = item
                                }
                            },
                            // 长按取消收藏
                            onLongClick = { pendingRemove = item }
                        )
                    }
                }
            }
        }

        viewing?.let { item ->
            FavoriteViewer(item) { viewing = null }
        }

        // 长按取消收藏：确认后从本地收藏存储移除并刷新网格
        pendingRemove?.let { target ->
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text("取消收藏", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "确定移除「${target.title.ifBlank { "这条收藏" }}」？",
                        fontSize = 14.sp,
                        color = Color(0xFF4B5563)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        removeFavorite(ctx.applicationContext, target)
                        if (viewing?.key == target.key) viewing = null
                        pendingRemove = null
                        listVersion++
                    }) {
                        Text("移除", color = Color(0xFFDC2626))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemove = null }) {
                        Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            )
        }
    }
}

/** 取消收藏：短视频走原存储，其余走统一收藏存储（会一并删掉本机复制的媒体文件）。 */
private fun removeFavorite(ctx: Context, item: FavoriteItem) {
    if (item.sourceType == "shortvideo") {
        SVFavoriteStore.remove(ctx, item.sourceId)
    } else {
        FavoritesStore.remove(ctx, item.key)
    }
}

// ---------- 分类栏 ----------

@Composable
private fun CategoryBar(selected: String, onSelect: (String) -> Unit) {
    val tabs = remember {
        listOf("all" to "全部") + FavoriteCategory.entries.map { it.id to it.label }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(com.aurora.chat.ui.theme.appSurface())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { (id, label) ->
            val active = id == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) com.aurora.chat.ui.theme.AuroraPrimary else com.aurora.chat.ui.theme.appCardBg())
                    .clickable { onSelect(id) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color.White else Color(0xFF4B5563)
                )
            }
        }
    }
}

// ---------- 网格卡片 ----------

@Composable
private fun FavoriteTile(item: FavoriteItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    // 社区帖子：按帖子卡片展示（标题+正文），不要渲染成图片卡片
    if (item.sourceType == "post" || item.categoryEnum == FavoriteCategory.COMMUNITY) {
        PostFavoriteTile(item, onClick, onLongClick)
        return
    }
    val ctx = LocalContext.current
    val model = remember(item.key) { item.coverModel() }
    val isVideo = item.category == FavoriteCategory.VIDEO.id

    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(10.dp))
            .background(com.aurora.chat.ui.theme.appCardBg())
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (model != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(model).crossfade(true).build(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // 文本 / 语音 / 文件：没有封面，用正文摘要当卡片内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.text.ifBlank { item.title },
                    fontSize = 11.sp,
                    color = Color(0xFF4B5563),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                text = item.title.ifBlank { item.categoryEnum.label },
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------- 社区帖子卡片 ----------

/**
 * 帖子收藏卡片：白底卡片，标题 + 正文摘要 + 右上角「社区」角标。
 * 收藏的是帖子本身，不是帖子里的图片；媒体在点开后的帖子详情查看器里展示。
 */
@Composable
private fun PostFavoriteTile(item: FavoriteItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val hasVideo = item.localFiles.any { isVideoPath(it) && File(it).exists() }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(10.dp))
            .background(com.aurora.chat.ui.theme.appSurface())
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Text(
                text = item.title.ifBlank { "社区帖子" },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.text,
                    fontSize = 10.sp,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    maxLines = 5,
                    lineHeight = 14.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (hasVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 右上角「社区」角标：明确这是帖子收藏
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(50))
                .background(com.aurora.chat.ui.theme.AuroraPrimary)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text("社区", color = Color.White, fontSize = 9.sp)
        }
    }
}

@Composable
private fun EmptyFavorites(category: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = Color(0xFFD1D5DB),
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (category == "all") "还没有收藏" else "该分类还没有收藏",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = com.aurora.chat.ui.theme.appTextSecondary()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "长按聊天消息，或在社区帖子「⋮」菜单里收藏",
                fontSize = 13.sp,
                color = com.aurora.chat.ui.theme.appTextHint()
            )
        }
    }
}

// ---------- 详情查看 ----------

@Composable
private fun FavoriteViewer(item: FavoriteItem, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    // 聊天文本/语音/文件 → 浅色聊天风；图片/视频/帖子 → 沉浸黑底
    val isLight = item.sourceType == "chat" &&
        item.category != FavoriteCategory.IMAGE.id &&
        item.category != FavoriteCategory.VIDEO.id
    val barBg = if (isLight) Color.White else Color.Black.copy(alpha = 0.35f)
    val barFg = if (isLight) com.aurora.chat.ui.theme.appTextPrimary() else Color.White
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isLight) com.aurora.chat.ui.theme.appCardBg() else Color.Black)
    ) {
        when {
            // 聊天记录收藏（bundle）：整段对话按聊天样式还原
            item.bundleJson.isNotBlank() -> ChatBundleViewer(item)
            // 帖子优先：无论分类迁移前后是什么，都走帖子详情查看器
            item.sourceType == "post" -> PostFavoriteViewer(item)
            item.category == FavoriteCategory.IMAGE.id -> ImageFavoriteViewer(item)
            item.category == FavoriteCategory.VIDEO.id -> VideoFavoriteViewer(item)
            else -> OtherFavoriteViewer(item)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(barBg)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = barFg,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = item.title.ifBlank { item.categoryEnum.label },
                color = barFg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
    }
}

@Composable
private fun ImageFavoriteViewer(item: FavoriteItem) {
    val ctx = LocalContext.current
    val images = remember(item.key) {
        buildList {
            item.localFiles.filter { isImagePath(it) && File(it).exists() }.forEach { add(it) }
            if (isEmpty()) item.thumbnail.takeIf { it.isNotBlank() && File(it).exists() }?.let { add(it) }
        }
    }
    if (images.size <= 1) {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(images.firstOrNull()?.let { File(it) } ?: item.remoteUrl)
                .crossfade(true).build(),
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 72.dp, bottom = 24.dp)
        ) {
            listItems(images) { path ->
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(File(path)).crossfade(true).build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun VideoFavoriteViewer(item: FavoriteItem) {
    val path = remember(item.key) { videoPathOf(item) }
    if (path.isBlank()) {
        CenterHint("视频文件已失效")
    } else {
        VideoPlayer(urlOf(path), Modifier.fillMaxSize())
    }
}

@Composable
private fun PostFavoriteViewer(item: FavoriteItem) {
    val ctx = LocalContext.current
    val videos = remember(item.key) { item.localFiles.filter { isVideoPath(it) && File(it).exists() } }
    val images = remember(item.key) { item.localFiles.filter { isImagePath(it) && File(it).exists() } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 96.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = item.title.ifBlank { "社区帖子" },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (item.subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(item.subtitle, color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 12.sp)
            }
        }
        if (item.text.isNotBlank()) {
            item {
                Text(item.text, color = com.aurora.chat.ui.theme.appDivider(), fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
        videos.forEach { path ->
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF111111))
                ) {
                    VideoPlayer(urlOf(path), Modifier.fillMaxSize())
                }
            }
        }
        listItems(images) { path ->
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(File(path)).crossfade(true).build(),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
            )
        }
        if (videos.isEmpty() && images.isEmpty() && item.text.isNotBlank()) {
            item {
                Text(
                    "媒体未保存或已被清理，以上为收藏时保留的文字内容。",
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * 聊天消息查看器（文本/语音/文件）：聊天风格浅色界面。
 * 对方消息 = 头像 + 昵称 + 白气泡；自己的消息 = 品牌深蓝色气泡靠右。
 */
@Composable
private fun OtherFavoriteViewer(item: FavoriteItem) {
    val ctx = LocalContext.current
    var playing by remember(item.key) { mutableStateOf(false) }
    val media = remember(item.key) {
        item.localFiles.firstOrNull { File(it).exists() } ?: item.remoteUrl.takeIf { it.isNotBlank() }
    }
    val mediaFile = remember(item.key) {
        media?.takeIf { !it.startsWith("http") && !it.startsWith("content://") }
            ?.let { File(it) }?.takeIf { it.exists() }
    }
    val isAudio = media != null && isAudioPath(media)
    val isMine = item.subtitle == "我"

    var avatar by remember(item.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.key) {
        avatar = when {
            isMine -> ChatRepository.loadAvatar(ctx, AuroraApi.currentUserId)
            item.senderId > 0 -> ChatRepository.loadAvatar(ctx, item.senderId)
            else -> null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 96.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
        ) {
            if (!isMine) FavoriteAvatar(avatar, item.subtitle)
            Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
            ) {
                Text(
                    text = item.subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF8A94A0),
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = if (isMine) 12.dp else 3.dp,
                                topEnd = if (isMine) 3.dp else 12.dp,
                                bottomEnd = 12.dp,
                                bottomStart = 12.dp
                            )
                        )
                        .background(if (isMine) com.aurora.chat.ui.theme.AuroraPrimary else com.aurora.chat.ui.theme.appCardBg())
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (item.text.isNotBlank()) {
                        Text(
                            text = item.text,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = if (isMine) Color.White else com.aurora.chat.ui.theme.appTextPrimary()
                        )
                    }
                    if (isAudio) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isMine) Color.White.copy(alpha = 0.2f)
                                    else Color(0xFFEAF1FB)
                                )
                                .clickable { playing = !playing }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (playing) Icons.Filled.Close else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = if (isMine) Color.White else com.aurora.chat.ui.theme.AuroraPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (playing) "停止播放" else "播放语音",
                                fontSize = 13.sp,
                                color = if (isMine) Color.White else com.aurora.chat.ui.theme.AuroraPrimary
                            )
                        }
                    } else if (mediaFile != null) {
                        // 文件消息：按类型完整渲染（图片/视频/文本预览/文件卡片）
                        FavoriteFileContent(mediaFile, isMine)
                    } else if (media != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "媒体未保存到本机，仅保留原始地址",
                            fontSize = 12.sp,
                            color = if (isMine) Color.White.copy(alpha = 0.85f) else com.aurora.chat.ui.theme.appTextSecondary()
                        )
                    }
                }
                if (item.createdAt > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formatFavoriteTime(item.createdAt),
                        fontSize = 11.sp,
                        color = Color(0xFFA8B0BA)
                    )
                }
            }
            if (isMine) FavoriteAvatar(avatar, item.subtitle)
        }
        if (item.text.isBlank() && media == null) {
            Text("暂无可展示的内容", color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 14.sp)
        }
        // 兜底：旧数据（如敏感帖降级进「其他」）里已保存的图仍尽量展示
        item.localFiles.filter { isImagePath(it) && File(it).exists() }.forEach { path ->
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(File(path)).crossfade(true).build(),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (playing) {
            media?.let { AudioPlayer(urlOf(it)) }
        }
    }
}

@Composable
private fun FavoriteAvatar(bmp: Bitmap?, name: String) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0xFFDDE5EE)),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.firstOrNull()?.toString() ?: "?",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = com.aurora.chat.ui.theme.appTextSecondary()
            )
        }
    }
}

private fun formatFavoriteTime(ts: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))

// ---------- 聊天记录收藏（bundle）渲染 ----------

private data class BundleMsg(
    val mine: Boolean,
    val name: String,
    val type: String,
    val url: String,
    val lf: String,
    val text: String
)

private fun parseBundle(json: String): List<BundleMsg> = try {
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        BundleMsg(
            mine = o.optInt("m", 0) == 1,
            name = o.optString("n").ifBlank { "对方" },
            type = o.optString("t", "text").ifBlank { "text" },
            url = o.optString("u"),
            lf = o.optString("lf"),
            text = o.optString("x")
        )
    }
} catch (_: Exception) {
    emptyList()
}

/** 整段聊天记录收藏：按对话原样还原（对方=白气泡+名字，我=品牌深蓝气泡靠右）。 */
@Composable
private fun ChatBundleViewer(item: FavoriteItem) {
    val msgs = remember(item.key) { parseBundle(item.bundleJson) }
    var playingIdx by remember(item.key) { mutableStateOf(-1) }
    var playingUrl by remember(item.key) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 96.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (msgs.isEmpty()) {
            Text("聊天记录内容为空", color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 14.sp)
        }
        msgs.forEachIndexed { idx, m ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start
            ) {
                if (!m.mine) FavoriteAvatar(null, m.name)
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start
                ) {
                    Text(m.name, fontSize = 11.sp, color = Color(0xFF8A94A0), maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    BundleBubble(m, isPlaying = playingIdx == idx, onTogglePlay = {
                        if (playingIdx == idx) {
                            playingIdx = -1; playingUrl = ""
                        } else {
                            playingIdx = idx; playingUrl = it
                        }
                    })
                }
                if (m.mine) FavoriteAvatar(null, m.name)
            }
        }
        if (playingIdx >= 0 && playingUrl.isNotBlank()) {
            AudioPlayer(urlOf(playingUrl))
        }
    }
}

@Composable
private fun BundleBubble(m: BundleMsg, isPlaying: Boolean, onTogglePlay: (String) -> Unit) {
    val ctx = LocalContext.current
    val src = m.lf.takeIf { it.isNotBlank() && File(it).exists() } ?: m.url
    val bubbleColor = if (m.mine) com.aurora.chat.ui.theme.AuroraPrimary else Color.White
    val textColor = if (m.mine) Color.White else com.aurora.chat.ui.theme.appTextPrimary()

    Column(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(
                RoundedCornerShape(
                    topStart = if (m.mine) 12.dp else 3.dp,
                    topEnd = if (m.mine) 3.dp else 12.dp,
                    bottomEnd = 12.dp,
                    bottomStart = 12.dp
                )
            )
            .background(bubbleColor)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        when (m.type) {
            "image" -> {
                if (src.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data(if (src.startsWith("http")) src else File(src))
                            .crossfade(true).build(),
                        contentDescription = "图片",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .width(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Text("[图片已失效]", fontSize = 13.sp, color = textColor.copy(alpha = 0.7f))
                }
                if (m.text.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(m.text, fontSize = 14.sp, lineHeight = 20.sp, color = textColor)
                }
            }
            "video" -> {
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF111111))
                ) {
                    if (isPlaying && src.isNotBlank()) {
                        VideoPlayer(urlOf(src), Modifier.fillMaxSize())
                    } else {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(34.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable(enabled = src.isNotBlank()) { onTogglePlay(src) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                if (m.text.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(m.text, fontSize = 14.sp, lineHeight = 20.sp, color = textColor)
                }
            }
            "voice" -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (m.mine) Color.White.copy(alpha = 0.2f) else Color(0xFFEAF1FB))
                        .clickable(enabled = src.isNotBlank()) { onTogglePlay(src) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (m.mine) Color.White else com.aurora.chat.ui.theme.AuroraPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isPlaying) "停止播放" else "播放语音",
                        fontSize = 13.sp,
                        color = if (m.mine) Color.White else com.aurora.chat.ui.theme.AuroraPrimary
                    )
                }
            }
            "file" -> {
                if (m.lf.isNotBlank() && File(m.lf).exists()) {
                    FavoriteFileContent(File(m.lf), m.mine)
                } else {
                    Text(
                        m.text.ifBlank { "[文件]" },
                        fontSize = 13.sp,
                        color = textColor
                    )
                    if (m.url.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("文件未保存到本机", fontSize = 11.sp, color = textColor.copy(alpha = 0.7f))
                    }
                }
            }
            else -> {
                if (m.text.isNotBlank()) {
                    Text(m.text, fontSize = 14.sp, lineHeight = 20.sp, color = textColor)
                }
            }
        }
    }
}

// ---------- 文件全类型渲染 ----------

/** 可直接读出文本预览的扩展名（与聊天文件预览同一口径的子集）。 */
private val TEXT_PREVIEW_EXT = setOf(
    "txt", "text", "log", "md", "markdown", "json", "xml", "ini", "conf", "cfg",
    "properties", "env", "toml", "yml", "yaml", "csv", "sql", "sh", "bat", "cmd",
    "java", "kt", "kts", "c", "h", "cpp", "py", "js", "ts", "html", "htm", "css", "go", "rs"
)

/** 文件消息的完整渲染：图片/视频直接渲染，文本文件出预览，其余出文件卡片 + 「打开」。 */
@Composable
private fun FavoriteFileContent(file: File, onMine: Boolean) {
    val ctx = LocalContext.current
    val ext = file.extension.lowercase()
    var playing by remember(file.path) { mutableStateOf(false) }
    val textColor = if (onMine) Color.White else com.aurora.chat.ui.theme.appTextPrimary()
    val subColor = if (onMine) Color.White.copy(alpha = 0.85f) else com.aurora.chat.ui.theme.appTextSecondary()

    when {
        ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") -> {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(file).crossfade(true).build(),
                contentDescription = file.name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .width(200.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        ext in setOf("mp4", "mov", "avi", "mkv", "webm", "3gp") -> {
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF111111))
            ) {
                if (playing) {
                    VideoPlayer(urlOf(file.absolutePath), Modifier.fillMaxSize())
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(34.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable { playing = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
        ext in TEXT_PREVIEW_EXT -> {
            val content = remember(file.path) {
                runCatching { file.readText().take(4000) }.getOrDefault("")
            }
            Text(
                text = content.ifBlank { "（文件为空）" },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (onMine) Color.White.copy(alpha = 0.12f) else com.aurora.chat.ui.theme.appCardBg())
                    .padding(8.dp)
            )
            FavoriteFileOpenRow(file, subColor, onMine)
        }
        else -> {
            Text(
                text = file.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatFileSize(file.length()),
                fontSize = 11.sp,
                color = subColor
            )
            Spacer(Modifier.height(6.dp))
            FavoriteFileOpenRow(file, subColor, onMine)
        }
    }
}

@Composable
private fun FavoriteFileOpenRow(file: File, subColor: Color, onMine: Boolean) {
    val ctx = LocalContext.current
    Spacer(Modifier.height(6.dp))
    Text(
        text = "打开文件",
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = if (onMine) Color.White else com.aurora.chat.ui.theme.AuroraPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (onMine) Color.White.copy(alpha = 0.2f)
                else Color(0xFFEAF1FB)
            )
            .clickable { openFavoriteFile(ctx, file) }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

private fun openFavoriteFile(ctx: Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file
        )
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(ctx, "没有可打开此文件的应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    else -> "%.2f GB".format(bytes / 1024f / 1024f / 1024f)
}

// ---------- 播放器 ----------

@Composable
private fun VideoPlayer(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var bound by remember { mutableStateOf(false) }
    DisposableEffect(url) {
        val p = createExoPlayer(ctx, url, !url.startsWith("http"))
        player = p
        onDispose {
            p.release()
            player = null
            bound = false
        }
    }
    AndroidView(
        factory = { c -> TextureView(c) },
        modifier = modifier,
        update = { tv ->
            val p = player
            if (p != null && !bound) {
                p.setVideoTextureView(tv)
                bound = true
            }
        }
    )
}

@Composable
private fun AudioPlayer(url: String) {
    val ctx = LocalContext.current
    DisposableEffect(url) {
        val p = createExoPlayer(ctx, url, !url.startsWith("http"))
        onDispose { p.release() }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 14.sp)
    }
}

// ---------- 数据与工具 ----------

private fun loadFavorites(ctx: Context): List<FavoriteItem> {
    val local = FavoritesStore.all(ctx)
    val shortVideos = SVFavoriteStore.all(ctx).map { f ->
        FavoriteItem(
            key = "sv:${f.id}",
            category = FavoriteCategory.VIDEO.id,
            title = f.title.ifEmpty { "短视频" },
            subtitle = f.publisherName,
            remoteUrl = "${AuroraApi.serverUrl}/api/shortvideo/hls/${f.id}/cover.jpg",
            sourceId = f.id,
            sourceType = "shortvideo"
        )
    }
    return local + shortVideos
}

private fun videoPathOf(item: FavoriteItem): String {
    item.localFiles.firstOrNull { isVideoPath(it) && File(it).exists() }?.let { return it }
    if (item.remoteUrl.isNotBlank()) return item.remoteUrl
    return item.localFiles.firstOrNull() ?: ""
}

private fun urlOf(path: String): String = when {
    path.startsWith("http") || path.startsWith("file://") || path.startsWith("content://") -> path
    else -> "file://$path"
}

private fun isImagePath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

private fun isVideoPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg")

private fun isAudioPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("mp3", "aac", "wav", "ogg", "flac", "m4a", "wma", "amr", "opus")

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
