package com.aurora.chat.ui.chat

import android.content.Context
import com.aurora.chat.data.api.AuroraApi

/**
 * 备注云同步：桌面端与手机端共用服务器 user-store（key=chat_remarks）里同一份备注映射。
 * 服务器映射的键统一为「会话 id」：好友为正（=friendId），群为负（=-(groupId+1000)）。
 * 手机端本地 SharedPreferences 的键约定：好友 remark_$friendId；群 remark_group_${groupId}。
 */
object RemarkCloudSync {

    private const val PREF = "aurora_friend_settings"

    /** 由会话 id 换算手机端本地备注键 */
    private fun localKey(convId: Long): String =
        if (convId < 0) "remark_group_${-convId - 1000}" else "remark_$convId"

    /** 进入会话列表时调用：拉取服务器备注并合并到本地（不覆盖本地更晚设置的值的主机侧），随后可刷新列表 */
    suspend fun loadAndApply(context: Context) {
        val server = AuroraApi.fetchServerRemarks() ?: return
        if (server.isEmpty()) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        server.forEach { (convId, remark) ->
            if (remark.isNotBlank()) editor.putString(localKey(convId), remark)
        }
        editor.apply()
    }

    /** 设置单个会话备注：先落本地即时生效，再读服务器整体映射、更新该会话后整体回写 */
    suspend fun pushToServer(context: Context, convId: Long, remark: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (remark.isBlank()) prefs.edit().remove(localKey(convId)).apply()
        else prefs.edit().putString(localKey(convId), remark).apply()

        val server = AuroraApi.fetchServerRemarks() ?: return
        val next = server.toMutableMap()
        if (remark.isBlank()) next.remove(convId) else next[convId] = remark
        AuroraApi.saveServerRemarks(next)
    }
}