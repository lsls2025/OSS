package com.aurora.chat.ui.components

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import com.aurora.chat.R
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.chat.data.repository.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.Executor

// ==================== 数据持久化 ====================

object SecurityStorage {
    private const val PREFS_SECURITY = "aurora_security"

    fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_SECURITY, Context.MODE_PRIVATE)

    fun isSecurityEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean("security_enabled", false)

    fun setSecurityEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("security_enabled", enabled).apply()
    }

    fun isPasswordSet(context: Context): Boolean =
        getPrefs(context).getBoolean("password_set", false)

    fun setPasswordSet(context: Context, set: Boolean) {
        getPrefs(context).edit().putBoolean("password_set", set).apply()
    }

    fun getPasswordHash(context: Context): String =
        getPrefs(context).getString("password_hash", "") ?: ""

    fun savePassword(context: Context, password: String) {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
        getPrefs(context).edit().putString("password_hash", hash).putBoolean("password_set", true).apply()
    }

    fun isFingerprintEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean("fingerprint_enabled", false)

    fun setFingerprintEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("fingerprint_enabled", enabled).apply()
    }

    fun isGestureEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean("gesture_enabled", false)

    fun setGestureEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("gesture_enabled", enabled).apply()
    }

    fun getGesturePattern(context: Context): String =
        getPrefs(context).getString("gesture_pattern", "") ?: ""

    fun saveGesturePattern(context: Context, pattern: String) {
        getPrefs(context).edit().putString("gesture_pattern", pattern).putBoolean("gesture_enabled", true).apply()
    }

    fun checkPassword(context: Context, input: String): Boolean {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return hash == getPasswordHash(context)
    }

    fun checkGesture(context: Context, pattern: String): Boolean {
        return pattern == getGesturePattern(context)
    }

    fun isMultiVerifyEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean("multi_verify", false)

    fun setMultiVerifyEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("multi_verify", enabled).apply()
    }

    /** 获取已启用方法的数量 */
    fun enabledCount(context: Context): Int {
        var n = 0
        if (isPasswordSet(context)) n++
        if (isFingerprintEnabled(context)) n++
        if (isGestureEnabled(context)) n++
        return n
    }

    /** 是否不显示 QQ 号（兼容旧字段 hide_email） */
    fun isHideQQ(context: Context): Boolean =
        getPrefs(context).getBoolean("hide_qq", false) ||
        getPrefs(context).getBoolean("hide_email", false)

    fun setHideQQ(context: Context, hide: Boolean) {
        getPrefs(context).edit().putBoolean("hide_qq", hide).apply()
    }

    /** 拉我时需经过我同意（默认开启） */
    fun getRequireGroupConsent(context: Context): Boolean =
        getPrefs(context).getBoolean("require_group_consent", true)

    fun setRequireGroupConsent(context: Context, on: Boolean) {
        getPrefs(context).edit().putBoolean("require_group_consent", on).apply()
    }

    /** 不显示邮箱（同步给服务端，好友列表/资料接口会据此掩码） */
    fun isHideEmail(context: Context): Boolean =
        getPrefs(context).getBoolean("hide_email", false)

    fun setHideEmail(context: Context, hide: Boolean) {
        getPrefs(context).edit().putBoolean("hide_email", hide).apply()
    }

    /** 检查设备是否有指纹硬件且已注册（原生 API，无需外部库） */
    fun canUseBiometric(context: Context): Int {
        // BIOMETRIC_SUCCESS=0  BIOMETRIC_ERROR_HW_UNAVAILABLE=1  BIOMETRIC_ERROR_NO_HARDWARE=2  BIOMETRIC_ERROR_NONE_ENROLLED=3
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // API 29+
            val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager ?: return 2
            return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28
            val pm = context.packageManager
            return if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)) {
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                if (keyguard?.isKeyguardSecure == true) 0 else 3
            } else 2
        }
        return 2 // API < 28 = 无硬件
    }

    /** 检查是否已设置锁屏 */
    fun hasDeviceCredential(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager ?: return false
            return bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0
        }
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return keyguard?.isKeyguardSecure == true
    }
}

// ==================== 手势锁 3×3（awaitPointerEvent 逐帧追踪） ====================

