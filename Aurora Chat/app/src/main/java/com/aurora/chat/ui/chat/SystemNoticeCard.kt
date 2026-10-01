package com.aurora.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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

// ==================== 系统通知：订单通过大卡片 ====================

/** 检测是否为「订单通过」类系统通知卡片 */
fun isOrderNotice(text: String): Boolean {
    return text.trimStart().startsWith("系统订单通知")
}

data class OrderNoticeData(
    val title: String,
    val desc: String,
    val tag: String,
    val orderId: Long = 0
)

/** 解析订单通知卡片文本（格式见后端 handleApproveOrder 推送） */
fun parseOrderNotice(text: String): OrderNoticeData? {
    return try {
        val cleaned = text.trim()
        if (!cleaned.startsWith("系统订单通知")) return null
        val lines = cleaned.split("\n").map { it.trim() }
        fun get(key: String): String {
            val l = lines.firstOrNull { it.startsWith("$key=") } ?: return ""
            return l.removePrefix("$key=").trim()
        }
        val title = get("title").ifEmpty { "您的订单已通过" }
        val desc = get("desc")
        val tag = get("tag")
        val orderId = get("order_id").toLongOrNull() ?: 0
        if (desc.isEmpty()) return null
        OrderNoticeData(title, desc, tag, orderId)
    } catch (_: Exception) { null }
}

/** 把结构化数据格式化为可本地存储的订单通知文本 */
fun formatOrderNotice(title: String, desc: String, tag: String, orderId: Long = 0): String {
    return buildString {
        appendLine("系统订单通知")
        appendLine("title=$title")
        appendLine("desc=$desc")
        appendLine("tag=$tag")
        appendLine("order_id=$orderId")
    }
}

/**
 * 订单通过系统通知大卡片：居中、尺寸偏大、带渐变底色（类似气泡），
 * 顶部 ✅ 图标，中间标题与说明，底部固定显示「查看订单」按钮（跳转订单详情）。
 */
@Composable
fun OrderNoticeCard(data: OrderNoticeData, onViewOrder: (Long) -> Unit = {}) {
    Card(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFDCE4F4), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F4FC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = data.title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2329),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = data.desc,
                fontSize = 14.sp,
                color = Color(0xFF5B6472),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            // 有关联订单：固定显示「查看订单」按钮，点击跳转详情
            if (data.orderId > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF1E40AF))
                        .clickable { onViewOrder(data.orderId) }
                        .padding(horizontal = 18.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "查看订单",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            } else if (data.tag.isNotBlank()) {
                // 无关联订单（如系统广播）：保留原标签展示
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFFE6EDFB))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = data.tag,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF2F6BD0)
                    )
                }
            }
        }
    }
}
