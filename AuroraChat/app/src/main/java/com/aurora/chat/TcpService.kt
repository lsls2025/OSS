package com.aurora.chat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.core.domain.ChatEvents
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

/** 群待办 TCP 推送通知（设置 / 对所有人撤回） */
object PendingGroupTodo {
    /** 群待办数据：与服务端 group_todos 字段一一对应 */
    data class TodoInfo(
        val groupId: Long,
        val messageId: Long,
        val setterName: String,
        val snippet: String,
        val createdAt: Long
    )

    /** 设置/更新群待办 */
    @Volatile
    var hasSetUpdate = false
    @Volatile
    var setGroupId = 0L
    @Volatile
    var setTodo: TodoInfo? = null

    /** 对所有人撤回群待办（横幅消失 + 提示「某某撤回一条群待办」） */
    @Volatile
    var hasClearUpdate = false
    @Volatile
    var clearGroupId = 0L
    @Volatile
    var clearSenderName = ""

    fun markSet(info: TodoInfo) {
        setGroupId = info.groupId
        setTodo = info
        hasSetUpdate = true
    }

    fun markCleared(groupId: Long, senderName: String) {
        clearGroupId = groupId
        clearSenderName = senderName
        hasClearUpdate = true
    }
}

/** 开发者广播：单条删除（悄无声息）TCP推送通知 */
object PendingBroadcastDeleted {
    /**
     * 每次事件自增。
     * 不能再用「hasUpdate 消费一次就置 false」：同时打开/切换多个会话时，
     * 只有第一个看到事件的会话页会消费它，其它会话（真正有广播副本的那个）永远收不到，导致删除不生效。
     */
    @Volatile
    var version = 0L
    @Volatile
    var taskId = 0L

    fun push(id: Long) {
        taskId = id
        version++
    }
}

/** 开发者广播：撤回整个任务（停止未发 + 清理已发）TCP推送通知 */
object PendingBroadcastRecalled {
    /** 同 [PendingBroadcastDeleted.version]：每个会话页各自比对，避免被别的会话消费掉 */
    @Volatile
    var version = 0L
    @Volatile
    var taskId = 0L

    fun push(id: Long) {
        taskId = id
        version++
    }
}

/** 开发者测试：服务端删除整个会话，本地随之清空 */
object PendingConversationDeleted {
    @Volatile
    var hasUpdate = false
    @Volatile
    var friendId = 0L
}

/**
 * 「我被移出群聊」推送暂存。
 *
 * TCP 线程只负责置标记，真正的上屏 / 状态切换由对话页在组合线程消费：
 * 被踢时我可能正在这个群里聊天，也可能是别的页面；两种情况下都要
 * 1) 立刻在会话里追加一条「X 将你踢出群聊」的居中提示；
 * 2) 把输入区整块收起来，切成只读历史。
 */
object PendingGroupKicked {
    @Volatile
    var hasUpdate = false
    @Volatile
    var convId = 0L
    @Volatile
    var text = ""
    /**
     * 随踢出推送一起带下来的附加本地提示（如群敏感词的「提示文案」）。
     * 被踢者已不是群成员，群消息拉取接口对他 403，这几条他永远拉不到，
     * 只能以本地广播的形式补进会话。空 = 普通踢人，没有附加提示。
     * 仅旧版服务端载荷使用；新版走 [extraMsgs]（带服务端真实 id 与形态）。
     */
    @Volatile
    var extraNotices: List<String> = emptyList()

    /** 被踢者副本的一条附加消息：服务端真实 id + 形态。 */
    data class KickExtraMsg(
        val id: Long,
        val text: String,
        /** "" = 群身份气泡；"system" = 居中系统提示 */
        val type: String,
        /** 处置提示的被处置者名字（渲染「你因…/某某因…」用），可为空 */
        val target: String = ""
    )

    /**
     * 群敏感词踢人随推送带下的副本（提示文案 + 处置提示）。
     * 按服务端**真实消息 id 与形态**落本地库：
     *  - 群身份模式 → 补成带群头像的气泡，而不是把「@某人 …」渲染成居中灰字（那是广播形态）；
     *  - 广播模式   → 补成 msg_type=system 的居中提示。
     * id 用服务端真身：将来重新入群拉到同一条历史时按 id 去重，不会出现两份。
     */
    @Volatile
    var extraMsgs: List<KickExtraMsg> = emptyList()

