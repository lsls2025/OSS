package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"time"
)

func handleAdminMuteUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID, ok := checkAdminPermission(r, "users.mute")
	if !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		UserID   int64 `json:"user_id"`
		Duration int64 `json:"duration"`
		MuteType int   `json:"mute_type"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 user_id", nil)
		return
	}
	// 禁言类型：1=社区禁言 2=对话禁言 3=全部禁言 4=短视频禁言
	if req.MuteType < 1 || req.MuteType > 4 {
		req.MuteType = 3
	}
	if req.Duration <= 0 {
		req.Duration = 100 * 365 * 24 * 60 * 60
	}

	if req.UserID == currentUserID {
		writeJSON(w, 403, "无法禁言自己的账号", nil)
		return
	}

	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}

	// 保护管理员和开发者：不可被禁言
	if isDeveloperUserObj(targetUser) {
		writeJSON(w, 403, "无法禁言开发者账号", nil)
		return
	}
	isTargetAdmin, _ := isPlatformAdmin(req.UserID)
	if isTargetAdmin && !checkDevPermission(r) {
		writeJSON(w, 403, "无法禁言管理员账号，请先取消其管理员权限", nil)
		return
	}

	if err := muteUser(req.UserID, req.Duration, currentUserID, req.MuteType); err != nil {
		log.Printf("[Admin] 禁言用户 %d 失败: %v", req.UserID, err)
		writeJSON(w, 500, "禁言失败", nil)
		return
	}

	AddMuteRecord(req.UserID, time.Now().Unix()+req.Duration)

	PushToUser(req.UserID, "account_muted", map[string]interface{}{
		"duration":   req.Duration,
		"expires_at": time.Now().Unix() + req.Duration,
		"mute_type":  req.MuteType,
	})

	DisconnectUser(req.UserID)

	// 记录操作日志
	adminUsername := getCurrentEmailFromHeader(r)
	muteTypeStr := "全部"
	switch req.MuteType {
	case 1:
		muteTypeStr = "社区"
	case 2:
		muteTypeStr = "对话"
	case 4:
		muteTypeStr = "短视频"
	}
	details, _ := json.Marshal(map[string]interface{}{
		"mute_type":       req.MuteType,
		"mute_type_label": muteTypeStr,
		"duration":        req.Duration,
		"expires_at":      time.Now().Unix() + req.Duration,
	})
	addAdminOperationLog(currentUserID, adminUsername, "mute", req.UserID, targetUser.Username, string(details))

	log.Printf("[Admin] 用户 %d (%s) 已被 %s 禁言 %d 秒，类型: %s",
		req.UserID, targetUser.Email, adminUsername, req.Duration, muteTypeStr)
	writeJSON(w, 200, "已禁言用户 "+targetUser.Email, nil)
}

// ==================== 管理员操作：解除禁言 ====================

func handleAdminUnmuteUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID, ok := checkAdminPermission(r, "users.mute")
	if !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 user_id", nil)
		return
	}
	targetUser, _ := findUserByID(req.UserID)
	targetUsername := ""
	if targetUser != nil {
		targetUsername = targetUser.Username
	}
	if err := unmuteUser(req.UserID); err != nil {
		log.Printf("[Admin] 解除禁言用户 %d 失败: %v", req.UserID, err)
		writeJSON(w, 500, "解除禁言失败", nil)
		return
	}
	RemoveMuteRecord(req.UserID)
	PushToUser(req.UserID, "account_unmuted", map[string]interface{}{
		"message": "您的账号已被解除禁言",
	})

	// 记录操作日志
	adminUsername := getCurrentEmailFromHeader(r)
	details, _ := json.Marshal(map[string]interface{}{
		"action": "unmute",
	})
	addAdminOperationLog(currentUserID, adminUsername, "unmute", req.UserID, targetUsername, string(details))

	log.Printf("[Admin] 用户 %d 已被 %s 解除禁言", req.UserID, adminUsername)
	writeJSON(w, 200, "已解除禁言", nil)
}

// ==================== 管理员查询用户禁言状态 ====================

func handleAdminCheckMuteStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	_, ok := checkAdminPermission(r, "users.mute")
	if !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	userIDStr := r.URL.Query().Get("user_id")
	if userIDStr == "" {
		writeJSON(w, 400, "参数错误：需要 user_id", nil)
		return
	}
	var userID int64
	fmt.Sscanf(userIDStr, "%d", &userID)
	if userID <= 0 {
		writeJSON(w, 400, "参数错误：无效的 user_id", nil)
		return
	}
	mute, err := getMuteStatus(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if mute != nil {
		writeJSON(w, 200, "查询成功", mute)
	} else {
		writeJSON(w, 200, "未禁言", map[string]interface{}{})
	}
}

// ==================== 群内禁言 ====================

// POST /api/group/mute — 群内禁言成员（仅群主/管理员）
func handleGroupMuteUser(w http.ResponseWriter, r *http.Request) {
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
		GroupID  int64 `json:"group_id"`
		UserID   int64 `json:"user_id"`
		Duration int64 `json:"duration"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID <= 0 || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.Duration <= 0 {
		req.Duration = 3600
	}
	// 验证当前用户是群主或管理员
	var role string
	db.QueryRow("SELECT role FROM group_members WHERE group_id = ? AND user_id = ?", req.GroupID, currentUserID).Scan(&role)
	if role != "owner" && role != "admin" {
		// 检查是否开发者（可绕过）
		if !isDeveloperUser(currentUserID) {
			writeJSON(w, 403, "仅群主和管理员可禁言成员", nil)
			return
		}
	}
	// 细粒度权限：管理员需要被授予「禁言成员」
	if !isDeveloperUser(currentUserID) && !hasGroupPerm(req.GroupID, currentUserID, "mute") {
		writeJSON(w, 403, "没有禁言成员的权限", nil)
		return
	}
	// 验证目标用户在群内
	var memberCount int
	db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ? AND user_id = ?", req.GroupID, req.UserID).Scan(&memberCount)
	if memberCount == 0 {
		writeJSON(w, 404, "该用户不在群中", nil)
		return
	}
	if err := groupMuteUser(req.GroupID, req.UserID, req.Duration, currentUserID); err != nil {
		log.Printf("[群禁言] 禁言失败: group=%d, user=%d, err=%v", req.GroupID, req.UserID, err)
		writeJSON(w, 500, "禁言失败", nil)
		return
	}
	log.Printf("[群禁言] 用户 %d 在群 %d 被禁言 %d 秒", req.UserID, req.GroupID, req.Duration)

	// 群通知：按该群设定的通知方式（群身份 / 系统广播）与逐项开关发送
	durationStr := formatMuteDuration(req.Duration)
	opName := groupMemberDisplayName(currentUserID)
	targetName := groupMemberDisplayName(req.UserID)
	sendGroupNotice(req.GroupID, notifyKeyMute,
		"@"+opName+" 将 @"+targetName+" 禁言了"+durationStr,
		opName+" 将 "+targetName+" 禁言了"+durationStr)

	writeJSON(w, 200, "已禁言", nil)
}

