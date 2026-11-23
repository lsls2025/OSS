package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"strconv"
	"strings"
	"time"
)

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
//
//	UPDATE users SET id = ? WHERE id = ?
//
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
	// 主动推送踢出信号：TCP 推送为主通道，HTTP 中间件 401 作为兜底，二者共用前端同一处理出口
	PushToUser(req.UserID, "account_kicked", map[string]interface{}{
		"message": "账号已被管理员踢出，请重新登录",
	})
	// 主动断开其 TCP 长连接（与封禁逻辑一致），避免旧连接残留
	DisconnectUser(req.UserID)
	writeJSON(w, 200, fmt.Sprintf("已踢出用户 #%d", req.UserID), nil)
}

// POST /api/admin/groups/dissolve-by-creator — 按创建者批量解散群聊（仅开发者）
//
// body: {"creator_id": 370, "dry_run": true|false}
//   - dry_run=true 仅统计、不解散（建议先跑一次确认数量）；
//   - dry_run=false 真正执行，返回已解散/失败的具体群 ID。
//
// 用途：清理"刷群"账号创建的大量群聊。安全约束：
//   - 仅开发者（ID=1 / 开发者标识）可调用；
//   - 官方群（isOfficialGroup）永不解散；
//   - 不允许对开发者自己（ID=1）执行。
func handleAdminDissolveGroupsByCreator(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	operator := getCurrentUserID(r)
	if operator == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	if !isDeveloperUser(operator) {
		writeJSON(w, 403, "仅开发者可操作", nil)
		return
	}

	var req struct {
		CreatorID int64 `json:"creator_id"`
		DryRun    bool  `json:"dry_run"`
		Limit     int   `json:"limit"` // 可选：本次最多清理多少个；<=0 或超过可解散数 = 全部
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.CreatorID <= 0 {
		writeJSON(w, 400, "无效的用户ID", nil)
		return
	}
	if req.CreatorID == 1 {
		writeJSON(w, 400, "不能对开发者自己执行", nil)
		return
	}

	// 查出该用户创建的全部群
	rows, err := db.Query("SELECT id FROM groups WHERE creator_id = ? ORDER BY id", req.CreatorID)
	if err != nil {
		log.Printf("[批量解散群] 查询失败: creator=%d err=%v", req.CreatorID, err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	var allIDs []int64
	for rows.Next() {
		var id int64
		if scanErr := rows.Scan(&id); scanErr == nil {
			allIDs = append(allIDs, id)
		}
	}
	rows.Close()

	// 排除官方群（永不解散）
	toDissolve := make([]int64, 0, len(allIDs))
	skippedOfficial := make([]int64, 0)
	for _, id := range allIDs {
		if isOfficialGroup(id) {
			skippedOfficial = append(skippedOfficial, id)
			continue
		}
		toDissolve = append(toDissolve, id)
	}

	// 可选：只清理前 N 个（limit<=0 或超过可解散数 → 全部）
	available := len(toDissolve)
	if req.Limit > 0 && req.Limit < len(toDissolve) {
		toDissolve = toDissolve[:req.Limit]
	}

	if req.DryRun {
		writeJSON(w, 200,
			fmt.Sprintf("预览：用户 #%d 共创建 %d 个群，可解散 %d 个，本次将清理 %d 个（官方群跳过 %d 个）",
				req.CreatorID, len(allIDs), available, len(toDissolve), len(skippedOfficial)),
			map[string]interface{}{
				"creator_id":       req.CreatorID,
				"total":            len(allIDs),
				"available":        available,
				"will_dissolve":    len(toDissolve),
				"group_ids":        toDissolve,
				"skipped_official": skippedOfficial,
			})
		return
	}

	dissolved := make([]int64, 0, len(toDissolve))
	failed := make([]int64, 0)
	for _, id := range toDissolve {
		if delErr := deleteGroup(id); delErr != nil {
			log.Printf("[批量解散群] 解散失败: groupID=%d err=%v", id, delErr)
			failed = append(failed, id)
			continue
		}
		dissolved = append(dissolved, id)
	}

	log.Printf("[批量解散群] 开发者 %d 解散用户 %d 创建的群: 成功=%d 失败=%d 跳过官方=%d",
		operator, req.CreatorID, len(dissolved), len(failed), len(skippedOfficial))
	writeJSON(w, 200,
		fmt.Sprintf("已解散 %d 个群聊（失败 %d 个，跳过官方群 %d 个）", len(dissolved), len(failed), len(skippedOfficial)),
		map[string]interface{}{
			"creator_id":       req.CreatorID,
			"dissolved":        dissolved,
			"failed":           failed,
			"skipped_official": skippedOfficial,
		})
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
	// 主动推送踢出信号：遍历所有被踢用户，TCP 推送为主通道，HTTP 中间件 401 作为兜底
	if idRows, qerr := db.Query("SELECT id FROM users WHERE id != 1"); qerr == nil {
		for idRows.Next() {
			var uid int64
			if idRows.Scan(&uid) == nil && uid != 1 {
				PushToUser(uid, "account_kicked", map[string]interface{}{
					"message": "账号已被管理员踢出，请重新登录",
				})
				// 主动断开其 TCP 长连接（与封禁逻辑一致），避免旧连接残留
				DisconnectUser(uid)
			}
		}
		idRows.Close()
	}
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

	// 开发者模拟登录：按手机端设备版本签发，避免带着落后版本号一登录就被中间件判失效
	token, _ := generateTokenFor(user.ID, user.Email, user.TokenVersion,
		currentDeviceVersion(user.ID, deviceMobile), deviceMobile)
	log.Printf("[开发者模拟登录] 开发者登录用户: target_user_id=%d, target_email=%s", user.ID, user.Email)

	writeJSON(w, 200, "ok", LoginResponse{
		ID:          user.ID,
		Email:       user.Email,
		Username:    user.Username,
		Signature:   user.Signature,
		Token:       token,
		IsDeveloper: isDeveloperUserObj(user),
		SignSecret:  getClientSignSecret(user.ID),
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
		Domain          string `json:"domain"`           // site: 绑定的域名
		Password        string `json:"password"`         // site: 卡密密码
		CustomKey       string `json:"custom_key"`       // site: 自定义卡密（可选）
		PermissionLevel string `json:"permission_level"` // site: 权限等级 A/B/C
		DangerQuota     int    `json:"danger_quota"`     // site: 危险操作次数
		BackupBalance   int    `json:"backup_balance"`   // site: 备份额度（可同时保留的备份数），省略默认 1
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
	log.Printf("%s 开发者生成卡密: %s (类型=%s)", logPrefix, maskSecret(keyStr), category)
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
		ID              int64  `json:"id"`
		KeyStr          string `json:"key_str"`
		DurationHours   int    `json:"duration_hours"`
		CreatedAt       int64  `json:"created_at"`
		ActivatedAt     int64  `json:"activated_at"`
		ExpiresAt       int64  `json:"expires_at"`
		UsedByUserID    int64  `json:"used_by_user_id"`
		IsActive        int    `json:"is_active"`
		UserName        string `json:"user_name"`
		Category        string `json:"category"`
		CardType        string `json:"card_type"`
		RewardType      string `json:"reward_type"`
		TokenAmount     int64  `json:"token_amount"`
		BindUserID      int64  `json:"bind_user_id"`
		Domain          string `json:"domain"`
		Password        string `json:"password"`
		PermissionLevel string `json:"permission_level"`
		DangerQuota     int    `json:"danger_quota"`
		DangerUsed      int    `json:"danger_used"`
		BackupBalance   int    `json:"backup_balance"`
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
				log.Printf("[卡密] 用户 %d 通过卡密 %s 获得 Token +%d", req.UserID, maskSecret(req.Key), tokenAmount)
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
				"valid":            true,
				"category":         "site",
				"domain":           domain,
				"permission_level": permissionLevel,
				"danger_quota":     dangerQuota,
				"danger_used":      dangerUsed,
				"backup_balance":   backupBalance,
			})
			return
		}
		writeJSON(w, 200, "卡密有效", map[string]interface{}{
			"valid":       true,
			"expires_at":  expiresAt,
			"category":    category,
			"reward_type": rewardType,
			"reward":      buildRewardInfo(),
			"domain":      domain,
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
		log.Printf("[体验版] 卡密 %s 被用户 %d 激活，有效期至 %d", maskSecret(req.Key), req.UserID, expireTime)
		// reward 类卡密：按 reward_type 实际发放奖励（token 发放 Token；member 开通/续费会员）
		applyReward()
		if category == "site" {
			writeJSON(w, 200, "卡密激活成功", map[string]interface{}{
				"valid":            true,
				"category":         "site",
				"domain":           domain,
				"permission_level": permissionLevel,
				"danger_quota":     dangerQuota,
				"danger_used":      dangerUsed,
				"backup_balance":   backupBalance,
			})
			return
		}
		writeJSON(w, 200, "卡密激活成功", map[string]interface{}{
			"valid":       true,
			"expires_at":  expireTime,
			"category":    category,
			"reward_type": rewardType,
			"reward":      buildRewardInfo(),
			"domain":      domain,
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
			"enabled":        false,
			"force_locked":   true,
			"lock_version":   lockVer,
			"client_version": clientVersion,
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
		"enabled":        enabled,
		"force_locked":   false,
		"min_supported":  minVer,
		"client_version": clientVersion,
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
