package main

// 群敏感词（群主 / 管理员）
//
// 设计要点：
//   1. 不在发送前拦截 —— 客户端照常发出、服务端照常落库并推送，随后由这里「撤回」。
//      这样不会出现「点了发送却弹一句失败」，与 QQ 群的做法一致。
//   2. 命中后的处置三选一：仅撤回 / 禁言（时长可自定义）/ 移出群聊（可同时拉入群黑名单）。
//   3. 提示复用 sendGroupNotice 这个唯一出口，因此自动跟随该群的「群通知方式」：
//        群身份  → 带 @ 的气泡，可点开对方资料
//        系统广播 → 灰色居中的文字，不带 @
//   4. 文案 = 固定的「@群成员」+ 管理员填的正文，默认正文 "你的消息违反了管理员设定的发言管理规则"。
//   5. 豁免范围：群主可发 / 群主及管理员可发 / 全员不可发，命中检查前先按角色放行。

import (
	"encoding/json"
	"log"
	"net/http"
	"regexp"
	"strings"
	"time"
)

// notifyKeySensitiveWord 群敏感词提示项（可在「群通知方式」里单独关闭）
const notifyKeySensitiveWord = "sensitive_word"

// defaultSensitiveNotice 默认提示正文。
// 客户端固定渲染成「@群成员 + 正文」两段式，所以这里只存正文；
// @ 的对象由服务端按触发者补齐，管理员不可能提前知道是谁。
const defaultSensitiveNotice = "你的消息违反了管理员设定的发言管理规则"

// 豁免范围：谁的发言不参与敏感词检查
//
//	admin 群主及管理员可发（默认）
//	owner 仅群主可发（管理员也会被拦）
//	none  全员不可发
const (
	sensitiveBypassAdmin = "admin"
	sensitiveBypassOwner = "owner"
	sensitiveBypassNone  = "none"
)

// GroupSensitiveSettings 单个群的敏感词配置
type GroupSensitiveSettings struct {
	Enabled       bool     `json:"enabled"`
	Words         []string `json:"words"`
	Action        string   `json:"action"` // recall | mute | kick
	MuteSeconds   int64    `json:"mute_seconds"`
	KickBlacklist bool     `json:"kick_blacklist"`
	NoticeText    string   `json:"notice_text"`
	BypassRole    string   `json:"bypass_role"` // admin | owner | none
	// BlockLinks 禁止发链接：与敏感词开关相互独立，命中后走同一套处置（撤回/禁言/移出）
	BlockLinks bool `json:"block_links"`
}

