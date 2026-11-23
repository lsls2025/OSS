@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.gomoku.GomokuActivity

/**
 * 五子棋邀请卡片。
 *
 * 协议（纯文本，和位置/分享/清空聊天等卡片同一套做法，后端零改动、可离线渲染）：
 * ```
 * 五子棋邀请
 * ━━━━━━━━
 * <邀请人昵称> 邀请你下一盘五子棋
 * 房间号：ABCD12
 * room_id=ABCD12
 * inviter_id=12
 * ```
 * 失效判定：房主（发起方）一退出对局，服务端就销毁房间，所以卡片只需向
 * `/api/gomoku/room?room_id=` 探活，房间不存在 = 已过期，按钮置灰不可点。
 */
data class GomokuInviteData(val roomId: String, val inviterId: Long, val inviterName: String)

fun isGomokuInviteCard(text: String): Boolean = text.trimStart().startsWith("五子棋邀请")

fun parseGomokuInvite(text: String): GomokuInviteData? {
    if (!isGomokuInviteCard(text)) return null
    val lines = text.lines().map { it.trim() }
    val roomId = lines.firstOrNull { it.startsWith("room_id=") }?.removePrefix("room_id=")?.trim()
        ?: lines.firstOrNull { it.startsWith("房间号：") }?.removePrefix("房间号：")?.trim()
        ?: return null
    if (roomId.isEmpty()) return null
    val inviterId = lines.firstOrNull { it.startsWith("inviter_id=") }
        ?.removePrefix("inviter_id=")?.trim()?.toLongOrNull() ?: 0L
    // 昵称：含「邀请你下一盘五子棋」的那一行，去掉尾巴
    val name = lines.firstOrNull { it.contains("邀请你下一盘五子棋") }
        ?.substringBefore(" 邀请你下一盘五子棋")?.trim().orEmpty()
    return GomokuInviteData(roomId, inviterId, name)
}

fun buildGomokuInviteCard(roomId: String, inviterId: Long, inviterName: String): String {
    val name = if (inviterName.isBlank()) "好友" else inviterName
    return buildString {
        appendLine("五子棋邀请")
        appendLine("━━━━━━━━")
        appendLine("$name 邀请你下一盘五子棋")
        appendLine("房间号：$roomId")
        appendLine("room_id=$roomId")
        append("inviter_id=$inviterId")
    }
}

@Composable
fun GomokuInviteCard(
    text: String,
    isMine: Boolean,
    onBubbleLongPress: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val data = remember(text) { parseGomokuInvite(text) }
    // 房间有效性：null=检测中，true=可加入，false=已过期/已开局/已满
    var canJoin by remember(text) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(text) {
        val room = data?.roomId.orEmpty()
        if (room.isEmpty()) {
            canJoin = false
            return@LaunchedEffect
        }
        // 网络失败不改判（保持"可加入"），避免断网时把好好的邀请误标成过期
        val r = AuroraApi.getGomokuRoomStatus(room)
        if (r.success) canJoin = (r.data == true)
    }

    val expired = canJoin == false
    val borderColor = if (expired) com.aurora.chat.ui.theme.appDivider() else Color(0xFFDCE7FA)
    val accent = if (expired) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF1E40AF)

    Column(
        modifier = Modifier
            .widthIn(min = 210.dp, max = 258.dp)
            .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(14.dp))
            .border(0.5.dp, borderColor, RoundedCornerShape(14.dp))
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {
                    if (isMine) {
                        Toast.makeText(ctx, "这是你发出的邀请，等待好友加入中", Toast.LENGTH_SHORT).show()
                        return@combinedClickable
                    }
                    if (expired) {
                        Toast.makeText(ctx, "邀请已过期：对方已离开对局", Toast.LENGTH_SHORT).show()
                        return@combinedClickable
                    }
                    val room = data?.roomId.orEmpty()
                    if (room.isEmpty()) return@combinedClickable
                    try {
                        ctx.startActivity(
                            Intent(ctx, GomokuActivity::class.java)
                                .putExtra("room_id", room)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "无法打开五子棋", Toast.LENGTH_SHORT).show()
                    }
                },
                onLongClick = { onBubbleLongPress() }
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GomokuMiniBoard(accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "五子棋对战邀请",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isMine) "等待好友加入…" else "来自 ${data?.inviterName?.ifBlank { "好友" } ?: "好友"}",
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(com.aurora.chat.ui.theme.appCardBg())
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "房间 ${data?.roomId ?: "----"}",
                fontSize = 11.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.weight(1f)
            )
            val label = when {
                isMine -> "已发送"
                canJoin == null -> "检测中…"
                expired -> "已过期"
                else -> "进入对局"
            }
            Box(
                Modifier
                    .background(
                        if (expired) com.aurora.chat.ui.theme.appCardBg() else accent,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (expired) com.aurora.chat.ui.theme.appTextHint() else Color.White
                )
            }
        }
    }
}

/** 卡片左侧的小棋盘图标：3x3 网格 + 一黑一白棋子 */
@Composable
private fun GomokuMiniBoard(accent: Color) {
    Box(
        Modifier
            .size(40.dp)
            .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(26.dp)) {
            val n = 3
            val step = size.width / (n + 1)
            for (i in 1..n) {
                val p = step * i
                drawLine(accent.copy(alpha = 0.45f), Offset(p, 0f), Offset(p, size.height), strokeWidth = 1.2f)
                drawLine(accent.copy(alpha = 0.45f), Offset(0f, p), Offset(size.width, p), strokeWidth = 1.2f)
            }
            val r = step * 0.42f
            drawCircle(com.aurora.chat.ui.theme.appTextPrimary(), r, Offset(step * 2f, step * 2f))
            drawCircle(Color(0xFFFFFFFF), r, Offset(step * 1f, step * 1f))
            drawCircle(com.aurora.chat.ui.theme.appTextHint(), r, Offset(step * 1f, step * 1f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
    }
}
