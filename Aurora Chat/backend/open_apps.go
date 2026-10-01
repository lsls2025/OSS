package main

// 第三方接入开放平台：应用申请/审核/吊销/删除/管理员主动创建
// - 用户(普通登录用户)在「开发者管理 → 申请第三方接入」提交申请 → 进入 pending
// - 管理员(open_apps.manage)在「开发者管理 → 应用」里审核/吊销/删除/主动创建
// - 管理接口统一 checkDevOrPerm(r, "open_apps.manage") 鉴权;用户申请接口由路由 authMiddleware 保证登录

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// ensureOpenAppsTable 建第三方接入应用表(在 database.go 初始化时调用)
func ensureOpenAppsTable() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS open_apps (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		app_name TEXT NOT NULL,
		client_id TEXT NOT NULL UNIQUE,
		app_secret TEXT NOT NULL DEFAULT '',
		owner_user_id INTEGER NOT NULL DEFAULT 0,
		scopes TEXT NOT NULL DEFAULT '',
		status TEXT NOT NULL DEFAULT 'pending',
		apply_reason TEXT NOT NULL DEFAULT '',
		contact TEXT NOT NULL DEFAULT '',
		remark TEXT NOT NULL DEFAULT '',
		reviewer_id INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL DEFAULT 0,
		reviewed_at INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  创建 open_apps 表失败: %v", err)
	} else {
		log.Println("  [开放平台] open_apps 表已确认存在")
	}
	if _, err := db.Exec("CREATE INDEX IF NOT EXISTS idx_open_apps_owner ON open_apps(owner_user_id)"); err != nil {
		log.Printf("  提示: open_apps 索引创建失败: %v", err)
	}
}

type openAppRow struct {
	ID          int64  `json:"id"`
	AppName     string `json:"appName"`
	ClientID    string `json:"clientId"`
	AppSecret   string `json:"appSecret"`
	OwnerUserID int64  `json:"ownerUserId"`
	Owner       string `json:"owner"`
	Scopes      string `json:"scopes"`
	Status      string `json:"status"`
	ApplyReason string `json:"applyReason"`
	Contact     string `json:"contact"`
	Remark      string `json:"remark"`
	CreatedAt   int64  `json:"createdAt"`
	ReviewedAt  int64  `json:"reviewedAt"`
}

// newClientID 生成唯一 client_id(带前缀,防碰撞重试一次)
func newClientID() string {
	for i := 0; i < 3; i++ {
		cid := "cli_" + randomString(20)
		var n int
		db.QueryRow("SELECT COUNT(*) FROM open_apps WHERE client_id = ?", cid).Scan(&n)
		if n == 0 {
			return cid
		}
	}
	return "cli_" + randomString(20) + "_" + randomString(6)
}

// handleOpenAdminList 管理员列表:返回全部申请记录(含申请人用户名),?status=过滤
func handleOpenAdminList(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "open_apps.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	status := strings.TrimSpace(r.URL.Query().Get("status"))
	where := "1=1"
	args := []interface{}{}
	if status != "" && status != "all" {
		where = "a.status = ?"
		args = append(args, status)
	}
	query := `SELECT a.id, a.app_name, a.client_id, a.app_secret, a.owner_user_id, IFNULL(u.username,''), a.scopes,
		a.status, a.apply_reason, a.contact, a.remark, a.created_at, a.reviewed_at
		FROM open_apps a LEFT JOIN users u ON u.id = a.owner_user_id
		WHERE ` + where + ` ORDER BY a.created_at DESC LIMIT 200`
	rows, err := db.Query(query, args...)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	list := []map[string]interface{}{}
	for rows.Next() {
		var it openAppRow
		var createdAt, reviewedAt int64
		if err := rows.Scan(&it.ID, &it.AppName, &it.ClientID, &it.AppSecret, &it.OwnerUserID, &it.Owner, &it.Scopes,
			&it.Status, &it.ApplyReason, &it.Contact, &it.Remark, &createdAt, &reviewedAt); err == nil {
			list = append(list, map[string]interface{}{
				"id": it.ID, "appName": it.AppName, "clientId": it.ClientID, "appSecret": it.AppSecret,
				"ownerUserId": it.OwnerUserID, "owner": it.Owner, "scopes": it.Scopes, "status": it.Status,
				"applyReason": it.ApplyReason, "contact": it.Contact, "remark": it.Remark,
				"createdAt": createdAt, "reviewedAt": reviewedAt,
			})
		}
	}
	writeJSON(w, 200, "ok", list)
}

