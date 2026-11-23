package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ============================================================
// 固定链接（alias）
// 公开链接原本是 /fd/<token>，token 每次上传都重新生成，删掉重传链接就会变。
// 现在可给文件绑定一个固定别名：/fd/<alias>，之后无论是原地替换内容、
// 还是删除后用同名 alias 重新上传，对外链接都不变——适合当图床/存储长期引用。
// 别名统一转小写并全局唯一（URL 路径大小写敏感，统一后可避免同一个名字出现两条不同链接）。
// ============================================================

// fdNormalizeAlias 规范化并校验固定链接别名。
// 返回空串表示「不设置/取消固定链接」；仅允许 ASCII 字母、数字与 . - _，2~64 字符。
func fdNormalizeAlias(raw string) (string, error) {
	a := strings.ToLower(strings.TrimSpace(raw))
	if a == "" {
		return "", nil
	}
	if len(a) < 2 || len(a) > 64 {
		return "", fmt.Errorf("固定链接需 2~64 个字符")
	}
	if a == "." || a == ".." || strings.Contains(a, "..") {
		return "", fmt.Errorf("固定链接格式不正确")
	}
	for _, c := range a {
		ok := (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_'
		if !ok {
			return "", fmt.Errorf("固定链接仅支持字母、数字和 . - _")
		}
	}
	return a, nil
}

// fdFixedURL 固定链接的公开路径（未设置别名时返回空串）
func fdFixedURL(alias string) string {
	if alias == "" {
		return ""
	}
	return "/fd/" + alias
}

// fdTokenPrefix 取 token 前 8 位（物理文件名用它做标识）；token 异常时返回空串
func fdTokenPrefix(token string) string {
	if len(token) < 8 {
		return ""
	}
	return token[:8]
}

// fdRemovePhysicalFiles 删除某个文件记录对应的物理文件（按 <ts>_<uid>_<token前8><ext> 匹配）；
// except 非空时跳过该路径（原地替换新写入的文件不能被误删）
func fdRemovePhysicalFiles(ownerID int64, token, except string) {
	prefix := fdTokenPrefix(token)
	if prefix == "" {
		return
	}
	matches, _ := filepath.Glob(filepath.Join(fdStorageDir, "*_"+strconv.FormatInt(ownerID, 10)+"_"+prefix+".*"))
	for _, m := range matches {
		if except != "" && m == except {
			continue
		}
		os.Remove(m)
	}
}

// handleFdUpload POST /api/fd/upload — multipart 上传，字段名 file
func handleFdUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	cfg := getFdConfig(userID)
	capBytes := cfg.capacityBytes()
	used := fdUsedBytes(userID)

	// 上限 2GB（单文件）
	if r.ContentLength > 2*1024*1024*1024 {
		writeJSON(w, 400, "单个文件最大 2GB", nil)
		return
	}
	// 容量预检（ContentLength 可用时提前拒绝，省流量）
	if capBytes > 0 && r.ContentLength > 0 && used+int64(r.ContentLength) > int64(capBytes) {
		remain := int64(capBytes) - used
		if remain < 0 {
			remain = 0
		}
		writeJSON(w, 400, fmt.Sprintf("容量不足：已用 %s / 总容量 %s，剩余 %s", fdHumanSize(used), fdHumanSize(int64(capBytes)), fdHumanSize(remain)), nil)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, 2*1024*1024*1024)
	file, header, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, "无法读取文件: "+err.Error(), nil)
		return
	}
	defer file.Close()

	// 固定链接别名（可选）：传了就是「让这个链接永久不变」，
	// 若该别名已属于本人 → 原地替换内容（链接、计数都保留）；属于他人 → 拒绝。
	alias, aerr := fdNormalizeAlias(r.FormValue("alias"))
	if aerr != nil {
		writeJSON(w, 400, aerr.Error(), nil)
		return
	}
	var replaceID, replaceOwner, replaceSize int64
	var replaceToken string
	if alias != "" {
		serr := db.QueryRow("SELECT id, user_id, file_size, token FROM fd_files WHERE alias = ?", alias).
			Scan(&replaceID, &replaceOwner, &replaceSize, &replaceToken)
		if serr == nil && replaceOwner != userID {
			writeJSON(w, 409, "固定链接已被占用，请换一个", nil)
			return
		}
		if serr != nil && serr != sql.ErrNoRows {
			writeJSON(w, 500, "查询失败", nil)
			return
		}
	}

	// 原地替换时，旧文件占用的容量会被释放，容量判断按「已用 - 旧大小」算
	usedAdj := used
	if replaceID > 0 {
		usedAdj -= replaceSize
		if usedAdj < 0 {
			usedAdj = 0
		}
	}

	// 文件名安全化（去掉路径成分）
	origName := filepath.Base(strings.ReplaceAll(header.Filename, "\\", "/"))
	origName = strings.TrimSpace(origName)
	if origName == "" || origName == "." {
		origName = "unnamed"
	}
	ext := strings.ToLower(filepath.Ext(origName))
	if fdBlockedExts[ext] {
		writeJSON(w, 400, "禁止分发可脚本渲染格式（"+strings.TrimPrefix(ext, ".")+"）", nil)
		return
	}

	// 实际读取后的容量复查（ContentLength 缺失/不准时兜底；边写边计大小）
	if capBytes > 0 && usedAdj >= int64(capBytes) {
		writeJSON(w, 400, fmt.Sprintf("容量已满：已用 %s / 总容量 %s", fdHumanSize(used), fdHumanSize(int64(capBytes))), nil)
		return
	}

	token := replaceToken
	if token == "" {
		token = randomString(24)
	}
	prefix := fdTokenPrefix(token)
	if prefix == "" {
		token = randomString(24)
		prefix = fdTokenPrefix(token)
	}
	// 文件名带纳秒级时间戳：同一秒内替换同名文件也不会互相覆盖
	storeName := fmt.Sprintf("%d_%d_%s%s", time.Now().UnixNano(), userID, prefix, ext)
	savePath := filepath.Join(fdStorageDir, storeName)
	out, err := os.Create(savePath)
	if err != nil {
		writeJSON(w, 500, "保存失败", nil)
		return
	}

	written, copyErr := io.Copy(out, file)
	closeErr := out.Close()
	if copyErr != nil || closeErr != nil {
		os.Remove(savePath)
		writeJSON(w, 500, "写入文件失败", nil)
		return
	}

	// 写入后终检：超容量立刻删除并拒绝
	if capBytes > 0 && usedAdj+written > int64(capBytes) {
		os.Remove(savePath)
		remain := int64(capBytes) - usedAdj
		if remain < 0 {
			remain = 0
		}
		writeJSON(w, 400, fmt.Sprintf("容量不足：本次需 %s，剩余 %s", fdHumanSize(written), fdHumanSize(remain)), nil)
		return
	}

	now := time.Now().Unix()
	mimeType := header.Header.Get("Content-Type")
	if replaceID > 0 {
		// 原地替换：保留 id / token / alias / 计数的同时更新内容与元信息
		if _, err := db.Exec(
			"UPDATE fd_files SET file_name = ?, file_size = ?, mime_type = ?, created_at = ? WHERE id = ?",
			origName, written, mimeType, now, replaceID); err != nil {
			os.Remove(savePath)
			writeJSON(w, 500, "记录失败", nil)
			return
		}
		fdRemovePhysicalFiles(replaceOwner, replaceToken, savePath)
		log.Printf("[文件分发] 用户 %d 原地替换 %s (/fd/%s): %s", userID, origName, alias, fdHumanSize(written))
	} else {
		_, err = db.Exec("INSERT INTO fd_files (user_id, file_name, file_size, token, mime_type, downloads, accesses, alias, created_at) VALUES (?,?,?,?,?,0,0,?,?)",
			userID, origName, written, token, mimeType, alias, now)
		if err != nil {
			os.Remove(savePath)
			// 并发抢注同一别名时唯一索引会报错，这里给出明确提示
			if strings.Contains(strings.ToLower(err.Error()), "unique") {
				writeJSON(w, 409, "固定链接已被占用，请换一个", nil)
				return
			}
			writeJSON(w, 500, "记录失败", nil)
			return
		}
		log.Printf("[文件分发] 用户 %d 上传: %s (%s) alias=%q", userID, origName, fdHumanSize(written), alias)
	}

	writeJSON(w, 200, "上传成功", map[string]interface{}{
		"url":       "/fd/" + token, // 相对路径，客户端用自身可达的 serverUrl 拼完整地址
		"fixed_url": fdFixedURL(alias),
		"alias":     alias,
		"replaced":  replaceID > 0,
		"size":      written,
	})
}