    /** 踢出发生时间（秒）：附加消息的 createdAt 与服务端落库口径一致 */
    @Volatile
    var at: Long = 0L
}

/** 消息已读TCP推送通知 */
object PendingMessageRead {
    @Volatile
    var hasUpdate = false
    @Volatile
    var messageId = 0L

    /**
     * 一次「已读」事件的自带数据。推送里已经带了权威结果，客户端直接落到本地，
     * 不再回头请求接口 —— 「推送到达」和「界面变已读」之间不应该再夹一次网络往返。
     *
     * @param readerId   谁读的（私聊里等于对方 userId）
     * @param lastReadId 读到哪一条。私聊已读是单调的：≤ 这个 id 的、我发的消息全部算已读
     * @param readAt     已读时间（秒）
     * @param groupId    群聊 id（>0 表示群聊事件，群聊要走批量刷新重算人数）
     */
    data class ReadEvent(
        val seq: Long,
        val readerId: Long,
        val lastReadId: Long,
        val readAt: Long,
        val messageId: Long,
        val groupId: Long
    )

    /** 私聊已读：数据自带，页面收到直接标记，零请求。 */
    val events = kotlinx.coroutines.flow.MutableStateFlow<ReadEvent?>(null)

    /**
     * 群聊已读计数：群聊的人数没法从单条事件推出来（要排除发送者、可能一次读多条），
     * 所以仍用「计数变化 → 批量刷新」这条路径。
     *
     * 用 StateFlow 而不是 Compose 快照状态（mutableStateOf）：
     * 这个值是在 TCP 读线程里写的，快照状态跨线程写依赖 Recomposer 的全局 apply 通知，
     * 存在被合并/延迟的窗口；StateFlow 的写是线程安全的，collect 侧一定会收到。
     */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0L)

    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0L)

    fun bumpVersion() {
        version.value = version.value + 1L
    }

    fun publish(messageId: Long, readerId: Long, lastReadId: Long, readAt: Long, groupId: Long) {
        hasUpdate = true
        this.messageId = messageId
        events.value = ReadEvent(
            seq = seqCounter.incrementAndGet(),
            readerId = readerId,
            lastReadId = lastReadId,
            readAt = readAt,
            messageId = messageId,
            groupId = groupId
        )
    }
}

/** 闪照"对方正在查看中"TCP推送通知（我发的闪照，对方开始长按查看） */
object PendingFlashViewing {
    @Volatile
    var hasUpdate = false
    @Volatile
    var messageId = 0L
}

/** 闪照"对方已查看"TCP推送通知（我发的闪照，对方查看结束/销毁） */
object PendingFlashViewed {
    @Volatile
    var hasUpdate = false
    @Volatile
    var messageId = 0L
}

/** 社区动态TCP推送通知 */
object PendingCommunityActivity {
    @Volatile
    var hasUpdate = false

    /**
     * 每次推送自增。
     * hasUpdate 是"待处理"标志，会被角标刷新消费掉（置回 false），
     * 列表页不能靠它判断有没有新动态，否则永远抢不过角标。
     * version 只增不减，谁都能拿来比对自己看到的是不是最新。
     */
    @Volatile
    var version = 0
}

class TcpService : Service() {

