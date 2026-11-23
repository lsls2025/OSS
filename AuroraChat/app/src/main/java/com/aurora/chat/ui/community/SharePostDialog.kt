package com.aurora.chat.ui.community

import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
    /** 非空时直接作为分享消息内容（用于「分享聊天」这类自带完整内容体的卡片） */
    customMessage: String? = null,
    /** 短视频分享专用：视频地址（接收端无封面时用它取首帧） */
    videoUrl: String = "",
    /** 短视频分享专用：服务端封面图 URL（优先展示，Coil 可直接解码） */
    coverUrl: String = "",
    /** 短视频分享专用：发布者名（视频内左下角标注的是"发视频的人"，不是分享者） */
    publisherName: String = "",
    /** 可选：标题下方的预览区（例如展示即将分享出去的卡片） */
    preview: (@Composable () -> Unit)? = null,
    /** 发送成功后回调（用于退出多选等收尾）；取消/失败不触发 */
    onSent: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    // 返回键/返回手势关闭面板：内嵌模式（AnimatedVisibility，无 Dialog 窗口）下
    // 系统返回不会再自动触发 onDismissRequest，必须显式消费，否则返回会穿透到下层页面。
    // Dialog 模式下 Dialog 自身也会处理返回，两路都只会把面板关掉一次，幂等无害。
    BackHandler { onDismiss() }
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
        // 自带完整内容体的卡片（如「分享聊天」）直接使用传入文本
        customMessage?.takeIf { it.isNotBlank() }?.let { return it }
        val preview = postContent.take(150).replace("\n", " ").trim()
        val prefix = if (titlePrefix.isNotEmpty()) titlePrefix
        else if (postType == "resource") "分享资源" else "分享帖子"
        var msg = prefix + "\n" +
                "━━━━━━━━━━\n" +
                postTitle + "\n" +
                (if (preview.isNotEmpty()) preview + "\n" else "") +
                "━━━━━━━━━━\n" +
                "来自 ${postUsername}\n" +
                "$idKey=$postId"
        // 短视频分享：把视频地址 / 封面 / 发布者名带进消息体，
        // 接收端据此直接渲染视频缩略图（不走白底卡片），并在视频内左下角标注发布者。
        if (videoUrl.isNotEmpty()) msg += "\nvideo_url=$videoUrl"
        if (coverUrl.isNotEmpty()) msg += "\ncover_url=$coverUrl"
        if (publisherName.isNotEmpty()) msg += "\npublisher=$publisherName"
        return msg
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
                // 分享卡片必须落本地消息库：私聊/个人对话的服务端会话预览不可用时，
                // 列表预览全靠本地库推导。不落库的话，分享当下列表靠内存更新看着正常，
                // 一下拉刷新（走服务端）预览就变回空白 / 上一条消息。
                val messageId = result.data ?: 0L
                if (messageId > 0) {
                    val nowMs = System.currentTimeMillis()
                    runCatching {
                        com.aurora.chat.data.repository.LocalMessageStore.saveMessages(
                            ctx,
                            conv.id,
                            listOf(
                                com.aurora.chat.data.api.MessageInfo(
                                    id = messageId,
                                    fromUserId = com.aurora.chat.data.api.AuroraApi.currentUserId,
                                    toUserId = conv.id,
                                    content = msg,
                                    createdAt = nowMs / 1000,
                                    fromUserName = com.aurora.chat.data.api.AuroraApi.currentUserName,
                                    localTimeMs = nowMs
                                )
                            )
                        )
                    }
                }
                Toast.makeText(ctx, "已分享给 ${conv.username}", Toast.LENGTH_SHORT).show()
                onSent()
                onDismiss()
            } else {
                // 发送失败：保持面板打开，允许原地重试或改选其他目标。
                // 此前失败也 onDismiss，用户视角就是"点了一下分享弹窗自己关了、也不知道发没发出去"。
                Toast.makeText(ctx, "分享失败: ${result.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // 遮罩
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable { onDismiss() }
        )

        // 底部面板（高度可拖动调整）。clip 必须加：内容一旦超出面板高度，
        // 不裁剪会画到遮罩上，点击命中也会落到子项上导致误触/误关。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(sheetHeightFraction)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(com.aurora.chat.ui.theme.appSurface())
                .padding(top = 16.dp)
                // 抬高底部避开系统手势导航栏，否则列表最后一项会被导航栏盖住半边且无法再下滑
                .navigationBarsPadding()
        ) {
            // 顶部手柄：只做高度吸附，不再「下滑即关闭」。
            // 关闭统一走点击上方遮罩，避免在面板内滑动列表时被误判成关闭手势。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                sheetHeightFraction = when {
                                    sheetHeightFraction > 0.85f -> 0.88f
                                    sheetHeightFraction < 0.6f -> 0.6f
                                    else -> 0.65f
                                }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                val dpPerPx = with(density) { dragAmount.toDp() }.value
                                sheetHeightFraction = (sheetHeightFraction - dpPerPx / screenHdp)
                                    .coerceIn(0.6f, 0.88f)
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
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // 预览区（可选）：展示将要分享出去的卡片摘要。
            // 必须限高 + 裁剪：预览一旦很高就会把下方列表挤成 0 高度，导致「列表划不动」。
            // 水平居中：卡片本身有固定宽度区间，靠左贴边会显得偏，和聊天里的观感也不一致。
            if (preview != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .heightIn(max = 110.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    preview()
                }
            }

            // 搜索输入框
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg())
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                    cursorBrush = SolidColor(Color(0xFF1E40AF)),
                    decorationBox = { innerTextField ->
                        if (searchText.isEmpty()) {
                            Text("搜索好友或群聊", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
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
                            .background(if (isSelected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                            .clickable { selectedTab = index }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Color.White else com.aurora.chat.ui.theme.appTextSecondary()
                        )
                    }
                }
            }

            // 好友/数字提示
            Text(
                "${filteredList.size} 个${if (selectedTab == 0) "好友" else "群聊"}",
                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // 列表
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else if (filteredList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchText.isNotEmpty()) "未找到匹配的${if (selectedTab == 0) "好友" else "群聊"}"
                        else "暂无${if (selectedTab == 0) "好友" else "群聊"}",
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()
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
                                .clip(RoundedCornerShape(12.dp)).background(Color.Transparent)
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
                                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (isGroup) {
                                    Text(
                                        "群聊",
                                        fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint()
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
                                .background(com.aurora.chat.ui.theme.appCardBg())
                        )
                    }
                }
            }
        }
    }
}

