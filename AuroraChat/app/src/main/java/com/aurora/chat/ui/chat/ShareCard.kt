@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import com.aurora.chat.ui.components.BubbleColorStorage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Stable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.util.ImageSizeFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 【分享协议规约 — 所有"转发/分享给好友或群聊"必须走这里，严禁另起炉灶】
 *
 * 本项目唯一的分享通道就是本文件的「分享卡片协议」：
 *   发送端：ui/community/SharePostDialog.kt（选人面板 + 官方群限制 + 发送）
 *   消息体：分享卡片文本（前缀「分享XX」+ ━━ 分隔 + 标题/摘要 + 来自 + <xxx_id>=ID）
 *   接收端：本文件 isShareCardMessage → parseShareCard → ShareCard 渲染 + 点击分发
 *
 * 新增可分享内容类型（如短视频）时的唯一正确做法：
 *   1. 在 isShareCardMessage / parseShareCard / ShareCardData / ShareCard 各处各加一个分支；
 *   2. 发送端调用方复用 SharePostDialog（用 customMessage 传完整卡片文本）；
 *   3. 点击跳转接线沿用 MessageBubble 既有 ShareCard 调用点新增的回调参数。
 *
 * 严禁（历史上已因此返工）：
 *   - 在 MessageBubble 里新增 mediaType 分支自绘分享卡片；
 *   - 另写选人/发送逻辑绕过 SharePostDialog。
 */

/** 从文本中检测是否为分享卡片消息（兼容"分享帖子"、"分享资源"、"分享群聊"、"分享文件"、"分享用户"、"分享聊天"、"分享短视频"） */
fun isShareCardMessage(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed.startsWith("分享帖子") || trimmed.startsWith("分享资源") || trimmed.startsWith("分享群聊") || trimmed.startsWith("分享文件") || trimmed.startsWith("分享用户") || trimmed.startsWith("分享聊天") || trimmed.startsWith("分享短视频")
}

/** 检测是否为群聊分享卡片 */
fun isShareGroupCard(text: String): Boolean {
    return text.trimStart().startsWith("分享群聊")
}

// ==================== 清空聊天记录卡片 ====================

/** 检测是否为"申请清空聊天记录"请求卡片 */
fun isClearChatRequestCard(text: String): Boolean {
    return text.trimStart().startsWith("清空聊天记录请求")
}

/** 检测是否为"清空聊天记录"回应卡片（已拒绝） */
fun isClearChatRejectCard(text: String): Boolean {
    return text.trimStart().startsWith("清空聊天记录\n")
}

/** 解析请求卡片中的发起方 ID */
fun parseClearChatRequestCard(text: String): Long {
    val idLine = text.lines().lastOrNull { it.contains("requester_id=") } ?: return 0L
    return idLine.removePrefix("requester_id=").trim().toLongOrNull() ?: 0L
}

/** 提取回应卡片内的正文（━━ 之间的那一行） */
fun parseClearChatRejectText(text: String): String {
    val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val idx = lines.indexOfFirst { it.startsWith("━━") }
    return if (idx in 0 until lines.size - 1) lines[idx + 1] else text
}

/**
 * 申请清空聊天记录卡片：请求方看到"已发送，等待确认"；接收方看到"同意/拒绝"按钮。
 * 同意 → 调用后端双向永久删除会话数据；拒绝 → 通知对方。
 */
