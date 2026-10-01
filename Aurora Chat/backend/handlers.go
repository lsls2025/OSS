package main

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"math/rand"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// API 统一响应格式
type ApiResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

// 登录响应
type LoginResponse struct {
	ID                     int64  `json:"id"`
	Email                  string `json:"email"`
	Username               string `json:"username"`
	Signature              string `json:"signature"`
	Token                  string `json:"token"`
	EmailVerified          bool   `json:"email_verified"`
	NeedsProfileCompletion bool   `json:"needs_profile_completion"`
	QQNumber               string `json:"qq_number"`
	// SessionKey 服务端下发的用户级 AES-256 传输会话密钥（base64，32 字节）。
	// 客户端用于发送前对消息内容做 AES-256-GCM 预加密（前缀 sess:v1:），
	// 消除客户端→服务端传输阶段的明文；服务端收到后用它解密再静态加密落库。
	SessionKey string `json:"session_key"`
}

// needsProfileCompletion 判断用户是否需要完善资料
// 满足任一条件即为 true：占位符邮箱、QQ 号为空、用户名为空
func needsProfileCompletion(user *User) bool {
	if strings.HasPrefix(user.Email, "qq_") || strings.HasPrefix(user.Email, "temp_") {
		return true
	}
	if user.QQNumber == "" {
		return true
	}
	if user.Username == "" {
		return true
	}
	return false
}

func writeJSON(w http.ResponseWriter, code int, msg string, data interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(ApiResponse{
		Code:    code,
		Message: msg,
		Data:    data,
	})
}

// checkDevPermission 检查是否为开发者
func checkDevPermission(r *http.Request) bool {
	return isDeveloperUser(getCurrentUserID(r))
}

// checkAdminOrDevPermission 检查是否为平台管理员或开发者
func checkAdminOrDevPermission(r *http.Request) (int64, bool) {
	userID := getCurrentUserID(r)
	if userID <= 0 {
		return 0, false
	}
	if checkDevPermission(r) {
		return userID, true
	}
	isAdmin, err := isPlatformAdmin(userID)
	if err != nil {
		return 0, false
	}
	return userID, isAdmin
}

// checkAdminPermission 通用管理权限检查（含细粒度权限）
// 返回值: userID, 是否有权限
//   - 开发者永远有权限
//   - 平台管理员需要拥有对应的 permKey 细粒度权限（或没有权限记录时视为有全部权限）
func checkAdminPermission(r *http.Request, permKey string) (int64, bool) {
	userID := getCurrentUserID(r)
	if userID <= 0 {
		return 0, false
	}
	if checkDevPermission(r) {
		return userID, true
	}
	ok, _ := isPlatformAdmin(userID)
	if !ok {
		return 0, false
	}
	if checkUserPermission(userID, permKey) {
		return userID, true
	}
	return 0, false
}

// checkDevOrPerm 检查是否有开发者权限，或平台管理员拥有指定细粒度权限
func checkDevOrPerm(r *http.Request, permKey string) bool {
	if checkDevPermission(r) {
		return true
	}
	userID := getCurrentUserID(r)
	ok, _ := isPlatformAdmin(userID)
	return ok && checkUserPermission(userID, permKey)
}

var emailRegex = regexp.MustCompile(`^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`)

// ==================== 健康检查 ====================

func handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "ok",
		"service": "Aurora Chat",
		"version": "2.0",
		"time":    time.Now().Unix(),
	})
}

// ==================== APP 版本检查（强制更新） ====================

// 当前最新版本配置（发布新版时更新这里，或在 version.txt 中修改）
var (
	LatestVersionCode = 17 // 发布新版时改成 build.gradle.kts 里的 versionCode
	LatestVersionName = "1.7.0"
	DownloadURL       = "http://www.YOUR_SERVER_DOMAIN:5004/tools/aurora.apk" // APK 放到服务器 data/tools/ 目录后改文件名
	ForceUpdate       = true                                               // 发布新版时改成 true
	UpdateMessage     = "发现新版本，请更新后继续使用。"
)

// GET /api/app/version — 获取最新版本信息（无需登录）
func handleAppVersion(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"latest_version_code": LatestVersionCode,
			"latest_version_name": LatestVersionName,
			"download_url":        DownloadURL,
			"force_update":        ForceUpdate,
			"update_message":      UpdateMessage,
		},
	})
}

// ==================== 发送验证码 ====================

func handleSendCode(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		Email         string `json:"email"`
		CaptchaID     string `json:"captcha_id"`
		CaptchaAnswer string `json:"captcha_answer"`
		Captcha       string `json:"captcha"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	// 已移除图形验证码闸门：人机/防刷由"注册必须携带设备指纹 + 发起冷却/频限"承担

	req.Email = strings.TrimSpace(req.Email)
	if req.Email == "" {
		writeJSON(w, 400, "请输入邮箱/用户名/QQ号", nil)
		return
	}

	// 如果不是邮箱格式，则按用户名或 QQ 号反查关联邮箱（兼容旧版邮箱用户找回密码）
	if !emailRegex.MatchString(req.Email) {
		var resolvedEmail string
		if isDigitsOnly(req.Email) {
			if u, _ := findUserByQQNumber(req.Email); u != nil && u.Email != "" && !strings.HasPrefix(u.Email, "qq_") && !strings.HasSuffix(u.Email, "@local") {
				resolvedEmail = u.Email
			}
		} else {
			if u, _ := findUserByUsername(req.Email); u != nil && u.Email != "" && !strings.HasPrefix(u.Email, "qq_") && !strings.HasSuffix(u.Email, "@local") {
				resolvedEmail = u.Email
			}
		}
		if resolvedEmail == "" {
			writeJSON(w, 400, "该账号未绑定邮箱，无法发送验证码", nil)
			return
		}
		req.Email = resolvedEmail
	}


	// 检查是否已被注册
	registered, err := isEmailRegistered(req.Email)
	if err != nil {
		log.Printf("检查邮箱失败: %v", err)
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if registered {
		writeJSON(w, 409, "该邮箱已被注册", nil)
		return
	}

	// 检查冷却
	ok, wait := checkCodeCoolDown(req.Email)
	if !ok {
		writeJSON(w, 429, fmt.Sprintf("发送过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	// 生成并保存验证码
	code := generateCode()
	if err := saveVerificationCode(req.Email, code); err != nil {
		log.Printf("保存验证码失败: %v", err)
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	// 发送验证码（同步发送，失败则重试，确保用户真正收到邮件后才返回成功）
	var (
		emailSent bool
		emailMsg  string
	)
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			time.Sleep(2 * time.Second)
		}
		emailSent, emailMsg = sendEmailCode(req.Email, code)
		if emailSent {
			break
		}
		log.Printf("[验证码] 第 %d 次发送失败: %s -> %s (%s)", attempt+1, req.Email, code, emailMsg)
	}
	if !emailSent {
		log.Printf("[验证码] 3 次重试均失败: %s", req.Email)
		writeJSON(w, 500, "邮件发送失败，请稍后重试或联系管理员", nil)
		return
	}

	recordCodeSent(req.Email)
	log.Printf("验证码已发送: %s -> %s", req.Email, code)
	writeJSON(w, 200, "验证码已发送至邮箱", nil)
}

// ==================== 注册 ====================

func handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	// 抵防：禁止注册 —— 开启后直接切断所有注册请求
	if getFlag("disable_register") {
		writeJSON(w, 403, "注册功能已临时关闭，请稍后再试", nil)
		return
	}

	var req struct {
		Email         string `json:"email"`
		Username      string `json:"username"`
		Password      string `json:"password"`
		Code          string `json:"code"`
		QQNumber      string `json:"qq_number"`
		DeviceID      string `json:"device_id"`
		CaptchaID     string `json:"captcha_id"`
		CaptchaAnswer string `json:"captcha_answer"`
		Captcha       string `json:"captcha"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	// 防脚本注册：任何注册请求必须携带真实设备指纹。
	// 设备指纹由客户端本地硬件/标识生成，纯 API 调用方拿不到合法指纹，
	// 缺失即视为非法接口调用，直接拒绝且不返回细节。
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.DeviceID == "" {
		writeJSON(w, 403, "非法请求", nil)
		return
	}

	// 单设备永久账号上限（防批量小号）
	if n, err := count_accounts_by_device(req.DeviceID); err == nil && n >= MAX_ACCOUNTS_PER_DEVICE {
		writeJSON(w, 429, fmt.Sprintf("当前设备最多可注册 %d 个账号，已达上限", MAX_ACCOUNTS_PER_DEVICE), nil)
		return
	}

	req.Email = strings.TrimSpace(req.Email)
	req.Username = html.EscapeString(strings.TrimSpace(req.Username))
	req.Code = strings.TrimSpace(req.Code)
	req.QQNumber = strings.TrimSpace(req.QQNumber)

	// 用户名和密码必填
	if req.Username == "" || req.Password == "" {
		writeJSON(w, 400, "请填写用户名和密码", nil)
		return
	}
	// 校验 QQ 号格式（如有）
	if req.QQNumber != "" && !isDigitsOnly(req.QQNumber) {
		writeJSON(w, 400, "QQ 号格式不正确", nil)
		return
	}

	// IP 频率限制 + 黑名单检查
	clientIP := getClientIP(r)
	if blocked, _ := isIPBlocked(clientIP); blocked {
		writeJSON(w, 403, "该 IP 已被封禁，无法注册", nil)
		return
	}
	if allowed, wait := checkRegisterRateLimit(clientIP); !allowed {
		writeJSON(w, 429, fmt.Sprintf("注册过于频繁，请在 %d 秒后再试", wait), nil)
		return
	}
	if len(req.Username) < 2 || len(req.Username) > 20 {
		writeJSON(w, 400, "用户名长度应在 2-20 个字符之间", nil)
		return
	}
	if len(req.Password) < 8 {
		writeJSON(w, 400, "密码不能少于 8 位", nil)
		return
	}

	hasVerifiedEmail := false

	if req.Email != "" {
		// 有邮箱 → 需要验证码
		if !emailRegex.MatchString(req.Email) {
			writeJSON(w, 400, "邮箱格式不正确", nil)
			return
		}
		if len(req.Code) != 6 {
			writeJSON(w, 400, "验证码格式不正确", nil)
			return
		}
		registered, err := isEmailRegistered(req.Email)
		if err != nil {
			writeJSON(w, 500, "服务器内部错误", nil)
			return
		}
		if registered {
			writeJSON(w, 409, "该邮箱已被注册", nil)
			return
		}
		if !verifyCode(req.Email, req.Code) {
			writeJSON(w, 400, "验证码错误或已过期", nil)
			return
		}
		hasVerifiedEmail = true
		deleteVerificationCode(req.Email)
	}

	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	ev := 0
	if hasVerifiedEmail {
		ev = 1
	}
	userID, err := createUser(req.Email, req.Username, string(hashedPassword), clientIP, ev)
	if err != nil {
		writeJSON(w, 500, "注册失败，请稍后重试", nil)
		return
	}
	if req.DeviceID != "" {
		db.Exec("UPDATE users SET reg_device = ? WHERE id = ?", req.DeviceID, userID)
	}
	if req.QQNumber != "" {
		db.Exec("UPDATE users SET qq_number = ? WHERE id = ?", req.QQNumber, userID)
	}

	db.Exec("UPDATE users SET token_version = 1 WHERE id = ?", userID)

	userEmail := req.Email
	if userEmail == "" {
		userEmail = fmt.Sprintf("temp_%d@local", userID)
	}
	token, _ := generateToken(userID, userEmail, 1)

	log.Printf("新用户注册成功: ID=%d, Username=%s (邮箱验证=%v)", userID, req.Username, hasVerifiedEmail)
	writeJSON(w, 200, "注册成功", LoginResponse{
		ID:            userID,
		Email:         userEmail,
		Username:      req.Username,
		Signature:     "",
		Token:         token,
		EmailVerified: hasVerifiedEmail,
		SessionKey:    getOrCreateSessionKey(userID),
	})
}

// ==================== 绑定邮箱 ====================

