package main

import (
	"encoding/json"
	"log"
	"net/http"
	"time"
)

// ==================== 关注（粉丝） ====================
//
// 数据模型：user_follows 一条记录 = 「follower 关注了 followee」。
// 方向必须写死，不要用对称表（好友是互相的，关注是单向的）：
//   - 粉丝数   = COUNT(*) WHERE followee_id = ?
//   - 关注数   = COUNT(*) WHERE follower_id = ?
//   - 是否已关注 = 上面那条是否存在于 (currentUser, target)
//
// 唯一约束 (follower_id, followee_id) 保证重复点关注不会产生多条记录，
// 接口里用 INSERT OR IGNORE 就能做到幂等。

// InitFollowTables 建关注表。启动时调用一次，IF NOT EXISTS 保证可重复执行。
func InitFollowTables() {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS user_follows (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			follower_id INTEGER NOT NULL,
			followee_id INTEGER NOT NULL,
			created_at INTEGER NOT NULL,
			UNIQUE(follower_id, followee_id)
		)`,
		// 按粉丝查（「谁关注了我」、粉丝数）走这个索引
		`CREATE INDEX IF NOT EXISTS idx_follows_followee ON user_follows(followee_id, created_at DESC)`,
		// 按关注数查（「我关注了谁」）
		`CREATE INDEX IF NOT EXISTS idx_follows_follower ON user_follows(follower_id, created_at DESC)`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			log.Printf("[关注] 建表失败（关注功能将不可用）: %v", err)
		}
	}
}

// isFollowing 判断 follower 是否已关注 followee。
func isFollowing(followerID, followeeID int64) bool {
	if followerID <= 0 || followeeID <= 0 {
		return false
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM user_follows WHERE follower_id = ? AND followee_id = ?`,
		followerID, followeeID).Scan(&n); err != nil {
		return false
	}
	return n > 0
}

// followCounts 返回 (粉丝数, 关注数)。
func followCounts(userID int64) (int, int) {
	var fans, following int
	if userID <= 0 {
		return 0, 0
	}
	db.QueryRow(`SELECT COUNT(*) FROM user_follows WHERE followee_id = ?`, userID).Scan(&fans)
	db.QueryRow(`SELECT COUNT(*) FROM user_follows WHERE follower_id = ?`, userID).Scan(&following)
	return fans, following
}

// handleFollowUser 关注某人。
//
// 幂等：重复调用不会报错、不会产生重复记录（INSERT OR IGNORE）。
// 副作用：写一条 community_activities（action_type=follow），
// 让对方在「动态」里看到「XX 关注了你」；同一对用户重复关注不重复写动态。
func handleFollowUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		TargetUserID int64 `json:"target_user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.TargetUserID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	if req.TargetUserID == currentUserID {
		writeJSON(w, 400, "不能关注自己", nil)
		return
	}
	target, err := findUserByID(req.TargetUserID)
	if err != nil || target == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	now := time.Now().Unix()
	res, err := db.Exec(`INSERT OR IGNORE INTO user_follows (follower_id, followee_id, created_at) VALUES (?, ?, ?)`,
		currentUserID, req.TargetUserID, now)
	if err != nil {
		log.Printf("[关注] 用户 %d 关注 %d 失败: %v", currentUserID, req.TargetUserID, err)
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	// 只有真的插入了新行才写动态，避免反复点关注刷出一堆重复消息
	if n, _ := res.RowsAffected(); n > 0 {
		// target_id 存「关注者」的 id：动态页点进去应该看到关注自己的人，
		// target_title 留空 —— 显示名由动态页 JOIN user_id 得到，不在两处各存一份。
		db.Exec(`INSERT INTO community_activities
			(user_id, target_user_id, action_type, target_type, target_id, target_title, comment_content, created_at)
			VALUES (?, ?, 'follow', 'user', ?, '', '', ?)`,
			currentUserID, req.TargetUserID, currentUserID, now)
		log.Printf("[关注] 用户 %d 关注了 %d（%s）", currentUserID, req.TargetUserID, target.Username)
	}

	fans, following := followCounts(req.TargetUserID)
	writeJSON(w, 200, "已关注", map[string]interface{}{
		"target_user_id": req.TargetUserID,
		"is_following":   true,
		"fans":           fans,
		"following":      following,
	})
}

// handleUnfollowUser 取消关注。幂等。
func handleUnfollowUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		TargetUserID int64 `json:"target_user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.TargetUserID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	db.Exec(`DELETE FROM user_follows WHERE follower_id = ? AND followee_id = ?`, currentUserID, req.TargetUserID)
	log.Printf("[关注] 用户 %d 取消关注 %d", currentUserID, req.TargetUserID)

	fans, following := followCounts(req.TargetUserID)
	writeJSON(w, 200, "已取消关注", map[string]interface{}{
		"target_user_id": req.TargetUserID,
		"is_following":   false,
		"fans":           fans,
		"following":      following,
	})
}

// handleFollowStats 查某人的粉丝/关注数，以及「我是否已关注他」。
//
// 资料页只需要这三个数，单独开一个轻量接口，不去改现有的用户资料返回结构
// （那个结构被很多地方使用，动它容易连带出问题）。
//
// GET /api/user/follow_stats?id=123
func handleFollowStats(w http.ResponseWriter, r *http.Request) {
	currentUserID := getCurrentUserID(r)
	var targetID int64
	if s := r.URL.Query().Get("id"); s != "" {
		if n := parseInt64Safe(s); n > 0 {
			targetID = n
		}
	}
	if targetID <= 0 {
		targetID = currentUserID
	}
	if targetID <= 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	fans, following := followCounts(targetID)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"user_id":      targetID,
		"fans":         fans,
		"following":    following,
		// 我是否关注他
		"is_following": isFollowing(currentUserID, targetID),
		// 他是否关注我 —— 两个都为真才是「互相关注」。
		// 没有这个字段，客户端只能显示「已关注」，看不出对方也关注了自己。
		"follows_you": isFollowing(targetID, currentUserID),
	})
}
