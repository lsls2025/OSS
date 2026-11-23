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

// POST /api/free-api/ping — 用户（本地直连）测速后上报结果。
//
// 【语义修正·关键】测速请求是**从用户自己的手机**发出去的，因此一次失败只能证明
// 「这台设备此刻连不上」，不能证明「这条 API 挂了」——两者成因完全不同：
//
//	· 只有我连不上：我的网络/DNS/代理问题，或上游按 IP、区域对调用方做了限制；
//	· 大家都连不上：这条 API 客观上死了。
//
// 旧实现把前者累加进**全局** fail_count，满 3 次就 marked_down=1 —— 于是任何人点几次测试
// 都能把一条好端端的 API 在全平台标成「已挂」，连提交者自己都被连累。这是真实反馈里的 bug。
//
// 现在：客户端上报**只记录"曾经有人成功过"的延迟参考值**（last_latency），
// 失败一律不写全局状态；「全局已挂」的唯一判据是**服务端可达性探测**（见 probeAllFreeApis）。
func handlePingFreeApi(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	uid := getCurrentUserID(r)
	if uid == 0 {
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
	if req.Ok && req.Latency > 0 {
		// 只在成功时留一个参考延迟（"这条 API 确实有人能连上、速度如何"）。
		// 注意：**不清 marked_down** —— 全局状态归服务端探测管，避免客户端越权改写。
		db.Exec("UPDATE free_apis SET last_latency = ? WHERE id = ?", req.Latency, req.ID)
	}
	// 失败：什么都不写。本地失败只应体现在这台设备的 UI 上。
	writeJSON(w, 200, "成功", map[string]interface{}{"recorded": req.Ok})
}

// ==================== 服务端可达性探测（全局状态的唯一事实源） ====================
//
// 免费 API 走「客户端直连上游」模式（key 明文下发、由各自手机请求），所以
// 「服务器能不能到」与「我能不能到」是两件独立的事，必须分开呈现。
// 服务端探测回答的是：**这条 API 对公网是否还活着**。

const freeApiProbeInterval = 5 * time.Minute

// freeApiProbeTimeout 单次探测超时：上游慢/黑洞时不能让探测循环卡住。
const freeApiProbeTimeout = 15 * time.Second

// shortProbeMsg 把底层错误压成一行可读短句（存库、下发到 UI 都用它）。
func shortProbeMsg(prefix, detail string) string {
	d := strings.TrimSpace(strings.ReplaceAll(detail, "\n", " "))
	if len(d) > 120 {
		d = d[:120]
	}
	if d == "" {
		return prefix
	}
	return prefix + ":" + d
}

// probeFreeApiOnce 从**服务器**发一次最小请求（max_tokens=1）验证上游可达性。
// 返回 (是否连通, 往返毫秒, 失败原因)。任何异常都收敛成可读字符串，绝不 panic。
func probeFreeApiOnce(apiKey, baseURL, model string) (bool, int64, string) {
	base := strings.TrimRight(strings.TrimSpace(baseURL), "/")
	if base == "" {
		return false, 0, "base_url 为空"
	}
	if strings.TrimSpace(model) == "" {
		model = "gpt-3.5-turbo"
	}
	endpoint := base + "/chat/completions"
	body, _ := json.Marshal(map[string]interface{}{
		"model":      model,
		"messages":   []map[string]string{{"role": "user", "content": "hi"}},
		"max_tokens": 1,
		"stream":     false,
	})
	ctx, cancel := context.WithTimeout(context.Background(), freeApiProbeTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(string(body)))
	if err != nil {
		return false, 0, shortProbeMsg("请求构造失败", err.Error())
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+apiKey)
	req.Header.Set("Accept-Encoding", "identity")
	t0 := time.Now()
	resp, err := (&http.Client{}).Do(req)
	if err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			return false, 0, "连接超时(服务器到不了该地址)"
		}
		return false, 0, shortProbeMsg("无法连接", err.Error())
	}
	defer resp.Body.Close()
	ms := time.Since(t0).Milliseconds()
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return true, ms, ""
	}
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	return false, ms, shortProbeMsg("HTTP "+strconv.Itoa(resp.StatusCode), string(raw))
}

