package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	_ "modernc.org/sqlite"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var db *sql.DB

func initDB(dbPath string) {
	var err error
	db, err = sql.Open("sqlite", dbPath+"?_journal_mode=WAL&_busy_timeout=5000")
	if err != nil {
		log.Fatalf("无法打开数据库: %v", err)
	}

	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(3)
	db.SetConnMaxLifetime(5 * time.Minute)

	createTables()
	initSiteEndpointsTable()
}

func createTables() {
	var err error
	createTableSQL := `
	CREATE TABLE IF NOT EXISTS users (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		email TEXT NOT NULL UNIQUE,
		username TEXT NOT NULL,
		password TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL
	);
	CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

	CREATE TABLE IF NOT EXISTS verification_codes (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	email TEXT NOT NULL,
	code TEXT NOT NULL,
	created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_vc_email ON verification_codes(email);

	CREATE TABLE IF NOT EXISTS friend_requests (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		from_user_id INTEGER NOT NULL,
		to_user_id INTEGER NOT NULL,
		from_email TEXT NOT NULL,
		from_username TEXT NOT NULL,
		greeting TEXT DEFAULT '',
		status TEXT NOT NULL DEFAULT 'pending',
		created_at INTEGER NOT NULL
	);
	CREATE INDEX IF NOT EXISTS idx_fr_to ON friend_requests(to_user_id, status);

	CREATE TABLE IF NOT EXISTS friends (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		friend_id INTEGER NOT NULL,
		friend_email TEXT NOT NULL,
		friend_username TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		UNIQUE(user_id, friend_id)
	);
	CREATE INDEX IF NOT EXISTS idx_friends_user ON friends(user_id);

	CREATE TABLE IF NOT EXISTS messages (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		from_user_id INTEGER NOT NULL,
		to_user_id INTEGER NOT NULL,
		content TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		is_revoked INTEGER DEFAULT 0,
		revoked_at INTEGER DEFAULT 0
	);
	CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(from_user_id, to_user_id, created_at);
	-- 会话列表/to_user_id 侧查询的复合索引（与 idx_msg_conv 互为反向，覆盖 "我收到的消息" 分支）
	CREATE INDEX IF NOT EXISTS idx_msg_conv_to ON messages(to_user_id, from_user_id, created_at);

	CREATE TABLE IF NOT EXISTS message_reads (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		message_id INTEGER NOT NULL,
		user_id INTEGER NOT NULL,
		read_at INTEGER NOT NULL,
		UNIQUE(message_id, user_id)
	);
	CREATE INDEX IF NOT EXISTS idx_mr_msg ON message_reads(message_id);

	CREATE TABLE IF NOT EXISTS public_keys (
		user_id INTEGER PRIMARY KEY,
		public_key TEXT NOT NULL,
		updated_at INTEGER NOT NULL
	);

	CREATE TABLE IF NOT EXISTS transport_keys (
		user_id INTEGER PRIMARY KEY,
		session_key TEXT NOT NULL,
		updated_at INTEGER NOT NULL
	);

	CREATE TABLE IF NOT EXISTS groups (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		name TEXT NOT NULL,
		signature TEXT DEFAULT '',
		announcement TEXT DEFAULT '',
		creator_id INTEGER NOT NULL,
		avatar TEXT DEFAULT '',
		display_id INTEGER NOT NULL DEFAULT 0,
		welcome_enabled INTEGER NOT NULL DEFAULT 0,
		welcome_text TEXT DEFAULT '',
		msg_rate_limit INTEGER NOT NULL DEFAULT -1,
		msg_rate_scope INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL
	);
	CREATE UNIQUE INDEX IF NOT EXISTS idx_groups_display_id ON groups(display_id);

	CREATE TABLE IF NOT EXISTS group_members (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		group_id INTEGER NOT NULL,
		user_id INTEGER NOT NULL,
		role TEXT NOT NULL DEFAULT 'member',
		joined_at INTEGER NOT NULL,
		UNIQUE(group_id, user_id)
	);
	CREATE INDEX IF NOT EXISTS idx_gm_group ON group_members(group_id);
	CREATE INDEX IF NOT EXISTS idx_gm_user ON group_members(user_id);

	-- 群待办：每个群同时只有一条生效的待办（指向群内某条消息），设置即覆盖。
	-- 群主/管理员长按消息可把该消息设为群待办；全员在会话顶部看到提示，点击可跳转到原消息。
	CREATE TABLE IF NOT EXISTS group_todos (
		group_id INTEGER PRIMARY KEY,
		message_id INTEGER NOT NULL,
		setter_id INTEGER NOT NULL,
		setter_name TEXT DEFAULT '',
		snippet TEXT DEFAULT '',
		created_at INTEGER NOT NULL
	);

	CREATE TABLE IF NOT EXISTS group_join_requests (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		group_id INTEGER NOT NULL,
		user_id INTEGER NOT NULL,
		reason TEXT DEFAULT '',
		status TEXT NOT NULL DEFAULT 'pending',
		created_at INTEGER NOT NULL,
		UNIQUE(group_id, user_id)
	);

	CREATE TABLE IF NOT EXISTS group_invites (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		group_id INTEGER NOT NULL,
		inviter_id INTEGER NOT NULL,
		target_user_id INTEGER NOT NULL,
		status TEXT NOT NULL DEFAULT 'pending',
		created_at INTEGER NOT NULL
	);

	-- 群公告确认收到记录：公告标识 = 公告文本（与撤回联动移除口径一致），幂等
	CREATE TABLE IF NOT EXISTS group_announcement_confirmations (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		group_id INTEGER NOT NULL,
		announcement_text TEXT NOT NULL,
		user_id INTEGER NOT NULL,
		confirmed_at INTEGER NOT NULL,
		UNIQUE(group_id, announcement_text, user_id)
	);
	CREATE INDEX IF NOT EXISTS idx_gac_group ON group_announcement_confirmations(group_id);

	CREATE TABLE IF NOT EXISTS banned_users (
		user_id INTEGER PRIMARY KEY,
		reason TEXT NOT NULL,
		banned_at INTEGER NOT NULL,
		expires_at INTEGER NOT NULL,
		banned_by INTEGER NOT NULL
	);

	CREATE TABLE IF NOT EXISTS user_security (
		user_id INTEGER PRIMARY KEY,
		password_hash TEXT DEFAULT '',
		gesture_pattern TEXT DEFAULT '',
		security_enabled INTEGER NOT NULL DEFAULT 0,
		multi_verify INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL,
		FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
	);

	CREATE TABLE IF NOT EXISTS blocked_emails (
		email TEXT PRIMARY KEY,
		blocked_at INTEGER NOT NULL,
		blocked_by INTEGER NOT NULL
	);
	CREATE TABLE IF NOT EXISTS blocked_ips (
		ip TEXT PRIMARY KEY,
		blocked_at INTEGER NOT NULL,
		blocked_by INTEGER NOT NULL,
		reason TEXT DEFAULT ''
	);

	CREATE TABLE IF NOT EXISTS login_logs (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		login_time INTEGER NOT NULL,
		login_ip TEXT DEFAULT '',
		FOREIGN KEY (user_id) REFERENCES users(id)
	);
	CREATE INDEX IF NOT EXISTS idx_login_logs_user_id ON login_logs(user_id);
	CREATE INDEX IF NOT EXISTS idx_login_logs_login_time ON login_logs(login_time);

	CREATE TABLE IF NOT EXISTS user_push_config (
		user_id INTEGER PRIMARY KEY,
		method INTEGER NOT NULL DEFAULT 2,
		template_mode INTEGER NOT NULL DEFAULT 1,
		own_email TEXT DEFAULT '',
		own_auth TEXT DEFAULT '',
		dnd_ids TEXT DEFAULT '[]',
		notify_enabled INTEGER NOT NULL DEFAULT 1,
		updated_at INTEGER NOT NULL
	);

	`

	_, err = db.Exec(createTableSQL)
	if err != nil {
		log.Fatalf("创建数据表失败: %v", err)
	}

	db.Exec("ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0")
	db.Exec("ALTER TABLE users ADD COLUMN kicked_at INTEGER NOT NULL DEFAULT 0")

	// 设备类别版本号：token_version 是「全局版本」（改密/管理员踢人 → 全端失效），
	// 这两列只管「同类设备新登录踢旧登录」。手机(mobile)与桌面(pc)各自独立，
	// 避免一端登录把另一端顶下线。新增列时用全局版本做初始值，保证存量 token 不被误踢。
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN token_version_mobile INTEGER NOT NULL DEFAULT 0"); err == nil {
		db.Exec("UPDATE users SET token_version_mobile = token_version")
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN token_version_pc INTEGER NOT NULL DEFAULT 0"); err == nil {
		db.Exec("UPDATE users SET token_version_pc = token_version")
	}
	// Agent 这类「只借用身份、不参与踢号」的客户端单独一类：它的版本号只跟随全局版本，
	// 手机/桌面登录都不会让它失效（否则手机上登录一次，Agent 就得重新登录）。
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN token_version_agent INTEGER NOT NULL DEFAULT 0"); err == nil {
		db.Exec("UPDATE users SET token_version_agent = token_version")
	}

	// 推送配置新增"总通知开关"字段；老库无此列，迁移补列（默认开启）
	if _, err := db.Exec("ALTER TABLE user_push_config ADD COLUMN notify_enabled INTEGER NOT NULL DEFAULT 1"); err != nil {
		log.Printf("  提示: notify_enabled 列可能已存在: %v", err)
	}

	if _, err := db.Exec("ALTER TABLE community_posts ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: deleted 列可能已存在: %v", err)
	}

	log.Println("正在检查/迁移数据库表结构...")
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: email_verified 列可能已存在: %v", err)
	}
	// 将已有邮箱的老用户标记为已验证（非 temp_ 开头的邮箱即视为真实邮箱）
	db.Exec("UPDATE users SET email_verified = 1 WHERE email NOT LIKE 'temp_%' AND email_verified = 0")
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN reg_ip TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: reg_ip 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN qq_openid TEXT DEFAULT NULL"); err != nil {
		log.Printf("  提示: qq_openid 列可能已存在: %v", err)
	}
	if _, err := db.Exec("CREATE INDEX IF NOT EXISTS idx_users_qq ON users(qq_openid)"); err != nil {
		log.Printf("  提示: qq_openid 索引可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN reg_device TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: reg_device 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN display_id INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: display_id 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN welcome_enabled INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: welcome_enabled 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN welcome_text TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: welcome_text 列可能已存在: %v", err)
	}
	if _, err := db.Exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_groups_display_id ON groups(display_id)"); err != nil {
		log.Printf("  提示: display_id 索引可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN hide_email INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: hide_email 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN qq_number TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: qq_number 列可能已存在: %v", err)
	}
	if _, err := db.Exec("CREATE INDEX IF NOT EXISTS idx_users_qq_number ON users(qq_number)"); err != nil {
		log.Printf("  提示: qq_number 索引可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN hide_qq INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: hide_qq 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN require_group_consent INTEGER NOT NULL DEFAULT 1"); err != nil {
		log.Printf("  提示: require_group_consent 列可能已存在: %v", err)
	}
	// 邮箱注册但 qq_number 为空的旧用户，从邮箱前缀数字回填 QQ 号
	migrateEmailToQQ()
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN online_time_seconds INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: online_time_seconds 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN word_count INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: word_count 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE users ADD COLUMN e2e_enabled INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: e2e_enabled 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE banned_users ADD COLUMN unban_notify INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: unban_notify 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE banned_users ADD COLUMN unban_popup_message TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: unban_popup_message 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN is_revoked INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: is_revoked 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN revoked_at INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: revoked_at 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN reply_to_id INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: reply_to_id 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN reply_to_text TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: reply_to_text 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN reply_to_sender TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: reply_to_sender 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN media_type TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: media_type 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN media_url TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: media_url 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN flash_duration INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: flash_duration 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN broadcast_task_id INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: broadcast_task_id 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN msg_type TEXT DEFAULT 'text'"); err != nil {
		// msg_type 可能已存在（系统消息使用），忽略错误
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN contact_source TEXT DEFAULT ''"); err != nil {
		// contact_source 可能已存在（陌生人首条消息来源：search / group:<群名>），忽略错误
	}
	// 媒体消息原始尺寸（用于客户端占位符正确比例，避免正方形骨架）
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN media_width INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: media_width 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN media_height INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: media_height 列可能已存在: %v", err)
	}
	// 客户端本机毫秒发送时间（发送瞬间 System.currentTimeMillis()，用于消息信息面板耗时计算）
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN client_send_at INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: client_send_at 列可能已存在: %v", err)
	}
	// 服务器毫秒级接收时间（落库瞬间 time.Now().UnixMilli()，用于消息信息面板服务器接收时间展示真实毫秒）
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN server_received_ms INTEGER DEFAULT 0"); err != nil {
		log.Printf("  提示: server_received_ms 列可能已存在: %v", err)
	}
	// @ 提及的目标映射（JSON：{"发送时的昵称": 用户ID}）。
	// 只存昵称文本的话，用户改名后历史消息里的 @ 就匹配不到人（客户端变灰、提示找不到用户）。
	if _, err := db.Exec("ALTER TABLE messages ADD COLUMN mentions TEXT DEFAULT ''"); err != nil {
		log.Printf("  提示: mentions 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN not_searchable INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: not_searchable 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN join_required INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: join_required 列可能已存在: %v", err)
	}
	// 群内发言频率：-1=未开启（不限制），0=全员禁言，N>0=每位成员每分钟最多 N 条
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN msg_rate_limit INTEGER NOT NULL DEFAULT -1"); err != nil {
		log.Printf("  提示: msg_rate_limit 列可能已存在: %v", err)
	}
	// 发言频率的作用范围：0=群主及管理员不受限（默认），1=管理员受限（群主仍不受限），2=全部受限
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN msg_rate_scope INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: msg_rate_scope 列可能已存在: %v", err)
	}

	if _, err := db.Exec(`
	CREATE TABLE IF NOT EXISTS servers (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		owner_user_id INTEGER NOT NULL UNIQUE,
		name TEXT NOT NULL UNIQUE,
		domain TEXT NOT NULL,
		password TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		server_status TEXT NOT NULL DEFAULT '',
		last_active_at INTEGER NOT NULL DEFAULT 0,
		FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
	);
	CREATE UNIQUE INDEX IF NOT EXISTS idx_servers_owner ON servers(owner_user_id);
	CREATE UNIQUE INDEX IF NOT EXISTS idx_servers_name ON servers(name);
	`); err != nil {
		log.Printf("  提示: servers 表准备: %v", err)
	}
	// 旧库补列：开关机状态与最近活跃时间。缺了这两列，开机/关机写不进状态、
	// computeServerStatus 查询直接报错返回 offline，表现就是「关机/重启按钮点了没反应」。
	if _, err := db.Exec("ALTER TABLE servers ADD COLUMN server_status TEXT NOT NULL DEFAULT ''"); err != nil {
		log.Printf("  提示: server_status 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE servers ADD COLUMN last_active_at INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: last_active_at 列可能已存在: %v", err)
	}

	if _, err := db.Exec(`
	CREATE TABLE IF NOT EXISTS server_access_logs (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		server_id INTEGER NOT NULL,
		user_id INTEGER NOT NULL DEFAULT 0,
		user_email TEXT DEFAULT '',
		accessed_at INTEGER NOT NULL,
		FOREIGN KEY (server_id) REFERENCES servers(id) ON DELETE CASCADE
	);
	CREATE INDEX IF NOT EXISTS idx_server_access_server ON server_access_logs(server_id);
	`); err != nil {
		log.Printf("  提示: server_access_logs 表准备: %v", err)
	}

	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS group_blacklist (
		group_id INTEGER NOT NULL,
		user_id INTEGER NOT NULL,
		blocked_at INTEGER NOT NULL,
		blocked_by INTEGER NOT NULL,
		PRIMARY KEY(group_id, user_id)
	)`); err != nil {
		log.Printf("  提示: group_blacklist 表可能已存在: %v", err)
	}

	// 用户级黑名单（拉黑单个用户）。黑名单为对称关系：A 拉黑 B 写入 (blocker_id=A, blocked_id=B)，
	// A 的黑名单列表看到 B，B 的黑名单列表也看到 A（B 是被拉黑方）；任一方「拉出」都删除同一行。
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS user_blacklist (
		blocker_id INTEGER NOT NULL,
		blocked_id INTEGER NOT NULL,
		blocked_at INTEGER NOT NULL,
		PRIMARY KEY(blocker_id, blocked_id)
	)`); err != nil {
		log.Printf("  提示: user_blacklist 表可能已存在: %v", err)
	}

	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS license_keys (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		key_str TEXT NOT NULL UNIQUE,
		duration_hours INTEGER NOT NULL,
		created_at INTEGER NOT NULL,
		activated_at INTEGER DEFAULT 0,
		expires_at INTEGER DEFAULT 0,
		used_by_user_id INTEGER DEFAULT 0,
		is_active INTEGER NOT NULL DEFAULT 1
	)`); err != nil {
		log.Printf("  提示: license_keys 表可能已存在: %v", err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS app_settings (
		setting_key TEXT PRIMARY KEY,
		setting_value TEXT NOT NULL DEFAULT ''
	)`); err != nil {
		log.Printf("  提示: app_settings 表可能已存在: %v", err)
	}
	db.Exec("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('trial_mode_enabled', '0')")
	db.Exec("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('trial_min_supported_version', '')")
	db.Exec("INSERT OR IGNORE INTO app_settings (setting_key, setting_value) VALUES ('trial_force_lock_version', '')")
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN duration_minutes INTEGER DEFAULT 0`); err != nil {
		log.Printf("  [体验版] 添加 duration_minutes 列失败: %v", err)
	}
	if _, err := db.Exec(`UPDATE license_keys SET duration_minutes = duration_hours * 60 WHERE duration_minutes = 0 AND duration_hours > 0`); err != nil {
		log.Printf("  [体验版] 迁移 duration_minutes 数据失败: %v", err)
	}
	// 卡密体系扩展：区分「体验卡密(trial)」与「奖励卡密(reward)」，奖励卡密支持个人/公共与 Token/会员
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN category TEXT DEFAULT 'trial'`); err != nil {
		log.Printf("  [卡密] 添加 category 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN card_type TEXT DEFAULT ''`); err != nil {
		log.Printf("  [卡密] 添加 card_type 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN reward_type TEXT DEFAULT ''`); err != nil {
		log.Printf("  [卡密] 添加 reward_type 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN token_amount BIGINT DEFAULT 0`); err != nil {
		log.Printf("  [卡密] 添加 token_amount 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN bind_user_id BIGINT DEFAULT 0`); err != nil {
		log.Printf("  [卡密] 添加 bind_user_id 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN reward_applied INTEGER NOT NULL DEFAULT 0`); err != nil {
		log.Printf("  [卡密] 添加 reward_applied 列失败(可忽略): %v", err)
	}
	// 历史数据默认归为体验卡密
	db.Exec("UPDATE license_keys SET category = 'trial' WHERE category IS NULL OR category = ''")
	// 站点卡密：绑定域名，用于外部 APP 激活文件管理功能
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN domain TEXT DEFAULT ''`); err != nil {
		log.Printf("  [卡密] 添加 domain 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN password TEXT DEFAULT ''`); err != nil {
		log.Printf("  [卡密] 添加 password 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN permission_level TEXT DEFAULT 'B'`); err != nil {
		log.Printf("  [卡密] 添加 permission_level 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN danger_quota INTEGER DEFAULT 0`); err != nil {
		log.Printf("  [卡密] 添加 danger_quota 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN danger_used INTEGER DEFAULT 0`); err != nil {
		log.Printf("  [卡密] 添加 danger_used 列失败(可忽略): %v", err)
	}
	// 站点卡密备份额度：该卡可同时保留的最大备份数量（备份文件存在服务器独立目录，不在站点目录内）
	if _, err := db.Exec(`ALTER TABLE license_keys ADD COLUMN backup_balance INTEGER NOT NULL DEFAULT 0`); err != nil {
		log.Printf("  [卡密] 添加 backup_balance 列失败(可忽略): %v", err)
	}
	// 存量站点卡默认回填 1 个备份额度；其他类卡保持 0
	db.Exec(`UPDATE license_keys SET backup_balance = 1 WHERE backup_balance = 0 AND category = 'site'`)

	// 免费 API 提交（用户贡献，开发者审核）：pending / approved / rejected
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS free_apis (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		submitter_id BIGINT NOT NULL DEFAULT 0,
		api_key TEXT DEFAULT '',
		base_url TEXT DEFAULT '',
		model_name TEXT DEFAULT '',
		status TEXT DEFAULT 'pending',
		created_at BIGINT NOT NULL DEFAULT 0,
		reviewed_at BIGINT NOT NULL DEFAULT 0,
		reviewer_id BIGINT NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  [免费API] 创建 free_apis 表失败(可忽略): %v", err)
	}

	// Token 余额扩展（会员体系已移除）
	if _, err := db.Exec(`ALTER TABLE users ADD COLUMN token_balance BIGINT NOT NULL DEFAULT 0`); err != nil {
		log.Printf("  [会员] token_balance 列可能已存在: %v", err)
	}
	// 拆分 Token 余额：永久池 token_balance_perm（会员/充值/卡密兑换，不清零）；
	// token_balance 为对外展示的有效余额（含各项奖励，直接计入此处，不清零）；
	// token_balance_free 为预留的每月免费池（当前不再写入此列）。
	if _, err := db.Exec(`ALTER TABLE users ADD COLUMN token_balance_perm BIGINT NOT NULL DEFAULT 0`); err != nil {
		log.Printf("  [会员] token_balance_perm 列可能已存在: %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE users ADD COLUMN token_balance_free BIGINT NOT NULL DEFAULT 0`); err != nil {
		log.Printf("  [会员] token_balance_free 列可能已存在: %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE users ADD COLUMN free_reset_month TEXT`); err != nil {
		log.Printf("  [会员] free_reset_month 列可能已存在: %v", err)
	}
	// 一次性回填：将旧 token_balance 全部视为「永久池」，并记录当前月份（仅对尚未迁移的用户执行，幂等）
	if _, err := db.Exec(`UPDATE users SET token_balance_perm = token_balance, free_reset_month = strftime('%Y-%m') WHERE COALESCE(free_reset_month,'') = ''`); err != nil {
		log.Printf("  [会员] 回填 token_balance_perm 失败(可忽略): %v", err)
	}

	// 创建会员订单表（用户扫码支付后提交订单，管理员审核通过后开通会员）
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS member_orders (
		id            INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id       INTEGER NOT NULL,
		username      TEXT NOT NULL DEFAULT '',
		level         INTEGER NOT NULL,
		duration_days INTEGER NOT NULL,
		amount        REAL NOT NULL DEFAULT 0,
		order_no      TEXT NOT NULL DEFAULT '',
		status        TEXT NOT NULL DEFAULT 'pending',
		created_at    INTEGER NOT NULL,
		processed_at  INTEGER NOT NULL DEFAULT 0,
		note          TEXT NOT NULL DEFAULT ''
	)`); err != nil {
		log.Printf("  [订单] member_orders 表创建失败: %v", err)
	} else {
		log.Println("  [订单] member_orders 表已确认存在")
	}
	if _, err := db.Exec(`CREATE INDEX IF NOT EXISTS idx_member_orders_status ON member_orders(status)`); err != nil {
		log.Printf("  [订单] member_orders 索引创建失败: %v", err)
	}
	// 订单扩展：支持「充值」类型订单（用户购买 Token），与会员订单共用一张表
	if _, err := db.Exec(`ALTER TABLE member_orders ADD COLUMN type TEXT NOT NULL DEFAULT 'member'`); err != nil {
		log.Printf("  [订单] 添加 type 列失败(可忽略): %v", err)
	}
	if _, err := db.Exec(`ALTER TABLE member_orders ADD COLUMN tokens BIGINT NOT NULL DEFAULT 0`); err != nil {
		log.Printf("  [订单] 添加 tokens 列失败(可忽略): %v", err)
	}

	// 创建禁言表
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS muted_users (
		user_id INTEGER PRIMARY KEY,
		muted_by INTEGER NOT NULL,
		muted_at INTEGER NOT NULL,
		expires_at INTEGER NOT NULL,
		mute_type INTEGER NOT NULL DEFAULT 3
	)`); err != nil {
		log.Printf("  提示: muted_users 表创建失败: %v", err)
	} else {
		log.Println("  [禁言] muted_users 表已确认存在")
	}

	// 创建平台管理员表
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS platform_admins (
		user_id INTEGER PRIMARY KEY,
		granted_by INTEGER NOT NULL,
		granted_at INTEGER NOT NULL,
		FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
		FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE CASCADE
	)`); err != nil {
		log.Printf("  提示: platform_admins 表创建失败: %v", err)
	} else {
		log.Println("  [平台管理员] platform_admins 表已确认存在")
	}

	// 创建细粒度权限表
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS user_permissions (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		perm_key TEXT NOT NULL,
		UNIQUE(user_id, perm_key)
	)`); err != nil {
		log.Printf("  提示: user_permissions 表创建失败: %v", err)
	} else {
		log.Println("  [权限] user_permissions 表已确认存在")
	}

	// 创建管理员操作日志表
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS admin_operation_logs (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		admin_user_id INTEGER NOT NULL,
		admin_username TEXT NOT NULL DEFAULT '',
		operation_type TEXT NOT NULL,
		target_user_id INTEGER NOT NULL DEFAULT 0,
		target_username TEXT NOT NULL DEFAULT '',
		details TEXT NOT NULL DEFAULT '{}',
		created_at INTEGER NOT NULL
	)`); err != nil {
		log.Printf("  提示: admin_operation_logs 表创建失败: %v", err)
	} else {
		log.Println("  [操作日志] admin_operation_logs 表已确认存在")
	}

	// 创建闪照查看记录表（防清缓存重看）
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS flash_views (
		user_id INTEGER NOT NULL,
		message_id INTEGER NOT NULL,
		viewed_at INTEGER NOT NULL,
		PRIMARY KEY (user_id, message_id)
	)`); err != nil {
		log.Printf("  提示: flash_views 表创建失败: %v", err)
	} else {
		log.Println("  [闪照] flash_views 表已确认存在")
	}

	// 创建用户位置记录表（自动上报+手动分享）
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS user_locations (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		lat REAL NOT NULL DEFAULT 0,
		lng REAL NOT NULL DEFAULT 0,
		address TEXT NOT NULL DEFAULT '',
		created_at INTEGER NOT NULL
	)`); err != nil {
		log.Printf("  提示: user_locations 表创建失败: %v", err)
	} else {
		log.Println("  [位置] user_locations 表已确认存在")
	}
	// 索引
	db.Exec("CREATE INDEX IF NOT EXISTS idx_user_locations_user ON user_locations(user_id, created_at)")

	// 升级旧表：添加 detect_screenshot / detect_recording 列（已有则忽略错误）
	db.Exec("ALTER TABLE privacy_settings ADD COLUMN detect_screenshot INTEGER NOT NULL DEFAULT 0")
	db.Exec("ALTER TABLE privacy_settings ADD COLUMN detect_recording INTEGER NOT NULL DEFAULT 0")
	// 拍一拍：记录被拍成员名。群聊场景下 to_user_id 为群会话 ID（负数），JOIN users 取不到成员名，
	// 必须把被拍成员名落库，群成员才能在会话内/列表看到「X拍了拍Y」。
	db.Exec("ALTER TABLE messages ADD COLUMN to_user_name TEXT")

	// 创建公告表
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS announcements (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		enabled INTEGER NOT NULL DEFAULT 0,
		title TEXT NOT NULL DEFAULT '',
		content TEXT NOT NULL DEFAULT '',
		source TEXT NOT NULL DEFAULT '拾光工作室',
		date TEXT NOT NULL DEFAULT '',
		supplement TEXT NOT NULL DEFAULT '',
		updated_at INTEGER NOT NULL
	)`); err != nil {
		log.Printf("  创建 announcements 表失败: %v", err)
	} else {
		log.Println("  [公告] announcements 表已确认存在")
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS privacy_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    friend_id INTEGER NOT NULL,
    master_on INTEGER NOT NULL DEFAULT 0,
    no_exit INTEGER NOT NULL DEFAULT 0,
    no_screenshot INTEGER NOT NULL DEFAULT 0,
    no_recording INTEGER NOT NULL DEFAULT 0,
    detect_screenshot INTEGER NOT NULL DEFAULT 0,
    detect_recording INTEGER NOT NULL DEFAULT 0,
    UNIQUE(user_id, friend_id)
)`); err != nil {
		log.Printf("  创建 privacy_settings 表失败: %v", err)
	} else {
		log.Println("  [隐私] privacy_settings 表已确认存在")
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS privacy_lock (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		friend_id INTEGER NOT NULL,
		locked INTEGER NOT NULL DEFAULT 0,
		UNIQUE(user_id, friend_id)
	)`); err != nil {
		log.Printf("  创建 privacy_lock 表失败: %v", err)
	} else {
		log.Println("  [隐私] privacy_lock 表已确认存在")
	}
	// 用户级隐私开关（与具体好友无关）：
	//   block_stranger_msg — 非好友禁止向我发私聊消息
	//
	// 单独建表而不是往 users 表加列：users 的 SELECT 列表在十几处是写死的，
	// 加一列要同步改所有 Scan，容易漏；这里只影响「发消息」时的一次查询。
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS user_privacy (
		user_id INTEGER PRIMARY KEY,
		block_stranger_msg INTEGER NOT NULL DEFAULT 0
	)`); err != nil {
		log.Printf("  创建 user_privacy 表失败: %v", err)
	} else {
		log.Println("  [隐私] user_privacy 表已确认存在")
	}
	if _, err := db.Exec(`ALTER TABLE announcements ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0`); err != nil {
		log.Printf("  [公告] 添加 created_at 列失败: %v", err)
	}

	var annCount int
	db.QueryRow("SELECT COUNT(*) FROM announcements").Scan(&annCount)
	if annCount == 0 {
		now := time.Now().Unix()
		today := time.Now().Format("2006-01-02")
		db.Exec("INSERT INTO announcements (enabled, title, content, source, date, supplement, updated_at, created_at) VALUES (0, '', '', '拾光工作室', ?, '', ?, ?)", today, now, now)
	}

	ensurePluginTables()
	ensureOpenAppsTable()
	ensureAgentAppVersionTable()
	ensureOpenAuthTables()
	ensureOfficialGroup()

	// 创建群内禁言表
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS group_mutes (
		group_id INTEGER NOT NULL,
		user_id INTEGER NOT NULL,
		muted_by INTEGER NOT NULL,
		muted_at INTEGER NOT NULL,
		expires_at INTEGER NOT NULL,
		PRIMARY KEY (group_id, user_id)
	)`); err != nil {
		log.Printf("  提示: group_mutes 表创建失败: %v", err)
	} else {
		log.Println("  [群禁言] group_mutes 表已确认存在")
	}
	if _, err := db.Exec("CREATE INDEX IF NOT EXISTS idx_group_mutes_user ON group_mutes(user_id)"); err != nil {
		log.Printf("  提示: group_mutes 索引创建失败: %v", err)
	}

	// 群通知方式设置表（群身份 / 系统广播 + 逐项开关）
	ensureGroupNotifyTable()

	// 群敏感词（词表 + 处置方式 + 提示模板）
	ensureGroupSensitiveTables()

	// 群管理员细粒度权限表（user_id = 0 为群级默认模板）
	ensureGroupAdminPermsTable()

	log.Println("数据库初始化完成")
}

// UserPushConfig 用户的邮箱推送配置
type UserPushConfig struct {
	UserID        int64  `json:"user_id"`
	Method        int    `json:"method"`        // 1=自己的邮箱，2=官方邮箱
	TemplateMode  int    `json:"template_mode"` // 1=提示模板，2=真实消息
	OwnEmail      string `json:"own_email"`
	OwnAuth       string `json:"own_auth"`
	DndIDs        string `json:"dnd_ids"`        // 免打扰好友ID的 JSON 数组
	NotifyEnabled int    `json:"notify_enabled"` // 总通知开关：0=关闭推送，1=开启（默认）
}

// saveUserPushConfig 保存用户的邮箱推送配置
func saveUserPushConfig(cfg *UserPushConfig) error {
	_, err := db.Exec(`
		INSERT INTO user_push_config (user_id, method, template_mode, own_email, own_auth, dnd_ids, notify_enabled, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(user_id) DO UPDATE SET
			method = excluded.method,
			template_mode = excluded.template_mode,
			own_email = excluded.own_email,
			own_auth = excluded.own_auth,
			dnd_ids = excluded.dnd_ids,
			notify_enabled = excluded.notify_enabled,
			updated_at = excluded.updated_at`,
		cfg.UserID, cfg.Method, cfg.TemplateMode, cfg.OwnEmail, cfg.OwnAuth, cfg.DndIDs, cfg.NotifyEnabled, time.Now().Unix())
	return err
}

// getUserPushConfig 读取用户的邮箱推送配置，不存在则返回 nil
func getUserPushConfig(userID int64) (*UserPushConfig, error) {
	cfg := &UserPushConfig{}
	err := db.QueryRow(`
		SELECT user_id, method, template_mode, COALESCE(own_email,''), COALESCE(own_auth,''), COALESCE(dnd_ids,'[]'), COALESCE(notify_enabled,1)
		FROM user_push_config WHERE user_id = ?`, userID).
		Scan(&cfg.UserID, &cfg.Method, &cfg.TemplateMode, &cfg.OwnEmail, &cfg.OwnAuth, &cfg.DndIDs, &cfg.NotifyEnabled)
	if err == sql.ErrNoRows {
		// 用户从未配置过推送，返回默认配置：官方推送开启，纯文本模式，无免打扰，通知开启
		// 这样即使没手动配置，只要邮箱合法也能推送（满足"一离线就推"的需求）
		return &UserPushConfig{
			UserID:        userID,
			Method:        0, // 0 = 官方推送
			TemplateMode:  0, // 0 = 显示正文
			OwnEmail:      "",
			OwnAuth:       "",
			DndIDs:        "[]",
			NotifyEnabled: 1,
		}, nil
	}
	if err != nil {
		return nil, err
	}
	return cfg, nil
}

// dndContainsID 判断 dnd_ids(JSON数组) 是否包含指定好友 ID
func dndContainsID(dndJSON string, friendID int64) bool {
	if dndJSON == "" {
		return false
	}
	var ids []int64
	if err := json.Unmarshal([]byte(dndJSON), &ids); err != nil {
		return false
	}
	for _, id := range ids {
		if id == friendID {
			return true
		}
	}
	return false
}

func ensureOfficialGroup() {
	// ... (implementation preserved from original)
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM groups WHERE display_id = 1").Scan(&count)
	if err != nil {
		log.Printf("[官方群] 检查失败: %v", err)
		return
	}

	var groupID int64
	if count == 0 {
		now := time.Now().Unix()
		result, err := db.Exec(
			"INSERT INTO groups (name, signature, announcement, creator_id, display_id, welcome_enabled, welcome_text, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
			"Aurora Chat官方群", "Aurora Chat 官方交流群，欢迎加入", "请遵守法律法规，文明交流。", int64(1), int64(1), 0, "", now, now,
		)
		if err != nil {
			log.Printf("[官方群] 创建失败: %v", err)
			return
		}
		groupID, err = result.LastInsertId()
		if err != nil {
			log.Printf("[官方群] 获取ID失败: %v", err)
			return
		}
		log.Printf("[官方群] 创建成功: 群ID=%d", groupID)

		convID := -(1000 + groupID)
		migrateOld, _ := db.Exec("UPDATE messages SET to_user_id = ? WHERE to_user_id = -1 AND from_user_id != -1", convID)
		if n, _ := migrateOld.RowsAffected(); n > 0 {
			log.Printf("[官方群] 已迁移 %d 条旧消息", n)
		}
		migrateSys, _ := db.Exec("UPDATE messages SET from_user_id = ?, to_user_id = ? WHERE from_user_id = -1 AND to_user_id = -1", convID, convID)
		if n, _ := migrateSys.RowsAffected(); n > 0 {
			log.Printf("[官方群] 已迁移 %d 条系统消息", n)
		}
	} else {
		var existingName string
		err = db.QueryRow("SELECT id, name FROM groups WHERE display_id = 1").Scan(&groupID, &existingName)
		if err != nil {
			log.Printf("[官方群] 查询ID失败: %v", err)
			return
		}
		// 历史数据兜底：官方群名字若为空（早期版本建群未设或后续被清空），补全，
		// 否则会话列表/对话页标题会因 conv.username 为空而不显示群名、回退成"聊天"。
		if existingName == "" {
			if _, e := db.Exec("UPDATE groups SET name = ? WHERE id = ?", "Aurora Chat官方群", groupID); e == nil {
				log.Printf("[官方群] 已补全空群名 (groupID=%d)", groupID)
			}
		}
	}

	rows, err := db.Query("SELECT id FROM users")
	if err != nil {
		log.Printf("[官方群] 查询用户失败: %v", err)
		return
	}
	defer rows.Close()
	now := time.Now().Unix()
	added := 0
	for rows.Next() {
		var uid int64
		rows.Scan(&uid)
		_, err := db.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)", groupID, uid, now)
		if err == nil {
			added++
		}
	}
	log.Printf("[官方群] 已同步 %d 个用户到官方群", added)

	avatarDir := getAvatarDir()
	avatarPath := filepath.Join(avatarDir, fmt.Sprintf("group_%d.png", groupID))
	if _, err := os.Stat(avatarPath); os.IsNotExist(err) {
		createDefaultGroupAvatar(avatarPath)
	}
}

func createDefaultGroupAvatar(path string) {
	os.MkdirAll(filepath.Dir(path), 0755)
	sourcePath := filepath.Join(filepath.Dir(path), "group_1_default.jpg")
	if _, err := os.Stat(sourcePath); err == nil {
		if srcData, err := os.ReadFile(sourcePath); err == nil {
			os.WriteFile(path, srcData, 0644)
			log.Printf("[官方群] 默认头像已使用自定义图片: %s", sourcePath)
			return
		}
	}
	log.Printf("[官方群] 未找到自定义头像 %s，跳过", sourcePath)
}

// ==================== User ====================

type User struct {
	ID                  int64  `json:"id"`
	Email               string `json:"email"`
	Username            string `json:"username"`
	Password            string `json:"-"`
	CreatedAt           int64  `json:"created_at"`
	UpdatedAt           int64  `json:"updated_at"`
	HideEmail           int    `json:"hide_email"`
	HideQQ              int    `json:"hide_qq"`
	RequireGroupConsent int    `json:"require_group_consent"`
	QQNumber            string `json:"qq_number"`
	Signature           string `json:"signature"`
	OnlineTimeSeconds   int64  `json:"online_time_seconds"`
	WordCount           int64  `json:"word_count"`
	TokenVersion        int64  `json:"token_version"`
	EmailVerified       int    `json:"email_verified"`
	RegIp               string `json:"reg_ip"`
	TokenBalance        int64  `json:"token_balance"`
}

// notDeletedCond 用于在所有用户查询中排除“已注销”残留账号。
// 旧版后端的注销采用软删除：username 改为“注销用户”、email 改为 deleted_{id}@deleted.aurora.chat，
// 但其 qq_number 等字段仍被保留，导致该 QQ/邮箱被“霸占”无法复用（重新绑定时报“已被其他账号绑定”）。
// 统一在查找时排除这类账号，避免复用冲突。
const notDeletedCond = "username != '注销用户' AND (email IS NULL OR email NOT LIKE 'deleted_%@deleted.aurora.chat')"

// isAccountDeleted 判断一个账号是否处于“已注销”状态（软删除残留）。
func isAccountDeleted(u *User) bool {
	if u == nil {
		return false
	}
	if u.Username == "注销用户" {
		return true
	}
	if strings.HasPrefix(u.Email, "deleted_") && strings.HasSuffix(u.Email, "@deleted.aurora.chat") {
		return true
	}
	return false
}

func isEmailRegistered(email string) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM users WHERE email = ? AND "+notDeletedCond, email).Scan(&count)
	return count > 0, err
}

