package main

import (
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
)

// ============================================================================
// 五子棋 WebSocket 连接层（gorilla/websocket）
// /ws 路由注册在 main.go 的 mux 上（同端口 :5004），与现有 API 共用中间件链。
// ============================================================================

// gomokuUpgrader 允许任意来源（Android 原生 WebSocket 无 Origin 限制）。
var gomokuUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

// gomokuConn 一个五子棋 WebSocket 连接。
// 读循环与写循环分离：读在主 goroutine（ReadMessage），写经 sendCh 投递。
type gomokuConn struct {
	ws         *websocket.Conn
	sendCh     chan []byte
	closed     bool
	roomID     string
	color      int
	devFlipped bool
	spectator  bool
	matching   bool
	playerID   string
	playerData *gomokuPlayer
	// userID 平台用户号（由 WS 握手时的登录态解析）。赌注要真实扣/加 token，必须确认"你是哪个平台用户"，
	// 不能听客户端自报，否则任何人都能冒充别人下注。未登录/校验失败时为 0：仍可正常对局，只是不能下注。
	userID int64
}

// handleGomokuWS /ws 路由处理器：升级连接并进入读循环。
func handleGomokuWS(w http.ResponseWriter, r *http.Request) {
	ws, err := gomokuUpgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[五子棋] WebSocket 升级失败: %v", err)
		return
	}
	// 解析登录态：赌注涉及真实 token 转移，用户身份必须由后端从 token 里解析，绝不能听客户端自报。
	// 客户端在 WS 握手时带 Authorization: Bearer <登录 token>；没带或无效时 userID=0（仍可对局，仅不能下注）。
	userID := int64(0)
	if tk := extractToken(r); tk != "" {
		if uid, _, _, err := validateToken(tk); err == nil {
			userID = uid
		} else {
			log.Printf("[五子棋] WS 登录态校验失败，按未登录处理: %v", err)
		}
	}
	conn := &gomokuConn{
		ws:     ws,
		sendCh: make(chan []byte, 256),
		userID: userID,
	}
	gomokuMu.Lock()
	gomokuConns[conn] = true
	// 新连接先拿到房间列表
	gomokuSend(conn, map[string]interface{}{"type": "roomList", "rooms": gomokuGetList()})
	gomokuMu.Unlock()

	// 写循环（含心跳）
	go gomokuWriteLoop(conn)
	// 读循环（阻塞直到断开）
	gomokuReadLoop(conn)
}

// gomokuReadLoop 读循环：逐条解析 JSON 消息并交给 gomokuHandle。
func gomokuReadLoop(conn *gomokuConn) {
	defer func() {
		gomokuMu.Lock()
		delete(gomokuConns, conn)
		conn.closed = true
		// 匹配等待中掉线：自动出队
		gomokuRemoveFromQueue(conn)
		// 离开房间并通知其他人
		gomokuLeaveRoom(conn)
		gomokuMu.Unlock()
		conn.ws.Close()
	}()
	conn.ws.SetReadLimit(4096)
	conn.ws.SetReadDeadline(time.Now().Add(90 * time.Second))
	conn.ws.SetPongHandler(func(string) error {
		conn.ws.SetReadDeadline(time.Now().Add(90 * time.Second))
		return nil
	})
	for {
		_, data, err := conn.ws.ReadMessage()
		if err != nil {
			return
		}
		var msg map[string]interface{}
		if json.Unmarshal(data, &msg) != nil {
			continue
		}
		gomokuMu.Lock()
		gomokuHandle(conn, msg)
		gomokuMu.Unlock()
	}
}

// gomokuWriteLoop 写循环：消费 sendCh，同时定期发 Ping 维持连接。
func gomokuWriteLoop(conn *gomokuConn) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case data := <-conn.sendCh:
			if err := conn.ws.WriteMessage(websocket.TextMessage, data); err != nil {
				return
			}
		case <-ticker.C:
			if err := conn.ws.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
