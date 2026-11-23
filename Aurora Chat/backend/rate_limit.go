package main

import (
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ============================================================
// IP 注册频率限制
// 规则：单 IP 12 小时内最多 1 次注册
// 使用内存 sync.Map + 定时清理
// ============================================================

type ipRecord struct {
	times []time.Time
}

var (
	registerLimiter sync.Map
	cleanupOnce     sync.Once
)

func startRegisterLimiterCleanup() {
	cleanupOnce.Do(func() {
		go func() {
			ticker := time.NewTicker(1 * time.Minute)
			for range ticker.C {
				now := time.Now()
				registerLimiter.Range(func(key, value interface{}) bool {
					rec := value.(*ipRecord)
					var valid []time.Time
					for _, t := range rec.times {
						if now.Sub(t) < 12*time.Hour {
							valid = append(valid, t)
						}
					}
					if len(valid) == 0 {
						registerLimiter.Delete(key)
					} else {
						rec.times = valid
					}
					return true
				})
			}
		}()
	})
}

func checkRegisterRateLimit(ip string) (allowed bool, waitSeconds int) {
	startRegisterLimiterCleanup()
	now := time.Now()
	val, _ := registerLimiter.LoadOrStore(ip, &ipRecord{})
	rec := val.(*ipRecord)

	var recent []time.Time
	for _, t := range rec.times {
		if now.Sub(t) <= 12*time.Hour {
			recent = append(recent, t)
		}
	}

	// 12 小时内 ≤0 次则允许（即 12 小时内只能注册 1 次）
	if len(recent) < 1 {
		recent = append(recent, now)
		rec.times = recent
		return true, 0
	}

	oldest := recent[0]
	wait := int((12*time.Hour - now.Sub(oldest)) / time.Second)
	if wait < 0 {
		wait = 0
	}
	return false, wait
}

// ============================================================
// 登录 / 重置密码 / QQ 登录 频率限制 — 防爆破、撞库、批量尝试
// 规则：单 IP 与单账号各 1 分钟内最多 8 次（双维度任一超限即拦截）。
// IP 维度防单点横扫全站；账号维度防攻击者轮换 IP 集中爆破同一个账号。
// ============================================================

var (
	loginRateMu      sync.Mutex
	loginIPTimes     = map[string][]time.Time{}
	loginAcctTimes   = map[string][]time.Time{}
	loginRateWindow  = 1 * time.Minute
	// 两个维度阈值分开：账号维度才是防爆破的关键（8 次/分足够挡撞库）；
	// IP 维度只是防单点横扫，国内运营商 CGNAT 下大量真实用户共用一个出口 IP，
	// 沿用 8 次/分会把正常用户一起拦掉，所以放宽到 30 次/分。
	loginRateMaxAcct = 8
	loginRateMaxIP   = 30
	loginCleanupOnce sync.Once
)

// loginRateTrim 仅保留窗口内的时间戳（复用底层数组），返回裁剪后的切片
func loginRateTrim(m *map[string][]time.Time, key string, now time.Time) []time.Time {
	ts := (*m)[key]
	kept := ts[:0]
	for _, t := range ts {
		if now.Sub(t) <= loginRateWindow {
			kept = append(kept, t)
		}
	}
	(*m)[key] = kept
	return kept
}

// checkLoginRateLimit 同时按 IP 与账号计数。返回 (是否允许, 还需等待的秒数)
func checkLoginRateLimit(ip, account string) (bool, int) {
	startLoginRateCleanup()
	loginRateMu.Lock()
	defer loginRateMu.Unlock()
	now := time.Now()
	for _, kv := range []struct {
		m   *map[string][]time.Time
		k   string
		max int
	}{
		{&loginIPTimes, ip, loginRateMaxIP},
		{&loginAcctTimes, strings.ToLower(account), loginRateMaxAcct},
	} {
		kept := loginRateTrim(kv.m, kv.k, now)
		if len(kept) >= kv.max {
			wait := int((loginRateWindow - now.Sub(kept[0])) / time.Second)
			if wait < 1 {
				wait = 1
			}
			return false, wait
		}
		(*kv.m)[kv.k] = append(kept, now)
	}
	return true, 0
}

func startLoginRateCleanup() {
	loginCleanupOnce.Do(func() {
		go func() {
			ticker := time.NewTicker(1 * time.Minute)
			for range ticker.C {
				loginRateMu.Lock()
				now := time.Now()
				for _, m := range []map[string][]time.Time{loginIPTimes, loginAcctTimes} {
					for k, ts := range m {
						kept := ts[:0]
						for _, t := range ts {
							if now.Sub(t) <= loginRateWindow {
								kept = append(kept, t)
							}
						}
						if len(kept) == 0 {
							delete(m, k)
						} else {
							m[k] = kept
						}
					}
				}
				loginRateMu.Unlock()
			}
		}()
	})
}

// ============================================================
// 未验证用户官方群消息频率限制 — 1 条/60 秒
// ============================================================

var unverifiedMsgLimiter sync.Map // key: userID(int64), value: time.Time

// checkUnverifiedMsgRate 检查未验证用户是否可发送消息
// 官方群（convID=-1001）限速 1 条/60 秒
func checkUnverifiedMsgRate(userID int64) (allowed bool, waitSeconds int) {
	now := time.Now()
	val, _ := unverifiedMsgLimiter.LoadOrStore(userID, time.Time{})
	lastSent := val.(time.Time)
	if lastSent.IsZero() || now.Sub(lastSent) >= 60*time.Second {
		unverifiedMsgLimiter.Store(userID, now)
		return true, 0
	}
	wait := int((60*time.Second - now.Sub(lastSent)) / time.Second)
	return false, wait
}

// ============================================================
// 群内发言频率限制 — 每位成员独立计数
// ============================================================

// checkGroupMsgRate 校验某成员在指定群的发言频率。
// limit: N>0 = 每分钟最多 N 条（0=全员禁言由调用方先行拦截）。
// 计数维度是「该群 + 该发送者」最近 60 秒的消息条数，绝不是全群总量。
func checkGroupMsgRate(groupID, userID int64, limit int) (allowed bool, waitSeconds int) {
	if limit <= 0 {
		return false, 0
	}
	convID := -(1000 + groupID)
	windowStart := time.Now().Unix() - 60
	var count int
	if err := db.QueryRow(
		"SELECT COUNT(*) FROM messages WHERE from_user_id = ? AND to_user_id = ? AND created_at > ?",
		userID, convID, windowStart,
	).Scan(&count); err != nil {
		// 统计失败时放行，绝不因为 DB 抖动误拦正常发言
		return true, 0
	}
	if count < limit {
		return true, 0
	}
	var oldest int64
	if err := db.QueryRow(
		"SELECT MIN(created_at) FROM messages WHERE from_user_id = ? AND to_user_id = ? AND created_at > ?",
		userID, convID, windowStart,
	).Scan(&oldest); err != nil || oldest == 0 {
		return false, 60
	}
	wait := int(oldest + 60 - time.Now().Unix())
	if wait < 1 {
		wait = 1
	}
	return false, wait
}

// isDigitsOnly 判断字符串是否全部为数字（用于识别 QQ 号登录）
func isDigitsOnly(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// trustedProxyNets 可信代理网段。nginx/宝塔反代通常与本服务同机（127.0.0.1）
// 或同内网，默认放行 loopback + RFC1918 + IPv6 唯一本地地址；
// 可用 TRUSTED_PROXIES 环境变量追加（逗号分隔，支持单 IP 与 CIDR）。
var trustedProxyNets = func() []*net.IPNet {
	cidrs := []string{"127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"}
	nets := make([]*net.IPNet, 0, len(cidrs))
	for _, c := range cidrs {
		if _, n, err := net.ParseCIDR(c); err == nil {
			nets = append(nets, n)
		}
	}
	if env := strings.TrimSpace(os.Getenv("TRUSTED_PROXIES")); env != "" {
		for _, part := range strings.Split(env, ",") {
			p := strings.TrimSpace(part)
			if p == "" {
				continue
			}
			if _, n, err := net.ParseCIDR(p); err == nil {
				nets = append(nets, n)
			} else if ip := net.ParseIP(p); ip != nil {
				bits := 32
				if ip.To4() == nil {
					bits = 128
				}
				nets = append(nets, &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)})
			} else {
				log.Printf("[安全] TRUSTED_PROXIES 含无法解析的条目(已忽略): %s", p)
			}
		}
	}
	return nets
}()

