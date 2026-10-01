package main

import (
	"bytes"
	"database/sql"
	"fmt"
	"io"
	"log"
	"math/rand"
	"mime"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/emersion/go-smtp"
)

// ========== 数据库操作 ==========

func initTempMailTables() {
	// 临时邮箱账号表
	db.Exec(`CREATE TABLE IF NOT EXISTS temp_mail_accounts (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		address TEXT NOT NULL UNIQUE,
		created_at INTEGER NOT NULL,
		expired_at INTEGER NOT NULL
	)`)
	db.Exec(`CREATE INDEX IF NOT EXISTS idx_temp_mail_user ON temp_mail_accounts(user_id)`)

	// 收件箱表
	db.Exec(`CREATE TABLE IF NOT EXISTS temp_mail_inbox (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		account_id INTEGER NOT NULL,
		from_address TEXT,
		to_address TEXT NOT NULL,
		subject TEXT,
		body TEXT,
		html TEXT,
		received_at INTEGER NOT NULL,
		is_read INTEGER DEFAULT 0
	)`)
	db.Exec(`CREATE INDEX IF NOT EXISTS idx_temp_mail_account ON temp_mail_inbox(account_id)`)
	db.Exec(`CREATE INDEX IF NOT EXISTS idx_temp_mail_received ON temp_mail_inbox(received_at)`)

	log.Println("[临时邮箱] 数据库表初始化完成")
}

// 创建临时邮箱（每个用户一个，覆盖旧邮箱）
func createTempMailAccount(userID int64) (string, error) {
	// 删除该用户的旧邮箱及邮件
	var oldID int64
	err := db.QueryRow("SELECT id FROM temp_mail_accounts WHERE user_id = ?", userID).Scan(&oldID)
	if err == nil {
		db.Exec("DELETE FROM temp_mail_inbox WHERE account_id = ?", oldID)
	}
	db.Exec("DELETE FROM temp_mail_accounts WHERE user_id = ?", userID)

	now := time.Now().Unix()
	expired := now + 24*3600 // 24小时

	// 生成随机邮箱地址（确保唯一）
	var address string
	for i := 0; i < 10; i++ {
		randomStr := generateRandomString(10)
		address = fmt.Sprintf("%s@YOUR_SERVER_DOMAIN", randomStr)
		var exists int
		err := db.QueryRow("SELECT 1 FROM temp_mail_accounts WHERE address = ?", address).Scan(&exists)
		if err == sql.ErrNoRows {
			break
		}
	}

	_, err = db.Exec("INSERT INTO temp_mail_accounts (user_id, address, created_at, expired_at) VALUES (?, ?, ?, ?)",
		userID, address, now, expired)
	if err != nil {
		return "", err
	}
	return address, nil
}

// 获取用户的临时邮箱
func getUserTempMailAccount(userID int64) (int64, string, error) {
	var id int64
	var address string
	err := db.QueryRow("SELECT id, address FROM temp_mail_accounts WHERE user_id = ? AND expired_at > ?",
		userID, time.Now().Unix()).Scan(&id, &address)
	if err == sql.ErrNoRows {
		return 0, "", nil
	}
	if err != nil {
		return 0, "", err
	}
	return id, address, nil
}

// 保存邮件（通过SMTP接收）
func saveTempMail(accountID int64, from, to, subject, body, html string) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT INTO temp_mail_inbox (account_id, from_address, to_address, subject, body, html, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
		accountID, from, to, subject, body, html, now)
	return err
}

