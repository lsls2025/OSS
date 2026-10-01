package main

// 第三方接入开放平台：授权数据下发（OAuth2 授权码模式简化版）
// - POST /api/open/authorize  （登录用户确认授权 → 签发一次性授权码 code）
// - POST /api/open/token      （第三方用 app_secret + code 换取 access_token）
// - GET  /api/open/userinfo   （第三方带 Bearer token 拉取用户数据）
// 其中 authorize 由 authMiddleware 保证登录；token/userinfo 使用第三方自有凭证，
// 不依赖用户 JWT，因此路由注册时不再包 authMiddleware，由 handler 内部校验。

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"
)

// ensureOpenAuthTables 建授权码/访问令牌表(在 database.go 初始化时调用)
func ensureOpenAuthTables() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS open_auth_codes (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		code TEXT NOT NULL UNIQUE,
		client_id TEXT NOT NULL,
		user_id INTEGER NOT NULL,
		scopes TEXT NOT NULL DEFAULT '',
		state TEXT NOT NULL DEFAULT '',
		expires_at INTEGER NOT NULL DEFAULT 0,
		used INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  创建 open_auth_codes 表失败: %v", err)
	} else {
		log.Println("  [开放平台] open_auth_codes 表已确认存在")
	}
	if _, err := db.Exec("CREATE INDEX IF NOT EXISTS idx_open_auth_codes_client ON open_auth_codes(client_id)"); err != nil {
		log.Printf("  提示: open_auth_codes 索引创建失败: %v", err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS open_access_tokens (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		token TEXT NOT NULL UNIQUE,
		client_id TEXT NOT NULL,
		user_id INTEGER NOT NULL,
		scopes TEXT NOT NULL DEFAULT '',
		expires_at INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  创建 open_access_tokens 表失败: %v", err)
	} else {
		log.Println("  [开放平台] open_access_tokens 表已确认存在")
	}
	if _, err := db.Exec("CREATE INDEX IF NOT EXISTS idx_open_access_tokens_client ON open_access_tokens(client_id)"); err != nil {
		log.Printf("  提示: open_access_tokens 索引创建失败: %v", err)
	}
}

// handleOpenAppInfo 公开查询应用信息(供授权页展示真实申请权限项)
// 仅返回 appName/scopes/status 等非敏感字段,不含 app_secret
func handleOpenAppInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	clientID := strings.TrimSpace(r.URL.Query().Get("client_id"))
	if clientID == "" {
		writeJSON(w, 400, "client_id 不能为空", nil)
		return
	}
	var appName, scopes, status string
	if err := db.QueryRow("SELECT app_name, scopes, status FROM open_apps WHERE client_id = ?", clientID).
		Scan(&appName, &scopes, &status); err != nil {
		writeJSON(w, 404, "应用不存在", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"appName": appName,
		"scopes":  scopes,
		"status":  status,
	})
}

