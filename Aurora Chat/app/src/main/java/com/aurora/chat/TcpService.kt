package com.aurora.chat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers

/** 跨进程/跨组件封禁状态通知（MainActivity 即时读取） */
object PendingBan {
    @Volatile
    var expiresAt = 0L
    @Volatile
    var reason = ""
    @Volatile
    var duration = 0L
    @Volatile
    var unbanPopupMessage = ""
    /** 标记是否有新的封禁待处理 */
    @Volatile
    var hasPending = false
    /** 标记是否有新的解封待处理 */
    @Volatile
    var hasUnban = false
    @Volatile
    var unbanMessage = ""
}

/** 入群申请TCP推送通知（ChatConversationScreen即时读取，替代轮询） */
object PendingJoinRequests {
    @Volatile
    var groupConvId = 0L
    @Volatile
    var hasUpdate = false
}

/** 消息撤回TCP推送通知 */
object PendingMessageRecalled {
    @Volatile
    var hasUpdate = false
    @Volatile
    var messageId = 0L
    @Volatile
    var senderName = ""
}

/** 开发者广播：单条删除（悄无声息）TCP推送通知 */
object PendingBroadcastDeleted {
    @Volatile
    var hasUpdate = false
    @Volatile
    var taskId = 0L
}

/** 开发者广播：撤回整个任务（停止未发 + 清理已发）TCP推送通知 */
object PendingBroadcastRecalled {
    @Volatile
    var hasUpdate = false
    @Volatile
    var taskId = 0L
}

/** 开发者测试：服务端删除整个会话，本地随之清空 */
object PendingConversationDeleted {
    @Volatile
    var hasUpdate = false
    @Volatile
    var friendId = 0L
}

/** 消息已读TCP推送通知 */
object PendingMessageRead {
    @Volatile
    var hasUpdate = false
    @Volatile
    var messageId = 0L
}

/** 社区动态TCP推送通知 */
object PendingCommunityActivity {
    @Volatile
    var hasUpdate = false
}

class TcpService : Service() {

