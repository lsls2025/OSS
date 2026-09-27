package com.pm.manager.model

import androidx.compose.runtime.Immutable
import com.pm.manager.core.formatBytes
import com.pm.manager.core.formatFullTime

/**
 * 站点备份条目：服务器返回 {id, name, size, time}，time 为 Unix 秒（历史数据可能残留毫秒，前端做归一化）。
 */
@Immutable
data class SiteBackup(
    val id: Long,
    val name: String,
    val size: Long,
    val time: Long
) {
    fun sizeText(): String = formatBytes(size)

    /** yyyy-MM-dd HH:mm。非法/缺失时间返回空串。兼容秒与毫秒两种单位。 */
    fun timeText(): String {
        if (time <= 0) return ""
        val sec = if (time > 100_000_000_000L) time / 1000 else time
        return formatFullTime(sec * 1000)
    }
}
