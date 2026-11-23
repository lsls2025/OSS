package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"html"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

// POST /api/admin/users/create — 开发者 / 管理员主动创建用户（培养开发者自建账号）
//
// 与公开注册接口（handleRegister）不同：这里走管理员通道，不做设备指纹 / 验证码 /
// 注册限流等前置校验，便于开发者在后台快速创建账号。
// 字段：username（选填）、qq_number（选填）、password（必填，至少 8 位）、avatar（选填，base64 PNG）。
func handleAdminCreateUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	if _, ok := canManageUsers(r); !ok {
		writeJSON(w, 403, "无权访问", nil)
		return
	}

	var req struct {
		Username  string `json:"username"`
		QQNumber  string `json:"qq_number"`
		Password  string `json:"password"`
		AvatarB64 string `json:"avatar"`
	}
	limitJSONBody(w, r, maxAuthBodyBytes)
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.QQNumber = strings.TrimSpace(req.QQNumber)
	req.Password = strings.TrimSpace(req.Password)
	req.Username = html.EscapeString(strings.TrimSpace(req.Username))

	// 密码必填，且至少 8 位（与公开注册 / 登录保持一致，保证账号可直接登录）
	if req.Password == "" {
		writeJSON(w, 400, "请填写密码", nil)
		return
	}
	if len(req.Password) < 8 {
		writeJSON(w, 400, "密码至少 8 位", nil)
		return
	}
	// QQ 号格式校验（如有）
	if req.QQNumber != "" && !isDigitsOnly(req.QQNumber) {
		writeJSON(w, 400, "QQ 号格式不正确", nil)
		return
	}
	// 账号冲突：QQ 已被占用则拒绝
	if code, msg := accountConflict("", req.QQNumber); code != 0 {
		writeJSON(w, code, msg, nil)
		return
	}

	clientIP := getClientIP(r)
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		writeJSON(w, 500, "服务器内部错误", nil)
		return
	}

	userID, err := createUser("", req.Username, string(hashedPassword), clientIP, 0)
	if err != nil {
		log.Printf("[管理员创建用户] 写入失败: %v", err)
		writeJSON(w, 500, "创建失败", nil)
		return
	}

	// 用户名留空时用「用户{id}」兜底，保证有展示名
	if req.Username == "" {
		db.Exec("UPDATE users SET username = ? WHERE id = ?", fmt.Sprintf("用户%d", userID), userID)
	}
	if req.QQNumber != "" {
		db.Exec("UPDATE users SET qq_number = ? WHERE id = ?", req.QQNumber, userID)
	}

	// 头像（选填）：直接写入 {id}.png，复用既有 /api/avatar/{id} 展示链路
	if req.AvatarB64 != "" {
		if len(req.AvatarB64) > 5*1024*1024 {
			writeJSON(w, 400, "头像文件过大", nil)
			return
		}
		data, derr := base64.StdEncoding.DecodeString(req.AvatarB64)
		if derr != nil || len(data) < 4 {
			writeJSON(w, 400, "头像数据格式错误", nil)
			return
		}
		avatarPath := filepath.Join(getAvatarDir(), fmt.Sprintf("%d.png", userID))
		if werr := os.WriteFile(avatarPath, data, 0644); werr != nil {
			log.Printf("[管理员创建用户] 写头像失败 id=%d: %v", userID, werr)
		}
	}

	writeJSON(w, 200, "创建成功", map[string]interface{}{"user_id": userID})
}

// canManageUsers 判断当前请求是否具备用户管理权限
// （开发者 / 平台管理员 / 任一 users.* 细粒度权限）。
func canManageUsers(r *http.Request) (int64, bool) {
	for _, p := range []string{"users.create", "users.delete", "users.view_profile", "users.location"} {
		if id, ok := checkAdminPermission(r, p); ok {
			return id, true
		}
	}
	return 0, false
}
