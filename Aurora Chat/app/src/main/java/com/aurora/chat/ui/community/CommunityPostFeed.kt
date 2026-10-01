package com.aurora.chat.ui.community

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.collection.LruCache
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import coil.compose.AsyncImage
import com.aurora.chat.data.api.AuroraApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.data.api.PostListItem
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// 帖子内容中 URL 高亮用的正则：提升为文件级常量，避免每个卡片每次重组都重新编译
private val URL_PATTERN = Regex(
    """(?:https?://)?www\.[a-zA-Z0-9-]+(?:\.[a-zA-Z]{2,})+(?:/[^\s]*)?|""" +
            """(?:https?://)?[a-zA-Z0-9][a-zA-Z0-9.-]*\.(?:com|cn|net|org|edu|gov|io|me|top|xyz|app|dev|info|cc|tv|co|uk|jp|de|ru|fr|au|ca|in|biz|pro|mobi|name|club|shop|online|site|space|live|wiki|store|blog|vip|fun|cloud|digital|world|work)(?:/[^\s]*)?""",
    RegexOption.IGNORE_CASE
)

/**
 * 社区信息流主体：搜索栏 + 下拉刷新 + 分页列表 + 触底加载 + 悬浮发布按钮。
 *
 * 本 Composable 本身不持有数据逻辑，所有加载状态来自 [CommunityFeedPresenter]（State Holder），
 * 只负责：渲染、把滚动/下拉/搜索事件转发给 presenter。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CommunityFeedBody(
    currentUserId: Long,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    onUserClick: (Long, String) -> Unit = { _, _ -> },
    onCreatePost: () -> Unit = {},
    onPostClick: (Long) -> Unit = {},
    refreshTrigger: Int = 0,
    mine: Boolean = false
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val presenter = remember { CommunityFeedPresenter(scope, ctx, mine = mine) }
    val state by presenter.uiState.collectAsState()
    val listState = rememberLazyListState()

    var searchText by remember { mutableStateOf("") }
    var firstLoad by remember { mutableStateOf(true) }

    // 切出界面再切入 -> 恢复被暂时隐藏的发布按钮
    LaunchedEffect(Unit) { CommunityFabAnchor.hidden = false }


    // 首屏立即加载
    LaunchedEffect(Unit) { presenter.loadFirst() }

    // 关键字搜索（带 350ms 防抖）；首次空串跳过，避免与首屏重复请求
    LaunchedEffect(searchText) {
        if (firstLoad) {
            firstLoad = false
            return@LaunchedEffect
        }
        delay(350)
        presenter.search(searchText.trim())
    }

    // 发布成功后外部通知刷新
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            listState.scrollToItem(0)
            presenter.refresh()
        }
    }

    // 触底加载更多（snapshotFlow 持续监控滚动位置，不受快速滑动跳过影响）
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            val s = presenter.uiState.value
            if (lastVisible >= total - 3 && s.hasMore && s.posts.isNotEmpty()) {
                presenter.loadMore()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            // ── 顶部：返回箭头（可选） + 搜索框 + 搜索按钮 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (showBack) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                Text(
                    "←", fontSize = 18.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onBack() }.padding(end = 8.dp)
                )
            }
            SearchBar(
                value = searchText,
                onValueChange = { searchText = it },
                onSearch = { searchText = it },
                sortMode = state.sortMode,
                onSortModeChange = { mode ->
                    presenter.setSortMode(mode)
                    // 切换排序后回到顶部，避免停留在原索引导致的“跑到底部/没回顶部”
                    scope.launch { listState.scrollToItem(0) }
                }
            )
        }

        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = state.loadState == FeedLoadState.Refreshing,
                onRefresh = { presenter.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.posts, contentType = { "post" }, key = { it.id }) { post ->
                        LaunchedEffect(post.id) { presenter.markSeen(post.id) }
                        PostCard(
                            post = post,
                            currentUserId = currentUserId,
                            onClick = { onPostClick(post.id) },
                            onUserClick = { onUserClick(post.user_id, post.username) },
                            onDeleted = { presenter.removePost(it) }
                        )
                    }

                    // 底部状态行
                    when {
                        state.loadState == FeedLoadState.LoadingMore -> {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text("加载中...", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                                }
                            }
                        }
                        state.loadState == FeedLoadState.Empty && state.posts.isEmpty() -> {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                                    Text("暂无内容，点击右下角 + 发布", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                }
                            }
                        }
                        !state.hasMore && state.posts.isNotEmpty() -> {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text("没有更多了", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                                }
                            }
                        }
                    }
                }
            }

            // 悬浮发布按钮（双击或长按可暂时隐藏：向右滑出收回；切出界面再切入恢复）
            // 全限定名调用，避免解析到 ColumnScope.AnimatedVisibility 重载
            androidx.compose.animation.AnimatedVisibility(
                visible = !CommunityFabAnchor.hidden,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = slideInHorizontally(tween(280)) { it },
                exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(280))
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 20.dp, bottom = 20.dp)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E40AF))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onCreatePost() },
                                onDoubleTap = { CommunityFabAnchor.hidden = true },
                                onLongPress = { CommunityFabAnchor.hidden = true }
                            )
                        }
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInRoot()
                            CommunityFabAnchor.centerInRoot = Offset(
                                pos.x + coords.size.width / 2f,
                                pos.y + coords.size.height / 2f
                            )
                            CommunityFabAnchor.radiusPx = coords.size.width / 2f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", fontSize = 28.sp, fontWeight = FontWeight.Light, color = Color.White)
                }
            }
        }
        }
    }
}

@Composable
private fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    sortMode: FeedSortMode,
    onSortModeChange: (FeedSortMode) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) Color(0xFF1E40AF) else Color(0xFFD1D5DB),
        animationSpec = tween(200)
    )
    var filterExpanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 筛选按钮：灰底描边，避免与右侧蓝色搜索按钮撞色
        Box {
            Box(
                modifier = Modifier
                    .height(36.dp).widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp))
                    .background(Color(0xFFF3F4F6))
                    .clickable { filterExpanded = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(sortMode.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF4B5563))
            }
            DropdownMenu(
                expanded = filterExpanded,
                onDismissRequest = { filterExpanded = false },
                modifier = Modifier.background(Color.White)
            ) {
                FeedSortMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = mode.label,
                                fontSize = 14.sp,
                                fontWeight = if (mode == sortMode) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (mode == sortMode) Color(0xFF1F2937) else Color(0xFF6B7280)
                            )
                        },
                        onClick = {
                            filterExpanded = false
                            onSortModeChange(mode)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f).defaultMinSize(minHeight = 36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = Color(0xFF1F2937)),
                singleLine = true, interactionSource = interactionSource,
                cursorBrush = SolidColor(Color(0xFF1E40AF)),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSearch(value.trim()) }),
                decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text("搜索社区内容", fontSize = 13.sp, color = Color(0xFF9CA3AF)); inner() }
                }
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier.height(36.dp).widthIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E40AF))
                .clickable { onSearch(value.trim()) },
            contentAlignment = Alignment.Center
        ) { Text("搜索", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White) }
    }
}

/**
 * 记录真实发布按钮在根布局中的位置，供全屏引导遮罩精准高亮。
 * 遮罩位于 Activity 根层级（要盖住顶栏与底栏），与按钮不在同一容器，
 * 因此不能靠 align 定位，必须实测坐标。
 */
