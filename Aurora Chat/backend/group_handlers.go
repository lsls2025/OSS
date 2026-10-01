package main

import (
	"encoding/json"
	"html"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// POST /api/groups/create — 创建群聊
func handleCreateGroup(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	// JWT 认证后的用户 ID（由 authMiddleware 注入）
	creatorID := getCurrentUserID(r)
	if creatorID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}

	var req struct {
		Name           string `json:"name"`
		Signature      string `json:"signature"`
		Announcement   string `json:"announcement"`
		WelcomeEnabled bool   `json:"welcome_enabled"`
		WelcomeText    string `json:"welcome_text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" {
		writeJSON(w, 400, "群聊名称不能为空", nil)
		return
	}
	req.Name = html.EscapeString(req.Name)
	req.Signature = html.EscapeString(req.Signature)
	req.Announcement = html.EscapeString(req.Announcement)
	req.WelcomeText = html.EscapeString(req.WelcomeText)

	groupID, displayID, err := createGroup(req.Name, req.Signature, req.Announcement, creatorID, req.WelcomeEnabled, req.WelcomeText)
	if err != nil {
		log.Printf("创建群聊失败: %v", err)
		writeJSON(w, 500, "创建失败", nil)
		return
	}

	// 如果开启了欢迎语，自动插入欢迎消息（创建者身份）
	if req.WelcomeEnabled && req.WelcomeText != "" {
		groupConvID := -(1000 + groupID)
		// 查找创建者昵称并前置 @用户名
		var creatorName string
		db.QueryRow("SELECT username FROM users WHERE id = ?", creatorID).Scan(&creatorName)
		if creatorName != "" {
			insertWelcomeMessage(groupConvID, "@"+creatorName+" "+req.WelcomeText)
		} else {
			insertWelcomeMessage(groupConvID, req.WelcomeText)
		}
	}

	log.Printf("群聊创建成功: ID=%d, DisplayID=%d, Name=%s, Creator=%d", groupID, displayID, req.Name, creatorID)
	writeJSON(w, 200, "创建成功", map[string]interface{}{
		"id":           groupID,
		"display_id":   displayID,
		"name":         req.Name,
		"signature":    req.Signature,
		"announcement": req.Announcement,
		"creator_id":   creatorID,
	})
}

// POST /api/groups/settings — 更新群聊设置
func handleUpdateGroupSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		GroupID        int64  `json:"group_id"`
		Name           string `json:"name"`
		Signature      string `json:"signature"`
		Announcement   string `json:"announcement"`
		WelcomeEnabled bool   `json:"welcome_enabled"`
		WelcomeText    string `json:"welcome_text"`
		NotSearchable  bool   `json:"not_searchable"`
		JoinRequired   bool   `json:"join_required"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	if req.GroupID == 0 {
		writeJSON(w, 400, "缺少群ID", nil)
		return
	}

	// 权限检查：仅群主和管理员可以修改群设置（开发者可操作任意群）
	currentUserID := getCurrentUserID(r)
	role, err := getMemberRole(req.GroupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || (role != "owner" && role != "admin")) {
		writeJSON(w, 403, "无权限操作", nil)
		return
	}

	if err := updateGroupSettings(req.GroupID, req.Name, req.Signature, req.Announcement, req.WelcomeEnabled, req.WelcomeText, req.NotSearchable, req.JoinRequired); err != nil {
		log.Printf("更新群设置失败: %v", err)
		writeJSON(w, 500, "更新失败", nil)
		return
	}

	log.Printf("群设置已更新: ID=%d", req.GroupID)
	writeJSON(w, 200, "更新成功", nil)
}

// GET /api/groups/info/{groupId} — 获取群聊详细信息（用内部 ID）
func handleGetGroupInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api/groups/info/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, 400, "缺少群ID", nil)
		return
	}
	groupID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeJSON(w, 400, "群ID格式错误", nil)
		return
	}

	group, err := getGroupByID(groupID)
	if err != nil {
		log.Printf("获取群信息失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if group == nil {
		writeJSON(w, 404, "群聊不存在", nil)
		return
	}

	// 获取当前请求用户在群中的角色
	currentUserID := getCurrentUserID(r)
	if currentUserID > 0 {
		role, err := getMemberRole(groupID, currentUserID)
		if err == nil {
			group.UserRole = role
		}
	}

	writeJSON(w, 200, "获取成功", group)
}

