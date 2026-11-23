package main

import (
	"encoding/json"
	"fmt"
	"html"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// ==================== 用户级黑名单（拉黑 / 拉出） ====================

// POST /api/user/block — 将某个用户拉入黑名单（对称：对方也能在其黑名单看到你并拉出）。
// 拉黑后若双方是好友则自动解除好友关系（规则 b）。
func handleBlockUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		TargetUserID int64 `json:"target_user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.TargetUserID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	if req.TargetUserID == currentUserID {
		writeJSON(w, 400, "不能拉黑自己", nil)
		return
	}
	target, err := findUserByID(req.TargetUserID)
	if err != nil || target == nil {
		writeJSON(w, 404, "用户不存在", nil)
		return
	}
	if err := blockUser(currentUserID, req.TargetUserID); err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	log.Printf("[黑名单] 用户 %d 将 %d 拉入黑名单", currentUserID, req.TargetUserID)
	writeJSON(w, 200, "已拉入黑名单", map[string]interface{}{"target_user_id": req.TargetUserID})
}

// POST /api/user/unblock — 将某个用户移出黑名单（拉出）。
func handleUnblockUser(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		TargetUserID int64 `json:"target_user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.TargetUserID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	if err := unblockUser(currentUserID, req.TargetUserID); err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}
	log.Printf("[黑名单] 用户 %d 将 %d 移出黑名单", currentUserID, req.TargetUserID)
	writeJSON(w, 200, "已移出黑名单", map[string]interface{}{"target_user_id": req.TargetUserID})
}

// GET /api/user/blacklist — 获取我的黑名单列表（含双向）。
func handleGetBlacklist(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	items, err := getBlacklist(currentUserID)
	if err != nil {
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if items == nil {
		items = []BlacklistItem{}
	}
	writeJSON(w, 200, "获取成功", items)
}

// GET /api/user/is-blocked?target_user_id= — 查询我与某用户之间的黑名单关系（用于前端菜单文案）。
func handleIsBlocked(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	targetID, _ := strconv.ParseInt(r.URL.Query().Get("target_user_id"), 10, 64)
	if targetID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	blocked, err := isBlockedAnyDir(currentUserID, targetID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	var iAmBlocker int
	db.QueryRow("SELECT COUNT(*) FROM user_blacklist WHERE blocker_id = ? AND blocked_id = ?", currentUserID, targetID).Scan(&iAmBlocker)
	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"is_blocked":   blocked,
		"i_am_blocker": iAmBlocker > 0,
	})
}

// ==================== 消息 ====================