@Composable
fun PatternLockView(
    modifier: Modifier = Modifier,
    showResult: Boolean = false,
    correctPattern: String = "",
    onPatternComplete: (String) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dotCR = remember {
        listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1, 0 to 2, 1 to 2, 2 to 2)
    }
    var selected by remember { mutableStateOf(listOf<Int>()) }
    var isWrong by remember { mutableStateOf(false) }
    var boxPx by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(showResult) {
        if (showResult && correctPattern.isNotEmpty()) {
            val expected = correctPattern.split(",").map { it.toIntOrNull() ?: -1 }.filter { it >= 0 }
            if (selected != expected) { isWrong = true; delay(600); selected = emptyList(); isWrong = false }
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .onSizeChanged { boxPx = it }
            .pointerInput(boxPx) {
                if (boxPx.width <= 0 || boxPx.height <= 0) return@pointerInput
                val cellW = boxPx.width.toFloat() / 3f
                val cellH = boxPx.height.toFloat() / 3f
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull() ?: continue
                        if (!ch.pressed) {
                            if (selected.size >= 2) onPatternComplete(selected.joinToString(","))
                            selected = emptyList()
                            ch.consume(); continue
                        }
                        val col = (ch.position.y / cellW).toInt().coerceIn(0, 2)  // y→列(横)
                        val row = (ch.position.x / cellH).toInt().coerceIn(0, 2)  // x→行(纵)
                        val idx = col * 3 + row
                        if (selected.isEmpty()) selected = listOf(idx)
                        else if (idx != selected.last() && idx !in selected) selected = selected + idx
                        ch.consume()
                    }
                }
            }
    ) {
        val cwPx = boxPx.width / 3f
        val chPx = boxPx.height / 3f
        val halfW = cwPx / 2
        val halfH = chPx / 2
        val dotR = minOf(cwPx, chPx) * 0.18f
        val strokePx = dotR * 0.25f

        Canvas(Modifier.fillMaxSize()) {
            val lineC = if (isWrong) Color(0xFFEF4444) else Color(0xFF1E40AF)
            if (selected.size >= 2) {
                val path = Path()
                val f = dotCR[selected[0]]
                path.moveTo(f.first * cwPx + halfW, f.second * chPx + halfH)
                for (i in 1 until selected.size) {
                    val p = dotCR[selected[i]]
                    path.lineTo(p.first * cwPx + halfW, p.second * chPx + halfH)
                }
                drawPath(path, color = lineC, style = Stroke(width = strokePx))
            }
            for (i in 0 until 9) {
                val p = dotCR[i]
                val cx = p.first * cwPx + halfW
                val cy = p.second * chPx + halfH
                val isSel = i in selected
                drawCircle(
                    color = when { isWrong && isSel -> Color(0xFFEF4444); isSel -> Color(0xFF1E40AF); else -> Color(0xFFD1D5DB) },
                    radius = dotR, center = Offset(cx, cy)
                )
                drawCircle(
                    color = if (isSel) Color.White else Color(0xFF9CA3AF),
                    radius = dotR * 0.5f, center = Offset(cx, cy)
                )
            }
        }
    }
}

// ==================== 安全设置面板 ====================

