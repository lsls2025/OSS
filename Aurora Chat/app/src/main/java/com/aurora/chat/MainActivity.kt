@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.aurora.chat
import com.aurora.chat.ui.theme.LocalAppColors

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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.LoginResponse
import com.aurora.chat.data.open.OpenAuthManager
import com.aurora.chat.ui.open.OpenAuthScreen
import com.aurora.chat.data.local.LocalStorage
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.gomoku.GomokuActivity
import com.aurora.chat.ui.components.*
import com.aurora.chat.ui.chat.*
import com.aurora.chat.ui.community.*
import com.aurora.chat.ui.qr.MyQrScreen
import com.aurora.chat.ui.qr.ScanScreen
import com.aurora.chat.ui.profile.*
import com.aurora.chat.ui.tools.*
import com.aurora.chat.ui.theme.AuroraChatTheme
import com.aurora.chat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob


// 进程级一次性 IO 任务作用域（启动重试/头像刷新/定位上报等），替代 MainScope：不泄漏且不在主线程执行
private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// 进程级一次性主线程任务作用域（登录等需在主线程执行的短时任务），替代 MainScope：不泄漏且不在主线程执行
private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

// ==================== 子界面层级计数（统一"是否主界面"判定） ====================
// 所有子界面覆盖层统一用下面的 SubScreen 包裹：可见时 +1、消失时 -1；
// 计数 > 0 即"不在主界面" → 隐藏底部导航栏。以后新增子界面只要用 SubScreen 包裹即自动生效。
// 单 Activity 应用，进程级状态等价于导航栈深度；配置变更时旧组合 dispose(-1)、新组合重进(+1)，自动守恒。
private val subScreenDepth = androidx.compose.runtime.mutableStateOf(0)

/**
 * 事件终点：整块区域参与命中测试，并在 Main 阶段把未被子级消费的指针变更标记掉。
 * 子级（按钮、列表、Pager）在 Main 阶段先收到事件，正常交互完全不受影响；
 * 只有"谁都没要"的点击才会被这里吃掉，不会继续落到下面那一层界面。
 */
private fun Modifier.consumeAllPointers(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { ch -> if (!ch.isConsumed) ch.consume() }
        }
    }
}

@Composable
private fun SubScreen(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = EnterTransition.None,
    exit: ExitTransition = ExitTransition.None,
    label: String = "SubScreen",
    swipeBack: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    /**
     * 全屏子页必须置 true：在整屏铺一层 pointerInput，让点击/滑动「到此为止」。
     *
     * Compose 的命中测试会跳过没有任何 pointer 修饰符的区域 —— 子页里留白的部分（灰底、
     * 透明占位）没有 pointerInput，命中测试就直接落到它下面那一层，于是一边看小游戏、
     * 一边点到了背后的主界面。铺满一层的 pointerInput 是"事件消费"的物理边界。
     */
    consumeTouches: Boolean = false,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    DisposableEffect(visible) {
        if (visible) subScreenDepth.value++
        onDispose { if (visible) subScreenDepth.value-- }
    }
    // ── 全局侧滑返回（仅系统预测性返回；界面切换设置开启后才生效；默认关 → swipeOn=false → 走原样 content）──
    val swipeCtx = LocalContext.current
    val swipeOn = swipeBack && onDismiss != null && SwipeBackPrefs.enabled(swipeCtx)
    val swipeOffset = remember { Animatable(0f) }
    val swipeScope = rememberCoroutineScope()
    val swipeHaptic = LocalHapticFeedback.current
    val swipeDim = SwipeBackPrefs.dim(swipeCtx)
    val swipeThresholdRatio = SwipeBackPrefs.threshold(swipeCtx)
    val swipeHapticOn = SwipeBackPrefs.haptic(swipeCtx)
    var swipeW by remember { mutableStateOf(0) }
    LaunchedEffect(visible) { if (visible) swipeOffset.snapTo(0f) }

    // 侧滑返回：只借系统"边缘右滑返回"手势驱动（Predictive Back），不再有自定义中间划（防误触）。
    if (swipeOn && visible) {
        androidx.activity.compose.PredictiveBackHandler { flow ->
            var armed = false
            // 是否真的滑动过。用于拦截"注册即完成"的空返回事件：若 flow 从头到尾没有 progress>0,
            // 说明不是用户真滑，不能因此调 onDismiss —— 否则会"点开聊天就立刻被关掉"（点两下才进）。
            var sawProgress = false
            // 跟随到 62% 后转为"渐进阻尼"（越往右越吃力，但不会硬停"卡死"），最多逼近约 75%，不把整页拖到底。
            val softStart = swipeW * 0.62f
            try {
                flow.collect { event ->
                    if (event.progress > 0.001f) sawProgress = true
                    val raw = event.progress * swipeW
                    val target = (if (raw <= softStart) raw else softStart + (raw - softStart) * 0.35f)
                        .coerceIn(0f, swipeW.toFloat())
                    swipeOffset.snapTo(target)
                    val thr = swipeW * swipeThresholdRatio
                    if (!armed && target >= thr) {
                        armed = true
                        if (swipeHapticOn) swipeHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else if (armed && target < thr) armed = false
                }
                // 只有真的滑动过才提交关闭；空返回事件视作误触发，保持界面不动。
                if (sawProgress) {
                    swipeOffset.animateTo(
                        swipeW.toFloat(),
                        spring(dampingRatio = 0.9f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
                    )
                    onDismiss?.invoke()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 松手未过阈值 / 中断：带阻尼滑回原位（独立作用域，避免被取消的协程打断）
                swipeScope.launch {
                    swipeOffset.animateTo(
                        0f,
                        spring(dampingRatio = 0.9f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
                    )
                }
                throw e
            }
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        // 侧滑返回时退场改为极短淡出：横向位移已由 swipeOffset 负责，若再用 slideOut 会看到
        // "内容滑出 + 暗蒙层再滑出"的两层先后划过（重影）。淡出只做收尾，只保留一次横向。
        exit = if (swipeOn) fadeOut(tween(120)) else exit,
        label = label
    ) {
        if (!swipeOn) {
            if (consumeTouches) {
                Box(Modifier.fillMaxSize().consumeAllPointers()) { content() }
            } else {
                content()
            }
        } else {
            Box(Modifier.fillMaxSize().onSizeChanged { swipeW = it.width }) {
                // 背面压暗：拖动时按进度压暗（有"阴影"），但滑到末端（>85%）快速淡出 →
                // 松手提交时不会留下黑影停留（之前那层独立 40% 黑已改透明，这里只保留这一层、不再"两层"）。
                if (swipeDim) {
                    // 方向：切得越少（露出越窄）越黑，切得越多（越接近完全露出）越亮；切到底(p=1)不压暗。
                    // p≈0（没在滑）时不画，避免退场淡出时露出这层造成闪黑。
                    val p = (swipeOffset.value / swipeW.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val dimA = if (p > 0.001f) 0.42f * (1f - p) else 0f
                    if (dimA > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimA)))
                }
                // 整页右滑（纯位移，无缩放/圆角/翻转）——干净的整页切换，非卡片浮层
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = swipeOffset.value }
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 「后台锁定」的判定依据：区分**用户主动离开应用**与**应用自己跳到别的界面**。
 *
 * Android 没有「用户是否按了 Home / 最近任务」的可靠回调（onUserLeaveHint 既会在应用内
 * startActivity 时误报，手势导航进最近任务时又常常不触发），所以这里用**反向判定**：
 *
 *   ON_STOP（应用真正不可见）时，如果这次不可见是**我们自己拉起界面**造成的 → 不算离开；
 *   否则（回桌面、切到别的应用、息屏）→ 认定用户离开 → 锁定。
 *
 * 覆盖方式（无需逐个界面改造）：
 *  1) [MainActivity] 重写全部 startActivity* 入口 —— Compose 层拿到的 context 就是 MainActivity，
 *     连 rememberLauncherForActivityResult 走的 startActivityForResult 也在这条路径上；
 *  2) 少数用 applicationContext / Service 发起的跳转，显式调用 [markSelfLaunch]。
 *
 * 复位时机：回到前台（ON_START / ON_RESUME）立即清零 —— 既覆盖「跳出去又回来」，
 * 也覆盖「被半透明界面遮住、只 pause 未 stop」的情况，避免标记残留导致下一次真的离开时不锁。
 */
internal object AppLockGuard {
    @Volatile private var selfLaunched = false

    /** 登记「本次界面切换是本应用自己发起的」 */
    fun markSelfLaunch() {
        selfLaunched = true
    }

    /** 取出并复位。返回 true = 本次应用不可见是应用自己跳出去造成的（不算用户离开）。 */
    fun consumeSelfLaunch(): Boolean {
        val v = selfLaunched
        selfLaunched = false
        return v
    }

    /** 回到前台时清零 */
    fun reset() {
        selfLaunched = false
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        var pendingNotifCount = 0
    }

    // ── 「后台锁定」登记点：应用自己拉起界面（选文件 / 拍照 / 分享 / 授权 / 支付 /
    //    系统设置 / 本应用的其他 Activity）时打标记，避免回到应用时被误判为「用户离开」。
    //    Compose 的 LocalContext 就是本 Activity，所以 UI 上的跳转全部经过这里，
    //    不需要逐个界面改动；startActivityForResult 覆盖的是 ActivityResultLauncher 那条路径。
    override fun startActivity(intent: Intent) {
        AppLockGuard.markSelfLaunch()
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        AppLockGuard.markSelfLaunch()
        super.startActivity(intent, options)
    }

    @Deprecated("Deprecated in Android")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        AppLockGuard.markSelfLaunch()
        super.startActivityForResult(intent, requestCode)
    }

    @Deprecated("Deprecated in Android")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        AppLockGuard.markSelfLaunch()
        super.startActivityForResult(intent, requestCode, options)
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

    /**
     * 「禁止退出」的最快回弹入口。
     *
     * onUserLeaveHint 是 HOME / 最近任务 / 上滑回桌面时**最早**的可靠回调，此时桌面
     * 往往还没画出来；等到 onStop 才动手就已经晚了（桌面已经起来，怎么轮询都来不及）。
     * 它只在用户主动离开时触发，应用内拉起相机/选文件/权限弹窗不会误触发。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        try {
            PrivacyEnforcer.onUserLeaveHint(this)
        } catch (_: Throwable) {}
    }

    /** 已全部授予（保留给「设置里自查权限」用；**不再作为进入主界面的前置条件**） */
    private val gatePassed = androidx.compose.runtime.mutableStateOf(false)

    /**
     * 首次引导是否已走完。
     *
     * 改动前是「一进来就要权限、不给就退出」；现在改成先走 6 步引导，
     * 权限在其中第 2 步由用户自己决定，**不授权也能进入主界面**。
     */
    private val onboardingDone = androidx.compose.runtime.mutableStateOf(false)

    /** 本次冷启动要展示的自定义启动扩展；null = 不展示，走原来的朴素启动。 */
    private val launchExtension =
        androidx.compose.runtime.mutableStateOf<com.aurora.chat.ui.custom.HostExtension?>(null)

    // 各权限实时状态（用于界面勾选标记与逐项跳转）
    private val permStates = androidx.compose.runtime.mutableStateOf<List<PermItem>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 10(API 29)及以下没有 WindowInsetsController(API30+)，上层 imePadding 收不到输入法 inset，
        // 键盘弹起后输入框会被遮挡；回退系统 adjustResize 由窗口自行上移，与 edge-to-edge 不冲突。
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        // 处理账号被删除/封禁后强制退出登录
        if (intent.getBooleanExtra("force_logout", false)) {
            LocalStorage.logout(this)
            AuroraApi.authToken = null
            AuroraApi.currentUserId = 0
        }
        // 应用默认白色背景
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))

        // 冷启动最早期预热联系人：在 setContent 之前后台把磁盘好友读进内存并建好拼音分组。
        // 这样用户点进「联系人」时直接命中缓存：既不显示“加载中”，也不在动画期间做任何重活。
        run {
            val uid = try { LocalStorage.loadLoginState(this).userId } catch (_: Throwable) { 0L }
            if (uid > 0) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        com.aurora.chat.ui.contacts.prewarmContactCaches(applicationContext, uid)
                    } catch (_: Throwable) { }
                }
            }
        }

        // 【不再在启动时索要权限】原实现一进来就 requestStartupPermissions() 弹系统权限框，
        // 未全部授予就停在权限门、按返回键直接退出。现在权限交给引导页第 2 步，由用户自己点。
        gatePassed.value = allRequiredGranted()
        permStates.value = buildPermStates()
        // 老用户（已登录过）直接视为已完成引导：避免升级后被突然弹一遍引导流程
        onboardingDone.value = isOnboardingDone() || hasLegacyUser()

        // ── 自定义启动层（HostExtension 框架）──
        // 外部唤起判断必须在下面 L419 清空分享 intent 之前做：分享 / deep link / 通知点击
        // 都是「用户带着明确目的进来」，此时插一张启动图只会挡路，一律跳过。
        val launchIntent = intent
        val isExternalLaunch = launchIntent != null && (
            launchIntent.action == Intent.ACTION_SEND ||
                launchIntent.action == Intent.ACTION_SEND_MULTIPLE ||
                launchIntent.action == Intent.ACTION_VIEW ||
                launchIntent.data != null ||
                launchIntent.hasExtra("open_chat_id") ||
                launchIntent.hasExtra("open_friend_requests") ||
                launchIntent.hasExtra("from_crash")
            )
        // 默认注册表为空 → activeLaunch 返回 null → 不展示任何额外启动层，保持系统原生启动。
        // 只有用户在「其他 → 自定义」里自建并启用扩展后才会走到这里。
        com.aurora.chat.ui.custom.HostExtensionStore.ensureInitialized(this)
        // 系统级悬浮球：上次开启过的在这里恢复（Service 独立运行，不依赖本 Activity）。
        com.aurora.chat.ui.custom.FloatingExtensionHost.restore(this)
        launchExtension.value = if (!isExternalLaunch && onboardingDone.value) {
            com.aurora.chat.ui.custom.HostExtensionStore.activeLaunch(this)
        } else null

        NotificationHelper.createNotificationChannel(this)

        // 启动网络状态监听
        com.aurora.chat.util.NetworkMonitor.start(this)

        // 启动时重试之前失败的群头像上传
        ioScope.launch {
            ChatRepository.retryPendingGroupAvatars(this@MainActivity)
        }

        // 崩溃自动重启（from_crash=true）：只有当本次确由崩溃重启意图拉起时，
        // 才证明自愈成功，由 CrashHandler 写入"拦截成功"标记（绝不谎报）。
        val fromCrash = intent?.getBooleanExtra("from_crash", false) ?: false
        com.aurora.chat.CrashHandler.confirmRestartIfNeeded(this, fromCrash)
        // 极端异常逃出消息级保护、走了自动重启时，也要明确告知用户而不是悄悄重开
        if (fromCrash) {
            android.widget.Toast.makeText(
                this, "上次异常已拦截，应用已自动恢复，详情见错误日志",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

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
            AuroraChatTheme(darkTheme = com.aurora.chat.ui.theme.ThemePrefs.isDark(this)) {
                Box {
                    if (!onboardingDone.value) {
                        // 首次引导：品牌页 → 权限页（可跳过）→ 云端保存 → 隐私协议
                        com.aurora.chat.ui.onboarding.OnboardingScreen(
                            // 权限状态由引导页自己在 ON_RESUME 时重新拉取（见 OnboardingScreen 内注释），
                            // 这里只提供取值方式，避免"授权返回后仍显示去设置"。
                            permProvider = { onboardingPerms() },
                            onPermClick = { onPermItemClick(it) },
                            onAgreed = {
                                markOnboardingDone()
                                onboardingDone.value = true
                            },
                            onExitApp = {
                                finishAffinity()
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        )
                    } else {
                        // 引导走完后**不再拦截权限**：未授权也能正常使用，用到时再懒唤起
                        AuroraMainScreen(openFriendRequests = openFriendRequests)
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
                    // 自定义启动层：盖住主界面，但仍位于安全守卫之下
                    com.aurora.chat.ui.custom.LaunchExtensionLayer(
                        extension = launchExtension.value,
                        onDismiss = { launchExtension.value = null }
                    )
                    // 反抓包守卫（置于最上层）：命中抓包/代理环境时全屏阻断网络与交互
                    com.aurora.chat.security.CaptureBlockOverlay()
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
                "权限已被拒绝，请到系统设置中手动开启",
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
            if (AITaskGuard.isRunning(this)) {
                AiKeepAliveService.startAuto(this)
            }
        } catch (_: Exception) {}
    }

    // ===== 首次引导的持久化 =====

    /** 引导是否已完成（且协议版本仍是当前版本：改条款后要重新同意） */
    private fun isOnboardingDone(): Boolean = try {
        val p = getSharedPreferences("onboarding", Context.MODE_PRIVATE)
        p.getBoolean("done", false) &&
            p.getString("policy_version", "") == com.aurora.chat.ui.onboarding.POLICY_VERSION
    } catch (_: Throwable) { false }

    private fun markOnboardingDone() {
        try {
            getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit()
                .putBoolean("done", true)
                .putString("policy_version", com.aurora.chat.ui.onboarding.POLICY_VERSION)
                .apply()
        } catch (_: Throwable) {}
    }

    /**
     * 是否是有历史使用痕迹的老用户。
     *
     * 判据用登录态而不是"有没有消息"：只要这个账号登录过，就说明引导对他没有意义，
     * 升级后突然被弹一遍反而是骚扰。全新安装的用户一定没登录过。
     */
    private fun hasLegacyUser(): Boolean = try {
        LocalStorage.loadLoginState(this).isLoggedIn
    } catch (_: Throwable) { false }

    /**
     * 引导页第 2 步展示的三项权限（悬浮窗 / 位置 / 安装外部应用）。
     *
     * 刻意**不含通知**：通知属于系统级运行时权限，用户拒绝后系统也不会再弹，
     * 放在引导里容易变成"点了没反应"。它已经在应用的权限自查页里，需要时再引导。
     */
    private fun onboardingPerms(): List<com.aurora.chat.ui.onboarding.OnboardingPerm> =
        onboardingPermsOf(this)

    /** 跳「安装未知应用」授权页；机型不支持时退回应用详情页 */
    private fun openInstallPermissionSettings() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:$packageName")
                ))
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettings()
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
            "camera" -> requestRuntimePermission(arrayOf(android.Manifest.permission.CAMERA))
            "microphone" -> requestRuntimePermission(arrayOf(android.Manifest.permission.RECORD_AUDIO))
            "overlay" -> openOverlaySettings()
            // 引导页第 2 步的第三项：安装外部应用（特殊权限，只能跳系统页）
            "install" -> openInstallPermissionSettings()
            // 所有文件访问（Android 11+ 特殊权限，只能跳系统设置页）
            "storage" -> openManageAllFilesAccess()
            // 文件管理权限不再强制，从闸门移除；需要时在对应功能处懒唤起
        }
    }

    // 请求单个运行时权限：直接弹系统授权框。
    // 注意：首次请求前 shouldShowRequestPermissionRationale() 也返回 false，
    // 若拿它当"永久拒绝"判据，第一次点击就会被误跳应用详情页（点位置不弹窗的根因）。
    // 永久拒绝的判定放到 launcher 回调里（那里才是真正弹过窗被拒之后的回调）。
    private fun requestRuntimePermission(perms: Array<String>) {
        startupPermissionLauncher.launch(perms)
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

    // Android 11+ 的「所有文件访问」是特殊权限，只能跳系统设置页；低版本视为已授予
    private fun openManageAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                ))
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettings()
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

