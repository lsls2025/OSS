package main

import (
	"net/http"
	"net/http/pprof"
	"os"
)

func registerCoreRoutes(mux *http.ServeMux) {
	// ==================== 五子棋 WebSocket（/ws，与旧 Node 服务协议兼容） ====================
	mux.HandleFunc("/ws", handleGomokuWS)
	// 邀请卡片探活：房间是否还存在（房主退出即销毁房间 → 卡片置为「已过期」）
	mux.HandleFunc("/api/gomoku/room", handleGomokuRoomStatus)

	// ==================== pprof 性能观测（/debug/pprof/*） ====================
	// B7 加固：默认关闭。仅当环境变量 PPROF_ENABLED=1 时挂载，
	// 且所有 pprof 路由均需先过 authMiddleware（登录态）与 pprofGuard（平台管理员/开发者）双重鉴权，
	// 防止匿名访问 dump 堆/CPU/goroutine 信息泄露内存中的 token/密钥。
	if os.Getenv("PPROF_ENABLED") == "1" {
		mux.HandleFunc("/debug/pprof/", authMiddleware(pprofGuard(pprof.Index)))
		mux.HandleFunc("/debug/pprof/cmdline", authMiddleware(pprofGuard(pprof.Cmdline)))
		mux.HandleFunc("/debug/pprof/profile", authMiddleware(pprofGuard(pprof.Profile)))
		mux.HandleFunc("/debug/pprof/symbol", authMiddleware(pprofGuard(pprof.Symbol)))
		mux.HandleFunc("/debug/pprof/trace", authMiddleware(pprofGuard(pprof.Trace)))
	}
}