internal object CommunityFabAnchor {
    var centerInRoot by mutableStateOf(Offset.Unspecified)
    var radiusPx by mutableStateOf(0f)
    /** 发布按钮被双击/长按暂时隐藏；离开界面再进入会恢复（不持久化） */
    var hidden by mutableStateOf(false)
}

/** 社区引导遮罩的持久化开关 */
internal object CommunityGuidePrefs {
    private const val FILE = "community_guide"
    private const val KEY_DISABLED = "guide_disabled"      // 已永久取消，不再显示
    private const val KEY_HIDDEN_ONCE = "guide_hidden_once" // 已被双击/长按隐藏过一次

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun isDisabled(ctx: Context) = prefs(ctx).getBoolean(KEY_DISABLED, false)
    fun setDisabled(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_DISABLED, true).apply()
    fun isHiddenOnce(ctx: Context) = prefs(ctx).getBoolean(KEY_HIDDEN_ONCE, false)
    fun setHiddenOnce(ctx: Context) = prefs(ctx).edit().putBoolean(KEY_HIDDEN_ONCE, true).apply()
}

/** 判断触点是否落在右下角发布按钮上（略放宽命中范围，便于手指操作） */
private fun isInFabArea(p: Offset): Boolean {
    val c = CommunityFabAnchor.centerInRoot
    val r = CommunityFabAnchor.radiusPx
    if (c == Offset.Unspecified || r <= 0f) return false
    val hit = r * 1.6f
    val dx = p.x - c.x
    val dy = p.y - c.y
    return dx * dx + dy * dy <= hit * hit
}

/**
 * 首次进入社区的引导遮罩（两阶段）：全屏暗层 + 高亮右下角发布按钮 + 中间提示文字。
 *
 * - 点击任意处 / 返回键：进入下一阶段；已是最后阶段则 [onFinish]（永久不再提示）。
 * - 在右下角发布按钮上双击或长按：[onHideButton]（隐藏该按钮，并结束引导）。
 */
