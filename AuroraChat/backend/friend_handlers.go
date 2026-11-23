package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// getCurrentUserID 从请求头提取当前登录用户 ID（由 authMiddleware 注入）
func getCurrentUserID(r *http.Request) int64 {
	id, _ := strconv.ParseInt(r.Header.Get("X-User-ID"), 10, 64)
	return id
}

func randomString(n int) string {
	b := make([]byte, n/2+1)
	rand.Read(b)
	return hex.EncodeToString(b)[:n]
}

func getCurrentEmail(r *http.Request) string {
	return r.Header.Get("X-User-Email")
}

// getCurrentEmailFromHeader 从请求头提取当前用户邮箱（由 authMiddleware 注入）
func getCurrentEmailFromHeader(r *http.Request) string {
	return r.Header.Get("X-User-Email")
}

// maskEmail 将邮箱脱敏为 a***@x.com
func maskEmail(email string) string {
	// 没填就是没填：打码串会被前端当成「有值」，反而把「未设置」显示成 ***
	if strings.TrimSpace(email) == "" {
		return ""
	}
	at := strings.Index(email, "@")
	if at <= 1 {
		return "***"
	}
	prefix := email[:at]
	suffix := email[at:]
	if len(prefix) <= 2 {
		return prefix[:1] + "***" + suffix
	}
	return prefix[:2] + "***" + suffix
}

// maskQQ 将 QQ 号脱敏为 388****539
func maskQQ(qq string) string {
	if strings.TrimSpace(qq) == "" {
		return ""
	}
	if len(qq) <= 4 {
		return "****"
	}
	return qq[:2] + "****" + qq[len(qq)-3:]
}

// qqVisibleTo 判定「查看者能否看到目标用户的完整 QQ 号」——全站唯一判定入口。
//
// 为什么要有这个函数：
// 这套判定原本散在四处、三套标准——
//   - 搜索用户：hide_qq != 0 或「不是本人」就打码（陌生人一律打码）
//   - 用户资料：只有 hide_qq != 0 才打码
//   - 黑名单：只有 hide_qq != 0 才打码
//   - 好友列表：完全不打码
//
// 于是出现了自相矛盾的体验：搜索列表里打了码，点「查看」进资料页却是明文。
// 更要紧的是资料页那一处本身就是漏洞：hide_qq 默认值是 0，等于全站未开开关的
// QQ 号对任何登录账号可见，遍历 /api/user/{id} 就能拖走全库 QQ——和之前修过的
// 「邮箱一律泄露」是同一类问题，只是 QQ 这一半漏了。
//
// 统一后的规则（任何出口都必须调用本函数，不允许各写一套）：
//   - 本人           → 可见
//   - 开发者         → 可见（排障需要）
//   - 对方开了 hide_qq → 一律不可见，好友也一样（开关就是开关）
//   - 好友           → 可见（好友关系本身即授权，与好友列表口径保持一致）
//   - 其余（陌生人） → 一律打码
//
// isFriend 由调用方传入而不是在这里查库：搜索结果的批量场景需要一次性取好友集合，
// 逐条查库会变成 N 次 SQL。
func qqVisibleTo(viewerID, targetID int64, targetHideQQ int, viewerIsDev, isFriend bool) bool {
	if viewerID <= 0 || targetID <= 0 {
		return false
	}
	if viewerID == targetID || viewerIsDev {
		return true
	}
	if targetHideQQ != 0 {
		return false
	}
	return isFriend
}

// ==================== 搜索用户 ====================

