package com.aurora.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 日志级别（严重程度递增）。顶层枚举，供同包内错误日志页/崩溃拦截管理器直接引用。
 */
enum class LogLevel(val label: String, val shortLabel: String) {
    DEBUG("调试", "DBG"),
    WARNING("警告", "WRN"),
    ERROR("错误", "ERR"),
    FATAL("致命", "FTL")
}

/**
 * 统一日志系统（重写版）。
 *
 * 设计要点：
 * - 所有日志（含崩溃）统一写入 filesDir/crash_logs/，文件名 {级别}_{yyyyMMdd_HHmmss_SSS}.log。
 * - 写入为同步 + flush，确保崩溃场景下日志在 killProcess 前已落盘。
 * - 写入过程自身受保护，写盘失败仅回退到系统 Log，绝不因打日志而引发二次崩溃。
 * - 对外公开 API（getAllFiles / getLevel / friendlyTime / getTag / oneLineSummary / hasStack /
 *   markAllRead / refreshUnread / getCrashAndErrorFiles / clearAll / createShareIntent / unreadCount）
 *   保持兼容，供错误日志页与崩溃拦截管理器使用。
 */
object ErrorReporter {

    private const val TAG = "ErrorReporter"
    private const val DIR_NAME = "crash_logs"
    private const val PREFS_READ = "aurora_log_read"
    private const val KEY_LAST_READ = "last_read_ms"

    /** 不通知后端的标签 */
    private val SILENT_TAGS = setOf("Network", "Download", "API")

    private var logDir: File? = null
    private var app: android.app.Application? = null
    private val writeLock = Any()

    // ======================== 未读状态 ========================

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount

    fun init(application: android.app.Application) {
        app = application
        logDir = File(application.filesDir, DIR_NAME)
        if (!logDir!!.exists()) logDir!!.mkdirs()
        refreshUnread()
    }

