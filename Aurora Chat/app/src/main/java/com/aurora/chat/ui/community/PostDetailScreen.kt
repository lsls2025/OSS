package com.aurora.chat.ui.community
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import androidx.activity.compose.BackHandler
import android.graphics.BitmapFactory

import android.media.MediaMetadataRetriever
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aurora.chat.ui.common.createExoPlayer
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.CommentInfo
import com.aurora.chat.data.api.CommunityPost
import com.aurora.chat.ui.text.UrlText
import com.aurora.chat.data.api.CommunityResource
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.tools.DownloadManager as AppDownloadManager
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog

@Composable
fun PostDetailScreen(
    postId: Long,
    onBack: () -> Unit,
    onUserClick: (Long) -> Unit = {},
    onOpenGroupProfile: (Long, String) -> Unit = { _, _ -> }
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
    // 视频封面：由列表缩略图直接带进全屏播放器，避免进全屏后先黑屏、也避免再联网取一次首帧
    var videoPoster by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // 退出动画期间保留上一次的非空数据快照，避免 AnimatedVisibility 退出时数据已清空导致黑屏/崩溃
    var fullScreenImageSnap by remember { mutableStateOf<List<CommunityResource>>(emptyList()) }
    var fullScreenImageIdxSnap by remember { mutableIntStateOf(0) }
    var videoUrlSnap by remember { mutableStateOf<String?>(null) }
    var videoPosterSnap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showComments by remember { mutableStateOf(false) }
    var expandedReplies by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var actionHasLiked by remember { mutableStateOf(false) }
    var actionLikeCount by remember { mutableStateOf(0) }
    var actionCommentCount by remember { mutableStateOf(0) }
    // 评论面板高度（可拖拽调节）
    // 评论面板默认高度：0.5 —— 打开只占半屏，不遮挡正文；如需更大可上拖展开（范围 0.2~0.9）。
    var sheetHeightFraction by remember { mutableStateOf(0.5f) }
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
                // 看过的帖子连图片/视频/文件列表一起缓存，离线重开也能完整渲染
                val cachedResources = obj.optJSONArray("resources")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val r = arr.getJSONObject(i)
                        com.aurora.chat.data.api.CommunityResource(
                            id = r.optLong("id"),
                            post_id = r.optLong("post_id"),
                            resource_type = r.optString("resource_type"),
                            file_name = r.optString("file_name"),
                            file_path = r.optString("file_path"),
                            file_size = r.optLong("file_size")
                        )
                    }
                } ?: emptyList()
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
                    resources = cachedResources
                )
            }
        } catch (_: Exception) {}
        // 再尝试网络加载（如果在线上，用新数据覆盖本地）
        try {
            val result = ChatRepository.getCommunityPostDetail(postId)
            if (result.success && result.data != null) {
                val d = result.data!!
                post = d
                // 同步写入本地缓存（正文 + 资源列表）
                try {
                    val json = org.json.JSONObject().apply {
                        put("id", d.id)
                        put("user_id", d.user_id)
                        put("username", d.username)
                        put("title", d.title)
                        put("content", d.content)
                        put("created_at", d.created_at)
                        put("likes_count", d.likes_count)
                        put("comments_count", d.comments_count)
                        put("post_type", d.post_type)
                        put("is_adult", d.is_adult)
                        put("resources", org.json.JSONArray().apply {
                            d.resources.forEach { r ->
                                put(org.json.JSONObject().apply {
                                    put("id", r.id)
                                    put("post_id", r.post_id)
                                    put("resource_type", r.resource_type)
                                    put("file_name", r.file_name)
                                    put("file_path", r.file_path)
                                    put("file_size", r.file_size)
                                })
                            }
                        })
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
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            // 注意：这里不能加 statusBarsPadding —— 否则全屏图片/视频 overlay 作为子布局
            // 也被限制在状态栏下方，顶部会露出这一层的白色，形成「黑底 + 白条」。
            // 状态栏安全区改由内部内容 Column 承担。
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {},
                    onDoubleTap = {},
                    onLongPress = {}
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            }

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", color = com.aurora.chat.ui.theme.appTextHint())
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
                        // ── 敏感内容判断 ──
                        val isAdultLocal = p.is_adult || com.aurora.chat.AdultPostCache.isAdult(p.id)
                        val isAdultBlocked = isAdultLocal && !com.aurora.chat.AdultPostCache.hasAgreed(p.id)

                        if (isAdultBlocked) {
                            // ── 标题照常展示：只屏蔽正文与图片，标题不屏蔽 ──
                            SelectionContainer {
                                Text(p.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("注意", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFEF4444).copy(alpha = 0.1f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("该内容已被标记为敏感内容", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            }
                            Spacer(Modifier.height(12.dp))
                            // ── 内容占位符：正文与图片都不渲染，点这里走确认流程 ──
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(com.aurora.chat.ui.theme.appCardBg())
                                    .clickable { showAdultConfirm = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("内容已被标记", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                        color = com.aurora.chat.ui.theme.appTextSecondary())
                                    Spacer(Modifier.height(4.dp))
                                    Text("点击继续查看", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        } else {
                            // ── 标题 ──
                            // 与下方正文一致：套SelectionContainer 支持长按选择/复制
                            SelectionContainer {
                                Text(p.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                            }
                            Spacer(Modifier.height(12.dp))

                            // ── 内容（正文 + 挂载卡片，URL 自动高亮） ──
                            val detailParts = remember(p.content) { extractPostCards(p.content) }
                            PostContentWithCards(
                                parts = detailParts,
                                onUserCardClick = { uid, _ -> onUserClick(uid) },
                                onGroupCardClick = onOpenGroupProfile,
                                bodyFontSize = 15.sp,
                                bodyLineHeight = 22.sp,
                                bodyMaxLines = Int.MAX_VALUE,
                                bodyColor = com.aurora.chat.ui.theme.appTextSecondary(),
                                linkColor = Color(0xFF1E40AF),
                                bodySelectable = true
                            )
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
                                            fullScreenImageSnap = groupCopy
                                            fullScreenImageIdxSnap = if (idx >= 0) idx else 0
                                        })
                                        Spacer(Modifier.height(12.dp))
                                        imgGroup = mutableListOf()
                                    }
                                    ResourceViewer(
                                        resource = res,
                                        onOpenFullImage = { _ -> fullScreenResources = listOf(res); fullScreenImageIndex = 0; fullScreenImageSnap = listOf(res); fullScreenImageIdxSnap = 0 },
                                        onOpenFullText = { path, name -> fullScreenText = Pair(path, name) },
                                        onOpenVideo = { url, poster -> videoPoster = poster; videoUrl = url; videoPosterSnap = poster; videoUrlSnap = url }
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
                                    fullScreenImageSnap = groupCopy
                                    fullScreenImageIdxSnap = if (idx >= 0) idx else 0
                                })
                                Spacer(Modifier.height(12.dp))
                            }
                        }

                        // ── 敏感内容确认对话框 ──
                        if (showAdultConfirm) {
                            AlertDialog(
                                onDismissRequest = { showAdultConfirm = false },
                                title = { Text("内容提示", fontWeight = FontWeight.Bold) },
                                text = { Text("该资源已被作者标记为敏感内容，是否继续观看？", fontSize = 14.sp, lineHeight = 22.sp) },
                                confirmButton = {
                                    Text("继续", color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { com.aurora.chat.AdultPostCache.agree(p.id); showAdultConfirm = false }.padding(12.dp))
                                },
                                dismissButton = {
                                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary(),
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
                        Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider())
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
                    Text(p.username, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.clickable { onUserClick(p.user_id) })
                    Spacer(Modifier.width(12.dp))
                    Text(formatTimeDetail(p.created_at), fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
                    .background(com.aurora.chat.ui.theme.appCardBg(), CircleShape)
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
                    drawPath(path, com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        }

        // 全屏预览是黑底，状态栏图标必须切成白色，否则深色图标在黑底上看不见；
        // 关闭后恢复深色图标（详情页本身是白底）。只改图标颜色、不隐藏系统栏，避免退出时布局跳动。
        val fullScreenActive = fullScreenResources.isNotEmpty() || videoUrl != null
        val rootView = LocalView.current
        DisposableEffect(fullScreenActive) {
            val window = rootView.context.findActivityOrNull()?.window
            val controller = window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
            }
            controller?.isAppearanceLightStatusBars = !fullScreenActive
            controller?.isAppearanceLightNavigationBars = !fullScreenActive
            onDispose {
                controller?.isAppearanceLightStatusBars = true
                controller?.isAppearanceLightNavigationBars = true
            }
        }

        // ── 返回键逐层关闭：全屏图片 → 全屏视频 → 全屏文本 → 退出详情页 ──
        BackHandler(enabled = fullScreenResources.isNotEmpty()) {
            fullScreenResources = emptyList(); fullScreenImageIndex = 0
        }
        BackHandler(enabled = videoUrl != null) { videoUrl = null }
        BackHandler(enabled = fullScreenText != null) { fullScreenText = null }

        // ── 全屏图片 overlay（顶层叠层，覆盖整个屏幕；带缩放+淡入淡出，展开观感与聊天一致） ──
        // ── 全屏图片 overlay（黑色背景独立渐显：图片先缩放到位，背景随后才出现） ──
        Box(Modifier.fillMaxSize()) {
            // 背景遮罩层：进入时延迟 130ms 再淡入黑，退出时立即淡出（先于图片缩回）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.animation.animateColorAsState(
                            targetValue = if (fullScreenResources.isNotEmpty()) Color.Black else Color.Transparent,
                            animationSpec = tween(durationMillis = 220, delayMillis = if (fullScreenResources.isNotEmpty()) 130 else 0)
                        ).value
                    )
            )
            AnimatedVisibility(
                visible = fullScreenResources.isNotEmpty(),
                enter = fadeIn(animationSpec = tween(260)) + androidx.compose.animation.scaleIn(animationSpec = tween(260), initialScale = 0.85f),
                exit = fadeOut(animationSpec = tween(200)) + androidx.compose.animation.scaleOut(animationSpec = tween(200), targetScale = 0.85f)
            ) {
                if (fullScreenImageSnap.isNotEmpty()) {
                    FullScreenImageOverlay(
                        resources = fullScreenImageSnap,
                        initialIndex = fullScreenImageIdxSnap,
                        onDismiss = { fullScreenResources = emptyList(); fullScreenImageIndex = 0 }
                    )
                }
            }
        }

        // ── 全屏视频 overlay（同样：图片/视频先到位，黑色背景随后渐显） ──
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.animation.animateColorAsState(
                            targetValue = if (videoUrl != null) Color.Black else Color.Transparent,
                            animationSpec = tween(durationMillis = 220, delayMillis = if (videoUrl != null) 130 else 0)
                        ).value
                    )
            )
            AnimatedVisibility(
                visible = videoUrl != null,
                // 视频不要用缩放进场：播放器尺寸在首帧后还会按真实比例变化，
                // 缩放与尺寸跳变叠加就会「抽搐」；纯淡入最稳。
                enter = fadeIn(animationSpec = tween(260)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                videoUrlSnap?.let { u ->
                    FullScreenVideoPlayer(
                        url = u,
                        poster = videoPosterSnap,
                        onDismiss = { videoUrl = null }
                    )
                }
            }
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
    // 每张图解码后记录宽高比，卡片按「当前页」的真实比例决定尺寸：
    // - 横图/方图：宽度铺满，高度按比例算
    // - 竖图（手机截图 1080×2400 这类）：高度封顶 280dp，宽度按比例收窄
    // 卡片本身就和图片同比例，所以既不会露灰边，也不会被一张长截图撑出半屏。
    val pageRatios = remember(resources.map { it.file_path }) { mutableStateMapOf<Int, Float>() }
    val rawRatio = pageRatios[pagerState.currentPage] ?: (4f / 3f)
    // 下限 0.45≈9:20（普通手机截图）；再极端的拼接长图按 0.45 处理，只露顶部并提示点开看全图
    val targetRatio = rawRatio.coerceIn(0.45f, 2f)
    val galleryRatio by animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = tween(durationMillis = 180),
        label = "communityGalleryRatio"
    )
    val maxPreviewHeight = 280.dp
    val topAligned = rawRatio < 0.45f

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val cardWidth = minOf(maxWidth, maxPreviewHeight * galleryRatio)
        val cardHeight = cardWidth / galleryRatio
        Box(
            modifier = Modifier
                .size(cardWidth, cardHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
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
                    bitmap?.let { bmp ->
                        if (bmp.height > 0) pageRatios[page] = bmp.width.toFloat() / bmp.height.toFloat()
                    }
                }
                Box(Modifier.fillMaxSize().clickable { bitmap?.let { onOpenFullImage(it, res) } }, contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alignment = if (topAligned) Alignment.TopCenter else Alignment.Center)
                    } else {
                        Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
}

// ==================== 资源查看器 ====================

@Composable
private fun ResourceViewer(resource: CommunityResource, onOpenFullImage: (android.graphics.Bitmap) -> Unit, onOpenFullText: (path: String, name: String) -> Unit, onOpenVideo: (url: String, poster: android.graphics.Bitmap?) -> Unit) {
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
                .background(com.aurora.chat.ui.theme.appCardBg()).clickable(enabled = canClick) {
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
                    Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            }
        }
        // 下载按钮（放在图片下方独立的 Row 中，保证绝对可见不被遮挡）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
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
        modifier = Modifier.fillMaxSize().background(Color.Transparent)
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val res = resources[page]
            var bitmap by remember(res.file_path) { mutableStateOf<android.graphics.Bitmap?>(null) }
            // 每页独立缩放/位移（与聊天预览一致：双指缩放 1~5 倍，放大后单指拖动平移）
            var scale by remember(res.file_path) { mutableFloatStateOf(1f) }
            var offset by remember(res.file_path) { mutableStateOf(Offset.Zero) }
            var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            LaunchedEffect(res.file_path) {
                withContext(Dispatchers.IO) {
                    // 解码长边提到 1600：保证放大后仍清晰（Pager 仅保留当前页，内存可控）
                    bitmap = loadImageBytes(res.file_path)?.let { decodeSampled(it, 1600) }
                }
            }
            // 长图（宽高比 < 0.66，典型是手机截图）打开后自动放大到「铺满屏幕宽度」，
            // 并且从图片顶部开始看 —— 不再需要用户自己双指放大找内容。
            // 横图/方图按 Fit 本来就铺满，不触发；用户已经开始缩放也不打扰。
            LaunchedEffect(res.file_path, bitmap, viewport, pagerState.currentPage) {
                val b = bitmap ?: return@LaunchedEffect
                if (pagerState.currentPage != page) return@LaunchedEffect
                val vw = viewport.width.toFloat()
                val vh = viewport.height.toFloat()
                if (vw <= 0f || vh <= 0f || b.height <= 0) return@LaunchedEffect
                val a = b.width.toFloat() / b.height
                if (a >= 0.66f) return@LaunchedEffect
                val fitW = kotlin.math.min(vw, vh * a)
                val target = (vw / fitW).coerceIn(1f, 3f)
                if (target < 1.1f || scale > 1.001f) return@LaunchedEffect
                delay(350)
                if (pagerState.currentPage != page || scale > 1.001f) return@LaunchedEffect
                androidx.compose.animation.core.animate(
                    initialValue = 1f,
                    targetValue = target,
                    animationSpec = tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                ) { v, _ ->
                    scale = v
                    // 顶部锚定：长图是按高度 Fit 的，向下平移让图片顶边始终贴住屏幕顶
                    offset = Offset(0f, vh * (v - 1f) / 2f)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewport = it }
                    .pointerInput(res.file_path, bitmap) {
                        // 放大后的平移边界按图片真实比例（Fit 后实际占据的矩形）计算，
                        // 用整屏算会把宽图/长图拖出大片空白
                        fun limits(s: Float): Pair<Float, Float> {
                            val vw = size.width.toFloat().coerceAtLeast(1f)
                            val vh = size.height.toFloat().coerceAtLeast(1f)
                            val b = bitmap
                            val a = if (b != null && b.height > 0) b.width.toFloat() / b.height else vw / vh
                            val fitW = kotlin.math.min(vw, vh * a)
                            val fitH = kotlin.math.min(vh, vw / a)
                            return ((fitW * s - vw) / 2f).coerceAtLeast(0f) to
                                ((fitH * s - vh) / 2f).coerceAtLeast(0f)
                        }
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var prevDistance = 0f
                            var prevPos: Offset? = null
                            var handedOff = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                if (changes.isEmpty()) break
                                if (changes.size >= 2) {
                                    // 双指缩放：以双指中心为锚点
                                    val dist = (changes[0].position - changes[1].position).getDistance()
                                    val centroid = (changes[0].position + changes[1].position) / 2f
                                    if (prevDistance > 0f && dist > 0f) {
                                        val old = scale
                                        val ns = (old * (dist / prevDistance)).coerceIn(1f, 5f)
                                        if (ns != old) {
                                            val c = Offset(size.width / 2f, size.height / 2f)
                                            val k = ns / old
                                            val shifted = (centroid - c) - (centroid - c - offset) * k
                                            val (mx, my) = limits(ns)
                                            scale = ns
                                            offset = if (ns <= 1.001f) Offset.Zero else Offset(
                                                shifted.x.coerceIn(-mx, mx),
                                                shifted.y.coerceIn(-my, my)
                                            )
                                        }
                                    }
                                    prevDistance = dist
                                    prevPos = null
                                    handedOff = false
                                    changes.forEach { it.consume() }
                                } else if (changes.size == 1) {
                                    val ch = changes[0]
                                    if (scale > 1.001f && ch.pressed && !handedOff) {
                                        // 已放大：单指拖拽平移图片
                                        val cur = ch.position
                                        val last = prevPos
                                        if (last != null) {
                                            val pan = cur - last
                                            if (pan.getDistance() > 0.3f) {
                                                val (mx, my) = limits(scale)
                                                val nx = offset.x + pan.x
                                                val ny = offset.y + pan.y
                                                // 到水平边界还往外推 → 让给 Pager 翻页
                                                if (mx > 0f &&
                                                    kotlin.math.abs(pan.x) > kotlin.math.abs(pan.y) &&
                                                    (nx > mx || nx < -mx)
                                                ) {
                                                    handedOff = true
                                                    prevPos = null
                                                } else {
                                                    offset = Offset(nx.coerceIn(-mx, mx), ny.coerceIn(-my, my))
                                                    prevPos = cur
                                                    ch.consume()
                                                }
                                            } else {
                                                prevPos = cur
                                            }
                                        } else {
                                            prevPos = cur
                                        }
                                    } else {
                                        // 未放大：不消费单指事件，让 HorizontalPager 接管左右切换
                                        prevPos = null
                                    }
                                    prevDistance = 0f
                                }
                                if (changes.all { !it.pressed }) break
                            }
                            // 抬手吸附：不吸附时双指外扩常停在 1.02~1.08，
                            // 之后 Pager 一直收到被消费的拖动 → 左右切图永久失效
                            if (scale < 1.08f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val (mx, my) = limits(scale)
                                offset = Offset(offset.x.coerceIn(-mx, mx), offset.y.coerceIn(-my, my))
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
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
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
                .clickable { onDismiss() })

        // 页码指示
        if (resources.size > 1) {
            Text("${pagerState.currentPage + 1} / ${resources.size}",
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp))
        }

        // 下载按钮
        Text("↓ 下载", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)
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
private fun VideoThumbnailViewer(resource: CommunityResource, onOpenVideo: (url: String, poster: android.graphics.Bitmap?) -> Unit) {
    val ctx = LocalContext.current
    var thumbnail by remember(resource.file_path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var thumbLoaded by remember { mutableStateOf(false) }

    // 自动加载第一帧：先查缓存（显示过一次后就零网络），没有才去视频里取帧并落盘
    LaunchedEffect(resource.file_path) {
        val videoUrl = resolveUrl(resource.file_path)
        withContext(Dispatchers.IO) {
            val cached = com.aurora.chat.data.local.VideoThumbCache.load(ctx, videoUrl)
            thumbnail = if (cached != null) {
                cached
            } else {
                loadVideoThumbnail(resource.file_path)?.also { bmp ->
                    com.aurora.chat.data.local.VideoThumbCache.save(ctx, videoUrl, bmp)
                }
            }
            thumbLoaded = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth().height(160.dp)
            .clip(RoundedCornerShape(12.dp)).background(com.aurora.chat.ui.theme.appTextPrimary())
            .clickable { onOpenVideo(resolveUrl(resource.file_path), thumbnail) },
        contentAlignment = Alignment.Center
    ) {
        if (thumbLoaded && thumbnail != null) {
            Image(bitmap = thumbnail!!.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "播放",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.FileDownload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("下载", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FullScreenVideoPlayer(url: String, poster: android.graphics.Bitmap? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var hasError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    // 初始宽高比直接取缩略图（poster）的真实比例：竖屏录屏一打开就是竖的，
    // 不会先用 16:9 的扁盒子把画面压扁、再等播放器报尺寸时突然拉伸到全屏。
    val posterAspect = poster?.let {
        if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height.toFloat() else null
    }
    var videoAspectRatio by remember(url) { mutableStateOf(posterAspect ?: (16f / 9f)) }
    // 封面：首帧真正上屏前盖住黑屏，封面直接用列表已解好的缩略图，不额外联网取帧
    var showPoster by remember(url) { mutableStateOf(poster != null) }
    // 播放器持有在组合作用域：组合被移除（关闭全屏 / 退出帖子 / 离开页面）时必定释放。
    // 只在 TextureView 回调里释放并不可靠，部分机型收不到回调，退出后仍会继续出声。
    val playerRef = remember { mutableStateOf<ExoPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { playerRef.value?.stop() }
            runCatching { playerRef.value?.release() }
            playerRef.value = null
        }
    }
    // 前后台：退到后台立即暂停，回前台恢复；避免用户离开应用后仍有声音
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP ->
                    runCatching { playerRef.value?.pause() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    runCatching {
                        val p = playerRef.value
                        if (p != null && p.playbackState != Player.STATE_ENDED) p.play()
                    }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 播放控制状态：点击暂停/继续 + 可拖动进度条 ──
    var isPlaying by remember { mutableStateOf(true) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val p = playerRef.value
            if (p != null) {
                durationMs = p.duration.coerceAtLeast(0L)
                if (!isDragging) positionMs = p.currentPosition.coerceAtLeast(0L)
                isPlaying = p.isPlaying
            }
            kotlinx.coroutines.delay(300)
        }
    }
    fun togglePlayPause() {
        val p = playerRef.value ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
            p.play()
        }
    }
    fun fmtMs(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { togglePlayPause() },
        contentAlignment = Alignment.Center
    ) {
        // 按比例缩放并完整放进屏幕：竖屏视频不再溢出屏幕，横屏视频铺满宽度、上下留黑
        val aspect by animateFloatAsState(
            targetValue = videoAspectRatio,
            animationSpec = tween(220),
            label = "communityVideoAspect"
        )
        val videoWidth = minOf(maxWidth, maxHeight * aspect)
        val videoHeight = videoWidth / aspect
        val textureModifier = Modifier.size(videoWidth, videoHeight)

        // 视频画面（TextureView + ExoPlayer，复用项目 OkHttp 磁盘缓存：流式/拖动/弱网不卡）
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                android.view.TextureView(context).apply {
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            try {
                                playerRef.value?.release()
                                // ExoPlayer：复用项目 OkHttp 磁盘缓存（createExoPlayer 内部），
                                // 流式分片、可拖动、弱网不卡，与短视频内核一致。
                                val p = com.aurora.chat.ui.common.createExoPlayer(context, url, false)
                                // 社区帖子的视频不循环：播完即停，避免播完了还在放/还有声音
                                p.repeatMode = Player.REPEAT_MODE_OFF
                                p.setVideoTextureView(this@apply)
                                p.addListener(object : Player.Listener {
                                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                                        val w = videoSize.width
                                        val h = videoSize.height
                                        if (w > 0 && h > 0) {
                                            // 录屏常带旋转元数据（90°/270°）：不换算的话会把竖屏算成横屏，
                                            // 画面被 TextureView 拉伸成“扁扁的横屏”。
                                            val rotated = videoSize.unappliedRotationDegrees == 90 ||
                                                videoSize.unappliedRotationDegrees == 270
                                            videoAspectRatio = if (rotated) h.toFloat() / w.toFloat()
                                            else w.toFloat() / h.toFloat()
                                        }
                                    }
                                    override fun onRenderedFirstFrame() {
                                        // 真正画出第一帧才撤封面（短视频同款逻辑）
                                        showPoster = false
                                        isLoading = false
                                    }
                                    override fun onPlaybackStateChanged(state: Int) {
                                        isLoading = state == Player.STATE_BUFFERING
                                    }
                                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                        android.util.Log.e("VideoPlayer", "播放错误: code=${error.errorCode} ${error.message} url=$url", error)
                                        isLoading = false
                                        hasError = true
                                    }
                                })
                                playerRef.value = p
                            } catch (e: Exception) {
                                android.util.Log.e("VideoPlayer", "加载失败: $url", e)
                                isLoading = false
                                hasError = true
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                            runCatching { playerRef.value?.release() }
                            playerRef.value = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                            if (showPoster) showPoster = false
                        }
                    }
                }
            },
            modifier = textureModifier
        )

        // 封面盖在黑屏上，首帧解码上屏后自动撤掉，避免「进全屏先黑一下」
        if (showPoster && poster != null) {
            Image(
                bitmap = poster.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(videoWidth, videoHeight),
                contentScale = ContentScale.Fit
            )
        }

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
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
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

        // 顶栏：关闭（overlay 现在铺到状态栏底下，关闭按钮要避开状态栏）
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "关闭",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
                .clickable { onDismiss() }
        )

        // ── 底部控制条：播放/暂停 + 进度条 + 时间 + 下载 ──
        if (!hasError) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .background(Color.Black.copy(alpha = 0.38f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { togglePlayPause() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    text = fmtMs(if (isDragging) (dragFraction * durationMs).toLong() else positionMs),
                    fontSize = 11.sp,
                    color = Color.White
                )
                Slider(
                    value = if (durationMs > 0) {
                        if (isDragging) dragFraction
                        else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else 0f,
                    onValueChange = {
                        isDragging = true
                        dragFraction = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        val p = playerRef.value
                        if (p != null && durationMs > 0) p.seekTo((dragFraction * durationMs).toLong())
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                    )
                )
                Text(fmtMs(durationMs), fontSize = 11.sp, color = Color.White)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.22f))
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
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.FileDownload,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("下载", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
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
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(com.aurora.chat.ui.theme.appCardBg()).padding(12.dp),
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
                Text(resource.file_name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    ext == "apk" -> "APK安装包"
                    ext in listOf("zip", "rar", "7z") -> "ZIP压缩包"
                    isAudio -> "音频"
                    else -> formatFileSizeInner(resource.file_size)
                }, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(com.aurora.chat.ui.theme.appCardBg()).padding(12.dp),
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
            color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        // 下载按钮
        Box(
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(com.aurora.chat.ui.theme.appTextSecondary())
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

    Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶栏
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", fontSize = 20.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onDismiss() }.padding(end = 8.dp))
                Text(fileName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            if (isLoading && content.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (errorMsg.isNotEmpty()) errorMsg else "加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else {
                Box(Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp)) {
                    Column {
                        SelectionContainer {
                            Text(content, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        if (hasMore || (prefetchedContent.isNotEmpty() && content.isNotEmpty())) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(com.aurora.chat.ui.theme.appCardBg())
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
        // 录屏/手机拍摄常用旋转元数据存竖屏视频：直接取帧会得到“横躺”的画面，
        // 导致全屏先用横版比例、再被拉伸。这里按元数据回正，封面比例第一时间就是对的。
        val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        mmr.release()
        if (bmp != null && rotation % 360 != 0) {
            android.graphics.Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true
            )
        } else bmp
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

// 从 Compose 拿到的 Context（通常是 ContextThemeWrapper）里解出 Activity，用于改系统栏图标颜色
private fun Context.findActivityOrNull(): android.app.Activity? {
    var c: Context? = this
    while (c != null) {
        if (c is android.app.Activity) return c
        c = (c as? android.content.ContextWrapper)?.baseContext
    }
    return null
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
                    drawPath(path, color = if (hasLiked) Color(0xFFEF4444) else com.aurora.chat.ui.theme.appTextHint())
                }
                if (animProgress > 0f) {
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(400)
                        animProgress = 0f
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Text("$likesCount", fontSize = 12.sp, color = if (hasLiked) Color(0xFFEF4444) else com.aurora.chat.ui.theme.appTextHint())
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

/** 评论看过的内容落盘：下次打开帖子先显示缓存，再静默刷新。 */
private fun loadCachedComments(ctx: android.content.Context, postId: Long): Pair<List<CommentInfo>, Int>? {
    return try {
        val raw = ctx.getSharedPreferences("community_comment_cache", android.content.Context.MODE_PRIVATE)
            .getString("c$postId", null) ?: return null
        val obj = org.json.JSONObject(raw)
        val arr = obj.optJSONArray("comments") ?: org.json.JSONArray()
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CommentInfo(
                id = o.getLong("id"),
                user_id = o.getLong("user_id"),
                username = o.optString("username"),
                content = o.optString("content"),
                created_at = o.optLong("created_at"),
                parent_id = o.optLong("parent_id"),
                reply_count = o.optInt("reply_count"),
                like_count = o.optInt("like_count"),
                liked = o.optBoolean("liked")
            )
        }
        list to obj.optInt("total", list.size)
    } catch (_: Exception) { null }
}

private fun saveCachedComments(ctx: android.content.Context, postId: Long, comments: List<CommentInfo>, total: Int) {
    try {
        val arr = org.json.JSONArray()
        comments.forEach { c ->
            arr.put(org.json.JSONObject().apply {
                put("id", c.id); put("user_id", c.user_id)
                put("username", c.username); put("content", c.content)
                put("created_at", c.created_at); put("parent_id", c.parent_id)
                put("reply_count", c.reply_count); put("like_count", c.like_count)
                put("liked", c.liked)
            })
        }
        val obj = org.json.JSONObject().apply {
            put("total", total); put("comments", arr)
        }
        ctx.getSharedPreferences("community_comment_cache", android.content.Context.MODE_PRIVATE)
            .edit().putString("c$postId", obj.toString()).apply()
    } catch (_: Exception) {}
}

@Composable
private fun CommentSheet(
    postId: Long,
    sheetHeightFraction: Float = 0.5f,
    onHeightChange: (Float) -> Unit = {},
    onDismiss: () -> Unit,
    onCommentAdded: () -> Unit,
    onUserClick: (Long) -> Unit = {},
    expandedReplies: Set<Long> = emptySet(),
    onToggleExpand: (Long) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 先同步读磁盘缓存：看过的评论下次打开立即显示，随后再静默联网刷新
    val cachedComments = remember(postId) { loadCachedComments(ctx, postId) }
    var allComments by remember { mutableStateOf<List<CommentInfo>>(cachedComments?.first ?: emptyList()) }
    var total by remember { mutableStateOf(cachedComments?.second ?: 0) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(cachedComments == null) }
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
    // 平台管理员身份（可删他人评论）：进面板查一次
    var isPlatformAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 禁言检测（自查接口，所有用户可用）：仅「社区禁言(1) / 全部禁言(3)」在社区生效，
        // 对话禁言/短视频禁言不影响社区评论区（三种禁言互相独立）
        try {
            val r = com.aurora.chat.data.api.AuroraApi.checkMuteStatus()
            if (r.success && r.data != null) {
                val expiresAt = r.data.optLong("expires_at", 0L)
                val muteType = r.data.optInt("mute_type", 0)
                if (expiresAt > 0 && (muteType == 1 || muteType == 3)) {
                    mutedUntil = expiresAt
                }
            }
        } catch (_: Exception) { }
        // 平台管理员身份（可删他人评论）：进面板查一次
        isPlatformAdmin = runCatching {
            com.aurora.chat.data.api.AuroraApi.isPlatformAdmin().data == true
        }.getOrDefault(false)
    }

    // ── 评论点赞 / 删除（后端已接入，真实生效） ──
    // 待确认删除的评论（长按/点"删除"触发）
    var pendingDeleteComment by remember { mutableStateOf<CommentInfo?>(null) }
    val loginPrefs = ctx.getSharedPreferences("aurora_login", Context.MODE_PRIVATE)
    val isDevUser = com.aurora.chat.data.local.LocalStorage.isDeveloper(
        loginPrefs.getString("user_qq", "") ?: "",
        loginPrefs.getString("user_email", "") ?: ""
    )
    /** 删除权限：作者本人 / 开发者 / 平台管理员 */
    fun canDeleteComment(c: CommentInfo): Boolean =
        c.user_id == com.aurora.chat.data.api.AuroraApi.currentUserId || isDevUser || isPlatformAdmin
    /** 点赞/取消点赞某条评论（toggle），成功后本地同步最新状态 */
    fun onLikeComment(c: CommentInfo) {
        scope.launch {
            val r = ChatRepository.likeComment(c.id)
            if (r.success && r.data != null) {
                val liked = r.data.optBoolean("liked", false)
                val lc = r.data.optInt("like_count", 0)
                allComments = allComments.map { if (it.id == c.id) it.copy(liked = liked, like_count = lc) else it }
            } else {
                android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    /** 长按/点删除：无权时提示；有权时弹确认框（后端会级联删除其下所有回复） */
    fun onRequestDeleteComment(c: CommentInfo) {
        if (!canDeleteComment(c)) {
            android.widget.Toast.makeText(ctx, "只能删除自己的评论", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pendingDeleteComment = c
    }
    fun refreshComments() {
        scope.launch {
            val refreshed = ChatRepository.getComments(postId)
            if (refreshed.success && refreshed.data != null) {
                allComments = refreshed.data!!.comments
                total = refreshed.data!!.total
                saveCachedComments(ctx, postId, allComments, total)
            }
        }
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
            saveCachedComments(ctx, postId, allComments, total)
        }
        isLoading = false
    }

    // 键盘高度：直接用 WindowInsets.ime —— 系统会在键盘动画的每一帧同步更新 inset 值，
    // 面板直接跟键盘 100% 同步，零延迟零空档。
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

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
        Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset(0, -imeBottomPx) },
        contentAlignment = Alignment.BottomCenter
    ) {
        var dragFraction by remember { mutableFloatStateOf(sheetHeightFraction) }
        var isDragging by remember { mutableStateOf(false) }
        LaunchedEffect(sheetHeightFraction) { if (!isDragging) dragFraction = sheetHeightFraction }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(dragFraction)
                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}
                // 只留底部导航栏内边距。曾经用 systemBarsPadding()：面板并不顶到状态栏，
                // 却被塞进一整条状态栏高度，于是拖拽条上方空出一大块白（截图里的红框就是它）。
                .navigationBarsPadding()
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
            Text("评论 ($total)", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Text("关闭", fontSize = 14.sp, color = Color(0xFF1E40AF),
                modifier = Modifier.clickable { onDismiss() })
        }


        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

        // 评论列表
        if (isLoading) {
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(top = 20.dp))
            }
        } else if (topLevelComments.isEmpty()) {
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("暂无评论，快去抢沙发吧", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(top = 20.dp))
            }
        } else {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                state = listState
            ) {
                items(topLevelComments, key = { it.id }) { comment ->
                    val replyList = repliesByRoot[comment.id] ?: emptyList()
                    // 不包 animateItem()：展开/收回是「item 自身高度渐变」，AnimatedVisibility 已逐帧驱动，
                    // animateItem 会在此基础上叠加 placement 补间，与内部渐变动画互相抢夺 → 抖动/发木。
                    // 后续评论靠前一条每帧长高的自然重排平滑下移，观感反而更连贯。
                    CommentRow(
                        comment = comment,
                        isExpanded = comment.id in expandedReplies,
                        replyCount = replyList.size,
                        replies = replyList,
                        parentUsernameMap = parentUsernameMap,
                        parentUserIdMap = parentUserIdMap,
                        onUserClick = onUserClick,
                        onToggleExpand = { onToggleExpand(comment.id) },
                        canDeleteFor = { canDeleteComment(it) },
                        onLike = { onLikeComment(it) },
                        onLongPress = { onRequestDeleteComment(it) },
                        onReplyClick = { id ->
                            replyingTo = parentUsernameMap[id] ?: ""
                            replyingToId = id
                        }
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

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
                    .background(com.aurora.chat.ui.theme.appCardBg())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = {
                        if (!isMuted) inputText = it
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = if (isMuted) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextPrimary()),
                    singleLine = true,
                    readOnly = isMuted,
                    cursorBrush = SolidColor(if (isMuted) Color.Transparent else Color(0xFF1E40AF)),
                    decorationBox = { inner ->
                        Box {
                            val placeholder = if (replyingTo.isNotEmpty()) "回复${replyingTo}:" else if (isMuted) "你已被禁言" else "发送有爱评论"
                            if (inputText.isEmpty()) Text(placeholder, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            inner()
                        }
                    }
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (isMuted) "禁言中" else "发送",
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = if (isMuted) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF1E40AF),
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
                                saveCachedComments(ctx, postId, allComments, total)
                            }
                            replyingToId = 0L
                            onCommentAdded()
                            // 确保所属顶层评论线程处于展开状态（幂等）：已展开时不动它。
                            // 旧写法用 toggle——若用户是在已展开的线程里回复子评论，会把整串子回复收起，
                            // 自己的新回复也被藏起来，表现为"回复了一下，子回复全被收回"。
                            if (parentId > 0L) {
                                val rootId = rootIdOfId(parentId)
                                if (rootId > 0L && rootId !in expandedReplies) onToggleExpand(rootId)
                            }
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
                    .background(com.aurora.chat.ui.theme.appCardBg(), CircleShape)
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
                    drawPath(path, com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        }

        // 删除评论确认（自己/管理员）：后端会级联删除该评论下的所有回复，不留幽灵数据
        val delTarget = pendingDeleteComment
        if (delTarget != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingDeleteComment = null },
                title = { Text("删除评论", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = { Text("确定删除这条评论吗？删除后其下的所有回复也会一并删除。", fontSize = 14.sp) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        pendingDeleteComment = null
                        val delId = delTarget.id
                        scope.launch {
                            val r = ChatRepository.deleteComment(delId)
                            if (r.success) {
                                refreshComments()
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (r.data != null && r.data > 1) "已删除 ${r.data} 条评论" else "已删除",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("删除", color = Color(0xFFDC2626)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingDeleteComment = null }) { Text("取消") }
                }
            )
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
    onToggleExpand: () -> Unit = {},
    /** 是否有权删除该评论（作者本人 / 开发者 / 平台管理员）——按具体评论判断，回复行用回复自身 */
    canDeleteFor: (CommentInfo) -> Boolean = { false },
    /** 点赞/取消点赞该评论（toggle） */
    onLike: (CommentInfo) -> Unit = {},
    /** 长按请求删除（调用方负责权限提示与确认弹窗） */
    onLongPress: (CommentInfo) -> Unit = {}
) {
    val ctx = LocalContext.current
    var avatarBmp by remember(comment.user_id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(comment.user_id) {
        withContext(Dispatchers.IO) {
            avatarBmp = ChatRepository.loadAvatar(ctx, comment.user_id)
        }
    }
    // 高度变化全部交给下方两个 AnimatedVisibility 逐帧驱动，不再套 animateContentSize：
    // 它会把 AnimatedVisibility 渐变的「高度目标」再补间一次（目标每帧在动），动画起步发木、中段变慢。
    Column(modifier = Modifier.fillMaxWidth()) {
        // 主评论行
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { onReplyClick(comment.id) },
                onLongClick = { onLongPress(comment) }
            ),
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
                    Text(comment.username, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.width(8.dp))
                    Text(formatTimeDetail(comment.created_at), fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
                Spacer(Modifier.height(4.dp))
                // 点击文字也能触发回复：把 onClick 交给 UrlText 自身处理，
                // 去掉外层 SelectionContainer（它会拦截点击手势，导致点文字不触发回复）。
                // selectable=true 保留长按选择文本的能力。
                UrlText(
                    text = comment.content,
                    fontSize = 14.sp,
                    defaultColor = com.aurora.chat.ui.theme.appTextSecondary(),
                    selectable = true,
                    onClick = { onReplyClick(comment.id) }
                )
                // 操作行：点赞（toggle）/ 回复 / 删除（仅有权时显示）
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onLike(comment) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.ThumbUp,
                            contentDescription = "点赞",
                            tint = if (comment.liked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint(),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            if (comment.like_count > 0) "${comment.like_count}" else "点赞",
                            fontSize = 11.sp,
                            color = if (comment.liked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint()
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "回复",
                        fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onReplyClick(comment.id) }
                            .padding(vertical = 4.dp)
                    )
                    if (canDeleteFor(comment)) {
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "删除",
                            fontSize = 11.sp, color = Color(0xFFEF4444),
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { onLongPress(comment) }
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // 展开N条回复按钮（有回复时才显示，展开状态下隐藏，用右下角的收回替代）
        // 节奏与下方回复列表对齐：展开时按钮快速让位(140ms)后列表向下长出(240ms)；
        // 收回时列表先折回(220ms)，按钮延迟 120ms 再淡入，避免过早占位把上方内容顶跳。
        AnimatedVisibility(
            visible = replyCount > 0 && !isExpanded,
            enter = expandVertically(tween(140, easing = androidx.compose.animation.core.FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(120, delayMillis = 120)),
            exit = shrinkVertically(tween(140, easing = androidx.compose.animation.core.FastOutLinearInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(90))
        ) {
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

        // 回复列表（手风琴式展开：锚点固定在顶部，高度向下长出来 / 向上折回去）
        // 旧实现用 slideInVertically(initialOffsetY = it/2)：容器高度瞬间变满，内容却从下方半个身位往上滑，
        // 于是"展开=从下往上弹、收回=从上往下掉"，方向和手风琴的直觉相反。
        // 改为 expandVertically/shrinkVertically 且都锚 Alignment.Top：顶边不动，内容自上而下逐帧铺开，
        // 收回时从底部往上折回，配合淡入淡出消除生硬感。
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ) + fadeIn(tween(180, delayMillis = 40)),
            exit = shrinkVertically(
                animationSpec = tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                shrinkTowards = Alignment.Top
            ) + fadeOut(tween(140))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp)
            ) {
                replies.forEach { reply ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { onReplyClick(reply.id) },
                                onLongClick = { onLongPress(reply) }
                            ),
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
                                Text(reply.username, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                                if (reply.parent_id > 0L) {
                                    val repliedName = parentUsernameMap[reply.parent_id] ?: ""
                                    val repliedUserId = parentUserIdMap[reply.parent_id] ?: 0L
                                    if (repliedName.isNotEmpty()) {
                                        Text(" 回复 ", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                        Text(repliedName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                            modifier = Modifier.clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) { if (repliedUserId > 0L) onUserClick(repliedUserId) })
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(formatTimeDetail(reply.created_at), fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                            Spacer(Modifier.height(2.dp))
                            // 点击文字也能触发回复（原理同上）
                            UrlText(
                                text = reply.content,
                                fontSize = 13.sp,
                                defaultColor = com.aurora.chat.ui.theme.appTextSecondary(),
                                selectable = true,
                                onClick = { onReplyClick(reply.id) }
                            )
                            // 操作行：点赞 / 回复 / 删除（仅有权时显示）
                            Row(
                                modifier = Modifier.padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { onLike(reply) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        androidx.compose.material.icons.Icons.Filled.ThumbUp,
                                        contentDescription = "点赞",
                                        tint = if (reply.liked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint(),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        if (reply.like_count > 0) "${reply.like_count}" else "点赞",
                                        fontSize = 10.sp,
                                        color = if (reply.liked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint()
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "回复",
                                    fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                                    modifier = Modifier
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { onReplyClick(reply.id) }
                                        .padding(vertical = 4.dp)
                                )
                                if (canDeleteFor(reply)) {
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "删除",
                                        fontSize = 10.sp, color = Color(0xFFEF4444),
                                        modifier = Modifier
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) { onLongPress(reply) }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                    // 子评论之间仅分割线
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider().copy(alpha = 0.4f)))
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