@Composable
internal fun CommunityGuideOverlay(
    onFinish: () -> Unit,
    onHideButton: () -> Unit
) {
    var stage by remember { mutableStateOf(0) }

    // 点击任意处：阶段 0 -> 阶段 1；阶段 1 已是最後阶段 -> 永久结束
    val advance: () -> Unit = { if (stage == 0) stage = 1 else onFinish() }

    BackHandler { advance() }

    val fabCenter = CommunityFabAnchor.centerInRoot
    val fabRadius = CommunityFabAnchor.radiusPx

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .pointerInput(stage) {
                detectTapGestures(
                    onTap = { advance() },
                    onDoubleTap = { offset ->
                        if (isInFabArea(offset)) onHideButton() else advance()
                    },
                    onLongPress = { offset ->
                        if (isInFabArea(offset)) onHideButton()
                    }
                )
            }
    ) {
        // 高亮：按真实发布按钮的实测位置绘制（无白色外圈）
        if (fabCenter != Offset.Unspecified && fabRadius > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = Color(0xFF1E40AF), radius = fabRadius, center = fabCenter)
                // “+” 号
                val len = fabRadius * 0.45f
                val sw = 3.dp.toPx()
                drawLine(Color.White, Offset(fabCenter.x - len, fabCenter.y), Offset(fabCenter.x + len, fabCenter.y), sw, StrokeCap.Round)
                drawLine(Color.White, Offset(fabCenter.x, fabCenter.y - len), Offset(fabCenter.x, fabCenter.y + len), sw, StrokeCap.Round)
            }
        }

        // 中间提示文字（两阶段文案不同）
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (stage == 0) {
                    "在此处你可以上传资源或者你想上传的内容"
                } else {
                    "双击或长按按钮可暂时隐藏切出界面再切入将会恢复"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        }
    }
}

private fun toThumbUrl(raw: String): String {
    val rel = if (raw.startsWith("/api/community/files/")) raw.removePrefix("/api/community/files/")
              else raw.trimStart('/')
    return "${AuroraApi.serverUrl}/api/community/thumb/$rel?w=240"
}

