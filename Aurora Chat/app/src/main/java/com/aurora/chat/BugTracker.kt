package com.aurora.chat

import android.util.Log

object BugTracker {

    private const val TAG = "BugTracker"

    /** 记录一个非致命 bug（带异常） */
    fun record(module: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message | ${throwable.javaClass.simpleName}: ${throwable.message}" else message
        Log.w(TAG, "[$module] $fullMsg")
        ErrorReporter.error(module, fullMsg, throwable)
    }

    /** 记录一个条件断言失败（逻辑走到非预期分支） */
    fun assert(condition: Boolean, module: String, description: String) {
        if (!condition) {
            val msg = "断言失败: $description"
            Log.w(TAG, "[$module] $msg")
            ErrorReporter.warn(module, msg)
        }
    }

    /** 记录一个警告（业务异常，非代码异常） */
    fun warn(module: String, description: String) {
        Log.w(TAG, "[$module] $description")
        ErrorReporter.warn(module, description)
    }
}
