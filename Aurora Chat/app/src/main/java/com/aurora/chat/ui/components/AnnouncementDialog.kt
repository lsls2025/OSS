package com.aurora.chat.ui.components
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.activity.compose.BackHandler
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
import com.aurora.chat.ui.text.UrlText
import org.json.JSONObject
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button

/**
 * 系统公告。
 *
 * 经典模式：完全保持原来的白色卡片。
 * 液态模式：玻璃材质卡片，折射公告自己的背景图。
 *
 * 关于折射源的选择（重要）：
 * 玻璃必须折射「有色彩/有内容」的图层，若折射白色聊天列表，则白玻璃叠白内容 = 一片灰白塑料感。
 * 且真 Dialog 是独立窗口，graphicsLayer 无法跨窗口捕获主界面内容。
 * 因此液态模式在本覆盖层内先铺一张背景图并用 layerBackdrop 捕获，
 * 让玻璃折射这张彩色背景图 —— 与 Kyant0 demo 折射壁纸的原理一致。
 * 卡片保留 demo 的半透明白 surface（非纯透明），保证正文可读。
 */
@Composable
fun SystemAnnouncementDialog(
    announcement: JSONObject,
    onDismiss: (todayNotAgain: Boolean) -> Unit,
    useGlass: Boolean = false
) {
    val title = announcement.optString("title", "公告")
    val content = announcement.optString("content", "")
    val source = announcement.optString("source", "拾光工作室")
    val date = announcement.optString("date", "")
    val supplement = announcement.optString("supplement", "")
    var notAgain by remember { mutableStateOf(false) }

    // 保留原行为：公告为强制提醒，返回键不关闭
    BackHandler(enabled = true) { }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center
    ) {
        // 遮罩：经典弹窗观感
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x60000000))
        ) {}

        // ── 卡片容器 ──
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .heightIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .clickable(enabled = false) { }
                    .padding(horizontal = 28.dp, vertical = 28.dp)
            ) {
            // 标题（靠左）
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 正文（可滑动）
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // 正文：用 UrlText 渲染，解析到的域名会高亮，点击弹「应用内打开 / 浏览器打开」
                UrlText(
                    text = content,
                    fontSize = 16.sp,
                    defaultColor = com.aurora.chat.ui.theme.appTextSecondary(),
                    lineHeight = 28.sp,
                    clickable = true
                )

                if (supplement.isNotBlank()) {
                    Spacer(Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 玻璃卡片上不再用不透明白底（会变成突兀白斑），改为极淡半透明区分
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .padding(14.dp)
                    ) {
                        // 附言：同样支持域名高亮与点击
                        UrlText(
                            text = "附：$supplement",
                            fontSize = 14.sp,
                            defaultColor = com.aurora.chat.ui.theme.appTextSecondary(),
                            lineHeight = 22.sp,
                            clickable = true
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
                        color = com.aurora.chat.ui.theme.appTextHint()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = date,
                        fontSize = 14.sp,
                        color = com.aurora.chat.ui.theme.appTextHint()
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
                        color = com.aurora.chat.ui.theme.appTextSecondary()
                    )
                }
                // 确定按钮（靠右）：经典文字按钮
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
