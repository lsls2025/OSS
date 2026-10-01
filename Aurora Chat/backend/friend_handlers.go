package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// getCurrentUserID 从请求头提取当前登录用户 ID（由 authMiddleware 注入）
func getCurrentUserID(r *http.Request) int64 {
	id, _ := strconv.ParseInt(r.Header.Get("X-User-ID"), 10, 64)
	return id
}

func randomString(n int) string {
	b := make([]byte, n/2+1)
	rand.Read(b)
	return hex.EncodeToString(b)[:n]
}

func getCurrentEmail(r *http.Request) string {
	return r.Header.Get("X-User-Email")
}

// getCurrentEmailFromHeader 从请求头提取当前用户邮箱（由 authMiddleware 注入）
func getCurrentEmailFromHeader(r *http.Request) string {
	return r.Header.Get("X-User-Email")
}

// maskEmail 将邮箱脱敏为 a***@x.com
func maskEmail(email string) string {
	at := strings.Index(email, "@")
	if at <= 1 {
		return "***"
	}
	prefix := email[:at]
	suffix := email[at:]
	if len(prefix) <= 2 {
		return prefix[:1] + "***" + suffix
	}
	return prefix[:2] + "***" + suffix
}

// maskQQ 将 QQ 号脱敏为 388****539
func maskQQ(qq string) string {
	if len(qq) <= 4 {
		return "****"
	}
	return qq[:2] + "****" + qq[len(qq)-3:]
}

// ==================== 搜索用户 ====================

func handleSearchUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	q := strings.TrimSpace(r.URL.Query().Get("q"))
	log.Printf("[搜索用户] 收到请求: q=%q", q)

	if q == "" {
		writeJSON(w, 200, "请输入搜索关键词", []User{})
		return
	}

	users, err := searchUsersByKeyword(q)
	if err != nil {
		log.Printf("[搜索用户] 数据库查询失败: %v", err)
		writeJSON(w, 500, "搜索失败", nil)
		return
	}

	// 隐私脱敏：搜索结果显示时隐藏 qq_number / reg_ip，email 仅在用户未隐藏时可见
	currentUID := getCurrentUserID(r)
	for i := range users {
		u := &users[i]
		if u.HideQQ != 0 || currentUID != u.ID {
			u.QQNumber = maskQQ(u.QQNumber)
		}
		if u.HideEmail != 0 && currentUID != u.ID {
			u.Email = maskEmail(u.Email)
		}
		u.RegIp = ""
		u.TokenBalance = 0
		u.TokenVersion = 0
	}

	if users == nil {
		users = []User{}
	}
	writeJSON(w, 200, "搜索成功", users)
}

// ==================== 好友请求 ====================

func handleSendFriendRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	var req struct {
		ToEmail  string `json:"to_email"`
		Greeting string `json:"greeting"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.ToEmail = strings.TrimSpace(req.ToEmail)
	if req.ToEmail == "" {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	// 支持邮箱 / QQ号 / 用户ID（与登录解析保持一致：纯数字优先按 QQ 号查）
	var targetUser *User
	var err error
	if emailRegex.MatchString(req.ToEmail) {
		targetUser, err = findUserByEmail(req.ToEmail)
	} else if isDigitsOnly(req.ToEmail) {
		targetUser, err = findUserByQQNumber(req.ToEmail)
		// QQ 号查不到时再按用户ID查（兼容旧版直接传 ID 的调用方）
		if targetUser == nil && err == nil {
			if uid, parseErr := strconv.ParseInt(req.ToEmail, 10, 64); parseErr == nil && uid > 0 {
				targetUser, err = findUserByID(uid)
			}
		}
	} else {
		targetUser, err = findUserByEmail(req.ToEmail)
	}
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if targetUser == nil {
		writeJSON(w, 404, "未找到该邮箱的用户", nil)
		return
	}
	if targetUser.ID == currentUserID {
		writeJSON(w, 400, "不能添加自己为好友", nil)
		return
	}
	// 拦截已注销账号（email 被改为 deleted_{id}@deleted.aurora.chat）
	if strings.HasPrefix(targetUser.Email, "deleted_") && strings.HasSuffix(targetUser.Email, "@deleted.aurora.chat") {
		writeJSON(w, 404, "该用户账号已注销", nil)
		return
	}
	// 拦截被封禁账号
	if IsUserBanned(targetUser.ID) {
		writeJSON(w, 403, "该用户已被封禁，无法发送好友申请", nil)
		return
	}

	alreadyFriends, _ := areFriends(currentUserID, targetUser.ID)
	if alreadyFriends {
		writeJSON(w, 409, "对方已经是你的好友", nil)
		return
	}

	hasPending, _ := hasPendingRequest(currentUserID, targetUser.ID)
	if hasPending {
		writeJSON(w, 409, "已发送过好友申请，请等待对方处理", nil)
		return
	}

	me, _ := findUserByID(currentUserID)
	if me == nil {
		writeJSON(w, 400, "用户不存在", nil)
		return
	}

	greeting := html.EscapeString(req.Greeting)
	reqID, err := createFriendRequest(currentUserID, targetUser.ID, me.Email, me.Username, greeting)
	if err != nil {
		writeJSON(w, 500, "发送失败", nil)
		return
	}

	log.Printf("好友请求已发送: %s(%d) -> %s(%d) request_id=%d", me.Email, currentUserID, targetUser.Email, targetUser.ID, reqID)

	// 通过 TCP 实时推送给目标用户
	PushToUser(targetUser.ID, "friend_request", map[string]interface{}{
		"from_user_id":  currentUserID,
		"from_username": me.Username,
		"from_email":    me.Email,
		"greeting":      greeting,
		"request_id":    reqID,
	})

	writeJSON(w, 200, "好友申请已发送", map[string]int64{"request_id": reqID})
}

func handleGetFriendRequests(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	requests, err := getFriendRequestsForUser(currentUserID)
	if err != nil {
		log.Printf("获取好友请求失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}

	if requests == nil {
		requests = []FriendRequest{}
	}
	log.Printf("[好友请求] 用户 %d 拉取好友请求，返回 %d 条", currentUserID, len(requests))
	for _, fr := range requests {
		log.Printf("[好友请求]   -> id=%d from=%d(%s) status=%s", fr.ID, fr.FromUserID, fr.FromUsername, fr.Status)
	}
	writeJSON(w, 200, "获取成功", requests)
}

func handleRespondFriendRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	var req struct {
		RequestID int64  `json:"request_id"`
		Action    string `json:"action"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	if req.RequestID == 0 || (req.Action != "accept" && req.Action != "reject") {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	fr, err := getFriendRequestByID(req.RequestID)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if fr == nil {
		writeJSON(w, 404, "好友请求不存在", nil)
		return
	}
	if fr.ToUserID != currentUserID {
		writeJSON(w, 403, "无权操作此请求", nil)
		return
	}

	if req.Action == "accept" {
		fromUser, _ := findUserByID(fr.FromUserID)
		toUser, _ := findUserByID(fr.ToUserID)
		if fromUser == nil || toUser == nil {
			writeJSON(w, 500, "用户不存在", nil)
			return
		}

		if err := addFriend(fr.ToUserID, fr.FromUserID, fromUser.Email, fromUser.Username); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		if err := addFriend(fr.FromUserID, fr.ToUserID, toUser.Email, toUser.Username); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		// 通知发起方好友请求已通过
		PushToUser(fr.FromUserID, "friend_accepted", map[string]interface{}{
			"user_id": fr.ToUserID,
			"name":    toUser.Username,
		})
	}

	if err := updateFriendRequestStatus(req.RequestID, req.Action+"ed"); err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}

	msg := "已拒绝"
	if req.Action == "accept" {
		msg = "已同意，你们现在是好友了"
	}
	log.Printf("好友请求 %d: %s", req.RequestID, req.Action)
	writeJSON(w, 200, msg, nil)
}