@Composable
fun SecuritySettingsContent(
    currentEmail: String
) {
    val context = LocalContext.current

    var securityEnabled by remember { mutableStateOf(SecurityStorage.isSecurityEnabled(context)) }
    var passwordSet by remember { mutableStateOf(SecurityStorage.isPasswordSet(context)) }
    var fingerprintEnabled by remember { mutableStateOf(SecurityStorage.isFingerprintEnabled(context)) }
    var gestureEnabled by remember { mutableStateOf(SecurityStorage.isGestureEnabled(context)) }

    var expanded by remember { mutableStateOf(securityEnabled) }

    // 弹窗状态
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showGestureDialog by remember { mutableStateOf(false) }
    var showCancelVerify by remember { mutableStateOf<String?>(null) } // 要取消的目标方法
    var showShutDownVerify by remember { mutableStateOf(false) } // 关闭安全防护前需验证所有已启用方法
    var showMultiHelp by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        // ── 安全防护主开关 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (securityEnabled) {
                        // 关闭安全防护 → 需要验证所有已启用方法
                        val hasAny = passwordSet || fingerprintEnabled || gestureEnabled
                        if (hasAny) {
                            showShutDownVerify = true
                        } else {
                            securityEnabled = false
                            SecurityStorage.setSecurityEnabled(context, false)
                            expanded = false
                        }
                    } else {
                        securityEnabled = true
                        SecurityStorage.setSecurityEnabled(context, true)
                        expanded = true
                    }
                }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("安全防护", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
            Spacer(Modifier.weight(1f))
            CheckboxBox(checked = securityEnabled)
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))

            // ── 分割线 ──
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Color(0xFFE5E7EB)))
            Spacer(Modifier.height(8.dp))

            // ── 密码校验 ──
            SecurityOptionRow(
                label = "密码校验",
                checked = passwordSet,
                enabled = securityEnabled,
                onToggle = {
                    if (passwordSet) {
                        showCancelVerify = "password"
                    } else {
                        showPasswordDialog = true
                    }
                }
            )

            // ── 指纹校验 ──
            SecurityOptionRow(
                label = "指纹校验",
                checked = fingerprintEnabled,
                enabled = securityEnabled,
                onToggle = {
                    if (fingerprintEnabled) {
                        showCancelVerify = "fingerprint"
                    } else {
                        val canAuth = SecurityStorage.canUseBiometric(context)
                        when (canAuth) {
                            BiometricManager.BIOMETRIC_SUCCESS -> {
                                launchBiometricAuth(
                                    context = context,
                                    title = "启用指纹校验",
                                    subtitle = "请验证指纹以启用此功能",
                                    onSuccess = {
                                        SecurityStorage.setFingerprintEnabled(context, true)
                                        fingerprintEnabled = true
                                        Toast.makeText(context, "指纹校验已开启", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { _, msg ->
                                        Toast.makeText(context, "指纹验证失败: $msg", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                                Toast.makeText(context, "设备不支持指纹", Toast.LENGTH_SHORT).show()
                            }
                            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                                Toast.makeText(context, "指纹硬件不可用", Toast.LENGTH_SHORT).show()
                            }
                            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                                Toast.makeText(context, "请先前往系统设置添加指纹", Toast.LENGTH_SHORT).show()
                            }
                            else -> Toast.makeText(context, "指纹不可用", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )

            // ── 手势校验 ──
            SecurityOptionRow(
                label = "手势校验",
                checked = gestureEnabled,
                enabled = securityEnabled,
                onToggle = {
                    if (gestureEnabled) {
                        showCancelVerify = "gesture"
                    } else {
                        showGestureDialog = true
                    }
                }
            )

            // ── 多重验证 ──
            var multiVerify by remember { mutableStateOf(SecurityStorage.isMultiVerifyEnabled(context)) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        multiVerify = !multiVerify
                        SecurityStorage.setMultiVerifyEnabled(context, multiVerify)
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("多重验证", fontSize = 15.sp, color = Color(0xFF1F2937))
                Text(
                    "?", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E40AF),
                    modifier = Modifier.clickable { showMultiHelp = true }
                )
                Spacer(Modifier.weight(1f))
                CheckboxBox(checked = multiVerify)
            }
            // ── 安全防护结束 ──
        }

    }

    // ── 弹窗 ──

    if (showPasswordDialog) {
        PasswordSetupDialog(
            onSave = { pwd ->
                SecurityStorage.savePassword(context, pwd)
                passwordSet = true
                showPasswordDialog = false
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            },
            onCancel = { showPasswordDialog = false }
        )
    }

    if (showGestureDialog) {
        GestureSetupDialog(
            onSave = { pattern ->
                SecurityStorage.saveGesturePattern(context, pattern)
                gestureEnabled = true
                showGestureDialog = false
                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            },
            onCancel = { showGestureDialog = false }
        )
    }

    // ── 取消验证链 ──
    showCancelVerify?.let { method ->
        CancelVerificationDialog(
            targetMethod = method,
            currentEmail = currentEmail,
            onVerified = {
                showCancelVerify = null
                when (method) {
                    "password" -> {
                        SecurityStorage.setPasswordSet(context, false)
                        passwordSet = false
                    }
                    "fingerprint" -> {
                        SecurityStorage.setFingerprintEnabled(context, false)
                        fingerprintEnabled = false
                    }
                    "gesture" -> {
                        SecurityStorage.setGestureEnabled(context, false)
                        gestureEnabled = false
                    }
                }
                Toast.makeText(context, "已关闭${when(method){"password"->"密码校验";"fingerprint"->"指纹校验";else->"手势校验"}}", Toast.LENGTH_SHORT).show()
            },
            onCancel = { showCancelVerify = null }
        )
    }

    // ── 关闭安全防护前验证所有已启用方法 ──
    if (showShutDownVerify) {
        SecurityShutDownVerifyDialog(
            currentEmail = currentEmail,
            onAllVerified = {
                showShutDownVerify = false
                securityEnabled = false
                SecurityStorage.setSecurityEnabled(context, false)
                expanded = false
                Toast.makeText(context, "安全防护已关闭", Toast.LENGTH_SHORT).show()
            },
            onCancel = { showShutDownVerify = false }
        )
    }

    // ── 多重验证帮助弹窗 ──
    if (showMultiHelp) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showMultiHelp = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("多重验证", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                Spacer(Modifier.height(12.dp))
                Text(
                    "未勾选时：可多选校验方式，打开应用时只需通过其中任意一种即可。",
                    fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFF6B7280)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "勾选后：可多选校验方式，打开应用时需要按顺序逐一通过所有已选方式。",
                    fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xFF6B7280)
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("知道了", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        color = Color(0xFF1E40AF),
                        modifier = Modifier.clickable { showMultiHelp = false }
                            .padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

// ==================== 隐私设置（独立全屏界面，不再进入安全面板） ====================

@Composable
fun PrivacySettingsPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val privacyScope = rememberCoroutineScope()

    var hideQQ by remember { mutableStateOf(SecurityStorage.isHideQQ(context)) }
    var hideEmail by remember { mutableStateOf(SecurityStorage.isHideEmail(context)) }
    var requireGroupConsent by remember { mutableStateOf(SecurityStorage.getRequireGroupConsent(context)) }

    // 进入时与服务端同步一次当前隐私状态（并落库）
    LaunchedEffect(Unit) {
        ChatRepository.updatePrivacy(hideEmail = hideEmail, hideQQ = hideQQ, requireGroupConsent = requireGroupConsent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(top = 8.dp)
    ) {
        // 标题栏：左侧返回键 + 中央标题（隐藏右侧✕）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧返回键
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
                text = "隐私设置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            // 与左侧返回键等宽，使标题真正居中
            Spacer(modifier = Modifier.width(36.dp))
        }

        // 标题与下方第一个选项的间距：收紧，避免标题居中后这块区域显得太空
        Spacer(Modifier.height(12.dp))

        // ── 不显示 QQ 号 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val newVal = !hideQQ
                    hideQQ = newVal
                    SecurityStorage.setHideQQ(context, newVal)
                    privacyScope.launch {
                        ChatRepository.updatePrivacy(hideQQ = newVal, requireGroupConsent = requireGroupConsent)
                    }
                }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("不显示 QQ 号", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
            Spacer(Modifier.weight(1f))
            CheckboxBox(checked = hideQQ)
        }

        // ── 不显示邮箱 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val newVal = !hideEmail
                    hideEmail = newVal
                    SecurityStorage.setHideEmail(context, newVal)
                    privacyScope.launch {
                        ChatRepository.updatePrivacy(hideEmail = newVal, hideQQ = hideQQ, requireGroupConsent = requireGroupConsent)
                    }
                }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("不显示邮箱", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
            Spacer(Modifier.weight(1f))
            CheckboxBox(checked = hideEmail)
        }

        // ── 拉我入群时需我同意 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val newVal = !requireGroupConsent
                    requireGroupConsent = newVal
                    SecurityStorage.setRequireGroupConsent(context, newVal)
                    privacyScope.launch {
                        ChatRepository.updatePrivacy(hideQQ = hideQQ, requireGroupConsent = newVal)
                    }
                }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("拉我入群时需我同意", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
            Spacer(Modifier.weight(1f))
            CheckboxBox(checked = requireGroupConsent)
        }
    }
}

// ==================== 辅助 UI 组件 ====================

@Composable
private fun CheckboxBox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) Color(0xFF1E40AF) else Color(0xFFD1D5DB)),
        contentAlignment = Alignment.Center
    ) {
        if (checked) Text("✓", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// ==================== 安全选项行 ====================

@Composable
private fun SecurityOptionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val textColor = if (enabled) Color(0xFF1F2937) else Color(0xFFD1D5DB)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onToggle() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, color = textColor)
        Spacer(Modifier.weight(1f))
        CheckboxBox(checked = checked)
    }
}

