package com.aurora.chat

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模型日志：把「一次 AI 请求」的全过程按行追加进**单个滚动文件**，供用户在
 * 「AI 使用 → 查看模型日志」里直接看（长任务"为什么自己停了/断了"就看这里）。
 *
 * 【为什么不复用 [ErrorReporter]】
 * ErrorReporter 是「**一个事件一个文件**」（`ERROR_20261010_101010_123.log`），适合崩溃、错误这类
 * **少量且重要**的事件；而模型日志是**高频流水**（每轮、每次工具调用都写），一事件一文件会瞬间
 * 产生成千上万个小文件。所以这里用「单文件追加 + 超过上限只保留尾部」。
 *
 * 但重要**报错**仍会同时写进 ErrorReporter —— 这样「发送日志给开发者」能把关键报错一起带走。
 *
 * 记录内容（用户要求：不只是"用户发了什么"，还要放重要的运行信息）：
 *  · REQ   新一轮请求：模型/模式/思考深度/工具轮数上限
 *  · ROUND 每一轮：轮次、工具数、finish_reason、是否被截断续写
 *  · STOP  结束原因：正常交付 / 轮数上限 / 手动停止 / 上游失败 / 未交付
 *  · ERR   异常与报错
 * 所有写入都在 try 里，**打日志本身绝不允许影响对话**。
 */
object ModelLogger {

    private const val TAG = "ModelLogger"
    private const val DIR_NAME = "model_log"
    private const val FILE_NAME = "model.log"

    /** 单文件上限（字节）。超过后只保留尾部一半，避免无限增长。 */
    private const val MAX_BYTES = 512L * 1024L

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    private fun logFile(ctx: Context): File {
        val dir = File(ctx.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun info(ctx: Context, tag: String, msg: String) = write(ctx, "INFO", tag, msg)

    fun warn(ctx: Context, tag: String, msg: String) = write(ctx, "WARN", tag, msg)

    /** 报错：写模型日志的同时也进 ErrorReporter（供「发送日志给开发者」带走）。 */
    fun error(ctx: Context, tag: String, msg: String, t: Throwable? = null) {
        val extra = t?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        write(ctx, "ERR ", tag, msg + extra)
        runCatching { ErrorReporter.error("Model", "$tag: $msg", t) }
    }

    private fun write(ctx: Context, level: String, tag: String, msg: String) {
        try {
            val line = "${timeFmt.format(Date())} [$level] [$tag] $msg\n"
            synchronized(lock) {
                val f = logFile(ctx)
                // 超过上限：只保留尾部一半（滚动，而不是把日志清空）
                if (f.isFile && f.length() > MAX_BYTES) {
                    val keep = runCatching { f.readText().takeLast((MAX_BYTES / 2).toInt()) }.getOrNull()
                    if (keep != null) f.writeText(keep) else f.delete()
                }
                f.appendText(line)
            }
        } catch (e: Exception) {
            // 打日志失败只回退系统 Log，绝不因日志引发二次问题
            Log.w(TAG, "写模型日志失败: ${e.message}")
        }
    }

    /** 读取全部日志行（供查看页展示）。解析失败返回空表。 */
    fun readLines(ctx: Context): List<String> = try {
        val f = logFile(ctx)
        if (f.isFile) f.readLines().filter { it.isNotBlank() } else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "读模型日志失败: ${e.message}")
        emptyList()
    }

    fun clear(ctx: Context) {
        try {
            synchronized(lock) { logFile(ctx).takeIf { it.isFile }?.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "清空模型日志失败: ${e.message}")
        }
    }

    /** 日志文件绝对路径（查看页直接显示，用来确认"到底写没写、写到哪了"） */
    fun filePath(ctx: Context): String = try { logFile(ctx).absolutePath } catch (_: Exception) { "—" }

    /** 当前日志文件大小（字节）；0 表示还没写入过 */
    fun sizeBytes(ctx: Context): Long = try {
        logFile(ctx).let { if (it.isFile) it.length() else 0L }
    } catch (_: Exception) { 0L }

    /** 日志文件是否已有内容（供入口显示副标题） */
    fun hasLog(ctx: Context): Boolean = try { logFile(ctx).let { it.isFile && it.length() > 0 } } catch (_: Exception) { false }
}
