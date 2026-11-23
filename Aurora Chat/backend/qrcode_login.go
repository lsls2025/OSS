package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"
)

// ==================== 扫码登录（微信式） ====================
// 流程：
//   1. 桌面端 POST /api/qrcode-login/create       → 生成 sessionId，返回二维码内容 auroraqr:login:<token>
//   2. 桌面端 GET  /api/qrcode-login/status/{id}  → 轮询：waiting → scanned → confirmed(token)|expired
//   3. 手机端 POST /api/qrcode-login/scan         → 上报"已扫描"（需登录）
//   4. 手机端 POST /api/qrcode-login/confirm      → 确认授权，后端生成 JWT（需登录）
// 有效期内未确认则过期；同一 session 只能被授权一次。
// ==================== 微信群：扫码登录 ====================

const (
	// 二维码内容为"官网落地页 + 参数"的 URL：桌面端/手机端 App 从 `qrlogin?sid=` 指纹解析出 sid 完成扫码登录；
	// 用浏览器/微信/QQ 等外部应用扫到该码时，会打开落地页 q.html（扫描适配手机下载/欢迎引导），
	// 不再落到官网首页 index.html。q.html?u=… 仍可显示被扫码者头像。
	qrLoginPrefix         = "https://example.com/q.html?qrlogin?sid="
	qrLoginTTL            = 5 * time.Minute
	qrLoginCleanupInterval = 2 * time.Minute
)

// GET /qrlogin — 应用外（浏览器/微信/QQ）扫到登录码时展示的落地页
func handleQRCodeLoginPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	html := `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Aurora Chat 扫码登录</title></head><body style="font-family:system-ui,-apple-system,sans-serif;background:linear-gradient(135deg,#eef1f6,#f7f8fb);display:flex;align-items:center;justify-content:center;height:100vh;margin:0"><div style="text-align:center;background:#fff;padding:44px 40px;border-radius:22px;box-shadow:0 10px 40px rgba(0,0,0,.08);max-width:360px"><div style="font-size:64px">&#128172;</div><h1 style="margin:14px 0 8px;color:#1a1d29;font-size:22px">Aurora Chat</h1><p style="color:#6b7280;margin:0 0 24px;font-size:14px">扫码登录</p><a href="/" style="display:block;background:#1a1d29;color:#fff;text-decoration:none;padding:12px 0;border-radius:12px;font-size:15px;font-weight:600">返回官网</a><p style="color:#9ca3af;font-size:12px;margin-top:24px;line-height:1.6">此二维码用于桌面端快捷登录，<br>请使用 Aurora Chat 手机端「扫一扫」完成授权</p></div></body></html>`
	w.Write([]byte(html))
}

type qrLoginSession struct {
	Status    string // waiting | scanned | confirmed | expired
	Token     string `json:"-"`
	UserID    int64
	UserEmail string
	UserName  string
	Persist   bool // 手机端勾选"桌面端下次免登录"时置 true，桌面端据此持久化登录态
	ScannedAt time.Time
	CreatedAt time.Time
	ExpiresAt time.Time
}

var qrLoginStore sync.Map // sessionId(string) → *qrLoginSession

func init() {
	go func() {
		ticker := time.NewTicker(qrLoginCleanupInterval)
		for range ticker.C {
			now := time.Now()
			qrLoginStore.Range(func(key, value interface{}) bool {
				s := value.(*qrLoginSession)
				if now.After(s.ExpiresAt) || s.Status == "confirmed" {
					qrLoginStore.Delete(key)
				}
				return true
			})
		}
	}()
}

func randomQRSessionID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// POST /api/qrcode-login/create — 生成登录二维码（公开接口）
func handleQRCodeLoginCreate(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	// 公开接口：按 IP 限流（3 秒 1 次），防止恶意批量创建扫码会话占满内存
	if ok, wait := checkQrPublicRateLimit(getClientIP(r)); !ok {
		writeJSON(w, 429, fmt.Sprintf("操作过于频繁，请 %d 秒后再试", wait), nil)
		return
	}
	sessionID := randomQRSessionID()
	now := time.Now()
	qrLoginStore.Store(sessionID, &qrLoginSession{
		Status:    "waiting",
		CreatedAt: now,
		ExpiresAt: now.Add(qrLoginTTL),
	})
	writeJSON(w, 200, "ok", map[string]interface{}{
		"session_id":  sessionID,
		"qr_content":  qrLoginPrefix + sessionID,
		"expires_at":  now.Add(qrLoginTTL).Unix(),
		"expires_in":  int(qrLoginTTL.Seconds()),
	})
}

