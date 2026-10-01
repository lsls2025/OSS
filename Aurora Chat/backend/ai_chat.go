package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
)

// SolitaryCryAI 第三方免费 AI（由用户ID 13的用户提供）。
// 地址 / 模型放在服务端；5 个 key 纯轮流（round-robin，非随机）使用，避免单个 key 被限流。
// 万一失效只需改下面的配置并重启后端，不用改客户端。
var solitaryCryAI = struct {
	apiKeys []string
	baseURL string
	model   string
	keyIdx  uint64
}{
	apiKeys: []string{
		"YOUR_API_KEY_1",
		"YOUR_API_KEY_2",
		"YOUR_API_KEY_3",
		"YOUR_API_KEY_4",
		"YOUR_API_KEY_5",
	},
	baseURL: "https://YOUR_AI_PROVIDER_HOST/v1",
	model:   "agnes-2.5-flash",
}

// nextAPIKey 原子自增下标，纯轮流返回下一个 key：第一次取第0个、第二次取第1个…循环。
// 并发安全，任何时刻所有请求看到的都是按序分配，不会重复命中同一个 key。
func nextAPIKey() string {
	if len(solitaryCryAI.apiKeys) == 0 {
		return ""
	}
	i := atomic.AddUint64(&solitaryCryAI.keyIdx, 1) - 1
	return solitaryCryAI.apiKeys[i%uint64(len(solitaryCryAI.apiKeys))]
}

// handleThirdPartyChat 把客户端的聊天请求原样（messages）转发给 SolitaryCryAI，
// 并用服务端配置强制覆盖 model 和鉴权，再把上游的流式 SSE 原样推回给客户端。
// 客户端沿用 OpenAI / DeepSeek 兼容的流式解析，无需改协议。
func handleThirdPartyChat(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, 8<<20))
	if err != nil {
		writeJSON(w, 400, "请求体读取失败", nil)
		return
	}
	r.Body.Close()

	var parsed map[string]interface{}
	if err := json.Unmarshal(body, &parsed); err != nil {
		writeJSON(w, 400, "请求体格式错误", nil)
		return
	}
	messages, ok := parsed["messages"].([]interface{})
	if !ok || len(messages) == 0 {
		writeJSON(w, 400, "缺少 messages", nil)
		return
	}

	// 向后端记录的调用者（Optional，用于排查）
	caller := r.Header.Get("X-User-ID")

	// 组装转发给 SolitaryCryAI 的请求体：model 固定用服务端配置，客户端传的 model 忽略
	outPayload := map[string]interface{}{
		"model":    solitaryCryAI.model,
		"messages": messages,
		"stream":   true,
		"stream_options": map[string]interface{}{
			"include_usage": true,
		},
	}
	// 透传客户端发来的 tools / tool_choice（Agent 原生 function calling）
	// 让上游模型知道有哪些工具可调用，否则模型只能把工具 JSON 当普通文本输出
	if tools, ok := parsed["tools"]; ok && tools != nil {
		outPayload["tools"] = tools
	}
	if tc, ok := parsed["tool_choice"]; ok && tc != nil {
		outPayload["tool_choice"] = tc
	}
	outBytes, err := json.Marshal(outPayload)
	if err != nil {
		writeJSON(w, 500, "转发数据构造失败", nil)
		return
	}

	endpoint := strings.TrimRight(solitaryCryAI.baseURL, "/") + "/chat/completions"
	client := &http.Client{Timeout: 300 * time.Second}
	upReq, err := http.NewRequestWithContext(r.Context(), http.MethodPost, endpoint, strings.NewReader(string(outBytes)))
	if err != nil {
		writeJSON(w, 500, "上游请求构造失败", nil)
		return
	}
	upReq.Header.Set("Content-Type", "application/json")
	upReq.Header.Set("Authorization", "Bearer "+nextAPIKey())
	upReq.Header.Set("Accept-Encoding", "identity")

	resp, err := client.Do(upReq)
	if err != nil {
		writeJSON(w, 502, "第三方 AI 服务暂不可用", nil)
		return
	}
	defer resp.Body.Close()

	// 原样转发上游状态码；把错误（如余额不足 402）也一样透传给客户端，便于客户端判断
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("Connection", "keep-alive")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(resp.StatusCode)

	flusher, _ := w.(http.Flusher)
	buf := make([]byte, 8192)
	total := int64(0)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				break
			}
			total += int64(n)
			if flusher != nil {
				flusher.Flush()
			}
		}
		if rerr != nil {
			break
		}
	}
	fmt.Printf("[SolitaryCryAI] caller=%s upstream=%d 转发 %d 字节\n", caller, resp.StatusCode, total)
}