// isTrustedProxy 判断 IP 是否为可信代理来源。只有可信代理追加的
// X-Forwarded-For / X-Real-IP 才被采纳。
func isTrustedProxy(ip string) bool {
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return false
	}
	for _, n := range trustedProxyNets {
		if n.Contains(parsed) {
			return true
		}
	}
	return false
}

// getClientIP 从 HTTP 请求中提取客户端真实 IP。
// 安全：X-Forwarded-For / X-Real-IP 仅在直连对端是可信代理（同机 nginx / 内网反代，
// 或 TRUSTED_PROXIES 显式配置）时才采纳。公网直连下这两个头完全由客户端自控，
// 无条件信任等于把 IP 封禁 / 注册频率限制 / 登录风控全部降到零，
// 且日志里记录的"客户端 IP"可被任意伪造。
func getClientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if isTrustedProxy(host) {
		if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			// 从右往左取第一个非可信代理地址。
			// 最左值完全由客户端自控（nginx 的 $proxy_add_x_forwarded_for 会把客户端自带的
			// XFF 原样保留在最左侧），取它会直接废掉注册/登录限流与 IP 封禁；
			// 最右一跳由紧邻的反代写入，才是真实的客户端地址。
			parts := strings.Split(xff, ",")
			for i := len(parts) - 1; i >= 0; i-- {
				ip := strings.TrimSpace(parts[i])
				if ip == "" || isTrustedProxy(ip) {
					continue
				}
				return ip
			}
			// 全链路都是可信代理（同机多级反代）：退回最右一跳
			for i := len(parts) - 1; i >= 0; i-- {
				if ip := strings.TrimSpace(parts[i]); ip != "" {
					return ip
				}
			}
		}
		if xri := strings.TrimSpace(r.Header.Get("X-Real-IP")); xri != "" {
			return xri
		}
	}
	return host
}

