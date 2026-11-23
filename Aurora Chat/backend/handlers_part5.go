package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"golang.org/x/crypto/bcrypt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// 保存版本锁定设置
func handleAdminSaveTrialVersionSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "trial.toggle") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		MinSupportedVersion string `json:"min_supported_version"`
		ForceLockVersion    string `json:"force_lock_version"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	db.Exec("UPDATE app_settings SET setting_value = ? WHERE setting_key = 'trial_min_supported_version'", req.MinSupportedVersion)
	db.Exec("UPDATE app_settings SET setting_value = ? WHERE setting_key = 'trial_force_lock_version'", req.ForceLockVersion)
	log.Printf("[体验版] 管理员更新了版本锁定设置: 最低支持=%q, 强制锁定=%q", req.MinSupportedVersion, req.ForceLockVersion)
	writeJSON(w, 200, "版本锁定设置已保存", map[string]interface{}{
		"min_supported_version": req.MinSupportedVersion,
		"force_lock_version":    req.ForceLockVersion,
	})
}

// ==================== 发送验证码(密码重置用，不检查是否已注册) ====================

// 由于 send-code 在注册时检查是否已注册，这里新增一个发送重置验证码的时间点
// 我们在 Android 端可以通过判断模式来调用
// 但为了不改变现有 API，密码重置的验证码复用 /api/send-code 逻辑：它需要邮箱未注册
// 所以我们提供一个新的发送方式：如果注册了，也可以发
// 这里我们通过查询判断：用户存在则可以发送重置验证码
// 实际上更好的方式：/api/send-reset-code 用于重置密码
func handleSendResetCode(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		Email string `json:"email"`
	}
	limitJSONBody(w, r, maxAuthBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.Email = strings.TrimSpace(req.Email)
	if req.Email == "" || !emailRegex.MatchString(req.Email) {
		writeJSON(w, 400, "邮箱格式不正确", nil)
		return
	}

	// 检查用户是否存在
	user, err := findUserByEmail(req.Email)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if user == nil {
		// B9 加固：统一响应文案，不泄露"该邮箱是否注册"，杜绝邮箱枚举。
		log.Printf("[验证码] 邮箱未注册，统一响应不发送（防枚举）: %s", req.Email)
		writeJSON(w, 200, "验证码已发送至邮箱", nil)
		return
	}

	// 按客户端真实 IP 做 60 秒冷却与每日上限。
	// 用 getClientIP 而非 getPeerIP：nginx 反代后所有请求的对端都是 127.0.0.1，
	// 继续用 getPeerIP 会让全站用户共用一个"IP"，冷却变成全局 60 秒一次，直接打死验证码功能。
	// getClientIP 只在直连对端是可信代理时才采纳 X-Forwarded-For，且取最右一跳，同样防伪造。
	peerIP := getClientIP(r)
	if ok, wait := checkIPCodeCoolDown(peerIP); !ok {
		writeJSON(w, 429, fmt.Sprintf("发送过于频繁，请 %d 秒后再试", wait), nil)
		return
	}
	// B9 加固：邮箱/IP 每日发送上限
	if !checkCodeDailyLimit("email:", req.Email, codeDailyLimitEmail) || !checkCodeDailyLimit("ip:", peerIP, codeDailyLimitIP) {
		writeJSON(w, 429, "今日发送次数已达上限，请明天再试", nil)
		return
	}

	ok, wait := checkCodeCoolDown(req.Email)
	if !ok {
		writeJSON(w, 429, fmt.Sprintf("发送过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	code := generateCode()
	if err := saveVerificationCode(req.Email, code); err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	success, msg := sendEmailCode(req.Email, code)
	if !success {
		writeJSON(w, 500, msg, nil)
		return
	}

	recordCodeSent(req.Email)
	recordIPCodeSent(peerIP)
	recordCodeDaily("email:", req.Email)
	recordCodeDaily("ip:", peerIP)
	log.Printf("重置密码验证码已发送至邮箱: %s", req.Email)
	writeJSON(w, 200, "验证码已发送至邮箱", nil)
}

// updatePassword 更新用户密码
// 同时递增 token_version：改密/找回密码后必须让旧 JWT 立即失效，
// 否则账号被盗后即使重置了密码，攻击者手里那张 30 天有效期的 token 仍然能用。
// 递增后所有旧设备下一次请求会被中间件判为「已在其他设备登录」并强制重新登录。
func updatePassword(userID int64, hashedPassword string) error {
	_, err := db.Exec("UPDATE users SET password = ?, token_version = token_version + 1, updated_at = ? WHERE id = ?",
		hashedPassword, time.Now().Unix(), userID)
	return err
}

// ==================== 服务器注册 ====================

// ==================== 服务器登录 ====================

// ==================== 查询我的服务器 ====================

func handleGetMyServer(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	// 新用户首次进入网站托管自动创建服务器（与文件管理的 getOrCreate 口径一致），
	// 不再返回「未注册服务器」让前端显示假数据/关机状态。
	server, err := getOrCreateServerByOwner(userID)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if server == nil {
		writeJSON(w, 500, "服务器创建失败", nil)
		return
	}
	// 访问时更新活跃时间
	updateServerLastActive(server.ID)
	status := computeServerStatus(server.ID)

	writeJSON(w, 200, "查询成功", map[string]interface{}{
		"id":             server.ID,
		"owner_user_id":  server.OwnerUserID,
		"owner_username": server.OwnerName,
		"name":           server.Name,
		"domain":         server.Domain,
		"server_url":     getServerBaseURL(),
		"created_at":     server.CreatedAt,
		"status":         status,
	})
}

// POST /api/server/shutdown — 关机（清理缓存、停止服务）
func handleShutdownServer(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	server, err := findServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}
	if server.ServerStatus == "stopping" || server.ServerStatus == "stopped" {
		writeJSON(w, 400, "服务器已是关机状态", nil)
		return
	}

	// 立即生效：写库 + 同步站点服务（见 asyncShutdownServer），不留 3 秒假等待
	asyncShutdownServer(server.ID)
	log.Printf("[服务器] 关机请求: server=%s(%d), user=%d", server.Name, server.ID, userID)
	writeJSON(w, 200, "已关机", map[string]interface{}{
		"status": "stopped",
	})
}

// POST /api/server/restart — 重启（清理缓存、重新开机）
func handleRestartServer(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}
	// 只拦「关机中」；旧的 restarting 残留不该把人锁死（重启现在是立即完成的）
	if server.ServerStatus == "stopping" {
		writeJSON(w, 400, "服务器正在处理中，请稍后再试", nil)
		return
	}

	// 立即生效：写库 + 同步站点服务，站点马上恢复
	asyncRestartServer(server.ID)
	log.Printf("[服务器] 重启请求: server=%s(%d), user=%d", server.Name, server.ID, userID)
	writeJSON(w, 200, "已恢复", map[string]interface{}{
		"status": "running",
	})
}

// GET /api/server/status — 获取最新状态（前端轮询/刷新用）
func handleGetServerStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}
	// 与 getMyServer 一致，使用 computeServerStatus 计算真实状态（含 last_active_at 判断），而非直接返回原始字段
	status := computeServerStatus(server.ID)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"status": status,
	})
}

// POST /api/spam/send — 无需认证，仅 localhost 可用，用于持续发送自定义内容
func handleSpamSend(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	// B6 加固：仅允许本机回环地址（127.0.0.1/::1/localhost）调用，否则 403。
	// 前端无真实调用方，本接口已随批次7下线；此校验作为纵深防御，
	// 防止未来误挂载路由后成为公网可调的无鉴权入口。
	if !checkLoopbackOnly(r) {
		writeJSON(w, 403, "仅限本机调用", nil)
		return
	}
	var req struct {
		Content string `json:"content"`
	}
	json.NewDecoder(r.Body).Decode(&req)
	log.Printf("[SPAM] %s", req.Content)
	writeJSON(w, 200, "已收到", nil)
}

// ==================== 安全设置 API（服务端存储密码/手势验证） ====================

func handleGetSecurityStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未认证", nil)
		return
	}
	sec, err := getUserSecurity(userID)
	if err != nil {
		log.Printf("[安全] 查询安全状态失败 user=%d: %v", userID, err)
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	resp := map[string]interface{}{
		"security_enabled": 0, "has_password": false, "has_gesture": false,
	}
	if sec != nil && sec.SecurityEnabled == 1 {
		resp["security_enabled"] = 1
		if sec.PasswordHash != "" {
			resp["has_password"] = true
		}
		if sec.GesturePattern != "" {
			resp["has_gesture"] = true
		}
	}
	writeJSON(w, 200, "成功", resp)
}

func handleSaveSecurity(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未认证", nil)
		return
	}
	var req struct {
		PasswordPlain   string `json:"password_plain"`
		GesturePattern  string `json:"gesture_pattern"`
		SecurityEnabled *int   `json:"security_enabled"`
		MultiVerify     *int   `json:"multi_verify"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	sec, _ := getUserSecurity(userID)
	if sec == nil {
		sec = &UserSecurity{UserID: userID, CreatedAt: time.Now().Unix()}
	}
	if req.PasswordPlain != "" {
		if len(req.PasswordPlain) < 4 {
			writeJSON(w, 400, "密码长度至少4位", nil)
			return
		}
		hash, err := bcrypt.GenerateFromPassword([]byte(req.PasswordPlain), bcrypt.DefaultCost)
		if err != nil {
			writeJSON(w, 500, "密码加密失败", nil)
			return
		}
		sec.PasswordHash = string(hash)
	}
	if req.GesturePattern != "" {
		sec.GesturePattern = req.GesturePattern
	}
	if req.SecurityEnabled != nil {
		sec.SecurityEnabled = *req.SecurityEnabled
	}
	if req.MultiVerify != nil {
		sec.MultiVerify = *req.MultiVerify
	}
	if err := saveUserSecurity(sec); err != nil {
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	writeJSON(w, 200, "保存成功", nil)
}

