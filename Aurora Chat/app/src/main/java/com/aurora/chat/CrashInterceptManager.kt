package com.aurora.chat

import android.content.Context
import android.content.Intent

/**
 * 闪退拦截 —— 基于日志文件的简单检测。
 *
 * 原理：应用启动后检查是否存在最后查看时间之后产生的 FATAL 日志，
 * 若有则说明发生过闪退。
 */
object CrashInterceptManager {

    private const val PREFS = "crash_intercept_v2"
    private const val KEY_LAST_SEEN = "last_seen_ms"

    /** 是否有新闪退（自上次标记后产生的 FATAL 日志） */
    fun hasPendingCrash(context: Context): Boolean {
        val lastSeen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SEEN, 0L)
        return ErrorReporter.getFilesByLevel(LogLevel.FATAL).any { it.lastModified() > lastSeen }
    }

    /** 标记已查看 */
    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
    }

    /** 分享错误日志到邮箱 */
    fun shareErrorLogs(context: Context) {
        try {
            val report = ErrorReporter.createShareIntent(context)
            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf("YOUR_DEVELOPER_EMAIL"))
                putExtra(Intent.EXTRA_SUBJECT, "Aurora Chat 错误报告")
                putExtra(Intent.EXTRA_TEXT, "请查看附件中的错误日志")
                report.extras?.getParcelable<android.net.Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(Intent.createChooser(emailIntent, "发送错误日志"))
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "无法发送邮件", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
