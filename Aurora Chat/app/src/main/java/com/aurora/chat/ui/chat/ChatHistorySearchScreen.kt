package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aurora.chat.data.api.MessageInfo
import com.aurora.chat.data.local.VideoThumbCache
import com.aurora.chat.data.repository.LocalMessageStore
import com.aurora.chat.ui.chat.media.MediaFailureCache
import com.aurora.chat.ui.chat.media.chatMediaThumbUrl
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.util.ApkIconExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 搜索页状态提升：关闭再打开时保留关键字与已加载结果，不需要重新搜索。
 * 由 [ChatConversationScreen] 按会话 remember，避免每次打开都从头加载。
 *
 * 分类导航（在哪一层、选了哪个月/哪一天/哪个成员）也放在这里：
 * 跳到会话看某条消息再返回时，仍然停在原来那一页，不用重新点入口。
 */
@androidx.compose.runtime.Stable
class ChatSearchState {
    var keyword by mutableStateOf("")
    var allMessages by mutableStateOf<List<MessageInfo>>(emptyList())
    var loading by mutableStateOf(true)
    var loadedConversationId by mutableStateOf(0L)

    /**
     * 从「图片/视频」九宫格直接开全屏预览时，这些老消息往往不在会话当前加载的消息窗口里。
     * 存一份「完整 URL → 消息引用」，让预览里长按呼出的「收藏 / 定位聊天」仍能找到原消息。
     */
    var previewRefs by mutableStateOf<Map<String, IMessageRef>>(emptyMap())

    internal var category by mutableStateOf<SearchCategory?>(null)
    /** 日历当前滚到哪个月（yyyyMM），只是滚动位置锚点，不算一层导航。 */
    internal var pickedMonth by mutableStateOf<Int?>(null)
    /** 日历上被点亮的日期（yyyy-MM-dd）：只做选中态，不跳转。 */
    internal var highlightDay by mutableStateOf<String?>(null)
    internal var pickedDay by mutableStateOf<String?>(null)  // yyyy-MM-dd
    internal var pickedMemberId by mutableStateOf<Long?>(null)

    internal fun resetNav() {
        category = null
        pickedMonth = null
        highlightDay = null
        pickedDay = null
        pickedMemberId = null
    }

    /** 退回一层；已经在首页返回 false，由调用方关闭搜索页。 */
    internal fun backOne(): Boolean = when {
        pickedDay != null -> { pickedDay = null; true }
        pickedMemberId != null -> { pickedMemberId = null; true }
        category != null -> { category = null; highlightDay = null; true }
        else -> false
    }
}

/** 未输入时的分类入口。表情不做（纯内置表情，翻出来也找不到东西）。 */
internal enum class SearchCategory(val label: String, val icon: ImageVector) {
    DATE("日期", Icons.Outlined.DateRange),
    MEMBER("成员", Icons.Outlined.Person),
    MEDIA("图片/视频", Icons.Outlined.Image),
    FILE("文件", Icons.Outlined.InsertDriveFile),
    VOICE("语音", Icons.Outlined.Mic),
    LINK("链接", Icons.Outlined.Link)
}

/**
 * 全屏「查找聊天记录」：
 * - 顶部：返回 + 输入框；
 * - 未输入时展示分类入口（日期 / 成员 / 图片视频 / 文件 / 语音 / 链接）；
 * - 输入后实时展示命中的聊天记录；
 * - 分类内逐级下钻（月份 → 日期 → 消息），返回键一级一级退回。
 *
 * 数据来源：本地消息文件（含解密），一次加载后本地筛选，不联网。
 */
