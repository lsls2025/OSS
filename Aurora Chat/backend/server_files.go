package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ============================================================
// 服务器文件存储系统（支持文件夹层级）
// ============================================================

// 文件存储根目录
var serverFilesDir string

func initServerFilesStorage(baseDir string) {
	serverFilesDir = filepath.Join(baseDir, "data", "server_files")
	os.MkdirAll(serverFilesDir, 0755)
	os.MkdirAll(filepath.Join(serverFilesDir, "files"), 0755)

	// 创建数据库表
	if _, err := db.Exec(`
	CREATE TABLE IF NOT EXISTS server_files (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		server_id INTEGER NOT NULL,
		user_id INTEGER NOT NULL,
		file_name TEXT NOT NULL,
		file_size INTEGER NOT NULL DEFAULT 0,
		storage_key TEXT NOT NULL UNIQUE,
		mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
		is_dir INTEGER NOT NULL DEFAULT 0,
		parent_id INTEGER DEFAULT NULL,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL,
		FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE CASCADE,
		FOREIGN KEY (parent_id) REFERENCES server_files(id) ON DELETE CASCADE
	);
	CREATE INDEX IF NOT EXISTS idx_server_files_server ON server_files(server_id);
	CREATE INDEX IF NOT EXISTS idx_server_files_parent ON server_files(parent_id);
	`); err != nil {
		log.Printf("  提示: server_files 表准备: %v", err)
	}

	// 兼容旧数据库：添加 is_dir 和 parent_id 列（已存在的表忽略错误）
	db.Exec("ALTER TABLE server_files ADD COLUMN is_dir INTEGER NOT NULL DEFAULT 0")
	db.Exec("ALTER TABLE server_files ADD COLUMN parent_id INTEGER DEFAULT NULL")
	db.Exec("CREATE INDEX IF NOT EXISTS idx_server_files_parent ON server_files(parent_id)")

	log.Printf("  服务器文件存储目录: %s", serverFilesDir)
}

// 生成随机存储 key
func generateStorageKey() (string, error) {
	bytes := make([]byte, 16)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes), nil
}

// 获取服务器基础 URL — 硬编码为 http://www.YOUR_SERVER_DOMAIN:5004
func getServerBaseURL() string {
	return "http://www.YOUR_SERVER_DOMAIN:5004"
}

// 获取服务器的公开文件访问基础 URL
func getServerServeURL(serverID int64) string {
	base := getServerBaseURL()
	return fmt.Sprintf("%s/serve/%d", base, serverID)
}

// ============================================================
// 文件/文件夹结构体
// ============================================================

type ServerFileItem struct {
	ID        int64  `json:"id"`
	FileName  string `json:"file_name"`
	FileSize  int64  `json:"file_size"`
	MimeType  string `json:"mime_type"`
	IsDir     bool   `json:"is_dir"`
	ParentID  *int64 `json:"parent_id"`
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
	// 下载链接（仅文件有效）
	DownloadURL string `json:"download_url,omitempty"`
}

// ============================================================
// 验证文件夹归属
// ============================================================

func validateFolderBelongsToServer(folderID, serverID int64) error {
	var actualServerID int64
	err := db.QueryRow("SELECT server_id FROM server_files WHERE id = ? AND is_dir = 1", folderID).Scan(&actualServerID)
	if err != nil {
		return fmt.Errorf("文件夹不存在")
	}
	if actualServerID != serverID {
		return fmt.Errorf("文件夹不属于该服务器")
	}
	return nil
}

// ============================================================
// API 路由
// ============================================================

