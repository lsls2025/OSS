package main

import (
	"encoding/json"
	"fmt"
	"golang.org/x/crypto/bcrypt"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// POST /api/user/signature — 更新用户个性签名
func handleUpdateSignature(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未授权", nil)
		return
	}
	var body struct {
		Signature string `json:"signature"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if len(body.Signature) > 200 {
		writeJSON(w, 400, "签名过长（最多200字符）", nil)
		return
	}
	now := time.Now().Unix()
	_, err := db.Exec("UPDATE users SET signature = ?, updated_at = ? WHERE id = ?", body.Signature, now, userID)
	if err != nil {
		writeJSON(w, 500, "更新失败", nil)
		return
	}
	writeJSON(w, 200, "更新成功", nil)
}

// POST /api/user/stats — 同步用户在线时间和发言次数
func handleSyncUserStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未授权", nil)
		return
	}
	var body struct {
		OnlineTimeSeconds int64 `json:"online_time_seconds"`
		WordCount         int64 `json:"word_count"`
	}
	limitJSONBody(w, r, maxAuthBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	// 这两个值由客户端自报，必须夹紧：单次同步不可能超过一天在线时长，
	// 也不可能有 10 万字发言，否则排行榜/统计可被任意刷高。
	if body.OnlineTimeSeconds < 0 {
		body.OnlineTimeSeconds = 0
	}
	if body.OnlineTimeSeconds > 86400 {
		body.OnlineTimeSeconds = 86400
	}
	if body.WordCount < 0 {
		body.WordCount = 0
	}
	if body.WordCount > 100000 {
		body.WordCount = 100000
	}
	if err := updateUserStats(userID, body.OnlineTimeSeconds, body.WordCount); err != nil {
		log.Printf("[sync] 更新用户统计失败: userID=%d, err=%v", userID, err)
		writeJSON(w, 500, "同步失败", nil)
		return
	}
	writeJSON(w, 200, "同步成功", nil)
}

// GET/POST /api/user/push-config — 读取/保存当前用户的邮箱推送配置
func handleUserPushConfig(w http.ResponseWriter, r *http.Request) {
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未授权", nil)
		return
	}
	switch r.Method {
	case "GET":
		cfg, err := getUserPushConfig(userID)
		if err != nil {
			log.Printf("[推送配置] 读取失败: userID=%d, err=%v", userID, err)
			writeJSON(w, 500, "读取失败", nil)
			return
		}
		if cfg == nil {
			cfg = &UserPushConfig{UserID: userID, Method: 2, TemplateMode: 1}
		}
		writeJSON(w, 200, "获取成功", cfg)
	case "POST":
		var req struct {
			Method        int     `json:"method"`
			TemplateMode  int     `json:"template_mode"`
			OwnEmail      string  `json:"own_email"`
			OwnAuth       string  `json:"own_auth"`
			DndIDs        []int64 `json:"dnd_ids"`
			NotifyEnabled int     `json:"notify_enabled"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, 400, "请求格式错误", nil)
			return
		}
		if req.Method != 1 && req.Method != 2 {
			req.Method = 2
		}
		if req.TemplateMode != 1 && req.TemplateMode != 2 {
			req.TemplateMode = 1
		}
		if req.NotifyEnabled != 0 && req.NotifyEnabled != 1 {
			req.NotifyEnabled = 1 // 非法值回退为开启
		}
		dndJSON := "[]"
		if req.DndIDs != nil && len(req.DndIDs) > 0 {
			if b, err := json.Marshal(req.DndIDs); err == nil {
				dndJSON = string(b)
			}
		}
		cfg := &UserPushConfig{
			UserID:        userID,
			Method:        req.Method,
			TemplateMode:  req.TemplateMode,
			OwnEmail:      req.OwnEmail,
			OwnAuth:       req.OwnAuth,
			DndIDs:        dndJSON,
			NotifyEnabled: req.NotifyEnabled,
		}
		if err := saveUserPushConfig(cfg); err != nil {
			log.Printf("[推送配置] 保存失败: userID=%d, err=%v", userID, err)
			writeJSON(w, 500, "保存失败", nil)
			return
		}
		writeJSON(w, 200, "保存成功", nil)
	default:
		writeJSON(w, 405, "仅支持 GET/POST", nil)
	}
}

