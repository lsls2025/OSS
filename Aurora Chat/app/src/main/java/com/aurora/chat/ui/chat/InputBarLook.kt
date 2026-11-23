package com.aurora.chat.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.chat.data.local.InputBarStylePrefs
import com.aurora.chat.ui.theme.appSurface
import com.aurora.chat.ui.theme.appCardBg
import com.aurora.chat.ui.theme.appDivider
import com.aurora.chat.ui.theme.isAppDark

/**
 * 输入栏 / 输入框的实际配色与圆角。
 *
 * 抽出来的原因：设置页要画样式预览，聊天页要真渲染，两边必须是同一份定义，
 * 否则「设置里选的」和「实际生效的」迟早对不上。
 */
data class InputBarLook(
    /** 整条输入栏的底色（与消息区分界就靠它，所以不要在这上面再画线） */
    val barBg: Color,
    /** 输入框填充色 */
    val fieldBg: Color,
    /** 描边色；null = 不描边 */
    val border: Color?,
    val radius: Dp,
    /**
     * 功能面板（加号）里每个按钮的圆角块底色。
     * 由栏底色自动派生：栏底是灰时用白块，栏底接近纯白时白块会隐形、改用中性浅灰。
     * 自定义样式同样走这套规则，不需要在设置里再加一个开关。
     */
    val buttonBlockBg: Color = Color.White
) {
    val borderWidth: Dp get() = if (border == null) 0.dp else 1.dp
}

/** 禁言态的输入框底色（比正常态再暗一档，文字同时变灰） */
private val MutedFieldBg = Color(0xFFEDEFF2)

/** 栏底接近纯白时用的按钮块底色：比纯白暗一档，蓝色图标仍然清晰 */
private val NeutralButtonBlockBg = Color(0xFFF0F2F5)

/** 亮度近似（不用 Color.luminance，避免依赖其 API 形态）；>0.985 视为「接近纯白」 */
private fun Color.isNearWhite(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) > 0.985f

fun inputBarLook(
    style: Int,
    isMuted: Boolean = false,
    /** 自定义样式的参数；非 [InputBarStylePrefs.CUSTOM] 时忽略 */
    custom: InputBarStylePrefs.Custom = InputBarStylePrefs.DEFAULT_CUSTOM
): InputBarLook {
    val dark = isAppDark()
    val look = when (style) {
        InputBarStylePrefs.CLASSIC -> InputBarLook(
            barBg = if (dark) appSurface() else Color(0xFFF9FAFB),
            fieldBg = if (dark) appCardBg() else Color.White,
            border = if (dark) appDivider() else Color(0xFFD1D5DB),
            radius = 8.dp
        )
        InputBarStylePrefs.SOFT -> InputBarLook(
            barBg = if (dark) appSurface() else Color(0xFFF5F6F8),
            fieldBg = if (dark) appCardBg() else Color.White,
            border = null,
            radius = 8.dp
        )
        InputBarStylePrefs.PILL -> InputBarLook(
            barBg = if (dark) appSurface() else Color.White,
            fieldBg = if (dark) appCardBg() else Color(0xFFF2F3F5),
            border = null,
            radius = 18.dp
        )
        InputBarStylePrefs.OUTLINE_PILL -> InputBarLook(
            barBg = if (dark) appSurface() else Color.White,
            fieldBg = if (dark) appCardBg() else Color.White,
            border = if (dark) appDivider() else Color(0xFFD1D5DB),
            radius = 18.dp
        )
        InputBarStylePrefs.CUSTOM -> InputBarLook(
            barBg = Color(custom.barColor),
            fieldBg = Color(custom.fieldColor),
            border = if (custom.borderOn) Color(custom.borderColor) else null,
            radius = custom.radius.coerceIn(0, InputBarStylePrefs.RADIUS_MAX).dp
        )
        else -> InputBarLook(
            barBg = if (dark) appSurface() else Color.White,
            fieldBg = if (dark) appCardBg() else Color(0xFFF2F3F5),
            border = null,
            radius = 8.dp
        )
    }
    // 按钮块底色统一在这里派生：所有内置样式与「自定义」都自动适配，
    // 聊天页和设置页预览共用这一份定义。
    // 深色下用抬升的深色块；浅色下栏底接近纯白时改用中性浅灰，否则用白块。
    val buttonBlockBg = when {
        style == InputBarStylePrefs.CUSTOM -> if (look.barBg.isNearWhite()) NeutralButtonBlockBg else Color.White
        dark -> Color(0xFF242424)
        look.barBg.isNearWhite() -> NeutralButtonBlockBg
        else -> Color.White
    }
    val withButtonBlock = look.copy(buttonBlockBg = buttonBlockBg)
    // 禁言：输入框底色统一压暗；有描边的样式换成浅黄，提示「这里现在是只读的」。
    // 栏底色没变，所以按钮块底色沿用上面的派生结果。
    return if (!isMuted) withButtonBlock else withButtonBlock.copy(
        fieldBg = if (dark) Color(0xFF1E1E1E) else MutedFieldBg,
        border = look.border?.let { Color(0xFFFDE68A) }
    )
}