// POST /api/server/files/create-folder — 创建文件夹
func handleCreateServerFolder(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	var req struct {
		FolderName string `json:"folder_name"`
		ParentID   *int64 `json:"parent_id"` // nil = 根目录
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.FolderName = strings.TrimSpace(req.FolderName)
	if req.FolderName == "" {
		writeJSON(w, 400, "文件夹名称不能为空", nil)
		return
	}
	if len(req.FolderName) > 255 || strings.Contains(req.FolderName, "/") || strings.Contains(req.FolderName, "\\") {
		writeJSON(w, 400, "文件夹名称不合法", nil)
		return
	}

	// 如果指定了父文件夹，验证父文件夹属于该服务器
	if req.ParentID != nil && *req.ParentID > 0 {
		if err := validateFolderBelongsToServer(*req.ParentID, server.ID); err != nil {
			writeJSON(w, 400, err.Error(), nil)
			return
		}
	}

	// 检查同一层级下是否已存在同名文件夹
	var exists int
	db.QueryRow(
		"SELECT COUNT(*) FROM server_files WHERE server_id = ? AND file_name = ? AND parent_id IS ? AND is_dir = 1",
		server.ID, req.FolderName, req.ParentID,
	).Scan(&exists)
	if exists > 0 {
		writeJSON(w, 409, "该位置已存在同名文件夹", nil)
		return
	}

	now := time.Now().Unix()

	// 为文件夹生成唯一的 storage_key
	folderKey, err := generateStorageKey()
	if err != nil {
		writeJSON(w, 500, "生成文件夹标识失败", nil)
		return
	}

	// 插入数据库
	result, err := db.Exec(
		`INSERT INTO server_files (server_id, user_id, file_name, file_size, storage_key, mime_type, is_dir, parent_id, created_at, updated_at)
		 VALUES (?, ?, ?, 0, ?, 'inode/directory', 1, ?, ?, ?)`,
		server.ID, userID, req.FolderName, folderKey, req.ParentID, now, now,
	)
	if err != nil {
		log.Printf("[文件夹] 创建失败: %v", err)
		writeJSON(w, 500, "创建文件夹失败", nil)
		return
	}

	folderID, _ := result.LastInsertId()
	log.Printf("[文件夹] 创建成功: server=%d, name=%s, id=%d", server.ID, req.FolderName, folderID)

	// 在磁盘上创建对应目录（便于文件存放）
	folderPath := filepath.Join(serverFilesDir, "files", fmt.Sprintf("dir_%d", folderID))
	os.MkdirAll(folderPath, 0755)

	writeJSON(w, 200, "创建成功", map[string]interface{}{
		"id":          folderID,
		"folder_name": req.FolderName,
		"parent_id":   req.ParentID,
		"created_at":  now,
	})
}

// POST /api/server/files/create — 创建空文件
func handleCreateServerFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	var req struct {
		FileName string `json:"file_name"`
		Content  string `json:"content"`
		ParentID *int64 `json:"parent_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	req.FileName = strings.TrimSpace(req.FileName)
	if req.FileName == "" {
		writeJSON(w, 400, "文件名不能为空", nil)
		return
	}
	if len(req.FileName) > 255 || strings.Contains(req.FileName, "/") || strings.Contains(req.FileName, "\\") {
		writeJSON(w, 400, "文件名不合法", nil)
		return
	}

	// 如果指定了父文件夹，验证
	if req.ParentID != nil && *req.ParentID > 0 {
		if err := validateFolderBelongsToServer(*req.ParentID, server.ID); err != nil {
			writeJSON(w, 400, err.Error(), nil)
			return
		}
	}

	// 检查同一层级下是否已存在同名文件
	var exists int
	db.QueryRow(
		"SELECT COUNT(*) FROM server_files WHERE server_id = ? AND file_name = ? AND parent_id IS ? AND is_dir = 0",
		server.ID, req.FileName, req.ParentID,
	).Scan(&exists)
	if exists > 0 {
		writeJSON(w, 409, "该位置已存在同名文件", nil)
		return
	}

	storageKey, err := generateStorageKey()
	if err != nil {
		log.Printf("[文件] 生成存储 key 失败: %v", err)
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	now := time.Now().Unix()

	// 文件存储位置：如果在文件夹内，放到文件夹对应的目录
	var filePath string
	if req.ParentID != nil && *req.ParentID > 0 {
		folderDir := filepath.Join(serverFilesDir, "files", fmt.Sprintf("dir_%d", *req.ParentID))
		os.MkdirAll(folderDir, 0755)
		filePath = filepath.Join(folderDir, storageKey)
	} else {
		filePath = filepath.Join(serverFilesDir, "files", storageKey)
	}

	content := []byte(req.Content)
	if err := os.WriteFile(filePath, content, 0644); err != nil {
		log.Printf("[文件] 写入文件失败: %v", err)
		writeJSON(w, 500, "创建文件失败", nil)
		return
	}

	mimeType := detectMimeByExtension(req.FileName)

	_, err = db.Exec(
		`INSERT INTO server_files (server_id, user_id, file_name, file_size, storage_key, mime_type, is_dir, parent_id, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)`,
		server.ID, userID, req.FileName, len(content), storageKey, mimeType, req.ParentID, now, now,
	)
	if err != nil {
		log.Printf("[文件] 数据库写入失败: %v", err)
		os.Remove(filePath)
		writeJSON(w, 500, "创建文件记录失败", nil)
		return
	}

	// 生成可访问的下载链接
	baseURL := getServerBaseURL()
	downloadURL := fmt.Sprintf("%s/serve/%d/files/%s", baseURL, server.ID, storageKey)

	log.Printf("[文件] 创建成功: server=%d, name=%s, size=%d", server.ID, req.FileName, len(content))
	writeJSON(w, 200, "创建成功", map[string]interface{}{
		"file_name":    req.FileName,
		"file_size":    len(content),
		"mime_type":    mimeType,
		"parent_id":    req.ParentID,
		"created_at":   now,
		"download_url": downloadURL,
	})
}

// POST /api/server/files/upload — 上传文件（multipart）
func handleUploadServerFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	// 限制 100MB
	r.Body = http.MaxBytesReader(w, r.Body, 100<<20)
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeJSON(w, 400, "文件过大或格式错误（最大100MB）", nil)
		return
	}

	// 读取 parent_id（可选）
	var parentID *int64
	if pidStr := r.FormValue("parent_id"); pidStr != "" {
		if pid, err := strconv.ParseInt(pidStr, 10, 64); err == nil && pid > 0 {
			if err := validateFolderBelongsToServer(pid, server.ID); err != nil {
				writeJSON(w, 400, err.Error(), nil)
				return
			}
			parentID = &pid
		}
	}

	file, header, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, "未找到上传文件", nil)
		return
	}
	defer file.Close()

	storageKey, err := generateStorageKey()
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	// 根据父文件夹确定存储路径
	var filePath string
	if parentID != nil && *parentID > 0 {
		folderDir := filepath.Join(serverFilesDir, "files", fmt.Sprintf("dir_%d", *parentID))
		os.MkdirAll(folderDir, 0755)
		filePath = filepath.Join(folderDir, storageKey)
	} else {
		filePath = filepath.Join(serverFilesDir, "files", storageKey)
	}

	dst, err := os.Create(filePath)
	if err != nil {
		log.Printf("[文件] 创建存储文件失败: %v", err)
		writeJSON(w, 500, "存储失败", nil)
		return
	}
	defer dst.Close()

	written, err := io.Copy(dst, file)
	if err != nil {
		log.Printf("[文件] 写入文件失败: %v", err)
		os.Remove(filePath)
		writeJSON(w, 500, "上传失败", nil)
		return
	}

	fileName := header.Filename
	mimeType := header.Header.Get("Content-Type")
	if mimeType == "" || mimeType == "application/octet-stream" {
		mimeType = detectMimeByExtension(fileName)
	}

	now := time.Now().Unix()
	_, err = db.Exec(
		`INSERT INTO server_files (server_id, user_id, file_name, file_size, storage_key, mime_type, is_dir, parent_id, created_at, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)`,
		server.ID, userID, fileName, written, storageKey, mimeType, parentID, now, now,
	)
	if err != nil {
		log.Printf("[文件] 数据库写入失败: %v", err)
		os.Remove(filePath)
		writeJSON(w, 500, "上传记录失败", nil)
		return
	}

	// 生成可访问的下载链接
	baseURL := getServerBaseURL()
	downloadURL := fmt.Sprintf("%s/serve/%d/files/%s", baseURL, server.ID, storageKey)

	log.Printf("[文件] 上传成功: server=%d, name=%s, size=%d", server.ID, fileName, written)
	writeJSON(w, 200, "上传成功", map[string]interface{}{
		"file_name":    fileName,
		"file_size":    written,
		"mime_type":    mimeType,
		"parent_id":    parentID,
		"created_at":   now,
		"download_url": downloadURL,
	})
}

// GET /api/server/files/list — 获取文件列表（支持按文件夹筛选）
func handleListServerFiles(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	// 读取 parent_id 参数（可选，省略则返回根目录）
	parentIDStr := r.URL.Query().Get("parent_id")
	baseURL := getServerBaseURL()

	var rows, foldersRows *sql.Rows

	if parentIDStr == "" || parentIDStr == "root" {
		// 根目录：parent_id IS NULL
		foldersRows, err = db.Query(
			`SELECT id, file_name, file_size, mime_type, is_dir, parent_id, created_at, updated_at
			 FROM server_files WHERE server_id = ? AND parent_id IS NULL AND is_dir = 1
			 ORDER BY file_name ASC`, server.ID,
		)
		if err != nil {
			log.Printf("[文件] 查询文件夹列表失败: %v", err)
			writeJSON(w, 500, "查询失败", nil)
			return
		}
		defer foldersRows.Close()

		rows, err = db.Query(
			`SELECT id, file_name, file_size, mime_type, is_dir, parent_id, created_at, updated_at
			 FROM server_files WHERE server_id = ? AND parent_id IS NULL AND is_dir = 0
			 ORDER BY created_at DESC`, server.ID,
		)
	} else {
		parentID, parseErr := strconv.ParseInt(parentIDStr, 10, 64)
		if parseErr != nil || parentID <= 0 {
			writeJSON(w, 400, "无效的 parent_id", nil)
			return
		}
		// 验证文件夹属于该服务器
		if err := validateFolderBelongsToServer(parentID, server.ID); err != nil {
			writeJSON(w, 400, err.Error(), nil)
			return
		}

		foldersRows, err = db.Query(
			`SELECT id, file_name, file_size, mime_type, is_dir, parent_id, created_at, updated_at
			 FROM server_files WHERE server_id = ? AND parent_id = ? AND is_dir = 1
			 ORDER BY file_name ASC`, server.ID, parentID,
		)
		if err != nil {
			log.Printf("[文件] 查询子文件夹列表失败: %v", err)
			writeJSON(w, 500, "查询失败", nil)
			return
		}
		defer foldersRows.Close()

		rows, err = db.Query(
			`SELECT id, file_name, file_size, mime_type, is_dir, parent_id, created_at, updated_at
			 FROM server_files WHERE server_id = ? AND parent_id = ? AND is_dir = 0
			 ORDER BY created_at DESC`, server.ID, parentID,
		)
	}

	if err != nil {
		log.Printf("[文件] 查询文件列表失败: %v", err)
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()

	// 先收集文件夹
	var items []ServerFileItem
	if foldersRows != nil {
		for foldersRows.Next() {
			var f ServerFileItem
			var isDirInt int
			if err := foldersRows.Scan(&f.ID, &f.FileName, &f.FileSize, &f.MimeType, &isDirInt, &f.ParentID, &f.CreatedAt, &f.UpdatedAt); err != nil {
				continue
			}
			f.IsDir = isDirInt == 1
			items = append(items, f)
		}
	}

	// 再收集文件
	for rows.Next() {
		var f ServerFileItem
		var isDirInt int
		if err := rows.Scan(&f.ID, &f.FileName, &f.FileSize, &f.MimeType, &isDirInt, &f.ParentID, &f.CreatedAt, &f.UpdatedAt); err != nil {
			continue
		}
		f.IsDir = isDirInt == 1
		// 为文件生成下载链接
		if !f.IsDir {
			// 查询 storage_key
			var storageKey string
			db.QueryRow("SELECT storage_key FROM server_files WHERE id = ?", f.ID).Scan(&storageKey)
			if storageKey != "" {
				f.DownloadURL = fmt.Sprintf("%s/serve/%d/files/%s", baseURL, server.ID, storageKey)
			}
		}
		items = append(items, f)
	}

	if items == nil {
		items = []ServerFileItem{}
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"files":         items,
		"server_domain": server.Domain,
		"server_url":    baseURL,
	})
}

// GET /api/server/files/breadcrumb — 获取面包屑导航
func handleGetFolderBreadcrumb(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	folderIDStr := r.URL.Query().Get("folder_id")
	if folderIDStr == "" || folderIDStr == "0" || folderIDStr == "root" {
		// 根目录，返回空路径
		writeJSON(w, 200, "ok", map[string]interface{}{
			"path": []map[string]interface{}{},
		})
		return
	}

	folderID, err := strconv.ParseInt(folderIDStr, 10, 64)
	if err != nil || folderID <= 0 {
		writeJSON(w, 400, "无效的 folder_id", nil)
		return
	}

	// 向上遍历构建路径
	type BreadcrumbItem struct {
		ID   int64  `json:"id"`
		Name string `json:"name"`
	}
	var path []BreadcrumbItem

	currentID := folderID
	for currentID > 0 {
		var name string
		var parentID sql.NullInt64
		err := db.QueryRow(
			"SELECT file_name, parent_id FROM server_files WHERE id = ? AND server_id = ? AND is_dir = 1",
			currentID, server.ID,
		).Scan(&name, &parentID)
		if err != nil {
			break
		}
		path = append([]BreadcrumbItem{{ID: currentID, Name: name}}, path...)
		if parentID.Valid {
			currentID = parentID.Int64
		} else {
			currentID = 0
		}
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"path": path,
	})
}

// GET /api/server/files/download/ — 下载文件（基于 ID 或 storage_key，支持 JWT 认证）
func handleDownloadServerFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api/server/files/download/")
	fileID, err := strconv.ParseInt(strings.TrimSpace(path), 10, 64)
	if err != nil || fileID <= 0 {
		writeJSON(w, 400, "文件 ID 无效", nil)
		return
	}

	var fileName string
	var storageKey string
	var serverID int64
	var isDir int
	err = db.QueryRow(
		"SELECT server_id, file_name, storage_key, is_dir FROM server_files WHERE id = ?",
		fileID,
	).Scan(&serverID, &fileName, &storageKey, &isDir)
	if err != nil {
		writeJSON(w, 404, "文件不存在", nil)
		return
	}
	if isDir == 1 {
		writeJSON(w, 400, "不能下载文件夹", nil)
		return
	}

	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil || server.ID != serverID {
		writeJSON(w, 403, "无权访问此文件", nil)
		return
	}

	// 查找文件物理路径（可能在根目录或子文件夹中）
	filePath := filepath.Join(serverFilesDir, "files", storageKey)
	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		// 尝试在文件夹目录中查找
		filePath = findFileInFolders(storageKey)
		if filePath == "" {
			writeJSON(w, 404, "文件已丢失", nil)
			return
		}
	}

	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, fileName))
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeFile(w, r, filePath)
}

// 在文件夹目录中查找文件
func findFileInFolders(storageKey string) string {
	filesDir := filepath.Join(serverFilesDir, "files")
	entries, err := os.ReadDir(filesDir)
	if err != nil {
		return ""
	}
	for _, entry := range entries {
		if entry.IsDir() && strings.HasPrefix(entry.Name(), "dir_") {
			subPath := filepath.Join(filesDir, entry.Name(), storageKey)
			if _, err := os.Stat(subPath); err == nil {
				return subPath
			}
		}
	}
	return ""
}

// POST /api/server/files/delete — 删除文件或文件夹
func handleDeleteServerFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "DELETE" && r.Method != "POST" {
		writeJSON(w, 405, "仅支持 DELETE/POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	var req struct {
		FileID int64 `json:"file_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FileID <= 0 {
		writeJSON(w, 400, "请提供有效的 file_id", nil)
		return
	}

	// 查询文件/文件夹信息
	var storageKey string
	var isDir int
	err = db.QueryRow(
		"SELECT storage_key, is_dir FROM server_files WHERE id = ? AND server_id = ?",
		req.FileID, server.ID,
	).Scan(&storageKey, &isDir)
	if err != nil {
		writeJSON(w, 404, "文件不存在或无权删除", nil)
		return
	}

	if isDir == 1 {
		// 删除文件夹及其所有子文件和子文件夹
		if err := deleteFolderRecursive(req.FileID, server.ID); err != nil {
			log.Printf("[文件] 删除文件夹失败: %v", err)
			writeJSON(w, 500, "删除文件夹失败", nil)
			return
		}
		// 删除磁盘上的文件夹目录
		folderPath := filepath.Join(serverFilesDir, "files", fmt.Sprintf("dir_%d", req.FileID))
		os.RemoveAll(folderPath)
	} else {
		// 删除单个文件
		if _, err := db.Exec("DELETE FROM server_files WHERE id = ?", req.FileID); err != nil {
			log.Printf("[文件] 删除记录失败: %v", err)
			writeJSON(w, 500, "删除失败", nil)
			return
		}
		// 删除可能的物理文件（根目录或文件夹目录中）
		filePath := filepath.Join(serverFilesDir, "files", storageKey)
		os.Remove(filePath)
		// 也尝试在文件夹中删除
		filesDir := filepath.Join(serverFilesDir, "files")
		entries, _ := os.ReadDir(filesDir)
		for _, entry := range entries {
			if entry.IsDir() && strings.HasPrefix(entry.Name(), "dir_") {
				os.Remove(filepath.Join(filesDir, entry.Name(), storageKey))
			}
		}
	}

	log.Printf("[文件] 删除成功: fileID=%d, server=%d", req.FileID, server.ID)
	writeJSON(w, 200, "删除成功", nil)
}