// GET /api/user/{userId} — 获取用户基本信息（公开）
func handleGetUserByID(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	// 排除 /api/user/privacy 子路径
	path := strings.TrimPrefix(r.URL.Path, "/api/user/")
	if path == "privacy" || path == "" {
		http.NotFound(w, r)
		return
	}

	// GET /api/user/check-exists?userId=xxx — 安全验证：该用户是否存在于数据库中
	if path == "check-exists" {
		// 防批量枚举「某个 ID 是否存在」：单账号 1 分钟 60 次，正常使用绰绰有余
		if ok, wait := checkSlidingLimit(fmt.Sprintf("check-exists:%d", getCurrentUserID(r)), time.Minute, 60); !ok {
			writeJSON(w, 429, fmt.Sprintf("操作过于频繁，请 %d 秒后再试", wait), nil)
			return
		}
		checkUserIDStr := r.URL.Query().Get("userId")
		if checkUserIDStr == "" {
			writeJSON(w, 400, "缺少 userId 参数", nil)
			return
		}
		checkUserID, err := strconv.ParseInt(checkUserIDStr, 10, 64)
		if err != nil {
			writeJSON(w, 400, "userId 格式错误", nil)
			return
		}
		user, err := findUserByID(checkUserID)
		if err != nil {
			writeJSON(w, 500, "服务器错误", nil)
			return
		}
		writeJSON(w, 200, "ok", map[string]bool{"exists": user != nil})
		return
	}

	userID, err := strconv.ParseInt(path, 10, 64)
	if err != nil {
		writeJSON(w, 400, "用户ID格式错误", nil)
		return
	}
	// 防遍历 ID 批量抓资料：邮箱/QQ 已脱敏，这里再给一道频率闸门。
	// 120 次/分对正常使用（资料页、私聊资料卡）远远够用，但挡住全库枚举。
	if ok, wait := checkSlidingLimit(fmt.Sprintf("user-profile:%d", getCurrentUserID(r)), time.Minute, 120); !ok {
		writeJSON(w, 429, fmt.Sprintf("操作过于频繁，请 %d 秒后再试", wait), nil)
		return
	}
	user, err := findUserByID(userID)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if user == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	// ============ 隐私脱敏：他人查看时隐藏 email/qq/注册IP ============
	currentID := getCurrentUserID(r)
	isSelf := currentID == user.ID
	isDev := isDeveloperUser(currentID)

	email := user.Email
	qqNum := user.QQNumber
	regIP := user.RegIp
	tokenVersion := user.TokenVersion
	if !isSelf && !isDev {
		// 邮箱一律脱敏。原来只在用户主动打开 hide_email 时才遮，而该字段默认值为 0，
		// 等于全站用户的完整邮箱对任何登录账号可见——遍历 /api/user/{id} 就能拖走全库邮箱。
		email = maskEmail(email)
		// QQ 同理：原来这里只判 hide_qq，而它默认是 0，等于陌生人也能从资料页拿到完整 QQ。
		// 更糟的是搜索页把 QQ 打了码，点「查看」进来却是明文，那层保护等于没有。
		// 现在统一走 qqVisibleTo：陌生人一律打码，好友仍可见。
		isFriend, ferr := areFriends(currentID, user.ID)
		if ferr != nil {
			isFriend = false // 查询失败按不可见处理
		}
		if !qqVisibleTo(currentID, user.ID, user.HideQQ, isDev, isFriend) {
			qqNum = maskQQ(qqNum)
		}
		regIP = ""
		// token_version 属账号内部状态（被踢次数），不对外暴露
		tokenVersion = 0
	}

	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"id":                    user.ID,
		"email":                 email,
		"username":              user.Username,
		"qq_number":             qqNum,
		"hide_qq":               user.HideQQ,
		"created_at":            user.CreatedAt,
		"updated_at":            user.UpdatedAt,
		"hide_email":            user.HideEmail,
		"require_group_consent": user.RequireGroupConsent,
		"signature":             user.Signature,
		"online_time_seconds":   user.OnlineTimeSeconds,
		"word_count":            user.WordCount,
		"token_version":         tokenVersion,
		"email_verified":        user.EmailVerified != 0,
		"reg_ip":                regIP,
		"token_balance":         user.TokenBalance,
	})
}

