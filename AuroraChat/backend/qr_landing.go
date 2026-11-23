package main

import (
	"fmt"
	"html"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// ==================== 群二维码「应用外扫码」落地页 ====================
//
// 线上事故（2026-11-08 实测确认）：群二维码内容一直是 https://example.com/g.html?g=<群ID>，
// 但这个文件从来没有被创建过 —— 站点静态根目录没有 g.html，后端也没有对应路由：
//
//	https://example.com/g.html?g=1   → 404（nginx 静态 404）
//	https://www.example.com/g.html?g=1 → 404（同样是 nginx）
//	https://example.com/q.html?u=1     → 200（好友二维码的页面确实存在，且只认 u 参数）
//
// 也就是说群二维码从上线起就没有可用落地页，扫码的人只会看到一个 nginx 404 页面。
//
// 正解是把这个页面补上：仓库里已新增静态页 site/g.html（与 q.html 同一套视觉，部署到
// example.com 站点根目录即可，无需重启后端），App 生成的二维码仍指向 /g.html?g=<群ID>。
//
// 本文件是它的「后端备胎」：
//   - 静态文件万一丢了/没上传，nginx 的 try_files 可以转到这里（见 deploy/nginx-qr-landing.conf）；
//   - 服务端直出 HTML，不依赖 JS/fetch，弱网、禁 JS 的扫码器也能看到群信息。
//
// 第二个坑（决定了本文件为什么挂在 /api 下）：www 那台 nginx 只把特定前缀
// （/api/、/qrlogin、/tools/、/chat-media/、/ws）代理给后端，其余一律走静态根目录。
// 所以后端页面必须落在「一定被代理」的前缀里 —— 这里选 /api/qr/g（实测可达）。
//
// 路由：
//   GET /api/qr/g?g=<群ID>    → 群邀请页（后端直出，唯一的兜底地址）
//   GET /g?g=<群ID>           → 同名别名（nginx 放行 /g 后可用）
//   GET /g.html?g=<群ID>      → 同名别名（nginx 把静态缺失的 /g.html 转给后端时命中）
//
// 三条硬规则：
//  1. 参数缺失、群不存在 / 已解散 → 302 回官网首页，绝不停在 404；
//  2. 页面只展示公开信息（群头像/群名/群号/签名/人数），加群仍必须由已登录 App 内扫码触发；
//  3. 限流命中、DB 异常等任何失败同样 302 回官网首页 —— 落地页坏掉不该由扫码的人承担。

const (
	// siteHomeURL 官网首页：任何落地页失败时的统一出口（裸域静态站，实测 200）。
	siteHomeURL = qrSiteURL + "/"
	// apkDownloadURL 安卓安装包直链（客户端更新检查用的也是这条）。
	apkDownloadURL = "https://www.example.com/tools/AuroraChat.apk"
	// appIconURL 应用图标：与官网首页、App 内默认群头像同一张图。
	// 群没设自定义头像时直接显示它，不用文字占位。
	appIconURL = qrSiteURL + "/images/app-icon.jpg"
)

// redirectSiteHome 把扫码的人送回官网首页。
// 用 302 不用 301：落地页以后可能修好，别让浏览器把「跳首页」永久缓存下来。
func redirectSiteHome(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, siteHomeURL, http.StatusFound)
}

// qrLandingAllowed 落地页的宽松限流：只挡脚本狂刷，不影响真人扫码。
// 被限流也回官网首页，不返回 429 —— 扫码的人看不懂 429。
func qrLandingAllowed(r *http.Request) bool {
	ok, _ := checkSlidingLimit("qrlanding:"+getClientIP(r), time.Minute, 120)
	return ok
}

