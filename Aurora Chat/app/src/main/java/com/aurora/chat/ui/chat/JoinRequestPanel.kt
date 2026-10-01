package com.aurora.chat.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.chat.ChatMsg
import kotlinx.coroutines.launch

@Composable
fun JoinRequestListPanel(
    requests: org.json.JSONArray,
    groupId: Long,
    onBack: () -> Unit,
    onReviewed: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 当前查看详情的申请
    var detailRequest by remember { mutableStateOf<org.json.JSONObject?>(null) }

    // 容错：过滤掉无法定位（user_id 失效/缺失）的申请，保留其余有效申请；
    // 若全部失效或不存任何有效申请，则默认取消（自动关闭面板），避免卡在中间状态。
    val validRequests = remember(requests) {
        (0 until requests.length()).mapNotNull { i ->
            val o = requests.optJSONObject(i)
            if (o != null && o.optLong("user_id", 0) > 0) o else null
        }
    }
    LaunchedEffect(validRequests.size) {
        if (validRequests.isEmpty()) onBack()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text("入群申请", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            if (validRequests.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无有效申请", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(validRequests.size) { i ->
                        val req = validRequests[i]
                        val uid = req.optLong("user_id")
                        val uname = req.optString("username", "用户$uid")
                        val reason = req.optString("reason", "")
                        val status = req.optString("status", "pending")
                        val statusText = when (status) {
                            "approved" -> "已通过"
                            "rejected" -> "已拒绝"
                            "ignored" -> "已忽略"
                            else -> ""
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(userId = uid, userName = uname, size = 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(uname, fontSize = 14.sp, color = Color(0xFF1F2937), fontWeight = FontWeight.Medium)
                                Text("ID: $uid", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                                if (reason.isNotBlank()) Text("理由: $reason", fontSize = 12.sp, color = Color(0xFF9CA3AF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (status == "pending") {
                                Text("查看", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFEFF6FF))
                                        .clickable { detailRequest = req }
                                        .padding(horizontal = 12.dp, vertical = 6.dp))
                            } else {
                                Text(statusText, fontSize = 13.sp,
                                    color = if (status == "approved") Color(0xFF059669) else if (status == "rejected") Color(0xFFDC2626) else Color(0xFF9CA3AF),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (status == "approved") Color(0xFFECFDF5) else if (status == "rejected") Color(0xFFFEF2F2) else Color(0xFFF3F4F6))
                                        .clickable { detailRequest = req }
                                        .padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
                    }
                }
            }
        }

        // 详情面板（覆盖在列表之上，从右侧滑入）
        AnimatedVisibility(
            visible = detailRequest != null,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            detailRequest?.let { req ->
                JoinRequestDetailPanel(
                    request = req,
                    groupId = groupId,
                    onBack = { detailRequest = null },
                    onReviewed = {
                        detailRequest = null
                        onReviewed()
                    }
                )
            }
        }
    }
}

// ── 入群申请详情面板（从右侧滑入，显示原因 + 同意/拒绝/忽略） ──
@Composable
fun JoinRequestDetailPanel(
    request: org.json.JSONObject,
    groupId: Long,
    onBack: () -> Unit,
    onReviewed: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val reqId = request.optLong("id")
    val uid = request.optLong("user_id")
    val uname = request.optString("username", "用户$uid")
    val reason = request.optString("reason", "")
    val status = request.optString("status", "pending")
    val createdAt = request.optLong("created_at", 0)
    val timeText = if (createdAt > 0) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(createdAt * 1000))
    } else ""

    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
            }
            Spacer(Modifier.width(12.dp))
            Text("申请详情", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
        Spacer(Modifier.height(24.dp))
        // 用户信息
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UserAvatar(userId = uid, userName = uname, size = 64.dp)
            Spacer(Modifier.height(10.dp))
            Text(uname, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
            Text("ID: $uid", fontSize = 13.sp, color = Color(0xFF9CA3AF))
            if (timeText.isNotBlank()) { Text("申请时间: $timeText", fontSize = 13.sp, color = Color(0xFF9CA3AF)) }
            if (status != "pending") {
                Spacer(Modifier.height(4.dp))
                val statusText = when (status) {
                    "approved" -> "已通过 ✓"
                    "rejected" -> "已拒绝 ✗"
                    "ignored" -> "已忽略"
                    else -> status
                }
                val statusColor = when (status) {
                    "approved" -> Color(0xFF059669)
                    "rejected" -> Color(0xFFDC2626)
                    else -> Color(0xFF9CA3AF)
                }
                Text(statusText, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = statusColor)
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
        Spacer(Modifier.height(16.dp))
        // 入群原因
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("入群原因", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF9FAFB)).padding(14.dp)) {
                Text(if (reason.isNotBlank()) reason else "未填写原因", fontSize = 14.sp, color = Color(0xFF1F2937), lineHeight = 22.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        if (status == "pending") {
            // 底部按钮：忽略、拒绝、同意
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF9CA3AF))
                    .clickable {
                        scope.launch {
                            try {
                                val r = com.aurora.chat.data.api.AuroraApi.ignoreJoinRequest(reqId)
                                if (r.success) {
                                    Toast.makeText(ctx, "已忽略", Toast.LENGTH_SHORT).show()
                                    onReviewed()
                                }
                            } catch (_: Exception) {}
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text("忽略", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFDC2626))
                    .clickable {
                        scope.launch {
                            try {
                                ChatRepository.reviewJoinRequest(reqId, false)
                                Toast.makeText(ctx, "已拒绝 $uname 的入群申请", Toast.LENGTH_SHORT).show()
                                onReviewed()
                            } catch (_: Exception) {}
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text("拒绝", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E40AF))
                    .clickable {
                        scope.launch {
                            try {
                                ChatRepository.reviewJoinRequest(reqId, true)
                                Toast.makeText(ctx, "已同意 $uname 的入群申请", Toast.LENGTH_SHORT).show()
                                onReviewed()
                            } catch (_: Exception) {}
                        }
                    }, contentAlignment = Alignment.Center) {
                    Text("同意", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        } else {
            // 已处理的申请，显示返回按钮
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                Box(Modifier.width(200.dp).height(48.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE5E7EB))
                    .clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("返回列表", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                }
            }
        }
    }
}