// ==================== 二维码（扫码加好友） ====================
//
// 二维码内容包含两个独立信息：
//  1. 官网落地页 https://example.com/q.html —— APP 外扫码（QQ/微信/相机/浏览器）
//     打开落地页，页面展示被扫码者的头像/昵称，并可引导去官网下载或在 App 内扫码加好友；
//  2. 用户 ID（一个纯数字）—— 通过 URL 的 query 参数 ?u=<id> 携带，供落地页 / App 内扫码解析。
//
// 格式：<官网>/q.html?u=<用户ID>
// 群二维码：<www>/api/qr/g?g=<群ID>（落地页由后端渲染，见 qr_landing.go）。
// 历史格式 <官网>/g.html?g=<群ID> 是错的 —— 那个文件从来没存在过，扫码必然 404，
// 现在只保留 /g.html → 新落地页的 nginx 302 兼容（见 deploy/nginx-qr-landing.conf）。
// 落地页仅展示公开的无害信息；真正的加好友动作必须由已登录的 APP 内扫码触发
// （调 /api/user/qrcode/resolve），外部扫码只能看到落地页，天然安全。

// 官网静态站点（nginx，443 裸域）。注意：不能用 getServerBaseURL()（后端 5004 端口），
// 否则扫码会打开 5004 后端地址的 /q.html 而 404。
const qrSiteURL = "https://example.com"

// GET /api/qr/group?gid=<群ID> — 群二维码落地页公开信息接口（无需登录）
// 仅返回群的公开信息（群名/群号/签名/成员数/头像），供落地页/客户端展示。
// 页面本身由 GET /api/qr/g?g=<群ID> 服务端直出（qr_landing.go），外部扫码不依赖 JS。
// 加群仍然只能由已登录的 APP 内扫码调用 /api/user/qrcode/resolve 触发，
// 外部扫码只能看到落地页，天然安全（与 /api/qr/user 同一安全模型）。
func handlePublicQrGroup(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	gidStr := r.URL.Query().Get("gid")
	gid, err := strconv.ParseInt(gidStr, 10, 64)
	if err != nil || gid <= 0 {
		writeJSON(w, 400, "无效的 gid 参数", nil)
		return
	}
	// 限流：过去这里是 3 秒一次（checkQrPublicRateLimit），对落地页来说太紧 ——
	// 打开页面会请求一次，用户手滑再扫一次、或浏览器重发，第二次就吃 429，
	// 而落地页拿到 429 会当成"读取失败"。这个接口只回公开信息，放宽到每分钟 120 次足够挡脚本。
	if ok, _ := checkSlidingLimit("qrgroup:"+getClientIP(r), time.Minute, 120); !ok {
		writeJSON(w, 429, "请求过于频繁，请稍后再试", nil)
		return
	}
	group, err := getGroupByID(gid)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if group == nil {
		writeJSON(w, 404, "群聊不存在或已被解散", nil)
		return
	}
	var memberCount int64
	db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ?", gid).Scan(&memberCount)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"id":           group.ID,
		"group_name":   group.Name,
		"display_id":   group.DisplayID,
		"signature":    group.Signature,
		"member_count": memberCount,
		"avatar_url":   fmt.Sprintf("/api/groups/avatar/%d", group.ID),
		// avatar 是公开头像接口的相对地址（没有头像时为空串），落地页直接当 <img src> 用。
		// avatar_url 走 /api/groups/avatar/{id}，需要登录，匿名扫码者拿到只有 401 破图，别用。
		"avatar": groupPublicAvatarURL(group.ID),
	})
}

// GET /api/user/qrcode — 获取当前用户的二维码内容
func handleGetMyQrCode(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	// 落地页 + ?u=用户ID，两个信息分离；落地页走官网裸域（而非后端 5004 端口）
	content := fmt.Sprintf("%s/q.html?u=%d", qrSiteURL, userID)
	writeJSON(w, 200, "ok", map[string]string{"content": content})
}

