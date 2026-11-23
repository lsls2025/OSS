package main

import (
	"bufio"
	"bytes"
	"context"
	"database/sql"
	_ "embed"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ============================================================
// 短视频模块
//
// 架构（严格按需求落地）：
//   - 多码率自适应：每个视频上传后由 ffmpeg 转码出 240/360/480/720/1080 五档 HLS 变体，
//     生成 master playlist（playlist.m3u8），客户端（或 Android MediaPlayer）按带宽自动选档。
//   - 切片按需加载：HLS 天然"按分片拉取、不整片下载"，playlist 走 no-cache、.ts 分片走长缓存。
//   - 短 GOP + 质量优先转码：固定 2s GOP（g=48/keyint_min=48/sc_threshold=0）+ preset medium（清晰度拐点，消除重编码档发软）。
//   - 缓存/请求优化：分片 URL 带 ETag（基于 mtime+size），支持 304；.ts 长缓存命中率由客户端/反向代理复用；
//     边缘化（CDN）缓存与一致性哈希/LRU 属于边缘层，原站只保证 HTTP 语义正确即可。
//   - 省带宽取向：默认以 H.264（兼容性最佳、主流短视频交付编码）为基准；HEVC/AV1 在 Android 解码兼容性与
//     许可上不稳定，故留作可扩展字段（renditions.codec），后续可在转码里追加对应变体。
// ============================================================

// ---------- 参数读取（兼容 query / 表单 / JSON body） ----------
// App 端统一用 application/json 提交，Go 的 r.FormValue 只解析表单和 query，
// 读不到 JSON body 里的字段（表现为"缺少 id"），所以这里统一走 svParam。
func svParam(r *http.Request, key string) string {
	if v := r.FormValue(key); v != "" {
		return v
	}
	if r.Body == nil {
		return ""
	}
	data, err := io.ReadAll(r.Body)
	if err != nil {
		return ""
	}
	r.Body = io.NopCloser(bytes.NewReader(data)) // 还原 body，后续仍可再读
	var m map[string]interface{}
	if json.Unmarshal(data, &m) != nil {
		return ""
	}
	v, ok := m[key]
	if !ok || v == nil {
		return ""
	}
	switch t := v.(type) {
	case string:
		return t
	case float64:
		return strconv.FormatInt(int64(t), 10)
	case bool:
		return strconv.FormatBool(t)
	default:
		return fmt.Sprint(t)
	}
}

// ---------- 存储目录 ----------
var shortVideoDataDir string   // .../data/shortvideo
var shortVideoVideosDir string // .../data/shortvideo/videos（原始文件）
var shortVideoHlsDir string    // .../data/shortvideo/hls（<id>/playlist.m3u8 + out_*.m3u8 + *.ts + cover.jpg）

func InitShortVideoStorage(baseDir string) {
	shortVideoDataDir = filepath.Join(baseDir, "data", "shortvideo")
	shortVideoVideosDir = filepath.Join(shortVideoDataDir, "videos")
	shortVideoHlsDir = filepath.Join(shortVideoDataDir, "hls")
	for _, d := range []string{shortVideoVideosDir, shortVideoHlsDir} {
		if err := os.MkdirAll(d, 0755); err != nil {
			log.Printf("[短视频] 创建目录失败 %s: %v", d, err)
		}
	}
	log.Printf("短视频存储目录: %s", shortVideoDataDir)
	// 迁移修复历史坏记录（旧版缺 cover_path 列 → 转码完成的 UPDATE 整条失败 → status 永远为 0 → 永远不出现在 feed）
	repairShortVideoOrphans()
}

// repairShortVideoOrphans 修复历史坏记录：status=0 但 HLS 产物其实已生成的记录，补全 play_url/cover/duration
// 并置为已发布（status=1）；若只有原文件则降级为渐进式播放。避免老数据永远不出现在 feed。
func repairShortVideoOrphans() {
	rows, err := db.Query(`SELECT id, orig_path FROM shortvideo_videos WHERE status = 0`)
	if err != nil {
		return
	}
	type rec struct {
		id   int64
		orig string
	}
	var list []rec
	for rows.Next() {
		var r rec
		if rows.Scan(&r.id, &r.orig) == nil {
			list = append(list, r)
		}
	}
	rows.Close()
	for _, r := range list {
		dir := filepath.Join(shortVideoHlsDir, strconv.FormatInt(r.id, 10))
		if _, err := os.Stat(filepath.Join(dir, "playlist.m3u8")); err == nil {
			dur := 0
			dw, dh := 0, 0
			if r.orig != "" {
				p := filepath.Join(shortVideoVideosDir, r.orig)
				dur = probeShortVideoDuration(p)
				dw, dh = probeShortVideoDisplaySize(p)
			}
			db.Exec(`UPDATE shortvideo_videos SET play_url = ?, is_hls = 1, cover_path = ?, duration = ?, display_w = ?, display_h = ?, status = 1 WHERE id = ?`,
				shortVideoPlayURL(r.id, true), shortVideoHlsRel(r.id, "cover.jpg"), dur, dw, dh, r.id)
			var cnt int
			db.QueryRow(`SELECT COUNT(*) FROM shortvideo_renditions WHERE video_id = ?`, r.id).Scan(&cnt)
			if cnt == 0 {
				insertShortVideoRenditions(r.id, dw, dh)
			}
			log.Printf("[短视频] 迁移修复 vid=%d → 已发布（HLS 已存在）", r.id)
		} else if r.orig != "" {
			if _, e := os.Stat(filepath.Join(shortVideoVideosDir, r.orig)); e == nil {
				finalizeShortVideoFallback(r.id, filepath.Join(shortVideoVideosDir, r.orig))
				log.Printf("[短视频] 迁移修复 vid=%d → 降级为渐进式播放", r.id)
			}
		}
	}

	// 已发布的 HLS 记录：补齐 orig.mp4 兜底文件（客户端 HLS 解析失败时可自动回退）
	prows, err := db.Query(`SELECT id, orig_path FROM shortvideo_videos WHERE status = 1 AND is_hls = 1`)
	if err != nil {
		return
	}
	type hrec struct {
		id   int64
		orig string
	}
	var hlist []hrec
	for prows.Next() {
		var r hrec
		if prows.Scan(&r.id, &r.orig) == nil && r.orig != "" {
			hlist = append(hlist, r)
		}
	}
	prows.Close()
	for _, r := range hlist {
		linkOrCopy(
			filepath.Join(shortVideoVideosDir, r.orig),
			filepath.Join(shortVideoHlsDir, strconv.FormatInt(r.id, 10), "orig.mp4"),
		)
	}

	// 存量修复（回滚 + 补转码）由 main.go 在 PurgeBrokenShortVideos 之后统一调用
	// rollbackAndHealShortVideos 完成，这里不再重复触发，避免两条链路互相踩。
}

// rollbackAndHealShortVideos 上线自愈（两步。铁律：宁可回到旧状态，也绝不把坏文件留给用户）。
//
// 背景事故链：兼容转码命令漏映射视频流 → 产出纯音频 MP4 → 客户端"有音乐、只有一帧封面"，
// 而退出码 0、文件 >4KB、playlist 校验全是绿的，根本发现不了。修复版命令虽已立三条铁律
// （显式 -map 0:v:0、mp4HasVideoTrack 复核、编码器降级链），存量坏文件还得有人善后。
//
// 第一步（同步，毫秒级，先于任何请求）：把"当前播放目标没有视频轨"的记录全部复位到
// orig.mp4——立刻回到"大部分视频能播"的旧状态。不依赖 ffmpeg、不依赖转码成败；
// 且播放 URL 从 compat.mp4 变成 orig.mp4，天然绕过客户端磁盘缓存里的坏副本。
//
// 第二步（后台，串行，错峰 3s）：只对"源非 H.264 且当前在原文件上"的记录补跑三级转码链。
// H.264 源 orig.mp4 本身全设备可播，不重编码、不烧 CPU；已在 compat 上的说明修过，不重复。
// 成功的切到带缓存击穿参数的 compat.mp4；失败的保持 orig.mp4——任何结果都不会比回滚态更差。
func rollbackAndHealShortVideos() {
	if exe, err := os.Executable(); err == nil {
		if fi, e := os.Stat(exe); e == nil {
			log.Printf("[短视频] 上线自愈模块就绪（二进制修改时间 %s，大小 %d 字节）",
				fi.ModTime().Format("2006-01-02 15:04:05"), fi.Size())
		}
	}
	rows, err := db.Query(`SELECT id, orig_path, play_url FROM shortvideo_videos
		WHERE status = 1 AND is_hls = 0 AND orig_path != ''`)
	if err != nil {
		return
	}
	type rec struct {
		id      int64
		orig    string
		playURL string
	}
	var list []rec
	for rows.Next() {
		var r rec
		if rows.Scan(&r.id, &r.orig, &r.playURL) == nil {
			list = append(list, r)
		}
	}
	rows.Close()

	var needRepair []rec
	rolled := 0
	for _, r := range list {
		origPath := filepath.Join(shortVideoVideosDir, r.orig)
		if _, e := os.Stat(origPath); e != nil {
			continue // 原文件都没了，归 PurgeBrokenShortVideos 管
		}
		// 第一步：当前播放目标坏了（没视频轨/文件没了）→ 回滚 orig.mp4
		local := shortVideoLocalPath(r.playURL)
		broken := true
		if local != "" {
			if fi, e := os.Stat(local); e == nil && !fi.IsDir() {
				broken = !mp4HasVideoTrack(local)
			}
		}
		if broken {
			db.Exec(`UPDATE shortvideo_videos SET play_url = ?, is_hls = 0 WHERE id = ?`,
				shortVideoPlayURL(r.id, false), r.id)
			r.playURL = shortVideoPlayURL(r.id, false) // 同步内存值，供下面的修复判断
			rolled++
			log.Printf("[短视频] 回滚 vid=%d → orig.mp4（原播放目标无视频轨）", r.id)
		}
		// 第二步候选：源非 H.264（HEVC/AV1 等，设备不一定解）且当前在原文件上
		srcCodec := probeShortVideoVideoCodec(origPath)
		if srcCodec != "h264" && srcCodec != "" && strings.HasSuffix(r.playURL, "/orig.mp4") {
			needRepair = append(needRepair, r)
		}
	}
	if rolled > 0 {
		log.Printf("[短视频] 回滚完成：%d 条记录已复位到 orig.mp4（恢复可播状态）", rolled)
	}
	if len(needRepair) == 0 {
		return
	}
	log.Printf("[短视频] %d 条非 H.264 源视频需要兼容转码，后台开始（失败保持原文件，不会更坏）", len(needRepair))
	go func() {
		for _, r := range needRepair {
			log.Printf("[短视频] 兼容转码重试 vid=%d", r.id)
			transcodeShortVideo(r.id, filepath.Join(shortVideoVideosDir, r.orig))
			time.Sleep(3 * time.Second)
		}
		log.Printf("[短视频] 兼容转码重试结束")
	}()
}

// shortVideoLocalPath 把 play_url（/api/shortvideo/hls/<id>/<file>[?v=ts]）映射回本地路径。
// 带防目录穿越校验：rel 必须形如 "<id>/<文件名>"，否则返回空串。
// 注意去掉缓存击穿参数（?v=...）：repair 切到 compat.mp4 时会带该参数绕过客户端缓存，
// 落盘路径里没有它。
func shortVideoLocalPath(playURL string) string {
	const prefix = "/api/shortvideo/hls/"
	if !strings.HasPrefix(playURL, prefix) {
		return ""
	}
	rel := strings.TrimPrefix(playURL, prefix)
	if q := strings.IndexByte(rel, '?'); q >= 0 {
		rel = rel[:q]
	}
	if strings.Contains(rel, "..") || strings.Count(rel, "/") != 1 {
		return ""
	}
	return filepath.Join(shortVideoHlsDir, rel)
}

// insertShortVideoRenditions 写入五档码率元数据（与 runFFmpegHLS 生成的 out_0..4 一一对应）。
// srcW/srcH 是源视频的显示尺寸（已转正）：档位宽高按同一套「等比缩进盒子、不许补边」规则算出来，
// 否则竖屏视频的 720 档实际是 406x720，库里却写 1280x720，客户端按后者设码率上限就选错档。
// 先删后插：转码/重转可以反复执行，不会把档位记录堆成两倍。
func insertShortVideoRenditions(vid int64, srcW, srcH int) {
	// 盒子必须是正方形：用 16:9 的 W×H 当盒子时，竖屏源会被按高度卡死
	//（720x1280 的 1080 档只能出 607x1080），客户端再放大上屏就是糊的。
	// 方形盒子等价于「限制短边、长边同比」，横屏产物与原来一致，竖屏不再降分辨率。
	// 三档：低 480 / 中 1080 / 高 2160（原画，产物 = 源分辨率，4K 封顶）。
	// br 一律记 0：三档都是 CRF 变码率，没有固定码率可参考，
	// 客户端看到 0 就理解为「不设码率上限」，不要拿它当上限去过滤轨道。
	qualities := []struct{ q, br, boxW, boxH int }{
		{480, 0, 854, 854},
		{1080, 0, 1920, 1920},
		{2160, 0, 3840, 3840},
	}
	db.Exec(`DELETE FROM shortvideo_renditions WHERE video_id = ?`, vid)
	dir := filepath.Join(shortVideoHlsDir, strconv.FormatInt(vid, 10))
	for i, q := range qualities {
		w, h := q.boxW, q.boxH
		if srcW > 0 && srcH > 0 {
			w, h = svTierSize(srcW, srcH, q.boxW, q.boxH)
		}
		pl := shortVideoHlsRel(vid, fmt.Sprintf("out_%d.m3u8", i))
		// 优先用真实产物尺寸：ffmpeg 的取整细节（decrease 到底是 floor 还是 round）不值得去猜，
		// 差 2 像素就可能让客户端的 setMaxVideoSize 把这一档判成"超限"而选错档。
		if aw, ah := probeShortVideoMediaSize(filepath.Join(dir, fmt.Sprintf("out_%d.m3u8", i))); aw > 0 && ah > 0 {
			w, h = aw, ah
		}
		db.Exec(`INSERT INTO shortvideo_renditions (video_id, quality, codec, bitrate, width, height, playlist)
			VALUES (?, ?, 'h264', ?, ?, ?, ?)`, vid, q.q, q.br, w, h, pl)
	}
}

// svFitFilter 生成「等比缩进 W×H 盒子」的滤镜链，产出画面比例 == 源显示比例。
//
// 【为什么绝不能 pad】
// 早期实现是 scale=...decrease,pad=W:H 把每一档都补成固定 16:9 画布。pad 会把这个比例
// 永久烤进画面像素：竖屏 720x1280 的源，240 档变成 426x240 的画布、真实画面只有 135x240。
// 客户端拿到的 VideoSize 是 426x240 → 按 16:9 排容器 → 在竖屏手机上渲染成 1080x608 的横带，
// 有效画面只有应得的约 10%，用户看到的就是"视频莫名其妙变得极小"，且这个信息在编码后不可逆。
//
// 四段各自的作用：
//  1. scale=trunc(iw*sar/2)*2:ih —— 先把变宽像素（SAR≠1，老 DVD/部分录屏）展开成方形像素，
//     否则像素比例和显示比例不一致，等比缩放出来还是歪的；
//  2. setsar=1 —— 标记为方形像素；
//  3. scale='min(iw,W)':'min(ih,H)':force_original_aspect_ratio=decrease —— 等比缩进盒子。
//     加 min() 是为了"只缩不放"：低清源不该被放大到档位尺寸，那只会白白增加码率；
//  4. scale=trunc(iw/2)*2:trunc(ih/2)*2 —— 宽高取偶。奇数宽高会让 libx264 + yuv420p 直接报错。
func svFitFilter(w, h int) string {
	// 不要再用 iw*sar 展开像素比：大量 MP4 的 SAR 是 0:1 或未指定，
	// iw*0/2 = 0 → 宽度算成 0 → ffmpeg 直接报 "Invalid size"，
	// 三档里挂一路就整体失败，最后降级成单档，用户切画质毫无反应。
	// 直接 setsar=1 把像素比声明成方形，再按 iw/ih 等比缩放即可；
	// 对 SAR=1 的普通视频结果完全一样，对 SAR=0 的异常文件则是唯一的活路。
	return fmt.Sprintf(
		"setsar=1,"+
			"scale='min(iw,%d)':'min(ih,%d)':force_original_aspect_ratio=decrease,"+
			"scale=trunc(iw/2)*2:trunc(ih/2)*2", w, h)
}

// svTierSize 用与 svFitFilter 完全相同的规则，算某一档的真实输出宽高（Go 侧镜像实现）。
// 用途：写 renditions 元数据、以及转码后核对产物比例是否符合预期。
func svTierSize(srcW, srcH, boxW, boxH int) (int, int) {
	if srcW <= 0 || srcH <= 0 {
		return boxW, boxH
	}
	ew, eh := boxW, boxH
	if srcW < ew {
		ew = srcW
	}
	if srcH < eh {
		eh = srcH
	}
	if ew < 2 || eh < 2 {
		return boxW, boxH
	}
	r := math.Min(float64(ew)/float64(srcW), float64(eh)/float64(srcH))
	w := int(math.Round(float64(srcW) * r))
	h := int(math.Round(float64(srcH) * r))
	w -= w % 2
	h -= h % 2
	if w < 2 {
		w = 2
	}
	if h < 2 {
		h = 2
	}
	return w, h
}

// ---------- 数据库 ----------
func InitShortVideoDB() {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS shortvideo_videos (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL,
			title TEXT NOT NULL DEFAULT '',
			description TEXT NOT NULL DEFAULT '',
			orig_path TEXT NOT NULL DEFAULT '',
			play_url TEXT NOT NULL DEFAULT '',
			cover_path TEXT NOT NULL DEFAULT '',
			is_hls INTEGER NOT NULL DEFAULT 0,
			duration INTEGER NOT NULL DEFAULT 0,
			display_w INTEGER NOT NULL DEFAULT 0,
			display_h INTEGER NOT NULL DEFAULT 0,
			direct_path TEXT NOT NULL DEFAULT '',
			view_count INTEGER NOT NULL DEFAULT 0,
			status INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL
		)`,
		`CREATE INDEX IF NOT EXISTS idx_sv_time ON shortvideo_videos(created_at DESC)`,
		`CREATE TABLE IF NOT EXISTS shortvideo_renditions (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			video_id INTEGER NOT NULL,
			quality INTEGER NOT NULL,
			codec TEXT NOT NULL DEFAULT 'h264',
			bitrate INTEGER NOT NULL DEFAULT 0,
			width INTEGER NOT NULL DEFAULT 0,
			height INTEGER NOT NULL DEFAULT 0,
			playlist TEXT NOT NULL DEFAULT ''
		)`,
		`CREATE INDEX IF NOT EXISTS idx_sv_rendition_video ON shortvideo_renditions(video_id)`,
		`CREATE TABLE IF NOT EXISTS shortvideo_likes (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			video_id INTEGER NOT NULL,
			user_id INTEGER NOT NULL,
			created_at INTEGER NOT NULL,
			UNIQUE(video_id, user_id)
		)`,
		`CREATE TABLE IF NOT EXISTS shortvideo_comments (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			video_id INTEGER NOT NULL,
			user_id INTEGER NOT NULL,
			content TEXT NOT NULL,
			parent_id INTEGER NOT NULL DEFAULT 0,
			is_pinned INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS shortvideo_comment_likes (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			comment_id INTEGER NOT NULL,
			user_id INTEGER NOT NULL,
			created_at INTEGER NOT NULL,
			UNIQUE(comment_id, user_id)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_sv_comment_likes ON shortvideo_comment_likes(comment_id)`,
	}
	for _, s := range stmts {
		if _, err := db.Exec(s); err != nil {
			log.Printf("[短视频] 建表失败: %v", err)
		}
	}
	// 兼容旧库：早期建表漏了 cover_path 列，导致转码完成的整条 UPDATE 报错、status 永远停在 0，feed 永远为空。
	// SQLite 的 ADD COLUMN 在列已存在时会报错，忽略即可（新库已在建表语句里带上该列）。
	if _, err := db.Exec(`ALTER TABLE shortvideo_videos ADD COLUMN cover_path TEXT NOT NULL DEFAULT ''`); err != nil {
		log.Printf("[短视频] cover_path 列已存在，跳过迁移")
	}
	// 显示尺寸（已应用旋转后的真实宽高）：客户端首帧前就靠它排布容器，
	// 不用再"默认 9:16 → 封面比例 → VideoSize"三段猜测，从根上消除尺寸跳变。
	for _, col := range []string{"display_w", "display_h"} {
		if _, err := db.Exec(`ALTER TABLE shortvideo_videos ADD COLUMN ` + col + ` INTEGER NOT NULL DEFAULT 0`); err != nil {
			log.Printf("[短视频] %s 列已存在，跳过迁移", col)
		}
	}
	if _, err := db.Exec(`ALTER TABLE shortvideo_videos ADD COLUMN direct_path TEXT NOT NULL DEFAULT ''`); err != nil {
		log.Printf("[短视频] direct_path 列已存在，跳过迁移")
	}
	// 评论置顶：0=普通，1=置顶。同一个视频同时最多一条置顶（由置顶接口保证）。
	//
	// 这里必须区分两种失败：列已经存在（正常）vs 真的加不上（表缺失/库只读）。
	// 以前一律打印"列已存在"，结果列真的没加上时，评论查询会一直 500，
	// 日志却看不出任何异常 —— 表现就是"评论区永远是 0 条"。
	if _, err := db.Exec(`ALTER TABLE shortvideo_comments ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0`); err != nil {
		// 直接用一刀简单查询验证列是否真的可用（比 pragma 兼容性更好）
		if probeRows, e2 := db.Query(`SELECT is_pinned FROM shortvideo_comments LIMIT 1`); e2 != nil {
			log.Printf("[短视频] 严重：is_pinned 列不存在且添加失败，评论列表/置顶会全部报错: %v", err)
		} else {
			probeRows.Close()
			log.Printf("[短视频] is_pinned 列已存在，跳过迁移")
		}
	}
}

// ---------- 路由 ----------
func RegisterShortVideoRoutes(mux *http.ServeMux, am func(http.HandlerFunc) http.HandlerFunc) {
	// 总开关状态（登录用户可读）：客户端进入页面后据此显示开发者配置的提示
	mux.HandleFunc("/api/shortvideo/gate", am(handleShortVideoGate))
	mux.HandleFunc("/api/shortvideo/feed", am(handleShortVideoFeed))
	mux.HandleFunc("/api/shortvideo/detail", am(handleShortVideoDetail))
	mux.HandleFunc("/api/shortvideo/upload", am(handleShortVideoUpload))
	mux.HandleFunc("/api/shortvideo/like/toggle", am(handleShortVideoToggleLike))
	mux.HandleFunc("/api/shortvideo/comments", am(handleShortVideoComments))
	mux.HandleFunc("/api/shortvideo/comment", am(handleShortVideoAddComment))
	mux.HandleFunc("/api/shortvideo/comment/like", am(handleShortVideoLikeComment))
	mux.HandleFunc("/api/shortvideo/comment/pin", am(handleShortVideoPinComment))
	mux.HandleFunc("/api/shortvideo/comment/delete", am(handleShortVideoDeleteComment))
	mux.HandleFunc("/api/shortvideo/view", am(handleShortVideoView))
	mux.HandleFunc("/api/shortvideo/status", am(handleShortVideoStatus))
	mux.HandleFunc("/api/shortvideo/delete", am(handleShortVideoDelete))
	// 开发者专属：作品全量信息（发布者 / 发布时间 / 存储占用 / 码率档位 / 播放数据）
	mux.HandleFunc("/api/shortvideo/info", am(handleShortVideoInfo))
	mux.HandleFunc("/api/shortvideo/retranscode", am(handleShortVideoRetranscode)) // 开发者：重跑三级转码
	// HLS 分片 / 封面：公开（播放器不附带 Bearer，与社区文件一致）
	mux.HandleFunc("/api/shortvideo/hls/", handleShortVideoHLS)
	// 本地视频预处理工具：同源托管（免鉴权）。同源的意义是网页直传上传接口时
	// 不会被浏览器 CORS 拦截；用户在页内粘贴自己的 Bearer 令牌即可把本地处理好的
	// 视频发到服务器，服务器对标准 H.264 直接零重编码直通，彻底不再烧 CPU。
	mux.HandleFunc("/tools/transcode", handleTranscodeTool)
}

//go:embed transcode_tool.html
var transcodeToolHTML []byte

// handleTranscodeTool 同源托管本地视频预处理工具（纯静态页，免鉴权）。
func handleTranscodeTool(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write(transcodeToolHTML)
}

// ---------- 数据模型 ----------
type ShortVideoPublisher struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
	Avatar   string `json:"avatar"`
}

type ShortVideoRendition struct {
	Quality  int    `json:"quality"`
	Codec    string `json:"codec"`
	Bitrate  int    `json:"bitrate"`
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	Playlist string `json:"playlist"`
}

type ShortVideoItem struct {
	ID            int64                 `json:"id"`
	UserID        int64                 `json:"user_id"`
	Publisher     ShortVideoPublisher   `json:"publisher"`
	Title         string                `json:"title"`
	Description   string                `json:"description"`
	CoverURL      string                `json:"cover_url"`
	PlayURL       string                `json:"play_url"`
	IsHLS         bool                  `json:"is_hls"`
	Duration      int                   `json:"duration"`
	// 显示尺寸与宽高比：视频「转正后」的真实画面尺寸（不含任何补边）。
	// 客户端首帧前就用 aspect_ratio 摆容器 → 黑底 contain，视频该多大就多大，不跳变、不变形。
	Width         int                   `json:"width"`
	Height        int                   `json:"height"`
	AspectRatio   float64               `json:"aspect_ratio"`
	// DirectURL 原画直通地址（视频轨零重编码，画质与源完全一致）。
	// 只对 H.264 源生成；为空表示该条没有直通能力，高画质只能走 HLS 原画档。
	DirectURL     string                `json:"direct_url"`
	ViewCount     int                   `json:"view_count"`
	LikesCount    int                   `json:"likes_count"`
	CommentsCount int                   `json:"comments_count"`
	HasLiked      bool                  `json:"has_liked"`
	CreatedAt     int64                 `json:"created_at"`
	Renditions    []ShortVideoRendition `json:"renditions,omitempty"`
	// DegradeReason 降级原因（HLS 转码失败时由 play_url + is_hls 推导，用于客户端诊断日志）。
	// 空字符串表示 HLS 成功（多码率），客户端据此判断「未降级」；非空表示走了兜底档。
	DegradeReason string `json:"degrade_reason"`
}

// hlsBase 返回某视频在 HLS 公开目录下的相对前缀（如 "12/cover.jpg"、"12/playlist.m3u8"）。
func shortVideoHlsRel(id int64, file string) string {
	return strconv.FormatInt(id, 10) + "/" + file
}

func shortVideoCoverURL(id int64) string {
	return "/api/shortvideo/hls/" + shortVideoHlsRel(id, "cover.jpg")
}

func shortVideoPlayURL(id int64, isHls bool) string {
	if isHls {
		return "/api/shortvideo/hls/" + shortVideoHlsRel(id, "playlist.m3u8")
	}
	return "/api/shortvideo/hls/" + shortVideoHlsRel(id, "orig.mp4")
}

// shortVideoDegradeReason 由 is_hls + play_url 推导降级原因，无需改表。
// HLS 成功返回空串（客户端据此判断「未降级」）；失败时指出具体兜底档，
// 便于在客户端诊断日志里定位「高画质也糊」的根因。
func shortVideoDegradeReason(isHls bool, playUrl string) string {
	if isHls {
		return ""
	}
	switch {
	case strings.Contains(playUrl, "highq.mp4"):
		return "HLS转码失败→高质量单档兜底(源分辨率，较清晰)"
	case strings.Contains(playUrl, "compat.mp4"):
		return "HLS转码失败→480P兼容档(偏糊，需重转)"
	case strings.Contains(playUrl, "orig.mp4"):
		return "HLS转码失败→原文件渐进式(源格式，部分设备可能播不了)"
	case strings.Contains(playUrl, "direct.mp4"):
		return "源H.264原画直通"
	default:
		return "渐进式播放(原因未知)"
	}
}

// ---------- 完整性校验：文件不在了，记录也不该再存在 ----------
// 判断一条记录的媒体文件是否真的还在：HLS 看 master playlist + 至少一个分片，降级看 orig.mp4。
// 存储根目录本身不可用时一律返回 true（宁可留着也别误删整库）。
func shortVideoMediaExists(id int64, isHls bool) bool {
	if _, err := os.Stat(shortVideoHlsDir); err != nil {
		return true
	}
	dir := filepath.Join(shortVideoHlsDir, strconv.FormatInt(id, 10))
	name := "orig.mp4"
	if isHls {
		name = "playlist.m3u8"
	}
	if fi, err := os.Stat(filepath.Join(dir, name)); err != nil || fi.IsDir() {
		return false
	}
	if isHls {
		ents, err := os.ReadDir(dir)
		if err != nil {
			return false
		}
		for _, e := range ents {
			if strings.HasSuffix(e.Name(), ".ts") {
				return true
			}
		}
		return false
	}
	return true
}

// purgeShortVideo 彻底清掉一条记录：物理文件 + 关联表 + 主表。
func purgeShortVideo(id int64) {
	os.RemoveAll(filepath.Join(shortVideoHlsDir, strconv.FormatInt(id, 10)))
	var orig sql.NullString
	db.QueryRow(`SELECT orig_path FROM shortvideo_videos WHERE id = ?`, id).Scan(&orig)
	if orig.Valid && orig.String != "" {
		os.Remove(filepath.Join(shortVideoVideosDir, orig.String))
	}
	db.Exec("DELETE FROM shortvideo_likes WHERE video_id = ?", id)
	db.Exec("DELETE FROM shortvideo_comments WHERE video_id = ?", id)
	db.Exec("DELETE FROM shortvideo_renditions WHERE video_id = ?", id)
	db.Exec("DELETE FROM shortvideo_videos WHERE id = ?", id)
	log.Printf("[短视频] 清理失效记录 vid=%d（媒体文件已不存在）", id)
}

// PurgeBrokenShortVideos 启动时扫一遍：库里还有记录、文件却没了的，直接清掉。
// 这就是"文件删干净了、编号还挂着能滑出黑屏"的根因修复。
func PurgeBrokenShortVideos() {
	if _, err := os.Stat(shortVideoHlsDir); err != nil {
		return
	}
	rows, err := db.Query(`SELECT id, is_hls, status, orig_path FROM shortvideo_videos`)
	if err != nil {
		return
	}
	type svRec struct {
		id     int64
		isHls  int
		status int
		orig   sql.NullString
	}
	list := []svRec{}
	for rows.Next() {
		var r svRec
		if rows.Scan(&r.id, &r.isHls, &r.status, &r.orig) == nil {
			list = append(list, r)
		}
	}
	rows.Close()

	n := 0
	for _, r := range list {
		if r.status == 1 {
			if !shortVideoMediaExists(r.id, r.isHls == 1) {
				purgeShortVideo(r.id)
				n++
			}
			continue
		}
		// 转码中的：原文件还在就留着（可能真的在转），原文件和产物都没了就是垃圾
		keep := false
		if r.orig.Valid && r.orig.String != "" {
			if _, e := os.Stat(filepath.Join(shortVideoVideosDir, r.orig.String)); e == nil {
				keep = true
			}
		}
		if !keep && !shortVideoMediaExists(r.id, false) {
			purgeShortVideo(r.id)
			n++
		}
	}
	if n > 0 {
		log.Printf("[短视频] 启动清理：%d 条记录的文件已不存在，已彻底删除", n)
	}
}

// buildShortVideoItem 组装单条短视频（发布者、聚合点赞/评论数、是否已赞、各码率变体）。
func buildShortVideoItem(id int64, userID int64) (ShortVideoItem, bool) {
	var it ShortVideoItem
	var username sql.NullString
	var coverPath string
	var isHls int
	var directPath string
	err := db.QueryRow(`SELECT v.id, v.user_id, u.username, v.title, v.description, v.cover_path, v.play_url, v.is_hls, v.duration, v.display_w, v.display_h, v.direct_path, v.view_count, v.created_at
		FROM shortvideo_videos v LEFT JOIN users u ON v.user_id = u.id
		WHERE v.id = ? AND v.status = 1`, id).
		Scan(&it.ID, &it.UserID, &username, &it.Title, &it.Description, &coverPath, &it.PlayURL, &isHls, &it.Duration, &it.Width, &it.Height, &directPath, &it.ViewCount, &it.CreatedAt)
	if err != nil {
		return it, false
	}
	it.Publisher = ShortVideoPublisher{
		ID:       it.UserID,
		Username: username.String,
		Avatar:   "/api/avatar/" + strconv.FormatInt(it.UserID, 10),
	}
	it.IsHLS = isHls == 1
	// 宽高比：老数据（本字段上线前转码的）display_w/h 为 0，这里填 0 让客户端回退到
	// 「封面比例 → VideoSize」的旧路径；存量重转完成后会自动补齐。
	if it.Width > 0 && it.Height > 0 {
		it.AspectRatio = float64(it.Width) / float64(it.Height)
	}
	// 文件已经不在（被清理/被删/转码产物丢失）就不该再出现在任何列表里，
	// 顺手把这条残记录一起清干净，避免"能滑出来但播不了"的半残状态。
	if !shortVideoMediaExists(it.ID, it.IsHLS) {
		purgeShortVideo(it.ID)
		return it, false
	}
	if coverPath != "" {
		it.CoverURL = "/api/shortvideo/hls/" + coverPath
	}
	// "-" 是「试过但不可用」的占位，不能当成路径下发
	if directPath != "" && directPath != "-" {
		it.DirectURL = "/api/shortvideo/hls/" + directPath
	}
	db.QueryRow("SELECT COUNT(*) FROM shortvideo_likes WHERE video_id = ?", id).Scan(&it.LikesCount)
	db.QueryRow("SELECT COUNT(*) FROM shortvideo_comments WHERE video_id = ?", id).Scan(&it.CommentsCount)
	var c int
	db.QueryRow("SELECT COUNT(*) FROM shortvideo_likes WHERE video_id = ? AND user_id = ?", id, userID).Scan(&c)
	it.HasLiked = c > 0

	rits := []ShortVideoRendition{}
	rrows, rerr := db.Query("SELECT quality, codec, bitrate, width, height, playlist FROM shortvideo_renditions WHERE video_id = ? ORDER BY quality ASC", id)
	if rerr == nil {
		defer rrows.Close()
		for rrows.Next() {
			var rr ShortVideoRendition
			var pl string
			if rrows.Scan(&rr.Quality, &rr.Codec, &rr.Bitrate, &rr.Width, &rr.Height, &pl) == nil {
				rr.Playlist = "/api/shortvideo/hls/" + pl
				rits = append(rits, rr)
			}
		}
	}
	it.Renditions = rits
	it.DegradeReason = shortVideoDegradeReason(it.IsHLS, it.PlayURL)
	return it, true
}

// ---------- Feed（列表） ----------
func handleShortVideoFeed(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	if r.Method != "GET" && r.Method != "POST" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	userID := getCurrentUserID(r)
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit < 1 || limit > 50 {
		limit = 20
	}
	offset := (page - 1) * limit
	// mine=1：只返回当前登录用户自己发布的视频（「我的发布 - 视频」列表用）。
	// 过滤放在服务端做，避免客户端逐页拉全量再筛——用户的视频可能在很深的页码里。
	mine := r.URL.Query().Get("mine") == "1"
	if mine && userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}

	var rows *sql.Rows
	var err error
	if mine {
		rows, err = db.Query(`SELECT id, user_id FROM shortvideo_videos
			WHERE status = 1 AND user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?`, userID, limit, offset)
	} else {
		rows, err = db.Query(`SELECT id, user_id FROM shortvideo_videos
			WHERE status = 1 ORDER BY created_at DESC LIMIT ? OFFSET ?`, limit, offset)
	}
	if err != nil {
		writeJSON(w, 500, "数据库错误", nil)
		return
	}
	defer rows.Close()
	items := []ShortVideoItem{}
	for rows.Next() {
		var id, uid int64
		if rows.Scan(&id, &uid) != nil {
			continue
		}
		if it, ok := buildShortVideoItem(id, userID); ok {
			items = append(items, it)
		}
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"videos": items,
		"page":   page,
		"limit":  limit,
	})
}

// ---------- 详情 ----------
func handleShortVideoDetail(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	vid, _ := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
	if vid == 0 {
		writeJSON(w, 400, "缺少 id", nil)
		return
	}
	userID := getCurrentUserID(r)
	it, ok := buildShortVideoItem(vid, userID)
	if !ok {
		writeJSON(w, 404, "视频不存在或转码中", nil)
		return
	}
	writeJSON(w, 200, "ok", it)
}

// ---------- 上传（触发后台多码率 HLS 转码） ----------
func handleShortVideoUpload(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	// 上传闸门（开发者管理 → 短视频 → 允许上传）：关闭后普通用户一律不能发布新作品，
	// 服务端直接 403，裸调接口 / 本地预处理页同样上传失败；开发者保留上传权限（便于补官方内容）。
	if !isDeveloperUser(getCurrentUserID(r)) && blockShortVideoUpload(w) {
		return
	}
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 500<<20) // 上限 500MB
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		writeJSON(w, 400, "文件过大或格式错误", nil)
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, "请选择文件", nil)
		return
	}
	defer file.Close()
	if ok, reason := validateUploadExt(header.Filename); !ok {
		writeJSON(w, 400, reason, nil)
		return
	}
	ext := strings.ToLower(strings.TrimPrefix(filepath.Ext(header.Filename), "."))

	// 标题/简介：ParseMultipartForm 已经执行过，表单字段可以直接读。
	// 这两个字段以前是写死空串，前端就算传了也存不进去（作品信息里永远显示"未填写"）。
	title := strings.TrimSpace(r.FormValue("title"))
	desc := strings.TrimSpace(r.FormValue("description"))
	if runes := []rune(title); len(runes) > 100 {
		title = string(runes[:100])
	}
	if runes := []rune(desc); len(runes) > 1000 {
		desc = string(runes[:1000])
	}

	now := time.Now().Unix()
	res, err := db.Exec(`INSERT INTO shortvideo_videos (user_id, title, description, status, created_at)
		VALUES (?, ?, ?, 0, ?)`, userID, title, desc, now)
	if err != nil {
		writeJSON(w, 500, "创建失败", nil)
		return
	}
	vid, _ := res.LastInsertId()

	origName := fmt.Sprintf("%d_orig.%s", vid, ext)
	os.MkdirAll(shortVideoVideosDir, 0755)
	origPath := filepath.Join(shortVideoVideosDir, origName)
	dst, err := os.Create(origPath)
	if err != nil {
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	if _, err := io.Copy(dst, file); err != nil {
		dst.Close()
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	dst.Close()
	db.Exec("UPDATE shortvideo_videos SET orig_path = ? WHERE id = ?", origName, vid)

	// 后台转码（多码率 HLS），不阻塞上传响应
	go transcodeShortVideo(vid, origPath)

	writeJSON(w, 200, "上传成功，正在后台转码", map[string]interface{}{
		"id":     vid,
		"status": "processing",
	})
}

// ---------- 转码（核心：多码率 HLS + 封面 + 时长） ----------
// shortVideoTranscodeSem 把转码串行化：2 核机器上并发跑多个 ffmpeg 会直接把机器顶死
//（负载 100%、运行队列堵塞）。容量 1 保证同一时刻只有一个转码在跑；上传 / 启动体检 /
// 手动重转 / 尺寸修复 全部排队，机器不会被瞬间打爆。
var shortVideoTranscodeSem = make(chan struct{}, 1)

func transcodeShortVideo(vid int64, origPath string) {
	shortVideoTranscodeSem <- struct{}{}
	defer func() { <-shortVideoTranscodeSem }()
	hlsDir := filepath.Join(shortVideoHlsDir, strconv.FormatInt(vid, 10))
	if err := os.MkdirAll(hlsDir, 0755); err != nil {
		log.Printf("[短视频] 创建转码目录失败 vid=%d: %v", vid, err)
		finalizeShortVideoFallback(vid, origPath)
		return
	}

	// 封面：第 1 秒取一帧
	extractShortVideoCover(origPath, filepath.Join(hlsDir, "cover.jpg"))

	// 时长
	dur := probeShortVideoDuration(origPath)

	// 显示尺寸（已按旋转角转正）。两个用途：
	//  1. 写进库，随 feed 下发 aspect_ratio —— 客户端首帧前就能把容器摆成正确比例，不再三段猜测、不跳变；
	//  2. 转码后核对产物比例是否与源一致 —— 尺寸出问题时能被日志抓住，而不是等用户投诉。
	dw, dh := probeShortVideoDisplaySize(origPath)
	if dw > 0 && dh > 0 {
		log.Printf("[短视频] 源显示尺寸 vid=%d = %dx%d（比例 %.4f）", vid, dw, dh, float64(dw)/float64(dh))
	} else {
		log.Printf("[短视频] 源显示尺寸探测失败 vid=%d（ffprobe 缺失或文件异常），客户端将回退到首帧探测", vid)
	}

	// 原画直通：视频轨零重编码，产物与源**完全一致**（这是唯一没有代际损失的路径）。
	// 只有 H.264 源能这么做 —— HEVC/AV1 直通后大量设备解不了，那种源只能走下面的重编码。
	// 直通文件通常还比 CRF18 重编码更小，因为用的就是源码率。
	srcCodec := probeShortVideoVideoCodec(origPath)
	directRel := ""
	if srcCodec == "h264" {
		if remuxShortVideoDirect(origPath, filepath.Join(hlsDir, "direct.mp4")) {
			directRel = shortVideoHlsRel(vid, "direct.mp4")
			log.Printf("[短视频] 原画直通就绪 vid=%d（视频轨零重编码）", vid)
		} else {
			// 写 "-" 表示「尝试过但不可用」。不能留空 —— 留空会让存量体检
			// 每次启动都判定"还没试过直通"，把所有 H.264 视频无限重转。
			directRel = "-"
			log.Printf("[短视频] 原画直通失败 vid=%d，高画质改走 HLS 原画档", vid)
		}
	} else {
		log.Printf("[短视频] 源编码 %q 不支持直通（设备兼容性），高画质走 HLS 原画档", srcCodec)
	}

	// ffmpeg 可用性（三级兜底都依赖它；缺失时日志要大声说出来，否则线上全静默降级看不出来）
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		log.Printf("[短视频] 服务器未安装 ffmpeg，vid=%d 只能渐进式播放原文件（HEVC/AV1 源可能无法播放）：%v", vid, err)
	}

	// 第一级：多码率 HLS。H.264 源且原画直通就绪时走「拷贝顶层」：
	// 高画质档 -c:v copy 已转正的 direct.mp4（零重编码、与源逐帧一致），
	// 仅低/中两档做 libx264 下采样；任何一步失败回退普通 HLS（全重编码），不破环、不丢兜底。
	if probeFFmpegEncoders()["libx264"] {
		ff, _ := exec.LookPath("ffmpeg")
		hlsOK := false
		if ff != "" && srcCodec == "h264" && directRel != "" && directRel != "-" {
			if runFFmpegHLSCopyTopRetry(ff, vid, origPath, filepath.Join(hlsDir, "direct.mp4"), hlsDir) {
				hlsOK = true
				log.Printf("[短视频] 拷贝顶层HLS成功 vid=%d（高画质零重编码）", vid)
			} else {
				log.Printf("[短视频] 拷贝顶层HLS失败，回退普通五档HLS vid=%d", vid)
			}
		}
		if !hlsOK {
			hlsOK = runFFmpegHLS(vid, origPath, hlsDir)
		}
		if hlsOK {
			insertShortVideoRenditions(vid, dw, dh)
			// 兜底：HLS 成功也保留一份渐进式可播文件（硬链接，不额外占空间），
			// 客户端 HLS 解析失败时可自动回退到 orig.mp4，不会只剩封面。
			linkOrCopy(origPath, filepath.Join(hlsDir, "orig.mp4"))
			// 再产出一份 H.264/AAC 兼容档（compat.mp4）：HLS 在某些设备/弱网下解析失败时客户端会回退到它。
			// 若只留 orig.mp4，而 orig 是 HEVC/AV1 源，无对应解码器的设备即便回退也仍播不了——
			// 所以无论走不走 HLS，都要有一份全设备可解的 compat 兜底。
			if runFFmpegCompat(origPath, filepath.Join(hlsDir, "compat.mp4")) {
				log.Printf("[短视频] 已产出兼容兜底档 vid=%d", vid)
			}
			db.Exec("UPDATE shortvideo_videos SET play_url = ?, is_hls = 1, cover_path = ?, duration = ?, display_w = ?, display_h = ?, direct_path = ?, status = 1 WHERE id = ?",
				shortVideoPlayURL(vid, true), shortVideoHlsRel(vid, "cover.jpg"), dur, dw, dh, directRel, vid)
			// 产物比例自检：pad 过一次的产物在这里会被当场抓出来（偏差 > 2.5% 记错误日志）。
			checkHLSKeepAspect(vid, hlsDir, dw, dh)
			// 新 playlist 已落盘，删掉上一代不再被引用的分片（重转时才会真的有东西可删）
			cleanupStaleShortVideoSegments(hlsDir)
			log.Printf("[短视频] 转码完成 vid=%d HLS（比例 %dx%d）", vid, dw, dh)
			return
		}
	} else {
		log.Printf("[短视频] 服务器无 libx264 编码器，跳过HLS，直接进兼容档 vid=%d", vid)
	}

	// 第二级（高质量）：HLS 没成，但源可能是高分辨率 / HEVC（没有原画直通），
	// 直接掉 480P 兼容档会把清晰度腰斩成"高画质也糊"。先试「源分辨率(封顶1920)单档
	// H.264/CRF20」兜底：仍是无 HLS 单文件，但分辨率保住了，客户端「高」档直接播它就清晰。
	// 只有这条也失败，才退下面的 480P 兼容档。
	hqPath := filepath.Join(hlsDir, "highq.mp4")
	if runFFmpegHighQuality(origPath, hqPath) {
		linkOrCopy(origPath, filepath.Join(hlsDir, "orig.mp4")) // 原件仍保留，供排查/后续重转
		hqURL := "/api/shortvideo/hls/" + shortVideoHlsRel(vid, "highq.mp4") +
			"?v=" + strconv.FormatInt(time.Now().Unix(), 10)
		db.Exec("UPDATE shortvideo_videos SET play_url = ?, is_hls = 0, cover_path = ?, duration = ?, display_w = ?, display_h = ?, direct_path = ?, status = 1 WHERE id = ?",
			hqURL, shortVideoHlsRel(vid, "cover.jpg"), dur, dw, dh, directRel, vid)
		log.Printf("[短视频] HLS失败，已降级高质量单档兜底 vid=%d（分辨率封顶1920，比例 %dx%d）", vid, dw, dh)
		return
	}

	// 第三级：单档兼容转码（H.264/AAC 480P）。
	// 这是"视频本身没问题、五路转码扛不住"时的主兜底：HEVC 源五路 filter_complex 容易挂，
	// 单路 ultrafast 几乎都能过；产物是 Android 全兼容格式，杜绝"上传成功却播不了"。
	compatPath := filepath.Join(hlsDir, "compat.mp4")
	if runFFmpegCompat(origPath, compatPath) {
		linkOrCopy(origPath, filepath.Join(hlsDir, "orig.mp4")) // 原件仍保留，供排查/后续重转
		// ?v=时间戳：同一 URL 内容变了（修好一个坏文件）也要让客户端缓存立刻失效，
		// 否则 OkHttp 磁盘缓存会把旧的纯音频副本继续发给播放器——"服务器修好了但用户还看着坏文件"。
		compatURL := "/api/shortvideo/hls/" + shortVideoHlsRel(vid, "compat.mp4") +
			"?v=" + strconv.FormatInt(time.Now().Unix(), 10)
		db.Exec("UPDATE shortvideo_videos SET play_url = ?, is_hls = 0, cover_path = ?, duration = ?, display_w = ?, display_h = ?, direct_path = ?, status = 1 WHERE id = ?",
			compatURL, shortVideoHlsRel(vid, "cover.jpg"), dur, dw, dh, directRel, vid)
		log.Printf("[短视频] 五路HLS失败，已降级单档兼容转码 vid=%d（比例 %dx%d）", vid, dw, dh)
		return
	}

	// 第四级：原文件渐进式（最后的兜底；HEVC/AV1 源走到这一步客户端可能播不了，
	// 由前端显示"格式暂不支持"，至少不再崩溃）
	finalizeShortVideoFallback(vid, origPath)
}

// finalizeShortVideoFallback 转码不可用时的降级：直接以原文件渐进式播放（play_url 指向 orig.mp4）。
func finalizeShortVideoFallback(vid int64, origPath string) {
	hlsDir := filepath.Join(shortVideoHlsDir, strconv.FormatInt(vid, 10))
	os.MkdirAll(hlsDir, 0755)
	dst := filepath.Join(hlsDir, "orig.mp4")
	if copyFile(origPath, dst) != nil {
		// 连复制都失败：直接以原始存储路径公开（仍放 hls 目录语义下）
		dst = origPath
	}
	// 渐进式播放的是原文件本身，比例天然是源的真实比例；顺手把显示尺寸补进库，
	// 让客户端这条回退路径也能拿到权威比例（否则只有它能正确显示、HLS 却能正常，观感割裂）。
	dw, dh := probeShortVideoDisplaySize(origPath)
	db.Exec("UPDATE shortvideo_videos SET play_url = ?, is_hls = 0, cover_path = ?, duration = 0, display_w = ?, display_h = ?, status = 1 WHERE id = ?",
		shortVideoPlayURL(vid, false), shortVideoHlsRel(vid, "cover.jpg"), dw, dh, vid)
	log.Printf("[短视频] 降级为渐进式播放 vid=%d（比例 %dx%d）", vid, dw, dh)
}

// remuxShortVideoDirect 原画直通：视频轨 -c:v copy 零重编码，只把封装换成 faststart 的 MP4。
//
// 【为什么必须有这条路径】
// 任何重编码都有代际损失 —— 哪怕 CRF 18 视觉上"接近"，纹理和边缘也一定会软一点。
// 用户能一眼看出"转码中清晰、发布后糊"，就是因为本地播的是原文件、线上播的是重编码产物。
// 只有 copy 能做到发布后与源**完全一致**，而且因为是源码率，通常比 CRF 18 重编码还省带宽。
//
// 限制：只对 H.264 源使用。HEVC/AV1 直通后大量设备解不了，那种源只能老老实实转码。
func remuxShortVideoDirect(in, out string) bool {
	ff, err := exec.LookPath("ffmpeg")
	if err != nil {
		return false
	}
	args := []string{"-i", in, "-y", "-map", "0:v:0", "-c:v", "copy"}
	if probeShortVideoHasAudio(in) {
		// 音频重编码成 AAC：AC3/DTS/多声道在 Android 上不可靠，而音频码率低，
		// 128k AAC 的损失远小于重新编码视频。
		args = append(args, "-map", "0:a:0", "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "44100")
	} else {
		args = append(args, "-an")
	}
	args = append(args, "-movflags", "+faststart", out)
	if !runFFmpegLogged(ff, args, "原画直通[remux]") {
		return false
	}
	return compatOutputOK(out)
}

// runFFmpegCompat 单档兼容转码：任意源（HEVC/AV1/奇怪 profile 都行）→ 设备全兼容 MP4。
// 这是五路 HLS 失败时的主兜底：Android 全系能解 H.264/AAC，HEVC 原文件在很多机器上直接播不了。
//
// 【线上事故教训，动这条命令前先读完】曾经的实现只写了 -map 0:a:0（只映射音轨）：
// ffmpeg 只要见到任何 -map 就彻底关闭自动选流，视频流从没进过命令行，-c:v libx264
// 无流可编成了空操作——于是 ffmpeg 退出码 0、产物 >4KB、校验全绿，发布出去的却是
// 一个纯音频 MP4：客户端"有音乐、只有一帧封面"，全员中招。
// 所以本函数三条铁律，缺一不可：
//  1. 视频流必须显式 -map 0:v:0，永远不依赖自动选流；
//  2. 产物必须过 mp4HasVideoTrack 复核"真的有视频轨"，没有就按失败走下一级；
//  3. 编码器不挑食：libx264 → 源已是 H.264 时直接换封装（不吃编码器）→ mpeg4
//     （ffmpeg 内置编码器，任何构建都带，Android 设备普遍可解）。
// runFFmpegHighQuality HLS 失败时的高质量单档兜底：源分辨率（等比缩到 1920 盒内、不放大）
// 单档 H.264 / CRF20。比 480P 兼容档清晰得多——HEVC 源没有原画直通，HLS 一挂就只剩 480P，
// 表现为"高画质也糊"。这条兜底让降级后仍尽量保住清晰度（仍是单文件、无多码率自适应，
// 但分辨率是对的，客户端「高」档直接播它就清晰）。
func runFFmpegHighQuality(in, out string) bool {
	ff, err := exec.LookPath("ffmpeg")
	if err != nil {
		return false
	}
	hasAudio := probeShortVideoHasAudio(in)
	args := []string{"-i", in, "-y", "-map", "0:v:0"}
	if hasAudio {
		args = append(args, "-map", "0:a:0")
	} else {
		args = append(args, "-an")
	}
	args = append(args,
		"-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p",
		"-preset", "veryfast", "-threads", "1", "-crf", "20", "-maxrate", "20M", "-bufsize", "40M",
		// 等比缩到 1920 盒内、不放大、宽高取偶；竖屏也保持竖比例
		"-vf", svFitFilter(1920, 1920),
	)
	if hasAudio {
		args = append(args, "-c:a", "aac", "-b:a", "128k", "-ar", "44100")
	}
	args = append(args, out)
	if !runFFmpegLogged(ff, args, "高质量单档兜底") {
		return false
	}
	return compatOutputOK(out)
}

func runFFmpegCompat(in, out string) bool {
	hasAudio := probeShortVideoHasAudio(in)
	srcCodec := probeShortVideoVideoCodec(in)

	// 策略一：libx264 + 480P 归一化滤镜（画质/码率最均衡）
	if tryFFmpegEncode(in, out, "libx264", hasAudio, true) {
		return true
	}
	// 策略二：libx264 原分辨率——滤镜在老 ffmpeg 上可能不受支持
	//（force_original_aspect_ratio / pad 的 color 选项都有最低版本要求），去掉滤镜重试
	if tryFFmpegEncode(in, out, "libx264", hasAudio, false) {
		return true
	}
	// 策略三：源视频已是 H.264 时直接换封装——视频轨原样拷贝，零重编码、零画质损失，
	// 也不依赖任何视频编码器（服务器没编 libx264 时这条路直接救命）
	if srcCodec == "h264" && tryFFmpegRemux(in, out, hasAudio) {
		return true
	}
	// 策略四：mpeg4 内置编码器 + 480P 滤镜（任何 ffmpeg 构建都带；Android 4.x 以后普遍可解）
	if tryFFmpegEncode(in, out, "mpeg4", hasAudio, true) {
		return true
	}
	// 策略五：mpeg4 原分辨率（滤镜失败的退路）
	if tryFFmpegEncode(in, out, "mpeg4", hasAudio, false) {
		return true
	}
	// 策略六：纯封装拷贝（不编码、不过滤镜，任何能读写 MP4 的 ffmpeg 都能干；
	// 源是 HEVC 时设备解不解播看机器，但至少有完整的视频轨，绝不会再有"有声无画"）
	if tryFFmpegRemux(in, out, hasAudio) {
		return true
	}
	log.Printf("[短视频] 兼容转码全部策略失败（源编码=%q）: %s", srcCodec, in)
	return false
}

// tryFFmpegEncode 用指定视频编码器做单档兼容转码。
// 视频流必须显式 -map 0:v:0：ffmpeg 只要见到任何 -map 就关闭自动选流，
// 漏写视频映射 = 产出纯音频文件（且退出码还是 0，极具迷惑性）。
// libx264 之外的编码器没有 preset/maxrate 这类私有选项，统一走质量档 -q:v。
// withFilter=false 时不加 -vf（原分辨率输出）：滤镜选项有 ffmpeg 版本要求，
// 老版本会让整条命令失败，此时降级到无滤镜重试，而不是放弃整个视频。
func tryFFmpegEncode(in, out, vcodec string, hasAudio, withFilter bool) bool {
	ff, err := exec.LookPath("ffmpeg")
	if err != nil {
		return false
	}
	args := []string{"-i", in, "-y", "-map", "0:v:0"}
	if hasAudio {
		args = append(args, "-map", "0:a:0")
	} else {
		args = append(args, "-an")
	}
	args = append(args, "-c:v", vcodec, "-pix_fmt", "yuv420p", "-movflags", "+faststart")
	switch vcodec {
	case "libx264":
		args = append(args, "-preset", "ultrafast", "-threads", "1", "-crf", "28", "-maxrate", "1200k", "-bufsize", "2400k")
	default:
		args = append(args, "-q:v", "5")
	}
	if withFilter {
		// 等比缩到 480P 盒子内（不补边、不放大、宽高取偶）。
		// 曾经这里也 pad 成 854x480，导致兜底盘把竖屏视频压成横条，
		// 客户端按 16:9 排布后画面只剩十分之一——和 HLS 那条路是同一个坑。
		args = append(args, "-vf", svFitFilter(854, 854))
	} else {
		// 无滤镜（老 ffmpeg 不认 force_original_aspect_ratio / pad 时）也要保证宽高为偶数：
		// 只把奇数宽高取偶，不缩放、不裁切，最大程度保留原分辨率与画质。
		args = append(args, "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2")
	}
	if hasAudio {
		args = append(args, "-c:a", "aac", "-b:a", "96k", "-ar", "44100")
	}
	args = append(args, out)
	if !runFFmpegLogged(ff, args, "兼容转码["+vcodec+"]") {
		return false
	}
	return compatOutputOK(out)
}

// tryFFmpegRemux 源视频已是 H.264 时直接换封装：视频轨 -c:v copy 零损耗，
// 音频统一转 AAC，moov 前置（faststart）。不需要任何视频编码器。
func tryFFmpegRemux(in, out string, hasAudio bool) bool {
	ff, err := exec.LookPath("ffmpeg")
	if err != nil {
		return false
	}
	args := []string{"-i", in, "-y", "-map", "0:v:0", "-c:v", "copy", "-movflags", "+faststart"}
	if hasAudio {
		args = append(args, "-map", "0:a:0", "-c:a", "aac", "-b:a", "96k", "-ar", "44100")
	} else {
		args = append(args, "-an")
	}
	args = append(args, out)
	if !runFFmpegLogged(ff, args, "兼容转码[remux]") {
		return false
	}
	return compatOutputOK(out)
}

// compatOutputOK 产物体检：非空 + 真的有视频轨。
// 只查"文件存在且 >4KB"是不够的——纯音频 MP4 同样满足，
// 正是这类"校验全绿却播不了"的产物导致了线上事故。
func compatOutputOK(out string) bool {
	fi, err := os.Stat(out)
	if err != nil || fi.Size() < 4096 {
		return false
	}
	if !mp4HasVideoTrack(out) {
		log.Printf("[短视频] 产物无视频轨，按失败处理: %s", out)
		return false
	}
	return true
}

// runFFmpegLogged 跑一条 ffmpeg 命令，失败时把 stderr 截断打日志。
// 带超时保护：任何一路 ffmpeg（HLS / 高质量单档 / 兼容档）都不允许无限期挂死，
// 否则会卡住整条兜底链——上层永远到不了下一级兜底，视频就停在"转码中"再也出不来。
// 超时即视为失败，由上层继续往下一级兜底（compat / 原文件）。
func runFFmpegLogged(ff string, args []string, what string) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, ff, args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := stderr.String()
		if len(msg) > 2000 {
			msg = msg[len(msg)-2000:]
		}
		log.Printf("[短视频] %s失败: %v\n%s", what, err, msg)
		return false
	}
	return true
}

// mp4HasVideoTrack 纯 Go 解析 MP4 盒结构，判断是否存在视频轨（trak/mdia/hdlr == 'vide'）。
// 不依赖 ffprobe：服务器上 ffprobe 偶有缺失，而"有没有视频轨"是发布前的硬指标，
// 必须有个不靠外部二进制的判断手段。moov 在文件头尾都可能，逐盒跳过 mdat 即可。
func mp4HasVideoTrack(path string) bool {
	fi, err := os.Stat(path)
	if err != nil || fi.Size() < 16 {
		return false
	}
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()

	// findBox 在 [start,end) 范围内顺序查找指定类型的盒，返回其偏移与大小。
	var findBox func(start, end int64, want string) (int64, int64, bool)
	findBox = func(start, end int64, want string) (int64, int64, bool) {
		pos := start
		hdr := make([]byte, 16)
		for pos+8 <= end {
			if _, err := f.ReadAt(hdr[:8], pos); err != nil {
				return 0, 0, false
			}
			size := int64(binary.BigEndian.Uint32(hdr[0:4]))
			typ := string(hdr[4:8])
			hdrLen := int64(8)
			if size == 1 { // 64 位扩展大小
				if _, err := f.ReadAt(hdr, pos); err != nil {
					return 0, 0, false
				}
				size = int64(binary.BigEndian.Uint64(hdr[8:16]))
				hdrLen = 16
			} else if size == 0 { // 延续到末尾
				size = end - pos
			}
			if size < hdrLen || pos+size > end {
				return 0, 0, false
			}
			if typ == want {
				return pos, size, true
			}
			pos += size
		}
		return 0, 0, false
	}

	moovOff, moovSize, ok := findBox(0, fi.Size(), "moov")
	if !ok {
		return false // 没有 moov（或非标准 MP4）：无法确认视频轨，按无处理
	}
	pos := moovOff + 8
	end := moovOff + moovSize
	hdr := make([]byte, 8)
	for pos+8 <= end {
		if _, err := f.ReadAt(hdr, pos); err != nil {
			return false
		}
		size := int64(binary.BigEndian.Uint32(hdr[0:4]))
		typ := string(hdr[4:8])
		if size < 8 || pos+size > end {
			return false
		}
		if typ == "trak" {
			if mdiaOff, mdiaSize, ok := findBox(pos+8, pos+size, "mdia"); ok {
				// hdlr 布局：size(4)+type(4)+version(1)+flags(3)+pre_defined(4)+handler_type(4)
				if hdlrOff, hdlrSize, ok := findBox(mdiaOff+8, mdiaOff+mdiaSize, "hdlr"); ok && hdlrSize >= 20 {
					ht := make([]byte, 4)
					if _, err := f.ReadAt(ht, hdlrOff+16); err == nil && string(ht) == "vide" {
						return true
					}
				}
			}
		}
		pos += size
	}
	return false
}

// shortVideoSegmentPattern 生成带代次前缀的分片文件名模板。
// %v / %03d 是留给 ffmpeg 的占位符（变体号 / 分片序号），所以这里必须转义成 %%v / %%03d。
func shortVideoSegmentPattern() string {
	return fmt.Sprintf("g%d_%%v_%%03d.ts", time.Now().UnixNano())
}

// cleanupStaleShortVideoSegments 删掉目录里不被任何 playlist 引用的分片。
// 每次转码都会产出一代新分片（见 shortVideoSegmentPattern），旧代留着只占磁盘。
// 只在新 playlist 已经落盘后调用（ffmpeg 已退出），不会删掉仍被引用的分片。
func cleanupStaleShortVideoSegments(dir string) {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	// 顺手删掉上一代方案的变体 playlist（老版本有过 5 档/6 档，会留下 out_3..out_5）。
	// 不删的话 hlsDirNeedsRebuild 会一直把"存在 out_3"当成老产物，每次启动都重转一遍。
	for i := 3; i < 10; i++ {
		os.Remove(filepath.Join(dir, fmt.Sprintf("out_%d.m3u8", i)))
	}
	keep := map[string]bool{}
	for _, e := range ents {
		if !strings.HasSuffix(e.Name(), ".m3u8") {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			continue
		}
		for _, line := range strings.Split(string(b), "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			keep[filepath.Base(line)] = true
		}
	}
	if len(keep) == 0 {
		return // 一个 playlist 都没读到：宁可留着，也不能误删唯一能播的分片
	}
	removed := 0
	var freed int64
	for _, e := range ents {
		n := e.Name()
		if !strings.HasSuffix(n, ".ts") || keep[n] {
			continue
		}
		info, _ := e.Info()
		if os.Remove(filepath.Join(dir, n)) == nil {
			removed++
			if info != nil {
				freed += info.Size()
			}
		}
	}
	if removed > 0 {
		log.Printf("[短视频] 清理旧分片 %s：%d 个（%.1f MB）", filepath.Base(dir), removed, float64(freed)/1048576)
	}
}

// runFFmpegHLSOnce 用 ffmpeg 生成多码率 HLS（master playlist + 三档变体 + 2s GOP）。
// withAudio=false 时不映射音轨——部分源（音轨异常 / 多音轨 / 特殊封装）会让 filter_complex
// 或 HLS 封装整条失败，此时去掉音轨至少能保住高清画面，再不行就走高质量单档兜底。
// 返回是否成功（含产物校验）。
func runFFmpegHLSOnce(ff string, vid int64, in, outDir string, withAudio bool) bool {
	args := []string{
		"-i", in, "-y",
		// -pix_fmt 必须显式写：源若是 10bit（HEVC Main10 / HDR 录屏），
		// 不指定就会产出高比特深度的变体，老设备解不了、部分播放器还会降级成低清渲染。
		"-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-threads", "1",
		"-g", "48", "-keyint_min", "48", "-sc_threshold", "0",
	}
	if withAudio {
		args = append(args, "-c:a", "aac", "-b:a", "128k", "-ar", "44100")
	}
	// 三路：低 / 中 / 高。只做等比缩放，绝不 pad：产物宽高比必须等于源显示比例。
	// 这样竖屏视频每一档都是竖的，客户端拿到的 VideoSize 就是真实比例，容器按它排布即可。
	args = append(args,
		"-filter_complex",
		"[0:v]split=3[v0][v1][v2];"+
			// 低：480 档，省流量，画质可以让步。
			"[v0]"+svFitFilter(854, 854)+"[v0];"+
			// 中：1080 档，画质与体积的平衡点。
			"[v1]"+svFitFilter(1920, 1920)+"[v1];"+
			// 高：原画。盒子放到 3840，只拦 4K 以上，产物 = 源分辨率。
			// 以前最高档盒子是 1920，等于把所有超过 1080 的源强行降采样 ——
			// 2K 直接砍掉 44% 像素，这才是"发过去就糊"的根源，不是网络问题。
			"[v2]"+svFitFilter(3840, 3840)+"[v2]",
		"-map", "[v0]", "-map", "[v1]", "-map", "[v2]",
	)
	varMap := "v:0 v:1 v:2"
	if withAudio {
		// 只取第一条音轨，避免多音轨进 var_stream_map 时无法映射导致报错
		args = append(args, "-map", "0:a:0")
		varMap = "v:0,a:0 v:1,a:0 v:2,a:0"
	} else {
		args = append(args, "-an")
	}
	args = append(args,
		//   低 CRF28 —— 省流量，画质让步
		//   中 CRF20 —— 肉眼接近源，体积可控
		//   高 CRF18 —— 视觉无损，源分辨率不降级
		"-crf:v:0", "28", "-maxrate:v:0", "2M", "-bufsize:v:0", "4M",
		"-crf:v:1", "20", "-maxrate:v:1", "14M", "-bufsize:v:1", "28M",
		"-crf:v:2", "18", "-maxrate:v:2", "45M", "-bufsize:v:2", "90M",
		// 强制每 2s 一个关键帧，保证分片边界干净、可精确 seek
		"-force_key_frames", "expr:gte(t,n_forced*2)",
		"-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod",
		// 显式指定分片名（含 %v 变体号），避免默认命名在不同 ffmpeg 版本下产生歧义。
		// 名字里带代次前缀（纳秒时间戳）：每次转码分片 URL 都不同，重转时客户端 HTTP 缓存
		// （.ts 是 max-age=86400，OkHttp 会真的存一天）不可能继续喂旧画面。
		"-hls_segment_filename", filepath.Join(outDir, shortVideoSegmentPattern()),
		"-master_pl_name", "playlist.m3u8",
		"-var_stream_map", varMap,
		filepath.Join(outDir, "out_%v.m3u8"),
	)
	what := "HLS转码"
	if !withAudio {
		what = "HLS转码(去音轨)"
	}
	if !runFFmpegLogged(ff, args, what) {
		return false
	}
	// 产物校验：ffmpeg 退出码 0 不代表产物可用（某一路静默失败 → 空 master → 客户端崩）。
	if !verifyHLSOutput(outDir) {
		log.Printf("[短视频] 转码产物不完整 vid=%d（master/变体 playlist 缺失或为空），按失败处理", vid)
		return false
	}
	return true
}

// runFFmpegHLS 用 ffmpeg 生成多码率 HLS，返回是否成功。
// 先带音轨试；若失败（部分源音轨会让整条 HLS 挂掉），去掉音轨再试一次，
// 仍失败才由上层降级到高质量单档 / 480P 兼容档。
func runFFmpegHLS(vid int64, in, outDir string) bool {
	ff, err := exec.LookPath("ffmpeg")
	if err != nil {
		log.Printf("[短视频] 未找到 ffmpeg，降级处理 vid=%d: %v", vid, err)
		return false
	}
	hasAudio := probeShortVideoHasAudio(in)
	if runFFmpegHLSOnce(ff, vid, in, outDir, hasAudio) {
		return true
	}
	if hasAudio {
		log.Printf("[短视频] HLS 带音轨失败，去音轨重试 vid=%d", vid)
		if runFFmpegHLSOnce(ff, vid, in, outDir, false) {
			return true
		}
	}
	return false
}

// runFFmpegHLSCopyTop 与 runFFmpegHLS 同产出三档 HLS，但「高」档直接 -c:v copy 已转正的
// direct.mp4（零重编码），只把低/中两档做 libx264 下采样。对 H.264 源（手机视频绝大多数），
// 最贵的那一路全分辨率重编码被彻底省掉 —— 服务器 CPU 从 3 次编码降到 2 次，且高画质档
// 与源逐帧一致（比任何 CRF 重编码都清晰）。任何一步失败都由上层回退到 runFFmpegHLS。
// 前置：cleanPath 必须已存在（remuxShortVideoDirect 成功），否则不要调用本函数。
func runFFmpegHLSCopyTop(ff string, vid int64, in, cleanPath, outDir string, withAudio bool) bool {
	args := []string{
		"-i", in, "-i", cleanPath, "-y",
		"-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-threads", "1",
		"-g", "48", "-keyint_min", "48", "-sc_threshold", "0",
	}
	if withAudio {
		args = append(args, "-c:a", "aac", "-b:a", "128k", "-ar", "44100")
	}
	// 低/中两档走缩放重编码；高（out2）直接 copy 已转正的 direct.mp4，零重编码。
	args = append(args,
		"-filter_complex",
		"[0:v]split=2[v0][v1];"+
			"[v0]"+svFitFilter(854, 854)+"[v0o];"+
			"[v1]"+svFitFilter(1920, 1920)+"[v1o]",
		"-map", "[v0o]", "-map", "[v1o]", "-map", "1:v",
	)
	varMap := "v:0,a:0 v:1,a:1 v:2,a:2"
	if withAudio {
		// 三路音轨：低/中取自源，高取自 direct.mp4（已是 AAC，重新封装即可）
		args = append(args, "-map", "0:a:0", "-map", "0:a:0", "-map", "1:a:0")
	} else {
		args = append(args, "-an")
		varMap = "v:0 v:1 v:2"
	}
	args = append(args,
		"-c:v:2", "copy",
		"-crf:v:0", "28", "-maxrate:v:0", "2M", "-bufsize:v:0", "4M",
		"-crf:v:1", "20", "-maxrate:v:1", "14M", "-bufsize:v:1", "28M",
		"-force_key_frames", "expr:gte(t,n_forced*2)",
		"-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod",
		"-hls_segment_filename", filepath.Join(outDir, shortVideoSegmentPattern()),
		"-master_pl_name", "playlist.m3u8",
		"-var_stream_map", varMap,
		filepath.Join(outDir, "out_%v.m3u8"),
	)
	if !runFFmpegLogged(ff, args, "HLS转码(拷贝顶层)") {
		return false
	}
	if !verifyHLSOutput(outDir) {
		log.Printf("[短视频] 拷贝顶层产物不完整 vid=%d，按失败处理", vid)
		return false
	}
	return true
}

// runFFmpegHLSCopyTopRetry 与 runFFmpegHLS 一样的音轨容错：带音轨失败就去音轨重试。
func runFFmpegHLSCopyTopRetry(ff string, vid int64, in, cleanPath, outDir string) bool {
	hasAudio := probeShortVideoHasAudio(in)
	if runFFmpegHLSCopyTop(ff, vid, in, cleanPath, outDir, hasAudio) {
		return true
	}
	if hasAudio {
		log.Printf("[短视频] 拷贝顶层HLS带音轨失败，去音轨重试 vid=%d", vid)
		if runFFmpegHLSCopyTop(ff, vid, in, cleanPath, outDir, false) {
			return true
		}
	}
	return false
}

// verifyHLSOutput 校验 HLS 产物真的齐全：
// master playlist 含至少一条 EXT-X-STREAM-INF，五档变体 playlist 均存在且各含至少一条 EXTINF。
// 任何一项不满足都说明这路转码不可用（客户端拿到会直接崩，不能发布）。
func verifyHLSOutput(outDir string) bool {
	master, err := os.ReadFile(filepath.Join(outDir, "playlist.m3u8"))
	if err != nil || !strings.Contains(string(master), "#EXT-X-STREAM-INF") {
		return false
	}
	for i := 0; i < 3; i++ {
		p := filepath.Join(outDir, fmt.Sprintf("out_%d.m3u8", i))
		b, err := os.ReadFile(p)
		if err != nil {
			// 必须说清是哪一档挂的：只要一路失败就整体降级成单档，
			// 用户那边表现成"画质低和高没区别"，而日志只写"失败"是查不出来的。
			log.Printf("[短视频] 三档校验失败：缺少 out_%d.m3u8（%v）", i, err)
			return false
		}
		if !strings.Contains(string(b), "#EXTINF") {
			log.Printf("[短视频] 三档校验失败：out_%d.m3u8 里没有任何分片", i)
			return false
		}
	}
	return true
}

// probeShortVideoMediaSize 探测一个媒体文件 / HLS playlist 的编码宽高。
// 与 probeShortVideoDisplaySize 的区别：产物是转码后已转正的画面，不存在旋转元数据，不需要换算。
func probeShortVideoMediaSize(in string) (int, int) {
	fp, err := exec.LookPath("ffprobe")
	if err != nil {
		return 0, 0
	}
	out, err := exec.Command(fp, "-v", "error", "-select_streams", "v:0",
		"-show_entries", "stream=width,height", "-of", "csv=p=0", in).Output()
	if err != nil {
		return 0, 0
	}
	fields := strings.FieldsFunc(strings.TrimSpace(string(out)), func(r rune) bool {
		return r == ',' || r == '\r' || r == '\n' || r == 'x'
	})
	if len(fields) < 2 {
		return 0, 0
	}
	w, _ := strconv.Atoi(strings.TrimSpace(fields[0]))
	h, _ := strconv.Atoi(strings.TrimSpace(fields[1]))
	return w, h
}

// checkHLSKeepAspect 核对转码产物是否保住了源比例——「补黑边事故」的守门员。
// 只要最高可用档的宽高比与源显示比例差超过 2.5%，就说明尺寸又被写坏了（画面会变小或被拉伸），
// 立刻打错误日志。不阻断发布：能播的视频不该因为一次探测失败就被丢掉，但问题必须可见。
func checkHLSKeepAspect(vid int64, hlsDir string, srcW, srcH int) {
	if srcW <= 0 || srcH <= 0 {
		return
	}
	want := float64(srcW) / float64(srcH)
	for i := 2; i >= 0; i-- {
		p := filepath.Join(hlsDir, fmt.Sprintf("out_%d.m3u8", i))
		if _, err := os.Stat(p); err != nil {
			continue
		}
		w, h := probeShortVideoMediaSize(p)
		if w <= 0 || h <= 0 {
			return
		}
		got := float64(w) / float64(h)
		dev := math.Abs(got-want) / want
		if dev > 0.025 {
			log.Printf("[短视频][尺寸异常] vid=%d 第%d档产物 %dx%d（比例 %.4f）与源比例 %.4f 偏差 %.1f%%，画面会变小或被拉伸，请检查 scale/pad 滤镜",
				vid, i, w, h, got, want, dev*100)
		} else {
			log.Printf("[短视频] 尺寸自检通过 vid=%d 第%d档 %dx%d（源 %dx%d 比例 %.4f）", vid, i, w, h, srcW, srcH, want)
		}
		return
	}
}

// hlsDirNeedsRebuild 判断某条视频的 HLS 产物是否已经不合格，需要重转。两种历史问题：
//
//  1. 比例不符：旧实现用 scale+pad 把每一档补成固定 16:9 画布，竖屏画面被塞进横画布。
//  2. 比例对但分辨率不够：档位盒子是 16:9 的 W×H，竖屏源被高度卡死
//     （720x1280 的「1080 档」只能出 607x1080），客户端放大上屏就是糊的。
//     这种产物比例是对的，只看比例发现不了，必须再比一次短边。
//
// 探测失败时返回 false（宁可不重转，也不要把本来就正常的视频反复重跑一遍）。
func hlsDirNeedsRebuild(dir string, wantW, wantH int) bool {
	if wantW <= 0 || wantH <= 0 {
		return false
	}
	// 旧版产物是 5 档或 6 档（out_0..out_4/out_5），档位语义也和现在的三档不同。
	// 只要 out_2.m3u8 的对端不是"低/中/高"这一套，就得重转 —— 用 out_3 是否存在
	// 当作"老产物"的判据：三档方案永远不会生成 out_3。
	if _, err := os.Stat(filepath.Join(dir, "out_3.m3u8")); err == nil {
		return true
	}
	want := float64(wantW) / float64(wantH)
	// 原画档应有的尺寸（与 runFFmpegHLS 的 3840x3840 盒子一致）
	ew, eh := svTierSize(wantW, wantH, 3840, 3840)
	expectedShort := ew
	if eh < ew {
		expectedShort = eh
	}
	for i := 2; i >= 0; i-- {
		p := filepath.Join(dir, fmt.Sprintf("out_%d.m3u8", i))
		if _, err := os.Stat(p); err != nil {
			continue
		}
		w, h := probeShortVideoMediaSize(p)
		if w <= 0 || h <= 0 {
			return false
		}
		if math.Abs(float64(w)/float64(h)-want)/want > 0.025 {
			return true // 被 pad 过
		}
		gotShort := w
		if h < w {
			gotShort = h
		}
		if gotShort < expectedShort-2 {
			return true // 比例对但分辨率被旧档位砍了
		}
		return false
	}
	return false
}

// retranscodePaddedShortVideos 一次性存量修复：把历史上被 pad 成固定 16:9 画布的视频重新转码。
//
// 背景（不改代码救不了）：早期五路 HLS 用 scale+pad 把每一档补成固定 16:9 画布，
// 竖屏源的真实画面被塞进横画布（720x1280 的源，240 档只有 135x240 被放在 426x240 里）。
// 客户端拿到的 VideoSize 是 16:9 → 按横屏排布 → 在竖屏手机上有效画面只剩应得的约 10%。
// 这个比例信息在编码时就烤进像素了，改滤镜只对新上传生效，老产物必须重转。
//
// 判据：display_w/h 若缺失就先 probe 源；再 probe 产物最高档，比例偏差 > 2.5% 即判定被 pad 过。
// 只补元数据不重转的情况：渐进式记录（播的就是原文件，比例天然对）、探测失败、源文件已丢。
// 全部串行 + 间隔 5s，避免和正常转码抢 CPU；跑完写标记文件，之后启动不再扫。
func retranscodePaddedShortVideos() {
	// 标记版本号要随判据升级：
	//  v1 修「补黑边」；v2 修「档位砍短边」；v3 补「原画档」；v4 改低/中/高三档 + CRF；
	//  v5 补「原画直通」（H.264 源零重编码）；v6 修 SAR 滤镜 + 把单档记录也重转出三档。
	// 之前跑过旧版本的机器必须再扫一遍，否则老产物仍然是旧的档位结构。
	marker := filepath.Join(shortVideoDataDir, "aspect_fix_v6.done")
	if _, err := os.Stat(marker); err == nil {
		return
	}
	// 【必须后台】扫描本身就要逐条 probe 源编码 + probe 产物尺寸
	//（几十条视频就是几十次 ffprobe 加读 m3u8），放在 main 的同步路径上会让后端起不来、
	// 接口全部超时——表现出来就是"服务挂了"。
	go func() {
	// 没有 H.264 编码器就产不出多档，重转只会白烧 CPU 再掉回单档
	canEncode := probeFFmpegEncoders()["libx264"]
	if !canEncode {
		log.Printf("[短视频] 修复跳过：服务器没有 libx264，无法产出三档（画质切换会一直无效）")
	}
	rows, err := db.Query(`SELECT id, orig_path, is_hls, display_w, display_h, direct_path
		FROM shortvideo_videos WHERE status = 1 AND orig_path != ''`)
	if err != nil {
		return
	}
	type rec struct {
		id         int64
		orig       string
		src        string // 实际可用的重转源：videos/ 下的原文件，或回退的 hls/<id>/orig.mp4
		isHls      int
		dw, dh     int
		directPath string
	}
	var all []rec
	for rows.Next() {
		var r rec
		if rows.Scan(&r.id, &r.orig, &r.isHls, &r.dw, &r.dh, &r.directPath) == nil {
			all = append(all, r)
		}
	}
	rows.Close()

	var todo []rec
	for i := range all {
		r := &all[i]
		origPath := filepath.Join(shortVideoVideosDir, r.orig)
		if _, e := os.Stat(origPath); e != nil {
			// 上传目录里的原文件没了？回退到 HLS 目录里的 orig.mp4——
			// 那是同一份原片的硬链接/副本（linkOrCopy / finalizeShortVideoFallback 产出）。
			// 没有这层回退，一次误清理就会让老视频永久停在错误比例上，只能靠用户重传。
			alt := filepath.Join(shortVideoHlsDir, strconv.FormatInt(r.id, 10), "orig.mp4")
			if _, e2 := os.Stat(alt); e2 == nil {
				origPath = alt
			} else {
				log.Printf("[短视频] 尺寸修复 vid=%d 跳过：原文件与 hls/orig.mp4 都不在了", r.id)
				continue
			}
		}
		dw, dh := r.dw, r.dh
		if dw <= 0 || dh <= 0 {
			dw, dh = probeShortVideoDisplaySize(origPath)
		}
		if dw <= 0 || dh <= 0 {
			log.Printf("[短视频] 尺寸修复 vid=%d 跳过：探测不到源尺寸（ffprobe 缺失或文件异常）", r.id)
			continue
		}
		// 源是 H.264 却从没尝试过直通 → 旧版转码，重转补出来。
		// 注意必须用 direct_path 判空而不是看文件在不在：转码后无论成功("-")还是失败，
		// 这个字段都会被写上，否则直通失败的视频会在每次启动时被无限重转。
		if r.directPath == "" && probeShortVideoVideoCodec(origPath) == "h264" {
			if _, e := os.Stat(filepath.Join(shortVideoHlsDir, strconv.FormatInt(r.id, 10), "direct.mp4")); e != nil {
				r.src = origPath
				todo = append(todo, *r)
				continue
			}
		}
		// 单档记录（渐进式播放）：整个视频只有一条流，客户端怎么切档都是同一路，
		// 表现就是"画质低和高没区别"。源还在、服务器有 libx264 就重转出三档。
		// 但 H.264「零转码直通」记录（direct_path 指向真实 direct.mp4）是**刻意保留单档**的：
		// 必须跳过，否则每次启动都会把所有 H.264 视频再转一遍，2 核机器直接被转码打爆。
		if r.isHls != 1 {
			if r.directPath != "" && r.directPath != "-" {
				continue
			}
			if canEncode {
				r.src = origPath
				todo = append(todo, *r)
			}
			continue
		}
		if hlsDirNeedsRebuild(filepath.Join(shortVideoHlsDir, strconv.FormatInt(r.id, 10)), dw, dh) {
			r.src = origPath
			todo = append(todo, *r)
			continue
		}
		// 比例没问题，只是老数据缺元数据 → 补上即可，不烧 CPU 重转
		if r.dw <= 0 || r.dh <= 0 {
			db.Exec(`UPDATE shortvideo_videos SET display_w = ?, display_h = ? WHERE id = ?`, dw, dh, r.id)
		}
	}
	if len(todo) == 0 {
		_ = os.WriteFile(marker, []byte(time.Now().Format(time.RFC3339)), 0644)
		log.Printf("[短视频] 尺寸修复：存量 %d 条，无需重转", len(all))
		return
	}
	log.Printf("[短视频] 尺寸修复：%d/%d 条需要重转（档位/比例/直通不符），后台逐条处理", len(todo), len(all))
	for _, r := range todo {
		// 原地覆盖，绝不把 <id>/ 目录改名搬走：buildShortVideoItem 只要发现媒体文件不在，
		// 就会 purgeShortVideo 把整条记录连库带文件删掉——"修复性重转"反而会把视频弄没。
		// 覆盖期间最坏是读到半个 playlist，客户端回退 compat/orig 即可，不会丢数据。
		log.Printf("[短视频] 尺寸修复 vid=%d 开始重转（原地覆盖）", r.id)
		transcodeShortVideo(r.id, r.src)
		time.Sleep(5 * time.Second)
	}
	_ = os.WriteFile(marker, []byte(time.Now().Format(time.RFC3339)), 0644)
	log.Printf("[短视频] 尺寸修复完成，共重转 %d 条", len(todo))
	}()
}

func extractShortVideoCover(in, out string) {
	ff, err := exec.LookPath("ffmpeg")
	if err != nil {
		return
	}
	// 等比缩到最长边 720 内、不补黑边：封面宽高比与视频第一帧完全一致（既不拉伸也不变形），
	// 客户端用 contain 渲染时，封面与播放画面占用同一块区域，切换不跳变。
	// 手机竖拍常被编码成"横屏像素尺寸 + 旋转元数据"，按旋转角 transpose 把封面转成"看到的样子"，
	// 否则竖拍视频的封面是横的、播放是竖的，加载态会先横后竖地闪一下。
	vf := "scale=720:720:force_original_aspect_ratio=decrease"
	switch probeShortVideoRotation(in) {
	case 90:
		vf = "transpose=1," + vf
	case 270:
		vf = "transpose=2," + vf
	case 180:
		vf = "hflip,vflip," + vf
	}
	if runFFmpegLogged(ff, []string{"-ss", "00:00:01", "-i", in, "-frames:v", "1", "-vf", vf, "-q:v", "2", out}, "封面提取") {
		return
	}
	// 兜底：极老版本 ffmpeg 不支持 force_original_aspect_ratio / transpose，退回原生分辨率取帧
	// （封面仍是视频原始比例，不会变形，只是文件可能偏大）。
	_ = runFFmpegLogged(ff, []string{"-ss", "00:00:01", "-i", in, "-frames:v", "1", "-q:v", "2", out}, "封面提取[原生]")
}

// probeShortVideoRotation 取视频轨的显示旋转角（归一化到 0/90/180/270）。
// 手机竖着拍的视频常被编码成横屏像素尺寸 + rotation 元数据；封面必须按此角旋转，
// 才能和 ExoPlayer 实际渲染出的（已旋转）画面方向一致。
func probeShortVideoRotation(in string) int {
	fp, err := exec.LookPath("ffprobe")
	if err != nil {
		return 0
	}
	// 新版 ffmpeg（5.x 起）：旋转角以 display matrix side data 暴露为 stream=rotation
	if n, ok := probeFPScalar(fp, in, "stream=rotation"); ok {
		return ((n % 360) + 360) % 360
	}
	// 旧版 ffmpeg：写在 stream tags 的 rotate 里。两条都要试，
	// 否则服务器装的是老版本时所有竖拍视频的旋转角都会被当成 0。
	if n, ok := probeFPScalar(fp, in, "stream_tags=rotate"); ok {
		return ((n % 360) + 360) % 360
	}
	return 0
}

// probeFPScalar 读单个数值型 ffprobe 字段。
// ok=false 表示「该字段在当前 ffmpeg 上不存在或为空」——必须能和「值确实是 0」区分开，
// 否则新版 ffmpeg 没有 tags=rotate 时会被误判成"旋转 0 度"。
func probeFPScalar(fp, in, entry string) (int, bool) {
	out, err := exec.Command(fp, "-v", "error", "-select_streams", "v:0",
		"-show_entries", entry, "-of", "csv=p=0", in).Output()
	if err != nil {
		return 0, false
	}
	s := strings.TrimSpace(string(out))
	if s == "" {
		return 0, false
	}
	if i := strings.IndexAny(s, ",\r\n"); i >= 0 {
		s = s[:i]
	}
	n, e := strconv.Atoi(strings.TrimSpace(s))
	if e != nil {
		return 0, false
	}
	return n, true
}

// probeShortVideoDisplaySize 取视频「转正后」的真实显示尺寸（已按旋转角交换宽高）。
// 这是客户端排布容器的权威来源：竖拍视频常被编码成横屏像素尺寸 + rotation 元数据，
// 不交换就会把 9:16 当成 16:9，客户端按横屏排布，画面直接被压成横条（缩小约十倍）。
// 返回 (0,0) 表示探测失败，调用方按"未知"处理，不要写入 0 宽高以外的猜测值。
func probeShortVideoDisplaySize(in string) (int, int) {
	fp, err := exec.LookPath("ffprobe")
	if err != nil {
		return 0, 0
	}
	out, err := exec.Command(fp, "-v", "error", "-select_streams", "v:0",
		"-show_entries", "stream=width,height", "-of", "csv=p=0", in).Output()
	if err != nil {
		return 0, 0
	}
	fields := strings.FieldsFunc(strings.TrimSpace(string(out)), func(r rune) bool {
		return r == ',' || r == '\r' || r == '\n' || r == 'x'
	})
	if len(fields) < 2 {
		return 0, 0
	}
	w, _ := strconv.Atoi(strings.TrimSpace(fields[0]))
	h, _ := strconv.Atoi(strings.TrimSpace(fields[1]))
	if w <= 0 || h <= 0 {
		return 0, 0
	}
	switch probeShortVideoRotation(in) {
	case 90, 270:
		w, h = h, w
	}
	return w, h
}

// probeShortVideoHasAudio 判断源文件是否含音轨（无音轨时必须走纯视频 var_stream_map）。
// 探测失败时返回 true，保持"按有音轨处理"的旧行为。
func probeShortVideoHasAudio(in string) bool {
	fp, err := exec.LookPath("ffprobe")
	if err != nil {
		return true
	}
	out, err := exec.Command(fp, "-v", "error", "-select_streams", "a",
		"-show_entries", "stream=index", "-of", "csv=p=0", in).Output()
	if err != nil {
		return true
	}
	return strings.TrimSpace(string(out)) != ""
}

// probeShortVideoVideoCodec 取源视频轨编码名（h264/hevc/av1/...）。
// 返回 "" 表示源确实没有视频轨（纯音频上传，没得可修）；
// 返回 "?" 表示探测不了（ffprobe 缺失/出错）——别和"无视频轨"混为一谈，
// 否则 ffprobe 缺失时所有修复逻辑都会被误判为"无需修复"而静默跳过。
func probeShortVideoVideoCodec(in string) string {
	fp, err := exec.LookPath("ffprobe")
	if err != nil {
		return "?"
	}
	out, err := exec.Command(fp, "-v", "error", "-select_streams", "v:0",
		"-show_entries", "stream=codec_name", "-of", "csv=p=0", in).Output()
	if err != nil {
		return "?"
	}
	return strings.TrimSpace(string(out))
}

// probeFFmpegEncoders 摸底服务器 ffmpeg 带了哪些编码器（只跑一次，之后走缓存）。
// 用途：① HLS 前置检查——没有 H.264 编码器就别烧 CPU 跑注定失败的五路转码；
//
//	② /info 面板直接展示，出问题不再靠猜。
var (
	encoderProbeOnce  sync.Once
	encoderProbeCache map[string]bool
)

func probeFFmpegEncoders() map[string]bool {
	encoderProbeOnce.Do(func() {
		encoderProbeCache = map[string]bool{}
		ff, err := exec.LookPath("ffmpeg")
		if err != nil {
			log.Printf("[短视频] 服务器未安装 ffmpeg：HLS/兼容转码均不可用，全部退原文件")
			return
		}
		out, err := exec.Command(ff, "-hide_banner", "-encoders").CombinedOutput()
		if err != nil {
			log.Printf("[短视频] ffmpeg -encoders 摸底失败: %v", err)
			return
		}
		sc := bufio.NewScanner(bytes.NewReader(out))
		for sc.Scan() {
			line := sc.Text()
			if !strings.HasPrefix(line, " V") && !strings.HasPrefix(line, " A") {
				continue
			}
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				encoderProbeCache[fields[1]] = true
			}
		}
		names := make([]string, 0, len(encoderProbeCache))
		for k := range encoderProbeCache {
			names = append(names, k)
		}
		sort.Strings(names)
		log.Printf("[短视频] ffmpeg 编码器摸底: %v", names)
	})
	return encoderProbeCache
}

// linkOrCopy 优先硬链接（不额外占磁盘），失败且文件不大时再复制。
// 用途：HLS 成功后仍保留一份 orig.mp4，供客户端 HLS 失败时回退。
func linkOrCopy(src, dst string) {
	if _, err := os.Stat(dst); err == nil {
		return
	}
	if err := os.Link(src, dst); err == nil {
		return
	}
	if fi, err := os.Stat(src); err == nil && fi.Size() <= 200<<20 {
		_ = copyFile(src, dst)
	}
}

func probeShortVideoDuration(in string) int {
	fp, err := exec.LookPath("ffprobe")
	if err != nil {
		return 0
	}
	out, err := exec.Command(fp, "-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=noprint_wrappers=1:nokey=1", in).Output()
	if err != nil {
		return 0
	}
	d, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if err != nil {
		return 0
	}
	return int(d)
}

func copyFile(src, dst string) error {
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
	_, err = io.Copy(out, in)
	return err
}

// ---------- 点赞切换 ----------
func handleShortVideoToggleLike(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	vid, _ := strconv.ParseInt(svParam(r, "id"), 10, 64)
	if vid == 0 {
		writeJSON(w, 400, "缺少 id", nil)
		return
	}
	var ownerID, isHls int64
	var videoTitle string
	db.QueryRow("SELECT user_id, is_hls, title FROM shortvideo_videos WHERE id = ?", vid).Scan(&ownerID, &isHls, &videoTitle)

	var cnt int
	db.QueryRow("SELECT COUNT(*) FROM shortvideo_likes WHERE video_id = ? AND user_id = ?", vid, userID).Scan(&cnt)
	var liked bool
	if cnt > 0 {
		db.Exec("DELETE FROM shortvideo_likes WHERE video_id = ? AND user_id = ?", vid, userID)
		liked = false
	} else {
		db.Exec("INSERT INTO shortvideo_likes (video_id, user_id, created_at) VALUES (?, ?, ?)",
			vid, userID, time.Now().Unix())
		liked = true
		// 写入作者的动态（与评论点赞同形态）：「某某点赞了你发布的短视频」
		if ownerID > 0 && ownerID != userID {
			db.Exec("INSERT INTO community_activities (user_id, target_user_id, action_type, target_type, target_id, target_title, comment_content, created_at) VALUES (?, ?, 'like', 'shortvideo', ?, ?, '', ?)",
				userID, ownerID, vid, videoTitle, time.Now().Unix())
			PushToUser(ownerID, "community_activity", map[string]interface{}{
				"action_type":  "like",
				"target_type":  "shortvideo",
				"target_id":    vid,
				"target_title": videoTitle,
			})
		}
	}
	var likes int
	db.QueryRow("SELECT COUNT(*) FROM shortvideo_likes WHERE video_id = ?", vid).Scan(&likes)
	_ = isHls
	writeJSON(w, 200, "ok", map[string]interface{}{"liked": liked, "likes_count": likes})
}

// ---------- 评论 ----------
func handleShortVideoComments(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	vid, _ := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
	if vid == 0 {
		writeJSON(w, 400, "缺少 id", nil)
		return
	}
	userID := getCurrentUserID(r)
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	limit := 50
	offset := (page - 1) * limit
	// 置顶排最前（同一个视频最多一条），其余按时间正序 —— 置顶评论永远在作品描述下方、普通评论上方。
	rows, err := db.Query(`SELECT c.id, c.user_id, u.username, c.content, c.parent_id, c.created_at, c.is_pinned
		FROM shortvideo_comments c LEFT JOIN users u ON c.user_id = u.id
		WHERE c.video_id = ? ORDER BY c.is_pinned DESC, c.created_at ASC LIMIT ? OFFSET ?`, vid, limit, offset)
	if err != nil {
		// 查询失败必须留痕：客户端拿到 500 只会显示"加载失败"，
		// 没有这条日志就完全无法判断是 SQL 结构问题还是数据库问题。
		log.Printf("[短视频] 评论查询失败 vid=%d: %v", vid, err)
		writeJSON(w, 500, "数据库错误", nil)
		return
	}
	defer rows.Close()
	list := []map[string]interface{}{}
	for rows.Next() {
		var id, uid, pid int64
		var username, content sql.NullString
		var created int64
		var pinned int
		if rows.Scan(&id, &uid, &username, &content, &pid, &created, &pinned) != nil {
			continue
		}
		var likeCount int
		db.QueryRow("SELECT COUNT(*) FROM shortvideo_comment_likes WHERE comment_id = ?", id).Scan(&likeCount)
		liked := false
		if userID > 0 {
			var lc int
			db.QueryRow("SELECT COUNT(*) FROM shortvideo_comment_likes WHERE comment_id = ? AND user_id = ?", id, userID).Scan(&lc)
			liked = lc > 0
		}
		list = append(list, map[string]interface{}{
			"id":         id,
			"user_id":    uid,
			"username":   username.String,
			"content":    content.String,
			"parent_id":  pid,
			"avatar":     "/api/avatar/" + strconv.FormatInt(uid, 10),
			"created_at": created,
			"like_count": likeCount,
			"liked":      liked,
			"is_pinned":  pinned == 1,
		})
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"comments": list, "page": page, "limit": limit})
}

func handleShortVideoAddComment(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	vid, _ := strconv.ParseInt(svParam(r, "id"), 10, 64)
	content := strings.TrimSpace(svParam(r, "content"))
	if vid == 0 || content == "" {
		writeJSON(w, 400, "参数缺失", nil)
		return
	}
	runes := []rune(content)
	if len(runes) > 500 {
		content = string(runes[:500])
	}
	pid, _ := strconv.ParseInt(svParam(r, "parent_id"), 10, 64)
	db.Exec("INSERT INTO shortvideo_comments (video_id, user_id, content, parent_id, created_at) VALUES (?, ?, ?, ?, ?)",
		vid, userID, html.EscapeString(content), pid, time.Now().Unix())
	var cc int
	db.QueryRow("SELECT COUNT(*) FROM shortvideo_comments WHERE video_id = ?", vid).Scan(&cc)

	// 动态通知：回复 → 沿回复链通知所有祖先评论作者 + 视频作者；普通评论 → 通知视频作者。
	// action_type 复用社区的 comment/reply，target_type=shortvideo（动态页据此显示"短视频"文案）。
	var videoAuthorID int64
	var videoTitle string
	db.QueryRow("SELECT user_id, title FROM shortvideo_videos WHERE id = ?", vid).Scan(&videoAuthorID, &videoTitle)
	now := time.Now().Unix()
	notified := make(map[int64]bool)
	if pid > 0 {
		curParent := pid
		guard := 0
		for curParent > 0 && guard < 50 {
			guard++
			var ancUserID int64
			if err := db.QueryRow("SELECT user_id FROM shortvideo_comments WHERE id = ?", curParent).Scan(&ancUserID); err != nil {
				break
			}
			if ancUserID > 0 && ancUserID != userID && !notified[ancUserID] {
				notified[ancUserID] = true
				db.Exec("INSERT INTO community_activities (user_id, target_user_id, action_type, target_type, target_id, target_title, comment_content, created_at) VALUES (?, ?, 'reply', 'shortvideo', ?, ?, ?, ?)",
					userID, ancUserID, vid, videoTitle, content, now)
				PushToUser(ancUserID, "community_activity", map[string]interface{}{
					"action_type":  "reply",
					"target_type":  "shortvideo",
					"target_id":    vid,
					"target_title": videoTitle,
				})
			}
			var nextParent int64
			db.QueryRow("SELECT parent_id FROM shortvideo_comments WHERE id = ?", curParent).Scan(&nextParent)
			curParent = nextParent
		}
	}
	if videoAuthorID > 0 && videoAuthorID != userID && !notified[videoAuthorID] {
		db.Exec("INSERT INTO community_activities (user_id, target_user_id, action_type, target_type, target_id, target_title, comment_content, created_at) VALUES (?, ?, 'comment', 'shortvideo', ?, ?, ?, ?)",
			userID, videoAuthorID, vid, videoTitle, content, now)
		PushToUser(videoAuthorID, "community_activity", map[string]interface{}{
			"action_type":  "comment",
			"target_type":  "shortvideo",
			"target_id":    vid,
			"target_title": videoTitle,
		})
	}

	writeJSON(w, 200, "评论成功", map[string]interface{}{"comments_count": cc})
}

// ---------- 评论点赞（toggle） ----------
func handleShortVideoLikeComment(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		CommentID int64 `json:"comment_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.CommentID <= 0 {
		writeJSON(w, 400, "评论ID无效", nil)
		return
	}

	// 评论必须存在，顺带拿作者与所属视频（动态通知用）
	var commentAuthorID, videoID int64
	if err := db.QueryRow("SELECT user_id, video_id FROM shortvideo_comments WHERE id = ?", req.CommentID).Scan(&commentAuthorID, &videoID); err != nil {
		writeJSON(w, 404, "评论不存在", nil)
		return
	}

	var cnt int
	db.QueryRow("SELECT COUNT(*) FROM shortvideo_comment_likes WHERE comment_id = ? AND user_id = ?", req.CommentID, userID).Scan(&cnt)
	now := time.Now().Unix()
	liked := false
	if cnt > 0 {
		db.Exec("DELETE FROM shortvideo_comment_likes WHERE comment_id = ? AND user_id = ?", req.CommentID, userID)
	} else {
		if _, err := db.Exec("INSERT INTO shortvideo_comment_likes (comment_id, user_id, created_at) VALUES (?, ?, ?)", req.CommentID, userID, now); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		liked = true
	}

	var likeCount int
	db.QueryRow("SELECT COUNT(*) FROM shortvideo_comment_likes WHERE comment_id = ?", req.CommentID).Scan(&likeCount)

	if liked && commentAuthorID > 0 && commentAuthorID != userID {
		var videoTitle string
		db.QueryRow("SELECT title FROM shortvideo_videos WHERE id = ?", videoID).Scan(&videoTitle)
		db.Exec("INSERT INTO community_activities (user_id, target_user_id, action_type, target_type, target_id, target_title, comment_content, created_at) VALUES (?, ?, 'comment_like', 'shortvideo', ?, ?, '', ?)",
			userID, commentAuthorID, videoID, videoTitle, now)
		PushToUser(commentAuthorID, "community_activity", map[string]interface{}{
			"action_type":  "comment_like",
			"target_type":  "shortvideo",
			"target_id":    videoID,
			"target_title": videoTitle,
		})
	}

	writeJSON(w, 200, "ok", map[string]interface{}{"liked": liked, "like_count": likeCount})
}

// ---------- 置顶 / 取消置顶评论 ----------
// 权限：开发者、平台管理员、视频发布者（作者）。
// 约束：
//  1. 只允许顶层评论（parent_id=0）——置顶一条回复没有意义，也会破坏"置顶在最上方"的语义；
//  2. 同一视频同时最多一条置顶：置顶时先把该视频其他评论的标记清零，再设目标。
func handleShortVideoPinComment(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		CommentID int64 `json:"comment_id"`
		Pinned    bool  `json:"pinned"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.CommentID <= 0 {
		writeJSON(w, 400, "评论ID无效", nil)
		return
	}

	var videoID, parentID int64
	if err := db.QueryRow(`SELECT video_id, parent_id FROM shortvideo_comments WHERE id = ?`, req.CommentID).
		Scan(&videoID, &parentID); err != nil {
		writeJSON(w, 404, "评论不存在", nil)
		return
	}
	if parentID != 0 {
		writeJSON(w, 400, "只能置顶顶层评论", nil)
		return
	}

	var videoOwner int64
	db.QueryRow(`SELECT user_id FROM shortvideo_videos WHERE id = ?`, videoID).Scan(&videoOwner)

	isManager := isDeveloperUser(userID)
	if !isManager {
		if isAdmin, _ := isPlatformAdmin(userID); isAdmin {
			isManager = true
		}
	}
	if !isManager && videoOwner != userID {
		writeJSON(w, 403, "只有发布者或开发者可以置顶评论", nil)
		return
	}

	if req.Pinned {
		// 先清掉同视频的其他置顶，保证全站唯一
		db.Exec(`UPDATE shortvideo_comments SET is_pinned = 0 WHERE video_id = ?`, videoID)
		db.Exec(`UPDATE shortvideo_comments SET is_pinned = 1 WHERE id = ?`, req.CommentID)
	} else {
		db.Exec(`UPDATE shortvideo_comments SET is_pinned = 0 WHERE id = ?`, req.CommentID)
	}
	log.Printf("[短视频] 用户 %d %s评论 %d（视频 %d）", userID,
		map[bool]string{true: "置顶", false: "取消置顶"}[req.Pinned], req.CommentID, videoID)
	writeJSON(w, 200, "ok", map[string]interface{}{
		"comment_id": req.CommentID,
		"pinned":     req.Pinned,
	})
}

// ---------- 删除评论（自己 / 开发者 / 平台管理员；级联删除所有后代回复） ----------
func handleShortVideoDeleteComment(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		CommentID int64 `json:"comment_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.CommentID <= 0 {
		writeJSON(w, 400, "评论ID无效", nil)
		return
	}

	var authorID int64
	if err := db.QueryRow("SELECT user_id FROM shortvideo_comments WHERE id = ?", req.CommentID).Scan(&authorID); err != nil {
		writeJSON(w, 404, "评论不存在", nil)
		return
	}

	isManager := isDeveloperUser(userID)
	if !isManager {
		if isAdmin, _ := isPlatformAdmin(userID); isAdmin {
			isManager = true
		}
	}
	if authorID != userID && !isManager {
		writeJSON(w, 403, "只能删除自己的评论", nil)
		return
	}

	// 级联收集：目标评论 + 所有层级的后代回复（父楼删了，回复不能留成幽灵数据）
	allIDs := []int64{req.CommentID}
	frontier := []int64{req.CommentID}
	guard := 0
	for len(frontier) > 0 && guard < 100 {
		guard++
		placeholders := make([]string, len(frontier))
		args := make([]interface{}, len(frontier))
		for i, id := range frontier {
			placeholders[i] = "?"
			args[i] = id
		}
		rows, err := db.Query("SELECT id FROM shortvideo_comments WHERE parent_id IN ("+strings.Join(placeholders, ",")+")", args...)
		if err != nil {
			break
		}
		var next []int64
		for rows.Next() {
			var id int64
			if rows.Scan(&id) == nil {
				next = append(next, id)
			}
		}
		rows.Close()
		if len(next) == 0 {
			break
		}
		allIDs = append(allIDs, next...)
		frontier = next
	}

	delComments := func(ids []int64) {
		for i := 0; i < len(ids); i += 100 {
			end := i + 100
			if end > len(ids) {
				end = len(ids)
			}
			chunk := ids[i:end]
			placeholders := make([]string, len(chunk))
			args := make([]interface{}, len(chunk))
			for j, id := range chunk {
				placeholders[j] = "?"
				args[j] = id
			}
			in := "(" + strings.Join(placeholders, ",") + ")"
			db.Exec("DELETE FROM shortvideo_comment_likes WHERE comment_id IN "+in, args...)
			db.Exec("DELETE FROM shortvideo_comments WHERE id IN "+in, args...)
		}
	}
	delComments(allIDs)

	log.Printf("[短视频] 用户 %d 删除评论 %d（级联共 %d 条）", userID, req.CommentID, len(allIDs))
	writeJSON(w, 200, "已删除", map[string]interface{}{"deleted": len(allIDs)})
}

// ---------- 播放量 ----------
func handleShortVideoView(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	vid, _ := strconv.ParseInt(svParam(r, "id"), 10, 64)
	if vid == 0 {
		writeJSON(w, 400, "缺少 id", nil)
		return
	}
	db.Exec("UPDATE shortvideo_videos SET view_count = view_count + 1 WHERE id = ?", vid)
	writeJSON(w, 200, "ok", nil)
}

// handleShortVideoStatus 查询转码状态（前端上传后轮询，做"转码可视化"）。
// status: 0 = 转码中，1 = 已发布可播（转码完成或降级完成）。
func handleShortVideoStatus(w http.ResponseWriter, r *http.Request) {
	if blockShortVideo(w) {
		return
	}
	vid, _ := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
	if vid == 0 {
		writeJSON(w, 400, "缺少 id", nil)
		return
	}
	var status int
	var playURL string
	var isHls int
	err := db.QueryRow(`SELECT status, play_url, is_hls FROM shortvideo_videos WHERE id = ?`, vid).Scan(&status, &playURL, &isHls)
	if err != nil {
		writeJSON(w, 404, "视频不存在", nil)
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{
		"id":       vid,
		"status":   status,
		"play_url": playURL,
		"is_hls":   isHls == 1,
	})
}

// ---------- 删除（真删：DB 记录 + HLS 目录 + 原始文件） ----------
// 权限：开发者可删任意视频；普通用户仅能删自己的。
func handleShortVideoDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	vid, _ := strconv.ParseInt(svParam(r, "id"), 10, 64)
	if vid == 0 {
		writeJSON(w, 400, "缺少 id", nil)
		return
	}
	var owner int64
	var origPath sql.NullString
	if err := db.QueryRow(`SELECT user_id, orig_path FROM shortvideo_videos WHERE id = ?`, vid).
		Scan(&owner, &origPath); err != nil {
		writeJSON(w, 404, "视频不存在", nil)
		return
	}
	if userID != owner && !checkDevPermission(r) {
		writeJSON(w, 403, "无权限删除", nil)
		return
	}

	// 1) 物理删除 HLS 目录：playlist/各码率变体/分片/封面/兜底 orig.mp4
	dir := filepath.Join(shortVideoHlsDir, strconv.FormatInt(vid, 10))
	if err := os.RemoveAll(dir); err != nil {
		log.Printf("[短视频] 删除 HLS 目录失败 vid=%d: %v", vid, err)
	}
	// 2) 物理删除原始上传文件（videos 目录）
	if origPath.Valid && origPath.String != "" {
		p := filepath.Join(shortVideoVideosDir, origPath.String)
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			log.Printf("[短视频] 删除原文件失败 vid=%d: %v", vid, err)
		}
	}
	// 3) 清理关联表 + 主表
	db.Exec("DELETE FROM shortvideo_likes WHERE video_id = ?", vid)
	db.Exec("DELETE FROM shortvideo_comments WHERE video_id = ?", vid)
	db.Exec("DELETE FROM shortvideo_renditions WHERE video_id = ?", vid)
	if _, err := db.Exec("DELETE FROM shortvideo_videos WHERE id = ?", vid); err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	log.Printf("[短视频] 已删除 vid=%d（含物理文件）by user=%d", vid, userID)
	writeJSON(w, 200, "已删除", map[string]interface{}{"id": vid})
}