// handleFdList GET /api/fd/list — 我的文件 + 用量 + 生效配置
func handleFdList(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	// 有固定链接的排在前面（固定链接是长期对外引用的资源，优先展示），其余按上传时间倒序
	rows, err := db.Query("SELECT id, file_name, file_size, token, alias, downloads, accesses, created_at FROM fd_files WHERE user_id = ? ORDER BY (alias <> '') DESC, id DESC", userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	files := []map[string]interface{}{}
	for rows.Next() {
		var id, size, downloads, accesses, createdAt int64
		var name, token, alias string
		if err := rows.Scan(&id, &name, &size, &token, &alias, &downloads, &accesses, &createdAt); err != nil {
			continue
		}
		files = append(files, map[string]interface{}{
			"id":         id,
			"file_name":  name,
			"file_size":  size,
			"url":        "/fd/" + token, // 原始 token 链接（删除重传会变）
			"fixed_url":  fdFixedURL(alias),
			"alias":      alias,
			"downloads":  downloads,
			"accesses":   accesses,
			"created_at": createdAt,
		})
	}
	cfg := getFdConfig(userID)
	var totalDownloads, totalAccesses int64
	db.QueryRow("SELECT COALESCE(SUM(downloads),0), COALESCE(SUM(accesses),0) FROM fd_files WHERE user_id = ?", userID).
		Scan(&totalDownloads, &totalAccesses)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"files":           files,
		"used":            fdUsedBytes(userID),
		"total_downloads": totalDownloads,
		"total_accesses":  totalAccesses,
		"max_speed":       cfg.MaxSpeedMbps,
		"capacity_value":  cfg.CapacityValue,
		"capacity_unit":   cfg.CapacityUnit,
	})
}