// GET /api/groups/members/{groupId} — 获取群成员列表
func handleGetGroupMembers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api/groups/members/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, 400, "缺少群ID", nil)
		return
	}
	groupID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeJSON(w, 400, "群ID格式错误", nil)
		return
	}

	members, err := getGroupMembersWithInfo(groupID)
	if err != nil {
		log.Printf("获取群成员失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if members == nil {
		members = []GroupMemberInfo{}
	}
	writeJSON(w, 200, "获取成功", members)
}

// GET /api/groups/search?q={id} — 按 display_id 搜索群聊
func handleSearchGroups(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		writeJSON(w, 200, "请输入群ID", []Group{})
		return
	}

	groups, err := searchGroupsByDisplayID(q)
	if err != nil {
		log.Printf("搜索群聊失败: %v", err)
		writeJSON(w, 500, "搜索失败", nil)
		return
	}

	log.Printf("[搜索群聊] 关键词=%q, 找到%d个匹配", q, len(groups))
	if groups == nil {
		groups = []Group{}
	}
	writeJSON(w, 200, "搜索成功", groups)
}

// GET /api/groups/{userId} — 获取用户加入的群聊列表
// 同时作为 /api/groups/ 下所有子路由的调度器（兼容 Go 1.21）
func handleGetGroups(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	method := r.Method
	log.Printf("[handleGetGroups] %s %s", method, path)

	// ── 按路径分发给对应的 handler ──
	// 注意：更具体的路径必须放在前面，避免被前缀匹配误拦截
	switch {
	case strings.HasPrefix(path, "/api/groups/create"):
		log.Printf("[dispatch] -> handleCreateGroup")
		handleCreateGroup(w, r)
	case strings.HasPrefix(path, "/api/groups/info/"):
		log.Printf("[dispatch] -> handleGetGroupInfo")
		handleGetGroupInfo(w, r)
	case strings.HasPrefix(path, "/api/groups/settings"):
		log.Printf("[dispatch] -> handleUpdateGroupSettings")
		handleUpdateGroupSettings(w, r)
	case strings.HasPrefix(path, "/api/groups/join-requests/"):
		log.Printf("[dispatch] -> handleGetJoinRequests")
		handleGetJoinRequests(w, r)
	case strings.HasPrefix(path, "/api/groups/join-request-count/"):
		log.Printf("[dispatch] -> handleJoinRequestCount")
		handleJoinRequestCount(w, r)
	case strings.HasPrefix(path, "/api/groups/join-request"):
		log.Printf("[dispatch] -> handleJoinRequest")
		handleJoinRequest(w, r)
	case strings.HasPrefix(path, "/api/groups/review-join"):
		log.Printf("[dispatch] -> handleReviewJoinRequest")
		handleReviewJoinRequest(w, r)
	case strings.HasPrefix(path, "/api/groups/dissolve"):
		log.Printf("[dispatch] -> handleDissolveGroup")
		handleDissolveGroup(w, r)
	case strings.HasPrefix(path, "/api/groups/transfer"):
		log.Printf("[dispatch] -> handleTransferOwner")
		handleTransferOwner(w, r)
	case strings.HasPrefix(path, "/api/groups/set-admin"):
		log.Printf("[dispatch] -> handleSetAdmin")
		handleSetAdmin(w, r)
	case strings.HasPrefix(path, "/api/groups/avatar/upload"):
		log.Printf("[dispatch] -> handleGroupAvatarUpload")
		handleGroupAvatarUpload(w, r)
	case strings.HasPrefix(path, "/api/groups/avatar/"):
		log.Printf("[dispatch] -> handleGroupAvatarGet")
		handleGroupAvatarGet(w, r)
	case strings.HasPrefix(path, "/api/groups/members/"):
		log.Printf("[dispatch] -> handleGetGroupMembers")
		handleGetGroupMembers(w, r)
	default:
		log.Printf("[handleGetGroups] 未匹配到子路由, method=%s, path=%s, 执行默认逻辑", method, path)
		if r.Method != "GET" {
			writeJSON(w, 405, "仅支持 GET 请求", nil)
			return
		}
		currentUserID := getCurrentUserID(r)

		trimmed := strings.TrimPrefix(path, "/api/groups/")
		parts := strings.Split(trimmed, "/")
		userID := currentUserID
		if len(parts) > 0 && parts[0] != "" {
			if id, err := strconv.ParseInt(parts[0], 10, 64); err == nil {
				userID = id
			}
		}
		if userID == 0 {
			writeJSON(w, 400, "缺少用户ID", nil)
			return
		}

		groups, err := getGroupsForUser(userID)
		if err != nil {
			log.Printf("获取群聊列表失败: %v", err)
			writeJSON(w, 500, "获取失败", nil)
			return
		}
		if groups == nil {
			groups = []Group{}
		}
		writeJSON(w, 200, "获取成功", groups)
	}
}