// ---------- 开发者专属：作品信息 ----------
// 一次性给全「谁发的、什么时候发的、占了多少磁盘、几档码率、多少人看过」，
// 客户端点「作品信息」直接整屏展示，不再逐项去凑接口。
// 权限：仅开发者（普通用户连字段结构都拿不到）。
func handleShortVideoInfo(w http.ResponseWriter, r *http.Request) {
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	vid, _ := strconv.ParseInt(r.URL.Query().Get("id"), 10, 64)
	if vid == 0 {
		writeJSON(w, 400, "缺少 id", nil)
		return
	}
	var (
		it                  ShortVideoItem
		origPath, coverPath sql.NullString
		status, isHls       int
	)
	err := db.QueryRow(`SELECT id, user_id, title, description, orig_path, cover_path, play_url,
		is_hls, duration, display_w, display_h, view_count, status, created_at
		FROM shortvideo_videos WHERE id = ?`, vid).
		Scan(&it.ID, &it.UserID, &it.Title, &it.Description, &origPath, &coverPath, &it.PlayURL,
			&isHls, &it.Duration, &it.Width, &it.Height, &it.ViewCount, &status, &it.CreatedAt)
	if err != nil {
		writeJSON(w, 404, "视频不存在", nil)
		return
	}
	it.IsHLS = isHls == 1
	if it.Width > 0 && it.Height > 0 {
		it.AspectRatio = float64(it.Width) / float64(it.Height)
	}

	// 发布者
	var pubName, pubEmail sql.NullString
	var pubCreated int64
	db.QueryRow(`SELECT username, email, created_at FROM users WHERE id = ?`, it.UserID).
		Scan(&pubName, &pubEmail, &pubCreated)

	// 原始上传文件
	origName := ""
	origSize := int64(0)
	origExists := false
	if origPath.Valid && origPath.String != "" {
		origName = origPath.String
		if fi, e := os.Stat(filepath.Join(shortVideoVideosDir, origName)); e == nil && !fi.IsDir() {
			origSize = fi.Size()
			origExists = true
		}
	}

	// HLS 目录占用（分片数 / 文件数 / 总字节）
	hlsDir := filepath.Join(shortVideoHlsDir, strconv.FormatInt(vid, 10))
	hlsSize := int64(0)
	hlsFiles, hlsSegs := 0, 0
	if _, e := os.Stat(hlsDir); e == nil {
		_ = filepath.Walk(hlsDir, func(p string, info os.FileInfo, err error) error {
			if err != nil || info == nil || info.IsDir() {
				return nil
			}
			hlsFiles++
			hlsSize += info.Size()
			if strings.HasSuffix(p, ".ts") {
				hlsSegs++
			}
			return nil
		})
	}

	// 互动
	likes, comments := 0, 0
	db.QueryRow(`SELECT COUNT(*) FROM shortvideo_likes WHERE video_id = ?`, vid).Scan(&likes)
	db.QueryRow(`SELECT COUNT(*) FROM shortvideo_comments WHERE video_id = ?`, vid).Scan(&comments)
	var lastLike, lastComment int64
	db.QueryRow(`SELECT COALESCE(MAX(created_at),0) FROM shortvideo_likes WHERE video_id = ?`, vid).Scan(&lastLike)
	db.QueryRow(`SELECT COALESCE(MAX(created_at),0) FROM shortvideo_comments WHERE video_id = ?`, vid).Scan(&lastComment)

	// 码率档位
	rits := []ShortVideoRendition{}
	if rrows, e := db.Query(`SELECT quality, codec, bitrate, width, height, playlist
		FROM shortvideo_renditions WHERE video_id = ? ORDER BY quality ASC`, vid); e == nil {
		defer rrows.Close()
		for rrows.Next() {
			var rr ShortVideoRendition
			var pl string
			if rrows.Scan(&rr.Quality, &rr.Codec, &rr.Bitrate, &rr.Width, &rr.Height, &pl) == nil {
				rr.Playlist = "/api/shortvideo/hls/" + pl
				rits = append(rits, rr)
			}
		}
	}

	// 转码能力（面板上直接告诉开发者"这台机器现在到底能不能转码、有哪些编码器"）
	_, ffErr := exec.LookPath("ffmpeg")
	encs := probeFFmpegEncoders()
	encNames := make([]string, 0, len(encs))
	for k := range encs {
		encNames = append(encNames, k)
	}
	sort.Strings(encNames)

	statusText := "转码中"
	switch {
	case status == 1 && it.IsHLS:
		statusText = "已发布（HLS 多码率）"
	case status == 1:
		statusText = "已发布（渐进式降级）"
	}

	coverURL := ""
	if coverPath.Valid && coverPath.String != "" {
		coverURL = "/api/shortvideo/hls/" + coverPath.String
	}

	writeJSON(w, 200, "ok", map[string]interface{}{
		"id":              it.ID,
		"user_id":         it.UserID,
		"title":           it.Title,
		"description":     it.Description,
		"created_at":      it.CreatedAt,
		"duration":        it.Duration,
		"width":           it.Width,
		"height":          it.Height,
		"aspect_ratio":    it.AspectRatio,
		"status":          status,
		"status_text":     statusText,
		"is_hls":          it.IsHLS,
		"degrade_reason":  shortVideoDegradeReason(it.IsHLS, it.PlayURL),
		"view_count":      it.ViewCount,
		"likes_count":     likes,
		"comments_count":  comments,
		"last_like_at":    lastLike,
		"last_comment_at": lastComment,
		"play_url":        it.PlayURL,
		"cover_url":       coverURL,
		"orig_name":       origName,
		"orig_size":       origSize,
		"orig_exists":     origExists,
		"hls_dir":         hlsDir,
		"hls_size":        hlsSize,
		"hls_files":       hlsFiles,
		"hls_segments":    hlsSegs,
		"renditions":      rits,
		"ffmpeg_ok":       ffErr == nil,
		"ffmpeg_encoders": encNames,
		"libx264":         encs["libx264"],
		"publisher": map[string]interface{}{
			"id":           it.UserID,
			"username":     pubName.String,
			"email":        pubEmail.String,
			"avatar":       "/api/avatar/" + strconv.FormatInt(it.UserID, 10),
			"created_at":   pubCreated,
			"is_developer": isDeveloperUser(it.UserID),
		},
	})
}