// handleOpenAdminReview 管理员审核:approve=同意 / reject=拒绝
func handleOpenAdminReview(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "open_apps.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		ID     int64  `json:"id"`
		Action string `json:"action"`
		Reason string `json:"reason"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	reviewerID := getCurrentUserID(r)
	now := time.Now().Unix()
	req.Reason = strings.TrimSpace(req.Reason)
	var newStatus string
	switch req.Action {
	case "approve":
		newStatus = "approved"
	case "reject":
		newStatus = "rejected"
		if req.Reason == "" {
			req.Reason = "不符合接入要求"
		}
	default:
		writeJSON(w, 400, "action 必须为 approve/reject", nil)
		return
	}
	var curStatus string
	if err := db.QueryRow("SELECT status FROM open_apps WHERE id = ?", req.ID).Scan(&curStatus); err != nil {
		writeJSON(w, 404, "申请不存在", nil)
		return
	}
	if req.Action == "approve" && curStatus != "pending" {
		writeJSON(w, 400, "只能同意待审核的申请", nil)
		return
	}
	if req.Action == "reject" && curStatus != "pending" {
		writeJSON(w, 400, "只能拒绝待审核的申请", nil)
		return
	}
	if _, err := db.Exec("UPDATE open_apps SET status = ?, reviewer_id = ?, reviewed_at = ? WHERE id = ?",
		newStatus, reviewerID, now, req.ID); err != nil {
		writeJSON(w, 500, "操作失败:"+err.Error(), nil)
		return
	}
	log.Printf("[开放平台] 管理员 %d 对应用 id=%d 执行 %s → %s(理由:%s)", reviewerID, req.ID, req.Action, newStatus, req.Reason)
	writeJSON(w, 200, "操作成功", map[string]interface{}{"status": newStatus})
}

// handleOpenAdminRevoke 管理员吊销:仅已通过(approved)的应用可吊销 → revoked
func handleOpenAdminRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "open_apps.manage") {
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
	var curStatus string
	if err := db.QueryRow("SELECT status FROM open_apps WHERE id = ?", req.ID).Scan(&curStatus); err != nil {
		writeJSON(w, 404, "应用不存在", nil)
		return
	}
	if curStatus != "approved" {
		writeJSON(w, 400, "只能吊销已通过的应用", nil)
		return
	}
	reviewerID := getCurrentUserID(r)
	now := time.Now().Unix()
	if _, err := db.Exec("UPDATE open_apps SET status = 'revoked', reviewer_id = ?, reviewed_at = ? WHERE id = ?", reviewerID, now, req.ID); err != nil {
		writeJSON(w, 500, "操作失败:"+err.Error(), nil)
		return
	}
	log.Printf("[开放平台] 管理员 %d 吊销应用 id=%d", reviewerID, req.ID)
	writeJSON(w, 200, "已吊销", map[string]interface{}{"status": "revoked"})
}

// handleOpenAdminDelete 管理员删除:物理删除记录(任意状态)
func handleOpenAdminDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "open_apps.manage") {
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
	res, err := db.Exec("DELETE FROM open_apps WHERE id = ?", req.ID)
	if err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		writeJSON(w, 404, "应用不存在", nil)
		return
	}
	log.Printf("[开放平台] 管理员 %d 删除应用 id=%d", getCurrentUserID(r), req.ID)
	writeJSON(w, 200, "已删除", nil)
}

// handleOpenAdminCreate 管理员主动创建:手动创建一个应用并直接通过(approved)
// owner 支持用户名或 userId(app_name 必填,owner 必填,scopes/remark 可选)
func handleOpenAdminCreate(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "open_apps.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		AppName string `json:"appName"`
		Owner   string `json:"owner"`
		Scopes  string `json:"scopes"`
		Remark  string `json:"remark"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	req.AppName = strings.TrimSpace(req.AppName)
	req.Owner = strings.TrimSpace(req.Owner)
	req.Scopes = strings.TrimSpace(req.Scopes)
	req.Remark = strings.TrimSpace(req.Remark)
	// scopes 白名单清洗:仅允许 email,qq,avatar,name,uid,非法项直接拒绝
	cleanScopes, invalid := sanitizeScopes(req.Scopes)
	if invalid {
		writeJSON(w, 400, "权限项不合法,仅支持 email,qq,avatar,name,uid", nil)
		return
	}
	req.Scopes = cleanScopes
	if req.AppName == "" || len(req.AppName) > 40 {
		writeJSON(w, 400, "应用名称不能为空且不超过 40 字", nil)
		return
	}
	if req.Owner == "" {
		writeJSON(w, 400, "请填写使用人用户名或 ID", nil)
		return
	}
	if len(req.Remark) > 300 {
		writeJSON(w, 400, "备注不超过 300 字", nil)
		return
	}
	// 解析 owner:优先按 userId(纯数字),否则按用户名
	var ownerID int64
	if isAllDigits(req.Owner) {
		u, err := findUserByID(parseInt64Safe(req.Owner))
		if err == nil && u != nil {
			ownerID = u.ID
		}
	}
	if ownerID <= 0 {
		u, err := findUserByUsername(req.Owner)
		if err == nil && u != nil {
			ownerID = u.ID
		}
	}
	if ownerID <= 0 {
		writeJSON(w, 400, "找不到该使用人用户(用户名或 ID 不正确)", nil)
		return
	}
	clientID := newClientID()
	appSecret := "sec_" + randomString(32)
	now := time.Now().Unix()
	reviewerID := getCurrentUserID(r)
	res, err := db.Exec(`INSERT INTO open_apps (app_name, client_id, app_secret, owner_user_id, scopes, status,
		apply_reason, contact, remark, reviewer_id, created_at, reviewed_at) VALUES (?, ?, ?, ?, ?, 'approved', '', '', ?, ?, ?, ?)`,
		req.AppName, clientID, appSecret, ownerID, req.Scopes, req.Remark, reviewerID, now, now)
	if err != nil {
		writeJSON(w, 500, "创建失败:"+err.Error(), nil)
		return
	}
	id, _ := res.LastInsertId()
	log.Printf("[开放平台] 管理员 %d 主动创建应用 %q (id=%d, client=%s) 给用户 %d", reviewerID, req.AppName, id, clientID, ownerID)
	writeJSON(w, 200, "创建成功", map[string]interface{}{
		"id": id, "appName": req.AppName, "clientId": clientID, "appSecret": appSecret,
		"ownerUserId": ownerID, "scopes": req.Scopes, "status": "approved",
	})
}

