package com.pm.manager.terminal

import android.content.Context
import com.pm.manager.core.AppPrefs

/** 后端地址与卡密会话配置。从原来的 TerminalClient.kt 拆出，避免一个文件塞四五个职责。 */
object SiteConfig {
    const val BASE_URL = BuildConfig.PM_API_BASE_URL
    const val WS_HOST = BuildConfig.PM_WS_HOST
    const val WS_PORT = BuildConfig.PM_WS_PORT
    const val TOKEN = "change-me"

    /** 权限等级常量：A=管理员（系统根），B/C=绑定各自站点目录。 */
    const val PERM_ADMIN = "A"

    @Volatile var cardKey: String = ""
    @Volatile var cardPassword: String = ""

    /** 当前卡密权限等级："A"/"B"/"C"。 */
    @Volatile var perm: String = "B"

    val isAdmin: Boolean get() = perm.equals(PERM_ADMIN, ignoreCase = true)

    /** 卡密是否已就绪（决定能否发起需要鉴权的请求）。 */
    val hasCard: Boolean get() = cardKey.isNotBlank() && cardPassword.isNotBlank()

    fun loadFromPrefs(context: Context) {
        AppPrefs.init(context)
        val prefs = AppPrefs.raw()
        cardKey = prefs.getString("card_key", "") ?: ""
        cardPassword = prefs.getString("card_password", "") ?: ""
        perm = prefs.getString("card_perm", "B") ?: "B"
    }

    fun saveCard(context: Context, card: String, password: String) {
        cardKey = card
        cardPassword = password
        prefs(context).edit()
            .putString("card_key", card)
            .putString("card_password", password)
            .apply()
    }

    fun setPerm(context: Context, permValue: String) {
        perm = if (permValue.isBlank()) "B" else permValue
        prefs(context).edit().putString("card_perm", perm).apply()
    }

    fun setActivated(context: Context, activated: Boolean) {
        prefs(context).edit().putBoolean("activated", activated).apply()
    }

    fun isActivated(context: Context): Boolean =
        AppPrefs.raw().getBoolean("activated", false)

    /** 清空卡密与本地状态（退出登录 / 卡失效被踢）。只移除卡密相关键，保留主题等设置。 */
    fun clear(context: Context) {
        cardKey = ""
        cardPassword = ""
        perm = "B"
        prefs(context).edit()
            .remove("card_key")
            .remove("card_password")
            .remove("card_perm")
            .remove("activated")
            .apply()
        AppPrefs.init(context)
    }

    private fun prefs(context: Context): android.content.SharedPreferences {
        AppPrefs.init(context)
        return AppPrefs.raw()
    }
}
