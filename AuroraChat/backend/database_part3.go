package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	_ "modernc.org/sqlite"
	"os"
	"strings"
	"time"
)

func getGroupByID(groupID int64) (*Group, error) {
	g := &Group{}
	err := db.QueryRow("SELECT id, name, signature, announcement, creator_id, avatar, display_id, welcome_enabled, welcome_text, not_searchable, join_required, msg_rate_limit, msg_rate_scope, created_at, updated_at FROM groups WHERE id = ?", groupID).Scan(&g.ID, &g.Name, &g.Signature, &g.Announcement, &g.CreatorID, &g.Avatar, &g.DisplayID, &g.WelcomeEnabled, &g.WelcomeText, &g.NotSearchable, &g.JoinRequired, &g.MsgRateLimit, &g.MsgRateScope, &g.CreatedAt, &g.UpdatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	g.Announcements = parseGroupAnnouncements(g.Announcement)
	return g, nil
}

// parseGroupAnnouncements 将 announcement 字段解析为多公告列表。
// 新格式为 GroupAnnouncement 对象数组；旧数据兼容两种：字符串数组、单条普通字符串。
func parseGroupAnnouncements(raw string) []GroupAnnouncement {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return []GroupAnnouncement{}
	}
	// 新格式：对象数组
	var list []GroupAnnouncement
	if err := json.Unmarshal([]byte(raw), &list); err == nil {
		clean := make([]GroupAnnouncement, 0, len(list))
		for _, a := range list {
			if strings.TrimSpace(a.Text) != "" {
				if len(a.Images) > 5 {
					a.Images = a.Images[:5]
				}
				clean = append(clean, a)
			}
		}
		return clean
	}
	// 旧格式1：字符串数组
	var legacyList []string
	if err := json.Unmarshal([]byte(raw), &legacyList); err == nil {
		clean := make([]GroupAnnouncement, 0, len(legacyList))
		for _, s := range legacyList {
			if strings.TrimSpace(s) != "" {
				clean = append(clean, GroupAnnouncement{Text: s})
			}
		}
		return clean
	}
	// 旧格式2：单条公告
	return []GroupAnnouncement{{Text: raw}}
}

// marshalGroupAnnouncements 将多公告列表序列化为 JSON 数组字符串存储。
// 每项文本已由调用方转义（与其它群资料字段一致），此处仅负责组装存储格式。
func marshalGroupAnnouncements(list []GroupAnnouncement) string {
	clean := make([]groupAnnouncementStore, 0, len(list))
	for _, a := range list {
		if strings.TrimSpace(a.Text) != "" {
			if len(a.Images) > 5 {
				a.Images = a.Images[:5]
			}
			clean = append(clean, groupAnnouncementStore{
				Text:         a.Text,
				Images:       a.Images,
				PopupEnabled: a.PopupEnabled,
				NeedConfirm:  a.NeedConfirm,
				CreatedAt:    a.CreatedAt,
			})
		}
	}
	b, err := json.Marshal(clean)
	if err != nil {
		return ""
	}
	return string(b)
}

// markAnnouncementConfirmed 记录群成员确认收到某条公告（幂等：重复确认忽略）。
// 公告标识 = 公告文本，与撤回联动移除口径一致。
func markAnnouncementConfirmed(groupID int64, text string, userID int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_announcement_confirmations (group_id, announcement_text, user_id, confirmed_at) VALUES (?, ?, ?, ?)", groupID, text, userID, time.Now().Unix())
	return err
}

// isAnnouncementConfirmed 判断指定用户是否已确认某条公告。
func isAnnouncementConfirmed(groupID int64, text string, userID int64) bool {
	var n int
	err := db.QueryRow("SELECT COUNT(*) FROM group_announcement_confirmations WHERE group_id = ? AND announcement_text = ? AND user_id = ?", groupID, text, userID).Scan(&n)
	return err == nil && n > 0
}

// countAnnouncementConfirmed 统计某条公告的确认人数。
func countAnnouncementConfirmed(groupID int64, text string) int {
	var n int
	if err := db.QueryRow("SELECT COUNT(*) FROM group_announcement_confirmations WHERE group_id = ? AND announcement_text = ?", groupID, text).Scan(&n); err != nil {
		return 0
	}
	return n
}