// 递归删除文件夹及其所有子文件/子文件夹
func deleteFolderRecursive(folderID, serverID int64) error {
	// 先删除所有子文件（非文件夹）
	if _, err := db.Exec(
		"DELETE FROM server_files WHERE parent_id = ? AND server_id = ? AND is_dir = 0",
		folderID, serverID,
	); err != nil {
		return err
	}

	// 查询所有子文件夹
	rows, err := db.Query(
		"SELECT id FROM server_files WHERE parent_id = ? AND server_id = ? AND is_dir = 1",
		folderID, serverID,
	)
	if err != nil {
		return err
	}
	var subFolderIDs []int64
	for rows.Next() {
		var subID int64
		rows.Scan(&subID)
		subFolderIDs = append(subFolderIDs, subID)
	}
	rows.Close()

	// 递归删除子文件夹
	for _, subID := range subFolderIDs {
		if err := deleteFolderRecursive(subID, serverID); err != nil {
			return err
		}
		// 删除子文件夹磁盘目录
		subPath := filepath.Join(serverFilesDir, "files", fmt.Sprintf("dir_%d", subID))
		os.RemoveAll(subPath)
	}

	// 最后删除文件夹本身
	if _, err := db.Exec(
		"DELETE FROM server_files WHERE id = ? AND server_id = ?",
		folderID, serverID,
	); err != nil {
		return err
	}

	return nil
}

// POST /api/server/files/move — 移动文件或文件夹
func handleMoveServerFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}

	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	var req struct {
		FileID         int64  `json:"file_id"`
		TargetParentID *int64 `json:"target_parent_id"` // nil = 移动到根目录
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FileID <= 0 {
		writeJSON(w, 400, "请提供有效的 file_id", nil)
		return
	}

	// 验证文件属于该服务器
	var isDir int
	var currentParentID sql.NullInt64
	err = db.QueryRow(
		"SELECT is_dir, parent_id FROM server_files WHERE id = ? AND server_id = ?",
		req.FileID, server.ID,
	).Scan(&isDir, &currentParentID)
	if err != nil {
		writeJSON(w, 404, "文件不存在或无权操作", nil)
		return
	}

	// 如果指定了目标文件夹，验证
	if req.TargetParentID != nil && *req.TargetParentID > 0 {
		if *req.TargetParentID == req.FileID {
			writeJSON(w, 400, "不能将文件夹移动到自身", nil)
			return
		}
		if err := validateFolderBelongsToServer(*req.TargetParentID, server.ID); err != nil {
			writeJSON(w, 400, err.Error(), nil)
			return
		}
		// 如果是文件夹，检查是否尝试移动到自己的子目录（防止循环）
		if isDir == 1 {
			if isDescendantOf(req.FileID, *req.TargetParentID, server.ID) {
				writeJSON(w, 400, "不能将文件夹移动到其子目录中", nil)
				return
			}
		}
	}

	// 更新 parent_id
	now := time.Now().Unix()
	_, err = db.Exec(
		"UPDATE server_files SET parent_id = ?, updated_at = ? WHERE id = ? AND server_id = ?",
		req.TargetParentID, now, req.FileID, server.ID,
	)
	if err != nil {
		log.Printf("[文件] 移动失败: %v", err)
		writeJSON(w, 500, "移动失败", nil)
		return
	}

	log.Printf("[文件] 移动成功: fileID=%d, newParentID=%v", req.FileID, req.TargetParentID)
	writeJSON(w, 200, "移动成功", nil)
}

