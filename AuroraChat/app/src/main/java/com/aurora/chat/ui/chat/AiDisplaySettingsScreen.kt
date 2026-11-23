package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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

/**
 * AI 显示设置（齿轮入口）。
 *
 * 全屏、普通 Compose 层从右侧切入，不用 Dialog —— 因此不存在
 * `decorFitsSystemWindows=false` 带来的状态栏穿透 / 顶栏配色不一致问题；
 * 白色背景铺满整屏，顶栏再单独让出状态栏高度。
 *
 * 页面只有功能标题，没有任何说明文字、提示、Toast 或保存按钮；开关改动即时落盘。
 */
@Composable
fun AiDisplaySettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var showReasoning by remember { mutableStateOf(AIUiPrefs.showReasoning(ctx)) }
    var showUsage by remember { mutableStateOf(AIUiPrefs.showUsage(ctx)) }
    var afterComplete by remember { mutableStateOf(AIUiPrefs.showAfterComplete(ctx)) }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    null,
                    Modifier.size(22.dp),
                    tint = com.aurora.chat.ui.theme.appTextPrimary()
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "设置",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
        }
        HorizontalDivider(thickness = 1.dp, color = com.aurora.chat.ui.theme.appDivider())

        SettingRow("显示思考", showReasoning) {
            showReasoning = it
            AIUiPrefs.setShowReasoning(ctx, it)
            aiDisplayRefresh.intValue++
        }
        SettingRow("显示消耗", showUsage) {
            showUsage = it
            AIUiPrefs.setShowUsage(ctx, it)
            aiDisplayRefresh.intValue++
        }
        SettingRow("整段显示", afterComplete) {
            afterComplete = it
            AIUiPrefs.setShowAfterComplete(ctx, it)
            aiDisplayRefresh.intValue++
        }
    }
}

@Composable
private fun SettingRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(thickness = 1.dp, color = com.aurora.chat.ui.theme.appDivider())
}
