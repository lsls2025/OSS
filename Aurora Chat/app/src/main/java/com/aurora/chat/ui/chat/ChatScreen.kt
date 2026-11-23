package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
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
import com.aurora.chat.ui.components.avatarCornerRadius
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

/**
 * 聊天列表下拉刷新状态。
 *
 * 刷新动作发生在 ChatScreen 内部，但转圈要显示在「聊天」标题右侧（AuroraTopBar，属于 MainActivity），
 * 所以用一个进程级快照状态把「正在刷新」传出去：置 true 后标题右侧出现蓝色转圈，完成/取消后置 false。
 */
/** 会话列表头像尺寸：调小会同步压缩整行高度（联系人/群/通知共用） */
private val ChatListAvatarSize = 40.dp

object ChatRefreshState {
    val refreshing = mutableStateOf(false)

    /** 转圈至少可见这么久：服务器很快时（本地几十毫秒就返回）不至于一闪而过 */
    private const val MIN_VISIBLE_MS = 800L
    // start() 在主线程、finish() 可能在网络回调线程，跨线程读写需要可见性
    @Volatile private var startedAt = 0L
    /** 刷新代号：上一次刷新遗留的延迟隐藏任务不能误伤新一轮刷新 */
    @Volatile private var generation = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun start() {
        generation++
        startedAt = android.os.SystemClock.elapsedRealtime()
        refreshing.value = true
    }

