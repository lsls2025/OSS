package com.aurora.chat.ui.gomoku

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.chat.AiChatManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// 五子棋 WS 地址统一在客户端配置里维护（与主站 serverUrl 分开各占一项），见 AuroraApi.gomokuWsUrl

data class RoomInfo(
    val id: String,
    val name: String,
    val status: String,
    val count: Int,
    val canJoin: Boolean
)

data class ChatMsg(
    val text: String,
    val who: String,
    val mine: Boolean
)

/**
 * 联机匹配结算信息：服务端 `matchResult` 消息下发的段位/分数变化。
 * 字段与 `onServerMessage("matchResult")` 里的赋值一一对应。
 */
data class MatchResultInfo(
    val win: Boolean,
    val draw: Boolean,
    val score: Int,
    val delta: Int,
    val rank: String,
    val rankBefore: String,
    val opponentScore: Int,
    val opponentRank: String
)

/** 赌注弹窗类型。 */
enum class BetDialogKind {
    /** 自己填金额发起下注 */
    OFFER,

    /** 已提交，等待对方响应 */
    WAITING,

    /** 对方已下注，等自己填金额或拒绝 */
    RESPOND,

    /** 双方金额都填好了，等自己（发起者）确认锁定 */
    CONFIRM
}

/** 赌注弹窗状态（GomokuGame.betDialog 为 null 表示不显示弹窗）。 */
data class BetDialog(
    val kind: BetDialogKind,
    /** 自己（或发起者）填写的金额 */
    val myAmount: Long = 0L,
    /** 对手填写的金额 */
    val opponentAmount: Long = 0L
)

/**
 * 赌注结算回执。
 * delta 为相对「托管扣款之前」的净变化：赢 = +对手赌注、输 = -自己赌注、平局 = 0。
 */
data class BetResult(
    val win: Boolean,
    val draw: Boolean,
    val myAmount: Long,
    val opponentAmount: Long,
    val delta: Long
)

/**
 * 五子棋状态机 + 联机客户端（照搬 C:\五子棋 的 game.js / net.js / main.js 行为）。
 * 所有可见状态用 Compose MutableState 暴露，UI 直接读取即可刷新。
 */
class GomokuGame(private val appContext: Context? = null) {
    // ---- 匹配/段位 ----
    var matching by mutableStateOf(false)
    var myRank by mutableStateOf("白银")
    var myScore by mutableStateOf(1000)
    var myWins by mutableStateOf(0)
    var myLosses by mutableStateOf(0)
    var opponentRank by mutableStateOf("")
    var opponentScore by mutableStateOf(0)
    var matchResultInfo by mutableStateOf<MatchResultInfo?>(null)
    private var playerId = ""

    // ---- 赌注（仅「联机房间」与「匹配对局」；AI 对局不显示入口） ----
    /** 赌注弹窗（null = 不显示；UI 直接读写，与 showDevAuth 同一做法） */
    var betDialog by mutableStateOf<BetDialog?>(null)
    /** 本局是否已发起过赌约（一局只能发起一次：被拒绝/放弃后本局不再允许） */
    var betUsed by mutableStateOf(false)
        private set
    /** 赌约是否已锁定（锁定后中途退出 = 逃跑 = 判负） */
    var betLocked by mutableStateOf(false)
        private set
    /** 权威金额（锁定后由服务端下发）：自己 / 对手 */
    var betMyAmount by mutableStateOf(0L)
        private set
    var betOpponentAmount by mutableStateOf(0L)
        private set
    /** 我是否为「响应方」（收到对方 betOffer 且尚未了结）：让"赌"入口可重开待响应的赌约 */
    var betIAmResponder by mutableStateOf(false)
        private set
    /** 响应方待响应的赌约中、对方已下注的金额（重开时展示"历史"用） */
    var betPendingOfferAmount by mutableStateOf(0L)
        private set
    /** 平台 token 余额（-1 = 尚未拉取）；仅用于下注校验与展示，权威校验在服务端 */
    var tokenBalance by mutableStateOf(-1L)
        private set
    /** 赌注结算回执（胜负弹窗文案用） */
    var betResult by mutableStateOf<BetResult?>(null)
        private set
    /** 退出对局二次确认：退出=逃跑判负，赌约锁定后还会扣款，必须严肃确认 */
    var exitConfirm by mutableStateOf(false)
        private set

