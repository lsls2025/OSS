package com.aurora.chat.ui.community

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ActivityReadStateManager
import com.aurora.chat.PendingCommunityActivity
import com.aurora.chat.R
import com.aurora.chat.TcpService
import com.aurora.chat.data.api.CommunityActivity
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CommunityScreen(
    currentUserId: Long = 0,
    onOpenSubPage: (String) -> Unit = {},
    onOpenPostDetail: (Long, String) -> Unit = { _, _ -> },
    onOpenUserProfile: (Long, String) -> Unit = { _, _ -> },
    onOpenCreatePost: () -> Unit = {},
    refreshKey: Int = 0,
    onOpenChat: (Long, String) -> Unit = { _, _ -> }
) {
    CommunityMain(
        currentUserId = currentUserId,
        onOpenSubPage = onOpenSubPage,
        onOpenPostDetail = onOpenPostDetail,
        onOpenUserProfile = onOpenUserProfile,
        onOpenCreatePost = onOpenCreatePost,
        refreshKey = refreshKey,
        onOpenChat = onOpenChat
    )
}

/**
 * 「我的发布」全屏界面：复用社区信息流（仅拉取当前用户自己的帖子），
 * 搜索框、排序、下拉刷新、触底分页、悬浮发布按钮、帖子卡片全部原样复用，
 * 仅数据源通过 [CommunityFeedBody.mine] = true 改为服务端按当前用户过滤。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MyPostsScreen(
    currentUserId: Long,
    onBack: () -> Unit,
    onOpenPostDetail: (Long, String) -> Unit = { _, _ -> },
    onOpenUserProfile: (Long, String) -> Unit = { _, _ -> },
    onOpenCreatePost: () -> Unit = {},
    refreshKey: Int = 0
) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1E40AF),
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 12.dp)
            )
            Text(
                text = "我的发布",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F2937)
            )
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            CommunityFeedBody(
                currentUserId = currentUserId,
                showBack = false,
                onBack = onBack,
                onUserClick = { uid, name -> onOpenUserProfile(uid, name) },
                onCreatePost = onOpenCreatePost,
                onPostClick = { id -> onOpenPostDetail(id, "post") },
                refreshTrigger = refreshKey,
                mine = true
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.HONEYCOMB_MR2)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CommunityMain(
    currentUserId: Long,
    onOpenSubPage: (String) -> Unit,
    onOpenPostDetail: (Long, String) -> Unit,
    onOpenUserProfile: (Long, String) -> Unit,
    onOpenCreatePost: () -> Unit,
    refreshKey: Int = 0,
    onOpenChat: (Long, String) -> Unit
) {
    // 社区 Tab 直接进入信息流（不再显示中间卡片）
    CommunityHomeContent(
        currentUserId = currentUserId,
        showBack = false,
        onOpenPostDetail = onOpenPostDetail,
        onOpenUserProfile = onOpenUserProfile,
        onOpenCreatePost = onOpenCreatePost,
        refreshKey = refreshKey,
        onOpenChat = onOpenChat
    )
}

/**
 * 社区首页内容：搜索栏 + 信息流 + 发布/详情/资料弹层。
 * 社区 Tab 直接展示（无返回）；从动态等子页进入时带返回箭头复用同一套。
 */
@RequiresApi(Build.VERSION_CODES.HONEYCOMB_MR2)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CommunityHomeContent(
    currentUserId: Long,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    onOpenPostDetail: (Long, String) -> Unit = { _, _ -> },
    onOpenUserProfile: (Long, String) -> Unit = { _, _ -> },
    onOpenCreatePost: () -> Unit = {},
    refreshKey: Int = 0,
    onOpenChat: (Long, String) -> Unit = { _, _ -> }
) {
    // 帖子详情 / 用户资料 / 发帖统一交给 MainActivity 顶层全屏覆盖层渲染，
    // 这样能完整覆盖顶部标题栏与底部导航，而不是只盖住中间内容区。
    Box(Modifier.fillMaxSize()) {
        CommunityFeedBody(
            currentUserId = currentUserId,
            showBack = showBack,
            onBack = onBack,
            onUserClick = { uid, name -> onOpenUserProfile(uid, name) },
            onCreatePost = onOpenCreatePost,
            onPostClick = { id -> onOpenPostDetail(id, "post") },
            refreshTrigger = refreshKey
        )
    }
}

