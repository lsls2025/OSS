package com.aurora.chat.ui.theme

import android.content.Context
import android.content.res.Configuration

/**
 * 全局主题偏好：白色主题 / 深色主题 / 跟随系统。
 * 默认白色。
 */
object ThemePrefs {
    private const val PREFS = "aurora_theme"
    private const val KEY = "theme_mode" // 0=白色 1=深色 2=跟随系统

    const val MODE_LIGHT = 0
    const val MODE_DARK = 1
    const val MODE_SYSTEM = 2

    fun getMode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, MODE_LIGHT)

    /** 当前是否应该用深色渲染。跟随系统时读系统 uiMode，系统是深色才深色。 */
    fun isDark(context: Context): Boolean = when (getMode(context)) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> run {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply()
    }

    /** 设置页那一行显示的模式名。 */
    fun label(context: Context): String = when (getMode(context)) {
        MODE_DARK -> "深色主题"
        MODE_SYSTEM -> "跟随系统"
        else -> "白色主题"
    }
}
