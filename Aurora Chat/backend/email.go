package main

import (
	"crypto/tls"
	"fmt"
	"log"
	"math/rand"
	"net/smtp"
	"os"
	"sync"
	"time"
)

// 验证码冷却缓存
var (
	codeCoolDown   = make(map[string]int64)
	codeCoolDownMu sync.Mutex
)

// SMTP 账号结构体
type smtpAccount struct {
	Username string
	Password string
}

// 五个 QQ 邮箱 SMTP 账号，随机轮流使用以避免单账号限流
var smtpAccounts = []smtpAccount{
	{Username: "smtp_main@example.com", Password: "YOUR_SMTP_PASSWORD"},
	{Username: "smtp_account_2@example.com", Password: "YOUR_SMTP_PASSWORD_2"},
	{Username: "smtp_account_3@example.com", Password: "YOUR_SMTP_PASSWORD_3"},
	{Username: "smtp_account_4@example.com", Password: "YOUR_SMTP_PASSWORD_4"},
	{Username: "smtp_account_5@example.com", Password: "YOUR_SMTP_PASSWORD_5"},
}

var smtpMu sync.Mutex

// 全局 SMTP 发送速率限制：两次发送至少间隔 30 秒，避免 QQ 风控
var (
	lastSMTPTime int64
	smtpRateMu   sync.Mutex
)

const smtpMinInterval = 30 // 秒，两次发送最小间隔

func waitSMTPRate() {
	smtpRateMu.Lock()
	defer smtpRateMu.Unlock()
	elapsed := time.Now().Unix() - lastSMTPTime
	if elapsed < smtpMinInterval {
		wait := smtpMinInterval - elapsed
		log.Printf("[邮件] 全局发送频率控制，等待 %d 秒", wait)
		time.Sleep(time.Duration(wait) * time.Second)
	}
	lastSMTPTime = time.Now().Unix()
}

// pickRandomAccount 随机选取一个 SMTP 账号（加权：第一个70%，后两个各15%）
func pickRandomAccount() smtpAccount {
	smtpMu.Lock()
	defer smtpMu.Unlock()
	r := rand.Intn(100)
	if r < 70 {
		return smtpAccounts[0] // smtp_main@example.com 优先
	} else if r < 85 {
		return smtpAccounts[1]
	}
	return smtpAccounts[2]
}

// getMailConfig 返回 SMTP 连接配置（随机选一个账号）
func getMailConfig() (addr, identity, username, password, server string) {
	addr = os.Getenv("SMTP_ADDR")
	if addr == "" {
		addr = "smtp.qq.com:465"
	}
	server = os.Getenv("SMTP_SERVER")
	if server == "" {
		server = "smtp.qq.com"
	}
	// 如果环境变量指定了账号，优先使用（兼容旧配置）
	envUser := os.Getenv("SMTP_USERNAME")
	envPass := os.Getenv("SMTP_PASSWORD")
	if envUser != "" && envPass != "" {
		return addr, "", envUser, envPass, server
	}
	acct := pickRandomAccount()
	identity = ""
	username = acct.Username
	password = acct.Password
	return
}

// ---------------- 低层邮件发送辅助 ----------------

// sendSMTPMail 使用随机账号发送邮件，统一处理连接和认证
func sendSMTPMail(to, subject, htmlBody string) (bool, string) {
	addr, _, username, password, server := getMailConfig()

	if username == "" || password == "" {
		log.Printf("[邮件] SMTP 未配置，邮件将打印到日志")
		log.Printf("[邮件] 主题: %s -> %s", subject, to)
		return true, "邮件已打印到日志（开发模式）"
	}

	return sendSMTPMailAs(to, subject, htmlBody, addr, server, username, password, false)
}

// sendSMTPMailAs 使用指定的 SMTP 账号发送（验证码与邮箱推送共用）。
// skipRate 为 true 时跳过 30 秒全局冷却（用于坏账号失败后的立即重发）。
func sendSMTPMailAs(to, subject, htmlBody, addr, server, username, password string, skipRate bool) (bool, string) {
	// 全局频率控制：两次发送至少间隔 30 秒（失败重发时可跳过）
	if !skipRate {
		waitSMTPRate()
	}

	log.Printf("[邮件] 开始发送 [%s] -> %s (使用: %s)", subject, to, username)

	auth := smtp.PlainAuth("", username, password, server)

	tlsConfig := &tls.Config{ServerName: server, InsecureSkipVerify: false}
	conn, err := tls.Dial("tcp", addr, tlsConfig)
	if err != nil {
		errMsg := fmt.Sprintf("邮件服务器连接失败: %v", err)
		return false, errMsg
	}

	client, err := smtp.NewClient(conn, server)
	if err != nil {
		conn.Close()
		return false, fmt.Sprintf("SMTP 客户端创建失败: %v", err)
	}
	defer client.Close()

	if err = client.Auth(auth); err != nil {
		return false, fmt.Sprintf("SMTP 认证失败(授权码可能已过期): %v", err)
	}

	if err = client.Mail(username); err != nil {
		return false, fmt.Sprintf("发件人设置失败: %v", err)
	}
	if err = client.Rcpt(to); err != nil {
		return false, fmt.Sprintf("收件人设置失败: %v", err)
	}

	w, err := client.Data()
	if err != nil {
		return false, fmt.Sprintf("邮件数据流创建失败: %v", err)
	}

	msg := []byte(fmt.Sprintf(
		"From: %s\r\nTo: %s\r\nContent-Type: text/html; charset=UTF-8\r\nSubject: %s\r\n\r\n%s",
		username, to, subject, htmlBody,
	))

	_, err = w.Write(msg)
	if err != nil {
		w.Close()
		return false, fmt.Sprintf("邮件写入失败: %v", err)
	}

	if err = w.Close(); err != nil {
		errMsg := fmt.Sprintf("%v", err)
		log.Printf("[邮件] 服务器拒绝投递: %s", errMsg)
		return false, fmt.Sprintf("邮件被服务器拒绝(%s)，可能是发送频率过快或内容触发了风控，请稍后重试", errMsg)
	}

	client.Quit()
	log.Printf("[邮件] 发送成功 [%s] -> %s", subject, to)
	return true, "发送成功"
}

