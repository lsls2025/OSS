package main

import (
	"encoding/json"
	"log"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// ==================== 付费 API 提交与审核 ====================
// 用户提交付费 API（含价格信息），状态 pending；开发者审核同意后上架，用户在「选择付费 API」中选用，
// 消耗用户 Token 余额（由上游 API 提供方实际扣费）。

// POST /api/paid-api/submit — 用户提交付费 API
func handleSubmitPaidApi(w http.ResponseWriter, r *http.Request) {
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
		ApiKey      string `json:"api_key"`
		BaseURL     string `json:"base_url"`
		ModelName   string `json:"model_name"`
		InputPrice  string `json:"input_price"`
		OutputPrice string `json:"output_price"`
		PriceNote   string `json:"price_note"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.ApiKey = strings.TrimSpace(req.ApiKey)
	req.BaseURL = strings.TrimSpace(req.BaseURL)
	req.ModelName = strings.TrimSpace(req.ModelName)
	req.InputPrice = strings.TrimSpace(req.InputPrice)
	req.OutputPrice = strings.TrimSpace(req.OutputPrice)
	req.PriceNote = strings.TrimSpace(req.PriceNote)
	if req.ApiKey == "" || req.BaseURL == "" || req.ModelName == "" {
		writeJSON(w, 400, "API Key、Base URL、模型名均不能为空", nil)
		return
	}
	if req.InputPrice == "" || req.OutputPrice == "" {
		writeJSON(w, 400, "请填写输入和输出价格", nil)
		return
	}
	lowerURL := strings.ToLower(req.BaseURL)
	if !strings.HasPrefix(lowerURL, "https://") {
		writeJSON(w, 400, "Base URL 必须以 https:// 开头", nil)
		return
	}
	// 基础限流：普通用户 1 小时内最多提交 3 次，防刷屏；开发者不受限
	if !isDeveloperUser(submitterID) {
		var recent int
		db.QueryRow("SELECT COUNT(*) FROM paid_apis WHERE submitter_id = ? AND created_at > ?", submitterID, time.Now().Unix()-3600).Scan(&recent)
		if recent >= 3 {
			writeJSON(w, 429, "提交过于频繁，请 1 小时后再试", nil)
			return
		}
	}
	// 只拦「完全相同」的重复提交：Key + 地址 + 模型名三者全部一致才算同一个 API。
	// 同名模型、不同 Key 是不同的资源，允许并存；开发者提交不做查重限制。
	if !isDeveloperUser(submitterID) {
		var dup int
		db.QueryRow(`SELECT COUNT(*) FROM paid_apis
			WHERE LOWER(base_url)=LOWER(?) AND api_key=? AND LOWER(model_name)=LOWER(?) AND status IN ('pending','approved')`,
			req.BaseURL, req.ApiKey, req.ModelName).Scan(&dup)
		if dup > 0 {
			writeJSON(w, 400, "完全相同的付费 API 已存在（Key、地址、模型完全一致），请勿重复提交", nil)
			return
		}
	}
	now := time.Now().Unix()
	_, err := db.Exec(
		`INSERT INTO paid_apis (submitter_id, api_key, base_url, model_name, input_price, output_price, price_note, status, created_at)
		 VALUES (?,?,?,?,?,?,?,'pending',?)`,
		submitterID, req.ApiKey, req.BaseURL, req.ModelName, req.InputPrice, req.OutputPrice, req.PriceNote, now,
	)
	if err != nil {
		log.Printf("[付费API] 提交失败 submitter=%d err=%v", submitterID, err)
		writeJSON(w, 500, "提交失败", nil)
		return
	}
	writeJSON(w, 200, "提交成功，等待审核", nil)
}

// GET /api/paid-api/list — 开发者/管理员查看所有付费 API 提交
func handleAdminListPaidApis(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "paid_apis.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	rows, err := db.Query(`SELECT pa.id, pa.submitter_id, pa.api_key, pa.base_url, pa.model_name,
		pa.input_price, pa.output_price, pa.price_note, pa.status, pa.created_at, pa.reviewed_at,
		COALESCE(u.username, ''), COALESCE(u.email, '')
		FROM paid_apis pa LEFT JOIN users u ON pa.submitter_id = u.id ORDER BY pa.id DESC LIMIT 500`)
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
		InputPrice  string `json:"input_price"`
		OutputPrice string `json:"output_price"`
		PriceNote   string `json:"price_note"`
		Status      string `json:"status"`
		CreatedAt   int64  `json:"created_at"`
		ReviewedAt  int64  `json:"reviewed_at"`
		UserName    string `json:"user_name"`
		Email       string `json:"email"`
	}
	var items []Item
	for rows.Next() {
		var it Item
		if err := rows.Scan(&it.ID, &it.SubmitterID, &it.ApiKey, &it.BaseURL, &it.ModelName,
			&it.InputPrice, &it.OutputPrice, &it.PriceNote, &it.Status, &it.CreatedAt, &it.ReviewedAt,
			&it.UserName, &it.Email); err != nil {
			continue
		}
		items = append(items, it)
	}
	writeJSON(w, 200, "成功", map[string]interface{}{"items": items})
}

// POST /api/paid-api/review — 开发者同意(approve)或拒绝(reject)
func handleAdminReviewPaidApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "paid_apis.manage") {
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
	res, err := db.Exec("UPDATE paid_apis SET status = ?, reviewed_at = ?, reviewer_id = ? WHERE id = ?", status, now, reviewerID, req.ID)
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

// POST /api/paid-api/takedown — 下架已通过的付费 API
func handleAdminTakedownPaidApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "paid_apis.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	reviewerID := getCurrentUserID(r)
	var req struct {
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var st string
	db.QueryRow("SELECT status FROM paid_apis WHERE id = ?", req.ID).Scan(&st)
	if st == "" {
		writeJSON(w, 404, "提交不存在", nil)
		return
	}
	if st != "approved" {
		writeJSON(w, 400, "只能下架已通过的付费 API", nil)
		return
	}
	now := time.Now().Unix()
	res, err := db.Exec("UPDATE paid_apis SET status = 'takedown', reviewed_at = ?, reviewer_id = ? WHERE id = ?", now, reviewerID, req.ID)
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

// POST /api/paid-api/delete — 删除已下架/已拒绝的付费 API 记录
func handleAdminDeletePaidApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "paid_apis.manage") {
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
	res, err := db.Exec("DELETE FROM paid_apis WHERE id = ? AND status IN ('takedown','rejected')", req.ID)
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

// GET /api/paid-api/available — 普通用户获取「已通过」的付费 API 列表
// 安全（密钥下发修复）：不再返回 api_key。上传者的 Key 只在服务端持有，
// 客户端对话走 /api/paid-api/chat 中转、生图走 /api/paid-api/image 中转，
// 任何登录用户都无法再从此接口收割全部上架 API 的明文密钥。
func handleListAvailablePaidApis(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if getCurrentUserID(r) == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	rows, err := db.Query(`SELECT pa.id, pa.base_url, pa.model_name, pa.input_price, pa.output_price,
		pa.price_note, pa.submitter_id, COALESCE(u.username, '')
		FROM paid_apis pa LEFT JOIN users u ON pa.submitter_id = u.id WHERE pa.status = 'approved' ORDER BY pa.id DESC LIMIT 200`)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	type Item struct {
		ID            int64  `json:"id"`
		BaseURL       string `json:"base_url"`
		Model         string `json:"model_name"`
		InputPrice    string `json:"input_price"`
		OutputPrice   string `json:"output_price"`
		PriceNote     string `json:"price_note"`
		SubmitterID   int64  `json:"submitter_id"`
		SubmitterName string `json:"submitter_name"`
	}
	var items []Item
	for rows.Next() {
		var it Item
		if err := rows.Scan(&it.ID, &it.BaseURL, &it.Model, &it.InputPrice, &it.OutputPrice,
			&it.PriceNote, &it.SubmitterID, &it.SubmitterName); err != nil {
			continue
		}
		items = append(items, it)
	}
	writeJSON(w, 200, "成功", map[string]interface{}{"items": items})
}

// ensurePaidApiTables 初始化 paid_apis 表（若无则自动创建）
func ensurePaidApiTables() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS paid_apis (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		submitter_id INTEGER NOT NULL,
		api_key TEXT NOT NULL,
		base_url TEXT NOT NULL,
		model_name TEXT NOT NULL,
		input_price TEXT NOT NULL,
		output_price TEXT NOT NULL,
		price_note TEXT,
		status TEXT NOT NULL DEFAULT 'pending',
		reviewer_id INTEGER DEFAULT 0,
		created_at INTEGER NOT NULL,
		reviewed_at INTEGER DEFAULT 0
	)`); err != nil {
		log.Printf("  [付费API] 创建 paid_apis 表失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS paid_api_usage (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		paid_api_id INTEGER NOT NULL,
		model_name TEXT NOT NULL,
		base_url TEXT NOT NULL,
		input_tokens INTEGER NOT NULL DEFAULT 0,
		output_tokens INTEGER NOT NULL DEFAULT 0,
		cost_tokens INTEGER NOT NULL DEFAULT 0,
		input_price TEXT,
		output_price TEXT,
		created_at INTEGER NOT NULL
	)`); err != nil {
		log.Printf("  [付费API] 创建 paid_api_usage 表失败(可忽略): %v", err)
	}
	// 幂等防重列：客户端每次扣费携带唯一 request_id，唯一索引保证同一请求只记账一次（数据不可乱）
	if _, err := db.Exec(`ALTER TABLE paid_api_usage ADD COLUMN request_id TEXT`); err != nil {
		// 已存在该列时忽略
	}
	if _, err := db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS idx_paid_api_usage_request_id
		ON paid_api_usage(request_id) WHERE request_id IS NOT NULL AND request_id <> ''`); err != nil {
		log.Printf("  [付费API] 创建 request_id 唯一索引失败(可忽略): %v", err)
	}
}

