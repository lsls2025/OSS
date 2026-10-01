package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

var cachedAvatarDir string // 缓存头像目录，避免重复计算

func getAvatarDir() string {
	if cachedAvatarDir != "" {
		return cachedAvatarDir
	}
	// 先尝试可执行文件所在目录
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	avatarDir := filepath.Join(baseDir, "data", "avatars")
	// 如果目录存在（无论是否有文件），直接使用
	if _, err := os.Stat(avatarDir); err == nil {
		cachedAvatarDir = avatarDir
		return avatarDir
	}
	// 尝试当前工作目录
	if wd, err := os.Getwd(); err == nil {
		altDir := filepath.Join(wd, "data", "avatars")
		if altDir != avatarDir {
			if _, err := os.Stat(altDir); err == nil {
				log.Printf("[头像] 使用工作目录路径: %s", altDir)
				cachedAvatarDir = altDir
				return altDir
			}
		}
	}
	// 都不存在则创建默认目录
	os.MkdirAll(avatarDir, 0755)
	log.Printf("[头像] 创建目录: %s", avatarDir)
	cachedAvatarDir = avatarDir
	return avatarDir
}

// POST /api/avatar/upload — 上传头像（base64）
// 用户身份由 JWT 认证中间件提供
func handleAvatarUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}

	// JWT 认证后的用户 ID（由 authMiddleware 注入）
	uploadUserID := getCurrentUserID(r)
	if uploadUserID == 0 {
		writeJSON(w, 401, "未登录，无法识别用户", nil)
		return
	}

	var req struct {
		AvatarB64 string `json:"avatar"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数格式错误", nil)
		return
	}

	if req.AvatarB64 == "" {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	// 限制头像大小（最大 5MB Base64）
	const maxBase64Size = 5 * 1024 * 1024
	if len(req.AvatarB64) > maxBase64Size {
		writeJSON(w, 400, "头像文件过大，最大支持 5MB", nil)
		return
	}

	data, err := base64.StdEncoding.DecodeString(req.AvatarB64)
	if err != nil {
		writeJSON(w, 400, "图片数据格式错误", nil)
		return
	}

	if len(data) < 4 {
		writeJSON(w, 400, "图片数据不完整", nil)
		return
	}

	avatarDir := getAvatarDir()
	avatarPath := filepath.Join(avatarDir, fmt.Sprintf("%d.png", uploadUserID))
	if err := os.WriteFile(avatarPath, data, 0644); err != nil {
		log.Printf("保存头像失败: %v", err)
		writeJSON(w, 500, "保存头像失败", nil)
		return
	}

	log.Printf("用户 %d 头像已上传 (%d bytes)", uploadUserID, len(data))

	// 通知所有在线好友和群友该用户的头像已变更
	go notifyAvatarChanged(uploadUserID)

	writeJSON(w, 200, "头像上传成功", nil)
}

// notifyAvatarChanged 当用户更换头像时，通知所有在线好友和群友
func notifyAvatarChanged(userID int64) {
	// 收集需要通知的用户 ID 集合
	notifySet := make(map[int64]bool)

	// 1. 好友
	friends, _ := getFriends(userID)
	for _, f := range friends {
		notifySet[f.ID] = true
	}

	// 2. 群友
	groups, _ := getGroupsForUser(userID)
	if len(groups) > 0 {
		for _, g := range groups {
			members, _ := getGroupMembers(g.ID)
			for _, m := range members {
				if m != userID {
					notifySet[m] = true
				}
			}
		}
	}

	// 3. 逐个推送（只推在线用户）
	for uid := range notifySet {
		PushToUser(uid, "avatar_updated", map[string]interface{}{
			"user_id": userID,
		})
	}
}

// GET /api/avatar/{userId} — 获取用户头像图片
// GET /api/avatar/{userId}/exists — 检查用户是否有真实头像
func handleAvatarGet(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/avatar/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || parts[0] == "" {
		http.NotFound(w, r)
		return
	}
	userID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		http.NotFound(w, r)
		return
	}

	// /api/avatar/{userId}/exists 分支：检查真实头像文件是否存在
	if len(parts) >= 2 && parts[1] == "exists" {
		avatarPath := filepath.Join(getAvatarDir(), fmt.Sprintf("%d.png", userID))
		_, err := os.Stat(avatarPath)
		writeJSON(w, 200, "ok", map[string]interface{}{"has_avatar": err == nil})
		return
	}

	avatarPath := filepath.Join(getAvatarDir(), fmt.Sprintf("%d.png", userID))
	if _, err := os.Stat(avatarPath); os.IsNotExist(err) {
		// 没有真实头像 → 返回 204 No Content（客户端显示蓝色空心线条 ic_profile）
		w.WriteHeader(http.StatusNoContent)
		return
	}

	w.Header().Set("Content-Type", "image/png")
	// 头像需实时反映用户最新上传，禁止客户端强缓存（避免换头像后仍显示旧图）
	w.Header().Set("Cache-Control", "no-cache")
	http.ServeFile(w, r, avatarPath)
}

// POST /api/groups/avatar/upload — 上传群头像（base64）
func handleGroupAvatarUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}

	var req struct {
		GroupID   int64  `json:"group_id"`
		AvatarB64 string `json:"avatar"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "参数格式错误", nil)
		return
	}

	if req.GroupID == 0 || req.AvatarB64 == "" {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	// 权限检查：仅群主和管理员可以修改群头像（开发者可操作任意群）
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	role, err := getMemberRole(req.GroupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || (role != "owner" && role != "admin")) {
		writeJSON(w, 403, "无权限操作", nil)
		return
	}

	const maxBase64Size = 5 * 1024 * 1024
	if len(req.AvatarB64) > maxBase64Size {
		writeJSON(w, 400, "头像文件过大，最大支持 5MB", nil)
		return
	}

	data, err := base64.StdEncoding.DecodeString(req.AvatarB64)
	if err != nil {
		writeJSON(w, 400, "图片数据格式错误", nil)
		return
	}
	if len(data) < 4 {
		writeJSON(w, 400, "图片数据不完整", nil)
		return
	}

	avatarDir := getAvatarDir()
	avatarPath := filepath.Join(avatarDir, fmt.Sprintf("group_%d.png", req.GroupID))
	if err := os.WriteFile(avatarPath, data, 0644); err != nil {
		log.Printf("保存群头像失败: %v", err)
		writeJSON(w, 500, "保存头像失败", nil)
		return
	}

	// 更新数据库中的 avatar 路径
	updateGroupAvatar(req.GroupID, avatarPath)

	log.Printf("群 %d 头像已上传 (%d bytes)", req.GroupID, len(data))
	writeJSON(w, 200, "头像上传成功", nil)
}

// GET /api/groups/avatar/{groupId} — 获取群头像图片
func handleGroupAvatarGet(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/api/groups/avatar/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || parts[0] == "" {
		http.NotFound(w, r)
		return
	}
	groupID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		http.NotFound(w, r)
		return
	}

	avatarPath := filepath.Join(getAvatarDir(), fmt.Sprintf("group_%d.png", groupID))
	if _, err := os.Stat(avatarPath); os.IsNotExist(err) {
		http.NotFound(w, r)
		return
	}

	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeFile(w, r, avatarPath)
}
