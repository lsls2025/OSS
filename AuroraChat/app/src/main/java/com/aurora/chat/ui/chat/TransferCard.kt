package com.aurora.chat.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val TransferOrange = Color(0xFFF5A623)
val TransferOrangeLight = Color(0xFFF8C77E)

private val TransferCardWidth = 196.dp

fun transferStatusText(status: String): String = when (status) {
    TRANSFER_STATUS_PENDING -> "请收款"
    TRANSFER_STATUS_CLAIMED -> "已收款"
    else -> "已过期"
}

fun transferBackgroundColor(status: String): Color =
    if (status == TRANSFER_STATUS_PENDING) TransferOrange else TransferOrangeLight

/** 聊天会话中的转账卡片 */
@Composable
fun TransferCard(data: TransferData, status: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(TransferCardWidth)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = transferBackgroundColor(status))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (status == TRANSFER_STATUS_PENDING) TransferIcon else Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        formatTransferAmount(data.amount),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(transferStatusText(status), fontSize = 12.sp, color = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("转账", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** 通知中心的收款卡片：保持原有版式与图标，不跟随聊天卡片的样式改动 */
@Composable
fun TransferNoticeCard(desc: String) {
    Card(
        modifier = Modifier
            .width(TransferCardWidth)
            .clip(RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TransferOrangeLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "转账收款",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(desc, fontSize = 12.sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 16.sp)
        }
    }
}
