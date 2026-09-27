package com.pm.manager.core

import java.util.Locale

/** 字节数格式化。site/backup 两处原本各写一份，这里统一。 */
fun formatBytes(size: Long): String = when {
    size < 0 -> "—"
    size < 1024 -> "$size B"
    size < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", size / 1024.0)
    size < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", size / 1048576.0)
    else -> String.format(Locale.ROOT, "%.2f GB", size / 1073741824.0)
}

/** 毫秒时长 → mm:ss 或 h:mm:ss。 */
fun formatDuration(ms: Int): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * 时间戳相对化：今天显示「今天 14:30」，昨天显示「昨天 09:12」，更早显示「MM-dd HH:mm」。
 * 比原来清一色 `MM-dd HH:mm` 更容易判断文件新旧。
 */
fun formatRelativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }

    val sameYear = cal.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR)
    val dayDiff = cal.get(java.util.Calendar.DAY_OF_YEAR) - target.get(java.util.Calendar.DAY_OF_YEAR)

    val hm = String.format(Locale.ROOT, "%02d:%02d",
        target.get(java.util.Calendar.HOUR_OF_DAY), target.get(java.util.Calendar.MINUTE))

    return when {
        dayDiff == 0 && sameYear -> "今天 $hm"
        dayDiff == 1 && sameYear -> "昨天 $hm"
        dayDiff in 2..6 && sameYear -> "${dayDiff}天前"
        else -> {
            val md = String.format(Locale.ROOT, "%02d-%02d",
                target.get(java.util.Calendar.MONTH) + 1, target.get(java.util.Calendar.DAY_OF_MONTH))
            if (sameYear) "$md $hm"
            else "${target.get(java.util.Calendar.YEAR)}-$md"
        }
    }
}

/** 精确到秒的完整时间，用于文件详情。 */
fun formatFullTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "未知"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        .format(java.util.Date(epochMillis))
}

/** 把路径段列表拼成展示用的相对路径。根目录返回空串。 */
fun joinPath(path: List<String>): String = path.joinToString("/")

/** 从 SAF 返回的 Uri 中解析出显示文件名（上传时用于命名）。 */
fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    val resolver = context.contentResolver
    var name: String? = null
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) name = cur.getString(cur.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
        }
    }
    return name ?: "file_${System.currentTimeMillis()}"
}

/** 从 SAF Uri 中读取文件真实大小；拿不到返回 -1。 */
fun queryFileSize(context: android.content.Context, uri: android.net.Uri): Long = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            val idx = cur.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0) cur.getLong(idx) else -1L
        } else -1L
    } ?: -1L
}.getOrDefault(-1L)

/** 把 "a/b//c/" 之类的输入规范化成路径段列表。 */
fun parsePath(raw: String?): List<String> =
    (raw ?: "").trim().trimStart('/').split("/").filter { it.isNotBlank() }
