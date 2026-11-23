package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors
import com.aurora.chat.ui.theme.ActiveAppColors

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val UhBlue = Color(0xFF1E40AF)
private val UhLightBlue = Color(0xFF93C5FD)
private val UhBg get() = com.aurora.chat.ui.theme.appSurface()
private val UhCard get() = com.aurora.chat.ui.theme.appCardBg()
private val UhBorder get() = ActiveAppColors.divider
private val UhText get() = ActiveAppColors.textPrimary
private val UhSub get() = ActiveAppColors.textSecondary

/**
 * Token 用量「近 30 天」全屏面板(从右侧滑入)。
 * 数据来自 AiChatManager.getUsageHistory():已配置按用户持久化,超过 30 天的记录在读写时静默清理,无任何提示。
 */
@Composable
fun UsageHistoryScreen(userId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }
    val todayStr = remember { sdf.format(Date()) }

    val history = remember { AiChatManager.getUsageHistory(ctx, userId) }
    val map = remember(history) { history.toMap() }
    // 连续 30 天序列(旧→新),无数据的天补 0,保证柱状图连续
    val points = remember(todayStr) {
        (29 downTo 0).map { off ->
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -off) }
            val d = sdf.format(c.time)
            d to (map[d] ?: 0L)
        }
    }
    val todayUsed = map[todayStr] ?: 0L
    val total30 = points.sumOf { it.second }
    val maxV = (points.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)

    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(UhBg)) {
        // 顶栏(固定)
        Row(
            Modifier.fillMaxWidth().background(UhCard).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.ArrowBack, null, Modifier.size(22.dp), tint = UhText) }
            Spacer(Modifier.width(8.dp))
            Text("Token 用量 · 近 30 天", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = UhText)
        }
        HorizontalDivider(thickness = 1.dp, color = UhBorder)

        // 内容(可滚动)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            // 统计卡
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("今日已用", fmtTokens(todayUsed), Modifier.weight(1f))
                StatCard("近 30 天总计", fmtTokens(total30), Modifier.weight(1f))
            }
            // 柱状图
            Text("每日用量", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = UhSub,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(UhCard).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, UhBorder, RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().height(176.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    points.forEachIndexed { idx, (d, t) ->
                        val ratio = if (maxV > 0) t.toFloat() / maxV else 0f
                        val h = (ratio * 128f).dp
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // 仅今天与每 5 天标一个刻度(只用「日」两位,避免文字撑宽槽位导致整图错位/重叠)
                            if (d == todayStr) Text("今", fontSize = 9.sp, color = UhSub, modifier = Modifier.padding(bottom = 3.dp))
                            else if (idx % 5 == 0) Text(d.takeLast(2), fontSize = 9.sp, color = UhSub, modifier = Modifier.padding(bottom = 3.dp))
                            Box(
                                Modifier.width(7.dp)
                                    .height(if (t > 0) h.coerceAtLeast(3.dp) else 0.dp)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(if (d == todayStr) UhBlue else UhLightBlue)
                            )
                        }
                    }
                }
            }
            // 明细列表
            Text("明细", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = UhSub,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .background(UhCard).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, UhBorder, RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    points.reversed().forEachIndexed { i, (d, t) ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(if (d == todayStr) "今天" else d.substring(5), fontSize = 13.sp, color = UhText)
                                Text(weekday(d), fontSize = 11.sp, color = UhSub)
                            }
                            Text(fmtTokens(t), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                color = if (t > 0) UhText else UhSub)
                        }
                        if (i < points.lastIndex) HorizontalDivider(thickness = 1.dp, color = UhBg)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier) {
    Column(
        modifier.background(UhCard).clip(RoundedCornerShape(12.dp))
            .border(1.dp, UhBorder, RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Text(title, fontSize = 11.sp, color = UhSub)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = UhBlue)
    }
}

/** 用量数字格式化:K / M,无消耗显示 0 */
private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> String.format(Locale.ROOT, "%.1fK", n / 1000.0)
    else -> n.toString()
}

/** yyyy-MM-dd → 周几 */
private fun weekday(d: String): String {
    val cal = Calendar.getInstance()
    runCatching {
        val y = d.substring(0, 4).toInt()
        val m = d.substring(5, 7).toInt()
        val day = d.substring(8, 10).toInt()
        cal.set(y, m - 1, day)
    }
    return arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
}