// POST /api/user/bind-email/send-code — 发送绑定邮箱验证码
func handleSendBindCode(w http.ResponseWriter, r *http.Request) {
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
		Email string `json:"email"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.Email = strings.TrimSpace(req.Email)
	if req.Email == "" || !emailRegex.MatchString(req.Email) {
		writeJSON(w, 400, "邮箱格式不正确", nil)
		return
	}
	// 检查邮箱是否已被其他用户绑定
	existing, _ := findUserByEmail(req.Email)
	if existing != nil && existing.ID != currentUserID {
		writeJSON(w, 409, "该邮箱已被其他账号绑定", nil)
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
	log.Printf("绑定邮箱验证码已发送: %s -> %s", req.Email, code)
	writeJSON(w, 200, "验证码已发送", nil)
}

// POST /api/user/bind-email — 绑定并验证邮箱
func handleBindEmail(w http.ResponseWriter, r *http.Request) {
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
		Email string `json:"email"`
		Code  string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.Email = strings.TrimSpace(req.Email)
	req.Code = strings.TrimSpace(req.Code)
	if req.Email == "" || req.Code == "" {
		writeJSON(w, 400, "请填写邮箱和验证码", nil)
		return
	}
	if !emailRegex.MatchString(req.Email) {
		writeJSON(w, 400, "邮箱格式不正确", nil)
		return
	}
	if len(req.Code) != 6 {
		writeJSON(w, 400, "验证码格式不正确", nil)
		return
	}
	// 检查邮箱是否已被其他用户绑定
	existing, _ := findUserByEmail(req.Email)
	if existing != nil && existing.ID != currentUserID {
		writeJSON(w, 409, "该邮箱已被其他账号绑定", nil)
		return
	}
	if !verifyCode(req.Email, req.Code) {
		writeJSON(w, 400, "验证码错误或已过期", nil)
		return
	}
	deleteVerificationCode(req.Email)

	// 更新用户邮箱和验证状态
	if err := updateUserEmail(currentUserID, req.Email); err != nil {
		log.Printf("绑定邮箱失败: user=%d email=%s err=%v", currentUserID, req.Email, err)
		writeJSON(w, 500, "绑定失败", nil)
		return
	}
	log.Printf("用户绑定邮箱成功: ID=%d, Email=%s", currentUserID, req.Email)
	writeJSON(w, 200, "邮箱绑定成功", nil)
}

// ==================== 登录 ====================

func handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.Email = strings.TrimSpace(req.Email)
	if req.Email == "" || req.Password == "" {
		writeJSON(w, 400, "请填写用户名/邮箱/QQ号和密码", nil)
		return
	}
	if len(req.Password) < 8 {
		writeJSON(w, 400, "密码不能少于 8 位", nil)
		return
	}

	// 支持邮箱 / 用户名 / QQ 号登录
	var user *User
	var err error
	if emailRegex.MatchString(req.Email) {
		user, err = findUserByEmail(req.Email)
	} else if isDigitsOnly(req.Email) {
		user, err = findUserByQQNumber(req.Email)
	} else {
		user, err = findUserByUsername(req.Email)
	}
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if user == nil || bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(req.Password)) != nil {
		writeJSON(w, 401, "用户名/邮箱/QQ号或密码错误", nil)
		return
	}

	// 清除 kicked_at 标记（用户重新登录后不再显示"已被踢出"提示）
	db.Exec("UPDATE users SET kicked_at = 0 WHERE id = ?", user.ID)
	var currentVersion int64
	db.QueryRow("SELECT token_version FROM users WHERE id = ?", user.ID).Scan(&currentVersion)

	// 生成 JWT token
	token, _ := generateToken(user.ID, user.Email, currentVersion)

	// 更新最近活动时间
	now := time.Now().Unix()
	db.Exec("UPDATE users SET updated_at = ? WHERE id = ?", now, user.ID)
	user.UpdatedAt = now

	log.Printf("用户登录成功: Email=%s, Username=%s", user.Email, user.Username)
	writeJSON(w, 200, "登录成功", LoginResponse{
		ID:                     user.ID,
		Email:                  user.Email,
		Username:               user.Username,
		Signature:              user.Signature,
		Token:                  token,
		EmailVerified:          user.EmailVerified == 1,
		NeedsProfileCompletion: needsProfileCompletion(user),
		QQNumber:               user.QQNumber,
		SessionKey:             getOrCreateSessionKey(user.ID),
	})
}

// ==================== 完善资料（防重复建号，仅 UPDATE） ====================

func handleCompleteProfile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	// 1. 从 token 获取当前用户
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未授权", nil)
		return
	}

	// 2. 解析请求体
	var req struct {
		Username string `json:"username"`
		QQNumber string `json:"qq_number"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.Username = html.EscapeString(strings.TrimSpace(req.Username))
	req.QQNumber = strings.TrimSpace(req.QQNumber)

	// 3. 校验必填字段
	if req.Username == "" {
		writeJSON(w, 400, "用户名不能为空", nil)
		return
	}
	if req.QQNumber == "" || !isDigitsOnly(req.QQNumber) {
		writeJSON(w, 400, "请输入有效的QQ号", nil)
		return
	}

	// 4. 核心：检查 QQ 号是否已被其他用户占用
	existing, _ := findUserByQQNumber(req.QQNumber)
	if existing != nil && existing.ID != userID {
		writeJSON(w, 409, "该QQ号已被其他账号绑定", nil)
		return
	}

	// 5. 更新当前用户记录（只 UPDATE，不 INSERT）
	now := time.Now().Unix()
	if req.Password != "" {
		hashed, _ := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
		db.Exec("UPDATE users SET username=?, qq_number=?, password=?, updated_at=? WHERE id=?",
			req.Username, req.QQNumber, string(hashed), now, userID)
	} else {
		db.Exec("UPDATE users SET username=?, qq_number=?, updated_at=? WHERE id=?",
			req.Username, req.QQNumber, now, userID)
	}

	// 6. 重新生成 token（资料变了，刷新 token）
	var newVersion int64
	db.QueryRow("SELECT token_version FROM users WHERE id = ?", userID).Scan(&newVersion)
	user, _ := findUserByID(userID)
	if user == nil {
		writeJSON(w, 500, "用户不存在", nil)
		return
	}
	newToken, _ := generateToken(userID, user.Email, newVersion)

	// 7. 返回新 token
	writeJSON(w, 200, "资料完善成功", LoginResponse{
		ID:                     user.ID,
		Email:                  user.Email,
		Username:               req.Username,
		Signature:              user.Signature,
		Token:                  newToken,
		EmailVerified:          user.EmailVerified == 1,
		NeedsProfileCompletion: false,
		QQNumber:               user.QQNumber,
	})
}

// ==================== 用户自主注销 ====================

func handleSelfDeleteUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.Password == "" {
		writeJSON(w, 400, "请输入密码", nil)
		return
	}
	// 验证密码
	user, err := findUserByID(userID)
	if err != nil || user == nil {
		writeJSON(w, 500, "用户不存在", nil)
		return
	}
	if bcrypt.CompareHashAndPassword([]byte(user.Password), []byte(req.Password)) != nil {
		writeJSON(w, 401, "密码错误", nil)
		return
	}
	// 删除账号数据
	if err := deleteUserAllData(userID); err != nil {
		log.Printf("[注销] 用户 %d 注销失败: %v", userID, err)
		writeJSON(w, 500, "注销失败: "+err.Error(), nil)
		return
	}
	log.Printf("[注销] 用户 %d 已成功注销", userID)
	writeJSON(w, 200, "账号已注销", nil)
}

// ==================== 密码重置 ====================

func handleResetPassword(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		Email       string `json:"email"`
		Code        string `json:"code"`
		NewPassword string `json:"new_password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.Email = strings.TrimSpace(req.Email)
	req.Code = strings.TrimSpace(req.Code)

	if req.Email == "" || req.Code == "" || req.NewPassword == "" {
		writeJSON(w, 400, "请填写所有字段", nil)
		return
	}
	if !emailRegex.MatchString(req.Email) {
		writeJSON(w, 400, "邮箱格式不正确", nil)
		return
	}
	if len(req.NewPassword) < 8 {
		writeJSON(w, 400, "密码不能少于 8 位", nil)
		return
	}

	// 验证用户存在
	user, err := findUserByEmail(req.Email)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if user == nil {
		writeJSON(w, 404, "该邮箱未注册", nil)
		return
	}

	// 验证验证码
	if !verifyCode(req.Email, req.Code) {
		writeJSON(w, 400, "验证码错误或已过期", nil)
		return
	}

	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	if err := updatePassword(user.ID, string(hashedPassword)); err != nil {
		writeJSON(w, 500, "重置密码失败", nil)
		return
	}

	deleteVerificationCode(req.Email)
	log.Printf("密码重置成功: Email=%s", req.Email)
	writeJSON(w, 200, "密码重置成功", nil)
}

// ==================== 获取所有用户（开发者管理） ====================

func handleGetUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	// 开发者或平台管理员均可查看用户列表
	_, ok := checkAdminPermission(r, "users.view_profile")
	if !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	users, err := getAllUsers()
	if err != nil {
		log.Printf("获取用户列表失败: %v", err)
		writeJSON(w, 500, "获取用户列表失败", nil)
		return
	}
	if users == nil {
		users = []User{}
	}
	writeJSON(w, 200, "获取成功", users)
}

// POST /api/user/privacy — 用户隐私设置
func handleUserPrivacy(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		HideEmail           *bool `json:"hide_email"`
		HideQQ              *bool `json:"hide_qq"`
		RequireGroupConsent *bool `json:"require_group_consent"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数格式错误", nil)
		return
	}

	if req.HideEmail != nil {
		val := 0
		if *req.HideEmail {
			val = 1
		}
		if _, err := db.Exec("UPDATE users SET hide_email = ? WHERE id = ?", val, userID); err != nil {
			writeJSON(w, 500, "设置失败", nil)
			return
		}
		log.Printf("用户 %d 隐私设置: hide_email=%d", userID, val)
	}
	if req.HideQQ != nil {
		val := 0
		if *req.HideQQ {
			val = 1
		}
		if _, err := db.Exec("UPDATE users SET hide_qq = ? WHERE id = ?", val, userID); err != nil {
			writeJSON(w, 500, "设置失败", nil)
			return
		}
		log.Printf("用户 %d 隐私设置: hide_qq=%d", userID, val)
	}
	if req.RequireGroupConsent != nil {
		val := 0
		if *req.RequireGroupConsent {
			val = 1
		}
		if _, err := db.Exec("UPDATE users SET require_group_consent = ? WHERE id = ?", val, userID); err != nil {
			writeJSON(w, 500, "设置失败", nil)
			return
		}
		log.Printf("用户 %d 隐私设置: require_group_consent=%d", userID, val)
	}

	var hideEmail, hideQQ, requireConsent int
	db.QueryRow("SELECT hide_email, hide_qq, require_group_consent FROM users WHERE id = ?", userID).Scan(&hideEmail, &hideQQ, &requireConsent)
	writeJSON(w, 200, "设置成功", map[string]bool{"hide_email": hideEmail == 1, "hide_qq": hideQQ == 1, "require_group_consent": requireConsent == 1})
}

// POST /api/user/update — 更新用户名和签名
func handleUpdateProfile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未授权", nil)
		return
	}
	var body struct {
		Username  string `json:"username"`
		Signature string `json:"signature"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if len(body.Username) > 50 {
		writeJSON(w, 400, "用户名过长", nil)
		return
	}
	if len(body.Signature) > 200 {
		writeJSON(w, 400, "签名过长", nil)
		return
	}
	now := time.Now().Unix()
	_, err := db.Exec("UPDATE users SET username = ?, signature = ?, updated_at = ? WHERE id = ?",
		body.Username, body.Signature, now, userID)
	if err != nil {
		writeJSON(w, 500, "更新失败", nil)
		return
	}
	writeJSON(w, 200, "更新成功", nil)
}

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
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
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
			Method       int      `json:"method"`
			TemplateMode int      `json:"template_mode"`
			OwnEmail     string   `json:"own_email"`
			OwnAuth      string   `json:"own_auth"`
			DndIDs       []int64  `json:"dnd_ids"`
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
			UserID:       userID,
			Method:       req.Method,
			TemplateMode: req.TemplateMode,
			OwnEmail:     req.OwnEmail,
			OwnAuth:      req.OwnAuth,
			DndIDs:       dndJSON,
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
	if !isSelf && !isDev {
		// 尊重用户隐私开关：hide_email/hide_qq 时为掩码；注册IP一律不暴露给非本人
		if user.HideEmail != 0 {
			email = maskEmail(email)
		}
		if user.HideQQ != 0 {
			qqNum = maskQQ(qqNum)
		}
		regIP = ""
	}

	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"id":                  user.ID,
		"email":               email,
		"username":            user.Username,
		"qq_number":           qqNum,
		"hide_qq":             user.HideQQ,
		"created_at":          user.CreatedAt,
		"updated_at":          user.UpdatedAt,
		"hide_email":          user.HideEmail,
		"require_group_consent": user.RequireGroupConsent,
		"signature":           user.Signature,
		"online_time_seconds": user.OnlineTimeSeconds,
		"word_count":          user.WordCount,
		"token_version":       user.TokenVersion,
		"email_verified":      user.EmailVerified != 0,
		"reg_ip":              regIP,
		"token_balance":       user.TokenBalance,
	})
}

// ==================== 二维码（扫码加好友） ====================
//
// 二维码内容包含两个独立信息：
//  1. 官网落地页 https://YOUR_SERVER_DOMAIN/q.html —— APP 外扫码（QQ/微信/相机/浏览器）
//     打开落地页，页面展示被扫码者的头像/昵称，并可引导去官网下载或在 App 内扫码加好友；
//  2. 用户 ID（一个纯数字）—— 通过 URL 的 query 参数 ?u=<id> 携带，供落地页 / App 内扫码解析。
//
// 格式：<官网>/q.html?u=<用户ID>
// 落地页仅展示公开的无害信息；真正的加好友动作必须由已登录的 APP 内扫码触发
// （调 /api/user/qrcode/resolve），外部扫码只能看到落地页，天然安全。

// 官网静态站点（nginx，443 裸域）。注意：不能用 getServerBaseURL()（后端 5004 端口），
// 否则扫码会打开 5004 后端地址的 /q.html 而 404。
const qrSiteURL = "https://YOUR_SERVER_DOMAIN"

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
	content := strings.TrimSpace(req.Content)
	switch {
	case strings.HasPrefix(content, "http://") || strings.HasPrefix(content, "https://"):
		// 最新版：官网落地页 /q.html?u=<用户ID>（query 参数）
		u, err := url.Parse(content)
		if err != nil {
			writeJSON(w, 400, "无效的二维码格式", nil)
			return
		}
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
			"is_self":   true,
			"id":        user.ID,
			"username":  user.Username,
			"signature": user.Signature,
			"avatar_url": fmt.Sprintf("/api/avatar/%d", user.ID),
			"message":   "这是你自己的二维码",
		})
		return
	}

	avatarUrl := fmt.Sprintf("/api/avatar/%d", targetID)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"is_self":   false,
		"id":        user.ID,
		"username":  user.Username,
		"signature": user.Signature,
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

	// 统计删除前数量（所有范围都排除官方群 -1001，绝不触碰）
	var countSQL string
	switch req.Scope {
	case "private":
		countSQL = "SELECT COUNT(*) FROM messages WHERE to_user_id >= 0 AND from_user_id >= 0"
	case "group":
		countSQL = "SELECT COUNT(*) FROM messages WHERE to_user_id < 0 AND to_user_id != -1001 AND from_user_id != -1001"
	default:
		countSQL = "SELECT COUNT(*) FROM messages WHERE to_user_id != -1001 AND from_user_id != -1001"
	}
	var before int64
	_ = db.QueryRow(countSQL).Scan(&before)

	// 删除消息（官方群 -1001 永久保留，任何范围都不删除）
	var msgSQL string
	switch req.Scope {
	case "private":
		msgSQL = "DELETE FROM messages WHERE to_user_id >= 0 AND from_user_id >= 0"
	case "group":
		msgSQL = "DELETE FROM messages WHERE to_user_id < 0 AND to_user_id != -1001 AND from_user_id != -1001"
	default:
		msgSQL = "DELETE FROM messages WHERE to_user_id != -1001 AND from_user_id != -1001"
	}
	res, err := db.Exec(msgSQL)
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

	AddBanRecord(req.UserID, req.Reason, time.Now().Unix()+req.Duration)

	PushToUser(req.UserID, "account_banned", map[string]interface{}{
		"reason":              req.Reason,
		"duration":            req.Duration,
		"expires_at":          time.Now().Unix() + req.Duration,
		"unban_popup_message": req.UnbanPopupMessage,
	})

	DisconnectUser(req.UserID)

	// 记录操作日志
	adminUsername := getCurrentEmailFromHeader(r)
	targetUsername := targetUser.Username
	details, _ := json.Marshal(map[string]interface{}{
		"reason":     req.Reason,
		"duration":   req.Duration,
		"expires_at": time.Now().Unix() + req.Duration,
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
		MessageID int64 `json:"message_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.MessageID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	recordFlashView(userID, req.MessageID)
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

// ==================== 管理员操作：解封用户 ====================

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

func handleAdminMuteUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID, ok := checkAdminPermission(r, "users.mute")
	if !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		UserID   int64 `json:"user_id"`
		Duration int64 `json:"duration"`
		MuteType int   `json:"mute_type"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 user_id", nil)
		return
	}
	if req.MuteType < 1 || req.MuteType > 3 {
		req.MuteType = 3
	}
	if req.Duration <= 0 {
		req.Duration = 100 * 365 * 24 * 60 * 60
	}

	if req.UserID == currentUserID {
		writeJSON(w, 403, "无法禁言自己的账号", nil)
		return
	}

	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	// 保护管理员和开发者：不可被禁言
	if isDeveloperUserObj(targetUser) {
		writeJSON(w, 403, "无法禁言开发者账号", nil)
		return
	}
	isTargetAdmin, _ := isPlatformAdmin(req.UserID)
	if isTargetAdmin && !checkDevPermission(r) {
		writeJSON(w, 403, "无法禁言管理员账号，请先取消其管理员权限", nil)
		return
	}

	if err := muteUser(req.UserID, req.Duration, currentUserID, req.MuteType); err != nil {
		log.Printf("[Admin] 禁言用户 %d 失败: %v", req.UserID, err)
		writeJSON(w, 500, "禁言失败", nil)
		return
	}

	AddMuteRecord(req.UserID, time.Now().Unix()+req.Duration)

	PushToUser(req.UserID, "account_muted", map[string]interface{}{
		"duration":   req.Duration,
		"expires_at": time.Now().Unix() + req.Duration,
		"mute_type":  req.MuteType,
	})

	DisconnectUser(req.UserID)

	// 记录操作日志
	adminUsername := getCurrentEmailFromHeader(r)
	muteTypeStr := "全部"
	if req.MuteType == 1 {
		muteTypeStr = "评论"
	} else if req.MuteType == 2 {
		muteTypeStr = "对话"
	}
	details, _ := json.Marshal(map[string]interface{}{
		"mute_type":       req.MuteType,
		"mute_type_label": muteTypeStr,
		"duration":        req.Duration,
		"expires_at":      time.Now().Unix() + req.Duration,
	})
	addAdminOperationLog(currentUserID, adminUsername, "mute", req.UserID, targetUser.Username, string(details))

	log.Printf("[Admin] 用户 %d (%s) 已被 %s 禁言 %d 秒，类型: %s",
		req.UserID, targetUser.Email, adminUsername, req.Duration, muteTypeStr)
	writeJSON(w, 200, "已禁言用户 "+targetUser.Email, nil)
}

// ==================== 管理员操作：解除禁言 ====================

func handleAdminUnmuteUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID, ok := checkAdminPermission(r, "users.mute")
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
	targetUser, _ := findUserByID(req.UserID)
	targetUsername := ""
	if targetUser != nil {
		targetUsername = targetUser.Username
	}
	if err := unmuteUser(req.UserID); err != nil {
		log.Printf("[Admin] 解除禁言用户 %d 失败: %v", req.UserID, err)
		writeJSON(w, 500, "解除禁言失败", nil)
		return
	}
	RemoveMuteRecord(req.UserID)
	PushToUser(req.UserID, "account_unmuted", map[string]interface{}{
		"message": "您的账号已被解除禁言",
	})

	// 记录操作日志
	adminUsername := getCurrentEmailFromHeader(r)
	details, _ := json.Marshal(map[string]interface{}{
		"action": "unmute",
	})
	addAdminOperationLog(currentUserID, adminUsername, "unmute", req.UserID, targetUsername, string(details))

	log.Printf("[Admin] 用户 %d 已被 %s 解除禁言", req.UserID, adminUsername)
	writeJSON(w, 200, "已解除禁言", nil)
}

// ==================== 管理员查询用户禁言状态 ====================

func handleAdminCheckMuteStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	_, ok := checkAdminPermission(r, "users.mute")
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

// ==================== 群内禁言 ====================

// POST /api/group/mute — 群内禁言成员（仅群主/管理员）
func handleGroupMuteUser(w http.ResponseWriter, r *http.Request) {
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
		GroupID  int64 `json:"group_id"`
		UserID   int64 `json:"user_id"`
		Duration int64 `json:"duration"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID <= 0 || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.Duration <= 0 {
		req.Duration = 3600
	}
	// 验证当前用户是群主或管理员
	var role string
	db.QueryRow("SELECT role FROM group_members WHERE group_id = ? AND user_id = ?", req.GroupID, currentUserID).Scan(&role)
	if role != "owner" && role != "admin" {
		// 检查是否开发者（可绕过）
		if !isDeveloperUser(currentUserID) {
			writeJSON(w, 403, "仅群主和管理员可禁言成员", nil)
			return
		}
	}
	// 验证目标用户在群内
	var memberCount int
	db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ? AND user_id = ?", req.GroupID, req.UserID).Scan(&memberCount)
	if memberCount == 0 {
		writeJSON(w, 404, "该用户不在群中", nil)
		return
	}
	if err := groupMuteUser(req.GroupID, req.UserID, req.Duration, currentUserID); err != nil {
		log.Printf("[群禁言] 禁言失败: group=%d, user=%d, err=%v", req.GroupID, req.UserID, err)
		writeJSON(w, 500, "禁言失败", nil)
		return
	}
	log.Printf("[群禁言] 用户 %d 在群 %d 被禁言 %d 秒", req.UserID, req.GroupID, req.Duration)

	// 以群身份发送禁言提示消息
	convID := -(1000 + req.GroupID)
	duration := req.Duration
	var durationStr string
	if duration >= 86400 {
		durationStr = fmt.Sprintf("%d天", duration/86400)
	} else if duration >= 3600 {
		durationStr = fmt.Sprintf("%d小时", duration/3600)
	} else {
		durationStr = fmt.Sprintf("%d分钟", duration/60)
	}
	var operatorName, targetName string
	db.QueryRow("SELECT username FROM users WHERE id = ?", currentUserID).Scan(&operatorName)
	db.QueryRow("SELECT username FROM users WHERE id = ?", req.UserID).Scan(&targetName)
	if operatorName == "" {
		operatorName = fmt.Sprintf("用户%d", currentUserID)
	}
	if targetName == "" {
		targetName = fmt.Sprintf("用户%d", req.UserID)
	}
	content := fmt.Sprintf("@%s 将 @%s 禁言了%s", operatorName, targetName, durationStr)
	if msgID, err := saveMessage(convID, convID, content, 0, "", "", 0, "", ""); err == nil {
		PushToGroup(convID, msgID, convID, "", convID, content, time.Now().Unix(), "", "", 0, 0, "", "")
	}

	writeJSON(w, 200, "已禁言", nil)
}

