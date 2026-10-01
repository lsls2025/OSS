package main

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// 聊天图片压缩预览的默认参数（默认显示压缩图，点开才加载原图，节省服务器带宽）
const (
	chatThumbLongSide = 1080 // 压缩图最长边
	chatThumbQuality  = 85   // JPEG 重编码质量
)

// 聊天媒体目录（main.go 启动时赋值）
var chatMediaDir string

// handleChatMediaThumb 返回聊天图片的压缩预览图，并按参数落盘缓存。
//
// 路径约定：/api/chat-media/thumb/<文件名>?w=1080&q=85
//   - 缓存命中：直接读文件，零计算开销；
//   - 未命中：解码 -> 按最长边缩放 -> 按质量重编码 -> 原子写入缓存（每张图全局只算一次）；
//   - 生成失败（如无法解码的格式）：回退原图，保证功能可用。
func handleChatMediaThumb(w http.ResponseWriter, r *http.Request) {
	rel := strings.TrimPrefix(r.URL.Path, "/api/chat-media/thumb/")
	rel = strings.Trim(rel, "/")
	if rel == "" || strings.Contains(rel, "..") {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}
	fullPath := filepath.Join(chatMediaDir, filepath.Clean(rel))
	info, err := os.Stat(fullPath)
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	// AI 生成图（ai_ 前缀）超过 7 天：返回"图片已过期"，与 /chat-media 原图保持一致
	if aiMediaExpired(rel, info) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusGone)
		fmt.Fprintf(w, `{"code":410,"message":"图片已过期，请重新生成"}`)
		return
	}

	target := chatThumbLongSide
	if v := r.URL.Query().Get("w"); v != "" {
		if n, e := strconv.Atoi(v); e == nil && n > 0 && n <= 4000 {
			target = n
		}
	}
	quality := chatThumbQuality
	if v := r.URL.Query().Get("q"); v != "" {
		if n, e := strconv.Atoi(v); e == nil && n > 0 && n <= 100 {
			quality = n
		}
	}

	cachePath := chatThumbCachePath(fullPath, target, quality)
	if cf, e := os.Open(cachePath); e == nil {
		defer cf.Close()
		serveThumbFile(w, r, cf, cachePath)
		return
	}

	if err := generateChatThumb(fullPath, cachePath, target, quality); err != nil {
		if orig, e := os.Open(fullPath); e == nil {
			defer orig.Close()
			serveThumbFile(w, r, orig, fullPath)
			return
		}
		http.Error(w, "thumb failed", http.StatusInternalServerError)
		return
	}
	cf, err := os.Open(cachePath)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer cf.Close()
	serveThumbFile(w, r, cf, cachePath)
}

func chatThumbCachePath(fullPath string, target int, quality int) string {
	return fullPath + fmt.Sprintf(".thumb_%d_q%d", target, quality)
}

// generateChatThumb 生成压缩预览图并原子写入缓存；已存在则直接跳过。
func generateChatThumb(srcPath string, cachePath string, target int, quality int) error {
	if _, err := os.Stat(cachePath); err == nil {
		return nil
	}
	src, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	img, format, err := image.Decode(src)
	src.Close()
	if err != nil {
		return err
	}

	b := img.Bounds()
	sw, sh := b.Dx(), b.Dy()
	dw, dh := chatThumbSize(sw, sh, target)

	// 尺寸无需缩小：直接沿用原文件，避免无谓重编码
	if dw == sw && dh == sh {
		data, err := os.ReadFile(srcPath)
		if err != nil {
			return err
		}
		return writeFileAtomic(cachePath, data)
	}

	dst := resizeBilinear(img, dw, dh)
	var buf bytes.Buffer
	switch format {
	case "png":
		_ = png.Encode(&buf, dst)
	default:
		_ = jpeg.Encode(&buf, dst, &jpeg.Options{Quality: quality})
	}
	return writeFileAtomic(cachePath, buf.Bytes())
}

// writeFileAtomic 先写临时文件再 rename，避免并发请求读到写了一半的文件
func writeFileAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// chatThumbSize 按“最长边”计算缩放后尺寸
func chatThumbSize(sw int, sh int, target int) (int, int) {
	maxDim := sw
	if sh > maxDim {
		maxDim = sh
	}
	if maxDim <= target {
		return sw, sh
	}
	scale := float64(target) / float64(maxDim)
	dw := int(float64(sw) * scale)
	dh := int(float64(sh) * scale)
	if dw < 1 {
		dw = 1
	}
	if dh < 1 {
		dh = 1
	}
	return dw, dh
}
