package com.aurora.chat.host.pm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aurora.chat.host.HostFile

/** 列表项菜单选项。 */
data class ChoiceItem(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** 无波纹点击。 */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(indication = null, interactionSource = MutableInteractionSource()) { onClick() }

/** 无波纹组合点击（点击 + 长按）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableNoRipple(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(
        indication = null,
        interactionSource = MutableInteractionSource(),
        onClick = onClick,
        onLongClick = onLongClick,
    )

@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalAppColors.current
    Row(
        // background 先于 statusBarsPadding，保证状态栏区域也被 cardBg 覆盖，彻底不透出底层
        Modifier.fillMaxWidth().background(c.cardBg).statusBarsPadding().height(56.dp)
            .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = c.textPrimary) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = c.textTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<ChoiceItem>, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(320.dp).clip(RoundedCornerShape(20.dp)).background(c.cardBg).padding(vertical = 10.dp)) {
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.textPrimary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                LazyColumn {
                    items(options) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickableNoRipple(item.onClick)
                                .padding(horizontal = 18.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.icon != null) {
                                Icon(item.icon, contentDescription = null,
                                    tint = if (item.danger) c.danger else c.textSecondary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(14.dp))
                            }
                            Text(item.label, color = if (item.danger) c.danger else c.textPrimary, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String, danger: Boolean = false, confirmText: String = "确定",
    message: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = if (danger) c.danger else c.teal) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textSecondary) } },
        title = { Text(title, fontWeight = FontWeight.Bold, color = c.textPrimary) },
        text = { Text(message, fontSize = 14.sp, color = c.textSecondary) },
        containerColor = c.cardBg,
    )
}

@Composable
fun ProgressOverlay(progress: Float, text: String) {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = c.teal, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(10.dp))
            Text(text.ifEmpty { "处理中…" }, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
fun EmptyState(text: String, icon: ImageVector) {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(46.dp))
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = 13.sp, color = c.textTertiary)
        }
    }
}

@Composable
fun LoadingState() {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = c.teal, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
    }
}

@Composable
fun ErrorState(text: String, onRetry: () -> Unit) {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(8.dp))
            Text(text, fontSize = 13.sp, color = c.textTertiary)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRetry) { Text("重试", color = c.teal) }
        }
    }
}

@Composable
fun AppButton(text: String, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    val c = LocalAppColors.current
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) c.danger else c.teal),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
    ) { Text(text, color = Color.White, fontSize = 15.sp) }
}

@Composable
fun AppOutlinedField(value: String, onValueChange: (String) -> Unit, label: String, singleLine: Boolean = true) {
    val c = LocalAppColors.current
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label, fontSize = 13.sp) },
        singleLine = singleLine, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.appBg, unfocusedContainerColor = c.appBg,
            focusedBorderColor = c.teal, unfocusedBorderColor = c.divider,
        ),
    )
}

@Composable
fun SectionHeader(text: String) {
    val c = LocalAppColors.current
    Text(text, color = c.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
}

@Composable
fun PureWhiteDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(340.dp).clip(RoundedCornerShape(20.dp)).background(c.cardBg).padding(20.dp)) {
            Column(content = content)
        }
    }
}

@Composable
fun TextButton2(text: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Text(text, color = c.teal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp))
}

fun fileKindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.VIDEO -> Icons.Rounded.VideoLibrary
    FileKind.AUDIO -> Icons.Rounded.MusicNote
    FileKind.ARCHIVE -> Icons.Rounded.Archive
    FileKind.CODE -> Icons.Rounded.Code
    FileKind.DOC -> Icons.Rounded.Description
    FileKind.GENERIC -> Icons.Rounded.InsertDriveFile
}

fun fileKindTint(kind: FileKind): Color = when (kind) {
    FileKind.IMAGE -> Color(0xFFD97706)
    FileKind.VIDEO -> Color(0xFFDC2626)
    FileKind.AUDIO -> Color(0xFF9333EA)
    FileKind.ARCHIVE -> Color(0xFF7C3AED)
    FileKind.CODE -> Color(0xFF059669)
    FileKind.DOC -> Color(0xFF2563EB)
    FileKind.GENERIC -> Color(0xFF6B7280)
}

/** 列表图标：文件夹固定显示文件夹图标，其余按文件类型。 */
fun hostFileIcon(f: HostFile): ImageVector =
    if (f.isDir) Icons.Rounded.Folder else fileKindIcon(f.kind)

/** 列表图标颜色：文件夹金色，其余按文件类型。 */
fun hostFileTint(f: HostFile): Color =
    if (f.isDir) Color(0xFF0EA5A4) else fileKindTint(f.kind) // 文件夹用 teal（与底部导航栏选中 tab 同款，LightColors.teal）
