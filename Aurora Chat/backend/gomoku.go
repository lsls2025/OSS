package main

import (
	"encoding/json"
	"log"
	"math/rand"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

// ============================================================================
// 五子棋 WebSocket 后端（Go 实现）
// 协议与旧 Node 服务 C:\五子棋\server.js 完全兼容，Android 客户端零改动。
// 数据与房间管理由 gomokuMu 全局互斥锁保护，所有业务函数约定调用方已持锁。
// ============================================================================

const gomokuSize = 15

// ------------------------- 棋局逻辑 -------------------------

type gomokuBoard [gomokuSize][gomokuSize]int

func gomokuEmptyBoard() gomokuBoard {
	var b gomokuBoard
	return b
}

func gomokuInBounds(r, c int) bool {
	return r >= 0 && r < gomokuSize && c >= 0 && c < gomokuSize
}

// gomokuCheckWin 以 (r,c) 为中心判断 player 是否五连。
func gomokuCheckWin(b *gomokuBoard, r, c, player int) bool {
	dirs := [4][2]int{{-1, 0}, {0, 1}, {-1, 1}, {1, 1}}
	for _, d := range dirs {
		dr, dc := d[0], d[1]
		count := 1
		for i := 1; gomokuInBounds(r+dr*i, c+dc*i) && b[r+dr*i][c+dc*i] == player; i++ {
			count++
		}
		for i := 1; gomokuInBounds(r-dr*i, c-dc*i) && b[r-dr*i][c-dc*i] == player; i++ {
			count++
		}
		if count >= 5 {
			return true
		}
	}
	return false
}

func gomokuIsFull(b *gomokuBoard) bool {
	for r := 0; r < gomokuSize; r++ {
		for c := 0; c < gomokuSize; c++ {
			if b[r][c] == 0 {
				return false
			}
		}
	}
	return true
}

// gomokuBoardFlat 返回 15x15 平铺一维数组（客户端 sync/spectate 期望格式）。
func gomokuBoardFlat(b *gomokuBoard) []int {
	flat := make([]int, gomokuSize*gomokuSize)
	for r := 0; r < gomokuSize; r++ {
		for c := 0; c < gomokuSize; c++ {
			flat[r*gomokuSize+c] = b[r][c]
		}
	}
	return flat
}

var gomokuIDChars = "abcdefghijklmnopqrstuvwxyz0123456789"

func gomokuGenID() string {
	b := make([]byte, 6)
	for i := range b {
		b[i] = gomokuIDChars[rand.Intn(len(gomokuIDChars))]
	}
	return string(b)
}

// ------------------------- 玩家档案与段位 -------------------------

type gomokuPlayer struct {
	Score  int    `json:"score"`
	Wins   int    `json:"wins"`
	Losses int    `json:"losses"`
	Name   string `json:"name"`
}

// gomokuShortID 安全截断 ID 用于日志（空串/短串不越界）。
func gomokuShortID(s string) string {
	if len(s) < 6 {
		return s
	}
	return s[:6]
}

func gomokuRankOf(score int) string {
	switch {
	case score >= 1800:
		return "王者"
	case score >= 1600:
		return "钻石"
	case score >= 1400:
		return "铂金"
	case score >= 1200:
		return "黄金"
	case score >= 1000:
		return "白银"
	default:
		return "青铜"
	}
}

var (
	gomokuMu            sync.Mutex
	gomokuRooms         = map[string]*gomokuRoom{}
	gomokuConns         = map[*gomokuConn]bool{}
	gomokuMatchQueue    []*gomokuConn
	gomokuPlayers       = map[string]*gomokuPlayer{}
	gomokuPlayerIDRe    = regexp.MustCompile(`^[0-9a-f]{32}$`)
	gomokuPlayersLoaded = false
)

func gomokuPlayersPath() string {
	execPath, err := os.Executable()
	if err != nil {
		return filepath.Join("data", "gomoku_players.json")
	}
	return filepath.Join(filepath.Dir(execPath), "data", "gomoku_players.json")
}

func gomokuLoadPlayers() {
	gomokuMu.Lock()
	defer gomokuMu.Unlock()
	data, err := os.ReadFile(gomokuPlayersPath())
	if err != nil {
		return // 首次启动无文件，忽略
	}
	var obj map[string]*gomokuPlayer
	if json.Unmarshal(data, &obj) != nil {
		log.Printf("[五子棋] 玩家档案解析失败，使用空档案启动")
		return
	}
	for k, v := range obj {
		gomokuPlayers[k] = v
	}
	gomokuPlayersLoaded = true
	log.Printf("[五子棋] 已加载 %d 个玩家档案", len(gomokuPlayers))
}

func gomokuSavePlayers() {
	obj := make(map[string]*gomokuPlayer, len(gomokuPlayers))
	for k, v := range gomokuPlayers {
		obj[k] = v
	}
	data, err := json.MarshalIndent(obj, "", "  ")
	if err != nil {
		return
	}
	path := gomokuPlayersPath()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return
	}
	if err := os.WriteFile(path, data, 0644); err != nil {
		log.Printf("[五子棋] 玩家档案写盘失败: %v", err)
	}
}

