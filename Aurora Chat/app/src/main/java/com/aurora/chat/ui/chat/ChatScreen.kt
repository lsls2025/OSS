package com.aurora.chat.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.aurora.chat.R
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UnreadBadge
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 像素级平滑滚动：把目标项对齐到列表顶部，带 spring 缓动（不依赖 animateScrollToItem）
private suspend fun LazyListState.smoothScrollToItem(targetIndex: Int) {
    if (targetIndex < 0 || targetIndex >= layoutInfo.totalItemsCount) return
    // 仅计算一次初始偏移：目标项相对视口顶的距离（下为正）。避免动画中每帧读取 layoutInfo/估算，减少开销
    val initialDeltaPx = run {
        val info = layoutInfo
        val target = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        if (target != null) {
            target.offset.toFloat()
        } else {
            var sum = 0
            var count = 0
            for (item in info.visibleItemsInfo) { sum += item.size; count++ }
            val avg = if (count > 0) sum.toFloat() / count else 1f
            (targetIndex - firstVisibleItemIndex) * avg
        }
    }
    if (initialDeltaPx == 0f) return
    // 增量滚动：每帧只 scrollBy 一小段，LazyLayout 仅组合视口内自然滚入的 item，不再跳跃布局远处目标项
    val animation = TargetBasedAnimation(
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        typeConverter = Float.VectorConverter,
        initialValue = 0f,
        targetValue = 1f,
        initialVelocity = 0f
    )
    val startTime = withFrameNanos { it }
    var prevValue = 0f
    do {
        val frameTime = withFrameNanos { it } - startTime
        val value = animation.getValueFromNanos(frameTime)
        val step = initialDeltaPx * (value - prevValue)
        if (step != 0f) scrollBy(step)
        prevValue = value
    } while (!animation.isFinishedFromNanos(frameTime))
    // 末尾精确对齐到顶部（此时目标已可见，仅组合极少 item，几乎无感）
    val finalTarget = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (finalTarget != null && finalTarget.offset != 0) {
        scrollToItem(targetIndex, 0)
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun ChatScreen(
    currentUserId: Long = 0,
    refreshKey: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    scrollRequest: Long? = null,
    onScrollConsumed: () -> Unit = {},
    onOpenChat: (friendId: Long, friendName: String) -> Unit
) {
    val conversations by ChatViewModel.conversations.collectAsState()
    val unreadCounts by ChatViewModel.unreadCounts.collectAsState()
    val isLoading by ChatViewModel.conversationsLoading.collectAsState()
    val isOffline by ChatViewModel.isOffline.collectAsState()
    val groupAvatarRefreshKey by ChatViewModel.groupAvatarRefreshKey.collectAsState()
    val context = LocalContext.current
    // 下拉刷新状态：显示刷新圈圈，加载完成后停止
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(currentUserId, refreshKey) {
        // 进入聊天 tab（refreshKey 变化）时刷新：
        // 非强制 → 命中 30s 新鲜度守卫时直接用内存缓存秒出，避免每次切换板块都发网络请求导致卡顿；
        // 超 30s 或缓存为空时才会真正网络刷新；TCP 推送新消息/IPC 仍会走 force = true 实时刷新。
        ChatViewModel.loadConversations(context, force = false)
        // 载入本账号未读（供底部"聊天"红点、列表红点使用）
        ChatViewModel.loadUnreadForUser(context, currentUserId)
    }

    // 启动时把已保存的 AI 自定义头像 / 名称载入全局状态，保证聊天列表立即生效
    LaunchedEffect(Unit) {
        if (AiAvatarState.bitmap == null) {
            val f = com.aurora.chat.data.local.LocalStorage.getAiAvatarFile(context)
            if (f.exists()) {
                AiAvatarState.bitmap = withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.decodeFile(f.absolutePath)
                }
            }
        }
        if (AiNameState.name == null) {
            AiNameState.name = com.aurora.chat.data.local.LocalStorage.getAiName(context)
        }
    }

    // TCP 推送新消息时自动刷新对话列表（强制刷新，绕过新鲜度守卫）
    LaunchedEffect(Unit) {
        com.aurora.chat.ui.viewmodel.ChatViewModel.newMessageReceived.collect {
            ChatViewModel.loadConversations(context, force = true)
        }
    }



    // 构建包含三个特殊对话的完整列表（放在最前面，受置顶排序影响）
    // 用 remember 派生：仅在数据/置顶/用户变化时重算，避免每次重组都全量读 SP 重排；
    // 同步计算，首帧即可用缓存满数据，配合外部 hoisted LazyListState 精确还原滚动位置。
    val selfChatName = com.aurora.chat.data.api.AuroraApi.currentUserName
    val aiChatName = AiNameState.name ?: "AI"
    val conversationsSnapshot = remember(conversations) { conversations.distinctBy { it.id } }
    // 置顶计数：长按置顶/取消后会自增，作为重排触发条件
    val pinTick by com.aurora.chat.ui.viewmodel.ChatViewModel.pinChanged.collectAsState()
    // 本地特殊对话（个人对话/DeepSeek/通知中心）消息变化信号：新增即重排，让有内容的特殊对话按时间上浮
    val localTick by com.aurora.chat.ui.chat.LocalChatRefreshTick.flow.collectAsState()
    // AI 入口的预览要跟随「当前激活的 AI 会话」，否则多会话时列表会一直显示默认会话的旧预览
    val aiActiveConvId = getActiveAiConvId(context)
    val aiActiveKey = if (aiActiveConvId == DEFAULT_AI_CONV_ID) "ai" else "ai_$aiActiveConvId"
    val expandedConversations = remember(currentUserId, conversationsSnapshot, pinTick, localTick, selfChatName, aiChatName, aiActiveConvId) {
        if (currentUserId > 0) {
            val selfLastMsg = loadSelfChatLastMsg(context, currentUserId)
            val aiLastMsg = getAiConvPreview(context, currentUserId, aiActiveConvId)
            val notifLastMsg = loadLocalChatLastMsg(context, currentUserId, "notification")
            // 三个特殊对话的最后消息时间戳，用于按时间参与会话排序（有消息时自然上浮，无消息沉底）
            val selfLastTime = loadLocalChatLastTime(context, currentUserId, "self")
            val aiLastTime = loadLocalChatLastTime(context, currentUserId, aiActiveKey)
            // 【性能】通知最后时间走 prefs 缓存（O(1)），不再每次全量解析通知 JSON 文件
            val notifLastTime = loadNotificationLastTime(context, currentUserId)
            val special = mutableListOf<ConversationInfo>(
                ConversationInfo(id = 0L, email = "", username = selfChatName, createdAt = selfLastTime, lastMessage = selfLastMsg, lastTime = selfLastTime),
                ConversationInfo(id = AI_CHAT_ID, email = "", username = aiChatName, createdAt = aiLastTime, lastMessage = aiLastMsg, lastTime = aiLastTime),
                ConversationInfo(id = NOTIFICATION_CHAT_ID, email = "", username = "通知中心", createdAt = notifLastTime, lastMessage = notifLastMsg, lastTime = notifLastTime)
            )
            val all = conversations.toMutableList()
            all.removeAll { it.id == 0L || it.id == AI_CHAT_ID || it.id == NOTIFICATION_CHAT_ID }
            // 个人对话/DeepSeek/通知中心作为普通项参与排序：默认不置顶，有消息时按最后消息时间上浮，无消息时沉底。
            // 如需固定在顶部，可长按该会话手动置顶。
            all.addAll(special)
            val prefs = context.getSharedPreferences("aurora_friend_settings", android.content.Context.MODE_PRIVATE)
            all.sortedWith(
                compareByDescending<ConversationInfo> {
                    val key = when {
                        it.id == 0L || it.id == AI_CHAT_ID || it.id == NOTIFICATION_CHAT_ID -> "pin_${it.id}"
                        it.id < 0 -> "pin_group_${-(it.id + 1000)}"
                        else -> "pin_${it.id}"
                    }
                    prefs.getBoolean(key, false)
                }.thenByDescending { it.lastTime }
            ).distinctBy { it.id }
        } else {
            conversationsSnapshot
        }
    }

    // 双击底部聊天图标：循环定位到下一个未读会话（仅滚动，绝不改变会话排序）
    val lastUnreadScrollPos = remember { mutableStateOf(-1) }
    LaunchedEffect(scrollRequest) {
        if (scrollRequest == null) return@LaunchedEffect
        val list = expandedConversations
        val unreadPositions = list.indices.filter { (unreadCounts[list[it].id] ?: 0) > 0 }
        if (unreadPositions.isEmpty()) {
            listState.smoothScrollToItem(0)
            lastUnreadScrollPos.value = -1
        } else {
            val next = if (lastUnreadScrollPos.value < 0 || lastUnreadScrollPos.value >= unreadPositions.last()) {
                unreadPositions.first()
            } else {
                unreadPositions.first { it > lastUnreadScrollPos.value }
            }
            listState.smoothScrollToItem(next)
            lastUnreadScrollPos.value = next
        }
        onScrollConsumed()
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (!isRefreshing) {
                isRefreshing = true
                com.aurora.chat.KeepAliveManager.tcpReconnectIfDisconnected(context)
                // 下拉兜底：从 SP 恢复/补齐未读红点（合并式，不会清掉内存已有增量）
                ChatViewModel.loadUnreadForUser(context, currentUserId)
                ChatViewModel.loadConversations(context, force = true) { isRefreshing = false }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = true
    ) {
        // 离线提示条（放在最顶部，在自己的对话上方）
        if (isOffline) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(Color(0xFFFFF3CD))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("当前网络不可用，显示的是缓存的会话列表",
                        fontSize = 12.sp, color = Color(0xFF856404))
                }
             Spacer(Modifier.height(4.dp))
            }
        }

        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            }
        } else if (expandedConversations.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("暂无会话", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            }
        } else {
            items(expandedConversations, key = { it.id }) { conv ->
            val internalGroupId = remember(conv.id) {
                if (conv.id < 0 && conv.id != AI_CHAT_ID && conv.id != NOTIFICATION_CHAT_ID) -(conv.id + 1000) else null
            }
            val unread = unreadCounts[conv.id] ?: 0
            val convRemarkKey = if (internalGroupId != null) "remark_group_$internalGroupId" else "remark_${conv.id}"
            val convRemark = remember(convRemarkKey) {
                context.getSharedPreferences("aurora_friend_settings", android.content.Context.MODE_PRIVATE)
                    .getString(convRemarkKey, "") ?: ""
            }
            val pinItemKey = when {
                internalGroupId != null -> "pin_group_$internalGroupId"
                else -> "pin_${conv.id}"
            }
            val pinnedItem = remember(pinItemKey) {
                context.getSharedPreferences("aurora_friend_settings", android.content.Context.MODE_PRIVATE)
                    .getBoolean(pinItemKey, false)
            }
            // 置顶背景铺满整行（左右贴边、无圆角）；有壁纸时不画灰底，让壁纸完整透出
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (pinnedItem) Modifier.background(Color(0xFFF6F7F8))
                        else Modifier
                    )
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ConversationItem(
                avatar = {
                    when (conv.id) {
                        0L -> UserAvatar(userId = currentUserId, userName = conv.username, size = 48.dp)
                        AI_CHAT_ID -> {
                            val aiName = AiNameState.name ?: "AI"
                            val custom = AiAvatarState.bitmap
                            if (custom != null) {
                                Image(bitmap = custom.asImageBitmap(), contentDescription = aiName,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                            } else {
                                AiDefaultAvatar(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                            }
                        }
                        NOTIFICATION_CHAT_ID -> Image(painter = painterResource(R.drawable.wd),
                            contentDescription = "通知", modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        else -> {
                            if (internalGroupId != null) {
                                GroupAvatar(internalGroupId = internalGroupId, groupName = conv.username, size = 48.dp, refreshKey = groupAvatarRefreshKey)
                            } else {
                                UserAvatar(userId = conv.id, userName = conv.username, size = 48.dp)
                            }
                        }
                    }
                },
                name = conv.username,
                lastMsg = conv.lastMessage,
                convId = conv.id,
                unread = unread,
                context = context,
                internalGroupId = internalGroupId,
                onClick = { onOpenChat(conv.id, convRemark.ifEmpty { conv.username }) }
            )
            // 分割线，受"去除分割线"设置控制
            if (com.aurora.chat.ui.components.LocalShowDividers.current) {
                Box(
                    modifier = Modifier
                        .padding(start = 60.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Color(0xFFE5E7EB))
                )
            }
                }
            }
        }
        }
    }
    }
}

