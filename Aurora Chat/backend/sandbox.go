package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// 沙盒默认示例页面（自动写入项目目录，方便用户开箱即用）
const sandboxIndexHTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>我的网站</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,"Microsoft YaHei",sans-serif;background:#f0f2f5;display:flex;justify-content:center;align-items:center;min-height:100vh}
.card{background:#fff;padding:40px;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,0.1);width:420px;max-width:90%}
h1{font-size:22px;margin-bottom:8px;color:#1a1a2e}
.sub{color:#666;font-size:14px;margin-bottom:24px}
input{width:100%;padding:12px 16px;border:1px solid #d9d9d9;border-radius:8px;font-size:15px;outline:none;transition:.2s;margin-bottom:12px}
input:focus{border-color:#4a6cf7;box-shadow:0 0 0 3px rgba(74,108,247,0.15)}
button{margin-top:4px;width:100%;padding:12px;border:none;border-radius:8px;font-size:16px;font-weight:600;cursor:pointer;transition:.2s}
.btn-blue{background:#4a6cf7;color:#fff}
.btn-blue:hover{background:#3b5de7}
.btn-green{background:#059669;color:#fff;margin-top:12px}
.btn-green:hover{background:#047857}
#msg{margin-top:16px;padding:12px;border-radius:8px;font-size:14px;display:none}
#msg.success{display:block;background:#e6f7ed;color:#1a7d3c}
#msg.error{display:block;background:#feebeb;color:#c0392b}
#msg.info{display:block;background:#e0f2fe;color:#0369a1}
.footer{margin-top:24px;text-align:center;color:#aaa;font-size:12px}
.api-info{font-size:12px;color:#888;margin-top:8px;word-break:break-all}
</style>
</head>
<body>
<div class="card">
<h1>我的网站</h1>
<p class="sub">前端 → 纯静态服务</p>
<input id="key" placeholder="键名 (例如: ceshi)">
<input id="value" placeholder="值 (例如: shujuku ceshi)">
<button class="btn-blue" onclick="writeDB()">💾 写入数据库</button>
<button class="btn-green" onclick="readDB()">📖 读取数据库</button>
<div id="msg"></div>
<p class="api-info" id="apiInfo"></p>
<p class="footer">通过 App 文件管理上传 · 纯静态托管</p>
</div>
<script>
const API_BASE = 'http://' + location.hostname + ':5000';
const DB_ID = new URLSearchParams(location.search).get('db') || '';
const apiInfo = document.getElementById('apiInfo');
if(apiInfo) apiInfo.textContent = 'API: ' + API_BASE + '/api/sandbox/store?db=' + (DB_ID || '【请在URL传入db参数】');
function showMsg(text, type) {
  const el = document.getElementById('msg');
  el.textContent = text; el.className = type;
}
async function writeDB() {
  const key = document.getElementById('key').value.trim();
  const value = document.getElementById('value').value.trim();
  if(!key){ showMsg('键名不能为空', 'error'); return; }
  if(!DB_ID){ showMsg('缺少 db 参数，请在 URL 后添加 ?db=你的数据库ID', 'error'); return; }
  showMsg('写入中...', 'info');
  try {
    const res = await fetch(API_BASE + '/api/sandbox/store?db=' + encodeURIComponent(DB_ID), {
      method: 'POST', headers: {'Content-Type':'application/json'},
      body: JSON.stringify({key, value})
    });
    const data = await res.json();
    if(data.code === 200){ showMsg('✅ ' + (data.message || '写入成功'), 'success'); }
    else { showMsg('❌ ' + (data.message || '写入失败'), 'error'); }
  } catch(e) { showMsg('❌ 请求失败: ' + e.message, 'error'); }
}
async function readDB() {
  const key = document.getElementById('key').value.trim();
  if(!key){ showMsg('键名不能为空', 'error'); return; }
  if(!DB_ID){ showMsg('缺少 db 参数，请在 URL 后添加 ?db=你的数据库ID', 'error'); return; }
  showMsg('读取中...', 'info');
  try {
    const res = await fetch(API_BASE + '/api/sandbox/store?db=' + encodeURIComponent(DB_ID) + '&key=' + encodeURIComponent(key));
    const data = await res.json();
    if(data.code === 200 && data.data){ showMsg('✅ 读取成功: ' + data.data.value, 'success'); document.getElementById('value').value = data.data.value; }
    else { showMsg('❌ ' + (data.message || '读取失败'), 'error'); }
  } catch(e) { showMsg('❌ 请求失败: ' + e.message, 'error'); }
}
</script>
</body>
</html>`

// 端口分配（避免冲突）
var portOffset = 10000

// ========== 数据库 ==========

func initSandboxTables() {
	db.Exec(`CREATE TABLE IF NOT EXISTS sandbox_projects (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		name TEXT NOT NULL,
		project_type TEXT NOT NULL,
		port INTEGER NOT NULL,
		container_id TEXT,
		status TEXT NOT NULL DEFAULT 'stopped',
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL
	)`)
	db.Exec(`CREATE INDEX IF NOT EXISTS idx_sandbox_user ON sandbox_projects(user_id)`)
	log.Println("[沙盒] 数据库表初始化完成")
}

// 普通用户最多可创建的项目数
const MaxSandboxProjectsPerUser = 5

type SandboxProject struct {
	ID          int64  `json:"id"`
	UserID      int64  `json:"user_id"`
	Name        string `json:"name"`
	ProjectType string `json:"project_type"` // node/python/go/nginx
	Port        int    `json:"port"`
	ContainerID string `json:"container_id"`
	Status      string `json:"status"`
	CreatedAt   int64  `json:"created_at"`
}

func createSandboxProject(userID int64, name, projectType string) (*SandboxProject, error) {
	// 找一个可用端口
	port := findAvailablePort()
	now := time.Now().Unix()
	result, err := db.Exec("INSERT INTO sandbox_projects (user_id, name, project_type, port, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'stopped', ?, ?)",
		userID, name, projectType, port, now, now)
	if err != nil {
		return nil, err
	}
	id, _ := result.LastInsertId()
	return &SandboxProject{ID: id, UserID: userID, Name: name, ProjectType: projectType, Port: port, Status: "stopped"}, nil
}

func getUserSandboxProjects(userID int64) ([]*SandboxProject, error) {
	rows, err := db.Query("SELECT id, user_id, name, project_type, port, container_id, status, created_at FROM sandbox_projects WHERE user_id = ? ORDER BY created_at DESC", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list = make([]*SandboxProject, 0)
	for rows.Next() {
		var p SandboxProject
		var cid sql.NullString
		rows.Scan(&p.ID, &p.UserID, &p.Name, &p.ProjectType, &p.Port, &cid, &p.Status, &p.CreatedAt)
		if cid.Valid {
			p.ContainerID = cid.String
		}
		list = append(list, &p)
	}
	return list, nil
}

// 统计用户项目数
func countUserSandboxProjects(userID int64) (int, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM sandbox_projects WHERE user_id = ?", userID).Scan(&count)
	return count, err
}

func getSandboxProjectByID(projectID, userID int64) (*SandboxProject, error) {
	var p SandboxProject
	var cid sql.NullString
	err := db.QueryRow("SELECT id, user_id, name, project_type, port, container_id, status, created_at FROM sandbox_projects WHERE id = ? AND user_id = ?", projectID, userID).
		Scan(&p.ID, &p.UserID, &p.Name, &p.ProjectType, &p.Port, &cid, &p.Status, &p.CreatedAt)
	if err != nil {
		return nil, err
	}
	if cid.Valid {
		p.ContainerID = cid.String
	}
	return &p, nil
}

func updateSandboxProjectStatus(projectID int64, status, containerID string) error {
	_, err := db.Exec("UPDATE sandbox_projects SET status = ?, container_id = ?, updated_at = ? WHERE id = ?",
		status, containerID, time.Now().Unix(), projectID)
	return err
}

func deleteSandboxProject(projectID int64) error {
	_, err := db.Exec("DELETE FROM sandbox_projects WHERE id = ?", projectID)
	return err
}

func findAvailablePort() int {
	for {
		port := portOffset + rand.Intn(50000)
		var exists int
		err := db.QueryRow("SELECT 1 FROM sandbox_projects WHERE port = ?", port).Scan(&exists)
		if err == sql.ErrNoRows {
			return port
		}
	}
}

// ========== 子域名路由（项目ID.域名 → 沙盒容器） ==========

// handleSubdomainSandbox 检查 Host 头是否为 项目ID.域名 格式
// 如果是，直接反向代理到对应的沙盒容器，返回 true
// 否则返回 false，继续走正常路由
func handleSubdomainSandbox(w http.ResponseWriter, r *http.Request) bool {
	host := r.Host
	// 去掉端口部分
	if idx := strings.LastIndex(host, ":"); idx >= 0 {
		host = host[:idx]
	}
	// 拆分子域名：项目ID.域名
	parts := strings.SplitN(host, ".", 2)
	if len(parts) < 2 || parts[0] == "" || parts[0] == "www" || parts[0] == "localhost" {
		return false
	}
	subdomain := parts[0]
	projectID, err := strconv.ParseInt(subdomain, 10, 64)
	if err != nil || projectID <= 0 {
		return false
	}

	// 查询项目（不限制 userID，公开访问）
	var p *SandboxProject
	rows, err := db.Query("SELECT id, user_id, name, project_type, port, container_id, status, created_at FROM sandbox_projects WHERE id = ?", projectID)
	if err != nil {
		return false
	}
	if rows.Next() {
		p = &SandboxProject{}
		var cid sql.NullString
		rows.Scan(&p.ID, &p.UserID, &p.Name, &p.ProjectType, &p.Port, &cid, &p.Status, &p.CreatedAt)
		if cid.Valid {
			p.ContainerID = cid.String
		}
	}
	rows.Close()

	if p == nil || p.Status != "running" {
		return false
	}

	// 纯静态服务：仅从文件系统提供静态文件（无论存储的类型，统一静态托管）
	projectDir := getStaticProjectDir(p.ID)
	filePath := strings.TrimPrefix(r.URL.Path, "/")
	if filePath == "" {
		filePath = "index.html"
	}
	fullPath, err := sanitizeSandboxPath(projectDir, filePath)
	if err != nil {
		return false
	}
	http.ServeFile(w, r, fullPath)
	return true
}

// ========== 沙盒反向代理（统一端口访问沙盒项目） ==========

// handleSandboxProjectProxy 通过主后端统一端口代理沙盒项目页面
// 访问路径: /sandbox/p/{projectID}/...  → 代理到 127.0.0.1:沙盒端口/...
// 用户不需要知道沙盒独立端口，统一通过主后端访问
func handleSandboxProjectProxy(w http.ResponseWriter, r *http.Request) {
	// 从路径中提取项目ID: /sandbox/p/{projectID}/文件路径
	trimmed := strings.TrimPrefix(r.URL.Path, "/sandbox/p/")
	parts := strings.SplitN(trimmed, "/", 2)
	if len(parts) == 0 || parts[0] == "" {
		w.WriteHeader(400)
		w.Write([]byte("缺少项目ID，格式: /sandbox/p/{项目ID}/"))
		return
	}
	projectID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		w.WriteHeader(400)
		w.Write([]byte("项目ID格式错误"))
		return
	}

	// 查找项目
	var p *SandboxProject
	rows, err := db.Query("SELECT id, user_id, name, project_type, port, container_id, status, created_at FROM sandbox_projects WHERE id = ?", projectID)
	if err != nil {
		http.Error(w, "查询失败", 500)
		return
	}
	if rows.Next() {
		p = &SandboxProject{}
		var cid sql.NullString
		rows.Scan(&p.ID, &p.UserID, &p.Name, &p.ProjectType, &p.Port, &cid, &p.Status, &p.CreatedAt)
		if cid.Valid {
			p.ContainerID = cid.String
		}
	}
	rows.Close()

	if p == nil {
		http.Error(w, "项目不存在", 404)
		return
	}
	if p.Status != "running" {
		w.WriteHeader(503)
		w.Write([]byte("项目未运行"))
		return
	}

	// ── 纯静态服务：仅从文件系统提供静态文件（无论存储类型，统一静态托管） ──
	projectDir := getStaticProjectDir(p.ID)
	// 确定文件路径：/sandbox/p/{projectID}/文件路径
	filePath := ""
	if len(parts) > 1 && parts[1] != "" {
		filePath = parts[1]
	} else {
		filePath = "index.html"
	}
	// 安全校验，防止路径穿越
	fullPath, err := sanitizeSandboxPath(projectDir, filePath)
	if err != nil {
		http.Error(w, "无效的路径", 400)
		return
	}
	http.ServeFile(w, r, fullPath)
}

// ========== 沙盒测试页面（主后端同端口提供，无跨域问题） ==========

// handleSandboxStoreDemo 在主后端端口提供沙盒测试页面，前端与 API 同端口，无需跨域
func handleSandboxStoreDemo(w http.ResponseWriter, r *http.Request) {
	dbID := r.URL.Query().Get("db")
	if dbID == "" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(400)
		w.Write([]byte(`<!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>沙盒测试</title><style>*{margin:0;padding:0;box-sizing:border-box}body{font-family:-apple-system,"Microsoft YaHei",sans-serif;background:#f0f2f5;display:flex;justify-content:center;align-items:center;min-height:100vh}.card{background:#fff;padding:40px;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,0.1);width:420px;max-width:90%}h1{font-size:22px;margin-bottom:8px;color:#1a1a2e}.sub{color:#666;font-size:14px;margin-bottom:24px}input{width:100%;padding:12px 16px;border:1px solid #d9d9d9;border-radius:8px;font-size:15px;outline:none;transition:.2s;margin-bottom:12px}input:focus{border-color:#4a6cf7;box-shadow:0 0 0 3px rgba(74,108,247,0.15)}button{margin-top:4px;width:100%;padding:12px;border:none;border-radius:8px;font-size:16px;font-weight:600;cursor:pointer;transition:.2s}.btn-blue{background:#4a6cf7;color:#fff}.btn-green{background:#059669;color:#fff;margin-top:12px}.btn-green:hover{background:#047857}.result{margin-top:12px;padding:14px;border-radius:10px;font-size:13px;line-height:1.6;white-space:pre-wrap;word-break:break-all;display:none}.ok{display:block;background:#ecfdf5;color:#065f46;border:1px solid #a7f3d0}.err{display:block;background:#fef2f2;color:#991b1b;border:1px solid #fecaca}</style></head><body><div class="card"><h1>沙盒测试页面</h1><p class="sub">请在 URL 后添加 ?db=你的数据库ID</p><div class="result ok">例如：?db=159ab8a1189f272e</div></div></body></html>`))
		return
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")

	// API 使用相对路径，因为页面和 API 在同端口
	html := fmt.Sprintf(`<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>我的网站</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,"Microsoft YaHei",sans-serif;background:#f0f2f5;display:flex;justify-content:center;align-items:center;min-height:100vh}
.card{background:#fff;padding:40px;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,0.1);width:420px;max-width:90%%}
h1{font-size:22px;margin-bottom:8px;color:#1a1a2e}
.sub{color:#666;font-size:14px;margin-bottom:24px}
input{width:100%%;padding:12px 16px;border:1px solid #d9d9d9;border-radius:8px;font-size:15px;outline:none;transition:.2s;margin-bottom:12px}
input:focus{border-color:#4a6cf7;box-shadow:0 0 0 3px rgba(74,108,247,0.15)}
button{margin-top:4px;width:100%%;padding:12px;border:none;border-radius:8px;font-size:16px;font-weight:600;cursor:pointer;transition:.2s}
.btn-blue{background:#4a6cf7;color:#fff}
.btn-blue:hover{background:#3b5de7}
.btn-green{background:#059669;color:#fff;margin-top:12px}
.btn-green:hover{background:#047857}
.result{margin-top:12px;padding:14px;border-radius:10px;font-size:13px;line-height:1.6;white-space:pre-wrap;word-break:break-all;display:none}
.ok{display:block;background:#ecfdf5;color:#065f46;border:1px solid #a7f3d0}
.err{display:block;background:#fef2f2;color:#991b1b;border:1px solid #fecaca}
.footer{margin-top:20px;padding-top:16px;border-top:1px solid #e5e7eb;font-size:12px;color:#9ca3af;text-align:center}
</style>
</head>
<body>
<div class="card">
<h1>我的网站</h1>
<p class="sub">前端 → 沙盒后端 → 数据库</p>
<input id="nameInput" placeholder="键名 (例如: ceshi)">
<input id="msgInput" placeholder="值 (例如: shujuku ceshi)">
<button class="btn btn-blue" onclick="save()">💾 写入数据库</button>
<button class="btn btn-green" onclick="load()">📖 读取数据库</button>
<div id="result" class="result"></div>
<p class="footer">API: /api/sandbox/store?db=%s</p>
</div>
<script>
const DB = "%s";
const API_BASE = "";

async function save() {
  const name = document.getElementById('nameInput').value.trim();
  const msg = document.getElementById('msgInput').value.trim();
  document.getElementById('result').className = 'result';
  try {
    const r = await fetch(API_BASE + '/api/sandbox/store?db=' + DB, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: name || 'my_site', value: msg })
    });
    const d = await r.json();
    show(d.code === 200 ? '✅ 写入成功' : '❌ ' + d.message, d.code === 200);
  } catch(e) { show('❌ ' + e.message, false); }
}
async function load() {
  const name = document.getElementById('nameInput').value.trim();
  document.getElementById('result').className = 'result';
  try {
    const r = await fetch(API_BASE + '/api/sandbox/store?db=' + DB + '&key=' + encodeURIComponent(name || 'my_site'));
    const d = await r.json();
    if (d.code === 200 && d.data) {
      show('✅ 读取成功\n值: ' + d.data.value, true);
    } else show('⚠️ ' + (d.message || '无数据'), false);
  } catch(e) { show('❌ ' + e.message, false); }
}
function show(msg, ok) {
  document.getElementById('result').className = 'result ' + (ok ? 'ok' : 'err');
  document.getElementById('result').textContent = msg;
}
</script>
</body>
</html>`, dbID, dbID)

	w.Write([]byte(html))
}

// ========== Docker 管理 ==========

// ========== HTTP Handlers ==========

// 部署沙盒项目
func handleDeploySandbox(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "only POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}

	name := r.FormValue("name")
	projectType := r.FormValue("type")
	if name == "" || projectType == "" {
		writeJSON(w, 400, "name and type required", nil)
		return
	}

	// 检查项目目录
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	sandboxDir := filepath.Join(baseDir, "data", "sandbox", fmt.Sprintf("user_%d", userID))
	projectDir := filepath.Join(sandboxDir, fmt.Sprintf("project_%s", name))
	os.MkdirAll(projectDir, 0755)

	// 处理上传文件
	file, header, err := r.FormFile("file")
	if err == nil && header != nil {
		defer file.Close()
		// 保存 ZIP
		zipPath := filepath.Join(sandboxDir, header.Filename)
		f, _ := os.Create(zipPath)
		if f != nil {
			buf := make([]byte, 1024*1024)
			for {
				n, err := file.Read(buf)
				if n > 0 {
					f.Write(buf[:n])
				}
				if err != nil {
					break
				}
			}
			f.Close()
			// 解压
			exec.Command("unzip", "-o", zipPath, "-d", projectDir).Run()
			os.Remove(zipPath)
		}
	}

	// 检查项目数量限制
	count, err := countUserSandboxProjects(userID)
	if err != nil {
		writeJSON(w, 500, "query failed: "+err.Error(), nil)
		return
	}
	if count >= MaxSandboxProjectsPerUser {
		writeJSON(w, 400, fmt.Sprintf("项目数量已达上限(%d个)，无法创建更多", MaxSandboxProjectsPerUser), nil)
		return
	}

	// 创建项目记录
	p, err := createSandboxProject(userID, name, projectType)
	if err != nil {
		writeJSON(w, 500, "create project failed: "+err.Error(), nil)
		return
	}

	// 纯静态服务：文件存统一目录，直接标记 running，不再使用 Docker 部署
	staticDir := getStaticProjectDir(p.ID)
	os.MkdirAll(staticDir, 0755)
	// 移动已解压的文件到统一目录
	files, _ := os.ReadDir(projectDir)
	for _, f := range files {
		os.Rename(filepath.Join(projectDir, f.Name()), filepath.Join(staticDir, f.Name()))
	}
	os.RemoveAll(projectDir)
	updateSandboxProjectStatus(p.ID, "running", "")
	writeJSON(w, 200, "ok", map[string]interface{}{
		"id":  p.ID,
		"url": fmt.Sprintf("/sandbox/p/%d/", p.ID),
	})
}

// 获取用户沙盒项目列表
func handleListSandbox(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "only GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	list, err := getUserSandboxProjects(userID)
	if err != nil {
		writeJSON(w, 500, "query failed", nil)
		return
	}
	if list == nil {
		list = []*SandboxProject{}
	}
	writeJSON(w, 200, "ok", list)
}

// 获取或自动创建用户的单项目（简化方案）
func handleGetOrCreateSandboxProject(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "only GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	list, err := getUserSandboxProjects(userID)
	if err != nil {
		writeJSON(w, 500, "query failed", nil)
		return
	}
	if len(list) > 0 {
		writeJSON(w, 200, "ok", list[0])
		return
	}
	// 没有项目，自动创建一个（static 类型：文件直接存服务器，无需Docker、无需独立端口）
	name := fmt.Sprintf("我的项目_%d", userID)
	projectType := "static"

	// 先创建项目记录（需要 projectID 来确定文件目录）
	p, err := createSandboxProject(userID, name, projectType)
	if err != nil {
		writeJSON(w, 500, "create project failed: "+err.Error(), nil)
		return
	}

	// 用项目ID创建统一文件目录
	projectDir := getStaticProjectDir(p.ID)
	os.MkdirAll(projectDir, 0755)

	// 状态直接设为 running（不需要启动容器）
	updateSandboxProjectStatus(p.ID, "running", "")

	// 生成默认示例页面，让用户开箱即用
	indexPath := filepath.Join(projectDir, "index.html")
	if _, err := os.Stat(indexPath); os.IsNotExist(err) {
		os.WriteFile(indexPath, []byte(sandboxIndexHTML), 0644)
	}

	log.Printf("[沙盒] 自动创建静态项目: id=%d, name=%s", p.ID, name)
	writeJSON(w, 200, "ok", p)
}

// 停止项目
func handleStopSandbox(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "only POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pid, _ := strconv.ParseInt(r.FormValue("project_id"), 10, 64)
	p, err := getSandboxProjectByID(pid, userID)
	if err != nil {
		writeJSON(w, 404, "project not found", nil)
		return
	}
	updateSandboxProjectStatus(p.ID, "stopped", "")
	writeJSON(w, 200, "ok", nil)
}

// 获取项目目录（纯静态服务：统一使用静态项目目录，不再区分用户子目录）
func getSandboxProjectDir(userID int64, projectID int64) string {
	return getStaticProjectDir(projectID)
}

// getStaticProjectDir 返回静态项目的文件目录（统一存储，无需Docker）
func getStaticProjectDir(projectID int64) string {
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	return filepath.Join(baseDir, "data", "sandbox_projects", fmt.Sprintf("project_%d", projectID))
}

// 限制文件路径不超出项目目录
func sanitizeSandboxPath(projectDir, reqPath string) (string, error) {
	cleanPath := filepath.Clean(reqPath)
	if strings.Contains(cleanPath, "..") {
		return "", fmt.Errorf("invalid path")
	}
	fullPath := filepath.Join(projectDir, cleanPath)
	if !strings.HasPrefix(filepath.Clean(fullPath), filepath.Clean(projectDir)) {
		return "", fmt.Errorf("path out of bounds")
	}
	return fullPath, nil
}

// 启动已停止的项目
func handleStartSandbox(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "only POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pidStr := r.URL.Query().Get("project_id")
	log.Printf("[沙盒] 启动请求: user=%d, project_id_str=%s", userID, pidStr)
	pid, _ := strconv.ParseInt(pidStr, 10, 64)
	if pid <= 0 {
		writeJSON(w, 400, "invalid project_id", nil)
		return
	}
	p, err := getSandboxProjectByID(pid, userID)
	if err != nil {
		log.Printf("[沙盒] 查询项目失败: %v", err)
		writeJSON(w, 404, "project not found", nil)
		return
	}

	// 纯静态服务：直接标记为运行中（文件已通过文件系统提供，无需启动容器）
	if p.Status == "running" {
		writeJSON(w, 200, "already running", map[string]interface{}{"id": p.ID})
		return
	}
	updateSandboxProjectStatus(p.ID, "running", "")
	writeJSON(w, 200, "running", map[string]interface{}{"id": p.ID})
}

// 删除项目
func handleDeleteSandbox(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeJSON(w, 405, "only DELETE", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pidStr := r.URL.Query().Get("project_id")
	log.Printf("[沙盒] 删除请求: user=%d, project_id_str=%s", userID, pidStr)
	pid, _ := strconv.ParseInt(pidStr, 10, 64)
	if pid <= 0 {
		writeJSON(w, 400, "invalid project_id", nil)
		return
	}
	p, err := getSandboxProjectByID(pid, userID)
	if err != nil {
		log.Printf("[沙盒] 查询项目失败: %v", err)
		writeJSON(w, 404, "project not found", nil)
		return
	}
	if err := deleteSandboxProject(p.ID); err != nil {
		log.Printf("[沙盒] 删除项目失败: %v", err)
		writeJSON(w, 500, "delete failed: "+err.Error(), nil)
		return
	}
	log.Printf("[沙盒] 项目删除成功: id=%d", p.ID)
	writeJSON(w, 200, "ok", nil)
}

// 获取项目日志
func handleSandboxLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "only GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pid, _ := strconv.ParseInt(r.URL.Query().Get("project_id"), 10, 64)
	if _, err := getSandboxProjectByID(pid, userID); err != nil {
		writeJSON(w, 404, "project not found", nil)
		return
	}
	// 纯静态服务：无容器日志
	writeJSON(w, 200, "ok", map[string]interface{}{"logs": ""})
}

// ========== 文件管理 ==========

func handleSandboxFilesList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "only GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pid, _ := strconv.ParseInt(r.URL.Query().Get("project_id"), 10, 64)
	p, err := getSandboxProjectByID(pid, userID)
	if err != nil {
		writeJSON(w, 404, "project not found", nil)
		return
	}
	reqPath := r.URL.Query().Get("path")
	if reqPath == "" {
		reqPath = "."
	}
	projectDir := getSandboxProjectDir(userID, p.ID)
	fullPath, err := sanitizeSandboxPath(projectDir, reqPath)
	if err != nil {
		writeJSON(w, 400, err.Error(), nil)
		return
	}
	info, err := os.Stat(fullPath)
	if err != nil {
		writeJSON(w, 404, "not found", nil)
		return
	}
	if !info.IsDir() {
		writeJSON(w, 400, "not a directory", nil)
		return
	}
	entries, err := os.ReadDir(fullPath)
	if err != nil {
		writeJSON(w, 500, err.Error(), nil)
		return
	}
	var files = make([]map[string]interface{}, 0, len(entries))
	for _, entry := range entries {
		info, _ := entry.Info()
		size := int64(0)
		if info != nil {
			size = info.Size()
		}
		files = append(files, map[string]interface{}{
			"name":   entry.Name(),
			"is_dir": entry.IsDir(),
			"size":   size,
		})
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"path":  reqPath,
		"files": files,
	})
}

func handleSandboxFilesRead(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "only GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pid, _ := strconv.ParseInt(r.URL.Query().Get("project_id"), 10, 64)
	p, err := getSandboxProjectByID(pid, userID)
	if err != nil {
		writeJSON(w, 404, "project not found", nil)
		return
	}
	reqPath := r.URL.Query().Get("path")
	if reqPath == "" {
		writeJSON(w, 400, "path required", nil)
		return
	}
	projectDir := getSandboxProjectDir(userID, p.ID)
	fullPath, err := sanitizeSandboxPath(projectDir, reqPath)
	if err != nil {
		writeJSON(w, 400, err.Error(), nil)
		return
	}
	data, err := os.ReadFile(fullPath)
	if err != nil {
		writeJSON(w, 500, err.Error(), nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"path":    reqPath,
		"content": string(data),
	})
}

func handleSandboxFilesWrite(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "only POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pid, _ := strconv.ParseInt(r.URL.Query().Get("project_id"), 10, 64)
	p, err := getSandboxProjectByID(pid, userID)
	if err != nil {
		writeJSON(w, 404, "project not found", nil)
		return
	}
	reqPath := r.URL.Query().Get("path")
	if reqPath == "" {
		writeJSON(w, 400, "path required", nil)
		return
	}
	var body struct {
		Content string `json:"content"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeJSON(w, 400, "invalid body", nil)
		return
	}
	projectDir := getSandboxProjectDir(userID, p.ID)
	fullPath, err := sanitizeSandboxPath(projectDir, reqPath)
	if err != nil {
		writeJSON(w, 400, err.Error(), nil)
		return
	}
	os.MkdirAll(filepath.Dir(fullPath), 0755)
	if err := os.WriteFile(fullPath, []byte(body.Content), 0644); err != nil {
		writeJSON(w, 500, err.Error(), nil)
		return
	}
	writeJSON(w, 200, "ok", nil)
}

func handleSandboxFilesDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeJSON(w, 405, "only DELETE", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pid, _ := strconv.ParseInt(r.URL.Query().Get("project_id"), 10, 64)
	p, err := getSandboxProjectByID(pid, userID)
	if err != nil {
		writeJSON(w, 404, "project not found", nil)
		return
	}
	reqPath := r.URL.Query().Get("path")
	if reqPath == "" {
		writeJSON(w, 400, "path required", nil)
		return
	}
	projectDir := getSandboxProjectDir(userID, p.ID)
	fullPath, err := sanitizeSandboxPath(projectDir, reqPath)
	if err != nil {
		writeJSON(w, 400, err.Error(), nil)
		return
	}
	if filepath.Clean(fullPath) == filepath.Clean(projectDir) {
		writeJSON(w, 400, "cannot delete project root", nil)
		return
	}
	if err := os.RemoveAll(fullPath); err != nil {
		writeJSON(w, 500, err.Error(), nil)
		return
	}
	writeJSON(w, 200, "ok", nil)
}

func handleSandboxFilesMkdir(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "only POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "unauthorized", nil)
		return
	}
	pid, _ := strconv.ParseInt(r.URL.Query().Get("project_id"), 10, 64)
	p, err := getSandboxProjectByID(pid, userID)
	if err != nil {
		writeJSON(w, 404, "project not found", nil)
		return
	}
	reqPath := r.URL.Query().Get("path")
	if reqPath == "" {
		writeJSON(w, 400, "path required", nil)
		return
	}
	projectDir := getSandboxProjectDir(userID, p.ID)
	fullPath, err := sanitizeSandboxPath(projectDir, reqPath)
	if err != nil {
		writeJSON(w, 400, err.Error(), nil)
		return
	}
	if err := os.MkdirAll(fullPath, 0755); err != nil {
		writeJSON(w, 500, err.Error(), nil)
		return
	}
	writeJSON(w, 200, "ok", nil)
}


