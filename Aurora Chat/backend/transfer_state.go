package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	transferStatusPending = "pending"
	transferStatusClaimed = "claimed"
	transferStatusExpired = "expired"
	transferExpireSeconds = int64(24 * 3600)
)

var transferSchemaOnce sync.Once

func ensureTransferSchema() {
	transferSchemaOnce.Do(func() {
		db.Exec(`CREATE TABLE IF NOT EXISTS transfers (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			from_user_id BIGINT, to_user_id BIGINT, conv_id BIGINT,
			amount BIGINT, created_at BIGINT,
			status TEXT NOT NULL DEFAULT 'pending',
			claimed_at BIGINT NOT NULL DEFAULT 0,
			expire_at BIGINT NOT NULL DEFAULT 0,
			message_id BIGINT NOT NULL DEFAULT 0
		)`)
		for _, ddl := range []string{
			"ALTER TABLE transfers ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'",
			"ALTER TABLE transfers ADD COLUMN claimed_at BIGINT NOT NULL DEFAULT 0",
			"ALTER TABLE transfers ADD COLUMN expire_at BIGINT NOT NULL DEFAULT 0",
			"ALTER TABLE transfers ADD COLUMN message_id BIGINT NOT NULL DEFAULT 0",
		} {
			db.Exec(ddl)
		}
		db.Exec("UPDATE transfers SET status = ?, claimed_at = created_at, expire_at = created_at + ? WHERE expire_at = 0",
			transferStatusClaimed, transferExpireSeconds)
	})
}

type transferRow struct {
	ID        int64
	FromUser  int64
	ToUser    int64
	ConvID    int64
	Amount    int64
	CreatedAt int64
	Status    string
	ClaimedAt int64
	ExpireAt  int64
	MessageID int64
}

func loadTransfer(id int64) (*transferRow, error) {
	var t transferRow
	err := db.QueryRow(`SELECT id, from_user_id, to_user_id, conv_id, amount, created_at,
		COALESCE(status, 'claimed'), COALESCE(claimed_at, 0), COALESCE(expire_at, 0), COALESCE(message_id, 0)
		FROM transfers WHERE id = ?`, id).Scan(
		&t.ID, &t.FromUser, &t.ToUser, &t.ConvID, &t.Amount, &t.CreatedAt,
		&t.Status, &t.ClaimedAt, &t.ExpireAt, &t.MessageID)
	if err != nil {
		return nil, err
	}
	return &t, nil
}

func buildTransferContent(fromID int64, fromName string, toID int64, toName string,
	amount, transferID, createdAt int64, status string, claimedAt int64) string {
	return fmt.Sprintf("%s\nfrom_user_id=%d\nfrom_name=%s\nto_user_id=%d\nto_name=%s\namount=%d\ntransfer_id=%d\nstatus=%s\ncreated_at=%d\nclaimed_at=%d",
		transferMsgPrefix, fromID, fromName, toID, toName, amount, transferID, status, createdAt, claimedAt)
}

func transferDetailJSON(t *transferRow) map[string]interface{} {
	return map[string]interface{}{
		"transfer_id":  t.ID,
		"amount":       t.Amount,
		"status":       t.Status,
		"created_at":   t.CreatedAt,
		"claimed_at":   t.ClaimedAt,
		"from_user_id": t.FromUser,
		"to_user_id":   t.ToUser,
	}
}

func setTransferMessageStatus(messageID int64, status string, claimedAt int64) {
	if messageID <= 0 {
		return
	}
	m, err := getMessageByID(messageID)
	if err != nil || m == nil || !strings.HasPrefix(m.Content, transferMsgPrefix) {
		return
	}
	lines := strings.Split(m.Content, "\n")
	out := make([]string, 0, len(lines)+2)
	hasStatus, hasClaimed := false, false
	for _, l := range lines {
		trimmed := strings.TrimSpace(l)
		switch {
		case strings.HasPrefix(trimmed, "status="):
			out = append(out, "status="+status)
			hasStatus = true
		case strings.HasPrefix(trimmed, "claimed_at="):
			out = append(out, fmt.Sprintf("claimed_at=%d", claimedAt))
			hasClaimed = true
		default:
			out = append(out, l)
		}
	}
	if !hasStatus {
		out = append(out, "status="+status)
	}
	if !hasClaimed {
		out = append(out, fmt.Sprintf("claimed_at=%d", claimedAt))
	}
	db.Exec("UPDATE messages SET content = ? WHERE id = ?", encryptContentStore(strings.Join(out, "\n")), messageID)
}

func pushTransferStatus(t *transferRow, status string, claimedAt int64) {
	data := map[string]interface{}{
		"transfer_id": t.ID,
		"status":      status,
		"claimed_at":  claimedAt,
		"message_id":  t.MessageID,
	}
	PushToUser(t.FromUser, "transfer_status", data)
	PushToUser(t.ToUser, "transfer_status", data)
}

