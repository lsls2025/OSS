package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ============================================================
// 漂流瓶模块
// ============================================================

var (
	driftMu          sync.Mutex
	throwBottleTimes = make(map[int64]time.Time)
	pickBottleTimes  = make(map[int64]time.Time)
)

type DriftBottle struct {
	ID        int64  `json:"id"`
	UserID    int64  `json:"user_id"`
	UserName  string `json:"user_name"`
	Content   string `json:"content"`
	Type      string `json:"type"`
	ImageData string `json:"image_data,omitempty"`
	IsPicked  bool   `json:"is_picked"`
	PickedBy  int64  `json:"picked_by,omitempty"`
	PickedAt  int64  `json:"picked_at,omitempty"`
	CreatedAt int64  `json:"created_at"`
}

func initDriftBottleTable() {
	// 直接创建全新的表 bottles，与旧表 drift_bottles 完全独立
	if _, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS bottles (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL DEFAULT 0,
			content TEXT NOT NULL,
			type TEXT NOT NULL DEFAULT 'text',
			image_data TEXT DEFAULT '',
			is_picked INTEGER NOT NULL DEFAULT 0,
			picked_by INTEGER DEFAULT 0,
			picked_at INTEGER DEFAULT 0,
			created_at INTEGER NOT NULL
		);
		CREATE INDEX IF NOT EXISTS idx_bottles_created ON bottles(created_at);
	`); err != nil {
		log.Printf("  提示: bottles 表准备: %v", err)
	}
	// 每日次数统计表
	if _, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS bottle_actions (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL,
			action TEXT NOT NULL,
			created_at INTEGER NOT NULL
		);
		CREATE INDEX IF NOT EXISTS idx_bottle_actions ON bottle_actions(user_id, action, created_at);
	`); err != nil {
		log.Printf("  提示: bottle_actions 表准备: %v", err)
	}
	log.Println("  漂流瓶模块 v2 已初始化（新表 bottles + bottle_actions）")
}

// 获取当天开始的时间戳
func todayStartUnix() int64 {
	now := time.Now()
	return now.Unix() - int64(now.Hour())*3600 - int64(now.Minute())*60 - int64(now.Second())
}

// 检查用户是否为开发者（QQ 号或邮箱任一匹配即视为开发者，与前端 LocalStorage.isDeveloper 保持一致）
func isDeveloperUser(userID int64) bool {
	u, err := findUserByID(userID)
	if err != nil || u == nil {
		return false
	}
	return u.QQNumber == DeveloperQQ || u.Email == DeveloperEmail
}

// 基于已加载的 User 对象判断是否为开发者（避免在循环中重复查库）
func isDeveloperUserObj(u *User) bool {
	if u == nil {
		return false
	}
	return u.QQNumber == DeveloperQQ || u.Email == DeveloperEmail
}

