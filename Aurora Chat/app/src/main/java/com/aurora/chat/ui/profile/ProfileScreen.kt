package com.aurora.chat.ui.profile

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.aurora.chat.ui.components.decodeSampledFile
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import com.aurora.chat.ui.community.CommunityActivityBadge
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import com.aurora.chat.ui.theme.LocalAppColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.aurora.chat.R
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.ui.chat.AiChatManager
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.EventBlocker
import com.aurora.chat.ui.activity.ActivityScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

// ==================== 佩戴徽章辅助 ====================

// ==================== 在线时间追踪器（永不重置版） ====================
// 原理：serverBase（服务端/本地最大值） + 本次会话已过秒数
// 启动时从本地 + 服务端取最大值作为基线，防止任何一方数据丢失

object OnlineTimeTracker {
    private const val PREFS = "aurora_online_time"
    private const val KEY_TOTAL_SECONDS = "total_seconds"

    private var serverBase = 0L          // 基线（取本地与服务端的最大值）
    private var sessionStartMs = AtomicLong(0L)
    private var isRunning = false

    fun isRunning(): Boolean = isRunning

    /** 从本地 SharedPreferences 恢复上次保存的总值 */
    fun restoreFromPrefs(context: Context): Long {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_TOTAL_SECONDS, 0L)
        if (saved > serverBase) serverBase = saved
        return serverBase
    }

    /** 设置服务端基线，只接受更大的值（防止旧数据覆写新数据） */
    fun setServerBase(seconds: Long) {
        if (seconds > serverBase) serverBase = seconds
    }

    /** 启动计时（恢复本地 + 返回当前基线） */
    fun start(context: Context): Long {
        restoreFromPrefs(context)
        sessionStartMs.set(System.currentTimeMillis())
        isRunning = true
        return serverBase
    }

    /** 停止计时并持久化 */
    fun stop(context: Context) {
        if (!isRunning) return
        flushToPrefsNow(context)
        isRunning = false
    }

    /** 获取当前总秒数 */
    fun getCurrentTotalSeconds(context: Context): Long {
        if (!isRunning) return serverBase
        flushIfNeeded(context)
        val elapsed = (System.currentTimeMillis() - sessionStartMs.get()) / 1000
        return serverBase + elapsed
    }

    /** 持久化当前总值到本地 */
    private var lastFlush = 0L
    private fun flushToPrefsNow(context: Context) {
        lastFlush = System.currentTimeMillis()
        val total = if (isRunning) {
            serverBase + (System.currentTimeMillis() - sessionStartMs.get()) / 1000
        } else {
            serverBase
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_TOTAL_SECONDS, total).apply()
    }

    /** 每 60 秒自动持久化（由 getCurrentTotalSeconds 调用） */
    private fun flushIfNeeded(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFlush < 60000L) return
        flushToPrefsNow(context)
    }
}

// ==================== 发言字数追踪器（本地累计 + 服务端基线） ====================

object WordCountTracker {
    private const val PREFS = "aurora_word_count"
    private const val KEY_LOCAL_DELTA = "local_delta"

    private var serverBase = 0L    // 服务端累计值
    private var localDelta = 0L    // 上次同步后本地新增

    fun setServerBase(count: Long) {
        serverBase = count
    }

    fun addChars(context: Context, count: Int) {
        if (count <= 0) return
        localDelta += count
    }

    fun getTotal(): Long = serverBase + localDelta

    /** 供 ProfileScreen 定时同步时获取应上传的值 */
    fun computeSyncValue(): Long = serverBase + localDelta

    fun markSynced(context: Context) {
        serverBase += localDelta
        localDelta = 0
    }
}

// ==================== 格式化工具 ====================

fun formatOnlineTime(totalSeconds: Long): String {
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分"
        else -> "${minutes}分钟"
    }
}

fun formatDaysOnPlatform(createdAtSeconds: Long): String {
    if (createdAtSeconds <= 0) return "新用户"
    val days = (System.currentTimeMillis() / 1000 - createdAtSeconds) / 86400
    return when {
        days < 1 -> "今天加入"
        days < 30 -> "${days}天"
        days < 365 -> "${days / 30}个月"
        else -> "${days / 365}年${(days % 365) / 30}个月"
    }
}


/** 我的页统一右箭头：功能行 / 二级页行共用同一处，避免不同入口各写一套导致大小、颜色走样。 */
@Composable
private fun ProfileChevron(
    size: androidx.compose.ui.unit.Dp = 16.dp,
    tint: Color = Color(0xFFC7C7CC)
) {
    Icon(
        Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(size)
    )
}

/** 功能列表的一行：标题在左、右侧 ChevronRight 箭头（路口样式），整行可点；危险项可指定红字。 */
@Composable
private fun ProfileMenuRow(
    title: String,
    onClick: () -> Unit,
    titleColor: Color = com.aurora.chat.ui.theme.appTextPrimary()
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable { onClick() }
            // clickable 在前、padding 在后：点击涟漪贯穿全宽，仅文字有内边距
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = titleColor,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = com.aurora.chat.ui.theme.appTextHint(),
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * 微信「我」页式单行：独立白条 + 左侧彩色图标 + 标题 + 右箭头。
 * 与 [ProfileMenuRow] 不同：不放在圆角卡片里，整行铺满屏幕宽度，靠页面灰底形成「一条一条」的间隔。
 */
@Composable
private fun ProfileWeChatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    onClick: () -> Unit,
    titleColor: Color = com.aurora.chat.ui.theme.appTextPrimary(),
    showDot: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(com.aurora.chat.ui.theme.appSurface())
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = titleColor
            )
            if (showDot) {
                Spacer(Modifier.width(5.dp))
                Box(
                    Modifier.offset(y = 2.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                )
            }
        }
        ProfileChevron()
    }
}

/** 微信式分组中的一条 */
private data class ProfileWeChatItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconTint: Color,
    val title: String,
    val onClick: () -> Unit,
    val titleColor: Color = com.aurora.chat.ui.theme.appTextPrimary(),
    val showDot: Boolean = false
)

/** 微信式分组：组内用分割线连成一条，组与组之间用统一间距分隔 */
@Composable
private fun ProfileWeChatGroup(items: List<ProfileWeChatItem>) {
    Column(Modifier.fillMaxWidth()) {
        items.forEachIndexed { idx, item ->
            if (idx > 0) ProfileMenuDivider()
            ProfileWeChatRow(
                icon = item.icon,
                iconTint = item.iconTint,
                title = item.title,
                onClick = item.onClick,
                titleColor = item.titleColor,
                showDot = item.showDot
            )
        }
    }
}

/** 二级页条目：进入第二层后不再显示图标 */
private data class ProfileSubItem(val title: String, val onClick: () -> Unit)

/**
 * 二级页：页面内全屏覆盖层（不是 Dialog），顶部返回 + 无图标白条列表。
 * 用覆盖层而不是 Dialog，才能做到「小游戏 → 五子棋/漂流瓶 → 返回」仍回到小游戏页。
 */
@Composable
private fun ProfileSubPage(
    title: String,
    items: List<ProfileSubItem>,
    onBack: () -> Unit
) {
    BackHandler(enabled = true) { onBack() }
    Column(
        Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg())
    ) {
        // 标题栏连同状态栏一起铺白，避免顶部状态栏露出一截灰色
        Column(
            Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp).clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = com.aurora.chat.ui.theme.appTextPrimary())
                }
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            }
        }
        Spacer(Modifier.height(12.dp))
        // 内容用白卡承载（与其他页面同一套板块风格），不再用两条贴顶的通栏白行，
        // 否则整屏只剩顶部一小块内容，看起来像被塞在页面上半部分。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(com.aurora.chat.ui.theme.appSurface())
        ) {
            items.forEachIndexed { idx, item ->
                if (idx > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .height(0.5.dp)
                            .background(com.aurora.chat.ui.theme.appCardBg())
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .clickable { item.onClick() }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.title, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
                    ProfileChevron()
                }
            }
        }
    }
}

