package com.aurora.chat.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.DpOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.*
import kotlinx.coroutines.delay

enum class ConnStatus { Connected, Connecting, Disconnected }

/**
 * 顶部标题右侧刷新转圈的通用状态。
 * 最短可见 800ms，避免接口过快时转圈一闪而过；generation 防止上一轮的延迟隐藏误伤新一轮。
 */
class RefreshIndicatorState {
    val refreshing = mutableStateOf(false)
    private val minVisibleMs = 800L
    @Volatile private var startedAt = 0L
    @Volatile private var generation = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        generation++
        startedAt = android.os.SystemClock.elapsedRealtime()
        refreshing.value = true
    }

    fun finish() {
        val gen = generation
        val remain = minVisibleMs - (android.os.SystemClock.elapsedRealtime() - startedAt)
        if (remain <= 0L) {
            refreshing.value = false
        } else {
            handler.postDelayed({
                if (gen == generation) refreshing.value = false
            }, remain)
        }
    }
}

/** 联系人页下拉刷新状态（由 ContactsScreen 写入，MainActivity 读给标题栏） */
val ContactsRefreshState = RefreshIndicatorState()

/** 社区页下拉刷新状态（由 CommunityPostFeed 写入，MainActivity 读给标题栏） */
val CommunityRefreshState = RefreshIndicatorState()

data class StatusBanner(
    val message: String,
    val isError: Boolean,
    val durationMs: Long = 3000L
)

@Composable
fun AuroraTopBar(
    title: String = "Aurora Chat",
    /** 标题右侧的刷新转圈（聊天列表下拉刷新中为 true） */
    refreshing: Boolean = false,
    status: ConnStatus = ConnStatus.Connected,
    /** 网络状态文字（如"正在尝试重连"/"当前网络不可用"/"已连接"）；空=正常不显示。
     *  以「标题下方小字 + 状态点」呈现，不用横幅，避免断线时与聊天列表离线提示形成双横幅。 */
    statusText: String = "",
    statusBanner: StatusBanner? = null,
    onBannerDismiss: () -> Unit = {},
    onPlusClick: () -> Unit = {},
    backgroundColor: Color = com.aurora.chat.ui.theme.appBarBg(),
    showDivider: Boolean = true,
    plusMenuOpen: Boolean = false,
    onDismissPlusMenu: () -> Unit = {},
    onSearchUser: () -> Unit = {},
    onSearchGroup: () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onScanQr: () -> Unit = {},
    // 右上角「加号」入口（搜索用户/搜索群聊/创建群聊/扫一扫）是否显示：
    // 只在聊天页显示，联系人/社区页不显示（右侧留等宽空白，保证标题居中）
    showPlus: Boolean = true
) {
    // durationMs <= 0 表示"常驻"（如断连横幅）：不自动撤，直到连接状态变化时由调用方清掉；
    // > 0 才延时自动撤，且撤走统一走下方 AnimatedVisibility 的淡出+上收动画，不再直愣愣消失。
    LaunchedEffect(statusBanner) {
        if (statusBanner != null && statusBanner.durationMs > 0) {
            delay(statusBanner.durationMs)
            onBannerDismiss()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .statusBarsPadding()
                .height(38.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧占位与右侧「加号」等宽，保证标题真正水平居中。
            // 加号缩小后：按钮 32dp + 右侧 4dp 间距 = 36dp，所以两侧都按 36dp 留位。
            val sideSlot = 36.dp
            Spacer(modifier = Modifier.width(sideSlot))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左右各留等宽空槽：转圈只占右侧槽位，标题始终精确居中，出现/消失都不会被顶偏
                Spacer(modifier = Modifier.width(26.dp))
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                    // 下拉刷新进行中：标题右侧显示蓝色转圈，刷新完成/取消即消失
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            // 品牌深蓝（与页面主色 #1E40AF 保持一致），不用浅蓝
                            color = Color(0xFF1E40AF),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            if (showPlus) {
                Box {
                    PlusButton(onClick = onPlusClick, expanded = plusMenuOpen)
                    DropdownMenu(
                        expanded = plusMenuOpen,
                        onDismissRequest = onDismissPlusMenu,
                        offset = DpOffset((-114).dp, 0.dp),
                        modifier = Modifier.width(150.dp).background(com.aurora.chat.ui.theme.appSurface())
                    ) {
                        DropdownMenuItem(
                            text = { Text("搜索用户", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                            onClick = onSearchUser
                        )
                        DropdownMenuItem(
                            text = { Text("搜索群聊", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                            onClick = onSearchGroup
                        )
                        DropdownMenuItem(
                            text = { Text("创建群聊", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                            onClick = onCreateGroup
                        )
                        DropdownMenuItem(
                            text = { Text("扫一扫", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                            onClick = onScanQr
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                // 联系人/社区页：不显示加号，右侧留等宽空白，标题不会偏
                Spacer(modifier = Modifier.width(sideSlot))
            }
        }

        // 网络状态文字（非横幅）：断线重连/无网络/刚连上时，置于标题下方居中；空则不占位。
        // 用 lastStatusText 滞后一拍，保证退场（淡出+上收）期间文字不会突然变空。
        var lastStatusText by remember { mutableStateOf("") }
        if (statusText.isNotEmpty()) lastStatusText = statusText
        AnimatedVisibility(
            visible = statusText.isNotEmpty(),
            enter = fadeIn(tween(150)) + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut(tween(150)) + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            val accent = when (status) {
                ConnStatus.Connected -> StatusOnline
                ConnStatus.Connecting -> StatusConnecting
                ConnStatus.Disconnected -> StatusOffline
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .padding(horizontal = 14.dp)
                    .padding(top = 2.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(accent))
                Spacer(modifier = Modifier.width(4.dp))
                Text(lastStatusText, fontSize = 10.sp, color = accent, fontWeight = FontWeight.Medium)
            }
        }

        // 临时横幅
        AnimatedVisibility(
            visible = statusBanner != null,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(200)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(200))
        ) {
            statusBanner?.let { banner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (banner.isError) Color(0xFFFEE2E2) else Color(0xFFD1FAE5))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = banner.message,
                        fontSize = 12.sp,
                        color = if (banner.isError) Color(0xFFDC2626) else Color(0xFF059669),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (showDivider && statusBanner == null) Box(modifier = Modifier.fillMaxWidth().height(0.5.dp)
            .background(com.aurora.chat.ui.theme.appDivider()))
    }
}

@Composable
private fun PlusButton(onClick: () -> Unit, expanded: Boolean = false) {
    // 菜单展开时加号往左旋转 45° 变乘号（关闭态转回加号），有过渡动画、不突兀
    val rotation by animateFloatAsState(
        targetValue = if (expanded) -45f else 0f,
        animationSpec = tween(200),
        label = "plusRotation"
    )
    Box(
        // 整体比原来（36dp / 画布 32dp）收小一档，但不至于太小
        // 点击不用水波纹：indication = null
        modifier = Modifier
            .size(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = rotation }) {
            val c = center
            val r = size.width / 2f - 1.dp.toPx()
            drawCircle(com.aurora.chat.ui.theme.appInk(), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4.dp.toPx()))
            val len = r * 0.5f
            drawLine(com.aurora.chat.ui.theme.appInk(), Offset(c.x - len, c.y), Offset(c.x + len, c.y), strokeWidth = 1.7.dp.toPx(), cap = StrokeCap.Round)
            drawLine(com.aurora.chat.ui.theme.appInk(), Offset(c.x, c.y - len), Offset(c.x, c.y + len), strokeWidth = 1.7.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}


