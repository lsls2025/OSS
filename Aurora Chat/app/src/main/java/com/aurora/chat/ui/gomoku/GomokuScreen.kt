package com.aurora.chat.ui.gomoku

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawScopeStroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import android.widget.Toast
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.chat.buildGomokuInviteCard
import com.aurora.chat.ui.components.UserAvatar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.ui.theme.AuroraChatTheme
import com.aurora.chat.ui.theme.AuroraPrimary
import com.aurora.chat.ui.theme.AuroraPrimaryLight
import com.aurora.chat.ui.theme.BgDark
import com.aurora.chat.ui.theme.CardDark
import com.aurora.chat.ui.theme.CardDarkElevated
import com.aurora.chat.ui.theme.StatusOnline
import com.aurora.chat.ui.theme.TextDark
import com.aurora.chat.ui.theme.TextDarkSec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val BG_TOP = Color(0xFF0f2347)
private val BG_BOTTOM = Color(0xFF1E40AF)
private val ACCENT = AuroraPrimary
private val ACCENT2 = StatusOnline
private val TEXT = TextDark
private val MUTED = TextDarkSec
private val PANEL = CardDarkElevated
private val BOARD_BG = Color(0xFFe3b574)
private val BOARD_LINE = Color(0xFF6b4a2b)

@Composable
fun GomokuApp(game: GomokuGame) {
    AuroraChatTheme {
    val scope = rememberCoroutineScope()
    var chatInputOpen by remember { mutableStateOf(false) }
    var chatText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var lastEnter by remember { mutableStateOf(0L) }
    var singleTimer by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        game.connect()
        onDispose { game.dispose() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(Brush.verticalGradient(listOf(BG_TOP, BG_BOTTOM)))
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.Enter -> {
                        if (chatInputOpen) return@onKeyEvent false
                        val now = System.currentTimeMillis()
                        val isDouble = now - lastEnter < 400
                        lastEnter = now
                        if (isDouble) {
                            singleTimer?.cancel()
                            game.toggleDev()
                        } else {
                            singleTimer?.cancel()
                            singleTimer = scope.launch {
                                delay(420)
                                if (game.view == "game") {
                                    chatInputOpen = !chatInputOpen
                                    if (chatInputOpen) focusRequester.requestFocus()
                                }
                            }
                        }
                        true
                    }
                    Key.One -> if (game.devEnabled) { game.devFill(); true } else false
                    Key.Two -> if (game.devEnabled) { game.devFlip(); true } else false
                    else -> false
                }
            }
    ) {
        when (game.view) {
            "lobby" -> LobbyScreen(game)
            "rooms" -> RoomsScreen(game)
            "game" -> GameScreen(
                game = game,
                chatInputOpen = chatInputOpen,
                chatText = chatText,
                setChatInputOpen = { chatInputOpen = it },
                setChatText = { chatText = it },
                focusRequester = focusRequester,
                onToggleChat = {
                    chatInputOpen = !chatInputOpen
                    if (chatInputOpen) focusRequester.requestFocus()
                }
            )
        }

        if (game.showDevAuth) DevAuthDialog(game)
        if (game.overlayMsg != null) ResultOverlay(game)
        if (game.betDialog != null) BetDialogs(game)
        if (game.exitConfirm) ExitConfirmDialog(game)
    }
    }
}

// ------------------------- 大厅 -------------------------
@Composable
private fun LobbyScreen(game: GomokuGame) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("五子棋", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = TEXT,
            letterSpacing = 8.sp)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            MenuCard("AI", "与电脑对弈", onClick = { game.startAi() }, width = 108.dp, height = 160.dp)
            Spacer(Modifier.width(12.dp))
            MenuCard("联机", "与好友实时对战", onClick = {
                game.view = "rooms"
                game.refreshRooms()
            }, width = 108.dp, height = 160.dp)
            Spacer(Modifier.width(12.dp))
            MenuCard("匹配", "匹配任意玩家", onClick = { game.startMatch() }, width = 108.dp, height = 160.dp)
        }
    }
}