func getGroupsForUser(userID int64) ([]Group, error) {
	rows, err := db.Query("SELECT g.id, g.name, g.signature, g.announcement, g.creator_id, g.avatar, g.display_id, g.welcome_enabled, g.welcome_text, g.msg_rate_limit, g.created_at, g.updated_at FROM groups g JOIN group_members gm ON g.id = gm.group_id WHERE gm.user_id = ? ORDER BY g.created_at DESC", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var groups []Group
	for rows.Next() {
		var g Group
		if err := rows.Scan(&g.ID, &g.Name, &g.Signature, &g.Announcement, &g.CreatorID, &g.Avatar, &g.DisplayID, &g.WelcomeEnabled, &g.WelcomeText, &g.MsgRateLimit, &g.CreatedAt, &g.UpdatedAt); err != nil {
			return nil, err
		}
		g.Announcements = parseGroupAnnouncements(g.Announcement)
		groups = append(groups, g)
	}
	return groups, nil
}

func getGroupMembersWithInfo(groupID int64) ([]GroupMemberInfo, error) {
	rows, err := db.Query("SELECT gm.user_id, u.username, gm.role, gm.joined_at FROM group_members gm JOIN users u ON gm.user_id = u.id WHERE gm.group_id = ? ORDER BY CASE gm.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, gm.joined_at ASC", groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var members []GroupMemberInfo
	for rows.Next() {
		var m GroupMemberInfo
		rows.Scan(&m.UserID, &m.Username, &m.Role, &m.JoinedAt)
		members = append(members, m)
	}
	return members, nil
}

type GroupMemberInfo struct {
	UserID   int64  `json:"user_id"`
	Username string `json:"username"`
	Role     string `json:"role"`
	JoinedAt int64  `json:"joined_at"`
}

func getGroupMembers(groupID int64) ([]int64, error) {
	rows, err := db.Query("SELECT user_id FROM group_members WHERE group_id = ?", groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var uid int64
		rows.Scan(&uid)
		ids = append(ids, uid)
	}
	return ids, nil
}

func getMemberRole(groupID, userID int64) (string, error) {
	var role string
	err := db.QueryRow("SELECT role FROM group_members WHERE group_id = ? AND user_id = ?", groupID, userID).Scan(&role)
	if err != nil {
		return "", err
	}
	return role, nil
}

// GroupTodo 记录一个群当前生效的群待办（指向某条群消息）。
type GroupTodo struct {
	GroupID    int64  `json:"group_id"`
	MessageID  int64  `json:"message_id"`
	SetterID   int64  `json:"setter_id"`
	SetterName string `json:"setter_name"`
	Snippet    string `json:"snippet"`
	CreatedAt  int64  `json:"created_at"`
}

// groupConvIDToID 由消息/会话的 to_user_id（convID = -(1000+groupID)）反推群内部 id。
// 与 PushToGroup / PushRecallToGroup 的编码严格一致。
func groupConvIDToID(convID int64) int64 {
	return -convID - 1000
}

// getGroupIDByMessage 给定一条消息 id，若其属于群聊则返回群内部 id，否则返回 0。
func getGroupIDByMessage(messageID int64) (int64, error) {
	var toUserID int64
	err := db.QueryRow("SELECT to_user_id FROM messages WHERE id = ?", messageID).Scan(&toUserID)
	if err != nil {
		return 0, err
	}
	if toUserID >= 0 {
		return 0, nil
	}
	return groupConvIDToID(toUserID), nil
}

// getGroupTodo 读取群当前生效的待办（无则返回 nil）。
func getGroupTodo(groupID int64) (*GroupTodo, error) {
	var t GroupTodo
	err := db.QueryRow("SELECT group_id, message_id, setter_id, setter_name, snippet, created_at FROM group_todos WHERE group_id = ?", groupID).
		Scan(&t.GroupID, &t.MessageID, &t.SetterID, &t.SetterName, &t.Snippet, &t.CreatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &t, nil
}

// setGroupTodo 设置/覆盖群待办（UPSERT，按 group_id 唯一）。
func setGroupTodo(t *GroupTodo) error {
	_, err := db.Exec(`INSERT INTO group_todos (group_id, message_id, setter_id, setter_name, snippet, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(group_id) DO UPDATE SET message_id=excluded.message_id, setter_id=excluded.setter_id, setter_name=excluded.setter_name, snippet=excluded.snippet, created_at=excluded.created_at`,
		t.GroupID, t.MessageID, t.SetterID, t.SetterName, t.Snippet, t.CreatedAt)
	return err
}

// clearGroupTodo 清除群待办（对所有人撤回后调用）。
func clearGroupTodo(groupID int64) error {
	_, err := db.Exec("DELETE FROM group_todos WHERE group_id = ?", groupID)
	return err
}

func updateGroupSettings(groupID int64, name, signature, announcement string, welcomeEnabled bool, welcomeText string, notSearchable, joinRequired bool) error {
	wEnabled := 0
	if welcomeEnabled {
		wEnabled = 1
	}
	ns := 0
	if notSearchable {
		ns = 1
	}
	jr := 0
	if joinRequired {
		jr = 1
	}
	_, err := db.Exec("UPDATE groups SET name=?, signature=?, announcement=?, welcome_enabled=?, welcome_text=?, not_searchable=?, join_required=?, updated_at=? WHERE id=?", name, signature, announcement, wEnabled, welcomeText, ns, jr, time.Now().Unix(), groupID)
	return err
}

// removeGroupAnnouncement 从群公告列表（announcements JSON 数组）中移除与 text 匹配的第一条。
// 供「撤回群公告卡片消息」时联动使用：公告列表按文本匹配删除。
func removeGroupAnnouncement(groupID int64, text string) error {
	g, err := getGroupByID(groupID)
	if err != nil {
		return err
	}
	if g == nil {
		return nil
	}
	list := g.Announcements
	for i, a := range list {
		if a.Text == text {
			list = append(list[:i], list[i+1:]...)
			break
		}
	}
	_, err = db.Exec("UPDATE groups SET announcement=?, updated_at=? WHERE id=?", marshalGroupAnnouncements(list), time.Now().Unix(), groupID)
	return err
}

// setGroupMsgRateLimit 保存群内发言频率（-1=不限制，0=全员禁言，N>0=每分钟 N 条）
func setGroupMsgRateLimit(groupID int64, limit int) error {
	_, err := db.Exec("UPDATE groups SET msg_rate_limit=?, updated_at=? WHERE id=?", limit, time.Now().Unix(), groupID)
	return err
}

// setGroupMsgRateScope 保存发言频率的作用范围（0=群主及管理员不受限，1=管理员受限，2=全部受限）
func setGroupMsgRateScope(groupID int64, scope int) error {
	_, err := db.Exec("UPDATE groups SET msg_rate_scope=?, updated_at=? WHERE id=?", scope, time.Now().Unix(), groupID)
	return err
}

// collectGroupMediaURLs 取某个群消息里引用到的全部媒体 URL（去重）。
// 必须在删消息行之前调用：messages 是这些 URL 的唯一索引，行没了就再也反查不到。
func collectGroupMediaURLs(groupID int64) []string {
	convID := -(1000 + groupID)
	urls := make([]string, 0, 32)
	rows, err := db.Query(
		`SELECT DISTINCT media_url FROM messages
		 WHERE (to_user_id = ? OR from_user_id = ?) AND media_url != ''`,
		convID, convID,
	)
	if err != nil {
		log.Printf("[群] 收集媒体 URL 失败: group=%d err=%v", groupID, err)
		return urls
	}
	defer rows.Close()
	for rows.Next() {
		var u string
		if rows.Scan(&u) == nil && u != "" {
			urls = append(urls, u)
		}
	}
	return urls
}

// groupChildTables 解散群聊时需要一并清空的附属表（归属维度都只有 group_id）。
// 原先只删 messages / group_members / groups，这 10 张表会留下永远没人用的孤儿行。
var groupChildTables = []string{
	"group_todos",
	"group_join_requests",
	"group_invites",
	"group_announcement_confirmations",
	"group_blacklist",
	"group_mutes",
	"group_notify_settings",
	"group_admin_perms",
	"group_sensitive_words",
	"group_sensitive_config",
}

func deleteGroup(groupID int64) error {
	convID := -(1000 + groupID)

	// 关键：先把本群消息里的媒体 URL 收下来再删行。
	// cleanupOldMedia 的回收方式是「从 messages 表里查过期 media_url，再删对应文件」，
	// 消息行一删，这些 URL 就从索引里彻底消失，物理文件再也无人引用得到、永远不会被回收
	// （data/chat_media/<文件名> 里不含群号，只能靠 media_url 反查）。
	mediaURLs := collectGroupMediaURLs(groupID)

	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	tx.Exec("DELETE FROM messages WHERE from_user_id = ? OR to_user_id = ?", convID, convID)
	tx.Exec("DELETE FROM group_members WHERE group_id = ?", groupID)
	tx.Exec("DELETE FROM groups WHERE id = ?", groupID)
	// 附属表：表名是编译期常量，不涉及外部输入拼接
	for _, table := range groupChildTables {
		tx.Exec("DELETE FROM "+table+" WHERE group_id = ?", groupID)
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	// 物理文件回收放在提交之后且异步：删文件可能涉及成百上千个（含两张缩略图），
	// 不能拖慢解散接口本身；失败只记日志，不影响群已解散的事实。
	go cleanupDissolvedGroupFiles(groupID, mediaURLs)
	return nil
}

// cleanupDissolvedGroupFiles 回收解散群聊「独占」的媒体文件与群头像。
//
// 为什么必须逐个查引用：转发 / 分享是直接复用原 /chat-media/ URL 的（客户端
// ShareReceiveHandler 就是拿旧 URL 发一条新消息），同一个物理文件可能还被私聊或
// 别的群的消息引用。无条件删除会把那些会话里的同一张图一起删掉，所以「还有引用就留」。
func cleanupDissolvedGroupFiles(groupID int64, mediaURLs []string) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("[解散群] 媒体回收 panic: group=%d err=%v", groupID, r)
		}
	}()
	if len(mediaURLs) == 0 {
		if p := groupAvatarFile(groupID); p != "" {
			_ = os.Remove(p)
		}
		return
	}
	// 批量查「还有没有别的消息引用同一个 URL」：medias 表没有 media_url 索引，
	// 逐个 COUNT 会变成 N 次全表扫描；这里按 400 个一批用 IN 查，把扫描次数压到常数级。
	stillUsed := make(map[string]bool, len(mediaURLs))
	for _, u := range mediaURLs {
		stillUsed[u] = false
	}
	const chunk = 400
	for start := 0; start < len(mediaURLs); start += chunk {
		end := start + chunk
		if end > len(mediaURLs) {
			end = len(mediaURLs)
		}
		batch := mediaURLs[start:end]
		placeholders := strings.TrimSuffix(strings.Repeat("?,", len(batch)), ",")
		args := make([]interface{}, 0, len(batch))
		for _, u := range batch {
			args = append(args, u)
		}
		rows, err := db.Query("SELECT DISTINCT media_url FROM messages WHERE media_url IN ("+placeholders+")", args...)
		if err != nil {
			log.Printf("[解散群] 查询媒体引用失败: group=%d err=%v", groupID, err)
			continue
		}
		for rows.Next() {
			var u string
			if rows.Scan(&u) == nil {
				stillUsed[u] = true
			}
		}
		rows.Close()
	}
	removed, kept := 0, 0
	for u, used := range stillUsed {
		if used {
			kept++ // 仍被其它会话引用（转发/分享），保留文件
			continue
		}
		if deleteChatMediaByURL(u) {
			removed++
		}
	}
	// 群头像：上传时固定落盘为 group_<id>.png（见 handleGroupAvatarUpload），
	// 群行删掉后没有任何地方还能用它。
	if p := groupAvatarFile(groupID); p != "" {
		if err := os.Remove(p); err == nil {
			removed++
		}
	}
	if removed > 0 || kept > 0 {
		log.Printf("[解散群] 群 %d 文件回收完成：删除 %d 个，因仍被引用保留 %d 个（群头像已尝试清理）", groupID, removed, kept)
	}
}

func transferGroupOwner(groupID, newOwnerID int64) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// 将当前所有群主降级为管理员。
	// 关键：以"当前真正的 owner"为准，而不是调用者。否则开发者转让（开发者未必是群主）时，
	// 原群主不会被降级，导致一个群出现多个群主。
	if _, err := tx.Exec("UPDATE group_members SET role = 'admin' WHERE group_id = ? AND role = 'owner'", groupID); err != nil {
		return err
	}
	// 新群主：若已是成员则升级为 owner，否则加入群并设为 owner
	if _, err := tx.Exec("UPDATE group_members SET role = 'owner' WHERE group_id = ? AND user_id = ?", groupID, newOwnerID); err != nil {
		return err
	}
	if _, err := tx.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'owner', ?)", groupID, newOwnerID, time.Now().Unix()); err != nil {
		return err
	}
	return tx.Commit()
}

