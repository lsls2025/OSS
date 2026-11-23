package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.launch

/**
 * 群管理（全屏页）：由群聊信息右上角「⋯」直接切入，取代原来的「群管理」弹窗。
 *
 * 入口顺序：分享群聊 / 发言频率 / 禁言成员 / 入群申请 / 设置管理员 / 管理员权限 / 转让群主。
 * 各子页都在本页内部做左右切入，返回时逐层退出，不会把用户甩回群聊信息页。
 */
@Composable
internal fun GroupManageScreen(
    groupId: Long,
    groupName: String,
    groupSignature: String,
    currentUserId: Long,
    isOwner: Boolean,
    isOfficialGroup: Boolean,
    canPerm: (String) -> Boolean,
    onBack: () -> Unit,
    onOpenJoinRequestList: (org.json.JSONArray, Long) -> Unit = { _, _ -> },
    onDissolve: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var showShare by remember { mutableStateOf(false) }
    var showMsgRate by remember { mutableStateOf(false) }
    var showMute by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }
    var showAdminPerms by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var loadingJoinRequests by remember { mutableStateOf(false) }

    val subPageOpen = showShare || showMsgRate || showMute || showAdmin || showAdminPerms || showTransfer
    fun closeSubPage() {
        when {
            showShare -> showShare = false
            showTransfer -> showTransfer = false
            showAdminPerms -> showAdminPerms = false
            showAdmin -> showAdmin = false
            showMute -> showMute = false
            showMsgRate -> showMsgRate = false
        }
    }

    // 本页在最上层：先退子页，子页都关了才退回群聊信息
    BackHandler(enabled = true) { if (subPageOpen) closeSubPage() else onBack() }

    val entries = buildList {
        add(ManageEntry("分享群聊") { showShare = true })
        if (canPerm("msg_rate")) add(ManageEntry("发言频率", "默认为不限制，可设每分钟条数") { showMsgRate = true })
        if (canPerm("mute")) add(ManageEntry("禁言成员", "禁言或解除禁言单个成员") { showMute = true })
        // 入群申请原先藏在「设置管理」页右上角，权限口径保持与之一致（仅群主）
        if (isOwner) {
            add(ManageEntry("入群申请", "审批想要加入本群的申请") {
                if (!loadingJoinRequests) {
                    loadingJoinRequests = true
                    scope.launch {
                        try {
                            val r = AuroraApi.getJoinRequests(groupId)
                            if (r.success && r.data != null) {
                                onOpenJoinRequestList(r.data!!, groupId)
                            } else {
                                Toast.makeText(ctx, "获取入群申请失败: ${r.message}", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                ctx,
                                "获取入群申请失败: ${e.localizedMessage ?: "网络异常"}",
                                Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            loadingJoinRequests = false
                        }
                    }
                }
            })
            add(ManageEntry("设置管理员", "任命或取消管理员") { showAdmin = true })
            add(ManageEntry("管理员权限", "管理员能做什么、不能做什么") { showAdminPerms = true })
        }
        // 官方群不提供解散 / 转让：群里是全体用户，解散或转让都会破坏「全员强制群」的前提
        if (isOwner && !isOfficialGroup) {
            add(ManageEntry("转让群主", "把群主身份交给其他成员") { showTransfer = true })
            add(ManageEntry("解散群聊", "解散后所有成员都无法再发言", danger = true) { onDissolve() })
        }
    }

    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg()).groupPageTouchGuard()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GroupPageHeader(title = "群管理", onBack = onBack)

            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp)).background(com.aurora.chat.ui.theme.appSurface())
                    ) {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) {
                                Box(
                                    Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp)
                                        .background(com.aurora.chat.ui.theme.appDivider())
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { entry.onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                        color = if (entry.danger) Color(0xFFDC2626) else com.aurora.chat.ui.theme.appTextPrimary()
                                    )
                                    if (entry.subtitle.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(entry.subtitle, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    }
                                }
                                Text(
                                    "›", fontSize = 18.sp,
                                    color = if (entry.danger) Color(0xFFF87171) else Color(0xFFC4C8CE)
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        // ── 子页：从右向左切入 ──
        AnimatedVisibility(
            visible = showMsgRate,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(tween(260)),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(tween(260))
        ) {
            GroupMsgRateScreen(groupId = groupId, onBack = { showMsgRate = false })
        }

        AnimatedVisibility(
            visible = showMute,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(tween(260)),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(tween(260))
        ) {
            MuteMemberPanel(
                groupId = groupId,
                currentUserId = currentUserId,
                // 没有「禁言成员」权限的管理员进来只能看名单（内部按钮按 isAdmin 判断）
                isAdmin = canPerm("mute"),
                isOwner = isOwner,
                onDismiss = { showMute = false }
            )
        }

        AnimatedVisibility(
            visible = showAdmin,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(tween(260)),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(tween(260))
        ) {
            AdminManagementPanel(
                groupId = groupId,
                onBack = { showAdmin = false },
                onOpenJoinRequestList = onOpenJoinRequestList,
                // 只有群主能设置/取消管理员；管理员可看名单，但角色变更按钮不显示
                canManageAdmins = isOwner
            )
        }

        AnimatedVisibility(
            visible = showAdminPerms,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(tween(260)),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(tween(260))
        ) {
            GroupAdminPermsScreen(groupId = groupId, onBack = { showAdminPerms = false })
        }

        AnimatedVisibility(
            visible = showTransfer,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(260)) + fadeIn(tween(260)),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(260)) + fadeOut(tween(260))
        ) {
            GroupTransferOwnerScreen(
                groupId = groupId,
                onBack = { showTransfer = false },
                onTransferred = { showTransfer = false; onBack() }
            )
        }

        // 分享群聊：直接对接既有的分享模块（选好友 / 复制链接 / 面板），不自己重做一套
        if (showShare) {
            ShareGroupDialog(
                groupId = groupId,
                groupName = groupName,
                groupSignature = groupSignature,
                onDismiss = { showShare = false }
            )
        }
    }
}

