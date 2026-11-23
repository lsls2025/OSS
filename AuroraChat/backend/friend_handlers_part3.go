package main

import (
	"encoding/json"
	"fmt"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// POST /api/messages/clear-chat — 对方同意清空聊天记录：双向永久删除两人会话数据，
// 并推送 conversation_deleted 让双方客户端本地也清空。
func handleClearChatConversation(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FriendID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	// 注：自聊(friend_id == userID)放行。此时下方删除 SQL 的 (from=? AND to=?) OR (from=? AND to=?)
	// 退化为删除 from==to==自己的会话消息，且 conversation_deleted 推送只发给自己，正好用于「清理自己与自己的云端记录」。
	// 是否「发清空请求卡片」仍由 handleClearChatRequest 拦截（自聊没有对方，不该发请求）。
	res, err := db.Exec("DELETE FROM messages WHERE (from_user_id=? AND to_user_id=?) OR (from_user_id=? AND to_user_id=?)", userID, req.FriendID, req.FriendID, userID)
	if err != nil {
		log.Printf("[清空聊天] 删除失败: %v", err)
		writeJSON(w, 500, "清理失败", nil)
		return
	}
	n := 0
	if c, e := res.RowsAffected(); e == nil {
		n = int(c)
	}
	// 双方客户端本地清空该会话（注意：各自 friends_id 指向对方）
	PushToUser(userID, "conversation_deleted", map[string]interface{}{"friend_id": req.FriendID})
	PushToUser(req.FriendID, "conversation_deleted", map[string]interface{}{"friend_id": userID})
	// 注意：这里刻意不再插入「聊天记录已清空」系统消息。
	// 那条消息让双方都看到一句「已清空」提示，但清空动作本身是隐式的（会话内容消失即可自明），
	// 多一句提示既冗余，又会被误读成"对方也清了"；同时它是服务端落库、客户端不认的类型，
	// 双方都靠它来确认"真的清了"，一旦有一侧没清就会像状态不一致。
	// 双方一致性由 conversation_deleted 推送保证：两端都会清掉本地记录。
	writeJSON(w, 200, "已清空会话消息", map[string]int{"count": n})
}

// POST /api/messages/clear-chat-reject — 拒绝清空聊天记录：向发起方回送一张"已拒绝"卡片。
func handleClearChatReject(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FriendID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	cardMsg := fmt.Sprintf("清空聊天记录\n━━━━━━━━━━\n%s 已拒绝清空聊天记录请求\n━━━━━━━━━━\nclear_chat_reject=%d", username, userID)
	res, err := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at, server_received_ms) VALUES (?, ?, ?, 'clear_chat_response', ?, ?)`,
		userID, req.FriendID, cardMsg, time.Now().Unix(), time.Now().UnixMilli())
	if err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	msgID, _ := res.LastInsertId()
	PushToUser(req.FriendID, "new_message", map[string]interface{}{
		"message_id":    msgID,
		"from_user_id":  userID,
		"from_username": username,
		"to_user_id":    req.FriendID,
		"content":       cardMsg,
		"created_at":    time.Now().Unix(),
	})
	// 落库回执给发起方自己（见 PushMessagePersistedReceipt 注释）
	PushMessagePersistedReceipt(userID, req.FriendID, msgID)
	writeJSON(w, 200, "已拒绝", nil)
}

// handleDevCleanConversation 清理两个用户 ID 之间的全部对话数据（含图片/卡片，均在 messages 表，永久删除）。
// 仅开发者可用；任意两个用户 ID，不限于当前登录者。
func handleDevCleanConversation(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "仅开发者可操作", nil)
		return
	}
	var req struct {
		UserA int64 `json:"user_a"`
		UserB int64 `json:"user_b"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserA <= 0 || req.UserB <= 0 {
		writeJSON(w, 400, "请求格式错误，需要 user_a 和 user_b", nil)
		return
	}
	res, err := db.Exec("DELETE FROM messages WHERE (from_user_id=? AND to_user_id=?) OR (from_user_id=? AND to_user_id=?)", req.UserA, req.UserB, req.UserB, req.UserA)
	if err != nil {
		writeJSON(w, 500, "清理失败", nil)
		return
	}
	n := 0
	if c, e := res.RowsAffected(); e == nil {
		n = int(c)
	}
	for _, uid := range []int64{req.UserA, req.UserB} {
		if uid > 0 {
			PushToUser(uid, "conversation_deleted", map[string]interface{}{"friend_id": req.UserA + req.UserB - uid, "clean": true})
		}
	}
	writeJSON(w, 200, "已清理对话数据", map[string]int{"count": n})
}

func handleMessageInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	// /api/messages/info/{messageId}
	path := strings.TrimPrefix(r.URL.Path, "/api/messages/info/")
	msgID, _ := strconv.ParseInt(strings.TrimSpace(path), 10, 64)
	if msgID == 0 {
		writeJSON(w, 400, "缺少消息ID", nil)
		return
	}
	msg, err := getMessageByID(msgID)
	if err != nil || msg == nil {
		writeJSON(w, 404, "消息不存在", nil)
		return
	}
	// 验证访问权限：必须是消息的发送方或接收方
	if msg.FromUserID != userID && msg.ToUserID != userID && msg.ToUserID >= 0 {
		writeJSON(w, 403, "无权查看此消息", nil)
		return
	}
	// 群聊消息允许群成员查看
	if msg.ToUserID < 0 {
		gid := -msg.ToUserID - 1000
		members, _ := getGroupMembers(gid)
		isMember := false
		for _, mid := range members {
			if mid == userID {
				isMember = true
				break
			}
		}
		if !isMember {
			writeJSON(w, 403, "您不是该群成员", nil)
			return
		}
	}
	// 先标记当前用户已读，确保计数包含自己。
	// 但发送者查看自己的消息不算「已读」：否则他自己那条群消息会永久 +1，
	// 气泡下面就会显示「1人已读」其实根本没人读。
	if msg.FromUserID != userID {
		markMessageRead(msgID, userID)
	}

	info := map[string]interface{}{
		"message_id":         msg.ID,
		"from_user_id":       msg.FromUserID,
		"from_user_name":     getUserName(msg.FromUserID),
		"to_user_id":         msg.ToUserID,
		"content":            msg.Content,
		"created_at":         msg.CreatedAt,
		"is_revoked":         msg.IsRevoked,
		"revoked_at":         msg.RevokedAt,
		"media_type":         msg.MediaType,
		"media_url":          msg.MediaURL,
		"flash_duration":     msg.FlashDuration,
		"client_send_at":     msg.ClientSendAt,
		"server_received_ms": msg.ServerReceivedMs,
	}
	if msg.ToUserID < 0 {
		// 群聊：已读人数（已包含当前用户）+ 已读成员明细
		gid := -msg.ToUserID - 1000
		members, _ := getGroupMembers(gid)
		readCount, _ := getMessageReadCountForSender(msgID, msg.FromUserID)
		info["read_count"] = readCount
		info["group_member_count"] = len(members)
		readRows, err := db.Query("SELECT user_id, read_at FROM message_reads WHERE message_id = ? ORDER BY read_at ASC", msgID)
		if err == nil {
			readUsers := make([]map[string]interface{}, 0)
			for readRows.Next() {
				var uid int64
				var readAt int64
				if readRows.Scan(&uid, &readAt) == nil {
					readUsers = append(readUsers, map[string]interface{}{
						"user_id":  uid,
						"nickname": getUserName(uid),
						"read_at":  readAt,
					})
				}
			}
			readRows.Close()
			info["read_users"] = readUsers
		}
		// 群公告卡片：附带「需确认收到」标记与已确认人数（前端卡片底部展示「N 人已收到」）
		if strings.HasPrefix(strings.TrimSpace(msg.Content), groupAnnouncementMsgPrefix) {
			if annText, annNeedConfirm := parseGroupAnnouncementCard(msg.Content); annText != "" {
				info["announcement_need_confirm"] = annNeedConfirm
				info["announcement_confirm_count"] = countAnnouncementConfirmed(gid, annText)
			}
		}
	} else {
		// 私聊：返回对方已读时间
		// 对端是消息的接收方（即应该已读的人）
		var otherUserID int64
		if msg.FromUserID == userID {
			otherUserID = msg.ToUserID // 发送者查 → 看接收方是否已读
		} else {
			otherUserID = userID // 接收者查 → 自己刚刚已标记已读
		}
		readAt, err := getMessageReadByUser(msgID, otherUserID)
		if err != nil || readAt == 0 {
			info["other_read_at"] = 0
			info["other_read_status"] = "unread"
		} else {
			info["other_read_at"] = readAt
			info["other_read_status"] = "read"
		}
	}
	writeJSON(w, 200, "获取成功", info)
}

