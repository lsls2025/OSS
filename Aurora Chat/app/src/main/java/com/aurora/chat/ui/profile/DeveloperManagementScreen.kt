package com.aurora.chat.ui.profile
import com.aurora.chat.ui.theme.LocalAppColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.aurora.chat.R
import com.aurora.chat.data.api.AuroraApi
import com.aurora.chat.data.api.ApiResult
import com.aurora.chat.data.api.UserInfo
import com.aurora.chat.data.repository.ChatRepository
import com.aurora.chat.ui.components.UserAvatar
import com.aurora.chat.ui.tools.BanManagementScreen
import com.aurora.chat.ui.tools.MuteManagementScreen
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
private const val PAGE_SIZE = 10
private enum class DevTab(val label: String) {
    REGISTER("用户"),
    ANNOUNCE("公告"),
    CARD("卡密"),
    CLEAN("清理"),
    GROUP("群聊"),
    KICK("踢出"),
    TRIAL("体验"),
    ADMIN("管理"),
    INFO("信息"),
    DEFENSE("底防"),
    SHORTVIDEO("短视频"),
    BALANCE("余额"),
    CONFIG("配置"),
    NOTIFY("通知"),
    CARD_KEY2("站点卡密"),
    API("免费提供API"),
    PAID_API("付费API审核"),
    PLUGIN_REVIEW("插件审核"),
    APPS("应用"),
    AGENT_APP("Agent更新")
}
private enum class TimeSort(val label: String) {
    NEWEST_FIRST("从近到远"),
    OLDEST_FIRST("从远到近")
}
private enum class IdSort(val label: String) {
    DESCENDING("从大到小"),
    ASCENDING("从小到大")
}
private enum class SortType { TIME, ID, NONE }
/** 每个 Tab 需要的细粒度权限键(后端 checkDevOrPerm 同源);空列表=后端未挂权限键,仅开发者可用 */
private fun devTabRequiredKeys(tab: DevTab): List<String> = when (tab) {
    DevTab.REGISTER -> listOf("users.view_profile", "users.delete", "users.location")
    DevTab.ANNOUNCE -> listOf("announcement.view", "announcement.edit")
    DevTab.CARD, DevTab.CARD_KEY2 -> listOf("card_keys.view", "card_keys.generate", "card_keys.delete", "card_keys.cancel")
    DevTab.GROUP -> listOf("groups.view", "groups.edit", "groups.msg")
    DevTab.KICK -> listOf("kick.single", "kick.all")
    DevTab.TRIAL -> listOf("trial.view", "trial.toggle")
    DevTab.ADMIN -> listOf("admin.logs", "admin.grant")
    DevTab.INFO -> listOf("admin.stats")
    // 短视频总开关：不下放任何细粒度权限，仅开发者本人可操作
    DevTab.SHORTVIDEO -> emptyList()
    DevTab.BALANCE -> listOf("balance.manage")
    DevTab.NOTIFY -> listOf("notify.send")
    DevTab.API -> listOf("free_apis.manage")
    DevTab.PAID_API -> listOf("paid_apis.manage")
    DevTab.PLUGIN_REVIEW -> listOf("plugin.review")
    DevTab.APPS -> listOf("open_apps.manage")
    DevTab.AGENT_APP -> listOf("open_apps.manage")
    // 清理(CLEAN)/订单(ORDER)/底防(DEFENSE):仅开发者可见
    else -> emptyList()
}
/** Tab 是否可见:开发者全可见;其他人只显示拥有任一对应权限键的 Tab——没给权限就不渲染,而不是点了才提示无权访问 */
private fun devTabVisible(tab: DevTab, isDeveloper: Boolean, perms: List<String>): Boolean =
    isDeveloper || devTabRequiredKeys(tab).any { perms.contains(it) }
