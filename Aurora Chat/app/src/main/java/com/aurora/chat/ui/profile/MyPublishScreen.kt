package com.aurora.chat.ui.profile
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 我的发布：条目页。
 *
 * 与「小游戏」页同构（五子棋/漂流瓶 → 社区/视频），点进去各自是全屏子页。
 * 由外层（MainActivity）以子页面方式从右侧滑入整屏展示。
 */
@Composable
fun MyPublishScreen(
    onCommunity: () -> Unit,
    onVideos: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(enabled = true) { onBack() }
    Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg())) {
        // 标题栏：自身延伸到状态栏底下（background 在 statusBarsPadding 之前 → 状态栏区域同为实心白）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.aurora.chat.ui.theme.appSurface())
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = com.aurora.chat.ui.theme.appTextPrimary()
                    )
                }
                Text(
                    text = "我的发布",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.aurora.chat.ui.theme.appTextPrimary()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 内容用白卡承载，与其他页面保持同一套板块风格
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(com.aurora.chat.ui.theme.appSurface())
        ) {
            PublishRow(title = "社区", onClick = onCommunity)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(com.aurora.chat.ui.theme.appCardBg())
            )
            PublishRow(title = "视频", onClick = onVideos)
        }
    }
}

@Composable
private fun PublishRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = com.aurora.chat.ui.theme.appTextPrimary(),
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Color(0xFFC7CBD1),
            modifier = Modifier.size(16.dp)
        )
    }
}
