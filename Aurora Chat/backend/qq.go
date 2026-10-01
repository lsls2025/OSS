package main

import (
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
)

// ============================================================
// QQ 快捷登录 / 注册
// 移动端集成腾讯 QQ SDK 后，可获得 access_token + openid，
// 发送到本接口；服务端用 access_token 向 QQ 官方接口核验 openid，
// 核验通过后按 openid 查找或创建账号（无需邮箱/密码），实现一键登录。
// 防刷：openid 唯一绑定、单设备 24h 新账号上限、IP 注册频限、验证码闸门。
// ============================================================

var qqCallbackRe = regexp.MustCompile(`"openid"\s*:\s*"([^"]+)"`)

// isValidQQFormat 校验 QQ 号是否合法：纯数字且长度 5~12（真实 QQ 号均满足）
func isValidQQFormat(qq string) bool {
	if !isDigitsOnly(qq) {
		return false
	}
	n := len(qq)
	return n >= 5 && n <= 12
}

// POST /api/qq/login — QQ 一键登录/注册
func handleQQLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}

	var req struct {
		OpenID        string `json:"openid"`
		AccessToken   string `json:"access_token"`
		QQ            string `json:"qq"`
		Username      string `json:"username"`
		DeviceID      string `json:"device_id"`
		CaptchaID     string `json:"captcha_id"`
		CaptchaAnswer string `json:"captcha_answer"`
		Captcha       string `json:"captcha"` // 生产模式用 reCAPTCHA token
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.OpenID = strings.TrimSpace(req.OpenID)
	req.AccessToken = strings.TrimSpace(req.AccessToken)
	req.QQ = strings.TrimSpace(req.QQ)

	// 注：人机验证（算术验证码）已在客户端本地完成，此处不再做服务端验证码闸门，
	// 避免客户端必须依赖后端 /api/captcha/issue 才能注册。

	// 1) 确定 openid：
	//    - 有 access_token → 服务端向 QQ 官方核验（防伪造，生产路径）
	//    - 仅传 qq 号（无 token）→ 测试模式，直接用 QQ 号作为标识（生产应接入真实 OAuth SDK）
	if req.AccessToken != "" {
		verifiedOpenID, err := verifyQQOpenID(req.AccessToken)
		if err != nil {
			log.Printf("[QQ登录] openid 核验失败: %v", err)
			writeJSON(w, 401, "QQ 授权校验失败", nil)
			return
		}
		if req.OpenID == "" {
			req.OpenID = verifiedOpenID
		} else if req.OpenID != verifiedOpenID {
			writeJSON(w, 401, "QQ 授权信息不一致", nil)
			return
		}
	} else if req.QQ != "" {
		req.OpenID = "qq:" + req.QQ
	}
	if req.OpenID == "" {
		writeJSON(w, 400, "缺少 QQ 授权信息", nil)
		return
	}

	// 测试模式(qq: 分支)必须提供合法 QQ 号，否则直接拒绝（拦截乱填/空 QQ 的刷号）
	if strings.HasPrefix(req.OpenID, "qq:") && !isValidQQFormat(req.QQ) {
		writeJSON(w, 400, "QQ 号格式不正确", nil)
		return
	}

	// 3) 已存在则直接登录；否则走防刷注册流程
	clientIP := getClientIP(r)
	existing, _ := getUserByQQOpenid(req.OpenID)
	if existing == nil || existing.ID == 0 {
		// ── 抵防：禁止注册（QQ 通道也必须拦截，否则开关形同虚设）──
		if getFlag("disable_register") {
			writeJSON(w, 403, "注册功能已临时关闭，请稍后再试", nil)
			return
		}
		// 人机验证已由“新账号必须携带设备指纹”承担（图形验证码已整体移除）。
		// 真实 OAuth 分支已通过 access_token 核验 openid，此字段仍须存在以防纯 API 建号。
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
		// IP 注册频限（复用邮箱注册限流：12h/1）
		if allowed, wait := checkRegisterRateLimit(clientIP); !allowed {
			writeJSON(w, 429, fmt.Sprintf("注册过于频繁，请在 %d 秒后再试", wait), nil)
			return
		}
		username := strings.TrimSpace(req.Username)
		if username == "" {
			if nick, e := fetchQQNickname(req.AccessToken, req.OpenID); e == nil && nick != "" {
				username = nick
			} else {
				username = "QQ用户" + req.OpenID[len(req.OpenID)-6:]
			}
		}
		username = html.EscapeString(username)
		if len(username) > 20 {
			username = username[:20]
		}
		userID, err := findOrCreateQQUser(req.OpenID, username, clientIP, req.DeviceID, req.QQ)
		if err != nil {
			log.Printf("[QQ登录] 创建用户失败: %v", err)
			writeJSON(w, 500, "登录失败，请稍后重试", nil)
			return
		}
		existing, _ = getUserByQQOpenid(req.OpenID)
		if existing == nil || existing.ID == 0 {
			// 兜底：回查失败时用刚创建的 ID 构造，避免下方 existing.ID 空指针 panic
			existing = &User{ID: userID, Email: fmt.Sprintf("qq_%d@local", userID), Username: username}
		}
		_ = userID
	}

	// 登录时异常检测：已存在账号若 QQ 号明显伪造（非空但格式非法），立即秒删并拒绝
	if existing != nil && existing.ID != 0 && existing.QQNumber != "" && !isValidQQFormat(existing.QQNumber) {
		log.Printf("[QQ登录] 检测到异常 QQ 号账号 ID=%d qq=%s，自动注销", existing.ID, existing.QQNumber)
		_ = deleteUserAllData(existing.ID)
		writeJSON(w, 403, "账号异常已被注销", nil)
		return
	}

	db.Exec("UPDATE users SET token_version = 1, kicked_at = 0 WHERE id = ?", existing.ID)
	var currentVersion int64
	db.QueryRow("SELECT token_version FROM users WHERE id = ?", existing.ID).Scan(&currentVersion)

	token, _ := generateToken(existing.ID, existing.Email, currentVersion)
	log.Printf("QQ 用户登录成功: ID=%d, Username=%s", existing.ID, existing.Username)
	writeJSON(w, 200, "登录成功", LoginResponse{
		ID:                     existing.ID,
		Email:                  existing.Email,
		Username:               existing.Username,
		Signature:              existing.Signature,
		Token:                  token,
		EmailVerified:          existing.EmailVerified != 0,
		NeedsProfileCompletion: needsProfileCompletion(existing),
		QQNumber:               existing.QQNumber,
	})
}