func setGroupRole(groupID, userID int64, role string) error {
	_, err := db.Exec("UPDATE group_members SET role = ? WHERE group_id = ? AND user_id = ?", role, groupID, userID)
	return err
}

func searchUsersByKeyword(keyword string) ([]User, error) {
	like := "%" + keyword + "%"
	rows, err := db.Query("SELECT id, email, username, created_at, updated_at, hide_email, hide_qq, require_group_consent, qq_number, signature, online_time_seconds, word_count FROM users WHERE email LIKE ? OR username LIKE ? OR CAST(id AS TEXT) LIKE ? OR qq_number LIKE ? LIMIT 50", like, like, like, like)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []User
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Email, &u.Username, &u.CreatedAt, &u.UpdatedAt, &u.HideEmail, &u.HideQQ, &u.RequireGroupConsent, &u.QQNumber, &u.Signature, &u.OnlineTimeSeconds, &u.WordCount); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	return users, nil
}

func addToGroupDirectly(groupID, userID int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)", groupID, userID, time.Now().Unix())
	if err == nil {
		sendGroupWelcomeMessage(groupID, userID)
	}
	return err
}

func removeFromGroup(groupID, userID int64) error {
	_, err := db.Exec("DELETE FROM group_members WHERE group_id = ? AND user_id = ?", groupID, userID)
	return err
}

