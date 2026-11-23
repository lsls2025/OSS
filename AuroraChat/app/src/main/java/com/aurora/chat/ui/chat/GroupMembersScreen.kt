package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R
import com.aurora.chat.data.local.AvatarCache
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.AlphabetIndexBar
import com.aurora.chat.ui.components.avatarCornerRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ==================== 数据模型与分组（纯函数，便于测试/后台计算） ====================

/** 群成员条目：id / 显示名 / 角色（owner|admin|member） */
private data class MemberItem(val uid: Long, val name: String, val role: String)

/** 成员列表扁平条目：分组头或成员行 */
private sealed interface MemberRow {
    val key: String
    data class Header(val title: String, override val key: String) : MemberRow
    data class Member(val item: MemberItem, override val key: String) : MemberRow
}

/** 用 Android 内置 ICU 转换「汉字→拼音首字母」，零新增依赖（与联系人/群列表同款实现） */
private val memberHanLatin: android.icu.text.Transliterator? by lazy {
    try { android.icu.text.Transliterator.getInstance("Han-Latin") } catch (_: Throwable) { null }
}

private fun membersPinyinInitial(name: String): String {
    if (name.isBlank()) return "#"
    val ch = name.trim().first()
    if (ch in 'A'..'Z') return ch.toString()
    if (ch in 'a'..'z') return ch.uppercaseChar().toString()
    // 其余（汉字 / @ / . / 数字 / 符号等）：汉字走 ICU 拼音，其余一律归 #，绝不排在 A 前面
    val t = memberHanLatin ?: return "#"
    return try {
        val r = t.transliterate(ch.toString()).trim().firstOrNull()?.uppercaseChar()
        if (r != null && r in 'A'..'Z') r.toString() else "#"
    } catch (_: Throwable) { "#" }
}

/**
 * 构建成员列表条目：
 *  1) 群主/管理员 置顶为第一段（群主在前、管理员随后）；
 *  2) 其余成员按拼音首字母 A-Z 分组，# 收尾。
 * 返回扁平条目 + 每个分组首项索引（供字母索引栏跳转）。
 */
private fun buildMemberRows(members: List<MemberItem>): Pair<List<MemberRow>, Map<String, Int>> {
    val rows = ArrayList<MemberRow>(members.size + 8)
    val index = HashMap<String, Int>()

    val admins = members.filter { it.role == "owner" || it.role == "admin" }
        .sortedBy { if (it.role == "owner") 0 else 1 }
    if (admins.isNotEmpty()) {
        index["admin"] = rows.size
        rows.add(MemberRow.Header("群主/管理员(${admins.size}人)", "h_admin"))
        admins.forEach { rows.add(MemberRow.Member(it, "m_${it.uid}")) }
    }

    val buckets = LinkedHashMap<String, MutableList<MemberItem>>()
    members.filter { it.role != "owner" && it.role != "admin" }.forEach { m ->
        buckets.getOrPut(membersPinyinInitial(m.name)) { mutableListOf() }.add(m)
    }
    val ordered = buckets.keys.sortedWith(compareBy<String> { if (it == "#") 1 else 0 }.thenBy { it })
    ordered.forEach { letter ->
        index[letter] = rows.size
        rows.add(MemberRow.Header("$letter(${buckets[letter]!!.size}人)", "h_$letter"))
        buckets[letter]!!.forEach { rows.add(MemberRow.Member(it, "m_${it.uid}")) }
    }
    return rows to index
}

/** 字母索引栏固定展示 A-Z + #（与截图一致），点击无对应分组时静默跳过 */
private val INDEX_LETTERS: List<String> = ('A'..'Z').map { it.toString() } + "#"

/** 首批立即流入列表的行数（先让首屏有内容，其余按帧续上） */
private const val INITIAL_REVEAL_ROWS = 36

/** JSONArray → JSONObject 列表（仅取引用，解析留给后台线程） */
private fun memberJsonToList(arr: JSONArray): List<JSONObject> {
    val out = ArrayList<JSONObject>(arr.length())
    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
    return out
}

/** 解析成员对象为可展示条目（在 Dispatchers.Default 上执行） */
private fun parseMemberItems(objects: List<JSONObject>): List<MemberItem> {
    val out = ArrayList<MemberItem>(objects.size)
    for (o in objects) {
        val uid = o.optLong("user_id")
        if (uid <= 0L) continue
        val name = o.optString("username", "").ifBlank { "用户$uid" }
        val role = o.optString("role", "member").ifBlank { "member" }
        out.add(MemberItem(uid, name, role))
    }
    return out
}