// 获取收件箱
func getTempMailInbox(accountID int64) ([]map[string]interface{}, error) {
	rows, err := db.Query("SELECT id, from_address, subject, received_at, is_read FROM temp_mail_inbox WHERE account_id = ? ORDER BY received_at DESC", accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var mails []map[string]interface{}
	for rows.Next() {
		var id int64
		var fromAddr, subject string
		var receivedAt int64
		var isRead int
		rows.Scan(&id, &fromAddr, &subject, &receivedAt, &isRead)
		mails = append(mails, map[string]interface{}{
			"id":          id,
			"from":        fromAddr,
			"subject":     subject,
			"received_at": receivedAt,
			"is_read":     isRead == 1,
		})
	}
	return mails, nil
}

// 获取单封邮件
func getTempMailByID(mailID int64) (map[string]interface{}, error) {
	var id int64
	var fromAddr, toAddr, subject, body, html string
	var receivedAt int64
	var isRead int
	err := db.QueryRow("SELECT id, from_address, to_address, subject, body, html, received_at, is_read FROM temp_mail_inbox WHERE id = ?", mailID).
		Scan(&id, &fromAddr, &toAddr, &subject, &body, &html, &receivedAt, &isRead)
	if err != nil {
		return nil, err
	}
	// 标记已读
	db.Exec("UPDATE temp_mail_inbox SET is_read = 1 WHERE id = ?", mailID)
	return map[string]interface{}{
		"id":          id,
		"from":        fromAddr,
		"to":          toAddr,
		"subject":     subject,
		"body":        body,
		"html":        html,
		"received_at": receivedAt,
		"is_read":     isRead == 1,
	}, nil
}

// 删除邮件
func deleteTempMail(mailID int64) error {
	_, err := db.Exec("DELETE FROM temp_mail_inbox WHERE id = ?", mailID)
	return err
}

// 删除用户临时邮箱
func deleteUserTempMailAccount(userID int64) error {
	var oldID int64
	err := db.QueryRow("SELECT id FROM temp_mail_accounts WHERE user_id = ?", userID).Scan(&oldID)
	if err == nil {
		db.Exec("DELETE FROM temp_mail_inbox WHERE account_id = ?", oldID)
	}
	_, err = db.Exec("DELETE FROM temp_mail_accounts WHERE user_id = ?", userID)
	return err
}

// 清理过期邮箱
func cleanupExpiredTempMail() {
	now := time.Now().Unix()
	result, err := db.Exec("DELETE FROM temp_mail_inbox WHERE account_id IN (SELECT id FROM temp_mail_accounts WHERE expired_at < ?)", now)
	var n1, n2 int64
	if err == nil {
		n1, _ = result.RowsAffected()
	} else {
		log.Printf("[临时邮箱] 清理收件箱失败: %v", err)
	}
	result, err = db.Exec("DELETE FROM temp_mail_accounts WHERE expired_at < ?", now)
	if err == nil {
		n2, _ = result.RowsAffected()
	} else {
		log.Printf("[临时邮箱] 清理账号失败: %v", err)
	}
	if n1 > 0 || n2 > 0 {
		log.Printf("[临时邮箱] 已清理过期记录: %d 封邮件, %d 个邮箱", n1, n2)
	}
}

// 生成随机字符串
func generateRandomString(length int) string {
	const chars = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, length)
	for i := range b {
		b[i] = chars[rand.Intn(len(chars))]
	}
	return string(b)
}

// ========== SMTP Backend & Session ==========

type TempMailBackend struct{}

func (b *TempMailBackend) NewSession(c *smtp.Conn) (smtp.Session, error) {
	return &TempMailSession{}, nil
}

type TempMailSession struct {
	from string
	to   []string
}

func (s *TempMailSession) Reset() {
	s.from = ""
	s.to = nil
}

func (s *TempMailSession) Logout() error {
	return nil
}

func (s *TempMailSession) Mail(from string, opts *smtp.MailOptions) error {
	s.from = from
	return nil
}

