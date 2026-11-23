package main

import (
	"net/http"
)

func registerMiscRoutes(mux *http.ServeMux) {
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
	mux.HandleFunc("/api/sandbox/files/rename", authMiddleware(handleSandboxFilesRename))
	mux.HandleFunc("/api/sandbox/files/delete", authMiddleware(handleSandboxFilesDelete))
	mux.HandleFunc("/api/sandbox/files/mkdir", authMiddleware(handleSandboxFilesMkdir))
	mux.HandleFunc("/api/sandbox/files/upload", authMiddleware(handleSandboxFilesUpload))
	mux.HandleFunc("/api/sandbox/files/unzip", authMiddleware(handleSandboxFilesUnzip))
	mux.HandleFunc("/api/sandbox/files/zip-info", authMiddleware(handleSandboxFilesZipInfo))
}
