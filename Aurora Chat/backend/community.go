package main

import (
	"bytes"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"image"
	"image/color"
	_ "image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ==================== 数据结构 ====================

type CommunityPost struct {
	ID            int64      `json:"id"`
	UserID        int64      `json:"user_id"`
	Username      string     `json:"username"`
	Title         string     `json:"title"`
	Content       string     `json:"content"`
	PostType      string     `json:"post_type"`
	CreatedAt     int64      `json:"created_at"`
	HasLiked      bool       `json:"has_liked"`
	LikesCount    int        `json:"likes_count"`
	CommentsCount int        `json:"comments_count"`
	IsAdult       bool       `json:"is_adult"`
	Resources     []Resource `json:"resources,omitempty"`
}

type Resource struct {
	ID       int64  `json:"id"`
	PostID   int64  `json:"post_id"`
	Type     string `json:"resource_type"`
	FileName string `json:"file_name"`
	FilePath string `json:"file_path"`
	FileSize int64  `json:"file_size"`
}

type CreatePostRequest struct {
	Title    string `json:"title"`
	Content  string `json:"content"`
	PostType string `json:"post_type"`
	IsAdult  bool   `json:"is_adult"`
}

var communityDataDir string

// ==================== 初始化 ====================

func InitCommunityDB() {
	// 逐条执行，避免多语句在已有不完整表时出错
	statements := []string{
		`CREATE TABLE IF NOT EXISTS community_posts (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL,
			title TEXT NOT NULL,
			content TEXT NOT NULL,
			created_at INTEGER NOT NULL
		)`,
		`CREATE INDEX IF NOT EXISTS idx_posts_user ON community_posts(user_id)`,
		`CREATE INDEX IF NOT EXISTS idx_posts_time ON community_posts(created_at DESC)`,
		`CREATE TABLE IF NOT EXISTS community_resources (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			post_id INTEGER NOT NULL,
			resource_type TEXT NOT NULL,
			file_name TEXT NOT NULL,
			file_path TEXT NOT NULL,
			file_size INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL
		)`,
		`CREATE INDEX IF NOT EXISTS idx_resources_post ON community_resources(post_id)`,
		`CREATE TABLE IF NOT EXISTS community_likes (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			post_id INTEGER NOT NULL,
			user_id INTEGER NOT NULL,
			created_at INTEGER NOT NULL,
			UNIQUE(post_id, user_id)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_likes_post ON community_likes(post_id)`,
		`CREATE TABLE IF NOT EXISTS community_comments (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			post_id INTEGER NOT NULL,
			user_id INTEGER NOT NULL,
			content TEXT NOT NULL,
			parent_id INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL
		)`,
		`CREATE INDEX IF NOT EXISTS idx_comments_post ON community_comments(post_id)`,
		`CREATE TABLE IF NOT EXISTS community_activities (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL,
			target_user_id INTEGER NOT NULL,
			action_type TEXT NOT NULL,
			target_type TEXT NOT NULL,
			target_id INTEGER NOT NULL,
			target_title TEXT NOT NULL DEFAULT '',
			comment_content TEXT NOT NULL DEFAULT '',
			created_at INTEGER NOT NULL
		)`,
		`CREATE INDEX IF NOT EXISTS idx_activities_target_user ON community_activities(target_user_id, created_at DESC)`,
	}
	for _, stmt := range statements {
		if _, err := db.Exec(stmt); err != nil {
			log.Fatalf("创建社区表失败: %v\nSQL: %s", err, stmt)
		}
	}
	// 兼容旧表：尝试添加 parent_id 列（已有列时会忽略）
	db.Exec("ALTER TABLE community_comments ADD COLUMN parent_id INTEGER NOT NULL DEFAULT 0")
	// 兼容旧表：尝试添加 post_type 列（已有列时会忽略）
	db.Exec("ALTER TABLE community_posts ADD COLUMN post_type TEXT NOT NULL DEFAULT 'post'")
	// 兼容旧表：尝试添加 deleted 列（已有列时会忽略）
	db.Exec("ALTER TABLE community_posts ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
	// 兼容旧表：尝试添加 is_adult 列（成人内容标记，已有列时会忽略）
	db.Exec("ALTER TABLE community_posts ADD COLUMN is_adult INTEGER NOT NULL DEFAULT 0")
	// 旧数据迁移：有附件的帖子标记为 resource
	db.Exec(`UPDATE community_posts SET post_type = 'resource' WHERE id IN (
		SELECT DISTINCT post_id FROM community_resources
	) AND post_type = 'post'`)
	log.Println("社区数据表已就绪")
}

func InitCommunityStorage(baseDir string) {
	dataDir := filepath.Join(baseDir, "data", "community")
	communityDataDir = dataDir
	for _, sub := range []string{"images", "videos", "audio", "files"} {
		os.MkdirAll(filepath.Join(dataDir, sub), 0755)
	}
	// 兼容旧路径
	if _, err := os.Stat(filepath.Join(baseDir, "community")); err == nil {
		communityDataDir = filepath.Join(baseDir, "community")
	}
	log.Printf("社区存储目录: %s", communityDataDir)
}

// ==================== 工具函数 ====================

func genFileName(ext string) string {
	b := make([]byte, 8)
	rand.Read(b)
	return fmt.Sprintf("%d_%s.%s", time.Now().UnixMilli(), hex.EncodeToString(b), strings.TrimPrefix(ext, "."))
}

func subDirFor(t string) string {
	switch t {
	case "image":
		return "images"
	case "video":
		return "videos"
	case "audio":
		return "audio"
	default:
		return "files"
	}
}

// ==================== API 路由注册 ====================

func RegisterCommunityRoutes(mux *http.ServeMux, am func(http.HandlerFunc) http.HandlerFunc) {
	mux.HandleFunc("/api/community/post/create", am(handleCreatePost))
	mux.HandleFunc("/api/community/posts", am(handleListPosts))
	mux.HandleFunc("/api/community/post/", am(handleGetPost))
	mux.HandleFunc("/api/community/delete", am(handleDeletePost))
	mux.HandleFunc("/api/community/upload", am(handleUploadFile))
	mux.HandleFunc("/api/community/like/toggle", am(handleToggleLike))
	mux.HandleFunc("/api/community/comment/add", am(handleAddComment))
	mux.HandleFunc("/api/community/comments/", am(handleGetComments))
	mux.HandleFunc("/api/community/activities", am(handleGetActivities))
	mux.HandleFunc("/api/community/activities/clear", am(handleClearActivities))
	// 文件服务（无需认证）
	mux.HandleFunc("/api/community/files/", handleServeFile)
	mux.HandleFunc("/api/community/thumb/", handleThumbnail)
	// 音频网页播放器
	mux.HandleFunc("/audio-player", handleAudioPlayer)
}

// ==================== 创建帖子 ====================

func handleCreatePost(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	// 抵防：社区禁发 —— 开启后用户无法发布帖子/资源（开发者除外）
	if getFlag("community_ban") && !isDeveloperUser(userID) {
		writeJSON(w, 403, "社区已开启禁发，暂不可发布", nil)
		return
	}
	// 检查用户是否已被注销
	var uname string
	if err := db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&uname); err != nil || uname == "" || uname == "注销用户" {
		writeJSON(w, 403, "账号异常，无法发布", nil)
		return
	}
	var req CreatePostRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.Title = strings.TrimSpace(req.Title)
	req.Content = html.EscapeString(req.Content)
	if req.PostType == "" {
		req.PostType = "post"
	}
	if req.Title == "" {
		writeJSON(w, 400, "标题不能为空", nil)
		return
	}
	now := time.Now().Unix()
	isAdultVal := 0
	if req.IsAdult {
		isAdultVal = 1
	}
	result, err := db.Exec("INSERT INTO community_posts (user_id, title, content, post_type, is_adult, created_at) VALUES (?, ?, ?, ?, ?, ?)",
		userID, req.Title, req.Content, req.PostType, isAdultVal, now)
	if err != nil {
		log.Printf("创建帖子失败: %v", err)
		writeJSON(w, 500, "创建失败", nil)
		return
	}
	postID, _ := result.LastInsertId()
	username := getUserName(userID)
	log.Printf("用户 %d 创建%s %d: %s (is_adult=%d)", userID, map[bool]string{true: "资源", false: "帖子"}[req.PostType == "resource"], postID, req.Title, isAdultVal)
	writeJSON(w, 200, "创建成功", map[string]interface{}{
		"id":         postID,
		"user_id":    userID,
		"username":   username,
		"title":      req.Title,
		"content":    req.Content,
		"post_type":  req.PostType,
		"is_adult":   req.IsAdult,
		"created_at": now,
	})
}

func getUserName(userID int64) string {
	var name string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&name)
	if name == "" {
		return "用户"
	}
	return name
}

