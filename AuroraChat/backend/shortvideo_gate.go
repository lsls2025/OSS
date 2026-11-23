package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
)

// ========== 短视频总开关（开发者管理 → 短视频） ==========
//
// 规则：
//  1. 开发者可一键开启/关闭全站短视频，默认开启；
//  2. 关闭后**所有人**都看不到，且必须从最底层拦截——不能只挡列表接口，
//     否则拿到 play_url 的人仍可直接取流；
//  3. 提示文案由开发者自定义，客户端「进入页面后」才显示，入口点击不做任何提示。
//
// 存储：
//   sys_flags.shortvideo_disabled = 1 → 已关闭（无该行 / 0 = 开启，天然满足"默认开启"）
//   sys_text_flags.shortvideo_message  → 自定义提示文案
//
// 拦截点（缺一不可）：
//   - /api/shortvideo/feed        列表
//   - /api/shortvideo/detail      详情
//   - /api/shortvideo/hls/        播放流 + 封面（**公开路由、不带鉴权，必须在这里拦**）
//   - /api/shortvideo/upload      上传
//   - 点赞 / 评论 / 播放计数 / 转码状态
//
// 开发者专属接口（/api/dev/shortvideo-gate、/api/shortvideo/info、retranscode）不受影响，
// 它们只做管理，不下发任何可播内容。

const (
	svFlagDisabled       = "shortvideo_disabled"
	svFlagUploadDisabled = "shortvideo_upload_disabled"
	svTextMessage        = "shortvideo_message"
	svDefaultMessage     = "短视频功能已关闭"
	svUploadBlockMessage = "短视频上传已关闭"
)

func initShortVideoGate() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS sys_text_flags (
		flag_key TEXT PRIMARY KEY,
		value    TEXT NOT NULL DEFAULT ''
	)`); err != nil {
		log.Printf("[短视频] 提示文案表初始化失败: %v", err)
	}
}

// getTextFlag 读取文本型系统配置
func getTextFlag(key string) string {
	var v string
	if err := db.QueryRow("SELECT value FROM sys_text_flags WHERE flag_key = ?", key).Scan(&v); err != nil {
		return ""
	}
	return v
}

// setTextFlag 写入文本型系统配置（持久化，重启保留）
func setTextFlag(key, val string) {
	if _, err := db.Exec("INSERT OR REPLACE INTO sys_text_flags (flag_key, value) VALUES (?, ?)", key, val); err != nil {
		log.Printf("[短视频] 写入文案 %s 失败: %v", key, err)
	}
}

// isShortVideoDisabled 当前是否已关闭全站短视频（默认开启）
func isShortVideoDisabled() bool { return getFlag(svFlagDisabled) }

// isShortVideoUploadDisabled 当前是否已禁止上传（默认允许）
func isShortVideoUploadDisabled() bool { return getFlag(svFlagUploadDisabled) }

// shortVideoGateMessage 开发者配置的提示文案；未配置时用默认短句
func shortVideoGateMessage() string {
	if s := strings.TrimSpace(getTextFlag(svTextMessage)); s != "" {
		return s
	}
	return svDefaultMessage
}

// blockShortVideo 统一拦截入口：已关闭时写 403 并返回 true，调用方立刻 return。
func blockShortVideo(w http.ResponseWriter) bool {
	if !isShortVideoDisabled() {
		return false
	}
	writeJSON(w, 403, shortVideoGateMessage(), nil)
	return true
}

// blockShortVideoUpload 上传闸门：已禁止上传时写 403 并返回 true。
// 与「总开关」独立：总开关管看，这个只管发；两者都不依赖客户端，裸调接口同样被拦。
func blockShortVideoUpload(w http.ResponseWriter) bool {
	if !isShortVideoUploadDisabled() {
		return false
	}
	writeJSON(w, 403, svUploadBlockMessage, nil)
	return true
}

// GET /api/shortvideo/gate —— 登录用户可读：客户端进入短视频页后据此显示提示
func handleShortVideoGate(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, "ok", map[string]interface{}{
		"disabled":        isShortVideoDisabled(),
		"message":         shortVideoGateMessage(),
		"upload_disabled": isShortVideoUploadDisabled(),
	})
}

// GET/POST /api/dev/shortvideo-gate —— 开发者读取 / 修改（字段可只传其一）
func handleDevShortVideoGate(w http.ResponseWriter, r *http.Request) {
	userID := getCurrentUserID(r)
	if userID == 0 || !isDeveloperUser(userID) {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, 200, "ok", map[string]interface{}{
			"disabled":        isShortVideoDisabled(),
			"message":         shortVideoGateMessage(),
			"upload_disabled": isShortVideoUploadDisabled(),
		})
	case http.MethodPost:
		var req struct {
			Disabled       *bool   `json:"disabled"`
			UploadDisabled *bool   `json:"upload_disabled"`
			Message        *string `json:"message"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeJSON(w, 400, "请求格式错误", nil)
			return
		}
		if req.Disabled != nil {
			setFlag(svFlagDisabled, *req.Disabled)
		}
		if req.UploadDisabled != nil {
			setFlag(svFlagUploadDisabled, *req.UploadDisabled)
		}
		if req.Message != nil {
			setTextFlag(svTextMessage, strings.TrimSpace(*req.Message))
		}
		log.Printf("[短视频] 开发者 %d 设置：disabled=%v uploadDisabled=%v message=%q",
			userID, isShortVideoDisabled(), isShortVideoUploadDisabled(), shortVideoGateMessage())
		writeJSON(w, 200, "ok", map[string]interface{}{
			"disabled":        isShortVideoDisabled(),
			"message":         shortVideoGateMessage(),
			"upload_disabled": isShortVideoUploadDisabled(),
		})
	default:
		writeJSON(w, 405, "方法不支持", nil)
	}
}