func isEmailBlocked(email string) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM blocked_emails WHERE email = ?", email).Scan(&count)
	return count > 0, err
}

func blockEmail(email string, blockedBy int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO blocked_emails (email, blocked_at, blocked_by) VALUES (?, ?, ?)",
		email, time.Now().Unix(), blockedBy)
	return err
}

func isIPBlocked(ip string) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM blocked_ips WHERE ip = ?", ip).Scan(&count)
	return count > 0, err
}

func blockIP(ip string, blockedBy int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO blocked_ips (ip, blocked_at, blocked_by) VALUES (?, ?, ?)",
		ip, time.Now().Unix(), blockedBy)
	return err
}

func findUserByEmail(email string) (*User, error) {
	user := &User{}
	err := db.QueryRow(
		"SELECT id, email, username, password, created_at, updated_at, hide_email, hide_qq, require_group_consent, qq_number, signature, online_time_seconds, word_count, token_version, email_verified, token_balance FROM users WHERE email = ? AND "+notDeletedCond,
		email,
	).Scan(&user.ID, &user.Email, &user.Username, &user.Password, &user.CreatedAt, &user.UpdatedAt, &user.HideEmail, &user.HideQQ, &user.RequireGroupConsent, &user.QQNumber, &user.Signature, &user.OnlineTimeSeconds, &user.WordCount, &user.TokenVersion, &user.EmailVerified, &user.TokenBalance)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return user, nil
}

