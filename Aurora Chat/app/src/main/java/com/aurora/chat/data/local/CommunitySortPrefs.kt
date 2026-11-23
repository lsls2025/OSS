package com.aurora.chat.data.local

import android.content.Context

/**
 * 社区信息流排序方式（DEFAULT 默认 / MIXED 综合 / LATEST 最新）的唯一读写口。
 *
 * 为什么单独抽出来：这个值有三个来源 —— 用户在本机切换、服务端同步回写、以及
 * 页面创建时读取。原来直接读 SharedPreferences，服务端同步如果晚于信息流页面创建，
 * 页面就会一直停在旧的「综合」上（用户看到的「重装后又变回综合」）。
 * 这里加一层内存缓存，页面每次加载都能读到最新值。
 */
object CommunitySortPrefs {
    private const val SP = "community_feed_cache"
    private const val KEY = "sort_mode"

    /** 默认排序：综合（与改造前保持一致） */
    const val DEFAULT = "MIXED"

    @Volatile private var memo: String? = null

    fun get(context: Context): String {
        memo?.let { return it }
        val v = context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: DEFAULT
        memo = v
        return v
    }

    fun set(context: Context, value: String) {
        if (value != "DEFAULT" && value != "MIXED" && value != "LATEST") return
        memo = value
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
