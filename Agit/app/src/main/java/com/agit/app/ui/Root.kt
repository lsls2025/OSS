package com.agit.app.ui

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF1F6FDB),
    secondary = Color(0xFF3FB950),
    onSecondary = Color(0xFF0D1117),
    tertiary = Color(0xFFD29922),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    error = Color(0xFFF85149),
    outline = Color(0xFF30363D)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0969DA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE8FF),
    secondary = Color(0xFF1A7F37),
    onSecondary = Color.White,
    tertiary = Color(0xFF9A6700),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1F2328),
    surface = Color(0xFFF6F8FA),
    onSurface = Color(0xFF1F2328),
    surfaceVariant = Color(0xFFD0D7DE),
    onSurfaceVariant = Color(0xFF59636E),
    error = Color(0xFFCF222E),
    outline = Color(0xFFD0D7DE)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Suppress("DEPRECATION")
@Composable
fun RootTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography
    ) {
        content()
    }
}

fun launchApp(activity: ComponentActivity) {
    activity.setContent {
        RootTheme {
            val nc = rememberNavController()
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                AppNav(nc)
            }
        }
    }
}
