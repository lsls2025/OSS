@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.aurora.chat.ui.contacts
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ErrorReporter
import com.aurora.chat.R
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.components.avatarCornerRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 供 MainActivity 在删除好友后使缓存失效，从而联系人列表重新拉取最新数据 */
fun invalidateFriendCache(context: android.content.Context, uid: Long) {
    FriendCache.invalidate(uid)
    FriendDiskStore.invalidate(context, uid)
}

/** 好友内存缓存（按账号隔离，5 分钟有效），避免频繁切入"联系人"tab 重复联网导致卡顿 */
private object FriendCache {
    private class Entry(val list: List<UserInfo>, val ts: Long)
    private val map = HashMap<Long, Entry>()
    private const val TTL = 300_000L

    fun get(uid: Long): List<UserInfo>? {
        val e = map[uid] ?: return null
        return if (System.currentTimeMillis() - e.ts < TTL) e.list else null
    }
    fun put(uid: Long, list: List<UserInfo>) { map[uid] = Entry(list, System.currentTimeMillis()) }
    fun invalidate(uid: Long) { map.remove(uid) }
}

/**
 * 拼音分组结果缓存（按账号，进程级）。
 * 把 buildGroup 的结果缓存起来：避免在 Compose 组合阶段同步建组（ICU 给几百个好友排序会卡主线程，
 * 表现为切到「联系人」时底部导航图标放大动画慢半拍）。切走再切回时直接命中缓存，不空白也不重算。
 */
private object ContactGroupCache {
    private val map = HashMap<Long, GroupData>()
    fun get(uid: Long): GroupData? = map[uid]
    fun put(uid: Long, data: GroupData) { map[uid] = data }
}

/**
 * 启动预热：登录/切号后把好友列表与拼音分组提前算好塞进缓存。
 * 这样首次进「联系人」tab 时 initialFriends / groupData 直接命中缓存，
 * loadFriends 走内存命中分支（friends 同引用不触发重建），切 tab 那一帧主线程没有任何
 * 状态写入，底部图标放大动画不会被后台回写 friends/groupData 占用的帧拖慢（慢半拍）。
 */
suspend fun prewarmContactCaches(context: android.content.Context, uid: Long) {
    if (uid <= 0) return
    var fromNetwork = false
    val list = FriendCache.get(uid)
        ?: withContext(Dispatchers.IO) { FriendDiskStore.load(context, uid) }
        ?: run {
            // 真·首次安装没有磁盘缓存：走一次网络，避免第一次点「联系人」才现拉、现白屏
            val r = runCatching { ChatRepository.getFriends() }.getOrNull()
            val data = r?.data
            if (r?.success == true && !data.isNullOrEmpty()) {
                fromNetwork = true
                data
            } else null
        }
        ?: return
    FriendCache.put(uid, list)
    // 冷启动即便只读到磁盘好友（还没联网），也把名字同步进全局用户资料缓存，
    // 这样群成员页/资料卡/消息气泡首帧就能按 userId 查到名字。
    com.aurora.chat.data.local.UserProfileStore.putAllUsers(context, list)
    if (fromNetwork) {
        withContext(Dispatchers.IO) { FriendDiskStore.save(context, uid, list) }
    }
    val gd = withContext(Dispatchers.Default) { buildGroup(list) }
    ContactGroupCache.put(uid, gd)
}

/** 好友磁盘缓存（按账号隔离）：重启后首次进入立即显示上次的好友，连接期间也不会空白/点不了 */
private object FriendDiskStore {
    private const val PREFS = "aurora_friends_cache"
    private const val KEY_PREFIX = "friends_"

    fun save(context: android.content.Context, uid: Long, list: List<UserInfo>) {
        try {
            val arr = org.json.JSONArray()
            list.forEach { u ->
                val o = org.json.JSONObject()
                o.put("id", u.id)
                o.put("email", u.email)
                o.put("username", u.username)
                o.put("qqNumber", u.qqNumber)
                o.put("signature", u.signature)
                arr.put(o)
            }
            // 用 commit 同步落盘：联系人列表是冷启动首帧的数据源，
            // apply 的异步写若进程随后被杀会丢，表现就是“根本没存到磁盘”。
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_PREFIX + uid, arr.toString()).commit()
        } catch (_: Exception) {}
    }

    fun load(context: android.content.Context, uid: Long): List<UserInfo>? = try {
        val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + uid, null) ?: return null
        val arr = org.json.JSONArray(raw)
        val out = ArrayList<UserInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(UserInfo(
                id = o.getLong("id"),
                email = o.optString("email", ""),
                username = o.optString("username", ""),
                qqNumber = o.optString("qqNumber", ""),
                signature = o.optString("signature", ""),
                createdAt = 0
            ))
        }
        out
    } catch (_: Exception) { null }

    fun invalidate(context: android.content.Context, uid: Long) {
        runCatching {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().remove(KEY_PREFIX + uid).apply()
        }
    }
}