// POST /api/user/qrcode/resolve — 解析二维码内容，返回目标用户基本信息
// 仅 APP 内扫码调用（需已登录）。从 # 片段提取用户 ID 返回加好友所需信息。
func handleResolveQrCode(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	var req struct {
		Content string `json:"content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Content == "" {
		writeJSON(w, 400, "缺少 content 参数", nil)
		return
	}

	currentID := getCurrentUserID(r)
	if currentID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	// 解析出目标用户 ID
	var targetID int64
	// 群二维码解析结果：仅当二维码内容指向群时非 nil
	var targetGroupID int64
	content := strings.TrimSpace(req.Content)
	switch {
	case strings.HasPrefix(content, "http://") || strings.HasPrefix(content, "https://"):
		// 官网落地页：/q.html?u=<用户ID>（好友二维码）或 /api/qr/g?g=<群ID>（群二维码，含历史 /g.html）
		// 两者都是 URL：query 里带 u= 走好友，带 g= 走群，按参数分流（不看路径，故老码也能解析）
		u, err := url.Parse(content)
		if err != nil {
			writeJSON(w, 400, "无效的二维码格式", nil)
			return
		}
		// 群二维码优先：?g= 参数 → 群
		if qv := u.Query().Get("g"); qv != "" {
			gid, _ := strconv.ParseInt(qv, 10, 64)
			if gid <= 0 {
				writeJSON(w, 400, "群ID格式错误", nil)
				return
			}
			targetGroupID = gid
		} else {
			var id int64
			if qv := u.Query().Get("u"); qv != "" {
				id, _ = strconv.ParseInt(qv, 10, 64)
			} else {
				// 兼容历史 # 片段格式：http://.../#<用户ID>
				id, _ = strconv.ParseInt(strings.TrimPrefix(u.Fragment, "#"), 10, 64)
			}
			if id <= 0 {
				writeJSON(w, 400, "用户ID格式错误", nil)
				return
			}
			targetID = id
		}
	case strings.HasPrefix(content, "aurora://group?id="):
		// 群二维码兼容格式：aurora://group?id=xxx
		idStr := strings.TrimPrefix(content, "aurora://group?id=")
		id, err := strconv.ParseInt(idStr, 10, 64)
		if err != nil || id <= 0 {
			writeJSON(w, 400, "群ID格式错误", nil)
			return
		}
		targetGroupID = id
	case strings.HasPrefix(content, "aurora://user?id="):
		// 兼容旧版 aurora://user?id=xxx（历史已生成的二维码）
		idStr := strings.TrimPrefix(content, "aurora://user?id=")
		id, err := strconv.ParseInt(idStr, 10, 64)
		if err != nil || id <= 0 {
			writeJSON(w, 400, "用户ID格式错误", nil)
			return
		}
		targetID = id
	default:
		writeJSON(w, 400, "无效的二维码格式", nil)
		return
	}

	// 群二维码分流：返回群信息供扫码端展示/加群
	if targetGroupID > 0 {
		group, err := getGroupByID(targetGroupID)
		if err != nil {
			writeJSON(w, 500, "服务器错误", nil)
			return
		}
		if group == nil {
			writeJSON(w, 404, "群聊不存在或已被解散", nil)
			return
		}
		var memberCount int64
		db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ?", targetGroupID).Scan(&memberCount)
		var isMember bool
		db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ? AND user_id = ?", targetGroupID, currentID).Scan(&isMember)
		writeJSON(w, 200, "ok", map[string]interface{}{
			"is_group":     true,
			"group_id":     group.ID,
			"group_name":   group.Name,
			"display_id":   group.DisplayID,
			"signature":    group.Signature,
			"member_count": memberCount,
			"is_member":    isMember,
			"message":      "群二维码",
		})
		return
	}

	// 防批量扫描：同一用户 2 秒内最多解析 1 次
	if allowed, wait := checkQrResolveRateLimit(currentID); !allowed {
		writeJSON(w, 429, fmt.Sprintf("操作过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	// 查询目标用户
	user, err := findUserByID(targetID)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if user == nil || isAccountDeleted(user) {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	isSelf := currentID == targetID
	if isSelf {
		writeJSON(w, 200, "ok", map[string]interface{}{
			"is_self":    true,
			"id":         user.ID,
			"username":   user.Username,
			"signature":  user.Signature,
			"avatar_url": fmt.Sprintf("/api/avatar/%d", user.ID),
			"message":    "这是你自己的二维码",
		})
		return
	}

	avatarUrl := fmt.Sprintf("/api/avatar/%d", targetID)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"is_self":    false,
		"id":         user.ID,
		"username":   user.Username,
		"signature":  user.Signature,
		"avatar_url": avatarUrl,
	})
}

// ==================== 获取在线用户（开发者管理） ====================

func handleGetOnlineUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	// 开发者或拥有 users.view_profile 权限的管理员可查看
	if !checkDevOrPerm(r, "users.view_profile") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	onlineIDs := GetOnlineUserIDs()

	// 查询在线用户的详细信息
	type OnlineUser struct {
		ID        int64  `json:"id"`
		Email     string `json:"email"`
		Username  string `json:"username"`
		CreatedAt int64  `json:"created_at"`
		UpdatedAt int64  `json:"updated_at"`
	}

	users := make([]OnlineUser, 0, len(onlineIDs))
	for _, id := range onlineIDs {
		u, err := findUserByID(id)
		if err != nil || u == nil {
			continue
		}
		users = append(users, OnlineUser{
			ID:        u.ID,
			Email:     u.Email,
			Username:  u.Username,
			CreatedAt: u.CreatedAt,
			UpdatedAt: u.UpdatedAt,
		})
	}

	writeJSON(w, 200, "获取成功", users)
}

// ==================== 管理员操作：删除用户账号 ====================

func handleAdminDeleteUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentEmail := getCurrentEmailFromHeader(r)
	if !checkDevOrPerm(r, "users.delete") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		UserID     int64 `json:"user_id"`
		BlockEmail bool  `json:"block_email"`
		BlockIP    bool  `json:"block_ip"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 user_id", nil)
		return
	}

	// 禁止删除开发者自身
	currentUserID := getCurrentUserID(r)
	if req.UserID == currentUserID {
		writeJSON(w, 403, "无法删除自己的账号", nil)
		return
	}

	// 查找目标用户
	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	// 通过 TCP 推送删除通知给目标用户（含清空本地数据指令）
	PushToUser(req.UserID, "account_deleted", map[string]interface{}{
		"message":    "您的账号已被管理员删除",
		"clear_data": true,
	})

	// 清除封禁/禁言记录
	RemoveBanRecord(req.UserID)

	// 清理数据库
	if err := deleteUserAllData(req.UserID); err != nil {
		log.Printf("[Admin] 删除用户 %d (%s) 失败: %v", req.UserID, targetUser.Email, err)
		writeJSON(w, 500, "删除失败: "+err.Error(), nil)
		return
	}

	log.Printf("[Admin] 用户 %d (%s) 已被 %s 彻底删除", req.UserID, targetUser.Email, currentEmail)

	// 如果勾选了拉黑邮箱
	if req.BlockEmail {
		if err := blockEmail(targetUser.QQNumber, currentUserID); err != nil {
			log.Printf("[Admin] 拉黑 QQ %s 失败: %v", targetUser.QQNumber, err)
		} else {
			log.Printf("[Admin] QQ %s 已被拉黑", targetUser.QQNumber)
		}
	}
	// 如果勾选了拉黑IP
	if req.BlockIP && targetUser.RegIp != "" {
		if err := blockIP(targetUser.RegIp, currentUserID); err != nil {
			log.Printf("[Admin] 拉黑 IP %s 失败: %v", targetUser.RegIp, err)
		} else {
			log.Printf("[Admin] IP %s 已被拉黑", targetUser.RegIp)
		}
	}

	writeJSON(w, 200, "已删除用户 "+targetUser.QQNumber, nil)
}

