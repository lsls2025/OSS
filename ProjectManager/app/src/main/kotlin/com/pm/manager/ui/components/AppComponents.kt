@file:OptIn(ExperimentalMaterial3Api::class)

package com.pm.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.pm.manager.ui.theme.LocalAppColors
import com.pm.manager.ui.theme.LightAppColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp

/** 无涟漪点击（用于列表项、菜单项，避免选中时出现多余的水波纹）。 */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick
    )

/** 无涟漪的 combinedClickable（列表项长按菜单用，避免水波纹特效）。 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableNoRipple(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = this.combinedClickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    onClick = onClick,
    onLongClick = onLongClick
)

/** 统一顶栏：返回按钮 + 标题 + 右侧操作区。所有子页面共用，保证视觉一致。 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    subtitle: String? = null
) {
    val c = LocalAppColors.current
    TopAppBar(
        title = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    color = c.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = c.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = c.textPrimary
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = c.cardBg,
            scrolledContainerColor = c.cardBg,
            titleContentColor = c.textPrimary,
            actionIconContentColor = c.textPrimary
        )
    )
}

/** 分组标题（如设置页、备份页里的小节）。 */
@Composable
fun SectionHeader(text: String) {
    val c = LocalAppColors.current
    Text(
        text = text,
        color = c.teal,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 18.dp, bottom = 8.dp)
    )
}

/** 空状态占位。 */
@Composable
fun EmptyState(message: String, icon: ImageVector = androidx.compose.material.icons.Icons.Rounded.FolderOpen) {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(14.dp))
            Text(message, color = c.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

/** 错误状态占位 + 重试按钮。 */
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)? = null) {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                androidx.compose.material.icons.Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = c.danger,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(message, color = c.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                AppButton(text = "重试", onClick = onRetry)
            }
        }
    }
}

/** 居中加载态。 */
@Composable
fun LoadingState(message: String = "加载中…") {
    val c = LocalAppColors.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = c.teal, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(14.dp))
            Text(message, color = c.textSecondary, fontSize = 14.sp)
        }
    }
}

/** 主操作按钮：支持 loading 态。 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
    colors: ButtonColors = if (danger) ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.danger) else ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.teal)
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(12.dp),
        colors = colors
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("处理中…", color = Color.White, fontSize = 15.sp)
        } else {
            Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 统一外形文本输入。 */
@Composable
fun AppOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    enabled: Boolean = true,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        isError = isError,
        supportingText = supportingText
    )
}

/**
 * 纯白弹窗容器：强制使用浅色语义配色，底色固定为纯白。
 * 深色主题下的卡片底色是偏青的深色，弹窗统一走纯白，
 * 并强制浅色配色确保「白底 + 深色字」，任何主题下都清晰可读。
 */
@Composable
fun PureWhiteDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    elevation: Dp = 4.dp,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalAppColors provides LightAppColors) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                tonalElevation = elevation,
                modifier = modifier
            ) { content() }
        }
    }
}

/** 通用确认弹窗。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    cancelText: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    PureWhiteDialog(onDismiss) {
        val c = LocalAppColors.current
        Column(Modifier.padding(20.dp).width(300.dp)) {
            Text(title, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = c.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = c.textSecondary)) {
                    Text(cancelText)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    colors = if (danger) ButtonDefaults.buttonColors(containerColor = c.danger) else ButtonDefaults.buttonColors(containerColor = c.teal)
                ) {
                    Text(confirmText, color = Color.White)
                }
            }
        }
    }
}

/** 带输入框的弹窗（重命名 / 新建目录 / 命名压缩包等）。 */
@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String,
    confirmText: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    singleLine: Boolean = true,
    isError: (String) -> Boolean = { it.isBlank() },
    errorMessage: String = "不能为空"
) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    PureWhiteDialog(onDismiss) {
        val c = LocalAppColors.current
        Column(Modifier.padding(20.dp).width(320.dp)) {
            Text(title, color = c.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            AppOutlinedField(
                value = text,
                onValueChange = { text = it },
                label = label,
                singleLine = singleLine
            )
            if (text.isNotBlank() && isError(text)) {
                Spacer(Modifier.height(6.dp))
                Text(errorMessage, color = c.danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = c.textSecondary)) {
                    Text("取消")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(text) },
                    enabled = !isError(text),
                    colors = ButtonDefaults.buttonColors(containerColor = c.teal)
                ) {
                    Text(confirmText, color = Color.White)
                }
            }
        }
    }
}

