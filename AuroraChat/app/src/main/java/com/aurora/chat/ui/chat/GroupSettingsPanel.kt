package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.AvatarCache
import com.aurora.chat.data.local.DataCache
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.ui.components.EventBlocker
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.components.avatarCornerRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

// 群内导航页（重写：单一返回栈驱动 INFO→MEMBERS→PROFILE，箭头与左滑同源）
private enum class GroupNavPage { INFO, MEMBERS, PROFILE }

@Composable
fun GroupSettingsPanel(
    internalGroupId: Long,
    groupName: String,
    onBack: () -> Unit,
    /**
     * 只读态：我已不在这个群（被管理员移出 / 自己退出）。
     *
     * 此时历史仍然属于用户，群信息面板必须能打开：顶部信息块（头像/群名/群号/群二维码）照常展示，
     * 那是群的身份标识，不是会过期的"群资料"；下面会变的那些（群名编辑/备注/签名/公告/欢迎语/通知方式）
     * 以及成员名单、入群设置、退出按钮一律不渲染——既因为接口对非成员已无返回，
     * 也因为「离开后还能看到名单和后续公告」本身就不合理。
     */
    readOnly: Boolean = false,
    onOpenProfile: (Long, String) -> Unit = { _, _ -> },
    onOpenChat: (Long, String) -> Unit = { _, _ -> },
    onDissolved: () -> Unit = {},
    /** 只读会话里点了「删除聊天」并清完本地数据后回调：由调用方关闭设置页并退出该会话 */
    onDeleted: () -> Unit = {},
    onOpenJoinRequestList: (org.json.JSONArray, Long) -> Unit = { _, _ -> },
    onOpenHistorySearch: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groupInfo by remember { mutableStateOf<JSONObject?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var editName by remember { mutableStateOf("") }
    var editSignature by remember { mutableStateOf("") }
    // 首帧即用缓存：断网/进群设置瞬间就能显示群号与群名，不必等联网。
    // 之后下面的 LaunchedEffect 联网刷新，覆盖更全的资料（公告/欢迎语等）。
    LaunchedEffect(internalGroupId) {
        val cached = com.aurora.chat.data.repository.ChatRepository.getCachedGroupInfo(internalGroupId)
        if (cached != null && groupInfo == null) {
            groupInfo = cached
            editName = cached.optString("name", groupName).ifBlank { groupName }
        }
    }
    // 多公告：JSON 数组字符串存后端，本地为增强对象列表；兼容旧单条字符串
    var editAnnouncements by remember { mutableStateOf<List<GroupAnnouncementData>>(emptyList()) }
    var editWelcomeEnabled by remember { mutableStateOf(false) }
    var editWelcomeText by remember { mutableStateOf("") }
    var editNotSearchable by remember { mutableStateOf(false) }
    var editJoinRequired by remember { mutableStateOf(false) }
    // 群备注（个人本地设置，任何人可改，存 aurora_friend_settings）
    var editRemark by remember { mutableStateOf("") }

    // ── 群资料子页面（路口入口） ──
    var showEditName by remember { mutableStateOf(false) }
    var showEditRemark by remember { mutableStateOf(false) }
    var showEditSignature by remember { mutableStateOf(false) }
    var showAnnouncements by remember { mutableStateOf(false) }
    var showEditWelcome by remember { mutableStateOf(false) }

    // ── 液态模式：开关用液态玻璃 Toggle；经典模式保持 Material3 Switch ──
    // 面板是白底，给玻璃手柄一个纯色折射源（面板本身仍是白底，只让控件内部有玻璃感）

    // 群成员列表（提升到顶层，便于"加入/移除"回调刷新）
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    // 批量预加载的成员头像（uid -> Bitmap），网格直接读此 Map，避免逐格 LaunchedEffect 在主线程解码导致滚动卡顿
    var avatarMap by remember { mutableStateOf<Map<Long, Bitmap?>>(emptyMap()) }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hasChanges by remember { mutableStateOf(false) }

    // 群管理全屏页（右上角「⋯」直接切入，取代原来的「群管理」弹窗）
    var showManage by remember { mutableStateOf(false) }
    var showDissolveDialog by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    var showRemoveMember by remember { mutableStateOf(false) }
    var showGroupQr by remember { mutableStateOf(false) }
    var showGroupProfile by remember { mutableStateOf(false) }
    // ── 群内导航栈（重写：单一返回栈驱动，箭头与左滑同源，物理上不可能跳两级） ──
    // INFO=群聊信息主面板  MEMBERS=群成员全屏界面  PROFILE=用户资料（内联 UserProfileCard，不再走顶层 showUserProfile）
    var groupNavStack by remember { mutableStateOf(listOf(GroupNavPage.INFO)) }
    var profileUid by remember { mutableStateOf(0L) }
    var profileName by remember { mutableStateOf("") }

    fun openMembers() { groupNavStack = groupNavStack + GroupNavPage.MEMBERS }
    fun openProfile(uid: Long, name: String) {
        profileUid = uid
        profileName = name
        groupNavStack = groupNavStack + GroupNavPage.PROFILE
    }
    // 唯一返回函数：箭头与系统左滑都调它，永远只 pop 栈顶一层
    fun popGroupNav() {
        if (groupNavStack.size > 1) groupNavStack = groupNavStack.dropLast(1)
        else onBack() // 仅剩 INFO：交由外层关闭整个群聊信息面板
    }
    var showGroupNotify by remember { mutableStateOf(false) }
    // 群敏感词 / 群黑名单（仅群主、管理员）
    var showSensitiveWords by remember { mutableStateOf(false) }
    var showGroupBlacklist by remember { mutableStateOf(false) }
    // 群敏感词摘要（词表可能很长，主面板只显示开关状态 + 词数 + 处置方式）
    var sensitiveEnabled by remember { mutableStateOf(false) }
    var sensitiveWordCount by remember { mutableStateOf(0) }
    var sensitiveAction by remember { mutableStateOf("recall") }
    // 「禁止发链接」独立开关，摘要里一起展示
    var sensitiveBlockLinks by remember { mutableStateOf(false) }
    // 群通知方式摘要（进入面板时同步一次，从通知设置页返回后再刷新）
    var notifySummary by remember { mutableStateOf("") }
    LaunchedEffect(internalGroupId, showGroupNotify) {
        if (showGroupNotify) return@LaunchedEffect
        val s = com.aurora.chat.data.local.GroupNotifyPrefs.load(internalGroupId)
            ?: com.aurora.chat.data.local.GroupNotifyPrefs.get(internalGroupId)
        // 摘要只显示当前方式，不带开启项数
        notifySummary = if (s.isGroupIdentity) "以群身份通知" else "以系统广播通知"
    }
    // 群敏感词摘要：从配置页返回后刷新（离开该页时取一次）
    LaunchedEffect(internalGroupId, showSensitiveWords) {
        if (showSensitiveWords) return@LaunchedEffect
        val d = runCatching { AuroraApi.getGroupSensitiveSettings(internalGroupId) }.getOrNull()?.data
        if (d != null) {
            sensitiveEnabled = d.optBoolean("enabled", false)
            sensitiveWordCount = d.optJSONArray("words")?.length() ?: 0
            sensitiveAction = d.optString("action", "recall").ifBlank { "recall" }
            sensitiveBlockLinks = d.optBoolean("block_links", false)
        }
    }
    var showLeaveDialog by remember { mutableStateOf(false) }
    // 只读会话的「删除聊天」：确认弹窗 + 执行中防重复点击
    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var deletingChat by remember { mutableStateOf(false) }

    val handleBack: () -> Unit = {
        scope.launch {
            if (hasChanges) {
                val r = ChatRepository.updateGroupSettings(
                    groupId = internalGroupId, name = editName,
                    signature = editSignature, announcement = "",
                    welcomeEnabled = editWelcomeEnabled, welcomeText = editWelcomeText,
                    notSearchable = editNotSearchable, joinRequired = editJoinRequired,
                    announcements = buildAnnouncementsJson(editAnnouncements)
                )
                if (r.success) {
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "保存失败: ${r.message}", Toast.LENGTH_SHORT).show()
                }
            }
            onBack()
        }
    }

    // 子页面保存：全量提交群设置（含公告数组），成功后返回
    fun persistGroupSettings(
        name: String = editName,
        signature: String = editSignature,
        announcements: List<GroupAnnouncementData> = editAnnouncements,
        welcomeEnabled: Boolean = editWelcomeEnabled,
        welcomeText: String = editWelcomeText,
        onResult: (Boolean, String?) -> Unit
    ) {
        scope.launch {
            val r = ChatRepository.updateGroupSettings(
                groupId = internalGroupId, name = name,
                signature = signature, announcement = "",
                welcomeEnabled = welcomeEnabled, welcomeText = welcomeText,
                notSearchable = editNotSearchable, joinRequired = editJoinRequired,
                announcements = buildAnnouncementsJson(announcements)
            )
            onResult(r.success, if (r.success) null else r.message)
        }
    }

    LaunchedEffect(internalGroupId) {
        val result = com.aurora.chat.data.repository.ChatRepository.getGroupInfo(internalGroupId)
        if (result.success && result.data != null) {
            val data = result.data!!
            groupInfo = data
            editName = data.optString("name", groupName)
            editSignature = data.optString("signature", "")
            editAnnouncements = parseGroupAnnouncementsJson(data)
            editWelcomeEnabled = data.optBoolean("welcome_enabled", false)
            editWelcomeText = data.optString("welcome_text", "")
            editNotSearchable = data.optBoolean("not_searchable", false)
            editJoinRequired = data.optBoolean("join_required", false)
            editRemark = context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                .getString("remark_group_$internalGroupId", "") ?: ""
        }
        isLoading = false
    }

    // 加载群头像
    LaunchedEffect(internalGroupId) {
        val memCached = AvatarCache.getCachedUploadedGroupAvatar(internalGroupId)
        if (memCached != null) { avatarBitmap = memCached; return@LaunchedEffect }
        val diskCached = AvatarCache.getGroupAvatar(context, internalGroupId)
        if (diskCached != null) { avatarBitmap = diskCached; return@LaunchedEffect }
        val bmp = ChatRepository.loadGroupAvatar(context, internalGroupId)
        if (bmp != null) avatarBitmap = bmp
    }

    val displayId = groupInfo?.optLong("display_id", 0) ?: 0
    val userRole = groupInfo?.optString("user_role", "") ?: ""
    val creatorId = groupInfo?.optLong("creator_id", 0) ?: 0
    val currentUserId = com.aurora.chat.data.api.AuroraApi.currentUserId
    // ID为1的用户在所有群聊中拥有绝对修改权限
    val isOwner = userRole == "owner" || currentUserId == 1L || (creatorId > 0 && creatorId == currentUserId)
    // 群主 + 管理员 都能看"移除"按钮
    val isAdmin = isOwner || userRole == "admin" || currentUserId == 1L
    // 官方群（display_id=1 / 内部群 id=1 / 会话 id=-1001）：全员强制加入的公告频道。
    // 锁定策略：不提供"退出群聊"、不提供"移除成员"、不提供"解散/转让群主"。
    // 后端 handleRemoveGroupMember / handleDissolveGroup / handleTransferOwner 同步返回 403 兜底。
    val isOfficialGroup = displayId == 1L || internalGroupId == 1L
    val pendingJoinCount = groupInfo?.optInt("pending_join_count", 0) ?: 0
    // 细粒度权限（群主=全部；管理员=群主给他开的那几项；普通成员=空）。
    // user_perms 由 /api/groups/info 下发；旧后端没有该字段时按角色兜底，避免把所有入口锁死。
    val myPerms: Set<String>? = groupInfo?.optJSONArray("user_perms")?.let { arr ->
        val s = mutableSetOf<String>()
        for (i in 0 until arr.length()) s.add(arr.optString(i))
        s
    }
    fun canPerm(key: String): Boolean = isOwner || (isAdmin && (myPerms == null || myPerms.contains(key)))
    val noPermTip: (String) -> Unit = { what ->
        Toast.makeText(context, "没有「$what」的权限，请联系群主", Toast.LENGTH_SHORT).show()
    }

    val nonOwnerClick: () -> Unit = {
        if (!isOwner) Toast.makeText(context, "该群仅群主可修改", Toast.LENGTH_SHORT).show()
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (!canPerm("group_avatar")) return@rememberLauncherForActivityResult
        uri?.let {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }
                }
                if (bmp != null) {
                    avatarBitmap = bmp
                    AvatarCache.cacheUploadedGroupAvatar(internalGroupId, bmp)
                    com.aurora.chat.ui.viewmodel.ChatViewModel.refreshGroupAvatars()
                    scope.launch(NonCancellable) {
                        ChatRepository.uploadGroupAvatarBitmap(internalGroupId, bmp, context)
                    }
                    hasChanges = true
                }
            }
        }
    }

    // 弹窗用 zIndex 抬到主内容之上：LiquidDialog 液态模式是主窗口内的覆盖层，
    // 若不抬高，会被随后声明的主内容（白色列表）盖住而"点不出来"。
    Box(Modifier.zIndex(2f)) {
    // ── 解散群聊确认弹窗 ──
    if (showDissolveDialog) {
        AlertDialog(
            onDismissRequest = { showDissolveDialog = false },
            title = { Text("解散群聊", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定要解散此群聊吗？\n所有成员将被移除，群聊将永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                        showDissolveDialog = false
                        scope.launch {
                            val r = ChatRepository.dissolveGroup(internalGroupId)
                            if (r.success) {
                                // 本地先落地「群主已解散群聊」，不等服务端推送：
                                // 群从列表里消失的那一瞬会被兜底逻辑判成「你已被踢出群聊」，
                                // 而解散是我自己刚点的，原因再清楚不过。
                                val nowSec = System.currentTimeMillis() / 1000
                                val convId = -(1000 + internalGroupId)
                                runCatching {
                                    com.aurora.chat.data.local.GroupExitStore.record(
                                        context,
                                        com.aurora.chat.data.local.GroupExit(
                                            groupId = internalGroupId,
                                            groupName = editName,
                                            time = nowSec,
                                            reason = com.aurora.chat.data.local.GroupExit.REASON_DISSOLVED
                                        )
                                    )
                                    com.aurora.chat.data.local.LocalNoticeStore.save(
                                        context,
                                        com.aurora.chat.data.local.LocalNotice(
                                            conversationId = convId,
                                            text = "群主已解散群聊",
                                            createdAt = nowSec,
                                            type = "group_dissolved"
                                        )
                                    )
                                }
                                com.aurora.chat.PendingGroupKicked.convId = convId
                                com.aurora.chat.PendingGroupKicked.text = "群主已解散群聊"
                                com.aurora.chat.PendingGroupKicked.hasUpdate = true
                                Toast.makeText(context, "群聊已解散", Toast.LENGTH_SHORT).show()
                                onDissolved()
                            } else {
                                Toast.makeText(context, "解散失败: ${r.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("解散", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { showDissolveDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            },
            containerColor = com.aurora.chat.ui.theme.appSurface()
        )
    }

    // ── 退出群聊确认弹窗 ──
    if (showLeaveDialog) {
        val isLastMember = members.size <= 1
        val leaveHint = when {
            isOwner && isLastMember -> "该群无其他成员，退出后将解散群聊"
            isOwner -> "你是该群主，退出后将随机将群主权限分发至管理员，都未设置则随机分发群成员"
            userRole == "admin" -> "你是该群管理员，退出后将取消该权限，下次进入并不会保留权限"
            else -> "确定要退出此群聊吗？"
        }
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false }, containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("退出群聊", fontWeight = FontWeight.Bold) },
            text = { Text(leaveHint, fontSize = 14.sp, lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = {
                        showLeaveDialog = false
                        scope.launch {
                            val r = try {
                                ChatRepository.removeGroupMember(internalGroupId, currentUserId, false)
                            } catch (e: Exception) {
                                Toast.makeText(context, "退出失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            // safeCall 会把失败包成 ApiResult(false) 而不是抛异常，
                            // 不判 success 就会在退群失败时也把会话标成只读。
                            if (!r.success) {
                                Toast.makeText(context, "退出失败: ${r.message}", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            // 自己主动退群：立刻落地带真实原因的退出记录，不等服务端推送。
                            // 后端 isSelfLeave 分支只发群内广播（本人已不在群里，收不到），
                            // 最后一名成员退出时直接 deleteGroup，两条路都不给本人推定向事件；
                            // 于是群从会话列表消失后，recordVanishedGroups 兜底一律写成 REASON_KICKED，
                            // 底部只读条就变成「你已被踢出群聊」。这里与上面的「解散」按钮对称，
                            // 先落一条记录，兜底查到已有记录会直接跳过，不再误判成被踢。
                            val nowSec = System.currentTimeMillis() / 1000
                            val convId = -(1000 + internalGroupId)
                            // 群里只剩自己时退出 = 后端 deleteGroup 解散。
                            // 严格用 ==1 判定：成员名单尚未加载完（size == 0）时退回「你已退出群聊」，
                            // 不把「名单没拉到」误说成「群已解散」。
                            val dissolved = isOwner && members.size == 1
                            val exitText = if (dissolved) "群主已解散群聊" else "你已退出群聊"
                            runCatching {
                                com.aurora.chat.data.local.GroupExitStore.record(
                                    context,
                                    com.aurora.chat.data.local.GroupExit(
                                        groupId = internalGroupId,
                                        groupName = editName.ifBlank { groupName },
                                        time = nowSec,
                                        reason = if (dissolved)
                                            com.aurora.chat.data.local.GroupExit.REASON_DISSOLVED
                                        else com.aurora.chat.data.local.GroupExit.REASON_LEFT
                                    )
                                )
                                com.aurora.chat.data.local.LocalNoticeStore.save(
                                    context,
                                    com.aurora.chat.data.local.LocalNotice(
                                        conversationId = convId,
                                        text = exitText,
                                        createdAt = nowSec,
                                        type = if (dissolved) "group_dissolved" else "group_left"
                                    )
                                )
                            }
                            com.aurora.chat.PendingGroupKicked.convId = convId
                            com.aurora.chat.PendingGroupKicked.text = exitText
                            com.aurora.chat.PendingGroupKicked.hasUpdate = true
                            Toast.makeText(context, "已退出群聊", Toast.LENGTH_SHORT).show()
                            onDissolved()
                        }
                    }) { Text("退出", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }

    // ── 删除聊天确认弹窗（只读会话：本地彻底清理，服务端不动）──
    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { if (!deletingChat) showDeleteChatDialog = false }, containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("删除聊天", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "将从本机删除这个会话及其全部聊天记录、图片和文件，并从聊天列表移除。" +
                        "\n仅清理本地，服务端数据不受影响；删除后无法恢复。",
                    fontSize = 14.sp, lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (deletingChat) return@TextButton
                    deletingChat = true
                    scope.launch {
                        try {
                            deleteReadOnlyConversationLocalFully(
                                context, currentUserId, -(1000L + internalGroupId)
                            )
                            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                            showDeleteChatDialog = false
                            deletingChat = false
                            onDeleted()
                        } catch (e: Exception) {
                            deletingChat = false
                            Toast.makeText(context, "删除失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text(if (deletingChat) "删除中…" else "删除", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChatDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
    }

    // ── 主界面 ──
    Box(Modifier.fillMaxSize()) {
        val lazyListState = rememberLazyListState()
        // 动作格（邀请 / 移除）不放成员，其余位置全部放成员：每行 5 格 × 3 行 = 15 格。
        // 官方群锁定成员不可被踢，所以没有「移除」格 —— 此时少一个动作格就多显示一个成员，
        // 不再像以前那样写死 13/14，导致最后一格空着。
        val hasRemoveCell = canPerm("kick") && !isOfficialGroup
        val actionCellCount = if (hasRemoveCell) 2 else 1
        val memberLimit = 15 - actionCellCount
        // ── 加载群成员（在 LazyColumn 外，确保可执行）──
        // 只读态跳过：非成员的成员接口只会 403，而名单本来也不展示。
        LaunchedEffect(internalGroupId, readOnly) {
            if (readOnly) return@LaunchedEffect
            val r = ChatRepository.getGroupMembers(internalGroupId)
            if (r.success && r.data != null) {
                val list = mutableListOf<JSONObject>()
                for (i in 0 until r.data!!.length()) list.add(r.data!!.getJSONObject(i))
                members = list
            }
        }
        // 预加载可见成员头像：每个头像独立 launch 一个 IO 任务并行加载（Dispatchers.IO 自身限流 64 并发，保护连接）。
        // 每加载完一个立即更新 avatarMap，保留"加载出一个显示一个"的效果，同时去掉串行互相等待的阻塞。
        LaunchedEffect(members) {
            val want = members.take(memberLimit)
            // 先同步命中内存缓存：缓存过的头像立即上屏，不再先显示默认图再被替换
            val memHit = want.mapNotNull { m ->
                val uid = m.optLong("user_id")
                if (avatarMap.containsKey(uid)) null
                else AvatarCache.getCachedUserAvatarSync(uid)?.let { uid to it }
            }
            if (memHit.isNotEmpty()) avatarMap = avatarMap + memHit
            val toLoad = want.filter { !avatarMap.containsKey(it.optLong("user_id")) }
            if (toLoad.isEmpty()) return@LaunchedEffect
            for (m in toLoad) {
                val uid = m.optLong("user_id")
                launch(Dispatchers.IO) {
                    // loadAvatar 内部是「内存 → 磁盘 → 网络」，磁盘有图就不会再请求网络
                    val bmp = ChatRepository.loadAvatar(context, uid)
                    avatarMap = avatarMap + (uid to bmp)
                }
            }
        }
        // 固定顶栏（不随列表滚动）：灰色底 + 标题，盖住状态栏区域；
        // 列表内容在其下方滚动，状态栏不会再透出白色卡片，也不会在顶部出现空白块。
        Box(
            Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .background(com.aurora.chat.ui.theme.appCardBg())
                .statusBarsPadding()
        ) {
            var backClicked by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clickable(enabled = !backClicked) {
                        backClicked = true; handleBack()
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text("群聊信息", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(12.dp))
                // 三个点菜单（所有成员可见，普通成员仅"分享群聊"）：横向三点
                Box(
                    modifier = Modifier.size(28.dp).clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showManage = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⋯", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
            }
        }
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(top = 48.dp),
            modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg()).statusBarsPadding()
        ) {

        item { Spacer(Modifier.height(16.dp)) }

        // ── 信息区（灰底直放，不卡片化）：头像 + 群名展示 + 群号 ──
        // 只读态（被移出 / 已退出）照常展示：头像/群名/群号/二维码是群的身份标识，
        // 不是会变、会过期的"群资料"，也没有隐私可言（搜索里就能看到）。
        // 只读态只改交互：头像不可点（改不了头像）、不进入「群资料」（签名/公告都在那儿）。
        item {
        val avatarClickable: Modifier = if (readOnly) Modifier
            else if (canPerm("group_avatar")) Modifier.clickable { imagePicker.launch("image/*") }
            else Modifier.clickable { noPermTip("修改群头像") }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(50.dp)
                    .clip(RoundedCornerShape(avatarCornerRadius(50.dp))).background(com.aurora.chat.ui.theme.appDivider()).then(avatarClickable),
                contentAlignment = Alignment.Center
            ) {
                if (internalGroupId == 1L) {
                    // 官方群：强制渲染应用图标，忽略任何上传/缓存的群头像
                    Image(painter = painterResource(com.aurora.chat.R.drawable.ic_app_icon),
                        contentDescription = "群头像", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else if (avatarBitmap != null) {
                    Image(bitmap = avatarBitmap!!.asImageBitmap(), contentDescription = "群头像",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Image(painter = painterResource(com.aurora.chat.R.drawable.ic_group_default),
                        contentDescription = "群头像", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                // 名称行：长名 weight(1f) 限宽省略，右侧为群二维码图标占位（功能后续实现）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (editName.isNotBlank()) editName else "未命名群聊",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.QrCode2,
                        contentDescription = "群二维码",
                        tint = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(18.dp).clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showGroupQr = true }
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 群号行：复制群号 + 靠右箭头（位于二维码正下方），点击进入群资料界面（不再往下滑）
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    Text("群号：$displayId", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.clickable {
                            clipboard.setPrimaryClip(ClipData.newPlainText("群ID", displayId.toString()))
                            Toast.makeText(context, "已复制群ID: $displayId", Toast.LENGTH_SHORT).show()
                        })
                    Spacer(Modifier.weight(1f))
                    // 只读态不给「群资料」入口：那一页是签名/公告/欢迎语，非成员看到的只会是旧值。
                    if (!readOnly) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "群资料",
                        tint = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(16.dp).clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showGroupProfile = true }
                    )
                    }
                }
            }
        }
        }

        item { Spacer(Modifier.height(16.dp)) }

        // ── 群成员卡（白卡，第一板块）：标题行(人数+空心右三角入口) + 三行网格(成员/邀请/移除) ──
        // 只读态（被移出 / 已退出）整块不渲染：我已经不是成员，既不该继续看到名单，
        // 成员接口对非成员也已经没有返回。
        if (!readOnly) {
        item {
        // 格子序列：成员 + 动作格（邀请 / 移除），总数正好 15 格（每行 5 格 × 3 行）
        val gridItems: List<Any> = buildList {
            addAll(members.take(memberLimit))
            add("invite")
            if (hasRemoveCell) add("remove")
        }
        // 每格宽度自适应：外层 item padding 16*2 + 卡内 padding 12*2 = 56dp，行内 4 个 8dp 间隔 = 32dp
        val cellWidthDp = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 56 - 32) / 5).coerceAtLeast(48)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(horizontal = 12.dp, vertical = 12.dp)) {
            // 标题行：群成员 + 真实人数 + 右箭头（点击进入完整成员界面）
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("群成员", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.weight(1f))
                Text("${members.size}人", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "群成员列表",
                    tint = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.size(16.dp).clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { openMembers() }
                )
            }
            Spacer(Modifier.height(8.dp))
            for (row in gridItems.chunked(5)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
                ) {
                    for (cell in row) {
                        if (cell is JSONObject) {
                            val uid = cell.optLong("user_id")
                            val rawName = cell.optString("username", "")
                            val uname = if (rawName.isNotBlank()) rawName else "用户$uid"
                            Column(Modifier.width(cellWidthDp.dp).clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { openProfile(uid, uname) },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                // 内存里已有就直接画（例如刚在别处显示过），否则等 avatarMap 异步补上
                                val bmp = avatarMap[uid] ?: AvatarCache.getCachedUserAvatarSync(uid)
                                if (bmp != null) {
                                    Image(bitmap = bmp.asImageBitmap(), contentDescription = uname,
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(avatarCornerRadius(40.dp))),
                                        contentScale = ContentScale.Crop)
                                } else {
                                    Image(painter = painterResource(com.aurora.chat.R.drawable.ic_profile_filled),
                                        contentDescription = uname,
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(avatarCornerRadius(40.dp))))
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(uname, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = com.aurora.chat.ui.theme.appTextPrimary(), textAlign = TextAlign.Center)
                            }
                        } else if (cell == "invite") {
                            // 邀请按钮（普通成员也可见）
                            Column(Modifier.width(cellWidthDp.dp).clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showAddMember = true },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(avatarCornerRadius(40.dp))).background(Color(0xFF1E40AF)),
                                    contentAlignment = Alignment.Center) {
                                    Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text("邀请", fontSize = 11.sp, color = Color(0xFF1E40AF), textAlign = TextAlign.Center)
                            }
                        } else if (cell == "remove") {
                            // 移除按钮（管理员可见，官方群隐藏：锁定策略下成员不可被踢）
                            Column(Modifier.width(cellWidthDp.dp).clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showRemoveMember = true },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(avatarCornerRadius(40.dp))).background(Color(0xFFDC2626)),
                                    contentAlignment = Alignment.Center) {
                                    Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text("移除", fontSize = 11.sp, color = Color(0xFFDC2626), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        }
        }
        // 这些间隔只属于被隐藏的卡片；只读态卡片全跳过，间隔也一并跳过，否则会留一段空白
        if (!readOnly) {
        item { Spacer(Modifier.height(12.dp)) }
        }

        // ── 群资料卡（白卡）：群名称 / 群备注 / 群签名 / 群公告 / 群欢迎语 路口入口 ──
        // 只读态整块不渲染：群名/备注/签名随时会被群主改，我手里只是离开时的旧值，
        // 展示出来等于给一个"看起来像最新"的假信息；公告/欢迎语/通知方式更属于群内信息。
        // 标题栏的「群聊信息」保留，那是页面身份，不是群资料。
        if (!readOnly) {
        item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(horizontal = 12.dp, vertical = 4.dp)) {
            // 1. 群名称（仅群主/管理员可改）
            Row(Modifier.fillMaxWidth().clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (canPerm("group_name")) showEditName = true
                else noPermTip("修改群名称")
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("群名称", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.width(8.dp))
                Text(if (editName.isNotBlank()) editName else "未设置", fontSize = 14.sp,
                    color = if (editName.isNotBlank()) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextHint(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Icon(Icons.Filled.ChevronRight, contentDescription = "修改群名称",
                    tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(16.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
            // 2. 群备注（个人本地设置，任何人可改）
            Row(Modifier.fillMaxWidth().clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { showEditRemark = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("群备注", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.width(8.dp))
                Text(if (editRemark.isNotBlank()) editRemark else "未设置", fontSize = 14.sp,
                    color = if (editRemark.isNotBlank()) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextHint(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Icon(Icons.Filled.ChevronRight, contentDescription = "修改群备注",
                    tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(16.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
            // 3. 群签名（仅群主/管理员可改）
            Row(Modifier.fillMaxWidth().clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (canPerm("group_signature")) showEditSignature = true
                else noPermTip("修改群签名")
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("群签名", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.width(8.dp))
                Text(if (editSignature.isNotBlank()) editSignature else "未设置", fontSize = 14.sp,
                    color = if (editSignature.isNotBlank()) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextHint(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Icon(Icons.Filled.ChevronRight, contentDescription = "修改群签名",
                    tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(16.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
            // 4~6. 群通知方式 / 群公告 / 群欢迎语
            // （整张资料卡已在只读态整体跳过，原来这里还套了一层 readOnly 判断，已随之移除）
            // 4. 群通知方式（仅群主 / 有权限的管理员可设置；普通成员只能看到当前方式）
            Row(Modifier.fillMaxWidth().clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (canPerm("group_notify")) showGroupNotify = true
                else Toast.makeText(context, "仅群主或管理员可设置群通知方式", Toast.LENGTH_SHORT).show()
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("群通知方式", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(2.dp))
                    // 摘要行：只显示当前通知方式
                    Text(
                        notifySummary.ifBlank { "以群身份通知" },
                        fontSize = 14.sp,
                        color = com.aurora.chat.ui.theme.appTextHint(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = "群通知方式",
                    tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(16.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
            // 5. 群公告（标题 + 下方预览一段，点进列表页；成员可查看，仅管理员可编辑）
            Row(Modifier.fillMaxWidth().clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                showAnnouncements = true
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("群公告", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(2.dp))
                    val preview = editAnnouncements.firstOrNull()?.text ?: ""
                    Text(if (preview.isNotBlank()) preview else "暂无公告", fontSize = 14.sp,
                        color = if (preview.isNotBlank()) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextHint(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = "群公告",
                    tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(16.dp))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
            // 6. 群欢迎语（仅群主/管理员可改）
            Row(Modifier.fillMaxWidth().clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                if (canPerm("group_welcome")) showEditWelcome = true
                else noPermTip("修改群欢迎语")
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("群欢迎语", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(2.dp))
                    val welcomeShow = if (!editWelcomeEnabled) "未开启"
                        else if (editWelcomeText.isNotBlank()) editWelcomeText else "暂无欢迎语"
                    Text(welcomeShow, fontSize = 14.sp,
                        color = if (editWelcomeEnabled) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextHint(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = "修改群欢迎语",
                    tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(16.dp))
            }
            // 7. 群敏感词
            if (canPerm("group_sensitive")) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                Row(Modifier.fillMaxWidth().clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { showSensitiveWords = true }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("群敏感词", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                        Spacer(Modifier.height(2.dp))
                        val actionSummary = when (sensitiveAction) {
                            "mute" -> "禁言"
                            "kick" -> "移出群聊"
                            else -> "仅撤回"
                        }
                        val summary = when {
                            !sensitiveEnabled && !sensitiveBlockLinks -> "未开启"
                            else -> buildList {
                                if (sensitiveEnabled) {
                                    add(if (sensitiveWordCount > 0) "${sensitiveWordCount} 个词" else "敏感词已开启")
                                }
                                if (sensitiveBlockLinks) add("禁止发链接")
                            }.joinToString("，") + "，" + actionSummary
                        }
                        Text(summary, fontSize = 14.sp,
                            color = if (sensitiveEnabled || sensitiveBlockLinks) com.aurora.chat.ui.theme.appTextPrimary() else com.aurora.chat.ui.theme.appTextHint(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = "群敏感词",
                        tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        }
        }
        if (!readOnly) {
        item { Spacer(Modifier.height(12.dp)) }
        }

        // ── 入群设置卡（白卡）：无法被搜索 / 入群需申请 ──
        // 只读态不显示：这是群管理设置，离开之后既改不了也不该关心
        if (!readOnly) {
        item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("无法被搜索", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.weight(1f))
                Switch(checked = editNotSearchable,
                        onCheckedChange = { if (isAdmin) { editNotSearchable = it; hasChanges = true } else { Toast.makeText(context, "仅群主或管理员可修改", Toast.LENGTH_SHORT).show() } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("入群需申请", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.weight(1f))
                Switch(checked = editJoinRequired,
                        onCheckedChange = { if (isAdmin) { editJoinRequired = it; hasChanges = true } else { Toast.makeText(context, "仅群主或管理员可修改", Toast.LENGTH_SHORT).show() } },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
            }
            // 群黑名单
            if (canPerm("group_blacklist")) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                Row(
                    Modifier.fillMaxWidth().clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showGroupBlacklist = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("群黑名单", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, contentDescription = "群黑名单",
                        tint = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.size(16.dp))
                }
            }
                Spacer(Modifier.height(4.dp))
        }
        }
        }
        if (!readOnly) {
        item { Spacer(Modifier.height(12.dp)) }
        }

        // ── 查找聊天记录（单独板块）：点击进入全屏搜索 ──
        item {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onOpenHistorySearch() }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("查找聊天记录", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "查找聊天记录",
                        tint = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }

        // ── 消息设置卡（白卡）：置顶 / 免打扰 ──
        // 只读态（被移出 / 已退出 / 群已解散 / 待审核）不显示：
        // 「置顶」在聊天列表里同样能操作；「免打扰」更是没有意义——我已经不在群里，不会再被打扰。
        // 这块位置改成下面的「删除聊天」，那是只读会话唯一还需要做的事。
        if (!readOnly) {
        item {
        var isPinned by remember { mutableStateOf(false) }
        var isGroupMuted by remember { mutableStateOf(false) }
        val pinPrefs = context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
        // 群会话 ID = -(1000 + 内部群ID)：与本地存储键、服务端免打扰列表口径一致
        val groupConvId = -(1000L + internalGroupId)
        LaunchedEffect(Unit) {
            isPinned = pinPrefs.getBoolean("pin_group_$internalGroupId", false)
            isGroupMuted = pinPrefs.getBoolean("dnd_group_$internalGroupId", false)
        }
        // 把「群免打扰」同步到服务端：以负数会话ID 记入免打扰列表（与私聊免打扰共用 dnd_ids），
        // 这样即使离线被 @，邮箱推送也会被服务端跳过
        fun syncGroupMutedToServer(enabled: Boolean) {
            val uid = AuroraApi.currentUserId
            if (uid <= 0) return
            val ids = DataCache.loadPushDndIds(context, uid).toMutableList()
            if (enabled) { if (!ids.contains(groupConvId)) ids.add(groupConvId) } else { ids.remove(groupConvId) }
            val method = DataCache.loadPushMethod(context, uid)
            val tpl = DataCache.loadPushTemplateMode(context, uid)
            val mail = DataCache.loadOwnEmail(context, uid)
            val auth = DataCache.loadOwnAuth(context, uid)
            val notify = DataCache.loadNotifyEnabled(context, uid)
            DataCache.savePushConfig(context, uid, method, tpl, mail, auth, ids, notify)
            scope.launch {
                try { AuroraApi.savePushConfig(method, tpl, mail, auth, ids, notify) } catch (_: Exception) { }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("置顶", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.weight(1f))
                Switch(checked = isPinned,
                        onCheckedChange = { newVal: Boolean ->
                            isPinned = newVal
                            pinPrefs.edit().putBoolean("pin_group_$internalGroupId", newVal).apply()
                            com.aurora.chat.ui.viewmodel.ChatViewModel.notifyPinChanged()
                            // 同步到服务端：重装/换机后群置顶还在
                            com.aurora.chat.data.local.ClientPrefsSync.schedulePush(context, immediate = true)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("免打扰", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.weight(1f))
                Switch(checked = isGroupMuted,
                        onCheckedChange = { newVal: Boolean ->
                            isGroupMuted = newVal
                            pinPrefs.edit().putBoolean("dnd_group_$internalGroupId", newVal).apply()
                            syncGroupMutedToServer(newVal)
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
            }
            Spacer(Modifier.height(4.dp))
        }
        }
        item { Spacer(Modifier.height(12.dp)) }
        }

        // ── 只读会话的「删除聊天」：本地彻底清理 ──
        // 只读态才有意义：正常的群要删会话，长按聊天列表项即可；这里出现是因为
        // 被移出 / 已退出 / 群已解散之后，用户往往再也不需要这段会话，却必须走列表长按才能删。
        if (readOnly) {
        item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())) {
            Text("删除聊天", fontSize = 14.sp, color = Color(0xFFDC2626),
                modifier = Modifier.fillMaxWidth().clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { showDeleteChatDialog = true }.padding(vertical = 14.dp),
                textAlign = TextAlign.Center)
        }
        }
        item { Spacer(Modifier.height(12.dp)) }
        }

        // ── 加载群成员 ──
        // （LaunchedEffect 已移至 LazyColumn 外）

        item { Spacer(Modifier.height(12.dp)) }

        // ── 危险操作卡（白卡）：退出群聊 ──
        // 只读态（被移出 / 已退出）不显示：我已经不在群里，"退出"这个动作没有意义，后端也会拒绝。
        if (!readOnly) {
        item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())) {
            if (isOfficialGroup) {
                Text("官方群为全员频道，不支持退出", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    textAlign = TextAlign.Center)
            } else {
                Text("退出群聊", fontSize = 14.sp, color = Color(0xFFDC2626),
                    modifier = Modifier.fillMaxWidth().clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { showLeaveDialog = true }.padding(vertical = 14.dp),
                    textAlign = TextAlign.Center)
            }
        }
        }
        }
        item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ── 群管理全屏页（右上角「⋯」直接切入；分享/发言频率/禁言/入群申请/设置管理员/管理员权限/转让群主）──
    AnimatedVisibility(
        visible = showManage,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupManageScreen(
            groupId = internalGroupId,
            groupName = editName.ifEmpty { groupName },
            groupSignature = editSignature,
            currentUserId = currentUserId,
            isOwner = isOwner,
            isOfficialGroup = isOfficialGroup,
            canPerm = { key -> canPerm(key) },
            onBack = { showManage = false },
            onOpenJoinRequestList = onOpenJoinRequestList,
            onDissolve = { showManage = false; showDissolveDialog = true }
        )
    }

    // ── 添加成员面板 ──
    if (showAddMember) {
        AddMemberPanel(
            groupId = internalGroupId,
            onBack = { showAddMember = false },
            onMemberAdded = {
                showAddMember = false
                scope.launch {
                    val r = ChatRepository.getGroupMembers(internalGroupId)
                    if (r.success && r.data != null) {
                        val newList = mutableListOf<org.json.JSONObject>()
                        for (i in 0 until r.data!!.length()) newList.add(r.data!!.getJSONObject(i))
                        members = newList
                    }
                }
            }
        )
    }

    // ── 移除成员面板 ──
    if (showRemoveMember) {
        RemoveMemberPanel(
            groupId = internalGroupId,
            onBack = { showRemoveMember = false },
            onMemberRemoved = {
                showRemoveMember = false
                // 移除后刷新成员列表
                scope.launch {
                    val r = ChatRepository.getGroupMembers(internalGroupId)
                    if (r.success && r.data != null) {
                        val newList = mutableListOf<org.json.JSONObject>()
                        for (i in 0 until r.data!!.length()) newList.add(r.data!!.getJSONObject(i))
                        members = newList
                    }
                }
            }
        )
    }

    // ── 群二维码全屏界面 ──
    if (showGroupQr) {
        GroupQrScreen(
            groupId = internalGroupId,
            groupName = if (editName.isNotBlank()) editName else "未命名群聊",
            displayId = displayId,
            // 非成员不展示签名：手里的只是离开时的旧值，而二维码本身跟签名无关。
            groupSignature = if (readOnly) "" else editSignature,
            avatarBitmap = avatarBitmap,
            onBack = { showGroupQr = false }
        )
    }

    // ── 群资料全屏界面（点击群号右侧箭头进入） ──
    if (showGroupProfile) {
        GroupProfileScreen(
            groupId = internalGroupId,
            groupName = if (editName.isNotBlank()) editName else "未命名群聊",
            displayId = displayId,
            signature = editSignature,
            announcements = editAnnouncements.map { it.text },
            welcomeEnabled = editWelcomeEnabled,
            welcomeText = editWelcomeText,
            memberCount = members.size,
            avatarBitmap = avatarBitmap,
            onBack = { showGroupProfile = false }
        )
    }

    // ── 群通知全屏界面（入群设置卡内"群通知"进入，从右向左切入） ──
    AnimatedVisibility(
        visible = showGroupNotify,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupNotifyScreen(
            groupId = internalGroupId,
            onBack = { showGroupNotify = false }
        )
    }

    // ── 群敏感词全屏界面（群资料卡 →「群敏感词」，仅群主 / 管理员） ──
    AnimatedVisibility(
        visible = showSensitiveWords,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupSensitiveWordsScreen(
            groupId = internalGroupId,
            onBack = { showSensitiveWords = false },
            onSaved = { en, count, act, blockLinks ->
                sensitiveEnabled = en
                sensitiveWordCount = count
                sensitiveAction = act
                sensitiveBlockLinks = blockLinks
            }
        )
    }

    // ── 群黑名单全屏界面（入群设置卡 →「群黑名单」，仅群主 / 管理员） ──
    AnimatedVisibility(
        visible = showGroupBlacklist,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupBlacklistScreen(
            groupId = internalGroupId,
            onBack = { showGroupBlacklist = false }
        )
    }

    // ── 群内导航栈返回处理器（重写核心）：声明在覆盖层之前 → 覆盖层内 BackHandler（如"发送申请"子面板）优先；
    // 箭头与左滑都走 popGroupNav，永远只 pop 栈顶一层，物理上不可能跳两级 ──
    BackHandler(enabled = groupNavStack.size > 1) { popGroupNav() }

    // ── 群聊成员全屏界面（群内导航栈第二层，从右向左切入） ──
    AnimatedVisibility(
        visible = groupNavStack.contains(GroupNavPage.MEMBERS),
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupMembersScreen(
            groupId = internalGroupId,
            onBack = { popGroupNav() },
            onOpenProfile = { uid, name -> openProfile(uid, name) },
            // 复用群信息页已拉到的成员：进成员列表不再二次拉全量、不用等网络
            initialMembers = members
        )
    }

    // ── 群资料子页面（路口入口进入，从右向左切入转场） ──
    AnimatedVisibility(
        visible = showEditName,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupSimpleEditScreen(
            title = "修改群名称",
            label = "群名称",
            initial = editName,
            maxLength = 20,
            onBack = { showEditName = false },
            onSave = { newName ->
                editName = newName; hasChanges = false
                persistGroupSettings(onResult = { ok, msg ->
                    Toast.makeText(context, if (ok) "已保存" else "保存失败: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) showEditName = false
                })
            }
        )
    }
    AnimatedVisibility(
        visible = showEditRemark,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupRemarkEditScreen(
            initial = editRemark,
            onBack = { showEditRemark = false },
            onSave = { newRemark ->
                editRemark = newRemark
                context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                    .edit().putString("remark_group_$internalGroupId", newRemark).apply()
                showEditRemark = false
            }
        )
    }
    AnimatedVisibility(
        visible = showEditSignature,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupSimpleEditScreen(
            title = "修改群签名",
            label = "群签名",
            initial = editSignature,
            maxLength = 30,
            onBack = { showEditSignature = false },
            onSave = { newSignature ->
                editSignature = newSignature; hasChanges = false
                persistGroupSettings(onResult = { ok, msg ->
                    Toast.makeText(context, if (ok) "已保存" else "保存失败: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) showEditSignature = false
                })
            }
        )
    }
    AnimatedVisibility(
        visible = showAnnouncements,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupAnnouncementsFlow(
            initialAnnouncements = editAnnouncements,
            canEdit = canPerm("group_announcement"),
            groupId = internalGroupId,
            onBack = { showAnnouncements = false },
            onSaved = { newList ->
                editAnnouncements = newList; hasChanges = false
                persistGroupSettings(announcements = newList, onResult = { ok, msg ->
                    Toast.makeText(context, if (ok) "已保存" else "保存失败: $msg", Toast.LENGTH_SHORT).show()
                })
            },
            onPostAnnouncement = { ann ->
                scope.launch {
                    val r = AuroraApi.postGroupAnnouncement(internalGroupId, ann.text, ann.needConfirm, ann.images)
                    if (!r.success) Toast.makeText(context, "发送失败: ${r.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onConfirm = { ann ->
                scope.launch {
                    val r = AuroraApi.confirmGroupAnnouncement(internalGroupId, ann.text)
                    if (r.success) {
                        editAnnouncements = editAnnouncements.map { a ->
                            if (a.text == ann.text) a.copy(confirmedByMe = true, confirmCount = a.confirmCount + 1) else a
                        }
                        // 同步本地确认记录：聊天内公告卡片按本地集合判断「已确认」，避免重进会话又显示未确认
                        try {
                            val set = DataCache.loadConfirmedAnnouncementTexts(context, AuroraApi.currentUserId, internalGroupId).toMutableSet()
                            set.add(ann.text)
                            DataCache.saveConfirmedAnnouncementTexts(context, AuroraApi.currentUserId, internalGroupId, set.toList())
                        } catch (_: Exception) {}
                    } else Toast.makeText(context, "确认失败: ${r.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    AnimatedVisibility(
        visible = showEditWelcome,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(animationSpec = tween(260)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(animationSpec = tween(260))
    ) {
        GroupWelcomeEditScreen(
            initialEnabled = editWelcomeEnabled,
            initialText = editWelcomeText,
            onBack = { showEditWelcome = false },
            onSave = { enabled, text ->
                editWelcomeEnabled = enabled; editWelcomeText = text; hasChanges = false
                persistGroupSettings(onResult = { ok, msg ->
                    Toast.makeText(context, if (ok) "已保存" else "保存失败: $msg", Toast.LENGTH_SHORT).show()
                    if (ok) showEditWelcome = false
                })
            }
        )
    }

    // ── 系统返回键：其它子面板（编辑群名/备注/签名等，非导航栈层级）打开时优先关闭 ──
    val anySubPageOpen = showEditName || showEditRemark || showEditSignature || showAnnouncements ||
        showEditWelcome || showGroupProfile || showGroupQr || showAddMember || showRemoveMember ||
        showGroupNotify || showSensitiveWords || showGroupBlacklist
    BackHandler(enabled = anySubPageOpen) {
        when {
            showEditName -> showEditName = false
            showEditRemark -> showEditRemark = false
            showEditSignature -> showEditSignature = false
            showAnnouncements -> showAnnouncements = false
            showEditWelcome -> showEditWelcome = false
            showGroupProfile -> showGroupProfile = false
            showGroupNotify -> showGroupNotify = false
            showSensitiveWords -> showSensitiveWords = false
            showGroupBlacklist -> showGroupBlacklist = false
            showGroupQr -> showGroupQr = false
            showAddMember -> showAddMember = false
            showRemoveMember -> showRemoveMember = false
        }
    }

    // ── 用户资料（群内导航栈第三层，内联 UserProfileCard，从右向左切入；不再走顶层 showUserProfile） ──
    AnimatedVisibility(
        visible = groupNavStack.lastOrNull() == GroupNavPage.PROFILE,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
            com.aurora.chat.ui.components.EventBlocker()
            // 覆盖在群成员之上、组合序最深 → 系统返回优先接管，逐层回退到群成员界面
            BackHandler(enabled = true) { popGroupNav() }
            UserProfileCard(
                userId = profileUid,
                userName = profileName,
                currentUserId = currentUserId,
                onBack = { popGroupNav() },
                onDeleteFriend = {
                    scope.launch {
                        try {
                            val r = com.aurora.chat.data.repository.ChatRepository.deleteFriend(profileUid)
                            Toast.makeText(context, if (r.success) "已删除好友" else "删除失败：${r.message}", Toast.LENGTH_SHORT).show()
                            if (r.success) com.aurora.chat.ui.contacts.invalidateFriendCache(context, currentUserId)
                        } catch (e: Exception) {
                            Toast.makeText(context, "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        popGroupNav()
                    }
                },
                onOpenChat = { uid, name -> popGroupNav(); onOpenChat(uid, name) }
            )
        }
    }
}

// ── 设置管理面板 ──
@Composable
internal fun AdminManagementPanel(
    groupId: Long,
    onBack: () -> Unit,
    onOpenJoinRequestList: (org.json.JSONArray, Long) -> Unit = { _, _ -> },
    // 是否有权变更成员角色（设置/取消管理员）。仅群主为 true；管理员进本页为只读。
    canManageAdmins: Boolean = false
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(groupId) {
        val r = ChatRepository.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val list = mutableListOf<JSONObject>()
            for (i in 0 until r.data!!.length()) list.add(r.data!!.getJSONObject(i))
            members = list
        }
    }

    // 过滤结果必须由 (members, searchQuery) 派生，不能存成独立 state 再靠 LaunchedEffect(searchQuery) 刷新：
    // 设置/取消管理员后 members 已更新，但 searchQuery 没变 → 那个 effect 不重跑 → 列表仍拿旧快照，
    // 表现为按钮还显示「设置」，必须退出面板重进才能看到最新角色。
    val filteredMembers = remember(members, searchQuery) {
        if (searchQuery.isBlank()) {
            members
        } else {
            members.filter { m ->
                val uid = m.optLong("user_id").toString()
                val un = m.optString("username", "")
                uid.contains(searchQuery, ignoreCase = true) ||
                un.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // 设置管理员确认弹窗
    var confirmTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    // 用 zIndex 抬到列表之上：LiquidDialog 液态模式是主窗口内的覆盖层，声明在内容前会被列表盖住
    Box(Modifier.zIndex(2f)) {
    if (confirmTarget != null) {
        val (targetId, targetName) = confirmTarget!!
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("设置管理员", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定将「$targetName」设为管理员吗？") },
            confirmButton = {
                TextButton(onClick = {
                        confirmTarget = null
                        scope.launch {
                            val r = ChatRepository.setGroupAdmin(groupId, targetId, "admin")
                            if (r.success) {
                                Toast.makeText(ctx, "已设为管理员", Toast.LENGTH_SHORT).show()
                                // 刷新列表
                                val r2 = ChatRepository.getGroupMembers(groupId)
                                if (r2.success && r2.data != null) {
                                    val list = mutableListOf<JSONObject>()
                                    for (i in 0 until r2.data!!.length()) list.add(r2.data!!.getJSONObject(i))
                                    members = list
                                }
                            } else {
                                Toast.makeText(ctx, "设置失败: ${r.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            },
            containerColor = com.aurora.chat.ui.theme.appSurface()
        )
    }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(28.dp).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
            }
            Spacer(Modifier.width(12.dp))
            Text("设置管理", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.weight(1f))
            Text("入群申请", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp)).background(com.aurora.chat.ui.theme.appSelectedBg())
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        scope.launch {
                            // 之前异常被 catch 吞掉：接口 403/500 或解析失败都毫无反馈，用户只看到"点了没反应"。
                            // 现在失败给出明确提示，成功无论有无数据都进面板（空列表由面板自己展示空态）。
                            try {
                                val r = AuroraApi.getJoinRequests(groupId)
                                if (r.success && r.data != null) {
                                    onOpenJoinRequestList(r.data, groupId)
                                } else {
                                    Toast.makeText(ctx, "获取入群申请失败: ${r.message}", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "获取入群申请失败: ${e.localizedMessage ?: "网络异常"}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }

        // 非群主（管理员）提示：本页只读，角色变更需群主操作
        if (!canManageAdmins) {
            Text(
                "仅群主可设置或取消管理员",
                fontSize = 12.sp,
                color = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
        }

        // 搜索框
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp))
                .background(com.aurora.chat.ui.theme.appCardBg()).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (searchQuery.isEmpty()) Text("搜索成员...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        // 成员列表
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(filteredMembers, key = { it.optLong("user_id") }) { m ->
                val uid = m.optLong("user_id")
                val rn = m.optString("username", "")
                val email = m.optString("email", "")
                val role = m.optString("role", "")
                val un = if (rn.isNotBlank()) rn else "用户$uid"
                val roleLabel = when (role) { "owner" -> "群主"; "admin" -> "管理员"; else -> "成员" }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserAvatar(userId = uid, userName = un, size = 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(un, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (email.isNotBlank()) email else "ID: $uid · $roleLabel",
                            fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (role == "owner") {
                        Text("群主", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(end = 8.dp))
                    } else if (!canManageAdmins) {
                        // 管理员（非群主）查看本页：只显示身份标签，不提供任何角色变更入口
                        Text(roleLabel, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(end = 8.dp))
                    } else if (role == "admin") {
                        // 取消管理员按钮
                        Text("取消管理", fontSize = 12.sp, color = Color(0xFFDC2626),
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val r = ChatRepository.setGroupAdmin(groupId, uid, "member")
                                    if (r.success) {
                                        Toast.makeText(ctx, "已取消管理员", Toast.LENGTH_SHORT).show()
                                        val r2 = ChatRepository.getGroupMembers(groupId)
                                        if (r2.success && r2.data != null) {
                                            val list = mutableListOf<JSONObject>()
                                            for (i in 0 until r2.data!!.length()) list.add(r2.data!!.getJSONObject(i))
                                            members = list
                                        }
                                    }
                                }
                            }.padding(horizontal = 8.dp, vertical = 4.dp))
                    } else {
                        // 设为管理员按钮
                        Text("设置", fontSize = 12.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { confirmTarget = uid to un }
                                .clip(RoundedCornerShape(4.dp)).background(com.aurora.chat.ui.theme.appSelectedBg())
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            }
        }
    }
}

@Composable
private fun SettingsTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(4.dp))
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(com.aurora.chat.ui.theme.appCardBg())
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (value.isEmpty() && label.isNotEmpty()) Text("无", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                })
        }
    }
}

// ==================== 添加成员面板 ====================

@Composable
private fun AddMemberPanel(
    groupId: Long,
    onBack: () -> Unit,
    onMemberAdded: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 群内成员 id：搜索结果里这些人不该再出现「拉入」。后端对成员只会回一句「已在群聊」，
    // 而 UI 照样给按钮，等于让人反复点一个必然无效的入口。
    var memberIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 本次面板内已发出邀请 / 已提交申请的人：还不是成员，但入口已经用掉了，不该再显示「拉入」
    var pendingIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 拉人面板必须知道自己群里已经有谁：进面板先取一次成员表，搜索列表据此标状态。
    // 搜索结果本身（/api/users/search）只返回好友资料，不含任何群关系字段。
    LaunchedEffect(groupId) {
        val r = ChatRepository.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val ids = HashSet<Long>()
            for (i in 0 until r.data!!.length()) {
                val id = r.data!!.optJSONObject(i)?.optLong("user_id", 0L) ?: 0L
                if (id > 0L) ids.add(id)
            }
            memberIds = ids
        }
    }

    // 首次加载显示全部用户
    LaunchedEffect(Unit) {
        loading = true
        val r = ChatRepository.searchUsersForGroup("")
        if (r.success && r.data != null) {
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until r.data!!.length()) list.add(r.data!!.getJSONObject(i))
            searchResults = list
        }
        loading = false
    }

    // 搜索时过滤
    LaunchedEffect(keyword) {
        if (keyword.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        val r = ChatRepository.searchUsersForGroup(keyword)
        if (r.success && r.data != null) {
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until r.data!!.length()) list.add(r.data!!.getJSONObject(i))
            searchResults = list
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 事件拦截层：白底 + 消费空白区域点击，防止事件穿透到背景的设置面板
        EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1E40AF), modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Text("拉人入群", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg())
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(value = keyword, onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (keyword.isEmpty()) Text("搜索用户...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                })
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(searchResults, key = { it.optLong("id") }) { u ->
                    val uid = u.optLong("id")
                    val un = u.optString("username", "")
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(userId = uid, userName = un, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(un, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                            modifier = Modifier.weight(1f))
                        if (uid in memberIds) {
                            // 已是群成员：只给状态，不给可点按钮
                            Text("在群内", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = com.aurora.chat.ui.theme.appTextHint(),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                                    .padding(horizontal = 12.dp, vertical = 6.dp))
                        } else if (uid in pendingIds) {
                            // 邀请已发出 / 申请已提交，人还没进群：入口用掉了，标状态不再给按钮
                            Text("已邀请", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = Color(0xFFB45309),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFF7ED))
                                    .padding(horizontal = 12.dp, vertical = 6.dp))
                        } else {
                            Text("拉入", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp)).background(com.aurora.chat.ui.theme.appSelectedBg())
                                    .clickable {
                                        scope.launch {
                                            val r = ChatRepository.addGroupMember(groupId, uid)
                                            if (r.success) {
                                                // 显示服务端返回的真实结果（已加入群聊 / 已发送入群邀请 / 已提交入群申请）
                                                val msg = r.message
                                                Toast.makeText(ctx, msg.ifBlank { "已拉入 $un" }, Toast.LENGTH_SHORT).show()
                                                when {
                                                    // 已在群：后端 200 只回一句提示，本行就地切成成员态
                                                    msg.contains("已在群") -> memberIds = memberIds + uid
                                                    // 真进群了：让父面板刷新成员（原逻辑会顺手关掉本面板）
                                                    msg.contains("已加入") -> {
                                                        memberIds = memberIds + uid
                                                        onMemberAdded()
                                                    }
                                                    // 邀请 / 待审核：人没进群，但这一行已经用过了，别再让人重复点
                                                    else -> pendingIds = pendingIds + uid
                                                }
                                            } else {
                                                Toast.makeText(ctx, "失败: ${r.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                }
            }
        }
    }
    }
}

// ==================== 移除成员面板 ====================

@Composable
private fun RemoveMemberPanel(
    groupId: Long,
    onBack: () -> Unit,
    onMemberRemoved: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var blockUser by remember { mutableStateOf(false) }
    // 开发者（ID=1）在后端拥有绝对权限，可以移除群主；普通群主/管理员仍然不能移除群主
    val isDeveloper = com.aurora.chat.data.api.AuroraApi.currentUserId == 1L

    LaunchedEffect(groupId) {
        val r = ChatRepository.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until r.data!!.length()) {
                val m = r.data!!.getJSONObject(i)
                val role = m.optString("role", "")
                if (role != "owner" || isDeveloper) list.add(m)
            }
            members = list
        }
    }
    val filtered = remember(keyword, members) {
        if (keyword.isBlank()) members
        else members.filter { m ->
            m.optString("username", "").contains(keyword, ignoreCase = true) ||
            m.optLong("user_id").toString().contains(keyword)
        }
    }

    // 用 zIndex 抬到列表之上：LiquidDialog 液态模式是主窗口内的覆盖层，声明在内容前会被列表盖住
    Box(Modifier.zIndex(2f)) {
    if (showConfirmDialog && confirmTarget != null) {
        val (targetId, targetName) = confirmTarget!!
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("移除成员", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("确定要移除「$targetName」吗？")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = blockUser,
                            onCheckedChange = { blockUser = it },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Color(0xFFDC2626))
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("拉黑该用户", fontSize = 14.sp, color = Color(0xFF4B5563),
                            modifier = Modifier.clickable { blockUser = !blockUser })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                        showConfirmDialog = false
                        scope.launch {
                            val r = ChatRepository.removeGroupMember(groupId, targetId, blockUser)
                            if (r.success) {
                                Toast.makeText(ctx, "已移除 $targetName", Toast.LENGTH_SHORT).show()
                                onMemberRemoved()
                            } else {
                                Toast.makeText(ctx, "失败: ${r.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("确定", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
    }

    Box(Modifier.fillMaxSize()) {
        // 事件拦截层：白底 + 消费空白区域点击，防止事件穿透到背景的设置面板
        EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1E40AF), modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Text("移除成员", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg())
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(value = keyword, onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (keyword.isEmpty()) Text("搜索成员...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                })
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(filtered, key = { it.optLong("user_id") }) { m ->
                val uid = m.optLong("user_id")
                val rn = m.optString("username", "")
                val un = if (rn.isNotBlank()) rn else "用户$uid"
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(userId = uid, userName = un, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(un, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.weight(1f))
                    Text("移除", fontSize = 13.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp)).background(Color(0xFFFEE2E2))
                            .clickable { confirmTarget = uid to un; blockUser = false; showConfirmDialog = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            }
        }
    }
    }
}

// ==================== 分享群聊弹窗 ====================

@Composable
internal fun ShareGroupDialog(
    groupId: Long,
    groupName: String,
    groupSignature: String,
    qrBitmap: Bitmap? = null,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var conversations by remember { mutableStateOf<List<ConversationInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val result = ChatRepository.getConversations()
        if (result.success && result.data != null) {
            conversations = result.data!!
        }
        isLoading = false
    }

    val filteredList = remember(conversations, searchText, selectedTab) {
        conversations.distinctBy { it.id }.filter { conv ->
            val matchTab = if (selectedTab == 0) conv.id > 0 else conv.id < 0
            val matchSearch = searchText.isBlank() ||
                    conv.username.contains(searchText, ignoreCase = true) ||
                    conv.lastMessage.contains(searchText, ignoreCase = true)
            matchTab && matchSearch
        }
    }

    fun sendShare(conv: ConversationInfo) {
        val isOfficialGroup = conv.id < 0 && conv.username.contains("官方", ignoreCase = true)
        val loginPrefs = ctx.getSharedPreferences("aurora_login", Context.MODE_PRIVATE)
        val userQQ = loginPrefs.getString("user_qq", "") ?: ""
        val userEmail = loginPrefs.getString("user_email", "") ?: ""
        val isDeveloper = com.aurora.chat.data.local.LocalStorage.isDeveloper(userQQ, userEmail)
        if (isOfficialGroup && !isDeveloper) {
            Toast.makeText(ctx, "官方群禁止分享群聊", Toast.LENGTH_SHORT).show()
            return
        }

        // 二维码图片分享：上传 PNG → 以图片消息发出（群聊/单聊通用）
        if (qrBitmap != null) {
            scope.launch {
                try {
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    val up = com.aurora.chat.data.api.AuroraApi.uploadChatMedia(bytes, "group_qr_$groupId.png", "image/png")
                    if (!up.success || up.data == null) {
                        Toast.makeText(ctx, "分享失败: ${up.message}", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val mediaUrl = resolveMediaUrl(up.data!!.optString("url", ""))
                    val result = if (conv.id < 0) {
                        ChatRepository.sendGroupMessage(conv.id, "群二维码", 0, "image", mediaUrl, 0)
                    } else {
                        ChatRepository.sendMessage(conv.id, "群二维码", 0, "image", mediaUrl, 0)
                    }
                    if (result.success) {
                        Toast.makeText(ctx, "已分享给 ${conv.username}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "分享失败: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val msg = buildString {
            append("分享群聊\n")
            append("━━━━━━━━━━\n")
            append(groupName)
            if (groupSignature.isNotBlank()) {
                append("\n").append(groupSignature.take(80))
            }
            append("\n")
            append("━━━━━━━━━━\n")
            append("来自 ${com.aurora.chat.data.api.AuroraApi.currentUserName}\n")
            append("group_id=$groupId")
        }

        scope.launch {
            // 群消息：后端要求 to_user_id 为负数会话ID（isGroupChat 约定，服务端内部再 -(id+1000) 还原群 id）。
            // 此前这里先算成 `val gid = -(conv.id + 1000)` 再传，导致后端按"私聊 to_user_id=gid"处理，
            // 表现为"提示发送成功、群里却找不到这条卡片"。必须直接传负数会话ID。
            val result = if (conv.id < 0) {
                ChatRepository.sendGroupMessage(conv.id, msg)
            } else {
                ChatRepository.sendMessage(conv.id, msg)
            }
            if (result.success) {
                Toast.makeText(ctx, "已分享给 ${conv.username}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "分享失败: ${result.message}", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { onDismiss() })
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.65f)
                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(top = 16.dp)
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(40.dp).height(4.dp).background(Color(0xFFD1D5DB), CircleShape))
            }
            Text("分享群聊到", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(42.dp)
                .clip(RoundedCornerShape(10.dp)).background(com.aurora.chat.ui.theme.appCardBg()).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart) {
                BasicTextField(value = searchText, onValueChange = { searchText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                    cursorBrush = SolidColor(Color(0xFF1E40AF)),
                    decorationBox = { inner ->
                        if (searchText.isEmpty()) Text("搜索好友或群聊", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        inner()
                    }, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("好友" to 0, "群聊" to 1).forEach { (label, index) ->
                    val isSelected = selectedTab == index
                    Box(Modifier.clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                        .clickable { selectedTab = index }
                        .padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Color.White else com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            }
            Text("${filteredList.size} 个${if (selectedTab == 0) "好友" else "群聊"}",
                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else if (filteredList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searchText.isNotEmpty()) "未找到匹配的${if (selectedTab == 0) "好友" else "群聊"}" else "暂无${if (selectedTab == 0) "好友" else "群聊"}",
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filteredList, key = { it.id }) { conv ->
                        val internalGroupId = if (conv.id < 0) -(conv.id + 1000) else null
                        val isGroup = conv.id < 0
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                if (isGroup && internalGroupId != null) {
                                    GroupAvatar(internalGroupId = internalGroupId, groupName = conv.username, size = 44.dp)
                                } else {
                                    UserAvatar(userId = conv.id, userName = conv.username, size = 44.dp)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(conv.username, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isGroup) Text("群聊", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFEEF2FF))
                                .clickable { sendShare(conv) }.padding(horizontal = 14.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center) {
                                Text("分享", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                    }
                }
            }
        }
    }
}

// ==================== 禁言成员面板（底部滑出） ====================

private val MUTE_DURATION_OPTIONS = listOf(
    "5分钟" to 300L, "30分钟" to 1800L, "1小时" to 3600L,
    "12小时" to 43200L, "1天" to 86400L, "7天" to 604800L,
    "自定义" to -1L
)

@Composable
internal fun MuteMemberPanel(
    groupId: Long,
    currentUserId: Long,
    isAdmin: Boolean,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    // 从聊天页「长按成员气泡 → 禁言」进入时，把被长按的那个人的昵称预填进搜索框：
    // 打开就能直接看到目标（点一下「禁言」即可选时长），清空搜索框即回到完整成员列表。
    // 群设置入口不传，保持原来的空搜索框。
    initialKeyword: String = ""
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var keyword by remember(initialKeyword) { mutableStateOf(initialKeyword) }
    var muteStatuses by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var loadingMuteStatus by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(0) }

    LaunchedEffect(groupId, refreshing) {
        val r = ChatRepository.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val list = mutableListOf<JSONObject>()
            for (i in 0 until r.data!!.length()) {
                val m = r.data!!.getJSONObject(i)
                if (m.optString("role", "") != "owner") list.add(m)
            }
            members = list
        }
        loadingMuteStatus = true
        // 批量查询禁言状态，一次API调用替代逐个查询
        val statuses = mutableMapOf<Long, Long>()
        try {
            val result = ChatRepository.groupMuteMembers(groupId)
            if (result.success && result.data != null) {
                val arr = result.data!!.optJSONArray("data")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val uid = item.optLong("user_id", 0)
                        val expiresAt = item.optLong("expires_at", 0)
                        if (uid > 0 && expiresAt > System.currentTimeMillis() / 1000) {
                            statuses[uid] = expiresAt
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        muteStatuses = statuses
        loadingMuteStatus = false
    }

    val filtered = remember(keyword, members) {
        if (keyword.isBlank()) members
        else members.filter { m ->
            m.optString("username", "").contains(keyword, ignoreCase = true) ||
            m.optLong("user_id").toString().contains(keyword)
        }
    }

    var showDurationPicker by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var showCustomDurationDialog by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var customDurationInput by remember { mutableStateOf("") }
    var customDurationUnit by remember { mutableStateOf("小时") }

    // 用 zIndex 抬到列表之上：LiquidDialog 液态模式是主窗口内的覆盖层，声明在内容前会被列表盖住
    Box(Modifier.zIndex(2f)) {
    // 自定义禁言时长弹窗
    if (showCustomDurationDialog != null) {
        val (targetId, targetName) = showCustomDurationDialog!!
        AlertDialog(
            onDismissRequest = { showCustomDurationDialog = null },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("自定义禁言「$targetName」", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("请输入禁言时长（最多365天）：", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = customDurationInput,
                            onValueChange = { v ->
                                val filtered = v.filter { it.isDigit() }
                                customDurationInput = filtered
                            },
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(com.aurora.chat.ui.theme.appCardBg())
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box { if (customDurationInput.isEmpty()) Text("输入时长", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        // 单位选择
                        val units = listOf("分钟", "小时", "天")
                        units.forEach { unit ->
                            val selected = customDurationUnit == unit
                            Text(
                                unit,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextPrimary(),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                                    .clickable { customDurationUnit = unit }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                        val input = customDurationInput.toLongOrNull()
                        if (input == null || input <= 0) {
                            Toast.makeText(ctx, "请输入有效时长", Toast.LENGTH_SHORT).show()
                        } else {
                            val seconds = when (customDurationUnit) {
                                "天" -> input * 86400L
                                "小时" -> input * 3600L
                                else -> input * 60L
                            }
                            val maxSeconds = 365 * 86400L // 1年
                            if (seconds > maxSeconds) {
                                Toast.makeText(ctx, "禁言时长不能超过365天", Toast.LENGTH_SHORT).show()
                            } else {
                                showCustomDurationDialog = null
                                scope.launch {
                                    try {
                                        val result = ChatRepository.groupMuteUser(groupId, targetId, seconds)
                                        if (result.success) {
                                            // 静默生效：操作者自己不需要被弹窗告知（群内提示由服务端按群通知方式发出）
                                            refreshing++
                                        } else {
                                            Toast.makeText(ctx, "禁言失败: ${result.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "禁言失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDurationDialog = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }

    if (showDurationPicker != null) {
        val (targetId, targetName) = showDurationPicker!!
        AlertDialog(
            onDismissRequest = { showDurationPicker = null },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("禁言「$targetName」", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("选择禁言时长：", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(12.dp))
                    MUTE_DURATION_OPTIONS.forEach { (label, seconds) ->
                        Text(label, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (seconds == -1L) {
                                        // 自定义
                                        customDurationInput = ""
                                        customDurationUnit = "小时"
                                        showDurationPicker = null
                                        showCustomDurationDialog = targetId to targetName
                                    } else {
                                        showDurationPicker = null
                                        scope.launch {
                                            try {
                                                val result = ChatRepository.groupMuteUser(groupId, targetId, seconds)
                                                if (result.success) {
                                                    // 静默生效，不弹 Toast
                                                    refreshing++
                                                } else {
                                                    Toast.makeText(ctx, "禁言失败: ${result.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(ctx, "禁言失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }.padding(vertical = 12.dp))
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDurationPicker = null }) { Text("关闭", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
    }

    // 全屏页（与「移除成员」同一套版式）：标题栏 + 搜索 + 成员列表，不再是底部抽屉
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding().groupPageTouchGuard()) {
        GroupPageHeader(title = "禁言成员", onBack = onDismiss)
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg())
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(value = keyword, onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (keyword.isEmpty()) Text("搜索成员...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                })
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

        if (loadingMuteStatus) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = Color(0xFF1E40AF), strokeWidth = 3.dp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                items(filtered, key = { it.optLong("user_id") }) { m ->
                    val uid = m.optLong("user_id")
                    val rn = m.optString("username", "")
                    val un = if (rn.isNotBlank()) rn else "用户$uid"
                    val isMuted = muteStatuses.containsKey(uid)
                    val muteExpires = muteStatuses[uid] ?: 0L
                    val muteRemainingText = if (muteExpires > 0) {
                        val remainSec = (muteExpires - System.currentTimeMillis() / 1000).coerceAtLeast(0)
                        val hours = remainSec / 3600
                        val mins = (remainSec % 3600) / 60
                        when {
                            hours > 0 -> "${hours}小时${mins}分钟"
                            mins > 0 -> "${mins}分钟"
                            else -> "${remainSec}秒"
                        }
                    } else ""

                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        com.aurora.chat.ui.components.UserAvatar(userId = uid, userName = un, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(un, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                            if (isMuted && muteRemainingText.isNotEmpty()) {
                                Text("已禁言 · 剩余$muteRemainingText", fontSize = 11.sp, color = Color(0xFFB45309))
                            }
                        }
                        if (isMuted) {
                            Text("解禁", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = Color(0xFF16A34A),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFFDCFCE7))
                                    .clickable {
                                        scope.launch {
                                            try {
                                                val result = ChatRepository.groupUnmuteUser(groupId, uid)
                                                if (result.success) {
                                                    // 静默生效，不弹 Toast
                                                    refreshing++
                                                } else {
                                                    Toast.makeText(ctx, "解禁失败: ${result.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(ctx, "解禁失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }.padding(horizontal = 12.dp, vertical = 6.dp))
                        } else {
                            Text("禁言", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = Color(0xFFDC2626),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFFFEE2E2))
                                    .clickable { showDurationPicker = uid to un }
                                    .padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                }
            }
        }
    }
}

// ==================== 群资料子页面（路口入口） ====================

// 群公告增强数据结构：文本 + 图片（最多5张）+ 发布时间
// confirmedByMe / confirmCount 为服务端只读展示字段，保存时不下发
data class GroupAnnouncementData(
    val text: String,
    val images: List<String> = emptyList(),
    val popupEnabled: Boolean = false,
    val needConfirm: Boolean = false,
    val confirmedByMe: Boolean = false,
    val confirmCount: Int = 0,
    // 发布日期（epoch 毫秒）：发布时写入，编辑时原样保留；0 表示旧数据缺省
    val createdAt: Long = 0
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("text", text)
        if (images.isNotEmpty()) put("images", org.json.JSONArray(images))
        put("popup_enabled", popupEnabled)
        put("need_confirm", needConfirm)
        if (createdAt > 0) put("created_at", createdAt)
    }
}

// 公告列表 → 后端 JSON 数组（仅保存可写字段，不含确认状态）
private fun buildAnnouncementsJson(list: List<GroupAnnouncementData>): org.json.JSONArray {
    val arr = org.json.JSONArray()
    list.forEach { arr.put(it.toJson()) }
    return arr
}

// 兼容解析后端公告：优先 announcements JSON 数组（对象含 text/images/popup_enabled/need_confirm/confirmed_by_me/confirm_count），
// 兼容旧字符串数组与旧单条字符串；无新字段时按默认值处理
internal fun parseGroupAnnouncementsJson(data: org.json.JSONObject): List<GroupAnnouncementData> {
    val arr = data.optJSONArray("announcements")
    if (arr != null) {
        val list = mutableListOf<GroupAnnouncementData>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            if (item is org.json.JSONObject) {
                val images = mutableListOf<String>()
                val imgs = item.optJSONArray("images")
                if (imgs != null) {
                    for (j in 0 until imgs.length()) {
                        val u = imgs.optString(j, "").trim()
                        if (u.isNotEmpty()) images.add(u)
                    }
                }
                val text = item.optString("text", "").trim()
                if (text.isEmpty() && images.isEmpty()) continue
                list.add(
                    GroupAnnouncementData(
                        text = text,
                        images = images,
                        popupEnabled = item.optBoolean("popup_enabled", false),
                        needConfirm = item.optBoolean("need_confirm", false),
                        confirmedByMe = item.optBoolean("confirmed_by_me", false),
                        confirmCount = item.optInt("confirm_count", 0),
                        createdAt = item.optLong("created_at", 0L)
                    )
                )
            } else {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) list.add(GroupAnnouncementData(text = s))
            }
        }
        return list
    }
    val legacy = data.optString("announcement", "").trim()
    return if (legacy.isNotEmpty()) listOf(GroupAnnouncementData(text = legacy)) else emptyList()
}

// ── 通用单行编辑页：群名称 / 群签名 / 群备注 ──
@Composable
private fun GroupSimpleEditScreen(
    title: String,
    label: String,
    initial: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    maxLength: Int = Int.MAX_VALUE
) {
    var text by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize()) {
        EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(28.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = text,
                        // 群名称必须封顶：没上限时群详情页会被超长群名铺满整屏
                        onValueChange = { if (it.length <= maxLength) text = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                        singleLine = true,
                        cursorBrush = SolidColor(Color(0xFF1E40AF)),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text("未设置", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                inner()
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(46.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E40AF))
                .clickable { onSave(text.trim()) },
                contentAlignment = Alignment.Center) {
                Text("完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 群备注编辑页（任何人可改，本地保存）──
@Composable
private fun GroupRemarkEditScreen(
    initial: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    GroupSimpleEditScreen(
        title = "修改群备注",
        label = "群备注",
        initial = initial,
        onBack = onBack,
        onSave = onSave
    )
}

// ── 群欢迎语编辑页：开关 + 输入框（关闭仅隐藏不丢内容）──
@Composable
private fun GroupWelcomeEditScreen(
    initialEnabled: Boolean,
    initialText: String,
    onBack: () -> Unit,
    onSave: (Boolean, String) -> Unit
) {
    var enabled by remember { mutableStateOf(initialEnabled) }
    var text by remember { mutableStateOf(initialText) }
    Box(Modifier.fillMaxSize()) {
        EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text("修改群欢迎语", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(28.dp))
            }
            Spacer(Modifier.height(8.dp))
            // ── 板块一：群欢迎语开关（独立卡片） ──
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("群欢迎语开关", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("开启群欢迎语", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.weight(1f))
                    Switch(checked = enabled,
                        onCheckedChange = { enabled = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
                }
            }
            Spacer(Modifier.height(12.dp))
            // ── 板块二：群欢迎语输入框（独立卡片，关闭仅隐藏不清空） ──
            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("群欢迎语输入框", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 21.sp),
                            singleLine = false, minLines = 2, maxLines = 6,
                            cursorBrush = SolidColor(Color(0xFF1E40AF)),
                            decorationBox = { inner ->
                                Box {
                                    if (text.isEmpty()) Text("新成员入群时自动发送的欢迎语", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    inner()
                                }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(46.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E40AF))
                .clickable { onSave(enabled, text.trim()) },
                contentAlignment = Alignment.Center) {
                Text("完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 群公告多级页面：列表 → 单项全屏 → 新增/编辑 ──
@Composable
private fun GroupAnnouncementsFlow(
    initialAnnouncements: List<GroupAnnouncementData>,
    canEdit: Boolean,
    groupId: Long,
    onBack: () -> Unit,
    onSaved: (List<GroupAnnouncementData>) -> Unit,
    onPostAnnouncement: ((GroupAnnouncementData) -> Unit)? = null,
    onConfirm: ((GroupAnnouncementData) -> Unit)? = null
) {
    var announcements by remember { mutableStateOf(initialAnnouncements) }
    // 父级确认/保存后会把新列表回传，这里同步一次，保证详情页的「已确认收到」立即刷新
    LaunchedEffect(initialAnnouncements) { announcements = initialAnnouncements }
    var page by remember { mutableStateOf(0) } // 0=列表 1=详情 2=编辑
    var currentIndex by remember { mutableIntStateOf(-1) }
    var editMode by remember { mutableIntStateOf(-1) } // -1=新增 >=0=编辑索引

    when (page) {
        0 -> AnnouncementsListPage(
            announcements = announcements,
            canEdit = canEdit,
            onBack = onBack,
            onAdd = {
                editMode = -1
                page = 2
            },
            onItemClick = { idx ->
                currentIndex = idx
                page = 1
            }
        )
        1 -> AnnouncementDetailPage(
            announcement = announcements.getOrNull(currentIndex),
            canEdit = canEdit,
            canConfirm = !canEdit,
            groupId = groupId,
            onBack = { page = 0 },
            onEdit = {
                editMode = currentIndex
                page = 2
            },
            onDelete = {
                val newList = announcements.toMutableList().apply { if (currentIndex in indices) removeAt(currentIndex) }
                announcements = newList
                onSaved(newList)
                page = 0
            },
            onConfirm = { ann -> onConfirm?.invoke(ann) }
        )
        else -> AnnouncementEditPage(
            title = if (editMode >= 0) "编辑公告" else "新增公告",
            initial = if (editMode >= 0) announcements.getOrNull(editMode) ?: GroupAnnouncementData("") else GroupAnnouncementData(""),
            onBack = { page = if (editMode >= 0) 1 else 0 },
            onSave = { ann ->
                // 发布日期：新增时落当前时间，编辑时沿用原发布日期（不能因为改内容就变成今天）
                val stamped = if (ann.createdAt > 0L) ann else ann.copy(createdAt = System.currentTimeMillis())
                val newList = if (editMode >= 0) {
                    announcements.toMutableList().apply {
                        if (editMode in indices) this[editMode] = stamped else add(stamped)
                    }
                } else {
                    mutableListOf(stamped).apply { addAll(announcements) }
                }
                announcements = newList
                onSaved(newList)
                // 新增公告：以卡片形式同步发布到群聊对话内，长按撤回后服务端联动移除本条公告
                if (editMode < 0) onPostAnnouncement?.invoke(stamped)
                page = if (editMode >= 0) 1 else 0
                if (editMode >= 0) currentIndex = editMode
            }
        )
    }
}

// ── 公告发布日期文案：优先用服务端 created_at；旧数据缺失时用本机首次可见时间兜底 ──
@Composable
private fun announcementDateText(ann: GroupAnnouncementData): String {
    val ctx = LocalContext.current
    val fallback = remember(ann.text) {
        if (ann.createdAt > 0L) 0L
        else runCatching { com.aurora.chat.data.local.DataCache.getOrInitAnnouncementDate(ctx, ann.text) }.getOrDefault(0L)
    }
    val ts = if (ann.createdAt > 0L) ann.createdAt else fallback
    return remember(ts) { formatAnnouncementDate(ts) }
}

// ── 公告列表页：顶部「群公告」+ 右侧铅笔新增（仅管理员可见） ──
@Composable
private fun AnnouncementsListPage(
    announcements: List<GroupAnnouncementData>,
    canEdit: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onItemClick: (Int) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text("群公告", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                if (canEdit) {
                    // 仿 QQ：正方形圆角底 + 铅笔戳入
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFE8EEFF))
                        .clickable { onAdd() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Edit, contentDescription = "新增公告", tint = Color(0xFF1E40AF),
                            modifier = Modifier.size(15.dp))
                    }
                } else {
                    Spacer(Modifier.width(28.dp))
                }
            }
            if (announcements.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无公告", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(announcements) { index, ann ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                            .clickable { onItemClick(index) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)) {
                            if (ann.text.isNotBlank()) {
                                Text(ann.text, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                                    maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                            }
                            if (ann.images.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                LazyRow {
                                    items(ann.images) { url ->
                                        coil.compose.AsyncImage(
                                            model = resolveMediaUrl(url),
                                            contentDescription = "公告图片",
                                            modifier = Modifier.size(52.dp).padding(end = 6.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                            if (ann.text.isBlank() && ann.images.isEmpty()) {
                                Text("（无内容）", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                            // 发布日期固定贴在白卡最底部
                            Spacer(Modifier.height(10.dp))
                            Text(
                                announcementDateText(ann),
                                fontSize = 11.sp,
                                color = com.aurora.chat.ui.theme.appTextHint()
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

// 公告状态小徽标（弹窗展示 / 需确认收到）；onClick 非空时可点击（管理员查看确认名单）
@Composable
private fun AnnouncementFlagChip(text: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFE8EEFF))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 11.sp, color = Color(0xFF1E40AF))
    }
}

// ── 单项公告全屏页：全文 + 图片 + 开关状态；管理员可编辑/删除，成员可确认收到 ──
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementDetailPage(
    announcement: GroupAnnouncementData?,
    canEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onConfirm: (GroupAnnouncementData) -> Unit,
    // 群主/管理员不需要「确认收到」：canConfirm=false 时只展示确认人数
    canConfirm: Boolean = true,
    // 从聊天卡片进入时消息里没有 popup_enabled，隐藏「弹窗展示/需确认」徽标，避免展示错误状态
    showFlags: Boolean = true,
    // 所在群 id（内部群 id，正数）：管理员点击「已确认 N 人」时拉取确认名单
    groupId: Long = 0L
) {
    val ann = announcement ?: return
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showConfirmSheet by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text("群公告", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                if (canEdit) {
                    Box {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = com.aurora.chat.ui.theme.appTextPrimary(),
                            modifier = Modifier.size(28.dp).clickable { menuExpanded = true })
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            // 不用 M3 默认的 surfaceContainer（带紫调的灰），弹层统一纯白
                            containerColor = com.aurora.chat.ui.theme.appSurface()
                        ) {
                            DropdownMenuItem(text = { Text("删除公告", fontSize = 14.sp, color = Color(0xFFDC2626)) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteConfirm = true
                                })
                        }
                    }
                } else {
                    Spacer(Modifier.width(28.dp))
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                    .padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text(if (ann.text.isBlank()) "（无内容）" else ann.text,
                        fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 23.sp)
                    if (ann.images.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Column {
                            ann.images.forEach { url ->
                                coil.compose.AsyncImage(
                                    model = resolveMediaUrl(url),
                                    contentDescription = "公告图片",
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    if (showFlags && (ann.popupEnabled || ann.needConfirm)) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (ann.popupEnabled) AnnouncementFlagChip("弹窗展示")
                            if (ann.needConfirm) {
                                AnnouncementFlagChip(
                                    text = if (ann.confirmCount > 0) "已确认 ${ann.confirmCount} 人" else "需确认收到",
                                    onClick = if (canEdit) ({ showConfirmSheet = true }) else null
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // 发布日期固定贴在白卡最底部
                    Text(
                        announcementDateText(ann),
                        fontSize = 11.sp,
                        color = com.aurora.chat.ui.theme.appTextHint()
                    )
                }
            }
            if (canEdit) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(46.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E40AF))
                    .clickable { onEdit() },
                    contentAlignment = Alignment.Center) {
                    Text("编辑公告", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                if (ann.needConfirm) {
                    Text("已有 ${ann.confirmCount} 人确认收到", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            .clickable { showConfirmSheet = true }, textAlign = TextAlign.Center)
                }
            } else if (canConfirm && ann.needConfirm) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (ann.confirmedByMe) Color(0xFFE8EEFF) else Color(0xFF1E40AF))
                    .clickable(enabled = !ann.confirmedByMe) { onConfirm(ann) },
                    contentAlignment = Alignment.Center) {
                    Text(
                        if (ann.confirmedByMe) "已确认收到" else "确认收到",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (ann.confirmedByMe) Color(0xFF1E40AF) else Color.White
                    )
                }
                Text("已有 ${ann.confirmCount} 人确认收到", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
        }
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                // 白底，不要 M3 默认的带紫调灰 surface
                containerColor = com.aurora.chat.ui.theme.appSurface(),
                title = { Text("删除公告", fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                text = { Text("删除后不可恢复，确定删除这条公告吗？", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) },
                confirmButton = {
                    // 用 TextButton：自带内边距，和「取消」之间不会挤成一条
                    TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                        Text("删除", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            )
        }
        // 管理员查看「确认收到」名单：底部抽屉（与「N人已读」抽屉一致的交互）
        if (showConfirmSheet && groupId > 0) {
            ModalBottomSheet(
                onDismissRequest = { showConfirmSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = com.aurora.chat.ui.theme.appSurface()
            ) {
                AnnouncementConfirmSheet(groupId = groupId, text = ann.text)
            }
        }
    }
}

// ── 公告「确认收到」名单抽屉：仅群主/管理员拉取（后端二次校验权限）──
@Composable
internal fun AnnouncementConfirmSheet(groupId: Long, text: String) {
    var loading by remember(text) { mutableStateOf(true) }
    var failed by remember(text) { mutableStateOf(false) }
    var users by remember(text) {
        mutableStateOf<List<Pair<Pair<Long, String>, String>>>(emptyList())
    }
    LaunchedEffect(groupId, text) {
        val r = runCatching { AuroraApi.getAnnouncementConfirms(groupId, text) }.getOrNull()
        if (r?.success == true && r.data != null) {
            val arr = r.data!!
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            users = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val at = o.optLong("confirmed_at", 0L)
                    add(
                        (o.optLong("user_id", 0L) to o.optString("nickname", "用户").ifBlank { "用户" }) to
                            if (at > 0) fmt.format(java.util.Date(at * 1000)) else ""
                    )
                }
            }
        } else {
            failed = true
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text("${users.size}人已收到", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.height(10.dp))
        when {
            loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp), color = com.aurora.chat.ui.theme.appTextHint())
            }
            failed -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("加载失败，请稍后重试", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
            users.isEmpty() -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("还没有人确认", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
            else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(users, key = { it.first.first }) { item ->
                    val (user, atText) = item
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            userId = user.first,
                            userName = user.second,
                            size = 30.dp,
                            modifier = Modifier.size(30.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            user.second, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text(atText, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                }
            }
        }
    }
}

// ── 公告新增/编辑页：内容 + 选图（≤5张）+ 两个独立开关板块 ──
@Composable
private fun AnnouncementEditPage(
    title: String,
    initial: GroupAnnouncementData,
    onBack: () -> Unit,
    onSave: (GroupAnnouncementData) -> Unit
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial.text) }
    var images by remember { mutableStateOf(initial.images) }
    // 两个独立开关：弹窗展示（成员进群聊时自动弹出）/ 需成员确认收到
    var popupEnabled by remember { mutableStateOf(initial.popupEnabled) }
    var needConfirm by remember { mutableStateOf(initial.needConfirm) }
    var uploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf(0) }
    var uploadTotal by remember { mutableStateOf(0) }
    // 选图后立即上传：直接在 picker 回调里用 rememberCoroutineScope 启动协程。
    // ⚠️ 不能用 LaunchedEffect(pendingAddUris) —— 在 effect 内把 pendingAddUris 置空会改变 key，
    //    触发 effect 自我取消，uploading 永远是 true，界面就卡死在「上传中 0/1」。
    val scope = rememberCoroutineScope()
    fun uploadPicked(picked: List<Uri>) {
        val room = 5 - images.size
        if (room <= 0) {
            Toast.makeText(ctx, "最多添加 5 张图片", Toast.LENGTH_SHORT).show()
            return
        }
        val pending = picked.take(room)
        if (pending.isEmpty()) return
        uploading = true
        uploadProgress = 0
        uploadTotal = pending.size
        scope.launch {
            val added = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        pending.chunked(3).forEach { batch ->
                            val urls = batch.map { uri ->
                                async {
                                    val bytes = readAnnouncementImageBytes(ctx, uri) ?: return@async null
                                    val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                                    // 相册 picker 返回的 Uri.lastPathSegment 通常是数字、不带扩展名，
                                    // 直接用会被后端 validateUploadExt 当成「无扩展名文件」拒掉（400）。
                                    // 这里按 MIME 补一个白名单扩展名；后端仍以 magic bytes 判定真实类型，不影响实际存储格式。
                                    val ext = when {
                                        mime.contains("png", ignoreCase = true) -> ".png"
                                        mime.contains("gif", ignoreCase = true) -> ".gif"
                                        mime.contains("webp", ignoreCase = true) -> ".webp"
                                        mime.contains("bmp", ignoreCase = true) -> ".bmp"
                                        else -> ".jpg"
                                    }
                                    val name = "announcement_${System.currentTimeMillis()}_${(0..9999).random()}$ext"
                                    runCatching { AuroraApi.uploadChatMedia(bytes, name, mime) }
                                        .getOrNull()
                                        ?.takeIf { it.success && it.data != null }
                                        ?.data?.optString("url", "")?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                }
                            }.awaitAll()
                            withContext(Dispatchers.Main) {
                                added.addAll(urls.filterNotNull())
                                uploadProgress = added.size
                            }
                        }
                    }
                }
            } finally {
                // 无论成功/失败/取消，都必须复位，避免卡在「上传中」
                uploading = false
                if (added.isNotEmpty()) images = (images + added).take(5)
                if (added.size < pending.size) {
                    Toast.makeText(ctx, "部分图片上传失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Android Photo Picker：最多选 5 张
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        if (uris.isNotEmpty()) uploadPicked(uris)
    }

    Box(Modifier.fillMaxSize()) {
        EventBlocker(backgroundColor = com.aurora.chat.ui.theme.appCardBg())
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(28.dp))
            }
            Spacer(Modifier.height(8.dp))

            // ── 公告内容输入板块 ──
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("公告内容", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 23.sp),
                        singleLine = false, minLines = 5, maxLines = 10,
                        cursorBrush = SolidColor(Color(0xFF1E40AF)),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text("输入公告内容...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                inner()
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            // ── 选择图片板块（最多 5 张，可删除） ──
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("选择图片（最多 5 张）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(8.dp))
                LazyRow {
                    itemsIndexed(images) { idx, url ->
                        Box {
                            coil.compose.AsyncImage(
                                model = resolveMediaUrl(url),
                                contentDescription = "已选图片",
                                modifier = Modifier.size(76.dp).padding(end = 8.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Box(Modifier.size(20.dp).align(Alignment.TopEnd).padding(end = 12.dp, top = 0.dp)
                                .clip(CircleShape).background(Color(0xCC000000)),
                                contentAlignment = Alignment.Center) {
                                Text("×", color = Color.White, fontSize = 13.sp,
                                    modifier = Modifier.clickable { images = images.toMutableList().apply { removeAt(idx) } })
                            }
                        }
                    }
                    if (images.size < 5) {
                        item {
                            Box(Modifier.size(76.dp).clip(RoundedCornerShape(10.dp))
                                .background(com.aurora.chat.ui.theme.appCardBg())
                                .clickable(enabled = !uploading) {
                                    if (uploading) Toast.makeText(ctx, "图片上传中，请稍候", Toast.LENGTH_SHORT).show()
                                    else imagePicker.launch(androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                    ))
                                },
                                contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("+", fontSize = 22.sp, color = if (uploading) Color(0xFFD1D5DB) else Color(0xFF1E40AF))
                                    Spacer(Modifier.height(2.dp))
                                    Text(if (uploading) "上传中 $uploadProgress/$uploadTotal" else "添加图片", fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        }
                    }
                }
                if (images.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("未选择图片", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── 展示方式：弹窗展示 / 需成员确认收到（两个独立开关）──
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("以弹窗形式展示", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        Spacer(Modifier.height(3.dp))
                        Text("成员进入群聊时自动弹出这条公告", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                    Switch(
                        checked = popupEnabled,
                        onCheckedChange = { popupEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF1E40AF)
                        )
                    )
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).padding(horizontal = 16.dp).background(com.aurora.chat.ui.theme.appCardBg()))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("需成员确认收到", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        Spacer(Modifier.height(3.dp))
                        Text("成员需点击「确认收到」，可查看确认人数", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                    Switch(
                        checked = needConfirm,
                        onCheckedChange = { needConfirm = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF1E40AF)
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (uploading) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF1E40AF))
                .clickable(enabled = !uploading) {
                    if (uploading) {
                        Toast.makeText(ctx, "图片上传中，请稍候", Toast.LENGTH_SHORT).show()
                    } else {
                        onSave(
                            GroupAnnouncementData(
                                text = text.trim(),
                                images = images,
                                popupEnabled = popupEnabled,
                                needConfirm = needConfirm,
                                createdAt = initial.createdAt
                            )
                        )
                    }
                },
                contentAlignment = Alignment.Center) {
                Text(if (uploading) "上传中 ${uploadProgress}/$uploadTotal" else "完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// 读取并压缩本地图片 Uri（复用聊天图片同款压缩策略，控制体积防 OOM）
private fun readAnnouncementImageBytes(ctx: Context, uri: Uri): ByteArray? {
    return try {
        ctx.contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }?.let { fileBytes ->
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size, opts)
            val isPng = opts.outMimeType?.contains("png", ignoreCase = true) == true
            val maxDimension = 1600
            val sampleSize = maxOf(
                (opts.outWidth + maxDimension - 1) / maxDimension,
                (opts.outHeight + maxDimension - 1) / maxDimension,
                1
            )
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                // PNG 保留透明通道；其余走 RGB_565 省内存
                inPreferredConfig = if (isPng) android.graphics.Bitmap.Config.ARGB_8888
                else android.graphics.Bitmap.Config.RGB_565
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(fileBytes, 0, fileBytes.size, decodeOpts)
            if (bitmap != null && (fileBytes.size > 400 * 1024 || sampleSize > 1)) {
                val out = java.io.ByteArrayOutputStream()
                // 与原始格式保持一致，避免「扩展名/实际内容类型不符」导致上传后无法显示
                if (isPng) bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                else bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            } else fileBytes
        }
    } catch (_: Exception) { null }
}
