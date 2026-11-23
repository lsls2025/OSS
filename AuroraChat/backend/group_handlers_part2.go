package main

import (
	"encoding/json"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

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

	// 官方群不可解散（否则全员强制群的会话记录与广播通道会被整体删掉）
	if isOfficialGroup(req.GroupID) {
		writeJSON(w, 403, "官方群不可解散", nil)
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

	// 解散前先通知全员：删完之后成员关系已经没了，谁在群里都查不到。
	// 不推这一条的话，客户端只能看到「群从列表里消失」，会被本地兜底逻辑判成「你已被踢出群聊」。
	var dissolveGroupName string
	db.QueryRow("SELECT COALESCE(name, '') FROM groups WHERE id = ?", req.GroupID).Scan(&dissolveGroupName)
	dissolveBy := groupMemberDisplayName(currentUserID)
	dissolveAt := time.Now().Unix()
	dissolveConvID := -(1000 + req.GroupID)
	if memberIDs, err := getGroupMembers(req.GroupID); err == nil {
		for _, uid := range memberIDs {
			PushToUser(uid, "group_dissolved", map[string]interface{}{
				"group_id":      req.GroupID,
				"conv_id":       dissolveConvID,
				"group_name":    dissolveGroupName,
				"operator_name": dissolveBy,
				"at":            dissolveAt,
			})
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

	// 官方群不可转让群主（群主固定为开发者，转让后锁定策略就失去意义）
	if isOfficialGroup(req.GroupID) {
		writeJSON(w, 403, "官方群不可转让群主", nil)
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
	// 群通知：群主转让（按该群设定的通知方式与开关发送）
	notifyOwnerTransfer(req.GroupID, req.NewOwnerID)
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
		// 取消管理员同时清掉单独给他的权限记录，避免下次再设为管理员时"继承"旧配置
		deleteGroupPermRow(req.GroupID, req.UserID)
	}
	log.Printf("群成员%s: groupID=%d, userID=%d", action, req.GroupID, req.UserID)
	// 群通知：管理员变更（按该群设定的通知方式与开关发送）
	notifyAdminChange(req.GroupID, currentUserID, req.UserID, req.Role == "admin")
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
		//    每个管理员可能同时挂着手机+桌面，全部取出后逐个推送
		targets := make(map[int64][]net.Conn, len(adminSet))
		if len(adminSet) > 0 {
			onlineUsersMu.RLock()
			for uid := range adminSet {
				if set := onlineUsers[uid]; len(set) > 0 {
					list := make([]net.Conn, 0, len(set))
					for c := range set {
						list = append(list, c)
					}
					targets[uid] = list
				}
			}
			onlineUsersMu.RUnlock()
		}

		// 3) 锁外推送；写失败只摘掉失败的那条连接，不影响同账号其它设备
		for uid, conns := range targets {
			for _, conn := range conns {
				if _, err := conn.Write(line); err != nil {
					log.Printf("[TCP] 推送入群申请通知失败 (user=%d): %v", uid, err)
					onlineUsersMu.Lock()
					if set, exists := onlineUsers[uid]; exists {
						delete(set, conn)
						if len(set) == 0 {
							delete(onlineUsers, uid)
						}
					}
					onlineUsersMu.Unlock()
					conn.Close()
				}
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
	// 群通知：成员入群（按该群设定的通知方式与开关发送）
	notifyMemberJoin(req.GroupID, currentUserID, 0)
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
		if msgID, err := saveMessage(currentUserID, req.UserID, cardText, 0, "", "", 0, "", "", 0, 0, 0); err == nil {
			PushToUser(req.UserID, "new_message", map[string]interface{}{
				"message_id":    msgID,
				"from_user_id":  currentUserID,
				"from_username": inviterName,
				"to_user_id":    req.UserID,
				"content":       cardText,
				"created_at":    time.Now().Unix(),
				"media_type":    "",
				"media_url":     "",
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
	// 群通知：成员入群（带邀请人/操作者）
	notifyMemberJoin(req.GroupID, req.UserID, currentUserID)
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
	joinedDirectly := false
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
		} else {
			joinedDirectly = true
		}
	} else {
		if err := updateGroupInviteStatus(inviteID, "rejected"); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
	}
	// 群通知：通过邀请直接入群的，按该群设定的通知方式发出入群提示
	if joinedDirectly {
		notifyMemberJoin(req.GroupID, currentUserID, req.InviterID)
	}
	// 结果通知：写入应答方 → 邀请方的私聊会话，双方都能看到；由客户端按 from_user_id 个性化文案
	statusFlag := 0
	if req.Approve {
		statusFlag = 1
	}
	groupName := getGroupName(req.GroupID)
	notice := "群聊邀请结果\n" + strconv.Itoa(statusFlag) + "\n" + groupName
	if msgID, err := saveMessage(currentUserID, req.InviterID, notice, 0, "", "", 0, "", "", 0, 0, 0); err == nil {
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
	// 官方群锁定策略：成员不可退出，也不可被移除（含开发者账号，统一在这里拦死，
	// 避免"开发者把自己踢出去→下次启动 ensureOfficialGroup 又拉回来"的诡异状态）
	if isOfficialGroup(req.GroupID) {
		writeJSON(w, 403, "官方群成员不可退出，也不可被移除", nil)
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
		// 细粒度权限：管理员需要被授予「移除成员」才能踢人
		if !isDeveloperUser(currentUserID) && !hasGroupPerm(req.GroupID, currentUserID, "kick") {
			writeJSON(w, 403, "没有移除成员的权限", nil)
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
				// 群通知：群主转让（按该群设定的通知方式与开关发送）
				notifyOwnerTransfer(req.GroupID, newOwnerID)
			} else {
				// 没有其他成员 → 解散群聊
				deleteGroup(req.GroupID)
				log.Printf("群 %d 已解散（最后一名成员退出）", req.GroupID)
			}
		} else {
			// 群通知：成员主动退群
			notifyMemberLeave(req.GroupID, currentUserID)
			log.Printf("用户 %d 已退出群 %d", currentUserID, req.GroupID)
		}
	} else {
		// 群通知：成员被移出群聊（群内广播，只会发给「仍在群里」的成员）
		// 开发者移除群主时，群会暂时没有 owner：按「群主退群」同样的规则顺位转让给管理员/其他成员，
		// 没有其他成员则直接解散，避免出现无人群可管理的僵尸群。
		if !isSelfLeave && leavingRole == "owner" {
			var newOwnerID int64
			var newOwnerName string
			rows, _ := db.Query(
				`SELECT gm.user_id, COALESCE(u.username, '')
				 FROM group_members gm JOIN users u ON gm.user_id = u.id
				 WHERE gm.group_id = ? AND gm.user_id != ?
				 ORDER BY CASE WHEN gm.role = 'admin' THEN 0 ELSE 1 END, gm.joined_at LIMIT 1`,
				req.GroupID, req.UserID,
			)
			if rows != nil && rows.Next() {
				rows.Scan(&newOwnerID, &newOwnerName)
				rows.Close()
			} else if rows != nil {
				rows.Close()
			}
			if newOwnerID > 0 {
				db.Exec("UPDATE group_members SET role = 'owner' WHERE group_id = ? AND user_id = ?", req.GroupID, newOwnerID)
				notifyOwnerTransfer(req.GroupID, newOwnerID)
				log.Printf("开发者移除群主后，群 %d 群主顺位转让给 %d(%s)", req.GroupID, newOwnerID, newOwnerName)
			} else {
				deleteGroup(req.GroupID)
				log.Printf("群 %d 已解散（开发者移除群主且无其他成员）", req.GroupID)
				writeJSON(w, 200, "已移除", nil)
				return
			}
		}
		notifyMemberKick(req.GroupID, currentUserID, req.UserID)
		log.Printf("管理员 %d 将用户 %d 移出群 %d", currentUserID, req.UserID, req.GroupID)

		// 定向通知被移出者本人。
		// 为什么必须单独推：removeFromGroup 已经先执行了，此刻他不再是群成员，
		// 上面那条群广播推不到他；客户端唯一的感知就是「群列表里少了一个群」，
		// 表现就是会话凭空消失、没有任何解释。
		// 推一条之后，客户端会落一条本地提示「X 将你踢出群聊」，并把该会话保留为只读历史。
		var groupName string
		_ = db.QueryRow("SELECT COALESCE(name, '') FROM groups WHERE id = ?", req.GroupID).Scan(&groupName)
		PushToUser(req.UserID, "group_kicked", map[string]interface{}{
			"group_id":      req.GroupID,
			"conv_id":       -(1000 + req.GroupID),
			"group_name":    groupName,
			"operator_name": groupMemberDisplayName(currentUserID),
			"at":            time.Now().Unix(),
		})
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
	// 细粒度权限：管理员需被授予「审批入群」
	if !isDeveloperUser(currentUserID) {
		var gid int64
		db.QueryRow("SELECT group_id FROM group_join_requests WHERE id = ?", req.RequestID).Scan(&gid)
		if gid > 0 && !hasGroupPerm(gid, currentUserID, "review_join") {
			writeJSON(w, 403, "没有审批入群申请的权限", nil)
			return
		}
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
	// 细粒度权限：忽略申请与审批同属「审批入群」
	if !isDeveloperUser(currentUserID) {
		var gid int64
		db.QueryRow("SELECT group_id FROM group_join_requests WHERE id = ?", req.RequestID).Scan(&gid)
		if gid > 0 && !hasGroupPerm(gid, currentUserID, "review_join") {
			writeJSON(w, 403, "没有审批入群申请的权限", nil)
			return
		}
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
	msgID, err := saveMessage(developerID, groupConvID, req.Content, 0, "", "", 0, "", "", 0, 0, 0)
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
