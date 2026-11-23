package main

import (
	"backend/internal/platform"
	"encoding/json"
	"fmt"
	"golang.org/x/crypto/bcrypt"
	"html"
	"log"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// API 统一响应格式
type ApiResponse = platform.ApiResponse

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
	// IsDeveloper 服务端判定结果：用户是否命中开发者配置（DEV_QQ / DEV_EMAIL 环境变量）
	IsDeveloper bool `json:"is_developer"`
	// SignSecret 服务端动态下发的客户端请求签名密钥（每用户随机生成，仅服务端内存持有）
	SignSecret string `json:"sign_secret"`
}

// maskSecret 对敏感凭据做脱敏展示：保留首尾各 2 位，其余掩码；过短则全掩码。
func maskSecret(s string) string {
	if len(s) <= 4 {
		return "****"
	}
	return s[:2] + "****" + s[len(s)-2:]
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
	platform.WriteJSON(w, code, msg, data)
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
	keyDisabledMu.Lock()
	keyCount := len(solitaryCryAI.apiKeys)
	keyDisabledMu.Unlock()
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "ok",
		"service": "Aurora Chat",
		"version": "2.0",
		"time":    time.Now().Unix(),
		// AI 代理配置自检：远程确认密钥是否加载（只报数量与上游域名，不含密钥本体）。
		// 排查「生图提示 AI 服务未配置」时先看这里的 keys_loaded 与 image_model。
		"ai": map[string]interface{}{
			"keys_loaded": keyCount,
			"ready":       keyCount > 0,
			"base_url":    solitaryCryAI.baseURL,
			"chat_model":  solitaryCryAI.model,
			"image_model": imageModel,
		},
	})
}

// ==================== APP 版本检查（强制更新） ====================

