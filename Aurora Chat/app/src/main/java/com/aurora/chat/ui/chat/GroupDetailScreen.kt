package com.aurora.chat.ui.chat

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.GroupAvatar
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 群聊详情页（可复用）。
 *
 * 供两处入口复用：
 * 1. 搜索群聊 SearchGroupScreen 的结果点击
 * 2. 联系人列表「群聊」MyGroupsScreen 的群行点击（此时直接打开详情，不经过搜索列表）
 *
 * 底部按钮判定：后端 getGroupInfo 返回的 user_role 非空表示「已在该群内」→ 显示「发消息」；
 * user_role 为空表示未加入 → 显示「加入群聊」（有审核则弹申请，无审核直接加入）。
 *
 * @param groupId 群内部 id（>0）
 * @param groupName 群名称
 * @param groupDisplayId 群展示 id（搜索用）
 * @param groupSignature 群签名
 * @param initialJoined 是否已知已加入（例如从我的群聊进入时一定已加入，可跳过首次加载闪烁）
 * @param fromShareCard 是否从分享卡片进入（true 时返回直接退出，一步回到卡片所在对话）
 * @param onBack 返回回调（关闭详情）
 * @param onOpenGroupChat 打开群聊（已加入时「发消息」触发）
 * @param onDismissSelf 当 fromShareCard 时返回需要一步退出整个链路
 */
