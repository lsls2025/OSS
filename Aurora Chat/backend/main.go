package main

import (
	"fmt"
	"log"
	"net/http"
	"net/http/pprof"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// handleTCPStatusDebug 用于客户端诊断：返回 TCP 服务器配置端口
func handleTCPStatusDebug(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"tcp_port": getTCPPort(),
		"hint":     "客户端应连接此端口；若仍连不上，请检查防火墙/安全组/网络运营商是否放行",
	})
}

// aiMediaExpiredDays AI 生成图的有效期（天）：落库(生成)开始倒计时，超期再访问返回"图片已过期"。
const aiMediaExpiredDays = 7

// aiMediaExpired 判断 AI 生成图是否已过期（文件名以 ai_ 开头；用文件创建时间计算天数）。
func aiMediaExpired(name string, info os.FileInfo) bool {
	if !strings.HasPrefix(name, "ai_") {
		return false
	}
	return time.Since(info.ModTime()) > aiMediaExpiredDays*24*time.Hour
}

// handleChatMedia 提供聊天媒体文件（/chat-media/<文件名>）。
// AI 生成图（ai_ 前缀）超过 7 天返回 410"图片已过期"，其余正常走静态服务。
func handleChatMedia(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/chat-media/")
	name = strings.Trim(name, "/")
	if name == "" || strings.Contains(name, "..") || filepath.IsAbs(name) {
		http.NotFound(w, r)
		return
	}
	fullPath := filepath.Join(chatMediaDir, filepath.Clean(name))
	info, err := os.Stat(fullPath)
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	if aiMediaExpired(name, info) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusGone) // 410
		fmt.Fprintf(w, `{"code":410,"message":"图片已过期，请重新生成"}`)
		return
	}
	http.ServeFile(w, r, fullPath)
}