/** 进度遮罩：上传/下载/还原时锁定界面并展示进度。 */
@Composable
fun ProgressOverlay(progress: Float, text: String) {
    CompositionLocalProvider(LocalAppColors provides LightAppColors) {
        val c = LocalAppColors.current
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 6.dp) {
                Column(
                    Modifier.padding(24.dp).width(260.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = { progress },
                        color = c.teal,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(text, color = c.textPrimary, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text("${(progress * 100).toInt()}%", color = c.textSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

/** 通用选项菜单（更多操作 / 排序 / 选择列表）。自适应宽度、居中弹窗，避免过大。 */
@Composable
fun ChoiceDialog(
    title: String? = null,
    options: List<ChoiceItem>,
    onDismiss: () -> Unit
) {
    PureWhiteDialog(onDismiss, modifier = Modifier.widthIn(min = 260.dp, max = 340.dp)) {
        val c = LocalAppColors.current
        Column(Modifier.padding(vertical = 8.dp).verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
            if (title != null) {
                Text(
                    title,
                    color = c.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
            options.forEach { opt ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .background(if (opt.danger) c.danger.copy(alpha = 0.08f) else Color.White)
                        .clickableNoRipple { opt.onClick(); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (opt.icon != null) {
                        Icon(opt.icon, contentDescription = null, tint = if (opt.danger) c.danger else c.textPrimary, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(
                        opt.label,
                        color = if (opt.danger) c.danger else c.textPrimary,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

data class ChoiceItem(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/** 文件类型 → 图标 + 配色，统一所有列表/网格/详情里的视觉表达。 */
fun fileKindIcon(kind: com.pm.manager.model.FileKind): ImageVector = when (kind) {
    com.pm.manager.model.FileKind.FOLDER -> androidx.compose.material.icons.Icons.Rounded.Folder
    com.pm.manager.model.FileKind.IMAGE -> androidx.compose.material.icons.Icons.Rounded.Image
    com.pm.manager.model.FileKind.CODE -> androidx.compose.material.icons.Icons.Rounded.Code
    com.pm.manager.model.FileKind.DOC -> androidx.compose.material.icons.Icons.Rounded.Description
    com.pm.manager.model.FileKind.ARCHIVE -> androidx.compose.material.icons.Icons.Rounded.Archive
    com.pm.manager.model.FileKind.AUDIO -> androidx.compose.material.icons.Icons.Rounded.AudioFile
    com.pm.manager.model.FileKind.VIDEO -> androidx.compose.material.icons.Icons.Rounded.Movie
    else -> androidx.compose.material.icons.Icons.Rounded.InsertDriveFile
}

@Composable
fun fileKindTint(kind: com.pm.manager.model.FileKind): Color = when (kind) {
    com.pm.manager.model.FileKind.FOLDER -> LocalAppColors.current.teal
    com.pm.manager.model.FileKind.IMAGE -> Color(0xFF3B82F6)
    com.pm.manager.model.FileKind.CODE -> Color(0xFF8B5CF6)
    com.pm.manager.model.FileKind.DOC -> Color(0xFF0EA5E9)
    com.pm.manager.model.FileKind.ARCHIVE -> Color(0xFFF59E0B)
    com.pm.manager.model.FileKind.AUDIO -> Color(0xFFEC4899)
    com.pm.manager.model.FileKind.VIDEO -> Color(0xFFEF4444)
    else -> LocalAppColors.current.textSecondary
}
