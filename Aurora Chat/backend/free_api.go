package main

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strconv"
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
	// 基础限流：普通用户 1 小时内最多提交 3 次（含任意状态），防刷屏；开发者不受限
	if !isDeveloperUser(submitterID) {
		var recent int
		db.QueryRow("SELECT COUNT(*) FROM free_apis WHERE submitter_id = ? AND created_at > ?", submitterID, time.Now().Unix()-3600).Scan(&recent)
		if recent >= 3 {
			writeJSON(w, 429, "提交过于频繁，请 1 小时后再试", nil)
			return
		}
	}
	// 只拦「完全相同」的重复提交：Key + 地址 + 模型名三者全部一致才算同一个 API。
	// 模型名相同但 Key 不同（同一中转站的不同账号）是**不同的资源**，允许并存——
	// 谁的 Key 更快更稳由测速和服务端探测说话，审核环节把关。
	// 开发者提交不做查重限制。
	if !isDeveloperUser(submitterID) {
		var dup int
		db.QueryRow(`SELECT COUNT(*) FROM free_apis
			WHERE LOWER(base_url)=LOWER(?) AND api_key=? AND LOWER(model_name)=LOWER(?) AND status IN ('pending','approved')`,
			req.BaseURL, req.ApiKey, req.ModelName).Scan(&dup)
		if dup > 0 {
			writeJSON(w, 400, "完全相同的免费 API 已存在（Key、地址、模型完全一致），请勿重复提交", nil)
			return
		}
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

// GET /api/free-api/available — 普通用户获取「已通过」的免费 API 列表
// 安全（密钥下发修复）：不再返回 api_key。贡献者的 Key 只在服务端持有，
// 客户端对话走 /api/free-api/chat 中转、生图走 /api/free-api/image 中转、
// 测速走 /api/free-api/test 服务端探测，任何登录用户都无法再收割全部明文密钥。
func handleListAvailableFreeApis(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if getCurrentUserID(r) == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	// 除条目本身，一并下发**服务端探测结果**（srv_*）：
	//   srv_ok  = 1 服务器可达 / 0 服务器不可达 / -1 尚未探测
	//   srv_error = 不可达时的简短原因（HTTP 码 / DNS / 超时）
	// 客户端据此把「这条 API 真挂了」与「只有我连不上」区分开 —— 二者成因与处置完全不同。
	rows, err := db.Query(`SELECT fa.id, fa.base_url, fa.model_name, fa.last_latency, fa.marked_down,
		fa.srv_ok, fa.srv_latency, fa.srv_error, fa.srv_checked_at, fa.submitter_id, COALESCE(u.username, '')
		FROM free_apis fa LEFT JOIN users u ON fa.submitter_id = u.id WHERE fa.status = 'approved' ORDER BY fa.id DESC LIMIT 200`)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	type Item struct {
		ID            int64  `json:"id"`
		BaseURL       string `json:"base_url"`
		Model         string `json:"model_name"`
		LastLatency   int64  `json:"last_latency"`
		MarkedDown    int    `json:"marked_down"`
		SrvOk         int    `json:"srv_ok"`
		SrvLatency    int64  `json:"srv_latency"`
		SrvError      string `json:"srv_error"`
		SrvCheckedAt  int64  `json:"srv_checked_at"`
		SubmitterID   int64  `json:"submitter_id"`
		SubmitterName string `json:"submitter_name"`
	}
	var items []Item
	for rows.Next() {
		var it Item
		if err := rows.Scan(&it.ID, &it.BaseURL, &it.Model, &it.LastLatency, &it.MarkedDown,
			&it.SrvOk, &it.SrvLatency, &it.SrvError, &it.SrvCheckedAt, &it.SubmitterID, &it.SubmitterName); err != nil {
			continue
		}
		items = append(items, it)
	}
	writeJSON(w, 200, "成功", map[string]interface{}{"items": items})
}

// ==================== 免费 API 后端中转（密钥不再下发） ====================
//
// 背景：免费 API 原为「客户端直连上游」模式，available 接口把贡献者的 api_key
// 明文下发给所有登录用户——任何账号登录一次即可收割全部在架密钥，并绕过平台
// 随意调用。现对话 / 生图 / 测速全部改走下面的服务端中转，Key 不出服务端。

// resolveFreeApi 按 id（优先）或 base_url+model（兜底）定位一条「已通过」的免费 API。
// 返回 (id, baseURL, apiKey, model, ok)。
func resolveFreeApi(apiID int64, baseURLHint, modelHint string) (int64, string, string, string, bool) {
	if apiID > 0 {
		var base, key, model, status string
		if db.QueryRow("SELECT base_url, api_key, model_name, status FROM free_apis WHERE id = ?", apiID).
			Scan(&base, &key, &model, &status) == nil && status == "approved" {
			return apiID, base, key, model, true
		}
	}
	baseURLHint = strings.TrimRight(strings.TrimSpace(baseURLHint), "/")
	modelHint = strings.TrimSpace(modelHint)
	if baseURLHint == "" {
		return 0, "", "", "", false
	}
	var id int64
	var base, key, model, status string
	if db.QueryRow(`SELECT id, base_url, api_key, model_name, status FROM free_apis
		WHERE LOWER(base_url)=LOWER(?) AND LOWER(model_name)=LOWER(?) AND status='approved'`,
		baseURLHint, modelHint).Scan(&id, &base, &key, &model, &status) == nil {
		return id, base, key, model, true
	}
	return 0, "", "", "", false
}

// POST /api/free-api/chat — 免费 API 对话后端中转。
// 客户端只带平台登录态 + api_id（本地 id 失效时带 base_url+model_name 兜底），
// 后端持贡献者 Key 转发到上游 /chat/completions，SSE 原样回传。
// 免费 API 不涉及平台扣费，故无余额校验/计量，仅做条目定位与转发。
func handleFreeApiChat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, 16<<20))
	if err != nil {
		writeJSON(w, 400, "请求体读取失败", nil)
		return
	}
	r.Body.Close()
	var in map[string]interface{}
	if err := json.Unmarshal(raw, &in); err != nil {
		writeJSON(w, 400, "请求体格式错误", nil)
		return
	}
	if _, ok := in["messages"].([]interface{}); !ok {
		writeJSON(w, 400, "缺少 messages", nil)
		return
	}
	apiID := int64(jsonNum(in["api_id"]))
	baseURLHint, _ := in["base_url"].(string)
	modelHint, _ := in["model_name"].(string)
	_, upstreamBase, upstreamKey, upstreamModel, ok := resolveFreeApi(apiID, baseURLHint, modelHint)
	if !ok || strings.TrimSpace(upstreamKey) == "" {
		writeJSON(w, 404, "免费 API 不存在或未上架", nil)
		return
	}
	// 组装上游请求：模型以服务端记录为准（记录为空时保留客户端传的 model）；
	// 剥掉平台自有字段；强制开流式 usage（与对话链路一致，便于排查）
	delete(in, "api_id")
	delete(in, "base_url")
	delete(in, "model_name")
	if strings.TrimSpace(upstreamModel) != "" {
		in["model"] = upstreamModel
	}
	in["stream"] = true
	in["stream_options"] = map[string]interface{}{"include_usage": true}
	outBytes, err := json.Marshal(in)
	if err != nil {
		writeJSON(w, 500, "转发数据构造失败", nil)
		return
	}
	endpoint := strings.TrimRight(upstreamBase, "/") + "/chat/completions"
	client := &http.Client{}
	upReq, err := http.NewRequestWithContext(r.Context(), http.MethodPost, endpoint, strings.NewReader(string(outBytes)))
	if err != nil {
		writeJSON(w, 500, "上游请求构造失败", nil)
		return
	}
	upReq.Header.Set("Content-Type", "application/json")
	upReq.Header.Set("Authorization", "Bearer "+upstreamKey)
	upReq.Header.Set("Accept-Encoding", "identity")
	resp, err := client.Do(upReq)
	if err != nil {
		if r.Context().Err() != nil {
			return // 客户端在拿到响应前断开：上游无消耗，直接结束
		}
		writeJSON(w, 502, "上游 API 暂不可用", nil)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		errRaw, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		msg := strings.TrimSpace(string(errRaw))
		if len(msg) > 300 {
			msg = msg[:300]
		}
		writeJSON(w, 502, "上游 API 错误("+strconv.Itoa(resp.StatusCode)+"):"+msg, nil)
		return
	}
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("Connection", "keep-alive")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	go func() {
		<-r.Context().Done()
		resp.Body.Close()
	}()
	buf := make([]byte, 32*1024)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if rerr != nil {
			return
		}
	}
}

