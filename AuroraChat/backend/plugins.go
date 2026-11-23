package main

// 插件市场：JSON 声明式工具插件的提交/审核/上架链路
// - 用户在客户端插件制作器里做出插件(一段 JSON 定义) → 上传到插件市场
// - 上传后进入 pending,由开发者(ID=1)在「开发者管理-插件审核」里审批
// - approve=上架 / reject=拒绝 / remove=下架(仅已上架的)
// - 市场列表只返回 approved 状态的插件

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

// ensurePluginTables 建插件相关表(在 database.go 初始化时调用)
func ensurePluginTables() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS plugin_submissions (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		uploader_id INTEGER NOT NULL,
		name TEXT NOT NULL,
		description TEXT NOT NULL DEFAULT '',
		plugin_json TEXT NOT NULL,
		status TEXT NOT NULL DEFAULT 'pending',
		review_reason TEXT NOT NULL DEFAULT '',
		reviewer_id INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL DEFAULT 0,
		reviewed_at INTEGER NOT NULL DEFAULT 0
	)`);
	err != nil {
		log.Printf("  创建 plugin_submissions 表失败: %v", err)
	} else {
		log.Println("  [插件] plugin_submissions 表已确认存在")
	}
}

type pluginSubmissionRow struct {
	ID          int64  `json:"id"`
	UploaderID  int64  `json:"uploaderId"`
	Uploader    string `json:"uploader"`
	Name        string `json:"name"`
	Description string `json:"description"`
	PluginJSON  string `json:"pluginJson"`
	Status      string `json:"status"`
	ReviewReason string `json:"reviewReason"`
	ReviewerID  int64  `json:"reviewerId"`
	CreatedAt   int64  `json:"createdAt"`
	ReviewedAt  int64  `json:"reviewedAt"`
}

// handlePluginSubmit 用户提交插件到插件市场(进入待审核)
func handlePluginSubmit(w http.ResponseWriter, r *http.Request) {
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
		Name        string `json:"name"`
		Description string `json:"description"`
		PluginJSON  string `json:"pluginJson"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	req.Description = strings.TrimSpace(req.Description)
	req.PluginJSON = strings.TrimSpace(req.PluginJSON)
	if req.Name == "" || len(req.Name) > 40 {
		writeJSON(w, 400, "插件名称不能为空且不超过 40 字", nil)
		return
	}
	if len(req.Description) > 300 {
		writeJSON(w, 400, "插件描述不超过 300 字", nil)
		return
	}
	// pluginJson 必须是合法 JSON 对象且大小受限(声明式插件本体)
	if req.PluginJSON == "" || len(req.PluginJSON) > 64*1024 {
		writeJSON(w, 400, "插件内容为空或超过 64KB 限制", nil)
		return
	}
	var probe map[string]interface{}
	if err := json.Unmarshal([]byte(req.PluginJSON), &probe); err != nil || probe == nil {
		writeJSON(w, 400, "插件内容不是合法的 JSON", nil)
		return
	}
	// 同名待审/已上架插件不允许重复提交(防刷屏与冒充)
	var exists int
	db.QueryRow("SELECT COUNT(*) FROM plugin_submissions WHERE name = ? AND status IN ('pending','approved')", req.Name).Scan(&exists)
	if exists > 0 {
		writeJSON(w, 400, "已存在同名插件(待审或已上架),请换个名字", nil)
		return
	}
	// 单人待审数量限制(防刷)
	var pending int
	db.QueryRow("SELECT COUNT(*) FROM plugin_submissions WHERE uploader_id = ? AND status = 'pending'", userID).Scan(&pending)
	if pending >= 10 {
		writeJSON(w, 400, "你有太多待审核的插件,请等待审核", nil)
		return
	}
	res, err := db.Exec("INSERT INTO plugin_submissions (uploader_id, name, description, plugin_json, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)",
		userID, req.Name, req.Description, req.PluginJSON, time.Now().Unix())
	if err != nil {
		writeJSON(w, 500, "提交失败:"+err.Error(), nil)
		return
	}
	id, _ := res.LastInsertId()
	log.Printf("[插件] 用户 %d 提交插件 %q (id=%d) 进入待审核", userID, req.Name, id)
	writeJSON(w, 200, "已提交,等待开发者审核", map[string]interface{}{"id": id})
}

// handlePluginMarket 市场列表:默认只返回已上架(approved)的插件;
// ?mine=1 时额外包含当前用户自建的全部状态插件(自己的插件无需审核即可自用)
func handlePluginMarket(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	includeMine := r.URL.Query().Get("mine") == "1"
	var rows *sql.Rows
	var err error
	if includeMine {
		rows, err = db.Query(`SELECT p.id, p.uploader_id, IFNULL(u.username,''), p.name, p.description, p.plugin_json, p.status, p.created_at
			FROM plugin_submissions p LEFT JOIN users u ON u.id = p.uploader_id
			WHERE p.status = 'approved' OR p.uploader_id = ? ORDER BY (p.status = 'approved') DESC, p.created_at DESC`, userID)
	} else {
		rows, err = db.Query(`SELECT p.id, p.uploader_id, IFNULL(u.username,''), p.name, p.description, p.plugin_json, p.status, p.created_at
			FROM plugin_submissions p LEFT JOIN users u ON u.id = p.uploader_id
			WHERE p.status = 'approved' ORDER BY p.reviewed_at DESC`)
	}
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	list := []map[string]interface{}{}
	for rows.Next() {
		var it pluginSubmissionRow
		var createdAt int64
		var status string
		if err := rows.Scan(&it.ID, &it.UploaderID, &it.Uploader, &it.Name, &it.Description, &it.PluginJSON, &status, &createdAt); err == nil {
			list = append(list, map[string]interface{}{
				"id": it.ID, "uploaderId": it.UploaderID, "uploader": it.Uploader,
				"name": it.Name, "description": it.Description,
				"pluginJson": it.PluginJSON, "status": status, "createdAt": createdAt,
			})
		}
	}
	writeJSON(w, 200, "ok", list)
}