// ============================================================
// 评论发布频率限制 — 防灌水
// 规则：单用户 2 秒内最多 1 条，且 1 分钟内最多 20 条
// ============================================================

type commentWindow struct {
	times []time.Time
}

var commentLimiter sync.Map

func checkCommentRateLimit(userID int64) (allowed bool, waitSeconds int) {
	now := time.Now()
	val, _ := commentLimiter.LoadOrStore(userID, &commentWindow{})
	w := val.(*commentWindow)

	// 清理 1 分钟外的记录
	var recent []time.Time
	for _, t := range w.times {
		if now.Sub(t) <= time.Minute {
			recent = append(recent, t)
		}
	}
	w.times = recent

	if len(recent) >= 20 {
		wait := int((time.Minute - now.Sub(recent[0])) / time.Second)
		if wait < 0 {
			wait = 0
		}
		return false, wait
	}
	if len(recent) > 0 {
		last := recent[len(recent)-1]
		if d := now.Sub(last); d < 2*time.Second {
			return false, int((2*time.Second - d) / time.Second)
		}
	}
	w.times = append(w.times, now)
	return true, 0
}

// ============================================================
// 订单提交频率限制 — 单个用户 10 分钟内最多提交 1 笔订单
// 会员开通订单与 Token 充值订单共用此限制
// ============================================================

var orderSubmitLimiter sync.Map // key: userID(int64), value: time.Time

// checkOrderSubmitRateLimit 判断指定用户是否可以提交新订单
// 返回 (是否允许, 还需等待的秒数)
func checkOrderSubmitRateLimit(userID int64) (allowed bool, waitSeconds int) {
	now := time.Now()
	val, _ := orderSubmitLimiter.LoadOrStore(userID, time.Time{})
	last := val.(time.Time)
	if last.IsZero() || now.Sub(last) >= 10*time.Minute {
		orderSubmitLimiter.Store(userID, now)
		return true, 0
	}
	wait := int((10*time.Minute - now.Sub(last)) / time.Second)
	if wait < 0 {
		wait = 0
	}
	return false, wait
}

// ============================================================
// 二维码解析频率限制 — 防批量扫描枚举用户
// 规则：单用户 2 秒内最多 1 次
// ============================================================

var qrResolveLimiter sync.Map // key: userID(int64), value: time.Time

// checkQrResolveRateLimit 判断指定用户是否可解析二维码
func checkQrResolveRateLimit(userID int64) (allowed bool, waitSeconds int) {
	now := time.Now()
	val, _ := qrResolveLimiter.LoadOrStore(userID, time.Time{})
	last := val.(time.Time)
	if last.IsZero() || now.Sub(last) >= 2*time.Second {
		qrResolveLimiter.Store(userID, now)
		return true, 0
	}
	wait := int((2*time.Second - now.Sub(last)) / time.Second)
	if wait < 0 {
		wait = 0
	}
	return false, wait
}

// ============================================================
// 落地页公开信息接口频率限制 — 防批量枚举用户
// 规则：单 IP 3 秒内最多 1 次
// ============================================================

var qrPublicLimiter sync.Map // key: clientIP(string), value: time.Time

