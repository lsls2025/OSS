package com.aurora.chat.host.pm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 与「项目管理」完全一致的配色（强制浅色 teal 主题，独立于 Aurora Chat 主皮肤）。 */
data class AppColors(
    val appBg: Color,
    val cardBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val teal: Color,
    val tealSoft: Color,
    val tealDark: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val terminalBg: Color,
    val terminalText: Color,
    val terminalBar: Color,
)

private val LightColors = AppColors(
    appBg = Color(0xFFFFFFFF),
    cardBg = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1F2937),
    textSecondary = Color(0xFF6B7280),
    textTertiary = Color(0xFF9CA3AF),
    divider = Color(0xFFE5E7EB),
    teal = Color(0xFF0EA5A4),
    tealSoft = Color(0xFFE6F7F6),
    tealDark = Color(0xFF0F766E),
    danger = Color(0xFFDC2626),
    success = Color(0xFF16A34A),
    warning = Color(0xFFF59E0B),
    terminalBg = Color(0xFF0B0E14),
    terminalText = Color(0xFFD6DEEB),
    terminalBar = Color(0xFF161B22),
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

/** 让虚拟主机界面整体使用「项目管理」配色，与外部聊天皮肤解耦。 */
@Composable
fun ProvideHostColors(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides LightColors) {
        content()
    }
}
