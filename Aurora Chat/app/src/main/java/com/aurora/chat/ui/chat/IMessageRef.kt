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
    val mediaWidth: Int get() = 0
    val mediaHeight: Int get() = 0
    val isUploading: Boolean
    /**
     * 上传中的本地媒体预览地址（content:// 或 file://）。
     * 文件此刻就在本机：气泡据此立刻画出真实缩略图与真实宽高比，无需等待网络上传完成，
     * 也不会出现"发送瞬间是一个与内容无关的灰块"。上传结束后该消息被真实消息替换，此字段自然消失。
     */
    val localPreviewUri: String get() = ""
    val toUserId: Long
    val targetName: String
    val flashDuration: Int
    val reasoningText: String get() = ""
    /** AI 思考阶段时长（秒），0 表示无/不可用（非 AI 对话恒为 0） */
    val thinkingSeconds: Long get() = 0
    /** AI 整次任务总时长（秒），0 表示无（非 AI 对话恒为 0） */
    val totalSeconds: Long get() = 0
    /** 本次回复输入 token 数（计费/用量统计，0 表示无/未计费，非 AI 对话恒为 0） */
    val inputTokens: Long get() = 0
    /** 本次回复输出 token 数（计费/用量统计，0 表示无/未计费，非 AI 对话恒为 0） */
    val outputTokens: Long get() = 0
    /** 本次回复缓存命中 token 数（上游未提供时为 0） */
    val cacheHitTokens: Long get() = 0
    /** 本次回复上下文长度（上游未提供时为 0） */
    val contextLength: Long get() = 0
    /** 本次回复扣减的余额（换算后的扣费 token 数，0 表示未扣费/官方模式） */
    val aiCostDeducted: Long get() = 0
    /** AI 本次会话改动过的文件记录（JSON 字符串，空表示无）；供「查看所有改动」全屏界面使用 */
    val aiFileChanges: String get() = ""
    /** AI 本次会话实际调用的工具次数（0 表示无；运行时由采集层计数，历史消息回退读 ai_tool_stages） */
    val aiToolCallCount: Int get() = 0
    /** 该 AI 回复是否被用户手动「停止」（仅 AI 对话可能为 true；用于显示「已手动停止 / 继续」） */
    val manualStopped: Boolean get() = false
    /**
     * 中断记录的 token 数值是否来自**服务端权威结算**（true = 后端 paid_api_usage 真值，false = 客户端估算）。
     * 仅 manualStopped 时有意义；用于 token 明细卡片区分「权威值」与「估算值」。
     */
    val aiUsageAuthoritative: Boolean get() = false
    /** 广播任务 ID：>0 表示这是一条开发者广播消息（可用于长按管理 / 批量撤回）。 */
    val broadcastTaskId: Long get() = 0
    /** 陌生人首条消息来源：search / group:<群名>，非空时在气泡上方渲染系统广播。 */
    val contactSource: String get() = ""
    /**
     * 本条消息是否发送失败（仅本人消息可能为 true）。
     * true 时气泡左侧渲染红色感叹号，点击弹出「重新发送」确认；消息本体保留在会话里，
     * 并已落盘（[com.aurora.chat.data.local.FailedMessageStore]），退出重进不会消失。
     */
    val sendFailed: Boolean get() = false
    /**
     * 服务端持久化的「客户端发送瞬间」（毫秒）。引用胶囊显示的是**被引用那条消息**的发出时间，
     * 用它能拿到精确到毫秒的原始时刻；为 0 时调用方回退 [createdAt]（秒级服务端时间）。
     */
    val clientSendAt: Long get() = 0
    /**
     * 本条消息的 @ 目标映射（发送时昵称 → 用户ID）。
     * 昵称会随改名变化，ID 不会；默认按消息 id 从 [com.aurora.chat.data.store.MentionStore] 取，
     * 因此 StoreMessageRef 不需要单独携带该字段。
     */
    val mentions: Map<String, Long>
        get() = com.aurora.chat.data.store.MentionStore.get(id)
}