// 检查 targetID 是否是 folderID 的子孙文件夹
func isDescendantOf(folderID, targetID, serverID int64) bool {
	currentID := targetID
	visited := make(map[int64]bool)
	for currentID > 0 {
		if currentID == folderID {
			return true
		}
		if visited[currentID] {
			return false
		}
		visited[currentID] = true
		var parentID sql.NullInt64
		err := db.QueryRow(
			"SELECT parent_id FROM server_files WHERE id = ? AND server_id = ? AND is_dir = 1",
			currentID, serverID,
		).Scan(&parentID)
		if err != nil {
			return false
		}
		if parentID.Valid {
			currentID = parentID.Int64
		} else {
			currentID = 0
		}
	}
	return false
}

// ============================================================
// 新增 API：文件更新、重命名、搜索、仪表盘
// ============================================================

// POST /api/server/files/update — 更新已存在文件的内容
func handleUpdateServerFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	var req struct {
		FileID  int64  `json:"file_id"`
		Content string `json:"content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.FileID <= 0 {
		writeJSON(w, 400, "请提供有效的 file_id", nil)
		return
	}

	// 验证文件属于该服务器且不是文件夹
	var storageKey string
	var fileName string
	var isDir int
	err = db.QueryRow(
		"SELECT storage_key, file_name, is_dir FROM server_files WHERE id = ? AND server_id = ?",
		req.FileID, server.ID,
	).Scan(&storageKey, &fileName, &isDir)
	if err != nil {
		writeJSON(w, 404, "文件不存在或无权修改", nil)
		return
	}
	if isDir == 1 {
		writeJSON(w, 400, "不能修改文件夹", nil)
		return
	}

	// 查找文件物理路径
	filePath := filepath.Join(serverFilesDir, "files", storageKey)
	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		filePath = findFileInFolders(storageKey)
		if filePath == "" {
			writeJSON(w, 404, "文件物理文件已丢失", nil)
			return
		}
	}

	// 写入新内容
	content := []byte(req.Content)
	if err := os.WriteFile(filePath, content, 0644); err != nil {
		log.Printf("[文件] 更新写入失败: %v", err)
		writeJSON(w, 500, "写入文件失败", nil)
		return
	}

	now := time.Now().Unix()
	mimeType := detectMimeByExtension(fileName)

	// 更新数据库记录
	_, err = db.Exec(
		"UPDATE server_files SET file_size = ?, mime_type = ?, updated_at = ? WHERE id = ?",
		len(content), mimeType, now, req.FileID,
	)
	if err != nil {
		log.Printf("[文件] 更新记录失败: %v", err)
		writeJSON(w, 500, "更新记录失败", nil)
		return
	}

	baseURL := getServerBaseURL()
	downloadURL := fmt.Sprintf("%s/serve/%d/files/%s", baseURL, server.ID, storageKey)

	log.Printf("[文件] 更新成功: fileID=%d, name=%s, size=%d", req.FileID, fileName, len(content))
	writeJSON(w, 200, "保存成功", map[string]interface{}{
		"file_id":      req.FileID,
		"file_name":    fileName,
		"file_size":    len(content),
		"mime_type":    mimeType,
		"updated_at":   now,
		"download_url": downloadURL,
	})
}

// POST /api/server/files/rename — 重命名文件或文件夹
func handleRenameServerFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	var req struct {
		FileID  int64  `json:"file_id"`
		NewName string `json:"new_name"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.FileID <= 0 {
		writeJSON(w, 400, "请提供有效的 file_id", nil)
		return
	}
	req.NewName = strings.TrimSpace(req.NewName)
	if req.NewName == "" {
		writeJSON(w, 400, "新名称不能为空", nil)
		return
	}
	if len(req.NewName) > 255 || strings.Contains(req.NewName, "/") || strings.Contains(req.NewName, "\\") {
		writeJSON(w, 400, "名称不合法", nil)
		return
	}

	// 验证文件属于该服务器
	var currentName string
	var parentID sql.NullInt64
	err = db.QueryRow(
		"SELECT file_name, parent_id FROM server_files WHERE id = ? AND server_id = ?",
		req.FileID, server.ID,
	).Scan(&currentName, &parentID)
	if err != nil {
		writeJSON(w, 404, "文件不存在或无权操作", nil)
		return
	}

	// 检查新名称是否与同目录下其他文件冲突
	var exists int
	if parentID.Valid {
		db.QueryRow(
			"SELECT COUNT(*) FROM server_files WHERE server_id = ? AND file_name = ? AND parent_id = ? AND id != ?",
			server.ID, req.NewName, parentID.Int64, req.FileID,
		).Scan(&exists)
	} else {
		db.QueryRow(
			"SELECT COUNT(*) FROM server_files WHERE server_id = ? AND file_name = ? AND parent_id IS NULL AND id != ?",
			server.ID, req.NewName, req.FileID,
		).Scan(&exists)
	}
	if exists > 0 {
		writeJSON(w, 409, "该位置已存在同名文件或文件夹", nil)
		return
	}

	now := time.Now().Unix()
	_, err = db.Exec(
		"UPDATE server_files SET file_name = ?, updated_at = ? WHERE id = ? AND server_id = ?",
		req.NewName, now, req.FileID, server.ID,
	)
	if err != nil {
		log.Printf("[文件] 重命名失败: %v", err)
		writeJSON(w, 500, "重命名失败", nil)
		return
	}

	log.Printf("[文件] 重命名成功: fileID=%d, %s -> %s", req.FileID, currentName, req.NewName)
	writeJSON(w, 200, "重命名成功", map[string]interface{}{
		"file_id":    req.FileID,
		"old_name":   currentName,
		"new_name":   req.NewName,
		"updated_at": now,
	})
}

// GET /api/server/files/search?q=keyword — 搜索文件
func handleSearchServerFiles(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	keyword := strings.TrimSpace(r.URL.Query().Get("q"))
	if keyword == "" {
		writeJSON(w, 400, "请输入搜索关键词", nil)
		return
	}

	like := "%" + keyword + "%"
	baseURL := getServerBaseURL()

	rows, err := db.Query(
		`SELECT id, file_name, file_size, mime_type, is_dir, parent_id, created_at, updated_at
		 FROM server_files WHERE server_id = ? AND file_name LIKE ?
		 ORDER BY is_dir DESC, file_name ASC LIMIT 100`,
		server.ID, like,
	)
	if err != nil {
		log.Printf("[文件] 搜索失败: %v", err)
		writeJSON(w, 500, "搜索失败", nil)
		return
	}
	defer rows.Close()

	var results []ServerFileItem
	for rows.Next() {
		var f ServerFileItem
		var isDirInt int
		if err := rows.Scan(&f.ID, &f.FileName, &f.FileSize, &f.MimeType, &isDirInt, &f.ParentID, &f.CreatedAt, &f.UpdatedAt); err != nil {
			continue
		}
		f.IsDir = isDirInt == 1
		if !f.IsDir {
			var storageKey string
			db.QueryRow("SELECT storage_key FROM server_files WHERE id = ?", f.ID).Scan(&storageKey)
			if storageKey != "" {
				f.DownloadURL = fmt.Sprintf("%s/serve/%d/files/%s", baseURL, server.ID, storageKey)
			}
		}
		results = append(results, f)
	}
	if results == nil {
		results = []ServerFileItem{}
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"files":      results,
		"total":      len(results),
		"server_url": baseURL,
	})
}

