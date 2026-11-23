package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 群黑名单（群主 / 管理员）
 *
 * 这里的成员是被移出群聊时勾选了「拉入群黑名单」的人。只要还在名单里，
 * 他重新加群会被服务端直接拒绝（提示「你已被该群拉黑」），在群里也发不了消息。
 * 所以这个页面必须能打开 —— 否则踢错人之后没有任何撤销入口。
 */
@Composable
fun GroupBlacklistScreen(groupId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var blockedUsers by remember(groupId) { mutableStateOf<List<JSONObject>>(emptyList()) }

    LaunchedEffect(groupId) {
        val arr = runCatching { AuroraApi.getGroupBlacklist(groupId) }.getOrNull()?.data
        if (arr != null) {
            blockedUsers = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶部栏：返回 / 标题 ──
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
                    "群黑名单",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(28.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))

            if (blockedUsers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无记录", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(blockedUsers, key = { it.optLong("user_id", 0L) }) { user ->
                        val uid = user.optLong("user_id", 0L)
                        val name = user.optString("username", "").ifBlank { "用户$uid" }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(userId = uid, userName = name, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                name,
                                fontSize = 14.sp,
                                color = com.aurora.chat.ui.theme.appTextPrimary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                Modifier
                                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(8.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        scope.launch {
                                            val r = AuroraApi.removeFromGroupBlacklist(groupId, uid)
                                            if (r.success) {
                                                blockedUsers = blockedUsers.filter { it.optLong("user_id", 0L) != uid }
                                                Toast.makeText(context, "已解除", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "解除失败: ${r.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text("解除", fontSize = 13.sp, color = Color(0xFF1E40AF))
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 62.dp, end = 16.dp)
                                .height(0.5.dp)
                                .background(com.aurora.chat.ui.theme.appCardBg())
                        )
                    }
                }
            }
        }
    }
}
