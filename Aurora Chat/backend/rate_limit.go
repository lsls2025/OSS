package main

import (
	"fmt"
	"net"
	"net/http"
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

// getClientIP 从 HTTP 请求中提取客户端真实 IP
func getClientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		return strings.TrimSpace(parts[0])
	}
	if xri := r.Header.Get("X-Real-IP"); xri != "" {
		return strings.TrimSpace(xri)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
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
