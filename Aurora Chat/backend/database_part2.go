package main

import (
	cryptorand "crypto/rand"
	"database/sql"
	"encoding/base64"
	"fmt"
	"log"
	"math/rand"
	_ "modernc.org/sqlite"
	"strings"
	"time"
)

func getFriendRequestByID(id int64) (*FriendRequest, error) {
	r := &FriendRequest{}
	err := db.QueryRow("SELECT id, from_user_id, to_user_id, from_email, from_username, greeting, status, created_at FROM friend_requests WHERE id = ?", id).Scan(&r.ID, &r.FromUserID, &r.ToUserID, &r.FromEmail, &r.FromUsername, &r.Greeting, &r.Status, &r.CreatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return r, nil
}

func updateFriendRequestStatus(id int64, status string) error {
	_, err := db.Exec("UPDATE friend_requests SET status = ? WHERE id = ?", status, id)
	return err
}

// deleteFriendRequest 删除某条好友申请记录（仅限本人发出或收到的）
func deleteFriendRequest(requestID int64, userID int64) error {
	_, err := db.Exec("DELETE FROM friend_requests WHERE id = ? AND (from_user_id = ? OR to_user_id = ?)", requestID, userID, userID)
	return err
}

func hasPendingRequest(fromUserID, toUserID int64) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM friend_requests WHERE ((from_user_id = ? AND to_user_id = ?) OR (from_user_id = ? AND to_user_id = ?)) AND status = 'pending'", fromUserID, toUserID, toUserID, fromUserID).Scan(&count)
	return count > 0, err
}

// areFriends 判断两人是否「互为好友」（双向两行都在）。
// 仅用于 QQ 号码可见性、陌生人消息拦截、拉黑清理等双向语义场景；
// 好友列表 / 好友申请 / 好友状态 / 非好友横幅一律用 friendRelation（单向），
// 否则会出现「列表没他、申请却说已经是好友」的自相矛盾。
func areFriends(userID1, userID2 int64) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM friends WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)", userID1, userID2, userID2, userID1).Scan(&count)
	return count >= 2, err
}

// friendRelation 是「好友关系」的唯一事实来源：friends 表里 (user_id=me, friend_id=other)
// 这一行存在，就是我的好友。peerRow 只表示对方那一侧是否还留着我，不参与好友判定。
//
// 为什么好友关系不再用 areFriends：areFriends 要求双向两行都在，
// 而好友列表 /api/friends/ 只查「我这一侧」且 JOIN users。口径不一致时会出现
// 「好友申请说对方已经是你的好友、好友列表里却没有这个人、聊天页据此画出
// 『对方还不是你的好友』」的自相矛盾。现在好友列表、好友申请、好友状态
// 三个接口全部以本函数为准，结论永远一致。
func friendRelation(me, other int64) (myRow bool, peerRow bool, err error) {
	var mine, peer int
	if err = db.QueryRow("SELECT COUNT(*) FROM friends WHERE user_id = ? AND friend_id = ?", me, other).Scan(&mine); err != nil {
		return false, false, err
	}
	if err = db.QueryRow("SELECT COUNT(*) FROM friends WHERE user_id = ? AND friend_id = ?", other, me).Scan(&peer); err != nil {
		return false, false, err
	}
	return mine > 0, peer > 0, nil
}

// ==================== Friends ====================

func addFriend(userID, friendID int64, friendEmail, friendUsername string) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT OR IGNORE INTO friends (user_id, friend_id, friend_email, friend_username, created_at) VALUES (?, ?, ?, ?, ?)", userID, friendID, friendEmail, friendUsername, now)
	return err
}

// deleteFriend 单方面删除好友：只删「我 → 对方」这一行。
// 对方的好友列表仍保留我（对方不丢联系人）；按 friendRelation 的单向口径，
// 删除方判定为非好友（挂非好友标记），被删方仍判定为好友。
// 会话与历史消息不受影响（会话列表不再按好友关系过滤）。
func deleteFriend(userID, friendID int64) error {
	_, err := db.Exec("DELETE FROM friends WHERE user_id = ? AND friend_id = ?", userID, friendID)
	return err
}

// deleteFriendBoth 双向删除好友（用于拉黑等需要彻底解除双方关系的场景）。
func deleteFriendBoth(userID, friendID int64) error {
	_, err := db.Exec("DELETE FROM friends WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)", userID, friendID, friendID, userID)
	return err
}

