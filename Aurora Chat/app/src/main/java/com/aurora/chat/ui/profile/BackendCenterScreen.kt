package com.aurora.chat.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.LocalAppColors

/**
 * 「后台中心」页：从「其他」页进入的独立全屏页。
 *
 * 排版布局：
 * - 一排两个板块入口（横排小按钮，与用户资料/群资料的板块同一套灰色风格）；
 * - 每个板块点击后进入对应的全屏界面（MainActivity 的 SubScreen 叠加渲染）。
 *
 * 骨架规格：
 * - 根级全屏渲染，覆盖顶部标题与底部导航；
 * - 顶部 statusBarsPadding 避让状态栏；
 * - 实底背景 + fillMaxSize，下层内容不可见也不可点（事件不穿透）；
 * - 返回：顶部返回键 / 左滑手势 / BackHandler，只关本页回到「其他」，不跳级。
 */
@Composable
fun BackendCenterScreen(
    onClose: () -> Unit,
    onOpenFileDistribution: () -> Unit,
    onOpenReserved2: () -> Unit
) {
    val appColors = LocalAppColors.current
    var totalDrag by remember { mutableStateOf(0f) }
    Column(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                    onDragEnd = { if (totalDrag < -80f) onClose() }
                )
            }
    ) {
        // 顶部栏：标题 + 返回（覆盖应用顶栏），避让状态栏
        Box(
            Modifier
                .fillMaxWidth()
                .background(appColors.surface)
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "后台中心",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 正文：一排两个板块入口（小按钮、横排、正常灰色）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            // 板块一：文件分发
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(10.dp))
                    .clickable { onOpenFileDistribution() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "文件分发",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    textAlign = TextAlign.Center
                )
            }
            // 板块二（预留）
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(10.dp))
                    .clickable { onOpenReserved2() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "板块二",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 后台中心预留板块的全屏占位页（与 [BackendCenterScreen] 同骨架）：
 * 状态栏避让、实底背景事件不穿透、返回键/左滑/顶部返回键只关本页不跳级。
 */
@Composable
fun BackendCenterPlaceholderScreen(
    title: String,
    onClose: () -> Unit
) {
    val appColors = LocalAppColors.current
    var totalDrag by remember { mutableStateOf(0f) }
    Column(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                    onDragEnd = { if (totalDrag < -80f) onClose() }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(appColors.surface)
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(appColors.surface),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$title（内容后续接入）",
                fontSize = 14.sp,
                color = com.aurora.chat.ui.theme.appTextHint()
            )
        }
    }
}
