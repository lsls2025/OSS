package main

import "log"

// getEffectiveTokenBalance 返回用户对外展示/可花费的"有效余额"。
// 数据库中 token_balance 列即为此有效余额（含会员/充值/卡密等各项奖励，直接计入该列、不清零），
// token_balance_perm/token_balance_free 为内部拆分池，不在此直接返回。
func getEffectiveTokenBalance(userID int64) int64 {
	var b int64
	if err := db.QueryRow(`SELECT COALESCE(token_balance, 0) FROM users WHERE id = ?`, userID).Scan(&b); err != nil {
		log.Printf("查询有效余额失败 user=%d: %v", userID, err)
		return 0
	}
	return b
}