// gomokuGetPlayer 校验 playerId 并返回档案；不存在则新建（初始 1000 分）并立即写盘。
func gomokuGetPlayer(playerID string) *gomokuPlayer {
	if playerID == "" || !gomokuPlayerIDRe.MatchString(strings.ToLower(playerID)) {
		return nil
	}
	p := gomokuPlayers[playerID]
	if p == nil {
		p = &gomokuPlayer{Score: 1000, Wins: 0, Losses: 0, Name: "玩家" + playerID[:4]}
		gomokuPlayers[playerID] = p
		gomokuSavePlayers()
	}
	return p
}

// ------------------------- 房间与连接 -------------------------

// gomokuBetState 一局之内的赌约状态。
// 注意是「局」级而不是「房间」级：点「再来一局」会重置，即每一局都可以各自发起一次赌约。
// 流程：发起者下注(waitPeer) → 对方接受并填自己的金额(waitConfirm) → 发起者确认 → locked（此时钱已托管扣出）。
type gomokuBetState struct {
	used        bool   // 本局是否已发起过一次（一局只能发起一次：被拒绝/放弃后不再允许）
	locked      bool   // 是否已锁定（锁定后中途退出 = 逃跑 = 判负）
	settled     bool   // 是否已结算（防重复结算）
	stage       string // "" | "waitPeer" | "waitConfirm" | "locked"
	initiator   int    // 发起者棋色（1 黑 / 2 白）
	blackAmount int64  // 黑方承诺金额（锁定后即已从余额扣出并托管）
	whiteAmount int64  // 白方承诺金额
}

type gomokuRoom struct {
	id         string
	name       string
	board      gomokuBoard
	current    int
	over       bool
	started    bool
	match      bool
	bet        gomokuBetState
	players    [2]*gomokuConn
	spectators map[*gomokuConn]bool
}

func gomokuGetList() []map[string]interface{} {
	list := make([]map[string]interface{}, 0, len(gomokuRooms))
	for _, r := range gomokuRooms {
		count := 0
		if r.players[0] != nil {
			count++
		}
		if r.players[1] != nil {
			count++
		}
		status := "waiting"
		if r.started {
			status = "playing"
		}
		list = append(list, map[string]interface{}{
			"id":           r.id,
			"name":         r.name,
			"status":       status,
			"count":        count,
			"canJoin":      !r.started && r.players[0] != nil,
			"canSpectate":  r.started && r.players[0] != nil && r.players[1] != nil,
		})
	}
	return list
}

// gomokuSend 向连接投递一条 JSON 消息（非阻塞，队列满则丢弃）。
func gomokuSend(c *gomokuConn, obj interface{}) {
	if c == nil || c.closed {
		return
	}
	data, err := json.Marshal(obj)
	if err != nil {
		return
	}
	select {
	case c.sendCh <- data:
	default:
		log.Printf("[五子棋] 发送队列满，丢弃消息: %s", string(data))
	}
}

// gomokuBroadcastRoom 给房间内双方 + 观战者都发消息。
func gomokuBroadcastRoom(room *gomokuRoom, obj interface{}) {
	gomokuSend(room.players[0], obj)
	gomokuSend(room.players[1], obj)
	for s := range room.spectators {
		gomokuSend(s, obj)
	}
}

// gomokuBroadcastRoomList 只给大厅（未在房间）的连接广播房间列表。
func gomokuBroadcastRoomList() {
	list := gomokuGetList()
	for c := range gomokuConns {
		if c.roomID == "" {
			gomokuSend(c, map[string]interface{}{"type": "roomList", "rooms": list})
		}
	}
}

// ------------------------- 消息处理 -------------------------

