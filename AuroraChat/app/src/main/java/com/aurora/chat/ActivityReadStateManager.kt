package com.aurora.chat

import android.content.Context
import android.content.SharedPreferences
import com.aurora.chat.data.local.ClientPrefsSync

/**
 * 社区动态「已读」状态。
 *
 * 服务端不返回 is_read，已读完全由端上维护（见 CommunityActivityScreen 的说明）。
 * 这里额外做两件事：
 *  1. 集合设上限（保留 id 最大的若干条，id 越大越新）——不然用得越久集合越大，
 *     每次上传/解析都是几万条，既慢又占配额；
 *  2. 每次落盘后触发一次服务端同步，重装/换机后红点不会重新冒出来。
 */
object ActivityReadStateManager {
    private const val PREF_NAME = "aurora_activity_read"
    private const val KEY_READ_IDS = "read_activity_ids"

    /** 本地保留的已读 id 上限：服务端只在前 50 条动态上算红点，3000 条远超需求 */
    private const val MAX_READ_IDS = 3000

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getReadActivityIds(context: Context): Set<Long> {
        val prefs = getPrefs(context)
        val json = prefs.getString(KEY_READ_IDS, "[]") ?: "[]"
        return try {
            val list = mutableSetOf<Long>()
            val jsonArray = org.json.JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getLong(i))
            }
            list
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun markActivityRead(context: Context, activityId: Long) {
        val ids = getReadActivityIds(context).toMutableSet()
        ids.add(activityId)
        saveReadIds(context, ids)
    }

    fun markAllRead(context: Context, activityIds: List<Long>) {
        val ids = getReadActivityIds(context).toMutableSet()
        ids.addAll(activityIds)
        saveReadIds(context, ids)
    }

    fun isActivityRead(context: Context, activityId: Long): Boolean {
        return getReadActivityIds(context).contains(activityId)
    }

    /**
     * 服务端同步落地：直接用给定集合覆盖本地。
     * 不回传（由调用方决定是否需要补传），避免「拉取 → 写本地 → 又触发上传」的环路。
     */
    fun applyFromServer(context: Context, ids: Set<Long>) {
        writeIds(context, ids)
    }

    private fun saveReadIds(context: Context, ids: Set<Long>) {
        writeIds(context, ids)
        // 本地改动即时回传服务端（内部有 600ms 合并，连续点多条也只发一次）
        ClientPrefsSync.schedulePush(context)
    }

    private fun writeIds(context: Context, ids: Set<Long>) {
        // 超出上限时丢最旧的（id 最小），保留最新的那些
        val trimmed = if (ids.size <= MAX_READ_IDS) ids else {
            ids.sorted().takeLast(MAX_READ_IDS).toSet()
        }
        val jsonArray = org.json.JSONArray()
        trimmed.forEach { jsonArray.put(it) }
        getPrefs(context).edit().putString(KEY_READ_IDS, jsonArray.toString()).apply()
    }
}
