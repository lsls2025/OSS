package main

import (
	"archive/zip"
	"bytes"
	"database/sql"
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

func siteSubdomainEnabled() bool {
	v := strings.TrimSpace(os.Getenv("SITE_SUBDOMAIN"))
	if v == "" {
		if b, err := os.ReadFile(siteSubdomainFile()); err == nil {
			v = strings.TrimSpace(string(b))
		}
	}
	return v == "1" || strings.EqualFold(v, "true")
}

// 站点公开访问地址：子域名模式返回 https://s{id}.域名；否则返回 基地址/serve/{id}（旧行为）
func sitePublicURL(serverID int64) string {
	base := getServerBaseURL()
	if serverID > 0 && siteSubdomainEnabled() {
		if u, err := url.Parse(base); err == nil && u.Host != "" {
			host := u.Hostname()
			u2 := *u
			u2.Host = fmt.Sprintf("s%d.%s", serverID, host)
			u2.Path = ""
			u2.RawPath = ""
			u2.RawQuery = ""
			u2.Fragment = ""
			return strings.TrimRight(u2.String(), "/")
		}
	}
	return fmt.Sprintf("%s/serve/%d", base, serverID)
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
	// 每用户 50MB 存储上限（开发者不受限）：先校验再推送，避免产生半成品
	if ok, msg := checkServerFilesQuota(userID, int64(len(content))); !ok {
		writeJSON(w, 400, msg, nil)
		return
	}
	// 先强制同步到 150 站点服务：未送达则直接失败，绝不“默认成功”
	rel := resolveRelPath(server.ID, req.ParentID, req.FileName)
	if err := pushFileToSiteServerSync(server.ID, rel, content); err != nil {
		log.Printf("[文件] 创建中止（150 未收到）: %v", err)
		writeJSON(w, 502, "文件未能同步到站点服务器（150），创建已取消："+err.Error(), nil)
		return
	}
	// 150 已确认收到，再落本地与数据库
	if err := os.WriteFile(filePath, content, 0644); err != nil {
		log.Printf("[文件] 写入本地文件失败: %v", err)
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

	// 生成可访问的下载链接（与站点静态服务的目录结构一致：按文件夹层级）
	baseURL := getServerBaseURL()
	downloadURL := fmt.Sprintf("%s/serve/%d/%s", baseURL, server.ID, rel)

	log.Printf("[文件] 创建成功（已同步150）: server=%d, name=%s, size=%d", server.ID, req.FileName, len(content))
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

	// 批次5 B3：统一上传扩展名校验；网站托管额外放行 HTML/CSS/JS 等静态站点资源
	if ok, reason := validateHostingUploadExt(header.Filename); !ok {
		writeJSON(w, 400, reason, nil)
		return
	}

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

	// 先读入内存，再强制同步到 150：未送达则直接失败，绝不“默认成功”
	var buf bytes.Buffer
	written, err := io.Copy(&buf, file)
	if err != nil {
		log.Printf("[文件] 读取上传流失败: %v", err)
		writeJSON(w, 500, "上传失败", nil)
		return
	}
	contentBytes := buf.Bytes()
	// 每用户 50MB 存储上限（开发者不受限）：先校验再推送
	if ok, msg := checkServerFilesQuota(userID, int64(len(contentBytes))); !ok {
		writeJSON(w, 400, msg, nil)
		return
	}
	rel := resolveRelPath(server.ID, parentID, header.Filename)
	if err := pushFileToSiteServerSync(server.ID, rel, contentBytes); err != nil {
		log.Printf("[文件] 上传中止（150 未收到）: %v", err)
		writeJSON(w, 502, "文件未能同步到站点服务器（150），上传已取消："+err.Error(), nil)
		return
	}

	fileName := header.Filename
	mimeType := header.Header.Get("Content-Type")
	if mimeType == "" || mimeType == "application/octet-stream" {
		mimeType = detectMimeByExtension(fileName)
	}

	// 150 已确认收到，再落本地
	if err := os.WriteFile(filePath, contentBytes, 0644); err != nil {
		log.Printf("[文件] 写入本地文件失败: %v", err)
		writeJSON(w, 500, "存储失败", nil)
		return
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

	// 生成可访问的下载链接（与站点静态服务的目录结构一致：按文件夹层级）
	baseURL := getServerBaseURL()
	downloadURL := fmt.Sprintf("%s/serve/%d/%s", baseURL, server.ID, rel)

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
			// 按文件夹层级生成访问地址（与站点静态服务的目录结构一致）
			f.DownloadURL = fmt.Sprintf("%s/serve/%d/%s", baseURL, server.ID, resolveRelPath(server.ID, f.ParentID, f.FileName))
		}
		items = append(items, f)
	}

	if items == nil {
		items = []ServerFileItem{}
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"files":         items,
		"server_domain": server.Domain,
		// 直接给完整站点地址（子域名模式下形如 https://s1.域名，不带 /serve/{id} 路径），
		// 客户端不再自行拼接
		"server_url": sitePublicURL(server.ID),
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
	var serverID int64
	var isDir int
	var parentID sql.NullInt64
	err = db.QueryRow(
		"SELECT server_id, file_name, is_dir, parent_id FROM server_files WHERE id = ?",
		fileID,
	).Scan(&serverID, &fileName, &isDir, &parentID)
	if err != nil {
		writeJSON(w, 404, "文件不存在", nil)
		return
	}
	if isDir == 1 {
		writeJSON(w, 400, "不能下载文件夹", nil)
		return
	}

	// 校验文件所属服务器确实属于当前用户（支持多服务器 / 多托管项目，
	// 不再硬编码“首个服务器”，避免非主服务器的文件下载被 403 拦截）
	var ownerID int64
	err = db.QueryRow("SELECT owner_user_id FROM servers WHERE id = ?", serverID).Scan(&ownerID)
	if err != nil || ownerID != userID {
		writeJSON(w, 403, "无权访问此文件", nil)
		return
	}

	// App 编辑/打开：优先读后端本地权威副本（服务端读取，不经 nginx 反代，
	// 永远最新），彻底规避「刚保存就被反代缓存的旧内容顶掉」。仅本地缺失才回退 150。
	_, _, _, _, phys, ferr := resolveServerFile(fileID)
	if ferr == nil {
		w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, fileName))
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Cache-Control", "no-store")
		http.ServeFile(w, r, phys)
		return
	}

	// 回退：从站点服务（150）拉取（带防缓存时间戳，避免命中反代缓存）
	var pid *int64
	if parentID.Valid {
		v := parentID.Int64
		pid = &v
	}
	rel := resolveRelPath(serverID, pid, fileName)
	target := fmt.Sprintf("%s/serve/%d/%s?_=%d", strings.TrimRight(siteServerURL(), "/"), serverID, rel, time.Now().UnixNano())
	req, _ := http.NewRequest(http.MethodGet, target, nil)
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := http.DefaultClient.Do(req)
	if err == nil && resp.StatusCode == 200 {
		defer resp.Body.Close()
		w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, fileName))
		if ct := resp.Header.Get("Content-Type"); ct != "" {
			w.Header().Set("Content-Type", ct)
		} else {
			w.Header().Set("Content-Type", "application/octet-stream")
		}
		io.Copy(w, resp.Body)
		return
	}
	if resp != nil {
		resp.Body.Close()
	}
	log.Printf("[文件] 150 拉取失败(本地也已缺失): target=%s err=%v", target, err)
	writeJSON(w, 404, "文件实体不存在：本地与站点服务均不可用", nil)
}