func handleVerifySecurity(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未认证", nil)
		return
	}
	var req struct {
		PasswordPlain  string `json:"password_plain"`
		GesturePattern string `json:"gesture_pattern"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	sec, err := getUserSecurity(userID)
	if err != nil || sec == nil {
		writeJSON(w, 403, "该账号未设置安全验证", nil)
		return
	}
	if req.PasswordPlain != "" {
		if sec.PasswordHash == "" {
			writeJSON(w, 403, "该账号未设置密码验证", nil)
			return
		}
		if err := bcrypt.CompareHashAndPassword([]byte(sec.PasswordHash), []byte(req.PasswordPlain)); err != nil {
			writeJSON(w, 403, "密码错误", nil)
			return
		}
		writeJSON(w, 200, "验证通过", map[string]interface{}{"verified": true, "method": "password"})
		return
	}
	if req.GesturePattern != "" {
		if sec.GesturePattern == "" {
			writeJSON(w, 403, "该账号未设置手势验证", nil)
			return
		}
		if req.GesturePattern != sec.GesturePattern {
			writeJSON(w, 403, "手势错误", nil)
			return
		}
		writeJSON(w, 200, "验证通过", map[string]interface{}{"verified": true, "method": "gesture"})
		return
	}
	writeJSON(w, 400, "请提供密码或手势进行验证", nil)
}

// ==================== 系统公告管理 ====================

// Announcement 公告数据模型
type Announcement struct {
	ID         int64  `json:"id"`
	Enabled    bool   `json:"enabled"`
	Title      string `json:"title"`
	Content    string `json:"content"`
	Source     string `json:"source"`
	Date       string `json:"date"`
	Supplement string `json:"supplement"`
	UpdatedAt  int64  `json:"updated_at"`
}

// 用于数据库 Scan 的内部结构（enabled 为 int）
type announcementRow struct {
	ID         int64
	Enabled    int
	Title      string
	Content    string
	Source     string
	Date       string
	Supplement string
	UpdatedAt  int64
}

// POST /api/admin/announcement/save — 管理员保存/更新公告
func handleAdminSaveAnnouncement(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "announcement.edit") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req Announcement
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数格式错误", nil)
		return
	}

	now := time.Now().Unix()
	enabledVal := 0
	if req.Enabled {
		enabledVal = 1
	}

	// 使用 INSERT OR REPLACE 确保 id=1 的公告记录始终存在
	_, err := db.Exec(
		`INSERT OR REPLACE INTO announcements (id, enabled, title, content, source, date, supplement, created_at, updated_at) VALUES (1, ?, ?, ?, ?, ?, ?, COALESCE((SELECT created_at FROM announcements WHERE id = 1), ?), ?)`,
		enabledVal, req.Title, req.Content, req.Source, req.Date, req.Supplement, now, now,
	)
	if err != nil {
		errMsg := err.Error()
		log.Printf("[公告] 保存失败: %v", errMsg)
		writeJSON(w, 500, "保存失败: "+errMsg, nil)
		return
	}

	log.Printf("[公告] 管理员更新公告: title=%s, enabled=%v", req.Title, req.Enabled)
	writeJSON(w, 200, "公告已保存", nil)
}

// GET /api/admin/announcement — 管理员获取公告详情
func handleAdminGetAnnouncement(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "announcement.view") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	row := &announcementRow{}
	err := db.QueryRow(
		`SELECT id, enabled, title, content, source, date, supplement, updated_at FROM announcements WHERE id=1`,
	).Scan(&row.ID, &row.Enabled, &row.Title, &row.Content, &row.Source, &row.Date, &row.Supplement, &row.UpdatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			writeJSON(w, 200, "无公告", nil)
			return
		}
		log.Printf("[公告] 查询失败: %v", err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}

	ann := &Announcement{
		ID:         row.ID,
		Enabled:    row.Enabled == 1,
		Title:      row.Title,
		Content:    row.Content,
		Source:     row.Source,
		Date:       row.Date,
		Supplement: row.Supplement,
		UpdatedAt:  row.UpdatedAt,
	}
	writeJSON(w, 200, "获取成功", ann)
}

// GET /api/announcement — 用户获取当前启用的公告（登录后调用）
func handleGetAnnouncementPublic(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}

	row := &announcementRow{}
	err := db.QueryRow(
		`SELECT id, enabled, title, content, source, date, supplement, updated_at FROM announcements WHERE id=1 AND enabled=1`,
	).Scan(&row.ID, &row.Enabled, &row.Title, &row.Content, &row.Source, &row.Date, &row.Supplement, &row.UpdatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			writeJSON(w, 200, "暂无公告", nil)
			return
		}
		writeJSON(w, 500, "查询失败", nil)
		return
	}

	ann := &Announcement{
		ID:         row.ID,
		Enabled:    row.Enabled == 1,
		Title:      row.Title,
		Content:    row.Content,
		Source:     row.Source,
		Date:       row.Date,
		Supplement: row.Supplement,
		UpdatedAt:  row.UpdatedAt,
	}
	writeJSON(w, 200, "获取成功", ann)
}

// ==================== 隐私功能（防截屏/防录屏/防退出） ====================

// GET /api/privacy/settings?friend_id=xxx — 获取与指定好友的隐私设置
func handleGetPrivacySettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	friendIDStr := r.URL.Query().Get("friend_id")
	friendID, err := strconv.ParseInt(friendIDStr, 10, 64)
	if err != nil || friendID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	masterOn, noExit, noScreenshot, noRecording := false, false, false, false
	detectScreenshot, detectRecording := false, false
	err = db.QueryRow("SELECT master_on, no_exit, no_screenshot, no_recording, detect_screenshot, detect_recording FROM privacy_settings WHERE user_id = ? AND friend_id = ?", userID, friendID).Scan(&masterOn, &noExit, &noScreenshot, &noRecording, &detectScreenshot, &detectRecording)
	if err == sql.ErrNoRows {
		writeJSON(w, 200, "ok", map[string]interface{}{"master_on": false, "no_exit": false, "no_screenshot": false, "no_recording": false, "detect_screenshot": false, "detect_recording": false})
		return
	}
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"master_on": masterOn, "no_exit": noExit, "no_screenshot": noScreenshot, "no_recording": noRecording, "detect_screenshot": detectScreenshot, "detect_recording": detectRecording})
}

// POST /api/privacy/settings/update — 更新隐私设置
func handleUpdatePrivacySettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FriendID         int64 `json:"friend_id"`
		MasterOn         bool  `json:"master_on"`
		NoExit           bool  `json:"no_exit"`
		NoScreenshot     bool  `json:"no_screenshot"`
		NoRecording      bool  `json:"no_recording"`
		DetectScreenshot bool  `json:"detect_screenshot"`
		DetectRecording  bool  `json:"detect_recording"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.FriendID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	_, err := db.Exec(`INSERT INTO privacy_settings (user_id, friend_id, master_on, no_exit, no_screenshot, no_recording, detect_screenshot, detect_recording) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user_id, friend_id) DO UPDATE SET master_on=?, no_exit=?, no_screenshot=?, no_recording=?, detect_screenshot=?, detect_recording=?`,
		userID, req.FriendID, req.MasterOn, req.NoExit, req.NoScreenshot, req.NoRecording, req.DetectScreenshot, req.DetectRecording,
		req.MasterOn, req.NoExit, req.NoScreenshot, req.NoRecording, req.DetectScreenshot, req.DetectRecording)
	if err != nil {
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	writeJSON(w, 200, "已更新", nil)
}

// POST /api/privacy/alert — 发送隐私告警（截图/录屏提醒 或 设置变更通知到对方）
func handlePrivacyAlert(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FriendID int64  `json:"friend_id"`
		Type     string `json:"type"` // "screenshot","recording","settings_change","no_exit_toggle","no_capture_toggle"(合并后的禁止截图/录屏),"no_screenshot_toggle"/"no_recording_toggle"(旧客户端兼容)
		Blocked  bool   `json:"blocked"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	var senderName string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&senderName)
	msg := ""
	switch req.Type {
	case "screenshot":
		if req.Blocked {
			msg = fmt.Sprintf("%s 尝试截图，已被系统拦截", senderName)
		} else {
			msg = fmt.Sprintf("%s 截取了屏幕", senderName)
		}
	case "recording":
		if req.Blocked {
			msg = fmt.Sprintf("%s 尝试录屏，已被系统拦截", senderName)
		} else {
			msg = fmt.Sprintf("%s 录制了屏幕", senderName)
		}
	case "no_screenshot_toggle":
		if req.Blocked {
			msg = fmt.Sprintf("%s 开启了禁止截图", senderName)
		} else {
			msg = fmt.Sprintf("%s 关闭了禁止截图", senderName)
		}
	case "no_recording_toggle":
		if req.Blocked {
			msg = fmt.Sprintf("%s 开启了禁止录屏", senderName)
		} else {
			msg = fmt.Sprintf("%s 关闭了禁止录屏", senderName)
		}
	case "no_capture_toggle":
		// 客户端已把「禁止截图」「禁止录屏」合并为一个开关，统一走这个类型，只发一条广播
		if req.Blocked {
			msg = fmt.Sprintf("%s 开启了禁止截图与录屏", senderName)
		} else {
			msg = fmt.Sprintf("%s 关闭了禁止截图与录屏", senderName)
		}
	case "no_exit_toggle":
		if req.Blocked {
			msg = fmt.Sprintf("%s 开启了禁止退出", senderName)
		} else {
			msg = fmt.Sprintf("%s 关闭了禁止退出", senderName)
		}
	case "settings_change":
		msg = fmt.Sprintf("%s 更新了隐私设置", senderName)
	default:
		msg = fmt.Sprintf("%s 触发了隐私事件", senderName)
	}
	// 作为系统消息发送给对方
	insertSystemMessage(req.FriendID, userID, msg)
	// 推送实时通知
	PushToUser(req.FriendID, "privacy_alert", map[string]interface{}{
		"from_user_id": userID,
		"type":         req.Type,
		"blocked":      req.Blocked,
		"message":      msg,
	})
	writeJSON(w, 200, "已发送告警", nil)
}

func insertSystemMessage(toUserID, fromUserID int64, msg string) {
	db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at, server_received_ms) VALUES (?, ?, ?, 'system', ?, ?)`,
		fromUserID, toUserID, msg, time.Now().Unix(), time.Now().UnixMilli())
}

