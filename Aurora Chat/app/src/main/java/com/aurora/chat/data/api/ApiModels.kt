package com.aurora.chat.data.api

/**
 * 统一的 API 响应模型
 */
data class ApiResult<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null,
    // HTTP 状态码（仅网络层失败时非 0）：供上层区分"永久失败(4xx，重试无益)"与"可重试(5xx/网络/超时)"
    val httpCode: Int = 0
)

/**
 * 好友关系权威结论（GET /api/friend/status）。
 *
 * isFriend：服务端 friends 表里「我 → 对方」这一行是否存在，与好友列表 / 好友申请同一口径。
 * peerHasMe：对方那一侧是否还留着我（仅诊断用，不参与好友判定）。
 * certain：服务端查库成功、结论可作「百分百判定」。只有它为 true 时客户端才允许
 *          写入本地「非好友标记」并画横幅；缺字段 / 请求失败一律视为未知。
 */
data class FriendRelationResult(
    val isFriend: Boolean = false,
    val peerHasMe: Boolean = false,
    val certain: Boolean = false
)

data class LoginResponse(
    val id: Long,
    val email: String,
    val username: String,
    val signature: String = "",
    val token: String,
    val emailVerified: Boolean = false,
    val needsProfileCompletion: Boolean = false,
    val qqNumber: String = "",
    val sessionKey: String = "",
    /** 服务端动态下发的客户端请求签名密钥（每用户随机生成，仅服务端持有；见 RequestSigner） */
    val signSecret: String = "",
    /** 服务端判定结果：用户是否命中开发者配置（DEV_QQ / DEV_EMAIL 环境变量）；不再内置 QQ/Email 常量做本地比较 */
    val isDeveloper: Boolean = false
)

data class UserInfo(
    val id: Long,
    val email: String,
    val username: String,
    val qqNumber: String = "",
    val createdAt: Long,
    val lastActiveAt: Long = 0,
    val hideEmail: Int = 0,
    val hideQQ: Int = 0,
    val signature: String = "",
    val onlineTimeSeconds: Long = 0,
    val wordCount: Long = 0,
    val regIp: String = "",
    val emailVerified: Boolean = false
)

data class FriendRequestInfo(
    val id: Long,
    val fromUserId: Long,
    val fromEmail: String,
    val fromUsername: String,
    val toUserId: Long = 0,
    val toUsername: String = "",
    val toEmail: String = "",
    val greeting: String,
    val status: String,
    val createdAt: Long
)

// ==================== 用户级黑名单 ====================

/** 黑名单列表条目。iAmBlocker=true 表示「我拉黑了对方」，false 表示「对方拉黑了我」。 */
data class BlacklistItem(
    val userId: Long,
    val username: String,
    val email: String = "",
    val qqNumber: String = "",
    val signature: String = "",
    val iAmBlocker: Boolean = false,
    val blockedAt: Long = 0
)

/** /api/user/is-blocked 的返回。 */
data class IsBlockedResult(
    val isBlocked: Boolean = false,
    val iAmBlocker: Boolean = false
)

data class MessageInfo(
    val id: Long,
    val fromUserId: Long,
    val toUserId: Long,
    val content: String,
    val createdAt: Long,
    val fromUserName: String = "",
    val isRevoked: Int = 0,
    val isSystemNotice: Boolean = false,
    val replyToText: String = "",
    val replyToSender: String = "",
    val replyToId: Long = 0,
    val mediaType: String = "",
    val mediaUrl: String = "",
    val flashDuration: Int = 0,
    val targetName: String = "",
    val msgType: String = "",
    val broadcastTaskId: Long = 0,
    /** 陌生人首条消息来源：search / group:<群名>，用于会话内系统广播 */
    val contactSource: String = "",
    /** 媒体原始宽度（0 表示后端未返回/非媒体消息） */
    val mediaWidth: Int = 0,
    /** 媒体原始高度（0 表示后端未返回/非媒体消息） */
    val mediaHeight: Int = 0,
    /** 本机毫秒时间（客户端事件时刻：发送瞬间/到达瞬间；0 = 未记录，仅在本地持久化，不回传服务端） */
    val localTimeMs: Long = 0,
    /** 服务端持久化的客户端本机毫秒发送时间（发送瞬间 System.currentTimeMillis()；0 = 旧消息/无记录） */
    val clientSendAt: Long = 0,
    /** @ 目标映射（发送时昵称 → 用户ID）：昵称会变，ID 不会 */
    val mentions: Map<String, Long> = emptyMap()
)

