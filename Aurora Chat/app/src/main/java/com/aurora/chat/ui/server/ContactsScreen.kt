package com.aurora.chat.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 联系人板块占位页（暂未开放，后续填充联系人列表）
 */
@Composable
fun ContactsPlaceholderScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "暂无联系人",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF9CA3AF)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "联系人功能尚未开放，敬请期待",
                fontSize = 13.sp,
                color = Color(0xFFB4B8BF)
            )
        }
    }
}