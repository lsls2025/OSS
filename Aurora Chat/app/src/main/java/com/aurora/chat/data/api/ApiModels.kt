package com.aurora.chat.data.api

/**
 * 统一的 API 响应模型
 */
data class ApiResult<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
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
    val sessionKey: String = ""
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
    val broadcastTaskId: Long = 0
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
    val reply_count: Int = 0
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
