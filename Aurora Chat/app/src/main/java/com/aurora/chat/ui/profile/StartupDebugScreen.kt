package com.aurora.chat.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.LocalAppColors

/**
 * 「启动调试」页：**首次进入软件时的启动页预览入口**。
 *
 * 当前只做了入口与页面骨架，正文内容（启动页要展示的东西）后续接入。
 * 之所以先把这个空页面建出来而不是只留一个点不动的菜单项：
 * 入口必须能点开、能返回、能确认挂在「其他」页下方，否则无从验收。
 *
 * 样式刻意与同级的 [ProvideFreeApiScreen] 保持一致（顶部栏 + 左滑返回），
 * 这样后续往正文里填内容时不需要再动外层骨架。
 */
@Composable
fun StartupDebugScreen(
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
        // 顶部栏：标题 + 返回（覆盖应用顶栏，与同级子页面一致）
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
                    text = "启动调试",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // ── 正文占位：启动页内容后续接入 ──
        // 这里刻意不放假数据/假动画：入口只是入口，内容由后续需求定义。
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "启动页内容待接入",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
