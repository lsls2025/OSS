package main

import (
	"encoding/json"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

// ==================== 开发者广播（仅 ID=1 可用） ====================
// 设计要点：
//   - to_user > 0：作为私聊消息，仅写入该用户会话（to_user_id=目标用户）。
//   - to_user < 0：作为群消息，仅写入该群会话（convID，如官方群 -1001、普通群 -1002…）。
//   - to_user == 0：兼容旧行为，等同于发到官方群（-1001）。
//   - 无论哪种，都只写入「当前这一个会话」，绝不遍历好友/群聊群发。
//   - 发送时仅做受理（立即返回 task_id），真正的 N 条消息由后台 goroutine
//     逐条插入并推送，带限速，绝不阻塞前端请求线程，也不阻塞其它业务。
//   - 支持「撤回全部」：标记任务取消后，后台循环会在下一轮停止，并清理已发出的消息。
//   - 所有接口都强制校验调用者 userID == 1（服务端权威校验，前端隐藏只是 UX）。

const broadcastConvID = int64(-1001) // 官方群会话 ID

// BroadcastTask 记录一次广播任务的进度，供撤回/查询使用。
type BroadcastTask struct {
	ID        int64
	Cancelled bool
	Sent      int
	Total     int
}

var (
	broadcastTasksMu sync.RWMutex
	broadcastTasks   = map[int64]*BroadcastTask{}
	broadcastTaskSeq int64
)

// handleBroadcastSend 受理一次广播任务（不阻塞）。
func handleBroadcastSend(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID != 1 {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	var req struct {
		Text   string `json:"text"`
		Count  int    `json:"count"`
		ToUser int64  `json:"to_user"` // 指定接收用户；0 表示官方群（所有人）
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	text := trimBroadcastText(req.Text)
	if text == "" {
		writeJSON(w, 400, "广播内容不能为空", nil)
		return
	}
	count := req.Count
	if count < 1 {
		count = 1
	}
	if count > 100000 {
		count = 100000 // 硬上限，防止误填巨大数把库打爆
	}
	toUser := req.ToUser
	if toUser > 0 {
		// 校验目标用户存在，避免把广播写进不存在的会话
		var exists int
		if err := db.QueryRow("SELECT 1 FROM users WHERE id = ? LIMIT 1", toUser).Scan(&exists); err != nil {
			writeJSON(w, 400, "目标用户不存在", nil)
			return
		}
	} else if toUser < 0 {
		// 目标为群会话：convID = -(1000 + groupID)，校验群确实存在
		groupID := -toUser - 1000
		var exists int
		if err := db.QueryRow("SELECT 1 FROM groups WHERE id = ? LIMIT 1", groupID).Scan(&exists); err != nil {
			writeJSON(w, 400, "目标群聊不存在", nil)
			return
		}
	}

	taskID := atomic.AddInt64(&broadcastTaskSeq, 1)
	broadcastTasksMu.Lock()
	broadcastTasks[taskID] = &BroadcastTask{ID: taskID, Cancelled: false, Sent: 0, Total: count}
	broadcastTasksMu.Unlock()

	// 立即返回，真正的发送在后台 goroutine 慢慢处理
	go runBroadcastTask(taskID, userID, text, count, toUser)
	writeJSON(w, 200, "已受理，后台处理中", map[string]interface{}{"task_id": taskID})
}

// runBroadcastTask 后台逐条发送广播。发现任务被取消则停止并退出。
//   - toUser>0：作为「系统广播」私聊消息，仅发给该用户（to_user_id=目标用户），绝不波及他人/群聊。
//   - toUser<0：作为「系统广播」群消息，仅写入该群会话（to_user_id=群 convID），绝不波及其它会话。
//   - toUser==0：写入官方群（-1001），所有人可见（保留原行为，用于真正的全员公告）。
func runBroadcastTask(taskID, fromUserID int64, text string, count int, toUser int64) {
	store := encryptContentStore(text)
	for i := 0; i < count; i++ {
		// 每轮先检查取消，避免继续发送
		broadcastTasksMu.RLock()
		task, ok := broadcastTasks[taskID]
		cancelled := !ok || task.Cancelled
		broadcastTasksMu.RUnlock()
		if cancelled {
			return
		}
		// 写入前再次确认（缩小与撤回竞争的窗口）
		broadcastTasksMu.RLock()
		task, ok = broadcastTasks[taskID]
		cancelled = !ok || task.Cancelled
		broadcastTasksMu.RUnlock()
		if cancelled {
			return
		}

		now := time.Now().Unix()
		if toUser > 0 {
			// —— 指定单个用户：仅写入该用户私聊 ——
			res, err := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, broadcast_task_id, created_at) VALUES (?, ?, ?, 'broadcast', ?, ?)`,
				fromUserID, toUser, store, taskID, now)
			if err == nil {
				if msgID, e := res.LastInsertId(); e == nil {
					data := map[string]interface{}{
						"message_id":     msgID,
						"from_user_id":   fromUserID,
						"from_username":  "系统广播",
						"to_user_id":     toUser,
						"content":        text,
						"created_at":     now,
						"msg_type":       "broadcast",
						"media_type":     "",
						"media_url":      "",
						"flash_duration": 0,
					}
					PushToUser(toUser, "new_message", data)     // 推给目标用户
					PushToUser(fromUserID, "new_message", data) // 推给开发者自己（便于查看）
				}
			}
		} else if toUser < 0 {
			// —— 指定群聊：仅写入该群会话 ——
			res, err := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, broadcast_task_id, created_at) VALUES (?, ?, ?, 'broadcast', ?, ?)`,
				fromUserID, toUser, store, taskID, now)
			if err == nil {
				if msgID, e := res.LastInsertId(); e == nil {
					PushToGroup(toUser, msgID, fromUserID, "系统广播", toUser, text, now, "", "", 0, 0, "", "")
					// 群推会排除发送者自身，这里单独推给开发者，便于自己看到广播一条条刷出来
					PushToUser(fromUserID, "new_message", map[string]interface{}{
						"message_id":     msgID,
						"from_user_id":   fromUserID,
						"from_username":  "系统广播",
						"to_user_id":     toUser,
						"content":        text,
						"created_at":     now,
						"msg_type":       "broadcast",
						"media_type":     "",
						"media_url":      "",
						"flash_duration": 0,
					})
				}
			}
		} else {
			// —— 官方群（所有人可见）——
			res, err := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, broadcast_task_id, created_at) VALUES (?, ?, ?, 'broadcast', ?, ?)`,
				fromUserID, broadcastConvID, store, taskID, now)
			if err == nil {
				if msgID, e := res.LastInsertId(); e == nil {
					PushToGroup(broadcastConvID, msgID, fromUserID, "系统广播", broadcastConvID, text, now, "", "", 0, 0, "", "")
					// 群推会排除发送者自身，这里单独把 new_message 推给开发者，
					// 否则开发者自己看不到广播在官方群里一条条刷出来。
					PushToUser(fromUserID, "new_message", map[string]interface{}{
						"message_id":     msgID,
						"from_user_id":   fromUserID,
						"from_username":  "系统广播",
						"to_user_id":     broadcastConvID,
						"content":        text,
						"created_at":     now,
						"media_type":     "",
						"media_url":      "",
						"flash_duration": 0,
					})
				}
			}
		}
		broadcastTasksMu.Lock()
		if t, ok := broadcastTasks[taskID]; ok {
			t.Sent++
		}
		broadcastTasksMu.Unlock()

		// 限速：每条间隔，避免瞬间打满数据库 / 推送通道；同时保证后端不阻塞前端。
		time.Sleep(80 * time.Millisecond)
	}
	// 正常跑完，清理注册表
	broadcastTasksMu.Lock()
	delete(broadcastTasks, taskID)
	broadcastTasksMu.Unlock()
}

// pushToAllOnline 向所有在线用户推送自定义事件（广播的删除/撤回实时同步到任何会话）。
func pushToAllOnline(eventType string, data map[string]interface{}) {
	payload := map[string]interface{}{"type": eventType, "data": data}
	line, _ := json.Marshal(payload)
	line = append(line, '\n')
	onlineUsersMu.RLock()
	defer onlineUsersMu.RUnlock()
	for _, conn := range onlineUsers {
		conn.Write(line)
	}
}

// handleBroadcastDeleteSingle 删除单条广播（悄无声息，所有人侧同步移除）。
func handleBroadcastDeleteSingle(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID != 1 {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	var req struct {
		MessageID int64 `json:"message_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.MessageID <= 0 {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	// 校验这确实是一条自己的广播，防止越权删除他人消息；并取出其 broadcast_task_id
	var mt string
	var fid int64
	var tid int64
	if err := db.QueryRow("SELECT msg_type, from_user_id, broadcast_task_id FROM messages WHERE id = ?", req.MessageID).Scan(&mt, &fid, &tid); err != nil || mt != "broadcast" || fid != userID {
		writeJSON(w, 403, "无权删除该消息", nil)
		return
	}
	// 删除该广播的全部副本（官方群 + 普通群聊 + 私信，均共享同一 broadcast_task_id）
	if _, err := db.Exec("DELETE FROM messages WHERE broadcast_task_id = ? AND msg_type = 'broadcast'", tid); err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	pushToAllOnline("broadcast_deleted", map[string]interface{}{"task_id": tid})
	writeJSON(w, 200, "已删除", nil)
}

// handleBroadcastRecallAll 撤回整个广播任务：停止未发送的，并清理已发出的。
func handleBroadcastRecallAll(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID != 1 {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	var req struct {
		TaskID int64 `json:"task_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.TaskID <= 0 {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	// 先标记取消，后台循环下一轮即停止（不再插入新消息）
	broadcastTasksMu.Lock()
	if t, ok := broadcastTasks[req.TaskID]; ok {
		t.Cancelled = true
	}
	broadcastTasksMu.Unlock()

	// 清理该任务已发出的所有广播消息
	if _, err := db.Exec("DELETE FROM messages WHERE broadcast_task_id = ? AND msg_type = 'broadcast'", req.TaskID); err != nil {
		writeJSON(w, 500, "清理失败", nil)
		return
	}
	pushToAllOnline("broadcast_recalled", map[string]interface{}{"task_id": req.TaskID})

	broadcastTasksMu.Lock()
	delete(broadcastTasks, req.TaskID)
	broadcastTasksMu.Unlock()
	writeJSON(w, 200, "已撤回全部广播", nil)
}

// trimBroadcastText 去除首尾空白，限制最大长度，避免异常内容。
func trimBroadcastText(s string) string {
	t := []rune(s)
	// 去除首尾空白
	start, end := 0, len(t)
	for start < end && isSpaceRune(t[start]) {
		start++
	}
	for end > start && isSpaceRune(t[end-1]) {
		end--
	}
	if start >= end {
		return ""
	}
	trimmed := string(t[start:end])
	const maxLen = 2000
	if len([]rune(trimmed)) > maxLen {
		runes := []rune(trimmed)
		trimmed = string(runes[:maxLen])
	}
	return trimmed
}

func isSpaceRune(r rune) bool {
	return r == ' ' || r == '\t' || r == '\n' || r == '\r' || r == '\u3000'
}
