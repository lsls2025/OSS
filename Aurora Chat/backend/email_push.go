package main

import (
	"fmt"
	"log"
	"strings"
	"sync"
)

// ==================== 邮箱推送（消息推送）SMTP 账号池 ====================
//
// 说明：
//   - 本文件仅存放"邮箱推送"功能的发件账号，与 email.go 里的验证码账号相互独立。
//   - 账号/授权码只写在后端，禁止下发到前端（App 侧不得携带任何 SMTP 凭据）。
//   - 授权码为敏感凭据：请勿在日志、接口响应中打印或返回完整授权码。
//   - 日后新增账号：只需在 emailPushAccounts 切片里追加一行即可，随机轮换会自动包含。

// emailPushAccount 邮箱推送使用的发件账号
type emailPushAccount struct {
	Email    string // 发件邮箱
	AuthCode string // SMTP 授权码
	Remark   string // 备注（可选，如用途/失效标记）
}

// emailPushAccounts 邮箱推送发件账号池（随机轮换，避免单账号被 QQ 邮箱限流）
var emailPushAccounts = []emailPushAccount{
	{Email: "smtp_main@example.com", AuthCode: "YOUR_SMTP_PASSWORD", Remark: "官方主推"},
	{Email: "smtp_account_2@example.com", AuthCode: "YOUR_SMTP_PASSWORD_2"},
	{Email: "smtp_account_3@example.com", AuthCode: "YOUR_SMTP_PASSWORD_3"},
	{Email: "smtp_account_4@example.com", AuthCode: "YOUR_SMTP_PASSWORD_4"},
	{Email: "smtp_account_5@example.com", AuthCode: "YOUR_SMTP_PASSWORD_5"},
}

// emailPushAccountsLen 返回账号池数量（供后续轮换逻辑判断是否可用）
func emailPushAccountsLen() int {
	return len(emailPushAccounts)
}

// ==================== 邮箱推送 · 验证码邮件样式 ====================
//
// 验证码邮件直接复用 email.go 中的 buildVerificationEmailHTML（旧样式），
// 不再使用单独的新样式模板。

// ==================== 邮箱推送 · 发送调度（轮流 + 串行队列） ====================
//
// 目标：
//   - 5 个发件账号轮流使用（round-robin），避免单一账号高频发信被 QQ 邮箱限流；
//   - 发送走串行队列：同一时刻只处理一封，突发请求按顺序排队、逐个处理，
//     不会一次性并发发送一大堆触发风控。

var (
	// 轮询计数器：每次发送后 +1，取模得到下一个账号
	pushSendMu     sync.Mutex
	pushRoundRobin int

	// 被判定为失效/认证失败的账号（本次进程内临时拉黑，跳过轮换）
	pushBadMu     sync.Mutex
	pushBadEmails = make(map[string]bool)

	// 串行发送队列：容量 64，正常情况下不会打满
	pushQueue = make(chan pushMailJob, 64)

	// 只启动一个串行发送协程
	pushSenderOnce sync.Once
)

// pushMailJob 一封待发送邮件
type pushMailJob struct {
	to, subject, html string
}

// nextPushAccount 轮流返回下一个未被拉黑的发件账号（round-robin）
func nextPushAccount() emailPushAccount {
	pushSendMu.Lock()
	defer pushSendMu.Unlock()
	if len(emailPushAccounts) == 0 {
		return emailPushAccount{}
	}
	// 从当前位置往后找第一个未被拉黑的账号，避免死循环最多试完整池
	for i := 0; i < len(emailPushAccounts); i++ {
		acct := emailPushAccounts[pushRoundRobin%len(emailPushAccounts)]
		pushRoundRobin++
		if !isPushBad(acct.Email) {
			return acct
		}
	}
	return emailPushAccount{}
}

// isPushBad 判断账号是否被临时拉黑
func isPushBad(email string) bool {
	pushBadMu.Lock()
	defer pushBadMu.Unlock()
	return pushBadEmails[email]
}

// markPushBad 将账号临时拉黑（认证失败/授权码过期）
func markPushBad(email string) {
	pushBadMu.Lock()
	defer pushBadMu.Unlock()
	pushBadEmails[email] = true
	log.Printf("[邮箱推送] 账号 %s 认证失败，已临时拉黑，后续发送将跳过该账号", email)
}

