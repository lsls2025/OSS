package main

import (
	"encoding/json"
	"log"
	"net/http"
)

// ========== 全局防御开关（"抵防"） ==========
//
// 这些开关由开发者在"开发者管理 → 抵防"页开启，用于对全站做极端管控：
//   - disable_register     禁止注册：开启后切断所有注册请求
//   - official_group_mute  官群禁言：开启后除开发者外，官方群(disap_id=1, convID=-1001)消息被切断
//   - community_ban        社区禁发：开启后用户无法发布帖子/上传资源
//   - full_mute            全面禁言：开启后除开发者外，所有用户的消息(群聊/私聊等)一律禁止发送
//
// 开关状态持久化在 sys_flags 表，重启后依然保持（维持关闭/开启状态直到开发者再次切换）。

func initSysFlags() {
	db.Exec(`CREATE TABLE IF NOT EXISTS sys_flags (
		flag_key TEXT PRIMARY KEY,
		value    INTEGER NOT NULL DEFAULT 0
	)`)
	log.Println("[抵防] 防御开关表初始化完成")
}

// getFlag 读取某个防御开关是否开启
func getFlag(key string) bool {
	var v int
	err := db.QueryRow("SELECT value FROM sys_flags WHERE flag_key = ?", key).Scan(&v)
	if err != nil {
		return false
	}
	return v != 0
}

// setFlag 设置某个防御开关（持久化）
func setFlag(key string, val bool) {
	v := 0
	if val {
		v = 1
	}
	_, err := db.Exec("INSERT OR REPLACE INTO sys_flags (flag_key, value) VALUES (?, ?)", key, v)
	if err != nil {
		log.Printf("[抵防] 写入开关 %s 失败: %v", key, err)
	}
}

// 合法的防御开关 key
func isValidDefenseKey(key string) bool {
	switch key {
	case "disable_register", "official_group_mute", "community_ban", "full_mute", "disable_official_api":
		return true
	}
	return false
}

// handleDevDefense GET 获取状态 / POST 设置开关（开发者专属）
func handleDevDefense(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		handleGetDefense(w, r)
	case http.MethodPost:
		handleSetDefense(w, r)
	default:
		writeJSON(w, 405, "方法不支持", nil)
	}
}

// GET /api/dev/defense — 返回当前四个开关状态
func handleGetDefense(w http.ResponseWriter, r *http.Request) {
	userID := getCurrentUserID(r)
	if userID == 0 || !isDeveloperUser(userID) {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"disable_register":    getFlag("disable_register"),
		"official_group_mute": getFlag("official_group_mute"),
		"community_ban":       getFlag("community_ban"),
		"full_mute":           getFlag("full_mute"),
		"disable_official_api": getFlag("disable_official_api"),
	})
}

// POST /api/dev/defense — 切换某个开关
// body: {"key": "disable_register", "value": true}
func handleSetDefense(w http.ResponseWriter, r *http.Request) {
	userID := getCurrentUserID(r)
	if userID == 0 || !isDeveloperUser(userID) {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	var req struct {
		Key   string `json:"key"`
		Value bool   `json:"value"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if !isValidDefenseKey(req.Key) {
		writeJSON(w, 400, "无效的开关", nil)
		return
	}
	setFlag(req.Key, req.Value)
	log.Printf("[抵防] 开发者 %d 设置 %s = %v", userID, req.Key, req.Value)
	writeJSON(w, 200, "ok", map[string]interface{}{
		req.Key: req.Value,
	})
}
