package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

// ============================================================
// 客户端请求签名（防脚本裸调接口）
//
// 目的：只有携带正确签名的请求（即来自官方 App 的请求）才能调用受保护接口，
//       纯脚本 / 第三方工具即使拿到 token，也无法直接发消息。
//
// 签名算法（与 Android 端 com.aurora.chat.security.RequestSigner 完全一致）：
//
//	canonical = method + "\n" + path + "\n" + sha256hex(body) + "\n" + ts + "\n" + nonce
//	sign      = hex( HMAC_SHA256( signSecret, canonical ) )
//
// 请求头：
//
//	X-AC-Ts    秒级时间戳
//	X-AC-Nonce 一次性随机串（防重放）
//	X-AC-Sign  签名
//
// 安全设计（批次6）：签名密钥不再硬编码进客户端。服务端按用户动态生成
// per-user 随机密钥（仅存服务端内存），登录成功后经 LoginResponse.sign_secret
// 下发，登录态恢复场景可通过 GET /api/auth/client-sign-secret 重新拉取。
// 反编译 APK 无法提取固定密钥，签名伪造成本显著提高。
// ============================================================

// clientSignSkew 时间戳允许偏差（秒），同时也是 nonce 缓存存活时长。
const clientSignSkew = 300

// enforceClientSign 是否强制校验客户端请求签名（防脚本裸调接口发消息）。
// 默认开启：官方 App 登录后持动态 sign_secret 对每个请求签名，服务端校验通过才放行；
// 第三方脚本即便拿到 token 也无法直接发消息。
// 迁移/排障期若需临时关闭（例如旧版 App 尚未拿到动态密钥、或出现「全员发不出消息」），
// 设环境变量 DISABLE_CLIENT_SIGN=1 即可（无需改代码，重启生效）。
var enforceClientSign = os.Getenv("DISABLE_CLIENT_SIGN") != "1"

// legacySignSecret 旧版客户端内置的签名密钥（已泄露，仅用于迁移期兼容）。
// 真实值不再内置，改为通过环境变量 LEGACY_SIGN_SECRET 注入；未配置时该兼容路径不可用。
// 默认不接受旧签名，只有显式设置 ACCEPT_LEGACY_SIGN=1 且配置了密钥的迁移窗口内才放行。
var legacySignSecret = os.Getenv("LEGACY_SIGN_SECRET")

var acceptLegacySign = os.Getenv("ACCEPT_LEGACY_SIGN") == "1" && legacySignSecret != ""

// clientSignSecrets 用户级签名密钥缓存：userID(int64) -> secret(string)
var clientSignSecrets sync.Map

var signNonceCache sync.Map // key: nonce(string) -> value: expireUnix(int64)

// clientSignSecretFile 持久化路径：<可执行文件目录>/data/client_sign_secrets.json。
// per-user 密钥只存内存时，服务端重启会令所有在线客户端的签名失效（发消息全被 403），
// 用户必须重新登录才恢复。落盘后重启不失效；文件只含 userID 与随机 hex，无业务数据，
// 权限 0600。文件缺失/损坏时静默跳过，对应密钥会在首次使用时重新生成并补写。
var clientSignSecretFile string

// clientSignPersistMu 串行化落盘写（sync.Map 自身并发安全，这里只保护文件写）
var clientSignPersistMu sync.Mutex

func init() {
	if execPath, err := os.Executable(); err == nil {
		clientSignSecretFile = filepath.Join(filepath.Dir(execPath), "data", "client_sign_secrets.json")
		loadClientSignSecrets()
	}
	go func() {
		t := time.NewTicker(time.Minute)
		for range t.C {
			now := time.Now().Unix()
			signNonceCache.Range(func(k, v interface{}) bool {
				if v.(int64) < now {
					signNonceCache.Delete(k)
				}
				return true
			})
		}
	}()
}

// loadClientSignSecrets 启动时加载已持久化的签名密钥。
func loadClientSignSecrets() {
	if clientSignSecretFile == "" {
		return
	}
	b, err := os.ReadFile(clientSignSecretFile)
	if err != nil {
		return
	}
	var m map[string]string
	if json.Unmarshal(b, &m) != nil {
		log.Printf("[安全] 客户端签名密钥持久化文件损坏，已忽略(将按需重新生成): %s", clientSignSecretFile)
		return
	}
	for k, v := range m {
		id, err := strconv.ParseInt(k, 10, 64)
		if err != nil || v == "" {
			continue
		}
		clientSignSecrets.Store(id, v)
	}
	log.Printf("[安全] 已从持久化文件加载 %d 个客户端签名密钥(重启后老客户端签名不失效)", len(m))
}