@Composable
private fun ConversationItem(
    avatar: @Composable () -> Unit,
    name: String,
    lastMsg: String,
    convId: Long = 0,
    unread: Int = 0,
    context: android.content.Context,
    internalGroupId: Long? = null,
    onClick: () -> Unit
) {
    // 缓存预览文本，避免每次重组时重新计算
    val annotatedText = remember(lastMsg) {
        if (lastMsg.isNotEmpty()) buildPreviewText(lastMsg) else null
    }

    val prefs = remember { context.getSharedPreferences("aurora_friend_settings", android.content.Context.MODE_PRIVATE) }

    // 备注
    val remarkKey = if (internalGroupId != null) "remark_group_$internalGroupId" else "remark_$convId"
    var remark by remember(remarkKey) { mutableStateOf(prefs.getString(remarkKey, "") ?: "") }
    LaunchedEffect(remarkKey) { remark = prefs.getString(remarkKey, "") ?: "" }
    val displayName = remark.ifEmpty { name }

    // 置顶
    val pinKey = if (internalGroupId != null) "pin_group_$internalGroupId" else "pin_$convId"
    var pinnedState by remember(pinKey) { mutableStateOf(prefs.getBoolean(pinKey, false)) }
    LaunchedEffect(pinKey) { pinnedState = prefs.getBoolean(pinKey, false) }

    var showMenu by remember { mutableStateOf(false) }
    var showRemarkDialog by remember { mutableStateOf(false) }
    var remarkInput by remember { mutableStateOf("") }

    // 备注弹窗
    if (showRemarkDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRemarkDialog = false },
            containerColor = Color.White,
            title = { Text("给「$name」备注", fontWeight = FontWeight.Bold) },
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
                    prefs.edit().putString(remarkKey, newRemark).apply()
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

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { showMenu = true }
                    )
                }
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像点击进入聊天，但移除水波纹特效（indication = null）
            Box(
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
            ) {
                avatar()
                if (unread > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-3).dp).zIndex(1f)
                    ) {
                        UnreadBadge(count = unread, size = 18.dp, fontSize = 11.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                if (annotatedText != null) {
                    Text(
                        text = annotatedText,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 长按菜单
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (pinnedState) "取消置顶" else "置顶",
                        fontSize = 14.sp,
                        color = if (pinnedState) Color(0xFF6B7280) else Color(0xFF1E40AF)
                    )
                },
                onClick = {
                    val newVal = !pinnedState
                    pinnedState = newVal
                    prefs.edit().putBoolean(pinKey, newVal).apply()
                    ChatViewModel.notifyPinChanged()
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = {
                    Text("备注", fontSize = 14.sp, color = if (remark.isNotEmpty()) Color(0xFF6B7280) else Color(0xFF1E40AF))
                },
                onClick = {
                    showMenu = false
                    remarkInput = remark
                    showRemarkDialog = true
                }
            )
        }
    }
}

