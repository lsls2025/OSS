package main

import (
	"log"
	"os"
	"path/filepath"
	"strings"
)

// ==================== 配置常量（可被环境变量覆盖） ====================

// 从环境变量读取配置，支持 fallback 默认值
// 注意：默认端口必须与 Android 端 AuroraApi.kt serverUrl / TcpService.kt 保持一致（5004/5005）
func getServerPort() string {
	if p := os.Getenv("SERVER_PORT"); p != "" {
		return ":" + p
	}
	return ":5004"
}

func getTCPPort() string {
	if p := os.Getenv("TCP_PORT"); p != "" {
		return ":" + p
	}
	return ":5005"
}

// jwtSecretRuntime 缓存运行时密钥，避免每次调用重复生成
var jwtSecretRuntime string

// setJWTSecret 由 main 在启动时调用：
// - 若设置了 JWT_SECRET 环境变量则使用之；
// - 否则从 data/jwt_secret 文件读取已持久化的密钥（保证重启后登录态不丢失）；
// - 若文件也不存在，则生成随机密钥并持久化到该文件。
func setJWTSecret() {
	if s := os.Getenv("JWT_SECRET"); s != "" {
		if len(s) >= 32 {
			jwtSecretRuntime = s
			return
		}
		log.Printf("[安全] 警告: JWT_SECRET 长度过短(<32)，已忽略，改用持久化/随机密钥")
	}

	// 持久化路径与 main 保持一致：<可执行文件目录>/data/jwt_secret
	execPath, err := os.Executable()
	if err == nil {
		secretFile := filepath.Join(filepath.Dir(execPath), "data", "jwt_secret")
		if b, rerr := os.ReadFile(secretFile); rerr == nil && len(strings.TrimSpace(string(b))) >= 32 {
			jwtSecretRuntime = strings.TrimSpace(string(b))
			log.Printf("[安全] 已从持久化文件加载 JWT_SECRET（重启后登录态保持有效）")
			return
		}
		if err := os.MkdirAll(filepath.Dir(secretFile), 0755); err == nil {
			generated := randomString(48)
			if werr := os.WriteFile(secretFile, []byte(generated), 0600); werr == nil {
				jwtSecretRuntime = generated
				log.Printf("[安全] 已生成 JWT_SECRET 并持久化到 %s（重启后登录态保持有效）", secretFile)
				return
			}
		}
	}

	// 极端兜底：无法读取/写入时使用随机密钥（本次进程内有效）
	jwtSecretRuntime = randomString(48)
	log.Printf("[安全] 无法持久化 JWT_SECRET，本次进程内使用随机密钥")
}

func getJWTSecret() string {
	if jwtSecretRuntime != "" {
		return jwtSecretRuntime
	}
	// 兜底：若未初始化（极端情况），现场生成一个随机值
	setJWTSecret()
	return jwtSecretRuntime
}

// getToolsDir 返回工具APK文件的存放目录
// 优先使用 TOOLS_DIR 环境变量；其次使用可执行文件所在目录下的 data/tools；
// 最后使用当前工作目录下的 data/tools
func getToolsDir(baseDir string) string {
	if d := os.Getenv("TOOLS_DIR"); d != "" {
		return d
	}
	// 尝试可执行文件所在目录
	if baseDir != "" {
		d := filepath.Join(baseDir, "data", "tools")
		if info, err := os.Stat(d); err == nil && info.IsDir() {
			return d
		}
	}
	// 尝试当前工作目录
	if wd, err := os.Getwd(); err == nil {
		d := filepath.Join(wd, "data", "tools")
		if info, err := os.Stat(d); err == nil && info.IsDir() {
			return d
		}
	}
	// 默认用可执行文件所在目录
	return filepath.Join(baseDir, "data", "tools")
}

const (
	CodeCoolDown = 120 // 验证码发送冷却时间（秒），原60s→120s降低触发频率
	CodeExpireIn = 300 // 验证码有效期（秒）
)

// 开发者标识（应用所有者身份）：QQ 号或邮箱任一匹配即视为开发者。
// 前端 LocalStorage.isDeveloper 与后端 isDeveloperUser 统一以此为准。
const DeveloperQQ = "YOUR_DEVELOPER_QQ"
const DeveloperEmail = "YOUR_DEVELOPER_EMAIL"

// 加密消息前缀标识（用于会话预览检测）
const EncryptedMsgPrefix = "[E2EE]"
