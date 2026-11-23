package main

import (
	"bytes"
	"fmt"
	"image"
	"image/draw"
	"image/jpeg"
	"image/png"
	"math"
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
	// 长图（长截图）缩略图裁切比例：宽/高 = 150:330（≈ 手机屏幕 1:2.2）。
	// 整张长图按「最长边 1080」压完宽度只剩几十像素，缩略图放大后必然糊；
	// 客户端对长图会带 ?top=1，服务端只取顶部这一块再压，缩略图才是清晰的顶部画面。
	// 必须与客户端 LONG_THUMB_ASPECT（150/330）严格一致，否则客户端会二次裁切。
	chatThumbTopAspect = 150.0 / 330.0
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
	// 长图：只取顶部一段（宽高比 150:330，手机屏比例）作为缩略图，避免整张长图压成极细的条
	topCrop := r.URL.Query().Get("top") == "1" || r.URL.Query().Get("top") == "true"
	// 宽度下限 / 总像素上限：全屏预览用。
	// 只按「最长边」压缩时，一张 1080×12000 的长截图压完只有 184×2048 —— 宽度不到屏幕的 1/5，
	// 放大纯粹是拉伸这几个像素，怎么调质量都糊。minw 保证宽度不低于屏幕宽度（先保证可读），
	// maxpix 再兜住解码内存（否则长图会压出十几兆像素，客户端解不动）。
	minW := 0
	if v := r.URL.Query().Get("minw"); v != "" {
		if n, e := strconv.Atoi(v); e == nil && n > 0 && n <= 4000 {
			minW = n
		}
	}
	maxPix := 0
	if v := r.URL.Query().Get("maxpix"); v != "" {
		if n, e := strconv.Atoi(v); e == nil && n >= 100000 && n <= 24000000 {
			maxPix = n
		}
	}

	cachePath := chatThumbCachePathOpt(fullPath, target, quality, topCrop, minW, maxPix)
	if cf, e := os.Open(cachePath); e == nil {
		defer cf.Close()
		serveThumbFile(w, r, cf, cachePath)
		return
	}

	if err := generateChatThumbOpt(fullPath, cachePath, target, quality, topCrop, minW, maxPix); err != nil {
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

func chatThumbCachePath(fullPath string, target int, quality int, topCrop bool) string {
	return chatThumbCachePathOpt(fullPath, target, quality, topCrop, 0, 0)
}

// chatThumbCachePathOpt 带「宽度下限 / 像素上限」的缓存路径。
// 参数不同缓存必须不同，否则先请求的 2048 预览会把后来请求的宽版糊版顶掉（互相读错文件）。
func chatThumbCachePathOpt(fullPath string, target int, quality int, topCrop bool, minW int, maxPix int) string {
	suffix := fmt.Sprintf(".thumb_%d_q%d", target, quality)
	if topCrop {
		suffix += "_top"
	}
	if minW > 0 {
		suffix += fmt.Sprintf("_minw%d", minW)
	}
	if maxPix > 0 {
		suffix += fmt.Sprintf("_px%d", maxPix)
	}
	return fullPath + suffix
}

// generateChatThumb 生成压缩预览图并原子写入缓存；已存在则直接跳过。
func generateChatThumb(srcPath string, cachePath string, target int, quality int, topCrop bool) error {
	return generateChatThumbOpt(srcPath, cachePath, target, quality, topCrop, 0, 0)
}

// generateChatThumbOpt 同 generateChatThumb，额外支持「宽度下限」与「总像素上限」。
func generateChatThumbOpt(srcPath string, cachePath string, target int, quality int, topCrop bool, minW int, maxPix int) error {
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

	// 长图：先裁出顶部一块（手机屏比例 150:330），再按这块的尺寸缩放（缩略图即「放大后的顶部」）
	source := img
	if topCrop {
		if ch := chatTopCropHeight(sw, sh); ch < sh {
			cropped := image.NewRGBA(image.Rect(0, 0, sw, ch))
			draw.Draw(cropped, cropped.Bounds(), img, b.Min, draw.Src)
			source = cropped
			sh = ch
		}
	}

	dw, dh := chatThumbSize(sw, sh, target)
	dw, dh = chatThumbBudget(sw, sh, dw, dh, minW, maxPix)

	// 尺寸无需缩小：直接沿用原文件，避免无谓重编码（裁切过则必须重编码）
	if !topCrop && dw == sw && dh == sh {
		data, err := os.ReadFile(srcPath)
		if err != nil {
			return err
		}
		return writeFileAtomic(cachePath, data)
	}

	dst := resizeBilinear(source, dw, dh)
	var buf bytes.Buffer
	switch format {
	case "png":
		_ = png.Encode(&buf, dst)
	default:
		_ = jpeg.Encode(&buf, dst, &jpeg.Options{Quality: quality})
	}
	return writeFileAtomic(cachePath, buf.Bytes())
}

// chatTopCropHeight 计算长图裁切后的高度：宽度保留全部，高度按 chatThumbTopAspect 取。
// 图本身没这么长时返回原高度（等价于不裁）。
func chatTopCropHeight(sw, sh int) int {
	ch := int(float64(sw) / chatThumbTopAspect)
	if ch > sh {
		ch = sh
	}
	if ch < 1 {
		ch = 1
	}
	return ch
}

// writeFileAtomic 先写临时文件再 rename，避免并发请求读到写了一半的文件
func writeFileAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// chatThumbBudget 在「最长边缩放」的结果上再叠加两道约束：
//
//	① minW：宽度下限。长截图按最长边压完宽度会只剩一两百像素，放大即糊；
//	   这里反过来保证宽度 >= minW（但绝不超过原图宽度，放大不增加信息）。
//	② maxPix：总像素上限。长图按宽度保真后会变得非常高（1080×12000 = 13MP），
//	   手机端解码会 OOM，用整体等比缩小把总量压回上限内。
func chatThumbBudget(sw int, sh int, dw int, dh int, minW int, maxPix int) (int, int) {
	if sw <= 0 || sh <= 0 {
		return dw, dh
	}
	if minW > 0 && dw < minW && sw > dw {
		targetW := minW
		if targetW > sw {
			targetW = sw
		}
		scale := float64(targetW) / float64(sw)
		dw = targetW
		dh = int(float64(sh) * scale)
		if dh < 1 {
			dh = 1
		}
	}
	if maxPix > 0 && dw*dh > maxPix {
		scale := math.Sqrt(float64(maxPix) / (float64(dw) * float64(dh)))
		dw = int(float64(dw) * scale)
		dh = int(float64(dh) * scale)
		if dw < 1 {
			dw = 1
		}
		if dh < 1 {
			dh = 1
		}
	}
	if dw > sw {
		dw = sw
	}
	if dh > sh {
		dh = sh
	}
	return dw, dh
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
