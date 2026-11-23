package main

import (
	"bytes"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
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

// ==================== 站点静态服务（独立部署在静态文件服务器，如旧服务器） ====================
// 业务后端只负责业务，网站托管的静态文件读写交给它，公开访问流量不再经过业务服务器。

func siteServerURL() string {
	if v := strings.TrimRight(strings.TrimSpace(os.Getenv("SITE_SERVER_URL")), "/"); v != "" {
		return v
	}
	return siteBaseURL()
}

func siteServerToken() string {
	return os.Getenv("SITE_SERVER_TOKEN")
}

// 计算文件在站点目录下的相对路径（沿 parent_id 逐级拼父文件夹名）
func resolveRelPath(serverID int64, parentID *int64, fileName string) string {
	segs := []string{fileName}
	cur := parentID
	guard := 0
	for cur != nil && *cur > 0 && guard < 50 {
		var name string
		var pid *int64
		if err := db.QueryRow(
			"SELECT file_name, parent_id FROM server_files WHERE id = ? AND server_id = ?",
			*cur, serverID,
		).Scan(&name, &pid); err != nil {
			break
		}
		segs = append([]string{name}, segs...)
		cur = pid
		guard++
	}
	return strings.Join(segs, "/")
}

// pushFileToSiteServerSync 同步把文件推送到独立站点服务（150）。
// 关键：未送达一律返回错误，绝不“默认成功”；错误中带上真实目标地址，方便定位“连在哪里”。
func pushFileToSiteServerSync(serverID int64, relPath string, content []byte) error {
	base := strings.TrimRight(siteServerURL(), "/")
	token := siteServerToken()
	if base == "" {
		return fmt.Errorf("[站点同步] 站点服务地址未配置：site_base_url 为空且未设置 SITE_SERVER_URL，文件无法推送到 150")
	}
	if relPath == "" {
		return fmt.Errorf("[站点同步] 相对路径为空，无法推送")
	}
	u := fmt.Sprintf("%s/internal/put?server_id=%d&path=%s", base, serverID, url.QueryEscape(relPath))
	log.Printf("[站点同步] 推送目标(150): %s", u)
	req, err := http.NewRequest(http.MethodPut, u, bytes.NewReader(content))
	if err != nil {
		return fmt.Errorf("[站点同步] 构造请求失败 %s: %v", u, err)
	}
	// 站点服务侧按来源 IP 白名单放行；token 只在配置时才附带（二选一即可）
	if token != "" {
		req.Header.Set("X-Site-Token", token)
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("[站点同步] 连接 150 失败（文件未送达）%s: %v", u, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("[站点同步] 150 返回错误 %d %s 目标 %s", resp.StatusCode, string(body), u)
	}
	log.Printf("[站点同步] 已送达 150: server=%d path=%s", serverID, relPath)
	return nil
}

// 把文件推送到独立的站点静态服务（异步；未配置或失败都不影响主流程，仅记日志）
func pushFileToSiteServer(serverID int64, relPath string, content []byte) {
	go func() {
		if err := pushFileToSiteServerSync(serverID, relPath, content); err != nil {
			log.Printf("[站点同步] 推送失败 server=%d path=%s: %v", serverID, relPath, err)
		} else {
			log.Printf("[站点同步] 已推送 server=%d path=%s", serverID, relPath)
		}
	}()
}

// migrateSiteFiles 一次性迁移：把业务库里所有历史文件推送到独立站点服务（150），
// 按文件夹层级存放（与公开访问路径一致），使文件管理里的访问地址可用。
func migrateSiteFiles() {
	log.Println("[迁移] 开始把历史站点文件推送到独立站点服务（按文件夹层级）...")
	rows, err := db.Query(`SELECT server_id, parent_id, file_name, storage_key FROM server_files WHERE is_dir = 0 AND storage_key <> ''`)
	if err != nil {
		log.Printf("[迁移] 查询失败: %v", err)
		return
	}
	defer rows.Close()

	var total, ok, skip int
	for rows.Next() {
		var serverID int64
		var parentID sql.NullInt64
		var fileName, storageKey string
		if err := rows.Scan(&serverID, &parentID, &fileName, &storageKey); err != nil {
			continue
		}
		total++
		phys := filepath.Join(serverFilesDir, "files", storageKey)
		if _, err := os.Stat(phys); os.IsNotExist(err) {
			phys = findFileInFolders(storageKey)
		}
		if phys == "" {
			skip++
			log.Printf("[迁移] 跳过(物理文件缺失): server=%d %s", serverID, fileName)
			continue
		}
		content, err := os.ReadFile(phys)
		if err != nil {
			skip++
			log.Printf("[迁移] 跳过(读取失败): %s: %v", phys, err)
			continue
		}
		var pid *int64
		if parentID.Valid {
			v := parentID.Int64
			pid = &v
		}
		rel := resolveRelPath(serverID, pid, fileName)
		if err := pushFileToSiteServerSync(serverID, rel, content); err != nil {
			skip++
			log.Printf("[迁移] 推送失败: server=%d %s: %v", serverID, rel, err)
			continue
		}
		ok++
	}
	log.Printf("[迁移] 完成: 共 %d, 成功 %d, 跳过 %d", total, ok, skip)
}

// 从站点静态服务删除文件（异步；未配置或失败不影响主流程）
func deleteFileFromSiteServer(serverID int64, relPath string) {
	base := siteServerURL()
	token := siteServerToken()
	if base == "" || relPath == "" {
		return
	}
	go func() {
		u := fmt.Sprintf("%s/internal/del?server_id=%d&path=%s", base, serverID, url.QueryEscape(relPath))
		req, err := http.NewRequest(http.MethodDelete, u, nil)
		if err != nil {
			return
		}
		if token != "" {
			req.Header.Set("X-Site-Token", token)
		}
		if resp, err := http.DefaultClient.Do(req); err == nil {
			resp.Body.Close()
		}
	}()
}

// pushServerStateToSiteServer 把某台「服务器」的开关机状态同步给站点静态服务（150）。
//
// 站点静态服务是独立进程、不连业务数据库，不推状态它就永远不知道站点被关机了，
// 表现就是：App 里点了关机，公网站点还一直正常打开。同步推送（几百毫秒）后，
// 关机请求返回时公网已经是关机页，不需要等任何定时任务。
// status=running 表示恢复服务（站点侧清除记录）。
func pushServerStateToSiteServer(serverID int64, status string) error {
	base := strings.TrimRight(siteServerURL(), "/")
	if base == "" {
		return fmt.Errorf("[站点状态] 站点服务地址未配置，状态未同步")
	}
	u := fmt.Sprintf("%s/internal/state?server_id=%d&status=%s", base, serverID, url.QueryEscape(status))
	req, err := http.NewRequest(http.MethodPut, u, nil)
	if err != nil {
		return fmt.Errorf("[站点状态] 构造请求失败: %v", err)
	}
	if token := siteServerToken(); token != "" {
		req.Header.Set("X-Site-Token", token)
	}
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("[站点状态] 连接站点服务失败 %s: %v", u, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("[站点状态] 站点服务返回 %d %s 目标 %s", resp.StatusCode, string(body), u)
	}
	log.Printf("[站点状态] 已同步 server=%d → %s", serverID, status)
	return nil
}

// 获取服务器基础 URL。
// 默认取 siteBaseURL()（兜底 https://o.example.com）；可用环境变量 AURORA_SERVE_BASE_URL 覆盖，
// 用于把「站点/文件的公开访问」单独指向专用子域/机器，让文件流量不占业务服务器带宽。
func getServerBaseURL() string {
	if v := strings.TrimSpace(os.Getenv("AURORA_SERVE_BASE_URL")); v != "" {
		return strings.TrimRight(v, "/")
	}
	return siteBaseURL()
}

// ==================== 站点服务地址（可配置，不硬编码） ====================
//
// 站点静态服务跑在独立的静态文件服务器（150，绑定不带 www 的域名）。
// 这里只存「域名」，不写死 IP：既不暴露服务器地址，也方便换机器/换域名。
//
// 取值优先级：
//  1. 环境变量 AURORA_SERVE_BASE_URL / SITE_SERVER_URL
//  2. 配置文件 <可执行文件目录>/data/site_base_url（一行纯文本，如 https://o.example.com）
//  3. 兜底默认值 https://o.example.com（专用反代子域，走 443，无端口）
var siteBaseCached string

func siteBaseFile() string {
	dir := "."
	if execPath, err := os.Executable(); err == nil {
		dir = filepath.Dir(execPath)
	}
	return filepath.Join(dir, "data", "site_base_url")
}

func siteBaseURL() string {
	if siteBaseCached != "" {
		return siteBaseCached
	}
	v := ""
	if b, err := os.ReadFile(siteBaseFile()); err == nil {
		v = strings.TrimSpace(string(b))
	}
	if v == "" {
		v = "https://o.example.com"
	}
	siteBaseCached = strings.TrimRight(v, "/")
	log.Printf("[配置] 站点服务地址 = %s", siteBaseCached)
	return siteBaseCached
}

// 是否启用「独立子域名」模式：开启后每个站点的地址是 https://s{站点ID}.域名，
// 不再带 /serve/{id} 路径，看起来就是一个独立站点。
// 开关：环境变量 SITE_SUBDOMAIN=1 或 <data>/site_subdomain 文件写入 1（默认关闭，即保持路径模式）。
// 开启前提：域名需泛解析 *.域名 → 静态服务器，且证书覆盖泛域名。
func siteSubdomainFile() string {
	dir := "."
	if execPath, err := os.Executable(); err == nil {
		dir = filepath.Dir(execPath)
	}
	return filepath.Join(dir, "data", "site_subdomain")
}