func isGroupBlacklisted(groupID, userID int64) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM group_blacklist WHERE group_id = ? AND user_id = ?", groupID, userID).Scan(&count)
	return count > 0, err
}

func addGroupBlacklist(groupID, userID, blockedBy int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_blacklist (group_id, user_id, blocked_at, blocked_by) VALUES (?, ?, ?, ?)", groupID, userID, time.Now().Unix(), blockedBy)
	return err
}

func listAllGroups(page, limit int, keyword string) ([]Group, int, error) {
	offset := (page - 1) * limit
	var total int
	var rows *sql.Rows
	var err error
	if keyword != "" {
		like := "%" + keyword + "%"
		db.QueryRow("SELECT COUNT(*) FROM groups WHERE name LIKE ? OR CAST(display_id AS TEXT) LIKE ?", like, like).Scan(&total)
		rows, err = db.Query("SELECT id, name, signature, announcement, creator_id, avatar, display_id, welcome_enabled, welcome_text, created_at, updated_at FROM groups WHERE name LIKE ? OR CAST(display_id AS TEXT) LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?", like, like, limit, offset)
	} else {
		db.QueryRow("SELECT COUNT(*) FROM groups").Scan(&total)
		rows, err = db.Query("SELECT id, name, signature, announcement, creator_id, avatar, display_id, welcome_enabled, welcome_text, created_at, updated_at FROM groups ORDER BY id DESC LIMIT ? OFFSET ?", limit, offset)
	}
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var groups []Group
	for rows.Next() {
		var g Group
		if err := rows.Scan(&g.ID, &g.Name, &g.Signature, &g.Announcement, &g.CreatorID, &g.Avatar, &g.DisplayID, &g.WelcomeEnabled, &g.WelcomeText, &g.CreatedAt, &g.UpdatedAt); err != nil {
			return nil, 0, err
		}
		g.Announcements = parseGroupAnnouncements(g.Announcement)
		groups = append(groups, g)
	}
	return groups, total, nil
}