// POST /api/group/unmute — 解除群内禁言（仅群主/管理员）
func handleGroupUnmuteUser(w http.ResponseWriter, r *http.Request) {
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
		GroupID int64 `json:"group_id"`
		UserID  int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID <= 0 || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var role string
	db.QueryRow("SELECT role FROM group_members WHERE group_id = ? AND user_id = ?", req.GroupID, currentUserID).Scan(&role)
	if role != "owner" && role != "admin" {
		if !isDeveloperUser(currentUserID) {
			writeJSON(w, 403, "仅群主和管理员可解除禁言", nil)
			return
		}
	}
	// 细粒度权限：解禁与禁言同属「禁言成员」权限
	if !isDeveloperUser(currentUserID) && !hasGroupPerm(req.GroupID, currentUserID, "mute") {
		writeJSON(w, 403, "没有解禁成员的权限", nil)
		return
	}
	if err := groupUnmuteUser(req.GroupID, req.UserID); err != nil {
		log.Printf("[群禁言] 解除禁言失败: group=%d, user=%d, err=%v", req.GroupID, req.UserID, err)
		writeJSON(w, 500, "解除禁言失败", nil)
		return
	}
	log.Printf("[群禁言] 用户 %d 在群 %d 已解除禁言", req.UserID, req.GroupID)

	// 群通知：按该群设定的通知方式与开关发送
	opName := groupMemberDisplayName(currentUserID)
	targetName := groupMemberDisplayName(req.UserID)
	sendGroupNotice(req.GroupID, notifyKeyUnmute,
		"@"+opName+" 已将 @"+targetName+" 解除禁言",
		opName+" 已将 "+targetName+" 解除禁言")

	writeJSON(w, 200, "已解除禁言", nil)
}