func (s *TempMailSession) Rcpt(to string, opts *smtp.RcptOptions) error {
	// 收件地址统一转小写（SMTP 本地部分通常不区分大小写，避免大小写导致查不到）
	lower := strings.ToLower(to)

	// 只允许本服务器域，拒绝作为中继（开放中继会被滥用且被拉黑）
	const localDomain = "YOUR_SERVER_DOMAIN"
	if !strings.HasSuffix(lower, "@"+localDomain) {
		log.Printf("[SMTP] 拒绝中继: %s", to)
		return fmt.Errorf("relay access denied for %s", to)
	}

	// 收件人必须存在且未过期，否则返回 550 让发件方明确退信，
	// 而不是在 Data() 里静默丢弃（那样 QQ 显示"发送成功"却收不到，难以排查）
	var exists int
	err := db.QueryRow("SELECT 1 FROM temp_mail_accounts WHERE address = ? AND expired_at > ?",
		lower, time.Now().Unix()).Scan(&exists)
	if err != nil {
		log.Printf("[SMTP] 拒绝未知收件人: %s", to)
		return fmt.Errorf("no such user: %s", to)
	}

	s.to = append(s.to, lower)
	return nil
}

func (s *TempMailSession) Data(r io.Reader) error {
	buf := new(bytes.Buffer)
	buf.ReadFrom(r)
	data := buf.String()

	msg, err := parseEmail(data)
	if err != nil {
		log.Printf("[SMTP] 解析邮件失败: %v", err)
		return nil
	}

	for _, to := range s.to {
		var accountID int64
		err := db.QueryRow("SELECT id FROM temp_mail_accounts WHERE address = ? AND expired_at > ?",
			to, time.Now().Unix()).Scan(&accountID)
		if err != nil {
			log.Printf("[SMTP] 未找到收件人: %s", to)
			continue
		}

		err = saveTempMail(accountID, s.from, to, msg.subject, msg.body, msg.html)
		if err != nil {
			log.Printf("[SMTP] 保存邮件失败: %v", err)
		} else {
			log.Printf("[SMTP] 收到邮件: %s -> %s, 主题: %s", s.from, to, msg.subject)
		}
	}
	return nil
}

// ========== 邮件解析 ==========

type parsedEmail struct {
	subject string
	body    string
	html    string
}

func parseEmail(data string) (*parsedEmail, error) {
	result := &parsedEmail{}

	// 分割头部和正文
	parts := strings.SplitN(data, "\r\n\r\n", 2)
	if len(parts) < 2 {
		parts = strings.SplitN(data, "\n\n", 2)
	}
	if len(parts) < 2 {
		result.body = data
		return result, nil
	}

	header := parts[0]
	body := parts[1]

	// 简单解析头部（处理折叠行）
	headerMap := make(map[string]string)
	lines := strings.Split(header, "\r\n")
	if len(lines) == 1 {
		lines = strings.Split(header, "\n")
	}
	var currentKey string
	for _, line := range lines {
		if len(line) == 0 {
			continue
		}
		if line[0] == ' ' || line[0] == '\t' {
			// 折叠行，追加到当前key
			if currentKey != "" {
				headerMap[currentKey] += " " + strings.TrimSpace(line)
			}
		} else if idx := strings.Index(line, ":"); idx > 0 {
			currentKey = strings.ToLower(strings.TrimSpace(line[:idx]))
			val := strings.TrimSpace(line[idx+1:])
			headerMap[currentKey] = val
		}
	}

	result.subject = headerMap["subject"]
	if result.subject != "" {
		result.subject = decodeMimeHeader(result.subject)
	}

	contentType := headerMap["content-type"]
	if contentType == "" {
		result.body = body
		return result, nil
	}

	mediaType, params, err := mime.ParseMediaType(contentType)
	if err != nil {
		result.body = body
		return result, nil
	}

	if strings.HasPrefix(mediaType, "multipart/") {
		boundary := params["boundary"]
		if boundary != "" {
			mr := multipart.NewReader(strings.NewReader(body), boundary)
			for {
				p, err := mr.NextPart()
				if err == io.EOF {
					break
				}
				if err != nil {
					continue
				}
				partType := p.Header.Get("Content-Type")
				partBody, _ := io.ReadAll(p)
				if strings.HasPrefix(partType, "text/plain") {
					result.body = string(partBody)
				} else if strings.HasPrefix(partType, "text/html") {
					result.html = string(partBody)
				}
				p.Close()
			}
		}
	} else if mediaType == "text/plain" {
		result.body = body
	} else if mediaType == "text/html" {
		result.html = body
	} else {
		result.body = body
	}

	return result, nil
}

