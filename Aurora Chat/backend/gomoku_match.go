package main

import (
	"fmt"
	"log"
	"strings"
	"time"
)

// ============================================================================
// 五子棋匹配模式 + 段位机制（协议与旧 Node 服务一致）
// 匹配队列内存维护；玩家档案持久化 backend/data/gomoku_players.json。
// 约定：所有函数在 gomokuMu 已持锁时调用。
// ============================================================================

// gomokuRemoveFromQueue 将连接从匹配队列移除（重复调用安全）。
func gomokuRemoveFromQueue(conn *gomokuConn) {
	for i, c := range gomokuMatchQueue {
		if c == conn {
			gomokuMatchQueue = append(gomokuMatchQueue[:i], gomokuMatchQueue[i+1:]...)
			conn.matching = false
			return
		}
	}
}

// gomokuHandleMatch 收到 match（带 playerId）：校验档案 -> 队列匹配或等待。
func gomokuHandleMatch(conn *gomokuConn, playerID string) {
	if conn.roomID != "" {
		gomokuLeaveRoom(conn)
	}
	if !gomokuPlayerIDRe.MatchString(strings.ToLower(playerID)) {
		gomokuSend(conn, map[string]interface{}{"type": "error", "msg": "playerId 格式错误，应为 32 位十六进制"})
		return
	}
	if conn.matching {
		// 已在队列，重新回等待提示（幂等）
		p := gomokuPlayers[playerID]
		if p == nil {
			p = gomokuGetPlayer(playerID)
		}
		gomokuSend(conn, map[string]interface{}{
			"type": "matchWaiting",
			"myScore": p.Score,
			"myRank":  gomokuRankOf(p.Score),
			"wins":    p.Wins,
			"losses":  p.Losses,
		})
		return
	}
	// 找队列中另一个有效等待者
	var peer *gomokuConn
	for i, c := range gomokuMatchQueue {
		if c != conn && !c.closed && c.ws != nil {
			peer = c
			gomokuMatchQueue = append(gomokuMatchQueue[:i], gomokuMatchQueue[i+1:]...)
			break
		}
	}
	if peer == nil {
		// 无人匹配，入队等待
		p := gomokuGetPlayer(playerID)
		if p == nil {
			gomokuSend(conn, map[string]interface{}{"type": "error", "msg": "playerId 无效"})
			return
		}
		conn.matching = true
		conn.playerID = playerID
		conn.playerData = p
		gomokuMatchQueue = append(gomokuMatchQueue, conn)
		gomokuSend(conn, map[string]interface{}{
			"type": "matchWaiting",
			"myScore": p.Score,
			"myRank":  gomokuRankOf(p.Score),
			"wins":    p.Wins,
			"losses":  p.Losses,
		})
		return
	}
	// 匹配成功：取两人档案并创建自动房间
	p1 := gomokuGetPlayer(playerID)
	if p1 == nil {
		gomokuSend(conn, map[string]interface{}{"type": "error", "msg": "playerId 无效"})
		return
	}
	p2 := peer.playerData
	if p2 == nil {
		p2 = gomokuGetPlayer(peer.playerID)
	}
	conn.matching = false
	peer.matching = false
	conn.playerID = playerID
	conn.playerData = p1
	// 先匹配者（peer）为黑方先手，后匹配者（conn）为白方
	gomokuStartMatchRoom(peer, conn, p2, p1)
}

// gomokuHandleCancelMatch 取消匹配：出队并回 cancelMatchOk。
func gomokuHandleCancelMatch(conn *gomokuConn) {
	if conn.matching {
		gomokuRemoveFromQueue(conn)
	}
	gomokuSend(conn, map[string]interface{}{"type": "cancelMatchOk"})
}

