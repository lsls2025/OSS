package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// ==================== 红包 ====================

const redPacketMsgPrefix = "系统红包数据"

// 抢红包并发锁（SQLite 写串行化兜底）
var redpacketMu sync.Mutex

// 判断用户是否为群成员
func isGroupMember(groupID, userID int64) bool {
	var n int
	db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ? AND user_id = ?", groupID, userID).Scan(&n)
	return n > 0
}

func ensureRedPacketTables() {
	db.Exec(`CREATE TABLE IF NOT EXISTS redpackets (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		sender_id BIGINT, conv_id BIGINT, total BIGINT, count INT,
		remaining BIGINT, claimed INT DEFAULT 0, greeting TEXT,
		created_at BIGINT, expire_at BIGINT, refunded INT DEFAULT 0)`)
	db.Exec(`CREATE TABLE IF NOT EXISTS redpacket_claims (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		packet_id BIGINT, user_id BIGINT, amount BIGINT, claimed_at BIGINT)`)
}

// 二倍均值法随机拆分：剩余 remaining 分给 slots 个人，每人至少 1，总和 = remaining
func redPacketRandomAmount(remaining int64, slots int) int64 {
	if slots <= 1 {
		return remaining
	}
	max := remaining - int64(slots-1) // 其余人至少各 1
	if max < 1 {
		max = 1
	}
	avg := remaining / int64(slots)
	bound := avg * 2
	if bound > max {
		bound = max
	}
	if bound < 1 {
		bound = 1
	}
	return rand.Int63n(bound) + 1
}

func buildRedPacketContent(packetID, senderID int64, senderName, greeting string, total int64, count int, createdAt, expireAt int64) string {
	return fmt.Sprintf("%s\npacket_id=%d\nfrom_user_id=%d\nfrom_name=%s\ntotal=%d\ncount=%d\ngreeting=%s\ncreated_at=%d\nexpire_at=%d",
		redPacketMsgPrefix, packetID, senderID, senderName, total, count, greeting, createdAt, expireAt)
}

