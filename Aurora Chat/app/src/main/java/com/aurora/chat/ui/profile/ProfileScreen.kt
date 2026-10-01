package com.aurora.chat.ui.profile

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.aurora.chat.ui.components.decodeSampledFile
import android.widget.Toast
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
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import com.aurora.chat.ui.components.SecuritySettingsContent
import com.aurora.chat.ui.theme.LocalAppColors
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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


/** 功能列表的一行：仅左侧标题，整行可点（非卡片样式，不带箭头）。 */
@Composable
private fun ProfileMenuRow(
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable { onClick() }
            // clickable 在前、padding 在后：点击涟漪贯穿全宽，仅文字有内边距
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            color = Color(0xFF1F2937),
            modifier = Modifier.weight(1f)
        )
    }
}

/** 行间细分隔线：贯穿整屏左右，最后一行之后不放。 */
@Composable
private fun ProfileMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFF0F1F3))
    )
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
    onPrivacySettings: () -> Unit = {},
    onWebsiteHosting: () -> Unit = {},
    onMore: () -> Unit = {},
    onShowAvatar: (Bitmap) -> Unit = {},
    onBottle: () -> Unit = {},
    onSettings: () -> Unit = {},
    onAbout: () -> Unit = {},
    onRedeemCardKey: () -> Unit = {},
    onMessagePush: () -> Unit = {},
    onMyPosts: () -> Unit = {},
    onSaveLocalData: () -> Unit = {},
    onRestoreLocalData: () -> Unit = {},
    onPullMessages: () -> Unit = {},
    onClearChatRecords: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cropImageUri by remember { mutableStateOf<Uri?>(null) }
    var showCrop by remember { mutableStateOf(false) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    var userName by remember { mutableStateOf("") }
    var userSignature by remember { mutableStateOf("") }
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
    var isRefreshing by remember { mutableStateOf(false) }
    var userEmailVerified by remember { mutableStateOf(com.aurora.chat.data.api.AuroraApi.currentUserEmailVerified) }

    // userId 变化时重新拉取服务器资料 + 头像 + 在线时间基线
    LaunchedEffect(userId) {
        withContext(Dispatchers.IO) {
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
            val avatarFile = com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, userId)
            if (avatarFile.exists()) {
                avatarBitmap = decodeSampledFile(avatarFile.absolutePath)
            } else {
                try {
                    val bmp = ChatRepository.loadAvatar(context, userId)
                    if (bmp != null) avatarBitmap = bmp
                } catch (_: Exception) {}
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
                            AuroraApi.syncUserStats(ot, 0)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun saveName(name: String) {
        scope.launch {
            try {
                val result = AuroraApi.updateProfile(name, userSignature)
                if (result.success) {
                    com.aurora.chat.data.local.LocalStorage.saveUserProfile(context, userId, name, userSignature)
                    userName = name
                } else {
                    Toast.makeText(context, "昵称保存失败: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "昵称保存失败: ${e.localizedMessage ?: "网络错误"}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun saveSignature(sig: String) {
        scope.launch {
            try {
                val result = AuroraApi.updateSignature(sig)
                if (result.success) {
                    com.aurora.chat.data.local.LocalStorage.saveUserProfile(context, userId, userName, sig)
                    userSignature = sig
                } else {
                    Toast.makeText(context, "签名保存失败: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "签名保存失败: ${e.localizedMessage ?: "网络错误"}", Toast.LENGTH_SHORT).show()
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

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isRefreshing) {
                    scope.launch {
                        isRefreshing = true
                        com.aurora.chat.KeepAliveManager.tcpReconnectIfDisconnected(context)
                        reloadProfileData()
                        isRefreshing = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // ==================== 头像 + 昵称/ID/签名 ====================
        Row(
            modifier = Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(12.dp))
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
                        painter = painterResource(R.drawable.ic_profile),
                        contentDescription = "默认头像",
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = userName.ifEmpty { "未设置昵称" },
                            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937),
                            modifier = Modifier.clickable { editNameText = userName; showNameDialog = true }
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "ID: $userId", fontSize = 13.sp, color = Color(0xFF9CA3AF))
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFEFF4FF))
                                .clickable { onShowMyQr() }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "二维码",
                                fontSize = 12.sp,
                                color = Color(0xFF2563EB),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = userSignature.ifEmpty { "这个人很懒，什么都没写…" },
                        fontSize = 14.sp, color = Color(0xFF6B7280),
                        modifier = Modifier.clickable { editSignatureText = userSignature; showSignatureDialog = true }
                    )
                }
            }
        }

        // 头像/昵称/签名 与下方功能列表之间的分割线
        Spacer(Modifier.height(14.dp))
        ProfileMenuDivider()

        // ==================== 功能列表（一行一条，非卡片） ====================
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            ProfileMenuRow(title = "编辑资料", onClick = onEditProfile)
            ProfileMenuDivider()
            ProfileMenuRow(title = "隐私设置", onClick = onPrivacySettings)
            ProfileMenuDivider()
            ProfileMenuRow(title = "网站托管", onClick = onWebsiteHosting)
            ProfileMenuDivider()
            // 「我的发布」：进入独立全屏界面，列表/搜索/排序/发布与社区一致
            ProfileMenuRow(title = "我的发布", onClick = onMyPosts)
            ProfileMenuDivider()
            ProfileMenuRow(title = "漂流瓶", onClick = onBottle)
            ProfileMenuDivider()
            // 「其他」：点进独立页面（含安全/关于我们/兑换卡密/消息推送/本地备份等）
            ProfileMenuRow(title = "其他", onClick = onMore)
            ProfileMenuDivider()

            ProfileMenuRow(title = "设置", onClick = onSettings)
        }
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
                            val serverOk = ChatRepository.uploadAvatarBitmap(context, cropped)
                            if (!serverOk) { withContext(Dispatchers.Main) { Toast.makeText(context, "头像上传失败，本地已保存", Toast.LENGTH_SHORT).show() } }
                        } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show() } }
                    }
                }
            },
            onDismiss = { showCrop = false; cropImageUri = null }
        )
    }

    if (showNameDialog) {
        Dialog(onDismissRequest = { showNameDialog = false }) {
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(24.dp)) {
                Text("修改昵称", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)); Spacer(Modifier.height(16.dp))
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
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(24.dp)) {
                Text("修改签名", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)); Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = editSignatureText, onValueChange = { if (it.length <= 30) editSignatureText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("${editSignatureText.length}/30", fontSize = 12.sp, color = if (editSignatureText.length > 25) Color(0xFFD97706) else Color(0xFF9CA3AF), modifier = Modifier.fillMaxWidth().padding(end = 4.dp), textAlign = TextAlign.End)
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
    currentEmail: String,
    onClose: () -> Unit,
    onAbout: () -> Unit,
    onRedeemCardKey: () -> Unit,
    onMessagePush: () -> Unit,
    onSaveLocalData: () -> Unit,
    onRestoreLocalData: () -> Unit,
    onPullMessages: () -> Unit,
    onClearChatRecords: () -> Unit,
    onProvideFreeApi: () -> Unit = {},
    onOpenApplyThirdParty: () -> Unit = {},
) {
    val appColors = LocalAppColors.current
    // ── 卸载程序：弹窗状态 + 无障碍授权回跳检测 ──
    val ctx = LocalContext.current
    val uninstallScope = rememberCoroutineScope()
    var showUninstallDialog by remember { mutableStateOf(false) }
    // 已跳转系统无障碍设置、等待用户返回
    var uninstallWaitingGrant by remember { mutableStateOf(false) }
    // 从设置页返回且确认已授权 → 需要「二次确认」
    var uninstallSecondConfirm by remember { mutableStateOf(false) }
    var uninstallBusy by remember { mutableStateOf(false) }
    val uninstallLifecycle = LocalLifecycleOwner.current

    // 用户去系统设置授权后回到本页时，Activity 会走 ON_RESUME：
    // 此时再查一次无障碍状态——已开启就给「二次确认」，没开启则如实提示。
    DisposableEffect(uninstallLifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && uninstallWaitingGrant) {
                uninstallWaitingGrant = false
                if (com.aurora.chat.PhoneControl.isReady()) {
                    uninstallSecondConfirm = true
                } else {
                    android.widget.Toast.makeText(
                        ctx, "仍未开启无障碍权限，无法自动卸载", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                showUninstallDialog = true
            }
        }
        uninstallLifecycle.lifecycle.addObserver(obs)
        onDispose { uninstallLifecycle.lifecycle.removeObserver(obs) }
    }
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
            // ── 安全防护（主开关 + 密码/指纹/手势 + 多重验证），直接内联，不再侧边栏 ──
            SecuritySettingsContent(currentEmail = currentEmail)
            ProfileMenuDivider()
            ProfileMenuRow(title = "关于我们", onClick = onAbout)
            ProfileMenuDivider()
            ProfileMenuRow(title = "兑换卡密", onClick = onRedeemCardKey)
            ProfileMenuDivider()
            ProfileMenuRow(title = "消息推送", onClick = onMessagePush)
            ProfileMenuDivider()
            ProfileMenuRow(title = "本地保存数据", onClick = onSaveLocalData)
            ProfileMenuDivider()
            ProfileMenuRow(title = "从本地数据覆盖回应用", onClick = onRestoreLocalData)
            ProfileMenuDivider()
            ProfileMenuRow(title = "拉取未拉取的消息", onClick = onPullMessages)
            ProfileMenuDivider()
            ProfileMenuRow(title = "清空所有聊天记录", onClick = onClearChatRecords)
            ProfileMenuDivider()
            ProfileMenuRow(title = "提供免费API", onClick = onProvideFreeApi)
            ProfileMenuDivider()
            ProfileMenuRow(title = "申请第三方接入", onClick = onOpenApplyThirdParty)
            ProfileMenuDivider()
            // ── 卸载程序：纯本地编排，不经过 AI；点「确定」后走无障碍自动卸载 ──
            ProfileMenuRow(title = "卸载程序") {
                // 每次重新打开都从干净状态开始（除非正处于「授权返回待二次确认」）
                if (!uninstallSecondConfirm) uninstallWaitingGrant = false
                showUninstallDialog = true
            }
            ProfileMenuDivider()
        }
    }

    if (showUninstallDialog) {
        UninstallAppDialog(
            granted = com.aurora.chat.PhoneControl.isReady(),
            secondConfirm = uninstallSecondConfirm,
            busy = uninstallBusy,
            onCancel = {
                if (!uninstallBusy) {
                    showUninstallDialog = false
                    uninstallSecondConfirm = false
                    uninstallWaitingGrant = false
                }
            },
            onConfirm = {
                if (!uninstallBusy) {
                    val ready = com.aurora.chat.PhoneControl.isReady()
                    if (uninstallSecondConfirm || ready) {
                        // 已授权（含二次确认）→ 直接开始无障碍自动卸载
                        uninstallBusy = true
                        val label = try {
                            val pm = ctx.packageManager
                            pm.getApplicationLabel(pm.getApplicationInfo(ctx.packageName, 0)).toString()
                        } catch (_: Exception) { "Aurora Chat" }
                        uninstallScope.launch {
                            val err = com.aurora.chat.PhoneControl.uninstallSelfByAccessibility(ctx, label)
                            uninstallBusy = false
                            if (err != null) {
                                // 失败原因（如系统拦截确认框）如实告诉用户，绝不假装成功
                                android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_LONG).show()
                            }
                            // err == null：卸载已触发，进程随后被系统结束
                        }
                    } else {
                        // 未授权 → 先跳系统无障碍设置；回来后由 ON_RESUME 触发二次确认
                        uninstallWaitingGrant = true
                        com.aurora.chat.PhoneControl.openSettings(ctx)
                    }
                }
            }
        )
    }
}