// gomokuStartMatchRoom 创建自动匹配房间（started=true，双方各自收 start，含段位信息）。
func gomokuStartMatchRoom(a, b *gomokuConn, pa, pb *gomokuPlayer) {
	id := gomokuGenID()
	room := &gomokuRoom{
		id:      id,
		name:    "匹配房间 " + id,
		board:   gomokuEmptyBoard(),
		current: 1,
		started: true,
		match:   true,
		players: [2]*gomokuConn{a, b},
		spectators: map[*gomokuConn]bool{},
	}
	gomokuRooms[id] = room
	a.roomID = id
	a.color = 1
	b.roomID = id
	b.color = 2
	// 各自收 start（含 match=true 与段位信息）
	gomokuSend(a, map[string]interface{}{
		"type":           "start",
		"yourColor":      1,
		"roomId":         id,
		"match":          true,
		"myScore":        pa.Score,
		"myRank":         gomokuRankOf(pa.Score),
		"wins":           pa.Wins,
		"losses":         pa.Losses,
		"opponentScore":  pb.Score,
		"opponentRank":   gomokuRankOf(pb.Score),
	})
	gomokuSend(b, map[string]interface{}{
		"type":           "start",
		"yourColor":      2,
		"roomId":         id,
		"match":          true,
		"myScore":        pb.Score,
		"myRank":         gomokuRankOf(pb.Score),
		"wins":           pb.Wins,
		"losses":         pb.Losses,
		"opponentScore":  pa.Score,
		"opponentRank":   gomokuRankOf(pa.Score),
	})
	log.Printf("[五子棋] 匹配成功: 房间 %s (%s vs %s)", id, gomokuShortID(a.playerID), gomokuShortID(b.playerID))
}

// gomokuSettleMatch 匹配对局结算：胜 +25 / 负 -25 / 平 +5，更新档案并写盘，双向发 matchResult。
func gomokuSettleMatch(room *gomokuRoom, winner int) {
	a, b := room.players[0], room.players[1]
	if a == nil || b == nil {
		return
	}
	pa, pb := a.playerData, b.playerData
	if pa == nil || pb == nil {
		return
	}
	// 记录结算前段位（客户端展示 rankBefore）
	aRankBefore := gomokuRankOf(pa.Score)
	bRankBefore := gomokuRankOf(pb.Score)

	var deltaA, deltaB int
	var winA, winB bool
	var draw bool
	switch {
	case winner == 0:
		deltaA, deltaB = 5, 5
		draw = true
	case winner == 1:
		deltaA, deltaB = 25, -25
		winA = true
	case winner == 2:
		deltaA, deltaB = -25, 25
		winB = true
	}

	pa.Score += deltaA
	if pa.Score < 0 {
		pa.Score = 0
	}
	pb.Score += deltaB
	if pb.Score < 0 {
		pb.Score = 0
	}
	if winA {
		pa.Wins++
		pb.Losses++
	} else if winB {
		pb.Wins++
		pa.Losses++
	} // 平局（winner==0）只加分，不增减胜负场（与旧 server.js 一致）
	gomokuSavePlayers()

	gomokuSend(a, map[string]interface{}{
		"type":           "matchResult",
		"win":            winA,
		"draw":           draw,
		"score":          pa.Score,
		"delta":          deltaA,
		"rank":           gomokuRankOf(pa.Score),
		"rankBefore":     aRankBefore,
		"wins":           pa.Wins,
		"losses":         pa.Losses,
		"opponentScore":  pb.Score,
		"opponentRank":   gomokuRankOf(pb.Score),
	})
	gomokuSend(b, map[string]interface{}{
		"type":           "matchResult",
		"win":            winB,
		"draw":           draw,
		"score":          pb.Score,
		"delta":          deltaB,
		"rank":           gomokuRankOf(pb.Score),
		"rankBefore":     bRankBefore,
		"wins":           pb.Wins,
		"losses":         pb.Losses,
		"opponentScore":  pa.Score,
		"opponentRank":   gomokuRankOf(pa.Score),
	})
	log.Printf("[五子棋] 匹配结算: 房间 %s winner=%d a=%+v b=%+v", room.id, winner, pa, pb)
}