func gomokuHandle(conn *gomokuConn, msg map[string]interface{}) {
	switch msg["type"] {
	case "list":
		gomokuSend(conn, map[string]interface{}{"type": "roomList", "rooms": gomokuGetList()})
	case "create":
		name, _ := msg["name"].(string)
		gomokuCreateRoom(conn, name)
	case "join":
		roomID, _ := msg["roomId"].(string)
		gomokuJoinRoom(conn, roomID)
	case "spectate":
		roomID, _ := msg["roomId"].(string)
		gomokuSpectateRoom(conn, roomID)
	case "leave":
		gomokuLeaveRoom(conn)
	case "move":
		gomokuDoMove(conn, msg)
	case "devWin":
		gomokuRequireDev(conn)
		gomokuDevWin(conn)
	case "devFlip":
		gomokuRequireDev(conn)
		gomokuDevFlip(conn)
	case "devFill":
		gomokuRequireDev(conn)
		gomokuDevFill(conn)
	case "restart":
		gomokuDoRestart(conn)
	case "chat":
		gomokuDoChat(conn, msg)
	case "match":
		playerID, _ := msg["playerId"].(string)
		gomokuHandleMatch(conn, playerID)
	case "cancelMatch":
		gomokuHandleCancelMatch(conn)
	// ==================== 赌注（仅联机房间 / 匹配对局；AI 对局客户端根本不显示入口） ====================
	case "bet": // 发起者提交自己"输了愿付多少"
		amount, _ := msg["amount"].(float64)
		gomokuHandleBetOffer(conn, int64(amount))
	case "betAccept": // 对方接受并填自己"输了愿付多少"
		amount, _ := msg["amount"].(float64)
		gomokuHandleBetAccept(conn, int64(amount))
	case "betReject": // 对方拒绝
		gomokuHandleBetReject(conn)
	case "betConfirm": // 发起者同意锁定（此时才真正扣款托管）
		gomokuHandleBetConfirm(conn)
	case "betDecline": // 发起者不同意 / 放弃
		gomokuHandleBetDecline(conn)
	}
}

func gomokuCreateRoom(conn *gomokuConn, name string) {
	if conn.roomID != "" {
		gomokuLeaveRoom(conn)
	}
	id := gomokuGenID()
	roomName := strings.TrimSpace(name)
	if roomName == "" {
		roomName = "房间 " + id
	}
	room := &gomokuRoom{
		id:      id,
		name:    roomName,
		board:   gomokuEmptyBoard(),
		current: 1,
		started: false,
		players: [2]*gomokuConn{conn, nil},
		spectators: map[*gomokuConn]bool{},
	}
	gomokuRooms[id] = room
	conn.roomID = id
	conn.color = 1
	gomokuSend(conn, map[string]interface{}{"type": "joined", "roomId": id, "color": 1})
	gomokuBroadcastRoomList()
}

func gomokuJoinRoom(conn *gomokuConn, roomID string) {
	if conn.roomID != "" {
		gomokuLeaveRoom(conn)
	}
	room := gomokuRooms[roomID]
	if room == nil || room.started || room.players[0] == nil || room.players[1] != nil {
		gomokuSend(conn, map[string]interface{}{"type": "error", "msg": "房间不存在或已满"})
		return
	}
	room.players[1] = conn
	room.started = true
	conn.roomID = roomID
	conn.color = 2
	gomokuSend(conn, map[string]interface{}{"type": "joined", "roomId": roomID, "color": 2})
	// start 双方各自收不同 yourColor
	gomokuSend(room.players[0], map[string]interface{}{"type": "start", "yourColor": 1, "roomId": roomID})
	gomokuSend(room.players[1], map[string]interface{}{"type": "start", "yourColor": 2, "roomId": roomID})
	gomokuBroadcastRoomList()
}

func gomokuSpectateRoom(conn *gomokuConn, roomID string) {
	if conn.roomID != "" {
		gomokuLeaveRoom(conn)
	}
	room := gomokuRooms[roomID]
	if room == nil || !room.started || room.players[0] == nil || room.players[1] == nil {
		gomokuSend(conn, map[string]interface{}{"type": "error", "msg": "房间不存在或观战不可用"})
		return
	}
	conn.roomID = roomID
	conn.color = 0
	conn.spectator = true
	room.spectators[conn] = true
	black, white := "已连接", "已连接"
	if room.players[0] == nil {
		black = ""
	}
	if room.players[1] == nil {
		white = ""
	}
	gomokuSend(conn, map[string]interface{}{
		"type":    "spectate",
		"roomId":  roomID,
		"board":   gomokuBoardFlat(&room.board),
		"current": room.current,
		"over":    room.over,
		"black":   black,
		"white":   white,
	})
}