func findUserByID(id int64) (*User, error) {
	user := &User{}
	err := db.QueryRow(
		"SELECT id, email, username, password, created_at, updated_at, hide_email, hide_qq, require_group_consent, qq_number, signature, online_time_seconds, word_count, token_version, email_verified, token_balance FROM users WHERE id = ?",
		id,
	).Scan(&user.ID, &user.Email, &user.Username, &user.Password, &user.CreatedAt, &user.UpdatedAt, &user.HideEmail, &user.HideQQ, &user.RequireGroupConsent, &user.QQNumber, &user.Signature, &user.OnlineTimeSeconds, &user.WordCount, &user.TokenVersion, &user.EmailVerified, &user.TokenBalance)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return user, nil
}

func findUserByUsername(username string) (*User, error) {
	user := &User{}
	err := db.QueryRow(
		"SELECT id, email, username, password, created_at, updated_at, hide_email, hide_qq, require_group_consent, qq_number, signature, online_time_seconds, word_count, token_version, email_verified, token_balance FROM users WHERE username = ? AND "+notDeletedCond,
		username,
	).Scan(&user.ID, &user.Email, &user.Username, &user.Password, &user.CreatedAt, &user.UpdatedAt, &user.HideEmail, &user.HideQQ, &user.RequireGroupConsent, &user.QQNumber, &user.Signature, &user.OnlineTimeSeconds, &user.WordCount, &user.TokenVersion, &user.EmailVerified, &user.TokenBalance)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return user, nil
}