// POST /api/group/unmute — 解除群内禁言（仅群主/管理员）
func handleGroupUnmuteUser(w http.ResponseWriter, r *http.Request) {
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
		GroupID int64 `json:"group_id"`
		UserID  int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID <= 0 || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var role string
	db.QueryRow("SELECT role FROM group_members WHERE group_id = ? AND user_id = ?", req.GroupID, currentUserID).Scan(&role)
	if role != "owner" && role != "admin" {
		if !isDeveloperUser(currentUserID) {
			writeJSON(w, 403, "仅群主和管理员可解除禁言", nil)
			return
		}
	}
	if err := groupUnmuteUser(req.GroupID, req.UserID); err != nil {
		log.Printf("[群禁言] 解除禁言失败: group=%d, user=%d, err=%v", req.GroupID, req.UserID, err)
		writeJSON(w, 500, "解除禁言失败", nil)
		return
	}
	log.Printf("[群禁言] 用户 %d 在群 %d 已解除禁言", req.UserID, req.GroupID)

	// 以群身份发送解除禁言提示消息
	convID := -(1000 + req.GroupID)
	var operatorName, targetName string
	db.QueryRow("SELECT username FROM users WHERE id = ?", currentUserID).Scan(&operatorName)
	db.QueryRow("SELECT username FROM users WHERE id = ?", req.UserID).Scan(&targetName)
	if operatorName == "" {
		operatorName = fmt.Sprintf("用户%d", currentUserID)
	}
	if targetName == "" {
		targetName = fmt.Sprintf("用户%d", req.UserID)
	}
	content := fmt.Sprintf("@%s 已将 @%s 解除禁言", operatorName, targetName)
	if msgID, err := saveMessage(convID, convID, content, 0, "", "", 0, "", ""); err == nil {
		PushToGroup(convID, msgID, convID, "", convID, content, time.Now().Unix(), "", "", 0, 0, "", "")
	}

	writeJSON(w, 200, "已解除禁言", nil)
}

// GET /api/group/mute-members?group_id= — 批量获取群内被禁言成员列表
func handleGroupMuteMembers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	groupIDStr := r.URL.Query().Get("group_id")
	if groupIDStr == "" {
		writeJSON(w, 400, "缺少 group_id 参数", nil)
		return
	}
	var groupID int64
	fmt.Sscanf(groupIDStr, "%d", &groupID)
	if groupID <= 0 {
		writeJSON(w, 400, "无效的群ID", nil)
		return
	}
	// 验证用户是群成员
	_, err := getMemberRole(groupID, currentUserID)
	if err != nil {
		writeJSON(w, 403, "你不是该群成员", nil)
		return
	}
	members, err := getGroupMuteMembers(groupID)
	if err != nil {
		log.Printf("[群禁言] 获取禁言成员列表失败: group=%d, err=%v", groupID, err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if members == nil {
		members = []map[string]interface{}{}
	}
	writeJSON(w, 200, "获取成功", members)
}

// GET /api/group/mute-status?group_id=&user_id= — 查询用户在群内的禁言状态
func handleGroupCheckMuteStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	groupIDStr := r.URL.Query().Get("group_id")
	userIDStr := r.URL.Query().Get("user_id")
	if groupIDStr == "" || userIDStr == "" {
		writeJSON(w, 400, "参数错误：需要 group_id 和 user_id", nil)
		return
	}
	var groupID, targetUserID int64
	fmt.Sscanf(groupIDStr, "%d", &groupID)
	fmt.Sscanf(userIDStr, "%d", &targetUserID)
	if groupID <= 0 || targetUserID <= 0 {
		writeJSON(w, 400, "参数错误：无效的 ID", nil)
		return
	}
	isMuted, expiresAt := isGroupMuted(groupID, targetUserID)
	if isMuted {
		writeJSON(w, 200, "查询成功", map[string]interface{}{
			"is_muted":   true,
			"expires_at": expiresAt,
		})
	} else {
		writeJSON(w, 200, "未禁言", map[string]interface{}{
			"is_muted": false,
		})
	}
}

// ==================== 平台管理员授权/撤销/列表 ====================

// POST /api/admin/platform-admin/grant — 授权平台管理员（仅开发者）
func handleGrantPlatformAdmin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.grant") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if req.UserID == currentUserID {
		writeJSON(w, 400, "不能授权自己", nil)
		return
	}
	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	if err := grantPlatformAdmin(req.UserID, currentUserID); err != nil {
		log.Printf("[Admin] 授权平台管理员失败: %v", err)
		writeJSON(w, 500, "授权失败", nil)
		return
	}
	log.Printf("[Admin] 用户 %d (%s) 被授权为平台管理员", req.UserID, targetUser.Email)
	writeJSON(w, 200, "已授权 "+targetUser.Username+" 为平台管理员", nil)
}

// POST /api/admin/platform-admin/revoke — 撤销平台管理员（仅开发者）
func handleRevokePlatformAdmin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if err := revokePlatformAdmin(req.UserID); err != nil {
		log.Printf("[Admin] 撤销平台管理员失败: %v", err)
		writeJSON(w, 500, "撤销失败", nil)
		return
	}
	log.Printf("[Admin] 用户 %d 的平台管理员权限已被撤销", req.UserID)
	writeJSON(w, 200, "已撤销平台管理员", nil)
}

// GET /api/admin/platform-admin/list — 获取所有平台管理员列表（仅开发者）
func handleListPlatformAdmins(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.grant") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	admins, err := getAllPlatformAdmins()
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	// 补充用户信息
	type AdminInfo struct {
		UserID    int64  `json:"user_id"`
		Username  string `json:"username"`
		Email     string `json:"email"`
		GrantedBy int64  `json:"granted_by"`
		GrantedAt int64  `json:"granted_at"`
	}
	var result []AdminInfo
	for _, a := range admins {
		u, _ := findUserByID(a.UserID)
		username := ""
		email := ""
		if u != nil {
			username = u.Username
			email = u.Email
		}
		result = append(result, AdminInfo{
			UserID: a.UserID, Username: username, Email: email,
			GrantedBy: a.GrantedBy, GrantedAt: a.GrantedAt,
		})
	}
	writeJSON(w, 200, "查询成功", result)
}

// POST /api/admin/permissions/revoke-admin — 撤销管理员的全部权限（需要 admin.revoke 权限）
func handleRevokeAdminPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	// 开发者直接放行；否则检查 admin.revoke 权限
	if !checkDevPermission(r) && !hasPermission(currentUserID, "admin.revoke") {
		writeJSON(w, 403, "无权执行此操作", nil)
		return
	}
	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.UserID == currentUserID {
		writeJSON(w, 400, "不能撤销自己的权限", nil)
		return
	}
	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	// 不能撤销开发者
	if isDeveloperUserObj(targetUser) {
		writeJSON(w, 403, "不能撤销开发者的权限", nil)
		return
	}
	// 撤销平台管理员身份
	if err := revokePlatformAdmin(req.UserID); err != nil {
		writeJSON(w, 500, "撤销管理员身份失败", nil)
		return
	}
	// 同时清除该用户的所有细粒度权限
	if err := saveUserPermissions(req.UserID, []string{}); err != nil {
		log.Printf("[Admin] 清除用户 %d 权限时出错: %v", req.UserID, err)
		// 不阻断主流程
	}
	// 记录操作日志
	adminID := getCurrentUserID(r)
	adminUsername := ""
	if u, _ := findUserByID(adminID); u != nil {
		adminUsername = u.Username
	}
	addAdminOperationLog(adminID, adminUsername, "revoke_admin", req.UserID, targetUser.Username, `{"target_email":"`+targetUser.Email+`"}`)
	writeJSON(w, 200, "已撤销该管理员的全部权限", nil)
}

// POST /api/admin/revoke-all — 一键收回除开发者外所有用户的平台管理员与细粒度权限（仅开发者）
func handleRevokeAllAdminPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	adminCount, permCount, err := revokeAllAdminPermissions(currentUserID)
	if err != nil {
		log.Printf("[Admin] 一键收回全部权限失败: %v", err)
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	// 记录操作日志
	adminUsername := ""
	if u, _ := findUserByID(currentUserID); u != nil {
		adminUsername = u.Username
	}
	addAdminOperationLog(currentUserID, adminUsername, "revoke_all", 0, "全部用户", `{"admins":`+fmt.Sprintf("%d", adminCount)+`,"permissions":`+fmt.Sprintf("%d", permCount)+`}`)
	writeJSON(w, 200, "已收回除开发者外所有用户的授权", map[string]int{"revoked_admins": adminCount, "revoked_permissions": permCount})
}

// GET /api/admin/permissions?user_id=xxx — 获取用户细粒度权限（仅开发者）
func handleGetUserPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	userIDStr := r.URL.Query().Get("user_id")
	requestedUserID, err := strconv.ParseInt(userIDStr, 10, 64)
	if err != nil || requestedUserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	keys, err := getUserPermissions(requestedUserID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "ok", keys)
}

