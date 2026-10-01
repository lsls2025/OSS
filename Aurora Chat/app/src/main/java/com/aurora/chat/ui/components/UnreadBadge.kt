package com.aurora.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 未读红点（数字严格居中）。
 * count<=0 不渲染；>99 显示 "99+"；圆宽随位数自适应，数字始终几何居中。
 */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    fontSize: TextUnit = 11.sp
) {
    if (count <= 0) return
    val text = if (count > 99) "99+" else count.toString()
    // 两位及以上（含 99+）适当加宽，保证数字不溢出且仍居中
    val badgeSize = if (text.length > 1) size + 6.dp else size
    Box(
        modifier = modifier
            .size(badgeSize)
            .clip(CircleShape)
            .background(Color.Red),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            style = TextStyle(
                lineHeight = fontSize,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}