// ---------------- 统一 HTML 邮件模板 ----------------

// buildEmailHTML 构建统一风格的 HTML 邮件正文
// title: 邮件标题（如"邮箱验证"、"账号已解封"）
// contentLines: 正文内容行（每行一个 <p> 元素）
func buildEmailHTML(title string, contentLines ...string) string {
	body := ""
	for _, line := range contentLines {
		body += fmt.Sprintf(`<p style='color:#555;font-size:14px;margin:0 0 8px;'>%s</p>`, line)
	}
	return fmt.Sprintf(
		`<div style='font-size:16px;padding:20px;max-width:500px;margin:0 auto;'>`+
			`<div style='background:#f8f9fa;border-radius:12px;padding:24px;'>`+
			`<h2 style='margin:0 0 16px;color:#1a1a2e;'>%s</h2>`+
			`%s`+
			`</div><hr style='border:none;border-top:1px solid #eee;margin:16px 0;'>`+
			`<p style='color:#bbb;font-size:11px;text-align:center;'>拾光工作室</p>`+
			`</div>`, title, body)
}

// buildVerificationEmailHTML 构建验证码邮件正文（带特殊样式的大号验证码）
func buildVerificationEmailHTML(code string) string {
	return fmt.Sprintf(
		`<div style='font-size:16px;padding:20px;max-width:500px;margin:0 auto;'>`+
			`<div style='background:#f8f9fa;border-radius:12px;padding:24px;'>`+
			`<h2 style='margin:0 0 16px;color:#1a1a2e;'>Aurora Chat 邮箱验证</h2>`+
			`<p style='color:#555;font-size:14px;margin:0 0 8px;'>您的验证码为：</p>`+
			`<div style='background:#fff;border-radius:8px;padding:16px;text-align:center;border:1px solid #eef0f4;'>`+
			`<span style='font-size:32px;font-weight:800;color:#000000;letter-spacing:8px;'>%s</span></div>`+
			`<p style='color:#999;font-size:12px;margin-top:12px;'>有效期5分钟，如非本人操作请忽略。</p>`+
			`</div><hr style='border:none;border-top:1px solid #eee;margin:16px 0;'>`+
			`<p style='color:#bbb;font-size:11px;text-align:center;'>拾光工作室</p>`+
			`</div>`, code)
}

// ---------------- 各类型邮件发送函数 ----------------

// generateCode 生成6位数字验证码
func generateCode() string {
	return fmt.Sprintf("%06d", rand.Intn(1000000))
}

// sendEmailCode 发送验证码邮件
func sendEmailCode(to, code string) (bool, string) {
	// 检查 SMTP 是否配置（任意一个账号有凭据即可）
	_, _, usr, pwd, _ := getMailConfig()
	if usr == "" || pwd == "" {
		log.Printf("[邮件] SMTP 未配置，验证码 %s 将打印到日志", code)
		log.Printf("[邮件] 验证码 %s -> %s", to, code)
		return true, "验证码已发送（开发模式）"
	}
	return sendSMTPMail(to, "Aurora Chat - 邮箱验证码", buildVerificationEmailHTML(code))
}

func checkCodeCoolDown(email string) (bool, int) {
	codeCoolDownMu.Lock()
	defer codeCoolDownMu.Unlock()

	lastSent, exists := codeCoolDown[email]
	now := time.Now().Unix()
	if exists {
		elapsed := now - lastSent
		if elapsed < int64(CodeCoolDown) {
			return false, int(CodeCoolDown - elapsed)
		}
	}
	return true, 0
}

func recordCodeSent(email string) {
	codeCoolDownMu.Lock()
	defer codeCoolDownMu.Unlock()
	codeCoolDown[email] = time.Now().Unix()
}

// sendUnbanEmail 发送解封通知邮件
func sendUnbanEmail(to, reason string) {
	if to == "" {
		return
	}

	htmlBody := buildEmailHTML("账号已解封",
		"您好，",
		"您的 Aurora Chat 账号封禁已到期，现已自动解封。",
		fmt.Sprintf("封禁原因：%s", reason),
		`<span style='color:#999;font-size:12px;'>您可以重新登录使用。</span>`,
	)
	success, msg := sendSMTPMail(to, "Aurora Chat - 账号已解封", htmlBody)
	if !success {
		log.Printf("[邮件] 解封通知发送失败: %s", msg)
	}
}

// getMailConfigAddr 获取 SMTP 地址（供其他文件使用）
func getMailConfigAddr() string {
	addr := os.Getenv("SMTP_ADDR")
	if addr == "" {
		return "smtp.qq.com:465"
	}
	return addr
}