// GET /api/server/dashboard — 服务器仪表盘数据
func handleServerDashboard(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	server, err := getOrCreateServerByOwner(userID)
	if err != nil || server == nil {
		writeJSON(w, 403, "你没有注册服务器", nil)
		return
	}

	// 总文件数
	var totalFiles int64
	db.QueryRow("SELECT COUNT(*) FROM server_files WHERE server_id = ? AND is_dir = 0", server.ID).Scan(&totalFiles)

	// 总文件夹数
	var totalFolders int64
	db.QueryRow("SELECT COUNT(*) FROM server_files WHERE server_id = ? AND is_dir = 1", server.ID).Scan(&totalFolders)

	// 总存储大小
	var totalSize int64
	db.QueryRow("SELECT COALESCE(SUM(file_size), 0) FROM server_files WHERE server_id = ? AND is_dir = 0", server.ID).Scan(&totalSize)

	// 最近活动（最后一次访问）
	var lastAccess int64
	err = db.QueryRow("SELECT COALESCE(MAX(accessed_at), 0) FROM server_access_logs WHERE server_id = ?", server.ID).Scan(&lastAccess)
	if err != nil {
		lastAccess = 0
	}

	// 访问总次数
	var totalAccess int64
	db.QueryRow("SELECT COUNT(*) FROM server_access_logs WHERE server_id = ?", server.ID).Scan(&totalAccess)

	// 文件类型分布
	typeDist := map[string]int64{}
	rows, _ := db.Query(
		"SELECT mime_type, COUNT(*) as cnt FROM server_files WHERE server_id = ? AND is_dir = 0 GROUP BY mime_type ORDER BY cnt DESC LIMIT 10",
		server.ID,
	)
	if rows != nil {
		defer rows.Close()
		for rows.Next() {
			var mime string
			var cnt int64
			rows.Scan(&mime, &cnt)
			typeDist[mime] = cnt
		}
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"total_files":       totalFiles,
		"total_folders":     totalFolders,
		"total_size":        totalSize,
		"total_size_str":    formatFileSize(totalSize),
		"last_access":       lastAccess,
		"total_access":      totalAccess,
		"server_name":       server.Name,
		"server_domain":     server.Domain,
		"server_status":     server.ServerStatus,
		"type_distribution": typeDist,
	})
}

// 格式化文件大小
func formatFileSize(size int64) string {
	if size >= 1024*1024*1024 {
		return fmt.Sprintf("%.2f GB", float64(size)/(1024*1024*1024))
	}
	if size >= 1024*1024 {
		return fmt.Sprintf("%.2f MB", float64(size)/(1024*1024))
	}
	if size >= 1024 {
		return fmt.Sprintf("%.2f KB", float64(size)/1024)
	}
	return fmt.Sprintf("%d B", size)
}

// 改进公共文件访问路由 — 支持服务器首页 /serve/{serverId} 及网站托管
func handlePublicServeFile(w http.ResponseWriter, r *http.Request) {
	// 路径格式: /serve/{serverId} 或 /serve/{serverId}/ 或 /serve/{serverId}/{fileName} 或 /serve/{serverId}/files/{storageKey}
	path := strings.TrimPrefix(r.URL.Path, "/serve/")
	parts := strings.SplitN(path, "/", 3)
	if len(parts) < 1 || parts[0] == "" {
		writeJSON(w, 400, "URL格式错误", nil)
		return
	}

	serverIDStr := parts[0]
	serverID, err := strconv.ParseInt(serverIDStr, 10, 64)
	if err != nil || serverID <= 0 {
		writeJSON(w, 400, "服务器ID无效", nil)
		return
	}

	// 检查服务器运行状态
	status := computeServerStatus(serverID)
	if status == "stopped" || status == "stopping" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		fmt.Fprint(w, `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Aurora Chat — 服务暂不可用</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;background:linear-gradient(135deg,#F0F4FF 0%,#E8EEF9 100%);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
.card{max-width:520px;width:100%;background:rgba(255,255,255,0.85);backdrop-filter:blur(20px);border-radius:24px;padding:48px 40px;box-shadow:0 8px 40px rgba(30,64,175,0.08),0 2px 8px rgba(0,0,0,0.04);border:1px solid rgba(255,255,255,0.6);text-align:center}
.logo{font-size:32px;font-weight:700;background:linear-gradient(135deg,#1E40AF,#3B82F6);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;letter-spacing:-0.5px;margin-bottom:6px}
.subtitle{font-size:13px;color:#6B7280;letter-spacing:1.5px;margin-bottom:32px;font-weight:400}
.divider{width:64px;height:2px;margin:0 auto 28px;background:linear-gradient(90deg,transparent,#1E40AF,transparent);opacity:0.35}
.status-badge{display:inline-block;background:#FEF2F2;color:#DC2626;font-size:12px;font-weight:500;padding:4px 14px;border-radius:20px;margin-bottom:20px;letter-spacing:0.5px}
.desc{text-align:left;background:#F8FAFC;border-radius:16px;padding:20px;margin-bottom:28px;border:1px solid #E5E7EB}
.desc p{font-size:13px;line-height:1.7;color:#4B5563}
.desc .tech{font-size:12px;color:#9CA3AF;margin-top:10px;line-height:1.6}
.community{text-align:left;background:#EFF6FF;border-radius:16px;padding:20px;margin-bottom:24px;border:1px solid #DBEAFE}
.community .tag{font-size:12px;font-weight:600;color:#1E40AF;margin-bottom:6px;letter-spacing:1px}
.community h3{font-size:16px;font-weight:700;color:#1F2937;margin-bottom:4px}
.community .id{font-size:13px;color:#6B7280;margin-bottom:8px}
.community .hint{font-size:12px;color:#6B7280;line-height:1.6}
.btn-group{margin-top:4px}
.btn{display:block;width:100%;height:46px;line-height:46px;text-align:center;border-radius:12px;font-size:15px;font-weight:600;text-decoration:none;transition:all 0.2s;cursor:pointer}
.btn-primary{background:linear-gradient(135deg,#1E40AF,#3B82F6);color:#fff;box-shadow:0 4px 12px rgba(30,64,175,0.2)}
.btn-primary:hover{box-shadow:0 6px 20px rgba(30,64,175,0.3);transform:translateY(-1px)}
.btn-primary:active{transform:translateY(0)}
.btn-outline{background:transparent;color:#1E40AF;border:1px solid rgba(30,64,175,0.25);margin-top:10px;font-size:13px;font-weight:500}
.btn-outline:hover{border-color:#1E40AF;background:rgba(30,64,175,0.04)}
.footer{font-size:11px;color:#9CA3AF;margin-top:28px;letter-spacing:0.5px}
</style>
</head>
<body>
<div class="card">
<div class="logo">Aurora Chat</div>
<div class="subtitle">即时通讯 · 端到端加密</div>
<div class="divider"></div>
<div class="status-badge">服务暂不可用</div>
<div class="desc">
<p>当前服务器实例处于离线状态。</p>
<p style="margin-top:8px">服务暂时无法连接，历史聊天记录会在恢复后重新加载。</p>
<p class="tech">Aurora Chat 私聊消息采用端到端加密（ECDH + AES-256-GCM），服务器仅存储密文，无法读取聊天内容。</p>
</div>
<div class="community">
<div class="tag">官方社区</div>
<h3>Aurora Chat 官方 QQ 群</h3>
<div class="id">群号: 1084612899</div>
<div class="hint">服务器宕机期间，您可前往官方群获取免费服务器托管服务。专业运维团队提供 7×24 小时技术支持，协助您快速恢复服务节点至可用状态。</div>
</div>
<div class="btn-group">
<a class="btn btn-primary" href="https://qun.qq.com/universal-share/share?ac=1&authKey=YOUR_GROUP_AUTH_KEY&tempid=h5_group_info">加入官方群获取托管</a>
</div>
<div class="footer">Powered by Aurora Chat</div>
</div>
</body>
</html>`)
		return
	}

	// 判断后续路径
	remain := ""
	if len(parts) >= 2 {
		remain = parts[1]
	}

	// /serve/{serverId}/files/{storageKey} 保持原有文件直链逻辑
	if remain == "files" {
		if len(parts) < 3 || parts[2] == "" {
			writeJSON(w, 400, "缺少文件key", nil)
			return
		}
		serveServerFileByStorageKey(w, r, serverID, parts[2])
		return
	}

	// /serve/{serverId}/dl/{fileName} — 下载落地页（显示署名、群号、跳转按钮）
	if remain == "dl" {
		if len(parts) < 3 || parts[2] == "" {
			writeJSON(w, 400, "缺少文件名", nil)
			return
		}
		serveDownloadLandingPage(w, r, serverID, parts[2])
		return
	}

	// 网站托管：支持访问根目录下的任意文件（用于网站资源引用、安装包下载等）
	fileName := remain
	if fileName == "" {
		fileName = "index.html"
	}

	// 按文件名在根目录查找
	var storageKey string
	var isDir int
	err = db.QueryRow(
		"SELECT storage_key, is_dir FROM server_files WHERE file_name = ? AND server_id = ? AND is_dir = 0 AND parent_id IS NULL",
		fileName, serverID,
	).Scan(&storageKey, &isDir)

	if err != nil {
		// 如果访问的是 index.html 没找到，返回简洁的提示页（不展示文件列表）
		if fileName == "index.html" {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Access-Control-Allow-Origin", "*")
			fmt.Fprint(w, `<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>服务器</title><style>*{margin:0;padding:0;box-sizing:border-box}body{font-family:system-ui,sans-serif;background:#f5f7fa;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:24px}.card{max-width:400px;width:100%;background:#fff;border-radius:20px;padding:40px;box-shadow:0 4px 24px rgba(0,0,0,.06);text-align:center}h2{font-size:22px;color:#1f2937;margin-bottom:8px}p{font-size:14px;color:#6b7280;line-height:1.6}.hint{margin-top:20px;font-size:12px;color:#9ca3af}</style></head><body><div class="card"><h2>服务器运行正常</h2><p>该服务器已启用，但未配置网站首页。<br>请上传 <code>index.html</code> 到根目录以启用网站。</p><div class="hint">Powered by Aurora Chat</div></div></body></html>`)
			return
		}
		writeJSON(w, 404, "文件不存在", nil)
		return
	}

	if isDir == 1 {
		writeJSON(w, 400, "不能访问文件夹", nil)
		return
	}

	// 可下载文件类型拦截：显示落地页而非直接下载
	if isDownloadableFile(fileName) {
		// 重定向到下载落地页
		http.Redirect(w, r, fmt.Sprintf("/serve/%d/dl/%s", serverID, url.PathEscape(fileName)), http.StatusFound)
		return
	}

	serveServerFileByStorageKey(w, r, serverID, storageKey)
}

