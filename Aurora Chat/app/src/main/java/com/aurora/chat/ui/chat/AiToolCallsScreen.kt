package com.aurora.chat.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「工具调用」全屏清单(独立全屏,绝不嵌套):
 * 列出本条 AI 消息调用的全部工具——工具名 / 成败状态 / 发起时刻(精确到毫秒)。
 * 数据源为消息内持久化的 aiToolStages 列表,由 AiChatManager 在每次工具调用时采集。
 */
@Composable
fun AiToolCallsScreen(records: List<ChatMsg.ToolStageRecord>, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // 毫秒级时间戳格式化(统一在 remember 里建一次,避免列表项反复创建)
    val tsFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF3F4F6))
            // 消费空区点击,防止穿透到下层 AI 聊天界面
            .clickable(interactionSource = noRipple, indication = null) {}
    ) {
        // ── 顶栏:白色背景先铺满(含状态栏区域),再让出状态栏高度 ──
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color(0xFF1F2937))
            }
            Column(Modifier.weight(1f)) {
                Text("工具调用", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Text(
                    "共 ${records.size} 次工具调用",
                    fontSize = 11.sp, color = Color(0xFF9CA3AF)
                )
            }
            IconButton(onClick = {
                val copy = buildString {
                    records.forEach { r ->
                        append("[${if (r.state == 1) "成功" else "失败"}] ${r.tool}")
                        if (r.ts > 0) append("  ${tsFmt.format(Date(r.ts))}")
                        append("\n")
                    }
                }
                try {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AI 工具调用清单", copy))
                    Toast.makeText(ctx, "已复制调用清单", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }) {
                Text("复制", fontSize = 13.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium)
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有工具调用记录", fontSize = 13.sp, color = Color(0xFF9CA3AF))
            }
            return@Column
        }

        // ── 调用清单 ──
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records.size) { i ->
                val r = records[i]
                val ok = r.state == 1
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(12.dp)
                ) {
                    // 第一行:序号 + 工具名 + 状态徽标
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$i",
                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF9CA3AF),
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFFF3F4F6))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            r.tool,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFF1F2937),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (ok) "调用成功" else "调用失败",
                            fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (ok) Color(0xFF15803D) else Color(0xFFB91C1C),
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (ok) Color(0x1A16A34A) else Color(0x1ADC2626))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // 第二行:调用发起时刻(精确到毫秒)
                    Text(
                        if (r.ts > 0) "调用时间  " + tsFmt.format(Date(r.ts)) else "调用时间  —(旧记录无时间戳)",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7280)
                    )
                }
            }
        }
    }
}
