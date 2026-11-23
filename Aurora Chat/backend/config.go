package main

import (
	"fmt"
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
// 真实值通过环境变量 DEV_QQ / DEV_EMAIL 注入，源码不内置任何所有者身份信息。
// 未配置时默认为空，仅靠「ID=1 主账号恒为开发者」兜底；自建部署请配置这两个变量。
// 安全说明：注册/完善资料已有 QQ 占用校验（findUserByQQNumber），他人无法通过注册 QQ 冒充开发者。
var (
	defaultDeveloperQQ    = ""
	defaultDeveloperEmail = ""
)

var DeveloperQQValue = defaultDeveloperQQ
var DeveloperEmailValue = defaultDeveloperEmail

// setupDeveloperConfig 加载开发者标识（环境变量优先，缺省用内置默认值），由 main 启动时调用一次。
func setupDeveloperConfig() {
	if v := strings.TrimSpace(os.Getenv("DEV_QQ")); v != "" {
		DeveloperQQValue = v
	}
	if v := strings.TrimSpace(os.Getenv("DEV_EMAIL")); v != "" {
		DeveloperEmailValue = v
	}
	if DeveloperQQValue != "" && !isValidQQFormat(DeveloperQQValue) {
		log.Printf("[安全] 警告: DEV_QQ 值不是合法 QQ 号，已回退默认值。")
		DeveloperQQValue = defaultDeveloperQQ
	}
	log.Printf("[安全] 开发者标识已加载: QQ=%s Email=%s（另有 ID=1 主账号恒为开发者兜底）",
		maskSecret(DeveloperQQValue), maskSecret(DeveloperEmailValue))
}

// 加密消息前缀标识（用于会话预览检测）
const EncryptedMsgPrefix = "[E2EE]"

// ==================== 第三方 AI（SolitaryCryAI）密钥与上游配置 ====================
// 安全约束（批次6）：源码中禁止出现任何真实密钥硬编码值。
// 密钥来源优先级：
//   ① 环境变量 AI_API_KEYS（deploy.sh / systemd EnvironmentFile）
//   ② 可执行文件同目录的配置文件 ai_config.env / ai_keys.txt（均不进 git）
//   ③ 编译期注入（go build -ldflags "-X main.bakedAIKeys=..."，见下方变量）
// 第 ② 条让「直接启动二进制」的场景（宝塔进程管理器、systemd）也能拿到密钥；
// 第 ③ 条让「只上传一个二进制 + 点重启」也能跑（配置文件缺失时的最后兜底）。
// 三条来源的密钥都只存在于部署产物里，源码与 git 历史中零密钥。

// 编译期注入位：源码恒为空字符串，仅由构建命令 -ldflags -X 填充。
// 这样「源码零密钥」的安全约束与「开箱即用」的部署体验可以同时成立。
// 构建示例（密钥不会写进任何文件，只存在于产物二进制中）：
//
//	go build -ldflags "-X 'main.bakedAIKeys=sk-aaa,sk-bbb' -X 'main.bakedAIBaseURL=https://api.agnes-ai.cn/v1'" -o aurora-server .
var (
	bakedAIKeys    string // 逗号/换行分隔的密钥列表
	bakedAIBaseURL string // 可选：上游 baseURL
	bakedAIModel   string // 可选：对话模型名
)

// setupAISolitaryCryConfig 加载第三方 AI 配置（幂等，ai_chat.go 的 init() 与 main() 各调一次）。
func setupAISolitaryCryConfig() {
	// ① 环境变量（deploy.sh 启动时由 ai_config.env 导出，或由 systemd EnvironmentFile 注入）
	if env := strings.TrimSpace(os.Getenv("AI_API_KEYS")); env != "" {
		if keys := splitAIKeys(env); len(keys) > 0 {
			solitaryCryAI.apiKeys = keys
		}
	}
	if u := strings.TrimSpace(os.Getenv("AI_BASE_URL")); u != "" {
		solitaryCryAI.baseURL = strings.TrimRight(u, "/")
	}
	if m := strings.TrimSpace(os.Getenv("AI_MODEL")); m != "" {
		solitaryCryAI.model = m
	}
	// ② 同目录配置文件兜底：宝塔进程管理器直接拉起 ./aurora-server 时没有 shell 环境变量，
	// 由二进制自己读同目录的 ai_config.env / ai_keys.txt（格式与 deploy.sh 约定一致）。
	// 这样「上传二进制 + 上传配置 + 点重启」即可完成部署，用户不需要跑任何命令。
	if len(solitaryCryAI.apiKeys) == 0 {
		if exe, err := os.Executable(); err == nil {
			if keys, base, model := loadAIConfigFromDir(filepath.Dir(exe)); len(keys) > 0 {
				solitaryCryAI.apiKeys = keys
				if strings.TrimSpace(os.Getenv("AI_BASE_URL")) == "" && base != "" {
					solitaryCryAI.baseURL = base
				}
				if strings.TrimSpace(os.Getenv("AI_MODEL")) == "" && model != "" {
					solitaryCryAI.model = model
				}
				log.Printf("[AI] 已从同目录配置文件加载 %d 个密钥（baseURL=%s）", len(keys), solitaryCryAI.baseURL)
			}
		}
	}
	// ③ 编译期注入兜底：构建时用 -ldflags -X 灌入的密钥（源码里恒为空）。
	// 用于「只传一个 aurora-server 文件、连配置文件都不想传」的部署场景。
	if len(solitaryCryAI.apiKeys) == 0 && strings.TrimSpace(bakedAIKeys) != "" {
		if keys := splitAIKeys(bakedAIKeys); len(keys) > 0 {
			solitaryCryAI.apiKeys = keys
			if strings.TrimSpace(os.Getenv("AI_BASE_URL")) == "" && strings.TrimSpace(bakedAIBaseURL) != "" {
				solitaryCryAI.baseURL = strings.TrimRight(strings.TrimSpace(bakedAIBaseURL), "/")
			}
			if strings.TrimSpace(os.Getenv("AI_MODEL")) == "" && strings.TrimSpace(bakedAIModel) != "" {
				solitaryCryAI.model = strings.TrimSpace(bakedAIModel)
			}
			log.Printf("[AI] 已从二进制内置配置加载 %d 个密钥（baseURL=%s）", len(keys), solitaryCryAI.baseURL)
		}
	}
	if len(solitaryCryAI.apiKeys) == 0 {
		log.Printf("[安全] 警告: 未找到 AI_API_KEYS（环境变量、同目录 ai_config.env/ai_keys.txt、二进制内置配置均为空），第三方 AI 代理功能已禁用（/api/ai/* 将返回 503）。请配置后重启服务。")
	}
	// 失效标记表长度与 apiKeys 对齐（nextAPIKey 依赖 keyDisabled 长度做轮询跳过）
	keyDisabledMu.Lock()
	keyDisabled = make([]bool, len(solitaryCryAI.apiKeys))
	keyDisabledMu.Unlock()
	WriteLog("ai_proxy", fmt.Sprintf("SolitaryCryAI 配置已加载: keys=%d baseURL=%s model=%s",
		len(solitaryCryAI.apiKeys), solitaryCryAI.baseURL, solitaryCryAI.model))
}

// loadAIConfigFromDir 读取 dir 下的 AI 配置文件，按 ai_config.env → ai_keys.txt 顺序尝试。
//   ai_config.env：shell 风格 KEY=VALUE（AI_API_KEYS / AI_BASE_URL / AI_MODEL，# 开头为注释）
//   ai_keys.txt  ：纯密钥列表，逗号/换行/空白分隔
//
// 返回空 keys 表示未找到可用配置。key 值只存在于这些文件里（.gitignore 已排除），不进源码。
func loadAIConfigFromDir(dir string) (keys []string, baseURL, model string) {
	for _, name := range []string{"ai_config.env", "ai_keys.txt"} {
		raw, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			continue
		}
		for _, line := range strings.Split(string(raw), "\n") {
			line = strings.TrimSpace(strings.TrimSuffix(line, "\r"))
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			k, v, isKV := strings.Cut(line, "=")
			if !isKV {
				// 纯密钥行（ai_keys.txt 的常态；也容忍 env 文件里误写的裸 key）
				keys = append(keys, splitAIKeys(line)...)
				continue
			}
			// 兼容 `export AI_API_KEYS=...` 写法
			k = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(k), "export "))
			v = strings.Trim(strings.TrimSpace(v), `"'`)
			switch k {
			case "AI_API_KEYS":
				keys = append(keys, splitAIKeys(v)...)
			case "AI_BASE_URL":
				baseURL = strings.TrimRight(v, "/")
			case "AI_MODEL":
				model = v
			}
		}
		if len(keys) > 0 {
			return
		}
	}
	return
}

// splitAIKeys 按逗号/换行/空白切分密钥串（与 AI_API_KEYS 环境变量的解析口径一致）。
func splitAIKeys(s string) []string {
	out := make([]string, 0, 2)
	for _, part := range strings.FieldsFunc(s, func(r rune) bool {
		return r == ',' || r == '\n' || r == ' ' || r == '\t' || r == '\r'
	}) {
		if p := strings.TrimSpace(part); p != "" {
			out = append(out, p)
		}
	}
	return out
}