// isDownloadableFile 判断是否为"可下载"文件类型（需显示落地页）
// 网站资源（html/css/js/图片/字体等）不拦截，保证网站正常运行
func isDownloadableFile(fileName string) bool {
	ext := strings.ToLower(filepath.Ext(fileName))
	switch ext {
	case ".apk", ".xapk", ".apks":
		return true
	case ".exe", ".msi", ".dmg", ".app", ".deb", ".rpm", ".appimage":
		return true
	case ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz":
		return true
	case ".pdf":
		return true
	case ".mp4", ".avi", ".mkv", ".mov", ".flv", ".wmv", ".webm", ".m4v":
		return true
	case ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".wma":
		return true
	case ".iso":
		return true
	default:
		return false
	}
}

// 返回服务器文件浏览页面（含文件列表 + 平台介绍 + QQ群加入）
func serveServerIndexPage(w http.ResponseWriter, r *http.Request, serverID int64) {
	// 查询该服务器的文件列表（根目录，非文件夹）
	rows, err := db.Query(
		"SELECT file_name, file_size, storage_key, mime_type FROM server_files WHERE server_id = ? AND is_dir = 0 AND parent_id IS NULL ORDER BY created_at DESC",
		serverID,
	)
	files := []struct {
		Name     string
		Size     int64
		Key      string
		MimeType string
	}{}
	if err == nil && rows != nil {
		for rows.Next() {
			var f struct {
				Name     string
				Size     int64
				Key      string
				MimeType string
			}
			rows.Scan(&f.Name, &f.Size, &f.Key, &f.MimeType)
			files = append(files, f)
		}
		rows.Close()
	}

	// 构建文件列表HTML
	var fileListHTML string
	if len(files) > 0 {
		fileListHTML = `<div class="section file-list"><div class="title">文件列表</div><div class="files">`
		for _, f := range files {
			ext := strings.ToLower(filepath.Ext(f.Name))
			isImg := ext == ".jpg" || ext == ".jpeg" || ext == ".png" || ext == ".gif" || ext == ".webp" || ext == ".bmp"
			isVideo := ext == ".mp4" || ext == ".webm" || ext == ".mov" || ext == ".avi" || ext == ".mkv"
			isTxt := ext == ".txt" || ext == ".md" || ext == ".json" || ext == ".xml" || ext == ".html" || ext == ".css" || ext == ".js" || ext == ".log"
			fileSize := formatFileSize(f.Size)

			if isImg {
				fileListHTML += fmt.Sprintf(`
<div class="file-item">
  <div class="file-preview">
    <img src="/serve/%d/files/%s" alt="%s" loading="lazy" />
  </div>
  <div class="file-info">
    <div class="file-name">%s</div>
    <div class="file-meta">%s</div>
  </div>
  <a class="file-download" href="/serve/%d/files/%s" download="%s">下载</a>
</div>`, serverID, f.Key, f.Name, f.Name, fileSize, serverID, f.Key, f.Name)
			} else if isVideo {
				fileListHTML += fmt.Sprintf(`
<div class="file-item">
  <div class="file-preview video">
    <video src="/serve/%d/files/%s" controls preload="metadata"></video>
  </div>
  <div class="file-info">
    <div class="file-name">%s</div>
    <div class="file-meta">%s</div>
  </div>
  <a class="file-download" href="/serve/%d/files/%s" download="%s">下载</a>
</div>`, serverID, f.Key, f.Name, fileSize, serverID, f.Key, f.Name)
			} else if isTxt {
				fileListHTML += fmt.Sprintf(`
<div class="file-item">
  <div class="file-icon">TXT</div>
  <div class="file-info">
    <div class="file-name">%s</div>
    <div class="file-meta">%s</div>
  </div>
  <a class="file-download" href="/serve/%d/files/%s" target="_blank">查看</a>
</div>`, f.Name, fileSize, serverID, f.Key)
			} else {
				fileListHTML += fmt.Sprintf(`
<div class="file-item">
  <div class="file-icon">FILE</div>
  <div class="file-info">
    <div class="file-name">%s</div>
    <div class="file-meta">%s</div>
  </div>
  <a class="file-download" href="/serve/%d/files/%s" download="%s">下载</a>
</div>`, f.Name, fileSize, serverID, f.Key, f.Name)
			}
		}
		fileListHTML += `</div></div>`
	} else {
		fileListHTML = `<div class="section file-list"><div class="title">文件列表</div><div class="empty">暂无文件</div></div>`
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	fmt.Fprintf(w, `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Aurora Chat — 文件浏览</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;background:linear-gradient(135deg,#F0F4FF 0%%,#E8EEF9 100%%);min-height:100vh;padding:24px;display:flex;justify-content:center}
.container{max-width:640px;width:100%%}
.card{max-width:560px;width:100%%;background:rgba(255,255,255,0.9);backdrop-filter:blur(20px);border-radius:24px;padding:36px 32px;box-shadow:0 8px 40px rgba(30,64,175,0.06);border:1px solid rgba(255,255,255,0.7);margin-bottom:16px}
.logo{font-size:28px;font-weight:700;background:linear-gradient(135deg,#1E40AF,#3B82F6);-webkit-background-clip:text;-webkit-text-fill-color:transparent;background-clip:text;letter-spacing:-0.5px;margin-bottom:4px;text-align:center}
.subtitle{text-align:center;font-size:13px;color:#6B7280;letter-spacing:1.5px;margin-bottom:6px}
.divider{width:64px;height:2px;margin:18px auto;background:linear-gradient(90deg,transparent,#1E40AF,transparent);opacity:0.3}
.section{background:#F8FAFC;border-radius:16px;padding:18px;margin-bottom:14px;border:1px solid #E5E7EB}
.section .title{font-size:14px;font-weight:600;color:#1F2937;margin-bottom:10px}
.section .tag{display:inline-block;background:#EFF6FF;color:#1E40AF;font-size:11px;font-weight:500;padding:2px 10px;border-radius:12px;margin-right:6px;margin-bottom:6px}
.community{background:linear-gradient(135deg,#EFF6FF,#F0F4FF);border-radius:16px;padding:20px;border:1px solid #DBEAFE;text-align:center;margin-bottom:14px}
.community .label{font-size:12px;font-weight:600;color:#1E40AF;margin-bottom:6px;letter-spacing:1px}
.community h3{font-size:17px;font-weight:700;color:#1F2937}
.community .qid{font-size:22px;font-weight:800;color:#1E40AF;margin:8px 0;letter-spacing:2px}
.btn{display:block;width:100%%;height:46px;line-height:46px;text-align:center;border-radius:12px;font-size:15px;font-weight:600;text-decoration:none;transition:all 0.2s}
.btn-primary{background:linear-gradient(135deg,#1E40AF,#3B82F6);color:#fff;box-shadow:0 4px 12px rgba(30,64,175,0.2)}
.btn-primary:hover{box-shadow:0 6px 20px rgba(30,64,175,0.3);transform:translateY(-1px)}
.footer{text-align:center;font-size:11px;color:#9CA3AF;margin-top:20px;letter-spacing:0.5px}
.file-item{display:flex;align-items:center;padding:10px 0;border-bottom:1px solid #E5E7EB;gap:12px}
.file-item:last-child{border-bottom:none}
.file-preview{width:60px;height:60px;flex-shrink:0;border-radius:8px;overflow:hidden;background:#F0F0F0;display:flex;align-items:center;justify-content:center}
.file-preview img{width:100%%;height:100%%;object-fit:cover}
.file-preview video{width:100%%;height:100%%;object-fit:cover}
.file-icon{width:40px;height:40px;flex-shrink:0;background:linear-gradient(135deg,#1E40AF,#3B82F6);color:#fff;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:10px;font-weight:700}
.file-info{flex:1;min-width:0}
.file-name{font-size:13px;font-weight:500;color:#1F2937;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.file-meta{font-size:11px;color:#9CA3AF;margin-top:2px}
.file-download{flex-shrink:0;padding:6px 14px;background:linear-gradient(135deg,#1E40AF,#3B82F6);color:#fff;border-radius:8px;font-size:12px;font-weight:500;text-decoration:none}
.empty{text-align:center;font-size:13px;color:#9CA3AF;padding:16px 0}
</style>
</head>
<body>
<div class="container">
<div class="card">
<div class="logo">Aurora Chat</div>
<div class="subtitle">即时通讯 · 端到端加密</div>
<div class="divider"></div>
<div class="section">
<div class="title">平台优势</div>
<p style="font-size:13px;line-height:1.7;color:#4B5563;margin-bottom:8px">Aurora Chat 私聊消息使用端到端加密（ECDH + AES-256-GCM），聊天内容在服务器上仅以密文形式保存。</p>
<span class="tag">端到端加密</span><span class="tag">私密社区</span><span class="tag">应用市场</span><span class="tag">文件系统</span><span class="tag">远程服务器</span>
</div>
</div>

%s

<div class="card">
<div class="community">
<div class="label">官方社区</div>
<h3>Aurora Chat 官方 QQ 群</h3>
<div class="qid">1084612899</div>
<a class="btn btn-primary" href="https://qun.qq.com/universal-share/share?ac=1&authKey=YOUR_GROUP_AUTH_KEY&tempid=h5_group_info">加入官方群</a>
</div>
<div class="footer">Powered by Aurora Chat</div>
</div>
</div>
</body>
</html>`, fileListHTML)
}

// 通过 storageKey 返回文件
func serveServerFileByStorageKey(w http.ResponseWriter, r *http.Request, serverID int64, storageKey string) {
	var fileName string
	var isDir int
	err := db.QueryRow(
		"SELECT file_name, is_dir FROM server_files WHERE storage_key = ? AND server_id = ?",
		storageKey, serverID,
	).Scan(&fileName, &isDir)
	if err != nil {
		writeJSON(w, 404, "文件不存在", nil)
		return
	}
	if isDir == 1 {
		writeJSON(w, 400, "不能下载文件夹", nil)
		return
	}

	// 查找文件物理路径
	filePath := filepath.Join(serverFilesDir, "files", storageKey)
	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		filePath = findFileInFolders(storageKey)
		if filePath == "" {
			writeJSON(w, 404, "文件已丢失", nil)
			return
		}
	}

	// 根据文件扩展名设置 Content-Type
	mimeType := detectMimeByExtension(fileName)
	w.Header().Set("Content-Type", mimeType)
	// 文本类文件直接内联显示，其他附件下载
	textTypes := []string{"text/", "image/", "application/javascript", "application/json", "application/typescript", "application/pdf"}
	isInline := false
	for _, t := range textTypes {
		if strings.HasPrefix(mimeType, t) {
			isInline = true
			break
		}
	}
	if isInline {
		w.Header().Set("Content-Disposition", fmt.Sprintf(`inline; filename="%s"`, fileName))
	} else {
		w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, fileName))
	}
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
	http.ServeFile(w, r, filePath)
}