// ============================================================================
// 赌注（仅「联机房间」与「匹配对局」；AI 对局客户端不提供入口）
//
// 流程：发起者先报自己"输了愿付多少"(bet) → 对方可填自己的金额(betAccept)或拒绝(betReject)
//       → 发起者同意(betConfirm)才锁定；一局只能发起一次，被拒绝/放弃后本局不再允许。
// 锁定即托管：双方承诺金额立刻从 users.token_balance 扣出并落库(gomoku_bets status=held)，
//       结算时赢家拿走两份、平局各退各的；锁定后中途退出(含断线/被杀进程) = 逃跑 = 判负。
// 约定：与 gomoku_match.go 其余函数一致，所有函数由调用方持 gomokuMu 后调用。
// ============================================================================

const (
	gomokuBetStageNone        = ""
	gomokuBetStageWaitPeer    = "waitPeer"    // 发起者已下注，等对方接受/拒绝
	gomokuBetStageWaitConfirm = "waitConfirm" // 对方已填金额，等发起者确认锁定
	gomokuBetStageLocked      = "locked"
)

func gomokuColorName(color int) string {
	if color == 1 {
		return "黑"
	}
	if color == 2 {
		return "白"
	}
	return "旁观"
}

func gomokuOtherColor(color int) int {
	if color == 1 {
		return 2
	}
	if color == 2 {
		return 1
	}
	return 0
}

// gomokuBetColorOf 返回该连接在房间内的棋色（不在房间/观战返回 0）。
func gomokuBetColorOf(conn *gomokuConn, room *gomokuRoom) int {
	if conn == nil || room == nil || conn.spectator {
		return 0
	}
	for i, p := range room.players {
		if p == conn {
			return i + 1
		}
	}
	return 0
}

func gomokuBetAmountOf(b *gomokuBetState, color int) int64 {
	if color == 1 {
		return b.blackAmount
	}
	if color == 2 {
		return b.whiteAmount
	}
	return 0
}

func gomokuBetSetAmount(b *gomokuBetState, color int, amount int64) {
	if color == 1 {
		b.blackAmount = amount
	} else if color == 2 {
		b.whiteAmount = amount
	}
}

// gomokuBetPlayer 取棋色对应的连接（players[color-1]）。
func gomokuBetPlayer(room *gomokuRoom, color int) *gomokuConn {
	if room == nil || color < 1 || color > 2 {
		return nil
	}
	return room.players[color-1]
}

func gomokuBetSendErr(conn *gomokuConn, msg string) {
	gomokuSend(conn, map[string]interface{}{"type": "betError", "msg": msg})
}

// gomokuBetCheckAmount 校验下注金额：必须已登录、>0、余额充足。返回 (当前余额, 错误文案)。
func gomokuBetCheckAmount(conn *gomokuConn, amount int64) (int64, string) {
	if conn == nil || conn.userID <= 0 {
		return 0, "未检测到登录态，请重新登录后再下注"
	}
	if amount <= 0 {
		return getEffectiveTokenBalance(conn.userID), "下注金额必须大于 0"
	}
	bal := getEffectiveTokenBalance(conn.userID)
	if bal < amount {
		return bal, fmt.Sprintf("余额不足：当前 %d，最多只能下注 %d", bal, bal)
	}
	return bal, ""
}

// gomokuHandleBetOffer 发起者提交自己的下注金额。
func gomokuHandleBetOffer(conn *gomokuConn, amount int64) {
	room := gomokuRooms[conn.roomID]
	color := gomokuBetColorOf(conn, room)
	if room == nil || color == 0 {
		gomokuBetSendErr(conn, "不在对局中，无法下注")
		return
	}
	if !room.started || room.over {
		gomokuBetSendErr(conn, "对局未开始或已结束，无法下注")
		return
	}
	if room.bet.used || room.bet.locked {
		gomokuBetSendErr(conn, "本局赌约已发起过，不能再发起")
		return
	}
	if gomokuBetPlayer(room, gomokuOtherColor(color)) == nil {
		gomokuBetSendErr(conn, "对手不在房间，无法下注")
		return
	}
	bal, errMsg := gomokuBetCheckAmount(conn, amount)
	if errMsg != "" {
		gomokuBetSendErr(conn, errMsg)
		return
	}
	room.bet.used = true
	room.bet.stage = gomokuBetStageWaitPeer
	room.bet.initiator = color
	gomokuBetSetAmount(&room.bet, color, amount)

	gomokuSend(conn, map[string]interface{}{"type": "betWaiting", "myAmount": amount, "balance": bal})
	gomokuSend(gomokuBetPlayer(room, gomokuOtherColor(color)), map[string]interface{}{
		"type": "betOffer", "amount": amount, "from": gomokuColorName(color),
	})
}

