package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

// ==================== 免费 API 提交与审核 ====================
// 用户（提供者）在「提供免费 API」中提交自己的 API Key / Base URL / 模型名（模型名可选），
// 状态为 pending；开发者在「开发者管理 → API」中同意或拒绝（审核时应确认 API 真实可用）。
// 同意后该免费 API 即可在 AI 设置的「选择免费 API」中被用户选用（选用功能后续接入）。
// 已通过的免费 API 也支持下架(takedown)。

// POST /api/free-api/submit — 用户提交免费 API（待审核）
func handleSubmitFreeApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	submitterID := getCurrentUserID(r)
	if submitterID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		ApiKey    string `json:"api_key"`
		BaseURL   string `json:"base_url"`
		ModelName string `json:"model_name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.ApiKey = strings.TrimSpace(req.ApiKey)
	req.BaseURL = strings.TrimSpace(req.BaseURL)
	req.ModelName = strings.TrimSpace(req.ModelName)
	// 模型名可选；API Key 与 Base URL 必填
	if req.ApiKey == "" || req.BaseURL == "" {
		writeJSON(w, 400, "API Key、Base URL 均不能为空", nil)
		return
	}
	// Base URL 必须以 https:// 开头，否则视为乱填
	lowerURL := strings.ToLower(req.BaseURL)
	if !strings.HasPrefix(lowerURL, "https://") {
		writeJSON(w, 400, "Base URL 必须以 https:// 开头", nil)
		return
	}
	// 每个用户上传频率限制：1 小时内最多提交 1 次（含任意状态），防止刷屏/滥用
	var recent int
	db.QueryRow("SELECT COUNT(*) FROM free_apis WHERE submitter_id = ? AND created_at > ?", submitterID, time.Now().Unix()-3600).Scan(&recent)
	if recent > 0 {
		writeJSON(w, 429, "提交过于频繁，请 1 小时后再试", nil)
		return
	}
	// 同一用户只允许保留一条待审/已通过的有效提交，避免重复刷屏
	var exist int
	db.QueryRow("SELECT COUNT(*) FROM free_apis WHERE submitter_id = ? AND status IN ('pending','approved')", submitterID).Scan(&exist)
	if exist > 0 {
		writeJSON(w, 400, "您已提交过免费 API，无需重复提交", nil)
		return
	}
	now := time.Now().Unix()
	_, err := db.Exec(
		"INSERT INTO free_apis (submitter_id, api_key, base_url, model_name, status, created_at) VALUES (?,?,?,?,'pending',?)",
		submitterID, req.ApiKey, req.BaseURL, req.ModelName, now,
	)
	if err != nil {
		log.Printf("[免费API] 提交失败 submitter=%d err=%v", submitterID, err)
		writeJSON(w, 500, "提交失败", nil)
		return
	}
	writeJSON(w, 200, "提交成功，等待开发者审核", nil)
}

// GET /api/free-api/list — 开发者/管理员查看所有提交（含提交者信息）
func handleAdminListFreeApis(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "free_apis.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	rows, err := db.Query(`SELECT fa.id, fa.submitter_id, fa.api_key, fa.base_url, fa.model_name, fa.status, fa.created_at, fa.reviewed_at,
		fa.last_latency, fa.fail_count, fa.marked_down,
		COALESCE(u.username, ''), COALESCE(u.email, '')
		FROM free_apis fa LEFT JOIN users u ON fa.submitter_id = u.id ORDER BY fa.id DESC LIMIT 500`)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	type Item struct {
		ID          int64  `json:"id"`
		SubmitterID int64  `json:"submitter_id"`
		ApiKey      string `json:"api_key"`
		BaseURL     string `json:"base_url"`
		ModelName   string `json:"model_name"`
		Status      string `json:"status"`
		CreatedAt   int64  `json:"created_at"`
		ReviewedAt  int64  `json:"reviewed_at"`
		LastLatency int64  `json:"last_latency"`
		FailCount   int    `json:"fail_count"`
		MarkedDown  int    `json:"marked_down"`
		UserName    string `json:"user_name"`
		Email       string `json:"email"`
	}
	var items []Item
	for rows.Next() {
		var it Item
		if err := rows.Scan(&it.ID, &it.SubmitterID, &it.ApiKey, &it.BaseURL, &it.ModelName, &it.Status, &it.CreatedAt, &it.ReviewedAt, &it.LastLatency, &it.FailCount, &it.MarkedDown, &it.UserName, &it.Email); err != nil {
			continue
		}
		items = append(items, it)
	}
	writeJSON(w, 200, "成功", map[string]interface{}{"items": items})
}

// POST /api/free-api/review — 开发者同意(approve)或拒绝(reject)
func handleAdminReviewFreeApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "free_apis.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	reviewerID := getCurrentUserID(r)
	var req struct {
		ID     int64  `json:"id"`
		Action string `json:"action"` // approve | reject
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.ID == 0 || (req.Action != "approve" && req.Action != "reject") {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	status := "approved"
	if req.Action == "reject" {
		status = "rejected"
	}
	now := time.Now().Unix()
	res, err := db.Exec("UPDATE free_apis SET status = ?, reviewed_at = ?, reviewer_id = ? WHERE id = ?", status, now, reviewerID, req.ID)
	if err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeJSON(w, 404, "提交不存在", nil)
		return
	}
	writeJSON(w, 200, "操作成功", nil)
}

// POST /api/free-api/takedown — 开发者下架已通过的免费 API（status -> 'takedown'）
func handleAdminTakedownFreeApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "free_apis.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	reviewerID := getCurrentUserID(r)
	var req struct {
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.ID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	// 只能下架「已通过」的免费 API
	var st string
	db.QueryRow("SELECT status FROM free_apis WHERE id = ?", req.ID).Scan(&st)
	if st == "" {
		writeJSON(w, 404, "提交不存在", nil)
		return
	}
	if st != "approved" {
		writeJSON(w, 400, "只能下架已通过的免费 API", nil)
		return
	}
	now := time.Now().Unix()
	res, err := db.Exec("UPDATE free_apis SET status = 'takedown', reviewed_at = ?, reviewer_id = ? WHERE id = ?", now, reviewerID, req.ID)
	if err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeJSON(w, 404, "提交不存在", nil)
		return
	}
	writeJSON(w, 200, "已下架", nil)
}

// POST /api/free-api/delete — 删除免费 API 记录（仅限已下架/已拒绝的记录，清理列表用）
func handleAdminDeleteFreeApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "free_apis.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	// 仅允许删除「已下架」「已拒绝」的记录,避免误删在用/待审的 API
	res, err := db.Exec("DELETE FROM free_apis WHERE id = ? AND status IN ('takedown','rejected')", req.ID)
	if err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		writeJSON(w, 400, "只能删除已下架或已拒绝的记录", nil)
		return
	}
	writeJSON(w, 200, "已删除", nil)
}

// GET /api/free-api/available — 普通用户获取「已通过」的免费 API 列表（用于 AI 设置的「选择免费 API」直接调用上游）
func handleListAvailableFreeApis(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if getCurrentUserID(r) == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	rows, err := db.Query(`SELECT fa.id, fa.api_key, fa.base_url, fa.model_name, fa.last_latency, fa.marked_down, fa.submitter_id, COALESCE(u.username, '')
		FROM free_apis fa LEFT JOIN users u ON fa.submitter_id = u.id WHERE fa.status = 'approved' ORDER BY fa.id DESC LIMIT 200`)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	type Item struct {
		ID           int64  `json:"id"`
		ApiKey       string `json:"api_key"`
		BaseURL      string `json:"base_url"`
		Model        string `json:"model_name"`
		LastLatency  int64  `json:"last_latency"`
		MarkedDown   int    `json:"marked_down"`
		SubmitterID  int64  `json:"submitter_id"`
		SubmitterName string `json:"submitter_name"`
	}
	var items []Item
	for rows.Next() {
		var it Item
		if err := rows.Scan(&it.ID, &it.ApiKey, &it.BaseURL, &it.Model, &it.LastLatency, &it.MarkedDown, &it.SubmitterID, &it.SubmitterName); err != nil {
			continue
		}
		items = append(items, it)
	}
	writeJSON(w, 200, "成功", map[string]interface{}{"items": items})
}

// POST /api/free-api/ping — 用户测速后上报结果（ok=是否连通，latency=往返毫秒）。
// 命中则记延迟并清零失败计数；连续失败累计，满 3 次判定服务器「已挂」(marked_down=1)。
func handlePingFreeApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if getCurrentUserID(r) == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		ID      int64 `json:"id"`
		Ok      bool  `json:"ok"`
		Latency int64 `json:"latency"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.ID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var failCount, markedDown int
	db.QueryRow("SELECT fail_count, marked_down FROM free_apis WHERE id = ?", req.ID).Scan(&failCount, &markedDown)
	if req.Ok {
		failCount = 0
		markedDown = 0
		db.Exec("UPDATE free_apis SET last_latency = ?, fail_count = 0, marked_down = 0 WHERE id = ?", req.Latency, req.ID)
	} else {
		failCount++
		if failCount >= 3 {
			markedDown = 1
		}
		db.Exec("UPDATE free_apis SET fail_count = ?, marked_down = ? WHERE id = ?", failCount, markedDown, req.ID)
	}
	writeJSON(w, 200, "成功", map[string]interface{}{"fail_count": failCount, "marked_down": markedDown})
}

