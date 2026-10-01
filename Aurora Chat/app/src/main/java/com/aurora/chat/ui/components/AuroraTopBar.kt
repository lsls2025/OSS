package com.aurora.chat.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.DpOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.*
import kotlinx.coroutines.delay

enum class ConnStatus { Connected, Connecting, Disconnected }

data class StatusBanner(
    val message: String,
    val isError: Boolean,
    val durationMs: Long = 3000L
)

@Composable
fun AuroraTopBar(
    status: ConnStatus = ConnStatus.Connected,
    statusBanner: StatusBanner? = null,
    onBannerDismiss: () -> Unit = {},
    onPlusClick: () -> Unit = {},
    backgroundColor: Color = Color.White,
    showDivider: Boolean = true,
    plusMenuOpen: Boolean = false,
    onDismissPlusMenu: () -> Unit = {},
    onSearchUser: () -> Unit = {},
    onSearchGroup: () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onScanQr: () -> Unit = {}
) {
    val statusColor by animateColorAsState(
        targetValue = when (status) {
            ConnStatus.Connected -> StatusOnline
            ConnStatus.Connecting -> StatusConnecting
            ConnStatus.Disconnected -> StatusOffline
        }, animationSpec = tween(400), label = "st"
    )
    val statusText = when (status) {
        ConnStatus.Connected -> "在线"
        ConnStatus.Connecting -> "连接中..."
        ConnStatus.Disconnected -> "断连"
    }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(statusBanner.durationMs)
            onBannerDismiss()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .statusBarsPadding()
                .height(38.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Aurora Chat",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                Spacer(modifier = Modifier.width(5.dp))
                Text(statusText, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                PlusButton(onClick = onPlusClick)
                DropdownMenu(
                    expanded = plusMenuOpen,
                    onDismissRequest = onDismissPlusMenu,
                    offset = DpOffset((-114).dp, 0.dp),
                    modifier = Modifier.width(150.dp).background(Color.White)
                ) {
                    DropdownMenuItem(
                        text = { Text("搜索用户", fontSize = 14.sp, color = Color(0xFF1F2937)) },
                        onClick = onSearchUser
                    )
                    DropdownMenuItem(
                        text = { Text("搜索群聊", fontSize = 14.sp, color = Color(0xFF1F2937)) },
                        onClick = onSearchGroup
                    )
                    DropdownMenuItem(
                        text = { Text("创建群聊", fontSize = 14.sp, color = Color(0xFF1F2937)) },
                        onClick = onCreateGroup
                    )
                    DropdownMenuItem(
                        text = { Text("扫一扫", fontSize = 14.sp, color = Color(0xFF1F2937)) },
                        onClick = onScanQr
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        // 临时横幅
        AnimatedVisibility(
            visible = statusBanner != null,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(tween(200)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(200))
        ) {
            statusBanner?.let { banner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (banner.isError) Color(0xFFFEE2E2) else Color(0xFFD1FAE5))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = banner.message,
                        fontSize = 12.sp,
                        color = if (banner.isError) Color(0xFFDC2626) else Color(0xFF059669),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (showDivider && statusBanner == null) Box(modifier = Modifier.fillMaxWidth().height(0.5.dp)
            .background(Color(0xFFD4D8DD)))
    }
}

@Composable
private fun PlusButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val c = center
            val r = size.width / 2f - 1.dp.toPx()
            drawCircle(Color.Black, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            val len = r * 0.5f
            drawLine(Color.Black, Offset(c.x - len, c.y), Offset(c.x + len, c.y), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.Black, Offset(c.x, c.y - len), Offset(c.x, c.y + len), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}


