package com.aurora.chat.ui.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.local.DataCache
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.launch

@Composable
fun FriendProfilePanel(
    friendId: Long,
    friendName: String,
    currentUserId: Long,
    onBack: () -> Unit,
    onDeleteFriend: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 免打扰开关变化时，更新本地推送配置里的免打扰好友列表，并同步到后端
    fun updatePushDndList(enabled: Boolean) {
        val dndIds = DataCache.loadPushDndIds(context, currentUserId).toMutableList()
        if (enabled) {
            if (!dndIds.contains(friendId)) dndIds.add(friendId)
        } else {
            dndIds.remove(friendId)
        }
        DataCache.savePushConfig(
            context, currentUserId,
            DataCache.loadPushMethod(context, currentUserId),
            DataCache.loadPushTemplateMode(context, currentUserId),
            DataCache.loadOwnEmail(context, currentUserId),
            DataCache.loadOwnAuth(context, currentUserId),
            dndIds
        )
        scope.launch {
            try {
                AuroraApi.savePushConfig(
                    DataCache.loadPushMethod(context, currentUserId),
                    DataCache.loadPushTemplateMode(context, currentUserId),
                    DataCache.loadOwnEmail(context, currentUserId),
                    DataCache.loadOwnAuth(context, currentUserId),
                    dndIds
                )
            } catch (_: Exception) {
                // 网络异常仅本地保留
            }
        }
    }

    var friendInfo by remember { mutableStateOf<UserInfo?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var doNotDisturb by remember { mutableStateOf(false) }
    var isPinned by remember { mutableStateOf(false) }
    var friendSince by remember { mutableStateOf(0L) }
    var remark by remember { mutableStateOf("") }
    var showRemarkDialog by remember { mutableStateOf(false) }
    var remarkInput by remember { mutableStateOf("") }

    LaunchedEffect(friendId) {
        val info = ChatRepository.getUserInfo(friendId)
        if (info.success && info.data != null) {
            friendInfo = info.data
        }
        // 加载免打扰/置顶状态
        val prefs = context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
        doNotDisturb = prefs.getBoolean("dnd_$friendId", false)
        isPinned = prefs.getBoolean("pin_$friendId", false)
        friendSince = prefs.getLong("friend_since_$friendId", 0L)
        // 加载备注
        remark = prefs.getString("remark_$friendId", "") ?: ""
        // 如果没有缓存加为好友时间，则从好友列表获取
        if (friendSince == 0L) {
            val friendsResult = ChatRepository.getFriends()
            if (friendsResult.success && friendsResult.data != null) {
                val f = friendsResult.data!!.find { it.id == friendId }
                if (f != null) friendSince = f.createdAt
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        // 顶部栏（statusBarsPadding 确保不被状态栏遮挡，避免点不到返回按钮）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "←",
                        fontSize = 20.sp,
                        color = Color(0xFF1F2937)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "好友资料",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            // 头像
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                com.aurora.chat.ui.components.UserAvatar(
                    userId = friendId,
                    userName = friendInfo?.username ?: friendName,
                    size = 80.dp,

                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(16.dp))
                )
            }

            Spacer(Modifier.height(16.dp))

            // 用户昵称（可点击修改备注）
            val displayName = remark.ifEmpty { friendInfo?.username?.takeIf { it.isNotBlank() } ?: friendName }
            Text(
                text = displayName,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        remarkInput = remark
                        showRemarkDialog = true
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (remark.isNotEmpty()) {
                Text(
                    "昵称: ${friendInfo?.username?.takeIf { it.isNotBlank() } ?: friendName}",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            // ID
            Text(
                "ID: $friendId",
                fontSize = 14.sp,
                color = Color(0xFF9CA3AF)
            )

            Spacer(Modifier.height(6.dp))

            // 个性签名
            val signature = friendInfo?.signature?.takeIf { it.isNotBlank() }
            if (signature != null) {
                Text(
                    signature,
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280),
                    lineHeight = 20.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
            }

            // QQ 号
            val qqText = when {
                friendInfo == null -> null
                friendInfo!!.hideQQ == 1 -> "该用户已隐藏 QQ 号"
                friendInfo!!.qqNumber.isBlank() -> null
                else -> "QQ: ${friendInfo!!.qqNumber}"
            }
            if (qqText != null) {
                Text(
                    qqText,
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF)
                )
                Spacer(Modifier.height(6.dp))
            }

            // 在平台天数
            if (friendInfo != null && friendInfo!!.createdAt > 0) {
                val days = (System.currentTimeMillis() / 1000 - friendInfo!!.createdAt) / 86400
                Text(
                    "在平台 $days 天",
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF)
                )
                Spacer(Modifier.height(4.dp))
            }

            // 已成为好友天数
            if (friendSince > 0) {
                val days = (System.currentTimeMillis() / 1000 - friendSince) / 86400
                Text(
                    "已成为好友 $days 天",
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF)
                )
                Spacer(Modifier.height(4.dp))
            }

            // 上次活动
            if (friendInfo != null && friendInfo!!.lastActiveAt > 0) {
                val diff = System.currentTimeMillis() / 1000 - friendInfo!!.lastActiveAt
                val activeText = when {
                    diff < 60 -> "刚刚在线"
                    diff < 3600 -> "${diff / 60} 分钟前在线"
                    diff < 86400 -> "${diff / 3600} 小时前在线"
                    else -> "${diff / 86400} 天前在线"
                }
                Text(
                    "上次活动: $activeText",
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF)
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            Spacer(Modifier.height(16.dp))

            // 免打扰
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "免打扰",
                    fontSize = 15.sp,
                    color = Color(0xFF374151),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Switch(
                    checked = doNotDisturb,
                    onCheckedChange = {
                        doNotDisturb = it
                        context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                            .edit().putBoolean("dnd_$friendId", it).apply()
                        updatePushDndList(it)
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF1E40AF),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFD1D5DB)
                    )
                )
            }

            Spacer(Modifier.height(12.dp))

            // 置顶（开启后该会话将排列在聊天列表顶部）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "置顶",
                    fontSize = 15.sp,
                    color = Color(0xFF374151),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Switch(
                    checked = isPinned,
                        onCheckedChange = { newVal ->
                            isPinned = newVal
                            context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                                .edit().putBoolean("pin_$friendId", newVal).apply()
                            // 通知聊天列表刷新
                            com.aurora.chat.ui.viewmodel.ChatViewModel.notifyPinChanged()
                        },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF1E40AF),
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFD1D5DB)
                    )
                )
            }

            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFEE2E2))
                    .clickable { showDeleteDialog = true }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "删除好友",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFDC2626)
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showDeleteDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))
                Text("删除好友", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))
                Text("确定要删除好友「${friendName}」吗？\n删除后需重新发送好友申请。",
                    fontSize = 14.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("取消", fontSize = 15.sp, color = Color(0xFF6B7280),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp)).clickable { showDeleteDialog = false }
                            .padding(horizontal = 24.dp, vertical = 10.dp))
                    Text("确认删除", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEE2E2))
                            .clickable {
                                showDeleteDialog = false
                                onDeleteFriend()
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // 备注修改弹窗
    if (showRemarkDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRemarkDialog = false },
            containerColor = Color.White,
            title = { Text("给「$friendName」备注", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("当前备注：", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (remark.isNotEmpty()) remark else "（未设置）",
                        fontSize = 14.sp, color = Color(0xFF1F2937), fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(12.dp))
                    BasicTextField(
                        value = remarkInput,
                        onValueChange = { remarkInput = it },
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF3F4F6))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box { if (remarkInput.isEmpty()) Text("输入备注名称", fontSize = 13.sp, color = Color(0xFF9CA3AF)); inner() }
                        }
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val newRemark = remarkInput.trim()
                    remark = newRemark
                    context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                        .edit().putString("remark_$friendId", newRemark).apply()
                    showRemarkDialog = false
                }) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRemarkDialog = false }) {
                    Text("取消", color = Color(0xFF6B7280))
                }
            }
        )
    }
}