// POST /api/free-api/clear-mark — 开发者手动清除某条免费 API 的「已挂」标记与失败计数
func handleAdminClearMarkFreeApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "free_apis.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.ID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if _, err := db.Exec("UPDATE free_apis SET fail_count = 0, marked_down = 0 WHERE id = ?", req.ID); err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	writeJSON(w, 200, "已清除标记", nil)
}

// ensureFreeApiColumns 迁移：为旧库补上测速/状态列（兼容已存在的 free_apis 表）
func ensureFreeApiColumns() {
	cols := map[string]string{
		"last_latency": "INTEGER NOT NULL DEFAULT 0",
		"fail_count":   "INTEGER NOT NULL DEFAULT 0",
		"marked_down":  "INTEGER NOT NULL DEFAULT 0",
	}
	rows, err := db.Query("PRAGMA table_info(free_apis)")
	if err != nil {
		log.Printf("[免费API] 读取表结构失败(可忽略): %v", err)
		return
	}
	existing := map[string]bool{}
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull int
		var dflt interface{}
		var pk int
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			continue
		}
		existing[name] = true
	}
	rows.Close()
	for col, def := range cols {
		if !existing[col] {
			if _, e := db.Exec("ALTER TABLE free_apis ADD COLUMN " + col + " " + def); e != nil {
				log.Printf("[免费API] 新增列 %s 失败(可忽略): %v", col, e)
			}
		}
	}
}