// writeGroupQrLanding 输出群邀请落地页（自包含 HTML：无外部 CSS/JS，弱网也能开）。
// avatarURI 是群头像的公开地址（为空表示该群没设头像，前端回退应用图标）。
func writeGroupQrLanding(w http.ResponseWriter, group *Group, memberCount int64, avatarURI string) {
	e := html.EscapeString

	name := strings.TrimSpace(group.Name)
	if name == "" {
		name = "未命名群聊"
	}
	// 群头像：有自定义头像就用自己的；没有就用应用图标，绝不做文字占位。
	avatarSrc := avatarURI
	if avatarSrc == "" {
		avatarSrc = appIconURL
	}
	avatarBlock := `<img class="avatar" src="` + e(avatarSrc) + `" alt="">`
	sigBlock := ""
	if s := strings.TrimSpace(group.Signature); s != "" {
		sigBlock = `<p class="sig">` + e(s) + `</p>`
	}
	// 群号、人数各占一枚标签，不用分隔符硬拼一行（排版更干净，也不会在窄屏折行折得难看）
	factsBlock := fmt.Sprintf(`<div class="facts"><span class="fact">群号 %d</span>`, group.DisplayID)
	if memberCount > 0 {
		factsBlock += fmt.Sprintf(`<span class="fact">%d 人</span>`, memberCount)
	}
	factsBlock += `</div>`

	page := `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">` +
		`<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">` +
		`<meta name="robots" content="noindex,nofollow">` +
		`<meta name="theme-color" content="#f1f6f5">` +
		`<title>加入 ` + e(name) + ` - Aurora Chat</title><style>` +
		`*{margin:0;padding:0;box-sizing:border-box}` +
		`body{font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:#16252b;` +
		`background:radial-gradient(760px 420px at 88% -12%,rgba(26,138,125,.16),transparent 62%),` +
		`radial-gradient(620px 380px at -6% 8%,rgba(198,158,92,.10),transparent 58%),#f1f6f5;` +
		`min-height:100vh;display:flex;align-items:center;justify-content:center;` +
		`padding:28px 20px;line-height:1.6}` +
		`.wrap{width:100%;max-width:392px}` +
		`.brand{display:flex;align-items:center;justify-content:center;gap:9px;margin-bottom:18px}` +
		`.brand-name{font-size:13.5px;font-weight:600;letter-spacing:.3px;color:#567076}` +
		`.card{background:#fff;border:1px solid #e8efed;border-radius:26px;padding:30px 24px 24px;` +
		`text-align:center;box-shadow:0 24px 56px -24px rgba(16,52,48,.30),0 2px 8px rgba(16,52,48,.04)}` +
		`.avatar{width:88px;height:88px;border-radius:24px;display:block;margin:0 auto 16px;` +
		`object-fit:cover;background:#e6f2ef;border:2px solid #eef6f4}` +
		`.name{font-size:21px;font-weight:700;letter-spacing:-.2px;line-height:1.4;word-break:break-word}` +
		`.sig{margin:8px auto 0;max-width:290px;font-size:13.5px;color:#74888f;` +
		`line-height:1.6;word-break:break-word}` +
		`.facts{display:flex;flex-wrap:wrap;align-items:center;justify-content:center;gap:8px;margin-top:14px}` +
		`.fact{font-size:12.5px;font-weight:500;color:#5c7278;background:#f3f8f7;` +
		`border:1px solid #e9f1ef;padding:4px 12px;border-radius:999px;white-space:nowrap}` +
		`.hint{margin-top:20px;padding:12px 14px;background:#f7faf9;border:1px solid #ecf3f1;` +
		`border-radius:14px;text-align:left;font-size:12.5px;color:#61777d;line-height:1.65}` +
		`.btn{display:block;text-align:center;text-decoration:none;font-size:15px;font-weight:600;` +
		`padding:13px 0;border-radius:14px;margin-top:10px}` +
		`.primary{background:linear-gradient(140deg,#1a8a7d,#23a091);color:#fff;` +
		`box-shadow:0 10px 22px -8px rgba(26,138,125,.55)}` +
		`.soft{background:#fff;color:#3d545b;border:1.5px solid #e8efed}` +
		`.foot{margin-top:20px;text-align:center;font-size:11.5px;color:#9dafb2;letter-spacing:.2px}` +
		`</style></head><body><div class="wrap">` +
		`<div class="brand"><span class="brand-name">Aurora Chat</span></div>` +
		`<div class="card">` +
		// 群头像走公开只读接口 /api/qr/group/avatar（读的就是上传落盘的那张图），
		// 不引 /api/groups/avatar/{id} —— 那条接口要登录，匿名扫码者拿到只有 401 破图。
		avatarBlock +
		`<div class="name">` + e(name) + `</div>` +
		sigBlock + factsBlock +
		`<div class="hint">打开 Aurora Chat，点右上角加号，选择扫一扫，扫描原群二维码即可加入。</div>` +
		`<a class="btn primary" href="` + e(apkDownloadURL) + `">下载 Android 版</a>` +
		`<a class="btn soft" href="` + e(siteHomeURL) + `">访问官网首页</a>` +
		`</div>` +
		`<div class="foot">本页面仅展示群聊的公开信息</div>` +
		`</div></body></html>`

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(page))
}

// handleGroupQrLanding 群二维码落地页。
// 注册在 /api/qr/g（实测可达）、/g 与 /g.html（别名，供 nginx 放行 / 老码兼容）。
func handleGroupQrLanding(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !qrLandingAllowed(r) {
		redirectSiteHome(w, r)
		return
	}
	gidStr := strings.TrimSpace(r.URL.Query().Get("g"))
	if gidStr == "" {
		gidStr = strings.TrimSpace(r.URL.Query().Get("gid"))
	}
	gid, err := strconv.ParseInt(gidStr, 10, 64)
	if err != nil || gid <= 0 {
		redirectSiteHome(w, r)
		return
	}
	group, err := getGroupByID(gid)
	if err != nil || group == nil {
		// 群已解散 / ID 不存在：不留 404，回官网首页
		redirectSiteHome(w, r)
		return
	}
	var memberCount int64
	if db != nil {
		db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ?", gid).Scan(&memberCount)
	}
	writeGroupQrLanding(w, group, memberCount, groupPublicAvatarURL(gid))
}

// handleSiteFallback 兜底：任何没被注册的路径都不该停在裸 404 上。
//   - /api/*        → JSON 404（接口调用方要结构化错误，不能给 HTML）
//   - "/"           → 保持 404：官网首页由静态站点提供，重定向到这里会形成死循环
//   - 其余 GET/HEAD → 302 回官网首页（打错的链接、失效的老页面、被删的资源）
//   - 非 GET/HEAD   → 原样 404
//
// 注意：www 那台 nginx 目前只把部分前缀代理到后端，走静态根目录的 404 到不了这里，
// 那一层需要 nginx 的 error_page 兜底（见 deploy/nginx-aurora.conf）。
func handleSiteFallback(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	if p == "/" {
		http.NotFound(w, r)
		return
	}
	if p == "/api" || strings.HasPrefix(p, "/api/") {
		writeJSON(w, 404, "接口不存在", nil)
		return
	}
	if r.Method == http.MethodGet || r.Method == http.MethodHead {
		redirectSiteHome(w, r)
		return
	}
	http.NotFound(w, r)
}
