package com.aurora.chat.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R

/**
 * 品牌区唯一实现（引导页品牌步 + 启动解锁页共用）：
 * 高清图标 + 项目名 + 英文标语 + 中文标语。
 *
 * 改动品牌只改这一处，引导页与启动解锁页自动同步，禁止再复制一份内联代码。
 */
@Composable
fun BrandBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 直接用 2398×2398 的高清品牌图 ic_app_icon。
        // 旧版用 192×192 的 mipmap PNG 放大导致发糊；高清原图在任何屏幕密度下都足够锐利。
        // 不加边框：图标外缘即图形本身，分割线反而破坏观感。
        Image(
            painter = painterResource(id = R.drawable.ic_app_icon),
            contentDescription = null,
            modifier = Modifier
                .size(210.dp)
                .clip(RoundedCornerShape(38.dp))
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Aurora Chat",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "WORDS ECHO, TASKS LAND",
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "聊有回响，办有着落",
            fontSize = 15.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
