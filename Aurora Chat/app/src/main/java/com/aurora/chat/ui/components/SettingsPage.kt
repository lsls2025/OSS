package com.aurora.chat.ui.components
import com.aurora.chat.ui.theme.LocalAppColors

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

enum class AnimationMode(val value: Int, val label: String) {
    NONE(0, "无动画"),
    LEFT_RIGHT(1, "左右滑出"),
    BOTTOM_RIGHT(2, "右下滑出")
}

// ===================== 界面设置存储 =====================

object WallpaperStorage {
    private const val PREFS = "aurora_wallpaper"

    fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 社区背景图自定义 URI；null = 使用默认 community_bg.jpg */
    fun communityBgUri(context: Context): String? =
        getPrefs(context).getString("community_bg_uri", null)

    fun setCommunityBgUri(context: Context, uri: String?) {
        getPrefs(context).edit().putString("community_bg_uri", uri).apply()
    }
}

// ===================== 消息气泡配色存储 =====================

object BubbleColorStorage {
    private const val PREFS = "aurora_bubble_color"
    private const val KEY = "my_bubble_color"
    /** 默认气泡色：品牌深蓝（旧版默认是微信绿，见 [OLD_DEFAULT] 的一次性迁移） */
    val DEFAULT = Color(0xFF1E40AF)
    /** 旧版默认值（微信绿），仅用于迁移识别 */
    private val OLD_DEFAULT = Color(0xFF95EC69)
    private const val KEY_MIGRATED = "default_migrated_to_brand_blue"

    /**
     * 「液态」气泡哨兵色：全透明（无任何预设/自定义色板会取到它）。
     * 存/取都走原来的 Long 通道，渲染端见 [isLiquid] 走玻璃材质（见 LiquidBubble.kt）。
     */
    val LIQUID = Color(0x00000000)

    /** 当前气泡色是否为「液态」玻璃材质（与 App 液态模式开关无关，只看配色本身） */
    fun isLiquid(color: Color): Boolean = color.value == LIQUID.value

    /** 预设气泡色板：名称 + 颜色（深底选项选中后气泡内文字自动转白） */
    val PRESETS = listOf(
        "微信绿" to Color(0xFF95EC69),
        "深蓝（品牌色）" to Color(0xFF1E40AF),
        "天蓝" to Color(0xFF3B82F6),
        "紫色" to Color(0xFF8B5CF6),
        "青色" to Color(0xFF14B8A6),
        "玫红" to Color(0xFFEC4899),
        "橙色" to Color(0xFFF59E0B),
        "红色" to Color(0xFFEF4444),
        "白色" to Color(0xFFFFFFFF),
    )

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun myBubbleColor(context: Context): Color {
        val prefs = getPrefs(context)
        var v = prefs.getLong(KEY, DEFAULT.value.toLong())
        // 一次性迁移：旧版本默认是微信绿。只改 DEFAULT 对已经存过值的老用户不生效，
        // 所以这里对「从未选过色」或「仍是旧默认绿」的情况统一改成品牌深蓝，且只执行一次。
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            if (!prefs.contains(KEY) || v == OLD_DEFAULT.value.toLong()) {
                v = DEFAULT.value.toLong()
                prefs.edit().putLong(KEY, v).apply()
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }
        // 旧「液态」哨兵色（全透明）→ 兜底回经典默认色
        if (v == LIQUID.value.toLong()) {
            v = DEFAULT.value.toLong()
            prefs.edit().putLong(KEY, v).apply()
        }
        return Color(v.toULong())
    }

    fun setMyBubbleColor(context: Context, color: Color) {
        getPrefs(context).edit().putLong(KEY, color.value.toLong()).apply()
    }

    /** 对方气泡默认白色；同样仅本地视角生效，不影响对方真实显示 */
    val DEFAULT_OTHER = Color(0xFFFFFFFF)

    fun otherBubbleColor(context: Context): Color {
        val v = getPrefs(context).getLong("other_bubble_color", DEFAULT_OTHER.value.toLong())
        // 旧「液态」哨兵色（全透明）→ 兜底回经典默认色
        if (v == LIQUID.value.toLong()) return DEFAULT_OTHER
        return Color(v.toULong())
    }

    fun setOtherBubbleColor(context: Context, color: Color) {
        getPrefs(context).edit().putLong("other_bubble_color", color.value.toLong()).apply()
    }

    /** 相对亮度 < 0.5 视为深底，需配白字；液态玻璃是浅色表面，一律按浅底（深字）处理 */
    fun isDark(color: Color): Boolean {
        if (isLiquid(color)) return false
        val lum = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
        return lum < 0.5
    }

    /**
     * 气泡正文色：**只由气泡自身的底色决定，与页面深浅主题无关**。
     *
     * 这里必须是纯函数。旧实现用的是「气泡深浅 ? 白 : 主题主文字色」——
     * 气泡底色是用户自己设的（默认白 / 微信绿 / 任意自定义色），不会跟着主题变；
     * 而主题主文字色在深色主题下是白色 → 黑主题里「对方发来的白色气泡」装着白字，
     * 整条消息肉眼完全看不见（用户实测反馈）。
     * 页面背景变深只是页面的事，气泡里的字永远只该读自己脚下那块底。
     */
    fun bubbleTextColor(color: Color): Color =
        if (isDark(color)) Color.White else Color(0xFF000000)

    /**
     * 气泡内次级文字色（引用胶囊的发件人名/副标题等）：同为深浅自适应的同色系，
     * 浅底用 58% 黑（≈ 改动前浅色主题的 #6B7280 观感），深底用 90% 白。
     */
    fun bubbleSubTextColor(color: Color): Color =
        if (isDark(color)) Color.White.copy(alpha = 0.9f) else Color(0xFF000000).copy(alpha = 0.58f)

    /**
     * 引用胶囊的底色叠加：浅底气泡压 6% 黑（保持原观感）；
     * 深底气泡必须改成压 12% 白，否则黑压黑等于没压，引用块和正文糊成一整片。
     */
    fun bubbleCapsuleOverlay(color: Color): Color =
        if (isDark(color)) Color.White.copy(alpha = 0.12f) else Color(0xFF000000).copy(alpha = 0.06f)