// startPushSender 启动唯一的串行发送协程（幂等，可重复调用）
func startPushSender() {
	pushSenderOnce.Do(func() {
		go func() {
			for job := range pushQueue {
				sendPushMail(job)
			}
		}()
	})
}

// enqueuePushMail 将一封邮件加入串行队列（按顺序逐个发送）
func enqueuePushMail(to, subject, html string) {
	startPushSender()
	pushQueue <- pushMailJob{to, subject, html}
}

// sendPushMail 用下一个轮到的账号发送邮件（由串行队列的 worker 逐个调用）。
// 若某账号认证失败（授权码失效），拉黑该账号，跳过 30 秒冷却、立即用下一个账号重发。
func sendPushMail(job pushMailJob) {
	// 账号池目前均为 QQ 邮箱，固定走 QQ SMTP
	const (
		addr   = "smtp.qq.com:465"
		server = "smtp.qq.com"
	)
	// 最多试完整账号池，坏账号会被跳过，直到成功或全部尝试完毕
	for i := 0; i < len(emailPushAccounts); i++ {
		acct := nextPushAccount()
		if acct.Email == "" || acct.AuthCode == "" {
			log.Printf("[邮箱推送] 账号池为空或全部被拉黑，无法发送: [%s] -> %s", job.subject, job.to)
			return
		}
		// 首次尝试遵守 30 秒冷却；失败后重发 skipRate=true 立即发，不等冷却
		ok, msg := sendSMTPMailAs(job.to, job.subject, job.html, addr, server, acct.Email, acct.AuthCode, i > 0)
		if ok {
			return
		}
		if isAuthFailure(msg) {
			log.Printf("[邮箱推送] 账号 %s 认证失败，拉黑并换下一个账号重发: %s（收件: %s）", acct.Email, msg, job.to)
			markPushBad(acct.Email)
			continue
		}
		// 非认证类失败（网络、被拒等）不拉黑，但不再重试，避免无意义循环
		log.Printf("[邮箱推送] 发送失败: %s（账号: %s，收件: %s）", msg, acct.Email, job.to)
		return
	}
}

// isAuthFailure 判断失败信息是否属于"认证失败/授权码失效"，这类才值得拉黑换账号重发
func isAuthFailure(msg string) bool {
	return strings.Contains(msg, "认证失败") || strings.Contains(msg, "授权码")
}

// sendPushVerificationEmail 发送邮箱推送的验证码邮件（入队串行处理）
// 复用 email.go 中的旧样式验证码模板
func sendPushVerificationEmail(to, code string) {
	enqueuePushMail(to, "Aurora Chat - 邮箱验证码", buildVerificationEmailHTML(code))
}

// buildPushMessageEmailHTML 构建"消息推送"邮件正文（私聊/群聊@统一用此样式）。
// title 为标题行（如"有一条新消息"、"你在【XX群】被【某人】@"），
// bodyText 为正文：真实消息模式传消息原文，提示模板模式传对应提示语。
func buildPushMessageEmailHTML(title, bodyText string) string {
	return fmt.Sprintf(
		`<div style='font-size:16px;padding:20px;max-width:500px;margin:0 auto;'>`+
			`<div style='background:#f8f9fa;border-radius:12px;padding:24px;'>`+
			`<h2 style='margin:0 0 16px;color:#1a1a2e;'>%s</h2>`+
			`<div style='background:#fff;border-radius:8px;padding:16px;border:1px solid #eef0f4;'>`+
			`<p style='color:#333;font-size:15px;margin:0;word-break:break-word;line-height:1.6;'>%s</p></div>`+
			`</div><hr style='border:none;border-top:1px solid #eee;margin:16px 0;'>`+
			`<p style='color:#bbb;font-size:11px;text-align:center;'>拾光工作室</p>`+
			`</div>`, title, bodyText)
}

// sendOwnPushMail 用用户自己的邮箱+授权码直发（不走官方账号池）。
// 自己邮箱推送时使用：发件人=接收者填的 own_email，收件人=接收者 own_email。
func sendOwnPushMail(to, ownEmail, ownAuth, subject, html string) {
	if to == "" || ownEmail == "" || ownAuth == "" {
		log.Printf("[邮箱推送] 自己的邮箱配置不完整，跳过: to=%s", to)
		return
	}
	const (
		addr   = "smtp.qq.com:465"
		server = "smtp.qq.com"
	)
	ok, msg := sendSMTPMailAs(to, subject, html, addr, server, ownEmail, ownAuth, true)
	if !ok {
		log.Printf("[邮箱推送] 自己的邮箱发送失败: %s（发件: %s，收件: %s）", msg, ownEmail, to)
	}
}

