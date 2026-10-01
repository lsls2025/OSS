package main

import (
	"net/http"
	"os"
	"strconv"
)

const (
	DbLimitMB    = 5
	FilesLimitMB = 45
	TotalLimitMB = 50
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
	totalUsed := q.DbSize + q.FilesSize
	if totalUsed+neededBytes > q.TotalLimit {
		return false, "磁盘配额已满，总配额50MB"
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