@Composable
private fun ActivityItemText(
    activity: CommunityActivity,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRead = remember { mutableStateOf(activity.is_read) }
    val ctx = LocalContext.current

    LaunchedEffect(activity.is_read) {
        isRead.value = activity.is_read
    }
    
    val actionText = when (activity.action_type) {
        "like" -> "点赞了你发布的${if (activity.target_type == "resource") "资源" else "帖子"}"
        "comment" -> "评论了你发布的${if (activity.target_type == "resource") "资源" else "帖子"}"
        "reply" -> "回复了你的评论"
        else -> "互动了你的${if (activity.target_type == "resource") "资源" else "帖子"}"
    }

    val displayUsername = if (activity.username.isBlank()) "用户${activity.user_id}" else activity.username.take(6)
    val timeText = formatTimeAgo(activity.created_at)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((16 * scale).dp))
            .background(Color(0xFFF3F4F6))
            .clickable { 
                if (!isRead.value) {
                    isRead.value = true
                    activity.is_read = true
                    ActivityReadStateManager.markActivityRead(ctx, activity.id)
                }
                onClick() 
            }
            .padding(horizontal = (12 * scale).dp, vertical = (10 * scale).dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isRead.value) {
                Box(
                    modifier = Modifier
                        .size((6 * scale).dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                )
                Spacer(modifier = Modifier.width((10 * scale).dp))
            }
            
            Text(
                "$displayUsername$actionText",
                fontSize = (13 * scale).sp,
                color = Color(0xFF4B5563),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            Text(
                timeText,
                fontSize = (11 * scale).sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(start = (8 * scale).dp)
            )
        }
    }
}

private fun formatTimeAgo(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp * 1000
    
    val minute = 60 * 1000L
    val hour = 60 * minute
    val day = 24 * hour
    
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute}分钟前"
        diff < day -> "${diff / hour}小时前"
        diff < 2 * day -> "昨天"
        diff < 7 * day -> "${diff / day}天前"
        else -> {
            val date = java.util.Date(timestamp * 1000)
            val fmt = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            fmt.format(date)
        }
    }
}

@Composable
private fun ActivityItem(
    activity: CommunityActivity,
    scale: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape((8 * scale).dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape((8 * scale).dp))
            .clickable { onClick() }
            .padding((8 * scale).dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val iconColor = when (activity.action_type) {
            "like" -> Color(0xFFEF4444)
            "comment" -> Color(0xFF3B82F6)
            "reply" -> Color(0xFF10B981)
            else -> Color(0xFF6B7280)
        }

        Canvas(modifier = Modifier.size((24 * scale).dp)) {
            val s = size.minDimension * 0.4f
            val c = center
            when (activity.action_type) {
                "like" -> {
                    drawPath(
                        Path().apply {
                            moveTo(c.x, c.y - s)
                            cubicTo(c.x + s * 1.2f, c.y - s * 0.8f, c.x + s * 1.4f, c.y + s * 0.4f, c.x, c.y + s * 0.9f)
                            cubicTo(c.x - s * 1.4f, c.y + s * 0.4f, c.x - s * 1.2f, c.y - s * 0.8f, c.x, c.y - s)
                        },
                        iconColor
                    )
                }
                "comment" -> {
                    drawPath(
                        Path().apply {
                            moveTo(c.x - s * 0.9f, c.y - s)
                            lineTo(c.x + s * 0.9f, c.y - s)
                            lineTo(c.x + s * 0.9f, c.y + s * 0.5f)
                            lineTo(c.x + s * 0.3f, c.y + s * 0.5f)
                            lineTo(c.x - s * 0.3f, c.y + s * 0.9f)
                            lineTo(c.x - s * 0.3f, c.y + s * 0.5f)
                            lineTo(c.x - s * 0.9f, c.y + s * 0.5f)
                            close()
                        },
                        iconColor
                    )
                }
                "reply" -> {
                    drawLine(iconColor, Offset(c.x - s, c.y), Offset(c.x + s, c.y), 2.dp.toPx())
                    drawLine(iconColor, Offset(c.x - s, c.y), Offset(c.x - s * 0.5f, c.y - s * 0.5f), 2.dp.toPx())
                    drawLine(iconColor, Offset(c.x - s, c.y), Offset(c.x - s * 0.5f, c.y + s * 0.5f), 2.dp.toPx())
                }
                else -> {
                    drawCircle(iconColor, radius = s * 0.5f)
                }
            }
        }

        Spacer(modifier = Modifier.height((4 * scale).dp))

        val displayText = buildString {
            append(activity.username.take(6))
            append("\n")
            when (activity.action_type) {
                "like" -> append("点赞了")
                "comment" -> append("评论了")
                "reply" -> append("回复了")
                else -> append("互动了")
            }
        }

        Text(
            displayText,
            fontSize = (10 * scale).sp,
            color = Color(0xFF4B5563),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = (14 * scale).sp
        )
    }
}