// 当前最新版本配置（发布新版时更新这里，或在 version.txt 中修改）
var (
	LatestVersionCode = 30 // 发布新版时改成 build.gradle.kts 里的 versionCode
	LatestVersionName = "3.0.0"
	DownloadURL       = "http://www.example.com:5004/tools/aurora.apk" // APK 放到服务器 data/tools/ 目录后改文件名
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

// limitJSONBody 给请求体加大小上限。
// 登录/注册/验证码等公开接口原本直接 json.Decode(r.Body)，超大请求体会被整包读进内存，
// 未登录即可打成内存耗尽；统一在这里兜一层。
func limitJSONBody(w http.ResponseWriter, r *http.Request, maxBytes int64) {
	if r.Body != nil {
		r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	}
}

// maxAuthBodyBytes 认证类接口的请求体上限（正常请求不到 1 KB，64 KB 足够宽松）
const maxAuthBodyBytes = 64 << 10

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
	limitJSONBody(w, r, maxAuthBodyBytes)
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
			// B9 加固：统一响应文案，不泄露"该账号是否存在/是否绑定邮箱"，
			// 杜绝按 QQ/用户名反查邮箱的账号枚举。
			log.Printf("[验证码] 反查邮箱失败，统一响应不发送（防枚举）: %s", req.Email)
			writeJSON(w, 200, "验证码已发送至邮箱", nil)
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
		// B9 加固：统一响应文案，不区分"邮箱已注册/未注册"，
		// 已注册邮箱不发码但返回一致的成功文案（真实原因仅记录日志），杜绝邮箱枚举。
		log.Printf("[验证码] 邮箱已注册，统一响应不发送（防枚举）: %s", req.Email)
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
		log.Printf("[验证码] 第 %d 次发送失败: %s (%s)", attempt+1, req.Email, emailMsg)
	}
	if !emailSent {
		log.Printf("[验证码] 3 次重试均失败: %s", req.Email)
		writeJSON(w, 500, "邮件发送失败，请稍后重试或联系管理员", nil)
		return
	}

	recordCodeSent(req.Email)
	recordIPCodeSent(peerIP)
	recordCodeDaily("email:", req.Email)
	recordCodeDaily("ip:", peerIP)
	log.Printf("验证码已发送至邮箱: %s", req.Email)
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
	limitJSONBody(w, r, maxAuthBodyBytes)
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

	// IP 黑名单检查
	// 注意：注册限流配额不在这里扣，放到验证码/用户名等校验全部通过之后再扣，
	// 否则用户填错一次验证码就会把 12 小时/IP 的配额白白烧掉。
	clientIP := getClientIP(r)
	if blocked, _ := isIPBlocked(clientIP); blocked {
		writeJSON(w, 403, "该 IP 已被封禁，无法注册", nil)
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

	if code, msg := accountConflict(req.Email, req.QQNumber); code != 0 {
		writeJSON(w, code, msg, nil)
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
		if !verifyCode(req.Email, req.Code) {
			writeJSON(w, 400, "验证码错误或已过期", nil)
			return
		}
		hasVerifiedEmail = true
		deleteVerificationCode(req.Email)
	}

	// 注册配额：所有校验通过后才扣，避免一次手误锁死 12 小时
	if allowed, wait := checkRegisterRateLimit(clientIP); !allowed {
		writeJSON(w, 429, fmt.Sprintf("注册过于频繁，请在 %d 秒后再试", wait), nil)
		return
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
	// 新账号两类设备版本同步初始化，避免首签 token 被设备版本判为过期
	db.Exec("UPDATE users SET token_version_mobile = 1, token_version_pc = 1, token_version_agent = 1 WHERE id = ?", userID)

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
		QQNumber:      req.QQNumber,
		SessionKey:    getOrCreateSessionKey(userID),
		IsDeveloper:   isDeveloperUser(userID),
		SignSecret:    getClientSignSecret(userID),
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
	log.Printf("绑定邮箱验证码已发送至邮箱: %s", req.Email)
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
		ID       string `json:"id"`
		Email    string `json:"email"`
		Password string `json:"password"`
		// Device 客户端上报的设备类别（mobile / pc）。手机端与桌面端各自持有独立登录态，
		// 同类设备新登录才互踢，跨端不再顶号。缺省按 mobile。
		Device string `json:"device"`
		// NoKick=true 时不抬高任何版本号：供 Aurora Agent 这类"只借用身份"的
		// 第三方客户端登录，避免把用户其它设备(如 Aurora Chat 本体)顶下线
		NoKick bool `json:"no_kick"`
	}
	limitJSONBody(w, r, maxAuthBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.ID = strings.TrimSpace(req.ID)
	req.Email = strings.TrimSpace(req.Email)
	// ByID=true 表示用数字 ID 登录（Aurora Agent 使用），否则沿用 Aurora Chat 的账号登录
	byID := req.ID != ""
	identifier := req.ID
	if !byID {
		identifier = req.Email
	}
	if identifier == "" || req.Password == "" {
		if byID {
			writeJSON(w, 400, "请填写 ID 和密码", nil)
		} else {
			writeJSON(w, 400, "请填写用户名/邮箱/QQ号和密码", nil)
		}
		return
	}
	if len(req.Password) < 8 {
		writeJSON(w, 400, "密码不能少于 8 位", nil)
		return
	}

	// 支持数字 ID（Aurora Agent）/ 邮箱 / 用户名 / QQ 号（Aurora Chat）登录
	var user *User
	var err error
	if byID {
		uid, convErr := strconv.ParseInt(identifier, 10, 64)
		if convErr != nil || uid <= 0 {
			writeJSON(w, 400, "ID 格式不正确", nil)
			return
		}
		user, err = findUserByID(uid)
	} else if emailRegex.MatchString(req.Email) {
		user, err = findUserByEmail(req.Email)
	} else if isDigitsOnly(req.Email) {
		// 纯数字标识有歧义：可能是 QQ 号，也可能是用户 ID（聊天 ID）。
		// 先按 QQ 号查，查不到再按 ID 查 —— 这样「用 ID 登录」不再被误判成 QQ 号。
		user, err = findUserByQQNumber(req.Email)
		if err == nil && user == nil {
			if uid, convErr := strconv.ParseInt(req.Email, 10, 64); convErr == nil && uid > 0 {
				user, err = findUserByID(uid)
			}
		}
	} else {
		user, err = findUserByUsername(req.Email)
	}
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	clientIP := getClientIP(r)

	// —— 防爆破第 2 层：失败熔断（落 SQLite，重启不清零，见 login_guard.go）——
	startLoginGuardCleanup()
	ipGuardKey := loginGuardIPKey(clientIP)
	if locked, remain := loginGuardLocked(ipGuardKey); locked {
		writeJSON(w, 429, fmt.Sprintf("登录尝试过于频繁，请 %d 秒后再试", remain), nil)
		return
	}
	// 账号维度：key 以 userID 归一（同一个账号用邮箱/用户名/QQ 三种写法不能各占一份额度）；
	// 账号不存在时按输入串计数，防止拿同一个不存在的标识反复试。
	// 但若来源 IP 是该账号近期成功登录过的 IP，则豁免账号锁——否则攻击者连发几次错密码
	// 就能把正常用户锁在门外（锁定型拒绝服务）。
	acctGuardKey := loginGuardAccountKey(0, identifier)
	if user != nil {
		acctGuardKey = loginGuardAccountKey(user.ID, req.Email)
	}
	if locked, remain := loginGuardLocked(acctGuardKey); locked {
		exempt := user != nil && isKnownLoginIP(user.ID, clientIP)
		if !exempt {
			writeJSON(w, 429, fmt.Sprintf("该账号因多次密码错误已被临时锁定，请 %d 秒后再试", remain), nil)
			return
		}
	}

	// 登录频率限制：防爆破/撞库。按 IP 与账号双维度滑动窗口。
	// 账号维度用 userID 归一：否则同一账号可用「邮箱 / 用户名 / QQ 号」三种写法各占一份配额，
	// 等于把单账号额度放大三倍。账号不存在时用输入串当 key，同样参与计数，不泄露账号是否存在。
	loginAcctKey := "input:" + identifier
	if user != nil {
		loginAcctKey = fmt.Sprintf("uid:%d", user.ID)
	}
	if ok, wait := checkLoginRateLimit(clientIP, loginAcctKey); !ok {
		writeJSON(w, 429, fmt.Sprintf("登录尝试过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	// 密码校验走等时入口：账号不存在时也跑满一次 bcrypt，
	// 否则"响应快 = 账号不存在"会成为一个可批量枚举的侧信道。
	storedHash := ""
	if user != nil {
		storedHash = user.Password
	}
	if !useLegacyAuthChannel() && user != nil && storedHash == "" {
		if h, ok := reclaimLegacyPassword(user.ID, req.Password); ok {
			storedHash = h
		}
	}
	if !verifyPasswordConstantTime(storedHash, req.Password) {
		loginGuardFail(acctGuardKey, loginGuardAcctMax, loginGuardAcctLockSec)
		loginGuardFail(ipGuardKey, loginGuardIPMax, loginGuardIPLockSec)
		if byID {
			writeJSON(w, 401, "ID 或密码错误", nil)
		} else {
			writeJSON(w, 401, "用户名/邮箱/QQ号或密码错误", nil)
		}
		return
	}
	// 登录成功：清空该账号的失败计数（IP 维度不清，避免攻击者用自己一个账号洗白整个 IP）
	loginGuardReset(acctGuardKey)

	// 清除 kicked_at 标记（用户重新登录后不再显示"已被踢出"提示）
	db.Exec("UPDATE users SET kicked_at = 0 WHERE id = ?", user.ID)
	// 同类设备新登录踢旧设备（开关 kick_on_new_login）：只抬高「本设备类别」的版本号。
	// 手机端登录不会顶掉桌面端，桌面端登录也不会顶掉手机端；只有再开一台同类设备才会互踢。
	// 旧 token 因设备版本落后被中间件判为"已在其他设备登录"（HTTP 401 兜底，前端同一弹窗）。
	// 注意：不在此处主动 PushToUser。踢线交由版本号 + 中间件 401 实现，旧设备下次请求即被拦截并弹窗。
	device := normalizeDevice(req.Device)
	if req.NoKick {
		// no_kick 的客户端（如 Aurora Agent）单独归为一类：不参与互踢，
		// 别人登录也不会把它顶下线。
		device = deviceAgent
	}
	deviceVersion := currentDeviceVersion(user.ID, device)
	if getFlag("kick_on_new_login") && !req.NoKick {
		deviceVersion = bumpDeviceVersion(user.ID, device)
	}
	var currentVersion int64
	db.QueryRow("SELECT token_version FROM users WHERE id = ?", user.ID).Scan(&currentVersion)

	// 生成 JWT token（带设备类别，供中间件按类别校验）
	token, _ := generateTokenFor(user.ID, user.Email, currentVersion, deviceVersion, device)

	// 更新最近活动时间
	now := time.Now().Unix()
	db.Exec("UPDATE users SET updated_at = ? WHERE id = ?", now, user.ID)
	user.UpdatedAt = now

	// 记录登录日志（用于 DAU / 登录次数统计）
	recordLogin(user.ID, getClientIP(r))

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
		IsDeveloper:            isDeveloperUserObj(user),
		SignSecret:             getClientSignSecret(user.ID),
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
	// 长度与保留名口径与注册 / 改资料保持一致（原来这里没有任何长度和保留名校验，
	// 可以在这里把用户名直接设成「注销用户」绕过限制）
	if len(req.Username) < 2 || len(req.Username) > 20 {
		writeJSON(w, 400, "用户名长度应在 2-20 个字符之间", nil)
		return
	}
	if req.Username == "注销用户" {
		writeJSON(w, 400, "该用户名为系统保留，请换一个", nil)
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
	//    必须带上「当前手机端设备版本」，否则新 token 的设备版本落后于库里，会被中间件直接判失效。
	var newVersion int64
	db.QueryRow("SELECT token_version FROM users WHERE id = ?", userID).Scan(&newVersion)
	mobileVersion := currentDeviceVersion(userID, deviceMobile)
	user, _ := findUserByID(userID)
	if user == nil {
		writeJSON(w, 500, "用户不存在", nil)
		return
	}
	newToken, _ := generateTokenFor(userID, user.Email, newVersion, mobileVersion, deviceMobile)

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
		IsDeveloper:            isDeveloperUserObj(user),
		SignSecret:             getClientSignSecret(user.ID),
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
	limitJSONBody(w, r, maxAuthBodyBytes)
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

	// 重置密码频率限制：防爆破/批量尝试（IP + 账号双维度，1 分钟内最多 8 次）
	if ok, wait := checkLoginRateLimit(getClientIP(r), req.Email); !ok {
		writeJSON(w, 429, fmt.Sprintf("操作过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	// B9 加固：不预先区分"邮箱是否注册"，未注册邮箱的验证码必然不存在，
	// 统一走验证码校验失败响应，杜绝按邮箱反查注册状态的枚举。
	user, err := findUserByEmail(req.Email)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}
	if user == nil {
		log.Printf("[重置密码] 邮箱未注册，统一响应（防枚举）: %s", req.Email)
		writeJSON(w, 400, "验证码错误或已过期", nil)
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

// GET /api/admin/users/search?q=<关键词>&limit=50
// 开发者/管理员专用：服务端按 ID / QQ / 用户名 / 邮箱过滤并限制返回条数。
// 客户端不再把全量用户拉到本地逐个比对——用户量大时那是主线程 O(n) 扫描，输入必卡。
func handleAdminSearchUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if _, ok := checkAdminPermission(r, "users.view_profile"); !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	// 关键词长度上限：正常用户 ID/QQ/名字远小于 64 字符，避免超长串拖慢 LIKE
	if rs := []rune(q); len(rs) > 64 {
		q = string(rs[:64])
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if q == "" {
		writeJSON(w, 200, "获取成功", []User{})
		return
	}
	// 排序优先级：ID 精确 > ID 前缀 > QQ 精确 > 其他（用户名/邮箱/QQ 包含）。
	// LIKE 走 SQLite 服务端扫描，客户端只收 50 条，不再参与比对。
	rows, err := db.Query(`SELECT id, email, username, created_at, updated_at,
		hide_email, hide_qq, require_group_consent, qq_number, signature,
		online_time_seconds, word_count, reg_ip, token_balance
		FROM users
		WHERE `+notDeletedCond+` AND (
			id = ? OR CAST(id AS TEXT) LIKE ? OR
			qq_number = ? OR qq_number LIKE ? OR
			username LIKE ? OR email LIKE ?
		)
		ORDER BY
			CASE
				WHEN id = ? THEN 0
				WHEN CAST(id AS TEXT) LIKE ? THEN 1
				WHEN qq_number = ? THEN 2
				ELSE 3
			END,
			created_at DESC
		LIMIT ?`,
		q, q+"%", q, "%"+q+"%", "%"+q+"%", "%"+q+"%",
		q, q+"%", q, limit)
	if err != nil {
		log.Printf("搜索用户失败: %v", err)
		writeJSON(w, 500, "搜索失败", nil)
		return
	}
	defer rows.Close()
	users := []User{}
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Email, &u.Username, &u.CreatedAt, &u.UpdatedAt,
			&u.HideEmail, &u.HideQQ, &u.RequireGroupConsent, &u.QQNumber, &u.Signature,
			&u.OnlineTimeSeconds, &u.WordCount, &u.RegIp, &u.TokenBalance); err != nil {
			log.Printf("搜索用户扫描失败: %v", err)
			continue
		}
		users = append(users, u)
	}
	writeJSON(w, 200, "搜索成功", users)
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
		BlockStrangerMsg    *bool `json:"block_stranger_msg"`
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

	blockStranger := false
	if req.BlockStrangerMsg != nil {
		if err := setStrangerMsgBlocked(userID, *req.BlockStrangerMsg); err != nil {
			writeJSON(w, 500, "设置失败", nil)
			return
		}
		log.Printf("用户 %d 隐私设置: block_stranger_msg=%v", userID, *req.BlockStrangerMsg)
	}
	blockStranger = isStrangerMsgBlocked(userID)

	writeJSON(w, 200, "设置成功", map[string]bool{
		"hide_email":            hideEmail == 1,
		"hide_qq":               hideQQ == 1,
		"require_group_consent": requireConsent == 1,
		"block_stranger_msg":    blockStranger,
	})
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
	limitJSONBody(w, r, maxAuthBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if len(body.Signature) > 200 {
		writeJSON(w, 400, "签名过长", nil)
		return
	}

	// 防刷：改资料本来就不该高频调用
	if ok, wait := checkIntervalLimit(fmt.Sprintf("profile-update:%d", userID), 3*time.Second); !ok {
		writeJSON(w, 429, fmt.Sprintf("操作过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	// 用户名处理：与当前值相同视为「未修改」，空值只改签名。
	// 注意不能对已有用户名二次 html 转义——注册时已经转过一次，
	// 客户端回传的是库里的值，再转义就会变成 &amp;amp; 这类脏数据。
	currentName := ""
	if cur, _ := findUserByID(userID); cur != nil {
		currentName = cur.Username
	}
	newName := strings.TrimSpace(body.Username)
	if newName == currentName {
		newName = ""
	} else if newName != "" {
		newName = html.EscapeString(newName)
		// 长度口径与注册一致（原来只限制 50，注册是 2-20）
		if len(newName) < 2 || len(newName) > 20 {
			writeJSON(w, 400, "用户名长度应在 2-20 个字符之间", nil)
			return
		}
		// 保留名：「注销用户」是系统判定「已注销」的魔法值，
		// 谁都能改成它就会把自己变成异常账号，也能伪装成已注销用户。
		if newName == "注销用户" {
			writeJSON(w, 400, "该用户名为系统保留，请换一个", nil)
			return
		}
		// 唯一性：否则可以改成与他人完全相同的用户名实施冒充
		if existing, _ := findUserByUsername(newName); existing != nil && existing.ID != userID {
			writeJSON(w, 409, "该用户名已被占用", nil)
			return
		}
	}

	now := time.Now().Unix()
	var err error
	if newName == "" {
		_, err = db.Exec("UPDATE users SET signature = ?, updated_at = ? WHERE id = ?",
			body.Signature, now, userID)
	} else {
		_, err = db.Exec("UPDATE users SET username = ?, signature = ?, updated_at = ? WHERE id = ?",
			newName, body.Signature, now, userID)
	}
	if err != nil {
		writeJSON(w, 500, "更新失败", nil)
		return
	}
	writeJSON(w, 200, "更新成功", nil)
}
