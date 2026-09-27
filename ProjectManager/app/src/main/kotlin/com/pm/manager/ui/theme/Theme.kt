package com.pm.manager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.pm.manager.core.ThemeMode

private val LightScheme = lightColorScheme(
    primary = LightAppColors.teal,
    onPrimary = Color.White,
    primaryContainer = LightAppColors.tealSoft,
    onPrimaryContainer = LightAppColors.tealDark,
    secondary = LightAppColors.teal,
    background = LightAppColors.appBg,
    onBackground = LightAppColors.textPrimary,
    surface = LightAppColors.surface,
    onSurface = LightAppColors.textPrimary,
    surfaceVariant = LightAppColors.divider,
    onSurfaceVariant = LightAppColors.textSecondary,
    outline = LightAppColors.divider,
    error = LightAppColors.danger,
    onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = DarkAppColors.teal,
    onPrimary = Color(0xFF062A26),
    primaryContainer = DarkAppColors.tealSoft,
    onPrimaryContainer = DarkAppColors.tealDark,
    secondary = DarkAppColors.teal,
    background = DarkAppColors.appBg,
    onBackground = DarkAppColors.textPrimary,
    surface = DarkAppColors.surface,
    onSurface = DarkAppColors.textPrimary,
    surfaceVariant = DarkAppColors.divider,
    onSurfaceVariant = DarkAppColors.textSecondary,
    outline = DarkAppColors.divider,
    error = DarkAppColors.danger,
    onError = Color(0xFF3B0A0C)
)

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalAppColors provides if (dark) DarkAppColors else LightAppColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            content = content
        )
    }
}