// GET /api/qrcode-login/status/{id} — 查询扫码登录状态（公开接口，桌面轮询）
func handleQRCodeLoginStatus(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/api/qrcode-login/status/")
	if id == "" {
		writeJSON(w, 400, "缺少 session id", nil)
		return
	}
	// 公开轮询接口原来完全没限流。正常桌面端 1-2 秒轮询一次（约 30-60 次/分），
	// 这里给到 180 次/分，既能兜住脚本狂刷，也不影响正常轮询。
	if ok, wait := checkSlidingLimit("qrlogin-status:"+getClientIP(r), time.Minute, 180); !ok {
		writeJSON(w, 429, fmt.Sprintf("操作过于频繁，请 %d 秒后再试", wait), nil)
		return
	}
	val, ok := qrLoginStore.Load(id)
	if !ok {
		writeJSON(w, 200, "ok", map[string]interface{}{"status": "expired"})
		return
	}
	s := val.(*qrLoginSession)
	if time.Now().After(s.ExpiresAt) {
		s.Status = "expired"
	}
	resp := map[string]interface{}{"status": s.Status}
	if s.Status == "confirmed" && s.Token != "" {
		// 桌面端轮询到 confirmed 时，返回 token 与用户信息以便直接登录
		resp["token"] = s.Token
		resp["user_id"] = s.UserID
		resp["user_email"] = s.UserEmail
		resp["user_name"] = s.UserName
	}
	// 手机端是否勾选了"桌面端下次免登录"
	resp["persist"] = s.Persist
	writeJSON(w, 200, "ok", resp)
}

// POST /api/qrcode-login/scan — 手机端上报"已扫描"（需登录，返回由谁扫描以展示确认框）
func handleQRCodeLoginScan(w http.ResponseWriter, r *http.Request) {
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
	// 只接受标准登录二维码
	if !strings.HasPrefix(req.QrContent, qrLoginPrefix) {
		writeJSON(w, 400, "非扫码登录二维码", nil)
		return
	}
	sessionID := strings.TrimPrefix(req.QrContent, qrLoginPrefix)
	val, ok := qrLoginStore.Load(sessionID)
	if !ok {
		writeJSON(w, 404, "二维码已失效，请刷新重试", nil)
		return
	}
	s := val.(*qrLoginSession)
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
	writeJSON(w, 200, "ok", map[string]interface{}{"status": "scanned"})
}

// POST /api/qrcode-login/confirm — 手机端确认授权，生成 JWT（需登录）
func handleQRCodeLoginConfirm(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	var req struct {
		SessionID string `json:"session_id"`
		QrContent string `json:"qr_content"`
		Persist   bool   `json:"persist"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	sessionID := req.SessionID
	if sessionID == "" && strings.HasPrefix(req.QrContent, qrLoginPrefix) {
		sessionID = strings.TrimPrefix(req.QrContent, qrLoginPrefix)
	}
	if sessionID == "" {
		writeJSON(w, 400, "缺少 session_id", nil)
		return
	}

	val, ok := qrLoginStore.Load(sessionID)
	if !ok {
		writeJSON(w, 404, "二维码已失效，请刷新重试", nil)
		return
	}
	s := val.(*qrLoginSession)
	if time.Now().After(s.ExpiresAt) {
		writeJSON(w, 410, "二维码已过期", nil)
		return
	}
	if s.Status == "confirmed" {
		writeJSON(w, 400, "该二维码已被使用", nil)
		return
	}

	// 授权人是当前登录的手机用户
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	// 生成正式 JWT（复用现有逻辑）
	user, err := findUserByID(userID)
	if err != nil || user == nil {
		writeJSON(w, 500, "用户数据错误", nil)
		return
	}
	// 扫码登录是给「桌面端」签发的 token，因此只抬高 pc 设备版本：
	// 桌面端登录不会把扫码授权的那台手机顶下线。
	// 不主动 PushToUser，失效交由设备版本号 + 中间件 401 实现。
	db.Exec("UPDATE users SET kicked_at = 0 WHERE id = ?", user.ID)
	deviceVersion := currentDeviceVersion(user.ID, devicePC)
	if getFlag("kick_on_new_login") {
		deviceVersion = bumpDeviceVersion(user.ID, devicePC)
	}
	var currentVersion int64
	db.QueryRow("SELECT token_version FROM users WHERE id = ?", user.ID).Scan(&currentVersion)
	token, terr := generateTokenFor(user.ID, user.Email, currentVersion, deviceVersion, devicePC)
	if terr != nil {
		log.Printf("[扫码登录] 生成 token 失败: %v", terr)
		writeJSON(w, 500, "登录失败，请重试", nil)
		return
	}

	s.Status = "confirmed"
	s.Token = token
	s.UserID = user.ID
	s.UserEmail = user.Email
	s.UserName = user.Username
	s.Persist = req.Persist

	writeJSON(w, 200, "ok", map[string]interface{}{
		"status": "confirmed",
		"token":  token,
	})
}