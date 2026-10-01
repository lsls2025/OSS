package com.aurora.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==================== 转账卡片 ====================

/** 聊天会话中的转账卡片（群聊/私聊统一渲染，视角由 TransferData 现算） */
@Composable
fun TransferCard(data: TransferData, currentUserId: Long) {
    val label = transferDisplayLabel(data, currentUserId)
    Card(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFEBD9B0), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBF3E0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Redeem, contentDescription = null, tint = Color(0xFFD9A441), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("转账", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9C6B1E))
            }
            Spacer(Modifier.height(10.dp))
            Text(label, fontSize = 14.sp, color = Color(0xFF6B5A3A), lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text("${data.amount} token", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9C6B1E))
        }
    }
}

/** 通知中心的收款卡片 */
@Composable
fun TransferNoticeCard(desc: String) {
    Card(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFEBD9B0), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBF3E0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Redeem, contentDescription = null, tint = Color(0xFFD9A441), modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text("转账收款", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2329), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(desc, fontSize = 14.sp, color = Color(0xFF5B6472), textAlign = TextAlign.Center, lineHeight = 20.sp)
        }
    }
}
