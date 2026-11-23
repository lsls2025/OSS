package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import com.aurora.chat.ui.components.FriendRequest
import com.aurora.chat.ui.components.LocalShowDividers
import com.aurora.chat.ui.components.RequestStatus
import com.aurora.chat.ui.components.UserAvatar
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun formatTime(timeMillis: Long): String {
    val now = Calendar.getInstance()
    val date = Calendar.getInstance().apply { timeInMillis = timeMillis }
    return when {
        now.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))
        now.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - date.get(Calendar.DAY_OF_YEAR) == 1 -> "昨天"
        now.get(Calendar.YEAR) == date.get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timeMillis))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timeMillis))
    }
}

@Composable
fun FriendRequestScreen(
    requests: List<FriendRequest>,
    onAccept: (Int) -> Unit,
    onReject: (Int) -> Unit,
    onDismiss: () -> Unit,
    onCancel: (Int) -> Unit = {},
    onAddFriend: () -> Unit = {}
) {
    var keyword by remember { mutableStateOf("") }
    var selectedRequest by remember { mutableStateOf<FriendRequest?>(null) }
    var cancelTarget by remember { mutableStateOf<FriendRequest?>(null) }

    fun computeResults(): List<Pair<Int, FriendRequest>> {
        // 展示全部记录（我发出的 + 收到的），仅按关键字过滤
        return requests.mapIndexedNotNull { index, req ->
            if (keyword.isNotBlank() && !req.name.contains(keyword, ignoreCase = true)) null
            else index to req
        }
    }

    // 仅在 requests 或 keyword 变化时重算，避免每次重组都重新过滤（大数据量下防止主线程卡顿）
    val results = remember(requests, keyword) { computeResults() }

    // 详情弹窗
    if (selectedRequest != null) {
        FriendRequestDetailDialog(
            request = selectedRequest!!,
            onDismiss = { selectedRequest = null }
        )
    }

    // 长按取消确认弹窗
    if (cancelTarget != null) {
        Dialog(onDismissRequest = { cancelTarget = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("取消该申请记录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.height(10.dp))
                Text(
                    "确定要取消这条好友申请记录吗？取消后将从本地与服务器一并删除，且不可恢复。",
                    fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                            .clickable { cancelTarget = null }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("暂不取消", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFFEF4444))
                            .clickable {
                                val idx = requests.indexOf(cancelTarget)
                                cancelTarget = null
                                if (idx >= 0) onCancel(idx)
                            }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("确认取消", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White) }
                }
            }
        }
    }

    // 搜索框在上、列表在下：必须用 Column 顺序排布。
    // 若两者同处一个 Box 且列表 fillMaxSize，列表会从顶部画起并盖住搜索框（列表行"映"进输入框）。
    Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding()) {
        // ========== 顶部标题栏：返回 + 标题 ==========
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(28.dp).clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "好友申请",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(12.dp))
            // 右侧等宽留白，保证标题视觉居中
            Box(modifier = Modifier.size(28.dp))
        }

        // ========== 搜索输入区 ==========
        Box(
            modifier = Modifier
                .fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()
                ),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box {
                        if (keyword.isEmpty()) Text("搜索好友申请", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        innerTextField()
                    }
                }
            )
        }

        Spacer(Modifier.height(4.dp))

        // ========== 请求列表（全部记录） ==========
        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (keyword.isNotBlank()) "未找到相关好友申请" else "暂无好友申请记录",
                    fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                // 惰性组合：仅渲染可见项，屏幕外不创建、不触发头像加载，低端机与大数据量均流畅
                items(results, key = { it.second.id }) { (index, req) ->
                    FriendRequestItem(
                        req = req,
                        modifier = Modifier.fillMaxWidth(),
                        onAccept = { onAccept(index) },
                        onReject = { onReject(index) },
                        onClick = { selectedRequest = req },
                        onLongClick = { cancelTarget = req }
                    )
                    Box(
                        Modifier.fillMaxWidth().height(0.5.dp)
                            .background(com.aurora.chat.ui.theme.appCardBg()).padding(start = 56.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectionBadge(isOutgoing: Boolean) {
    // 方向标记：蓝色↗=我发出的；橙色↙=别人发给我的（不使用“我发出的”文字）
    Box(
        Modifier.size(16.dp).clip(CircleShape)
            .background(if (isOutgoing) Color(0xFF1E40AF) else Color(0xFFF59E0B)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (isOutgoing) "↗" else "↙",
            fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = TextStyle(
                lineHeight = 10.sp,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendRequestItem(
    req: FriendRequest,
    modifier: Modifier = Modifier,
    onAccept: (() -> Unit)?,
    onReject: (() -> Unit)?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val statusText: String
    val statusColor: Color
    when {
        req.status == RequestStatus.ACCEPTED -> { statusText = "已同意"; statusColor = Color(0xFF10B981) }
        req.status == RequestStatus.REJECTED -> { statusText = "已拒绝"; statusColor = com.aurora.chat.ui.theme.appTextHint() }
        req.isOutgoing -> { statusText = "等待验证"; statusColor = Color(0xFF1E40AF) }
        else -> { statusText = "待处理"; statusColor = Color(0xFFF59E0B) }
    }
    // 预格式化时间，避免每次重组都重新计算（Calendar/SimpleDateFormat 开销）
    val timeText = remember(req.time) { formatTime(req.time) }

    val otherUserId = if (req.isOutgoing) req.toUserId else req.fromUserId
    Row(
        modifier = modifier.padding(vertical = 8.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(userId = otherUserId, userName = req.name, size = 44.dp)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(req.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectionBadge(req.isOutgoing)
                Spacer(Modifier.width(6.dp))
                Text(statusText, fontSize = 12.sp, color = statusColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(timeText, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // 仅“收到的待处理”请求显示 拒绝/同意 按钮
        if (req.status == RequestStatus.PENDING && !req.isOutgoing && onAccept != null && onReject != null) {
            Text("拒绝", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.clickable { onReject() }.padding(horizontal = 10.dp, vertical = 6.dp))
            Text("同意", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier.clickable { onAccept() }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun FriendRequestDetailDialog(request: FriendRequest, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 头像（显示对方，而非自己）
            val otherUserId = if (request.isOutgoing) request.toUserId else request.fromUserId
            UserAvatar(
                userId = otherUserId,
                userName = request.name,
                size = 72.dp,
                modifier = Modifier.clip(RoundedCornerShape(18.dp))
            )

            Spacer(Modifier.height(12.dp))

            // 名字
            Text(request.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = com.aurora.chat.ui.theme.appTextPrimary())

            Spacer(Modifier.height(6.dp))

            // 发送方向提示
            Text(
                if (request.isOutgoing) "你向对方发起了好友申请" else "对方向你发起了好友申请",
                fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
            )

            Spacer(Modifier.height(6.dp))

            // 个性签名
            Text(request.signature, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                textAlign = TextAlign.Center)

            Spacer(Modifier.height(16.dp))

            // 分隔线
            if (LocalShowDividers.current) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            }

            Spacer(Modifier.height(12.dp))

            // 打招呼信息
            Text("打招呼信息", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.align(Alignment.Start))

            Spacer(Modifier.height(6.dp))

            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg()).padding(12.dp)
            ) {
                Text(
                    if (request.greeting.isNotBlank()) request.greeting else "暂无打招呼信息",
                    fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()
                )
            }

            Spacer(Modifier.height(20.dp))

            // 关闭按钮
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg()).clickable { onDismiss() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("关闭", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = com.aurora.chat.ui.theme.appTextPrimary())
            }
        }
    }
}