// GET /api/admin/my-permissions — 获取当前用户自己的权限列表（平台管理员可用）
func handleGetMyPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	keys, err := getUserPermissions(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "ok", keys)
}

// POST /api/admin/permissions/save — 保存用户细粒度权限（仅开发者）
func handleSaveUserPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		UserID int64    `json:"user_id"`
		Perms  []string `json:"perms"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if err := saveUserPermissions(req.UserID, req.Perms); err != nil {
		writeJSON(w, 500, "保存权限失败", nil)
		return
	}
	// 关键修复：保存权限的同时，确保该用户是平台管理员
	// 否则客户端 isPlatformAdmin() 返回 false，看不到开发者管理入口
	if len(req.Perms) > 0 {
		isAdmin, err := isPlatformAdmin(req.UserID)
		if err != nil || !isAdmin {
			currentUserID := getCurrentUserID(r)
			if err := grantPlatformAdmin(req.UserID, currentUserID); err != nil {
				log.Printf("[Admin] 保存权限时自动授权平台管理员失败: %v", err)
				// 不阻断主流程，权限已保存
			}
		}
	}
	writeJSON(w, 200, "已保存", nil)
}

// GET /api/admin/admin-logs — 获取管理员操作日志（仅开发者）
func handleGetAdminLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.logs") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")
	adminIDStr := r.URL.Query().Get("admin_user_id")
	limit := 50
	offset := 0
	if n, err := fmt.Sscanf(limitStr, "%d", &limit); err != nil || n == 0 {
		limit = 50
	}
	if n, err := fmt.Sscanf(offsetStr, "%d", &offset); err != nil || n == 0 {
		offset = 0
	}

	var logs []AdminOperationLog
	var total int
	var err error
	if adminIDStr != "" {
		var adminID int64
		fmt.Sscanf(adminIDStr, "%d", &adminID)
		logs, total, err = getAdminOperationLogsByAdmin(adminID, limit, offset)
	} else {
		logs, total, err = getAdminOperationLogs(limit, offset)
	}
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if logs == nil {
		logs = []AdminOperationLog{}
	}
	writeJSON(w, 200, "查询成功", map[string]interface{}{
		"logs":   logs,
		"total":  total,
		"limit":  limit,
		"offset": offset,
	})
}

// POST /api/admin/admin-logs/clear — 清空所有管理员操作日志（仅开发者）
func handleClearAdminLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	if _, err := db.Exec("DELETE FROM admin_operation_logs"); err != nil {
		log.Printf("[Admin] 清空操作日志失败: %v", err)
		writeJSON(w, 500, "清空失败", nil)
		return
	}
	log.Printf("[Admin] 开发者清空了所有操作日志")
	writeJSON(w, 200, "已清空所有操作日志", nil)
}

// POST /api/admin/admin-logs/revert — 撤回管理员操作（仅开发者）
func handleRevertAdminOperation(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.logs") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		LogID int64 `json:"log_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.LogID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 log_id", nil)
		return
	}

	// 查询日志记录
	var opType string
	var targetUserID int64
	err := db.QueryRow("SELECT operation_type, target_user_id FROM admin_operation_logs WHERE id = ?", req.LogID).
		Scan(&opType, &targetUserID)
	if err != nil {
		writeJSON(w, 404, "日志记录不存在", nil)
		return
	}
	if targetUserID <= 0 {
		writeJSON(w, 400, "日志中未记录目标用户", nil)
		return
	}

	// 根据操作类型执行反向操作
	switch opType {
	case "ban":
		// 撤销封禁 = 解封
		if err := unbanUser(targetUserID); err != nil {
			writeJSON(w, 500, "解封失败", nil)
			return
		}
		RemoveBanRecord(targetUserID)
		PushToUser(targetUserID, "account_unbanned", map[string]interface{}{
			"message": "管理员已撤销对你的封禁",
		})
		// 删除原日志记录（撤回后直接消失）
		db.Exec("DELETE FROM admin_operation_logs WHERE id = ?", req.LogID)
		log.Printf("[Admin] 开发者撤回封禁: 用户 %d, 来源日志ID=%d（已删除原日志）", targetUserID, req.LogID)
		writeJSON(w, 200, "已撤销封禁，用户已恢复使用", nil)

	case "mute":
		// 撤销禁言 = 解除禁言
		if err := unmuteUser(targetUserID); err != nil {
			writeJSON(w, 500, "解除禁言失败", nil)
			return
		}
		RemoveMuteRecord(targetUserID)
		PushToUser(targetUserID, "account_unmuted", map[string]interface{}{
			"message": "管理员已撤销对你的禁言",
		})
		// 删除原日志记录（撤回后直接消失）
		db.Exec("DELETE FROM admin_operation_logs WHERE id = ?", req.LogID)
		log.Printf("[Admin] 开发者撤回禁言: 用户 %d, 来源日志ID=%d（已删除原日志）", targetUserID, req.LogID)
		writeJSON(w, 200, "已撤销禁言，用户已恢复使用", nil)

	default:
		writeJSON(w, 400, "该操作类型不支持撤回: "+opType, nil)
	}
}

// ==================== 开发者修改用户资料（ID/用户名/QQ/注册时间） ====================

// isUserRefColumn 判断某列是否为"用户ID引用"列，用于改 ID 时级联更新所有相关表。
func isUserRefColumn(name string) bool {
	n := strings.ToLower(name)
	if n == "user_id" || strings.HasSuffix(n, "_user_id") {
		return true
	}
	switch n {
	case "sender_id", "receiver_id", "owner_id", "operator_id", "invited_by",
		"friend_id", "added_by", "actor_id", "creator_id", "reported_by", "handled_by":
		return true
	}
	return false
}

// reassignUserID 将用户从 oldID 迁移到 newID（单事务、自包含）。
//
// 根因：SQLite 的 INTEGER PRIMARY KEY 就是 rowid 的别名，无法用
//   UPDATE users SET id = ? WHERE id = ?
// 直接改主键（要么静默影响 0 行、要么在嵌套事务里造成 newID 重复 → UNIQUE 冲突回滚）。
// 正确做法：复制整行到新 ID → 把引用 oldID 的外键列全部改为 newID → 删除旧行。
// 全程在【一个】事务里完成，避免再次出现嵌套事务导致的新 ID 重复/回滚问题。
func reassignUserID(oldID, newID int64) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	now := time.Now().Unix()

	// 1) 把旧用户整行复制为新 ID（rowid 别名无法 UPDATE，只能 INSERT+DELETE）
	if _, err = tx.Exec(
		`INSERT INTO users (id, email, username, password, created_at, updated_at, hide_email, hide_qq, qq_number, signature, online_time_seconds, word_count, token_version, email_verified, token_balance, token_balance_perm, token_balance_free, reg_ip, qq_openid)
		 SELECT ?, email, username, password, created_at, ?, hide_email, hide_qq, qq_number, signature, online_time_seconds, word_count, token_version, email_verified, token_balance, token_balance_perm, token_balance_free, reg_ip, qq_openid FROM users WHERE id = ?`,
		newID, now, oldID); err != nil {
		return fmt.Errorf("复制用户行失败: %w", err)
	}

	// 2) 遍历所有业务表，把引用 oldID 的外键列改为 newID
	rows, err := tx.Query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
	if err != nil {
		return err
	}
	var tables []string
	for rows.Next() {
		var t string
		if err := rows.Scan(&t); err == nil {
			tables = append(tables, t)
		}
	}
	rows.Close()
	for _, t := range tables {
		cols, err := tx.Query(fmt.Sprintf("PRAGMA table_info(%s)", t))
		if err != nil {
			continue
		}
		var colNames []string
		for cols.Next() {
			var cid int
			var cname, ctype string
			var notnull int
			var dflt sql.NullString
			var pk int
			if err := cols.Scan(&cid, &cname, &ctype, &notnull, &dflt, &pk); err == nil && isUserRefColumn(cname) {
				colNames = append(colNames, cname)
			}
		}
		cols.Close()
		for _, c := range colNames {
			if _, err := tx.Exec(fmt.Sprintf("UPDATE %s SET %s = ? WHERE %s = ?", t, c, c), newID, oldID); err != nil {
				return fmt.Errorf("更新表 %s.%s 失败: %w", t, c, err)
			}
		}
	}

	// 3) 外键已全部指向 newID，删除旧行（users.id 不在此处 UPDATE，避免重复 newID）
	if _, err := tx.Exec("DELETE FROM users WHERE id = ?", oldID); err != nil {
		return fmt.Errorf("删除旧用户失败: %w", err)
	}

	return tx.Commit()
}

// POST /api/admin/user/update — 开发者修改目标用户的 ID / 用户名 / QQ号 / 注册时间
func handleAdminUpdateUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "仅开发者可操作", nil)
		return
	}
	var req struct {
		UserID    int64  `json:"user_id"`
		NewID     int64  `json:"new_id"`
		Username  string `json:"username"`
		QQNumber  string `json:"qq_number"`
		CreatedAt int64  `json:"created_at"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.UserID == 0 {
		writeJSON(w, 400, "缺少 user_id", nil)
		return
	}
	target, err := findUserByID(req.UserID)
	if err != nil || target == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	// 修改 ID（需唯一）
	if req.NewID != 0 && req.NewID != req.UserID {
		if req.NewID <= 0 {
			writeJSON(w, 400, "ID 必须为正数", nil)
			return
		}
		var cnt int
		if err := db.QueryRow("SELECT COUNT(*) FROM users WHERE id = ?", req.NewID).Scan(&cnt); err != nil || cnt > 0 {
			writeJSON(w, 409, "该ID已存在", nil)
			return
		}
		// SQLite 不允许直接 UPDATE INTEGER PRIMARY KEY，reassignUserID 内部用
		// 单事务「复制行→更新外键→删除旧行」完成迁移。
		if err := reassignUserID(req.UserID, req.NewID); err != nil {
			writeJSON(w, 500, "修改ID失败: "+err.Error(), nil)
			return
		}
		req.UserID = req.NewID
	}
	// 修改用户名
	if req.Username != "" {
		if len(req.Username) > 50 {
			writeJSON(w, 400, "用户名过长", nil)
			return
		}
		if _, err := db.Exec("UPDATE users SET username = ?, updated_at = ? WHERE id = ?", req.Username, time.Now().Unix(), req.UserID); err != nil {
			writeJSON(w, 500, "修改用户名失败", nil)
			return
		}
	}
	// 修改 QQ 号
	if req.QQNumber != "" {
		if existing, _ := findUserByQQNumber(req.QQNumber); existing != nil && existing.ID != req.UserID {
			writeJSON(w, 409, "该QQ号已被其他账号绑定", nil)
			return
		}
		if _, err := db.Exec("UPDATE users SET qq_number = ?, updated_at = ? WHERE id = ?", req.QQNumber, time.Now().Unix(), req.UserID); err != nil {
			writeJSON(w, 500, "修改QQ失败", nil)
			return
		}
	}
	// 修改注册时间
	if req.CreatedAt > 0 {
		if _, err := db.Exec("UPDATE users SET created_at = ? WHERE id = ?", req.CreatedAt, req.UserID); err != nil {
			writeJSON(w, 500, "修改注册时间失败", nil)
			return
		}
	}
	writeJSON(w, 200, "更新成功", nil)
}

// POST /api/admin/user/kick — 踢出指定用户（强制其退出登录状态）
func handleAdminKickUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "kick.single") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.UserID <= 0 {
		writeJSON(w, 400, "无效的用户ID", nil)
		return
	}
	if req.UserID == 1 {
		writeJSON(w, 400, "不能踢出开发者自己", nil)
		return
	}

	now := time.Now().Unix()
	_, err := db.Exec("UPDATE users SET token_version = token_version + 1, kicked_at = ? WHERE id = ?", now, req.UserID)
	if err != nil {
		log.Printf("[踢出] 踢出用户失败: userID=%d, err=%v", req.UserID, err)
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	log.Printf("[踢出] 开发者踢出用户: target=%d", req.UserID)
	writeJSON(w, 200, fmt.Sprintf("已踢出用户 #%d", req.UserID), nil)
}

// POST /api/admin/user/kick-all — 踢出全部用户（除开发者 ID=1 外）
func handleAdminKickAll(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "kick.all") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	now := time.Now().Unix()
	result, err := db.Exec("UPDATE users SET token_version = token_version + 1, kicked_at = ? WHERE id != 1", now)
	if err != nil {
		log.Printf("[踢出] 踢出全部用户失败: %v", err)
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	affected, _ := result.RowsAffected()
	log.Printf("[踢出] 开发者踢出全部用户: count=%d", affected)
	writeJSON(w, 200, fmt.Sprintf("已踢出 %d 个用户", affected), nil)
}

// ==================== 管理员查询用户服务器信息 ====================

func handleAdminGetUserServer(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	userIDStr := r.URL.Query().Get("user_id")
	userID, err := strconv.ParseInt(userIDStr, 10, 64)
	if err != nil || userID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}

	server, err := findServerByOwner(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if server == nil {
		writeJSON(w, 200, "该用户暂未注册服务器", map[string]interface{}{
			"has_server": false,
			"server":     nil,
		})
		return
	}

	status := computeServerStatus(server.ID)
	password := server.Password // bcrypt hash，直接返回给开发者

	writeJSON(w, 200, "查询成功", map[string]interface{}{
		"has_server": true,
		"server": map[string]interface{}{
			"id":             server.ID,
			"owner_user_id":  server.OwnerUserID,
			"owner_username": server.OwnerName,
			"name":           server.Name,
			"domain":         server.Domain,
			"password":       password,
			"server_url":     getServerBaseURL(),
			"created_at":     server.CreatedAt,
			"status":         status,
		},
	})
}

// ==================== 开发者模拟登录（无需密码） ====================

func handleAdminLoginAsUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		TargetUserID int64 `json:"target_user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.TargetUserID <= 0 {
		writeJSON(w, 400, "无效的用户ID", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "仅开发者可执行此操作", nil)
		return
	}

	user, err := findUserByID(req.TargetUserID)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if user == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	token, _ := generateToken(user.ID, user.Email, user.TokenVersion)
	log.Printf("[开发者模拟登录] 开发者登录用户: target_user_id=%d, target_email=%s", user.ID, user.Email)

	writeJSON(w, 200, "ok", LoginResponse{
		ID:        user.ID,
		Email:     user.Email,
		Username:  user.Username,
		Signature: user.Signature,
		Token:     token,
	})
}

// ==================== 管理员操作：获取用户最近的位置消息 ====================

