package main

import (
	"bufio"
	"encoding/json"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

// 用户操作状态管理（管理员通过 API 操作后生效）
type BanInfo struct {
	Reason string
	Until  int64 // Unix 时间戳，0 表示永不过期
}

type MuteInfo struct {
	Reason string
	Until  int64
}

var (
	blockedUserIDsMu sync.RWMutex
	blockedUserIDs   = make(map[int64]BanInfo)
	mutedUserIDsMu   sync.RWMutex
	mutedUserIDs     = make(map[int64]MuteInfo)
)

// IsUserBanned 检查用户是否被封禁（含过期判断）
func IsUserBanned(userID int64) bool {
	blockedUserIDsMu.RLock()
	info, ok := blockedUserIDs[userID]
	blockedUserIDsMu.RUnlock()
	if !ok {
		return false
	}
	if info.Until > 0 && time.Now().Unix() > info.Until {
		blockedUserIDsMu.Lock()
		delete(blockedUserIDs, userID)
		blockedUserIDsMu.Unlock()
		return false
	}
	return true
}

// IsUserMuted 检查用户是否被禁言
func IsUserMuted(userID int64) bool {
	mutedUserIDsMu.RLock()
	info, ok := mutedUserIDs[userID]
	mutedUserIDsMu.RUnlock()
	if !ok {
		return false
	}
	if info.Until > 0 && time.Now().Unix() > info.Until {
		mutedUserIDsMu.Lock()
		delete(mutedUserIDs, userID)
		mutedUserIDsMu.Unlock()
		return false
	}
	return true
}

// RemoveBanRecord 移除用户的封禁/禁言记录
func RemoveBanRecord(userID int64) {
	blockedUserIDsMu.Lock()
	delete(blockedUserIDs, userID)
	blockedUserIDsMu.Unlock()
	mutedUserIDsMu.Lock()
	delete(mutedUserIDs, userID)
	mutedUserIDsMu.Unlock()
}

// AddBanRecord 将封禁记录添加到内存
func AddBanRecord(userID int64, reason string, until int64) {
	blockedUserIDsMu.Lock()
	blockedUserIDs[userID] = BanInfo{Reason: reason, Until: until}
	blockedUserIDsMu.Unlock()
}

// AddMuteRecord 将禁言记录添加到内存
func AddMuteRecord(userID int64, until int64) {
	mutedUserIDsMu.Lock()
	mutedUserIDs[userID] = MuteInfo{Reason: "管理员禁言", Until: until}
	mutedUserIDsMu.Unlock()
}

// RemoveMuteRecord 移除用户的禁言记录
func RemoveMuteRecord(userID int64) {
	mutedUserIDsMu.Lock()
	delete(mutedUserIDs, userID)
	mutedUserIDsMu.Unlock()
}

// LoadActiveBans 从数据库恢复所有有效封禁到内存
func LoadActiveBans() {
	bans, err := getAllBannedUsers()
	if err != nil {
		log.Printf("[封禁] 恢复内存封禁失败: %v", err)
		return
	}
	for _, b := range bans {
		AddBanRecord(b.UserID, b.Reason, b.ExpiresAt)
	}
	log.Printf("[封禁] 已从数据库恢复 %d 条封禁记录", len(bans))
}

// LoadActiveMutes 从数据库恢复所有有效禁言到内存
func LoadActiveMutes() {
	mutes, err := getAllMutedUsers()
	if err != nil {
		log.Printf("[禁言] 恢复内存禁言失败: %v", err)
		return
	}
	for _, m := range mutes {
		AddMuteRecord(m.UserID, m.ExpiresAt)
	}
	log.Printf("[禁言] 已从数据库恢复 %d 条禁言记录", len(mutes))
}

// CheckExpiredBans 检查过期封禁，自动解封并发送邮件通知
func CheckExpiredBans() {
	now := time.Now().Unix()
	blockedUserIDsMu.Lock()
	for userID, info := range blockedUserIDs {
		if info.Until > 0 && now >= info.Until {
			delete(blockedUserIDs, userID)
			// 从 DB 删除并发送通知
			go func(uid int64) {
				ban, err := getBanStatus(uid)
				if err == nil && ban != nil {
					// 已过期，从 DB 删除
					unbanUser(uid)
					if ban.UnbanNotify == 1 {
						user, _ := findUserByID(uid)
						if user != nil && user.Email != "" {
							sendUnbanEmail(user.Email, ban.Reason)
						}
					}
				}
			}(userID)
		}
	}
	blockedUserIDsMu.Unlock()
}

// CheckExpiredMutes 检查过期禁言，自动解除
func CheckExpiredMutes() {
	now := time.Now().Unix()
	mutedUserIDsMu.Lock()
	for userID, info := range mutedUserIDs {
		if info.Until > 0 && now >= info.Until {
			delete(mutedUserIDs, userID)
			// 从 DB 删除
			go func(uid int64) {
				unmuteUser(uid)
			}(userID)
		}
	}
	mutedUserIDsMu.Unlock()
}

// DisconnectUser 强制断开指定用户的 TCP 连接
func DisconnectUser(userID int64) {
	onlineUsersMu.RLock()
	conn, ok := onlineUsers[userID]
	onlineUsersMu.RUnlock()
	if ok && conn != nil {
		conn.Close()
	}
}

// ==================== 在线用户 TCP 连接池 ====================
var (
	onlineUsers     = make(map[int64]net.Conn)
	onlineUsersMu   sync.RWMutex
	lastHeartbeat   = make(map[int64]time.Time)
	lastHeartbeatMu sync.RWMutex
	// lastSeen 记录每个用户最后活跃（心跳）时间，断开时保留，用于计算离线时长
	lastSeen   = make(map[int64]time.Time)
	lastSeenMu sync.RWMutex
)

const heartbeatTimeout = 60 // 秒，超过此时间无心跳视为离线

// GetOnlineUserIDs 返回当前在线用户 ID 列表（供 HTTP API 使用）
func GetOnlineUserIDs() []int64 {
	onlineUsersMu.RLock()
	defer onlineUsersMu.RUnlock()
	ids := make([]int64, 0, len(onlineUsers))
	for id := range onlineUsers {
		ids = append(ids, id)
	}
	return ids
}

// isUserOnline 检查指定用户是否在线（含心跳超时检测）
func isUserOnline(userID int64) bool {
	onlineUsersMu.RLock()
	_, ok := onlineUsers[userID]
	onlineUsersMu.RUnlock()
	if !ok {
		return false
	}
	lastHeartbeatMu.RLock()
	last, has := lastHeartbeat[userID]
	lastHeartbeatMu.RUnlock()
	if has && time.Since(last) > heartbeatTimeout*time.Second {
		return false
	}
	return true
}

// updateHeartbeat 更新用户最后心跳时间
func updateHeartbeat(userID int64) {
	now := time.Now()
	lastHeartbeatMu.Lock()
	lastHeartbeat[userID] = now
	lastHeartbeatMu.Unlock()
	lastSeenMu.Lock()
	lastSeen[userID] = now
	lastSeenMu.Unlock()
}

// offlineMinutes 返回用户离线的分钟数。

// cleanupStaleConnections 定期清理超时未心跳的连接
func cleanupStaleConnections() {
	ticker := time.NewTicker(30 * time.Second)
	for range ticker.C {
		now := time.Now()
		lastHeartbeatMu.Lock()
		onlineUsersMu.Lock()
		for id, conn := range onlineUsers {
			last, ok := lastHeartbeat[id]
			if ok && now.Sub(last) > heartbeatTimeout*time.Second {
				conn.Close()
				delete(onlineUsers, id)
				delete(lastHeartbeat, id)
				log.Printf("[TCP] 心跳超时，强制下线: ID=%d", id)
			}
		}
		onlineUsersMu.Unlock()
		lastHeartbeatMu.Unlock()
	}
}

// 离线消息推送队列（TCP 重连后补推）
var (
	pendingPushMu sync.RWMutex
	pendingPush   = make(map[int64][]pendingMessage) // userID → 未推送消息列表
)

type pendingMessage struct {
	Type string
	Data interface{}
}

// QueuePendingPush 将消息加入离线推送队列（TCP 重连后补推）
func QueuePendingPush(userID int64, msgType string, data interface{}) {
	pendingPushMu.Lock()
	pendingPush[userID] = append(pendingPush[userID], pendingMessage{Type: msgType, Data: data})
	pendingPushMu.Unlock()
}

// FlushPendingPushes 推送该用户所有积压的离线消息
func FlushPendingPushes(userID int64, conn net.Conn) {
	pendingPushMu.Lock()
	msgs, ok := pendingPush[userID]
	if ok {
		delete(pendingPush, userID)
	}
	pendingPushMu.Unlock()
	if !ok || len(msgs) == 0 {
		return
	}
	count := 0
	var unsent []pendingMessage
	for i, m := range msgs {
		payload := map[string]interface{}{
			"type": m.Type,
			"data": m.Data,
		}
		line, _ := json.Marshal(payload)
		line = append(line, '\n')
		if _, err := conn.Write(line); err != nil {
			// 连接写入失败：剩余（含本条）全部重新入队，等待下次重连补推，避免漏推导致本地漏删
			log.Printf("[TCP] 补推离线消息失败 (user=%d): %v，剩余 %d 条重新入队", userID, err, len(msgs)-i)
			unsent = append(unsent, msgs[i:]...)
			break
		}
		count++
	}
	if len(unsent) > 0 {
		pendingPushMu.Lock()
		pendingPush[userID] = append(pendingPush[userID], unsent...)
		pendingPushMu.Unlock()
	}
	if count > 0 {
		log.Printf("[TCP] 已补推 %d 条离线消息给用户 %d", count, userID)
	}
}

// PushToUser 向指定用户推送消息（如果在线，否则加入离线队列）
func PushToUser(userID int64, msgType string, data interface{}) {
	onlineUsersMu.RLock()
	conn, ok := onlineUsers[userID]
	onlineUsersMu.RUnlock()
	if !ok {
		// 用户不在线 → 加入离线推送队列，重连后补推
		QueuePendingPush(userID, msgType, data)
		return
	}

	payload := map[string]interface{}{
		"type": msgType,
		"data": data,
	}
	line, _ := json.Marshal(payload)
	line = append(line, '\n')
	if _, err := conn.Write(line); err != nil {
		log.Printf("[TCP] 推送消息失败 (user=%d): %v", userID, err)
		// 写失败说明连接已断开，清理并加入离线队列
		onlineUsersMu.Lock()
		if current, exists := onlineUsers[userID]; exists && current == conn {
			delete(onlineUsers, userID)
			conn.Close()
		}
		onlineUsersMu.Unlock()
		QueuePendingPush(userID, msgType, data)
	}
}

// PushToGroup 向群聊所有在线成员推送消息（排除 senderID）
func PushToGroup(convID int64, msgID, fromUserID int64, fromUserName string, toUserID int64, content string, createdAt int64, mediaType, mediaURL string, flashDuration int, replyToID int64, replyToText, replyToSender string) {
	// 从 convID 反推群内部 ID: convID = -(1000 + groupID) => groupID = -convID - 1000
	groupID := -convID - 1000

	// 一次性预取群成员 ID 集合（替代对每个在线用户逐人 COUNT 查询，DB 次数从 O(在线人数) 降为 O(1)）
	memberIDs, err := getGroupMembers(groupID)
	if err != nil {
		log.Printf("[TCP] 群推失败: 获取群成员失败 convID=%d: %v", convID, err)
		return
	}
	memberSet := make(map[int64]struct{}, len(memberIDs))
	for _, uid := range memberIDs {
		memberSet[uid] = struct{}{}
	}

	// 短临界区快照在线用户（DB 查询在锁外执行，避免长时间占用读锁阻塞上下线/其它推送）
	onlineUsersMu.RLock()
	onlineConns := make(map[int64]net.Conn, len(onlineUsers))
	for uid, conn := range onlineUsers {
		if uid == fromUserID {
			continue // 不推送给发送者
		}
		onlineConns[uid] = conn
	}
	onlineUsersMu.RUnlock()

	// 与群成员集合取交集后逐个推送
	var onlineMemberIDs []int64
	for uid, conn := range onlineConns {
		if _, isMember := memberSet[uid]; !isMember {
			continue
		}
		onlineMemberIDs = append(onlineMemberIDs, uid)
		payload := map[string]interface{}{
			"type": "new_message",
			"data": map[string]interface{}{
				"message_id":      msgID,
				"from_user_id":    fromUserID,
				"from_username":   fromUserName,
				"to_user_id":      toUserID,
				"content":         content,
				"created_at":      createdAt,
				"media_type":      mediaType,
				"media_url":       mediaURL,
				"flash_duration":  flashDuration,
				"reply_to_id":     replyToID,
				"reply_to_text":   replyToText,
				"reply_to_sender": replyToSender,
			},
		}
		line, _ := json.Marshal(payload)
		line = append(line, '\n')
		if _, err := conn.Write(line); err != nil {
			log.Printf("[TCP] 群推失败 (user=%d): %v", uid, err)
			// 写失败说明连接已断开，仅当仍是同一连接时清理，避免误删重连后的新连接
			onlineUsersMu.Lock()
			if current, exists := onlineUsers[uid]; exists && current == conn {
				delete(onlineUsers, uid)
				conn.Close()
			}
			onlineUsersMu.Unlock()
		}
	}
	if len(onlineMemberIDs) > 0 {
		log.Printf("[TCP] 群推完成: convID=%d, 推送人数=%d", convID, len(onlineMemberIDs))
	}
}

// PushRecallToGroup 向群聊推送消息撤回通知
func PushRecallToGroup(convID int64, msgID, fromUserID int64, senderName string) {
	groupID := -convID - 1000

	// 一次性预取群成员 ID 集合（同 PushToGroup，DB 查询 O(1)）
	memberIDs, err := getGroupMembers(groupID)
	if err != nil {
		log.Printf("[TCP] 群撤回推送失败: 获取群成员失败 convID=%d: %v", convID, err)
		return
	}
	memberSet := make(map[int64]struct{}, len(memberIDs))
	for _, uid := range memberIDs {
		memberSet[uid] = struct{}{}
	}

	// 短临界区快照在线用户
	onlineUsersMu.RLock()
	onlineConns := make(map[int64]net.Conn, len(onlineUsers))
	for uid, conn := range onlineUsers {
		if uid == fromUserID {
			continue
		}
		onlineConns[uid] = conn
	}
	onlineUsersMu.RUnlock()

	for uid, conn := range onlineConns {
		if _, isMember := memberSet[uid]; !isMember {
			continue
		}
		payload := map[string]interface{}{
			"type": "message_recalled",
			"data": map[string]interface{}{
				"message_id":  msgID,
				"sender_name": senderName,
			},
		}
		line, _ := json.Marshal(payload)
		line = append(line, '\n')
		if _, err := conn.Write(line); err != nil {
			log.Printf("[TCP] 群撤回推送失败 (user=%d): %v", uid, err)
		}
	}
}

// StartTCPServer 启动裸 TCP 长连接服务器
func StartTCPServer(addr string) {
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("TCP 服务器启动失败: %v", err)
	}
	log.Printf("  TCP 长连接: tcp://0.0.0.0%s", addr)

	// 启动心跳超时清理协程
	go cleanupStaleConnections()

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("TCP 接受连接失败: %v", err)
			continue
		}
		go handleTCPConnection(conn)
	}
}