// POST /api/redpacket/create — 发红包（扣全额，写红包记录 + 会话卡片，推送消息）
func handleRedPacketCreate(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	uid := getCurrentUserID(r)
	if uid == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		ToUserID int64  `json:"to_user_id"`
		Count    int    `json:"count"`
		Amount   int64  `json:"amount"`
		Greeting string `json:"greeting"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.Count < 1 {
		writeJSON(w, 400, "红包个数至少 1 个", nil)
		return
	}
	if req.Amount < int64(req.Count) {
		writeJSON(w, 400, "每个红包至少 1 token，个数不能超过总额", nil)
		return
	}
	if req.ToUserID == 0 {
		writeJSON(w, 400, "缺少会话目标", nil)
		return
	}
	// 群聊需校验群存在
	if req.ToUserID < 0 {
		internalGroupID := -(req.ToUserID + 1000)
		grp, _ := getGroupByID(internalGroupID)
		if grp == nil {
			writeJSON(w, 404, "群聊不存在", nil)
			return
		}
	}
	sender, err := findUserByID(uid)
	if err != nil || sender == nil {
		writeJSON(w, 500, "发送方账号异常", nil)
		return
	}
	balance := getEffectiveTokenBalance(uid)
	if balance < req.Amount {
		writeJSON(w, 400, "token 余额不足", map[string]interface{}{"balance": balance, "need": req.Amount})
		return
	}

	ensureRedPacketTables()

	greeting := req.Greeting
	if greeting == "" {
		greeting = "恭喜发财，大吉大利"
	}
	now := time.Now().Unix()
	expireAt := now + 24*3600

	tx, err := db.Begin()
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	res, err := tx.Exec("UPDATE users SET token_balance = token_balance - ? WHERE id = ? AND token_balance >= ?", req.Amount, uid, req.Amount)
	if err != nil {
		tx.Rollback()
		writeJSON(w, 500, "扣款失败", nil)
		return
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		tx.Rollback()
		writeJSON(w, 400, "token 余额不足", map[string]interface{}{"balance": getEffectiveTokenBalance(uid), "need": req.Amount})
		return
	}
	r2, err := tx.Exec("INSERT INTO redpackets (sender_id, conv_id, total, count, remaining, claimed, greeting, created_at, expire_at, refunded) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, 0)",
		uid, req.ToUserID, req.Amount, req.Count, req.Amount, greeting, now, expireAt)
	if err != nil {
		tx.Rollback()
		writeJSON(w, 500, "红包创建失败", nil)
		return
	}
	packetID, _ := r2.LastInsertId()
	if err := tx.Commit(); err != nil {
		writeJSON(w, 500, "红包创建失败", nil)
		return
	}

	fromName := sender.Username
	content := buildRedPacketContent(packetID, uid, fromName, greeting, req.Amount, req.Count, now, expireAt)
	msgID, err := saveMessage(uid, req.ToUserID, content, 0, "redpacket", "", 0, "", "")
	if err != nil {
		log.Printf("[红包] 卡片消息保存失败（钱款已扣除）: %v", err)
	}

	// 推送聊天消息（1:1 推送给对端；群聊推送给群内成员）
	if req.ToUserID > 0 {
		PushToUser(req.ToUserID, "new_message", map[string]interface{}{
			"message_id":  msgID,
			"from_user_id": uid,
			"from_username": fromName,
			"to_user_id":   req.ToUserID,
			"content":      content,
			"created_at":   now,
			"media_type":   "redpacket",
			"media_url":    "",
			"flash_duration": 0,
			"reply_to_id":  0,
			"reply_to_text": "",
			"reply_to_sender": "",
		})
	} else if req.ToUserID < 0 {
		PushToGroup(req.ToUserID, msgID, uid, fromName, req.ToUserID, content, now, "redpacket", "", 0, 0, "", "")
	}

	writeJSON(w, 200, "红包发送成功", map[string]interface{}{
		"balance":    getEffectiveTokenBalance(uid),
		"message_id": msgID,
		"packet_id":  packetID,
	})
}

// POST /api/redpacket/grab — 抢红包（随机拆分，记录领取，实时入账）
func handleRedPacketGrab(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	uid := getCurrentUserID(r)
	if uid == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		PacketID int64 `json:"packet_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.PacketID == 0 {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	ensureRedPacketTables()

	redpacketMu.Lock()
	defer redpacketMu.Unlock()

	tx, err := db.Begin()
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	var total, remaining, expireAt int64
	var count, claimed, refunded int
	err = tx.QueryRow("SELECT total, remaining, count, claimed, expire_at, refunded FROM redpackets WHERE id = ?", req.PacketID).
		Scan(&total, &remaining, &count, &claimed, &expireAt, &refunded)
	if err != nil {
		tx.Rollback()
		writeJSON(w, 404, "红包不存在", nil)
		return
	}
	now := time.Now().Unix()
	if refunded == 1 || now >= expireAt {
		tx.Rollback()
		writeJSON(w, 400, "红包已过期", nil)
		return
	}
	if claimed >= count || remaining <= 0 {
		tx.Rollback()
		writeJSON(w, 400, "红包已被抢光", nil)
		return
	}

	// 已领取过则幂等返回
	var already int
	tx.QueryRow("SELECT COUNT(*) FROM redpacket_claims WHERE packet_id = ? AND user_id = ?", req.PacketID, uid).Scan(&already)
	if already > 0 {
		var amt int64
		tx.QueryRow("SELECT amount FROM redpacket_claims WHERE packet_id = ? AND user_id = ?", req.PacketID, uid).Scan(&amt)
		tx.Commit()
		writeJSON(w, 200, "你已领取过该红包", map[string]interface{}{
			"amount": amt, "already": true, "claimed_count": claimed, "remaining": remaining,
		})
		return
	}

	slots := count - claimed
	amt := redPacketRandomAmount(remaining, slots)
	if _, err := tx.Exec("UPDATE users SET token_balance = token_balance + ? WHERE id = ?", amt, uid); err != nil {
		tx.Rollback()
		writeJSON(w, 500, "入账失败", nil)
		return
	}
	if _, err := tx.Exec("INSERT INTO redpacket_claims (packet_id, user_id, amount, claimed_at) VALUES (?, ?, ?, ?)", req.PacketID, uid, amt, now); err != nil {
		tx.Rollback()
		writeJSON(w, 500, "领取记录失败", nil)
		return
	}
	if _, err := tx.Exec("UPDATE redpackets SET remaining = remaining - ?, claimed = claimed + 1 WHERE id = ?", amt, req.PacketID); err != nil {
		tx.Rollback()
		writeJSON(w, 500, "红包更新失败", nil)
		return
	}
	if err := tx.Commit(); err != nil {
		writeJSON(w, 500, "领取失败", nil)
		return
	}

	newClaimed := claimed + 1
	writeJSON(w, 200, "领取成功", map[string]interface{}{
		"amount":        amt,
		"already":       false,
		"claimed_count": newClaimed,
		"remaining":     remaining - amt,
		"total":         total,
		"count":         count,
	})
}