// POST /api/groups/dissolve — 解散群聊（仅群主）
func handleDissolveGroup(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}

	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		GroupID int64 `json:"group_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	// 仅群主或开发者可解散群聊
	if isDeveloperUser(currentUserID) {
		// 开发者有权限解散任何群
	} else {
		role, err := getMemberRole(req.GroupID, currentUserID)
		if err != nil || role != "owner" {
			writeJSON(w, 403, "仅群主可解散群聊", nil)
			return
		}
	}

	if err := deleteGroup(req.GroupID); err != nil {
		log.Printf("解散群聊失败: %v", err)
		writeJSON(w, 500, "解散失败", nil)
		return
	}

	log.Printf("群聊已解散: groupID=%d, by=%d", req.GroupID, currentUserID)
	writeJSON(w, 200, "群聊已解散", nil)
}

// POST /api/groups/transfer — 转让群主（仅群主）
func handleTransferOwner(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}

	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		GroupID    int64 `json:"group_id"`
		NewOwnerID int64 `json:"new_owner_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 || req.NewOwnerID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	role, err := getMemberRole(req.GroupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || role != "owner") {
		writeJSON(w, 403, "仅群主可转让群聊", nil)
		return
	}

	if err := transferGroupOwner(req.GroupID, req.NewOwnerID); err != nil {
		log.Printf("转让群主失败: %v", err)
		writeJSON(w, 500, "转让失败", nil)
		return
	}

	log.Printf("群主已转让: groupID=%d, newOwner=%d", req.GroupID, req.NewOwnerID)
	writeJSON(w, 200, "群主已转让", nil)
}

// POST /api/groups/set-admin — 设置/取消管理员（仅群主）
func handleSetAdmin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}

	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		GroupID int64  `json:"group_id"`
		UserID  int64  `json:"user_id"`
		Role    string `json:"role"` // "admin" 或 "member"
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 || req.UserID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	if req.Role != "admin" && req.Role != "member" {
		writeJSON(w, 400, "角色参数错误", nil)
		return
	}

	role, err := getMemberRole(req.GroupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || role != "owner") {
		writeJSON(w, 403, "仅群主可设置管理员", nil)
		return
	}

	// 不能设置群主自己
	if req.UserID == currentUserID {
		writeJSON(w, 400, "不能设置自己", nil)
		return
	}

	if err := setGroupRole(req.GroupID, req.UserID, req.Role); err != nil {
		log.Printf("设置管理员失败: %v", err)
		writeJSON(w, 500, "设置失败", nil)
		return
	}

	action := "设为管理员"
	if req.Role == "member" {
		action = "取消管理员"
	}
	log.Printf("群成员%s: groupID=%d, userID=%d", action, req.GroupID, req.UserID)
	writeJSON(w, 200, "操作成功", nil)
}