// ---------- 开发者：重新转码（修复存量播不了的视频） ----------
// 用途：上传时转码失败退到 orig.mp4、而源是 HEVC/AV1 导致客户端播不了的记录，
// 由开发者手动触发重跑（三级兜底流程）。异步执行，立即返回。
func handleShortVideoRetranscode(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	if !checkDevPermission(r) {
		writeJSON(w, 403, "无权限", nil)
		return
	}
	vid, _ := strconv.ParseInt(svParam(r, "id"), 10, 64)
	if vid == 0 {
		writeJSON(w, 400, "缺少 id", nil)
		return
	}
	var orig string
	if err := db.QueryRow(`SELECT orig_path FROM shortvideo_videos WHERE id = ?`, vid).Scan(&orig); err != nil {
		writeJSON(w, 404, "视频不存在", nil)
		return
	}
	if orig == "" {
		writeJSON(w, 400, "该记录没有原始文件，无法重转码", nil)
		return
	}
	origPath := filepath.Join(shortVideoVideosDir, orig)
	if _, err := os.Stat(origPath); err != nil {
		writeJSON(w, 400, "原始文件已丢失，无法重转码", nil)
		return
	}
	// 先清掉旧的失败产物，避免旧 compat/orig 文件干扰新结果
	os.Remove(filepath.Join(shortVideoHlsDir, strconv.FormatInt(vid, 10), "compat.mp4"))
	go transcodeShortVideo(vid, origPath)
	log.Printf("[短视频] 开发者手动重转码 vid=%d", vid)
	writeJSON(w, 200, "已开始重新转码", map[string]interface{}{"id": vid})
}