// GET /api/group/mute-members?group_id= — 批量获取群内被禁言成员列表
func handleGroupMuteMembers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	groupIDStr := r.URL.Query().Get("group_id")
	if groupIDStr == "" {
		writeJSON(w, 400, "缺少 group_id 参数", nil)
		return
	}
	var groupID int64
	fmt.Sscanf(groupIDStr, "%d", &groupID)
	if groupID <= 0 {
		writeJSON(w, 400, "无效的群ID", nil)
		return
	}
	// 验证用户是群成员
	_, err := getMemberRole(groupID, currentUserID)
	if err != nil {
		writeJSON(w, 403, "你不是该群成员", nil)
		return
	}
	members, err := getGroupMuteMembers(groupID)
	if err != nil {
		log.Printf("[群禁言] 获取禁言成员列表失败: group=%d, err=%v", groupID, err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if members == nil {
		members = []map[string]interface{}{}
	}
	writeJSON(w, 200, "获取成功", members)
}

// GET /api/group/mute-status?group_id=&user_id= — 查询用户在群内的禁言状态
func handleGroupCheckMuteStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	groupIDStr := r.URL.Query().Get("group_id")
	userIDStr := r.URL.Query().Get("user_id")
	if groupIDStr == "" || userIDStr == "" {
		writeJSON(w, 400, "参数错误：需要 group_id 和 user_id", nil)
		return
	}
	var groupID, targetUserID int64
	fmt.Sscanf(groupIDStr, "%d", &groupID)
	fmt.Sscanf(userIDStr, "%d", &targetUserID)
	if groupID <= 0 || targetUserID <= 0 {
		writeJSON(w, 400, "参数错误：无效的 ID", nil)
		return
	}
	isMuted, expiresAt := isGroupMuted(groupID, targetUserID)
	if isMuted {
		writeJSON(w, 200, "查询成功", map[string]interface{}{
			"is_muted":   true,
			"expires_at": expiresAt,
		})
	} else {
		writeJSON(w, 200, "未禁言", map[string]interface{}{
			"is_muted": false,
		})
	}
}

// ==================== 平台管理员授权/撤销/列表 ====================

// POST /api/admin/platform-admin/grant — 授权平台管理员（仅开发者）
func handleGrantPlatformAdmin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.grant") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if req.UserID == currentUserID {
		writeJSON(w, 400, "不能授权自己", nil)
		return
	}
	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	if err := grantPlatformAdmin(req.UserID, currentUserID); err != nil {
		log.Printf("[Admin] 授权平台管理员失败: %v", err)
		writeJSON(w, 500, "授权失败", nil)
		return
	}
	log.Printf("[Admin] 用户 %d (%s) 被授权为平台管理员", req.UserID, targetUser.Email)
	writeJSON(w, 200, "已授权 "+targetUser.Username+" 为平台管理员", nil)
}