// ==================== 列表帖子 ====================

func handleListPosts(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit < 1 || limit > 50 {
		limit = 20
	}
	offset := (page - 1) * limit
	postType := r.URL.Query().Get("type")
	// has_res: 1=只要带附件的，0=只要纯图文（按真实附件判定，不受 post_type 影响）
	hasRes := r.URL.Query().Get("has_res")

	log.Printf("[列表追踪] 步骤1-请求到达: page=%d, limit=%d, offset=%d, type=%q, userID=%d", page, limit, offset, postType, getCurrentUserID(r))

	type PostItem struct {
		ID               int64  `json:"id"`
		UserID           int64  `json:"user_id"`
		Username         string `json:"username"`
		Title            string `json:"title"`
		Content          string `json:"content"`
		PostType         string `json:"post_type"`
		CreatedAt        int64  `json:"created_at"`
		ResTypes         string `json:"res_types"`
		ResExts          string `json:"res_exts"`
		ResCount         int    `json:"res_count"`
		LikesCount       int    `json:"likes_count"`
		CommentsCount    int    `json:"comments_count"`
		HasLiked         bool   `json:"has_liked"`
		IsAdult          bool   `json:"is_adult"`
		FirstResourceURL string `json:"first_resource_url"`
		ImageURLs        []string `json:"image_urls"`
	}
	currentUserID := getCurrentUserID(r)
	keyword := strings.TrimSpace(r.URL.Query().Get("q"))

	// 构建查询
	var countQuery string
	var listQuery string
	var countArgs []interface{}
	var listArgs []interface{}

	whereClause := ""
	if postType != "" {
		whereClause = "WHERE p.post_type = ?"
		countArgs = append(countArgs, postType)
		listArgs = append(listArgs, postType)
	}
	if keyword != "" {
		if whereClause == "" {
			whereClause = "WHERE (p.title LIKE ? OR p.content LIKE ?)"
		} else {
			whereClause += " AND (p.title LIKE ? OR p.content LIKE ?)"
		}
		kw := "%" + keyword + "%"
		countArgs = append(countArgs, kw, kw)
		listArgs = append(listArgs, kw, kw)
	}
	if hasRes == "1" || hasRes == "0" {
		cond := "EXISTS (SELECT 1 FROM community_resources r WHERE r.post_id = p.id)"
		if hasRes == "0" {
			cond = "NOT " + cond
		}
		if whereClause == "" {
			whereClause = "WHERE " + cond
		} else {
			whereClause += " AND " + cond
		}
	}

	// mine=1：只返回当前登录用户自己的帖子（服务端强制，不依赖前端传参）
	if r.URL.Query().Get("mine") == "1" {
		if whereClause == "" {
			whereClause = "WHERE p.user_id = ?"
		} else {
			whereClause += " AND p.user_id = ?"
		}
		countArgs = append(countArgs, currentUserID)
		listArgs = append(listArgs, currentUserID)
	}

	listArgs = append(listArgs, limit, offset)

	// 硬核软删除过滤：永远不返回已删帖子
	deletedFilter := " (p.deleted IS NULL OR p.deleted = 0)"
	if whereClause == "" {
		whereClause = "WHERE" + deletedFilter
	} else {
		whereClause += " AND" + deletedFilter
	}

	countQuery = "SELECT COUNT(*) FROM community_posts p " + whereClause
	listQuery = `SELECT p.id, p.user_id, u.username, p.title, p.content, p.post_type, p.created_at, COALESCE(p.is_adult, 0)
		FROM community_posts p JOIN users u ON p.user_id = u.id ` + whereClause +
		` ORDER BY p.created_at DESC LIMIT ? OFFSET ?`

	log.Printf("[列表追踪] 步骤2-SQL构建完成: listQuery=%q, countQuery=%q, listArgs=%v, countArgs=%v", listQuery, countQuery, listArgs, countArgs)

	rows, err := db.Query(listQuery, listArgs...)
	if err != nil {
		log.Printf("[列表追踪] 步骤3-列表查询失败: err=%v, sql=%q, args=%v", err, listQuery, listArgs)
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()

	var posts []PostItem
	for rows.Next() {
		var p PostItem
		var adultFlag int
		rows.Scan(&p.ID, &p.UserID, &p.Username, &p.Title, &p.Content, &p.PostType, &p.CreatedAt, &adultFlag)
		p.IsAdult = adultFlag != 0
		// 获取资源类型摘要和扩展名
		var extCount int
		db.QueryRow("SELECT GROUP_CONCAT(DISTINCT resource_type), GROUP_CONCAT(DISTINCT SUBSTR(file_name, INSTR(file_name, '.') + 1)), COUNT(*) FROM community_resources WHERE post_id = ?", p.ID).Scan(&p.ResTypes, &p.ResExts, &extCount)
		p.ResCount = extCount
		// 获取点赞数和评论数
		db.QueryRow("SELECT COUNT(*) FROM community_likes WHERE post_id = ?", p.ID).Scan(&p.LikesCount)
		db.QueryRow("SELECT COUNT(*) FROM community_comments WHERE post_id = ?", p.ID).Scan(&p.CommentsCount)
		// 检查当前用户是否已点赞
		var likeCount int
		db.QueryRow("SELECT COUNT(*) FROM community_likes WHERE post_id = ? AND user_id = ?", p.ID, currentUserID).Scan(&likeCount)
		p.HasLiked = likeCount > 0
		// 获取第一个图片资源的路径（用于列表缩略图），补全为可访问的相对 URL 路径
		var firstResPath string
		db.QueryRow("SELECT file_path FROM community_resources WHERE post_id = ? AND resource_type = 'image' ORDER BY id LIMIT 1", p.ID).Scan(&firstResPath)
		if firstResPath != "" {
			p.FirstResourceURL = "/api/community/files/" + firstResPath
		}
		// 获取图片资源路径列表（最多 6 张），供列表横向滑动预览
		p.ImageURLs = []string{}
		if resRows, e := db.Query("SELECT file_path FROM community_resources WHERE post_id = ? AND resource_type = 'image' ORDER BY id LIMIT 6", p.ID); e == nil {
			for resRows.Next() {
				var fp string
				if resRows.Scan(&fp) == nil && fp != "" {
					p.ImageURLs = append(p.ImageURLs, "/api/community/files/"+fp)
				}
			}
			resRows.Close()
		}
		posts = append(posts, p)
	}
	if posts == nil {
		posts = []PostItem{}
	}
	postIDs := make([]int64, len(posts))
	for i, p := range posts {
		postIDs[i] = p.ID
	}
	log.Printf("[列表追踪] 步骤3-列表查询返回 %d 条帖子: ids=%v", len(posts), postIDs)

	var total int
	countErr := db.QueryRow(countQuery, countArgs...).Scan(&total)
	if countErr != nil {
		log.Printf("[列表追踪] 步骤4-COUNT查询失败: err=%v, sql=%q, args=%v", countErr, countQuery, countArgs)
	} else {
		log.Printf("[列表追踪] 步骤4-COUNT结果: total=%d", total)
	}

	log.Printf("[列表追踪] 步骤5-返回响应: posts=%d, total=%d, page=%d, limit=%d", len(posts), total, page, limit)
	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"posts": posts,
		"total": total,
		"page":  page,
		"limit": limit,
	})
}