@Composable
private fun MenuCard(
    label: String,
    desc: String,
    onClick: () -> Unit,
    width: Dp = 160.dp,
    height: Dp = 190.dp
) {
    Column(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(PANEL.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ACCENT,
            letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        Text(desc, fontSize = 13.sp, color = MUTED, textAlign = TextAlign.Center)
    }
}

// ------------------------- 房间大厅 -------------------------
@Composable
private fun RoomsScreen(game: GomokuGame) {
    var roomName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { game.backToLobby() }) { Text("← 大厅", color = TEXT) }
            Text("联机对战", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TEXT)
            TextButton(onClick = { game.refreshRooms() }) { Text("刷新", color = TEXT) }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (game.rooms.isEmpty()) {
                item { Text("暂无房间，点下方创建一个吧", color = MUTED, modifier = Modifier.padding(24.dp)) }
            }
            items(game.rooms) { room ->
                RoomItem(room) { game.joinRoom(room.id) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = roomName,
                onValueChange = { roomName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("房间名（可留空）", color = MUTED) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TEXT,
                    unfocusedTextColor = TEXT,
                    cursorColor = ACCENT
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { game.createRoom(roomName) })
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { game.createRoom(roomName) },
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
            ) { Text("创建房间") }
        }
        if (game.roomsStatus.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(game.roomsStatus, color = Color(0xFFf87171))
        }
    }
}

@Composable
private fun RoomItem(room: RoomInfo, onJoin: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PANEL.copy(alpha = 0.6f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(room.name.ifEmpty { "未命名房间" }, color = TEXT, fontSize = 16.sp)
            Text(
                "${if (room.status == "playing") "对弈中" else "等待中"} · ${room.count}/2",
                color = MUTED, fontSize = 12.sp
            )
        }
        Button(
            onClick = onJoin,
            enabled = room.canJoin,
            modifier = Modifier.width(90.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
        ) { Text(if (room.canJoin) "进入" else "不可进") }
    }
}

// ------------------------- 对局 -------------------------
@Composable
private fun GameScreen(
    game: GomokuGame,
    chatInputOpen: Boolean,
    chatText: String,
    setChatInputOpen: (Boolean) -> Unit,
    setChatText: (String) -> Unit,
    focusRequester: FocusRequester,
    onToggleChat: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        // 系统返回键：聊天输入框打开时先关输入框，否则弹退出确认（退出=逃跑判负）
        BackHandler(enabled = !chatInputOpen) { game.requestExit() }
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { game.requestExit() }) { Text("← 大厅", color = TEXT) }
                // 状态（你的回合 / 对手回合 / 匹配中）占中间，单行省略；不再显示"五子棋"标题以免拥挤
                Text(
                    game.status,
                    color = ACCENT, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                TextButton(onClick = onToggleChat) {
                    Text("聊天", color = ACCENT, fontSize = 13.sp)
                }
                // 「赌」入口：仅联机房间 / 匹配对局、对局进行中显示（AI 对局不显示）
                if (game.betLocked) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "赌注 你${game.betMyAmount} : ${game.betOpponentAmount}（已锁）",
                        color = Color(0xFFFFB020), fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (game.betEntryVisible) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = game.betEntryEnabled,
                        onClick = { game.openBetDialog() }
                    ) { Text("赌", color = if (game.betEntryEnabled) ACCENT else MUTED, fontSize = 13.sp) }
                }
            }
            if (game.mode == "match" && !game.matching) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    RankBadge("我 · ${game.myRank}")
                    Spacer(Modifier.width(8.dp))
                    if (game.opponentRank.isNotEmpty()) {
                        Text("VS", color = MUTED, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        RankBadge("对手 · ${game.opponentRank}")
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(visible = game.banner != null) {
                game.banner?.let { (color, turn) -> StartBanner(color, turn) }
            }
            Spacer(Modifier.height(8.dp))
            GameBoard(game)
            Spacer(Modifier.height(12.dp))
            if (game.mode == "ai") {
                Button(
                        onClick = { game.undo() },
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
                    ) { Text("悔棋") }
            }
            if (chatInputOpen) {
                Spacer(Modifier.height(8.dp))
                ChatInputField(
                    value = chatText,
                    onValueChange = setChatText,
                    focusRequester = focusRequester,
                    onSend = {
                        game.sendChat(chatText)
                        setChatText("")
                        setChatInputOpen(false)
                    },
                    onCancel = { setChatInputOpen(false) }
                )
            }
        }

        ChatLog(game, chatInputOpen)
        if (game.devEnabled) DevPanel(game)
        // 等待遮罩最后画：必须盖住聊天记录/开发面板，且邀请抽屉要在最上层
        if (game.waiting) WaitingMask(game)
    }
}