// ==================== 开发者：清空聊天记录 ====================
// POST /api/admin/clear-chat-records  body: {"scope":"private"|"group"|"all"}
// 仅开发者可调用。private=清空所有私信(1:1)，group=清空所有群聊(不含官方群)，all=两者皆清(不含官方群)。
// 官方群(会话ID=-1001)永久保留，任何范围都不会删除。
func handleAdminClearChatRecords(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		Scope string `json:"scope"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	switch req.Scope {
	case "private", "group", "all":
	default:
		writeJSON(w, 400, "scope 必须为 private/group/all", nil)
		return
	}

	// 官方群永久保留：按 display_id 反查真实会话 ID，不硬编码 -1001
	officialConv := officialGroupConvID()
	var condSQL string
	switch req.Scope {
	case "private":
		condSQL = "to_user_id >= 0 AND from_user_id >= 0"
	case "group":
		condSQL = fmt.Sprintf("to_user_id < 0 AND to_user_id != %d AND from_user_id != %d", officialConv, officialConv)
	default:
		condSQL = fmt.Sprintf("to_user_id != %d AND from_user_id != %d", officialConv, officialConv)
	}

	// 统计删除前数量（所有范围都排除官方群，绝不触碰）
	var before int64
	_ = db.QueryRow("SELECT COUNT(*) FROM messages WHERE " + condSQL).Scan(&before)

	// 删除消息（官方群永久保留，任何范围都不删除）
	res, err := db.Exec("DELETE FROM messages WHERE " + condSQL)
	if err != nil {
		log.Printf("[Admin] 清空聊天记录失败(%s): %v", req.Scope, err)
		writeJSON(w, 500, "清空失败: "+err.Error(), nil)
		return
	}
	deleted, _ := res.RowsAffected()

	// 清理残留的已读记录，避免孤儿数据
	_, _ = db.Exec(`DELETE FROM message_reads WHERE message_id NOT IN (SELECT id FROM messages)`)

	log.Printf("[Admin] 开发者 %d 清空聊天记录 scope=%s 删除=%d", getCurrentUserID(r), req.Scope, deleted)
	writeJSON(w, 200, "已清空聊天记录", map[string]interface{}{
		"scope":   req.Scope,
		"deleted": deleted,
		"before":  before,
	})
}

// ==================== 管理员操作：修改用户密码（重置） ====================

func handleAdminResetUserPassword(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "users.delete") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		UserID      int64  `json:"user_id"`
		NewPassword string `json:"new_password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 || req.NewPassword == "" {
		writeJSON(w, 400, "参数错误：需要 user_id 与新密码", nil)
		return
	}
	if len([]rune(req.NewPassword)) < 8 {
		writeJSON(w, 400, "新密码不能少于 8 位", nil)
		return
	}

	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	// 与注册/重置密码完全一致的哈希算法（bcrypt），保证登录/鉴权能识别
	hashed, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		writeJSON(w, 500, "密码加密失败", nil)
		return
	}
	if err := updatePassword(req.UserID, string(hashed)); err != nil {
		log.Printf("[Admin] 重置用户 %d (%s) 密码失败: %v", req.UserID, targetUser.Email, err)
		writeJSON(w, 500, "重置密码失败", nil)
		return
	}

	log.Printf("[Admin] 用户 %d (%s) 的密码已被 %s 重置", req.UserID, targetUser.Email, getCurrentEmailFromHeader(r))
	writeJSON(w, 200, "密码已重置", nil)
}