func insertWelcomeMessage(groupConvID int64, welcomeText string) (int64, error) {
	if welcomeText == "" {
		return 0, nil
	}
	result, err := db.Exec("INSERT INTO messages (from_user_id, to_user_id, content, created_at, server_received_ms) VALUES (?, ?, ?, ?, ?)", groupConvID, groupConvID, encryptContentStore(welcomeText), time.Now().Unix(), time.Now().UnixMilli())
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

func submitJoinRequest(groupID, userID int64, reason string) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_join_requests (group_id, user_id, reason, status, created_at) VALUES (?, ?, ?, 'pending', ?)", groupID, userID, reason, time.Now().Unix())
	return err
}

func getGroupJoinRequests(groupID int64) ([]JoinRequest, error) {
	rows, err := db.Query("SELECT gjr.id, gjr.group_id, gjr.user_id, u.username, u.email, gjr.reason, gjr.status, gjr.created_at FROM group_join_requests gjr JOIN users u ON gjr.user_id = u.id WHERE gjr.group_id = ? AND gjr.status = 'pending' ORDER BY gjr.created_at DESC", groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var requests []JoinRequest
	for rows.Next() {
		var r JoinRequest
		if err := rows.Scan(&r.ID, &r.GroupID, &r.UserID, &r.Username, &r.Email, &r.Reason, &r.Status, &r.CreatedAt); err != nil {
			return nil, err
		}
		requests = append(requests, r)
	}
	return requests, nil
}

// ==================== 群邀请（拉我时需经过我同意） ====================

// getUserRequireConsent 读取目标用户的"拉我时需经过我同意"开关（默认开启）。
func getUserRequireConsent(userID int64) bool {
	var v int
	err := db.QueryRow("SELECT require_group_consent FROM users WHERE id = ?", userID).Scan(&v)
	if err != nil {
		return true // 默认开启
	}
	return v == 1
}

// getGroupName 读取群名，用于邀请卡片/结果通知拼文案。
func getGroupName(groupID int64) string {
	var name string
	err := db.QueryRow("SELECT name FROM groups WHERE id = ?", groupID).Scan(&name)
	if err != nil {
		return ""
	}
	return name
}

// getGroupJoinRequired 判断群是否需要审核加入。
func getGroupJoinRequired(groupID int64) bool {
	var v int
	err := db.QueryRow("SELECT join_required FROM groups WHERE id = ?", groupID).Scan(&v)
	if err != nil {
		return false
	}
	return v == 1
}

// createGroupInvite 记录一次待确认的入群邀请（重复邀请被忽略）。
func createGroupInvite(groupID, inviterID, targetUserID int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_invites (group_id, inviter_id, target_user_id, status, created_at) VALUES (?, ?, ?, 'pending', ?)",
		groupID, inviterID, targetUserID, time.Now().Unix())
	return err
}

