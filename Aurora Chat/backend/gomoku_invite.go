package main

import (
	"net/http"
	"strings"
)

// GET /api/gomoku/room?room_id=xxx —— 查询五子棋房间是否仍然可加入。
//
// 用途：五子棋「邀请卡片」需要一个不依赖 WebSocket 的轻量探活。
// 房主（发起方）一旦退出对局，服务端会立刻销毁房间（见 gomokuLeaveRoom），
// 因此「房间不存在」= 邀请已过期，接收方卡片应置灰不可进入。
func handleGomokuRoomStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET", nil)
		return
	}
	roomID := strings.TrimSpace(r.URL.Query().Get("room_id"))
	if roomID == "" {
		writeJSON(w, 400, "参数缺失", nil)
		return
	}

	gomokuMu.Lock()
	room := gomokuRooms[roomID]
	exists := room != nil
	started := false
	full := false
	if room != nil {
		started = room.started
		full = room.players[0] != nil && room.players[1] != nil
	}
	gomokuMu.Unlock()

	writeJSON(w, 200, "ok", map[string]interface{}{
		"exists":   exists,
		"started":  started,
		"full":     full,
		"can_join": exists && !started && !full,
	})
}
