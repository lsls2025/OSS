package main

// ==================== 开放平台：二维码授权（扫码授权第三方拉取数据） ====================
// 流程：
//   1. 第三方 POST /api/open/qrauth/create       → 生成 sessionId，返回二维码内容（落地页 URL 形式）
//   2. 第三方 GET  /api/open/qrauth/status/{id}  → 轮询：waiting → scanned → confirmed(code)|expired
//   3. APP   POST /api/open/qrauth/scan          → 上报"已扫描"（需登录，返回应用名与申请权限供确认框展示）
//   4. APP   POST /api/open/qrauth/confirm       → 确认授权，以扫码者本人为授权用户，签发一次性授权码（需登录）
// 第三方拿到 code 后走现有 /api/open/token + /api/open/userinfo 拉取数据（无需新写）。
// 会话 5 分钟过期、同一会话只能被授权一次；授权码复用 open_auth_codes 表语义（10 分钟有效）。
// ==================== 微信/QQ 群：扫码授权 ====================

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	// 二维码内容沿用落地页 URL 形式：APP 从 `qrauth?sid=` 指纹解析出 sid 完成扫码授权；
	// 用浏览器/微信/QQ 等外部应用扫到该码时，会打开落地页 q.html（与扫码登录同一落地页）。
	qrAuthPrefix          = "https://example.com/q.html?qrauth?sid="
	qrAuthTTL             = 5 * time.Minute
	qrAuthCleanupInterval = 2 * time.Minute
	// 授权码有效期（秒）：复用 open_auth_codes 表语义，与 handleOpenAuthorize 一致（10 分钟）
	qrAuthCodeTTLSeconds = 600
)

type qrAuthSession struct {
	Status         string // waiting | scanned | confirmed | expired
	ClientID       string
	Scopes         string // 最终授权范围（已按应用申请范围收紧后的白名单子集）
	State          string
	Code           string `json:"-"` // confirm 后签发的一次性授权码
	ScannedByUser  int64  // 扫码上报的手机用户（confirm 时以本人为授权用户）
	CreatedAt      time.Time
	ScannedAt      time.Time
	ExpiresAt      time.Time
}

var qrAuthStore sync.Map // sessionId(string) → *qrAuthSession

func init() {
	go func() {
		ticker := time.NewTicker(qrAuthCleanupInterval)
		for range ticker.C {
			now := time.Now()
			qrAuthStore.Range(func(key, value interface{}) bool {
				s := value.(*qrAuthSession)
				if now.After(s.ExpiresAt) || s.Status == "confirmed" {
					qrAuthStore.Delete(key)
				}
				return true
			})
		}
	}()
}