/**
 * 引导页第 2 步展示的三项权限（悬浮窗 / 位置 / 安装外部应用）。
 *
 * 抽成**顶层函数**而不是 MainActivity 的私有方法：「启动调试」入口在 composable 里，
 * 拿不到 Activity 的私有方法，但必须复用同一份定义，否则调试时看到的和真实首次进入会不一致。
 *
 * 刻意不含通知：通知是系统级运行时权限，用户拒绝后系统不会再弹，
 * 放进引导容易变成「点了没反应」。它已在应用内的权限自查里，需要时再引导。
 */
internal fun onboardingPermsOf(
    ctx: android.content.Context
): List<com.aurora.chat.ui.onboarding.OnboardingPerm> {
    val locOk = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val overlayOk = android.provider.Settings.canDrawOverlays(ctx)
    val installOk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        ctx.packageManager.canRequestPackageInstalls()
    } else true
    val notifOk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } else true
    val cameraOk = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val micOk = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val storageOk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else true
    val alarmOk = com.aurora.chat.alarm.AlarmScheduler.canScheduleExact(ctx)
    return listOf(
        com.aurora.chat.ui.onboarding.OnboardingPerm(
            key = "overlay",
            title = "悬浮窗",
            desc = "后台下载 / 上传时以浮层显示进度",
            granted = overlayOk,
            systemPage = true
        ),
        com.aurora.chat.ui.onboarding.OnboardingPerm(
            key = "location",
            title = "位置",
            desc = "用于「附近的人」与位置共享",
            granted = locOk
        ),
        com.aurora.chat.ui.onboarding.OnboardingPerm(
            key = "install",
            title = "安装外部应用",
            desc = "让 AI 助手直接安装下载好的 APK",
            granted = installOk,
            systemPage = true
        ),
        com.aurora.chat.ui.onboarding.OnboardingPerm(
            key = "notification",
            title = "通知",
            desc = "接收新消息与任务进度提醒",
            granted = notifOk
        ),
        com.aurora.chat.ui.onboarding.OnboardingPerm(
            key = "alarm_exact",
            title = "闹钟和提醒",
            desc = "让 AI 设置的闹钟/定时提醒能准时响铃",
            granted = alarmOk,
            systemPage = true
        ),
        com.aurora.chat.ui.onboarding.OnboardingPerm(
            key = "camera",
            title = "相机",
            desc = "扫码、拍照与 AI 视觉能力",
            granted = cameraOk
        ),
        com.aurora.chat.ui.onboarding.OnboardingPerm(
            key = "microphone",
            title = "麦克风",
            desc = "语音消息与语音输入",
            granted = micOk
        ),
        com.aurora.chat.ui.onboarding.OnboardingPerm(
            key = "storage",
            title = "所有文件访问",
            desc = "读写公共存储中你指定的文件",
            granted = storageOk,
            systemPage = true
        )
    )
}

/** 引导页里点某一项权限：特殊权限跳系统页，运行时权限直接弹窗。 */
internal fun openOnboardingPermPage(ctx: android.content.Context, key: String) {
    when (key) {
        "alarm_exact" -> {
            // Android 12+ 的「闹钟和提醒」只能跳系统设置页打开，没有运行时弹窗
            if (!com.aurora.chat.alarm.AlarmScheduler.requestExactPermission(ctx)) {
                openAppDetailsSettingsOf(ctx)
            }
        }
        "overlay" -> {
            try {
                ctx.startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}")
                ))
            } catch (_: Exception) {
                openAppDetailsSettingsOf(ctx)
            }
        }
        "install" -> {
            var done = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    ctx.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    ))
                    done = true
                } catch (_: Exception) {}
            }
            if (!done) openAppDetailsSettingsOf(ctx)
        }
        "location" -> {
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                0x0A11
            )
        }
        "notification" -> {
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                0x0A11
            )
        }
        "camera" -> {
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(android.Manifest.permission.CAMERA),
                0x0A11
            )
        }
        "microphone" -> {
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                0x0A11
            )
        }
        "storage" -> {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    ctx.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    ))
                } else {
                    openAppDetailsSettingsOf(ctx)
                }
            } catch (_: Exception) {
                openAppDetailsSettingsOf(ctx)
            }
        }
    }
}

