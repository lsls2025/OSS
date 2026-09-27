package com.pm.manager.terminal

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 纯 TCP 长连接推送客户端（替代 WebSocket）。
 *
 * 协议（换行分隔的纯文本行）：
 *  客户端->服务器: AUTH <card>|<password>
 *  服务器->客户端: OK / ERR <原因>
 *  客户端->服务器: PATH <relpath>
 *  服务器->客户端: DATA <json: items...>
 *
 * 服务器只在订阅目录内容变化时推送 DATA，订阅后立即推一次。
 *
 * 性能优化：
 * - 原来 `readLine` 在裸 socket 上逐字节 `input.read()`，一条 JSON 几千字节就是几千次系统调用，
 *   现在套 [BufferedInputStream]；写出同理套 [BufferedOutputStream]。
 * - 增加应用层心跳：NAT/运营商常在几分钟无流量后静默掐断，客户端却以为还连着，
 *   状态条一直显示"已连接"。现在 45 秒无数据就发 PING 探活，断了能立刻发现并重连。
 * - 所有 socket 写入切到单线程 executor，订阅操作不再占用/阻塞主线程。
 */
class WsClient(
    private val host: String,
    private val port: Int,
    private val onMessage: (JSONObject) -> Unit,
    private val onError: (String) -> Unit,
    private val onConnected: (() -> Unit)? = null
) {
    private companion object {
        const val TAG = "TcpPusher"
        const val CONNECT_TIMEOUT = 10_000
        /** 读超时：既是探活间隔，也是断连发现延迟的上限。 */
        const val READ_TIMEOUT = 45_000
        const val HEARTBEAT = "PING"
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tcp-push").apply { isDaemon = true }
    }
    private val alive = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    @Volatile private var sink: OutputStream? = null
    @Volatile private var currentPath: String = ""

    fun start() {
        if (!alive.compareAndSet(false, true)) return
        executor.execute { reconnectLoop() }
    }

    fun stop() {
        alive.set(false)
        closeQuietly()
        executor.shutdownNow()
    }

    /** 订阅目录变化时调用，切换推送目标目录。异步发送，不阻塞调用线程。 */
    fun subscribe(path: List<String>) {
        currentPath = path.joinToString("/")
        if (!alive.get()) return
        executor.execute { sendLineSafe("PATH $currentPath") }
    }

    // ==================== 内部 ====================

    private fun reconnectLoop() {
        var backoff = 1500L
        while (alive.get()) {
            try {
                connectAndLoop()
                backoff = 1500L
            } catch (e: Exception) {
                if (alive.get()) {
                    Log.e(TAG, "连接异常: ${e.message}")
                    onError(e.message ?: "连接失败")
                }
            }
            if (!alive.get()) break
            sleepQuietly(backoff)
            backoff = (backoff * 2).coerceAtMost(12_000L)
        }
    }

    private fun connectAndLoop() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
        s.soTimeout = READ_TIMEOUT
        s.tcpNoDelay = true
        socket = s
        Log.i(TAG, "TCP 已连接 $host:$port")

        val input: InputStream = BufferedInputStream(s.getInputStream(), 8192)
        val out: OutputStream = BufferedOutputStream(s.getOutputStream(), 4096)
        sink = out

        try {
            // 鉴权
            sendLine(out, "AUTH ${SiteConfig.cardKey}|${SiteConfig.cardPassword}")
            val authResp = readLine(input)
            if (authResp == "OK") {
                Log.i(TAG, "鉴权成功")
                onConnected?.invoke()
            } else {
                Log.e(TAG, "鉴权失败: $authResp")
                if (authResp.startsWith("ERR")) onError(authResp.removePrefix("ERR").trim())
                throw java.io.IOException("鉴权失败")
            }

            // 订阅初始目录（重连后要恢复之前订阅的目录，否则推送的是根目录）
            sendLine(out, "PATH $currentPath")

            while (alive.get()) {
                val line = try {
                    readLine(input)
                } catch (e: SocketTimeoutException) {
                    // 一段时间没收到推送：发心跳探活，如果连接已死，下一次写/读会抛异常触发重连
                    sendLine(out, HEARTBEAT)
                    continue
                }
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("DATA ") -> {
                        runCatching { onMessage(JSONObject(line.substring(5))) }
                            .onFailure { Log.e(TAG, "解析推送失败: ${it.message}") }
                    }
                    line.startsWith("ERR ") -> {
                        val reason = line.removePrefix("ERR").trim()
                        Log.e(TAG, "服务器错误: $reason")
                        if (reason.contains("失效") || reason.contains("过期") || reason.contains("鉴权")) {
                            SessionState.markExpired()
                        } else {
                            onError(reason)
                        }
                    }
                    line == "PONG" -> Unit
                }
            }
        } finally {
            sink = null
            socket = null
            closeQuietly()
        }
    }

    private fun sendLineSafe(line: String) {
        val out = sink
        if (out == null) {
            // 还没连上：等连上后 connectAndLoop 会用 currentPath 自动补发，这里忽略即可
            return
        }
        try {
            sendLine(out, line)
        } catch (e: Exception) {
            Log.e(TAG, "发送失败: ${e.message}")
        }
    }

    private fun sendLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun readLine(input: InputStream): String {
        if (!alive.get()) throw java.io.IOException("已停止")
        val sb = StringBuilder(256)
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("连接断开")
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
