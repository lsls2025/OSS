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

// generateToken 为指定用户生成 JWT token（30天有效期）
func generateToken(userID int64, email string, tokenVersion int64) (string, error) {
	claims := jwt.MapClaims{
		"user_id":       userID,
		"email":         email,
		"token_version": tokenVersion,
		"exp":           time.Now().Add(30 * 24 * time.Hour).Unix(), // 30天过期
		"iat":           time.Now().Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(getJWTSecret()))
}

// validateToken 验证 JWT token 并返回 user_id, email, tokenVersion
func validateToken(tokenStr string) (int64, string, int64, error) {
	token, err := jwt.Parse(tokenStr, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, jwt.ErrSignatureInvalid
		}
		return []byte(getJWTSecret()), nil
	})
	if err != nil {
		return 0, "", 0, err
	}
	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok || !token.Valid {
		return 0, "", 0, jwt.ErrSignatureInvalid
	}
	userID := int64(claims["user_id"].(float64))
	email, _ := claims["email"].(string)
	tokenVersion := int64(0)
	if v, ok := claims["token_version"]; ok {
		tokenVersion = int64(v.(float64))
	}
	return userID, email, tokenVersion, nil
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
		userID, email, tokenVersion, err := validateToken(tokenStr)
		if err != nil {
			writeJSON(w, 401, "登录状态异常，请重新登录", nil)
			return
		}

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
		if !queryFailed && !isExplicitDeleted && !isMuteStatusAPI {
			isCommentAPI := strings.HasPrefix(r.URL.Path, "/api/community/comment")
			isMessageSendAPI := r.URL.Path == "/api/messages/send" || r.URL.Path == "/api/messages/poke"
			if isCommentAPI || isMessageSendAPI {
				mute, _ := getMuteStatus(userID)
				if mute != nil {
					if isCommentAPI && (mute.MuteType == 1 || mute.MuteType == 3) {
						logDedupPrintf(userID, "muted-comment", "[中间件] 拦截被评论禁言用户: userID=%d", userID)
						writeJSON(w, 403, "你已被禁言", nil)
						return
					}
					if isMessageSendAPI && (mute.MuteType == 2 || mute.MuteType == 3) {
						logDedupPrintf(userID, "muted-chat", "[中间件] 拦截被对话禁言用户: userID=%d", userID)
						writeJSON(w, 403, "你已被禁言", nil)
						return
					}
				}
			}
		}

		// 单设备登录检查：token 中的版本号必须 >= 数据库中的版本号
		// 版本号不匹配 -> 旧 token 被新登录踢下线或被管理员踢出
		var dbTokenVersion int64
		var kickedAt int64
		err = db.QueryRow("SELECT token_version, kicked_at FROM users WHERE id = ?", userID).Scan(&dbTokenVersion, &kickedAt)
		if err == nil && tokenVersion < dbTokenVersion {
			logDedupPrintf(userID, "old-token", "[中间件] 拦截旧 token: userID=%d, tokenVersion=%d, dbVersion=%d, kickedAt=%d", userID, tokenVersion, dbTokenVersion, kickedAt)
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
