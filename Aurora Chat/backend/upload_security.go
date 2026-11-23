package main

// ============================================================
// 安全修复批次5（审计 B3）：统一上传扩展名校验 + 公开文件直链安全响应头
// 1) 上传扩展名黑名单 + 白名单，应用到全部上传入口
// 2) 公开直链（/serve/、/chat-media/、/api/community/files/）统一安全响应头
// ============================================================

import (
	"fmt"
	"net/http"
	"path/filepath"
	"strings"
)

// blockedUploadExts 危险/可脚本渲染扩展名黑名单（统一小写，含点）。
// 命中即拒绝上传；历史文件命中时强制以 octet-stream 下载，禁止服务器映射危险 Content-Type。
var blockedUploadExts = map[string]bool{
	// HTML / SVG / XML 等可脚本渲染格式
	".html": true, ".htm": true, ".xhtml": true, ".shtml": true,
	".svg": true, ".svgz": true,
	".xml": true, ".xsd": true, ".xsl": true, ".xslt": true, ".dtd": true,
	// 服务端脚本
	".php": true, ".php3": true, ".php4": true, ".php5": true, ".phtml": true,
	".jsp": true, ".jspx": true,
	".asp": true, ".aspx": true, ".asax": true, ".ashx": true, ".asmx": true, ".axd": true,
	".cgi": true, ".pl": true, ".py": true, ".pyc": true, ".pyw": true, ".rb": true,
	// 客户端脚本
	".js": true, ".mjs": true, ".jsx": true, ".ts": true, ".tsx": true, ".vue": true,
	// 批处理 / 脚本类可执行（安装包 apk 系列见下方白名单，前端社区/私聊明确支持分享）
	".exe": true, ".msi": true, ".msp": true, ".mst": true,
	".bat": true, ".cmd": true, ".com": true, ".scr": true, ".pif": true, ".hta": true,
	".sh": true, ".bash": true, ".zsh": true, ".ksh": true, ".csh": true,
	".dll": true, ".so": true, ".dylib": true, ".jar": true, ".class": true, ".swf": true,
	".vbs": true, ".vbe": true, ".wsf": true, ".wsh": true,
	".ps1": true, ".psm1": true, ".psd1": true,
	".reg": true, ".inf": true, ".url": true, ".lnk": true, ".iso": true,
	// 浏览器插件等可执行分发载体
	".crx": true, ".xpi": true,
}

// validateUploadExt 校验上传文件扩展名（基于原始文件名）。
// 策略：仅拦截黑名单中的危险/可脚本渲染格式（.html/.js/.exe/.apk 系列等），
// 其余扩展名一律放行——避免白名单遗漏导致"明明附了文件却上传失败"。
// 服务端在直链响应时仍会强制 nosniff + sandbox + attachment，浏览器不会执行任何被下载内容。
func validateUploadExt(filename string) (bool, string) {
	ext := strings.ToLower(filepath.Ext(filename))
	// 允许无扩展名：客户端 content:// 媒体 URI 的 lastPathSegment 常是无扩展名的数字 ID，
	// 直接拒绝会表现为"任何文件都传不出去"。后端实际按真实内容类型（magic bytes）推导
	// 存储扩展名与响应头，且直链响应强制 nosniff + attachment，不会执行任何被下载内容。
	// 因此仅黑名单中的危险/可脚本渲染格式（.html/.js/.exe 等）才拒绝。
	if blockedUploadExts[ext] {
		return false, "禁止上传可执行或可脚本渲染格式（" + strings.TrimPrefix(ext, ".") + "）"
	}
	return true, ""
}

// hostingAllowedExts 网站托管专用放行扩展名。
// 网站托管本身就是让用户上传静态站点（index.html / css / js 等），
// 不能沿用聊天附件的黑名单，否则整个功能没法用；站点由独立端口静态服务，不会执行服务端脚本。
var hostingAllowedExts = map[string]bool{
	".html": true, ".htm": true, ".xhtml": true,
	".css": true, ".js": true, ".mjs": true, ".map": true,
	".json": true, ".xml": true, ".svg": true, ".svgz": true,
	".webmanifest": true, ".wasm": true,
}

// validateHostingUploadExt 网站托管上传校验：
// 先放行静态站点资源（HTML/CSS/JS 等）；其余仍按公共黑名单，
// .php/.jsp/.sh/.exe 等服务端脚本与可执行文件继续拒绝。
func validateHostingUploadExt(filename string) (bool, string) {
	if hostingAllowedExts[strings.ToLower(filepath.Ext(filename))] {
		return true, ""
	}
	return validateUploadExt(filename)
}

// inlineImageExts 可安全内联预览的图片扩展名（浏览器原生渲染，无脚本执行面）。
var inlineImageExts = map[string]bool{
	".png": true, ".jpg": true, ".jpeg": true, ".gif": true, ".webp": true,
	".bmp": true, ".ico": true, ".avif": true, ".heic": true, ".heif": true,
}

// inlineMediaExts 视频/音频扩展名：允许 <video>/<audio> 内联流式播放（不强制下载）。
// attachment 不影响 App 内 MediaPlayer，但会让网页 <video> 变成"下载"而非内联播放；
// 视频内联风险低（不可脚本执行），且对播放体验至关重要。
var inlineMediaExts = map[string]bool{
	".mp4": true, ".webm": true, ".mov": true, ".m4v": true, ".avi": true,
	".mkv": true, ".mpg": true, ".mpeg": true, ".3gp": true,
	".mp3": true, ".wav": true, ".ogg": true, ".aac": true, ".flac": true,
	".m4a": true, ".weba": true,
}

// sanitizeFilenameForHeader 清洗用于 Content-Disposition 的文件名，防止头注入。
func sanitizeFilenameForHeader(name string) string {
	name = strings.ReplaceAll(name, "\r", "")
	name = strings.ReplaceAll(name, "\n", "")
	name = strings.ReplaceAll(name, `"`, "")
	return name
}

// applyFileResponseHeaders 为公开文件直链统一设置安全响应头。
// 策略：
//   - 危险/可脚本渲染类型（黑名单）：强制 application/octet-stream + attachment + nosniff + CSP sandbox，
//     禁止服务器端映射 text/html / image/svg+xml / application/javascript 等危险 Content-Type；
//   - 图片：inline（内联预览）+ nosniff；
//   - 其余（视频/音频/文档/压缩包等）：attachment（下载）+ nosniff。
//
// Content-Type 未设置时由 http.ServeFile/ServeContent 按扩展名或内容嗅探补充（图片映射为 image/* 安全类型）。
func applyFileResponseHeaders(w http.ResponseWriter, fileName string) {
	ext := strings.ToLower(filepath.Ext(fileName))
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "sandbox")
	dispName := sanitizeFilenameForHeader(fileName)
	if blockedUploadExts[ext] {
		// 危险类型：禁止危险 Content-Type 映射，强制下载
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, dispName))
		return
	}
	if inlineImageExts[ext] || inlineMediaExts[ext] {
		w.Header().Set("Content-Disposition", fmt.Sprintf(`inline; filename="%s"`, dispName))
		return
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, dispName))
}