func checkQrPublicRateLimit(ip string) (allowed bool, waitSeconds int) {
	now := time.Now()
	val, _ := qrPublicLimiter.LoadOrStore(ip, time.Time{})
	last := val.(time.Time)
	if last.IsZero() || now.Sub(last) >= 3*time.Second {
		qrPublicLimiter.Store(ip, now)
		return true, 0
	}
	wait := int((3*time.Second - now.Sub(last)) / time.Second)
	if wait < 0 {
		wait = 0
	}
	return false, wait
}

// GET /api/qr/user?uid=<id> — 落地页公开信息接口（无需登录）
// 仅返回被扫码者的公开信息（昵称/签名/头像），用于 q.html 页面展示。
// 加好友仍然只能由已登录的 APP 内扫码调用 /api/user/qrcode/resolve 触发。
func handlePublicQrUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	uidStr := r.URL.Query().Get("uid")
	uid, err := strconv.ParseInt(uidStr, 10, 64)
	if err != nil || uid <= 0 {
		writeJSON(w, 400, "无效的 uid 参数", nil)
		return
	}
	if ok, wait := checkQrPublicRateLimit(getClientIP(r)); !ok {
		writeJSON(w, 429, fmt.Sprintf("操作过于频繁，请 %d 秒后再试", wait), nil)
		return
	}
	user, err := findUserByID(uid)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if user == nil || isAccountDeleted(user) {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"id":         user.ID,
		"username":   user.Username,
		"signature":  user.Signature,
		"avatar_url": fmt.Sprintf("/api/avatar/%d", user.ID),
	})
}

// ============================================================
// 消息发送频率限制 — 防脚本裸调接口批量刷消息
// 规则：单用户 10 秒内最多 150 条，硬截断（不排队）。
// 说明：正常人一秒点不了那么多（150/10s ≈ 15 条/秒），脚本刷屏会被立即拦截。
// 如需更严/更松，直接调下面的常量即可。
// ============================================================

const (
	msgSendWindow   = 10 * time.Second
	msgSendMaxInWin = 150
	msgSendMinGap   = 0 // 0=不限制瞬时间隔
)

type msgSendWindowRec struct {
	times []time.Time
}

var msgSendLimiter sync.Map // key: userID(int64), value: *msgSendWindowRec

// checkMsgSendRateLimit 判断指定用户是否可发送下一条消息。
// 返回 (是否允许, 还需等待的秒数)。
func checkMsgSendRateLimit(userID int64) (allowed bool, waitSeconds int) {
	now := time.Now()
	val, _ := msgSendLimiter.LoadOrStore(userID, &msgSendWindowRec{})
	w := val.(*msgSendWindowRec)

	// 仅保留窗口内的记录（每次调用顺带裁剪，避免数组无限增长）
	kept := w.times[:0]
	for _, t := range w.times {
		if now.Sub(t) <= msgSendWindow {
			kept = append(kept, t)
		}
	}
	w.times = kept

	// ① 瞬时最小间隔（仅在 msgSendMinGap>0 时生效）
	if msgSendMinGap > 0 && len(w.times) > 0 {
		if d := now.Sub(w.times[len(w.times)-1]); d < msgSendMinGap {
			return false, 1
		}
	}
	// ② 滑动窗口上限：窗口内最多 msgSendMaxInWin 条
	if len(w.times) >= msgSendMaxInWin {
		wait := int((msgSendWindow - now.Sub(w.times[0])) / time.Second)
		if wait < 1 {
			wait = 1
		}
		return false, wait
	}

	w.times = append(w.times, now)
	return true, 0
}

// ============================================================
// 批次7 辅助函数：对端 IP 提取（B9 防伪造 XFF 绕过）+ 回环校验（B6）
// ============================================================

// getPeerIP 直接从 TCP 对端地址取 IP，不信任 X-Forwarded-For / X-Real-IP，
// 防止攻击者伪造头绕过验证码发送的 IP 频率限制。
func getPeerIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// isLoopbackIP 判断 IP 是否为回环地址（127.0.0.1 / ::1 / localhost）
func isLoopbackIP(ip string) bool {
	ip = strings.TrimSpace(ip)
	if ip == "" {
		return false
	}
	if ip == "localhost" {
		return true
	}
	if parsed := net.ParseIP(ip); parsed != nil {
		return parsed.IsLoopback()
	}
	return false
}

// checkLoopbackOnly 校验请求是否来自本机回环地址；非回环返回 false。
// 必须用 getClientIP 而不是 TCP 对端：一旦前面挂了 nginx 反代，所有请求的对端都是
// 127.0.0.1，用对端判断等于把这个"仅限本机"的闸门对全世界打开。
// getClientIP 在直连时等价于对端地址，在反代后返回注入前的真实客户端 IP。
func checkLoopbackOnly(r *http.Request) bool {
	return isLoopbackIP(getClientIP(r))
}

