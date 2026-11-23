package main

import (
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// logDedup 防刷日志去重：同 userID + 拦截类型在 suppressInterval 内只打一条
var (
	logDedupMu    sync.Mutex
	logDedupMap   = map[string]int64{} // key:"userID|type" → lastLogUnix
	dedupInterval = int64(10)          // 同类型拦截 10 秒内只打印一次
)

func logDedupPrintf(userID int64, logType string, format string, v ...interface{}) {
	key := fmt.Sprintf("%d|%s", userID, logType)
	now := time.Now().Unix()
	logDedupMu.Lock()
	last, exists := logDedupMap[key]
	if exists && now-last < dedupInterval {
		logDedupMu.Unlock()
		return // 10秒内同 userID+类型 已经打过日志，静默跳过
	}
	logDedupMap[key] = now
	logDedupMu.Unlock()
	log.Printf(format, v...)
}

// ==================== JWT 认证中间件 ====================

type contextKey string

const UserIDKey contextKey = "user_id"

// ==================== 设备类别（多端共存） ====================
// 账号级 token_version 是「全局版本」：改密、管理员踢人、账号异常等情况一次性抬高，
// 让该账号所有设备同时失效。device 版本（mobile / pc）只负责「同类设备新登录踢旧登录」，
// 让手机与桌面各自独立登录、互不顶号（与微信 / QQ 的多端行为一致）。
const (
	deviceMobile = "mobile"
	devicePC     = "pc"
	deviceAgent  = "agent"
)

// normalizeDevice 把客户端上报的设备标识归一为设备类别。
// 缺省一律按 mobile：老版本安卓端不带 device 字段，这样升级后行为不变。
func normalizeDevice(device string) string {
	switch strings.ToLower(strings.TrimSpace(device)) {
	case "pc", "desktop", "windows", "win", "win32", "win64", "mac", "macos", "linux":
		return devicePC
	case "agent", "bot":
		return deviceAgent
	}
	return deviceMobile
}

// deviceVersionColumn 设备类别 → users 表中对应的版本列名。
// 列名只在下面几个常量分支里产生，不接受外部输入，不存在注入面。
func deviceVersionColumn(device string) string {
	switch normalizeDevice(device) {
	case devicePC:
		return "token_version_pc"
	case deviceAgent:
		return "token_version_agent"
	}
	return "token_version_mobile"
}

// currentDeviceVersion 读取该设备类别当前的版本号。
func currentDeviceVersion(userID int64, device string) int64 {
	var v int64
	db.QueryRow("SELECT "+deviceVersionColumn(device)+" FROM users WHERE id = ?", userID).Scan(&v)
	return v
}

// bumpDeviceVersion 同类设备新登录：只抬高该类别版本号，其它设备不受影响。
func bumpDeviceVersion(userID int64, device string) int64 {
	col := deviceVersionColumn(device)
	db.Exec("UPDATE users SET "+col+" = "+col+" + 1 WHERE id = ?", userID)
	return currentDeviceVersion(userID, device)
}

// generateToken 生成「手机端」JWT（保留原签名，供注册/资料完善等内部场景使用）。
func generateToken(userID int64, email string, tokenVersion int64) (string, error) {
	return generateTokenFor(userID, email, tokenVersion, tokenVersion, deviceMobile)
}

// generateTokenFor 生成带设备类别的 JWT。
//   token_version —— 全局版本，用于改密/踢人时全端失效
//   deviceVersion —— 设备类别版本，用于同类设备互踢
func generateTokenFor(userID int64, email string, tokenVersion, deviceVersion int64, device string) (string, error) {
	claims := jwt.MapClaims{
		"user_id":       userID,
		"email":         email,
		"token_version": tokenVersion,
		"tv":            deviceVersion,
		"dev":           normalizeDevice(device),
		"exp":           time.Now().Add(30 * 24 * time.Hour).Unix(), // 30天过期
		"iat":           time.Now().Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(getJWTSecret()))
}

// tokenInfo 解析后的 token 载荷。
type tokenInfo struct {
	UserID        int64
	Email         string
	TokenVersion  int64  // 全局版本
	DeviceVersion int64  // 设备类别版本
	Device        string // mobile / pc
}

// parseToken 解析并校验签名，返回完整载荷（含设备类别）。
func parseToken(tokenStr string) (*tokenInfo, error) {
	token, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, jwt.ErrSignatureInvalid
		}
		return []byte(getJWTSecret()), nil
	})
	if err != nil {
		return nil, err
	}
	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok || !token.Valid {
		return nil, jwt.ErrSignatureInvalid
	}
	info := &tokenInfo{}
	if v, ok := claims["user_id"].(float64); ok {
		info.UserID = int64(v)
	}
	info.Email, _ = claims["email"].(string)
	if v, ok := claims["token_version"].(float64); ok {
		info.TokenVersion = int64(v)
	}
	// 老 token 没有 tv / dev：按手机端处理，且设备版本回落到全局版本，
	// 这样升级瞬间仍在使用的旧 token 不会被误判失效。
	info.DeviceVersion = info.TokenVersion
	if v, ok := claims["tv"].(float64); ok {
		info.DeviceVersion = int64(v)
	}
	info.Device = deviceMobile
	if v, ok := claims["dev"].(string); ok {
		info.Device = normalizeDevice(v)
	}
	return info, nil
}

