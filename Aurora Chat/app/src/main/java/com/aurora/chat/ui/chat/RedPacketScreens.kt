package com.aurora.chat.ui.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.server.SetDialogSystemBarColors
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.aurora.chat.ui.viewmodel.ChatViewModel
import com.aurora.chat.data.api.AuroraApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.aurora.chat.ui.chat.ChatMsg

// ==================== 红包视觉 ====================

private val redPacketGradient = Brush.verticalGradient(
    listOf(Color(0xFFF4624C), Color(0xFFE8402F))
)
private val redPacketGray = Brush.verticalGradient(
    listOf(Color(0xFFBDBDBD), Color(0xFF9E9E9E))
)

private fun formatClaimTime(ts: Long): String {
    if (ts <= 0L) return ""
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ts * 1000L
    val m = c.get(java.util.Calendar.MONTH) + 1
    val d = c.get(java.util.Calendar.DAY_OF_MONTH)
    val h = c.get(java.util.Calendar.HOUR_OF_DAY)
    val min = c.get(java.util.Calendar.MINUTE)
    return String.format("%02d-%02d %02d:%02d", m, d, h, min)
}

/** 内联红包卡片（不居中，由调用方用 Row 对齐到头像侧） */
@Composable
fun RedPacketCard(
    data: RedPacketData,
    status: RedPacketStatus?,
    isMine: Boolean,
    onClick: () -> Unit
) {
    val nowSec = System.currentTimeMillis() / 1000
    val expired = (status?.status == "expired") || (status == null && nowSec >= data.expireAt)
    val footer = when {
        expired -> "已过期"
        status != null -> "已领取 ${status.claimedCount}/${status.totalCount} 份"
        else -> "领取红包"
    }
    Card(
        modifier = Modifier
            .widthIn(min = 180.dp, max = 216.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (expired) redPacketGray else redPacketGradient)
                .padding(14.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Redeem, contentDescription = null,
                        tint = Color(0xFFFFE0B2), modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${data.fromName}的红包",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    data.greeting.ifEmpty { "恭喜发财，大吉大利" },
                    fontSize = 14.sp, color = Color(0xFFFFF3E0), lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.height(8.dp))
                Text(
                    footer,
                    fontSize = 12.sp,
                    color = if (expired) Color(0xFFEEEEEE) else Color(0xFFFFE0B2)
                )
            }
        }
    }
}

/** 内联卡片 + 自动拉取实时状态（已领取X/Y、过期） */
@Composable
fun RedPacketCardWithStatus(
    data: RedPacketData,
    isMine: Boolean,
    statusMap: SnapshotStateMap<Long, RedPacketStatus>,
    onClick: () -> Unit
) {
    val status = statusMap[data.packetId]
    LaunchedEffect(data.packetId) {
        if (status == null) {
            try {
                val r = AuroraApi.getRedPacketDetail(data.packetId)
                if (r.success && r.data != null) {
                    redPacketStatusFromJson(r.data!!)?.let { statusMap[data.packetId] = it }
                }
            } catch (_: Exception) { }
        }
    }
    RedPacketCard(data = data, status = status, isMine = isMine, onClick = onClick)
}

// ==================== 发红包弹窗 ====================