/** 行间细分隔线：贯穿整屏左右，最后一行之后不放。 */
@Composable
private fun ProfileMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(com.aurora.chat.ui.theme.appCardBg())
    )
}

/** 功能卡片：白底圆角块（页面灰底 → 白卡，微信式层次感）。 */
@Composable
private fun ProfileMenuCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(com.aurora.chat.ui.theme.appSurface())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        content = content
    )
}

/** 分组标题：卡片上方的小字灰色标题。 */
@Composable
private fun ProfileMenuGroupTitle(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = com.aurora.chat.ui.theme.appTextSecondary(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, bottom = 8.dp)
    )
}

/** 卡片内子分组小标题：如「数据管理」「API 接入」，置于对应入口行之前。 */
@Composable
private fun ProfileMenuGroupSubTitle(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = com.aurora.chat.ui.theme.appTextSecondary()
        )
    }
}


// ==================== 主界面 ====================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userId: Long = 0,
    onSwitchAccount: () -> Unit = {},
    onLogout: () -> Unit = {},
    isDeveloper: Boolean = false,
    activityScreenOpen: Boolean = false,
    onActivityScreenOpenChange: (Boolean) -> Unit = {},
    // 二维码入口：点击后在 MainActivity 根级打开"我的二维码"，避免内部覆盖层被压缩/事件穿透
    onShowMyQr: () -> Unit = {},
    // 功能列表跳转
    onEditProfile: () -> Unit = {},
    onWebsiteHosting: () -> Unit = {},
    onMore: () -> Unit = {},
    onShowAvatar: (Bitmap) -> Unit = {},
    onBottle: () -> Unit = {},
    onGomoku: () -> Unit = {},
    // 小游戏：由外层打开独立全屏页（SubScreen），不在此处内嵌展开
    onGamesPage: () -> Unit = {},
    // 短视频：由外层打开独立全屏页（与「小游戏」同级），放在「小游戏」下方、「其他」上方
    onShortVideo: () -> Unit = {},
    // 收藏：短视频收藏及以后的各类收藏，外层打开独立全屏页（与「短视频」同级）
    onFavorites: () -> Unit = {},
    onSettings: () -> Unit = {},
    onMyPosts: () -> Unit = {},
    onOpenCommunityActivity: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 初值直接取头像内存缓存：本页在切走 tab 时会被销毁重建，
    // 若从 null 起步，每次切回「我的」都会先闪一下占位图再变正常。
    var avatarBitmap by remember(userId) {
        mutableStateOf(com.aurora.chat.data.local.AvatarCache.getCachedUserAvatarSync(userId))
    }
    var cropImageUri by remember { mutableStateOf<Uri?>(null) }
    var showCrop by remember { mutableStateOf(false) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    // 昵称/签名同样不能从空串起步：本页切走 tab 时会被销毁重建，
    // 空串会让首帧先画「未设置昵称」，等 getUserInfo 网络往返回来才闪成真名。
    // 这里同步读本地缓存（登录成功时已写入 SharedPreferences），首帧即为正确内容；
    // 本地缓存意外为空时，退回本次会话登录返回的 currentUserName。
    val cachedProfile = remember(userId) {
        val localName = com.aurora.chat.data.local.LocalStorage.getUserName(context, userId)
        val fallbackName =
            if (localName.isBlank() && userId == com.aurora.chat.data.api.AuroraApi.currentUserId)
                com.aurora.chat.data.api.AuroraApi.currentUserName else ""
        (if (localName.isBlank()) fallbackName else localName) to
            com.aurora.chat.data.local.LocalStorage.getUserSignature(context, userId)
    }
    var userName by remember(userId) { mutableStateOf(cachedProfile.first) }
    var userSignature by remember(userId) { mutableStateOf(cachedProfile.second) }
    var userCreatedAt by remember { mutableStateOf(0L) }
    var showNameDialog by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }
    var editSignatureText by remember { mutableStateOf("") }

    // 在线时间
    var onlineTimeSec by remember { mutableStateOf(OnlineTimeTracker.restoreFromPrefs(context)) }
    var serverDataReady by remember { mutableStateOf(false) }
    var showTimeDetail by remember { mutableStateOf(false) }
    var showPlatformDetail by remember { mutableStateOf(false) }
    var userEmailVerified by remember { mutableStateOf(com.aurora.chat.data.api.AuroraApi.currentUserEmailVerified) }

    // userId 变化时重新拉取服务器资料 + 头像 + 在线时间基线
    LaunchedEffect(userId) {
        withContext(Dispatchers.IO) {
            // 头像优先：本地文件就在盘上，先读出来，不能排在 getUserInfo 的网络往返之后，
            // 否则网络一慢，占位图就明晃晃挂在那里。
            if (avatarBitmap == null) {
                try {
                    val avatarFile = com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, userId)
                    val localBmp = if (avatarFile.exists()) decodeSampledFile(avatarFile.absolutePath) else null
                    if (localBmp != null) {
                        avatarBitmap = localBmp
                        // 回填内存缓存，下次切回本页首帧即可直接命中
                        com.aurora.chat.data.local.AvatarCache.cacheUserAvatar(userId, localBmp)
                    } else {
                        val bmp = ChatRepository.loadAvatar(context, userId)
                        if (bmp != null) avatarBitmap = bmp
                    }
                } catch (_: Exception) {}
            }
            try {
                val info = ChatRepository.getUserInfo(userId)
                if (info.success && info.data != null) {
                    userName = info.data!!.username
                    userSignature = info.data!!.signature
                    userCreatedAt = info.data!!.createdAt
                    userEmailVerified = info.data!!.emailVerified
                    // 设置服务端累计值为基线
                    OnlineTimeTracker.setServerBase(info.data!!.onlineTimeSeconds)
                    com.aurora.chat.data.local.LocalStorage.saveUserProfile(
                        context, userId, userName, userSignature
                    )
                } else {
                    userName = com.aurora.chat.data.local.LocalStorage.getUserName(context, userId)
                    userSignature = com.aurora.chat.data.local.LocalStorage.getUserSignature(context, userId)
                }
            } catch (_: Exception) {
                userName = com.aurora.chat.data.local.LocalStorage.getUserName(context, userId)
                userSignature = com.aurora.chat.data.local.LocalStorage.getUserSignature(context, userId)
            }
        }
        // 服务端数据加载完成后启动追踪
        OnlineTimeTracker.start(context)
        serverDataReady = true
    }

    // 下拉刷新：重拉服务器资料 + 头像 + 在线时间基线
    suspend fun reloadProfileData() {
        withContext(Dispatchers.IO) {
            try {
                val info = ChatRepository.getUserInfo(userId)
                if (info.success && info.data != null) {
                    userName = info.data!!.username
                    userSignature = info.data!!.signature
                    userCreatedAt = info.data!!.createdAt
                    userEmailVerified = info.data!!.emailVerified
                    OnlineTimeTracker.setServerBase(info.data!!.onlineTimeSeconds)
                    com.aurora.chat.data.local.LocalStorage.saveUserProfile(
                        context, userId, userName, userSignature
                    )
                } else {
                    userName = com.aurora.chat.data.local.LocalStorage.getUserName(context, userId)
                    userSignature = com.aurora.chat.data.local.LocalStorage.getUserSignature(context, userId)
                }
            } catch (_: Exception) {
                userName = com.aurora.chat.data.local.LocalStorage.getUserName(context, userId)
                userSignature = com.aurora.chat.data.local.LocalStorage.getUserSignature(context, userId)
            }
            // 强制重新拉取头像：先清缓存再拉，确保真正从服务器拉最新；旧头像保留作占位，拉到后覆盖
            try {
                com.aurora.chat.data.local.AvatarCache.clear(userId, context)
                val bmp = ChatRepository.loadAvatar(context, userId)
                if (bmp != null) avatarBitmap = bmp
            } catch (_: Exception) {}
        }
        OnlineTimeTracker.start(context)
        serverDataReady = true
        onlineTimeSec = OnlineTimeTracker.getCurrentTotalSeconds(context)
    }

    // 计时器（每秒刷新，每60秒同步到服务端）
    LaunchedEffect(Unit) {
        var syncCounter = 0
        while (true) {
            delay(1000)
            if (serverDataReady) {
                onlineTimeSec = OnlineTimeTracker.getCurrentTotalSeconds(context)
                syncCounter++
                if (syncCounter >= 15) { // 每15秒同步一次在线时间
                    syncCounter = 0
                    withContext(Dispatchers.IO) {
                        try {
                            val ot = OnlineTimeTracker.getCurrentTotalSeconds(context)
                            val wc = WordCountTracker.computeSyncValue()
                            val r = AuroraApi.syncUserStats(ot, wc)
                            if (r.success) WordCountTracker.markSynced(context)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun saveName(name: String) {
        // 顺序固定：先落本地（立即生效、断网也不丢），再推服务器。
        // 服务器失败不回滚本地，只提示「已存本地、同步失败」。
        userName = name
        com.aurora.chat.data.local.LocalStorage.saveUserProfile(context, userId, name, userSignature)
        com.aurora.chat.data.api.AuroraApi.currentUserName = name
        scope.launch {
            try {
                val result = AuroraApi.updateProfile(name, userSignature)
                if (!result.success) {
                    Toast.makeText(context, "昵称已存本地，服务器同步失败：${result.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "昵称已存本地，服务器同步失败：${e.localizedMessage ?: "网络错误"}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun saveSignature(sig: String) {
        // 同 saveName：本地先落盘，再同步服务器
        userSignature = sig
        com.aurora.chat.data.local.LocalStorage.saveUserProfile(context, userId, userName, sig)
        com.aurora.chat.data.api.AuroraApi.currentUserSignature = sig
        scope.launch {
            try {
                val result = AuroraApi.updateSignature(sig)
                if (!result.success) {
                    Toast.makeText(context, "签名已存本地，服务器同步失败：${result.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "签名已存本地，服务器同步失败：${e.localizedMessage ?: "网络错误"}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { cropImageUri = it; showCrop = true }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
        if (success && cameraPhotoUri != null) { cropImageUri = cameraPhotoUri; showCrop = true }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { val uri = createTempImageUri(context); cameraPhotoUri = uri; cameraLauncher.launch(uri) }
        else { Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show() }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { galleryLauncher.launch("image/*") }
        else { Toast.makeText(context, "需要存储权限才能选择图片", Toast.LENGTH_SHORT).show() }
    }

    // ==================== 微信式过滚动（去掉刷新圈，改成整页可拽动） ====================
    val profileScrollState = rememberScrollState()
    // 手感偏「重」的惯性滚动：系统默认衰减太滑，一甩就飞到底；
    // 这里把摩擦系数拉高，一甩只推进一小段，边到边界就停。
    val profileFlingBehavior = remember {
        object : FlingBehavior {
            private val decay = exponentialDecay<Float>(frictionMultiplier = 3f, absVelocityThreshold = 25f)
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                if (kotlin.math.abs(initialVelocity) < 1f) return initialVelocity
                var lastValue = 0f
                AnimationState(initialValue = 0f, initialVelocity = initialVelocity)
                    .animateDecay(decay) {
                        val delta = value - lastValue
                        lastValue = value
                        val consumed = scrollBy(delta)
                        // 撞到边界立即收手：既不顶着边界空转，也不会把余速甩进过滚动
                        if (kotlin.math.abs(delta - consumed) > 0.5f) cancelAnimation()
                    }
                return 0f
            }
        }
    }
    // 过滚动位移（px）：> 0 = 内容被拽到顶部之外；< 0 = 内容被推到底部之外
    var overscrollPx by remember { mutableFloatStateOf(0f) }
    // 拽出边界时的阻力 —— 渐进式，不是固定系数：
    //   位移越大，新输入的位移能换到的移动量越小（factor = start·e^(−|位移| / K)）。
    //   起点 factor 接近 1（不突然变沉），随后平滑、连续地递减，
    //   但永远不归零 —— 所以只会「越拽越费劲」，不会在某个点突然绑死。
    //   start = 0.82：刚越过边界时就已经有一点点分量；
    //   K（softPx）越大越软，240dp 附近是手感转折区。
    val overscrollStartFactor = 0.82f
    val overscrollSoftPx = with(LocalDensity.current) {
        (240.dp.toPx() * overscrollStartFactor).coerceAtLeast(1f)
    }
    // 拽出超过这个距离再松手，视为「想刷新」：静默重拉资料，不显示任何提示或加载圈
    val silentRefreshThresholdPx = with(LocalDensity.current) { 220.dp.toPx() }
    val overscrollConnection = remember(profileScrollState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 1. 反向拖：先把已经拽出来的位移按 1:1 收回，收完再让列表正常滚动。
                //    1:1 收回是为了「跟着手指走」，拽出去有阻力、收回来跟手。
                if (overscrollPx > 0f && available.y < 0f) {
                    val next = (overscrollPx + available.y).coerceAtLeast(0f)
                    val consumed = next - overscrollPx
                    overscrollPx = next
                    return Offset(0f, consumed)
                }
                if (overscrollPx < 0f && available.y > 0f) {
                    val next = (overscrollPx + available.y).coerceAtMost(0f)
                    val consumed = next - overscrollPx
                    overscrollPx = next
                    return Offset(0f, consumed)
                }

                // 2. 顶到边界还想继续拖：在列表拿到这份位移【之前】就截走，
                //    按「当前已经拽了多远」算一个连续变化的阻力系数，再记成过滚动量。
                //    放在 preScroll 而不是 postScroll：系统自带的拉伸效果会参与
                //    「列表没吃完的那部分位移」，在这里截走它就拿不到，
                //    避免两种过滚动叠在一起、或者干脆被它吃掉。
                val atTop = profileScrollState.value <= 0
                val atBottom = profileScrollState.value >= profileScrollState.maxValue
                if ((available.y > 0f && atTop) || (available.y < 0f && atBottom)) {
                    // 连续阻尼：位移越大，同样的手指位移换到的内容位移越少
                    val factor = overscrollStartFactor *
                        kotlin.math.exp(-kotlin.math.abs(overscrollPx) / overscrollSoftPx)
                    overscrollPx += available.y * factor
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            // 位移已在 preScroll 全部处理，这里不再重复计算
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = Offset.Zero

            // 松手瞬间掐掉「惯性」这条路：只要身上还挂着过滚动量，就把速度全部吃掉。
            // 否则列表会先空跑一段惯性（几百毫秒），回弹要等它跑完才开始 —— 就是那种
            // 「松手了但没反应、过一会儿才往回弹」的迟钝感。吃掉速度后 performFling 立刻返回，
            // onPostFling 同一帧就开始回弹。
            override suspend fun onPreFling(available: Velocity): Velocity =
                if (overscrollPx != 0f) available else Velocity.Zero

            // 松手：回弹归零。临界阻尼（dampingRatio = 1）不产生二次回弹，
            // 刚度提到 700 让「松手就开始往回走」的反应更快，但不会弹得发冲。
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val released = overscrollPx
                if (released != 0f) {
                    animate(
                        initialValue = released,
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 1f, stiffness = 700f)
                    ) { value, _ -> overscrollPx = value }
                }
                if (released > silentRefreshThresholdPx) {
                    scope.launch {
                        // 只重拉资料，不重启 TCP：连接断了由 TcpService 自身重连循环处理。
                        // 在下拉里 stop/start 会让服务端看到频繁上下线。
                        reloadProfileData()
                    }
                }
                return Velocity.Zero
            }
        }
    }

    // 我的页学微信：整体灰色页底，顶部资料区为纯白大块
    Box(
        Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appCardBg())
            // 往下拽时，露出来的顶部区域补成白色（与资料区同色），
            // 看上去是整页被手拽下来，而不是露出灰底
            .drawBehind {
                if (overscrollPx > 0f) {
                    drawRect(Color.White, size = Size(size.width, overscrollPx))
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(overscrollConnection)
                .offset { IntOffset(0, overscrollPx.roundToInt()) }
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(profileScrollState, flingBehavior = profileFlingBehavior)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // ==================== 顶部资料大块（微信「我」页：纯白整块，灰色页底） ====================
        Column(
            Modifier
                .fillMaxWidth()
                .background(com.aurora.chat.ui.theme.appSurface())
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 46.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 头像（缩小；字体尺寸保持不变）
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFD1D5DB))
                        .clickable { if (avatarBitmap != null) onShowAvatar(avatarBitmap!!) },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap!!.asImageBitmap(),
                            contentDescription = "头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.ic_profile_filled),
                            contentDescription = "默认头像",
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                // 昵称 / ID（不再展示个性签名；ID 保持原有的「ID: 数字」样式）
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = userName.ifEmpty { "未设置昵称" },
                        fontSize = 19.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { editNameText = userName; showNameDialog = true }
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "ID: $userId",
                        fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                // 右侧：上=我的二维码，下=进入编辑资料的右箭头（ID 行水平位置）
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.QrCode2,
                        contentDescription = "我的二维码",
                        tint = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(20.dp).clickable { onShowMyQr() }
                    )
                    Spacer(Modifier.height(12.dp))
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "编辑资料",
                        tint = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(20.dp).clickable { onEditProfile() }
                    )
                }
            }
        }

        // 资料块与下方功能卡片之间的灰色留白
        Spacer(Modifier.height(10.dp))

        // ==================== 功能列表（微信「我」页式：独立白条 + 彩色图标 + 右箭头） ====================
        Spacer(Modifier.height(2.dp))

        // 收藏 → 其他 入口用分割线连成一条，设置单独放在最底部
        // 「个人资料」入口已移除：点击 ID 右侧的箭头即可进入编辑资料，无需重复入口
        ProfileWeChatGroup(
            listOf(
                ProfileWeChatItem(Icons.Filled.Star, Color(0xFFFACC15), "收藏", onFavorites),
                // 「动态」：谁点赞/评论/回复/点赞评论了我（社区 + 短视频），放在收藏下方
                ProfileWeChatItem(Icons.Filled.Notifications, Color(0xFFF59E0B), "动态", onOpenCommunityActivity,
                    showDot = CommunityActivityBadge.unreadCount.value > 0),
                // 「我的发布」：进入独立全屏界面，列表/搜索/排序/发布与社区一致
                ProfileWeChatItem(Icons.Filled.List, Color(0xFF4A90E2), "我的发布", onMyPosts),
                ProfileWeChatItem(Icons.Filled.Language, Color(0xFF4A90E2), "网站托管", onWebsiteHosting),
                // 五子棋/漂流瓶收进二级页，个人页只保留一个入口
                // 小游戏：交给外层打开独立全屏页（与设置/隐私同级），不在个人页内嵌展开
                ProfileWeChatItem(Icons.Filled.SportsEsports, Color(0xFFF59E0B), "小游戏", onClick = onGamesPage),
                // 短视频：交给外层打开独立全屏页（与「小游戏」同级），放在「小游戏」下方、「其他」上方
                ProfileWeChatItem(Icons.Filled.SmartDisplay, Color(0xFFEC4899), "短视频", onClick = onShortVideo),
                // 「其他」：安全防护 / API 接入 / 错误日志 / 兑换卡密 / 关于我们 / 后台中心
                ProfileWeChatItem(Icons.Filled.MoreHoriz, com.aurora.chat.ui.theme.appTextSecondary(), "其他", onMore)
            )
        )
        Spacer(Modifier.height(10.dp))

        // 设置固定放在个人页最底部
        ProfileWeChatGroup(
            listOf(
                ProfileWeChatItem(Icons.Filled.Settings, Color(0xFF4A90E2), "设置", onSettings)
            )
        )
        Spacer(Modifier.height(8.dp))
        }

    }

    // ==================== 在线时间详情（Dialog 全屏） ====================
    if (showTimeDetail) {
        Dialog(
            onDismissRequest = { showTimeDetail = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            OnlineTimeDetailPanel(
                totalSeconds = onlineTimeSec,
                onBack = { showTimeDetail = false }
            )
        }
    }

    // ==================== 在平台详情（Dialog 全屏） ====================
    if (showPlatformDetail) {
        Dialog(
            onDismissRequest = { showPlatformDetail = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            PlatformDetailPanel(
                createdAtSeconds = userCreatedAt,
                onBack = { showPlatformDetail = false }
            )
        }
    }

    }

    // ==================== 弹窗 ====================


    if (showCrop && cropImageUri != null) {
        CropPreviewDialog(
            imageUri = cropImageUri!!, context = context,
            onCropConfirm = { cropped ->
                avatarBitmap = cropped; showCrop = false; cropImageUri = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val file = com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, userId)
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.PNG, 100, out) }
                            // 同步刷新头像内存缓存，否则下次切回「我的」会先闪出上一次的旧头像
                            com.aurora.chat.data.local.AvatarCache.cacheUserAvatar(userId, cropped)
                            val serverOk = ChatRepository.uploadAvatarBitmap(context, cropped)
                            if (!serverOk) { withContext(Dispatchers.Main) { Toast.makeText(context, "头像上传失败，本地已保存", Toast.LENGTH_SHORT).show() } }
                        } catch (t: Throwable) {
                            com.aurora.chat.ErrorReporter.warn(
                                "Avatar", "头像保存/上传失败：${t.javaClass.simpleName}: ${t.message}", t
                            )
                            val msg = if (t is OutOfMemoryError) "内存不足，头像保存失败，请换一张小一点的图片" else "保存失败: ${t.message}"
                            withContext(Dispatchers.Main) { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            },
            onDismiss = { showCrop = false; cropImageUri = null }
        )
    }

    if (showNameDialog) {
        Dialog(onDismissRequest = { showNameDialog = false }) {
            Column(Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp)).padding(20.dp)) {
            Text("修改昵称", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()); Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = editNameText, onValueChange = { editNameText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showNameDialog = false }) { Text("取消") }; Spacer(Modifier.width(8.dp))
                Button(onClick = { userName = editNameText; saveName(editNameText); showNameDialog = false }) { Text("保存") }
            }
            }
        }
    }
    if (showSignatureDialog) {
        Dialog(onDismissRequest = { showSignatureDialog = false }) {
            Column(Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp)).padding(20.dp)) {
            Text("修改签名", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()); Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = editSignatureText, onValueChange = { if (it.length <= 30) editSignatureText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("${editSignatureText.length}/30", fontSize = 12.sp, color = if (editSignatureText.length > 25) Color(0xFFD97706) else com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.fillMaxWidth().padding(end = 4.dp), textAlign = TextAlign.End)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showSignatureDialog = false }) { Text("取消") }; Spacer(Modifier.width(8.dp))
                Button(onClick = { userSignature = editSignatureText; saveSignature(editSignatureText); showSignatureDialog = false }) { Text("保存") }
            }
            }
        }
    }

    // ==================== 头像查看器（全屏放大，可双指缩放） ====================
    // ==================== 活动中心入口 ====================
    // 活动中心改由 MainActivity 统一渲染（showActivity），此处不再重复渲染

    // ==================== 我的二维码 / 扫一扫 / 用户资料 ====================
    // 已提升到 MainActivity 根级渲染（onShowMyQr），彻底解决内部覆盖层被压缩与事件穿透问题
}

