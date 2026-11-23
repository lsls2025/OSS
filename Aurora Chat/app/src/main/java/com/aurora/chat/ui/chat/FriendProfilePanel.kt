package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.local.DataCache
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.theme.AuroraPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults

@Composable
fun FriendProfilePanel(
    friendId: Long,
    friendName: String,
    currentUserId: Long,
    onBack: () -> Unit,
    onDeleteFriend: () -> Unit,
    onOpenHistorySearch: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 免打扰开关变化时，更新本地推送配置里的免打扰好友列表，并同步到后端
    fun updatePushDndList(enabled: Boolean) {
        val dndIds = DataCache.loadPushDndIds(context, currentUserId).toMutableList()
        if (enabled) {
            if (!dndIds.contains(friendId)) dndIds.add(friendId)
        } else {
            dndIds.remove(friendId)
        }
        DataCache.savePushConfig(
            context, currentUserId,
            DataCache.loadPushMethod(context, currentUserId),
            DataCache.loadPushTemplateMode(context, currentUserId),
            DataCache.loadOwnEmail(context, currentUserId),
            DataCache.loadOwnAuth(context, currentUserId),
            dndIds,
            DataCache.loadNotifyEnabled(context, currentUserId)
        )
        scope.launch {
            try {
                AuroraApi.savePushConfig(
                    DataCache.loadPushMethod(context, currentUserId),
                    DataCache.loadPushTemplateMode(context, currentUserId),
                    DataCache.loadOwnEmail(context, currentUserId),
                    DataCache.loadOwnAuth(context, currentUserId),
                    dndIds,
                    DataCache.loadNotifyEnabled(context, currentUserId)
                )
            } catch (_: Exception) {
                // 网络异常仅本地保留
            }
        }
    }

    var friendInfo by remember { mutableStateOf<UserInfo?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    // 二次确认弹窗：先选范围，再确认具体会发生什么
    var showClearConfirm by remember { mutableStateOf(false) }
    // 0=未选择 1=本地清理 2=云端清理
    var pendingClearScope by remember { mutableStateOf(0) }
    var doNotDisturb by remember { mutableStateOf(false) }
    var isPinned by remember { mutableStateOf(false) }
    var friendSince by remember { mutableStateOf(0L) }
    var remark by remember { mutableStateOf("") }
    var showRemarkDialog by remember { mutableStateOf(false) }
    var remarkInput by remember { mutableStateOf("") }
    // 分享用户卡片
    var showShareMenu by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var currentUserName by remember { mutableStateOf("") }
    // 黑名单状态（true=我与该用户之间存在黑名单关系）
    var isBlocked by remember { mutableStateOf(false) }
    // 聊天背景图片：已有背景时点击菜单先弹「更换/移除」，否则直接选图
    var showBgActionDialog by remember { mutableStateOf(false) }
    val bgPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val u = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // 解码+EXIF 校正+保存都放 IO；完成后 toast，对话页靠 revision 自动换背景
            var failAt = ""
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bmp = ChatBackgroundStorage.decodeUri(context, u)
                    when {
                        bmp == null -> { failAt = "图片解码失败"; false }
                        !ChatBackgroundStorage.save(context, friendId, bmp) -> { failAt = "保存失败（未登录？）"; false }
                        else -> true
                    }
                } catch (e: Exception) {
                    failAt = e.javaClass.simpleName
                    false
                }
            }
            android.widget.Toast.makeText(
                context,
                if (ok) "聊天背景已更新" else "背景图片设置失败：$failAt",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── 液态模式：开关用液态玻璃Toggle；经典模式保持 Material3 Switch ──
    // 白底页面上给玻璃手柄一个纯色折射源（页面本身仍是白底，只让控件内部有玻璃感）

    LaunchedEffect(friendId) {
        // 首帧先显示「看过的」资料缓存（纯内存、零等待），网络结果随后覆盖；
        // 断网时也至少有名字和签名，而不是一片空白。
        friendInfo = ChatRepository.getCachedUserInfo(friendId)
        val info = ChatRepository.getUserInfo(friendId)
        if (info.success && info.data != null) {
            friendInfo = info.data
        }
        // 加载黑名单关系（用于「三个点」菜单文案：拉入 / 拉出黑名单）
        runCatching {
            val blk = ChatRepository.isBlocked(friendId)
            if (blk.success) isBlocked = blk.data?.isBlocked == true
        }
        // 加载免打扰/置顶状态
        val prefs = context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
        doNotDisturb = prefs.getBoolean("dnd_$friendId", false)
        isPinned = prefs.getBoolean("pin_$friendId", false)
        friendSince = prefs.getLong("friend_since_$friendId", 0L)
        // 加载备注
        remark = prefs.getString("remark_$friendId", "") ?: ""
        // 如果没有缓存加为好友时间，则从好友列表获取
        if (friendSince == 0L) {
            val friendsResult = ChatRepository.getFriends()
            if (friendsResult.success && friendsResult.data != null) {
                val f = friendsResult.data!!.find { it.id == friendId }
                if (f != null) friendSince = f.createdAt
            }
        }
    }

    // ── 在线状态 / 上次活动（与用户资料页同一套口径：<60 秒视为在线） ──
    val lastActiveDiff = if (friendInfo != null && friendInfo!!.lastActiveAt > 0)
        ((System.currentTimeMillis() / 1000) - friendInfo!!.lastActiveAt).coerceAtLeast(0) else -1L
    val isOnline = lastActiveDiff in 0..59
    val lastActiveText = when {
        lastActiveDiff < 0 -> ""
        lastActiveDiff < 3600 -> "刚刚"
        lastActiveDiff < 86400 -> "${lastActiveDiff / 3600} 小时前"
        else -> "${lastActiveDiff / 86400} 天前"
    }

    // 当前用户名（分享用户卡片时的"来自"）
    LaunchedEffect(currentUserId) {
        if (currentUserId > 0) {
            currentUserName = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ChatRepository.getUserInfo(currentUserId).data?.username ?: "" }.getOrDefault("")
            }
        }
    }

    // 与群聊信息 / 用户资料同一套视觉：浅灰底 + 白卡 + 行式列表
    Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg()).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 固定标题栏（不随内容滚动，实色底防穿透）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AuroraPrimary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "好友资料",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                // 三个点：分享 / 背景图片 / 黑名单
                if (friendId != currentUserId) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "⋯", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AuroraPrimary,
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { showShareMenu = true }
                                .padding(4.dp)
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = showShareMenu,
                            onDismissRequest = { showShareMenu = false },
                            modifier = Modifier.background(com.aurora.chat.ui.theme.appSurface())
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("分享用户", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                                onClick = {
                                    showShareMenu = false
                                    showShare = true
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("背景图片", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                                onClick = {
                                    showShareMenu = false
                                    // 已设过背景 → 先给「更换/移除」；否则直接选图
                                    if (ChatBackgroundStorage.has(context, friendId)) {
                                        showBgActionDialog = true
                                    } else {
                                        bgPickerLauncher.launch("image/*")
                                    }
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (isBlocked) "拉出黑名单" else "拉入黑名单", fontSize = 14.sp, color = Color(0xFFDC2626)) },
                                onClick = {
                                    showShareMenu = false
                                    scope.launch {
                                        val res = if (isBlocked) {
                                            ChatRepository.unblockUser(friendId)
                                        } else {
                                            ChatRepository.blockUser(friendId)
                                        }
                                        if (res.success) {
                                            isBlocked = !isBlocked
                                            // 拉黑会解除好友关系，刷新本地好友缓存让联系人列表同步
                                            if (!isBlocked) {
                                                com.aurora.chat.ui.contacts.invalidateFriendCache(context, currentUserId)
                                            }
                                            android.widget.Toast.makeText(
                                                context,
                                                if (isBlocked) "已拉入黑名单" else "已移出黑名单",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            android.widget.Toast.makeText(context, res.message, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.size(28.dp))
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp)
            ) {
            item { Spacer(Modifier.height(16.dp)) }

            // ── 头部信息区（灰底直放，不卡片化）：头像 + 名字(备注优先) + ID/在线状态 ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    com.aurora.chat.ui.components.UserAvatar(
                        userId = friendId,
                        userName = friendInfo?.username ?: friendName,
                        size = 56.dp
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val displayName = remark.ifEmpty { friendInfo?.username?.takeIf { it.isNotBlank() } ?: friendName }
                            Text(
                                text = displayName,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = com.aurora.chat.ui.theme.appTextPrimary(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isOnline) {
                                Spacer(Modifier.width(8.dp))
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Color(0xFFECFDF5))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color(0xFF10B981)))
                                    Spacer(Modifier.width(4.dp))
                                    Text("在线", fontSize = 11.sp, color = Color(0xFF059669))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("ID：$friendId", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                }
            }

            // ── 个性签名（单独整行） ──
            item {
                val signature = friendInfo?.signature?.takeIf { it.isNotBlank() }
                if (signature != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        signature,
                        fontSize = 13.sp,
                        color = com.aurora.chat.ui.theme.appTextSecondary(),
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(18.dp)) }

            // ── 资料卡（白卡）：备注 / QQ 号 / 加入时间 / 已成为好友 / 上次活动 ──
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    // 备注（点击修改）
                    ProfileRow(
                        label = "备注",
                        value = remark.ifEmpty { "未设置" },
                        valueMuted = remark.isEmpty(),
                        onClick = {
                            remarkInput = remark
                            showRemarkDialog = true
                        }
                    )
                    if (remark.isNotEmpty()) {
                        RowDivider()
                        // 有备注时补一行原始昵称，避免备注盖掉真名后找不到人
                        ProfileRow(
                            label = "昵称",
                            value = friendInfo?.username?.takeIf { it.isNotBlank() } ?: friendName,
                            valueMuted = false,
                            onClick = null
                        )
                    }
                    RowDivider()
                    // QQ 号（对方开启「不显示 QQ 号」或当前是陌生人时，服务端已返回打码串）
                    val qqText = when {
                        friendInfo == null -> "正在加载…"
                        friendInfo!!.qqNumber.isBlank() -> "未设置"
                        friendInfo!!.hideQQ == 1 -> com.aurora.chat.util.MaskUtil.qq(friendInfo!!.qqNumber)
                        else -> friendInfo!!.qqNumber
                    }
                    // 打码串（含 *）说明服务端已按隐私策略遮蔽，不能再当成「可用的号码」弱化展示
                    val qqMasked = friendInfo != null && (friendInfo!!.hideQQ == 1 || friendInfo!!.qqNumber.contains('*'))
                    ProfileRow(label = "QQ 号", value = qqText, valueMuted = friendInfo == null || qqMasked, onClick = null)
                    if (friendInfo != null && friendInfo!!.createdAt > 0) {
                        RowDivider()
                        val ts = friendInfo!!.createdAt
                        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        val days = (System.currentTimeMillis() / 1000 - ts) / 86400
                        ProfileRow(
                            label = "加入时间",
                            value = "${fmt.format(java.util.Date(ts * 1000))} · $days 天",
                            valueMuted = false,
                            onClick = null
                        )
                    }
                    if (friendSince > 0) {
                        RowDivider()
                        val days = (System.currentTimeMillis() / 1000 - friendSince) / 86400
                        ProfileRow(label = "已成为好友", value = "$days 天", valueMuted = false, onClick = null)
                    }
                    if (lastActiveText.isNotBlank() && !isOnline) {
                        RowDivider()
                        ProfileRow(label = "上次活动", value = lastActiveText, valueMuted = false, onClick = null)
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }

            // ── 会话设置卡（白卡）：免打扰 / 置顶 ──
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(horizontal = 16.dp)
                ) {
                    // 免打扰
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "免打扰",
                            fontSize = 14.sp,
                            color = com.aurora.chat.ui.theme.appTextPrimary(),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                                checked = doNotDisturb,
                                onCheckedChange = {
                                    doNotDisturb = it
                                    context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                                        .edit().putBoolean("dnd_$friendId", it).apply()
                                    updatePushDndList(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1E40AF),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFD1D5DB)
                                )
                            )
                    }

                    RowDivider()

                    // 置顶（开启后该会话将排列在聊天列表顶部）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "置顶",
                            fontSize = 14.sp,
                            color = com.aurora.chat.ui.theme.appTextPrimary(),
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                                checked = isPinned,
                                onCheckedChange = { newVal ->
                                    isPinned = newVal
                                    context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                                        .edit().putBoolean("pin_$friendId", newVal).apply()
                                    // 通知聊天列表刷新
                                    com.aurora.chat.ui.viewmodel.ChatViewModel.notifyPinChanged()
                                    // 同步到服务端：重装/换机后置顶还在
                                    com.aurora.chat.data.local.ClientPrefsSync.schedulePush(context, immediate = true)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF1E40AF),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFD1D5DB)
                                )
                            )
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }

            // ── 查找聊天记录（独立白卡，位于「清空聊天记录」上方）──
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
                        Text("查找聊天记录", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
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

            // ── 操作卡（白卡）：只放「清空聊天记录」；删除好友弱化为卡外文字行 ──
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                        .padding(12.dp)
                ) {
                    Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(com.aurora.chat.ui.theme.appSelectedBg())
                                .clickable { showClearChatDialog = true }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("清空聊天记录", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AuroraPrimary) }
                }
                // 危险操作弱化：无底色无描边的小字行
                Text(
                    "删除好友", fontSize = 13.sp, color = Color(0xFFDC2626),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        .clickable { showDeleteDialog = true }
                        .padding(vertical = 12.dp)
                )
            }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteDialog) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showDeleteDialog = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(com.aurora.chat.ui.theme.appSurface()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(16.dp))
                    Text("删除好友", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Spacer(Modifier.height(12.dp))
                    Text("确定要删除好友「${friendName}」吗？\n删除后需重新发送好友申请。",
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Text("取消", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp)).clickable { showDeleteDialog = false }
                                .padding(horizontal = 24.dp, vertical = 10.dp))
                        Text("确认删除", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEE2E2))
                                .clickable {
                                    showDeleteDialog = false
                                    onDeleteFriend()
                                }
                                .padding(horizontal = 24.dp, vertical = 10.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

    // 清空聊天记录 · 第一步：只选范围（自绘弹窗，与本面板"删除好友"同一套样式，不用 M3 AlertDialog）
    if (showClearChatDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showClearChatDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(com.aurora.chat.ui.theme.appSurface()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))
                Text("清空聊天记录", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ClearScopePill("本地清理") {
                        showClearChatDialog = false
                        pendingClearScope = 1
                        showClearConfirm = true
                    }
                    ClearScopePill("云端清理") {
                        showClearChatDialog = false
                        pendingClearScope = 2
                        showClearConfirm = true
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("取消", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp)).clickable { showClearChatDialog = false }
                        .padding(horizontal = 24.dp, vertical = 10.dp))
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // 清空聊天记录 · 第二步：二次确认，这里才说清楚各自会发生什么
    if (showClearConfirm) {
        val isLocalScope = pendingClearScope == 1
        androidx.compose.ui.window.Dialog(onDismissRequest = { showClearConfirm = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(com.aurora.chat.ui.theme.appSurface()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (isLocalScope) "本地清理" else "云端清理",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = com.aurora.chat.ui.theme.appTextPrimary()
                )
                Spacer(Modifier.height(12.dp))
                val isSelfChat = friendId == currentUserId
                Text(
                    when {
                        isLocalScope -> "将清空本机上与「$friendName」的聊天记录，服务器上的数据保持不变。"
                        isSelfChat -> "将清空服务器上你与自己的聊天记录，本机记录随后同步清除。"
                        else -> "将向对方发送清空聊天记录请求，对方同意后服务器上的记录会被清空，本机记录保持不变。"
                    },
                    fontSize = 14.sp,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("取消", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp)).clickable { showClearConfirm = false }
                            .padding(horizontal = 24.dp, vertical = 10.dp))
                    Text(
                        if (isLocalScope || isSelfChat) "确定清理" else "发送请求",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = AuroraPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(com.aurora.chat.ui.theme.appSelectedBg())
                            .clickable {
                                showClearConfirm = false
                                scope.launch {
                                    if (isLocalScope) {
                                        // 本地清理：只删本机的记录（消息 + 红叹号失败气泡 + 本地广播）
                                        clearConversationLocalOnly(context, currentUserId, friendId)
                                    } else if (isSelfChat) {
                                        // 自聊（自己与自己的会话）没有"对方"，无法走「发请求-对方同意」流程。
                                        // 直接清理自己云端记录：后端已放开 /api/messages/clear-chat 的自聊拦截，
                                        // 删除 from==to==自己 的会话消息并向自己推送 conversation_deleted 清本地。
                                        val r = runCatching { AuroraApi.agreeClearChat(friendId) }.getOrNull()
                                        if (r == null || !r.success) {
                                            android.widget.Toast.makeText(
                                                context,
                                                r?.message ?: "清理失败",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    } else {
                                        // 云端清理：向对方发清空请求卡片，对方同意后服务端双向删除。
                                        // 卡片本身客户端没有乐观气泡 —— 服务端落库后会给我推一条
                                        // message_persisted 回执，我这边立刻增量对齐，卡片秒级出现。
                                        // 清空动作是双向的：对方同意后两端都会清本地（对称，不保留任何一边）。
                                        val r = runCatching { AuroraApi.sendClearChatRequest(friendId) }.getOrNull()
                                        if (r == null || !r.success) {
                                            android.widget.Toast.makeText(
                                                context,
                                                r?.message ?: "发送失败",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // 备注修改弹窗
    if (showRemarkDialog) {
        AlertDialog(
            onDismissRequest = { showRemarkDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("给「$friendName」备注", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("当前备注：", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (remark.isNotEmpty()) remark else "（未设置）",
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(12.dp))
                    BasicTextField(
                        value = remarkInput,
                        onValueChange = { remarkInput = it },
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box { if (remarkInput.isEmpty()) Text("输入备注名称", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                        }
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val newRemark = remarkInput.trim()
                    remark = newRemark
                    context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
                        .edit().putString("remark_$friendId", newRemark).apply()
                    showRemarkDialog = false
                }) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showRemarkDialog = false }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }

    // 聊天背景操作（仅已设置过背景时出现）：更换图片 / 移除背景
    if (showBgActionDialog) {
        AlertDialog(
            onDismissRequest = { showBgActionDialog = false },
            title = { Text("聊天背景", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
            text = { Text("当前已设置背景图片", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) },
            confirmButton = {
                TextButton(onClick = {
                        showBgActionDialog = false
                        bgPickerLauncher.launch("image/*")
                    }) { Text("更换图片", color = AuroraPrimary) }
            },
            dismissButton = {
                TextButton(onClick = {
                        showBgActionDialog = false
                        ChatBackgroundStorage.clear(context, friendId)
                        android.widget.Toast.makeText(context, "已恢复默认背景", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("移除背景", color = Color(0xFFDC2626)) }
            }
        )
    }

    // 分享用户卡片给好友：复用社区通用分享面板，卡片协议为"分享用户 / user_id"。
    // 内嵌渲染（不用 Dialog）：避免 Dialog 系统 dim 与面板自带遮罩叠加造成顶部色差，
    // 也避免 Dialog onDismissRequest 被返回手势/窗口外触摸触发导致"点着点着就没了"。
    AnimatedVisibility(
        visible = showShare,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        com.aurora.chat.ui.community.SharePostDialog(
            postTitle = friendInfo?.username?.takeIf { it.isNotBlank() } ?: friendName,
            postContent = friendInfo?.signature ?: "",
            postId = friendId,
            postUserId = friendId,
            postUsername = currentUserName,
            postType = "post",
            titlePrefix = "分享用户",
            idKey = "user_id",
            onDismiss = { showShare = false }
        )
    }
}

/**
 * 清空范围选项：药丸按钮（浅蓝底 + 品牌色字），与本面板其它主按钮同一套样式。
 * 只显示名称，说明文案一律放到第二步的二次确认弹窗里。
 */
@Composable
private fun ClearScopePill(
    title: String,
    onClick: () -> Unit
) {
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = AuroraPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(com.aurora.chat.ui.theme.appSelectedBg())
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp)
    )
}

/** 资料行：左侧灰色 label，右侧取值（onClick 非空时整行可点并带右箭头） */
@Composable
private fun ProfileRow(
    label: String,
    value: String,
    valueMuted: Boolean,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.width(12.dp))
        Text(
            value, fontSize = 14.sp,
            color = if (valueMuted) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextPrimary(),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
        if (onClick != null) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 白卡内的 0.5dp 分割线 */
@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appCardBg()))
}