// handlePluginMine 我的插件:当前登录用户提交过的全部插件(任意状态,含 AI 代为提交的)
func handlePluginMine(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	rows, err := db.Query(`SELECT p.id, p.uploader_id, IFNULL(u.username,''), p.name, p.description, p.plugin_json, p.status,
		p.review_reason, p.created_at, p.reviewed_at
		FROM plugin_submissions p LEFT JOIN users u ON u.id = p.uploader_id
		WHERE p.uploader_id = ? ORDER BY p.created_at DESC LIMIT 200`, userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	list := []map[string]interface{}{}
	for rows.Next() {
		var it pluginSubmissionRow
		var createdAt, reviewedAt int64
		if err := rows.Scan(&it.ID, &it.UploaderID, &it.Uploader, &it.Name, &it.Description, &it.PluginJSON, &it.Status,
			&it.ReviewReason, &createdAt, &reviewedAt); err == nil {
			list = append(list, map[string]interface{}{
				"id": it.ID, "uploaderId": it.UploaderID, "uploader": it.Uploader,
				"name": it.Name, "description": it.Description, "pluginJson": it.PluginJSON,
				"status": it.Status, "reviewReason": it.ReviewReason,
				"createdAt": createdAt, "reviewedAt": reviewedAt,
			})
		}
	}
	writeJSON(w, 200, "ok", list)
}

// handlePluginDelete 删除自己创建的插件(任意状态均可删,已上架的删除后从市场消失):仅上传者本人可删,物理删除记录
func handlePluginDelete(w http.ResponseWriter, r *http.Request) {
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
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	res, err := db.Exec(`DELETE FROM plugin_submissions WHERE id = ? AND uploader_id = ?`, req.ID, userID)
	if err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		writeJSON(w, 404, "插件不存在或无权删除", nil)
		return
	}
	log.Printf("[plugin] user#%d deleted plugin#%d", userID, req.ID)
	writeJSON(w, 200, "ok", nil)
}

// handlePluginAdminList 开发者审核列表:?status=pending|approved|rejected|removed|all
func handlePluginAdminList(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "plugin.review") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	status := strings.TrimSpace(r.URL.Query().Get("status"))
	if status == "" {
		status = "pending"
	}
	where := "p.status = ?"
	if status == "all" {
		where = "p.status != ''"
	}
	rows, err := db.Query(`SELECT p.id, p.uploader_id, IFNULL(u.username,''), p.name, p.description, p.plugin_json, p.status,
		p.review_reason, p.reviewer_id, p.created_at, p.reviewed_at
		FROM plugin_submissions p LEFT JOIN users u ON u.id = p.uploader_id
		WHERE ` + where + ` ORDER BY p.created_at DESC LIMIT 200`, status)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	list := []map[string]interface{}{}
	for rows.Next() {
		var it pluginSubmissionRow
		var createdAt, reviewedAt int64
		if err := rows.Scan(&it.ID, &it.UploaderID, &it.Uploader, &it.Name, &it.Description, &it.PluginJSON, &it.Status,
			&it.ReviewReason, &it.ReviewerID, &createdAt, &reviewedAt); err == nil {
			list = append(list, map[string]interface{}{
				"id": it.ID, "uploaderId": it.UploaderID, "uploader": it.Uploader,
				"name": it.Name, "description": it.Description, "pluginJson": it.PluginJSON,
				"status": it.Status, "reviewReason": it.ReviewReason, "reviewerId": it.ReviewerID,
				"createdAt": createdAt, "reviewedAt": reviewedAt,
			})
		}
	}
	writeJSON(w, 200, "ok", list)
}

// handlePluginAdminReview 审核操作:approve=上架 / reject=拒绝 / remove=下架
func handlePluginAdminReview(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "plugin.review") {
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
			req.Reason = "不符合上架要求"
		}
	case "remove":
		newStatus = "removed"
	default:
		writeJSON(w, 400, "action 必须为 approve/reject/remove", nil)
		return
	}
	// remove 仅针对已上架;reject 仅针对待审
	var curStatus string
	if err := db.QueryRow("SELECT status FROM plugin_submissions WHERE id = ?", req.ID).Scan(&curStatus); err != nil {
		writeJSON(w, 404, "插件不存在", nil)
		return
	}
	if req.Action == "remove" && curStatus != "approved" {
		writeJSON(w, 400, "只能下架已上架的插件", nil)
		return
	}
	if req.Action == "reject" && curStatus != "pending" {
		writeJSON(w, 400, "只能拒绝待审核的插件", nil)
		return
	}
	if _, err := db.Exec("UPDATE plugin_submissions SET status = ?, review_reason = ?, reviewer_id = ?, reviewed_at = ? WHERE id = ?",
		newStatus, req.Reason, reviewerID, now, req.ID); err != nil {
		writeJSON(w, 500, "操作失败:"+err.Error(), nil)
		return
	}
	log.Printf("[插件] 开发者 %d 对插件 id=%d 执行 %s → %s", reviewerID, req.ID, req.Action, newStatus)
	writeJSON(w, 200, "操作成功", map[string]interface{}{"status": newStatus})
}
