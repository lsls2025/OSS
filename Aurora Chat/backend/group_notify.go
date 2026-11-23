package main

// 群通知方式设置（按群维度保存，所有成员共用同一套）
//
// 背景：
//   群内的各种提示（入群/退群/被移出/禁言/解除禁言/群主转让/消息撤回……）
//   目前是散落在各处「各自拼文案、各自入库」的，没有任何统一出口，
//   所以既不能按群切换通知方式，也不能逐项关闭。
//
// 这里收敛成唯一出口 sendGroupNotice：
//   - 两种互斥方式：群身份（带 @、可点击、气泡来自群）/ 系统广播（灰色居中、无 @）
//   - 逐项开关：关闭的项彻底不发（不是仅前端隐藏）
//
// 存储：group_notify_settings(group_id, mode, disabled_keys)
//   mode: "group" | "broadcast"（默认 group，与改造前的表现保持一致）
//   disabled_keys: 逗号分隔的 key 列表

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

const (
	notifyModeGroup     = "group"
	notifyModeBroadcast = "broadcast"
)

// 通知项 key（前后端共用，改动需同步 GroupNotices.kt）
const (
	notifyKeyMemberJoin    = "member_join"    // 新成员入群
	notifyKeyMemberLeave   = "member_leave"   // 成员主动退群
	notifyKeyMemberKick    = "member_kick"    // 成员被移出群聊
	notifyKeyOwnerTransfer = "owner_transfer" // 群主转让
	notifyKeyMute          = "mute"           // 成员被禁言
	notifyKeyUnmute        = "unmute"         // 成员被解除禁言
	notifyKeyAdminChange   = "admin_change"   // 管理员变更（设置 / 取消）
	notifyKeyRecall        = "recall"         // 消息撤回（客户端合成，服务端只存设置）
)

// GroupNotifySettings 群通知方式与逐项开关
type GroupNotifySettings struct {
	Mode     string   `json:"mode"`
	Disabled []string `json:"disabled"`
}

// ensureGroupNotifyTable 建表（幂等）
func ensureGroupNotifyTable() {
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS group_notify_settings (
		group_id INTEGER PRIMARY KEY,
		mode TEXT NOT NULL DEFAULT 'group',
		disabled_keys TEXT NOT NULL DEFAULT '',
		updated_at INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  提示: group_notify_settings 表创建失败: %v", err)
	} else {
		log.Println("  [群通知] group_notify_settings 表已确认存在")
	}
}

// getGroupNotifySettings 读取群通知设置（无记录时返回默认：群身份 + 全开）
func getGroupNotifySettings(groupID int64) GroupNotifySettings {
	s := GroupNotifySettings{Mode: notifyModeGroup, Disabled: []string{}}
	if groupID <= 0 {
		return s
	}
	var mode, disabled string
	if err := db.QueryRow(
		"SELECT mode, disabled_keys FROM group_notify_settings WHERE group_id = ?", groupID,
	).Scan(&mode, &disabled); err != nil {
		return s
	}
	if mode == notifyModeBroadcast {
		s.Mode = notifyModeBroadcast
	}
	for _, k := range strings.Split(disabled, ",") {
		if k = strings.TrimSpace(k); k != "" {
			s.Disabled = append(s.Disabled, k)
		}
	}
	return s
}

// saveGroupNotifySettings 保存群通知设置
func saveGroupNotifySettings(groupID int64, mode string, disabled []string) error {
	if mode != notifyModeBroadcast {
		mode = notifyModeGroup
	}
	cleaned := make([]string, 0, len(disabled))
	for _, k := range disabled {
		if k = strings.TrimSpace(k); k != "" {
			cleaned = append(cleaned, k)
		}
	}
	_, err := db.Exec(
		`INSERT INTO group_notify_settings (group_id, mode, disabled_keys, updated_at)
		 VALUES (?, ?, ?, ?)
		 ON CONFLICT(group_id) DO UPDATE SET mode = excluded.mode,
		   disabled_keys = excluded.disabled_keys, updated_at = excluded.updated_at`,
		groupID, mode, strings.Join(cleaned, ","), time.Now().Unix(),
	)
	return err
}