private fun openAppDetailsSettingsOf(ctx: android.content.Context) {
    try {
        ctx.startActivity(android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${ctx.packageName}")
        ))
    } catch (_: Exception) {}
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

    val ctx = LocalContext.current

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
    openFriendRequests: Boolean = false
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

    // ── 封禁 / 解封（根层，独立于 isLoggedIn；登录页也能弹） ──
    val banPrefs = remember { context.getSharedPreferences("aurora_ban", android.content.Context.MODE_PRIVATE) }
    var showBanOverlay by remember { mutableStateOf(false) }
    var banReason by remember { mutableStateOf("") }
    var banDuration by remember { mutableStateOf(0L) }          // 服务端权威给的封禁时长（秒）
    var banLocalEndAt by remember { mutableStateOf(0L) }        // 本地时钟算出的倒计时终点（不依赖服务端绝对时间戳）
    var showUnbanPopup by remember { mutableStateOf(false) }
    var unbanPopupText by remember { mutableStateOf("") }
    // 主动向服务器验证封禁状态的时间戳（避免每 500ms 都打 API）
    var lastServerCheck by remember { mutableStateOf(0L) }

    // 首次同步检查（启动立即扫本地 prefs，不等）
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis() / 1000
        val exp = banPrefs.getLong("expires_at", 0L)
        val dur = banPrefs.getLong("duration", 0L)
        if (exp > now && dur > 0) {
            banReason = banPrefs.getString("reason", "") ?: ""
            banDuration = dur
            // 冷启动时用 (服务端expires_at - 本地时钟偏移) 估算终点——
            // 服务端expires_at可能和本地时钟有漂移，但第一次checkSrv会校正
            banLocalEndAt = now + (exp - now).coerceAtLeast(0L)
            showBanOverlay = true
        }
    }

    // 核心监控：500ms 扫 PendingBan/本地 prefs；每 2s 主动问服务器验证
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val now = System.currentTimeMillis() / 1000
            // 1) 即时 TCP 推送触发封禁（优先级最高）
            if (com.aurora.chat.PendingBan.hasPending) {
                com.aurora.chat.PendingBan.hasPending = false
                val pb = com.aurora.chat.PendingBan
                val pbNow = System.currentTimeMillis() / 1000
                com.aurora.chat.ErrorReporter.error("BanTrace", "ROOT PendingBan: duration=${pb.duration} expiresAt=${pb.expiresAt} localNow=$pbNow")
                banReason = pb.reason
                banDuration = pb.duration
                // ★ 倒计时终点：优先用服务端权威 duration，没有（auth_denied 先到）就用 expiresAt 估算
                // checkSrv 会在 2 秒内回来校正，彻底消除漂移
                banLocalEndAt = if (pb.duration > 0) {
                    pbNow + pb.duration
                } else if (pb.expiresAt > pbNow) {
                    pb.expiresAt  // auth_denied 里 expiresAt 是服务端绝对时间戳，和本地 now 差几秒
                } else {
                    pbNow + 30  // fallback：估 30 秒，checkSrv 立即校正
                }
                banPrefs.edit().apply {
                    putString("reason", pb.reason)
                    putLong("expires_at", pb.expiresAt)
                    putLong("duration", pb.duration)
                    apply()
                }
                showBanOverlay = true
            }
            if (com.aurora.chat.PendingBan.hasUnban) {
                com.aurora.chat.PendingBan.hasUnban = false
                val pb = com.aurora.chat.PendingBan
                showBanOverlay = false
                banPrefs.edit().clear().apply()
                if (pb.unbanMessage.isNotEmpty()) {
                    unbanPopupText = pb.unbanMessage
                    showUnbanPopup = true
                }
            }
            // 2) 本地 prefs 扫一次
            val exp = banPrefs.getLong("expires_at", 0L)
            if (exp > now && !showBanOverlay) {
                banReason = banPrefs.getString("reason", "") ?: ""
                val savedDur = banPrefs.getLong("duration", 0L)
                banDuration = savedDur
                // 用剩余时间校正本地终点
                val remaining = (exp - now).coerceAtLeast(0L)
                banLocalEndAt = now + remaining
                showBanOverlay = true
            }
            // 3) 每 2 秒主动向服务器验证一次（只在被封禁时打，省配额）
            val wallNow = System.currentTimeMillis()
            val needServerCheck = wallNow - lastServerCheck >= 2000L && (showBanOverlay || exp > 0)
            if (needServerCheck) {
                lastServerCheck = wallNow
                kotlinx.coroutines.Dispatchers.IO.run {
                    try {
                        val res = com.aurora.chat.data.api.AuroraApi.checkBanStatus()
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (res.success && res.data != null) {
                                val srvExp = res.data!!.optLong("expires_at", 0L)
                                val srvNow = System.currentTimeMillis() / 1000
                                val srvDur = srvExp - res.data!!.optLong("banned_at", 0L)
                                com.aurora.chat.ErrorReporter.error("BanTrace", "ROOT checkSrv: srvExp=$srvExp localNow=$srvNow remaining=${srvExp - srvNow} duration=$srvDur")
                                if (srvExp > 0 && srvExp > now) {
                                    // 服务器还在封禁 → 校正本地状态
                                    val srvReason = res.data!!.optString("reason", "")
                                    val srvUnbanMsg = res.data!!.optString("unban_popup_message", "")
                                    val srvDuration = srvExp - res.data!!.optLong("banned_at", srvExp - 1)
                                    banReason = srvReason.ifBlank { banReason }
                                    banDuration = srvDuration.coerceAtLeast(0)
                                    // ★ 用实时 HTTP 请求回来的剩余时间校正本地终点（精度几十毫秒）
                                    val remaining = (srvExp - srvNow).coerceAtLeast(0L)
                                    banLocalEndAt = srvNow + remaining
                                    banPrefs.edit().apply {
                                        putString("reason", banReason)
                                        putLong("expires_at", srvExp)
                                        putLong("duration", banDuration)
                                        if (srvUnbanMsg.isNotBlank()) putString("unban_popup_message", srvUnbanMsg)
                                        apply()
                                    }
                                    showBanOverlay = true
                                } else {
                                    // 服务器说没 ban 了 → 解封
                                    val srvUnbanMsg = res.data!!.optString("unban_popup_message", "")
                                    val localMsg = banPrefs.getString("unban_popup_message", "") ?: ""
                                    showBanOverlay = false
                                    banPrefs.edit().clear().apply()
                                    val finalMsg = srvUnbanMsg.ifBlank { localMsg }
                                    if (finalMsg.isNotBlank()) {
                                        unbanPopupText = finalMsg
                                        showUnbanPopup = true
                                    }
                                }
                            } else if (res.success && res.data == null) {
                                // 服务器空 data → 未封禁
                                val localMsg = banPrefs.getString("unban_popup_message", "") ?: ""
                                showBanOverlay = false
                                banPrefs.edit().clear().apply()
                                if (localMsg.isNotBlank()) {
                                    unbanPopupText = localMsg
                                    showUnbanPopup = true
                                }
                            }
                            // else: 服务器拒绝（token 失效/网络错误）→ 继续用本地倒计时，不强制解
                        }
                    } catch (_: Exception) { /* 网络/鉴权失败，静默，靠本地继续 */ }
                }
            }
            // 4) 本地倒计时归零时不立刻解——等服务器验证确认没 ban 才解
            //    避免本地时钟不准 / 服务器 ban 记录还在就误解然后被 auth_denied 秒回来
        }
    }

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

    // 单设备登录：被踢下线时弹出权威安全提醒，确认后切回登录页
    val kickedOffInfo by AuroraApi.kickedOffEvent.collectAsState()
    var showKickOffDialog by remember { mutableStateOf(false) }
    var kickOffDetail by remember { mutableStateOf<AuroraApi.KickOffInfo?>(null) }
    LaunchedEffect(kickedOffInfo) {
        if (kickedOffInfo != null && isLoggedIn) {
            kickOffDetail = kickedOffInfo
            showKickOffDialog = true
            // 立即终止本地会话，避免用已失效 token 继续发请求
            AuroraApi.authToken = null
            AuroraApi.currentUserId = 0
            LocalStorage.logout(context)
            isLoggedIn = false
            currentUserId = 0
            AuroraApi.clearKickOffEvent()
        }
    }
    if (showKickOffDialog && kickOffDetail != null) {
        KickOffSecurityDialog(
            info = kickOffDetail!!,
            onConfirm = {
                showKickOffDialog = false
                kickOffDetail = null
            }
        )
    }

    fun doLogin(email: String, username: String, userId: Long, token: String) {
        //  先保存登录状态到本地（确保持久化优先于一切）
        val sig = AuroraApi.currentUserSignature
        val qq = AuroraApi.currentUserQQ
        // 显式带上 session_key（登录响应里的传输会话密钥）：漏传会让冷启动后的 TCP 认证失败
        LocalStorage.saveLogin(context, LoginResponse(userId, email, username, sig, token, qqNumber = qq, sessionKey = AuroraApi.sessionKey))
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
        // 新用户（从未在「AI 使用」里做过选择）→ 后台自动挑一个「活着的」免费 API 落库，
        // 界面上「选择免费 API」即显示为已选中；若无可用的则回落平台免费 AI（始终可用），
        // 保证用户拿到的永远不是一个已挂的条目。已有自己选择的用户不受影响。
        ioScope.launch {
            try { com.aurora.chat.ui.chat.AiChatManager.ensureDefaultFreeApi(context, userId) } catch (_: Exception) { }
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
                    com.aurora.chat.ui.profile.WordCountTracker.setServerBase(info.data!!.wordCount)
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
            }
        )
    } else if (!showForceProfileDialog) {
        LoginScreen(onLoginSuccess = { email, username, userId ->
            mainScope.launch {
                val token = AuroraApi.authToken ?: ""
                doLogin(email, username, userId, token)
            }
        })
    }

    // 封禁提示覆盖层（根层最后渲染，覆盖 MainContent / LoginScreen / 所有弹窗之上）
    if (showBanOverlay) {
        val act = context as? android.app.Activity
        // 优先用当前登录 userId，其次用 aurora_ban prefs 里存的（登录被拒场景）
        val banUserId = if (currentUserId > 0) currentUserId else banPrefs.getLong("user_id", 0L)
        BanOverlay(
            currentUserId = banUserId,
            reason = banReason,
            localEndAt = banLocalEndAt,
            duration = banDuration,
            onExit = { act?.finishAffinity() }
        )
    }

    // 解封弹窗（根层，在 BanOverlay 之后渲染；只有 showBanOverlay=false 时才显示）
    if (showUnbanPopup && !showBanOverlay) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x80000000)).clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.width(320.dp).padding(24.dp),
                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSurface()),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("账号已解封", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                    Box(
                        modifier = Modifier.heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth()
                    ) {
                        Text(
                            text = unbanPopupText.ifEmpty { "你的账号已被解封，可以继续使用了。" },
                            fontSize = 15.sp,
                            color = Color(0xFF4B5563),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { showUnbanPopup = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("好的", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                    }
                }
            }
        }
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
                                TextButton(onClick = { showBindEmailPrompt = false }) { Text("知道了", color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold) }
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
                .background(com.aurora.chat.ui.theme.appCardBg())
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
                    color = com.aurora.chat.ui.theme.appTextPrimary()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "请输入卡密以继续使用",
                    fontSize = 14.sp,
                    color = com.aurora.chat.ui.theme.appTextSecondary()
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

    // ── 位置权限：只在「从未询问过」时提示一次，此后进 App 一律静默 ──
    val locPrefs = remember {
        context.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE)
    }
    val locationPermissionGranted = remember { mutableStateOf(false) }
    var showLocationRationale by remember { mutableStateOf(false) }
    val locationRequestLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fine = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationPermissionGranted.value = fine || coarse
        if (locationPermissionGranted.value) {
            mainScope.launch {
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
                    if (!locPrefs.getBoolean("uploaded_once", false)) {
                        if (uploadCurrentLocation(context)) {
                            locPrefs.edit().putBoolean("uploaded_once", true).apply()
                        }
                    }
                } else if (!locPrefs.getBoolean("rationale_shown", false)) {
                    // 从未询问过 → 提示一次。先落盘再显示：即使用户点窗外/按返回键关掉，
                    // 也不会在下次进入时重来（用户要求：位置权限只允许弹一次）
                    locPrefs.edit().putBoolean("rationale_shown", true).apply()
                    showLocationRationale = true
                }
                // 已询问过且仍未授权：静默跳过，不再弹说明窗，也不弹系统授权框
            } catch (_: Exception) { }
        }
    }

    // 位置权限用途说明弹窗（仅弹出一次，之后直接走系统授权）
    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = {
                showLocationRationale = false
                locPrefs.edit().putBoolean("rationale_shown", true).apply()
            },
            title = { Text("位置权限", fontWeight = FontWeight.Bold) },
            text = { Text("该权限将会用于地域区分", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) },
            confirmButton = {
                val onLocConfirm: () -> Unit = {
                    showLocationRationale = false
                    context.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("rationale_shown", true).apply()
                    locationRequestLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
                                TextButton(onClick = onLocConfirm) { Text("确定", color = Color(0xFF1E40AF)) }
            },
            dismissButton = {
                val onLocDismiss: () -> Unit = {
                    showLocationRationale = false
                    context.getSharedPreferences("aurora_location", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("rationale_shown", true).apply()
                }
                                TextButton(onClick = onLocDismiss) { Text("拒绝", color = com.aurora.chat.ui.theme.appTextSecondary()) }
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
    onUserIdChange: (Long) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current


    var currentTab by remember { mutableStateOf(AuroraTab.Chat) }
    // 活动中心（由 MainActivity 统一控制，便于从任意页面跳转进入）
    var showActivity by remember { mutableStateOf(false) }
    var activityInitialPage by remember { mutableStateOf(0) }
    var connectionStatus by remember { mutableStateOf(ConnStatus.Connecting) }
    var statusBanner by remember { mutableStateOf<StatusBanner?>(null) }
    // 网络状态文字（顶部标题下方）：断线重连/无网络/刚连上；空=正常（无提示）
    var statusText by remember { mutableStateOf("") }
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
            if (dlUrl.isEmpty()) dlUrl = "https://www.example.com/tools/AuroraChat.apk"
            Box(
                modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()),
                contentAlignment = Alignment.Center
            ) {
                // 强制更新弹窗覆盖所有内容
                if (!showDownloadQueue) {
                    Card(
                        modifier = Modifier.widthIn(max = 320.dp).padding(24.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSurface()),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("版本更新", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
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
        if (dlUrl.isEmpty()) dlUrl = "https://www.example.com/tools/AuroraChat.apk"
        AlertDialog(
            onDismissRequest = { updateSkipped = true; updateData = null },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("发现新版本", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { Text(msg, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) },
            confirmButton = {
                val onUpdateClick = {
                    try {
                        com.aurora.chat.ui.tools.DownloadManager.addCommunityDownload("Aurora Chat", "aurora.apk", dlUrl, com.aurora.chat.R.drawable.ic_app_icon)
                        com.aurora.chat.ui.tools.DownloadManager.startDownloads(ctx)
                        showDownloadQueue = true
                        updateData = null
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(ctx, "下载失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                                                    TextButton(onClick = onUpdateClick) { Text("马上更新", color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold) }
                
            },
            dismissButton = {
                                                    TextButton(onClick = { updateSkipped = true; updateData = null }) {
                                        Text("稍后更新", color = com.aurora.chat.ui.theme.appTextSecondary())
                                    }
                
            }
        )
    }

    var showAbout by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    // 小游戏：独立全屏子页（从右侧滑入整屏），不再在「我的」页内部展开
    var showGamesPage by remember { mutableStateOf(false) }
    var showShortVideo by remember { mutableStateOf(false) }
    // 从「收藏」页点短视频封面跳转进来时，定位到指定视频
    var shortVideoInitialId by remember { mutableStateOf(0L) }
    // 从「动态」点进来时，需要自动打开评论区并定位/高亮的那条评论（服务端动态不带评论 id，按内容+作者匹配）
    var shortVideoCommentText by remember { mutableStateOf("") }
    var shortVideoCommentUserId by remember { mutableStateOf(0L) }
    var showFavorites by remember { mutableStateOf(false) }
    var showEditProfile by remember { mutableStateOf(false) }
    var showWebsiteHosting by remember { mutableStateOf(false) }
    var showBottle by remember { mutableStateOf(false) }
    var showRedeemCardKey by remember { mutableStateOf(false) }
    var showMessagePush by remember { mutableStateOf(false) }
    // 样式测试台（临时页）：从「其他」进入，纯白画布
    var showBlacklist by remember { mutableStateOf(false) }
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
    // 「我的发布」下的两个子页：社区（复用 MyPostsScreen）/ 视频（缩略图网格）
    var myPublishCommunityOpen by remember { mutableStateOf(false) }
    var myPublishVideosOpen by remember { mutableStateOf(false) }
    // 从「我的发布 - 视频」点开的播放页（独立于聊天/收藏进入的短视频页，保证覆盖层级正确）
    var myVideoPlayerOpen by remember { mutableStateOf(false) }
    var myVideoPlayerItem by remember { mutableStateOf<com.aurora.chat.ui.profile.SVItem?>(null) }
    var myVideosRefreshKey by remember { mutableStateOf(0) }
    var showCommunityActivity by remember { mutableStateOf(false) }
    var myPostsRefreshKey by remember { mutableStateOf(0) }
    var showPostDetail by remember { mutableStateOf(false) }
    var postDetailId by remember { mutableStateOf(0L) }
    var postDetailType by remember { mutableStateOf("") }
    var chatOpen by remember { mutableStateOf(false) }
    var chatFriendId by remember { mutableStateOf(0L) }
    // token 余额耗尽卡片（Chat/AI沙盒回调触发，MainActivity 顶层独立渲染，避免依赖子页面布局）
    var showTokenCard by remember { mutableStateOf(false) }
    var chatFriendName by remember { mutableStateOf("") }
    var chatContactSource by remember { mutableStateOf("") }
    var chatRefreshKey by remember { mutableStateOf(0) }
    // 群成员「发消息」打开一对一后，返回时还原「群聊 + 资料页」双层界面所需的上一次记录
    var chatBackGroupId by remember { mutableStateOf(0L) }
    var chatBackGroupName by remember { mutableStateOf("") }
    var chatBackProfileUid by remember { mutableStateOf(0L) }
    var chatBackProfileName by remember { mutableStateOf("") }
    var pendingProfileRestore by remember { mutableStateOf<Pair<Long, String>?>(null) }
    /** 读取上一次记录，把对话切回群聊，并连带恢复资料页（返回记忆）。
     *  同样走「先切出再切入」强制整屏卸载重建——否则 ChatConversationScreen 不 key()，
     *  只改 friendId 不会重载消息列表，会导致私聊内容串进群聊（关掉资料页后露出别人的对话）。 */
    fun restoreChatBackStack() {
        val gid = chatBackGroupId
        val gname = chatBackGroupName
        val pid = chatBackProfileUid
        val pname = chatBackProfileName
        chatBackGroupId = 0L; chatBackGroupName = ""; chatBackProfileUid = 0L; chatBackProfileName = ""
        // 先把待恢复资料置位，群聊重挂后由其 LaunchedEffect 自动重新弹出资料页
        pendingProfileRestore = if (pid > 0L) Pair(pid, pname) else null
        chatOpen = false
        scope.launch {
            kotlinx.coroutines.delay(230)
            chatFriendId = gid
            chatFriendName = gname
            chatOpen = true
        }
    }
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
    var showForceAvatar by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // 安全防护中心（从「其他」页进入的独立全屏页）
    var showSecurityCenter by remember { mutableStateOf(false) }
    var showProvideFreeApi by remember { mutableStateOf(false) }
    var showProvidePaidApi by remember { mutableStateOf(false) }
    var showOpenApply by remember { mutableStateOf(false) }
    // 启动调试（首次进入软件的启动页预览）：从「其他」页进入，内容后续接入
    var showStartupDebug by remember { mutableStateOf(false) }
    // 自定义：宿主扩展（启动画面 / 悬浮窗）管理页，从「其他」页进入
    var showCustomization by remember { mutableStateOf(false) }
    // 后台中心：从「其他」页进入的独立全屏页（内容后续接入，这里先保证骨架可用）
    var showBackendCenter by remember { mutableStateOf(false) }
    // 后台中心子入口：板块一「文件分发」/ 板块二（预留），各自独立全屏页
    var showFileDistribution by remember { mutableStateOf(false) }
    var showFdReserved2 by remember { mutableStateOf(false) }
    var devInitialTab by remember { mutableStateOf<String?>(null) }
    var showAvatarViewer by remember { mutableStateOf(false) }
    var avatarViewerBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
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

    // 应用锁状态
    val hasAnySecurity = SecurityStorage.isPasswordSet(context) ||
        SecurityStorage.isFingerprintEnabled(context) ||
        SecurityStorage.isGestureEnabled(context)
    var appUnlocked by remember {
        mutableStateOf(!SecurityStorage.isSecurityEnabled(context) || !hasAnySecurity)
    }

    // ── 后台锁定：用户离开应用（回桌面 / 切到别的应用 / 息屏）后，回到前台必须重新验证 ──
    // 判定见 [AppLockGuard]：只有「本次不可见**不是**应用自己拉起界面造成的」才算离开，
    // 因此切进选文件、拍照、分享、第三方授权、支付、系统设置，以及本应用的其他 Activity
    // （WebView / 终端 / 相机 / 五子棋 / 缓存管理）再回来，都不会被锁。
    // 用 ON_STOP 而不是 ON_PAUSE：系统权限弹窗、指纹弹窗这类 Dialog 只走 ON_PAUSE，不会误锁。
    // 条件在回调里**实时重读**而非取组合期快照 —— 设置里刚打开开关就立刻生效，无需重启应用。
    // 锁定后仍复用既有解锁层（appUnlocked=false 时展示的 AppUnlockScreen），不新增验证入口。
    val appLockLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(appLockLifecycleOwner) {
        val appLockObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val selfJump = AppLockGuard.consumeSelfLaunch()
                    if (!selfJump &&
                        SecurityStorage.isBackgroundLockEnabled(context) &&
                        SecurityStorage.isSecurityEnabled(context) &&
                        (SecurityStorage.isPasswordSet(context) ||
                            SecurityStorage.isFingerprintEnabled(context) ||
                            SecurityStorage.isGestureEnabled(context))
                    ) {
                        appUnlocked = false
                    }
                }
                // 回到前台：清掉「自己跳出去」标记（跳出去又回来、被半透明界面遮住只 pause 未 stop 都覆盖）
                androidx.lifecycle.Lifecycle.Event.ON_START,
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> AppLockGuard.reset()
                else -> {}
            }
        }
        appLockLifecycleOwner.lifecycle.addObserver(appLockObserver)
        onDispose { appLockLifecycleOwner.lifecycle.removeObserver(appLockObserver) }
    }

    // 开发者判断：以服务端判定的 is_developer 为准（本地 QQ/邮箱仅作兜底，
    // 本地 QQ 丢失/改动时不会再出现「明明是开发者却进不去开发者管理」）
    val isDeveloper = LocalStorage.isDeveloper(currentQQ, currentEmail) || AuroraApi.isDeveloperAccount
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
    // 社区帖子里的群卡片点击 → 打开群资料覆盖层（内部群 id）
    var communityGroupDetailId by remember { mutableStateOf<Long?>(null) }
    var communityGroupDetailName by remember { mutableStateOf("") }
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
            // 提前把联系人好友+分组预热进缓存：首次进「联系人」tab 时直接命中，
            // 避免后台回写 friends/groupData 占用主线程帧、拖慢底部图标放大动画。
            launch {
                com.aurora.chat.ui.contacts.prewarmContactCaches(context, currentUserId)
            }
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

    // "已连接"提示显示 2 秒后自动清除，回到无状态（标题居中、无状态文字）
    LaunchedEffect(statusText) {
        if (statusText == "已连接") {
            kotlinx.coroutines.delay(2000)
            statusText = ""
        }
    }

    // 真实的连接状态监测：定期 health 检查 + 设备网络状态
    LaunchedEffect(Unit) {
        // 辅助函数：health 检测
        suspend fun checkHealth(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // 先确保入口已定：https 反代没就绪时会自动退回明文端口，
                // 否则这里会一直拿到 404，状态栏永远显示"连接中"
                com.aurora.chat.data.api.AuroraApi.ensureBaseUrl()
                val req = okhttp3.Request.Builder().url("${com.aurora.chat.data.api.AuroraApi.serverUrl}/api/health").build()
                com.aurora.chat.data.api.HttpClient.client.newCall(req).execute().use { resp -> resp.code == 200 }
            } catch (_: Exception) { false }
        }

        suspend fun updateStatus(healthy: Boolean, deviceOnline: Boolean) {
            // 网络状态统一走「标题下方状态文字」，不再用横幅：避免断线时顶部横幅 + 聊天列表离线提示双横幅
            if (healthy) {
                if (connectionStatus != ConnStatus.Connected) {
                    connectionStatus = ConnStatus.Connected
                    statusText = "已连接" // 稍后由 LaunchedEffect 延时 2s 清除
                } else {
                    statusText = ""
                }
            } else if (!deviceOnline) {
                connectionStatus = ConnStatus.Disconnected
                statusText = "当前网络不可用"
            } else {
                connectionStatus = ConnStatus.Connecting
                statusText = "正在尝试重连"
            }
        }

        // 初始检测
        connectionStatus = ConnStatus.Connecting
        statusText = ""
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

        // 重连即时复检：设备网络一回线（NetworkMonitor.onAvailable）立即跑一次健康检测并刷新横幅，
        // 不必等下面 5s 轮询——消除"已重连好几秒、断连文案才开始消失"的延迟。
        launch {
            var lastOnline = com.aurora.chat.util.NetworkMonitor.isOnline.value
            com.aurora.chat.util.NetworkMonitor.isOnline.collect { online ->
                if (online && !lastOnline) {
                    updateStatus(checkHealth(), true)
                }
                lastOnline = online
            }
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
            }
            kotlinx.coroutines.delay(5000)
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

    // 切换到聊天 / 社区 Tab 时刷新
    LaunchedEffect(currentTab) {
        if (currentTab == AuroraTab.Chat) chatRefreshKey++
        // 切入社区即触发自动刷新（只在真正切到社区时；未切入不刷新）
        if (currentTab == AuroraTab.Community) communityRefreshKey++
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

    // 社区动态未读红点：首屏刷新一次，之后 TCP 推送即时刷新 + 30 秒兜底轮询
    LaunchedEffect(Unit) {
        CommunityActivityBadge.refresh(context)
        var lastPollAt = System.currentTimeMillis()
        while (true) {
            delay(1_000L)
            if (PendingCommunityActivity.hasUpdate) {
                PendingCommunityActivity.hasUpdate = false
                CommunityActivityBadge.refresh(context)
                lastPollAt = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastPollAt >= 30_000L) {
                CommunityActivityBadge.refresh(context)
                lastPollAt = System.currentTimeMillis()
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
    BackHandler(enabled = chatOpen) {
        // 群成员「发消息」进来的对话，返回时读取上一次记录，切回群聊（并连带恢复资料页）
        if (chatBackGroupId != 0L) restoreChatBackStack() else chatOpen = false
    }
    BackHandler(enabled = showDeveloperManagement) { showDeveloperManagement = false }
    BackHandler(enabled = showCreateGroup) { showCreateGroup = false }
    BackHandler(enabled = showSearchUser) { showSearchUser = false }
    BackHandler(enabled = showSearchGroup) { showSearchGroup = false }
    BackHandler(enabled = showFriendRequests) { showFriendRequests = false }
    BackHandler(enabled = showMyGroups) { showMyGroups = false }
    // 子页（隐私/消息推送/黑名单/错误日志）打开时，设置页仍在栈里，返回键必须先关子页。
    // Compose 的 BackHandler 是「后注册先响应」，所以这里排除子页做双保险，
    // 下面几条为子页注册的 handler 排在后面，正常情况下由它们优先接管。
    BackHandler(enabled = showSettings && !showSwitchAccount
        && !showPrivacy && !showMessagePush && !showBlacklist && !showErrorLog
        && !showDeveloperManagement) { showSettings = false }
    BackHandler(enabled = showSwitchAccount) { showSwitchAccount = false }

    BackHandler(enabled = showPrivacy) { showPrivacy = false }
    BackHandler(enabled = showGamesPage) { showGamesPage = false }
    BackHandler(enabled = showShortVideo) { showShortVideo = false }
    BackHandler(enabled = showFavorites) { showFavorites = false }
    // 这几个子页叠在设置页之上：注册在设置页 handler 之后 → 返回键优先关子页，回到设置页
    BackHandler(enabled = showMessagePush) { showMessagePush = false }
    BackHandler(enabled = showBlacklist) { showBlacklist = false }
    BackHandler(enabled = showErrorLog) { showErrorLog = false }
    BackHandler(enabled = showMore) { showMore = false }
    // 自定义页叠在「其他」之上：注册在其后 → 返回键优先关自定义页，回到「其他」页
    BackHandler(enabled = showCustomization) { showCustomization = false }
    // 后台中心叠在「其他」之上：注册在其后 → 返回键优先关后台中心，回到「其他」页
    BackHandler(enabled = showBackendCenter) { showBackendCenter = false }
    // 后台中心的子页叠在后台中心之上：返回键优先关子页，只回「后台中心」，不会跳级回「其他」
    BackHandler(enabled = showFileDistribution) { showFileDistribution = false }
    BackHandler(enabled = showFdReserved2) { showFdReserved2 = false }
    BackHandler(enabled = showProvideFreeApi) { showProvideFreeApi = false }
    BackHandler(enabled = showProvidePaidApi) { showProvidePaidApi = false }
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

    // 我的发布页返回：播放页 → 视频/社区子页 → 我的发布条目页，逐层回退
    // （播放页自身的返回处理在本层内容里注册，保证优先于下方视频网格的 BackHandler）
    BackHandler(enabled = myPublishVideosOpen) { myPublishVideosOpen = false }
    BackHandler(enabled = myPublishCommunityOpen) { myPublishCommunityOpen = false }
    BackHandler(enabled = showMyPosts) { showMyPosts = false }

    // 我的二维码 / 扫一扫 / 扫码用户资料 返回
    BackHandler(enabled = showScannedProfile) { showScannedProfile = false }
    BackHandler(enabled = showScan) { showScan = false }
    BackHandler(enabled = showMyQr) { showMyQr = false }

    // ── 双击返回退出（仅在所有面板/弹窗都关闭时生效） ──
    val isAtRoot = !chatOpen && !showDeveloperManagement
            && !showShortVideo && !showGamesPage && !showFavorites
            && !showCommunityActivity
            && !showCreateGroup && !showSearchUser && !showSearchGroup
            && !showFriendRequests && !showMyGroups && !showSettings && !showSwitchAccount
            && !showMessagePush && !showBlacklist && !showAbout && !showDonate && !showMore && !showProvideFreeApi && !showProvidePaidApi && !showOpenApply && !showAvatarViewer
            && !showBackendCenter
            && !showFileDistribution && !showFdReserved2
            && !showCommunitySub && !communityCreatePost && !showForceAvatar
            && !plusMenuOpen && !showLogoutDialog
            && !showErrorLog
            && !showPostDetail && appUnlocked
            && !showMyPosts && !myPublishCommunityOpen && !myPublishVideosOpen && !myVideoPlayerOpen
            && !showMyQr && !showScan && !showScannedProfile
            && !showUserProfile && !showEditProfile
            && !(DownloadManager.isShowing.value && !DownloadManager.isMinimized.value)
            && updateData == null
    var backPressedTime by remember { mutableStateOf(0L) }
    val rootContext = LocalContext.current
    BackHandler(enabled = isAtRoot) {
        // 「禁止退出」互锁生效时（双方都开启）：根页面拒绝退出，只提示。
        // 这里只拦「退出 App」这一种返回——各子页面的 BackHandler 先注册后生效，
        // 所以 App 内部的返回（关面板、回上一级）完全不受影响。
        if (PrivacyLockState.locked) {
            Toast.makeText(rootContext, "隐私锁定中，无法退出", Toast.LENGTH_SHORT).show()
            backPressedTime = 0L
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime < 2000) {
                (rootContext as? ComponentActivity)?.finishAffinity()
            } else {
                backPressedTime = now
                Toast.makeText(rootContext, "再按一次退出应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 用户资料页返回：优先于"双击退出"，返回即关闭资料页回到上一层
    // （放在 isAtRoot 之后注册，BackHandler 后注册者优先；资料页可叠在帖子详情/联系人之上）
    BackHandler(enabled = showUserProfile) { showUserProfile = false }

    // 动态页返回：关闭动态页回到「我的」，与顶部返回箭头、左右滑手势保持同一语义。
    // 之前这里刻意走了"双击退出"，结果在动态页左右滑不是返回上一层，而是弹"再按一次退出应用"，
    // 和用户在其它子页面的习惯完全相反。
    BackHandler(enabled = showCommunityActivity) {
        showCommunityActivity = false
    }

    // 全局关闭滚动到边界的橡皮筋拉伸感（聊天/联系人/社区等所有列表），避免恶心的回弹
    CompositionLocalProvider(
        LocalShowDividers provides true,
        LocalOverscrollFactory provides null
    ) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
    ) {
        // 顶部/底部栏跟随主题（深色下为深色背景）
        val barBg = com.aurora.chat.ui.theme.appBarBg()

        // ── 共享的主体结构：TopBar + Content ──
        Column(modifier = Modifier.fillMaxSize()) {
            // 社区 Tab 时砍掉顶部标题栏，让社区直接全屏；
            // 我的 Tab 也砍掉顶栏（去掉"我的"标题/在线状态/搜索用户），学微信"我"页：顶部直接是资料区
            if (currentTab != AuroraTab.Profile) {
                AuroraTopBar(
                    title = currentTab.label,
                    // 下拉刷新中：标题右侧显示蓝色转圈（状态分别由聊天/联系人/社区写入）
                    refreshing = when (currentTab) {
                        AuroraTab.Chat -> com.aurora.chat.ui.chat.ChatRefreshState.refreshing.value
                        AuroraTab.Server -> com.aurora.chat.ui.components.ContactsRefreshState.refreshing.value
                        AuroraTab.Community -> com.aurora.chat.ui.components.CommunityRefreshState.refreshing.value
                        else -> false
                    },
                    status = connectionStatus,
                    statusText = statusText,
                    statusBanner = statusBanner,
                    onBannerDismiss = { statusBanner = null },
                    onPlusClick = { plusMenuOpen = !plusMenuOpen },
                    backgroundColor = barBg,
                    showDivider = true,
                    plusMenuOpen = plusMenuOpen,
                    onDismissPlusMenu = { plusMenuOpen = false },
                    onSearchUser = { showSearchUser = true; showSearchGroup = false; plusMenuOpen = false },
                    onSearchGroup = { showSearchGroup = true; showSearchUser = false; plusMenuOpen = false },
                    onCreateGroup = { showCreateGroup = true; plusMenuOpen = false },
                    onScanQr = { showScan = true; plusMenuOpen = false },
                    // 加号入口只在聊天页出现；联系人/社区页右上角不放（右侧留等宽空白，标题仍居中）
                    showPlus = currentTab == AuroraTab.Chat
                )
            }
            // 已访问的 tab 集合：首次访问后常驻挂载，切 tab 只切可见性（不销毁/重建/首帧布局）
            var visitedTabs by remember { mutableStateOf(setOf(currentTab)) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // 已访问的 tab 全部常驻挂载，切 tab 只切可见性（alpha + zIndex）。
                // 不再销毁/重建/首帧布局 → 切过去那一帧主线程对内容页零工作，
                // 底部图标放大动画的帧不可能被联系人页的加载/布局抢占（结构上不可能慢半拍）。
                AuroraTab.entries.forEach { tab ->
                    if (tab in visitedTabs) {
                        val active = tab == currentTab
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (active) 1f else 0f)
                                .alpha(if (active) 1f else 0f)
                        ) {
                            when (tab) {
                        AuroraTab.Chat -> {
                        ChatScreen(
                            currentUserId = currentUserId,
                            refreshKey = chatRefreshKey,
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
                            },
                            onOpenGroupProfile = { gid, name ->
                                communityGroupDetailId = gid
                                communityGroupDetailName = name
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
                            onWebsiteHosting = { showWebsiteHosting = true },
                            onBottle = { showBottle = true },
                            onGomoku = {
                                context.startActivity(
                                    android.content.Intent(
                                        context,
                                        GomokuActivity::class.java
                                    )
                                )
                            },
                            onGamesPage = { showGamesPage = true },
                            onShortVideo = { showShortVideo = true },
                            onFavorites = { showFavorites = true },
                            onMore = { showMore = true },
                            onMyPosts = { showMyPosts = true },
                            onSettings = { showSettings = true },
                            onOpenCommunityActivity = { showCommunityActivity = true }
                        )
                        }
                            }
                        }
                    }
                }
            }
                AuroraBottomNav(
                    currentTab = currentTab,
                    onTabSelected = {
                        if (it == AuroraTab.Chat && currentTab == AuroraTab.Chat) {
                            if (chatOpen) chatOpen = false
                            chatScrollRequest = System.nanoTime()
                        }
                        currentTab = it
                        visitedTabs = visitedTabs + it
                    },
                    // 注意：导航栏内边距由 AuroraBottomNav 内部统一处理（Row 的 navigationBarsPadding），
                    //调用处不要再加一遍，否则会把底栏整体抬高一个系统导航栏高度、下方留出空白带。
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = barBg,
                    showDivider = true,
                    badges = mapOf(
                        AuroraTab.Server to friendRequestUnreadCount,
                        AuroraTab.Chat to chatUnreadCounts.values.sum(),
                        AuroraTab.Profile to CommunityActivityBadge.unreadCount.value
                    )
                )
        }


        // 好友申请（保持原样）
        SubScreen(
            visible = showFriendRequests,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(
                backgroundColor = Color.Black.copy(alpha = 0.4f),
                onClick = { showFriendRequests = false }
            )
        }
        SubScreen(
            visible = showFriendRequests,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
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
        SubScreen(
            visible = showMyGroups,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(
                backgroundColor = Color.Black.copy(alpha = 0.4f),
                onClick = { showMyGroups = false }
            )
        }
        SubScreen(
            visible = showMyGroups,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
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
                visible = showSearchUser,
                onOpenChat = { fId, fName ->
                    showSearchUser = false
                    chatContactSource = "search"
                    chatFriendId = fId
                    chatFriendName = fName
                    chatOpen = true
                }
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

        // 其他：独立全屏界面（由右侧滑入），覆盖顶部标题与底部导航
        SubScreen(
            visible = showMore,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                MorePage(
                    onClose = { showMore = false },
                    // 子页面保持「其他」在下层，返回时回到「其他」，而不是直接回「我的」
                    onAbout = { showAbout = true },
                    onRedeemCardKey = { showRedeemCardKey = true },
                    onOpenErrorLog = { showErrorLog = true },
                    onOpenSecurityCenter = { showSecurityCenter = true },
                    onProvideFreeApi = { showProvideFreeApi = true },
                    onProvidePaidApi = { showProvidePaidApi = true },
                    onOpenApplyThirdParty = { showOpenApply = true },
                    onOpenStartupDebug = { showStartupDebug = true },
                    onOpenCustomization = { showCustomization = true },
                    onOpenBackendCenter = { showBackendCenter = true }
                )
            }
        }

        // ==================== 启动调试（全屏页，左滑返回） ====================
        // 入口在「其他」页内；页面正文后续接入，这里只保证入口可用。
        SubScreen(
            visible = showStartupDebug,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 直接复用真实引导页：调试时看到的就是用户首次进入时的样子。
                // 这里**不写** onboarding 完成标记，也不真的退出应用，方便反复看。
                val dbgCtx = androidx.compose.ui.platform.LocalContext.current
                com.aurora.chat.ui.onboarding.OnboardingScreen(
                    permProvider = { onboardingPermsOf(dbgCtx) },
                    onPermClick = { openOnboardingPermPage(dbgCtx, it) },
                    onAgreed = { showStartupDebug = false },
                    onExitApp = { showStartupDebug = false }
                )
            }
        }

        // ==================== 自定义（宿主扩展管理 + 可视化编辑器） ====================
        SubScreen(
            visible = showCustomization,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.aurora.chat.ui.custom.CustomizationScreen(
                    onClose = { showCustomization = false }
                )
            }
        }

        // ==================== 后台中心（全屏页，左滑返回） ====================
        // 入口在「其他」页内；左侧两个板块入口，点击进入对应的全屏子页。
        // 覆盖顶部标题与底部导航、状态栏避让、事件不穿透、返回键/左滑均可正常关闭。
        SubScreen(
            visible = showBackendCenter,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.aurora.chat.ui.profile.BackendCenterScreen(
                    onClose = { showBackendCenter = false },
                    onOpenFileDistribution = { showFileDistribution = true },
                    onOpenReserved2 = { showFdReserved2 = true }
                )
            }
        }

        // ==================== 后台中心 · 板块一：文件分发（全屏子页） ====================
        // 渲染在后台中心之后 → 叠在其上；consumeTouches 拦截全部触摸，防止穿透点到底层；
        // 返回（返回键/左滑/顶部返回键）只关本页，回到后台中心，不跳级。
        SubScreen(
            visible = showFileDistribution,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.aurora.chat.ui.profile.FileDistributionScreen(
                    onClose = { showFileDistribution = false }
                )
            }
        }

        // ==================== 提供免费 API（全屏空白页，左滑返回） ====================
        SubScreen(
            visible = showProvideFreeApi,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ProvideFreeApiScreen(
                    onClose = { showProvideFreeApi = false }
                )
            }
        }

        // ==================== 提交付费 API（全屏空白页，左滑返回） ====================
        SubScreen(
            visible = showProvidePaidApi,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ProvidePaidApiScreen(
                    onClose = { showProvidePaidApi = false }
                )
            }
        }

        // ==================== 申请第三方接入 ====================
        SubScreen(
            visible = showOpenApply,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                OpenApplyScreen(
                    onClose = { showOpenApply = false }
                )
            }
        }

        // 安全防护中心：从「其他」页的安全防护入口进入，独立全屏
        SubScreen(
            visible = showSecurityCenter,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                SecurityCenterPage(
                    currentEmail = currentEmail,
                    onClose = { showSecurityCenter = false }
                )
            }
        }

        // 关于面板
        SubScreen(
            visible = showAbout && !showSettings,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(backgroundColor = Color.Black.copy(alpha = 0.4f), onClick = { showAbout = false })
        }
        SubScreen(
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
        // consumeTouches：整页吃掉未被控件消费的点击，避免空白处穿透点到背后的底部页签/列表
        SubScreen(
            visible = showSettings,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                    SettingsPage(
                        onClose = { showSettings = false },
                        onSwitchAccount = { showSwitchAccount = true },
                        // 与「我的」页同源:开发者/平台管理员/任一细粒度权限均可见开发者管理入口
                        isDeveloper = canAccessDevMgmt,
                        onOpenDeveloperManagement = { showDeveloperManagement = true },
                        onOpenErrorLog = {
                            showErrorLog = true
                        },
                        // 子页一律「叠在设置页之上」，不再关闭设置页：
                        // 旧写法进入子页时先 showSettings=false，子页返回后就直接回到「我的」页（跳了两级）。
                        onPrivacySettings = {
                            showPrivacy = true
                        },
                        onBlacklist = {
                            showBlacklist = true
                        },
                        onLogout = {
                            showSettings = false
                            showLogoutDialog = true
                        },
                        onMessagePush = {
                            showMessagePush = true
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

        // 隐私设置：独立全屏界面（由右侧滑入）
        // 必须渲染在设置页【之后】：后声明的在上面，才能叠在设置页之上显示；
        // 放在设置页之前的话会被设置页盖住，只能靠关闭设置页来露出（返回就跳两级）。
        SubScreen(
            visible = showPrivacy,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PrivacySettingsPage(onClose = { showPrivacy = false })
            }
        }

        // 小游戏：独立全屏子页（与设置/隐私同级），从右侧滑入占满整屏
        SubScreen(
            visible = showGamesPage,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appCardBg())) {
                com.aurora.chat.ui.profile.GamesScreen(
                    onGomoku = {
                        context.startActivity(
                            android.content.Intent(context, GomokuActivity::class.java)
                        )
                    },
                    onBottle = { showBottle = true },
                    onBack = { showGamesPage = false }
                )
            }
        }

        // 收藏：独立全屏子页（与「短视频」「小游戏」同级），从右侧滑入占满整屏（当前为占位页）
        SubScreen(
            visible = showFavorites,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                com.aurora.chat.ui.profile.FavoritesScreen(
                    onBack = { showFavorites = false },
                    reloadKey = if (showShortVideo) 1 else 0,
                    onOpenShortVideo = { id ->
                        // 收藏页保持打开（本 SubScreen 就在它下面），短视频盖在上面；
                        // 短视频返回箭头关闭后自然回到收藏，不会跳级。
                        shortVideoInitialId = id
                        shortVideoCommentText = ""
                        shortVideoCommentUserId = 0L
                        showShortVideo = true
                    }
                )
            }
        }

        // 聊天界面
        // 注：swipeBack 必须关掉。开启时本层会注册 PredictiveBackHandler，其 onDismiss 直接关掉整个聊天，
        // 而 Android 的预测返回回调（OnBackInvokedCallback）优先级高于内部覆盖层的普通 BackHandler，
        // 导致「群聊信息 / 群成员 / 用户资料」等内部层级被跳过、左滑直接跳两级。
        // 关掉后，左滑走普通 BackHandler 链，内部覆盖层各自拦截，返回逐层正确。
        SubScreen(
            visible = chatOpen,
            enter = slideInHorizontally(spring(dampingRatio = 0.8f, stiffness = 300f)) { it } + fadeIn(spring()),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
            swipeBack = false,
            onDismiss = {
                if (chatBackGroupId != 0L) restoreChatBackStack()
                else { chatOpen = false; chatContactSource = ""; chatRefreshKey++ }
            }
        ) {
            // 关键修复：按 friendId 整屏重建，切换群聊↔私聊时消息必按新 id 重读，根除「标题是群聊、内容却是上一个人私聊」的串台
            key(chatFriendId) {
            ChatConversationScreen(
                currentUserId = currentUserId,
                currentUserName = com.aurora.chat.data.api.AuroraApi.currentUserName,
                friendId = chatFriendId,
                friendName = chatFriendName,
                onBack = {
                    // 群成员「发消息」进来的对话，返回时读取上一次记录，切回群聊（并连带恢复资料页）
                    if (chatBackGroupId != 0L) restoreChatBackStack()
                    else {
                        chatOpen = false
                        chatContactSource = ""
                        chatRefreshKey++
                    }
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
                    onOpenShortVideo = { vid ->
                        // 从聊天内点「短视频分享卡片」→ 打开短视频模块并定位到该视频。
                        // 短视频 SubScreen 已移至聊天 SubScreen 之后渲染，这里直接叠上即可盖住聊天；
                        // 返回时聊天原地还在。
                        shortVideoInitialId = vid
                        shortVideoCommentText = ""
                        shortVideoCommentUserId = 0L
                        showShortVideo = true
                    },
                    onOpenNewChat = { uid, name, source ->
                        chatContactSource = source
                        if (chatOpen && chatFriendId != uid) {
                            // 群成员来源：记下上一次界面（当前群聊 + 目标成员），供返回时还原
                            if (source.startsWith("group:")) {
                                chatBackGroupId = chatFriendId
                                chatBackGroupName = chatFriendName
                                chatBackProfileUid = uid
                                chatBackProfileName = name
                            } else {
                                chatBackGroupId = 0L
                            }
                            // 已有对话（如群聊）打开且目标不同：先切出当前对话，
                            // 等退出动画结束后，再切入目标用户的一对一聊天，
                            // 强制 ChatConversationScreen 整体卸载重建，避免旧会话状态串台
                            chatOpen = false
                            scope.launch {
                                kotlinx.coroutines.delay(230)
                                chatFriendId = uid
                                chatFriendName = name
                                chatOpen = true
                            }
                        } else {
                            chatBackGroupId = 0L
                            chatFriendId = uid
                            chatFriendName = name
                            chatOpen = true
                        }
                    },
                contactSource = chatContactSource,
                onContactSourceConsumed = { chatContactSource = "" },
                restoreProfile = pendingProfileRestore,
                onRestoreProfileConsumed = { pendingProfileRestore = null },
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
        }

        // ==================== 社区动态 ====================
    // 排在聊天之后、短视频之前：视频/个人资料要能盖在动态之上，返回时动态原地还在。
    SubScreen(
        visible = showCommunityActivity,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize(),
        consumeTouches = true
    ) {
        CommunityActivityScreen(
            onBack = {
                showCommunityActivity = false
                scope.launch { CommunityActivityBadge.refresh(context) }
            },
            onOpenPost = { activity ->
                if (activity.action_type == "follow") {
                    // 关注动态：target_id 存的是「关注者」的 id —— 点进去看是谁关注了我。
                    // 不能走下面的帖子详情分支，否则会拿用户 id 当帖子 id 去查，报"帖子不存在"。
                    profileUserId = activity.target_id
                    profileUserName = activity.username
                    showUserProfile = true
                } else if (activity.target_type == "shortvideo") {
                    // 短视频相关动态（评论/回复/评论点赞）→ 切入短视频模块、定位到该视频，
                    // 并在该视频里自动打开评论区、滚动定位并对目标评论闪烁两下
                    shortVideoInitialId = activity.target_id
                    shortVideoCommentText = activity.comment_content
                    shortVideoCommentUserId = activity.user_id
                    showShortVideo = true
                } else {
                    postDetailId = activity.target_id
                    postDetailType = activity.target_type
                    showPostDetail = true
                }
            }
        )
    }

    // 短视频：独立全屏子页，从右侧滑入占满整屏。
    // 必须排在「聊天」「动态」等 SubScreen 之后（AnimatedVisibility 后来居上）：
    // 之前本块排在动态之前，从「动态」点进来时视频被压到动态页下面就是这个原因。
    SubScreen(
        visible = showShortVideo,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize(),
        consumeTouches = true
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            com.aurora.chat.ui.profile.ShortVideoScreen(
                isDeveloper = isDeveloper,
                initialVideoId = shortVideoInitialId,
                initialCommentText = shortVideoCommentText,
                initialCommentUserId = shortVideoCommentUserId,
                onBack = {
                    showShortVideo = false
                    // 清掉一次性定位参数，避免下次从「收藏」/聊天卡片再次进入时又跳到旧视频 / 重开评论区
                    shortVideoInitialId = 0L
                    shortVideoCommentText = ""
                    shortVideoCommentUserId = 0L
                },
                // 短视频页在导航里不会被销毁，只是被这些全屏层压住。
                // 不告诉它"你已经被盖住了"，播放器就会继续出声（切到用户资料还在放的原因）。
                isForeground = !showUserProfile && !showPostDetail &&
                    !showCommunityActivity && !showEditProfile,
                onOpenUserProfile = { uid, name ->
                    profileUserId = uid
                    profileUserName = name
                    showUserProfile = true
                }
            )
        }
    }


        // ==================== 我的二维码 ====================
        // 遮罩层（拦截穿透，防止点到背面"我的"页头像等）
        SubScreen(
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
        SubScreen(
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
        SubScreen(
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
        SubScreen(
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

        // ==================== 黑名单（全屏页，内容待补充） ====================
        SubScreen(
            visible = showBlacklist,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            com.aurora.chat.ui.profile.BlacklistScreen(currentUserId = currentUserId, onBack = { showBlacklist = false })
        }

        // ==================== 编辑资料（从右侧滑入的独立界面） ====================
        SubScreen(
            visible = showEditProfile,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            com.aurora.chat.ui.profile.EditProfileScreen(
                userId = currentUserId,
                onBack = { showEditProfile = false }
            )
        }

        // ==================== 网站托管（独立直达，不再经过活动中心） ====================
        SubScreen(
            visible = showWebsiteHosting,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            com.aurora.chat.ui.server.ServerScreen(direct = true, onBack = { showWebsiteHosting = false })
        }

        // ==================== 漂流瓶（独立直达） ====================
        SubScreen(
            visible = showBottle,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            com.aurora.chat.ui.activity.BottleScreen(onBack = { showBottle = false })
        }

        // ==================== 扫一扫 ====================
        SubScreen(
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
        SubScreen(
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
                },
                onGroupScanned = { gid, gname ->
                    // 扫码识别群二维码并加入成功后：切入群资料页（群详情），不直接进对话
                    showScan = false
                    communityGroupDetailId = gid
                    communityGroupDetailName = gname
                }
            )
        }

        // ==================== 扫码后用户资料 ====================
        SubScreen(
            visible = showScannedProfile,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            // 相册选图：先解析待解析图片（后台），解析出 uid 前显示"加载中"
            LaunchedEffect(scanPendingImage) {
                val bytes = scanPendingImage ?: return@LaunchedEffect
                // 同时保留原始文本：站内码解析失败时用它走通用二维码处理（链接/文本/电话等）
                val parsed = withContext(Dispatchers.IO) {
                    val bmp = com.aurora.chat.ui.qr.decodeBitmapFromBytes(bytes)
                    val qrText = bmp?.let { com.aurora.chat.ui.qr.parseQrFromBitmap(it) }
                    qrText to qrText?.let { com.aurora.chat.data.api.AuroraApi.resolveQrCode(it) }
                }
                val qrText = parsed.first
                val result = parsed.second
                if (result?.success == true && result.data != null) {
                    val d = result.data!!
                    // 相册选图识别到群二维码：切入群资料页（群详情），不直接进对话
                    if (d.isGroup && d.groupId > 0) {
                        scanPendingImage = null
                        showScannedProfile = false
                        communityGroupDetailId = d.groupId
                        communityGroupDetailName = d.groupName.ifEmpty { "群聊${d.groupId}" }
                    } else if (d.userId > 0) {
                        scannedUserId = d.userId
                        scannedUserName = d.username
                    } else {
                        // 站内码但内容无效：能解出文本就按通用二维码处理
                        if (!qrText.isNullOrBlank()) {
                            com.aurora.chat.ui.qr.handleGenericQrText(context, qrText)
                        } else {
                            com.aurora.chat.ui.qr.toast(context, d.message.ifEmpty { "无法识别该二维码" })
                        }
                        showScannedProfile = false
                        showScan = true
                        scanPendingImage = null
                    }
                } else {
                    // 不是站内码（或解析失败）：链接直接打开、电话/邮件/短信调起系统、文本复制
                    if (!qrText.isNullOrBlank()) {
                        if (!com.aurora.chat.ui.qr.handleGenericQrText(context, qrText)) {
                            com.aurora.chat.ui.qr.toast(context, "无法打开该内容")
                        }
                    } else {
                        com.aurora.chat.ui.qr.toast(context, result?.message ?: "无法识别该二维码")
                    }
                    showScannedProfile = false
                    showScan = true
                    scanPendingImage = null
                }
            }
            if (scannedUserId > 0) {
                UserProfileCard(
                    userId = scannedUserId,
                    userName = scannedUserName,
                    currentUserId = currentUserId,
                    onBack = { showScannedProfile = false },
                    onDeleteFriend = { showScannedProfile = false },
                    onOpenChat = { uid, name ->
                        showScannedProfile = false
                        chatFriendId = uid
                        chatFriendName = name
                        chatOpen = true
                    }
                )
            } else {
                // 加载中占位界面
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(com.aurora.chat.ui.theme.appSurface())
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(color = Color(0xFF2563EB))
                        Spacer(Modifier.height(16.dp))
                        Text("正在识别二维码…", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            }
        }

        // ==================== 我的发布（置于最底层覆盖层，确保详情/发帖/用户资料显示在其之上） ====================
        // 条目页：社区 / 视频（与「小游戏」的五子棋/漂流瓶同构）
        SubScreen(
            visible = showMyPosts,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            com.aurora.chat.ui.profile.MyPublishScreen(
                onCommunity = { myPublishCommunityOpen = true },
                onVideos = { myPublishVideosOpen = true },
                onBack = { showMyPosts = false }
            )
        }

        // 我的发布 - 社区：复用社区信息流（仅当前用户自己的帖子）
        SubScreen(
            visible = myPublishCommunityOpen,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            MyPostsScreen(
                currentUserId = currentUserId,
                title = "社区",
                onBack = { myPublishCommunityOpen = false },
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
                onOpenGroupProfile = { gid, name ->
                    communityGroupDetailId = gid
                    communityGroupDetailName = name
                },
                refreshKey = myPostsRefreshKey
            )
        }

        // 我的发布 - 视频：缩略图网格，展示自己发布的短视频
        SubScreen(
            visible = myPublishVideosOpen,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            com.aurora.chat.ui.profile.MyVideosScreen(
                onBack = {
                    myPublishVideosOpen = false
                    myVideosRefreshKey++
                },
                onOpenVideo = { item ->
                    myVideoPlayerItem = item
                    myVideoPlayerOpen = true
                },
                refreshKey = myVideosRefreshKey
            )
        }

        // 我的发布 - 视频播放页：复用短视频播放器（定位到所选视频），返回后刷新网格
        SubScreen(
            visible = myVideoPlayerOpen,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            // 播放页返回：在本层注册，优先于下方「视频」网格注册的 BackHandler，
            // 否则按返回会被下层抢先关掉网格、播放页却还盖在上面。
            BackHandler(enabled = true) {
                myVideoPlayerOpen = false
                myVideosRefreshKey++
            }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                com.aurora.chat.ui.profile.ShortVideoScreen(
                    isDeveloper = isDeveloper,
                    initialVideoId = myVideoPlayerItem?.id ?: 0L,
                    initialItem = myVideoPlayerItem,
                    isForeground = !showUserProfile && !showPostDetail,
                    onBack = {
                        myVideoPlayerOpen = false
                        myVideosRefreshKey++
                    },
                    onOpenUserProfile = { uid, name ->
                        profileUserId = uid
                        profileUserName = name
                        showUserProfile = true
                    }
                )
            }
        }

        // ==================== 联系人个人资料（全屏从右侧滑入，覆盖顶部标题与底部导航） ====================
        SubScreen(
            visible = showUserProfile,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            // 高 zIndex：保证从帖子详情打开资料时，资料始终覆盖在详情之上
            // （Compose 默认后声明者画在上层，而本覆层声明早于帖子详情，不加 zIndex 会被详情盖住）
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                // 资料页自身拦截返回：资料页是顶层覆盖层，常叠在「群聊信息/成员列表」等
                // 自带 BackHandler 的页面之上；若不在内容里注册，返回会优先触发下层页面的
                // BackHandler（如群设置的 anySubPageOpen）先把底层子面板关掉、而资料页还在 → 返回跳级。
                // 注册在内容里 → 组合顺序比下层页面更深 → 优先接管返回，逐层回到上一层。
                BackHandler(enabled = showUserProfile) { showUserProfile = false }
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
        SubScreen(
            visible = showDeveloperManagement,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(backgroundColor = Color.Black.copy(alpha = 0.4f), onClick = { showDeveloperManagement = false })
        }
        SubScreen(
            visible = showDeveloperManagement,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                com.aurora.chat.ui.components.EventBlocker()
                DeveloperManagementScreen(
                    onBack = { showDeveloperManagement = false },
                    isDeveloper = isDeveloper,
                    canAccess = canAccessDevMgmt,
                    initialTab = devInitialTab
                )
            }
        }

        // ===== 兑换卡密（全新全屏页：只有标题、输入框、兑换按钮） =====
        SubScreen(
            visible = showRedeemCardKey,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
        ) {
            var cardKeyInput by remember { mutableStateOf("") }
            var redeeming by remember { mutableStateOf(false) }
            androidx.activity.compose.BackHandler(enabled = showRedeemCardKey) {
                if (!redeeming) showRedeemCardKey = false
            }
            val onRedeemClick: () -> Unit = redeem@{
                val key = cardKeyInput.trim()
                if (key.isEmpty()) {
                    Toast.makeText(context, "请输入卡密", Toast.LENGTH_SHORT).show()
                    return@redeem
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
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .imePadding()
            ) {
                com.aurora.chat.ui.components.SettingsTopBar(
                    title = "兑换卡密",
                    onBack = { if (!redeeming) showRedeemCardKey = false }
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    OutlinedTextField(
                        value = cardKeyInput,
                        onValueChange = { cardKeyInput = it },
                        placeholder = { Text("请输入卡密", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onRedeemClick,
                        enabled = !redeeming,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (redeeming) "兑换中..." else "兑换", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }

        // ===== 切换账号面板（从右侧滑入） =====
        SubScreen(
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
        SubScreen(
            visible = showCreateGroup,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(200))
        ) {
            EventBlocker(backgroundColor = Color.Black.copy(alpha = 0.4f), onClick = { showCreateGroup = false })
        }
        SubScreen(
            visible = showCreateGroup,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            CreateGroupScreen(
                currentUserId = currentUserId,
                onBack = { showCreateGroup = false },
                onCreated = { groupId, createdName ->
                    showCreateGroup = false
                    chatRefreshKey++
                    if (groupId > 0L) {
                        // 群会话 id = -(1000 + 内部群 id)，与 ChatScreen / ChatConversationScreen 的映射一致。
                        // 创建完直接落进这个群的对话内，不再停在聊天列表里让用户自己找。
                        chatBackGroupId = 0L
                        chatBackGroupName = ""
                        chatBackProfileUid = 0L
                        chatBackProfileName = ""
                        pendingProfileRestore = null
                        chatContactSource = ""
                        chatFriendId = -(1000L + groupId)
                        chatFriendName = createdName
                        chatOpen = true
                        currentTab = AuroraTab.Chat
                    }
                }
            )
        }

        // 入群申请审批全屏面板
        SubScreen(
            visible = showJoinRequestFullScreen && joinRequestData != null,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize(),
            consumeTouches = true
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
    SubScreen(
        visible = !appUnlocked,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(400)) + scaleOut(targetScale = 0.8f, animationSpec = tween(400)),
        consumeTouches = true
    ) {
        AppUnlockScreen(
            currentEmail = currentEmail,
            onUnlock = { appUnlocked = true }
        )
    }

    // ==================== 错误日志全屏页面（从右侧滑入） ====================
    SubScreen(
        visible = showErrorLog,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize(),
        consumeTouches = true
    ) {
        ErrorLogScreen(
            onBack = { showErrorLog = false }
        )
    }

    // ── 退出登录确认弹窗 ──
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("退出登录", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = { Text("确定要退出当前账号吗？\n退出后需重新登录才能使用。", fontSize = 14.sp, color = Color(0xFF4B5563)) },
            confirmButton = {
                val onLogoutConfirm: () -> Unit = {
                    showLogoutDialog = false
                    com.aurora.chat.ui.profile.OnlineTimeTracker.stop(context)
                    LocalStorage.logout(context)
                    TcpService.stop(context)
                    DownloadManager.closeAll()
                    onLogout()
                }
                                androidx.compose.material3.TextButton(onClick = onLogoutConfirm) {
                                    Text("确认退出", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E40AF))
                                }
            },
            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { showLogoutDialog = false }) {
                                    Text("取消", color = com.aurora.chat.ui.theme.appTextHint())
                                }
            }
        )
    }

    // ==================== 社区子页面（全屏从右侧滑入，覆盖顶栏和底栏） ====================
    SubScreen(
        visible = showCommunitySub,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize(),
        consumeTouches = true
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
    SubScreen(
        visible = showPostDetail,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize(),
        consumeTouches = true
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
                    },
                    onOpenGroupProfile = { gid, name ->
                        communityGroupDetailId = gid
                        communityGroupDetailName = name
                    }
        )
    }

    // ==================== 社区群卡片 → 群资料（全屏从右侧滑入，覆盖顶栏与底栏） ====================
    var lastCommunityGroupId by remember { mutableStateOf(0L) }
    var lastCommunityGroupName by remember { mutableStateOf("") }
    LaunchedEffect(communityGroupDetailId) {
        if (communityGroupDetailId != null) {
            lastCommunityGroupId = communityGroupDetailId!!
            lastCommunityGroupName = communityGroupDetailName
        }
    }
    SubScreen(
        visible = communityGroupDetailId != null,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize(),
        consumeTouches = true
    ) {
        if (lastCommunityGroupId > 0) {
            com.aurora.chat.ui.chat.GroupDetailScreen(
                groupId = lastCommunityGroupId,
                groupName = lastCommunityGroupName,
                groupDisplayId = 0L,
                groupSignature = "",
                // 社区卡片/扫群二维码的进入者未必是成员（可能是被踢后想加回来的），
                // 不能乐观地当"已加入"——否则底部一直「发消息」，加不回群。
                // 成员与否交给 GroupDetailScreen 内部的 user_role 判定。
                initialJoined = false,
                onBack = { communityGroupDetailId = null },
                onOpenGroupChat = { convId, name ->
                    communityGroupDetailId = null
                    chatFriendId = convId
                    chatFriendName = name
                    chatOpen = true
                    currentTab = AuroraTab.Chat
                },
                onDismissSelf = { communityGroupDetailId = null }
            )
        }
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
    SubScreen(
        visible = communityCreatePost,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it },
        modifier = Modifier.fillMaxSize(),
        consumeTouches = true
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

    // ==================== AI 任务被系统中断提示 ====================
    // 与闪退拦截的区别：这类中断（后台被清理 / 被强行停止 / ANR）不会产生未捕获异常，
    // CrashHandler 抓不到，所以必须靠 AITaskGuard 留下的标记来发现。
    var agentKilledNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        agentKilledNotice = com.aurora.chat.AITaskGuard.consumeAbnormalTermination(context)
        // 【崩溃可追溯·恢复】把上次被强杀时留下的追加式执行日志折回对应消息：
        // 工具调用清单（「调用 N 个工具」）与文件改动（「查看所有改动」）都恢复出来，
        // 用户下次打开即可完整看到"中断前调了什么、改了什么"。
        // 判据不依赖上面那个标记（它可能被 forceReset 或数据清理搞失真），只看"日志文件还在"这件事本身 ——
        // 正常收尾必定删文件，所以残留即中断。
        try { com.aurora.chat.ui.chat.AIJournal.recoverAll(context) } catch (_: Throwable) {}
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
        AlertDialog(
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
                    Text("知道了", color = com.aurora.chat.ui.theme.appTextHint())
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
    SubScreen(
        visible = showAvatarViewer,
        enter = fadeIn(tween(60)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
        consumeTouches = true
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
        // 液态模式已移除，保留经典 Dialog
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    // 让弹窗内容延伸到状态栏下方：遮罩才能盖住顶部系统栏。
                    // 不开这个时遮罩只到状态栏下沿，顶部会留一条亮带（公告详情页一直是开的，就这里漏了）。
                    decorFitsSystemWindows = false
                )
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

        // ===== token 余额耗尽卡片（Chat/AI 沙盒回调触发，顶层独立渲染，不依赖子页面布局） =====
        if (showTokenCard) {
            TokenInsufficientCard(
                onRecharge = {
                    showTokenCard = false
                    chatOpen = false
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

@Composable
private fun MenuButton(text: String, onClick: () -> Unit, unreadCount: Int = 0) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(com.aurora.chat.ui.theme.appCardBg())
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = com.aurora.chat.ui.theme.appTextPrimary()
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
    currentUserId: Long,
    reason: String,
    localEndAt: Long,
    duration: Long,
    onExit: () -> Unit
) {
    val ctx = LocalContext.current
    val username = remember(currentUserId) {
        com.aurora.chat.data.local.LocalStorage.getUserName(ctx, currentUserId).ifBlank { "用户" }
    }

    // 封死返回键，不能躲
    BackHandler(enabled = true) { /* do nothing */ }

    // ★ 倒计时终点是本地时钟算出的绝对时间戳，不依赖任何服务端时间戳
    // 每次 checkSrv 回来根层会重算 banLocalEndAt 传入，LaunchedEffect 重启重新同步
    var remainSecs by remember { mutableStateOf((localEndAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)) }

    LaunchedEffect(localEndAt) {
        remainSecs = (localEndAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)
        while (remainSecs > 0) {
            kotlinx.coroutines.delay(1000)
            remainSecs = (localEndAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)
        }
    }

    val hms = remember(remainSecs) {
        String.format("%02d:%02d:%02d", remainSecs / 3600, (remainSecs % 3600) / 60, remainSecs % 60)
    }
    val durStr = remember(duration) {
        val d = duration / 86400; val h = (duration % 86400) / 3600; val m = (duration % 3600) / 60
        when { d > 0 -> "${d}天${h}小时"; h > 0 -> "${h}小时${m}分钟"; else -> "${m}分钟" }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "🚫", fontSize = 72.sp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = "您的账号已被封禁",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFDC2626),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "昵称：$username\nID：$currentUserId\n原因：$reason",
                fontSize = 15.sp,
                color = Color(0xFF4B5563),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "违规处罚 $durStr",
                fontSize = 15.sp,
                color = Color(0xFFFCA5A5),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "距离解封还有",
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
            Spacer(Modifier.height(48.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                BanButton(
                    text = "退出软件",
                    color = Color(0xFFDC2626),
                    modifier = Modifier.weight(1f)
                ) { onExit() }
            }
        }
    }
}

@Composable
private fun BanButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
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
        modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).clickable(enabled = false) {},
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
            Text("QQ群：${com.aurora.chat.util.QqGroupLink.GROUP_NUMBER}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E40AF))
                    .clickable {
                        try {
                            ctx.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(com.aurora.chat.util.QqGroupLink.URL)
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

/**
 * 踢下线 / 异地登录安全提醒弹窗。
 * 单一出口：由 AuroraApi.kickedOffEvent 触发（TCP 主动推送或 HTTP 中间件兜底共用同一事件）。
 * 文案权威、不啰嗦；带账号 / 登录时间 / IP 占位符；官方群号蓝色可点击跳转 QQ。
 */
@Composable
@Suppress("DEPRECATION")
private fun KickOffSecurityDialog(
    info: AuroraApi.KickOffInfo,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }
    val mainLine = if (info.ip.isNotBlank() && info.loginTs > 0) {
        val timeText = sdf.format(Date(info.loginTs * 1000))
        "您的账号 ${info.account} 于 $timeText 在 IP '${info.ip}' 登录。"
    } else {
        when {
            info.reason.contains("已被管理员踢出") ->
                "您的账号 ${info.account} 已被管理员强制下线。"
            info.reason.contains("在其他设备登录") ->
                "您的账号 ${info.account} 已在其他设备登录，当前会话已终止。"
            else -> "您的账号 ${info.account} 已被强制下线。"
        }
    }
    AlertDialog(
        onDismissRequest = onConfirm,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("我知道了", color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text("账号安全提醒", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = mainLine,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                val annotated = buildAnnotatedString {
                    append("如非本人操作，请前往QQ官方群")
                    pushStringAnnotation("qq_group", "open")
                    withStyle(SpanStyle(color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold)) {
                        append(com.aurora.chat.util.QqGroupLink.GROUP_NUMBER)
                    }
                    pop()
                    append("或联系开发者处理。")
                }
                ClickableText(
                    text = annotated,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = { offset ->
                        annotated.getStringAnnotations("qq_group", offset, offset).firstOrNull()?.let {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(com.aurora.chat.util.QqGroupLink.URL))
                                )
                            }
                        }
                    }
                )
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)
    )
}