// ---------- HLS / 分片 / 封面 公开服务 ----------
// 关键点（对应"已看分片不重复请求 / 边缘缓存命中率 / LRU"）：
//   - .ts 分片：public, max-age=86400（一天），由客户端与反向代理复用，已看分片不重复回源；
//   - .m3u8：no-cache（播放列表需即时反映最新分片）；
//   - ETag（基于 mtime+size）+ 304：已看且未变的分片直接 304，省带宽；
//   - Accept-Ranges：支持 Range，按需拉取分片，不整片下载。
func handleShortVideoHLS(w http.ResponseWriter, r *http.Request) {
	// 短视频已关闭：这是**公开路由**（播放器不附带 Bearer），必须在这里直接 404，
	// 否则拿到 play_url / cover_url 的人仍能绕过所有接口直接取流看视频。
	if isShortVideoDisabled() {
		http.NotFound(w, r)
		return
	}
	rel := strings.TrimPrefix(r.URL.Path, "/api/shortvideo/hls/")
	rel = strings.Trim(rel, "/")
	if rel == "" || strings.Contains(rel, "..") || filepath.IsAbs(rel) {
		http.NotFound(w, r)
		return
	}
	full := filepath.Join(shortVideoHlsDir, filepath.Clean(rel))
	info, err := os.Stat(full)
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	ext := strings.ToLower(filepath.Ext(rel))
	switch ext {
	case ".m3u8":
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-cache")
	case ".ts":
		w.Header().Set("Content-Type", "video/mp2t")
		w.Header().Set("Cache-Control", "public, max-age=86400")
	default:
		w.Header().Set("Cache-Control", "public, max-age=86400")
		if ext == ".jpg" || ext == ".jpeg" {
			w.Header().Set("Content-Type", "image/jpeg")
		} else if ext == ".mp4" {
			w.Header().Set("Content-Type", "video/mp4")
		}
	}
	etag := fmt.Sprintf(`"%x-%x"`, info.ModTime().UnixNano(), info.Size())
	w.Header().Set("ETag", etag)
	if match := r.Header.Get("If-None-Match"); match != "" && match == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeFile(w, r, full)
}