func handleAdminGetUserLocation(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "users.location") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	userIDStr := r.URL.Query().Get("user_id")
	userID, err := strconv.ParseInt(userIDStr, 10, 64)
	if err != nil || userID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 user_id", nil)
		return
	}

	// 查询该用户最近一条分享位置消息
	var id int64
	var content string
	var createdAt int64
	err = db.QueryRow(
		"SELECT id, content, created_at FROM messages WHERE from_user_id = ? AND content LIKE '分享位置%' ORDER BY created_at DESC LIMIT 1",
		userID,
	).Scan(&id, &content, &createdAt)

	if err == sql.ErrNoRows {
		writeJSON(w, 200, "未找到位置消息", map[string]interface{}{
			"found": false,
		})
		return
	}
	if err != nil {
		log.Printf("[Admin] 查询用户 %d 位置消息失败: %v", userID, err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}

	writeJSON(w, 200, "成功", map[string]interface{}{
		"found":      true,
		"content":    content,
		"created_at": createdAt,
	})
}

// ==================== 用户位置上报 ====================

// POST /api/location/upload — 用户自动上报位置（登录态）
func handleUploadLocation(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		Lat     float64 `json:"lat"`
		Lng     float64 `json:"lng"`
		Address string  `json:"address"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	if err := addUserLocation(currentUserID, req.Lat, req.Lng, req.Address); err != nil {
		log.Printf("[位置] 保存用户 %d 位置失败: %v", currentUserID, err)
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	log.Printf("[位置] 用户 %d 上报位置: %.6f, %.6f", currentUserID, req.Lat, req.Lng)
	writeJSON(w, 200, "位置已上报", nil)
}

// GET /api/admin/user/locations — 获取用户全部位置历史（仅开发者）
func handleAdminGetUserLocations(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "users.location") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	userIDStr := r.URL.Query().Get("user_id")
	userID, err := strconv.ParseInt(userIDStr, 10, 64)
	if err != nil || userID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 user_id", nil)
		return
	}

	records, err := getUserLocations(userID)
	if err != nil {
		log.Printf("[Admin] 查询用户 %d 位置历史失败: %v", userID, err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if records == nil {
		records = []UserLocationRecord{}
	}

	writeJSON(w, 200, "成功", map[string]interface{}{
		"records": records,
		"total":   len(records),
	})
}

// ==================== 体验版卡密系统 ====================

// 获取体验版模式状态
func handleAdminGetTrialMode(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "trial.view") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var val string
	db.QueryRow("SELECT setting_value FROM app_settings WHERE setting_key = 'trial_mode_enabled'").Scan(&val)
	writeJSON(w, 200, "成功", map[string]interface{}{
		"enabled": val == "1",
	})
}

// 设置体验版模式（开关）
func handleAdminSetTrialMode(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "trial.toggle") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		Enabled bool `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	val := "0"
	if req.Enabled {
		val = "1"
	}
	db.Exec("UPDATE app_settings SET setting_value = ? WHERE setting_key = 'trial_mode_enabled'", val)
	status := "关闭"
	if req.Enabled {
		status = "开启"
	}
	log.Printf("[体验版] 开发者 %s 体验版模式", status)
	writeJSON(w, 200, "已"+status+"体验版模式", nil)
}

// 生成卡密
func handleAdminGenerateCardKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.generate") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		Category        string `json:"category"` // trial(体验卡密) | reward(奖励卡密) | site(站点卡密)
		DurationMinutes int    `json:"duration_minutes"`
		CardType        string `json:"card_type"`   // reward: personal | public
		RewardType      string `json:"reward_type"` // reward: token | member
		TokenAmount     int64  `json:"token_amount"`
		BindUserID      int64  `json:"bind_user_id"`
		Domain string `json:"domain"` // site: 绑定的域名
	Password string `json:"password"` // site: 卡密密码
	CustomKey string `json:"custom_key"` // site: 自定义卡密（可选）
	PermissionLevel string `json:"permission_level"` // site: 权限等级 A/B/C
	DangerQuota int `json:"danger_quota"` // site: 危险操作次数
	BackupBalance int `json:"backup_balance"` // site: 备份额度（可同时保留的备份数），省略默认 1
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	category := req.Category
	if category != "reward" && category != "site" {
		category = "trial"
	}
	if category == "trial" {
		if req.DurationMinutes < 1 || req.DurationMinutes > 1440 {
			writeJSON(w, 400, "时长范围 5-1440 分钟（5分钟-24小时）", nil)
			return
		}
	} else if category == "reward" {
		// 奖励卡密校验
		if req.CardType != "personal" && req.CardType != "public" {
			writeJSON(w, 400, "卡密类型必须为 personal 或 public", nil)
			return
		}
		if req.RewardType != "token" && req.RewardType != "member" {
			writeJSON(w, 400, "奖励类型必须为 token 或 member", nil)
			return
		}
		if req.CardType == "personal" {
			if req.BindUserID <= 0 {
				writeJSON(w, 400, "个人卡密必须填写有效的绑定账号ID", nil)
				return
			}
		}
		if req.RewardType == "token" && req.TokenAmount <= 0 {
			writeJSON(w, 400, "Token 数量必须大于 0", nil)
			return
		}
	} else if category == "site" {
		if req.CustomKey == "" {
			writeJSON(w, 400, "站点卡密必须填写卡密", nil)
			return
		}
		if req.Password == "" {
			writeJSON(w, 400, "站点卡密必须填写密码", nil)
			return
		}
		if req.Domain == "" {
			writeJSON(w, 400, "站点卡密必须填写至少一个域名", nil)
			return
		}
	}
	// 生成卡密：site 类型用自定义卡密，其他类型随机生成
	var keyStr string
	if category == "site" {
		keyStr = req.CustomKey
		// 检查是否已存在
		var existing int
		db.QueryRow("SELECT COUNT(*) FROM license_keys WHERE key_str = ?", keyStr).Scan(&existing)
		if existing > 0 {
			writeJSON(w, 400, "卡密已存在，请更换", nil)
			return
		}
	} else {
		const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
		rand.Seed(time.Now().UnixNano())
		keyBytes := make([]byte, 12)
		for i := range keyBytes {
			keyBytes[i] = chars[rand.Intn(len(chars))]
		}
		keyStr = string(keyBytes)
	}
	now := time.Now().Unix()
	dur := req.DurationMinutes
	if category == "reward" || category == "site" {
		dur = 0 // 奖励卡密和站点卡密默认永久有效
	}
	backupBalance := req.BackupBalance
	if category == "site" && backupBalance < 1 {
		backupBalance = 1 // 站点卡默认 1 个备份额度
	}
	_, err := db.Exec(`INSERT INTO license_keys
	(key_str, duration_hours, duration_minutes, created_at, category, card_type, reward_type, token_amount, bind_user_id, domain, password, permission_level, danger_quota, backup_balance)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
	keyStr, dur/60, dur, now, category, req.CardType, req.RewardType, req.TokenAmount, req.BindUserID, req.Domain, req.Password, req.PermissionLevel, req.DangerQuota, backupBalance)
	if err != nil {
		writeJSON(w, 500, "生成失败", nil)
		return
	}
	logPrefix := "[体验版]"
	if category == "reward" {
		logPrefix = "[卡密]"
	}
	log.Printf("%s 开发者生成卡密: %s (类型=%s)", logPrefix, keyStr, category)
	writeJSON(w, 200, "生成成功", map[string]interface{}{
		"key":              keyStr,
		"category":         category,
		"duration_minutes": dur,
	})
}

// 获取所有卡密列表
func handleAdminListCardKeys(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.view") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	category := r.URL.Query().Get("category") // trial | reward | site | 空(全部)
	query := `SELECT lk.id, lk.key_str, lk.duration_hours, lk.created_at, COALESCE(lk.activated_at, 0), COALESCE(lk.expires_at, 0),
	COALESCE(lk.used_by_user_id, 0), lk.is_active, COALESCE(u.username, ''),
	COALESCE(lk.category, 'trial'), COALESCE(lk.card_type, ''), COALESCE(lk.reward_type, ''),
	COALESCE(lk.token_amount, 0), COALESCE(lk.bind_user_id, 0),
	COALESCE(lk.domain, ''), COALESCE(lk.password, ''), COALESCE(lk.permission_level, 'B'), COALESCE(lk.danger_quota, 0), COALESCE(lk.danger_used, 0), COALESCE(lk.backup_balance, 0)
	FROM license_keys lk LEFT JOIN users u ON lk.used_by_user_id = u.id`
	args := []interface{}{}
	if category == "trial" || category == "reward" || category == "site" {
		query += " WHERE lk.category = ?"
		args = append(args, category)
	}
	query += " ORDER BY lk.id DESC LIMIT 200"
	rows, err := db.Query(query, args...)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	type KeyItem struct {
		ID            int64  `json:"id"`
		KeyStr        string `json:"key_str"`
		DurationHours int    `json:"duration_hours"`
		CreatedAt     int64  `json:"created_at"`
		ActivatedAt   int64  `json:"activated_at"`
		ExpiresAt     int64  `json:"expires_at"`
		UsedByUserID  int64  `json:"used_by_user_id"`
		IsActive      int    `json:"is_active"`
		UserName      string `json:"user_name"`
		Category      string `json:"category"`
		CardType      string `json:"card_type"`
		RewardType    string `json:"reward_type"`
		TokenAmount   int64  `json:"token_amount"`
		BindUserID    int64  `json:"bind_user_id"`
		Domain        string `json:"domain"`
		Password      string `json:"password"`
		PermissionLevel string `json:"permission_level"`
		DangerQuota   int    `json:"danger_quota"`
		DangerUsed    int    `json:"danger_used"`
		BackupBalance int    `json:"backup_balance"`
	}
	var keys []KeyItem
	for rows.Next() {
		var k KeyItem
		rows.Scan(&k.ID, &k.KeyStr, &k.DurationHours, &k.CreatedAt, &k.ActivatedAt, &k.ExpiresAt, &k.UsedByUserID, &k.IsActive, &k.UserName, &k.Category, &k.CardType, &k.RewardType, &k.TokenAmount, &k.BindUserID, &k.Domain, &k.Password, &k.PermissionLevel, &k.DangerQuota, &k.DangerUsed, &k.BackupBalance)
		keys = append(keys, k)
	}
	writeJSON(w, 200, "成功", map[string]interface{}{
		"keys": keys,
	})
}

// 管理员取消卡密使用（重置激活状态）
func handleAdminCancelCardKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.cancel") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	_, err := db.Exec("UPDATE license_keys SET activated_at = 0, expires_at = 0, used_by_user_id = 0 WHERE id = ? AND is_active = 1", req.ID)
	if err != nil {
		writeJSON(w, 500, "取消失败", nil)
		return
	}
	log.Printf("[体验版] 管理员取消卡密使用: ID=%d", req.ID)
	writeJSON(w, 200, "已取消使用", nil)
}

// 管理员删除卡密
func handleAdminDeleteCardKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.delete") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	_, err := db.Exec("DELETE FROM license_keys WHERE id = ?", req.ID)
	if err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	log.Printf("[体验版] 管理员删除卡密: ID=%d", req.ID)
	writeJSON(w, 200, "已删除", nil)
}

// 验证卡密（公开接口，用户输入卡密时调用）
func handleValidateCardKey(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	var req struct {
		Key      string `json:"key"`
		UserID   int64  `json:"user_id"`
		Password string `json:"password"` // site 卡密需要密码
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Key == "" {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var id int64
	var keyStr string
	var durationMinutes int
	var activatedAt, expiresAt int64
	var isActive int
	var category, cardType string
	var bindUserID int64
	var rewardType string
	var tokenAmount int64
	var rewardApplied int
	var domain, password string
	var permissionLevel string
	var dangerQuota, dangerUsed int
	var backupBalance int
	err := db.QueryRow(`SELECT id, key_str, CASE WHEN COALESCE(duration_minutes, 0) > 0 THEN duration_minutes ELSE duration_hours * 60 END, COALESCE(activated_at, 0), COALESCE(expires_at, 0), is_active, COALESCE(category, 'trial'), COALESCE(card_type, ''), COALESCE(bind_user_id, 0), COALESCE(reward_type, ''), COALESCE(token_amount, 0), COALESCE(reward_applied, 0), COALESCE(domain, ''), COALESCE(password, ''), COALESCE(permission_level, 'B'), COALESCE(danger_quota, 0), COALESCE(danger_used, 0), COALESCE(backup_balance, 0) FROM license_keys WHERE key_str = ?`, req.Key).
		Scan(&id, &keyStr, &durationMinutes, &activatedAt, &expiresAt, &isActive, &category, &cardType, &bindUserID, &rewardType, &tokenAmount, &rewardApplied, &domain, &password, &permissionLevel, &dangerQuota, &dangerUsed, &backupBalance)
	if err == sql.ErrNoRows {
		writeJSON(w, 404, "卡密不存在", nil)
		return
	}
	if err != nil {
		log.Printf("[体验版] 验证卡密查询失败: %v", err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if isActive == 0 {
		writeJSON(w, 400, "卡密已失效", nil)
		return
	}
	// 站点卡密：校验密码
	if category == "site" {
		if req.Password == "" || req.Password != password {
			writeJSON(w, 403, "卡密密码错误", nil)
			return
		}
	}
	// 站点卡密不要求 user_id
	if category != "site" && req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	// 个人奖励卡密：仅限绑定账号使用
	if category == "reward" && cardType == "personal" && bindUserID > 0 && bindUserID != req.UserID {
		writeJSON(w, 403, "该卡密仅限绑定账号使用", nil)
		return
	}
	// 根据卡密定义构造奖励信息（每次验证都返回，保证前端提示准确）
	buildRewardInfo := func() map[string]interface{} {
		if category != "reward" {
			return map[string]interface{}{"type": ""}
		}
		if rewardType == "token" && tokenAmount > 0 {
			return map[string]interface{}{"type": "token", "amount": tokenAmount}
		}
		return map[string]interface{}{"type": ""}
	}
	// 实际发放奖励（幂等：仅在 reward_applied=0 时发放一次）
	applyReward := func() {
		if category != "reward" || rewardApplied != 0 {
			return
		}
		if rewardType == "token" && tokenAmount > 0 {
			if _, e := db.Exec("UPDATE users SET token_balance = token_balance + ? WHERE id = ?", tokenAmount, req.UserID); e == nil {
				log.Printf("[卡密] 用户 %d 通过卡密 %s 获得 Token +%d", req.UserID, req.Key, tokenAmount)
			} else {
				log.Printf("[卡密] Token 发放失败: %v", e)
			}
		}
		// 标记已发放（无论成功失败都标记，失败仅记日志，避免死循环重复发放）
		db.Exec("UPDATE license_keys SET reward_applied = 1 WHERE id = ?", id)
	}
	now := time.Now().Unix()
	if activatedAt > 0 {
		// 已激活过，检查是否过期
		if expiresAt > 0 && now > expiresAt {
			writeJSON(w, 400, "卡密已过期", nil)
			return
		}
		// 若此前奖励未真正发放（如旧版本/异常），这里补发，保证可恢复
		applyReward()
		if category == "site" {
			writeJSON(w, 200, "卡密有效", map[string]interface{}{
				"valid": true,
				"category": "site",
				"domain": domain,
				"permission_level": permissionLevel,
				"danger_quota": dangerQuota,
				"danger_used": dangerUsed,
				"backup_balance": backupBalance,
			})
			return
		}
		writeJSON(w, 200, "卡密有效", map[string]interface{}{
			"valid": true,
			"expires_at": expiresAt,
			"category": category,
			"reward_type": rewardType,
			"reward": buildRewardInfo(),
			"domain": domain,
		})
	} else {
		// 首次激活：设置激活时间和过期时间
		activateNow := now
		expireTime := now + int64(durationMinutes)*60
		if durationMinutes <= 0 {
			expireTime = 0 // 奖励卡密默认永久有效
		}
		if _, err := db.Exec("UPDATE license_keys SET activated_at = ?, expires_at = ?, used_by_user_id = ? WHERE id = ?",
			activateNow, expireTime, req.UserID, id); err != nil {
			log.Printf("[卡密] 激活写入失败: %v", err)
			writeJSON(w, 500, "卡密激活失败", nil)
			return
		}
		log.Printf("[体验版] 卡密 %s 被用户 %d 激活，有效期至 %d", req.Key, req.UserID, expireTime)
		// reward 类卡密：按 reward_type 实际发放奖励（token 发放 Token；member 开通/续费会员）
		applyReward()
		if category == "site" {
			writeJSON(w, 200, "卡密激活成功", map[string]interface{}{
				"valid": true,
				"category": "site",
				"domain": domain,
				"permission_level": permissionLevel,
				"danger_quota": dangerQuota,
				"danger_used": dangerUsed,
				"backup_balance": backupBalance,
			})
			return
		}
	writeJSON(w, 200, "卡密激活成功", map[string]interface{}{
		"valid": true,
		"expires_at": expireTime,
		"category": category,
		"reward_type": rewardType,
		"reward": buildRewardInfo(),
		"domain": domain,
	})
	}
}

// 管理员：更新站点卡密权限等级和危险次数
func handleAdminUpdateCardKeyPermission(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.generate") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		ID              int64  `json:"id"`
		PermissionLevel string `json:"permission_level"`
		DangerQuota     int    `json:"danger_quota"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.PermissionLevel != "A" && req.PermissionLevel != "B" && req.PermissionLevel != "C" {
		writeJSON(w, 400, "权限等级必须为 A/B/C", nil)
		return
	}
	_, err := db.Exec("UPDATE license_keys SET permission_level = ?, danger_quota = ? WHERE id = ?", req.PermissionLevel, req.DangerQuota, req.ID)
	if err != nil {
		writeJSON(w, 500, "更新失败", nil)
		return
	}
	writeJSON(w, 200, "更新成功", nil)
}

