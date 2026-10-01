package com.aurora.chat.host

import android.content.Context
import android.content.SharedPreferences

/**
 * 虚拟主机连接配置（对接 server/site_server.py）。
 * 主机地址与 token 为部署常量（与 site_server 启动时的 --token 一致），不在登录框让用户填，
 * 与本地站点管理器保持一致。登录只采集：绑定域名 + 卡密 + 密码。
 */
object HostConfig {
    const val BASE_URL = "http://www.YOUR_SERVER_DOMAIN:3001"
    const val TOKEN = "change-me" // 与 site_server --token 一致；如需自定义改这里即可

    @Volatile var cardKey: String = ""
    @Volatile var cardPassword: String = ""
    @Volatile var perm: String = "B"

    val isAdmin: Boolean get() = perm.equals("A", ignoreCase = true)
    val hasCard: Boolean get() = cardKey.isNotBlank() && cardPassword.isNotBlank()

    private const val PREFS = "aurora_host_prefs"
    private const val KEY_HOST_MODE = "in_host_mode"
    private lateinit var appCtx: Context

    fun loadFromPrefs(context: Context) {
        appCtx = context.applicationContext
        val p = prefs(context)
        cardKey = p.getString("card_key", "") ?: ""
        cardPassword = p.getString("card_password", "") ?: ""
        perm = p.getString("card_perm", "B") ?: "B"
        HostSession.loggedIn.value = hasCard
        HostSession.perm.value = perm
        // 记住“是否停留在虚拟主机视图”，登录态有效时才恢复
        HostSession.inHostMode.value = hasCard && p.getBoolean(KEY_HOST_MODE, false)
    }

    /** 切换“虚拟主机视图”并持久化，离开工作区/重启后仍能保持。 */
    fun saveHostMode(value: Boolean) {
        HostSession.inHostMode.value = value
        runCatching { prefs(appCtx).edit().putBoolean(KEY_HOST_MODE, value).apply() }
    }

    fun saveCard(context: Context, card: String, password: String) {
        cardKey = card
        cardPassword = password
        prefs(context).edit()
            .putString("card_key", card)
            .putString("card_password", password)
            .apply()
        HostSession.loggedIn.value = true
    }

    fun setPerm(context: Context, permValue: String) {
        perm = if (permValue.isBlank()) "B" else permValue
        HostSession.perm.value = perm
        prefs(context).edit().putString("card_perm", perm).apply()
    }

    fun logout(context: Context) {
        cardKey = ""
        cardPassword = ""
        perm = "B"
        HostSession.markLoggedOut()
        prefs(context).edit()
            .remove("card_key").remove("card_password").remove("card_perm").remove(KEY_HOST_MODE).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
