package main

import (
	cryptorand "crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
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
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN not_searchable INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: not_searchable 列可能已存在: %v", err)
	}
	if _, err := db.Exec("ALTER TABLE groups ADD COLUMN join_required INTEGER NOT NULL DEFAULT 0"); err != nil {
		log.Printf("  提示: join_required 列可能已存在: %v", err)
	}

	if _, err := db.Exec(`
	CREATE TABLE IF NOT EXISTS servers (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		owner_user_id INTEGER NOT NULL UNIQUE,
		name TEXT NOT NULL UNIQUE,
		domain TEXT NOT NULL,
		password TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
	);
	CREATE UNIQUE INDEX IF NOT EXISTS idx_servers_owner ON servers(owner_user_id);
	CREATE UNIQUE INDEX IF NOT EXISTS idx_servers_name ON servers(name);
	`); err != nil {
		log.Printf("  提示: servers 表准备: %v", err)
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

	log.Println("数据库初始化完成")
}

// UserPushConfig 用户的邮箱推送配置
type UserPushConfig struct {
	UserID       int64  `json:"user_id"`
	Method       int    `json:"method"`         // 1=自己的邮箱，2=官方邮箱
	TemplateMode int    `json:"template_mode"`  // 1=提示模板，2=真实消息
	OwnEmail     string `json:"own_email"`
	OwnAuth      string `json:"own_auth"`
	DndIDs       string `json:"dnd_ids"`       // 免打扰好友ID的 JSON 数组
	NotifyEnabled int   `json:"notify_enabled"` // 总通知开关：0=关闭推送，1=开启（默认）
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
			UserID:       userID,
			Method:       0, // 0 = 官方推送
			TemplateMode: 0, // 0 = 显示正文
			OwnEmail:     "",
			OwnAuth:      "",
			DndIDs:       "[]",
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
		err = db.QueryRow("SELECT id FROM groups WHERE display_id = 1").Scan(&groupID)
		if err != nil {
			log.Printf("[官方群] 查询ID失败: %v", err)
			return
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
	ID                int64  `json:"id"`
	Email             string `json:"email"`
	Username          string `json:"username"`
	Password          string `json:"-"`
	CreatedAt         int64  `json:"created_at"`
	UpdatedAt         int64  `json:"updated_at"`
	HideEmail         int    `json:"hide_email"`
	HideQQ            int    `json:"hide_qq"`
	RequireGroupConsent int  `json:"require_group_consent"`
	QQNumber          string `json:"qq_number"`
	Signature         string `json:"signature"`
	OnlineTimeSeconds int64  `json:"online_time_seconds"`
	WordCount         int64  `json:"word_count"`
	TokenVersion      int64  `json:"token_version"`
	EmailVerified     int    `json:"email_verified"`
	RegIp             string `json:"reg_ip"`
	TokenBalance      int64  `json:"token_balance"`
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

// migrateEmailToQQ 为邮箱注册但 qq_number 为空的旧用户，从邮箱前缀数字回填 QQ 号
func migrateEmailToQQ() {
	re := regexp.MustCompile(`^(\d{5,11})`)
	rows, err := db.Query("SELECT id, email FROM users WHERE (qq_number IS NULL OR qq_number = '') AND email LIKE '%@%'")
	if err != nil {
		log.Printf("  提示: 读取待迁移用户失败: %v", err)
		return
	}
	defer rows.Close()
	type upd struct{ id int64; qq string }
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
	_, err := db.Exec("INSERT INTO verification_codes (email, code, created_at) VALUES (?, ?, ?)", email, code, time.Now().Unix())
	return err
}

func verifyCode(email, code string) bool {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM verification_codes WHERE email = ? AND code = ? AND created_at > ?", email, code, time.Now().Unix()-300).Scan(&count)
	if err != nil {
		return false
	}
	return count > 0
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

func areFriends(userID1, userID2 int64) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM friends WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)", userID1, userID2, userID2, userID1).Scan(&count)
	return count > 0, err
}

// ==================== Friends ====================

func addFriend(userID, friendID int64, friendEmail, friendUsername string) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT OR IGNORE INTO friends (user_id, friend_id, friend_email, friend_username, created_at) VALUES (?, ?, ?, ?, ?)", userID, friendID, friendEmail, friendUsername, now)
	return err
}

func deleteFriend(userID, friendID int64) error {
	_, err := db.Exec("DELETE FROM friends WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)", userID, friendID, friendID, userID)
	return err
}

func getFriends(userID int64) ([]User, error) {
	rows, err := db.Query(`SELECT u.id, u.email, u.username, u.created_at, u.updated_at, u.hide_email, u.hide_qq, u.require_group_consent, u.qq_number, u.signature, u.online_time_seconds, u.word_count FROM friends f JOIN users u ON f.friend_id = u.id WHERE f.user_id = ? ORDER BY f.created_at DESC`, userID)
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
		users = append(users, u)
	}
	return users, nil
}

// ==================== Messages ====================

type Message struct {
	ID            int64  `json:"id"`
	FromUserID    int64  `json:"from_user_id"`
	ToUserID      int64  `json:"to_user_id"`
	Content       string `json:"content"`
	CreatedAt     int64  `json:"created_at"`
	IsRevoked     int    `json:"is_revoked"`
	RevokedAt     int64  `json:"revoked_at"`
	FromUserName  string `json:"username"`
	ReplyToID     int64  `json:"reply_to_id"`
	ReplyToText   string `json:"reply_to_text"`
	ReplyToSender string `json:"reply_to_sender"`
	MediaType     string `json:"media_type"`
	MediaURL      string `json:"media_url"`
	FlashDuration int    `json:"flash_duration"`
	ToUserName    string `json:"to_user_name"`
	MsgType       string `json:"msg_type"`
	BroadcastTaskID int64 `json:"broadcast_task_id"`
}

