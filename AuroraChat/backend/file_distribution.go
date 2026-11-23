package main

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// 文件分发存储根目录
var fdStorageDir string

// fdDefaultConfigUserID 默认(通用)配置的虚拟 user_id
const fdDefaultConfigUserID = 0

// fdConfigUnitOptions 容量单位可选项
var fdConfigUnitOptions = []string{"KB", "MB", "GB", "TB"}

// fdConfigKeySpecs 配置键注册表：key → 校验类型（"number" | "unit"）。
// 新增配置项在这里注册即可，前后端都按此键值驱动。
var fdConfigKeySpecs = map[string]string{
	"max_speed":      "number", // Mbps
	"capacity_value": "number", // 数值
	"capacity_unit":  "unit",   // KB/MB/GB/TB
}

// fdUnitBytes 容量单位 → 字节数
func fdUnitBytes(unit string) int64 {
	switch strings.ToUpper(strings.TrimSpace(unit)) {
	case "KB":
		return 1024
	case "MB":
		return 1024 * 1024
	case "TB":
		return 1024 * 1024 * 1024 * 1024
	default: // GB
		return 1024 * 1024 * 1024
	}
}

// initFileDistributionStorage 建目录 + 建表（兼容旧库自动补列）
func initFileDistributionStorage(baseDir string) {
	fdStorageDir = filepath.Join(baseDir, "data", "file_distribution")
	os.MkdirAll(fdStorageDir, 0755)

	if _, err := db.Exec(`
	CREATE TABLE IF NOT EXISTS fd_files (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		file_name TEXT NOT NULL,
		file_size INTEGER NOT NULL DEFAULT 0,
		token TEXT NOT NULL UNIQUE,
		mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
		downloads INTEGER NOT NULL DEFAULT 0,
		accesses INTEGER NOT NULL DEFAULT 0,
		alias TEXT NOT NULL DEFAULT '',
		created_at INTEGER NOT NULL
	);
	CREATE INDEX IF NOT EXISTS idx_fd_files_user ON fd_files(user_id);
	`); err != nil {
		log.Printf("  提示: fd_files 表准备: %v", err)
	}
	// 中转加速生成的文件：no_limit=1 → 下载不限速（不套用所有者的速度配置）
	db.Exec("ALTER TABLE fd_files ADD COLUMN no_limit INTEGER NOT NULL DEFAULT 0")
	// 访问计数（?dl=1 计下载，其余计访问）与固定链接别名
	db.Exec("ALTER TABLE fd_files ADD COLUMN accesses INTEGER NOT NULL DEFAULT 0")
	db.Exec("ALTER TABLE fd_files ADD COLUMN alias TEXT NOT NULL DEFAULT ''")
	// 固定链接全局唯一（别名已统一小写）；空串表示未设置，不参与唯一约束
	db.Exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_fd_files_alias ON fd_files(alias) WHERE alias <> ''")

	if _, err := db.Exec(`
	CREATE TABLE IF NOT EXISTS fd_user_configs (
		user_id INTEGER NOT NULL,
		config_key TEXT NOT NULL,
		config_value TEXT NOT NULL,
		updated_at INTEGER NOT NULL DEFAULT 0,
		PRIMARY KEY (user_id, config_key)
	);
	`); err != nil {
		log.Printf("  提示: fd_user_configs 表准备: %v", err)
	}

	// 中转加速任务表
	if _, err := db.Exec(`
	CREATE TABLE IF NOT EXISTS fd_relay_jobs (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		url TEXT NOT NULL,
		status TEXT NOT NULL DEFAULT 'pending',
		file_name TEXT NOT NULL DEFAULT '',
		total INTEGER NOT NULL DEFAULT 0,
		downloaded INTEGER NOT NULL DEFAULT 0,
		error TEXT NOT NULL DEFAULT '',
		fd_id INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL
	);
	CREATE INDEX IF NOT EXISTS idx_fd_relay_user ON fd_relay_jobs(user_id);
	`); err != nil {
		log.Printf("  提示: fd_relay_jobs 表准备: %v", err)
	}

	// 旧版（固定列的 fd_user_config）迁移到键值表后移除；表不存在时静默跳过
	if rows, err := db.Query("SELECT user_id, max_speed_mbps, capacity_value, capacity_unit FROM fd_user_config"); err == nil {
		type migRow struct {
			uid           int64
			speed, capVal float64
			unit          string
		}
		var batch []migRow
		for rows.Next() {
			var r migRow
			if rows.Scan(&r.uid, &r.speed, &r.capVal, &r.unit) == nil {
				batch = append(batch, r)
			}
		}
		rows.Close()
		for _, r := range batch {
			db.Exec("INSERT OR REPLACE INTO fd_user_configs (user_id, config_key, config_value, updated_at) VALUES (?,?,?,?)",
				r.uid, "max_speed", strconv.FormatFloat(r.speed, 'f', -1, 64), time.Now().Unix())
			db.Exec("INSERT OR REPLACE INTO fd_user_configs (user_id, config_key, config_value, updated_at) VALUES (?,?,?,?)",
				r.uid, "capacity_value", strconv.FormatFloat(r.capVal, 'f', -1, 64), time.Now().Unix())
			db.Exec("INSERT OR REPLACE INTO fd_user_configs (user_id, config_key, config_value, updated_at) VALUES (?,?,?,?)",
				r.uid, "capacity_unit", r.unit, time.Now().Unix())
		}
		db.Exec("DROP TABLE fd_user_config")
		if len(batch) > 0 {
			log.Printf("  文件分发: 已迁移 %d 条旧版固定列配置到键值表", len(batch))
		}
	}

	log.Printf("  文件分发存储目录: %s", fdStorageDir)
}