func handleMarkMessageRead(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		MessageID int64 `json:"message_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	newlyRead, err := markMessageReadIfNew(req.MessageID, userID)
	if err != nil {
		log.Printf("[标记已读失败] msgID=%d userID=%d err=%v", req.MessageID, userID, err)
		writeJSON(w, 500, "标记已读失败: "+err.Error(), nil)
		return
	}
	// TCP 推送已读通知
	msg, err := getMessageByID(req.MessageID)
	if err == nil && msg != nil {
		if msg.ToUserID > 0 {
			// 私聊：推送给发送方
			senderID := msg.FromUserID
			if senderID != userID {
				PushToUser(senderID, "message_read", map[string]interface{}{
					"message_id":   req.MessageID,
					"last_read_id": req.MessageID,
					"reader_id":    userID,
					"read_at":      time.Now().Unix(),
				})
			}
		} else if newlyRead && msg.FromUserID != userID {
			// 群聊：推给发送方，让他气泡下方的「N人已读」立刻刷新。
			// 只在本次真的新增了已读记录时才推（重复打开会话不推）。
			PushToUser(msg.FromUserID, "message_read", map[string]interface{}{
				"message_id": req.MessageID,
				"reader_id":  userID,
				"group_id":   -msg.ToUserID - 1000,
			})
		}
	}
	writeJSON(w, 200, "标记成功", nil)
}

func handleMarkMessagesReadBatch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		MessageIDs []int64 `json:"message_ids"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if len(req.MessageIDs) == 0 {
		writeJSON(w, 200, "没有需要标记的消息", nil)
		return
	}

	now := time.Now().Unix()
	tx, err := db.Begin()
	if err != nil {
		log.Printf("[批量标记已读失败] userID=%d 开启事务失败: %v", userID, err)
		writeJSON(w, 500, "批量标记已读失败: "+err.Error(), nil)
		return
	}
	defer tx.Rollback()

	stmt, err := tx.Prepare("INSERT OR IGNORE INTO message_reads (message_id, user_id, read_at) VALUES (?, ?, ?)")
	if err != nil {
		log.Printf("[批量标记已读失败] userID=%d 准备语句失败: %v", userID, err)
		writeJSON(w, 500, "批量标记已读失败: "+err.Error(), nil)
		return
	}
	defer stmt.Close()

	var failed int
	// 只有真正新插入的才算「刚被读了」：重复标记会被 INSERT OR IGNORE 吃掉，
	// 不去区分的话，每次打开会话都会把同一批消息的已读事件再推一遍。
	var newlyReadIDs []int64
	for _, msgID := range req.MessageIDs {
		res, err := stmt.Exec(msgID, userID, now)
		if err != nil {
			log.Printf("[批量标记已读失败] msgID=%d userID=%d err=%v", msgID, userID, err)
			failed++
			continue
		}
		if n, e := res.RowsAffected(); e == nil && n > 0 {
			newlyReadIDs = append(newlyReadIDs, msgID)
		}
	}

	if err := tx.Commit(); err != nil {
		log.Printf("[批量标记已读失败] userID=%d 提交事务失败: %v", userID, err)
		writeJSON(w, 500, "批量标记已读失败: "+err.Error(), nil)
		return
	}

	// ── 私聊：把「读者读到哪一条」直接推给每个发送者 ──
	// 已读是单调的：一条 last_read_id 就等价于「它之前这个发送者发的消息全被读了」。
	// 客户端拿到这个 id 直接本地标记（改内存 + 写缓存），不回来查接口 ——
	// 这样推送一到界面就变「已读」，不用等任何请求往返。
	if len(newlyReadIDs) > 0 {
		nph := strings.TrimSuffix(strings.Repeat("?,", len(newlyReadIDs)), ",")
		nargs := make([]interface{}, 0, len(newlyReadIDs))
		for _, id := range newlyReadIDs {
			nargs = append(nargs, id)
		}
		maxBySender := make(map[int64]int64)
		if rr, e := db.Query(
			"SELECT id, from_user_id, to_user_id FROM messages WHERE id IN ("+nph+") AND to_user_id > 0", nargs...,
		); e == nil {
			for rr.Next() {
				var id, sender, to int64
				if rr.Scan(&id, &sender, &to) == nil && sender != userID {
					if id > maxBySender[sender] {
						maxBySender[sender] = id
					}
				}
			}
			rr.Close()
		}
		for sender, lastID := range maxBySender {
			PushToUser(sender, "message_read", map[string]interface{}{
				"message_id":   lastID,
				"last_read_id": lastID,
				"reader_id":    userID,
				"read_at":      now,
			})
		}
	}

	// 群聊：把本批**真正新增**的已读消息按发送者聚合，每个发送者只推一条，
	// 让他那边的「N人已读」立刻刷新（群消息本来就没人推，不推的话数字只能等重进会话）。
	if len(newlyReadIDs) > 0 {
		nph := strings.TrimSuffix(strings.Repeat("?,", len(newlyReadIDs)), ",")
		nargs := make([]interface{}, 0, len(newlyReadIDs))
		for _, id := range newlyReadIDs {
			nargs = append(nargs, id)
		}
		seenSender := make(map[int64]bool)
		if rr, e := db.Query(
			"SELECT DISTINCT from_user_id, to_user_id FROM messages WHERE id IN ("+nph+") AND to_user_id < 0", nargs...,
		); e == nil {
			for rr.Next() {
				var sender, to int64
				if rr.Scan(&sender, &to) == nil && sender != userID && !seenSender[sender] {
					seenSender[sender] = true
					PushToUser(sender, "message_read", map[string]interface{}{
						"group_id": -to - 1000,
					})
				}
			}
			rr.Close()
		}
	}

	writeJSON(w, 200, "批量标记成功", map[string]interface{}{
		"total":  len(req.MessageIDs),
		"failed": failed,
	})
}

