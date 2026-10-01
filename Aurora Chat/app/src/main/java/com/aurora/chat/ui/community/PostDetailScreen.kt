package com.aurora.chat.ui.community

import android.content.Context
import androidx.activity.compose.BackHandler
import android.graphics.BitmapFactory

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.CommentInfo
import com.aurora.chat.data.api.CommunityPost
import com.aurora.chat.ui.text.UrlText
import androidx.compose.foundation.text.selection.SelectionContainer
import com.aurora.chat.data.api.CommunityResource
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.tools.DownloadManager as AppDownloadManager
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

@Composable
fun PostDetailScreen(
    postId: Long,
    onBack: () -> Unit,
    onUserClick: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val showPostScrollDown by remember {
        derivedStateOf {
            val maxScroll = scrollState.maxValue
            maxScroll > 400 && scrollState.value < maxScroll - 200
        }
    }

    var post by remember { mutableStateOf<CommunityPost?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var adultAgreed by remember { mutableStateOf(false) }
    var adultAgreedPosts by remember { mutableStateOf(setOf<Long>()) }
    var showAdultConfirm by remember { mutableStateOf(false) }
    var showAdultWarning by remember { mutableStateOf(false) }

    // ── 全屏状态 ──
    var fullScreenResources by remember { mutableStateOf<List<CommunityResource>>(emptyList()) }
    var fullScreenImageIndex by remember { mutableIntStateOf(0) }
    var fullScreenText by remember { mutableStateOf<Pair<String, String>?>(null) } // (content, fileName)
    var videoUrl by remember { mutableStateOf<String?>(null) }
    var showComments by remember { mutableStateOf(false) }
    var expandedReplies by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var actionHasLiked by remember { mutableStateOf(false) }
    var actionLikeCount by remember { mutableStateOf(0) }
    var actionCommentCount by remember { mutableStateOf(0) }
    // 评论面板高度（可拖拽调节）
    var sheetHeightFraction by remember { mutableStateOf(0.55f) }
    var showShare by remember { mutableStateOf(false) }
    // 从 post 同步初始化操作栏状态
    LaunchedEffect(post) {
        post?.let { p ->
            actionHasLiked = p.has_liked
            actionLikeCount = p.likes_count
            actionCommentCount = p.comments_count
        }
    }

    LaunchedEffect(postId) {
        isLoading = true
        // 先尝试从本地缓存加载（离线立即显示，在线也先看缓存）
        try {
            val cached = ctx.getSharedPreferences("post_cache", Context.MODE_PRIVATE)
                .getString("p$postId", null)
            if (cached != null) {
                val obj = org.json.JSONObject(cached)
                post = com.aurora.chat.data.api.CommunityPost(
                    id = obj.getLong("id"),
                    user_id = obj.getLong("user_id"),
                    username = obj.getString("username"),
                    title = obj.getString("title"),
                    content = obj.getString("content"),
                    created_at = obj.getLong("created_at"),
                    likes_count = obj.getInt("likes_count"),
                    comments_count = obj.getInt("comments_count"),
                    post_type = obj.optString("post_type", "post"),
                    is_adult = obj.optBoolean("is_adult"),
                    resources = emptyList()
                )
            }
        } catch (_: Exception) {}
        // 再尝试网络加载（如果在线上，用新数据覆盖本地）
        try {
            val result = ChatRepository.getCommunityPostDetail(postId)
            if (result.success && result.data != null) {
                post = result.data
                // 同步写入本地缓存
                try {
                    val json = org.json.JSONObject().apply {
                        put("id", result.data!!.id)
                        put("user_id", result.data!!.user_id)
                        put("username", result.data!!.username)
                        put("title", result.data!!.title)
                        put("content", result.data!!.content)
                        put("created_at", result.data!!.created_at)
                        put("likes_count", result.data!!.likes_count)
                        put("comments_count", result.data!!.comments_count)
                        put("post_type", result.data!!.post_type)
                        put("is_adult", result.data!!.is_adult)
                    }
                    ctx.getSharedPreferences("post_cache", Context.MODE_PRIVATE).edit()
                        .putString("p$postId", json.toString()).commit()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        if (post == null) error = "加载失败"
        isLoading = false
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏 ──
            val label = if (post != null) {
                if (post!!.post_type == "resource") "${post!!.username}发布的资源" else "${post!!.username}发布的帖子"
            } else ""
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", fontSize = 20.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onBack() }.padding(end = 8.dp))
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", color = Color(0xFF9CA3AF))
                }
            } else if (error.isNotEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error, color = Color(0xFFEF4444))
                }
            } else {
                val p = post ?: return@Box
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        // ── 成人内容判断 ──
                        val isAdultLocal = p.is_adult || com.aurora.chat.AdultPostCache.isAdult(p.id)
                        val isAdultBlocked = isAdultLocal && !com.aurora.chat.AdultPostCache.hasAgreed(p.id)

                        if (isAdultBlocked) {
                            // ── 标题占位符 ──
                            Text("标题已被标记成人，点击同意后即可查看", fontSize = 14.sp,
                                color = Color(0xFF6B7280), fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdultConfirm = true }
                                    .padding(vertical = 24.dp))
                            Spacer(Modifier.height(8.dp))
                            // ── 内容占位符 ──
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFFF3F4F6))
                                    .clickable { showAdultConfirm = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("内容已被标记成人，点击同意后即可查看", fontSize = 14.sp,
                                    color = Color(0xFF9CA3AF))
                            }
                        } else {
                            // ── 标题 ──
                            Text(p.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                            Spacer(Modifier.height(12.dp))

                            // ── 内容 ──
                            SelectionContainer {
                                UrlText(
                                    text = p.content,
                                    fontSize = 15.sp,
                                    defaultColor = Color(0xFF374151),
                                    lineHeight = 22.sp,
                                    selectable = false
                                )
                            }
                            Spacer(Modifier.height(20.dp))

                            // ── 资源列表（图片合并为左右滑动画廊） ──
                            var imgGroup = mutableListOf<CommunityResource>()
                            p.resources.forEach { res ->
                                if (res.resource_type == "image") {
                                    imgGroup.add(res)
                                } else {
                                    if (imgGroup.size > 0) {
                                        val groupCopy = imgGroup.toList()
                                        ImageGallery(resources = groupCopy, onOpenFullImage = { _, res ->
                                            val idx = groupCopy.indexOfFirst { it.file_path == res.file_path }
                                            fullScreenResources = groupCopy
                                            fullScreenImageIndex = if (idx >= 0) idx else 0
                                        })
                                        Spacer(Modifier.height(12.dp))
                                        imgGroup = mutableListOf()
                                    }
                                    ResourceViewer(
                                        resource = res,
                                        onOpenFullImage = { _ -> fullScreenResources = listOf(res); fullScreenImageIndex = 0 },
                                        onOpenFullText = { path, name -> fullScreenText = Pair(path, name) },
                                        onOpenVideo = { url -> videoUrl = url }
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                            if (imgGroup.size > 0) {
                                val groupCopy = imgGroup.toList()
                                ImageGallery(resources = groupCopy, onOpenFullImage = { _, res ->
                                    val idx = groupCopy.indexOfFirst { it.file_path == res.file_path }
                                    fullScreenResources = groupCopy
                                    fullScreenImageIndex = if (idx >= 0) idx else 0
                                })
                                Spacer(Modifier.height(12.dp))
                            }
                        }

                        // ── 成人内容确认对话框 ──
                        if (showAdultConfirm) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { showAdultConfirm = false },
                                title = { Text("内容提示", fontWeight = FontWeight.Bold) },
                                text = { Text("该资源已被作者标记为成人内容，是否继续观看？", fontSize = 14.sp, lineHeight = 22.sp) },
                                confirmButton = {
                                    Text("继续", color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { com.aurora.chat.AdultPostCache.agree(p.id); showAdultConfirm = false }.padding(12.dp))
                                },
                                dismissButton = {
                                    Text("取消", color = Color(0xFF6B7280),
                                        modifier = Modifier.clickable { showAdultConfirm = false }.padding(12.dp))
                                }
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                    }

                    // ── 操作栏：点赞 评论 分享（紧凑布局，与用户信息同行） ──
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
                        ActionBar(
                            likesCount = actionLikeCount,
                            commentsCount = actionCommentCount,
                            hasLiked = actionHasLiked,
                            onLike = {
                                scope.launch {
                                    val prevLiked = actionHasLiked
                                    val prevCount = actionLikeCount
                                    val result = ChatRepository.toggleLike(p.id)
                                    if (result.success && result.data != null) {
                                        val d = result.data!!
                                        actionLikeCount = d.optInt("count")
                                        actionHasLiked = d.optBoolean("liked")
                                        if (!prevLiked && d.optBoolean("liked")) {
                                            Toast.makeText(ctx, "点赞成功", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        actionHasLiked = prevLiked
                                        actionLikeCount = prevCount
                                        Toast.makeText(ctx, "点赞失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onComment = { showComments = true },
                            onShare = { showShare = true }
                        )
                    }

                    // ── 底部分割线 ──
                    Box(
                        Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB))
                            .padding(horizontal = 16.dp)
                    )

                // ── 用户信息（头像 + 姓名 → 可点击进入用户资料，时间不点击） ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    var avatarBmp by remember(p.user_id) { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(p.user_id) {
                        withContext(Dispatchers.IO) {
                            avatarBmp = ChatRepository.loadAvatar(ctx, p.user_id)
                        }
                    }
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFD1D5DB))
                        .clickable { onUserClick(p.user_id) },
                        contentAlignment = Alignment.Center) {
                        if (avatarBmp != null) {
                            Image(bitmap = avatarBmp!!.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Text(p.username.take(1), fontSize = 12.sp, color = Color.White)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(p.username, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937),
                        modifier = Modifier.clickable { onUserClick(p.user_id) })
                    Spacer(Modifier.width(12.dp))
                    Text(formatTimeDetail(p.created_at), fontSize = 12.sp, color = Color(0xFF9CA3AF))
                }

                    Spacer(Modifier.height(40.dp))
                }
            }
        }

        // ── 帖子内容滚动到底部按钮 ──
        AnimatedVisibility(
            visible = showPostScrollDown,
            enter = fadeIn() + androidx.compose.animation.scaleIn(),
            exit = fadeOut() + androidx.compose.animation.scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 16.dp)
        ) {
            Box(
                modifier = Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White, CircleShape)
                    .border(1.dp, Color(0xFFD1D5DB), CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        scope.launch {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val path = Path().apply {
                        moveTo(c.x - 9f, c.y - 4f)
                        lineTo(c.x, c.y + 5f)
                        lineTo(c.x + 9f, c.y - 4f)
                        close()
                    }
                    drawPath(path, Color(0xFF6B7280))
                }
            }
        }

        // ── 返回键逐层关闭：全屏图片 → 全屏视频 → 全屏文本 → 退出详情页 ──
        BackHandler(enabled = fullScreenResources.isNotEmpty()) {
            fullScreenResources = emptyList(); fullScreenImageIndex = 0
        }
        BackHandler(enabled = videoUrl != null) { videoUrl = null }
        BackHandler(enabled = fullScreenText != null) { fullScreenText = null }

        // ── 全屏图片 overlay（顶层叠层，覆盖整个屏幕） ──
        if (fullScreenResources.isNotEmpty()) {
            FullScreenImageOverlay(
                resources = fullScreenResources,
                initialIndex = fullScreenImageIndex,
                onDismiss = { fullScreenResources = emptyList(); fullScreenImageIndex = 0 }
            )
        }

        // ── 全屏视频 overlay ──
        if (videoUrl != null) {
            FullScreenVideoPlayer(
                url = videoUrl!!,
                onDismiss = { videoUrl = null }
            )
        }

        // ── 全屏文本查看器（从右侧滑入） ──
        AnimatedVisibility(
            visible = fullScreenText != null,
            enter = slideInHorizontally(animationSpec = tween(300)) { it },
            exit = slideOutHorizontally(animationSpec = tween(300)) { it }
        ) {
            Box(Modifier.fillMaxSize()) {
                fullScreenText?.let { (path, name) ->
                    FullScreenTextViewer(
                        filePath = path,
                        fileName = name,
                        onDismiss = { fullScreenText = null }
                    )
                }
            }
        }

        // ── 拖拽手柄已在 CommentSheet 标题栏中 ──

        // ── 评论背景遮罩（单独淡入淡出，不与面板绑定） ──
        AnimatedVisibility(
            visible = showComments,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) { showComments = false }
    )
        }

        // ── 评论面板（仅面板滑入滑出） ──
        AnimatedVisibility(
            visible = showComments,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300))
        ) {
            CommentSheet(
                postId = post?.id ?: 0L,
                sheetHeightFraction = sheetHeightFraction,
                onHeightChange = { sheetHeightFraction = it },
                onDismiss = { showComments = false },
                onCommentAdded = { actionCommentCount++ },
                onUserClick = onUserClick,
                expandedReplies = expandedReplies,
                onToggleExpand = { id ->
                    expandedReplies = if (id in expandedReplies) expandedReplies - id else expandedReplies + id
                }
            )
        }

        // ── 分享面板（带入场动画） ──
        val sharePost = post
        AnimatedVisibility(
            visible = showShare && sharePost != null,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            if (sharePost != null) {
                SharePostDialog(
                    postTitle = sharePost.title,
                    postContent = sharePost.content,
                    postId = sharePost.id,
                    postUserId = sharePost.user_id,
                    postUsername = sharePost.username,
                    postType = sharePost.post_type,
                    onDismiss = { showShare = false }
                )
            }
        }
    }
}

