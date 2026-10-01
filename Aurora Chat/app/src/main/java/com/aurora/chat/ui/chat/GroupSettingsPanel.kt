package com.aurora.chat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.local.AvatarCache
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.data.api.ConversationInfo
import com.aurora.chat.ui.components.EventBlocker
import com.aurora.chat.ui.components.GroupAvatar
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun GroupSettingsPanel(
    internalGroupId: Long,
    groupName: String,
    onBack: () -> Unit,
    onOpenProfile: (Long, String) -> Unit = { _, _ -> },
    onDissolved: () -> Unit = {},
    onOpenJoinRequestList: (org.json.JSONArray, Long) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groupInfo by remember { mutableStateOf<JSONObject?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var editName by remember { mutableStateOf("") }
    var editSignature by remember { mutableStateOf("") }
    var editAnnouncement by remember { mutableStateOf("") }
    var editWelcomeEnabled by remember { mutableStateOf(false) }
    var editWelcomeText by remember { mutableStateOf("") }
    var editNotSearchable by remember { mutableStateOf(false) }
    var editJoinRequired by remember { mutableStateOf(false) }

    // 群成员列表（提升到顶层，便于"加入/移除"回调刷新）
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var displayCount by remember { mutableIntStateOf(50) }
    // 批量预加载的成员头像（uid -> Bitmap），网格直接读此 Map，避免逐格 LaunchedEffect 在主线程解码导致滚动卡顿
    var avatarMap by remember { mutableStateOf<Map<Long, Bitmap?>>(emptyMap()) }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hasChanges by remember { mutableStateOf(false) }

    // 菜单状态
    var showMenu by remember { mutableStateOf(false) }
    var showDissolveDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var showAdminPanel by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    var showRemoveMember by remember { mutableStateOf(false) }
    var showShareGroup by remember { mutableStateOf(false) }
    var showMuteMember by remember { mutableStateOf(false) }

    val handleBack: () -> Unit = {
        scope.launch {
            if (hasChanges) {
                val r = ChatRepository.updateGroupSettings(
                    groupId = internalGroupId, name = editName,
                    signature = editSignature, announcement = editAnnouncement,
                    welcomeEnabled = editWelcomeEnabled, welcomeText = editWelcomeText,
                    notSearchable = editNotSearchable, joinRequired = editJoinRequired
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

    LaunchedEffect(internalGroupId) {
        val result = AuroraApi.getGroupInfo(internalGroupId)
        if (result.success && result.data != null) {
            val data = result.data!!
            groupInfo = data
            editName = data.optString("name", groupName)
            editSignature = data.optString("signature", "")
            editAnnouncement = data.optString("announcement", "")
            editWelcomeEnabled = data.optBoolean("welcome_enabled", false)
            editWelcomeText = data.optString("welcome_text", "")
            editNotSearchable = data.optBoolean("not_searchable", false)
            editJoinRequired = data.optBoolean("join_required", false)
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
    val pendingJoinCount = groupInfo?.optInt("pending_join_count", 0) ?: 0

    val nonOwnerClick: () -> Unit = {
        if (!isOwner) Toast.makeText(context, "该群仅群主可修改", Toast.LENGTH_SHORT).show()
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (!isOwner) return@rememberLauncherForActivityResult
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

    // ── 解散群聊确认弹窗 ──
    if (showDissolveDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDissolveDialog = false },
            title = { Text("解散群聊", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定要解散此群聊吗？\n所有成员将被移除，群聊将永久删除。") },
            confirmButton = {
                Text("确认解散", color = Color(0xFFDC2626),
                    modifier = Modifier.clickable {
                        showDissolveDialog = false
                        scope.launch {
                            val r = ChatRepository.dissolveGroup(internalGroupId)
                            if (r.success) {
                                Toast.makeText(context, "群聊已解散", Toast.LENGTH_SHORT).show()
                                onDissolved()
                            } else {
                                Toast.makeText(context, "解散失败: ${r.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.padding(12.dp))
            },
            dismissButton = {
                Text("取消", modifier = Modifier.clickable { showDissolveDialog = false }.padding(12.dp))
            },
            containerColor = Color.White
        )
    }

    // ── 转让群主弹窗 ──
    if (showTransferDialog) {
        TransferOwnerDialog(
            groupId = internalGroupId,
            onDismiss = { showTransferDialog = false },
            onTransferred = { showTransferDialog = false; onBack() }
        )
    }

    // ── 主界面 ──
    Box(Modifier.fillMaxSize()) {
        val lazyListState = rememberLazyListState()
        // ── 加载群成员（在 LazyColumn 外，确保可执行）──
        LaunchedEffect(internalGroupId) {
            val r = ChatRepository.getGroupMembers(internalGroupId)
            if (r.success && r.data != null) {
                val list = mutableListOf<JSONObject>()
                for (i in 0 until r.data!!.length()) list.add(r.data!!.getJSONObject(i))
                members = list
            }
        }
        // 预加载可见成员头像：每个头像独立 launch 一个 IO 任务并行加载（Dispatchers.IO 自身限流 64 并发，保护连接）。
        // 每加载完一个立即更新 avatarMap，保留"加载出一个显示一个"的效果，同时去掉串行互相等待的阻塞。
        LaunchedEffect(members, displayCount) {
            val want = members.take(displayCount)
            val toLoad = want.filter { !avatarMap.containsKey(it.optLong("user_id")) }
            if (toLoad.isEmpty()) return@LaunchedEffect
            for (m in toLoad) {
                val uid = m.optLong("user_id")
                launch(Dispatchers.IO) {
                    val bmp = ChatRepository.loadAvatar(context, uid)
                    avatarMap = avatarMap + (uid to bmp)
                }
            }
        }
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()
        ) {
        // 顶部栏
        item {
        var backClicked by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
            Text("群设置", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
            // 三个点菜单（所有成员可见，普通成员仅"分享群聊"）
            Text("⋮", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1E40AF),
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { showMenu = true }.padding(4.dp))
            if (showMenu) {
                val menuItems = mutableListOf<Pair<String, () -> Unit>>()
                menuItems.add("分享群聊" to { showShareGroup = true })
                if (isOwner) {
                    menuItems.add("解散群聊" to { showDissolveDialog = true })
                    menuItems.add("转让群主" to { showTransferDialog = true })
                    menuItems.add("设置管理" to { showAdminPanel = true })
                    menuItems.add("禁言成员" to { showMuteMember = true })
                }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showMenu = false },
                    title = { Text("群管理", fontWeight = FontWeight.SemiBold) },
                    text = {
                        Column {
                            menuItems.forEachIndexed { index, (item, action) ->
                                Text(item, fontSize = 15.sp, color = Color(0xFF1F2937),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showMenu = false
                                            action()
                                        }
                                        .padding(vertical = 14.dp, horizontal = 4.dp))
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("取消", fontSize = 15.sp, color = Color(0xFF6B7280),
                                modifier = Modifier.fillMaxWidth().clickable { showMenu = false }
                                    .padding(vertical = 14.dp, horizontal = 4.dp),
                                textAlign = TextAlign.Center)
                        }
                    },
                    confirmButton = {},
                    dismissButton = {},
                    containerColor = Color.White
                )
            }
        }
        }

        item { Spacer(Modifier.height(20.dp)) }

        // 群头像
        item {
        val avatarClickable = if (isOwner) Modifier.clickable { imagePicker.launch("image/*") }
            else Modifier.clickable { nonOwnerClick() }
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(80.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xFFE5E7EB)).then(avatarClickable),
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
        }
        }

        item { Spacer(Modifier.height(10.dp)) }

        // 群ID
        item {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
        Text("群ID: $displayId", fontSize = 13.sp, color = Color(0xFF9CA3AF),
            modifier = Modifier.fillMaxWidth().clickable {
                clipboard.setPrimaryClip(ClipData.newPlainText("群ID", displayId.toString()))
                Toast.makeText(context, "已复制群ID: $displayId", Toast.LENGTH_SHORT).show()
            }, textAlign = TextAlign.Center)
        }

        item { Spacer(Modifier.height(12.dp)) }

        // 群名称
        item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("群名称", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
            Spacer(Modifier.height(4.dp))
            SettingsTextField("", editName) { editName = it; hasChanges = true }
        }
        }

        item { Spacer(Modifier.height(20.dp)) }

        item {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            SettingsTextField("群签名", editSignature) { editSignature = it; hasChanges = true }
            Spacer(Modifier.height(12.dp))
            Text("群公告", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF9FAFB)).padding(12.dp)) {
                BasicTextField(value = editAnnouncement,
                    onValueChange = { editAnnouncement = it; hasChanges = true },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                    singleLine = false, minLines = 3, maxLines = 6,
                    decorationBox = { inner ->
                        Box { if (editAnnouncement.isEmpty()) Text("无公告", fontSize = 14.sp, color = Color(0xFF9CA3AF)); inner() }
                    })
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("群欢迎语", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(Modifier.weight(1f))
                Switch(checked = editWelcomeEnabled,
                    onCheckedChange = { editWelcomeEnabled = it; hasChanges = true },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
            }
            if (editWelcomeEnabled) {
                Spacer(Modifier.height(4.dp))
                SettingsTextField("", editWelcomeText) { editWelcomeText = it; hasChanges = true }
            }
            Spacer(Modifier.height(16.dp))
            // ── 无法被搜索 / 入群需申请（同一排）──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("无法被搜索", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(Modifier.width(4.dp))
                Switch(checked = editNotSearchable,
                    onCheckedChange = { if (isAdmin) { editNotSearchable = it; hasChanges = true } else { Toast.makeText(context, "仅群主或管理员可修改", Toast.LENGTH_SHORT).show() } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
                Spacer(Modifier.width(16.dp))
                Text("入群需申请", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(Modifier.width(4.dp))
                Switch(checked = editJoinRequired,
                    onCheckedChange = { if (isAdmin) { editJoinRequired = it; hasChanges = true } else { Toast.makeText(context, "仅群主或管理员可修改", Toast.LENGTH_SHORT).show() } },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
            }
            Spacer(Modifier.height(16.dp))
            // ── 置顶（独立一行，开关在右侧）──
            var isPinned by remember { mutableStateOf(false) }
            val pinPrefs = context.getSharedPreferences("aurora_friend_settings", Context.MODE_PRIVATE)
            LaunchedEffect(Unit) { isPinned = pinPrefs.getBoolean("pin_group_$internalGroupId", false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("置顶", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
                Spacer(Modifier.width(4.dp))
                Switch(checked = isPinned,
                    onCheckedChange = { newVal: Boolean ->
                        isPinned = newVal
                        pinPrefs.edit().putBoolean("pin_group_$internalGroupId", newVal).apply()
                        com.aurora.chat.ui.viewmodel.ChatViewModel.notifyPinChanged()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF1E40AF),
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFFD1D5DB)))
            }
            Spacer(Modifier.height(12.dp))
        }
        }

        // ── 加载群成员 ──
        // （LaunchedEffect 已移至 LazyColumn 外）

        // ── 群成员标题 ──
        item {
            Text("群成员", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280),
                modifier = Modifier.padding(horizontal = 24.dp))
        }
        item { Spacer(Modifier.height(8.dp)) }

        // ── 群成员列表（懒加载）──
        // 注意：整张网格只用一个 items 块，行内间距直接并入 item，避免两套 items 用相同 key 撞车导致滚动频繁重组（卡顿根因）
        val showMembers = members.take(displayCount)
        val chunked = showMembers.chunked(5)
        items(chunked.size, key = { "mbr_row_$it" }) { rowIndex ->
            val row = chunked[rowIndex]
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
            ) {
                for (m in row) {
                    val uid = m.optLong("user_id")
                    val rawName = m.optString("username", "")
                    val uname = if (rawName.isNotBlank()) rawName else "用户$uid"
                    Column(Modifier.width(60.dp).clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onOpenProfile(uid, uname) },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val bmp = avatarMap[uid]
                        if (bmp != null) {
                            Image(bitmap = bmp.asImageBitmap(), contentDescription = uname,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop)
                        } else {
                            Image(painter = painterResource(com.aurora.chat.R.drawable.ic_profile),
                                contentDescription = uname,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(uname, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = Color(0xFF1F2937), textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 显示更多按钮
        item {
            if (displayCount < members.size) {
                val remaining = members.size - displayCount
                Text("显示更多（剩余 $remaining 人）", fontSize = 13.sp, color = Color(0xFF1E40AF),
                    modifier = Modifier.fillMaxWidth().clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) {
                        displayCount = if (displayCount == 50) 100 else members.size
                    }.padding(vertical = 8.dp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── "+" 和 "-" 操作按钮（群主+管理员可见）──
        // 直接左对齐紧凑排列，不再用空格撑位，避免右侧出现多余空白格
        if (isAdmin) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
                ) {
                    Column(Modifier.width(60.dp).clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { showAddMember = true },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1E40AF)),
                            contentAlignment = Alignment.Center) {
                            Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("添加", fontSize = 11.sp, color = Color(0xFF1E40AF), textAlign = TextAlign.Center)
                    }
                    Column(Modifier.width(60.dp).clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { showRemoveMember = true },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFDC2626)),
                            contentAlignment = Alignment.Center) {
                            Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text("移除", fontSize = 11.sp, color = Color(0xFFDC2626), textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ── 退出群聊 ──
        item {
            var showLeaveDialog by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            Text("退出群聊", fontSize = 14.sp, color = Color(0xFFDC2626),
                modifier = Modifier.fillMaxWidth().clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { showLeaveDialog = true }.padding(vertical = 14.dp),
                textAlign = TextAlign.Center)
            if (showLeaveDialog) {
                val isLastMember = members.size <= 1
                val leaveHint = when {
                    isOwner && isLastMember -> "该群无其他成员，退出后将解散群聊"
                    isOwner -> "你是该群主，退出后将随机将群主权限分发至管理员，都未设置则随机分发群成员"
                    userRole == "admin" -> "你是该群管理员，退出后将取消该权限，下次进入并不会保留权限"
                    else -> "确定要退出此群聊吗？"
                }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showLeaveDialog = false }, containerColor = Color.White,
                    title = { Text("退出群聊", fontWeight = FontWeight.Bold) },
                    text = { Text(leaveHint, fontSize = 14.sp, lineHeight = 22.sp) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            showLeaveDialog = false
                            scope.launch {
                                try {
                                    ChatRepository.removeGroupMember(internalGroupId, currentUserId, false)
                                    Toast.makeText(context, "已退出群聊", Toast.LENGTH_SHORT).show()
                                    onDissolved()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "退出失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text("确认退出", color = Color(0xFFDC2626)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showLeaveDialog = false }) { Text("取消", color = Color(0xFF6B7280)) }
                    }
                )
            }
        }
        }
    }

    // ── 设置管理面板（放在主界面后，确保在之上绘制）──
    if (showAdminPanel) {
        AdminManagementPanel(
            groupId = internalGroupId,
            onBack = { showAdminPanel = false },
            onOpenJoinRequestList = onOpenJoinRequestList
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

    // ── 分享群聊弹窗 ──
    if (showShareGroup) {
        ShareGroupDialog(
            groupId = internalGroupId,
            groupName = editName.ifEmpty { groupName },
            groupSignature = editSignature,
            onDismiss = { showShareGroup = false }
        )
    }

    // ── 禁言成员面板（底部滑出） ──
    if (showMuteMember) {
        MuteMemberPanel(
            groupId = internalGroupId,
            currentUserId = currentUserId,
            isAdmin = isAdmin,
            isOwner = isOwner,
            onDismiss = { showMuteMember = false }
        )
    }
}

// ── 转让群主弹窗 ──
@Composable
private fun TransferOwnerDialog(groupId: Long, onDismiss: () -> Unit, onTransferred: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedId by remember { mutableStateOf(0L) }
    var selectedName by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(groupId) {
        val r = ChatRepository.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val list = mutableListOf<JSONObject>()
            for (i in 0 until r.data!!.length()) {
                val m = r.data!!.getJSONObject(i)
                val role = m.optString("role", "")
                if (role != "owner") list.add(m)
            }
            members = list
            loaded = true
            android.util.Log.d("TransferDialog", "加载成员: ${list.size}人")
        } else {
            loaded = true
            android.util.Log.w("TransferDialog", "加载失败: ${r.message}")
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转让群主", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("选择新群主：", fontSize = 14.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(8.dp))
                if (!loaded) {
                    Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                } else if (members.isEmpty()) {
                    Text("群内无其他成员可转让", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                } else {
                    members.forEach { m ->
                        val uid = m.optLong("user_id")
                        val rn = m.optString("username", "")
                        val un = if (rn.isNotBlank()) rn else "用户$uid"
                        val isSel = uid == selectedId
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedId = uid; selectedName = un }
                                .background(if (isSel) Color(0xFFEFF6FF) else Color.Transparent)
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(userId = uid, userName = un, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(un, fontSize = 14.sp, color = Color(0xFF1F2937))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text("转让",
                modifier = Modifier.clickable(enabled = selectedId > 0) {
                    scope.launch {
                        val r = ChatRepository.transferOwner(groupId, selectedId)
                        if (r.success) {
                            Toast.makeText(ctx, "已转让给 $selectedName", Toast.LENGTH_SHORT).show()
                            onTransferred()
                        } else {
                            Toast.makeText(ctx, "转让失败: ${r.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.padding(12.dp),
                color = if (selectedId > 0) Color(0xFF1E40AF) else Color(0xFF9CA3AF))
        },
        dismissButton = { Text("取消", modifier = Modifier.clickable { onDismiss() }.padding(12.dp)) },
        containerColor = Color.White
    )
}

// ── 设置管理面板 ──
@Composable
private fun AdminManagementPanel(groupId: Long, onBack: () -> Unit, onOpenJoinRequestList: (org.json.JSONArray, Long) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var filteredMembers by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    LaunchedEffect(groupId) {
        val r = ChatRepository.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val list = mutableListOf<JSONObject>()
            for (i in 0 until r.data!!.length()) list.add(r.data!!.getJSONObject(i))
            members = list
            filteredMembers = list
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            filteredMembers = members
        } else {
            filteredMembers = members.filter { m ->
                val uid = m.optLong("user_id").toString()
                val un = m.optString("username", "")
                uid.contains(searchQuery, ignoreCase = true) ||
                un.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // 设置管理员确认弹窗
    var confirmTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    if (confirmTarget != null) {
        val (targetId, targetName) = confirmTarget!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("设置管理员", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定将「$targetName」设为管理员吗？") },
            confirmButton = {
                Text("确认", color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable {
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
                    }.padding(12.dp))
            },
            dismissButton = { Text("取消", modifier = Modifier.clickable { confirmTarget = null }.padding(12.dp)) },
            containerColor = Color.White
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color.White).statusBarsPadding()
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
            Text("设置管理", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937),
                modifier = Modifier.weight(1f))
            Text("入群申请", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFFEFF6FF))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        scope.launch {
                            try {
                                val r = AuroraApi.getJoinRequests(groupId)
                                if (r.success && r.data != null) {
                                    onOpenJoinRequestList(r.data, groupId)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }

        // 搜索框
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF3F4F6)).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (searchQuery.isEmpty()) Text("搜索成员...", fontSize = 14.sp, color = Color(0xFF9CA3AF)); inner() }
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
                        Text(un, fontSize = 14.sp, color = Color(0xFF1F2937), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (email.isNotBlank()) email else "ID: $uid · $roleLabel",
                            fontSize = 12.sp, color = Color(0xFF9CA3AF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (role == "owner") {
                        Text("群主", fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(end = 8.dp))
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
                                .clip(RoundedCornerShape(4.dp)).background(Color(0xFFEFF6FF))
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            }
        }
    }
}

@Composable
private fun SettingsTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
            Spacer(Modifier.height(4.dp))
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF9FAFB))
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (value.isEmpty() && label.isNotEmpty()) Text("无", fontSize = 14.sp, color = Color(0xFF9CA3AF)); inner() }
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
        EventBlocker(backgroundColor = Color.White)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1E40AF), modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Text("拉人入群", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6))
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(value = keyword, onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (keyword.isEmpty()) Text("搜索用户...", fontSize = 13.sp, color = Color(0xFF9CA3AF)); inner() }
                })
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
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
                        Text(un, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937),
                            modifier = Modifier.weight(1f))
                        Text("拉入", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp)).background(Color(0xFFEFF6FF))
                                .clickable {
                                    scope.launch {
                                        val r = ChatRepository.addGroupMember(groupId, uid)
                                        if (r.success) {
                                            // 显示服务端返回的真实结果（已加入群聊 / 已发送入群邀请 / 已提交入群申请）
                                            Toast.makeText(ctx, r.message.ifBlank { "已拉入 $un" }, Toast.LENGTH_SHORT).show()
                                            onMemberAdded()
                                        } else {
                                            Toast.makeText(ctx, "失败: ${r.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
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

    LaunchedEffect(groupId) {
        val r = ChatRepository.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until r.data!!.length()) {
                val m = r.data!!.getJSONObject(i)
                val role = m.optString("role", "")
                if (role != "owner") list.add(m)
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

    if (showConfirmDialog && confirmTarget != null) {
        val (targetId, targetName) = confirmTarget!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = Color.White,
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
                androidx.compose.material3.TextButton(onClick = {
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
                }) { Text("确认移除", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showConfirmDialog = false }) {
                    Text("取消", color = Color(0xFF6B7280))
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        // 事件拦截层：白底 + 消费空白区域点击，防止事件穿透到背景的设置面板
        EventBlocker(backgroundColor = Color.White)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1E40AF), modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Text("移除成员", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6))
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(value = keyword, onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (keyword.isEmpty()) Text("搜索成员...", fontSize = 13.sp, color = Color(0xFF9CA3AF)); inner() }
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
                    Text(un, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937),
                        modifier = Modifier.weight(1f))
                    Text("移除", fontSize = 13.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp)).background(Color(0xFFFEE2E2))
                            .clickable { confirmTarget = uid to un; blockUser = false; showConfirmDialog = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            }
        }
    }
    }
}

// ==================== 分享群聊弹窗 ====================

@Composable
private fun ShareGroupDialog(
    groupId: Long,
    groupName: String,
    groupSignature: String,
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
            val result = if (conv.id < 0) {
                val gid = -(conv.id + 1000)
                ChatRepository.sendGroupMessage(gid, msg)
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
                .background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(top = 16.dp)
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(40.dp).height(4.dp).background(Color(0xFFD1D5DB), CircleShape))
            }
            Text("分享群聊到", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(42.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFFF3F4F6)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart) {
                BasicTextField(value = searchText, onValueChange = { searchText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                    cursorBrush = SolidColor(Color(0xFF1E40AF)),
                    decorationBox = { inner ->
                        if (searchText.isEmpty()) Text("搜索好友或群聊", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                        inner()
                    }, modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("好友" to 0, "群聊" to 1).forEach { (label, index) ->
                    val isSelected = selectedTab == index
                    Box(Modifier.clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) Color(0xFF1E40AF) else Color(0xFFF3F4F6))
                        .clickable { selectedTab = index }
                        .padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color(0xFF6B7280))
                    }
                }
            }
            Text("${filteredList.size} 个${if (selectedTab == 0) "好友" else "群聊"}",
                fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                }
            } else if (filteredList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searchText.isNotEmpty()) "未找到匹配的${if (selectedTab == 0) "好友" else "群聊"}" else "暂无${if (selectedTab == 0) "好友" else "群聊"}",
                        fontSize = 14.sp, color = Color(0xFF9CA3AF))
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
                                Text(conv.username, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isGroup) Text("群聊", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                            }
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFEEF2FF))
                                .clickable { sendShare(conv) }.padding(horizontal = 14.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center) {
                                Text("分享", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFF3F4F6)))
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
private fun MuteMemberPanel(
    groupId: Long,
    currentUserId: Long,
    isAdmin: Boolean,
    isOwner: Boolean,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var keyword by remember { mutableStateOf("") }
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

    // 自定义禁言时长弹窗
    if (showCustomDurationDialog != null) {
        val (targetId, targetName) = showCustomDurationDialog!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCustomDurationDialog = null },
            containerColor = Color.White,
            title = { Text("自定义禁言「$targetName」", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("请输入禁言时长（最多365天）：", fontSize = 14.sp, color = Color(0xFF6B7280))
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
                                .background(Color(0xFFF3F4F6))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box { if (customDurationInput.isEmpty()) Text("输入时长", fontSize = 13.sp, color = Color(0xFF9CA3AF)); inner() }
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
                                color = if (selected) Color.White else Color(0xFF1F2937),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color(0xFF1E40AF) else Color(0xFFF3F4F6))
                                    .clickable { customDurationUnit = unit }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val input = customDurationInput.toLongOrNull()
                    if (input == null || input <= 0) {
                        Toast.makeText(ctx, "请输入有效时长", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val seconds = when (customDurationUnit) {
                        "天" -> input * 86400L
                        "小时" -> input * 3600L
                        else -> input * 60L
                    }
                    val maxSeconds = 365 * 86400L // 1年
                    if (seconds > maxSeconds) {
                        Toast.makeText(ctx, "禁言时长不能超过365天", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    showCustomDurationDialog = null
                    scope.launch {
                        try {
                            val result = ChatRepository.groupMuteUser(groupId, targetId, seconds)
                            if (result.success) {
                                val operatorLabel = if (isOwner) "群主" else "管理员"
                                Toast.makeText(ctx, "$operatorLabel 禁言了 $targetName", Toast.LENGTH_SHORT).show()
                                refreshing++
                            } else {
                                Toast.makeText(ctx, "禁言失败: ${result.message}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "禁言失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text("确认", color = Color(0xFF1E40AF))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCustomDurationDialog = null }) {
                    Text("取消", color = Color(0xFF6B7280))
                }
            }
        )
    }

    if (showDurationPicker != null) {
        val (targetId, targetName) = showDurationPicker!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDurationPicker = null },
            containerColor = Color.White,
            title = { Text("禁言「$targetName」", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("选择禁言时长：", fontSize = 14.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(12.dp))
                    MUTE_DURATION_OPTIONS.forEach { (label, seconds) ->
                        Text(label, fontSize = 15.sp, color = Color(0xFF1F2937),
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
                                                    val operatorLabel = if (isOwner) "群主" else "管理员"
                                                    Toast.makeText(ctx, "$operatorLabel 禁言了 $targetName", Toast.LENGTH_SHORT).show()
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
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDurationPicker = null }) {
                    Text("取消", color = Color(0xFF6B7280))
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { onDismiss() })
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).align(Alignment.BottomCenter)
                .background(Color.White, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFD1D5DB).copy(alpha = 0.5f)))
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("禁言成员", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                modifier = Modifier.weight(1f))
            Text("关闭", fontSize = 14.sp, color = Color(0xFF1E40AF), modifier = Modifier.clickable { onDismiss() })
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6))
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(value = keyword, onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = Color(0xFF1F2937)),
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (keyword.isEmpty()) Text("搜索成员...", fontSize = 13.sp, color = Color(0xFF9CA3AF)); inner() }
                })
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

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
                            Text(un, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
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
                                                    val operatorLabel = if (isOwner) "群主" else "管理员"
                                                    Toast.makeText(ctx, "$operatorLabel 解除了 $un 的禁言", Toast.LENGTH_SHORT).show()
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
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
                }
            }
        }
    }
}
}