// POST /api/paid-api/consume — 使用者上报一次付费 API 调用并扣费
// 按上传者填写的 input_price/output_price(倍率:每 1 token 扣多少余额)换算扣费:
// cost = input_tokens*input_price + output_tokens*output_price
func handleConsumePaidApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		ApiID        int64  `json:"api_id"`
		InputTokens  int64  `json:"input_tokens"`
		OutputTokens int64  `json:"output_tokens"`
		RequestID    string `json:"request_id"`
		BaseURL      string `json:"base_url"`
		ModelName    string `json:"model_name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	var baseURL, modelName, inputPrice, outputPrice, status string
	var err error
	var apiID int64
	if req.ApiID > 0 {
		err = db.QueryRow("SELECT id, base_url, model_name, input_price, output_price, status FROM paid_apis WHERE id = ?", req.ApiID).
			Scan(&apiID, &baseURL, &modelName, &inputPrice, &outputPrice, &status)
		if err != nil || status != "approved" {
			log.Printf("[付费API] consume 按 api_id=%d 未命中 approved，尝试按 base_url+model 兜底匹配", req.ApiID)
			apiID = 0
		}
	}
	if apiID <= 0 {
		// 兜底：客户端可能因本地 prefs 丢失/线上库重建导致 paid_api_id 失效，
		// 此时按用户实际配置的 base_url+model_name 匹配已上架 API，保证扣费不落空
		req.BaseURL = strings.TrimSpace(strings.TrimRight(req.BaseURL, "/"))
		req.ModelName = strings.TrimSpace(req.ModelName)
		if req.BaseURL == "" || req.ModelName == "" {
			writeJSON(w, 400, "参数错误", nil)
			return
		}
		err = db.QueryRow("SELECT id, base_url, model_name, input_price, output_price, status FROM paid_apis WHERE LOWER(base_url)=LOWER(?) AND LOWER(model_name)=LOWER(?)", req.BaseURL, req.ModelName).
			Scan(&apiID, &baseURL, &modelName, &inputPrice, &outputPrice, &status)
		if err != nil || status != "approved" {
			log.Printf("[付费API] consume 按 base_url=%s model=%s 也未匹配到 approved 记录: %v", req.BaseURL, req.ModelName, err)
			writeJSON(w, 404, "付费 API 不存在或未上架", nil)
			return
		}
		log.Printf("[付费API] consume 通过 base_url+model 兜底匹配到 api_id=%d", apiID)
	}
	_ = apiID
	cost := chargeTokensFromPrice(inputPrice, req.InputTokens) + chargeTokensFromPrice(outputPrice, req.OutputTokens)
	if cost <= 0 {
		writeJSON(w, 400, "扣费金额无效", nil)
		return
	}
	// 幂等防重：同一 request_id 已处理过则直接返回原扣费结果，避免客户端重试导致重复扣费/重复记账
	req.RequestID = strings.TrimSpace(req.RequestID)
	if req.RequestID != "" {
		var prevCost int64
		if err := db.QueryRow("SELECT cost_tokens FROM paid_api_usage WHERE user_id = ? AND request_id = ?", userID, req.RequestID).Scan(&prevCost); err == nil {
			var curBalance int64
			_ = db.QueryRow("SELECT token_balance FROM users WHERE id = ?", userID).Scan(&curBalance)
			writeJSON(w, 200, "扣费成功(已处理过)", map[string]interface{}{
				"cost_tokens": prevCost, "input_tokens": req.InputTokens, "output_tokens": req.OutputTokens, "balance": curBalance,
			})
			return
		}
	}
	tx, err := db.Begin()
	if err != nil {
		writeJSON(w, 500, "系统繁忙", nil)
		return
	}
	defer tx.Rollback()
	var balance int64
	if err := tx.QueryRow("SELECT token_balance FROM users WHERE id = ?", userID).Scan(&balance); err != nil {
		writeJSON(w, 500, "用户信息获取失败", nil)
		return
	}
	if balance < cost {
		writeJSON(w, 402, "余额不足", nil)
		return
	}
	now := time.Now().Unix()
	if _, err := tx.Exec("UPDATE users SET token_balance = token_balance - ? WHERE id = ?", cost, userID); err != nil {
		writeJSON(w, 500, "扣费失败", nil)
		return
	}
	if _, err := tx.Exec(`INSERT INTO paid_api_usage
		(user_id, paid_api_id, model_name, base_url, input_tokens, output_tokens, cost_tokens, input_price, output_price, request_id, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		userID, req.ApiID, modelName, baseURL, req.InputTokens, req.OutputTokens, cost, inputPrice, outputPrice, req.RequestID, now); err != nil {
		writeJSON(w, 500, "扣费记录写入失败", nil)
		return
	}
	if err := tx.Commit(); err != nil {
		writeJSON(w, 500, "扣费提交失败", nil)
		return
	}
	writeJSON(w, 200, "扣费成功", map[string]interface{}{
		"cost_tokens": cost, "input_tokens": req.InputTokens, "output_tokens": req.OutputTokens, "balance": balance - cost,
	})
}

