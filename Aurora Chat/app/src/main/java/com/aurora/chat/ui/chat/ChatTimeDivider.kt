package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 会话内居中那条灰色时间（微信/QQ 式）。
 *
 * 它不是消息：不参与引用、多选、复制；但可以长按 → 弹白底菜单 → 删除这一条时间。
 * 删除只是记墓碑（见 ChatTimeDividerPrefs），判定规则本身不变，
 * 所以后面的消息该显示时间照旧显示，只是这一条不再出现。
 */
@Composable
internal fun ChatTimeDivider(
    label: String,
    expanded: Boolean,
    onLongPress: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box {
            Text(
                text = label,
                fontSize = 12.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onLongPress
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            // 与会话列表长按菜单同款：纯白底、零色调抬升，不用 M3 的灰紫 surfaceContainer
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismiss,
                containerColor = com.aurora.chat.ui.theme.appSurface(),
                tonalElevation = 0.dp
            ) {
                DropdownMenuItem(
                    text = { Text("删除时间", fontSize = 14.sp, color = Color(0xFFE5484D)) },
                    onClick = onDelete
                )
            }
        }
    }
}
