package com.aurora.chat.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * LiveEditPanel —— 复刻 CodeBuddy「修改」实时编辑视图。
 *
 * 效果：顶部标题「修改」+ 脉冲指示点，下方内容以"逐字流式"方式实时呈现（打字机效果），
 * 末尾带闪烁光标；播完转为「修改完成」（绿点）。可点右上「重新播放」再看一次。
 *
 * 设计为 Compose 覆盖层（非窗口 Dialog），可直接叠在任意界面之上，避免与现有 Dialog 层级冲突。
 *
 * @param content  要展示的完整内容（通常传入编辑器当前全文）
 * @param fileName 顶栏副标题（文件名）
 * @param onClose  关闭回调
 */
@Composable
fun LiveEditPanel(
    content: String,
    fileName: String = "",
    onClose: () -> Unit
) {
    // 流式进度：已显示的字符数
    var shownLen by remember(content) { mutableStateOf(0) }
    // 闪烁光标状态
    var cursorOn by remember { mutableStateOf(true) }

    // 脉冲指示点：透明度循环
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700)), label = "pulseAlpha"
    )

    // 打字机：整段内容在约 2.6s 内播完；超长文本按块推进，避免每帧重排卡顿
    LaunchedEffect(content) {
        shownLen = 0
        val total = content.length
        if (total == 0) return@LaunchedEffect
        val perStep = if (total > 1200) (total / 120) + 1 else 1
        while (shownLen < total) {
            shownLen = (shownLen + perStep).coerceAtMost(total)
            delay(if (perStep == 1) 12L else 18L)
        }
    }
    // 光标闪烁
    LaunchedEffect(Unit) {
        while (true) { cursorOn = !cursorOn; delay(500) }
    }

    val done = shownLen >= content.length
    val shown = if (content.isEmpty() || shownLen > content.length) "" else content.substring(0, shownLen)

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：修改/修改完成 + 脉冲点 + 文件名 + 重新播放 + 关闭
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (done) Color(0xFF10B981)
                            else Color(0xFF3B82F6).copy(alpha = pulseAlpha)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (done) "修改完成" else "修改",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
                if (fileName.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        fileName, fontSize = 12.sp, color = Color(0xFF8B949E),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = { shownLen = 0 }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新播放", tint = Color(0xFF8B949E))
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color(0xFF8B949E))
                }
            }

            // 内容区：等宽字体，逐字流式呈现 + 闪烁光标
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D1117))
                    .padding(16.dp)
            ) {
                val display = if (content.isEmpty()) {
                    buildAnnotatedString { append("（内容为空）") }
                } else {
                    buildAnnotatedString {
                        append(shown)
                        if (!done && cursorOn) {
                            pushStyle(SpanStyle(color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold))
                            append("▍")
                            pop()
                        }
                    }
                }
                Text(
                    text = display,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFC9D1D9),
                    lineHeight = 20.sp
                )
            }
        }
    }
}