@Composable
fun RedPacketSendDialog(
    friendId: Long,
    currentUserId: Long,
    currentUserName: String,
    friendName: String,
    messages: SnapshotStateList<IMessageRef>,
    ctx: Context,
    onDismiss: () -> Unit
) {
    val isGroup = isGroupChat(friendId)
    var countText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf(-1L) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val r = AuroraApi.tokenBalance()
            // tokenBalance 返回的 r.data 为完整响应（含 code/message/data），
            // token_balance 嵌套在 data 内，需先解包，否则取到默认值 0 误判为余额不足。
            val d = if (r.data?.has("data") == true) r.data!!.optJSONObject("data") ?: r.data else r.data
            balance = d?.optLong("token_balance") ?: -1L
        } catch (_: Exception) { }
    }

    val count = countText.toIntOrNull() ?: 0
    val amount = amountText.toLongOrNull() ?: 0
    val perOk = count > 0 && amount >= count
    val balanceOk = balance < 0 || amount <= balance
    val canSend = perOk && balanceOk && amount > 0

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(20.dp)
        ) {
            Column {
                Text("发红包", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isGroup) "发到群聊（群内成员均可抢）" else "发给 $friendName",
                    fontSize = 13.sp, color = Color(0xFF6B7280)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "当前余额：${if (balance < 0) "加载中..." else "$balance token"}",
                    fontSize = 13.sp, color = Color(0xFF1E40AF)
                )
                Spacer(Modifier.height(14.dp))

                Text("红包个数", fontSize = 13.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = countText,
                    onValueChange = { countText = it.filter { c -> c.isDigit() }.take(4) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F6)).padding(12.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Color(0xFF1F2937)),
                    decorationBox = { inner -> if (countText.isEmpty()) Text("可被多少人领取", fontSize = 14.sp, color = Color(0xFF9CA3AF)) else inner() }
                )
                Spacer(Modifier.height(12.dp))

                Text("红包总额（token）", fontSize = 13.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() }.take(12) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F6)).padding(12.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Color(0xFF1F2937)),
                    decorationBox = { inner -> if (amountText.isEmpty()) Text("每人至少1toke", fontSize = 14.sp, color = Color(0xFF9CA3AF)) else inner() }
                )
                Spacer(Modifier.height(12.dp))

                Text("祝福语（可选）", fontSize = 13.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = greeting,
                    onValueChange = { greeting = it.take(30) },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F6)).padding(12.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = Color(0xFF1F2937)),
                    decorationBox = { inner -> if (greeting.isEmpty()) Text("恭喜发财，大吉大利", fontSize = 14.sp, color = Color(0xFF9CA3AF)) else inner() }
                )

                if (!perOk && (countText.isNotEmpty() || amountText.isNotEmpty())) {
                    Spacer(Modifier.height(8.dp))
                    Text("每份至少 1 token，个数不能超过总额", fontSize = 12.sp, color = Color(0xFFDC2626))
                }
                if (!balanceOk) {
                    Spacer(Modifier.height(8.dp))
                    Text("余额不足", fontSize = 12.sp, color = Color(0xFFDC2626))
                }

                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (canSend) Color(0xFFE8402F) else Color(0xFFD1D5DB))
                        .clickable(enabled = canSend && !loading) {
                            scope.launch {
                                loading = true
                                try {
                                    val greet = greeting.ifEmpty { "恭喜发财，大吉大利" }
                                    val r = AuroraApi.createRedPacket(friendId, count, amount, greet)
                                    if (r.success) {
                                        val pid = r.data?.optLong("packet_id") ?: 0L
                                        val now = System.currentTimeMillis() / 1000
                                        val expire = now + 24 * 3600
                                        val senderName = currentUserName.ifEmpty {
                                            ctx.getSharedPreferences("aurora_login", Context.MODE_PRIVATE).getString("username", "") ?: ""
                                        }
                                        val content = buildRedPacketContent(pid, currentUserId, senderName, amount, count, greet, now, expire)
                                        messages.add(
                                            ChatMsg.create(
                                                text = content, isMine = true, fromUserId = currentUserId,
                                                isNew = true, mediaType = "redpacket", createdAt = now
                                            )
                                        )
                                        ChatViewModel.updateConversationPreview(friendId, "[红包]", now)
                                        ChatViewModel.notifyNewMessage()
                                        Toast.makeText(ctx, "红包发送成功", Toast.LENGTH_SHORT).show()
                                        onDismiss()
                                    } else {
                                        Toast.makeText(ctx, r.message, Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally { loading = false }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (loading) "发送中..." else "发红包", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        }
    }
}

// ==================== 红包全屏领取界面 ====================

@Composable
fun RedPacketDetailDialog(
    packetId: Long,
    currentUserId: Long,
    statusMap: SnapshotStateMap<Long, RedPacketStatus>,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var grabbing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val r = AuroraApi.getRedPacketDetail(packetId)
                    if (r.success && r.data != null) {
                        detail = r.data
                        redPacketStatusFromJson(r.data!!)?.let { statusMap[packetId] = it }
                    } else {
                        error = r.message
                    }
                } catch (e: Exception) {
                    error = e.message ?: "加载失败"
                } finally {
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val packet = detail?.optJSONObject("packet")
    val status = detail?.optString("status", "active") ?: "active"
    val myClaimed = detail?.optLong("my_claimed") ?: 0L
    val expired = status == "expired"
    val alreadyGrabbed = myClaimed > 0L
    val senderName = packet?.optString("sender_name") ?: ""
    val greeting = packet?.optString("greeting") ?: "恭喜发财，大吉大利"
    val total = packet?.optLong("total") ?: 0L
    val count = packet?.optInt("count") ?: 0
    val claimsArr = detail?.optJSONArray("claims")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // 清除 Dialog 默认灰色状态栏蒙层，将状态栏染成红包渐变顶部色 + 浅色图标
        SetDialogSystemBarColors(
            statusBarColor = 0xFFF4624C.toInt(),
            navigationBarColor = 0xFFF4624C.toInt(),
            lightStatusBars = false
        )
        Box(Modifier.fillMaxSize().background(redPacketGradient)) {
            // 顶栏（固定顶部）
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                Text("红包", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("✕", fontSize = 20.sp, color = Color.White, modifier = Modifier.clickable { onDismiss() })
            }

            // 主内容：整体居中并略微偏下（不再被领取名单顶到底部）
            Box(
                Modifier.fillMaxSize().padding(top = 80.dp, bottom = 28.dp, start = 24.dp, end = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.offset(y = 24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 顶部金圆徽章（经典红包外观）
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(Color(0xFFFFE0B2)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Redeem, contentDescription = null,
                            tint = Color(0xFFE8402F), modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(senderName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("的红包", fontSize = 14.sp, color = Color(0xFFFFE0B2))
                    Spacer(Modifier.height(10.dp))
                    Text(greeting, fontSize = 15.sp, color = Color(0xFFFFF3E0))
                    Spacer(Modifier.height(22.dp))
                    // 中部结果
                    when {
                        loading -> Text("加载中...", fontSize = 15.sp, color = Color(0xFFFFE0B2))
                        alreadyGrabbed -> {
                            Text("已存入你的余额", fontSize = 13.sp, color = Color(0xFFFFE0B2))
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$myClaimed", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("token", fontSize = 16.sp, color = Color(0xFFFFE0B2))
                            }
                        }
                        expired -> Text("红包已过期", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        else -> Text("点击下方按钮抢红包", fontSize = 15.sp, color = Color(0xFFFFE0B2))
                    }

                    Spacer(Modifier.height(16.dp))
                    // 领取名单（限制高度，避免把按钮顶到底部）
                    if (claimsArr != null && claimsArr.length() > 0) {
                        val claimList = (0 until claimsArr.length()).mapNotNull { claimsArr.optJSONObject(it) }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x2AFFFFFF))
                                .padding(8.dp)
                        ) {
                            items(claimList) { c ->
                                if (c != null) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        UserAvatar(
                                            userId = c.optLong("user_id", 0L),
                                            userName = c.optString("username", "用户"),
                                            size = 34.dp
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(c.optString("username", "用户"), fontSize = 14.sp, color = Color.White)
                                            val t = c.optLong("claimed_at", 0L)
                                            if (t > 0) Text(formatClaimTime(t), fontSize = 11.sp, color = Color(0xFFFFE0B2))
                                        }
                                        Text("${c.optLong("amount", 0L)} token", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    } else if (!loading) {
                        Text("还没有人领取", fontSize = 14.sp, color = Color(0xFFFFE0B2))
                        Spacer(Modifier.height(16.dp))
                    }

                    // 底部按钮
                    Box(
                        Modifier.fillMaxWidth().height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                when {
                                    alreadyGrabbed || expired -> Color(0x55FFFFFF)
                                    else -> Color(0xFFFFD54F)
                                }
                            )
                            .clickable(enabled = !alreadyGrabbed && !expired && !grabbing, onClick = {
                                scope.launch {
                                    grabbing = true
                                    try {
                                        val r = AuroraApi.grabRedPacket(packetId)
                                        if (r.success) {
                                            load()
                                        } else {
                                            error = r.message
                                            Toast.makeText(ctx, r.message, Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "领取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally { grabbing = false }
                                }
                            }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when {
                                grabbing -> "抢红包中..."
                                alreadyGrabbed -> "你已领取 $myClaimed token"
                                expired -> "红包已过期"
                                else -> "抢红包"
                            },
                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            color = if (alreadyGrabbed || expired) Color.White else Color(0xFF8A5A00)
                        )
                    }
                }
            }
        }
    }
}
