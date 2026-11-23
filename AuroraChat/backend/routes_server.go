package main

import (
	"net/http"
)

func registerServerRoutes(mux *http.ServeMux) {
	// ==================== 服务器接口（需要 JWT 认证） ====================
	mux.HandleFunc("/api/server/my", authMiddleware(handleGetMyServer))
	mux.HandleFunc("/api/server/status", authMiddleware(handleGetServerStatus))
	mux.HandleFunc("/api/server/shutdown", authMiddleware(handleShutdownServer))
	mux.HandleFunc("/api/server/restart", authMiddleware(handleRestartServer))
	// 服务器文件管理
	mux.HandleFunc("/api/server/files/create", authMiddleware(handleCreateServerFile))
	mux.HandleFunc("/api/server/files/create-folder", authMiddleware(handleCreateServerFolder))
	mux.HandleFunc("/api/server/files/upload", authMiddleware(handleUploadServerFile))
	mux.HandleFunc("/api/server/files/list", authMiddleware(handleListServerFiles))
	mux.HandleFunc("/api/server/files/download/", authMiddleware(handleDownloadServerFile))
	mux.HandleFunc("/api/server/files/delete", authMiddleware(handleDeleteServerFile))
	mux.HandleFunc("/api/server/files/zip-info", authMiddleware(handleServerFileZipInfo))
	mux.HandleFunc("/api/server/files/unzip", authMiddleware(handleServerFileUnzip))
	mux.HandleFunc("/api/server/files/move", authMiddleware(handleMoveServerFile))
	mux.HandleFunc("/api/server/files/breadcrumb", authMiddleware(handleGetFolderBreadcrumb))
	mux.HandleFunc("/api/server/files/update", authMiddleware(handleUpdateServerFile))
	mux.HandleFunc("/api/server/files/rename", authMiddleware(handleRenameServerFile))
	mux.HandleFunc("/api/server/files/search", authMiddleware(handleSearchServerFiles))

	// 注意：公开 /serve/ 访问已由 150 上的 site-server 统一处理（见 siteserver/main.go），
	// 业务后端不再本地应答 /serve/，避免"一半在新服务器、一半在旧服务器"的分裂。
}
