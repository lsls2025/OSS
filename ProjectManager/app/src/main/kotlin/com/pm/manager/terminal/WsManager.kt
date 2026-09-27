package com.pm.manager.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 应用级纯 TCP 长连接管理。激活后即拉起，不依赖任何界面生命周期。
 * 服务器推送的目录列表直接透传给当前界面（只更新列表，绝不改变界面路径）。
 */
object WsManager {
    private const val TAG = "WsManager"

    @Volatile private var client: WsClient? = null
    @Volatile private var currentPath: List<String> = emptyList()
    @Volatile private var started = false
    @Volatile private var restartJob: Job? = null

    /** 收到目录推送时的回调，由主界面注册。 */
    @Volatile var onListing: ((JSONObject) -> Unit)? = null

    fun ensureStarted() {
        if (started && client != null) return
        if (SiteConfig.cardKey.isBlank()) {
            Log.e(TAG, "卡密为空，无法建立长连接")
            SessionState.setPushStatus("卡密为空，无法连接")
            return
        }
        started = true
        Log.i(TAG, "启动 TCP 推送连接 ${SiteConfig.WS_HOST}:${SiteConfig.WS_PORT}")
        SessionState.setPushStatus("正在连接...")
        client = WsClient(
            host = SiteConfig.WS_HOST,
            port = SiteConfig.WS_PORT,
            onMessage = { json ->
                if (json.optBoolean("expired", false)) {
                    SessionState.markExpired()
                    return@WsClient
                }
                onListing?.invoke(json)
            },
            onError = { msg ->
                Log.e(TAG, "推送连接错误: $msg")
                SessionState.setPushStatus(if (msg.contains("失效") || msg.contains("鉴权")) "卡密失效" else "长连接断连")
            },
            onConnected = {
                SessionState.setPushStatus("已连接")
                // 重连后补发一次订阅，否则推送的是根目录
                val p = currentPath
                if (p.isNotEmpty()) client?.subscribe(p)
            }
        ).also { it.start() }
    }

    fun subscribe(path: List<String>) {
        currentPath = path
        client?.subscribe(path)
    }

    /** 主动重连：先断开再重建，用于状态条上的"重试"按钮。 */
    fun restart() {
        restartJob?.cancel()
        restartJob = CoroutineScope(Dispatchers.Default).launch {
            stop()
            // 等旧 socket 的读循环彻底退出再重连，避免刚建好又被旧的 finally 关掉
            delay(300)
            ensureStarted()
        }
    }

    fun stop() {
        started = false
        client?.stop()
        client = null
        SessionState.setPushStatus("")
    }
}