func handleSearchUsers(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	// 防批量抓取用户资料：单账号 1 分钟 60 次搜索（正常找人用不到这么多）
	if ok, wait := checkSlidingLimit(fmt.Sprintf("search-users:%d", getCurrentUserID(r)), time.Minute, 60); !ok {
		writeJSON(w, 429, fmt.Sprintf("搜索过于频繁，请 %d 秒后再试", wait), nil)
		return
	}

	q := strings.TrimSpace(r.URL.Query().Get("q"))
	log.Printf("[搜索用户] 收到请求: q=%q", q)

	if q == "" {
		writeJSON(w, 200, "请输入搜索关键词", []User{})
		return
	}

	users, err := searchUsersByKeyword(q)
	if err != nil {
		log.Printf("[搜索用户] 数据库查询失败: %v", err)
		writeJSON(w, 500, "搜索失败", nil)
		return
	}

	// 隐私脱敏：判定统一走 qqVisibleTo，注册 IP / token 余额 / token 版本一律不外发
	currentUID := getCurrentUserID(r)
	isDev := isDeveloperUser(currentUID)
	for i := range users {
		u := &users[i]
		// 好友关系用 areFriends（双向）而不是 getFriends（单向），
		// 保证与资料页 handleGetUserByID 的口径完全一致——否则对方单方面删了我时，
		// 搜索页会显示明文、点进资料页却变打码，等于又造一个新的自相矛盾。
		isFriend, ferr := areFriends(currentUID, u.ID)
		if ferr != nil {
			isFriend = false // 查库失败往严格一侧倒
		}
		if !qqVisibleTo(currentUID, u.ID, u.HideQQ, isDev, isFriend) {
			u.QQNumber = maskQQ(u.QQNumber)
		}
		// 邮箱一律脱敏：hide_email 默认值是 0，原逻辑等于任何登录账号
		// 都能用 /api/search-users 批量导出全站邮箱
		if currentUID != u.ID {
			u.Email = maskEmail(u.Email)
		}
		u.RegIp = ""
		u.TokenBalance = 0
		u.TokenVersion = 0
	}

	if users == nil {
		users = []User{}
	}
	writeJSON(w, 200, "搜索成功", users)
}

// ==================== 好友请求 ====================

func handleSendFriendRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	var req struct {
		ToEmail  string `json:"to_email"`
		Greeting string `json:"greeting"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	req.ToEmail = strings.TrimSpace(req.ToEmail)
	if req.ToEmail == "" {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	// 支持邮箱 / QQ号 / 用户ID / 用户名
	var targetUser *User
	var err error
	switch {
	case emailRegex.MatchString(req.ToEmail):
		// 看起来像邮箱 — 按邮箱查
		targetUser, err = findUserByEmail(req.ToEmail)
	case isDigitsOnly(req.ToEmail):
		// 纯数字 — 先按 QQ 号查，查不到再按用户 ID 查
		targetUser, err = findUserByQQNumber(req.ToEmail)
		if targetUser == nil && err == nil {
			if uid, parseErr := strconv.ParseInt(req.ToEmail, 10, 64); parseErr == nil && uid > 0 {
				targetUser, err = findUserByID(uid)
			}
		}
	default:
		// 其他 — 按用户名查（最常见的路径：前端传用户名）
		targetUser, err = findUserByUsername(req.ToEmail)
		// 用户名查不到时再按邮箱兜底（极少数人邮箱不带 @ 符号的边界情况）
		if targetUser == nil && err == nil {
			targetUser, err = findUserByEmail(req.ToEmail)
		}
	}
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if targetUser == nil {
		writeJSON(w, 404, "未找到该用户（支持邮箱、QQ号、用户ID、用户名）", nil)
		return
	}
	if targetUser.ID == currentUserID {
		writeJSON(w, 400, "不能添加自己为好友", nil)
		return
	}
	// 拦截已注销账号（email 被改为 deleted_{id}@deleted.aurora.chat）
	if strings.HasPrefix(targetUser.Email, "deleted_") && strings.HasSuffix(targetUser.Email, "@deleted.aurora.chat") {
		writeJSON(w, 404, "该用户账号已注销", nil)
		return
	}
	// 拦截被封禁账号
	if IsUserBanned(targetUser.ID) {
		writeJSON(w, 403, "该用户已被封禁，无法发送好友申请", nil)
		return
	}

	// 黑名单拦截：双方存在任一方向黑名单时禁止发送好友申请（规则 c）
	if blocked, _ := isBlockedAnyDir(currentUserID, targetUser.ID); blocked {
		var iAmBlocker int
		db.QueryRow("SELECT COUNT(*) FROM user_blacklist WHERE blocker_id = ? AND blocked_id = ?", currentUserID, targetUser.ID).Scan(&iAmBlocker)
		if iAmBlocker > 0 {
			writeJSON(w, 403, "你已将对方拉入黑名单，无法发送好友申请", nil)
		} else {
			writeJSON(w, 403, "对方已将你拉入黑名单，无法发送好友申请", nil)
		}
		return
	}

	// 与好友列表 / 好友状态完全同一口径：只看「我这一侧」friends 行是否存在。
	// 旧实现用 areFriends（双向两行），会出现「好友列表里没有他、申请却提示
	// 对方已经是你的好友」的死循环；查库失败必须报错，不能默认当成非好友放行。
	myRow, _, ferr := friendRelation(currentUserID, targetUser.ID)
	if ferr != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if myRow {
		writeJSON(w, 409, "对方已经是你的好友", nil)
		return
	}

	hasPending, _ := hasPendingRequest(currentUserID, targetUser.ID)
	if hasPending {
		writeJSON(w, 409, "已发送过好友申请，请等待对方处理", nil)
		return
	}

	me, _ := findUserByID(currentUserID)
	if me == nil {
		writeJSON(w, 400, "用户不存在", nil)
		return
	}

	greeting := html.EscapeString(req.Greeting)
	reqID, err := createFriendRequest(currentUserID, targetUser.ID, me.Email, me.Username, greeting)
	if err != nil {
		writeJSON(w, 500, "发送失败", nil)
		return
	}

	log.Printf("好友请求已发送: %s(%d) -> %s(%d) request_id=%d", me.Email, currentUserID, targetUser.Email, targetUser.ID, reqID)

	// 通过 TCP 实时推送给目标用户
	PushToUser(targetUser.ID, "friend_request", map[string]interface{}{
		"from_user_id":  currentUserID,
		"from_username": me.Username,
		"from_email":    me.Email,
		"greeting":      greeting,
		"request_id":    reqID,
	})

	writeJSON(w, 200, "好友申请已发送", map[string]int64{"request_id": reqID})
}

func handleGetFriendRequests(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	requests, err := getFriendRequestsForUser(currentUserID)
	if err != nil {
		log.Printf("获取好友请求失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}

	if requests == nil {
		requests = []FriendRequest{}
	}
	log.Printf("[好友请求] 用户 %d 拉取好友请求，返回 %d 条", currentUserID, len(requests))
	for _, fr := range requests {
		log.Printf("[好友请求]   -> id=%d from=%d(%s) status=%s", fr.ID, fr.FromUserID, fr.FromUsername, fr.Status)
	}
	writeJSON(w, 200, "获取成功", requests)
}

func handleRespondFriendRequest(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	var req struct {
		RequestID int64  `json:"request_id"`
		Action    string `json:"action"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}

	if req.RequestID == 0 || (req.Action != "accept" && req.Action != "reject") {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}

	fr, err := getFriendRequestByID(req.RequestID)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if fr == nil {
		writeJSON(w, 404, "好友请求不存在", nil)
		return
	}
	if fr.ToUserID != currentUserID {
		writeJSON(w, 403, "无权操作此请求", nil)
		return
	}

	if req.Action == "accept" {
		fromUser, _ := findUserByID(fr.FromUserID)
		toUser, _ := findUserByID(fr.ToUserID)
		if fromUser == nil || toUser == nil {
			writeJSON(w, 500, "用户不存在", nil)
			return
		}

		if err := addFriend(fr.ToUserID, fr.FromUserID, fromUser.Email, fromUser.Username); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		if err := addFriend(fr.FromUserID, fr.ToUserID, toUser.Email, toUser.Username); err != nil {
			writeJSON(w, 500, "操作失败", nil)
			return
		}
		// 通知发起方好友请求已通过
		PushToUser(fr.FromUserID, "friend_accepted", map[string]interface{}{
			"user_id": fr.ToUserID,
			"name":    toUser.Username,
		})
	}

	if err := updateFriendRequestStatus(req.RequestID, req.Action+"ed"); err != nil {
		writeJSON(w, 500, "操作失败", nil)
		return
	}

	msg := "已拒绝"
	if req.Action == "accept" {
		msg = "已同意，你们现在是好友了"
	}
	log.Printf("好友请求 %d: %s", req.RequestID, req.Action)
	writeJSON(w, 200, msg, nil)
}