// ==================== 界面 ====================

/**
 * 群聊成员全屏界面（微信通讯录风格）：
 *  - 顶部栏：返回箭头 + 标题「群聊成员」+ 右侧三个点（功能后续迭代）
 *  - 搜索框：按成员名字或 ID 过滤
 *  - 右侧字母索引栏 A-Z + #：点击平滑滚动到对应分组
 *  - 列表：群主/管理员置顶，其余按拼音首字母分组
 * 由「群聊信息」页群成员卡右上箭头进入。
 */
@Composable
fun GroupMembersScreen(
    groupId: Long,
    onBack: () -> Unit,
    onOpenProfile: (Long, String) -> Unit = { _, _ -> },
    /** 群信息页已经拉到的全量成员（复用可省掉二次大请求，进入即出内容） */
    initialMembers: List<JSONObject> = emptyList()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUserId = com.aurora.chat.data.api.AuroraApi.currentUserId

    // 完整行数据（后台算好）与「已流入列表的行数」分开存：
    // 进入先出标题栏/搜索框，成员按帧一行行往下追加，不把整份大列表一次性塞进首帧。
    var allRows by remember { mutableStateOf<List<MemberRow>>(emptyList()) }
    var letterIndex by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var visibleCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    // 点击「添加」后进入发送好友申请界面的目标成员（null=不显示）
    var greetingFor by remember { mutableStateOf<MemberItem?>(null) }
    // 全量成员（用于判断「我」的角色）与好友 id 集合（决定是否显示「添加好友」）
    var memberList by remember { mutableStateOf<List<MemberItem>>(emptyList()) }
    var friendIds by remember { mutableStateOf<Set<Long>?>(null) }
    // 群管理操作：操作弹窗的目标 / 踢人确认目标 / 禁言面板目标，以及操作后的刷新计数
    var actionTarget by remember { mutableStateOf<MemberItem?>(null) }
    var kickTarget by remember { mutableStateOf<MemberItem?>(null) }
    var muteTarget by remember { mutableStateOf<MemberItem?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    // 首帧拿到群信息页的成员就作为唯一数据源：之后父级刷新不再让本页重载（避免列表被重置）
    val seedMembers = remember { initialMembers.ifEmpty { null } }

    // 拉取成员：优先复用群信息页数据；解析 + 拼音分组全部在后台线程完成，主线程只接收结果。
    LaunchedEffect(groupId, reloadToken) {
        if (reloadToken == 0) {
            loading = true
            visibleCount = 0
            allRows = emptyList()
            letterIndex = emptyMap()
        }

        // ① 首帧：先把上次保存的成员名单铺出来（IO 读盘，不阻塞组合）。
        //    群成员多时这一步能省掉整段白屏等待，断网更是直接就有完整名单。
        if (seedMembers == null && reloadToken == 0) {
            val cachedArr = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.aurora.chat.data.local.GroupMemberStore.load(context, groupId)
            }
            if (cachedArr != null && cachedArr.length() > 0) {
                val cachedObjects = withContext(Dispatchers.Default) { memberJsonToList(cachedArr) }
                val cachedItems = withContext(Dispatchers.Default) { parseMemberItems(cachedObjects) }
                val cachedBuilt = withContext(Dispatchers.Default) { buildMemberRows(cachedItems) }
                allRows = cachedBuilt.first
                letterIndex = cachedBuilt.second
                memberList = cachedItems
                loading = false
            }
        }

        // ② 再取仓库结果：联网成功覆盖成最新名单；断网时仓库层会直接返回同一份缓存。
        //    管理操作（设置/取消管理员、踢人）后 reloadToken 变化 → 强制走一次仓库刷新。
        val objects: List<JSONObject>? = if (reloadToken > 0) {
            val r = ChatRepository.getGroupMembers(groupId)
            if (r.success && r.data != null) {
                withContext(Dispatchers.Default) { memberJsonToList(r.data!!) }
            } else null
        } else seedMembers ?: run {
            val r = ChatRepository.getGroupMembers(groupId)
            if (r.success && r.data != null) {
                withContext(Dispatchers.Default) { memberJsonToList(r.data!!) }
            } else null
        }

        if (objects != null) {
            val items = withContext(Dispatchers.Default) { parseMemberItems(objects) }
            val built = withContext(Dispatchers.Default) { buildMemberRows(items) }
            allRows = built.first
            letterIndex = built.second
            memberList = items
            // 管理操作后的刷新：直接把全部行放出来，避免列表从头逐帧长出来
            if (reloadToken > 0) visibleCount = allRows.size
        }
        loading = false
    }

    // 「我」在群里的角色：决定行尾显示「添加」还是「操作」，以及操作弹窗里能出现哪些项
    val myRole = remember(memberList, currentUserId) {
        memberList.firstOrNull { it.uid == currentUserId }?.role ?: "member"
    }
    val isOwner = myRole == "owner"
    val canOperate = isOwner || myRole == "admin"

    // 好友集合：只有管理员/群主需要（普通成员行尾只有「添加」）。
    // 拉取失败时保持 null → 仍然给出「添加好友」，让用户自己发申请（服务端会兜底校验）。
    LaunchedEffect(canOperate) {
        if (!canOperate) return@LaunchedEffect
        val r = runCatching { ChatRepository.getFriends() }.getOrNull()
        if (r?.success == true) friendIds = r.data?.map { it.id }?.toSet() ?: emptySet()
    }

    // 逐帧追加：首批立即出现，其余每帧补一批，列表「一行一行」往下长，主线程不被打满。
    LaunchedEffect(allRows) {
        val total = allRows.size
        if (total == 0) {
            visibleCount = 0
            return@LaunchedEffect
        }
        // 管理操作后的刷新：列表本来就铺满了，直接全量显示，不再逐帧重播一遍
        if (reloadToken > 0) {
            visibleCount = total
            return@LaunchedEffect
        }
        visibleCount = minOf(total, INITIAL_REVEAL_ROWS)
        val step = maxOf(24, total / 60)   // 大群约 1 秒内铺完，避免"加载中"长期挂在尾部
        while (visibleCount < total) {
            withFrameNanos { }
            visibleCount = minOf(total, visibleCount + step)
        }
    }

    // 搜索命中项（基于全量行，不受「已流入窗口」限制）
    val searchRows: List<MemberRow>? = remember(query, allRows) {
        val q = query.trim()
        if (q.isEmpty()) null else allRows.filterIsInstance<MemberRow.Member>()
            .filter { it.item.name.contains(q, ignoreCase = true) || it.item.uid.toString().contains(q) }
    }
    // 实际渲染的行：正常态只取已流入的前 N 行，搜索态直接给全部命中
    val displayRows = remember(allRows, visibleCount, searchRows) {
        searchRows ?: if (visibleCount >= allRows.size) allRows else allRows.subList(0, visibleCount)
    }

    val listState = rememberLazyListState()
    // 滚动到已流入窗口末尾时继续放开更多行（滚多少加载多少，不预先硬塞）
    LaunchedEffect(listState, allRows) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= 0 && visibleCount < allRows.size && last >= visibleCount - 4) {
                    visibleCount = minOf(allRows.size, visibleCount + 30)
                }
            }
    }
    // 字母索引触发的滚动任务（新的一次先取消旧的，避免抢位）
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 仅「发送好友申请」子面板打开时拦截返回（关子面板，属本组件内部子页）。
    // 整个成员列表的返回交给群内导航栈统一处理（popGroupNav）；本组件不再注册整层 BackHandler，
    // 避免与栈处理器重叠、由组合顺序决定谁先响应而导致"跳两级"。
    BackHandler(enabled = greetingFor != null || muteTarget != null) {
        if (muteTarget != null) muteTarget = null else greetingFor = null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appCardBg())
            .imePadding()
    ) {
        // 事件穿透防御：全屏覆盖层必须先吃掉触摸，避免点击穿透到下层群设置列表
        com.aurora.chat.ui.components.EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        // 注意：statusBarsPadding 不能加在根 Box 上，否则内部全屏浮层（发送好友申请）
        // 只能从内容区开始铺白底，状态栏那条会露出父级灰底 = 视觉上"透过去"。
        Column(Modifier.fillMaxSize()) {
            // ── 顶部白底区：标题栏 + 搜索框整块纯白（白底先铺满，再吃状态栏内边距） ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .statusBarsPadding()
            ) {
                // 标题栏：返回 / 标题 / 三个点
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).clickable { onBack() },
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
                        "群聊成员",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.width(12.dp))
                    // 三个点（本版本仅占位）
                    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        Text("⋯", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                    }
                }

                // 搜索框（标题下方第一个位置，输入框本身仍是灰色）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                        cursorBrush = SolidColor(Color(0xFF1E40AF)),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("搜索", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                            inner()
                        }
                    )
                }
            }

            // ── 成员列表 + 右侧字母索引栏 ──
            Box(Modifier.fillMaxSize()) {
                when {
                    loading && allRows.isEmpty() -> {
                        Text(
                            "加载中…",
                            fontSize = 14.sp,
                            color = com.aurora.chat.ui.theme.appTextHint(),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    allRows.isEmpty() -> {
                        Text(
                            if (query.isBlank()) "暂无成员" else "未找到相关成员",
                            fontSize = 14.sp,
                            color = com.aurora.chat.ui.theme.appTextHint(),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    displayRows.isEmpty() -> {
                        Text(
                            "未找到相关成员",
                            fontSize = 14.sp,
                            color = com.aurora.chat.ui.theme.appTextHint(),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                count = displayRows.size,
                                key = { i -> displayRows[i].key }
                            ) { i ->
                                when (val row = displayRows[i]) {
                                    is MemberRow.Header -> MemberSectionHeader(row.title)
                                    is MemberRow.Member -> MemberRowItem(
                                        item = row.item,
                                        isSelf = row.item.uid == currentUserId,
                                        onClick = { onOpenProfile(row.item.uid, row.item.name) },
                                        onAddFriend = { greetingFor = row.item },
                                        // 群主/管理员看别人 = 「操作」；普通成员（以及管理员看群主）只有「添加」
                                        showOperate = canOperate && row.item.role != "owner",
                                        onOperate = { actionTarget = row.item }
                                    )
                                }
                            }
                            // 还有没流入的成员：底部给一行轻提示，滚到底会自动续上
                            if (searchRows == null && visibleCount < allRows.size) {
                                item(key = "loading_more") {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(com.aurora.chat.ui.theme.appSurface())
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("加载中…", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    }
                                }
                            }
                        }
                    }
                }

                // 右侧字母索引栏（A-Z + #）：微信/QQ 式，支持点击 + 拖动，跟手气泡提示
                // 搜索过滤时隐藏（此时列表是平铺结果，按字母跳转会错位）
                if (!loading && query.isBlank() && displayRows.isNotEmpty()) {
                    AlphabetIndexBar(
                        letters = INDEX_LETTERS,
                        onLetterSelected = { letter ->
                            val idx = letterIndex[letter] ?: return@AlphabetIndexBar
                            // 即时定位并取消上一次滚动：命中即到，拖动不抢位
                            scrollJob?.cancel()
                            scrollJob = scope.launch {
                                // 目标可能还在「未流入」窗口外：先放开到目标位置（只改 count，
                                // LazyColumn 只组合可见项，不会因此卡顿），等一帧再滚
                                if (visibleCount <= idx) visibleCount = allRows.size
                                withFrameNanos { }
                                listState.scrollToItem(idx)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                    )
                }
            }
        }

        // ── 发送好友申请界面（复用全局 SendGreetingPanel，从右切入） ──
        AnimatedVisibility(
            visible = greetingFor != null,
            enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(260)),
            exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(260))
        ) {
            val target = greetingFor
            if (target != null) {
                SendGreetingPanel(
                    userName = target.name,
                    onSend = { greeting ->
                        scope.launch {
                            val r = ChatRepository.sendFriendRequest(target.uid.toString(), greeting)
                            Toast.makeText(
                                context,
                                if (r.success) "已发送好友申请" else "发送失败: ${r.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (r.success) greetingFor = null
                        }
                    },
                    onBack = { greetingFor = null }
                )
            }
        }

        // ── 「操作」弹窗：禁言 / 设置(取消)管理员 / 踢出群聊 / 添加好友 ──
        actionTarget?.let { target ->
            val isTargetAdmin = target.role == "admin"
            val alreadyFriend = friendIds?.contains(target.uid) == true
            // 踢人权限：群主可踢管理员和普通成员；管理员只能踢普通成员（与 QQ 一致，避免服务端拒绝）
            val canKick = target.role != "owner" && (isOwner || target.role == "member")
            AlertDialog(
                onDismissRequest = { actionTarget = null },
                containerColor = com.aurora.chat.ui.theme.appSurface(),
                title = {
                    Text(
                        target.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        // 1) 禁言：直接复用现成的禁言成员面板（搜索框预填该成员）
                        MemberActionRow("禁言") {
                            actionTarget = null
                            muteTarget = target
                        }
                        // 2) 设置/取消管理员（仅群主有权限；已是管理员 → 红色「取消管理员」）
                        if (isOwner) {
                            MemberActionRow(
                                if (isTargetAdmin) "取消管理员" else "设置成管理员",
                                danger = isTargetAdmin
                            ) {
                                actionTarget = null
                                val newRole = if (isTargetAdmin) "member" else "admin"
                                scope.launch {
                                    val r = ChatRepository.setGroupAdmin(groupId, target.uid, newRole)
                                    Toast.makeText(
                                        context,
                                        if (r.success) {
                                            if (isTargetAdmin) "已取消管理员" else "已设为管理员"
                                        } else "操作失败: ${r.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (r.success) reloadToken++
                                }
                            }
                        }
                        // 3) 踢出群聊：二次确认后执行
                        if (canKick) {
                            MemberActionRow("踢出群聊", danger = true) {
                                actionTarget = null
                                kickTarget = target
                            }
                        }
                        // 4) 添加好友：已经是好友就不显示
                        if (!alreadyFriend) {
                            MemberActionRow("添加好友") {
                                actionTarget = null
                                greetingFor = target
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { actionTarget = null }) {
                        Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            )
        }

        // ── 踢出群聊二次确认 ──
        kickTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { kickTarget = null },
                containerColor = com.aurora.chat.ui.theme.appSurface(),
                title = { Text("踢出群聊", fontWeight = FontWeight.SemiBold) },
                text = { Text("确定将「${target.name}」移出本群吗？", fontSize = 14.sp, lineHeight = 21.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        kickTarget = null
                        scope.launch {
                            val r = ChatRepository.removeGroupMember(groupId, target.uid)
                            Toast.makeText(
                                context,
                                if (r.success) "已移出群聊" else "操作失败: ${r.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (r.success) reloadToken++
                        }
                    }) { Text("确定", color = Color(0xFFDC2626), fontWeight = FontWeight.Medium) }
                },
                dismissButton = {
                    TextButton(onClick = { kickTarget = null }) {
                        Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            )
        }

        // ── 禁言面板（复用群管理里现成的 MuteMemberPanel，整屏覆盖） ──
        muteTarget?.let { target ->
            MuteMemberPanel(
                groupId = groupId,
                currentUserId = currentUserId,
                isAdmin = canOperate,
                isOwner = isOwner,
                onDismiss = { muteTarget = null },
                initialKeyword = target.name
            )
        }
    }
}

/** 「操作」弹窗里的一行：整行可点，危险操作用红色 */
@Composable
private fun MemberActionRow(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = label,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = if (danger) Color(0xFFDC2626) else com.aurora.chat.ui.theme.appTextPrimary(),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun MemberSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        color = com.aurora.chat.ui.theme.appTextHint(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 40.dp, top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun MemberRowItem(
    item: MemberItem,
    isSelf: Boolean,
    onClick: () -> Unit,
    onAddFriend: () -> Unit,
    /** true = 行尾显示「操作」（当前用户是群主/管理员，且目标不是群主） */
    showOperate: Boolean = false,
    onOperate: () -> Unit = {}
) {
    val context = LocalContext.current
    // 头像按行懒加载：只给真正进入视口的行开协程（原先给全群几千人各开一个，滚动时全在主线程刷 Map）
    var avatar by remember(item.uid) {
        mutableStateOf(AvatarCache.getCachedUserAvatarSync(item.uid))
    }
    LaunchedEffect(item.uid) {
        val cached = avatar
        if (cached == null) {
            avatar = runCatching { ChatRepository.loadAvatar(context, item.uid) }.getOrNull()
        }
    }
    val avatarBitmap = avatar
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(com.aurora.chat.ui.theme.appSurface())
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 36.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(avatarCornerRadius(40.dp))),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_profile_filled),
                    contentDescription = item.name,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(avatarCornerRadius(40.dp)))
                )
            }
            Spacer(Modifier.width(10.dp))
            // 角色徽章（群主=金、管理员=蓝）
            if (item.role == "owner" || item.role == "admin") {
                val badgeBg = if (item.role == "owner") Color(0xFFFEF3C7) else com.aurora.chat.ui.theme.appSelectedBg()
                val badgeFg = if (item.role == "owner") Color(0xFFD97706) else Color(0xFF0284C7)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeBg)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(if (item.role == "owner") "群主" else "管理员", fontSize = 10.sp, color = badgeFg)
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = item.name,
                fontSize = 14.sp,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            when {
                isSelf -> Text("我", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                showOperate -> Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .clickable { onOperate() }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text("操作", fontSize = 12.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
                }
                else -> Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .clickable { onAddFriend() }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text("添加", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        }
        // 行间发丝分隔线（从头像后开始，与 QQ/微信一致）
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 66.dp)
                .height(0.5.dp)
                .background(com.aurora.chat.ui.theme.appCardBg())
        )
    }
}
