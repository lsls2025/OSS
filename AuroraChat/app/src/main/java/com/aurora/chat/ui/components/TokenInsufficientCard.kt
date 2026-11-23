package com.aurora.chat.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val QQ_GROUP_URL = "https://example.com/qq-group"

@Composable
fun TokenInsufficientCard(
    title: String = "token余额已耗尽",
    subtitle: String = "可前往 QQ 官方交流群\nYOUR_QQ_GROUP\n寻找群主购买余额",
    onRecharge: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(150))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 340.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onDismiss),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg()),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        color = com.aurora.chat.ui.theme.appTextSecondary(),
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TokenCardPill("前往群聊") {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL)))
                            }
                            onRecharge()
                        }
                    }
                }
            }
        }
    }
}

/** 底部胶囊按钮（沿用通知中心卡片的蓝色圆角胶囊风格） */
@Composable
private fun RowScope.TokenCardPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF1E40AF))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}
