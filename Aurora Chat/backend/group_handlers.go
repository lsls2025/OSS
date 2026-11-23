package main

import (
	"encoding/json"
	"html"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// 每个非开发者每 24 小时最多创建的群聊数；超过则自动封禁 1 天（开发者不受影响）。
const maxGroupsPerDay = 20

// 群名称长度上限（按字符数，不是字节数）。
//
// 为什么要封顶：群名会出现在聊天列表、群详情大标题、群设置、二维码等位置，
// 不限制时有人填上千字，群详情页整屏都是群名（而且把下面的群签名/成员挤走）。
// 客户端 CreateGroupScreen 的 MAX_GROUP_NAME_LEN 必须与此值一致。
const maxGroupNameRunes = 20

// 群名称留空时使用的默认名（客户端也会自动补，这里再兜一层，避免直接调接口时建出无名群）。
const defaultGroupName = "群聊"

// normalizeGroupName 去掉首尾空白；留空则回落到默认名；超长直接判为非法。
// 返回 (规范化后的名称, 是否合法)。
func normalizeGroupName(raw string) (string, bool) {
	name := strings.TrimSpace(raw)
	if name == "" {
		name = defaultGroupName
	}
	if utf8.RuneCountInString(name) > maxGroupNameRunes {
		return "", false
	}
	return name, true
}

// 群公告卡片消息的正文前缀：content = 前缀 + 公告文本。
// 前端据此把该消息渲染成「群公告」大卡片；撤回时后端据此联动从群公告列表移除对应公告。
const groupAnnouncementMsgPrefix = "系统公告数据"

// parseGroupAnnouncementCard 从公告卡片 content 解析正文与「需确认收到」标记。
// 兼容旧纯文本协议与带图片/需确认的 JSON 协议：前缀 + {"text":...,"need_confirm":true,...}
func parseGroupAnnouncementCard(content string) (text string, needConfirm bool) {
	raw := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(content), groupAnnouncementMsgPrefix))
	if strings.HasPrefix(raw, "{") && strings.HasSuffix(raw, "}") {
		var payload struct {
			Text        string `json:"text"`
			NeedConfirm bool   `json:"need_confirm"`
		}
		if err := json.Unmarshal([]byte(raw), &payload); err == nil {
			return strings.TrimSpace(payload.Text), payload.NeedConfirm
		}
	}
	return raw, false
}


// isOfficialGroup 判断是否为官方群（groups.display_id = 1，内部群 id=1，会话 id=-1001）。
// 官方群是全员强制加入的公告频道，锁定策略：
//   - 成员不可主动退出，也不可被群主/管理员移除（handleRemoveGroupMember）
//   - 群本身不可解散、不可转让群主（handleDissolveGroup / handleTransferOwner）
//
// 这三处拦截是配套的：只挡其中一处，用户仍可借"解散/转让/被踢"绕开锁定。
func isOfficialGroup(groupID int64) bool {
	if groupID <= 0 {
		return false
	}
	var d int64
	if err := db.QueryRow("SELECT COALESCE(display_id, 0) FROM groups WHERE id = ?", groupID).Scan(&d); err != nil {
		return false
	}
	return d == 1
}