    /** 当前选中色对应的预设名（用于设置列表右侧展示），无匹配返回「自定义」 */
    fun presetName(color: Color): String {
        if (isLiquid(color)) return "液态"
        return PRESETS.firstOrNull { it.second.value == color.value }?.first ?: "自定义"
    }
}

// ===================== 设置页面 =====================

/**
 * 设置类子页统一标题栏（以「开发者管理」为基准样式）：
 * 白色底一直铺到状态栏、52dp 行高、左侧 44dp 返回热区（ArrowBack #1F2937）、
 * 标题 17sp Bold #1F2937 左对齐。
 */
@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = com.aurora.chat.ui.theme.appBarBg(),
    right: @Composable () -> Unit = {}
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled. ArrowBack,
                    contentDescription = "返回",
                    tint = com.aurora.chat.ui.theme.appTextPrimary()
                )
            }
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = com.aurora.chat.ui.theme.appTextPrimary()
            )
            Spacer(Modifier.weight(1f))
            right()
        }
    }
}

@Composable
fun SettingsPage(
    onClose: () -> Unit,
    onSwitchAccount: () -> Unit = {},
    isDeveloper: Boolean = false,
    onOpenDeveloperManagement: () -> Unit = {},
    onLogout: () -> Unit = {},
    onMessagePush: () -> Unit = {},
    onOpenErrorLog: () -> Unit = {},
    onPrivacySettings: () -> Unit = {},
    onBlacklist: () -> Unit = {},
    currentAccount: String = "",
    onAccountDeleted: () -> Unit = {}
) {
    var showMessageDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBindEmailDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // ── 固定标题栏（与其他设置子页统一）──
        SettingsTopBar(title = "设置", onBack = onClose)

        // ── 可滚动内容区 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {

        // ── 消息设置（气泡配色，点击进入全屏）──
        val curBubbleColor = BubbleColorStorage.myBubbleColor(context)
        val curOtherBubbleColor = BubbleColorStorage.otherBubbleColor(context)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showMessageDialog = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "消息设置", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${BubbleColorStorage.presetName(curBubbleColor)} · ${BubbleColorStorage.presetName(curOtherBubbleColor)} ›",
                fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextHint()
            )
        }

        // ── 绑定邮箱（已绑定则仅展示，不再弹窗） ──
        val emailVerified = com.aurora.chat.data.api.AuroraApi.currentUserEmailVerified
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !emailVerified) { showBindEmailDialog = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "绑定邮箱", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (emailVerified) "已绑定" else "未绑定 ›",
                fontSize = 14.sp,
                color = if (emailVerified) Color(0xFF16A34A) else com.aurora.chat.ui.theme.appTextHint()
            )
        }

        // ── 分割线 ──
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

        // ── 主题（白色 / 深色 / 跟随系统）──
        val themeLabel = com.aurora.chat.ui.theme.ThemePrefs.label(context)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showThemeDialog = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "主题", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "$themeLabel ›", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
        }

        // ── 分割线 ──
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

        // ── 消息推送（从「其他」迁入） ──
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onMessagePush() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "消息推送", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
        // ── 全部已读（从「其他」迁入） ──
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                com.aurora.chat.ui.viewmodel.ChatViewModel.markAllRead(context)
                android.widget.Toast.makeText(context, "已标记全部会话为已读", android.widget.Toast.LENGTH_SHORT).show()
            }.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "全部已读", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
        // ── 隐私设置 / 黑名单（从「我的」迁入） ──
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onPrivacySettings() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "隐私设置", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onBlacklist() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "黑名单", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }

        // ── 开发者管理（仅开发者可见，点击直接跳转，不复用旧 UI） ──
        if (isDeveloper) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenDeveloperManagement() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "开发者管理", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.weight(1f))
                Text(text = "›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
            }
        }

        // ── 分割线 ──
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSwitchAccount() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "切换账号", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }

        // ── 退出登录 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLogout() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "退出登录", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }

        // ── 分割线 ──
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
        // ── 注销账号（危险操作，红色） ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDeleteDialog = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "注销账号", fontSize = 16.sp, color = Color(0xFFDC2626))
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFFDC2626))
        }
        // 底部 padding，防止最后一项被系统导航栏/底部遮挡
        Spacer(Modifier.height(32.dp))
        } // 结束可滚动 Column
    } // 结束最外层 Column

    if (showDeleteDialog) {
        DeleteAccountDialog(
            account = currentAccount,
            onDismiss = { showDeleteDialog = false },
            onSuccess = onAccountDeleted
        )
    }

    // 消息设置及其子页：整层右滑进入/滑出，与隐私设置、切换账号等子页保持一致
    AnimatedVisibility(
        visible = showMessageDialog,
        enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(160)),
        exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(120))
    ) {
        MessageSettingScreen(onBack = { showMessageDialog = false })
    }

    // 主题选择：整层右滑进入/滑出，与消息设置等子页保持一致
    AnimatedVisibility(
        visible = showThemeDialog,
        enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(160)),
        exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(120))
    ) {
        ThemeScreen(onBack = { showThemeDialog = false })
    }

    if (showBindEmailDialog) {
        BindEmailDialog(
            onDismiss = { showBindEmailDialog = false },
            onBound = { showBindEmailDialog = false }
        )
    }
}

// ===================== 绑定邮箱弹窗 =====================