// handleOpenApply 用户提交第三方接入申请(进入 pending);路由已由 authMiddleware 保证登录
func handleOpenApply(w http.ResponseWriter, r *http.Request) {
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
		AppName string `json:"appName"`
		Reason  string `json:"reason"`
		Scopes  string `json:"scopes"`
		Contact string `json:"contact"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	req.AppName = strings.TrimSpace(req.AppName)
	req.Reason = strings.TrimSpace(req.Reason)
	req.Scopes = strings.TrimSpace(req.Scopes)
	req.Contact = strings.TrimSpace(req.Contact)
	// scopes 白名单清洗:仅允许 email,qq,avatar,name,uid,非法项直接拒绝
	cleanScopes, invalid := sanitizeScopes(req.Scopes)
	if invalid {
		writeJSON(w, 400, "权限项不合法,仅支持 email,qq,avatar,name,uid", nil)
		return
	}
	req.Scopes = cleanScopes
	if req.AppName == "" || len(req.AppName) > 40 {
		writeJSON(w, 400, "应用名称不能为空且不超过 40 字", nil)
		return
	}
	if req.Reason == "" || len(req.Reason) > 500 {
		writeJSON(w, 400, "申请理由不能为空且不超过 500 字", nil)
		return
	}
	if req.Scopes == "" {
		writeJSON(w, 400, "请至少选择一项所需权限", nil)
		return
	}
	if req.Contact == "" || len(req.Contact) > 100 {
		writeJSON(w, 400, "联系方式不能为空且不超过 100 字", nil)
		return
	}
	// 同名待审/已通过不允许重复提交(防刷屏)
	var exists int
	db.QueryRow("SELECT COUNT(*) FROM open_apps WHERE app_name = ? AND status IN ('pending','approved')", req.AppName).Scan(&exists)
	if exists > 0 {
		writeJSON(w, 400, "已存在同名应用(待审核或已通过),请换个名字", nil)
		return
	}
	// 单人待审数量限制(防刷)
	var pending int
	db.QueryRow("SELECT COUNT(*) FROM open_apps WHERE owner_user_id = ? AND status = 'pending'", userID).Scan(&pending)
	if pending >= 10 {
		writeJSON(w, 400, "你有太多待审核的申请,请等待审核", nil)
		return
	}
	res, err := db.Exec(`INSERT INTO open_apps (app_name, client_id, app_secret, owner_user_id, scopes, status,
		apply_reason, contact, remark, created_at) VALUES (?, ?, '', ?, ?, 'pending', ?, ?, '', ?)`,
		req.AppName, newClientID(), userID, req.Scopes, req.Reason, req.Contact, time.Now().Unix())
	if err != nil {
		writeJSON(w, 500, "提交失败:"+err.Error(), nil)
		return
	}
	id, _ := res.LastInsertId()
	log.Printf("[开放平台] 用户 %d 提交第三方接入申请 %q (id=%d) 进入待审核", userID, req.AppName, id)
	writeJSON(w, 200, "已提交,等待管理员审核", map[string]interface{}{"id": id})
}

// sanitizeScopes 对 scopes 做白名单清洗:仅允许 email/qq/avatar/name/uid,
// 去重并统一小写;含非法项时返回 (空串, true)
func sanitizeScopes(s string) (string, bool) {
	allowed := map[string]bool{"email": true, "qq": true, "avatar": true, "name": true, "uid": true}
	seen := make(map[string]bool)
	var parts []string
	for _, p := range strings.Split(s, ",") {
		p = strings.ToLower(strings.TrimSpace(p))
		if p == "" || seen[p] {
			continue
		}
		if !allowed[p] {
			return "", true
		}
		seen[p] = true
		parts = append(parts, p)
	}
	return strings.Join(parts, ","), false
}

// isAllDigits 判断字符串是否全为数字(用于 owner 传 userId 的场景)
func isAllDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, ch := range s {
		if ch < '0' || ch > '9' {
			return false
		}
	}
	return true
}

// parseInt64Safe 安全解析 int64,失败返回 0
func parseInt64Safe(s string) int64 {
	v, _ := strconv.ParseInt(s, 10, 64)
	return v
}