@Composable
private fun PostCard(
    post: PostListItem,
    currentUserId: Long,
    onClick: () -> Unit,
    onUserClick: () -> Unit,
    onDeleted: (Long) -> Unit
) {
    val ctx = LocalContext.current
    // 派生数据用 remember(post) 缓存：列表重发（如点赞数变化触发整卡重组）时不再重复计算
    val hasImage = remember(post) { post.res_types.contains("image") }
    val hasVideo = remember(post) { post.res_types.contains("video") }
    val hasAudio = remember(post) {
        post.res_types.contains("audio") || post.first_resource_url.let { url ->
            url.endsWith(".mp3") || url.endsWith(".aac") || url.endsWith(".wav") || url.endsWith(".ogg") ||
                url.endsWith(".flac") || url.endsWith(".m4a") || url.endsWith(".wma")
        }
    }
    val imageUrls = remember(post) {
        buildList {
            post.image_urls.forEach { add(toThumbUrl(it)) }
            if (isEmpty() && post.first_resource_url.isNotBlank()) add(toThumbUrl(post.first_resource_url))
        }
    }
    var avatarBmp by remember(post.user_id) { mutableStateOf(AvatarLoader.getCached(post.user_id)) }
    var showMenu by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(post.user_id) {
        if (avatarBmp == null) {
            avatarBmp = AvatarLoader.load(ctx, post.user_id)
        }
    }

    val canDelete = currentUserId == 1L || currentUserId == post.user_id

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF8F9FA))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Column {
            // 标题行 + 三点菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = post.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (post.is_adult) {
                    Spacer(Modifier.width(6.dp))
                    Text("18+", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.1f))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                }
                Box {
                    Text("⋮", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFF6B7280),
                        modifier = Modifier
                            .clickable { showMenu = true }
                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp))
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text("删除", color = Color(0xFFEF4444), fontSize = 14.sp) },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("分享", fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                showShareDialog = true
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))

            // 内容预览（URL 高亮，不可点击以免干扰卡片点击）
            val preview = remember(post.content) { post.content.take(120).replace("\n", " ") }
            val displayText = remember(post.content) {
                buildAnnotatedString {
                    val pText = preview + if (post.content.length > 120) "..." else ""
                    var lastIdx = 0
                    for (m in URL_PATTERN.findAll(pText)) {
                        append(pText.substring(lastIdx, m.range.first))
                        withStyle(SpanStyle(color = Color(0xFF1E40AF), textDecoration = TextDecoration.Underline)) {
                            append(m.value)
                        }
                        lastIdx = m.range.last + 1
                    }
                    withStyle(SpanStyle(color = Color(0xFF6B7280))) {
                        append(pText.substring(lastIdx))
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = displayText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (imageUrls.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 注意：不能用 key = { it } —— 同一条动态的图片 URL 可能重复（如 first_resource_url 与 image_urls 重复），重复 key 会直接抛异常闪退；
                    // 用默认索引 key 最稳妥（图片行极少重排）
                    items(imageUrls) { url ->
                        val w = if (imageUrls.size == 1) 112.dp else 84.dp
                        val h = if (imageUrls.size == 1) 72.dp else 60.dp
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier.size(width = w, height = h)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFE5E7EB)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // 底部：用户信息 + 资源标记
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFD1D5DB))
                        .clickable { onUserClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBmp != null) {
                        Image(bitmap = avatarBmp!!.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(post.username.take(1), fontSize = 10.sp, color = Color.White)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(post.username, fontSize = 12.sp, color = Color(0xFF4B5563),
                    modifier = Modifier.clickable { onUserClick() })
                Spacer(Modifier.width(8.dp))
                Text(formatTime(post.created_at), fontSize = 11.sp, color = Color(0xFF9CA3AF))

                Spacer(Modifier.weight(1f))

                if (hasVideo) {
                    ResourceBadge("视频", Color(0xFF7C3AED))
                    Spacer(Modifier.width(6.dp))
                }
                if (hasAudio) {
                    ResourceBadge("音频", Color(0xFF059669))
                    Spacer(Modifier.width(6.dp))
                }
                if (post.res_count > 0 && !hasImage && !hasVideo && !hasAudio) {
                    val exts = post.res_exts.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                    val inferredExt = if (exts.isEmpty()) {
                        post.first_resource_url.substringAfterLast('.').lowercase()
                    } else null
                    fun badgeFor(extSet: Set<String>): Pair<String, Color> = when {
                        "apk" in extSet -> "安装包" to Color(0xFF16A34A)
                        "zip" in extSet || "rar" in extSet || "7z" in extSet -> "压缩包" to Color(0xFFD97706)
                        "pdf" in extSet -> "PDF" to Color(0xFFDC2626)
                        "py" in extSet -> "py源码" to Color(0xFF306998)
                        "c" in extSet || "h" in extSet -> "C源码" to Color(0xFF004481)
                        "txt" in extSet || "json" in extSet || "xml" in extSet || "html" in extSet ||
                            "css" in extSet || "js" in extSet || "md" in extSet || "log" in extSet ||
                            "csv" in extSet || "ini" in extSet || "cfg" in extSet || "properties" in extSet -> "文本" to Color(0xFF0891B2)
                        else -> "文件" to Color(0xFF6B7280)
                    }
                    val badgeInfo = if (exts.isNotEmpty()) {
                        badgeFor(exts)
                    } else if (inferredExt != null) {
                        badgeFor(setOf(inferredExt))
                    } else {
                        "文件" to Color(0xFF6B7280)
                    }
                    ResourceBadge(badgeInfo.first, badgeInfo.second)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定要删除「${post.title}」吗？此操作不可撤销。", fontSize = 14.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            val result = ChatRepository.deleteCommunityPost(post.id)
                            if (result.success) {
                                android.widget.Toast.makeText(ctx, "删除成功", android.widget.Toast.LENGTH_SHORT).show()
                                onDeleted(post.id)
                            } else {
                                android.widget.Toast.makeText(ctx, result.message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("删除", color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showShareDialog) {
        Dialog(
            onDismissRequest = { showShareDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize()) {
                SharePostDialog(
                    postTitle = post.title,
                    postContent = post.content,
                    postId = post.id,
                    postUserId = post.user_id,
                    postUsername = post.username,
                    postType = post.post_type,
                    onDismiss = { showShareDialog = false }
                )
            }
        }
    }
}

@Composable
private fun ResourceBadge(label: String, color: Color) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 头像加载器：内存 LruCache + 并发去重，避免列表滚动时同一用户头像被反复请求/解码。
 * 底层仍走 ChatRepository.loadAvatar（含磁盘缓存），此处叠加内存层进一步降低开销。
 */
private object AvatarLoader {
    private val memCache = LruCache<Long, Bitmap>(300)
    private val jobs = ConcurrentHashMap<Long, kotlinx.coroutines.Deferred<Bitmap?>>()

    fun getCached(userId: Long): Bitmap? = memCache.get(userId)

    suspend fun load(context: Context, userId: Long): Bitmap? {
        memCache.get(userId)?.let { return it }
        jobs[userId]?.let { return it.await() }
        val deferred = coroutineScope {
            async(Dispatchers.IO) { ChatRepository.loadAvatar(context, userId) }
        }
        jobs[userId] = deferred
        return try {
            val bmp = deferred.await()
            if (bmp != null) memCache.put(userId, bmp)
            bmp
        } finally {
            jobs.remove(userId)
        }
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0) return ""
    val diff = (System.currentTimeMillis() / 1000) - ts
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60}分钟前"
        diff < 86400 -> "${diff / 3600}小时前"
        else -> "${diff / 86400}天前"
    }
}
