package com.pm.manager.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TermResult(val output: String, val cwd: String)

/** 终端命令执行客户端（走 /api/exec）。 */
class TerminalClient {

    suspend fun execute(command: String, cwd: String = ""): TermResult = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext TermResult("", cwd)
        val res = SiteApi.postJson("/api/exec", mapOf("command" to command, "cwd" to cwd))
        if (res.optBoolean("ok", false)) {
            TermResult(res.optString("output", "(无输出)"), res.optString("cwd", cwd))
        } else {
            val msg = res.optString("error", "").ifEmpty { res.optString("output", "未知错误") }
            TermResult("执行失败：$msg", res.optString("cwd", cwd))
        }
    }
}