// persistClientSignSecrets 把当前密钥表写入磁盘（best-effort，失败只告警不影响内存使用）。
func persistClientSignSecrets() {
	if clientSignSecretFile == "" {
		return
	}
	m := make(map[string]string)
	clientSignSecrets.Range(func(k, v interface{}) bool {
		m[strconv.FormatInt(k.(int64), 10)] = v.(string)
		return true
	})
	data, err := json.Marshal(m)
	if err != nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(clientSignSecretFile), 0755); err != nil {
		return
	}
	if err := os.WriteFile(clientSignSecretFile, data, 0600); err != nil {
		log.Printf("[安全] 客户端签名密钥持久化失败(可忽略): %v", err)
	}
}

// getClientSignSecret 获取（必要时首次生成）指定用户的签名密钥。
// 新生成的密钥立即落盘，保证服务端重启后老客户端无需重新登录。
func getClientSignSecret(userID int64) string {
	if v, ok := clientSignSecrets.Load(userID); ok {
		return v.(string)
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return ""
	}
	secret := hex.EncodeToString(raw)
	actual, loaded := clientSignSecrets.LoadOrStore(userID, secret)
	if !loaded {
		clientSignPersistMu.Lock()
		persistClientSignSecrets()
		clientSignPersistMu.Unlock()
	}
	return actual.(string)
}

// handleClientSignSecret 供登录用户拉取自己的签名密钥（登录态恢复场景）。
// GET /api/auth/client-sign-secret （受 authMiddleware 保护）
func handleClientSignSecret(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID <= 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	// 顺带下发该用户的传输会话密钥（session_key）：旧版本登录链路漏存该字段的设备，
	// 冷启动后 TCP 认证会带着空值来连，被「认证失败: session_key 校验不通过」拒绝且无法自愈。
	// 这里给它们一个登录态下的补齐入口（内容与该用户登录响应里的 session_key 完全一致）。
	writeJSON(w, 200, "ok", map[string]string{
		"sign_secret": getClientSignSecret(userID),
		"session_key": getSessionKey(userID),
	})
}

// verifyClientSignature 校验请求签名。rawBody 为完整请求体字节（无 body 传 nil）。
// userID 用于取该用户的动态签名密钥（批次6）。
func verifyClientSignature(r *http.Request, rawBody []byte, userID int64) bool {
	ts := r.Header.Get("X-AC-Ts")
	nonce := r.Header.Get("X-AC-Nonce")
	sig := r.Header.Get("X-AC-Sign")
	if ts == "" || nonce == "" || sig == "" {
		return false
	}
	tsInt, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return false
	}
	now := time.Now().Unix()
	if tsInt > now+clientSignSkew || tsInt < now-clientSignSkew {
		return false
	}
	// 防重放：同一 nonce 只允许出现一次
	if _, loaded := signNonceCache.LoadOrStore(nonce, now+clientSignSkew); loaded {
		return false
	}
	secret := getClientSignSecret(userID)
	if secret == "" {
		return false
	}
	if hmac.Equal([]byte(expectedSign(secret, r, rawBody, ts, nonce)), []byte(sig)) {
		return true
	}
	// 迁移期兼容：新版密钥不匹配时，若显式开启了 ACCEPT_LEGACY_SIGN，
	// 再试一次旧版内置密钥（该常量已随旧 APK 泄露，仅作过渡，全员升级后必须关闭）。
	if acceptLegacySign && hmac.Equal([]byte(expectedSign(legacySignSecret, r, rawBody, ts, nonce)), []byte(sig)) {
		log.Printf("[签名] 用户 %d 使用旧版内置密钥签名(迁移兼容)，请尽快推 App 更新", userID)
		return true
	}
	return false
}

// expectedSign 用指定密钥计算期望签名值。
func expectedSign(secret string, r *http.Request, rawBody []byte, ts, nonce string) string {
	sum := sha256.Sum256(rawBody)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(r.Method))
	mac.Write([]byte("\n"))
	mac.Write([]byte(r.URL.Path))
	mac.Write([]byte("\n"))
	mac.Write([]byte(hex.EncodeToString(sum[:])))
	mac.Write([]byte("\n"))
	mac.Write([]byte(ts))
	mac.Write([]byte("\n"))
	mac.Write([]byte(nonce))
	return hex.EncodeToString(mac.Sum(nil))
}
