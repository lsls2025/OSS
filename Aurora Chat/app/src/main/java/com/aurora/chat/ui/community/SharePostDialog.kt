package com.aurora.chat.ui.community

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.launch

@Composable
fun SharePostDialog(
    postTitle: String,
    postContent: String,
    postId: Long,
    postUserId: Long,
    postUsername: String,
    postType: String = "post",
    titlePrefix: String = "",
    idKey: String = "post_id",
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0=好友 1=群聊
    var conversations by remember { mutableStateOf<List<ConversationInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    // 面板高度比例：默认 65%，可通过顶部手柄拖动调整（最小40% 最大88%），向下拖到阈值关闭
    var sheetHeightFraction by remember { mutableStateOf(0.65f) }
    val density = LocalDensity.current
    val screenHdp = LocalConfiguration.current.screenHeightDp

    LaunchedEffect(Unit) {
        val result = ChatRepository.getConversations()
        if (result.success && result.data != null) {
            conversations = result.data!!
        }
        isLoading = false
    }

    // 根据筛选和搜索过滤
    val filteredList = remember(conversations, searchText, selectedTab) {
        conversations.distinctBy { it.id }.filter { conv ->
            val matchTab = if (selectedTab == 0) conv.id > 0 else conv.id < 0
            val matchSearch = searchText.isBlank() ||
                    conv.username.contains(searchText, ignoreCase = true) ||
                    conv.lastMessage.contains(searchText, ignoreCase = true)
            matchTab && matchSearch
        }
    }

    // 构建分享消息内容（卡片格式）
    fun buildShareMessage(): String {
        val preview = postContent.take(150).replace("\n", " ").trim()
        val prefix = if (titlePrefix.isNotEmpty()) titlePrefix
        else if (postType == "resource") "分享资源" else "分享帖子"
        return prefix + "\n" +
                "━━━━━━━━━━\n" +
                postTitle + "\n" +
                (if (preview.isNotEmpty()) preview + "\n" else "") +
                "━━━━━━━━━━\n" +
                "来自 ${postUsername}\n" +
                "$idKey=$postId"
    }

    fun sendShare(conv: ConversationInfo) {
        // 检查官方群限制：群名含"官方"的非开发者禁止分享
        val isOfficialGroup = conv.id < 0 && conv.username.contains("官方", ignoreCase = true)
        val loginPrefs = ctx.getSharedPreferences("aurora_login", Context.MODE_PRIVATE)
        val userQQ = loginPrefs.getString("user_qq", "") ?: ""
        val userEmail = loginPrefs.getString("user_email", "") ?: ""
        val isDeveloper = com.aurora.chat.data.local.LocalStorage.isDeveloper(userQQ, userEmail)

        if (isOfficialGroup && !isDeveloper) {
            Toast.makeText(ctx, "官方群禁止分享帖子", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            val msg = buildShareMessage()
            // 群消息：后端要求 to_user_id 为负数会话 id（isGroupChat 约定，内部再 -(id+1000) 还原群 id）。
            // 不能先转成正数内部 id，否则会被后端当作"私聊用户id=该数"，导致分享成功但群里找不到。
            val result = if (conv.id < 0) {
                ChatRepository.sendGroupMessage(conv.id, msg)
            } else {
                ChatRepository.sendMessage(conv.id, msg)
            }
            if (result.success) {
                Toast.makeText(ctx, "已分享给 ${conv.username}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "分享失败: ${result.message}", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // 遮罩
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable { onDismiss() }
        )

        // 底部面板（高度可拖动调整）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(sheetHeightFraction)
                .background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(top = 16.dp)
                // 抬高底部避开系统手势导航栏，否则列表最后一项会被导航栏盖住半边且无法再下滑
                .navigationBarsPadding()
        ) {
            // 顶部手柄（可上下拖动调整面板；向下拖到阈值即关闭）
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                when {
                                    sheetHeightFraction < 0.5f -> onDismiss()          // 拖到过低 → 关闭
                                    sheetHeightFraction > 0.85f -> sheetHeightFraction = 0.88f  // 拖到接近顶 → 吸附到最高
                                    else -> sheetHeightFraction = 0.65f                          // 回弹到默认
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                val dpPerPx = with(density) { dragAmount.toDp() }.value
                                sheetHeightFraction = (sheetHeightFraction - dpPerPx / screenHdp)
                                    .coerceIn(0.4f, 0.88f)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.width(40.dp).height(4.dp)
                        .background(Color(0xFFD1D5DB), CircleShape)
                )
            }

            // 标题
            Text(
                "分享到",
                fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // 搜索输入框
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF3F4F6))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1F2937)),
                    cursorBrush = SolidColor(Color(0xFF1E40AF)),
                    decorationBox = { innerTextField ->
                        if (searchText.isEmpty()) {
                            Text("搜索好友或群聊", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                        }
                        innerTextField()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 好友/群聊切换胶囊
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf("好友" to 0, "群聊" to 1).forEach { (label, index) ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) Color(0xFF1E40AF) else Color(0xFFF3F4F6))
                            .clickable { selectedTab = index }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color(0xFF6B7280)
                        )
                    }
                }
            }

            // 好友/数字提示
            Text(
                "${filteredList.size} 个${if (selectedTab == 0) "好友" else "群聊"}",
                fontSize = 12.sp, color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // 列表
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            } else if (filteredList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchText.isNotEmpty()) "未找到匹配的${if (selectedTab == 0) "好友" else "群聊"}"
                        else "暂无${if (selectedTab == 0) "好友" else "群聊"}",
                        fontSize = 14.sp, color = Color(0xFF9CA3AF)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredList, key = { it.id }) { conv ->
                        val internalGroupId = if (conv.id < 0) -(conv.id + 1000) else null
                        val isGroup = conv.id < 0

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 头像
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isGroup && internalGroupId != null) {
                                    GroupAvatar(
                                        internalGroupId = internalGroupId,
                                        groupName = conv.username,
                                        size = 44.dp
                                    )
                                } else {
                                    UserAvatar(
                                        userId = conv.id,
                                        userName = conv.username,
                                        size = 44.dp
                                    )
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            // 名称
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conv.username,
                                    fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1F2937),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (isGroup) {
                                    Text(
                                        "群聊",
                                        fontSize = 11.sp, color = Color(0xFF9CA3AF)
                                    )
                                }
                            }

                            // 分享按钮
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFEEF2FF))
                                    .clickable { sendShare(conv) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "分享", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1E40AF)
                                )
                            }
                        }

                        // 分割线
                        Box(
                            Modifier.fillMaxWidth().height(0.5.dp)
                                .background(Color(0xFFF3F4F6))
                        )
                    }
                }
            }
        }
    }
}