// handleFdDelete POST /api/fd/delete {"id":N} — 本人或开发者可删
func handleFdDelete(w http.ResponseWriter, r *http.Request) {
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
		ID int64 `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	var ownerID int64
	var storeName string
	err := db.QueryRow("SELECT user_id, token FROM fd_files WHERE id = ?", req.ID).Scan(&ownerID, &storeName)
	// token 字段即存储文件名主体（fd_files 存 token，物理文件名带前缀，这里按 token 反查）
	if err != nil {
		writeJSON(w, 404, "文件不存在", nil)
		return
	}
	if ownerID != userID && !checkDevOrPerm(r, "fd.config") {
		writeJSON(w, 403, "无权删除他人文件", nil)
		return
	}
	// 找物理文件（<ts>_<uid>_<token前8><ext>）
	fdRemovePhysicalFiles(ownerID, storeName, "")
	if _, err := db.Exec("DELETE FROM fd_files WHERE id = ?", req.ID); err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	writeJSON(w, 200, "已删除", nil)
}

// handleFdSetAlias POST /api/fd/alias {"id":N,"alias":"data.json"}
// 给已上传的文件补设/修改/取消固定链接（别名传空串 = 取消，退回 token 链接）。
// 固定链接全局唯一：被他人占用时返回 409，被自己的其它文件占用时同样返回 409（避免歧义）。
func handleFdSetAlias(w http.ResponseWriter, r *http.Request) {
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
		ID    int64  `json:"id"`
		Alias string `json:"alias"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID <= 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	alias, err := fdNormalizeAlias(req.Alias)
	if err != nil {
		writeJSON(w, 400, err.Error(), nil)
		return
	}
	var ownerID int64
	var token string
	if err := db.QueryRow("SELECT user_id, token FROM fd_files WHERE id = ?", req.ID).Scan(&ownerID, &token); err != nil {
		writeJSON(w, 404, "文件不存在", nil)
		return
	}
	if ownerID != userID && !checkDevOrPerm(r, "fd.config") {
		writeJSON(w, 403, "无权修改他人文件", nil)
		return
	}
	if alias != "" {
		var conflictID int64
		err := db.QueryRow("SELECT id FROM fd_files WHERE alias = ? AND id <> ?", alias, req.ID).Scan(&conflictID)
		if err == nil {
			writeJSON(w, 409, "固定链接已被占用，请换一个", nil)
			return
		}
		if err != sql.ErrNoRows {
			writeJSON(w, 500, "查询失败", nil)
			return
		}
	}
	if _, err := db.Exec("UPDATE fd_files SET alias = ? WHERE id = ?", alias, req.ID); err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			writeJSON(w, 409, "固定链接已被占用，请换一个", nil)
			return
		}
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	log.Printf("[文件分发] 用户 %d 设置固定链接: id=%d alias=%q", userID, req.ID, alias)
	writeJSON(w, 200, "已保存", map[string]interface{}{
		"id":        req.ID,
		"alias":     alias,
		"fixed_url": fdFixedURL(alias),
		"url":       "/fd/" + token,
	})
}

