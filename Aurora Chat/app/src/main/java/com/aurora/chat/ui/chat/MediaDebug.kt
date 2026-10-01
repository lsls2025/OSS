package com.aurora.chat.ui.chat

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 媒体（图片/视频）加载诊断工具。
 * 同时输出到 Logcat（tag AuroraMedia）与 App 私有目录下的 aurora_media_debug.log，
 * 方便排查「发出图片正常、重进对话变空气泡」这类问题。
 */
object MediaDebug {
    private val lock = Any()

    // 通过反射读取 BuildConfig.DEBUG（该 module 未开启 buildConfig 生成，避免编译期依赖）
    private val isDebug: Boolean by lazy {
        try {
            Class.forName("com.aurora.chat.BuildConfig").getField("DEBUG").getBoolean(null)
        } catch (_: Exception) { false }
    }

    fun log(ctx: Context?, tag: String, message: String) {
        // 诊断仅在 debug 构建启用：release 下每次加载会逐条写 Logcat + 落盘，拖动大列表时造成主线程卡顿
        if (!isDebug) return
        Log.e("AuroraMedia", "[$tag] $message")
        try {
            if (ctx == null) return
            val file = File(ctx.filesDir, "aurora_media_debug.log")
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
            synchronized(lock) {
                file.appendText("$ts [$tag] $message\n")
            }
        } catch (_: Exception) {
        }
    }

    /** 读取日志内容（用于调试界面展示） */
    fun readLog(ctx: Context): String {
        return try {
            val file = File(ctx.filesDir, "aurora_media_debug.log")
            if (file.exists()) file.readText() else "(无日志)"
        } catch (e: Exception) {
            "(读取失败: ${e.message})"
        }
    }

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, "aurora_media_debug.log").delete()
        } catch (_: Exception) {
        }
    }
}