func ensureGroupSensitiveTables() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS group_sensitive_words (
		group_id INTEGER NOT NULL,
		word TEXT NOT NULL,
		PRIMARY KEY(group_id, word)
	)`); err != nil {
		log.Printf("  提示: group_sensitive_words 表创建失败: %v", err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS group_sensitive_config (
		group_id INTEGER PRIMARY KEY,
		enabled INTEGER NOT NULL DEFAULT 0,
		action TEXT NOT NULL DEFAULT 'recall',
		mute_seconds INTEGER NOT NULL DEFAULT 3600,
		kick_blacklist INTEGER NOT NULL DEFAULT 0,
		notice_text TEXT NOT NULL DEFAULT '',
		bypass_role TEXT NOT NULL DEFAULT 'admin',
		block_links INTEGER NOT NULL DEFAULT 0,
		updated_at INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  提示: group_sensitive_config 表创建失败: %v", err)
	}
	// 老库补列：豁免范围是后加的设置，重复执行会报 duplicate column，忽略即可
	db.Exec("ALTER TABLE group_sensitive_config ADD COLUMN bypass_role TEXT NOT NULL DEFAULT 'admin'")
	// 老库补列：禁止发链接开关
	db.Exec("ALTER TABLE group_sensitive_config ADD COLUMN block_links INTEGER NOT NULL DEFAULT 0")
}

// getGroupSensitiveSettings 读取配置（无记录时给默认值：关闭、仅撤回、模板留空=内置默认）
func getGroupSensitiveSettings(groupID int64) GroupSensitiveSettings {
	s := GroupSensitiveSettings{
		Enabled:     false,
		Words:       []string{},
		Action:      "recall",
		MuteSeconds: 3600,
		NoticeText:  "",
		BypassRole:  sensitiveBypassAdmin,
	}
	if groupID <= 0 {
		return s
	}
	var enabled, kickBlacklist, blockLinks int
	var action, notice, bypass string
	var muteSeconds int64
	err := db.QueryRow(
		`SELECT enabled, action, mute_seconds, kick_blacklist, notice_text, bypass_role, block_links
		 FROM group_sensitive_config WHERE group_id = ?`, groupID,
	).Scan(&enabled, &action, &muteSeconds, &kickBlacklist, &notice, &bypass, &blockLinks)
	if err == nil {
		s.Enabled = enabled == 1
		if action == "mute" || action == "kick" || action == "recall" {
			s.Action = action
		}
		if muteSeconds > 0 {
			s.MuteSeconds = muteSeconds
		}
		s.KickBlacklist = kickBlacklist == 1
		s.NoticeText = notice
		s.BlockLinks = blockLinks == 1
		if bypass == sensitiveBypassOwner || bypass == sensitiveBypassNone || bypass == sensitiveBypassAdmin {
			s.BypassRole = bypass
		}
	}
	rows, err := db.Query("SELECT word FROM group_sensitive_words WHERE group_id = ? ORDER BY word", groupID)
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var w string
			if rows.Scan(&w) == nil && strings.TrimSpace(w) != "" {
				s.Words = append(s.Words, w)
			}
		}
	}
	return s
}

// saveGroupSensitiveSettings 覆盖式保存（词表整体替换，去重、去首尾空白、单条最长 32 字、最多 200 条）
func saveGroupSensitiveSettings(groupID int64, s GroupSensitiveSettings) error {
	action := s.Action
	if action != "mute" && action != "kick" {
		action = "recall"
	}
	muteSeconds := s.MuteSeconds
	if muteSeconds <= 0 {
		muteSeconds = 3600
	}
	enabled := 0
	if s.Enabled {
		enabled = 1
	}
	kickBlacklist := 0
	if s.KickBlacklist {
		kickBlacklist = 1
	}
	blockLinks := 0
	if s.BlockLinks {
		blockLinks = 1
	}
	bypass := s.BypassRole
	if bypass != sensitiveBypassOwner && bypass != sensitiveBypassNone {
		bypass = sensitiveBypassAdmin
	}
	if _, err := db.Exec(
		`INSERT INTO group_sensitive_config
		   (group_id, enabled, action, mute_seconds, kick_blacklist, notice_text, bypass_role, block_links, updated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(group_id) DO UPDATE SET enabled = excluded.enabled, action = excluded.action,
		   mute_seconds = excluded.mute_seconds, kick_blacklist = excluded.kick_blacklist,
		   notice_text = excluded.notice_text, bypass_role = excluded.bypass_role,
		   block_links = excluded.block_links,
		   updated_at = excluded.updated_at`,
		groupID, enabled, action, muteSeconds, kickBlacklist, strings.TrimSpace(s.NoticeText), bypass, blockLinks, time.Now().Unix(),
	); err != nil {
		return err
	}

	seen := make(map[string]bool, len(s.Words))
	cleaned := make([]string, 0, len(s.Words))
	for _, w := range s.Words {
		w = strings.TrimSpace(w)
		if w == "" || len([]rune(w)) > 32 || seen[w] {
			continue
		}
		seen[w] = true
		cleaned = append(cleaned, w)
		if len(cleaned) >= 200 {
			break
		}
	}
	if _, err := db.Exec("DELETE FROM group_sensitive_words WHERE group_id = ?", groupID); err != nil {
		return err
	}
	for _, w := range cleaned {
		db.Exec("INSERT OR IGNORE INTO group_sensitive_words (group_id, word) VALUES (?, ?)", groupID, w)
	}
	return nil
}

// matchSensitiveWord 返回命中的词（不区分大小写；空词表 / 未开启直接不命中）
func matchSensitiveWord(s GroupSensitiveSettings, content string) (string, bool) {
	if !s.Enabled || len(s.Words) == 0 {
		return "", false
	}
	lower := strings.ToLower(content)
	for _, w := range s.Words {
		if w == "" {
			continue
		}
		if strings.Contains(lower, strings.ToLower(w)) {
			return w, true
		}
	}
	return "", false
}

// buildSensitiveNotice 把模板渲染成「群身份」与「系统广播」两种形态
//
// 客户端固定了「@群成员」这一段，管理员只填后面的正文，因此：
//
//	群身份   "@张三 " + 正文      →  "@张三 你的消息违反了管理员设定的发言管理规则"
//	系统广播 昵称 + 正文（「你的」→「的」）
//	          →  "张三的消息违反了管理员设定的发言管理规则"
//
// 兼容旧数据：老版本把整句存在 notice_text 里并带 {user} 占位符，走原来的替换。
func buildSensitiveNotice(template, name string) (string, string) {
	t := strings.TrimSpace(template)
	if t == "" {
		t = defaultSensitiveNotice
	}
	if strings.Contains(t, "{user}") {
		identity := strings.ReplaceAll(t, "{user}", "@"+name)
		broadcast := strings.ReplaceAll(t, "{user}", name)
		broadcast = strings.Replace(broadcast, "你的", "的", 1)
		return identity, broadcast
	}
	identity := "@" + name + " " + t
	// 系统广播是「昵称 + 正文」，中文里两者直接相连容易读成病句：
	//   正文「你的消息违反了…」→「张三的消息违反了…」  ← 去「你」正好接得上
	//   正文「请不要发广告」    →「张三请不要发广告」    ← 硬连，读起来像在对张三下命令
	// 所以只有能接上「的」的时候才直连，其余情况补一个空格。
	var broadcast string
	body := strings.Replace(t, "你的", "的", 1)
	if strings.HasPrefix(body, "的") {
		broadcast = name + body
	} else {
		broadcast = name + " " + body
	}
	return identity, broadcast
}

// sensitiveRoleBypass 判断这次发言是否免检。
// 群主永远有豁免的可能，取决于设置；管理员仅在 admin 档位上免检。
func sensitiveRoleBypass(bypass string, groupID, userID int64) bool {
	switch bypass {
	case sensitiveBypassNone:
		return false
	case sensitiveBypassOwner:
		role, err := getMemberRole(groupID, userID)
		return err == nil && role == "owner"
	default:
		role, err := getMemberRole(groupID, userID)
		return err == nil && (role == "owner" || role == "admin")
	}
}

// ==================== 禁止发链接 ====================

// urlInTextRegex 与 App 端 UrlText 的识别口径保持一致：
// 支持 http(s):// 前缀、www. 开头、裸域名（多级域名 / 端口 / 路径），TLD 允许 2-24 个字母，
// 这样 .asia / .tech / .ai / .space / .blog 等新后缀都能被识别，不给绕过留口子。
// 注意：Go 的 regexp 不支持 `\uXXXX` 转义（写进正则不会编译报错，但 init 时 panic），
// 全角 / 中文标点一律用 `\x{XXXX}` 形式。
var urlInTextRegex = regexp.MustCompile(`(?i)(?:https?://)?(?:www\.)?[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*\.[a-z]{2,24}(?::\d+)?(?:/[^\s\x{3000}-\x{303F}\x{FF01}-\x{FF5E}\x{2018}\x{2019}\x{201C}\x{201D}\x{2026}\x{00AB}\x{00BB}]*)?`)

// emailInTextRegex 用来先把邮箱从文本里剔除：邮箱不是链接，别因为带了个域名就被当成发链接
var emailInTextRegex = regexp.MustCompile(`[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}`)

// fileExtSet 常见文件扩展名：`note.txt`、`index.html`、`main.go` 这类裸文件名会被
// App 端识别成文件引用而不是链接，这里同样排除，避免把发文件名也当成发链接。
var fileExtSet = map[string]bool{
	// 文档 / 办公
	"txt": true, "text": true, "md": true, "markdown": true, "mdx": true, "rst": true, "log": true,
	"rtf": true, "pdf": true, "doc": true, "docx": true, "xls": true, "xlsx": true, "csv": true,
	"tsv": true, "ppt": true, "pptx": true, "odt": true, "ods": true, "odp": true, "epub": true,
	// 数据 / 配置
	"json": true, "json5": true, "yaml": true, "yml": true, "xml": true, "toml": true, "ini": true,
	"cfg": true, "conf": true, "properties": true, "env": true, "lock": true, "sql": true,
	// 网页 / 前端
	"html": true, "htm": true, "xhtml": true, "css": true, "scss": true, "sass": true, "less": true,
	"js": true, "mjs": true, "cjs": true, "jsx": true, "ts": true, "tsx": true, "vue": true,
	"svelte": true, "wasm": true,
	// 代码
	"go": true, "py": true, "java": true, "kt": true, "kts": true, "gradle": true, "rb": true,
	"php": true, "pl": true, "lua": true, "r": true, "swift": true, "cs": true, "rs": true,
	"c": true, "h": true, "cc": true, "cpp": true, "hpp": true, "dart": true, "sh": true,
	"bash": true, "zsh": true, "bat": true, "cmd": true, "ps1": true, "apk": true, "jar": true,
	// 图片 / 音视频 / 压缩包
	"png": true, "jpg": true, "jpeg": true, "gif": true, "webp": true, "bmp": true, "svg": true,
	"ico": true, "heic": true, "mp3": true, "wav": true, "flac": true, "aac": true, "ogg": true,
	"mp4": true, "mov": true, "mkv": true, "avi": true, "webm": true, "zip": true, "rar": true,
	"7z": true, "tar": true, "gz": true, "bz2": true, "xz": true, "exe": true, "dll": true,
}

// trimUrlTail 去掉链接尾部常见的标点（App 端 trimUrlTrailing 的等价实现）
func trimUrlTail(s string) string {
	return strings.TrimRight(s, ".,!?;:)]}>'\"、。，！？；：）】》」』”’…")
}

// isFileNameLike 判断匹配到的是不是「文件引用」而不是链接。
// App 端把 note.txt / dir/file.zip / C:\path\file.kt 这类渲染成文件元素而非可点击链接，
// 这里按同一口径排除，避免把「发个文件名」也当成发链接。
func isFileNameLike(s string) bool {
	lower := strings.ToLower(s)
	if strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://") || strings.HasPrefix(lower, "www.") {
		return false
	}
	// 只看第一段：有路径时它是目录名（dir/file.zip）或域名（example.com/file.zip）
	head := lower
	if i := strings.IndexAny(head, "/\\"); i >= 0 {
		head = head[:i]
	}
	if i := strings.IndexByte(head, ':'); i >= 0 { // 端口 / 盘符
		head = head[:i]
	}
	if !strings.Contains(head, ".") {
		// 第一段没有点 → 纯目录路径（或 C: 盘符），是文件引用
		return true
	}
	lastLabel := head[strings.LastIndex(head, ".")+1:]
	if !fileExtSet[lastLabel] {
		// 第一段是域名（example.com/...）→ 链接
		return false
	}
	// 域名后缀恰好也是文件扩展名（.sh / .md 之类）：带路径按链接算，否则按文件算
	return !strings.Contains(lower, "/")
}

// containsLink 判断消息里是否包含可识别/可解析的链接。
// 口径对齐 App 端：先剔除邮箱，再按 URL 规则匹配，最后排除 note.txt / index.html 这类裸文件名。
func containsLink(content string) bool {
	text := emailInTextRegex.ReplaceAllString(content, " ")
	for _, m := range urlInTextRegex.FindAllString(text, -1) {
		u := trimUrlTail(m)
		if u == "" || isFileNameLike(u) {
			continue
		}
		return true
	}
	return false
}

// enforceGroupSensitiveWords 在消息**发出之后**执行敏感词处置。
// 返回 true 表示命中了（消息已被撤回，调用方可以跳过后续的邮件推送等动作）。
func enforceGroupSensitiveWords(groupID, userID, msgID int64, content, senderName string) bool {
	if groupID <= 0 || msgID <= 0 || strings.TrimSpace(content) == "" {
		return false
	}
	s := getGroupSensitiveSettings(groupID)
	// 按「豁免范围」先放行：群主/管理员自己说的话不该被自己的规则吞掉
	if sensitiveRoleBypass(s.BypassRole, groupID, userID) {
		return false
	}
	// 敏感词与「禁止发链接」互相独立：链接开关即使敏感词关闭也生效
	_, wordHit := matchSensitiveWord(s, content)
	linkHit := s.BlockLinks && containsLink(content)
	if !wordHit && !linkHit {
		return false
	}
	// 处置提示的措辞按命中原因区分，避免把「发链接」也写成「触发敏感词」
	reason := "触发该群敏感词"
	if linkHit && !wordHit {
		reason = "发送链接"
	}

	name := strings.TrimSpace(senderName)
	if name == "" {
		name = groupMemberDisplayName(userID)
	}
	convID := -(1000 + groupID)

	// 群主永不被处置：禁言群主 / 把群主移出群都会让这个群变成「没有群主」的坏状态
	// （手动踢人接口本来就有「不能移除群主」的拦截，这条自动处置路径同样必须拦）。
	// 群主命中时只撤回他自己那条消息，后续禁言 / 移出 / 通知一律跳过。
	isOwner := false
	if role, err := getMemberRole(groupID, userID); err == nil && role == "owner" {
		isOwner = true
	}

	// ① 撤回：这一条必须消失，否则「规则」形同虚设
	if err := recallMessageAsAdmin(msgID); err != nil {
		log.Printf("[群敏感词] 撤回失败 group=%d msg=%d: %v", groupID, msgID, err)
	} else {
		PushRecallToGroup(convID, msgID, userID, name)
		// 群广播会排除发送者自己，但「他自己那条」同样要变灰 —— 单独补一条，
		// 否则发违规词的人自己屏幕上还留着那条，只有重进会话才消失。
		PushToUser(userID, "message_recalled", map[string]interface{}{
			"message_id":  msgID,
			"sender_name": name,
		})
	}

	// 群主命中：到撤回为止，不再禁言 / 移出 / 发提示
	if isOwner {
		log.Printf("[群敏感词] 群主命中，仅撤回 group=%d user=%d msg=%d", groupID, userID, msgID)
		return true
	}

	// ② 处置 + ③ 提示：顺序不能反
	//
	// 群消息拉取接口对非群成员直接 403，而客户端收到推送后是**异步发 HTTP 拉增量**的 ——
	// 一旦先移出，被踢者的那次拉取必然失败，而且他被踢后永远拉不到这两条
	//（它们在库里，但他没有读取权限）。所以通知和处置提示都必须在移出**之前**发出。
	//
	// 会话内的顺序（与用户看到的从上到下一致）：
	//   1. 撤回占位（前面已处理）
	//   2. 提示文案（管理员自定义的那句，sendGroupNotice，全员可见）
	//   3. 处置提示（独立一条，全员可见；本人看到「你因…」，别人看到「某某 因…」）
	// 处置提示不再拼进提示文案句尾 —— 那是两个信息，凑一句反而谁都没说清。
	identity, broadcast := buildSensitiveNotice(s.NoticeText, name)

	muteSeconds := s.MuteSeconds
	if muteSeconds <= 0 {
		muteSeconds = 3600
	}
	// 处置提示正文：**不带人称主语**，人称作主语的部分由客户端按查看者补
	// （被处置者 to_user_name 匹配到自己 → 「你因触发…」；其他人 → 「某某 因触发…」，
	// 与拍一拍按查看者个性化是同一套做法）。
	punishBody := ""
	switch s.Action {
	case "mute":
		// muted_by = 0：系统自动执行，不是某个管理员点的
		if err := groupMuteUser(groupID, userID, muteSeconds, 0); err != nil {
			log.Printf("[群敏感词] 禁言失败 group=%d user=%d: %v", groupID, userID, err)
		} else {
			punishBody = "因" + reason + "被禁言" + formatMuteDuration(muteSeconds)
		}
	case "kick":
		punishBody = "因" + reason + "已被踢出群聊"
	}

	// ③ 提示文案：就是管理员写的那句，不带处置后缀
	noticeMsgID := sendGroupNotice(groupID, notifyKeySensitiveWord, identity, broadcast)

	// ④ 处置提示：独立的一条系统消息，全员可见
	punishMsgID := int64(0)
	if punishBody != "" {
		if pid, err := saveMessage(convID, convID, punishBody, 0, "", "", 0, "", "", 0, 0, 0); err != nil {
			log.Printf("[群敏感词] 处置提示写入失败 group=%d: %v", groupID, err)
		} else {
			punishMsgID = pid
			// to_user_name = 被处置者名字，客户端据此把「他自己的那条」渲染成「你因…」
			db.Exec("UPDATE messages SET to_user_name = ?, msg_type = 'system' WHERE id = ?", name, pid)
			PushToGroup(convID, pid, convID, "", convID, punishBody, time.Now().Unix(), "", "", 0, 0, "", "")
		}
	}

	// ⑤ 移出放在所有群消息之后（见②的顺序说明）
	if s.Action == "kick" {
		if err := removeFromGroup(groupID, userID); err != nil {
			log.Printf("[群敏感词] 移出群失败 group=%d user=%d: %v", groupID, userID, err)
		} else {
			if s.KickBlacklist {
				if err := addGroupBlacklist(groupID, userID, 0); err != nil {
					log.Printf("[群敏感词] 拉黑失败 group=%d user=%d: %v", groupID, userID, err)
				}
			}
			// 定向通知本人：他已不是群成员，③④ 的群消息他**永远拉不到**（接口对非成员 403），
			// 所以这两条的内容随这条推送带下去，由客户端落成本地提示补给他。
			//   text         → 处置提示的第二人称版（「你因触发该群敏感词已被踢出群聊」），
			//                  同时也是会话底部只读态的文案
			//   extra_notices→ 他要以本地广播形式补进的行：先是提示文案（他看到的形态
			//                  跟随该群的通知方式），再是处置提示的第二人称版
			//   silent       → 不再额外落「你已被踢出群聊」那条本地广播，
			//                  否则和处置提示重复（截图里那三行就是这么来的）
			var groupName string
			db.QueryRow("SELECT COALESCE(name, '') FROM groups WHERE id = ?", groupID).Scan(&groupName)
			// 给被踢者的副本：他已不是成员，③④ 的群消息永远拉不到（接口对非成员 403）。
			// extra_msgs 按服务端真实形态逐条带下（id = 服务端消息 id）：
			//   type ""     → 群身份气泡（以群会话为发送者的普通消息）
			//   type system → 居中系统提示（msg_type='system'）
			// 客户端按**同样的形态**本地落库 —— 群身份模式下他看到的也是带群头像的气泡，
			// 而不是把「@某人 …」渲染成居中灰字（那正是广播形态，两种模式就串了）。
			// id 用服务端真身：将来他重新入群拉到同一条历史时按 id 去重，不会出现两份。
			// extra_notices（纯文本）保留给旧版客户端兜底，新版读到 extra_msgs 就忽略它。
			noticeForHim := identity
			noticeType := ""
			if getGroupNotifySettings(groupID).Mode == notifyModeBroadcast {
				noticeForHim = strings.ReplaceAll(broadcast, "@", "")
				noticeType = "system"
			}
			extraMsgs := []map[string]interface{}{}
			if noticeMsgID > 0 {
				extraMsgs = append(extraMsgs, map[string]interface{}{
					"id": noticeMsgID, "text": noticeForHim, "type": noticeType,
				})
			}
			if punishMsgID > 0 {
				extraMsgs = append(extraMsgs, map[string]interface{}{
					"id": punishMsgID, "text": punishBody, "type": "system", "target": name,
				})
			}
			kickText := "你因" + reason + "已被踢出群聊"
			PushToUser(userID, "group_kicked", map[string]interface{}{
				"group_id":      groupID,
				"conv_id":       convID,
				"group_name":    groupName,
				"operator_name": "",
				"text":          kickText,
				"silent":        1,
				"extra_msgs":    extraMsgs,
				"extra_notices": []string{noticeForHim, kickText},
				"at":            time.Now().Unix(),
			})
		}
	}

	log.Printf("[群敏感词] group=%d user=%d msg=%d action=%s reason=%s", groupID, userID, msgID, s.Action, reason)
	return true
}

// ==================== HTTP 接口 ====================

func canManageGroupSensitive(groupID, userID int64) bool {
	if isDeveloperUser(userID) {
		return true
	}
	role, err := getMemberRole(groupID, userID)
	return err == nil && (role == "owner" || role == "admin")
}

// /api/group/sensitive
//
//	GET  ?group_id=123   读取配置（群成员均可读，用于展示摘要）
//	POST {group_id, enabled, words, action, mute_seconds, kick_blacklist, notice_text}  保存（群主 / 管理员）
func handleGroupSensitiveSettings(w http.ResponseWriter, r *http.Request) {
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	switch r.Method {
	case "GET":
		groupID := parseInt64Safe(r.URL.Query().Get("group_id"))
		if groupID <= 0 {
			writeJSON(w, 400, "缺少 group_id", nil)
			return
		}
		if !isGroupMember(groupID, currentUserID) && !isDeveloperUser(currentUserID) {
			writeJSON(w, 403, "你不在该群中", nil)
			return
		}
		writeJSON(w, 200, "获取成功", getGroupSensitiveSettings(groupID))
	case "POST":
		var req struct {
			GroupID       int64    `json:"group_id"`
			Enabled       bool     `json:"enabled"`
			Words         []string `json:"words"`
			Action        string   `json:"action"`
			MuteSeconds   int64    `json:"mute_seconds"`
			KickBlacklist bool     `json:"kick_blacklist"`
			NoticeText    string   `json:"notice_text"`
			BypassRole    string   `json:"bypass_role"`
			BlockLinks    bool     `json:"block_links"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID <= 0 {
			writeJSON(w, 400, "参数不完整", nil)
			return
		}
		if !canManageGroupSensitive(req.GroupID, currentUserID) {
			writeJSON(w, 403, "仅群主或管理员可设置群敏感词", nil)
			return
		}
		if !hasGroupPerm(req.GroupID, currentUserID, "group_sensitive") {
			writeJSON(w, 403, "没有「"+groupPermLabel("group_sensitive")+"」的权限", nil)
			return
		}
		err := saveGroupSensitiveSettings(req.GroupID, GroupSensitiveSettings{
			Enabled:       req.Enabled,
			Words:         req.Words,
			Action:        req.Action,
			MuteSeconds:   req.MuteSeconds,
			KickBlacklist: req.KickBlacklist,
			NoticeText:    req.NoticeText,
			BypassRole:    req.BypassRole,
			BlockLinks:    req.BlockLinks,
		})
		if err != nil {
			log.Printf("[群敏感词] 保存失败 group=%d: %v", req.GroupID, err)
			writeJSON(w, 500, "保存失败", nil)
			return
		}
		writeJSON(w, 200, "已保存", getGroupSensitiveSettings(req.GroupID))
	default:
		writeJSON(w, 405, "仅支持 GET/POST", nil)
	}
}

// /api/group/blacklist
//
//	GET  ?group_id=123  黑名单列表（群主 / 管理员）
//	POST {group_id, user_id}  移出黑名单（解除拉黑，之后可以重新加群）
func handleGroupBlacklist(w http.ResponseWriter, r *http.Request) {
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	switch r.Method {
	case "GET":
		groupID := parseInt64Safe(r.URL.Query().Get("group_id"))
		if groupID <= 0 {
			writeJSON(w, 400, "缺少 group_id", nil)
			return
		}
		if !canManageGroupSensitive(groupID, currentUserID) {
			writeJSON(w, 403, "仅群主或管理员可查看", nil)
			return
		}
		if !hasGroupPerm(groupID, currentUserID, "group_blacklist") {
			writeJSON(w, 403, "没有「"+groupPermLabel("group_blacklist")+"」的权限", nil)
			return
		}
		items := make([]map[string]interface{}, 0)
		rows, err := db.Query(
			`SELECT gb.user_id, COALESCE(u.username, ''), gb.blocked_at
			 FROM group_blacklist gb LEFT JOIN users u ON u.id = gb.user_id
			 WHERE gb.group_id = ? ORDER BY gb.blocked_at DESC`, groupID,
		)
		if err != nil {
			writeJSON(w, 500, "获取失败", nil)
			return
		}
		defer rows.Close()
		for rows.Next() {
			var uid, at int64
			var name string
			if rows.Scan(&uid, &name, &at) == nil {
				items = append(items, map[string]interface{}{
					"user_id": uid, "username": name, "blocked_at": at,
				})
			}
		}
		writeJSON(w, 200, "获取成功", map[string]interface{}{"items": items})
	case "POST":
		var req struct {
			GroupID int64 `json:"group_id"`
			UserID  int64 `json:"user_id"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID <= 0 || req.UserID <= 0 {
			writeJSON(w, 400, "参数不完整", nil)
			return
		}
		if !canManageGroupSensitive(req.GroupID, currentUserID) {
			writeJSON(w, 403, "仅群主或管理员可操作", nil)
			return
		}
		if !hasGroupPerm(req.GroupID, currentUserID, "group_blacklist") {
			writeJSON(w, 403, "没有「"+groupPermLabel("group_blacklist")+"」的权限", nil)
			return
		}
		if _, err := db.Exec("DELETE FROM group_blacklist WHERE group_id = ? AND user_id = ?",
			req.GroupID, req.UserID); err != nil {
			writeJSON(w, 500, "解除失败", nil)
			return
		}
		writeJSON(w, 200, "已解除", nil)
	default:
		writeJSON(w, 405, "仅支持 GET/POST", nil)
	}
}