@Composable
fun GroupDetailScreen(
    groupId: Long,
    groupName: String,
    groupDisplayId: Long,
    groupSignature: String,
    initialJoined: Boolean = false,
    fromShareCard: Boolean = false,
    onBack: () -> Unit,
    onOpenGroupChat: (groupConvId: Long, groupName: String) -> Unit,
    onDismissSelf: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    fun hideKeyboard() { imm.hideSoftInputFromWindow(view.windowToken, 0) }

    // 群详情状态
    var groupInfo by remember(groupId) { mutableStateOf<JSONObject?>(null) }
    var memberCount by remember { mutableStateOf(0) }
    var isJoining by remember { mutableStateOf(false) }

    // 入群申请面板状态
    var showJoinPanel by remember { mutableStateOf(false) }
    var joinReason by remember { mutableStateOf("") }

    // 是否已加入：优先用后端 user_role 判定，否则用进入时已知状态兜底
    var isAlreadyMember by remember(groupId) {
        mutableStateOf(initialJoined || groupInfo?.optString("user_role", "")?.isNotEmpty() == true)
    }

    // 加载群详情
    LaunchedEffect(groupId) {
        val r = AuroraApi.getGroupInfo(groupId)
        if (r.success && r.data != null) {
            groupInfo = r.data
            val role = r.data!!.optString("user_role", "")
            isAlreadyMember = initialJoined || role.isNotEmpty()
        } else {
            groupInfo = null
        }
        val members = ChatRepository.getGroupMembers(groupId)
        if (members.success && members.data != null) {
            memberCount = members.data!!.length()
        }
    }

    val needJoinReview = groupInfo?.optBoolean("join_required", false) == true

    // 用后端详情覆盖传入的展示字段（"我的群聊"入口传入的 displayId/signature 可能是占位值）
    val displayIdText = groupInfo?.optLong("display_id", groupDisplayId)?.takeIf { it > 0 } ?: groupDisplayId
    val signatureText = groupInfo?.optString("signature", groupSignature)?.takeIf { it.isNotEmpty() } ?: groupSignature

    Box(Modifier.fillMaxSize()) {
        com.aurora.chat.ui.components.EventBlocker()
        Column(Modifier.fillMaxSize().background(Color.White)) {
            // 顶栏
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clickable {
                        if (fromShareCard) onDismissSelf() else onBack()
                    },
                    contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text("群聊详情", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
            }
            Spacer(Modifier.height(20.dp))
            GroupAvatar(internalGroupId = groupId, groupName = groupName, size = 80.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Text(groupName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("群ID: $displayIdText", fontSize = 13.sp, color = Color(0xFF9CA3AF),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Text("群签名", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(Modifier.height(4.dp))
                Text(signatureText.ifEmpty { "无" }, fontSize = 14.sp, color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))
                Text("创建时间", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(Modifier.height(4.dp))
                val createTime = groupInfo?.optLong("created_at", 0) ?: 0L
                val timeText = if (createTime > 0) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(createTime * 1000))
                } else "未知"
                Text(timeText, fontSize = 14.sp, color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))
                Text("群人数", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(Modifier.height(4.dp))
                Text("${memberCount} 人", fontSize = 14.sp, color = Color(0xFF1F2937))
            }
            Spacer(Modifier.weight(1f))
            // ── 底部按钮：已加入显示「发消息」，否则显示「加入群聊」──
            Box(Modifier.fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E40AF))
                .clickable {
                    hideKeyboard()
                    if (isAlreadyMember) {
                        onOpenGroupChat(-(1000 + groupId), groupName)
                    } else if (needJoinReview) {
                        showJoinPanel = true // 需审核 → 弹出申请面板
                    } else {
                        // 无需审核 → 直接加入
                        isJoining = true
                        scope.launch {
                            val r = ChatRepository.submitJoinRequest(groupId, "")
                            isJoining = false
                            if (r.success) {
                                Toast.makeText(context, "已加入群聊", Toast.LENGTH_SHORT).show()
                                isAlreadyMember = true
                                onOpenGroupChat(-(1000 + groupId), groupName)
                            } else if (r.message.contains("已在群")) {
                                Toast.makeText(context, "你已在群聊中", Toast.LENGTH_SHORT).show()
                                isAlreadyMember = true
                            } else {
                                Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        isJoining -> "加入中..."
                        isAlreadyMember -> "发消息"
                        else -> "加入群聊"
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White
                )
            }
        }

        // ── 入群申请填写面板（从右侧滑入，全屏）──
        AnimatedVisibility(
            visible = showJoinPanel,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                Column(Modifier.fillMaxSize().background(Color.White)) {
                    // 顶栏
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clickable { showJoinPanel = false },
                            contentAlignment = Alignment.Center) {
                            Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("提交入群申请", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                    }
                    Spacer(Modifier.height(8.dp))
                    // 大输入框（固定5行高度，超出可滑动）
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(0.dp, 120.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F6)).padding(14.dp)) {
                        BasicTextField(value = joinReason, onValueChange = { joinReason = it },
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                            singleLine = false,
                            decorationBox = { inner ->
                                Box {
                                    if (joinReason.isEmpty()) Text("请输入入群原因（可选）", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                    inner()
                                }
                            })
                    }
                    Spacer(Modifier.height(20.dp))
                    // 提交按钮
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E40AF))
                        .clickable(enabled = !isJoining) {
                            isJoining = true
                            scope.launch {
                                val r = ChatRepository.submitJoinRequest(groupId, joinReason)
                                isJoining = false
                                if (r.success) {
                                    if (r.data?.contains("入群申请") == true || r.data?.contains("等待审批") == true) {
                                        Toast.makeText(context, "入群申请已提交，群主同意后将进入此群", Toast.LENGTH_LONG).show()
                                        showJoinPanel = false
                                    } else {
                                        Toast.makeText(context, "已加入群聊", Toast.LENGTH_SHORT).show()
                                        showJoinPanel = false
                                        isAlreadyMember = true
                                        onOpenGroupChat(-(1000 + groupId), groupName)
                                    }
                                } else if (r.message.contains("已在群")) {
                                    Toast.makeText(context, "你已在群聊中", Toast.LENGTH_SHORT).show()
                                    isAlreadyMember = true
                                    showJoinPanel = false
                                } else {
                                    Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        contentAlignment = Alignment.Center) {
                        Text(if (isJoining) "提交中..." else "提交申请", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }
        }
    }
}
