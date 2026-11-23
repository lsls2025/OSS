package com.aurora.chat.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.chat.ui.theme.AppColors
import com.aurora.chat.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/**
 * 首次进入软件的引导流程（4 步）。
 *
 * ## 为什么要有它
 * 改动前 `MainActivity.onCreate` 一进来就 `requestStartupPermissions()` 弹系统权限框，
 * 未全部授予就停在权限门里、按返回键直接退出应用 —— 等于"第一次打开就是被要权限"。
 * 现在改成先讲清这是什么、能干什么，再让用户自己决定授权，且**不授权也能进入**。
 *
 * ## 页序
 * 1. 品牌页（高清图标 + 标语）
 * 2. 权限页（悬浮窗 / 位置 / 安装外部应用，全部可跳过）
 * 3. 聊天记录云端保存
 * 4. 隐私政策与服务协议（必须滑到底才能同意）
 *
 * ## 文案纪律
 * 每条都必须能在代码里找到对应实现，不写放之四海皆准的模板话术，也不对用户说教。
 */
@Composable
fun OnboardingScreen(
    permProvider: () -> List<OnboardingPerm>,
    onPermClick: (String) -> Unit,
    onAgreed: () -> Unit,
    onExitApp: () -> Unit
) {
    val appColors = LocalAppColors.current
    val primary = MaterialTheme.colorScheme.primary
    var step by remember { mutableIntStateOf(0) }
    val total = 4

    // 从系统设置页授权返回时，Activity 走 ON_RESUME。这里跟着刷新一次权限状态，
    // 否则会出现"明明已经授权，界面仍显示去设置"——也就是只靠首帧那次读取的经典坑。
    var permTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val perms = remember(permTick) { permProvider() }

    fun next() { if (step < total - 1) step++ }
    fun prev() { if (step > 0) step-- else onExitApp() }

    // 系统返回键：第 2 步及以后＝回上一页；第 1 步放行（交给系统退出应用）
    androidx.activity.compose.BackHandler(enabled = step > 0) { prev() }

    Box(
        Modifier
            .fillMaxSize()
            .background(appColors.surface)
            // 【拦住事件穿透】引导页是覆盖在主界面之上的独立层，若不消费触摸，
            // 没被按钮吃掉的点击/滑动会穿透到下层界面（实测能点到底下的东西）。
            // 必须用 Final 阶段兜底：事件分发的 Final 阶段按「子→父」回溯，
            // 子组件（按钮、verticalScroll）已在 Initial/Main 阶段先处理并消费，
            // 到这里只剩未被消费的触摸会被吃掉——既拦住穿透，又不会干扰内部滚动。
            // 之前的 Main 阶段全量消费是「中间区域滑不动、右侧偶发可动」的根因。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部只留状态栏避让；返回统一走底部的「上一步」，不再依赖左上角箭头
            Spacer(Modifier.statusBarsPadding())

            // 页面区：用 AnimatedContent 做左右滑动过渡。
            // 前进（step 变大）＝新页从右滑入、旧页向左滑出；后退则相反。
            // 判断方向直接用 targetState 与 initialState 比较，不需要额外记方向变量。
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { it } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it } + fadeOut())
                        } else {
                            (slideInHorizontally { -it } + fadeIn()) togetherWith
                                (slideOutHorizontally { it } + fadeOut())
                        }
                    },
                    label = "onboardingStep"
                ) { s ->
                    when (s) {
                        0 -> BrandStep()
                        1 -> PermissionStep(perms, onPermClick, primary, appColors)
                        2 -> CloudStep(primary, appColors)
                        3 -> PolicyStep(appColors, primary, onAgreed, onExitApp)
                        else -> PolicyStep(appColors, primary, onAgreed, onExitApp)
                    }
                }
            }

            if (step < total - 1) {
                BottomBar(
                    step = step,
                    total = total,
                    primary = primary,
                    appColors = appColors,
                    onPrev = { prev() },
                    onNext = { next() }
                )
            }
        }
    }
}

/** 引导页用的权限条目（状态由 MainActivity 实时检查后传入）。 */
data class OnboardingPerm(
    val key: String,
    val title: String,
    val desc: String,
    val granted: Boolean,
    /** true = 需要跳系统设置页；false = 运行时权限弹窗 */
    val systemPage: Boolean = false
)

// ==================== 第 1 步：品牌 ====================

@Composable
private fun BrandStep() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(0.85f))
        // 品牌区唯一实现：BrandBlock（图标 + 项目名 + 英文标语 + 中文标语），
        // 与启动解锁页 AppUnlockScreen 共用同一份代码，改动只改 BrandBlock 一处即全端同步
        com.aurora.chat.ui.components.BrandBlock()
        Spacer(Modifier.weight(1.15f))
    }
}

// ==================== 第 2 步：权限 ====================