// POST /api/groups/create — 创建群聊
func handleCreateGroup(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	// JWT 认证后的用户 ID（由 authMiddleware 注入）
	creatorID := getCurrentUserID(r)
	if creatorID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}

	// 创建群聊频率限制：非开发者每人每 24 小时最多创建 maxGroupsPerDay 个群，超限直接封号 1 天。
	// 开发者不受此限制。
	if !isDeveloperUser(creatorID) {
		now := time.Now().Unix()
		var created int
		if err := db.QueryRow("SELECT COUNT(*) FROM groups WHERE creator_id = ? AND created_at >= ?", creatorID, now-86400).Scan(&created); err == nil && created >= maxGroupsPerDay {
			const banSeconds = int64(24 * 60 * 60)
			reason := "刷群：24 小时内创建群聊超过 " + strconv.Itoa(maxGroupsPerDay) + " 个，自动封禁 1 天"
			if err := banUser(creatorID, reason, banSeconds, 0, reason); err == nil {
				// 同步写入内存封禁，立即生效（无需等重启/定时刷新）
				AddBanRecord(creatorID, reason, now+banSeconds)
			}
			log.Printf("[限频] 用户 %d 24h 内创建群超过 %d 个，已封禁 1 天", creatorID, maxGroupsPerDay)
			writeJSON(w, 403, "创建群聊过于频繁，账号已被封禁 1 天", nil)
			return
		}
	}

	var req struct {
		Name           string `json:"name"`
		Signature      string `json:"signature"`
		Announcement   string `json:"announcement"`
		WelcomeEnabled bool   `json:"welcome_enabled"`
		WelcomeText    string `json:"welcome_text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	name, ok := normalizeGroupName(req.Name)
	if !ok {
		writeJSON(w, 400, "群聊名称最多 "+strconv.Itoa(maxGroupNameRunes)+" 个字", nil)
		return
	}
	req.Name = html.EscapeString(name)
	req.Signature = html.EscapeString(req.Signature)
	req.Announcement = html.EscapeString(req.Announcement)
	req.WelcomeText = html.EscapeString(req.WelcomeText)

	groupID, displayID, err := createGroup(req.Name, req.Signature, req.Announcement, creatorID, req.WelcomeEnabled, req.WelcomeText)
	if err != nil {
		log.Printf("创建群聊失败: %v", err)
		writeJSON(w, 500, "创建失败", nil)
		return
	}

	// 如果开启了欢迎语，自动插入欢迎消息（创建者身份）
	if req.WelcomeEnabled && req.WelcomeText != "" {
		groupConvID := -(1000 + groupID)
		// 查找创建者昵称并前置 @用户名
		var creatorName string
		db.QueryRow("SELECT username FROM users WHERE id = ?", creatorID).Scan(&creatorName)
		if creatorName != "" {
			insertWelcomeMessage(groupConvID, "@"+creatorName+" "+req.WelcomeText)
		} else {
			insertWelcomeMessage(groupConvID, req.WelcomeText)
		}
	}

	log.Printf("群聊创建成功: ID=%d, DisplayID=%d, Name=%s, Creator=%d, IP=%s", groupID, displayID, req.Name, creatorID, getClientIP(r))
	writeJSON(w, 200, "创建成功", map[string]interface{}{
		"id":           groupID,
		"display_id":   displayID,
		"name":         req.Name,
		"signature":    req.Signature,
		"announcement": req.Announcement,
		"creator_id":   creatorID,
	})
}

// POST /api/groups/settings — 更新群聊设置
func handleUpdateGroupSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		GroupID        int64             `json:"group_id"`
		Name           string            `json:"name"`
		Signature      string            `json:"signature"`
		Announcement   string            `json:"announcement"`
		Announcements  []json.RawMessage `json:"announcements"`
		WelcomeEnabled bool              `json:"welcome_enabled"`
		WelcomeText    string            `json:"welcome_text"`
		NotSearchable  bool              `json:"not_searchable"`
		JoinRequired   bool              `json:"join_required"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	if req.GroupID == 0 {
		writeJSON(w, 400, "缺少群ID", nil)
		return
	}

	// 权限检查：仅群主和管理员可以修改群设置（开发者可操作任意群）
	currentUserID := getCurrentUserID(r)
	role, err := getMemberRole(req.GroupID, currentUserID)
	if !isDeveloperUser(currentUserID) && (err != nil || (role != "owner" && role != "admin")) {
		writeJSON(w, 403, "无权限操作", nil)
		return
	}

	name, ok := normalizeGroupName(req.Name)
	if !ok {
		writeJSON(w, 400, "群聊名称最多 "+strconv.Itoa(maxGroupNameRunes)+" 个字", nil)
		return
	}
	req.Name = html.EscapeString(name)
	req.Signature = html.EscapeString(req.Signature)
	req.WelcomeText = html.EscapeString(req.WelcomeText)

	// 多公告：优先使用 announcements 数组（序列化为 JSON 存储，兼容旧单条字符串）；
	// 未提供数组时回退到原 announcement 字段逻辑
	announcementValue := req.Announcement
	if req.Announcements != nil {
		parsed := make([]GroupAnnouncement, 0, len(req.Announcements))
		for _, raw := range req.Announcements {
			// 旧格式：纯字符串
			var s string
			if err := json.Unmarshal(raw, &s); err == nil {
				t := html.EscapeString(strings.TrimSpace(s))
				if t != "" {
					parsed = append(parsed, GroupAnnouncement{Text: t})
				}
				continue
			}
			// 新格式：对象（text/images/popup_enabled/need_confirm）
			var obj GroupAnnouncement
			if err := json.Unmarshal(raw, &obj); err == nil {
				obj.Text = html.EscapeString(strings.TrimSpace(obj.Text))
				if obj.Text != "" {
					if len(obj.Images) > 5 {
						obj.Images = obj.Images[:5]
					}
					for i := range obj.Images {
						obj.Images[i] = html.EscapeString(strings.TrimSpace(obj.Images[i]))
					}
					parsed = append(parsed, obj)
				}
			}
		}
		announcementValue = marshalGroupAnnouncements(parsed)
	} else {
		announcementValue = html.EscapeString(announcementValue)
	}

	// 细粒度权限：按「实际发生变化的字段」逐项校验。
	// 群主天然通过；管理员只有在群主给他开了对应权限时才能改（例如能改群签名但不能改群名称）。
	if !isDeveloperUser(currentUserID) {
		need := requiredGroupSettingPerms(req.GroupID, req.Name, req.Signature, announcementValue, req.WelcomeEnabled, req.WelcomeText)
		if msg, ok := checkGroupSettingPerms(req.GroupID, currentUserID, need); !ok {
			writeJSON(w, 403, msg, nil)
			return
		}
	}

	if err := updateGroupSettings(req.GroupID, req.Name, req.Signature, announcementValue, req.WelcomeEnabled, req.WelcomeText, req.NotSearchable, req.JoinRequired); err != nil {
		log.Printf("更新群设置失败: %v", err)
		writeJSON(w, 500, "更新失败", nil)
		return
	}

	log.Printf("群设置已更新: ID=%d", req.GroupID)
	writeJSON(w, 200, "更新成功", nil)
}

