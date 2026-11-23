package main

import (
	"log"
	"net/http"
	"os"
	"path/filepath"
)

func registerStaticRoutes(mux *http.ServeMux, baseDir string) {
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

	// B6 加固：/api/spam/send 下线——前端无任何真实调用方，仅为内部调试接口；
	// handleSpamSend 本身亦已增加本机回环地址校验作为纵深防御，防止未来误挂载后公网可调。
	// mux.HandleFunc("/api/spam/send", handleSpamSend)
	// 客户端错误上报接口已下线：日志只存本机，不再自动外发。
	// mux.HandleFunc("/api/report-error", handleErrorReport)
	mux.HandleFunc("/api/server/log/add", authMiddleware(handleLogAdd))
	mux.HandleFunc("/api/server/logs", authMiddleware(handleLogGet))
}
