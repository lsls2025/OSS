package com.aurora.chat.ui.community
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发帖时挂载的一张卡片：好友（user）或群聊（group）。
 * @param id 用户卡为好友 userId；群卡为群「内部 id」（非负，用于 GroupAvatar / 群资料）。
 */
data class MountedCard(val type: String, val id: Long, val name: String) {
    companion object {
        const val TYPE_USER = "user"
        const val TYPE_GROUP = "group"
    }
}

/** 每类卡片（个人 / 群）最大挂载数量 */
private const val MAX_CARDS_PER_TYPE = 3

/**
 * 添加卡片选择界面：顶部 Tab 切换「好友 | 群聊」，列表展示头像 + 名称，支持多选。
 * 通过 [initialSelected] 回填发帖页已挂载的卡片（避免再次进入时被清空、个人卡与群卡互相替换），
 * 确认后通过 [onConfirm] 回填完整的选中列表。
 */
@Composable
fun CardPickerScreen(
    onDismiss: () -> Unit,
    initialSelected: List<MountedCard> = emptyList(),
    onConfirm: (List<MountedCard>) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) } // 0=好友, 1=群聊
    var friends by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var groups by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) } // (内部群 id, 群名)
    var loading by remember { mutableStateOf(true) }
    val selected = remember { mutableStateListOf<MountedCard>().also { it.addAll(initialSelected) } }

    // 进入时拉取一次好友与已加入群聊（群 = 会话 id<0，内部 id = -convId - 1000）
    LaunchedEffect(Unit) {
        val (fs, gs) = withContext(Dispatchers.IO) {
            val f = runCatching { ChatRepository.getFriends() }.getOrNull()?.data ?: emptyList()
            val g = runCatching { ChatRepository.getConversations() }.getOrNull()?.data
                ?.filter { it.id < 0 }
                ?.map { (-it.id - 1000L) to it.username }
                ?: emptyList()
            f to g
        }
        friends = fs
        groups = gs
        loading = false
    }

    fun toggle(card: MountedCard) {
        if (selected.contains(card)) {
            selected.remove(card)
        } else {
            val sameTypeCount = selected.count { it.type == card.type }
            if (sameTypeCount >= MAX_CARDS_PER_TYPE) {
                Toast.makeText(
                    ctx,
                    if (card.type == MountedCard.TYPE_GROUP) "群卡片最多 $MAX_CARDS_PER_TYPE 个"
                    else "个人卡片最多 $MAX_CARDS_PER_TYPE 个",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            selected.add(card)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .statusBarsPadding()
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "←", fontSize = 20.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onDismiss() }.padding(end = 12.dp)
            )
            Text("添加卡片", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
        }

        // ── Tab 切换：好友 | 群聊 ──
        PrimaryTabRow(
            selectedTabIndex = tab,
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            contentColor = Color(0xFF1E40AF)
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                selectedContentColor = Color(0xFF1E40AF),
                unselectedContentColor = com.aurora.chat.ui.theme.appTextSecondary(),
                text = { Text("好友", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                selectedContentColor = Color(0xFF1E40AF),
                unselectedContentColor = com.aurora.chat.ui.theme.appTextSecondary(),
                text = { Text("群聊", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            )
        }

        // ── 列表 ──
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在加载", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
                tab == 0 -> {
                    if (friends.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无好友", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(friends, key = { "u_${it.id}" }) { f ->
                                val card = MountedCard(MountedCard.TYPE_USER, f.id, f.username)
                                PickerRow(
                                    selected = selected.contains(card),
                                    name = f.username,
                                    avatar = { UserAvatar(userId = f.id, userName = f.username, size = 40.dp) },
                                    onClick = { toggle(card) }
                                )
                            }
                        }
                    }
                }
                else -> {
                    if (groups.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无群聊", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(groups, key = { "g_${it.first}" }) { (gid, name) ->
                                val card = MountedCard(MountedCard.TYPE_GROUP, gid, name)
                                PickerRow(
                                    selected = selected.contains(card),
                                    name = name,
                                    avatar = { GroupAvatar(internalGroupId = gid, groupName = name, size = 40.dp) },
                                    onClick = { toggle(card) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 底部确定 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (selected.isEmpty()) Color(0xFFD1D5DB) else Color(0xFF1E40AF))
                .clickable(enabled = selected.isNotEmpty()) {
                    if (selected.isEmpty()) {
                        Toast.makeText(ctx, "请先选择好友或群聊", Toast.LENGTH_SHORT).show()
                    } else {
                        onConfirm(selected.toList())
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected.isEmpty()) "确定" else "确定（${selected.size}）",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun PickerRow(
    selected: Boolean,
    name: String,
    avatar: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFFEFF3FF) else com.aurora.chat.ui.theme.appCardBg())
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        avatar()
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            fontSize = 15.sp,
            color = com.aurora.chat.ui.theme.appTextPrimary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                .border(1.5.dp, if (selected) Color(0xFF1E40AF) else Color(0xFFD1D5DB), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Text("✓", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