// validateToken 验证 JWT token 并返回 user_id, email, tokenVersion（兼容旧调用点）
func validateToken(tokenStr string) (int64, string, int64, error) {
	info, err := parseToken(tokenStr)
	if err != nil {
		return 0, "", 0, err
	}
	return info.UserID, info.Email, info.TokenVersion, nil
}

// extractToken 从请求头提取 Bearer token
func extractToken(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	if strings.HasPrefix(auth, "Bearer ") {
		return strings.TrimPrefix(auth, "Bearer ")
	}
	return ""
}

// authMiddleware 验证用户是否已登录且账号正常
func authMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tokenStr := extractToken(r)
		if tokenStr == "" {
			writeJSON(w, 401, "未登录，请先登录", nil)
			return
		}
		tk, err := parseToken(tokenStr)
		if err != nil {
			writeJSON(w, 401, "登录状态异常，请重新登录", nil)
			return
		}
		userID, email, tokenVersion := tk.UserID, tk.Email, tk.TokenVersion

		// 数据库验证：用户必须存在且未被注销
		// 放行 /api/user/* 路径（getUserInfo/check-exists/ban-status/mute-status），
		// 让客户端能检测到自己的"注销用户"状态从而主动执行 forceLogout
		isUserAPI := strings.HasPrefix(r.URL.Path, "/api/user/")
		isBanStatusAPI := strings.HasPrefix(r.URL.Path, "/api/user/ban-status")
		isMuteStatusAPI := strings.HasPrefix(r.URL.Path, "/api/user/mute-status")

		// 带重试的查询，避免 SQLITE_BUSY 误判
		var username string
		for i := 0; i < 3; i++ {
			err = db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
			if err == nil {
				break
			}
			if i < 2 && (strings.Contains(err.Error(), "busy") || strings.Contains(err.Error(), "locked")) {
				time.Sleep(time.Duration(i*50+50) * time.Millisecond)
				continue
			}
		}
		// 防误判：仅当 username 明确为"注销用户"时才视为已注销，
		// 数据库查询本身失败（如不存在该用户）也算删除；
		// username 为空字符串不视为删除（可能是旧数据或字段未正确填充），只打警告日志
		queryFailed := err != nil
		isExplicitDeleted := username == "注销用户"
		if queryFailed || isExplicitDeleted {
			if !isUserAPI {
				logDedupPrintf(userID, "deleted", "[中间件] 拦截异常请求: userID=%d, err=%v, username=%q", userID, err, username)
				writeJSON(w, 401, "账号异常，请重新登录", nil)
				return
			}
		} else if username == "" {
			// 空 username 不拦截，只打日志记录，防止误判
			logDedupPrintf(userID, "empty-username", "[中间件] 警告: userID=%d 的用户名为空，已放行", userID)
		}

		// 封禁检查（/api/user/ban-status、/api/user/mute-status、/api/user/ban-notify 不受封禁影响）
		isBypassAPI := isBanStatusAPI || isMuteStatusAPI || strings.HasPrefix(r.URL.Path, "/api/user/ban-notify")
		if !queryFailed && !isExplicitDeleted && !isBypassAPI {
			ban, _ := getBanStatus(userID)
			if ban != nil {
				logDedupPrintf(userID, "banned", "[中间件] 拦截被封禁用户请求: userID=%d, path=%s", userID, r.URL.Path)
				writeJSON(w, 403, "账号已被封禁", nil)
				return
			}
		}

	// 禁言检查（/api/user/mute-status 不受禁言影响）
	// 禁言类型：1=社区禁言（社区发帖/评论） 2=对话禁言（聊天消息） 3=全部禁言 4=短视频禁言（发布短视频/短视频评论）
	// 只拦截"写"操作；读取（评论列表/feed 等）一律放行，被禁言用户仍可正常浏览。
	if !queryFailed && !isExplicitDeleted && !isMuteStatusAPI {
		isCommunityWriteAPI := r.URL.Path == "/api/community/post/create" ||
			r.URL.Path == "/api/community/comment/add" ||
			r.URL.Path == "/api/community/comment/like" ||
			r.URL.Path == "/api/community/comment/delete"
		isMessageSendAPI := r.URL.Path == "/api/messages/send" || r.URL.Path == "/api/messages/poke"
		// 注意：/api/shortvideo/comments（读取评论列表）不能被 /api/shortvideo/comment 前缀误伤，
		// 所以添加评论用全等匹配、点赞/删除用带斜杠的前缀匹配。
		isShortVideoWriteAPI := r.URL.Path == "/api/shortvideo/upload" ||
			r.URL.Path == "/api/shortvideo/comment" ||
			strings.HasPrefix(r.URL.Path, "/api/shortvideo/comment/")
		if isCommunityWriteAPI || isMessageSendAPI || isShortVideoWriteAPI {
			mute, _ := getMuteStatus(userID)
			if mute != nil {
				if isCommunityWriteAPI && (mute.MuteType == 1 || mute.MuteType == 3) {
					logDedupPrintf(userID, "muted-community", "[中间件] 拦截被社区禁言用户: userID=%d, path=%s", userID, r.URL.Path)
					writeJSON(w, 403, "你已被社区禁言", nil)
					return
				}
				if isMessageSendAPI && (mute.MuteType == 2 || mute.MuteType == 3) {
					logDedupPrintf(userID, "muted-chat", "[中间件] 拦截被对话禁言用户: userID=%d", userID)
					writeJSON(w, 403, "你已被禁言", nil)
					return
				}
				if isShortVideoWriteAPI && (mute.MuteType == 4 || mute.MuteType == 3) {
					logDedupPrintf(userID, "muted-shortvideo", "[中间件] 拦截被短视频禁言用户: userID=%d, path=%s", userID, r.URL.Path)
					writeJSON(w, 403, "你已被短视频禁言", nil)
					return
				}
			}
		}
	}

		// 登录态校验分两级：
		//   1) 全局版本 token_version —— 改密 / 管理员踢人 / 账号异常时统一抬高，全端失效；
		//   2) 设备类别版本 —— 只有「同类设备」新登录才会抬高，所以手机与桌面互不顶号。
		var dbTokenVersion, dbDeviceVersion, kickedAt int64
		deviceCol := deviceVersionColumn(tk.Device)
		err = db.QueryRow("SELECT token_version, "+deviceCol+", kicked_at FROM users WHERE id = ?", userID).
			Scan(&dbTokenVersion, &dbDeviceVersion, &kickedAt)
		if err == nil && (tokenVersion < dbTokenVersion || tk.DeviceVersion < dbDeviceVersion) {
			logDedupPrintf(userID, "old-token", "[中间件] 拦截旧 token: userID=%d, dev=%s, tv=%d/%d, deviceVer=%d/%d, kickedAt=%d",
				userID, tk.Device, tokenVersion, dbTokenVersion, tk.DeviceVersion, dbDeviceVersion, kickedAt)
			if kickedAt > 0 {
				writeJSON(w, 401, "账号已被管理员踢出，请重新登录", nil)
			} else {
				writeJSON(w, 401, "账号已在其他设备登录", nil)
			}
			return
		}

		// 将用户信息注入请求上下文
		r.Header.Set("X-User-ID", formatInt64(userID))
		r.Header.Set("X-User-Email", email)
		next(w, r)
	}
}

func formatInt64(n int64) string {
	return fmt.Sprintf("%d", n)
}

// loggingMiddleware 记录所有 HTTP 请求
func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("[%s] %s %s (%s)",
			r.Method, r.URL.Path, r.RemoteAddr, time.Since(start))
	})
}

// recoverMiddleware 捕获 panic，防止服务器崩溃
func recoverMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("[PANIC] %v", rec)
				writeJSON(w, 500, "服务器内部错误", nil)
			}
		}()
		next.ServeHTTP(w, r)
	})
}
