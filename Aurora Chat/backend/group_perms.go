package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

// ==================== 群管理员细粒度权限 ====================
//
// 角色只有 owner / admin / member 三种，但「管理员能做什么」需要更细：
//   - 群级默认模板：user_id = 0 的那条记录，作用于所有没有单独设定的管理员
//   - 单个管理员覆盖：user_id = 该管理员，优先于默认模板
//
// 存储语义（很重要）：
//   - 记录不存在 → 视为「全部允许」（向后兼容：老群没配过，管理员行为不变）
//   - 记录存在   → 只允许 perms 里列出的 key（空字符串 = 全部禁止）
//
// 群主永远拥有全部权限，member 永远没有，开发者账号可绕过（运维用）。

// 权限 key，顺序即前端展示顺序
var allGroupPermKeys = []string{
	"group_name",
	"group_signature",
	"group_announcement",
	"group_welcome",
	"group_avatar",
	"group_notify",
	"mute",
	"kick",
	"msg_rate",
	"review_join",
	"group_sensitive",
	"group_blacklist",
	"group_todo",
}

func isValidGroupPerm(key string) bool {
	for _, k := range allGroupPermKeys {
		if k == key {
			return true
		}
	}
	return false
}

// 按固定顺序输出（前端展示稳定，存储字符串也稳定，便于比对）
func normalizeGroupPerms(keys []string) []string {
	set := make(map[string]bool, len(keys))
	for _, k := range keys {
		k = strings.TrimSpace(k)
		if isValidGroupPerm(k) {
			set[k] = true
		}
	}
	out := make([]string, 0, len(set))
	for _, k := range allGroupPermKeys {
		if set[k] {
			out = append(out, k)
		}
	}
	return out
}