    fun finish() {
        val gen = generation
        val remain = MIN_VISIBLE_MS - (android.os.SystemClock.elapsedRealtime() - startedAt)
        if (remain <= 0L) {
            refreshing.value = false
        } else {
            mainHandler.postDelayed({
                if (gen == generation) refreshing.value = false
            }, remain)
        }
    }
}

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
    val hiddenIds by ChatViewModel.hiddenConversations.collectAsState()
    val unreadCounts by ChatViewModel.unreadCounts.collectAsState()
    val isLoading by ChatViewModel.conversationsLoading.collectAsState()
    val groupAvatarRefreshKey by ChatViewModel.groupAvatarRefreshKey.collectAsState()
    val context = LocalContext.current
    // 液态模式：会话卡片玻璃化（折射源用纯色画布，避免纯白塑料感）
    // 聊天列表液态开关（关 = 经典聊天列表）
    // 长按菜单弹窗（删除/备注）的根级状态：弹窗必须置于列表之上（覆盖全屏），
    // 否则液态模式下 LiquidDialog 的 in-window 玻璃层会被限制在列表项范围内。
    var deleteTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var remarkTarget by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    // 长按菜单当前归属哪个会话（null = 没有菜单）。只用来在列表上铺一层"加深"遮罩：
    // 微信长按列表时整块列表会压暗一档，让浮出的菜单和底层内容分层。
    var menuOpenConvId by remember { mutableStateOf<Long?>(null) }
    var remarkInput by remember { mutableStateOf("") }
    val listPrefs = remember { context.getSharedPreferences("aurora_friend_settings", android.content.Context.MODE_PRIVATE) }
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
    // 自聊会话（id = 自己）就是「文件助手」：桌面端和手机端互相传文件的同一个入口，
    // 消息走服务端私聊链路，所以两端看到的是同一份内容。
    val selfChatName = "文件助手"
    val aiChatName = AiNameState.name ?: "AI"
    // 【首帧铺底】冷启动时 ViewModel 的磁盘预热是异步的，第一帧拿不到列表，
    // 于是界面会先只显示个人对话 / AI / 通知中心这 3 个固定项。
    // 这里在组合阶段同步取一次磁盘快照（Application 启动已预热到内存；极端情况才同步读一次文件），
    // 保证进入聊天页的**第一帧**就是磁盘里的完整会话列表。
    val diskConversations = remember(currentUserId) {
        if (currentUserId <= 0) emptyList()
        else com.aurora.chat.data.local.LocalConversationStore.peek()
            ?: com.aurora.chat.data.local.LocalConversationStore.loadBlocking(context)
    }
    // 实时列表一旦有了就以它为准（会覆盖首帧的磁盘快照）
    val baseConversations = if (conversations.isNotEmpty()) conversations else diskConversations
    // 过滤掉用户手动隐藏的会话（仅从列表移除，联系人/群与消息保留）。
    // 个人对话(服务端自聊 id=currentUserId) 不参与隐藏：老版本可能把「自己」会话隐藏过，
    // 若不豁免，换成新实现后默认项会被静默过滤掉，表现为「个人对话又没了」。
    val conversationsSnapshot = remember(baseConversations, hiddenIds, currentUserId) {
        baseConversations.distinctBy { it.id }
            .filter { it.id == currentUserId || it.id !in hiddenIds }
    }
    // 置顶计数：长按置顶/取消后会自增，作为重排触发条件
    val pinTick by com.aurora.chat.ui.viewmodel.ChatViewModel.pinChanged.collectAsState()
    // 本地特殊对话（个人对话/DeepSeek/通知中心）消息变化信号：新增即重排，让有内容的特殊对话按时间上浮
    val localTick by com.aurora.chat.ui.chat.LocalChatRefreshTick.flow.collectAsState()
    // AI 入口的预览要跟随「当前激活的 AI 会话」，否则多会话时列表会一直显示默认会话的旧预览
    val aiActiveConvId = getActiveAiConvId(context)
    val aiActiveKey = if (aiActiveConvId == DEFAULT_AI_CONV_ID) "ai" else "ai_$aiActiveConvId"
    // 首帧就同时展示真实会话 + 3 个特殊会话（本地缓存已在 ViewModel 预填），不再「先加载再显示」
    val expandedConversations = remember(currentUserId, conversationsSnapshot, pinTick, localTick, selfChatName, aiChatName, aiActiveConvId, isLoading) {
        if (currentUserId > 0) {
            val aiLastMsg = getAiConvPreview(context, currentUserId, aiActiveConvId)
            val notifLastMsg = loadLocalChatLastMsg(context, currentUserId, "notification")
            // 两个本地特殊对话的最后消息时间戳，用于按时间参与会话排序（有消息时自然上浮，无消息沉底）
            val aiLastTime = loadLocalChatLastTime(context, currentUserId, aiActiveKey)
            // 【性能】通知最后时间走 prefs 缓存（O(1)），不再每次全量解析通知 JSON 文件
            val notifLastTime = loadNotificationLastTime(context, currentUserId)
            // 「个人对话」= 服务端自聊会话（id = 自己 userId），走普通私聊链路，消息落库可跨设备。
            // 这里只放一个占位项保证「默认显示」；服务端一旦返回同 id 的真实会话，
            // 下面的 sortedWith 是稳定排序、distinctBy 保留先者，会优先用服务端的真实预览与时间。
            val special = mutableListOf<ConversationInfo>(
                ConversationInfo(id = currentUserId, email = "", username = selfChatName, createdAt = 0L, lastMessage = "", lastTime = 0L),
                ConversationInfo(id = AI_CHAT_ID, email = "", username = aiChatName, createdAt = aiLastTime, lastMessage = aiLastMsg, lastTime = aiLastTime),
                ConversationInfo(id = NOTIFICATION_CHAT_ID, email = "", username = "通知中心", createdAt = notifLastTime, lastMessage = notifLastMsg, lastTime = notifLastTime)
            )
            // 基于已过滤（含隐藏会话剔除）的快照构建，delete 后才会真正从列表消失
            val all = conversationsSnapshot.toMutableList()
            // 只剔除本地内置的两个特殊会话 id（它们由 special 重新构造）；
            // id = currentUserId 的服务端自聊会话不再剔除——它就是现在的「个人对话」。
            all.removeAll {
                it.id == 0L || it.id == AI_CHAT_ID || it.id == NOTIFICATION_CHAT_ID
            }
            // 个人对话(服务端自聊)/DeepSeek/通知中心作为普通项参与排序：默认不置顶，有消息时上浮，无消息沉底。
            // 服务端已返回自聊会话时，上面的快照项排在 special 占位项之前，distinctBy 会保留真实数据那条。
            all.addAll(special)
            val prefs = context.getSharedPreferences("aurora_friend_settings", android.content.Context.MODE_PRIVATE)
            all.sortedWith(
                compareByDescending<ConversationInfo> {
                    val key = when {
                        it.id == AI_CHAT_ID || it.id == NOTIFICATION_CHAT_ID -> "pin_${it.id}"
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

    // 预热会话头像：只走内存/磁盘，不发网络请求。把屏幕外会话的头像提前解码进内存，
    // 滚动进屏时组合期直接同步命中，避免每次滑动都先闪一下默认头像。
    LaunchedEffect(currentUserId, expandedConversations) {
        if (expandedConversations.isEmpty()) return@LaunchedEffect
        val userIds = LinkedHashSet<Long>()
        val groupIds = LinkedHashSet<Long>()
        if (currentUserId > 0) userIds.add(currentUserId)
        expandedConversations.forEach { conv ->
            when {
                conv.id == AI_CHAT_ID || conv.id == NOTIFICATION_CHAT_ID -> Unit
                conv.id < 0 -> groupIds.add(-(conv.id + 1000))
                else -> userIds.add(conv.id)
            }
        }
        com.aurora.chat.data.local.AvatarCache.preloadUserAvatars(context, userIds)
        com.aurora.chat.data.local.AvatarCache.preloadGroupAvatars(context, groupIds)
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

    // ===== 微信式下拉（与联系人/我的同一套手感）=====
    // 顶到列表边界后继续下拉，内容整体跟着手指位移，松手回弹；
    // 超过阈值则静默刷新，并在「聊天」标题右侧显示蓝色转圈（ChatRefreshState → AuroraTopBar）。
    // 标题栏不在这个盒子里，所以只位移列表本身，标题不会被拽走。
    var overscrollPx by remember { mutableFloatStateOf(0f) }
    val overscrollStartFactor = 0.82f
    val density = LocalDensity.current
    val overscrollSoftPx = with(density) {
        (240.dp.toPx() * overscrollStartFactor).coerceAtLeast(1f)
    }
    // 松手时内容被拽出超过这个距离 → 视为想刷新（72dp 对应手指行程约 105dp，轻轻一拉就够）
    val refreshThresholdPx = with(density) { 72.dp.toPx() }
    val overscrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 反向拖：先把拽出去的位移按 1:1 收回，收完再让列表正常滚动
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
                // 顶到边界还想继续拖：在列表消费之前截走，按渐进阻力记成过滚动量
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

            // 松手瞬间掐掉惯性：否则回弹要等列表惯性跑完，看起来迟钝
            override suspend fun onPreFling(available: Velocity): Velocity =
                if (overscrollPx != 0f) available else Velocity.Zero

            // 松手：先判定是否刷新，再回弹 —— 两者并行，不等回弹动画结束才发请求
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val released = overscrollPx
                if (released > refreshThresholdPx && !isRefreshing) {
                    // 一松手就立刻开始强刷新：转圈马上出现，网络请求与回弹同时进行
                    isRefreshing = true
                    ChatRefreshState.start()
                    // 只重拉数据，不动 TCP 长连接：连接断了由 TcpService 自身重连循环处理，
                    // 这里再 stop/start 会让每次下拉都重建一条 TCP 连接（服务端表现为频繁上下线）。
                    // 下拉兜底：从 SP 恢复/补齐未读红点（合并式，不会清掉内存已有增量）
                    ChatViewModel.loadUnreadForUser(context, currentUserId)
                    ChatViewModel.loadConversations(context, force = true) {
                        isRefreshing = false
                        ChatRefreshState.finish()
                    }
                }
                if (released != 0f) {
                    animate(
                        initialValue = released,
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 700f)
                    ) { value, _ -> overscrollPx = value }
                }
                return Velocity.Zero
            }
        }
    }

    // 外层裁掉越界部分：标题栏不在这个盒子里，永远不会被拽动
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(overscrollConnection)
            .offset { IntOffset(0, overscrollPx.roundToInt()) },
        userScrollEnabled = true,
        contentPadding = PaddingValues(bottom = 0.dp)
    ) {
        // 联网刷新中且有缓存内容时，顶部显示一条细进度条作为轻量“加载中”提示：
        // 不阻断列表、不显示碍眼的「加载中」文字，满足“加载提示可以进行”的需求
        if (isLoading && expandedConversations.isNotEmpty()) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp).padding(bottom = 2.dp),
                    color = Color(0xFF3B82F6)
                )
            }
        }

        // 不再显示「加载中」占位：冷启动先用本地历史直接铺列表；没缓存时静默等网络返回后直接出内容。
        if (expandedConversations.isEmpty()) {
            if (!isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text("暂无会话", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
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
            // 置顶背景铺满整行（左右贴边、无圆角）；有壁纸时不画灰底，让壁纸完整透出。
            // 必须用 Column 纵向排列：之前是 Box，分割线会被叠在本行顶部，
            // 第一条就与标题栏分割线重合，看起来像多了一条线。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            // 长按某一行时只把「这一行」加深（微信那种整行按下灰），
                            // 不是给整个列表铺遮罩 —— 其它行保持原样。
                            menuOpenConvId == conv.id -> Modifier.background(com.aurora.chat.ui.theme.appCardBg())
                            pinnedItem -> Modifier.background(com.aurora.chat.ui.theme.appCardBg())
                            else -> Modifier
                        }
                    )
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ConversationItem(
                avatar = {
                    when (conv.id) {
                        AI_CHAT_ID -> {
                            val aiName = AiNameState.name ?: "AI"
                            val custom = AiAvatarState.bitmap
                            if (custom != null) {
                                Image(bitmap = custom.asImageBitmap(), contentDescription = aiName,
                                    modifier = Modifier.size(ChatListAvatarSize).clip(RoundedCornerShape(avatarCornerRadius(ChatListAvatarSize))), contentScale = ContentScale.Crop)
                            } else {
                                AiDefaultAvatar(modifier = Modifier.size(ChatListAvatarSize), size = ChatListAvatarSize)
                            }
                        }
                        NOTIFICATION_CHAT_ID -> Image(painter = painterResource(R.drawable.wd),
                            contentDescription = "通知", modifier = Modifier.size(ChatListAvatarSize).clip(RoundedCornerShape(avatarCornerRadius(ChatListAvatarSize))), contentScale = ContentScale.Crop)
                        else -> {
                            if (internalGroupId != null) {
                                GroupAvatar(internalGroupId = internalGroupId, groupName = conv.username, size = ChatListAvatarSize, refreshKey = groupAvatarRefreshKey)
                            } else {
                                UserAvatar(userId = conv.id, userName = conv.username, size = ChatListAvatarSize)
                            }
                        }
                    }
                },
                name = conv.username,
                lastMsg = conv.lastMessage,
                lastTime = conv.lastTime,
                convId = conv.id,
                unread = unread,
                context = context,
                internalGroupId = internalGroupId,
                // 个人对话就是服务端自聊会话，直接进；不再重定向到本地 id=0。
                onClick = { onOpenChat(conv.id, convRemark.ifEmpty { conv.username }) },
                menuOpen = menuOpenConvId == conv.id,
                onMenuOpenChange = { open -> menuOpenConvId = if (open) conv.id else null },
                onRequestRemark = { key, n, cur -> remarkInput = cur; remarkTarget = Triple(key, n, cur) },
                // 个人对话(自聊)与 AI/通知中心一样不允许长按删除，避免隐藏后默认项消失
                onRequestDelete = if (conv.id != currentUserId && conv.id != AI_CHAT_ID && conv.id != NOTIFICATION_CHAT_ID)
                    ({ id, n -> deleteTarget = id to n }) else null
            )
                } // 关闭 16dp 横向内边距的 Column
                // 分割线：右端贴到屏幕右边缘（只有左侧 60dp 缩进对齐文字）
                if (com.aurora.chat.ui.components.LocalShowDividers.current) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 60.dp)
                            .height(0.5.dp)
                            .background(com.aurora.chat.ui.theme.appDivider())
                    )
                }
            }
        }
        }
    }

    // ===== 长按菜单弹窗：根级覆盖层（液态模式玻璃卡片铺满全屏，不再被限制在列表项内）=====
    if (deleteTarget != null) {
        val (delId, delName) = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确定删除「$delName」的会话吗？联系人不会被删除，重新发消息即可恢复。",
                    fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ChatViewModel.hideConversation(context, delId)
                    // 群会话：如果这个群我是「被踢出/已退出」的状态，删除会话时把本地退出记录一起清掉。
                    // 否则隐藏集合一旦被清空（换设备、清理数据），这个群又会被重新拼回列表。
                    if (delId < 0) {
                        runCatching {
                            com.aurora.chat.data.local.GroupExitStore.remove(context, -(delId + 1000))
                        }
                    }
                    deleteTarget = null
                }) { Text("删除", color = Color(0xFFF04438)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
    if (remarkTarget != null) {
        val (rk, rn, rcur) = remarkTarget!!
        AlertDialog(
            onDismissRequest = { remarkTarget = null },
            title = { Text("给「$rn」备注", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("当前备注：", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (rcur.isNotEmpty()) rcur else "（未设置）",
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(12.dp))
                    BasicTextField(
                        value = remarkInput,
                        onValueChange = { remarkInput = it },
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box { if (remarkInput.isEmpty()) Text("输入备注名称", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    listPrefs.edit().putString(rk, remarkInput.trim()).apply()
                    remarkTarget = null
                }) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { remarkTarget = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
    }
}

@Composable
private fun ConversationItem(
    avatar: @Composable () -> Unit,
    name: String,
    lastMsg: String,
    lastTime: Long = 0,
    convId: Long = 0,
    unread: Int = 0,
    context: android.content.Context,
    internalGroupId: Long? = null,
    onClick: () -> Unit,
    menuOpen: Boolean = false,
    onMenuOpenChange: (Boolean) -> Unit = {},
    onRequestRemark: (remarkKey: String, name: String, currentRemark: String) -> Unit,
    onRequestDelete: ((convId: Long, name: String) -> Unit)? = null
) {
    // 缓存预览文本，避免每次重组时重新计算。
    // 【性能】预览只取首个片段：buildPreviewText 是逐字符着色（O(n)），
    // 而分享卡片正文可能内嵌整段聊天记录（现在不限制条数），直接整段处理会造成列表卡顿。
    val annotatedText = remember(lastMsg) {
        if (lastMsg.isNotEmpty()) buildPreviewText(conversationPreviewSource(lastMsg)) else null
    }

    // 名字右侧的最后消息时间，按微信规则格式化（今天=时刻 / 昨天 / 本周=星期X / 同年=月日 / 往年=年月日）
    val timeLabel = remember(lastTime) { formatConversationListTime(lastTime) }

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

    // 菜单开关由列表根统一持有：列表据此压暗一档（微信式"列表加深"），
    // 菜单本身只管上报开/关，避免每行各存一份状态导致遮罩残留。
    val showMenu = menuOpen

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onMenuOpenChange(true) }
                    )
                }
                .padding(vertical = 5.dp),
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
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        // 名字过长时按微信的方式单行省略，把右侧空间让给时间
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (timeLabel.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = timeLabel,
                            fontSize = 11.5.sp,
                            color = com.aurora.chat.ui.theme.appTextHint(),
                            maxLines = 1
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                // 预览行固定占位：没有内容时用零宽空格撑住这一行的高度。
                // 否则 Column 少一行、被 Row 的 CenterVertically 重新居中，
                // 会话名就会整体往下沉（空对话看着名字偏低）。
                Text(
                    text = annotatedText ?: androidx.compose.ui.text.AnnotatedString("\u200B"),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 长按菜单
        // 底色固定纯白、零色调抬升：M3 默认的 surfaceContainer（那种偏灰紫的 M3 底色）
        // 在这种系统级长按菜单里显得很"框架"，直接铺白更贴近微信/QQ 的观感。
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { onMenuOpenChange(false) },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            tonalElevation = 0.dp
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (pinnedState) "取消置顶" else "置顶",
                        fontSize = 14.sp,
                        color = if (pinnedState) com.aurora.chat.ui.theme.appTextSecondary() else Color(0xFF1E40AF)
                    )
                },
                onClick = {
                    val newVal = !pinnedState
                    pinnedState = newVal
                    prefs.edit().putBoolean(pinKey, newVal).apply()
                    ChatViewModel.notifyPinChanged()
                    // 置顶状态同步到服务端：重装/换机后自动还原
                    com.aurora.chat.data.local.ClientPrefsSync.schedulePush(context, immediate = true)
                    onMenuOpenChange(false)
                }
            )
            DropdownMenuItem(
                text = {
                    Text("备注", fontSize = 14.sp, color = if (remark.isNotEmpty()) com.aurora.chat.ui.theme.appTextSecondary() else Color(0xFF1E40AF))
                },
                onClick = {
                    onMenuOpenChange(false)
                    onRequestRemark(remarkKey, name, remark)
                }
            )
            if (onRequestDelete != null) {
                DropdownMenuItem(
                    text = {
                        Text("删除", fontSize = 14.sp, color = Color(0xFFF04438))
                    },
                    onClick = {
                        onMenuOpenChange(false)
                        onRequestDelete.invoke(convId, name)
                    }
                )
            }
        }
    }
}

