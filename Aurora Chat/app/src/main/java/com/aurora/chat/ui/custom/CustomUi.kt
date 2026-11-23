package com.aurora.chat.ui.custom
import com.aurora.chat.ui.theme.LocalAppColors
import com.aurora.chat.ui.theme.ActiveAppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「自定义」相关页面共用的视觉规范。
 *
 * 刻意对齐群聊信息页（GroupSettingsPanel.kt）：浅灰页面底、纯白无阴影卡片、
 * 深蓝 #1E40AF 强调色、0.5dp 浅灰分割线。不使用 Material3 的 tonal surface /
 * Card elevation / 默认 Switch 配色，避免出现 M3 那套发灰发紫的观感。
 */
object CustomUi {
    val PageBg get() = com.aurora.chat.ui.theme.appSurface()
    val CardBg get() = com.aurora.chat.ui.theme.appCardBg()
    val Primary = Color(0xFF1E40AF)
    val Text get() = ActiveAppColors.textPrimary
    val Sub get() = ActiveAppColors.textSecondary
    val Hint get() = ActiveAppColors.textSecondary
    val Divider get() = com.aurora.chat.ui.theme.appDivider()
    val FieldBg get() = ActiveAppColors.cardBg
    val Danger = Color(0xFFDC2626)
    val Border get() = ActiveAppColors.divider
    val CardShape = RoundedCornerShape(14.dp)
    val SmallShape = RoundedCornerShape(10.dp)

    /** 只保留真正常用的中性色；任意颜色在下面的大方格里精调。 */
    val Palette = listOf(
        0xFFFFFFFFL, // 白
        0xFF000000L, // 黑
        0xFF1E40AFL, // 深蓝
        0xFF9CA3AFL, // 中性灰
        0x00000000L  // 透明
    )
}

@Composable
fun CustomTopBar(
    title: String,
    onBack: () -> Unit,
    right: @Composable () -> Unit = {}
) {
    // 与设置各子页统一：52dp 行高、44dp 返回热区、ArrowBack 图标、17sp Bold 左对齐标题。
    // 白色底一直铺到状态栏（含状态栏留白），整条标题栏纯白、不再露出页面底色。
    Column(
        Modifier
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
                title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f)
            )
            right()
        }
    }
}

/** 白底圆角卡片（无 elevation、无 tonal 着色）。 */
@Composable
fun CustomSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CustomUi.CardShape)
            .background(CustomUi.CardBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
fun CustomDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(CustomUi.Divider))
}

/** 顶栏右侧的文字按钮。 */
@Composable
fun TopAction(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CustomUi.SmallShape)
            .background(if (primary) CustomUi.Primary else Color.Transparent)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = if (primary) Color.White else CustomUi.Primary
        )
    }
}

/** 属性面板用的小按钮。enabled=false 时置灰且不可点，用于「当前无可撤销操作」这类状态。 */
@Composable
fun SmallAction(
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = when {
        !enabled -> com.aurora.chat.ui.theme.appCardBg()
        active -> CustomUi.Primary
        danger -> Color(0xFFFEE2E2)
        else -> CustomUi.FieldBg
    }
    val fg = when {
        !enabled -> CustomUi.Hint
        active -> Color.White
        danger -> CustomUi.Danger
        else -> CustomUi.Text
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg) }
}

/** 加减步进器。 */
@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = CustomUi.Sub, modifier = Modifier.width(52.dp))
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                .background(CustomUi.FieldBg).clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onMinus() },
            contentAlignment = Alignment.Center
        ) { Text("−", fontSize = 15.sp, color = CustomUi.Text) }
        Text(
            value, fontSize = 12.sp, color = CustomUi.Text,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center
        )
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                .background(CustomUi.FieldBg).clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onPlus() },
            contentAlignment = Alignment.Center
        ) { Text("+", fontSize = 15.sp, color = CustomUi.Text) }
    }
}

/** 几个常用色 + 一个「精调」入口；展开后在方格内自己拖出任意颜色。 */
@Composable
fun ColorSwatches(
    current: Long,
    /** 直接写状态，不进撤销栈 */
    onPick: (Long) -> Unit,
    /** 一次选色手势开始时记一次历史 */
    onGestureStart: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CustomUi.Palette.forEach { c ->
                val picked = c == current
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (c == 0L) Color.White else Color(c))
                        .border(
                            width = if (picked) 2.dp else 0.5.dp,
                            color = if (picked) CustomUi.Primary else CustomUi.Border,
                            shape = CircleShape
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onGestureStart(); onPick(c) },
                    contentAlignment = Alignment.Center
                ) {
                    if (c == 0L) Text("∅", fontSize = 13.sp, color = CustomUi.Hint)
                }
            }
            Spacer(Modifier.weight(1f))
            SmallAction(if (expanded) "收起" else "精调", active = expanded) {
                expanded = !expanded
            }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            ColorPrecisionPicker(
                current = current,
                onPick = onPick,
                onGestureStart = onGestureStart
            )
        }
    }
}

@Composable
fun CustomSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = CustomUi.Primary,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFFD1D5DB)
        )
    )
}

/** 灰底输入框，与群聊信息页的输入区一致。 */
@Composable
fun CustomField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(CustomUi.SmallShape)
            .background(CustomUi.FieldBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        textStyle = TextStyle(fontSize = 14.sp, color = CustomUi.Text),
        singleLine = true,
        cursorBrush = SolidColor(CustomUi.Primary),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (value.isEmpty()) Text(hint, fontSize = 14.sp, color = CustomUi.Hint)
                inner()
            }
        }
    )
}