// handleOpenAuthorize 用户确认授权:校验应用已通过且请求权限不越权,签发一次性授权码(有效期10分钟)
func handleOpenAuthorize(w http.ResponseWriter, r *http.Request) {
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
		ClientID    string `json:"client_id"`
		Scopes      string `json:"scopes"`
		RedirectURI string `json:"redirect_uri"`
		State       string `json:"state"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	req.ClientID = strings.TrimSpace(req.ClientID)
	req.Scopes = strings.TrimSpace(req.Scopes)
	req.RedirectURI = strings.TrimSpace(req.RedirectURI)
	req.State = strings.TrimSpace(req.State)
	if req.ClientID == "" {
		writeJSON(w, 400, "client_id 不能为空", nil)
		return
	}
	if len(req.RedirectURI) > 500 {
		writeJSON(w, 400, "redirect_uri 过长", nil)
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
	// scopes 以应用真实申请为准:先查 open_apps 中该应用注册的 scopes,
	// 请求 scopes 必须是其子集(不得越权),授权码按应用真实申请范围签发
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
	// 生成一次性授权码(10分钟有效)
	code := "ac_" + randomString(32)
	now := time.Now().Unix()
	expiresAt := now + 600
	if _, err := db.Exec(`INSERT INTO open_auth_codes (code, client_id, user_id, scopes, state, expires_at, used, created_at)
		VALUES (?, ?, ?, ?, ?, ?, 0, ?)`,
		code, req.ClientID, userID, cleanScopes, req.State, expiresAt, now); err != nil {
		writeJSON(w, 500, "生成授权码失败:"+err.Error(), nil)
		return
	}
	log.Printf("[开放平台] 用户 %d 为应用 %s 生成授权码(scope=%s, redirect_uri=%q)", userID, req.ClientID, cleanScopes, req.RedirectURI)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"code":  code,
		"state": req.State,
	})
}

// handleOpenToken 第三方用 app_secret + 一次性授权码换取 access_token(有效期30天)
func handleOpenToken(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	var req struct {
		ClientID  string `json:"client_id"`
		AppSecret string `json:"app_secret"`
		Code      string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	req.ClientID = strings.TrimSpace(req.ClientID)
	req.AppSecret = strings.TrimSpace(req.AppSecret)
	req.Code = strings.TrimSpace(req.Code)
	if req.ClientID == "" || req.AppSecret == "" || req.Code == "" {
		writeJSON(w, 400, "client_id/app_secret/code 均不能为空", nil)
		return
	}
	// 校验应用凭证
	var appSecret, appStatus string
	if err := db.QueryRow("SELECT app_secret, status FROM open_apps WHERE client_id = ?", req.ClientID).Scan(&appSecret, &appStatus); err != nil {
		writeJSON(w, 401, "client_id 无效", nil)
		return
	}
	if appSecret == "" || appSecret != req.AppSecret {
		writeJSON(w, 401, "app_secret 不匹配", nil)
		return
	}
	if appStatus != "approved" {
		writeJSON(w, 403, "应用未通过审核", nil)
		return
	}
	// 校验授权码:存在、未被使用、未过期,且属于该 client
	var codeID int64
	var userID int64
	var scopes string
	var expiresAt int64
	var used int
	if err := db.QueryRow("SELECT id, user_id, scopes, expires_at, used FROM open_auth_codes WHERE code = ? AND client_id = ?",
		req.Code, req.ClientID).Scan(&codeID, &userID, &scopes, &expiresAt, &used); err != nil {
		writeJSON(w, 400, "code 无效", nil)
		return
	}
	if used != 0 {
		writeJSON(w, 400, "code 已被使用", nil)
		return
	}
	if time.Now().Unix() > expiresAt {
		writeJSON(w, 400, "code 已过期", nil)
		return
	}
	// 标记授权码已使用(一次性)
	if _, err := db.Exec("UPDATE open_auth_codes SET used = 1 WHERE id = ?", codeID); err != nil {
		writeJSON(w, 500, "更新授权码状态失败:"+err.Error(), nil)
		return
	}
	// 签发 access_token(30天有效)
	token := "oat_" + randomString(32)
	now := time.Now().Unix()
	expiresIn := int64(30 * 24 * 3600)
	if _, err := db.Exec(`INSERT INTO open_access_tokens (token, client_id, user_id, scopes, expires_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?)`,
		token, req.ClientID, userID, scopes, now+expiresIn, now); err != nil {
		writeJSON(w, 500, "生成访问令牌失败:"+err.Error(), nil)
		return
	}
	log.Printf("[开放平台] 应用 %s 用授权码换取访问令牌(用户 %d)", req.ClientID, userID)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"access_token": token,
		"token_type":   "Bearer",
		"expires_in":   expiresIn,
	})
}

// handleOpenUserinfo 第三方带 Bearer token 拉取用户数据:按授权时 scopes 返回,
// 尊重用户 hide_email/hide_qq 脱敏设置,头像返回公开 URL
func handleOpenUserinfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	auth := strings.TrimSpace(r.Header.Get("Authorization"))
	if !strings.HasPrefix(strings.ToLower(auth), "bearer ") {
		writeJSON(w, 401, "缺少 Bearer 令牌", nil)
		return
	}
	token := strings.TrimSpace(auth[len("bearer "):])
	if token == "" {
		writeJSON(w, 401, "缺少 Bearer 令牌", nil)
		return
	}
	var clientID string
	var userID int64
	var scopes string
	var expiresAt int64
	if err := db.QueryRow("SELECT client_id, user_id, scopes, expires_at FROM open_access_tokens WHERE token = ?", token).
		Scan(&clientID, &userID, &scopes, &expiresAt); err != nil {
		writeJSON(w, 401, "access_token 无效", nil)
		return
	}
	if time.Now().Unix() > expiresAt {
		writeJSON(w, 401, "access_token 已过期", nil)
		return
	}
	user, err := findUserByID(userID)
	if err != nil || user == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	data := map[string]interface{}{}
	for _, s := range strings.Split(scopes, ",") {
		switch s {
		case "email":
			if user.HideEmail != 1 {
				data["email"] = user.Email
			} else {
				data["email"] = ""
			}
		case "qq":
			if user.HideQQ != 1 {
				data["qq_number"] = user.QQNumber
			} else {
				data["qq_number"] = ""
			}
		case "avatar":
			data["avatar"] = fmt.Sprintf("/api/avatar/%d", user.ID)
		case "name":
			data["name"] = user.Username
		case "uid":
			data["uid"] = user.ID
		}
	}
	writeJSON(w, 200, "ok", data)
}
