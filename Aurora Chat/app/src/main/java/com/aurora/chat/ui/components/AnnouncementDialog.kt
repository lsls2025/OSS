package com.aurora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

@Composable
fun SystemAnnouncementDialog(
    announcement: JSONObject,
    onDismiss: (todayNotAgain: Boolean) -> Unit
) {
    val title = announcement.optString("title", "公告")
    val content = announcement.optString("content", "")
    val source = announcement.optString("source", "拾光工作室")
    val date = announcement.optString("date", "")
    val supplement = announcement.optString("supplement", "")
    var notAgain by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { onDismiss(notAgain) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x60000000))
                .clickable(enabled = false) { },
            contentAlignment = Alignment.Center
        ) {
            // 白色信件卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .heightIn(max = 520.dp)
                    .background(Color.White)
                    .clickable(enabled = false) { }
                    .padding(horizontal = 28.dp, vertical = 28.dp)
            ) {
                // 标题（靠左）
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 正文（可滑动）
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = content,
                        fontSize = 16.sp,
                        color = Color(0xFF374151),
                        lineHeight = 28.sp
                    )

                    if (supplement.isNotBlank()) {
                        Spacer(Modifier.height(24.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF9FAFB))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "附：$supplement",
                                fontSize = 14.sp,
                                color = Color(0xFF6B7280),
                                lineHeight = 22.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // 落款（仿信件格式：来源 + 日期，右对齐）
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = source,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF9CA3AF)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = date,
                            fontSize = 14.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 今日不再提示 + 确定按钮（同一行）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 复选框 + 文字（靠左）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { notAgain = !notAgain }
                    ) {
                        Checkbox(
                            checked = notAgain,
                            onCheckedChange = { notAgain = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1E40AF)),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "今日不再提示",
                            fontSize = 13.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                    // 确定按钮（靠右）
                    Text(
                        text = "确定",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E40AF),
                        modifier = Modifier
                            .clickable { onDismiss(notAgain) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