// ===================== 纯视觉卡片（无手势） =====================

@Composable
private fun CardVisual(
    title: String,
    brush: Brush,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    scale: Float = 1f
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape((16 * scale).dp))
            .background(brush),
        contentAlignment = Alignment.BottomStart
    ) {
        Column(modifier = Modifier.padding(start = (18 * scale).dp, bottom = (18 * scale).dp)) {
            icon()
            Spacer(modifier = Modifier.height((10 * scale).dp))
            Text(title, fontSize = (20 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// ===================== 蜂窝粒子数据 =====================

private data class HoneyCell(
    val baseX: Float, val baseY: Float,
    val baseSize: Float,
    val gridAngle: Float,
    var size: Float = baseSize,
    var pulse: Float = 1f,
    var alpha: Float = 0.5f
)

/** 生成铺满整个卡片的六边形蜂窝网格 */
private fun honeycombGrid(maxW: Float, maxH: Float): List<HoneyCell> {
    val cells = mutableListOf<HoneyCell>()
    val hSpacing = 30f
    val vSpacing = 26f
    val baseSz = 6f
    var idx = 0
    var y = hSpacing * 0.5f
    while (y < maxH) {
        val rowOffset = if (idx % 2 == 0) 0f else hSpacing * 0.5f
        var x = rowOffset + hSpacing * 0.3f
        while (x < maxW) {
            val angle = (idx * 37 + (x / hSpacing).toInt() * 53).toFloat() * 0.01f
            cells.add(HoneyCell(baseX = x, baseY = y, baseSize = baseSz, gridAngle = angle))
            x += hSpacing
        }
        y += vSpacing
        idx++
    }
    return cells
}

@Composable
private fun CommunityIcon(scale: Float = 1f) {
    Canvas(modifier = Modifier.size((42 * scale).dp)) {
        val sw = 2.8.dp.toPx()
        val c = center
        val s = size.minDimension * 0.36f

        // 等轴测3D立方体 - 三个可见面
        // 顶面坐标
        val tx = c.x; val ty = c.y - s * 1.2f
        val tl = c.x - s * 1.1f; val tly = c.y - s * 0.5f
        val tr = c.x + s * 1.1f; val trY = c.y - s * 0.5f

        // 顶面
        val topPath = Path().apply {
            moveTo(tx, ty)
            lineTo(tl, tly)
            lineTo(c.x, c.y - s * 0.1f)
            lineTo(tr, trY)
            close()
        }
        drawPath(topPath, Color.White.copy(alpha = 0.2f))
        drawPath(topPath, Color.White, style = Stroke(width = sw))

        // 左面
        val leftPath = Path().apply {
            moveTo(tl, tly)
            lineTo(c.x, c.y - s * 0.1f)
            lineTo(c.x, c.y + s * 1.1f)
            lineTo(tl, c.y + s * 0.5f)
            close()
        }
        drawPath(leftPath, Color.White.copy(alpha = 0.35f))
        drawPath(leftPath, Color.White, style = Stroke(width = sw))

        // 右面
        val rightPath = Path().apply {
            moveTo(c.x, c.y - s * 0.1f)
            lineTo(tr, trY)
            lineTo(tr, c.y + s * 0.5f)
            lineTo(c.x, c.y + s * 1.1f)
            close()
        }
        drawPath(rightPath, Color.White.copy(alpha = 0.15f))
        drawPath(rightPath, Color.White, style = Stroke(width = sw))
    }
}

@Composable
private fun PostIcon(scale: Float = 1f) {
    Canvas(modifier = Modifier.size((36 * scale).dp)) {
        val sw = 3.dp.toPx()
        val c = center
        val w = size.width * 0.7f
        val h = size.height * 0.7f
        val left = c.x - w / 2f
        val top = c.y - h / 2f

        // 消息气泡
        val path = Path().apply {
            moveTo(left + w * 0.05f, top)
            lineTo(left + w * 0.95f, top)
            quadraticBezierTo(left + w, top, left + w, top + h * 0.08f)
            lineTo(left + w, top + h * 0.65f)
            quadraticBezierTo(left + w, top + h * 0.73f, left + w * 0.95f, top + h * 0.73f)
            lineTo(left + w * 0.35f, top + h * 0.73f)
            lineTo(left + w * 0.15f, top + h)
            lineTo(left + w * 0.15f, top + h * 0.73f)
            lineTo(left + w * 0.05f, top + h * 0.73f)
            quadraticBezierTo(left, top + h * 0.73f, left, top + h * 0.65f)
            lineTo(left, top + h * 0.08f)
            quadraticBezierTo(left, top, left + w * 0.05f, top)
            close()
        }
        drawPath(path, color = Color.White, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 三条内容线
        val lineY = top + h * 0.35f
        drawLine(Color.White, Offset(left + w * 0.15f, lineY), Offset(left + w * 0.7f, lineY), sw, StrokeCap.Round)
        drawLine(Color.White, Offset(left + w * 0.15f, lineY + h * 0.22f), Offset(left + w * 0.55f, lineY + h * 0.22f), sw, StrokeCap.Round)
        drawLine(Color.White, Offset(left + w * 0.15f, lineY + h * 0.44f), Offset(left + w * 0.4f, lineY + h * 0.44f), sw, StrokeCap.Round)
    }
}

@Composable
fun SubPage(
    title: String,
    currentUserId: Long = 0,
    onBack: () -> Unit,
    onOpenPostDetail: (Long, String) -> Unit = { _, _ -> },
    onOpenUserProfile: (Long, String) -> Unit = { _, _ -> },
    onOpenCreatePost: () -> Unit = {},
    refreshKey: Int = 0,
    onOpenChat: (Long, String) -> Unit = { _, _ -> }
) {
    if (title == "社区" || title == "帖子" || title == "资源") {
        // 资源与帖子已合并为统一社区流，社区子页直接复用首页内容（带返回箭头）
        CommunityHomeContent(
            currentUserId = currentUserId,
            showBack = true,
            onBack = onBack,
            onOpenPostDetail = onOpenPostDetail,
            onOpenUserProfile = onOpenUserProfile,
            onOpenCreatePost = onOpenCreatePost,
            refreshKey = refreshKey,
            onOpenChat = onOpenChat
        )
        return
    }

    // "资源" 保留原有搜索样式
    var inputText by remember { mutableStateOf("") }
    val placeholder = "搜索你需要的资源"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.White)
            .statusBarsPadding()
    ) {
        // ── 顶部：返回箭头 + 搜索框 + 搜索按钮 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回箭头
            Text(
                text = "←",
                fontSize = 18.sp,
                color = Color(0xFF1E40AF),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onBack() }.padding(end = 8.dp)
            )

            // 搜索输入框
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val borderColor by androidx.compose.animation.animateColorAsState(
                targetValue = if (isFocused) Color(0xFF1E40AF) else Color(0xFFD1D5DB),
                animationSpec = androidx.compose.animation.core.tween(200)
            )
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
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF1F2937)
                    ),
                    singleLine = true,
                    interactionSource = interactionSource,
                    cursorBrush = SolidColor(Color(0xFF1E40AF)),
                    decorationBox = { innerTextField ->
                        Box {
                            if (inputText.isEmpty()) Text(placeholder, fontSize = 13.sp, color = Color(0xFF9CA3AF))
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(Modifier.width(8.dp))

            // 搜索按钮
            Box(
                modifier = Modifier
                    .height(36.dp).widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E40AF))
                    .clickable {
                        // TODO: 搜索逻辑
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("搜索", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── 内容区域 ──
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "资源页面 - 开发中",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

// ==================== 从帖子详情点击用户头像/姓名跳转的资料页（带添加好友） ====================

@Composable
private fun PostUserDetailScreen(
    userId: Long,
    currentUserId: Long,
    onBack: () -> Unit,
    onOpenChat: (Long, String) -> Unit = { _, _ -> }
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showGreeting by remember { mutableStateOf(false) }
    var userInfo by remember { mutableStateOf<UserInfo?>(null) }
    var avatarBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isFriend by remember { mutableStateOf<Boolean?>(null) }

    // 加载用户信息
    LaunchedEffect(userId) {
        val info = ChatRepository.getUserInfo(userId)
        if (info.success && info.data != null) {
            userInfo = info.data
            val friendResult = ChatRepository.isFriend(userId)
            isFriend = friendResult.data
        }
        withContext(Dispatchers.IO) {
            avatarBmp = ChatRepository.loadAvatar(ctx, userId)
        }
    }

    if (showGreeting) {
        // 打招呼面板
        Column(
            modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E40AF), modifier = Modifier.clickable { showGreeting = false })
                Spacer(Modifier.width(12.dp))
                Text("添加好友", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
            }
            Spacer(Modifier.height(20.dp))
            val userName = userInfo?.username ?: "用户"
            Text("发送好友申请给 $userName", fontSize = 14.sp, color = Color(0xFF6B7280),
                modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            var greeting by remember { mutableStateOf("你好，交个朋友吧！") }
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF9FAFB))
                    .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp)).padding(12.dp)
            ) {
                BasicTextField(
                    value = greeting, onValueChange = { greeting = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                    cursorBrush = SolidColor(Color(0xFF1E40AF))
                )
            }
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E40AF))
                    .clickable {
                        val email = userInfo?.email ?: return@clickable
                        if (email.isBlank()) return@clickable
                        scope.launch {
                            val result = ChatRepository.sendFriendRequest(email, greeting.trim())
                            android.widget.Toast.makeText(ctx, result.message, android.widget.Toast.LENGTH_SHORT).show()
                            if (result.success) onBack()
                        }
                    }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("发送", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
    } else {
        // 用户资料主面板
        Column(
            modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding().verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E40AF), modifier = Modifier.clickable { onBack() })
                Spacer(Modifier.width(12.dp))
                Text("用户资料", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
            }

            Spacer(Modifier.height(24.dp))

            // 头像 + 用户名 + 签名
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFD1D5DB)),
                    contentAlignment = Alignment.Center) {
                    if (avatarBmp != null) {
                        Image(bitmap = avatarBmp!!.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text((userInfo?.username?.take(1) ?: "?"), fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(userInfo?.username ?: "加载中...", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                    }
                    Spacer(Modifier.height(4.dp))
                    val sig = userInfo?.signature
                    if (!sig.isNullOrEmpty()) {
                        Text(sig, fontSize = 14.sp, color = Color(0xFF6B7280))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 详细信息列表 ──
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                InfoRow("ID", "${userInfo?.id ?: userId}")
                InfoRow("QQ号", when {
                    userInfo?.hideQQ == 1 -> "该用户已隐藏 QQ 号"
                    userInfo?.qqNumber.isNullOrBlank() -> "未设置"
                    else -> userInfo?.qqNumber ?: "加载中..."
                })
                InfoRow("个性签名", userInfo?.signature ?: if (userInfo != null) "无" else "加载中...")
                if (userInfo != null && userInfo!!.createdAt > 0) {
                    val since = System.currentTimeMillis() / 1000 - userInfo!!.createdAt
                    val days = since / 86400
                    val platformText = if (days >= 1) "${days} 天" else "${since / 3600} 小时"
                    InfoRow("已在平台", platformText)
                    if (userInfo!!.lastActiveAt > 0) {
                        val diff = System.currentTimeMillis() / 1000 - userInfo!!.lastActiveAt
                        val text = when {
                            userId == currentUserId -> "在线"
                            diff < 60 -> "刚刚"
                            diff < 3600 -> "${diff / 60} 分钟前"
                            diff < 86400 -> "${diff / 3600} 小时前"
                            else -> "${diff / 86400} 天前"
                        }
                        InfoRow("上次活动", text)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (isFriend == true) {
                // 已是好友：显示蓝色发消息按钮（无灰色背景）
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        .padding(vertical = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E40AF))
                        .clickable {
                            onOpenChat(userId, userInfo?.username ?: "用户")
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("发消息", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            } else {
                // 自己或非好友：显示灰色按钮
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6))
                        .clickable { if (userId != currentUserId) showGreeting = true }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (userId == currentUserId) {
                        Text("自己", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFF9CA3AF))
                    } else {
                        Text("添加好友", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2937))
                    }
                }
            }
        }
    }

}

// ==================== 信息行组件 ====================

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Color(0xFF9CA3AF), modifier = Modifier.width(80.dp))
        Text(value, fontSize = 14.sp, color = Color(0xFF1F2937))
    }
}

private fun formatRegTime(ts: Long): String {
    if (ts <= 0) return ""
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts * 1000))
}
