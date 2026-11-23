package main

import (
	"encoding/json"
	"log"
	"net/http"
	"time"
)

// ensureAgentAppVersionTable 建 Agent 应用版本表(在 database.go 初始化时调用)
// 管理 auroraagent 安装包的版本发布:开发者在「开发者管理 → 应用」下方新增的 Agent 条目里
// 推送/切换版本、设置强制更新;auroraagent 客户端启动时读取当前生效版本决定是否提示更新。
func ensureAgentAppVersionTable() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS agent_app_versions (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		version_code INTEGER NOT NULL DEFAULT 0,
		version_name TEXT NOT NULL DEFAULT '',
		apk_url TEXT NOT NULL DEFAULT '',
		force_update INTEGER NOT NULL DEFAULT 0,
		update_message TEXT NOT NULL DEFAULT '',
		is_active INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  创建 agent_app_versions 表失败: %v", err)
	} else {
		log.Println("  [Agent 应用] agent_app_versions 表已确认存在")
	}
	// 清理早期误注入的占位记录(3.1.0 / code 31,来自版本隔离改造前的 seed)。
	// Agent 版本由管理员在「开发者管理 → Agent更新」中主动推送,与 Aurora Chat 主程序版本(version.txt)完全隔离,
	// 不复用也不读取同一份版本文件;表已通过上面的 CREATE TABLE IF NOT EXISTS 自动创建,
	// 首次为空即显示「暂无生效版本」。
	if _, err := db.Exec(
		"DELETE FROM agent_app_versions WHERE version_code = 31 AND version_name = '3.1.0' AND apk_url = 'https://www.example.com/backend/data/tools/AuroraAgent.apk'",
	); err != nil {
		log.Printf("  清理占位版本记录失败: %v", err)
	}
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// GET /api/agent-app/version — 公开:返回当前生效版本(供 auroraagent 客户端检查更新)
func handleAgentAppVersion(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	var id int64
	var code int
	var name, url, msg string
	var force, active int
	err := db.QueryRow("SELECT id, version_code, version_name, apk_url, force_update, update_message, is_active FROM agent_app_versions WHERE is_active = 1 ORDER BY id DESC LIMIT 1").Scan(
		&id, &code, &name, &url, &force, &msg, &active)
	if err != nil {
		// 没有生效版本时回退到最新一条
		err = db.QueryRow("SELECT id, version_code, version_name, apk_url, force_update, update_message, is_active FROM agent_app_versions ORDER BY id DESC LIMIT 1").Scan(
			&id, &code, &name, &url, &force, &msg, &active)
	}
	if err != nil {
		json.NewEncoder(w).Encode(map[string]interface{}{
			"code": 200,
			"data": map[string]interface{}{
				"latest_version_code": 0,
				"latest_version_name": "",
				"download_url":        "",
				"force_update":        false,
				"update_message":      "",
			},
		})
		return
	}
	json.NewEncoder(w).Encode(map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"latest_version_code": code,
			"latest_version_name": name,
			"download_url":        url,
			"force_update":        force == 1,
			"update_message":      msg,
		},
	})
}

// GET /api/admin/agent-app/versions — 列出所有版本(含当前生效标记)
func handleAdminAgentAppVersions(w http.ResponseWriter, r *http.Request) {
	if !checkDevOrPerm(r, "open_apps.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	rows, err := db.Query("SELECT id, version_code, version_name, apk_url, force_update, update_message, is_active, created_at FROM agent_app_versions ORDER BY id DESC")
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	list := []map[string]interface{}{}
	for rows.Next() {
		var id int64
		var code int
		var name, url, msg string
		var force, active int
		var created int64
		if err := rows.Scan(&id, &code, &name, &url, &force, &msg, &active, &created); err == nil {
			list = append(list, map[string]interface{}{
				"id":            id,
				"versionCode":   code,
				"versionName":   name,
				"apkUrl":        url,
				"forceUpdate":   force == 1,
				"updateMessage": msg,
				"isActive":      active == 1,
				"createdAt":     created,
			})
		}
	}
	writeJSON(w, 200, "ok", list)
}

// POST /api/admin/agent-app/version — 推送新版本(仅新增记录,不自动设为生效)
func handleAdminAgentAppPushVersion(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "open_apps.manage") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		VersionCode   int    `json:"versionCode"`
		VersionName   string `json:"versionName"`
		ApkURL        string `json:"apkUrl"`
		ForceUpdate   bool   `json:"forceUpdate"`
		UpdateMessage string `json:"updateMessage"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数解析失败", nil)
		return
	}
	if req.VersionCode <= 0 || req.VersionName == "" {
		writeJSON(w, 400, "versionCode 与 versionName 必填", nil)
		return
	}
	url := req.ApkURL
	if url == "" {
		url = "https://www.example.com/backend/data/tools/AuroraAgent.apk"
	}
	msg := req.UpdateMessage
	if msg == "" {
		msg = "发现新版本，请更新后继续使用。"
	}
	now := time.Now().Unix()
	res, err := db.Exec("INSERT INTO agent_app_versions (version_code, version_name, apk_url, force_update, update_message, is_active, created_at) VALUES (?, ?, ?, ?, ?, 0, ?)",
		req.VersionCode, req.VersionName, url, boolToInt(req.ForceUpdate), msg, now)
	if err != nil {
		writeJSON(w, 500, "插入失败", nil)
		return
	}
	id, _ := res.LastInsertId()
	writeJSON(w, 200, "ok", map[string]interface{}{"id": id})
}

// POST /api/admin/agent-app/version/activate — 切换当前生效版本(其它版本置为非生效)
func handleAdminAgentAppActivate(w http.ResponseWriter, r *http.Request) {
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
	var n int
	db.QueryRow("SELECT COUNT(*) FROM agent_app_versions WHERE id = ?", req.ID).Scan(&n)
	if n == 0 {
		writeJSON(w, 404, "版本不存在", nil)
		return
	}
	db.Exec("UPDATE agent_app_versions SET is_active = 0")
	db.Exec("UPDATE agent_app_versions SET is_active = 1 WHERE id = ?", req.ID)
	writeJSON(w, 200, "ok", nil)
}

// POST /api/admin/agent-app/version/delete — 删除版本(若删的是生效版本,自动把最新一条设为生效)
func handleAdminAgentAppDelete(w http.ResponseWriter, r *http.Request) {
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
	var active int
	db.QueryRow("SELECT is_active FROM agent_app_versions WHERE id = ?", req.ID).Scan(&active)
	db.Exec("DELETE FROM agent_app_versions WHERE id = ?", req.ID)
	if active == 1 {
		db.Exec("UPDATE agent_app_versions SET is_active = 1 WHERE id = (SELECT id FROM agent_app_versions ORDER BY id DESC LIMIT 1)")
	}
	writeJSON(w, 200, "ok", nil)
}