// POST /api/users/search — 搜索用户（仅返回当前用户的好友，用于拉人入群）
func handleSearchUsersForGroup(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}

	var req struct {
		Keyword string `json:"keyword"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}

	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}

	friends, err := getFriends(currentUserID)
	if err != nil {
		log.Printf("获取好友列表失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}

	if req.Keyword == "" {
		// 关键词为空则返回所有好友
		if friends == nil {
			friends = []User{}
		}
		writeJSON(w, 200, "获取成功", friends)
		return
	}

	// 关键词过滤好友
	keyword := strings.ToLower(req.Keyword)
	var filtered []User
	for _, u := range friends {
		if strings.Contains(strings.ToLower(u.Username), keyword) ||
			strings.Contains(strings.ToLower(u.Email), keyword) ||
			strconv.FormatInt(u.ID, 10) == keyword {
			filtered = append(filtered, u)
		}
	}
	if filtered == nil {
		filtered = []User{}
	}
	writeJSON(w, 200, "搜索成功", filtered)
}

// POST /api/groups/join-request — 直接加入群聊（无需审核）
func handleJoinRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		GroupID int64  `json:"group_id"`
		Reason  string `json:"reason"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	// 检查是否已被拉黑（不论 err 与否，查到黑名单就拦截）
	if blacklisted, _ := isGroupBlacklisted(req.GroupID, currentUserID); blacklisted {
		writeJSON(w, 403, "你已被该群拉黑", nil)
		return
	}
	// 检查是否已在群中
	_, err := getMemberRole(req.GroupID, currentUserID)
	if err == nil {
		writeJSON(w, 400, "你已在群聊中", nil)
		return
	}
	// 检查群是否开启入群审核
	var joinRequired int
	db.QueryRow("SELECT join_required FROM groups WHERE id = ?", req.GroupID).Scan(&joinRequired)
	if joinRequired == 1 {
		// 自动转为提交入群申请
		if err := submitJoinRequest(req.GroupID, currentUserID, req.Reason); err != nil {
			log.Printf("提交入群申请失败: %v", err)
			writeJSON(w, 500, "提交申请失败: "+err.Error(), nil)
			return
		}
		// TCP推送通知群管理员/群主
		convID := -(1000 + req.GroupID)
		payload := map[string]interface{}{
			"type": "join_request_update",
			"data": map[string]interface{}{"conv_id": convID},
		}
		line, _ := json.Marshal(payload)
		line = append(line, '\n')

		// 1) 锁外一次性预取群管理员/群主 ID 集合（DB 查询 O(1)，替代对每个在线用户逐人查 role 的 O(在线人数)）
		adminSet := make(map[int64]struct{})
		if rows, err := db.Query("SELECT user_id FROM group_members WHERE group_id = ? AND role IN ('owner','admin')", req.GroupID); err == nil {
			for rows.Next() {
				var uid int64
				if err := rows.Scan(&uid); err == nil {
					adminSet[uid] = struct{}{}
				}
			}
			rows.Close()
		} else {
			log.Printf("[入群申请] 预取群管理员失败 group=%d: %v", req.GroupID, err)
		}

		// 2) 短临界区快照在线管理员连接（锁内只做内存比较，无任何 I/O，不阻塞上下线/其它推送）
		targets := make(map[int64]net.Conn, len(adminSet))
		if len(adminSet) > 0 {
			onlineUsersMu.RLock()
			for uid := range adminSet {
				if conn, ok := onlineUsers[uid]; ok {
					targets[uid] = conn
				}
			}
			onlineUsersMu.RUnlock()
		}

		// 3) 锁外推送；写失败仅当仍是同一连接时清理，避免误删重连后的新连接
		for uid, conn := range targets {
			if _, err := conn.Write(line); err != nil {
				log.Printf("[TCP] 推送入群申请通知失败 (user=%d): %v", uid, err)
				onlineUsersMu.Lock()
				if current, exists := onlineUsers[uid]; exists && current == conn {
					delete(onlineUsers, uid)
					conn.Close()
				}
				onlineUsersMu.Unlock()
			}
		}
		log.Printf("用户 %d 提交群 %d 入群申请，等待审批（通知 %d 位管理员）", currentUserID, req.GroupID, len(targets))
		writeJSON(w, 200, "入群申请已提交，等待管理员审批", nil)
		return
	}
	if err := addToGroupDirectly(req.GroupID, currentUserID); err != nil {
		log.Printf("加入群聊失败: %v", err)
		writeJSON(w, 500, "加入失败: "+err.Error(), nil)
		return
	}
	log.Printf("用户 %d 直接加入群聊 %d", currentUserID, req.GroupID)
	writeJSON(w, 200, "已加入群聊", nil)
}