func handleTCPConnection(conn net.Conn) {
	defer conn.Close()

	remoteAddr := conn.RemoteAddr().String()
	log.Printf("[TCP] 新连接: %s", remoteAddr)

	// 禁用 Nagle 算法，小包即时发送（核心延迟优化）
	tcpConn := conn.(*net.TCPConn)
	tcpConn.SetNoDelay(true)
	// 设置读超时（如果超过 heartbeatTimeout 无数据，自动断开）
	conn.SetReadDeadline(time.Now().Add((heartbeatTimeout + 30) * time.Second))

	scanner := bufio.NewScanner(conn)
	scanner.Buffer(make([]byte, 65536), 65536)

	var userID int64

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		var msg map[string]interface{}
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			continue
		}

		msgType, _ := msg["type"].(string)

		switch msgType {
		case "auth":
			// 认证：{type:"auth",user_id:123}
			if id, ok := msg["user_id"].(float64); ok {
				userID = int64(id)

				// 检查是否被封禁
				if IsUserBanned(userID) {
					info := blockedUserIDs[userID]
					log.Printf("[TCP] 封禁用户尝试连接: ID=%d (%s)", userID, remoteAddr)

					//  先推送离线积压消息（含 account_banned），确保客户端收到完整封禁信息后再断连
					FlushPendingPushes(userID, conn)

					// 从 DB 获取完整封禁信息（含 unban_popup_message）
					var unbanPopupMsg string
					db.QueryRow("SELECT unban_popup_message FROM banned_users WHERE user_id = ?", userID).Scan(&unbanPopupMsg)

					resp, _ := json.Marshal(map[string]interface{}{
						"type":    "auth_denied",
						"message": "账号已被封禁",
						"data":    map[string]interface{}{"reason": info.Reason, "until": info.Until, "unban_popup_message": unbanPopupMsg},
					})
					resp = append(resp, '\n')
					conn.Write(resp)
					return
				}

				// 检查用户 ID 是否存在且未被软删除（注销用户禁止连接）
				var username string
				err := db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
				if err != nil || username == "" || username == "注销用户" {
					log.Printf("[TCP] 拒绝连接: 用户 ID=%d 不存在或已被注销 (%s)", userID, remoteAddr)
					resp, _ := json.Marshal(map[string]string{
						"type":    "auth_denied",
						"message": "账号异常，请重新登录",
					})
					resp = append(resp, '\n')
					conn.Write(resp)
					return
				}

				// 注册到在线池
				onlineUsersMu.Lock()
				// 如果之前已经有连接，关闭旧的
				if oldConn, exists := onlineUsers[userID]; exists {
					oldConn.Close()
				}
				onlineUsers[userID] = conn
				onlineUsersMu.Unlock()

				// 记录初始心跳时间
				updateHeartbeat(userID)

				log.Printf("[TCP] 用户上线: ID=%d (%s)", userID, remoteAddr)

				// 推送离线积压消息
				FlushPendingPushes(userID, conn)

				// 回复 ok
				resp, _ := json.Marshal(map[string]string{"type": "auth_ok"})
				resp = append(resp, '\n')
				conn.Write(resp)
			}

		case "ping":
			// 心跳：更新最后心跳时间并回复
			if userID > 0 {
				updateHeartbeat(userID)
				// 更新 updated_at 作为"最近活动时间"
				db.Exec("UPDATE users SET updated_at = ? WHERE id = ?", time.Now().Unix(), userID)
			}
			// 延长读超时
			conn.SetReadDeadline(time.Now().Add((heartbeatTimeout + 30) * time.Second))
			resp, _ := json.Marshal(map[string]string{"type": "pong"})
			resp = append(resp, '\n')
			conn.Write(resp)

		default:
			log.Printf("[TCP] 未知消息: %s", line)
		}
	}

	// 连接断开 — 更新数据库中的最后活动时间
	if userID > 0 {
		onlineUsersMu.Lock()
		// 只有当前的连接才是有效连接
		if current, exists := onlineUsers[userID]; exists && current == conn {
			delete(onlineUsers, userID)
			log.Printf("[TCP] 用户下线: ID=%d (%s)", userID, remoteAddr)
		}
		onlineUsersMu.Unlock()
		lastHeartbeatMu.Lock()
		delete(lastHeartbeat, userID)
		lastHeartbeatMu.Unlock()
		// 记录用户最后断连时间
		db.Exec("UPDATE users SET updated_at = ? WHERE id = ?", time.Now().Unix(), userID)
	}
	log.Printf("[TCP] 断开连接: %s", remoteAddr)
}