    init {
        val sp = appContext?.getSharedPreferences("gomoku_pref", Context.MODE_PRIVATE)
        playerId = sp?.getString("playerId", null) ?: run {
            val id = genPlayerId()
            sp?.edit()?.putString("playerId", id)?.apply()
            id
        }
    }

    private fun genPlayerId(): String {
        val chars = "0123456789abcdef"
        return (1..32).map { chars.random() }.joinToString("")
    }

    // ---- 视图 ----
    var view by mutableStateOf("lobby") // lobby | rooms | game
    var board by mutableStateOf(GomokuEngine.emptyBoard())
    var current by mutableStateOf(1)
    var over by mutableStateOf(false)
    var mode by mutableStateOf("ai") // ai | online | match
    var myColor by mutableStateOf<Int?>(null)
    var started by mutableStateOf(false)
    var waiting by mutableStateOf(false)
    var status by mutableStateOf("")
    var overlayMsg by mutableStateOf<String?>(null)
    var banner by mutableStateOf<Pair<String, String>?>(null)

    var rooms by mutableStateOf<List<RoomInfo>>(emptyList())
    var roomsStatus by mutableStateOf("")
    val chat = mutableStateListOf<ChatMsg>()
    val history = mutableStateListOf<Pair<Int, Int>>()

    // ---- 开发者模式 ----
    var devEnabled by mutableStateOf(false)
    var devPassed by mutableStateOf(false)
    var devInfinite by mutableStateOf(false)
    var devFlipped by mutableStateOf(false)
    var showDevAuth by mutableStateOf(false)
    var devAuthError by mutableStateOf("")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var reconnectScheduled = false
    /**
     * 当前所在房间号（服务端在 joined / start 消息里下发，离开/房间关闭时清空）。
     * 这是判断"能不能把消息发给服务端"的**唯一依据**——不要再用 mode / matching 这类客户端自己维护的状态去猜，
     * 那些状态一旦卡住（例如 matching 没被清掉），发言就会被误判成"本地回显"，对手永远收不到。
     * 用 Compose 状态暴露，赌注入口的可见性（betEntryVisible）才能随进/出房间正确刷新。
     */
    private var roomId by mutableStateOf("")

    /** 当前所在房间号（对外只读）：邀请卡片要把房间号写进去 */
    val currentRoomId: String get() = roomId

    /** 邀请好友抽屉是否展开（等待对手界面用） */
    var inviteDrawer by mutableStateOf(false)

    /**
     * 从邀请卡片进入时携带的房间号：连接建立后自动 join。
     * 只在真正发出 join 后清空，重连（断线重连）时不会重复触发。
     */
    private var pendingJoinRoomId = ""

    /** 本次连接是否在尝试「凭邀请卡入局」：用于把 join 失败的报错显示成明确的"邀请已失效" */
    private var joiningFromInvite = false

    fun setPendingJoin(roomId: String) {
        pendingJoinRoomId = roomId
    }

    private val colorName: String
        get() = if (myColor == 1) "黑棋" else "白棋"

