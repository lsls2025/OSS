@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aurora.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.components.GroupAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 从文本中检测是否为分享卡片消息（兼容"分享帖子"、"分享资源"、"分享群聊"和"分享文件"） */
fun isShareCardMessage(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed.startsWith("分享帖子") || trimmed.startsWith("分享资源") || trimmed.startsWith("分享群聊") || trimmed.startsWith("分享文件")
}

/** 检测是否为群聊分享卡片 */
fun isShareGroupCard(text: String): Boolean {
    return text.trimStart().startsWith("分享群聊")
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
            else -> "post"
        }
        val shareType = when (targetType) {
            "resource" -> "资源"
            "group" -> "群聊"
            "file" -> "文件"
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
        val idRaw = idLine.removePrefix("post_id=").removePrefix("group_id=").removePrefix("file_id=").trim()
        val postId = idRaw.toLongOrNull() ?: 0L
        val filePath = lines.firstOrNull { it.startsWith("file_path=") }
            ?.removePrefix("file_path=")?.trim()?.takeIf { it.isNotEmpty() } ?: ""

        if (postId <= 0) return null
        return ShareCardData(title, preview, username, postId, shareType, targetType, filePath)
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
    val targetType: String = "post",   // "post" | "resource" | "group" | "file"
    val filePath: String = ""
)

@Composable
fun ShareCard(
    text: String,
    isMine: Boolean,
    onPostClick: ((Long) -> Unit)? = null,
    onGroupClick: ((Long) -> Unit)? = null,  // group_id → 打开群详情
    onFileClick: ((String) -> Unit)? = null, // file_path → 打开/查看分享文件
    onBubbleLongPress: () -> Unit = {}
) {
    val data = parseShareCard(text)

    // 如果解析失败，尝试从最后一行扣 id
    val fallbackId = runCatching {
        text.lines().lastOrNull { it.contains("post_id=") || it.contains("group_id=") || it.contains("file_id=") }
            ?.let { line ->
                line.removePrefix("post_id=").removePrefix("group_id=").removePrefix("file_id=").trim().toLongOrNull()
            }
    }.getOrNull() ?: 0L

    val postId = data?.postId ?: fallbackId
    val title = data?.title?.takeIf { it.isNotBlank() } ?: "分享内容"
    val preview = data?.preview ?: ""
    val username = data?.username?.takeIf { it.isNotBlank() } ?: ""
    val shareType = data?.shareType ?: "帖子"
    val targetType = data?.targetType ?: "post"
    val isGroup = targetType == "group"
    val isFile = targetType == "file"

    if (postId <= 0) {
        Text(
            text = text,
            fontSize = 15.sp,
            color = Color(0xFF1F2937),
            modifier = Modifier.padding(vertical = 4.dp)
        )
        return
    }

    // 资源与帖子已合并为统一「分享」类型，群聊单独保留「分享群聊」，文件用「分享文件」区分
    val label = when (shareType) {
        "群聊" -> "分享群聊"
        "文件" -> "分享文件"
        else -> "分享"
    }

    Card(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    when {
                        isGroup -> onGroupClick?.invoke(postId)
                        isFile -> onFileClick?.invoke(data?.filePath ?: "")
                        else -> onPostClick?.invoke(postId)
                    }
                },
                onLongClick = onBubbleLongPress
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // 顶部标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "查看",
                    fontSize = 12.sp,
                    color = Color(0xFF1E40AF),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(10.dp))

            // 群聊卡片：显示群头像 + 名称 + 签名
            if (isGroup) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatar(
                        internalGroupId = postId,
                        groupName = title,
                        size = 40.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1F2937),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (preview.isNotEmpty()) {
                            Text(
                                text = preview,
                                fontSize = 12.sp,
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
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // 内容预览
                if (preview.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = preview,
                        fontSize = 13.sp,
                        color = Color(0xFF64748B),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 19.sp
                    )
                }
            }

            if (username.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
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
        Text(text = text, fontSize = 14.sp, color = Color(0xFF1F2937))
        return
    }
    Card(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (!isMine) onRespondClick?.invoke(data) },
                onLongClick = onBubbleLongPress
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
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
                        color = Color(0xFF1F2937),
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
            .background(Color.White)
            .safeDrawingPadding()
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (!submitting.value) onDismiss() }) {
                Text("✕", fontSize = 20.sp, color = Color(0xFF1F2937))
            }
            Text("入群邀请", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
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
                color = Color(0xFF1F2937),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${invite.inviterName.ifBlank { "对方" }} 邀请你进入群聊",
                fontSize = 15.sp,
                color = Color(0xFF6B7280),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "同意后可进入该群聊",
                fontSize = 13.sp,
                color = Color(0xFF9CA3AF),
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
                    containerColor = Color(0xFFE5E7EB),
                    contentColor = Color(0xFF374151)
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
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            when (data?.type) {
                "unlock_request" -> {
                    Text("🔓 取消锁定请求", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706))
                    Spacer(Modifier.height(8.dp))
                    Text("对方请求取消隐私锁定\n双方同意后即可退出", fontSize = 14.sp, color = Color(0xFF1F2937))
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
                    Text("双方已同意取消隐私锁定\n现在可以正常退出对话", fontSize = 14.sp, color = Color(0xFF1F2937))
                    // 对方同意取消时也关闭本地的悬浮球
                    LaunchedEffect(Unit) {
                        com.aurora.chat.PrivacyFloatingService.stop(ctx)
                    }
                }
                "unlock_reject" -> {
                    Text("🔒 取消锁定被拒", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                    Spacer(Modifier.height(8.dp))
                    Text("对方拒绝取消隐私锁定", fontSize = 14.sp, color = Color(0xFF1F2937))
                }
                "request" -> {
                    Text("🔒 隐私锁定请求", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                    Spacer(Modifier.height(8.dp))
                    Text("${data.fromUsername} 请求与你进行隐私锁定", fontSize = 14.sp, color = Color(0xFF1F2937))
                    Spacer(Modifier.height(4.dp))
                    Text("开启后双方将无法退出当前对话\n双方均需开启无障碍权限", fontSize = 12.sp, color = Color(0xFF6B7280))
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
                    Text("对方已同意隐私锁定，请前往开启无障碍权限", fontSize = 14.sp, color = Color(0xFF1F2937))
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
                    Text("对方已拒绝隐私锁定请求", fontSize = 14.sp, color = Color(0xFF1F2937))
                }
                else -> {
                    Text(text, fontSize = 14.sp, color = Color(0xFF1F2937))
                }
            }
        }
    }
}
