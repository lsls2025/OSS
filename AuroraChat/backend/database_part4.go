package main

import (
	"database/sql"
	"fmt"
	"golang.org/x/crypto/bcrypt"
	"log"
	_ "modernc.org/sqlite"
	"time"
)

func getBanStatus(userID int64) (*BanRecord, error) {
	r := &BanRecord{}
	err := db.QueryRow("SELECT user_id, reason, banned_at, expires_at, banned_by, unban_notify, unban_popup_message FROM banned_users WHERE user_id = ?", userID).Scan(&r.UserID, &r.Reason, &r.BannedAt, &r.ExpiresAt, &r.BannedBy, &r.UnbanNotify, &r.UnbanPopupMessage)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	now := time.Now().Unix()
	if now >= r.ExpiresAt {
		db.Exec("DELETE FROM banned_users WHERE user_id = ?", userID)
		return nil, nil
	}
	return r, nil
}

func getAllBannedUsers() ([]BanRecord, error) {
	now := time.Now().Unix()
	rows, err := db.Query("SELECT user_id, reason, banned_at, expires_at, banned_by, unban_notify, unban_popup_message FROM banned_users WHERE expires_at > ?", now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var bans []BanRecord
	for rows.Next() {
		var r BanRecord
		if err := rows.Scan(&r.UserID, &r.Reason, &r.BannedAt, &r.ExpiresAt, &r.BannedBy, &r.UnbanNotify, &r.UnbanPopupMessage); err != nil {
			return nil, err
		}
		bans = append(bans, r)
	}
	return bans, nil
}

// ==================== 禁言管理 ====================

type MuteRecord struct {
	UserID    int64 `json:"user_id"`
	MutedBy   int64 `json:"muted_by"`
	MutedAt   int64 `json:"muted_at"`
	ExpiresAt int64 `json:"expires_at"`
	MuteType  int   `json:"mute_type"`
}

func muteUser(userID int64, durationSeconds int64, mutedBy int64, muteType int) error {
	now := time.Now().Unix()
	expiresAt := now + durationSeconds
	_, err := db.Exec("INSERT OR REPLACE INTO muted_users (user_id, muted_by, muted_at, expires_at, mute_type) VALUES (?, ?, ?, ?, ?)", userID, mutedBy, now, expiresAt, muteType)
	return err
}

func unmuteUser(userID int64) error {
	_, err := db.Exec("DELETE FROM muted_users WHERE user_id = ?", userID)
	return err
}

func getMuteStatus(userID int64) (*MuteRecord, error) {
	r := &MuteRecord{}
	err := db.QueryRow("SELECT user_id, muted_by, muted_at, expires_at, mute_type FROM muted_users WHERE user_id = ?", userID).Scan(&r.UserID, &r.MutedBy, &r.MutedAt, &r.ExpiresAt, &r.MuteType)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	now := time.Now().Unix()
	if now >= r.ExpiresAt {
		db.Exec("DELETE FROM muted_users WHERE user_id = ?", userID)
		return nil, nil
	}
	return r, nil
}

// ==================== 群内禁言 ====================

func groupMuteUser(groupID int64, userID int64, durationSeconds int64, mutedBy int64) error {
	now := time.Now().Unix()
	expiresAt := now + durationSeconds
	_, err := db.Exec("INSERT OR REPLACE INTO group_mutes (group_id, user_id, muted_by, muted_at, expires_at) VALUES (?, ?, ?, ?, ?)",
		groupID, userID, mutedBy, now, expiresAt)
	return err
}

func groupUnmuteUser(groupID int64, userID int64) error {
	_, err := db.Exec("DELETE FROM group_mutes WHERE group_id = ? AND user_id = ?", groupID, userID)
	return err
}

// isGroupMuted 检查用户在群内是否被禁言，返回 (是否禁言, expires_at)
func isGroupMuted(groupID int64, userID int64) (bool, int64) {
	var expiresAt int64
	err := db.QueryRow("SELECT expires_at FROM group_mutes WHERE group_id = ? AND user_id = ?", groupID, userID).Scan(&expiresAt)
	if err != nil {
		return false, 0
	}
	now := time.Now().Unix()
	if now >= expiresAt {
		db.Exec("DELETE FROM group_mutes WHERE group_id = ? AND user_id = ?", groupID, userID)
		return false, 0
	}
	return true, expiresAt
}

func getAllGroupMutes() ([]map[string]interface{}, error) {
	now := time.Now().Unix()
	rows, err := db.Query("SELECT group_id, user_id, muted_by, muted_at, expires_at FROM group_mutes WHERE expires_at > ?", now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []map[string]interface{}
	for rows.Next() {
		var gid, uid, mutedBy, mutedAt, expiresAt int64
		if err := rows.Scan(&gid, &uid, &mutedBy, &mutedAt, &expiresAt); err != nil {
			continue
		}
		result = append(result, map[string]interface{}{
			"group_id":   gid,
			"user_id":    uid,
			"muted_by":   mutedBy,
			"muted_at":   mutedAt,
			"expires_at": expiresAt,
		})
	}
	return result, nil
}

// getGroupMuteMembers 获取群内所有被禁言成员及其信息
func getGroupMuteMembers(groupID int64) ([]map[string]interface{}, error) {
	now := time.Now().Unix()
	rows, err := db.Query(
		`SELECT gm.user_id, gm.expires_at, COALESCE(u.username, '')
		 FROM group_mutes gm
		 LEFT JOIN users u ON gm.user_id = u.id
		 WHERE gm.group_id = ? AND gm.expires_at > ?`, groupID, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []map[string]interface{}
	for rows.Next() {
		var uid, expiresAt int64
		var username string
		if err := rows.Scan(&uid, &expiresAt, &username); err != nil {
			continue
		}
		if username == "" {
			username = fmt.Sprintf("用户%d", uid)
		}
		result = append(result, map[string]interface{}{
			"user_id":    uid,
			"expires_at": expiresAt,
			"username":   username,
		})
	}
	return result, nil
}

func getAllMutedUsers() ([]MuteRecord, error) {
	now := time.Now().Unix()
	rows, err := db.Query("SELECT user_id, muted_by, muted_at, expires_at, mute_type FROM muted_users WHERE expires_at > ?", now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var mutes []MuteRecord
	for rows.Next() {
		var r MuteRecord
		if err := rows.Scan(&r.UserID, &r.MutedBy, &r.MutedAt, &r.ExpiresAt, &r.MuteType); err != nil {
			return nil, err
		}
		mutes = append(mutes, r)
	}
	return mutes, nil
}

// ==================== 平台管理员 ====================

type PlatformAdminRecord struct {
	UserID    int64 `json:"user_id"`
	GrantedBy int64 `json:"granted_by"`
	GrantedAt int64 `json:"granted_at"`
}

func isPlatformAdmin(userID int64) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM platform_admins WHERE user_id = ?", userID).Scan(&count)
	return count > 0, err
}

func grantPlatformAdmin(userID, grantedBy int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO platform_admins (user_id, granted_by, granted_at) VALUES (?, ?, ?)", userID, grantedBy, time.Now().Unix())
	return err
}

func revokePlatformAdmin(userID int64) error {
	_, err := db.Exec("DELETE FROM platform_admins WHERE user_id = ?", userID)
	return err
}

func getAllPlatformAdmins() ([]PlatformAdminRecord, error) {
	rows, err := db.Query("SELECT user_id, granted_by, granted_at FROM platform_admins ORDER BY granted_at DESC")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var admins []PlatformAdminRecord
	for rows.Next() {
		var r PlatformAdminRecord
		if err := rows.Scan(&r.UserID, &r.GrantedBy, &r.GrantedAt); err != nil {
			return nil, err
		}
		admins = append(admins, r)
	}
	return admins, nil
}

// revokeAllAdminPermissions 一键收回除 keepUserID 外所有用户的平台管理员与细粒度权限。
// 注意：只删除"有授权记录"的行，绝不能整体清空 user_permissions，
// 否则按 checkUserPermission 的兼容规则，空记录反而会被视为拥有全部权限。
func revokeAllAdminPermissions(keepUserID int64) (adminCount, permCount int, err error) {
	tx, err := db.Begin()
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()

	res, err := tx.Exec("DELETE FROM platform_admins WHERE user_id != ?", keepUserID)
	if err != nil {
		return 0, 0, err
	}
	if n, _ := res.RowsAffected(); n > 0 {
		adminCount = int(n)
	}

	// 只清空被授予过细粒度权限的用户，避免"空记录=全权限"的兼容陷阱
	res2, err := tx.Exec("DELETE FROM user_permissions WHERE user_id != ?", keepUserID)
	if err != nil {
		return 0, 0, err
	}
	if n, _ := res2.RowsAffected(); n > 0 {
		permCount = int(n)
	}

	if err := tx.Commit(); err != nil {
		return 0, 0, err
	}
	return adminCount, permCount, nil
}

// ==================== 细粒度权限 ====================

func getUserPermissions(userID int64) ([]string, error) {
	rows, err := db.Query("SELECT perm_key FROM user_permissions WHERE user_id = ?", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var keys []string
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		keys = append(keys, k)
	}
	return keys, nil
}

func saveUserPermissions(userID int64, keys []string) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	tx.Exec("DELETE FROM user_permissions WHERE user_id = ?", userID)
	for _, k := range keys {
		tx.Exec("INSERT OR IGNORE INTO user_permissions (user_id, perm_key) VALUES (?, ?)", userID, k)
	}
	return tx.Commit()
}

func hasPermission(userID int64, permKey string) bool {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM user_permissions WHERE user_id = ? AND perm_key = ?", userID, permKey).Scan(&count)
	return err == nil && count > 0
}

// checkUserPermission 检查用户是否有指定的细粒度管理权限
// 兼容规则：
//   - 用户没有任何权限记录（空列表）→ 视为有全部权限，兼容旧版
//   - 大类 key（如 "users"）覆盖所有子权限（如 users.ban, users.mute...）
//   - 子权限 key 精确匹配
func checkUserPermission(userID int64, permKey string) bool {
	var count int
	db.QueryRow("SELECT COUNT(*) FROM user_permissions WHERE user_id = ?", userID).Scan(&count)
	// 无任何权限记录 = 兼容旧版：允许所有操作
	if count == 0 {
		return true
	}
	// 精确匹配子权限 key
	if hasPermission(userID, permKey) {
		return true
	}
	// 大类 key 匹配（如 permKey="users.ban"，检查 "users" 是否被勾选）
	for i := 0; i < len(permKey); i++ {
		if permKey[i] == '.' {
			if hasPermission(userID, permKey[:i]) {
				return true
			}
			break
		}
	}
	return false
}

// ==================== 管理员操作日志 ====================

type AdminOperationLog struct {
	ID             int64  `json:"id"`
	AdminUserID    int64  `json:"admin_user_id"`
	AdminUsername  string `json:"admin_username"`
	OperationType  string `json:"operation_type"`
	TargetUserID   int64  `json:"target_user_id"`
	TargetUsername string `json:"target_username"`
	Details        string `json:"details"`
	CreatedAt      int64  `json:"created_at"`
}

func addAdminOperationLog(adminUserID int64, adminUsername, operationType string, targetUserID int64, targetUsername, details string) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT INTO admin_operation_logs (admin_user_id, admin_username, operation_type, target_user_id, target_username, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", adminUserID, adminUsername, operationType, targetUserID, targetUsername, details, now)
	return err
}

func getAdminOperationLogs(limit, offset int) ([]AdminOperationLog, int, error) {
	var total int
	db.QueryRow("SELECT COUNT(*) FROM admin_operation_logs").Scan(&total)
	rows, err := db.Query("SELECT id, admin_user_id, admin_username, operation_type, target_user_id, target_username, details, created_at FROM admin_operation_logs ORDER BY created_at DESC LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var logs []AdminOperationLog
	for rows.Next() {
		var l AdminOperationLog
		if err := rows.Scan(&l.ID, &l.AdminUserID, &l.AdminUsername, &l.OperationType, &l.TargetUserID, &l.TargetUsername, &l.Details, &l.CreatedAt); err != nil {
			return nil, 0, err
		}
		logs = append(logs, l)
	}
	return logs, total, nil
}

func getAdminOperationLogsByAdmin(adminUserID int64, limit, offset int) ([]AdminOperationLog, int, error) {
	var total int
	db.QueryRow("SELECT COUNT(*) FROM admin_operation_logs WHERE admin_user_id = ?", adminUserID).Scan(&total)
	rows, err := db.Query("SELECT id, admin_user_id, admin_username, operation_type, target_user_id, target_username, details, created_at FROM admin_operation_logs WHERE admin_user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?", adminUserID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var logs []AdminOperationLog
	for rows.Next() {
		var l AdminOperationLog
		if err := rows.Scan(&l.ID, &l.AdminUserID, &l.AdminUsername, &l.OperationType, &l.TargetUserID, &l.TargetUsername, &l.Details, &l.CreatedAt); err != nil {
			return nil, 0, err
		}
		logs = append(logs, l)
	}
	return logs, total, nil
}

// ==================== 用户位置记录 ====================

type UserLocationRecord struct {
	ID        int64   `json:"id"`
	UserID    int64   `json:"user_id"`
	Lat       float64 `json:"lat"`
	Lng       float64 `json:"lng"`
	Address   string  `json:"address"`
	CreatedAt int64   `json:"created_at"`
}

func addUserLocation(userID int64, lat, lng float64, address string) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT INTO user_locations (user_id, lat, lng, address, created_at) VALUES (?, ?, ?, ?, ?)",
		userID, lat, lng, address, now)
	return err
}

func getUserLocations(userID int64) ([]UserLocationRecord, error) {
	rows, err := db.Query("SELECT id, user_id, lat, lng, address, created_at FROM user_locations WHERE user_id = ? ORDER BY created_at DESC",
		userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var records []UserLocationRecord
	for rows.Next() {
		var r UserLocationRecord
		if err := rows.Scan(&r.ID, &r.UserID, &r.Lat, &r.Lng, &r.Address, &r.CreatedAt); err != nil {
			return nil, err
		}
		records = append(records, r)
	}
	return records, nil
}

// ==================== 闪照查看记录（防清缓存重看） ====================

func recordFlashView(userID, messageID int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO flash_views (user_id, message_id, viewed_at) VALUES (?, ?, ?)", userID, messageID, time.Now().Unix())
	return err
}

func hasUserViewedFlash(userID, messageID int64) bool {
	var count int
	db.QueryRow("SELECT COUNT(*) FROM flash_views WHERE user_id = ? AND message_id = ?", userID, messageID).Scan(&count)
	return count > 0
}

func getViewedFlashIDs(userID int64) []int64 {
	rows, err := db.Query("SELECT message_id FROM flash_views WHERE user_id = ?", userID)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		ids = append(ids, id)
	}
	return ids
}

// ==================== User Security ====================

type UserSecurity struct {
	UserID          int64  `json:"user_id"`
	PasswordHash    string `json:"-"`
	GesturePattern  string `json:"-"`
	SecurityEnabled int    `json:"security_enabled"`
	MultiVerify     int    `json:"multi_verify"`
	CreatedAt       int64  `json:"created_at"`
	UpdatedAt       int64  `json:"updated_at"`
}

func saveUserSecurity(sec *UserSecurity) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT INTO user_security (user_id, password_hash, gesture_pattern, security_enabled, multi_verify, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET password_hash = COALESCE(NULLIF(?, ''), password_hash), gesture_pattern = COALESCE(NULLIF(?, ''), gesture_pattern), security_enabled = ?, multi_verify = COALESCE(?, multi_verify), updated_at = ?", sec.UserID, sec.PasswordHash, sec.GesturePattern, sec.SecurityEnabled, sec.MultiVerify, now, now, sec.PasswordHash, sec.GesturePattern, sec.SecurityEnabled, sec.MultiVerify, now)
	return err
}

func getUserSecurity(userID int64) (*UserSecurity, error) {
	sec := &UserSecurity{}
	err := db.QueryRow("SELECT user_id, password_hash, gesture_pattern, security_enabled, multi_verify, created_at, updated_at FROM user_security WHERE user_id = ?", userID).Scan(&sec.UserID, &sec.PasswordHash, &sec.GesturePattern, &sec.SecurityEnabled, &sec.MultiVerify, &sec.CreatedAt, &sec.UpdatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return sec, nil
}

// ==================== Server Registration ====================

type ServerRecord struct {
	ID           int64  `json:"id"`
	OwnerUserID  int64  `json:"owner_user_id"`
	OwnerName    string `json:"owner_username"`
	Name         string `json:"name"`
	Domain       string `json:"domain"`
	Password     string `json:"-"`
	CreatedAt    int64  `json:"created_at"`
	ServerStatus string `json:"server_status"`
	LastActiveAt int64  `json:"last_active_at"`
}

func findServerByOwner(userID int64) (*ServerRecord, error) {
	s := &ServerRecord{}
	// server_status / last_active_at 必须一起查出来：handleShutdownServer / handleRestartServer
	// 靠 ServerStatus 判断「已关机/处理中」，结构体里一直是零值的话这两个判断永远不成立（重复点击会互相覆盖状态）。
	err := db.QueryRow("SELECT s.id, s.owner_user_id, COALESCE(u.username,''), s.name, s.domain, s.password, s.created_at, COALESCE(s.server_status,''), COALESCE(s.last_active_at,0) FROM servers s JOIN users u ON s.owner_user_id = u.id WHERE s.owner_user_id = ?", userID).
		Scan(&s.ID, &s.OwnerUserID, &s.OwnerName, &s.Name, &s.Domain, &s.Password, &s.CreatedAt, &s.ServerStatus, &s.LastActiveAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return s, nil
}

func createServer(ownerUserID int64, name, domain, hashedPassword string) (int64, error) {
	now := time.Now().Unix()
	result, err := db.Exec("INSERT INTO servers (owner_user_id, name, domain, password, created_at) VALUES (?, ?, ?, ?, ?)", ownerUserID, name, domain, hashedPassword, now)
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

// getOrCreateServerByOwner 获取或自动创建用户的服务器（用于文件托管等功能，无需用户手动注册）
func getOrCreateServerByOwner(userID int64) (*ServerRecord, error) {
	// 先查找现有服务器
	s, err := findServerByOwner(userID)
	if err != nil {
		return nil, err
	}
	if s != nil {
		return s, nil
	}

	// 没有服务器，自动创建一个（用于文件托管/网站托管）
	// 获取用户名
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	if username == "" {
		username = fmt.Sprintf("用户%d", userID)
	}

	serverName := fmt.Sprintf("%s的服务器", username)
	domain := fmt.Sprintf("user%d", userID)
	// 生成一个随机密码（用户不需要知道，仅用于兼容旧字段）
	password := randomString(16)
	hashedPasswordBytes, _ := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	hashedPassword := string(hashedPasswordBytes)

	serverID, err := createServer(userID, serverName, domain, hashedPassword)
	if err != nil {
		// 并发请求同时进来时，后到的 INSERT 会撞 owner_user_id 唯一索引：
		// 此时重新查一次，能查到就说明别的请求已经建好了，直接用。
		if s2, e2 := findServerByOwner(userID); e2 == nil && s2 != nil {
			return s2, nil
		}
		return nil, err
	}
	// 新建的服务器默认就是运行中：新用户进「网站托管」不该先看到「已关机」，
	// 否则还得手动点一次开机（且旧逻辑没有记录时点开机直接 403）。
	db.Exec("UPDATE servers SET server_status = 'running', last_active_at = ? WHERE id = ?", time.Now().Unix(), serverID)

	log.Printf("[服务器] 自动为用户创建服务器: user=%d, server=%d, name=%s", userID, serverID, serverName)

	// 返回新创建的服务器
	return findServerByOwner(userID)
}

func updateServerLastActive(serverID int64) {
	db.Exec("UPDATE servers SET last_active_at = ? WHERE id = ?", time.Now().Unix(), serverID)
}

func computeServerStatus(serverID int64) string {
	// 优先读取 server_status 字段（手动设置的 stopped/stopping/restarting 等）
	var serverStatus string
	err := db.QueryRow("SELECT server_status FROM servers WHERE id = ?", serverID).Scan(&serverStatus)
	if err != nil {
		return "offline"
	}
	// 开关机现在是同步立即生效（没有过渡态），这里顺手把老数据里可能残留的
	// stopping / restarting 收敛掉：否则前端会一直卡在「重启中…」且按钮全隐藏，只能等死。
	switch serverStatus {
	case "stopped", "stopping":
		return "stopped"
	case "restarting":
		return "running"
	}
	// 显式「运行中」以状态字段为准，不再用 last_active_at 反推。
	// 否则页面停留超过 5 分钟再点「开机」：asyncRestartServer 刚把状态写成 running，
	// 轮询接口 computeServerStatus 却因为 last_active_at 过期返回 offline，
	// 前端立刻退回「已关机」——表现就是开机/重启按钮点了没反应。
	if serverStatus == "running" {
		return "running"
	}

	// 状态字段为空（旧数据/从未开关过机）：才退回按最近活跃时间估算

	var lastActiveAt int64
	err = db.QueryRow("SELECT last_active_at FROM servers WHERE id = ?", serverID).Scan(&lastActiveAt)
	if err != nil {
		return "offline"
	}
	if lastActiveAt == 0 {
		return "offline"
	}
	now := time.Now().Unix()
	if now-lastActiveAt <= 300 {
		return "running"
	}
	return "offline"
}

func setServerStatus(serverID int64, status string) error {
	_, err := db.Exec("UPDATE servers SET server_status = ? WHERE id = ?", status, serverID)
	return err
}

// 关机：状态立即落到 stopped，并同步给站点静态服务（150）。
//
// 旧实现是「先写 stopping → sleep 3 秒 → 再写 stopped」，而且站点静态服务根本不读这个状态，
// 结果是用户点完关机后公网站点还会正常显示很久，才突然变成关机页。
// 现在不做任何假等待：写库 + 推送同步完成，请求返回时站点已经不可访问。
func asyncShutdownServer(serverID int64) {
	setServerStatus(serverID, "stopped")
	if err := pushServerStateToSiteServer(serverID, "stopped"); err != nil {
		log.Printf("[服务器] 关机状态同步站点服务失败 server=%d: %v", serverID, err)
	}
}

// 开机/重启：同样立即恢复为 running 并通知站点服务撤销拦截。
func asyncRestartServer(serverID int64) {
	setServerStatus(serverID, "running")
	if err := pushServerStateToSiteServer(serverID, "running"); err != nil {
		log.Printf("[服务器] 开机状态同步站点服务失败 server=%d: %v", serverID, err)
	}
}

// syncServerStatesToSiteServer 启动时把「非运行中」的服务器状态补推给站点服务。
// 覆盖两种会漏状态的情况：① 站点服务重启过，只有落盘文件里的旧记录；
// ② 状态是这次改动之前就被置为 stopped 的存量数据。
func syncServerStatesToSiteServer() {
	rows, err := db.Query("SELECT id, COALESCE(server_status,'') FROM servers")
	if err != nil {
		log.Printf("[站点状态] 启动同步跳过: %v", err)
		return
	}
	defer rows.Close()
	type item struct {
		id     int64
		status string
	}
	var items []item
	for rows.Next() {
		var it item
		if rows.Scan(&it.id, &it.status) != nil {
			continue
		}
		if it.status != "" && it.status != "running" {
			items = append(items, it)
		}
	}
	if len(items) == 0 {
		return
	}
	for _, it := range items {
		if err := pushServerStateToSiteServer(it.id, it.status); err != nil {
			log.Printf("[站点状态] 启动同步失败 server=%d: %v", it.id, err)
		}
	}
	log.Printf("[站点状态] 启动同步完成，共 %d 台非运行中服务器", len(items))
}

func updateServerDomainByID(serverID int64, newDomain string) error {
	_, err := db.Exec("UPDATE servers SET domain = ? WHERE id = ?", newDomain, serverID)
	return err
}
