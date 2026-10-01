package main

import (
	"database/sql"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

var logsDB *sql.DB
var logsDBOnce sync.Once

func getLogsDB(dataDir string) *sql.DB {
	logsDBOnce.Do(func() {
		path := filepath.Join(dataDir, "server_logs.db")
		var err error
		logsDB, err = sql.Open("sqlite", path+"?_journal_mode=WAL&_busy_timeout=5000")
		if err != nil {
			log.Fatalf("无法打开日志数据库: %v", err)
		}
		logsDB.SetMaxOpenConns(3)
		logsDB.SetMaxIdleConns(1)
		logsDB.SetConnMaxLifetime(5 * time.Minute)
		_, err = logsDB.Exec(`
			CREATE TABLE IF NOT EXISTS server_logs (
				id INTEGER PRIMARY KEY AUTOINCREMENT,
				type TEXT NOT NULL DEFAULT 'info',
				message TEXT NOT NULL,
				created_at TEXT DEFAULT (datetime('now','localtime'))
			)
		`)
		if err != nil {
			log.Fatalf("创建日志表失败: %v", err)
		}
	})
	return logsDB
}

// handleLogAdd 添加日志
func handleLogAdd(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	var req struct {
		Type    string `json:"type"`
		Message string `json:"message"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.Message = strings.TrimSpace(req.Message)
	if req.Message == "" {
		writeJSON(w, 400, "消息不能为空", nil)
		return
	}
	if req.Type == "" {
		req.Type = "info"
	}
	// dataDir 从全局获取
	execPath, _ := os.Executable()
	db := getLogsDB(filepath.Join(filepath.Dir(execPath), "data"))
	_, err := db.Exec("INSERT INTO server_logs(type, message) VALUES(?, ?)", req.Type, req.Message)
	if err != nil {
		writeJSON(w, 500, "写入失败", nil)
		return
	}
	writeJSON(w, 200, "日志已记录", nil)
}

// handleLogGet 获取日志（分页）
func handleLogGet(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	execPath, _ := os.Executable()
	db := getLogsDB(filepath.Join(filepath.Dir(execPath), "data"))

	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 1000 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}

	var total int
	db.QueryRow("SELECT COUNT(*) FROM server_logs").Scan(&total)

	rows, err := db.Query("SELECT id, type, message, created_at FROM server_logs ORDER BY id DESC LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()

	var items []map[string]interface{}
	for rows.Next() {
		var id int
		var typ, msg, ca string
		if err := rows.Scan(&id, &typ, &msg, &ca); err != nil {
			continue
		}
		items = append(items, map[string]interface{}{
			"id": id, "type": typ, "message": msg, "created_at": ca,
		})
	}
	if items == nil {
		items = []map[string]interface{}{}
	}
	writeJSON(w, 200, "", map[string]interface{}{
		"items": items,
		"total": total,
		"more":  offset+limit < total,
	})
}

// WriteLog 便捷函数，供其他模块调用记录日志
func WriteLog(logType, message string) {
	execPath, _ := os.Executable()
	dataDir := filepath.Join(filepath.Dir(execPath), "data")
	db := getLogsDB(dataDir)
	db.Exec("INSERT INTO server_logs(type, message) VALUES(?, ?)", logType, message)
}


