package main

import (
	"net/http"
)

func registerDistributionRoutes(mux *http.ServeMux) {
	// ==================== 文件分发（后台中心板块一） ====================
	mux.HandleFunc("/api/fd/upload", authMiddleware(handleFdUpload))
	mux.HandleFunc("/api/fd/list", authMiddleware(handleFdList))
	mux.HandleFunc("/api/fd/delete", authMiddleware(handleFdDelete))
	mux.HandleFunc("/api/fd/alias", authMiddleware(handleFdSetAlias)) // 设置/取消固定链接（/fd/<别名>）
	mux.HandleFunc("/api/admin/fd/config", authMiddleware(handleFdAdminConfig))
	mux.HandleFunc("/api/admin/fd/config/custom-list", authMiddleware(handleFdAdminCustomList))
	// 公开下载直链（无需登录）：/fd/<token>?dl=1 强制下载
	mux.HandleFunc("/fd/", handleFdDownload)
	mux.HandleFunc("/api/location/upload", authMiddleware(handleUploadLocation))
	mux.HandleFunc("/api/user/ban-status", authMiddleware(handleCheckBanStatus))
	mux.HandleFunc("/api/user/mute-status", authMiddleware(handleCheckMuteStatus))
	mux.HandleFunc("/api/user/is-platform-admin", authMiddleware(handleCheckIsPlatformAdmin))
	mux.HandleFunc("/api/flash/view", authMiddleware(handleRecordFlashView))
	mux.HandleFunc("/api/flash/viewing", authMiddleware(handleFlashViewing))
	mux.HandleFunc("/api/flash/viewed-ids", authMiddleware(handleGetViewedFlashIDs))
	mux.HandleFunc("/api/flash/partner-viewed", authMiddleware(handleGetPartnerViewedFlash))
	mux.HandleFunc("/api/user/ban-notify", authMiddleware(handleBanNotify))

	// ==================== 插件市场接口（需要 JWT 认证；审核类仅开发者） ====================
	mux.HandleFunc("/api/plugin/submit", authMiddleware(handlePluginSubmit))            // 用户提交插件(进入待审核)
	mux.HandleFunc("/api/plugin/market", authMiddleware(handlePluginMarket))            // 市场列表(仅已上架)
	mux.HandleFunc("/api/plugin/mine", authMiddleware(handlePluginMine))                // 我的插件(本人提交的全部记录)
	mux.HandleFunc("/api/plugin/delete", authMiddleware(handlePluginDelete))            // 删除自己创建的插件
	mux.HandleFunc("/api/plugin/admin/list", authMiddleware(handlePluginAdminList))     // 开发者:审核列表
	mux.HandleFunc("/api/plugin/admin/review", authMiddleware(handlePluginAdminReview)) // 开发者:上架/拒绝/下架

	// ==================== 安全设置接口（需要 JWT 认证） ====================
	mux.HandleFunc("/api/security/status", authMiddleware(handleGetSecurityStatus))
	mux.HandleFunc("/api/security/save", authMiddleware(handleSaveSecurity))
	mux.HandleFunc("/api/security/verify", authMiddleware(handleVerifySecurity))
}
