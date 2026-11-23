package com.aurora.chat.security
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 反抓包全屏阻断页：检测到抓包/代理环境时覆盖整个界面，阻断与下层的一切交互。
 */
@Composable
fun CaptureBlockScreen(reason: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { /* 吞掉点击，阻断与下层交互 */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("访问已被拦截", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
            Spacer(Modifier.height(14.dp))
            Text(
                "检测到抓包环境，连接已终止。",
                fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
            )
            Spacer(Modifier.height(10.dp))
            Text("原因：$reason", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
    }
}

/**
 * 反抓包守卫：放进根布局的最上层即可。
 * 周期检测当前环境，命中抓包/代理即全屏阻断。
 */
@Composable
fun CaptureBlockOverlay() {
    var reason by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            reason = SecurityGuard.detect()
            kotlinx.coroutines.delay(2000L)
        }
    }
    reason?.let { CaptureBlockScreen(it) }
}