@Composable
private fun BindEmailDialog(onDismiss: () -> Unit, onBound: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var isSendingCode by remember { mutableStateOf(false) }
    var codeCountdown by remember { mutableStateOf(0) }
    var binding by remember { mutableStateOf(false) }

    LaunchedEffect(codeCountdown) {
        if (codeCountdown > 0) {
            kotlinx.coroutines.delay(1000)
            codeCountdown--
        }
    }

    fun sendCode() {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(ctx, "邮箱格式不正确", Toast.LENGTH_SHORT).show(); return
        }
        isSendingCode = true
        scope.launch {
            val result = com.aurora.chat.data.api.AuroraApi.sendBindCode(email)
            isSendingCode = false
            if (result.success) {
                Toast.makeText(ctx, "验证码已发送", Toast.LENGTH_SHORT).show()
                codeCountdown = 60
            } else {
                Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun bind() {
        if (email.isBlank() || code.isBlank()) {
            Toast.makeText(ctx, "请填写邮箱和验证码", Toast.LENGTH_SHORT).show(); return
        }
        binding = true
        scope.launch {
            val result = com.aurora.chat.data.api.AuroraApi.bindEmail(email, code)
            binding = false
            if (result.success) {
                com.aurora.chat.data.api.AuroraApi.currentUserEmailVerified = true
                onBound()
            } else {
                Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val bindDialogContent: @Composable () -> Unit = {
            Text("绑定邮箱", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
            Spacer(Modifier.height(4.dp))
            Text("绑定后即可解锁全部功能（私信、群聊、社区等）",
                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(value = email, onValueChange = { email = it },
                placeholder = { Text("输入邮箱", fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = code, onValueChange = { code = it },
                    placeholder = { Text("输入验证码", fontSize = 14.sp) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .height(48.dp).widthIn(min = 90.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (codeCountdown > 0 || isSendingCode) Color(0xFFD1D5DB) else Color(0xFF1E40AF))
                        .clickable(enabled = codeCountdown == 0 && !isSendingCode && email.isNotBlank()) { sendCode() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when { isSendingCode -> "发送中..."; codeCountdown > 0 -> "${codeCountdown}s"; else -> "发送验证码" },
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        color = if (codeCountdown > 0 || isSendingCode) com.aurora.chat.ui.theme.appTextSecondary() else Color.White)
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { bind() },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                enabled = !binding
            ) {
                Text(if (binding) "绑定中…" else "绑定邮箱", color = Color(0xFF1E40AF), fontSize = 15.sp)
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("暂不绑定", color = com.aurora.chat.ui.theme.appTextHint())
            }
        }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(com.aurora.chat.ui.theme.appCardBg(), RoundedCornerShape(16.dp)).padding(24.dp)
        ) { bindDialogContent() }
    }
}

// ===================== 注销账号弹窗 =====================

@Composable
private fun DeleteAccountDialog(
    account: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val deleteDialogContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("注销账号", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = com.aurora.chat.ui.theme.appTextPrimary())
                Spacer(Modifier.height(8.dp))
                Text("此操作将永久删除该账号的所有数据（含 QQ 号与邮箱），且不可恢复。", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                Spacer(Modifier.height(18.dp))

                // 账号（只读确认）
                OutlinedTextField(
                    value = account,
                    onValueChange = {},
                    label = { Text("账号") },
                    readOnly = true,
                    enabled = false,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFDC2626),
                        cursorColor = Color(0xFFDC2626)
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = null },
                    label = { Text("密码（必填）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFDC2626),
                        cursorColor = Color(0xFFDC2626)
                    )
                )

                if (errorMsg != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(errorMsg!!, fontSize = 13.sp, color = Color(0xFFDC2626))
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("取消", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextHint(),
                        modifier = Modifier.clickable { if (!isLoading) onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 8.dp))
                    Spacer(Modifier.width(8.dp))
                    val onDeleteClick: () -> Unit = delete@{
                        if (password.isBlank()) { errorMsg = "请输入密码"; return@delete }
                        isLoading = true
                        errorMsg = null
                        scope.launch {
                            try {
                                val r = com.aurora.chat.data.api.AuroraApi.selfDeleteAccount(password)
                                if (r.success) {
                                    onSuccess()
                                } else {
                                    errorMsg = r.message ?: "注销失败"
                                    isLoading = false
                                }
                            } catch (e: Exception) {
                                errorMsg = e.message ?: "注销失败"
                                isLoading = false
                            }
                        }
                    }
                    Button(onClick = onDeleteClick,
                            modifier = Modifier.height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("确认注销", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDC2626))
                        }
                    }
                }
            }
        }
    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = com.aurora.chat.ui.theme.appCardBg()
        ) { deleteDialogContent() }
    }
}

// ===================== 主题选择 =====================

/**
 * 主题选择：白色主题 / 深色主题 / 跟随系统。
 * 选中即保存，并重建当前 Activity 让主题即时生效。
 */
@Composable
private fun ThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(com.aurora.chat.ui.theme.ThemePrefs.getMode(context)) }
    fun choose(v: Int) {
        mode = v
        com.aurora.chat.ui.theme.ThemePrefs.setMode(context, v)
        // 重建 Activity 让主题即时生效（顶层 AuroraChatTheme 在重建时读取新偏好）。
        // 注意：本项目的宿主 Activity 是 ComponentActivity（非 AppCompatActivity），
        // 用 AppCompatActivity 强转会返回 null 导致 recreate 不执行，故改用 android.app.Activity。
        (context as? android.app.Activity)?.recreate()
    }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        SettingsTopBar(title = "主题", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(4.dp))
            ThemeOption(
                title = "白色主题",
                subtitle = "始终使用浅色背景",
                selected = mode == com.aurora.chat.ui.theme.ThemePrefs.MODE_LIGHT
            ) { choose(com.aurora.chat.ui.theme.ThemePrefs.MODE_LIGHT) }
            ThemeOption(
                title = "深色主题",
                subtitle = "始终使用深色背景",
                selected = mode == com.aurora.chat.ui.theme.ThemePrefs.MODE_DARK
            ) { choose(com.aurora.chat.ui.theme.ThemePrefs.MODE_DARK) }
            ThemeOption(
                title = "跟随系统",
                subtitle = "系统切到深色时自动变深色，反之变浅色",
                selected = mode == com.aurora.chat.ui.theme.ThemePrefs.MODE_SYSTEM
            ) { choose(com.aurora.chat.ui.theme.ThemePrefs.MODE_SYSTEM) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThemeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.padding(top = 2.dp))
        }
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
}

// ===================== 消息设置全屏页（菜单：气泡设置 / 消息动画）=====================

/** 消息动画模式存储：与聊天页读同一键 animation_mode（默认 0=无动画）。 */
private object MessageAnimStorage {
    fun get(context: Context): Int =
        com.aurora.chat.data.local.LocalStorage.getChatPrefs(context).getInt("animation_mode", 0).coerceIn(0, 2)
    fun set(context: Context, value: Int) {
        com.aurora.chat.data.local.LocalStorage.getChatPrefs(context).edit().putInt("animation_mode", value.coerceIn(0, 2)).apply()
    }
    /** 高级动画：开启后翻看历史滑入视口也播放动画（默认关，更耗性能）。 */
    fun getAdvanced(context: Context): Boolean =
        com.aurora.chat.data.local.LocalStorage.getChatPrefs(context).getBoolean("advanced_anim", false)
    fun setAdvanced(context: Context, on: Boolean) {
        com.aurora.chat.data.local.LocalStorage.getChatPrefs(context).edit().putBoolean("advanced_anim", on).apply()
    }
}

/** 界面切换（全局侧滑返回）设置存储：默认全关，勾选后才生效，不影响普通用户。 */
internal object SwipeBackPrefs {
    private fun p(context: Context) = com.aurora.chat.data.local.LocalStorage.getChatPrefs(context)
    /** 总开关：关闭则侧滑返回完全不启用。 */
    fun enabled(context: Context): Boolean = p(context).getBoolean("swipe_back_enabled", false)
    fun setEnabled(context: Context, v: Boolean) { p(context).edit().putBoolean("swipe_back_enabled", v).apply() }
    /** 整屏可触发（关=仅左边缘，防误触）。 */
    fun fullArea(context: Context): Boolean = p(context).getBoolean("swipe_back_full", false)
    fun setFullArea(context: Context, v: Boolean) { p(context).edit().putBoolean("swipe_back_full", v).apply() }
    /** 斜开透视：拉动时当前页 3D 倾斜露背。 */
    fun tilt(context: Context): Boolean = p(context).getBoolean("swipe_back_tilt", true)
    fun setTilt(context: Context, v: Boolean) { p(context).edit().putBoolean("swipe_back_tilt", v).apply() }
    /** 背面压暗：露出时背后加暗蒙层。 */
    fun dim(context: Context): Boolean = p(context).getBoolean("swipe_back_dim", true)
    fun setDim(context: Context, v: Boolean) { p(context).edit().putBoolean("swipe_back_dim", v).apply() }
    /** 过阈值震动。 */
    fun haptic(context: Context): Boolean = p(context).getBoolean("swipe_back_haptic", true)
    fun setHaptic(context: Context, v: Boolean) { p(context).edit().putBoolean("swipe_back_haptic", v).apply() }
    /** 返回阈值（滑过屏宽的比例）。 */
    fun threshold(context: Context): Float = p(context).getFloat("swipe_back_threshold", 0.28f).coerceIn(0.15f, 0.6f)
    fun setThreshold(context: Context, v: Float) { p(context).edit().putFloat("swipe_back_threshold", v.coerceIn(0.15f, 0.6f)).apply() }
}

/** 消息设置菜单：气泡设置 / 消息动画 / 输入框外观（预留更多行，后续消息相关设置都往这里加）。 */
@Composable
private fun MessageSettingScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf(0) } // 0=菜单 1=气泡设置 2=消息动画 3=界面切换设置 4=已读显示 5=输入框设置 6=头像显示
    // 层与层之间同样走左右滑动：进入子页从右侧滑入，返回菜单时反向滑回
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState > initialState) {
                (slideInHorizontally(tween(260)) { it } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it / 4 } + fadeOut(tween(120)))
            } else {
                (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(260)) { it } + fadeOut(tween(120)))
            }
        },
        label = "messageSettingPage"
    ) { p ->
        when (p) {
            1 -> BubbleSettingScreen(onBack = { page = 0 })
            2 -> MessageAnimScreen(onBack = { page = 0 })
            3 -> SwitchScreen(onBack = { page = 0 })
            4 -> ReadReceiptScreen(onBack = { page = 0 })
            5 -> InputBarStyleScreen(onBack = { page = 0 })
            6 -> AvatarStyleScreen(onBack = { page = 0 })
            else -> MessageSettingsMenu(
                onBack = onBack,
                onOpenBubble = { page = 1 },
                onOpenAnim = { page = 2 },
                onOpenSwitch = { page = 3 },
                onOpenReadReceipt = { page = 4 },
                onOpenInputBar = { page = 5 },
                onOpenAvatar = { page = 6 }
            )
        }
    }
}