// ==================== 卸载程序确认弹窗 ====================

/**
 * 卸载本应用的确认弹窗。纯本地逻辑，不经过 AI。
 *
 * 三种形态（由外部状态驱动）：
 * 1. 未授权无障碍：说明将先前往系统设置授权，点「确定」→ 跳转授权页；
 * 2. 已授权无障碍：说明点「确定」将立即卸载，点「确定」→ 直接开始卸载；
 * 3. 授权返回后的「二次确认」：标题与正文换成再次确认文案，点「确定」→ 直接开始卸载。
 *
 * 卸载由 [com.aurora.chat.PhoneControl.uninstallSelfByAccessibility] 完成（回桌面 → 长按图标
 * → 卸载 → 确定）；若系统拦截确认框，会返回原因由调用方如实提示，不谎报成功。
 */
@Composable
private fun UninstallAppDialog(
    granted: Boolean,
    secondConfirm: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onCancel() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (secondConfirm) "你已授权无障碍" else "卸载程序",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        busy -> "正在卸载，请稍候…"
                        secondConfirm -> "你已授权无障碍，请再次确认。\n点击「确定」将立即卸载 Aurora Chat。"
                        granted -> "卸载后，聊天记录、本地文件、登录状态将全部清除，且无法恢复。\n\n已获得无障碍权限，点击「确定」将立即卸载。"
                        else -> "卸载后，聊天记录、本地文件、登录状态将全部清除，且无法恢复。\n\n自动卸载需要无障碍权限。点击「确定」后将先前往系统设置开启无障碍；授权返回后还需再次确认。"
                    },
                    fontSize = 13.sp,
                    color = Color(0xFF6B7280),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onCancel,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消", color = Color(0xFF6B7280), fontSize = 15.sp)
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text("确定", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
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
                fontSize = 13.sp, color = Color(0xFF6B7280)
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
    // 本地测速结果：id -> 延迟ms（失败记为 -1）
    val latencies = remember { mutableStateMapOf<Long, Long>() }
    var testing by remember { mutableStateOf(setOf<Long>()) }
    // 一键测速：true = 正在逐个对所有 API 测速
    var testingAll by remember { mutableStateOf(false) }
    val selectedUrl = remember(currentUserId) {
        if (AiChatManager.getMode(ctx, currentUserId) == "free_api") {
            AiChatManager.getFreeApiConfig(ctx, currentUserId).second
        } else ""
    }

    fun doPing(item: FreeApiAvailableItem) {
        scope.launch {
            testing = testing + item.id
            val (ok, ms) = AuroraApi.measureFreeApiLatency(item.apiKey, item.baseUrl, item.model)
            latencies[item.id] = if (ok) ms else -1L
            try {
                // 上报测速结果，服务端据此累计失败次数、连续 3 次判定「已挂」
                AuroraApi.pingFreeApi(item.id, ok, if (ok) ms else 0)
            } catch (e: Exception) {
                // 上报失败不影响本地展示
            }
            testing = testing - item.id
            toast = if (ok) "测速完成：${ms}ms" else "测速失败（服务器可能不可用）"
        }
    }

    /** 一键测速：对所有已上架 API 逐个测速并就地更新延迟。 */
    fun doPingAll() {
        if (items.isEmpty()) return
        testingAll = true
        scope.launch {
            for (item in items) {
                testing = testing + item.id
                val (ok, ms) = AuroraApi.measureFreeApiLatency(item.apiKey, item.baseUrl, item.model)
                latencies[item.id] = if (ok) ms else -1L
                try {
                    AuroraApi.pingFreeApi(item.id, ok, if (ok) ms else 0)
                } catch (e: Exception) { }
                testing = testing - item.id
            }
            testingAll = false
            toast = "全部测速完成"
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
                            apiKey = o.optString("api_key"),
                            baseUrl = o.optString("base_url"),
                            model = o.optString("model_name"),
                            serverLatency = o.optLong("last_latency"),
                            markedDown = o.optInt("marked_down") == 1,
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
                    color = if (testingAll) Color(0xFF9CA3AF) else Color(0xFF1E40AF)
                )
            }
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
                    "暂无可用免费 API，敬请期待（由用户贡献、开发者审核后在此出现）",
                    fontSize = 14.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Text("以下免费 API 由用户贡献、开发者审核通过。点击条目选用；点右上角「一键测速」可全部测速并按响应速度排序：", fontSize = 13.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(12.dp))
                sorted.forEach { item ->
                    val isSel = item.baseUrl == selectedUrl
                    val isTesting = item.id in testing
                    val lat = latencies[item.id]
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable {
                                scope.launch {
                                    AiChatManager.saveFreeApiConfig(ctx, currentUserId, item.apiKey, item.baseUrl, item.model)
                                    android.widget.Toast.makeText(ctx, "已选用该免费 API", android.widget.Toast.LENGTH_SHORT).show()
                                    onSelected()
                                }
                            },
                        colors = CardDefaults.cardColors(containerColor = if (isSel) Color(0xFFEFF6FF) else Color(0xFFF9FAFB)),
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
                                    Text("测速中…", fontSize = 12.sp, color = Color(0xFF9CA3AF), fontWeight = FontWeight.Medium)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // 提供者信息（不暴露 URL / Key，避免被白嫖）
                            Text(
                                "由 ID 为 ${item.submitterId}、用户名为 ${item.submitterName.ifBlank { "未知" }} 的用户提供",
                                fontSize = 12.sp, color = Color(0xFF6B7280)
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (item.markedDown) {
                                    Text("● 已挂", fontSize = 12.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(10.dp))
                                }
                                if (lat != null) {
                                    Text(
                                        if (lat >= 0) "延迟 ${lat}ms" else "测速失败",
                                        fontSize = 12.sp,
                                        color = if (lat >= 0) Color(0xFF6B7280) else Color(0xFFDC2626)
                                    )
                                } else if (item.serverLatency > 0) {
                                    Text("上次延迟 ${item.serverLatency}ms", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                                }
                                if (isSel) {
                                    Spacer(Modifier.width(10.dp))
                                    Text("当前选用", fontSize = 12.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Medium)
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
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val serverLatency: Long = 0,
    val markedDown: Boolean = false,
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
            color = Color(0xFF6B7280)
        )
    }
}

// ===================== 裁剪预览弹窗 =====================

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
    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(imageUri)
                sourceBitmap = BitmapFactory.decodeStream(inputStream); inputStream?.close()
            } catch (_: Exception) {}
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
                            val cropped = try { Bitmap.createBitmap(bmp, bx, by, bSz, bSz) } catch (_: Exception) { null }
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
                        Text("取消", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                    }
                }
            } ?: run {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载图片中...", color = Color.White, fontSize = 16.sp)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                ) {
                    Text("取消", color = Color(0xFF9CA3AF))
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

    Box(Modifier.fillMaxSize().background(Color.White)) {
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
                    color = Color(0xFF1F2937)
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
                    color = Color(0xFF1F2937),
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

    Box(Modifier.fillMaxSize().background(Color.White)) {
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
                Text("在平台", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(48.dp))
            }

            Spacer(Modifier.height(24.dp))

            Text("注册时间", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
            Spacer(Modifier.height(6.dp))
            Text(regDate, fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(Modifier.height(14.dp))
            Text("注册了此账号", fontSize = 16.sp, color = Color(0xFF1F2937))

            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(1.dp).background(Color(0xFFE5E7EB)))
            Spacer(Modifier.height(28.dp))

            Text("距现在", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
            Spacer(Modifier.height(6.dp))
            Text(currentTimeStr, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))

            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(1.dp).background(Color(0xFFE5E7EB)))
            Spacer(Modifier.height(28.dp))

            Text("已在平台", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280))
            Spacer(Modifier.height(6.dp))
            Text(sinceRegStr, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
        }
    }
}
