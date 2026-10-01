package main

import (
	"crypto/sha1"
	"database/sql"
	"encoding/json"
	"fmt"
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

var (
	userDBDir   string
	userDBMu    sync.Mutex
	userDBCache = map[int64]*sql.DB{}
)

func getUserDBPath(userID int64) string {
	// 用 userID + 服务端密钥 做哈希，防止用户推算别人的文件名
	hash := sha1.Sum([]byte(fmt.Sprintf("%d:%s", userID, getJWTSecret())))
	name := fmt.Sprintf("%x.db", hash)[:16]
	return filepath.Join(userDBDir, name)
}

func getUserDB(userID int64) (*sql.DB, error) {
	userDBMu.Lock()
	defer userDBMu.Unlock()

	if db, ok := userDBCache[userID]; ok {
		return db, nil
	}

	dbPath := getUserDBPath(userID)
	db, err := sql.Open("sqlite", dbPath+"?_journal_mode=WAL&_busy_timeout=5000")
	if err != nil {
		return nil, fmt.Errorf("打开用户数据库失败: %w", err)
	}
	db.SetMaxOpenConns(2)
	db.SetMaxIdleConns(1)
	db.SetConnMaxLifetime(5 * time.Minute)

	// 创建表（如果文件是新创建的）
	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS user_store (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			key TEXT NOT NULL UNIQUE,
			value TEXT,
			created_at TEXT DEFAULT (datetime('now','localtime')),
			updated_at TEXT DEFAULT (datetime('now','localtime'))
		)
	`)
	if err != nil {
		db.Close()
		return nil, fmt.Errorf("创建用户数据表失败: %w", err)
	}

	userDBCache[userID] = db
	log.Printf("用户数据库已创建: userID=%d path=%s", userID, dbPath)
	return db, nil
}

// initUserDB 初始化用户数据库目录
func initUserDB(dataDir string) {
	userDBDir = filepath.Join(dataDir, "user_db")
	if err := os.MkdirAll(userDBDir, 0755); err != nil {
		log.Fatalf("创建用户数据库目录失败: %v", err)
	}
	log.Printf("用户数据库目录: %s", userDBDir)
}

// handleGetUserDbPath 返回当前用户专属数据库路径
func handleGetUserDbPath(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userIDStr := r.Header.Get("X-User-ID")
	userID, _ := strconv.ParseInt(userIDStr, 10, 64)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	writeJSON(w, 200, "", map[string]string{
		"path": getUserDBPath(userID),
	})
}

// handleUserDataSave 保存当前用户数据
func handleUserDataSave(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	userIDStr := r.Header.Get("X-User-ID")
	userID, _ := strconv.ParseInt(userIDStr, 10, 64)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		Key   string `json:"key"`
		Value string `json:"value"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.Key = strings.TrimSpace(req.Key)
	if req.Key == "" {
		writeJSON(w, 400, "key 不能为空", nil)
		return
	}

	db, err := getUserDB(userID)
	if err != nil {
		writeJSON(w, 500, "数据库初始化失败", nil)
		return
	}

	// 配额检查：新写入约需 value 字节
	if ok, msg := checkQuota(userID, int64(len(req.Value))); !ok {
		writeJSON(w, 403, msg, nil)
		return
	}

	_, err = db.Exec(`
		INSERT INTO user_store(key, value, created_at, updated_at)
		VALUES(?, ?, datetime('now','localtime'), datetime('now','localtime'))
		ON CONFLICT(key) DO UPDATE SET
			value = excluded.value,
			updated_at = datetime('now','localtime')
	`, req.Key, req.Value)
	if err != nil {
		writeJSON(w, 500, "保存失败", nil)
		return
	}

	writeJSON(w, 200, "保存成功", nil)
}

// handleUserDataGet 获取当前用户数据
func handleUserDataGet(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	userIDStr := r.Header.Get("X-User-ID")
	userID, _ := strconv.ParseInt(userIDStr, 10, 64)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	db, err := getUserDB(userID)
	if err != nil {
		writeJSON(w, 500, "数据库初始化失败", nil)
		return
	}

	key := strings.TrimSpace(r.URL.Query().Get("key"))
	if key != "" {
		var value string
		err := db.QueryRow("SELECT value FROM user_store WHERE key = ?", key).Scan(&value)
		if err == sql.ErrNoRows {
			writeJSON(w, 200, "", map[string]string{"key": key, "value": ""})
			return
		}
		if err != nil {
			writeJSON(w, 500, "查询失败", nil)
			return
		}
		writeJSON(w, 200, "", map[string]string{"key": key, "value": value})
	} else {
		// 分页参数，默认 offset=0, limit=100
		offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if limit <= 0 || limit > 1000 {
			limit = 100
		}
		if offset < 0 {
			offset = 0
		}

		// 查询总数
		var total int
		db.QueryRow("SELECT COUNT(*) FROM user_store").Scan(&total)

		rows, err := db.Query("SELECT key, value, created_at, updated_at FROM user_store ORDER BY id DESC LIMIT ? OFFSET ?", limit, offset)
		if err != nil {
			writeJSON(w, 500, "查询失败", nil)
			return
		}
		defer rows.Close()

		var items []map[string]string
		for rows.Next() {
			var k, v, ca, ua string
			if err := rows.Scan(&k, &v, &ca, &ua); err != nil {
				continue
			}
			items = append(items, map[string]string{
				"key": k, "value": v, "created_at": ca, "updated_at": ua,
			})
		}
		if items == nil {
			items = []map[string]string{}
		}
		writeJSON(w, 200, "", map[string]interface{}{
			"items": items,
			"total": total,
			"more":  offset+limit < total,
		})
	}
}