@Composable
private fun PermissionStep(
    items: List<OnboardingPerm>,
    onClick: (String) -> Unit,
    primary: Color,
    appColors: AppColors
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "按需授权，不授权也能用",
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(18.dp))

        items.forEach { item ->
            PermCard(item, primary, appColors) { onClick(item.key) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PermCard(
    item: OnboardingPerm,
    primary: Color,
    appColors: AppColors,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(appColors.cardBg)
            .clickable(enabled = !item.granted) { onClick() }
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (item.granted) primary.copy(alpha = 0.12f) else appColors.divider),
            contentAlignment = Alignment.Center
        ) {
            // 用矢量图标而不是文字占位：文字当图标既不对齐也不成体系
            Icon(
                painter = painterResource(id = permIconRes(item.key)),
                contentDescription = null,
                tint = if (item.granted) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.desc,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        if (item.granted) {
            Text(
                text = "已开启",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = primary
            )
        } else {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(primary.copy(alpha = 0.10f))
                    .clickable { onClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (item.systemPage) "去设置" else "授权",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = primary
                )
            }
        }
    }
}

private fun permIconRes(key: String): Int = when (key) {
    "overlay" -> com.aurora.chat.R.drawable.ic_perm_overlay
    "location" -> com.aurora.chat.R.drawable.ic_perm_location
    "install" -> com.aurora.chat.R.drawable.ic_perm_install
    "notification" -> com.aurora.chat.R.drawable.ic_perm_notification
    "camera" -> com.aurora.chat.R.drawable.ic_perm_camera
    "microphone" -> com.aurora.chat.R.drawable.ic_perm_mic
    "storage" -> com.aurora.chat.R.drawable.ic_perm_storage
    else -> com.aurora.chat.R.drawable.ic_perm_storage
}

// ==================== 第 3 步：云端保存 ====================

@Composable
private fun CloudStep(primary: Color, appColors: AppColors) {
    StepScaffold(
        title = "聊天记录云端永久保存",
        subtitle = "换手机、清缓存、重装软件，登录后全部拉回。"
    ) {
        FeatureCard(
            primary = primary, appColors = appColors,
            headline = "服务端长期留存",
            // 依据：backend/database.go 的 cleanupOldMedia 注释明写「绝不删除任何消息记录」，
            // 只对超过 30 天的图片/视频物理文件做清理以释放服务器空间。
            detail = "消息记录保存在服务器，与账号绑定，不随卸载或换机消失。" +
                "超过 30 天的图片、视频会清理物理文件以控制存储，文字记录不在清理范围内。"
        )
        Spacer(Modifier.height(10.dp))
        FeatureCard(
            primary = primary, appColors = appColors,
            headline = "换机后全量拉取",
            // 依据：friend_handlers.go 的 getMessages(limit/offset/after_id) 分页漫游
            detail = "新设备登录同一账号，会话与历史自动同步；往上翻到顶会继续加载更早的记录，不需要手动导出导入。"
        )
        Spacer(Modifier.height(10.dp))
        FeatureCard(
            primary = primary, appColors = appColors,
            headline = "本地同时留有缓存",
            // 依据：DataCache / ServerCache 本地缓存 + saveLocalChatMessages
            detail = "常用会话在本机留有缓存，断网时仍可查看。清理缓存只影响本地副本，云端记录不受影响。"
        )
    }
}

// ==================== 第 4 步：隐私政策与服务协议 ====================

@Composable
private fun PolicyStep(
    appColors: AppColors,
    primary: Color,
    onAgreed: () -> Unit,
    onExitApp: () -> Unit
) {
    val scroll = rememberScrollState()
    // 必须滑到底才能同意：仅靠倒计时会出现"随便看两眼就点同意"。
    // 判定用 value >= maxValue - 8 而不是 canScrollForward：前者在"内容不足一屏"时
    // （maxValue==0）同样成立，不会出现大屏设备上永远无法同意的死锁；首次布局前的那一帧
    // 也会被判为已到底，但还有 POLICY_MIN_READ_SECONDS 的倒计时兜着。
    val reachedBottom by remember {
        derivedStateOf { scroll.value >= scroll.maxValue - 8 }
    }
    var secondsLeft by remember { mutableIntStateOf(POLICY_MIN_READ_SECONDS) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1000)
            secondsLeft--
        }
    }
    val canAgree = reachedBottom && secondsLeft <= 0

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "隐私政策与服务协议",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    // 右侧留出快速滚动条宽度，避免正文被滑块压住
                    .padding(start = 20.dp, end = 30.dp)
            ) {
                PolicyBody(appColors, primary)
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "—— 以上为全部条款 ——",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
            }
            // 右侧快速滚动条：自带消费并直接驱动 scroll，不依赖正文 verticalScroll 与系统手势抢事件，
            // 平板/手表上也能稳定滑动；内容超出一屏（maxValue>0）时才显示。中心区照常可滑。
            if (scroll.maxValue > 0) {
                PolicyFastScroll(
                    scroll = scroll,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                text = when {
                    !reachedBottom -> "请滑动阅读至底部"
                    secondsLeft > 0 -> "请继续阅读 $secondsLeft 秒"
                    else -> "你可以选择同意，或拒绝并退出"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedPillButton(
                    text = "拒绝并退出",
                    modifier = Modifier.weight(1f),
                    enabled = true
                ) { onExitApp() }
                Spacer(Modifier.width(10.dp))
                FilledPillButton(
                    text = "同意并继续",
                    modifier = Modifier.weight(1.2f),
                    enabled = canAgree
                ) { onAgreed() }
            }
        }
    }
}