// POST /api/privacy/unlock-request — 发送取消锁定请求
func handleSendUnlockRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	cardMsg := fmt.Sprintf("取消锁定请求\n━━━━━━━━━━\n%s 请求取消隐私锁定\n双方同意后即可退出当前对话\n━━━━━━━━━━\nunlock_request=%d_%d", username, userID, req.FriendID)
	res, _ := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at, server_received_ms) VALUES (?, ?, ?, 'privacy_request', ?, ?)`,
		userID, req.FriendID, cardMsg, time.Now().Unix(), time.Now().UnixMilli())
	msgID, _ := res.LastInsertId()
	// 落库即推双方：对方立刻看到卡片，发起方立刻对齐（卡片客户端没有乐观气泡）
	pushPersistedCardToBoth(userID, req.FriendID, msgID, cardMsg, username)
	writeJSON(w, 200, "取消请求已发送", nil)
}

// POST /api/privacy/unlock-respond — 响应取消锁定请求
func handleRespondUnlockRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FromUserID int64 `json:"from_user_id"`
		Accepted   bool  `json:"accepted"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	msg := ""
	if req.Accepted {
		msg = fmt.Sprintf("取消锁定\n━━━━━━━━━━\n%s 已同意取消隐私锁定\n双方均可退出当前对话\n━━━━━━━━━━\nunlock_accept=%d_%d", username, userID, req.FromUserID)
		// 清除双方锁定关系
		db.Exec("DELETE FROM privacy_lock WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)",
			userID, req.FromUserID, req.FromUserID, userID)
	} else {
		msg = fmt.Sprintf("取消锁定\n━━━━━━━━━━\n%s 拒绝取消隐私锁定\n━━━━━━━━━━\nunlock_reject=%d_%d", username, userID, req.FromUserID)
	}
	res, _ := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at, server_received_ms) VALUES (?, ?, ?, 'privacy_response', ?, ?)`,
		userID, req.FromUserID, msg, time.Now().Unix(), time.Now().UnixMilli())
	msgID, _ := res.LastInsertId()
	// 落库即推双方（见 pushPersistedCardToBoth 注释）
	pushPersistedCardToBoth(userID, req.FromUserID, msgID, msg, username)
	writeJSON(w, 200, map[bool]string{true: "已同意取消", false: "已拒绝取消"}[req.Accepted], nil)
}

// ==================== 隐私请求（禁止退出双向锁定） ====================

// POST /api/privacy/request-send — 发送隐私锁定请求
func handleSendPrivacyRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	// 构建卡片消息
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	cardMsg := fmt.Sprintf("隐私锁定请求\n━━━━━━━━━━\n%s 请求与你进行隐私锁定\n开启后双方将无法退出当前对话\n━━━━━━━━━━\n双方均需开启无障碍权限\n请在收到后选择同意或拒绝\nprivacy_request_id=%d_%d", username, userID, req.FriendID)
	res, _ := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at, server_received_ms) VALUES (?, ?, ?, 'privacy_request', ?, ?)`,
		userID, req.FriendID, cardMsg, time.Now().Unix(), time.Now().UnixMilli())
	msgID, _ := res.LastInsertId()
	// 落库即推双方（见 pushPersistedCardToBoth 注释）
	pushPersistedCardToBoth(userID, req.FriendID, msgID, cardMsg, username)
	writeJSON(w, 200, "请求已发送", nil)
}

