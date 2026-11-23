package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// copyChatMediaFile 把 /chat-media/ 下的某个媒体文件复制成独立副本，返回新的媒体 URL。
// 用于「分享聊天」等场景：内嵌的媒体改为本次发送时复制出的副本，原消息撤回/删除不影响已分享内容。
// 安全：仅允许复制 chatMediaDir 内、单文件名（禁 .. 与 /、\）的文件；只取原图（忽略 ?query），
// 缩略图按原图自动生成，无需单独复制。
func copyChatMediaFile(url string) (string, bool) {
	src := url
	if idx := strings.Index(src, "?"); idx >= 0 {
		src = src[:idx] // 去 query，复制原图
	}
	if !strings.HasPrefix(src, "/chat-media/") {
		return "", false
	}
	name := strings.TrimPrefix(src, "/chat-media/")
	if name == "" || strings.Contains(name, "..") || strings.Contains(name, "/") || strings.Contains(name, "\\") {
		return "", false
	}
	srcPath := filepath.Join(chatMediaDir, name)
	info, err := os.Stat(srcPath)
	if err != nil || info.IsDir() {
		return "", false
	}
	ext := filepath.Ext(name)
	newName := fmt.Sprintf("copy_%d_%s%s", time.Now().UnixNano(), randToken(8), ext)
	dstPath := filepath.Join(chatMediaDir, newName)
	if err := copyFileContents(srcPath, dstPath); err != nil {
		log.Printf("[媒体复制] 失败 src=%s err=%v", srcPath, err)
		return "", false
	}
	log.Printf("[媒体复制] %s -> %s", name, newName)
	return "/chat-media/" + newName, true
}

func copyFileContents(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()
	if _, err = io.Copy(out, in); err != nil {
		return err
	}
	return out.Sync()
}

func randToken(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%d", time.Now().UnixNano())
	}
	for i := range b {
		b[i] = letters[int(b[i])%len(letters)]
	}
	return string(b)
}

// rewriteShareCardMediaURLs 若 content 是「聊天记录」类卡片（含 msgs=Base64(JSON)），
// 把内嵌的每个媒体 URL 复制成独立副本并重写，使分享内容不再依赖原消息的媒体文件。
// 非卡片内容原样返回；任一媒体复制失败时跳过该条（不影响其余），整体失败则原样返回。
func rewriteShareCardMediaURLs(content string) string {
	if content == "" {
		return content
	}
	raw := ""
	for _, line := range strings.Split(content, "\n") {
		if strings.HasPrefix(line, "msgs=") {
			raw = strings.TrimPrefix(line, "msgs=")
			break
		}
	}
	if raw == "" {
		return content
	}
	b, err := base64.URLEncoding.DecodeString(raw)
	if err != nil {
		b, err = base64.URLEncoding.WithPadding(base64.NoPadding).DecodeString(raw)
		if err != nil {
			return content
		}
	}
	var arr []map[string]interface{}
	if err := json.Unmarshal(b, &arr); err != nil || len(arr) == 0 {
		return content
	}
	changed := false
	for _, o := range arr {
		u, ok := o["u"].(string)
		if !ok || !strings.HasPrefix(u, "/chat-media/") {
			continue
		}
		if newU, ok2 := copyChatMediaFile(u); ok2 {
			o["u"] = newU
			changed = true
		}
	}
	if !changed {
		return content
	}
	nb, err := json.Marshal(arr)
	if err != nil {
		return content
	}
	newRaw := base64.URLEncoding.EncodeToString(nb)
	lines := strings.Split(content, "\n")
	for i, line := range lines {
		if strings.HasPrefix(line, "msgs=") {
			lines[i] = "msgs=" + newRaw
			break
		}
	}
	return strings.Join(lines, "\n")
}