// ==================== 多图片左右滑动画廊 ====================

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageGallery(resources: List<CommunityResource>, onOpenFullImage: (android.graphics.Bitmap, CommunityResource) -> Unit) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { resources.size })

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF3F4F6))
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val res = resources[page]
            var bitmap by remember(res.file_path) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(res.file_path) {
                withContext(Dispatchers.IO) {
                    bitmap = loadImageBytes(res.file_path)?.let { decodeSampled(it, 720) }
                }
            }
            Box(Modifier.fillMaxSize().clickable { bitmap?.let { onOpenFullImage(it, res) } }, contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            }
        }

        // 指示器小圆点
        if (resources.size > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                resources.forEachIndexed { i, _ ->
                    Box(Modifier.size(if (pagerState.currentPage == i) 8.dp else 6.dp).clip(CircleShape)
                        .background(if (pagerState.currentPage == i) Color(0xFF1E40AF) else Color(0xFFD1D5DB)))
                }
            }
        }
    }
}

// ==================== 资源查看器 ====================

@Composable
private fun ResourceViewer(resource: CommunityResource, onOpenFullImage: (android.graphics.Bitmap) -> Unit, onOpenFullText: (path: String, name: String) -> Unit, onOpenVideo: (url: String) -> Unit) {
    when (resource.resource_type) {
        "image" -> ImageViewer(resource, onOpenFullImage)
        "video" -> VideoThumbnailViewer(resource, onOpenVideo)
        "audio" -> FileViewer(resource, onOpenFullText) // 音频作为文件，可下载+浏览器播放
        else -> FileViewer(resource, onOpenFullText)
    }
}