// ==================== 管理员操作：封禁用户 ====================

func handleAdminBanUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID, ok := checkAdminPermission(r, "users.ban")
	if !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		UserID            int64  `json:"user_id"`
		Reason            string `json:"reason"`
		Duration          int64  `json:"duration"`
		UnbanPopupMessage string `json:"unban_popup_message"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 user_id", nil)
		return
	}
	if req.Duration <= 0 {
		req.Duration = 100 * 365 * 24 * 60 * 60
	}

	if req.UserID == currentUserID {
		writeJSON(w, 403, "无法封禁自己的账号", nil)
		return
	}

	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	// 保护管理员和开发者：不可被封禁
	if isDeveloperUserObj(targetUser) {
		writeJSON(w, 403, "无法封禁开发者账号", nil)
		return
	}
	isTargetAdmin, _ := isPlatformAdmin(req.UserID)
	if isTargetAdmin && !checkDevPermission(r) {
		writeJSON(w, 403, "无法封禁管理员账号，请先取消其管理员权限", nil)
		return
	}

	if err := banUser(req.UserID, req.Reason, req.Duration, currentUserID, req.UnbanPopupMessage); err != nil {
		log.Printf("[Admin] 封禁用户 %d 失败: %v", req.UserID, err)
		writeJSON(w, 500, "封禁失败", nil)
		return
	}

	// 从 DB 取回权威 expires_at（banUser 内部只算一次 time.Now()，是唯一真值），
	// AddBanRecord / PushToUser / 日志 全部复用这一个值，彻底消除任何漂移
	banRec, _ := getBanStatus(req.UserID)
	banExpiresAt := int64(0)
	if banRec != nil {
		banExpiresAt = banRec.ExpiresAt
	} else {
		banExpiresAt = time.Now().Unix() + req.Duration
	}
	AddBanRecord(req.UserID, req.Reason, banExpiresAt)

	PushToUser(req.UserID, "account_banned", map[string]interface{}{
		"reason":              req.Reason,
		"duration":            req.Duration,
		"expires_at":          banExpiresAt,
		"unban_popup_message": req.UnbanPopupMessage,
	})

	DisconnectUser(req.UserID)

	// 记录操作日志
	adminUsername := getCurrentEmailFromHeader(r)
	targetUsername := targetUser.Username
	details, _ := json.Marshal(map[string]interface{}{
		"reason":     req.Reason,
		"duration":   req.Duration,
		"expires_at": banExpiresAt,
	})
	addAdminOperationLog(currentUserID, adminUsername, "ban", req.UserID, targetUsername, string(details))

	log.Printf("[Admin] 用户 %d (%s) 已被 %s 封禁 %d 秒，原因: %s",
		req.UserID, targetUser.Email, adminUsername, req.Duration, req.Reason)
	writeJSON(w, 200, "已封禁用户 "+targetUser.Email, nil)
}

// ==================== 用户请求解封通知 ====================

func handleBanNotify(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	if err := setUnbanNotify(userID); err != nil {
		log.Printf("[封禁] 用户 %d 设置解封通知失败: %v", userID, err)
		writeJSON(w, 500, "设置解封通知失败", nil)
		return
	}
	log.Printf("[封禁] 用户 %d 已请求解封邮件通知", userID)
	writeJSON(w, 200, "解封后将发送邮件通知", nil)
}

// ==================== 用户查询自己的封禁状态（不受封禁影响） ====================

func handleCheckBanStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	ban, err := getBanStatus(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if ban != nil {
		writeJSON(w, 200, "查询成功", ban)
	} else {
		writeJSON(w, 200, "未封禁", map[string]interface{}{})
	}
}

// ==================== 用户查询自己的禁言状态（不受禁言影响） ====================

func handleCheckMuteStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	mute, err := getMuteStatus(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if mute != nil {
		writeJSON(w, 200, "查询成功", mute)
	} else {
		writeJSON(w, 200, "未禁言", map[string]interface{}{})
	}
}

// ==================== 用户查询自己是否为平台管理员 ====================

func handleCheckIsPlatformAdmin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	if checkDevPermission(r) {
		writeJSON(w, 200, "ok", map[string]bool{"is_admin": false})
		return
	}
	isAdmin, err := isPlatformAdmin(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]bool{"is_admin": isAdmin})
}

// ==================== 闪照查看记录API ====================

// POST /api/flash/view — 记录用户已查看闪照
func handleRecordFlashView(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		MessageID int64  `json:"message_id"`
		MediaURL  string `json:"media_url"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.MessageID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	// 1. 记录查看
	recordFlashView(userID, req.MessageID)
	// 1.5 发送方自己不触发销毁（前端已拦截，这里兜底）
	msg, err := getMessageByID(req.MessageID)
	if err == nil && msg != nil && msg.FromUserID == userID {
		writeJSON(w, 200, "ok", nil)
		return
	}
	// 1.6 实时通知发送方：对方已查看。发送方客户端据此立即把气泡从"对方查看中/暂未查看"切到"对方已查看"，
	// 无需退出重进对话。
	if err == nil && msg != nil && msg.FromUserID > 0 {
		PushToUser(msg.FromUserID, "flash_viewed", map[string]interface{}{"message_id": req.MessageID})
	}
	// 2. 销毁服务器端闪照资源文件
	// 安全（路径穿越修复）：删除目标以**数据库里该消息的真实 media_url** 为准
	// （上传流程写入，客户端无权篡改）；客户端自报 URL 仅在库中缺失时兜底。
	// 删除统一走 deleteChatMediaByURL——内部含「/chat-media/ 前缀 + 去 query +
	// 纯文件名(禁 .. / \\) + 目录 containment」四重过滤，与撤回删除同款实现，
	// 杜绝 /chat-media/../aurora_chat.db 式任意文件删除。
	if err == nil && msg != nil && msg.MediaURL != "" {
		deleteChatMediaByURL(msg.MediaURL)
	} else {
		deleteChatMediaByURL(req.MediaURL)
	}
	writeJSON(w, 200, "ok", nil)
}