// maybeEmailPushForMessage 新消息到达时，判断是否对单个接收者触发邮箱推送。
// 判定：接收者离线超过5分钟，且满足对应触发条件（私聊非免打扰 / 群聊被@），且接收者已配置推送。
func maybeEmailPushForMessage(receiverID, fromUserID int64, senderName, content string, isGroup bool, groupName string) {
	cfg, err := getUserPushConfig(receiverID)
	if err != nil || cfg == nil {
		log.Printf("[邮箱推送] 用户 %d 推送配置不可用: err=%v cfg=%v", receiverID, err, cfg == nil)
		return
	}
	// 总通知开关：用户关闭通知后，一律不进行邮箱推送
	if cfg.NotifyEnabled != 1 {
		log.Printf("[邮箱推送] 用户 %d 已关闭通知，跳过邮箱推送", receiverID)
		return
	}
	if isUserOnline(receiverID) {
		log.Printf("[邮箱推送] 用户 %d TCP 在线，消息经长连接送达，不触发邮箱推送", receiverID)
		return // 用户 TCP 在线：消息能通过长连接实时送达，无需邮箱推送
	}
	// 用户 TCP 离线：立即触发邮箱推送，不等待 5 分钟（用户要求：一离线就推送）
	receiver, err := findUserByID(receiverID)
	if err != nil || receiver == nil {
		log.Printf("[邮箱推送] 用户 %d 不存在，跳过", receiverID)
		return
	}

	var subject, title string
	if isGroup {
		// 群聊：仅当消息@了该接收者（或@全体）才推送
		mention := "@" + receiver.Username
		if !strings.Contains(content, mention) && !strings.Contains(content, "@全体成员") {
			log.Printf("[邮箱推送] 群 %s 消息未@用户 %d，跳过", groupName, receiverID)
			return
		}
		if groupName == "" {
			groupName = "群聊"
		}
		title = "你在【" + groupName + "】被 " + senderName + " @了"
		subject = "【Aurora Chat】你在群聊被@" + senderName
	} else {
		// 私聊：排除免打扰（接收者对该发送者开了免打扰则不推送）
		if dndContainsID(cfg.DndIDs, fromUserID) {
			log.Printf("[邮箱推送] 用户 %d 对发送者 %d 免打扰，跳过", receiverID, fromUserID)
			return
		}
		title = "有一条新消息"
		subject = "【Aurora Chat】有一条新消息"
	}

	bodyText := content
	if cfg.TemplateMode == 1 {
		bodyText = title // 提示模板模式：正文与标题一致，仅提示不泄露内容
	}
	html := buildPushMessageEmailHTML(title, bodyText)

	// 发件账号：官方走5账号轮流；自己的邮箱走用户自配账号
	if cfg.Method == 1 {
		if cfg.OwnEmail == "" || cfg.OwnAuth == "" {
			log.Printf("[邮箱推送] 用户 %d 选择自己邮箱但配置不完整，跳过", receiverID)
			return
		}
		log.Printf("[邮箱推送] 尝试用用户 %d 自己的邮箱 %s 推送", receiverID, cfg.OwnEmail)
		sendOwnPushMail(cfg.OwnEmail, cfg.OwnEmail, cfg.OwnAuth, subject, html)
		return
	}
	// 官方推送：收件人必须是真实绑定的邮箱，占位邮箱（QQ/手机注册的假邮箱）无法投递，直接跳过
	if receiver.Email == "" || strings.HasPrefix(receiver.Email, "qq_") || strings.HasPrefix(receiver.Email, "temp_") {
		log.Printf("[邮箱推送] 用户 %d 未绑定真实邮箱，官方推送跳过（当前邮箱: %s）", receiverID, receiver.Email)
		return
	}
	log.Printf("[邮箱推送] 用户 %d 离线，推送私聊消息 → %s", receiverID, receiver.Email)
	enqueuePushMail(receiver.Email, subject, html)
}