// ==================== 图片查看器（直接显示缩略图，点击全屏 + 双指缩放） ====================

@Composable
private fun ImageViewer(resource: CommunityResource, onOpenFullImage: (android.graphics.Bitmap) -> Unit) {
    val ctx = LocalContext.current
    var bitmap by remember(resource.file_path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var canClick by remember { mutableStateOf(false) }
    // 页面加载完成后 0.3s 才允许点击，防止误触
    LaunchedEffect(Unit) {
        delay(300)
        canClick = true
    }
    LaunchedEffect(resource.file_path) {
        withContext(Dispatchers.IO) {
            bitmap = loadImageBytes(resource.file_path)?.let { decodeSampled(it, 720) }
        }
    }

    // ── 图片 ──
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF3F4F6)).clickable(enabled = canClick) {
                    val b = bitmap
                    if (b != null) onOpenFullImage(b)
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = resource.file_name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    contentScale = ContentScale.Fit)
            } else {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            }
        }
        // 下载按钮（放在图片下方独立的 Row 中，保证绝对可见不被遮挡）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF3F4F6))
                .clickable {
                    AppDownloadManager.addCommunityDownload(
                        name = resource.file_name,
                        fileName = resource.file_name,
                        downloadUrl = resolveUrl(resource.file_path)
                    )
                    AppDownloadManager.startDownloads(ctx)
                    Toast.makeText(ctx, "已加入下载队列", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("↓", fontSize = 14.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("下载", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FullScreenImageOverlay(
    resources: List<CommunityResource>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        pageCount = { resources.size },
        initialPage = initialIndex.coerceIn(0, resources.size - 1)
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val res = resources[page]
            var bitmap by remember(res.file_path) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(res.file_path) {
                withContext(Dispatchers.IO) {
                    bitmap = loadImageBytes(res.file_path)?.let { decodeSampled(it, 720) }
                }
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("加载中...", fontSize = 14.sp, color = Color.White)
                }
            }
        }

        // 关闭按钮
        Text("✕", fontSize = 24.sp, color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).clickable { onDismiss() })

        // 页码指示
        if (resources.size > 1) {
            Text("${pagerState.currentPage + 1} / ${resources.size}",
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        }

        // 下载按钮
        Text("↓ 下载", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.25f))
                .clickable {
                    val res = resources[pagerState.currentPage]
                    AppDownloadManager.addCommunityDownload(
                        name = res.file_name, fileName = res.file_name,
                        downloadUrl = resolveUrl(res.file_path)
                    )
                    AppDownloadManager.startDownloads(ctx)
                    Toast.makeText(ctx, "已加入下载队列", Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

// ==================== 视频缩略图 + 全屏播放 ====================

@Composable
private fun VideoThumbnailViewer(resource: CommunityResource, onOpenVideo: (url: String) -> Unit) {
    val ctx = LocalContext.current
    var thumbnail by remember(resource.file_path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var thumbLoaded by remember { mutableStateOf(false) }

    // 自动加载第一帧
    LaunchedEffect(resource.file_path) {
        withContext(Dispatchers.IO) {
            thumbnail = loadVideoThumbnail(resource.file_path)
            thumbLoaded = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth().height(160.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1F2937))
            .clickable { onOpenVideo(resolveUrl(resource.file_path)) },
        contentAlignment = Alignment.Center
    ) {
        if (thumbLoaded && thumbnail != null) {
            Image(bitmap = thumbnail!!.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center) {
            Text("▶", fontSize = 22.sp, color = Color.White)
        }
        // 下载按钮（右下角）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable {
                    AppDownloadManager.addCommunityDownload(
                        name = resource.file_name,
                        fileName = resource.file_name,
                        downloadUrl = resolveUrl(resource.file_path)
                    )
                    AppDownloadManager.startDownloads(ctx)
                    Toast.makeText(ctx, "已加入下载队列", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("↓ 下载", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FullScreenVideoPlayer(url: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var hasError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    // 视频真实宽高比（用于 TextureView 自适应）
    var videoAspectRatio by remember { mutableStateOf(1f) }

    //  根据视频真实比例计算 TextureView 尺寸（保持原始比例，不拉伸）
    val textureModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(videoAspectRatio)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        // 视频画面（TextureView + MediaPlayer）
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                android.view.TextureView(context).apply {
                    var mediaPlayer: MediaPlayer? = null

                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            try {
                                mediaPlayer?.release()
                                mediaPlayer = MediaPlayer().apply {
                                    setDataSource(url)
                                    setSurface(android.view.Surface(surface))
                                    setOnPreparedListener { mp ->
                                        //  获取视频真实宽高，计算比例
                                        val vw = mp.videoWidth
                                        val vh = mp.videoHeight
                                        if (vw > 0 && vh > 0) {
                                            videoAspectRatio = vw.toFloat() / vh.toFloat()
                                        }
                                        mp.isLooping = true
                                        //  使用 SCALE_TO_FIT（letterbox）不裁剪
                                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                                        mp.start()
                                        isLoading = false
                                    }
                                    setOnBufferingUpdateListener { _, percent ->
                                        // 显示缓冲百分比（0-100），让用户知道正在加载中
                                    }
                                    setOnInfoListener { _, what, _ ->
                                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                                            isLoading = true
                                        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                                            isLoading = false
                                        }
                                        false
                                    }
                                    setOnCompletionListener { seekTo(0); start() }
                                    setOnErrorListener { _, what, extra ->
                                        android.util.Log.e("VideoPlayer", "播放错误: what=$what extra=$extra url=$url")
                                        isLoading = false
                                        hasError = true
                                        false
                                    }
                                    prepareAsync()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("VideoPlayer", "加载失败: $url", e)
                                isLoading = false
                                hasError = true
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                            mediaPlayer?.apply {
                                try { stop() } catch (_: Exception) {}
                                release()
                            }
                            mediaPlayer = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                    }
                }
            },
            modifier = textureModifier
        )

        // 加载中
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text("视频加载中...", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
        }

        // 加载失败
        if (hasError) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("视频加载失败", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable { onDismiss() }
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text("关闭", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        // 顶栏：关闭
        Text("✕", fontSize = 24.sp, color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clickable { onDismiss() }
        )

        // 下载按钮（右下角）
        if (!isLoading && !hasError) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.25f))
                    .clickable {
                        // 从 url 反推文件名
                        val parts = url.split("/")
                        val fileName = parts.lastOrNull() ?: "video.mp4"
                        AppDownloadManager.addCommunityDownload(
                            name = fileName,
                            fileName = fileName,
                            downloadUrl = url
                        )
                        AppDownloadManager.startDownloads(ctx)
                        Toast.makeText(ctx, "已加入下载队列", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("↓ 下载", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ==================== 音频播放器 ====================


// ==================== 文件查看器 ====================

@Composable
private fun FileViewer(resource: CommunityResource, onOpenFullText: (path: String, name: String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val ext = resource.file_name.substringAfterLast('.', "").lowercase()
    val textExts = listOf("txt", "json", "xml", "html", "css", "js", "md", "log", "csv", "ini", "cfg", "properties", "py", "c", "h", "java", "kt", "swift", "go", "rs", "ts", "php", "rb", "pl", "sh", "bat", "lua", "sql")
    val isText = ext in textExts
    val audioExts = listOf("mp3", "wav", "ogg", "aac", "flac", "m4a", "wma")
    val isAudio = ext in audioExts

    if (isText) {
        TextFileViewer(resource) { content, name -> onOpenFullText(content, name) }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFF3F4F6)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                    .background(when (ext) { "apk" -> Color(0xFF16A34A); "zip", "rar", "7z" -> Color(0xFFD97706); else -> Color(0xFFD1D5DB) }),
                contentAlignment = Alignment.Center) {
                when (ext) {
                    "apk" -> Text("APK", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    "zip", "rar", "7z" -> Text("ZIP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    else -> Text(if (isAudio) "♪" else "📄", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(resource.file_name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    ext == "apk" -> "APK安装包"
                    ext in listOf("zip", "rar", "7z") -> "ZIP压缩包"
                    isAudio -> "音频"
                    else -> formatFileSizeInner(resource.file_size)
                }, fontSize = 11.sp, color = Color(0xFF9CA3AF))
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E40AF))
                    .clickable {
                        AppDownloadManager.addCommunityDownload(
                            name = resource.file_name,
                            fileName = resource.file_name,
                            downloadUrl = resolveUrl(resource.file_path)
                        )
                        AppDownloadManager.startDownloads(ctx)
                        Toast.makeText(ctx, "已加入下载队列: ${resource.file_name}", Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("下载", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun TextFileViewer(resource: CommunityResource, onOpenFullScreen: (path: String, name: String) -> Unit) {
    val ctx = LocalContext.current
    val ext = resource.file_name.substringAfterLast('.', "").lowercase()
    val (iconText, iconColor) = when (ext) {
        "py" -> "PY" to Color(0xFF306998)
        "c", "h" -> "C" to Color(0xFF004481)
        "java" -> "JAVA" to Color(0xFFED8B00)
        "kt" -> "KT" to Color(0xFF7F52FF)
        "go" -> "GO" to Color(0xFF00ADD8)
        "rs" -> "RS" to Color(0xFFDEA584)
        "ts" -> "TS" to Color(0xFF3178C6)
        "swift" -> "SWIFT" to Color(0xFFF05138)
        "php" -> "PHP" to Color(0xFF777BB4)
        else -> "TXT" to Color(0xFF2563EB)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F6)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：动态图标
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(iconColor),
            contentAlignment = Alignment.Center) {
            Text(iconText, fontSize = if (iconText.length > 2) 9.sp else 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        // 截断文件名
        Text(
            truncateFileName(resource.file_name),
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = Color(0xFF1F2937), maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        // 下载按钮
        Box(
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF6B7280))
                .clickable {
                    AppDownloadManager.addCommunityDownload(
                        name = resource.file_name,
                        fileName = resource.file_name,
                        downloadUrl = resolveUrl(resource.file_path)
                    )
                    AppDownloadManager.startDownloads(ctx)
                    Toast.makeText(ctx, "已加入下载队列", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("下载", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(6.dp))
        // 右侧：查看内容
        Box(
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E40AF))
                .clickable { onOpenFullScreen(resource.file_path, resource.file_name) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("查看内容", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}

// ==================== 全屏文本查看器 ====================

@Composable
private fun FullScreenTextViewer(filePath: String, fileName: String, onDismiss: () -> Unit) {
    val chunkSize = 5000
    var content by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf("") }
    // 加载倍数: 1× → 首次点加载 +2× → 第二次 +3× → 之后固定 +3×
    var loadFactor by remember { mutableIntStateOf(1) }
    // 预加载缓冲区 — 用户下次点"加载更多"时秒出
    var prefetchedContent by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val readerRef = remember { mutableStateOf<java.io.BufferedReader?>(null) }
    val responseRef = remember { mutableStateOf<okhttp3.Response?>(null) }

    /** 从当前 reader 读取最多 count 个字符 */
    fun readFromStream(count: Int): String {
        val reader = readerRef.value ?: return ""
        try {
            val buf = CharArray(count)
            var total = 0
            while (total < count) {
                val r = reader.read(buf, total, count - total)
                if (r <= 0) break
                total += r
            }
            return String(buf, 0, total)
        } catch (_: Exception) {
            hasMore = false
            try { readerRef.value?.close() } catch (_: Exception) {}
            readerRef.value = null
            try { responseRef.value?.close() } catch (_: Exception) {}
            responseRef.value = null
            return ""
        }
    }

    /** 后台预加载下一批内容 */
    fun triggerPrefetch() {
        if (prefetchedContent.isNotEmpty() || !hasMore) return
        val nextFactor = when (loadFactor) { 1 -> 2; else -> 3 }
        val loadCount = chunkSize * nextFactor
        scope.launch(Dispatchers.IO) {
            val text = readFromStream(loadCount)
            if (text.isNotEmpty()) {
                prefetchedContent = text
                hasMore = text.length >= loadCount
            } else {
                hasMore = false
            }
        }
    }

    // 打开连接 + 读取第一块
    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val resp = com.aurora.chat.data.api.HttpClient.client.newCall(
                    okhttp3.Request.Builder().url(resolveUrl(filePath)).build()
                ).execute()
                val body = resp.body ?: throw java.io.IOException("空响应")
                val reader = body.charStream().buffered()
                val firstChunk = reader.readChars(chunkSize)
                content = firstChunk
                hasMore = firstChunk.length >= chunkSize
                readerRef.value = reader
                responseRef.value = resp
                // 初始加载完后立即预加载
                if (hasMore) triggerPrefetch()
            } catch (e: Exception) {
                errorMsg = "加载失败: ${e.localizedMessage}"
            }
            isLoading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { readerRef.value?.close() } catch (_: Exception) {}
            readerRef.value = null
            try { responseRef.value?.close() } catch (_: Exception) {}
            responseRef.value = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶栏
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", fontSize = 20.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onDismiss() }.padding(end = 8.dp))
                Text(fileName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            if (isLoading && content.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (errorMsg.isNotEmpty()) errorMsg else "加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            } else {
                Box(Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp)) {
                    Column {
                        SelectionContainer {
                            Text(content, fontSize = 13.sp, color = Color(0xFF374151), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        if (hasMore || (prefetchedContent.isNotEmpty() && content.isNotEmpty())) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFF3F4F6))
                                    .clickable(enabled = !isLoading) {
                                        scope.launch(Dispatchers.IO) {
                                            isLoading = true
                                            try {
                                                // 如果有预加载内容，秒出；否则实时读取
                                                if (prefetchedContent.isNotEmpty()) {
                                                    content += prefetchedContent
                                                    prefetchedContent = ""
                                                } else {
                                                    val nextFactor = when (loadFactor) { 1 -> 2; else -> 3 }
                                                    val loadCount = chunkSize * nextFactor
                                                    val text = readFromStream(loadCount)
                                                    content += text
                                                    hasMore = text.length >= loadCount
                                                }
                                                // 更新倍数
                                                loadFactor = when (loadFactor) { 1 -> 2; 2 -> 3; else -> 3 }
                                                // 立即预加载下一批
                                                if (hasMore) triggerPrefetch()
                                            } catch (_: Exception) {
                                                hasMore = false
                                            }
                                            isLoading = false
                                        }
                                    }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (isLoading) "加载中..." else "加载更多内容", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 辅助函数：从 BufferedReader 读取最多 count 个字符 */
private fun java.io.BufferedReader.readChars(count: Int): String {
    val buf = CharArray(count)
    var total = 0
    while (total < count) {
        val r = read(buf, total, count - total)
        if (r <= 0) break
        total += r
    }
    return String(buf, 0, total)
}

// ==================== 工具函数 ====================

private fun resolveUrl(path: String): String {
    if (path.startsWith("http")) return path
    return "${AuroraApi.serverUrl}$path"
}

private fun truncateFileName(name: String, maxLen: Int = 28): String {
    if (name.length <= maxLen) return name
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot >= name.length - 1) {
        return name.take(maxLen - 3) + "..."
    }
    val ext = name.substring(dot)        // 含点，如 .txt
    val maxBaseLen = maxLen - ext.length - 4 // " ..." 占 4 字符
    if (maxBaseLen <= 2) return name.take(maxLen - 3) + "..."
    return name.take(maxBaseLen) + " ..." + ext
}

private suspend fun loadImageBytes(urlStr: String): ByteArray? = withContext(Dispatchers.IO) {
    val fullUrl = resolveUrl(urlStr)
    try {
        val app = com.aurora.chat.AuroraChatApplication.instance
        val cacheName = run {
            val digest = java.security.MessageDigest.getInstance("MD5").digest(fullUrl.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }
        if (app != null) {
            val cacheFile = java.io.File(app.cacheDir, "community_img_cache/$cacheName")
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return@withContext cacheFile.readBytes()
            }
        }
        val bytes = com.aurora.chat.data.api.HttpClient.downloadBytes(fullUrl)
        // 写入缓存
        if (app != null && bytes.isNotEmpty()) {
            try {
                val dir = java.io.File(app.cacheDir, "community_img_cache")
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, cacheName).writeBytes(bytes)
            } catch (_: Exception) {}
        }
        bytes
    } catch (_: Exception) { null }
}

private suspend fun loadVideoThumbnail(urlStr: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
    try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(resolveUrl(urlStr), HashMap())
        val bmp = mmr.frameAtTime
        mmr.release()
        bmp
    } catch (_: Exception) { null }
}

private fun getMimeForExt(ext: String): String = when (ext) {
    "apk" -> "application/vnd.android.package-archive"
    "pdf" -> "application/pdf"
    "zip", "rar", "7z", "gz" -> "application/zip"
    "doc", "docx" -> "application/msword"
    "xls", "xlsx" -> "application/vnd.ms-excel"
    else -> "application/octet-stream"
}

// 复用同一 SimpleDateFormat 实例，避免评论/回复行每次调用都新建（滚动列表反复分配）
private val TIME_DETAIL_FMT = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
private fun formatTimeDetail(ts: Long): String {
    if (ts <= 0) return ""
    return TIME_DETAIL_FMT.format(java.util.Date(ts * 1000))
}

// 按最大边长采样解码，避免大图整分辨率解码导致内存峰值/OOM（缩略图/查看器显示用）
private fun decodeSampled(bytes: ByteArray, maxDim: Int): android.graphics.Bitmap? {
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    opts.inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxDim)
    opts.inJustDecodeBounds = false
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

private fun formatFileSizeInner(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
}

// ==================== 操作栏（点赞 评论 分享） ====================

@Composable
private fun ActionBar(
    likesCount: Int,
    commentsCount: Int,
    hasLiked: Boolean,
    onLike: () -> Unit,
    onComment: () -> Unit,
    onShare: () -> Unit = {}
) {
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var animProgress by remember { mutableStateOf(0f) }
    val animScale by animateFloatAsState(
        targetValue = if (animProgress > 0f) 1.3f else 1f,
        animationSpec = tween(300)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 点赞
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(interactionSource = noRipple, indication = null) {
                if (animProgress > 0f) return@clickable
                animProgress = 1f
                onLike()
            }.padding(end = 24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(24.dp).graphicsLayer(scaleX = animScale, scaleY = animScale)) {
                    val s = size.width / 24f
                    val path = Path().apply {
                        moveTo(12f * s, 20f * s)
                        cubicTo(5f * s, 15f * s, 2f * s, 12f * s, 2f * s, 8.5f * s)
                        cubicTo(2f * s, 5.5f * s, 4.5f * s, 3f * s, 7.5f * s, 3f * s)
                        cubicTo(9.2f * s, 3f * s, 11f * s, 4f * s, 12f * s, 5.5f * s)
                        cubicTo(13f * s, 4f * s, 14.8f * s, 3f * s, 16.5f * s, 3f * s)
                        cubicTo(19.5f * s, 3f * s, 22f * s, 5.5f * s, 22f * s, 8.5f * s)
                        cubicTo(22f * s, 12f * s, 19f * s, 15f * s, 12f * s, 20f * s)
                        close()
                    }
                    drawPath(path, color = if (hasLiked) Color(0xFFEF4444) else Color(0xFF9CA3AF))
                }
                if (animProgress > 0f) {
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(400)
                        animProgress = 0f
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Text("$likesCount", fontSize = 12.sp, color = if (hasLiked) Color(0xFFEF4444) else Color(0xFF9CA3AF))
        }

        // 评论
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onComment() }.padding(end = 24.dp)) {
            Canvas(modifier = Modifier.size(24.dp)) {
                val cx = size.width / 2; val cy = size.height / 2
                val r = size.width * 0.35f
                // 气泡形状
                val path = Path().apply {
                    moveTo(cx - r * 1.1f, cy - r * 0.3f)
                    quadraticBezierTo(cx - r * 1.1f, cy - r * 0.9f, cx - r * 0.4f, cy - r * 0.9f)
                    lineTo(cx + r * 0.4f, cy - r * 0.9f)
                    quadraticBezierTo(cx + r * 1.1f, cy - r * 0.9f, cx + r * 1.1f, cy - r * 0.3f)
                    lineTo(cx + r * 1.1f, cy + r * 0.15f)
                    quadraticBezierTo(cx + r * 1.1f, cy + r * 0.5f, cx + r * 0.6f, cy + r * 0.5f)
                    lineTo(cx + r * 0.2f, cy + r * 0.5f)
                    lineTo(cx - r * 0.3f, cy + r * 0.9f)
                    lineTo(cx - r * 0.3f, cy + r * 0.5f)
                    lineTo(cx - r * 0.4f, cy + r * 0.5f)
                    quadraticBezierTo(cx - r * 1.1f, cy + r * 0.5f, cx - r * 1.1f, cy + r * 0.15f)
                    close()
                }
                drawPath(path, color = Color(0xFF1E40AF))
            }
            Spacer(Modifier.width(4.dp))
            Text("$commentsCount", fontSize = 12.sp, color = Color(0xFF1E40AF))
        }

        // 分享
        Text(
            "分享", fontSize = 12.sp, color = Color(0xFF1E40AF),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() },
                indication = null) { onShare() }
        )
    }
}

// ==================== 评论面板（底部抬升） ====================

@Composable
private fun CommentSheet(
    postId: Long,
    sheetHeightFraction: Float = 0.55f,
    onHeightChange: (Float) -> Unit = {},
    onDismiss: () -> Unit,
    onCommentAdded: () -> Unit,
    onUserClick: (Long) -> Unit = {},
    expandedReplies: Set<Long> = emptySet(),
    onToggleExpand: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var allComments by remember { mutableStateOf<List<CommentInfo>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var replyingTo by remember { mutableStateOf("") }
    var replyingToId by remember { mutableStateOf(0L) }
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 禁言检测
    var mutedUntil by remember { mutableStateOf(0L) }
    val muteRemainingText = remember(mutedUntil) {
        if (mutedUntil <= 0L) ""
        else {
            val remainSec = (mutedUntil - System.currentTimeMillis() / 1000).coerceAtLeast(0)
            if (remainSec <= 0) ""
            else {
                val days = remainSec / 86400; val hours = (remainSec % 86400) / 3600
                val mins = (remainSec % 3600) / 60
                when { days > 0 -> "${days}天${hours}小时"; hours > 0 -> "${hours}小时${mins}分钟"
                    mins > 0 -> "${mins}分钟"; else -> "${remainSec}秒" }
            }
        }
    }
    val isMuted = mutedUntil > 0L && muteRemainingText.isNotEmpty()
    LaunchedEffect(Unit) {
        try {
            val r = com.aurora.chat.data.api.AuroraApi.adminCheckMuteStatus(com.aurora.chat.data.api.AuroraApi.currentUserId)
            if (r.success && r.data != null && r.data.has("expires_at")) {
                mutedUntil = r.data.optLong("expires_at", 0L)
            }
        } catch (_: Exception) { }
    }

    // 自动聚焦输入框并弹出键盘
    LaunchedEffect(replyingTo) {
        if (replyingTo.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // 分离顶层评论和子评论：按"根祖先"分组，使任意深度的回复都能归到所属顶层评论下显示
    val commentById = allComments.associateBy { it.id }
    fun rootIdOfId(id: Long): Long {
        var curId = id
        var guard = 0
        while (guard < 50) {
            val c = commentById[curId] ?: break
            if (c.parent_id <= 0L) break
            curId = c.parent_id
            guard++
        }
        return curId
    }
    val topLevelComments = allComments.filter { it.parent_id == 0L }
    val repliesByRoot = allComments.filter { it.parent_id > 0L }.groupBy { rootIdOfId(it.id) }
    // 根据 parent_id 查找被回复的用户名（用于显示"回复"格式）
    val parentUsernameMap = allComments.associate { it.id to it.username }
    val parentUserIdMap = allComments.associate { it.id to it.user_id }

    LaunchedEffect(postId) {
        val result = ChatRepository.getComments(postId)
        if (result.success && result.data != null) {
            allComments = result.data!!.comments
            total = result.data!!.total
        }
        isLoading = false
    }

    // 键盘高度（用于让面板随键盘平滑抬起，但不撑满全屏）
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val liftPx by androidx.compose.animation.core.animateIntAsState(
        targetValue = imeBottomPx,
        animationSpec = tween(durationMillis = 280),
        label = "commentSheetLift"
    )

    val listState = rememberLazyListState()
    val shouldShowScrollDown by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible < totalItems - 5
        }
    }

    // 键盘弹起时：整体用动画偏移抬到键盘之上，保持可拖拽高度（不再撑满全屏），
    // 抬起/落下均有平滑动画；拖拽手柄始终可用。
    Box(
        Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset(0, -liftPx) },
        contentAlignment = Alignment.BottomCenter
    ) {
        var dragFraction by remember { mutableFloatStateOf(sheetHeightFraction) }
        var isDragging by remember { mutableStateOf(false) }
        LaunchedEffect(sheetHeightFraction) { if (!isDragging) dragFraction = sheetHeightFraction }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(dragFraction)
                .background(Color.White, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}
                .systemBarsPadding()
        ) {
        // 顶部：拖拽条单独一行（不跟文字并排）
        Box(
            modifier = Modifier
                .fillMaxWidth().padding(top = 6.dp)
                .height(20.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = {
                            isDragging = false
                            onHeightChange(dragFraction)
                        },
                        onDragCancel = {
                            isDragging = false
                            dragFraction = sheetHeightFraction
                        }
                    ) { _, dragAmount ->
                        // 灵敏度：/ 3000f 降低灵敏度，拖拽更平滑
                        dragFraction = (dragFraction - dragAmount / 3000f).coerceIn(0.2f, 0.9f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .width(40.dp).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFD1D5DB).copy(alpha = 0.5f))
            )
        }
        // 第二行：标题 + 关闭
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("评论 ($total)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            Text("关闭", fontSize = 14.sp, color = Color(0xFF1E40AF),
                modifier = Modifier.clickable { onDismiss() })
        }


        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        // 评论列表
        if (isLoading) {
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(top = 20.dp))
            }
        } else if (topLevelComments.isEmpty()) {
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("暂无评论，快去抢沙发吧", fontSize = 14.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(top = 20.dp))
            }
        } else {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                state = listState
            ) {
                items(topLevelComments, key = { it.id }) { comment ->
                    val replyList = repliesByRoot[comment.id] ?: emptyList()
                    Box(Modifier.animateItemPlacement()) {
                    CommentRow(
                        comment = comment,
                        isExpanded = comment.id in expandedReplies,
                        replyCount = replyList.size,
                        replies = replyList,
                        parentUsernameMap = parentUsernameMap,
                        parentUserIdMap = parentUserIdMap,
                        onUserClick = onUserClick,
                        onToggleExpand = { onToggleExpand(comment.id) },
                        onReplyClick = { id ->
                            replyingTo = parentUsernameMap[id] ?: ""
                            replyingToId = id
                        }
                    )
                    } // end Box animateItemPlacement
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        // 禁言横幅
        if (isMuted) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFEF3C7))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "你已被禁言，$muteRemainingText",
                    fontSize = 13.sp, color = Color(0xFF92400E), fontWeight = FontWeight.Medium
                )
            }
        }

        // 输入框
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFF3F4F6))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = {
                        if (!isMuted) inputText = it
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = if (isMuted) Color(0xFF9CA3AF) else Color(0xFF1F2937)),
                    singleLine = true,
                    readOnly = isMuted,
                    cursorBrush = SolidColor(if (isMuted) Color.Transparent else Color(0xFF1E40AF)),
                    decorationBox = { inner ->
                        Box {
                            val placeholder = if (replyingTo.isNotEmpty()) "回复${replyingTo}:" else if (isMuted) "你已被禁言" else "发送有爱评论"
                            if (inputText.isEmpty()) Text(placeholder, fontSize = 14.sp, color = Color(0xFF9CA3AF))
                            inner()
                        }
                    }
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (isMuted) "禁言中" else "发送",
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (isMuted) Color(0xFF9CA3AF) else Color(0xFF1E40AF),
                modifier = Modifier.clickable(enabled = inputText.isNotBlank() && !isMuted) {
                    val text = inputText.trim()
                    if (text.isBlank()) return@clickable
                    scope.launch {
                        val parentId = replyingToId
                        val result = ChatRepository.addComment(postId, text, parentId)
                        if (result.success) {
                            inputText = ""
                            replyingTo = ""
                            // 刷新评论
                            val refreshed = ChatRepository.getComments(postId)
                            if (refreshed.success && refreshed.data != null) {
                                allComments = refreshed.data!!.comments
                                total = refreshed.data!!.total
                            }
                            replyingToId = 0L
                            onCommentAdded()
                            // 自动展开所属顶层评论线程（自己的回复默认可展开）
                            if (parentId > 0L) onToggleExpand(rootIdOfId(parentId))
                        } else {
                            android.widget.Toast.makeText(ctx, result.message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }.padding(horizontal = 8.dp, vertical = 4.dp))
        }   // 关闭输入Row
    }       // 关闭Column

        // 滚动到底部按钮
        AnimatedVisibility(
            visible = shouldShowScrollDown,
            enter = fadeIn() + androidx.compose.animation.scaleIn(),
            exit = fadeOut() + androidx.compose.animation.scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 76.dp, end = 16.dp)
        ) {
            Box(
                modifier = Modifier.size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White, CircleShape)
                    .border(1.dp, Color(0xFFD1D5DB), CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        scope.launch {
                            val target = (topLevelComments.size - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(target)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val path = Path().apply {
                        moveTo(c.x - 9f, c.y - 4f)
                        lineTo(c.x, c.y + 5f)
                        lineTo(c.x + 9f, c.y - 4f)
                        close()
                    }
                    drawPath(path, Color(0xFF6B7280))
                }
            }
        }
}           // 关闭Box
}           // 关闭CommentSheet函数

// ── 带有回复功能的评论行 ──
@Composable
internal fun CommentRow(
    comment: CommentInfo,
    isExpanded: Boolean = false,
    replyCount: Int = 0,
    replies: List<CommentInfo> = emptyList(),
    parentUsernameMap: Map<Long, String> = emptyMap(),
    parentUserIdMap: Map<Long, Long> = emptyMap(),
    onUserClick: (Long) -> Unit = {},
    onReplyClick: (Long) -> Unit = {},
    onToggleExpand: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var avatarBmp by remember(comment.user_id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(comment.user_id) {
        withContext(Dispatchers.IO) {
            avatarBmp = ChatRepository.loadAvatar(ctx, comment.user_id)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(250))) {
        // 主评论行
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onReplyClick(comment.id) },
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFD1D5DB)).clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onUserClick(comment.user_id) },
                contentAlignment = Alignment.Center) {
                if (avatarBmp != null) {
                    Image(bitmap = avatarBmp!!.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(comment.username.take(1), fontSize = 12.sp, color = Color.White)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(comment.username, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                    Spacer(Modifier.width(8.dp))
                    Text(formatTimeDetail(comment.created_at), fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
                Spacer(Modifier.height(4.dp))
                // 点击文字也能触发回复：把 onClick 交给 UrlText 自身处理，
                // 去掉外层 SelectionContainer（它会拦截点击手势，导致点文字不触发回复）。
                // selectable=true 保留长按选择文本的能力。
                UrlText(
                    text = comment.content,
                    fontSize = 14.sp,
                    defaultColor = Color(0xFF374151),
                    selectable = true,
                    onClick = { onReplyClick(comment.id) }
                )
            }
        }

        // 展开N条回复按钮（有回复时才显示，展开状态下隐藏，用右下角的收回替代）
        if (replyCount > 0 && !isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, bottom = 4.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onToggleExpand() }
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    "展开${replyCount}条评论",
                    fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium
                )
            }
        }

        // 回复列表（AnimatedVisibility动画展开，整体向右缩进）
        AnimatedVisibility(
            visible = isExpanded,
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = tween(250)
            ) + fadeIn(tween(250)),
            exit = slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(200)
            ) + fadeOut(tween(200))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp)
            ) {
                replies.forEach { reply ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onReplyClick(reply.id) },
                        verticalAlignment = Alignment.Top
                    ) {
                        // 子评论头像（加载实际头像）
                        var replyAvatar by remember(reply.user_id) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        LaunchedEffect(reply.user_id) {
                            withContext(Dispatchers.IO) {
                                replyAvatar = ChatRepository.loadAvatar(ctx, reply.user_id)
                            }
                        }
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFD1D5DB)).clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onUserClick(reply.user_id) },
                            contentAlignment = Alignment.Center) {
                            if (replyAvatar != null) {
                                Image(bitmap = replyAvatar!!.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Text(reply.username.take(1), fontSize = 10.sp, color = Color.White)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            // 显示 "username 回复 replied_username"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(reply.username, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                                if (reply.parent_id > 0L) {
                                    val repliedName = parentUsernameMap[reply.parent_id] ?: ""
                                    val repliedUserId = parentUserIdMap[reply.parent_id] ?: 0L
                                    if (repliedName.isNotEmpty()) {
                                        Text(" 回复 ", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                                        Text(repliedName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                            modifier = Modifier.clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) { if (repliedUserId > 0L) onUserClick(repliedUserId) })
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(formatTimeDetail(reply.created_at), fontSize = 10.sp, color = Color(0xFF9CA3AF))
                            }
                            Spacer(Modifier.height(2.dp))
                            // 点击文字也能触发回复（原理同上）
                            UrlText(
                                text = reply.content,
                                fontSize = 13.sp,
                                defaultColor = Color(0xFF374151),
                                selectable = true,
                                onClick = { onReplyClick(reply.id) }
                            )
                        }
                    }
                    // 子评论之间仅分割线
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB).copy(alpha = 0.4f)))
                }
                // "收回"按钮在展开区域的右下角（展开状态下唯一的收回入口）
                if (replies.isNotEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text("收回", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onToggleExpand() })
                    }
                }
            }
        }
    }
}