    // ---------------------------------------------------------
    // 网络层
    // ---------------------------------------------------------
    fun connect() {
        if (ws != null) return
        // 带上登录态：赌注要真实扣/加 token，服务端必须由 token 解析出"你是哪个平台用户"，
        // 不能听客户端自报 user_id，否则可以冒充别人下注。未登录时仍然能正常对局（只是不能下注）。
        val token = AuroraApi.authToken.orEmpty()
        val reqBuilder = Request.Builder().url(AuroraApi.gomokuWsUrl)
        if (token.isNotEmpty()) reqBuilder.header("Authorization", "Bearer $token")
        val req = reqBuilder.build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                post {
                    send(mapOf("type" to "list"))
                    // 从邀请卡片进来：连上就把 join 发出去（等房主那边 start 下发即入局）
                    val pj = pendingJoinRoomId
                    if (pj.isNotEmpty()) {
                        pendingJoinRoomId = ""
                        joiningFromInvite = true
                        view = "rooms"
                        send(mapOf("type" to "join", "roomId" to pj))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                post { handleMessage(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                post { handleClose() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                post { handleClose() }
            }
        })
    }

    private fun handleClose() {
        // 连接断了服务端就会把房间清掉（房主离开房间即销毁），本地也同步清空，
        // 避免断线期间发言走"发给服务端"分支、结果 ws 为 null 被静默丢弃、连自己都看不到
        roomId = ""
        // 锁定赌约中途断线 = 逃跑判负（服务端会结算）；如实提示，避免用户误以为赌注没事
        if (betLocked) {
            betLocked = false
            tip("连接已断开：锁定的赌约将按逃跑判负结算")
        }
        betDialog = null
        betUsed = false
        if (mode == "match" && matching) {
            matching = false
            showOverlay("连接已断开，重连中…", false)
        }
        if (mode == "online" && (online || started)) {
            online = false
            started = false
            waiting = false
            showOverlay("连接已断开，重连中…", false)
        }
        if (!reconnectScheduled) {
            reconnectScheduled = true
            mainHandler.postDelayed({
                reconnectScheduled = false
                connect()
            }, 1500)
        }
    }

    /** 发送一条消息；返回是否真正投递出去（连接已断/正在关闭时会返回 false，调用方据此兜底）。 */
    private fun send(map: Map<String, Any?>): Boolean {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        return ws?.send(json.toString()) ?: false
    }

    fun dispose() {
        ws?.close(1000, null)
        ws = null
        scope.cancel()
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun handleMessage(text: String) {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "roomList" -> {
                val arr = json.optJSONArray("rooms")
                val list = mutableListOf<RoomInfo>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            RoomInfo(
                                o.optString("id"),
                                o.optString("name"),
                                o.optString("status"),
                                o.optInt("count"),
                                o.optBoolean("canJoin")
                            )
                        )
                    }
                }
                rooms = list
            }
            "joined" -> {
                joiningFromInvite = false
                roomId = json.optString("roomId")
                enterRoom(json.optInt("color"))
            }
            "start" -> {
                // start 一定带 roomId（匹配房与普通房都带），以它为准记录"我现在确实在房间里"
                roomId = json.optString("roomId")
                startOnline(json.optInt("yourColor"))
                if (json.optBoolean("match")) {
                    mode = "match"
                    matching = false
                    myScore = json.optInt("myScore", myScore)
                    myRank = json.optString("myRank", myRank)
                    myWins = json.optInt("wins", myWins)
                    myLosses = json.optInt("losses", myLosses)
                    opponentScore = json.optInt("opponentScore", 0)
                    opponentRank = json.optString("opponentRank", "")
                }
            }
            "move" -> applyRemoteMove(
                json.optInt("r"),
                json.optInt("c"),
                json.optInt("player"),
                json.optInt("current"),
                json.optBoolean("over"),
                json.optInt("winner")
            )
            "restart" -> restartOnline()
            "matchWaiting" -> {
                matching = true
                status = "匹配中…"
                myScore = json.optInt("myScore", myScore)
                myRank = json.optString("myRank", myRank)
                myWins = json.optInt("wins", myWins)
                myLosses = json.optInt("losses", myLosses)
            }
            "cancelMatchOk" -> {
                matching = false
            }
            "matchResult" -> {
                matching = false
                val win = json.optBoolean("win")
                val draw = json.optBoolean("draw", false)
                val score = json.optInt("score", myScore)
                val delta = json.optInt("delta", 0)
                val rank = json.optString("rank", myRank)
                val rankBefore = json.optString("rankBefore", rank)
                myScore = score
                myRank = rank
                myWins = json.optInt("wins", myWins)
                myLosses = json.optInt("losses", myLosses)
                opponentScore = json.optInt("opponentScore", opponentScore)
                opponentRank = json.optString("opponentRank", opponentRank)
                matchResultInfo = MatchResultInfo(
                    win = win,
                    draw = draw,
                    score = score,
                    delta = delta,
                    rank = rank,
                    rankBefore = rankBefore,
                    opponentScore = opponentScore,
                    opponentRank = opponentRank
                )
                showResult(if (draw) 0 else if (win) (myColor ?: 1) else (if (myColor == 1) 2 else 1))
            }
            "opponentLeft" -> {
                online = false
                started = false
                waiting = false
                roomId = ""
                showOverlay("对手已离开", false)
            }
            "roomClosed" -> {
                online = false
                started = false
                waiting = false
                roomId = ""
                showOverlay("房间已关闭", false)
            }
            "error" -> {
                // 从邀请卡点进来但房间已不存在（房主退出/房间已满）：给出明确结论，不要只闪一行小字
                if (joiningFromInvite) {
                    joiningFromInvite = false
                    showOverlay("邀请已失效：房间已关闭或已开局", false)
                } else {
                    roomsStatus = json.optString("msg")
                    mainHandler.postDelayed({ roomsStatus = "" }, 2500)
                }
            }
            "chat" -> appendChat(
                json.optString("text"),
                json.optString("who"),
                json.optBoolean("mine")
            )
            // ===================== 赌注（联机房间 / 匹配对局） =====================
            "betWaiting" -> {
                // 发起者已提交，等待对方
                betIAmResponder = false
                betPendingOfferAmount = 0
                betUsed = true
                betMyAmount = json.optLong("myAmount", betMyAmount)
                betDialog = BetDialog(BetDialogKind.WAITING, myAmount = betMyAmount)
            }
            "betOffer" -> {
                // 对方发起了赌约，我是响应方：记住金额，可随时经"赌"入口重开
                betIAmResponder = true
                betPendingOfferAmount = json.optLong("amount", 0L)
                betDialog = BetDialog(BetDialogKind.RESPOND, opponentAmount = betPendingOfferAmount)
                // 接收方是被动弹窗，不会主动点"赌"，这里主动拉一次余额用于校验/展示
                refreshBetBalance()
            }
            "betSent" -> {
                // 我已接受并提交了金额，等待发起者确认：关闭响应弹窗并标记已响应
                betMyAmount = json.optLong("myAmount", betMyAmount)
                betIAmResponder = false
                betPendingOfferAmount = 0
                betUsed = true
                betDialog = null
                tip("已提交，等待对方确认")
            }
            "betConfirm" -> {
                // 发起者收到对方金额，确认锁定
                betIAmResponder = false
                betPendingOfferAmount = 0
                betDialog = BetDialog(
                    BetDialogKind.CONFIRM,
                    myAmount = json.optLong("myAmount", betMyAmount),
                    opponentAmount = json.optLong("opponentAmount", 0L)
                )
            }
            "betRejected" -> {
                betUsed = true
                betIAmResponder = false
                betPendingOfferAmount = 0
                betDialog = null
                tip(if (json.optBoolean("byMe")) "已拒绝对方的赌约（本局不再可赌）" else "对方拒绝了赌约（本局不再可赌）")
            }
            "betDeclined" -> {
                betUsed = true
                betIAmResponder = false
                betPendingOfferAmount = 0
                betDialog = null
                tip(if (json.optBoolean("byMe")) "已放弃赌约（本局不再可赌）" else "对方不同意，赌约作废（本局不再可赌）")
            }
            "betLocked" -> {
                betLocked = true
                betIAmResponder = false
                betPendingOfferAmount = 0
                betDialog = null
                betMyAmount = json.optLong("myAmount", 0L)
                betOpponentAmount = json.optLong("opponentAmount", 0L)
                tip("赌约已锁定：你 ${betMyAmount} / 对手 ${betOpponentAmount}")
                refreshBetBalance()
            }
            "betError" -> tip(json.optString("msg").ifBlank { "下注失败" })
            "betResult" -> {
                val delta = json.optLong("delta", 0L)
                betResult = BetResult(
                    win = json.optBoolean("win"),
                    draw = json.optBoolean("draw"),
                    myAmount = json.optLong("myAmount", 0L),
                    opponentAmount = json.optLong("opponentAmount", 0L),
                    delta = delta
                )
                refreshBetBalance()
                tip(
                    when {
                        betResult!!.draw -> "平局，赌注已退回"
                        betResult!!.win -> "赌注结算：你获得了 ${betResult!!.delta} token"
                        else -> "赌注结算：你输掉了 ${-betResult!!.delta} token"
                    }
                )
            }
            "devFlip" -> devFlipped = json.optBoolean("on")
            "remoteWin" -> {
                over = true
                showResult(json.optInt("winner"))
            }
            "sync" -> {
                val arr = json.optJSONArray("board")
                if (arr != null) {
                    val nb = GomokuEngine.emptyBoard()
                    for (i in nb.indices) nb[i] = arr.optInt(i)
                    board = nb
                }
            }
        }
    }

    // ---------------------------------------------------------
    // 匹配模式
    // ---------------------------------------------------------
    fun ensureConnected() {
        if (ws == null) connect()
    }

    fun startMatch() {
        ensureConnected()
        mode = "match"
        online = true
        myColor = null
        started = false
        waiting = false
        matching = true
        board = GomokuEngine.emptyBoard()
        current = 1
        over = false
        history.clear()
        chat.clear()
        overlayMsg = null
        banner = null
        matchResultInfo = null
        resetBetState()
        view = "game"
        status = "匹配中…"
        send(mapOf("type" to "match", "playerId" to playerId))
    }

    fun cancelMatch() {
        if (!matching) return
        matching = false
        send(mapOf("type" to "cancelMatch"))
        backToLobby()
    }

    // ---------------------------------------------------------
    // 大厅 / 房间
    // ---------------------------------------------------------
    fun startAi() {
        mode = "ai"
        online = false
        myColor = null
        started = false
        waiting = false
        board = GomokuEngine.emptyBoard()
        current = 1
        over = false
        history.clear()
        chat.clear()
        overlayMsg = null
        banner = null
        resetBetState()
        view = "game"
        status = "轮到你落子（黑棋）"
        flashBanner(1, 1)
    }

    fun refreshRooms() {
        send(mapOf("type" to "list"))
    }

    fun createRoom(name: String) {
        send(mapOf("type" to "create", "name" to name.trim()))
    }

    fun joinRoom(id: String) {
        send(mapOf("type" to "join", "roomId" to id))
    }

    fun leaveRoom() {
        if (online) send(mapOf("type" to "leave"))
        online = false
        started = false
        waiting = false
        roomId = ""
        resetBetState()
    }

    fun backToRooms() {
        leaveRoom()
        chat.clear()
        overlayMsg = null
        banner = null
        matchResultInfo = null
        view = if (mode == "match") "lobby" else "rooms"
        refreshRooms()
    }

    fun backToLobby() {
        resetBetState()
        view = "lobby"
    }

    /** 请求退出对局：进行中（尤其赌约已锁定）退出即逃跑判负，弹二次确认；已结束直接走。 */
    fun requestExit() {
        if (!started || over) { backToRooms(); return }
        exitConfirm = true
    }
    fun confirmExit() {
        exitConfirm = false
        backToRooms()
    }
    fun cancelExit() { exitConfirm = false }

    // ---------------------------------------------------------
    // 对局（AI 本地）
    // ---------------------------------------------------------
    private var online by mutableStateOf(false)

    private fun setStone(r: Int, c: Int, player: Int) {
        val nb = board.copyOf()
        nb[GomokuEngine.idx(r, c)] = player
        board = nb
    }

    fun onBoardTap(r: Int, c: Int) {
        if (over) return
        if (mode == "ai") {
            if (current != 1 && !devInfinite) return
            if (board[GomokuEngine.idx(r, c)] != 0) return
            val mover = if (devInfinite) 1 else current
            placeLocal(r, c, mover)
            if (over) return
            if (devInfinite) {
                current = 1
                status = "轮到你落子（黑棋）"
            } else {
                current = 2
                status = "AI 思考中…"
                scope.launch {
                    delay(350)
                    aiMove()
                }
            }
        } else if (mode == "online" || mode == "match") {
            if (!started) return
            if (current != myColor && !devInfinite) return
            if (board[GomokuEngine.idx(r, c)] != 0) return
            send(mapOf("type" to "move", "r" to r, "c" to c, "force" to devInfinite))
        }
    }

    private fun placeLocal(r: Int, c: Int, player: Int) {
        setStone(r, c, player)
        history.add(r to c)
        if (GomokuEngine.checkWin(board, r, c, player)) {
            over = true
            var winner = player
            if (devFlipped && player != (myColor ?: 1)) winner = myColor ?: 1
            showResult(winner)
            return
        }
        if (GomokuEngine.isFull(board)) {
            over = true
            showResult(0)
        }
    }

    private fun aiMove() {
        val mv = GomokuEngine.getBestMove(board, 2) ?: run {
            over = true
            showResult(0)
            return
        }
        val (r, c) = mv
        placeLocal(r, c, 2)
        if (!over) {
            current = 1
            status = "轮到你落子（黑棋）"
        }
    }

    fun undo() {
        if (mode != "ai" || over || history.isEmpty()) return
        val steps = minOf(2, history.size)
        repeat(steps) {
            val (r, c) = history.removeAt(history.lastIndex)
            setStone(r, c, 0)
        }
        current = 1
        over = false
        overlayMsg = null
        status = "轮到你落子（黑棋）"
    }

    // ---------------------------------------------------------
    // 对局（联机）
    // ---------------------------------------------------------
    private fun enterRoom(color: Int) {
        mode = "online"
        online = true
        myColor = color
        started = false
        waiting = true
        inviteDrawer = false   // 新房间别把上一局的邀请抽屉状态带进来
        board = GomokuEngine.emptyBoard()
        current = 1
        over = false
        history.clear()
        overlayMsg = null
        banner = null
        resetBetState()
        view = "game"
        status = "等待对手加入…"
    }

    private fun startOnline(yourColor: Int) {
        started = true
        inviteDrawer = false
        myColor = yourColor
        board = GomokuEngine.emptyBoard()
        current = 1
        over = false
        history.clear()
        resetBetState()
        waiting = false
        status = if (current == myColor) "你的回合（$colorName）" else "对手回合…"
        flashBanner(myColor ?: 1, 1)
    }

    private fun restartOnline() {
        started = true
        board = GomokuEngine.emptyBoard()
        current = 1
        over = false
        history.clear()
        resetBetState()
        waiting = false
        overlayMsg = null
        status = if (current == myColor) "你的回合（$colorName）" else "对手回合…"
        flashBanner(myColor ?: 1, 1)
    }

    private fun applyRemoteMove(r: Int, c: Int, player: Int, cur: Int, overFlag: Boolean, winner: Int) {
        setStone(r, c, player)
        history.add(r to c)
        if (overFlag) {
            over = true
            showResult(winner)
            return
        }
        current = cur
        status = if (current == myColor) "你的回合（$colorName）" else "对手回合…"
    }

    fun restart() {
        overlayMsg = null
        if (mode == "online" || mode == "match") send(mapOf("type" to "restart"))
        else startAi()
    }

    // ---------------------------------------------------------
    // 结算 / 横幅
    // ---------------------------------------------------------
    fun showResult(winner: Int) {
        overlayMsg = when {
            winner == 0 -> "平局！"
            mode == "ai" -> if (winner == 1) "你赢了！" else "AI 获胜，再试一次？"
            (mode == "online" || mode == "match") -> if (winner == myColor) "你赢了！"
            else "对方（${if (winner == 1) "黑棋" else "白棋"}）胜利！"

            else -> if (winner == 1) "黑棋获胜！" else "白棋获胜！"
        }
    }

    private fun showOverlay(msg: String, showRestart: Boolean) {
        overlayMsg = msg
        overlayShowRestart = showRestart
    }

    var overlayShowRestart by mutableStateOf(true)
        private set

    private fun flashBanner(yourColor: Int, firstMove: Int) {
        val colorText = if (yourColor == 1) "执 黑棋" else "执 白棋"
        val turnText = when {
            mode == "ai" -> "你先手 · 落子开始"
            mode == "online" && firstMove == yourColor -> "你先手 · 请落子"
            mode == "online" -> "对手先手 · 请等待"
            else -> "黑棋先手"
        }
        banner = colorText to turnText
        scope.launch {
            delay(2600)
            banner = null
        }
    }

    // ---------------------------------------------------------
    // 聊天
    // ---------------------------------------------------------
    fun sendChat(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // 只要"确实在服务端的某个房间里"（roomId 由服务端下发）就必须真发给服务端:
        // 由服务端回显 mine=true 给自己、mine=false 给对手,两边才都能看到。
        // 不能用 mode / matching 这类客户端状态判断——它们一旦不同步（例如匹配模式的 mode 是 "match"、
        // 或 matching 卡在 true），发言就会被误判成本地回显，对手永远收不到。
        // 不在任何房间时（AI 对局、匹配等待中、已断线）才本地回显，避免消息发出去凭空消失。
        if (roomId.isNotEmpty() && send(mapOf("type" to "chat", "text" to t))) return
        // 不在房间（AI 对局 / 匹配等待中），或发送实际失败（连接已断/正在关闭）：
        // 本地回显，保证"自己刚说的话"永远看得到，不会静默丢失
        appendChat(t, if (myColor == 2) "白" else "黑", true)
    }

    private fun appendChat(text: String, who: String, mine: Boolean) {
        chat.add(ChatMsg(text, who, mine))
        if (chat.size > 20) chat.removeAt(0)
    }

    // ---------------------------------------------------------
    // 赌注（仅联机房间 / 匹配对局）
    // ---------------------------------------------------------
    /**
     * 「赌」入口是否显示：只在联机房间 / 匹配对局、且对局进行中时显示（AI 对局永远 false）。
     * 判定用服务端下发的 roomId（而非 mode），与发言路由同一依据。
     */
    val betEntryVisible: Boolean get() = roomId.isNotEmpty() && started && !over
    /** 当前能否「发起赌约」：入口可见 + 本局还没发起过 + 还没锁定（一局只能发起一次）。 */
    /**
     * 「赌」入口是否可点：
     * - 响应方且对方有未了结的赌约 → 可点，用于重开 RESPOND（看到历史/再次提交）；
     * - 发起方且本局尚未发起 → 可点，开 OFFER；
     * 其余（已发起/已响应/已锁定）→ 禁用。
     */
    val betEntryEnabled: Boolean
        get() = betEntryVisible && !betLocked && (
            (betIAmResponder && betPendingOfferAmount > 0) ||
            (!betIAmResponder && !betUsed)
        )

    /** 打开下注弹窗（同时拉一次真实余额，用于校验/展示）。 */
    fun openBetDialog() {
        if (!betEntryVisible || betLocked) return
        refreshBetBalance()
        if (betIAmResponder && betPendingOfferAmount > 0) {
            // 我是响应方：重开待响应的赌约（展示对方下注历史），可再次提交或拒绝
            betDialog = BetDialog(BetDialogKind.RESPOND, opponentAmount = betPendingOfferAmount)
        } else if (!betUsed) {
            betDialog = BetDialog(BetDialogKind.OFFER)
        }
    }

    /** 刷新平台 token 余额（仅用于下注校验与展示；权威校验始终在服务端）。 */
    fun refreshBetBalance() {
        val ctx = appContext ?: return
        val uid = AuroraApi.currentUserId
        if (uid <= 0) return
        scope.launch {
            runCatching { AiChatManager.syncServerLimit(ctx, uid) }
            tokenBalance = AiChatManager.getServerTokenBalance(ctx, uid)
        }
    }

    /** 发起者提交自己的下注金额。 */
    fun submitBetOffer(amount: Long) {
        if (amount <= 0) { tip("下注金额必须大于 0"); return }
        val bal = tokenBalance
        if (bal >= 0 && amount > bal) { tip("余额不足，最多只能下注 $bal"); return }
        betDialog = null
        if (!send(mapOf("type" to "bet", "amount" to amount))) { tip("连接已断开，下注失败"); return }
        betUsed = true
        betMyAmount = amount
        betDialog = BetDialog(BetDialogKind.WAITING)
    }

    /** 对方接受赌约并提交自己的下注金额。 */
    fun submitBetAccept(amount: Long) {
        if (amount <= 0) { tip("下注金额必须大于 0"); return }
        val bal = tokenBalance
        if (bal >= 0 && amount > bal) { tip("余额不足，最多只能下注 $bal"); return }
        // 不在发送前关闭弹窗：成功由服务端 betSent 关闭，失败由 betError 保留以便重填 / 拒绝，
        // 避免「提交失败却关了弹窗」导致发起方在 WAITING 里卡死。
        send(mapOf("type" to "betAccept", "amount" to amount))
    }

    /** 对方拒绝赌约（一局只能发起一次，此后本局不再有赌约）。 */
    fun rejectBet() {
        betDialog = null
        betUsed = true
        send(mapOf("type" to "betReject"))
    }

    /** 发起者确认锁定（此时服务端才真正扣款托管）。 */
    fun confirmBet() {
        betDialog = null
        send(mapOf("type" to "betConfirm"))
    }

    /** 发起者放弃 / 不同意（赌约作废，本局不再允许）。 */
    fun declineBet() {
        betDialog = null
        betUsed = true
        send(mapOf("type" to "betDecline"))
    }

    /** 清空本局赌注相关状态（新对局 / 离开房间时调用）。 */
    private fun resetBetState() {
        betDialog = null
        betUsed = false
        betLocked = false
        betMyAmount = 0L
        betOpponentAmount = 0L
        betIAmResponder = false
        betPendingOfferAmount = 0L
        betResult = null
    }

    private fun tip(msg: String) {
        val ctx = appContext ?: return
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------
    // 开发者模式
    // ---------------------------------------------------------
    fun toggleDev() {
        if (!devPassed) showDevAuth = true
        else devEnabled = !devEnabled
    }

    fun submitDevAuth(password: String) {
        if (password.trim().equals("LL", ignoreCase = true)) {
            devPassed = true
            devEnabled = true
            showDevAuth = false
            devAuthError = ""
        } else {
            devAuthError = "密码错误，请重试"
        }
    }

    fun devWin() {
        over = true
        showResult(myColor ?: 1)
        if (online) send(mapOf("type" to "devWin"))
    }

    fun devFill() {
        if (online) {
            send(mapOf("type" to "devFill"))
        } else {
            val color = myColor ?: 1
            val nb = board.copyOf()
            for (i in nb.indices) if (nb[i] == 0) nb[i] = color
            board = nb
        }
    }

    fun devFlip() {
        devFlipped = !devFlipped
        if (online) send(mapOf("type" to "devFlip"))
    }

    fun toggleInfinite() {
        devInfinite = !devInfinite
    }
}
