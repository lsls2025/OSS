package com.aurora.chat.ui.chat

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
    if (ch.isDigit()) return "#"
    val t = hanLatin ?: return "#"
    return try {
        t.transliterate(ch.toString()).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
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

    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
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
                    color = Color(0xFF1F2937))
            }

            // ========== 群列表 ==========
            val gd = groupData
            if (loading || gd == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在加载", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            } else if (gd.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无群聊", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                    items(gd.items, key = { it.key }) { entry ->
                        when (entry) {
                            is GroupListEntry.Header -> {
                                Box(
                                    Modifier.fillMaxWidth().background(Color(0xFFF4F5F7))
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Text(entry.letter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF9CA3AF))
                                }
                            }
                            is GroupListEntry.Row -> {
                                val conv = entry.conv
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White)
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
                                            color = Color(0xFF1F2937), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                val showDiv = LocalShowDividers.current
                                if (showDiv) {
                                    Box(
                                        Modifier.padding(start = 68.dp, end = 16.dp).fillMaxWidth()
                                            .height(0.5.dp).background(Color(0xFFEEF0F3))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ========== 群详情覆盖层（复用 GroupDetailScreen，从右侧滑入） ==========
        val dGroup = detailConv
        if (dGroup != null) {
            val internalId = internalGroupIdOf(dGroup.id)
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(300)) { it },
                exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(300)) { it },
                modifier = Modifier.fillMaxSize()
            ) {
                GroupDetailScreen(
                    groupId = internalId,
                    groupName = detailName,
                    groupDisplayId = dGroup.let { 0L },
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

        // ========== 右侧字母索引条 ==========
        val gd2 = groupData
        if (!loading && gd2 != null && gd2.items.isNotEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
            ) {
                gd2.letters.forEach { letter ->
                    Text(
                        text = letter,
                        fontSize = 10.sp, color = Color(0xFF6B7280),
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