// findPendingGroupInvite 查找某用户的待确认邀请，返回邀请记录 id（0 表示无）。
func findPendingGroupInvite(groupID, inviterID, targetUserID int64) int64 {
	var id int64
	err := db.QueryRow("SELECT id FROM group_invites WHERE group_id = ? AND inviter_id = ? AND target_user_id = ? AND status = 'pending' LIMIT 1",
		groupID, inviterID, targetUserID).Scan(&id)
	if err != nil {
		return 0
	}
	return id
}

// updateGroupInviteStatus 更新邀请状态：accepted / rejected。
func updateGroupInviteStatus(id int64, status string) error {
	_, err := db.Exec("UPDATE group_invites SET status = ? WHERE id = ?", status, id)
	return err
}

type JoinRequest struct {
	ID        int64  `json:"id"`
	GroupID   int64  `json:"group_id"`
	UserID    int64  `json:"user_id"`
	Username  string `json:"username"`
	Email     string `json:"email"`
	Reason    string `json:"reason"`
	Status    string `json:"status"`
	CreatedAt int64  `json:"created_at"`
}

func reviewJoinRequest(requestID int64, approve bool) error {
	var req struct {
		GroupID int64
		UserID  int64
	}
	err := db.QueryRow("SELECT group_id, user_id FROM group_join_requests WHERE id = ?", requestID).Scan(&req.GroupID, &req.UserID)
	if err != nil {
		return err
	}
	status := "rejected"
	if approve {
		status = "approved"
		_, err = db.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)", req.GroupID, req.UserID, time.Now().Unix())
		if err != nil {
			return err
		}
		sendGroupWelcomeMessage(req.GroupID, req.UserID)
		// 群通知：审批通过入群（按该群设定的通知方式与开关发送）
		notifyMemberJoin(req.GroupID, req.UserID, 0)
	}
	_, err = db.Exec("UPDATE group_join_requests SET status = ? WHERE id = ?", status, requestID)
	return err
}