func findUserByQQNumber(qq string) (*User, error) {
	user := &User{}
	err := db.QueryRow(
		"SELECT id, email, username, password, created_at, updated_at, hide_email, hide_qq, require_group_consent, qq_number, signature, online_time_seconds, word_count, token_version, email_verified, token_balance FROM users WHERE qq_number = ? AND "+notDeletedCond,
		qq,
	).Scan(&user.ID, &user.Email, &user.Username, &user.Password, &user.CreatedAt, &user.UpdatedAt, &user.HideEmail, &user.HideQQ, &user.RequireGroupConsent, &user.QQNumber, &user.Signature, &user.OnlineTimeSeconds, &user.WordCount, &user.TokenVersion, &user.EmailVerified, &user.TokenBalance)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return user, nil
}

func isEmailVerified(userID int64) bool {
	var ev int
	err := db.QueryRow("SELECT email_verified FROM users WHERE id = ?", userID).Scan(&ev)
	return err == nil && ev == 1
}

func setEmailVerified(userID int64) error {
	_, err := db.Exec("UPDATE users SET email_verified = 1, updated_at = ? WHERE id = ?", time.Now().Unix(), userID)
	return err
}

func updateUserEmail(userID int64, email string) error {
	_, err := db.Exec("UPDATE users SET email = ?, email_verified = 1, updated_at = ? WHERE id = ?", email, time.Now().Unix(), userID)
	return err
}

