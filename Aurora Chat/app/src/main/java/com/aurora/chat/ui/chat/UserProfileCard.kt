package com.aurora.chat.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.chat.ChatMsg
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UserProfileCard(
    userId: Long,
    userName: String,
    currentUserId: Long,
    onBack: () -> Unit,
    onDeleteFriend: () -> Unit,
    onOpenChat: (Long, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAvatarPreview by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isFriend by remember { mutableStateOf<Boolean?>(null) }
    var friendInfo by remember { mutableStateOf<com.aurora.chat.data.api.UserInfo?>(null) }
    // 好友申请（打招呼）填写面板
    var showGreeting by remember { mutableStateOf(false) }
    var greetingEmail by remember { mutableStateOf("") }
    var isSubmittingFriend by remember { mutableStateOf(false) }
    val displayName by remember(userName, friendInfo) {
        derivedStateOf {
            if (userName.isNotBlank()) userName else (friendInfo?.username ?: "用户$userId")
        }
    }

    LaunchedEffect(userId) {
        // 尝试从好友列表获取
        val result = ChatRepository.getFriends()
        if (result.success) {
            val friends = result.data ?: emptyList()
            isFriend = friends.any { it.id == userId }
        } else {
            isFriend = false
        }
        // 始终调用 getUserInfo 获取完整资料（好友列表数据不完整）
        if (userId > 0) {
            val info = ChatRepository.getUserInfo(userId)
            if (info.success && info.data != null) {
                friendInfo = info.data
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("用户资料", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                }
                Spacer(Modifier.height(20.dp))
                Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.Top
            ) {
                UserAvatar(
                    userId = userId, userName = userName, size = 64.dp,
                    modifier = Modifier.clickable {
                        scope.launch {
                            try {
                                val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val bytes = com.aurora.chat.data.api.HttpClient.downloadBytes(com.aurora.chat.data.api.AuroraApi.getAvatarUrlWithTimestamp(userId))
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }
                                if (bmp != null) {
                                    previewBitmap = bmp
                                    showAvatarPreview = true
                                }
                            } catch (_: Exception) { }
                        }
                    }
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    // 用户名可点击复制
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937),
                            modifier = Modifier.clickable {
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("用户名", displayName))
                                android.widget.Toast.makeText(context, "已复制用户名", android.widget.Toast.LENGTH_SHORT).show()
                            })
                    }
                    Spacer(Modifier.height(4.dp))
                    // ID可点击复制
                    Text("ID: $userId", fontSize = 13.sp, color = Color(0xFF9CA3AF),
                        modifier = Modifier.clickable {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ID", userId.toString()))
                            android.widget.Toast.makeText(context, "已复制ID", android.widget.Toast.LENGTH_SHORT).show()
                        })
                    // 个性签名（从服务器获取）
                    Spacer(Modifier.height(2.dp))
                    val sigText = if (friendInfo != null && friendInfo!!.signature.isNotBlank())
                        friendInfo!!.signature else "这个人很懒，什么都没写…"
                    Text(sigText, fontSize = 12.sp, color = Color(0xFF6B7280),
                        modifier = Modifier.clickable {
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("个性签名", sigText))
                            android.widget.Toast.makeText(context, "已复制签名", android.widget.Toast.LENGTH_SHORT).show()
                        })
                    // QQ 号显示
                    Spacer(Modifier.height(2.dp))
                    val qqText = when {
                        friendInfo == null -> ""
                        friendInfo!!.hideQQ == 1 -> "QQ: 该用户已隐藏 QQ 号"
                        friendInfo!!.qqNumber.isBlank() -> "QQ: 未设置"
                        else -> "QQ: ${friendInfo!!.qqNumber}"
                    }
                    if (qqText.isNotEmpty()) {
                        Text(qqText, fontSize = 12.sp, color = Color(0xFF9CA3AF),
                            modifier = if (friendInfo != null && friendInfo!!.hideQQ == 0 && friendInfo!!.qqNumber.isNotBlank())
                                Modifier.clickable {
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ号", friendInfo!!.qqNumber))
                                    android.widget.Toast.makeText(context, "已复制QQ号", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            else Modifier)
                    }
                    // 在平台天数
                    if (friendInfo != null && friendInfo!!.createdAt > 0) {
                        Spacer(Modifier.height(2.dp))
                        val since = System.currentTimeMillis() / 1000 - friendInfo!!.createdAt
                        val days = since / 86400
                        val text = if (days >= 1) "已在平台 ${days} 天" else "已在平台 ${since / 3600} 小时"
                        Text(text, fontSize = 12.sp, color = Color(0xFF9CA3AF))
                    }
                    // 上次活动时间（自己看自己且在线时显示"在线"）
                    if (friendInfo != null && friendInfo!!.lastActiveAt > 0) {
                        Spacer(Modifier.height(2.dp))
                        val diff = System.currentTimeMillis() / 1000 - friendInfo!!.lastActiveAt
                        val text = when {
                            userId == currentUserId -> "在线"
                            diff < 60 -> "刚刚在线"
                            diff < 3600 -> "${diff / 60} 分钟前在线"
                            diff < 86400 -> "${diff / 3600} 小时前在线"
                            else -> "${diff / 86400} 天前在线"
                        }
                        Text("上次活动: $text",
                            fontSize = 12.sp, color = Color(0xFF9CA3AF))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 24.dp).background(Color(0xFFE5E7EB))
            )
            if (userId != currentUserId) {
                if (isFriend == true) {
                    // 好友：显示 "发消息" 按钮（不删除好友）和 "删除好友" 文字链接
                    Box(
                        modifier = Modifier
                            .fillMaxWidth().padding(horizontal = 24.dp)
                            .padding(vertical = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E40AF))
                            .clickable {
                                // 关闭资料面板后跳转到该用户的对话
                                onOpenChat(userId, displayName)
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "发消息",
                            fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                } else {
                    // 非好友：添加好友
                    Box(
                        modifier = Modifier
                            .fillMaxWidth().padding(horizontal = 24.dp)
                            .padding(vertical = 16.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E40AF))
                            .clickable {
                                scope.launch {
                                    val info = com.aurora.chat.data.repository.ChatRepository.getUserInfo(userId)
                                    if (info.success && info.data != null) {
                                        greetingEmail = info.data!!.email
                                        showGreeting = true
                                    } else {
                                        android.widget.Toast.makeText(context, "获取用户信息失败", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "添加好友",
                            fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

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
                Text("确定要删除好友「$displayName」吗？\n删除后需重新发送好友申请。",
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
                                android.widget.Toast.makeText(context, "正在删除...", android.widget.Toast.LENGTH_SHORT).show()
                                onDeleteFriend()
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // 头像预览弹窗（半透明背景，点外部关闭）
    if (showAvatarPreview && previewBitmap != null) {
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }
        val animProgress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { animProgress.animateTo(1f, tween(200)) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable { showAvatarPreview = false }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = previewBitmap!!.asImageBitmap(),
                contentDescription = "头像预览",
                modifier = Modifier
                    .graphicsLayer(
                        scaleX = scale * animProgress.value,
                        scaleY = scale * animProgress.value,
                        alpha = animProgress.value,
                        translationX = offsetX, translationY = offsetY
                    )
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .statusBarsPadding()
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable { showAvatarPreview = false },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 好友申请（打招呼）填写面板
        if (showGreeting) {
            Column(modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF), modifier = Modifier.clickable { showGreeting = false })
                    Spacer(Modifier.width(12.dp))
                    Text("添加好友", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                }
                Spacer(Modifier.height(20.dp))
                Text("发送好友申请给 $displayName", fontSize = 14.sp, color = Color(0xFF6B7280), modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(12.dp))
                var greeting by remember { mutableStateOf("你好，交个朋友吧！") }
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF9FAFB))
                        .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp)).padding(12.dp)
                ) {
                    BasicTextField(
                        value = greeting, onValueChange = { greeting = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                        singleLine = false, minLines = 3, maxLines = 6,
                        decorationBox = { inner ->
                            Box {
                                if (greeting.isEmpty()) Text("请输入打招呼内容…", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.fillMaxWidth().padding(24.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E40AF))
                        .clickable(enabled = !isSubmittingFriend) {
                            scope.launch {
                                isSubmittingFriend = true
                                val r = com.aurora.chat.data.repository.ChatRepository.sendFriendRequest(greetingEmail, greeting.trim())
                                isSubmittingFriend = false
                                android.widget.Toast.makeText(context, if (r.success) "好友申请已发送" else "发送失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                                if (r.success) { showGreeting = false; isFriend = false }
                            }
                        }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isSubmittingFriend) "发送中..." else "发送", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        }
    }
}