func gomokuDoMove(conn *gomokuConn, msg map[string]interface{}) {
	room := gomokuRooms[conn.roomID]
	if room == nil || !room.started || room.over {
		return
	}
	if conn.spectator {
		return
	}
	force, _ := msg["force"].(bool)
	if conn.color != room.current && !force {
		return // 不是你的回合（开发者可强制）
	}
	r, _ := msg["r"].(float64)
	c, _ := msg["c"].(float64)
	ri, ci := int(r), int(c)
	if !gomokuInBounds(ri, ci) || room.board[ri][ci] != 0 {
		return // 非法落子
	}
	// 开发者「无限出棋」强制落子时，落子颜色始终为操作者自身棋色，且不切换回合
	player := room.current
	if force {
		player = conn.color
	}
	room.board[ri][ci] = player
	over := false
	winner := 0
	if gomokuCheckWin(&room.board, ri, ci, player) {
		over = true
		winner = player
		// 已翻转：若落子方是对手（非翻转者本人），则判翻转者（开发者）胜
		for _, p := range room.players {
			if p != nil && p.devFlipped && p.color != player {
				winner = p.color
				break
			}
		}
	} else if gomokuIsFull(&room.board) {
		over = true
		winner = 0
	}
	room.over = over
	if !over && !force {
		if room.current == 1 {
			room.current = 2
		} else {
			room.current = 1
		}
	}
	payload := map[string]interface{}{
		"type":    "move",
		"r":       ri,
		"c":       ci,
		"player":  player,
		"current": room.current,
		"over":    over,
		"winner":  winner,
	}
	gomokuBroadcastRoom(room, payload)
	// 对局结束：匹配房照旧结算段位/战绩；赌注结算对「联机房间」和「匹配对局」都生效
	// （赢家拿走双方托管的全部金额，平局各退各的）
	if over {
		if room.match {
			gomokuSettleMatch(room, winner)
		}
		gomokuSettleBet(room, winner)
	}
}

// gomokuRequireDev dev* 消息（devWin/devFlip/devFill）统一鉴权门禁。
// 这些消息能直接判胜/结算赌注（gomokuSettleBet 会拿走双方托管 token），
// 一旦裸调即可被任何人用来"判自己胜并拿走双方赌注"。
// conn.userID 由 WS 握手时的登录 token 解析（见 handleGomokuWS），不听客户端自报。
func gomokuRequireDev(conn *gomokuConn) {
	if conn.userID > 0 && isDeveloperUser(conn.userID) {
		return
	}
	log.Printf("[五子棋] 拒绝非开发者 dev 消息: userID=%d room=%s", conn.userID, conn.roomID)
	gomokuSend(conn, map[string]interface{}{"type": "devDenied"})
}

func gomokuDevFlip(conn *gomokuConn) {
	conn.devFlipped = !conn.devFlipped
	gomokuSend(conn, map[string]interface{}{"type": "devFlip", "on": conn.devFlipped})
}

func gomokuDevFill(conn *gomokuConn) {
	room := gomokuRooms[conn.roomID]
	if room == nil || room.over {
		return
	}
	color := conn.color
	if color == 0 {
		color = 1
	}
	for r := 0; r < gomokuSize; r++ {
		for c := 0; c < gomokuSize; c++ {
			if room.board[r][c] == 0 {
				room.board[r][c] = color
			}
		}
	}
	gomokuBroadcastRoom(room, map[string]interface{}{
		"type":  "sync",
		"board": gomokuBoardFlat(&room.board),
	})
}

func gomokuDevWin(conn *gomokuConn) {
	room := gomokuRooms[conn.roomID]
	if room == nil || room.over {
		return
	}
	winner := conn.color
	room.over = true
	gomokuBroadcastRoom(room, map[string]interface{}{"type": "remoteWin", "winner": winner})
	if room.match {
		gomokuSettleMatch(room, winner)
	}
	gomokuSettleBet(room, winner)
	room.started = false
	gomokuBroadcastRoomList()
}

func gomokuDoRestart(conn *gomokuConn) {
	room := gomokuRooms[conn.roomID]
	if room == nil || !room.started {
		return
	}
	room.board = gomokuEmptyBoard()
	room.current = 1
	room.over = false
	// 新的一局：赌约状态清零（上一局已结算），本局可以重新发起一次
	room.bet = gomokuBetState{}
	gomokuBroadcastRoom(room, map[string]interface{}{"type": "restart", "current": 1})
}