// gomokuHandleBetAccept 对方接受并提交自己的下注金额。
func gomokuHandleBetAccept(conn *gomokuConn, amount int64) {
	room := gomokuRooms[conn.roomID]
	color := gomokuBetColorOf(conn, room)
	if room == nil || color == 0 || room.bet.locked ||
		room.bet.stage != gomokuBetStageWaitPeer || room.bet.initiator == color {
		gomokuBetSendErr(conn, "当前没有待你确认的赌约")
		return
	}
	if _, errMsg := gomokuBetCheckAmount(conn, amount); errMsg != "" {
		gomokuBetSendErr(conn, errMsg)
		return
	}
	gomokuBetSetAmount(&room.bet, color, amount)
	room.bet.stage = gomokuBetStageWaitConfirm

	gomokuSend(conn, map[string]interface{}{"type": "betSent", "myAmount": amount})
	gomokuSend(gomokuBetPlayer(room, room.bet.initiator), map[string]interface{}{
		"type":           "betConfirm",
		"myAmount":       gomokuBetAmountOf(&room.bet, room.bet.initiator),
		"opponentAmount": amount,
		"from":           gomokuColorName(color),
	})
}

// gomokuHandleBetReject 对方拒绝赌约（一局只能发起一次，此后本局不再有赌约）。
func gomokuHandleBetReject(conn *gomokuConn) {
	room := gomokuRooms[conn.roomID]
	color := gomokuBetColorOf(conn, room)
	if room == nil || color == 0 || room.bet.locked ||
		room.bet.stage != gomokuBetStageWaitPeer || room.bet.initiator == color {
		gomokuBetSendErr(conn, "当前没有待你确认的赌约")
		return
	}
	room.bet.stage = gomokuBetStageNone
	gomokuSend(gomokuBetPlayer(room, room.bet.initiator), map[string]interface{}{"type": "betRejected", "byMe": false})
	gomokuSend(conn, map[string]interface{}{"type": "betRejected", "byMe": true})
}

// gomokuHandleBetConfirm 发起者同意锁定：此时才真正扣款托管。
func gomokuHandleBetConfirm(conn *gomokuConn) {
	room := gomokuRooms[conn.roomID]
	color := gomokuBetColorOf(conn, room)
	if room == nil || color == 0 || room.bet.locked ||
		room.bet.stage != gomokuBetStageWaitConfirm || room.bet.initiator != color {
		gomokuBetSendErr(conn, "当前没有待你确认锁定的赌约")
		return
	}
	if !gomokuHoldBet(room) {
		room.bet.stage = gomokuBetStageNone
		gomokuBroadcastRoom(room, map[string]interface{}{"type": "betRejected", "byMe": false, "msg": "余额不足，赌约未成立"})
		return
	}
	room.bet.locked = true
	room.bet.stage = gomokuBetStageLocked
	for i, p := range room.players {
		if p == nil {
			continue
		}
		gomokuSend(p, map[string]interface{}{
			"type":           "betLocked",
			"myAmount":       gomokuBetAmountOf(&room.bet, i+1),
			"opponentAmount": gomokuBetAmountOf(&room.bet, gomokuOtherColor(i+1)),
		})
	}
	log.Printf("[五子棋] 赌约锁定: 房间 %s 黑=%d 白=%d", room.id, room.bet.blackAmount, room.bet.whiteAmount)
}

