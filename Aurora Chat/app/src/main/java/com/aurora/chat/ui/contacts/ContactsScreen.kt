package com.aurora.chat.ui.contacts

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                arr.put(o)
            }
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_PREFIX + uid, arr.toString()).apply()
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
    if (ch.isDigit()) return "#"
    val t = hanLatin ?: return "#"
    return try {
        t.transliterate(ch.toString()).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
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

private fun buildGroup(friends: List<UserInfo>): GroupData {
    val buckets = LinkedHashMap<String, MutableList<UserInfo>>()
    friends.forEach { f ->
        val letter = pinyinInitial(f.username.ifBlank { f.email })
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
            items.add(ContactItem.Row(u, u.username.ifBlank { u.email }, key = "r_${u.id}"))
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
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 同步提前查缓存，第一帧就决定好初始值——有缓存秒显，无缓存才转圈
    val _memCache = FriendCache.get(currentUserId)
    val _diskCache = if (_memCache == null) FriendDiskStore.load(context, currentUserId) else null
    var friends by remember { mutableStateOf<List<UserInfo>>(_memCache ?: _diskCache ?: emptyList()) }
    var loading by remember { mutableStateOf(_memCache == null && _diskCache == null) }
    // ===== 拼音分组（后台计算） =====
    var groupData by remember { mutableStateOf<GroupData?>(null) }
    LaunchedEffect(friends) {
        val t0 = System.currentTimeMillis()
        val gd = withContext(Dispatchers.Default) { buildGroup(friends) }
        groupData = gd
        ErrorReporter.debug("ContactsTrace", "groupData ready count=${gd.items.size} took=${System.currentTimeMillis() - t0}ms")
    }

    fun loadFriends() {
        scope.launch {
            val T = System.currentTimeMillis()
            ErrorReporter.debug("ContactsTrace", "loadFriends START t=${T}")
            // 1) 内存缓存
            val mem = FriendCache.get(currentUserId)
            if (mem != null && mem.isNotEmpty()) {
                friends = mem
                loading = false
                ErrorReporter.debug("ContactsTrace", "hit MEM CACHE n=${mem.size}, total took=${System.currentTimeMillis() - T}ms")
                return@launch
            }
            // 2) 磁盘缓存
            val disk = withContext(Dispatchers.Default) { FriendDiskStore.load(context, currentUserId) }
            if (disk != null && disk.isNotEmpty()) {
                friends = disk
                loading = false
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
                return@launch
            }
            // 3) 无缓存 → 转圈等网络
            ErrorReporter.debug("ContactsTrace", "NO CACHE, loading=true, fetching network...")
            loading = true
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
            loading = false
            ErrorReporter.debug("ContactsTrace", "loadFriends END, loading=false, total took=${System.currentTimeMillis() - T}ms")
        }
    }

    LaunchedEffect(currentUserId) {
        ErrorReporter.debug("ContactsTrace", "LaunchedEffect fired (currentUserId=$currentUserId), NOW calling loadFriends")
        loadFriends()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // ===== LazyColumn：永远在这里，不会被移除 =====
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
            ) {
            // ===== 顶部固定项：新的好友（红点在其右上角，点击复用好友申请流程）=====
            // 加载中先不显示，等数据就绪后与联系人列表一起出现
            if (!loading && groupData != null) {
            item(key = "new_friends_entry") {
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFriendRequests() }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_profile),
                            contentDescription = "新的好友",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "新的好友",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1F2937),
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
                        .padding(start = 68.dp, end = 16.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Color(0xFFEEF0F3))
                )
            }
            item(key = "my_groups_entry") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenGroups() }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_group_default),
                        contentDescription = "群聊",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "群聊",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2937),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    Modifier
                        .padding(start = 68.dp, end = 16.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Color(0xFFEEF0F3))
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
                                    .background(Color(0xFFF4F5F7))
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Text(item.letter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9CA3AF))
                            }
                        }
                        is ContactItem.Row -> {
                            val friend = item.user
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White)
                                    .clickable { onOpenProfile(friend.id, friend.username) }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(userId = friend.id, userName = friend.username, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF1F2937),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (friend.email.isNotEmpty() && friend.username.isNotEmpty()) {
                                        Text(
                                            friend.email,
                                            fontSize = 12.sp,
                                            color = Color(0xFF9CA3AF),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                }
                                Box(
                                Modifier
                                    .padding(start = 68.dp, end = 16.dp)
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(Color(0xFFEEF0F3))
                            )
                        }
                    }
                }
            }
        }

        // ===== 纯文字 loading 动画（替代 BlueSpinner）=====
        if (loading || groupData == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                var dotsIdx by remember { mutableStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) { delay(280); dotsIdx = (dotsIdx + 1) % 3 }
                }
                val dots = arrayOf(".", "..", "...")
                Text(
                    text = "正在加载${dots[dotsIdx]}",
                    fontSize = 15.sp,
                    color = Color(0xFF6B7280),
                    letterSpacing = 0.5.sp,
                )
            }
        }

        // 右侧字母索引条
        val gd2 = groupData
        if (!loading && gd2 != null && gd2.items.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
            ) {
                gd2.letters.forEach { letter ->
                    Text(
                        text = letter,
                        fontSize = 10.sp,
                        color = Color(0xFF6B7280),
                        modifier = Modifier
                            .padding(horizontal = 2.dp, vertical = 1.dp)
                            .clickable {
                                val idx = gd2.letterIndex[letter] ?: return@clickable
                                scope.launch { listState.scrollToItem(idx) }
                            }
                    )
                }
            }
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