/** 用 Android 内置 ICU 转换"汉字→拼音首字母"，零新增依赖，全量准确 */
private val hanLatin: android.icu.text.Transliterator? by lazy {
    try { android.icu.text.Transliterator.getInstance("Han-Latin") } catch (_: Throwable) { null }
}

private fun pinyinInitial(name: String): String {
    if (name.isBlank()) return "#"
    val ch = name.trim().first()
    if (ch in 'A'..'Z') return ch.toString()
    if (ch in 'a'..'z') return ch.uppercaseChar().toString()
    // 其余（汉字 / @ / . / 数字 / 符号等）：汉字走 ICU 拼音，其余一律归 #，绝不排在 A 前面
    val t = hanLatin ?: return "#"
    return try {
        val r = t.transliterate(ch.toString()).trim().firstOrNull()?.uppercaseChar()
        if (r != null && r in 'A'..'Z') r.toString() else "#"
    } catch (_: Throwable) { "#" }
}

/** 分组后的扁平条目：字母标题或好友行 */
private sealed interface ContactItem {
    val key: String
    data class Header(val letter: String, override val key: String) : ContactItem
    data class Row(val user: UserInfo, val name: String, override val key: String) : ContactItem
}

private data class GroupData(
    val items: List<ContactItem>,
    val letters: List<String>,
    val letterIndex: Map<String, Int>
)

/**
 * 联系人显示的标题：用户名 → "用户<ID>"。
 * 不用签名兜底：副标题固定展示签名（缺省显示"该用户还没有写签名"），名字若也取签名，
 * 同一行会出现两遍同样的字；更不能用 email —— QQ/手机注册存的是 qq_10001@local 这种内部占位串。
 */
private fun contactName(u: UserInfo): String =
    u.username.trim().ifBlank { "用户${u.id}" }

private fun buildGroup(friends: List<UserInfo>): GroupData {
    // 排序必须稳定：服务器每次返回好友的顺序不保证一致，
    // 若直接按返回顺序建组，刷新后同一字母内会重排，列表位置就"乱跳"。
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    val nameComparator = Comparator<UserInfo> { a, b ->
        collator.compare(contactName(a), contactName(b))
    }
    val sortedFriends = friends.sortedWith(nameComparator.thenBy { it.id })
    val buckets = LinkedHashMap<String, MutableList<UserInfo>>()
    sortedFriends.forEach { f ->
        val letter = pinyinInitial(contactName(f))
        buckets.getOrPut(letter) { mutableListOf() }.add(f)
    }
    val ordered = buckets.keys.sortedWith(
        compareBy<String> { if (it == "#") 1 else 0 }.thenBy { it }
    )
    val items = ArrayList<ContactItem>(friends.size + ordered.size)
    val letterIndex = HashMap<String, Int>()
    ordered.forEach { letter ->
        letterIndex[letter] = items.size
        items.add(ContactItem.Header(letter, key = "h_$letter"))
        buckets[letter]!!.forEach { u ->
            items.add(ContactItem.Row(u, contactName(u), key = "r_${u.id}"))
        }
    }
    return GroupData(items, ordered, letterIndex)
}