func randomQRAuthSessionID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// POST /api/open/qrauth/create — 第三方拉取授权二维码（公开接口）
// 请求: {"client_id": "cli_xxx", "scopes": "email,name", "state": "任意透传串"}
// 校验: 应用必须 approved；scopes 白名单(email,qq,avatar,name,uid)；请求 scopes 必须是
//       open_apps 表中该应用申请范围(appScopes)的子集；未显式传 scopes 时默认按 appScopes 授权。
func handleQRAuthCreate(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	var req struct {
		ClientID string `json:"client_id"`
		Scopes   string `json:"scopes"`
		State    string `json:"state"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	req.ClientID = strings.TrimSpace(req.ClientID)
	req.Scopes = strings.TrimSpace(req.Scopes)
	req.State = strings.TrimSpace(req.State)
	if req.ClientID == "" {
		writeJSON(w, 400, "client_id 不能为空", nil)
		return
	}
	if len(req.State) > 200 {
		writeJSON(w, 400, "state 过长", nil)
		return
	}
	// 校验应用存在且已通过审核
	var appStatus string
	if err := db.QueryRow("SELECT status FROM open_apps WHERE client_id = ?", req.ClientID).Scan(&appStatus); err != nil {
		writeJSON(w, 404, "应用不存在或 client_id 无效", nil)
		return
	}
	if appStatus != "approved" {
		writeJSON(w, 403, "应用未通过审核,无法授权", nil)
		return
	}
	// 请求 scopes 必须是应用真实申请范围(appScopes)的子集，防止越权申请未获批权限（如 QQ）
	var appScopes string
	if err := db.QueryRow("SELECT scopes FROM open_apps WHERE client_id = ?", req.ClientID).Scan(&appScopes); err != nil {
		writeJSON(w, 500, "读取应用权限失败", nil)
		return
	}
	appScopes = strings.TrimSpace(appScopes)
	if appScopes == "" {
		writeJSON(w, 400, "应用未配置可授权权限", nil)
		return
	}
	// scopes 白名单清洗:仅允许 email,qq,avatar,name,uid,非法项直接拒绝
	cleanScopes, invalid := sanitizeScopes(req.Scopes)
	if invalid {
		writeJSON(w, 400, "权限项不合法,仅支持 email,qq,avatar,name,uid", nil)
		return
	}
	if cleanScopes == "" {
		// 未显式传 scopes 时,默认按应用真实申请的权限范围授权
		cleanScopes = appScopes
	} else {
		// 请求 scopes 必须是应用真实申请的子集,防止越权申请未获批权限
		appScopeSet := map[string]bool{}
		for _, s := range strings.Split(appScopes, ",") {
			appScopeSet[strings.ToLower(strings.TrimSpace(s))] = true
		}
		reqScopeList := strings.Split(cleanScopes, ",")
		for _, s := range reqScopeList {
			if !appScopeSet[s] {
				writeJSON(w, 400, "请求权限超出应用申请范围: "+s, nil)
				return
			}
		}
	}

	sessionID := randomQRAuthSessionID()
	now := time.Now()
	qrAuthStore.Store(sessionID, &qrAuthSession{
		Status:    "waiting",
		ClientID:  req.ClientID,
		Scopes:    cleanScopes,
		State:     req.State,
		CreatedAt: now,
		ExpiresAt: now.Add(qrAuthTTL),
	})
	log.Printf("[开放平台] 第三方应用 %s 拉取授权二维码(session=%s, scopes=%s)", req.ClientID, sessionID, cleanScopes)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"session_id": sessionID,
		"qr_content": qrAuthPrefix + sessionID,
		"expires_at": now.Add(qrAuthTTL).Unix(),
		"expires_in": int(qrAuthTTL.Seconds()),
	})
}

// GET /api/open/qrauth/status/{id} — 第三方轮询授权状态（公开接口）
// waiting → scanned → confirmed(code)|expired；confirmed 时返回一次性授权码 code 与透传 state。
func handleQRAuthStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/api/open/qrauth/status/")
	if id == "" {
		writeJSON(w, 400, "缺少 session id", nil)
		return
	}
	val, ok := qrAuthStore.Load(id)
	if !ok {
		writeJSON(w, 200, "ok", map[string]interface{}{"status": "expired"})
		return
	}
	s := val.(*qrAuthSession)
	if time.Now().After(s.ExpiresAt) {
		s.Status = "expired"
	}
	resp := map[string]interface{}{
		"status": s.Status,
		"state":  s.State,
	}
	if s.Status == "confirmed" && s.Code != "" {
		// 第三方轮询到 confirmed 时返回一次性授权码，凭 code 走 /api/open/token + /api/open/userinfo
		resp["code"] = s.Code
	}
	writeJSON(w, 200, "ok", resp)
}

// POST /api/open/qrauth/scan — APP 扫码上报"已扫描"（需登录）
// 返回应用名与申请权限项，供 APP 确认框展示。
func handleQRAuthScan(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	var req struct {
		QrContent string `json:"qr_content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.QrContent == "" {
		writeJSON(w, 400, "缺少 qr_content 参数", nil)
		return
	}
	// 只接受标准授权二维码
	if !strings.HasPrefix(req.QrContent, qrAuthPrefix) {
		writeJSON(w, 400, "非授权二维码", nil)
		return
	}
	sessionID := strings.TrimPrefix(req.QrContent, qrAuthPrefix)
	// 落地页 URL 可能追加 & 追踪参数,剥离后才是纯 sessionID
	if idx := strings.IndexByte(sessionID, '&'); idx >= 0 {
		sessionID = sessionID[:idx]
	}
	val, ok := qrAuthStore.Load(sessionID)
	if !ok {
		writeJSON(w, 404, "二维码已失效，请刷新重试", nil)
		return
	}
	s := val.(*qrAuthSession)
	if time.Now().After(s.ExpiresAt) {
		writeJSON(w, 410, "二维码已过期，请刷新重试", nil)
		return
	}
	if s.Status == "confirmed" {
		writeJSON(w, 200, "ok", map[string]interface{}{"status": "confirmed"})
		return
	}
	scannerID := getCurrentUserID(r)
	if scannerID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	s.Status = "scanned"
	s.ScannedAt = time.Now()
	s.ScannedByUser = scannerID
	// 返回应用名与授权范围，供 APP 确认框展示
	var appName string
	db.QueryRow("SELECT app_name FROM open_apps WHERE client_id = ?", s.ClientID).Scan(&appName)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"status":   "scanned",
		"app_name": appName,
		"scopes":   s.Scopes,
	})
}

