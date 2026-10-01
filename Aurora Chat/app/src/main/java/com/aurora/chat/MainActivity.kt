@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.aurora.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.LoginResponse
import com.aurora.chat.data.open.OpenAuthManager
import com.aurora.chat.ui.open.OpenAuthScreen
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.activity.ActivityScreen
import com.aurora.chat.ui.components.*
import com.aurora.chat.ui.chat.*
import com.aurora.chat.ui.community.*
import com.aurora.chat.ui.qr.MyQrScreen
import com.aurora.chat.ui.qr.ScanScreen
import com.aurora.chat.ui.profile.*
import com.aurora.chat.ui.tools.*
import com.aurora.chat.ui.theme.AuroraChatTheme
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob


// 进程级一次性 IO 任务作用域（启动重试/头像刷新/定位上报等），替代 MainScope：不泄漏且不在主线程执行
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// 进程级一次性主线程任务作用域（登录等需在主线程执行的短时任务），替代 MainScope
private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

class MainActivity : ComponentActivity() {
    companion object {
        var pendingNotifCount = 0
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 第三方授权 deep link（热启动）：解析后交由 Compose 弹出授权界面
        OpenAuthManager.handleIntent(intent)
        // 系统分享接收（热启动）：单 Activity 复用必须走 onNewIntent，解析后由 Compose 弹出三选项菜单
        ShareReceiveHandler.handleIntent(intent)
        // 消费后立即清空分享 intent：避免后续 onResume（锁屏解锁/切后台）用残留 intent 反复弹出三选项菜单
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) setIntent(Intent())
        // 保存通知点击传过来的对话目标
        val openChatId = intent.getLongExtra("open_chat_id", 0L)
        if (openChatId != 0L) {
            getSharedPreferences("aurora_notif", Context.MODE_PRIVATE)
                .edit()
                .putLong("open_chat_id", openChatId)
                .putString("open_chat_name", intent.getStringExtra("open_chat_name") ?: "")
                .apply()
            pendingNotifCount++
        }
    }

    // 必要权限闸门状态：位置 / 文件管理 / 悬浮窗 / 通知，未全部授予不允许进入主界面
    private val gatePassed = androidx.compose.runtime.mutableStateOf(false)

    // 各权限实时状态（用于界面勾选标记与逐项跳转）
    private val permStates = androidx.compose.runtime.mutableStateOf<List<PermItem>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 处理账号被删除/封禁后强制退出登录
        if (intent.getBooleanExtra("force_logout", false)) {
            LocalStorage.logout(this)
            AuroraApi.authToken = null
            AuroraApi.currentUserId = 0
        }
        // 应用默认白色背景
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))

        // 启动时检查并申请必要权限（位置 / 文件管理 / 悬浮窗 / 通知），未全部授予不允许进入
        gatePassed.value = allRequiredGranted()
        permStates.value = buildPermStates()
        requestStartupPermissions()


        NotificationHelper.createNotificationChannel(this)

        // 启动网络状态监听
        com.aurora.chat.util.NetworkMonitor.start(this)

        // 启动时重试之前失败的群头像上传
        ioScope.launch {
            ChatRepository.retryPendingGroupAvatars(this@MainActivity)
        }

        // 崩溃自动重启（from_crash=true）：只有当本次确由崩溃重启意图拉起时，
        // 才证明自愈成功，由 CrashHandler 写入“拦截成功”标记（绝不谎报）。
        val fromCrash = intent?.getBooleanExtra("from_crash", false) ?: false
        com.aurora.chat.CrashHandler.confirmRestartIfNeeded(this, fromCrash)

        val openFriendRequests = intent?.getBooleanExtra("open_friend_requests", false) ?: false

        // 保存 TCP 通知带来的对话目标，供 Composable 中读取
        val openChatId = intent?.getLongExtra("open_chat_id", 0L) ?: 0L
        val openChatName = intent?.getStringExtra("open_chat_name") ?: ""
        if (openChatId != 0L) {
            getSharedPreferences("aurora_notif", Context.MODE_PRIVATE)
                .edit()
                .putLong("open_chat_id", openChatId)
                .putString("open_chat_name", openChatName)
                .apply()
        }

        // 第三方授权 deep link（冷启动）：进程被杀/全新拉起时，onCreate 解析 intent 并登记授权请求
        OpenAuthManager.handleIntent(intent)
        // 系统分享接收（冷启动）：其他 App 通过 ACTION_SEND / ACTION_SEND_MULTIPLE 分享文本/图片/文件
        ShareReceiveHandler.handleIntent(intent)
        // 消费后立即清空分享 intent：避免 onResume 兜底用残留 intent 反复弹出三选项菜单
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) setIntent(Intent())

        setContent {
            AuroraChatTheme(darkTheme = false) {
                Box {
                    if (gatePassed.value) {
                        AuroraMainScreen(openFriendRequests = openFriendRequests, fromCrash = fromCrash)
                    } else {
                        PermissionGateScreen(
                            items = permStates.value,
                            onItemClick = { onPermItemClick(it) },
                            onGrantAll = { requestStartupPermissions() },
                            onExit = {
                                finishAffinity()
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        )
                    }
                    // 第三方授权全屏界面：冷/热启动收到 deep link 后全局弹出（覆盖所有页面）
                    OpenAuthManager.authRequest.value?.let { req ->
                        OpenAuthScreen(
                            req = req,
                            onBack = { OpenAuthManager.dismiss() },
                            onConfirm = { OpenAuthManager.dismiss() },
                        )
                    }
                    // 系统分享接收：收到外部分享后弹出三选项菜单（保存到工作区 / 发给 AI / 发给好友）
                    ShareReceiveHandler.pendingShare.value?.let { share ->
                        ShareReceiveDialog(
                            payload = share,
                            onDismiss = { ShareReceiveHandler.dismiss() },
                            onSendAiToChat = {
                                // 发给 AI：不再弹窗内假流程——关闭分享对话框，跳转真实 AI 对话，
                                // 把分享内容预挂到输入区上方文件挂载行，由用户点发送才真正发送。
                                ShareReceiveHandler.dismiss()
                                com.aurora.chat.ui.chat.AiChatShare.pending.value =
                                    com.aurora.chat.ui.chat.AiChatSharePayload(
                                        text = share.text,
                                        files = share.files.mapNotNull { f ->
                                            runCatching {
                                                val size = f.length()
                                                val bytes = if (size in 1..(512 * 1024)) {
                                                    try { f.readBytes() } catch (_: Exception) { null }
                                                } else null
                                                val ext = f.extension.lowercase()
                                                val mime = android.webkit.MimeTypeMap.getSingleton()
                                                    .getMimeTypeFromExtension(ext) ?: "*/*"
                                                com.aurora.chat.ui.chat.PendingAiFile(
                                                    uri = android.net.Uri.fromFile(f),
                                                    name = f.name,
                                                    mime = mime,
                                                    size = size,
                                                    bytes = bytes
                                                )
                                            }.getOrNull()
                                        }
                                    )
                            }
                        )
                    }
                }
            }
        }
    }


    // 启动时批量申请必要权限（位置 / 文件管理 / 悬浮窗 / 通知），未全部授予不允许进入
    private val startupPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty()) {
            // 运行时权限全部授予，特殊权限非强制，不主动跳系统页
            proceedSpecialPermissions(autoJump = false)
            return@registerForActivityResult
        }
        // 存在被拒绝的运行时权限：判断是否"永久拒绝（不再询问）"
        val permanentlyDenied = denied.any { !shouldShowRequestPermissionRationale(it) }
        if (permanentlyDenied) {
            // 已勾选"不再询问"，系统弹窗不会再出现，引导用户去应用详情设置页手动开启
            android.widget.Toast.makeText(
                this,
                "权限已被拒绝，请到系统设置中手动开启位置/通知权限",
                android.widget.Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {}
        } else {
            // 本次只是拒绝、仍可再次请求：特殊权限非强制，不主动跳系统页，避免死循环
            proceedSpecialPermissions(autoJump = false)
        }
    }

    // 是否所有必要权限均已授予
    // 注意：悬浮窗与文件管理属于「增强型」权限（后台保活、悬浮下载提示），
    // 不应作为进入 App 的强制门槛，否则未授权时会反复跳系统设置页甚至闪退。
    // 仅将定位与通知作为启动必需权限。
    private fun allRequiredGranted(): Boolean {
        val locOk = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notifOk = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
            || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return locOk && notifOk
    }

    private fun requestStartupPermissions() {
        // 1) 运行时权限（位置 + 通知）走系统弹窗
        val runtimePerms = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runtimePerms += android.Manifest.permission.POST_NOTIFICATIONS
            }
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runtimePerms += android.Manifest.permission.ACCESS_FINE_LOCATION
            runtimePerms += android.Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (runtimePerms.isNotEmpty()) {
            startupPermissionLauncher.launch(runtimePerms.toTypedArray())
        } else {
            // 运行时权限都已授予，特殊权限非强制，不主动跳系统页
            proceedSpecialPermissions(autoJump = false)
        }
    }

    // 处理需要跳转到系统设置页授予的特殊权限（悬浮窗 / 文件管理），一次跳转一个
    // autoJump=false 时（如 App 启动阶段）只评估闸门、不主动跳系统页，
    // 避免未授权这些「增强型」权限时反复被拽到系统设置页甚至闪退。
    private fun proceedSpecialPermissions(autoJump: Boolean = true) {
        if (!autoJump) {
            // 启动阶段：特殊权限非强制，直接放行进主界面，由用户在设置页自行决定是否开启
            gatePassed.value = allRequiredGranted()
            return
        }
        when {
            !android.provider.Settings.canDrawOverlays(this) -> {
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(
                        this,
                        "无法打开悬浮窗设置页，请到系统设置中手动开启",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            else -> {
                // 特殊权限也齐了，重新评估闸门
                gatePassed.value = allRequiredGranted()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回后，重新检查是否所有必要权限均已授予
        if (!gatePassed.value && allRequiredGranted()) {
            gatePassed.value = true
        }
        permStates.value = buildPermStates()
        // 回到前台:复位「手动关闭保活」的抑制标志(用户回来过,下次退出若 AI 仍在跑可再次自动保活)
        AiKeepAliveService.resetAutoSuppress()
        // 热启动兜底：个别 ROM/分享器可能不回调 onNewIntent 而直接 resume，
        // 用 handleIntent 幂等去重后再解析一次（同一 intent 不会重复弹窗），确保系统分享一定能进入接收流程。
        // 仅当 intent 确为分享时才处理，且消费后立即清空，避免锁屏解锁/onResume 反复误触发三选项菜单。
        getIntent()?.let { itt ->
            if (itt.action == Intent.ACTION_SEND || itt.action == Intent.ACTION_SEND_MULTIPLE) {
                ShareReceiveHandler.handleIntent(itt)
                setIntent(Intent())
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 自动后台保活:AI 任务未完成(哨兵标记在跑)时用户退出应用,
        // 自动挂可见保活小窗,防止进程被系统冻结导致任务中断。
        // 用户在悬浮窗里手动关闭过(本次在 App 外)则不重复开启;回到前台后自动复位。
        try {
            if (AgentTaskGuard.isRunning(this)) {
                AiKeepAliveService.startAuto(this)
            }
        } catch (_: Exception) {}
    }

    // 构建各权限的实时状态列表（用于闸门界面勾选标记与逐项跳转）
    private fun buildPermStates(): List<PermItem> {
        val locOk = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notifOk = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
            || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val overlayOk = android.provider.Settings.canDrawOverlays(this)
        return listOf(
            PermItem("location", "位置权限", "用于附近的人、位置相关功能", locOk),
            // 文件管理权限已不再作为启动强制项（易在部分机型/新版系统上无法授权）；
            // 保留清单声明，用户真正用到公共目录读写时再临时唤起。
            PermItem("overlay", "悬浮窗权限", "用于后台下载 / 上传悬浮窗提示", overlayOk),
            PermItem("notification", "通知权限", "用于接收消息与系统通知", notifOk)
        )
    }

    // 点击闸门界面中某个权限项：运行时权限直接弹窗 / 特殊权限跳转系统设置页
    private fun onPermItemClick(key: String) {
        when (key) {
            "location" -> requestRuntimePermission(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ))
            "notification" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    requestRuntimePermission(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            "overlay" -> openOverlaySettings()
            // 文件管理权限不再强制，从闸门移除；需要时在对应功能处懒唤起
        }
    }

    // 请求单个运行时权限；若已被永久拒绝（不再询问）则直接跳转应用详情设置页
    private fun requestRuntimePermission(perms: Array<String>) {
        val anyPermanent = perms.any { p ->
            checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(p)
        }
        if (anyPermanent) {
            openAppDetailsSettings()
        } else {
            startupPermissionLauncher.launch(perms)
        }
    }

    private fun openOverlaySettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            // 兜底：跳到应用详情设置页（始终可用），用户在"权限"中手动授予悬浮窗权限
            openAppDetailsSettings()
        }
    }

    private fun openAppDetailsSettings() {
        try {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")
            ))
        } catch (_: Exception) {}
    }
}

// 权限项数据模型（用于闸门界面显示状态与逐项跳转）
internal data class PermItem(
    val key: String,
    val title: String,
    val desc: String,
    val granted: Boolean
)

// 必要权限强制闸门界面：未授予全部权限前不允许进入主界面
@Composable
private fun PermissionGateScreen(
    items: List<PermItem>,
    onItemClick: (String) -> Unit,
    onGrantAll: () -> Unit,
    onExit: () -> Unit
) {
    // 不给权限不让进，拦截返回键直接退出应用
    BackHandler { onExit() }

    val grantedColor = Color(0xFF2E7D32)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "权限申请",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "以下权限为必要权限，请全部授予后进入。点击未授予的项可直接前往系统设置开启。",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            items.forEach { item ->
                val cardBg = if (item.granted) {
                    grantedColor.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onItemClick(item.key) },
                    colors = CardDefaults.cardColors(containerColor = cardBg)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                item.desc,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        if (item.granted) {
                            Text(
                                "✓ 已授权",
                                color = grantedColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        } else {
                            Text(
                                "去开启",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onGrantAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("一键授予全部并进入", fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "退出应用",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
@androidx.compose.material3.ExperimentalMaterial3Api
fun AuroraMainScreen(
    openFriendRequests: Boolean = false,
    fromCrash: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()


    // 使用 LocalStorage 管理登录状态
    val loginState = remember { LocalStorage.loadLoginState(context) }
    var isLoggedIn by remember { mutableStateOf(loginState.isLoggedIn) }
    var currentUserId by remember { mutableStateOf(loginState.userId) }
    var currentEmail by remember { mutableStateOf(loginState.email) }
    var currentQQ by remember { mutableStateOf(loginState.qq) }
    // 初始化即判断：已保存会话但本地 QQ 为空（邮箱/占位账号）则直接拦截，避免启动闪烁
    var showForceProfileDialog by remember { mutableStateOf(loginState.isLoggedIn && loginState.qq.isBlank()) }
    // 未绑定真实邮箱（占位邮箱）时，引导绑定邮箱用于消息推送，只弹一次
    var showBindEmailPrompt by remember { mutableStateOf(false) }

    // 恢复会话时同步更新 AuroraApi 中的当前用户信息
    LaunchedEffect(loginState) {
        AuroraApi.currentUserId = loginState.userId
        AuroraApi.currentUserName = loginState.username
        AuroraApi.currentUserQQ = loginState.qq
        // 旧会话本地未存 QQ（user_qq 为空）时，从服务器补齐，
        // 否则 isDeveloper 判断为空串 → 开发者管理按钮不显示、切换账号不显示 QQ
        if (loginState.qq.isBlank() && loginState.isLoggedIn && loginState.userId > 0) {
            launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val info = com.aurora.chat.data.repository.ChatRepository.getUserInfo(loginState.userId)
                    if (info.success && info.data != null) {
                        val srvQQ = info.data!!.qqNumber
                        if (srvQQ.isNotBlank()) {
                            // 服务端有 QQ，补齐本地并解除拦截
                            com.aurora.chat.data.local.LocalStorage.saveUserQQ(context, srvQQ)
                            AuroraApi.currentUserQQ = srvQQ
                            currentQQ = srvQQ
                            showForceProfileDialog = false
                        } else {
                            // 服务端也无 QQ（邮箱/占位账号），强制补全
                            showForceProfileDialog = true
                        }
                    } else {
                        showForceProfileDialog = true
                    }
                } catch (_: Exception) {
                    // 拉取失败且本地无 QQ，仍强制补全，避免开发者等功能判定异常
                    showForceProfileDialog = true
                }
            }
        }
    }

    // 单设备登录：被踢下线时自动切回登录页
    val kickedOffReason by AuroraApi.kickedOffEvent.collectAsState()
    LaunchedEffect(kickedOffReason) {
        if (kickedOffReason != null && isLoggedIn) {
            AuroraApi.authToken = null
            AuroraApi.currentUserId = 0
            LocalStorage.logout(context)
            isLoggedIn = false
            currentUserId = 0
            Toast.makeText(context, kickedOffReason, Toast.LENGTH_LONG).show()
        }
    }

    fun doLogin(email: String, username: String, userId: Long, token: String) {
        //  先保存登录状态到本地（确保持久化优先于一切）
        val sig = AuroraApi.currentUserSignature
        val qq = AuroraApi.currentUserQQ
        LocalStorage.saveLogin(context, LoginResponse(userId, email, username, sig, token, qqNumber = qq))
        AuroraApi.currentUserName = username
        currentUserId = userId
        currentEmail = email
        // 登录后立即载入本账号未读（与底部"聊天"红点、列表红点同源）
        ChatViewModel.loadUnreadForUser(context, userId)
        // 切账号时归零"当前打开会话"，避免旧账号会话 id 残留导致新账号未读被误判为"正在看不计未读"
        ChatViewModel.openConversationId = 0L
        currentQQ = qq
        isLoggedIn = true

        // 邮箱/占位账号（QQ 为空）强制完善资料，未补填前不进入主界面
        if (qq.isBlank()) {
            showForceProfileDialog = true
        }
        ioScope.launch {
            try { ChatRepository.refreshAvatarsOnLogin(context) } catch (_: Exception) { }
        }
        try { TcpService.start(context) } catch (_: Exception) { }
        ChatViewModel.clearFriendRequests()
        ChatViewModel.loadFriendRequests()
        // 保活（1px 浮窗 / 前台服务等）交由用户在「保活设置」页手动开启，
        // 不再在登录时强制拉起，避免未授权悬浮窗/电池白名单时反复跳系统设置页并闪退
        // 在线时间：先恢复本地缓存，再异步拉取服务端基线
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 先恢复本地上次保存的值
                com.aurora.chat.ui.profile.OnlineTimeTracker.restoreFromPrefs(context)
                // 再拉取服务端最大值作为基线
                val info = com.aurora.chat.data.repository.ChatRepository.getUserInfo(userId)
                if (info.success && info.data != null) {
                    com.aurora.chat.ui.profile.OnlineTimeTracker.setServerBase(info.data!!.onlineTimeSeconds)
                }
            } catch (_: Exception) {}
        }
        // 启动在线计时
        com.aurora.chat.ui.profile.OnlineTimeTracker.start(context)
    }

    // 判断占位邮箱（未绑定真实邮箱）：QQ/手机注册的假邮箱，或 @local 结尾
    fun isPlaceholderEmail(email: String): Boolean =
        email.startsWith("qq_") || email.startsWith("temp_") || email.endsWith("@local")

    // 进入主界面后：若当前用户未绑定真实邮箱，弹一次"绑定邮箱用于消息推送"提示（仅一次，不重复弹）
    LaunchedEffect(isLoggedIn, currentUserId, currentEmail, showForceProfileDialog) {
        if (isLoggedIn && !showForceProfileDialog && currentEmail.isNotBlank() && isPlaceholderEmail(currentEmail)) {
            val prefs = context.getSharedPreferences("aurora_main_prefs", Context.MODE_PRIVATE)
            val key = "bind_email_prompt_shown_$currentUserId"
            if (!prefs.getBoolean(key, false)) {
                prefs.edit().putBoolean(key, true).apply()
                showBindEmailPrompt = true
            }
        }
    }

    if (isLoggedIn && !showForceProfileDialog) {
        // 拦截闪退自动重启标记（崩溃后 from_crash=true），供 MainContent 决定是否在拦截瞬间弹“闪退拦截成功”对话框
        MainContent(
            currentUserId = currentUserId,
            currentEmail = currentEmail,
            currentQQ = currentQQ,
            openFriendRequests = openFriendRequests,
            isLoggedIn = isLoggedIn,
            onLoggedInChange = { isLoggedIn = it },
            onUserIdChange = { currentUserId = it },
            onLogout = {
                isLoggedIn = false
            },
            onLoginSuccess = { email, username, userId ->
                val token = AuroraApi.authToken ?: ""
                doLogin(email, username, userId, token)
            },
            fromCrash = fromCrash
        )
    } else if (!showForceProfileDialog) {
        LoginScreen(onLoginSuccess = { email, username, userId ->
            mainScope.launch {
                val token = AuroraApi.authToken ?: ""
                doLogin(email, username, userId, token)
            }
        })
    }

    // 强制完善资料弹窗（不可跳过，未完善前不进入主界面）
    if (showForceProfileDialog) {
        com.aurora.chat.ui.components.ForceProfileDialog(
            currentUsername = AuroraApi.currentUserName,
            currentQQNumber = AuroraApi.currentUserQQ,
            onComplete = { resp ->
                showForceProfileDialog = false
                doLogin(resp.email, resp.username, resp.id, resp.token)
            }
        )
    }

    // 未绑定真实邮箱时的一次性引导弹窗（提示绑定邮箱用于消息推送）
    if (showBindEmailPrompt) {
        AlertDialog(
            onDismissRequest = { showBindEmailPrompt = false },
            title = { Text("消息推送") },
            text = { Text("如需要消息推送，请在设置中绑定邮箱") },
            confirmButton = {
                TextButton(onClick = { showBindEmailPrompt = false }) { Text("知道了") }
            }
        )
    }

    // ── 体验版卡密检测（优先级最高，在公告弹窗之后） ──
    var showTrialDialog by remember { mutableStateOf(false) }
    var trialKeyInput by remember { mutableStateOf("") }
    var trialError by remember { mutableStateOf("") }
    var trialChecked by remember { mutableStateOf(false) }
    var trialPassed by remember { mutableStateOf(false) }

    val devCtx = LocalContext.current
    LaunchedEffect(currentUserId) {
        if (currentUserId > 0 && !trialPassed) {
            // 开发者账号直接跳过验证
            if (com.aurora.chat.data.local.LocalStorage.isDeveloper(currentQQ, currentEmail)) {
                trialPassed = true
                // 标记开发者账号，官方 API 额度视为无限
                com.aurora.chat.ui.chat.AiChatManager.setDeveloper(devCtx, currentUserId, true)
            } else {
            try {
                val trialResult = com.aurora.chat.data.api.AuroraApi.checkTrialMode()
                val enabled = trialResult.success && trialResult.data != null && trialResult.data!!.optBoolean("enabled", false)
                trialChecked = true
                if (enabled) {
                    showTrialDialog = true
                } else {
                    trialPassed = true
                }
            } catch (_: Exception) {
                trialChecked = true
                trialPassed = true // 网络错误时放行，避免用户卡死
            }
            }
        }
    }

    // 体验版全屏验证界面
    if (showTrialDialog && !trialPassed) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF9FAFB))
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "体验版验证",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "请输入卡密以继续使用",
                    fontSize = 14.sp,
                    color = Color(0xFF6B7280)
                )
                Spacer(Modifier.height(24.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = trialKeyInput,
                    onValueChange = { trialKeyInput = it.take(12).uppercase(); trialError = "" },
                    singleLine = true,
                    placeholder = { Text("请输入卡密") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    )
                )
                if (trialError.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(trialError, fontSize = 13.sp, color = Color(0xFFDC2626))
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (trialKeyInput.length < 4) {
                            trialError = "请输入有效的卡密"
                            return@Button
                        }
                        trialError = ""
                        scope.launch {
                            try {
                                val r = com.aurora.chat.data.api.AuroraApi.validateCardKey(trialKeyInput, currentUserId)
                                val valid = r.success && r.data != null && r.data!!.optBoolean("valid", false)
                                if (valid) {
                                    trialPassed = true
                                    showTrialDialog = false
                                    android.widget.Toast.makeText(context, "验证成功", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val msg = r.data?.optString("message", "") ?: ""
                                    trialError = if (msg.isNotEmpty()) msg else r.message.ifEmpty { "卡密无效或已过期" }
                                }
                            } catch (_: Exception) {
                                trialError = "验证失败，请检查网络"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("验证", fontSize = 15.sp, color = Color.White)
                }
            }
        }
    }

    // ── 启动时请求位置权限（仅首次弹说明，已授权则静默上报） ──
    val locationPermissionGranted = remember { mutableStateOf(false) }
    var showLocationRationale by remember { mutableStateOf(false) }
    val locationRationaleShown = remember {
        context.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE)
            .getBoolean("rationale_shown", false)
    }
    val locationRequestLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fine = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationPermissionGranted.value = fine || coarse
        if (locationPermissionGranted.value) {
            mainScope.launch {
                val locPrefs = context.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE)
                // 仅在已登录且从未成功上报过时才上报；未登录时交由登录后的 LaunchedEffect(isLoggedIn) 处理，
                // 避免未登录（无 token）上报失败却仍置"已上报"导致后续不再重试
                if (isLoggedIn && !locPrefs.getBoolean("uploaded_once", false)) {
                    if (uploadCurrentLocation(context)) {
                        locPrefs.edit().putBoolean("uploaded_once", true).apply()
                    }
                }
            }
        }
    }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn && currentUserId > 0) {
            try {
                val hasFine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasCoarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                locationPermissionGranted.value = true
                // 仅在从未成功上报过位置时才自动读取并上报（成功一次后跳过，不再每次打开都读）
                val locPrefs = context.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE)
                if (!locPrefs.getBoolean("uploaded_once", false)) {
                    if (uploadCurrentLocation(context)) {
                        locPrefs.edit().putBoolean("uploaded_once", true).apply()
                    }
                }
            } else if (!locationRationaleShown) {
                    // 首次未授权 → 弹说明窗
                    showLocationRationale = true
                } else {
                    // 已说明过但用户还没给权限 → 直接弹系统授权
                    locationRequestLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            } catch (_: Exception) { }
        }
    }

    // 位置权限用途说明弹窗（仅弹出一次，之后直接走系统授权）
    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = { showLocationRationale = false },
            title = { Text("位置权限", fontWeight = FontWeight.Bold) },
            text = { Text("该权限将会用于地域区分", fontSize = 14.sp, color = Color(0xFF374151)) },
            confirmButton = {
                TextButton(onClick = {
                    showLocationRationale = false
                    context.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("rationale_shown", true).apply()
                    locationRequestLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLocationRationale = false
                    context.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("rationale_shown", true).apply()
                }) { Text("拒绝", color = Color(0xFF6B7280)) }
            }
        )
    }
}