@Composable
fun ChatHistorySearchScreen(
    conversationId: Long,
    currentUserId: Long,
    conversationTitle: String,
    state: ChatSearchState,
    onBack: () -> Unit,
    /** 点击某条结果：回到会话并定位到该消息 */
    onOpenMessage: (Long) -> Unit = {},
    /** 点九宫格里的图片：直接开全屏预览，带缩略图位置做放大/缩回动画，不跳回会话定位 */
    onOpenMedia: (urls: List<String>, index: Int, sourceRect: Rect?) -> Unit = { _, _, _ -> },
    /** 全屏图片预览已打开：本页让出返回键，返回/侧滑最先由图片预览接住 */
    imagePreviewOpen: Boolean = false
) {
    val ctx = LocalContext.current

    // 返回键：先退一层分类，最后才关闭搜索页。
    // 图片预览打开时禁用：否则「后注册的优先」下搜索页会抢走返回，
    // 表现为侧滑一下先退到群聊信息、图片还挂在上面，得连退好几层才关得掉。
    BackHandler(enabled = !imagePreviewOpen) {
        if (!state.backOne()) onBack()
    }

    LaunchedEffect(conversationId, currentUserId) {
        // 换会话：分类位置归零，重新加载
        if (state.loadedConversationId != conversationId) {
            state.loadedConversationId = 0L
            state.resetNav()
        }
        // 同一会话再次打开：直接复用上次结果，不重新加载、不丢关键字
        if (state.loadedConversationId == conversationId) return@LaunchedEffect
        state.loading = true
        // 只走本地消息文件（含解密），不请求网络
        val merged = withContext(Dispatchers.IO) {
            runCatching { LocalMessageStore.loadMessages(ctx, conversationId) }
                .getOrDefault(emptyList())
                .filter { it.id != 0L }
                .distinctBy { it.id }
                .sortedByDescending { it.createdAt }
        }
        state.allMessages = merged
        state.loadedConversationId = conversationId
        state.loading = false
    }

    // 只保留真实聊天内容（剔除系统/协议卡片），供搜索与所有分类共用
    val visible = remember(state.allMessages) {
        state.allMessages.filter { isSearchableMessage(it) }
    }

    val query = state.keyword.trim()
    val results = remember(query, visible) {
        if (query.isEmpty()) emptyList()
        else visible.filter { it.content.contains(query, ignoreCase = true) }
    }

    val isGroup = isGroupChat(conversationId)

    val dayList = remember(visible) { groupByDay(visible) }
    val memberList = remember(visible, currentUserId) { groupByMember(visible, currentUserId) }
    // 已被清理/过期的媒体不进这个分类：放进去也只是一块灰色占位，点了也白点
    val mediaList = remember(visible) {
        visible.filter {
            it.isPictureOrVideo() && !MediaFailureCache.isExpired(resolveMediaUrl(it.mediaUrl))
        }
    }
    // 九宫格里只有图片走「直接放大查看」；视频仍按原逻辑回到会话定位。
    // 图片顺序与 URL 列表严格一一对应，预览左右翻页就按这个顺序走。
    val imageMediaList = remember(mediaList) { mediaList.filterNot { it.isVideo() } }
    val imageUrls = remember(imageMediaList) { imageMediaList.map { resolveMediaUrl(it.mediaUrl) } }
    val fileList = remember(visible) { visible.filter { it.isRealFile() } }
    val voiceList = remember(visible) {
        visible.filter { it.isAudioMessage() && it.mediaUrl.isNotBlank() }
    }
    val linkList = remember(visible) { visible.mapNotNull { msg -> msg.firstLink()?.let { it to msg } } }

    val categories = remember(isGroup) {
        SearchCategory.entries.filter { it != SearchCategory.MEMBER || isGroup }
    }

    // 全屏看图会临时隐藏系统栏：statusBarsPadding() 会瞬间塌成 0，退出时再涨回来，
    // 顶栏就跟着"抽搐"一下（打开/关闭预览都能看到）。这里把状态栏高度冻结成稳定值：
    // 短暂为 0 不采信；涨回来后等它稳定 150ms 再采纳（数值与原来相同，画面不位移）。
    val density = LocalDensity.current
    val liveStatusTopPx = WindowInsets.statusBars.getTop(density)
    var stableStatusTopPx by remember { mutableIntStateOf(liveStatusTopPx) }
    LaunchedEffect(liveStatusTopPx) {
        if (liveStatusTopPx <= 0) return@LaunchedEffect
        if (stableStatusTopPx <= 0) {
            stableStatusTopPx = liveStatusTopPx
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(150L)
        stableStatusTopPx = liveStatusTopPx
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .padding(top = with(density) { stableStatusTopPx.toDp() })
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶部：返回 + 输入框 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { if (!state.backOne()) onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = state.keyword,
                        onValueChange = { state.keyword = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            color = com.aurora.chat.ui.theme.appTextPrimary()
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(Color(0xFF1E40AF)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { }),
                        decorationBox = { inner ->
                            Box {
                                if (state.keyword.isEmpty()) {
                                    Text("搜索聊天记录", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                                inner()
                            }
                        }
                    )
                }
                if (state.keyword.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .clickable { state.keyword = "" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))

            when {
                state.loading -> CenterHint("正在加载…")

                // 有输入：搜索优先，分类让位
                query.isNotEmpty() -> {
                    if (results.isEmpty()) CenterHint("没有找到相关内容")
                    else LazyColumn(Modifier.fillMaxSize().imePadding()) {
                        items(results, key = { it.id }) { msg ->
                            SearchResultRow(
                                msg = msg,
                                keyword = query,
                                currentUserId = currentUserId,
                                conversationTitle = conversationTitle,
                                onClick = { onOpenMessage(msg.id) }
                            )
                            RowDivider()
                        }
                    }
                }

                // 未输入 + 未选分类：分类入口
                state.category == null -> CategoryHome(
                    categories = categories,
                    counts = mapOf(
                        SearchCategory.DATE to dayList.size,
                        SearchCategory.MEMBER to memberList.size,
                        SearchCategory.MEDIA to mediaList.size,
                        SearchCategory.FILE to fileList.size,
                        SearchCategory.VOICE to voiceList.size,
                        SearchCategory.LINK to linkList.size
                    ),
                    onPick = { state.category = it }
                )

                else -> when (state.category) {
                    SearchCategory.DATE -> DateBrowser(
                        state = state,
                        dayList = dayList,
                        currentUserId = currentUserId,
                        conversationTitle = conversationTitle,
                        onOpenMessage = onOpenMessage
                    )

                    SearchCategory.MEMBER -> {
                        val uid = state.pickedMemberId
                        if (uid == null) {
                            val dupNames = remember(memberList) {
                                memberList.groupBy { it.second.name }
                                    .filter { it.value.size > 1 }
                                    .keys
                            }
                            CategoryHeader("成员", "${memberList.size} 人")
                            if (memberList.isEmpty()) CenterHint("还没有人发过消息")
                            else LazyColumn(Modifier.fillMaxSize()) {
                                items(memberList, key = { it.first }) { (id, info) ->
                                    MemberRow(
                                        userId = id,
                                        info = info,
                                        showUid = info.name in dupNames,
                                        onClick = { state.pickedMemberId = id }
                                    )
                                    RowDivider()
                                }
                            }
                        } else {
                            val info = memberList.firstOrNull { it.first == uid }
                            val list = visible.filter { it.fromUserId == uid }
                            CategoryHeader(info?.second?.name ?: conversationTitle, "${list.size} 条")
                            MessageList(
                                list = list,
                                currentUserId = currentUserId,
                                conversationTitle = conversationTitle,
                                onOpenMessage = onOpenMessage
                            )
                        }
                    }

                    SearchCategory.MEDIA -> {
                        CategoryHeader("图片/视频", "${mediaList.size} 个")
                        if (mediaList.isEmpty()) CenterHint("没有图片或视频")
                        else LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            gridItems(mediaList, key = { it.id }) { msg ->
                                MediaTile(
                                    msg = msg,
                                    onClick = { rect ->
                                        if (msg.isVideo()) {
                                            onOpenMessage(msg.id)
                                        } else {
                                            val url = resolveMediaUrl(msg.mediaUrl)
                                            // 老消息往往不在会话当前加载的窗口里：把这一屏图片的引用
                                            // 交给预览层，长按抽屉里的「收藏 / 定位聊天」才不会失效。
                                            state.previewRefs = imageMediaList.associate { m ->
                                                resolveMediaUrl(m.mediaUrl) to m.toPreviewRef(currentUserId)
                                            }
                                            onOpenMedia(
                                                imageUrls,
                                                imageUrls.indexOf(url).coerceAtLeast(0),
                                                rect
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }

                    SearchCategory.FILE -> {
                        CategoryHeader("文件", "${fileList.size} 个")
                        if (fileList.isEmpty()) CenterHint("没有文件")
                        else LazyColumn(Modifier.fillMaxSize()) {
                            items(fileList, key = { it.id }) { msg ->
                                FileRow(
                                    msg = msg,
                                    currentUserId = currentUserId,
                                    conversationTitle = conversationTitle,
                                    onClick = { onOpenMessage(msg.id) }
                                )
                                RowDivider()
                            }
                        }
                    }

                    SearchCategory.VOICE -> {
                        CategoryHeader("语音", "${voiceList.size} 条")
                        if (voiceList.isEmpty()) CenterHint("没有语音消息")
                        else LazyColumn(Modifier.fillMaxSize()) {
                            items(voiceList, key = { it.id }) { msg ->
                                VoiceRow(
                                    msg = msg,
                                    currentUserId = currentUserId,
                                    conversationTitle = conversationTitle,
                                    onClick = { onOpenMessage(msg.id) }
                                )
                                RowDivider()
                            }
                        }
                    }

                    SearchCategory.LINK -> {
                        CategoryHeader("链接", "${linkList.size} 条")
                        if (linkList.isEmpty()) CenterHint("没有链接")
                        else LazyColumn(Modifier.fillMaxSize()) {
                            items(linkList, key = { it.second.id }) { (url, msg) ->
                                LinkRow(
                                    url = url,
                                    msg = msg,
                                    currentUserId = currentUserId,
                                    conversationTitle = conversationTitle,
                                    onClick = { onOpenMessage(msg.id) }
                                )
                                RowDivider()
                            }
                        }
                    }

                    null -> Unit
                }
            }
        }
    }
}

// ==================== 日期：微信式连续日历 ====================

/**
 * 按日期查找：整页连续日历（周日开头），时间从早到晚——越早的月份在上面，
 * 打开时直接落在最新一个月。
 *
 * 仿微信：
 * - 顶部「yyyy年M月 ⌄」可下拉直接跳到某个月，聊好几年也不用一屏屏划；
 * - 有记录的日期是深色、可点；没记录的日期浅灰、点了没反应；
 * - 点日期只点亮选中，再点一次（或点底部按钮）才进当天的消息列表；
 * - 今天用品牌蓝标出，下面写「今天」；
 * - 返回仍停在这个月的日历位置。
 */
@Composable
private fun DateBrowser(
    state: ChatSearchState,
    dayList: List<Pair<String, List<MessageInfo>>>,
    currentUserId: Long,
    conversationTitle: String,
    onOpenMessage: (Long) -> Unit
) {
    val pickedDay = state.pickedDay

    // 第 2 层：某天的消息
    if (pickedDay != null) {
        val list = dayList.firstOrNull { it.first == pickedDay }?.second.orEmpty()
        CategoryHeader(dayTitle(pickedDay), "${list.size} 条")
        MessageList(
            list = list,
            currentUserId = currentUserId,
            conversationTitle = conversationTitle,
            onOpenMessage = onOpenMessage
        )
        return
    }

    if (dayList.isEmpty()) {
        CenterHint("没有聊天记录")
        return
    }

    val countByDay = remember(dayList) { dayList.associate { it.first to it.second.size } }
    val months = remember(dayList) { buildCalendarMonths(dayList) }
    val indexOfMonth = remember(months) { months.withIndex().associate { it.value to it.index } }

    // 返回进来时还原到上次看的月份；第一次进入直接落在最新一个月
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (
            state.pickedMonth?.let { indexOfMonth[it] } ?: months.lastIndex
            ).coerceIn(0, (months.size - 1).coerceAtLeast(0))
    )
    LaunchedEffect(listState, months) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            months.getOrNull(idx)?.let { if (state.pickedMonth != it) state.pickedMonth = it }
        }
    }
    val visibleMonth by remember {
        derivedStateOf { months.getOrNull(listState.firstVisibleItemIndex) }
    }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        MonthPickerBar(
            current = visibleMonth,
            months = months,
            onPick = { key ->
                indexOfMonth[key]?.let { idx ->
                    state.pickedMonth = key
                    scope.launch { listState.animateScrollToItem(idx) }
                }
            }
        )
        WeekHeader()
        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // 给底部浮动按钮留位置，选中日期时列表不被顶动
                contentPadding = PaddingValues(bottom = 76.dp)
            ) {
                items(months.size, key = { months[it] }) { i ->
                    MonthCalendar(
                        year = months[i] / 100,
                        month = months[i] % 100,
                        countByDay = countByDay,
                        selectedDay = state.highlightDay,
                        showDivider = i > 0,
                        onPickDay = { key ->
                            // 第一次点只点亮；点已点亮的那个才进当天消息
                            if (state.highlightDay == key) state.pickedDay = key
                            else state.highlightDay = key
                        }
                    )
                }
            }
            // 选中某天后浮出确认条，避免"点一下就被弹进消息列表"
            state.highlightDay?.let { key ->
                Box(Modifier.align(Alignment.BottomCenter)) {
                    DayActionBar(
                        dayKey = key,
                        count = countByDay[key] ?: 0,
                        onOpen = { state.pickedDay = key }
                    )
                }
            }
        }
    }
}

/** 底部「查看当天记录」按钮：点亮日期后才出现。 */
@Composable
private fun DayActionBar(dayKey: String, count: Int, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(com.aurora.chat.ui.theme.appSurface())
    ) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E40AF))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onOpen() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "查看 ${calendarDayLabel(dayKey)}的聊天记录",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Spacer(Modifier.width(6.dp))
            Text("$count 条", fontSize = 12.sp, color = Color(0xCCFFFFFF))
        }
    }
}