private data class ManageEntry(
    val title: String,
    val subtitle: String = "",
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 全屏页根节点用：整页吞掉未被控件消费的点击，避免空白处穿透点到背后（群聊信息主面板 / 列表）。
 * 子节点自己的 clickable 在命中测试中优先，所以不会影响页面内按钮。
 */
@Composable
internal fun Modifier.groupPageTouchGuard(): Modifier = clickable(
    indication = null,
    interactionSource = remember { MutableInteractionSource() }
) { }

/** 全屏子页统一顶栏：左返回 + 居中标题（右侧留等宽占位，保证标题真正居中） */
@Composable
internal fun GroupPageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(28.dp).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center
        )
        Spacer(Modifier.width(12.dp))
        Spacer(Modifier.size(28.dp))
    }
}

/**
 * 发言频率（全屏页）：标题 + 输入框 + 快捷项 + 保存。
 * 不按保存直接返回也会自动提交（与群名称/群备注的「编辑即保存」一致）。
 */
@Composable
internal fun GroupMsgRateScreen(groupId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(-1) }
    var input by remember { mutableStateOf("") }
    // 作用范围：0=群主及管理员不受限（默认），1=管理员受限，2=全部受限
    var savedScope by remember { mutableIntStateOf(0) }
    var currentScope by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(groupId) {
        val r = ChatRepository.getGroupInfo(groupId)
        if (r.success && r.data != null) {
            current = r.data!!.optInt("msg_rate_limit", -1)
            input = if (current < 0) "" else current.toString()
            savedScope = r.data!!.optInt("msg_rate_scope", 0)
            currentScope = savedScope
        }
        loaded = true
    }

    // 空 = 不限制；"0" = 全员禁言；其余为每分钟条数
    fun pendingLimit(): Int = input.trim().let { if (it.isEmpty()) -1 else (it.toIntOrNull() ?: -1) }

    fun save(silent: Boolean, onDone: () -> Unit) {
        // 还没读到当前值就返回：直接退出，不做无意义的提交（也避免卡在“按了没反应”）
        if (saving) return
        if (!loaded) { onDone(); return }
        val target = pendingLimit()
        if (target == current && currentScope == savedScope) {
            // 没有改动也要给反馈，否则点击「保存」看起来像没生效
            if (!silent) Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show()
            onDone()
            return
        }
        saving = true
        scope.launch {
            val r = ChatRepository.setGroupMsgRate(groupId, target, currentScope)
            saving = false
            if (r.success) {
                current = target
                savedScope = currentScope
                if (!silent) {
                    Toast.makeText(
                        ctx,
                        when {
                            target < 0 -> "已关闭发言频率限制"
                            target == 0 -> "已设为全员禁言"
                            else -> "已设为每位成员每分钟 $target 条"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
                onDone()
            } else {
                Toast.makeText(ctx, "保存失败: ${r.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler { save(silent = true) { onBack() } }

    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding().groupPageTouchGuard()) {
        GroupPageHeader(title = "发言频率", onBack = { save(silent = true) { onBack() } })

        Column(
            Modifier.fillMaxWidth().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg()).padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.filter { c -> c.isDigit() }.take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextPrimary()
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(Color(0xFF1E40AF)),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    "每分钟最多几条，留空表示不限制",
                                    fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextHint()
                                )
                            }
                            inner()
                        }
                    }
                )
            }

            Spacer(Modifier.height(14.dp))
            // 快捷项：不限制（= 空值，与服务端 -1 同义，也是新建群的默认）／5 条／10 条／全员禁言。
            // 四项挤一行在窄屏上会被截断，所以排成两行两列，各自等宽。
            listOf(
                "不限制" to "",
                "每分钟 5 条" to "5",
                "每分钟 10 条" to "10",
                "全员禁言" to "0"
            ).chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (label, value) ->
                        val picked = input == value
                        Box(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (picked) Color(0xFFDBEAFE) else com.aurora.chat.ui.theme.appCardBg())
                                .clickable { input = value }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label, fontSize = 12.sp,
                                color = if (picked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextSecondary(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(24.dp))
            Text("作用范围", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(8.dp))
            // 群主及管理员是否受频率限制：默认「群主及管理员不受限」
            listOf(
                Triple(0, "群主及管理员不受限", "群主、管理员可以正常发言"),
                Triple(1, "管理员受限", "群主仍不受限，管理员按频率限制"),
                Triple(2, "全部受限", "群主、管理员同样按频率限制")
            ).forEach { (value, label, desc) ->
                val picked = currentScope == value
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (picked) com.aurora.chat.ui.theme.appSelectedBg() else com.aurora.chat.ui.theme.appCardBg())
                        .clickable { currentScope = value }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape)
                            .background(if (picked) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                            .border(1.5.dp, if (picked) Color(0xFF1E40AF) else Color(0xFFD1D5DB), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (picked) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(com.aurora.chat.ui.theme.appSurface()))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(label, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                        Text(desc, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (saving) Color(0xFF93A7D4) else Color(0xFF1E40AF))
                    .clickable(enabled = !saving) { save(silent = false) { } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (saving) "保存中…" else "保存",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White
                )
            }
        }
    }
}