// POST /api/open/qrauth/confirm — APP 确认授权（需登录，以扫码者本人为授权用户）
// 校验会话有效且未被授权过；签发一次性授权码（10 分钟有效，复用 open_auth_codes 表）。
func handleQRAuthConfirm(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	var req struct {
		SessionID string `json:"session_id"`
		QrContent string `json:"qr_content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	sessionID := strings.TrimSpace(req.SessionID)
	if sessionID == "" && strings.HasPrefix(req.QrContent, qrAuthPrefix) {
		sessionID = strings.TrimPrefix(req.QrContent, qrAuthPrefix)
		// 落地页 URL 可能追加 & 追踪参数,剥离后才是纯 sessionID
		if idx := strings.IndexByte(sessionID, '&'); idx >= 0 {
			sessionID = sessionID[:idx]
		}
	}
	if sessionID == "" {
		writeJSON(w, 400, "缺少 session_id", nil)
		return
	}

	val, ok := qrAuthStore.Load(sessionID)
	if !ok {
		writeJSON(w, 404, "二维码已失效，请刷新重试", nil)
		return
	}
	s := val.(*qrAuthSession)
	if time.Now().After(s.ExpiresAt) {
		writeJSON(w, 410, "二维码已过期", nil)
		return
	}
	if s.Status == "confirmed" {
		writeJSON(w, 400, "该二维码已被使用", nil)
		return
	}
	// 授权人是当前登录的手机用户（扫码者本人）
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	// 签发一次性授权码(10分钟有效)，复用 open_auth_codes 表语义
	code := "ac_" + randomString(32)
	now := time.Now().Unix()
	expiresAt := now + qrAuthCodeTTLSeconds
	if _, err := db.Exec(`INSERT INTO open_auth_codes (code, client_id, user_id, scopes, state, expires_at, used, created_at)
		VALUES (?, ?, ?, ?, ?, ?, 0, ?)`,
		code, s.ClientID, userID, s.Scopes, s.State, expiresAt, now); err != nil {
		writeJSON(w, 500, "生成授权码失败:"+err.Error(), nil)
		return
	}

	s.Status = "confirmed"
	s.Code = code
	log.Printf("[开放平台] 用户 %d 扫码确认授权应用 %s(session=%s, scopes=%s)", userID, s.ClientID, sessionID, s.Scopes)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"status": "confirmed",
	})
}
