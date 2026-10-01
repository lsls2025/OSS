package com.aurora.chat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 可响应主题切换的页面配色
data class AppColors(
    val surface: Color,      // 页面背景
    val cardBg: Color,       // 卡片/列表项背景
    val divider: Color       // 分割线
)

private val LightAppColors = AppColors(
    surface = SurfaceLight,
    cardBg = CardBgLight,
    divider = DividerLight
)

private val DarkAppColors = AppColors(
    surface = SurfaceDark,
    cardBg = CardBgDark,
    divider = DividerDark
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

private val DarkCS = darkColorScheme(
    primary = AuroraPrimaryLight,
    secondary = AuroraPrimary,
    tertiary = AuroraPrimaryLight,
    background = BgDark,
    surface = CardDark,
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
fun AuroraChatTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(colorScheme = if (darkTheme) DarkCS else LightCS, content = content)
    }
}