// POST /api/groups/add-member — 管理员拉人入群
func handleAddGroupMember(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		GroupID int64 `json:"group_id"`
		UserID  int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 || req.UserID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	// 权限检查：群主或管理员（开发者可操作任意群）
	role, err := getMemberRole(req.GroupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || (role != "owner" && role != "admin")) {
		writeJSON(w, 403, "无权限操作", nil)
		return
	}
	// 检查目标用户是否已被群拉黑
	if blacklisted, _ := isGroupBlacklisted(req.GroupID, req.UserID); blacklisted {
		writeJSON(w, 403, "该用户已被拉入群黑名单", nil)
		return
	}
	// 已在群聊则直接提示，避免重复邀请/重复加入
	if isGroupMember(req.GroupID, req.UserID) {
		writeJSON(w, 200, "已在群聊", nil)
		return
	}
	// 目标开启了"拉我时需经过我同意"（默认开启）→ 发送邀请卡片，不直接入群
	if getUserRequireConsent(req.UserID) {
		createGroupInvite(req.GroupID, currentUserID, req.UserID)
		groupName := getGroupName(req.GroupID)
		inviterName := getUserName(currentUserID)
		cardText := "邀请入群\n━━━━━━━━━━\n" + groupName + "\n" + inviterName +
			" 邀请你进入群聊\n━━━━━━━━━━\ninvite_group_id=" + strconv.FormatInt(req.GroupID, 10) +
			"\ninviter_id=" + strconv.FormatInt(currentUserID, 10)
		if msgID, err := saveMessage(currentUserID, req.UserID, cardText, 0, "", "", 0, "", ""); err == nil {
			PushToUser(req.UserID, "new_message", map[string]interface{}{
				"message_id":  msgID,
				"from_user_id": currentUserID,
				"from_username": inviterName,
				"to_user_id":  req.UserID,
				"content":     cardText,
				"created_at":  time.Now().Unix(),
				"media_type":  "",
				"media_url":   "",
			})
		}
		log.Printf("管理员 %d 向用户 %d 发送群 %d 入群邀请", currentUserID, req.UserID, req.GroupID)
		writeJSON(w, 200, "已发送入群邀请", nil)
		return
	}
	// 目标关闭开关：群需审核 → 走审核；否则直接加入
	if getGroupJoinRequired(req.GroupID) {
		if err := submitJoinRequest(req.GroupID, req.UserID, "被管理员拉入（群需审核）"); err != nil {
			log.Printf("拉人入群提交审核失败: %v", err)
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		writeJSON(w, 200, "已提交入群申请", nil)
		return
	}
	if err := addToGroupDirectly(req.GroupID, req.UserID); err != nil {
		log.Printf("拉人入群失败: %v", err)
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	log.Printf("管理员 %d 将用户 %d 拉入群 %d", currentUserID, req.UserID, req.GroupID)
	writeJSON(w, 200, "已加入群聊", nil)
}

// POST /api/groups/respond-invite — 应答"拉我时需经过我同意"的入群邀请
func handleRespondInvite(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		GroupID   int64 `json:"group_id"`
		InviterID int64 `json:"inviter_id"`
		Approve   bool  `json:"approve"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 || req.InviterID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	inviteID := findPendingGroupInvite(req.GroupID, req.InviterID, currentUserID)
	if inviteID == 0 {
		writeJSON(w, 403, "邀请不存在或已处理", nil)
		return
	}
	if req.Approve {
		if err := updateGroupInviteStatus(inviteID, "accepted"); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		if getGroupJoinRequired(req.GroupID) {
			if err := submitJoinRequest(req.GroupID, currentUserID, "同意入群邀请（群需审核）"); err != nil {
				writeJSON(w, 500, "操作失败", nil)
				return
			}
		} else if err := addToGroupDirectly(req.GroupID, currentUserID); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
	} else {
		if err := updateGroupInviteStatus(inviteID, "rejected"); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
	}
	// 结果通知：写入应答方 → 邀请方的私聊会话，双方都能看到；由客户端按 from_user_id 个性化文案
	statusFlag := 0
	if req.Approve {
		statusFlag = 1
	}
	groupName := getGroupName(req.GroupID)
	notice := "群聊邀请结果\n" + strconv.Itoa(statusFlag) + "\n" + groupName
	if msgID, err := saveMessage(currentUserID, req.InviterID, notice, 0, "", "", 0, "", ""); err == nil {
		fromName := getUserName(currentUserID)
		now := time.Now().Unix()
		data := map[string]interface{}{
			"message_id": msgID, "from_user_id": currentUserID, "from_username": fromName,
			"to_user_id": req.InviterID, "content": notice, "created_at": now,
			"media_type": "", "media_url": "",
		}
		PushToUser(req.InviterID, "new_message", data)
		PushToUser(currentUserID, "new_message", data)
	}
	writeJSON(w, 200, "已处理", nil)
}

// POST /api/groups/remove-member — 移除群成员
func handleRemoveGroupMember(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		GroupID   int64 `json:"group_id"`
		UserID    int64 `json:"user_id"`
		BlockUser bool  `json:"block_user"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 || req.UserID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	// 权限检查：自己退群不需要权限，管理员/群主可以移除他人；开发者可移除任意群任意成员
	isSelfLeave := currentUserID == req.UserID
	if !isSelfLeave {
		role, err := getMemberRole(req.GroupID, currentUserID)
		if !isDeveloperUser(currentUserID) && (err != nil || (role != "owner" && role != "admin")) {
			writeJSON(w, 403, "无权限操作", nil)
			return
		}
		// 不能移除群主（开发者可移除群主）
		if !isDeveloperUser(currentUserID) {
			targetRole, _ := getMemberRole(req.GroupID, req.UserID)
			if targetRole == "owner" {
				writeJSON(w, 403, "不能移除群主", nil)
				return
			}
		}
	}
	// 提前获取退群者角色（必须在移除成员之前查询，否则移出后查不到，导致群主退群无法解散群）
	leavingRole, _ := getMemberRole(req.GroupID, req.UserID)
	if err := removeFromGroup(req.GroupID, req.UserID); err != nil {
		log.Printf("移除群成员失败: %v", err)
		writeJSON(w, 500, "移除失败", nil)
		return
	}
	// 如果勾选了拉黑
	if req.BlockUser {
		if err := addGroupBlacklist(req.GroupID, req.UserID, currentUserID); err != nil {
			log.Printf("拉黑用户失败: %v", err)
		} else {
			log.Printf("用户 %d 已被群 %d 拉黑", req.UserID, req.GroupID)
		}
	}
	// 自己退群时发送系统消息，群主退群需要转移所有权
	if isSelfLeave {
		convID := -(1000 + req.GroupID)
		var username string
		db.QueryRow("SELECT username FROM users WHERE id = ?", currentUserID).Scan(&username)

		// 检查退群者是否为群主（角色已在移除成员前获取）
		if leavingRole == "owner" {
			// 转移群主：优先找管理员，没有则找其他普通成员
			var newOwnerID int64
			var newOwnerName string
			rows, _ := db.Query(
				`SELECT gm.user_id, COALESCE(u.username, '')
				 FROM group_members gm JOIN users u ON gm.user_id = u.id
				 WHERE gm.group_id = ? AND gm.user_id != ? AND gm.role != 'owner'
				 ORDER BY CASE WHEN gm.role = 'admin' THEN 0 ELSE 1 END, gm.joined_at LIMIT 1`,
				req.GroupID, req.UserID,
			)
			if rows != nil && rows.Next() {
				rows.Scan(&newOwnerID, &newOwnerName)
				if rows != nil {
					rows.Close()
				}
			} else if rows != nil {
				rows.Close()
			}
			if newOwnerID > 0 {
				// 转交群主
				db.Exec("UPDATE group_members SET role = 'owner' WHERE group_id = ? AND user_id = ?", req.GroupID, newOwnerID)
				content := "群主已退出群聊，群主已经重新分配给 " + newOwnerName
				msgID, saveErr := saveMessage(convID, convID, content, 0, "", "", 0, "", "")
				if saveErr == nil {
					PushToGroup(convID, msgID, convID, "", convID, content, time.Now().Unix(), "", "", 0, 0, "", "")
				}
			} else {
				// 没有其他成员 → 解散群聊
				deleteGroup(req.GroupID)
				log.Printf("群 %d 已解散（最后一名成员退出）", req.GroupID)
			}
		} else {
			content := username + " 已退出群"
			msgID, saveErr := saveMessage(convID, convID, content, 0, "", "", 0, "", "")
			if saveErr == nil {
				PushToGroup(convID, msgID, convID, "", convID, content, time.Now().Unix(), "", "", 0, 0, "", "")
			}
			log.Printf("用户 %d 已退出群 %d", currentUserID, req.GroupID)
		}
	} else {
		log.Printf("管理员 %d 将用户 %d 移出群 %d", currentUserID, req.UserID, req.GroupID)
	}
	writeJSON(w, 200, "已移除", nil)
}

// GET /api/groups/join-requests/{groupId} — 获取待审批申请
func handleGetJoinRequests(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/api/groups/join-requests/")
	groupID, err := strconv.ParseInt(strings.Split(path, "/")[0], 10, 64)
	if err != nil {
		writeJSON(w, 400, "群ID格式错误", nil)
		return
	}
	role, err := getMemberRole(groupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || (role != "owner" && role != "admin")) {
		writeJSON(w, 403, "无权限查看", nil)
		return
	}
	requests, err := getGroupJoinRequests(groupID)
	if err != nil {
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if requests == nil {
		requests = []JoinRequest{}
	}
	writeJSON(w, 200, "获取成功", requests)
}

// POST /api/groups/review-join — 审批加群申请
func handleReviewJoinRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		RequestID int64 `json:"request_id"`
		Approve   bool  `json:"approve"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RequestID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	if err := reviewJoinRequest(req.RequestID, req.Approve); err != nil {
		log.Printf("审批失败: %v", err)
		errMsg := err.Error()
		if strings.Contains(errMsg, "黑名单") {
			writeJSON(w, 403, errMsg, nil)
		} else {
			writeJSON(w, 500, "操作失败", nil)
		}
		return
	}
	action := "已拒绝"
	if req.Approve {
		action = "已同意"
	}
	writeJSON(w, 200, action, nil)
}

// POST /api/groups/ignore-join — 忽略加群申请（不计入横幅，但列表仍可见）
func handleIgnoreJoinRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		RequestID int64 `json:"request_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.RequestID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	if err := ignoreJoinRequest(req.RequestID); err != nil {
		log.Printf("忽略申请失败: %v", err)
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	writeJSON(w, 200, "已忽略", nil)
}

// GET /api/groups/join-request-count/{groupId} — 获取待审批申请数量
func handleJoinRequestCount(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/api/groups/join-request-count/")
	groupID, err := strconv.ParseInt(strings.Split(path, "/")[0], 10, 64)
	if err != nil {
		writeJSON(w, 400, "群ID格式错误", nil)
		return
	}
	role, err := getMemberRole(groupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || (role != "owner" && role != "admin")) {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	var count int
	db.QueryRow("SELECT COUNT(*) FROM group_join_requests WHERE group_id = ? AND status = 'pending'", groupID).Scan(&count)
	writeJSON(w, 200, "获取成功", map[string]int{"count": count})
}

// ==================== 管理员群组管理 ====================

// GET /api/admin/groups — 获取所有群聊列表（开发者管理，分页+搜索）
func handleAdminListGroups(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "groups.view") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	keyword := r.URL.Query().Get("keyword")
	if page < 1 {
		page = 1
	}
	if limit < 1 || limit > 100 {
		limit = 15
	}
	groups, total, err := listAllGroups(page, limit, keyword)
	if err != nil {
		log.Printf("[Admin] 获取群聊列表失败: %v", err)
		writeJSON(w, 500, "获取群聊列表失败", nil)
		return
	}
	if groups == nil {
		groups = []Group{}
	}
	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"groups": groups,
		"total":  total,
		"page":   page,
		"limit":  limit,
	})
}

