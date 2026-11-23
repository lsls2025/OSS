package com.aurora.chat

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 登录桥接：供 Aurora Agent（asia.aurora.agent）复用本 App 已登录的身份
 *
 * 只做一件事：读取本 App 已持久化的登录态（aurora_login.auth_token），通过 setResult 回传
 * 不拉起任何界面、不触碰 IM 逻辑、不改变本 App 的登录状态，因此不会把本 App 顶下线
 * 未登录时返回 RESULT_CANCELED，由调用方自行兜底
 */
class LoginBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val prefs = getSharedPreferences("aurora_login", MODE_PRIVATE)
            val token = prefs.getString("auth_token", null)
            val uid = prefs.getLong("user_id", 0L)
            val email = prefs.getString("user_email", "") ?: ""
            val qq = prefs.getString("user_qq", "") ?: ""
            if (!token.isNullOrBlank()) {
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putExtra("auth_token", token)
                        putExtra("user_id", uid)
                        putExtra("user_email", email)
                        putExtra("user_qq", qq)
                    }
                )
            } else {
                setResult(RESULT_CANCELED)
            }
        } catch (e: Throwable) {
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