// POST /api/friend-request/cancel — 取消（删除）某条好友申请记录（本人发出或收到的均可）
func handleCancelFriendRequest(w http.ResponseWriter, r *http.Request) {
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
		RequestID int64 `json:"request_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.RequestID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	fr, err := getFriendRequestByID(req.RequestID)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if fr == nil {
		writeJSON(w, 404, "好友请求不存在", nil)
		return
	}
	if fr.FromUserID != currentUserID && fr.ToUserID != currentUserID {
		writeJSON(w, 403, "无权操作此请求", nil)
		return
	}
	if err := deleteFriendRequest(req.RequestID, currentUserID); err != nil {
		writeJSON(w, 500, "取消失败", nil)
		return
	}
	writeJSON(w, 200, "已取消", nil)
}

// ==================== 好友列表 ====================

func handleGetFriends(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	friends, err := getFriends(currentUserID)
	if err != nil {
		log.Printf("获取好友列表失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}

	if friends == nil {
		friends = []User{}
	}
	writeJSON(w, 200, "获取成功", friends)
}

// POST /api/friend/delete — 删除好友
func handleDeleteFriend(w http.ResponseWriter, r *http.Request) {
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
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.FriendID == 0 {
		writeJSON(w, 400, "缺少好友ID", nil)
		return
	}

	// 检查是否确实是好友
	isFriend, err := areFriends(currentUserID, req.FriendID)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if !isFriend {
		writeJSON(w, 400, "你们不是好友关系", nil)
		return
	}

	if err := deleteFriend(currentUserID, req.FriendID); err != nil {
		log.Printf("删除好友失败: %v", err)
		writeJSON(w, 500, "删除失败", nil)
		return
	}

	log.Printf("好友已删除: user=%d, friend=%d", currentUserID, req.FriendID)
	writeJSON(w, 200, "已删除好友", nil)
}

// ==================== 消息 ====================

func handleSendMessage(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	// JWT 认证后的用户 ID（由 authMiddleware 注入）
	fromUserID := getCurrentUserID(r)
	if fromUserID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}

	var req struct {
		ToUserID      int64  `json:"to_user_id"`
		Content       string `json:"content"`
		ReplyTo       int64  `json:"reply_to"`
		ReplyToText   string `json:"reply_to_text"`
		ReplyToSender string `json:"reply_to_sender"`
		MediaType     string `json:"media_type"`
		MediaURL      string `json:"media_url"`
		FlashDuration int    `json:"flash_duration"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	// ── 底防：全面禁言 / 官群禁言 ──
	// 全面禁言：除开发者外所有用户的消息（群聊/私聊等）一律强制截止发送
	if getFlag("full_mute") && !isDeveloperUser(fromUserID) {
		writeJSON(w, 403, "全站已开启全面禁言，消息发送已暂停", nil)
		return
	}
	// 官群禁言：官方群(display_id=1, convID=-1001)除开发者外禁止发言
	if getFlag("official_group_mute") && !isDeveloperUser(fromUserID) && req.ToUserID == -1001 {
		writeJSON(w, 403, "官方群已开启禁言，暂不可发言", nil)
		return
	}

	// 媒体消息：content 可以为空，media_url 必须有值
	isMedia := req.MediaType != "" && req.MediaURL != ""
	content := req.Content
	if !isMedia {
		content = html.EscapeString(strings.TrimSpace(req.Content))
		if content == "" {
			writeJSON(w, 400, "消息内容不能为空", nil)
			return
		}
		if len([]rune(content)) > 10000 {
			writeJSON(w, 400, "消息内容过长，最多10000个字符", nil)
			return
		}
	}

	log.Printf("[消息] 收到: from=%d to=%d media=%s", fromUserID, req.ToUserID, req.MediaType)

	// 私聊时校验收发双方ID是否有效
	if req.ToUserID > 0 {
		sender, err := findUserByID(fromUserID)
		if err != nil {
			log.Printf("[消息] findUserByID(sender=%d) 查询失败: %v", fromUserID, err)
			writeJSON(w, 500, "服务器内部错误", nil)
			return
		}
		if sender == nil || sender.Username == "注销用户" {
			writeJSON(w, 410, "您的账号异常，消息发送已被禁止", nil)
			return
		}
		receiver, err := findUserByID(req.ToUserID)
		if err != nil {
			log.Printf("[消息] findUserByID(receiver=%d) 查询失败: %v", req.ToUserID, err)
			writeJSON(w, 500, "服务器内部错误", nil)
			return
		}
		if receiver == nil || receiver.Username == "注销用户" {
			writeJSON(w, 410, "对方账号异常，无法发送消息", nil)
			return
		}
	}

	// 群聊消息：校验群存在且发送者未被拉黑（不检查 group_members，兼容官方群自动同步机制）
	if req.ToUserID < 0 {
		sender, err := findUserByID(fromUserID)
		if err != nil {
			log.Printf("[消息] findUserByID(sender=%d) 查询失败: %v", fromUserID, err)
			writeJSON(w, 500, "服务器内部错误", nil)
			return
		}
		if sender == nil || sender.Username == "注销用户" {
			writeJSON(w, 410, "您的账号异常，消息发送已被禁止", nil)
			return
		}
		internalGroupID := -(req.ToUserID + 1000)
		grp, _ := getGroupByID(internalGroupID)
		if grp == nil {
			log.Printf("[消息] 群不存在: group=%d", internalGroupID)
			writeJSON(w, 404, "群聊不存在", nil)
			return
		}
		blocked, _ := isGroupBlacklisted(internalGroupID, fromUserID)
		if blocked {
			log.Printf("[消息] 用户已被拉黑: group=%d, user=%d", internalGroupID, fromUserID)
			writeJSON(w, 403, "您已被移出该群", nil)
			return
		}

		// 检查群内禁言
		gmuted, gmuteExpires := isGroupMuted(internalGroupID, fromUserID)
		if gmuted {
			log.Printf("[消息] 用户在群内被禁言: group=%d, user=%d, expires=%d", internalGroupID, fromUserID, gmuteExpires)
			writeJSON(w, 403, "你在该群已被禁言，无法发送消息", nil)
			return
		}

		// 越权防护：需要审核入群的群，未通过审核（非群成员）禁止发言，与消息读取侧保持一致。
		// 仅针对开启了"入群需申请"的群，普通群/官方自动同步群不受影响（成员校验通过）。
		if getGroupJoinRequired(internalGroupID) && !isGroupMember(internalGroupID, fromUserID) {
			log.Printf("[消息] 越权拦截: 非群成员(待审核)尝试在群内发言 group=%d user=%d", internalGroupID, fromUserID)
			writeJSON(w, 403, "你尚未通过入群审核，无法在群内发言", nil)
			return
		}

		// 官方群（display_id=1, convID=-1001）：仅开发者可发送媒体消息
		if req.ToUserID == -1001 && isMedia && !isDeveloperUserObj(sender) {
			log.Printf("[消息] 官方群禁止非开发者发送媒体: user=%d, qq=%s", fromUserID, sender.QQNumber)
			writeJSON(w, 403, "官方群暂不支持发送图片、视频、文件", nil)
			return
		}

		// 未验证邮箱用户 → 官方群限速 1 条/60 秒
		if req.ToUserID == -1001 && sender.EmailVerified == 0 {
			if allowed, wait := checkUnverifiedMsgRate(fromUserID); !allowed {
				log.Printf("[消息] 未验证用户限速: user=%d 等待 %d 秒", fromUserID, wait)
				writeJSON(w, 429, fmt.Sprintf("您的账号当前处于受限状态，官方群每分钟只能发送 1 条消息，请 %d 秒后再试", wait), nil)
				return
			}
		}
	}

	msgID, err := saveMessage(fromUserID, req.ToUserID, content, req.ReplyTo, req.MediaType, req.MediaURL, req.FlashDuration, req.ReplyToText, req.ReplyToSender)
	if err != nil {
		log.Printf("[消息] 保存失败: %v", err)
		writeJSON(w, 500, "发送失败", nil)
		return
	}

	log.Printf("[消息] 保存成功: id=%d", msgID)

	// 获取已保存消息（含 reply_to 字段），用于 TCP 推送
	savedMsg, _ := getMessageByID(msgID)
	replyToID := int64(0)
	replyToText := ""
	replyToSender := ""
	if savedMsg != nil {
		replyToID = savedMsg.ReplyToID
		replyToText = savedMsg.ReplyToText
		replyToSender = savedMsg.ReplyToSender
	}

	// TCP 推送通知接收方
	sender, _ := findUserByID(fromUserID)
	senderName := ""
	if sender != nil {
		senderName = sender.Username
	}

	if req.ToUserID > 0 {
		// 私聊：推送给指定用户
		PushToUser(req.ToUserID, "new_message", map[string]interface{}{
			"message_id":      msgID,
			"from_user_id":    fromUserID,
			"from_username":   senderName,
			"to_user_id":      req.ToUserID,
			"content":         content,
			"created_at":      time.Now().Unix(),
			"media_type":      req.MediaType,
			"media_url":       req.MediaURL,
			"flash_duration":  req.FlashDuration,
			"reply_to_id":     replyToID,
			"reply_to_text":   replyToText,
			"reply_to_sender": replyToSender,
		})
		// 邮箱推送：接收者离线超5分钟且非免打扰时发信
		maybeEmailPushForMessage(req.ToUserID, fromUserID, senderName, content, false, "")
	} else if req.ToUserID < 0 {
		// 群聊：推送给群内所有在线成员（排除发送者自己）
		PushToGroup(req.ToUserID, msgID, fromUserID, senderName, req.ToUserID, content,
			time.Now().Unix(), req.MediaType, req.MediaURL, req.FlashDuration,
			replyToID, replyToText, replyToSender)
		// 邮箱推送：遍历群成员，被@的离线成员触发
		internalGroupID := -(req.ToUserID + 1000)
		grp, _ := getGroupByID(internalGroupID)
		grpName := ""
		if grp != nil {
			grpName = grp.Name
		}
		if members, err := getGroupMembers(internalGroupID); err == nil {
			for _, uid := range members {
				if uid == fromUserID {
					continue
				}
				maybeEmailPushForMessage(uid, fromUserID, senderName, content, true, grpName)
			}
		}
	}

	writeJSON(w, 200, "发送成功", map[string]int64{"message_id": msgID})
}

