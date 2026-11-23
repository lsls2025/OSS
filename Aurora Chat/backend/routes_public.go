package main

import (
	"net/http"
)

func registerPublicRoutes(mux *http.ServeMux) {
	// ==================== 公开接口（无需登录） ====================
	mux.HandleFunc("/api/send-code", handleSendCode)
	mux.HandleFunc("/api/register", handleRegister)
	mux.HandleFunc("/api/login", handleLogin)
	mux.HandleFunc("/api/reset-password", handleResetPassword)
	mux.HandleFunc("/api/send-reset-code", handleSendResetCode)
	mux.HandleFunc("/api/user/self-delete", authMiddleware(handleSelfDeleteUser))
	// 服务器登录（需要JWT用于开发者/管理员身份验证）
	mux.HandleFunc("/api/health", handleHealth)
	// 客户端签名密钥拉取（登录态恢复场景；动态 per-user 密钥，不内嵌 APK）
	mux.HandleFunc("/api/auth/client-sign-secret", authMiddleware(handleClientSignSecret))
	mux.HandleFunc("/api/app/version", handleAppVersion)
	mux.HandleFunc("/api/agent-app/version", handleAgentAppVersion)
	mux.HandleFunc("/api/qq/login", handleQQLogin)
	mux.HandleFunc("/qrlogin", handleQRCodeLoginPage)
	mux.HandleFunc("/api/qr/user", handlePublicQrUser)   // 落地页公开信息（无需登录）
	mux.HandleFunc("/api/qr/group", handlePublicQrGroup) // 群二维码落地页公开信息（无需登录）
	// 群头像的公开只读通路：/api/groups/avatar/{id} 需要登录，匿名扫码页引用它只有 401
	mux.HandleFunc("/api/qr/group/avatar", handlePublicGroupAvatar)
	// 群二维码落地页：正式页面是静态站根目录的 site/g.html；这里是后端备胎，
	// 静态文件缺失时由 nginx try_files 转过来（见 deploy/nginx-qr-landing.conf）。
	// /api/qr/g 是实测可达的挂载点（www 的 nginx 只代理 /api/ 等前缀），/g、/g.html 为别名。
	mux.HandleFunc("/api/qr/g", handleGroupQrLanding)
	mux.HandleFunc("/g", handleGroupQrLanding)
	mux.HandleFunc("/g.html", handleGroupQrLanding)
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
	// 站点服务器动态绑定：站点服务自动心跳上报端点，客户端认证后按卡密取回真实地址
	mux.HandleFunc("/api/client/register", handleClientRegister)
	mux.HandleFunc("/api/client/bootstrap", handleClientBootstrap)
	mux.HandleFunc("/api/debug/tcp-status", handleTCPStatusDebug)
}