// chargeTokensFromPrice 按上传者填写的倍率把 token 数换算为扣费 token 数。
// 约定:填写的倍率为「每 1 token 扣多少余额 token」(纯数字,如 0.2 表示每用 1 token 扣 0.2 余额);
// 不填/非法时按默认值 1 处理(1 token 扣 1 余额,与官方模型一致)。
func chargeTokensFromPrice(ratio string, tokens int64) int64 {
	return chargeByRatio(parsePriceRatio(ratio), tokens)
}

// parsePriceRatio 解析倍率文本;空/非法/非正数一律回落到默认 1.0(与官方模型口径一致)。
func parsePriceRatio(ratio string) float64 {
	if r := strings.TrimSpace(ratio); r != "" {
		if v, err := strconv.ParseFloat(r, 64); err == nil && v > 0 {
			return v
		}
	}
	return 1.0
}

// chargeByRatio 按倍率换算扣费:向上取整,至少扣 1 token,避免出现 0 扣费。
func chargeByRatio(multiplier float64, tokens int64) int64 {
	if tokens <= 0 {
		return 0
	}
	if multiplier <= 0 {
		multiplier = 1.0
	}
	cost := float64(tokens) * multiplier
	// 向上取整,保证至少扣 1 token,避免出现 0 扣费
	if cost < 1 {
		return 1
	}
	return int64(math.Ceil(cost))
}