// POST /api/privacy/request-respond — 响应隐私锁定请求
func handleRespondPrivacyRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FromUserID int64 `json:"from_user_id"`
		Accepted   bool  `json:"accepted"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	msg := ""
	if req.Accepted {
		msg = fmt.Sprintf("隐私锁定\n━━━━━━━━━━\n%s 已同意隐私锁定\n请前往开启无障碍权限\n开启后双方将锁定在对话\n━━━━━━━━━━\nprivacy_accept=%d_%d", username, userID, req.FromUserID)
	} else {
		msg = fmt.Sprintf("隐私锁定\n━━━━━━━━━━\n%s 已拒绝隐私锁定请求\n━━━━━━━━━━\nprivacy_reject=%d_%d", username, userID, req.FromUserID)
	}
	res, _ := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at, server_received_ms) VALUES (?, ?, ?, 'privacy_response', ?, ?)`,
		userID, req.FromUserID, msg, time.Now().Unix(), time.Now().UnixMilli())
	msgID, _ := res.LastInsertId()
	// 落库即推双方（见 pushPersistedCardToBoth 注释）
	pushPersistedCardToBoth(userID, req.FromUserID, msgID, msg, username)
	if req.Accepted {
		// 记录双方锁定关系
		db.Exec(`INSERT OR REPLACE INTO privacy_lock (user_id, friend_id, locked) VALUES (?, ?, 1), (?, ?, 1)`,
			userID, req.FromUserID, req.FromUserID, userID)
	}
	writeJSON(w, 200, map[bool]string{true: "已同意", false: "已拒绝"}[req.Accepted], nil)
}