// ==================== token 转账 ====================

// 转账消息正文前缀（Android 端 parseTransferMessage 据此解析结构化字段）
const transferMsgPrefix = "系统转账数据"

// POST /api/transfer — token 转账（扣发送方、加接收方、写流水、推送收款通知 + 会话卡片）
// 视角由结构化字段驱动，不在服务端写死文案，避免群聊/私聊视角错乱。
func handleTransfer(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	fromUserID := getCurrentUserID(r)
	if fromUserID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}

	var req struct {
		ToUserID     int64  `json:"to_user_id"`     // 会话对端：1:1 为好友ID，群聊为群ID（负数）
		RealToUserID int64  `json:"real_to_user_id"` // 真实收款人ID（1:1 时等于 ToUserID）
		RealToName   string `json:"real_to_name"`    // 真实收款人昵称（兜底，后端会重新解析）
		Amount       int64  `json:"amount"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.Amount <= 0 {
		writeJSON(w, 400, "转账金额必须大于 0", nil)
		return
	}
	if req.RealToUserID <= 0 {
		writeJSON(w, 400, "收款人无效", nil)
		return
	}
	if req.RealToUserID == fromUserID {
		writeJSON(w, 400, "不能给自己转账", nil)
		return
	}
	// 会话对端必须有效（1:1 或群）
	if req.ToUserID == 0 {
		writeJSON(w, 400, "缺少会话目标", nil)
		return
	}

	sender, err := findUserByID(fromUserID)
	if err != nil || sender == nil {
		writeJSON(w, 500, "发送方账号异常", nil)
		return
	}
	receiver, err := findUserByID(req.RealToUserID)
	if err != nil || receiver == nil || receiver.Username == "注销用户" {
		writeJSON(w, 400, "收款人账号不存在", nil)
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

	balance := getEffectiveTokenBalance(fromUserID)
	if balance < req.Amount {
		writeJSON(w, 400, "token 余额不足", map[string]interface{}{"balance": balance, "need": req.Amount})
		return
	}

	// 流水表（首次使用自动建表，置于事务外避免 DDL 锁）
	db.Exec("CREATE TABLE IF NOT EXISTS transfers (id INTEGER PRIMARY KEY AUTOINCREMENT, from_user_id BIGINT, to_user_id BIGINT, conv_id BIGINT, amount BIGINT, created_at BIGINT)")

	// 事务：扣款 + 入账 + 写流水，保证原子性
	tx, err := db.Begin()
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	res, err := tx.Exec("UPDATE users SET token_balance = token_balance - ? WHERE id = ? AND token_balance >= ?", req.Amount, fromUserID, req.Amount)
	if err != nil {
		tx.Rollback()
		writeJSON(w, 500, "扣款失败", nil)
		return
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		tx.Rollback()
		writeJSON(w, 400, "token 余额不足", map[string]interface{}{"balance": getEffectiveTokenBalance(fromUserID), "need": req.Amount})
		return
	}
	if _, err := tx.Exec("UPDATE users SET token_balance = token_balance + ? WHERE id = ?", req.Amount, req.RealToUserID); err != nil {
		tx.Rollback()
		writeJSON(w, 500, "入账失败", nil)
		return
	}
	// 写流水
	if _, err := tx.Exec("INSERT INTO transfers (from_user_id, to_user_id, conv_id, amount, created_at) VALUES (?, ?, ?, ?, ?)",
		fromUserID, req.RealToUserID, req.ToUserID, req.Amount, time.Now().Unix()); err != nil {
		tx.Rollback()
		writeJSON(w, 500, "转账记录失败", nil)
		return
	}
	if err := tx.Commit(); err != nil {
		writeJSON(w, 500, "转账失败", nil)
		return
	}

	fromName := sender.Username
	toName := req.RealToName
	if toName == "" {
		toName = receiver.Username
	}

	// 会话卡片：结构化字段，视角由各端本地现算
	content := fmt.Sprintf("%s\nfrom_user_id=%d\nfrom_name=%s\nto_user_id=%d\nto_name=%s\namount=%d",
		transferMsgPrefix, fromUserID, fromName, req.RealToUserID, toName, req.Amount)
	msgID, err := saveMessage(fromUserID, req.ToUserID, content, 0, "transfer", "", 0, "", "")
	if err != nil {
		log.Printf("[转账] 卡片消息保存失败（钱款已到账）: %v", err)
	}

	now := time.Now().Unix()
	// 推送聊天消息给相关会话（1:1 推送给收款人；群聊推送给群内成员）
	if req.ToUserID > 0 {
		PushToUser(req.RealToUserID, "new_message", map[string]interface{}{
			"message_id": msgID, "from_user_id": fromUserID, "from_username": fromName,
			"to_user_id": req.ToUserID, "content": content, "created_at": now,
			"media_type": "transfer", "media_url": "", "flash_duration": 0,
			"reply_to_id": 0, "reply_to_text": "", "reply_to_sender": "",
		})
	} else if req.ToUserID < 0 {
		PushToGroup(req.ToUserID, msgID, fromUserID, fromName, req.ToUserID, content, now, "transfer", "", 0, 0, "", "")
	}

	// 推送收款通知到通知中心（仅真实收款人）
	noticeDesc := fmt.Sprintf("%s向你转账了 %d 个 token", fromName, req.Amount)
	PushToUser(req.RealToUserID, "system_notice", map[string]interface{}{
		"title":     "转账收款",
		"desc":      noticeDesc,
		"tag":       "转账",
		"kind":      "transfer",
		"from_name": fromName,
		"to_name":   toName,
		"amount":    req.Amount,
	})
	saveSystemNotice(req.RealToUserID, "转账收款", noticeDesc, "转账", 0)

	writeJSON(w, 200, "转账成功", map[string]interface{}{
		"balance":    getEffectiveTokenBalance(fromUserID),
		"message_id": msgID,
		"amount":     req.Amount,
	})
}

// ==================== 上传聊天媒体 ====================

func handleUploadChatMedia(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	// 限制 100MB
	if r.ContentLength > 100*1024*1024 {
		writeJSON(w, 400, "文件过大，最大100MB", nil)
		return
	}
	// 读取 multipart 文件
	r.Body = http.MaxBytesReader(w, r.Body, 100*1024*1024)
	file, header, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, "无法读取文件: "+err.Error(), nil)
		return
	}
	defer file.Close()

	// 读取文件内容到内存（10MB 上限已提前限制，内存安全）
	fileBytes, err := io.ReadAll(file)
	if err != nil {
		writeJSON(w, 500, "读取文件失败", nil)
		return
	}

	// ── 检测真实文件类型（优先级：Content-Type 头 >  magic bytes > 文件名后缀） ──
	// 从 multipart header 中获取客户端声明的 Content-Type（最可靠）
	partContentType := header.Header.Get("Content-Type")
	// 用 magic bytes 检测真实类型（防客户端伪造/未设 Content-Type）
	magicType := http.DetectContentType(fileBytes[:min(len(fileBytes), 512)])

	ext := ".jpg"
	mediaType := "jpg"
	lower := strings.ToLower(header.Filename)
	switch {
	case strings.HasPrefix(partContentType, "video/") || strings.HasPrefix(magicType, "video/"):
		ext = ".mp4"
		mediaType = "mp4"
	case strings.HasPrefix(partContentType, "image/gif") || strings.HasPrefix(magicType, "image/gif"):
		ext = ".gif"
		mediaType = "gif"
	case strings.HasPrefix(partContentType, "image/png") || strings.HasPrefix(magicType, "image/png"):
		ext = ".png"
		mediaType = "png"
	case strings.HasPrefix(partContentType, "image/") || strings.HasPrefix(magicType, "image/"):
		// 其余图片（jpeg/webp/bmp 等）：保持图片类型，扩展名按实际文件名
		if strings.HasSuffix(lower, ".png") {
			ext, mediaType = ".png", "png"
		} else if strings.HasSuffix(lower, ".gif") {
			ext, mediaType = ".gif", "gif"
		} else {
			ext, mediaType = ".jpg", "jpg"
		}
	default:
		// 内容未识别为图片/视频：先按文件名后缀兜底识别常见媒体，避免误判
		switch {
		case strings.HasSuffix(lower, ".png"):
			ext, mediaType = ".png", "png"
		case strings.HasSuffix(lower, ".gif"):
			ext, mediaType = ".gif", "gif"
		case strings.HasSuffix(lower, ".jpg") || strings.HasSuffix(lower, ".jpeg"):
			ext, mediaType = ".jpg", "jpg"
		case strings.HasSuffix(lower, ".mp4"):
			ext, mediaType = ".mp4", "mp4"
		default:
			// 非图片非视频 → 普通文件：media_type="file"，保留真实扩展名（APK→.apk、TXT→.txt…）
			mediaType = "file"
			ext = filepath.Ext(lower)
			if ext == "" || ext == "." {
				ext = ".bin"
			}
		}
	}
	filename := fmt.Sprintf("%d_%d_%s%s", time.Now().Unix(), userID, randomString(8), ext)

	// 获取保存目录
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	saveDir := filepath.Join(baseDir, "data", "chat_media")
	os.MkdirAll(saveDir, 0755)

	savePath := filepath.Join(saveDir, filename)
	if err := os.WriteFile(savePath, fileBytes, 0644); err != nil {
		writeJSON(w, 500, "保存失败", nil)
		return
	}

	// 预生成压缩预览图（最长边 1080、质量 85），让查看者无需等待首次生成。
	// 仅处理静态图片：GIF 重编码会丢失动画，保持原样。
	if mediaType == "jpg" || mediaType == "png" {
		go func() {
			_ = generateChatThumb(
				savePath,
				chatThumbCachePath(savePath, chatThumbLongSide, chatThumbQuality),
				chatThumbLongSide,
				chatThumbQuality,
			)
		}()
	}

	// 构建相对 URL：由客户端根据自身可达的 serverUrl 拼接完整地址。
	// 不能用 r.Host —— 服务器若在反向代理(nginx 等)后，r.Host 可能是
	// localhost:8080 等内网地址，手机端无法访问，导致图片一直加载中转圈/空气泡。
	mediaURL := fmt.Sprintf("/chat-media/%s", filename)

	log.Printf("[聊天媒体] 用户 %d 上传: %s (%d bytes, type=%s)", userID, filename, len(fileBytes), mediaType)
	writeJSON(w, 200, "上传成功", map[string]string{"url": mediaURL, "media_type": mediaType})
}

// POST /api/messages/delete-chat-media — 删除聊天媒体文件（AI 生成图清理等）。
// body: {"name":"<文件名>"}。仅允许纯文件名，防路径穿越；同时删除压缩预览缓存。
func handleDeleteChatMedia(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil {
		writeJSON(w, 400, "请求体格式错误", nil)
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		writeJSON(w, 400, "缺少文件名", nil)
		return
	}
	// 防路径穿越：只允许纯文件名
	if strings.ContainsAny(name, "/\\") || strings.Contains(name, "..") {
		writeJSON(w, 400, "非法文件名", nil)
		return
	}
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	saveDir := filepath.Join(baseDir, "data", "chat_media")
	delPath := filepath.Join(saveDir, name)
	if !strings.HasPrefix(delPath, saveDir+string(os.PathSeparator)) {
		writeJSON(w, 400, "非法文件名", nil)
		return
	}
	// 同时删除压缩预览缓存（best-effort）
	_ = os.Remove(chatThumbCachePath(delPath, chatThumbLongSide, chatThumbQuality))
	err := os.Remove(delPath)
	if err != nil {
		if os.IsNotExist(err) {
			writeJSON(w, 200, "文件不存在", map[string]interface{}{"deleted": false})
			return
		}
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	log.Printf("[聊天媒体] 删除文件: %s", name)
	writeJSON(w, 200, "删除成功", map[string]interface{}{"deleted": true})
}

// POST /api/messages/poke — 拍了拍
func handlePoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	fromUserID := getCurrentUserID(r)
	if fromUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		ToUserID int64 `json:"to_user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.ToUserID == 0 {
		writeJSON(w, 400, "缺少目标用户ID", nil)
		return
	}

	// 保存系统通知消息（flashDuration=-1 作为拍拍标记）
	msgID, err := saveMessage(fromUserID, req.ToUserID, "POKE", 0, "", "", -1, "", "")
	if err != nil {
		log.Printf("[拍拍] 保存失败: %v", err)
		writeJSON(w, 500, "发送失败", nil)
		return
	}

	// 获取发送者名称
	sender, _ := findUserByID(fromUserID)
	senderName := ""
	if sender != nil {
		senderName = sender.Username
	}
	// 获取接收者名称
	receiver, _ := findUserByID(req.ToUserID)
	receiverName := ""
	if receiver != nil {
		receiverName = receiver.Username
	}

	// 推送给接收方
	if req.ToUserID > 0 {
		PushToUser(req.ToUserID, "new_message", map[string]interface{}{
			"message_id":     msgID,
			"from_user_id":   fromUserID,
			"to_user_id":     req.ToUserID,
			"content":        "POKE",
			"sender_name":    senderName,
			"target_name":    receiverName,
			"created_at":     time.Now().Unix(),
			"flash_duration": -1,
		})
	}
	// 群聊场景：推送给群内所有在线成员（排除发送者自己）
	if req.ToUserID < 0 {
		PushToGroup(req.ToUserID, msgID, fromUserID, senderName, req.ToUserID, "POKE",
			time.Now().Unix(), "", "", -1, 0, "", "")
	}
	// 推送给发送者自己（让他看到"你拍了拍XX"）
	PushToUser(fromUserID, "new_message", map[string]interface{}{
		"message_id":     msgID,
		"from_user_id":   fromUserID,
		"to_user_id":     req.ToUserID,
		"content":        "POKE",
		"sender_name":    senderName,
		"target_name":    receiverName,
		"created_at":     time.Now().Unix(),
		"flash_duration": -1,
	})

	log.Printf("[拍拍] %s 拍了 %s", senderName, receiverName)
	writeJSON(w, 200, "发送成功", map[string]int64{"message_id": msgID})
}