/** 获取当前位置并上报到服务器（静默执行）。成功返回 true，失败返回 false，调用方据此决定是否标记"已上报"。 */
private suspend fun uploadCurrentLocation(context: android.content.Context): Boolean {
    return withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager

            // 1) 优先用系统缓存的最近位置（立即返回，不等待、不依赖定位服务是否开启）
            var loc: android.location.Location? = null
            for (p in arrayOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                try {
                    @Suppress("MissingPermission")
                    val last = locationManager.getLastKnownLocation(p)
                    if (last != null) { loc = last; break }
                } catch (_: Exception) { }
            }

            // 2) 缓存位置不可用 → 等一次单点定位（最长 12 秒）
            if (loc == null) {
                val provider = when {
                    locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (provider != null) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(l: android.location.Location) { loc = l; latch.countDown() }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) { latch.countDown() }
                    }
                    try {
                        @Suppress("MissingPermission")
                        locationManager.requestSingleUpdate(provider, listener, null)
                        latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) { } finally {
                        try { locationManager.removeUpdates(listener) } catch (_: Exception) { }
                    }
                }
            }

            val got = loc ?: return@withContext false
            val lat = got.latitude
            val lng = got.longitude
            var address = ""
            try {
                val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                val addrs = geocoder.getFromLocation(lat, lng, 1)
                if (addrs != null && addrs.isNotEmpty()) address = addrs[0].getAddressLine(0) ?: ""
            } catch (_: Exception) { }
            // 上报服务器；只有上报真正成功才返回 true（无 token 或网络失败时返回 false，调用方会保留重试机会）
            com.aurora.chat.data.api.AuroraApi.uploadLocation(lat, lng, address).success
        } catch (_: Exception) {
            false
        }
    }
}