/** 构建预览消息 AnnotatedString：纯文字灰色，表情保持原色 */
private fun buildPreviewText(text: String): androidx.compose.ui.text.AnnotatedString {
    val grayStyle = SpanStyle(color = Color(0xFF9CA3AF))
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            val charCount = Character.charCount(codePoint)
            if (isEmoji(codePoint)) {
                // 表情：不应用任何样式，保持默认颜色
                append(text.substring(i, i + charCount))
            } else {
                // 纯文字：应用灰色
                withStyle(grayStyle) {
                    append(text.substring(i, i + charCount))
                }
            }
            i += charCount
        }
    }
}

/** 从本地加载自己对话的最后一条消息预览 */
private fun loadSelfChatLastMsg(ctx: android.content.Context, currentUserId: Long): String {
    try {
        val prefs = ctx.getSharedPreferences("self_chat_${currentUserId}", android.content.Context.MODE_PRIVATE)
        return prefs.getString("last_msg", "") ?: ""
    } catch (_: Exception) { return "" }
}

/** 判断字符是否为 Emoji */
private fun isEmoji(codePoint: Int): Boolean {
    return (codePoint in 0x1F300..0x1F9FF) ||          // 杂项符号和 pictographs
           (codePoint in 0x1FA00..0x1FA6F) ||          // 象棋符号扩展
           (codePoint in 0x1FA70..0x1FAFF) ||          // 符号扩展-A
           (codePoint in 0x2600..0x27BF) ||            // 杂项符号
           (codePoint in 0xFE00..0xFE0F) ||            // 变体选择符
           (codePoint in 0x1F1E0..0x1F1FF) ||          // 国旗（区域指示符）
           (codePoint in 0x200D..0x200D) ||            // 零宽连字符
           (codePoint in 0x20E3..0x20E3) ||            // 组合围圈键帽
           (codePoint in 0x231A..0x23FF) ||            // 杂项技术符号
           (codePoint in 0x25AA..0x25FF) ||            // 几何形状
           (codePoint in 0x2934..0x2935) ||            // 箭头补充
           (codePoint in 0x2B05..0x2B55) ||            // 杂项符号和箭头
           (codePoint in 0x3030..0x3030) ||            // 波浪线
           (codePoint in 0x3297..0x3299) ||            // 杂项符号
           (codePoint in 0x1F600..0x1F64F) ||          // 表情符号
           (codePoint in 0x2700..0x27BF) ||            // 丁当符号
           (codePoint in 0x1F680..0x1F6FF) ||          // 交通和地图符号
           (codePoint in 0x1F900..0x1F9FF)             // 补充符号和 pictographs
}