@Composable
private fun MessageSettingsMenu(
    onBack: () -> Unit,
    onOpenBubble: () -> Unit,
    onOpenAnim: () -> Unit,
    onOpenSwitch: () -> Unit,
    onOpenReadReceipt: () -> Unit,
    onOpenInputBar: () -> Unit,
    onOpenAvatar: () -> Unit
) {
    val context = LocalContext.current
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        SettingsTopBar(title = "消息设置", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            val curMy = BubbleColorStorage.myBubbleColor(context)
            val curOther = BubbleColorStorage.otherBubbleColor(context)
            MessageMenuRow("气泡设置", "${BubbleColorStorage.presetName(curMy)} · ${BubbleColorStorage.presetName(curOther)} · ${BubbleRadiusStorage.label(context)}", onOpenBubble)
            val animLabel = AnimationMode.entries[MessageAnimStorage.get(context)].label
            MessageMenuRow("消息动画", animLabel, onOpenAnim)
            MessageMenuRow("头像显示", AvatarStyleStorage.label(context), onOpenAvatar)
            MessageMenuRow("界面切换设置", if (SwipeBackPrefs.enabled(context)) "侧滑返回已开启" else "侧滑返回（默认关）", onOpenSwitch)
            MessageMenuRow(
                "已读显示",
                com.aurora.chat.data.local.ReadReceiptPrefs.label(context),
                onOpenReadReceipt
            )
            MessageMenuRow(
                "输入框设置",
                com.aurora.chat.data.local.InputBarStylePrefs.label(
                    com.aurora.chat.data.local.InputBarStylePrefs.get(context)
                ),
                onOpenInputBar
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MessageMenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
        Text("›", fontSize = 20.sp, color = com.aurora.chat.ui.theme.appTextHint())
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
}

/**
 * 已读显示：默认维持现状（已读/未读只能在长按 →「消息信息」里看），
 * 也可以开启成气泡下方一行浅灰小字，或气泡左侧（与气泡垂直居中）。
 * 选中即保存，并同步服务端（重装/换机不丢）。
 */
@Composable
private fun ReadReceiptScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember {
        mutableStateOf(com.aurora.chat.data.local.ReadReceiptPrefs.get(context))
    }
    fun choose(v: Int) {
        mode = v
        com.aurora.chat.data.local.ReadReceiptPrefs.set(context, v)
        com.aurora.chat.data.local.ClientPrefsSync.schedulePush(context, immediate = true)
    }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        SettingsTopBar(title = "已读显示", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(4.dp))
            ReadReceiptOption(
                title = "默认设置",
                subtitle = "已读状态只在消息信息里查看",
                selected = mode == com.aurora.chat.data.local.ReadReceiptPrefs.MODE_INFO_ONLY
            ) { choose(com.aurora.chat.data.local.ReadReceiptPrefs.MODE_INFO_ONLY) }
            ReadReceiptOption(
                title = "气泡下方显示",
                subtitle = "私聊显示已读或未读，群聊显示已读人数",
                selected = mode == com.aurora.chat.data.local.ReadReceiptPrefs.MODE_BUBBLE
            ) { choose(com.aurora.chat.data.local.ReadReceiptPrefs.MODE_BUBBLE) }
            ReadReceiptOption(
                title = "气泡左侧",
                subtitle = "已读状态显示在气泡左侧，与气泡垂直居中",
                selected = mode == com.aurora.chat.data.local.ReadReceiptPrefs.MODE_BUBBLE_LEFT
            ) { choose(com.aurora.chat.data.local.ReadReceiptPrefs.MODE_BUBBLE_LEFT) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReadReceiptOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint(), modifier = Modifier.padding(top = 2.dp))
        }
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
}

