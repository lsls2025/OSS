package com.aurora.chat.data.local

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 「已读显示」开关。
 *
 * 默认维持现状：已读/未读只在长按 →「消息信息」里看得到（不进二级页永远看不到）。
 * 开启后有两种摆放位置：
 *  - [MODE_BUBBLE]：自己发的消息气泡**下方**常驻一行浅灰小字
 *  - [MODE_BUBBLE_LEFT]：同样的小字挪到气泡**左侧**、与气泡垂直居中
 *
 * 文案规则（两种位置一致）：
 *  - 私聊：`已读` / `未读`（只标最后一条自己发的，因为已读是单调的，标一条等价于标全部）
 *  - 群聊：`3人已读`（每条自己发的都标，每条人数都不同；点开可看名单）
 *
 * 值会随 [ClientPrefsSync] 上传到服务端，重装/换机后自动还原。
 */
object ReadReceiptPrefs {

    /** 维持现状：只能在「消息信息」二级页里看 */
    const val MODE_INFO_ONLY = 0

    /** 在消息气泡下方常驻显示 */
    const val MODE_BUBBLE = 1

    /** 在消息气泡左侧常驻显示（与气泡垂直居中） */
    const val MODE_BUBBLE_LEFT = 2

    private const val KEY = "read_receipt_mode"

    private const val MIN_MODE = MODE_INFO_ONLY
    private const val MAX_MODE = MODE_BUBBLE_LEFT

    /**
     * 变更计数。
     *
     * 会话页用 `remember(friendId, ReadReceiptPrefs.version)` 拿模式：设置页在另一个界面改了偏好后，
     * 会话页不会重建，只靠 remember(friendId) 会一直停在旧值（表现就是「改了没反应，退出重进才对」）。
     * 这里在 [set] 里 +1，读它的 Composable 会立即重组。
     */
    var version by mutableIntStateOf(0)
        private set

    fun get(context: Context): Int =
        LocalStorage.getChatPrefs(context).getInt(KEY, MODE_INFO_ONLY).coerceIn(MIN_MODE, MAX_MODE)

    fun set(context: Context, value: Int) {
        LocalStorage.getChatPrefs(context).edit()
            .putInt(KEY, value.coerceIn(MIN_MODE, MAX_MODE))
            .apply()
        version++
    }

    /** 是否开了任意一种气泡已读显示（下方 / 左侧）。探针、推送刷新等副作用只关心这个。 */
    fun enabled(context: Context): Boolean = get(context) != MODE_INFO_ONLY

    /** 是否需要在气泡下方显示（调用点全部走这里，避免到处比对魔数） */
    fun showUnderBubble(context: Context): Boolean = get(context) == MODE_BUBBLE

    /** 是否需要在气泡左侧显示 */
    fun showLeftOfBubble(context: Context): Boolean = get(context) == MODE_BUBBLE_LEFT

    /** 设置页菜单里那句副标题 */
    fun label(context: Context): String = when (get(context)) {
        MODE_BUBBLE -> "气泡下方显示"
        MODE_BUBBLE_LEFT -> "气泡左侧显示"
        else -> "只在消息信息里查看"
    }
}