func decodeMimeHeader(s string) string {
	dec := new(mime.WordDecoder)
	result, err := dec.DecodeHeader(s)
	if err != nil {
		return s
	}
	return result
}

// ========== 启动 SMTP 服务器 ==========

func startSMTPServer() {
	be := &TempMailBackend{}
	s := smtp.NewServer(be)
	s.Addr = ":25"
	// HELO/EHLO 通告的主机名，应与公网 IP 的反向解析(PTR)一致，
	// 否则 QQ 等严格服务商可能拒收。收件域校验仍用 YOUR_SERVER_DOMAIN（见 Rcpt）。
	s.Domain = "mail.YOUR_SERVER_DOMAIN"
	s.MaxRecipients = 10
	s.MaxMessageBytes = 10 * 1024 * 1024 // 10MB
	s.AllowInsecureAuth = true

	log.Println("[SMTP] 临时邮箱服务器启动: 0.0.0.0:25 (YOUR_SERVER_DOMAIN)")

	go func() {
		if err := s.ListenAndServe(); err != nil {
			log.Printf("[SMTP] 服务器错误: %v", err)
		}
	}()

	// 定时清理过期邮箱
	go func() {
		for {
			time.Sleep(1 * time.Hour)
			cleanupExpiredTempMail()
		}
	}()
}

// ========== HTTP Handlers ==========

// 创建临时邮箱
func handleCreateTempMail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	address, err := createTempMailAccount(userID)
	if err != nil {
		writeJSON(w, 500, "创建失败: "+err.Error(), nil)
		return
	}

	writeJSON(w, 200, "创建成功", map[string]interface{}{
		"address":    address,
		"expired_at": time.Now().Unix() + 24*3600,
	})
}

// 获取收件箱
func handleGetTempMailInbox(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	accountID, address, err := getUserTempMailAccount(userID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if accountID == 0 {
		writeJSON(w, 200, "暂无临时邮箱", map[string]interface{}{
			"address": "",
			"mails":   []map[string]interface{}{},
		})
		return
	}

	mails, err := getTempMailInbox(accountID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"address": address,
		"mails":   mails,
	})
}

// 获取单封邮件
func handleGetTempMail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	// 从 URL 提取邮件ID
	path := strings.TrimPrefix(r.URL.Path, "/api/temp-mail/mail/")
	mailID, err := strconv.ParseInt(path, 10, 64)
	if err != nil {
		writeJSON(w, 400, "无效的邮件ID", nil)
		return
	}

	mail, err := getTempMailByID(mailID)
	if err != nil {
		if err == sql.ErrNoRows {
			writeJSON(w, 404, "邮件不存在", nil)
			return
		}
		writeJSON(w, 500, "查询失败", nil)
		return
	}

	writeJSON(w, 200, "ok", mail)
}

// 删除邮件
func handleDeleteTempMail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeJSON(w, 405, "仅支持 DELETE", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	mailIDStr := r.URL.Query().Get("id")
	mailID, err := strconv.ParseInt(mailIDStr, 10, 64)
	if err != nil {
		writeJSON(w, 400, "无效的邮件ID", nil)
		return
	}

	err = deleteTempMail(mailID)
	if err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}

	writeJSON(w, 200, "删除成功", nil)
}

// 删除临时邮箱
func handleDeleteTempMailAccount(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		writeJSON(w, 405, "仅支持 DELETE", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	err := deleteUserTempMailAccount(userID)
	if err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}

	writeJSON(w, 200, "删除成功", nil)
}