/**
 * 转让群主（全屏页）：搜索 + 成员列表 + 每行「转让」 → 确认弹窗 → 转让。
 */
@Composable
internal fun GroupTransferOwnerScreen(
    groupId: Long,
    onBack: () -> Unit,
    onTransferred: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var keyword by remember { mutableStateOf("") }
    var confirmTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var transferring by remember { mutableStateOf(false) }

    LaunchedEffect(groupId) {
        val r = ChatRepository.getGroupMembers(groupId)
        if (r.success && r.data != null) {
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until r.data!!.length()) {
                val m = r.data!!.getJSONObject(i)
                // 群主自己不能转让给自己
                if (m.optString("role", "") != "owner") list.add(m)
            }
            members = list
        }
        loading = false
    }

    val filtered = remember(members, keyword) {
        if (keyword.isBlank()) members else members.filter { m ->
            m.optString("username", "").contains(keyword, ignoreCase = true) ||
                m.optLong("user_id").toString().contains(keyword)
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).groupPageTouchGuard()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GroupPageHeader(title = "转让群主", onBack = onBack)

            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(8.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg()).padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = keyword, onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()
                    ),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (keyword.isEmpty()) Text("搜索成员...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            inner()
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

            if (loading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color(0xFF1E40AF), strokeWidth = 3.dp)
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (members.isEmpty()) "群内无其他成员可转让" else "没有匹配的成员",
                        fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
                    items(filtered, key = { it.optLong("user_id") }) { m ->
                        val uid = m.optLong("user_id")
                        val rawName = m.optString("username", "")
                        val name = if (rawName.isNotBlank()) rawName else "用户$uid"
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(userId = uid, userName = name, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                name, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "转让", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = Color(0xFF1E40AF),
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(com.aurora.chat.ui.theme.appSelectedBg())
                                    .clickable { confirmTarget = uid to name }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                    }
                }
            }
        }

        confirmTarget?.let { (targetId, targetName) ->
            AlertDialog(
                onDismissRequest = { if (!transferring) confirmTarget = null },
                containerColor = com.aurora.chat.ui.theme.appSurface(),
                title = { Text("转让群主", fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        "确定把群主转让给「$targetName」吗？\n转让后你将成为普通成员，此操作不可撤销。",
                        fontSize = 14.sp, lineHeight = 21.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (transferring) return@TextButton
                        transferring = true
                        scope.launch {
                            val r = ChatRepository.transferOwner(groupId, targetId)
                            transferring = false
                            confirmTarget = null
                            if (r.success) {
                                Toast.makeText(ctx, "已转让给 $targetName", Toast.LENGTH_SHORT).show()
                                onTransferred()
                            } else {
                                Toast.makeText(ctx, "转让失败: ${r.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("确认转让", color = Color(0xFFDC2626)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmTarget = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                }
            )
        }
    }
}