// 根据文件 ID 解析物理路径（根目录或子文件夹中）。
func resolveServerFile(fileID int64) (fileName string, storageKey string, serverID int64, isDir bool, phys string, err error) {
	var sid int64
	var d int
	e := db.QueryRow("SELECT server_id, file_name, storage_key, is_dir FROM server_files WHERE id = ?", fileID).
		Scan(&sid, &fileName, &storageKey, &d)
	if e != nil {
		return "", "", 0, false, "", fmt.Errorf("文件不存在")
	}
	isDir = d == 1
	p := filepath.Join(serverFilesDir, "files", storageKey)
	if _, e2 := os.Stat(p); os.IsNotExist(e2) {
		p = findFileInFolders(storageKey)
	}
	if p == "" {
		return "", "", 0, false, "", fmt.Errorf("文件已丢失")
	}
	return fileName, storageKey, sid, isDir, p, nil
}

// GET /api/server/files/zip-info?id= — 预览 zip 内容（不解压）
func handleServerFileZipInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	fileID, _ := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
	if fileID <= 0 {
		writeJSON(w, 400, "文件 ID 无效", nil)
		return
	}
	fileName, _, serverID, isDir, phys, e := resolveServerFile(fileID)
	if e != nil {
		writeJSON(w, 404, e.Error(), nil)
		return
	}
	if isDir {
		writeJSON(w, 400, "不是文件", nil)
		return
	}
	server, e := getOrCreateServerByOwner(userID)
	if e != nil || server == nil || server.ID != serverID {
		writeJSON(w, 403, "无权访问此文件", nil)
		return
	}
	if !strings.EqualFold(filepath.Ext(fileName), ".zip") {
		writeJSON(w, 400, "仅支持 zip 文件", nil)
		return
	}
	zr, e := zip.OpenReader(phys)
	if e != nil {
		writeJSON(w, 500, "打开 zip 失败: "+e.Error(), nil)
		return
	}
	defer zr.Close()
	entries := make([]map[string]interface{}, 0, len(zr.File))
	for _, zf := range zr.File {
		entries = append(entries, map[string]interface{}{
			"name":   zf.Name,
			"size":   zf.UncompressedSize64,
			"is_dir": zf.FileInfo().IsDir(),
		})
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"name":    fileName,
		"count":   len(entries),
		"entries": entries,
	})
}