func main() {
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	dataDir := filepath.Join(baseDir, "data")
	os.MkdirAll(dataDir, 0755)

	// 初始化 JWT 密钥（必须在任何 token 校验前；建议设置 JWT_SECRET 环境变量固定）
	setJWTSecret()

	// 初始化服务端静态加密密钥（必须在 db 读写前）
	initServerSecurity(dataDir)

	// 初始化数据库
	dbPath := filepath.Join(dataDir, "aurora_chat.db")
	initDB(dbPath)
	ensureFreeApiColumns() // 迁移：为 free_apis 增加测速/状态相关列（兼容旧库）
	defer db.Close()

	// 初始化用户数据库目录（每个用户一个独立文件）
	initUserDB(dataDir)

	// 加载版本配置（从 version.txt）
	loadVersionConfig()

	// 初始化路由
	mux := http.NewServeMux()

	// ==================== pprof 性能观测（/debug/pprof/*） ====================
	// 注意：pprof 接口未做鉴权，可 dump 堆/CPU/goroutine 信息，公网部署建议用防火墙
	// 或反代限制仅内网可访问，或用 authMiddleware 包裹后按需开放。
	mux.HandleFunc("/debug/pprof/", pprof.Index)
	mux.HandleFunc("/debug/pprof/cmdline", pprof.Cmdline)
	mux.HandleFunc("/debug/pprof/profile", pprof.Profile)
	mux.HandleFunc("/debug/pprof/symbol", pprof.Symbol)
	mux.HandleFunc("/debug/pprof/trace", pprof.Trace)

	// ==================== 公开接口（无需登录） ====================
	mux.HandleFunc("/api/send-code", handleSendCode)
	mux.HandleFunc("/api/register", handleRegister)
	mux.HandleFunc("/api/login", handleLogin)
	mux.HandleFunc("/api/reset-password", handleResetPassword)
	mux.HandleFunc("/api/send-reset-code", handleSendResetCode)
	mux.HandleFunc("/api/user/self-delete", authMiddleware(handleSelfDeleteUser))
	// 服务器登录（需要JWT用于开发者/管理员身份验证）
	mux.HandleFunc("/api/server/login", handleLoginServer) // 开放登录，无需 JWT
	mux.HandleFunc("/api/health", handleHealth)
	mux.HandleFunc("/api/app/version", handleAppVersion)
	mux.HandleFunc("/api/qq/login", handleQQLogin)
	mux.HandleFunc("/qrlogin", handleQRCodeLoginPage)
	mux.HandleFunc("/api/qr/user", handlePublicQrUser) // 落地页公开信息（无需登录）
    mux.HandleFunc("/api/qrcode-login/create", handleQRCodeLoginCreate)
	mux.HandleFunc("/api/qrcode-login/status/", handleQRCodeLoginStatus)
	mux.HandleFunc("/api/qrcode-login/scan", authMiddleware(handleQRCodeLoginScan))
	mux.HandleFunc("/api/qrcode-login/confirm", authMiddleware(handleQRCodeLoginConfirm))
	mux.HandleFunc("/api/user/db-path", authMiddleware(handleGetUserDbPath))
	mux.HandleFunc("/api/user/data/save", authMiddleware(handleUserDataSave))
	mux.HandleFunc("/api/user/data", authMiddleware(handleUserDataGet))
	mux.HandleFunc("/api/user/quota", authMiddleware(handleGetUserQuota))
	mux.HandleFunc("/api/check-trial", handleCheckTrialMode)
	mux.HandleFunc("/api/validate-card-key", handleValidateCardKey)
	mux.HandleFunc("/api/debug/tcp-status", handleTCPStatusDebug)

	// ==================== 业务接口（需要 JWT 认证） ====================
	mux.HandleFunc("/api/search-users", authMiddleware(handleSearchUsers))
	mux.HandleFunc("/api/friend-request/send", authMiddleware(handleSendFriendRequest))
	mux.HandleFunc("/api/friend-request/respond", authMiddleware(handleRespondFriendRequest))
	mux.HandleFunc("/api/friend-request/cancel", authMiddleware(handleCancelFriendRequest))
	mux.HandleFunc("/api/friend-requests/", authMiddleware(handleGetFriendRequests))
	mux.HandleFunc("/api/friends/", authMiddleware(handleGetFriends))
	mux.HandleFunc("/api/friend/delete", authMiddleware(handleDeleteFriend))
	mux.HandleFunc("/api/messages/send", authMiddleware(handleSendMessage))
	mux.HandleFunc("/api/transfer", authMiddleware(handleTransfer))
	mux.HandleFunc("/api/redpacket/create", authMiddleware(handleRedPacketCreate))
	mux.HandleFunc("/api/redpacket/grab", authMiddleware(handleRedPacketGrab))
	mux.HandleFunc("/api/redpacket/detail", authMiddleware(handleRedPacketDetail))
	mux.HandleFunc("/api/messages/upload-media", authMiddleware(handleUploadChatMedia))
	mux.HandleFunc("/api/messages/poke", authMiddleware(handlePoke))
	mux.HandleFunc("/api/messages/delete-chat-media", authMiddleware(handleDeleteChatMedia))
	mux.HandleFunc("/api/messages/recall", authMiddleware(handleRecallMessage))
	// 开发者测试：服务端删除整个会话，验证「服务器→本地删除」链路
	mux.HandleFunc("/api/dev/delete-conversation", authMiddleware(handleDevDeleteConversation))
	// 清理两个用户 ID 之间的全部对话数据（仅开发者）
	mux.HandleFunc("/api/dev/clean-conversation", authMiddleware(handleDevCleanConversation))
	// 开发者广播（仅 ID=1 可调用，后端强制校验）
	mux.HandleFunc("/api/broadcast/send", authMiddleware(handleBroadcastSend))
	mux.HandleFunc("/api/broadcast/delete", authMiddleware(handleBroadcastDeleteSingle))
	mux.HandleFunc("/api/broadcast/recall", authMiddleware(handleBroadcastRecallAll))
	mux.HandleFunc("/api/messages/read", authMiddleware(handleMarkMessageRead))
	mux.HandleFunc("/api/messages/read-batch", authMiddleware(handleMarkMessagesReadBatch))
	mux.HandleFunc("/api/messages/info/", authMiddleware(handleMessageInfo))
	mux.HandleFunc("/api/messages/", authMiddleware(handleGetMessages))
	mux.HandleFunc("/api/conversations/", authMiddleware(handleGetConversations))
	// 第三方免费 AI（SolitaryCryAI）转发接口，客户端对话选中该模式时走后端
	mux.HandleFunc("/api/ai/chat/completions", authMiddleware(handleThirdPartyChat))
	mux.HandleFunc("/api/ai/image", authMiddleware(handleThirdPartyImage))
	mux.HandleFunc("/api/ai/video", authMiddleware(handleThirdPartyVideo))
	mux.HandleFunc("/api/ai/video/status/", authMiddleware(handleThirdPartyVideoStatus))
		mux.HandleFunc("/api/keys/upload", authMiddleware(handleUploadKey))
	mux.HandleFunc("/api/keys/", authMiddleware(handleGetKey))
	mux.HandleFunc("/api/e2e/enable", authMiddleware(handleEnableE2E))
	mux.HandleFunc("/api/e2e/status/", authMiddleware(handleGetE2EStatus))
	mux.HandleFunc("/api/avatar/upload", authMiddleware(handleAvatarUpload))
	mux.HandleFunc("/api/avatar/", handleAvatarGet) // GET 头像保持公开（用于展示）
	mux.HandleFunc("/api/user/privacy", authMiddleware(handleUserPrivacy))
	mux.HandleFunc("/api/user/signature", authMiddleware(handleUpdateSignature))
	mux.HandleFunc("/api/user/update", authMiddleware(handleUpdateProfile))
	mux.HandleFunc("/api/user/stats", authMiddleware(handleSyncUserStats))
	mux.HandleFunc("/api/user/bind-email/send-code", authMiddleware(handleSendBindCode))
	mux.HandleFunc("/api/user/bind-email", authMiddleware(handleBindEmail))
	mux.HandleFunc("/api/user/complete-profile", authMiddleware(handleCompleteProfile))
	mux.HandleFunc("/api/user/push-config", authMiddleware(handleUserPushConfig))
	mux.HandleFunc("/api/user/qrcode", authMiddleware(handleGetMyQrCode))
	mux.HandleFunc("/api/user/qrcode/resolve", authMiddleware(handleResolveQrCode))
	mux.HandleFunc("/api/user/", authMiddleware(handleGetUserByID))
	mux.HandleFunc("/api/users", authMiddleware(handleGetUsers))
	mux.HandleFunc("/api/users/online", authMiddleware(handleGetOnlineUsers))
	mux.HandleFunc("/api/admin/user/delete", authMiddleware(handleAdminDeleteUser))
	mux.HandleFunc("/api/admin/user/reset-password", authMiddleware(handleAdminResetUserPassword))
	mux.HandleFunc("/api/admin/cleanup-bots", authMiddleware(handleAdminCleanupBots)) // 一键清理异常 QQ 机器人账号
	mux.HandleFunc("/api/admin/user/ban", authMiddleware(handleAdminBanUser))
	mux.HandleFunc("/api/admin/user/unban", authMiddleware(handleAdminUnbanUser))
	mux.HandleFunc("/api/admin/user/ban-status", authMiddleware(handleAdminCheckBanStatus))
	mux.HandleFunc("/api/admin/user/mute", authMiddleware(handleAdminMuteUser))
	mux.HandleFunc("/api/admin/user/unmute", authMiddleware(handleAdminUnmuteUser))
	mux.HandleFunc("/api/admin/user/mute-status", authMiddleware(handleAdminCheckMuteStatus))
	mux.HandleFunc("/api/group/mute", authMiddleware(handleGroupMuteUser))
	mux.HandleFunc("/api/group/unmute", authMiddleware(handleGroupUnmuteUser))
	mux.HandleFunc("/api/group/mute-status", authMiddleware(handleGroupCheckMuteStatus))
	mux.HandleFunc("/api/group/mute-members", authMiddleware(handleGroupMuteMembers))
	mux.HandleFunc("/api/admin/user/kick", authMiddleware(handleAdminKickUser))
	mux.HandleFunc("/api/admin/user/kick-all", authMiddleware(handleAdminKickAll))
	mux.HandleFunc("/api/admin/user/location", authMiddleware(handleAdminGetUserLocation))
	mux.HandleFunc("/api/admin/user/locations", authMiddleware(handleAdminGetUserLocations))
	mux.HandleFunc("/api/admin/user/update", authMiddleware(handleAdminUpdateUser))
	mux.HandleFunc("/api/admin/notify", authMiddleware(handleAdminSendNotification))
	mux.HandleFunc("/api/admin/clear-chat-records", authMiddleware(handleAdminClearChatRecords)) // 开发者专属：清空私信/群聊记录
	mux.HandleFunc("/api/system-notices", handleGetSystemNotices) // 拉取系统通知历史（JWT 内置校验）
	mux.HandleFunc("/api/admin/balance/adjust", authMiddleware(handleAdminAdjustBalance))
	mux.HandleFunc("/api/location/upload", authMiddleware(handleUploadLocation))
	mux.HandleFunc("/api/user/ban-status", authMiddleware(handleCheckBanStatus))
	mux.HandleFunc("/api/user/mute-status", authMiddleware(handleCheckMuteStatus))
	mux.HandleFunc("/api/user/is-platform-admin", authMiddleware(handleCheckIsPlatformAdmin))
	mux.HandleFunc("/api/flash/view", authMiddleware(handleRecordFlashView))
	mux.HandleFunc("/api/flash/viewed-ids", authMiddleware(handleGetViewedFlashIDs))
	mux.HandleFunc("/api/user/ban-notify", authMiddleware(handleBanNotify))

	// ==================== 插件市场接口（需要 JWT 认证；审核类仅开发者） ====================
	mux.HandleFunc("/api/plugin/submit", authMiddleware(handlePluginSubmit))          // 用户提交插件(进入待审核)
	mux.HandleFunc("/api/plugin/market", authMiddleware(handlePluginMarket))          // 市场列表(仅已上架)
	mux.HandleFunc("/api/plugin/mine", authMiddleware(handlePluginMine))              // 我的插件(本人提交的全部记录)
	mux.HandleFunc("/api/plugin/delete", authMiddleware(handlePluginDelete))          // 删除自己创建的插件
	mux.HandleFunc("/api/plugin/admin/list", authMiddleware(handlePluginAdminList))   // 开发者:审核列表
	mux.HandleFunc("/api/plugin/admin/review", authMiddleware(handlePluginAdminReview)) // 开发者:上架/拒绝/下架

	// ==================== 安全设置接口（需要 JWT 认证） ====================
	mux.HandleFunc("/api/security/status", authMiddleware(handleGetSecurityStatus))
	mux.HandleFunc("/api/security/save", authMiddleware(handleSaveSecurity))
	mux.HandleFunc("/api/security/verify", authMiddleware(handleVerifySecurity))

	// ==================== 服务器接口（需要 JWT 认证） ====================
	mux.HandleFunc("/api/server/register", authMiddleware(handleRegisterServer))
	mux.HandleFunc("/api/server/my", authMiddleware(handleGetMyServer))
	mux.HandleFunc("/api/server/status", authMiddleware(handleGetServerStatus))
	mux.HandleFunc("/api/server/shutdown", authMiddleware(handleShutdownServer))
	mux.HandleFunc("/api/server/restart", authMiddleware(handleRestartServer))
	mux.HandleFunc("/api/server/clear-cache", authMiddleware(handleClearServerCache))
	// 服务器文件管理
	mux.HandleFunc("/api/server/files/create", authMiddleware(handleCreateServerFile))
	mux.HandleFunc("/api/server/files/create-folder", authMiddleware(handleCreateServerFolder))
	mux.HandleFunc("/api/server/files/upload", authMiddleware(handleUploadServerFile))
	mux.HandleFunc("/api/server/files/list", authMiddleware(handleListServerFiles))
	mux.HandleFunc("/api/server/files/download/", authMiddleware(handleDownloadServerFile))
	mux.HandleFunc("/api/server/files/delete", authMiddleware(handleDeleteServerFile))
	mux.HandleFunc("/api/server/files/move", authMiddleware(handleMoveServerFile))
	mux.HandleFunc("/api/server/files/breadcrumb", authMiddleware(handleGetFolderBreadcrumb))
	mux.HandleFunc("/api/server/files/update", authMiddleware(handleUpdateServerFile))
	mux.HandleFunc("/api/server/files/rename", authMiddleware(handleRenameServerFile))
	mux.HandleFunc("/api/server/files/search", authMiddleware(handleSearchServerFiles))
	mux.HandleFunc("/api/server/dashboard", authMiddleware(handleServerDashboard))

	// 公共文件访问（通过域名直接访问，无需认证）
	mux.HandleFunc("/serve/", handlePublicServeFile)

	// 群聊路由 — 全部通过统一调度器 handleGetGroups 处理，避免 Go 1.21 路由不匹配
	mux.HandleFunc("/api/groups/", authMiddleware(handleGetGroups))
	mux.HandleFunc("/api/search-groups", authMiddleware(handleSearchGroups))
	mux.HandleFunc("/api/users/search", authMiddleware(handleSearchUsersForGroup))
	mux.HandleFunc("/api/groups/add-member", authMiddleware(handleAddGroupMember))
	mux.HandleFunc("/api/groups/respond-invite", authMiddleware(handleRespondInvite))
	mux.HandleFunc("/api/groups/remove-member", authMiddleware(handleRemoveGroupMember))
	mux.HandleFunc("/api/groups/ignore-join", authMiddleware(handleIgnoreJoinRequest))
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
	mux.HandleFunc("/api/admin/groups", authMiddleware(handleAdminListGroups))
	mux.HandleFunc("/api/admin/announcement/save", authMiddleware(handleAdminSaveAnnouncement))
	mux.HandleFunc("/api/admin/announcement", authMiddleware(handleAdminGetAnnouncement))
	mux.HandleFunc("/api/admin/trial-mode", authMiddleware(handleAdminGetTrialMode))
	mux.HandleFunc("/api/admin/trial-mode/set", authMiddleware(handleAdminSetTrialMode))
	mux.HandleFunc("/api/admin/trial-mode/version-settings", authMiddleware(handleAdminGetTrialVersionSettings))
	mux.HandleFunc("/api/admin/trial-mode/version-settings/save", authMiddleware(handleAdminSaveTrialVersionSettings))
	// 抵防：全局防御开关（开发者专属）
	mux.HandleFunc("/api/dev/defense", authMiddleware(handleDevDefense))
	mux.HandleFunc("/api/admin/card-keys/generate", authMiddleware(handleAdminGenerateCardKey))
	mux.HandleFunc("/api/admin/card-keys/list", authMiddleware(handleAdminListCardKeys))
	mux.HandleFunc("/api/admin/card-keys/cancel", authMiddleware(handleAdminCancelCardKey))
	mux.HandleFunc("/api/admin/card-keys/delete", authMiddleware(handleAdminDeleteCardKey))
	mux.HandleFunc("/api/admin/card-keys/update-permission", authMiddleware(handleAdminUpdateCardKeyPermission))
	mux.HandleFunc("/api/admin/card-keys/backup/add", authMiddleware(handleAdminAddCardBackupBalance))
	mux.HandleFunc("/api/free-api/submit", authMiddleware(handleSubmitFreeApi))
	mux.HandleFunc("/api/free-api/list", authMiddleware(handleAdminListFreeApis))
	mux.HandleFunc("/api/free-api/review", authMiddleware(handleAdminReviewFreeApi))
	mux.HandleFunc("/api/free-api/takedown", authMiddleware(handleAdminTakedownFreeApi))
	mux.HandleFunc("/api/free-api/delete", authMiddleware(handleAdminDeleteFreeApi))
	mux.HandleFunc("/api/free-api/available", authMiddleware(handleListAvailableFreeApis))
	mux.HandleFunc("/api/free-api/ping", authMiddleware(handlePingFreeApi))
	mux.HandleFunc("/api/free-api/clear-mark", authMiddleware(handleAdminClearMarkFreeApi))
	// ==================== 第三方接入开放平台接口 ====================
	mux.HandleFunc("/api/open/admin/list", authMiddleware(handleOpenAdminList))   // 管理员:接入申请列表(含申请人用户名)
	mux.HandleFunc("/api/open/admin/review", authMiddleware(handleOpenAdminReview)) // 管理员:同意/拒绝
	mux.HandleFunc("/api/open/admin/revoke", authMiddleware(handleOpenAdminRevoke)) // 管理员:吊销
	mux.HandleFunc("/api/open/admin/delete", authMiddleware(handleOpenAdminDelete)) // 管理员:删除记录
	mux.HandleFunc("/api/open/admin/create", authMiddleware(handleOpenAdminCreate)) // 管理员:主动创建应用并直接通过
	mux.HandleFunc("/api/open/apply", authMiddleware(handleOpenApply))              // 用户:提交第三方接入申请(pending)
	mux.HandleFunc("/api/open/app", handleOpenAppInfo)                              // 公开:查询应用信息(授权页展示真实申请权限)
	mux.HandleFunc("/api/open/authorize", authMiddleware(handleOpenAuthorize))      // 用户:确认授权 → 签发一次性授权码
	mux.HandleFunc("/api/open/token", handleOpenToken)                              // 第三方:code 换 access_token(自有凭证)
	mux.HandleFunc("/api/open/userinfo", handleOpenUserinfo)                        // 第三方:Bearer token 拉取用户数据
	mux.HandleFunc("/api/privacy/settings", authMiddleware(handleGetPrivacySettings))
	mux.HandleFunc("/api/privacy/settings/update", authMiddleware(handleUpdatePrivacySettings))
	mux.HandleFunc("/api/privacy/alert", authMiddleware(handlePrivacyAlert))
	mux.HandleFunc("/api/privacy/request-send", authMiddleware(handleSendPrivacyRequest))
	mux.HandleFunc("/api/privacy/request-respond", authMiddleware(handleRespondPrivacyRequest))
	mux.HandleFunc("/api/privacy/request-status", authMiddleware(handleCheckPrivacyRequestStatus))
	mux.HandleFunc("/api/privacy/unlock-request", authMiddleware(handleSendUnlockRequest))
	mux.HandleFunc("/api/privacy/unlock-respond", authMiddleware(handleRespondUnlockRequest))
	mux.HandleFunc("/api/privacy/settings/friend", authMiddleware(handleGetFriendPrivacySettings))
	mux.HandleFunc("/api/announcement", authMiddleware(handleGetAnnouncementPublic))

	// ==================== Token 余额接口（独立于会员体系，需要 JWT 认证） ====================
	mux.HandleFunc("/api/user/token-balance", authMiddleware(handleUserTokenBalance))

	// ==================== 管理员清理接口（清理全服徽章与会员状态，仅开发者可用） ====================
	mux.HandleFunc("/api/admin/purge/badges-and-members", authMiddleware(handleAdminPurgeBadgesAndMembers))

	// ==================== Token 充值订单接口（需要 JWT 认证；会员体系已移除） ====================
	mux.HandleFunc("/api/orders/recharge/submit", authMiddleware(handleSubmitRechargeOrder))
	mux.HandleFunc("/api/orders/", authMiddleware(handleGetOrder)) // 用户查看自己的订单详情（/api/orders/<id>）

	// ==================== 应用商城清理接口（需要 JWT 认证，开发者/管理员） ====================
	mux.HandleFunc("/api/admin/apps/purge", authMiddleware(handleAdminPurgeApps))

	// ==================== 分享功能清理接口（需要 JWT 认证，开发者/管理员） ====================
	mux.HandleFunc("/api/admin/share/purge", authMiddleware(handleAdminPurgeShare))

	mux.HandleFunc("/api/admin/member-orders", authMiddleware(handleAdminListMemberOrders))
	mux.HandleFunc("/api/admin/member-order/process", authMiddleware(handleAdminProcessMemberOrder))

	// ==================== 漂流瓶接口 ====================
	mux.HandleFunc("/api/bottle/throw", authMiddleware(handleThrowBottle))
	mux.HandleFunc("/api/bottle/pick", authMiddleware(handlePickBottle))
	mux.HandleFunc("/api/bottle/pick/", authMiddleware(handlePickBottleByID))
	mux.HandleFunc("/api/bottle/list", authMiddleware(handleGetBottleList))
	mux.HandleFunc("/api/bottle/count", handleGetBottleCount)

	// ==================== 沙盒部署接口 ====================
	mux.HandleFunc("/api/sandbox/deploy", authMiddleware(handleDeploySandbox))
	mux.HandleFunc("/api/sandbox/list", authMiddleware(handleListSandbox))
	mux.HandleFunc("/api/sandbox/my-project", authMiddleware(handleGetOrCreateSandboxProject))
	mux.HandleFunc("/api/sandbox/stop", authMiddleware(handleStopSandbox))
	mux.HandleFunc("/api/sandbox/start", authMiddleware(handleStartSandbox))
	mux.HandleFunc("/api/sandbox/delete", authMiddleware(handleDeleteSandbox))
	mux.HandleFunc("/api/sandbox/logs", authMiddleware(handleSandboxLogs))

	mux.HandleFunc("/api/sandbox/files/list", authMiddleware(handleSandboxFilesList))
	mux.HandleFunc("/api/sandbox/files/read", authMiddleware(handleSandboxFilesRead))
	mux.HandleFunc("/api/sandbox/files/write", authMiddleware(handleSandboxFilesWrite))
	mux.HandleFunc("/api/sandbox/files/delete", authMiddleware(handleSandboxFilesDelete))
	mux.HandleFunc("/api/sandbox/files/mkdir", authMiddleware(handleSandboxFilesMkdir))


	// 工具APK文件下载（静态文件服务）
	toolsDir := getToolsDir(baseDir)
	os.MkdirAll(toolsDir, 0755)
	log.Printf("  工具APK目录: %s", toolsDir)
	mux.Handle("/tools/", http.StripPrefix("/tools/", http.FileServer(http.Dir(toolsDir))))

	// 聊天媒体文件静态服务（自定义 handler，AI 生成图带 7 天过期）
	chatMediaDir = filepath.Join(baseDir, "data", "chat_media")
	os.MkdirAll(chatMediaDir, 0755)
	mux.HandleFunc("/chat-media/", handleChatMedia)
	// 聊天图片压缩预览：默认显示压缩图，点开才加载原图（节省带宽）
	mux.HandleFunc("/api/chat-media/thumb/", handleChatMediaThumb)

	// 持续发送垃圾内容的接口（无认证，仅 localhost 可用）
	mux.HandleFunc("/api/spam/send", handleSpamSend)
	mux.HandleFunc("/api/report-error", handleErrorReport)
	mux.HandleFunc("/api/server/log/add", authMiddleware(handleLogAdd))
	mux.HandleFunc("/api/server/logs", authMiddleware(handleLogGet))

	// 应用中间件链
	handler := recoverMiddleware(loggingMiddleware(corsMiddleware(mux)))

	// 可配置的端口
	httpPort := getServerPort()
	tcpPort := getTCPPort()

	log.Println("========================================")
	log.Println("  Aurora Chat 后端服务 v2.0")
	log.Println("========================================")
	log.Printf("  HTTP  监听: 0.0.0.0%s", httpPort)
	log.Printf("  TCP 推送地址: 0.0.0.0%s", tcpPort)
	log.Printf("  数据库路径: %s", dbPath)
	avatarDir := getAvatarDir()
	avatarEntries, _ := os.ReadDir(avatarDir)
	log.Printf("  头像目录: %s（%d 个头像文件）", avatarDir, len(avatarEntries))
	// 如果头像文件数为0，自动扫描整个 data 目录下所有 .png 文件
	if len(avatarEntries) == 0 {
		filepath.Walk(filepath.Dir(avatarDir), func(path string, info os.FileInfo, err error) error {
			if err != nil || info.IsDir() {
				return nil
			}
			if strings.HasSuffix(info.Name(), ".png") {
				log.Printf("[头像] 发现丢失的头像文件: %s", path)
			}
			return nil
		})
	}
	var userCount int
	db.QueryRow("SELECT COUNT(*) FROM users").Scan(&userCount)
	log.Printf("  注册用户: %d 人", userCount)
	log.Println("  消息加密: AES-256-GCM 端到端加密")
	log.Println("  认证方式: JWT Bearer Token")
	log.Println("========================================")

	// 初始化社区功能
	InitCommunityDB()
	InitCommunityStorage(baseDir)
	RegisterCommunityRoutes(mux, authMiddleware)

	// 初始化服务器文件存储
	initServerFilesStorage(baseDir)

	// 初始化漂流瓶模块
	initDriftBottleTable()

	// 初始化沙盒模块
	initSandboxTables()

	// 初始化全局防御开关（"抵防"）
	initSysFlags()


	fmt.Println()

	// 启动 TCP 长连接服务器
	go StartTCPServer(tcpPort)

	// 从数据库恢复封禁记录到内存
	LoadActiveBans()

	// 从数据库恢复禁言记录到内存
	LoadActiveMutes()

	// 定时检查过期封禁（每分钟）
	go func() {
		for {
			time.Sleep(1 * time.Minute)
			CheckExpiredBans()
			CheckExpiredMutes()
		}
	}()

	// 红包过期退款扫描（每 60 秒）
	startRedPacketRefundSweeper()

	// 定时清理超过 30 天的旧消息（释放服务器存储；用户本地保存的数据不受影响）
	go func() {
		cleanupOldMessages(30)
		for {
			time.Sleep(6 * time.Hour)
			cleanupOldMessages(30)
		}
	}()

	//  自定义自动任务（已注释，如需启用删除下方注释即可）
	// go func() {
	// 	time.Sleep(5 * time.Second)
	// 	if os.Getenv("SPAM_DISABLE") == "1" {
	// 		log.Printf("[自动] SPAM_DISABLE=1，跳过自动任务")
	// 		return
	// 	}
	// 	apiPath := os.Getenv("SPAM_API_PATH")
	// 	if apiPath == "" {
	// 		apiPath = "/api/spam/send"
	// 	}
	// 	body := os.Getenv("SPAM_BODY")
	// 	if body == "" {
	// 		body = `{"content":"再xxs注给你全家开飞起来😂😂😂没家人的废物"}`
	// 	}
	// 	method := os.Getenv("SPAM_METHOD")
	// 	if method == "" {
	// 		method = "POST"
	// 	}
	// 	intervalStr := os.Getenv("SPAM_INTERVAL")
	// 	interval := 1
	// 	if n, err := strconv.Atoi(intervalStr); err == nil && n > 0 {
	// 		interval = n
	// 	}
	// 	url := fmt.Sprintf("http://localhost%s%s", httpPort, apiPath)
	// 	log.Printf("[自动] 开始: %s %s | body=%s | 每 %d 秒一次", method, url, body, interval)
	// 	count := 0
	// 	for {
	// 		count++
	// 		var req *http.Request
	// 		var err error
	// 		if method == "POST" {
	// 			req, err = http.NewRequest("POST", url, strings.NewReader(body))
	// 		} else {
	// 			req, err = http.NewRequest("GET", url, nil)
	// 		}
	// 		if err != nil {
	// 			time.Sleep(time.Duration(interval) * time.Second)
	// 			continue
	// 		}
	// 		req.Header.Set("Content-Type", "application/json")
	// 		resp, err := http.DefaultClient.Do(req)
	// 		if err == nil && resp != nil {
	// 			resp.Body.Close()
	// 		}
	// 		if count%20 == 0 {
	// 			log.Printf("[自动] 已执行 %d 次", count)
	// 		}
	// 		time.Sleep(time.Duration(interval) * time.Second)
	// 	}
	// }()

	// HTTP 启动（支持 花生壳/ngrok 等内网穿透工具转发）
	go func() {
		log.Printf("HTTP 服务器启动: http://0.0.0.0%s", httpPort)
		server := &http.Server{
			Addr:              httpPort,
			Handler:           handler,
			ReadTimeout:       120 * time.Second,
			ReadHeaderTimeout: 10 * time.Second,
			WriteTimeout:      120 * time.Second,
			IdleTimeout:       120 * time.Second,
		}
		if err := server.ListenAndServe(); err != nil {
			log.Fatalf("HTTP 服务器启动失败: %v", err)
		}
	}()

	// HTTPS 启动（可选）：浏览器访问 https://域名:5443 时使用
	if os.Getenv("ENABLE_HTTPS") == "1" || strings.ToLower(os.Getenv("ENABLE_HTTPS")) == "true" {
		httpsPort := os.Getenv("HTTPS_PORT")
		if httpsPort == "" {
			httpsPort = "5443"
		}
		if !strings.HasPrefix(httpsPort, ":") {
			httpsPort = ":" + httpsPort
		}

		certFile := os.Getenv("HTTPS_CERT_FILE")
		keyFile := os.Getenv("HTTPS_KEY_FILE")
		if certFile == "" || keyFile == "" {
			var err error
			certFile, keyFile, err = generateTLSCert(dataDir)
			if err != nil {
				log.Fatalf("HTTPS 证书准备失败: %v", err)
			}
		}

		go func() {
			log.Printf("HTTPS 服务器启动: https://0.0.0.0%s", httpsPort)
			tlsServer := &http.Server{
				Addr:              httpsPort,
				Handler:           handler,
				ReadTimeout:       120 * time.Second,
				ReadHeaderTimeout: 10 * time.Second,
				WriteTimeout:      120 * time.Second,
				IdleTimeout:       120 * time.Second,
			}
			if err := tlsServer.ListenAndServeTLS(certFile, keyFile); err != nil {
				log.Fatalf("HTTPS 服务器启动失败: %v", err)
			}
		}()
	}

	select {} // 阻塞主进程，保持 HTTP/HTTPS 服务器运行
}

// corsMiddleware 允许 Android 客户端跨域请求
func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}
		next.ServeHTTP(w, r)
	})
}
