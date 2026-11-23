package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.LocalShowDividers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** 将"会话 id"转换为"群内部 id"：会话 id = -(1000 + 内部 id)，官方群内部 id=1 对应会话 id=-1001 */
private fun internalGroupIdOf(convId: Long): Long = -convId - 1000L

/** 分组后的扁平条目：字母标题或群行 */
private sealed interface GroupListEntry {
    val key: String
    data class Header(val letter: String, override val key: String) : GroupListEntry
    data class Row(val conv: ConversationInfo, override val key: String) : GroupListEntry
}

private data class GroupListData(
    val items: List<GroupListEntry>,
    val letters: List<String>,
    val letterIndex: Map<String, Int>
)

private fun buildGroupList(groups: List<ConversationInfo>): GroupListData {
    val buckets = LinkedHashMap<String, MutableList<ConversationInfo>>()
    groups.forEach { g ->
        val letter = pinyinInitial(g.username.ifBlank { "群聊" })
        buckets.getOrPut(letter) { mutableListOf() }.add(g)
    }
    val ordered = buckets.keys.sortedWith(
        compareBy<String> { if (it == "#") 1 else 0 }.thenBy { it }
    )
    val items = ArrayList<GroupListEntry>(groups.size + ordered.size)
    val letterIndex = HashMap<String, Int>()
    ordered.forEach { letter ->
        letterIndex[letter] = items.size
        items.add(GroupListEntry.Header(letter, key = "g_$letter"))
        buckets[letter]!!.forEach { c ->
            items.add(GroupListEntry.Row(c, key = "r_${c.id}"))
        }
    }
    return GroupListData(items, ordered, letterIndex)
}

/** 我的群聊列表：从右滑出的完整界面，列出当前用户所在的所有群聊（含官方群），按 ABC 逻辑排列 */
@Composable
fun MyGroupsScreen(
    currentUserId: Long,
    onOpenGroupDetail: (convId: Long, groupName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<ConversationInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var groupData by remember { mutableStateOf<GroupListData?>(null) }

    // 群详情面板状态：点击群行打开详情（不直接进对话），返回回到本列表
    var detailConvId by remember { mutableStateOf<Long?>(null) }
    var detailName by remember { mutableStateOf("") }
    val detailConv = detailConvId?.let { id -> groups.firstOrNull { it.id == id } }

    // 液态模式：列表卡片玻璃化（折射源用纯色画布，避免纯白塑料感）
    val ctx = LocalContext.current
    // 我的群列表液态开关（关 = 经典列表）

    // 加载群列表：复用会话接口，取 id<0 的即为群会话（含官方群 -1001）
    LaunchedEffect(currentUserId) {
        loading = true
        val r = withContext(Dispatchers.IO) { runCatching { ChatRepository.getConversations() }.getOrNull() }
        val list = r?.data?.filter { it.id < 0 } ?: emptyList()
        groups = list
        loading = false
    }

    // 拼音分组
    LaunchedEffect(groups) {
        val gd = withContext(Dispatchers.Default) { buildGroupList(groups) }
        groupData = gd
    }

    Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ========== 顶栏 ==========
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable { onDismiss() })
                Spacer(Modifier.width(12.dp))
                Text("群聊", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary())
            }

            // ========== 群列表 ==========
            val gd = groupData
            if (loading || gd == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在加载", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else if (gd.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无群聊", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                    items(gd.items, key = { it.key }) { entry ->
                        when (entry) {
                            is GroupListEntry.Header -> {
                                Box(
                                    Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg())
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Text(entry.letter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                            is GroupListEntry.Row -> {
                                val conv = entry.conv
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(com.aurora.chat.ui.theme.appSurface())
                                        .clickable {
                                            detailConvId = conv.id
                                            detailName = conv.username
                                        }
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GroupAvatar(
                                        internalGroupId = internalGroupIdOf(conv.id),
                                        groupName = conv.username,
                                        size = 40.dp
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            conv.username,
                                            fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                            color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                val showDiv = LocalShowDividers.current
                                if (showDiv) {
                                    Box(
                                        Modifier.padding(start = 68.dp, end = 16.dp).fillMaxWidth()
                                            .height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg())
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ========== 右侧字母索引条（与联系人/群成员一致：支持点击 + 上下滑动） ==========
        // 必须画在列表之后、群详情之前：详情是同一 Box 里的覆盖层，
        // 索引条若放在最后，就会浮在详情页上面（"点进群聊信息还挂着一排 A-Z"）。
        // 条件里也要排除详情已展开的情况，双保险。
        val gd2 = groupData
        if (detailConvId == null && !loading && gd2 != null && gd2.items.isNotEmpty()) {
            com.aurora.chat.ui.components.AlphabetIndexBar(
                letters = gd2.letters,
                onLetterSelected = { letter ->
                    val idx = gd2.letterIndex[letter] ?: return@AlphabetIndexBar
                    scope.launch { listState.scrollToItem(idx) }
                },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }

        // ========== 群详情覆盖层（复用 GroupDetailScreen，从右侧滑入） ==========
        // 进出场动画：AnimatedVisibility 常驻组合树、用 visible 切状态，才能同时播放
        // 进入（false→true）与退出（true→false）动画；原来用 if(dGroup!=null) 包裹且 visible 写死 true，
        // 挂载即满显，没有任何滑动转场，观感很突兀。
        // 返回层级：详情是群列表内部的覆盖层，必须自己拦截返回——否则系统的
        // BackHandler(showMyGroups) 会把整个「我的群聊」一起关掉，造成左滑/返回跳两级回到联系人。
        BackHandler(enabled = detailConvId != null) { detailConvId = null }
        val dGroup = detailConv
        // 退场时 detailConvId 已置空、dGroup 立即变 null；用快照保留最后一组，确保滑出动画有内容可播。
        var detailSnapshot by remember { mutableStateOf(dGroup) }
        LaunchedEffect(dGroup) { if (dGroup != null) detailSnapshot = dGroup }
        AnimatedVisibility(
            visible = detailConvId != null,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            val g = detailSnapshot
            if (g != null) {
                val internalId = internalGroupIdOf(g.id)
                GroupDetailScreen(
                    groupId = internalId,
                    groupName = detailName,
                    groupDisplayId = 0L,
                    groupSignature = "",
                    initialJoined = true, // 从"我的群聊"进入，用户必在群内 → 底部显示"发消息"
                    onBack = { detailConvId = null },
                    onOpenGroupChat = { groupConvId, groupName ->
                        detailConvId = null
                        onOpenGroupDetail(groupConvId, groupName)
                    },
                    onDismissSelf = { detailConvId = null }
                )
            }
        }
    }
}
