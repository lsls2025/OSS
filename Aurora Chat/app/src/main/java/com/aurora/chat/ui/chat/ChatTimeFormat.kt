package com.aurora.chat.ui.chat

import java.util.Calendar
import java.util.Locale

/**
 * 会话内「时间分隔」的判定与文案（口径对齐微信 / QQ）。
 *
 * 机制不是「每条消息都带时间」，而是「相邻两条消息的空隙足够大时，才在前一条上方插一条时间」：
 *  · 同一自然日内，间隔 >= 5 分钟才显示；
 *  · 会话首条消息上方必显示（哪怕 1 秒前发的）；
 *  · 跨自然日必显示（哪怕只隔 2 分钟），文案同时降级为「昨天 / 星期X / 几月几日 / 年月日」；
 *  · 同日且不足 5 分钟 —— 完全隐藏，不留白、不占行高（连续对话的阅读节奏不能被打断）。
 *
 * 注意：锚点是「上一条消息」，不是「上一条显示了时间的消息」。
 * 所以如果对方每 6 分钟发一句，你会看到每一句上方都有时间 —— 这是正确行为，不要去做去重。
 */
internal object ChatTimeFormat {

    /** 同一自然日内触发时间分隔的最小间隔（秒）。微信取 5 分钟，这里保持一致。 */
    const val GAP_SECONDS = 300L

    private val WEEK_CN = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /**
     * 本地时区的「天序号」：year * 366 + dayOfYear。
     * 只用来判「同一天 / 差几天」，保证单调、跨年不跳变（366 是上界，年与年之间不会撞号）。
     * 不用「时间戳 / 86400」，因为那是 UTC 天，东八区会整体偏移 8 小时、跨天判断全错。
     */
    fun dayIndex(sec: Long): Long {
        if (sec <= 0L) return 0L
        val cal = Calendar.getInstance()
        cal.timeInMillis = sec * 1000L
        return cal.get(Calendar.YEAR).toLong() * 366L + cal.get(Calendar.DAY_OF_YEAR).toLong()
    }

    /** 本条消息上方是否要插一条时间。prevSec <= 0 表示「没有更早的消息」（会话首条 / 窗口首条）。 */
    fun shouldShow(prevSec: Long, curSec: Long): Boolean {
        if (curSec <= 0L) return false          // 时间戳缺失，宁可不显示也不显示 1970
        if (prevSec <= 0L) return true          // 首条必显示
        if (curSec < prevSec) return true       // 时间倒挂（补发 / 乱序），稳妥显示
        if (dayIndex(curSec) != dayIndex(prevSec)) return true // 跨天必显示
        return curSec - prevSec >= GAP_SECONDS
    }

    /**
     * 时间文案。nowSec 只用来判「今天 / 昨天 / 星期X / 今年」，调用方应在跨天时才推进它，
     * 不要每秒传新值 —— 否则可见 item 每秒重组，列表会抖。
     */
    fun formatLabel(curSec: Long, nowSec: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = curSec * 1000L
        val hm = String.format(
            Locale.CHINA,
            "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE)
        )
        val diff = dayIndex(nowSec) - dayIndex(curSec)
        return when {
            // diff <= 0：今天；客户端时钟比服务端快一点点时会算成 -1，同样按今天处理
            diff <= 0L -> hm
            diff == 1L -> "昨天 $hm"
            diff in 2L..6L -> "${WEEK_CN[cal.get(Calendar.DAY_OF_WEEK) - 1]} $hm"
            cal.get(Calendar.YEAR) == yearOf(nowSec) ->
                "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $hm"
            else ->
                "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $hm"
        }
    }

    private fun yearOf(sec: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = sec * 1000L
        return cal.get(Calendar.YEAR)
    }

    /** 消息的秒级时间：服务端 createdAt 优先，本地乐观消息回退 time（毫秒）。 */
    fun secOf(msg: IMessageRef): Long {
        val c = msg.createdAt
        return if (c > 0L) c else msg.time / 1000L
    }
}