// groupNoticeEnabled 某一项提示是否开启
func groupNoticeEnabled(groupID int64, key string) bool {
	for _, k := range getGroupNotifySettings(groupID).Disabled {
		if k == key {
			return false
		}
	}
	return true
}

// sendGroupNotice 群提示的唯一出口。
//
// identityText  = 以群身份发出的文案（可含 @成员，客户端会渲染成可点击的提及）
// broadcastText = 系统广播文案（不带 @，灰色居中）
//
// 行为：
//   - 该群关闭了这一项 → 直接不发（数据库里不会留下任何记录）
//   - 广播方式 → 写 msg_type='system' 的居中消息
//   - 群身份方式 → 以群（convID）为发送者写普通消息，保留 @ 以便点击跳转
//
// 返回值：落库消息 id；0 = 未写入（功能被关 / 文案为空 / 写库失败）。
// 群敏感词的踢人链路需要这个 id：被踢者之后永远拉不到群消息（接口对非成员 403），
// 他的那份副本要由客户端按同样的 id 本地落库 —— 重进群后与服务端历史按 id 去重，不出现两份。
func sendGroupNotice(groupID int64, key, identityText, broadcastText string) int64 {
	if groupID <= 0 {
		return 0
	}
	settings := getGroupNotifySettings(groupID)
	for _, k := range settings.Disabled {
		if k == key {
			return 0
		}
	}
	convID := -(1000 + groupID)
	now := time.Now().Unix()
	if settings.Mode == notifyModeBroadcast {
		text := strings.ReplaceAll(broadcastText, "@", "")
		text = strings.TrimSpace(text)
		if text == "" {
			return 0
		}
		res, err := db.Exec(
			`INSERT INTO messages (from_user_id, to_user_id, content, msg_type, created_at, server_received_ms)
			 VALUES (?, ?, ?, 'system', ?, ?)`,
			convID, convID, encryptContentStore(text), now, time.Now().UnixMilli(),
		)
		if err != nil {
			log.Printf("[群通知] 广播写入失败 group=%d key=%s: %v", groupID, key, err)
			return 0
		}
		msgID, _ := res.LastInsertId()
		PushToGroup(convID, msgID, convID, "", convID, text, now, "", "", 0, 0, "", "")
		return msgID
	}
	content := strings.TrimSpace(identityText)
	if content == "" {
		return 0
	}
	msgID, err := saveMessage(convID, convID, content, 0, "", "", 0, "", "", 0, 0, 0)
	if err != nil {
		log.Printf("[群通知] 群身份写入失败 group=%d key=%s: %v", groupID, key, err)
		return 0
	}
	PushToGroup(convID, msgID, convID, "", convID, content, now, "", "", 0, 0, "", "")
	return msgID
}

// ==================== 常用文案组装 ====================

// groupMemberDisplayName 取用户昵称，空则回退「用户<ID>」
func groupMemberDisplayName(userID int64) string {
	var name string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&name)
	name = strings.TrimSpace(name)
	if name == "" {
		return "用户" + itoa(userID)
	}
	return name
}

// itoa 小工具：避免为了一个数字再引 strconv 到文案层
func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

// formatMuteDuration 把秒数格式化成「X天 / X小时 / X分钟」
func formatMuteDuration(seconds int64) string {
	if seconds <= 0 {
		seconds = 3600
	}
	switch {
	case seconds >= 86400:
		return itoa(seconds/86400) + "天"
	case seconds >= 3600:
		return itoa(seconds/3600) + "小时"
	default:
		return itoa(seconds/60) + "分钟"
	}
}

