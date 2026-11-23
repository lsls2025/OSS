package main

import (
	"database/sql"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// 站点服务器端点有效期：超过该时长没有心跳就视为离线，避免把客户端指到已经下线的机器。
const siteEndpointTTL = 300

// initSiteEndpointsTable 建立「站点域名 -> 服务器端点」绑定表。
// 站点服务启动后会自动心跳上报，控制面把端点写在这里，客户端认证后按卡密域名取用。
func initSiteEndpointsTable() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS site_endpoints (
		domain TEXT PRIMARY KEY,
		host TEXT NOT NULL DEFAULT '',
		scheme TEXT NOT NULL DEFAULT 'http',
		http_port INTEGER NOT NULL DEFAULT 3001,
		ws_port INTEGER NOT NULL DEFAULT 3003,
		token TEXT NOT NULL DEFAULT '',
		manual INTEGER NOT NULL DEFAULT 0,
		updated_at INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  提示: site_endpoints 表准备: %v", err)
	}
	// 兼容旧表：补上"手动绑定"标记。手动绑定后心跳只刷新在线时间，不再覆盖地址。
	_, _ = db.Exec(`ALTER TABLE site_endpoints ADD COLUMN manual INTEGER NOT NULL DEFAULT 0`)
}

// siteClientIP 取站点服务器上报时的来源 IP。若部署在反向代理后面，优先读转发头。
func siteClientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if ip := strings.TrimSpace(strings.Split(xff, ",")[0]); ip != "" {
			return ip
		}
	}
	if ip := strings.TrimSpace(r.Header.Get("X-Real-IP")); ip != "" {
		return ip
	}
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

// isLanHost 判断是否本机/内网地址。是的话说明站点服务在代理或容器里，
// 不能拿它当公网地址，改用上报的站点域名。
func isLanHost(host string) bool {
	ip := net.ParseIP(host)
	if ip == nil {
		return strings.EqualFold(host, "localhost")
	}
	return ip.IsLoopback() || ip.IsPrivate()
}