func updateUserStats(userID int64, onlineTime int64, wordCount int64) error {
	var curOnline int64
	var curWords int64
	err := db.QueryRow("SELECT online_time_seconds, word_count FROM users WHERE id = ?", userID).Scan(&curOnline, &curWords)
	if err != nil {
		return err
	}
	if onlineTime < curOnline {
		onlineTime = curOnline
	}
	if wordCount < curWords {
		wordCount = curWords
	}
	_, err = db.Exec("UPDATE users SET online_time_seconds = ?, word_count = ?, updated_at = ? WHERE id = ?", onlineTime, wordCount, time.Now().Unix(), userID)
	return err
}

// recordLogin 记录一条登录日志到 login_logs 表
func recordLogin(userID int64, ip string) {
	_, err := db.Exec("INSERT INTO login_logs (user_id, login_time, login_ip) VALUES (?, ?, ?)",
		userID, time.Now().Unix(), ip)
	if err != nil {
		log.Printf("[login_log] 写入登录日志失败: userID=%d, err=%v", userID, err)
	}
}

// getTodayActiveUsers 返回今日去重登录用户数、总注册用户数、活跃率
func getTodayActiveUsers() (int64, int64, float64, error) {
	todayStart := startOfDay(time.Now())
	todayEnd := todayStart.Add(24 * time.Hour).Unix()

	var activeCount int64
	err := db.QueryRow(
		"SELECT COUNT(DISTINCT user_id) FROM login_logs WHERE login_time >= ? AND login_time < ?",
		todayStart.Unix(), todayEnd,
	).Scan(&activeCount)
	if err != nil {
		return 0, 0, 0, err
	}

	var totalUsers int64
	db.QueryRow("SELECT COUNT(*) FROM users WHERE username != '注销用户'").Scan(&totalUsers)

	var rate float64
	if totalUsers > 0 {
		rate = float64(activeCount) / float64(totalUsers)
	}
	return activeCount, totalUsers, rate, nil
}