// ==================== 获取单个帖子 ====================

func handleGetPost(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	idStr := strings.TrimPrefix(r.URL.Path, "/api/community/post/")
	postID, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil || postID <= 0 {
		writeJSON(w, 400, "帖子ID无效", nil)
		return
	}

	var p CommunityPost
	var adultFlag int
	err = db.QueryRow(`SELECT p.id, p.user_id, u.username, p.title, p.content, p.post_type, p.created_at, COALESCE(p.is_adult, 0)
		FROM community_posts p JOIN users u ON p.user_id = u.id WHERE p.id = ?`, postID).
		Scan(&p.ID, &p.UserID, &p.Username, &p.Title, &p.Content, &p.PostType, &p.CreatedAt, &adultFlag)
	p.IsAdult = adultFlag != 0
	if err != nil {
		writeJSON(w, 404, "帖子不存在", nil)
		return
	}

	// 获取点赞数和评论数
	db.QueryRow("SELECT COUNT(*) FROM community_likes WHERE post_id = ?", postID).Scan(&p.LikesCount)
	db.QueryRow("SELECT COUNT(*) FROM community_comments WHERE post_id = ?", postID).Scan(&p.CommentsCount)
	// 检查当前用户是否已点赞
	currentUserID := getCurrentUserID(r)
	var likeCount int
	db.QueryRow("SELECT COUNT(*) FROM community_likes WHERE post_id = ? AND user_id = ?", postID, currentUserID).Scan(&likeCount)
	p.HasLiked = likeCount > 0

	// 加载资源
	resRows, err := db.Query("SELECT id, post_id, resource_type, file_name, file_path, file_size, created_at FROM community_resources WHERE post_id = ? ORDER BY id", postID)
	if err == nil {
		defer resRows.Close()
		for resRows.Next() {
			var r Resource
			var createdAt int64
			resRows.Scan(&r.ID, &r.PostID, &r.Type, &r.FileName, &r.FilePath, &r.FileSize, &createdAt)
			r.FilePath = "/api/community/files/" + r.FilePath
			p.Resources = append(p.Resources, r)
		}
	}
	if p.Resources == nil {
		p.Resources = []Resource{}
	}

	writeJSON(w, 200, "获取成功", p)
}

// ==================== 上传文件 ====================

func handleUploadFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	// 抵防：社区禁发 —— 开启后用户无法上传资源（开发者除外）
	if getFlag("community_ban") && !isDeveloperUser(userID) {
		writeJSON(w, 403, "社区已开启禁发，暂不可上传", nil)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, 500<<20) // 500MB
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeJSON(w, 400, "文件太大或格式错误", nil)
		return
	}

	file, header, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, "请选择文件", nil)
		return
	}
	defer file.Close()

	postIDStr := r.FormValue("post_id")
	postID, _ := strconv.ParseInt(postIDStr, 10, 64)
	resourceType := r.FormValue("resource_type")
	ext := strings.ToLower(strings.TrimPrefix(filepath.Ext(header.Filename), "."))

	// 如果前端没有显式指定 resource_type，再根据扩展名自动判断
	if resourceType == "" {
		switch ext {
		case "png", "jpg", "jpeg", "gif", "webp", "bmp":
			resourceType = "image"
		case "mp4", "webm", "avi", "mov", "mkv":
			resourceType = "video"
		case "mp3", "wav", "ogg", "aac", "flac":
			resourceType = "audio"
		default:
			resourceType = "file"
		}
	}

	subDir := subDirFor(resourceType)
	saveName := genFileName(ext)
	saveDir := filepath.Join(communityDataDir, subDir)
	os.MkdirAll(saveDir, 0755)
	savePath := filepath.Join(saveDir, saveName)

	dst, err := os.Create(savePath)
	if err != nil {
		writeJSON(w, 500, "保存文件失败", nil)
		return
	}
	defer dst.Close()

	written, _ := io.Copy(dst, file)
	relPath := subDir + "/" + saveName

	now := time.Now().Unix()
	result, err := db.Exec("INSERT INTO community_resources (post_id, resource_type, file_name, file_path, file_size, created_at) VALUES (?, ?, ?, ?, ?, ?)",
		postID, resourceType, header.Filename, relPath, written, now)
	if err != nil {
		writeJSON(w, 500, "记录资源失败", nil)
		return
	}
	resID, _ := result.LastInsertId()

	// 音频文件自动转 AAC（原始 MP3 会被删除）
	audioExts := map[string]bool{"mp3": true, "wav": true, "ogg": true, "flac": true, "m4a": true, "wma": true}
	if audioExts[ext] && ext != "aac" {
		aacPath := strings.TrimSuffix(savePath, "."+ext) + ".aac"
		aacRelPath := strings.TrimSuffix(relPath, "."+ext) + ".aac"
		cmd := exec.Command("ffmpeg", "-i", savePath, "-c:a", "aac", "-b:a", "128k", "-y", aacPath)
		if err := cmd.Run(); err == nil {
			os.Remove(savePath) // 删除原始文件
			savePath = aacPath
			relPath = aacRelPath
			if fi, err := os.Stat(aacPath); err == nil {
				written = fi.Size()
			}
			//  更新数据库中的 file_path 和 file_size
			db.Exec("UPDATE community_resources SET file_path = ?, file_size = ? WHERE id = ?", relPath, written, resID)
			log.Printf("音频已转AAC: %s -> %s", header.Filename, aacRelPath)
		} else {
			log.Printf("ffmpeg转AAC失败 (保留原文件): %v", err)
		}
	}

	writeJSON(w, 200, "上传成功", map[string]interface{}{
		"id":            resID,
		"resource_type": resourceType,
		"file_name":     header.Filename,
		"file_path":     "/api/community/files/" + relPath,
		"file_size":     written,
	})
}

// ==================== 文件服务 ====================

func handleServeFile(w http.ResponseWriter, r *http.Request) {
	filePath := strings.TrimPrefix(r.URL.Path, "/api/community/files/")
	if filePath == "" || strings.Contains(filePath, "..") {
		http.NotFound(w, r)
		return
	}
	fullPath := filepath.Join(communityDataDir, filepath.Clean(filePath))
	if _, err := os.Stat(fullPath); os.IsNotExist(err) {
		http.NotFound(w, r)
		return
	}

	ext := strings.ToLower(filepath.Ext(fullPath))
	switch ext {
	case ".png", ".jpg", ".jpeg", ".gif", ".webp":
		w.Header().Set("Content-Type", "image/"+strings.TrimPrefix(ext, "."))
	case ".mp4":
		w.Header().Set("Content-Type", "video/mp4")
	case ".webm":
		w.Header().Set("Content-Type", "video/webm")
	case ".mp3":
		w.Header().Set("Content-Type", "audio/mpeg")
	case ".wav":
		w.Header().Set("Content-Type", "audio/wav")
	case ".ogg":
		w.Header().Set("Content-Type", "audio/ogg")
	case ".aac":
		w.Header().Set("Content-Type", "audio/aac")
	default:
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeFile(w, r, fullPath)
}

// ==================== 缩略图服务 ====================

// handleThumbnail 按宽度等比缩放图片并返回缩略图，按尺寸落盘缓存。
// 路径约定：/api/community/thumb/<相对路径>?w=240
func handleThumbnail(w http.ResponseWriter, r *http.Request) {
	rel := strings.TrimPrefix(r.URL.Path, "/api/community/thumb/")
	rel = strings.Trim(rel, "/")
	if rel == "" || strings.Contains(rel, "..") {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}
	fullPath := filepath.Join(communityDataDir, filepath.Clean(rel))
	info, err := os.Stat(fullPath)
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}

	// 目标宽度（限制范围，防止滥用）
	targetW := 240
	if v := r.URL.Query().Get("w"); v != "" {
		if n, e := strconv.Atoi(v); e == nil && n > 0 && n <= 1000 {
			targetW = n
		}
	}

	// 命中缓存直接返回
	cachePath := fullPath + fmt.Sprintf(".thumb_%d", targetW)
	if cf, e := os.Open(cachePath); e == nil {
		defer cf.Close()
		serveThumbFile(w, r, cf, cachePath)
		return
	}

	// 解码原图
	src, err := os.Open(fullPath)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	img, format, err := image.Decode(src)
	src.Close()
	if err != nil {
		// 无法解码（如 webp）则直接转发原文件
		if orig, e := os.Open(fullPath); e == nil {
			defer orig.Close()
			serveThumbFile(w, r, orig, fullPath)
			return
		}
		http.Error(w, "decode failed", http.StatusBadRequest)
		return
	}
	b := img.Bounds()
	sw := b.Dx()
	sh := b.Dy()

	// 原图已足够小则直接转发原文件
	if sw <= targetW {
		if orig, e := os.Open(fullPath); e == nil {
			defer orig.Close()
			serveThumbFile(w, r, orig, fullPath)
			return
		}
	}

	targetH := sh * targetW / sw
	if targetH < 1 {
		targetH = 1
	}
	dst := resizeBilinear(img, targetW, targetH)

	var buf bytes.Buffer
	ctype := "image/jpeg"
	switch format {
	case "png":
		ctype = "image/png"
		_ = png.Encode(&buf, dst)
	default:
		// gif 仅取首帧转 jpeg
		_ = jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 85})
	}
	_ = os.WriteFile(cachePath, buf.Bytes(), 0644)

	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.Write(buf.Bytes())
}