/** 联系人界面：微信通讯录风格（A-Z/# 分组） */
@Composable
fun ContactsScreen(
    currentUserId: Long,
    onOpenChat: (friendId: Long, friendName: String) -> Unit,
    onOpenProfile: (friendId: Long, friendName: String) -> Unit,
    friendRequestUnreadCount: Int = 0,
    onOpenFriendRequests: () -> Unit = {},
    onOpenGroups: () -> Unit = {},
) {
    val context = LocalContext.current
    // 液态模式：列表卡片玻璃化（折射源用纯色画布，避免纯白塑料感）
    // 联系人列表液态开关（关 = 经典联系人列表）
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 字母索引触发的滚动任务（新的一次先取消旧的，避免抢位）
    var indexScrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // 首帧只同步读内存缓存（瞬时）。磁盘读取与网络拉取交给 loadFriends 在后台协程做，
    // 绝不在组合阶段同步读 SharedPreferences + 解析好友 JSON —— 那会卡主线程，
    // 而底栏在布局顺序上位于内容区之后，会连带把「联系人」底部图标的放大动画第一帧拖住（慢半拍）。
    val initialFriends = remember(currentUserId) {
        FriendCache.get(currentUserId) ?: emptyList()
    }
    var friends by remember(currentUserId) { mutableStateOf<List<UserInfo>>(initialFriends) }
    // 是否真的完成过一次加载（内存/磁盘/网络）。没完成前 friends 为空只是“还没加载”，
    // 不能当成“没有联系人”去建一个空组 —— 那会造成 friends 已到、分组未好之间的全屏白屏。
    var fetchDone by remember(currentUserId) {
        mutableStateOf(initialFriends.isNotEmpty() || ContactGroupCache.get(currentUserId) != null)
    }
    // ===== 拼音分组 =====
    // 组结果从进程缓存/启动预热里取；没有时先为 null，界面显示加载文案而不是白屏。
    var groupData by remember(currentUserId) {
        mutableStateOf(ContactGroupCache.get(currentUserId))
    }
    LaunchedEffect(friends, fetchDone) {
        // 冷启动首次：好友还没加载完 → 保持加载态，绝不先塞空组（否则会短暂白屏）
        if (friends.isEmpty() && !fetchDone) return@LaunchedEffect
        // 缓存已覆盖当前好友数量（表头占 extra）→ 直接命中，切回来不再重算、不再重组
        val cached = ContactGroupCache.get(currentUserId)
        if (cached != null && cached.items.count { it is ContactItem.Row } == friends.size) {
            groupData = cached
            return@LaunchedEffect
        }
        // 后台建组：不阻塞主线程，算完回写缓存供下次即时命中
        val t0 = System.currentTimeMillis()
        val gd = withContext(Dispatchers.Default) { buildGroup(friends) }
        groupData = gd
        ContactGroupCache.put(currentUserId, gd)
        ErrorReporter.debug("ContactsTrace", "groupData ready count=${gd.items.size} took=${System.currentTimeMillis() - t0}ms")
    }

    fun loadFriends(onDone: (() -> Unit)? = null) {
        scope.launch {
            val T = System.currentTimeMillis()
            ErrorReporter.debug("ContactsTrace", "loadFriends START t=${T}")
            // 1) 内存缓存
            val mem = FriendCache.get(currentUserId)
            if (mem != null && mem.isNotEmpty()) {
                friends = mem
                fetchDone = true
                // 内存命中时也确保磁盘有一份：覆盖“本次进程内刚拉到好友、但还没落过盘”的情况
                if (currentUserId > 0) {
                    val disk = withContext(Dispatchers.IO) {
                        runCatching { FriendDiskStore.load(context, currentUserId) }.getOrNull()
                    }
                    if (disk == null) FriendDiskStore.save(context, currentUserId, mem)
                }
                ErrorReporter.debug("ContactsTrace", "hit MEM CACHE n=${mem.size}, total took=${System.currentTimeMillis() - T}ms")
                onDone?.invoke()
                return@launch
            }
            // 2) 磁盘缓存
            val disk = withContext(Dispatchers.Default) { FriendDiskStore.load(context, currentUserId) }
            if (disk != null && disk.isNotEmpty()) {
                friends = disk
                fetchDone = true
                ErrorReporter.debug("ContactsTrace", "hit DISK CACHE n=${disk.size}, total took=${System.currentTimeMillis() - T}ms, network will run silently")
                // 静默拉网络更新 friends
                val r = try { withContext(Dispatchers.IO) { ChatRepository.getFriends() } } catch (_: Exception) { null }
                if (r != null && r.success && r.data != null && r.data.isNotEmpty()) {
                    friends = r.data!!
                    FriendCache.put(currentUserId, r.data!!)
                    FriendDiskStore.save(context, currentUserId, r.data!!)
                    ErrorReporter.debug("ContactsTrace", "network OK, friends updated, total took=${System.currentTimeMillis() - T}ms")
                } else {
                    ErrorReporter.debug("ContactsTrace", "network no-data/err, total took=${System.currentTimeMillis() - T}ms")
                }
                onDone?.invoke()
                return@launch
            }
            // 3) 无缓存 → 转圈等网络
            ErrorReporter.debug("ContactsTrace", "NO CACHE, fetching network...")
            try {
                val r = withContext(Dispatchers.IO) { ChatRepository.getFriends() }
                if (r.success && r.data != null && r.data.isNotEmpty()) {
                    friends = r.data!!
                    FriendCache.put(currentUserId, r.data!!)
                    FriendDiskStore.save(context, currentUserId, r.data!!)
                    ErrorReporter.debug("ContactsTrace", "network OK n=${r.data!!.size}, total took=${System.currentTimeMillis() - T}ms")
                } else {
                    ErrorReporter.debug("ContactsTrace", "network empty/fail, total took=${System.currentTimeMillis() - T}ms")
                }
            } catch (e: Exception) {
                ErrorReporter.debug("ContactsTrace", "network EXC=${e.javaClass.simpleName}:, total took=${System.currentTimeMillis() - T}ms")
            }
            fetchDone = true
            ErrorReporter.debug("ContactsTrace", "loadFriends END, fetchDone=true, total took=${System.currentTimeMillis() - T}ms")
            onDone?.invoke()
        }
    }

    LaunchedEffect(currentUserId) {
        // 热进入（启动预热/缓存里已有分组）：立刻用磁盘/缓存内容渲染，不等待、不显示加载、不产生状态写入，
        // 底栏放大动画因此完全不受影响。
        // 冷进入（确实没有任何缓存）：才需要读盘 + 建组，此时先让底栏放大动画（200ms）跑完再开始，
        // 把重活挪出动画窗口，同样不会卡底栏。
        if (groupData == null) kotlinx.coroutines.delay(300)
        ErrorReporter.debug("ContactsTrace", "LaunchedEffect fired (currentUserId=$currentUserId), NOW calling loadFriends")
        loadFriends()
    }

    // ===== 微信式过滚动（与"我的"同一套手感）=====
    // 下拉/上滑越过边界时内容整体被拽出去、松手弹回；不再用下拉刷新圈。
    // 标题栏 AuroraTopBar 在滚动区之外，这里只位移列表本身，所以标题不会被拽走；
    // 外层再加 clipToBounds，防止上滑时列表上边缘跑到标题栏上面去盖住标题。
    var overscrollPx by remember { mutableFloatStateOf(0f) }
    val overscrollStartFactor = 0.82f
    val density = LocalDensity.current
    // 渐进阻力：拽得越远，同样的手指位移换到的内容位移越少（e 指数连续衰减，不设死点）
    val overscrollSoftPx = with(density) {
        (240.dp.toPx() * overscrollStartFactor).coerceAtLeast(1f)
    }
    // 拽出超过这个距离再松手，视为「想刷新」：与聊天页同阈值，松手后标题右侧出转圈
    val refreshThresholdPx = with(density) { 72.dp.toPx() }
    val overscrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 1. 反向拖：先把拽出去的位移按 1:1 收回，收完再让列表正常滚动
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

                // 2. 顶到边界还想继续拖：在列表消费之前截走，按渐进阻力记成过滚动量
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

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = Offset.Zero

            // 松手瞬间掐掉惯性：否则回弹要等列表惯性跑完，看起来迟钝
            override suspend fun onPreFling(available: Velocity): Velocity =
                if (overscrollPx != 0f) available else Velocity.Zero

            // 松手回弹：临界阻尼不二次弹跳，刚度 700 起步干脆
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val released = overscrollPx
                if (released != 0f) {
                    animate(
                        initialValue = released,
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 700f)
                    ) { value, _ -> overscrollPx = value }
                }
                if (released > refreshThresholdPx && !isRefreshing) {
                    isRefreshing = true
                    com.aurora.chat.ui.components.ContactsRefreshState.start()
                    // 只失效内存缓存、保留磁盘：loadFriends 会走「磁盘先显示 + 静默拉网络」路径，
                    // 不会把 loading 置真、也不会清空列表重排。删好友才需要连磁盘一起失效。
                    FriendCache.invalidate(currentUserId)
                    loadFriends {
                        isRefreshing = false
                        com.aurora.chat.ui.components.ContactsRefreshState.finish()
                    }
                }
                return Velocity.Zero
            }
        }
    }

    // 外层裁掉越界部分：标题栏不在这个盒子里，永远不会被拽动或盖住
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(com.aurora.chat.ui.theme.appSurface())
    ) {
        // ===== LazyColumn：永远在这里，不会被移除 =====
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(overscrollConnection)
                .offset { IntOffset(0, overscrollPx.roundToInt()) },
            contentPadding = PaddingValues(
                bottom = 16.dp,
                top = 8.dp
            )
            ) {
            // ===== 顶部固定项：新的好友（红点在其右上角，点击复用好友申请流程）=====
            // 不跟 loading 挂钩：刷新时若临时移除这两行，列表会先少两行再补回来，位置就会跳。
            if (groupData != null) {
            item(key = "new_friends_entry") {
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFriendRequests() }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_profile_filled),
                            contentDescription = "新的好友",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(avatarCornerRadius(40.dp)))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "新的好友",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = com.aurora.chat.ui.theme.appTextPrimary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (friendRequestUnreadCount > 0) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = (-12).dp)
                                .size(18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawCircle(color = Color.Red, radius = size.minDimension / 2, center = center)
                            }
                            Text(
                                text = friendRequestUnreadCount.toString(),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(
                                    lineHeight = 11.sp,
                                    textAlign = TextAlign.Center,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                                )
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .padding(start = 66.dp, end = 16.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(com.aurora.chat.ui.theme.appCardBg())
                )
            }
            item(key = "my_groups_entry") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenGroups() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_group_default),
                        contentDescription = "群聊",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(avatarCornerRadius(40.dp)))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "群聊",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = com.aurora.chat.ui.theme.appTextPrimary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    Modifier
                        .padding(start = 66.dp, end = 16.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(com.aurora.chat.ui.theme.appCardBg())
                )
            }
            }

            val gd = groupData
            if (gd != null) {
                items(gd.items, key = { it.key }) { item ->
                    when (item) {
                        is ContactItem.Header -> {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(com.aurora.chat.ui.theme.appCardBg())
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Text(item.letter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                        }
                        is ContactItem.Row -> {
                            val friend = item.user
                            Column(Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface())) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenProfile(friend.id, friend.username) }
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    UserAvatar(userId = friend.id, userName = friend.username, size = 40.dp)
                                    Spacer(Modifier.width(10.dp))
                                    // 微信式：只显示名字，不再显示签名/邮箱。
                                    // 字号比原来缩了 0.5sp，把节省的空间让给头像，行高更舒展。
                                    Text(
                                        item.name,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    Modifier
                                        .padding(start = 66.dp, end = 16.dp)
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(com.aurora.chat.ui.theme.appCardBg())
                                )
                            }
                        }
                    }
                }
                // ===== 底部触底提示：滑到底后可见共有多少位联系人 =====
                item(key = "contact_count_footer") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "共 ${friends.size} 位联系人",
                            fontSize = 12.sp,
                            color = com.aurora.chat.ui.theme.appTextHint()
                        )
                    }
                }
            }
        }

        // ===== 纯文字 loading 动画（替代 BlueSpinner）=====
        // 只要分组还没就绪就显示加载：堵住“好友已从磁盘读到、分组还在后台算”这段空窗，
        // 否则 friends 非空但 groupData 为 null 时列表不渲染、加载文案又不显示 → 全屏白屏。
        if (groupData == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                var dotsIdx by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) { delay(280); dotsIdx = (dotsIdx + 1) % 3 }
                }
                val dots = arrayOf(".", "..", "...")
                Text(
                    text = "正在加载${dots[dotsIdx]}",
                    fontSize = 15.sp,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    letterSpacing = 0.5.sp,
                )
            }
        } else if (groupData!!.items.isEmpty() && fetchDone) {
            // 真正没有任何好友时的空态，避免整屏空白
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有联系人",
                    fontSize = 15.sp,
                    color = com.aurora.chat.ui.theme.appTextHint()
                )
            }
        }

        // 右侧字母索引条（微信/QQ 式：支持点击 + 拖动，触点精确对应字母）
        val gd2 = groupData
        if (gd2 != null && gd2.items.isNotEmpty()) {
            com.aurora.chat.ui.components.AlphabetIndexBar(
                letters = gd2.letters,
                onLetterSelected = { letter ->
                    val idx = gd2.letterIndex[letter] ?: return@AlphabetIndexBar
                    // 即时定位并取消上一次滚动：命中即到，拖动不抢位
                    indexScrollJob?.cancel()
                    indexScrollJob = scope.launch { listState.scrollToItem(idx) }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
            )
        }
    }
}

/** 手绘蓝色转圈：用 Canvas 画一段旋转蓝弧，100% 必然在画面上渲染（不依赖 Material 组件） */
@Composable
private fun BlueSpinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(44.dp).graphicsLayer { rotationZ = angle }) {
            val stroke = 4.dp.toPx()
            drawArc(
                color = Color(0xFF1E40AF),
                startAngle = 0f,
                sweepAngle = 120f,
                useCenter = false,
                topLeft = Offset(stroke, stroke),
                size = Size(size.width - stroke * 2, size.height - stroke * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}




