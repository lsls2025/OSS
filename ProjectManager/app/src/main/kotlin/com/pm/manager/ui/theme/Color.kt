package com.pm.manager.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 应用语义色板。
 *
 * 原来所有颜色都是顶层硬编码常量（TealPrimary / TextPrimary / AppBg…），深色模式下无处可换。
 * 现在收成一套语义 token，浅/深各一份，界面统一从 [LocalAppColors] 取色，
 * 保证「同一个语义在任何界面都是同一个颜色」。
 */
@Immutable
data class AppColors(
    /** 品牌主色（青绿）。 */
    val teal: Color,
    val tealDark: Color,
    val tealSoft: Color,
    val tealFaint: Color,
    /** 窗口背景（卡片之外）。 */
    val appBg: Color,
    /** 卡片/列表容器背景。 */
    val cardBg: Color,
    /** 弹窗等抬升层背景。 */
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    /** 黑色遮罩，用于全屏预览容器。 */
    val scrim: Color,
    // ---- 固定场景色：终端与代码编辑器本身就是深色工作台，两种主题下保持一致 ----
    val terminalBg: Color,
    val terminalBar: Color,
    val terminalText: Color,
    val terminalError: Color,
    val editorBg: Color,
    val editorSurface: Color,
    val editorText: Color,
    val editorGutter: Color
)

val LightAppColors = AppColors(
    teal = Color(0xFF0E9F8E),
    tealDark = Color(0xFF0A6B60),
    tealSoft = Color(0xFFD9F1ED),
    tealFaint = Color(0xFFF0FAF8),
    appBg = Color(0xFFF4F6F8),
    cardBg = Color.White,
    surface = Color.White,
    textPrimary = Color(0xFF1D2329),
    textSecondary = Color(0xFF79828A),
    textTertiary = Color(0xFFA6AEB5),
    divider = Color(0xFFECEEF1),
    danger = Color(0xFFD64545),
    success = Color(0xFF22A06B),
    warning = Color(0xFFD98A0B),
    scrim = Color.Black,
    terminalBg = Color.Black,
    terminalBar = Color(0xFF050505),
    terminalText = Color(0xFF22C55E),
    terminalError = Color(0xFFFF6B6B),
    editorBg = Color(0xFF1E1E1E),
    editorSurface = Color(0xFF2D2D2D),
    editorText = Color(0xFFD4D4D4),
    editorGutter = Color(0xFF6E7681)
)

val DarkAppColors = AppColors(
    teal = Color(0xFF2DBFA9),
    tealDark = Color(0xFF7FE3D4),
    tealSoft = Color(0xFF14332F),
    tealFaint = Color(0xFF1B2624),
    appBg = Color(0xFF0F1416),
    cardBg = Color(0xFF171D20),
    surface = Color(0xFF1D2427),
    textPrimary = Color(0xFFE6EAED),
    textSecondary = Color(0xFF97A1A9),
    textTertiary = Color(0xFF6D767D),
    divider = Color(0xFF262D31),
    danger = Color(0xFFF2666B),
    success = Color(0xFF4CC38A),
    warning = Color(0xFFE5A33A),
    scrim = Color.Black,
    terminalBg = Color(0xFF050505),
    terminalBar = Color(0xFF0C0C0C),
    terminalText = Color(0xFF3DDC84),
    terminalError = Color(0xFFFF7B7B),
    editorBg = Color(0xFF141414),
    editorSurface = Color(0xFF212121),
    editorText = Color(0xFFD4D4D4),
    editorGutter = Color(0xFF6E7681)
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }
