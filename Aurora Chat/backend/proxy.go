package main

import (
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

// ==================== HTTP 正向代理（供手机通过服务器上网） ====================

func handleProxy(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 GET/POST", nil)
		return
	}

	target := r.URL.Query().Get("url")
	if target == "" {
		writeJSON(w, 400, "缺少 url 参数", nil)
		return
	}

	// 安全限制：只允许 http/https
	if !strings.HasPrefix(target, "http://") && !strings.HasPrefix(target, "https://") {
		writeJSON(w, 400, "仅支持 http/https 协议", nil)
		return
	}

	log.Printf("[代理] %s -> %s", r.RemoteAddr, target)

	// 构造转发请求
	proxyReq, err := http.NewRequest(r.Method, target, r.Body)
	if err != nil {
		writeJSON(w, 500, "请求构造失败", nil)
		return
	}
	// 复制请求头
	for k, v := range r.Header {
		if k != "Host" {
			proxyReq.Header[k] = v
		}
	}
	proxyReq.Header.Set("X-Forwarded-For", r.RemoteAddr)

	// 发送请求
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(proxyReq)
	if err != nil {
		writeJSON(w, 502, "代理请求失败: "+err.Error(), nil)
		return
	}
	defer resp.Body.Close()

	// 复制响应头
	for k, v := range resp.Header {
		w.Header()[k] = v
	}
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)
}