// ==================== 密码设置弹窗 ====================

@Composable
private fun PasswordSetupDialog(
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text("设置密码", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            Spacer(Modifier.height(16.dp))

            androidx.compose.material3.OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it.take(16) },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it.take(16) },
                label = { Text("确认密码") },
                singleLine = true,
                visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Checkbox(
                    checked = showPwd,
                    onCheckedChange = { showPwd = it }
                )
                Text("显示密码", fontSize = 13.sp, color = Color(0xFF6B7280))
            }

            Spacer(Modifier.height(20.dp))

            // 保存 / 取消 — 纯文字按钮，无底色
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "取消",
                    fontSize = 15.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier
                        .clickable { onCancel() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "保存",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (pwd.isNotBlank() && pwd == confirm) Color(0xFF1E40AF) else Color(0xFFD1D5DB),
                    modifier = Modifier
                        .then(
                            if (pwd.isNotBlank() && pwd == confirm) Modifier.clickable {
                                if (pwd.length >= 4) {
                                    onSave(pwd)
                                } else {
                                    Toast.makeText(context, "密码至少4位", Toast.LENGTH_SHORT).show()
                                }
                            } else Modifier
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// ==================== 手势设置弹窗 ====================

@Composable
private fun GestureSetupDialog(
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(1) } // 1=第一次绘制, 2=确认绘制
    var firstPattern by remember { mutableStateOf("") }
    var showRetry by remember { mutableStateOf(false) }
    var retryPattern by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (step == 1) "绘制手势" else "再次绘制以确认",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937)
            )
            if (showRetry) {
                Text("图案不一致，请重试", fontSize = 13.sp, color = Color(0xFFEF4444))
            }

            Spacer(Modifier.height(12.dp))

            val size = 260.dp
            PatternLockView(
                modifier = Modifier.size(size),
                showResult = retryPattern,
                correctPattern = firstPattern,
                onPatternComplete = { pattern ->
                    if (step == 1) {
                        if (pattern.split(",").size < 3) {
                            Toast.makeText(context, "至少连接3个点", Toast.LENGTH_SHORT).show()
                            return@PatternLockView
                        }
                        firstPattern = pattern
                        step = 2
                        showRetry = false
                    } else {
                        if (pattern == firstPattern) {
                            onSave(pattern)
                        } else {
                            showRetry = true
                            retryPattern = !retryPattern // 触发重绘标记
                            firstPattern = ""
                            step = 1
                        }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "取消",
                    fontSize = 15.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier
                        .clickable { onCancel() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// ==================== 取消验证链弹窗 ====================

@Composable
private fun CancelVerificationDialog(
    targetMethod: String,
    currentEmail: String,
    onVerified: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember { context as? Activity }

    // 状态机步骤
    var currentStep by remember { mutableStateOf(
        when (targetMethod) {
            "password" -> "password"
            "fingerprint" -> "fingerprint"
            "gesture" -> "gesture"
            else -> "done"
        }
    ) }
    var errorMsg by remember { mutableStateOf("") }
    var fpAttempts by remember { mutableStateOf(0) }

    // 密码校验输入
    var pwdInput by remember { mutableStateOf("") }
    // 手势
    var gestureInput by remember { mutableStateOf("") }
    var gestureDone by remember { mutableStateOf(false) }
    // 邮箱
    var emailCode by remember { mutableStateOf("") }
    val expectedCode = remember { (100000..999999).random().toString() }

    if (currentStep == "done") return

    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (currentStep) {
                // ── 密码验证 ──
                "password" -> {
                    Text("请输入密码", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    if (errorMsg.isNotEmpty()) {
                        Text(errorMsg, fontSize = 13.sp, color = Color(0xFFEF4444))
                    }
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = pwdInput,
                        onValueChange = { pwdInput = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                            modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("确认", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable {
                                if (SecurityStorage.checkPassword(context, pwdInput)) {
                                    currentStep = "done"
                                    onVerified()
                                } else {
                                    errorMsg = "密码错误"
                                    // 跳转到下一方式
                                    currentStep = "email"
                                    errorMsg = "忘记密码? 使用邮箱验证"
                                }
                            }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }

                // ── 指纹验证 ──
                "fingerprint" -> {
                    Text("指纹验证", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    if (errorMsg.isNotEmpty()) {
                        Text(errorMsg, fontSize = 13.sp, color = Color(0xFFEF4444))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("请按指纹设备以验证身份", fontSize = 14.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(16.dp))

                    // 触发指纹
                    LaunchedEffect(Unit) {
                        launchBiometricAuth(
                            context = context,
                            title = "验证指纹",
                            subtitle = "关闭指纹校验需要验证身份",
                            onSuccess = {
                                currentStep = "done"
                                onVerified()
                            },
                            onError = { _, msg ->
                                fpAttempts++
                                if (fpAttempts >= 2) {
                                    errorMsg = "不是本人?"
                                    // 转手势
                                    if (SecurityStorage.isGestureEnabled(context)) {
                                        currentStep = "gesture"
                                    } else if (SecurityStorage.isPasswordSet(context)) {
                                        currentStep = "password"
                                        errorMsg = "忘记手势? 使用密码"
                                    } else {
                                        currentStep = "email"
                                        errorMsg = "忘记手势? 使用邮箱验证"
                                    }
                                } else {
                                    errorMsg = "验证失败，请重试 ($msg)"
                                }
                            },
                            onFailed = {
                                fpAttempts++
                                if (fpAttempts >= 2) {
                                    errorMsg = "不是本人?"
                                    if (SecurityStorage.isGestureEnabled(context)) {
                                        currentStep = "gesture"
                                    } else if (SecurityStorage.isPasswordSet(context)) {
                                        currentStep = "password"
                                    } else {
                                        currentStep = "email"
                                    }
                                } else {
                                    errorMsg = "指纹不匹配，请重试"
                                }
                            }
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                            modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }

                // ── 手势验证 ──
                "gesture" -> {
                    Text("手势验证", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    if (errorMsg.isNotEmpty()) {
                        Text(errorMsg, fontSize = 13.sp, color = Color(0xFFEF4444))
                    }
                    Spacer(Modifier.height(12.dp))

                    PatternLockView(
                        modifier = Modifier.size(240.dp),
                        showResult = gestureDone,
                        correctPattern = SecurityStorage.getGesturePattern(context),
                        onPatternComplete = { pattern ->
                            if (SecurityStorage.checkGesture(context, pattern)) {
                                gestureDone = true
                                currentStep = "done"
                                onVerified()
                            } else {
                                gestureDone = true
                                Handler(Looper.getMainLooper()).postDelayed({
                                    errorMsg = "忘记手势?"
                                    // 转到密码
                                    if (SecurityStorage.isPasswordSet(context)) {
                                        currentStep = "password"
                                    } else {
                                        currentStep = "email"
                                    }
                                }, 500)
                            }
                        }
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                            modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }

                // ── 邮箱验证 (最后兜底) ──
                "email" -> {
                    Text("邮箱验证", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                    Text("验证码已发送至 ${currentEmail.take(3)}****", fontSize = 13.sp, color = Color(0xFF6B7280))
                    if (errorMsg.isNotEmpty() && errorMsg != "密码错误" && !errorMsg.startsWith("忘记")) {
                        Text(errorMsg, fontSize = 13.sp, color = Color(0xFFEF4444))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("演示验证码: $expectedCode", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = emailCode,
                        onValueChange = { emailCode = it.take(6) },
                        label = { Text("验证码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                            modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("确认", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable {
                                if (emailCode == expectedCode) {
                                    currentStep = "done"
                                    onVerified()
                                } else {
                                    errorMsg = "验证码错误"
                                }
                            }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

// ==================== 应用解锁界面（支持多重验证） ====================

@Composable
fun AppUnlockScreen(
    currentEmail: String,
    onUnlock: () -> Unit
) {
    val context = LocalContext.current

    val hasPassword = SecurityStorage.isPasswordSet(context)
    val hasFingerprint = SecurityStorage.isFingerprintEnabled(context)
    val hasGesture = SecurityStorage.isGestureEnabled(context)
    val isMulti = SecurityStorage.isMultiVerifyEnabled(context)

    // 多重验证顺序：获取所有启用方法
    val verifyOrder = remember {
        buildList {
            if (hasPassword) add("password")
            if (hasFingerprint) add("fingerprint")
            if (hasGesture) add("gesture")
        }
    }

    var step by remember { mutableStateOf(0) } // 0=选择方式(单)/自动开始(多), 1=验证中, 2=完成
    var currentMethod by remember { mutableStateOf("") }
    var waitingFp by remember { mutableStateOf(false) }
    var verifiedMethods by remember { mutableStateOf(setOf<String>()) }
    var multiStepIndex by remember { mutableStateOf(0) }

    // 没有任何验证方式，直接解锁
    LaunchedEffect(Unit) {
        if (!hasPassword && !hasFingerprint && !hasGesture) onUnlock()
        // 多重验证：直接开始第一个
        if (isMulti && verifyOrder.isNotEmpty()) {
            currentMethod = verifyOrder[0]
            multiStepIndex = 0
            step = 1
            if (verifyOrder[0] == "fingerprint") waitingFp = true
        }
    }

    if (step == 2) return

    /** 当前方法验证成功后的处理 */
    fun onMethodVerified() {
        val label = when (currentMethod) {
            "password" -> "密码"; "fingerprint" -> "指纹"; "gesture" -> "手势"; else -> ""
        }
        Toast.makeText(context, "${label}校验通过", Toast.LENGTH_SHORT).show()
        if (isMulti) {
            verifiedMethods = verifiedMethods + currentMethod
            val nextIdx = multiStepIndex + 1
            if (nextIdx < verifyOrder.size) {
                multiStepIndex = nextIdx
                currentMethod = verifyOrder[nextIdx]
                waitingFp = verifyOrder[nextIdx] == "fingerprint"
            } else {
                // 全部通过
                step = 2
                onUnlock()
            }
        } else {
            step = 2
            onUnlock()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        com.aurora.chat.ui.components.EventBlocker()
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Aurora Chat", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
            Spacer(Modifier.height(8.dp))
            Text(
                if (isMulti) "请按序完成所有验证" else "请输入验证方式解锁",
                fontSize = 14.sp, color = Color(0xFF9CA3AF)
            )
            if (isMulti) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "进度: ${verifiedMethods.size}/${verifyOrder.size}",
                    fontSize = 12.sp, color = Color(0xFF1E40AF)
                )
            }
            Spacer(Modifier.height(32.dp))

            if (step == 0 && !isMulti) {
                // 单选模式：选择验证方式
                if (hasPassword) { UnlockButton("密码校验") { currentMethod = "password"; step = 1 }; Spacer(Modifier.height(12.dp)) }
                if (hasFingerprint) { UnlockButton("指纹校验") { currentMethod = "fingerprint"; step = 1; waitingFp = true }; Spacer(Modifier.height(12.dp)) }
                if (hasGesture) { UnlockButton("手势校验") { currentMethod = "gesture"; step = 1 } }
            }

            if (step == 1 && currentMethod.isNotEmpty()) {
                // 显示当前验证进度
                if (isMulti) {
                    Text(
                        getMethodLabel(currentMethod),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7280)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                when (currentMethod) {
                    "password" -> {
                        var pwd by remember { mutableStateOf("") }
                        var err by remember { mutableStateOf("") }
                        Text("请输入密码", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                        if (err.isNotEmpty()) Text(err, fontSize = 13.sp, color = Color(0xFFEF4444))
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = pwd, onValueChange = { pwd = it },
                            label = { Text("密码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("确认", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable {
                                if (SecurityStorage.checkPassword(context, pwd)) { onMethodVerified() }
                                else { err = "密码错误" }
                            }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    "fingerprint" -> {
                        Text("指纹校验", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                        Spacer(Modifier.height(12.dp))
                        Text("请按压指纹传感器", fontSize = 14.sp, color = Color(0xFF6B7280))
                        if (waitingFp) {
                            LaunchedEffect(Unit) {
                                launchBiometricAuth(
                                    context = context, title = "解锁 Aurora", subtitle = "验证指纹以进入",
                                    onSuccess = { waitingFp = false; onMethodVerified() },
                                    onError = { _, msg -> waitingFp = false; Toast.makeText(context, "验证失败: $msg", Toast.LENGTH_SHORT).show(); step = if (isMulti) 0 else 0 },
                                    onFailed = { waitingFp = false; Toast.makeText(context, "指纹不匹配", Toast.LENGTH_SHORT).show() }
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (!isMulti) Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF), modifier = Modifier.clickable { step = 0 }.padding(8.dp))
                    }
                    "gesture" -> {
                        Text("手势验证", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                        Spacer(Modifier.height(12.dp))
                        PatternLockView(modifier = Modifier.size(240.dp), onPatternComplete = { pattern ->
                            if (SecurityStorage.checkGesture(context, pattern)) { onMethodVerified() }
                            else { Toast.makeText(context, "手势错误", Toast.LENGTH_SHORT).show() }
                        })
                        Spacer(Modifier.height(12.dp))
                        if (!isMulti) Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF), modifier = Modifier.clickable { step = 0 }.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UnlockButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(max = 200.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF3F4F6))
            .clickable { onClick() }
            .padding(horizontal = 28.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
    }
}

// ==================== 关闭安全防护验证（需全部通过） ====================

@Composable
private fun SecurityShutDownVerifyDialog(
    currentEmail: String,
    onAllVerified: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    // 获取所有已启用方法列表
    val methodList = remember {
        buildList {
            if (SecurityStorage.isPasswordSet(context)) add("password")
            if (SecurityStorage.isFingerprintEnabled(context)) add("fingerprint")
            if (SecurityStorage.isGestureEnabled(context)) add("gesture")
        }
    }

    var stepIndex by remember { mutableStateOf(0) }
    var pwdInput by remember { mutableStateOf("") }
    var pwdError by remember { mutableStateOf("") }
    var gestureInput by remember { mutableStateOf("") }
    var gestureDone by remember { mutableStateOf(false) }
    var waitingFp by remember { mutableStateOf(methodList.firstOrNull() == "fingerprint") }
    var fpError by remember { mutableStateOf("") }
    // 邮箱兜底
    var showEmailFallback by remember { mutableStateOf(false) }
    var emailCode by remember { mutableStateOf("") }
    val expectedCode = remember { (100000..999999).random().toString() }
    var emailError by remember { mutableStateOf("") }

    val currentMethod = remember(stepIndex) { if (stepIndex < methodList.size) methodList[stepIndex] else null }

    fun onStepSucceeded() {
        val nextIdx = stepIndex + 1
        if (nextIdx >= methodList.size) {
            // 全部通过
            onAllVerified()
        } else {
            stepIndex = nextIdx
            waitingFp = methodList[nextIdx] == "fingerprint"
            pwdInput = ""
            pwdError = ""
            gestureInput = ""
            gestureDone = false
            fpError = ""
        }
    }

    if (currentMethod == null) {
        onAllVerified()
        return
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            Text("关闭安全防护", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
            Spacer(Modifier.height(4.dp))
            Text(
                "需验证所有已启用的校验方式 (${stepIndex + 1}/${methodList.size})",
                fontSize = 13.sp, color = Color(0xFF6B7280)
            )
            Spacer(Modifier.height(16.dp))

            when (currentMethod) {
                "password" -> {
                    Text("请输入密码", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                    if (pwdError.isNotEmpty()) {
                        Text(pwdError, fontSize = 13.sp, color = Color(0xFFEF4444))
                    }
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = pwdInput, onValueChange = { pwdInput = it },
                        label = { Text("密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                            modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("确认", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                            modifier = Modifier.clickable {
                                if (SecurityStorage.checkPassword(context, pwdInput)) {
                                    onStepSucceeded()
                                } else {
                                    pwdError = "密码错误"
                                }
                            }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                "fingerprint" -> {
                    Text("指纹校验", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                    if (fpError.isNotEmpty()) {
                        Text(fpError, fontSize = 13.sp, color = Color(0xFFEF4444))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("请按压指纹传感器", fontSize = 14.sp, color = Color(0xFF6B7280))
                    if (waitingFp) {
                        LaunchedEffect(Unit) {
                            launchBiometricAuth(
                                context = context,
                                title = "验证指纹",
                                subtitle = "关闭安全防护需要验证身份",
                                onSuccess = { waitingFp = false; onStepSucceeded() },
                                onError = { _, msg ->
                                    waitingFp = false
                                    fpError = "验证失败: $msg"
                                    // 尝试邮箱兜底
                                    showEmailFallback = true
                                },
                                onFailed = {
                                    waitingFp = false
                                    fpError = "指纹不匹配"
                                    // 尝试邮箱兜底
                                    showEmailFallback = true
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (showEmailFallback) {
                        Text("验证码已发送至 ${currentEmail.take(3)}****", fontSize = 13.sp, color = Color(0xFF6B7280))
                        Text("演示验证码: $expectedCode", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = emailCode, onValueChange = { emailCode = it.take(6) },
                            label = { Text("邮箱验证码") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (emailError.isNotEmpty()) {
                            Text(emailError, fontSize = 13.sp, color = Color(0xFFEF4444))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                                modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("确认", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                modifier = Modifier.clickable {
                                    if (emailCode == expectedCode) {
                                        onStepSucceeded()
                                    } else {
                                        emailError = "验证码错误"
                                    }
                                }.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                                modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
                "gesture" -> {
                    Text("手势验证", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1F2937))
                    Spacer(Modifier.height(12.dp))
                    PatternLockView(
                        modifier = Modifier.size(240.dp),
                        showResult = gestureDone,
                        correctPattern = SecurityStorage.getGesturePattern(context),
                        onPatternComplete = { pattern ->
                            if (SecurityStorage.checkGesture(context, pattern)) {
                                gestureDone = true
                                onStepSucceeded()
                            } else {
                                gestureDone = true
                                Handler(Looper.getMainLooper()).postDelayed({
                                    showEmailFallback = true
                                }, 500)
                            }
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    if (showEmailFallback) {
                        Text("验证码已发送至 ${currentEmail.take(3)}****", fontSize = 13.sp, color = Color(0xFF6B7280))
                        Text("演示验证码: $expectedCode", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = emailCode, onValueChange = { emailCode = it.take(6) },
                            label = { Text("邮箱验证码") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (emailError.isNotEmpty()) {
                            Text(emailError, fontSize = 13.sp, color = Color(0xFFEF4444))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                                modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("确认", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E40AF),
                                modifier = Modifier.clickable {
                                    if (emailCode == expectedCode) {
                                        onStepSucceeded()
                                    } else {
                                        emailError = "验证码错误"
                                    }
                                }.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("取消", fontSize = 15.sp, color = Color(0xFF9CA3AF),
                                modifier = Modifier.clickable { onCancel() }.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

// ==================== 辅助方法 ====================

/** 获取已启用的下一验证方式 */
private fun getNextMethod(context: Context, after: String): String? {
    val order = listOf("password", "fingerprint", "gesture")
    val enabled = mutableListOf<String>()
    if (SecurityStorage.isPasswordSet(context)) enabled.add("password")
    if (SecurityStorage.isFingerprintEnabled(context)) enabled.add("fingerprint")
    if (SecurityStorage.isGestureEnabled(context)) enabled.add("gesture")

    val idx = order.indexOf(after)
    for (i in (idx + 1) until order.size) {
        if (order[i] in enabled) return order[i]
    }
    return null
}

private fun getMethodLabel(method: String): String = when (method) {
    "password" -> "密码校验"
    "fingerprint" -> "指纹校验"
    "gesture" -> "手势校验"
    else -> "邮箱验证"
}

/** 启动指纹验证（使用 Android 原生 API，API 28+ 有效） */
fun launchBiometricAuth(
    context: Context,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (Int, String) -> Unit,
    onFailed: () -> Unit = {}
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        onError(2, "当前设备不支持指纹验证")
        return
    }
    val executor: java.util.concurrent.Executor = ContextCompat.getMainExecutor(context)
    var callbackCalled = false
    val cancelSignal = CancellationSignal()

    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (!callbackCalled) { callbackCalled = true; onSuccess() }
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (!callbackCalled) { callbackCalled = true; onError(errorCode, errString.toString()) }
        }
        override fun onAuthenticationFailed() {
            onFailed()
        }
    }

    val prompt = BiometricPrompt.Builder(context)
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButton("取消", executor) { _, _ ->
            if (!callbackCalled) { callbackCalled = true; onError(0, "用户取消") }
        }
        .build()

    prompt.authenticate(cancelSignal, executor, callback)
}