    // ======================== 公开记录方法 ========================

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.WARNING, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable)
    fun fatal(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.FATAL, tag, message, throwable)

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        // 协程取消异常不作为错误记录（属于正常生命周期）
        if (isCancellation(throwable)) return
        val dir = logDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            val file = File(dir, "${level.name}_${fmt.format(Date())}.log")
            synchronized(writeLock) { writeFile(file, level, tag, message, throwable) }
            Log.w(TAG, "[${level.shortLabel}][$tag] $message")
            refreshUnread()
            if (level >= LogLevel.ERROR && throwable != null && tag !in SILENT_TAGS) {
                notifyBackend(level, tag, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入日志失败: ${e.message}")
        }
    }

    private fun isCancellation(t: Throwable?): Boolean {
        var cur = t
        while (cur != null) {
            if (cur is kotlinx.coroutines.CancellationException) return true
            cur = cur.cause
        }
        return false
    }

    // ======================== 文件读取 ========================

    /** 所有日志文件（按时间倒序） */
    fun getAllFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { f -> LEVEL_PREFIXES.any { f.name.startsWith(it) } && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 按级别筛选 */
    fun getFilesByLevel(level: LogLevel): List<File> =
        getAllFiles().filter { it.name.startsWith(level.name) }

    /** 从文件名解析级别 */
    fun getLevel(file: File): LogLevel? =
        LogLevel.values().find { file.name.startsWith(it.name) }

    /** 读取文件内容 */
    fun readFile(file: File): String = try { file.readText() } catch (e: Exception) { "读取失败: ${e.message}" }

    /** 从文件内容提取标签 */
    fun getTag(file: File): String = try {
        file.readText().lines().find { it.startsWith("标签: ") }
            ?.removePrefix("标签: ")?.trim() ?: "其他"
    } catch (_: Exception) { "其他" }

    /** 友好时间 "6月27日 06:13" */
    fun friendlyTime(file: File): String = try {
        val name = file.nameWithoutExtension
        val parts = name.split("_")
        if (parts.size >= 3) {
            val d = parts[1]; val t = parts[2]
            if (d.length == 8 && t.length >= 4) {
                val m = d.substring(4, 6).toIntOrNull() ?: return name
                val day = d.substring(6, 8).toIntOrNull() ?: return name
                val h = t.substring(0, 2).toIntOrNull() ?: return name
                val min = t.substring(2, 4).toIntOrNull() ?: return name
                "${m}月${day}日 ${h}:${min.toString().padStart(2, '0')}"
            } else name
        } else name
    } catch (_: Exception) { file.name }

    /** 一行摘要 */
    fun oneLineSummary(file: File): String = try {
        val text = file.readText()
        val tag = text.lines().find { it.startsWith("标签: ") }?.removePrefix("标签: ") ?: ""
        val msg = text.lines().find { it.startsWith("消息: ") }?.removePrefix("消息: ") ?: ""
        val level = getLevel(file)
        val time = friendlyTime(file)
        val p = level?.shortLabel ?: ""
        when {
            tag.isNotEmpty() && msg.isNotEmpty() -> "$time [$p][$tag] $msg"
            msg.isNotEmpty() -> "$time [$p] $msg"
            else -> "$time [$p]"
        }
    } catch (_: Exception) { friendlyTime(file) }

    /** 是否含堆栈 */
    fun hasStack(file: File): Boolean = try { file.readText().contains("at ") } catch (_: Exception) { false }

    // ======================== 已读/未读 ========================

    fun markAllRead() {
        _unreadCount.value = 0
        try {
            app?.getSharedPreferences(PREFS_READ, Context.MODE_PRIVATE)
                ?.edit()?.putLong(KEY_LAST_READ, System.currentTimeMillis())?.apply()
        } catch (_: Exception) {}
    }

    fun refreshUnread() {
        try {
            val lastRead = app?.getSharedPreferences(PREFS_READ, Context.MODE_PRIVATE)
                ?.getLong(KEY_LAST_READ, 0L) ?: 0L
            _unreadCount.value = getCrashAndErrorFiles().count { it.lastModified() > lastRead }
        } catch (_: Exception) { _unreadCount.value = 0 }
    }

    /** 获取 ERROR + FATAL 级别的日志（含旧版 crash_/error_ 前缀兼容） */
    fun getCrashAndErrorFiles(): List<File> =
        getAllFiles().filter {
            it.name.startsWith("FATAL") || it.name.startsWith("ERROR") ||
                it.name.startsWith("crash_") || it.name.startsWith("error_")
        }

    // ======================== 清空 ========================

    fun clearAll() {
        try {
            logDir?.listFiles()?.forEach { it.delete() }
            markAllRead()
        } catch (_: Exception) {}
    }

    // ======================== 分享 ========================

    fun createShareIntent(context: Context): Intent {
        val all = getAllFiles()
        val sb = StringBuilder()
        sb.appendLine("Aurora Chat 错误报告")
        sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("共 ${all.size} 条")
        sb.appendLine()
        for (f in all.take(10)) {
            sb.appendLine("===== ${f.name} =====")
            sb.appendLine(f.readText())
        }
        return try {
            val report = File(context.cacheDir, "aurora_report.log")
            report.writeText(sb.toString())
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", report)
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Aurora Chat 错误报告")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Aurora Chat 错误报告")
                putExtra(Intent.EXTRA_TEXT, sb.toString())
            }
        }
    }

    // ======================== 内部 ========================

    /** 写入日志文件（统一格式，含堆栈）。同步调用，写完 flush。 */
    private fun writeFile(file: File, level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val now = Date()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val pkgInfo = try {
            app?.packageManager?.getPackageInfo(app?.packageName ?: "", 0)
        } catch (_: Exception) { null }
        val verName = pkgInfo?.versionName ?: "unknown"
        val verCode = if (Build.VERSION.SDK_INT >= 28) {
            pkgInfo?.longVersionCode ?: 0L
        } else {
            @Suppress("DEPRECATION") pkgInfo?.versionCode?.toLong() ?: 0L
        }
        FileWriter(file, false).use { w ->
            w.write("===================\n")
            w.write("级别: ${level.label}\n")
            w.write("时间: ${fmt.format(now)}\n")
            w.write("标签: $tag\n")
            w.write("设备: ${Build.MANUFACTURER} ${Build.MODEL} | API ${Build.VERSION.SDK_INT}\n")
            w.write("版本: $verName ($verCode)\n")
            w.write("===================\n\n")
            w.write("消息: $message\n")
            if (throwable != null) {
                w.write("\n--- 堆栈 ---\n")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                w.write(sw.toString())
                var cause = throwable.cause
                var depth = 1
                while (cause != null && depth < 5) {
                    w.write("\n--- Caused by ($depth) ---\n")
                    w.write("${cause.javaClass.name}: ${cause.message}\n")
                    val sw2 = StringWriter()
                    cause.printStackTrace(PrintWriter(sw2))
                    w.write(sw2.toString())
                    cause = cause.cause
                    depth++
                }
            }
            w.write("\n--- END ---\n")
            w.flush()
        }
    }

    /** 通知后端服务器 */
    private fun notifyBackend(level: LogLevel, tag: String, message: String) {
        try {
            val serverUrl = com.aurora.chat.data.api.AuroraApi.serverUrl
            val body = org.json.JSONObject().apply {
                put("level", level.label); put("tag", tag)
                put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("msg", message.take(500))
            }
            Thread {
                try {
                    val req = okhttp3.Request.Builder()
                        .url("$serverUrl/api/report-error")
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
        } catch (_: Exception) {}
    }

    /** 所有合法日志文件名前缀 */
    private val LEVEL_PREFIXES = LogLevel.values().map { it.name } + listOf("crash_", "error_")
}