// POST /api/admin/platform-admin/revoke — 撤销平台管理员（仅开发者）
func handleRevokePlatformAdmin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if err := revokePlatformAdmin(req.UserID); err != nil {
		log.Printf("[Admin] 撤销平台管理员失败: %v", err)
		writeJSON(w, 500, "撤销失败", nil)
		return
	}
	log.Printf("[Admin] 用户 %d 的平台管理员权限已被撤销", req.UserID)
	writeJSON(w, 200, "已撤销平台管理员", nil)
}

// GET /api/admin/platform-admin/list — 获取所有平台管理员列表（仅开发者）
func handleListPlatformAdmins(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.grant") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	admins, err := getAllPlatformAdmins()
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	// 补充用户信息
	type AdminInfo struct {
		UserID    int64  `json:"user_id"`
		Username  string `json:"username"`
		Email     string `json:"email"`
		GrantedBy int64  `json:"granted_by"`
		GrantedAt int64  `json:"granted_at"`
	}
	var result []AdminInfo
	for _, a := range admins {
		u, _ := findUserByID(a.UserID)
		username := ""
		email := ""
		if u != nil {
			username = u.Username
			email = u.Email
		}
		result = append(result, AdminInfo{
			UserID: a.UserID, Username: username, Email: email,
			GrantedBy: a.GrantedBy, GrantedAt: a.GrantedAt,
		})
	}
	writeJSON(w, 200, "查询成功", result)
}

// POST /api/admin/permissions/revoke-admin — 撤销管理员的全部权限（需要 admin.revoke 权限）
func handleRevokeAdminPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	// 开发者直接放行；否则检查 admin.revoke 权限
	if !checkDevPermission(r) && !hasPermission(currentUserID, "admin.revoke") {
		writeJSON(w, 403, "无权执行此操作", nil)
		return
	}
	var req struct {
		UserID int64 `json:"user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.UserID == currentUserID {
		writeJSON(w, 400, "不能撤销自己的权限", nil)
		return
	}
	targetUser, err := findUserByID(req.UserID)
	if err != nil || targetUser == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	// 不能撤销开发者
	if isDeveloperUserObj(targetUser) {
		writeJSON(w, 403, "不能撤销开发者的权限", nil)
		return
	}
	// 撤销平台管理员身份
	if err := revokePlatformAdmin(req.UserID); err != nil {
		writeJSON(w, 500, "撤销管理员身份失败", nil)
		return
	}
	// 同时清除该用户的所有细粒度权限
	if err := saveUserPermissions(req.UserID, []string{}); err != nil {
		log.Printf("[Admin] 清除用户 %d 权限时出错: %v", req.UserID, err)
		// 不阻断主流程
	}
	// 记录操作日志
	adminID := getCurrentUserID(r)
	adminUsername := ""
	if u, _ := findUserByID(adminID); u != nil {
		adminUsername = u.Username
	}
	addAdminOperationLog(adminID, adminUsername, "revoke_admin", req.UserID, targetUser.Username, `{"target_email":"`+targetUser.Email+`"}`)
	writeJSON(w, 200, "已撤销该管理员的全部权限", nil)
}

// POST /api/admin/revoke-all — 一键收回除开发者外所有用户的平台管理员与细粒度权限（仅开发者）
func handleRevokeAllAdminPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	adminCount, permCount, err := revokeAllAdminPermissions(currentUserID)
	if err != nil {
		log.Printf("[Admin] 一键收回全部权限失败: %v", err)
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	// 记录操作日志
	adminUsername := ""
	if u, _ := findUserByID(currentUserID); u != nil {
		adminUsername = u.Username
	}
	addAdminOperationLog(currentUserID, adminUsername, "revoke_all", 0, "全部用户", `{"admins":`+fmt.Sprintf("%d", adminCount)+`,"permissions":`+fmt.Sprintf("%d", permCount)+`}`)
	writeJSON(w, 200, "已收回除开发者外所有用户的授权", map[string]int{"revoked_admins": adminCount, "revoked_permissions": permCount})
}

// GET /api/admin/permissions?user_id=xxx — 获取用户细粒度权限（仅开发者）
func handleGetUserPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	userIDStr := r.URL.Query().Get("user_id")
	requestedUserID, err := strconv.ParseInt(userIDStr, 10, 64)
	if err != nil || requestedUserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	keys, err := getUserPermissions(requestedUserID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "ok", keys)
}

// GET /api/admin/my-permissions — 获取当前用户自己的权限列表（平台管理员可用）
func handleGetMyPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	keys, err := getUserPermissions(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "ok", keys)
}

// POST /api/admin/permissions/save — 保存用户细粒度权限（仅开发者）
func handleSaveUserPermissions(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		UserID int64    `json:"user_id"`
		Perms  []string `json:"perms"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if err := saveUserPermissions(req.UserID, req.Perms); err != nil {
		writeJSON(w, 500, "保存权限失败", nil)
		return
	}
	// 关键修复：保存权限的同时，确保该用户是平台管理员
	// 否则客户端 isPlatformAdmin() 返回 false，看不到开发者管理入口
	if len(req.Perms) > 0 {
		isAdmin, err := isPlatformAdmin(req.UserID)
		if err != nil || !isAdmin {
			currentUserID := getCurrentUserID(r)
			if err := grantPlatformAdmin(req.UserID, currentUserID); err != nil {
				log.Printf("[Admin] 保存权限时自动授权平台管理员失败: %v", err)
				// 不阻断主流程，权限已保存
			}
		}
	}
	writeJSON(w, 200, "已保存", nil)
}