func getFriends(userID int64) ([]User, error) {
	// 必须用 LEFT JOIN：friends 里存在关系行、users 里对应账号被清理（注销/迁移）时，
	// 旧的 INNER JOIN 会直接把这行丢掉，导致「明明是好友，好友列表里却没有他」，
	// 客户端据此画出非好友横幅。缺 users 行时用 friends 里冗余的邮箱/用户名兜底，
	// 至少 ID 一定在列表里（好友关系的判定只认 ID）。
	rows, err := db.Query(`SELECT f.friend_id,
		COALESCE(u.email, f.friend_email, ''),
		COALESCE(u.username, f.friend_username, ''),
		COALESCE(u.created_at, f.created_at),
		COALESCE(u.updated_at, 0),
		COALESCE(u.hide_email, 0),
		COALESCE(u.hide_qq, 0),
		COALESCE(u.require_group_consent, 0),
		COALESCE(u.qq_number, ''),
		COALESCE(u.signature, ''),
		COALESCE(u.online_time_seconds, 0),
		COALESCE(u.word_count, 0)
		FROM friends f LEFT JOIN users u ON f.friend_id = u.id
		WHERE f.user_id = ? ORDER BY f.created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []User
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Email, &u.Username, &u.CreatedAt, &u.UpdatedAt, &u.HideEmail, &u.HideQQ, &u.RequireGroupConsent, &u.QQNumber, &u.Signature, &u.OnlineTimeSeconds, &u.WordCount); err != nil {
			return nil, err
		}
		// 对方主动关掉了 QQ 展示：即使是好友也不给完整号码，否则这个开关形同虚设。
		// 与 qqVisibleTo 同一口径，保证「好友列表 / 资料页 / 搜索页」看到的永远一致。
		if u.HideQQ != 0 {
			u.QQNumber = maskQQ(u.QQNumber)
		}
		users = append(users, u)
	}
	return users, nil
}

// ==================== 用户级黑名单（拉黑单用户） ====================

// BlacklistItem 黑名单列表条目：other 为对称后的「对方」用户，
// IAmBlocker=true 表示「我拉黑了对方」，false 表示「对方拉黑了我」。
type BlacklistItem struct {
	UserID     int64  `json:"user_id"`
	Username   string `json:"username"`
	Email      string `json:"email"`
	QQNumber   string `json:"qq_number"`
	Signature  string `json:"signature"`
	HideEmail  int    `json:"hide_email"`
	HideQQ     int    `json:"hide_qq"`
	IAmBlocker bool   `json:"i_am_blocker"`
	BlockedAt  int64  `json:"blocked_at"`
}

// blockUser 拉黑：写入 (blocker_id, blocked_id)（幂等），并解除双方好友关系（拉黑即非好友）。
func blockUser(blockerID, blockedID int64) error {
	now := time.Now().Unix()
	if _, err := db.Exec("INSERT OR IGNORE INTO user_blacklist (blocker_id, blocked_id, blocked_at) VALUES (?, ?, ?)", blockerID, blockedID, now); err != nil {
		return err
	}
	// 规则 b：拉黑后双方不再算好友，若当前是好友则双向删除（拉黑是强动作，双方列表都要清）
	if friends, _ := areFriends(blockerID, blockedID); friends {
		_ = deleteFriendBoth(blockerID, blockedID)
	}
	return nil
}

// unblockUser 拉出黑名单（对称删除：任一方向都删同一行）。
func unblockUser(userID, otherID int64) error {
	_, err := db.Exec("DELETE FROM user_blacklist WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)", userID, otherID, otherID, userID)
	return err
}

// isBlockedAnyDir 判断两用户之间是否存在任一方向的黑名单关系（对称）。
func isBlockedAnyDir(a, b int64) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM user_blacklist WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)", a, b, b, a).Scan(&count)
	return count > 0, err
}

// isStrangerMsgBlocked 目标用户是否开启了「非好友禁止向我发消息」。
// 查询失败时按「未开启」处理：这是限制性开关，DB 异常不应该反过来把消息全拦掉。
func isStrangerMsgBlocked(userID int64) bool {
	var v int
	if err := db.QueryRow("SELECT block_stranger_msg FROM user_privacy WHERE user_id = ?", userID).Scan(&v); err != nil {
		return false
	}
	return v != 0
}

// setStrangerMsgBlocked 写入「非好友禁止向我发消息」开关。
func setStrangerMsgBlocked(userID int64, block bool) error {
	v := 0
	if block {
		v = 1
	}
	_, err := db.Exec(`INSERT INTO user_privacy (user_id, block_stranger_msg) VALUES (?, ?)
		ON CONFLICT(user_id) DO UPDATE SET block_stranger_msg = ?`, userID, v, v)
	return err
}

// getBlacklist 返回当前用户的黑名单列表（含双向：我拉黑的 + 拉黑我的），按拉黑时间倒序。
func getBlacklist(userID int64) ([]BlacklistItem, error) {
	rows, err := db.Query(`
		SELECT
			CASE WHEN ub.blocker_id = ? THEN ub.blocked_id ELSE ub.blocker_id END AS other_id,
			CASE WHEN ub.blocker_id = ? THEN 1 ELSE 0 END AS i_am_blocker,
			ub.blocked_at,
			u.username, u.email, u.qq_number, u.signature, u.hide_email, u.hide_qq
		FROM user_blacklist ub
		JOIN users u ON u.id = (CASE WHEN ub.blocker_id = ? THEN ub.blocked_id ELSE ub.blocker_id END)
		WHERE ub.blocker_id = ? OR ub.blocked_id = ?
		ORDER BY ub.blocked_at DESC`, userID, userID, userID, userID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var items []BlacklistItem
	for rows.Next() {
		var it BlacklistItem
		var iam, hideEmail, hideQQ int
		if err := rows.Scan(&it.UserID, &iam, &it.BlockedAt, &it.Username, &it.Email, &it.QQNumber, &it.Signature, &hideEmail, &hideQQ); err != nil {
			return nil, err
		}
		it.IAmBlocker = iam != 0
		it.HideEmail = hideEmail
		it.HideQQ = hideQQ
		// 隐私脱敏：黑名单里的人已因拉黑解除好友关系，按陌生人处理 → 一律打码。
		// 原来只在对方开了 hide_email/hide_qq 时才遮，与搜索页的「陌生人一律打码」不一致。
		it.Email = maskEmail(it.Email)
		it.QQNumber = maskQQ(it.QQNumber)
		items = append(items, it)
	}
	return items, nil
}

// ==================== Messages ====================

type Message struct {
	ID               int64  `json:"id"`
	FromUserID       int64  `json:"from_user_id"`
	ToUserID         int64  `json:"to_user_id"`
	Content          string `json:"content"`
	CreatedAt        int64  `json:"created_at"`
	IsRevoked        int    `json:"is_revoked"`
	RevokedAt        int64  `json:"revoked_at"`
	FromUserName     string `json:"username"`
	ReplyToID        int64  `json:"reply_to_id"`
	ReplyToText      string `json:"reply_to_text"`
	ReplyToSender    string `json:"reply_to_sender"`
	MediaType        string `json:"media_type"`
	MediaURL         string `json:"media_url"`
	FlashDuration    int    `json:"flash_duration"`
	ToUserName       string `json:"to_user_name"`
	MsgType          string `json:"msg_type"`
	BroadcastTaskID  int64  `json:"broadcast_task_id"`
	ContactSource    string `json:"contact_source"`
	MediaWidth       int    `json:"media_width"`
	MediaHeight      int    `json:"media_height"`
	ClientSendAt     int64  `json:"client_send_at"`
	ServerReceivedMs int64  `json:"server_received_ms"`
	// @ 目标映射 JSON（{"发送时昵称": 用户ID}）。昵称会随改名变化，ID 不会，
	// 客户端据此在改名后仍能高亮并定位到被 @ 的人。
	Mentions string `json:"mentions"`
}

func saveMessage(fromUserID, toUserID int64, content string, replyToID int64, mediaType, mediaURL string, flashDuration int, replyToText string, replyToSender string, mediaWidth, mediaHeight int, clientSendAt int64, mentions ...string) (int64, error) {
	mentionJSON := ""
	if len(mentions) > 0 {
		mentionJSON = mentions[0]
	}
	now := time.Now().Unix()
	// 传输解密：客户端发送前用发送者 session_key 加密（sess:v1:），这里解密回明文，
	// 保证「客户端→服务端」传输阶段无明文；非 sess:v1:（旧客户端明文）原样透传，兼容不破坏。
	content = decryptSessionContent(fromUserID, content)
	replyToText = decryptSessionContent(fromUserID, replyToText)
	// 引用消息预览：优先采用客户端上报的 replyToText；
	// 否则回退到从被引用消息派生（服务端可读，派生即正确明文）。
	if replyToText == "" && replyToID > 0 {
		orig, _ := getMessageByID(replyToID)
		if orig != nil {
			replyToText = orig.Content
			if replyToSender == "" {
				origSender, _ := findUserByID(orig.FromUserID)
				if origSender != nil {
					replyToSender = origSender.Username
				}
			}
		}
	}
	// 服务端静态加密：对所有消息入库前加密
	storeContent := encryptContentStore(content)
	result, err := db.Exec("INSERT INTO messages (from_user_id, to_user_id, content, created_at, reply_to_id, reply_to_text, reply_to_sender, media_type, media_url, flash_duration, media_width, media_height, client_send_at, server_received_ms, mentions) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", fromUserID, toUserID, storeContent, now, replyToID, replyToText, replyToSender, mediaType, mediaURL, flashDuration, mediaWidth, mediaHeight, clientSendAt, time.Now().UnixMilli(), mentionJSON)
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

// getMessages 拉取两人/群聊消息，统一返回「旧→新」。
//   - 默认：从第 1 条开始升序分页（安卓端历史漫游依赖这个语义，不能改）
//   - tail：取最近 limit 条（桌面端首次加载）
//   - beforeID：取 id 小于它的最近 limit 条（桌面端向上翻历史，键集分页不受新消息插入影响）
func getMessages(userID1, userID2 int64, limit, offset int, afterID, beforeID int64, tail bool) ([]Message, error) {
	// 倒序取、返回前翻转回升序的两种情况：tail（最新一屏）与 beforeID（更早一屏）。
	// after_id 是增量拉新，永远保持升序。
	newestFirst := afterID == 0 && (tail || beforeID > 0)
	replyCols := ", COALESCE(m.reply_to_id, 0), COALESCE(m.reply_to_text, ''), COALESCE(m.reply_to_sender, ''), COALESCE(m.media_type, ''), COALESCE(m.media_url, ''), COALESCE(m.flash_duration, 0), COALESCE(m.media_width, 0), COALESCE(m.media_height, 0), COALESCE(m.mentions, '')"
	toNameCol := ", COALESCE(m.to_user_name, ut.username, ''), COALESCE(m.msg_type, ''), COALESCE(m.broadcast_task_id, 0), COALESCE(m.contact_source, ''), COALESCE(m.client_send_at, 0), COALESCE(m.server_received_ms, 0)"
	var rows *sql.Rows
	var err error
	if userID2 < 0 {
		sqlStr := `SELECT m.id, m.from_user_id, m.to_user_id, m.content, m.created_at, m.is_revoked, m.revoked_at, COALESCE(u.username, '')` + replyCols + toNameCol + ` FROM messages m LEFT JOIN users u ON m.from_user_id = u.id LEFT JOIN users ut ON m.to_user_id = ut.id WHERE (m.from_user_id = ? OR m.to_user_id = ?) AND m.to_user_id < 0`
		args := []interface{}{userID2, userID2}
		if afterID > 0 {
			sqlStr += " AND m.id > ?"
			args = append(args, afterID)
		}
		if beforeID > 0 {
			sqlStr += " AND m.id < ?"
			args = append(args, beforeID)
		}
		// 统一显式排序为旧→新（id 升序），配合下方不再反转，保证全量/增量都顺序稳定。
		// 切勿改回 DESC：客户端首次加载（无本地缓存）时不做二次排序，DESC 会让整列消息彻底颠倒，
		// 表现为「旧消息堆在最上面 / 刚发的新消息跑到下面」。
		// tail=1（桌面端取最新一屏）例外：先倒序取，返回前在下方翻回升序。
		if newestFirst {
			sqlStr += " ORDER BY m.id DESC"
		} else {
			sqlStr += " ORDER BY m.id ASC"
		}
		if limit > 0 {
			sqlStr += " LIMIT ? OFFSET ?"
			args = append(args, limit, offset)
		}
		rows, err = db.Query(sqlStr, args...)
	} else {
		sqlStr := `SELECT m.id, m.from_user_id, m.to_user_id, m.content, m.created_at, m.is_revoked, m.revoked_at, COALESCE(u.username, '')` + replyCols + toNameCol + ` FROM messages m LEFT JOIN users u ON m.from_user_id = u.id LEFT JOIN users ut ON m.to_user_id = ut.id WHERE (m.from_user_id = ? AND m.to_user_id = ?) OR (m.from_user_id = ? AND m.to_user_id = ?)`
		args := []interface{}{userID1, userID2, userID2, userID1}
		if afterID > 0 {
			sqlStr += " AND m.id > ?"
			args = append(args, afterID)
		}
		if beforeID > 0 {
			sqlStr += " AND m.id < ?"
			args = append(args, beforeID)
		}
		// 统一显式排序为旧→新（id 升序），配合下方不再反转，保证全量/增量都顺序稳定。
		// 切勿改回 DESC：客户端首次加载（无本地缓存）时不做二次排序，DESC 会让整列消息彻底颠倒，
		// 表现为「旧消息堆在最上面 / 刚发的新消息跑到下面」。
		// tail=1（桌面端取最新一屏）例外：先倒序取，返回前在下方翻回升序。
		if newestFirst {
			sqlStr += " ORDER BY m.id DESC"
		} else {
			sqlStr += " ORDER BY m.id ASC"
		}
		if limit > 0 {
			sqlStr += " LIMIT ? OFFSET ?"
			args = append(args, limit, offset)
		}
		rows, err = db.Query(sqlStr, args...)
	}
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var msgs []Message
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.FromUserID, &m.ToUserID, &m.Content, &m.CreatedAt, &m.IsRevoked, &m.RevokedAt, &m.FromUserName, &m.ReplyToID, &m.ReplyToText, &m.ReplyToSender, &m.MediaType, &m.MediaURL, &m.FlashDuration, &m.MediaWidth, &m.MediaHeight, &m.Mentions, &m.ToUserName, &m.MsgType, &m.BroadcastTaskID, &m.ContactSource, &m.ClientSendAt, &m.ServerReceivedMs); err != nil {
			return nil, err
		}
		// 服务端静态加密：读取时解密
		m.Content = decryptContentLoad(m.Content)
		maskRevokedContent(&m)
		msgs = append(msgs, m)
	}
	// tail=1 时 SQL 是倒序取的，翻转回「旧→新」再返回：对客户端来说顺序语义不变，
	// 唯一差别是这一页是最新的那段，而不是最早的那段。
	if newestFirst {
		for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
			msgs[i], msgs[j] = msgs[j], msgs[i]
		}
	}
	return msgs, nil
}

func getMessageByID(msgID int64) (*Message, error) {
	var m Message
	err := db.QueryRow(`SELECT m.id, m.from_user_id, m.to_user_id, m.content, m.created_at, m.is_revoked, m.revoked_at, COALESCE(u.username, ''), COALESCE(m.to_user_name, ''), COALESCE(m.reply_to_id, 0), COALESCE(m.reply_to_text, ''), COALESCE(m.reply_to_sender, ''), COALESCE(m.media_type, ''), COALESCE(m.media_url, ''), COALESCE(m.flash_duration, 0), COALESCE(m.media_width, 0), COALESCE(m.media_height, 0), COALESCE(m.mentions, ''), COALESCE(m.msg_type, ''), COALESCE(m.broadcast_task_id, 0), COALESCE(m.contact_source, ''), COALESCE(m.client_send_at, 0), COALESCE(m.server_received_ms, 0) FROM messages m LEFT JOIN users u ON m.from_user_id = u.id WHERE m.id = ?`, msgID).Scan(&m.ID, &m.FromUserID, &m.ToUserID, &m.Content, &m.CreatedAt, &m.IsRevoked, &m.RevokedAt, &m.FromUserName, &m.ToUserName, &m.ReplyToID, &m.ReplyToText, &m.ReplyToSender, &m.MediaType, &m.MediaURL, &m.FlashDuration, &m.MediaWidth, &m.MediaHeight, &m.Mentions, &m.MsgType, &m.BroadcastTaskID, &m.ContactSource, &m.ClientSendAt, &m.ServerReceivedMs)
	if err != nil {
		return nil, err
	}
	m.Content = decryptContentLoad(m.Content)
	maskRevokedContent(&m)
	return &m, nil
}

// maskRevokedContent 撤回后不再向任何客户端下发原文。
// 群公告正文是「系统公告数据{...}」这类内部格式，旧版/第三方客户端若把它当普通文本渲染，
// 会把原始 JSON 直接印在群里所有人的屏幕上；广播正文同理。这里统一替换为撤回占位，
// 原文只保留在服务端数据库中（客户端开发者「点击回显」依赖本地缓存，不依赖该接口）。
func maskRevokedContent(m *Message) {
	if m == nil || m.IsRevoked != 1 {
		return
	}
	if m.FromUserName != "" {
		m.Content = m.FromUserName + " 撤回了一条消息"
	} else {
		m.Content = "消息已撤回"
	}
	m.ReplyToText = ""
	m.ReplyToSender = ""
}

func recallMessage(msgID, userID int64) error {
	_, err := db.Exec("UPDATE messages SET is_revoked = 1, revoked_at = ? WHERE id = ? AND from_user_id = ?", time.Now().Unix(), msgID, userID)
	return err
}

func recallMessageAsAdmin(msgID int64) error {
	_, err := db.Exec("UPDATE messages SET is_revoked = 1, revoked_at = ? WHERE id = ?", time.Now().Unix(), msgID)
	return err
}

func markMessageRead(msgID, userID int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO message_reads (message_id, user_id, read_at) VALUES (?, ?, ?)", msgID, userID, time.Now().Unix())
	return err
}

func getMessageReadCount(msgID int64) (int, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM message_reads WHERE message_id = ?", msgID).Scan(&count)
	return count, err
}

// getMessageReadCountForSender 群消息已读人数：排除发送者自己。
// 发送者不是「已读方」——把他也算进去的话，自己发的群消息会永远显示「1人已读」，
// 一旦这个数字搬到气泡下方，用户看到的就是假象。
func getMessageReadCountForSender(msgID, senderID int64) (int, error) {
	var count int
	err := db.QueryRow(
		"SELECT COUNT(*) FROM message_reads WHERE message_id = ? AND user_id <> ?", msgID, senderID,
	).Scan(&count)
	return count, err
}

// markMessageReadIfNew 标记已读，并告诉调用方「是不是本次真的新增了一行」。
// 重复标记会被 INSERT OR IGNORE 吃掉，只有新增才值得推送 ——
// 否则每次打开会话都会把同一批消息的已读事件再推一遍，发送方那边的计数白刷。
func markMessageReadIfNew(msgID, userID int64) (bool, error) {
	res, err := db.Exec(
		"INSERT OR IGNORE INTO message_reads (message_id, user_id, read_at) VALUES (?, ?, ?)",
		msgID, userID, time.Now().Unix(),
	)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

func getRecentConversations(userID int64) ([]ConversationUser, error) {
	// 两次改写，均以实际执行计划为准：
	// 1) 原 WHERE from=? OR to=? + GROUP BY CASE 表达式 + created_at 等值 JOIN 无法命中索引（全表扫描）
	//    → 改为 UNION ALL 两个索引分支，分别命中 idx_msg_conv / idx_msg_conv_to。
	// 2) 原外层 LEFT JOIN 带相关子查询（每个会话 1 次 SELECT ... ORDER BY created_at DESC, id DESC LIMIT 1），
	//    50 个会话即 50 次子查询 + 50 次排序（N+1）
	//    → 改用 SQLite bare-column 特性：含 MAX() 的聚合查询中，未聚合列保证取自 MAX 命中的那一行，
	//      因此一次聚合即可同时拿到 last_time 与最新消息的 id/content，子查询次数 50 → 1。
	//
	// 语义说明：MAX(created_at) 并列（同一秒多条消息）时，bare column 取扫描序中先命中者；
	// 会话列表仅用于展示"最后一条消息预览"，同秒消息取任一条无实际差异。
	rows, err := db.Query(`SELECT u.id, u.email, u.username, u.created_at, u.updated_at,
COALESCE(recent.last_content, '') as last_message, COALESCE(recent.last_time, 0) as last_time,
recent.last_from, recent.last_to, recent.last_flash, recent.last_toname, recent.last_revoked, recent.last_media
FROM (
	SELECT other_id, MAX(created_at) as last_time, id as last_id, content as last_content, is_revoked as last_revoked, from_user_id as last_from, to_user_id as last_to, flash_duration as last_flash, COALESCE(to_user_name, '') as last_toname, media_type as last_media
	FROM (
		SELECT to_user_id AS other_id, created_at, id, content, is_revoked, from_user_id, to_user_id, flash_duration, to_user_name, media_type FROM messages WHERE from_user_id = ?
		UNION ALL
		SELECT from_user_id AS other_id, created_at, id, content, is_revoked, from_user_id, to_user_id, flash_duration, to_user_name, media_type FROM messages WHERE to_user_id = ?
	)
	GROUP BY other_id
	ORDER BY last_time DESC
	LIMIT 50
) recent
JOIN users u ON u.id = recent.other_id
ORDER BY recent.last_time DESC`, userID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []ConversationUser
	for rows.Next() {
		var cu ConversationUser
		var lastFrom, lastTo, lastFlash, lastRevoked int64
		var lastToname, lastMedia string
		if err := rows.Scan(&cu.ID, &cu.Email, &cu.Username, &cu.CreatedAt, &cu.UpdatedAt, &cu.LastMessage, &cu.LastTime, &lastFrom, &lastTo, &lastFlash, &lastToname, &lastRevoked, &lastMedia); err != nil {
			return nil, err
		}
		if lastFlash == -1 {
			// 拍一拍：按查看者视角个性化，避免会话列表直接显示 "POKE" 占位
			if lastFrom == userID {
				t := lastToname
				if t == "" {
					t = cu.Username // 兜底为会话对方
				}
				cu.LastMessage = "你拍了拍" + t
			} else if lastTo == userID {
				cu.LastMessage = cu.Username + "拍了拍你"
			} else {
				cu.LastMessage = cu.Username + "拍了拍" + lastToname
			}
		} else {
			// 媒体消息（图片/视频/闪照/文件）正文为空，这里统一补一个可读的预览文案，
			// 避免会话列表预览一片空白；已有正文的消息保持原文。
			text := decryptContentLoad(cu.LastMessage)
			switch {
			// 撤回必须放在最前面：撤回只置 is_revoked=1，正文仍留在库里，
			// 若先判断 text != "" 就会把撤回前的内容继续显示出来（表现为"撤回跟摆设一样"）。
			case lastRevoked == 1:
				if lastFrom == userID {
					cu.LastMessage = "你撤回了一条消息"
				} else {
					cu.LastMessage = cu.Username + "撤回了一条消息"
				}
			// 语音的 content 存的是时长秒数（如 "2"），不是正文。必须排在 text != "" 之前，
			// 否则会话列表会把最后一条语音显示成一个裸数字。
			case lastMedia == "voice":
				cu.LastMessage = "[语音]"
			case text != "":
				cu.LastMessage = text
			case lastFlash > 0:
				cu.LastMessage = "[闪照]"
			case lastMedia == "image":
				cu.LastMessage = "[图片]"
			case lastMedia == "video":
				cu.LastMessage = "[视频]"
			case lastMedia != "":
				cu.LastMessage = "[文件]"
			default:
				cu.LastMessage = ""
			}
		}
		users = append(users, cu)
	}
	return users, nil
}

type ConversationUser struct {
	User
	LastMessage string `json:"last_message"`
	LastTime    int64  `json:"last_time"`
}

func isServerRunning(serverID int64) bool {
	return computeServerStatus(serverID) == "running"
}

func savePublicKey(userID int64, publicKey string) error {
	_, err := db.Exec("INSERT OR REPLACE INTO public_keys (user_id, public_key, updated_at) VALUES (?, ?, ?)", userID, publicKey, time.Now().Unix())
	return err
}

func getPublicKey(userID int64) (string, error) {
	var key string
	err := db.QueryRow("SELECT public_key FROM public_keys WHERE user_id = ?", userID).Scan(&key)
	if err != nil {
		if err == sql.ErrNoRows {
			return "", nil
		}
		return "", err
	}
	return key, nil
}

// ==================== 传输会话密钥（session_key） ====================
// 每个用户一把 AES-256 会话密钥，客户端发送消息前用它加密内容（前缀 sess:v1:），
// 服务端收到后解密，消除传输阶段明文；落库仍走 enc:v1: 静态加密。

const sessionKeyPrefix = "sess:v1:"

// getOrCreateSessionKey 获取用户的传输会话密钥，不存在则生成（32 字节 AES-256）并持久化。
func getOrCreateSessionKey(userID int64) string {
	var key string
	err := db.QueryRow("SELECT session_key FROM transport_keys WHERE user_id = ?", userID).Scan(&key)
	if err == nil && key != "" {
		return key
	}
	// 生成 32 字节随机密钥
	raw := make([]byte, 32)
	if _, err := cryptorand.Read(raw); err != nil {
		return ""
	}
	key = base64.StdEncoding.EncodeToString(raw)
	if _, err := db.Exec("INSERT OR REPLACE INTO transport_keys (user_id, session_key, updated_at) VALUES (?, ?, ?)", userID, key, time.Now().Unix()); err != nil {
		return ""
	}
	return key
}

// getSessionKey 仅读取用户的传输会话密钥，不存在返回空串。
func getSessionKey(userID int64) string {
	var key string
	err := db.QueryRow("SELECT session_key FROM transport_keys WHERE user_id = ?", userID).Scan(&key)
	if err != nil {
		return ""
	}
	return key
}

// decryptSessionContent 若内容带 sess:v1: 前缀，则用对应用户的 session_key 解密回明文；
// 非 sess:v1: 前缀（旧客户端明文 / 已是其他格式）原样返回，保证兼容不破坏。
func decryptSessionContent(userID int64, content string) string {
	if !strings.HasPrefix(content, sessionKeyPrefix) {
		return content
	}
	keyB64 := getSessionKey(userID)
	if keyB64 == "" {
		return content
	}
	key, err := base64.StdEncoding.DecodeString(keyB64)
	if err != nil || len(key) != 32 {
		return content
	}
	plain, err := DecryptMessageAES(strings.TrimPrefix(content, sessionKeyPrefix), key)
	if err != nil {
		return content
	}
	return plain
}

func getMessageReadByUser(msgID, userID int64) (int64, error) {
	var readAt int64
	err := db.QueryRow("SELECT read_at FROM message_reads WHERE message_id = ? AND user_id = ?", msgID, userID).Scan(&readAt)
	if err != nil {
		return 0, err
	}
	return readAt, nil
}

// officialGroupConvID 解析官方群的会话 ID（to_user_id 形式 = -(1000+群ID)）。
// 官方群的 groups.id 是自增主键（ensureOfficialGroup 用 LastInsertId 取），并不保证等于 1；
// 早期实现把 -1001 写死，一旦线上群 ID 不是 1，官方群消息就会被当成普通消息按天清理掉。
func officialGroupConvID() int64 {
	var gid int64
	if err := db.QueryRow("SELECT id FROM groups WHERE display_id = 1").Scan(&gid); err != nil || gid <= 0 {
		return -1001 // 查询失败时退回历史约定值，保持原有排除行为
	}
	return -(1000 + gid)
}

// mediaRetentionDays 聊天图片/视频「物理文件」的保留天数。
// 注意：这里只限制媒体文件本身，消息记录（文本/时间/结构）永久保留，不受此值影响。
const mediaRetentionDays = 30

// cleanupOldMedia 定期清理聊天里的图片/视频文件，绝不删除任何消息记录。
//
// 当前数据保留机制（与旧版「按天删消息」彻底不同）：
//   - 消息内容（文本、时间、回复关系、撤回标记等）永久保留，私聊、普通群、官方群一律不删；
//   - 只对超过 mediaRetentionDays 天的图片/视频物理文件做清理，释放服务器存储；
//   - 文件被清理后消息仍在，客户端加载媒体会拿到 404，已有「已过期」占位处理；
//   - 官方群（display_id=1）整体永久保留，其媒体文件同样不清理。
func cleanupOldMedia(retentionDays int) {
	if retentionDays <= 0 {
		return
	}
	cutoff := time.Now().Unix() - int64(retentionDays)*24*3600
	officialConv := officialGroupConvID()
	rows, err := db.Query(
		`SELECT DISTINCT media_url FROM messages
		 WHERE created_at < ? AND media_url != ''
		   AND (
		     media_type IN ('jpg','jpeg','png','gif','mp4','webp','mov','image','video','live')
		     OR lower(media_url) LIKE '%.jpg'  OR lower(media_url) LIKE '%.jpeg'
		     OR lower(media_url) LIKE '%.png'  OR lower(media_url) LIKE '%.gif'
		     OR lower(media_url) LIKE '%.mp4'  OR lower(media_url) LIKE '%.webp'
		     OR lower(media_url) LIKE '%.mov'
		   )
		   AND to_user_id != ? AND from_user_id != ?`,
		cutoff, officialConv, officialConv,
	)
	if err != nil {
		log.Printf("[清理] 查询过期媒体失败: %v", err)
		return
	}
	var urls []string
	for rows.Next() {
		var u string
		if rows.Scan(&u) == nil && u != "" {
			urls = append(urls, u)
		}
	}
	rows.Close()
	cleaned := 0
	for _, u := range urls {
		// 只统计「确实落到服务器本地文件」的清理；外链、sticker:// 等会被内部判定为 false
		if deleteChatMediaByURL(u) {
			cleaned++
		}
	}
	if cleaned > 0 {
		log.Printf("[清理] 已清理 %d 个超过 %d 天的聊天图片/视频文件（消息记录永久保留）", cleaned, retentionDays)
	}
}

func getGroupMessages(groupConvID int64, limit, offset int) ([]Message, error) {
	// 与 getMessages（私聊）保持一致：limit<=0 时返回该群全部消息，不加 LIMIT。
	// 原实现无条件追加 "LIMIT ? OFFSET ?"，前端群聊初始加载传 limit=0 时被 SQLite 执行为
	// "LIMIT 0" 返回 0 条，导致群历史只能依赖本地缓存、服务端补不上，往上翻越翻越空（官方群尤为明显）。
	sqlStr := `SELECT m.id, m.from_user_id, m.to_user_id, m.content, m.created_at, m.is_revoked, m.revoked_at, COALESCE(u.username, ''), COALESCE(m.to_user_name, ''), COALESCE(m.reply_to_id, 0), COALESCE(m.reply_to_text, ''), COALESCE(m.reply_to_sender, ''), COALESCE(m.media_type, ''), COALESCE(m.media_url, ''), COALESCE(m.flash_duration, 0), COALESCE(m.media_width, 0), COALESCE(m.media_height, 0), COALESCE(m.msg_type, ''), COALESCE(m.broadcast_task_id, 0) FROM messages m LEFT JOIN users u ON m.from_user_id = u.id WHERE m.to_user_id = ?`
	args := []interface{}{groupConvID}
	sqlStr += " ORDER BY m.created_at DESC"
	if limit > 0 {
		sqlStr += " LIMIT ? OFFSET ?"
		args = append(args, limit, offset)
	}
	rows, err := db.Query(sqlStr, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var msgs []Message
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.FromUserID, &m.ToUserID, &m.Content, &m.CreatedAt, &m.IsRevoked, &m.RevokedAt, &m.FromUserName, &m.ToUserName, &m.ReplyToID, &m.ReplyToText, &m.ReplyToSender, &m.MediaType, &m.MediaURL, &m.FlashDuration, &m.MediaWidth, &m.MediaHeight, &m.MsgType, &m.BroadcastTaskID); err != nil {
			return nil, err
		}
		m.Content = decryptContentLoad(m.Content)
		msgs = append(msgs, m)
	}
	return msgs, nil
}

// deleteUserAllData 注销账号：删掉这个人的个人数据，但**绝不连坐删掉他创建的群**。
//
// 群是多人共享的空间。原来这里直接 DELETE 掉他创建的所有群，等于一个人注销就把群里
// 所有人的聊天记录、媒体文件、群设置一起抹掉，对其它成员影响极大。现在改成
// 「群主身份顺位交接」：
//   - 他是群主 → 交给管理员；没有管理员就交给最近在群里发过言的成员；再没有就交给
//     入群最早的成员。群照常存在，只是换个人管；
//   - 群里只剩他一个人 → 确实没有接收者，才真正删除该群（连同消息与媒体文件）；
//   - 他只是普通成员 / 管理员 → 只移除成员身份，群归群主照常。
func deleteUserAllData(userID int64) error {
	type handover struct {
		groupID  int64
		newOwner int64 // 0 = 没人可接
	}
	var handovers []handover
	var dissolvedGroups []int64

	// ── 1. 算清「要交接的群」和「确实该删的群」（只读，事务外做，方便逐条挑接收人）──
	seen := make(map[int64]bool)
	var ownedGroups []int64
	if rows, err := db.Query("SELECT group_id, role FROM group_members WHERE user_id = ?", userID); err == nil {
		for rows.Next() {
			var gid int64
			var role string
			if rows.Scan(&gid, &role) == nil {
				seen[gid] = true
				if role == "owner" {
					ownedGroups = append(ownedGroups, gid)
				}
			}
		}
		rows.Close()
	}
	// 兼容历史脏数据：creator_id 是自己但成员表里已经没有自己（转让群主时没同步 creator_id）。
	// 只有在「该群确实没有别的群主」时才算进来，避免误抢已经转让出去的群。
	var creatorGroups []int64
	if rows, err := db.Query("SELECT id FROM groups WHERE creator_id = ?", userID); err == nil {
		for rows.Next() {
			var gid int64
			if rows.Scan(&gid) == nil && !seen[gid] {
				creatorGroups = append(creatorGroups, gid)
			}
		}
		rows.Close()
	}
	for _, gid := range creatorGroups {
		var otherOwners int
		db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ? AND role = 'owner' AND user_id != ?",
			gid, userID).Scan(&otherOwners)
		if otherOwners == 0 {
			ownedGroups = append(ownedGroups, gid)
		}
	}

	for _, gid := range ownedGroups {
		var others int
		db.QueryRow("SELECT COUNT(*) FROM group_members WHERE group_id = ? AND user_id != ?", gid, userID).Scan(&others)
		if others <= 0 {
			dissolvedGroups = append(dissolvedGroups, gid) // 只剩他一个人，没有接收者
			continue
		}
		handovers = append(handovers, handover{groupID: gid, newOwner: pickNewGroupOwner(gid, userID)})
	}

	// 要删的群：先把媒体 URL 收下来（消息行一删，这些文件就永远反查不到了）
	mediaByGroup := make(map[int64][]string, len(dissolvedGroups))
	for _, gid := range dissolvedGroups {
		mediaByGroup[gid] = collectGroupMediaURLs(gid)
	}

	// ── 2. 事务内改数据 ──
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	tx.Exec("DELETE FROM friend_requests WHERE from_user_id = ? OR to_user_id = ?", userID, userID)
	tx.Exec("DELETE FROM friends WHERE user_id = ? OR friend_id = ?", userID, userID)
	tx.Exec("DELETE FROM group_join_requests WHERE user_id = ?", userID)
	// 这个人在「别的群」里留下的、与他本人绑定的附属行：账号没了就是死数据
	tx.Exec("DELETE FROM group_mutes WHERE user_id = ?", userID)
	tx.Exec("DELETE FROM group_blacklist WHERE user_id = ?", userID)
	tx.Exec("DELETE FROM group_admin_perms WHERE user_id = ?", userID)
	tx.Exec("DELETE FROM group_announcement_confirmations WHERE user_id = ?", userID)

	now := time.Now().Unix()
	for _, h := range handovers {
		if h.newOwner <= 0 {
			continue
		}
		tx.Exec("UPDATE group_members SET role = 'owner' WHERE group_id = ? AND user_id = ?", h.groupID, h.newOwner)
		// creator_id 只是展示/统计字段，但群主换人后它还指向一个不存在的账号会很难看，跟着改
		tx.Exec("UPDATE groups SET creator_id = ?, updated_at = ? WHERE id = ?", h.newOwner, now, h.groupID)
	}

	// 没有任何其他成员的群：确实无人可接，整群清理（消息 + 附属表 + 群）
	for _, gid := range dissolvedGroups {
		convID := -(1000 + gid)
		tx.Exec("DELETE FROM messages WHERE from_user_id = ? OR to_user_id = ?", convID, convID)
		for _, table := range groupChildTables {
			tx.Exec("DELETE FROM "+table+" WHERE group_id = ?", gid)
		}
		tx.Exec("DELETE FROM group_members WHERE group_id = ?", gid)
		tx.Exec("DELETE FROM groups WHERE id = ?", gid)
	}

	tx.Exec("DELETE FROM group_members WHERE user_id = ?", userID)
	tx.Exec("DELETE FROM public_keys WHERE user_id = ?", userID)
	tx.Exec("DELETE FROM verification_codes WHERE email = (SELECT email FROM users WHERE id = ?)", userID)
	tx.Exec("DELETE FROM user_security WHERE user_id = ?", userID)
	// 物理删除账号行，释放 email 与 qq_number 供后续复用
	if _, err := tx.Exec("DELETE FROM users WHERE id=?", userID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}

	// ── 3. 提交之后再做有副作用的事：群内公告「谁接手了」、回收物理文件 ──
	for _, h := range handovers {
		if h.newOwner > 0 {
			notifyOwnerTransfer(h.groupID, h.newOwner)
			log.Printf("[注销] 群主身份已交接: group=%d 原群主=%d 新群主=%d", h.groupID, userID, h.newOwner)
		}
	}
	for _, gid := range dissolvedGroups {
		go cleanupDissolvedGroupFiles(gid, mediaByGroup[gid])
		log.Printf("[注销] 群 %d 仅剩注销账号一人，已解散", gid)
	}
	return nil
}

// pickNewGroupOwner 群主注销 / 退群时挑一个接手的人：
// 管理员 → 最近在群里发过言的成员 → 入群最早的成员。返回 0 表示群里已没有其他人。
func pickNewGroupOwner(groupID, excludeUserID int64) int64 {
	var uid int64
	// 1) 管理员优先：接手后权限完整，不必再走一遍「设管理员」
	if err := db.QueryRow(
		`SELECT user_id FROM group_members
		 WHERE group_id = ? AND user_id != ? AND role = 'admin'
		 ORDER BY joined_at ASC, user_id ASC LIMIT 1`,
		groupID, excludeUserID).Scan(&uid); err == nil && uid > 0 {
		return uid
	}
	// 2) 最近在群里发过言的成员（真实活跃，而不是只看入群时间）
	if err := db.QueryRow(
		`SELECT m.from_user_id FROM messages m
		 JOIN group_members gm ON gm.group_id = ? AND gm.user_id = m.from_user_id
		 WHERE m.to_user_id = ? AND m.from_user_id != ?
		 ORDER BY m.created_at DESC LIMIT 1`,
		groupID, -(1000 + groupID), excludeUserID).Scan(&uid); err == nil && uid > 0 {
		return uid
	}
	// 3) 兜底：入群最早的成员
	if err := db.QueryRow(
		`SELECT user_id FROM group_members
		 WHERE group_id = ? AND user_id != ?
		 ORDER BY joined_at ASC, user_id ASC LIMIT 1`,
		groupID, excludeUserID).Scan(&uid); err == nil && uid > 0 {
		return uid
	}
	return 0
}

func updateGroupAvatar(groupID int64, avatarPath string) error {
	_, err := db.Exec("UPDATE groups SET avatar=? WHERE id=?", avatarPath, groupID)
	return err
}

// ==================== Groups ====================

type Group struct {
	ID           int64  `json:"id"`
	Name         string `json:"name"`
	Signature    string `json:"signature"`
	Announcement string `json:"announcement"`
	// Announcements 多公告列表（从 announcement 字段解析，兼容旧的单条字符串）
	Announcements  []GroupAnnouncement `json:"announcements"`
	CreatorID      int64               `json:"creator_id"`
	Avatar         string              `json:"avatar"`
	DisplayID      int64               `json:"display_id"`
	WelcomeEnabled bool                `json:"welcome_enabled"`
	WelcomeText    string              `json:"welcome_text"`
	// NotSearchable 无法被搜索（入群设置）
	NotSearchable bool `json:"not_searchable"`
	// JoinRequired 入群需申请
	JoinRequired bool `json:"join_required"`
	// MsgRateLimit 群内发言频率：-1=未开启（不限制），0=全员禁言，N>0=每位成员每分钟最多 N 条
	MsgRateLimit int `json:"msg_rate_limit"`
	// MsgRateScope 发言频率作用范围：0=群主及管理员不受限（默认），1=管理员受限（群主不受限），2=全部受限
	MsgRateScope int    `json:"msg_rate_scope"`
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
	UserRole     string `json:"user_role"`
	// UserPerms 当前请求用户在该群实际拥有的细粒度权限 key 列表（群主=全部，管理员=按其配置，普通成员=空）
	UserPerms []string `json:"user_perms"`
}

// GroupAnnouncement 群公告条目（存储于 groups.announcement 字段的 JSON 数组中）。
// 公告标识 = Text（公告文本），与撤回联动移除、确认记录口径一致。
type GroupAnnouncement struct {
	Text         string   `json:"text"`
	Images       []string `json:"images,omitempty"`     // 最多5张图片URL
	PopupEnabled bool     `json:"popup_enabled"`        // 使用弹窗展示公告
	NeedConfirm  bool     `json:"need_confirm"`         // 需群成员确认收到
	CreatedAt    int64    `json:"created_at,omitempty"` // 发布日期（epoch 毫秒），由客户端发布时写入
	// 以下字段仅 API 输出时填充，不写入存储（marshal 时使用 store 结构剔除）
	ConfirmedByMe bool `json:"confirmed_by_me,omitempty"`
	ConfirmCount  int  `json:"confirm_count,omitempty"`
}

// groupAnnouncementStore 存储专用结构（不含 API 临时字段）
type groupAnnouncementStore struct {
	Text         string   `json:"text"`
	Images       []string `json:"images,omitempty"`
	PopupEnabled bool     `json:"popup_enabled"`
	NeedConfirm  bool     `json:"need_confirm"`
	CreatedAt    int64    `json:"created_at,omitempty"`
}

func generateUniqueDisplayID() (int64, error) {
	for i := 0; i < 100; i++ {
		did := int64(rand.Intn(99999) + 2)
		var count int
		db.QueryRow("SELECT COUNT(*) FROM groups WHERE display_id = ?", did).Scan(&count)
		if count == 0 {
			return did, nil
		}
	}
	for did := int64(2); did <= 100000; did++ {
		var count int
		db.QueryRow("SELECT COUNT(*) FROM groups WHERE display_id = ?", did).Scan(&count)
		if count == 0 {
			return did, nil
		}
	}
	return 0, fmt.Errorf("群ID已用完")
}

func createGroup(name, signature, announcement string, creatorID int64, welcomeEnabled bool, welcomeText string) (int64, int64, error) {
	displayID, err := generateUniqueDisplayID()
	if err != nil {
		return 0, 0, err
	}
	now := time.Now().Unix()
	wEnabled := 0
	if welcomeEnabled {
		wEnabled = 1
	}
	result, err := db.Exec("INSERT INTO groups (name, signature, announcement, creator_id, display_id, welcome_enabled, welcome_text, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", name, signature, announcement, creatorID, displayID, wEnabled, welcomeText, now, now)
	if err != nil {
		return 0, 0, err
	}
	groupID, err := result.LastInsertId()
	if err != nil {
		return 0, 0, err
	}
	_, err = db.Exec("INSERT INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'owner', ?)", groupID, creatorID, now)
	return groupID, displayID, err
}