// GET /api/redpacket/detail?id=packetID — 红包详情（领取名单 + 自身领取额 + 状态）
func handleRedPacketDetail(w http.ResponseWriter, r *http.Request) {
	uid := getCurrentUserID(r)
	if uid == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	idStr := r.URL.Query().Get("id")
	pid, _ := strconv.ParseInt(idStr, 10, 64)
	if pid == 0 {
		writeJSON(w, 400, "缺少红包 ID", nil)
		return
	}
	ensureRedPacketTables()

	var senderID, convID, total, remaining, createdAt, expireAt int64
	var count, claimed int
	var greeting string
	err := db.QueryRow("SELECT sender_id, conv_id, total, remaining, count, claimed, greeting, created_at, expire_at FROM redpackets WHERE id = ?", pid).
		Scan(&senderID, &convID, &total, &remaining, &count, &claimed, &greeting, &createdAt, &expireAt)
	if err != nil {
		writeJSON(w, 404, "红包不存在", nil)
		return
	}

	// 权限校验：仅会话参与者可查看
	if convID > 0 {
		if uid != senderID && uid != convID {
			writeJSON(w, 403, "无权查看该红包", nil)
			return
		}
	} else {
		if !isGroupMember(-(convID + 1000), uid) {
			writeJSON(w, 403, "无权查看该红包", nil)
			return
		}
	}

	// 领取名单（按金额从大到小，其次领取时间早的在前）
	rows, err := db.Query("SELECT c.user_id, COALESCE(u.username, ''), c.amount, c.claimed_at FROM redpacket_claims c LEFT JOIN users u ON c.user_id = u.id WHERE c.packet_id = ? ORDER BY c.amount DESC, c.claimed_at ASC", pid)
	claims := []map[string]interface{}{}
	if err == nil {
		for rows.Next() {
			var cid, camt, ctime int64
			var cname string
			if rows.Scan(&cid, &cname, &camt, &ctime) == nil {
				claims = append(claims, map[string]interface{}{
					"user_id":    cid,
					"username":   cname,
					"amount":     camt,
					"claimed_at": ctime,
				})
			}
		}
		rows.Close()
	}

	var myClaimed int64
	db.QueryRow("SELECT COALESCE(amount, 0) FROM redpacket_claims WHERE packet_id = ? AND user_id = ?", pid, uid).Scan(&myClaimed)

	now := time.Now().Unix()
	status := "active"
	if claimed >= count {
		status = "full"
	}
	if now >= expireAt {
		status = "expired"
	}

	senderName := ""
	if s, e := findUserByID(senderID); e == nil && s != nil {
		senderName = s.Username
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"packet": map[string]interface{}{
			"id":          pid,
			"sender_id":   senderID,
			"sender_name": senderName,
			"total":       total,
			"count":       count,
			"remaining":   remaining,
			"claimed":     claimed,
			"greeting":    greeting,
			"created_at":  createdAt,
			"expire_at":   expireAt,
		},
		"claims":     claims,
		"my_claimed": myClaimed,
		"status":     status,
	})
}

// 退款定时任务：红包过期（24h）且仍有剩余，退回发送者并通知
func startRedPacketRefundSweeper() {
	go func() {
		ticker := time.NewTicker(60 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			refundExpiredRedPackets()
		}
	}()
}

func refundExpiredRedPackets() {
	now := time.Now().Unix()
	rows, err := db.Query("SELECT id, sender_id, remaining FROM redpackets WHERE refunded = 0 AND expire_at <= ? AND remaining > 0", now)
	if err != nil {
		return
	}
	type rp struct {
		id, sender, remaining int64
	}
	var list []rp
	for rows.Next() {
		var x rp
		if rows.Scan(&x.id, &x.sender, &x.remaining) == nil {
			list = append(list, x)
		}
	}
	rows.Close()

	for _, x := range list {
		tx, err := db.Begin()
		if err != nil {
			continue
		}
		if _, err := tx.Exec("UPDATE users SET token_balance = token_balance + ? WHERE id = ?", x.remaining, x.sender); err != nil {
			tx.Rollback()
			continue
		}
		if _, err := tx.Exec("UPDATE redpackets SET refunded = 1, remaining = 0 WHERE id = ?", x.id); err != nil {
			tx.Rollback()
			continue
		}
		if err := tx.Commit(); err != nil {
			continue
		}
		desc := fmt.Sprintf("你的红包有 %d 个 token 未被领取，已退回余额", x.remaining)
		PushToUser(x.sender, "system_notice", map[string]interface{}{
			"title":   "余额已退还",
			"desc":    desc,
			"tag":     "红包",
			"kind":    "redpacket_refund",
		})
		saveSystemNotice(x.sender, "余额已退还", desc, "红包", 0)
	}
}