@Composable
private fun StartBanner(colorText: String, turnText: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(ACCENT.copy(alpha = 0.18f), AuroraPrimary.copy(alpha = 0.18f))))
            .padding(16.dp, 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("对局开始", fontSize = 24.sp, fontWeight = FontWeight.Bold,
            color = ACCENT, letterSpacing = 6.sp)
        Spacer(Modifier.height(4.dp))
        Text("$colorText   $turnText", fontSize = 14.sp, color = TEXT)
    }
}

/** 匹配段位小徽标：用于「我 · 白银」「对手 · 黄金」这类等级标签（圆角胶囊样式） */
@Composable
private fun RankBadge(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = TEXT,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ACCENT.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun WaitingMask(game: GomokuGame) {
    Box(Modifier.fillMaxSize().background(Color(0xD20f172a)), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp)
        ) {
            androidx.compose.material3.CircularProgressIndicator(color = ACCENT)
            Spacer(Modifier.height(18.dp))
            Text("等待对手加入…", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                color = ACCENT, letterSpacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            Text("房间号 ${game.currentRoomId}", fontSize = 13.sp, color = MUTED, letterSpacing = 1.sp)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { game.inviteDrawer = true },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
            ) {
                Text("邀请好友", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Text("可随时返回大厅", color = MUTED, fontSize = 12.sp)
        }

        // ── 邀请好友抽屉：遮罩淡入 + 面板自下而上滑出 ──
        AnimatedVisibility(
            visible = game.inviteDrawer,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(140)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(
                        indication = null,
                        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource()
                    ) { game.inviteDrawer = false }
            )
        }
        AnimatedVisibility(
            visible = game.inviteDrawer,
            enter = slideInVertically(tween(260)) { it },
            exit = slideOutVertically(tween(200)) { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            InviteFriendsSheet(game, onDismiss = { game.inviteDrawer = false })
        }
    }
}

/**
 * 邀请好友抽屉：列出全部好友，点一个人就给他私聊发一张「五子棋邀请卡片」。
 * 允许多发（每个好友一张），已经发过的显示「已邀请」，可再次点击重发。
 */
@Composable
private fun InviteFriendsSheet(game: GomokuGame, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sentIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var sendingId by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val r = ChatRepository.getFriends()
        if (r.success) friends = r.data ?: emptyList()
        loading = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.76f)
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(PANEL)
            // 抽屉自身吃掉点击，避免穿透到背后的遮罩把抽屉关掉（这是之前禁言抽屉踩过的坑）
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        // 顶部把手
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                .background(Color(0x33FFFFFF)))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("邀请好友", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TEXT)
                Spacer(Modifier.height(2.dp))
                Text("好友点一下卡片即可直接进入这局", fontSize = 12.sp, color = MUTED)
            }
            Text("✕", fontSize = 18.sp, color = MUTED,
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x22FFFFFF)))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = ACCENT, strokeWidth = 2.dp)
            }
        } else if (friends.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("还没有好友，先去加个好友再来邀请吧", fontSize = 13.sp, color = MUTED, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(friends, key = { it.id }) { f ->
                    val sent = sentIds.contains(f.id)
                    val busy = sendingId == f.id
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !busy) {
                                val room = game.currentRoomId
                                if (room.isEmpty()) {
                                    Toast.makeText(ctx, "房间已关闭，请重新创建", Toast.LENGTH_SHORT).show()
                                    return@clickable
                                }
                                scope.launch {
                                    sendingId = f.id
                                    val card = buildGomokuInviteCard(room, AuroraApi.currentUserId, AuroraApi.currentUserName)
                                    val r = ChatRepository.sendMessage(f.id, card)
                                    sendingId = 0L
                                    if (r.success) {
                                        sentIds = sentIds + f.id
                                        Toast.makeText(ctx, "已邀请 ${f.username}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(ctx, "邀请失败：${r.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(userId = f.id, userName = f.username, size = 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.username.ifBlank { "用户${f.id}" }, fontSize = 15.sp, color = TEXT,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(f.signature.ifBlank { "ID: ${f.id}" }, fontSize = 12.sp, color = MUTED,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(10.dp))
                        val label = when {
                            busy -> "发送中…"
                            sent -> "已邀请"
                            else -> "邀请"
                        }
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (sent) MUTED else Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (sent) Color(0x22FFFFFF) else ACCENT)
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(start = 74.dp).height(0.5.dp)
                        .background(Color(0x14FFFFFF)))
                }
            }
        }
    }
}

@Composable
private fun GameBoard(game: GomokuGame) {
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val maxBoard = minOf(maxWidth, maxHeight * 0.7f).coerceAtMost(564.dp)
        val pad: Dp = maxBoard * 30f / 564f
        val cell: Dp = (maxBoard - pad * 2) / (GomokuEngine.SIZE - 1)
        val radius: Dp = maxBoard * 15f / 564f

        val padPx = with(density) { pad.toPx() }
        val cellPx = with(density) { cell.toPx() }
        val radiusPx = with(density) { radius.toPx() }
        val lineW = with(density) { 1.dp.toPx() }
        val borderW = with(density) { 2.dp.toPx() }
        val markW = with(density) { 2.dp.toPx() }
        val starR = radiusPx * 0.22f

        Canvas(
            Modifier
                .size(maxBoard)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val c = ((offset.x - padPx) / cellPx).roundToInt()
                        val r = ((offset.y - padPx) / cellPx).roundToInt()
                        if (r in 0 until GomokuEngine.SIZE && c in 0 until GomokuEngine.SIZE) {
                            game.onBoardTap(r, c)
                        }
                    }
                }
        ) {
            val boardState = game.board
            drawRect(BOARD_BG, size = size)
            drawRect(
                color = BOARD_LINE.copy(alpha = 0.4f),
                topLeft = Offset(8f, 8f),
                size = Size(size.width - 16f, size.height - 16f),
                style = DrawScopeStroke(borderW)
            )
            for (i in 0 until GomokuEngine.SIZE) {
                val p = padPx + i * cellPx
                drawLine(BOARD_LINE, Offset(padPx, p), Offset(padPx + (GomokuEngine.SIZE - 1) * cellPx, p), strokeWidth = lineW)
                drawLine(BOARD_LINE, Offset(p, padPx), Offset(p, padPx + (GomokuEngine.SIZE - 1) * cellPx), strokeWidth = lineW)
            }
            val stars = listOf(3 to 3, 3 to 11, 11 to 3, 11 to 11, 7 to 7)
            for ((r, c) in stars) {
                drawCircle(BOARD_LINE, starR, Offset(padPx + c * cellPx, padPx + r * cellPx))
            }
            for (r in 0 until GomokuEngine.SIZE) {
                for (c in 0 until GomokuEngine.SIZE) {
                    val v = boardState[GomokuEngine.idx(r, c)]
                    if (v != 0) {
                        val x = padPx + c * cellPx
                        val y = padPx + r * cellPx
                        val brush = if (v == 1)
                            Brush.radialGradient(
                                listOf(Color(0xFF777777), Color(0xFF0a0a0a)),
                                center = Offset(x - radiusPx * 0.33f, y - radiusPx * 0.33f),
                                radius = radiusPx
                            )
                        else
                            Brush.radialGradient(
                                listOf(Color.White, Color(0xFFc9c9c9)),
                                center = Offset(x - radiusPx * 0.33f, y - radiusPx * 0.33f),
                                radius = radiusPx
                            )
                        drawCircle(brush, radiusPx, Offset(x, y))
                        drawCircle(Color.Black.copy(alpha = 0.25f), radiusPx, Offset(x, y), style = DrawScopeStroke(radiusPx * 0.08f))
                    }
                }
            }
            if (game.history.isNotEmpty()) {
                val (r, c) = game.history.last()
                drawCircle(Color(0xFFe74c3c), radiusPx * 0.55f, Offset(padPx + c * cellPx, padPx + r * cellPx), style = DrawScopeStroke(markW))
            }
        }
    }
}