// POST /api/client/register — 站点服务器心跳上报自己的端点与域名（无需额外启动参数）。
func handleClientRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	var req struct {
		Token    string   `json:"token"`
		Scheme   string   `json:"scheme"`
		HTTPPort int      `json:"http_port"`
		WSPort   int      `json:"ws_port"`
		Domains  []string `json:"domains"`
		TS       int64    `json:"ts"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}

	seen := map[string]bool{}
	var domains []string
	for _, d := range req.Domains {
		d = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(d), "."))
		if d == "" || seen[d] {
			continue
		}
		seen[d] = true
		domains = append(domains, d)
	}
	if len(domains) == 0 {
		writeJSON(w, 400, "未上报站点域名", nil)
		return
	}

	host := siteClientIP(r)
	if isLanHost(host) {
		// 容器 / 反向代理内部地址：用站点域名兜底，保证客户端可达
		host = domains[0]
	}
	scheme := strings.ToLower(strings.TrimSpace(req.Scheme))
	if scheme != "https" {
		scheme = "http"
	}
	httpPort := req.HTTPPort
	if httpPort <= 0 {
		httpPort = 3001
	}
	wsPort := req.WSPort
	if wsPort <= 0 {
		wsPort = 3003
	}
	now := time.Now().Unix()
	token := strings.TrimSpace(req.Token)
	saved := 0

	for _, d := range domains {
		// 域名首次绑定的 token 即为该站点凭据：后续上报必须带同一个 token，
		// 防止别人随便把某个域名指向自己的机器。若旧端点已超时（相当于旧服务器已下线），
		// 允许新机器直接接管，避免换服务器时被历史 token 卡住。
		var existing string
		var existingAt int64
		_ = db.QueryRow(`SELECT token, updated_at FROM site_endpoints WHERE domain = ?`, d).
			Scan(&existing, &existingAt)
		if existing != "" && token != "" && existing != token && now-existingAt <= siteEndpointTTL {
			log.Printf("[site] 拒绝覆盖域名 %s 的端点：上报 token 不匹配且旧端点仍在线", d)
			continue
		}
		_, err := db.Exec(`INSERT INTO site_endpoints (domain, host, scheme, http_port, ws_port, token, manual, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, 0, ?)
			ON CONFLICT(domain) DO UPDATE SET
				host = CASE WHEN site_endpoints.manual = 1 THEN site_endpoints.host ELSE excluded.host END,
				scheme = CASE WHEN site_endpoints.manual = 1 THEN site_endpoints.scheme ELSE excluded.scheme END,
				http_port = CASE WHEN site_endpoints.manual = 1 THEN site_endpoints.http_port ELSE excluded.http_port END,
				ws_port = CASE WHEN site_endpoints.manual = 1 THEN site_endpoints.ws_port ELSE excluded.ws_port END,
				token = CASE WHEN site_endpoints.manual = 1 THEN site_endpoints.token
					WHEN excluded.token <> '' THEN excluded.token ELSE site_endpoints.token END,
				updated_at = excluded.updated_at`,
			d, host, scheme, httpPort, wsPort, token, now)
		if err != nil {
			log.Printf("[site] 端点写入失败 domain=%s: %v", d, err)
			continue
		}
		saved++
	}
	if saved == 0 {
		writeJSON(w, 403, "端点未更新", nil)
		return
	}
	log.Printf("[site] 端点上报成功 host=%s http=%d ws=%d domains=%v", host, httpPort, wsPort, domains)
	writeJSON(w, 200, "上报成功", map[string]interface{}{
		"host":      host,
		"http_port": httpPort,
		"ws_port":   wsPort,
	})
}

// loadSiteCard 校验站点卡密（含首次激活与过期判断），返回卡密绑定的域名等信息。
func loadSiteCard(key, password string) (domain string, perm string, code int, msg string) {
	var id int64
	var durationMinutes int
	var activatedAt, expiresAt int64
	var isActive int
	var category, pw string

	err := db.QueryRow(`SELECT id,
		CASE WHEN COALESCE(duration_minutes, 0) > 0 THEN duration_minutes ELSE duration_hours * 60 END,
		COALESCE(activated_at, 0), COALESCE(expires_at, 0), is_active,
		COALESCE(category, ''), COALESCE(domain, ''), COALESCE(password, ''),
		COALESCE(permission_level, 'B')
		FROM license_keys WHERE key_str = ?`, key).
		Scan(&id, &durationMinutes, &activatedAt, &expiresAt, &isActive, &category, &domain, &pw, &perm)
	if err == sql.ErrNoRows {
		return "", "", 404, "卡密不存在"
	}
	if err != nil {
		log.Printf("[site] 卡密查询失败: %v", err)
		return "", "", 500, "查询失败"
	}
	if isActive == 0 {
		return "", "", 400, "卡密已失效"
	}
	if category != "site" {
		return "", "", 403, "仅站点卡密可用于服务器绑定"
	}
	if password == "" || password != pw {
		return "", "", 403, "卡密密码错误"
	}
	if strings.TrimSpace(domain) == "" {
		return "", "", 400, "该卡密未绑定域名"
	}

	now := time.Now().Unix()
	if activatedAt > 0 {
		if expiresAt > 0 && now > expiresAt {
			return "", "", 400, "卡密已过期"
		}
		return domain, perm, 200, ""
	}
	// 首次认证即激活，和 /api/validate-card-key 的行为保持一致
	expireTime := now + int64(durationMinutes)*60
	if durationMinutes <= 0 {
		expireTime = 0
	}
	if _, err := db.Exec("UPDATE license_keys SET activated_at = ?, expires_at = ? WHERE id = ?", now, expireTime, id); err != nil {
		log.Printf("[site] 卡密激活写入失败: %v", err)
		return "", "", 500, "卡密激活失败"
	}
	log.Printf("[site] 站点卡密首次激活: %s", maskSecret(key))
	return domain, perm, 200, ""
}

// POST /api/client/bootstrap — 客户端输入卡密后调用，返回该卡密对应站点的地址与凭据。
func handleClientBootstrap(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	var req struct {
		Card     string `json:"card"`
		Key      string `json:"key"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	key := strings.TrimSpace(req.Card)
	if key == "" {
		key = strings.TrimSpace(req.Key)
	}
	password := strings.TrimSpace(req.Password)
	if key == "" || password == "" {
		writeJSON(w, 400, "请输入卡密和密码", nil)
		return
	}

	domainList, perm, code, msg := loadSiteCard(key, password)
	if code != 200 {
		writeJSON(w, code, msg, nil)
		return
	}

	now := time.Now().Unix()
	for _, d := range strings.Split(domainList, ",") {
		d = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(d), "."))
		if d == "" {
			continue
		}
		var host, scheme, token string
		var httpPort, wsPort, manual int
		var updatedAt int64
		err := db.QueryRow(`SELECT host, scheme, http_port, ws_port, token, manual, updated_at
			FROM site_endpoints WHERE domain = ?`, d).
			Scan(&host, &scheme, &httpPort, &wsPort, &token, &manual, &updatedAt)
		// 手动绑定的端点不依赖站点心跳：站点服务器上可以完全不跑上报逻辑
		if err != nil || host == "" || (manual == 0 && now-updatedAt > siteEndpointTTL) {
			continue
		}
		baseURL := scheme + "://" + net.JoinHostPort(host, strconv.Itoa(httpPort))
		log.Printf("[site] 卡密 %s 绑定到 %s (%s)", maskSecret(key), baseURL, d)
		writeJSON(w, 200, "绑定成功", map[string]interface{}{
			"domain":           d,
			"base_url":         baseURL,
			"ws_host":          host,
			"ws_port":          wsPort,
			"token":            token,
			"permission_level": perm,
		})
		return
	}
	writeJSON(w, 503, "该站点服务器暂未上线", nil)
}