// serveThumbFile 以合适 Content-Type 与缓存头输出图片文件
func serveThumbFile(w http.ResponseWriter, r *http.Request, f *os.File, path string) {
	ext := strings.ToLower(filepath.Ext(path))
	ctype := "image/jpeg"
	switch ext {
	case ".png":
		ctype = "image/png"
	case ".gif":
		ctype = "image/gif"
	case ".webp":
		ctype = "image/webp"
	}
	w.Header().Set("Content-Type", ctype)
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeContent(w, r, path, time.Time{}, f)
}

// resizeBilinear 用双线性插值将图片缩放为目标尺寸（纯标准库实现）
func resizeBilinear(src image.Image, dw, dh int) *image.RGBA {
	b := src.Bounds()
	sw := b.Dx()
	sh := b.Dy()
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	lerp := func(a, b uint32, t float64) uint32 {
		return uint32(float64(a) + (float64(b)-float64(a))*t)
	}
	for y := 0; y < dh; y++ {
		fy := float64(y) * float64(sh-1) / float64(dh-1)
		y0 := int(fy)
		y1 := y0 + 1
		if y1 > sh-1 {
			y1 = sh - 1
		}
		dy := fy - float64(y0)
		for x := 0; x < dw; x++ {
			fx := float64(x) * float64(sw-1) / float64(dw-1)
			x0 := int(fx)
			x1 := x0 + 1
			if x1 > sw-1 {
				x1 = sw - 1
			}
			dx := fx - float64(x0)
			c00 := src.At(x0, y0)
			c10 := src.At(x1, y0)
			c01 := src.At(x0, y1)
			c11 := src.At(x1, y1)
			r00, g00, b00, a00 := c00.RGBA()
			r10, g10, b10, a10 := c10.RGBA()
			r01, g01, b01, a01 := c01.RGBA()
			r11, g11, b11, a11 := c11.RGBA()
			r := lerp(lerp(r00, r10, dx), lerp(r01, r11, dx), dy) >> 8
			g := lerp(lerp(g00, g10, dx), lerp(g01, g11, dx), dy) >> 8
			bl := lerp(lerp(b00, b10, dx), lerp(b01, b11, dx), dy) >> 8
			al := lerp(lerp(a00, a10, dx), lerp(a01, a11, dx), dy) >> 8
			dst.SetRGBA(x, y, color.RGBA{uint8(r), uint8(g), uint8(bl), uint8(al)})
		}
	}
	return dst
}

// 初始化群聊资源（main.go 中还没调用，备用）
func init() {
	// init in main.go manually
}

// ==================== 删除帖子 ====================

// execWithRetry 带重试的数据库执行函数，解决 SQLITE_BUSY 问题
func execWithRetry(tx *sql.Tx, query string, args ...interface{}) error {
	var lastErr error
	for i := 0; i < 5; i++ {
		_, lastErr = tx.Exec(query, args...)
		if lastErr == nil {
			return nil
		}
		// 仅对 SQLITE_BUSY 重试
		if !strings.Contains(lastErr.Error(), "busy") && !strings.Contains(lastErr.Error(), "locked") {
			return lastErr
		}
		time.Sleep(time.Duration(i*50) * time.Millisecond)
	}
	return lastErr
}