// GET /api/admin/admin-logs — 获取管理员操作日志（仅开发者）
func handleGetAdminLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.logs") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")
	adminIDStr := r.URL.Query().Get("admin_user_id")
	limit := 50
	offset := 0
	if n, err := fmt.Sscanf(limitStr, "%d", &limit); err != nil || n == 0 {
		limit = 50
	}
	if n, err := fmt.Sscanf(offsetStr, "%d", &offset); err != nil || n == 0 {
		offset = 0
	}

	var logs []AdminOperationLog
	var total int
	var err error
	if adminIDStr != "" {
		var adminID int64
		fmt.Sscanf(adminIDStr, "%d", &adminID)
		logs, total, err = getAdminOperationLogsByAdmin(adminID, limit, offset)
	} else {
		logs, total, err = getAdminOperationLogs(limit, offset)
	}
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if logs == nil {
		logs = []AdminOperationLog{}
	}
	writeJSON(w, 200, "查询成功", map[string]interface{}{
		"logs":   logs,
		"total":  total,
		"limit":  limit,
		"offset": offset,
	})
}

// GET /api/admin/stats/today-active-users — 今日 DAU（去重登录用户数）
func handleAdminTodayActiveUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.stats") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	active, total, rate, err := getTodayActiveUsers()
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "查询成功", map[string]interface{}{
		"date":              time.Now().Format("2006-01-02"),
		"active_user_count": active,
		"total_registered":  total,
		"active_rate":       rate,
	})
}

// GET /api/admin/stats/total-login-count — 今日登录总次数（不去重）
func handleAdminTodayLoginCount(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.stats") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	total, unique, err := getTodayLoginCount()
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	var avg float64
	if unique > 0 {
		avg = float64(total) / float64(unique)
	}
	writeJSON(w, 200, "查询成功", map[string]interface{}{
		"date":                time.Now().Format("2006-01-02"),
		"total_login_count":   total,
		"unique_users":        unique,
		"avg_logins_per_user": avg,
	})
}

