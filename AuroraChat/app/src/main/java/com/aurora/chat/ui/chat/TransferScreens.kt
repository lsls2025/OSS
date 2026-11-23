package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.theme.AuroraPrimary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTransferTime(ts: Long): String {
    if (ts <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts * 1000))
}

@Composable
fun TransferCardWithDetail(
    data: TransferData,
    currentUserId: Long,
    fallbackCreatedAt: Long
) {
    var showDetail by remember { mutableStateOf(false) }
    val version by TransferStatusCache.version.collectAsState()
    val cached = remember(data.transferId, version) { TransferStatusCache.statusOf(data.transferId) }
    val status = cached?.first ?: data.status
    val claimedAt = cached?.second ?: data.claimedAt

    TransferCard(data = data, status = status, onClick = { showDetail = true })

    if (showDetail) {
        TransferDetailDialog(
            data = data.copy(status = status, claimedAt = claimedAt),
            status = status,
            claimedAt = claimedAt,
            currentUserId = currentUserId,
            fallbackCreatedAt = fallbackCreatedAt,
            onDismiss = { showDetail = false }
        )
    }
}

@Composable
fun TransferDetailDialog(
    data: TransferData,
    status: String,
    claimedAt: Long,
    currentUserId: Long,
    fallbackCreatedAt: Long,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var claiming by remember { mutableStateOf(false) }
    var currentStatus by remember(status) { mutableStateOf(status) }
    var currentClaimedAt by remember(claimedAt) { mutableStateOf(claimedAt) }
    var sentAt by remember(data.createdAt) { mutableStateOf(if (data.createdAt > 0L) data.createdAt else fallbackCreatedAt) }
    var recvAt by remember(claimedAt) { mutableStateOf(claimedAt) }

    LaunchedEffect(data.transferId) {
        if (data.transferId <= 0L) return@LaunchedEffect
        val r = AuroraApi.getTransferDetail(data.transferId)
        val d = r.data ?: return@LaunchedEffect
        if (!r.success) return@LaunchedEffect
        val s = d.optString("status", currentStatus)
        val ca = d.optLong("claimed_at", currentClaimedAt)
        val created = d.optLong("created_at", 0L)
        if (created > 0L) sentAt = created
        currentStatus = s
        currentClaimedAt = ca
        recvAt = ca
        TransferStatusCache.put(data.transferId, s, ca)
    }

    val isReceiver = currentUserId == data.toUserId
    val pending = currentStatus == TRANSFER_STATUS_PENDING
    val canClaim = pending && isReceiver && data.transferId > 0L && !claiming
    val buttonText = when {
        pending && isReceiver -> if (claiming) "收款中" else "收款"
        pending -> "等待收款"
        currentStatus == TRANSFER_STATUS_CLAIMED -> "已收款"
        else -> "已过期"
    }
    val buttonColor = AuroraPrimary

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // 旧的 Dialog 没有 decorFitsSystemWindows=false，状态栏区域露出系统默认灰底。
        // 与红包详情页同一套处理：白底铺到状态栏、状态栏图标切深色。
        com.aurora.chat.ui.server.SetDialogSystemBarColors(
            statusBarColor = android.graphics.Color.WHITE,
            navigationBarColor = android.graphics.Color.WHITE,
            lightStatusBars = true
        )
        Surface(Modifier.fillMaxSize(), color = com.aurora.chat.ui.theme.appSurface()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color(0xFF1F2329))
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 按用户要求去掉中间那个实心橙色圆（连同里面的旧双向箭头），
                    // 只保留金额、状态与时间。
                    // 数字与单位分开排版：数字按长度自适应字号、单位用小字号，
                    // 超大数值由 formatTransferAmountParts 缩成 K/M/B/T，不会再挤成两行。
                    val (amountNumber, amountUnit) = formatTransferAmountParts(data.amount)
                    val amountNumberSize = when {
                        amountNumber.length > 12 -> 24.sp
                        amountNumber.length > 9 -> 30.sp
                        else -> 36.sp
                    }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = amountNumber,
                            fontSize = amountNumberSize,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1F2329),
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = amountUnit,
                            fontSize = 15.sp,
                            color = com.aurora.chat.ui.theme.appTextHint(),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(transferStatusText(currentStatus), fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(28.dp))
                    val sent = formatTransferTime(sentAt)
                    if (sent.isNotEmpty()) {
                        Text("发送时间：$sent", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                    val received = formatTransferTime(recvAt)
                    if (currentStatus == TRANSFER_STATUS_CLAIMED && received.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("接收时间：$received", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }

                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 24.dp, end = 24.dp, bottom = 40.dp)
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(buttonColor)
                        .clickable(enabled = canClaim) {
                            claiming = true
                            scope.launch {
                                val r = AuroraApi.claimTransfer(data.transferId)
                                claiming = false
                                if (r.success) {
                                    val ca = r.data?.optLong("claimed_at", System.currentTimeMillis() / 1000)
                                        ?: System.currentTimeMillis() / 1000
                                    currentStatus = TRANSFER_STATUS_CLAIMED
                                    currentClaimedAt = ca
                                    recvAt = ca
                                    TransferStatusCache.put(data.transferId, TRANSFER_STATUS_CLAIMED, ca)
                                } else {
                                    Toast.makeText(ctx, r.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(buttonText, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        }
    }
}