// gomokuHandleBetDecline 发起者不同意 / 放弃 → 赌约作废（本局不再允许）。
func gomokuHandleBetDecline(conn *gomokuConn) {
	room := gomokuRooms[conn.roomID]
	color := gomokuBetColorOf(conn, room)
	if room == nil || color == 0 || room.bet.locked ||
		room.bet.stage != gomokuBetStageWaitConfirm || room.bet.initiator != color {
		gomokuBetSendErr(conn, "当前没有待你确认锁定的赌约")
		return
	}
	room.bet.stage = gomokuBetStageNone
	gomokuSend(gomokuBetPlayer(room, gomokuOtherColor(color)), map[string]interface{}{"type": "betDeclined", "byMe": false})
	gomokuSend(conn, map[string]interface{}{"type": "betDeclined", "byMe": true})
}

// gomokuHoldBet 锁定瞬间把双方承诺金额从余额扣出并记为托管。
// 用带条件的原子 UPDATE 保证不会扣成负数；任何一步失败整体回滚（赌约不成立），
// 绝不允许出现"只托管了一边"的中间态。
func gomokuHoldBet(room *gomokuRoom) bool {
	black := gomokuBetPlayer(room, 1)
	white := gomokuBetPlayer(room, 2)
	if black == nil || white == nil || black.userID <= 0 || white.userID <= 0 {
		return false
	}
	if room.bet.blackAmount <= 0 || room.bet.whiteAmount <= 0 {
		return false
	}
	tx, err := db.Begin()
	if err != nil {
		log.Printf("[五子棋] 赌注托管开启事务失败: %v", err)
		return false
	}
	defer tx.Rollback()
	now := time.Now().Unix()
	stakes := []struct {
		conn   *gomokuConn
		color  int
		amount int64
	}{{black, 1, room.bet.blackAmount}, {white, 2, room.bet.whiteAmount}}
	for _, s := range stakes {
		res, err := tx.Exec(`UPDATE users SET token_balance = token_balance - ? WHERE id = ? AND token_balance >= ?`,
			s.amount, s.conn.userID, s.amount)
		if err != nil {
			log.Printf("[五子棋] 赌注托管扣款失败 user=%d: %v", s.conn.userID, err)
			return false
		}
		if n, _ := res.RowsAffected(); n == 0 {
			log.Printf("[五子棋] 赌注托管余额不足 user=%d amount=%d", s.conn.userID, s.amount)
			return false
		}
		if _, err := tx.Exec(`INSERT INTO gomoku_bets (room_id, user_id, color, amount, status, created_at)
			VALUES (?, ?, ?, ?, 'held', ?)`, room.id, s.conn.userID, s.color, s.amount, now); err != nil {
			log.Printf("[五子棋] 赌注托管记账失败 user=%d: %v", s.conn.userID, err)
			return false
		}
	}
	if err := tx.Commit(); err != nil {
		log.Printf("[五子棋] 赌注托管提交失败: %v", err)
		return false
	}
	return true
}

// gomokuPayBet 把托管金额结给赢家（平局各退各的），并把托管记录标记为已结算。
func gomokuPayBet(room *gomokuRoom, winner int, black, white *gomokuConn) bool {
	if black == nil || white == nil {
		return false
	}
	tx, err := db.Begin()
	if err != nil {
		return false
	}
	defer tx.Rollback()
	total := room.bet.blackAmount + room.bet.whiteAmount
	switch winner {
	case 1:
		if _, err := tx.Exec(`UPDATE users SET token_balance = token_balance + ? WHERE id = ?`, total, black.userID); err != nil {
			return false
		}
	case 2:
		if _, err := tx.Exec(`UPDATE users SET token_balance = token_balance + ? WHERE id = ?`, total, white.userID); err != nil {
			return false
		}
	default:
		if _, err := tx.Exec(`UPDATE users SET token_balance = token_balance + ? WHERE id = ?`, room.bet.blackAmount, black.userID); err != nil {
			return false
		}
		if _, err := tx.Exec(`UPDATE users SET token_balance = token_balance + ? WHERE id = ?`, room.bet.whiteAmount, white.userID); err != nil {
			return false
		}
	}
	if _, err := tx.Exec(`UPDATE gomoku_bets SET status = 'settled', settled_at = ? WHERE room_id = ? AND status = 'held'`,
		time.Now().Unix(), room.id); err != nil {
		return false
	}
	return tx.Commit() == nil
}