// 同层级下生成不重名的文件夹名。
func uniqueFolderName(serverID int64, name string, parentID *int64) string {
	candidate := name
	for i := 1; ; i++ {
		var exists int
		db.QueryRow(
			"SELECT COUNT(*) FROM server_files WHERE server_id = ? AND file_name = ? AND parent_id IS ? AND is_dir = 1",
			serverID, candidate, parentID,
		).Scan(&exists)
		if exists == 0 {
			return candidate
		}
		candidate = fmt.Sprintf("%s (%d)", name, i)
	}
}

// zip 内部目录（去掉末尾文件名）。
func zipEntryDir(name string) string {
	name = strings.TrimSuffix(strings.TrimSuffix(name, "/"), "\\")
	idx := strings.LastIndexAny(name, "/\\")
	if idx < 0 {
		return ""
	}
	return name[:idx]
}

// POST /api/server/files/unzip?id= — 解压 zip 到同名新文件夹，并登记进数据库
func handleServerFileUnzip(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "请先登录", nil)
		return
	}
	fileID, _ := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
	if fileID <= 0 {
		writeJSON(w, 400, "文件 ID 无效", nil)
		return
	}
	var fileName, storageKey string
	var serverID int64
	var parentID sql.NullInt64
	e := db.QueryRow("SELECT server_id, file_name, storage_key, parent_id FROM server_files WHERE id = ?", fileID).
		Scan(&serverID, &fileName, &storageKey, &parentID)
	if e != nil {
		writeJSON(w, 404, "文件不存在", nil)
		return
	}
	server, e := getOrCreateServerByOwner(userID)
	if e != nil || server == nil || server.ID != serverID {
		writeJSON(w, 403, "无权访问此文件", nil)
		return
	}
	if !strings.EqualFold(filepath.Ext(fileName), ".zip") {
		writeJSON(w, 400, "仅支持 zip 文件", nil)
		return
	}
	zipPhys := filepath.Join(serverFilesDir, "files", storageKey)
	if _, e2 := os.Stat(zipPhys); os.IsNotExist(e2) {
		zipPhys = findFileInFolders(storageKey)
	}
	if zipPhys == "" {
		writeJSON(w, 404, "文件已丢失", nil)
		return
	}

	// 解压前先算解压后总大小并校验配额（开发者不受限），避免建了一半目录再失败
	zr, e := zip.OpenReader(zipPhys)
	if e != nil {
		writeJSON(w, 500, "打开 zip 失败: "+e.Error(), nil)
		return
	}
	defer zr.Close()
	var unzipBytes int64
	for _, zf := range zr.File {
		if !zf.FileInfo().IsDir() {
			unzipBytes += int64(zf.UncompressedSize64)
		}
	}
	if ok, msg := checkServerFilesQuota(userID, unzipBytes); !ok {
		writeJSON(w, 400, msg, nil)
		return
	}

	// 目标根文件夹：与 zip 同级，名为 <zip 名去扩展>
	baseName := strings.TrimSuffix(fileName, filepath.Ext(fileName))
	var rootParent *int64
	if parentID.Valid {
		p := parentID.Int64
		rootParent = &p
	}
	baseName = uniqueFolderName(server.ID, baseName, rootParent)
	folderKey, _ := generateStorageKey()
	now := time.Now().Unix()
	res, e := db.Exec(
		`INSERT INTO server_files (server_id, user_id, file_name, file_size, storage_key, mime_type, is_dir, parent_id, created_at, updated_at)
		 VALUES (?, ?, ?, 0, ?, 'inode/directory', 1, ?, ?, ?)`,
		server.ID, userID, baseName, folderKey, rootParent, now, now,
	)
	if e != nil {
		writeJSON(w, 500, "创建解压目录失败", nil)
		return
	}
	rootFolderID, _ := res.LastInsertId()
	rootDir := filepath.Join(serverFilesDir, "files", fmt.Sprintf("dir_%d", rootFolderID))
	os.MkdirAll(rootDir, 0755)

	folderCache := map[string]int64{"": rootFolderID}
	var getFolder = func(relDir string) (int64, error) {
		relDir = strings.Trim(relDir, "/\\")
		if id, ok := folderCache[relDir]; ok {
			return id, nil
		}
		parts := strings.Split(relDir, "/")
		curPath := ""
		pid := rootFolderID
		for _, p := range parts {
			if p == "" || p == "." || p == ".." {
				continue
			}
			if strings.Contains(p, "/") || strings.Contains(p, "\\") {
				return 0, fmt.Errorf("非法路径: %s", p)
			}
			curPath = strings.Trim(curPath+"/"+p, "/")
			if id, ok := folderCache[curPath]; ok {
				pid = id
				continue
			}
			key, _ := generateStorageKey()
			r2, e2 := db.Exec(
				`INSERT INTO server_files (server_id, user_id, file_name, file_size, storage_key, mime_type, is_dir, parent_id, created_at, updated_at)
				 VALUES (?, ?, ?, 0, ?, 'inode/directory', 1, ?, ?, ?)`,
				server.ID, userID, p, key, &pid, now, now,
			)
			if e2 != nil {
				return 0, e2
			}
			fid, _ := r2.LastInsertId()
			os.MkdirAll(filepath.Join(serverFilesDir, "files", fmt.Sprintf("dir_%d", fid)), 0755)
			folderCache[curPath] = fid
			pid = fid
		}
		return pid, nil
	}

	for _, zf := range zr.File {
		clean := filepath.Clean(strings.ReplaceAll(zf.Name, "\\", "/"))
		if strings.HasPrefix(clean, "..") {
			continue // 跳过路径穿越条目
		}
		entryName := strings.TrimPrefix(clean, "/")
		if zf.FileInfo().IsDir() {
			if _, e2 := getFolder(entryName); e2 != nil {
				writeJSON(w, 500, "解压失败: "+e2.Error(), nil)
				return
			}
			continue
		}
		dir := zipEntryDir(entryName)
		pid, e2 := getFolder(dir)
		if e2 != nil {
			writeJSON(w, 500, "解压失败: "+e2.Error(), nil)
			return
		}
		base := filepath.Base(entryName)
		if base == "" || base == "." || base == ".." {
			continue
		}
		// 文件名段不允许包含路径分隔符
		if strings.Contains(base, "/") || strings.Contains(base, "\\") {
			continue
		}
		rc, e3 := zf.Open()
		if e3 != nil {
			continue
		}
		fkey, _ := generateStorageKey()
		dstDir := filepath.Join(serverFilesDir, "files", fmt.Sprintf("dir_%d", pid))
		dstPath := filepath.Join(dstDir, fkey)
		out, e3 := os.Create(dstPath)
		if e3 != nil {
			rc.Close()
			continue
		}
		_, e3 = io.Copy(out, rc)
		out.Close()
		rc.Close()
		if e3 != nil {
			os.Remove(dstPath)
			continue
		}
		mime := detectMimeByExtension(base)
		db.Exec(
			`INSERT INTO server_files (server_id, user_id, file_name, file_size, storage_key, mime_type, is_dir, parent_id, created_at, updated_at)
			 VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)`,
			server.ID, userID, base, zf.UncompressedSize64, fkey, mime, &pid, now, now,
		)
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"folder_id":   rootFolderID,
		"folder_name": baseName,
	})
}