// ==================== 「其他」独立入口页（根级全屏渲染，覆盖顶部标题与底部导航） ====================
@Composable
fun MorePage(
    onClose: () -> Unit,
    onAbout: () -> Unit,
    onRedeemCardKey: () -> Unit,
    onOpenErrorLog: () -> Unit = {},
    onOpenSecurityCenter: () -> Unit = {},
    onProvideFreeApi: () -> Unit = {},
    onProvidePaidApi: () -> Unit = {},
    onOpenApplyThirdParty: () -> Unit = {},
    /** 「启动调试」：首次进入软件的启动页预览入口（内容后续接入，这里只提供跳转） */
    onOpenStartupDebug: () -> Unit = {},
    /** 「自定义」：宿主扩展（自定义启动画面 / 悬浮窗）管理入口 */
    onOpenCustomization: () -> Unit = {},
    /** 「后台中心」：独立全屏页入口（内容后续接入，这里只提供跳转） */
    onOpenBackendCenter: () -> Unit = {}
) {
    val appColors = LocalAppColors.current
    var showApiPage by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
    ) {
        // 顶部栏：自带标题与返回，覆盖在应用顶栏之上（使用正确页面配色，不再发蓝）
        // 顶部增加状态栏避让，使标题/返回键位于状态栏下方，避免被时钟遮挡、便于点击
        Box(
            Modifier
                .fillMaxWidth()
                .background(appColors.surface)
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "其他",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            ProfileMenuRow(title = "安全防护", onClick = onOpenSecurityCenter)
            ProfileMenuDivider()
            ProfileMenuRow(title = "API 接入", onClick = { showApiPage = true })
            ProfileMenuDivider()
            ProfileMenuRow(title = "错误日志", onClick = onOpenErrorLog)
            ProfileMenuDivider()
            ProfileMenuRow(title = "兑换卡密", onClick = onRedeemCardKey)
            ProfileMenuDivider()
            ProfileMenuRow(title = "关于我们", onClick = onAbout)
            ProfileMenuDivider()
            // ── 自定义：宿主扩展（启动画面 / 悬浮窗）管理入口 ──
            ProfileMenuRow(title = "自定义", onClick = onOpenCustomization)
            ProfileMenuDivider()
            // ── 启动调试：首次进入软件的启动页入口（调试用，内容后续接入）──
            ProfileMenuRow(title = "启动调试", onClick = onOpenStartupDebug)
            ProfileMenuDivider()
            // ── 后台中心：独立全屏页（内容后续接入，这里只提供跳转）──
            ProfileMenuRow(title = "后台中心", onClick = onOpenBackendCenter)
        }
    }

    // API 接入二级页：从右滑入 + 全屏实底，替代原来的 if 硬切（没有动画、内容贴顶）
    AnimatedVisibility(
        visible = showApiPage,
        enter = slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(200))
    ) {
        Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg())) {
            ProfileSubPage(
                title = "API 接入",
                items = listOf(
                    ProfileSubItem("提供免费 API") { onProvideFreeApi() },
                    ProfileSubItem("提交付费 API") { onProvidePaidApi() },
                    ProfileSubItem("申请第三方接入") { onOpenApplyThirdParty() }
                ),
                onBack = { showApiPage = false }
            )
        }
    }
}