// POST /api/flash/viewing — 通知服务端"我正在查看对方的闪照"。
// 用于给发送方实时推送"对方查看中"（查看结束会由 /api/flash/view 推送"已查看"）。
func handleFlashViewing(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		MessageID int64 `json:"message_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.MessageID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	msg, err := getMessageByID(req.MessageID)
	if err != nil || msg == nil {
		writeJSON(w, 404, "消息不存在", nil)
		return
	}
	// 仅"查看他人闪照"实时推送；发送方自己查看不推
	if msg.FromUserID != userID && msg.FromUserID > 0 {
		PushToUser(msg.FromUserID, "flash_viewing", map[string]interface{}{"message_id": req.MessageID})
	}
	writeJSON(w, 200, "ok", nil)
}

// GET /api/flash/viewed-ids — 获取用户已查看的闪照ID列表
func handleGetViewedFlashIDs(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	ids := getViewedFlashIDs(userID)
	if ids == nil {
		ids = []int64{}
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"ids": ids})
}

// POST /api/flash/partner-viewed — 查询"对方是否查看过我发的闪照"
// body: {"message_ids": [1,2,3,...]}  — 必须全是当前用户发送的闪照
// 返回: {"viewed_ids": [1,3]}        — 其中已被对方（非发送方）查看过的那些
func handleGetPartnerViewedFlash(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		MessageIDs []int64 `json:"message_ids"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || len(req.MessageIDs) == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	// 过滤出"我发的闪照"：查 messages 表确保 from_user_id = 当前用户 && flash_duration > 0
	placeholders := strings.TrimRight(strings.Repeat("?,", len(req.MessageIDs)), ",")
	args1 := make([]any, 0, len(req.MessageIDs)+1)
	for _, id := range req.MessageIDs {
		args1 = append(args1, id)
	}
	args1 = append(args1, userID)
	rows, err := db.Query(
		fmt.Sprintf("SELECT id FROM messages WHERE id IN (%s) AND from_user_id = ? AND flash_duration > 0", placeholders),
		args1...,
	)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	var mySentFlashIDs []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		mySentFlashIDs = append(mySentFlashIDs, id)
	}
	rows.Close()

	// 查这些闪照哪些被非发送方的人看过了
	var viewedIDs []int64
	if len(mySentFlashIDs) > 0 {
		p2 := strings.TrimRight(strings.Repeat("?,", len(mySentFlashIDs)), ",")
		args2 := make([]any, len(mySentFlashIDs))
		for i, id := range mySentFlashIDs {
			args2[i] = id
		}
		r2, err := db.Query(
			fmt.Sprintf(`SELECT DISTINCT fv.message_id FROM flash_views fv
				JOIN messages m ON m.id = fv.message_id
				WHERE fv.message_id IN (%s) AND fv.user_id != m.from_user_id`, p2),
			args2...,
		)
		if err == nil {
			for r2.Next() {
				var id int64
				r2.Scan(&id)
				viewedIDs = append(viewedIDs, id)
			}
			r2.Close()
		}
	}
	if viewedIDs == nil {
		viewedIDs = []int64{}
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"viewed_ids": viewedIDs})
}

func handleAdminUnbanUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID, ok := checkAdminPermission(r, "users.ban")
	if !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 user_id", nil)
		return
	}
	ban, _ := getBanStatus(req.UserID)
	popupMsg := ""
	targetUsername := ""
	if ban != nil {
		popupMsg = ban.UnbanPopupMessage
		targetUser, _ := findUserByID(req.UserID)
		if targetUser != nil {
			targetUsername = targetUser.Username
		}
	}
	if err := unbanUser(req.UserID); err != nil {
		log.Printf("[Admin] 解封用户 %d 失败: %v", req.UserID, err)
		writeJSON(w, 500, "解封失败", nil)
		return
	}
	RemoveBanRecord(req.UserID)
	PushToUser(req.UserID, "account_unbanned", map[string]interface{}{
		"message":             "您的账号已被解封",
		"unban_popup_message": popupMsg,
	})

	// 记录操作日志
	adminUsername := getCurrentEmailFromHeader(r)
	details, _ := json.Marshal(map[string]interface{}{
		"action": "unban",
	})
	addAdminOperationLog(currentUserID, adminUsername, "unban", req.UserID, targetUsername, string(details))

	log.Printf("[Admin] 用户 %d 已被 %s 解封", req.UserID, adminUsername)
	writeJSON(w, 200, "已解封用户", nil)
}

// ==================== 管理员查询用户封禁状态 ====================

func handleAdminCheckBanStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	_, ok := checkAdminPermission(r, "users.ban")
	if !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	userIDStr := r.URL.Query().Get("user_id")
	if userIDStr == "" {
		writeJSON(w, 400, "参数错误：需要 user_id", nil)
		return
	}
	var userID int64
	fmt.Sscanf(userIDStr, "%d", &userID)
	if userID <= 0 {
		writeJSON(w, 400, "参数错误：无效的 user_id", nil)
		return
	}
	ban, err := getBanStatus(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if ban != nil {
		writeJSON(w, 200, "查询成功", ban)
	} else {
		writeJSON(w, 200, "未封禁", map[string]interface{}{})
	}
}

// ==================== 管理员操作：禁言用户 ====================
