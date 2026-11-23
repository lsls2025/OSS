package com.aurora.chat.ui.profile
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.BlacklistItem
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.launch

/**
 * 黑名单全屏页。
 *
 * 展示当前用户的黑名单列表（含双向：我拉黑的 + 拉黑我的），每条可「拉出」。
 * 返回箭头在顶部栏，内容区自身消费点击以避免穿透到下层页面。
 */
@Composable
fun BlacklistScreen(currentUserId: Long, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<BlacklistItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun reload() {
        scope.launch {
            loading = true
            val res = ChatRepository.getBlacklist()
            if (res.success && res.data != null) items = res.data!!
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Box(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
    ) {
        // 内容区：自身消费点击事件，避免穿透到下层页面；返回箭头为子组件，仍可正常点击
        Column(
            Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {}
        ) {

            // 统一标题栏（与设置各子页一致）
            com.aurora.chat.ui.components.SettingsTopBar(title = "黑名单", onBack = onBack)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(com.aurora.chat.ui.theme.appDivider())
            )

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("加载中…", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("暂无黑名单", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.userId }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(
                                userId = item.userId,
                                userName = item.username,
                                size = 44.dp,
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.username,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = com.aurora.chat.ui.theme.appTextPrimary()
                                )
                                Text(
                                    text = if (item.iAmBlocker) "你拉黑了 TA" else "TA 拉黑了你",
                                    fontSize = 12.sp,
                                    color = com.aurora.chat.ui.theme.appTextHint()
                                )
                                if (item.signature.isNotBlank()) {
                                    Text(
                                        text = item.signature,
                                        fontSize = 12.sp,
                                        color = com.aurora.chat.ui.theme.appTextHint()
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFDC2626))
                                    .clickable {
                                        scope.launch {
                                            val res = ChatRepository.unblockUser(item.userId)
                                            if (res.success) {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "已移出黑名单",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                reload()
                                            } else {
                                                android.widget.Toast.makeText(
                                                    context,
                                                    res.message,
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("拉出", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
