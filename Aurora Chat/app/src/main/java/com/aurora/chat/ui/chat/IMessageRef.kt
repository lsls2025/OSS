package com.aurora.chat.ui.chat

import androidx.compose.runtime.Stable

/**
 * 消息引用通用接口 — ChatMsg 和 StoreMessageRef 都实现此接口，
 * 允许 MessageBubbleItem 使用统一类型。
 * 标注 @Stable 使 MessageBubbleItem 的 msg: IMessageRef 参数被视为稳定类型，
 * 从而恢复 Compose 的跳过重组（skip），长列表交互不再整窗重算。
 */
@Stable
interface IMessageRef {
    val id: Long
    /** 服务端消息 ID；乐观消息在发送成功前为 0，撤回时优先用它，避免用本地临时 id 撤回导致 404。StoreMessageRef 默认等于 id。 */
    val serverId: Long get() = id
    val text: String
    val isMine: Boolean
    val fromUserId: Long
    val senderName: String
    val time: Long
    val isNew: Boolean
    val createdAt: Long
    val isRevoked: Int      // 0=正常, 1=已撤回
    val isSystemNotice: Boolean
    val replyToText: String
    val replyToSender: String
    val replyToId: Long
    val mediaType: String
    val mediaUrl: String
    val isUploading: Boolean
    val toUserId: Long
    val targetName: String
    val flashDuration: Int
    val reasoningText: String get() = ""
    /** AI 思考阶段时长（秒），0 表示无/不可用（非 AI 对话恒为 0） */
    val thinkingSeconds: Long get() = 0
    /** AI 整次任务总时长（秒），0 表示无（非 AI 对话恒为 0） */
    val totalSeconds: Long get() = 0
    /** AI 本次会话改动过的文件记录（JSON 字符串，空表示无）；供「查看所有改动」全屏界面使用 */
    val aiFileChanges: String get() = ""
    /** AI 本次会话实际调用的工具次数（0 表示无；运行时由采集层计数，历史消息回退读 ai_tool_stages） */
    val aiToolCallCount: Int get() = 0
    /** 该 AI 回复是否被用户手动「停止」（仅 AI 对话可能为 true；用于显示「已手动停止 / 继续」） */
    val manualStopped: Boolean get() = false
    /** 广播任务 ID：>0 表示这是一条开发者广播消息（可用于长按管理 / 批量撤回）。 */
    val broadcastTaskId: Long get() = 0
}