    // 协程作用域：Service 生命周期管理，避免无限制的线程创建和阻塞
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        companion object {
        const val TCP_PORT = 5005
        const val TAG = "TcpService"

        /**
         * Intent 标记：本次启动是否**必须**拿到前台服务身份。
         * true（用 startForegroundService 拉起）时必须 startForeground，否则要 stopSelf，
         * 两者都不做系统会在 5 秒后抛 RemoteServiceException；
         * false（用 startService 拉起，App 可见时）前台身份可选，拿不到也照常跑 TCP。
         */
        const val EXTRA_FG_REQUIRED = "fg_required"
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 【崩溃修复】App 可见时用普通 startService：
                // 进程本来就是前台优先级，并不需要"前台服务"身份，也就不必去消耗 dataSync 的每日额度
                // （Android 15 / targetSdk 35 给 dataSync 加了累计时长上限，用尽后 startForeground
                //  会抛 ForegroundServiceStartNotAllowedException，直接把 App 干崩 —— 用户日志里就是这条）。
                // 后台拉起时才用 startForegroundService（系统要求必须在 5 秒内 startForeground 或 stopSelf）。
                val visible = runCatching { com.aurora.chat.AppVisibility.isForeground }.getOrDefault(false)
                if (visible) {
                    intent.putExtra(EXTRA_FG_REQUIRED, false)
                    val ok = runCatching { context.startService(intent) }.isSuccess
                    if (!ok) {
                        // 少数机型在"前台"依然拒绝普通 startService：退回前台服务方式
                        runCatching {
                            context.startForegroundService(
                                Intent(context, TcpService::class.java).putExtra(EXTRA_FG_REQUIRED, true)
                            )
                        }
                    }
                } else {
                    intent.putExtra(EXTRA_FG_REQUIRED, true)
                    runCatching { context.startForegroundService(intent) }
                }
            } else {
                runCatching { context.startService(intent) }
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
    private var sessionKey: String = ""
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // 新消息批量到达时对会话列表强制刷新做防抖合并（800ms），避免逐条重拉造成主线程抖动；
    // 实时消息仍由 notifyNewMessage 立即投递，仅「列表预览」刷新被合并
    private val conversationReloadHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val conversationReloadRunnable = Runnable {
        ChatEvents.sink.loadConversations(force = true)
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
        // 传输会话密钥（TCP 认证凭据 + 推送解密密钥）：优先取内存登录态，进程被回收后从本地存储恢复
        sessionKey = AuroraApi.sessionKey.ifEmpty { LocalStorage.getStoredSessionKey(this) }
        // 获取唤醒锁，息屏后保持 CPU 和网络
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "aurora_chat:tcp_wakelock")
            wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    /**
     * 尽力把本服务推成前台服务。
     *
     * 为什么不能直接 `startForeground(id, notif)`：
     * targetSdk 35（Android 15）给 dataSync 类前台服务加了**每日累计时长上限**，额度用尽后再调
     * startForeground 会抛
     * `ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync`，
     * 它抛在 onStartCommand 里就是"主线程未捕获异常 = App 直接闪退"（用户日志里正是这条）。
     *
     * 顺序：先 specialUse（"其它类型覆盖不到的合法场景"，**没有时长上限**，最适合 IM 长连接），
     * 不行再用 dataSync 兜底；都不行返回 false，由调用方决定 stopSelf 还是当普通服务跑（绝不抛出去）。
     */
    private fun goForeground(notification: android.app.Notification): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = runCatching {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }.isSuccess
            if (ok) return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ok = runCatching {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }.isSuccess
            if (ok) return true
        }
        return runCatching { startForeground(1001, notification) }.isSuccess
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fgRequired = intent?.getBooleanExtra(EXTRA_FG_REQUIRED, true) ?: true
        val notification = NotificationHelper.buildForegroundNotification(this, "正在连接...")
        val foregroundOk = goForeground(notification)
        if (!foregroundOk && fgRequired) {
            // startForegroundService 拉起的：必须给出前台或自己停掉，二选一。
            // 这里选择 stopSelf() 干净退出（不崩、不残留），等下次进前台/系统重启再拉起。
            Log.w(TAG, "前台服务身份被系统拒绝（dataSync 额度用尽），本次不启动，等待下次拉起")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundOk) {
            // startService 拉起的（App 可见）：前台身份只是"更好"，拿不到也继续跑
            Log.w(TAG, "前台身份不可用，本服务以普通后台服务继续运行")
        }
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
                        val wc = com.aurora.chat.ui.profile.WordCountTracker.computeSyncValue()
                        val r = AuroraApi.syncUserStats(ot, wc)
                        if (r.success) com.aurora.chat.ui.profile.WordCountTracker.markSynced(this@TcpService)
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
            // 走 goForeground：保证与首次启动用的是同一套类型降级逻辑，且内部已吞掉所有异常
            goForeground(notification)
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

                // 发送认证前补齐传输会话密钥：旧版本登录链路漏存 session_key 的设备冷启动后
                // 本地为空，直接连会被服务端以「认证失败: session_key 校验不通过」拒绝且无法自愈。
                // 这里在认证前向服务端拉一次（同一响应含 sign_secret 与 session_key），拿到即落盘。
                if (sessionKey.isEmpty()) {
                    runCatching { AuroraApi.refreshClientSignSecret() }
                    val fresh = AuroraApi.sessionKey.ifEmpty { LocalStorage.getStoredSessionKey(this@TcpService) }
                    if (fresh.isNotEmpty()) {
                        sessionKey = fresh
                        LocalStorage.saveSessionKey(this@TcpService, fresh)
                        Log.i(TAG, "已补齐传输会话密钥（session_key）")
                    }
                }

