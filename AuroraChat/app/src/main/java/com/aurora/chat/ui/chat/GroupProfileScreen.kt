package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.components.GroupAvatar

/**
 * 群资料全屏界面（只读）：
 * 顶部返回箭头 + 标题「群资料」（样式与群聊信息一致），
 * 中部群头像 + 群名 + 群号，下方展示签名/公告/欢迎语/成员数。
 * 由群设置页群号右侧箭头进入。
 */
@Composable
fun GroupProfileScreen(
    groupId: Long,
    groupName: String,
    displayId: Long,
    signature: String,
    announcements: List<String>,
    welcomeEnabled: Boolean,
    welcomeText: String,
    memberCount: Int,
    avatarBitmap: Bitmap?,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg()).statusBarsPadding()) {
        // 事件穿透防御：全屏覆盖层必须先吃掉触摸，避免点击穿透到下层群设置列表
        com.aurora.chat.ui.components.EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        Column(Modifier.fillMaxSize()) {
            // ── 顶部栏（与群聊信息标题一致） ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text("群资料", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                // 右侧占位保持标题居中
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(28.dp))
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))

                // ── 头部信息：头像 + 群名 + 群号 ──
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap!!.asImageBitmap(),
                            contentDescription = "群头像",
                            // background 只画底色、不裁图，必须 clip 才是圆角（否则显示直角方块）
                            modifier = Modifier.size(88.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(com.aurora.chat.ui.theme.appSurface()),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        GroupAvatar(internalGroupId = groupId, groupName = groupName, size = 88.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (groupName.isNotBlank()) groupName else "未命名群聊",
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text("群号：$displayId", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Spacer(Modifier.height(4.dp))
                    Text("$memberCount 位成员", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }

                Spacer(Modifier.height(24.dp))

                // ── 信息卡：签名 / 公告 / 欢迎语（只读） ──
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("群签名", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Text(if (signature.isNotBlank()) signature else "暂无签名",
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                    Spacer(Modifier.height(12.dp))
                    Text("群公告", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    if (announcements.isEmpty()) {
                        Text("无公告", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    } else {
                        announcements.forEach { a ->
                            if (a.isNotBlank()) {
                                Text("· $a", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 20.sp)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                    Spacer(Modifier.height(12.dp))
                    Text("群欢迎语", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (!welcomeEnabled) "未开启"
                        else if (welcomeText.isNotBlank()) welcomeText else "暂无欢迎语",
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
