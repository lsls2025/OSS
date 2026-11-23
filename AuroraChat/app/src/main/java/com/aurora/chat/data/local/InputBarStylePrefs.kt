package com.aurora.chat.data.local

import android.content.Context

/**
 * 「输入框设置」：聊天输入栏与输入框的外观样式。
 *
 * 只影响观感，不改变任何交互（高度、内边距、按钮位置都是同一套）。
 * 读取点只有一处（InputBar 根容器 + 输入框），改完返回聊天页即刻生效。
 *
 * 前 5 项是固定样式，第 6 项 [CUSTOM] 的圆角与配色由用户自己调，参数存在同一份 prefs 里。
 */
object InputBarStylePrefs {

    /** 浅灰栏 + 白输入框 + 1dp 灰描边（项目最初的默认外观） */
    const val CLASSIC = 0

    /** 白栏 + 浅灰输入框 + 无描边（微信 / QQ 的做法） */
    const val FILLED = 1

    /** 浅灰栏 + 白输入框 + 无描边（靠底色差区分，不看线）—— 默认样式 */
    const val SOFT = 2

    /** 白栏 + 浅灰输入框 + 全圆角（胶囊形） */
    const val PILL = 3

    /** 白栏 + 白输入框 + 1dp 灰描边 + 全圆角 */
    const val OUTLINE_PILL = 4

    /** 自定义：圆角、栏底色、框底色、描边色全部由用户调，参数见 [Custom] */
    const val CUSTOM = 5

    /**
     * 默认样式：白框浅底。
     *
     * 只影响「没设置过的人」—— [get] 读不到键时才用这个值；
     * 已经选过别的样式的用户，存的还是自己的选择，不会因为改默认被改回去。
     */
    const val DEFAULT = SOFT

    private const val KEY = "input_bar_style"

    private const val KEY_RADIUS = "input_bar_custom_radius"
    private const val KEY_BAR = "input_bar_custom_bar"
    private const val KEY_FIELD = "input_bar_custom_field"
    private const val KEY_BORDER_ON = "input_bar_custom_border_on"
    private const val KEY_BORDER = "input_bar_custom_border"

    /** 圆角上限（dp）。再大在单行高度下会变成半圆，和多行状态差得太多。 */
    const val RADIUS_MAX = 24

    /**
     * 自定义样式参数。颜色都是 ARGB Int —— 数据层不引 Compose 类型，
     * 由 UI 层 (InputBarLook) 再转成 Color。
     */
    data class Custom(
        /** 圆角，dp */
        val radius: Int,
        /** 整条输入栏底色 */
        val barColor: Int,
        /** 输入框填充色 */
        val fieldColor: Int,
        /** 是否描边 */
        val borderOn: Boolean,
        /** 描边色（borderOn = false 时忽略） */
        val borderColor: Int
    )

    /** 首次进自定义时的初值：就是默认的浅灰填充，改起来有个基准 */
    val DEFAULT_CUSTOM = Custom(
        radius = 8,
        barColor = 0xFFFFFFFF.toInt(),
        fieldColor = 0xFFF2F3F5.toInt(),
        borderOn = false,
        borderColor = 0xFFD1D5DB.toInt()
    )

    fun get(context: Context): Int {
        val v = LocalStorage.getChatPrefs(context).getInt(KEY, DEFAULT)
        return v.coerceIn(CLASSIC, CUSTOM)
    }

    fun set(context: Context, value: Int) {
        LocalStorage.getChatPrefs(context)
            .edit()
            .putInt(KEY, value.coerceIn(CLASSIC, CUSTOM))
            .apply()
    }

    fun custom(context: Context): Custom {
        val p = LocalStorage.getChatPrefs(context)
        val d = DEFAULT_CUSTOM
        return Custom(
            radius = p.getInt(KEY_RADIUS, d.radius).coerceIn(0, RADIUS_MAX),
            barColor = p.getInt(KEY_BAR, d.barColor),
            fieldColor = p.getInt(KEY_FIELD, d.fieldColor),
            borderOn = p.getBoolean(KEY_BORDER_ON, d.borderOn),
            borderColor = p.getInt(KEY_BORDER, d.borderColor)
        )
    }

    fun setCustom(context: Context, c: Custom) {
        LocalStorage.getChatPrefs(context)
            .edit()
            .putInt(KEY_RADIUS, c.radius.coerceIn(0, RADIUS_MAX))
            .putInt(KEY_BAR, c.barColor)
            .putInt(KEY_FIELD, c.fieldColor)
            .putBoolean(KEY_BORDER_ON, c.borderOn)
            .putInt(KEY_BORDER, c.borderColor)
            .apply()
    }

    fun label(value: Int): String = when (value) {
        CLASSIC -> "经典描边"
        SOFT -> "白框浅底"
        PILL -> "全圆角"
        OUTLINE_PILL -> "全圆角描边"
        CUSTOM -> "自定义"
        else -> "浅灰填充"
    }

    /**
     * 设置页的展示顺序：默认样式（白框浅底）在最前，其余按既有顺序，自定义固定放最后。
     * 这里只决定「排列」，不参与默认值计算 —— 默认值是上面的 [DEFAULT]。
     */
    val ALL = listOf(SOFT, FILLED, CLASSIC, PILL, OUTLINE_PILL, CUSTOM)
}