@Composable
fun ClearChatCard(
    text: String,
    isMine: Boolean,
    onBubbleLongPress: () -> Unit = {}
) {
    val isRequest = isClearChatRequestCard(text)
    val requesterId = if (isRequest) parseClearChatRequestCard(text) else 0L
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val submitting = remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .clip(RoundedCornerShape(16.dp))
            // 深色主题下给卡片一圈细边（浅色主题为透明，观感不变）
            .border(1.dp, com.aurora.chat.ui.theme.appCardOutline(), RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onBubbleLongPress
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardSurface()),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                if (isRequest) "清空聊天记录请求" else "清空聊天记录",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            Spacer(Modifier.height(8.dp))
            if (isRequest) {
                Text(
                    "对方申请清空与你的聊天记录\n同意后将永久删除服务器数据",
                    fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()
                )
                if (!isMine) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = {
                                if (submitting.value || requesterId <= 0) return@TextButton
                                submitting.value = true
                                scope.launch {
                                    val r = runCatching { AuroraApi.rejectClearChat(requesterId) }.getOrNull()
                                    withContext(Dispatchers.Main) {
                                        submitting.value = false
                                        Toast.makeText(ctx, if (r != null && r.success) "已拒绝" else (r?.message ?: "操作失败"), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) { Text("拒绝", color = Color(0xFFDC2626)) }
                        TextButton(onClick = {
                                if (submitting.value || requesterId <= 0) return@TextButton
                                submitting.value = true
                                scope.launch {
                                    val r = runCatching { AuroraApi.agreeClearChat(requesterId) }.getOrNull()
                                    withContext(Dispatchers.Main) {
                                        submitting.value = false
                                        // 不再提示"已清空聊天记录"：清空后本就会空，说这句反而像多此一举；
                                        // 只在失败时给出原因。
                                        if (r == null || !r.success) {
                                            Toast.makeText(ctx, r?.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }) { Text("同意", color = Color(0xFF059669)) }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text("已发送，等待对方确认", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else {
                Text(parseClearChatRejectText(text), fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            }
        }
    }
}

// ==================== 隐私锁定卡片 ====================

/** 检测是否为隐私锁定请求卡片 */
fun isPrivacyRequestCard(text: String): Boolean {
    return text.trimStart().startsWith("隐私锁定请求")
}

/** 检测是否为隐私锁定回应卡片 */
fun isPrivacyResponseCard(text: String): Boolean {
    val t = text.trimStart()
    return t.startsWith("隐私锁定\n") || t.contains("privacy_accept=") || t.contains("privacy_reject=")
}

/** 检测是否为"拉我进群"邀请卡片 */
fun isInviteGroupCard(text: String): Boolean {
    return text.trimStart().startsWith("邀请入群")
}

/** 检测是否为"群聊邀请结果"系统广播（双方可见、按查看者个性化文案） */
fun isGroupConsentNotice(text: String): Boolean {
    return text.trimStart().startsWith("群聊邀请结果")
}

/**
 * 将"群聊邀请结果"广播文本转换为当前查看者的个性化文案。
 * @param isMine 当前查看者是否为应答方（fromUserId == 当前用户）
 */
fun parseGroupConsentNotice(text: String, isMine: Boolean): String {
    val lines = text.split("\n").map { it.trim() }
    val approve = lines.getOrNull(1) == "1"
    return if (isMine) {
        if (approve) "你已同意入群" else "你已拒绝"
    } else {
        if (approve) "对方已同意入群" else "对方已拒绝"
    }
}

/** 群邀请卡片解析结果 */
data class InviteGroupCardData(
    val groupId: Long,
    val inviterId: Long,
    val groupName: String,
    val inviterName: String
)

/** 解析"拉我进群"邀请卡片 */
fun parseInviteGroupCard(text: String): InviteGroupCardData? {
    if (!isInviteGroupCard(text)) return null
    return try {
        val lines = text.split("\n").map { it.trim() }
        val groupId = lines.firstOrNull { it.startsWith("invite_group_id=") }
            ?.removePrefix("invite_group_id=")?.toLongOrNull() ?: 0L
        val inviterId = lines.firstOrNull { it.startsWith("inviter_id=") }
            ?.removePrefix("inviter_id=")?.toLongOrNull() ?: 0L
        if (groupId <= 0 || inviterId <= 0) return null
        // 群名与邀请人：卡片第二段 ━ 之后第一行是群名，第三行是"某 邀请你进入群聊"
        val titleIdx = lines.indexOfFirst { it.startsWith("━━") }
        val groupName = lines.getOrNull(titleIdx + 1) ?: ""
        val inviterLine = lines.getOrNull(titleIdx + 2) ?: ""
        val inviterName = inviterLine.substringBefore(" 邀请你进入群聊").trim()
        InviteGroupCardData(groupId, inviterId, groupName, inviterName)
    } catch (_: Exception) { null }
}

data class PrivacyCardData(
    val type: String, // "request", "accept", "reject"
    val fromUsername: String = "",
    val fromUserId: Long = 0,
    val toUserId: Long = 0
)

fun parsePrivacyCard(text: String): PrivacyCardData? {
    return try {
        val cleaned = text.trim()
        if (cleaned.startsWith("隐私锁定请求")) {
            val lines = cleaned.split("\n").map { it.trim() }
            val fromLine = lines.getOrNull(1) ?: ""
            val username = fromLine.removePrefix("━━━━━━━━━━").trim()
            val idLine = lines.lastOrNull { it.contains("privacy_request_id=") } ?: ""
            val ids = idLine.removePrefix("privacy_request_id=").split("_")
            PrivacyCardData("request", username, ids.getOrNull(0)?.toLongOrNull() ?: 0, ids.getOrNull(1)?.toLongOrNull() ?: 0)
        } else if (cleaned.startsWith("取消锁定请求")) {
            val lines = cleaned.split("\n").map { it.trim() }
            val idLine = lines.lastOrNull { it.contains("unlock_request=") } ?: ""
            val ids = idLine.removePrefix("unlock_request=").split("_")
            PrivacyCardData("unlock_request", "", ids.getOrNull(0)?.toLongOrNull() ?: 0, ids.getOrNull(1)?.toLongOrNull() ?: 0)
        } else if (cleaned.startsWith("取消锁定")) {
            val acceptLine = cleaned.lines().lastOrNull { it.contains("unlock_accept=") }
            val rejectLine = cleaned.lines().lastOrNull { it.contains("unlock_reject=") }
            if (acceptLine != null) {
                val ids = acceptLine.removePrefix("unlock_accept=").split("_")
                PrivacyCardData("unlock_accept", "", ids.getOrNull(1)?.toLongOrNull() ?: 0, ids.getOrNull(0)?.toLongOrNull() ?: 0)
            } else if (rejectLine != null) {
                val ids = rejectLine.removePrefix("unlock_reject=").split("_")
                PrivacyCardData("unlock_reject", "", ids.getOrNull(1)?.toLongOrNull() ?: 0, ids.getOrNull(0)?.toLongOrNull() ?: 0)
            } else null
        } else if (cleaned.startsWith("隐私锁定")) {
            val acceptLine = cleaned.lines().lastOrNull { it.contains("privacy_accept=") }
            val rejectLine = cleaned.lines().lastOrNull { it.contains("privacy_reject=") }
            if (acceptLine != null) {
                val ids = acceptLine.removePrefix("privacy_accept=").split("_")
                PrivacyCardData("accept", "", ids.getOrNull(1)?.toLongOrNull() ?: 0, ids.getOrNull(0)?.toLongOrNull() ?: 0)
            } else if (rejectLine != null) {
                val ids = rejectLine.removePrefix("privacy_reject=").split("_")
                PrivacyCardData("reject", "", ids.getOrNull(1)?.toLongOrNull() ?: 0, ids.getOrNull(0)?.toLongOrNull() ?: 0)
            } else null
        } else null
    } catch (_: Exception) { null }
}

/** 解析分享卡片消息，返回 (title, preview, username, postId) */
fun parseShareCard(text: String): ShareCardData? {
    if (!isShareCardMessage(text)) return null
    try {
        val cleaned = text.trim()
        // 提取第一行作为类型标识
        val firstLine = cleaned.substringBefore("\n").trim()
        val targetType = when {
            firstLine.contains("分享资源") -> "resource"
            firstLine.contains("分享群聊") -> "group"
            firstLine.contains("分享文件") -> "file"
            firstLine.contains("分享用户") -> "user"
            firstLine.contains("分享聊天") -> "chat"
            firstLine.contains("分享短视频") -> "shortvideo"
            else -> "post"
        }
        val shareType = when (targetType) {
            "resource" -> "资源"
            "group" -> "群聊"
            "file" -> "文件"
            "user" -> "用户"
            "chat" -> "聊天"
            "shortvideo" -> "短视频"
            else -> "帖子"
        }
        val lines = cleaned.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        // 格式: 分享群聊/分享帖子/分享资源/分享文件 / ━━━ / Title / Preview / ━━━ / 来自 Name / <idKey>=ID
        val titleIdx = lines.indexOfFirst { it.startsWith("━━") }
        val endIdx = lines.indexOfLast { it.startsWith("━━") }
        if (titleIdx < 0 || endIdx <= titleIdx || endIdx + 2 >= lines.size) return null

        val title = lines.getOrNull(titleIdx + 1) ?: ""
        val preview = if (endIdx - titleIdx > 2) {
            lines.subList(titleIdx + 2, endIdx).joinToString(" ")
        } else ""
        val fromLine = lines.getOrNull(endIdx + 1) ?: ""
        val username = fromLine.removePrefix("来自").trim()
        val idLine = lines.getOrNull(endIdx + 2) ?: ""
        val idRaw = idLine.removePrefix("post_id=").removePrefix("group_id=").removePrefix("file_id=").removePrefix("user_id=").removePrefix("chat_id=").removePrefix("shortvideo_id=").trim()
        val postId = idRaw.toLongOrNull() ?: 0L
        val filePath = lines.firstOrNull { it.startsWith("file_path=") }
            ?.removePrefix("file_path=")?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        // 短视频分享：视频地址 / 封面 / 发布者名（发送端在 id 行之后追加的键值行）
        val videoUrl = lines.firstOrNull { it.startsWith("video_url=") }
            ?.removePrefix("video_url=")?.trim().orEmpty()
        val coverUrl = lines.firstOrNull { it.startsWith("cover_url=") }
            ?.removePrefix("cover_url=")?.trim().orEmpty()
        val publisherName = lines.firstOrNull { it.startsWith("publisher=") }
            ?.removePrefix("publisher=")?.trim().orEmpty()
        // 「分享聊天」：卡片正文是内嵌的消息快照（msgs=[...]）
        val chatMessages = if (targetType == "chat") parseChatCardMessages(lines) else emptyList()

        if (postId <= 0) return null
        return ShareCardData(title, preview, username, postId, shareType, targetType, filePath, chatMessages, videoUrl, coverUrl, publisherName)
    } catch (_: Exception) {
        return null
    }
}

data class ShareCardData(
    val title: String,
    val preview: String,
    val username: String,
    val postId: Long,
    val shareType: String = "帖子",
    val targetType: String = "post",   // "post" | "resource" | "group" | "file" | "user" | "chat" | "shortvideo"
    val filePath: String = "",
    /** 仅 targetType == "chat"：卡片内要还原的聊天消息快照 */
    val chatMessages: List<ChatCardMessage> = emptyList(),
    /** 仅 targetType == "shortvideo"：视频地址（无封面时取首帧用） */
    val videoUrl: String = "",
    /** 仅 targetType == "shortvideo"：服务端封面图 URL */
    val coverUrl: String = "",
    /** 仅 targetType == "shortvideo"：发布者名（视频内左下角标注用） */
    val publisherName: String = ""
)

/**
 * 「分享聊天」卡片内的一条消息快照。
 *
 * 实现 [IMessageRef]：卡片快照只序列化了 8 个展示字段，其余消息属性按「只读、无副作用」给默认值，
 * 这样单聊渲染模块（MessageBubble）能以与真实消息完全相同的契约消费它，无需任何自绘适配层。
 */
@Stable
data class ChatCardMessage(
    override val isMine: Boolean = false,
    override val fromUserId: Long = 0L,
    override val senderName: String = "",
    override val mediaType: String = "",
    override val mediaUrl: String = "",
    /** 视频原始宽高（发送端从消息元数据带入；0=未知，接收端用 MediaMetadataRetriever 嗅探） */
    override val mediaWidth: Int = 0,
    override val mediaHeight: Int = 0,
    override val text: String = "",
    /** 解析顺序号：卡片快照不带服务端消息 id，用作 LazyColumn 稳定 key 与 IMessageRef.id。 */
    val ordinal: Int = 0
) : IMessageRef {
    override val id: Long get() = ordinal.toLong() + 1L
    override val time: Long get() = 0L
    override val isNew: Boolean get() = false
    override val createdAt: Long get() = 0L
    override val isRevoked: Int get() = 0
    override val isSystemNotice: Boolean get() = false
    override val replyToText: String get() = ""
    override val replyToSender: String get() = ""
    override val replyToId: Long get() = 0L
    override val isUploading: Boolean get() = false
    override val toUserId: Long get() = 0L
    override val targetName: String get() = ""
    override val flashDuration: Int get() = 0
}

/** 解析「分享聊天」卡片中内嵌的消息快照（msgs= 载荷，Base64 编码）。 */
fun parseChatCardMessages(lines: List<String>): List<ChatCardMessage> {
    val raw = lines.firstOrNull { it.startsWith("msgs=") }?.removePrefix("msgs=")?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()
    val arr = decodeChatCardPayload(raw) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        ChatCardMessage(
            isMine = o.optInt("m", 0) == 1,
            fromUserId = o.optLong("f", 0L),
            senderName = o.optString("n", ""),
            mediaType = o.optString("t", ""),
            mediaUrl = o.optString("u", ""),
            mediaWidth = o.optInt("w", 0),
            mediaHeight = o.optInt("h", 0),
            text = o.optString("x", ""),
            ordinal = i
        )
    // 历史卡片里可能残留「……（更多消息已省略）」这类系统行：解析阶段直接丢弃，任何界面都不再显示
    }.filter { it.mediaType != "system" }
}

/**
 * 解码卡片载荷。
 * 新格式为 URL-Safe Base64：服务端会对消息正文做 `html.EscapeString`（把 " < > & ' 转成实体），
 * 明文 JSON 会被转成 &#34; 而解析失败，Base64 的字母表不含这些字符，天然免疫。
 * 旧格式（明文 JSON）仍兼容，解析前先还原 HTML 实体。
 */
private fun decodeChatCardPayload(raw: String): org.json.JSONArray? {
    try {
        val bytes = android.util.Base64.decode(raw, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        return org.json.JSONArray(String(bytes, Charsets.UTF_8))
    } catch (_: Exception) { }
    return try {
        org.json.JSONArray(unescapeHtmlEntities(raw))
    } catch (_: Exception) { null }
}

/** 还原服务端 html.EscapeString 产生的实体（&amp; 必须最后替换，避免二次解码）。 */
private fun unescapeHtmlEntities(s: String): String = s
    .replace("&#34;", "\"")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

/**
 * 这条消息能否被分享进「分享聊天」卡片。
 * 防护：闪照（含已被对方/自己销毁的）、已撤回、系统通知一律不可分享，
 * 否则会把不该出现的内容（乃至闪照原图）泄露给接收方。
 */
fun isShareableChatMessage(m: IMessageRef, excludeIds: Set<Long> = emptySet()): Boolean =
    m.flashDuration == 0 && m.isRevoked == 0 && !m.isSystemNotice && m.id !in excludeIds

/**
 * 把多选的消息序列化为「分享聊天」卡片文本。
 * 走既有分享卡片协议（文本块），因此不新增后端字段；正文用 Base64(JSON) 内嵌以保留消息结构，
 * 同时避免服务端 html.EscapeString 破坏载荷。不截断任何消息（长度上限由调用方把控）。
 */
fun buildChatShareCard(
    messages: List<IMessageRef>,
    author: String,
    excludeIds: Set<Long> = emptySet()
): String {
    val anchorId = messages.firstOrNull { it.id > 0 }?.id ?: 1L
    val arr = org.json.JSONArray()
    messages.forEach { m ->
        if (!isShareableChatMessage(m, excludeIds)) return@forEach
        // 空字段一律不写：不带条数限制后，载荷越小越省内存/流量，也越接近后端单条消息上限
        val o = org.json.JSONObject()
        o.put("m", if (m.isMine) 1 else 0)
        if (m.fromUserId != 0L) o.put("f", m.fromUserId)
        if (m.senderName.isNotEmpty()) o.put("n", m.senderName)
        if (m.mediaType.isNotEmpty()) o.put("t", m.mediaType)
        if (m.mediaUrl.isNotEmpty()) o.put("u", m.mediaUrl)
        // 视频宽高：带入后接收端可按真实比例渲染缩略图，无需再联网嗅探
        if (m.mediaWidth > 0) o.put("w", m.mediaWidth)
        if (m.mediaHeight > 0) o.put("h", m.mediaHeight)
        if (m.text.isNotEmpty()) o.put("x", m.text)
        arr.put(o)
    }
    val payload = android.util.Base64.encodeToString(
        arr.toString().toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
    )
    return buildString {
        append("分享聊天\n")
        append("━━━━━━━━━━\n")
        append("分享聊天\n")
        append("━━━━━━━━━━\n")
        append("来自 ${author}\n")
        append("chat_id=$anchorId\n")
        append("msgs=$payload")
    }
}

@Composable
fun ShareCard(
    text: String,
    isMine: Boolean,
    onPostClick: ((Long) -> Unit)? = null,
    onGroupClick: ((Long) -> Unit)? = null,  // group_id → 打开群详情
    onUserClick: ((Long) -> Unit)? = null,   // user_id → 打开用户资料
    onFileClick: ((String) -> Unit)? = null, // file_path → 打开/查看分享文件
    onShortVideoClick: ((Long) -> Unit)? = null, // shortvideo_id → 切入短视频模块并定位
    onChatClick: ((String) -> Unit)? = null, // 分享聊天的原始文本 → 打开全屏查看页
    // 社区内嵌卡片专用：framed 加边框以便在白底上与背景区分；showFrom 控制是否显示"来自"；compact 紧凑尺寸（列表预览）
    framed: Boolean = false,
    showFrom: Boolean = true,
    compact: Boolean = false,
    onBubbleLongPress: () -> Unit = {},
    /** 我方气泡配色（默认微信绿），用于卡片内嵌的我方消息气泡 */
    myBubbleColor: Color = BubbleColorStorage.DEFAULT,
    /** 对方气泡配色（默认白色），用于卡片内嵌的对方消息气泡 */
    otherBubbleColor: Color = BubbleColorStorage.DEFAULT_OTHER
) {
    // 解析结果按文本缓存：卡片在滚动/重组中会被反复执行，而 Base64+JSON 解析是 O(n) 且带分配的开销。
    // 共享消息可能很长，必须避免每帧重解析。
    val data = remember(text) { parseShareCard(text) }

    // 如果解析失败，尝试从最后一行扣 id
    val fallbackId = runCatching {
        text.lines().lastOrNull { it.contains("post_id=") || it.contains("group_id=") || it.contains("file_id=") || it.contains("shortvideo_id=") }
            ?.let { line ->
                line.removePrefix("post_id=").removePrefix("group_id=").removePrefix("file_id=").removePrefix("shortvideo_id=").trim().toLongOrNull()
            }
    }.getOrNull() ?: 0L

    val postId = data?.postId ?: fallbackId
    val title = data?.title?.takeIf { it.isNotBlank() } ?: "分享内容"
    val preview = data?.preview ?: ""
    val username = data?.username?.takeIf { it.isNotBlank() } ?: ""
    val shareType = data?.shareType ?: "帖子"
    val targetType = data?.targetType ?: "post"
    val isGroup = targetType == "group"
    val isUser = targetType == "user"
    val isFile = targetType == "file"
    val isChat = targetType == "chat"
    val isShortVideo = targetType == "shortvideo"

    if (postId <= 0) {
        Text(
            text = text,
            fontSize = 15.sp,
            color = com.aurora.chat.ui.theme.appTextPrimary(),
            modifier = Modifier.padding(vertical = 4.dp)
        )
        return
    }

    // 短视频分享：不用白底卡片外壳，直接渲染视频缩略图（视频内左下角标注发布者名）。
    // 封面为空且无视频地址时才回退成普通卡片，保证老消息不丢内容。
    if (isShortVideo && (data?.coverUrl?.isNotEmpty() == true || data?.videoUrl?.isNotEmpty() == true)) {
        ShortVideoShareThumb(
            coverUrl = data?.coverUrl.orEmpty(),
            videoUrl = data?.videoUrl.orEmpty(),
            publisherName = data?.publisherName?.ifBlank { username } ?: username,
            onClick = { onShortVideoClick?.invoke(postId) },
            onLongClick = onBubbleLongPress
        )
        return
    }

    // 资源与帖子已合并为统一「分享」类型，群聊单独保留「分享群聊」，文件用「分享文件」区分
    val label = when (shareType) {
        "群聊" -> "分享群聊"
        "文件" -> "分享文件"
        "用户" -> "分享用户"
        // 卡片左上角标签：聊天记录就叫「聊天记录」，不再写「分享聊天」
        "聊天" -> "聊天记录"
        "短视频" -> "分享短视频"
        else -> "分享"
    }

    // 「分享聊天」收敛为矮卡：圆角 10dp、内边距 10dp；其它分享类型外壳保持原样，避免回归
    val cardCorner = if (isChat) 10.dp else 16.dp
    val cardPadding = if (isChat) 10.dp else if (compact) 10.dp else 14.dp
    Card(
        modifier = Modifier
            .widthIn(min = if (compact) 150.dp else 200.dp, max = if (compact) 200.dp else 260.dp)
            .clip(RoundedCornerShape(cardCorner))
            .then(
                // 深色主题下页面与卡片同为深色，必须描一圈细边才有边界感；
                // 浅色主题 appCardOutline() 返回透明，观感与改动前一致。
                if (framed || com.aurora.chat.ui.theme.isAppDark())
                    Modifier.border(1.dp, if (framed) com.aurora.chat.ui.theme.appDivider() else com.aurora.chat.ui.theme.appCardOutline(), RoundedCornerShape(cardCorner))
                else Modifier
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    when {
                        isChat -> onChatClick?.invoke(text)   // 聊天卡片：点击打开全屏查看页
                        isShortVideo -> onShortVideoClick?.invoke(postId) // 短视频卡片：切入短视频模块
                        isGroup -> onGroupClick?.invoke(postId)
                        isUser -> onUserClick?.invoke(postId)
                        isFile -> onFileClick?.invoke(data?.filePath ?: "")
                        else -> onPostClick?.invoke(postId)
                    }
                },
                onLongClick = onBubbleLongPress
            ),
        shape = RoundedCornerShape(cardCorner),
        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardSurface()),
        // 聊天卡不要阴影：阴影叠在聊天底色上会形成一圈灰边，看起来像「标题背后透出一层」
        elevation = CardDefaults.cardElevation(defaultElevation = if (isChat) 0.dp else 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(cardPadding)
        ) {
            // 顶部标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    fontSize = if (compact) 11.sp else 12.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
                if (!isChat || onChatClick != null) {
                    Text(
                        "查看",
                        fontSize = if (compact) 11.sp else 12.sp,
                        color = Color(0xFF1E40AF),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(if (isChat) 6.dp else 10.dp))

            // 聊天记录卡片：顶部标签已是「分享聊天」，这里不再重复标题；
            // 卡片只放前 3 条「发送者:内容」纯文本占位行，绝不把图片/视频塞进卡片，
            // 完整消息（含图片尺寸、动图、点图放大）点进全屏页由单聊渲染模块还原。
            if (isChat) {
                val chatMsgs = data?.chatMessages ?: emptyList()
                // 嵌套的「聊天记录」不能整条跳过：否则前几条都是聊天记录时，卡片会直接显示后面
                // 普通消息的内容，看起来像把里层内容摊到了第一层。这里照样占一行，写成「[聊天记录]」。
                // （预览只是纯文本，不存在 ShareCard → MessageBubble → ShareCard 的递归问题）
                val previewLines = remember(chatMsgs) {
                    chatMsgs.take(3).map { chatCardPreviewLine(it) }
                }
                if (previewLines.isEmpty()) {
                    Text("暂无聊天内容", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        previewLines.forEach { line ->
                            Text(
                                text = line,
                                fontSize = if (compact) 10.sp else 11.sp,
                                // 三行要紧挨着：去掉额外间距，并把行高压到字号的 1.1 倍左右
                                lineHeight = if (compact) 11.sp else 12.sp,
                                color = Color(0xFF64748B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else if (isGroup) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatar(
                        internalGroupId = postId,
                        groupName = title,
                        size = if (compact) 30.dp else 40.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = if (compact) 14.sp else 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = com.aurora.chat.ui.theme.appTextPrimary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (preview.isNotEmpty()) {
                            Text(
                                text = preview,
                                fontSize = if (compact) 11.sp else 12.sp,
                                color = Color(0xFF64748B),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else if (isUser) {
                // 用户卡片：显示头像 + 名称 + 签名（与群卡片样式一致）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(userId = postId, userName = title, size = if (compact) 30.dp else 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = if (compact) 14.sp else 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = com.aurora.chat.ui.theme.appTextPrimary(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (preview.isNotEmpty()) {
                            Text(
                                text = preview,
                                fontSize = if (compact) 11.sp else 12.sp,
                                color = Color(0xFF64748B),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // 标题
                Text(
                    text = title,
                    fontSize = if (compact) 14.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // 内容预览
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = preview,
                        fontSize = if (compact) 12.sp else 13.sp,
                        color = Color(0xFF64748B),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 19.sp
                    )
                }
            }

            // 「来自」只在帖子/文件等「内容不是从这个人来的就说不清」的场景显示；
            // 分享用户 / 分享群聊本身就是那个人/那个群，卡片里已经写明是谁，再标一行「来自」纯属废话。
            if (showFrom && username.isNotEmpty() && !isUser && !isGroup) {
                Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "来自 ",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        username,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E40AF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 分享文件：附原始路径小字，便于识别来源（点击走 onFileClick 打开）
            if (isFile && !data?.filePath.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = data?.filePath ?: "",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 「分享聊天」卡片的分发预览：只给一眼能看懂的摘要（条数 + 内容构成）。
 * 分享面板里的预览区不会把整段聊天内容灌进去——那会把下方好友/群列表挤到没有高度。
 */
@Composable
fun ChatShareSummary(text: String) {
    val messages = remember(text) {
        parseShareCard(text)?.chatMessages?.filter { it.mediaType != "system" } ?: emptyList()
    }
    val images = messages.count { isChatCardImage(it.mediaType, it.mediaUrl) }
    val videos = messages.count { it.mediaType == "video" || it.mediaType == "mp4" || it.mediaType == "mov" }
    val texts = messages.size - images - videos
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            // 深色主题下给卡片一圈细边（浅色主题为透明，观感不变）
            .border(1.dp, com.aurora.chat.ui.theme.appCardOutline(), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardSurface()),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFEEF2FF)),
                contentAlignment = Alignment.Center
            ) {
                Text("💬", fontSize = 14.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "分享聊天",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    // 条数与各项构成之间不用「·」：点号和左边的条数挤在一起很碎，改成中文逗号
                    text = "共 ${messages.size} 条消息" + buildString {
                        if (texts > 0) append("，文本 $texts")
                        if (images > 0) append("，图片 $images")
                        if (videos > 0) append("，视频 $videos")
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ==================== 「分享聊天」卡片内的聊天内容 ====================

/** 是否按图片渲染（媒体类型缺失时用扩展名兜底）。 */
private fun isChatCardImage(mediaType: String, url: String): Boolean {
    when (mediaType) {
        "image", "jpg", "jpeg", "png", "gif", "webp" -> return true
    }
    val clean = url.substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".gif") || clean.endsWith(".webp")
}

/**
 * 卡片预览的一行占位文本：「发送者:内容」。
 * 媒体消息一律用方括号占位（如「张三:[图片]」），卡片里不加载、不渲染任何图片或缩略图；
 * 真实媒体只在全屏查看页由单聊渲染模块呈现。
 */
private fun chatCardPreviewLine(m: ChatCardMessage): String {
    val sender = m.senderName.ifBlank { if (m.isMine) "我" else "对方" }
    // 嵌套的分享卡片：预览里只留一个类型占位，不摊平里层内容。
    // 「分享聊天」→ [聊天记录]（用户看到的就是"聊天记录"这个正常标题），其它分享 → [分享]
    if (m.text.trimStart().startsWith("分享聊天")) return "$sender:[聊天记录]"
    if (isShareCardMessage(m.text)) return "$sender:[分享]"
    val type = normalizeMediaType(m.mediaType, m.mediaUrl)
    val body = when {
        type == "image" || type == "gif" || type == "sticker" || type == "emoji" ||
            isChatCardImage(m.mediaType, m.mediaUrl) -> "[图片]"
        type == "video" -> "[视频]"
        type == "voice" -> "[语音]"
        type == "file" -> "[文件]"
        m.text.isBlank() -> "[消息]"
        else -> m.text.replace('\n', ' ').trim()
    }
    return "$sender:$body"
}


/**
 * 「分享聊天」只读消息行 —— 复用单聊消息渲染模块，绝不自绘。
 *
 * 这里只把卡片快照 [ChatCardMessage] 适配成 [MessageBubble] 的只读调用契约：
 * 头像 / 气泡 / 图片等比尺寸与 GIF·WebP 动图 / 视频缩略图 / 语音 / 文件 / 嵌套分享卡片
 * 全部由 MessageBubble（及其内部的 ChatMediaImage）渲染，卡片侧不写任何绘制代码。
 *
 * 只读消毒参数：msgId=0（避免全局 MediaStore 用别的会话 URL 覆盖快照地址）、
 * isHighlighted=false、交互回调只做转发，不产生撤回/已读/闪烁等副作用。
 */
@Composable
private fun SharedChatBubble(
    message: ChatCardMessage,
    imageUrls: List<String>,
    imageIndexByUrl: Map<String, Int>,
    onImageClick: (List<String>, Int) -> Unit,
    onVideoClick: (String) -> Unit,
    onOpenChatShare: (String) -> Unit,
    onUserClick: ((Long) -> Unit)?,
    onGroupClick: ((Long) -> Unit)?,
    onPostClick: ((Long) -> Unit)?,
    onFileClick: ((String) -> Unit)?,
    onShortVideoClick: ((Long) -> Unit)?,
    onBoundsMeasured: (String, Rect) -> Unit,
    myBubbleColor: Color,
    otherBubbleColor: Color
) {
    // 别名媒体类型（jpg/mp4…）必须归一化，否则 MessageBubble 的分支只认 image/video 会渲染成空白
    val mediaType = remember(message.mediaType, message.mediaUrl) {
        normalizeMediaType(message.mediaType, message.mediaUrl)
    }
    val isVideo = mediaType == "video"
    // 分享卡片里的图片/视频通常没带真实 w/h（原始消息未存尺寸，或来自查找聊天记录快照），
    // 若只交给 ChatMediaImage 运行期嗅探，会先落到固定 150×200 框，且 LazyColumn 复用会反复重置 → 恒为固定尺寸。
    // 这里用进程级尺寸缓存(跨重组保留) + 按需嗅探，提前把真实比例喂给 MessageBubble。
    val resolvedMedia = remember(message.mediaUrl) { resolveMediaUrl(message.mediaUrl) }
    var mediaSize by remember(resolvedMedia) {
        mutableStateOf(ImageSizeFetcher.getCached(resolvedMedia))
    }
    LaunchedEffect(resolvedMedia, mediaSize) {
        if (mediaSize == null && resolvedMedia.startsWith("http")) {
            runCatching { ImageSizeFetcher.fetchSize(resolvedMedia) }.getOrNull()?.let { mediaSize = it }
        }
    }
    val effW = mediaSize?.first ?: message.mediaWidth
    val effH = mediaSize?.second ?: message.mediaHeight
    // 左右方向必须按「当前查看者」算：卡片里的 m 记的是**分享者视角**的 isMine，
    // 直接沿用会让接收方看到「分享者自己发的消息」也贴在右侧（对方视角里那是别人的消息）。
    // 有真实发送者 id 时用它与当前登录用户比对；只有拿不到 id（AI / 残缺旧卡片）才退回卡片里的 m。
    val viewerId = com.aurora.chat.data.api.AuroraApi.currentUserId
    val mineForViewer = if (message.fromUserId > 0L) {
        message.fromUserId == viewerId
    } else {
        message.isMine
    }
    MessageBubble(
        text = message.text,
        isMine = mineForViewer,
        fromUserId = message.fromUserId,
        myAvatar = null,
        displayName = message.senderName,
        showSenderName = !mineForViewer && message.senderName.isNotBlank(),
        loadMedia = message.mediaUrl.isNotEmpty(),
        mediaType = mediaType,
        mediaUrl = message.mediaUrl,
        mediaWidth = effW,
        mediaHeight = effH,
        // 快照没有服务端 id：保持 0，避免全局 MediaStore 拿别的会话 URL 覆盖本条地址
        msgId = 0L,
        isHighlighted = false,
        myBubbleColor = myBubbleColor,
        otherBubbleColor = otherBubbleColor,
        onImageClick = { clickedUrl ->
            if (isVideo) {
                onVideoClick(message.mediaUrl)
            } else {
                val idx = imageIndexByUrl[clickedUrl]
                    ?: imageIndexByUrl[resolveMediaUrl(message.mediaUrl)]
                    ?: -1
                if (idx >= 0) onImageClick(imageUrls, idx)
            }
        },
        onBoundsMeasured = onBoundsMeasured,
        onFileClick = { url, _ -> onFileClick?.invoke(url) },
        onOpenPostDetail = { id -> onPostClick?.invoke(id) },
        onOpenGroupDetail = { id -> onGroupClick?.invoke(id) },
        onAvatarClick = { uid, _ -> onUserClick?.invoke(uid) },
        onOpenShortVideo = { id -> onShortVideoClick?.invoke(id) },
        // 嵌套「分享聊天」卡片：MessageBubble 内部自动渲染 ShareCard 并把点击透传到这里
        onOpenChatShare = onOpenChatShare
    )
}

/**
 * 「分享短视频」的缩略图（不用白底卡片，直接展示视频封面）。
 *
 * - 封面优先用服务端 cover_url（Coil 可直接解码的图片）；封面缺失才回退
 *   MediaMetadataRetriever 取视频首帧（与 ChatCardVideoThumb 同款逻辑）。
 * - 左下角在视频画面内标注「发布者名」——是发这个视频的人，不是分享者。
 * - 竖屏固定比例（与短视频信息流观感一致），点击切入短视频模块。
 */
@Composable
private fun ShortVideoShareThumb(
    coverUrl: String,
    videoUrl: String,
    publisherName: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val ctx = LocalContext.current
    // 服务端 cover_url 可能是相对路径（/api/...），Coil 直接加载必失败（黑屏），
    // 与对话内媒体同一规矩：先过 resolveMediaUrl 拼成客户端可达的绝对 URL。
    val coverAbs = remember(coverUrl) { resolveMediaUrl(coverUrl) }
    // 封面 404 / 解码失败时置位：自动回退 MMR 取视频首帧（与"无封面"同一条兜底路径）
    var coverFailed by remember(coverAbs) { mutableStateOf(false) }
    // 无服务端封面（或封面加载失败）时，才用 MMR 取视频首帧兜底
    var frame by remember(videoUrl) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(videoUrl, coverAbs, coverFailed) {
        if ((coverAbs.isBlank() || coverFailed) && videoUrl.isNotBlank()) {
            frame = withContext(Dispatchers.IO) {
                runCatching {
                    val mmr = android.media.MediaMetadataRetriever()
                    try {
                        val cached = com.aurora.chat.data.local.MediaCache.getCachedVideoUri(ctx, resolveMediaUrl(videoUrl))
                        if (cached != null) mmr.setDataSource(ctx, cached)
                        else mmr.setDataSource(resolveMediaUrl(videoUrl).removePrefix("file://"))
                        mmr.frameAtTime
                    } finally {
                        runCatching { mmr.release() }
                    }
                }.getOrNull()
            }
        }
    }

    Box(
        modifier = Modifier
            .width(150.dp)
            .height(210.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111827))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        when {
            coverAbs.isNotBlank() && !coverFailed -> coil.compose.AsyncImage(
                model = coverAbs,
                contentDescription = "短视频封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { coverFailed = true }
            )
            frame != null -> Image(
                bitmap = frame!!.asImageBitmap(),
                contentDescription = "短视频封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            else -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Aurora Chat", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0x99FFFFFF))
            }
        }

        // ── 底部暗角：保证发布者名在任何封面上都清晰 ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(52.dp)
                .background(
                    Brush.verticalGradient(
                        0.4f to Color.Transparent,
                        1.0f to Color(0xB3000000)
                    )
                )
        )

        // ── 左下角：发布者名（发视频的人，不是分享者） ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "@${publisherName.ifBlank { "用户" }}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // ── 居中播放键 ──
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(30.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0x66000000)),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", fontSize = 12.sp, color = Color.White)
        }
    }
}


// ==================== 「分享聊天」全屏查看页 ====================

/**
 * 「分享聊天」全屏页的一层：文本 + 栈深。
 * 用 depth 判断动画方向（depth 变大 = 进下一层，变小 = 返回上一层），
 * 不能直接比较文本，因为同一份聊天记录可能被重复嵌套。
 */
private data class ShareLayer(val text: String, val depth: Int)

/**
 * 「分享聊天」全屏查看页：完整展示全部消息。
 *
 * 架构：消息行统一委托 [SharedChatBubble] → [MessageBubble]（单聊渲染模块），
 * 图片等比尺寸、GIF/WebP 动图、点图放大、视频播放、语音/文件卡片、嵌套分享卡片
 * 全部与真实聊天页同源，本文件不再自绘任何气泡或媒体。
 *
 * 层级栈：聊天记录里再套聊天记录时逐层压栈，返回键/顶栏返回逐层出栈，
 * 只剩根层才真正退出；层数不设上限，完全由卡片内容决定。
 */
@Composable
fun ChatShareFullScreen(
    text: String,
    onBack: () -> Unit,
    onImageClick: (urls: List<String>, index: Int) -> Unit,
    /** 当前会话 id：用于沿用普通聊天对话同一套底色/自定义壁纸 */
    friendId: Long = 0L,
    onUserClick: ((Long) -> Unit)? = null,
    onGroupClick: ((Long) -> Unit)? = null,
    onPostClick: ((Long) -> Unit)? = null,
    onFileClick: ((String) -> Unit)? = null,
    onChatClick: ((String) -> Unit)? = null,
    /** 卡片内的视频缩略图：mediaUrl → 调用方用应用内播放器打开 */
    onVideoClick: ((String) -> Unit)? = null,
    /** 卡片内嵌套的「分享短视频」缩略图：shortvideo_id → 切入短视频模块 */
    onShortVideoClick: ((Long) -> Unit)? = null,
    /** 缩略图屏幕矩形上报：交给调用方登记，供全屏图片预览的 hero 放大动画校准起点 */
    onBoundsMeasured: (String, Rect) -> Unit = { _, _ -> },
    /** 图片预览层打开时置 false：把返回键/手势让给全局图片预览 BackHandler，
     *  否则按返回会先被本页 BackHandler 吃掉而直接退出分享聊天，图片预览层没机会先关闭 */
    backEnabled: Boolean = true,
    /** 全屏看图隐藏系统栏期间冻结状态栏高度，退出时不重排（与聊天顶栏一致，避免顶栏「抽搐」） */
    freezeStatusBar: Boolean = false,
    myBubbleColor: Color = BubbleColorStorage.DEFAULT,
    otherBubbleColor: Color = BubbleColorStorage.DEFAULT_OTHER
) {
    // 根层由外部 text 决定；嵌套层由卡片点击压栈。remember(text) 保证换根时重置。
    val stack = remember(text) { mutableStateListOf(text) }
    val currentText = stack.last()

    fun goBack() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack()
    }
    // 逐层返回：还有上一层就出栈，否则交给外部关闭全屏。
    // 图片预览层打开时本页 BackHandler 让位（见 backEnabled），返回优先关闭预览而非退出全屏。
    BackHandler(enabled = backEnabled) { goBack() }

    // 按当前层文本缓存解析结果：切层/重组不重复做 Base64+JSON 解析（顶栏「共 N 条」用）
    val messages = remember(currentText) { parseShareCard(currentText)?.chatMessages ?: emptyList() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 与普通聊天对话同一套底色：默认 AI 白/其余浅灰，该会话设过壁纸就用壁纸
            .chatBackground(friendId)
    ) {
        // 顶栏：从屏幕最顶端（含状态栏）起一整条纯白，不透底、不分层。
        // statusBarsPadding 放在白底之后，白色才能铺满状态栏那一截，不会露出灰色聊天底色。
        // 留白/返回键尺寸/标题字号全部对齐单聊页顶栏（上下一 11dp、返回键 28dp、标题 15sp Medium），
        // 之前是 IconButton(48dp)+上下 4dp 的 56dp 高栏，标题比单聊页低了约 3dp，所以看着"往下沉"。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.aurora.chat.ui.theme.appSurface())
                .padding(top = rememberStableStatusBarTopPadding(freeze = freezeStatusBar))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { goBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                // 不管是根层还是嵌套层，这里展示的都是聊天记录，标题统一「聊天记录」
                text = "聊天记录",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (messages.isNotEmpty()) {
                Text(
                    text = "共 ${messages.size} 条",
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextHint()
                )
            }
        }

        // 层级切换动画：进下一层从右侧推入、原层往左淡出；返回时反过来。
        // 之前只有「主对话 → 分享聊天」这一次有整页动画，层与层之间是瞬间替换的，所以这里补上。
        AnimatedContent(
            targetState = ShareLayer(currentText, stack.size),
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = {
                if (targetState.depth > initialState.depth) {
                    // 入栈：新层从右边进来，旧层往左退一小段并淡出
                    (slideInHorizontally(tween(260)) { it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 4 } + fadeOut(tween(160)))
                } else {
                    // 出栈：上层从左边回来，当前层往右退出去
                    (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260)) { it } + fadeOut(tween(160)))
                }
            }
        ) { layer ->
            // 每层独立解析并缓存：动画期间新旧两层同时存在，各用各的数据
            val layerMessages = remember(layer.text) {
                parseShareCard(layer.text)?.chatMessages ?: emptyList()
            }
            val layerImageUrls = remember(layerMessages) {
                layerMessages
                    .filter { isChatCardImage(it.mediaType, it.mediaUrl) }
                    .map { resolveMediaUrl(it.mediaUrl) }
                    .filter { it.isNotBlank() }
            }
            // url → 下标：避免每行 indexOf 造成 O(n²)
            val layerImageIndexByUrl = remember(layerImageUrls) {
                layerImageUrls.mapIndexed { i, u -> u to i }.toMap()
            }
            if (layerMessages.isEmpty()) {
                Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                    Text("暂无聊天内容", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(layerMessages, key = { it.id }) { m ->
                        SharedChatBubble(
                            message = m,
                            imageUrls = layerImageUrls,
                            imageIndexByUrl = layerImageIndexByUrl,
                            onImageClick = onImageClick,
                            onVideoClick = { url -> onVideoClick?.invoke(url) },
                            onOpenChatShare = { nested ->
                                // 嵌套聊天记录：先通知外部（清除撤回校验 id 等），再压栈进入下一层
                                onChatClick?.invoke(nested)
                                stack.add(nested)
                            },
                            onUserClick = onUserClick,
                            onGroupClick = onGroupClick,
                            onPostClick = onPostClick,
                            onFileClick = onFileClick,
                            onShortVideoClick = onShortVideoClick,
                            onBoundsMeasured = onBoundsMeasured,
                            myBubbleColor = myBubbleColor,
                            otherBubbleColor = otherBubbleColor
                        )
                    }
                }
            }
        }
    }
}

// ==================== 入群邀请卡片组件 ====================

/**
 * "某某邀请你进入某某群聊"邀请卡片。
 * 仿分享群聊卡片但样式独立；收到方点击可进入同意/拒绝页。
 */
@Composable
fun InviteGroupCard(
    text: String,
    isMine: Boolean,
    onRespondClick: ((InviteGroupCardData) -> Unit)? = null,
    onBubbleLongPress: () -> Unit = {}
) {
    val data = parseInviteGroupCard(text)
    if (data == null) {
        Text(text = text, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
        return
    }
    Card(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .clip(RoundedCornerShape(16.dp))
            // 深色主题下给卡片一圈细边（浅色主题为透明，观感不变）
            .border(1.dp, com.aurora.chat.ui.theme.appCardOutline(), RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!isMine) onRespondClick?.invoke(data) },
                onLongClick = onBubbleLongPress
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardSurface()),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 顶部标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("入群邀请", fontSize = 12.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
                Text(
                    if (isMine) "已发送" else "点此处理",
                    fontSize = 12.sp,
                    color = if (isMine) Color(0xFF94A3B8) else Color(0xFF1E40AF),
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(10.dp))
            // 群头像 + 群名 + 邀请人文案
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatar(
                    internalGroupId = data.groupId,
                    groupName = data.groupName,
                    size = 40.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${data.inviterName.ifBlank { "对方" }} 邀请你进入群聊",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (data.groupName.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = data.groupName,
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ==================== 入群邀请全屏应答页 ====================

/**
 * 入群邀请的独立全屏应答页：显示群头像/群名/邀请人，提供"同意/拒绝"。
 * 外层先铺满白色背景（含状态栏区域），避免顶部灰色区。
 */
@Composable
fun GroupInviteRespondPage(
    invite: InviteGroupCardData,
    onDismiss: () -> Unit,
    onResponded: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val submitting = remember { mutableStateOf(false) }
    val responded = remember { mutableStateOf(false) }

    fun respond(approve: Boolean) {
        if (submitting.value || responded.value) return
        submitting.value = true
        scope.launch {
            val r = runCatching {
                AuroraApi.respondGroupInvite(invite.groupId, invite.inviterId, approve)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                submitting.value = false
                if (r != null && r.success) {
                    responded.value = true
                    Toast.makeText(ctx, if (approve) "已同意入群" else "已拒绝", Toast.LENGTH_SHORT).show()
                    onResponded()
                } else {
                    Toast.makeText(ctx, r?.message ?: "操作失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .safeDrawingPadding()
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(com.aurora.chat.ui.theme.appSurface())
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (!submitting.value) onDismiss() }) {
                Text("✕", fontSize = 20.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            }
            Text("入群邀请", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
        }

        // 中部内容
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            GroupAvatar(internalGroupId = invite.groupId, groupName = invite.groupName.ifBlank { "群聊" }, size = 72.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = invite.groupName.ifBlank { "群聊" },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${invite.inviterName.ifBlank { "对方" }} 邀请你进入群聊",
                fontSize = 15.sp,
                color = com.aurora.chat.ui.theme.appTextSecondary(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "同意后可进入该群聊",
                fontSize = 13.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // 底部按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            androidx.compose.material3.Button(
                onClick = { respond(false) },
                enabled = !submitting.value,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = com.aurora.chat.ui.theme.appDivider(),
                    contentColor = com.aurora.chat.ui.theme.appTextSecondary()
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("拒绝", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.Button(
                onClick = { respond(true) },
                enabled = !submitting.value,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2F6FED),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (submitting.value) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Text("同意", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ==================== 隐私锁定卡片组件 ====================

@Composable
fun PrivacyCard(
    text: String,
    isMine: Boolean,
    onRespond: ((Long, Boolean) -> Unit)? = null
) {
    val data = parsePrivacyCard(text)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSurface()),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            when (data?.type) {
                "unlock_request" -> {
                    Text("🔓 取消锁定请求", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                    Spacer(Modifier.height(8.dp))
                    Text("对方请求取消隐私锁定\n双方同意后即可退出", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    if (!isMine) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = {
                                scope.launch {
                                    try { AuroraApi.respondUnlockRequest(data.fromUserId, false) } catch (_: Exception) {}
                                }
                            }) { Text("拒绝", color = Color(0xFFDC2626), fontSize = 13.sp) }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        AuroraApi.respondUnlockRequest(data.fromUserId, true)
                                        // 关闭本地的隐私保护
                                        com.aurora.chat.PrivacyFloatingService.masterOn = false
                                        com.aurora.chat.PrivacyFloatingService.noExit = false
                                        // 双方同意取消锁定 → 同时清掉「禁止退出」互锁状态
                                        com.aurora.chat.PrivacyLockState.clear()
                                        com.aurora.chat.PrivacyFloatingService.stop(ctx)
                                        onRespond?.invoke(data.fromUserId, true)
                                    } catch (_: Exception) {}
                                }
                            }) { Text("同意取消", color = Color(0xFF059669), fontSize = 13.sp) }
                        }
                    }
                }
                "unlock_accept" -> {
                    Text("🔓 锁定已取消", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                    Spacer(Modifier.height(8.dp))
                    Text("双方已同意取消隐私锁定\n现在可以正常退出对话", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    // 对方同意取消时也关闭本地的悬浮球
                    LaunchedEffect(Unit) {
                        com.aurora.chat.PrivacyFloatingService.stop(ctx)
                    }
                }
                "unlock_reject" -> {
                    Text("🔒 取消锁定被拒", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                    Spacer(Modifier.height(8.dp))
                    Text("对方拒绝取消隐私锁定", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                }
                "request" -> {
                    Text("🔒 隐私锁定请求", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                    Spacer(Modifier.height(8.dp))
                    Text("${data.fromUsername} 请求与你进行隐私锁定", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.height(4.dp))
                    Text("开启后双方将无法退出当前对话\n双方均需开启无障碍权限", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    if (!isMine) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { onRespond?.invoke(data.fromUserId, false) }) {
                                Text("拒绝", color = Color(0xFFDC2626), fontSize = 13.sp)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        val result = AuroraApi.respondPrivacyRequest(data.fromUserId, true)
                                        if (result.success) {
                                            onRespond?.invoke(data.fromUserId, true)
                                            // 启动本地的隐私悬浮球（对方用 fromUserId 作为聊天对象）
                                            com.aurora.chat.PrivacyFloatingService.start(ctx, data.fromUserId)
                                            // 跳转到无障碍设置
                                            withContext(Dispatchers.Main) {
                                                try {
                                                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }) {
                                Text("同意", color = Color(0xFF059669), fontSize = 13.sp)
                            }
                        }
                    }
                }
                "accept" -> {
                    Text("✅ 隐私锁定已同意", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                    Spacer(Modifier.height(8.dp))
                    Text("对方已同意隐私锁定，请前往开启无障碍权限", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        try {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    }) {
                        Text("开启无障碍权限", color = Color(0xFF1E40AF), fontSize = 13.sp)
                    }
                }
                "reject" -> {
                    Text("❌ 隐私锁定已拒绝", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                    Spacer(Modifier.height(8.dp))
                    Text("对方已拒绝隐私锁定请求", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                }
                else -> {
                    Text(text, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                }
            }
        }
    }
}