// ------------------------- 聊天 -------------------------
@Composable
private fun BoxScope.ChatLog(game: GomokuGame, inputOpen: Boolean) {
    if (game.chat.isEmpty()) return
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 12.dp, end = 12.dp, bottom = if (inputOpen) 84.dp else 12.dp)
            .fillMaxWidth(0.9f)
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState())
    ) {
        game.chat.forEach { msg ->
            // 双方都带气泡：自己的靠右、实色气泡；对方的靠左、卡片气泡，清晰区分
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (msg.mine) ACCENT else Color(0xFF2A2A30))
                        .padding(10.dp, 6.dp)
                ) {
                    Text(
                        "[${msg.who}]",
                        fontSize = 11.sp,
                        color = if (msg.mine) Color.White.copy(alpha = 0.85f) else ACCENT2,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        msg.text,
                        fontSize = 13.sp,
                        color = if (msg.mine) Color.White else TEXT
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .focusRequester(focusRequester)
                .onKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown) {
                        when (ev.key) {
                            Key.Enter -> { onSend(); true }
                            Key.Escape -> { onCancel(); true }
                            else -> false
                        }
                    } else false
                },
            placeholder = { Text("回车发送，Esc 关闭", color = MUTED) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TEXT,
                unfocusedTextColor = TEXT,
                cursorColor = ACCENT
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() })
        )
}