// GET /api/admin/stats/today-active-users-list — 今日活跃用户列表（去重）
func handleAdminTodayActiveUsersList(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.stats") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	list, err := getTodayActiveUsersList()
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	writeJSON(w, 200, "查询成功", map[string]interface{}{
		"date":  time.Now().Format("2006-01-02"),
		"count": len(list),
		"list":  list,
	})
}

// GET /api/admin/stats/user-login-stats?query=xxx — 按用户名或 ID 查用户详细登录统计
func handleAdminUserLoginStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.stats") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	query := r.URL.Query().Get("query")
	if query == "" {
		writeJSON(w, 400, "请传入 query 参数（用户名或 ID）", nil)
		return
	}
	userID, err := getUserIDByNameOrID(query)
	if err != nil || userID == 0 {
		writeJSON(w, 404, "未找到该用户", nil)
		return
	}
	stats := getUserLoginStats(userID)
	writeJSON(w, 200, "查询成功", stats)
}

// POST /api/admin/admin-logs/clear — 清空所有管理员操作日志（仅开发者）
func handleClearAdminLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	if _, err := db.Exec("DELETE FROM admin_operation_logs"); err != nil {
		log.Printf("[Admin] 清空操作日志失败: %v", err)
		writeJSON(w, 500, "清空失败", nil)
		return
	}
	log.Printf("[Admin] 开发者清空了所有操作日志")
	writeJSON(w, 200, "已清空所有操作日志", nil)
}

// POST /api/admin/admin-logs/revert — 撤回管理员操作（仅开发者）
func handleRevertAdminOperation(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if !checkDevOrPerm(r, "admin.logs") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		LogID int64 `json:"log_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.LogID <= 0 {
		writeJSON(w, 400, "参数错误：需要有效的 log_id", nil)
		return
	}

	// 查询日志记录
	var opType string
	var targetUserID int64
	err := db.QueryRow("SELECT operation_type, target_user_id FROM admin_operation_logs WHERE id = ?", req.LogID).
		Scan(&opType, &targetUserID)
	if err != nil {
		writeJSON(w, 404, "日志记录不存在", nil)
		return
	}
	if targetUserID <= 0 {
		writeJSON(w, 400, "日志中未记录目标用户", nil)
		return
	}

	// 根据操作类型执行反向操作
	switch opType {
	case "ban":
		// 撤销封禁 = 解封
		if err := unbanUser(targetUserID); err != nil {
			writeJSON(w, 500, "解封失败", nil)
			return
		}
		RemoveBanRecord(targetUserID)
		PushToUser(targetUserID, "account_unbanned", map[string]interface{}{
			"message": "管理员已撤销对你的封禁",
		})
		// 删除原日志记录（撤回后直接消失）
		db.Exec("DELETE FROM admin_operation_logs WHERE id = ?", req.LogID)
		log.Printf("[Admin] 开发者撤回封禁: 用户 %d, 来源日志ID=%d（已删除原日志）", targetUserID, req.LogID)
		writeJSON(w, 200, "已撤销封禁，用户已恢复使用", nil)

	case "mute":
		// 撤销禁言 = 解除禁言
		if err := unmuteUser(targetUserID); err != nil {
			writeJSON(w, 500, "解除禁言失败", nil)
			return
		}
		RemoveMuteRecord(targetUserID)
		PushToUser(targetUserID, "account_unmuted", map[string]interface{}{
			"message": "管理员已撤销对你的禁言",
		})
		// 删除原日志记录（撤回后直接消失）
		db.Exec("DELETE FROM admin_operation_logs WHERE id = ?", req.LogID)
		log.Printf("[Admin] 开发者撤回禁言: 用户 %d, 来源日志ID=%d（已删除原日志）", targetUserID, req.LogID)
		writeJSON(w, 200, "已撤销禁言，用户已恢复使用", nil)

	default:
		writeJSON(w, 400, "该操作类型不支持撤回: "+opType, nil)
	}
}

// ==================== 开发者修改用户资料（ID/用户名/QQ/注册时间） ====================