// ============================================================
// 通用轻量限流器 — 低频接口的防刷兜底
// 用于：资料修改、账号存在性探测、扫码登录轮询等原本「零限流」的接口。
// 内存态滑动窗口，与其余限流器一致（进程重启清零，单实例部署可接受）。
// ============================================================

var (
	genericLimiterMu     sync.Mutex
	genericLimiterHits   = map[string][]time.Time{}
	genericCleanupOnce   sync.Once
	genericLimiterMaxKey = 20000 // 键数量兜底，防止恶意刷出无限 key 撑爆内存
)

func startGenericLimiterCleanup() {
	genericCleanupOnce.Do(func() {
		go func() {
			ticker := time.NewTicker(2 * time.Minute)
			for range ticker.C {
				genericLimiterMu.Lock()
				now := time.Now()
				for k, ts := range genericLimiterHits {
					var valid []time.Time
					for _, t := range ts {
						if now.Sub(t) <= 10*time.Minute {
							valid = append(valid, t)
						}
					}
					if len(valid) == 0 {
						delete(genericLimiterHits, k)
					} else {
						genericLimiterHits[k] = valid
					}
				}
				genericLimiterMu.Unlock()
			}
		}()
	})
}

// checkSlidingLimit 单 key 在 window 内最多 max 次。返回 (是否允许, 需等待秒数)。
func checkSlidingLimit(key string, window time.Duration, max int) (bool, int) {
	startGenericLimiterCleanup()
	genericLimiterMu.Lock()
	defer genericLimiterMu.Unlock()
	if len(genericLimiterHits) > genericLimiterMaxKey {
		genericLimiterHits = map[string][]time.Time{}
	}
	now := time.Now()
	ts := genericLimiterHits[key]
	kept := ts[:0]
	for _, t := range ts {
		if now.Sub(t) <= window {
			kept = append(kept, t)
		}
	}
	if len(kept) >= max {
		wait := int((window - now.Sub(kept[0])) / time.Second)
		if wait < 1 {
			wait = 1
		}
		genericLimiterHits[key] = kept
		return false, wait
	}
	genericLimiterHits[key] = append(kept, now)
	return true, 0
}

// checkIntervalLimit 单 key 最短间隔（秒级冷却）。
func checkIntervalLimit(key string, minGap time.Duration) (bool, int) {
	startGenericLimiterCleanup()
	genericLimiterMu.Lock()
	defer genericLimiterMu.Unlock()
	if len(genericLimiterHits) > genericLimiterMaxKey {
		genericLimiterHits = map[string][]time.Time{}
	}
	now := time.Now()
	ts := genericLimiterHits[key]
	if len(ts) > 0 {
		if d := now.Sub(ts[len(ts)-1]); d < minGap {
			wait := int((minGap - d) / time.Second)
			if wait < 1 {
				wait = 1
			}
			return false, wait
		}
	}
	genericLimiterHits[key] = append(ts[:0], now)
	return true, 0
}

// ============================================================
// 验证码错误次数限制 — 6 位数字验证码防在线爆破
// 原来 verifyCode 只做字符串匹配，不对失败次数计数，完全靠外层 IP 限流兜底；
// 这里按邮箱累计失败次数，达到上限直接作废当前验证码。
// ============================================================

const codeFailMax = 10

var (
	codeFailMu   sync.Mutex
	codeFailHits = map[string]int{}
)

// recordCodeFailure 记录一次验证码校验失败；累计到上限则作废该邮箱的验证码并要求重新获取。
func recordCodeFailure(email string) {
	codeFailMu.Lock()
	if len(codeFailHits) > genericLimiterMaxKey {
		codeFailHits = map[string]int{}
	}
	codeFailHits[email]++
	n := codeFailHits[email]
	codeFailMu.Unlock()
	if n >= codeFailMax {
		deleteVerificationCode(email)
		codeFailMu.Lock()
		delete(codeFailHits, email)
		codeFailMu.Unlock()
		log.Printf("[验证码] 失败次数达上限(%d)，已作废该邮箱验证码: %s", codeFailMax, email)
	}
}

// clearCodeFailure 验证码校验成功后清零失败计数
func clearCodeFailure(email string) {
	codeFailMu.Lock()
	delete(codeFailHits, email)
	codeFailMu.Unlock()
}
