package main

import (
	"bufio"
	"crypto/subtle"
	"encoding/base64"
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

// DisconnectUser 强制断开指定用户的全部 TCP 连接
func DisconnectUser(userID int64) {
	onlineUsersMu.RLock()
	conns := make([]net.Conn, 0, len(onlineUsers[userID]))
	for c := range onlineUsers[userID] {
		conns = append(conns, c)
	}
	onlineUsersMu.RUnlock()
	for _, c := range conns {
		c.Close()
	}
}

// ==================== 在线用户 TCP 连接池 ====================
// 一个账号可以同时挂多条连接（手机 + 桌面 + …）。早期这里是 map[int64]net.Conn 单槽，
// 桌面端一连上就会把手机那条连接从池里挤掉，表现为「手机突然收不到消息」——看着像被顶号。
// 改成按「连接集合」存储后，推送会广播到该账号的所有在线设备。
var (
	onlineUsers     = make(map[int64]map[net.Conn]struct{})
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
	online := len(onlineUsers[userID]) > 0
	onlineUsersMu.RUnlock()
	if !online {
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
		for id, conns := range onlineUsers {
			last, ok := lastHeartbeat[id]
			if ok && now.Sub(last) > heartbeatTimeout*time.Second {
				for conn := range conns {
					conn.Close()
				}
				delete(onlineUsers, id)
				delete(lastHeartbeat, id)
				log.Printf("[TCP] 心跳超时，强制下线: ID=%d（连接数=%d）", id, len(conns))
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

// getTCPPushKey 获取用户的推送加密密钥（base64 session_key → 32 字节 AES 密钥）。
// 用户无 session_key 或密钥非法时返回 nil，调用方据此降级明文并记录日志，保证消息不丢失。
func getTCPPushKey(userID int64) []byte {
	keyB64 := getSessionKey(userID)
	if keyB64 == "" {
		return nil
	}
	key, err := base64.StdEncoding.DecodeString(keyB64)
	if err != nil || len(key) != 32 {
		return nil
	}
	return key
}

// writeEncryptedPush 将推送帧加密后写入连接：外层 {"type":"push","data":"sess:v1:..."}。
// data 为用该用户 session_key AES-256-GCM 加密的内层 JSON 字符串（base64(nonce||ciphertext)），
// 与客户端 sess:v1: 传输加密格式一致（见 CryptoUtil.decryptWithKey）。无有效密钥时降级明文。
func writeEncryptedPush(conn net.Conn, userID int64, payload map[string]interface{}) error {
	plain, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	key := getTCPPushKey(userID)
	var line []byte
	if key == nil {
		log.Printf("[TCP] 推送加密降级为明文: user=%d（无有效 session_key）", userID)
		line = append(plain, '\n')
	} else {
		enc, err := EncryptMessageAES(string(plain), key)
		if err != nil {
			return err
		}
		outer, _ := json.Marshal(map[string]interface{}{
			"type": "push",
			"data": "sess:v1:" + enc,
		})
		line = append(outer, '\n')
	}
	if _, err := conn.Write(line); err != nil {
		return err
	}
	return nil
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
		if err := writeEncryptedPush(conn, userID, payload); err != nil {
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

// PushMessagePersistedReceipt 落库回执：把「服务端已替我落库」这件事推回给发起方自己。
//
// 为什么需要这条回执：
// 客户端对普通聊天消息会先插一个乐观气泡，发出去立刻可见；
// 但卡片类消息（清空请求/拒绝、隐私请求/响应）、以及从别的入口代发的消息（分享给好友/群聊），
// 客户端根本没有对应的气泡 —— 发起方只能等 15 秒兜底轮询、或切出重进，才能看到自己刚发出去的东西。
//
// 这里给发起方推一条轻量回执，客户端收到后立刻做一次增量对齐（会话内 pull + 会话列表刷新），
// 于是「我发出去的东西」在我这边也是秒级可见，行为与普通消息完全一致。
//
// 单独用 message_persisted 事件而不是复用 new_message：new_message 在客户端语义是
// 「有人给我发消息」，会触发未读计数与系统通知；回执是「我自己的东西已入库」，两者不能混。
func PushMessagePersistedReceipt(userID int64, peerID int64, msgID int64) {
	if userID <= 0 {
		return
	}
	PushToUser(userID, "message_persisted", map[string]interface{}{
		"message_id": msgID,
		"peer_id":    peerID,
	})
}

// pushPersistedCardToBoth 卡片类消息落库后的统一推送：对方立刻看到 + 发起方立刻对齐。
//
// 统一规则：凡是「客户端没有乐观气泡」的服务端落库消息（清空请求/拒绝、隐私请求/响应…），
// 都必须让两端立刻知道：
//   · 对方收 new_message —— 走正常的实时消息通路，会出现在对方会话里；
//   · 发起方收 message_persisted 回执 —— 走增量对齐通路，不产生未读、不弹通知。
// 这样"我发出去的东西"在我这边也是秒级可见，不必等 15 秒兜底轮询或切出重进。
func pushPersistedCardToBoth(fromUserID, toUserID int64, msgID int64, content, fromUsername string) {
	if toUserID > 0 {
		PushToUser(toUserID, "new_message", map[string]interface{}{
			"message_id":    msgID,
			"from_user_id":  fromUserID,
			"from_username": fromUsername,
			"to_user_id":    toUserID,
			"content":       content,
			"created_at":    time.Now().Unix(),
		})
	}
	PushMessagePersistedReceipt(fromUserID, toUserID, msgID)
}

// PushToUser 向指定用户推送消息（如果在线，否则加入离线队列）
func PushToUser(userID int64, msgType string, data interface{}) {
	onlineUsersMu.RLock()
	conns := make([]net.Conn, 0, len(onlineUsers[userID]))
	for c := range onlineUsers[userID] {
		conns = append(conns, c)
	}
	onlineUsersMu.RUnlock()
	if len(conns) == 0 {
		// 用户不在线 → 加入离线推送队列，重连后补推
		QueuePendingPush(userID, msgType, data)
		return
	}

	payload := map[string]interface{}{
		"type": msgType,
		"data": data,
	}
	// 广播到该账号的每一条在线连接（手机、桌面各自都要收到）
	delivered := 0
	for _, conn := range conns {
		if err := writeEncryptedPush(conn, userID, payload); err != nil {
			log.Printf("[TCP] 推送消息失败 (user=%d): %v", userID, err)
			// 写失败说明这条连接已断开，只摘掉它自己，不影响同账号的其它设备
			onlineUsersMu.Lock()
			if set, exists := onlineUsers[userID]; exists {
				delete(set, conn)
				if len(set) == 0 {
					delete(onlineUsers, userID)
				}
			}
			onlineUsersMu.Unlock()
			conn.Close()
			continue
		}
		delivered++
	}
	// 全部设备都写失败时才进离线队列，否则重连补推会重复
	if delivered == 0 {
		QueuePendingPush(userID, msgType, data)
	}
}

// snapshotOnlineConns 在短临界区内复制「用户 → 连接列表」快照，供推送在锁外遍历。
// 一个账号可能有多台设备在线，所以值必须是列表而不是单条连接。
// exclude > 0 时排除该用户（通常是消息发送者）。
func snapshotOnlineConns(exclude int64) map[int64][]net.Conn {
	onlineUsersMu.RLock()
	defer onlineUsersMu.RUnlock()
	out := make(map[int64][]net.Conn, len(onlineUsers))
	for uid, set := range onlineUsers {
		if uid == exclude || len(set) == 0 {
			continue
		}
		list := make([]net.Conn, 0, len(set))
		for c := range set {
			list = append(list, c)
		}
		out[uid] = list
	}
	return out
}

// PushToGroup 向群聊所有在线成员推送消息（排除 senderID）
func PushToGroup(convID int64, msgID, fromUserID int64, fromUserName string, toUserID int64, content string, createdAt int64, mediaType, mediaURL string, flashDuration int, replyToID int64, replyToText, replyToSender string, mentions ...string) {
	mentionJSON := ""
	if len(mentions) > 0 {
		mentionJSON = mentions[0]
	}
	// 官方群全局免打扰：不向客户端下发 new_message（客户端据此弹通知），也不进离线补推队列。
	// 消息仍正常入库，用户打开官方群时按增量拉取即可看到。
	if isOfficialGroupNoNotify(convID) {
		return
	}

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
	// 每个成员可能有多条连接（手机+桌面），这里整组取出，后面一起广播。
	onlineUsersMu.RLock()
	onlineConns := make(map[int64][]net.Conn, len(onlineUsers))
	for uid, set := range onlineUsers {
		if uid == fromUserID {
			continue // 不推送给发送者
		}
		if len(set) == 0 {
			continue
		}
		list := make([]net.Conn, 0, len(set))
		for c := range set {
			list = append(list, c)
		}
		onlineConns[uid] = list
	}
	onlineUsersMu.RUnlock()

	// 实时推送补 to_user_name / msg_type：禁言等系统提示靠 to_user_name 渲染「某某 因…」，
	// 原先推送负载不带这两个字段，会导致名字只在 HTTP 拉取后出现、重载后又丢失（不稳定）。
	// 一次性预取，循环内复用，避免每条消息额外查库。
	pushToUserName, pushMsgType := "", ""
	if msgID > 0 {
		if pm, perr := getMessageByID(msgID); perr == nil && pm != nil {
			pushToUserName, pushMsgType = pm.ToUserName, pm.MsgType
		}
	}

	// 与群成员集合取交集后逐个推送
	var onlineMemberIDs []int64
	for uid, conns := range onlineConns {
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
				"to_user_name":    pushToUserName,
				"msg_type":        pushMsgType,
				"mentions":        mentionJSON,
			},
		}
		for _, conn := range conns {
			if err := writeEncryptedPush(conn, uid, payload); err != nil {
				log.Printf("[TCP] 群推失败 (user=%d): %v", uid, err)
				// 只摘掉写失败的那条连接，同账号其它设备不受影响
				onlineUsersMu.Lock()
				if set, exists := onlineUsers[uid]; exists {
					delete(set, conn)
					if len(set) == 0 {
						delete(onlineUsers, uid)
					}
				}
				onlineUsersMu.Unlock()
				conn.Close()
			}
		}
	}
	if len(onlineMemberIDs) > 0 {
		log.Printf("[TCP] 群推完成: convID=%d, 推送人数=%d", convID, len(onlineMemberIDs))
	}
}

// PushRecallToGroup 向群聊推送消息撤回通知
func PushRecallToGroup(convID int64, msgID, fromUserID int64, senderName string) {
	// 官方群全局免打扰：撤回通知同样不推送
	if isOfficialGroupNoNotify(convID) {
		return
	}

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

	// 短临界区快照在线用户（同 PushToGroup，一个账号可能有多台设备）
	onlineConns := snapshotOnlineConns(fromUserID)

	for uid, conns := range onlineConns {
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
		for _, conn := range conns {
			if err := writeEncryptedPush(conn, uid, payload); err != nil {
				log.Printf("[TCP] 群撤回推送失败 (user=%d): %v", uid, err)
			}
		}
	}
}

// PushGroupTodoEvent 向群聊推送群待办相关事件（设置/清除），排除发起者。
// 与 PushRecallToGroup 同构，仅事件类型与负载不同。
func PushGroupTodoEvent(convID int64, excludeUserID int64, eventType string, data map[string]interface{}) {
	if isOfficialGroupNoNotify(convID) {
		return
	}
	groupID := groupConvIDToID(convID)
	memberIDs, err := getGroupMembers(groupID)
	if err != nil {
		log.Printf("[TCP] 群待办推送失败: 获取群成员失败 convID=%d: %v", convID, err)
		return
	}
	memberSet := make(map[int64]struct{}, len(memberIDs))
	for _, uid := range memberIDs {
		memberSet[uid] = struct{}{}
	}
	onlineConns := snapshotOnlineConns(excludeUserID)
	for uid, conns := range onlineConns {
		if _, isMember := memberSet[uid]; !isMember {
			continue
		}
		payload := map[string]interface{}{
			"type": eventType,
			"data": data,
		}
		for _, conn := range conns {
			if err := writeEncryptedPush(conn, uid, payload); err != nil {
				log.Printf("[TCP] 群待办推送失败 (user=%d event=%s): %v", uid, eventType, err)
			}
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
			// 认证：{type:"auth",user_id:123,session_key:"<登录下发的传输会话密钥>"}
			if id, ok := msg["user_id"].(float64); ok {
				userID = int64(id)

				// 认证凭据校验：必须携带与服务器一致的 session_key（登录后由服务端下发的
				// 真实传输会话密钥，见 getOrCreateSessionKey）。仅凭 user_id 无法上线，
				// 杜绝伪造任意 user_id 接收他人消息（审计 B4）。
				providedKey, _ := msg["session_key"].(string)
				serverKey := getSessionKey(userID)
				if serverKey == "" || providedKey == "" ||
					subtle.ConstantTimeCompare([]byte(providedKey), []byte(serverKey)) != 1 {
					log.Printf("[TCP] 认证失败: session_key 校验不通过 (ID=%d, %s)", userID, remoteAddr)
					resp, _ := json.Marshal(map[string]string{
						"type":    "auth_denied",
						"message": "认证失败，请重新登录",
					})
					resp = append(resp, '\n')
					conn.Write(resp)
					return
				}

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

				// 注册到在线池：同一账号允许多条连接并存（手机 + 桌面），不再关掉旧连接
				onlineUsersMu.Lock()
				set := onlineUsers[userID]
				if set == nil {
					set = make(map[net.Conn]struct{}, 1)
					onlineUsers[userID] = set
				}
				set[conn] = struct{}{}
				onlineUsersMu.Unlock()

				// 记录初始心跳时间
				updateHeartbeat(userID)

				log.Printf("[TCP] 用户上线: ID=%d (%s)", userID, remoteAddr)

				// 记录登录日志（TCP 自动上线也算一次登录）
				host, _, _ := net.SplitHostPort(remoteAddr)
				recordLogin(userID, host)

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
		remaining := 0
		onlineUsersMu.Lock()
		// 只摘掉这一条连接；同账号其它设备（手机/桌面）继续保留在池里
		if set, exists := onlineUsers[userID]; exists {
			delete(set, conn)
			remaining = len(set)
			if remaining == 0 {
				delete(onlineUsers, userID)
			}
		}
		onlineUsersMu.Unlock()
		if remaining == 0 {
			// 该账号已无任何在线连接，才算真正下线
			lastHeartbeatMu.Lock()
			delete(lastHeartbeat, userID)
			lastHeartbeatMu.Unlock()
			log.Printf("[TCP] 用户下线: ID=%d (%s)", userID, remoteAddr)
		} else {
			log.Printf("[TCP] 设备断开: ID=%d (%s)，仍有 %d 条连接在线", userID, remoteAddr, remaining)
		}
		// 记录用户最后断连时间
		db.Exec("UPDATE users SET updated_at = ? WHERE id = ?", time.Now().Unix(), userID)
	}
	log.Printf("[TCP] 断开连接: %s", remoteAddr)
}
