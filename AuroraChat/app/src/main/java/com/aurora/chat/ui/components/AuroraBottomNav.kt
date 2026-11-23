package com.aurora.chat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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

enum class AuroraTab(val label: String, val iconRes: Int, val iconFilledRes: Int) {
    Chat("聊天", R.drawable.ic_chat, R.drawable.ic_chat_filled),
    Server("联系人", R.drawable.ic_contacts, R.drawable.ic_contacts_filled),
    Community("社区", R.drawable.ic_community, R.drawable.ic_community_filled),
    Profile("我的", R.drawable.ic_profile, R.drawable.ic_profile_filled);
}

@Composable
fun AuroraBottomNav(
    currentTab: AuroraTab,
    onTabSelected: (AuroraTab) -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = com.aurora.chat.ui.theme.appBarBg(),
    showDivider: Boolean = true,
    badges: Map<AuroraTab, Int> = emptyMap()
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(com.aurora.chat.ui.theme.appDivider())
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                // 分割线（底栏上边界）下移 3dp：上内边距 10 → 7，底栏总高度随之减 3dp，
                // 整条栏变矮、上边界向下压。内容底部距屏底由 bottom + 系统导航区决定，
                // bottom 保持 0，所以图标与文字的位置不会跟着上移。
                .padding(top = 5.dp, bottom = 0.dp)
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
                painter = painterResource(if (isSelected) tab.iconFilledRes else tab.iconRes),
                contentDescription = tab.label,
                tint = if (isSelected) Color.Unspecified else tabColor,
                // 四个图标通体下移 3dp。用 offset 只移动绘制位置、不参与布局测量，
                // 所以 Column 高度、文字位置和 11sp 字号一点都不会变。
                modifier = Modifier
                    .size(24.dp)
                    .offset(y = 3.dp)
            )
            if (badgeCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(1f)
                ) {
                    if (tab == AuroraTab.Profile) {
                        // 「我的」tab：社区动态未读用纯红点（更小、更克制），并略微下移避免压住图标
                        Box(
                            Modifier
                                .offset(x = 2.dp, y = 4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                        )
                    } else {
                        UnreadBadge(count = badgeCount, size = 14.dp, fontSize = 9.sp,
                            modifier = Modifier.offset(x = 3.dp, y = 1.dp))
                    }
                }
            }
        }
        // 原来这里留 2dp 空隙，图标与文字显得松散；改为 1dp 贴近但不粘连。
        // 字号 11sp 保持不变。
        Spacer(modifier = Modifier.height(1.dp))
        Text(text = tab.label, fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, color = tabColor)
    }
}
