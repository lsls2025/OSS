package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// pprofGuard 为 pprof 路由做管理员/开发者权限二次校验（须先经 authMiddleware 登录鉴权，
// authMiddleware 已注入 X-User-ID 等请求头，checkAdminOrDevPermission 据此判断）。
func pprofGuard(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := checkAdminOrDevPermission(r); !ok {
			writeJSON(w, 403, "无权限访问", nil)
			return
		}
		next(w, r)
	}
}

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

// aiMediaExpiredDays AI 生成图的有效期（天）：与聊天图片/视频的保留期统一为 mediaRetentionDays。
// 原来写死 7 天，会出现「7 天就提示图片已过期、文件却还占着磁盘、与 30 天清理规则互相打架」的两套机制。
const aiMediaExpiredDays = mediaRetentionDays

// aiMediaExpired 判断 AI 生成图是否已过期（文件名以 ai_ 开头；用文件创建时间计算天数）。
func aiMediaExpired(name string, info os.FileInfo) bool {
	if !strings.HasPrefix(name, "ai_") {
		return false
	}
	return time.Since(info.ModTime()) > aiMediaExpiredDays*24*time.Hour
}

// handleChatMedia 提供聊天媒体文件（/chat-media/<文件名>）。
// AI 生成图（ai_ 前缀）超过 aiMediaExpiredDays（=图片视频保留期）天返回 410"图片已过期"，
// 之后由 cleanupOldMedia 统一回收物理文件；其余文件正常走静态服务。
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
	// 批次5 B3：公开直链统一安全响应头（图片内联预览，其余附件下载；危险类型强制 octet-stream + nosniff）
	applyFileResponseHeaders(w, name)
	http.ServeFile(w, r, fullPath)
}

