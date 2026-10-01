package com.aurora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

enum class RequestStatus { PENDING, ACCEPTED, REJECTED }

data class FriendRequest(
    val id: Long = 0,
    val name: String,
    val status: RequestStatus = RequestStatus.PENDING,
    val time: Long = System.currentTimeMillis(),
    val signature: String = "这个人很懒，什么都没写…",
    val greeting: String = "",
    val fromUserId: Long = 0,
    val toUserId: Long = 0,
    val isOutgoing: Boolean = false
)

@Composable
fun FriendRequestDialog(
    requests: List<FriendRequest>,
    onAccept: (Int) -> Unit,
    onReject: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
        ) {
            Text("好友申请", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937), modifier = Modifier.padding(20.dp))

            if (requests.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(20.dp),
                    contentAlignment = Alignment.Center) {
                    Text("暂无好友申请", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(requests.withIndex().toList(), key = { it.index }) { (index, req) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(
                                userId = req.fromUserId,
                                userName = req.name,
                                size = 40.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(req.name, fontSize = 15.sp, color = Color(0xFF1F2937),
                                modifier = Modifier.weight(1f))
                            Text("拒绝", fontSize = 13.sp, color = Color(0xFF9CA3AF),
                                modifier = Modifier.clickable { onReject(index) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp))
                            Text("同意", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = Color(0xFF1E40AF),
                                modifier = Modifier.clickable { onAccept(index) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                    modifier = Modifier.clickable { onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
                Text("确定", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable { onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}