// fdConfig 解析后的生效配置（数值形态）
type fdConfig struct {
	MaxSpeedMbps  float64
	CapacityValue float64
	CapacityUnit  string
}

// fdGetConfigMap 读取某个 user_id 的原始键值配置（含 user_id=0 的默认配置）
func fdGetConfigMap(userID int64) map[string]string {
	result := map[string]string{}
	rows, err := db.Query("SELECT config_key, config_value FROM fd_user_configs WHERE user_id = ?", userID)
	if err != nil {
		return result
	}
	defer rows.Close()
	for rows.Next() {
		var k, v string
		if rows.Scan(&k, &v) == nil {
			result[k] = v
		}
	}
	return result
}

// fdHasCustomConfig 用户是否已单独配置（单独配置的用户不受默认限制约束）
func fdHasCustomConfig(userID int64) bool {
	var n int
	db.QueryRow("SELECT COUNT(*) FROM fd_user_configs WHERE user_id = ?", userID).Scan(&n)
	return n > 0
}

// fdGetEffectiveConfigMap 生效配置：开发者 → 空 map（完全不受任何限制，也不套用默认配置）；
// 已单独配置 → 只用本人的键（缺键=不限）；否则套用默认(通用)配置
func fdGetEffectiveConfigMap(userID int64) map[string]string {
	if isDeveloperUser(userID) {
		return map[string]string{}
	}
	own := fdGetConfigMap(userID)
	if len(own) > 0 {
		return own
	}
	return fdGetConfigMap(fdDefaultConfigUserID)
}

// fdConfigFromMap 键值配置 → 数值配置（缺键/非法值一律按 0/不限处理）
func fdConfigFromMap(m map[string]string) fdConfig {
	cfg := fdConfig{CapacityUnit: "GB"}
	if v, ok := m["max_speed"]; ok {
		if f, err := strconv.ParseFloat(strings.TrimSpace(v), 64); err == nil && f > 0 {
			cfg.MaxSpeedMbps = f
		}
	}
	if v, ok := m["capacity_value"]; ok {
		if f, err := strconv.ParseFloat(strings.TrimSpace(v), 64); err == nil && f > 0 {
			cfg.CapacityValue = f
		}
	}
	if v, ok := m["capacity_unit"]; ok {
		u := strings.ToUpper(strings.TrimSpace(v))
		for _, opt := range fdConfigUnitOptions {
			if u == opt {
				cfg.CapacityUnit = u
				break
			}
		}
	}
	return cfg
}

// getFdConfig 生效配置（数值形态）
func getFdConfig(userID int64) fdConfig {
	return fdConfigFromMap(fdGetEffectiveConfigMap(userID))
}

// fdCapacityBytes 配置的总容量字节数（0 = 不限）
func (c fdConfig) capacityBytes() float64 {
	if c.CapacityValue <= 0 {
		return 0
	}
	return c.CapacityValue * float64(fdUnitBytes(c.CapacityUnit))
}

// fdUsedBytes 用户已用容量
func fdUsedBytes(userID int64) int64 {
	var used int64
	db.QueryRow("SELECT COALESCE(SUM(file_size),0) FROM fd_files WHERE user_id = ?", userID).Scan(&used)
	return used
}

// fdBlockedExts 分发场景只拦截「可脚本渲染/服务端脚本」类型（会被浏览器当网页执行才是真风险），
// APK/EXE 等安装包允许分发——下载时强制 octet-stream + attachment，不会在服务器上执行。
var fdBlockedExts = map[string]bool{
	".html": true, ".htm": true, ".xhtml": true, ".shtml": true,
	".svg": true, ".svgz": true,
	".xml": true, ".xsd": true, ".xsl": true, ".xslt": true, ".dtd": true,
	".php": true, ".php3": true, ".php4": true, ".php5": true, ".phtml": true,
	".jsp": true, ".jspx": true,
	".asp": true, ".aspx": true, ".asax": true, ".ashx": true, ".asmx": true, ".axd": true,
	".cgi": true, ".pl": true, ".py": true, ".rb": true,
	".js": true, ".mjs": true, ".jsx": true, ".ts": true, ".tsx": true, ".vue": true,
	".htaccess": true,
}

// fdHumanSize 人类可读大小
func fdHumanSize(n int64) string {
	f := float64(n)
	switch {
	case f >= 1024*1024*1024:
		return fmt.Sprintf("%.2f GB", f/1024/1024/1024)
	case f >= 1024*1024:
		return fmt.Sprintf("%.2f MB", f/1024/1024)
	case f >= 1024:
		return fmt.Sprintf("%.2f KB", f/1024)
	default:
		return fmt.Sprintf("%d B", n)
	}
}

// fdMapToJSON map → JSONObject 友好形态（保持键名原样）
func fdMapToJSON(m map[string]string) map[string]string {
	if m == nil {
		return map[string]string{}
	}
	return m
}