@Composable
@androidx.compose.material3.ExperimentalMaterial3Api
fun MainContent(
    currentUserId: Long = 0,
    currentEmail: String = "",
    currentQQ: String = "",
    openFriendRequests: Boolean = false,
    onLogout: () -> Unit = {},
    onLoginSuccess: (email: String, username: String, userId: Long) -> Unit = { _, _, _ -> },
    isLoggedIn: Boolean = false,
    onLoggedInChange: (Boolean) -> Unit = {},
    onUserIdChange: (Long) -> Unit = {},
            fromCrash: Boolean = false
) {
    val scope = rememberCoroutineScope()
    var currentTab by remember { mutableStateOf(AuroraTab.Chat) }
    // 活动中心（由 MainActivity 统一控制，便于从任意页面跳转进入）
    var showActivity by remember { mutableStateOf(false) }
    var activityInitialPage by remember { mutableStateOf(0) }
    var connectionStatus by remember { mutableStateOf(ConnStatus.Connecting) }
    var statusBanner by remember { mutableStateOf<StatusBanner?>(null) }
    // 是否正在执行"保存所有数据"
    var savingAll by remember { mutableStateOf(false) }
    // 是否正在执行"重置本地数据"
    var resetting by remember { mutableStateOf(false) }
    // 是否正在执行"拉取30天内未拉取的消息"（含进度）
    var pulling30 by remember { mutableStateOf(false) }
    var pullDone by remember { mutableStateOf(0) }
    var pullTotal by remember { mutableStateOf(0) }
    // 是否弹出"将数据重置到本地"确认框
    var showResetConfirm by remember { mutableStateOf(false) }
    // 文件管理器中选中的待重置备份 URI
    var resetUri by remember { mutableStateOf<Uri?>(null) }
    // 备份/重置用到的 context（MainContent 作用域内自行获取，避免依赖上层作用域变量）
    val backupCtx = LocalContext.current
    // 是否弹出"清空所有聊天记录"确认框
    var showClearChatRecordsConfirm by remember { mutableStateOf(false) }

    // 保存备份：弹出文件管理器让用户选择保存位置/文件名
    val backupSaveLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && !savingAll) {
            savingAll = true
            scope.launch(Dispatchers.IO) {
                val res = com.aurora.chat.data.local.DataBackup.exportTo(backupCtx, currentUserId, uri)
                withContext(Dispatchers.Main) {
                    savingAll = false
                    val sizeText = if (res.fileSize > 0) "，大小 ${formatBackupSize(res.fileSize)}" else ""
                    val msg = if (res.success)
                        "已保存 ${res.conversations} 个会话、共 ${res.messages} 条消息到所选位置$sizeText"
                    else "保存失败：${res.error}"
                    Toast.makeText(backupCtx, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 选择备份文件用于重置
    val backupOpenLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            resetUri = uri
            showResetConfirm = true
        }
    }
    // ── 封禁全屏提示 ──
    var showBanOverlay by remember { mutableStateOf(false) }
    var banReason by remember { mutableStateOf("") }
    var banExpiresAt by remember { mutableStateOf(0L) }
    var banDuration by remember { mutableStateOf(0L) }
    var banUnbanPopupMessage by remember { mutableStateOf("") }
    //  APK 签名校验（防篡改）
    val ctxForSig = LocalContext.current
    val isSignatureValid by remember {
        mutableStateOf(com.aurora.chat.util.SignatureValidator.verify(ctxForSig))
    }

    // ── 版本更新检查（每次打开都检查） ──
    var updateData by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var isForceUpdate by remember { mutableStateOf(false) }
    var updateSkipped by remember { mutableStateOf(false) }
    var showDownloadQueue by remember { mutableStateOf(false) }
    val versionCtx = LocalContext.current
    LaunchedEffect(Unit) {
        try {
            val r = com.aurora.chat.data.api.AuroraApi.checkAppVersion()
            if (r.success && r.data != null) {
                val serverCode = r.data.optInt("latest_version_code", 0)
                val force = r.data.optBoolean("force_update", false)
                if (serverCode > 0) {
                    val pkgInfo = versionCtx.packageManager.getPackageInfo(versionCtx.packageName, 0)
                    val localCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkgInfo.longVersionCode else pkgInfo.versionCode.toLong()
                    if (serverCode > localCode) {
                        updateData = r.data
                        isForceUpdate = force
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ── 签名校验失败：全屏拦截弹窗 ──
    if (!isSignatureValid) {
        SignatureBlockDialog()
        return
    }

    // ── 强制更新覆盖层（在弹窗之上，全屏拦截操作） ──
    if (isForceUpdate && updateData != null) {
        val updateBox = @Composable {
            val ctx = LocalContext.current
            val data = updateData!!
            var dlUrl = data.optString("download_url", "")
            val msg = data.optString("update_message", "发现新版本，请更新后继续使用。")
            if (dlUrl.isEmpty()) dlUrl = "http://www.YOUR_SERVER_DOMAIN:5004/tools/AuroraChat.apk"
            Box(
                modifier = Modifier.fillMaxSize().background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                // 强制更新弹窗覆盖所有内容
                if (!showDownloadQueue) {
                    Card(
                        modifier = Modifier.widthIn(max = 320.dp).padding(24.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("版本更新", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                            Spacer(Modifier.height(16.dp))
                            Text(msg, fontSize = 14.sp, color = Color(0xFF4B5563))
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    try {
                                        com.aurora.chat.ui.tools.DownloadManager.addCommunityDownload("Aurora Chat", "aurora.apk", dlUrl, com.aurora.chat.R.drawable.ic_app_icon)
                                        com.aurora.chat.ui.tools.DownloadManager.startDownloads(ctx)
                                        showDownloadQueue = true
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(ctx, "下载失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                            ) {
                                Text("马上更新", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    // 下载管理界面（无法退出）
                    Box(Modifier.fillMaxSize()) {
                        com.aurora.chat.ui.components.EventBlocker()
                        com.aurora.chat.ui.tools.DownloadQueueScreen(
                            onMinimize = { /* do nothing - can't exit */ }
                        )
                    }
                }
            }
        }
        updateBox()
        return
    }

    // ── 可选更新弹窗 ──
    if (updateData != null && !updateSkipped && !isForceUpdate) {
        val ctx = LocalContext.current
        val data = updateData!!
        var dlUrl = data.optString("download_url", "")
        val msg = data.optString("update_message", "发现新版本，请更新后继续使用。")
        if (dlUrl.isEmpty()) dlUrl = "http://www.YOUR_SERVER_DOMAIN:5004/tools/AuroraChat.apk"
        AlertDialog(
            onDismissRequest = { updateSkipped = true; updateData = null },
            containerColor = Color.White,
            title = { Text("发现新版本", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { Text(msg, fontSize = 14.sp, color = Color(0xFF374151)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        com.aurora.chat.ui.tools.DownloadManager.addCommunityDownload("Aurora Chat", "aurora.apk", dlUrl, com.aurora.chat.R.drawable.ic_app_icon)
                        com.aurora.chat.ui.tools.DownloadManager.startDownloads(ctx)
                        showDownloadQueue = true
                        updateData = null
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(ctx, "下载失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text("马上更新", color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { updateSkipped = true; updateData = null }) {
                    Text("稍后更新", color = Color(0xFF6B7280))
                }
            }
        )
    }

    //  首次渲染前同步检查封禁记录
    val banPrefs = LocalContext.current.getSharedPreferences("aurora_ban", android.content.Context.MODE_PRIVATE)
    val initExp = banPrefs.getLong("expires_at", 0L)
    if (initExp > System.currentTimeMillis() / 1000 && currentUserId > 0 && !showBanOverlay) {
        banReason = banPrefs.getString("reason", "") ?: ""
        banExpiresAt = initExp
        banDuration = banPrefs.getLong("duration", 0L)
        banUnbanPopupMessage = banPrefs.getString("unban_popup_message", "") ?: ""
        showBanOverlay = true
    }

    //  封禁拦截：放在正常UI之前渲染，防止因API请求失败导致渲染崩溃而无法显示封禁提示
    if (showBanOverlay) {
        val act = LocalContext.current as? android.app.Activity
        BanOverlay(
            reason = banReason,
            expiresAt = banExpiresAt,
            duration = banDuration,
            unbanPopupMessage = banUnbanPopupMessage,
            onDismiss = { showBanOverlay = false; banPrefs.edit().clear().apply() },
            onExit = { act?.finishAffinity() }
        )
        return
    }

    // 解封弹窗状态（实际渲染见下方 Compose 卡片，统一为声明式 UI）
    var showUnbanPopup by remember { mutableStateOf(false) }
    var unbanPopupText by remember { mutableStateOf("") }

    var showAbout by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var showPrivacy by remember { mutableStateOf(false) }
    var showEditProfile by remember { mutableStateOf(false) }
    var showWebsiteHosting by remember { mutableStateOf(false) }
    var showBottle by remember { mutableStateOf(false) }
    var showRedeemCardKey by remember { mutableStateOf(false) }
    var showMessagePush by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showDeveloperManagement by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showJoinRequestFullScreen by remember { mutableStateOf(false) }
    var joinRequestData by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var joinRequestGroupId by remember { mutableStateOf(0L) }
    val redeemScope = rememberCoroutineScope()
    var showCommunitySub by remember { mutableStateOf(false) }
    var communitySubTitle by remember { mutableStateOf("") }
    var communityRefreshKey by remember { mutableStateOf(0) }
    var communityCreatePost by remember { mutableStateOf(false) }
    var showMyPosts by remember { mutableStateOf(false) }
    var myPostsRefreshKey by remember { mutableStateOf(0) }
    var showPostDetail by remember { mutableStateOf(false) }
    var postDetailId by remember { mutableStateOf(0L) }
    var postDetailType by remember { mutableStateOf("") }
    var chatOpen by remember { mutableStateOf(false) }
    var chatFriendId by remember { mutableStateOf(0L) }
    var showAiSandbox by remember { mutableStateOf(false) }
    // token 余额耗尽卡片（Chat/AI沙盒回调触发，MainActivity 顶层独立渲染，避免依赖子页面布局）
    var showTokenCard by remember { mutableStateOf(false) }
    val sandboxCtx = androidx.compose.ui.platform.LocalContext.current
    val sandboxState = remember {
        AiSandboxState().apply { loadConfig(sandboxCtx) }
    }
    var chatFriendName by remember { mutableStateOf("") }
    var chatRefreshKey by remember { mutableStateOf(0) }
    // 系统分享 → AI：有注入请求时跳转真实 AI 对话；注入内容由 ChatConversationScreen 消费后置 null
    LaunchedEffect(com.aurora.chat.ui.chat.AiChatShare.pending.value) {
        val inject = com.aurora.chat.ui.chat.AiChatShare.pending.value ?: return@LaunchedEffect
        if (chatFriendId != com.aurora.chat.ui.chat.AI_CHAT_ID) {
            chatFriendId = com.aurora.chat.ui.chat.AI_CHAT_ID
            chatFriendName = "AI 助手"
        }
        chatOpen = true
        currentTab = AuroraTab.Chat
    }
    // 聊天列表滚动状态提升到此处，跨 AnimatedContent 的销毁/重建存活，实现切回聊天时位置记忆化
    val chatListState = rememberLazyListState()
    // 双击底部聊天图标时触发的"循环定位未读"请求（非 null 即待消费，ChatScreen 消费后置 null 防重建重滚）
    var chatScrollRequest by remember { mutableStateOf<Long?>(null) }
    // 我的二维码 / 扫一扫 / 扫码后用户资料（MainActivity 根级渲染，避免被内部容器压缩/事件穿透）
    var showMyQr by remember { mutableStateOf(false) }
    var showScan by remember { mutableStateOf(false) }
    var scannedUserId by remember { mutableStateOf(0L) }
    var scannedUserName by remember { mutableStateOf("") }
    var showScannedProfile by remember { mutableStateOf(false) }
    // 联系人点击 -> 全屏个人资料（MainActivity 根级渲染，从右侧滑入覆盖顶部标题与底部导航）
    var showUserProfile by remember { mutableStateOf(false) }
    var profileUserId by remember { mutableStateOf(0L) }
    var profileUserName by remember { mutableStateOf("") }
    // 相册选图后的待解析图片字节（选图后立即切入资料页，后台解析）
    var scanPendingImage by remember { mutableStateOf<ByteArray?>(null) }
    var showSwitchAccount by remember { mutableStateOf(false) }
    var showErrorLog by remember { mutableStateOf(false) }
    var showCrashIntercept by remember { mutableStateOf(false) }
    var showForceAvatar by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showProvideFreeApi by remember { mutableStateOf(false) }
    var showOpenApply by remember { mutableStateOf(false) }
    var devInitialTab by remember { mutableStateOf<String?>(null) }
    var showAvatarViewer by remember { mutableStateOf(false) }
    var avatarViewerBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    // 清空数据-安全验证
    var showClearDataPasswordDialog by remember { mutableStateOf(false) }
    var clearDataPassword by remember { mutableStateOf("") }
    var clearDataPasswordError by remember { mutableStateOf(false) }
    // 切换账号时间戳：切换后 60 秒内跳过所有账号检测，给新连接足够稳定时间
    var lastSwitchTime by remember { mutableStateOf(0L) }
    // 用户状态验证冷却：至少间隔60秒，防止网络抖动导致误判为账号异常
    var lastUserCheckTime by remember { mutableStateOf(0L) }

    // ── 系统公告弹窗 ──
    var showAnnouncement by remember { mutableStateOf(false) }
    var announcementData by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var hasShownAnnouncement by remember { mutableStateOf(false) }

    // 登录后拉取系统公告（同一 session 只弹一次，检查今日是否已勾选"不再提示"）
    val annPrefs = LocalContext.current.getSharedPreferences("aurora_announcement", android.content.Context.MODE_PRIVATE)
    LaunchedEffect(currentUserId) {
        if (currentUserId > 0 && !hasShownAnnouncement) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            val hiddenDate = annPrefs.getString("hide_date", "")
            if (hiddenDate != today) {
                try {
                    val result = com.aurora.chat.data.api.AuroraApi.fetchActiveAnnouncement()
                    if (result.success && result.data != null && result.data!!.length() > 0
                        && result.data!!.optBoolean("enabled", false)
                        && result.data!!.optString("title", "").isNotBlank()) {
                        announcementData = result.data
                        showAnnouncement = true
                    }
                } catch (_: Exception) { }
            }
            hasShownAnnouncement = true
        }
    }

    /** 执行清空数据操作（彻底抹除，1 字节不留） */
    fun doClearData(ctx: android.content.Context, onLogoutCb: () -> Unit) {
        val uid = com.aurora.chat.data.api.AuroraApi.currentUserId
        val prefsFiles = listOf("aurora_login", "aurora_chat_prefs", "aurora_keepalive",
            "aurora_installed_pkgs", "aurora_ban", "aurora_downloads", "aurora_security",
            "aurora_profile", "login_history", "aurora_download_counts", "aurora_announcement",
            "self_del_$uid")
        for (name in prefsFiles) {
            ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).edit().clear().apply()
        }
        // 删除 self_chat 所有缓存文件（媒体 + JSON + 通知标记）
        try {
            val cacheDir = ctx.cacheDir
            val selfChatDir = java.io.File(cacheDir, "self_chat")
            if (selfChatDir.exists()) selfChatDir.deleteRecursively()
            cacheDir.listFiles()?.filter { it.name.startsWith("self_chat_") }?.forEach { it.delete() }
        } catch (_: Exception) {}
        // 清除 old filesDir 的残留
        try {
            val oldSelfChat = java.io.File(ctx.filesDir, "self_chat")
            if (oldSelfChat.exists()) oldSelfChat.deleteRecursively()
            ctx.filesDir.listFiles()?.filter { it.name.startsWith("self_chat_") }?.forEach { it.delete() }
        } catch (_: Exception) {}
        // 彻底清空数据目录：所有会话消息、对话列表缓存、媒体缓存（等价于卸载重装）
        try {
            val msgDir = java.io.File(ctx.filesDir, "messages")
            if (msgDir.exists()) msgDir.deleteRecursively()
        } catch (_: Exception) {}
        try {
            java.io.File(ctx.filesDir, "conversations_cache.json").delete()
        } catch (_: Exception) {}
        try {
            ctx.getSharedPreferences("msg_store", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
        // 清空进程内消息热缓存、媒体缓存与对话内存缓存，避免退出前残留
        try { com.aurora.chat.ui.chat.messageHotCache.clear() } catch (_: Exception) {}
        try { com.aurora.chat.ui.chat.media.MediaStore.clear() } catch (_: Exception) {}
        com.aurora.chat.ui.viewmodel.ChatViewModel.notifySelfChatRefresh()
        com.aurora.chat.ui.viewmodel.ChatViewModel.release()
        com.aurora.chat.data.local.AvatarCache.clearAll()
        com.aurora.chat.ui.tools.DownloadManager.clearAllRecords(ctx)
        com.aurora.chat.ErrorReporter.clearAll()
        com.aurora.chat.data.local.LocalStorage.logout(ctx)
        com.aurora.chat.data.api.AuroraApi.authToken = null
        com.aurora.chat.data.api.AuroraApi.currentUserId = 0
        TcpService.stop(ctx)
        DownloadManager.closeAll()
        onLogoutCb()
        android.widget.Toast.makeText(ctx, "所有本地数据已清空", android.widget.Toast.LENGTH_LONG).show()
    }

    /** 清空本地的所有聊天记录（各对话消息与本地缓存），保留登录状态与账号数据。 */
    fun clearLocalChatRecords(ctx: android.content.Context) {
        // 1) 清普通会话消息：内存缓存 + 消息文件（filesDir/messages/*.json）
        com.aurora.chat.data.local.LocalMessageStore.clear(ctx)
        // 2) 清会话列表本地缓存：内存缓存 + conversations_cache.json
        com.aurora.chat.data.local.LocalConversationStore.clear(ctx)
        // 3) 清个人对话/DeepSeek/通知中心的本地消息（cacheDir JSON + last_msg 预览 SP）
        listOf("self", "ai", "notification").forEach { key ->
            try {
                java.io.File(ctx.cacheDir, "${key}_chat_${currentUserId}.json").delete()
            } catch (_: Exception) {}
            try {
                ctx.getSharedPreferences("${key}_chat_${currentUserId}", android.content.Context.MODE_PRIVATE)
                    .edit().clear().apply()
            } catch (_: Exception) {}
        }
        // 4) 清理旧版 SharedPreferences 兜底消息
        try {
            ctx.getSharedPreferences("msg_store", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        } catch (_: Exception) {}
        // 5) 清空进程内消息热缓存与媒体缓存
        try { com.aurora.chat.ui.chat.messageHotCache.clear() } catch (_: Exception) {}
        try { com.aurora.chat.ui.chat.media.MediaStore.clear() } catch (_: Exception) {}
        com.aurora.chat.ui.viewmodel.ChatViewModel.notifySelfChatRefresh()
    }

    val context = LocalContext.current
    val animPrefs = LocalStorage.getChatPrefs(context)
    var animMode by remember { mutableStateOf(
        AnimationMode.entries[animPrefs.getInt("animation_mode", 2)]
    ) }
    var advancedAnim by remember { mutableStateOf(animPrefs.getBoolean("advanced_anim", false)) }

    // 应用锁状态
    val hasAnySecurity = SecurityStorage.isPasswordSet(context) ||
        SecurityStorage.isFingerprintEnabled(context) ||
        SecurityStorage.isGestureEnabled(context)
    var appUnlocked by remember {
        mutableStateOf(!SecurityStorage.isSecurityEnabled(context) || !hasAnySecurity)
    }

    // 壁纸刷新 key
    var wallpaperRefreshKey by remember { mutableStateOf(0) }

    // 开发者判断（QQ 号或邮箱任一匹配即视为开发者）
    val isDeveloper = LocalStorage.isDeveloper(currentQQ, currentEmail)
    // 平台管理员判断
    var isPlatformAdmin by remember { mutableStateOf(false) }
    // 是否有细粒度权限
    var hasAnyPermission by remember { mutableStateOf(false) }
    LaunchedEffect(currentUserId) {
        if (currentUserId > 0 && !isDeveloper) {
            try {
                val r = com.aurora.chat.data.api.AuroraApi.isPlatformAdmin()
                if (r.success) isPlatformAdmin = r.data == true
            } catch (_: Exception) { }
            // 检查是否有任何细粒度权限
            try {
                val r = com.aurora.chat.data.api.AuroraApi.getUserPermissions(currentUserId)
                if (r.success && r.data != null && r.data.length() > 0) {
                    hasAnyPermission = true
                }
            } catch (_: Exception) { }
        }
    }

    val canAccessDevMgmt = isDeveloper || isPlatformAdmin || hasAnyPermission

    // 从 ViewModel 获取好友请求
    val friendRequestList by ChatViewModel.friendRequests.collectAsState()
    val friendRequestReceived by ChatViewModel.friendRequestReceived.collectAsState()
    // 聊天标签未读总数（与列表红点同源）
    val chatUnreadCounts by ChatViewModel.unreadCounts.collectAsState()
    var showFriendRequests by remember { mutableStateOf(false) }
    var showMyGroups by remember { mutableStateOf(false) }
    // 好友申请未读数
    var friendRequestUnreadCount by remember { mutableStateOf(0) }
    // friendRequestList 或收到新推送时重新计算未读数
    LaunchedEffect(friendRequestList, friendRequestReceived) {
        friendRequestUnreadCount = friendRequestList.count { it.status == "pending" && it.fromUserId != currentUserId }
        // 如果好友申请面板开着，不显示红点
        if (showFriendRequests) friendRequestUnreadCount = 0
    }
    // 打开好友申请时清零
    LaunchedEffect(showFriendRequests) {
        if (showFriendRequests) friendRequestUnreadCount = 0
    }
    var showSearchUser by remember { mutableStateOf(false) }
    var showSearchGroup by remember { mutableStateOf(false) }
    var searchGroupInitialId by remember { mutableStateOf(0L) }
    var plusMenuOpen by remember { mutableStateOf(false) }

    // 加载好友请求（currentUserId 变化时自动重新加载，确保切换账号后刷新数据）
    LaunchedEffect(currentUserId) {
        if (currentUserId > 0) {
            ChatViewModel.loadFriendRequests()
            com.aurora.chat.ui.tools.DownloadManager.loadCompletedItems(context)
        }
    }

    // 启动时检查当前用户是否在服务器上有头像，没有才弹上传框（切换账号时延迟执行）
    LaunchedEffect(currentUserId) {
        if (currentUserId > 0) {
            kotlinx.coroutines.delay(500) // 等待切换完成
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val avatarFile = com.aurora.chat.data.local.LocalStorage.getMyAvatarFile(context, currentUserId)
                if (avatarFile.exists()) return@withContext
                // 用专门的 API 检查服务器是否有真实头像文件（不返回默认头像）
                try {
                    val bytes = com.aurora.chat.data.api.AuroraApi.loadAvatarBytes(currentUserId)
                    val hasAvatar = bytes != null && bytes.size > 100
                    if (hasAvatar) {
                        com.aurora.chat.data.repository.ChatRepository.loadAvatar(context, currentUserId)
                        return@withContext
                    }
                } catch (_: Exception) {
                    // 网络失败时不弹上传框，等下次重试
                    return@withContext
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) { showForceAvatar = true }
            }
        }
    }

    // 真实的连接状态监测：定期 health 检查 + 设备网络状态
    LaunchedEffect(Unit) {
        // 辅助函数：health 检测
        suspend fun checkHealth(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url("${com.aurora.chat.data.api.AuroraApi.serverUrl}/api/health").build()
                com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp -> resp.code == 200 }
            } catch (_: Exception) { false }
        }

        suspend fun updateStatus(healthy: Boolean, deviceOnline: Boolean) {
            if (healthy) {
                if (connectionStatus != ConnStatus.Connected) {
                    connectionStatus = ConnStatus.Connected
                    statusBanner = StatusBanner("已连接", isError = false, durationMs = 2000)
                } else {
                    statusBanner = null
                }
            } else if (!deviceOnline) {
                connectionStatus = ConnStatus.Disconnected
                statusBanner = StatusBanner("断连 · 正在尝试重连...", isError = true, durationMs = 0)
            } else {
                connectionStatus = ConnStatus.Disconnected
                statusBanner = StatusBanner("断连 · 正在尝试重连...", isError = true, durationMs = 0)
            }
        }

        // 初始检测
        connectionStatus = ConnStatus.Connecting
        statusBanner = StatusBanner("连接中...", isError = false, durationMs = 5000)
        val initialHealth = checkHealth()
        val initialOnline = com.aurora.chat.util.NetworkMonitor.isOnline.value
        updateStatus(initialHealth, initialOnline)
        // 健康通过后立即验证当前用户账号是否正常（启动时首次验证不受冷却限制）
        if (initialHealth && currentUserId > 0) {
            lastUserCheckTime = System.currentTimeMillis()
            val shouldLogout = ChatRepository.checkAndLogoutCurrentUser(context, currentUserId)
            if (shouldLogout) {
                onLogout()
            }
            // 确保 TCP 服务已启动
            try { TcpService.start(context) } catch (_: Exception) {}
            // 注册网络切换监听（WiFi/蜂窝切换时自动重置 TCP）
            com.aurora.chat.KeepAliveManager.registerNetworkListener(context)
        }

        // 持续监测：每 5 秒检测一次 health + TCP 保活 + 用户校验 + 封禁检测
        while (true) {
            val healthy = checkHealth()
            val deviceOnline = com.aurora.chat.util.NetworkMonitor.isOnline.value
            updateStatus(healthy, deviceOnline)
            // 每次健康检测通过后也验证用户（切换账号后60秒内跳过，给新连接足够稳定时间）
            // 每 5 秒循环中：用户验证至少间隔 60 秒，避免网络抖动频繁触发
            val userCheckInterval = 60000L
            if (healthy && currentUserId > 0
                && System.currentTimeMillis() - lastSwitchTime > 60000
                && System.currentTimeMillis() - lastUserCheckTime > userCheckInterval) {
                lastUserCheckTime = System.currentTimeMillis()
                val shouldLogout = ChatRepository.checkAndLogoutCurrentUser(context, currentUserId)
                if (shouldLogout) {
                    onLogout()
                }
                // TCP 保活：尝试重启可能被系统杀掉的 TCP 服务（已有服务则无操作）
                try {
                    com.aurora.chat.TcpService.start(context)
                } catch (_: Exception) {}
                // 定时检查本地封禁记录（处理 auth_denied 后写入的封禁信息）
                if (!showBanOverlay) {
                    val banPrefsCheck = context.getSharedPreferences("aurora_ban", android.content.Context.MODE_PRIVATE)
                    val localExp = banPrefsCheck.getLong("expires_at", 0L)
                    val now = System.currentTimeMillis() / 1000
                    if (localExp > now) {
                        banReason = banPrefsCheck.getString("reason", "") ?: ""
                        banExpiresAt = localExp
                        banDuration = banPrefsCheck.getLong("duration", 0L)
                        banUnbanPopupMessage = banPrefsCheck.getString("unban_popup_message", "") ?: ""
                        showBanOverlay = true
                    }
                }
            }
            kotlinx.coroutines.delay(5000)
        }
    }

    // ── 封禁检测：启动时检查本地缓存 + 服务端查询 ──
    LaunchedEffect(currentUserId) {
        if (currentUserId <= 0) return@LaunchedEffect
        // 1. 检查本地 TCP 推送留下的封禁记录
        val localExpiresAt = banPrefs.getLong("expires_at", 0L)
        val now = System.currentTimeMillis() / 1000
        if (localExpiresAt > now) {
            banReason = banPrefs.getString("reason", "") ?: ""
            banExpiresAt = localExpiresAt
            banDuration = banPrefs.getLong("duration", 0L)
            banUnbanPopupMessage = banPrefs.getString("unban_popup_message", "") ?: ""
            showBanOverlay = true
        } else {
            // 2. 本地无记录时向服务器查询
            try {
                val result = com.aurora.chat.data.api.AuroraApi.checkBanStatus()
                if (result.success && result.data != null) {
                    val serverExpires = result.data.optLong("expires_at", 0L)
                    if (serverExpires > now) {
                        banReason = result.data.optString("reason", "")
                        banExpiresAt = serverExpires
                        banDuration = result.data.optLong("expires_at", 0L) - result.data.optLong("banned_at", 0L)
                        banUnbanPopupMessage = result.data.optString("unban_popup_message", "")
                        showBanOverlay = true
                    } else if (serverExpires > 0 && serverExpires <= now) {
                        banPrefs.edit().clear().apply()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ── 持续监控封禁状态（每500ms检查SharedPreferences + PendingBan 即时通知） ──
    LaunchedEffect(currentUserId) {
        if (currentUserId <= 0) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(500)
            val now = System.currentTimeMillis() / 1000
            //  优先检查 PendingBan 即时通知（TcpService 直接设置）
            if (com.aurora.chat.PendingBan.hasPending && !showBanOverlay) {
                com.aurora.chat.PendingBan.hasPending = false
                val pb = com.aurora.chat.PendingBan
                banExpiresAt = pb.expiresAt
                banReason = pb.reason
                banDuration = pb.duration
                banUnbanPopupMessage = pb.unbanPopupMessage
                showBanOverlay = true
            }
            if (com.aurora.chat.PendingBan.hasUnban && showBanOverlay) {
                com.aurora.chat.PendingBan.hasUnban = false
                val pb = com.aurora.chat.PendingBan
                showBanOverlay = false
                if (pb.unbanMessage.isNotEmpty()) {
                    unbanPopupText = pb.unbanMessage
                    showUnbanPopup = true
                }
            }
            // 后备检测 SharedPreferences
            val exp = banPrefs.getLong("expires_at", 0L)
            val trigger = banPrefs.getInt("unban_trigger", 0)
            // 检测解封
            if (trigger > 0 && showBanOverlay) {
                val msg = banPrefs.getString("unban_popup_message", "") ?: ""
                showBanOverlay = false
                if (msg.isNotEmpty()) {
                    unbanPopupText = msg
                    showUnbanPopup = true
                }
                banPrefs.edit().putInt("unban_trigger", 0).apply()
            }
            // 检测封禁
            if (exp > now && !showBanOverlay) {
                banReason = banPrefs.getString("reason", "") ?: ""
                banExpiresAt = exp
                banDuration = banPrefs.getLong("duration", 0L)
                banUnbanPopupMessage = banPrefs.getString("unban_popup_message", "") ?: ""
                showBanOverlay = true
            }
            // 检测封禁过期 - 自动清除
            if (exp > 0 && exp <= now && showBanOverlay) {
                showBanOverlay = false
                banPrefs.edit().clear().apply()
            }
        }
    }

    // ── 后台权限引导改为用户手动在「保活设置」页开启，避免每次启动强制跳系统设置页造成死循环 ──

    // 从通知点击进入时自动打开好友申请面板
    LaunchedEffect(openFriendRequests) {
        if (openFriendRequests) {
            showFriendRequests = true
        }
    }

    // 从 TCP 通知点击进入时自动打开对应对话
    var lastNotifCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentUserId, lastNotifCount) {
        if (currentUserId > 0) {
            val prefs = context.getSharedPreferences("aurora_notif", Context.MODE_PRIVATE)
            val openChatId = prefs.getLong("open_chat_id", 0L)
            val openChatName = prefs.getString("open_chat_name", "") ?: ""
            if (openChatId != 0L) {
                chatFriendId = openChatId
                chatFriendName = openChatName
                chatOpen = true
                currentTab = AuroraTab.Chat
                prefs.edit().remove("open_chat_id").remove("open_chat_name").apply()
            }
        }
    }
    // 监听外部通知计数器变化
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val current = MainActivity.pendingNotifCount
            if (current != lastNotifCount) {
                lastNotifCount = current
            }
        }
    }

    // 切换到聊天 Tab 时刷新
    LaunchedEffect(currentTab) {
        if (currentTab == AuroraTab.Chat) chatRefreshKey++
        // 每次切换板块都校验当前用户是否仍存在于服务器（切换账号后60秒内跳过）
        val tabUserCheckInterval = 60000L
        if (System.currentTimeMillis() - lastSwitchTime > 60000
            && System.currentTimeMillis() - lastUserCheckTime > tabUserCheckInterval) {
            lastUserCheckTime = System.currentTimeMillis()
            launch {
                val shouldLogout = ChatRepository.checkAndLogoutCurrentUser(context, currentUserId)
                if (shouldLogout) onLogout()
            }
        }
    }

    // 从悬浮球点击"打开下载管理"时，切换到工具 Tab 并显示下载队列
    LaunchedEffect(DownloadManager.navigateToDownloadManager.value) {
        if (DownloadManager.navigateToDownloadManager.value) {
            currentTab = AuroraTab.Server
            DownloadManager.navigateToDownloadManager.value = false
        }
    }

    // 搜索用户 —— 面板滑入动画（300ms 快进快出）
    val searchUserAnim = remember { Animatable(1f) }
    LaunchedEffect(showSearchUser) {
        if (showSearchUser) searchUserAnim.animateTo(0f, tween(300))
        else searchUserAnim.animateTo(1f, tween(300))
    }

    // 搜索群聊
    val searchGroupAnim = remember { Animatable(1f) }
    LaunchedEffect(showSearchGroup) {
        if (showSearchGroup) searchGroupAnim.animateTo(0f, tween(300))
        else searchGroupAnim.animateTo(1f, tween(300))
    }

    // 返回处理
    BackHandler(enabled = chatOpen) { chatOpen = false }
    BackHandler(enabled = showDeveloperManagement) { showDeveloperManagement = false }
    BackHandler(enabled = showCreateGroup) { showCreateGroup = false }
    BackHandler(enabled = showSearchUser) { showSearchUser = false }
    BackHandler(enabled = showSearchGroup) { showSearchGroup = false }
    BackHandler(enabled = showFriendRequests) { showFriendRequests = false }
    BackHandler(enabled = showMyGroups) { showMyGroups = false }
    BackHandler(enabled = showSettings && !showSwitchAccount) { showSettings = false }
    BackHandler(enabled = showSwitchAccount) { showSwitchAccount = false }

    BackHandler(enabled = showPrivacy) { showPrivacy = false }
    BackHandler(enabled = showMore) { showMore = false }
    BackHandler(enabled = showProvideFreeApi) { showProvideFreeApi = false }
    BackHandler(enabled = showOpenApply) { showOpenApply = false }
    BackHandler(enabled = showEditProfile) { showEditProfile = false }
    BackHandler(enabled = showAbout && !showSettings) { showAbout = false }
    BackHandler(enabled = showCommunitySub) { showCommunitySub = false }
    BackHandler(enabled = communityCreatePost) { communityCreatePost = false }
    BackHandler(enabled = DownloadManager.isShowing.value && !DownloadManager.isMinimized.value) {
        DownloadManager.minimize()
    }

    // 帖子详情页返回
    BackHandler(enabled = showPostDetail) { showPostDetail = false }

    // 我的发布页返回
    BackHandler(enabled = showMyPosts) { showMyPosts = false }

    // 我的二维码 / 扫一扫 / 扫码用户资料 返回
    BackHandler(enabled = showScannedProfile) { showScannedProfile = false }
    BackHandler(enabled = showScan) { showScan = false }
    BackHandler(enabled = showMyQr) { showMyQr = false }

    // ── 双击返回退出（仅在所有面板/弹窗都关闭时生效） ──
    val isAtRoot = !chatOpen && !showDeveloperManagement
            && !showCreateGroup && !showSearchUser && !showSearchGroup
            && !showFriendRequests && !showMyGroups && !showSettings && !showSwitchAccount
            && !showMessagePush && !showAbout && !showDonate && !showMore && !showProvideFreeApi && !showOpenApply && !showAvatarViewer
            && !showCommunitySub && !communityCreatePost && !showForceAvatar
            && !plusMenuOpen && !showLogoutDialog && !showClearDataDialog
            && !showClearDataPasswordDialog && !showErrorLog && !showCrashIntercept
            && !showPostDetail && appUnlocked
            && !showMyQr && !showScan && !showScannedProfile
            && !showUserProfile
            && !(DownloadManager.isShowing.value && !DownloadManager.isMinimized.value)
            && updateData == null && !showBanOverlay && !showUnbanPopup
    var backPressedTime by remember { mutableStateOf(0L) }
    val rootContext = LocalContext.current
    BackHandler(enabled = isAtRoot) {
        val now = System.currentTimeMillis()
        if (now - backPressedTime < 2000) {
            (rootContext as? ComponentActivity)?.finishAffinity()
        } else {
            backPressedTime = now
            Toast.makeText(rootContext, "再按一次退出应用", Toast.LENGTH_SHORT).show()
        }
    }

    // 用户资料页返回：优先于"双击退出"，返回即关闭资料页回到上一层
    // （放在 isAtRoot 之后注册，BackHandler 后注册者优先；资料页可叠在帖子详情/联系人之上）
    BackHandler(enabled = showUserProfile) { showUserProfile = false }

    // 读取当前界面设置
    val wallpaperFullscreen = remember(wallpaperRefreshKey) { WallpaperStorage.isFullscreen(context) }
    val noDivider = remember(wallpaperRefreshKey) { WallpaperStorage.isDividerHidden(context) }

    CompositionLocalProvider(LocalShowDividers provides !noDivider) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // 非全屏时，顶部/底部栏用白色背景，界面内容区域保持白色
        val barBg = if (wallpaperFullscreen) Color.Transparent else Color.White

        Column(modifier = Modifier.fillMaxSize()) {
            AuroraTopBar(
                status = connectionStatus,
                statusBanner = statusBanner,
                onBannerDismiss = { statusBanner = null },
                onPlusClick = { plusMenuOpen = !plusMenuOpen },
                backgroundColor = barBg,
                showDivider = !noDivider,
                plusMenuOpen = plusMenuOpen,
                onDismissPlusMenu = { plusMenuOpen = false },
                onSearchUser = { showSearchUser = true; showSearchGroup = false; plusMenuOpen = false },
                onSearchGroup = { showSearchGroup = true; showSearchUser = false; plusMenuOpen = false },
                onCreateGroup = { showCreateGroup = true; plusMenuOpen = false },
                onScanQr = { showScan = true; plusMenuOpen = false }
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // 直接按 currentTab 组合当前板块：无切换动画、不常驻。
                // Coil 全局内存/磁盘缓存保证切回时图片秒出，无需把整屏常驻（常驻会同时布局+加载 4 个板块，放大成卡死）
                when (currentTab) {
                        AuroraTab.Chat -> {
                        ChatScreen(
                            currentUserId = currentUserId,
                            refreshKey = chatRefreshKey,
                            listState = chatListState,
                            scrollRequest = chatScrollRequest,
                            onScrollConsumed = { chatScrollRequest = null },
                            onOpenChat = { fId, fName ->
                                chatFriendId = fId
                                chatFriendName = fName
                                chatOpen = true
                            }
                        )
                        }
                        AuroraTab.Server -> {
                        com.aurora.chat.ui.contacts.ContactsScreen(
                            currentUserId = currentUserId,
                            onOpenChat = { fId, fName ->
                                chatFriendId = fId
                                chatFriendName = fName
                                chatOpen = true
                            },
                            onOpenProfile = { uid, name ->
                                profileUserId = uid
                                profileUserName = name
                                showUserProfile = true
                            },
                            friendRequestUnreadCount = friendRequestUnreadCount,
                            onOpenFriendRequests = {
                                showFriendRequests = true
                                ChatViewModel.loadFriendRequests()
                            },
                            onOpenGroups = { showMyGroups = true }
                        )
                        }
                        AuroraTab.Community -> {
                        CommunityScreen(
                            currentUserId = currentUserId,
                            onOpenSubPage = { title ->
                                communitySubTitle = title
                                showCommunitySub = true
                            },
                            onOpenPostDetail = { postId, postType ->
                                postDetailId = postId
                                postDetailType = postType
                                showPostDetail = true
                            },
                            onOpenUserProfile = { uid, name ->
                                profileUserId = uid
                                profileUserName = name
                                showUserProfile = true
                            },
                            onOpenCreatePost = { communityCreatePost = true },
                            refreshKey = communityRefreshKey,
                            onOpenChat = { uid, name ->
                                chatFriendId = uid
                                chatFriendName = name
                                chatOpen = true
                                currentTab = AuroraTab.Chat
                            }
                        )
                        }
                        AuroraTab.Profile -> {
                        ProfileScreen(
                            currentUserId,
                            onSwitchAccount = { showSwitchAccount = true },
                            onLogout = { showLogoutDialog = true },
                            isDeveloper = canAccessDevMgmt,
                            activityScreenOpen = showActivity,
                            onActivityScreenOpenChange = { showActivity = it },
                            onShowMyQr = { showMyQr = true },
                            onShowAvatar = { bmp -> avatarViewerBitmap = bmp; showAvatarViewer = true },
                            onEditProfile = { showEditProfile = true },
                            onPrivacySettings = { showPrivacy = true },
                            onWebsiteHosting = { showWebsiteHosting = true },
                            onBottle = { showBottle = true },
                            onMore = { showMore = true },
                            onMyPosts = { showMyPosts = true },
                            onSettings = { showSettings = true },
                            onAbout = { showAbout = true },
                            onRedeemCardKey = { showRedeemCardKey = true },
                            onMessagePush = { showMessagePush = true },
                            onSaveLocalData = {
                                if (!savingAll) backupSaveLauncher.launch("aurora_backup_${currentUserId}_${System.currentTimeMillis()}.abk")
                            },
                            onRestoreLocalData = {
                                if (!resetting) backupOpenLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
                            },
                            onPullMessages = {
                                if (!pulling30) {
                                    pulling30 = true; pullDone = 0; pullTotal = 0
                                    scope.launch(Dispatchers.IO) {
                                        val res = com.aurora.chat.data.local.DataBackup.pullRecentMessages(
                                            context, currentUserId
                                        ) { done, total, _ ->
                                            scope.launch(Dispatchers.Main) {
                                                pullDone = done; pullTotal = total
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            pulling30 = false
                                            val msg = if (res.success)
                                                "拉取成功，共拉取 ${res.totalPulled} 条"
                                            else "拉取失败：${res.error}"
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        if (res.success) {
                                            // 刷新会话列表 + 清空消息热缓存，使拉取到的消息在各对话可见
                                            ChatViewModel.loadConversations(context, showLoading = false, force = true)
                                            ChatViewModel.clearHotCache()
                                        }
                                        }
                                    }
                                }
                            },
                            onClearChatRecords = { showClearChatRecordsConfirm = true }
                        )
                        }
                }
                }
            AuroraBottomNav(
                currentTab = currentTab,
                onTabSelected = {
                    // 原"双击聊天滚到未读"改为：再次点击已选中的聊天 tab 触发（单击即时，无双击等待延迟）
                    if (it == AuroraTab.Chat && currentTab == AuroraTab.Chat) {
                        if (chatOpen) chatOpen = false
                        chatScrollRequest = System.nanoTime()
                    }
                    currentTab = it
                },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                backgroundColor = barBg,
                showDivider = !noDivider,
                badges = mapOf(
                    AuroraTab.Server to friendRequestUnreadCount,
                    AuroraTab.Chat to chatUnreadCounts.values.sum()
                )
            )
        }

        // 好友申请
        AnimatedVisibility(
            visible = showFriendRequests,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(
                backgroundColor = Color.Black.copy(alpha = 0.4f),
                onClick = { showFriendRequests = false }
            )
        }
        AnimatedVisibility(
            visible = showFriendRequests,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            FriendRequestScreen(
                requests = remember(friendRequestList, currentUserId) {
                    friendRequestList.map { fr ->
                        val outgoing = fr.fromUserId == currentUserId
                        FriendRequest(
                            id = fr.id,
                            name = if (outgoing) fr.toUsername else fr.fromUsername,
                            status = when (fr.status) {
                                "accepted" -> RequestStatus.ACCEPTED
                                "rejected" -> RequestStatus.REJECTED
                                else -> RequestStatus.PENDING
                            },
                            time = fr.createdAt * 1000,
                            signature = fr.fromEmail,
                            greeting = fr.greeting,
                            fromUserId = fr.fromUserId,
                            toUserId = fr.toUserId,
                            isOutgoing = outgoing
                        )
                    }
                },
                onAccept = { index ->
                    val fr = friendRequestList.getOrNull(index) ?: return@FriendRequestScreen
                    ChatViewModel.respondFriendRequest(fr.id, "accept", context) { success, msg ->
                        Toast.makeText(context, if (success) "已同意好友申请" else "操作失败: $msg", Toast.LENGTH_SHORT).show()
                        if (success) chatRefreshKey++
                    }
                },
                onReject = { index ->
                    val fr = friendRequestList.getOrNull(index) ?: return@FriendRequestScreen
                    ChatViewModel.respondFriendRequest(fr.id, "reject", context) { success, msg ->
                        if (!success) Toast.makeText(context, "操作失败: $msg", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancel = { index ->
                    val fr = friendRequestList.getOrNull(index) ?: return@FriendRequestScreen
                    ChatViewModel.cancelFriendRequest(fr.id, context) { success, msg ->
                        if (!success) Toast.makeText(context, "取消失败: $msg", Toast.LENGTH_SHORT).show()
                    }
                },
                onAddFriend = { showSearchUser = true },
                onDismiss = { showFriendRequests = false }
            )
        }

        // ========== 群聊列表（我的群聊） ==========
        AnimatedVisibility(
            visible = showMyGroups,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(
                backgroundColor = Color.Black.copy(alpha = 0.4f),
                onClick = { showMyGroups = false }
            )
        }
        AnimatedVisibility(
            visible = showMyGroups,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            MyGroupsScreen(
                currentUserId = currentUserId,
                onOpenGroupDetail = { convId, groupName ->
                    // 只有从群详情点「发消息」才会走到这里：直接进入对话（不经过搜索群聊）
                    showMyGroups = false
                    chatFriendId = convId
                    chatFriendName = groupName
                    chatOpen = true
                    currentTab = AuroraTab.Chat
                },
                onDismiss = { showMyGroups = false }
            )
        }

        // ========== 搜索用户（永不从树中移除，Animatable 持久驱动） ==========
        val userAlpha = (1f - searchUserAnim.value).coerceIn(0f, 1f)
        val userSlideX = (searchUserAnim.value * 2000)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(userAlpha)
                .offset(x = userSlideX.dp)
        ) {
            // 遮罩
            EventBlocker(backgroundColor = Color.Black.copy(alpha = 0.3f), onClick = { showSearchUser = false })
            // 面板
            SearchUserScreen(
                currentUserId = currentUserId,
                onDismiss = { showSearchUser = false },
                visible = showSearchUser
            )
        }

        // ========== 搜索群聊（永不从树中移除） ==========
        val groupAlpha = (1f - searchGroupAnim.value).coerceIn(0f, 1f)
        val groupSlideX = (searchGroupAnim.value * 2000)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
                .alpha(groupAlpha)
                .offset(x = groupSlideX.dp)
        ) {
            // 遮罩
            EventBlocker(backgroundColor = Color.Black.copy(alpha = 0.3f), onClick = { showSearchGroup = false })
            // 面板
            SearchGroupScreen(
                currentUserId = currentUserId,
                onDismiss = {
                    showSearchGroup = false
                    searchGroupInitialId = 0L
                },
                onOpenGroupChat = { groupConvId, groupName ->
                    showSearchGroup = false
                    searchGroupInitialId = 0L
                    chatFriendId = groupConvId
                    chatFriendName = groupName
                    chatOpen = true
                },
                initialGroupId = searchGroupInitialId,
                visible = showSearchGroup
            )
        }

        // 保存全部数据进度遮罩
        if (savingAll) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在保存全部数据到本地…", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // 重置本地数据遮罩
        if (resetting) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在重置本地数据…", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // 拉取30天内未拉取的消息：进度遮罩
        if (pulling30) {
            val pct = if (pullTotal > 0) pullDone * 100 / pullTotal else 0
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = if (pullTotal > 0) pullDone.toFloat() / pullTotal else 0f,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(0.6f).height(6.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在拉取30天内未拉取的消息… $pct%", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // 将数据重置到本地：确认框
        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("将数据重置到本地", fontWeight = FontWeight.Bold) },
                text = { Text("将从最近一份本地备份覆盖当前本地数据（对话列表、消息、个人信息）。此操作仅本地生效，不会影响服务器。确定继续吗？", fontSize = 14.sp, color = Color(0xFF374151)) },
                confirmButton = {
                    TextButton(onClick = {
                        showResetConfirm = false
                        val uri = resetUri
                        if (uri != null && !resetting) {
                            resetting = true
                            scope.launch(Dispatchers.IO) {
                                val res = com.aurora.chat.data.local.DataBackup.resetFromUri(backupCtx, currentUserId, uri)
                                withContext(Dispatchers.Main) {
                                    resetting = false
                                    val msg = if (res.success)
                                        "重置成功：${res.conversations} 个会话、${res.messages} 条消息"
                                    else "重置失败：${res.error}"
                                    Toast.makeText(backupCtx, msg, Toast.LENGTH_LONG).show()
                                    if (res.success) {
                                        // 刷新会话列表 + 清空消息热缓存，使恢复的数据在各对话可见
                                        ChatViewModel.loadConversations(backupCtx, showLoading = false, force = true)
                                        ChatViewModel.clearHotCache()
                                    }
                                }
                            }
                        }
                    }) { Text("确定", color = Color(0xFF1E40AF)) }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("取消", color = Color(0xFF6B7280)) }
                }
            )
        }

        // 清空所有聊天记录：确认框
        if (showClearChatRecordsConfirm) {
            AlertDialog(
                onDismissRequest = { showClearChatRecordsConfirm = false },
                title = { Text("清空所有聊天记录", fontWeight = FontWeight.Bold) },
                text = { Text("将清空本地的所有聊天记录（各对话的消息与缓存）。登录状态与账号数据保留。清空后可通过「拉取30天内未拉取的消息」重新拉取。确定继续吗？", fontSize = 14.sp, color = Color(0xFF374151)) },
                confirmButton = {
                    TextButton(onClick = {
                        showClearChatRecordsConfirm = false
                        clearLocalChatRecords(backupCtx)
                        Toast.makeText(backupCtx, "聊天记录已清空", Toast.LENGTH_LONG).show()
                        ChatViewModel.loadConversations(context, showLoading = true, force = true)
                    }) { Text("确定", color = Color(0xFFDC2626)) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearChatRecordsConfirm = false }) { Text("取消", color = Color(0xFF6B7280)) }
                }
            )
        }

        // 其他：独立全屏界面（由右侧滑入），覆盖顶部标题与底部导航
        AnimatedVisibility(
            visible = showMore,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MorePage(
                    currentEmail = currentEmail,
                    onClose = { showMore = false },
                    onAbout = { showMore = false; showAbout = true },
                    onRedeemCardKey = { showMore = false; showRedeemCardKey = true },
                    onMessagePush = { showMore = false; showMessagePush = true },
                    onSaveLocalData = {
                        showMore = false
                        if (!savingAll) backupSaveLauncher.launch("aurora_backup_${currentUserId}_${System.currentTimeMillis()}.abk")
                    },
                    onRestoreLocalData = {
                        showMore = false
                        if (!resetting) backupOpenLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
                    },
                    onPullMessages = {
                        showMore = false
                        if (!pulling30) {
                            pulling30 = true; pullDone = 0; pullTotal = 0
                            scope.launch(Dispatchers.IO) {
                                val res = com.aurora.chat.data.local.DataBackup.pullRecentMessages(
                                    context, currentUserId
                                ) { done, total, _ ->
                                    scope.launch(Dispatchers.Main) {
                                        pullDone = done; pullTotal = total
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    pulling30 = false
                                    val msg = if (res.success)
                                        "拉取成功，共拉取 ${res.totalPulled} 条"
                                    else "拉取失败：${res.error}"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onClearChatRecords = { showMore = false; showClearChatRecordsConfirm = true },
                    onProvideFreeApi = { showMore = false; showProvideFreeApi = true },
                    onOpenApplyThirdParty = { showMore = false; showOpenApply = true }
                )
            }
        }

        // ==================== 提供免费 API（全屏空白页，左滑返回） ====================
        AnimatedVisibility(
            visible = showProvideFreeApi,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ProvideFreeApiScreen(
                    onClose = { showProvideFreeApi = false }
                )
            }
        }

        // ==================== 申请第三方接入（独立全屏页，不复用开发者管理壳层） ====================
        AnimatedVisibility(
            visible = showOpenApply,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                OpenApplyScreen(
                    onClose = { showOpenApply = false }
                )
            }
        }

        // 隐私设置：独立全屏界面（由右侧滑入）
        AnimatedVisibility(
            visible = showPrivacy,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PrivacySettingsPage(onClose = { showPrivacy = false })
            }
        }

        // 关于面板
        AnimatedVisibility(
            visible = showAbout && !showSettings,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(backgroundColor = Color.Black.copy(alpha = 0.4f), onClick = { showAbout = false })
        }
        AnimatedVisibility(
            visible = showAbout && !showSettings,
            enter = slideInHorizontally(spring()) { it },
            exit = slideOutHorizontally(spring()) { it }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(240.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { }
                ) {
                    AboutPage(onDonate = { showDonate = true })
                }
            }
        }

        // 赞赏
        if (showDonate) {
            DonateDialog(onDismiss = { showDonate = false })
        }

        // 设置：独立全屏界面（由右侧滑入），内容即原侧滑面板的整套设置
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                    SettingsPage(
                        currentAnimMode = animMode,
                        onAnimModeSelect = { mode ->
                            animMode = mode
                            animPrefs.edit().putInt("animation_mode", mode.value).apply()
                        },
                        advancedAnim = advancedAnim,
                        onAdvancedToggle = { on ->
                            advancedAnim = on
                            animPrefs.edit().putBoolean("advanced_anim", on).apply()
                        },
                        onClose = { showSettings = false },
                        onSwitchAccount = { showSwitchAccount = true },
                        // 与「我的」页同源:开发者/平台管理员/任一细粒度权限均可见开发者管理入口
                        isDeveloper = canAccessDevMgmt,
                        onOpenDeveloperManagement = { showDeveloperManagement = true },
                        onOpenErrorLog = {
                            showSettings = false
                            showErrorLog = true
                        },
                        onOpenCrashIntercept = {
                            showSettings = false
                            showCrashIntercept = true
                        },
                        onOpenTerminal = {
                            showSettings = false
                            context.startActivity(android.content.Intent(context, com.aurora.chat.ui.server.TerminalActivity::class.java))
                        },
                        onClearCache = {
                            // 真实清理缓存：清空头像缓存、媒体缓存、错误日志、下载记录
                            com.aurora.chat.data.local.AvatarCache.clearAll()
                            com.aurora.chat.data.local.MediaCache.clearAll(context)
                            com.aurora.chat.ErrorReporter.clearAll()
                            com.aurora.chat.ui.tools.DownloadManager.clearAllRecords(context)
                            androidx.compose.material3.SnackbarHostState()
                            android.widget.Toast.makeText(context, "缓存已清理", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onClearData = {
                            showClearDataDialog = true
                        },
                        onLogout = {
                            showSettings = false
                            showLogoutDialog = true
                        },
                        currentAccount = currentEmail,
                        onAccountDeleted = {
                            val uid = AuroraApi.currentUserId
                            com.aurora.chat.ui.profile.OnlineTimeTracker.stop(context)
                            com.aurora.chat.ui.viewmodel.ChatViewModel.removeUserData(uid)
                            LocalStorage.logout(context)
                            TcpService.stop(context)
                            DownloadManager.closeAll()
                            showSettings = false
                            onLoggedInChange(false)
                            onUserIdChange(0)
                            Toast.makeText(context, "账号已注销，已返回登录页", Toast.LENGTH_LONG).show()
                        }
                    )
            }
        }

        // 聊天界面
        AnimatedVisibility(
            visible = chatOpen,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(
                backgroundColor = Color.Black.copy(alpha = 0.4f),
                onClick = { chatOpen = false; chatRefreshKey++ }
            )
        }
        AnimatedVisibility(
            visible = chatOpen,
            enter = slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 300f)) { it } + fadeIn(spring()),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            //  不用 key()！切对话时不销毁重建，只更新 friendId 参数触发 LaunchedEffect
            ChatConversationScreen(
                currentUserId = currentUserId,
                currentUserName = com.aurora.chat.data.api.AuroraApi.currentUserName,
                friendId = chatFriendId,
                friendName = chatFriendName,
                onBack = {
                    chatOpen = false
                    chatRefreshKey++
                },
                onBroadcastSent = {
                    // 开发者广播：发送后直接跳到官方群，实时观看消息一条条刷出
                    chatFriendId = -1001L
                    chatFriendName = "官方群聊"
                    chatOpen = true
                },
                onOpenPostDetail = { postId ->
                    // 根治：不关闭聊天界面。帖子详情作为覆盖层叠在聊天之上，
                    // 返回时聊天原地还在，零重建、零卡顿。
                    postDetailId = postId
                    showPostDetail = true
                },
                    onOpenGroupDetail = { groupId ->
                        // 根治：不关闭聊天界面。群详情作为覆盖层叠在聊天之上，
                        // 返回时聊天原地还在，零重建、零卡顿。
                        showSearchGroup = true
                        searchGroupInitialId = groupId
                    },
                    onOpenNewChat = { uid, name ->
                        chatFriendId = uid
                        chatFriendName = name
                        chatOpen = true
                    },
                onOpenAiSandbox = { showAiSandbox = true },
                onRechargeToken = {
                    scope.launch {
                        chatOpen = false
                        kotlinx.coroutines.delay(450)
                        currentTab = AuroraTab.Profile
                        kotlinx.coroutines.delay(450)
                        showTokenCard = true
                    }
                },
                onTokenExhausted = { showTokenCard = true },
                onOpenJoinRequestFullScreen = { arr, gid ->
                    joinRequestData = arr
                    joinRequestGroupId = gid
                    showJoinRequestFullScreen = true
                },
                onAppAction = { action ->
                    when (action.type) {
                        com.aurora.chat.ui.chat.AiChatManager.AppActionType.SWITCH_TAB -> {
                            val tab = action.args.optString("tab", "chat").trim().lowercase()
                            val target = when (tab) {
                                "contacts" -> AuroraTab.Server
                                "community" -> AuroraTab.Community
                                "profile" -> AuroraTab.Profile
                                else -> AuroraTab.Chat
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                currentTab = target
                                chatOpen = false
                                chatRefreshKey++
                            }
                            com.aurora.chat.ui.chat.AiChatManager.AppActionResult(
                                ok = true,
                                message = "已切换到「$tab」页并退出 AI 对话",
                                extra = org.json.JSONObject().put("tab", tab)
                            )
                        }
                        else -> com.aurora.chat.ui.chat.AiChatManager.AppActionResult(
                            ok = false,
                            message = "不支持的 UI 动作：${action.type}"
                        )
                    }
                }
            )
            }
        }

        // AI 沙盒（全屏占位界面，横向滑入）
        AnimatedVisibility(
            visible = showAiSandbox,
            enter = slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 300f)) { it } + fadeIn(spring()),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            AiSandboxScreen(
                state = sandboxState,
                onBack = { showAiSandbox = false },
                userId = currentUserId,
                onRechargeToken = {
                    scope.launch {
                        showAiSandbox = false
                        kotlinx.coroutines.delay(450)
                        currentTab = AuroraTab.Profile
                        kotlinx.coroutines.delay(450)
                        showTokenCard = true
                    }
                },
                onTokenExhausted = { showTokenCard = true }
            )
        }

        // ==================== 我的二维码 ====================
        // 遮罩层（拦截穿透，防止点到背面"我的"页头像等）
        AnimatedVisibility(
            visible = showMyQr,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.0f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
            )
        }
        // 面板层（全屏）
        AnimatedVisibility(
            visible = showMyQr,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            MyQrScreen(
                userId = currentUserId,
                userName = com.aurora.chat.data.api.AuroraApi.currentUserName,
                onBack = { showMyQr = false },
                onScanClick = { showMyQr = false; showScan = true }
            )
        }

        // ==================== 消息推送 ====================
        // 遮罩层（拦截穿透，防止点到背面界面）
        AnimatedVisibility(
            visible = showMessagePush,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.0f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
            )
        }
        // 面板层（全屏，从右侧滑入）
        AnimatedVisibility(
            visible = showMessagePush,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            MessagePushScreen(
                userId = currentUserId,
                onBack = { showMessagePush = false }
            )
        }

        // ==================== 编辑资料（从右侧滑入的独立界面） ====================
        AnimatedVisibility(
            visible = showEditProfile,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            com.aurora.chat.ui.profile.EditProfileScreen(
                userId = currentUserId,
                onBack = { showEditProfile = false }
            )
        }

        // ==================== 网站托管（独立直达，不再经过活动中心） ====================
        AnimatedVisibility(
            visible = showWebsiteHosting,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            com.aurora.chat.ui.server.ServerScreen(direct = true, onBack = { showWebsiteHosting = false })
        }

        // ==================== 漂流瓶（独立直达） ====================
        AnimatedVisibility(
            visible = showBottle,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            com.aurora.chat.ui.activity.BottleScreen(onBack = { showBottle = false })
        }

        // ==================== 扫一扫 ====================
        AnimatedVisibility(
            visible = showScan,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.0f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
            )
        }
        AnimatedVisibility(
            visible = showScan,
            enter = slideInHorizontally(tween(300)) { it },
            // 退场不再做平移：含相机预览的图层在平移时会拖影，改为淡出彻底规避
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            ScanScreen(
                currentUserId = currentUserId,
                onBack = { showScan = false },
                onScanned = { targetUid, targetName ->
                    scannedUserId = targetUid
                    scannedUserName = targetName
                    showScan = false
                    showScannedProfile = true
                },
                onPickedImage = { bytes ->
                    // 选图后立即切入资料页（先显示加载中），解析在后台进行
                    scannedUserId = 0
                    scannedUserName = ""
                    scanPendingImage = bytes
                    showScan = false
                    showScannedProfile = true
                }
            )
        }

        // ==================== 扫码后用户资料 ====================
        AnimatedVisibility(
            visible = showScannedProfile,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            // 相册选图：先解析待解析图片（后台），解析出 uid 前显示"加载中"
            LaunchedEffect(scanPendingImage) {
                val bytes = scanPendingImage ?: return@LaunchedEffect
                val result = withContext(Dispatchers.IO) {
                    val bmp = com.aurora.chat.ui.qr.decodeBitmapFromBytes(bytes)
                    if (bmp == null) null
                    else {
                        val text = com.aurora.chat.ui.qr.parseQrFromBitmap(bmp)
                        text?.let { com.aurora.chat.data.api.AuroraApi.resolveQrCode(it) }
                    }
                }
                if (result?.success == true && result.data?.userId ?: 0L > 0) {
                    scannedUserId = result.data!!.userId
                    scannedUserName = result.data!!.username
                } else {
                    com.aurora.chat.ui.qr.toast(context, result?.message ?: "无法识别该二维码")
                    showScannedProfile = false
                    showScan = true
                }
                scanPendingImage = null
            }
            if (scannedUserId > 0) {
                UserProfileCard(
                    userId = scannedUserId,
                    userName = scannedUserName,
                    currentUserId = currentUserId,
                    onBack = { showScannedProfile = false },
                    onDeleteFriend = { showScannedProfile = false },
                    onOpenChat = { _, _ -> showScannedProfile = false }
                )
            } else {
                // 加载中占位界面
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color(0xFF2563EB))
                        Spacer(Modifier.height(16.dp))
                        Text("正在识别二维码…", fontSize = 15.sp, color = Color(0xFF6B7280))
                    }
                }
            }
        }

        // ==================== 我的发布（置于最底层覆盖层，确保详情/发帖/用户资料显示在其之上） ====================
        AnimatedVisibility(
            visible = showMyPosts,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            MyPostsScreen(
                currentUserId = currentUserId,
                onBack = { showMyPosts = false },
                onOpenPostDetail = { postId, postType ->
                    postDetailId = postId
                    postDetailType = postType
                    showPostDetail = true
                },
                onOpenUserProfile = { uid, name ->
                    profileUserId = uid
                    profileUserName = name
                    showUserProfile = true
                },
                onOpenCreatePost = { communityCreatePost = true },
                refreshKey = myPostsRefreshKey
            )
        }

        // ==================== 联系人个人资料（全屏从右侧滑入，覆盖顶部标题与底部导航） ====================
        AnimatedVisibility(
            visible = showUserProfile,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            // 高 zIndex：保证从帖子详情打开资料时，资料始终覆盖在详情之上
            // （Compose 默认后声明者画在上层，而本覆层声明早于帖子详情，不加 zIndex 会被详情盖住）
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                if (profileUserId > 0) {
                    com.aurora.chat.ui.chat.UserProfileCard(
                        userId = profileUserId,
                        userName = profileUserName,
                        currentUserId = currentUserId,
                        onBack = { showUserProfile = false },
                        onDeleteFriend = {
                            scope.launch {
                                try {
                                    val r = ChatRepository.deleteFriend(profileUserId)
                                    Toast.makeText(
                                        context,
                                        if (r.success) "已删除好友" else "删除失败：${r.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (r.success) com.aurora.chat.ui.contacts.invalidateFriendCache(context, currentUserId)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
                                }
                                showUserProfile = false
                            }
                        },
                        onOpenChat = { uid, name ->
                            showUserProfile = false
                            chatFriendId = uid
                            chatFriendName = name
                            chatOpen = true
                        }
                    )
                }
            }
        }

        // ========== 开发者管理 ==========
        AnimatedVisibility(
            visible = showDeveloperManagement,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(backgroundColor = Color.Black.copy(alpha = 0.4f), onClick = { showDeveloperManagement = false })
        }
        AnimatedVisibility(
            visible = showDeveloperManagement,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                DeveloperManagementScreen(onBack = { showDeveloperManagement = false }, isDeveloper = isDeveloper, initialTab = devInitialTab)
            }
        }

        // ===== 兑换卡密弹窗 =====
        if (showRedeemCardKey) {
            var cardKeyInput by remember { mutableStateOf("") }
            var redeeming by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!redeeming) showRedeemCardKey = false },
                title = { Text("兑换卡密", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)) },
                text = {
                    Column {
                        Text("请输入卡密进行兑换", fontSize = 14.sp, color = Color(0xFF6B7280))
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = cardKeyInput,
                            onValueChange = { cardKeyInput = it },
                            placeholder = { Text("请输入卡密", fontSize = 14.sp, color = Color(0xFF9CA3AF)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val key = cardKeyInput.trim()
                            if (key.isEmpty()) {
                                Toast.makeText(context, "请输入卡密", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            redeeming = true
                            redeemScope.launch {
                                try {
                                    val res = AuroraApi.validateCardKey(key, currentUserId)
                                    val data = res.data ?: org.json.JSONObject()
                                    val reward = data.optJSONObject("reward")
                                    val msg = if (reward != null && reward.optString("type") == "token") {
                                        "兑换成功，获得 ${reward.optLong("amount", 0)} Token"
                                    } else {
                                        // 后端未发放奖励：根据卡密类型给出明确原因
                                        when {
                                            data.optString("reward_type", "") == "token" ->
                                                "兑换成功，但未发放 Token，请联系管理员"
                                            data.optString("category", "") == "reward" ->
                                                "兑换成功，但此奖励卡密不含可发放内容"
                                            else ->
                                                "兑换成功，但这是体验卡密，不含 Token 奖励"
                                        }
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    // 兑换后同步服务器真实 token 余额，使各页面显示真实值
                                    com.aurora.chat.ui.chat.AiChatManager.syncServerLimit(context, currentUserId)
                                    try {
                                        val ms = AuroraApi.tokenBalance()
                                        if (ms.success && ms.data != null) {
                                            val d = if (ms.data!!.has("data")) ms.data!!.optJSONObject("data") ?: ms.data!! else ms.data!!
                                            val mb = d.optLong("token_balance", -1L)
                                            if (mb >= 0L) com.aurora.chat.ui.chat.AiChatManager.cacheServerTokenBalance(context, currentUserId, mb)
                                        }
                                    } catch (_: Exception) { }
                                    showRedeemCardKey = false
                                } catch (e: Exception) {
                                    Toast.makeText(context, "没有这个卡密或已过期", Toast.LENGTH_LONG).show()
                                } finally {
                                    redeeming = false
                                }
                            }
                        },
                        enabled = !redeeming,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (redeeming) "兑换中..." else "兑换", color = Color.White, fontSize = 15.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRedeemCardKey = false }) {
                        Text("取消", color = Color(0xFF6B7280))
                    }
                }
            )
        }

        // ===== 切换账号面板（从右侧滑入） =====
        AnimatedVisibility(
            visible = showSwitchAccount,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                SwitchAccountScreen(
                onBack = { showSwitchAccount = false },
                onSwitchSuccess = { email, username, userId ->
                    lastSwitchTime = System.currentTimeMillis()
                    showSwitchAccount = false
                    showSettings = false
                    com.aurora.chat.ui.profile.OnlineTimeTracker.stop(context)
                    TcpService.stop(context)
                    DownloadManager.closeAll()
                    onLoginSuccess(email, username, userId)
                    android.widget.Toast.makeText(context, "已切换到账号: $email", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            }
        }

        // 创建群聊
        AnimatedVisibility(
            visible = showCreateGroup,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(backgroundColor = Color.Black.copy(alpha = 0.4f), onClick = { showCreateGroup = false })
        }
        AnimatedVisibility(
            visible = showCreateGroup,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            CreateGroupScreen(
                currentUserId = currentUserId,
                onBack = { showCreateGroup = false },
                onCreated = {
                    showCreateGroup = false
                    chatRefreshKey++
                }
            )
        }

        // 入群申请审批全屏面板
        AnimatedVisibility(
            visible = showJoinRequestFullScreen && joinRequestData != null,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            joinRequestData?.let { data ->
                JoinRequestListPanel(
                    requests = data,
                    groupId = joinRequestGroupId,
                    onBack = { showJoinRequestFullScreen = false },
                    onReviewed = {
                        showJoinRequestFullScreen = false
                        chatRefreshKey++
                    }
                )
            }
        }

    // 系统级悬浮窗由 DownloadService 管理生命周期，
    // 有下载任务时自动弹出系统级悬浮球，退出应用后仍然可见。

    // 应用锁
    AnimatedVisibility(
        visible = !appUnlocked,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(400)) + scaleOut(targetScale = 0.8f, animationSpec = tween(400))
    ) {
        AppUnlockScreen(
            currentEmail = currentEmail,
            onUnlock = { appUnlocked = true }
        )
    }

    // ==================== 错误日志全屏页面（从右侧滑入） ====================
    AnimatedVisibility(
        visible = showErrorLog,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        ErrorLogScreen(
            onBack = { showErrorLog = false }
        )
    }

    // ==================== 闪退拦截页面 ====================
    AnimatedVisibility(
        visible = showCrashIntercept,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        CrashInterceptScreen(
            onBack = { showCrashIntercept = false },
            onOpenErrorLog = { showCrashIntercept = false; showErrorLog = true }
        )
    }

    // ── 退出登录确认弹窗 ──
    if (showLogoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = Color.White,
            title = { Text("退出登录", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text("确定要退出当前账号吗？\n退出后需重新登录才能使用。", fontSize = 14.sp, color = Color(0xFF4B5563)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showLogoutDialog = false
                    com.aurora.chat.ui.profile.OnlineTimeTracker.stop(context)
                    LocalStorage.logout(context)
                    TcpService.stop(context)
                    DownloadManager.closeAll()
                    onLogout()
                }) {
                    Text("确认退出", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消", color = Color(0xFF9CA3AF))
                }
            }
        )
    }

    // ── 清空数据确认弹窗 ──
    if (showClearDataDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            containerColor = Color.White,
            title = { Text("清空数据", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text("确定要清理本地的所有聊天数据吗？\n包括聊天记录、缓存文件等，此操作不可恢复。\n如需保留聊天记录请使用「清理缓存」。", fontSize = 14.sp, color = Color(0xFF4B5563)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showClearDataDialog = false
                    // 检查是否有任何安全防护
                    val hasPassword = com.aurora.chat.ui.components.SecurityStorage.isPasswordSet(context)
                    val hasFingerprint = com.aurora.chat.ui.components.SecurityStorage.isFingerprintEnabled(context)
                    val hasGesture = com.aurora.chat.ui.components.SecurityStorage.isGestureEnabled(context)
                    if (hasPassword || hasFingerprint || hasGesture) {
                        // 优先用密码验证（最简单可靠）
                        if (hasPassword) {
                            showClearDataPasswordDialog = true
                            clearDataPassword = ""
                            clearDataPasswordError = false
                        } else if (hasFingerprint) {
                            // 尝试指纹验证
                            launchBiometricAuth(
                                context = context,
                                title = "身份验证",
                                subtitle = "验证指纹后清空所有数据",
                                onSuccess = { doClearData(context, onLogout) },
                                onError = { _, msg ->
                                    android.widget.Toast.makeText(context, "验证出错: $msg", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                onFailed = {
                                    android.widget.Toast.makeText(context, "验证失败，无法清空数据", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            // 手势验证 — 弹出密码验证作为替代（手势太复杂）
                            showClearDataPasswordDialog = true
                            clearDataPassword = ""
                            clearDataPasswordError = false
                        }
                    } else {
                        doClearData(context, onLogout)
                    }
                }) {
                    Text("确认清空", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDataDialog = false }) {
                    Text("取消", color = Color(0xFF9CA3AF))
                }
            }
        )
    }

    // ── 清空数据-密码验证弹窗 ──
    if (showClearDataPasswordDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearDataPasswordDialog = false },
            containerColor = Color.White,
            title = { Text("身份验证", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    Text("请验证密码以确认清空数据", fontSize = 14.sp, color = Color(0xFF4B5563))
                    Spacer(Modifier.height(12.dp))
                    val inputStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp, color = Color(0xFF1F2937)
                    )
                    androidx.compose.foundation.text.BasicTextField(
                        value = clearDataPassword,
                        onValueChange = { clearDataPassword = it; clearDataPasswordError = false },
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF3F4F6))
                            .padding(12.dp),
                        textStyle = inputStyle,
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        decorationBox = { inner ->
                            Box {
                                if (clearDataPassword.isEmpty()) Text("输入安全密码", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                                inner()
                            }
                        }
                    )
                    if (clearDataPasswordError) {
                        Spacer(Modifier.height(6.dp))
                        Text("密码错误，请重试", fontSize = 12.sp, color = Color(0xFFDC2626))
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (com.aurora.chat.ui.components.SecurityStorage.checkPassword(context, clearDataPassword)) {
                        showClearDataPasswordDialog = false
                        doClearData(context, onLogout)
                    } else {
                        clearDataPasswordError = true
                    }
                }) {
                    Text("验证", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDataPasswordDialog = false }) {
                    Text("取消", color = Color(0xFF9CA3AF))
                }
            }
        )
    }

    // ==================== 社区子页面（全屏从右侧滑入，覆盖顶栏和底栏） ====================
    AnimatedVisibility(
        visible = showCommunitySub,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        SubPage(
            title = communitySubTitle,
            currentUserId = currentUserId,
            onBack = { showCommunitySub = false },
            onOpenPostDetail = { postId, postType ->
                postDetailId = postId
                postDetailType = postType
                showPostDetail = true
            },
            onOpenUserProfile = { uid, name ->
                profileUserId = uid
                profileUserName = name
                showUserProfile = true
            },
            onOpenCreatePost = { communityCreatePost = true },
            refreshKey = communityRefreshKey,
            onOpenChat = { uid, name ->
                showCommunitySub = false
                chatFriendId = uid
                chatFriendName = name
                chatOpen = true
                currentTab = AuroraTab.Chat
            }
        )
    }

    // ==================== 帖子详情页 ====================
    AnimatedVisibility(
        visible = showPostDetail,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        PostDetailScreen(
                    postId = postDetailId,
                    onBack = {
                        // 根治：chat 未关闭，聊天界面仍在底层存活，返回即原位呈现
                        showPostDetail = false
                    },
                    onUserClick = { uid ->
                        // 从帖子详情点击头像/姓名 → 打开该用户资料（覆盖在详情之上）
                        profileUserId = uid
                        profileUserName = ""
                        showUserProfile = true
                    }
        )
    }

    // ==================== 社区首次引导遮罩（全屏，覆盖顶栏与底栏） ====================
    var communityGuideVisible by remember { mutableStateOf(false) }
    LaunchedEffect(currentTab) {
        communityGuideVisible =
            currentTab == AuroraTab.Community && !CommunityGuidePrefs.isDisabled(context)
    }
    if (communityGuideVisible) {
        CommunityGuideOverlay(
            onFinish = {
                // 走完两个阶段 -> 永久关闭，以后进入社区不再提示
                CommunityGuidePrefs.setDisabled(context)
                communityGuideVisible = false
            },
            onHideButton = {
                // 隐藏右下角发布按钮（切出再切入会恢复），并结束引导不再提示
                CommunityFabAnchor.hidden = true
                CommunityGuidePrefs.setDisabled(context)
                communityGuideVisible = false
            }
        )
    }

    // ==================== 发帖页（全屏，覆盖顶栏和底栏） ====================
    AnimatedVisibility(
        visible = communityCreatePost,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        CreatePostScreen(
            currentUserId = currentUserId,
            onBack = { communityCreatePost = false },
            onPostCreated = {
                communityCreatePost = false
                communityRefreshKey++
                myPostsRefreshKey++
            }
        )
    }

    // ==================== 闪退拦截对话框 ====================
    var showCrashDialog by remember { mutableStateOf(false) }

    // 仅在“拦截瞬间”（崩溃后由 CrashHandler 自动重启、fromCrash=true）弹一次，
    // 不用 hasPendingCrash 判据——否则之后任何一次手动打开 App 都会再弹。
    // 主线程崩溃后进程必须重置，无法在旧进程里画弹窗；自动重启完成即刻弹出即是最接近
    // “拦截那一瞬间”的可靠反馈。
    LaunchedEffect(Unit) {
        if (fromCrash && com.aurora.chat.CrashInterceptManager.hasPendingCrash(context)) {
            showCrashDialog = true
        }
        com.aurora.chat.CrashInterceptManager.markSeen(context)
    }

    if (showCrashDialog) {
        // 弹窗明确标注本次闪退是否被系统拦截：拦截成功（已自动重启，可自愈）
        // or 拦截失败（仅记录，未能自动重启）。
        val intercepted = com.aurora.chat.CrashHandler.getLastIntercepted(context)
        val title = if (intercepted) "闪退拦截成功" else "闪退拦截失败"
        val desc = if (intercepted) {
            "发生了一次异常，已被系统拦截并自动重启，应用数据未丢失。请查看错误日志并反馈给开发者"
        } else {
            "发生了一次闪退，无法自动恢复。请查看错误日志并反馈给开发者"
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text(desc, fontSize = 14.sp, color = Color(0xFF4B5563)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showCrashDialog = false
                    com.aurora.chat.CrashInterceptManager.shareErrorLogs(context)
                }) { Text("上传错误日志", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCrashDialog = false }) {
                    Text("取消", color = Color(0xFF9CA3AF))
                }
            }
        )
    }

    // ==================== AI 任务被系统中断提示 ====================
    // 与闪退拦截的区别：这类中断（后台被清理 / 被强行停止 / ANR）不会产生未捕获异常，
    // CrashHandler 抓不到，所以必须靠 AgentTaskGuard 留下的标记来发现。
    var agentKilledNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        agentKilledNotice = com.aurora.chat.AgentTaskGuard.consumeAbnormalTermination(context)
    }
    agentKilledNotice?.let { notice ->
        // 「查看 AI 说明」:点一下向 AI 发一次不落盘的请求,让 AI 解释这次为什么被中断,
        // 说明文字直接回填到弹窗里;这次问答不进消息流、不写会话文件,关掉弹窗即消失。
        var aiAskState by remember { mutableStateOf(0) } // 0=未问 1=问询中 2=已回答
        var aiExplanation by remember { mutableStateOf("") }
        LaunchedEffect(aiAskState) {
            if (aiAskState == 1) {
                aiExplanation = com.aurora.chat.ui.chat.AiChatManager.explainInterruption(context, currentUserId, notice)
                aiAskState = 2
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { agentKilledNotice = null },
            title = { Text("上次的任务被中断", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                androidx.compose.foundation.layout.Column(
                    Modifier.heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(notice, fontSize = 14.sp, color = Color(0xFF4B5563))
                    if (aiAskState == 1) {
                        Spacer(Modifier.height(10.dp))
                        Text("AI 正在分析中断原因…", fontSize = 13.sp, color = Color(0xFF2F6FED))
                    }
                    if (aiAskState == 2 && aiExplanation.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("AI 说明", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                        Spacer(Modifier.height(4.dp))
                        Text(aiExplanation, fontSize = 13.sp, color = Color(0xFF4B5563), lineHeight = 20.sp)
                    }
                }
            },
            confirmButton = {
                if (aiAskState == 0) {
                    androidx.compose.material3.TextButton(onClick = { aiAskState = 1 }) {
                        Text("查看 AI 说明", color = Color(0xFF1E40AF))
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { agentKilledNotice = null }) {
                    Text("知道了", color = Color(0xFF9CA3AF))
                }
            }
        )
    }

    // ==================== 强制头像上传 ====================
    if (showForceAvatar) {
        ForceAvatarDialog(
            onAvatarUploaded = {
                showForceAvatar = false
            }
        )
    }

    // ==================== 头像放大查看器（根级全屏；缩放只在图片本身,黑底遮罩由内部延迟淡入,容器只负责淡出） ====================
    AnimatedVisibility(
        visible = showAvatarViewer,
        enter = fadeIn(tween(60)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize()
    ) {
        if (avatarViewerBitmap != null) {
            AvatarViewerDialog(
                bitmap = avatarViewerBitmap!!,
                onDismiss = { showAvatarViewer = false }
            )
        }
    }

    // ==================== 系统公告弹窗 ====================
    if (showAnnouncement && announcementData != null) {
        val ann = announcementData!!
        // 使用 Compose Dialog 以保证在所有界面上层显示
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            com.aurora.chat.ui.components.SystemAnnouncementDialog(
                announcement = ann,
                onDismiss = { todayNotAgain ->
                    showAnnouncement = false
                    if (todayNotAgain) {
                        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                        context.getSharedPreferences("aurora_announcement", android.content.Context.MODE_PRIVATE)
                            .edit().putString("hide_date", today).apply()
                    }
                }
            )
        }
    }

    // ==================== 下载队列管理（置于最底部，确保覆盖一切界面） ====================
    val hasDownloadItems = DownloadManager.queue.isNotEmpty()
    AnimatedVisibility(
        visible = DownloadManager.isShowing.value && hasDownloadItems,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {
            com.aurora.chat.ui.components.EventBlocker()
            DownloadQueueScreen(
                onMinimize = { DownloadManager.minimize() }
            )
        }
    }

    // ==================== 解封后弹窗提示 ====================
    if (showUnbanPopup) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x80000000)).clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Card(
                modifier = Modifier.widthIn(max = 320.dp).padding(24.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.White),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "公告",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = unbanPopupText.ifEmpty { "你的账号已被解封，可以继续使用了。" },
                        fontSize = 15.sp,
                        color = Color(0xFF4B5563),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text(
                            text = "我不",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDC2626),
                            modifier = Modifier
                                .clickable {
                                    showUnbanPopup = false
                                    (context as? android.app.Activity)?.finishAffinity()
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        android.widget.Toast.makeText(context, "你不你就别用了😁", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                        Text(
                            text = "确定",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier
                                .clickable { showUnbanPopup = false }
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }

        // ===== token 余额耗尽卡片（Chat/AI 沙盒回调触发，顶层独立渲染，不依赖子页面布局） =====
        if (showTokenCard) {
            TokenInsufficientCard(
                onRecharge = {
                    showTokenCard = false
                    chatOpen = false
                    showAiSandbox = false
                    scope.launch {
                        currentTab = AuroraTab.Profile
                        kotlinx.coroutines.delay(300)
                        showTokenCard = true
                    }
                },
                onDismiss = { showTokenCard = false }
            )
        }
    }
}
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit, unreadCount: Int = 0) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF3F4F6))
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF1F2937)
        )
        if (unreadCount > 0) {
            val badgeText = if (unreadCount > 99) "99+" else unreadCount.toString()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .size(20.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFFEF4444)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

// ==================== 封禁提示（硬核方案：纯 Android 原生 Dialog，完全脱离 Compose） ====================

/**
 * 核心策略：使用 Android 原生 Dialog + 原生 TextView/Layout，
 * 100% 脱离 Compose 渲染树 / MaterialTheme / CompositionLocal，
 * 杜绝任何主题色污染背景的可能性。
 * 背景色使用 android.graphics.Color.WHITE，无任何中间层。
 */
@Composable
private fun BanOverlay(
    reason: String,
    expiresAt: Long,
    duration: Long,
    unbanPopupMessage: String = "",
    onDismiss: () -> Unit = {},
    onExit: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("aurora_login", android.content.Context.MODE_PRIVATE)
    val username = prefs.getString("username", "用户") ?: "用户"
    val email = prefs.getString("email", "") ?: ""

    var remainSecs by remember { mutableStateOf((expiresAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)) }
    var isNotified by remember { mutableStateOf(false) }

    // 计时器
    LaunchedEffect(Unit) {
        while (remainSecs > 0) {
            kotlinx.coroutines.delay(1000)
            remainSecs = (expiresAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)
        }
    }

    val isExpired = remainSecs <= 0
    val hms = remember(remainSecs) { String.format("%02d小时%02d分钟%02d秒", remainSecs / 3600, (remainSecs % 3600) / 60, remainSecs % 60) }
    val durStr = remember(duration) {
        val d = duration / 86400; val h = (duration % 86400) / 3600; val m = (duration % 3600) / 60
        when { d > 0 -> "${d}天${h}小时"; h > 0 -> "${h}小时${m}分钟"; else -> "${m}分钟" }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "您的账号 $username 因为 $reason 已被封禁!",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "违规处罚 $durStr",
                    fontSize = 15.sp,
                    color = Color(0xFFFCA5A5),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "以下是解封时间",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = hms,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(40.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BanButton(text = "退出软件", color = Color(0xFFDC2626)) {
                        onDismiss(); onExit()
                    }
                    BanButton(text = "解封通知", color = Color(0xFF4B5563)) {
                        if (!isNotified) {
                            isNotified = true
                            ioScope.launch {
                                try {
                                    com.aurora.chat.data.api.AuroraApi.requestUnbanNotify()
                                    withContext(Dispatchers.Main.immediate) {
                                        android.widget.Toast.makeText(ctx, "解封后将通过 $email 发送邮件通知", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                } catch (_: Exception) { isNotified = false }
                            }
                        }
                    }
                    BanButton(text = "申诉", color = Color(0xFF1E40AF)) {
                        android.widget.Toast.makeText(ctx, "请加QQ群 1084612899 找群主申诉", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun BanButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// ==================== APK 签名校验拦截弹窗 ====================

@Composable
private fun SignatureBlockDialog() {
    val ctx = LocalContext.current
    BackHandler(enabled = true) {}
    Box(
        modifier = Modifier.fillMaxSize().background(Color.White).clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("⚠️", fontSize = 56.sp)
            Spacer(Modifier.height(20.dp))
            Text("应用校验失败", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
            Spacer(Modifier.height(12.dp))
            Text(
                text = "当前 Aurora Chat 版本疑似非官方版本，\n请加入官方QQ群获取最新安装包。",
                fontSize = 15.sp, color = Color(0xFF4B5563), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text("QQ群：1084612899", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E40AF))
                    .clickable {
                        try {
                            ctx.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://qun.qq.com/universal-share/share?ac=1&authKey=YOUR_GROUP_AUTH_KEY&tempid=h5_group_info")
                            ))
                        } catch (_: Exception) {}
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("加入官方QQ群", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}


/** 将字节数格式化为可读大小（B / KB / MB） */
private fun formatBackupSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

