package com.aurora.chat.ui.chat
import com.aurora.chat.ui.theme.LocalAppColors
import com.aurora.chat.ui.theme.ActiveAppColors

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.UserAvatar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults

// 与群聊信息（GroupSettingsPanel）同一套视觉：浅灰底 #F5F6F7 + 白色卡片 14dp 圆角 + 0.5dp 分割线
private val PageBg get() = com.aurora.chat.ui.theme.appSurface()
private val CardBg get() = com.aurora.chat.ui.theme.appCardBg()
private val LabelColor get() = ActiveAppColors.textSecondary
private val ValueColor get() = ActiveAppColors.textPrimary
private val HintColor get() = ActiveAppColors.textSecondary
private val BrandBlue = Color(0xFF1E40AF)
private val DividerColor get() = com.aurora.chat.ui.theme.appDivider()

@Composable
fun UserProfileCard(
    userId: Long,
    userName: String,
    currentUserId: Long,
    onBack: () -> Unit,
    onDeleteFriend: () -> Unit,
    onOpenChat: (Long, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAvatarPreview by remember { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isFriend by remember { mutableStateOf<Boolean?>(null) }
    var friendInfo by remember { mutableStateOf<com.aurora.chat.data.api.UserInfo?>(null) }
    // 粉丝数/关注数：任何入口打开的资料页都要能看到
    var followStats by remember { mutableStateOf<com.aurora.chat.data.api.FollowStats?>(null) }
    // 关注/取消关注请求进行中：防止连点
    var followBusy by remember { mutableStateOf(false) }
    // 取消关注需要二次确认，避免手滑
    var showUnfollowConfirm by remember { mutableStateOf(false) }
    // 好友申请（打招呼）填写面板
    var showGreeting by remember { mutableStateOf(false) }
    var greetingEmail by remember { mutableStateOf("") }
    var isSubmittingFriend by remember { mutableStateOf(false) }
    // 分享用户卡片
    var showShareMenu by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var currentUserName by remember { mutableStateOf("") }
    val displayName by remember(userName, friendInfo) {
        derivedStateOf {
            if (userName.isNotBlank()) userName else (friendInfo?.username ?: "用户$userId")
        }
    }

    LaunchedEffect(userId) {
        // 首帧先铺资料缓存：点开任意用户（社区、群成员、搜索）立刻有名字，断网也不空白
        if (userId > 0) friendInfo = ChatRepository.getCachedUserInfo(userId)
        // 尝试从好友列表获取
        val result = ChatRepository.getFriends()
        if (result.success) {
            val friends = result.data ?: emptyList()
            isFriend = friends.any { it.id == userId }
        } else {
            isFriend = false
        }
        // 始终调用 getUserInfo 获取完整资料（好友列表数据不完整）
        if (userId > 0) {
            val info = ChatRepository.getUserInfo(userId)
            if (info.success && info.data != null) {
                friendInfo = info.data
            }
            // 粉丝数 / 关注数：单独一个轻量接口，不改动 getUserInfo 的返回结构
            followStats = withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.aurora.chat.data.api.AuroraApi.getFollowStats(userId)
            }
        }
    }

    // 关注 / 取消关注统一入口：按钮和确认框共用，避免两处各写一套逻辑
    fun doFollowChange(wantFollow: Boolean) {
        if (followBusy || userId <= 0) return
        followBusy = true
        scope.launch {
            val r = if (wantFollow) {
                com.aurora.chat.data.api.AuroraApi.followUser(userId)
            } else {
                com.aurora.chat.data.api.AuroraApi.unfollowUser(userId)
            }
            followBusy = false
            if (r != null) {
                // 只更新粉丝数和关注状态；followsYou 是对方对我的关系，不受我这次操作影响
                followStats = followStats?.copy(fans = r.fans, isFollowing = r.isFollowing) ?: r
                // 同步短视频动作栏缓存，否则返回视频后加号状态不同步
                com.aurora.chat.ui.profile.svFollowState[userId] = r.isFollowing
                Toast.makeText(
                    context,
                    if (r.isFollowing) "已关注" else "已取消关注",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, "操作失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 当前用户名（分享用户卡片时的"来自"）
    LaunchedEffect(currentUserId) {
        if (currentUserId > 0) {
            currentUserName = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ChatRepository.getUserInfo(currentUserId).data?.username ?: "" }.getOrDefault("")
            }
        }
    }

    // ── 资料行的展示值（全部在 Composable 内派生，避免每帧重复计算） ──
    val nowSec = System.currentTimeMillis() / 1000
    val createdText = remember(friendInfo?.createdAt) {
        val ts = friendInfo?.createdAt ?: 0L
        if (ts <= 0) "" else {
            val elapsed = (System.currentTimeMillis() / 1000 - ts).coerceAtLeast(0)
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val date = fmt.format(java.util.Date(ts * 1000))
            val span = if (elapsed >= 86400) "${elapsed / 86400} 天" else "${elapsed / 3600} 小时"
            "$date · $span"
        }
    }
    val lastActiveDiff = if (friendInfo != null && friendInfo!!.lastActiveAt > 0)
        (nowSec - friendInfo!!.lastActiveAt).coerceAtLeast(0) else -1L
    val isOnline = lastActiveDiff in 0..59
    val lastActiveText = when {
        lastActiveDiff < 0 -> ""
        lastActiveDiff < 60 -> "在线"
        lastActiveDiff < 3600 -> "${lastActiveDiff / 60} 分钟前"
        lastActiveDiff < 86400 -> "${lastActiveDiff / 3600} 小时前"
        else -> "${lastActiveDiff / 86400} 天前"
    }
    val qqText = when {
        friendInfo == null -> ""
        friendInfo!!.qqNumber.isBlank() -> "未设置"
        friendInfo!!.hideQQ == 1 -> com.aurora.chat.util.MaskUtil.qq(friendInfo!!.qqNumber)
        else -> friendInfo!!.qqNumber
    }
    // 打码串（含 *）说明服务端按隐私策略遮蔽过：此时不能再提供「点击复制」，
    // 否则用户复制到的是一串带星号的假号码。hideQQ==0 也不等于可见——
    // 陌生人的 QQ 现在由后端统一打码，前端必须认这个结果，而不能只看开关。
    val qqMasked = friendInfo != null &&
        (friendInfo!!.hideQQ == 1 || friendInfo!!.qqNumber.contains('*'))
    val qqCopiable = friendInfo != null && !qqMasked && friendInfo!!.qqNumber.isNotBlank()
    val sigText = if (friendInfo != null && friendInfo!!.signature.isNotBlank())
        friendInfo!!.signature else "这个人很懒，什么都没写…"

    val copyTo: (String, String) -> Unit = { label, text ->
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize().background(PageBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 固定标题栏（不随内容滚动，实色底不会透出背后颜色） ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(28.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BrandBlue)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "用户资料", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = ValueColor, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.width(12.dp))
                // 仅他人资料显示分享入口
                if (userId != currentUserId) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "⋯", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BrandBlue,
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
                                text = { Text("分享用户", fontSize = 14.sp, color = ValueColor) },
                                onClick = {
                                    showShareMenu = false
                                    showShare = true
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
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                item { Spacer(Modifier.height(16.dp)) }

                // ── 头部信息区（灰底直放，不卡片化）：头像 + 名字 + ID/状态 ──
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(
                            userId = userId, userName = userName, size = 56.dp,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    try {
                                        val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val bytes = com.aurora.chat.data.api.HttpClient.downloadBytes(
                                                com.aurora.chat.data.api.AuroraApi.getAvatarUrlWithTimestamp(userId)
                                            )
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        }
                                        if (bmp != null) {
                                            previewBitmap = bmp
                                            showAvatarPreview = true
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                    color = ValueColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                        .clickable { copyTo("用户名", displayName) }
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
                            Text(
                                "ID：$userId", fontSize = 13.sp, color = HintColor,
                                modifier = Modifier.clickable { copyTo("ID", userId.toString()) }
                            )
                            // 粉丝 / 关注 + 关注按钮：未加载完成时用占位符，避免数字从 0 跳变
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "粉丝 ${followStats?.fans ?: "-"}",
                                    fontSize = 13.sp, color = HintColor
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "关注 ${followStats?.following ?: "-"}",
                                    fontSize = 13.sp, color = HintColor
                                )
                                if (userId != currentUserId && userId > 0) {
                                    Spacer(Modifier.width(12.dp))
                                    val following = followStats?.isFollowing == true
                                    // 双方互相关注：文案要能区分，不然用户看不出对方也关注了自己
                                    val mutual = following && followStats?.followsYou == true
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(
                                                if (following) Color.Transparent else BrandBlue
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (following) BrandBlue.copy(alpha = 0.45f) else Color.Transparent,
                                                shape = RoundedCornerShape(50)
                                            )
                                            .clickable(enabled = !followBusy) {
                                                // 已关注先弹确认，避免手滑误触；未关注直接关注
                                                if (following) showUnfollowConfirm = true
                                                else doFollowChange(true)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            when {
                                                followBusy -> "…"
                                                mutual -> "互相关注"
                                                following -> "已关注"
                                                else -> "关注"
                                            },
                                            fontSize = 11.sp,
                                            color = if (following) BrandBlue else Color.White,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 个性签名（单独整行，最多两行，不挤压头像右侧） ──
                item {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        sigText, fontSize = 13.sp, color = LabelColor, lineHeight = 19.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                            .clickable { copyTo("个性签名", sigText) }
                    )
                }

                item { Spacer(Modifier.height(18.dp)) }

                // ── 资料卡（白卡）：QQ 号 / 加入时间 / 上次活动（离线时才有） ──
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp)).background(CardBg)
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        // 资料尚未拉回：先占一行，避免卡片高度在加载完成瞬间跳动
                        if (friendInfo == null) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("资料", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LabelColor)
                                Spacer(Modifier.weight(1f))
                                Text("正在加载…", fontSize = 14.sp, color = HintColor)
                            }
                        } else {
                            // QQ 号（开启「不显示 QQ 号」时打码）
                            InfoRow(
                                label = "QQ 号",
                                value = qqText,
                                valueMuted = !qqCopiable,
                                onClick = if (qqCopiable) ({ copyTo("QQ号", friendInfo!!.qqNumber) }) else null
                            )
                            if (createdText.isNotBlank()) {
                                Divider0()
                                InfoRow(label = "加入时间", value = createdText, valueMuted = false, onClick = null)
                            }
                            if (lastActiveText.isNotBlank() && !isOnline) {
                                Divider0()
                                InfoRow(label = "上次活动", value = lastActiveText, valueMuted = false, onClick = null)
                            }
                        }
                    }
                }

                // ── 操作卡（白卡）：半宽按钮并排，不再通栏实心大蓝条 ──
                // 自己：只给「发消息」→ 服务端自聊会话（个人对话）；不能加自己为好友、也不显示删除好友。
                // 他人：沿用好友 / 非好友两套操作。
                val isSelf = userId == currentUserId
                item { Spacer(Modifier.height(12.dp)) }
                item {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(14.dp)).background(CardBg)
                                .padding(12.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (isSelf) {
                                    PrimaryAction(
                                        text = "发消息",
                                        modifier = Modifier.fillMaxWidth()
                                    ) { onOpenChat(userId, displayName) }
                                } else if (isFriend == true) {
                                    // 好友：卡片内只放主按钮，删除好友弱化为下方一行文字，不再红蓝并排抢眼
                                    PrimaryAction(
                                        text = "发消息",
                                        modifier = Modifier.fillMaxWidth()
                                    ) { onOpenChat(userId, displayName) }
                                } else {
                                    PrimaryAction(
                                        text = "添加好友",
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        scope.launch {
                                            val info = com.aurora.chat.data.repository.ChatRepository.getUserInfo(userId)
                                            if (info.success && info.data != null) {
                                                greetingEmail = info.data!!.email
                                                showGreeting = true
                                            } else {
                                                Toast.makeText(context, "获取用户信息失败", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    SecondaryAction(
                                        text = "发消息",
                                        modifier = Modifier.weight(1f)
                                    ) { onOpenChat(userId, displayName) }
                                }
                            }
                            if (!isSelf && isFriend == true) {
                                // 危险操作弱化：无底色无描边的小字行，点击仍走确认弹窗
                                Text(
                                    "删除好友", fontSize = 13.sp, color = Color(0xFFDC2626),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().clickable { showDeleteDialog = true }
                                        .padding(vertical = 12.dp)
                                )
                            }
                        }
                }
            }
        }
    }

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
                Text("删除好友", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = ValueColor)
                Spacer(Modifier.height(12.dp))
                Text("确定要删除好友「$displayName」吗？\n删除后需重新发送好友申请。",
                    fontSize = 14.sp, color = LabelColor)
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Text("取消", fontSize = 15.sp, color = LabelColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp)).clickable { showDeleteDialog = false }
                            .padding(horizontal = 24.dp, vertical = 10.dp))
                    Text("确认删除", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFFEE2E2))
                            .clickable {
                                showDeleteDialog = false
                                Toast.makeText(context, "正在删除...", Toast.LENGTH_SHORT).show()
                                onDeleteFriend()
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // 头像预览弹窗（半透明背景，点外部关闭）
    // 取消关注确认：已关注状态点一下就取消太容易手滑，统一二次确认
    if (showUnfollowConfirm) {
        AlertDialog(
            onDismissRequest = { showUnfollowConfirm = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = {
                Text(
                    "取消关注", fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, color = ValueColor
                )
            },
            text = {
                Text("确定不再关注 $displayName 吗？", fontSize = 14.sp, color = LabelColor)
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnfollowConfirm = false
                    doFollowChange(false)
                }) { Text("确定取消", color = Color(0xFFDC2626), fontSize = 14.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showUnfollowConfirm = false }) {
                    Text("再想想", color = HintColor, fontSize = 14.sp)
                }
            }
        )
    }

    if (showAvatarPreview && previewBitmap != null) {
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }
        val animProgress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { animProgress.animateTo(1f, tween(200)) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable { showAvatarPreview = false }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = previewBitmap!!.asImageBitmap(),
                contentDescription = "头像预览",
                modifier = Modifier
                    .graphicsLayer(
                        scaleX = scale * animProgress.value,
                        scaleY = scale * animProgress.value,
                        alpha = animProgress.value,
                        translationX = offsetX, translationY = offsetY
                    )
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Fit
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .statusBarsPadding()
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable { showAvatarPreview = false },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 好友申请（打招呼）填写面板
    if (showGreeting) {
        Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BrandBlue, modifier = Modifier.clickable { showGreeting = false })
                Spacer(Modifier.width(12.dp))
                Text("添加好友", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ValueColor)
            }
            Spacer(Modifier.height(20.dp))
            Text("发送好友申请给 $displayName", fontSize = 14.sp, color = LabelColor, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            var greeting by remember { mutableStateOf("你好，交个朋友吧！") }
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                    .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp)).padding(12.dp)
            ) {
                BasicTextField(
                    value = greeting, onValueChange = { greeting = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = ValueColor),
                    singleLine = false, minLines = 3, maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (greeting.isEmpty()) Text("请输入打招呼内容…", fontSize = 14.sp, color = HintColor)
                            inner()
                        }
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.fillMaxWidth().padding(24.dp)
                    .clip(RoundedCornerShape(10.dp)).background(BrandBlue)
                    .clickable(enabled = !isSubmittingFriend) {
                        scope.launch {
                            isSubmittingFriend = true
                            val r = com.aurora.chat.data.repository.ChatRepository.sendFriendRequest(greetingEmail, greeting.trim())
                            isSubmittingFriend = false
                            Toast.makeText(context, if (r.success) "好友申请已发送" else "发送失败: ${r.message}", Toast.LENGTH_SHORT).show()
                            if (r.success) { showGreeting = false; isFriend = false }
                        }
                    }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (isSubmittingFriend) "发送中..." else "发送", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
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
            postTitle = displayName,
            postContent = friendInfo?.signature ?: "",
            postId = userId,
            postUserId = userId,
            postUsername = currentUserName,
            postType = "post",
            titlePrefix = "分享用户",
            idKey = "user_id",
            onDismiss = { showShare = false }
        )
    }
}

/** 资料行：左侧灰色 label，右侧取值（可点击复制时右上角带小箭头提示） */
@Composable
private fun InfoRow(
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
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LabelColor)
        Spacer(Modifier.width(12.dp))
        Text(
            value, fontSize = 14.sp, color = if (valueMuted) HintColor else ValueColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.Filled.ChevronRight, contentDescription = null,
                tint = HintColor, modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun Divider0() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(DividerColor))
}

/** 主操作按钮：实心品牌蓝 */
@Composable
private fun PrimaryAction(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
            modifier = modifier.clip(RoundedCornerShape(12.dp)).background(BrandBlue)
                .clickable { onClick() }.padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
}

/** 次操作按钮：浅蓝底 + 品牌蓝字（不抢主按钮） */
@Composable
private fun SecondaryAction(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
            modifier = modifier.clip(RoundedCornerShape(12.dp)).background(com.aurora.chat.ui.theme.appSelectedBg())
                .clickable { onClick() }.padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BrandBlue)
        }
}