func normalizeDomain(s string) string {
	return strings.ToLower(strings.TrimSuffix(strings.TrimSpace(s), "."))
}

// GET /api/admin/site-endpoints/get?domain=xxx — 开发者管理：查看某域名的服务器绑定与在线状态
func handleAdminGetSiteEndpoint(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.view") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	domain := normalizeDomain(r.URL.Query().Get("domain"))
	if domain == "" {
		writeJSON(w, 400, "缺少域名", nil)
		return
	}
	var host, scheme, token string
	var httpPort, wsPort, manual int
	var updatedAt int64
	err := db.QueryRow(`SELECT host, scheme, http_port, ws_port, token, manual, updated_at
		FROM site_endpoints WHERE domain = ?`, domain).
		Scan(&host, &scheme, &httpPort, &wsPort, &token, &manual, &updatedAt)
	if err == sql.ErrNoRows {
		writeJSON(w, 200, "该域名尚未上报服务器", map[string]interface{}{
			"domain": domain, "bound": false,
		})
		return
	}
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	now := time.Now().Unix()
	writeJSON(w, 200, "成功", map[string]interface{}{
		"domain":     domain,
		"bound":      true,
		"host":       host,
		"scheme":     scheme,
		"http_port":  httpPort,
		"ws_port":    wsPort,
		"token":      token,
		"manual":     manual == 1,
		"updated_at": updatedAt,
		// 手动绑定的端点无需心跳，直接视为可用
		"online": host != "" && (manual == 1 || now-updatedAt <= siteEndpointTTL),
	})
}

// POST /api/admin/site-endpoints/save — 开发者管理：手动指定域名的服务器端点（优先级高于自动上报）
func handleAdminSaveSiteEndpoint(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.generate") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		Domain   string `json:"domain"`
		Host     string `json:"host"`
		Scheme   string `json:"scheme"`
		HTTPPort int    `json:"http_port"`
		WSPort   int    `json:"ws_port"`
		Token    string `json:"token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	domain := normalizeDomain(req.Domain)
	host := strings.TrimSpace(req.Host)
	if domain == "" || host == "" {
		writeJSON(w, 400, "请填写域名和服务器地址", nil)
		return
	}
	scheme := strings.ToLower(strings.TrimSpace(req.Scheme))
	if scheme != "https" {
		scheme = "http"
	}
	httpPort := req.HTTPPort
	if httpPort <= 0 {
		httpPort = 3001
	}
	wsPort := req.WSPort
	if wsPort <= 0 {
		wsPort = 3003
	}
	token := strings.TrimSpace(req.Token)
	now := time.Now().Unix()
	_, err := db.Exec(`INSERT INTO site_endpoints (domain, host, scheme, http_port, ws_port, token, manual, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, 1, ?)
		ON CONFLICT(domain) DO UPDATE SET
			host = excluded.host,
			scheme = excluded.scheme,
			http_port = excluded.http_port,
			ws_port = excluded.ws_port,
			token = CASE WHEN excluded.token <> '' THEN excluded.token ELSE site_endpoints.token END,
			manual = 1,
			updated_at = excluded.updated_at`,
		domain, host, scheme, httpPort, wsPort, token, now)
	if err != nil {
		log.Printf("[site] 手动绑定写入失败 domain=%s: %v", domain, err)
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	log.Printf("[site] 手动绑定 domain=%s -> %s://%s:%d", domain, scheme, host, httpPort)
	writeJSON(w, 200, "已绑定", map[string]interface{}{
		"domain": domain, "host": host, "scheme": scheme,
		"http_port": httpPort, "ws_port": wsPort,
	})
}

// POST /api/admin/site-endpoints/reset — 开发者管理：取消手动绑定，恢复站点自动上报
func handleAdminResetSiteEndpoint(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevOrPerm(r, "card_keys.generate") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	var req struct {
		Domain string `json:"domain"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	domain := normalizeDomain(req.Domain)
	if domain == "" {
		writeJSON(w, 400, "缺少域名", nil)
		return
	}
	if _, err := db.Exec(`UPDATE site_endpoints SET manual = 0, host = '', updated_at = 0 WHERE domain = ?`, domain); err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	writeJSON(w, 200, "已恢复自动上报", nil)
}