// ==================== 隐私页：右侧快速滚动条 ====================

/**
 * 隐私政策页右侧快速滚动条（触碰侧边拖动/点按即可快速滚动）。
 *
 * 为什么单独做一条、而不是只靠正文的 verticalScroll：
 * 引导页最外层有一个「Final 阶段全量消费」的 pointerInput 用来拦事件穿透（见 OnboardingScreen），
 * 它对滚动这类连续手势极其敏感——平板/手表触控采样与手势尺度不同，更容易出现「中间滑不动」。
 * 这条滚动条**自带消费并直接调用 [ScrollState.scrollTo]**，是一条独立于正文滚动的 guaranteed 通道，
 * 不跟系统返回手势/外层消费者抢事件，从而保证任何设备都绝对能滑得动。
 *
 * 交互：手指在右侧 28dp 竖条内按下/拖动/点按，触点 Y 直接映射为滚动位置（跟手）；滑块跟随滚动比例。
 * 仅在内容超出一屏（maxValue>0）时由调用方显示；中心正文区照常可滑，二者互不干扰。
 */
@Composable
private fun PolicyFastScroll(
    scroll: ScrollState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fraction by remember {
        derivedStateOf { if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f }
    }
    BoxWithConstraints(modifier = modifier.width(28.dp).fillMaxHeight()) {
        val trackHPx = with(density) { maxHeight.toPx() }
        val thumbHPx = with(density) { 40.dp.toPx() }
        val usablePx = (trackHPx - thumbHPx).coerceAtLeast(0f)

        // 轨道
        Box(
            Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(trackColor.copy(alpha = 0.18f))
        )
        // 滑块：跟随滚动比例，钳在轨道内
        val thumbTopPx = (fraction * usablePx).coerceIn(0f, usablePx)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbTopPx.toInt()) }
                .width(18.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(trackColor.copy(alpha = 0.42f))
        )
        // 手势层：覆盖整条竖条；触点 Y 直接映射滚动位置，并自行消费，绝不被外层吃掉
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(trackHPx) {
                    if (trackHPx <= 0f) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        fun scrollToY(y: Float) {
                            val f = (y / trackHPx).coerceIn(0f, 1f)
                            val target = (f * scroll.maxValue).toInt()
                            scope.launch { scroll.scrollTo(target) }
                        }
                        scrollToY(down.position.y)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            scrollToY(change.position.y)
                        }
                    }
                }
        )
    }
}

// ==================== 通用组件 ====================

@Composable
private fun StepScaffold(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FeatureCard(
    primary: Color,
    appColors: AppColors,
    headline: String,
    detail: String
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(appColors.cardBg)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = headline,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = detail,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单个页码圆点：当前页放大并染成主色，切换时大小与颜色都有弹簧过渡动画。
 */
@Composable
private fun StepDot(active: Boolean, primary: Color, appColors: AppColors) {
    val dotSize by animateDpAsState(
        targetValue = if (active) 9.dp else 5.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dotSize"
    )
    val dotColor by animateColorAsState(
        targetValue = if (active) primary else appColors.divider,
        label = "dotColor"
    )
    Box(
        Modifier
            .padding(end = 5.dp)
            .size(dotSize)
            .clip(CircleShape)
            .background(dotColor)
    )
}

/**
 * 底部栏：左侧「上一步」胶囊 + 居中页码圆点（当前页为放大的实心点）+ 右侧「下一步」胶囊。
 *
 * 指示器水平居中，不再贴在左下角；圆点不配 `1 / 6` 这类文字——页数对用户没有意义，
 * 位置信息由放大的圆点本身承载。
 */
@Composable
private fun BottomBar(
    step: Int,
    total: Int,
    primary: Color,
    appColors: AppColors,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        // 指示器：水平居中。上方一行小字官网（蓝色、可点击跳转浏览器），下方是页码圆点（当前页放大并染成主色）。
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val context = LocalContext.current
            Text(
                text = "example.com",
                fontSize = 11.sp,
                color = Color(0xFF1E40AF),
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                        )
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until total) {
                    StepDot(active = i == step, primary = primary, appColors = appColors)
                }
            }
        }
        if (step > 0) {
            OutlinedPillButton(
                text = "上一步",
                enabled = true,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(96.dp)
            ) { onPrev() }
        }
        FilledPillButton(
            text = "下一步",
            enabled = true,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(96.dp)
        ) { onNext() }
    }
}

@Composable
private fun FilledPillButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OutlinedPillButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
