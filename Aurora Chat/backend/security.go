package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// ==================== 服务端静态加密（AES-256-GCM） ====================
// 消息落库存储加密：私聊、群聊、AI 对话、会话摘要、回复等所有消息正文
// 统一用此层做 AES-256-GCM 静态加密（前缀 enc:v1:）。
//
// 客户端 → 服务端传输阶段另有 session_key 传输加密（sess:v1:，见 database.go
// 的 decryptSessionContent）：客户端发送前用对应用户 session_key 加密正文，
// 服务端 saveMessage 解密回明文后，再走本层 enc:v1: 静态加密落库。
// 全链路无明文：传输 sess:v1:，存储 enc:v1:，均为 AES-256-GCM。
//
// 密钥来源：优先读环境变量 DATA_KEY（base64，32 字节），否则首次启动生成并
// 持久化到 data/server_key.bin，权限 0600。
// 密文格式：前缀 "enc:v1:" + base64(nonce||ciphertext)，便于自动识别 + 兼容旧明文。

const encPrefix = "enc:v1:"

// 客户端 E2EE 密文前缀（私聊）。带此前缀的内容是客户端端到端加密后的密文，
// 服务器无法也不应解密，保存/读取时直接原样透传，不做二次静态加密（性能更好）。
const e2eePrefix = "E2EE:v2:"

var (
	serverKey  []byte
	serverKeyM sync.RWMutex
)

func loadOrCreateServerKey(dataDir string) []byte {
	if k := os.Getenv("DATA_KEY"); k != "" {
		if raw, err := base64.StdEncoding.DecodeString(k); err == nil && len(raw) == 32 {
			return raw
		}
	}
	keyFile := filepath.Join(dataDir, "server_key.bin")
	if raw, err := os.ReadFile(keyFile); err == nil && len(raw) == 32 {
		return raw
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		panic("无法生成服务端加密密钥: " + err.Error())
	}
	_ = os.MkdirAll(dataDir, 0700)
	if err := os.WriteFile(keyFile, key, 0600); err != nil {
		panic("无法保存服务端加密密钥: " + err.Error())
	}
	return key
}

func getServerKey() []byte {
	serverKeyM.RLock()
	defer serverKeyM.RUnlock()
	return serverKey
}

// initServerSecurity 在 main 启动时调用
func initServerSecurity(dataDir string) {
	serverKeyM.Lock()
	serverKey = loadOrCreateServerKey(dataDir)
	serverKeyM.Unlock()
}

// EncryptField 加密字符串（幂等：已是密文则原样返回）
func EncryptField(plain string) string {
	// 已是密文（服务端 enc 或客户端 E2EE）则原样返回，避免二次加密
	if plain == "" || strings.HasPrefix(plain, encPrefix) || strings.HasPrefix(plain, e2eePrefix) {
		return plain
	}
	key := getServerKey()
	if len(key) != 32 {
		return plain
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return plain
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return plain
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return plain
	}
	ct := gcm.Seal(nonce, nonce, []byte(plain), nil)
	return encPrefix + base64.StdEncoding.EncodeToString(ct)
}

// DecryptField 解密字符串（兼容旧明文：不是密文则原样返回）
func DecryptField(enc string) string {
	if !strings.HasPrefix(enc, encPrefix) {
		return enc
	}
	key := getServerKey()
	if len(key) != 32 {
		return enc
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(enc, encPrefix))
	if err != nil {
		return enc
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return enc
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return enc
	}
	if len(raw) < gcm.NonceSize()+1 {
		return enc
	}
	nonce, ct := raw[:gcm.NonceSize()], raw[gcm.NonceSize():]
	pt, err := gcm.Open(nil, nonce, ct, nil)
	if err != nil {
		return enc
	}
	return string(pt)
}

// encryptContentStore 写入前的标准加密入口
func encryptContentStore(content string) string { return EncryptField(content) }

// decryptContentLoad 读取后的标准解密入口
func decryptContentLoad(content string) string { return DecryptField(content) }

var _ = errors.New // 保留引用，避免误删 import
