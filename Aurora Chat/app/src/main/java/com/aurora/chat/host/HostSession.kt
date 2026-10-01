package com.aurora.chat.host

import androidx.compose.runtime.mutableStateOf

/**
 * 虚拟主机登录与会话状态（全局单例，供工作区面板与主机面板共享）。
 * 卡密校验走 Aurora Chat 自建后端 /api/validate-card-key，密码不进聊天文本、不泄露给第三方 AI。
 */
object HostSession {
    /** 是否已用卡密登录（持久化在 prefs，启动时由 HostConfig.loadFromPrefs 恢复）。 */
    val loggedIn = mutableStateOf(false)
    /** 是否处于“虚拟主机目录”视图（true=显示主机面板，false=显示工作区面板）。 */
    val inHostMode = mutableStateOf(false)
    /** 当前卡密权限等级："A"/"B"/"C"。 */
    val perm = mutableStateOf("B")

    fun markLoggedOut() {
        loggedIn.value = false
        inHostMode.value = false
        runCatching { HostConfig.saveHostMode(false) }
    }
}
