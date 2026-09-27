package com.agit.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun relative(ts: Long): String {
        if (ts <= 0) return ""
        val diff = System.currentTimeMillis() - ts
        val sec = diff / 1000
        if (sec < 60) return "刚刚"
        val min = sec / 60
        if (min < 60) return "${min} 分钟前"
        val hr = min / 60
        if (hr < 24) return "${hr} 小时前"
        val day = hr / 24
        if (day < 30) return "${day} 天前"
        val mon = day / 30
        if (mon < 12) return "${mon} 个月前"
        return "${mon / 12} 年前"
    }

    fun dateTime(ts: Long): String {
        if (ts <= 0) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }

    fun date(ts: Long): String {
        if (ts <= 0) return "-"
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    }

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }

    fun shortId(id: String): String = if (id.length >= 7) id.substring(0, 7) else id
}