// throttledWriter 按「每连接字节数/秒」限速写出
type throttledWriter struct {
	w       io.Writer
	bps     float64 // 每秒字节数，<=0 不限速
	start   time.Time
	written int64
}

func (t *throttledWriter) Write(p []byte) (int, error) {
	n, err := t.w.Write(p)
	t.written += int64(n)
	if t.bps > 0 && err == nil {
		expected := time.Duration(float64(t.written) / t.bps * float64(time.Second))
		elapsed := time.Since(t.start)
		if elapsed < expected {
			time.Sleep(expected - elapsed)
		}
	}
	return n, err
}

// fdBpsForOwner 按文件所有者的生效配置计算限速字节/秒（0 = 不限速）
func fdBpsForOwner(ownerID int64) float64 {
	cfg := getFdConfig(ownerID)
	if cfg.MaxSpeedMbps > 0 {
		return cfg.MaxSpeedMbps * 1024 * 1024 / 8
	}
	return 0
}

// fdThrottleResponse 在原 ResponseWriter 上按 bps 限速写出。
// 配合 http.ServeContent 使用：Range 分片请求（视频拖动/断点续传）
// 走的也是这个 Write，分片同样按配置限速。bps<=0 时无额外开销。
type fdThrottleResponse struct {
	http.ResponseWriter
	bps     float64
	start   time.Time
	written int64
}

func (t *fdThrottleResponse) Write(p []byte) (int, error) {
	n, err := t.ResponseWriter.Write(p)
	t.written += int64(n)
	if t.bps > 0 && err == nil {
		expected := time.Duration(float64(t.written) / t.bps * float64(time.Second))
		if elapsed := time.Since(t.start); elapsed < expected {
			time.Sleep(expected - elapsed)
		}
	}
	return n, err
}

