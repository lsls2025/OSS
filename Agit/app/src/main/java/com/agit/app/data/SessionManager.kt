package com.agit.app.data

import android.content.Context
import android.content.SharedPreferences
import com.agit.app.data.model.User
import com.google.gson.Gson

class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("agit_session", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(user: User) {
        prefs.edit()
            .putString(KEY_USER, gson.toJson(user))
            .putString(KEY_TOKEN, user.token)
            .putLong(KEY_LOGIN_AT, System.currentTimeMillis())
            .putLong(KEY_AVATAR_AT, System.currentTimeMillis())
            .apply()
    }

    /** 头像本地缓存是否已超过刷新周期（默认 1 小时）。 */
    fun avatarStale(): Boolean {
        val at = prefs.getLong(KEY_AVATAR_AT, 0)
        return System.currentTimeMillis() - at > AVATAR_REFRESH_MS
    }

    fun currentUser(): User? {
        val json = prefs.getString(KEY_USER, null) ?: return null
        return try { gson.fromJson(json, User::class.java) } catch (_: Exception) { null }
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun isLoggedIn(): Boolean = token() != null

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_USER = "user_json"
        private const val KEY_TOKEN = "token"
        private const val KEY_LOGIN_AT = "login_at"
        private const val KEY_AVATAR_AT = "avatar_at"
        private const val AVATAR_REFRESH_MS = 60 * 60 * 1000L
    }
}