/** 界面切换设置：全局侧滑返回（跟手右滑露背、松手过阈值返回、没过弹回）。默认全关，勾选才生效。 */
@Composable
private fun SwitchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SwipeBackPrefs.enabled(context)) }
    var dim by remember { mutableStateOf(SwipeBackPrefs.dim(context)) }
    var haptic by remember { mutableStateOf(SwipeBackPrefs.haptic(context)) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        SettingsTopBar(title = "界面切换设置", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("侧滑返回：跟手右滑露出背面，松手滑过阈值返回、没过则弹回。默认关闭，勾选后生效。", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            SwitchCheckRow("启用侧滑返回", "总开关，关闭后完全不启用", enabled) { enabled = it; SwipeBackPrefs.setEnabled(context, it) }
            SwitchCheckRow("背面压暗", "露出时背后加暗色蒙层", dim) { dim = it; SwipeBackPrefs.setDim(context, it) }
            SwitchCheckRow("过阈值震动", "松手判定返回时震一下", haptic) { haptic = it; SwipeBackPrefs.setHaptic(context, it) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchCheckRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
        }
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
}

/** 消息动画选择：无 / 左右滑出 / 右下滑出（默认右下滑出）。 */
@Composable
private fun MessageAnimScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var sel by remember { mutableStateOf(MessageAnimStorage.get(context)) }
    var adv by remember { mutableStateOf(MessageAnimStorage.getAdvanced(context)) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        SettingsTopBar(title = "消息动画", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("新消息的滑入动画；开启下方「高级动画」后，翻看历史滑入也会播放。", fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary(), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            AnimationMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { sel = mode.value; MessageAnimStorage.set(context, mode.value) }.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(mode.label, fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
                    if (mode.value == sel) Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            }
            // ── 高级动画：开启后翻看历史滑入也播放 ──
            Row(
                modifier = Modifier.fillMaxWidth().clickable { adv = !adv; MessageAnimStorage.setAdvanced(context, adv) }.padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("高级动画", fontSize = 16.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium)
                    Text("翻看历史滑入也播放（更耗性能）", fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint())
                }
                androidx.compose.material3.Checkbox(checked = adv, onCheckedChange = null)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 输入框设置：五种固定外观 + 自定义，选中即写入，返回聊天页立刻生效。
 * 每行左侧是真实渲染的缩略预览（与聊天页共用同一份 inputBarLook），不是示意图。
 * 选中「自定义」时，列表下方展开编辑器（圆角 / 三种颜色），预览行实时跟着变。
 */
@Composable
private fun InputBarStyleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = com.aurora.chat.data.local.InputBarStylePrefs
    var sel by remember { mutableStateOf(prefs.get(context)) }
    var custom by remember { mutableStateOf(prefs.custom(context)) }
    var customDirty by remember { mutableStateOf(false) }

    fun push() {
        // 外观也跟随账号：换机/重装后自动还原
        com.aurora.chat.data.local.ClientPrefsSync.schedulePush(context, immediate = true)
    }

    fun applyCustom(c: com.aurora.chat.data.local.InputBarStylePrefs.Custom) {
        custom = c
        prefs.setCustom(context, c)
        customDirty = true
    }

    // 拖圆角滑块时每一帧都会改参数：本地即时写，上传做 600ms 防抖，
    // 否则一次拖动就是几十个请求打上去。
    LaunchedEffect(customDirty, custom) {
        if (!customDirty) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        push()
        customDirty = false
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        SettingsTopBar(title = "输入框设置", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(4.dp))
            prefs.ALL.forEach { style ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            sel = style
                            prefs.set(context, style)
                            push()
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InputBarStylePreview(
                        style,
                        if (style == prefs.CUSTOM) custom else null
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        prefs.label(style),
                        fontSize = 16.sp,
                        color = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.weight(1f)
                    )
                    if (style == sel) {
                        Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
            }
            // 只有选中「自定义」才展开参数区：不选就不占地方，页面上也不多一堆看不出作用的控件
            if (sel == prefs.CUSTOM) {
                Spacer(Modifier.height(18.dp))
                InputBarCustomEditor(custom, onCustom = { applyCustom(it) })
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── 自定义样式的可选色板（都是浅色底，输入框上的深灰文字在上面都读得清）──
// ARGB 字面量带 0xFF alpha 会超出 Int 正数范围，Kotlin 推断成 Long；
// 显式 .toInt() 按补码解释（0xFFFFFFFF -> -1 -> 不透明白），与 Int 型 barColor/签名对齐。
// 这是模块化重构遗留的类型不匹配，不改任何颜色值或行为。
private val BarBgPalette = listOf(
    0xFFFFFFFF.toInt(), 0xFFF9FAFB.toInt(), 0xFFF5F6F8.toInt(), 0xFFF2F3F5.toInt(),
    0xFFEDEFF2.toInt(), 0xFFE5E7EB.toInt(), 0xFFF1F5F9.toInt(), 0xFFEFF6FF.toInt(),
    0xFFF0FDF4.toInt(), 0xFFFEF7EC.toInt(), 0xFFFDF2F8.toInt(), 0xFFF5F3FF.toInt()
)

private val BarBorderPalette = listOf(
    0xFFE5E7EB.toInt(), 0xFFD1D5DB.toInt(), 0xFF9CA3AF.toInt(), 0xFF6B7280.toInt(), 0xFF1F2937.toInt(),
    0xFF1E40AF.toInt(), 0xFF059669.toInt(), 0xFFD97706.toInt(), 0xFFDC2626.toInt(), 0xFF7C3AED.toInt()
)

/** 自定义编辑器：圆角滑块 + 输入栏底色 + 输入框底色 + 描边开关与颜色。 */
@Composable
private fun InputBarCustomEditor(
    custom: com.aurora.chat.data.local.InputBarStylePrefs.Custom,
    onCustom: (com.aurora.chat.data.local.InputBarStylePrefs.Custom) -> Unit
) {
    // 圆角
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("圆角", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
        Text("${custom.radius}dp", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
    }
    androidx.compose.material3.Slider(
        value = custom.radius.toFloat(),
        onValueChange = { onCustom(custom.copy(radius = it.roundToInt())) },
        valueRange = 0f..com.aurora.chat.data.local.InputBarStylePrefs.RADIUS_MAX.toFloat(),
        steps = com.aurora.chat.data.local.InputBarStylePrefs.RADIUS_MAX - 1,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = Color(0xFF1E40AF),
            activeTrackColor = Color(0xFF1E40AF),
            inactiveTrackColor = com.aurora.chat.ui.theme.appDivider()
        ),
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(10.dp))
    InputBarSwatchGroup(
        title = "输入栏底色",
        colors = BarBgPalette,
        current = custom.barColor,
        onPick = { onCustom(custom.copy(barColor = it)) }
    )
    InputBarSwatchGroup(
        title = "输入框底色",
        colors = BarBgPalette,
        current = custom.fieldColor,
        onPick = { onCustom(custom.copy(fieldColor = it)) }
    )

    // 描边开关：默认关（微信 / QQ 都没有那圈线）
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("描边", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(CircleShape)
                .background(if (custom.borderOn) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appDivider())
                .clickable { onCustom(custom.copy(borderOn = !custom.borderOn)) }
                .padding(3.dp)
        ) {
            Box(
                Modifier
                    .align(if (custom.borderOn) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(com.aurora.chat.ui.theme.appSurface())
            )
        }
    }

    if (custom.borderOn) {
        InputBarSwatchGroup(
            title = "描边颜色",
            colors = BarBorderPalette,
            current = custom.borderColor,
            onPick = { onCustom(custom.copy(borderColor = it)) }
        )
    }
}

/** 一排色块（标题 + 6 列网格），选中的加一圈蓝框。 */
@Composable
private fun InputBarSwatchGroup(
    title: String,
    colors: List<Int>,
    current: Int,
    onPick: (Int) -> Unit
) {
    Text(title, fontSize = 13.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
    Spacer(Modifier.height(8.dp))
    colors.chunked(6).forEach { rowColors ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            rowColors.forEach { argb ->
                val isSel = argb == current
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(argb))
                        .border(0.5.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp))
                        .then(
                            if (isSel) Modifier.border(2.dp, Color(0xFF1E40AF), RoundedCornerShape(8.dp))
                            else Modifier
                        )
                        .clickable { onPick(argb) }
                )
            }
        }
    }
}

/** 缩略预览：外层铺输入栏底色，内层就是输入框本体（圆角按预览尺寸折半）。 */
@Composable
private fun InputBarStylePreview(style: Int, custom: com.aurora.chat.data.local.InputBarStylePrefs.Custom? = null) {
    val look = com.aurora.chat.ui.chat.inputBarLook(
        style,
        custom = custom ?: com.aurora.chat.data.local.InputBarStylePrefs.DEFAULT_CUSTOM
    )
    val previewShape = RoundedCornerShape(look.radius / 2f)
    val previewBorder = look.border
    Box(
        modifier = Modifier
            .size(width = 108.dp, height = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            // 预览窗自身的边界：白底样式放在白页面上，否则看不出这块是预览
            .background(look.barBg)
            .border(0.5.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(previewShape)
                .background(look.fieldBg)
                .then(
                    if (previewBorder != null) {
                        Modifier.border(look.borderWidth, previewBorder, previewShape)
                    } else Modifier
                )
        )
    }
}

// ===================== 气泡设置：颜色 / 圆角 两个分类 =====================

/**
 * 气泡设置。页面上按「颜色」「圆角」两类分开：
 * - 颜色：预设色板 + **自定义色值**（HEX 输入 / 拖动取色，不再只能用几个预设）；
 * - 圆角：默认（8dp，比原来的 12dp 收一圈）/ 经典（12dp，原样）/ 自定义 0~24dp。
 * 所有调整即时生效并即时写本地，返回不需要再点保存。
 */
@Composable
private fun BubbleSettingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }                 // 0=颜色 1=圆角
    var myColor by remember { mutableStateOf(BubbleColorStorage.myBubbleColor(context)) }
    var otherColor by remember { mutableStateOf(BubbleColorStorage.otherBubbleColor(context)) }
    var pickerSide by remember { mutableStateOf(-1) }          // -1 收起 / 0 我方 / 1 对方
    var radiusMode by remember { mutableStateOf(BubbleRadiusStorage.mode(context)) }
    var radiusCustom by remember { mutableStateOf(BubbleRadiusStorage.customDp(context)) }
    var tailOn by remember { mutableStateOf(BubbleTailStorage.enabled(context)) }

    fun pickMy(c: Color) {
        myColor = c
        BubbleColorStorage.setMyBubbleColor(context, c)
    }
    fun pickOther(c: Color) {
        otherColor = c
        BubbleColorStorage.setOtherBubbleColor(context, c)
    }
    fun pickRadiusMode(mode: Int) {
        radiusMode = mode
        BubbleRadiusStorage.setMode(context, mode)
    }

    val corner = when (radiusMode) {
        BubbleRadiusStorage.MODE_CLASSIC -> BubbleRadiusStorage.CLASSIC_DP.dp
        BubbleRadiusStorage.MODE_CUSTOM -> radiusCustom.dp
        else -> BubbleRadiusStorage.DEFAULT_DP.dp
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        SettingsTopBar(title = "气泡设置", onBack = onBack)
        // 两个分类切换
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SegmentPill("颜色", tab == 0) { tab = 0 }
            SegmentPill("圆角", tab == 1) { tab = 1 }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            if (tab == 0) {
                BubbleColorBlock(
                    title = "我方气泡",
                    color = myColor,
                    corner = corner,
                    pickerOpen = pickerSide == 0,
                    onTogglePicker = { pickerSide = if (pickerSide == 0) -1 else 0 },
                    onPick = { pickMy(it) }
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
                BubbleColorBlock(
                    title = "对方气泡",
                    color = otherColor,
                    corner = corner,
                    pickerOpen = pickerSide == 1,
                    onTogglePicker = { pickerSide = if (pickerSide == 1) -1 else 1 },
                    onPick = { pickOther(it) }
                )

                // ── 恢复默认（原厂设置）──
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(com.aurora.chat.ui.theme.appCardBg())
                        .clickable {
                            pickMy(BubbleColorStorage.DEFAULT)
                            pickOther(BubbleColorStorage.DEFAULT_OTHER)
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("恢复默认配色（品牌深蓝 / 白色）", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary())
                }
            } else {
                Text(
                    "气泡圆角",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
                StyleOptionRow("默认圆角（8dp）", radiusMode == BubbleRadiusStorage.MODE_DEFAULT) {
                    pickRadiusMode(BubbleRadiusStorage.MODE_DEFAULT)
                }
                StyleOptionRow("经典圆角（12dp）", radiusMode == BubbleRadiusStorage.MODE_CLASSIC) {
                    pickRadiusMode(BubbleRadiusStorage.MODE_CLASSIC)
                }
                StyleOptionRow("自定义", radiusMode == BubbleRadiusStorage.MODE_CUSTOM) {
                    pickRadiusMode(BubbleRadiusStorage.MODE_CUSTOM)
                }

                if (radiusMode == BubbleRadiusStorage.MODE_CUSTOM) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("圆角", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
                        Text("${radiusCustom.roundToInt()}dp", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                    }
                    androidx.compose.material3.Slider(
                        value = radiusCustom,
                        onValueChange = { radiusCustom = it },
                        onValueChangeFinished = { BubbleRadiusStorage.setCustomDp(context, radiusCustom) },
                        valueRange = BubbleRadiusStorage.CUSTOM_MIN..BubbleRadiusStorage.CUSTOM_MAX,
                        steps = (BubbleRadiusStorage.CUSTOM_MAX - BubbleRadiusStorage.CUSTOM_MIN - 1f).roundToInt(),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = Color(0xFF1E40AF),
                            activeTrackColor = Color(0xFF1E40AF),
                            inactiveTrackColor = com.aurora.chat.ui.theme.appDivider()
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── 气泡凸起（勾选框，默认关）──
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            tailOn = !tailOn
                            BubbleTailStorage.setEnabled(context, tailOn)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = tailOn,
                        onCheckedChange = { checked ->
                            tailOn = checked
                            BubbleTailStorage.setEnabled(context, checked)
                        },
                        colors = androidx.compose.material3.CheckboxDefaults.colors(
                            checkedColor = Color(0xFF1E40AF),
                            uncheckedColor = com.aurora.chat.ui.theme.appTextHint()
                        )
                    )
                    Text(
                        "气泡凸起",
                        fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(18.dp))
                Text("预览", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                    modifier = Modifier.padding(bottom = 8.dp))
                Box(
                    Modifier.fillMaxWidth().bubbleBackground(myColor, corner = corner)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        "这是一条我发出的消息示例",
                        fontSize = 15.sp,
                        color = BubbleColorStorage.bubbleTextColor(myColor),
                        lineHeight = 22.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().bubbleBackground(otherColor, corner = corner)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        "这是对方发来的消息示例",
                        fontSize = 15.sp,
                        color = BubbleColorStorage.bubbleTextColor(otherColor),
                        lineHeight = 22.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 分类切换用的胶囊按钮。 */
@Composable
private fun SegmentPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color(0xFF1E40AF) else com.aurora.chat.ui.theme.appCardBg())
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color.White else com.aurora.chat.ui.theme.appTextSecondary()
        )
    }
}

/**
 * 单侧气泡配色块：预览 + 预设色板 + 自定义色值。
 *
 * 「自定义颜色」是这次的补足项：原来只能从 9 个预设里挑，现在既能直接输入 HEX 色值，
 * 也能用取色器拖出任意颜色，选中的颜色会实时同步到预览和聊天页。
 */
@Composable
private fun BubbleColorBlock(
    title: String,
    color: Color,
    corner: Dp,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    onPick: (Color) -> Unit
) {
    val dark = BubbleColorStorage.isDark(color)
    val textColor = if (dark) Color.White else com.aurora.chat.ui.theme.appTextPrimary()

    Text(
        title,
        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
    )
    Box(
        Modifier.fillMaxWidth().bubbleBackground(color, corner = corner)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text("这是一条消息示例", fontSize = 15.sp, color = textColor, lineHeight = 22.sp)
    }
    Spacer(Modifier.height(10.dp))

    BubbleColorStorage.PRESETS.forEach { (name, preset) ->
        BubbleColorRow(name, preset, preset.value == color.value) { onPick(preset) }
    }

    // 自定义色值：HEX 输入 + 取色器
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onTogglePicker).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color)
                .border(0.5.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("自定义颜色", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary())
            Text(
                "当前 ${String.format(java.util.Locale.US, "#%06X", color.value.toLong() and 0xFFFFFFL)}",
                fontSize = 12.sp, color = com.aurora.chat.ui.theme.appTextHint()
            )
        }
        Text(if (pickerOpen) "收起" else "展开", fontSize = 13.sp, color = Color(0xFF1E40AF))
    }
    if (pickerOpen) {
        CustomColorPicker(current = color, onPick = onPick)
        Spacer(Modifier.height(10.dp))
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
}

/** 预设色一行（色块 + 名称 + 勾选）。 */
@Composable
private fun BubbleColorRow(name: String, color: Color, isSel: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(color)
                .border(0.5.dp, com.aurora.chat.ui.theme.appDivider(), RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Text(name, fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
        if (isSel) Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
}

/**
 * 取色器包装：气泡色是 [Color]（ARGB 打包在 ULong 低 32 位），
 * 取色器吃 Long ARGB，这里做一次双向转换。
 */
@Composable
private fun CustomColorPicker(current: Color, onPick: (Color) -> Unit) {
    com.aurora.chat.ui.custom.ColorPrecisionPicker(
        current = current.value.toLong() and 0xFFFFFFFFL,
        onPick = { argb -> onPick(Color((argb or 0xFF000000L).toInt())) },
        onGestureStart = {},
        // 气泡底色必须不透明：否则半透明气泡会透出壁纸，alpha=0 还会被当成「液态」玻璃哨兵色
        lockAlpha = true
    )
}

/** 圆角 / 头像风格这类单选行。 */
@Composable
private fun StyleOptionRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))
}

// ===================== 头像显示 =====================

/**
 * 头像显示：圆角风格三选一。
 *
 * 这次大轮改动把头像圆角一路收到「边长 1/8」，有人喜欢方的、有人还是习惯原来那个大圆角，
 * 所以给三档：默认小圆角（当前）/ 经典大圆角（改动前）/ 自定义。
 * 圆角按头像边长等比换算，聊天气泡头像、会话列表头像、群头像、AI 头像同时跟着变。
 */
@Composable
private fun AvatarStyleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AvatarStyleStorage.mode(context)) }
    var custom by remember { mutableStateOf(AvatarStyleStorage.customDp(context)) }

    fun apply(m: Int) {
        mode = m
        AvatarStyleStorage.setMode(context, m)
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(com.aurora.chat.ui.theme.appSurface())) {
        SettingsTopBar(title = "头像显示", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Text(
                "预览",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = com.aurora.chat.ui.theme.appTextPrimary(),
                modifier = Modifier.padding(top = 10.dp, bottom = 10.dp)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AvatarSample("我", 38.dp, Color(0xFF3B82F6))
                AvatarSample("友", 38.dp, Color(0xFF10B981))
                AvatarSample("群", 38.dp, Color(0xFFF59E0B))
                AvatarSample("AI", 38.dp, Color(0xFF3E63DD))
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(com.aurora.chat.ui.theme.appDivider()))

            StyleOptionRow("默认小圆角", mode == AvatarStyleStorage.MODE_DEFAULT) {
                apply(AvatarStyleStorage.MODE_DEFAULT)
            }
            StyleOptionRow("经典大圆角", mode == AvatarStyleStorage.MODE_CLASSIC) {
                apply(AvatarStyleStorage.MODE_CLASSIC)
            }
            StyleOptionRow("自定义", mode == AvatarStyleStorage.MODE_CUSTOM) {
                apply(AvatarStyleStorage.MODE_CUSTOM)
            }

            if (mode == AvatarStyleStorage.MODE_CUSTOM) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("圆角", fontSize = 15.sp, color = com.aurora.chat.ui.theme.appTextPrimary(), modifier = Modifier.weight(1f))
                    Text("${custom.roundToInt()}dp", fontSize = 14.sp, color = com.aurora.chat.ui.theme.appTextSecondary())
                }
                androidx.compose.material3.Slider(
                    value = custom,
                    onValueChange = { custom = it },
                    onValueChangeFinished = { AvatarStyleStorage.setCustomDp(context, custom) },
                    valueRange = AvatarStyleStorage.CUSTOM_MIN..AvatarStyleStorage.CUSTOM_MAX,
                    steps = (AvatarStyleStorage.CUSTOM_MAX - AvatarStyleStorage.CUSTOM_MIN - 1f).roundToInt(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color(0xFF1E40AF),
                        activeTrackColor = Color(0xFF1E40AF),
                        inactiveTrackColor = com.aurora.chat.ui.theme.appDivider()
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 头像示例块：跟着当前设置实时变形。 */
@Composable
private fun AvatarSample(label: String, size: Dp, color: Color) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(avatarCornerRadius(size)))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = (size.value * 0.38f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}
