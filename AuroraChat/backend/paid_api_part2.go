package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// GET /api/paid-api/submitter-stats — 按上传者(提交人)/API 汇总真实 token 消耗统计。
// 用途:平台按真实 input/output token(非平台余额 token)线下给上传者结算奖励。
// 只统计已上架(approved)付费 API;每条返回真实 tokens 与平台扣费 tokens,两者口径独立。
func handleSubmitterTokenStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "paid_apis.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	rows, err := db.Query(`SELECT
			pa.submitter_id,
			COALESCE(u.username, ''),
			pa.id,
			pa.model_name,
			pa.base_url,
			COALESCE(pa.input_price, ''),
			COALESCE(pa.output_price, ''),
			COUNT(s.id),
			COALESCE(SUM(s.input_tokens), 0),
			COALESCE(SUM(s.output_tokens), 0),
			COALESCE(SUM(s.cost_tokens), 0)
		FROM paid_apis pa
		LEFT JOIN users u ON pa.submitter_id = u.id
		LEFT JOIN paid_api_usage s ON s.paid_api_id = pa.id
		WHERE pa.status = 'approved'
		GROUP BY pa.id
		ORDER BY (COALESCE(SUM(s.input_tokens), 0) + COALESCE(SUM(s.output_tokens), 0)) DESC`)
	if err != nil {
		writeJSON(w, 500, "统计查询失败", nil)
		return
	}
	defer rows.Close()
	type ApiStat struct {
		SubmitterID   int64  `json:"submitter_id"`
		SubmitterName string `json:"submitter_name"`
		ApiID         int64  `json:"api_id"`
		ModelName     string `json:"model_name"`
		BaseURL       string `json:"base_url"`
		InputPrice    string `json:"input_price"`
		OutputPrice   string `json:"output_price"`
		Calls         int64  `json:"calls"`
		InputTokens   int64  `json:"input_tokens"`
		OutputTokens  int64  `json:"output_tokens"`
		CostTokens    int64  `json:"cost_tokens"`
	}
	apis := make([]ApiStat, 0)
	var totalInput, totalOutput, totalCost int64
	for rows.Next() {
		var st ApiStat
		var uName string
		var apiID int64
		var modelName, baseURL, inP, outP string
		var calls, inT, outT, costT int64
		if err := rows.Scan(&st.SubmitterID, &uName, &apiID, &modelName, &baseURL, &inP, &outP, &calls, &inT, &outT, &costT); err != nil {
			writeJSON(w, 500, "统计读取失败", nil)
			return
		}
		st.SubmitterName = uName
		st.ApiID = apiID
		st.ModelName = modelName
		st.BaseURL = baseURL
		st.InputPrice = inP
		st.OutputPrice = outP
		st.Calls = calls
		st.InputTokens = inT
		st.OutputTokens = outT
		st.CostTokens = costT
		apis = append(apis, st)
		totalInput += inT
		totalOutput += outT
		totalCost += costT
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"apis": apis,
		"total": map[string]interface{}{
			"input_tokens":  totalInput,
			"output_tokens": totalOutput,
			"real_tokens":   totalInput + totalOutput,
			"cost_tokens":   totalCost,
		},
	})
}

// ==================== 付费 API 后端中转(权威计量与扣费) ====================
// POST /api/paid-api/chat/chat/completions (客户端 OpenAI 兼容层会在 base 后追加 /chat/completions;
// 同时注册了 /api/paid-api/chat 与 /api/paid-api/chat/ 两个前缀,两种写法都能命中本处理器)
// 客户端不再直连上游、不再以「自己上报 token」的方式计费(那套口径可以少报/漏报/被篡改/客户端崩溃丢报,
// 平台会按轮数成比例亏损)。改为:请求统一经本接口转发到上传者的上游 API,由后端在流式回传过程中
// 解析上游返回的真实 usage,按 paid_apis 表里的权威倍率从使用者 token_balance 扣费。
// 三道防线保证「公平且绝不亏」:
//  1. 发请求前按请求体估算输入成本,余额不够直接拒发,不让上传者的 key 白烧;
//  2. 流式过程中实时估算成本,达到剩余余额立即掐断上游连接;
//  3. 流结束后按上游真实 usage 结算(上游不返回 usage 时用本地估算兜底),
//     余额不足时扣到 0,而不是像以前那样整笔拒扣(上游已消耗,平台不能一分不收)。

// tokenEstimator 中英文混合 token 估算器(与客户端 AiChatManager.estimateTokens 同口径),
// 仅用于「事前预检 / 流中掐断 / 上游无 usage 时的兜底」,权威值永远以上游返回的 usage 为准。
type tokenEstimator struct {
	cjk   int64
	other int64
}

func (t *tokenEstimator) add(s string) {
	for _, r := range s {
		if (r >= 0x4E00 && r <= 0x9FFF) || (r >= 0x3000 && r <= 0x303F) || (r >= 0xFF00 && r <= 0xFFEF) {
			t.cjk++
		} else {
			t.other++
		}
	}
}

func (t *tokenEstimator) tokens() int64 {
	if t.cjk <= 0 && t.other <= 0 {
		return 0
	}
	v := t.cjk + t.other/4 + 1
	if v < 1 {
		v = 1
	}
	return v
}

// jsonNum 安全读取 map 里的数值(JSON 反序列化后数字统一是 float64)。
func jsonNum(v interface{}) float64 {
	if f, ok := v.(float64); ok {
		return f
	}
	return 0
}

// settlePaidApiUsage 结算一次付费 API 调用:「扣到 0 也不整笔拒扣」。
// 上游已经真实消耗了资源,平台必须尽可能回收;返回实际扣掉的余额 token 数。
// 说明:余额在事务外先读(只用于「本次最多能收多少」的钳制),事务内第一条语句即写,
// 且用 max(token_balance-?,0) 做原子钳制——多 Agent 并行子任务同时结算也不会把余额扣成负数。
func settlePaidApiUsage(userID, apiID int64, model, baseURL, inputPrice, outputPrice string, inTok, outTok, cost int64) int64 {
	if cost <= 0 {
		return 0
	}
	var balance int64
	if err := db.QueryRow("SELECT token_balance FROM users WHERE id = ?", userID).Scan(&balance); err != nil {
		log.Printf("[付费API] 中转结算读取余额失败 user=%d err=%v", userID, err)
		return 0
	}
	charged := cost
	if charged > balance {
		charged = balance
	}
	if charged <= 0 {
		return 0
	}
	// 扣费写库失败就等于平台白干,故重试几次(busy_timeout 已给 5s,这里只兜极端抖动)
	for attempt := 0; attempt < 3; attempt++ {
		if settleOnce(userID, apiID, model, baseURL, inputPrice, outputPrice, inTok, outTok, charged) {
			return charged
		}
		time.Sleep(120 * time.Millisecond)
	}
	log.Printf("[付费API] 中转结算多次重试仍失败,该笔未能回收 user=%d api=%d cost=%d", userID, apiID, charged)
	return 0
}

func settleOnce(userID, apiID int64, model, baseURL, inputPrice, outputPrice string, inTok, outTok, charged int64) bool {
	tx, err := db.Begin()
	if err != nil {
		log.Printf("[付费API] 中转结算开启事务失败 user=%d err=%v", userID, err)
		return false
	}
	defer tx.Rollback()
	if _, err := tx.Exec("UPDATE users SET token_balance = max(token_balance - ?, 0) WHERE id = ?", charged, userID); err != nil {
		log.Printf("[付费API] 中转结算扣费失败 user=%d err=%v", userID, err)
		return false
	}
	requestID := fmt.Sprintf("proxy-%d-%d", userID, time.Now().UnixNano())
	if _, err := tx.Exec(`INSERT INTO paid_api_usage
		(user_id, paid_api_id, model_name, base_url, input_tokens, output_tokens, cost_tokens, input_price, output_price, request_id, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		userID, apiID, model, baseURL, inTok, outTok, charged, inputPrice, outputPrice, requestID, time.Now().Unix()); err != nil {
		log.Printf("[付费API] 中转结算写账失败 user=%d err=%v", userID, err)
		return false
	}
	if err := tx.Commit(); err != nil {
		log.Printf("[付费API] 中转结算提交失败 user=%d err=%v", userID, err)
		return false
	}
	return true
}