// ------------------------- 开发者面板 -------------------------
@Composable
private fun BoxScope.DevPanel(game: GomokuGame) {
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PANEL.copy(alpha = 0.95f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("开发者模式", fontSize = 12.sp, color = MUTED)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { game.devWin() },
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
        ) { Text("胜利") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { game.toggleInfinite() },
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
        ) { Text(if (game.devInfinite) "无限出棋：开" else "无限出棋：关") }
    }
}

// ------------------------- 结算 / 验证弹窗 -------------------------
@Composable
private fun ResultOverlay(game: GomokuGame) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(PANEL)
                .padding(32.dp, 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
                ) {
                Text(game.overlayMsg ?: "", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TEXT)
                // 有赌约且已结算：在胜负文案下补一行金额变动；没有赌约则不显示（保持原提示语）
                game.betResult?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            r.draw -> "平局，赌注 ${r.myAmount} token 已退回"
                            r.win -> "你获得了 ${r.opponentAmount} token"
                            else -> "你输掉了 ${r.myAmount} token"
                        },
                        fontSize = 15.sp,
                        color = if (r.win) Color(0xFF3DD68C) else Color(0xFFFF7676),
                        fontWeight = FontWeight.Medium
                    )
                }
            Spacer(Modifier.height(24.dp))
            Row {
                if (game.overlayShowRestart) {
                    Button(
                        onClick = { game.restart() },
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
                    ) { Text("再来一局") }
                    Spacer(Modifier.width(12.dp))
                }
                Button(
                    onClick = { game.backToRooms() },
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
                ) { Text("返回大厅") }
            }
        }
    }
}

// ------------------------- 退出确认（逃跑判负） -------------------------
@Composable
private fun ExitConfirmDialog(game: GomokuGame) {
    val betLine = if (game.betLocked)
        "本局赌约已锁定，退出将被视为逃跑并扣除你押注的 ${game.betMyAmount} token。"
    else
        "退出当前对局将被视为逃跑，对方胜利"
    AlertDialog(
        onDismissRequest = { game.cancelExit() },
        title = {
            Text(
                "确认退出对局？",
                color = Color(0xFFFF5C5C),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "退出即视为逃跑",
                    color = Color(0xFFFF5C5C),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(betLine, color = TEXT, fontSize = 13.sp, lineHeight = 19.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = { game.confirmExit() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5C5C), contentColor = Color.White)
            ) { Text("确认退出（判负）") }
        },
        dismissButton = {
            TextButton(onClick = { game.cancelExit() }) { Text("继续对局", color = ACCENT) }
        }
    )
}

@Composable
private fun DevAuthDialog(game: GomokuGame) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { game.showDevAuth = false },
        title = { Text("开发者验证", color = TEXT) },
        text = {
            Column {
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text("请输入密码", color = MUTED) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TEXT,
                        unfocusedTextColor = TEXT,
                        cursorColor = ACCENT
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { game.submitDevAuth(pw) })
                )
                if (game.devAuthError.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(game.devAuthError, color = Color(0xFFFF7676), fontSize = 12.sp)
                }
            }
        },
        confirmButton = { Button(onClick = { game.submitDevAuth(pw) }, colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)) { Text("确认") } },
        dismissButton = { TextButton(onClick = { game.showDevAuth = false }) { Text("取消", color = TEXT) } }
    )
}