/** 构建预览消息 AnnotatedString：纯文字灰色，表情保持原色 */
private fun buildPreviewText(text: String): androidx.compose.ui.text.AnnotatedString {
    val grayStyle = SpanStyle(color = com.aurora.chat.ui.theme.appTextHint())
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

/**
 * 会话列表的预览源文本：分享类卡片只显示友好标题，普通消息最多取前 80 个字符。
 * 避免对可能极长的卡片载荷做逐字符着色与整段文本测量。
 */
private fun conversationPreviewSource(lastMsg: String): String {
    val trimmed = lastMsg.trimStart()
    // 群聊预览由服务端拼成「发送者: 正文」（senderName + ": " + msg），
    // 分享卡片判定前先尝试剥掉这层前缀：只有「不含空白的名字 + 冒号」且其后确实以
    // 分享卡片前缀开头才剥，避免误伤「12:30 开会」这类普通正文。
    val afterColon = trimmed.substringAfter(':', trimmed).trimStart()
    val colonHead = trimmed.substringBefore(':')
    val stripped = if (afterColon.length < trimmed.length &&
        colonHead.isNotEmpty() && colonHead.none { it.isWhitespace() } &&
        isShareCardMessage(afterColon)
    ) afterColon else trimmed
    if (isShareCardMessage(stripped)) {
        val firstLine = stripped.substringBefore('\n').trim()
        // 聊天记录卡片：会话列表预览统一显示「[聊天记录]」，
        // 不再显示「[分享聊天]」，也不把卡片载荷原样透出来
        if (firstLine == "分享聊天" || firstLine == "聊天记录") return "[聊天记录]"
        return if (firstLine.isNotEmpty()) "[$firstLine]" else "[分享]"
    }
    // 历史缓存/旧版本已把预览存成裸的「聊天记录」：补回方括号，
    // 保证和发送当下、刷新之后的观感一致（只做完全相等匹配，不误伤普通正文）。
    if (trimmed == "聊天记录") return "[聊天记录]"
    // 群公告卡片：最终兜底归一化。服务端群聊预览带「发送者: 」前缀，ViewModel 各路径已归一化，
    // 但磁盘缓存首帧 / ensureConversation 等路径可能漏网，这里保证列表永不出现「系统公告数据…」原始串。
    val announcement = normalizeGroupAnnouncementPreview(lastMsg)
    if (announcement != lastMsg) return announcement
    return if (lastMsg.length > 80) lastMsg.take(80) else lastMsg
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

private val WEEKDAY_NAMES = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

/**
 * 会话列表右上角的最后消息时间，按微信的规则格式化：
 * 1. 今天            → 17:39
 * 2. 昨天            → 昨天
 * 3. 本周（周一起）  → 星期一 … 星期日
 * 4. 今年更早        → 8月3日
 * 5. 往年            → 2025年8月3日
 * 兼容秒 / 毫秒两种时间戳；<= 0 视为无时间，返回空串（列表里不显示、不占位）。
 */
private fun formatConversationListTime(raw: Long): String {
    if (raw <= 0L) return ""
    // 兼容：大于 1e11 按毫秒处理，否则按秒处理（服务端 last_time 是秒）
    val ms = if (raw > 100_000_000_000L) raw else raw * 1000L
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = ms }

    val sameDay = now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) {
        return java.lang.String.format(
            java.util.Locale.US, "%02d:%02d",
            target.get(java.util.Calendar.HOUR_OF_DAY),
            target.get(java.util.Calendar.MINUTE)
        )
    }

    // 今天 00:00 与 昨天 00:00
    val startOfToday = (now.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val startOfYesterday = (startOfToday.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_MONTH, -1)
    }
    if (ms >= startOfYesterday.timeInMillis) return "昨天"

    // 本周一 00:00（Calendar 里 周日=1、周一=2 … 周六=7）
    val startOfWeek = (startOfToday.clone() as java.util.Calendar).apply {
        val dow = get(java.util.Calendar.DAY_OF_WEEK)
        val daysFromMonday = if (dow == java.util.Calendar.SUNDAY) 6 else dow - java.util.Calendar.MONDAY
        add(java.util.Calendar.DAY_OF_MONTH, -daysFromMonday)
    }
    if (ms >= startOfWeek.timeInMillis) {
        return WEEKDAY_NAMES[target.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    }

    val year = target.get(java.util.Calendar.YEAR)
    val month = target.get(java.util.Calendar.MONTH) + 1
    val day = target.get(java.util.Calendar.DAY_OF_MONTH)
    return if (year == now.get(java.util.Calendar.YEAR)) {
        "${month}月${day}日"
    } else {
        "${year}年${month}月${day}日"
    }
}