func handleSendMessage(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	// JWT 认证后的用户 ID（由 authMiddleware 注入）
	fromUserID := getCurrentUserID(r)
	if fromUserID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}

	var req struct {
		ToUserID      int64  `json:"to_user_id"`
		Content       string `json:"content"`
		ReplyTo       int64  `json:"reply_to"`
		ReplyToText   string `json:"reply_to_text"`
		ReplyToSender string `json:"reply_to_sender"`
		MediaType     string `json:"media_type"`
		MediaURL      string `json:"media_url"`
		FlashDuration int    `json:"flash_duration"`
		ContactSource string `json:"contact_source"`
		MediaWidth    int    `json:"media_width"`
		MediaHeight   int    `json:"media_height"`
		ClientSendAt  int64  `json:"client_send_at"`
		// @ 目标映射 JSON（{"发送时昵称": 用户ID}）：昵称会变，ID 不会，用于改名后仍能定位被 @ 的人
		Mentions string `json:"mentions"`
	}
	rawBody, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	// 防脚本裸调接口：校验客户端签名（仅官方 App 持有登录时下发的动态密钥）
	if enforceClientSign && !verifyClientSignature(r, rawBody, fromUserID) {
		log.Printf("[消息] 客户端签名校验失败，拒绝裸调: ip=%s user=%d", getClientIP(r), fromUserID)
		writeJSON(w, 403, "请求校验失败，请使用官方客户端发送", nil)
		return
	}
	if err := json.Unmarshal(rawBody, &req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	// ── 底防：全面禁言 / 官群禁言 ──
	// 全面禁言：除开发者外所有用户的消息（群聊/私聊等）一律强制截止发送
	if getFlag("full_mute") && !isDeveloperUser(fromUserID) {
		writeJSON(w, 403, "全站已开启全面禁言，消息发送已暂停", nil)
		return
	}
	// 官群禁言：官方群(display_id=1, convID=-1001)除开发者外禁止发言
	if getFlag("official_group_mute") && !isDeveloperUser(fromUserID) && req.ToUserID == -1001 {
		writeJSON(w, 403, "官方群已开启禁言，暂不可发言", nil)
		return
	}

	// 通用发送限流：防脚本裸调接口批量刷消息（对所有用户生效；开发者 ID=1 豁免）。
	// 规则见 rate_limit.go checkMsgSendRateLimit：单用户 10 秒内最多 50 条 + 两条间隔至少 120ms。
	if fromUserID != 1 {
		if allowed, wait := checkMsgSendRateLimit(fromUserID); !allowed {
			log.Printf("[消息] 发送限流: user=%d 等待 %d 秒", fromUserID, wait)
			writeJSON(w, 429, fmt.Sprintf("发送过于频繁，请 %d 秒后再试", wait), nil)
			return
		}
	}

	// 媒体消息：content 可以为空，media_url 必须有值
	isMedia := req.MediaType != "" && req.MediaURL != ""
	content := req.Content
	if !isMedia {
		content = html.EscapeString(strings.TrimSpace(req.Content))
		if content == "" {
			writeJSON(w, 400, "消息内容不能为空", nil)
			return
		}
		// 长度上限放宽（原为 10000）：分享聊天卡片会把整段聊天记录内嵌在一条消息里，
		// 1 万字符远不够用。这里只保留一个极高的兜底值，防止单个超大报文把服务器打爆。
		if len([]rune(content)) > 1000000 {
			writeJSON(w, 400, "消息内容过长", nil)
			return
		}
	}

	// 分享聊天等「聊天记录」类卡片：把内嵌媒体复制成独立副本，避免原消息撤回/删除后分享内容失效
	content = rewriteShareCardMediaURLs(content)

	log.Printf("[消息] 收到: from=%d to=%d media=%s", fromUserID, req.ToUserID, req.MediaType)

	// 私聊时校验收发双方ID是否有效
	if req.ToUserID > 0 {
		sender, err := findUserByID(fromUserID)
		if err != nil {
			log.Printf("[消息] findUserByID(sender=%d) 查询失败: %v", fromUserID, err)
			writeJSON(w, 500, "服务器内部错误", nil)
			return
		}
		if sender == nil || sender.Username == "注销用户" {
			writeJSON(w, 410, "您的账号异常，消息发送已被禁止", nil)
			return
		}
		receiver, err := findUserByID(req.ToUserID)
		if err != nil {
			log.Printf("[消息] findUserByID(receiver=%d) 查询失败: %v", req.ToUserID, err)
			writeJSON(w, 500, "服务器内部错误", nil)
			return
		}
		if receiver == nil || receiver.Username == "注销用户" {
			writeJSON(w, 410, "对方账号异常，无法发送消息", nil)
			return
		}
	}

	// 黑名单拦截（私聊）：双方存在任一方向黑名单时禁止发送消息（规则 c）
	if req.ToUserID > 0 {
		if blocked, _ := isBlockedAnyDir(fromUserID, req.ToUserID); blocked {
			var iAmBlocker int
			db.QueryRow("SELECT COUNT(*) FROM user_blacklist WHERE blocker_id = ? AND blocked_id = ?", fromUserID, req.ToUserID).Scan(&iAmBlocker)
			if iAmBlocker > 0 {
				writeJSON(w, 403, "你已将对方拉入黑名单", nil)
			} else {
				writeJSON(w, 403, "对方已将你拉入黑名单", nil)
			}
			return
		}
	}

	// 非好友禁止向我发消息（私聊）：
	// 接收方在「安全防护 → 其他」里开启后，非好友将无法给他发私聊。
	// 排除自己给自己（自聊/文件传输）、开发者账号（测试与官方通知）、群聊（ToUserID < 0 不走这里）。
	if req.ToUserID > 0 && req.ToUserID != fromUserID && !isDeveloperUser(fromUserID) {
		if isStrangerMsgBlocked(req.ToUserID) {
			if friends, _ := areFriends(fromUserID, req.ToUserID); !friends {
				log.Printf("[消息] 拦截非好友私聊: from=%d to=%d（接收方已开启非好友禁止发消息）", fromUserID, req.ToUserID)
				writeJSON(w, 403, "对方已开启「非好友禁止发消息」，请先添加对方为好友", nil)
				return
			}
		}
	}

	// 群聊消息：校验群存在且发送者未被拉黑（不检查 group_members，兼容官方群自动同步机制）
	if req.ToUserID < 0 {
		sender, err := findUserByID(fromUserID)
		if err != nil {
			log.Printf("[消息] findUserByID(sender=%d) 查询失败: %v", fromUserID, err)
			writeJSON(w, 500, "服务器内部错误", nil)
			return
		}
		if sender == nil || sender.Username == "注销用户" {
			writeJSON(w, 410, "您的账号异常，消息发送已被禁止", nil)
			return
		}
		internalGroupID := -(req.ToUserID + 1000)
		grp, _ := getGroupByID(internalGroupID)
		if grp == nil {
			log.Printf("[消息] 群不存在: group=%d", internalGroupID)
			writeJSON(w, 404, "群聊不存在", nil)
			return
		}
		blocked, _ := isGroupBlacklisted(internalGroupID, fromUserID)
		if blocked {
			log.Printf("[消息] 用户已被拉黑: group=%d, user=%d", internalGroupID, fromUserID)
			writeJSON(w, 403, "您已被移出该群", nil)
			return
		}

		// 检查群内禁言（开发者始终豁免）
		gmuted, gmuteExpires := isGroupMuted(internalGroupID, fromUserID)
		if gmuted && !isDeveloperUser(fromUserID) {
			log.Printf("[消息] 用户在群内被禁言: group=%d, user=%d, expires=%d", internalGroupID, fromUserID, gmuteExpires)
			writeJSON(w, 403, "你在该群已被禁言，无法发送消息", nil)
			return
		}

		// 群内发言频率限制（开发者始终不受限）：
		// limit = -1 未开启；0 全员禁言；N>0 每位成员每分钟最多 N 条。
		// scope = 0 群主及管理员不受限（默认）；1 管理员受限、群主不受限；2 全部受限（群主也受限）。
		// 计数按「本群 + 本人」维度统计最近 60 秒自己的消息数，与该群其他成员互不影响。
		if grp.MsgRateLimit >= 0 {
			role, _ := getMemberRole(internalGroupID, fromUserID)
			exempt := isDeveloperUser(fromUserID) ||
				(grp.MsgRateScope <= 0 && (role == "owner" || role == "admin")) ||
				(grp.MsgRateScope == 1 && role == "owner")
			if !exempt {
				if allowed, wait := checkGroupMsgRate(internalGroupID, fromUserID, grp.MsgRateLimit); !allowed {
					if grp.MsgRateLimit == 0 {
						log.Printf("[消息] 群内全员禁言，拒绝发送: group=%d user=%d", internalGroupID, fromUserID)
						writeJSON(w, 429, "当前群已开启全员禁言，暂时无法发言", nil)
					} else {
						log.Printf("[消息] 群内发言频率超限: group=%d user=%d limit=%d wait=%d", internalGroupID, fromUserID, grp.MsgRateLimit, wait)
						// 文案改为系统广播风格（不再带"请 X 秒后再试"），由客户端渲染成居中系统提示而非弹窗。
						writeJSON(w, 429, fmt.Sprintf("本群一分钟内只能发 %d 条消息", grp.MsgRateLimit), nil)
					}
					return
				}
			}
		}

		// 越权防护：需要审核入群的群，未通过审核（非群成员）禁止发言，与消息读取侧保持一致。
		// 仅针对开启了"入群需申请"的群，普通群/官方自动同步群不受影响（成员校验通过）。
		if getGroupJoinRequired(internalGroupID) && !isGroupMember(internalGroupID, fromUserID) {
			log.Printf("[消息] 越权拦截: 非群成员(待审核)尝试在群内发言 group=%d user=%d", internalGroupID, fromUserID)
			writeJSON(w, 403, "你尚未通过入群审核，无法在群内发言", nil)
			return
		}

		// 官方群（display_id=1, convID=-1001）：普通成员禁止发送图片/文件等媒体消息，仅开发者（ID=1）可发。
		// 说明：媒体判定放宽为「media_url 或 media_type 任一非空」，避免客户端只带其一绕过限制；
		//      开发者以 ID==1 为准（兼容与开发者 QQ/邮箱一致的账号）。
		// 表情消息豁免：sticker（media_url=sticker://组/名）与 emoji（media_url 直接是 emoji 字符）
		// 都只是本机资源引用，不占服务器存储、不产生媒体流量，普通成员也应能发表情
		// （与微信/QQ 官方群一致）。注意必须两种都豁免：emoji 消息由表情面板的 emoji tab 发出，
		// 早期只判断了 "sticker"，导致 emoji 在官方群被当成图片/文件拦截。
		isStickerMsg := req.MediaType == "sticker" || req.MediaType == "emoji"
		hasMediaAttachment := !isStickerMsg && (req.MediaURL != "" || req.MediaType != "")
		if req.ToUserID == -1001 && hasMediaAttachment && fromUserID != 1 && !isDeveloperUserObj(sender) {
			log.Printf("[消息] 官方群禁止非开发者发送媒体: user=%d, qq=%s", fromUserID, sender.QQNumber)
			writeJSON(w, 403, "官方群仅开发者可发送图片、视频、文件", nil)
			return
		}

		// 未验证邮箱用户 → 官方群限速 1 条/60 秒
		if req.ToUserID == -1001 && sender.EmailVerified == 0 {
			if allowed, wait := checkUnverifiedMsgRate(fromUserID); !allowed {
				log.Printf("[消息] 未验证用户限速: user=%d 等待 %d 秒", fromUserID, wait)
				writeJSON(w, 429, fmt.Sprintf("您的账号当前处于受限状态，官方群每分钟只能发送 1 条消息，请 %d 秒后再试", wait), nil)
				return
			}
		}
	}

	// 推送链路修复：handler 层 content 若为 sess:v1: 密文，此处先解密为明文再落库与推送，
	// 避免通知栏显示密文；decryptSessionContent 幂等（非 sess:v1: 原样返回），不影响旧客户端明文。
	content = decryptSessionContent(fromUserID, content)

	msgID, err := saveMessage(fromUserID, req.ToUserID, content, req.ReplyTo, req.MediaType, req.MediaURL, req.FlashDuration, req.ReplyToText, req.ReplyToSender, req.MediaWidth, req.MediaHeight, req.ClientSendAt, req.Mentions)
	if err != nil {
		log.Printf("[消息] 保存失败: %v", err)
		writeJSON(w, 500, "发送失败", nil)
		return
	}

	// 陌生人首条消息来源：仅当该对话此前无任何消息时记录（search / group:<群名>），
	// 供客户端在首条消息上方展示「对方通过搜索/某某群聊与你联系」系统广播。
	storedSource := ""
	if req.ContactSource != "" {
		var cnt int
		db.QueryRow("SELECT COUNT(*) FROM messages WHERE (from_user_id = ? AND to_user_id = ?) OR (from_user_id = ? AND to_user_id = ?)", fromUserID, req.ToUserID, req.ToUserID, fromUserID).Scan(&cnt)
		if cnt <= 1 { // 含刚插入的这条，故 <=1 表示此前无历史消息
			storedSource = req.ContactSource
			db.Exec("UPDATE messages SET contact_source = ? WHERE id = ?", storedSource, msgID)
		}
	}

	log.Printf("[消息] 保存成功: id=%d", msgID)

	// 获取已保存消息（含 reply_to 字段），用于 TCP 推送
	savedMsg, _ := getMessageByID(msgID)
	replyToID := int64(0)
	replyToText := ""
	replyToSender := ""
	if savedMsg != nil {
		replyToID = savedMsg.ReplyToID
		replyToText = savedMsg.ReplyToText
		replyToSender = savedMsg.ReplyToSender
	}

	// TCP 推送通知接收方
	sender, _ := findUserByID(fromUserID)
	senderName := ""
	if sender != nil {
		senderName = sender.Username
	}

	if req.ToUserID > 0 {
		if req.ToUserID == fromUserID {
			// 个人对话（服务端自聊）：自己发给自己，不能再推 new_message —— 那会被客户端当成
			// 「有人给我发消息」触发未读/通知，还会和本地乐观气泡抢着上屏造成回显抖动。
			// 只回落库回执，客户端据此做增量对齐；也不给自己发离线邮件。
			PushMessagePersistedReceipt(fromUserID, req.ToUserID, msgID)
		} else {
			// 私聊：推送给指定用户
			PushToUser(req.ToUserID, "new_message", map[string]interface{}{
				"message_id":      msgID,
				"from_user_id":    fromUserID,
				"from_username":   senderName,
				"to_user_id":      req.ToUserID,
				"content":         content,
				"created_at":      time.Now().Unix(),
				"media_type":      req.MediaType,
				"media_url":       req.MediaURL,
				"flash_duration":  req.FlashDuration,
				"reply_to_id":     replyToID,
				"reply_to_text":   replyToText,
				"reply_to_sender": replyToSender,
				"contact_source":  storedSource,
				"mentions":        req.Mentions,
			})
			// 邮箱推送：接收者离线超5分钟且非免打扰时发信
			maybeEmailPushForMessage(req.ToUserID, fromUserID, senderName, content, false, "", 0)
		}
	} else if req.ToUserID < 0 {
		// 群聊：推送给群内所有在线成员（排除发送者自己）
		PushToGroup(req.ToUserID, msgID, fromUserID, senderName, req.ToUserID, content,
			time.Now().Unix(), req.MediaType, req.MediaURL, req.FlashDuration,
			replyToID, replyToText, replyToSender, req.Mentions)
		// 邮箱推送：遍历群成员，被@的离线成员触发
		internalGroupID := -(req.ToUserID + 1000)
		// 群敏感词：消息已经落库、也已经推给群成员 —— 这里做「发出之后」的处置
		// （撤回 / 禁言 / 移出群聊），不堵住用户发送。命中就直接返回成功，
		// 不再往下走邮件推送：违规内容没必要再发一封邮件到离线成员的邮箱。
		if enforceGroupSensitiveWords(internalGroupID, fromUserID, msgID, content, senderName) {
			writeJSON(w, 200, "发送成功", map[string]int64{"message_id": msgID})
			return
		}
		grp, _ := getGroupByID(internalGroupID)
		grpName := ""
		if grp != nil {
			grpName = grp.Name
		}
		if members, err := getGroupMembers(internalGroupID); err == nil && !isOfficialGroupNoNotify(req.ToUserID) {
			for _, uid := range members {
				if uid == fromUserID {
					continue
				}
				maybeEmailPushForMessage(uid, fromUserID, senderName, content, true, grpName, req.ToUserID)
			}
		}
	}

	writeJSON(w, 200, "发送成功", map[string]int64{"message_id": msgID})
}