// POST /api/admin/groups/update-display-id — 修改群 display_id（开发者管理）
func handleAdminUpdateGroupDisplayId(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "groups.edit") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		GroupID      int64 `json:"group_id"`
		NewDisplayID int64 `json:"new_display_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.GroupID <= 0 || req.NewDisplayID <= 0 {
		writeJSON(w, 400, "参数无效", nil)
		return
	}
	if err := updateGroupDisplayId(req.GroupID, req.NewDisplayID); err != nil {
		log.Printf("[Admin] 修改群 display_id 失败: group=%d new_id=%d err=%v", req.GroupID, req.NewDisplayID, err)
		writeJSON(w, 500, err.Error(), nil)
		return
	}
	log.Printf("[Admin] 群 display_id 已修改: group=%d new_display_id=%d by=%s", req.GroupID, req.NewDisplayID, getCurrentEmailFromHeader(r))
	writeJSON(w, 200, "修改成功", nil)
}

// POST /api/admin/groups/send-message — 开发者以群聊身份发送消息
func handleAdminDeveloperSendMessage(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	developerID := getCurrentUserID(r)
	if !checkDevOrPerm(r, "groups.msg") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		GroupID int64  `json:"group_id"`
		Content string `json:"content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.GroupID <= 0 || strings.TrimSpace(req.Content) == "" {
		writeJSON(w, 400, "参数无效", nil)
		return
	}
	groupConvID := -(1000 + req.GroupID)
	msgID, err := saveMessage(developerID, groupConvID, req.Content, 0, "", "", 0, "", "")
	if err != nil {
		log.Printf("[Admin] 开发者发送群消息失败: group=%d err=%v", req.GroupID, err)
		writeJSON(w, 500, "发送失败", nil)
		return
	}
	// 推送消息给群内所有在线成员
	PushToGroup(groupConvID, msgID, developerID, "", groupConvID, req.Content, time.Now().Unix(), "", "", 0, 0, "", "")
	log.Printf("[Admin] 开发者 %s 向群 %d 发送消息: %s", getCurrentEmailFromHeader(r), req.GroupID, req.Content)
	writeJSON(w, 200, "发送成功", map[string]interface{}{"message_id": msgID})
}

// GET /api/admin/groups/messages/{groupId} — 获取群聊消息（开发者管理，分页）
func handleAdminGetGroupMessages(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "groups.msg") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	// 从路径提取 groupId: /api/admin/groups/messages/{groupId}
	path := strings.TrimPrefix(r.URL.Path, "/api/admin/groups/messages/")
	path = strings.TrimSuffix(path, "/")
	groupID, err := strconv.ParseInt(path, 10, 64)
	if err != nil || groupID <= 0 {
		writeJSON(w, 400, "群ID格式错误", nil)
		return
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if page < 1 {
		page = 1
	}
	if limit < 1 || limit > 100 {
		limit = 30
	}
	offset := (page - 1) * limit
	groupConvID := -(1000 + groupID)
	msgs, err := getGroupMessages(groupConvID, limit, offset)
	if err != nil {
		log.Printf("[Admin] 获取群消息失败: group=%d err=%v", groupID, err)
		writeJSON(w, 500, "获取消息失败", nil)
		return
	}
	if msgs == nil {
		msgs = []Message{}
	}
	writeJSON(w, 200, "获取成功", msgs)
}
