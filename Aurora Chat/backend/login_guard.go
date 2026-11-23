package main

import (
	"database/sql"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"
)



const (
	// 账号维度：窗口内累计 5 次失败 → 锁 5 分钟
	loginGuardAcctMax     = 5
	loginGuardAcctLockSec = 300
	// IP 维度：窗口内累计 50 次失败 → 锁 10 分钟（阈值放宽，避免 CGNAT 共享出口误伤整片用户）
	loginGuardIPMax     = 50
	loginGuardIPLockSec = 600
	// 失败计数窗口：超过该时长未再失败则重新计数
	loginGuardWindowSec = 900
)

var loginGuardCleanupOnce sync.Once

// dummyPasswordHash 用于"账号不存在"时也走一次 bcrypt。
// 原实现 `user == nil || bcrypt.Compare(...)` 是短路求值：账号不存在直接跳过 bcrypt，
// 响应约 5ms；账号存在则要跑完 cost 10 的 bcrypt，约 60ms。这个时间差足够枚举出
// 哪些邮箱/用户名真的注册过（文案统一也没用）。这里预生成一个哈希，把两条路径的耗时拉平。
var dummyPasswordHash = func() []byte {
	h, err := bcrypt.GenerateFromPassword([]byte("aurora-timing-equalizer-not-a-real-password"), bcrypt.DefaultCost)
	if err != nil {
		return nil
	}
	return h
}()

// startLoginGuardCleanup 建表并启动过期记录清理（进程内只执行一次）。
func startLoginGuardCleanup() {
	loginGuardCleanupOnce.Do(func() {
		ensureLoginGuardTable()
		go func() {
			ticker := time.NewTicker(10 * time.Minute)
			for range ticker.C {
				// 24 小时没有动静的记录直接删掉：失败计数窗口只有 15 分钟，留着没意义。
				if _, err := db.Exec("DELETE FROM login_guard WHERE updated_at < ?",
					time.Now().Unix()-86400); err != nil {
					log.Printf("[登录熔断] 清理过期记录失败: %v", err)
				}
			}
		}()
	})
}

// ensureLoginGuardTable 幂等建表。放在这里而不是 database.go 的大建表语句里，
// 是为了让这套熔断逻辑自成一体、删掉本文件即可完全移除。
func ensureLoginGuardTable() {
	_, err := db.Exec(`
	CREATE TABLE IF NOT EXISTS login_guard (
		key TEXT PRIMARY KEY,
		fails INTEGER NOT NULL DEFAULT 0,
		first_at INTEGER NOT NULL DEFAULT 0,
		locked_until INTEGER NOT NULL DEFAULT 0,
		updated_at INTEGER NOT NULL DEFAULT 0
	);`)
	if err != nil {
		log.Printf("[登录熔断] 建表失败: %v", err)
	}
}

// loginGuardLocked 查询某个 key 是否处于锁定期，返回 (是否锁定, 剩余秒数)。
func loginGuardLocked(key string) (bool, int) {
	if key == "" {
		return false, 0
	}
	var lockedUntil int64
	err := db.QueryRow("SELECT locked_until FROM login_guard WHERE key = ?", key).Scan(&lockedUntil)
	if err != nil || lockedUntil == 0 {
		return false, 0
	}
	now := time.Now().Unix()
	if lockedUntil > now {
		return true, int(lockedUntil - now)
	}
	return false, 0
}

// loginGuardFail 记一次失败；累计达到 max 时锁定 lockSec 秒。
// 返回 (是否已锁定, 剩余秒数)。
func loginGuardFail(key string, max int, lockSec int) (bool, int) {
	if key == "" {
		return false, 0
	}
	now := time.Now().Unix()
	var fails int
	var firstAt, lockedUntil int64
	err := db.QueryRow("SELECT fails, first_at, locked_until FROM login_guard WHERE key = ?", key).
		Scan(&fails, &firstAt, &lockedUntil)
	if err == sql.ErrNoRows {
		fails, firstAt, lockedUntil = 0, now, 0
	} else if err != nil {
		// 读失败时不阻断登录（宁可少挡一次，也不能因为 DB 抖动把所有人锁死）
		log.Printf("[登录熔断] 读取失败计数失败: %v", err)
		return false, 0
	}

	// 距离第一次失败已超出窗口 → 重新计数
	if firstAt == 0 || now-firstAt > loginGuardWindowSec {
		fails, firstAt = 0, now
	}
	fails++

	newLocked := lockedUntil
	if fails >= max {
		newLocked = now + int64(lockSec)
	}
	// INSERT OR REPLACE：整行覆盖，字段全部现算，无需依赖 SQLite 的 upsert 语法版本
	_, err = db.Exec(`INSERT OR REPLACE INTO login_guard (key, fails, first_at, locked_until, updated_at)
		VALUES (?, ?, ?, ?, ?)`, key, fails, firstAt, newLocked, now)
	if err != nil {
		log.Printf("[登录熔断] 写入失败计数失败: %v", err)
		return false, 0
	}
	if newLocked > now {
		log.Printf("[登录熔断] %s 连续失败 %d 次，锁定 %d 秒", key, fails, newLocked-now)
		return true, int(newLocked - now)
	}
	return false, 0
}

// loginGuardReset 密码校验通过后清空该 key 的失败记录。
func loginGuardReset(key string) {
	if key == "" {
		return
	}
	if _, err := db.Exec("DELETE FROM login_guard WHERE key = ?", key); err != nil {
		log.Printf("[登录熔断] 重置失败记录失败: %v", err)
	}
}

// loginGuardAccountKey 账号维度的 key。
// 能定位到真实用户时以 userID 为键——否则同一个账号用「邮箱 / 用户名 / QQ 号」三种写法
// 会各占一份失败额度，等于把 5 次放大到 15 次。定位不到时退回输入串，同样参与计数。
func loginGuardAccountKey(userID int64, input string) string {
	if userID > 0 {
		return fmt.Sprintf("acct:%d", userID)
	}
	return "acct-input:" + strings.ToLower(strings.TrimSpace(input))
}

// loginGuardIPKey IP 维度的 key。
func loginGuardIPKey(ip string) string {
	if ip == "" {
		return ""
	}
	return "ip:" + ip
}

// isKnownLoginIP 判断该 IP 是否在这个账号最近 30 天的成功登录记录里。
// 用于豁免账号锁定，避免"攻击者用 5 次错密码把正常用户锁死"的锁定型拒绝服务。
func isKnownLoginIP(userID int64, ip string) bool {
	if userID <= 0 || ip == "" {
		return false
	}
	var n int
	err := db.QueryRow(`SELECT COUNT(*) FROM login_logs
		WHERE user_id = ? AND login_ip = ? AND login_time > ?`,
		userID, ip, time.Now().Unix()-30*86400).Scan(&n)
	if err != nil {
		return false
	}
	return n > 0
}

// verifyPasswordConstantTime 统一的密码校验入口：
// 账号不存在时也跑一次 bcrypt，消除响应时间差，堵掉"按耗时枚举已注册账号"的侧信道。
func verifyPasswordConstantTime(storedHash, password string) bool {
	hash := storedHash
	// 账号不存在（无哈希）也必须跑满一次 bcrypt，否则耗时差异就是"该账号是否存在"的答案
	if hash == "" {
		if dummyPasswordHash == nil {
			return false
		}
		hash = string(dummyPasswordHash)
	}
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
}