func gomokuDoChat(conn *gomokuConn, msg map[string]interface{}) {
	room := gomokuRooms[conn.roomID]
	if room == nil {
		return
	}
	text, _ := msg["text"].(string)
	text = strings.TrimSpace(text)
	if len([]rune(text)) > 120 {
		text = string([]rune(text)[:120])
	}
	if text == "" {
		return
	}
	who := "旁观"
	if conn.color == 1 {
		who = "黑"
	} else if conn.color == 2 {
		who = "白"
	}
	payload := map[string]interface{}{"type": "chat", "text": text, "who": who, "mine": false}
	gomokuSend(conn, map[string]interface{}{"type": "chat", "text": text, "who": who, "mine": true})
	for _, p := range room.players {
		if p != nil && p != conn {
			gomokuSend(p, payload)
		}
	}
	for s := range room.spectators {
		if s != conn {
			gomokuSend(s, payload)
		}
	}
}

// gomokuLeaveRoom 处理连接离开房间（观战者/玩家/匹配对局），并通知相关人员。
func gomokuLeaveRoom(conn *gomokuConn) {
	room := gomokuRooms[conn.roomID]
	if room == nil {
		conn.roomID = ""
		conn.color = 0
		conn.spectator = false
		return
	}
	// 先处理观战者退出
	if conn.spectator {
		delete(room.spectators, conn)
		conn.roomID = ""
		conn.color = 0
		conn.spectator = false
		return
	}
	idx := -1
	for i, p := range room.players {
		if p == conn {
			idx = i
			break
		}
	}
	if idx == -1 {
		conn.roomID = ""
		conn.color = 0
		return
	}
	// 赌约已锁定、对局尚未结束就退出：按规则视为逃跑 = 判负，先把赌注按"退出方输"结算掉，
	// 再做下面原有的房间清理。断线/被系统杀掉进程同样走这里（连接断开也会触发本函数），
	// 否则要输的人只要拔网线就能不付钱。
	if room.bet.locked && !room.bet.settled && !room.over {
		loserColor := conn.color
		winner := 0
		if loserColor == 1 {
			winner = 2
		} else if loserColor == 2 {
			winner = 1
		}
		if winner != 0 {
			room.over = true // 标记对局已结束，防止对方随后离开时重复结算
			gomokuSettleBet(room, winner)
			log.Printf("[五子棋] 赌约逃跑判负: 房间 %s 退出方=%s", room.id, gomokuColorName(loserColor))
		}
	}
	room.players[idx] = nil
	conn.roomID = ""
	conn.color = 0
	conn.devFlipped = false
	conn.spectator = false
	if room.match {
		// 匹配对局任一方离开 -> 关闭房间并通知对方
		other := room.players[0]
		if other == nil {
			other = room.players[1]
		}
		spectators := make([]*gomokuConn, 0, len(room.spectators))
		for s := range room.spectators {
			spectators = append(spectators, s)
		}
		for _, s := range spectators {
			gomokuSend(s, map[string]interface{}{"type": "roomClosed"})
		}
		delete(gomokuRooms, room.id)
		if other != nil {
			gomokuSend(other, map[string]interface{}{"type": "roomClosed"})
		}
		gomokuBroadcastRoomList()
		return
	}
	if idx == 0 {
		// 房主离开 -> 关闭房间，通知所有观战者
		other := room.players[1]
		spectators := make([]*gomokuConn, 0, len(room.spectators))
		for s := range room.spectators {
			spectators = append(spectators, s)
		}
		for _, s := range spectators {
			gomokuSend(s, map[string]interface{}{"type": "roomClosed"})
		}
		delete(gomokuRooms, room.id)
		if other != nil {
			gomokuSend(other, map[string]interface{}{"type": "roomClosed"})
		}
	} else {
		// 访客离开 -> 房间回到等待，并通知房主 + 所有观战者
		room.started = false
		room.over = false
		room.board = gomokuEmptyBoard()
		room.current = 1
		room.bet = gomokuBetState{} // 对手已离开（赌注已在上面结算/退回）：清空赌约，下一场重新来
		creator := room.players[0]
		if creator != nil {
			gomokuSend(creator, map[string]interface{}{"type": "opponentLeft"})
		}
		spectators := make([]*gomokuConn, 0, len(room.spectators))
		for s := range room.spectators {
			spectators = append(spectators, s)
		}
		for _, s := range spectators {
			gomokuSend(s, map[string]interface{}{"type": "opponentLeft"})
			delete(room.spectators, s)
		}
	}
	gomokuBroadcastRoomList()
}

// gomokuInit 启动时加载玩家档案（main 里在 initDB 之后调用）。
func gomokuInit() {
	gomokuLoadPlayers()
	// 赌注托管表 + 启动恢复：进程重启会丢掉内存里的房间，托管中的 token 必须原路退回，否则玩家凭空损失
	gomokuEnsureBetTable()
	gomokuRecoverHeldBets()
}
