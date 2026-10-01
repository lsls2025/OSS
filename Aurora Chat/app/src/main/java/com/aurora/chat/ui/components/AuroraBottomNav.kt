package com.aurora.chat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.R
import com.aurora.chat.ui.theme.AuroraPrimary
import com.aurora.chat.ui.components.UnreadBadge

enum class AuroraTab(val label: String, val iconRes: Int) {
    Chat("聊天", R.drawable.ic_chat),
    Server("联系人", R.drawable.ic_contacts),
    Community("社区", R.drawable.ic_community),
    Profile("我的", R.drawable.ic_profile);
}

@Composable
fun AuroraBottomNav(
    currentTab: AuroraTab,
    onTabSelected: (AuroraTab) -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.White,
    showDivider: Boolean = true,
    badges: Map<AuroraTab, Int> = emptyMap()
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFFD4D8DD))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(vertical = 6.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AuroraTab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    isSelected = currentTab == tab,
                    onClick = { onTabSelected(tab) },
                    badgeCount = badges[tab] ?: 0
                )
            }
        }
    }
}

@Composable
private fun TabItem(tab: AuroraTab, isSelected: Boolean, onClick: () -> Unit, badgeCount: Int = 0) {
    val tabColor by animateColorAsState(
        targetValue = if (isSelected) AuroraPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        animationSpec = tween(200), label = "tabColor"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1.0f,
        animationSpec = tween(200), label = "tabScale"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clip(RoundedCornerShape(8.dp))
            .clickable(
                // 用 clickable 而非 detectTapGestures：detectTapGestures 在注册 onDoubleTap 时
                // 会强制等待约 300ms 双击检测窗口才触发单击，导致 tab 切换"延迟到爆"。
                // clickable 单击即时响应，无任何等待。
                interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                indication = null
            ) {
                onClick()
            }
            .scale(scale)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(tab.iconRes),
                contentDescription = tab.label,
                tint = tabColor,
                modifier = Modifier.size(26.dp)
            )
            if (badgeCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = 1.dp)
                        .zIndex(1f)
                ) {
                    UnreadBadge(count = badgeCount, size = 14.dp, fontSize = 9.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = tab.label, fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, color = tabColor)
    }
}