func handleRecallMessage(w http.ResponseWriter, r *http.Request) {
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
	msg, err := getMessageByID(req.MessageID)
	if err != nil || msg == nil {
		writeJSON(w, 404, "消息不存在", nil)
		return
	}
	// 判断是否为开发者（开发者可撤回任何人的消息，无时间限制）
	isDeveloper := isDeveloperUser(userID)

	if msg.FromUserID != userID && !isDeveloper {
		writeJSON(w, 403, "只能撤回自己的消息", nil)
		return
	}
	if time.Now().Unix()-msg.CreatedAt > 120 && !isDeveloper {
		writeJSON(w, 400, "消息已超过2分钟，无法撤回", nil)
		return
	}
	if msg.IsRevoked == 1 {
		writeJSON(w, 400, "消息已被撤回", nil)
		return
	}
	if isDeveloper && msg.FromUserID != userID {
		if err := recallMessageAsAdmin(req.MessageID); err != nil {
			writeJSON(w, 500, "撤回失败", nil)
			return
		}
	} else {
		if err := recallMessage(req.MessageID, userID); err != nil {
			writeJSON(w, 500, "撤回失败", nil)
			return
		}
	}
	// TCP 推送撤回通知
	// 开发者撤回他人消息时，senderName 应为原消息发送者，而非开发者
	var senderName string
	if isDeveloper && msg.FromUserID != userID {
		originalSender, _ := findUserByID(msg.FromUserID)
		if originalSender != nil {
			senderName = originalSender.Username
		}
	} else {
		sender, _ := findUserByID(userID)
		if sender != nil {
			senderName = sender.Username
		}
	}
	if msg.ToUserID > 0 {
		// 私聊：推送给对方
		otherID := msg.FromUserID
		if otherID == userID {
			otherID = msg.ToUserID
		}
		PushToUser(otherID, "message_recalled", map[string]interface{}{
			"message_id":  req.MessageID,
			"sender_name": senderName,
		})
	} else if msg.ToUserID < 0 {
		// 群聊：推送给所有在线群成员（排除撤回者）
		PushRecallToGroup(msg.ToUserID, req.MessageID, userID, senderName)
	}
	writeJSON(w, 200, "撤回成功", nil)
}