@Composable
fun DeveloperManagementScreen(
    onBack: () -> Unit,
    isDeveloper: Boolean = false,
    /** 入口是否可见（开发者 / 平台管理员 / 任一细粒度权限）；为 false 时明确提示无权访问，而不是白屏 */
    canAccess: Boolean = isDeveloper,
    initialTab: String? = null
) {
    var users by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var onlineUsers by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingOnline by remember { mutableStateOf(true) }
    var onlineError by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // null = 入口列表；非 null = 已切入某个 Tab 的全屏内容页
    var openTab by remember { mutableStateOf<DevTab?>(DevTab.entries.firstOrNull { it.label == initialTab }) }
    // 退出动画期间 openTab 已变 null，用最后一次的 Tab 让内容继续渲染到动画结束
    var lastOpenTab by remember { mutableStateOf<DevTab?>(null) }
    LaunchedEffect(openTab) { if (openTab != null) lastOpenTab = openTab }
    var selectedUser by remember { mutableStateOf<UserInfo?>(null) }
    var isPlatformAdminView by remember { mutableStateOf(false) }
    var myPermissions by remember { mutableStateOf<List<String>>(emptyList()) }
    // 检查当前用户是否是平台管理员（限制视图）
    LaunchedEffect(Unit) {
        try {
            val result = com.aurora.chat.data.api.AuroraApi.isPlatformAdmin()
            if (result.success && result.data == true) {
                isPlatformAdminView = true
            }
        } catch (_: Exception) { }
        // 非开发者一律拉取细粒度权限:Tab 可见性按权限过滤(没给权限的 Tab 直接不显示)
        if (!isDeveloper) {
            try {
                val permResult = com.aurora.chat.data.api.AuroraApi.getMyPermissions()
                if (permResult.success) {
                    myPermissions = permResult.data ?: emptyList()
                }
            } catch (_: Exception) { }
            // 直达的 Tab 若当前账号无权访问，退回入口列表（列表本身已按权限过滤）
            val visible = DevTab.entries.filter { devTabVisible(it, isDeveloper, myPermissions) }
            val target = openTab
            if (target != null && visible.none { it == target }) openTab = null
        }
    }
    var showSearch by remember { mutableStateOf(false) }
    var showCreateUser by remember { mutableStateOf(false) }
    var timeSort by remember { mutableStateOf(TimeSort.NEWEST_FIRST) }
    var idSort by remember { mutableStateOf(IdSort.DESCENDING) }
    var activeSort by remember { mutableStateOf(SortType.NONE) }
    // 返回顺序：创建页 → 搜索弹窗 → 内容页 → 入口列表；后者返回交给外层 onBack
    BackHandler(enabled = showCreateUser) { showCreateUser = false }
    BackHandler(enabled = openTab != null && !showSearch && !showCreateUser) { openTab = null }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    fun loadUsers() {
        scope.launch {
            isLoading = true
            errorMsg = null
            val result = ChatRepository.getAllUsers()
            if (result.success) {
                users = result.data ?: emptyList()
            } else {
                errorMsg = result.message
            }
            isLoading = false
        }
    }
    fun loadOnlineUsers() {
        scope.launch {
            isLoadingOnline = true
            onlineError = false
            val result = ChatRepository.getOnlineUsers()
            if (result.success) {
                onlineUsers = result.data ?: emptyList()
            } else {
                onlineError = true
            }
            isLoadingOnline = false
        }
    }
    LaunchedEffect(Unit) { loadUsers() }
    // 入口可见但确实没有任何可用 Tab（既非开发者、也没有任何细粒度权限）时明确提示，不留白屏
    if (!canAccess) {
        Box(
            modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("无权访问", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(modifier = Modifier.height(8.dp))
                Text("当前账号没有开发者管理权限", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "返回",
                    fontSize = 14.sp,
                    color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable { onBack() }
                )
            }
        }
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(com.aurora.chat.ui.theme.appCardBg())
        ) {
            // 标题栏：白色，自身延伸到状态栏底下（与小游戏页一致）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = com.aurora.chat.ui.theme.appTextPrimary()
                        )
                    }
                    Text(
                        text = "开发者管理",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = com.aurora.chat.ui.theme.appTextPrimary()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 胶囊板块：灰底上垫一张白底圆角卡片，行与行之间用 0.5dp 分割线隔开
            val visibleTabs = DevTab.entries.filter { devTabVisible(it, isDeveloper, myPermissions) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(com.aurora.chat.ui.theme.appSurface())
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    visibleTabs.forEachIndexed { index, tab ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openTab = tab }
                                .height(52.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tab.label,
                                fontSize = 15.sp,
                                color = com.aurora.chat.ui.theme.appTextPrimary(),
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFFC7CBD1),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (index != visibleTabs.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .height(0.5.dp)
                                    .background(com.aurora.chat.ui.theme.appCardBg())
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ==================== 具体 Tab 内容：全屏从右侧滑入，顶部标题 + 下方完整内容 ====================
        AnimatedVisibility(
            visible = openTab != null,
            enter = slideInHorizontally(tween(260)) { it },
            exit = slideOutHorizontally(tween(220)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            val tab = openTab ?: lastOpenTab
            if (tab != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(com.aurora.chat.ui.theme.appSurface())
                        // 全屏覆盖层必须吞掉空白区域的点击，否则会穿透到下层入口列表
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {}
                ) {
                    // 标题栏：与小游戏子页一致，白色延伸到状态栏下
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(com.aurora.chat.ui.theme.appSurface())
                            .statusBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp).clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { openTab = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    tint = com.aurora.chat.ui.theme.appTextPrimary()
                                )
                            }
                            Text(
                                text = tab.label,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = com.aurora.chat.ui.theme.appTextPrimary()
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // 「用户」Tab：标题右侧加「+」，点开全屏新建用户（培养开发者自建账号）
                            if (tab == DevTab.REGISTER) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { showCreateUser = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "新建用户",
                                        tint = Color(0xFF1E40AF)
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = com.aurora.chat.ui.theme.appDivider())
                    // 内容区 —— 必须用 weight(1f) 占满剩余高度，否则子 Composable 的 fillMaxSize 无效
                    Box(modifier = Modifier.weight(1f, fill = true)) {
                        when (tab) {
                            DevTab.REGISTER -> RegisterUsersContent(
                                users = users,
                                isLoading = isLoading,
                                errorMsg = errorMsg,
                                onRetry = { loadUsers() },
                                onViewUser = { selectedUser = it },
                                onSearchClick = { showSearch = true },
                                timeSort = timeSort,
                                idSort = idSort,
                                onTimeSortChange = { timeSort = it; activeSort = SortType.TIME },
                                onIdSortChange = { idSort = it; activeSort = SortType.ID },
                                activeSort = activeSort
                            )
                            DevTab.ANNOUNCE -> AnnouncementManagementContent()
                            DevTab.CARD -> CardKeyListContent(category = "reward", title = "奖励卡密列表", isReward = true)
                            DevTab.CLEAN -> CleanConversationContent()
                            DevTab.GROUP -> GroupManagementContent()
                            DevTab.KICK -> KickManagementContent()
                            DevTab.TRIAL -> TrialManagementContent()
                            DevTab.ADMIN -> AdminLogContent()
                            DevTab.INFO -> InfoContent()
                            DevTab.DEFENSE -> DefenseTab()
                            DevTab.SHORTVIDEO -> ShortVideoGateContent()
                            DevTab.BALANCE -> BalanceManagementContent()
                            DevTab.CONFIG -> FdConfigManagementContent()
                            DevTab.NOTIFY -> NotificationManagementContent()
                            DevTab.CARD_KEY2 -> CardKeyManageContent()
                            DevTab.API -> FreeApiManageContent()
                            DevTab.PAID_API -> PaidApiManageContent()
                            DevTab.PLUGIN_REVIEW -> PluginReviewContent()
                            DevTab.APPS -> OpenAppManageContent()
                            DevTab.AGENT_APP -> AgentAppVersionScreen(onBack = {}, showBack = false)
                        }
                    }
                }
            }
        }
        // 用户详情侧滑页面
        AnimatedVisibility(
            visible = selectedUser != null,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            selectedUser?.let { user ->
                UserDetailPage(
                    user = user,
                    onBack = { selectedUser = null },
                    onUserDeleted = { loadUsers() },
                    isPlatformAdmin = isPlatformAdminView,
                    onUserIdChanged = { oldId, newId ->
                        // 同步到当前选中的用户与列表，避免返回后重新进入又显示旧 ID
                        selectedUser = selectedUser?.copy(id = newId)
                        users = users.map { if (it.id == oldId) it.copy(id = newId) else it }
                    }
                )
            }
        }
        // 开发者新建用户：全屏从右侧滑入（培养开发者自建账号）
        AnimatedVisibility(
            visible = showCreateUser,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            CreateUserScreen(
                onBack = { showCreateUser = false },
                onCreated = {
                    showCreateUser = false
                    loadUsers()
                }
            )
        }
        // 搜索弹窗：遮罩与抽屉各自做动画（遮罩淡入、抽屉从底部滑入），
        // 不再让整块一起位移——否则遮罩会跟着抽屉"被拖上来"，像一块移动的阴影。
        if (showSearch) {
            SearchDialog(
                onDismiss = { showSearch = false },
                onViewUser = { selectedUser = it; showSearch = false }
            )
        }
    }
}
// ==================== 搜索弹窗 ====================
@Composable
private fun SearchDialog(
    onDismiss: () -> Unit,
    onViewUser: (UserInfo) -> Unit
) {
    val ctx = LocalContext.current
    // 上一次的搜索词自动保存（按账号隔离）：下次打开直接回填，不用重打
    val prefs = remember { ctx.getSharedPreferences("dev_user_search", Context.MODE_PRIVATE) }
    val prefsKey = remember { "kw_${AuroraApi.currentUserId}" }
    var keyword by remember { mutableStateOf(prefs.getString(prefsKey, "") ?: "") }
    // 防抖后的查询词：输入框即时响应，真正搜索等停止输入 280ms
    var query by remember { mutableStateOf(keyword) }
    var results by remember { mutableStateOf<List<UserInfo>>(emptyList()) }
    var exitingResults by remember { mutableStateOf<List<UserInfo>?>(null) }
    var clearedFlags by remember { mutableStateOf(setOf<Int>()) }
    var isTransitioning by remember { mutableStateOf(false) }
    var searchId by remember { mutableStateOf(0) }
    var idMatchIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    // 遮罩独立淡入、抽屉从底部滑入：不再让整块一起位移，避免遮罩像被抽屉拖上来
    var closing by remember { mutableStateOf(false) }
    fun requestClose() { if (!closing) closing = true }
    BackHandler(enabled = true) { requestClose() }
    LaunchedEffect(closing) {
        if (closing) {
            delay(300)
            onDismiss()
        }
    }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (entered && !closing) 0.4f else 0f,
        animationSpec = tween(200), label = "searchScrim"
    )
    val sheetHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f
    val sheetOffset by animateDpAsState(
        targetValue = if (entered && !closing) 0.dp else sheetHeight,
        animationSpec = tween(280), label = "searchSheet"
    )
    val scope = rememberCoroutineScope()
    // 防抖 + 自动保存：输入即落盘（避免打完马上关闭导致丢失），真正查询等停止输入 250ms
    LaunchedEffect(keyword) {
        runCatching { prefs.edit().putString(prefsKey, keyword).apply() }
        if (keyword.isNotBlank()) delay(250)
        query = keyword
    }
    LaunchedEffect(query, retryTick) {
        if (query.isBlank()) {
            results = emptyList()
            exitingResults = null
            idMatchIds = emptySet()
            searching = false
            searchError = false
            return@LaunchedEffect
        }
        searching = true
        searchError = false
        // 服务端过滤（ID / QQ / 用户名 / 邮箱）并限制条数：客户端不再遍历全量用户
        val resp = ChatRepository.searchAdminUsers(query)
        searching = false
        if (!resp.success) {
            searchError = true
            return@LaunchedEffect
        }
        val ranked = resp.data ?: emptyList()
        val idIds = ranked.filter {
            val s = it.id.toString()
            s == query || s.startsWith(query)
        }.map { it.id }.toSet()
        if (!isTransitioning && results.isNotEmpty()) {
            val old = results
            exitingResults = old
            clearedFlags = emptySet()
            isTransitioning = true
            searchId++
            scope.launch {
                old.indices.forEach { i ->
                    launch {
                        delay(i.coerceAtMost(8) * 60L + 320L)
                        clearedFlags = clearedFlags + i
                    }
                }
                delay(old.size.coerceAtMost(9) * 60L + 420L)
                exitingResults = null
                isTransitioning = false
            }
        }
        results = ranked
        idMatchIds = idIds
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrimAlpha)))
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.3f)
                    .align(Alignment.TopCenter)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { requestClose() }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .align(Alignment.BottomCenter)
                    .offset(y = sheetOffset)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(com.aurora.chat.ui.theme.appSurface())
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(com.aurora.chat.ui.theme.appCardBg())
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            BasicTextField(
                                value = keyword,
                                onValueChange = { keyword = it.take(64) },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(Color(0xFF1E40AF)),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (keyword.isEmpty()) {
                                            Text("输入QQ号或用户ID搜索", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "取消",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable { requestClose() }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(com.aurora.chat.ui.theme.appDivider())
                    )
                    if (query.isBlank()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("输入QQ号或用户ID搜索", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        }
                    } else if (searchError && results.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("搜索失败", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                Text(
                                    text = "重试",
                                    fontSize = 14.sp,
                                    color = Color(0xFF1E40AF),
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .clickable { retryTick++ }
                                )
                            }
                        }
                    } else if (searching && results.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF1E40AF)
                            )
                        }
                    } else if (results.isEmpty() && exitingResults == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("无匹配结果", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        }
                    } else {
                        val maxCount = maxOf(exitingResults?.size ?: 0, results.size)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            for (i in 0 until maxCount) {
                                val isOld = exitingResults != null && i < exitingResults!!.size && i !in clearedFlags
                                val isNew = i < results.size && (i in clearedFlags || exitingResults == null)
                                if (isOld) {
                                    val exitKey = "exit-${exitingResults!![i].id}-$searchId-$i"
                                    var startExit by remember(exitKey) { mutableStateOf(false) }
                                    LaunchedEffect(exitKey) {
                                        delay(i * 80L + 50L)
                                        startExit = true
                                    }
                                    val offX by animateFloatAsState(
                                        targetValue = if (startExit) -800f else 0f,
                                        animationSpec = tween(300)
                                    )
                                    val itemA by animateFloatAsState(
                                        targetValue = if (startExit) 0f else 1f,
                                        animationSpec = tween(300)
                                    )
                                    SearchResultRow(
                                        user = exitingResults!![i],
                                        modifier = Modifier
                                            .graphicsLayer { translationX = offX; this.alpha = itemA },
                                        onView = { onViewUser(exitingResults!![i]) }
                                    )
                                }
                                if (isNew) {
                                    val enterKey = "enter-${results[i].id}-$searchId-$i"
                                    var show by remember(enterKey) { mutableStateOf(false) }
                                    LaunchedEffect(enterKey) {
                                        val delayMs = if (exitingResults != null) {
                                            i * 80L + 320L
                                        } else {
                                            i * 80L
                                        }
                                        delay(delayMs)
                                        show = true
                                    }
                                    val offX by animateFloatAsState(
                                        targetValue = if (show) 0f else 600f,
                                        animationSpec = tween(280)
                                    )
                                    val itemA by animateFloatAsState(
                                        targetValue = if (show) 1f else 0f,
                                        animationSpec = tween(250)
                                    )
                                    SearchResultRow(
                                        user = results[i],
                                        isIdMatch = idMatchIds.contains(results[i].id),
                                        modifier = Modifier
                                            .graphicsLayer { translationX = offX; this.alpha = itemA },
                                        onView = { onViewUser(results[i]) }
                                    )
                                }
                                if (isNew && isOld) {
                                    Spacer(Modifier.height(2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SearchResultRow(user: UserInfo, modifier: Modifier = Modifier, isIdMatch: Boolean = false, onView: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val displayQQ = displayUserQQ(user.qqNumber)
            Text(
                text = displayQQ,
                fontSize = 14.sp,
                fontWeight = if (displayQQ.startsWith("未填写")) FontWeight.Normal else FontWeight.Medium,
                color = if (displayQQ.startsWith("未填写")) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${user.username}  ID:${user.id}",
                    fontSize = 12.sp,
                    color = com.aurora.chat.ui.theme.appTextHint()
                )
                if (isIdMatch) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFDCFCE7))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "ID吻合",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF16A34A)
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E40AF))
                .clickable { onView() }
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "查看",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}
// ==================== 注册用户列表 ====================
@Composable
private fun RegisterUsersContent(
    users: List<UserInfo>,
    isLoading: Boolean,
    errorMsg: String?,
    onRetry: () -> Unit,
    onViewUser: (UserInfo) -> Unit,
    onSearchClick: () -> Unit,
    timeSort: TimeSort = TimeSort.NEWEST_FIRST,
    idSort: IdSort = IdSort.DESCENDING,
    onTimeSortChange: (TimeSort) -> Unit = {},
    onIdSortChange: (IdSort) -> Unit = {},
    activeSort: SortType = SortType.NONE
) {
    val listState = rememberLazyListState()
    var displayCount by remember { mutableStateOf(PAGE_SIZE) }
    val sortedUsers = remember(users, timeSort, idSort, activeSort) {
        when (activeSort) {
            SortType.ID -> users.sortedWith(compareByDescending<UserInfo> {
                when (idSort) {
                    IdSort.DESCENDING -> it.id
                    IdSort.ASCENDING -> -it.id
                }
            })
            else -> users.sortedWith(compareByDescending<UserInfo> {
                when (timeSort) {
                    TimeSort.NEWEST_FIRST -> it.lastActiveAt
                    TimeSort.OLDEST_FIRST -> -it.lastActiveAt
                }
            })
        }
    }
    LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= displayCount - 3 && displayCount < sortedUsers.size) {
            displayCount = (displayCount + PAGE_SIZE).coerceAtMost(sortedUsers.size)
        }
    }
    LaunchedEffect(users) {
        if (sortedUsers.size <= PAGE_SIZE) {
            displayCount = sortedUsers.size.coerceAtLeast(0)
        } else if (displayCount > sortedUsers.size) {
            displayCount = sortedUsers.size
        }
    }
    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(color = Color(0xFF1E40AF))
            }
        }
        errorMsg != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "加载失败", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMsg, fontSize = 13.sp, color = Color(0xFFE74C3C))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "点击重试",
                        fontSize = 14.sp,
                        color = Color(0xFF1E40AF),
                        modifier = Modifier.clickable { onRetry() }
                    )
                }
            }
        }
        users.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "暂无注册用户", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
        }
        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())
            ) {
                item {
                    StatsHeader(
                        text = "注册总人数: ${sortedUsers.size} 人",
                        onSearchClick = onSearchClick,
                        timeSort = timeSort,
                        idSort = idSort,
                        onTimeSortChange = onTimeSortChange,
                        onIdSortChange = onIdSortChange,
                        activeSort = activeSort
                    )
                }
                val visibleUsers = sortedUsers.take(displayCount)
                items(visibleUsers, key = { it.id }) { user ->
                    UserRegisterItem(user = user, onView = { onViewUser(user) })
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = com.aurora.chat.ui.theme.appDivider()
                    )
                }
                if (displayCount < sortedUsers.size) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "加载更多...",
                                fontSize = 12.sp,
                                color = com.aurora.chat.ui.theme.appTextHint()
                            )
                        }
                    }
                }
            }
        }
    }
}
// ==================== 开发者新建用户（全屏） ====================
// 培养开发者在后台自建账号：可填头像（选填）、用户名（选填）、QQ号、密码。
// 顶部只保留返回箭头 + 标题 + 创建，不做任何多余提示。
@Composable
private fun CreateUserScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var qqNumber by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCreating by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(it)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                avatarBitmap = bmp
            }
        }
    }

    val canCreate = password.length >= 8 && !isCreating

    Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        // 顶部栏：仅返回箭头 + 标题 + 创建
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(44.dp).clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = com.aurora.chat.ui.theme.appTextPrimary())
            }
            Text(
                text = "新建用户",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (isCreating) "创建中" else "创建",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (canCreate) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextHint(),
                modifier = Modifier.clickable(enabled = canCreate) {
                    isCreating = true
                    scope.launch {
                        // 头像（选填）：压缩到 320px 以内再转 base64，控制体积
                        val avatarB64 = avatarBitmap?.let { bmp ->
                            withContext(Dispatchers.IO) {
                                val scaled = scaleBitmap(bmp, 320)
                                val bytes = java.io.ByteArrayOutputStream().use { out ->
                                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    out.toByteArray()
                                }
                                Base64.encodeToString(bytes, Base64.NO_WRAP)
                            }
                        }
                        val result = ChatRepository.adminCreateUser(
                            username = username.trim(),
                            qqNumber = qqNumber.trim(),
                            password = password,
                            avatarB64 = avatarB64
                        )
                        isCreating = false
                        if (result.success) {
                            onCreated()
                        } else {
                            Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        HorizontalDivider(thickness = 0.5.dp, color = com.aurora.chat.ui.theme.appDivider())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            // 头像（选填）：点击选图，空态用 + 表示
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(14.dp))
                    .background(com.aurora.chat.ui.theme.appDivider())
                    .clickable { imagePicker.launch("image/*") },
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
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "选择头像",
                        tint = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            CreateUserField(placeholder = "用户名", value = username, onValueChange = { username = it })
            Spacer(modifier = Modifier.height(16.dp))
            CreateUserField(placeholder = "QQ号", value = qqNumber, onValueChange = { qqNumber = it }, digitsOnly = true)
            Spacer(modifier = Modifier.height(16.dp))
            CreateUserField(placeholder = "密码", value = password, onValueChange = { password = it }, isPassword = true)
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CreateUserField(
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    digitsOnly: Boolean = false
) {
    BasicTextField(
        value = value,
        onValueChange = {
            val v = if (digitsOnly) it.filter { c -> c.isDigit() } else it
            onValueChange(v)
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg())
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
                innerTextField()
            }
        }
    )
}

/** 等比缩放到最长边不超过 maxSize（仅缩小，不放大），用于控制头像上传体积 */
private fun scaleBitmap(src: Bitmap, maxSize: Int): Bitmap {
    val w = src.width
    val h = src.height
    val scale = (maxSize.toFloat() / maxOf(w, h)).coerceAtMost(1f)
    if (scale >= 1f) return src
    return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
}

// ==================== 在线用户列表 ====================
@Composable
private fun OnlineUsersContent(
    users: List<UserInfo>,
    isLoading: Boolean,
    hasError: Boolean,
    onViewUser: (UserInfo) -> Unit,
    onSearchClick: () -> Unit
) {
    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(color = Color(0xFF1E40AF))
            }
        }
        hasError -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "获取在线用户失败", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "15秒后自动重试", fontSize = 12.sp, color = Color(0xFFD1D5DB))
                }
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())
            ) {
                item {
                    StatsHeader(
                        text = "当前在线: ${users.size} 人",
                        onSearchClick = onSearchClick
                    )
                }
                if (users.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "暂无在线用户", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "列表每15秒自动刷新", fontSize = 12.sp, color = Color(0xFFD1D5DB))
                            }
                        }
                    }
                } else {
                    items(users, key = { it.id }) { user ->
                        UserOnlineItem(user = user, onView = { onViewUser(user) })
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = com.aurora.chat.ui.theme.appDivider()
                        )
                    }
                }
            }
        }
    }
}
// ==================== 统计栏 ====================
@Composable
private fun StatsHeader(
    text: String,
    onSearchClick: () -> Unit,
    timeSort: TimeSort = TimeSort.NEWEST_FIRST,
    idSort: IdSort = IdSort.DESCENDING,
    onTimeSortChange: (TimeSort) -> Unit = {},
    onIdSortChange: (IdSort) -> Unit = {},
    activeSort: SortType = SortType.NONE
) {
    var showTimeMenu by remember { mutableStateOf(false) }
    var showIdMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface())) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1E40AF),
                modifier = Modifier.weight(1f)
            )
            Box {
                Text(
                    text = "时间",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF4B5563),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .clickable { showTimeMenu = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                DropdownMenu(
                    expanded = showTimeMenu,
                    onDismissRequest = { showTimeMenu = false }
                ) {
                    Text(
                        text = "从近到远",
                        fontSize = 14.sp,
                        color = if (activeSort == SortType.TIME && timeSort == TimeSort.NEWEST_FIRST) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextSecondary(),
                        fontWeight = if (activeSort == SortType.TIME && timeSort == TimeSort.NEWEST_FIRST) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { onTimeSortChange(TimeSort.NEWEST_FIRST); showTimeMenu = false }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .fillMaxWidth()
                    )
                    Text(
                        text = "从远到近",
                        fontSize = 14.sp,
                        color = if (activeSort == SortType.TIME && timeSort == TimeSort.OLDEST_FIRST) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextSecondary(),
                        fontWeight = if (activeSort == SortType.TIME && timeSort == TimeSort.OLDEST_FIRST) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { onTimeSortChange(TimeSort.OLDEST_FIRST); showTimeMenu = false }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Box {
                Text(
                    text = "ID",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF4B5563),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .clickable { showIdMenu = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                DropdownMenu(
                    expanded = showIdMenu,
                    onDismissRequest = { showIdMenu = false }
                ) {
                    Text(
                        text = "从大到小",
                        fontSize = 14.sp,
                        color = if (activeSort == SortType.ID && idSort == IdSort.DESCENDING) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextSecondary(),
                        fontWeight = if (activeSort == SortType.ID && idSort == IdSort.DESCENDING) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { onIdSortChange(IdSort.DESCENDING); showIdMenu = false }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .fillMaxWidth()
                    )
                    Text(
                        text = "从小到大",
                        fontSize = 14.sp,
                        color = if (activeSort == SortType.ID && idSort == IdSort.ASCENDING) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appTextSecondary(),
                        fontWeight = if (activeSort == SortType.ID && idSort == IdSort.ASCENDING) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { onIdSortChange(IdSort.ASCENDING); showIdMenu = false }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E40AF))
                    .clickable { onSearchClick() }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "搜索",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .padding(horizontal = 16.dp)
                .background(com.aurora.chat.ui.theme.appDivider())
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}
// ==================== 注册用户卡片 ====================
@Composable
private fun UserRegisterItem(user: UserInfo, onView: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(com.aurora.chat.ui.theme.appSurface())
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        val displayQQ2 = displayUserQQ(user.qqNumber)
        Text(
            text = displayQQ2,
            fontSize = 14.sp,
            fontWeight = if (displayQQ2.startsWith("未填写")) FontWeight.Normal else FontWeight.Medium,
            color = if (displayQQ2.startsWith("未填写")) com.aurora.chat.ui.theme.appTextHint() else com.aurora.chat.ui.theme.appTextPrimary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = user.username, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "ID: ${user.id}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E40AF))
                    .clickable { onView() }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "查看", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "注册时间:${dateFormat.format(Date(user.createdAt * 1000))}",
            fontSize = 12.sp,
            color = com.aurora.chat.ui.theme.appTextHint()
        )
    }
}
// ==================== 在线用户卡片 ====================
@Composable
private fun UserOnlineItem(user: UserInfo, onView: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(com.aurora.chat.ui.theme.appSurface())
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = displayUserQQ(user.qqNumber),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = user.username, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "ID: ${user.id}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E40AF))
                    .clickable { onView() }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "查看", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "最近活动:${dateFormat.format(Date(user.lastActiveAt * 1000))}",
            fontSize = 12.sp,
            color = com.aurora.chat.ui.theme.appTextHint()
        )
    }
}
// ==================== 用户详情 ====================
@Composable
private fun UserDetailPage(
    user: UserInfo,
    onBack: () -> Unit,
    onUserDeleted: () -> Unit = {},
    isPlatformAdmin: Boolean = false,
    onUserIdChanged: (oldId: Long, newId: Long) -> Unit = { _, _ -> }
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    var avatarBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val ctx = LocalContext.current
    var displayUser by remember { mutableStateOf(user) }
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var blockEmail by remember { mutableStateOf(false) }
    var blockIp by remember { mutableStateOf(false) }
    var showBanScreen by remember { mutableStateOf(false) }
    var isBanned by remember { mutableStateOf<Boolean?>(null) }
    var isUnbanning by remember { mutableStateOf(false) }
    var banRefreshKey by remember { mutableStateOf(0) }
    var showMuteScreen by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf<Boolean?>(null) }
    var muteType by remember { mutableIntStateOf(0) } // 1=社区 2=对话 3=全部 4=短视频
    var isUnmuting by remember { mutableStateOf(false) }
    var muteRefreshKey by remember { mutableStateOf(0) }
    var showLocationScreen by remember { mutableStateOf(false) }
    var isUserAdmin by remember { mutableStateOf<Boolean?>(null) }
    var isGrantingAdmin by remember { mutableStateOf(false) }
    var showPermissionEditor by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var showEditID by remember { mutableStateOf(false) }
    var showEditName by remember { mutableStateOf(false) }
    var showEditQQ by remember { mutableStateOf(false) }
    var showEditReg by remember { mutableStateOf(false) }
    var showDeleteConvDialog by remember { mutableStateOf(false) }
    // 拦截返回手势/按键，先返回用户列表而不退出整个开发者管理
    BackHandler(enabled = true) { onBack() }
    // 权限编辑页打开时，返回键先关闭编辑页
    BackHandler(enabled = showPermissionEditor) { showPermissionEditor = false }
    // 检查该用户是否已是平台管理员
    LaunchedEffect(user.id) {
        try {
            val listResult = AuroraApi.listPlatformAdmins()
            if (listResult.success && listResult.data != null) {
                var found = false
                for (i in 0 until listResult.data.length()) {
                    if (listResult.data.getJSONObject(i).optLong("user_id") == user.id) {
                        found = true; break
                    }
                }
                isUserAdmin = found
            }
        } catch (_: Exception) { isUserAdmin = false }
    }
    LaunchedEffect(user.id, banRefreshKey) {
        avatarBitmap = ChatRepository.loadAvatar(ctx, user.id)
        kotlinx.coroutines.delay(300)
        try {
            val result = com.aurora.chat.data.api.AuroraApi.adminCheckBanStatus(user.id)
            isBanned = result.success && result.data != null && result.data.has("expires_at") && result.data.optLong("expires_at", 0L) > 0
        } catch (_: Exception) {
            isBanned = false
        }
    }
    LaunchedEffect(user.id, muteRefreshKey) {
        try {
            val result = com.aurora.chat.data.api.AuroraApi.adminCheckMuteStatus(user.id)
            isMuted = result.success && result.data != null && result.data.has("expires_at") && result.data.optLong("expires_at", 0L) > 0
            muteType = if (isMuted == true) result.data?.optInt("mute_type", 0) ?: 0 else 0
        } catch (_: Exception) {
            isMuted = false
            muteType = 0
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(com.aurora.chat.ui.theme.appSurface())
                .statusBarsPadding()
                .clickable { }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appSurface())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E40AF), modifier = Modifier.clickable { onBack() }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${displayUser.username}的详情", fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary()
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap!!.asImageBitmap(),
                        contentDescription = "头像",
                        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).clickable { },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(com.aurora.chat.ui.theme.appDivider()).clickable { },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayUser.username.take(1).uppercase(),
                            fontSize = 28.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextSecondary()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                DetailRow("用户 ID", "${displayUser.id}", onEdit = { showEditID = true })
                Spacer(modifier = Modifier.height(16.dp))
                DetailRow("用户名称", displayUser.username, onEdit = { showEditName = true })
                Spacer(modifier = Modifier.height(16.dp))
                DetailRow("QQ号", if (displayUser.qqNumber.isNotEmpty()) displayUser.qqNumber else "未绑定", onEdit = { showEditQQ = true }, copyValue = displayUser.qqNumber)
                Spacer(modifier = Modifier.height(16.dp))
                DetailRow(
                    "绑定邮箱",
                    if (displayUser.email.isBlank() || displayUser.email.contains("@local") || displayUser.email.startsWith("qq_") || displayUser.email.startsWith("temp_"))
                        "未绑定" else displayUser.email,
                    copyValue = if (displayUser.email.contains("@local") || displayUser.email.startsWith("qq_") || displayUser.email.startsWith("temp_")) "" else displayUser.email
                )
                Spacer(modifier = Modifier.height(16.dp))
                DetailRow("注册时间", dateFormat.format(Date(displayUser.createdAt * 1000)), onEdit = { showEditReg = true })
                Spacer(modifier = Modifier.height(16.dp))
                DetailRow("最近活动", if (displayUser.lastActiveAt > 0) dateFormat.format(Date(displayUser.lastActiveAt * 1000)) else "未知")
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { showDeleteConvDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("删除与该用户的会话", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                if (showDeleteConvDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConvDialog = false },
                        title = { Text("删除会话") },
                        text = { Text("将永久删除你与「${displayUser.username}」（ID ${displayUser.id}）之间的全部消息，且不可恢复。确定继续？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteConvDialog = false
                                scope.launch {
                                    try {
                                        val r = AuroraApi.deleteConversation(displayUser.id)
                                        val count = r.data ?: 0
                                        android.widget.Toast.makeText(ctx, "已删除 $count 条消息", android.widget.Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(ctx, "删除失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) { Text("确认删除", color = Color(0xFFDC2626)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConvDialog = false }) { Text("取消") }
                        }
                    )
                }
                // ── 编辑弹窗 ──
                if (showEditID) {
                    EditFieldDialog("修改用户 ID", "${displayUser.id}", "输入新的用户 ID（正整数）", onDismiss = { showEditID = false }) { newVal ->
                        showEditID = false
                        val nv = newVal.toLongOrNull()
                        if (nv == null || nv <= 0) {
                            android.widget.Toast.makeText(ctx, "ID 必须是正整数", android.widget.Toast.LENGTH_SHORT).show()
                            return@EditFieldDialog
                        }
                        scope.launch {
                            try {
                                AuroraApi.adminUpdateUser(displayUser.id, newId = nv)
                                withContext(Dispatchers.Main) {
                                    val oldId = displayUser.id
                                    displayUser = displayUser.copy(id = nv)
                                    onUserIdChanged(oldId, nv)
                                    android.widget.Toast.makeText(ctx, "用户 ID 已更新", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                }
                if (showEditName) {
                    EditFieldDialog("修改用户名称", displayUser.username, "输入新的用户名称", onDismiss = { showEditName = false }) { newVal ->
                        showEditName = false
                        if (newVal.isEmpty()) {
                            android.widget.Toast.makeText(ctx, "名称不能为空", android.widget.Toast.LENGTH_SHORT).show()
                            return@EditFieldDialog
                        }
                        scope.launch {
                            try {
                                AuroraApi.adminUpdateUser(displayUser.id, username = newVal)
                                withContext(Dispatchers.Main) {
                                    displayUser = displayUser.copy(username = newVal)
                                    android.widget.Toast.makeText(ctx, "用户名称已更新", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                }
                if (showEditQQ) {
                    EditFieldDialog("修改 QQ 号", displayUser.qqNumber, "输入新的 QQ 号（留空为解绑）", onDismiss = { showEditQQ = false }) { newVal ->
                        showEditQQ = false
                        scope.launch {
                            try {
                                AuroraApi.adminUpdateUser(displayUser.id, qqNumber = newVal)
                                withContext(Dispatchers.Main) {
                                    displayUser = displayUser.copy(qqNumber = newVal)
                                    android.widget.Toast.makeText(ctx, "QQ 号已更新", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                }
                if (showEditReg) {
                    EditFieldDialog("修改注册时间", dateFormat.format(Date(displayUser.createdAt * 1000)), "格式: yyyy-MM-dd HH:mm:ss", onDismiss = { showEditReg = false }) { newVal ->
                        showEditReg = false
                        val nv = try { dateFormat.parse(newVal)?.time?.div(1000) } catch (e: Exception) { null }
                        if (nv == null || nv <= 0) {
                            android.widget.Toast.makeText(ctx, "时间格式错误，应为 yyyy-MM-dd HH:mm:ss", android.widget.Toast.LENGTH_SHORT).show()
                            return@EditFieldDialog
                        }
                        scope.launch {
                            try {
                                AuroraApi.adminUpdateUser(displayUser.id, createdAt = nv)
                                withContext(Dispatchers.Main) {
                                    displayUser = displayUser.copy(createdAt = nv)
                                    android.widget.Toast.makeText(ctx, "注册时间已更新", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                // 当前生效的禁言类型（社区/对话/短视频/全部互相独立，这里直接显示哪种在生效）
                if (isMuted == true && muteType > 0) {
                    val muteTypeLabel = when (muteType) {
                        1 -> "社区禁言"; 2 -> "对话禁言"; 4 -> "短视频禁言"; else -> "全部禁言"
                    }
                    Text(
                        "当前禁言类型: $muteTypeLabel",
                        fontSize = 12.sp, color = Color(0xFFD97706), fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        textAlign = TextAlign.Center
                    )
                }
                // ── 操作按钮 ──
                // 只有开发者本人不可被操作（QQ 号或邮箱任一匹配，来自 LocalStorage 配置）
                val devQq = com.aurora.chat.data.local.LocalStorage.DEVELOPER_QQ
                val devEmail = com.aurora.chat.data.local.LocalStorage.DEVELOPER_EMAIL
                val isProtectedUser = (devQq.isNotEmpty() && displayUser.qqNumber == devQq) ||
                    (devEmail.isNotEmpty() && displayUser.email.equals(devEmail, ignoreCase = true))
                if (isProtectedUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isUserAdmin == true) {
                            Text("平台管理员 · 不可被操作", fontSize = 13.sp, color = Color(0xFF7C3AED), fontWeight = FontWeight.Medium)
                        } else {
                            Text("开发者账号 · 不可被操作", fontSize = 13.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
                        }
                    }
                } else if (isPlatformAdmin) {
                    // 平台管理员：仅封禁+禁言
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        if (isBanned == true) {
                            Text(if (isUnbanning) "解封中..." else "解封", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF10B981), modifier = Modifier.clickable(enabled = !isUnbanning) { isUnbanning = true; scope.launch { try { val r = AuroraApi.adminUnbanUser(user.id); withContext(Dispatchers.Main) { if (r.success) { android.widget.Toast.makeText(ctx, "已解封 ${user.username}", android.widget.Toast.LENGTH_SHORT).show(); banRefreshKey++ } else { android.widget.Toast.makeText(ctx, "解封失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "解封失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show() } } finally { isUnbanning = false } } }.weight(1f), textAlign = TextAlign.Center)
                        } else {
                            Text("封禁", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626), modifier = Modifier.clickable { showBanScreen = true }.weight(1f), textAlign = TextAlign.Center)
                        }
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        if (isMuted == true) {
                            Text(if (isUnmuting) "解除中..." else "解禁言", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF10B981), modifier = Modifier.clickable(enabled = !isUnmuting) { isUnmuting = true; scope.launch { try { val r = AuroraApi.adminUnmuteUser(user.id); withContext(Dispatchers.Main) { if (r.success) { android.widget.Toast.makeText(ctx, "已解除禁言 ${user.username}", android.widget.Toast.LENGTH_SHORT).show(); muteRefreshKey++ } else { android.widget.Toast.makeText(ctx, "解除禁言失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "解除禁言失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show() } } finally { isUnmuting = false } } }.weight(1f), textAlign = TextAlign.Center)
                        } else {
                            Text("禁言", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFD97706), modifier = Modifier.clickable { showMuteScreen = true }.weight(1f), textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    // 开发者完整视图：删除 | 封禁/解封 | 禁言/解禁言 | 修改 | 授权/取消授权
                    var showResetDialog by remember { mutableStateOf(false) }
                    var newPassword by remember { mutableStateOf("") }
                    var isResetting by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text(if (isDeleting) "删除中..." else "删除", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (isDeleting) com.aurora.chat.ui.theme.appTextHint() else Color(0xFFDC2626), modifier = Modifier.clickable(enabled = !isDeleting) { showDeleteDialog = true }.weight(1f), textAlign = TextAlign.Center)
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        if (isBanned == true) {
                            Text(if (isUnbanning) "解封中..." else "解封", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF10B981), modifier = Modifier.clickable(enabled = !isUnbanning) { isUnbanning = true; scope.launch { try { val r = AuroraApi.adminUnbanUser(user.id); withContext(Dispatchers.Main) { if (r.success) { android.widget.Toast.makeText(ctx, "已解封 ${user.username}", android.widget.Toast.LENGTH_SHORT).show(); banRefreshKey++ } else { android.widget.Toast.makeText(ctx, "解封失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "解封失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show() } } finally { isUnbanning = false } } }.weight(1f), textAlign = TextAlign.Center)
                        } else {
                            Text("封禁", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626), modifier = Modifier.clickable { showBanScreen = true }.weight(1f), textAlign = TextAlign.Center)
                        }
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        if (isMuted == true) {
                            Text(if (isUnmuting) "解除中..." else "解禁言", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF10B981), modifier = Modifier.clickable(enabled = !isUnmuting) { isUnmuting = true; scope.launch { try { val r = AuroraApi.adminUnmuteUser(user.id); withContext(Dispatchers.Main) { if (r.success) { android.widget.Toast.makeText(ctx, "已解除禁言 ${user.username}", android.widget.Toast.LENGTH_SHORT).show(); muteRefreshKey++ } else { android.widget.Toast.makeText(ctx, "解除禁言失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "解除禁言失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show() } } finally { isUnmuting = false } } }.weight(1f), textAlign = TextAlign.Center)
                        } else {
                            Text("禁言", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFD97706), modifier = Modifier.clickable { showMuteScreen = true }.weight(1f), textAlign = TextAlign.Center)
                        }
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        Text("修改", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF0D9488), modifier = Modifier.clickable { showResetDialog = true }.weight(1f), textAlign = TextAlign.Center)
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        Text(if (isUserAdmin == true) "权限" else "授权", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF7C3AED), modifier = Modifier.clickable { showPermissionEditor = true }.weight(1f), textAlign = TextAlign.Center)
                    }
                    // 取消授权（当目标用户是管理员时显示）
                    if (isUserAdmin == true) {
                        Spacer(Modifier.height(4.dp))
                        var showRevokeDialog by remember { mutableStateOf(false) }
                        var isRevoking by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (isRevoking) "撤销中..." else "取消授权",
                                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                color = if (isRevoking) com.aurora.chat.ui.theme.appTextHint() else Color(0xFFDC2626),
                                modifier = Modifier.clickable(enabled = !isRevoking) { showRevokeDialog = true }.weight(1f),
                                textAlign = TextAlign.Center
                            )
                        }
                        if (showRevokeDialog) {
                            AlertDialog(
                                onDismissRequest = { if (!isRevoking) showRevokeDialog = false },
                                containerColor = com.aurora.chat.ui.theme.appSurface(),
                                title = { Text("确认取消授权", fontWeight = FontWeight.Bold) },
                                text = { Text("确定要撤销 ${user.username} 的所有管理权限吗？\n\n撤销后将同时移除其平台管理员身份和所有细粒度权限。") },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            isRevoking = true
                                            scope.launch {
                                                try {
                                                    val r = AuroraApi.revokeAdminPermissions(user.id)
                                                    withContext(Dispatchers.Main) {
                                                        if (r.success) {
                                                            Toast.makeText(ctx, "已撤销 ${user.username} 的管理权限", Toast.LENGTH_SHORT).show()
                                                            isUserAdmin = false
                                                        } else {
                                                            Toast.makeText(ctx, r.message, Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(ctx, "操作失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                                    }
                                                } finally {
                                                    isRevoking = false
                                                    showRevokeDialog = false
                                                }
                                            }
                                        },
                                        enabled = !isRevoking
                                    ) { Text("确认撤销", color = Color(0xFFDC2626)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showRevokeDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                                }
                            )
                        }
                    }
                    // 第二行：位置 + 登录 + 徽章 + 服务 + 余额（仅开发者）
                    Spacer(Modifier.height(4.dp))
                    var showServerInfo by remember { mutableStateOf(false) }
                    var serverInfoData by remember { mutableStateOf<org.json.JSONObject?>(null) }
                    var serverInfoLoading by remember { mutableStateOf(false) }
                    var showBalanceDialog by remember { mutableStateOf(false) }
                    var balanceValue by remember { mutableStateOf(-1L) }
                    var balanceLoading by remember { mutableStateOf(false) }
                    // 文件分发配置（后台中心板块一）：该用户的单独配置（不受通用限制约束）
                    var showFdConfigDialog by remember { mutableStateOf(false) }
                    var fdConfigLoading by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("位置", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF059669), modifier = Modifier.clickable { showLocationScreen = true }.weight(1f), textAlign = TextAlign.Center)
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        Text(if (isLoggingIn) "登录中..." else "登录", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (isLoggingIn) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF7C3AED), modifier = Modifier.clickable(enabled = !isLoggingIn) {
                            isLoggingIn = true
                            scope.launch {
                                try {
                                    val result = com.aurora.chat.data.api.AuroraApi.adminLoginAsUser(user.id)
                                    if (result.success && result.data != null) {
                                        com.aurora.chat.data.local.LocalStorage.saveLogin(ctx, result.data!!)
                                        com.aurora.chat.data.api.AuroraApi.currentUserName = result.data!!.username
                                        // 关键：以新账号身份重建 TCP 长连接。
                                        // TcpService 的 userId 在启动时从 SP 读取一次后不再更新，
                                        // 若不主动 stop+start，旧连接会继续按原账号身份挂在服务器上，
                                        // 导致"小号登录后仍显示大号在线、消息错乱、邮箱推送失效"。
                                        try { com.aurora.chat.TcpService.stop(ctx) } catch (_: Exception) {}
                                        try { com.aurora.chat.TcpService.start(ctx) } catch (_: Exception) {}
                                        (ctx as? android.app.Activity)?.recreate()
                                    } else {
                                        android.widget.Toast.makeText(ctx, "登录失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        isLoggingIn = false
                                    }
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(ctx, "登录失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                    isLoggingIn = false
                                }
                            }
                        }.weight(1f), textAlign = TextAlign.Center)
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        Text(
                            text = if (serverInfoLoading) "查询中..." else "服务",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = if (serverInfoLoading) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF2563EB),
                            modifier = Modifier.clickable(enabled = !serverInfoLoading) {
                                serverInfoLoading = true
                                scope.launch {
                                    try {
                                        val result = com.aurora.chat.data.api.AuroraApi.adminGetUserServer(user.id)
                                        if (result.success && result.data != null) {
                                            serverInfoData = result.data
                                            showServerInfo = true
                                        } else {
                                            android.widget.Toast.makeText(ctx, "查询失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(ctx, "查询失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    serverInfoLoading = false
                                }
                            }.weight(1f),
                            textAlign = TextAlign.Center
                        )
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        Text(
                            text = if (balanceLoading) "查询中..." else "余额",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = if (balanceLoading) com.aurora.chat.ui.theme.appTextHint() else Color(0xFFF59E0B),
                            modifier = Modifier.clickable(enabled = !balanceLoading) {
                                balanceLoading = true
                                scope.launch {
                                    try {
                                        // user-login-stats 的 data 里含 token_balance（用户名或 ID 均可查）
                                        val result = com.aurora.chat.data.api.AuroraApi.getUserLoginStats(user.id.toString())
                                        if (result.success && result.data != null) {
                                            balanceValue = result.data!!.optLong("token_balance", 0L)
                                            showBalanceDialog = true
                                        } else {
                                            android.widget.Toast.makeText(ctx, "查询失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(ctx, "查询失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    balanceLoading = false
                                }
                            }.weight(1f),
                            textAlign = TextAlign.Center
                        )
                        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFFD1D5DB)))
                        Text(
                            text = if (fdConfigLoading) "查询中..." else "配置",
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = if (fdConfigLoading) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF0891B2),
                            modifier = Modifier.clickable(enabled = !fdConfigLoading) {
                                fdConfigLoading = true
                                scope.launch {
                                    try {
                                        val result = com.aurora.chat.data.api.AuroraApi.adminGetFdConfig(user.id)
                                        if (result.success && result.data != null) {
                                            showFdConfigDialog = true
                                        } else {
                                            android.widget.Toast.makeText(ctx, "查询失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(ctx, "查询失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    fdConfigLoading = false
                                }
                            }.weight(1f),
                            textAlign = TextAlign.Center
                        )
                    }
                    // 修改密码弹窗（管理员重置该用户登录密码）
                    if (showResetDialog) {
                        AlertDialog(
                            onDismissRequest = { if (!isResetting) { showResetDialog = false; newPassword = "" } },
                            title = { Text("修改密码", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text("为 ${user.username} 重置登录密码（服务器会以统一哈希加密存储）：", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                    Spacer(Modifier.height(10.dp))
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = newPassword,
                                        onValueChange = { newPassword = it },
                                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                                            .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(8.dp)),
                                        singleLine = true,
                                        decorationBox = { inner ->
                                            if (newPassword.isEmpty()) Text("输入新密码（至少 8 位）", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                            inner()
                                        }
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(enabled = !isResetting && newPassword.length >= 8, onClick = {
                                    isResetting = true
                                    scope.launch {
                                        try {
                                            val r = com.aurora.chat.data.api.AuroraApi.adminResetUserPassword(user.id, newPassword)
                                            withContext(Dispatchers.Main) {
                                                if (r.success) {
                                                    android.widget.Toast.makeText(ctx, "已重置 ${user.username} 的密码", android.widget.Toast.LENGTH_SHORT).show()
                                                    showResetDialog = false
                                                    newPassword = ""
                                                } else android.widget.Toast.makeText(ctx, "重置失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, "重置失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show() }
                                        }
                                        withContext(Dispatchers.Main) { isResetting = false }
                                    }
                                }) { Text(if (isResetting) "重置中..." else "确认", color = Color(0xFF0D9488)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { if (!isResetting) { showResetDialog = false; newPassword = "" } }) { Text("取消", color = Color(0xFF9E9E9E)) }
                            }
                        )
                    }
                    // 服务器信息弹窗（放在 Row 外面，点击任意行复制对应内容）
                    if (showServerInfo && serverInfoData != null) {
                        val hasServer = serverInfoData!!.optBoolean("has_server", false)
                        AlertDialog(
                            onDismissRequest = { showServerInfo = false },
                            title = { Text("服务器信息", fontWeight = FontWeight.Bold) },
                            text = {
                                if (!hasServer) {
                                    Text("该用户暂未注册服务器", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                } else {
                                    val s = serverInfoData!!.optJSONObject("server")
                                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    fun copyText(label: String, value: String) {
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                                        android.widget.Toast.makeText(ctx, "已复制 ${label}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    val statusRaw = s.optString("status", "offline")
                                    val statusCN = when (statusRaw) {
                                        "running" -> "运行中"
                                        "stopped" -> "已关机"
                                        "offline" -> "已离线"
                                        else -> statusRaw
                                    }
                                    Column {
                                        ClickRow(label = "名称", value = s.optString("name", ""), onCopy = { copyText("名称", it) })
                                        Spacer(Modifier.height(6.dp))
                                        ClickRow(label = "服务器ID", value = s.optLong("id", 0).toString(), onCopy = { copyText("服务器ID", it) })
                                        Spacer(Modifier.height(6.dp))
                                        ClickRow(label = "所有者", value = s.optString("owner_username", ""), onCopy = { copyText("所有者", it) })
                                        Spacer(Modifier.height(6.dp))
                                        ClickRow(label = "状态", value = statusCN, onCopy = { copyText("状态", statusRaw) })
                                        Spacer(Modifier.height(6.dp))
                                        ClickRow(label = "访问地址", value = s.optString("server_url", ""), onCopy = { copyText("访问地址", it) })
                                        Spacer(Modifier.height(6.dp))
                                        val domain = s.optString("domain", "")
                                        if (domain.isNotEmpty()) {
                                            ClickRow(label = "域名", value = domain, onCopy = { copyText("域名", it) })
                                            Spacer(Modifier.height(6.dp))
                                        }
                                        // 密码（bcrypt 哈希）对登录无用且暴露哈希值，不显示
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { showServerInfo = false }) { Text("关闭") } }
                        )
                    }
                    // 余额弹窗（查看该用户的 Token 余额）
                    if (showBalanceDialog) {
                        AlertDialog(
                            onDismissRequest = { showBalanceDialog = false },
                            containerColor = com.aurora.chat.ui.theme.appSurface(),
                            title = { Text("Token 余额", fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
                                    Text(
                                        text = "${user.username} 的账号余额：",
                                        fontSize = 14.sp,
                                        color = com.aurora.chat.ui.theme.appTextSecondary()
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = if (balanceValue >= 0) "$balanceValue Tokens" else "查询失败",
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFF59E0B)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "用户ID: ${user.id}",
                                        fontSize = 12.sp,
                                        color = com.aurora.chat.ui.theme.appTextHint()
                                    )
                                }
                            },
                            confirmButton = { TextButton(onClick = { showBalanceDialog = false }) { Text("关闭") } }
                        )
                    }
                    // 文件分发配置弹窗（该用户的单独配置，键值驱动，后台中心板块一）
                    if (showFdConfigDialog) {
                        FdConfigDialog(
                            userId = user.id,
                            userName = user.username,
                            onDismiss = { showFdConfigDialog = false }
                        )
                    }
                }
            }
            if (showLocationScreen) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showLocationScreen = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        UserLocationHistoryScreen(
                            userId = user.id,
                            userName = user.username,
                            onBack = { showLocationScreen = false }
                        )
                    }
                }
            }
        }
        // 封禁管理侧滑面板
        AnimatedVisibility(
            visible = showBanScreen,
            enter = slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(300)) { it },
            exit = slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            BanManagementScreen(
                user = user,
                onBack = { showBanScreen = false },
                onBanSuccess = { banRefreshKey++ }
            )
        }
        // 禁言管理侧滑面板
        AnimatedVisibility(
            visible = showMuteScreen,
            enter = slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(300)) { it },
            exit = slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            MuteManagementScreen(
                user = user,
                onBack = { showMuteScreen = false },
                onMuteSuccess = { muteRefreshKey++ }
            )
        }
        // 权限管理侧滑面板
        AnimatedVisibility(
            visible = showPermissionEditor,
            enter = slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(300)) { it },
            exit = slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(300)) { it },
            modifier = Modifier.fillMaxSize()
        ) {
            PermissionEditorPage(
                user = user,
                onClose = { showPermissionEditor = false }
            )
        }
    }
    // 删除确认弹窗
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("确认删除账号", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("删除后该用户账号与数据将被彻底删除，确定要继续吗？")
                    Spacer(Modifier.height(16.dp))
                    val isPH = isPlaceholderQQ(user.qqNumber)
                    if (isPH) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = blockIp,
                                onCheckedChange = { blockIp = it },
                                colors = androidx.compose.material3.CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFDC2626)
                                )
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("拉黑IP", fontSize = 14.sp, color = Color(0xFF4B5563),
                                modifier = Modifier.clickable { blockIp = !blockIp })
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = blockEmail,
                                onCheckedChange = { blockEmail = it },
                                colors = androidx.compose.material3.CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFDC2626)
                                )
                            )
                        Spacer(Modifier.width(4.dp))
                        Text("拉黑QQ", fontSize = 14.sp, color = Color(0xFF4B5563),
                            modifier = Modifier.clickable { blockEmail = !blockEmail })
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDeleteDialog = false
                        isDeleting = true
                        scope.launch {
                            try {
                                val result = com.aurora.chat.data.api.AuroraApi.adminDeleteUser(user.id, blockEmail, if (isPlaceholderQQ(user.qqNumber)) blockIp else false)
                                withContext(Dispatchers.Main) {
                                    if (result.success) {
                                        android.widget.Toast.makeText(ctx, "已删除用户 ${user.username}", android.widget.Toast.LENGTH_SHORT).show()
                                        onUserDeleted()
                                        onBack()
                                    } else {
                                        android.widget.Toast.makeText(ctx, result.message, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(ctx, "删除失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } finally {
                                isDeleting = false
                            }
                        }
                    },
                    enabled = !isDeleting
                ) {
                    Text("确定删除", color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }
}
/** 格式化用户 QQ 显示：未绑定显示"未填写QQ号" */
private fun displayUserQQ(qq: String): String {
    return if (qq.isNotEmpty()) qq else "未填写QQ号"
}
private fun isPlaceholderQQ(qq: String): Boolean = qq.isEmpty()
/** 检查权限列表是否包含指定权限（支持大类匹配，如 "balance" 匹配 "balance.manage"） */
private fun hasPermission(perms: List<String>, target: String): Boolean {
    if (perms.contains(target)) return true
    // 大类匹配：检查有无 "balance" 等前缀权限
    for (p in perms) {
        if (target.startsWith("$p.")) return true
    }
    return false
}
@Composable
private fun DetailRow(label: String, value: String, onEdit: (() -> Unit)? = null, copyValue: String? = null) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = com.aurora.chat.ui.theme.appTextHint(),
            modifier = Modifier.width(80.dp).clickable { onEdit?.invoke() }
        )
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = com.aurora.chat.ui.theme.appTextPrimary(),
            modifier = Modifier.clickable {
                val copied = copyValue ?: value
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, copied))
                android.widget.Toast.makeText(context, "已复制: $copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }
}
@Composable
private fun EditFieldDialog(
    title: String,
    current: String,
    hint: String,
    onDismiss: () -> Unit = {},
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.aurora.chat.ui.theme.appSurface(),
        title = { Text(title, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(hint, color = com.aurora.chat.ui.theme.appTextSecondary()) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2563EB),
                    unfocusedBorderColor = Color(0xFFD1D5DB),
                    cursorColor = Color(0xFF2563EB)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("确认", color = Color(0xFF2563EB)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
        }
    )
}
// ==================== 群聊管理 ====================
@Composable
private fun GroupManagementContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // 检测到底加载
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= groups.size - 3 && hasMore && !isLoading && groups.isNotEmpty()
        }
    }
    fun loadMore() {
        if (isLoading || !hasMore) return
        isLoading = true
        scope.launch {
            val r = ChatRepository.adminListGroups(page, 15, keyword)
            if (r.success && r.data != null) {
                val arr = r.data!!.optJSONArray("groups") ?: JSONArray()
                val total = r.data!!.optInt("total", 0)
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                groups = groups + list
                page++
                hasMore = groups.size < total
            } else {
                Toast.makeText(ctx, "加载失败", Toast.LENGTH_SHORT).show()
            }
            isLoading = false
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) loadMore()
    }
    LaunchedEffect(Unit) { loadMore() }
    // 操作弹窗状态
    var showOpsDialog by remember { mutableStateOf(false) }
    var opsGroup by remember { mutableStateOf<JSONObject?>(null) }
    // 修改群ID
    var showEditIdDialog by remember { mutableStateOf(false) }
    var editIdGroup by remember { mutableStateOf<JSONObject?>(null) }
    var newDisplayId by remember { mutableStateOf("") }
    // 解散确认
    var showDissolveConfirm by remember { mutableStateOf(false) }
    var dissolveTarget by remember { mutableStateOf<JSONObject?>(null) }
    // 发消息
    var showSendMsgDialog by remember { mutableStateOf(false) }
    var sendMsgGroupId by remember { mutableStateOf(0L) }
    var sendMsgText by remember { mutableStateOf("") }
    // 查看对话
    var showConversation by remember { mutableStateOf(false) }
    var convGroupId by remember { mutableStateOf(0L) }
    var convGroupName by remember { mutableStateOf("") }
    // 清理（按创建者批量解散）
    var showCleanupDialog by remember { mutableStateOf(false) }
    var cleanupUserId by remember { mutableStateOf("") }
    var cleanupLimit by remember { mutableStateOf("") }
    var cleanupQuerying by remember { mutableStateOf(false) }
    var cleanupAvailable by remember { mutableIntStateOf(-1) } // -1 = 尚未查询
    var cleanupTotal by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        // 标题
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("全服群聊列表", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.weight(1f))
            Text("共 ${groups.size + if (hasMore) 0 else 0} 群", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
            Spacer(Modifier.width(10.dp))
            // 清理：按创建者批量解散（输入用户 ID → 查询 → 选数量/全部）
            Text("清理", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFFEF2F2))
                    .clickable {
                        cleanupUserId = ""; cleanupLimit = ""; cleanupAvailable = -1; cleanupTotal = 0
                        showCleanupDialog = true
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp))
        }
        // 搜索框
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp)).background(com.aurora.chat.ui.theme.appCardBg())
            .padding(horizontal = 10.dp, vertical = 6.dp)) {
            BasicTextField(value = keyword, onValueChange = {
                keyword = it
                groups = emptyList(); page = 1; hasMore = true; loadMore()
            }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true, decorationBox = { inner ->
                    Box { if (keyword.isEmpty()) Text("搜索群名称/ID", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                })
        }
        // 列表
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState) {
            items(groups, key = { it.optLong("id") }) { g ->
                val gid = g.optLong("id")
                val gName = g.optString("name", "")
                val gDisplayId = g.optLong("display_id")
                val gCreator = g.optLong("creator_id")
                val gCreated = g.optLong("created_at", 0)
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg()).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(gName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Spacer(Modifier.height(2.dp))
                            Row {
                                Text("显示ID: $gDisplayId", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                Spacer(Modifier.width(12.dp))
                                Text("创建者: $gCreator", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            }
                            if (gCreated > 0) {
                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                Text("创建于: ${sdf.format(Date(gCreated * 1000))}", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                        }
                        Text("操作", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(com.aurora.chat.ui.theme.appSelectedBg())
                                .clickable {
                                    opsGroup = g; showOpsDialog = true
                                }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            if (isLoading) {
                item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("加载中...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }}
            }
            if (!hasMore && groups.isNotEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("— 已加载全部群聊 —", fontSize = 12.sp, color = Color(0xFFD1D5DB))
                }}
            }
        }
    }
    // ── 清理：按创建者批量解散（输入用户 ID → 查询数量 → 选数量/全部）──
    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("清理刷群", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "输入用户 ID，先查询该用户创建的群数量，再选择清理多少个（留空=全部）。",
                        fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg()).padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        BasicTextField(
                            value = cleanupUserId,
                            onValueChange = { v ->
                                cleanupUserId = v.filter { it.isDigit() }
                                cleanupAvailable = -1
                                cleanupTotal = 0
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box {
                                    if (cleanupUserId.isEmpty()) Text("用户 ID", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    inner()
                                }
                            }
                        )
                    }
                    if (cleanupQuerying) {
                        Spacer(Modifier.height(8.dp))
                        Text("查询中...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                    if (cleanupAvailable >= 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "该用户共 ${cleanupTotal} 个群，可清理 ${cleanupAvailable} 个",
                            fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(com.aurora.chat.ui.theme.appCardBg()).padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            BasicTextField(
                                value = cleanupLimit,
                                onValueChange = { v -> cleanupLimit = v.filter { it.isDigit() } },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                                singleLine = true,
                                decorationBox = { inner ->
                                    Box {
                                        if (cleanupLimit.isEmpty()) Text("清理数量（留空=全部）", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                        inner()
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = {
                        val uid = cleanupUserId.toLongOrNull()
                        if (uid == null || uid <= 0) {
                            Toast.makeText(ctx, "请输入有效用户 ID", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (cleanupQuerying) return@TextButton
                        cleanupQuerying = true
                        scope.launch {
                            val r = ChatRepository.adminDissolveGroupsByCreator(uid, dryRun = true)
                            cleanupQuerying = false
                            if (r.success && r.data != null) {
                                cleanupTotal = r.data!!.optInt("total", 0)
                                cleanupAvailable = r.data!!.optInt("available", 0)
                            } else {
                                Toast.makeText(ctx, r.message.ifBlank { "查询失败" }, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("查询", color = Color(0xFF1E40AF)) }
                    androidx.compose.material3.TextButton(onClick = {
                        val uid = cleanupUserId.toLongOrNull()
                        if (uid == null || uid <= 0) {
                            Toast.makeText(ctx, "请输入有效用户 ID", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (cleanupAvailable < 0) {
                            Toast.makeText(ctx, "请先点「查询」确认数量", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (cleanupQuerying) return@TextButton
                        val limit = cleanupLimit.toIntOrNull() ?: 0
                        if (limit > 0 && limit > cleanupAvailable) {
                            Toast.makeText(ctx, "最多可清理 ${cleanupAvailable} 个（留空=全部）", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        if (limit == 0 && cleanupAvailable == 0) {
                            Toast.makeText(ctx, "该用户没有可清理的群", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        cleanupQuerying = true
                        scope.launch {
                            val r = ChatRepository.adminDissolveGroupsByCreator(uid, dryRun = false, limit = limit)
                            cleanupQuerying = false
                            if (r.success) {
                                val n = r.data?.optJSONArray("dissolved")?.length() ?: 0
                                Toast.makeText(ctx, "已清理 $n 个群", Toast.LENGTH_SHORT).show()
                                showCleanupDialog = false
                                groups = emptyList(); page = 1; hasMore = true; loadMore()
                            } else {
                                Toast.makeText(ctx, r.message.ifBlank { "清理失败" }, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("开始清理", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showCleanupDialog = false }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }
    // ── 操作菜单弹窗 ──
    if (showOpsDialog && opsGroup != null) {
        val g = opsGroup!!
        val gid = g.optLong("id")
        val gName = g.optString("name", "")
        AlertDialog(
            onDismissRequest = { showOpsDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text(gName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = {
                Column {
                    listOf(
                    "修改群ID" to {
                        showOpsDialog = false
                        editIdGroup = g
                        newDisplayId = ""
                        showEditIdDialog = true
                    },
                    "解散群聊" to {
                        showOpsDialog = false
                        dissolveTarget = g
                        showDissolveConfirm = true
                    },
                    "发送消息" to {
                        showOpsDialog = false
                        sendMsgGroupId = gid
                        sendMsgText = ""
                        showSendMsgDialog = true
                    },
                    "查看对话" to {
                        showOpsDialog = false
                        convGroupId = gid
                        convGroupName = gName
                        showConversation = true
                    }
                    ).forEach { (item, action) ->
                        Text(item, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                            modifier = Modifier.fillMaxWidth().clickable { action() }
                                .padding(vertical = 14.dp, horizontal = 4.dp))
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("取消", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                        modifier = Modifier.fillMaxWidth().clickable { showOpsDialog = false }
                            .padding(vertical = 14.dp, horizontal = 4.dp), textAlign = TextAlign.Center)
                }
            },
            confirmButton = {}, dismissButton = {}
        )
    }
    // ── 修改群ID弹窗 ──
    if (showEditIdDialog && editIdGroup != null) {
        val g = editIdGroup!!
        val gid = g.optLong("id")
        AlertDialog(
            onDismissRequest = { showEditIdDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("修改群ID", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("当前显示ID: ${g.optLong("display_id")}", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                        .padding(horizontal = 10.dp, vertical = 8.dp)) {
                        BasicTextField(value = newDisplayId, onValueChange = { if (it.all { c -> c.isDigit() }) newDisplayId = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                            singleLine = true, decorationBox = { inner ->
                                Box { if (newDisplayId.isEmpty()) Text("输入新群ID (1-100000)", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                            })
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val newId = newDisplayId.toLongOrNull() ?: return@TextButton
                    if (newId < 1 || newId > 100000) { Toast.makeText(ctx, "群ID必须在 1-100000 之间", Toast.LENGTH_SHORT).show(); return@TextButton }
                    scope.launch {
                        val r = ChatRepository.adminUpdateGroupDisplayId(gid, newId)
                        if (r.success) {
                            Toast.makeText(ctx, "群ID已更新", Toast.LENGTH_SHORT).show()
                            showEditIdDialog = false
                            groups = emptyList(); page = 1; hasMore = true; loadMore()
                        } else {
                            Toast.makeText(ctx, r.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确认修改", color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showEditIdDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
    // ── 解散群聊确认 ──
    if (showDissolveConfirm && dissolveTarget != null) {
        val g = dissolveTarget!!
        val gid = g.optLong("id")
        AlertDialog(
            onDismissRequest = { showDissolveConfirm = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("解散群聊", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定要解散「${g.optString("name", "")}」(${g.optLong("display_id")}) 吗？\n此操作不可撤销！", fontSize = 14.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDissolveConfirm = false
                    scope.launch {
                        val r = ChatRepository.dissolveGroup(gid)
                        if (r.success) {
                            Toast.makeText(ctx, "群聊已解散", Toast.LENGTH_SHORT).show()
                            groups = emptyList(); page = 1; hasMore = true; loadMore()
                        } else {
                            Toast.makeText(ctx, "解散失败: ${r.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("确认解散", color = Color(0xFFDC2626), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDissolveConfirm = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
    // ── 发送消息弹窗 ──
    if (showSendMsgDialog) {
        AlertDialog(
            onDismissRequest = { showSendMsgDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("发送消息到群聊", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("你将以开发者身份向此群发送消息（无需入群）", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(com.aurora.chat.ui.theme.appCardBg())
                        .padding(horizontal = 10.dp, vertical = 8.dp)) {
                        BasicTextField(value = sendMsgText, onValueChange = { sendMsgText = it },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                            decorationBox = { inner ->
                                Box { if (sendMsgText.isEmpty()) Text("输入消息内容", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()); inner() }
                            })
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (sendMsgText.isBlank()) return@TextButton
                    scope.launch {
                        val r = ChatRepository.adminDeveloperSendMessage(sendMsgGroupId, sendMsgText)
                        if (r.success) {
                            Toast.makeText(ctx, "消息已发送", Toast.LENGTH_SHORT).show()
                            sendMsgText = ""
                            showSendMsgDialog = false
                        } else {
                            Toast.makeText(ctx, "发送失败: ${r.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("发送", color = Color(0xFF1E40AF), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSendMsgDialog = false }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
    // ── 查看对话面板（全屏对话框形式）
    if (showConversation) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showConversation = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            GroupDevConversationPanel(
                groupId = convGroupId,
                groupName = convGroupName,
                onBack = { showConversation = false }
            )
        }
    }
}
// ── 开发者查看群对话面板 ──
@Composable
private fun GroupDevConversationPanel(groupId: Long, groupName: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var inputText by remember { mutableStateOf("") }
    var firstLoadDone by remember { mutableStateOf(false) }
    // 查看用户主页
    var viewingUser by remember { mutableStateOf<UserInfo?>(null) }
    val sdf = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val listState = rememberLazyListState()
    fun loadMsgs(p: Int, reset: Boolean) {
        if (isLoading) return
        isLoading = true
        scope.launch {
            try {
                val r = ChatRepository.adminGetGroupMessages(groupId, p, 30)
                if (r.success && r.data != null) {
                    val arr = r.data!!
                    val list = withContext(Dispatchers.IO) {
                        val out = mutableListOf<JSONObject>()
                        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
                        out
                    }
                    if (reset) {
                        messages = list
                        hasMore = arr.length() >= 30
                        page = 2
                    } else {
                        messages = list + messages
                        hasMore = arr.length() >= 30
                        page = p + 1
                    }
                    if (reset && list.isNotEmpty()) {
                        delay(100)
                        listState.animateScrollToItem(list.size - 1)
                    }
                } else {
                    hasMore = false
                }
            } catch (_: Exception) {
                Toast.makeText(ctx, "加载消息失败", Toast.LENGTH_SHORT).show()
            }
            isLoading = false
            firstLoadDone = true
        }
    }
    LaunchedEffect(groupId) {
        messages = emptyList()
        hasMore = true
        page = 1
        firstLoadDone = false
        loadMsgs(1, true)
    }
    // 发送消息后刷新
    fun sendMsg() {
        if (inputText.isBlank()) return
        scope.launch {
            val r = ChatRepository.adminDeveloperSendMessage(groupId, inputText)
            if (r.success) {
                inputText = ""
                val rr = ChatRepository.adminGetGroupMessages(groupId, 1, 30)
                if (rr.success && rr.data != null) {
                    val arr = rr.data!!
                    val list = withContext(Dispatchers.IO) {
                        val out = mutableListOf<JSONObject>()
                        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
                        out
                    }
                    messages = list
                    hasMore = arr.length() >= 30
                    page = 2
                    delay(100)
                    listState.animateScrollToItem((list.size - 1).coerceAtLeast(0))
                }
            } else {
                Toast.makeText(ctx, "发送失败: ${r.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (viewingUser != null) {
            // 使用已有的 UserDetailPage（功能完整）
            UserDetailPage(
                user = viewingUser!!,
                onBack = { viewingUser = null },
                onUserDeleted = { viewingUser = null; loadMsgs(1, true) },
                onUserIdChanged = { oldId, newId ->
                    viewingUser = viewingUser?.copy(id = newId)
                    // 同步消息列表里该用户的展示 ID
                    messages = messages.map { m ->
                        if (m.optLong("from_user_id", 0) == oldId) {
                            val c = org.json.JSONObject(m.toString())
                            c.put("from_user_id", newId)
                            c
                        } else m
                    }
                }
            )
        } else {
            Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
                // 顶栏
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E40AF),
                        modifier = Modifier.clickable { onBack() }.padding(end = 12.dp))
                    Text("群聊对话 - $groupName", fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
                }
                HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider())
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    if (hasMore && messages.isNotEmpty()) {
                        item(key = "load_more") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center) {
                                if (isLoading) {
                                    Text("加载中...", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                } else {
                                    Text("加载更多", fontSize = 12.sp, color = Color(0xFF1E40AF),
                                        modifier = Modifier.clickable { loadMsgs(page, false) }
                                            .padding(horizontal = 16.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                    items(messages, key = { it.optLong("id") }) { msg ->
                        DevMessageItem(
                            msg = msg,
                            sdf = sdf,
                            onUserClick = { uid, uname ->
                                viewingUser = UserInfo(
                                    id = uid,
                                    email = "",
                                    username = uname,
                                    createdAt = 0L
                                )
                                // 异步加载完整用户信息
                                scope.launch {
                                    try {
                                        // 走仓库层：这里看过的用户也一并写进全局资料缓存
                                        val info = com.aurora.chat.data.repository.ChatRepository.getUserInfo(uid)
                                        if (info.success && info.data != null) {
                                            viewingUser = info.data
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        )
                    }
                }
                // 输入框
                HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider())
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .padding(horizontal = 14.dp, vertical = 8.dp)) {
                        BasicTextField(value = inputText, onValueChange = { inputText = it },
                            textStyle = MaterialTheme.typography.bodyMedium
                                .copy(fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                            decorationBox = { inner ->
                                Box {
                                    if (inputText.isEmpty())
                                        Text("输入消息...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                                    inner()
                                }
                            })
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("发送", fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = if (inputText.isBlank()) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF1E40AF),
                        modifier = Modifier
                            .clickable(enabled = inputText.isNotBlank()) { sendMsg() }
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}
// ── 开发者对话单条消息 ──
@Composable
private fun DevMessageItem(
    msg: JSONObject,
    sdf: SimpleDateFormat,
    onUserClick: (Long, String) -> Unit
) {
    val senderId = msg.optLong("from_user_id", 0)
    val content = msg.optString("content", "")
    val ts = msg.optLong("created_at", 0)
    val senderName = msg.optString("username", "").ifEmpty { "用户$senderId" }
    val avatarSize = 36.dp
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左侧头像（可点击查看用户）
        Box(modifier = Modifier
            .size(avatarSize)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onUserClick(senderId, senderName) }) {
            UserAvatar(userId = senderId, userName = senderName, size = avatarSize)
        }
        Spacer(Modifier.width(8.dp))
        // 右侧消息内容
        Column(modifier = Modifier.weight(1f)) {
            // 用户名（可点击查看用户）
            Text(
                text = senderName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF4B5563),
                modifier = Modifier
                    .clickable { onUserClick(senderId, senderName) }
                    .padding(bottom = 3.dp)
            )
            // 消息气泡
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp, 10.dp, 10.dp, 10.dp))
                    .background(com.aurora.chat.ui.theme.appCardBg())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(content, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 20.sp)
            }
            // 时间
            if (ts > 0) {
                var timeText = ""
                try { timeText = sdf.format(Date(ts * 1000)) } catch (_: Exception) {}
                Text(timeText, fontSize = 10.sp, color = Color(0xFFADB5BD),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
        }
    }
}
// ── 短视频总开关（开发者专属）──
//
// 关闭后**全站所有人都看不到**短视频：服务端在 feed / detail / 上传 / 点赞评论 / 播放计数
// 以及**公开的 HLS 取流路由**上统一拦截，拿到 play_url 也取不到一个字节。
// 提示文案由这里自定义；客户端「进入短视频页之后」才显示，入口点击不做任何提示。
@Composable
fun ShortVideoGateContent() {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    // true = 允许观看 / 允许上传（默认都开启）
    var enabled by remember { mutableStateOf(true) }
    var uploadEnabled by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun applyServer(d: org.json.JSONObject) {
        enabled = !d.optBoolean("disabled", false)
        uploadEnabled = !d.optBoolean("upload_disabled", false)
        message = d.optString("message", "")
    }

    fun load() {
        loading = true
        scope.launch {
            try {
                val r = AuroraApi.getShortVideoGate()
                if (r.success && r.data != null) applyServer(r.data!!)
                else if (!r.success) android.widget.Toast.makeText(ctx, "加载失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "加载失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val r = AuroraApi.setShortVideoGate(
                    disabled = !enabled,
                    uploadDisabled = !uploadEnabled,
                    message = message.trim()
                )
                if (r.success && r.data != null) {
                    applyServer(r.data!!)
                    android.widget.Toast.makeText(
                        ctx, "已保存", android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(ctx, "保存失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "保存失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF1E40AF))
        }
        return
    }

    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            ShortVideoSwitchRow("允许观看", enabled) { enabled = it }
            Spacer(Modifier.height(10.dp))
            ShortVideoSwitchRow("允许上传", uploadEnabled) { uploadEnabled = it }

            Spacer(Modifier.height(22.dp))
            Text("关闭观看后的提示", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = message,
                onValueChange = { if (it.length <= 200) message = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                placeholder = { Text("短视频功能已关闭", color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 14.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                maxLines = 6,
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E40AF),
                    disabledContainerColor = Color(0xFF1E40AF).copy(alpha = 0.4f)
                )
            ) {
                Text(if (saving) "保存中…" else "保存", fontSize = 15.sp, color = Color.White)
            }
        }
    }
}

/** 短视频设置里的单行开关卡片（观看 / 上传共用） */
@Composable
private fun ShortVideoSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title, modifier = Modifier.weight(1f),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF1E40AF),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD1D5DB)
                )
            )
        }
    }
}

@Composable
// ── 抵防：全局极端防御开关（开发者专属）──
fun DefenseTab() {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var disableRegister by remember { mutableStateOf(false) }
    var officialGroupMute by remember { mutableStateOf(false) }
    var communityBan by remember { mutableStateOf(false) }
    var fullMute by remember { mutableStateOf(false) }
    var disableOfficialApi by remember { mutableStateOf(false) }
    // 待二次确认的操作
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var pendingValue by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun getItem(key: String): Triple<String, String, String> = when (key) {
        "disable_register" -> Triple("disable_register", "禁止注册", "开启后将直接切断所有注册请求，直到开发者再次点击解禁。")
        "official_group_mute" -> Triple("official_group_mute", "官群禁言", "开启后，除开发者外所有用户在官方群的消息请求将被切断。")
        "community_ban" -> Triple("community_ban", "社区禁发", "开启后，用户无法再发布帖子或上传资源（图片/视频/文件等）。")
        "full_mute" -> Triple("full_mute", "全面禁言", "开启后，除开发者外所有用户的消息（群聊/私聊等）一律强制截止发送。")
        "disable_official_api" -> Triple("disable_official_api", "中断官方 API", "开启后全服所有用户的官方 API 接口将停止工作，用户只能使用个人 API 或等待恢复。")
        else -> Triple("", "", "")
    }
    fun loadFlags() {
        loading = true
        scope.launch {
            try {
                val r = AuroraApi.getDefense()
                if (r.success && r.data != null) {
                    val d = r.data!!
                    disableRegister = d.optBoolean("disable_register")
                    officialGroupMute = d.optBoolean("official_group_mute")
                    communityBan = d.optBoolean("community_ban")
                    fullMute = d.optBoolean("full_mute")
                    disableOfficialApi = d.optBoolean("disable_official_api")
                } else if (!r.success) {
                    android.widget.Toast.makeText(ctx, "加载失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "加载失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { loadFlags() }
    fun getValue(key: String) = when (key) {
        "disable_register" -> disableRegister
        "official_group_mute" -> officialGroupMute
        "community_ban" -> communityBan
        "full_mute" -> fullMute
        "disable_official_api" -> disableOfficialApi
        else -> false
    }
    fun setValue(key: String, v: Boolean) {
        when (key) {
            "disable_register" -> disableRegister = v
            "official_group_mute" -> officialGroupMute = v
            "community_ban" -> communityBan = v
            "full_mute" -> fullMute = v
            "disable_official_api" -> disableOfficialApi = v
        }
    }
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(color = Color(0xFF1E40AF))
        }
        return
    }
    val keys = listOf("disable_register", "official_group_mute", "community_ban", "full_mute", "disable_official_api")
    Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("极端防御措施", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(4.dp))
            Text(
                "以下开关开启后立即全站生效，且会保持状态直到开发者再次切换。每个操作都需要二次确认。",
                fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
            )
            Spacer(Modifier.height(16.dp))
            keys.forEach { key ->
                val item = getItem(key)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .then(if (getValue(key)) Modifier.border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(12.dp)) else Modifier),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(item.second, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                            Spacer(Modifier.height(4.dp))
                            Text(item.third, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), lineHeight = 17.sp)
                        }
                        androidx.compose.material3.Switch(
                            checked = getValue(key),
                            onCheckedChange = { nv ->
                                pendingKey = key
                                pendingValue = nv
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFDC2626),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD1D5DB)
                            )
                        )
                    }
                }
            }
        }
        // 二次确认弹窗
        if (pendingKey != null) {
            val key = pendingKey!!
            val nv = pendingValue
            val item = getItem(key)
            AlertDialog(
                onDismissRequest = { pendingKey = null },
                title = {
                    Text(
                        if (nv) "确认开启" else "确认解禁",
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()
                    )
                },
                text = {
                    Text(
                        if (nv) "确定要开启「${item.second}」吗？\n开启后立即全站生效。"
                        else "确定要解禁「${item.second}」吗？\n操作后该功能将恢复正常。",
                        fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                val r = AuroraApi.setDefense(key, nv)
                                if (r.success) {
                                    setValue(key, nv)
                                } else {
                                    android.widget.Toast.makeText(ctx, "操作失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(ctx, "操作失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                            } finally {
                                pendingKey = null
                            }
                        }
                    }) {
                        Text(
                            if (nv) "确认开启" else "确认解禁",
                            color = Color(0xFFDC2626), fontWeight = FontWeight.Medium
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingKey = null }) {
                        Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                }
            )
        }
    }
}
// ==================== 踢出登录管理 ====================
@Composable
private fun KickManagementContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var targetId by remember { mutableStateOf("") }
    var kicking by remember { mutableStateOf(false) }
    var kickingAll by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var showKickAllDialog by remember { mutableStateOf(false) }
    fun doKickAll() {
        kickingAll = true
        resultMsg = null
        showKickAllDialog = false
        scope.launch {
            try {
                val r = ChatRepository.adminKickAll()
                resultMsg = if (r.success) "✓ ${r.message}" else "✗ ${r.message}"
            } catch (e: Exception) {
                resultMsg = "✗ 操作失败: ${e.localizedMessage ?: "未知错误"}"
            }
            kickingAll = false
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).padding(16.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 说明文字
            Text(
                text = "踢出用户登录状态",
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "被踢出的用户下次打开 App 时会自动回到登录页",
                fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint()
            )
            Spacer(Modifier.height(20.dp))
            // ── 单个踢出 ──
            Text("踢出指定用户", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = targetId,
                    onValueChange = { targetId = it.filter { c -> c.isDigit() } },
                    placeholder = { Text("输入用户 ID", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFD1D5DB),
                        focusedBorderColor = Color(0xFF2563EB)
                    )
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val id = targetId.toLongOrNull() ?: return@Button
                        if (id <= 1) { Toast.makeText(ctx, "无效的用户ID", Toast.LENGTH_SHORT).show(); return@Button }
                        kicking = true; resultMsg = null
                        scope.launch {
                            try {
                                val r = ChatRepository.adminKickUser(id)
                                resultMsg = if (r.success) "✓ 已踢出用户 #$id" else "✗ ${r.message}"
                            } catch (e: Exception) {
                                resultMsg = "✗ 操作失败: ${e.localizedMessage ?: "未知错误"}"
                            }
                            kicking = false
                        }
                    },
                    enabled = targetId.isNotBlank() && !kicking,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.height(48.dp)
                ) {
                    if (kicking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text("踢出", color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            // ── 分割线 ──
            HorizontalDivider(color = com.aurora.chat.ui.theme.appDivider())
            Spacer(Modifier.height(24.dp))
            // ── 一键踢出全部 ──
            Text("踢出全部用户", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(4.dp))
            Text(
                text = "除开发者（ID=1）外所有用户将被强制退出登录",
                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { showKickAllDialog = true },
                enabled = !kickingAll,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (kickingAll) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (kickingAll) "执行中..." else "一键踢出全部", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            // ── 结果反馈 ──
            resultMsg?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (msg.startsWith("✓")) Color(0xFFD1FAE5) else Color(0xFFFEE2E2))
                        .padding(14.dp)
                ) {
                    Text(text = msg, fontSize = 14.sp, color = if (msg.startsWith("✓")) Color(0xFF065F46) else Color(0xFF991B1B))
                }
            }
        }
    }
    // 一键踢出全部确认弹窗
    if (showKickAllDialog) {
        AlertDialog(
            onDismissRequest = { showKickAllDialog = false },
            containerColor = com.aurora.chat.ui.theme.appSurface(),
            title = { Text("确认踢出全部用户", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text(
                    text = "此操作将踢出除开发者外的所有用户，所有用户需要重新登录。\n\n请谨慎操作！",
                    fontSize = 14.sp,
                    color = Color(0xFF4B5563),
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { doKickAll() }) {
                    Text("确认踢出", color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showKickAllDialog = false }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = FontWeight.Medium)
                }
            }
        )
    }
}
// ==================== 公告管理 ====================
private val todayDate: String
    get() {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }
// ==================== 公告本地缓存 ====================
private const val ANNOUNCEMENT_PREFS = "aurora_announcement"
private const val KEY_ENABLED = "enabled"
private const val KEY_TITLE = "title"
private const val KEY_CONTENT = "content"
private const val KEY_SOURCE = "source"
private const val KEY_DATE = "date"
private const val KEY_SUPPLEMENT = "supplement"
data class LocalAnnouncement(
    val enabled: Boolean,
    val title: String,
    val content: String,
    val source: String,
    val date: String,
    val supplement: String
)
private fun saveAnnouncementLocal(
    ctx: Context,
    enabled: Boolean,
    title: String,
    content: String,
    source: String,
    date: String,
    supplement: String
) {
    ctx.getSharedPreferences(ANNOUNCEMENT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .putString(KEY_TITLE, title)
        .putString(KEY_CONTENT, content)
        .putString(KEY_SOURCE, source)
        .putString(KEY_DATE, date)
        .putString(KEY_SUPPLEMENT, supplement)
        .apply()
}
private fun loadAnnouncementLocal(ctx: Context): LocalAnnouncement? {
    val prefs = ctx.getSharedPreferences(ANNOUNCEMENT_PREFS, Context.MODE_PRIVATE)
    if (!prefs.contains(KEY_TITLE)) return null
    return LocalAnnouncement(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        title = prefs.getString(KEY_TITLE, "") ?: "",
        content = prefs.getString(KEY_CONTENT, "") ?: "",
        source = prefs.getString(KEY_SOURCE, "拾光工作室") ?: "拾光工作室",
        date = prefs.getString(KEY_DATE, todayDate) ?: todayDate,
        supplement = prefs.getString(KEY_SUPPLEMENT, "") ?: ""
    )
}
@Composable
private fun AnnouncementManagementContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("拾光工作室") }
    var date by remember { mutableStateOf(todayDate) }
    var supplement by remember { mutableStateOf("") }
    // 草稿状态：loaded 前不落盘；dirty 表示有未同步到服务器的编辑
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    // 加载现有公告：服务器成功时，若本地草稿与服务器不一致（上次没等到自动保存就离开），以本地为准
    LaunchedEffect(Unit) {
        val local = loadAnnouncementLocal(ctx)
        val result = ChatRepository.getAdminAnnouncement()
        if (result.success && result.data != null && result.data!!.length() > 0) {
            val d = result.data!!
            val sEnabled = d.optBoolean("enabled", false)
            val sTitle = d.optString("title", "")
            val sContent = d.optString("content", "")
            val sSource = d.optString("source", "拾光工作室")
            val sDate = d.optString("date", todayDate)
            val sSupplement = d.optString("supplement", "")
            val localDiffers = local != null && (
                local.enabled != sEnabled || local.title != sTitle || local.content != sContent ||
                    local.source != sSource || local.date != sDate || local.supplement != sSupplement
                )
            if (localDiffers) {
                enabled = local!!.enabled
                title = local.title
                content = local.content
                source = local.source
                date = local.date
                supplement = local.supplement
                dirty = true
            } else {
                enabled = sEnabled
                title = sTitle
                content = sContent
                source = sSource
                date = sDate
                supplement = sSupplement
                saveAnnouncementLocal(ctx, enabled, title, content, source, date, supplement)
            }
        } else {
            // 服务器拉取失败，回退本地缓存，保证离开再进入数据不丢
            val fallback = local
            if (fallback != null) {
                enabled = fallback.enabled
                title = fallback.title
                content = fallback.content
                source = fallback.source
                date = fallback.date
                supplement = fallback.supplement
            }
        }
        loaded = true
        isLoading = false
    }
    // 本地草稿：任何输入变更立即落盘，离开页面也不会丢
    LaunchedEffect(loaded, enabled, title, content, source, date, supplement) {
        if (loaded) saveAnnouncementLocal(ctx, enabled, title, content, source, date, supplement)
    }
    // 自动同步服务器：停止输入 800ms 后保存；标题/内容为空不写，避免把公告清空
    LaunchedEffect(loaded, dirty, isSaving, enabled, title, content, source, date, supplement) {
        if (!loaded || !dirty || isSaving) return@LaunchedEffect
        if (title.isBlank() || content.isBlank()) return@LaunchedEffect
        delay(800)
        val r = runCatching {
            ChatRepository.saveAnnouncement(
                title = title,
                content = content,
                source = source.ifBlank { "拾光工作室" },
                date = date.ifBlank { todayDate },
                supplement = supplement,
                enabled = enabled
            )
        }.getOrNull()
        if (r?.success == true) {
            dirty = false
            saveAnnouncementLocal(ctx, enabled, title, content, source, date, supplement)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.aurora.chat.ui.theme.appSurface())
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = Color(0xFF1E40AF))
            }
            return
        }
        // 开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "公告【${if (enabled) "已开启" else "已关闭"}】",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = { enabled = it; dirty = true },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF1E40AF),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD1D5DB)
                )
            )
        }
        Spacer(Modifier.height(20.dp))
        // 标题
        Text("标题", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it; dirty = true },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) Text("输入公告标题", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        // 内容（默认显示五行，可滑动）
        Text("内容", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 300.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = content,
                onValueChange = { content = it; dirty = true },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), lineHeight = 22.sp),
                modifier = Modifier.fillMaxSize(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()) {
                        if (content.isEmpty()) Text("输入公告内容（支持多行，可在框内滑动）", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        // 来源
        Text("来源", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = source,
                onValueChange = { source = it; dirty = true },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (source.isEmpty()) Text("默认 拾光工作室", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        // 日期
        Text("日期", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = date,
                onValueChange = { date = it; dirty = true },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (date.isEmpty()) Text("格式: 2026-07-04", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        // 补充（可选）
        Text("补充（可选）", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(com.aurora.chat.ui.theme.appCardBg())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = supplement,
                onValueChange = { supplement = it; dirty = true },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary()),
                singleLine = true,
                decorationBox = { inner ->
                    Box {
                        if (supplement.isEmpty()) Text("选填，为空则不显示", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(28.dp))
        // 保存按钮
        Button(
            onClick = {
                if (title.isBlank()) {
                    android.widget.Toast.makeText(ctx, "请输入公告标题", android.widget.Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (content.isBlank()) {
                    android.widget.Toast.makeText(ctx, "请输入公告内容", android.widget.Toast.LENGTH_SHORT).show()
                    return@Button
                }
                isSaving = true
                scope.launch {
                    try {
                        val result = ChatRepository.saveAnnouncement(
                            title = title,
                            content = content,
                            source = source.ifBlank { "拾光工作室" },
                            date = date.ifBlank { todayDate },
                            supplement = supplement,
                            enabled = enabled
                        )
                        if (result.success) {
                            // 服务器保存成功，也写一份本地缓存，防止意外丢失；
                            // dirty 不在这里清 —— 交给自动保存再核对一次，避免保存请求期间的新输入被漏掉
                            saveAnnouncementLocal(ctx, enabled, title, content, source, date, supplement)
                            android.widget.Toast.makeText(ctx, "公告已保存", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(ctx, "保存失败: ${result.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(ctx, "保存失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    isSaving = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isSaving) "保存中..." else "保存公告", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(40.dp))
    }
}
// ==================== 工具函数 ====================
private fun copyToClipboard(ctx: android.content.Context, text: String, toastMsg: String) {
    try {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("label", text))
        Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {}
}
// ==================== 体验版管理 ====================
/** 清理对话数据：填入两个用户 ID，永久删除两人之间的全部对话（含文本/图片/卡片） */
@Composable
private fun CleanConversationContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var userA by remember { mutableStateOf("") }
    var userB by remember { mutableStateOf("") }
    var cleaning by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("清理对话数据", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.height(4.dp))
        Text("填入两个用户 ID，将永久删除两人之间的全部对话数据（含文本、图片、卡片），不可恢复。", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = userA,
            onValueChange = { userA = it.filter { c -> c.isDigit() } },
            label = { Text("用户 A ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = userB,
            onValueChange = { userB = it.filter { c -> c.isDigit() } },
            label = { Text("用户 B ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (userA.toLongOrNull() == null || userB.toLongOrNull() == null) {
                    Toast.makeText(ctx, "请填写两个有效的用户 ID", Toast.LENGTH_SHORT).show()
                } else {
                    showConfirm = true
                }
            },
            enabled = !cleaning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
            Text(if (cleaning) "清理中..." else "清理对话数据", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text("确认清理") },
                text = { Text("将永久删除用户 $userA 与用户 $userB 之间的全部对话数据（含文本、图片、卡片），且不可恢复。确定继续？") },
                confirmButton = {
                    TextButton(onClick = {
                        showConfirm = false
                        cleaning = true
                        scope.launch {
                            try {
                                val a = userA.toLong()
                                val b = userB.toLong()
                                val r = AuroraApi.cleanConversationBetween(a, b)
                                val count = r.data ?: 0
                                Toast.makeText(ctx, "已清理 $count 条消息", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "清理失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            } finally {
                                cleaning = false
                            }
                        }
                    }) { Text("确认清理", color = Color(0xFFDC2626)) }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) { Text("取消") }
                }
            )
        }
    }
}
@Composable
private fun TrialManagementContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var trialEnabled by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        loading = true
        try {
            val r = AuroraApi.adminGetTrialMode()
            if (r.success && r.data != null) trialEnabled = r.data.optBoolean("enabled", false)
        } catch (_: Exception) {}
        loading = false
    }
    var minVersion by remember { mutableStateOf("") }
    var lockVersion by remember { mutableStateOf("") }
    var versionLoading by remember { mutableStateOf(true) }
    // 加载版本锁定设置
    LaunchedEffect(Unit) {
        versionLoading = true
        try {
            val r = AuroraApi.adminGetTrialVersionSettings()
            if (r.success && r.data != null) {
                minVersion = r.data.optString("min_supported_version", "")
                lockVersion = r.data.optString("force_lock_version", "")
            }
        } catch (_: Exception) {}
        versionLoading = false
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).padding(16.dp)
    ) {
            // 体验版开关
            Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("体验版模式", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Text(if (trialEnabled) "已开启 — 用户进入需输入卡密" else "已关闭 — 用户可正常使用",
                    fontSize = 12.sp, color = if (trialEnabled) Color(0xFF059669) else com.aurora.chat.ui.theme.appTextHint())
            }
            androidx.compose.material3.Switch(
                checked = trialEnabled,
                onCheckedChange = { enable ->
                    scope.launch {
                        try {
                            val r = AuroraApi.adminSetTrialMode(enable)
                            if (r.success) {
                                trialEnabled = enable
                                Toast.makeText(ctx, "体验版模式已${if (enable) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(ctx, "操作失败", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) { Toast.makeText(ctx, "网络错误", Toast.LENGTH_SHORT).show() }
                    }
                },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF059669)
                )
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "体验版模式下，新用户进入应用需输入有效卡密才能使用。下方为体验卡密列表，生成后分发即可。",
            fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
        )
        Spacer(Modifier.height(16.dp))
        // ==================== 版本锁定设置 ====================
        Text("版本锁定", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = minVersion,
            onValueChange = { minVersion = it },
            label = { Text("最低支持版本", fontSize = 14.sp) },
            placeholder = { Text("例如: 1.3.0", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF059669),
                cursorColor = Color(0xFF059669)
            ),
            enabled = !versionLoading
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = lockVersion,
            onValueChange = { lockVersion = it },
            label = { Text("强制锁定版本", fontSize = 14.sp) },
            placeholder = { Text("例如: 1.0.0", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFDC2626),
                cursorColor = Color(0xFFDC2626)
            ),
            enabled = !versionLoading
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    try {
                        val r = AuroraApi.adminSaveTrialVersionSettings(minVersion.trim(), lockVersion.trim())
                        if (r.success) {
                            Toast.makeText(ctx, "版本锁定设置已保存", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "保存失败: ${r.message}", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "网络错误", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
            enabled = !versionLoading
        ) {
            Text("保存版本锁定设置", fontSize = 14.sp, color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        CardKeyListContent(category = "trial", title = "体验卡密列表", isReward = false)
    }
}
@Composable
private fun CardKeyListContent(
    category: String,
    title: String,
    isReward: Boolean
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cardKeys by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showGenDialog by remember { mutableStateOf(false) }
    fun reload() {
        scope.launch {
            try {
                val kr = AuroraApi.adminListCardKeys(category)
                if (kr.success && kr.data != null) cardKeys = kr.data.optJSONArray("keys")
            } catch (_: Exception) {}
        }
    }
    LaunchedEffect(Unit) {
        loading = true
        try {
            val kr = AuroraApi.adminListCardKeys(category)
            if (kr.success && kr.data != null) cardKeys = kr.data.optJSONArray("keys")
        } catch (_: Exception) {}
        loading = false
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).padding(16.dp)
    ) {
        // 生成卡密
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("卡密列表", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Button(
                onClick = { showGenDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("+ 生成", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        } else if (cardKeys == null || cardKeys!!.length() == 0) {
            Text(if (isReward) "暂无奖励卡密" else "暂无体验卡密", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
        } else {
            cardKeys!!.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val keyStr = obj.optString("key_str", "")
                    val durationMin = obj.optInt("duration_minutes", obj.optInt("duration_hours", 0) * 60)
                    val activatedAt = obj.optLong("activated_at", 0)
                    val expiresAt = obj.optLong("expires_at", 0)
                    val isActive = obj.optInt("is_active", 1)
                    val userID = obj.optLong("used_by_user_id", 0)
                    val userName = obj.optString("user_name", "")
                    val status = when {
                        isActive == 0 -> "已失效"
                        activatedAt > 0 && expiresAt > 0 && System.currentTimeMillis()/1000 > expiresAt -> "已过期"
                        activatedAt > 0 && userName.isNotEmpty() -> "使用中 ($userName)"
                        activatedAt > 0 -> "使用中 (ID:$userID)"
                        else -> "未激活"
                    }
                    val statusColor = when {
                        status.contains("已失效") || status.contains("已过期") -> Color(0xFFDC2626)
                        status.contains("使用中") -> Color(0xFF059669)
                        else -> com.aurora.chat.ui.theme.appTextHint()
                    }
                    val durationText = if (durationMin <= 0) "永久" else when {
                        durationMin < 60 -> "${durationMin}分钟"
                        durationMin % 60 == 0 -> "${durationMin / 60}小时"
                        else -> "${durationMin / 60}小时${durationMin % 60}分钟"
                    }
                    // 类型/奖励展示（后端尚未返回这些字段时 typeText 为空，自动隐藏，不崩溃）
                    val cardTypeStr = obj.optString("card_type", "")
                    val rewardTypeStr = obj.optString("reward_type", "")
                    val tokenAmt = obj.optLong("token_amount", 0)
                    val bindUid = obj.optLong("bind_user_id", 0)
                    val typeText = buildString {
                        if (cardTypeStr == "personal") append("个人")
                        else if (cardTypeStr == "public") append("公共")
                        if (rewardTypeStr == "token") append(" · Token ${if (tokenAmt > 0) tokenAmt else ""}")
                        if (cardTypeStr == "personal" && bindUid > 0) append(" (ID:$bindUid)")
                    }
                    var showMenu by remember { mutableStateOf(false) }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { showMenu = true },
                        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg()),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    keyStr, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1E40AF),
                                    modifier = Modifier.clickable {
                                        copyToClipboard(ctx, keyStr, "卡密已复制: $keyStr")
                                    }
                                )
                                Text(status, fontSize = 12.sp, color = statusColor)
                            }
                            Text("$durationText  |  生成: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(obj.optLong("created_at", 0)*1000))}",
                                fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            if (typeText.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(typeText, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            }
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (userID > 0) {
                                DropdownMenuItem(
                                    text = { Text("取消使用", color = Color(0xFF1E40AF)) },
                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            try {
                                                val r = AuroraApi.adminCancelCardKey(obj.optLong("id"))
                                                if (r.success) {
                                                    Toast.makeText(ctx, "已取消使用", Toast.LENGTH_SHORT).show()
                                                    reload()
                                                }
                                            } catch (_: Exception) { Toast.makeText(ctx, "操作失败", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("删除", color = Color(0xFFDC2626)) },
                                onClick = {
                                    showMenu = false
                                    scope.launch {
                                        try {
                                            val r = AuroraApi.adminDeleteCardKey(obj.optLong("id"))
                                            if (r.success) {
                                                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
                                                reload()
                                            }
                                        } catch (_: Exception) { Toast.makeText(ctx, "操作失败", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            )
                            if (userID > 0) {
                                DropdownMenuItem(
                                    text = { Text("拉黑用户", color = Color(0xFFDC2626)) },
                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            try {
                                                val r = AuroraApi.adminBanUser(userID, 0L, "滥用卡密", userName.ifEmpty { "使用卡密的用户" })
                                                if (r.success) {
                                                    try {
                                                        AuroraApi.adminCancelCardKey(obj.optLong("id"))
                                                        reload()
                                                    } catch (_: Exception) {}
                                                    Toast.makeText(ctx, "用户已拉黑并取消卡密绑定，卡密可重新被他人使用", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(ctx, "拉黑失败: ${r.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (_: Exception) { Toast.makeText(ctx, "操作失败", Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // 生成卡密弹窗（体验 / 奖励 两套，互不影响、数据隔离）
    if (showGenDialog) {
        if (isReward) RewardGenerateDialog(onDismiss = { showGenDialog = false }) { reload() }
        else TrialGenerateDialog(onDismiss = { showGenDialog = false }) { reload() }
    }
}
/** 体验卡密生成弹窗（仅有效期，对应 category=trial） */
@Composable
private fun TrialGenerateDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedMinutes by remember { mutableStateOf(60) }
    var customMode by remember { mutableStateOf(false) }
    var customMinText by remember { mutableStateOf("") }
    val presets = listOf(5 to "5分钟", 30 to "30分钟", 60 to "1小时", 360 to "6小时", 720 to "12小时", 1440 to "24小时")
    val customMin = customMinText.toIntOrNull()
    val confirmEnabled = if (customMode) customMin in 1..1440 else true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成体验卡密", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("有效期（5分钟 ~ 24小时）", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (min, label) ->
                        val selected = !customMode && selectedMinutes == min
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                            .clickable { selectedMinutes = min; customMode = false }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(label, fontSize = 12.sp, color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextSecondary())
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自定义", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = if (customMode) customMinText else "", onValueChange = { customMinText = it.filter { c -> c.isDigit() }; customMode = true }, placeholder = { Text("分钟", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.width(100.dp), textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                }
                if (customMode && customMinText.isNotEmpty()) {
                    val min = customMinText.toIntOrNull()
                    if (min != null && (min < 1 || min > 1440)) {
                        Spacer(Modifier.height(4.dp))
                        Text("范围 1 ~ 1440 分钟", fontSize = 11.sp, color = Color(0xFFDC2626))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = {
                val finalMin = if (customMode) customMin ?: return@TextButton else selectedMinutes
                scope.launch {
                    try {
                        val r = AuroraApi.adminGenerateCardKey(category = "trial", durationMinutes = finalMin)
                        if (r.success) {
                            Toast.makeText(ctx, "体验卡密生成成功", Toast.LENGTH_SHORT).show()
                            onDone()
                        } else Toast.makeText(ctx, "生成失败: ${r.message}", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(ctx, "网络错误", Toast.LENGTH_SHORT).show() }
                }
            }) { Text("生成", color = Color(0xFF1E40AF), modifier = Modifier.padding(vertical = 4.dp)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) } }
    )
}
/** 奖励卡密生成弹窗（个人/公共 + Token，对应 category=reward；会员体系已移除） */
@Composable
private fun RewardGenerateDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cardType by remember { mutableStateOf("public") }
    var bindUserIdText by remember { mutableStateOf("") }
    var rewardType by remember { mutableStateOf("token") }
    var tokenAmountText by remember { mutableStateOf("") }
    val bindId = bindUserIdText.toLongOrNull()
    val tokenAmount = tokenAmountText.toLongOrNull()
    val confirmEnabled = (cardType != "personal" || (bindId != null && bindId > 0))
        && (rewardType != "token" || (tokenAmount != null && tokenAmount > 0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生成奖励卡密", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("卡密类型", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenTypeChip("公共卡密", "谁都能用", cardType == "public") { cardType = "public" }
                    GenTypeChip("个人卡密", "需绑定账号ID", cardType == "personal") { cardType = "personal" }
                }
                if (cardType == "personal") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = bindUserIdText, onValueChange = { bindUserIdText = it.filter { c -> c.isDigit() } }, placeholder = { Text("填写该账号的 ID", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                    Text("个人卡密仅限该账号使用，他人无法激活", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
                Spacer(Modifier.height(16.dp))
                Text("奖励类型", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(8.dp))
                GenTypeChip("Token", "发放额度", true) {}
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = tokenAmountText, onValueChange = { tokenAmountText = it.filter { c -> c.isDigit() } }, placeholder = { Text("Token 数量", fontSize = 13.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
            }
        },
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = {
                scope.launch {
                    try {
                        val r = AuroraApi.adminGenerateCardKey(
                            category = "reward",
                            cardType = cardType,
                            rewardType = rewardType,
                            tokenAmount = tokenAmount ?: 0,
                            bindUserId = bindId ?: 0
                        )
                        if (r.success) {
                            Toast.makeText(ctx, "奖励卡密生成成功", Toast.LENGTH_SHORT).show()
                            onDone()
                        } else Toast.makeText(ctx, "生成失败: ${r.message}", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { Toast.makeText(ctx, "网络错误", Toast.LENGTH_SHORT).show() }
                }
            }) { Text("生成", color = Color(0xFF1E40AF), modifier = Modifier.padding(vertical = 4.dp)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) } }
    )
}
/** 生成卡密弹窗里的可选标签（卡密类型 / 奖励类型通用） */
@Composable
private fun GenTypeChip(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column {
            Text(title, fontSize = 13.sp, color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextSecondary(), fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 10.sp,
                    color = if (selected) Color.White.copy(alpha = 0.85f) else com.aurora.chat.ui.theme.appTextHint())
            }
        }
    }
}
// ==================== 占位结果 ====================
@Composable
private fun PlaceholderContent(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextHint())
    }
}
// ==================== 用户位置弹窗 ====================
// ==================== 用户位置历史记录（独立页面） ====================
@Composable
private fun UserLocationHistoryScreen(
    userId: Long,
    userName: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf("") }
    LaunchedEffect(userId) {
        loading = true
        try {
            val apiResult = com.aurora.chat.data.api.AuroraApi.adminGetUserLocations(userId)
            if (apiResult.success && apiResult.data != null) {
                val arr = apiResult.data.optJSONArray("records")
                if (arr != null && arr.length() > 0) {
                    val list = mutableListOf<org.json.JSONObject>()
                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    records = list
                } else {
                    errorMsg = "该用户暂无位置记录"
                }
            } else {
                errorMsg = apiResult.message
            }
        } catch (e: Exception) {
            errorMsg = "查询失败: ${e.localizedMessage}"
        }
        loading = false
    }
    val dateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()) }
    Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface()).statusBarsPadding()) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appSurface()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E40AF),
                modifier = Modifier.clickable { onBack() }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${userName} 的位置历史",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "共 ${records.size} 条",
                fontSize = 13.sp,
                color = com.aurora.chat.ui.theme.appTextHint()
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            errorMsg.isNotEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorMsg, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        Spacer(Modifier.height(8.dp))
                        Text("用户授权位置权限并启动应用后将自动记录", fontSize = 13.sp, color = Color(0xFFD1D5DB))
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(records, key = { it.optLong("id") }) { record ->
                        val lat = record.optDouble("lat", 0.0)
                        val lng = record.optDouble("lng", 0.0)
                        val address = record.optString("address", "")
                        val createdAt = record.optLong("created_at", 0)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSurface()),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                // 时间
                                Text(
                                    text = dateFormat.format(java.util.Date(createdAt * 1000)),
                                    fontSize = 12.sp,
                                    color = com.aurora.chat.ui.theme.appTextHint()
                                )
                                Spacer(Modifier.height(8.dp))
                                // 地址
                                if (address.isNotEmpty()) {
                                    Text(
                                        text = address,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                                        modifier = Modifier.clickable {
                                            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("地址", address))
                                            android.widget.Toast.makeText(ctx, "地址已复制", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                // 经纬度
                                val latLngText = "$lat, $lng"
                                Text(
                                    text = "经纬度: $latLngText",
                                    fontSize = 12.sp,
                                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                                    modifier = Modifier.clickable {
                                        val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("经纬度", latLngText))
                                        android.widget.Toast.makeText(ctx, "经纬度已复制", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                )
                                // 打开地图按钮
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val geoUri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                                            val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, geoUri)
                                            ctx.startActivity(android.content.Intent.createChooser(mapIntent, "选择地图应用"))
                                        } catch (_: Exception) {
                                            android.widget.Toast.makeText(ctx, "没有可用的地图应用", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1E40AF)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("在地图中查看", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
// 从位置文本中提取地址
private fun extractAddressFromLocationText(text: String): String {
    try {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val addrIdx = lines.indexOfFirst { it == "\u2501\u2501\u2501" }
        val endIdx = lines.indexOfLast { it == "\u2501\u2501\u2501" }
        if (addrIdx < 0 || endIdx <= addrIdx) return text
        val addrLine = lines.getOrNull(addrIdx + 1) ?: ""
        return if (addrLine.startsWith("\u6211\u5728\uff1a")) {
            val nextLine = lines.getOrNull(addrIdx + 2) ?: ""
            if (nextLine.isNotEmpty() && !nextLine.startsWith("\u2501\u2501\u2501") && !nextLine.startsWith("\u6765\u81ea")) nextLine
            else addrLine.removePrefix("\u6211\u5728\uff1a").trim()
        } else addrLine
    } catch (_: Exception) { return text }
}
// ==================== 管理Tab：操作日志 ====================
@Composable
private fun AdminLogContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedId by remember { mutableStateOf(0L) }
    var filterAdminId by remember { mutableStateOf(0L) }
    var adminList by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var revertConfirmId by remember { mutableStateOf(0L) }
    var isReverting by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    fun loadLogs() {
        scope.launch {
            isLoading = true
            try {
                val r = AuroraApi.getAdminLogs(limit = 100, offset = 0, adminUserId = filterAdminId)
                if (r.success && r.data != null) {
                    val arr = r.data.optJSONArray("logs") ?: org.json.JSONArray()
                    val l = mutableListOf<org.json.JSONObject>()
                    for (i in 0 until arr.length()) l.add(arr.getJSONObject(i))
                    logs = l
                    total = r.data.optInt("total", 0)
                }
                val adminResult = AuroraApi.listPlatformAdmins()
                if (adminResult.success && adminResult.data != null) {
                    val a = mutableListOf<org.json.JSONObject>()
                    for (i in 0 until adminResult.data.length()) a.add(adminResult.data.getJSONObject(i))
                    adminList = a
                }
            } catch (_: Exception) { }
            isLoading = false
        }
    }
    LaunchedEffect(filterAdminId, refreshKey) { loadLogs() }
    Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        // 筛选行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("管理员操作日志", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            val isOwner = com.aurora.chat.data.api.AuroraApi.currentUserId == 1L
            if (total > 0 && isOwner) {
                Spacer(Modifier.width(12.dp))
                Text(
                    if (isClearing) "清空中..." else "清空",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isClearing) com.aurora.chat.ui.theme.appTextHint() else Color(0xFFDC2626),
                    modifier = Modifier.clickable(enabled = !isClearing) { showClearConfirm = true }
                )
            }
            Spacer(Modifier.weight(1f))
            Text(if (total > 0) "共 $total 条" else "", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()) }
        } else if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无操作日志", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Spacer(Modifier.height(4.dp))
                    Text("管理员执行封禁/禁言操作后将在此处记录", fontSize = 13.sp, color = Color(0xFFD1D5DB))
                }
            }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item { Spacer(Modifier.height(0.dp)) }
                items(logs, key = { it.optLong("id") }) { log ->
                    val logId = log.optLong("id")
                    val adminName = log.optString("admin_username", "未知")
                    val opType = log.optString("operation_type", "")
                    val targetName = log.optString("target_username", "未知用户")
                    val createdAt = log.optLong("created_at", 0)
                    val detailsStr = log.optString("details", "{}")
                    var detailsObj by remember(logId, detailsStr) {
                        mutableStateOf(try { org.json.JSONObject(detailsStr) } catch (_: Exception) { org.json.JSONObject() })
                    }
                    val isExpanded = expandedId == logId
                    val dateStr = remember(createdAt) {
                        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                        sdf.format(java.util.Date(createdAt * 1000))
                    }
                    val opLabel = when (opType) {
                        "ban" -> "封禁"
                        "unban" -> "解封"
                        "mute" -> "禁言"
                        "unmute" -> "解除禁言"
                        else -> opType
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isExpanded) com.aurora.chat.ui.theme.appSelectedBg() else com.aurora.chat.ui.theme.appCardBg())
                            .clickable { expandedId = if (isExpanded) 0L else logId }
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$adminName $opLabel 了 $targetName", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
                                Text(dateStr, fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                            if (isExpanded) {
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                                Spacer(Modifier.height(8.dp))
                                // 展开详情
                                Text("操作类型: $opLabel", fontSize = 13.sp, color = Color(0xFF4B5563))
                                Text("管理员: $adminName (ID: ${log.optLong("admin_user_id")})", fontSize = 13.sp, color = Color(0xFF4B5563))
                                Text("目标用户: $targetName (ID: ${log.optLong("target_user_id")})", fontSize = 13.sp, color = Color(0xFF4B5563))
                                // 根据操作类型显示详情
                                when (opType) {
                                    "ban" -> {
                                        val reason = detailsObj.optString("reason", "无")
                                        val dur = detailsObj.optLong("duration", 0)
                                        val expires = detailsObj.optLong("expires_at", 0)
                                        val expireStr = if (expires > 0) {
                                            val sf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                            sf.format(java.util.Date(expires * 1000))
                                        } else "永久"
                                        val durStr = formatDurationForLog(dur)
                                        Text("封禁原因: $reason", fontSize = 13.sp, color = Color(0xFF4B5563))
                                        Text("封禁时长: $durStr", fontSize = 13.sp, color = Color(0xFF4B5563))
                                        Text("到期时间: $expireStr", fontSize = 13.sp, color = Color(0xFF4B5563))
                                    }
                                    "mute" -> {
                                        val mt = detailsObj.optInt("mute_type", 0)
                                        val mtLabel = if (mt == 1) "社区禁言" else if (mt == 2) "对话禁言" else if (mt == 4) "短视频禁言" else "全部禁言"
                                        val dur = detailsObj.optLong("duration", 0)
                                        val expires = detailsObj.optLong("expires_at", 0)
                                        val expireStr = if (expires > 0) {
                                            val sf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                            sf.format(java.util.Date(expires * 1000))
                                        } else "永久"
                                        val durStr = formatDurationForLog(dur)
                                        Text("禁言类型: $mtLabel", fontSize = 13.sp, color = Color(0xFF4B5563))
                                        Text("禁言时长: $durStr", fontSize = 13.sp, color = Color(0xFF4B5563))
                                        Text("到期时间: $expireStr", fontSize = 13.sp, color = Color(0xFF4B5563))
                                    }
                                    "unban" -> {
                                        Text("操作说明: 解除封禁", fontSize = 13.sp, color = Color(0xFF4B5563))
                                    }
                                    "unmute" -> {
                                        Text("操作说明: 解除禁言", fontSize = 13.sp, color = Color(0xFF4B5563))
                                    }
                                }
                                // 撤回按钮（仅封禁/禁言操作可撤回）
                                if (opType == "ban" || opType == "mute") {
                                    Spacer(Modifier.height(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFFEF2F2))
                                            .clickable { revertConfirmId = logId }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            if (isReverting && revertConfirmId == logId) "撤回中..." else "撤回该操作",
                                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // ── 撤回确认弹窗 ──
    if (revertConfirmId > 0) {
        val targetLog = logs.find { it.optLong("id") == revertConfirmId }
        val opName = targetLog?.let {
            when (it.optString("operation_type", "")) { "ban" -> "封禁"; "mute" -> "禁言"; else -> "操作" }
        } ?: "操作"
        val targetUser = targetLog?.optString("target_username", "该用户") ?: "该用户"
        AlertDialog(
            onDismissRequest = { revertConfirmId = 0 },
            title = { Text("确认撤回", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
            text = {
                Column {
                    Text("确定要撤回这条 $opName 操作吗？", fontSize = 15.sp, color = Color(0xFF4B5563))
                    Spacer(Modifier.height(8.dp))
                    Text("撤回后 $targetUser 将立即恢复使用", fontSize = 14.sp, color = Color(0xFFDC2626))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    isReverting = true
                    scope.launch {
                        try {
                            val r = AuroraApi.revertAdminOperation(revertConfirmId)
                            withContext(Dispatchers.Main) {
                                if (r.success) {
                                    android.widget.Toast.makeText(ctx, "已撤回该${opName}，用户已恢复", android.widget.Toast.LENGTH_SHORT).show()
                                    revertConfirmId = 0
                                    refreshKey++ // 刷新列表
                                } else {
                                    android.widget.Toast.makeText(ctx, "撤回失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(ctx, "撤回失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } finally { isReverting = false }
                    }
                }) {
                    Text("确认撤回", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { revertConfirmId = 0 }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }
    // ── 清空日志确认弹窗 ──
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { if (!isClearing) showClearConfirm = false },
            title = { Text("确认清空", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = com.aurora.chat.ui.theme.appTextPrimary()) },
            text = {
                Column {
                    Text("确定要清空所有管理员操作日志吗？", fontSize = 15.sp, color = Color(0xFF4B5563))
                    Spacer(Modifier.height(8.dp))
                    Text("此操作不可恢复", fontSize = 14.sp, color = Color(0xFFDC2626))
                    Spacer(Modifier.height(8.dp))
                    Text("共 $total 条日志将被清空", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    isClearing = true
                    scope.launch {
                        try {
                            val r = com.aurora.chat.data.api.AuroraApi.clearAdminLogs()
                            withContext(Dispatchers.Main) {
                                if (r.success) {
                                    android.widget.Toast.makeText(ctx, "已清空所有操作日志", android.widget.Toast.LENGTH_SHORT).show()
                                    showClearConfirm = false
                                    refreshKey++ // 刷新列表
                                } else {
                                    android.widget.Toast.makeText(ctx, "清空失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(ctx, "清空失败: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } finally { isClearing = false }
                    }
                }) {
                    Text("确认清空", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        )
    }
}
private fun formatDurationForLog(seconds: Long): String {
    if (seconds <= 0) return "永久"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val mins = (seconds % 3600) / 60
    return when {
        days > 365 -> "${days / 365}年${if (days % 365 > 0) "${days % 365}天" else ""}"
        days > 0 -> "${days}天${if (hours > 0) "${hours}小时" else ""}"
        hours > 0 -> "${hours}小时${if (mins > 0) "${mins}分钟" else ""}"
        else -> "${mins}分钟"
    }
}
// 从位置文本中提取经纬度
private fun extractLatLngFromLocationText(text: String): Pair<Double, Double>? {
    try {
        val lines = text.split("\n")
        var lat: Double? = null
        var lng: Double? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("location_lat=")) lat = trimmed.removePrefix("location_lat=").trim().toDoubleOrNull()
            if (trimmed.startsWith("location_lng=")) lng = trimmed.removePrefix("location_lng=").trim().toDoubleOrNull()
        }
        if (lat != null && lng != null) return Pair(lat, lng)
        return null
    } catch (_: Exception) { return null }
}
@Composable
private fun InfoRowDMS(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
    }
}
/** 带点击复制功能的行，点击整行复制 value */
@Composable
private fun ClickRow(label: String, value: String, onCopy: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onCopy(value) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.width(80.dp))
        Text(
            value, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = com.aurora.chat.ui.theme.appTextPrimary(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
// ═════════════════════════════════════════════════════
// 细粒度权限编辑器
// ═════════════════════════════════════════════════════
@Composable
private fun PermissionEditorPage(
    user: UserInfo,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var selectedPerms by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    // 加载当前权限
    LaunchedEffect(user.id) {
        try {
            val result = AuroraApi.getUserPermissions(user.id)
            if (result.success && result.data != null) {
                selectedPerms = (0 until result.data.length()).map { result.data.getString(it) }
            }
        } catch (_: Exception) {}
        loading = false
    }
    AnimatedVisibility(
        visible = true,
        enter = slideInHorizontally(tween(300)) { it },
        exit = slideOutHorizontally(tween(300)) { it }
    ) {
        Box(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF1E40AF))
                }
            } else {
                Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
                    // 顶栏
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("←", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable { onClose() }.padding(end = 12.dp))
                        Text("权限管理 — ${user.username}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        // 十大类 + 各子权限（所有大类默认展开，便于查找未授权的子权限）
                        val categoryData = listOf(
                            "users" to "用户管理" to listOf(
                                "users.view_profile" to "查看资料", "users.messages" to "消息查看",
                                "users.ban" to "封禁", "users.mute" to "禁言",
                                "users.kick" to "踢出", "users.location" to "位置查看",
                                "users.badges_view" to "徽章管理", "users.delete" to "删除账号"
                            ),
                            "announcement" to "公告管理" to listOf(
                                "announcement.view" to "查看公告", "announcement.edit" to "编辑公告",
                                "announcement.publish" to "发布公告", "announcement.delete" to "删除公告"
                            ),
                            "card_keys" to "卡密管理" to listOf(
                                "card_keys.view" to "查看卡密", "card_keys.generate" to "生成卡密",
                                "card_keys.cancel" to "作废卡密", "card_keys.delete" to "删除卡密"
                            ),
                            "groups" to "群聊管理" to listOf(
                                "groups.view" to "查看群聊", "groups.edit" to "修改群资料",
                                "groups.dissolve" to "解散群聊", "groups.msg" to "群消息管理"
                            ),
                            "kick" to "踢出管理" to listOf(
                                "kick.single" to "踢出单个用户", "kick.all" to "踢出全部用户"
                            ),
                            "trial" to "体验模式" to listOf(
                                "trial.view" to "查看设置", "trial.toggle" to "开关模式",
                                "trial.card_keys" to "管理体验卡密"
                            ),
                            "admin" to "管理员" to listOf(
                                "admin.grant" to "授权管理员", "admin.revoke" to "撤销管理员",
                                "admin.logs" to "操作日志"
                            ),
                            "info" to "信息统计" to listOf(
                                "admin.stats" to "查看统计信息"
                            ),
                            "community" to "社区" to listOf(
                                "community.delete_resource" to "删除资源",
                                "community.delete_post" to "删除帖子"
                            ),
                            "free_apis" to "免费 API" to listOf(
                                "free_apis.manage" to "API 审核"
                            ),
                            "paid_apis" to "付费 API" to listOf(
                                "paid_apis.manage" to "付费 API 审核"
                            ),
                            "plugin" to "插件" to listOf(
                                "plugin.review" to "插件审核"
                            )
                        )
                        // 为每个大类创建独立的展开状态，默认全展开
                        val expandedCategories = remember { mutableStateMapOf<String, Boolean>().apply {
                            categoryData.forEach { (cat, _) -> this[cat.first] = true }
                        } }
                        categoryData.forEach { (cat, subs) ->
                            val (key, label) = cat
                            val subKeys = subs.map { it.first }
                            // 大类勾选状态 = 大类 key 存在，或任意子权限被勾选（后端只返回子权限 key）
                            val anySubChecked = subKeys.any { selectedPerms.contains(it) }
                            val isChecked = selectedPerms.contains(key) || anySubChecked
                            // 展开状态独立于勾选状态，点击箭头可折叠/展开
                            val isExpanded = expandedCategories[key] ?: true
                            // 点击大类 = 批量勾选/取消该大类下全部子权限（不再写入纯大类 key）
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable {
                                selectedPerms = if (anySubChecked) selectedPerms - subKeys else selectedPerms + subKeys
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(if (isChecked) Color(0xFF1E40AF) else Color(0xFFD1D5DB)).padding(2.dp)) {
                                    if (isChecked) Text("✓", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                                Spacer(Modifier.weight(1f))
                                // 展开/折叠箭头（独立于勾选）
                                Text(if (isExpanded) "▼" else "▶", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(),
                                    modifier = Modifier.clickable { expandedCategories[key] = !isExpanded }.padding(4.dp))
                            }
                            if (isExpanded) {
                                Spacer(Modifier.height(4.dp))
                                subs.forEach { (subKey, subLabel) ->
                                    val isSubChecked = selectedPerms.contains(subKey)
                                    Row(Modifier.fillMaxWidth().padding(start = 40.dp).padding(vertical = 4.dp).clickable { selectedPerms = if (isSubChecked) selectedPerms - subKey else selectedPerms + subKey }, verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(if (isSubChecked) Color(0xFF1E40AF) else Color(0xFFD1D5DB)).padding(2.dp)) {
                                            if (isSubChecked) Text("✓", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(subLabel, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    // 保存按钮
                    Button(
                        onClick = {
                            saving = true
                            scope.launch {
                                // 仅保存真实子权限 key，剔除纯大类 key（如 "card_keys"），避免污染授权记录
                                val allCategoryKeys = listOf(
                                    "users", "announcement", "card_keys", "groups",
                                    "kick", "trial", "admin", "community",
                                    "free_apis", "plugin"
                                )
                                val permsToSave = selectedPerms.filter { it !in allCategoryKeys }
                                val result = AuroraApi.saveUserPermissions(user.id, permsToSave)
                                saving = false
                                if (result.success) {
                                    Toast.makeText(ctx, "权限已保存", Toast.LENGTH_SHORT).show()
                                    onClose()
                                } else {
                                    Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                        enabled = !saving
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("保存权限", color = Color.White, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}
// ==================== 余额管理 ====================
// 按范围批量调整用户 Token 余额：范围=按用户 ID/全部/ID区块，操作=加/扣/清空
@Composable
private fun BalanceManagementContent() {
    val scope = rememberCoroutineScope()
    var scopeMode by remember { mutableStateOf("user_ids") } // user_ids | all | range
    var opMode by remember { mutableStateOf("add") }         // add | deduct | reset
    var userIdsText by remember { mutableStateOf("") }
    var rangesText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scopeOptions = listOf("user_ids" to "按用户ID", "all" to "全部用户", "range" to "ID 区块")
    val opOptions = listOf("add" to "加余额", "deduct" to "扣余额", "reset" to "清空余额")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("批量调整用户 Token 余额", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.height(12.dp))
        Text("调整范围", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            scopeOptions.forEach { (key, label) ->
                val selected = scopeMode == key
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                        .clickable { scopeMode = key }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 13.sp, color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("操作类型", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            opOptions.forEach { (key, label) ->
                val selected = opMode == key
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                        .clickable { opMode = key }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 13.sp, color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (scopeMode == "user_ids") {
            OutlinedTextField(
                value = userIdsText,
                onValueChange = { userIdsText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '，' || ch == ' ' } },
                label = { Text("用户ID（多个用逗号/空格分隔）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
            )
        } else if (scopeMode == "range") {
            OutlinedTextField(
                value = rangesText, onValueChange = { rangesText = it },
                label = { Text("ID 区块（如 1-20, 30-40）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
            )
            Text("每个区间用逗号分隔，区间用「-」连接最小与最大 ID", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(top = 4.dp))
        } else {
            Text("将调整全部用户的余额，请谨慎操作。", fontSize = 13.sp, color = Color(0xFFDC2626))
        }
        Spacer(Modifier.height(14.dp))
        if (opMode != "reset") {
            OutlinedTextField(
                value = amountText, onValueChange = { amountText = it.filter { ch -> ch.isDigit() } },
                label = { Text(if (opMode == "add") "增加数量" else "扣除数量") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
            )
            Spacer(Modifier.height(14.dp))
        }
        OutlinedTextField(
            value = reason, onValueChange = { reason = it }, label = { Text("原因备注（可选）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val parsedIds = if (scopeMode == "user_ids")
                    userIdsText.split(Regex("[,，\\s]+")).mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
                else emptyList()
                if (scopeMode == "user_ids" && parsedIds.isEmpty()) { message = "请填写至少一个有效用户ID"; isError = true; return@Button }
                val parsedRanges = mutableListOf<Pair<Long, Long>>()
                if (scopeMode == "range") {
                    val parts = rangesText.split(Regex("[,，]")).map { it.trim() }.filter { it.isNotEmpty() }
                    for (p in parts) {
                        val seg = p.split("-")
                        if (seg.size == 2) {
                            val a = seg[0].toLongOrNull(); val b = seg[1].toLongOrNull()
                            if (a != null && b != null) parsedRanges.add(a to b)
                        }
                    }
                    if (parsedRanges.isEmpty()) { message = "请填写有效的 ID 区块"; isError = true; return@Button }
                }
                val amount = amountText.toLongOrNull() ?: 0L
                if (opMode != "reset" && amount <= 0L) { message = "调整数量必须大于 0"; isError = true; return@Button }
                scope.launch {
                    isSubmitting = true; message = null
                    val r = ChatRepository.adjustBalance(scopeMode, parsedIds, parsedRanges, opMode, amount, reason.trim())
                    isSubmitting = false
                    if (r.success) {
                        val affected = r.data?.optLong("affected", 0) ?: 0
                        message = when (opMode) {
                            "reset" -> "已清空余额，共影响 $affected 名用户"
                            "add" -> "已加余额，共影响 $affected 名用户"
                            else -> "已扣余额，共影响 $affected 名用户"
                        }
                        isError = false
                        userIdsText = ""; rangesText = ""; amountText = ""; reason = ""
                    } else { message = r.message; isError = true }
                }
            },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isSubmitting) "处理中…" else "执行调整", color = Color.White) }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message!!, fontSize = 13.sp, color = if (isError) Color(0xFFDC2626) else Color(0xFF16A34A))
        }
    }
}
// ==================== 通知中心（系统通知下发） ====================
// 开发者向用户发送系统通知：支持按用户 ID / 全部 / ID 区块
@Composable
private fun NotificationManagementContent() {
    val scope = rememberCoroutineScope()
    var scopeMode by remember { mutableStateOf("user") } // user | all | range
    var usernames by remember { mutableStateOf("") }
    var rangesText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scopeOptions = listOf("user" to "按用户 ID", "all" to "全部用户", "range" to "ID 区块")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("发送系统通知（通知中心）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.height(12.dp))
        Text("通知范围", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            scopeOptions.forEach { (key, label) ->
                val selected = scopeMode == key
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                        .clickable { scopeMode = key }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 13.sp, color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextSecondary())
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (scopeMode == "user") {
            OutlinedTextField(
                value = usernames, onValueChange = { usernames = it },
                label = { Text("用户 ID（多个用逗号/空格分隔）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
            )
        } else if (scopeMode == "range") {
            OutlinedTextField(
                value = rangesText, onValueChange = { rangesText = it },
                label = { Text("ID 区块（如 1-20, 30-40）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
            )
            Text("每个区间用逗号分隔，区间用「-」连接最小与最大 ID", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(top = 4.dp))
        } else {
            Text("将向全部用户发送通知，请谨慎操作。", fontSize = 13.sp, color = Color(0xFFDC2626))
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("通知标题") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = content, onValueChange = { content = it }, label = { Text("通知内容") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = extra, onValueChange = { extra = it }, label = { Text("底部补充信息（可选）") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFFD1D5DB))
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val finalTitle = title.trim()
                val finalContent = content.trim()
                if (finalContent.isEmpty()) { message = "通知内容不能为空"; isError = true; return@Button }
                val parsedUserIds = if (scopeMode == "user")
                    usernames.split(Regex("[,，\\s]+")).mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 } else emptyList()
                if (scopeMode == "user" && parsedUserIds.isEmpty()) { message = "请填写至少一个有效的用户 ID"; isError = true; return@Button }
                val parsedRanges = mutableListOf<Pair<Long, Long>>()
                if (scopeMode == "range") {
                    val parts = rangesText.split(Regex("[,，]")).map { it.trim() }.filter { it.isNotEmpty() }
                    for (p in parts) {
                        val seg = p.split("-")
                        if (seg.size == 2) {
                            val a = seg[0].toLongOrNull(); val b = seg[1].toLongOrNull()
                            if (a != null && b != null) parsedRanges.add(a to b)
                        }
                    }
                    if (parsedRanges.isEmpty()) { message = "请填写有效的 ID 区块"; isError = true; return@Button }
                }
                scope.launch {
                    isSubmitting = true; message = null
                    val r = ChatRepository.sendNotification(scopeMode, parsedUserIds, parsedRanges, finalTitle, finalContent, extra.trim())
                    isSubmitting = false
                    if (r.success) {
                        val sent = r.data?.optInt("sent", 0) ?: 0
                        message = "已发送，共送达 $sent 名用户"; isError = false
                        title = ""; content = ""; extra = ""; usernames = ""; rangesText = ""
                    } else { message = r.message; isError = true }
                }
            },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isSubmitting) "发送中…" else "发送通知", color = Color.White) }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message!!, fontSize = 13.sp, color = if (isError) Color(0xFFDC2626) else Color(0xFF16A34A))
        }
    }
}
// ==================== 免费 API 审核（用户贡献，开发者同意/拒绝） ====================
private data class FreeApiItem(
    val id: Long,
    val submitterId: Long,
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val status: String,
    val userName: String,
    val email: String,
    val lastLatency: Long = 0,
    val failCount: Int = 0,
    val markedDown: Boolean = false
)
@Composable
private fun FreeApiManageContent() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<FreeApiItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    fun loadList() {
        scope.launch {
            loading = true
            val res = AuroraApi.adminListFreeApis()
            if (res.success) {
                val arr = res.data?.optJSONArray("items")
                if (arr != null && arr.length() > 0) {
                    items = (0 until arr.length()).map { i ->
                        val o = arr.optJSONObject(i)!!
                        FreeApiItem(
                            id = o.optLong("id"),
                            submitterId = o.optLong("submitter_id"),
                            apiKey = o.optString("api_key"),
                            baseUrl = o.optString("base_url"),
                            modelName = o.optString("model_name"),
                            status = o.optString("status"),
                            userName = o.optString("user_name"),
                            email = o.optString("email"),
                            lastLatency = o.optLong("last_latency"),
                            failCount = o.optInt("fail_count"),
                            markedDown = o.optInt("marked_down") == 1
                        )
                    }
                } else {
                    items = emptyList()
                }
            } else {
                toast = res.message
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { loadList() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("免费 API 提交", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(12.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF1E40AF))
                }
            } else if (items.isEmpty()) {
                Text(if (toast.isNotEmpty()) toast else "暂无提交", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
            } else {
                items.forEach { item ->
                    val statusText = when (item.status) {
                        "approved" -> "已通过"
                        "rejected" -> "已拒绝"
                        "takedown" -> "已下架"
                        else -> "待审核"
                    }
                    val statusColor = when (item.status) {
                        "approved" -> Color(0xFF16A34A)
                        "rejected" -> Color(0xFFDC2626)
                        "takedown" -> com.aurora.chat.ui.theme.appTextSecondary()
                        else -> Color(0xFFB45309)
                    }
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg()),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.modelName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "提交者: ${if (item.userName.isNotEmpty()) item.userName else "ID ${item.submitterId}"}${if (item.email.isNotEmpty()) " (${item.email})" else ""}",
                                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                            )
                            Text("URL: ${item.baseUrl}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Text("Key: ${item.apiKey}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("状态: $statusText", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = statusColor)
                                if (item.markedDown) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("● 已挂（连续失败 ${item.failCount} 次）", fontSize = 12.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Medium)
                                } else if (item.lastLatency > 0) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("延迟 ${item.lastLatency}ms", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                }
                                Spacer(Modifier.weight(1f))
                                if (item.status == "pending") {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val r = AuroraApi.adminReviewFreeApi(item.id, "approve")
                                                toast = if (r.success) "已通过" else r.message
                                                loadList()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("同意", fontSize = 12.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val r = AuroraApi.adminReviewFreeApi(item.id, "reject")
                                                toast = if (r.success) "已拒绝" else r.message
                                                loadList()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("拒绝", fontSize = 12.sp) }
                                } else if (item.status == "approved") {
                                    if (item.markedDown) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    val r = AuroraApi.adminClearFreeApiMark(item.id)
                                                    toast = if (r.success) "已清除标记" else r.message
                                                    loadList()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                            shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                        ) { Text("清标记", fontSize = 12.sp) }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val r = AuroraApi.adminTakedownFreeApi(item.id)
                                                toast = if (r.success) "已下架" else r.message
                                                loadList()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = com.aurora.chat.ui.theme.appTextSecondary()),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("下架", fontSize = 12.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = { pendingDeleteId = item.id },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("删除", fontSize = 12.sp) }
                                } else if (item.status == "takedown" || item.status == "rejected") {
                                    // 已下架/已拒绝:仅可删除,清理列表
                                    Button(
                                        onClick = { pendingDeleteId = item.id },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("删除", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 删除免费 API 记录确认弹窗
        pendingDeleteId?.let { pid ->
            val target = items.firstOrNull { it.id == pid }
            AlertDialog(
                onDismissRequest = { pendingDeleteId = null },
                title = { Text("删除记录", fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                text = {
                    Text(
                        "确定删除这条免费 API 记录吗?\n\nURL: ${target?.baseUrl ?: ""}\n删除后该记录将从列表永久消失,不可恢复。",
                        fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            val r = AuroraApi.adminDeleteFreeApi(pid)
                            toast = if (r.success) "已删除" else r.message
                            pendingDeleteId = null
                            loadList()
                        }
                    }) { Text("删除", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteId = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                },
                containerColor = com.aurora.chat.ui.theme.appSurface()
            )
        }
    }
}

// ==================== 付费 API 审核 ====================
private data class PaidApiItem(
    val id: Long,
    val submitterId: Long,
    val apiKey: String,
    val baseUrl: String,
    val modelName: String,
    val inputPrice: String,
    val outputPrice: String,
    val priceNote: String,
    val status: String,
    val userName: String,
    val email: String
)
@Composable
private fun PaidApiManageContent() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<PaidApiItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    fun loadList() {
        scope.launch {
            loading = true
            val res = AuroraApi.adminListPaidApis()
            if (res.success) {
                val arr = res.data?.optJSONArray("items")
                if (arr != null && arr.length() > 0) {
                    items = (0 until arr.length()).map { i ->
                        val o = arr.optJSONObject(i)!!
                        PaidApiItem(
                            id = o.optLong("id"),
                            submitterId = o.optLong("submitter_id"),
                            apiKey = o.optString("api_key"),
                            baseUrl = o.optString("base_url"),
                            modelName = o.optString("model_name"),
                            inputPrice = o.optString("input_price"),
                            outputPrice = o.optString("output_price"),
                            priceNote = o.optString("price_note"),
                            status = o.optString("status"),
                            userName = o.optString("user_name"),
                            email = o.optString("email")
                        )
                    }
                } else {
                    items = emptyList()
                }
            } else {
                toast = res.message
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { loadList() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text("付费 API 提交", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(12.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF1E40AF))
                }
            } else if (items.isEmpty()) {
                Text(if (toast.isNotEmpty()) toast else "暂无提交", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
            } else {
                items.forEach { item ->
                    val statusText = when (item.status) {
                        "approved" -> "已通过"
                        "rejected" -> "已拒绝"
                        "takedown" -> "已下架"
                        else -> "待审核"
                    }
                    val statusColor = when (item.status) {
                        "approved" -> Color(0xFF16A34A)
                        "rejected" -> Color(0xFFDC2626)
                        "takedown" -> com.aurora.chat.ui.theme.appTextSecondary()
                        else -> Color(0xFFB45309)
                    }
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg()),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.modelName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "提交者: ${if (item.userName.isNotEmpty()) item.userName else "ID ${item.submitterId}"}${if (item.email.isNotEmpty()) " (${item.email})" else ""}",
                                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                            )
                            Text("URL: ${item.baseUrl}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Text("Key: ${item.apiKey}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "输入倍率 ${item.inputPrice} · 输出倍率 ${item.outputPrice}",
                                fontSize = 12.sp, color = Color(0xFFB45309), fontWeight = FontWeight.Medium
                            )
                            if (item.priceNote.isNotBlank()) {
                                Text("备注: ${item.priceNote}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("状态: $statusText", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = statusColor)
                                Spacer(Modifier.weight(1f))
                                if (item.status == "pending") {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val r = AuroraApi.adminReviewPaidApi(item.id, "approve")
                                                toast = if (r.success) "已通过" else r.message
                                                loadList()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("同意", fontSize = 12.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val r = AuroraApi.adminReviewPaidApi(item.id, "reject")
                                                toast = if (r.success) "已拒绝" else r.message
                                                loadList()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("拒绝", fontSize = 12.sp) }
                                } else if (item.status == "approved") {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val r = AuroraApi.adminTakedownPaidApi(item.id)
                                                toast = if (r.success) "已下架" else r.message
                                                loadList()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = com.aurora.chat.ui.theme.appTextSecondary()),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("下架", fontSize = 12.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = { pendingDeleteId = item.id },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("删除", fontSize = 12.sp) }
                                } else if (item.status == "takedown" || item.status == "rejected") {
                                    Button(
                                        onClick = { pendingDeleteId = item.id },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                        shape = RoundedCornerShape(6.dp), modifier = Modifier.height(30.dp)
                                    ) { Text("删除", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
        pendingDeleteId?.let { pid ->
            val target = items.firstOrNull { it.id == pid }
            AlertDialog(
                onDismissRequest = { pendingDeleteId = null },
                title = { Text("删除记录", fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
                text = {
                    Text(
                        "确定删除这条付费 API 记录吗?\n\nURL: ${target?.baseUrl ?: ""}\n删除后该记录将从列表永久消失,不可恢复。",
                        fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            val r = AuroraApi.adminDeletePaidApi(pid)
                            toast = if (r.success) "已删除" else r.message
                            pendingDeleteId = null
                            loadList()
                        }
                    }) { Text("删除", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteId = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
                },
                containerColor = com.aurora.chat.ui.theme.appSurface()
            )
        }
    }
}

// ==================== 站点卡密管理（后端持久化） ====================
private data class CardKeyItem2(
    val id: Long,
    val cardKey: String,
    val password: String,
    val domain: String,
    val permissionLevel: String = "B",
    val dangerQuota: Int = 0,
    val dangerUsed: Int = 0,
    val backupBalance: Int = 1
)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CardKeyManageContent() {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<CardKeyItem2>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showGenDialog by remember { mutableStateOf(false) }
    var menuIndex by remember { mutableStateOf(-1) }
    var toast by remember { mutableStateOf("") }
    var showPermDialog by remember { mutableStateOf<CardKeyItem2?>(null) }
    var showAddBalanceItem by remember { mutableStateOf<CardKeyItem2?>(null) }
    var showServerItem by remember { mutableStateOf<CardKeyItem2?>(null) }
    fun loadList() {
        scope.launch {
            loading = true
            val res = AuroraApi.adminListCardKeys("site")
            if (res.success) {
                val arr = res.data?.optJSONArray("keys")
                if (arr != null && arr.length() > 0) {
                    items = (0 until arr.length()).map { i ->
                        val o = arr.optJSONObject(i)!!
                        CardKeyItem2(
                id = o.optLong("id"),
                cardKey = o.optString("key_str"),
                password = o.optString("password"),
                domain = o.optString("domain"),
                permissionLevel = o.optString("permission_level", "B"),
                dangerQuota = o.optInt("danger_quota"),
                dangerUsed = o.optInt("danger_used"),
                backupBalance = o.optInt("backup_balance", 1)
            )
                    }
                } else {
                    items = emptyList()
                    toast = "暂无站点卡密"
                }
            } else {
                toast = res.message
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { loadList() }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("站点卡密列表", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Button(
                    onClick = { showGenDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("+ 生成", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF1E40AF))
            }
        } else if (items.isEmpty()) {
            Text("暂无站点卡密", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
        } else {
            items.forEachIndexed { index, item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuIndex = index },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ),
                    colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg()),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                Text(item.cardKey, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF))
                Spacer(Modifier.height(4.dp))
                Text("密码: ${item.password}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Text("域名: ${item.domain}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                val permText = when (item.permissionLevel) {
                    "A" -> "权限: A级(全部放行)"
                    "C" -> "权限: C级(仅站内)"
                    else -> "权限: B级(限制危险)"
                }
                Text(permText, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                if (item.dangerQuota > 0) {
                    Text("危险次数: ${item.dangerUsed}/${item.dangerQuota}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                }
                Text("备份余额: ${item.backupBalance}", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Text(
                    "服务器绑定 / 更换  >",
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showServerItem = item }
                        .padding(vertical = 2.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1E40AF)
                )
            }
            DropdownMenu(
                expanded = menuIndex == index,
                onDismissRequest = { menuIndex = -1 }
            ) {
                DropdownMenuItem(
                    text = { Text("权限", color = Color(0xFF1E40AF)) },
                    onClick = {
                        val target = item
                        menuIndex = -1
                        showPermDialog = target
                    }
                )
                DropdownMenuItem(
                    text = { Text("服务器绑定", color = Color(0xFF1E40AF)) },
                    onClick = {
                        val target = item
                        menuIndex = -1
                        showServerItem = target
                    }
                )
                DropdownMenuItem(
                    text = { Text("添加备份余额", color = Color(0xFF1E40AF)) },
                    onClick = {
                        val target = item
                        menuIndex = -1
                        showAddBalanceItem = target
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = Color(0xFFDC2626)) },
                    onClick = {
                        val target = item
                        menuIndex = -1
                        scope.launch {
                            val res = AuroraApi.adminDeleteCardKey(target.id)
                            if (res.success) {
                                toast = "已删除"
                                loadList()
                            } else {
                                toast = res.message
                            }
                        }
                    }
                )
            }
                }
            }
        }
        if (toast.isNotEmpty()) {
            Text(
                toast,
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                fontSize = 13.sp,
                color = Color(0xFF16A34A),
                textAlign = TextAlign.Center
            )
        }
    }
    if (showGenDialog) {
        CardKeyGenerateDialog2(
            onDismiss = { showGenDialog = false },
            onGenerate = { key, pwd, domain, permLevel, dangerQty ->
                scope.launch {
                    val res = AuroraApi.adminGenerateCardKey(
                        category = "site",
                        customKey = key,
                        domain = domain,
                        password = pwd,
                        permissionLevel = permLevel,
                        dangerQuota = dangerQty
                    )
                    if (res.success) {
                        toast = "生成成功"
                        loadList()
                    } else {
                        toast = res.message
                    }
                    showGenDialog = false
                }
            }
        )
    }
    showPermDialog?.let { item ->
        CardKeyPermissionDialog(
            item = item,
            onDismiss = { showPermDialog = null },
            onUpdate = { permLevel, dangerQty ->
                scope.launch {
                    val res = AuroraApi.adminUpdateCardKeyPermission(item.id, permLevel, dangerQty)
                    if (res.success) {
                        toast = "权限已更新"
                        loadList()
                    } else {
                        toast = res.message
                    }
                    showPermDialog = null
                }
            }
        )
    }
    showAddBalanceItem?.let { item ->
        CardKeyAddBalanceDialog(
            item = item,
            onDismiss = { showAddBalanceItem = null },
            onAdd = { amount ->
                scope.launch {
                    val res = AuroraApi.adminAddCardBackupBalance(item.id, amount)
                    if (res.success) {
                        toast = "已添加备份余额"
                        loadList()
                    } else {
                        toast = res.message
                    }
                    showAddBalanceItem = null
                }
            }
        )
    }
    showServerItem?.let { item ->
        SiteServerBindingDialog(
            item = item,
            onDismiss = { showServerItem = null },
            onChanged = { toast = it }
        )
    }
    }
}
@Composable
private fun SiteServerBindingDialog(
    item: CardKeyItem2,
    onDismiss: () -> Unit,
    onChanged: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val domain = remember(item.domain) { item.domain.split(",").firstOrNull()?.trim().orEmpty() }
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var loading by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf("") }
    var scheme by remember { mutableStateOf("http") }
    var httpPort by remember { mutableStateOf("3001") }
    var wsPort by remember { mutableStateOf("3003") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun load() {
        if (domain.isBlank()) {
            loading = false
            status = "该卡密未绑定域名，无法绑定服务器"
            return
        }
        scope.launch {
            loading = true
            val res = AuroraApi.adminGetSiteEndpoint(domain)
            if (res.success) {
                val d = res.data
                val bound = d?.optBoolean("bound", false) ?: false
                host = d?.optString("host", "") ?: ""
                scheme = d?.optString("scheme", "http")?.ifBlank { "http" } ?: "http"
                httpPort = (d?.optInt("http_port", 3001) ?: 3001).toString()
                wsPort = (d?.optInt("ws_port", 3003) ?: 3003).toString()
                token = d?.optString("token", "") ?: ""
                manual = d?.optBoolean("manual", false) ?: false
                val ts = d?.optLong("updated_at", 0) ?: 0
                val online = d?.optBoolean("online", false) ?: false
                val timeText = if (ts <= 0) "从未" else timeFmt.format(Date(ts * 1000))
                status = when {
                    !bound -> "该域名尚未上报服务器"
                    manual -> "手动绑定 · 直连可用"
                    online -> "在线 · 最后心跳 $timeText"
                    else -> "离线 · 最后心跳 $timeText"
                }
            } else {
                status = res.message
            }
            loading = false
        }
    }
    LaunchedEffect(domain) { load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.aurora.chat.ui.theme.appSurface(),
        title = { Text("服务器绑定", fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("域名: $domain", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(6.dp))
                if (loading) {
                    Text("读取中...", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                } else {
                    Text(status, fontSize = 12.sp, color = if (status.startsWith("在线")) Color(0xFF16A34A) else Color(0xFFDC2626))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器地址（域名或 IP）") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = scheme,
                    onValueChange = { scheme = it },
                    label = { Text("协议（http / https）") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = httpPort,
                        onValueChange = { httpPort = it.filter { c -> c.isDigit() } },
                        label = { Text("API 端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1E40AF),
                            unfocusedBorderColor = Color(0xFFD1D5DB)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = wsPort,
                        onValueChange = { wsPort = it.filter { c -> c.isDigit() } },
                        label = { Text("推送端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1E40AF),
                            unfocusedBorderColor = Color(0xFFD1D5DB)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("接口 Token（与站点服务 --token 一致）") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("手动绑定后无需站点服务器上报心跳，也不会被心跳覆盖；换服务器时直接改这里即可。",
                    fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (host.isBlank()) {
                        status = "请填写服务器地址"
                        return@Button
                    }
                    saving = true
                    scope.launch {
                        val res = AuroraApi.adminSaveSiteEndpoint(
                            domain = domain,
                            host = host.trim(),
                            scheme = if (scheme.trim() == "https") "https" else "http",
                            httpPort = httpPort.toIntOrNull() ?: 3001,
                            wsPort = wsPort.toIntOrNull() ?: 3003,
                            token = token.trim()
                        )
                        saving = false
                        if (res.success) {
                            onChanged("服务器已绑定")
                            onDismiss()
                        } else {
                            status = res.message
                        }
                    }
                },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                shape = RoundedCornerShape(6.dp)
            ) { Text(if (saving) "保存中..." else "保存并绑定", fontSize = 13.sp) }
        },
        dismissButton = {
            Row {
                if (manual) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val res = AuroraApi.adminResetSiteEndpoint(domain)
                                if (res.success) {
                                    onChanged("已恢复自动上报")
                                    onDismiss()
                                } else {
                                    status = res.message
                                }
                            }
                        }
                    ) { Text("恢复自动", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) }
                }
                TextButton(onClick = onDismiss) { Text("取消", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        }
    )
}
@Composable
private fun CardKeyGenerateDialog2(
    onDismiss: () -> Unit,
    onGenerate: (String, String, String, String, Int) -> Unit
) {
    var cardKey by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var permLevel by remember { mutableStateOf("B") }
    var dangerQty by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.aurora.chat.ui.theme.appSurface(),
        title = { Text("生成站点卡密", fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = cardKey,
                    onValueChange = { cardKey = it },
                    label = { Text("卡密") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("卡密密码") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("绑定域名") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("权限等级", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Column {
                    listOf("A" to "A级（全部放行）", "B" to "B级（限制危险）", "C" to "C级（仅站内）").forEach { (code, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = permLevel == code,
                                onClick = { permLevel = code },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Color(0xFF1E40AF))
                            )
                            Text(label, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = dangerQty,
                    onValueChange = { dangerQty = it.filter { c -> c.isDigit() } },
                    label = { Text("危险操作次数（0=不允许）") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = cardKey.isNotBlank() && password.isNotBlank() && domain.isNotBlank(),
                onClick = { onGenerate(cardKey.trim(), password.trim(), domain.trim(), permLevel, dangerQty.trim().toIntOrNull() ?: 0) }
            ) { Text("生成", color = Color(0xFF1E40AF), modifier = Modifier.padding(vertical = 4.dp)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
        }
    )
}
@Composable
private fun CardKeyPermissionDialog(
    item: CardKeyItem2,
    onDismiss: () -> Unit,
    onUpdate: (String, Int) -> Unit
) {
    var permLevel by remember { mutableStateOf(item.permissionLevel) }
    var dangerQty by remember { mutableStateOf(item.dangerQuota.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.aurora.chat.ui.theme.appSurface(),
        title = { Text("权限设置", fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("卡密: ${item.cardKey}", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(8.dp))
                if (item.dangerQuota > 0) {
                    Text("已用危险次数: ${item.dangerUsed}/${item.dangerQuota}", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    Spacer(Modifier.height(10.dp))
                }
                Text("权限等级", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Column {
                    listOf("A" to "A级（全部放行）", "B" to "B级（限制危险）", "C" to "C级（仅站内）").forEach { (code, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = permLevel == code,
                                onClick = { permLevel = code },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Color(0xFF1E40AF))
                            )
                            Text(label, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = dangerQty,
                    onValueChange = { dangerQty = it.filter { c -> c.isDigit() } },
                    label = { Text("危险操作次数（0=不允许）") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUpdate(permLevel, dangerQty.trim().toIntOrNull() ?: 0) }
            ) { Text("保存", color = Color(0xFF1E40AF), modifier = Modifier.padding(vertical = 4.dp)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
        }
    )
}
@Composable
private fun CardKeyAddBalanceDialog(
    item: CardKeyItem2,
    onDismiss: () -> Unit,
    onAdd: (Int) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    val curBalance = remember { item.backupBalance }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.aurora.chat.ui.theme.appSurface(),
        title = { Text("添加备份余额", fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("卡密: ${item.cardKey}", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Text("当前备份余额: ${curBalance}", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(4.dp))
                Text("备份余额表示该站点可同时保留的最大备份份数，在项目管理端创建备份时受此限制。", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() } },
                    label = { Text("本次增加额度") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFFD1D5DB)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (amount.toIntOrNull() ?: 0) > 0,
                onClick = { onAdd(amount.trim().toIntOrNull() ?: 0) }
            ) { Text("确认添加", color = Color(0xFF1E40AF), modifier = Modifier.padding(vertical = 4.dp)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
        }
    )
}
// ═══════════════════ 插件审核(仅开发者) ═══════════════════
/** 插件审核主内容:状态筛选 + 提件列表 + 上架/拒绝/下架操作 */
@Composable
fun PluginReviewContent() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var statusFilter by remember { mutableStateOf("pending") }
    var list by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf(-1L) }
    // 拒绝原因弹窗
    var rejectTarget by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var rejectReason by remember { mutableStateOf("") }
    var actionRunning by remember { mutableStateOf(false) }
    fun load() {
        scope.launch {
            loading = true
            val res = com.aurora.chat.data.api.AuroraApi.pluginAdminList(statusFilter)
            loading = false
            if (res.success) {
                list = res.data ?: emptyList()
            } else {
                list = emptyList()
                android.widget.Toast.makeText(ctx, res.message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(statusFilter) { load() }
    fun act(id: Long, action: String, reason: String = "") {
        scope.launch {
            actionRunning = true
            val res = com.aurora.chat.data.api.AuroraApi.pluginAdminReview(id, action, reason)
            actionRunning = false
            val verb = when (action) { "approve" -> "上架"; "reject" -> "拒绝"; else -> "下架" }
            if (res.success) {
                android.widget.Toast.makeText(ctx, "已$verb", android.widget.Toast.LENGTH_SHORT).show()
                load()
            } else {
                android.widget.Toast.makeText(ctx, res.message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ── 状态筛选 ──
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                "pending" to "待审核", "approved" to "已上架",
                "rejected" to "已拒绝", "removed" to "已下架", "all" to "全部"
            ).forEach { (key, label) ->
                val selected = statusFilter == key
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
                        .clickable { statusFilter = key }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(label, fontSize = 12.sp, color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextSecondary()) }
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF1E40AF))
            }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                Text("暂无记录", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.optLong("id") }) { item ->
                    val id = item.optLong("id")
                    val expanded = expandedId == id
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(com.aurora.chat.ui.theme.appCardBg()).border(0.5.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(10.dp))
                            .clickable { expandedId = if (expanded) -1L else id }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                                Text(
                                    "提交者:${item.optString("uploader")}(ID:${item.optLong("uploaderId")})",
                                    fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint()
                                )
                            }
                            val statusLabel = when (item.optString("status")) {
                                "pending" -> "待审核" to Color(0xFFD97706)
                                "approved" -> "已上架" to Color(0xFF059669)
                                "rejected" -> "已拒绝" to Color(0xFFDC2626)
                                else -> "已下架" to com.aurora.chat.ui.theme.appTextSecondary()
                            }
                            Box(
                                Modifier.clip(RoundedCornerShape(6.dp))
                                    .background(statusLabel.second.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp)
                            ) { Text(statusLabel.first, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusLabel.second) }
                        }
                        if (item.optString("description").isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(item.optString("description"), fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), maxLines = if (expanded) Int.MAX_VALUE else 2)
                        }
                        if (item.optString("reviewReason").isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("审核备注:${item.optString("reviewReason")}", fontSize = 11.sp, color = Color(0xFFDC2626))
                        }
                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            Text("插件内容(JSON)", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F172A)).padding(10.dp)) {
                                Text(item.optString("pluginJson"), fontSize = 11.sp, color = Color(0xFFA5F3FC), lineHeight = 15.sp)
                            }
                        }
                        // ── 操作按钮行 ──
                        if (item.optString("status") == "pending" || item.optString("status") == "approved") {
                            Spacer(Modifier.height(10.dp))
                            Row {
                                if (item.optString("status") == "pending") {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF059669))
                                            .clickable(enabled = !actionRunning) { act(id, "approve") }
                                            .padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) { Text("同意上架", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier.clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appSurface())
                                            .border(1.dp, Color(0xFFDC2626), RoundedCornerShape(8.dp))
                                            .clickable(enabled = !actionRunning) { rejectTarget = item; rejectReason = "" }
                                            .padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) { Text("拒绝", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626)) }
                                } else {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(8.dp)).background(com.aurora.chat.ui.theme.appTextSecondary())
                                            .clickable(enabled = !actionRunning) { act(id, "remove") }
                                            .padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) { Text("下架", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // 拒绝原因弹窗
    rejectTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!actionRunning) rejectTarget = null },
            title = { Text("拒绝「${target.optString("name")}」", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column {
                    Text("可选填拒绝原因,会显示给提交者", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rejectReason,
                        onValueChange = { rejectReason = it },
                        placeholder = { Text("原因(可空)", fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1E40AF),
                            unfocusedBorderColor = Color(0xFFD1D5DB)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !actionRunning, onClick = {
                    act(target.optLong("id"), "reject", rejectReason.trim())
                    rejectTarget = null
                }) { Text("确认拒绝", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { rejectTarget = null }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
            }
        )
    }
}
// ==================== 信息 Tab ====================

@Composable
private fun StatsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        Text(value, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), textAlign = TextAlign.End)
    }
}

// ==================== 信息 Tab ====================
private enum class InfoViewMode { Overview, ActiveUsersList }

@Composable
private fun InfoContent() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeData by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var loginData by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var isLoadingOverview by remember { mutableStateOf(true) }
    var overviewError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var userResult by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    // 列表视图状态
    var viewMode by remember { mutableStateOf(InfoViewMode.Overview) }
    var listTitle by remember { mutableStateOf("今日活跃用户") }
    var activeUserList by remember { mutableStateOf<org.json.JSONArray?>(null) }
    var isLoadingList by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    // 加载今日活跃用户列表
    fun loadActiveUsersList() {
        scope.launch {
            isLoadingList = true
            listError = null
            val r = com.aurora.chat.data.api.AuroraApi.getTodayActiveUsersList()
            if (r.success && r.data != null) {
                // 后端无数据时会返回 list:null（Go 空切片），这里兜底为空数组，
                // 避免落到"数据异常"分支而呈现为空白页，改为明确显示"暂无"
                activeUserList = r.data.optJSONArray("list") ?: org.json.JSONArray()
            } else {
                listError = r.message ?: "加载失败"
            }
            isLoadingList = false
        }
    }
    // 按用户名/ID 查询用户登录统计（"查询"按钮与列表"查看"共用）
    fun performUserSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        isSearching = true
        searchError = null
        userResult = null
        scope.launch {
            try {
                val r = com.aurora.chat.data.api.AuroraApi.getUserLoginStats(q)
                if (r.success && r.data != null && r.data!!.optLong("user_id", 0) > 0) {
                    userResult = r.data
                } else {
                    searchError = "未找到该用户"
                }
            } catch (e: Exception) {
                searchError = "查询失败: ${e.message}"
            }
            isSearching = false
        }
    }
    // 加载今日概览
    LaunchedEffect(Unit) {
        isLoadingOverview = true
        overviewError = null
        val errors = mutableListOf<String>()
        val activeR = com.aurora.chat.data.api.AuroraApi.getTodayActiveUsers()
        if (activeR.success) {
            activeData = activeR.data
        } else {
            errors.add("DAU: ${activeR.message}")
        }
        val loginR = com.aurora.chat.data.api.AuroraApi.getTodayLoginCount()
        if (loginR.success) {
            loginData = loginR.data
        } else {
            errors.add("登录数: ${loginR.message}")
        }
        if (errors.isNotEmpty()) overviewError = errors.joinToString(" | ")
        isLoadingOverview = false
    }
    Box(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        // ============ 概览视图 ============
        if (viewMode == InfoViewMode.Overview) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // 今日概览区
        Text(
            text = "今日概览",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = com.aurora.chat.ui.theme.appTextPrimary(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (isLoadingOverview) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text("加载中...", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // DAU 卡片（可点击）
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            listTitle = "今日活跃用户"
                            viewMode = InfoViewMode.ActiveUsersList
                            loadActiveUsersList()
                        },
                    colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appSelectedBg())
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("今日活跃", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${activeData?.optLong("active_user_count", 0) ?: 0}",
                            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF)
                        )
                        Text("去重用户", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                }
                // 登录次数卡片（可点击）
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            listTitle = "今日登录用户"
                            viewMode = InfoViewMode.ActiveUsersList
                            loadActiveUsersList()
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("今日登录", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${loginData?.optLong("total_login_count", 0) ?: 0}",
                            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A)
                        )
                        Text("总次数", fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF9C3))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("总注册", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${activeData?.optLong("total_registered", 0) ?: 0}",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCA8A04)
                        )
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("活跃率", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                        Spacer(Modifier.height(4.dp))
                        val rate = activeData?.optDouble("active_rate", 0.0) ?: 0.0
                        Text(
                            "${(rate * 100).toInt()}%",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED)
                        )
                    }
                }
            }
        }
        // 调试：API 错误信息
        if (overviewError != null) {
            Text(
                text = "⚠ 接口错误: ${overviewError!!}",
                fontSize = 12.sp,
                color = Color(0xFFDC2626),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        // 分隔线
        Box(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .height(0.5.dp)
                .background(com.aurora.chat.ui.theme.appDivider())
        )
        // 用户查询区
        Text(
            text = "用户查询",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = com.aurora.chat.ui.theme.appTextPrimary(),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it; searchError = null },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入用户名或用户 ID", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { performUserSearch(searchQuery) },
                enabled = !isSearching && searchQuery.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
            ) {
                Text(if (isSearching) "查询中..." else "查询", fontSize = 14.sp)
            }
        }
        if (searchError != null) {
            Text(
                text = searchError!!,
                fontSize = 13.sp,
                color = Color(0xFFDC2626),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        // 查询结果详情
        userResult?.let { user ->
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            // 基本信息卡片
            Text(
                text = "基本信息",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextSecondary(),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    StatsInfoRow("用户 ID", user.optLong("user_id", 0).toString())
                    StatsInfoRow("用户名", user.optString("username", ""))
                    StatsInfoRow("邮箱", user.optString("email", ""))
                    StatsInfoRow("QQ", user.optString("qq_number", "").ifBlank { "未绑定" })
                    StatsInfoRow("签名", user.optString("signature", "").ifBlank { "无" })
                    StatsInfoRow("注册 IP", user.optString("reg_ip", "").ifBlank { "未知" })
                    StatsInfoRow("注册时间", user.optLong("created_at", 0).let { if (it > 0) fmt.format(java.util.Date(it * 1000)) else "未知" })
                    StatsInfoRow("最后活跃", user.optLong("updated_at", 0).let { if (it > 0) fmt.format(java.util.Date(it * 1000)) else "未知" })
                    StatsInfoRow("邮箱验证", if (user.optBoolean("email_verified", false)) "已验证" else "未验证")
                }
            }
            Spacer(Modifier.height(12.dp))
            // 统计数据卡片
            Text(
                text = "统计数据",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextSecondary(),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val ot = user.optLong("online_time_seconds", 0)
                    val _d = ot / 86400; val _h = (ot % 86400) / 3600; val _m = (ot % 3600) / 60
                    StatsInfoRow("累计在线", "${_d}天${_h}小时${_m}分")
                    StatsInfoRow("累计发字", user.optLong("word_count", 0).toString())
                    StatsInfoRow("Token 余额", user.optInt("token_balance", 0).toString())
            Spacer(Modifier.height(12.dp))
            // 登录统计卡片
            Text(
                text = "登录统计",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextSecondary(),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(6.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    StatsInfoRow("总登录次数", user.optLong("total_logins", 0).toString())
                    StatsInfoRow("今日登录", "${user.optLong("today_logins", 0)} 次")
                    StatsInfoRow("近 7 天登录", "${user.optLong("week_logins", 0)} 次")
                    StatsInfoRow("近 30 天登录", "${user.optLong("month_logins", 0)} 次")
                    StatsInfoRow("最后登录", user.optLong("last_login_time", 0).let { if (it > 0) fmt.format(java.util.Date(it * 1000)) else "无记录" })
                    StatsInfoRow("最后登录 IP", user.optString("last_login_ip", "").ifBlank { "无记录" })
                }
            }
            // 近 7 天趋势
            val weekTrend = user.optJSONArray("week_trend")
            if (weekTrend != null && weekTrend.length() > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "近 7 天登录趋势",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        var maxCount = 0
                        for (i in 0 until weekTrend.length()) {
                            val cnt = weekTrend.getJSONObject(i).optInt("count", 0)
                            if (cnt > maxCount) maxCount = cnt
                        }
                        for (i in 0 until weekTrend.length()) {
                            val item = weekTrend.getJSONObject(i)
                            val date = item.optString("date", "")
                            val cnt = item.optInt("count", 0)
                            val barHeight = if (maxCount > 0) (cnt.toFloat() / maxCount * 40).toInt() else 0
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("", fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                                Spacer(Modifier.height(2.dp))
                                Box(
                                    modifier = Modifier
                                        .width(20.dp)
                                        .height(barHeight.dp)
                                        .background(if (cnt > 0) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(date, fontSize = 10.sp, color = com.aurora.chat.ui.theme.appTextHint())
                            }
                        }
                    }
                }
            }
            // 最近登录记录
            val recentLogins = user.optJSONArray("recent_logins")
            if (recentLogins != null && recentLogins.length() > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "最近登录记录",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.aurora.chat.ui.theme.appTextSecondary(),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        for (i in 0 until recentLogins.length()) {
                            val record = recentLogins.getJSONObject(i)
                            val time = record.optLong("login_time", 0)
                            val ip = record.optString("login_ip", "")
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    time.let { if (it > 0) fmt.format(java.util.Date(it * 1000)) else "未知" },
                                    fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                                )
                                Text(
                                    ip.ifBlank { "未知 IP" },
                                    fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                                )
                            }
                            if (i < recentLogins.length() - 1) {
                                Box(
                                    modifier = Modifier.fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(com.aurora.chat.ui.theme.appDivider())
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        }  // 关闭概览 Column
        }  // 关闭 if (Overview)
        }  // 修正：真正闭合"概览 Column"（此前缺失，导致列表视图被误嵌进概览分支，切换后整页空白）
        }  // 修正：真正闭合"概览 if"

        // ============ 今日活跃/登录用户列表视图（全屏覆盖） ============
        if (viewMode == InfoViewMode.ActiveUsersList) {
        Column(modifier = Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
            // 顶部返回栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appCardBg())
                    .clickable { viewMode = InfoViewMode.Overview }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "← 返回",
                    fontSize = 14.sp,
                    color = Color(0xFF1E40AF)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = listTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.weight(1f)
                )
            }
            // 列表内容（用 Box + weight 占据剩余高度，避免 LazyColumn 在 Column 内高度塌缩为 0 而空白）
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                isLoadingList -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF1E40AF))
                            Spacer(Modifier.height(8.dp))
                            Text("加载中...", color = com.aurora.chat.ui.theme.appTextSecondary(), fontSize = 13.sp)
                        }
                    }
                }
                listError != null -> {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠ 接口错误", color = Color(0xFFDC2626), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(listError!!, color = Color(0xFFDC2626), fontSize = 13.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { loadActiveUsersList() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF))
                            ) { Text("重试", color = Color.White) }
                        }
                    }
                }
                activeUserList == null -> {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠ 数据异常", color = Color(0xFFDC2626), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("列表数据为空，可能是接口返回格式变化", color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 13.sp)
                        }
                    }
                }
                activeUserList!!.length() == 0 -> {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📭", fontSize = 48.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("今日暂无活跃用户", color = com.aurora.chat.ui.theme.appTextSecondary(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text("登录次数为 0，还没有用户今日登录过", color = com.aurora.chat.ui.theme.appTextHint(), fontSize = 12.sp)
                        }
                    }
                }
                else -> {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
                    ) {
                        items(activeUserList!!.length()) { idx ->
                            val user = activeUserList!!.getJSONObject(idx)
                            val userId = user.optLong("user_id", 0)
                            val username = user.optString("username", "?")
                            val loginCount = user.optLong("login_count", 0)
                            val lastLoginTime = user.optLong("last_login_time", 0)
                            val lastLoginIP = user.optString("last_login_ip", "")
                            val timeStr = if (lastLoginTime > 0) {
                                val s = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                s.format(java.util.Date(lastLoginTime * 1000))
                            } else ""
                            // 行
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = com.aurora.chat.ui.theme.appCardBg())
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 真实头像
                                    UserAvatar(
                                        userId = userId,
                                        userName = username,
                                        size = 40.dp
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    // 用户名 + 副标题
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = username,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = com.aurora.chat.ui.theme.appTextPrimary()
                                        )
                                        Text(
                                            text = "ID=$userId  ·  登录 ${loginCount} 次  ·  $timeStr",
                                            fontSize = 11.sp,
                                            color = com.aurora.chat.ui.theme.appTextSecondary()
                                        )
                                        if (lastLoginIP.isNotEmpty()) {
                                            Text(
                                                text = "IP: $lastLoginIP",
                                                fontSize = 10.sp,
                                                color = com.aurora.chat.ui.theme.appTextHint()
                                            )
                                        }
                                    }
                                    // 查看按钮
                                    Text(
                                        text = "查看",
                                        color = Color(0xFF1E40AF),
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clickable {
                                                searchQuery = username
                                                viewMode = InfoViewMode.Overview
                                                performUserSearch(username)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }  // 关闭列表内容 Box (weight 占据剩余高度)
        }  // 关闭列表视图 Column
        }  // 关闭 if (ActiveUsersList)
    }  // 关闭 Box
}  // 关闭 InfoContent

// ==================== 文件分发配置（后台中心板块一，键值驱动） ====================
// 配置管理规范：配置项全部走键值存储（后端 fd_user_configs 表 + fdConfigKeySpecs 注册表），
// 不写死字段。日后新增配置项 = 在 fdConfigFieldSpecs 加一个字段定义 + 后端注册同键，前后端结构零改动。
//
// 配置层级：
//   - 开发者 → 完全不受限制（后端 isDeveloperUser 直接跳过所有配置）；
//   - user_id=0 → 默认(通用)限制，对所有未单独配置的用户生效；
//   - 已单独配置的用户 → 只按自己的配置生效（缺键=不限）。

/** 单个配置项的字段定义（纯数据，改这里就能加新配置项） */
private class FdConfigFieldSpec(
    val key: String,                      // 存储键（与后端 fdConfigKeySpecs 同名）
    val label: String,                    // 显示名
    val fixedUnit: String? = null,        // 固定单位后缀（如 Mbps），不参与编辑
    val unitKey: String? = null,          // 可选单位的存储键（如 capacity_unit）
    val unitOptions: List<String> = emptyList(), // 可选单位列表
    val placeholder: String = "0（不限）",
    val hint: String = ""
)

/** 当前全部配置项定义 */
private val fdConfigFieldSpecs = listOf(
    FdConfigFieldSpec(
        key = "max_speed",
        label = "最高下载速度",
        fixedUnit = "Mbps",
        placeholder = "0 = 不限"
    ),
    FdConfigFieldSpec(
        key = "capacity_value",
        label = "总容量",
        unitKey = "capacity_unit",
        unitOptions = listOf("KB", "MB", "GB", "TB"),
        placeholder = "0 = 不限"
    )
)

/** 编辑状态：键值双向转换（JSONObject ↔ 输入框），单位键单独存 */
private class FdConfigEditState {
    val values = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val units = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun load(configs: org.json.JSONObject) {
        fdConfigFieldSpecs.forEach { spec ->
            values[spec.key] = configs.optString(spec.key, "")
            spec.unitKey?.let { uk ->
                val cur = configs.optString(uk, "")
                units[uk] = if (cur.isNotEmpty()) cur else (spec.unitOptions.lastOrNull() ?: "GB")
            }
        }
    }

    fun toConfigs(): org.json.JSONObject {
        val obj = org.json.JSONObject()
        fdConfigFieldSpecs.forEach { spec ->
            obj.put(spec.key, values[spec.key]?.trim() ?: "")   // 空串 = 删除该键 = 该项不限
            spec.unitKey?.let { uk -> obj.put(uk, units[uk] ?: "") }
        }
        return obj
    }
}

/** 配置摘要文案："速度 5 Mbps · 容量 10 GB"，未设置的键显示 不限 */
private fun fdConfigSummary(configs: org.json.JSONObject): String {
    val speed = configs.optString("max_speed", "").trim()
    val capVal = configs.optString("capacity_value", "").trim()
    val capUnit = configs.optString("capacity_unit", "GB").ifBlank { "GB" }
    val speedPart = if (speed.isNotEmpty() && speed.toDoubleOrNull()?.let { it > 0 } == true) "速度 ${speed} Mbps" else "速度不限"
    val capPart = if (capVal.isNotEmpty() && capVal.toDoubleOrNull()?.let { it > 0 } == true) "容量 $capVal $capUnit" else "容量不限"
    return "$speedPart · $capPart"
}

/** 配置字段编辑器（默认配置 Tab 与用户弹窗共用，纯按 fdConfigFieldSpecs 渲染） */
@Composable
private fun FdConfigFieldsEditor(state: FdConfigEditState) {
    Column {
        fdConfigFieldSpecs.forEachIndexed { idx, spec ->
            if (idx > 0) Spacer(Modifier.height(14.dp))
            Text(spec.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = state.values[spec.key] ?: "",
                    onValueChange = { state.values[spec.key] = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.weight(1f).padding(8.dp)
                        .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(8.dp)),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    ),
                    decorationBox = { inner ->
                        if ((state.values[spec.key] ?: "").isEmpty()) Text(spec.placeholder, fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint())
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                if (spec.fixedUnit != null) {
                    Text(spec.fixedUnit, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF0891B2))
                }
                if (spec.unitKey != null) {
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        spec.unitOptions.forEach { unit ->
                            val selected = (state.units[spec.unitKey] ?: "") == unit
                            Text(
                                text = unit,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextSecondary(),
                                modifier = Modifier
                                    .background(
                                        if (selected) Color(0xFF0891B2) else com.aurora.chat.ui.theme.appDivider(),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { state.units[spec.unitKey!!] = unit }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「配置」Tab：默认(通用)限制 + 已单独配置的用户管理 */
@Composable
private fun FdConfigManagementContent() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf("") }
    // 默认(通用)配置
    var defaultConfigs by remember { mutableStateOf(org.json.JSONObject()) }
    var defaultState by remember { mutableStateOf<FdConfigEditState?>(null) }
    var savingDefault by remember { mutableStateOf(false) }
    // 已单独配置的用户列表
    var customList by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }

    fun loadAll() {
        loading = true
        errorText = ""
        scope.launch {
            try {
                val r = com.aurora.chat.data.api.AuroraApi.adminGetFdConfig(0)
                if (r.success && r.data != null) {
                    defaultConfigs = r.data!!.optJSONObject("configs") ?: org.json.JSONObject()
                    defaultState = FdConfigEditState().apply { load(defaultConfigs) }
                } else errorText = r.message
                val lr = com.aurora.chat.data.api.AuroraApi.adminGetFdCustomList()
                if (lr.success && lr.data != null) {
                    customList = (0 until lr.data!!.length()).map { lr.data!!.getJSONObject(it) }
                }
            } catch (e: Exception) {
                errorText = e.message ?: "加载失败"
            }
            loading = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { loadAll() }

    fun humanSize(n: Long): String {
        val f = n.toDouble()
        return when {
            f >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", f / 1024 / 1024 / 1024)
            f >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB", f / 1024 / 1024)
            f >= 1024 -> String.format(java.util.Locale.US, "%.2f KB", f / 1024)
            else -> "${n} B"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 默认(通用)限制
        Text("默认(通用)限制", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth()
                .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            if (loading) {
                Text("加载中...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
            } else if (defaultState != null) {
                FdConfigFieldsEditor(defaultState!!)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (savingDefault) "保存中..." else "保存默认配置",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (savingDefault) com.aurora.chat.ui.theme.appTextHint() else Color(0xFF0891B2),
                    modifier = Modifier.clickable(enabled = !savingDefault) {
                        savingDefault = true
                        scope.launch {
                            try {
                                val r = com.aurora.chat.data.api.AuroraApi.adminSetFdConfig(0, defaultState!!.toConfigs())
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (r.success) {
                                        android.widget.Toast.makeText(ctx, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                                        loadAll()
                                    } else {
                                        android.widget.Toast.makeText(ctx, "保存失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(ctx, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            withContext(kotlinx.coroutines.Dispatchers.Main) { savingDefault = false }
                        }
                    }
                )
            }
            if (errorText.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("加载失败: $errorText", fontSize = 12.sp, color = Color(0xFFDC2626))
            }
        }

        Spacer(Modifier.height(20.dp))

        // 已单独配置的用户
        Text("已单独配置的用户 (${customList.size})", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
        Spacer(Modifier.height(12.dp))
        if (customList.isEmpty()) {
            if (!loading) Text("暂无", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
        customList.forEach { item ->
            val uid = item.optLong("user_id", 0)
            val username = item.optString("username", "")
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (username.isNotEmpty()) "$username (ID=$uid)" else "ID=$uid",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary()
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${fdConfigSummary(item.optJSONObject("configs") ?: org.json.JSONObject())} · 已用 ${humanSize(item.optLong("used", 0))}",
                        fontSize = 11.sp, color = com.aurora.chat.ui.theme.appTextSecondary()
                    )
                }
                Text(
                    "清除",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFDC2626),
                    modifier = Modifier.clickable {
                        scope.launch {
                            try {
                                val r = com.aurora.chat.data.api.AuroraApi.adminResetFdConfig(uid)
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (r.success) {
                                        android.widget.Toast.makeText(ctx, "已清除", android.widget.Toast.LENGTH_SHORT).show()
                                        loadAll()
                                    } else {
                                        android.widget.Toast.makeText(ctx, "操作失败: ${r.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(ctx, "操作失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 用户单独配置弹窗（用户行「配置」按钮；键值驱动，与「配置」Tab 共用编辑器） */
@Composable
private fun FdConfigDialog(
    userId: Long,
    userName: String,
    onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var hasCustom by remember { mutableStateOf(false) }
    var defaultConfigs by remember { mutableStateOf(org.json.JSONObject()) }
    var usedBytes by remember { mutableLongStateOf(0L) }
    var state by remember { mutableStateOf<FdConfigEditState?>(null) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            val r = com.aurora.chat.data.api.AuroraApi.adminGetFdConfig(userId)
            if (r.success && r.data != null) {
                val d = r.data!!
                hasCustom = d.optBoolean("has_custom", false)
                defaultConfigs = d.optJSONObject("default_configs") ?: org.json.JSONObject()
                usedBytes = d.optLong("used", 0L)
                state = FdConfigEditState().apply { load(d.optJSONObject("configs") ?: org.json.JSONObject()) }
            } else loadError = r.message
        } catch (e: Exception) {
            loadError = e.message ?: "加载失败"
        }
        loading = false
    }

    fun humanSize(n: Long): String {
        val f = n.toDouble()
        return when {
            f >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", f / 1024 / 1024 / 1024)
            f >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f MB", f / 1024 / 1024)
            f >= 1024 -> String.format(java.util.Locale.US, "%.2f KB", f / 1024)
            else -> "${n} B"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = com.aurora.chat.ui.theme.appSurface(),
        title = { Text("文件分发配置", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                when {
                    loading -> Text("加载中...", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
                    loadError.isNotEmpty() -> Text("加载失败: $loadError", fontSize = 12.sp, color = Color(0xFFDC2626))
                    state != null -> FdConfigFieldsEditor(state!!)
                }
                if (errorText.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(errorText, fontSize = 12.sp, color = Color(0xFFDC2626))
                }
                if (hasCustom) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "清除单独配置",
                        fontSize = 12.sp, color = Color(0xFFDC2626),
                        modifier = Modifier.clickable(enabled = !saving) {
                            saving = true
                            scope.launch {
                                try {
                                    val r = com.aurora.chat.data.api.AuroraApi.adminResetFdConfig(userId)
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        if (r.success) {
                                            android.widget.Toast.makeText(ctx, "已清除", android.widget.Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } else errorText = r.message
                                    }
                                } catch (e: Exception) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) { errorText = e.message ?: "操作失败" }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) { saving = false }
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && !loading && state != null, onClick = {
                saving = true
                errorText = ""
                scope.launch {
                    try {
                        val r = com.aurora.chat.data.api.AuroraApi.adminSetFdConfig(userId, state!!.toConfigs())
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (r.success) {
                                android.widget.Toast.makeText(ctx, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else errorText = r.message
                        }
                    } catch (e: Exception) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) { errorText = e.message ?: "保存失败" }
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) { saving = false }
                }
            }) { Text(if (saving) "保存中..." else "保存", color = Color(0xFF0891B2)) }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) { Text("取消", color = com.aurora.chat.ui.theme.appTextSecondary()) }
        }
    )
}

private fun trimFdNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(java.util.Locale.US, "%.1f", d)