// notifyMemberJoin 成员入群（inviterID > 0 时带邀请人）
func notifyMemberJoin(groupID, userID, inviterID int64) {
	name := groupMemberDisplayName(userID)
	if inviterID > 0 && inviterID != userID {
		inviter := groupMemberDisplayName(inviterID)
		sendGroupNotice(groupID, notifyKeyMemberJoin,
			"@"+name+" 通过 @"+inviter+" 的邀请加入了群聊",
			name+" 通过 "+inviter+" 的邀请加入了群聊")
		return
	}
	sendGroupNotice(groupID, notifyKeyMemberJoin,
		"@"+name+" 加入了群聊",
		name+" 加入了群聊")
}

// notifyMemberLeave 成员主动退群
func notifyMemberLeave(groupID, userID int64) {
	name := groupMemberDisplayName(userID)
	sendGroupNotice(groupID, notifyKeyMemberLeave,
		"@"+name+" 退出了群聊",
		name+" 退出了群聊")
}

// notifyMemberKick 成员被移出群聊
func notifyMemberKick(groupID, operatorID, userID int64) {
	name := groupMemberDisplayName(userID)
	op := groupMemberDisplayName(operatorID)
	sendGroupNotice(groupID, notifyKeyMemberKick,
		"@"+op+" 将 @"+name+" 移出了群聊",
		op+" 将 "+name+" 移出了群聊")
}

// notifyOwnerTransfer 群主转让
func notifyOwnerTransfer(groupID, newOwnerID int64) {
	name := groupMemberDisplayName(newOwnerID)
	sendGroupNotice(groupID, notifyKeyOwnerTransfer,
		"@"+name+" 成为了新群主",
		name+" 成为了新群主")
}

// notifyAdminChange 管理员变更（设为管理员 / 取消管理员）
func notifyAdminChange(groupID, operatorID, userID int64, granted bool) {
	op := groupMemberDisplayName(operatorID)
	name := groupMemberDisplayName(userID)
	if granted {
		sendGroupNotice(groupID, notifyKeyAdminChange,
			"@"+op+" 将 @"+name+" 设为管理员",
			op+" 将 "+name+" 设为管理员")
	} else {
		sendGroupNotice(groupID, notifyKeyAdminChange,
			"@"+op+" 取消了 @"+name+" 的管理员",
			op+" 取消了 "+name+" 的管理员")
	}
}

// ==================== HTTP 接口 ====================

// /api/groups/notify-settings
//
//	GET  ?group_id=123    读取（群成员均可读）
//	POST {group_id, mode, disabled:[...]} 保存（群成员均可改，与「群通知」入口的定位一致）
func handleGroupNotifySettings(w http.ResponseWriter, r *http.Request) {
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
		s := getGroupNotifySettings(groupID)
		writeJSON(w, 200, "获取成功", s)
	case "POST":
		var req struct {
			GroupID  int64    `json:"group_id"`
			Mode     string   `json:"mode"`
			Disabled []string `json:"disabled"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID <= 0 {
			writeJSON(w, 400, "参数不完整", nil)
			return
		}
		if !isGroupMember(req.GroupID, currentUserID) && !isDeveloperUser(currentUserID) {
			writeJSON(w, 403, "你不在该群中", nil)
			return
		}
		// 群通知方式属于管理动作：仅群主 / 管理员，且管理员需被授予「群通知方式」权限
		if !hasGroupPerm(req.GroupID, currentUserID, "group_notify") {
			writeJSON(w, 403, "仅群主或管理员可设置群通知方式", nil)
			return
		}
		if err := saveGroupNotifySettings(req.GroupID, req.Mode, req.Disabled); err != nil {
			log.Printf("[群通知] 保存失败 group=%d: %v", req.GroupID, err)
			writeJSON(w, 500, "保存失败", nil)
			return
		}
		writeJSON(w, 200, "已保存", getGroupNotifySettings(req.GroupID))
	default:
		writeJSON(w, 405, "仅支持 GET/POST", nil)
	}
}