func handleDeletePost(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		log.Printf("[删除追踪] 拒绝非POST请求: method=%s", r.Method)
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	log.Printf("[删除追踪] 步骤1-请求到达: userID=%d, 来源IP=%s", userID, r.RemoteAddr)
	if userID == 0 {
		log.Printf("[删除追踪] 步骤1-终止: userID=0, 未登录")
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		PostID int64 `json:"post_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		log.Printf("[删除追踪] 步骤2-JSON解析失败: err=%v", err)
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	log.Printf("[删除追踪] 步骤2-请求体解析成功: postID=%d", req.PostID)
	if req.PostID <= 0 {
		log.Printf("[删除追踪] 步骤2-终止: 无效postID=%d", req.PostID)
		writeJSON(w, 400, "帖子ID无效", nil)
		return
	}

	// 查询帖子作者（最多重试3次避免 SQLITE_BUSY）
	var authorID int64
	var title string
	var err error
	for i := 0; i < 3; i++ {
		err = db.QueryRow("SELECT user_id, title FROM community_posts WHERE id = ?", req.PostID).Scan(&authorID, &title)
		if err == nil {
			break
		}
		if i < 2 && (strings.Contains(err.Error(), "busy") || strings.Contains(err.Error(), "locked")) {
			log.Printf("[删除追踪] 步骤3-SQLITE_BUSY 重试: i=%d, postID=%d", i, req.PostID)
			time.Sleep(time.Duration(i*50+50) * time.Millisecond)
			continue
		}
	}
	if err != nil {
		log.Printf("[删除追踪] 步骤3-帖子不存在: postID=%d, err=%v", req.PostID, err)
		writeJSON(w, 404, "帖子不存在", nil)
		return
	}
	log.Printf("[删除追踪] 步骤3-帖子存在: postID=%d, authorID=%d, title=%q", req.PostID, authorID, title)

	// 权限检查：仅开发者（ID=1）或帖子作者可删除
	if userID != 1 && userID != authorID {
		log.Printf("[删除追踪] 步骤4-权限不足: userID=%d != authorID=%d", userID, authorID)
		writeJSON(w, 403, "无权删除该帖子", nil)
		return
	}
	log.Printf("[删除追踪] 步骤4-权限通过: userID=%d == authorID=%d", userID, authorID)

	// ── 步骤5: 执行 UPDATE 软删除 ──
	log.Printf("[删除追踪] 步骤5-执行UPDATE: SET deleted=1 WHERE id=%d", req.PostID)
	result, err := db.Exec("UPDATE community_posts SET deleted = 1 WHERE id = ?", req.PostID)
	if err != nil {
		log.Printf("[删除追踪] 步骤5-UPDATE失败: id=%d, err=%v", req.PostID, err)
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	affected, _ := result.RowsAffected()
	log.Printf("[删除追踪] 步骤5-UPDATE成功: id=%d, rowsAffected=%d", req.PostID, affected)

	// ── 步骤6: 读取验证 ──
	var deletedVal int
	verifyErr := db.QueryRow("SELECT COALESCE(deleted,0) FROM community_posts WHERE id = ?", req.PostID).Scan(&deletedVal)
	if verifyErr != nil {
		log.Printf("[删除追踪] 步骤6-验证查询失败: id=%d, err=%v", req.PostID, verifyErr)
	} else {
		log.Printf("[删除追踪] 步骤6-验证查询: id=%d, deleted=%d (1=已删,0=未删)", req.PostID, deletedVal)
		if deletedVal != 1 {
			log.Printf("[删除追踪] ⚠️ 步骤6-验证失败: deleted=%d, 应为1! 数据可能未正确更新!", deletedVal)
		}
	}

	// 后台异步清理关联资源（不阻塞响应，失败不影响帖子可见性）
	go func() {
		log.Printf("[删除追踪] 步骤7(异步)-开始清理资源: postID=%d", req.PostID)
		// 删除关联的资源文件
		resRows, err := db.Query("SELECT file_path FROM community_resources WHERE post_id = ?", req.PostID)
		if err == nil {
			for resRows.Next() {
				var filePath string
				resRows.Scan(&filePath)
				fullPath := filepath.Join(communityDataDir, filePath)
				if err := os.Remove(fullPath); err != nil && !os.IsNotExist(err) {
					log.Printf("[删除追踪] 清理资源文件失败: %s, err=%v", fullPath, err)
				}
			}
			resRows.Close()
		}
		// 异步删除关联数据库记录（不阻塞客户端响应）
		db.Exec("DELETE FROM community_resources WHERE post_id = ?", req.PostID)
		db.Exec("DELETE FROM community_likes WHERE post_id = ?", req.PostID)
		db.Exec("DELETE FROM community_comments WHERE post_id = ?", req.PostID)
		db.Exec("DELETE FROM community_activities WHERE target_id = ? AND target_type IN ('post','resource')", req.PostID)
		log.Printf("[删除追踪] 步骤7(异步)-资源清理完成: post_id=%d", req.PostID)
	}()

	log.Printf("[删除追踪] 步骤8-返回成功响应: id=%d, title=%q, by user=%d", req.PostID, title, userID)
	writeJSON(w, 200, "删除成功", nil)
}

// ==================== 音频网页播放器 ====================

func handleAudioPlayer(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	html := `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>音频播放器 - Aurora Chat</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #fff; color: #1F2937; min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 20px; }
.container { width: 100%; max-width: 480px; padding: 40px 24px; }
.logo { text-align: center; margin-bottom: 32px; }
.logo h1 { font-size: 20px; font-weight: 700; color: #1E40AF; }
.logo p { font-size: 13px; color: #9CA3AF; margin-top: 4px; }
.info { text-align: center; margin-bottom: 24px; padding: 16px; background: #F8F9FA; border-radius: 12px; }
.info .name { font-size: 15px; font-weight: 600; color: #1F2937; word-break: break-all; }
.info .uploader { font-size: 12px; color: #9CA3AF; margin-top: 4px; }
.player { margin-top: 8px; }
.controls { display: flex; align-items: center; justify-content: center; gap: 20px; margin-bottom: 20px; }
.ctrl-btn { width: 56px; height: 56px; border-radius: 50%; border: none; background: #1E40AF; color: #fff; font-size: 24px; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: background 0.2s; }
.ctrl-btn:hover { background: #1E3A8A; }
.ctrl-btn:active { transform: scale(0.95); }
.progress-wrap { margin-bottom: 16px; }
.progress-bar { position: relative; width: 100%; height: 6px; background: #E5E7EB; border-radius: 3px; cursor: pointer; }
.progress-fill { height: 100%; background: #1E40AF; border-radius: 3px; width: 0%; transition: width 0.1s; }
.time { display: flex; justify-content: space-between; font-size: 12px; color: #9CA3AF; margin-top: 4px; }
.speed { display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 16px; }
.speed label { font-size: 13px; color: #6B7280; }
.speed select { padding: 4px 8px; border: 1px solid #D1D5DB; border-radius: 6px; font-size: 13px; color: #1F2937; background: #fff; outline: none; cursor: pointer; }
.speed select:focus { border-color: #1E40AF; }
.msg { text-align: center; color: #EF4444; font-size: 14px; display: none; margin-top: 12px; }
.footer { text-align: center; margin-top: 32px; font-size: 12px; color: #D1D5DB; }
</style>
</head>
<body>
<div class="container">
<div class="logo"><h1>♪ Aurora Audio</h1><p>Aurora Chat 官方音频播放器</p></div>
<div class="info"><div class="name" id="fileName">加载中...</div><div class="uploader" id="uploaderInfo"></div></div>
<div class="player">
<div class="controls"><button class="ctrl-btn" id="playBtn" onclick="togglePlay()">▶</button></div>
<div class="progress-wrap">
<div class="progress-bar" id="progressBar" onclick="seek(event)">
<div class="progress-fill" id="progressFill"></div>
</div>
<div class="time"><span id="currentTime">00:00</span><span id="totalTime">00:00</span></div>
</div>
<div class="speed"><label>播放速度</label><select id="speedSelect" onchange="changeSpeed()"><option value="0.5">0.5x</option><option value="0.75">0.75x</option><option value="1" selected>1x</option><option value="1.25">1.25x</option><option value="1.5">1.5x</option><option value="2">2x</option></select></div>
<div class="msg" id="errorMsg"></div>
</div>
<div class="footer">Powered by Aurora Chat</div>
</div>
<script>
const url = new URL(location.href);
const audioUrl = url.searchParams.get('url') || '';
const fileName = url.searchParams.get('name') || '未知音频';
const uploader = url.searchParams.get('user') || '';
document.getElementById('fileName').textContent = fileName;
document.getElementById('uploaderInfo').textContent = uploader ? '发布者: ' + uploader : '';
const audio = new Audio(audioUrl);
const playBtn = document.getElementById('playBtn');
const progressFill = document.getElementById('progressFill');
const currentTime = document.getElementById('currentTime');
const totalTime = document.getElementById('totalTime');
const errorMsg = document.getElementById('errorMsg');
const speedSelect = document.getElementById('speedSelect');
audio.preload = 'auto';
function fmt(t) { const m = Math.floor(t/60); const s = Math.floor(t%60); return m+':'+(s<10?'0':'')+s; }
audio.addEventListener('loadedmetadata', function() { totalTime.textContent = fmt(audio.duration); });
audio.addEventListener('timeupdate', function() { if(audio.duration) { progressFill.style.width = (audio.currentTime/audio.duration*100)+'%'; currentTime.textContent = fmt(audio.currentTime); } });
audio.addEventListener('ended', function() { playBtn.textContent = '▶'; progressFill.style.width = '0%'; currentTime.textContent = '00:00'; });
audio.addEventListener('error', function() { errorMsg.style.display = 'block'; errorMsg.textContent = '无法加载音频，请检查网络或文件是否存在'; });
function togglePlay() { if(audio.paused) { audio.play().then(function(){playBtn.textContent='⏸';}).catch(function(e){errorMsg.style.display='block';errorMsg.textContent='播放失败: '+e.message;}); } else { audio.pause(); playBtn.textContent='▶'; } }
function seek(e) { const bar = document.getElementById('progressBar'); const rect = bar.getBoundingClientRect(); const p = (e.clientX-rect.left)/rect.width; if(audio.duration) { audio.currentTime = p * audio.duration; } }
function changeSpeed() { audio.playbackRate = parseFloat(speedSelect.value); }
document.addEventListener('keydown', function(e) { if(e.code === 'Space') { e.preventDefault(); togglePlay(); } });
</script>
</body>
</html>`
	w.Write([]byte(html))
}

// ==================== 点赞/取消点赞 ====================

func handleToggleLike(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		PostID int64 `json:"post_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.PostID <= 0 {
		writeJSON(w, 400, "帖子ID无效", nil)
		return
	}

	// 检查帖子是否存在
	var exists int
	db.QueryRow("SELECT COUNT(*) FROM community_posts WHERE id = ?", req.PostID).Scan(&exists)
	if exists == 0 {
		writeJSON(w, 404, "帖子不存在", nil)
		return
	}

	// 检查是否已点赞
	var liked int
	db.QueryRow("SELECT COUNT(*) FROM community_likes WHERE post_id = ? AND user_id = ?", req.PostID, userID).Scan(&liked)

	if liked > 0 {
		// 取消点赞
		_, err := db.Exec("DELETE FROM community_likes WHERE post_id = ? AND user_id = ?", req.PostID, userID)
		if err != nil {
			log.Printf("取消点赞失败: %v", err)
			writeJSON(w, 500, "操作失败", nil)
			return
		}
	} else {
		// 添加点赞
		now := time.Now().Unix()
		_, err := db.Exec("INSERT INTO community_likes (post_id, user_id, created_at) VALUES (?, ?, ?)", req.PostID, userID, now)
		if err != nil {
			log.Printf("点赞失败: %v", err)
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		// 记录动态（通知帖子作者）
		var postAuthorID int64
		var postTitle string
		var postType string
		db.QueryRow("SELECT user_id, title, post_type FROM community_posts WHERE id = ?", req.PostID).Scan(&postAuthorID, &postTitle, &postType)
		if postAuthorID > 0 && postAuthorID != userID {
			db.Exec("INSERT INTO community_activities (user_id, target_user_id, action_type, target_type, target_id, target_title, created_at) VALUES (?, ?, 'like', ?, ?, ?, ?)",
				userID, postAuthorID, postType, req.PostID, postTitle, now)
			// TCP 推送通知帖子作者
			PushToUser(postAuthorID, "community_activity", map[string]interface{}{
				"action_type":  "like",
				"target_type":  postType,
				"target_id":    req.PostID,
				"target_title": postTitle,
			})
		}
	}

	// 返回最新的点赞数和状态
	var count int
	db.QueryRow("SELECT COUNT(*) FROM community_likes WHERE post_id = ?", req.PostID).Scan(&count)
	writeJSON(w, 200, "操作成功", map[string]interface{}{
		"count": count,
		"liked": liked == 0,
	})
}

// ==================== 获取评论 ====================

func handleGetComments(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	// 从路径提取 postId: /api/community/comments/{postId}
	idStr := strings.TrimPrefix(r.URL.Path, "/api/community/comments/")
	postID, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil || postID <= 0 {
		writeJSON(w, 400, "帖子ID无效", nil)
		return
	}

	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit < 1 || limit > 50 {
		limit = 20
	}
	offset := (page - 1) * limit

	rows, err := db.Query(`SELECT c.id, c.user_id, u.username, c.content, c.parent_id, c.created_at
		FROM community_comments c JOIN users u ON c.user_id = u.id
		WHERE c.post_id = ? ORDER BY c.created_at ASC LIMIT ? OFFSET ?`, postID, limit, offset)
	if err != nil {
		log.Printf("查询评论失败: %v", err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()

	type CommentItem struct {
		ID         int64  `json:"id"`
		UserID     int64  `json:"user_id"`
		Username   string `json:"username"`
		Content    string `json:"content"`
		ParentID   int64  `json:"parent_id"`
		CreatedAt  int64  `json:"created_at"`
		ReplyCount int    `json:"reply_count"`
	}

	var comments []CommentItem
	for rows.Next() {
		var c CommentItem
		rows.Scan(&c.ID, &c.UserID, &c.Username, &c.Content, &c.ParentID, &c.CreatedAt)
		// 查询该评论的回复数
		db.QueryRow("SELECT COUNT(*) FROM community_comments WHERE parent_id = ?", c.ID).Scan(&c.ReplyCount)
		comments = append(comments, c)
	}
	if comments == nil {
		comments = []CommentItem{}
	}

	var total int
	db.QueryRow("SELECT COUNT(*) FROM community_comments WHERE post_id = ?", postID).Scan(&total)

	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"comments": comments,
		"total":    total,
	})
}

// ==================== 添加评论 ====================

func handleAddComment(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var req struct {
		PostID   int64  `json:"post_id"`
		Content  string `json:"content"`
		ParentID int64  `json:"parent_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.Content = strings.TrimSpace(req.Content)
	if req.Content == "" {
		writeJSON(w, 400, "评论内容不能为空", nil)
		return
	}
	if req.PostID <= 0 {
		writeJSON(w, 400, "帖子ID无效", nil)
		return
	}

	// 检查帖子是否存在
	var exists int
	db.QueryRow("SELECT COUNT(*) FROM community_posts WHERE id = ?", req.PostID).Scan(&exists)
	if exists == 0 {
		writeJSON(w, 404, "帖子不存在", nil)
		return
	}

	// 评论频率限制（防灌水）
	if allowed, wait := checkCommentRateLimit(userID); !allowed {
		writeJSON(w, 429, fmt.Sprintf("评论过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	// 防XSS
	req.Content = html.EscapeString(req.Content)
	// 如果指定了 parent_id，验证该评论是否存在
	if req.ParentID > 0 {
		var parentExists int
		db.QueryRow("SELECT COUNT(*) FROM community_comments WHERE id = ? AND post_id = ?", req.ParentID, req.PostID).Scan(&parentExists)
		if parentExists == 0 {
			writeJSON(w, 400, "被回复的评论不存在", nil)
			return
		}
	}

	now := time.Now().Unix()
	result, err := db.Exec("INSERT INTO community_comments (post_id, user_id, content, parent_id, created_at) VALUES (?, ?, ?, ?, ?)",
		req.PostID, userID, req.Content, req.ParentID, now)
	if err != nil {
		log.Printf("添加评论失败: %v", err)
		writeJSON(w, 500, "添加失败", nil)
		return
	}

	commentID, _ := result.LastInsertId()

	// 记录动态
	var postAuthorID int64
	var postTitle string
	var postType string
	db.QueryRow("SELECT user_id, title, post_type FROM community_posts WHERE id = ?", req.PostID).Scan(&postAuthorID, &postTitle, &postType)

	if req.ParentID > 0 {
		// 回复评论：沿回复链向上，通知链上所有祖先评论的作者
		// （例如 A 评论、B 回复 A、C 回复 B 时，A 和 B 都会收到通知）
		notifiedUsers := make(map[int64]bool)
		var curParentID = req.ParentID
		guard := 0
		for curParentID > 0 && guard < 50 {
			var ancID, ancUserID int64
			if err2 := db.QueryRow("SELECT id, user_id FROM community_comments WHERE id = ?", curParentID).Scan(&ancID, &ancUserID); err2 != nil {
				break
			}
			if ancUserID > 0 && ancUserID != userID && !notifiedUsers[ancUserID] {
				notifiedUsers[ancUserID] = true
				db.Exec("INSERT INTO community_activities (user_id, target_user_id, action_type, target_type, target_id, target_title, comment_content, created_at) VALUES (?, ?, 'reply', ?, ?, ?, ?, ?)",
					userID, ancUserID, postType, req.PostID, postTitle, req.Content, now)
				PushToUser(ancUserID, "community_activity", map[string]interface{}{
					"action_type":  "reply",
					"target_type":  postType,
					"target_id":    req.PostID,
					"target_title": postTitle,
				})
			}
			// 继续向上找父评论
			var nextParent int64
			db.QueryRow("SELECT parent_id FROM community_comments WHERE id = ?", curParentID).Scan(&nextParent)
			curParentID = nextParent
			guard++
		}
		// 同时通知帖子作者（帖子作者不在回复链中、且不是评论者时）
		if postAuthorID > 0 && postAuthorID != userID && !notifiedUsers[postAuthorID] {
			db.Exec("INSERT INTO community_activities (user_id, target_user_id, action_type, target_type, target_id, target_title, comment_content, created_at) VALUES (?, ?, 'comment', ?, ?, ?, ?, ?)",
				userID, postAuthorID, postType, req.PostID, postTitle, req.Content, now)
			PushToUser(postAuthorID, "community_activity", map[string]interface{}{
				"action_type":  "comment",
				"target_type":  postType,
				"target_id":    req.PostID,
				"target_title": postTitle,
			})
		}
	} else {
		// 普通评论：通知帖子作者
		if postAuthorID > 0 && postAuthorID != userID {
			db.Exec("INSERT INTO community_activities (user_id, target_user_id, action_type, target_type, target_id, target_title, comment_content, created_at) VALUES (?, ?, 'comment', ?, ?, ?, ?, ?)",
				userID, postAuthorID, postType, req.PostID, postTitle, req.Content, now)
			PushToUser(postAuthorID, "community_activity", map[string]interface{}{
				"action_type":  "comment",
				"target_type":  postType,
				"target_id":    req.PostID,
				"target_title": postTitle,
			})
		}
	}

	log.Printf("用户 %d 评论帖子 %d (parent_id=%d, comment_id=%d)", userID, req.PostID, req.ParentID, commentID)
	writeJSON(w, 200, "评论成功", nil)
}

// ==================== 获取社区动态 ====================

func handleGetActivities(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit < 1 || limit > 50 {
		limit = 25
	}
	offset := (page - 1) * limit

	rows, err := db.Query(`SELECT a.id, a.user_id, u.username, a.action_type, a.target_type, a.target_id, a.target_title, a.comment_content, a.created_at
		FROM community_activities a JOIN users u ON a.user_id = u.id
		WHERE a.target_user_id = ? ORDER BY a.created_at DESC LIMIT ? OFFSET ?`, userID, limit, offset)
	if err != nil {
		log.Printf("查询动态失败: %v", err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()

	type ActivityItem struct {
		ID             int64  `json:"id"`
		UserID         int64  `json:"user_id"`
		Username       string `json:"username"`
		ActionType     string `json:"action_type"`
		TargetType     string `json:"target_type"`
		TargetID       int64  `json:"target_id"`
		TargetTitle    string `json:"target_title"`
		CommentContent string `json:"comment_content"`
		CreatedAt      int64  `json:"created_at"`
	}

	var activities []ActivityItem
	for rows.Next() {
		var a ActivityItem
		rows.Scan(&a.ID, &a.UserID, &a.Username, &a.ActionType, &a.TargetType, &a.TargetID, &a.TargetTitle, &a.CommentContent, &a.CreatedAt)
		if a.Username == "" {
			a.Username = "用户" + strconv.FormatInt(a.UserID, 10)
		}
		activities = append(activities, a)
	}
	if activities == nil {
		activities = []ActivityItem{}
	}

	var total int
	db.QueryRow("SELECT COUNT(*) FROM community_activities WHERE target_user_id = ?", userID).Scan(&total)

	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"activities": activities,
		"total":      total,
	})
}

// ==================== 清空当前用户全部动态（物理删除，暴力模式）====================

// handleClearActivities POST /api/community/activities/clear
// 直接从数据库 DELETE 当前用户作为接收方的全部活动记录（target_user_id = 当前用户）
// 这是最暴力的清除方式：清空后即使刷新 / 重新登录 / 重新拉取接口，数据也不会再出现
func handleClearActivities(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	// 物理删除：直接从 community_activities 表删除所有 target_user_id = 当前用户 的记录
	// 删除软删除标记和冗余缓存，清空后不可恢复
	result, err := db.Exec("DELETE FROM community_activities WHERE target_user_id = ?", userID)
	if err != nil {
		log.Printf("[清空动态] DELETE 失败: userID=%d, err=%v", userID, err)
		writeJSON(w, 500, "清空失败", nil)
		return
	}
	affected, _ := result.RowsAffected()
	log.Printf("[清空动态] 成功: userID=%d, 删除记录数=%d", userID, affected)

	writeJSON(w, 200, "清空成功", map[string]interface{}{
		"deleted_count": affected,
	})
}