// handleFdDownload GET /fd/<token|alias> — 公开直链（?dl=1 强制下载）
func handleFdDownload(w http.ResponseWriter, r *http.Request) {
	key := strings.Trim(strings.TrimPrefix(r.URL.Path, "/fd/"), "/")
	// token 是十六进制小写；别名入库时统一小写——这里统一小写后即可用同一个 key 解析
	key = strings.ToLower(key)
	if key == "" || strings.Contains(key, "..") || strings.Contains(key, "/") {
		http.NotFound(w, r)
		return
	}
	if r.Method != "GET" && r.Method != "HEAD" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	var id, ownerID, size, createdAt int64
	var fileName, token string
	var noLimit int
	// 先按 token（原始链接）精确匹配，未命中再按固定链接别名解析
	err := db.QueryRow("SELECT id, user_id, file_name, file_size, no_limit, created_at, token FROM fd_files WHERE token = ?", key).
		Scan(&id, &ownerID, &fileName, &size, &noLimit, &createdAt, &token)
	if err == sql.ErrNoRows {
		err = db.QueryRow("SELECT id, user_id, file_name, file_size, no_limit, created_at, token FROM fd_files WHERE alias <> '' AND alias = ?", key).
			Scan(&id, &ownerID, &fileName, &size, &noLimit, &createdAt, &token)
	}
	if err != nil {
		http.NotFound(w, r)
		return
	}

	// 找物理文件
	matches, _ := filepath.Glob(filepath.Join(fdStorageDir, "*_"+strconv.FormatInt(ownerID, 10)+"_"+fdTokenPrefix(token)+".*"))
	if len(matches) == 0 {
		http.NotFound(w, r)
		return
	}
	fullPath := matches[0]

	// 下载速度限制：按文件所有者的生效配置（Mbps → 字节/秒）；中转加速文件（no_limit=1）不限速
	var bps float64
	if noLimit == 1 {
		bps = 0
	} else {
		cfg := getFdConfig(ownerID)
		if cfg.MaxSpeedMbps > 0 {
			bps = cfg.MaxSpeedMbps * 1024 * 1024 / 8
		}
	}

	// ?dl=1 强制 attachment（其余走统一安全头：图片内联、其他 attachment）
	applyFileResponseHeaders(w, fileName)
	if r.URL.Query().Get("dl") == "1" {
		w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, sanitizeFilenameForHeader(fileName)))
	}

	f, err := os.Open(fullPath)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()

	// 计数（后台异步，不阻塞下载）：
	//   ?dl=1 → 下载次数；其余 → 访问次数（两条链接的语义不同，分开统计）
	//   HEAD 探测与 Range 分片（视频拖动进度/断点续传）不重复计数，避免一次观看被算成几十次
	if r.Method == "GET" && r.Header.Get("Range") == "" {
		if r.URL.Query().Get("dl") == "1" {
			go db.Exec("UPDATE fd_files SET downloads = downloads + 1 WHERE id = ?", id)
		} else {
			go db.Exec("UPDATE fd_files SET accesses = accesses + 1 WHERE id = ?", id)
		}
	}

	// ServeContent 自带 Range 分片支持（视频拖动进度、断点续传）；
	// 每个分片请求经限速包装器写出——视频播放、图片渲染同样按所有者配置限速。
	tw := &fdThrottleResponse{ResponseWriter: w, bps: bps, start: time.Now()}
	http.ServeContent(tw, r, fileName, time.Unix(createdAt, 0), f)
}

// fdValidateConfigValue 按注册表校验单个配置值（key 不在注册表 → 拒绝，防止乱写键）
func fdValidateConfigValue(key, value string) (string, error) {
	spec, ok := fdConfigKeySpecs[key]
	if !ok {
		return "", fmt.Errorf("未注册的配置项: %s", key)
	}
	v := strings.TrimSpace(value)
	switch spec {
	case "number":
		if v == "" {
			return "", nil // 空值 = 删除该键（不限）
		}
		f, err := strconv.ParseFloat(v, 64)
		if err != nil || f < 0 {
			return "", fmt.Errorf("配置项 %s 须为非负数字", key)
		}
		return strconv.FormatFloat(f, 'f', -1, 64), nil
	case "unit":
		if v == "" {
			return "", nil
		}
		u := strings.ToUpper(v)
		for _, opt := range fdConfigUnitOptions {
			if u == opt {
				return u, nil
			}
		}
		return "", fmt.Errorf("配置项 %s 仅支持 %s", key, strings.Join(fdConfigUnitOptions, "/"))
	}
	return v, nil
}