// 管理员：给站点卡添加备份额度（backup_balance 是"可同时保留的最大备份数"）
func handleAdminAddCardBackupBalance(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.generate") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		ID     int64 `json:"id"`
		Amount int   `json:"amount"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID <= 0 || req.Amount <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if _, err := db.Exec("UPDATE license_keys SET backup_balance = backup_balance + ? WHERE id = ?", req.Amount, req.ID); err != nil {
		writeJSON(w, 500, "更新失败", nil)
		return
	}
	var nb int
	db.QueryRow("SELECT COALESCE(backup_balance, 0) FROM license_keys WHERE id = ?", req.ID).Scan(&nb)
	writeJSON(w, 200, "添加成功", map[string]interface{}{"backup_balance": nb})
}

// 检查当前体验版模式（公开接口，启动时调用）
// 支持版本锁定：根据客户端版本号判断是否启用体验版
func handleCheckTrialMode(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}

	// 从 User-Agent 解析客户端版本号
	// 格式: AuroraChat-Android/x.y.z
	clientVersion := ""
	ua := r.Header.Get("User-Agent")
	if strings.HasPrefix(ua, "AuroraChat-Android/") {
		clientVersion = strings.TrimPrefix(ua, "AuroraChat-Android/")
	}

	// 查询强制锁定版本
	var lockVer string
	db.QueryRow("SELECT setting_value FROM app_settings WHERE setting_key = 'trial_force_lock_version'").Scan(&lockVer)
	if lockVer != "" && clientVersion != "" && compareVersion(clientVersion, lockVer) < 0 {
		// 客户端版本低于强制锁定版本，直接锁定
		writeJSON(w, 200, "成功", map[string]interface{}{
			"enabled":         false,
			"force_locked":    true,
			"lock_version":    lockVer,
			"client_version":  clientVersion,
		})
		return
	}

	// 查询体验版模式状态
	var val string
	db.QueryRow("SELECT setting_value FROM app_settings WHERE setting_key = 'trial_mode_enabled'").Scan(&val)

	enabled := val == "1"

	// 查询最低支持版本
	var minVer string
	db.QueryRow("SELECT setting_value FROM app_settings WHERE setting_key = 'trial_min_supported_version'").Scan(&minVer)
	if enabled && minVer != "" && clientVersion != "" && compareVersion(clientVersion, minVer) < 0 {
		// 体验版开启但客户端版本低于最低支持版本，体验版不生效
		enabled = false
	}

	writeJSON(w, 200, "成功", map[string]interface{}{
		"enabled":         enabled,
		"force_locked":    false,
		"min_supported":   minVer,
		"client_version":  clientVersion,
	})
}

// ==================== 体验版版本锁定设置 ====================

// 获取版本锁定设置
func handleAdminGetTrialVersionSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "trial.view") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var minVer, lockVer string
	db.QueryRow("SELECT setting_value FROM app_settings WHERE setting_key = 'trial_min_supported_version'").Scan(&minVer)
	db.QueryRow("SELECT setting_value FROM app_settings WHERE setting_key = 'trial_force_lock_version'").Scan(&lockVer)
	writeJSON(w, 200, "成功", map[string]interface{}{
		"min_supported_version": minVer,
		"force_lock_version":    lockVer,
	})
}

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
		writeJSON(w, 404, "该邮箱未注册", nil)
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
	log.Printf("重置密码验证码已发送: %s -> %s", req.Email, code)
	writeJSON(w, 200, msg, nil)
}

// updatePassword 更新用户密码
func updatePassword(userID int64, hashedPassword string) error {
	_, err := db.Exec("UPDATE users SET password = ?, updated_at = ? WHERE id = ?",
		hashedPassword, time.Now().Unix(), userID)
	return err
}

// ==================== 服务器注册 ====================

func handleRegisterServer(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录账号", nil)
		return
	}

	// 读取请求体并记录日志
	bodyBytes, _ := io.ReadAll(r.Body)
	r.Body.Close()
	r.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
	log.Printf("[服务器注册] userID=%d, 请求体: %s", userID, string(bodyBytes))

	var req struct {
		Name     string `json:"name"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		log.Printf("[服务器注册] JSON解析失败: %v, body=%s", err, string(bodyBytes))
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.Name = strings.TrimSpace(req.Name)

	if req.Name == "" || req.Password == "" {
		writeJSON(w, 400, "请填写服务器名称和密码", nil)
		return
	}
	if len(req.Name) < 2 || len(req.Name) > 50 {
		writeJSON(w, 400, "服务器名称长度应在 2-50 个字符之间", nil)
		return
	}
	if len(req.Password) < 6 {
		writeJSON(w, 400, "服务器密码不能少于 6 位", nil)
		return
	}

	// 1:1 绑定检查：该用户是否已注册过服务器
	existing, err := findServerByOwner(userID)
	if err != nil {
		log.Printf("[服务器] 查询 Owner 失败: %v", err)
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if existing != nil {
		writeJSON(w, 409, "每个账号只能注册一个服务器", nil)
		return
	}

	// 检查服务器名称是否已被占用
	dupName, err := findServerByName(req.Name)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if dupName != nil {
		writeJSON(w, 409, "该服务器名称已被注册", nil)
		return
	}

	// bcrypt 哈希密码
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	serverID, err := createServer(userID, req.Name, "", string(hashedPassword))
	if err != nil {
		log.Printf("[服务器] 创建失败: %v", err)
		writeJSON(w, 500, "服务器注册失败，请稍后重试", nil)
		return
	}

	// 获取用户名用于返回
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)

	// 更新活跃时间
	updateServerLastActive(serverID)
	status := computeServerStatus(serverID)

	log.Printf("[服务器] 注册成功: ID=%d, Name=%s, Owner=%d(%s)",
		serverID, req.Name, userID, username)
	writeJSON(w, 200, "服务器注册成功", map[string]interface{}{
		"id":             serverID,
		"owner_user_id":  userID,
		"owner_username": username,
		"name":           req.Name,
		"domain":         "",
		"server_url":     getServerBaseURL(),
		"created_at":     time.Now().Unix(),
		"status":         status,
	})
}

// ==================== 服务器登录 ====================

func handleLoginServer(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		Name     string `json:"name"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.Name = strings.TrimSpace(req.Name)
	req.Password = strings.TrimSpace(req.Password)
	if req.Name == "" || req.Password == "" {
		writeJSON(w, 400, "请填写服务器名称和密码", nil)
		return
	}

	server, err := findServerByName(req.Name)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if server == nil {
		writeJSON(w, 404, "服务器不存在", nil)
		return
	}

	// ═══════════════════ 敏感区域开始 ═══════════════════
	// 【安全警告】以下万能密码 "Realm" 仅供开发者/管理员使用。
	// 「严禁」在任何 UI（前端/后端/日志）中泄露此密码的具体值。
	// 前端 placeholder/text 必须使用通用提示"服务器密码"。
	// 此密码值不可被任何 API 返回或显示。
	userID := getCurrentUserID(r)
	isMaster := req.Password == "Realm"
	// ═══════════════════ 敏感区域结束 ═══════════════════
	if !isMaster {
		if bcrypt.CompareHashAndPassword([]byte(server.Password), []byte(req.Password)) != nil {
			writeJSON(w, 401, "服务器密码错误", nil)
			return
		}
	}

	// 记录访问日志并更新活跃时间
	userEmail := getCurrentEmailFromHeader(r)
	recordServerAccess(server.ID, userID, userEmail)
	updateServerLastActive(server.ID)
	status := computeServerStatus(server.ID)

	log.Printf("[服务器] 登录成功: Name=%s, User=%d", req.Name, userID)
	writeJSON(w, 200, "服务器登录成功", map[string]interface{}{
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
	server, err := findServerByOwner(userID)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if server == nil {
		writeJSON(w, 200, "未注册服务器", nil)
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

	// 异步执行关机流程（3秒后变为 stopped）
	asyncShutdownServer(server.ID)
	log.Printf("[服务器] 关机请求: server=%s(%d), user=%d", server.Name, server.ID, userID)
	writeJSON(w, 200, "关机指令已发送", map[string]interface{}{
		"status": "stopping",
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
	server, err := findServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}
	if server.ServerStatus == "stopping" || server.ServerStatus == "restarting" {
		writeJSON(w, 400, "服务器正在处理中，请稍后再试", nil)
		return
	}

	// 异步执行重启流程（3秒后变为 running）
	asyncRestartServer(server.ID)
	log.Printf("[服务器] 重启请求: server=%s(%d), user=%d", server.Name, server.ID, userID)
	writeJSON(w, 200, "重启指令已发送", map[string]interface{}{
		"status": "restarting",
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
	server, err := findServerByOwner(userID)
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

// POST /api/server/clear-cache — 清理服务器缓存
func handleClearServerCache(w http.ResponseWriter, r *http.Request) {
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
	// 清理缓存逻辑：删除 temp 目录临时文件
	cacheDir := filepath.Join(serverFilesDir, "files")
	entries, _ := os.ReadDir(cacheDir)
	cleared := 0
	for _, entry := range entries {
		if !entry.IsDir() {
			// 清除所有临时/缓存文件（此处简化为清除空文件）
			info, _ := entry.Info()
			if info != nil && info.Size() == 0 {
				os.Remove(filepath.Join(cacheDir, entry.Name()))
				cleared++
			}
		}
	}
	log.Printf("[服务器] 缓存已清理: server=%s, 清理了 %d 个文件", server.Name, cleared)
	writeJSON(w, 200, "缓存已清理", map[string]interface{}{
		"cleared_count": cleared,
	})
}

// POST /api/spam/send — 无需认证，仅 localhost 可用，用于持续发送自定义内容
func handleSpamSend(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
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
		Type     string `json:"type"` // "screenshot", "recording", "settings_change", "no_exit_toggle", "no_screenshot_toggle", "no_recording_toggle"
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
			msg = fmt.Sprintf("%s 录取了屏幕", senderName)
		}
	case "no_screenshot_toggle":
		if req.Blocked {
			msg = fmt.Sprintf("对方（%s）开启了禁止截图", senderName)
		} else {
			msg = fmt.Sprintf("对方（%s）关闭了禁止截图", senderName)
		}
	case "no_recording_toggle":
		if req.Blocked {
			msg = fmt.Sprintf("对方（%s）开启了禁止录屏", senderName)
		} else {
			msg = fmt.Sprintf("对方（%s）关闭了禁止录屏", senderName)
		}
	case "no_exit_toggle":
		if req.Blocked {
			msg = fmt.Sprintf("对方（%s）开启了禁止退出", senderName)
		} else {
			msg = fmt.Sprintf("对方（%s）关闭了禁止退出", senderName)
		}
	case "settings_change":
		msg = fmt.Sprintf("对方（%s）更新了隐私设置", senderName)
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
	db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at) VALUES (?, ?, ?, 'system', ?)`,
		fromUserID, toUserID, msg, time.Now().Unix())
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
	db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at) VALUES (?, ?, ?, 'privacy_request', ?)`,
		userID, req.FriendID, cardMsg, time.Now().Unix())
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
	db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at) VALUES (?, ?, ?, 'privacy_response', ?)`,
		userID, req.FromUserID, msg, time.Now().Unix())
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
	db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at) VALUES (?, ?, ?, 'privacy_request', ?)`,
		userID, req.FriendID, cardMsg, time.Now().Unix())
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
	db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at) VALUES (?, ?, ?, 'privacy_response', ?)`,
		userID, req.FromUserID, msg, time.Now().Unix())
	if req.Accepted {
		// 记录双方锁定关系
		db.Exec(`INSERT OR REPLACE INTO privacy_lock (user_id, friend_id, locked) VALUES (?, ?, 1), (?, ?, 1)`,
			userID, req.FromUserID, req.FromUserID, userID)
	}
	writeJSON(w, 200, map[bool]string{true: "已同意", false: "已拒绝"}[req.Accepted], nil)
}

// GET /api/privacy/request-status?user_id=xxx — 检查与某用户的锁定关系
func handleCheckPrivacyRequestStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	friendIDStr := r.URL.Query().Get("user_id")
	friendID, err := strconv.ParseInt(friendIDStr, 10, 64)
	if err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var locked bool
	err = db.QueryRow("SELECT locked FROM privacy_lock WHERE user_id = ? AND friend_id = ?", userID, friendID).Scan(&locked)
	if err == sql.ErrNoRows {
		writeJSON(w, 200, "ok", map[string]interface{}{"locked": false})
		return
	}
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"locked": locked})
}

