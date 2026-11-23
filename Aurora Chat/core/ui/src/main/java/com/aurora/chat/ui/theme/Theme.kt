package com.aurora.chat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 可响应主题切换的页面配色
data class AppColors(
    val surface: Color,         // 页面背景（白主题=纯白，黑主题=纯黑）
    val cardBg: Color,          // 卡片/列表项/浅灰底背景
    val divider: Color,         // 分割线
    val textPrimary: Color,     // 主文字/图标
    val textSecondary: Color    // 次级文字/图标（灰色）
)

private val LightAppColors = AppColors(
    surface = Color.White,
    cardBg = CardBgLight,
    divider = DividerLight,
    textPrimary = Color(0xFF1F2937),
    textSecondary = Color(0xFF6B7280)
)

private val DarkAppColors = AppColors(
    surface = BgDark,
    cardBg = CardDark,
    divider = DividerDark,
    textPrimary = Color.White,
    textSecondary = TextDarkSec
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/**
 * 非 Composable 上下文（顶层 val / Canvas 绘制 lambda / 普通函数）也能取到的当前主题色。
 * 由 [AuroraChatTheme] 在组合根同步，单次组合内稳定，跟随重建切换主题。
 */
var ActiveAppColors: AppColors = LightAppColors
    internal set

fun appSurface(): Color = ActiveAppColors.surface
fun appCardBg(): Color = ActiveAppColors.cardBg
fun appDivider(): Color = ActiveAppColors.divider
fun appTextPrimary(): Color = ActiveAppColors.textPrimary
fun appTextSecondary(): Color = ActiveAppColors.textSecondary

/**
 * 三级提示文字（时间分隔、系统广播、占位符、未读数旁注等）：
 * 浅色主题=浅灰 #9CA3AF（改动前的原始观感），深色主题=中性灰 #8B8B8B。
 * 比 [appTextSecondary] 更淡一档，用于「有信息但不抢眼」的弱化文字。
 */
fun appTextHint(): Color = if (isAppDark()) Color(0xFF8B8B8B) else Color(0xFF9CA3AF)

/** 当前是否为深色主题（非 Composable 上下文可用，例如输入栏配色派生） */
fun isAppDark(): Boolean = ActiveAppColors === DarkAppColors

/**
 * 浅蓝「选中 / 高亮 / 面板」背景：浅色主题保持原浅蓝，深色主题换成中性深灰，
 * 与中性灰阶色板保持一致（不掺蓝，避免整屏偏色）。
 * 覆盖 EFF6FF / EAF1FF / F0F4FF / F0F9FF / E0F2FE 等历史硬编码浅蓝背景。
 */
fun appSelectedBg(): Color = if (isAppDark()) Color(0xFF272727) else Color(0xFFEFF6FF)

/**
 * 顶栏 / 底栏底色：浅色=纯白，深色=纯黑。
 * 这两条栏刻意比页面底色更「沉」，用来和内容区分层。
 */
fun appBarBg(): Color = if (isAppDark()) Color.Black else Color.White

/**
 * 卡片底面：浅色=纯白（与改动前一致），深色=比页面亮一档的卡片灰。
 * 深色主题里页面是 #0A0A0A，卡片若也用页面色就会「糊」在背景里，
 * 所以卡片统一抬一档，再配 [appCardOutline] 描边才有边界感。
 */
fun appCardSurface(): Color = if (isAppDark()) ActiveAppColors.cardBg else ActiveAppColors.surface

/** 卡片描边：深色主题下给卡片一圈细边；浅色主题返回透明（保持改动前的无边框观感） */
fun appCardOutline(): Color = if (isAppDark()) ActiveAppColors.divider else Color.Transparent

/**
 * 线稿图标（笑脸 / 加号 / 麦克风 / 键盘等手绘 Canvas）的笔画色：
 * 浅色主题=纯黑（保持改动前观感），深色主题=纯白。
 */
fun appInk(): Color = if (isAppDark()) Color.White else Color.Black

/** [appInk] 的反色：画在墨色色块上的点/条（如键盘键位点） */
fun appInkContrast(): Color = if (isAppDark()) Color.Black else Color.White

private val DarkCS = darkColorScheme(
    primary = AuroraPrimaryLight,
    secondary = AuroraPrimary,
    tertiary = AuroraPrimaryLight,
    background = BgDark,
    surface = BgDark,
    surfaceVariant = CardDarkElevated,
    onPrimary = Color.White,
    onBackground = TextDark,
    onSurface = TextDark,
    outline = BorderDark,
)

private val LightCS = lightColorScheme(
    primary = AuroraPrimary,
    secondary = AuroraPrimary,
    tertiary = AuroraPrimaryLight,
    background = BgLight,
    surface = CardLight,
    onPrimary = Color.White,
    onBackground = TextLight,
    onSurface = TextLight,
    outline = BorderLight,
)

@Composable
fun AuroraChatTheme(darkTheme: Boolean = ThemePrefs.isDark(LocalContext.current), content: @Composable () -> Unit) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    ActiveAppColors = appColors
    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(colorScheme = if (darkTheme) DarkCS else LightCS, content = content)
    }
}
