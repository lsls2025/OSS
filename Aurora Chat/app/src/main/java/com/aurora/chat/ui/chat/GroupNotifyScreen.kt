package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.GroupNoticeItem
import com.aurora.chat.data.local.GroupNotices
import com.aurora.chat.data.local.GroupNotifyPrefs
import com.aurora.chat.data.local.GroupNotifySettings
import kotlinx.coroutines.launch

/**
 * 群通知全屏设置界面（群设置 →「群通知方式」进入，从右向左切入）。
 *
 * 两种互斥方式：
 *  1. 以群身份通知 —— 提示以群的名义发出，成员名带 @，可点击查看资料
 *  2. 以系统广播通知 —— 灰色居中提示，不带 @，不可点击
 *
 * 下方为逐项开关，关闭后服务端不再写入对应提示（真实生效，不是本地隐藏）。
 */
@Composable
fun GroupNotifyScreen(
    groupId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember(groupId) { mutableStateOf(GroupNotifyPrefs.get(groupId)) }

    // 进入即拉一次服务端设置，避免多设备不同步
    LaunchedEffect(groupId) {
        GroupNotifyPrefs.load(groupId)?.let { settings = it }
    }

    fun persist(next: GroupNotifySettings) {
        val prev = settings
        settings = next
        GroupNotifyPrefs.put(groupId, next)
        scope.launch {
            val r = AuroraApi.saveGroupNotifySettings(groupId, next.mode, next.disabled.toList())
            if (!r.success) {
                settings = prev
                GroupNotifyPrefs.put(groupId, prev)
                Toast.makeText(context, "保存失败：${r.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶部栏：返回 / 标题 / 右侧留白（与群成员页一致） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "群通知方式",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(28.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp)
            ) {
                // ── 通知方式 ──
                SectionLabel("通知方式", topPadding = 16.dp)
                ModeOptionCard(
                    title = "以群身份通知",
                    desc = "提示以群的名义发出，成员名带 @，点击可查看资料",
                    sample = "@群主 将 @小星 移出了群聊",
                    selected = settings.isGroupIdentity,
                    onClick = {
                        if (!settings.isGroupIdentity) {
                            persist(settings.copy(mode = GroupNotifySettings.MODE_GROUP))
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
                ModeOptionCard(
                    title = "以系统广播通知",
                    desc = "灰色系统提示，不带 @，不可点击",
                    sample = "群主 将 小星 移出了群聊",
                    selected = !settings.isGroupIdentity,
                    onClick = {
                        if (settings.isGroupIdentity) {
                            persist(settings.copy(mode = GroupNotifySettings.MODE_BROADCAST))
                        }
                    }
                )
                Text(
                    "两种方式互斥，只能选一种；切换后新产生的提示立即按新方式发出。",
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )

                // ── 提示项目 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "提示项目",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = com.aurora.chat.ui.theme.appTextSecondary(),
                        modifier = Modifier.weight(1f)
                    )
                    val allOn = settings.disabled.isEmpty()
                    Text(
                        if (allOn) "全部关闭" else "全部开启",
                        fontSize = 13.sp,
                        color = Color(0xFF1E40AF),
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                persist(
                                    if (allOn) settings.copy(disabled = GroupNotices.items.map { it.key }.toSet())
                                    else settings.copy(disabled = emptySet())
                                )
                            }
                            .padding(vertical = 4.dp, horizontal = 2.dp)
                    )
                }

                GroupNotices.categories().forEach { category ->
                    Text(
                        category,
                        fontSize = 12.sp,
                        color = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp)
                    )
                    GroupNotices.itemsOf(category).forEach { item ->
                        NoticeSwitchRow(
                            item = item,
                            mode = settings.mode,
                            checked = settings.enabled(item.key),
                            onCheckedChange = { on ->
                                val next = if (on) settings.disabled - item.key else settings.disabled + item.key
                                persist(settings.copy(disabled = next))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, topPadding: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = com.aurora.chat.ui.theme.appTextSecondary(),
        modifier = Modifier.padding(start = 20.dp, top = topPadding, bottom = 8.dp)
    )
}

@Composable
private fun ModeOptionCard(
    title: String,
    desc: String,
    sample: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appDivider()
    val bgColor = if (selected) Color(0xFFF3F7FF) else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(bgColor, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 单选圆点
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .border(1.5.dp, if (selected) Color(0xFF1E40AF) else Color(0xFFD1D5DB), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(9.dp).background(Color(0xFF1E40AF), CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            Spacer(Modifier.height(3.dp))
            Text(desc, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), lineHeight = 17.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                sample,
                fontSize = 12.sp,
                color = if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier
                    .background(
                        if (selected) Color(0xFFE6EEFF) else Color(0xFFF5F6F8),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun NoticeSwitchRow(
    item: GroupNoticeItem,
    mode: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(com.aurora.chat.ui.theme.appSurface())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
            .padding(start = 20.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 15.sp,
                color = if (checked) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextHint()
            )
            Spacer(Modifier.height(2.dp))
            Text(
                GroupNotices.sampleOf(item, mode),
                fontSize = 12.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                maxLines = 1
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF1E40AF),
                checkedBorderColor = Color(0xFF1E40AF),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = com.aurora.chat.ui.theme.appDivider(),
                uncheckedBorderColor = com.aurora.chat.ui.theme.appDivider()
            )
        )
    }
}