// POST /api/paid-api/image 的同款免费版：POST /api/free-api/image — 生图中转。
// 后端持贡献者 Key 转发到上游 /images/generations，响应原样回传（非流式）。
func handleFreeApiImage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
	if err != nil {
		writeJSON(w, 400, "请求体读取失败", nil)
		return
	}
	r.Body.Close()
	var in map[string]interface{}
	if err := json.Unmarshal(raw, &in); err != nil {
		writeJSON(w, 400, "请求体格式错误", nil)
		return
	}
	apiID := int64(jsonNum(in["api_id"]))
	baseURLHint, _ := in["base_url"].(string)
	modelHint, _ := in["model_name"].(string)
	id, upstreamBase, upstreamKey, _, ok := resolveFreeApi(apiID, baseURLHint, modelHint)
	if !ok || strings.TrimSpace(upstreamKey) == "" {
		writeJSON(w, 404, "免费 API 不存在或未上架", nil)
		return
	}
	delete(in, "api_id")
	delete(in, "base_url")
	delete(in, "model_name")
	outBytes, err := json.Marshal(in)
	if err != nil {
		writeJSON(w, 500, "转发数据构造失败", nil)
		return
	}
	endpoint := strings.TrimRight(upstreamBase, "/") + "/images/generations"
	ctx, cancel := context.WithTimeout(r.Context(), 180*time.Second)
	defer cancel()
	upReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(string(outBytes)))
	if err != nil {
		writeJSON(w, 500, "上游请求构造失败", nil)
		return
	}
	upReq.Header.Set("Content-Type", "application/json")
	upReq.Header.Set("Authorization", "Bearer "+upstreamKey)
	upReq.Header.Set("Accept-Encoding", "identity")
	resp, err := (&http.Client{}).Do(upReq)
	if err != nil {
		writeJSON(w, 502, "上游 API 暂不可用", nil)
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(body)
	log.Printf("[免费API] 生图中转 user=%d api=%d code=%d bytes=%d", userID, id, resp.StatusCode, len(body))
}

// POST /api/free-api/test — 用户点击「测速」时由**服务端**发一次最小请求。
// 密钥不再下发后，客户端自己无法直连上游测速；本接口复用 probeFreeApiOnce
// （与服务端可达性探测同一实现），返回 (ok, latency_ms, error)。
// 注意：本结果只代表「服务器到上游通不通」，用于替换原客户端直连测速的展示；
// 全局「已挂」判定仍只归 probeAllFreeApis 的后台周期探测，本接口不写任何全局状态。
func handleFreeApiTest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if getCurrentUserID(r) == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var key, url, model string
	if err := db.QueryRow("SELECT api_key, base_url, model_name FROM free_apis WHERE id = ? AND status = 'approved'", req.ID).
		Scan(&key, &url, &model); err != nil {
		writeJSON(w, 404, "条目不存在或未上架", nil)
		return
	}
	ok, ms, errMsg := probeFreeApiOnce(key, url, model)
	writeJSON(w, 200, "成功", map[string]interface{}{"ok": ok, "latency": ms, "error": errMsg})
}
