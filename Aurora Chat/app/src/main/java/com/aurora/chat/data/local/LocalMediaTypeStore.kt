package com.aurora.chat.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 记录"本机已成功发送"的媒体消息真实类型（serverId -> mediaType）。
 *
 * 背景：后端在保存文件类消息时，会把所有文件统一返回成 media_type="jpg"，
 * 导致客户端重新拉取/推送时把 APK/TXT 等误当成图片。这里在发送成功时把真实类型
 * 落盘，加载消息时用它覆盖服务端返回的错误类型，不依赖 URL 后缀或后端行为。
 */
object LocalMediaTypeStore {
    private const val PREFS = "local_media_type"
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun put(serverId: Long, type: String) {
        if (serverId > 0 && type.isNotEmpty()) {
            prefs?.edit()?.putString(serverId.toString(), type)?.apply()
        }
    }

    fun get(serverId: Long): String? =
        if (serverId > 0) prefs?.getString(serverId.toString(), null) else null
}
