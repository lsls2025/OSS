package com.aurora.chat.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

enum class AnimationMode(val value: Int, val label: String) {
    NONE(0, "无动画"),
    LEFT_RIGHT(1, "左右滑出"),
    BOTTOM_RIGHT(2, "下方滑出")
}

// ===================== 界面设置存储 =====================

object WallpaperStorage {
    private const val PREFS = "aurora_wallpaper"

    fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否全屏模式（覆盖顶部/底部导航栏） */
    fun isFullscreen(context: Context): Boolean =
        getPrefs(context).getBoolean("fullscreen", false)

    fun setFullscreen(context: Context, full: Boolean) {
        getPrefs(context).edit().putBoolean("fullscreen", full).apply()
    }

    /** 是否去除分割线 */
    fun isDividerHidden(context: Context): Boolean =
        getPrefs(context).getBoolean("no_divider", false)

    fun setDividerHidden(context: Context, hide: Boolean) {
        getPrefs(context).edit().putBoolean("no_divider", hide).apply()
    }
}

// ===================== 设置页面 =====================

@Composable
fun SettingsPage(
    currentAnimMode: AnimationMode,
    onAnimModeSelect: (AnimationMode) -> Unit,
    advancedAnim: Boolean,
    onAdvancedToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
    onSwitchAccount: () -> Unit = {},
    isDeveloper: Boolean = false,
    onOpenDeveloperManagement: () -> Unit = {},
    onLogout: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onClearCache: () -> Unit = {},
    onClearData: () -> Unit = {},
    onOpenErrorLog: () -> Unit = {},
    onOpenCrashIntercept: () -> Unit = {},
    currentAccount: String = "",
    onAccountDeleted: () -> Unit = {}
) {
    var showAnimDialog by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBindEmailDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧返回箭头（用户已习惯左上角返回）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "←",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            // 与左侧箭头等宽，使标题真正居中
            Spacer(modifier = Modifier.width(36.dp))
        }

        // 标题与下方第一条选项("消息动画")的间距：收紧，避免标题居中后这块区域显得太空
        Spacer(modifier = Modifier.height(12.dp))

        // ── 消息动画 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAnimDialog = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "消息动画", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(modifier = Modifier.weight(1f))
            val modeLabel = if (currentAnimMode == AnimationMode.NONE) "无动画"
                else "${currentAnimMode.label}${if (advancedAnim) " + 高级" else ""} ›"
            Text(text = modeLabel, fontSize = 14.sp, color = Color(0xFF9CA3AF))
        }

        // ── 界面设置 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showWallpaperDialog = true }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "界面设置", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
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
            Text(text = "绑定邮箱", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (emailVerified) "已绑定" else "未绑定 ›",
                fontSize = 14.sp,
                color = if (emailVerified) Color(0xFF16A34A) else Color(0xFF9CA3AF)
            )
        }

        // ── 分割线 ──
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))

        // ── 终端 ──
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpenTerminal() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "终端", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
        }

        // ── 开发者管理（仅开发者可见，点击直接跳转，不复用旧 UI） ──
        if (isDeveloper) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenDeveloperManagement() }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "开发者管理", fontSize = 16.sp, color = Color(0xFF1F2937))
                Spacer(Modifier.weight(1f))
                Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
            }
        }

    // ── 分割线 ──
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
        // 错误日志
        Row(Modifier.fillMaxWidth().clickable { onOpenErrorLog() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text = "错误日志", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
        }
        // 闪退拦截
        Row(Modifier.fillMaxWidth().clickable { onOpenCrashIntercept() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text = "闪退拦截", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
        // 清理缓存
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClearCache() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "清理缓存", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
        }
        // 清空数据（无分割线）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClearData() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "清空数据", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
        }
        // ── 分割线 ──
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSwitchAccount() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "切换账号", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
        }

        // ── 退出登录 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onLogout() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "退出登录", fontSize = 16.sp, color = Color(0xFF1F2937))
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "›", fontSize = 18.sp, color = Color(0xFF9CA3AF))
        }

        // ── 分割线 ──
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE5E7EB)))
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
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            account = currentAccount,
            onDismiss = { showDeleteDialog = false },
            onSuccess = onAccountDeleted
        )
    }

    if (showAnimDialog) {
        AnimationSettingDialog(
            currentMode = currentAnimMode,
            onSelect = { onAnimModeSelect(it) },
            advancedAnim = advancedAnim,
            onAdvancedToggle = onAdvancedToggle,
            onDismiss = { showAnimDialog = false }
        )
    }

    if (showWallpaperDialog) {
        UiSettingDialog(
            onDismiss = { showWallpaperDialog = false }
        )
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

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(24.dp)
        ) {
            Text("绑定邮箱", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            Spacer(Modifier.height(4.dp))
            Text("绑定后即可解锁全部功能（私信、群聊、社区等）",
                fontSize = 12.sp, color = Color(0xFF6B7280))
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
                        color = if (codeCountdown > 0 || isSendingCode) Color(0xFF6B7280) else Color.White)
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
                Text(if (binding) "绑定中…" else "绑定邮箱", color = Color.White, fontSize = 15.sp)
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("暂不绑定", color = Color(0xFF9CA3AF))
            }
        }
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

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("注销账号", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(8.dp))
                Text("此操作将永久删除该账号的所有数据（含 QQ 号与邮箱），且不可恢复。", fontSize = 13.sp, color = Color(0xFF6B7280))
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
                    Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                        modifier = Modifier.clickable { if (!isLoading) onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 8.dp))
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (password.isBlank()) { errorMsg = "请输入密码"; return@Button }
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
                        },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("确认注销", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// ===================== 界面设置弹窗 =====================

@Composable
private fun UiSettingDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isFull by remember { mutableStateOf(WallpaperStorage.isFullscreen(context)) }
    var noDivider by remember { mutableStateOf(WallpaperStorage.isDividerHidden(context)) }
    var showFullHelp by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text("界面设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            Spacer(Modifier.height(16.dp))

            // ── 全屏切换 ──
            Row(
                modifier = Modifier.fillMaxWidth().clickable { isFull = !isFull },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("全屏", fontSize = 14.sp, color = Color(0xFF1F2937))
                Spacer(Modifier.width(6.dp))
                Text("?", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable { showFullHelp = true })
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isFull) Color(0xFF1E40AF) else Color(0xFFD1D5DB)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isFull) Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 去除分割线 ──
            Row(
                modifier = Modifier.fillMaxWidth().clickable { noDivider = !noDivider },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("去除分割线", fontSize = 14.sp, color = Color(0xFF1F2937))
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (noDivider) Color(0xFF1E40AF) else Color(0xFFD1D5DB)),
                    contentAlignment = Alignment.Center
                ) {
                    if (noDivider) Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            if (showFullHelp) {
                Dialog(onDismissRequest = { showFullHelp = false }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(24.dp)
                    ) {
                        Text("全屏模式", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                        Spacer(Modifier.height(12.dp))
                        Text("开启后界面将铺满整个屏幕，覆盖顶部标题栏和底部导航栏。",
                            fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFF6B7280))
                        Spacer(Modifier.height(20.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("知道了", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                modifier = Modifier.clickable { showFullHelp = false }
                                    .padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 操作按钮 ──
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
                Spacer(Modifier.width(8.dp))
                Text("确认", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                    modifier = Modifier
                        .clickable {
                            WallpaperStorage.setFullscreen(context, isFull)
                            WallpaperStorage.setDividerHidden(context, noDivider)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

// ===================== 动画设置弹窗（不变） =====================

@Composable
private fun AnimationSettingDialog(
    currentMode: AnimationMode,
    onSelect: (AnimationMode) -> Unit,
    advancedAnim: Boolean,
    onAdvancedToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var showAdvancedInfo by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            Text(
                text = "消息动画",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            listOf(AnimationMode.NONE, AnimationMode.LEFT_RIGHT, AnimationMode.BOTTOM_RIGHT).forEach { mode ->
                val isSelected = currentMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = mode.label, fontSize = 15.sp, color = Color(0xFF1F2937), modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Color(0xFF1E40AF) else Color(0xFFD1D5DB)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(1.dp).background(Color(0xFFE5E7EB)))
            Spacer(Modifier.height(4.dp))

            val advancedEnabled = currentMode != AnimationMode.NONE
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (advancedEnabled) Modifier.clickable { onAdvancedToggle(!advancedAnim) } else Modifier)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("高级动画", fontSize = 15.sp, color = if (advancedEnabled) Color(0xFF1F2937) else Color(0xFFD1D5DB))
                Spacer(Modifier.width(6.dp))
                Text("?", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = if (advancedEnabled) Color(0xFF1E40AF) else Color(0xFFD1D5DB),
                    modifier = if (advancedEnabled) Modifier.clickable { showAdvancedInfo = true } else Modifier)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(if (advancedEnabled && advancedAnim) Color(0xFF1E40AF) else Color(0xFFD1D5DB)),
                    contentAlignment = Alignment.Center
                ) {
                    if (advancedEnabled && advancedAnim) Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }

    if (showAdvancedInfo) {
        Dialog(onDismissRequest = { showAdvancedInfo = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(24.dp)
            ) {
                Text("高级动画", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))
                Text("该选项可多选，可配合左右滑出、右下滑出、开启后每次进入对话或查看以往消息都将触发动画。",
                    fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFF6B7280))
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                        modifier = Modifier.clickable { showAdvancedInfo = false }.padding(horizontal = 16.dp, vertical = 8.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("好的", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                        modifier = Modifier.clickable { showAdvancedInfo = false }.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}