// gomokuSettleBet 结算赌注。winner：1 黑胜 / 2 白胜 / 0 平局。
// 赢家拿走双方托管的全部金额（自己本金 + 对手赌注）；平局各退各的。未锁定/已结算直接返回（幂等）。
func gomokuSettleBet(room *gomokuRoom, winner int) {
	if room == nil || !room.bet.locked || room.bet.settled {
		return
	}
	room.bet.settled = true
	blackAmount := room.bet.blackAmount
	whiteAmount := room.bet.whiteAmount
	black := gomokuBetPlayer(room, 1)
	white := gomokuBetPlayer(room, 2)
	paid := gomokuPayBet(room, winner, black, white)
	if !paid {
		// 落库失败时托管记录仍为 held，重启时会自动退回，不会凭空消失；这里必须留痕
		log.Printf("[五子棋] 赌注结算落库失败(托管保留待重启退回): 房间 %s winner=%d", room.id, winner)
	}
	var blackDelta, whiteDelta int64
	switch winner {
	case 1:
		blackDelta, whiteDelta = whiteAmount, -whiteAmount
	case 2:
		blackDelta, whiteDelta = -blackAmount, blackAmount
	default:
		blackDelta, whiteDelta = 0, 0
	}
	notify := func(p *gomokuConn, my, opp, delta int64) {
		if p == nil {
			return
		}
		gomokuSend(p, map[string]interface{}{
			"type":           "betResult",
			"win":            delta > 0,
			"draw":           winner == 0,
			"myAmount":       my,
			"opponentAmount": opp,
			"delta":          delta,
			"settled":        paid,
		})
	}
	notify(black, blackAmount, whiteAmount, blackDelta)
	notify(white, whiteAmount, blackAmount, whiteDelta)
	log.Printf("[五子棋] 赌注结算: 房间 %s winner=%d 黑净变=%d 白净变=%d", room.id, winner, blackDelta, whiteDelta)
}

// gomokuEnsureBetTable 建赌注托管表（幂等）。
func gomokuEnsureBetTable() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS gomoku_bets (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		room_id TEXT NOT NULL,
		user_id INTEGER NOT NULL,
		color INTEGER NOT NULL DEFAULT 0,
		amount INTEGER NOT NULL,
		status TEXT NOT NULL DEFAULT 'held',
		created_at INTEGER NOT NULL,
		settled_at INTEGER DEFAULT 0
	)`); err != nil {
		log.Printf("  [五子棋] 创建 gomoku_bets 表失败(可忽略): %v", err)
	}
}

// gomokuRecoverHeldBets 启动恢复：进程重启会丢掉内存里的房间与赌约，
// 托管中的 token 必须原路退回，否则玩家会凭空损失。
func gomokuRecoverHeldBets() {
	rows, err := db.Query(`SELECT id, user_id, amount FROM gomoku_bets WHERE status = 'held'`)
	if err != nil {
		return
	}
	type heldBet struct {
		id     int64
		userID int64
		amount int64
	}
	var held []heldBet
	for rows.Next() {
		var h heldBet
		if rows.Scan(&h.id, &h.userID, &h.amount) == nil {
			held = append(held, h)
		}
	}
	rows.Close()
	if len(held) == 0 {
		return
	}
	for _, h := range held {
		if _, err := db.Exec(`UPDATE users SET token_balance = token_balance + ? WHERE id = ?`, h.amount, h.userID); err != nil {
			log.Printf("[五子棋] 托管赌注退回失败 bet=%d user=%d: %v", h.id, h.userID, err)
			continue
		}
		if _, err := db.Exec(`UPDATE gomoku_bets SET status = 'refunded', settled_at = ? WHERE id = ?`, time.Now().Unix(), h.id); err != nil {
			log.Printf("[五子棋] 托管赌注标记失败 bet=%d: %v", h.id, err)
		}
		log.Printf("[五子棋] 重启恢复：托管赌注已退回 user=%d amount=%d", h.userID, h.amount)
	}
}