func handlePaidApiChat(w http.ResponseWriter, r *http.Request) {
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

	// 定位付费 API:优先 api_id;客户端本地 id 失效(重装/换库)时按 base_url+model 兜底匹配
	apiID := int64(jsonNum(in["api_id"]))
	baseURLHint, _ := in["base_url"].(string)
	modelHint, _ := in["model_name"].(string)
	baseURLHint = strings.TrimRight(strings.TrimSpace(baseURLHint), "/")

	var upstreamBase, upstreamKey, upstreamModel, inputPrice, outputPrice, status string
	if apiID > 0 {
		e := db.QueryRow("SELECT base_url, api_key, model_name, input_price, output_price, status FROM paid_apis WHERE id = ?", apiID).
			Scan(&upstreamBase, &upstreamKey, &upstreamModel, &inputPrice, &outputPrice, &status)
		if e != nil || status != "approved" {
			apiID = 0
		}
	}
	if apiID <= 0 {
		modelHint = strings.TrimSpace(modelHint)
		if baseURLHint == "" || modelHint == "" {
			writeJSON(w, 404, "付费 API 不存在或未上架", nil)
			return
		}
		if e := db.QueryRow(`SELECT id, base_url, api_key, model_name, input_price, output_price, status
			FROM paid_apis WHERE LOWER(base_url)=LOWER(?) AND LOWER(model_name)=LOWER(?) AND status='approved'`,
			baseURLHint, modelHint).
			Scan(&apiID, &upstreamBase, &upstreamKey, &upstreamModel, &inputPrice, &outputPrice, &status); e != nil {
			log.Printf("[付费API] 中转未匹配到付费 API user=%d base_url=%s model=%s", userID, baseURLHint, modelHint)
			writeJSON(w, 404, "付费 API 不存在或未上架", nil)
			return
		}
	}
	if strings.TrimSpace(upstreamKey) == "" {
		writeJSON(w, 502, "该付费 API 未配置密钥", nil)
		return
	}

	var balance int64
	if err := db.QueryRow("SELECT token_balance FROM users WHERE id = ?", userID).Scan(&balance); err != nil {
		writeJSON(w, 500, "用户信息获取失败", nil)
		return
	}
	if balance <= 0 {
		writeJSON(w, 402, "余额不足", nil)
		return
	}

	// 组装上游请求:模型由服务端强制覆盖;剥掉平台自有字段;强制开流式 usage(拿不到 usage 就只能估算)
	delete(in, "api_id")
	delete(in, "base_url")
	delete(in, "model_name")
	in["model"] = upstreamModel
	in["stream"] = true
	in["stream_options"] = map[string]interface{}{"include_usage": true}
	outBytes, err := json.Marshal(in)
	if err != nil {
		writeJSON(w, 500, "转发数据构造失败", nil)
		return
	}

	inputRatio := parsePriceRatio(inputPrice)
	outputRatio := parsePriceRatio(outputPrice)
	var estIn tokenEstimator
	estIn.add(string(outBytes))
	// 防线①:发请求前按请求体估算「输入成本」,余额不够直接拒发,不让上传者的 key 白烧
	if chargeByRatio(inputRatio, estIn.tokens()) > balance {
		writeJSON(w, 402, "余额不足", nil)
		return
	}

	endpoint := strings.TrimRight(upstreamBase, "/") + "/chat/completions"
	// 不设整体 Timeout：长推理/大工具规划不应被 300s 截断；取消由 r.Context() 驱动。
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
			// 客户端在拿到响应前就断开:上游没有任何消耗,不扣费
			return
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
		// 上游报错统一折算成 502:尤其上游 402 不能被客户端误判成「平台余额不足」
		WriteLog("paid_billing", fmt.Sprintf("[付费API] 中转上游报错 user=%d api=%d code=%d body=%s", userID, apiID, resp.StatusCode, msg))
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

	var (
		promptTokens     int64
		completionTokens int64
		gotUsage         bool
		outEst           tokenEstimator
		cutOff           bool
	)
	reader := bufio.NewReaderSize(resp.Body, 32*1024)
	for {
		line, rerr := reader.ReadBytes('\n')
		if len(line) > 0 {
			trimmed := strings.TrimRight(string(line), "\r\n")
			// 扣下 [DONE] 不立即转发:等真实 usage 结算完、扣费写库后再补发,保证「计费先于流结束」
			if strings.TrimSpace(trimmed) == "data: [DONE]" || strings.TrimSpace(trimmed) == "data:[DONE]" {
				break
			}
			if strings.HasPrefix(trimmed, "data:") {
				payload := strings.TrimSpace(strings.TrimPrefix(trimmed, "data:"))
				if payload != "" && payload != "[DONE]" {
					var obj map[string]interface{}
					if json.Unmarshal([]byte(payload), &obj) == nil {
						if u, ok := obj["usage"].(map[string]interface{}); ok && u != nil {
							promptTokens = int64(jsonNum(u["prompt_tokens"]))
							completionTokens = int64(jsonNum(u["completion_tokens"]))
							gotUsage = true
						}
						if chs, ok := obj["choices"].([]interface{}); ok && len(chs) > 0 {
							if ch, ok := chs[0].(map[string]interface{}); ok {
								if d, ok := ch["delta"].(map[string]interface{}); ok {
									if c, ok := d["content"].(string); ok && c != "" && c != "null" {
										outEst.add(c)
									}
									if c, ok := d["reasoning_content"].(string); ok && c != "" && c != "null" {
										outEst.add(c)
									}
								}
							}
						}
					}
				}
			}
			// 防线②:实时掐断——估算成本已达剩余余额,立即断开上游,避免继续白烧
			if !cutOff {
				cur := chargeByRatio(inputRatio, estIn.tokens()) + chargeByRatio(outputRatio, outEst.tokens())
				if cur >= balance {
					cutOff = true
					_ = resp.Body.Close()
					WriteLog("paid_billing", fmt.Sprintf("[付费API] 中转余额耗尽掐断 user=%d api=%d est=%d balance=%d", userID, apiID, cur, balance))
					break
				}
			}
			if _, werr := w.Write(line); werr != nil {
				break
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if rerr != nil {
			break
		}
	}

	// 防线③:结算。上游真实 usage 优先;缺失时本地估算兜底,绝不漏计。
	finalIn, finalOut := promptTokens, completionTokens
	if !gotUsage || (finalIn <= 0 && finalOut <= 0) {
		finalIn, finalOut = estIn.tokens(), outEst.tokens()
	}
	cost := chargeByRatio(inputRatio, finalIn) + chargeByRatio(outputRatio, finalOut)
	charged := settlePaidApiUsage(userID, apiID, upstreamModel, upstreamBase, inputPrice, outputPrice, finalIn, finalOut, cost)

	// 把权威扣费结果作为一件 SSE 事件推给客户端(在 [DONE] 之前),客户端据此展示「已扣费」
	if bb, e := json.Marshal(map[string]interface{}{
		"aurora_billing": map[string]interface{}{
			"cost_tokens":   charged,
			"input_tokens":  finalIn,
			"output_tokens": finalOut,
			"balance":       balance - charged,
			"cut_off":       cutOff,
			"real_usage":    gotUsage,
		},
	}); e == nil {
		fmt.Fprintf(w, "data: %s\n\n", bb)
	}
	fmt.Fprint(w, "data: [DONE]\n\n")
	if flusher != nil {
		flusher.Flush()
	}
	WriteLog("paid_billing", fmt.Sprintf("[付费API] 中转结算 user=%d api=%d model=%s in=%d out=%d cost=%d charged=%d realUsage=%v cutOff=%v",
		userID, apiID, upstreamModel, finalIn, finalOut, cost, charged, gotUsage, cutOff))
}

// GET /api/paid-api/last-usage — 查询当前用户「最近一次」已结算的付费 API 用量明细（服务端权威值）。
//
// 为什么需要它：用户点「停止」时客户端会主动断开连接，SSE 里的 aurora_billing 结算事件
// 已经随断开发不出去；但上面的中转结算逻辑**照样执行并落库**（paid_api_usage + token_balance）。
// 客户端凭这个接口把中断记录里的真实 input/output/cost 取回来，而不是拿着一份估算值展示
// ——用户对"假数据"零容忍，估算值只能当兜底，不能冒充权威计量。
//
// 只取最近一条：普通对话单用户串行，中断后查的"最近一条"必然是本轮；
// 万一同一账号在多端并发，也只会错登到相邻的一条（值仍然真实，非估算）。
func handlePaidApiLastUsage(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var (
		apiID      int64
		modelName  string
		baseURL    string
		inTok      int64
		outTok     int64
		costTokens int64
		requestID  string
		createdAt  int64
	)
	// 优先取最近一条「中转结算」行（request_id 形如 proxy-<uid>-<ns>）；
	// 老库里可能只有早期 consume 上报行，取不到再退回任意最近一条。
	scan := func(q string, args ...interface{}) error {
		return db.QueryRow(q, args...).Scan(&apiID, &modelName, &baseURL, &inTok, &outTok, &costTokens, &requestID, &createdAt)
	}
	err := scan(`SELECT paid_api_id, model_name, base_url, input_tokens, output_tokens, cost_tokens,
		COALESCE(request_id,''), created_at FROM paid_api_usage
		WHERE user_id = ? AND request_id LIKE 'proxy-%' ORDER BY created_at DESC, id DESC LIMIT 1`, userID)
	if err != nil {
		err = scan(`SELECT paid_api_id, model_name, base_url, input_tokens, output_tokens, cost_tokens,
			COALESCE(request_id,''), created_at FROM paid_api_usage
			WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 1`, userID)
	}
	if err != nil {
		writeJSON(w, 200, "暂无用量记录", map[string]interface{}{"found": false})
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"found":         true,
		"api_id":        apiID,
		"model":         modelName,
		"base_url":      baseURL,
		"input_tokens":  inTok,
		"output_tokens": outTok,
		"cost_tokens":   costTokens,
		"request_id":    requestID,
		"created_at":    createdAt,
	})
}

// ==================== 付费 API 生图中转（密钥不再下发） ====================
// POST /api/paid-api/image — 生图后端中转。
// 背景：对话早已走 /api/paid-api/chat 中转（Key 只在服务端），但生图此前仍由
// 客户端持 Key 直连上游——available 接口明文下发 api_key 供养的正是这条链路。
// 现在生图也统一走本接口：后端持上传者 Key 转发到上游 /images/generations，
// 响应原样回传（非流式 JSON）。计费口径与直连时期一致：生图不按 token 扣费，
// 成本归上传者渠道（paid_api_usage 的 token 计量只覆盖对话）。
func handlePaidApiImage(w http.ResponseWriter, r *http.Request) {
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
	// 定位付费 API：优先 api_id；客户端本地 id 失效（重装/换库）时按 base_url+model 兜底
	apiID := int64(jsonNum(in["api_id"]))
	baseURLHint, _ := in["base_url"].(string)
	modelHint, _ := in["model_name"].(string)
	baseURLHint = strings.TrimRight(strings.TrimSpace(baseURLHint), "/")

	var upstreamBase, upstreamKey, status string
	if apiID > 0 {
		e := db.QueryRow("SELECT base_url, api_key, status FROM paid_apis WHERE id = ?", apiID).
			Scan(&upstreamBase, &upstreamKey, &status)
		if e != nil || status != "approved" {
			apiID = 0
		}
	}
	if apiID <= 0 {
		modelHint = strings.TrimSpace(modelHint)
		if baseURLHint == "" || modelHint == "" {
			writeJSON(w, 404, "付费 API 不存在或未上架", nil)
			return
		}
		if e := db.QueryRow(`SELECT id, base_url, api_key, status FROM paid_apis
			WHERE LOWER(base_url)=LOWER(?) AND LOWER(model_name)=LOWER(?) AND status='approved'`,
			baseURLHint, modelHint).Scan(&apiID, &upstreamBase, &upstreamKey, &status); e != nil {
			log.Printf("[付费API] 生图中转未匹配到付费 API user=%d base_url=%s model=%s", userID, baseURLHint, modelHint)
			writeJSON(w, 404, "付费 API 不存在或未上架", nil)
			return
		}
	}
	if strings.TrimSpace(upstreamKey) == "" {
		writeJSON(w, 502, "该付费 API 未配置密钥", nil)
		return
	}
	// 组装上游请求：剥掉平台自有字段，其余（model/prompt/size/response_format）原样透传
	delete(in, "api_id")
	delete(in, "base_url")
	delete(in, "model_name")
	outBytes, err := json.Marshal(in)
	if err != nil {
		writeJSON(w, 500, "转发数据构造失败", nil)
		return
	}
	endpoint := strings.TrimRight(upstreamBase, "/") + "/images/generations"
	// 生图是慢接口（可达 60s+），给足超时；客户端断开由 r.Context() 驱动取消
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
		WriteLog("paid_billing", fmt.Sprintf("[付费API] 生图中转上游请求失败 user=%d api=%d err=%v", userID, apiID, err))
		writeJSON(w, 502, "上游 API 暂不可用", nil)
		return
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(body)
	WriteLog("paid_billing", fmt.Sprintf("[付费API] 生图中转 user=%d api=%d code=%d bytes=%d", userID, apiID, resp.StatusCode, len(body)))
}