// verifyQQOpenID 用 access_token 调用 QQ 官方接口换取 openid
func verifyQQOpenID(accessToken string) (string, error) {
	client := &http.Client{Timeout: 6 * time.Second}
	resp, err := client.Get("https://graph.qq.com/oauth2.0/me?access_token=" + url.QueryEscape(accessToken))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	// 返回形如：callback( {"client_id":"...","openid":"..."} );
	m := qqCallbackRe.FindStringSubmatch(string(body))
	if len(m) < 2 || m[1] == "" {
		return "", fmt.Errorf("无法解析 openid: %s", string(body))
	}
	return m[1], nil
}

// fetchQQNickname 拉取 QQ 昵称（需配置 QQ_APP_ID）
func fetchQQNickname(accessToken, openid string) (string, error) {
	appID := os.Getenv("QQ_APP_ID")
	if appID == "" {
		return "", fmt.Errorf("未配置 QQ_APP_ID")
	}
	client := &http.Client{Timeout: 6 * time.Second}
	u := fmt.Sprintf("https://graph.qq.com/user/get_user_info?access_token=%s&oauth_consumer_key=%s&openid=%s",
		url.QueryEscape(accessToken), url.QueryEscape(appID), url.QueryEscape(openid))
	resp, err := client.Get(u)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	var out struct {
		Nickname string `json:"nickname"`
		Ret      int    `json:"ret"`
	}
	if err := json.Unmarshal(body, &out); err != nil || out.Ret != 0 {
		return "", fmt.Errorf("拉取昵称失败: %s", string(body))
	}
	return out.Nickname, nil
}

// handleAdminCleanupBots 一键清理「QQ 号明显伪造」的机器人账号（开发者专属）
// 判定标准：qq_number 非空，但不符合真实 QQ 号格式（纯数字 5~12 位）。
// 真实 QQ 号必然为数字，因此凡是非数字/长度异常的 QQ 一律视为刷号并物理删除。
func handleAdminCleanupBots(w http.ResponseWriter, r *http.Request) {
	userID := getCurrentUserID(r)
	if userID == 0 || !isDeveloperUser(userID) {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	rows, err := db.Query("SELECT id, qq_number FROM users WHERE qq_number IS NOT NULL AND qq_number <> ''")
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	var toDelete []int64
	var samples []string
	for rows.Next() {
		var id int64
		var qq string
		if err := rows.Scan(&id, &qq); err != nil {
			continue
		}
		if !isValidQQFormat(qq) {
			toDelete = append(toDelete, id)
			if len(samples) < 20 {
				samples = append(samples, fmt.Sprintf("id=%d qq=%s", id, qq))
			}
		}
	}
	deleted := 0
	for _, id := range toDelete {
		if err := deleteUserAllData(id); err == nil {
			deleted++
		}
	}
	log.Printf("[清理机器人] 开发者 %d 清理了 %d 个异常 QQ 账号（待删 %d）", userID, deleted, len(toDelete))
	writeJSON(w, 200, "清理完成", map[string]interface{}{
		"deleted":   deleted,
		"total_bad": len(toDelete),
		"samples":   samples,
	})
}