// handleFdAdminConfig GET/POST /api/admin/fd/config — 开发者读取/设置配置（键值驱动）
// GET  ?user_id=N   （N=0 → 默认通用配置；N>0 → 该用户的单独配置）
//
//	返回: {"user_id":N,"has_custom":bool,"configs":{...},"default_configs":{...},"used":N}
//
// POST {"user_id":N,"configs":{"max_speed":"5","capacity_value":"10","capacity_unit":"GB"},"reset":false}
//   - user_id=0 → 修改默认(通用)配置；user_id>0 → 修改该用户的单独配置
//   - reset=true → 清除该目标的全部单独配置（用户恢复为套用默认配置）
//   - configs 里值为空字符串 → 删除该键（该项恢复为不限）
func handleFdAdminConfig(w http.ResponseWriter, r *http.Request) {
	if !checkDevOrPerm(r, "fd.config") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	switch r.Method {
	case "GET":
		uidStr := r.URL.Query().Get("user_id")
		uid, err := strconv.ParseInt(uidStr, 10, 64)
		if err != nil || uid < 0 {
			writeJSON(w, 400, "user_id 参数错误", nil)
			return
		}
		if uid > 0 {
			var exists int
			db.QueryRow("SELECT COUNT(*) FROM users WHERE id = ?", uid).Scan(&exists)
			if exists == 0 {
				writeJSON(w, 404, "用户不存在", nil)
				return
			}
		}
		writeJSON(w, 200, "ok", map[string]interface{}{
			"user_id":         uid,
			"has_custom":      fdHasCustomConfig(uid),
			"configs":         fdMapToJSON(fdGetConfigMap(uid)),
			"default_configs": fdMapToJSON(fdGetConfigMap(fdDefaultConfigUserID)),
			"used":            fdUsedBytes(uid),
		})
	case "POST":
		var req struct {
			UserID  int64             `json:"user_id"`
			Configs map[string]string `json:"configs"`
			Reset   bool              `json:"reset"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UserID < 0 {
			writeJSON(w, 400, "参数解析失败", nil)
			return
		}
		if req.UserID > 0 {
			var exists int
			db.QueryRow("SELECT COUNT(*) FROM users WHERE id = ?", req.UserID).Scan(&exists)
			if exists == 0 {
				writeJSON(w, 404, "用户不存在", nil)
				return
			}
		}
		if req.Reset {
			if _, err := db.Exec("DELETE FROM fd_user_configs WHERE user_id = ?", req.UserID); err != nil {
				writeJSON(w, 500, "清除失败", nil)
				return
			}
			log.Printf("[文件分发] 管理员清除 user_id=%d 的单独配置（恢复默认）", req.UserID)
			writeJSON(w, 200, "已清除单独配置，恢复为默认配置", map[string]interface{}{
				"user_id": req.UserID, "has_custom": false, "configs": map[string]string{},
			})
			return
		}
		if len(req.Configs) == 0 {
			writeJSON(w, 400, "configs 不能为空", nil)
			return
		}
		// 先全部校验，再统一写入（避免写一半失败留下脏数据）
		cleaned := map[string]string{}
		for k, v := range req.Configs {
			cv, err := fdValidateConfigValue(k, v)
			if err != nil {
				writeJSON(w, 400, err.Error(), nil)
				return
			}
			cleaned[k] = cv
		}
		for k, v := range cleaned {
			if v == "" {
				db.Exec("DELETE FROM fd_user_configs WHERE user_id = ? AND config_key = ?", req.UserID, k)
				continue
			}
			db.Exec(`
				INSERT INTO fd_user_configs (user_id, config_key, config_value, updated_at)
				VALUES (?,?,?,?)
				ON CONFLICT(user_id, config_key) DO UPDATE SET
					config_value = excluded.config_value,
					updated_at   = excluded.updated_at
			`, req.UserID, k, v, time.Now().Unix())
		}
		target := "默认(通用)配置"
		if req.UserID > 0 {
			target = fmt.Sprintf("用户 %d", req.UserID)
		}
		log.Printf("[文件分发] 管理员更新%s: %v", target, cleaned)
		writeJSON(w, 200, "配置已保存", map[string]interface{}{
			"user_id":    req.UserID,
			"has_custom": fdHasCustomConfig(req.UserID),
			"configs":    fdMapToJSON(fdGetConfigMap(req.UserID)),
		})
	default:
		writeJSON(w, 405, "仅支持 GET/POST", nil)
	}
}

// handleFdAdminCustomList GET /api/admin/fd/config/custom-list — 已单独配置的用户列表
// 返回: {"list":[{"user_id":N,"username":"...","configs":{...},"used":N}]}
func handleFdAdminCustomList(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	if !checkDevOrPerm(r, "fd.config") {
		writeJSON(w, 403, "无权访问", nil)
		return
	}
	rows, err := db.Query(`
		SELECT DISTINCT c.user_id, COALESCE(u.username, '')
		FROM fd_user_configs c LEFT JOIN users u ON u.id = c.user_id
		WHERE c.user_id > 0 ORDER BY c.user_id DESC
	`)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	defer rows.Close()
	list := []map[string]interface{}{}
	for rows.Next() {
		var uid int64
		var username string
		if rows.Scan(&uid, &username) != nil {
			continue
		}
		list = append(list, map[string]interface{}{
			"user_id":  uid,
			"username": username,
			"configs":  fdMapToJSON(fdGetConfigMap(uid)),
			"used":     fdUsedBytes(uid),
		})
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"list": list})
}