// ------------------------- 赌注弹窗 -------------------------
// 仅在「联机房间」/「匹配对局」中出现（GomokuGame 里 betEntryVisible 已限定），AI 对局根本没有入口。
@Composable
private fun BetDialogs(game: GomokuGame) {
    val d = game.betDialog ?: return
    when (d.kind) {
        BetDialogKind.OFFER -> BetAmountDialog(
            title = "下注（你输了愿付多少）",
            desc = "锁定后中途退出视为逃跑 = 判负并扣款；一局只能发起一次。",
            balance = game.tokenBalance,
            confirmLabel = "提交",
            onConfirm = { game.submitBetOffer(it) },
            onCancel = { game.betDialog = null }
        )
        BetDialogKind.WAITING -> AlertDialog(
            onDismissRequest = { /* 等待对方响应阶段不可取消，避免误触丢失流程 */ },
            title = { Text("已提交，等待对方", color = TEXT) },
            text = { Text("你的下注：${d.myAmount} token。等待对方接受或拒绝……", color = MUTED, fontSize = 13.sp) },
            confirmButton = {}
        )
        BetDialogKind.RESPOND -> BetAmountDialog(
            title = "对方押了 ${d.opponentAmount} token",
            desc = "对方下注 ${d.opponentAmount} token。你输了愿付多少？只能接受或拒绝，无法静默关闭。",
            balance = game.tokenBalance,
            confirmLabel = "接受并下注",
            onConfirm = { game.submitBetAccept(it) },
            onReject = { game.rejectBet() }
        )
        BetDialogKind.CONFIRM -> AlertDialog(
            onDismissRequest = { /* 等待发起者确认，不可取消 */ },
            title = { Text("确认锁定赌约", color = TEXT) },
            text = {
                Column {
                    Text("你下注：${d.myAmount} token", color = TEXT, fontSize = 13.sp)
                    Text("对方下注：${d.opponentAmount} token", color = TEXT, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "锁定后将从双方余额各扣出上述金额并托管；中途退出视为逃跑=判负。一局只能发起一次。",
                        color = MUTED, fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { game.confirmBet() },
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
                ) { Text("同意锁定") }
            },
            dismissButton = {
                TextButton(onClick = { game.declineBet() }) { Text("不同意", color = Color(0xFFFF7676)) }
            }
        )
    }
}

@Composable
private fun BetAmountDialog(
    title: String,
    desc: String,
    balance: Long,
    confirmLabel: String,
    onConfirm: (Long) -> Unit,
    onCancel: (() -> Unit)? = null,
    onReject: (() -> Unit)? = null
) {
    var text by remember { mutableStateOf("") }
    val amount = text.trim().toLongOrNull() ?: 0L
    val tooMuch = balance >= 0 && amount > balance
    val invalid = amount <= 0 || tooMuch
    AlertDialog(
        onDismissRequest = onCancel ?: {},
        title = { Text(title, color = TEXT, fontSize = 16.sp) },
        text = {
            Column {
                Text(desc, color = MUTED, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(12) },
                    singleLine = true,
                    placeholder = { Text("输入 token 数量", color = MUTED) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TEXT,
                        unfocusedTextColor = TEXT,
                        cursorColor = ACCENT
                    )
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (balance >= 0) "我的余额：$balance token" else "余额读取中…",
                    color = if (tooMuch) Color(0xFFFF7676) else MUTED, fontSize = 12.sp
                )
                if (tooMuch) Text("超过余额，不能提交", color = Color(0xFFFF7676), fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(amount) },
                enabled = !invalid,
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.White)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            Row {
                if (onReject != null) {
                    TextButton(onClick = onReject) { Text("拒绝", color = Color(0xFFFF7676)) }
                    if (onCancel != null) Spacer(Modifier.width(4.dp))
                }
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("取消", color = TEXT) }
                }
            }
        }
    )
}