                // 发送认证（携带真实登录凭据 session_key，服务端校验不通过会拒绝并断开）
                val auth = JSONObject().apply {
                    put("type", "auth")
                    put("user_id", userId)
                    put("session_key", sessionKey)
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
                        var json = JSONObject(line)
                        // 推送加密帧：{"type":"push","data":"sess:v1:<密文>"}，data 为服务端用
                        // session_key（AES-256-GCM）加密的内层 JSON（与原推送帧同构）。
                        // 解密成功则替换为内层帧继续走原分发；解密失败保持原帧（走未知类型分支，不崩）。
                        if (json.optString("type", "") == "push" && sessionKey.isNotEmpty()) {
                            val enc = json.optString("data", "")
                            val plain = CryptoUtil.decryptWithKey(enc, sessionKey)
                            if (plain.isNotEmpty() && !plain.startsWith("sess:v1:")) {
                                json = JSONObject(plain)
                            }
                        }
                        val type = json.optString("type", "")

                        when (type) {
                            "auth_ok" -> Log.i(TAG, "TCP 认证成功")
                            "auth_denied" -> {
                                val msg = json.optString("message", "账号已被封禁")
                                val data = json.optJSONObject("data")
                                val reason = data?.optString("reason", "") ?: ""
                                val until = data?.optLong("until", 0L) ?: 0L
                                val adNow = System.currentTimeMillis() / 1000
                                com.aurora.chat.ErrorReporter.error("BanTrace", "TCP auth_denied: until=$until now=$adNow")
                                Log.w(TAG, "认证被拒: $msg, reason=$reason")
                                // ★ 彻底简化：只写 prefs 的 expires_at + reason，不设 PendingBan
                                // 根层 500ms 循环扫 prefs 会发现 expires_at > now 并弹出 BanOverlay
                                // duration 在 prefs 里留 0 → checkSrv 2 秒内回来用 (expires_at-banned_at) 补权威值
                                // 
                                // 为什么不设 PendingBan？
                                //   在线封禁：account_banned 先到（admin handler 先 PushToUser 再 DisconnectUser）
                                //   离线重连：FlushPendingPushes 先刷 account_banned 再 auth_denied
                                //   auth_denied 后设 PendingBan 只会覆盖 account_banned 写入的正确值
                                if (until > 0) {
                                    getSharedPreferences("aurora_ban", Context.MODE_PRIVATE).edit().apply {
                                        putString("reason", reason)
                                        putLong("expires_at", until)
                                        putLong("user_id", userId)
                                        apply()
                                    }
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
                                    // 私聊推送的 content 为服务端解密后的明文，通知栏直接显示即可；
                                    // 仅当 content 残留历史兼容密文前缀时才用中性占位，避免显示密文乱码。
                                    val isPrivatePlaintext = !isGroup && content.isNotBlank() &&
                                        (mediaType.isEmpty() || mediaType == "text")
                                    val body = when {
                                        isPrivatePlaintext -> if (content.startsWith("E2EE:v2:") || content.startsWith("sess:v1:")) "[新消息]" else content
                                        content.isNotBlank() -> content
                                        mediaType == "image" || mediaType == "jpg" || mediaType == "png" || mediaType == "gif" -> "[图片]"
                                        mediaType == "video" || mediaType == "mp4" -> "[视频]"
                                        else -> "发来了一条新消息"
                                    }
                                    // 获取对话ID用于通知跳转
                                    val convId = if (isGroup) toUserId else fromUserId
                                    // 能收到这个群的新消息，就证明当前账号确实还在群里。
                                    // 若本地还残留「被踢出」记录（换账号/换设备后沿用了旧缓存、或退出推送乱序），
                                    // 立刻清掉并通知会话页解除只读——否则会出现「能收消息却被框死成你已被踢出群聊」。
                                    if (isGroup && convId < 0L) {
                                        val exitGid = -(convId + 1000L)
                                        if (exitGid > 0L &&
                                            com.aurora.chat.data.local.GroupExitStore.get(this, exitGid) != null
                                        ) {
                                            com.aurora.chat.data.local.GroupExitStore.remove(this, exitGid)
                                            PendingGroupKicked.convId = convId
                                            PendingGroupKicked.text = ""
                                            PendingGroupKicked.hasUpdate = true
                                        }
                                    }
                                    // 隐私：日志只记录元数据，绝不打印消息正文（明文或密文）
                                    Log.i(TAG, "收到新消息: from=$fromName, toUsername=$toUsername, media=$mediaType, convId=$convId, msgLen=${content.length}")

                                    // 累计未读：当前未打开该会话、且不是自己发出的消息（群聊自己发的消息不计入）
                                    if (convId != ChatEvents.sink.openConversationId
                                        && fromUserId != com.aurora.chat.data.api.AuroraApi.currentUserId) {
                                        ChatEvents.sink.addUnread(convId, 1)
                                    } else {
                                        Log.i(TAG, "未读不计: convId=$convId, openConv=${ChatEvents.sink.openConversationId}, from=$fromUserId, me=${com.aurora.chat.data.api.AuroraApi.currentUserId}")
                                    }

                                    // 拍拍消息（flash_duration == -1）不弹系统通知，只在对话内显示；
                                    // 群自身发出的通知（from_user_id == to_user_id == 群会话ID，如入群/退群/禁言/
                                    // 系统广播）同样不弹通知，避免每次成员变动都推一条；
                                    // 自己发出的消息也不弹 —— 自己给自己发时服务端会把消息回推给我（from == 我），
                                    // 以前这里漏了这次判断，表现就是「自己发消息，自己手机还响一下」。
                                    val flashDuration = data.optInt("flash_duration", 0)
                                    val isGroupSelfNotice = isGroup && fromUserId == toUserId
                                    val isMyOwnEcho = fromUserId == com.aurora.chat.data.api.AuroraApi.currentUserId
                                    if (flashDuration != -1 && !isGroupSelfNotice && !isMyOwnEcho) {
                                        NotificationHelper.showMessageNotification(
                                            this, title, body,
                                            friendId = convId,
                                            friendName = if (isGroup && toUsername.isNotBlank()) toUsername else fromName
                                        )
                                    }
                                    // 通知 ChatViewModel 实时投递新消息；会话列表强制刷新做防抖合并
                                    ChatEvents.sink.notifyNewMessage()
                                    requestConversationReloadDebounced()
                                }
                            }
                            // 落库回执：服务端已替我落库（清空请求卡片、隐私卡片、分享等客户端没有乐观气泡的消息）。
                            // 这里立刻做一次增量对齐 + 会话列表刷新，
                            // 让"我发出去的东西"在我这边也是秒级可见，不必等 15 秒兜底轮询或切出重进。
                            // 刻意不弹通知、不计未读：这是我自己发的，不是别人给我发的。
                            "message_persisted" -> {
                                ChatEvents.sink.notifyNewMessage()
                                requestConversationReloadDebounced()
                            }
                            "message_read" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val msgId = data.optLong("message_id", 0L)
                                    val readerId = data.optLong("reader_id", 0L)
                                    val groupId = data.optLong("group_id", 0L)
                                    // 老服务端没有 last_read_id 字段，退回单条 message_id
                                    val lastReadId = data.optLong("last_read_id", 0L).let {
                                        if (it > 0) it else msgId
                                    }
                                    Log.i(TAG, "消息已读通知: msgId=$msgId lastRead=$lastReadId group=$groupId")
                                    // 私聊：事件自带权威结果，页面直接本地标记，不再回查接口
                                    com.aurora.chat.PendingMessageRead.publish(
                                        messageId = msgId,
                                        readerId = readerId,
                                        lastReadId = lastReadId,
                                        readAt = data.optLong("read_at", 0L),
                                        groupId = groupId
                                    )
                                    // 群聊：人数要重算，走批量刷新
                                    if (groupId > 0L) com.aurora.chat.PendingMessageRead.bumpVersion()
                                }
                            }
                            "friend_request" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val fromName = data.optString("from_username", "未知用户")
                                    val greeting = data.optString("greeting", "")
                                    Log.i(TAG, "收到好友请求: $fromName")
                                    ChatEvents.sink.notifyFriendRequest()
                                    // 立即重新拉取好友请求列表，保证打开列表时能看到最新申请
                                    ChatEvents.sink.loadFriendRequests()
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
                                    ChatEvents.sink.notifyFriendRequest()
                                    ChatEvents.sink.loadConversations(force = true)
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
                                    ChatEvents.sink.notifyNewMessage()
                                }
                            }
                            "group_todo_set" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val info = PendingGroupTodo.TodoInfo(
                                        groupId = data.optLong("group_id", 0L),
                                        messageId = data.optLong("message_id", 0L),
                                        setterName = data.optString("setter_name", ""),
                                        snippet = data.optString("snippet", ""),
                                        createdAt = data.optLong("created_at", 0L)
                                    )
                                    PendingGroupTodo.markSet(info)
                                    ChatEvents.sink.notifyNewMessage()
                                }
                            }
                            "group_todo_cleared" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    PendingGroupTodo.markCleared(
                                        data.optLong("group_id", 0L),
                                        data.optString("setter_name", "")
                                    )
                                    ChatEvents.sink.notifyNewMessage()
                                }
                            }
                            "privacy_alert" -> {
                                // 隐私告警（截图/录屏检测、隐私开关变更）：后端已插入一条 msg_type=system 的消息，
                                // 但不会推 new_message。这里主动触发会话内增量刷新 + 会话列表刷新，
                                // 让该条广播立即出现，而不是等下次重进对话才看到。
                                val data = json.optJSONObject("data")
                                ChatEvents.sink.notifyNewMessage()
                                requestConversationReloadDebounced()
                                // 通知会话内重新读取对方隐私设置：对方刚开启禁止截图/录屏时立即挂上 FLAG_SECURE
                                ChatEvents.sink.notifyPrivacyChanged()
                                // 「禁止退出」互锁：对方一动开关就立刻重新核对（对方关闭 → 立即放行），
                                // 不必等 15 秒的兜底轮询。
                                val fromId = data?.optLong("from_user_id", 0L) ?: 0L
                                if (fromId > 0L) PrivacyLockState.refresh(fromId)
                            }
                            "conversation_deleted" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    com.aurora.chat.PendingConversationDeleted.friendId = data.optLong("friend_id", 0L)
                                    com.aurora.chat.PendingConversationDeleted.hasUpdate = true
                                }
                            }
                            // 被管理员/群主移出群聊。
                            // 群内广播（"X 将你移出群聊"）推不到我——服务端是先移除成员再发广播的，
                            // 所以我只能收到这条定向推送。收到后要在本地留三样东西：
                            //   1) 退出记录（会话列表据此把这个群保留下来，否则会话直接消失）
                            //   2) 一条本地广播（会话里那行「X 将你踢出群聊」）
                            //   3) 一次性标记（正在这个会话里时立刻切成只读态）
                            "group_kicked" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val gid = data.optLong("group_id", 0L)
                                    if (gid > 0L) {
                                        val convId = data.optLong("conv_id", 0L)
                                            .takeIf { it < 0L } ?: -(1000 + gid)
                                        val at = data.optLong("at", 0L)
                                            .takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000)
                                        val exit = com.aurora.chat.data.local.GroupExit(
                                            groupId = gid,
                                            groupName = data.optString("group_name", ""),
                                            operatorName = data.optString("operator_name", ""),
                                            time = at,
                                            reason = com.aurora.chat.data.local.GroupExit.REASON_KICKED,
                                            // 服务端给的完整原因（如「你因触发该群敏感词已被踢出群聊」）
                                            customText = data.optString("text", "")
                                        )
                                        com.aurora.chat.data.local.GroupExitStore.record(this, exit)
                                        // silent = 服务端已随推送带下了完整的提示行（extra_notices），
                                        // 主提示（exit.text）那条本地广播就不再落 —— 否则「你因触发…」
                                        // 会出现两遍（一遍本地广播、一遍随附提示），截图里那三行就是这么堆出来的。
                                        val silent = data.optInt("silent", 0) == 1
                                        // 新版载荷：extra_msgs = 服务端真实消息的副本（id + 形态）。
                                        // 群身份模式 → 补成真正的群气泡（渲染和别人看到的一致），
                                        // 广播模式 → 补成 msg_type=system 的居中提示。
                                        // 直接落 MessageDb（不是 LocalNoticeStore）：
                                        //  1) 会话历史从本地库读，重进会话自动还原；
                                        //  2) id 是服务端真身，将来重新入群拉到同一条历史时按 id 去重。
                                        val extraMsgList = data.optJSONArray("extra_msgs")?.let { arr ->
                                            (0 until arr.length()).mapNotNull { i ->
                                                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                                                val mid = o.optLong("id", 0L)
                                                val txt = o.optString("text", "").trim()
                                                if (mid <= 0L || txt.isEmpty()) null
                                                else PendingGroupKicked.KickExtraMsg(
                                                    id = mid,
                                                    text = txt,
                                                    type = if (o.optString("type", "") == "system") "system" else "",
                                                    target = o.optString("target", "")
                                                )
                                            }
                                        } ?: emptyList()
                                        val groupName = data.optString("group_name", "")
                                        if (extraMsgList.isNotEmpty()) {
                                            coroutineScope.launch {
                                                try {
                                                    com.aurora.chat.data.repository.LocalMessageStore.saveMessages(
                                                        this@TcpService,
                                                        convId,
                                                        extraMsgList.map { e ->
                                                            com.aurora.chat.data.api.MessageInfo(
                                                                id = e.id,
                                                                fromUserId = convId,
                                                                toUserId = convId,
                                                                content = e.text,
                                                                createdAt = at,
                                                                fromUserName = groupName,
                                                                targetName = e.target,
                                                                msgType = e.type
                                                            )
                                                        }
                                                    )
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "被踢副本落库失败 conv=$convId: ${e.message}")
                                                }
                                            }
                                        }
                                        val extras = data.optJSONArray("extra_notices")?.let { arr ->
                                            (0 until arr.length()).mapNotNull { i ->
                                                arr.optString(i, "").takeIf { s -> s.isNotBlank() }
                                            }
                                        } ?: emptyList()
                                        if (!silent && extraMsgList.isEmpty()) {
                                            com.aurora.chat.data.local.LocalNoticeStore.save(
                                                this,
                                                com.aurora.chat.data.local.LocalNotice(
                                                    conversationId = convId,
                                                    text = exit.text,
                                                    createdAt = at,
                                                    type = "group_kicked"
                                                )
                                            )
                                        }
                                        // 旧版兜底：服务端没带 extra_msgs 时，附加提示退回本地广播形式
                                        if (extraMsgList.isEmpty()) {
                                            for (t in extras) {
                                                com.aurora.chat.data.local.LocalNoticeStore.save(
                                                    this,
                                                    com.aurora.chat.data.local.LocalNotice(
                                                        conversationId = convId,
                                                        text = t,
                                                        createdAt = at,
                                                        type = "group_kicked"
                                                    )
                                                )
                                            }
                                        }
                                        PendingGroupKicked.convId = convId
                                        PendingGroupKicked.text = if (silent) "" else exit.text
                                        PendingGroupKicked.extraMsgs = extraMsgList
                                        PendingGroupKicked.at = at
                                        PendingGroupKicked.extraNotices = if (extraMsgList.isEmpty()) extras else emptyList()
                                        PendingGroupKicked.hasUpdate = true
                                        Log.i(TAG, "被移出群聊: group=$gid conv=$convId")
                                        // 会话列表要把这个群重新拼出来（预览换成踢出提示）
                                        requestConversationReloadDebounced()
                                    }
                                }
                            }
                            // 群被解散（群主 / 开发者操作）。
                            // 和「被踢」共用同一套本地落地：退出记录 + 本地提示 + 只读切换，
                            // 区别只在 reason —— 文案必须是「群主已解散群聊」，
                            // 否则会被兜底逻辑（群从列表消失 = 被踢）写成「你已被踢出群聊」。
                            "group_dissolved" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val gid = data.optLong("group_id", 0L)
                                    if (gid > 0L) {
                                        val convId = data.optLong("conv_id", 0L)
                                            .takeIf { it < 0L } ?: -(1000 + gid)
                                        val at = data.optLong("at", 0L)
                                            .takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000)
                                        val exit = com.aurora.chat.data.local.GroupExit(
                                            groupId = gid,
                                            groupName = data.optString("group_name", ""),
                                            operatorName = data.optString("operator_name", ""),
                                            time = at,
                                            reason = com.aurora.chat.data.local.GroupExit.REASON_DISSOLVED
                                        )
                                        com.aurora.chat.data.local.GroupExitStore.record(this, exit)
                                        com.aurora.chat.data.local.LocalNoticeStore.save(
                                            this,
                                            com.aurora.chat.data.local.LocalNotice(
                                                conversationId = convId,
                                                text = exit.text,
                                                createdAt = at,
                                                type = "group_dissolved"
                                            )
                                        )
                                        PendingGroupKicked.convId = convId
                                        PendingGroupKicked.text = exit.text
                                        PendingGroupKicked.hasUpdate = true
                                        Log.i(TAG, "群已解散: group=$gid conv=$convId")
                                        requestConversationReloadDebounced()
                                    }
                                }
                            }
                            // 闪照实时状态：对方开始查看我的闪照 → "对方查看中"
                            "flash_viewing" -> {
                                val mid = json.optJSONObject("data")?.optLong("message_id", 0L) ?: 0L
                                if (mid > 0L) {
                                    com.aurora.chat.PendingFlashViewing.messageId = mid
                                    com.aurora.chat.PendingFlashViewing.hasUpdate = true
                                }
                            }
                            // 闪照实时状态：对方查看结束 → "对方已查看"
                            "flash_viewed" -> {
                                val mid = json.optJSONObject("data")?.optLong("message_id", 0L) ?: 0L
                                if (mid > 0L) {
                                    com.aurora.chat.PendingFlashViewed.messageId = mid
                                    com.aurora.chat.PendingFlashViewed.hasUpdate = true
                                }
                            }
                            "broadcast_deleted" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    PendingBroadcastDeleted.push(data.optLong("task_id", 0L))
                                }
                            }
                            "broadcast_recalled" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    PendingBroadcastRecalled.push(data.optLong("task_id", 0L))
                                }
                            }
                            "community_activity" -> {
                                Log.i(TAG, "收到社区动态推送")
                                PendingCommunityActivity.hasUpdate = true
                                // 只增不减的版本号：列表页据此判断"该刷新了"，
                                // 不会被角标那侧把 hasUpdate 消费掉而错过。
                                PendingCommunityActivity.version++
                            }
                            "transfer_status" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val tid = data.optLong("transfer_id", 0L)
                                    val st = data.optString("status", "")
                                    val at = data.optLong("claimed_at", 0L)
                                    if (tid > 0L && st.isNotEmpty()) {
                                        com.aurora.chat.ui.chat.TransferStatusCache.put(tid, st, at)
                                        ChatEvents.sink.notifyNewMessage()
                                    }
                                }
                            }
                            "system_notice" -> {
                                val data = json.optJSONObject("data")
                                if (data != null) {
                                    val kind = data.optString("kind", "")
                                    if (kind == "transfer") {
                                        // 转账收款通知：与其它系统通知共用同一份模板，
                                        // 不再走专门的橙色转账卡片。
                                        val desc = data.optString("desc", "")
                                        val title = data.optString("title", "转账收款").ifEmpty { "转账收款" }
                                        val tag = data.optString("tag", "转账")
                                        if (desc.isNotEmpty()) {
                                            val uid = com.aurora.chat.data.api.AuroraApi.currentUserId
                                            if (uid > 0) {
                                                val text = com.aurora.chat.ui.chat.formatOrderNotice(title, desc, tag, 0)
                                                val id = System.currentTimeMillis() * 10000 + (0..9999).random()
                                                val msg = com.aurora.chat.ui.chat.ChatMsg.create(
                                                    serverId = id, text = text, isMine = false, fromUserId = 0,
                                                    isSystemNotice = true, isNew = false, createdAt = System.currentTimeMillis() / 1000
                                                )
                                                com.aurora.chat.ui.chat.appendNotificationMessage(this@TcpService, uid, msg)
                                                ChatEvents.sink.notifySelfChatRefresh()
                                                ChatEvents.sink.loadConversations(force = true)
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
                                                ChatEvents.sink.notifySelfChatRefresh()
                                                ChatEvents.sink.loadConversations(force = true)
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
                                val tcpNow = System.currentTimeMillis() / 1000
                                com.aurora.chat.ErrorReporter.error("BanTrace", "TCP account_banned: expiresAt=$expiresAt now=$tcpNow diff=${expiresAt - tcpNow} duration_field=$duration")
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
                            "account_kicked" -> {
                                val data = json.optJSONObject("data")
                                val message = data?.optString("message", "账号已被管理员踢出，请重新登录")
                                    ?: "账号已被管理员踢出，请重新登录"
                                val ip = data?.optString("ip", "") ?: ""
                                val loginTs = data?.optLong("login_ts", 0L) ?: 0L
                                Log.w(TAG, "账号被踢出（主动推送）: $message ip=$ip")
                                com.aurora.chat.data.api.AuroraApi.triggerKickOff(message, ip = ip, loginTs = loginTs)
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
