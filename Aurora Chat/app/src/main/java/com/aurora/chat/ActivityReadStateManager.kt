package com.aurora.chat

import android.content.Context
import android.content.SharedPreferences

object ActivityReadStateManager {
    private const val PREF_NAME = "aurora_activity_read"
    private const val KEY_READ_IDS = "read_activity_ids"

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

    private fun saveReadIds(context: Context, ids: Set<Long>) {
        val prefs = getPrefs(context)
        val jsonArray = org.json.JSONArray()
        ids.forEach { jsonArray.put(it) }
        prefs.edit().putString(KEY_READ_IDS, jsonArray.toString()).apply()
    }
}