// POST /api/groups/announcement/post — 发布群公告：往群聊会话内插入一条「群公告」卡片消息。
// content = 前缀 + 公告文本（msg_type='group_announcement'）；撤回该消息时后端会联动从群公告列表移除。
// 注意：本接口只负责「发卡片」，公告列表的增删仍由 updateGroupSettings(announcements=...) 全量管理，避免重复追加。
func handlePostGroupAnnouncement(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		GroupID     int64    `json:"group_id"`
		Text        string   `json:"text"`
		NeedConfirm bool     `json:"need_confirm"`
		Images      []string `json:"images"` // 最多5张，随卡片发布到群聊
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.GroupID <= 0 {
		writeJSON(w, 400, "缺少群ID", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	// 权限：仅群成员可发布公告（开发者可操作任意群）
	if !isDeveloperUser(userID) {
		role, err := getMemberRole(req.GroupID, userID)
		if err != nil || role == "" {
			writeJSON(w, 403, "仅群成员可发布公告", nil)
			return
		}
		// 细粒度权限：管理员需被授予「群公告」
		if !hasGroupPerm(req.GroupID, userID, "group_announcement") {
			writeJSON(w, 403, "没有管理群公告的权限", nil)
			return
		}
	}

	req.Text = html.EscapeString(strings.TrimSpace(req.Text))
	if req.Text == "" {
		writeJSON(w, 400, "公告内容不能为空", nil)
		return
	}
	// 图片列表清洗：最多5张、逐条 trim、跳过空串
	reqImages := make([]string, 0, len(req.Images))
	for _, u := range req.Images {
		u = strings.TrimSpace(u)
		if u == "" {
			continue
		}
		reqImages = append(reqImages, u)
		if len(reqImages) >= 5 {
			break
		}
	}

	convID := -(1000 + req.GroupID)
	// 卡片协议：纯文本兼容旧数据；带图片或开启「需确认收到」时升级为 前缀 + JSON{text,images,need_confirm}
	content := groupAnnouncementMsgPrefix + req.Text
	if req.NeedConfirm || len(reqImages) > 0 {
		payload := map[string]interface{}{"text": req.Text, "need_confirm": req.NeedConfirm}
		if len(reqImages) > 0 {
			payload["images"] = reqImages
		}
		annBody, _ := json.Marshal(payload)
		content = groupAnnouncementMsgPrefix + string(annBody)
	}
	storeContent := encryptContentStore(content)
	now := time.Now().Unix()
	result, err := db.Exec("INSERT INTO messages (from_user_id, to_user_id, content, created_at, msg_type, server_received_ms) VALUES (?, ?, ?, ?, 'group_announcement', ?)", userID, convID, storeContent, now, time.Now().UnixMilli())
	if err != nil {
		log.Printf("发布群公告失败: %v", err)
		writeJSON(w, 500, "发布失败", nil)
		return
	}
	msgID, _ := result.LastInsertId()

	fromUserName := ""
	if u, e := findUserByID(userID); e == nil && u != nil {
		fromUserName = u.Username
	}
	PushToGroup(convID, msgID, userID, fromUserName, convID, content, now, "text", "", 0, 0, "", "")
	// PushToGroup 会排除发送者，但发布者本地没有这条消息，需定向推送给自己，保证在线时也能看到公告卡片
	PushToUser(userID, "new_message", map[string]interface{}{
		"message_id":      msgID,
		"from_user_id":    userID,
		"from_username":   fromUserName,
		"to_user_id":      convID,
		"content":         content,
		"created_at":      now,
		"media_type":      "text",
		"media_url":       "",
		"flash_duration":  0,
		"reply_to_id":     0,
		"reply_to_text":   "",
		"reply_to_sender": "",
	})

	log.Printf("群公告已发布: groupID=%d msgID=%d", req.GroupID, msgID)
	writeJSON(w, 200, "发布成功", map[string]interface{}{"message_id": msgID})
}

// POST /api/groups/msg-rate — 设置群内发言频率（群主/管理员；开发者可操作任意群）
// limit: -1=不限制（关闭），0=全员禁言，N>0=每位成员每分钟最多 N 条
// scope: 0=群主及管理员不受限（默认），1=管理员受限（群主不受限），2=全部受限
// scope 不传（nil）时保持原值不动，兼容只改频率的旧客户端。
// 注意：该限制针对「每一个成员」独立计数，不是全群总量。
func handleSetGroupMsgRate(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		GroupID int64 `json:"group_id"`
		Limit   int   `json:"limit"`
		Scope   *int  `json:"scope"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.GroupID <= 0 {
		writeJSON(w, 400, "缺少群ID", nil)
		return
	}

	currentUserID := getCurrentUserID(r)
	if !isDeveloperUser(currentUserID) {
		role, err := getMemberRole(req.GroupID, currentUserID)
		if err != nil || (role != "owner" && role != "admin") {
			writeJSON(w, 403, "仅群主和管理员可设置发言频率", nil)
			return
		}
		if !hasGroupPerm(req.GroupID, currentUserID, "msg_rate") {
			writeJSON(w, 403, "没有修改「发言频率」的权限", nil)
			return
		}
	}

	// 兜底收敛：低于 -1 视为不限制；上限 100000 条/分钟，避免超大值把发送侧计数变成无意义查询
	if req.Limit < -1 {
		req.Limit = -1
	}
	if req.Limit > 100000 {
		req.Limit = 100000
	}

	if err := setGroupMsgRateLimit(req.GroupID, req.Limit); err != nil {
		log.Printf("设置群发言频率失败: %v", err)
		writeJSON(w, 500, "设置失败", nil)
		return
	}
	if req.Scope != nil {
		scope := *req.Scope
		if scope < 0 || scope > 2 {
			scope = 0
		}
		if err := setGroupMsgRateScope(req.GroupID, scope); err != nil {
			log.Printf("设置群发言频率范围失败: %v", err)
			writeJSON(w, 500, "设置失败", nil)
			return
		}
	}

	log.Printf("群发言频率已更新: group=%d limit=%d by=%d", req.GroupID, req.Limit, currentUserID)
	respScope := -1 // -1 = 本次未修改，保持原值
	if req.Scope != nil {
		respScope = *req.Scope
	}
	writeJSON(w, 200, "设置成功", map[string]interface{}{"msg_rate_limit": req.Limit, "msg_rate_scope": respScope})
}

// GET /api/groups/info/{groupId} — 获取群聊详细信息（用内部 ID）
func handleGetGroupInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api/groups/info/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, 400, "缺少群ID", nil)
		return
	}
	groupID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeJSON(w, 400, "群ID格式错误", nil)
		return
	}

	group, err := getGroupByID(groupID)
	if err != nil {
		log.Printf("获取群信息失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if group == nil {
		writeJSON(w, 404, "群聊不存在", nil)
		return
	}

	// 获取当前请求用户在群中的角色
	currentUserID := getCurrentUserID(r)
	if currentUserID > 0 {
		role, err := getMemberRole(groupID, currentUserID)
		if err == nil {
			group.UserRole = role
		}
		// 细粒度权限：客户端据此隐藏/禁用没有权限的入口（服务端仍会二次校验）
		group.UserPerms = effectiveGroupPerms(groupID, currentUserID)
		// 填充每条公告的确认状态（仅当前用户视角 + 确认人数）
		for i := range group.Announcements {
			a := &group.Announcements[i]
			a.ConfirmedByMe = isAnnouncementConfirmed(groupID, a.Text, currentUserID)
			a.ConfirmCount = countAnnouncementConfirmed(groupID, a.Text)
		}
	}

	// 非群成员（被移出 / 已退出 / 从未加入）：公告与欢迎语一律不下发。
	// 群名、签名、头像继续返回——入群预览与分享卡片依赖它们。
	if !isDeveloperUser(currentUserID) && !isGroupMember(groupID, currentUserID) {
		stripGroupPrivateFieldsForNonMember(group)
	}

	writeJSON(w, 200, "获取成功", group)
}

// stripGroupPrivateFieldsForNonMember 把「只有群成员才该看到」的字段清空。
//
// 背景：/api/groups/info/{id} 不校验成员身份（入群预览、分享卡片都要靠它拿群名/签名/头像），
// 但公告和欢迎语属于群内信息——被移出之后，对方后续更新的公告不应该还能被这个接口读出来。
// 客户端虽然已经隐藏了入口，但「界面不显示」不等于「拿不到」，所以在这里直接不下发。
func stripGroupPrivateFieldsForNonMember(group *Group) {
	if group == nil {
		return
	}
	group.Announcement = ""
	group.Announcements = nil
	group.WelcomeText = ""
	group.WelcomeEnabled = false
}

// POST /api/groups/announcement/confirm — 群成员确认收到某条公告（幂等）。
// 公告标识 = 公告文本（与撤回联动移除口径一致）。未开启「需确认收到」时前端不展示入口，
// 后端对确认请求不做开关限制（记录无害且保持幂等）。
func handleConfirmGroupAnnouncement(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		GroupID int64  `json:"group_id"`
		Text    string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.GroupID <= 0 || strings.TrimSpace(req.Text) == "" {
		writeJSON(w, 400, "缺少群ID或公告内容", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	// 权限：仅群成员可确认（开发者可操作任意群）
	if !isDeveloperUser(userID) {
		role, err := getMemberRole(req.GroupID, userID)
		if err != nil || role == "" {
			writeJSON(w, 403, "仅群成员可确认公告", nil)
			return
		}
	}

	req.Text = html.EscapeString(strings.TrimSpace(req.Text))
	if err := markAnnouncementConfirmed(req.GroupID, req.Text, userID); err != nil {
		log.Printf("确认公告失败: %v", err)
		writeJSON(w, 500, "确认失败", nil)
		return
	}
	log.Printf("群公告已确认: groupID=%d userID=%d", req.GroupID, userID)
	writeJSON(w, 200, "确认成功", nil)
}

// POST /api/groups/announcement/confirms — 某条公告的「确认收到」名单（仅群主/管理员/开发者）。
// 公告标识与确认接口一致 = 公告文本；返回确认人昵称与确认时间，供管理端抽屉展示。
func handleGetAnnouncementConfirms(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	var req struct {
		GroupID int64  `json:"group_id"`
		Text    string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.GroupID <= 0 || strings.TrimSpace(req.Text) == "" {
		writeJSON(w, 400, "缺少群ID或公告内容", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	// 权限：确认名单只对群主/管理员开放（开发者可查看任意群）
	if !isDeveloperUser(userID) {
		role, err := getMemberRole(req.GroupID, userID)
		if err != nil || (role != "owner" && role != "admin") {
			writeJSON(w, 403, "仅群主或管理员可查看", nil)
			return
		}
	}

	// 与确认接口相同的转义口径，确保能命中确认记录
	text := html.EscapeString(strings.TrimSpace(req.Text))
	rows, err := db.Query("SELECT user_id, confirmed_at FROM group_announcement_confirmations WHERE group_id = ? AND announcement_text = ? ORDER BY confirmed_at ASC", req.GroupID, text)
	if err != nil {
		log.Printf("获取公告确认名单失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	defer rows.Close()

	users := make([]map[string]interface{}, 0)
	for rows.Next() {
		var uid, at int64
		if rows.Scan(&uid, &at) == nil {
			users = append(users, map[string]interface{}{
				"user_id":      uid,
				"nickname":     getUserName(uid),
				"confirmed_at": at,
			})
		}
	}
	writeJSON(w, 200, "获取成功", map[string]interface{}{"users": users, "count": len(users)})
}

// GET /api/groups/members/{groupId} — 获取群成员列表
func handleGetGroupMembers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	path := strings.TrimPrefix(r.URL.Path, "/api/groups/members/")
	parts := strings.Split(path, "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, 400, "缺少群ID", nil)
		return
	}
	groupID, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeJSON(w, 400, "群ID格式错误", nil)
		return
	}

	members, err := getGroupMembersWithInfo(groupID)
	if err != nil {
		log.Printf("获取群成员失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}
	if members == nil {
		members = []GroupMemberInfo{}
	}
	writeJSON(w, 200, "获取成功", members)
}

// GET /api/groups/search?q={id} — 按 display_id 搜索群聊
func handleSearchGroups(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		writeJSON(w, 200, "请输入群ID", []Group{})
		return
	}

	groups, err := searchGroupsByDisplayID(q)
	if err != nil {
		log.Printf("搜索群聊失败: %v", err)
		writeJSON(w, 500, "搜索失败", nil)
		return
	}

	log.Printf("[搜索群聊] 关键词=%q, 找到%d个匹配", q, len(groups))
	if groups == nil {
		groups = []Group{}
	}
	writeJSON(w, 200, "搜索成功", groups)
}

// GET /api/groups/{userId} — 获取用户加入的群聊列表
// 同时作为 /api/groups/ 下所有子路由的调度器（兼容 Go 1.21）
func handleGetGroups(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	method := r.Method
	log.Printf("[handleGetGroups] %s %s", method, path)

	// ── 按路径分发给对应的 handler ──
	// 注意：更具体的路径必须放在前面，避免被前缀匹配误拦截
	switch {
	case strings.HasPrefix(path, "/api/groups/create"):
		log.Printf("[dispatch] -> handleCreateGroup")
		handleCreateGroup(w, r)
	case strings.HasPrefix(path, "/api/groups/announcement/confirms"):
		log.Printf("[dispatch] -> handleGetAnnouncementConfirms")
		handleGetAnnouncementConfirms(w, r)
	case strings.HasPrefix(path, "/api/groups/announcement/confirm"):
		log.Printf("[dispatch] -> handleConfirmGroupAnnouncement")
		handleConfirmGroupAnnouncement(w, r)
	case strings.HasPrefix(path, "/api/groups/announcement/post"):
		log.Printf("[dispatch] -> handlePostGroupAnnouncement")
		handlePostGroupAnnouncement(w, r)
	case strings.HasPrefix(path, "/api/groups/info/"):
		log.Printf("[dispatch] -> handleGetGroupInfo")
		handleGetGroupInfo(w, r)
	case strings.HasPrefix(path, "/api/groups/settings"):
		log.Printf("[dispatch] -> handleUpdateGroupSettings")
		handleUpdateGroupSettings(w, r)
	case strings.HasPrefix(path, "/api/groups/join-requests/"):
		log.Printf("[dispatch] -> handleGetJoinRequests")
		handleGetJoinRequests(w, r)
	case strings.HasPrefix(path, "/api/groups/join-request-count/"):
		log.Printf("[dispatch] -> handleJoinRequestCount")
		handleJoinRequestCount(w, r)
	case strings.HasPrefix(path, "/api/groups/join-request"):
		log.Printf("[dispatch] -> handleJoinRequest")
		handleJoinRequest(w, r)
	case strings.HasPrefix(path, "/api/groups/review-join"):
		log.Printf("[dispatch] -> handleReviewJoinRequest")
		handleReviewJoinRequest(w, r)
	case strings.HasPrefix(path, "/api/groups/dissolve"):
		log.Printf("[dispatch] -> handleDissolveGroup")
		handleDissolveGroup(w, r)
	case strings.HasPrefix(path, "/api/groups/transfer"):
		log.Printf("[dispatch] -> handleTransferOwner")
		handleTransferOwner(w, r)
	case strings.HasPrefix(path, "/api/groups/set-admin"):
		log.Printf("[dispatch] -> handleSetAdmin")
		handleSetAdmin(w, r)
	case strings.HasPrefix(path, "/api/groups/msg-rate"):
		log.Printf("[dispatch] -> handleSetGroupMsgRate")
		handleSetGroupMsgRate(w, r)
	case strings.HasPrefix(path, "/api/groups/avatar/upload"):
		log.Printf("[dispatch] -> handleGroupAvatarUpload")
		handleGroupAvatarUpload(w, r)
	case strings.HasPrefix(path, "/api/groups/avatar/"):
		log.Printf("[dispatch] -> handleGroupAvatarGet")
		handleGroupAvatarGet(w, r)
	case strings.HasPrefix(path, "/api/groups/members/"):
		log.Printf("[dispatch] -> handleGetGroupMembers")
		handleGetGroupMembers(w, r)
	default:
		log.Printf("[handleGetGroups] 未匹配到子路由, method=%s, path=%s, 执行默认逻辑", method, path)
		if r.Method != "GET" {
			writeJSON(w, 405, "仅支持 GET 请求", nil)
			return
		}
		currentUserID := getCurrentUserID(r)

		trimmed := strings.TrimPrefix(path, "/api/groups/")
		parts := strings.Split(trimmed, "/")
		userID := currentUserID
		if len(parts) > 0 && parts[0] != "" {
			if id, err := strconv.ParseInt(parts[0], 10, 64); err == nil {
				userID = id
			}
		}
		if userID == 0 {
			writeJSON(w, 400, "缺少用户ID", nil)
			return
		}

		groups, err := getGroupsForUser(userID)
		if err != nil {
			log.Printf("获取群聊列表失败: %v", err)
			writeJSON(w, 500, "获取失败", nil)
			return
		}
		if groups == nil {
			groups = []Group{}
		}
		writeJSON(w, 200, "获取成功", groups)
	}
}