// probeAllFreeApis 探测全部「已通过」的免费 API，把结果写入 srv_* 列。
// marked_down 的语义收敛为：**服务端连续失败达到阈值**（不再受客户端上报影响）。
func probeAllFreeApis() {
	rows, err := db.Query("SELECT id, api_key, base_url, model_name, srv_fail_count FROM free_apis WHERE status = 'approved'")
	if err != nil {
		return
	}
	type target struct {
		id              int64
		key, url, model string
		fails           int
	}
	var list []target
	for rows.Next() {
		var t target
		if rows.Scan(&t.id, &t.key, &t.url, &t.model, &t.fails) == nil {
			list = append(list, t)
		}
	}
	rows.Close()
	now := time.Now().Unix()
	for _, t := range list {
		ok, ms, errMsg := probeFreeApiOnce(t.key, t.url, t.model)
		if ok {
			// 可达：清空连续失败与「已挂」标记，记录服务端往返延迟与探测时间
			db.Exec("UPDATE free_apis SET srv_ok = 1, srv_latency = ?, srv_checked_at = ?, srv_error = '', srv_fail_count = 0, marked_down = 0 WHERE id = ?",
				ms, now, t.id)
		} else {
			fails := t.fails + 1
			markedDown := 0
			if fails >= 3 {
				markedDown = 1
			}
			db.Exec("UPDATE free_apis SET srv_ok = 0, srv_checked_at = ?, srv_error = ?, srv_fail_count = ?, marked_down = ? WHERE id = ?",
				now, errMsg, fails, markedDown, t.id)
			log.Printf("[免费API] 服务端探测失败 id=%d fails=%d err=%s", t.id, fails, errMsg)
		}
	}
}

// startFreeApiProbeLoop 启动后台探测：开机探一次，之后每 freeApiProbeInterval 探一轮。
func startFreeApiProbeLoop() {
	go func() {
		// 启动后稍等再探，避免与其它启动任务抢资源
		time.Sleep(20 * time.Second)
		probeAllFreeApis()
		ticker := time.NewTicker(freeApiProbeInterval)
		defer ticker.Stop()
		for range ticker.C {
			probeAllFreeApis()
		}
	}()
}

// POST /api/free-api/probe — 开发者手动触发一次探测。
// 带 id → 只探这一条（即时返回结果，便于排查「到底是谁的问题」）；不带 → 全量探一轮。
func handleAdminProbeFreeApi(w http.ResponseWriter, r *http.Request) {
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
	_ = json.NewDecoder(r.Body).Decode(&req)
	if req.ID == 0 {
		probeAllFreeApis()
		writeJSON(w, 200, "已触发全量探测", nil)
		return
	}
	var key, url, model string
	if err := db.QueryRow("SELECT api_key, base_url, model_name FROM free_apis WHERE id = ?", req.ID).Scan(&key, &url, &model); err != nil {
		writeJSON(w, 404, "条目不存在", nil)
		return
	}
	ok, ms, errMsg := probeFreeApiOnce(key, url, model)
	now := time.Now().Unix()
	srvOk := 0
	if ok {
		srvOk = 1
		db.Exec("UPDATE free_apis SET srv_ok = 1, srv_latency = ?, srv_checked_at = ?, srv_error = '', srv_fail_count = 0, marked_down = 0 WHERE id = ?", ms, now, req.ID)
	} else {
		db.Exec("UPDATE free_apis SET srv_ok = 0, srv_checked_at = ?, srv_error = ? WHERE id = ?", now, errMsg, req.ID)
	}
	writeJSON(w, 200, "探测完成", map[string]interface{}{"srv_ok": srvOk, "srv_latency": ms, "srv_error": errMsg})
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
		// 服务端可达性探测结果（全局状态的唯一事实源）
		"srv_ok":         "INTEGER NOT NULL DEFAULT -1", // 1 可达 / 0 不可达 / -1 尚未探测
		"srv_latency":    "INTEGER NOT NULL DEFAULT 0",  // 服务端实测往返毫秒
		"srv_error":      "TEXT NOT NULL DEFAULT ''",    // 不可达原因（HTTP 码 / 超时 / DNS）
		"srv_checked_at": "INTEGER NOT NULL DEFAULT 0",  // 最近一次探测时间戳
		"srv_fail_count": "INTEGER NOT NULL DEFAULT 0",  // 服务端连续失败次数（满 3 次才 marked_down）
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