// handleMessageReadStats 批量查询「我自己发的消息」的已读情况。
//
// 为什么非要有这个接口：/api/messages/info/{id} 一次只能查一条，内部还要拉群成员、
// 查 read_users 明细、再逐个取昵称。气泡下方要常驻显示「3人已读」时，一屏 10 条气泡
// 就是 10 次这种查询，滚动一下再来一遍 —— 走单条接口必炸，所以给一个批量口子。
//
// 请求：POST /api/messages/read-stats  {"message_ids": [1,2,3]}（最多 100 条）
// 响应：data.stats = [
//
//	    {"message_id":1, "read_count":3}        群聊：已读人数（不含发送者自己）
//	    {"message_id":2, "other_read_at":0}     私聊：对方已读时间，0 = 未读
//	]
//
// 只统计 from_user_id = 当前用户 的消息：别人的消息显示已读状态对自己没有信息量。
func handleMessageReadStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		MessageIDs []int64 `json:"message_ids"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	ids := req.MessageIDs
	if len(ids) > 100 {
		ids = ids[:100]
	}
	stats := make([]map[string]interface{}, 0, len(ids))
	if len(ids) == 0 {
		writeJSON(w, 200, "获取成功", map[string]interface{}{"stats": stats})
		return
	}

	ph := strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")
	args := make([]interface{}, 0, len(ids)+1)
	args = append(args, userID)
	for _, id := range ids {
		args = append(args, id)
	}
	// 一次取出归属：只认自己发的，顺带把不存在 / 别人的 id 过滤掉
	toUserByID := make(map[int64]int64, len(ids))
	rows, err := db.Query(
		"SELECT id, to_user_id FROM messages WHERE from_user_id = ? AND id IN ("+ph+")", args...,
	)
	if err != nil {
		writeJSON(w, 500, "查询失败: "+err.Error(), nil)
		return
	}
	var groupIDs, privateIDs []int64
	for rows.Next() {
		var id, to int64
		if rows.Scan(&id, &to) == nil {
			toUserByID[id] = to
			if to < 0 {
				groupIDs = append(groupIDs, id)
			} else {
				privateIDs = append(privateIDs, id)
			}
		}
	}
	rows.Close()

	// 群聊：每个消息一条 COUNT，排除发送者（= 我）自己
	readCountByID := make(map[int64]int, len(groupIDs))
	if len(groupIDs) > 0 {
		gph := strings.TrimSuffix(strings.Repeat("?,", len(groupIDs)), ",")
		gargs := make([]interface{}, 0, len(groupIDs)+1)
		for _, id := range groupIDs {
			gargs = append(gargs, id)
		}
		gargs = append(gargs, userID)
		if rr, e := db.Query(
			"SELECT message_id, COUNT(*) FROM message_reads WHERE message_id IN ("+gph+") AND user_id <> ? GROUP BY message_id",
			gargs...,
		); e == nil {
			for rr.Next() {
				var id int64
				var c int
				if rr.Scan(&id, &c) == nil {
					readCountByID[id] = c
				}
			}
			rr.Close()
		}
	}

	// 私聊：读方 = 消息的接收方，一次 JOIN 拿全
	readAtByID := make(map[int64]int64, len(privateIDs))
	if len(privateIDs) > 0 {
		pph := strings.TrimSuffix(strings.Repeat("?,", len(privateIDs)), ",")
		pargs := make([]interface{}, 0, len(privateIDs))
		for _, id := range privateIDs {
			pargs = append(pargs, id)
		}
		if rr, e := db.Query(
			"SELECT mr.message_id, mr.read_at FROM message_reads mr JOIN messages m ON m.id = mr.message_id "+
				"WHERE mr.message_id IN ("+pph+") AND mr.user_id = m.to_user_id", pargs...,
		); e == nil {
			for rr.Next() {
				var id, at int64
				if rr.Scan(&id, &at) == nil {
					readAtByID[id] = at
				}
			}
			rr.Close()
		}
	}

	for _, id := range ids {
		to, ok := toUserByID[id]
		if !ok {
			continue
		}
		item := map[string]interface{}{"message_id": id}
		if to < 0 {
			item["read_count"] = readCountByID[id]
		} else {
			item["other_read_at"] = readAtByID[id]
		}
		stats = append(stats, item)
	}
	writeJSON(w, 200, "获取成功", map[string]interface{}{"stats": stats})
}

func handleGetMessages(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	// /api/messages/{userId1}/{userId2} 格式
	path := strings.TrimPrefix(r.URL.Path, "/api/messages/")
	parts := strings.Split(path, "/")
	if len(parts) < 2 || parts[0] == "" || parts[1] == "" {
		writeJSON(w, 400, "缺少用户ID", nil)
		return
	}

	userID1, _ := strconv.ParseInt(parts[0], 10, 64)
	userID2, _ := strconv.ParseInt(parts[1], 10, 64)

	// ============ 越权防护：必须校验当前用户是会话参与者 ============
	currentID := getCurrentUserID(r)
	if currentID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	if userID2 < 0 {
		// 群聊：请求者必须是群成员（to_user_id = -(1000+groupID)）
		internalGroupID := -(userID2 + 1000)
		if !isGroupMember(internalGroupID, currentID) {
			log.Printf("[安全] 越权拦截: 非群成员尝试读取群消息 current=%d group=%d", currentID, internalGroupID)
			writeJSON(w, 403, "无权查看该群消息", nil)
			return
		}
	} else {
		// 私聊：请求者必须是会话双方之一
		if currentID != userID1 && currentID != userID2 {
			log.Printf("[安全] 越权拦截: 用户 %d 尝试读取 %d<->%d 的私聊", currentID, userID1, userID2)
			writeJSON(w, 403, "无权查看该会话", nil)
			return
		}
	}

	limit := 50
	offset := 0
	var afterID, beforeID int64
	tail := false
	if l := r.URL.Query().Get("limit"); l != "" {
		limit, _ = strconv.Atoi(l)
	}
	if o := r.URL.Query().Get("offset"); o != "" {
		offset, _ = strconv.Atoi(o)
	}
	if a := r.URL.Query().Get("after_id"); a != "" {
		afterID, _ = strconv.ParseInt(a, 10, 64)
	}
	if b := r.URL.Query().Get("before_id"); b != "" {
		beforeID, _ = strconv.ParseInt(b, 10, 64)
	}
	// tail=1：取「最近 limit 条」而不是「最早 limit 条」。
	// 默认仍是升序 + OFFSET，安卓端分页依赖这个语义，不能动；
	// 桌面端没有本地消息库，只能靠它一次拿到最新一屏。
	if t := r.URL.Query().Get("tail"); t == "1" || strings.EqualFold(t, "true") {
		tail = true
	}

	log.Printf("[消息] 获取: uid1=%d uid2=%d limit=%d offset=%d afterID=%d beforeID=%d tail=%v", userID1, userID2, limit, offset, afterID, beforeID, tail)
	msgs, err := getMessages(userID1, userID2, limit, offset, afterID, beforeID, tail)
	if err != nil {
		log.Printf("[消息] 获取失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}

	if msgs == nil {
		msgs = []Message{}
	}
	log.Printf("[消息] 获取成功: %d 条", len(msgs))
	writeJSON(w, 200, "获取成功", msgs)
}

// ==================== 会话列表（带安全的消息预览） ====================

func handleGetConversations(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	// 优先用 JWT，没有则从 URL 路径读（/api/conversations/{userId}）
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/conversations/"), "/")
		if len(parts) > 0 && parts[0] != "" {
			if id, err := strconv.ParseInt(parts[0], 10, 64); err == nil {
				currentUserID = id
			}
		}
	}
	if currentUserID == 0 {
		writeJSON(w, 400, "缺少用户ID", nil)
		return
	}

	recent, err := getRecentConversations(currentUserID)
	if err != nil {
		log.Printf("获取最近聊天失败: %v", err)
	}

	friends, err := getFriends(currentUserID)
	if err != nil {
		log.Printf("获取好友列表失败: %v", err)
	}

	seen := make(map[int64]bool)
	var result []ConversationUser

	// 1. 官方群始终在最前面（查数据库真实数据，不再自动拉回已退出用户）
	var officialGroupID int64
	var officialGroupName string
	err = db.QueryRow("SELECT id, name FROM groups WHERE display_id = 1").Scan(&officialGroupID, &officialGroupName)
	if err == nil && officialGroupID > 0 {

		// 查官方群最新消息
		convID := -(1000 + officialGroupID)
		var lastMsg string
		var lastTime int64
		var senderName string
		var flashDur int64
		var pokeTarget string
		var lastRevoked int64
		var lastMedia string
		db.QueryRow(
			"SELECT COALESCE(u.username, ''), m.content, m.created_at, COALESCE(m.flash_duration, 0), COALESCE(m.to_user_name, ''), COALESCE(m.is_revoked, 0), COALESCE(m.media_type, '') FROM messages m LEFT JOIN users u ON m.from_user_id = u.id WHERE (m.from_user_id = ? OR m.to_user_id = ?) ORDER BY m.created_at DESC LIMIT 1",
			convID, convID,
		).Scan(&senderName, &lastMsg, &lastTime, &flashDur, &pokeTarget, &lastRevoked, &lastMedia)
		if lastMsg != "" {
			// 服务端静态加密：预览读取时解密（enc:v1: 解回明文，与对话内 getMessages 一致）
			lastMsg = decryptContentLoad(lastMsg)
		}
		if lastRevoked == 1 {
			// 撤回优先：撤回只置标记、正文仍在库里，绝不能把撤回前的内容当预览
			if senderName != "" {
				lastMsg = senderName + "撤回了一条消息"
			} else {
				lastMsg = "撤回了一条消息"
			}
		} else if flashDur == -1 {
			// 拍一拍：个性化为「X拍了拍Y」
			t := pokeTarget
			if t == "" {
				t = "对方"
			}
			if senderName != "" {
				lastMsg = senderName + "拍了拍" + t
			} else {
				lastMsg = "拍了拍" + t
			}
		} else if lastMedia == "voice" {
			// 语音 content 是时长秒数，不能当正文预览（会显示成「2」）
			if senderName != "" {
				lastMsg = senderName + ": [语音]"
			} else {
				lastMsg = "[语音]"
			}
		} else if senderName != "" && lastMsg != "" {
			lastMsg = senderName + ": " + lastMsg
		}

		seen[convID] = true
		result = append(result, ConversationUser{
			User: User{
				ID:       convID,
				Username: officialGroupName,
				Email:    fmt.Sprintf("group_%d@aurora.chat", officialGroupID),
			},
			LastMessage: lastMsg,
			LastTime:    lastTime,
		})
	}

	// 1.5 用户创建的群聊（使用负 ID 与好友区分，偏移 1000 避免与官方群冲突）
	userGroups, _ := getGroupsForUser(currentUserID)
	if len(userGroups) > 0 {
		// 批量查询所有群聊的最新消息（解决 N+1 问题）
		groupIDs := make([]string, 0, len(userGroups))
		for _, g := range userGroups {
			convID := -(1000 + g.ID)
			groupIDs = append(groupIDs, fmt.Sprintf("%d", convID))
		}
		groupQuery := fmt.Sprintf(
			"SELECT m.to_user_id, COALESCE(u.username, ''), m.content, m.created_at, COALESCE(m.flash_duration, 0), COALESCE(m.to_user_name, ''), COALESCE(m.is_revoked, 0), COALESCE(m.media_type, '') FROM messages m LEFT JOIN users u ON m.from_user_id = u.id INNER JOIN (SELECT to_user_id, MAX(created_at) AS max_ct FROM messages WHERE to_user_id IN (%s) GROUP BY to_user_id) latest ON m.to_user_id = latest.to_user_id AND m.created_at = latest.max_ct",
			strings.Join(groupIDs, ","),
		)
		groupLastMsg := make(map[int64]string)
		groupLastTime := make(map[int64]int64)
		if gRows, gErr := db.Query(groupQuery); gErr == nil {
			defer gRows.Close()
			for gRows.Next() {
				var convID int64
				var senderName string
				var msg string
				var tm int64
				var flashDur int64
				var pokeTarget string
				var lastRevoked int64
				var lastMedia string
				gRows.Scan(&convID, &senderName, &msg, &tm, &flashDur, &pokeTarget, &lastRevoked, &lastMedia)
				if msg != "" {
					// 服务端静态加密：预览读取时解密（enc:v1: 解回明文，与对话内 getMessages 一致）
					msg = decryptContentLoad(msg)
				}
				if lastRevoked == 1 {
					// 撤回优先：撤回只置标记、正文仍在库里，绝不把撤回前的内容当预览
					if senderName != "" {
						msg = senderName + "撤回了一条消息"
					} else {
						msg = "撤回了一条消息"
					}
				} else if flashDur == -1 {
					// 拍一拍：个性化为「X拍了拍Y」
					t := pokeTarget
					if t == "" {
						t = "对方"
					}
					if senderName != "" {
						msg = senderName + "拍了拍" + t
					} else {
						msg = "拍了拍" + t
					}
				} else if lastMedia == "voice" {
					// 语音 content 是时长秒数，不能当正文预览（会显示成「2」）
					if senderName != "" {
						msg = senderName + ": [语音]"
					} else {
						msg = "[语音]"
					}
				} else if senderName != "" && msg != "" {
					msg = senderName + ": " + msg
				}
				groupLastMsg[convID] = msg
				groupLastTime[convID] = tm
			}
		}

		for _, g := range userGroups {
			groupID := -(1000 + g.ID)
			if seen[groupID] {
				continue // 已添加（如官方群）
			}
			seen[groupID] = true

			result = append(result, ConversationUser{
				User: User{
					ID:       groupID,
					Username: g.Name,
					Email:    fmt.Sprintf("group_%d@aurora.chat", g.ID),
				},
				LastMessage: groupLastMsg[groupID],
				LastTime:    groupLastTime[groupID],
			})
		}
	}

	// 2. 最近聊天的好友
	for _, u := range recent {
		// 注：不再按好友关系过滤。单方面删除好友后会话双方都保留（历史消息不丢），
		// 仅通过会话顶部横幅提示「对方还不是你的好友」，并以非好友身份继续通信。
		seen[u.ID] = true
		// 群聊消息在会话列表中已有正确预览
		result = append(result, u)
	}

	// 3. 没聊过天的好友
	for _, u := range friends {
		if !seen[u.ID] {
			result = append(result, ConversationUser{User: u})
		}
	}

	if result == nil {
		result = []ConversationUser{}
	}
	writeJSON(w, 200, "获取成功", result)
}

// ==================== 公钥管理 ====================

func handleUploadKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	var req struct {
		PublicKey string `json:"public_key"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	if req.PublicKey == "" {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	if err := savePublicKey(currentUserID, req.PublicKey); err != nil {
		log.Printf("保存公钥失败: %v", err)
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	writeJSON(w, 200, "公钥上传成功", nil)
}

func handleGetKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/keys/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, 400, "缺少用户ID", nil)
		return
	}

	targetUserID, _ := strconv.ParseInt(parts[0], 10, 64)
	if targetUserID == 0 {
		writeJSON(w, 400, "用户ID无效", nil)
		return
	}

	pubKey, err := getPublicKey(targetUserID)
	if err != nil {
		log.Printf("获取公钥失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if pubKey == "" {
		writeJSON(w, 404, "对方尚未上传公钥", nil)
		return
	}

	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"user_id":    targetUserID,
		"public_key": pubKey,
	})
}

func handleEnableE2E(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		E2EEnabled bool `json:"e2e_enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	enabled := 0
	if req.E2EEnabled {
		enabled = 1
	}
	if _, err := db.Exec("UPDATE users SET e2e_enabled = ? WHERE id = ?", enabled, currentUserID); err != nil {
		log.Printf("更新端到端加密开关失败: %v", err)
		writeJSON(w, 500, "设置失败", nil)
		return
	}

	writeJSON(w, 200, "设置成功", map[string]interface{}{
		"e2e_enabled": req.E2EEnabled,
	})
}

func handleGetE2EStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/e2e/status/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, 400, "缺少用户ID", nil)
		return
	}

	targetUserID, _ := strconv.ParseInt(parts[0], 10, 64)
	if targetUserID == 0 {
		writeJSON(w, 400, "用户ID无效", nil)
		return
	}

	var enabled int
	if err := db.QueryRow("SELECT e2e_enabled FROM users WHERE id = ?", targetUserID).Scan(&enabled); err != nil {
		log.Printf("查询端到端加密开关失败: %v", err)
		writeJSON(w, 400, "用户不存在", nil)
		return
	}

	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"e2e_enabled": enabled == 1,
	})
}