// getTodayLoginCount 返回今日登录总次数（不去重）、去重用户数
func getTodayLoginCount() (int64, int64, error) {
	todayStart := startOfDay(time.Now())
	todayEnd := todayStart.Add(24 * time.Hour).Unix()

	var totalCount int64
	err := db.QueryRow(
		"SELECT COUNT(*) FROM login_logs WHERE login_time >= ? AND login_time < ?",
		todayStart.Unix(), todayEnd,
	).Scan(&totalCount)
	if err != nil {
		return 0, 0, err
	}

	var uniqueUsers int64
	db.QueryRow(
		"SELECT COUNT(DISTINCT user_id) FROM login_logs WHERE login_time >= ? AND login_time < ?",
		todayStart.Unix(), todayEnd,
	).Scan(&uniqueUsers)

	return totalCount, uniqueUsers, nil
}

// TodayActiveUserRow 今日活跃用户列表行
type TodayActiveUserRow struct {
	UserID        int64  `json:"user_id"`
	Username      string `json:"username"`
	LoginCount    int64  `json:"login_count"`
	LastLoginTime int64  `json:"last_login_time"`
	LastLoginIP   string `json:"last_login_ip"`
}

// getTodayActiveUsersList 返回今日活跃用户列表（去重，每个用户一行，含登录次数和最后登录时间）
func getTodayActiveUsersList() ([]TodayActiveUserRow, error) {
	todayStart := startOfDay(time.Now())
	todayEnd := todayStart.Add(24 * time.Hour).Unix()

	rows, err := db.Query(`
		SELECT ll.user_id, u.username,
		       COUNT(*) AS login_count,
		       MAX(ll.login_time) AS last_login_time
		FROM login_logs ll
		JOIN users u ON u.id = ll.user_id
		WHERE ll.login_time >= ? AND ll.login_time < ?
		  AND u.username != '注销用户'
		GROUP BY ll.user_id
		ORDER BY last_login_time DESC
	`, todayStart.Unix(), todayEnd)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var result []TodayActiveUserRow
	for rows.Next() {
		var r TodayActiveUserRow
		if err := rows.Scan(&r.UserID, &r.Username, &r.LoginCount, &r.LastLoginTime); err != nil {
			return nil, err
		}
		// 查最后登录 IP
		db.QueryRow("SELECT login_ip FROM login_logs WHERE user_id = ? AND login_time = ? LIMIT 1",
			r.UserID, r.LastLoginTime).Scan(&r.LastLoginIP)
		result = append(result, r)
	}
	return result, nil
}

