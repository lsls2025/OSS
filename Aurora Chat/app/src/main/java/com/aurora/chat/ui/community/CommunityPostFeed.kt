package com.aurora.chat.ui.community
import com.aurora.chat.ui.theme.LocalAppColors

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import coil.compose.AsyncImage
import com.aurora.chat.data.api.AuroraApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import com.aurora.chat.R
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
import com.aurora.chat.data.api.PostListItem
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.material3.AlertDialog

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
    onOpenGroupProfile: (Long, String) -> Unit = { _, _ -> },
    refreshTrigger: Int = 0,
    mine: Boolean = false
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val presenter = remember { CommunityFeedPresenter(scope, ctx, mine = mine) }
    val state by presenter.uiState.collectAsState()
    val listState = rememberLazyListState()

    // ===== 微信式过滚动（与"我的"/"联系人"同一套手感）=====
    // 信息流单独整体位移，顶部搜索框/AuroraTopBar 都在这个容器之外，所以标题不会被拽走。
    var overscrollPx by remember { mutableFloatStateOf(0f) }
    val overscrollStartFactor = 0.82f
    val density = LocalDensity.current
    val overscrollSoftPx = with(density) {
        (240.dp.toPx() * overscrollStartFactor).coerceAtLeast(1f)
    }
    // 与聊天页同阈值：松手后标题右侧出转圈
    val refreshThresholdPx = with(density) { 72.dp.toPx() }
    val overscrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (overscrollPx > 0f && available.y < 0f) {
                    val next = (overscrollPx + available.y).coerceAtLeast(0f)
                    val consumed = next - overscrollPx
                    overscrollPx = next
                    return Offset(0f, consumed)
                }
                if (overscrollPx < 0f && available.y > 0f) {
                    val next = (overscrollPx + available.y).coerceAtMost(0f)
                    val consumed = next - overscrollPx
                    overscrollPx = next
                    return Offset(0f, consumed)
                }
                val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                val atBottom = !listState.canScrollForward
                if ((available.y > 0f && atTop) || (available.y < 0f && atBottom)) {
                    val factor = overscrollStartFactor *
                        kotlin.math.exp(-kotlin.math.abs(overscrollPx) / overscrollSoftPx)
                    overscrollPx += available.y * factor
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (overscrollPx != 0f) available else Velocity.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val released = overscrollPx
                if (released != 0f) {
                    animate(
                        initialValue = released,
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 700f)
                    ) { value, _ -> overscrollPx = value }
                }
                // 拽够距离松手 = 想刷新：松手立即开始，转圈显示在「社区」标题右侧，
                // 刷新完成（含失败）再收起，最短可见时长由 CommunityRefreshState 控制。
                if (released > refreshThresholdPx) {
                    com.aurora.chat.ui.components.CommunityRefreshState.start()
                    presenter.refresh {
                        com.aurora.chat.ui.components.CommunityRefreshState.finish()
                    }
                }
                return Velocity.Zero
            }
        }
    }

    // 社区列表液态开关已随液态模式移除（恒经典社区）。

    // 删除确认弹窗的目标帖子：提升到根层渲染。
    // 若把 LiquidDialog 放在 PostCard（LazyColumn 的 item）里，其 fillMaxSize 覆盖层会被
    // item 约束成卡片大小 —— 弹窗会"塞进卡片里"，既奇怪又无法全屏。故在此持有状态，根层渲染。
    var deleteTarget by remember { mutableStateOf<PostListItem?>(null) }
    // 分享面板目标帖：由 PostCard 三点菜单回调置入，提升到 feed 页级渲染（卡片带 clip，内嵌会被裁）。
    var sharePostTarget by remember { mutableStateOf<PostListItem?>(null) }

    var searchText by remember { mutableStateOf("") }
    var firstLoad by remember { mutableStateOf(true) }


    // 切出界面再切入 -> 恢复被暂时隐藏的发布按钮
    LaunchedEffect(Unit) { CommunityFabAnchor.hidden = false }


    // 首屏：若已带外部刷新信号（切入社区 / 发布成功），交给下面的 refresh 统一处理，避免重复请求
    LaunchedEffect(Unit) { if (refreshTrigger == 0) presenter.loadFirst() }

    // 关键字搜索（带 350ms 防抖）；首次空串跳过，避免与首屏重复请求
    LaunchedEffect(searchText) {
        if (firstLoad) {
            firstLoad = false
            return@LaunchedEffect
        }
        delay(350)
        presenter.search(searchText.trim())
    }

    // 切入社区 / 发布成功 → 自动刷新；刷新后若确有新内容落在顶部，才带动画滑回顶部
    // （无新内容则保持当前滚动位置，不打扰浏览）。
    // 注意顺序：必须先刷新、后滚动。若先滚到顶再刷新，新帖插入顶部时锚点帖整体下移，
    // 用户依旧不在顶部（旧实现就是这个 bug：切回来还得自己往上滑）。
    // 数量优化：少量内容平滑回顶，大量内容瞬移回顶（避免逐条滚过几百上千项造成卡顿）。
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            presenter.refresh { hasNewAtTop ->
                if (!hasNewAtTop) return@refresh
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) return@refresh
                scope.launch {
                    val count = presenter.uiState.value.posts.size
                    if (count in 1..40) {
                        listState.animateScrollToItem(0)
                    } else {
                        listState.scrollToItem(0)
                    }
                }
            }
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
        // 经典模式：白底 + 顶部 Row 搜索框
        Column(
            Modifier
                .fillMaxSize()
                .background(com.aurora.chat.ui.theme.appSurface())
        ) {
            // ── 顶部 Row ──
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
                        scope.launch { listState.scrollToItem(0) }
                    }
                )
            }

            Box(Modifier.fillMaxSize()) {
                // 信息流位移容器：过滚动只作用于列表本身，搜索框/标题栏在这个容器之外，不受影响
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .nestedScroll(overscrollConnection)
                        .offset { IntOffset(0, overscrollPx.roundToInt()) }
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 0.dp,
                            end = 0.dp,
                            top = 0.dp,
                            bottom = 80.dp
                        )
                    ) {
                        itemsIndexed(state.posts, contentType = { _, _ -> "post" }, key = { _, post -> post.id }) { index, post ->
                        LaunchedEffect(post.id) { presenter.markSeen(post.id) }
                        Column {
                            PostCard(
                                post = post,
                                currentUserId = currentUserId,
                                onClick = { onPostClick(post.id) },
                                onUserClick = { onUserClick(post.user_id, post.username) },
                                onUserCardClick = { uid, name -> onUserClick(uid, name) },
                                onOpenGroupProfile = onOpenGroupProfile,
                                onRequestDelete = { deleteTarget = it },
                                onRequestShare = { sharePostTarget = it },
                                topPadding = if (index == 0) 4.dp else 12.dp
                            )
                            if (index < state.posts.lastIndex) {
                                HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider(), thickness = 0.5.dp)
                            }
                        }
                    }

                    // 底部状态行
                    when {
                        state.loadState == FeedLoadState.LoadingMore -> {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text("加载中...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        }
                        state.loadState == FeedLoadState.Empty && state.posts.isEmpty() -> {
                            item {
                                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                                    Text("暂无内容，点击右下角 + 发布", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        }
                        !state.hasMore && state.posts.isNotEmpty() -> {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text("没有更多了", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(bottom = CommunityFabAnchor.bottomInset),
                enter = slideInHorizontally(tween(280)) { it },
                exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(280))
            ) {
                    // 经典：深蓝圆 + 白色「+」
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

            // ── 删除确认弹窗（根层全屏覆盖层；折射社区背景的彩色玻璃） ──
            val target = deleteTarget
            if (target != null) {
                AlertDialog(
                    onDismissRequest = { deleteTarget = null },
                    title = { Text("确认删除", fontWeight = FontWeight.SemiBold) },
                    text = { Text("确定要删除「${target.title}」吗？此操作不可撤销。", fontSize = 14.sp) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                deleteTarget = null
                                scope.launch {
                                    val result = ChatRepository.deleteCommunityPost(target.id)
                                    if (result.success) {
                                        android.widget.Toast.makeText(ctx, "删除成功", android.widget.Toast.LENGTH_SHORT).show()
                                        presenter.removePost(target.id)
                                    } else {
                                        android.widget.Toast.makeText(ctx, result.message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        ) {
                            Text("删除", color = Color(0xFFEF4444))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) {
                            Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                        }
                    }
                )
            }

            // ── 分享面板（内嵌全屏覆盖层，与帖子详情页同一形态：无 Dialog 系统 dim，
            //    避免与面板自带遮罩叠加导致顶部发亮/下方发暗的色差） ──
            val shareTarget = sharePostTarget
            // 全限定名调用，避免解析到 ColumnScope.AnimatedVisibility 重载（同下方 FAB 的用法）
            androidx.compose.animation.AnimatedVisibility(
                visible = shareTarget != null,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                if (shareTarget != null) {
                    SharePostDialog(
                        postTitle = shareTarget.title,
                        postContent = shareTarget.content,
                        postId = shareTarget.id,
                        postUserId = shareTarget.user_id,
                        postUsername = shareTarget.username,
                        postType = shareTarget.post_type,
                        onDismiss = { sharePostTarget = null }
                    )
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
                    .background(com.aurora.chat.ui.theme.appCardBg())
                    .clickable { filterExpanded = true }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(sortMode.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF4B5563))
            }
            DropdownMenu(
                expanded = filterExpanded,
                onDismissRequest = { filterExpanded = false },
                modifier = Modifier.background(com.aurora.chat.ui.theme.appSurface())
            ) {
                FeedSortMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = mode.label,
                                fontSize = 14.sp,
                                fontWeight = if (mode == sortMode) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (mode == sortMode) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextSecondary()
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
                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(8.dp))
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true, interactionSource = interactionSource,
                cursorBrush = SolidColor(Color(0xFF1E40AF)),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSearch(value.trim()) }),
                decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text("搜索社区内容", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
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
    /** 底部预留高度：经典模式恒为 0（底栏是内嵌占位，内容本就上移，无需额外抬高）。 */
    var bottomInset by mutableStateOf(0.dp)
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
    onUserCardClick: (Long, String) -> Unit = { _, _ -> },
    onOpenGroupProfile: (Long, String) -> Unit = { _, _ -> },
    onRequestDelete: (PostListItem) -> Unit,
    onRequestShare: (PostListItem) -> Unit = {},
    // 顶部内边距可调：列表第一条用小值，紧贴搜索栏；其余条目保持默认，维持条目间距
    topPadding: androidx.compose.ui.unit.Dp = 12.dp
) {
    val ctx = LocalContext.current
    // 敏感标记：后端持久化的 is_adult 为准，同时兼容早期只在本地标记过的老帖（AdultPostCache）。
    // 只要命中一条，预览区就只露标题，正文与图片一律不渲染。
    val isAdultPost = post.is_adult || remember(post.id) { com.aurora.chat.AdultPostCache.isAdult(post.id) }
    // 派生数据用 remember(post) 缓存：列表重发（如点赞数变化触发整卡重组）时不再重复计算
    val hasImage = remember(post) { post.res_types.contains("image") }
    val hasVideo = remember(post) { post.res_types.contains("video") }
    val hasAudio = remember(post) {
        post.res_types.contains("audio") || post.first_resource_url.let { url ->
            url.endsWith(".mp3") || url.endsWith(".aac") || url.endsWith(".wav") || url.endsWith(".ogg") ||
                url.endsWith(".flac") || url.endsWith(".m4a") || url.endsWith(".wma")
        }
    }
    // 是否含普通文件资源（用于卡片右下角"文件"角标）
    val hasFile = remember(post) {
        post.res_types.contains("file") || (post.res_count > 0 && !hasImage && !hasVideo && !hasAudio)
    }
    val imageUrls = remember(post) {
        buildList {
            post.image_urls.forEach { add(toThumbUrl(it)) }
            if (isEmpty() && post.first_resource_url.isNotBlank()) add(toThumbUrl(post.first_resource_url))
        }
    }
    var avatarBmp by remember(post.user_id) { mutableStateOf(AvatarLoader.getCached(post.user_id)) }
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(post.user_id) {
        if (avatarBmp == null) {
            avatarBmp = AvatarLoader.load(ctx, post.user_id)
        }
    }

    val canDelete = currentUserId == 1L || currentUserId == post.user_id

    // 文字配色：经典深色
    val titleColor = com.aurora.chat.ui.theme.appTextPrimary()
    val bodyColor = com.aurora.chat.ui.theme.appTextSecondary()
    val nameColor = Color(0xFF4B5563)
    val linkColor = Color(0xFF1E40AF)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = topPadding, bottom = 12.dp)
        ) {
            // 标题行 + 三点菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = post.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isAdultPost) {
                    Spacer(Modifier.width(6.dp))
                    Text("注意", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF4444).copy(alpha = 0.1f))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                }
                Box {
                    Text("⋮", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = bodyColor,
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
                                    onRequestDelete(post)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("分享", fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                // 分享面板提升到 feed 页级渲染：卡片自身带 clip，
                                // 内嵌会被裁成圆角内的小窗；同时统一走内嵌面板（与帖子详情一致），
                                // 避免 Dialog 系统 dim 与面板自带遮罩叠加造成顶部色差。
                                onRequestShare(post)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("收藏", fontSize = 14.sp) },
                            onClick = {
                                showMenu = false
                                // 图片/视频会在收藏时复制到本机，不依赖服务器 30 天清理后的原文件。
                                scope.launch {
                                    val r = com.aurora.chat.data.local.FavoritesStore.addPost(ctx, post)
                                    android.widget.Toast.makeText(
                                        ctx,
                                        when (r) {
                                            com.aurora.chat.data.local.FavoriteAddResult.ADDED -> "已收藏"
                                            com.aurora.chat.data.local.FavoriteAddResult.EXISTS -> "该帖子已在收藏中"
                                            com.aurora.chat.data.local.FavoriteAddResult.FAILED -> "收藏失败"
                                        },
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))

            // 内容预览（正文 + 挂载卡片，URL 自动高亮）
            val parts = remember(post.content) { extractPostCards(post.content) }
            // 敏感内容：预览区只允许露出标题，正文与图片一律不渲染（连 AsyncImage 都不创建，
            // 避免图片 URL 进入 Coil 缓存、也避免"展开前就能看到图"泄漏）。用一条遮罩提示代替空白。
            if (isAdultPost) {
                AdultPreviewMask()
            } else {
            PostContentWithCards(
                parts = PostContentParts(parts.body.take(120), parts.cards),
                onUserCardClick = { uid, name -> onUserCardClick(uid, name) },
                onGroupCardClick = onOpenGroupProfile,
                bodyFontSize = 12.sp,
                bodyLineHeight = 18.sp,
                bodyMaxLines = 2,
                bodyColor = bodyColor,
                linkColor = linkColor,
                bodyClickable = false,
                compactCards = true,
                horizontalCards = true
            )
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
                                .background(com.aurora.chat.ui.theme.appDivider()),
                            contentScale = ContentScale.Crop
                        )
                    }
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
                Text(post.username, fontSize = 12.sp, color = nameColor,
                    modifier = Modifier.clickable { onUserClick() })
                Spacer(Modifier.width(8.dp))
                Text(formatTime(post.created_at), fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())

                Spacer(Modifier.weight(1f))

                if (hasImage) {
                    ResourceBadge("图片", Color(0xFF2563EB))
                    Spacer(Modifier.width(6.dp))
                }
                if (hasVideo) {
                    ResourceBadge("视频", Color(0xFF7C3AED))
                    Spacer(Modifier.width(6.dp))
                }
                if (hasAudio) {
                    ResourceBadge("音频", Color(0xFF059669))
                    Spacer(Modifier.width(6.dp))
                }
                if (parts.cards.isNotEmpty()) {
                    ResourceBadge("卡片", Color(0xFF1E40AF))
                    Spacer(Modifier.width(6.dp))
                }
                if (hasFile) {
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
                        else -> "文件" to com.aurora.chat.ui.theme.appTextSecondary()
                    }
                    val badgeInfo = if (exts.isNotEmpty()) {
                        badgeFor(exts)
                    } else if (inferredExt != null) {
                        badgeFor(setOf(inferredExt))
                    } else {
                        "文件" to com.aurora.chat.ui.theme.appTextSecondary()
                    }
                        ResourceBadge(badgeInfo.first, badgeInfo.second)
                }
            }
        }
    }
}

/**
 * 敏感内容在「列表预览」里的遮罩条。
 *
 * 规则：标题可看，正文与图片不可看。这里不渲染任何正文文字、也不创建 AsyncImage，
 * 只给一条占位提示——既不是空洞的空白，也不会把内容提前泄漏出去。
 * 点整张卡片进入详情页，由详情页的敏感内容确认门禁决定是否放行。
 */
@Composable
private fun AdultPreviewMask() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(com.aurora.chat.ui.theme.appCardBg())
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "注意",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFEF4444),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFEF4444).copy(alpha = 0.1f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "内容已被标记，点击查看",
            fontSize = 12.sp,
            color = com.aurora.chat.ui.theme.appTextSecondary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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