    // 协程作用域：Service 生命周期管理，避免无限制的线程创建和阻塞
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        companion object {
        const val TCP_PORT = 5005
        const val TAG = "TcpService"
        const val MIN_RECONNECT_DELAY = 1000L   // 最小 1s
        const val MAX_RECONNECT_DELAY = 30000L  // 最大 30s
        const val PING_INTERVAL = 15000L        // 15s (TCP KeepAlive 已在 socket 层面开启)

        /** 当前服务是否在运行 */
        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        private var currentSocket: Socket? = null
            set(value) {
                field = value
                isConnected = value?.isConnected == true && !value.isClosed
            }

        @Volatile
        private var isConnectionRunning = false

        /** 当前 TCP 是否已连接 */
        @Volatile
        var isConnected = false
            private set

        /** stop() 设置的等待锁，onDestroy 时释放，防止 stop→start 竞态 */
        @Volatile
        private var pendingStopLatch: CountDownLatch? = null

        fun start(context: Context) {
            val intent = Intent(context, TcpService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val latch = CountDownLatch(1)
            pendingStopLatch = latch
            context.stopService(Intent(context, TcpService::class.java))
            // 等待 onDestroy 完成（最多 2 秒），确保旧连接完全关闭后再创建新连接
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        /** 检查连接状态，仅用于前台检测展示（重连由 runConnection 自循环处理，不再手动关 socket） */
        fun isTcpConnected(): Boolean = isConnected
    }

    private var running = false
    private var socket: Socket? = null
        set(value) {
            field = value
            currentSocket = value
        }
    private var userId: Long = 0
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // 新消息批量到达时对会话列表强制刷新做防抖合并（800ms），避免逐条重拉造成主线程抖动；
    // 实时消息仍由 notifyNewMessage 立即投递，仅「列表预览」刷新被合并
    private val conversationReloadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val conversationReloadRunnable = Runnable {
        com.aurora.chat.ui.viewmodel.ChatViewModel.loadConversations(force = true)
    }
    private fun requestConversationReloadDebounced() {
        conversationReloadHandler.removeCallbacks(conversationReloadRunnable)
        conversationReloadHandler.postDelayed(conversationReloadRunnable, 800)
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        NotificationHelper.createNotificationChannel(this)
        val prefs = getSharedPreferences("aurora_login", Context.MODE_PRIVATE)
        userId = prefs.getLong("user_id", 0)
        // 获取唤醒锁，息屏后保持 CPU 和网络
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "aurora_chat:tcp_wakelock")
            wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.buildForegroundNotification(this, "正在连接...")
        startForeground(1001, notification)
        running = true
        isServiceRunning = true

        // 防止重复启动多个连接线程
        if (isConnectionRunning) {
            Log.i(TAG, "连接线程已在运行，跳过重复启动")
            updateNotification(if (isConnected) "TCP已连接" else "TCP已断连")
            return START_STICKY
        }
        isConnectionRunning = true

        coroutineScope.launch { runConnection() }
        // 在线时间同步协程（前台 Service 存活期间持续运行）
        coroutineScope.launch {
            var syncCounter = 0
            while (isActive && userId > 0) {
                delay(1000)
                syncCounter++
                if (syncCounter >= 15 && isActive) { // 每15秒同步一次在线时间
                    syncCounter = 0
                    try {
                        val ot = com.aurora.chat.ui.profile.OnlineTimeTracker.getCurrentTotalSeconds(this@TcpService)
                        AuroraApi.syncUserStats(ot, 0)
                    } catch (_: Exception) {}
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        isServiceRunning = false
        isConnectionRunning = false
        isConnected = false
        coroutineScope.cancel()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        pendingStopLatch?.countDown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 发送心跳包并检查连接状态 */
    private fun sendPing(writer: java.io.OutputStreamWriter) {
        try {
            val ping = org.json.JSONObject().apply { put("type", "ping") }
            writer.write(ping.toString() + "\n")
            writer.flush()
        } catch (_: Exception) {
            throw java.net.SocketException("心跳发送失败")
        }
    }

    /** 更新前台通知文字（反映真实 TCP 连接状态） */
    private fun updateNotification(text: String) {
        try {
            val notification = NotificationHelper.buildForegroundNotification(this, text)
            startForeground(1001, notification)
        } catch (_: Exception) {}
    }

    private suspend fun runConnection() {
        var reconnectAttempts = 0
        while (coroutineScope.isActive && userId > 0) {
            try {
                Log.i(TAG, "正在连接 TCP 服务器: ${getTcpHost()}:$TCP_PORT")
                val sock = Socket()
                sock.connect(InetSocketAddress(getTcpHost(), TCP_PORT), 10000) // 10 秒超时
                sock.soTimeout = 8000 // 8 秒读取超时，尽快检测断线
                // 启用 TCP KeepAlive 内核探活
                sock.keepAlive = true
                sock.tcpNoDelay = true // 禁用 Nagle 算法，小包即时推送
                socket = sock
                Log.i(TAG, "TCP 连接成功")
                reconnectAttempts = 0  // 重置重连计数
                isConnected = true
                updateNotification("TCP已连接")

                // 发送认证
                val auth = JSONObject().apply {
                    put("type", "auth")
                    put("user_id", userId)
                }
                val writer = OutputStreamWriter(sock.getOutputStream())
                writer.write(auth.toString() + "\n")
                writer.flush()

                Log.i(TAG, "已发送认证: user_id=$userId")

                // 读取数据
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                var lastPing = System.currentTimeMillis()
                // 自适应心跳：记录当前使用的间隔
                var currentPingInterval = PING_INTERVAL

                while (running && !sock.isClosed) {
                    // 自适应心跳：根据网络类型调整间隔
                    val now = System.currentTimeMillis()
                    val isWifi = try {
                        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        val activeNetwork = cm.activeNetwork
                        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                    } catch (_: Exception) { false }
                    currentPingInterval = PING_INTERVAL // 统一使用 15s 心跳

                    if (now - lastPing > currentPingInterval) {
                        try {
                            val ping = JSONObject().apply { put("type", "ping") }
                            writer.write(ping.toString() + "\n")
                            writer.flush()
                            lastPing = now
                        } catch (_: Exception) { break }
                    }

                    // 读取一行（阻塞，带超时）
                    val line = try {
                        reader.readLine()
                    } catch (e: SocketTimeoutException) {
                        continue // 超时是正常的，继续心跳
                    }

                    if (line == null) {
                        Log.w(TAG, "连接已关闭")
                        break
                    }

                    if (line.isBlank()) continue

                    try {
                        val json = JSONObject(line)
                        val type = json.optString("type", "")

                        when (type) {
                            "auth_ok" -> Log.i(TAG, "TCP 认证成功")
                            "auth_denied" -> {
                                val msg = json.optString("message", "账号已被封禁")
                                val data = json.optJSONObject("data")
                                val reason = data?.optString("reason", "") ?: ""
                                val until = data?.optLong("until", 0L) ?: 0L
                                val unbanPopupMsg = data?.optString("unban_popup_message", "") ?: ""
                                Log.w(TAG, "认证被拒: $msg, reason=$reason")
                                if (until > 0) {
                                    val now = System.currentTimeMillis() / 1000
                                    val duration = until - now
                                    val prefs = getSharedPreferences("aurora_ban", Context.MODE_PRIVATE)
                                    prefs.edit().apply {
                                        putLong("user_id", userId)
                                        putString("reason", reason)
                                        putLong("expires_at", until)
                                        putLong("duration", duration.coerceAtLeast(0))
                                        putString("unban_popup_message", unbanPopupMsg)
                                        apply()
                                    }
                                }
                                // 即时通知 MainActivity（设置 PendingBan，让轮询立即检测到封禁）
                                if (until > 0) {
                                    val now = System.currentTimeMillis() / 1000
                                    PendingBan.hasPending = true
                                    PendingBan.expiresAt = until
                                    PendingBan.reason = reason
                                    PendingBan.duration = (until - now).coerceAtLeast(0)
                                    PendingBan.unbanPopupMessage = unbanPopupMsg
                                }
                                running = false
                                try { socket?.close() } catch (_: Exception) {}
                                stopForeground(true)
                                stopSelf()
                            }
                            "pong" -> { /* 心跳回复，忽略 */ }
                            "new_message" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val fromName = data.optString("from_username", "好友")
                                    val content = data.optString("content", "")
                                    val mediaType = data.optString("media_type", "")
                                    val fromUserId = data.optLong("from_user_id", 0L)
                                    var toUserId = data.optLong("to_user_id", 0L)
                                    val toUsername = data.optString("to_username", "")
                                    // 群聊：标题用群名称或来源名，内容用实际消息
                                    val isGroup = toUserId < 0
                                    val title = when {
                                        isGroup && toUsername.isNotBlank() -> toUsername
                                        isGroup -> fromName
                                        else -> fromName
                                    }
                                    // 已取消端到端加密：私聊推送的 content 为服务端解密后的明文，通知栏直接显示即可；
                                    // 仅当 content 仍残留历史 E2EE 密文前缀时才用中性占位，避免显示密文乱码。
                                    val isPrivatePlaintext = !isGroup && content.isNotBlank() &&
                                        (mediaType.isEmpty() || mediaType == "text")
                                    val body = when {
                                        isPrivatePlaintext -> if (content.startsWith("E2EE:v2:")) "[新消息]" else content
                                        content.isNotBlank() -> content
                                        mediaType == "image" || mediaType == "jpg" || mediaType == "png" || mediaType == "gif" -> "[图片]"
                                        mediaType == "video" || mediaType == "mp4" -> "[视频]"
                                        else -> "发来了一条新消息"
                                    }
                                    // 获取对话ID用于通知跳转
                                    val convId = if (isGroup) toUserId else fromUserId
                                    // 隐私：日志只记录元数据，绝不打印消息正文（明文或密文）
                                    Log.i(TAG, "收到新消息: from=$fromName, toUsername=$toUsername, media=$mediaType, convId=$convId, msgLen=${content.length}")

                                    // 累计未读：当前未打开该会话、且不是自己发出的消息（群聊自己发的消息不计入）
                                    if (convId != com.aurora.chat.ui.viewmodel.ChatViewModel.openConversationId
                                        && fromUserId != com.aurora.chat.data.api.AuroraApi.currentUserId) {
                                        com.aurora.chat.ui.viewmodel.ChatViewModel.addUnread(this, convId, 1)
                                    } else {
                                        Log.i(TAG, "未读不计: convId=$convId, openConv=${com.aurora.chat.ui.viewmodel.ChatViewModel.openConversationId}, from=$fromUserId, me=${com.aurora.chat.data.api.AuroraApi.currentUserId}")
                                    }

                                    // 拍拍消息（flash_duration == -1）不弹系统通知，只在对话内显示
                                    val flashDuration = data.optInt("flash_duration", 0)
                                    if (flashDuration != -1) {
                                        NotificationHelper.showMessageNotification(
                                            this, title, body,
                                            friendId = convId,
                                            friendName = if (isGroup && toUsername.isNotBlank()) toUsername else fromName
                                        )
                                    }
                                    // 通知 ChatViewModel 实时投递新消息；会话列表强制刷新做防抖合并
                                    ChatViewModel.notifyNewMessage()
                                    requestConversationReloadDebounced()
                                }
                            }
                            "message_read" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val msgId = data.optLong("message_id", 0L)
                                    Log.i(TAG, "消息已读通知: msgId=$msgId")
                                    com.aurora.chat.PendingMessageRead.hasUpdate = true
                                    com.aurora.chat.PendingMessageRead.messageId = msgId
                                }
                            }
                            "friend_request" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val fromName = data.optString("from_username", "未知用户")
                                    val greeting = data.optString("greeting", "")
                                    Log.i(TAG, "收到好友请求: $fromName")
                                    ChatViewModel.notifyFriendRequest()
                                    // 立即重新拉取好友请求列表，保证打开列表时能看到最新申请
                                    ChatViewModel.loadFriendRequests()
                                    NotificationHelper.showFriendRequestNotification(
                                        this, fromName, greeting
                                    )
                                }
                            }
                            "avatar_updated" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val changedUserId = data.optLong("user_id", 0)
                                    if (changedUserId > 0) {
                                        Log.i(TAG, "头像已变更: userId=$changedUserId")
                                        com.aurora.chat.data.local.AvatarCache.clearNoAvatar(changedUserId)
                                        com.aurora.chat.data.local.AvatarCache.clear(changedUserId, this@TcpService)
                                    }
                                }
                            }
                            "friend_accepted" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val name = data.optString("name", "")
                                    Log.i(TAG, "好友申请已通过: $name")
                                    ChatViewModel.notifyFriendRequest()
                                    ChatViewModel.loadConversations(force = true)
                                }
                            }
                            "account_deleted" -> {
                                val data = json.optJSONObject("data")
                                val msg = data?.optString("message", "账号已被删除") ?: "账号已被删除"
                                val shouldClearData = data?.optBoolean("clear_data", false) ?: false
                                Log.w(TAG, "账号被管理员删除: $msg, clearData=$shouldClearData")
                                // 清除所有本地数据
                                val prefsNames = listOf("aurora_login", "aurora_chat_prefs", "aurora_keepalive",
                                    "aurora_installed_pkgs", "aurora_ban", "aurora_downloads", "aurora_profile",
                                    "login_history")
                                for (name in prefsNames) {
                                    try { getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
                                }
                                com.aurora.chat.data.local.AvatarCache.clearAll()
                                AuroraApi.authToken = null
                                AuroraApi.currentUserId = 0
                                NotificationHelper.showAccountDeletedNotification(this@TcpService, msg)
                                running = false
                                try { socket?.close() } catch (_: Exception) {}
                                stopForeground(true)
                                stopSelf()
                            }
                            "account_unbanned" -> {
                                val data = json.optJSONObject("data")
                                val popupMsg = data?.optString("unban_popup_message", "") ?: ""
                                Log.w(TAG, "账号已被解封, unbanPopupMessage=$popupMsg")
                                val prefs = getSharedPreferences("aurora_ban", Context.MODE_PRIVATE)
                                prefs.edit().apply {
                                    putLong("expires_at", 0L) // 标记已解封
                                    putString("unban_popup_message", popupMsg)
                                    putInt("unban_trigger", 1 + prefs.getInt("unban_trigger", 0))
                                    apply()
                                }
                                // 即时通知 MainActivity（无需等待轮询）
                                PendingBan.hasUnban = true
                                PendingBan.unbanMessage = popupMsg
                            }
                            "join_request_update" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    PendingJoinRequests.groupConvId = data.optLong("conv_id", 0)
                                    PendingJoinRequests.hasUpdate = true
                                }
                            }
                            "message_recalled" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    PendingMessageRecalled.messageId = data.optLong("message_id", 0L)
                                    PendingMessageRecalled.senderName = data.optString("sender_name", "")
                                    PendingMessageRecalled.hasUpdate = true
                                    // 触发即时刷新，等轮询太慢
                                    ChatViewModel.notifyNewMessage()
                                }
                            }
                            "conversation_deleted" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    com.aurora.chat.PendingConversationDeleted.friendId = data.optLong("friend_id", 0L)
                                    com.aurora.chat.PendingConversationDeleted.hasUpdate = true
                                }
                            }
                            "broadcast_deleted" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    PendingBroadcastDeleted.taskId = data.optLong("task_id", 0L)
                                    PendingBroadcastDeleted.hasUpdate = true
                                }
                            }
                            "broadcast_recalled" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    PendingBroadcastRecalled.taskId = data.optLong("task_id", 0L)
                                    PendingBroadcastRecalled.hasUpdate = true
                                }
                            }
                            "community_activity" -> {
                                Log.i(TAG, "收到社区动态推送")
                                PendingCommunityActivity.hasUpdate = true
                            }
                            "system_notice" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val kind = data.optString("kind", "")
                                    if (kind == "transfer") {
                                        // 转账收款通知
                                        val desc = data.optString("desc", "")
                                        if (desc.isNotEmpty()) {
                                            val uid = com.aurora.chat.data.api.AuroraApi.currentUserId
                                            if (uid > 0) {
                                                val text = com.aurora.chat.ui.chat.formatTransferNotice(desc)
                                                val id = System.currentTimeMillis() * 10000 + (0..9999).random()
                                                val msg = com.aurora.chat.ui.chat.ChatMsg.create(
                                                    serverId = id, text = text, isMine = false, fromUserId = 0,
                                                    isSystemNotice = true, isNew = false, createdAt = System.currentTimeMillis() / 1000
                                                )
                                                com.aurora.chat.ui.chat.appendNotificationMessage(this@TcpService, uid, msg)
                                                com.aurora.chat.ui.viewmodel.ChatViewModel.notifySelfChatRefresh()
                                                com.aurora.chat.ui.viewmodel.ChatViewModel.loadConversations(force = true)
                                                Log.i(TAG, "收到转账收款通知，已写入系统通知对话")
                                            }
                                        }
                                    } else {
                                        val title = data.optString("title", "通知中心")
                                        val desc = data.optString("desc", "")
                                        val tag = data.optString("tag", "")
                                        val orderId = data.optLong("order_id", 0L)
                                        if (desc.isNotEmpty()) {
                                            val uid = com.aurora.chat.data.api.AuroraApi.currentUserId
                                            if (uid > 0) {
                                                val text = com.aurora.chat.ui.chat.formatOrderNotice(title, desc, tag, orderId)
                                                val id = System.currentTimeMillis() * 10000 + (0..9999).random()
                                                val msg = com.aurora.chat.ui.chat.ChatMsg.create(
                                                    serverId = id, text = text, isMine = false, fromUserId = 0,
                                                    isSystemNotice = true, isNew = false, createdAt = System.currentTimeMillis() / 1000
                                                )
                                                com.aurora.chat.ui.chat.appendNotificationMessage(this@TcpService, uid, msg)
                                                // 若正在查看「系统通知」则立即刷新；并更新会话列表预览
                                                com.aurora.chat.ui.viewmodel.ChatViewModel.notifySelfChatRefresh()
                                                com.aurora.chat.ui.viewmodel.ChatViewModel.loadConversations(force = true)
                                                Log.i(TAG, "收到系统通知(订单通过)推送，已写入系统通知对话")
                                            }
                                        }
                                    }
                                }
                            }
                            "account_banned" -> {
                                val data = json.optJSONObject("data")
                                val reason = data?.optString("reason", "") ?: ""
                                val duration = data?.optLong("duration", 0L) ?: 0L
                                val expiresAt = data?.optLong("expires_at", 0L) ?: 0L
                                val unbanPopupMessage = data?.optString("unban_popup_message", "") ?: ""
                                Log.w(TAG, "账号被封禁: reason=$reason, duration=${duration}s")
                                // 存储封禁信息到 SharedPreferences（MainActivity 读取后弹出全屏提示）
                                val prefs = getSharedPreferences("aurora_ban", Context.MODE_PRIVATE)
                                prefs.edit().apply {
                                    putLong("user_id", userId)
                                    putString("reason", reason)
                                    putLong("expires_at", expiresAt)
                                    putLong("duration", duration)
                                    putString("unban_popup_message", unbanPopupMessage)
                                    apply()
                                }
                                // 即时通知 MainActivity（无需等待轮询）
                                PendingBan.hasPending = true
                                PendingBan.expiresAt = expiresAt
                                PendingBan.reason = reason
                                PendingBan.duration = duration
                                PendingBan.unbanPopupMessage = unbanPopupMessage
                            }
                            else -> Log.d(TAG, "未知消息类型: $type")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "解析消息失败: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                val errMsg = "TCP 连接异常: ${e::class.simpleName} - ${e.message}"
                Log.w(TAG, errMsg)
                com.aurora.chat.ErrorReporter.warn("Network", errMsg, e)
                isConnected = false
                updateNotification("TCP已断连")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                // 如果仍在运行（即将重连），更新通知为断连状态
                if (running && userId > 0) {
                    isConnected = false
                    updateNotification("TCP已断连")
                }
            }

            // 指数退避重连：1s → 2s → 4s → 8s → 16s → 30s（上限）
            if (running && userId > 0) {
                reconnectAttempts++
                val delay = minOf(
                    MAX_RECONNECT_DELAY,
                    MIN_RECONNECT_DELAY * (1L shl (reconnectAttempts - 1))
                )
                Log.i(TAG, "$delay ms 后重连（第${reconnectAttempts}次）...")
                delay(delay)
            }
        }

        isConnectionRunning = false
        Log.i(TAG, "TCP 服务已停止")
        // 如果完全停止，移除前台通知
        try { stopForeground(true) } catch (_: Exception) {}
    }

    private fun getTcpHost(): String {
        // 从 AuroraApi.serverUrl 提取 IP
        val base = AuroraApi.serverUrl
            .replace("https://", "")
            .replace("http://", "")
            .split(":")[0]
        return base
    }
}