// ==================== 提供免费 API（独立全屏空白页，支持左滑返回） ====================
@Composable
fun ProvideFreeApiScreen(
    onClose: () -> Unit,
) {
    val appColors = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var totalDrag by remember { mutableStateOf(0f) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                    onDragEnd = { if (totalDrag < -80f) onClose() }
                )
            }
    ) {
        // 顶部栏：标题 + 返回（覆盖应用顶栏）
        Box(
            Modifier
                .fillMaxWidth()
                .background(appColors.surface)
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "提供免费API",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // 正文：提供者填写表单
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .imePadding()
        ) {
            Text(
                text = "填写你愿意提供的免费 API 信息，提交后由开发者审核，通过后其他用户即可在「选择免费 API」中使用。",
                fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "提示：你提供的 API 并不会被别人知道（仅开发者可见，普通用户只能选用，看不到地址与 Key）。",
                fontSize = 12.sp, color = Color(0xFF16A34A)
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API Key") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("URL（Base URL，如 https://api.deepseek.com/v1）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = modelName, onValueChange = { modelName = it },
                label = { Text("模型名（可选，如 deepseek-chat）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val key = apiKey.trim()
                    val url = baseUrl.trim()
                    if (key.isBlank() || url.isBlank()) {
                        Toast.makeText(ctx, "API Key 与 Base URL 不能为空", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    // 前端预校验：Base URL 必须以 https:// 开头，否则乱填不予提交
                    if (!url.lowercase().startsWith("https://")) {
                        Toast.makeText(ctx, "Base URL 必须以 https:// 开头", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        submitting = true
                        try {
                            AuroraApi.submitFreeApi(key, url, modelName.trim())
                            Toast.makeText(ctx, "提交成功，等待审核", Toast.LENGTH_SHORT).show()
                            onClose()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message ?: "提交失败", Toast.LENGTH_SHORT).show()
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                if (submitting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("提交", fontSize = 15.sp)
            }
        }
    }
}

// ==================== 提交付费 API ====================
@Composable
fun ProvidePaidApiScreen(
    onClose: () -> Unit,
) {
    val appColors = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var totalDrag by remember { mutableStateOf(0f) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var inputPrice by remember { mutableStateOf("") }
    var outputPrice by remember { mutableStateOf("") }
    var priceNote by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                    onDragEnd = { if (totalDrag < -80f) onClose() }
                )
            }
    ) {
        // 顶部栏
        Box(
            Modifier
                .fillMaxWidth()
                .background(appColors.surface)
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "提交付费API",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        // 正文
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .imePadding()
        ) {
            Text(
                text = "填写你提供的付费 API 信息及收费标准，提交后由开发者审核，通过后其他用户可按你的价格调用。",
                fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API Key") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("Base URL（如 https://api.example.com/v1）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = modelName, onValueChange = { modelName = it },
                label = { Text("模型名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            // 收费标准区
            Text(
                text = "收费标准",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputPrice, onValueChange = { inputPrice = it },
                    label = { Text("输入倍率(每1token扣N余额,如0.2)") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = outputPrice, onValueChange = { outputPrice = it },
                    label = { Text("输出倍率(每1token扣N余额,如0.8)") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = priceNote, onValueChange = { priceNote = it },
                label = { Text("补充说明（可选，如特殊计费方式、套餐等）") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines = 4
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val key = apiKey.trim()
                    val url = baseUrl.trim()
                    val model = modelName.trim()
                    val inPrice = inputPrice.trim()
                    val outPrice = outputPrice.trim()
                    if (key.isBlank() || url.isBlank() || model.isBlank()) {
                        Toast.makeText(ctx, "API Key、Base URL、模型名不能为空", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (!url.lowercase().startsWith("https://")) {
                        Toast.makeText(ctx, "Base URL 必须以 https:// 开头", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (inPrice.isBlank() || outPrice.isBlank()) {
                        Toast.makeText(ctx, "请填写输入和输出价格", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    submitting = true
                    scope.launch {
                        try {
                            AuroraApi.submitPaidApi(key, url, model, inPrice, outPrice, priceNote.trim())
                            Toast.makeText(ctx, "提交成功，等待审核上架", Toast.LENGTH_SHORT).show()
                            onClose()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message ?: "提交失败", Toast.LENGTH_SHORT).show()
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                if (submitting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("提交", fontSize = 15.sp)
            }
        }
    }
}

// 一键测速并发上限：最多同时发 N 个测速请求，任意一个完成立刻补上下一个（滑动窗口）。
// 服务端测速是「服务器代发」，10 个并发对上游贡献者 Key 压力可控。
private const val PING_CONCURRENCY = 10

// ==================== 选择免费 API（空白占位页，实际选用功能后续接入） ====================
@Composable
fun FreeApiSelectScreen(
    currentUserId: Long,
    onBack: () -> Unit,
    onSelected: () -> Unit,
) {
    val appColors = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<FreeApiAvailableItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf("") }
    // 提示色随语义走：成功=绿、失败=红。此前渲染处把颜色写死成红色，
    // 于是「全部测速完成」这种成功结果也显示成警示红，与语义相反。
    var toastColor by remember { mutableStateOf(Color(0xFF16A34A)) }
    // 本地测速结果：id -> 延迟ms（失败记为 -1）
    val latencies = remember { mutableStateMapOf<Long, Long>() }
    var testing by remember { mutableStateOf(setOf<Long>()) }
    // 一键测速：true = 正在逐个对所有 API 测速
    var testingAll by remember { mutableStateOf(false) }
    // 免费 API 当前选中：只认服务端条目 id。
    // 【不能按 url+model 比对】同一个中转站（同 baseUrl）+ 同模型名可以由不同提供者的
    // 不同 Key 各上一条——它们是完全不同的资源，按 url+model 比对会把它们全部误标成「当前选用」。
    val selectedFreeApiId = remember(currentUserId) {
        if (AiChatManager.getMode(ctx, currentUserId) == "free_api") {
            AiChatManager.getFreeApiId(ctx, currentUserId)
        } else 0L
    }

    /**
     * 单个 API 测速（点条目 / 一键测速共用）。
     * showToast=true → 按本条结果弹 toast（点条目场景）；
     * 一键测速传 false，只在全部完成时弹一次总结 toast，避免刷屏。
     * testing / latencies 的写操作统一切回主线程，避免并发改同一 State 丢更新。
     */
    suspend fun testOne(item: FreeApiAvailableItem, showToast: Boolean = true) {
        withContext(Dispatchers.Main.immediate) { testing = testing + item.id }
        try {
            // 测速由服务端代发（贡献者的 Key 不离开服务端，客户端不再直连上游）
            val (ok, ms) = AuroraApi.testFreeApi(item.id)
            withContext(Dispatchers.Main.immediate) {
                latencies[item.id] = if (ok) ms else -1L
                if (showToast) {
                    // 文案保持中性：只说结果与下一步，不把责任推给用户（"你的网络…"这类措辞会让用户觉得被针对）。
                    toast = if (ok) "测速完成：${ms}ms" else "测速失败，请稍后重试或选用其他条目"
                    toastColor = if (ok) Color(0xFF16A34A) else Color(0xFFDC2626)
                }
            }
            try {
                // 上报测速结果，服务端据此累计失败次数、连续 3 次判定「已挂」
                AuroraApi.pingFreeApi(item.id, ok, if (ok) ms else 0)
            } catch (e: Exception) {
                // 上报失败不影响本地展示
            }
        } finally {
            withContext(Dispatchers.Main.immediate) { testing = testing - item.id }
        }
    }

    // 点条目单独测速
    fun doPing(item: FreeApiAvailableItem) {
        scope.launch { testOne(item, showToast = true) }
    }

    /**
     * 一键测速：并发测全部，信号量把并发数限制在 [PING_CONCURRENCY] 个。
     * 采用「滑动窗口」：最多同时跑 N 个，任意一个完成立刻补上下一个，
     * 比「整批等完再整批」更快、进度更连续。
     */
    fun doPingAll() {
        if (items.isEmpty()) return
        testingAll = true
        scope.launch {
            val sem = Semaphore(PING_CONCURRENCY)
            items.map { item ->
                async(Dispatchers.IO) {
                    sem.withPermit { testOne(item, showToast = false) }
                }
            }.awaitAll()
            testingAll = false
            withContext(Dispatchers.Main.immediate) {
                toast = "全部测速完成"
                toastColor = Color(0xFF16A34A)
            }
        }
    }

    fun load() {
        scope.launch {
            loading = true
            val res = AuroraApi.listAvailableFreeApis()
            if (res.success) {
                val arr = res.data?.optJSONArray("items")
                items = if (arr != null && arr.length() > 0) {
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        FreeApiAvailableItem(
                            id = o.optLong("id"),
                            baseUrl = o.optString("base_url"),
                            model = o.optString("model_name"),
                            serverLatency = o.optLong("last_latency"),
                            markedDown = o.optInt("marked_down") == 1,
                            // 缺字段时兜底 -1（未探测），不能用 optInt 的默认 0 —— 那会被误读成「服务端不可达」
                            srvOk = if (o.has("srv_ok")) o.optInt("srv_ok") else -1,
                            srvLatency = o.optLong("srv_latency"),
                            srvError = o.optString("srv_error"),
                            submitterId = o.optLong("submitter_id"),
                            submitterName = o.optString("submitter_name")
                        )
                    }
                } else emptyList()
            } else {
                toast = res.message
                toastColor = Color(0xFFDC2626)
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    // 排序：已测速的排前面、按延迟升序；未测速的排后面（最快的在最上）
    val sorted = items.sortedWith(
        compareBy({ if (it.id in latencies) 0 else 1 }, { latencies[it.id] ?: Long.MAX_VALUE })
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "选择免费 API",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { doPingAll() },
                enabled = !testingAll && items.isNotEmpty()
            ) {
                Text(
                    if (testingAll) "测速中…" else "一键测速",
                    fontSize = 14.sp,
                    color = if (testingAll) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF1E40AF)
                )
            }
        }
        if (toast.isNotEmpty()) {
            // 颜色随语义（toastColor）：成功=绿、失败=红，不再一律红字
            Text(toast, fontSize = 13.sp, color = toastColor, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF1E40AF))
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无可用免费 API，敬请期待（由用户贡献、开发者审核后在此出现）",
                    fontSize = 14.sp,
                    color = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text("以下免费 API 由用户贡献、开发者审核通过。点击条目选用；点右上角「一键测速」可全部测速并按响应速度排序：", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(12.dp))
                sorted.forEach { item ->
                    val isSel = selectedFreeApiId > 0L && item.id == selectedFreeApiId
                    val isTesting = item.id in testing
                    val lat = latencies[item.id]
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable {
                                scope.launch {
                                    AiChatManager.saveFreeApiConfig(ctx, currentUserId, item.id, item.baseUrl, item.model)
                                    android.widget.Toast.makeText(ctx, "已选用该免费 API", android.widget.Toast.LENGTH_SHORT).show()
                                    onSelected()
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = if (isSel) com.aurora.chat.ui.theme.appSelectedBg() else com.aurora.chat.ui.theme.appCardBg()),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            // 每条顶部：模型名 + 测速中状态（测速统一走右上角「一键测速」）
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.model.ifBlank { "(默认模型)" },
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1E40AF), modifier = Modifier.weight(1f)
                                )
                                if (isTesting) {
                                    Text("测速中…", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), fontWeight = FontWeight.Medium)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // 提供者信息（不暴露 URL / Key，避免被白嫖）
                            Text(
                                "由 ID 为 ${item.submitterId}、用户名为 ${item.submitterName.ifBlank { "未知" }} 的用户提供",
                                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                            )
                            Spacer(Modifier.height(4.dp))
                            // ── 状态行①：服务端视角（客观事实源）──
                            // 「这条 API 对公网是否还活着」由平台服务器探测裁决，与任何单个用户无关。
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                when (item.srvOk) {
                                    1 -> Text(
                                        if (item.srvLatency > 0) "服务端可达 ${item.srvLatency}ms" else "服务端可达",
                                        fontSize = 11.sp, color = Color(0xFF16A34A)
                                    )
                                    0 -> Text(
                                        if (item.markedDown) "服务端不可达·已挂" else "服务端不可达",
                                        fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium
                                    )
                                    else -> Text("服务端尚未探测", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                                Spacer(Modifier.weight(1f))
                                if (isSel) {
                                    Text("当前选用", fontSize = 11.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Medium)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            // ── 状态行②：本机视角 ──
                            // 与上面一行并列显示，才能一眼判断"问题出在谁"：
                            //   服务端可达 + 我连不上  → 该 API 限制了调用方，或你的网络到不了（换网/换代理可试）
                            //   服务端不可达 + 我连不上 → 这条 API 确实失效了
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (lat != null) {
                                    Text(
                                        if (lat >= 0) "我的网络 ${lat}ms" else "我的网络 连不上",
                                        fontSize = 11.sp,
                                        color = if (lat >= 0) com.aurora.chat.ui.theme.appTextSecondary() else Color(0xFFDC2626)
                                    )
                                } else {
                                    Text("我的网络 未测速", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class FreeApiAvailableItem(
    val id: Long,
    val baseUrl: String,
    val model: String,
    val serverLatency: Long = 0,
    val markedDown: Boolean = false,
    /** 服务端可达性（服务端探测得出的**客观**结论）：1 可达 / 0 不可达 / -1 尚未探测 */
    val srvOk: Int = -1,
    /** 服务端实测往返毫秒 */
    val srvLatency: Long = 0,
    /** 服务端不可达的原因（HTTP 码 / 超时 / DNS），可达时为空 */
    val srvError: String = "",
    val submitterId: Long = 0,
    val submitterName: String = ""
)

// ==================== 选择付费 API ====================
@Composable
fun PaidApiSelectScreen(
    currentUserId: Long,
    onBack: () -> Unit,
    onSelected: () -> Unit,
) {
    val appColors = LocalAppColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<PaidApiAvailableItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf("") }
    // 付费 API 当前选中：优先按持久化的 paid_api_id 精确匹配；旧版本无 id 时退回 baseUrl+model 双匹配
    val selectedPaid = remember(currentUserId) {
        if (AiChatManager.getMode(ctx, currentUserId) == "paid_api") {
            Triple(AiChatManager.getPaidApiId(ctx, currentUserId), AiChatManager.getPaidApiConfig(ctx, currentUserId).second, AiChatManager.getPaidApiConfig(ctx, currentUserId).third)
        } else Triple(0L, "", "")
    }

    fun load() {
        scope.launch {
            loading = true
            val res = AuroraApi.listAvailablePaidApis()
            if (res.success) {
                val arr = res.data?.optJSONArray("items")
                items = if (arr != null && arr.length() > 0) {
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        PaidApiAvailableItem(
                            id = o.optLong("id"),
                            baseUrl = o.optString("base_url"),
                            model = o.optString("model_name"),
                            inputPrice = o.optString("input_price"),
                            outputPrice = o.optString("output_price"),
                            priceNote = o.optString("price_note"),
                            submitterId = o.optLong("submitter_id"),
                            submitterName = o.optString("submitter_name")
                        )
                    }
                } else emptyList()
            } else {
                toast = res.message
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "选择付费 API",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (toast.isNotEmpty()) {
            Text(toast, fontSize = 13.sp, color = Color(0xFFDC2626), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF1E40AF))
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无可用付费 API",
                    fontSize = 14.sp,
                    color = com.aurora.chat.ui.theme.appTextHint(),
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text("选择下方任一付费 API，消耗你的 Token 余额使用。", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(12.dp))
                items.forEach { item ->
                    val isSel = item.id == selectedPaid.first || (selectedPaid.first == 0L && item.baseUrl == selectedPaid.second && item.model == selectedPaid.third)
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable {
                                AiChatManager.savePaidApiConfig(ctx, currentUserId, item.id, item.baseUrl, item.model)
                                AiChatManager.savePaidApiPrice(ctx, currentUserId, item.inputPrice.toString().toDoubleOrNull() ?: 0.0, item.outputPrice.toString().toDoubleOrNull() ?: 0.0)
                                Toast.makeText(ctx, "已选用该付费 API", Toast.LENGTH_SHORT).show()
                                onSelected()
                            },
                        colors = CardDefaults.cardColors(containerColor = if (isSel) com.aurora.chat.ui.theme.appSelectedBg() else com.aurora.chat.ui.theme.appCardBg()),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.model.ifBlank { "(默认模型)" },
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1E40AF), modifier = Modifier.weight(1f)
                                )
                                if (isSel) {
                                    Text("当前选用", fontSize = 12.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Medium)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            // 收费标准
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "输入倍率 ${item.inputPrice}",
                                    fontSize = 12.sp, color = Color(0xFFB45309), fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "输出倍率 ${item.outputPrice}",
                                    fontSize = 12.sp, color = Color(0xFFB45309), fontWeight = FontWeight.Medium
                                )
                            }
                            if (item.priceNote.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    item.priceNote,
                                    fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class PaidApiAvailableItem(
    val id: Long,
    val baseUrl: String,
    val model: String,
    val inputPrice: String,
    val outputPrice: String,
    val priceNote: String,
    val submitterId: Long = 0,
    val submitterName: String = ""
)

// ==================== 头像放大查看器（根级全屏；只有图片本身缩放入场，黑底遮罩延迟淡入，不跟随图片缩放） ====================
@Composable
fun AvatarViewerDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
) {
    var viewerScale by remember { mutableStateOf(1f) }
    var viewerOffset by remember { mutableStateOf(Offset.Zero) }
    // 入场动画拆成两层:图片从 0.4 缩放到 1;黑底遮罩延迟到图片基本就位后再淡入
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val enterScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (entered) 1f else 0.4f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "avatarEnterScale"
    )
    val scrimAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(220, delayMillis = 180),
        label = "avatarScrim"
    )
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
    ) {
        // 黑底遮罩:独立一层,延迟淡入,绝不参与图片的缩放动画
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
        )
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = enterScale * viewerScale
                    scaleY = enterScale * viewerScale
                    translationX = viewerOffset.x
                    translationY = viewerOffset.y
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        viewerScale = (viewerScale * zoom).coerceIn(1f, 5f)
                        viewerOffset = Offset(viewerOffset.x + pan.x, viewerOffset.y + pan.y)
                    }
                }
        )
        // 关闭按钮(随遮罩一起出现,避免黑底未显时白字悬空)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        ) {
            Text("✕", color = Color.White, fontSize = 24.sp)
        }
    }
}

// ==================== 胶囊体内单项统计 ====================

@Composable
private fun StatItem(value: String, label: String, onClick: (() -> Unit)? = null) {
    val intSrc = remember { MutableInteractionSource() }
    val mod = if (onClick != null) Modifier.clickable(interactionSource = intSrc, indication = null) { onClick() } else Modifier
    Column(
        modifier = mod,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E40AF)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = com.aurora.chat.ui.theme.appTextSecondary()
        )
    }
}

// ===================== 裁剪预览弹窗 =====================

/** 裁剪预览解码上限（像素）：够清晰，又不至于把大图整张塞进内存 */
private const val CROP_DECODE_MAX_PX = 1600

@Composable
fun CropPreviewDialog(
    imageUri: android.net.Uri, context: Context,
    onCropConfirm: (Bitmap) -> Unit, onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    CropPreviewDialogContent(imageUri = imageUri, context = context, scope = scope, onCropConfirm = onCropConfirm, onDismiss = onDismiss)
}

@Composable
private fun CropPreviewDialogContent(
    imageUri: android.net.Uri, context: Context, scope: kotlinx.coroutines.CoroutineScope,
    onCropConfirm: (Bitmap) -> Unit, onDismiss: () -> Unit
) {
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember { mutableStateOf(false) }
    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            try {
                // 两段式采样解码：先读原图尺寸，再按采样率加载。
                // 原图整张进内存（一张 4000x3000 照片≈48MB）在低内存机型上会抛 OutOfMemoryError，
                // 而它不是 Exception，会漏过下面的 catch 一路逃到主线程，被 UiCrashGuard 兜成
                // 「刚才的操作触发异常」——这就是头像上传偶发失败、过一会儿又正常的来源。
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(imageUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    decodeFailed = true
                    return@withContext
                }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > CROP_DECODE_MAX_PX) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = context.contentResolver.openInputStream(imageUri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                if (bmp == null) decodeFailed = true else sourceBitmap = bmp
            } catch (t: Throwable) {
                decodeFailed = true
                com.aurora.chat.ErrorReporter.warn(
                    "Avatar", "裁剪预览解码失败：${t.javaClass.simpleName}: ${t.message}", t
                )
            }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var screenW by remember { mutableFloatStateOf(1080f) }
    var screenH by remember { mutableFloatStateOf(2100f) }
    var initialized by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            sourceBitmap?.let { bmp ->
                val fitScale = minOf(screenW / bmp.width.toFloat(), screenH / bmp.height.toFloat())
                val imgScrW = bmp.width.toFloat() * fitScale
                val imgScrH = bmp.height.toFloat() * fitScale
                val cropSize = minOf(screenW, screenH) * 0.7f
                val cropL = (screenW - cropSize) / 2f
                val cropT = (screenH - cropSize) / 2f
                if (!initialized) {
                    scale = minOf(screenW / imgScrW, screenH / imgScrH) * 0.85f
                    offsetX = 0f; offsetY = 0f; initialized = true
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { screenW = it.width.toFloat(); screenH = it.height.toFloat() }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.3f, 5f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "裁剪",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offsetX; translationY = offsetY
                            },
                        contentScale = ContentScale.Fit,
                        alpha = 0.6f
                    )
                }

                // 裁剪框遮罩
                Canvas(Modifier.fillMaxSize()) {
                    val cr = 24.dp.toPx()
                    val outer = Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
                    val inner = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        androidx.compose.ui.geometry.Rect(cropL, cropT, cropL + cropSize, cropT + cropSize),
                        androidx.compose.ui.geometry.CornerRadius(cr)
                    )) }
                    val mask = Path().apply { op(outer, inner, PathOperation.Difference) }
                    drawPath(mask, Color.Black.copy(alpha = 0.55f))
                    drawRoundRect(
                        Color.White,
                        androidx.compose.ui.geometry.Offset(cropL, cropT),
                        androidx.compose.ui.geometry.Size(cropSize, cropSize),
                        androidx.compose.ui.geometry.CornerRadius(cr),
                        style = Stroke(2.dp.toPx())
                    )
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = {
                            val cx = screenW / 2f + offsetX; val cy = screenH / 2f + offsetY
                            val iL = cx - imgScrW * scale / 2f; val iT = cy - imgScrH * scale / 2f
                            val cropRelX = (cropL - iL) / (imgScrW * scale)
                            val cropRelY = (cropT - iT) / (imgScrH * scale)
                            val bx = (cropRelX * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                            val by = (cropRelY * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                            val bSz = ((cropSize / (imgScrW * scale)) * bmp.width).toInt().coerceIn(1, minOf(bmp.width - bx, bmp.height - by))
                            val cropped = try {
                                Bitmap.createBitmap(bmp, bx, by, bSz, bSz)
                            } catch (t: Throwable) {
                                com.aurora.chat.ErrorReporter.warn(
                                    "Avatar", "裁剪失败：${t.javaClass.simpleName}: ${t.message}", t
                                )
                                null
                            }
                            if (cropped != null) onCropConfirm(cropped) else onCropConfirm(bmp)
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                    ) {
                        Text("确认裁剪", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                }
            } ?: run {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (decodeFailed) "图片读取失败，请重新选择" else "加载图片中...",
                        color = Color.White, fontSize = 16.sp
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                ) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextHint())
                }
            }
        }
    }
}

fun createTempImageUri(context: Context): android.net.Uri {
    val dir = File(context.cacheDir, "camera_photos"); if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

// ==================== 在线时间详情面板 ====================

@Composable
private fun OnlineTimeDetailPanel(totalSeconds: Long, onBack: () -> Unit) {
    val entries = buildTimeEntries(totalSeconds)

    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        // 事件拦截：防止点击穿透
        EventBlocker()

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    fontSize = 24.sp,
                    color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "在线时间",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.aurora.chat.ui.theme.appTextPrimary()
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(48.dp))
            }

            Spacer(Modifier.height(16.dp))

            // 各时间单位行
            entries.forEach { (displayText, fontSize) ->
                Text(
                    text = displayText,
                    fontSize = fontSize,
                    fontWeight = if (fontSize >= 24.sp) FontWeight.Bold else FontWeight.Medium,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
    }
}

// ==================== 时间条目生成 ====================

private data class TimeEntry(val value: String, val unit: String)

private fun buildTimeEntries(totalSeconds: Long): List<Pair<String, androidx.compose.ui.unit.TextUnit>> {
    val s = totalSeconds.toDouble()
    val m = s / 60.0
    val h = s / 3600.0
    val d = s / 86400.0
    val mo = s / (86400.0 * 30.4375)
    val y = s / (86400.0 * 365.25)

    return listOf(
        "${fmt(s)} 秒" to 26.sp,
        "${fmt(m)} 分钟" to 22.sp,
        "${fmt(h)} 小时" to 18.sp,
        "${fmt(d)} 天" to 16.sp,
        "${fmt(mo)} 月" to 14.sp,
        "${fmt(y)} 年" to 13.sp,
    )
}

private fun fmt(value: Double): String {
    if (value == 0.0) return "0"
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return String.format("%.10f", value).trimEnd('0').trimEnd('.')
}

// ==================== 在平台详情面板 ====================

@Composable
private fun PlatformDetailPanel(createdAtSeconds: Long, onBack: () -> Unit) {
    val regDate = remember(createdAtSeconds) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = createdAtSeconds * 1000 }
        "${cal.get(java.util.Calendar.YEAR)}年${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日" +
            "${cal.get(java.util.Calendar.HOUR_OF_DAY)}时${cal.get(java.util.Calendar.MINUTE)}分${cal.get(java.util.Calendar.SECOND)}秒"
    }

    // 每秒刷新的当前时间
    var currentTimeStr by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val cal = java.util.Calendar.getInstance()
            currentTimeStr = "${cal.get(java.util.Calendar.YEAR)}年${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日" +
                "${cal.get(java.util.Calendar.HOUR_OF_DAY)}时${cal.get(java.util.Calendar.MINUTE)}分${cal.get(java.util.Calendar.SECOND)}秒"
        }
    }

    // 每秒刷新的"已在平台"累积时长（精确到秒）
    var sinceRegStr by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val diff = System.currentTimeMillis() / 1000 - createdAtSeconds
            val y = diff / 31536000
            val mo = (diff % 31536000) / 2592000
            val d = (diff % 2592000) / 86400
            val h = (diff % 86400) / 3600
            val mi = (diff % 3600) / 60
            val s = diff % 60
            sinceRegStr = "${y}年${mo}个月${d}日${h}时${mi}分${s}秒"
        }
    }

    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        EventBlocker()
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", fontSize = 24.sp, color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onBack() })
                Spacer(Modifier.weight(1f))
                Text("在平台", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(48.dp))
            }

            Spacer(Modifier.height(24.dp))

            Text("注册时间", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(6.dp))
            Text(regDate, fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(14.dp))
            Text("注册了此账号", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())

            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(1.dp).background(com.aurora.chat.ui.theme.appDivider()))
            Spacer(Modifier.height(28.dp))

            Text("距现在", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(6.dp))
            Text(currentTimeStr, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))

            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(1.dp).background(com.aurora.chat.ui.theme.appDivider()))
            Spacer(Modifier.height(28.dp))

            Text("已在平台", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(6.dp))
            Text(sinceRegStr, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
        }
    }
}