func pushClaimedCardToSender(t *transferRow) {
	sender, _ := findUserByID(t.FromUser)
	receiver, _ := findUserByID(t.ToUser)
	fromName, toName := "", ""
	if sender != nil {
		fromName = sender.Username
	}
	if receiver != nil {
		toName = receiver.Username
	}
	content := buildTransferContent(t.FromUser, fromName, t.ToUser, toName, t.Amount,
		t.ID, t.CreatedAt, transferStatusClaimed, t.ClaimedAt)
	convTarget := t.ConvID
	if convTarget > 0 {
		convTarget = t.FromUser
	}
	msgID, err := saveMessage(t.ToUser, convTarget, content, 0, "transfer", "", 0, "", "", 0, 0, 0)
	if err != nil {
		log.Printf("[转账] 已收款卡片保存失败: %v", err)
		return
	}
	PushToUser(t.FromUser, "new_message", map[string]interface{}{
		"message_id": msgID, "from_user_id": t.ToUser, "from_username": toName,
		"to_user_id": convTarget, "content": content, "created_at": time.Now().Unix(),
		"media_type": "transfer", "media_url": "", "flash_duration": 0,
		"reply_to_id": 0, "reply_to_text": "", "reply_to_sender": "",
	})
}

func refundExpiredTransfer(t *transferRow) bool {
	tx, err := db.Begin()
	if err != nil {
		return false
	}
	res, err := tx.Exec("UPDATE transfers SET status = ? WHERE id = ? AND status = ?",
		transferStatusExpired, t.ID, transferStatusPending)
	if err != nil {
		tx.Rollback()
		return false
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		tx.Rollback()
		return false
	}
	if _, err := tx.Exec("UPDATE users SET token_balance = token_balance + ? WHERE id = ?", t.Amount, t.FromUser); err != nil {
		tx.Rollback()
		return false
	}
	if err := tx.Commit(); err != nil {
		return false
	}
	setTransferMessageStatus(t.MessageID, transferStatusExpired, 0)
	t.Status = transferStatusExpired
	pushTransferStatus(t, transferStatusExpired, 0)
	return true
}

func refundExpiredTransfers() {
	ensureTransferSchema()
	rows, err := db.Query("SELECT id FROM transfers WHERE status = ? AND expire_at > 0 AND expire_at <= ?",
		transferStatusPending, time.Now().Unix())
	if err != nil {
		return
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	rows.Close()
	for _, id := range ids {
		if t, err := loadTransfer(id); err == nil && t != nil {
			refundExpiredTransfer(t)
		}
	}
}

func startTransferRefundSweeper() {
	go func() {
		ensureTransferSchema()
		refundExpiredTransfers()
		ticker := time.NewTicker(60 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			refundExpiredTransfers()
		}
	}()
}

func handleClaimTransfer(w http.ResponseWriter, r *http.Request) {
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
		TransferID int64 `json:"transfer_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.TransferID <= 0 {
		writeJSON(w, 400, "转账记录无效", nil)
		return
	}
	ensureTransferSchema()

	t, err := loadTransfer(req.TransferID)
	if err != nil {
		writeJSON(w, 404, "转账记录不存在", nil)
		return
	}
	if t.ToUser != uid {
		writeJSON(w, 403, "无权领取该转账", nil)
		return
	}
	if t.Status == transferStatusClaimed {
		writeJSON(w, 200, "已收款", transferDetailJSON(t))
		return
	}
	if t.Status != transferStatusPending {
		writeJSON(w, 400, "转账已过期", nil)
		return
	}
	now := time.Now().Unix()
	if t.ExpireAt <= now {
		refundExpiredTransfer(t)
		writeJSON(w, 400, "转账已过期", nil)
		return
	}

	tx, err := db.Begin()
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	res, err := tx.Exec("UPDATE transfers SET status = ?, claimed_at = ? WHERE id = ? AND status = ? AND expire_at > ?",
		transferStatusClaimed, now, t.ID, transferStatusPending, now)
	if err != nil {
		tx.Rollback()
		writeJSON(w, 500, "领取失败", nil)
		return
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		tx.Rollback()
		writeJSON(w, 409, "转账状态已变更", nil)
		return
	}
	if _, err := tx.Exec("UPDATE users SET token_balance = token_balance + ? WHERE id = ?", t.Amount, t.ToUser); err != nil {
		tx.Rollback()
		writeJSON(w, 500, "入账失败", nil)
		return
	}
	if err := tx.Commit(); err != nil {
		writeJSON(w, 500, "领取失败", nil)
		return
	}

	t.Status = transferStatusClaimed
	t.ClaimedAt = now
	setTransferMessageStatus(t.MessageID, transferStatusClaimed, now)
	pushClaimedCardToSender(t)
	pushTransferStatus(t, transferStatusClaimed, now)
	writeJSON(w, 200, "收款成功", transferDetailJSON(t))
}

func handleTransferDetail(w http.ResponseWriter, r *http.Request) {
	uid := getCurrentUserID(r)
	if uid == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	ensureTransferSchema()
	id, err := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
	if err != nil || id <= 0 {
		writeJSON(w, 400, "转账记录无效", nil)
		return
	}
	t, err := loadTransfer(id)
	if err != nil {
		writeJSON(w, 404, "转账记录不存在", nil)
		return
	}
	if uid != t.FromUser && uid != t.ToUser {
		writeJSON(w, 403, "无权查看该转账", nil)
		return
	}
	writeJSON(w, 200, "ok", transferDetailJSON(t))
}