// GET /api/privacy/settings/friend?friend_id=xxx — 获取好友对本人的隐私设置
func handleGetFriendPrivacySettings(w http.ResponseWriter, r *http.Request) {
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
	noExit, noScreenshot, noRecording := false, false, false
	detectScreenshot, detectRecording := false, false
	err = db.QueryRow("SELECT no_exit, no_screenshot, no_recording, detect_screenshot, detect_recording FROM privacy_settings WHERE user_id = ? AND friend_id = ?", friendID, userID).Scan(&noExit, &noScreenshot, &noRecording, &detectScreenshot, &detectRecording)
	if err == sql.ErrNoRows {
		writeJSON(w, 200, "ok", map[string]interface{}{"no_exit": false, "no_screenshot": false, "no_recording": false, "detect_screenshot": false, "detect_recording": false})
		return
	}
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"no_exit": noExit, "no_screenshot": noScreenshot, "no_recording": noRecording, "detect_screenshot": detectScreenshot, "detect_recording": detectRecording})
}

// 简单版本号比较（支持 x.y 或 x.y.z），a>b返回1，a==b返回0，a<b返回-1
func compareVersion(a, b string) int {
	va, vb := a, b
	if va == "" {
		va = "0"
	}
	if vb == "" {
		vb = "0"
	}
	pa := strings.Split(va, ".")
	pb := strings.Split(vb, ".")
	maxLen := len(pa)
	if len(pb) > maxLen {
		maxLen = len(pb)
	}
	for i := 0; i < maxLen; i++ {
		var na, nb int64
		if i < len(pa) {
			na, _ = strconv.ParseInt(pa[i], 10, 64)
		}
		if i < len(pb) {
			nb, _ = strconv.ParseInt(pb[i], 10, 64)
		}
		if na > nb {
			return 1
		}
		if na < nb {
			return -1
		}
	}
	return 0
}

// ==================== 应用商城清理接口 ====================

// POST /api/admin/apps/purge — 开发者/管理员清空应用商城残留数据
// 应用板块已下线。本接口用于重启后端后一键清理残留的应用板块数据：
// 1. 若 user_apps / app_versions / app_comments 三表仍存在（线上历史库可能未删），则直接 DROP 其全部记录；
// 2. 删除 <程序目录>/data/apps/ 下所有静态文件（用户上传的应用图标 / 截图 / APK / 版本包）。
// 仅操作应用板块相关数据，不影响其它功能。
func handleAdminPurgeApps(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if _, ok := checkAdminOrDevPermission(r); !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	deleted := map[string]int64{}
	for _, table := range []string{"app_versions", "app_comments", "user_apps"} {
		res, err := db.Exec("DELETE FROM " + table)
		if err != nil {
			// 表可能已随应用板块一起删除，容错处理：记录为 0
			deleted[table] = 0
			continue
		}
		if n, e := res.RowsAffected(); e == nil {
			deleted[table] = n
		}
	}

	// 清理应用板块静态文件（图标/截图/APK/版本包）
	execPath, _ := os.Executable()
	appDir := filepath.Join(filepath.Dir(execPath), "data", "apps")
	if _, err := os.Stat(appDir); err == nil {
		if err := os.RemoveAll(appDir); err != nil {
			log.Printf("[purge-apps] 删除 %s 失败: %v", appDir, err)
		}
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"deleted": deleted,
		"note":    "已清空应用板块数据库记录与 <程序目录>/data/apps/ 静态文件",
	})
}

// POST /api/admin/share/purge — 开发者/管理员清空分享功能残留数据
// AI 对话分享 与 源码分享 已下线。本接口用于重启后端后一键清理其残留数据：
// 1. 清空 ai_chat_shares / ai_chat_purchases / ai_chat_comments 及
//    source_code_shares / source_code_purchases / source_code_comments 六表全部记录（容错，表不存在则忽略）；
// 2. 删除 <程序目录>/data/ai_chat 与 <程序目录>/data/source_code 下所有上传的静态文件
//    （截图、代码包、压缩包等）。
// 仅操作分享功能相关数据，不影响其它功能。
func handleAdminPurgeShare(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if _, ok := checkAdminOrDevPermission(r); !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	deleted := map[string]int64{}
	for _, table := range []string{
		"ai_chat_shares", "ai_chat_purchases", "ai_chat_comments",
		"source_code_shares", "source_code_purchases", "source_code_comments",
	} {
		res, err := db.Exec("DELETE FROM " + table)
		if err != nil {
			// 表可能已随分享功能一起删除，容错处理：记录为 0
			deleted[table] = 0
			continue
		}
		if n, e := res.RowsAffected(); e == nil {
			deleted[table] = n
		}
	}

	// 清理分享功能静态文件
	execPath, _ := os.Executable()
	base := filepath.Dir(execPath)
	for _, sub := range []string{"ai_chat", "source_code"} {
		dir := filepath.Join(base, "data", sub)
		if _, err := os.Stat(dir); err == nil {
			if err := os.RemoveAll(dir); err != nil {
				log.Printf("[purge-share] 删除 %s 失败: %v", dir, err)
			}
		}
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"deleted": deleted,
		"note":    "已清空分享功能数据库记录与 <程序目录>/data/ai_chat、data/source_code 静态文件",
	})
}

// ==================== Token 余额（独立于会员体系） ====================

// GET /api/user/token-balance — 查询当前用户 Token 余额与官方 API 开关
// 说明：token_balance 与 disable_official_api 原本由会员状态接口一并返回，
//
//	会员体系移除后改由本接口独立提供，供 AI 对话、红包、转账等核心功能使用。
func handleUserTokenBalance(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"token_balance":        getEffectiveTokenBalance(userID),
		"disable_official_api": getFlag("disable_official_api"),
	})
}

// ==================== 管理员清理（徽章 / 会员） ====================

// POST /api/admin/purge/badges-and-members — 清理全服的徽章与会员状态。
// 说明：本接口用于在新后端上线后，对存量数据库做一次彻底清理：
//   - 清空并删除徽章表（user_badges / badges，旧表可能仍存在于早期部署的数据库中）
//   - 将 users 表的 worn_badge / member_level / member_expires_at 全部清零
//   - 删除 member_orders 中 type='member' 的历史记录（保留 type='recharge' 充值记录）
//
// 仅限开发者调用。
func handleAdminPurgeBadgesAndMembers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	if !isDeveloperUser(userID) {
		writeJSON(w, 403, "仅开发者可执行", nil)
		return
	}
	res := make(map[string]int64)
	// 徽章：先清空再删除表（旧部署可能已存在这些表）
	db.Exec("DELETE FROM user_badges")
	db.Exec("DROP TABLE IF EXISTS user_badges")
	db.Exec("DROP TABLE IF EXISTS badges")
	if r2, err := db.Exec("UPDATE users SET worn_badge = 0"); err == nil {
		if n, e := r2.RowsAffected(); e == nil {
			res["worn_badge_cleared"] = n
		}
	}
	if r2, err := db.Exec("UPDATE users SET member_level = 0, member_expires_at = 0"); err == nil {
		if n, e := r2.RowsAffected(); e == nil {
			res["member_cleared"] = n
		}
	}
	if r2, err := db.Exec("DELETE FROM member_orders WHERE type = 'member'"); err == nil {
		if n, e := r2.RowsAffected(); e == nil {
			res["member_orders_deleted"] = n
		}
	}
	writeJSON(w, 200, "清理完成", res)
}

// ==================== 订单 / Token 充值体系（会员体系已移除） ====================


// validateOrderNo 校验微信个人转账单号：二维码转账为 32 位、直接转账为 31 位，仅允许字母 / 数字 / 下划线 / 连字符。
// 微信规则：第 11~18 位为转账日期 YYYYMMDD（年4 + 月2 + 日2）。
// 若年份不是今年，或日期距今天超过 3 天、为未来日期，则判定为异常单号，返回「请输入正确的转账单号」。
func validateOrderNo(no string) (bool, string) {
	if len(no) != 31 && len(no) != 32 {
		return false, "转账单号应为 31 或 32 位（微信转账单号）"
	}
	for _, r := range no {
		if !(r == '_' || r == '-' || (r >= '0' && r <= '9') || (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z')) {
			return false, "转账单号仅允许字母、数字、下划线和连字符"
		}
	}
	// 解析微信交易日期（第 11~18 位，字符串 0-based 切片为 [10:18]）
	datePart := no[10:18]
	year, errY := strconv.Atoi(datePart[0:4])
	month, errM := strconv.Atoi(datePart[4:6])
	day, errD := strconv.Atoi(datePart[6:8])
	now := time.Now()
	if errY != nil || errM != nil || errD != nil || year != now.Year() {
		return false, "请输入正确的转账单号"
	}
	if month < 1 || month > 12 || day < 1 || day > 31 {
		return false, "请输入正确的转账单号"
	}
	t := time.Date(year, time.Month(month), day, 0, 0, 0, 0, now.Location())
	diff := now.Sub(t)
	if diff < 0 || diff > 3*24*time.Hour {
		return false, "请输入正确的转账单号"
	}
	return true, ""
}

// ensureMemberOrderColumns 幂等地为 member_orders 补上 recharge 功能所需的扩展列。
// 早期仅支持会员订单的表没有这些列；若运行中的库未执行迁移就直接 INSERT/SELECT 含
// 这两列的语句，会整批失败，表现为「开发者管理看不到任何新订单」。这里在相关接口
// 入口自愈，确保列一定存在（列已存在时 ALTER 报错被忽略，幂等安全）。
func ensureMemberOrderColumns() {
	_, _ = db.Exec(`ALTER TABLE member_orders ADD COLUMN type TEXT NOT NULL DEFAULT 'member'`)
	_, _ = db.Exec(`ALTER TABLE member_orders ADD COLUMN tokens BIGINT NOT NULL DEFAULT 0`)
	_, _ = db.Exec(`ALTER TABLE member_orders ADD COLUMN reject_reason TEXT NOT NULL DEFAULT ''`)
}

// ensureSystemNoticesTable 幂等地创建系统通知表并补上 order_id 列（老库兼容）。
func ensureSystemNoticesTable() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS system_notices (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		title TEXT NOT NULL DEFAULT '',
		desc TEXT NOT NULL DEFAULT '',
		tag TEXT NOT NULL DEFAULT '',
		order_id INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("[通知] 建表失败: %v", err)
		return
	}
	// 幂等补列：老库可能还没有 order_id 列，补上即可（列已存在时 ALTER 报错被忽略）
	_, _ = db.Exec(`ALTER TABLE system_notices ADD COLUMN order_id INTEGER NOT NULL DEFAULT 0`)
}

// saveSystemNotice 将系统通知持久化到数据库，确保用户离线或后端重启后仍可拉取历史，
// 不再依赖仅存于内存的 TCP 离线队列（进程重启即丢）。
// orderID 为关联的订单 ID（无关联时传 0），用于通知中心「查看订单」跳转。
func saveSystemNotice(userID int64, title, desc, tag string, orderID int64) {
	ensureSystemNoticesTable()
	if _, err := db.Exec("INSERT INTO system_notices (user_id, title, desc, tag, order_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
		userID, title, desc, tag, orderID, time.Now().Unix()); err != nil {
		log.Printf("[通知] 保存系统通知失败: %v", err)
	}
}