func searchGroupsByDisplayID(query string) ([]Group, error) {
	like := "%" + query + "%"
	// 已开启「无法被搜索」(not_searchable=1) 的群不进入搜索结果；NOT NULL 兼容旧数据未写入该列的情况
	rows, err := db.Query("SELECT id, name, signature, announcement, creator_id, avatar, display_id, welcome_enabled, welcome_text, not_searchable, join_required, created_at, updated_at FROM groups WHERE (CAST(display_id AS TEXT) LIKE ? OR name LIKE ?) AND (not_searchable = 0 OR not_searchable IS NULL) LIMIT 50", like, like)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var groups []Group
	for rows.Next() {
		var g Group
		if err := rows.Scan(&g.ID, &g.Name, &g.Signature, &g.Announcement, &g.CreatorID, &g.Avatar, &g.DisplayID, &g.WelcomeEnabled, &g.WelcomeText, &g.NotSearchable, &g.JoinRequired, &g.CreatedAt, &g.UpdatedAt); err != nil {
			return nil, err
		}
		g.Announcements = parseGroupAnnouncements(g.Announcement)
		groups = append(groups, g)
	}
	return groups, nil
}

func updateGroupDisplayId(groupID, newDisplayId int64) error {
	var existing int64
	err := db.QueryRow("SELECT id FROM groups WHERE display_id = ? AND id != ?", newDisplayId, groupID).Scan(&existing)
	if err == nil {
		return fmt.Errorf("群ID %d 已被其他群使用", newDisplayId)
	}
	if err != sql.ErrNoRows {
		return err
	}
	_, err = db.Exec("UPDATE groups SET display_id = ?, updated_at = ? WHERE id = ?", newDisplayId, time.Now().Unix(), groupID)
	return err
}

// sendGroupWelcomeMessage 自动 @ 新成员
func sendGroupWelcomeMessage(groupID, userID int64) {
	var welcomeEnabled bool
	var welcomeText string
	err := db.QueryRow("SELECT welcome_enabled, welcome_text FROM groups WHERE id = ?", groupID).Scan(&welcomeEnabled, &welcomeText)
	if err != nil || !welcomeEnabled || welcomeText == "" {
		return
	}
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	if username == "" {
		username = fmt.Sprintf("用户%d", userID)
	}
	finalText := "@" + username + " " + welcomeText
	convID := -(1000 + groupID)
	msgID, err := saveMessage(convID, convID, finalText, 0, "", "", 0, "", "", 0, 0, 0)
	if err != nil {
		log.Printf("发送群欢迎消息失败: %v", err)
		return
	}
	PushToGroup(convID, msgID, convID, "", convID, finalText, time.Now().Unix(), "", "", 0, 0, "", "")
}

// ==================== 封禁管理 ====================

type BanRecord struct {
	UserID            int64  `json:"user_id"`
	Reason            string `json:"reason"`
	BannedAt          int64  `json:"banned_at"`
	ExpiresAt         int64  `json:"expires_at"`
	BannedBy          int64  `json:"banned_by"`
	UnbanNotify       int    `json:"unban_notify"`
	UnbanPopupMessage string `json:"unban_popup_message"`
}

func banUser(userID int64, reason string, durationSeconds int64, bannedBy int64, unbanPopupMessage string) error {
	now := time.Now().Unix()
	expiresAt := now + durationSeconds
	_, err := db.Exec("INSERT OR REPLACE INTO banned_users (user_id, reason, banned_at, expires_at, banned_by, unban_notify, unban_popup_message) VALUES (?, ?, ?, ?, ?, 0, ?)", userID, reason, now, expiresAt, bannedBy, unbanPopupMessage)
	return err
}

func unbanUser(userID int64) error {
	_, err := db.Exec("DELETE FROM banned_users WHERE user_id = ?", userID)
	return err
}

func setUnbanNotify(userID int64) error {
	_, err := db.Exec("UPDATE banned_users SET unban_notify = 1 WHERE user_id = ?", userID)
	return err
}