func ensureGroupAdminPermsTable() {
	if db == nil {
		return
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS group_admin_perms (
		group_id   INTEGER NOT NULL,
		user_id    INTEGER NOT NULL,
		perms      TEXT    NOT NULL DEFAULT '',
		updated_at INTEGER NOT NULL DEFAULT 0,
		PRIMARY KEY (group_id, user_id)
	)`); err != nil {
		log.Printf("[群权限] 建表失败: %v", err)
	}
}

// 新增权限 key 的一次性迁移：给历史记录补上新 key，保持已配置群的行为不变。
func migrateGroupPermsNewKeys() {
	const flagKey = "group_perms_v2_keys"
	if getFlag(flagKey) {
		return
	}
	rows, err := db.Query("SELECT group_id, user_id, perms FROM group_admin_perms")
	if err != nil {
		log.Printf("[群权限] 迁移读取失败: %v", err)
		return
	}
	type permRow struct {
		groupID int64
		userID  int64
		perms   string
	}
	items := make([]permRow, 0)
	for rows.Next() {
		var it permRow
		if rows.Scan(&it.groupID, &it.userID, &it.perms) == nil {
			items = append(items, it)
		}
	}
	rows.Close()
	for _, it := range items {
		parts := make([]string, 0, len(allGroupPermKeys)+3)
		if it.perms != "" {
			parts = append(parts, strings.Split(it.perms, ",")...)
		}
		parts = append(parts, "group_sensitive", "group_blacklist", "group_todo")
		merged := normalizeGroupPerms(parts)
		if _, err := db.Exec("UPDATE group_admin_perms SET perms = ? WHERE group_id = ? AND user_id = ?",
			strings.Join(merged, ","), it.groupID, it.userID); err != nil {
			log.Printf("[群权限] 迁移写入失败 group=%d user=%d: %v", it.groupID, it.userID, err)
			return
		}
	}
	setFlag(flagKey, true)
	log.Printf("[群权限] 新增权限 key 迁移完成，共 %d 条记录", len(items))
}

// 读取一条权限记录：exists=false 表示从未设定过（= 全部允许）
func getGroupPermRow(groupID, userID int64) (perms []string, exists bool) {
	var raw string
	if err := db.QueryRow(
		"SELECT perms FROM group_admin_perms WHERE group_id = ? AND user_id = ?",
		groupID, userID,
	).Scan(&raw); err != nil {
		return nil, false
	}
	out := make([]string, 0, len(allGroupPermKeys))
	for _, k := range strings.Split(raw, ",") {
		k = strings.TrimSpace(k)
		if isValidGroupPerm(k) {
			out = append(out, k)
		}
	}
	return out, true
}

// 当前用户在该群实际拥有的权限 key 列表（群主 = 全部；普通成员 = 空）
func effectiveGroupPerms(groupID, userID int64) []string {
	role, err := getMemberRole(groupID, userID)
	if err != nil || role == "" {
		return []string{}
	}
	if role == "owner" || isDeveloperUser(userID) {
		return append([]string{}, allGroupPermKeys...)
	}
	if role != "admin" {
		return []string{}
	}
	if p, ok := getGroupPermRow(groupID, userID); ok {
		return p
	}
	if p, ok := getGroupPermRow(groupID, 0); ok {
		return p
	}
	return append([]string{}, allGroupPermKeys...)
}

// 单个权限判定：服务端每个写接口的兜底闸门
func hasGroupPerm(groupID, userID int64, key string) bool {
	if isDeveloperUser(userID) {
		return true
	}
	role, err := getMemberRole(groupID, userID)
	if err != nil || role == "" {
		return false
	}
	if role == "owner" {
		return true
	}
	if role != "admin" {
		return false
	}
	var (
		perms  []string
		exists bool
	)
	if perms, exists = getGroupPermRow(groupID, userID); !exists {
		perms, exists = getGroupPermRow(groupID, 0)
	}
	if !exists {
		return true // 从未配置过：保持旧行为（管理员全部允许）
	}
	for _, k := range perms {
		if k == key {
			return true
		}
	}
	return false
}

func saveGroupPermRow(groupID, userID int64, perms []string) error {
	clean := normalizeGroupPerms(perms)
	_, err := db.Exec(
		`INSERT INTO group_admin_perms (group_id, user_id, perms, updated_at) VALUES (?, ?, ?, ?)
		 ON CONFLICT(group_id, user_id) DO UPDATE SET perms = excluded.perms, updated_at = excluded.updated_at`,
		groupID, userID, strings.Join(clean, ","), time.Now().Unix(),
	)
	return err
}

func deleteGroupPermRow(groupID, userID int64) {
	db.Exec("DELETE FROM group_admin_perms WHERE group_id = ? AND user_id = ?", groupID, userID)
}

// ==================== HTTP 接口 ====================

type groupPermAdminEntry struct {
	UserID     int64    `json:"user_id"`
	Username   string   `json:"username"`
	Perms      []string `json:"perms"`
	Customized bool     `json:"customized"` // true = 单独设定过；false = 跟随默认模板
}

// /api/groups/admin-perms
//
//	GET  ?group_id=123  → 读取（仅群主 / 开发者）
//	POST {group_id, user_id(0=默认模板), perms:[...]}  → 保存（仅群主 / 开发者）
func handleGroupAdminPerms(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" && r.Method != "POST" {
		writeJSON(w, 405, "仅支持 GET/POST", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	if r.Method == "GET" {
		groupID := parseInt64Safe(r.URL.Query().Get("group_id"))
		if groupID <= 0 {
			writeJSON(w, 400, "缺少 group_id", nil)
			return
		}
		// 只有群主能看/改这套配置（开发者运维可看）
		role, err := getMemberRole(groupID, currentUserID)
		if !isDeveloperUser(currentUserID) && (err != nil || role != "owner") {
			writeJSON(w, 403, "仅群主可设置管理权限", nil)
			return
		}

		defaultPerms, defaultCustomized := getGroupPermRow(groupID, 0)
		if !defaultCustomized {
			defaultPerms = append([]string{}, allGroupPermKeys...)
		}

		admins := make([]groupPermAdminEntry, 0, 8)
		if rows, err := db.Query(
			`SELECT gm.user_id, COALESCE(u.username, '')
			 FROM group_members gm LEFT JOIN users u ON gm.user_id = u.id
			 WHERE gm.group_id = ? AND gm.role = 'admin'
			 ORDER BY gm.joined_at ASC`, groupID,
		); err == nil {
			for rows.Next() {
				var uid int64
				var name string
				if err := rows.Scan(&uid, &name); err != nil {
					continue
				}
				entry := groupPermAdminEntry{UserID: uid, Username: name}
				if p, ok := getGroupPermRow(groupID, uid); ok {
					entry.Perms = p
					entry.Customized = true
				} else {
					entry.Perms = defaultPerms
				}
				admins = append(admins, entry)
			}
			rows.Close()
		}

		writeJSON(w, 200, "获取成功", map[string]interface{}{
			"group_id":           groupID,
			"all_keys":           allGroupPermKeys,
			"default_perms":      defaultPerms,
			"default_customized": defaultCustomized,
			"admins":             admins,
			"my_perms":           effectiveGroupPerms(groupID, currentUserID),
		})
		return
	}

	// POST
	var req struct {
		GroupID int64    `json:"group_id"`
		UserID  int64    `json:"user_id"` // 0 = 群级默认模板
		Perms   []string `json:"perms"`
		Reset   bool     `json:"reset"` // true = 删除该条记录，回到「跟随默认 / 全开」
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID <= 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	role, err := getMemberRole(req.GroupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || role != "owner") {
		writeJSON(w, 403, "仅群主可设置管理权限", nil)
		return
	}
	if req.UserID > 0 {
		targetRole, _ := getMemberRole(req.GroupID, req.UserID)
		if targetRole != "admin" {
			writeJSON(w, 400, "该用户不是管理员", nil)
			return
		}
	}

	if req.Reset {
		deleteGroupPermRow(req.GroupID, req.UserID)
	} else if err := saveGroupPermRow(req.GroupID, req.UserID, req.Perms); err != nil {
		log.Printf("[群权限] 保存失败 group=%d user=%d: %v", req.GroupID, req.UserID, err)
		writeJSON(w, 500, "保存失败", nil)
		return
	}

	saved := effectiveGroupPerms(req.GroupID, req.UserID)
	if req.UserID == 0 {
		if p, ok := getGroupPermRow(req.GroupID, 0); ok {
			saved = p
		} else {
			saved = append([]string{}, allGroupPermKeys...)
		}
	}
	log.Printf("[群权限] group=%d user=%d 权限已保存: %v", req.GroupID, req.UserID, saved)
	writeJSON(w, 200, "已保存", map[string]interface{}{"perms": saved})
}

// ==================== 群设置字段级权限判定 ====================

// 群设置快照（用于判断本次提交到底改了哪些字段，再逐个字段校验权限）
type groupSettingsSnapshot struct {
	Name           string
	Signature      string
	Announcement   string
	WelcomeEnabled bool
	WelcomeText    string
}

func getGroupSettingsSnapshot(groupID int64) groupSettingsSnapshot {
	var s groupSettingsSnapshot
	db.QueryRow(
		"SELECT COALESCE(name,''), COALESCE(signature,''), COALESCE(announcement,''), COALESCE(welcome_enabled,0), COALESCE(welcome_text,'') FROM groups WHERE id = ?",
		groupID,
	).Scan(&s.Name, &s.Signature, &s.Announcement, &s.WelcomeEnabled, &s.WelcomeText)
	return s
}

// 公告是按 JSON 存的，字符串直接比对会因字段顺序/省略差异误判成"改过"，
// 这里按语义比较（文本/图片/弹窗/需确认/发布时间），避免管理员只改签名却被判越权。
func sameGroupAnnouncements(a, b string) bool {
	if a == b {
		return true
	}
	pa, pb := parseGroupAnnouncements(a), parseGroupAnnouncements(b)
	if len(pa) != len(pb) {
		return false
	}
	for i := range pa {
		x, y := pa[i], pb[i]
		if x.Text != y.Text || x.PopupEnabled != y.PopupEnabled || x.NeedConfirm != y.NeedConfirm || x.CreatedAt != y.CreatedAt {
			return false
		}
		if len(x.Images) != len(y.Images) {
			return false
		}
		for j := range x.Images {
			if x.Images[j] != y.Images[j] {
				return false
			}
		}
	}
	return true
}

// 本次提交需要哪些权限 key（只把真正变化的字段算进去，避免"顺带提交原值"被误判越权）
func requiredGroupSettingPerms(groupID int64, name, signature, announcement string, welcomeEnabled bool, welcomeText string) []string {
	old := getGroupSettingsSnapshot(groupID)
	need := make([]string, 0, 4)
	if name != old.Name {
		need = append(need, "group_name")
	}
	if signature != old.Signature {
		need = append(need, "group_signature")
	}
	if !sameGroupAnnouncements(announcement, old.Announcement) {
		need = append(need, "group_announcement")
	}
	if welcomeEnabled != old.WelcomeEnabled || welcomeText != old.WelcomeText {
		need = append(need, "group_welcome")
	}
	return need
}

// 群设置的统一出口：群主天然通过，管理员按细粒度权限逐项判断
func checkGroupSettingPerms(groupID, userID int64, need []string) (string, bool) {
	for _, key := range need {
		if !hasGroupPerm(groupID, userID, key) {
			return "没有修改「" + groupPermLabel(key) + "」的权限", false
		}
	}
	return "", true
}

func groupPermLabel(key string) string {
	switch key {
	case "group_name":
		return "群名称"
	case "group_signature":
		return "群签名"
	case "group_announcement":
		return "群公告"
	case "group_welcome":
		return "群欢迎语"
	case "group_avatar":
		return "群头像"
	case "group_notify":
		return "群通知方式"
	case "mute":
		return "禁言成员"
	case "kick":
		return "移除成员"
	case "msg_rate":
		return "发言频率"
	case "review_join":
		return "审批入群"
	case "group_sensitive":
		return "群敏感词"
	case "group_blacklist":
		return "群黑名单"
	case "group_todo":
		return "群待办"
	}
	return key
}
