package main

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/smtp"
	"sync"
	"time"
)

// 错误报告请求体
type ErrorReportRequest struct {
	Level  string `json:"level"`
	Tag    string `json:"tag"`
	Time   string `json:"time"`
	Device string `json:"device"`
	Msg    string `json:"msg"`
}

// 错误报告冷却（按标签分别冷却，30分钟内不重复发送同类型错误）
var lastErrorReports = struct {
	m map[string]int64
	sync.Mutex
}{m: make(map[string]int64)}

func handleErrorReport(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "方法不允许", nil)
		return
	}

	var req ErrorReportRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.Tag == "" || req.Msg == "" {
		writeJSON(w, 400, "缺少必要参数", nil)
		return
	}

	// 只通知真正的代码 Bug：忽略用户侧的业务错误（API / Network / Download）
	userSideTags := map[string]bool{"API": true, "Network": true, "Download": true}
	isCodeBug := !userSideTags[req.Tag] && (req.Level == "错误" || req.Level == "致命")
	if isCodeBug {
		// 按标签冷却：30分钟内不重复发送同类型错误
		lastErrorReports.Lock()
		lastTS, exists := lastErrorReports.m[req.Tag]
		now := time.Now().Unix()
		if exists && now-lastTS < 1800 { // 30分钟 = 1800秒
			lastErrorReports.Unlock()
			log.Printf("[错误报告] 跳过: %s 类型错误30分钟内已报告过", req.Tag)
			writeJSON(w, 200, "已报告（冷却中）", nil)
			return
		}
		lastErrorReports.m[req.Tag] = now
		lastErrorReports.Unlock()
		subject := fmt.Sprintf("[Aurora Chat 错误报告] %s - %s", req.Level, req.Tag)
		body := fmt.Sprintf(
			`<div style="font-family:sans-serif;max-width:600px;margin:0 auto;">
				<h2 style="color:#DC2626;">Aurora Chat 错误报告</h2>
				<table style="width:100%%;border-collapse:collapse;">
					<tr><td style="padding:8px;background:#f3f4f6;font-weight:bold;">级别</td><td style="padding:8px;">%s</td></tr>
					<tr><td style="padding:8px;background:#f3f4f6;font-weight:bold;">类型</td><td style="padding:8px;">%s</td></tr>
					<tr><td style="padding:8px;background:#f3f4f6;font-weight:bold;">时间</td><td style="padding:8px;">%s</td></tr>
					<tr><td style="padding:8px;background:#f3f4f6;font-weight:bold;">设备</td><td style="padding:8px;">%s</td></tr>
					<tr><td style="padding:8px;background:#f3f4f6;font-weight:bold;">消息</td><td style="padding:8px;color:#DC2626;">%s</td></tr>
				</table>
				<p style="color:#999;font-size:12px;margin-top:16px;">请登录错误日志页面查看详细堆栈信息。</p>
			</div>`,
			req.Level, req.Tag, req.Time, req.Device, req.Msg,
		)
		_, _, smtpUser, _, _ := getMailConfig()
		_ = sendRawEmail(smtpUser, subject, body)
		log.Printf("[错误报告] 已通知开发者: [%s] %s - %s", req.Level, req.Tag, req.Msg)
	} else {
		log.Printf("[错误报告] 用户侧错误，跳过邮件通知: [%s] %s", req.Tag, req.Msg)
	}

	writeJSON(w, 200, "已报告", nil)
}

// sendRawEmail 发送纯 HTML 邮件（不依赖验证码功能）
func sendRawEmail(to, subject, htmlBody string) error {
	addr, identity, username, password, server := getMailConfig()
	if username == "" || password == "" {
		log.Printf("[邮件] SMTP 未配置，跳过错误报告邮件: %s", subject)
		return nil
	}
	msg := []byte(fmt.Sprintf(
		"From: %s\r\nTo: %s\r\nContent-Type: text/html; charset=UTF-8\r\nSubject: %s\r\n\r\n%s",
		username, to, subject, htmlBody,
	))
	return sendMail(addr, identity, username, password, server, to, msg)
}

// sendMail 执行 SMTP 发送
func sendMail(addr, identity, username, password, server, to string, msg []byte) error {
	auth := smtp.PlainAuth(identity, username, password, server)
	tlsConfig := &tls.Config{ServerName: server, InsecureSkipVerify: false}
	conn, err := tls.Dial("tcp", addr, tlsConfig)
	if err != nil {
		return fmt.Errorf("连接失败: %v", err)
	}
	client, err := smtp.NewClient(conn, server)
	if err != nil {
		conn.Close()
		return fmt.Errorf("客户端创建失败: %v", err)
	}
	defer client.Close()
	if err = client.Auth(auth); err != nil {
		return fmt.Errorf("认证失败: %v", err)
	}
	if err = client.Mail(username); err != nil {
		return fmt.Errorf("发件人失败: %v", err)
	}
	if err = client.Rcpt(to); err != nil {
		return fmt.Errorf("收件人失败: %v", err)
	}
	w, err := client.Data()
	if err != nil {
		return fmt.Errorf("数据流失败: %v", err)
	}
	if _, err = w.Write(msg); err != nil {
		w.Close()
		return fmt.Errorf("写入失败: %v", err)
	}
	if err = w.Close(); err != nil {
		return fmt.Errorf("服务器拒绝: %v", err)
	}
	client.Quit()
	return nil
}
