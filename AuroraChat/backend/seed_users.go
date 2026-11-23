//go:build ignore

package main

import (
	"database/sql"
	"fmt"
	"log"
	"time"

	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

func main() {
	// 请根据实际路径修改
	dbPath := "./data/aurora_chat.db"
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		log.Fatalf("无法打开数据库: %v", err)
	}
	defer db.Close()

	hashedPassword, _ := bcrypt.GenerateFromPassword([]byte("123456"), bcrypt.DefaultCost)
	now := time.Now().Unix()

	for i := 1; i <= 20; i++ {
		email := fmt.Sprintf("test%02d@test.com", i)
		username := fmt.Sprintf("测试用户%02d", i)

		_, err := db.Exec(
			"INSERT OR IGNORE INTO users (email, username, password, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
			email, username, string(hashedPassword), now-int64(i)*3600, now-int64(i)*1800,
		)
		if err != nil {
			log.Printf("插入 %s 失败: %v", email, err)
		} else {
			fmt.Printf("✓ 已创建: %s / %s (密码已设置，不显示)\n", email, username)
		}
	}
	fmt.Println("\n完成！共创建 20 个测试账号（密码不显示，避免明文入日志）")
}
