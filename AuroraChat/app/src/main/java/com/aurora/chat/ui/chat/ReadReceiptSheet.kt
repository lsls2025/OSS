package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.UserAvatar

/** 一条已读记录（只用于这个抽屉） */
private data class ReadMember(val userId: Long, val name: String, val readAtText: String)

/**
 * 群聊「N人已读」点开后的底部抽屉：列出到底谁读了、什么时候读的。
 *
 * 这里才走单条 `/api/messages/info/{id}` —— 一次点开只查一条，代价可以接受；
 * 气泡下方那一行常驻的「N人已读」走的是批量接口，两条路径不要混用。
 */
@Composable
internal fun ReadReceiptSheetContent(serverMsgId: Long) {
    var loading by remember(serverMsgId) { mutableStateOf(true) }
    var readers by remember(serverMsgId) { mutableStateOf<List<ReadMember>>(emptyList()) }

    LaunchedEffect(serverMsgId) {
        val info = runCatching { AuroraApi.getMessageInfo(serverMsgId) }.getOrNull()?.data
        if (info != null) {
            val arr = info.optJSONArray("read_users")
            val nowSec = System.currentTimeMillis() / 1000
            val list = ArrayList<ReadMember>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val at = o.optLong("read_at", 0L)
                    list.add(
                        ReadMember(
                            userId = o.optLong("user_id", 0L),
                            name = o.optString("nickname", "用户").ifBlank { "用户" },
                            readAtText = if (at > 0) ChatTimeFormat.formatLabel(at, nowSec) else ""
                        )
                    )
                }
            }
            readers = list
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text(
            text = "${readers.size}人已读",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = com.aurora.chat.ui.theme.appTextPrimary()
        )
        Spacer(Modifier.height(10.dp))

        when {
            loading -> Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp), color = com.aurora.chat.ui.theme.appTextHint())
            }

            readers.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有人读", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }

            else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(readers, key = { it.userId }) { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            userId = m.userId,
                            userName = m.name,
                            size = 30.dp,
                            modifier = Modifier.size(30.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = m.name,
                            fontSize = 14.sp,
                            color = com.aurora.chat.ui.theme.appTextPrimary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(m.readAtText, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                }
            }
        }
    }
}