// startOfDay 返回给定时间所在日的零点时间
func startOfDay(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, t.Location())
}

// getUserLoginStats 返回指定用户的登录统计详情
func getUserLoginStats(userID int64) map[string]interface{} {
	result := map[string]interface{}{
		"user_id": userID,
	}

	// 总登录次数
	var totalLogins int64
	db.QueryRow("SELECT COUNT(*) FROM login_logs WHERE user_id = ?", userID).Scan(&totalLogins)
	result["total_logins"] = totalLogins

	// 今日登录次数
	todayStart := startOfDay(time.Now())
	todayEnd := todayStart.Add(24 * time.Hour).Unix()
	var todayLogins int64
	db.QueryRow("SELECT COUNT(*) FROM login_logs WHERE user_id = ? AND login_time >= ? AND login_time < ?",
		userID, todayStart.Unix(), todayEnd).Scan(&todayLogins)
	result["today_logins"] = todayLogins

	// 最后登录时间（从 login_logs）
	var lastLoginTime int64
	var lastLoginIP string
	err := db.QueryRow("SELECT login_time, login_ip FROM login_logs WHERE user_id = ? ORDER BY login_time DESC LIMIT 1",
		userID).Scan(&lastLoginTime, &lastLoginIP)
	if err != nil {
		lastLoginTime = 0
	}
	result["last_login_time"] = lastLoginTime
	result["last_login_ip"] = lastLoginIP

	// 近 7 天登录次数
	weekAgo := time.Now().AddDate(0, 0, -7).Unix()
	var weekLogins int64
	db.QueryRow("SELECT COUNT(*) FROM login_logs WHERE user_id = ? AND login_time >= ?",
		userID, weekAgo).Scan(&weekLogins)
	result["week_logins"] = weekLogins

	// 近 30 天登录次数
	monthAgo := time.Now().AddDate(0, 0, -30).Unix()
	var monthLogins int64
	db.QueryRow("SELECT COUNT(*) FROM login_logs WHERE user_id = ? AND login_time >= ?",
		userID, monthAgo).Scan(&monthLogins)
	result["month_logins"] = monthLogins

	// 首次登录时间
	var firstLoginTime int64
	db.QueryRow("SELECT MIN(login_time) FROM login_logs WHERE user_id = ?", userID).Scan(&firstLoginTime)
	result["first_login_time"] = firstLoginTime

	// 用户基本信息（从 users 表 JOIN）
	var username, email, signature, qqNumber, regIP string
	var createdAt, updatedAt, onlineTimeSeconds, wordCount int64
	var emailVerified, tokenBalance int
	err = db.QueryRow(`SELECT username, email, signature, qq_number, reg_ip,
		created_at, updated_at, online_time_seconds, word_count,
		email_verified, token_balance
		FROM users WHERE id = ?`, userID).Scan(
		&username, &email, &signature, &qqNumber, &regIP,
		&createdAt, &updatedAt, &onlineTimeSeconds, &wordCount,
		&emailVerified, &tokenBalance)
	if err == nil {
		result["username"] = username
		result["email"] = email
		result["signature"] = signature
		result["qq_number"] = qqNumber
		result["reg_ip"] = regIP
		result["created_at"] = createdAt
		result["updated_at"] = updatedAt
		result["online_time_seconds"] = onlineTimeSeconds
		result["word_count"] = wordCount
		result["email_verified"] = emailVerified == 1
		result["token_balance"] = tokenBalance
	}

	// 近 7 天每日登录趋势（[{date, count}, ...]）
	type dayCount struct {
		Date  string `json:"date"`
		Count int    `json:"count"`
	}
	var weekTrend []dayCount
	for i := 6; i >= 0; i-- {
		day := time.Now().AddDate(0, 0, -i)
		dayStart := startOfDay(day)
		dayEnd := dayStart.Add(24 * time.Hour).Unix()
		var cnt int
		db.QueryRow("SELECT COUNT(*) FROM login_logs WHERE user_id = ? AND login_time >= ? AND login_time < ?",
			userID, dayStart.Unix(), dayEnd).Scan(&cnt)
		weekTrend = append(weekTrend, dayCount{
			Date:  dayStart.Format("01-02"),
			Count: cnt,
		})
	}
	result["week_trend"] = weekTrend

	// 最近 10 条登录记录
	type loginRecord struct {
		LoginTime int64  `json:"login_time"`
		LoginIP   string `json:"login_ip"`
	}
	rows, err := db.Query("SELECT login_time, login_ip FROM login_logs WHERE user_id = ? ORDER BY login_time DESC LIMIT 10", userID)
	var recentLogins []loginRecord
	if err == nil {
		defer rows.Close()
		for rows.Next() {
			var lr loginRecord
			rows.Scan(&lr.LoginTime, &lr.LoginIP)
			recentLogins = append(recentLogins, lr)
		}
	}
	result["recent_logins"] = recentLogins

	return result
}

// getUserIDByNameOrID 根据用户名或 ID 查找用户
func getUserIDByNameOrID(query string) (int64, error) {
	var id int64
	// 先尝试纯数字 ID
	if _, err := fmt.Sscanf(query, "%d", &id); err == nil && id > 0 {
		return id, nil
	}
	// 按用户名找
	err := db.QueryRow("SELECT id FROM users WHERE username = ?", query).Scan(&id)
	return id, err
}

// migrateEmailToQQ 为邮箱注册但 qq_number 为空的旧用户，从邮箱前缀数字回填 QQ 号
func migrateEmailToQQ() {
	re := regexp.MustCompile(`^(\d{5,11})`)
	rows, err := db.Query("SELECT id, email FROM users WHERE (qq_number IS NULL OR qq_number = '') AND email LIKE '%@%'")
	if err != nil {
		log.Printf("  提示: 读取待迁移用户失败: %v", err)
		return
	}
	defer rows.Close()
	type upd struct {
		id int64
		qq string
	}
	var updates []upd
	for rows.Next() {
		var id int64
		var email string
		if err := rows.Scan(&id, &email); err != nil {
			continue
		}
		local := email
		if at := strings.Index(email, "@"); at > 0 {
			local = email[:at]
		}
		if m := re.FindStringSubmatch(local); m != nil {
			updates = append(updates, upd{id, m[1]})
		}
	}
	for _, u := range updates {
		if _, err := db.Exec("UPDATE users SET qq_number = ? WHERE id = ?", u.qq, u.id); err != nil {
			log.Printf("  提示: 回填 QQ 失败 user=%d: %v", u.id, err)
		}
	}
	if len(updates) > 0 {
		log.Printf("  迁移: 已从邮箱前缀为 %d 个旧用户回填 QQ 号", len(updates))
	}
}