func saveMessage(fromUserID, toUserID int64, content string, replyToID int64, mediaType, mediaURL string, flashDuration int, replyToText string, replyToSender string) (int64, error) {
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
	// 服务端静态加密：对非端到端内容（群聊/AI/回复等）入库前加密
	storeContent := encryptContentStore(content)
	result, err := db.Exec("INSERT INTO messages (from_user_id, to_user_id, content, created_at, reply_to_id, reply_to_text, reply_to_sender, media_type, media_url, flash_duration) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", fromUserID, toUserID, storeContent, now, replyToID, replyToText, replyToSender, mediaType, mediaURL, flashDuration)
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

func getMessages(userID1, userID2 int64, limit, offset int, afterID int64) ([]Message, error) {
	replyCols := ", COALESCE(m.reply_to_id, 0), COALESCE(m.reply_to_text, ''), COALESCE(m.reply_to_sender, ''), COALESCE(m.media_type, ''), COALESCE(m.media_url, ''), COALESCE(m.flash_duration, 0)"
	toNameCol := ", COALESCE(ut.username, ''), COALESCE(m.msg_type, ''), COALESCE(m.broadcast_task_id, 0)"
	var rows *sql.Rows
	var err error
	if userID2 < 0 {
		sqlStr := `SELECT m.id, m.from_user_id, m.to_user_id, m.content, m.created_at, m.is_revoked, m.revoked_at, COALESCE(u.username, '')` + replyCols + toNameCol + ` FROM messages m LEFT JOIN users u ON m.from_user_id = u.id LEFT JOIN users ut ON m.to_user_id = ut.id WHERE (m.from_user_id = ? OR m.to_user_id = ?) AND m.to_user_id < 0`
		args := []interface{}{userID2, userID2}
		if afterID > 0 {
			sqlStr += " AND m.id > ?"
			args = append(args, afterID)
		}
		// 统一显式排序为旧→新（id 升序），配合下方不再反转，保证全量/增量都顺序稳定
		sqlStr += " ORDER BY m.id ASC"
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
		// 统一显式排序为旧→新（id 升序），配合下方不再反转，保证全量/增量都顺序稳定
		sqlStr += " ORDER BY m.id ASC"
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
		if err := rows.Scan(&m.ID, &m.FromUserID, &m.ToUserID, &m.Content, &m.CreatedAt, &m.IsRevoked, &m.RevokedAt, &m.FromUserName, &m.ReplyToID, &m.ReplyToText, &m.ReplyToSender, &m.MediaType, &m.MediaURL, &m.FlashDuration, &m.ToUserName, &m.MsgType, &m.BroadcastTaskID); err != nil {
			return nil, err
		}
		// 服务端静态加密：读取时解密（私聊 E2EE 密文不带前缀，原样返回给客户端自行解密）
		m.Content = decryptContentLoad(m.Content)
		msgs = append(msgs, m)
	}
	return msgs, nil
}

func getMessageByID(msgID int64) (*Message, error) {
	var m Message
	err := db.QueryRow(`SELECT m.id, m.from_user_id, m.to_user_id, m.content, m.created_at, m.is_revoked, m.revoked_at, COALESCE(u.username, ''), COALESCE(m.reply_to_id, 0), COALESCE(m.reply_to_text, ''), COALESCE(m.reply_to_sender, ''), COALESCE(m.media_type, ''), COALESCE(m.media_url, ''), COALESCE(m.flash_duration, 0), COALESCE(m.msg_type, ''), COALESCE(m.broadcast_task_id, 0) FROM messages m LEFT JOIN users u ON m.from_user_id = u.id WHERE m.id = ?`, msgID).Scan(&m.ID, &m.FromUserID, &m.ToUserID, &m.Content, &m.CreatedAt, &m.IsRevoked, &m.RevokedAt, &m.FromUserName, &m.ReplyToID, &m.ReplyToText, &m.ReplyToSender, &m.MediaType, &m.MediaURL, &m.FlashDuration, &m.MsgType, &m.BroadcastTaskID)
	if err != nil {
		return nil, err
	}
	m.Content = decryptContentLoad(m.Content)
	return &m, nil
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
COALESCE(recent.last_content, '') as last_message, COALESCE(recent.last_time, 0) as last_time
FROM (
	SELECT other_id, MAX(created_at) as last_time, id as last_id, content as last_content, is_revoked as last_revoked
	FROM (
		SELECT to_user_id AS other_id, created_at, id, content, is_revoked FROM messages WHERE from_user_id = ?
		UNION ALL
		SELECT from_user_id AS other_id, created_at, id, content, is_revoked FROM messages WHERE to_user_id = ?
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
		if err := rows.Scan(&cu.ID, &cu.Email, &cu.Username, &cu.CreatedAt, &cu.UpdatedAt, &cu.LastMessage, &cu.LastTime); err != nil {
			return nil, err
		}
		cu.LastMessage = decryptContentLoad(cu.LastMessage)
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

// cleanupOldMessages 删除超过 retentionDays 天的消息及其已读记录，释放服务器存储。
// 注意：仅在服务器端清理；客户端通过"保存所有数据"留在本地的副本不受影响。
// 官方群（群ID=1，会话ID=-1001）消息永久保留，不参与按天清理，任何时间都能拉取到。
func cleanupOldMessages(retentionDays int) {
	cutoff := time.Now().Unix() - int64(retentionDays)*24*3600
	// 官方群消息永久保留：to_user_id 或 from_user_id 为 -1001 的不清理
	const officialConv int64 = -1001
	if _, err := db.Exec("DELETE FROM message_reads WHERE message_id IN (SELECT id FROM messages WHERE created_at < ? AND to_user_id != ? AND from_user_id != ? AND msg_type != 'broadcast')", cutoff, officialConv, officialConv); err != nil {
		log.Printf("[清理] 删除过期消息已读记录失败: %v", err)
	}
	res, err := db.Exec("DELETE FROM messages WHERE created_at < ? AND to_user_id != ? AND from_user_id != ? AND msg_type != 'broadcast'", cutoff, officialConv, officialConv)
	if err != nil {
		log.Printf("[清理] 删除超过 %d 天的消息失败: %v", retentionDays, err)
		return
	}
	if n, _ := res.RowsAffected(); n > 0 {
		log.Printf("[清理] 已删除 %d 条超过 %d 天的旧消息（官方群永久保留）", n, retentionDays)
	}
}

func getGroupMessages(groupConvID int64, limit, offset int) ([]Message, error) {
	rows, err := db.Query(`SELECT m.id, m.from_user_id, m.to_user_id, m.content, m.created_at, m.is_revoked, m.revoked_at, COALESCE(u.username, ''), COALESCE(m.reply_to_id, 0), COALESCE(m.reply_to_text, ''), COALESCE(m.reply_to_sender, ''), COALESCE(m.media_type, ''), COALESCE(m.media_url, ''), COALESCE(m.flash_duration, 0), COALESCE(m.msg_type, ''), COALESCE(m.broadcast_task_id, 0) FROM messages m LEFT JOIN users u ON m.from_user_id = u.id WHERE m.to_user_id = ? ORDER BY m.created_at DESC LIMIT ? OFFSET ?`, groupConvID, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var msgs []Message
	for rows.Next() {
		var m Message
		if err := rows.Scan(&m.ID, &m.FromUserID, &m.ToUserID, &m.Content, &m.CreatedAt, &m.IsRevoked, &m.RevokedAt, &m.FromUserName, &m.ReplyToID, &m.ReplyToText, &m.ReplyToSender, &m.MediaType, &m.MediaURL, &m.FlashDuration, &m.MsgType, &m.BroadcastTaskID); err != nil {
			return nil, err
		}
		m.Content = decryptContentLoad(m.Content)
		msgs = append(msgs, m)
	}
	return msgs, nil
}

func deleteUserAllData(userID int64) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	tx.Exec("DELETE FROM friend_requests WHERE from_user_id = ? OR to_user_id = ?", userID, userID)
	tx.Exec("DELETE FROM friends WHERE user_id = ? OR friend_id = ?", userID, userID)
	tx.Exec("DELETE FROM group_join_requests WHERE user_id = ?", userID)
	rows, _ := tx.Query("SELECT id FROM groups WHERE creator_id = ?", userID)
	if rows != nil {
		var groupIDs []int64
		for rows.Next() {
			var gid int64
			rows.Scan(&gid)
			groupIDs = append(groupIDs, gid)
		}
		rows.Close()
		for _, gid := range groupIDs {
			tx.Exec("DELETE FROM group_members WHERE group_id = ?", gid)
			tx.Exec("DELETE FROM group_join_requests WHERE group_id = ?", gid)
			tx.Exec("DELETE FROM groups WHERE id = ?", gid)
		}
	}
	tx.Exec("DELETE FROM group_members WHERE user_id = ?", userID)
	tx.Exec("DELETE FROM public_keys WHERE user_id = ?", userID)
	tx.Exec("DELETE FROM verification_codes WHERE email = (SELECT email FROM users WHERE id = ?)", userID)
	tx.Exec("DELETE FROM user_security WHERE user_id = ?", userID)
	// 物理删除账号行，释放 email 与 qq_number 供后续复用
	if _, err := tx.Exec("DELETE FROM users WHERE id=?", userID); err != nil {
		return err
	}
	return tx.Commit()
}

func updateGroupAvatar(groupID int64, avatarPath string) error {
	_, err := db.Exec("UPDATE groups SET avatar=? WHERE id=?", avatarPath, groupID)
	return err
}

// ==================== Groups ====================

type Group struct {
	ID             int64  `json:"id"`
	Name           string `json:"name"`
	Signature      string `json:"signature"`
	Announcement   string `json:"announcement"`
	CreatorID      int64  `json:"creator_id"`
	Avatar         string `json:"avatar"`
	DisplayID      int64  `json:"display_id"`
	WelcomeEnabled bool   `json:"welcome_enabled"`
	WelcomeText    string `json:"welcome_text"`
	CreatedAt      int64  `json:"created_at"`
	UpdatedAt      int64  `json:"updated_at"`
	UserRole       string `json:"user_role"`
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

func getGroupByID(groupID int64) (*Group, error) {
	g := &Group{}
	err := db.QueryRow("SELECT id, name, signature, announcement, creator_id, avatar, display_id, welcome_enabled, welcome_text, created_at, updated_at FROM groups WHERE id = ?", groupID).Scan(&g.ID, &g.Name, &g.Signature, &g.Announcement, &g.CreatorID, &g.Avatar, &g.DisplayID, &g.WelcomeEnabled, &g.WelcomeText, &g.CreatedAt, &g.UpdatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return g, nil
}

func getGroupsForUser(userID int64) ([]Group, error) {
	rows, err := db.Query("SELECT g.id, g.name, g.signature, g.announcement, g.creator_id, g.avatar, g.display_id, g.welcome_enabled, g.welcome_text, g.created_at, g.updated_at FROM groups g JOIN group_members gm ON g.id = gm.group_id WHERE gm.user_id = ? ORDER BY g.created_at DESC", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var groups []Group
	for rows.Next() {
		var g Group
		if err := rows.Scan(&g.ID, &g.Name, &g.Signature, &g.Announcement, &g.CreatorID, &g.Avatar, &g.DisplayID, &g.WelcomeEnabled, &g.WelcomeText, &g.CreatedAt, &g.UpdatedAt); err != nil {
			return nil, err
		}
		groups = append(groups, g)
	}
	return groups, nil
}

func getGroupMembersWithInfo(groupID int64) ([]GroupMemberInfo, error) {
	rows, err := db.Query("SELECT gm.user_id, u.username, gm.role, gm.joined_at FROM group_members gm JOIN users u ON gm.user_id = u.id WHERE gm.group_id = ? ORDER BY CASE gm.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END, gm.joined_at ASC", groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var members []GroupMemberInfo
	for rows.Next() {
		var m GroupMemberInfo
		rows.Scan(&m.UserID, &m.Username, &m.Role, &m.JoinedAt)
		members = append(members, m)
	}
	return members, nil
}

type GroupMemberInfo struct {
	UserID   int64  `json:"user_id"`
	Username string `json:"username"`
	Role     string `json:"role"`
	JoinedAt int64  `json:"joined_at"`
}

func getGroupMembers(groupID int64) ([]int64, error) {
	rows, err := db.Query("SELECT user_id FROM group_members WHERE group_id = ?", groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var uid int64
		rows.Scan(&uid)
		ids = append(ids, uid)
	}
	return ids, nil
}

func getMemberRole(groupID, userID int64) (string, error) {
	var role string
	err := db.QueryRow("SELECT role FROM group_members WHERE group_id = ? AND user_id = ?", groupID, userID).Scan(&role)
	if err != nil {
		return "", err
	}
	return role, nil
}

func updateGroupSettings(groupID int64, name, signature, announcement string, welcomeEnabled bool, welcomeText string, notSearchable, joinRequired bool) error {
	wEnabled := 0
	if welcomeEnabled {
		wEnabled = 1
	}
	ns := 0
	if notSearchable {
		ns = 1
	}
	jr := 0
	if joinRequired {
		jr = 1
	}
	_, err := db.Exec("UPDATE groups SET name=?, signature=?, announcement=?, welcome_enabled=?, welcome_text=?, not_searchable=?, join_required=?, updated_at=? WHERE id=?", name, signature, announcement, wEnabled, welcomeText, ns, jr, time.Now().Unix(), groupID)
	return err
}

func deleteGroup(groupID int64) error {
	convID := -(1000 + groupID)
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	tx.Exec("DELETE FROM messages WHERE from_user_id = ? OR to_user_id = ?", convID, convID)
	tx.Exec("DELETE FROM group_members WHERE group_id = ?", groupID)
	tx.Exec("DELETE FROM groups WHERE id = ?", groupID)
	return tx.Commit()
}

func transferGroupOwner(groupID, newOwnerID int64) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// 将当前所有群主降级为管理员。
	// 关键：以"当前真正的 owner"为准，而不是调用者。否则开发者转让（开发者未必是群主）时，
	// 原群主不会被降级，导致一个群出现多个群主。
	if _, err := tx.Exec("UPDATE group_members SET role = 'admin' WHERE group_id = ? AND role = 'owner'", groupID); err != nil {
		return err
	}
	// 新群主：若已是成员则升级为 owner，否则加入群并设为 owner
	if _, err := tx.Exec("UPDATE group_members SET role = 'owner' WHERE group_id = ? AND user_id = ?", groupID, newOwnerID); err != nil {
		return err
	}
	if _, err := tx.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'owner', ?)", groupID, newOwnerID, time.Now().Unix()); err != nil {
		return err
	}
	return tx.Commit()
}

func setGroupRole(groupID, userID int64, role string) error {
	_, err := db.Exec("UPDATE group_members SET role = ? WHERE group_id = ? AND user_id = ?", role, groupID, userID)
	return err
}

func searchUsersByKeyword(keyword string) ([]User, error) {
	like := "%" + keyword + "%"
	rows, err := db.Query("SELECT id, email, username, created_at, updated_at, hide_email, hide_qq, require_group_consent, qq_number, signature, online_time_seconds, word_count FROM users WHERE email LIKE ? OR username LIKE ? OR CAST(id AS TEXT) LIKE ? OR qq_number LIKE ? LIMIT 50", like, like, like, like)
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
		users = append(users, u)
	}
	return users, nil
}

func addToGroupDirectly(groupID, userID int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)", groupID, userID, time.Now().Unix())
	if err == nil {
		sendGroupWelcomeMessage(groupID, userID)
	}
	return err
}

func removeFromGroup(groupID, userID int64) error {
	_, err := db.Exec("DELETE FROM group_members WHERE group_id = ? AND user_id = ?", groupID, userID)
	return err
}

func isGroupBlacklisted(groupID, userID int64) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM group_blacklist WHERE group_id = ? AND user_id = ?", groupID, userID).Scan(&count)
	return count > 0, err
}

func addGroupBlacklist(groupID, userID, blockedBy int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_blacklist (group_id, user_id, blocked_at, blocked_by) VALUES (?, ?, ?, ?)", groupID, userID, time.Now().Unix(), blockedBy)
	return err
}

func listAllGroups(page, limit int, keyword string) ([]Group, int, error) {
	offset := (page - 1) * limit
	var total int
	var rows *sql.Rows
	var err error
	if keyword != "" {
		like := "%" + keyword + "%"
		db.QueryRow("SELECT COUNT(*) FROM groups WHERE name LIKE ? OR CAST(display_id AS TEXT) LIKE ?", like, like).Scan(&total)
		rows, err = db.Query("SELECT id, name, signature, announcement, creator_id, avatar, display_id, welcome_enabled, welcome_text, created_at, updated_at FROM groups WHERE name LIKE ? OR CAST(display_id AS TEXT) LIKE ? ORDER BY id DESC LIMIT ? OFFSET ?", like, like, limit, offset)
	} else {
		db.QueryRow("SELECT COUNT(*) FROM groups").Scan(&total)
		rows, err = db.Query("SELECT id, name, signature, announcement, creator_id, avatar, display_id, welcome_enabled, welcome_text, created_at, updated_at FROM groups ORDER BY id DESC LIMIT ? OFFSET ?", limit, offset)
	}
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var groups []Group
	for rows.Next() {
		var g Group
		if err := rows.Scan(&g.ID, &g.Name, &g.Signature, &g.Announcement, &g.CreatorID, &g.Avatar, &g.DisplayID, &g.WelcomeEnabled, &g.WelcomeText, &g.CreatedAt, &g.UpdatedAt); err != nil {
			return nil, 0, err
		}
		groups = append(groups, g)
	}
	return groups, total, nil
}

func insertWelcomeMessage(groupConvID int64, welcomeText string) (int64, error) {
	if welcomeText == "" {
		return 0, nil
	}
	result, err := db.Exec("INSERT INTO messages (from_user_id, to_user_id, content, created_at) VALUES (?, ?, ?, ?)", groupConvID, groupConvID, encryptContentStore(welcomeText), time.Now().Unix())
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

func submitJoinRequest(groupID, userID int64, reason string) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_join_requests (group_id, user_id, reason, status, created_at) VALUES (?, ?, ?, 'pending', ?)", groupID, userID, reason, time.Now().Unix())
	return err
}

func getGroupJoinRequests(groupID int64) ([]JoinRequest, error) {
	rows, err := db.Query("SELECT gjr.id, gjr.group_id, gjr.user_id, u.username, u.email, gjr.reason, gjr.status, gjr.created_at FROM group_join_requests gjr JOIN users u ON gjr.user_id = u.id WHERE gjr.group_id = ? AND gjr.status = 'pending' ORDER BY gjr.created_at DESC", groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var requests []JoinRequest
	for rows.Next() {
		var r JoinRequest
		if err := rows.Scan(&r.ID, &r.GroupID, &r.UserID, &r.Username, &r.Email, &r.Reason, &r.Status, &r.CreatedAt); err != nil {
			return nil, err
		}
		requests = append(requests, r)
	}
	return requests, nil
}

// ==================== 群邀请（拉我时需经过我同意） ====================

// getUserRequireConsent 读取目标用户的"拉我时需经过我同意"开关（默认开启）。
func getUserRequireConsent(userID int64) bool {
	var v int
	err := db.QueryRow("SELECT require_group_consent FROM users WHERE id = ?", userID).Scan(&v)
	if err != nil {
		return true // 默认开启
	}
	return v == 1
}

// getGroupName 读取群名，用于邀请卡片/结果通知拼文案。
func getGroupName(groupID int64) string {
	var name string
	err := db.QueryRow("SELECT name FROM groups WHERE id = ?", groupID).Scan(&name)
	if err != nil {
		return ""
	}
	return name
}

// getGroupJoinRequired 判断群是否需要审核加入。
func getGroupJoinRequired(groupID int64) bool {
	var v int
	err := db.QueryRow("SELECT join_required FROM groups WHERE id = ?", groupID).Scan(&v)
	if err != nil {
		return false
	}
	return v == 1
}

// createGroupInvite 记录一次待确认的入群邀请（重复邀请被忽略）。
func createGroupInvite(groupID, inviterID, targetUserID int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO group_invites (group_id, inviter_id, target_user_id, status, created_at) VALUES (?, ?, ?, 'pending', ?)",
		groupID, inviterID, targetUserID, time.Now().Unix())
	return err
}

// findPendingGroupInvite 查找某用户的待确认邀请，返回邀请记录 id（0 表示无）。
func findPendingGroupInvite(groupID, inviterID, targetUserID int64) int64 {
	var id int64
	err := db.QueryRow("SELECT id FROM group_invites WHERE group_id = ? AND inviter_id = ? AND target_user_id = ? AND status = 'pending' LIMIT 1",
		groupID, inviterID, targetUserID).Scan(&id)
	if err != nil {
		return 0
	}
	return id
}

// updateGroupInviteStatus 更新邀请状态：accepted / rejected。
func updateGroupInviteStatus(id int64, status string) error {
	_, err := db.Exec("UPDATE group_invites SET status = ? WHERE id = ?", status, id)
	return err
}

type JoinRequest struct {
	ID        int64  `json:"id"`
	GroupID   int64  `json:"group_id"`
	UserID    int64  `json:"user_id"`
	Username  string `json:"username"`
	Email     string `json:"email"`
	Reason    string `json:"reason"`
	Status    string `json:"status"`
	CreatedAt int64  `json:"created_at"`
}

func reviewJoinRequest(requestID int64, approve bool) error {
	var req struct {
		GroupID int64
		UserID  int64
	}
	err := db.QueryRow("SELECT group_id, user_id FROM group_join_requests WHERE id = ?", requestID).Scan(&req.GroupID, &req.UserID)
	if err != nil {
		return err
	}
	status := "rejected"
	if approve {
		status = "approved"
		_, err = db.Exec("INSERT OR IGNORE INTO group_members (group_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?)", req.GroupID, req.UserID, time.Now().Unix())
		if err != nil {
			return err
		}
		sendGroupWelcomeMessage(req.GroupID, req.UserID)
	}
	_, err = db.Exec("UPDATE group_join_requests SET status = ? WHERE id = ?", status, requestID)
	return err
}

func searchGroupsByDisplayID(query string) ([]Group, error) {
	like := "%" + query + "%"
	rows, err := db.Query("SELECT id, name, signature, announcement, creator_id, avatar, display_id, welcome_enabled, welcome_text, created_at, updated_at FROM groups WHERE CAST(display_id AS TEXT) LIKE ? OR name LIKE ? LIMIT 50", like, like)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var groups []Group
	for rows.Next() {
		var g Group
		if err := rows.Scan(&g.ID, &g.Name, &g.Signature, &g.Announcement, &g.CreatorID, &g.Avatar, &g.DisplayID, &g.WelcomeEnabled, &g.WelcomeText, &g.CreatedAt, &g.UpdatedAt); err != nil {
			return nil, err
		}
		groups = append(groups, g)
	}
	return groups, nil
}

func updateGroupDisplayId(groupID, newDisplayId int64) error {
	var existing int64
	err := db.QueryRow("SELECT id FROM groups WHERE display_id = ? AND id != ?", newDisplayId, groupID).Scan(&existing)
	if err == nil {
		return fmt.Errorf("群ID %d 已被其他群使用", newDisplayId)
	}
	if err != sql.ErrNoRows {
		return err
	}
	_, err = db.Exec("UPDATE groups SET display_id = ?, updated_at = ? WHERE id = ?", newDisplayId, time.Now().Unix(), groupID)
	return err
}

// sendGroupWelcomeMessage 自动 @ 新成员
func sendGroupWelcomeMessage(groupID, userID int64) {
	var welcomeEnabled bool
	var welcomeText string
	err := db.QueryRow("SELECT welcome_enabled, welcome_text FROM groups WHERE id = ?", groupID).Scan(&welcomeEnabled, &welcomeText)
	if err != nil || !welcomeEnabled || welcomeText == "" {
		return
	}
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	if username == "" {
		username = fmt.Sprintf("用户%d", userID)
	}
	finalText := "@" + username + " " + welcomeText
	convID := -(1000 + groupID)
	msgID, err := saveMessage(convID, convID, finalText, 0, "", "", 0, "", "")
	if err != nil {
		log.Printf("发送群欢迎消息失败: %v", err)
		return
	}
	PushToGroup(convID, msgID, convID, "", convID, finalText, time.Now().Unix(), "", "", 0, 0, "", "")
}

// ==================== 封禁管理 ====================

type BanRecord struct {
	UserID            int64  `json:"user_id"`
	Reason            string `json:"reason"`
	BannedAt          int64  `json:"banned_at"`
	ExpiresAt         int64  `json:"expires_at"`
	BannedBy          int64  `json:"banned_by"`
	UnbanNotify       int    `json:"unban_notify"`
	UnbanPopupMessage string `json:"unban_popup_message"`
}

func banUser(userID int64, reason string, durationSeconds int64, bannedBy int64, unbanPopupMessage string) error {
	now := time.Now().Unix()
	expiresAt := now + durationSeconds
	_, err := db.Exec("INSERT OR REPLACE INTO banned_users (user_id, reason, banned_at, expires_at, banned_by, unban_notify, unban_popup_message) VALUES (?, ?, ?, ?, ?, 0, ?)", userID, reason, now, expiresAt, bannedBy, unbanPopupMessage)
	return err
}

func unbanUser(userID int64) error {
	_, err := db.Exec("DELETE FROM banned_users WHERE user_id = ?", userID)
	return err
}

func setUnbanNotify(userID int64) error {
	_, err := db.Exec("UPDATE banned_users SET unban_notify = 1 WHERE user_id = ?", userID)
	return err
}

func getBanStatus(userID int64) (*BanRecord, error) {
	r := &BanRecord{}
	err := db.QueryRow("SELECT user_id, reason, banned_at, expires_at, banned_by, unban_notify, unban_popup_message FROM banned_users WHERE user_id = ?", userID).Scan(&r.UserID, &r.Reason, &r.BannedAt, &r.ExpiresAt, &r.BannedBy, &r.UnbanNotify, &r.UnbanPopupMessage)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	now := time.Now().Unix()
	if now >= r.ExpiresAt {
		db.Exec("DELETE FROM banned_users WHERE user_id = ?", userID)
		return nil, nil
	}
	return r, nil
}

func getAllBannedUsers() ([]BanRecord, error) {
	now := time.Now().Unix()
	rows, err := db.Query("SELECT user_id, reason, banned_at, expires_at, banned_by, unban_notify, unban_popup_message FROM banned_users WHERE expires_at > ?", now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var bans []BanRecord
	for rows.Next() {
		var r BanRecord
		if err := rows.Scan(&r.UserID, &r.Reason, &r.BannedAt, &r.ExpiresAt, &r.BannedBy, &r.UnbanNotify, &r.UnbanPopupMessage); err != nil {
			return nil, err
		}
		bans = append(bans, r)
	}
	return bans, nil
}

// ==================== 禁言管理 ====================

type MuteRecord struct {
	UserID    int64 `json:"user_id"`
	MutedBy   int64 `json:"muted_by"`
	MutedAt   int64 `json:"muted_at"`
	ExpiresAt int64 `json:"expires_at"`
	MuteType  int   `json:"mute_type"`
}

func muteUser(userID int64, durationSeconds int64, mutedBy int64, muteType int) error {
	now := time.Now().Unix()
	expiresAt := now + durationSeconds
	_, err := db.Exec("INSERT OR REPLACE INTO muted_users (user_id, muted_by, muted_at, expires_at, mute_type) VALUES (?, ?, ?, ?, ?)", userID, mutedBy, now, expiresAt, muteType)
	return err
}

func unmuteUser(userID int64) error {
	_, err := db.Exec("DELETE FROM muted_users WHERE user_id = ?", userID)
	return err
}

func getMuteStatus(userID int64) (*MuteRecord, error) {
	r := &MuteRecord{}
	err := db.QueryRow("SELECT user_id, muted_by, muted_at, expires_at, mute_type FROM muted_users WHERE user_id = ?", userID).Scan(&r.UserID, &r.MutedBy, &r.MutedAt, &r.ExpiresAt, &r.MuteType)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	now := time.Now().Unix()
	if now >= r.ExpiresAt {
		db.Exec("DELETE FROM muted_users WHERE user_id = ?", userID)
		return nil, nil
	}
	return r, nil
}

// ==================== 群内禁言 ====================

func groupMuteUser(groupID int64, userID int64, durationSeconds int64, mutedBy int64) error {
	now := time.Now().Unix()
	expiresAt := now + durationSeconds
	_, err := db.Exec("INSERT OR REPLACE INTO group_mutes (group_id, user_id, muted_by, muted_at, expires_at) VALUES (?, ?, ?, ?, ?)",
		groupID, userID, mutedBy, now, expiresAt)
	return err
}

func groupUnmuteUser(groupID int64, userID int64) error {
	_, err := db.Exec("DELETE FROM group_mutes WHERE group_id = ? AND user_id = ?", groupID, userID)
	return err
}

// isGroupMuted 检查用户在群内是否被禁言，返回 (是否禁言, expires_at)
func isGroupMuted(groupID int64, userID int64) (bool, int64) {
	var expiresAt int64
	err := db.QueryRow("SELECT expires_at FROM group_mutes WHERE group_id = ? AND user_id = ?", groupID, userID).Scan(&expiresAt)
	if err != nil {
		return false, 0
	}
	now := time.Now().Unix()
	if now >= expiresAt {
		db.Exec("DELETE FROM group_mutes WHERE group_id = ? AND user_id = ?", groupID, userID)
		return false, 0
	}
	return true, expiresAt
}

func getAllGroupMutes() ([]map[string]interface{}, error) {
	now := time.Now().Unix()
	rows, err := db.Query("SELECT group_id, user_id, muted_by, muted_at, expires_at FROM group_mutes WHERE expires_at > ?", now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []map[string]interface{}
	for rows.Next() {
		var gid, uid, mutedBy, mutedAt, expiresAt int64
		if err := rows.Scan(&gid, &uid, &mutedBy, &mutedAt, &expiresAt); err != nil {
			continue
		}
		result = append(result, map[string]interface{}{
			"group_id":   gid,
			"user_id":    uid,
			"muted_by":   mutedBy,
			"muted_at":   mutedAt,
			"expires_at": expiresAt,
		})
	}
	return result, nil
}

// getGroupMuteMembers 获取群内所有被禁言成员及其信息
func getGroupMuteMembers(groupID int64) ([]map[string]interface{}, error) {
	now := time.Now().Unix()
	rows, err := db.Query(
		`SELECT gm.user_id, gm.expires_at, COALESCE(u.username, '')
		 FROM group_mutes gm
		 LEFT JOIN users u ON gm.user_id = u.id
		 WHERE gm.group_id = ? AND gm.expires_at > ?`, groupID, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var result []map[string]interface{}
	for rows.Next() {
		var uid, expiresAt int64
		var username string
		if err := rows.Scan(&uid, &expiresAt, &username); err != nil {
			continue
		}
		if username == "" {
			username = fmt.Sprintf("用户%d", uid)
		}
		result = append(result, map[string]interface{}{
			"user_id":    uid,
			"expires_at": expiresAt,
			"username":   username,
		})
	}
	return result, nil
}

func getAllMutedUsers() ([]MuteRecord, error) {
	now := time.Now().Unix()
	rows, err := db.Query("SELECT user_id, muted_by, muted_at, expires_at, mute_type FROM muted_users WHERE expires_at > ?", now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var mutes []MuteRecord
	for rows.Next() {
		var r MuteRecord
		if err := rows.Scan(&r.UserID, &r.MutedBy, &r.MutedAt, &r.ExpiresAt, &r.MuteType); err != nil {
			return nil, err
		}
		mutes = append(mutes, r)
	}
	return mutes, nil
}

// ==================== 平台管理员 ====================

type PlatformAdminRecord struct {
	UserID    int64 `json:"user_id"`
	GrantedBy int64 `json:"granted_by"`
	GrantedAt int64 `json:"granted_at"`
}

func isPlatformAdmin(userID int64) (bool, error) {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM platform_admins WHERE user_id = ?", userID).Scan(&count)
	return count > 0, err
}

func grantPlatformAdmin(userID, grantedBy int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO platform_admins (user_id, granted_by, granted_at) VALUES (?, ?, ?)", userID, grantedBy, time.Now().Unix())
	return err
}

func revokePlatformAdmin(userID int64) error {
	_, err := db.Exec("DELETE FROM platform_admins WHERE user_id = ?", userID)
	return err
}

func getAllPlatformAdmins() ([]PlatformAdminRecord, error) {
	rows, err := db.Query("SELECT user_id, granted_by, granted_at FROM platform_admins ORDER BY granted_at DESC")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var admins []PlatformAdminRecord
	for rows.Next() {
		var r PlatformAdminRecord
		if err := rows.Scan(&r.UserID, &r.GrantedBy, &r.GrantedAt); err != nil {
			return nil, err
		}
		admins = append(admins, r)
	}
	return admins, nil
}

// revokeAllAdminPermissions 一键收回除 keepUserID 外所有用户的平台管理员与细粒度权限。
// 注意：只删除"有授权记录"的行，绝不能整体清空 user_permissions，
// 否则按 checkUserPermission 的兼容规则，空记录反而会被视为拥有全部权限。
func revokeAllAdminPermissions(keepUserID int64) (adminCount, permCount int, err error) {
	tx, err := db.Begin()
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()

	res, err := tx.Exec("DELETE FROM platform_admins WHERE user_id != ?", keepUserID)
	if err != nil {
		return 0, 0, err
	}
	if n, _ := res.RowsAffected(); n > 0 {
		adminCount = int(n)
	}

	// 只清空被授予过细粒度权限的用户，避免"空记录=全权限"的兼容陷阱
	res2, err := tx.Exec("DELETE FROM user_permissions WHERE user_id != ?", keepUserID)
	if err != nil {
		return 0, 0, err
	}
	if n, _ := res2.RowsAffected(); n > 0 {
		permCount = int(n)
	}

	if err := tx.Commit(); err != nil {
		return 0, 0, err
	}
	return adminCount, permCount, nil
}

// ==================== 细粒度权限 ====================

func getUserPermissions(userID int64) ([]string, error) {
	rows, err := db.Query("SELECT perm_key FROM user_permissions WHERE user_id = ?", userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var keys []string
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return nil, err
		}
		keys = append(keys, k)
	}
	return keys, nil
}

func saveUserPermissions(userID int64, keys []string) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	tx.Exec("DELETE FROM user_permissions WHERE user_id = ?", userID)
	for _, k := range keys {
		tx.Exec("INSERT OR IGNORE INTO user_permissions (user_id, perm_key) VALUES (?, ?)", userID, k)
	}
	return tx.Commit()
}

func hasPermission(userID int64, permKey string) bool {
	var count int
	err := db.QueryRow("SELECT COUNT(*) FROM user_permissions WHERE user_id = ? AND perm_key = ?", userID, permKey).Scan(&count)
	return err == nil && count > 0
}

// checkUserPermission 检查用户是否有指定的细粒度管理权限
// 兼容规则：
//   - 用户没有任何权限记录（空列表）→ 视为有全部权限，兼容旧版
//   - 大类 key（如 "users"）覆盖所有子权限（如 users.ban, users.mute...）
//   - 子权限 key 精确匹配
func checkUserPermission(userID int64, permKey string) bool {
	var count int
	db.QueryRow("SELECT COUNT(*) FROM user_permissions WHERE user_id = ?", userID).Scan(&count)
	// 无任何权限记录 = 兼容旧版：允许所有操作
	if count == 0 {
		return true
	}
	// 精确匹配子权限 key
	if hasPermission(userID, permKey) {
		return true
	}
	// 大类 key 匹配（如 permKey="users.ban"，检查 "users" 是否被勾选）
	for i := 0; i < len(permKey); i++ {
		if permKey[i] == '.' {
			if hasPermission(userID, permKey[:i]) {
				return true
			}
			break
		}
	}
	return false
}

// ==================== 管理员操作日志 ====================

type AdminOperationLog struct {
	ID             int64  `json:"id"`
	AdminUserID    int64  `json:"admin_user_id"`
	AdminUsername  string `json:"admin_username"`
	OperationType  string `json:"operation_type"`
	TargetUserID   int64  `json:"target_user_id"`
	TargetUsername string `json:"target_username"`
	Details        string `json:"details"`
	CreatedAt      int64  `json:"created_at"`
}

func addAdminOperationLog(adminUserID int64, adminUsername, operationType string, targetUserID int64, targetUsername, details string) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT INTO admin_operation_logs (admin_user_id, admin_username, operation_type, target_user_id, target_username, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", adminUserID, adminUsername, operationType, targetUserID, targetUsername, details, now)
	return err
}

func getAdminOperationLogs(limit, offset int) ([]AdminOperationLog, int, error) {
	var total int
	db.QueryRow("SELECT COUNT(*) FROM admin_operation_logs").Scan(&total)
	rows, err := db.Query("SELECT id, admin_user_id, admin_username, operation_type, target_user_id, target_username, details, created_at FROM admin_operation_logs ORDER BY created_at DESC LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var logs []AdminOperationLog
	for rows.Next() {
		var l AdminOperationLog
		if err := rows.Scan(&l.ID, &l.AdminUserID, &l.AdminUsername, &l.OperationType, &l.TargetUserID, &l.TargetUsername, &l.Details, &l.CreatedAt); err != nil {
			return nil, 0, err
		}
		logs = append(logs, l)
	}
	return logs, total, nil
}

func getAdminOperationLogsByAdmin(adminUserID int64, limit, offset int) ([]AdminOperationLog, int, error) {
	var total int
	db.QueryRow("SELECT COUNT(*) FROM admin_operation_logs WHERE admin_user_id = ?", adminUserID).Scan(&total)
	rows, err := db.Query("SELECT id, admin_user_id, admin_username, operation_type, target_user_id, target_username, details, created_at FROM admin_operation_logs WHERE admin_user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?", adminUserID, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var logs []AdminOperationLog
	for rows.Next() {
		var l AdminOperationLog
		if err := rows.Scan(&l.ID, &l.AdminUserID, &l.AdminUsername, &l.OperationType, &l.TargetUserID, &l.TargetUsername, &l.Details, &l.CreatedAt); err != nil {
			return nil, 0, err
		}
		logs = append(logs, l)
	}
	return logs, total, nil
}

// ==================== 用户位置记录 ====================

type UserLocationRecord struct {
	ID        int64   `json:"id"`
	UserID    int64   `json:"user_id"`
	Lat       float64 `json:"lat"`
	Lng       float64 `json:"lng"`
	Address   string  `json:"address"`
	CreatedAt int64   `json:"created_at"`
}

func addUserLocation(userID int64, lat, lng float64, address string) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT INTO user_locations (user_id, lat, lng, address, created_at) VALUES (?, ?, ?, ?, ?)",
		userID, lat, lng, address, now)
	return err
}

func getUserLocations(userID int64) ([]UserLocationRecord, error) {
	rows, err := db.Query("SELECT id, user_id, lat, lng, address, created_at FROM user_locations WHERE user_id = ? ORDER BY created_at DESC",
		userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var records []UserLocationRecord
	for rows.Next() {
		var r UserLocationRecord
		if err := rows.Scan(&r.ID, &r.UserID, &r.Lat, &r.Lng, &r.Address, &r.CreatedAt); err != nil {
			return nil, err
		}
		records = append(records, r)
	}
	return records, nil
}

// ==================== 闪照查看记录（防清缓存重看） ====================

func recordFlashView(userID, messageID int64) error {
	_, err := db.Exec("INSERT OR IGNORE INTO flash_views (user_id, message_id, viewed_at) VALUES (?, ?, ?)", userID, messageID, time.Now().Unix())
	return err
}

func hasUserViewedFlash(userID, messageID int64) bool {
	var count int
	db.QueryRow("SELECT COUNT(*) FROM flash_views WHERE user_id = ? AND message_id = ?", userID, messageID).Scan(&count)
	return count > 0
}

func getViewedFlashIDs(userID int64) []int64 {
	rows, err := db.Query("SELECT message_id FROM flash_views WHERE user_id = ?", userID)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		ids = append(ids, id)
	}
	return ids
}

// ==================== User Security ====================

type UserSecurity struct {
	UserID          int64  `json:"user_id"`
	PasswordHash    string `json:"-"`
	GesturePattern  string `json:"-"`
	SecurityEnabled int    `json:"security_enabled"`
	MultiVerify     int    `json:"multi_verify"`
	CreatedAt       int64  `json:"created_at"`
	UpdatedAt       int64  `json:"updated_at"`
}

func saveUserSecurity(sec *UserSecurity) error {
	now := time.Now().Unix()
	_, err := db.Exec("INSERT INTO user_security (user_id, password_hash, gesture_pattern, security_enabled, multi_verify, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET password_hash = COALESCE(NULLIF(?, ''), password_hash), gesture_pattern = COALESCE(NULLIF(?, ''), gesture_pattern), security_enabled = ?, multi_verify = COALESCE(?, multi_verify), updated_at = ?", sec.UserID, sec.PasswordHash, sec.GesturePattern, sec.SecurityEnabled, sec.MultiVerify, now, now, sec.PasswordHash, sec.GesturePattern, sec.SecurityEnabled, sec.MultiVerify, now)
	return err
}

func getUserSecurity(userID int64) (*UserSecurity, error) {
	sec := &UserSecurity{}
	err := db.QueryRow("SELECT user_id, password_hash, gesture_pattern, security_enabled, multi_verify, created_at, updated_at FROM user_security WHERE user_id = ?", userID).Scan(&sec.UserID, &sec.PasswordHash, &sec.GesturePattern, &sec.SecurityEnabled, &sec.MultiVerify, &sec.CreatedAt, &sec.UpdatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return sec, nil
}

// ==================== Server Registration ====================

type ServerRecord struct {
	ID           int64  `json:"id"`
	OwnerUserID  int64  `json:"owner_user_id"`
	OwnerName    string `json:"owner_username"`
	Name         string `json:"name"`
	Domain       string `json:"domain"`
	Password     string `json:"-"`
	CreatedAt    int64  `json:"created_at"`
	ServerStatus string `json:"server_status"`
	LastActiveAt int64  `json:"last_active_at"`
}

func findServerByName(name string) (*ServerRecord, error) {
	s := &ServerRecord{}
	err := db.QueryRow("SELECT s.id, s.owner_user_id, COALESCE(u.username,''), s.name, s.domain, s.password, s.created_at FROM servers s JOIN users u ON s.owner_user_id = u.id WHERE s.name = ?", name).Scan(&s.ID, &s.OwnerUserID, &s.OwnerName, &s.Name, &s.Domain, &s.Password, &s.CreatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return s, nil
}

func findServerByOwner(userID int64) (*ServerRecord, error) {
	s := &ServerRecord{}
	err := db.QueryRow("SELECT s.id, s.owner_user_id, COALESCE(u.username,''), s.name, s.domain, s.password, s.created_at FROM servers s JOIN users u ON s.owner_user_id = u.id WHERE s.owner_user_id = ?", userID).Scan(&s.ID, &s.OwnerUserID, &s.OwnerName, &s.Name, &s.Domain, &s.Password, &s.CreatedAt)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	return s, nil
}

func createServer(ownerUserID int64, name, domain, hashedPassword string) (int64, error) {
	now := time.Now().Unix()
	result, err := db.Exec("INSERT INTO servers (owner_user_id, name, domain, password, created_at) VALUES (?, ?, ?, ?, ?)", ownerUserID, name, domain, hashedPassword, now)
	if err != nil {
		return 0, err
	}
	return result.LastInsertId()
}

// getOrCreateServerByOwner 获取或自动创建用户的服务器（用于文件托管等功能，无需用户手动注册）
func getOrCreateServerByOwner(userID int64) (*ServerRecord, error) {
	// 先查找现有服务器
	s, err := findServerByOwner(userID)
	if err != nil {
		return nil, err
	}
	if s != nil {
		return s, nil
	}

	// 没有服务器，自动创建一个（用于文件托管/网站托管）
	// 获取用户名
	var username string
	db.QueryRow("SELECT username FROM users WHERE id = ?", userID).Scan(&username)
	if username == "" {
		username = fmt.Sprintf("用户%d", userID)
	}

	serverName := fmt.Sprintf("%s的服务器", username)
	domain := fmt.Sprintf("user%d", userID)
	// 生成一个随机密码（用户不需要知道，仅用于兼容旧字段）
	password := randomString(16)
	hashedPasswordBytes, _ := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	hashedPassword := string(hashedPasswordBytes)

	serverID, err := createServer(userID, serverName, domain, hashedPassword)
	if err != nil {
		return nil, err
	}

	log.Printf("[服务器] 自动为用户创建服务器: user=%d, server=%d, name=%s", userID, serverID, serverName)

	// 返回新创建的服务器
	return findServerByOwner(userID)
}

func recordServerAccess(serverID, userID int64, userEmail string) {
	db.Exec("INSERT INTO server_access_logs (server_id, user_id, user_email, accessed_at) VALUES (?, ?, ?, ?)", serverID, userID, userEmail, time.Now().Unix())
}

func updateServerLastActive(serverID int64) {
	db.Exec("UPDATE servers SET last_active_at = ? WHERE id = ?", time.Now().Unix(), serverID)
}

func computeServerStatus(serverID int64) string {
	// 优先读取 server_status 字段（手动设置的 stopped/stopping/restarting 等）
	var serverStatus string
	err := db.QueryRow("SELECT server_status FROM servers WHERE id = ?", serverID).Scan(&serverStatus)
	if err != nil {
		return "offline"
	}
	// 如果手动设置了状态（非空且不是默认值），优先返回
	if serverStatus == "stopped" || serverStatus == "stopping" || serverStatus == "restarting" {
		return serverStatus
	}

	var lastActiveAt int64
	err = db.QueryRow("SELECT last_active_at FROM servers WHERE id = ?", serverID).Scan(&lastActiveAt)
	if err != nil {
		return "offline"
	}
	if lastActiveAt == 0 {
		return "offline"
	}
	now := time.Now().Unix()
	if now-lastActiveAt <= 300 {
		return "running"
	}
	return "offline"
}

func setServerStatus(serverID int64, status string) error {
	_, err := db.Exec("UPDATE servers SET server_status = ? WHERE id = ?", status, serverID)
	return err
}

func asyncShutdownServer(serverID int64) {
	setServerStatus(serverID, "stopping")
	go func() {
		time.Sleep(3 * time.Second)
		setServerStatus(serverID, "stopped")
	}()
}

func asyncRestartServer(serverID int64) {
	setServerStatus(serverID, "restarting")
	go func() {
		time.Sleep(3 * time.Second)
		setServerStatus(serverID, "running")
	}()
}

func updateServerDomainByID(serverID int64, newDomain string) error {
	_, err := db.Exec("UPDATE servers SET domain = ? WHERE id = ?", newDomain, serverID)
	return err
}
