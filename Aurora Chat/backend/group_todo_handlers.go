package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// 群待办（群主/管理员功能）：长按群消息可把该消息设为「群待办」，全员在会话顶部看到提示，
// 点击提示跳转到原消息。群主/管理员可「对所有人撤回」（清除待办，全员横幅消失并提示
// 「某某撤回了一条消息」）或「只对自己撤回」（仅本地隐藏，需客户端配合）。
//
// 服务端只负责「对所有人撤回」：清除 group_todos 并向群成员广播；「只对自己撤回」纯客户端行为，
// 不触发任何服务端写操作。

// POST /api/group/set_todo — 把指定群消息设为群待办（覆盖旧待办）。
func handleSetGroupTodo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		MessageID int64 `json:"message_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.MessageID == 0 {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	msg, err := getMessageByID(req.MessageID)
	if err != nil || msg == nil {
		writeJSON(w, 404, "消息不存在", nil)
		return
	}
	// 仅群消息可设为群待办
	if msg.ToUserID >= 0 {
		writeJSON(w, 400, "该消息不属于群聊", nil)
		return
	}
	groupID := groupConvIDToID(msg.ToUserID)
	role, rerr := getMemberRole(groupID, userID)
	if rerr != nil || (role != "owner" && role != "admin") {
		writeJSON(w, 403, "只有群主或管理员可以设置群待办", nil)
		return
	}
	if !hasGroupPerm(groupID, userID, "group_todo") {
		writeJSON(w, 403, "没有「"+groupPermLabel("group_todo")+"」的权限", nil)
		return
	}
	// 摘要：去除换行并截断，卡片类 JSON 也直接截断（不展开）
	snippet := strings.ReplaceAll(msg.Content, "\n", " ")
	snippet = strings.ReplaceAll(snippet, "\r", "")
	if len([]rune(snippet)) > 80 {
		snippet = string([]rune(snippet)[:80]) + "…"
	}
	if snippet == "" {
		snippet = "[消息]"
	}
	setterName := ""
	if u, _ := findUserByID(userID); u != nil {
		setterName = u.Username
	}
	todo := &GroupTodo{
		GroupID:    groupID,
		MessageID:  req.MessageID,
		SetterID:   userID,
		SetterName: setterName,
		Snippet:    snippet,
		CreatedAt:  time.Now().Unix(),
	}
	if err := setGroupTodo(todo); err != nil {
		writeJSON(w, 500, "设置群待办失败", nil)
		return
	}
	// 广播给其余在线群成员（排除发起者，发起者由客户端本地乐观更新）
	PushGroupTodoEvent(msg.ToUserID, userID, "group_todo_set", map[string]interface{}{
		"group_id":    groupID,
		"message_id":  todo.MessageID,
		"setter_name": todo.SetterName,
		"setter_id":   todo.SetterID,
		"snippet":     todo.Snippet,
		"created_at":  todo.CreatedAt,
	})
	writeJSON(w, 200, "已设为群待办", nil)
}

// POST /api/group/clear_todo — 对所有人撤回群待办（群主/管理员）。
func handleClearGroupTodo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, "仅支持 POST 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	var req struct {
		GroupID int64 `json:"group_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == 0 {
		writeJSON(w, 400, "请求格式错误", nil)
		return
	}
	role, rerr := getMemberRole(req.GroupID, userID)
	if rerr != nil || (role != "owner" && role != "admin") {
		writeJSON(w, 403, "只有群主或管理员可以撤回群待办", nil)
		return
	}
	if !hasGroupPerm(req.GroupID, userID, "group_todo") {
		writeJSON(w, 403, "没有「"+groupPermLabel("group_todo")+"」的权限", nil)
		return
	}
	if err := clearGroupTodo(req.GroupID); err != nil {
		writeJSON(w, 500, "撤回失败", nil)
		return
	}
	// convID = -(1000 + groupID)，用于向群成员广播
	convID := -(1000 + req.GroupID)
	setterName := ""
	if u, _ := findUserByID(userID); u != nil {
		setterName = u.Username
	}
	// 广播给其余在线群成员：横幅消失 + 提示「某某撤回了一条消息」
	PushGroupTodoEvent(convID, userID, "group_todo_cleared", map[string]interface{}{
		"group_id":    req.GroupID,
		"setter_name": setterName,
		"setter_id":   userID,
	})
	writeJSON(w, 200, "已撤回群待办", nil)
}

// GET /api/group/todo?group_id= — 获取群当前生效的待办（群成员可调用）。
func handleGetGroupTodo(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		writeJSON(w, 405, "仅支持 GET 请求", nil)
		return
	}
	userID := getCurrentUserID(r)
	if userID == 0 {
		writeJSON(w, 401, "无法识别用户身份", nil)
		return
	}
	groupIDStr := r.URL.Query().Get("group_id")
	var groupID int64
	if _, err := fmt.Sscanf(groupIDStr, "%d", &groupID); err != nil || groupID <= 0 {
		writeJSON(w, 400, "群 id 无效", nil)
		return
	}
	// 必须是群成员
	role, rerr := getMemberRole(groupID, userID)
	if rerr != nil || role == "" {
		writeJSON(w, 403, "无权访问该群", nil)
		return
	}
	todo, err := getGroupTodo(groupID)
	if err != nil {
		writeJSON(w, 500, "查询失败", nil)
		return
	}
	if todo == nil {
		writeJSON(w, 200, "无群待办", map[string]interface{}{"todo": nil})
		return
	}
	writeJSON(w, 200, "ok", map[string]interface{}{"todo": todo})
}