func createUser(email, username, hashedPassword, regIP string, emailVerified ...int) (int64, error) {
	now := time.Now().Unix()
	ev := 0
	if len(emailVerified) > 0 {
		ev = emailVerified[0]
	}
	result, err := db.Exec(
		"INSERT INTO users (email, username, password, created_at, updated_at, email_verified, reg_ip) VALUES (?, ?, ?, ?, ?, ?, ?)",
		email, username, hashedPassword, now, now, ev, regIP,
	)
	if err != nil {
		return 0, err
	}
	userID, err := result.LastInsertId()
	if err != nil {
		return 0, err
	}
	// 如果是无邮箱注册，用占位符更新 email 字段避免 UNIQUE 冲突
	if email == "" {
		db.Exec("UPDATE users SET email = ? WHERE id = ?", fmt.Sprintf("temp_%d@local", userID), userID)
	}
	// 邮箱注册时若未单独提供 QQ，尝试从邮箱前缀数字默认填充 QQ 号
	if at := strings.Index(email, "@"); at > 0 {
		local := email[:at]
		if m := regexp.MustCompile(`^(\d{5,11})`).FindStringSubmatch(local); m != nil {
			db.Exec("UPDATE users SET qq_number = ? WHERE id = ?", m[1], userID)
		}
	}
	var officialGroupID int64
	err = db.QueryRow("SELECT id FROM groups WHERE display_id = 1").Scan(&officialGroupID)
	if err == nil && officialGroupID > 0 {
		db.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)",
			officialGroupID, userID, now)
	}
	return userID, nil
}

// ==================== QQ 快捷登录 ====================

// getUserByQQOpenid 根据 QQ openid 查找已绑定用户
func getUserByQQOpenid(openid string) (*User, error) {
	var u User
	err := db.QueryRow(
		"SELECT id, email, username, password, created_at, updated_at, hide_email, hide_qq, require_group_consent, qq_number, signature, online_time_seconds, word_count, token_version, email_verified, token_balance FROM users WHERE qq_openid = ?",
		openid,
	).Scan(&u.ID, &u.Email, &u.Username, &u.Password, &u.CreatedAt, &u.UpdatedAt, &u.HideEmail, &u.HideQQ, &u.RequireGroupConsent, &u.QQNumber, &u.Signature, &u.OnlineTimeSeconds, &u.WordCount, &u.TokenVersion, &u.EmailVerified, &u.TokenBalance)
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// findOrCreateQQUser 通过 QQ openid 查找用户，不存在则创建（绑定 QQ，无需密码/邮箱）
func findOrCreateQQUser(openid, username, regIP, regDevice, qqNumber string) (int64, error) {
	if u, err := getUserByQQOpenid(openid); err == nil && u.ID > 0 {
		// 同步更新 QQ 号（用户首次登录后绑定到个人资料）
		if qqNumber != "" {
			db.Exec("UPDATE users SET qq_number = ? WHERE id = ?", qqNumber, u.ID)
		}
		return u.ID, nil
	}
	now := time.Now().Unix()
	placeholderEmail := fmt.Sprintf("qq_%s@local", openid)
	result, err := db.Exec(
		"INSERT INTO users (email, username, password, created_at, updated_at, email_verified, reg_ip, qq_openid, reg_device) VALUES (?, ?, '', ?, ?, 1, ?, ?, ?)",
		placeholderEmail, username, now, now, regIP, openid, regDevice,
	)
	if err != nil {
		return 0, err
	}
	userID, err := result.LastInsertId()
	if err != nil {
		return 0, err
	}
	db.Exec("UPDATE users SET email = ? WHERE id = ?", fmt.Sprintf("qq_%d@local", userID), userID)
	if qqNumber != "" {
		db.Exec("UPDATE users SET qq_number = ? WHERE id = ?", qqNumber, userID)
	}
	var officialGroupID int64
	if err := db.QueryRow("SELECT id FROM groups WHERE display_id = 1").Scan(&officialGroupID); err == nil && officialGroupID > 0 {
		db.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)",
			officialGroupID, userID, now)
	}
	return userID, nil
}

// countNewAccountsTodayByDevice 统计某设备最近 24 小时新建账号数（防批量注册）
func countNewAccountsTodayByDevice(device string) (int, error) {
	if device == "" {
		return 0, nil
	}
	var n int
	err := db.QueryRow("SELECT COUNT(*) FROM users WHERE reg_device = ? AND created_at > ?", device, time.Now().Unix()-86400).Scan(&n)
	return n, err
}

// MAX_ACCOUNTS_PER_DEVICE 单设备永久可注册账号上限（防批量小号）
const MAX_ACCOUNTS_PER_DEVICE = 2

// count_accounts_by_device 统计该设备历史累计注册账号数（永久，不限时间）
func count_accounts_by_device(device string) (int, error) {
	if device == "" {
		return 0, nil
	}
	var n int
	err := db.QueryRow("SELECT COUNT(*) FROM users WHERE reg_device = ?", device).Scan(&n)
	return n, err
}

func getAllUsers() ([]User, error) {
	rows, err := db.Query("SELECT id, email, username, created_at, updated_at, hide_email, hide_qq, require_group_consent, qq_number, signature, online_time_seconds, word_count, reg_ip, token_balance FROM users WHERE username != '注销用户' ORDER BY created_at DESC")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []User
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Email, &u.Username, &u.CreatedAt, &u.UpdatedAt, &u.HideEmail, &u.HideQQ, &u.RequireGroupConsent, &u.QQNumber, &u.Signature, &u.OnlineTimeSeconds, &u.WordCount, &u.RegIp, &u.TokenBalance); err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	return users, nil
}

// ==================== Verification Code ====================

func saveVerificationCode(email, code string) error {
	_, _ = db.Exec("DELETE FROM verification_codes WHERE email = ?", email)
	// 新验证码下发即重置失败计数：失败次数按「当前这条验证码」计，
	// 不能跨验证码累计，否则用户前面几次手误会影响后面新发的码。
	clearCodeFailure(email)
	_, err := db.Exec("INSERT INTO verification_codes (email, code, created_at) VALUES (?, ?, ?)", email, code, time.Now().Unix())
	return err
}

func verifyCode(email, code string) bool {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM verification_codes WHERE email = ? AND code = ? AND created_at > ?", email, code, time.Now().Unix()-300).Scan(&count)
	if err != nil || count <= 0 {
		// 防在线爆破：同一邮箱累计失败到上限即作废验证码（原来没有任何失败计数）
		recordCodeFailure(email)
		return false
	}
	clearCodeFailure(email)
	return true
}

func deleteVerificationCode(email string) {
	_, _ = db.Exec("DELETE FROM verification_codes WHERE email = ?", email)
}

// ==================== Friend Request ====================

type FriendRequest struct {
	ID           int64  `json:"id"`
	FromUserID   int64  `json:"from_user_id"`
	ToUserID     int64  `json:"to_user_id"`
	FromEmail    string `json:"from_email"`
	FromUsername string `json:"from_username"`
	ToUsername   string `json:"to_username"`
	Greeting     string `json:"greeting"`
	Status       string `json:"status"`
	CreatedAt    int64  `json:"created_at"`
}

func createFriendRequest(fromUserID, toUserID int64, fromEmail, fromUsername, greeting string) (int64, error) {
	now := time.Now().Unix()
	result, err := db.Exec("INSERT INTO friend_requests (from_user_id, to_user_id, from_email, from_username, greeting, status, created_at) VALUES (?, ?, ?, ?, ?, 'pending', ?)", fromUserID, toUserID, fromEmail, fromUsername, greeting, now)
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

func getFriendRequestsForUser(userID int64) ([]FriendRequest, error) {
	rows, err := db.Query(`SELECT fr.id, fr.from_user_id, fr.to_user_id, fr.from_email, fr.from_username, u2.username, fr.greeting, fr.status, fr.created_at
		FROM friend_requests fr
		LEFT JOIN users u2 ON fr.to_user_id = u2.id
		WHERE fr.from_user_id = ? OR fr.to_user_id = ?
		ORDER BY fr.created_at DESC`, userID, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var requests []FriendRequest
	for rows.Next() {
		var r FriendRequest
		if err := rows.Scan(&r.ID, &r.FromUserID, &r.ToUserID, &r.FromEmail, &r.FromUsername, &r.ToUsername, &r.Greeting, &r.Status, &r.CreatedAt); err != nil {
			return nil, err
		}
		requests = append(requests, r)
	}
	return requests, nil
}