/** 顶部月份条：显示当前滚到的月份，点开下拉直接跳月。 */
@Composable
private fun MonthPickerBar(current: Int?, months: List<Int>, onPick: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                current?.let { "${it / 100}年${it % 100}月" } ?: "选择月份",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 380.dp)
        ) {
            months.forEach { key ->
                val selected = key == current
                DropdownMenuItem(
                    text = {
                        Text(
                            "${key / 100}年${key % 100}月",
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextPrimary()
                        )
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color(0xFF1E40AF),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onPick(key)
                    }
                )
            }
        }
    }
}

/** 周日开头的星期表头，跟随日历一起固定在上方。 */
@Composable
private fun WeekHeader() {
    Column {
        Row(
            Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg()),
            horizontalArrangement = Arrangement.Center
        ) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { w ->
                Text(
                    w,
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextHint(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(DayCellWidth).padding(vertical = 8.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
    }
}

/** 日历格子尺寸：整行居中排，不再按屏宽均分，避免数字之间拉得太开。 */
private val DayCellWidth = 40.dp
private val DayCellHeight = 48.dp

/** 一个月的日历块：月份标题 + 按周排的日期格子。 */
@Composable
private fun MonthCalendar(
    year: Int,
    month: Int,
    countByDay: Map<String, Int>,
    selectedDay: String?,
    showDivider: Boolean,
    onPickDay: (String) -> Unit
) {
    val firstDayOfWeek = remember(year, month) {
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }.get(Calendar.DAY_OF_WEEK) - 1 // 0 = 周日
    }
    val daysInMonth = remember(year, month) {
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
    val today = remember { Calendar.getInstance() }
    val todayYear = today.get(Calendar.YEAR)
    val todayMonth = today.get(Calendar.MONTH) + 1
    val todayDay = today.get(Calendar.DAY_OF_MONTH)
    val rows = (firstDayOfWeek + daysInMonth + 6) / 7

    Column(Modifier.fillMaxWidth()) {
        // 月份之间的分隔线：11 月 / 10 月 各成一段
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(0.5.dp)
                    .background(com.aurora.chat.ui.theme.appCardBg())
            )
        }
        Text(
            "${year}年${month}月",
            fontSize = 13.sp,
            color = com.aurora.chat.ui.theme.appTextHint(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)
        )
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (c in 0..6) {
                    val dayNum = r * 7 + c - firstDayOfWeek + 1
                    Box(
                        Modifier.width(DayCellWidth).height(DayCellHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        if (dayNum in 1..daysInMonth) {
                            val key = String.format(
                                Locale.US, "%04d-%02d-%02d", year, month, dayNum
                            )
                            DayCell(
                                day = dayNum,
                                enabled = countByDay.containsKey(key),
                                isToday = year == todayYear &&
                                    month == todayMonth && dayNum == todayDay,
                                isSelected = key == selectedDay,
                                onClick = { onPickDay(key) }
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 单个日期格子：选中品牌蓝实心；今天只做数字高亮＋下方「今天」，不套圈；没记录的浅灰。 */
@Composable
private fun DayCell(
    day: Int,
    enabled: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (enabled) {
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onClick() }
                } else Modifier
            )
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (isSelected) Color(0xFF1E40AF) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$day",
                fontSize = 15.sp,
                fontWeight = if (isSelected || isToday) FontWeight.Medium else FontWeight.Normal,
                color = when {
                    isSelected -> Color.White
                    isToday -> Color(0xFF1E40AF)
                    !enabled -> Color(0xFFC7CBD1)
                    else -> com.aurora.chat.ui.theme.appTextPrimary()
                }
            )
        }
        // 固定一块 15dp 的文字带：不再用 12dp 去挤 9sp，避免"今天"被切掉半截
        Box(Modifier.height(15.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (isToday) {
                Text(
                    "今天",
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = Color(0xFF1E40AF)
                )
            }
        }
    }
}

// ==================== 首页分类入口 ====================

@Composable
private fun CategoryHome(
    categories: List<SearchCategory>,
    counts: Map<SearchCategory, Int>,
    onPick: (SearchCategory) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        categories.chunked(2).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { cat ->
                    CategoryCard(
                        category = cat,
                        count = counts[cat] ?: 0,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(cat) }
                    )
                }
                // 奇数个时补一个占位，保证卡片不拉满整行
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CategoryCard(
    category: SearchCategory,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .height(64.dp)
            .clip(shape)
            .background(com.aurora.chat.ui.theme.appCardBg())
            .border(0.5.dp, com.aurora.chat.ui.theme.appCardBg(), shape)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFE8EEFB)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = Color(0xFF1E40AF),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                category.label,
                fontSize = 14.sp,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (count <= 0) "暂无" else "$count 项",
                fontSize = 11.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CategoryHeader(title: String, countText: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = com.aurora.chat.ui.theme.appTextSecondary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(countText, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
}

// ==================== 各分类的行 ====================

@Composable
private fun SimpleRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 14.sp,
            color = com.aurora.chat.ui.theme.appTextPrimary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(subtitle, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
        Spacer(Modifier.width(6.dp))
        Text("›", fontSize = 16.sp, color = Color(0xFFC7CBD1))
    }
}

@Composable
private fun MemberRow(
    userId: Long,
    info: MemberInfo,
    showUid: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(userId = userId, userName = info.name, size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    info.name,
                    fontSize = 14.sp,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 同名成员靠 ID 区分，避免两个"小明"分不清
                if (showUid) {
                    Spacer(Modifier.width(6.dp))
                    Text("ID $userId", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            }
            Spacer(Modifier.height(2.dp))
            Text("共 ${info.count} 条", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
        Text("›", fontSize = 16.sp, color = Color(0xFFC7CBD1))
    }
}

@Composable
private fun MessageList(
    list: List<MessageInfo>,
    currentUserId: Long,
    conversationTitle: String,
    onOpenMessage: (Long) -> Unit
) {
    if (list.isEmpty()) {
        CenterHint("没有找到相关内容")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { msg ->
            SearchResultRow(
                msg = msg,
                keyword = "",
                currentUserId = currentUserId,
                conversationTitle = conversationTitle,
                onClick = { onOpenMessage(msg.id) }
            )
            RowDivider()
        }
    }
}

/** 图片/视频九宫格：图片走服务端压缩预览（长图自动只取顶部），视频只显示播放标、不联网取帧。 */
@Composable
private fun MediaTile(msg: MessageInfo, onClick: (Rect?) -> Unit) {
    val isVideo = msg.isVideo()
    val ctx = LocalContext.current
    val resolved = remember(msg.mediaUrl) { resolveMediaUrl(msg.mediaUrl) }
    // 点击瞬间冻结缩略图在屏幕上的位置，全屏预览据此做「从这张图放大 / 缩回这张图」的 hero 动画。
    // 必须在点击时冻结：预览打开会隐藏系统栏，下面这层搜索页会跟着 inset 重排，实时读会跳位。
    var tileRect by remember { mutableStateOf<Rect?>(null) }

    // 视频封面：先读会话里已经落盘的缓存（同一张图），没有才联网取帧并写回同一份缓存
    var cover by remember(resolved, isVideo) {
        mutableStateOf(
            if (isVideo) {
                runCatching { VideoThumbCache.load(ctx, resolved, maxDim = 480) }.getOrNull()
            } else null
        )
    }
    LaunchedEffect(resolved, isVideo) {
        if (!isVideo || cover != null) return@LaunchedEffect
        cover = withContext(Dispatchers.IO) { loadVideoCoverCached(ctx, resolved, maxDim = 480) }
    }

    Box(
        Modifier
            .aspectRatio(1f)
            .onGloballyPositioned { tileRect = it.boundsInWindow() }
            .clip(RoundedCornerShape(6.dp))
            .background(com.aurora.chat.ui.theme.appCardBg())
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick(tileRect) }
    ) {
        when {
            isVideo && cover != null -> Image(
                bitmap = remember(cover) { cover!!.asImageBitmap() },
                contentDescription = "视频",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            isVideo -> Box(
                Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appDivider()),
                contentAlignment = Alignment.Center
            ) {
                Text("▶", fontSize = 20.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
            }
            else -> {
                val thumb = remember(resolved) { chatMediaThumbUrl(resolved) }
                AsyncImage(
                    model = thumb,
                    contentDescription = "图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // 视频角标：有封面时也留一个播放标记，一眼能区分静图
        if (isVideo) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▶", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

/**
 * 把本地库里的历史消息转成一个轻量 [IMessageRef]。
 * 这些消息可能不在会话当前加载的窗口里，但全屏预览长按后的「收藏 / 定位聊天」
 * 需要拿到真实 id、发送者、媒体地址等字段，光有 URL 不够。
 */
private fun MessageInfo.toPreviewRef(currentUserId: Long): IMessageRef = ChatMsg(
    id = id,
    serverId = id,
    text = content,
    isMine = fromUserId == currentUserId,
    fromUserId = fromUserId,
    senderName = fromUserName,
    time = if (localTimeMs > 0L) localTimeMs else createdAt * 1000L,
    createdAt = createdAt,
    isRevoked = isRevoked,
    isSystemNotice = isSystemNotice,
    replyToText = replyToText,
    replyToSender = replyToSender,
    replyToId = replyToId,
    mediaType = mediaType,
    mediaUrl = mediaUrl,
    mediaWidth = mediaWidth,
    mediaHeight = mediaHeight,
    toUserId = toUserId,
    targetName = targetName,
    flashDuration = flashDuration,
    broadcastTaskId = broadcastTaskId,
    contactSource = contactSource,
    clientSendAt = clientSendAt
)

@Composable
private fun FileRow(
    msg: MessageInfo,
    currentUserId: Long,
    conversationTitle: String,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    val fileName = remember(msg.mediaUrl) { msg.fileName() }
    val ext = remember(fileName) { fileName.substringAfterLast('.', "").lowercase() }
    val color = remember(ext) { extColor(ext) }
    val url = remember(msg.mediaUrl) { resolveMediaUrl(msg.mediaUrl) }

    // 大小：HEAD 一次后进内存缓存，滚动回来不再请求
    var sizeText by remember(url) { mutableStateOf(fileSizeCache[url] ?: "") }
    LaunchedEffect(url) {
        if (sizeText.isEmpty()) {
            val label = withContext(Dispatchers.IO) { fetchFileSizeText(url) }
            if (label.isNotEmpty()) fileSizeCache[url] = label
            sizeText = label
        }
    }

    // APK 用其自身图标；解析不到才回退到扩展名色块
    var apkIcon by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url, ext) {
        if (ext == "apk" && apkIcon == null) {
            ApkIconExtractor.ensureIcon(ctx, url, null) { bmp -> apkIcon = bmp }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = apkIcon
        if (icon != null) {
            // 有真实图标就直接铺满，不再套浅色底板——APK 图标本身已经自带形状和配色
            Image(
                bitmap = remember(icon) { icon.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(9.dp))
            )
        } else {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (ext.isEmpty()) "FILE" else ext.uppercase().take(4),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fileName,
                fontSize = 14.sp,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sizeText.isNotEmpty()) {
                    Text(sizeText, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    senderName(msg, currentUserId, conversationTitle),
                    fontSize = 11.sp,
                    color = com.aurora.chat.ui.theme.appTextHint(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(formatSearchTime(msg.createdAt), fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
        }
    }
}

@Composable
private fun VoiceRow(
    msg: MessageInfo,
    currentUserId: Long,
    conversationTitle: String,
    onClick: () -> Unit
) {
    val seconds = msg.content.toIntOrNull() ?: 0
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFFE8EEFB)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = null,
                tint = Color(0xFF1E40AF),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (seconds > 0) "语音 ${seconds}″" else "语音消息",
                fontSize = 14.sp,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${senderName(msg, currentUserId, conversationTitle)}  ${formatSearchTime(msg.createdAt)}",
                fontSize = 11.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LinkRow(
    url: String,
    msg: MessageInfo,
    currentUserId: Long,
    conversationTitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFFE8EEFB)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = Color(0xFF1E40AF),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                url,
                fontSize = 14.sp,
                color = Color(0xFF1E40AF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${senderName(msg, currentUserId, conversationTitle)}  ${formatSearchTime(msg.createdAt)}",
                fontSize = 11.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    msg: MessageInfo,
    keyword: String,
    currentUserId: Long,
    conversationTitle: String,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = senderName(msg, currentUserId, conversationTitle),
                fontSize = 12.sp,
                color = com.aurora.chat.ui.theme.appTextSecondary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatSearchTime(msg.createdAt),
                fontSize = 11.sp,
                color = com.aurora.chat.ui.theme.appTextHint()
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            // keyword 为空时 highlightKeyword 原样返回，天然兼容分类列表
            text = highlightKeyword(displaySnippet(msg), keyword),
            fontSize = 14.sp,
            color = com.aurora.chat.ui.theme.appTextPrimary(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==================== 数据加工 ====================

private fun senderName(msg: MessageInfo, currentUserId: Long, conversationTitle: String): String =
    if (msg.fromUserId == currentUserId) "我" else msg.fromUserName.ifBlank { conversationTitle }

/**
 * 只保留真实聊天内容：剔除撤回、系统通知、群公告、拍一拍、广播/服务端系统消息、
 * 相机闪照（阅后即焚）、红包/转账/位置、分享卡片、清空记录请求、隐私请求/回应、群邀请等协议卡片。
 */
private fun isSearchableMessage(msg: MessageInfo): Boolean =
    msg.isRevoked == 0 &&
        !msg.isSystemNotice &&
        !isGroupAnnouncementMessage(msg.content) &&
        !isRedPacketMessage(msg.content) &&
        !isTransferMessage(msg.content) &&
        !isLocationCardMessage(msg.content) &&
        !isShareCardMessage(msg.content) &&
        !isClearChatRequestCard(msg.content) &&
        !isClearChatRejectCard(msg.content) &&
        !isPrivacyRequestCard(msg.content) &&
        !isPrivacyResponseCard(msg.content) &&
        !isInviteGroupCard(msg.content) &&
        !isSystemNoticeMessage(
            isRevoked = msg.isRevoked != 0,
            msgType = msg.msgType,
            broadcastTaskId = msg.broadcastTaskId,
            flashDuration = msg.flashDuration,
            content = msg.content
        ) &&
        msg.flashDuration <= 0

/** 表情（内置表情 / emoji）既不是图片也不是文件，一律不进这些分类。 */
private fun MessageInfo.isStickerOrEmoji(): Boolean =
    mediaUrl.startsWith("sticker://") || isEmojiMediaUrl(mediaUrl)

private val IMAGE_TYPES = setOf("image", "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_TYPES = setOf("video", "mp4", "mov", "avi", "mkv", "webm", "3gp", "3gpp", "mpg", "mpeg")
/** 语音/音频：绝不能进「图片视频」。录音常见 3gp / m4a / amr，3gp 还和视频扩展名撞车。 */
private val AUDIO_TYPES = setOf(
    "voice", "audio", "amr", "m4a", "mp3", "aac", "wav", "ogg", "oga", "opus",
    "3ga", "silk", "slk", "m4r", "flac"
)

private fun MessageInfo.isVideo(): Boolean =
    mediaType.lowercase() in VIDEO_TYPES || isVideoMediaUrl(mediaUrl)

/** 语音：media_type 是 voice/audio，或本身就是音频扩展名。 */
private fun MessageInfo.isAudioMessage(): Boolean {
    if (mediaType.lowercase() in AUDIO_TYPES) return true
    val ext = mediaUrl.substringAfterLast('.').substringBefore('?').lowercase()
    return ext in AUDIO_TYPES
}

private fun MessageInfo.isPicture(): Boolean =
    !isStickerOrEmoji() && (mediaType.lowercase() in IMAGE_TYPES || isImageMediaUrl(mediaUrl))

/**
 * 图片 / 视频：后端 media_type 存的是扩展名（jpg / png / mp4…），必须按扩展名集合判断。
 * 判定前先排除音频——录音常存成 .3gp / .m4a，其中 3gp 会被误当成视频混进这个分类。
 */
private fun MessageInfo.isPictureOrVideo(): Boolean =
    mediaUrl.isNotBlank() && !isAudioMessage() && (isPicture() || isVideo())

/** 真正的文件：media_type=file，且不是表情、也不是被误标成 file 的图片/视频。 */
private fun MessageInfo.isRealFile(): Boolean =
    mediaUrl.isNotBlank() &&
        mediaType == "file" &&
        !isStickerOrEmoji() &&
        !isImageMediaUrl(mediaUrl) &&
        !isVideoMediaUrl(mediaUrl)

private fun MessageInfo.fileName(): String =
    mediaUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "文件" }

/** 列表里给非文本消息一个可读摘要，避免出现一整片空白行。 */
private fun displaySnippet(msg: MessageInfo): String {
    return when {
        msg.isStickerOrEmoji() -> "[表情]"
        msg.isPictureOrVideo() -> if (msg.isVideo()) "[视频]" else "[图片]"
        msg.mediaType == "voice" -> "[语音]"
        msg.mediaType == "file" -> "[文件]"
        msg.content.isBlank() -> "[消息]"
        else -> msg.content
    }
}

private data class MemberInfo(val name: String, val count: Int)

/**
 * 日历要渲染的月份（yyyyMM，升序）：聊天出现过的月份 + 当前月。
 * 升序＝越早的月份越靠上，往下翻时间越来越新，和聊天记录阅读方向一致。
 * 中间完全没消息的月份直接跳过，避免十年老聊天被空白月拖成几十屏。
 */
private fun buildCalendarMonths(dayList: List<Pair<String, List<MessageInfo>>>): List<Int> {
    val keys = LinkedHashSet<Int>()
    dayList.forEach { (dayKey, _) ->
        val y = dayKey.substring(0, 4).toIntOrNull() ?: return@forEach
        val m = dayKey.substring(5, 7).toIntOrNull() ?: return@forEach
        keys.add(y * 100 + m)
    }
    val now = Calendar.getInstance()
    keys.add(now.get(Calendar.YEAR) * 100 + (now.get(Calendar.MONTH) + 1))
    return keys.sorted()
}

/** 按天分组，返回「yyyy-MM-dd → 当天消息（时间倒序）」，按日期倒序。 */
private fun groupByDay(list: List<MessageInfo>): List<Pair<String, List<MessageInfo>>> {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val map = LinkedHashMap<String, MutableList<MessageInfo>>()
    // list 本身已按时间倒序，这里保持顺序即可让组内也是倒序
    list.forEach { msg ->
        val key = runCatching { fmt.format(Date(msg.createdAt * 1000)) }.getOrDefault("")
        if (key.isEmpty()) return@forEach
        map.getOrPut(key) { mutableListOf() }.add(msg)
    }
    return map.entries.sortedByDescending { it.key }.map { it.key to it.value.toList() }
}

/** 按发送人分组（不含自己），按消息数倒序；名字取消息里带的最新昵称。 */
private fun groupByMember(
    list: List<MessageInfo>,
    currentUserId: Long
): List<Pair<Long, MemberInfo>> {
    val map = LinkedHashMap<Long, MutableList<MessageInfo>>()
    list.filter { it.fromUserId != currentUserId && it.fromUserId > 0 }
        .forEach { map.getOrPut(it.fromUserId) { mutableListOf() }.add(it) }
    return map.entries
        .map { (uid, msgs) ->
            val name = msgs.firstOrNull { it.fromUserName.isNotBlank() }?.fromUserName
                ?: "用户 $uid"
            uid to MemberInfo(name, msgs.size)
        }
        .sortedWith(compareByDescending<Pair<Long, MemberInfo>> { it.second.count }
            .thenBy { it.second.name })
}

private val linkRegex = Regex("""https?://[^\s<>"']+""")

/** 取消息里第一条链接（去掉尾部标点），没有则 null。 */
private fun MessageInfo.firstLink(): String? {
    if (mediaType.isNotEmpty() && mediaType != "file") return null
    val m = linkRegex.find(content) ?: return null
    return m.value.trimEnd('.', ',', '。', '，', ')', '）', ']', '】', '>', '"', '!', '！', '?', '？', ';', '；', ':', '：')
        .takeIf { it.length > 8 }
}

private val dayTitleFmt = SimpleDateFormat("M月d日", Locale.getDefault())
private val dayYearTitleFmt = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
private val weekFmt = SimpleDateFormat("EEEE", Locale.getDefault())

/** 日期标题：今天 / 昨天 / 具体日期，都带星期。 */
private fun dayTitle(dayKey: String): String {
    val day = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dayKey) }
        .getOrNull() ?: return dayKey
    val cal = Calendar.getInstance().apply { time = day }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val sameDay = { a: Calendar, b: Calendar ->
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
    val week = runCatching { weekFmt.format(day) }.getOrDefault("")
    return when {
        sameDay(cal, today) -> "今天 $week"
        sameDay(cal, yesterday) -> "昨天 $week"
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            "${dayTitleFmt.format(day)} $week"
        else -> "${dayYearTitleFmt.format(day)} $week"
    }
}

/** 日历底部按钮上的日期文本：yyyy-MM-dd → M月d日 */
private fun calendarDayLabel(dayKey: String): String {
    val parts = dayKey.split("-")
    if (parts.size != 3) return dayKey
    val m = parts[1].toIntOrNull() ?: return dayKey
    val d = parts[2].toIntOrNull() ?: return dayKey
    return "${m}月${d}日"
}

/** 关键字高亮：把命中的片段标成品牌蓝加粗 */
private fun highlightKeyword(text: String, keyword: String) = buildAnnotatedString {
    if (keyword.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    val lowerText = text.lowercase()
    val lowerKey = keyword.lowercase()
    var start = 0
    while (true) {
        val idx = lowerText.indexOf(lowerKey, start)
        if (idx < 0) {
            append(text.substring(start))
            break
        }
        append(text.substring(start, idx))
        withStyle(SpanStyle(color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold)) {
            append(text.substring(idx, idx + lowerKey.length))
        }
        start = idx + lowerKey.length
    }
}

private val searchTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/** createdAt 为秒级时间戳 */
private fun formatSearchTime(createdAt: Long): String =
    if (createdAt <= 0L) "" else runCatching { searchTimeFmt.format(Date(createdAt * 1000)) }.getOrDefault("")