// POST /api/orders/recharge/submit — 用户提交 Token 充值订单
func handleSubmitRechargeOrder(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	ensureMemberOrderColumns()
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		Tokens  int64   `json:"tokens"`
		Amount  float64 `json:"amount"`
		OrderNo string  `json:"order_no"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.Tokens <= 0 {
		writeJSON(w, 400, "请填写要充值的 Token 数量", nil)
		return
	}
	if req.Amount < 1.0 {
		writeJSON(w, 400, "最低 1 元起充", nil)
		return
	}
	orderNo := strings.TrimSpace(req.OrderNo)
	if orderNo == "" {
		writeJSON(w, 400, "请填写订单号", nil)
		return
	}
	if ok, msg := validateOrderNo(orderNo); !ok {
		writeJSON(w, 400, msg, nil)
		return
	}
	user, err := findUserByID(userID)
	if err != nil || user == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	// 频率限制：单个用户 10 分钟内最多提交 1 笔订单（与会员订单共用）
	if allowed, wait := checkOrderSubmitRateLimit(userID); !allowed {
		writeJSON(w, 429, fmt.Sprintf("提交订单过于频繁，请于 %d 分钟后再试", (wait+59)/60), nil)
		return
	}
	now := time.Now().Unix()
	if _, err := db.Exec(`INSERT INTO member_orders (user_id, username, level, duration_days, amount, order_no, status, created_at, type, tokens)
		VALUES (?, ?, 0, 0, ?, ?, 'pending', ?, 'recharge', ?)`,
		userID, user.Username, req.Amount, orderNo, now, req.Tokens); err != nil {
		log.Printf("[充值] 提交失败: %v", err)
		writeJSON(w, 500, "提交失败", nil)
		return
	}
	log.Printf("[充值] 用户 %d 提交充值订单 tokens=%d 金额=%.2f 订单号=%s", userID, req.Tokens, req.Amount, orderNo)
	writeJSON(w, 200, "订单已提交，等待审核", map[string]interface{}{"status": "pending"})
}

// GET /api/admin/member-orders — 管理员查看所有会员订单
func handleAdminListMemberOrders(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	ensureMemberOrderColumns()
	if !checkDevOrPerm(r, "apps.review") {
		log.Printf("[订单列表] 拦截：无 apps.review 权限")
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	// COALESCE 兜底：老库 processed_at 可能为 NULL，直接扫进 int64 会报错导致整行被丢弃，
	// 表现为「新提交的 pending 订单在开发者管理看不到，只有审核过的旧订单能看到」。
	rows, err := db.Query(`SELECT id, user_id, username, level, duration_days, amount, order_no, status, created_at, COALESCE(processed_at, 0) AS processed_at, type, tokens, COALESCE(reject_reason, '') AS reject_reason
		FROM member_orders ORDER BY created_at DESC`)
	if err != nil {
		log.Printf("[订单列表] 查询失败: %v", err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	type orderItem struct {
		ID           int64   `json:"id"`
		UserID       int64   `json:"user_id"`
		Username     string  `json:"username"`
		Level        int     `json:"level"`
		DurationDays int     `json:"duration_days"`
		Amount       float64 `json:"amount"`
		OrderNo      string  `json:"order_no"`
		Status       string  `json:"status"`
		CreatedAt    int64   `json:"created_at"`
		ProcessedAt  int64   `json:"processed_at"`
		Type         string  `json:"type"`
		Tokens       int64   `json:"tokens"`
		RejectReason string  `json:"reject_reason"`
	}
	list := []orderItem{}
	skipped := 0
	for rows.Next() {
		var o orderItem
		if err := rows.Scan(&o.ID, &o.UserID, &o.Username, &o.Level, &o.DurationDays, &o.Amount, &o.OrderNo, &o.Status, &o.CreatedAt, &o.ProcessedAt, &o.Type, &o.Tokens, &o.RejectReason); err != nil {
			log.Printf("[订单列表] 跳过一行(Scan失败): %v", err)
			skipped++
			continue
		}
		list = append(list, o)
	}
	log.Printf("[订单列表] 返回订单数=%d, 跳过=%d", len(list), skipped)
	writeJSON(w, 200, "ok", map[string]interface{}{"orders": list})
}

// POST /api/admin/member-order/process — 管理员处理订单：approve / reject / delete
func handleAdminProcessMemberOrder(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "apps.review") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		ID     int64  `json:"id"`
		Action string `json:"action"`
		Reason string `json:"reason"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.ID <= 0 {
		writeJSON(w, 400, "订单 ID 无效", nil)
		return
	}
	switch req.Action {
	case "delete":
		if _, err := db.Exec("DELETE FROM member_orders WHERE id = ?", req.ID); err != nil {
			writeJSON(w, 500, "删除失败", nil)
			return
		}
		writeJSON(w, 200, "已删除", nil)
	case "reject":
		var o struct {
			UserID int64
			Type   string
		}
		if err := db.QueryRow("SELECT user_id, type FROM member_orders WHERE id = ?", req.ID).
			Scan(&o.UserID, &o.Type); err != nil {
			writeJSON(w, 404, "订单不存在", nil)
			return
		}
		reason := strings.TrimSpace(req.Reason)
		if reason == "" {
			reason = "管理员未填写拒绝原因"
		}
		if _, err := db.Exec("UPDATE member_orders SET status = 'rejected', processed_at = ?, reject_reason = ? WHERE id = ?", time.Now().Unix(), reason, req.ID); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		// 推送系统通知卡片到「通知中心」对话（在线走 TCP，离线进离线队列），附拒绝原因
		PushToUser(o.UserID, "system_notice", map[string]interface{}{
			"title":    "您的订单被拒绝",
			"desc":     "拒绝原因：" + reason,
			"tag":      "订单拒绝",
			"order_id": req.ID,
		})
		// 持久化到数据库，避免后端重启/用户离线导致通知丢失
		saveSystemNotice(o.UserID, "您的订单被拒绝", "拒绝原因："+reason, "订单拒绝", req.ID)
		log.Printf("[订单] 管理员拒绝订单 %d，用户 %d，原因：%s", req.ID, o.UserID, reason)
		writeJSON(w, 200, "已拒绝", nil)
	case "approve":
		var o struct {
			UserID int64
			Status string
			Type   string
			Tokens int64
		}
		if err := db.QueryRow("SELECT user_id, status, type, tokens FROM member_orders WHERE id = ?", req.ID).
			Scan(&o.UserID, &o.Status, &o.Type, &o.Tokens); err != nil {
			writeJSON(w, 404, "订单不存在", nil)
			return
		}
		if o.Status == "approved" {
			writeJSON(w, 400, "该订单已处理", nil)
			return
		}
		now := time.Now().Unix()
		// 会员体系已移除，当前订单均为 Token 充值订单：审核通过后直接发放 Token
		if o.Tokens > 0 {
			if _, err := db.Exec("UPDATE users SET token_balance = token_balance + ? WHERE id = ?", o.Tokens, o.UserID); err != nil {
				writeJSON(w, 500, "发放失败", nil)
				return
			}
		}
		if _, err := db.Exec("UPDATE member_orders SET status = 'approved', processed_at = ?, reject_reason = '' WHERE id = ?", now, req.ID); err != nil {
			log.Printf("[订单] 更新订单状态失败: %v", err)
		}
		log.Printf("[订单] 管理员批准订单 %d，用户 %d 获得 Token %d", req.ID, o.UserID, o.Tokens)
		// 推送系统通知卡片到「系统通知」对话
		PushToUser(o.UserID, "system_notice", map[string]interface{}{
			"title":    "您的订单已通过",
			"desc":     fmt.Sprintf("您提交的订单已审核通过，%d 个 Token 已下发至您的账户", o.Tokens),
			"tag":      "充值到账",
			"order_id": req.ID,
		})
		saveSystemNotice(o.UserID, "您的订单已通过", fmt.Sprintf("您提交的订单已审核通过，%d 个 Token 已下发至您的账户", o.Tokens), "充值到账", req.ID)
		writeJSON(w, 200, "已同意，Token 已到账", nil)
	default:
		writeJSON(w, 400, "无效的操作", nil)
	}
}

// ==================== 开发者通知中心（系统通知下发） ====================

// POST /api/admin/notify — 开发者/管理员向用户发送系统通知（通知中心）
// 范围 scope: "user"(按用户名列表) | "all"(全部在线+离线) | "range"(ID 区块)
func handleAdminSendNotification(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "notify.send") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		Scope     string    `json:"scope"`
		Usernames []string  `json:"usernames"`
		Ranges    [][]int64 `json:"ranges"`
		Title     string    `json:"title"`
		Content   string    `json:"content"`
		Extra     string    `json:"extra"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数解析失败", nil)
		return
	}
	title := strings.TrimSpace(req.Title)
	content := strings.TrimSpace(req.Content)
	if title == "" {
		title = "通知中心"
	}
	if content == "" {
		writeJSON(w, 400, "通知内容不能为空", nil)
		return
	}

	targetSet := map[int64]bool{}
	addID := func(id int64) {
		if id > 0 {
			targetSet[id] = true
		}
	}
	switch req.Scope {
	case "user":
		for _, un := range req.Usernames {
			un = strings.TrimSpace(un)
			if un == "" {
				continue
			}
			// 纯数字 → 按用户 ID 匹配（兼容"按 ID 发送"）
			if id, err := strconv.ParseInt(un, 10, 64); err == nil && id > 0 {
				var exists int
				if e := db.QueryRow("SELECT 1 FROM users WHERE id = ? AND "+notDeletedCond, id).Scan(&exists); e == nil {
					addID(id)
					continue
				}
			}
			// 否则按用户名匹配：LOWER 使其大小写不敏感，避免"明明存在却查不到"
			var id int64
			if err := db.QueryRow("SELECT id FROM users WHERE LOWER(username) = LOWER(?) AND "+notDeletedCond, un).Scan(&id); err == nil {
				addID(id)
			}
		}
	case "all":
		rows, err := db.Query("SELECT id FROM users WHERE " + notDeletedCond)
		if err == nil {
			for rows.Next() {
				var id int64
				if rows.Scan(&id) == nil {
					addID(id)
				}
			}
			rows.Close()
		}
	case "range":
		for _, rg := range req.Ranges {
			if len(rg) < 2 {
				continue
			}
			mn, mx := rg[0], rg[1]
			if mn > mx {
				mn, mx = mx, mn
			}
			rows, err := db.Query("SELECT id FROM users WHERE id BETWEEN ? AND ? AND "+notDeletedCond, mn, mx)
			if err == nil {
				for rows.Next() {
					var id int64
					if rows.Scan(&id) == nil {
						addID(id)
					}
				}
				rows.Close()
			}
		}
	default:
		writeJSON(w, 400, "未知的通知范围", nil)
		return
	}

	if len(targetSet) == 0 {
		writeJSON(w, 400, "未找到匹配的用户（请检查用户名/ID 是否正确，或账号是否已注销）", nil)
		return
	}

	sent := 0
	for uid := range targetSet {
		PushToUser(uid, "system_notice", map[string]interface{}{
			"title": title,
			"desc":  content,
			"tag":   strings.TrimSpace(req.Extra),
		})
		saveSystemNotice(uid, title, content, strings.TrimSpace(req.Extra), 0)
		sent++
	}
	writeJSON(w, 200, "已发送", map[string]interface{}{"sent": sent})
}

// GET /api/system-notices — 返回当前登录用户的系统通知历史（持久化存储，重启/离线不丢）
func handleGetSystemNotices(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	tokenStr := extractToken(r)
	if tokenStr == "" {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	userID, _, _, err := validateToken(tokenStr)
	if err != nil || userID <= 0 {
		writeJSON(w, 401, "登录状态异常", nil)
		return
	}
	ensureSystemNoticesTable()
	rows, qerr := db.Query("SELECT id, title, desc, tag, order_id, created_at FROM system_notices WHERE user_id = ? ORDER BY created_at ASC, id ASC", userID)
	if qerr != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	list := []map[string]interface{}{}
	for rows.Next() {
		var id int64
		var title, desc, tag string
		var orderID, createdAt int64
		if err := rows.Scan(&id, &title, &desc, &tag, &orderID, &createdAt); err != nil {
			continue
		}
		list = append(list, map[string]interface{}{
			"id":         id,
			"title":      title,
			"desc":       desc,
			"tag":        tag,
			"order_id":   orderID,
			"created_at": createdAt,
		})
	}
	writeJSON(w, 200, "ok", list)
}

// GET /api/orders/<id> — 用户查看自己的某笔订单详情（仅能查看属于自己的订单）
func handleGetOrder(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID, _, _, err := validateToken(extractToken(r))
	if err != nil || userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	// 从路径 /api/orders/<id> 解析订单 ID
	idStr := strings.TrimPrefix(r.URL.Path, "/api/orders/")
	idStr = strings.Trim(idStr, "/")
	orderID, perr := strconv.ParseInt(idStr, 10, 64)
	if perr != nil || orderID <= 0 {
		writeJSON(w, 400, "订单 ID 无效", nil)
		return
	}
	ensureMemberOrderColumns()
	var o struct {
		ID           int64
		UserID       int64
		Username     string
		Level        int
		DurationDays int
		Amount       float64
		OrderNo      string
		Status       string
		CreatedAt    int64
		ProcessedAt  int64
		Type         string
		Tokens       int64
		RejectReason string
	}
	// COALESCE 兜底老库 NULL 列
	if err := db.QueryRow(`SELECT id, user_id, username, level, duration_days, amount, order_no, status, created_at, COALESCE(processed_at, 0), type, tokens, COALESCE(reject_reason, '')
		FROM member_orders WHERE id = ?`, orderID).Scan(
		&o.ID, &o.UserID, &o.Username, &o.Level, &o.DurationDays, &o.Amount, &o.OrderNo, &o.Status, &o.CreatedAt, &o.ProcessedAt, &o.Type, &o.Tokens, &o.RejectReason); err != nil {
		writeJSON(w, 404, "订单不存在", nil)
		return
	}
	// 仅允许查看属于自己的订单，避免越权
	if o.UserID != userID {
		writeJSON(w, 403, "无权查看该订单", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"id":            o.ID,
		"user_id":       o.UserID,
		"username":      o.Username,
		"level":         o.Level,
		"duration_days": o.DurationDays,
		"amount":        o.Amount,
		"order_no":      o.OrderNo,
		"status":        o.Status,
		"created_at":    o.CreatedAt,
		"processed_at":  o.ProcessedAt,
		"type":          o.Type,
		"tokens":        o.Tokens,
		"reject_reason": o.RejectReason,
	})
}

// POST /api/admin/balance/adjust — 调整指定用户 Token 余额（开发者管理-余额）
// POST /api/admin/balance/adjust — 按范围批量调整用户 Token 余额（加/扣/清空）
func handleAdminAdjustBalance(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "balance.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		Scope     string    `json:"scope"`     // user | all | range
		Usernames []string  `json:"usernames"` // scope=user 时有效
		Ranges    [][]int64 `json:"ranges"`    // scope=range 时有效，每项为 [min,max]
		Op        string    `json:"op"`        // add | deduct | reset
		Amount    int64     `json:"amount"`    // add/deduct 时有效，须 >0
		Reason    string    `json:"reason"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数解析失败", nil)
		return
	}
	op := req.Op
	if op != "add" && op != "deduct" && op != "reset" {
		writeJSON(w, 400, "无效的操作类型", nil)
		return
	}
	if op != "reset" && req.Amount <= 0 {
		writeJSON(w, 400, "调整数量必须大于 0", nil)
		return
	}

	// 解析目标用户集合（排除已注销用户，与通知/封禁保持一致）
	targetSet := map[int64]bool{}
	addID := func(id int64) {
		if id > 0 {
			targetSet[id] = true
		}
	}
	switch req.Scope {
	case "user":
		for _, un := range req.Usernames {
			un = strings.TrimSpace(un)
			if un == "" {
				continue
			}
			var id int64
			if err := db.QueryRow("SELECT id FROM users WHERE username = ? AND "+notDeletedCond, un).Scan(&id); err == nil {
				addID(id)
			}
		}
	case "all":
		rows, err := db.Query("SELECT id FROM users WHERE " + notDeletedCond)
		if err == nil {
			for rows.Next() {
				var id int64
				if rows.Scan(&id) == nil {
					addID(id)
				}
			}
			rows.Close()
		}
	case "range":
		for _, rg := range req.Ranges {
			if len(rg) < 2 {
				continue
			}
			mn, mx := rg[0], rg[1]
			if mn > mx {
				mn, mx = mx, mn
			}
			rows, err := db.Query("SELECT id FROM users WHERE id BETWEEN ? AND ? AND "+notDeletedCond, mn, mx)
			if err == nil {
				for rows.Next() {
					var id int64
					if rows.Scan(&id) == nil {
						addID(id)
					}
				}
				rows.Close()
			}
		}
	default:
		writeJSON(w, 400, "未知的调整范围", nil)
		return
	}
	if len(targetSet) == 0 {
		writeJSON(w, 400, "未找到任何目标用户", nil)
		return
	}

	// 构造 IN 占位符与参数（金额作为首个参数）
	ids := make([]int64, 0, len(targetSet))
	for id := range targetSet {
		ids = append(ids, id)
	}
	placeholders := make([]string, len(ids))
	args := make([]interface{}, 0, len(ids)+1)
	for i, id := range ids {
		placeholders[i] = "?"
		args = append(args, id)
	}
	inClause := strings.Join(placeholders, ",")

	var sqlStr string
	switch op {
	case "add":
		sqlStr = "UPDATE users SET token_balance = token_balance + ? WHERE id IN (" + inClause + ")"
		args = append([]interface{}{req.Amount}, args...)
	case "deduct":
		sqlStr = "UPDATE users SET token_balance = MAX(0, token_balance - ?) WHERE id IN (" + inClause + ")"
		args = append([]interface{}{req.Amount}, args...)
	case "reset":
		sqlStr = "UPDATE users SET token_balance = 0 WHERE id IN (" + inClause + ")"
	}

	res, err := db.Exec(sqlStr, args...)
	if err != nil {
		writeJSON(w, 500, "调整失败: "+err.Error(), nil)
		return
	}
	affected, _ := res.RowsAffected()
	writeJSON(w, 200, "已调整", map[string]interface{}{"affected": affected})
}