// POST /api/friend-request/cancel — 取消（删除）某条好友申请记录（本人发出或收到的均可）
func handleCancelFriendRequest(w http.ResponseWriter, r *http.Request) {
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
		RequestID int64 `json:"request_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.RequestID == 0 {
		writeJSON(w, 400, "参数不完整", nil)
		return
	}
	fr, err := getFriendRequestByID(req.RequestID)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if fr == nil {
		writeJSON(w, 404, "好友请求不存在", nil)
		return
	}
	if fr.FromUserID != currentUserID && fr.ToUserID != currentUserID {
		writeJSON(w, 403, "无权操作此请求", nil)
		return
	}
	if err := deleteFriendRequest(req.RequestID, currentUserID); err != nil {
		writeJSON(w, 500, "取消失败", nil)
		return
	}
	writeJSON(w, 200, "已取消", nil)
}

// ==================== 好友列表 ====================

func handleGetFriends(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}

	currentUserID := getCurrentUserID(r)

	friends, err := getFriends(currentUserID)
	if err != nil {
		log.Printf("获取好友列表失败: %v", err)
		writeJSON(w, 500, "获取失败", nil)
		return
	}

	if friends == nil {
		friends = []User{}
	}
	writeJSON(w, 200, "获取成功", friends)
}

// POST /api/friend/delete — 单方面删除好友（只移除我这一侧）
// 说明：删除后对方的好友列表仍保留我；双方会话与历史消息都不受影响；
// 按单向口径，删除方判定为非好友（顶部挂非好友横幅），被删方仍判定为好友。
func handleDeleteFriend(w http.ResponseWriter, r *http.Request) {
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
		FriendID int64 `json:"friend_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	if req.FriendID == 0 {
		writeJSON(w, 400, "缺少好友ID", nil)
		return
	}

	// 单方面删除：只要求「我 → 对方」这一行存在。
	// 不要求双向——对方可能已先单方面删了我，此时我仍需能删掉自己这条记录（否则会残留幽灵好友）。
	var myRow int
	if err := db.QueryRow("SELECT COUNT(*) FROM friends WHERE user_id = ? AND friend_id = ?", currentUserID, req.FriendID).Scan(&myRow); err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	if myRow == 0 {
		writeJSON(w, 400, "你们不是好友关系", nil)
		return
	}

	if err := deleteFriend(currentUserID, req.FriendID); err != nil {
		log.Printf("删除好友失败: %v", err)
		writeJSON(w, 500, "删除失败", nil)
		return
	}

	log.Printf("好友已单方面删除: user=%d, friend=%d", currentUserID, req.FriendID)
	writeJSON(w, 200, "已删除好友", nil)
}

// GET /api/friend/status?user_id=X — 好友关系的权威结论（单向，与好友列表同口径）。
// certain=true 表示服务端查库成功、结论可作「百分百判定」，客户端才允许据此
// 落地本地「非好友标记」并画横幅；任何失败都返回非 200，客户端一律视为未知。
func handleFriendStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	currentUserID := getCurrentUserID(r)
	if currentUserID == 0 {
		writeJSON(w, 401, "未登录", nil)
		return
	}
	targetID, _ := strconv.ParseInt(r.URL.Query().Get("user_id"), 10, 64)
	if targetID <= 0 {
		writeJSON(w, 400, "缺少用户ID", nil)
		return
	}
	myRow, peerRow, err := friendRelation(currentUserID, targetID)
	if err != nil {
		writeJSON(w, 500, "服务器错误", nil)
		return
	}
	writeJSON(w, 200, "获取成功", map[string]interface{}{
		"is_friend":   myRow,
		"peer_has_me": peerRow,
		"certain":     true,
	})
}