// POST /api/bottle/throw — 扔瓶子（新表）
func handleThrowBottle(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	if !isDeveloperUser(userID) {
		driftMu.Lock()
		lastThrow, exists := throwBottleTimes[userID]
		now := time.Now()
		if exists && now.Sub(lastThrow) < 60*time.Second {
			driftMu.Unlock()
			writeJSON(w, 429, "休息一下吧", nil)
			return
		}

		// 每日次数限制：最多扔3次
		todayStart := todayStartUnix()
		var throwToday int
		db.QueryRow("SELECT COUNT(*) FROM bottle_actions WHERE user_id = ? AND action = 'throw' AND created_at >= ?", userID, todayStart).Scan(&throwToday)
		if throwToday >= 3 {
			driftMu.Unlock()
			writeJSON(w, 429, "没瓶子了", nil)
			return
		}

		throwBottleTimes[userID] = now
		driftMu.Unlock()
	}

	var req struct {
		Content   string `json:"content"`
		Type      string `json:"type"`
		ImageData string `json:"image_data"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.Content = strings.TrimSpace(req.Content)
	if req.Content == "" {
		writeJSON(w, 400, "内容不能为空", nil)
		return
	}
	if len(req.Content) > 5000 {
		writeJSON(w, 400, "内容太长了，最多5000字", nil)
		return
	}
	bottleType := "text"
	if req.Type == "image" && req.ImageData != "" {
		bottleType = "image"
		if len(req.ImageData) > 500000 {
			writeJSON(w, 400, "图片数据太大", nil)
			return
		}
	}

	nowUnix := time.Now().Unix()
	_, err := db.Exec(
		"INSERT INTO bottles (user_id, content, type, image_data, created_at) VALUES (?, ?, ?, ?, ?)",
		userID, req.Content, bottleType, req.ImageData, nowUnix,
	)
	if err != nil {
		log.Printf("[漂流瓶] 扔瓶子失败: %v", err)
		writeJSON(w, 500, "漂流瓶扔出失败", nil)
		return
	}
	db.Exec("INSERT INTO bottle_actions (user_id, action, created_at) VALUES (?, 'throw', ?)", userID, time.Now().Unix())
	log.Printf("[漂流瓶] 用户 %d 扔出了一个瓶子", userID)
	writeJSON(w, 200, "漂流瓶已扔入大海", nil)
}

// POST /api/bottle/pick — 捞瓶子（新表）
func handlePickBottle(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	if !isDeveloperUser(userID) {
		driftMu.Lock()
		lastPick, exists := pickBottleTimes[userID]
		now := time.Now()
		if exists && now.Sub(lastPick) < 10*time.Second {
			driftMu.Unlock()
			writeJSON(w, 429, "休息一下吧", nil)
			return
		}

		// 每日次数限制：最多捞10次
		todayStart := todayStartUnix()
		var pickToday int
		db.QueryRow("SELECT COUNT(*) FROM bottle_actions WHERE user_id = ? AND action = 'pick' AND created_at >= ?", userID, todayStart).Scan(&pickToday)
		if pickToday >= 10 {
			driftMu.Unlock()
			writeJSON(w, 429, "没体力了", nil)
			return
		}

		pickBottleTimes[userID] = now
		driftMu.Unlock()
	}

	var bottle struct {
		ID        int64
		Content   string
		Type      string
		ImageData string
		CreatedAt int64
	}
	err := db.QueryRow(`
		SELECT b.id, b.content, b.type, b.image_data, b.created_at
		FROM bottles b
		WHERE b.is_picked = 0
		ORDER BY RANDOM() LIMIT 1
	`).Scan(&bottle.ID, &bottle.Content, &bottle.Type, &bottle.ImageData, &bottle.CreatedAt)
	if err != nil {
		writeJSON(w, 200, "没有捞到", map[string]interface{}{
			"bottle": nil,
		})
		return
	}

	// 从数据库删除漂流瓶记录
	db.Exec("DELETE FROM bottles WHERE id = ?", bottle.ID)

	db.Exec("INSERT INTO bottle_actions (user_id, action, created_at) VALUES (?, 'pick', ?)", userID, time.Now().Unix())

	writeJSON(w, 200, "ok", map[string]interface{}{
		"bottle": map[string]interface{}{
			"content":    bottle.Content,
			"type":       bottle.Type,
			"image_data": bottle.ImageData,
			"created_at": bottle.CreatedAt,
		},
	})
}

// GET /api/bottle/count — 获取漂流瓶总数（新表）
func handleGetBottleCount(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM bottles WHERE is_picked = 0").Scan(&count)
	if err != nil {
		count = 0
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"count": count,
	})
}

// GET /api/bottle/list — 获取海面上所有漂流瓶 ID 列表（用于海面展示）
func handleGetBottleList(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	// 安全：列表只返回 ID 和脱敏短预览，完整内容在捞取（pick）时才返回
	type BottlePreview struct {
		ID      int64  `json:"id"`
		Preview string `json:"preview"`
	}
	var bottles []BottlePreview
	rows, err := db.Query("SELECT id, content FROM bottles WHERE is_picked = 0 ORDER BY RANDOM()")
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var content string
		rows.Scan(&id, &content)
		preview := []rune(content)
		if len(preview) > 30 {
			preview = preview[:30]
		}
		bottles = append(bottles, BottlePreview{ID: id, Preview: string(preview)})
	}
	if bottles == nil {
		bottles = []BottlePreview{}
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"bottles": bottles})
}

// POST /api/bottle/pick/{id} — 按 ID 捞取特定漂流瓶
func handlePickBottleByID(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	idStr := strings.TrimPrefix(r.URL.Path, "/api/bottle/pick/")
	if idStr == "" || idStr == r.URL.Path {
		writeJSON(w, 400, "缺少漂流瓶ID", nil)
		return
	}
	var bottleID int64
	for _, ch := range idStr {
		if ch < '0' || ch > '9' {
			writeJSON(w, 400, "无效的漂流瓶ID", nil)
			return
		}
	}
	bottleID, _ = strconv.ParseInt(idStr, 10, 64)
	if bottleID <= 0 {
		writeJSON(w, 400, "无效的漂流瓶ID", nil)
		return
	}

	// 每日次数限制：最多捞10次（与随机捞取共用次数）
	if !isDeveloperUser(userID) {
		driftMu.Lock()
		lastPick, exists := pickBottleTimes[userID]
		now := time.Now()
		if exists && now.Sub(lastPick) < 10*time.Second {
			driftMu.Unlock()
			writeJSON(w, 429, "休息一下吧", nil)
			return
		}
		todayStart := todayStartUnix()
		var pickToday int
		db.QueryRow("SELECT COUNT(*) FROM bottle_actions WHERE user_id = ? AND action = 'pick' AND created_at >= ?", userID, todayStart).Scan(&pickToday)
		if pickToday >= 10 {
			driftMu.Unlock()
			writeJSON(w, 429, "没体力了", nil)
			return
		}
		pickBottleTimes[userID] = now
		driftMu.Unlock()
	}

	var bottle struct {
		ID        int64
		Content   string
		Type      string
		ImageData string
		CreatedAt int64
	}
	err := db.QueryRow(`SELECT id, content, type, image_data, created_at FROM bottles WHERE id = ? AND is_picked = 0`, bottleID).
		Scan(&bottle.ID, &bottle.Content, &bottle.Type, &bottle.ImageData, &bottle.CreatedAt)
	if err != nil {
		writeJSON(w, 200, "没有捞到", map[string]interface{}{"bottle": nil})
		return
	}
	db.Exec("DELETE FROM bottles WHERE id = ?", bottle.ID)
	db.Exec("INSERT INTO bottle_actions (user_id, action, created_at) VALUES (?, 'pick', ?)", userID, time.Now().Unix())
	writeJSON(w, 200, "ok", map[string]interface{}{
		"bottle": map[string]interface{}{
			"content":    bottle.Content,
			"type":       bottle.Type,
			"image_data": bottle.ImageData,
			"created_at": bottle.CreatedAt,
		},
	})
}