// ============================================================
// 下载落地页（显示署名、QQ群号、跳转按钮）
// ============================================================

func serveDownloadLandingPage(w http.ResponseWriter, r *http.Request, serverID int64, fileName string) {
	// 查询文件信息（验证文件存在）
	var fileSize int64
	var storageKey string
	err := db.QueryRow(
		"SELECT file_size, storage_key FROM server_files WHERE file_name = ? AND server_id = ? AND is_dir = 0 AND parent_id IS NULL",
		fileName, serverID,
	).Scan(&fileSize, &storageKey)

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Access-Control-Allow-Origin", "*")

	// QQ群跳转链接
	qqGroupUrl := "https://qun.qq.com/universal-share/share?ac=1&authKey=YOUR_GROUP_AUTH_KEY&tempid=h5_group_info"
	qqGroupNumber := "10846124899"

	fileSizeStr := formatFileSize(fileSize)
	if err != nil {
		// 文件不存在，也显示页面（只是文件大小显示为未知）
		fileSizeStr = "未知"
	}

	// 文件扩展名判断图标类型
	ext := strings.ToLower(filepath.Ext(fileName))
	var fileIcon string
	var fileTypeText string
	switch ext {
	case ".apk":
		fileIcon = "APK"
		fileTypeText = "APK 安装包"
	case ".zip", ".rar", ".7z":
		fileIcon = "ZIP"
		fileTypeText = "压缩包"
	case ".exe":
		fileIcon = "EXE"
		fileTypeText = "Windows 程序"
	case ".pdf":
		fileIcon = "PDF"
		fileTypeText = "PDF 文档"
	case ".mp4", ".avi", ".mkv":
		fileIcon = "MP4"
		fileTypeText = "视频文件"
	case ".mp3", ".wav", ".flac":
		fileIcon = "MP3"
		fileTypeText = "音频文件"
	default:
		fileIcon = "FILE"
		fileTypeText = strings.ToUpper(strings.TrimPrefix(ext, ".")) + " 文件"
		if fileTypeText == " 文件" {
			fileTypeText = "文件"
		}
	}

	// 真正的文件下载直链（通过 storageKey 直达，不会经过落地页拦截）
	downloadUrl := fmt.Sprintf("/serve/%d/files/%s", serverID, storageKey)

	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>下载 · %s</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
:root{
  --bg:#08080a;
  --card:rgba(22,22,26,0.88);
  --border:rgba(255,255,255,0.08);
  --text:#f5f5f7;
  --text-dim:#86868b;
  --accent:#e0c36a;
}
body{
  font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display","Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
  background:var(--bg);
  background-image:
    radial-gradient(ellipse 80%% 50%% at 50%% -20%%,rgba(224,195,106,0.06),transparent),
    radial-gradient(ellipse 60%% 40%% at 50%% 120%%,rgba(120,120,255,0.04),transparent);
  background-attachment:fixed;
  min-height:100vh;
  display:flex;
  align-items:center;
  justify-content:center;
  padding:20px;
  color:var(--text);
  -webkit-font-smoothing:antialiased;
}
.wrap{
  max-width:440px;
  width:100%%;
}
.card{
  background:var(--card);
  backdrop-filter:blur(40px) saturate(1.2);
  -webkit-backdrop-filter:blur(40px) saturate(1.2);
  border:1px solid var(--border);
  border-radius:24px;
  padding:40px 32px 32px;
  box-shadow:0 24px 80px rgba(0,0,0,0.5),inset 0 1px 0 rgba(255,255,255,0.04);
}

/* 文件信息 */
.file-head{
  text-align:center;
  margin-bottom:32px;
}
.file-badge{
  width:80px;height:80px;
  display:flex;align-items:center;justify-content:center;
  margin:0 auto 20px;
  border-radius:22px;
  border:1px solid rgba(224,195,106,0.25);
  background:linear-gradient(180deg,rgba(224,195,106,0.08),rgba(224,195,106,0.02));
  box-shadow:0 4px 24px rgba(224,195,106,0.06);
}
.file-badge span{
  font-size:15px;
  font-weight:700;
  letter-spacing:2px;
  color:var(--accent);
}
.file-name{
  font-size:17px;
  font-weight:600;
  color:var(--text);
  word-break:break-all;
  line-height:1.5;
  margin-bottom:8px;
}
.file-meta{
  font-size:12px;
  color:var(--text-dim);
  letter-spacing:0.5px;
}
.file-meta .sep{
  margin:0 8px;
  opacity:0.4;
}

/* 分割线 */
.line{
  height:1px;
  background:linear-gradient(90deg,transparent,var(--border),transparent);
  margin:0 0 28px;
}

/* QQ群卡片 */
.group-box{
  background:rgba(255,255,255,0.03);
  border:1px solid var(--border);
  border-radius:16px;
  padding:18px 20px;
  margin-bottom:28px;
}
.group-label{
  font-size:10px;
  font-weight:600;
  color:var(--accent);
  letter-spacing:2px;
  text-transform:uppercase;
  margin-bottom:10px;
  opacity:0.8;
}
.group-name{
  font-size:14px;
  font-weight:500;
  color:var(--text);
  margin-bottom:2px;
}
.group-num{
  font-size:20px;
  font-weight:700;
  color:var(--accent);
  letter-spacing:2px;
  margin-bottom:6px;
}
.group-tip{
  font-size:11px;
  color:var(--text-dim);
  line-height:1.6;
}

/* 按钮区 */
.btns{
  display:flex;
  flex-direction:column;
  gap:12px;
}
.btn{
  display:flex;
  align-items:center;
  justify-content:center;
  gap:8px;
  height:52px;
  border-radius:14px;
  font-size:15px;
  font-weight:600;
  text-decoration:none;
  transition:all 0.25s ease;
  cursor:pointer;
}
.btn-primary{
  background:#f5f5f7;
  color:#08080a;
  border:1px solid #f5f5f7;
}
.btn-primary:hover{
  background:#fff;
  transform:translateY(-1px);
  box-shadow:0 8px 28px rgba(245,245,247,0.12);
}
.btn-secondary{
  background:transparent;
  color:var(--accent);
  border:1px solid rgba(224,195,106,0.3);
}
.btn-secondary:hover{
  border-color:rgba(224,195,106,0.6);
  background:rgba(224,195,106,0.06);
  transform:translateY(-1px);
}
.btn-arrow{
  font-size:14px;
  opacity:0.6;
  transition:transform 0.25s ease;
}
.btn:hover .btn-arrow{
  transform:translateX(4px);
  opacity:1;
}

/* 底部 */
.foot{
  text-align:center;
  margin-top:28px;
  padding-top:20px;
  border-top:1px solid var(--border);
}
.foot-text{
  font-size:11px;
  color:var(--text-dim);
  letter-spacing:0.5px;
}
.foot-brand{
  font-size:12px;
  font-weight:600;
  color:var(--text);
  margin-top:4px;
  letter-spacing:1px;
}
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <!-- 文件信息 -->
    <div class="file-head">
      <div class="file-badge"><span>%s</span></div>
      <div class="file-name">%s</div>
      <div class="file-meta">
        <span>%s</span>
        <span class="sep">|</span>
        <span>%s</span>
      </div>
    </div>

    <div class="line"></div>

    <!-- QQ群信息 -->
    <div class="group-box">
      <div class="group-label">官方社区</div>
      <div class="group-name">Aurora Chat 官方交流群</div>
      <div class="group-num">%s</div>
      <div class="group-tip">加入群聊获取更多资源与技术支持</div>
    </div>

    <!-- 按钮 -->
    <div class="btns">
      <a href="%s" class="btn btn-primary" download="%s">
        下载文件
        <span class="btn-arrow">&rarr;</span>
      </a>
      <a href="%s" class="btn btn-secondary" target="_blank">
        加入群聊
        <span class="btn-arrow">&rarr;</span>
      </a>
    </div>

    <!-- 署名 -->
    <div class="foot">
      <div class="foot-text">本服务由</div>
      <div class="foot-brand">Aurora Chat 提供技术支持</div>
    </div>
  </div>
</div>
</body>
</html>`, fileName, fileIcon, fileName, fileTypeText, fileSizeStr, qqGroupNumber, downloadUrl, fileName, qqGroupUrl)

	w.Write([]byte(html))
}

// ============================================================
// MIME 类型检测（与之前一致）
// ============================================================

func detectMimeByExtension(fileName string) string {
	ext := strings.ToLower(filepath.Ext(fileName))
	switch ext {
	case ".txt", ".md", ".json", ".xml", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".log", ".csv":
		return "text/plain"
	case ".html", ".htm":
		return "text/html"
	case ".css":
		return "text/css"
	case ".js":
		return "application/javascript"
	case ".py":
		return "text/x-python"
	case ".java":
		return "text/x-java"
	case ".kt", ".kts":
		return "text/x-kotlin"
	case ".go":
		return "text/x-go"
	case ".cpp", ".cc", ".cxx":
		return "text/x-c++"
	case ".c":
		return "text/x-c"
	case ".h", ".hpp":
		return "text/x-c-header"
	case ".rs":
		return "text/x-rust"
	case ".ts":
		return "application/typescript"
	case ".sh", ".bash":
		return "text/x-shellscript"
	case ".bat", ".cmd":
		return "text/x-bat"
	case ".swift":
		return "text/x-swift"
	case ".rb":
		return "text/x-ruby"
	case ".php":
		return "text/x-php"
	case ".sql":
		return "text/x-sql"
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".svg":
		return "image/svg+xml"
	case ".ico":
		return "image/x-icon"
	case ".bmp":
		return "image/bmp"
	case ".mp4":
		return "video/mp4"
	case ".avi":
		return "video/x-msvideo"
	case ".mov":
		return "video/quicktime"
	case ".mkv":
		return "video/x-matroska"
	case ".mp3":
		return "audio/mpeg"
	case ".wav":
		return "audio/wav"
	case ".ogg":
		return "audio/ogg"
	case ".flac":
		return "audio/flac"
	case ".aac":
		return "audio/aac"
	case ".pdf":
		return "application/pdf"
	case ".doc", ".docx":
		return "application/msword"
	case ".xls", ".xlsx":
		return "application/vnd.ms-excel"
	case ".ppt", ".pptx":
		return "application/vnd.ms-powerpoint"
	case ".zip":
		return "application/zip"
	case ".rar":
		return "application/vnd.rar"
	case ".7z":
		return "application/x-7z-compressed"
	case ".tar":
		return "application/x-tar"
	case ".gz":
		return "application/gzip"
	case ".apk":
		return "application/vnd.android.package-archive"
	case ".exe":
		return "application/x-msdownload"
	case ".deb":
		return "application/vnd.debian.binary-package"
	case ".rpm":
		return "application/x-rpm"
	default:
		return "application/octet-stream"
	}
}
