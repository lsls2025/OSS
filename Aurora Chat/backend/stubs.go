package main

import (
	"bufio"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// 从 version.txt 加载版本配置（如果文件存在）
func loadVersionConfig() {
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	versionFile := filepath.Join(baseDir, "version.txt")

	f, err := os.Open(versionFile)
	if err != nil {
		log.Printf("[版本] version.txt 不存在 (%s)，使用默认版本", versionFile)
		return
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.TrimSpace(parts[0])
		value := strings.TrimSpace(parts[1])

		switch key {
		case "version_code":
			if code, err := strconv.Atoi(value); err == nil && code > 0 {
				LatestVersionCode = code
			}
		case "version_name":
			if value != "" {
				LatestVersionName = value
			}
		case "download_url":
			if value != "" {
				DownloadURL = value
			}
		case "force_update":
			ForceUpdate = value == "true"
		case "update_message":
			if value != "" {
				UpdateMessage = value
			}
		}
	}
	log.Printf("[版本] 已加载: v%s (code=%d)", LatestVersionName, LatestVersionCode)
}

// ignoreJoinRequest 用于忽略群加群申请（占位，实际实现在 database.go 中可通过 reviewJoinRequest(requestID, false) 替代）
func ignoreJoinRequest(requestID int64) error {
	return nil
}
