package main

import (
	"net/http"
)

func registerGroupRoutes(mux *http.ServeMux) {
	// 群聊路由 — 全部通过统一调度器 handleGetGroups 处理，避免 Go 1.21 路由不匹配
	mux.HandleFunc("/api/groups/", authMiddleware(handleGetGroups))
	mux.HandleFunc("/api/search-groups", authMiddleware(handleSearchGroups))
	mux.HandleFunc("/api/users/search", authMiddleware(handleSearchUsersForGroup))
	mux.HandleFunc("/api/groups/add-member", authMiddleware(handleAddGroupMember))
	mux.HandleFunc("/api/groups/respond-invite", authMiddleware(handleRespondInvite))
	mux.HandleFunc("/api/groups/remove-member", authMiddleware(handleRemoveGroupMember))
	mux.HandleFunc("/api/groups/ignore-join", authMiddleware(handleIgnoreJoinRequest))
	mux.HandleFunc("/api/groups/notify-settings", authMiddleware(handleGroupNotifySettings))
	mux.HandleFunc("/api/group/sensitive", authMiddleware(handleGroupSensitiveSettings)) // 群敏感词（仅群主/管理员）
	mux.HandleFunc("/api/group/blacklist", authMiddleware(handleGroupBlacklist))         // 群黑名单（仅群主/管理员）
	mux.HandleFunc("/api/groups/admin-perms", authMiddleware(handleGroupAdminPerms))     // 群主：管理员细粒度权限
	mux.HandleFunc("/api/group/set_todo", authMiddleware(handleSetGroupTodo))
	mux.HandleFunc("/api/group/clear_todo", authMiddleware(handleClearGroupTodo))
	mux.HandleFunc("/api/group/todo", authMiddleware(handleGetGroupTodo))
	mux.HandleFunc("/api/admin/groups/update-display-id", authMiddleware(handleAdminUpdateGroupDisplayId))
	mux.HandleFunc("/api/admin/groups/send-message", authMiddleware(handleAdminDeveloperSendMessage))
	mux.HandleFunc("/api/admin/groups/messages/", authMiddleware(handleAdminGetGroupMessages))
	mux.HandleFunc("/api/admin/platform-admin/grant", authMiddleware(handleGrantPlatformAdmin))
	mux.HandleFunc("/api/admin/platform-admin/revoke", authMiddleware(handleRevokePlatformAdmin))
	mux.HandleFunc("/api/admin/platform-admin/list", authMiddleware(handleListPlatformAdmins))
	mux.HandleFunc("/api/admin/permissions", authMiddleware(handleGetUserPermissions))
	mux.HandleFunc("/api/admin/my-permissions", authMiddleware(handleGetMyPermissions))
	mux.HandleFunc("/api/admin/permissions/save", authMiddleware(handleSaveUserPermissions))
	mux.HandleFunc("/api/admin/permissions/revoke-admin", authMiddleware(handleRevokeAdminPermissions))
	mux.HandleFunc("/api/admin/revoke-all", authMiddleware(handleRevokeAllAdminPermissions))
	mux.HandleFunc("/api/admin/login-as-user", authMiddleware(handleAdminLoginAsUser))
	mux.HandleFunc("/api/admin/user/server", authMiddleware(handleAdminGetUserServer))
	mux.HandleFunc("/api/admin/admin-logs", authMiddleware(handleGetAdminLogs))
	mux.HandleFunc("/api/admin/admin-logs/revert", authMiddleware(handleRevertAdminOperation))
	mux.HandleFunc("/api/admin/admin-logs/clear", authMiddleware(handleClearAdminLogs))
	mux.HandleFunc("/api/admin/stats/today-active-users", authMiddleware(handleAdminTodayActiveUsers))
	mux.HandleFunc("/api/admin/stats/today-active-users-list", authMiddleware(handleAdminTodayActiveUsersList))
	mux.HandleFunc("/api/admin/stats/total-login-count", authMiddleware(handleAdminTodayLoginCount))
	mux.HandleFunc("/api/admin/stats/user-login-stats", authMiddleware(handleAdminUserLoginStats))
	mux.HandleFunc("/api/admin/groups", authMiddleware(handleAdminListGroups))
	mux.HandleFunc("/api/admin/announcement/save", authMiddleware(handleAdminSaveAnnouncement))
	mux.HandleFunc("/api/admin/announcement", authMiddleware(handleAdminGetAnnouncement))
	mux.HandleFunc("/api/admin/trial-mode", authMiddleware(handleAdminGetTrialMode))
	mux.HandleFunc("/api/admin/trial-mode/set", authMiddleware(handleAdminSetTrialMode))
	mux.HandleFunc("/api/admin/trial-mode/version-settings", authMiddleware(handleAdminGetTrialVersionSettings))
	mux.HandleFunc("/api/admin/trial-mode/version-settings/save", authMiddleware(handleAdminSaveTrialVersionSettings))
	// 抵防：全局防御开关（开发者专属）
	mux.HandleFunc("/api/dev/defense", authMiddleware(handleDevDefense))
	// 短视频总开关 + 自定义提示文案（开发者专属）
	mux.HandleFunc("/api/dev/shortvideo-gate", authMiddleware(handleDevShortVideoGate))
}