// ==================== token 转账 ====================

// 转账消息正文前缀（Android 端 parseTransferMessage 据此解析结构化字段）
const transferMsgPrefix = "系统转账数据"

// POST /api/transfer — token 转账（扣发送方、加接收方、写流水、推送收款通知 + 会话卡片）
// 视角由结构化字段驱动，不在服务端写死文案，避免群聊/私聊视角错乱。
func handleTransfer(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	fromUserID := getCurrentUserID(r)
	if fromUserID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}

	var req struct {
		ToUserID     int64  `json:"to_user_id"`      // 会话对端：1:1 为好友ID，群聊为群ID（负数）
		RealToUserID int64  `json:"real_to_user_id"` // 真实收款人ID（1:1 时等于 ToUserID）
		RealToName   string `json:"real_to_name"`    // 真实收款人昵称（兜底，后端会重新解析）
		Amount       int64  `json:"amount"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.Amount <= 0 {
		writeJSON(w, 400, "转账金额必须大于 0", nil)
		return
	}
	if req.RealToUserID <= 0 {
		writeJSON(w, 400, "收款人无效", nil)
		return
	}
	if req.RealToUserID == fromUserID {
		writeJSON(w, 400, "不能给自己转账", nil)
		return
	}
	// 会话对端必须有效（1:1 或群）
	if req.ToUserID == 0 {
		writeJSON(w, 400, "缺少会话目标", nil)
		return
	}

	sender, err := findUserByID(fromUserID)
	if err != nil || sender == nil {
		writeJSON(w, 500, "发送方账号异常", nil)
		return
	}
	receiver, err := findUserByID(req.RealToUserID)
	if err != nil || receiver == nil || receiver.Username == "注销用户" {
		writeJSON(w, 400, "收款人账号不存在", nil)
		return
	}
	// 群聊需校验群存在
	if req.ToUserID < 0 {
		internalGroupID := -(req.ToUserID + 1000)
		grp, _ := getGroupByID(internalGroupID)
		if grp == nil {
			writeJSON(w, 404, "群聊不存在", nil)
			return
		}
	}

	balance := getEffectiveTokenBalance(fromUserID)
	if balance < req.Amount {
		writeJSON(w, 400, "token 余额不足", map[string]interface{}{"balance": balance, "need": req.Amount})
		return
	}

	ensureTransferSchema()

	now := time.Now().Unix()
	// 事务：扣发送方并托管 + 写待领取流水；领取时才入账收款方
	tx, err := db.Begin()
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	res, err := tx.Exec("UPDATE users SET token_balance = token_balance - ? WHERE id = ? AND token_balance >= ?", req.Amount, fromUserID, req.Amount)
	if err != nil {
		tx.Rollback()
		writeJSON(w, 500, "扣款失败", nil)
		return
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		tx.Rollback()
		writeJSON(w, 400, "token 余额不足", map[string]interface{}{"balance": getEffectiveTokenBalance(fromUserID), "need": req.Amount})
		return
	}
	result, err := tx.Exec("INSERT INTO transfers (from_user_id, to_user_id, conv_id, amount, created_at, status, expire_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
		fromUserID, req.RealToUserID, req.ToUserID, req.Amount, now, transferStatusPending, now+transferExpireSeconds)
	if err != nil {
		tx.Rollback()
		writeJSON(w, 500, "转账记录失败", nil)
		return
	}
	transferID, err := result.LastInsertId()
	if err != nil {
		tx.Rollback()
		writeJSON(w, 500, "转账记录失败", nil)
		return
	}
	if err := tx.Commit(); err != nil {
		writeJSON(w, 500, "转账失败", nil)
		return
	}

	fromName := sender.Username
	toName := req.RealToName
	if toName == "" {
		toName = receiver.Username
	}

	// 会话卡片：结构化字段，视角由各端本地现算
	content := buildTransferContent(fromUserID, fromName, req.RealToUserID, toName, req.Amount,
		transferID, now, transferStatusPending, 0)
	msgID, err := saveMessage(fromUserID, req.ToUserID, content, 0, "transfer", "", 0, "", "", 0, 0, 0)
	if err != nil {
		log.Printf("[转账] 卡片消息保存失败: %v", err)
	} else {
		db.Exec("UPDATE transfers SET message_id = ? WHERE id = ?", msgID, transferID)
	}
	// 推送聊天消息给相关会话（1:1 推送给收款人；群聊推送给群内成员）
	if req.ToUserID > 0 {
		PushToUser(req.RealToUserID, "new_message", map[string]interface{}{
			"message_id": msgID, "from_user_id": fromUserID, "from_username": fromName,
			"to_user_id": req.ToUserID, "content": content, "created_at": now,
			"media_type": "transfer", "media_url": "", "flash_duration": 0,
			"reply_to_id": 0, "reply_to_text": "", "reply_to_sender": "",
		})
	} else if req.ToUserID < 0 {
		PushToGroup(req.ToUserID, msgID, fromUserID, fromName, req.ToUserID, content, now, "transfer", "", 0, 0, "", "")
	}

	// 推送转账通知到通知中心（仅真实收款人）。
	// 此刻转账只是「待领取」状态，标题绝不能写「转账收款」（听起来像已经收到钱了），
	// 用「转账提醒」表明这是一条「有人给你转账、待你领取」的通知，避免收款方还没领就显示成已收款。
	noticeDesc := fmt.Sprintf("%s向你转账 %d 个 token，待领取", fromName, req.Amount)
	PushToUser(req.RealToUserID, "system_notice", map[string]interface{}{
		"title":     "转账提醒",
		"desc":      noticeDesc,
		"tag":       "转账",
		"kind":      "transfer",
		"from_name": fromName,
		"to_name":   toName,
		"amount":    req.Amount,
	})
	saveSystemNotice(req.RealToUserID, "转账提醒", noticeDesc, "转账", 0)

	writeJSON(w, 200, "转账成功", map[string]interface{}{
		"balance":     getEffectiveTokenBalance(fromUserID),
		"message_id":  msgID,
		"transfer_id": transferID,
		"created_at":  now,
		"amount":      req.Amount,
		"status":      transferStatusPending,
	})
}

// ==================== 上传聊天媒体 ====================

func handleUploadChatMedia(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	// 限制 100MB
	if r.ContentLength > 100*1024*1024 {
		writeJSON(w, 400, "文件过大，最大100MB", nil)
		return
	}
	// 读取 multipart 文件
	r.Body = http.MaxBytesReader(w, r.Body, 100*1024*1024)
	file, header, err := r.FormFile("file")
	if err != nil {
		writeJSON(w, 400, "无法读取文件: "+err.Error(), nil)
		return
	}
	defer file.Close()

	// 批次5 B3：统一上传扩展名校验（黑名单+白名单），超限返回 400
	if ok, reason := validateUploadExt(header.Filename); !ok {
		writeJSON(w, 400, reason, nil)
		return
	}

	// 读取文件内容到内存（10MB 上限已提前限制，内存安全）
	fileBytes, err := io.ReadAll(file)
	if err != nil {
		writeJSON(w, 500, "读取文件失败", nil)
		return
	}

	// ── 检测真实文件类型（优先级：Content-Type 头 >  magic bytes > 文件名后缀） ──
	// 从 multipart header 中获取客户端声明的 Content-Type（最可靠）
	partContentType := header.Header.Get("Content-Type")
	// 用 magic bytes 检测真实类型（防客户端伪造/未设 Content-Type）
	magicType := http.DetectContentType(fileBytes[:min(len(fileBytes), 512)])

	ext := ".jpg"
	mediaType := "jpg"
	lower := strings.ToLower(header.Filename)
	switch {
	case strings.HasPrefix(partContentType, "video/") || strings.HasPrefix(magicType, "video/"):
		ext = ".mp4"
		mediaType = "mp4"
	case strings.HasPrefix(partContentType, "image/gif") || strings.HasPrefix(magicType, "image/gif"):
		ext = ".gif"
		mediaType = "gif"
	case strings.HasPrefix(partContentType, "image/png") || strings.HasPrefix(magicType, "image/png"):
		ext = ".png"
		mediaType = "png"
	case strings.HasPrefix(partContentType, "image/") || strings.HasPrefix(magicType, "image/"):
		// 其余图片（jpeg/webp/bmp 等）：保持图片类型，扩展名按实际文件名
		if strings.HasSuffix(lower, ".png") {
			ext, mediaType = ".png", "png"
		} else if strings.HasSuffix(lower, ".gif") {
			ext, mediaType = ".gif", "gif"
		} else {
			ext, mediaType = ".jpg", "jpg"
		}
	default:
		// 内容未识别为图片/视频：先按文件名后缀兜底识别常见媒体，避免误判
		switch {
		case strings.HasSuffix(lower, ".png"):
			ext, mediaType = ".png", "png"
		case strings.HasSuffix(lower, ".gif"):
			ext, mediaType = ".gif", "gif"
		case strings.HasSuffix(lower, ".jpg") || strings.HasSuffix(lower, ".jpeg"):
			ext, mediaType = ".jpg", "jpg"
		case strings.HasSuffix(lower, ".mp4"):
			ext, mediaType = ".mp4", "mp4"
		default:
			// 非图片非视频 → 普通文件：media_type="file"，保留真实扩展名（APK→.apk、TXT→.txt…）
			mediaType = "file"
			ext = filepath.Ext(lower)
			if ext == "" || ext == "." {
				ext = ".bin"
			}
		}
	}
	filename := fmt.Sprintf("%d_%d_%s%s", time.Now().Unix(), userID, randomString(8), ext)

	// 获取保存目录
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	saveDir := filepath.Join(baseDir, "data", "chat_media")
	os.MkdirAll(saveDir, 0755)

	savePath := filepath.Join(saveDir, filename)
	if err := os.WriteFile(savePath, fileBytes, 0644); err != nil {
		writeJSON(w, 500, "保存失败", nil)
		return
	}
	// 视频 faststart 重封装（moov 前移，支持边下边播 / 拖动进度条）
	if mediaType == "mp4" {
		optimizeVideoFaststart(savePath)
	}

	// 预生成压缩预览图（最长边 1080、质量 85），让查看者无需等待首次生成。
	// 仅处理静态图片：GIF 重编码会丢失动画，保持原样。
	if mediaType == "jpg" || mediaType == "png" {
		go func() {
			_ = generateChatThumb(
				savePath,
				chatThumbCachePath(savePath, chatThumbLongSide, chatThumbQuality, false),
				chatThumbLongSide,
				chatThumbQuality,
				false,
			)
		}()
	}

	// 解析图片尺寸（标准库 image.DecodeConfig 只读头部、不解码像素，极快）
	// 仅支持 JPEG/PNG/GIF；其余格式（WebP/BMP/视频）返回 0，客户端会兜底嗅探
	var imgWidth, imgHeight int
	if strings.HasPrefix(mediaType, "jpg") || strings.HasPrefix(mediaType, "png") || strings.HasPrefix(mediaType, "gif") {
		if cfg, _, err := image.DecodeConfig(strings.NewReader(string(fileBytes))); err == nil {
			imgWidth, imgHeight = cfg.Width, cfg.Height
		}
	} else if mediaType == "mp4" {
		// 视频：ffprobe 取真实宽高随上传响应/消息下发，客户端首帧即可按正确比例定框（跨设备稳定）
		if vw, vh := probeVideoDimensions(savePath); vw > 0 && vh > 0 {
			imgWidth, imgHeight = vw, vh
		}
	}

	// 构建相对 URL：由客户端根据自身可达的 serverUrl 拼接完整地址。
	// 不能用 r.Host —— 服务器若在反向代理(nginx 等)后，r.Host 可能是
	// localhost:8080 等内网地址，手机端无法访问，导致图片一直加载中转圈/空气泡。
	mediaURL := fmt.Sprintf("/chat-media/%s", filename)

	log.Printf("[聊天媒体] 用户 %d 上传: %s (%d bytes, type=%s, size=%dx%d)", userID, filename, len(fileBytes), mediaType, imgWidth, imgHeight)
	writeJSON(w, 200, "上传成功", map[string]interface{}{
		"url":          mediaURL,
		"media_type":   mediaType,
		"media_width":  imgWidth,
		"media_height": imgHeight,
	})
}

// parseChatMediaOwnerID 从聊天媒体文件名（格式 <ts>_<userID>_<random><ext>）解析出上传者 userID。
// 解析失败（旧命名/非标准格式）返回 0，由调用方回退到会话参与方校验。
func parseChatMediaOwnerID(name string) int64 {
	parts := strings.Split(name, "_")
	if len(parts) < 2 {
		return 0
	}
	id, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil {
		return 0
	}
	return id
}

// POST /api/messages/delete-chat-media — 删除聊天媒体文件（AI 生成图清理等）。
// body: {"name":"<文件名>"}。仅允许纯文件名，防路径穿越；同时删除压缩预览缓存。
func handleDeleteChatMedia(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil {
		writeJSON(w, 400, "请求体格式错误", nil)
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" {
		writeJSON(w, 400, "缺少文件名", nil)
		return
	}
	// 防路径穿越：只允许纯文件名
	if strings.ContainsAny(name, "/\\") || strings.Contains(name, "..") {
		writeJSON(w, 400, "非法文件名", nil)
		return
	}
	execPath, _ := os.Executable()
	baseDir := filepath.Dir(execPath)
	saveDir := filepath.Join(baseDir, "data", "chat_media")
	delPath := filepath.Join(saveDir, name)
	if !strings.HasPrefix(delPath, saveDir+string(os.PathSeparator)) {
		writeJSON(w, 400, "非法文件名", nil)
		return
	}
	// 归属校验（B1 加固）：仅文件所有者（文件名中嵌入的上传者 userID）或
	// 该媒体所在会话的参与方（私聊收发双方 / 群聊成员）可删除，否则 403。
	ownerID := parseChatMediaOwnerID(name)
	canDelete := false
	if ownerID == userID {
		canDelete = true
	} else {
		// 会话参与方校验：media_url 精确匹配该文件名的消息，当前用户须为 from 或 to（私聊），
		// 群聊（to_user_id<0）则须为群成员。
		var fromID, toID int64
		rowErr := db.QueryRow("SELECT from_user_id, to_user_id FROM messages WHERE media_url = ? AND media_url != '' ORDER BY id DESC LIMIT 1", "/chat-media/"+name).Scan(&fromID, &toID)
		if rowErr == nil {
			if toID > 0 {
				// 私聊：收发双方之一即会话参与方
				if fromID == userID || toID == userID {
					canDelete = true
				}
			} else if toID < 0 {
				// 群聊：校验当前用户是否群成员
				gid := -toID - 1000
				members, gErr := getGroupMembers(gid)
				if gErr == nil {
					for _, mid := range members {
						if mid == userID {
							canDelete = true
							break
						}
					}
				}
			}
		}
	}
	if !canDelete {
		writeJSON(w, 403, "无权删除该媒体文件", nil)
		return
	}
	// 同时删除压缩预览缓存（普通图 + 长图裁切版，best-effort）
	_ = os.Remove(chatThumbCachePath(delPath, chatThumbLongSide, chatThumbQuality, false))
	_ = os.Remove(chatThumbCachePath(delPath, chatThumbLongSide, chatThumbQuality, true))
	err := os.Remove(delPath)
	if err != nil {
		if os.IsNotExist(err) {
			writeJSON(w, 200, "文件不存在", map[string]interface{}{"deleted": false})
			return
		}
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	log.Printf("[聊天媒体] 删除文件: %s", name)
	writeJSON(w, 200, "删除成功", map[string]interface{}{"deleted": true})
}

// deleteChatMediaByURL 依据消息里的媒体 URL（形如 /chat-media/<文件名>）删除服务端物理文件与预览缓存。
// 仅接受 /chat-media/ 前缀，防路径穿越；best-effort，任何失败都不影响撤回本身。
// 返回 true 表示该 URL 对应的服务器文件已被清理（含「本就不存在」的情况），
// 返回 false 表示不是服务器本地文件（外链 / sticker://）或路径非法，未做任何删除。
func deleteChatMediaByURL(mediaURL string) bool {
	const prefix = "/chat-media/"
	idx := strings.Index(mediaURL, prefix)
	if idx < 0 {
		return false
	}
	name := mediaURL[idx+len(prefix):]
	if q := strings.IndexByte(name, '?'); q >= 0 {
		name = name[:q]
	}
	name = strings.TrimSpace(name)
	// 防路径穿越：只允许纯文件名
	if name == "" || strings.ContainsAny(name, "/\\") || strings.Contains(name, "..") {
		return false
	}
	execPath, _ := os.Executable()
	saveDir := filepath.Join(filepath.Dir(execPath), "data", "chat_media")
	delPath := filepath.Join(saveDir, name)
	if !strings.HasPrefix(delPath, saveDir+string(os.PathSeparator)) {
		return false
	}
	// 同时删除压缩预览缓存（普通图 + 长图裁切版，best-effort）
	_ = os.Remove(chatThumbCachePath(delPath, chatThumbLongSide, chatThumbQuality, false))
	_ = os.Remove(chatThumbCachePath(delPath, chatThumbLongSide, chatThumbQuality, true))
	if err := os.Remove(delPath); err == nil {
		log.Printf("[聊天媒体] 已删除文件: %s", name)
		return true
	} else if os.IsNotExist(err) {
		// 文件已不存在（此前清理过 / 上传失败），视为已清理，不重复告警
		return true
	}
	return false
}

// POST /api/messages/poke — 拍了拍
func handlePoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	fromUserID := getCurrentUserID(r)
	if fromUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	var req struct {
		ToUserID     int64 `json:"to_user_id"`
		TargetUserID int64 `json:"target_user_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.ToUserID == 0 {
		writeJSON(w, 400, "缺少目标用户ID", nil)
		return
	}
	// 黑名单拦截（私聊拍一拍同样禁止）
	if req.ToUserID > 0 {
		if blocked, _ := isBlockedAnyDir(fromUserID, req.ToUserID); blocked {
			var iAmBlocker int
			db.QueryRow("SELECT COUNT(*) FROM user_blacklist WHERE blocker_id = ? AND blocked_id = ?", fromUserID, req.ToUserID).Scan(&iAmBlocker)
			if iAmBlocker > 0 {
				writeJSON(w, 403, "你已将对方拉入黑名单", nil)
			} else {
				writeJSON(w, 403, "对方已将你拉入黑名单", nil)
			}
			return
		}
	}
	isGroup := req.ToUserID < 0
	// 被拍成员：群聊由前端经 target_user_id 传入（被拍的群成员），否则等于 to_user_id
	pokedUserID := req.TargetUserID
	if pokedUserID == 0 {
		pokedUserID = req.ToUserID
	}

	// 保存系统通知消息（flashDuration=-1 作为拍拍标记）。
	// 群聊时 to_user_id 用群会话 ID（负数），使拍一拍作为「群消息」存储，隔离在群内、群成员可见，
	// 不会泄漏到与成员的私聊会话；私聊则仍按 (我, 成员) 存储。
	msgID, err := saveMessage(fromUserID, req.ToUserID, "POKE", 0, "", "", -1, "", "", 0, 0, 0)
	if err != nil {
		log.Printf("[拍拍] 保存失败: %v", err)
		writeJSON(w, 500, "发送失败", nil)
		return
	}

	// 获取发送者 / 被拍者名称
	sender, _ := findUserByID(fromUserID)
	senderName := ""
	if sender != nil {
		senderName = sender.Username
	}
	receiverName := ""
	if r2, _ := findUserByID(pokedUserID); r2 != nil {
		receiverName = r2.Username
	}
	// 群聊：把被拍成员名落库（to_user_id 为群 ID，JOIN users 取不到成员名，必须落库）
	if receiverName != "" {
		if _, e := db.Exec("UPDATE messages SET to_user_name = ? WHERE id = ?", receiverName, msgID); e != nil {
			log.Printf("[拍拍] 记录被拍成员名失败(可忽略): %v", e)
		}
	}

	// 推送给接收方：私聊推给被拍成员个人；群聊推给群内所有在线成员（排除发送者自己）
	if isGroup {
		PushToGroup(req.ToUserID, msgID, fromUserID, senderName, req.ToUserID, "POKE",
			time.Now().Unix(), "", "", -1, 0, "", "")
	} else if req.ToUserID > 0 {
		PushToUser(req.ToUserID, "new_message", map[string]interface{}{
			"message_id":     msgID,
			"from_user_id":   fromUserID,
			"to_user_id":     req.ToUserID,
			"content":        "POKE",
			"sender_name":    senderName,
			"target_name":    receiverName,
			"created_at":     time.Now().Unix(),
			"flash_duration": -1,
		})
	}
	// 推送给发送者自己（让他看到"你拍了拍XX"）
	PushToUser(fromUserID, "new_message", map[string]interface{}{
		"message_id":     msgID,
		"from_user_id":   fromUserID,
		"to_user_id":     req.ToUserID,
		"content":        "POKE",
		"sender_name":    senderName,
		"target_name":    receiverName,
		"created_at":     time.Now().Unix(),
		"flash_duration": -1,
	})

	log.Printf("[拍拍] %s 拍了 %s", senderName, receiverName)
	writeJSON(w, 200, "发送成功", map[string]int64{"message_id": msgID})
}