// 图片/视频生成模型：与对话模型同属 Agnes，密钥共用服务端配置。
var (
	imageModel = "agnes-image-2.1-flash"
	videoModel = "agnes-video-v2.0"
)

// forwardAgnesGeneration 把客户端的生成请求转发到 Agnes 的生成类接口（图片/视频），
// 强制覆盖 model 与鉴权，并把上游 JSON 响应包进 {code,data} 返回给客户端（客户端自行解析 data.data）。
func forwardAgnesGeneration(w http.ResponseWriter, r *http.Request, path, model string) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 16<<20))
	if err != nil {
		writeJSON(w, 400, "请求体读取失败", nil)
		return
	}
	r.Body.Close()

	var parsed map[string]interface{}
	if err := json.Unmarshal(body, &parsed); err != nil {
		writeJSON(w, 400, "请求体格式错误", nil)
		return
	}
	// 强制用服务端模型，客户端传的 model 忽略
	parsed["model"] = model
	outBytes, err := json.Marshal(parsed)
	if err != nil {
		writeJSON(w, 500, "转发数据构造失败", nil)
		return
	}

	endpoint := strings.TrimRight(solitaryCryAI.baseURL, "/") + path
	client := &http.Client{Timeout: 300 * time.Second}
	upReq, err := http.NewRequestWithContext(r.Context(), http.MethodPost, endpoint, strings.NewReader(string(outBytes)))
	if err != nil {
		writeJSON(w, 500, "上游请求构造失败", nil)
		return
	}
	upReq.Header.Set("Content-Type", "application/json")
	upReq.Header.Set("Authorization", "Bearer "+nextAPIKey())

	resp, err := client.Do(upReq)
	if err != nil {
		writeJSON(w, 502, "第三方 AI 生成服务暂不可用", nil)
		return
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		writeJSON(w, 502, "读取上游响应失败", nil)
		return
	}
	var out map[string]interface{}
	if err := json.Unmarshal(respBody, &out); err != nil {
		writeJSON(w, 502, "上游响应解析失败", nil)
		return
	}
	// 诊断：打印图片上游响应结构（不含 base64 内容），便于排查返回格式。
	if path == "/images/generations" {
		if dataArr, ok := out["data"].([]interface{}); ok && len(dataArr) > 0 {
			if m, ok := dataArr[0].(map[string]interface{}); ok {
				keys := make([]string, 0, len(m))
				for k := range m {
					keys = append(keys, k)
				}
				fmt.Printf("[SolitaryCryAI] 图片上游响应 data[0] keys=%v\n", keys)
			}
		} else {
			fmt.Printf("[SolitaryCryAI] 图片上游响应无 data 数组，raw=%.300s\n", respBody)
		}
	}
	// 图片生成：Agnes 的 b64_json 可能为空串、url 客户端可能下载不了，且 2MB base64 走手机网络易被中断。
	// 后端：解析出图片字节 → 直接存到本服务 chat_media → 只回一个小 media_path，客户端走正常 /chat-media 渲染。
	if path == "/images/generations" {
		if dataArr, ok := out["data"].([]interface{}); ok {
			for idx, item := range dataArr {
				m, ok := item.(map[string]interface{})
				if !ok {
					continue
				}
				// 1) 解析出最终图片字节
				var imgBytes []byte
				if bs, ok := m["b64_json"].(string); ok && bs != "" {
					if dec, err := base64.StdEncoding.DecodeString(bs); err == nil {
						imgBytes = dec
					}
				}
				if len(imgBytes) == 0 {
					if u, ok := m["url"].(string); ok && u != "" {
						if b64s, err := fetchAsBase64(u); err == nil {
							if dec, err := base64.StdEncoding.DecodeString(b64s); err == nil {
								imgBytes = dec
							}
						} else {
							fmt.Printf("[SolitaryCryAI] 图片回源下载失败 url=%s err=%v\n", u, err)
						}
					}
				}
				if len(imgBytes) == 0 {
					fmt.Printf("[SolitaryCryAI] 图片第%d张无可用字节\n", idx)
					continue
				}
				// 2) 存到 chat_media，与聊天图同一目录，客户端走 /chat-media 渲染
				name := fmt.Sprintf("ai_%d_%d_%s.jpg", time.Now().Unix(), 13, randomString(8))
				os.MkdirAll(chatMediaDir, 0755)
				if err := os.WriteFile(filepath.Join(chatMediaDir, name), imgBytes, 0644); err != nil {
					fmt.Printf("[SolitaryCryAI] 保存图片失败: %v\n", err)
					continue
				}
				// 3) 用小的 media_path 替换掉大 base64
				m["media_path"] = "/chat-media/" + name
				delete(m, "b64_json")
				delete(m, "url")
				fmt.Printf("[SolitaryCryAI] 图片已落盘 %s bytes=%d\n", name, len(imgBytes))
			}
		}
	}
	writeJSON(w, resp.StatusCode, "", out)
}

// fetchAsBase64 下载 url 并编码为 base64（仅用于图片回源，视频不在此列）。
// 携带 Agnes 鉴权头：生成图 CDN 可能要求 Bearer 才放行，空 body 视为失败。
func fetchAsBase64(url string) (string, error) {
	c := &http.Client{Timeout: 90 * time.Second}
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+nextAPIKey())
	resp, err := c.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("download http %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return "", err
	}
	if len(b) == 0 {
		return "", fmt.Errorf("empty body")
	}
	fmt.Printf("[SolitaryCryAI] 回源下载成功 bytes=%d\n", len(b))
	return base64.StdEncoding.EncodeToString(b), nil
}

// handleThirdPartyImage 图片生成（文生图）：POST /api/ai/image，body: {prompt,size,...}
func handleThirdPartyImage(w http.ResponseWriter, r *http.Request) {
	forwardAgnesGeneration(w, r, "/images/generations", imageModel)
}

// handleThirdPartyVideo 视频生成（文生视频）：POST /api/ai/video，body: {prompt,...}
func handleThirdPartyVideo(w http.ResponseWriter, r *http.Request) {
	forwardAgnesGeneration(w, r, "/video/generations", videoModel)
}

// extractVideoURL 从视频任务对象里尽量取出成片地址（兼容多种字段名）。
func extractVideoURL(task map[string]interface{}) string {
	for _, k := range []string{"url", "video_url", "output", "file_url"} {
		if u, ok := task[k].(string); ok && u != "" {
			return u
		}
	}
	if arr, ok := task["data"].([]interface{}); ok && len(arr) > 0 {
		if m, ok := arr[0].(map[string]interface{}); ok {
			if u, ok := m["url"].(string); ok && u != "" {
				return u
			}
		}
	}
	return ""
}

// fetchBytes 下载原始字节（视频成片等二进制），带鉴权头。
func fetchBytes(target string) ([]byte, error) {
	req, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+nextAPIKey())
	client := &http.Client{Timeout: 120 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("download http %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	if err != nil {
		return nil, err
	}
	return b, nil
}

// handleThirdPartyVideoStatus 视频任务状态轮询：GET /api/ai/video/status/<taskId>
// 转发 Agnes /video/tasks/<taskId>；完成后下载成片存 chat_media 并返回 media_path。
func handleThirdPartyVideoStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	taskID := strings.TrimPrefix(r.URL.Path, "/api/ai/video/status/")
	taskID = strings.Trim(taskID, "/")
	if taskID == "" {
		writeJSON(w, 400, "缺少 task_id", nil)
		return
	}
	upstream := solitaryCryAI.baseURL + "/video/tasks/" + url.PathEscape(taskID)
	req, err := http.NewRequest(http.MethodGet, upstream, nil)
	if err != nil {
		writeJSON(w, 500, "构造请求失败", nil)
		return
	}
	req.Header.Set("Authorization", "Bearer "+nextAPIKey())
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		writeJSON(w, 502, "上游请求失败", nil)
		return
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		writeJSON(w, 502, "上游读取失败", nil)
		return
	}
	var task map[string]interface{}
	if err := json.Unmarshal(body, &task); err != nil {
		writeJSON(w, 502, "上游响应解析失败", nil)
		return
	}
	status, _ := task["status"].(string)
	if strings.EqualFold(status, "completed") || strings.EqualFold(status, "succeeded") {
		if videoURL := extractVideoURL(task); videoURL != "" {
			if vid, err := fetchBytes(videoURL); err == nil && len(vid) > 0 {
				name := fmt.Sprintf("ai_%d_%d_%s.mp4", time.Now().Unix(), 13, randomString(8))
				os.MkdirAll(chatMediaDir, 0755)
				if err := os.WriteFile(filepath.Join(chatMediaDir, name), vid, 0644); err == nil {
					task["media_path"] = "/chat-media/" + name
					fmt.Printf("[SolitaryCryAI] 视频已落盘 %s bytes=%d\n", name, len(vid))
				} else {
					fmt.Printf("[SolitaryCryAI] 视频写盘失败: %v\n", err)
				}
			} else {
				fmt.Printf("[SolitaryCryAI] 视频下载失败 url=%s err=%v\n", videoURL, err)
			}
		}
	}
	writeJSON(w, 200, "", task)
}