func main() {
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	dataDir := filepath.Join(baseDir, "data")
	os.MkdirAll(dataDir, 0755)

	// 初始化 JWT 密钥（必须在任何 token 校验前；建议设置 JWT_SECRET 环境变量固定）
	setJWTSecret()

	// 初始化第三方 AI（SolitaryCryAI）密钥/上游配置（环境变量优先，源码兜底）
	setupAISolitaryCryConfig()

	// 初始化服务端静态加密密钥（必须在 db 读写前）
	initServerSecurity(dataDir)

	// 初始化数据库
	dbPath := filepath.Join(dataDir, "aurora_chat.db")
	initDB(dbPath)
	ensureFreeApiColumns() // 迁移：为 free_apis 增加测速/状态相关列（兼容旧库）
	ensurePaidApiTables()  // 初始化 paid_apis 表
	defer db.Close()

	// 初始化用户数据库目录（每个用户一个独立文件）
	initUserDB(dataDir)

	// 加载版本配置（从 version.txt）
	loadVersionConfig()

	// 把存量「已关机」状态补推给站点静态服务（站点服务重启后会丢内存态，不推就会把已关机站点放出来）。
	// 放 goroutine：不阻塞启动，也不因站点服务暂时不可达而卡住。
	go syncServerStatesToSiteServer()

	// 加载五子棋玩家档案（匹配/段位用，backend/data/gomoku_players.json）
	gomokuInit()

	// 初始化路由
	mux := http.NewServeMux()

	registerCoreRoutes(mux)
	registerPublicRoutes(mux)
	registerSocialRoutes(mux)
	registerDistributionRoutes(mux)
	registerServerRoutes(mux)
	registerGroupRoutes(mux)
	registerAdminRoutes(mux)
	registerMiscRoutes(mux)
	registerStaticRoutes(mux, baseDir)

	// 兜底路由（必须最后注册）：未注册路径不再停在裸 404 —— 接口给 JSON 404，
	// 页面类请求（含失效的老落地页、打错的链接）统一 302 回官网首页。详见 handleSiteFallback。
	mux.HandleFunc("/", handleSiteFallback)

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
	log.Println("  消息加密: AES-256-GCM 传输与静态加密")
	log.Println("  认证方式: JWT Bearer Token")
	log.Println("========================================")

	// 初始化社区功能
	InitCommunityDB()
	// 关注表：建在社区库之后 —— 关注成功要往 community_activities 写一条动态，
	// 顺序反了会在首次启动时写出「no such table」。
	InitFollowTables()
	InitCommunityStorage(baseDir)
	RegisterCommunityRoutes(mux, authMiddleware)

	// 初始化短视频功能（多码率 HLS / 发布者 / 点赞 / 评论）
	InitShortVideoDB()
	InitShortVideoStorage(baseDir)
	// 短视频总开关的文案表（sys_text_flags）
	initShortVideoGate()
	PurgeBrokenShortVideos()
	// 上线自愈（顺序不能变）：先把"无视频轨"的坏记录同步回滚到 orig.mp4——
	// 立刻恢复"大部分视频能播"的状态，不依赖 ffmpeg；再后台把非 H.264 源逐条转兼容格式。
	rollbackAndHealShortVideos()
	// 尺寸修复（一次性，后台串行）：历史 HLS 产物被 pad 成固定 16:9 画布，竖屏视频在客户端
	// 被排成横条、有效画面只剩约十分之一。比例是编码时烤进像素的，改滤镜对新上传生效，
	// 老产物必须重转才能修好；跑完写标记文件，之后启动不再重复扫描。
	retranscodePaddedShortVideos()
	// 转码能力摸底：服务器 ffmpeg 有哪些编码器，启动时打一次日志，出问题不再靠猜。
	probeFFmpegEncoders()
	RegisterShortVideoRoutes(mux, authMiddleware)

	// 初始化服务器文件存储
	initServerFilesStorage(baseDir)

	// 初始化文件分发存储（BackendCenter 板块一）
	initFileDistributionStorage(baseDir)

	// 站点文件迁移子命令：把历史文件按文件夹层级推送到独立站点服务（150），
	// 使文件管理里的访问地址可用。用法：./aurora-server migrate-site-files
	if len(os.Args) > 1 && os.Args[1] == "migrate-site-files" {
		migrateSiteFiles()
		return
	}

	// 初始化漂流瓶模块
	initDriftBottleTable()

	// 初始化沙盒模块
	initSandboxTables()

	// 初始化全局防御开关（"抵防"）
	initSysFlags()
	migrateGroupPermsNewKeys()

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

	// 转账超期退回扫描（每 60 秒）
	startTransferRefundSweeper()

	// 免费 API 服务端可达性探测（启动后探一次，之后每 5 分钟一轮）：
	// 「这条 API 是否对公网还活着」由服务器统一裁决，客户端本地测速失败不再改写全局状态。
	startFreeApiProbeLoop()

	// 定时清理超过 30 天的聊天图片/视频「文件」。
	// 机制：消息记录（文本/时间/结构）永久保留，私聊与群聊一律不删；只回收媒体物理文件释放磁盘。
	go func() {
		// 启动后延迟 1 分钟再跑首轮，避免和冷启动的建表/加载抢资源
		time.Sleep(1 * time.Minute)
		cleanupOldMedia(mediaRetentionDays)
		for {
			time.Sleep(6 * time.Hour)
			cleanupOldMedia(mediaRetentionDays)
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

	// HTTPS 启动（可选）：nginx 未做 API 反代时，可直接用 TLS 端口对外提供服务。
	// 证书优先取环境变量，其次取宝塔面板惯用的证书路径；都拿不到就【跳过 HTTPS】并打印原因。
	// 绝不默认生成自签证书——客户端启用了系统信任链 + 证书绑定（CERT_PINS），
	// 自签证书会让 App 直接连不上，比不开 HTTPS 更糟。
	if v := strings.ToLower(strings.TrimSpace(os.Getenv("ENABLE_HTTPS"))); v == "1" || v == "true" {
		httpsPort := strings.TrimSpace(os.Getenv("HTTPS_PORT"))
		if httpsPort == "" {
			httpsPort = "5443"
		}
		if !strings.HasPrefix(httpsPort, ":") {
			httpsPort = ":" + httpsPort
		}

		certFile := strings.TrimSpace(os.Getenv("HTTPS_CERT_FILE"))
		keyFile := strings.TrimSpace(os.Getenv("HTTPS_KEY_FILE"))
		if certFile == "" || keyFile == "" {
			domain := strings.TrimSpace(os.Getenv("HTTPS_DOMAIN"))
			if domain == "" {
				domain = "www.example.com"
			}
			base := "/www/server/panel/vhost/cert/" + domain
			certFile = base + "/fullchain.pem"
			keyFile = base + "/privkey.pem"
		}

		if _, err := os.Stat(certFile); err != nil {
			log.Printf("[HTTPS] 证书不可用(%s): %v，已跳过 HTTPS（HTTP 服务不受影响）", certFile, err)
		} else if _, err := os.Stat(keyFile); err != nil {
			log.Printf("[HTTPS] 私钥不可用(%s): %v，已跳过 HTTPS（HTTP 服务不受影响）", keyFile, err)
		} else {
			go func() {
				log.Printf("HTTPS 服务器启动: https://0.0.0.0%s（证书: %s）", httpsPort, certFile)
				tlsServer := &http.Server{
					Addr:              httpsPort,
					Handler:           handler,
					ReadTimeout:       120 * time.Second,
					ReadHeaderTimeout: 10 * time.Second,
					WriteTimeout:      120 * time.Second,
					IdleTimeout:       120 * time.Second,
				}
				// 这里绝不能 Fatalf：HTTPS 起不来不该把 HTTP 主服务一起带走
				if err := tlsServer.ListenAndServeTLS(certFile, keyFile); err != nil {
					log.Printf("[HTTPS] 启动失败: %v（HTTP 仍在运行）", err)
				}
			}()
		}
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
