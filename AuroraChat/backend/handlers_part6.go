package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

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
//  1. 清空 ai_chat_shares / ai_chat_purchases / ai_chat_comments 及
//     source_code_shares / source_code_purchases / source_code_comments 六表全部记录（容错，表不存在则忽略）；
//  2. 删除 <程序目录>/data/ai_chat 与 <程序目录>/data/source_code 下所有上传的静态文件
//     （截图、代码包、压缩包等）。
//
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
// 范围 scope: "user"(按用户 ID 列表) | "all"(全部在线+离线) | "range"(ID 区块)
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
		Scope   string    `json:"scope"`
		UserIDs []int64   `json:"user_ids"`
		Ranges  [][]int64 `json:"ranges"`
		Title   string    `json:"title"`
		Content string    `json:"content"`
		Extra   string    `json:"extra"`
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
		for _, uid := range req.UserIDs {
			if uid <= 0 {
				continue
			}
			var exists int
			if e := db.QueryRow("SELECT 1 FROM users WHERE id = ? AND "+notDeletedCond, uid).Scan(&exists); e == nil {
				addID(uid)
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
		writeJSON(w, 400, "未找到匹配的用户（请检查用户 ID 是否正确，或账号是否已注销）", nil)
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
	tk, err := parseToken(tokenStr)
	if err != nil || tk.UserID <= 0 {
		writeJSON(w, 401, "登录状态异常", nil)
		return
	}
	userID, tokenVersion := tk.UserID, tk.TokenVersion
	// 该接口没走 authMiddleware（JWT 自校验），这里补上登录态失效校验，
	// 否则被踢下线或已被管理员踢出的旧 token 仍能继续拉取系统通知。
	// 口径与中间件一致：全局版本 + 本设备类别版本。
	var sysNoticeDbVer, sysNoticeDevVer int64
	if qerr := db.QueryRow("SELECT token_version, "+deviceVersionColumn(tk.Device)+" FROM users WHERE id = ?", userID).
		Scan(&sysNoticeDbVer, &sysNoticeDevVer); qerr == nil &&
		(tokenVersion < sysNoticeDbVer || tk.DeviceVersion < sysNoticeDevVer) {
		writeJSON(w, 401, "登录状态已失效，请重新登录", nil)
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
		Scope   string    `json:"scope"`    // user_ids | all | range
		UserIDs []int64   `json:"user_ids"` // scope=user_ids 时有效
		Ranges  [][]int64 `json:"ranges"`   // scope=range 时有效，每项为 [min,max]
		Op      string    `json:"op"`       // add | deduct | reset
		Amount  int64     `json:"amount"`   // add/deduct 时有效，须 >0
		Reason  string    `json:"reason"`
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
	case "user_ids":
		for _, uid := range req.UserIDs {
			addID(uid)
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
