package main

import (
	"fmt"
	"net/http"
	"os"
	"strconv"
)

const (
	DbLimitMB    = 5
	FilesLimitMB = 45
	TotalLimitMB = 50

	// ServerFilesLimitBytes 网站托管（文件管理）每用户存储上限 50MB，开发者不受限
	ServerFilesLimitBytes int64 = 50 * 1024 * 1024
)

type UserQuota struct {
	DbSize     int64 `json:"db_size"`
	FilesSize  int64 `json:"files_size"`
	DbLimit    int64 `json:"db_limit"`
	FilesLimit int64 `json:"files_limit"`
	TotalLimit int64 `json:"total_limit"`
}

// getUserQuota 计算用户磁盘使用量
func getUserQuota(userID int64) UserQuota {
	// 1. 用户数据库文件大小
	var dbSize int64
	if p := getUserDBPath(userID); p != "" {
		if fi, err := os.Stat(p); err == nil {
			dbSize = fi.Size()
		}
	}

	// 2. 文件管理中该用户上传的文件总大小
	var filesSize int64
	db.QueryRow("SELECT COALESCE(SUM(file_size), 0) FROM server_files WHERE user_id = ?", userID).Scan(&filesSize)

	return UserQuota{
		DbSize:     dbSize,
		FilesSize:  filesSize,
		DbLimit:    DbLimitMB * 1024 * 1024,
		FilesLimit: FilesLimitMB * 1024 * 1024,
		TotalLimit: TotalLimitMB * 1024 * 1024,
	}
}

// checkQuota 检查是否有足够空间，neededBytes 为需要新增的字节数
func checkQuota(userID int64, neededBytes int64) (bool, string) {
	q := getUserQuota(userID)
	// 网站托管文件已单独限制 50MB（见 checkServerFilesQuota），这里不再把它们算进来：
	// 否则用户把网站文件放满，会连带把聊天等用户数据写入一起打死。
	if q.DbSize+neededBytes > q.TotalLimit {
		return false, "磁盘配额已满，总配额50MB"
	}
	return true, ""
}

// getServerFilesUsedBytes 网站托管已占用空间（只算文件，文件夹不计）
func getServerFilesUsedBytes(userID int64) int64 {
	var used int64
	db.QueryRow("SELECT COALESCE(SUM(file_size), 0) FROM server_files WHERE user_id = ? AND is_dir = 0", userID).Scan(&used)
	return used
}

// checkServerFilesQuota 检查网站托管本次写入是否超出每用户 50MB。
// neededBytes 为本次新增占用；覆盖保存已有文件时传「新大小 - 旧大小」，可为负。
// 开发者（isDeveloperUser）不受任何限制。
func checkServerFilesQuota(userID int64, neededBytes int64) (bool, string) {
	if isDeveloperUser(userID) {
		return true, ""
	}
	used := getServerFilesUsedBytes(userID)
	if used+neededBytes > ServerFilesLimitBytes {
		remain := ServerFilesLimitBytes - used
		if remain < 0 {
			remain = 0
		}
		return false, fmt.Sprintf("存储空间不足：网站托管每人上限 50MB，已用 %s，剩余 %s",
			fdHumanSize(used), fdHumanSize(remain))
	}
	return true, ""
}

// handleGetUserQuota 获取用户磁盘配额使用情况
func handleGetUserQuota(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userIDStr := r.Header.Get("X-User-ID")
	userID, _ := strconv.ParseInt(userIDStr, 10, 64)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	q := getUserQuota(userID)
	writeJSON(w, 200, "", q)
}


