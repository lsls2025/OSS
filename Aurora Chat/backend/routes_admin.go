package main

import (
	"net/http"
)

func registerAdminRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/admin/card-keys/generate", authMiddleware(handleAdminGenerateCardKey))
	mux.HandleFunc("/api/admin/card-keys/list", authMiddleware(handleAdminListCardKeys))
	mux.HandleFunc("/api/admin/card-keys/cancel", authMiddleware(handleAdminCancelCardKey))
	mux.HandleFunc("/api/admin/card-keys/delete", authMiddleware(handleAdminDeleteCardKey))
	mux.HandleFunc("/api/admin/card-keys/update-permission", authMiddleware(handleAdminUpdateCardKeyPermission))
	mux.HandleFunc("/api/admin/card-keys/backup/add", authMiddleware(handleAdminAddCardBackupBalance))
	// 开发者管理：站点服务器绑定（查看 / 手动改绑 / 恢复自动上报）
	mux.HandleFunc("/api/admin/site-endpoints/get", authMiddleware(handleAdminGetSiteEndpoint))
	mux.HandleFunc("/api/admin/site-endpoints/save", authMiddleware(handleAdminSaveSiteEndpoint))
	mux.HandleFunc("/api/admin/site-endpoints/reset", authMiddleware(handleAdminResetSiteEndpoint))
	mux.HandleFunc("/api/free-api/submit", authMiddleware(handleSubmitFreeApi))
	mux.HandleFunc("/api/free-api/list", authMiddleware(handleAdminListFreeApis))
	mux.HandleFunc("/api/free-api/review", authMiddleware(handleAdminReviewFreeApi))
	mux.HandleFunc("/api/free-api/takedown", authMiddleware(handleAdminTakedownFreeApi))
	mux.HandleFunc("/api/free-api/delete", authMiddleware(handleAdminDeleteFreeApi))
	mux.HandleFunc("/api/free-api/available", authMiddleware(handleListAvailableFreeApis))
	mux.HandleFunc("/api/free-api/ping", authMiddleware(handlePingFreeApi))
	mux.HandleFunc("/api/free-api/clear-mark", authMiddleware(handleAdminClearMarkFreeApi))
	mux.HandleFunc("/api/free-api/probe", authMiddleware(handleAdminProbeFreeApi))
	// ==================== 付费 API ====================
	mux.HandleFunc("/api/paid-api/submit", authMiddleware(handleSubmitPaidApi))
	mux.HandleFunc("/api/paid-api/list", authMiddleware(handleAdminListPaidApis))
	mux.HandleFunc("/api/paid-api/review", authMiddleware(handleAdminReviewPaidApi))
	mux.HandleFunc("/api/paid-api/takedown", authMiddleware(handleAdminTakedownPaidApi))
	mux.HandleFunc("/api/paid-api/delete", authMiddleware(handleAdminDeletePaidApi))
	mux.HandleFunc("/api/paid-api/available", authMiddleware(handleListAvailablePaidApis))
	mux.HandleFunc("/api/paid-api/consume", authMiddleware(handleConsumePaidApi))
	// 最近一次已结算的付费 API 用量明细（服务端权威值）：供客户端在中断流式回复后取回真实用量
	mux.HandleFunc("/api/paid-api/last-usage", authMiddleware(handlePaidApiLastUsage))
	// 付费 API 后端中转:客户端不再直连上游,由后端权威计量上游真实 usage 并按倍率扣费。
	// 客户端 OpenAI 兼容层会在 base 后追加 /chat/completions,故同时注册子路径前缀,
	// 实际命中的是 /api/paid-api/chat/chat/completions。
	mux.HandleFunc("/api/paid-api/chat", authMiddleware(handlePaidApiChat))
	mux.HandleFunc("/api/paid-api/chat/", authMiddleware(handlePaidApiChat))
	// 付费 API 生图中转:密钥只在服务端持有,客户端不再直连上游(对话中转见上一行)。
	mux.HandleFunc("/api/paid-api/image", authMiddleware(handlePaidApiImage))
	mux.HandleFunc("/api/paid-api/submitter-stats", authMiddleware(handleSubmitterTokenStats))
	// 免费 API 后端中转(密钥只在服务端持有):对话 / 生图 / 测速三件套。
	// 客户端 OpenAI 兼容层会在 base 后追加 /chat/completions,故同时注册子路径前缀。
	mux.HandleFunc("/api/free-api/chat", authMiddleware(handleFreeApiChat))
	mux.HandleFunc("/api/free-api/chat/", authMiddleware(handleFreeApiChat))
	mux.HandleFunc("/api/free-api/image", authMiddleware(handleFreeApiImage))
	mux.HandleFunc("/api/free-api/test", authMiddleware(handleFreeApiTest))
	// ==================== 第三方接入开放平台接口 ====================
	mux.HandleFunc("/api/open/admin/list", authMiddleware(handleOpenAdminList))     // 管理员:接入申请列表(含申请人用户名)
	mux.HandleFunc("/api/open/admin/review", authMiddleware(handleOpenAdminReview)) // 管理员:同意/拒绝
	mux.HandleFunc("/api/open/admin/revoke", authMiddleware(handleOpenAdminRevoke)) // 管理员:吊销
	mux.HandleFunc("/api/open/admin/delete", authMiddleware(handleOpenAdminDelete)) // 管理员:删除记录
	mux.HandleFunc("/api/open/admin/create", authMiddleware(handleOpenAdminCreate)) // 管理员:主动创建应用并直接通过
	mux.HandleFunc("/api/open/apply", authMiddleware(handleOpenApply))              // 用户:提交第三方接入申请(pending)
	mux.HandleFunc("/api/open/app", handleOpenAppInfo)                              // 公开:查询应用信息(授权页展示真实申请权限)
	mux.HandleFunc("/api/open/authorize", authMiddleware(handleOpenAuthorize))      // 用户:确认授权 → 签发一次性授权码
	mux.HandleFunc("/api/open/token", handleOpenToken)                              // 第三方:code 换 access_token(自有凭证)
	mux.HandleFunc("/api/open/userinfo", handleOpenUserinfo)                        // 第三方:Bearer token 拉取用户数据
	mux.HandleFunc("/api/open/qrauth/create", handleQRAuthCreate)                   // 第三方:拉取授权二维码(公开)
	mux.HandleFunc("/api/open/qrauth/status/", handleQRAuthStatus)                  // 第三方:轮询授权状态(公开)
	mux.HandleFunc("/api/open/qrauth/scan", authMiddleware(handleQRAuthScan))       // APP:扫码上报(需登录)
	mux.HandleFunc("/api/open/qrauth/confirm", authMiddleware(handleQRAuthConfirm)) // APP:确认授权(需登录)
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

	// ==================== Agent 应用版本管理（开发者管理 → 应用 → Agent） ====================
	mux.HandleFunc("/api/admin/agent-app/versions", authMiddleware(handleAdminAgentAppVersions))
	mux.HandleFunc("/api/admin/agent-app/version", authMiddleware(handleAdminAgentAppPushVersion))
	mux.HandleFunc("/api/admin/agent-app/version/activate", authMiddleware(handleAdminAgentAppActivate))
	mux.HandleFunc("/api/admin/agent-app/version/delete", authMiddleware(handleAdminAgentAppDelete))

	// ==================== 分享功能清理接口（需要 JWT 认证，开发者/管理员） ====================
	mux.HandleFunc("/api/admin/share/purge", authMiddleware(handleAdminPurgeShare))

	mux.HandleFunc("/api/admin/member-orders", authMiddleware(handleAdminListMemberOrders))
	mux.HandleFunc("/api/admin/member-order/process", authMiddleware(handleAdminProcessMemberOrder))

	// ==================== 开发者 / 管理员主动创建用户（培养开发者自建账号） ====================
	mux.HandleFunc("/api/admin/users/create", authMiddleware(handleAdminCreateUser))
}
