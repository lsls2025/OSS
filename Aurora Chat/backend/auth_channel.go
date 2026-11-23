package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

// 账号通道边界。注册通道只经 /api/register 建号，登录通道只校验身份。
// legacy_auth_channel 置 1 时整体退回旧通道行为。

const authFlagLegacyChannel = "legacy_auth_channel"

func useLegacyAuthChannel() bool {
	return getFlag(authFlagLegacyChannel)
}

// accountConflict 统一注册标识占用口径。
func accountConflict(email, qq string) (int, string) {
	if qq != "" {
		if u, _ := findUserByQQNumber(qq); u != nil {
			return 409, "该QQ号已被其他账号绑定"
		}
		var id int64
		err := db.QueryRow("SELECT id FROM users WHERE qq_openid = ? AND "+notDeletedCond, "qq:"+qq).Scan(&id)
		if err == nil && id > 0 {
			return 409, "该QQ号已被其他账号绑定"
		}
	}
	if email != "" {
		if u, _ := findUserByEmail(email); u != nil {
			return 409, "该邮箱已被注册"
		}
	}
	return 0, ""
}

// reclaimLegacyPassword 为无密码的存量账号写入首次提交的密码。
func reclaimLegacyPassword(userID int64, password string) (string, bool) {
	hashed, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", false
	}
	res, err := db.Exec(
		"UPDATE users SET password = ? WHERE id = ? AND (password IS NULL OR password = '')",
		string(hashed), userID,
	)
	if err != nil {
		return "", false
	}
	n, err := res.RowsAffected()
	if err != nil || n == 0 {
		return "", false
	}
	log.Printf("[通道] 存量账号补齐密码: ID=%d", userID)
	return string(hashed), true
}

func handleQQLogin(w http.ResponseWriter, r *http.Request) {
	if useLegacyAuthChannel() {
		handleQQLoginLegacy(w, r)
		return
	}
	handleQQLoginStrict(w, r)
}

// handleQQLoginStrict 登录通道：只认已绑定账号，不建号。
func handleQQLoginStrict(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	var req struct {
		OpenID      string `json:"openid"`
		AccessToken string `json:"access_token"`
		QQ          string `json:"qq"`
	}
	limitJSONBody(w, r, maxAuthBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.OpenID = strings.TrimSpace(req.OpenID)
	req.AccessToken = strings.TrimSpace(req.AccessToken)
	req.QQ = strings.TrimSpace(req.QQ)

	if req.AccessToken == "" {
		writeJSON(w, 403, "缺少 QQ 授权信息", nil)
		return
	}
	verifiedOpenID, err := verifyQQOpenID(req.AccessToken)
	if err != nil {
		log.Printf("[通道] QQ 授权校验失败: %v", err)
		writeJSON(w, 401, "QQ 授权校验失败", nil)
		return
	}
	if req.OpenID != "" && req.OpenID != verifiedOpenID {
		writeJSON(w, 401, "QQ 授权信息不一致", nil)
		return
	}
	req.OpenID = verifiedOpenID

	if ok, wait := checkLoginRateLimit(getClientIP(r), req.OpenID); !ok {
		writeJSON(w, 429, fmt.Sprintf("登录尝试过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	existing, _ := getUserByQQOpenid(req.OpenID)
	if existing == nil || existing.ID == 0 {
		writeJSON(w, 404, "该QQ号未绑定账号", nil)
		return
	}
	if existing.QQNumber != "" && !isValidQQFormat(existing.QQNumber) {
		log.Printf("[通道] 检测到异常 QQ 号账号 ID=%d qq=%s，自动注销", existing.ID, existing.QQNumber)
		_ = deleteUserAllData(existing.ID)
		writeJSON(w, 403, "账号异常已被注销", nil)
		return
	}

	// QQ 登录属于手机端通道：只抬高 mobile 设备版本，不会把已登录的桌面端顶下线。
	db.Exec("UPDATE users SET kicked_at = 0 WHERE id = ?", existing.ID)
	deviceVersion := currentDeviceVersion(existing.ID, deviceMobile)
	if getFlag("kick_on_new_login") {
		deviceVersion = bumpDeviceVersion(existing.ID, deviceMobile)
	}
	var currentVersion int64
	db.QueryRow("SELECT token_version FROM users WHERE id = ?", existing.ID).Scan(&currentVersion)

	token, _ := generateTokenFor(existing.ID, existing.Email, currentVersion, deviceVersion, deviceMobile)
	log.Printf("[通道] QQ 用户登录成功: ID=%d, Username=%s", existing.ID, existing.Username)
	writeJSON(w, 200, "登录成功", LoginResponse{
		ID:                     existing.ID,
		Email:                  existing.Email,
		Username:               existing.Username,
		Signature:              existing.Signature,
		Token:                  token,
		EmailVerified:          existing.EmailVerified != 0,
		NeedsProfileCompletion: needsProfileCompletion(existing),
		QQNumber:               existing.QQNumber,
		IsDeveloper:            isDeveloperUserObj(existing),
		SignSecret:             getClientSignSecret(existing.ID),
	})
}
