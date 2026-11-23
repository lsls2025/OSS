package com.aurora.chat.ui.components
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.R

@Composable
fun AboutPage(
    onDonate: () -> Unit
) {
    val context = LocalContext.current
    // QQ 官方交流群跳转链接（点击群号直接加入）
    val qqGroupUrl = com.aurora.chat.util.QqGroupLink.URL
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(240.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 标题紧贴顶部
        Text(
            text = "关于我们",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 60.dp, end = 20.dp, bottom = 20.dp)
        )

        // 拾光工作室出品 - 大号加深
        Text(
            text = "拾光工作室出品",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        // QQ 官方交流群：群号高亮（蓝色）且可点击直接跳转，不额外显示提示文字
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qqGroupUrl)))
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "QQ官方交流群：",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = "YOUR_QQ_GROUP",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1E40AF)
            )
        }

        Text(
            text = "作者是一名初中生没啥实力,\n感兴趣的可以来交流群交流。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.weight(1f))

        // 赞赏按钮 - 无emoji，用醒目红色
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE74C3C))
                .clickable { onDonate() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "赞赏作者",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun DonateDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(com.aurora.chat.ui.theme.appSurface())
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.qr_placeholder),
                    contentDescription = "赞赏码",
                    modifier = Modifier.size(200.dp),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text("感谢你的支持", fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2D3436))
                Spacer(modifier = Modifier.height(4.dp))
                Text("作者感激不尽", fontSize = 14.sp,
                    color = Color(0xFF636E72))
            }
        }
    }
}