func handleRecallMessage(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		MessageID int64 `json:"message_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	msg, err := getMessageByID(req.MessageID)
	if err != nil || msg == nil {
		writeJSON(w, 404, "消息不存在", nil)
		return
	}
	// 判断是否为开发者（开发者可撤回任何人的消息，无时间限制）
	isDeveloper := isDeveloperUser(userID)

	if msg.FromUserID != userID && !isDeveloper {
		writeJSON(w, 403, "只能撤回自己的消息", nil)
		return
	}
	if time.Now().Unix()-msg.CreatedAt > 120 && !isDeveloper {
		writeJSON(w, 400, "消息已超过2分钟，无法撤回", nil)
		return
	}
	if msg.IsRevoked == 1 {
		writeJSON(w, 400, "消息已被撤回", nil)
		return
	}
	if isDeveloper && msg.FromUserID != userID {
		if err := recallMessageAsAdmin(req.MessageID); err != nil {
			writeJSON(w, 500, "撤回失败", nil)
			return
		}
	} else {
		if err := recallMessage(req.MessageID, userID); err != nil {
			writeJSON(w, 500, "撤回失败", nil)
			return
		}
	}
	// 红包消息：撤回时把「未被领取」的余额退回发送者（幂等，已领完则无可退）
	if msg.MediaType == "redpacket" || strings.HasPrefix(strings.TrimSpace(msg.Content), redPacketMsgPrefix) {
		refundRedPacketOnRecall(msg.Content)
	}
	// 群公告卡片消息：撤回后联动从群公告列表移除对应公告
	if msg.MsgType == "group_announcement" && msg.ToUserID < 0 {
		groupID := -msg.ToUserID - 1000
		text, _ := parseGroupAnnouncementCard(msg.Content)
		if groupID > 0 && text != "" {
			if err := removeGroupAnnouncement(groupID, text); err != nil {
				log.Printf("撤回群公告联动移除失败: groupID=%d err=%v", groupID, err)
			}
		}
	}
	// TCP 推送撤回通知
	// 开发者撤回他人消息时，senderName 应为原消息发送者，而非开发者
	var senderName string
	if isDeveloper && msg.FromUserID != userID {
		originalSender, _ := findUserByID(msg.FromUserID)
		if originalSender != nil {
			senderName = originalSender.Username
		}
	} else {
		sender, _ := findUserByID(userID)
		if sender != nil {
			senderName = sender.Username
		}
	}
	if msg.ToUserID > 0 {
		// 私聊：推送给对方
		otherID := msg.FromUserID
		if otherID == userID {
			otherID = msg.ToUserID
		}
		PushToUser(otherID, "message_recalled", map[string]interface{}{
			"message_id":  req.MessageID,
			"sender_name": senderName,
		})
	} else if msg.ToUserID < 0 {
		// 群聊：推送给所有在线群成员（排除撤回者）
		PushRecallToGroup(msg.ToUserID, req.MessageID, userID, senderName)
	}
	// 撤回后真正删除服务端媒体文件：否则撤回的图片/视频仍能通过原 URL 拉取（客户端缓存命中即"撤回还能看"）
	if msg.MediaURL != "" {
		deleteChatMediaByURL(msg.MediaURL)
	}
	writeJSON(w, 200, "撤回成功", nil)
}

// handleDevDeleteConversation 开发者测试接口：删除指定会话在服务端的所有消息，
// 并推送 conversation_deleted 事件，用于验证「服务器可删除本地数据」链路。
// 仅影响 (from_user_id,to_user_id) 双向匹配的消息；非破坏性演示，可随时重新发送。
func handleDevDeleteConversation(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FriendID == 0 {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	// 权限校验（B2 加固）：仅开发者/平台管理员，或会话所有者本人（当前用户与 friend_id
	// 之间存在会话消息）可删除；普通用户对不存在会话的任意 friend_id 调用一律 403。
	isPrivileged := checkDevPermission(r)
	if !isPrivileged {
		isPrivileged, _ = isPlatformAdmin(userID)
	}
	if !isPrivileged {
		var cnt int
		if err := db.QueryRow("SELECT COUNT(*) FROM messages WHERE (from_user_id=? AND to_user_id=?) OR (from_user_id=? AND to_user_id=?)",
			userID, req.FriendID, req.FriendID, userID).Scan(&cnt); err == nil {
			if cnt > 0 {
				// 会话确实有消息 → 当前用户必然是该会话的参与方，放行
				isPrivileged = true
			} else {
				// 会话在服务端本来就是空的（从没发过消息 / 已被清过）→ 语义上等于"已经清空"，
				// 直接按成功返回，绝不能再报"无权删除该会话"：
				// 否则用户点「云端清理」时，只要这个会话恰好没消息就必然 403，看起来像功能坏了。
				// 这里不推送 conversation_deleted：服务端没有任何记录可清，
				// 也不能让调用方凭这个接口去影响第三方客户端的本地数据。
				writeJSON(w, 200, "已清空", map[string]int{"count": 0})
				return
			}
		}
	}
	if !isPrivileged {
		writeJSON(w, 403, "无权删除该会话", nil)
		return
	}
	res, err := db.Exec("DELETE FROM messages WHERE (from_user_id=? AND to_user_id=?) OR (from_user_id=? AND to_user_id=?)", userID, req.FriendID, req.FriendID, userID)
	if err != nil {
		writeJSON(w, 500, "删除失败", nil)
		return
	}
	n := 0
	if c, e := res.RowsAffected(); e == nil {
		n = int(c)
	}
	PushToUser(userID, "conversation_deleted", map[string]interface{}{"friend_id": req.FriendID})
	if req.FriendID != userID {
		PushToUser(req.FriendID, "conversation_deleted", map[string]interface{}{"friend_id": req.FriendID})
	}
	writeJSON(w, 200, "已删除会话消息", map[string]int{"count": n})
}

// ==================== 申请清空聊天记录（双向同意） ====================

// POST /api/messages/clear-chat-request — 申请清空与某好友的聊天记录：
// 向对方发送一张请求卡片，对方同意后由后端双向永久删除会话数据。
func handleClearChatRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST", nil)
		return
	}
	userID := getCurrentUserID(r)
	var req struct {
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FriendID == 0 {
		writeJSON(w, 400, "参数错误", nil)
		return
	}
	if req.FriendID == userID {
		writeJSON(w, 400, "不能对自己发起", nil)
		return
	}
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	cardMsg := fmt.Sprintf("清空聊天记录请求\n━━━━━━━━━━\n%s 申请清空与你的聊天记录\n对方同意后，本会话数据将从服务器永久删除\n━━━━━━━━━━\nrequester_id=%d", username, userID)
	res, err := db.Exec(`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at, server_received_ms) VALUES (?, ?, ?, 'clear_chat_request', ?, ?)`,
		userID, req.FriendID, cardMsg, time.Now().Unix(), time.Now().UnixMilli())
	if err != nil {
		log.Printf("[清空聊天] 发送请求失败: %v", err)
		writeJSON(w, 500, "发送失败", nil)
		return
	}
	msgID, _ := res.LastInsertId()
	// 实时推送给对方，确保对方立即看到请求卡片
	PushToUser(req.FriendID, "new_message", map[string]interface{}{
		"message_id":    msgID,
		"from_user_id":  userID,
		"from_username": username,
		"to_user_id":    req.FriendID,
		"content":       cardMsg,
		"created_at":    time.Now().Unix(),
	})
	// 落库回执给发起方自己：这张卡片客户端没有乐观气泡，
	// 没有回执就只能等 15 秒兜底轮询或切出重进才看得到自己发出去的卡片。
	PushMessagePersistedReceipt(userID, req.FriendID, msgID)
	writeJSON(w, 200, "请求已发送", nil)
}