// handleDevDeleteConversation 开发者测试接口：删除指定会话在服务端的所有消息，
// 并推送 conversation_deleted 事件，用于验证「服务器可删除本地数据」链路。
// 仅影响 (from_user_id,to_user_id) 双向匹配的消息；非破坏性演示，可随时重新发送。
func handleDevDeleteConversation(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FriendID == 0 {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	res, err := db.Exec("DELETE FROM messages WHERE (from_user_id=? AND to_user_id=?) OR (from_user_id=? AND to_user_id=?)", userID, req.FriendID, req.FriendID, userID)
	if err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	n := 0
	if c, e := res.RowsAffected(); e == nil {
		n = int(c)
	}
	PushToUser(userID, "conversation_deleted", map[string]interface{}{"friend_id": req.FriendID})
	if req.FriendID != userID {
		PushToUser(req.FriendID, "conversation_deleted", map[string]interface{}{"friend_id": req.FriendID})
	}
	writeJSON(w, 200, "已删除会话消息", map[string]int{"count": n})
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
	// 先标记当前用户已读，确保计数包含自己
	markMessageRead(msgID, userID)

	info := map[string]interface{}{
		"message_id":    msg.ID,
		"from_user_id":  msg.FromUserID,
		"to_user_id":    msg.ToUserID,
		"content":       msg.Content,
		"created_at":    msg.CreatedAt,
		"is_revoked":    msg.IsRevoked,
		"revoked_at":    msg.RevokedAt,
		"media_type":    msg.MediaType,
		"media_url":     msg.MediaURL,
		"flash_duration": msg.FlashDuration,
	}
	if msg.ToUserID < 0 {
		// 群聊：已读人数（已包含当前用户）
		readCount, _ := getMessageReadCount(msgID)
		info["read_count"] = readCount
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
	if err := markMessageRead(req.MessageID, userID); err != nil {
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
					"message_id": req.MessageID,
					"reader_id":  userID,
				})
			}
		}
		// 群聊：已读人数在下次查 info 时自动更新，不需要单独推送
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
	for _, msgID := range req.MessageIDs {
		if _, err := stmt.Exec(msgID, userID, now); err != nil {
			log.Printf("[批量标记已读失败] msgID=%d userID=%d err=%v", msgID, userID, err)
			failed++
		}
	}

	if err := tx.Commit(); err != nil {
		log.Printf("[批量标记已读失败] userID=%d 提交事务失败: %v", userID, err)
		writeJSON(w, 500, "批量标记已读失败: "+err.Error(), nil)
		return
	}

	// TCP 推送已读通知（仅针对最新一条私聊消息推送，避免大量并发推送）
	if len(req.MessageIDs) > 0 {
		lastMsgID := req.MessageIDs[len(req.MessageIDs)-1]
		msg, err := getMessageByID(lastMsgID)
		if err == nil && msg != nil && msg.ToUserID > 0 {
			senderID := msg.FromUserID
			if senderID != userID {
				PushToUser(senderID, "message_read", map[string]interface{}{
					"message_id": lastMsgID,
					"reader_id":  userID,
				})
			}
		}
	}

	writeJSON(w, 200, "批量标记成功", map[string]interface{}{
		"total":  len(req.MessageIDs),
		"failed": failed,
	})
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
	var afterID int64
	if l := r.URL.Query().Get("limit"); l != "" {
		limit, _ = strconv.Atoi(l)
	}
	if o := r.URL.Query().Get("offset"); o != "" {
		offset, _ = strconv.Atoi(o)
	}
	if a := r.URL.Query().Get("after_id"); a != "" {
		afterID, _ = strconv.ParseInt(a, 10, 64)
	}

	log.Printf("[消息] 获取: uid1=%d uid2=%d limit=%d offset=%d afterID=%d", userID1, userID2, limit, offset, afterID)
	msgs, err := getMessages(userID1, userID2, limit, offset, afterID)
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
		db.QueryRow(
			"SELECT COALESCE(u.username, ''), m.content, m.created_at FROM messages m LEFT JOIN users u ON m.from_user_id = u.id WHERE (m.from_user_id = ? OR m.to_user_id = ?) ORDER BY m.created_at DESC LIMIT 1",
			convID, convID,
		).Scan(&senderName, &lastMsg, &lastTime)
		if lastMsg != "" {
			// 服务端静态加密：预览读取时解密（enc:v1: 解回明文，与对话内 getMessages 一致）
			lastMsg = decryptContentLoad(lastMsg)
		}
		if senderName != "" && lastMsg != "" {
			lastMsg = senderName + ": " + lastMsg
		}

		seen[convID] = true
		result = append(result, ConversationUser{
			User: User{
				ID:        convID,
				Username:  officialGroupName,
				Email:     fmt.Sprintf("group_%d@aurora.chat", officialGroupID),
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
			"SELECT m.to_user_id, COALESCE(u.username, ''), m.content, m.created_at FROM messages m LEFT JOIN users u ON m.from_user_id = u.id INNER JOIN (SELECT to_user_id, MAX(created_at) AS max_ct FROM messages WHERE to_user_id IN (%s) GROUP BY to_user_id) latest ON m.to_user_id = latest.to_user_id AND m.created_at = latest.max_ct",
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
				gRows.Scan(&convID, &senderName, &msg, &tm)
				if msg != "" {
					// 服务端静态加密：预览读取时解密（enc:v1: 解回明文，与对话内 getMessages 一致）
					msg = decryptContentLoad(msg)
				}
				if senderName != "" && msg != "" {
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
					ID:        groupID,
					Username:  g.Name,
					Email:     fmt.Sprintf("group_%d@aurora.chat", g.ID),
				},
				LastMessage: groupLastMsg[groupID],
				LastTime:    groupLastTime[groupID],
			})
		}
	}

	// 2. 最近聊天的好友（群聊预览显示明文，私聊因E2EE加密服务器无法解密故留空）
	for _, u := range recent {
		// 过滤已删除的好友：如果对方是正ID（私聊用户），检查是否还是好友
		if u.ID > 0 {
			if isFriend, _ := areFriends(currentUserID, u.ID); !isFriend {
				continue
			}
		}
		seen[u.ID] = true
		// 私聊消息是 E2EE 加密的密文，服务器无法解密，预览留空
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

// ==================== 每好友端到端加密开关 ====================

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