data class ConversationInfo(
    val id: Long,
    val email: String,
    val username: String,
    val createdAt: Long,
    val lastMessage: String = "",
    val lastTime: Long = 0
)

// ==================== 社区帖子 ====================

data class CommunityPost(
    val id: Long,
    val user_id: Long,
    val username: String = "",
    val title: String,
    val content: String,
    val created_at: Long = 0,
    val post_type: String = "post",
    val has_liked: Boolean = false,
    val likes_count: Int = 0,
    val comments_count: Int = 0,
    val resources: List<CommunityResource> = emptyList(),
    val is_adult: Boolean = false
)

data class CommunityResource(
    val id: Long = 0,
    val post_id: Long = 0,
    val resource_type: String,
    val file_name: String = "",
    val file_path: String,
    val file_size: Long = 0
)

data class PostListItem(
    val id: Long,
    val user_id: Long,
    val username: String,
    val title: String,
    val content: String,
    val created_at: Long,
    val post_type: String = "post",
    val res_types: String = "",
    val res_exts: String = "",
    val res_count: Int = 0,
    val first_resource_url: String = "",
    val image_urls: List<String> = emptyList(),
    val is_adult: Boolean = false
)

data class PostListResponse(
    val posts: List<PostListItem>,
    val total: Int,
    val page: Int,
    val limit: Int
)

data class CommentInfo(
    val id: Long,
    val user_id: Long,
    val username: String = "",
    val content: String,
    val created_at: Long = 0,
    val parent_id: Long = 0,
    val reply_count: Int = 0,
    /** 该评论被点赞数（后端 community_comment_likes / shortvideo_comment_likes 聚合） */
    val like_count: Int = 0,
    /** 当前用户是否已赞过该评论 */
    val liked: Boolean = false
)

data class CommentListResponse(
    val comments: List<CommentInfo>,
    val total: Int
)

// ==================== 社区动态 ====================

data class CommunityActivity(
    val id: Long,
    val user_id: Long,
    val username: String,
    val action_type: String,
    val target_type: String,
    val target_id: Long,
    val target_title: String = "",
    val comment_content: String = "",
    val created_at: Long = 0,
    var is_read: Boolean = false
)

data class ActivityListResponse(
    val activities: List<CommunityActivity>,
    val total: Int
)

// ==================== 服务器板块 ====================

data class ServerInfo(
    val id: Long,
    val ownerUserId: Long,
    val ownerUsername: String = "",
    val name: String,
    val domain: String,
    val serverUrl: String = "",
    val createdAt: Long = 0,
    val status: String = "offline"
)

// ==================== 服务器文件 ====================

data class ServerFileInfo(
    val id: Long,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String = "",
    val isDir: Boolean = false,
    val parentId: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val downloadUrl: String = ""
)

// 面包屑导航项
data class BreadcrumbItem(
    val id: Long,
    val name: String
)

// ==================== 封禁相关 ====================

data class BanRequest(
    val userId: Long,
    val duration: Long,      // 封禁时长（秒）
    val reason: String,
    val unbanPopupMessage: String = ""  // 解封弹窗提示，为空不弹窗
)

// ==================== 消息已读状态（气泡下方常驻显示用） ====================

/**
 * 一条「我发的」消息的已读情况。
 *  - 群聊：[readCount] = 已读人数（不含发送者自己，0 表示还没有人读）
 *  - 私聊：[otherReadAt] = 对方已读时间（秒），0 表示未读
 */
data class ReadStat(
    val messageId: Long,
    val readCount: Int = 0,
    val otherReadAt: Long = 0
)
